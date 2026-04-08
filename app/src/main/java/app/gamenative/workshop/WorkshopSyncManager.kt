package app.gamenative.workshop

import app.gamenative.PrefManager
import app.gamenative.utils.Net
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.depotdownloader.data.PubFileItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EWorkshopFileType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.types.SteamID
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import org.tukaani.xz.LZMAInputStream
import timber.log.Timber

data class WorkshopItemSubscription(
    val publishedFileId: Long,
    val appId: Int,
    val title: String,
    val fileSizeBytes: Long,
    val manifestId: Long,
    val timeUpdated: Long,
    val fileUrl: String = "",
    val fileName: String = "",
    val previewUrl: String = "",
)

data class WorkshopSubscriptionFetchResult(
    val items: List<WorkshopItemSubscription>,
    val succeeded: Boolean,
    val isComplete: Boolean = false,
)

object WorkshopSyncManager {
    private const val TAG = "WorkshopSyncManager"
    private const val PAGE_SIZE = 100
    private const val MAX_PAGES = 50
    private const val COMPLETE_MARKER = ".workshop_complete"
    private const val LOCAL_WORKSHOP_UI_METADATA_FILE = "gamenative_workshop_ui.json"
    private const val HAYDEE_ROUTE_MARKER = ".gamenative_haydee_workshop"
    private const val BROTATO_ROUTE_MARKER = ".gamenative_brotato_workshop"
    private var workshopTypesPatched = false

    fun getWorkshopContentDir(winePrefix: String, appId: Int): File {
        return File(winePrefix, "drive_c/Program Files (x86)/Steam/steamapps/workshop/content/$appId")
    }

    fun getLegacyLocalModsContentDir(containerRootDir: File, appId: Int): File {
        return File(containerRootDir, "workshopmods/content/$appId")
    }

    private fun forEachFileTree(rootDir: File, maxDepth: Int, action: (File) -> Unit) {
        if (!rootDir.isDirectory || maxDepth < 0) return

        fun walk(current: File, depth: Int) {
            action(current)
            if (depth >= maxDepth || !current.isDirectory) return
            current.listFiles()?.forEach { child ->
                walk(child, depth + 1)
            }
        }

        walk(rootDir, 0)
    }

    suspend fun getSubscribedItems(
        appId: Int,
        steamClient: SteamClient,
        steamId: SteamID,
    ): WorkshopSubscriptionFetchResult {
        val unifiedMessages = steamClient.getHandler(SteamUnifiedMessages::class.java)
            ?: return WorkshopSubscriptionFetchResult(emptyList(), succeeded = false)
        val publishedFile = unifiedMessages.createService(PublishedFile::class.java)
        val allItems = mutableListOf<WorkshopItemSubscription>()
        var fetchedAtLeastOnePage = false
        var allPagesSucceeded = false
        var page = 1

        while (page <= MAX_PAGES) {
            val result = fetchSubscribedFilesViaRpc(publishedFile, appId, steamId, page) ?: break
            fetchedAtLeastOnePage = true
            allItems += result.items.map { if (it.appId == 0) it.copy(appId = appId) else it }
            if (result.items.isEmpty() || allItems.size >= result.totalResults) {
                allPagesSucceeded = true
                break
            }
            page++
        }

        return WorkshopSubscriptionFetchResult(allItems, fetchedAtLeastOnePage, allPagesSucceeded)
    }

    fun cleanupUnsubscribedItems(
        subscribedItems: List<WorkshopItemSubscription>,
        workshopContentDir: File,
        preserveIds: Set<Long> = emptySet(),
    ) {
        if (!workshopContentDir.exists()) return
        val subscribedIds = subscribedItems.map { it.publishedFileId }.toSet()
        workshopContentDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.forEach { dir ->
                if (dir.name.toLong() !in subscribedIds && dir.name.toLong() !in preserveIds) {
                    dir.deleteRecursively()
                    File(workshopContentDir, "${dir.name}.partial").deleteRecursively()
                }
            }
        cleanupBrotatoFlattenedMods(subscribedIds, workshopContentDir)
    }

    fun getItemsNeedingSync(
        items: List<WorkshopItemSubscription>,
        workshopContentDir: File,
    ): List<WorkshopItemSubscription> {
        return items.filter { item ->
            if (item.fileUrl.isEmpty() && item.manifestId == 0L) return@filter false
            val itemDir = File(workshopContentDir, item.publishedFileId.toString())
            val partialDir = File(workshopContentDir, "${item.publishedFileId}.partial")
            val completeMarker = File(itemDir, COMPLETE_MARKER)
            if (!completeMarker.exists() || partialDir.exists()) return@filter true
            val savedTimestamp = completeMarker.readText().trim().toLongOrNull()
            savedTimestamp == null || item.timeUpdated > savedTimestamp
        }
    }

    fun updateMarkerTimestamps(
        items: List<WorkshopItemSubscription>,
        workshopContentDir: File,
    ) {
        items.forEach { item ->
            val completeMarker = File(workshopContentDir, "${item.publishedFileId}/$COMPLETE_MARKER")
            if (!completeMarker.exists()) return@forEach
            if (completeMarker.readText().trim().toLongOrNull() == null && item.timeUpdated > 0) {
                completeMarker.writeText(item.timeUpdated.toString())
            }
        }
    }

    fun finalizeDownloadedItems(
        items: List<WorkshopItemSubscription>,
        workshopContentDir: File,
    ) {
        if (!workshopContentDir.exists()) return
        fixItemFileNames(items, workshopContentDir)
        decompressLzmaFiles(workshopContentDir)
        fixFileExtensions(workshopContentDir)
        extractCkmFiles(workshopContentDir)
        flattenBrotatoWorkshopZips(items, workshopContentDir)
    }

    private fun flattenBrotatoWorkshopZips(
        items: List<WorkshopItemSubscription>,
        workshopContentDir: File,
    ) {
        if (items.none { it.appId == 1942280 } && workshopContentDir.name != "1942280") return

        val subscribedIds = items.map { it.publishedFileId }.toSet()
        cleanupBrotatoFlattenedMods(subscribedIds, workshopContentDir)

        workshopContentDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() in subscribedIds }
            ?.forEach { itemDir ->
                val itemId = itemDir.name.toLongOrNull() ?: return@forEach
                itemDir.listFiles()
                    ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
                    ?.forEach { zipFile ->
                        val target = File(workshopContentDir, zipFile.name)
                        runCatching {
                            if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                                if (Files.isSymbolicLink(target.toPath())) {
                                    Files.deleteIfExists(target.toPath())
                                } else {
                                    target.delete()
                                }
                            }
                            Files.createSymbolicLink(target.toPath(), zipFile.toPath().toAbsolutePath().normalize())
                            File(workshopContentDir, "${zipFile.name}.$BROTATO_ROUTE_MARKER").writeText(itemId.toString())
                        }.recoverCatching {
                            zipFile.copyTo(target, overwrite = true)
                            File(workshopContentDir, "${zipFile.name}.$BROTATO_ROUTE_MARKER").writeText(itemId.toString())
                        }.onFailure {
                            Timber.tag(TAG).w(it, "Failed flattening Brotato workshop zip ${zipFile.absolutePath}")
                        }
                    }
            }
    }

    private fun cleanupBrotatoFlattenedMods(
        subscribedIds: Set<Long>,
        workshopContentDir: File,
    ) {
        workshopContentDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".$BROTATO_ROUTE_MARKER") }
            ?.forEach { marker ->
                val itemId = marker.readText().trim().toLongOrNull()
                val baseName = marker.name.removeSuffix(".$BROTATO_ROUTE_MARKER")
                if (itemId == null || itemId !in subscribedIds) {
                    File(workshopContentDir, baseName).let { routed ->
                        if (Files.isSymbolicLink(routed.toPath())) {
                            Files.deleteIfExists(routed.toPath())
                        } else if (routed.exists()) {
                            routed.delete()
                        }
                    }
                    marker.delete()
                }
            }
    }

    fun mergeLocalModsIntoContentDir(localModsContentDir: File, workshopContentDir: File) {
        if (!localModsContentDir.exists()) return
        val localItemDirs = localModsContentDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?: return
        if (localItemDirs.isEmpty()) return

        workshopContentDir.mkdirs()
        localItemDirs.forEach { localDir ->
            val hasContent = localDir.listFiles()?.any { file ->
                !file.name.startsWith(".") && (file.isFile || file.isDirectory)
            } == true
            if (!hasContent) return@forEach

            val target = File(workshopContentDir, localDir.name)
            if (Files.isSymbolicLink(target.toPath())) {
                target.delete()
            }
            if (target.isDirectory && target.listFiles()?.isNotEmpty() == true) {
                return@forEach
            }
            if (target.isDirectory) {
                target.deleteRecursively()
            }
            runCatching {
                localDir.copyRecursively(target, overwrite = true)
            }.onFailure {
                Timber.tag(TAG).w("Failed to copy local mod ${localDir.name}: ${it.message}")
            }
        }
    }

    fun readModNameFromDir(dir: File): String? {
        val rimworldAbout = File(dir, "About/About.xml")
        if (rimworldAbout.isFile) {
            runCatching {
                val xml = rimworldAbout.readText()
                Regex("<name>(.*?)</name>", RegexOption.IGNORE_CASE)
                    .find(xml)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return it }
        }

        val descriptorMod = File(dir, "descriptor.mod")
        if (descriptorMod.isFile) {
            runCatching {
                val content = descriptorMod.readText()
                Regex("name\\s*=\\s*\"(.*?)\"", RegexOption.IGNORE_CASE)
                    .find(content)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return it }
        }

        val modCpp = File(dir, "mod.cpp")
        if (modCpp.isFile) {
            runCatching {
                val content = modCpp.readText()
                Regex("name\\s*=\\s*\"(.*?)\";", RegexOption.IGNORE_CASE)
                    .find(content)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return it }
        }

        val addonJson = File(dir, "addon.json")
        if (addonJson.isFile) {
            runCatching {
                val json = JSONObject(addonJson.readText())
                json.optString("title").ifBlank { json.optString("name") }
                    .takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return it }
        }

        val modTxt = File(dir, "mod.txt")
        if (modTxt.isFile) {
            runCatching {
                modTxt.bufferedReader().use { it.readLine() }
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return it }
        }

        val metadataJson = File(dir, "metadata.json")
        if (metadataJson.isFile) {
            runCatching {
                val json = JSONObject(metadataJson.readText())
                json.optString("name").ifBlank { json.optString("title") }
                    .takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return it }
        }

        return null
    }

    suspend fun downloadItems(
        items: List<WorkshopItemSubscription>,
        steamClient: SteamClient,
        licenses: List<License>,
        workshopContentDir: File,
        onItemProgress: (completed: Int, total: Int, currentTitle: String) -> Unit,
        onBytesProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Int = coroutineScope {
        if (items.isEmpty()) return@coroutineScope 0
        workshopContentDir.mkdirs()

        val totalItems = items.size
        val completedCount = AtomicInteger(0)
        val fixedTotalBytes = items.sumOf { it.fileSizeBytes }
        val bytesDownloadedMap = ConcurrentHashMap<Long, Long>()
        val concurrentLimit = when (PrefManager.downloadSpeed) {
            8 -> 3
            16 -> 5
            24 -> 7
            32 -> 10
            else -> 5
        }
        val downloadSemaphore = Semaphore(concurrentLimit)
        val previewJobs = Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Deferred<Unit>>())
        patchSupportedWorkshopFileTypes()
        val (maxDownloads, maxDecompress) = computeDownloadThreads(concurrentLimit)

        onItemProgress(0, totalItems, items.firstOrNull()?.title.orEmpty())

        val jobs = items.map { item ->
            async {
                val downloaded = downloadSemaphore.withPermit {
                    val itemDir = File(workshopContentDir, item.publishedFileId.toString())
                    val completeMarker = File(itemDir, COMPLETE_MARKER)
                    try {
                        completeMarker.delete()
                        bytesDownloadedMap[item.publishedFileId] = 0L
                        downloadWorkshopItem(
                            item = item,
                            steamClient = steamClient,
                            licenses = licenses,
                            installDirectory = itemDir.absolutePath,
                            maxDownloads = maxDownloads,
                            maxDecompress = maxDecompress,
                        ) { downloaded, _ ->
                            bytesDownloadedMap[item.publishedFileId] = downloaded
                            onBytesProgress(bytesDownloadedMap.values.sum(), fixedTotalBytes)
                        }
                        completeMarker.writeText(item.timeUpdated.toString())
                        val done = completedCount.incrementAndGet()
                        onItemProgress(done, totalItems, item.title)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to sync workshop item ${item.publishedFileId}")
                        false
                    }
                }
                if (downloaded) {
                    val itemDir = File(workshopContentDir, item.publishedFileId.toString())
                    previewJobs.add(async(Dispatchers.IO) {
                        runCatching {
                            downloadPreviewImage(item, itemDir)
                        }.onFailure {
                            Timber.tag(TAG).d(it, "Preview download skipped for ${item.publishedFileId}")
                        }
                        Unit
                    })
                }
                downloaded
            }
        }

        jobs.awaitAll()
        previewJobs.toList().awaitAll()
        completedCount.get()
    }

    private suspend fun downloadWorkshopItem(
        item: WorkshopItemSubscription,
        steamClient: SteamClient,
        licenses: List<License>,
        installDirectory: String,
        maxDownloads: Int,
        maxDecompress: Int,
        onBytesProgress: (downloadedBytes: Long, estimatedTotalBytes: Long) -> Unit,
    ) {
        val failures = mutableListOf<String>()

        val canUseDepot = item.manifestId != 0L
        val canUseHttp = item.fileUrl.isNotBlank()

        if (canUseHttp) {
            runCatching {
                downloadViaHttp(
                    item = item,
                    installDirectory = installDirectory,
                    onBytesProgress = onBytesProgress,
                )
            }.onSuccess {
                return
            }.onFailure {
                failures += "http: ${it.message ?: it::class.simpleName.orEmpty()}"
                Timber.tag(TAG).w(
                    it,
                    "HTTP workshop download failed for ${item.publishedFileId}, trying other route if available",
                )
            }
        }

        if (canUseDepot) {
            runCatching {
                downloadViaDepotDownloader(
                    item = item,
                    steamClient = steamClient,
                    licenses = licenses,
                    installDirectory = installDirectory,
                    maxDownloads = maxDownloads,
                    maxDecompress = maxDecompress,
                    onBytesProgress = onBytesProgress,
                )
            }.onSuccess {
                return
            }.onFailure {
                failures += "depot: ${it.message ?: it::class.simpleName.orEmpty()}"
                Timber.tag(TAG).w(
                    it,
                    "Depot workshop download failed for ${item.publishedFileId}",
                )
            }
        }

        val itemDir = File(installDirectory)
        val hasFiles = itemDir.exists() && (itemDir.listFiles()?.any { !it.name.startsWith(".") } == true)
        if (hasFiles) {
            Timber.tag(TAG).w(
                "Workshop item ${item.publishedFileId} reported download errors but content exists on disk; keeping it",
            )
            return
        }

        error(
            if (failures.isNotEmpty()) {
                "Workshop download failed (${failures.joinToString("; ")})"
            } else {
                "Workshop download failed (no usable content route)"
            },
        )
    }

    fun configureModSymlinks(
        gameRootDir: File,
        workshopContentDir: File,
        items: List<WorkshopItemSubscription> = emptyList(),
        winePrefix: String = "",
        gameName: String = "",
        developerName: String = "",
    ) {
        if (!workshopContentDir.exists()) return
        val rawModDirs = workshopContentDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory &&
                    dir.name.toLongOrNull() != null &&
                    dir.listFiles()?.any { !it.name.startsWith(".") } == true
            }
            ?: return
        val orderedIds = items.map { it.publishedFileId }
        val modDirById = rawModDirs.associateBy { it.name.toLong() }
        val persistedOrder = loadPersistedLoadOrder(workshopContentDir)
        val modDirs = (orderedIds.mapNotNull { modDirById[it] } +
            rawModDirs.filterNot { dir -> dir.name.toLong() in orderedIds })
            .distinctBy { it.name }
            .sortedWith(
                compareBy<File> { dir -> persistedOrder[dir.name.toLong()] ?: Int.MAX_VALUE }
                    .thenBy { dir -> orderedIds.indexOf(dir.name.toLong()).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                    .thenBy { it.name },
            )

        val routing = if (winePrefix.isNotBlank()) {
            val windowsUserDir = detectWindowsUserDir(winePrefix)
            WorkshopModPathDetector().detect(
                gameInstallDir = gameRootDir,
                appDataRoaming = File(windowsUserDir, "AppData/Roaming"),
                appDataLocal = File(windowsUserDir, "AppData/Local"),
                appDataLocalLow = File(windowsUserDir, "AppData/LocalLow"),
                documentsMyGames = File(windowsUserDir, "Documents/My Games"),
                documentsDir = File(windowsUserDir, "Documents"),
                gameName = gameName,
                developerName = developerName,
            )
        } else {
            WorkshopModPathDetector.DetectionResult(
                strategy = WorkshopModPathStrategy.Standard,
                confidence = WorkshopModPathDetector.Confidence.LOW,
                reason = "Wine prefix unavailable",
            )
        }
        val isHaydee = workshopContentDir.name == "530890" || gameName.contains("haydee", ignoreCase = true)
        val isBrotato = workshopContentDir.name == "1942280" || gameName.contains("brotato", ignoreCase = true)
        val isEndlessLegend = workshopContentDir.name == "289130" || gameName.contains("endless legend", ignoreCase = true)
        val isNoita = workshopContentDir.name == "881100" || gameName.contains("noita", ignoreCase = true)
        val effectiveModDirs = if (isNoita) {
            modDirs.map(::resolveNoitaModRoot)
        } else {
            modDirs
        }
        val willUseFilesystemMods =
            isHaydee || (
                !isEndlessLegend &&
                !isBrotato &&
                routing.strategy is WorkshopModPathStrategy.SymlinkIntoDir &&
                    routing.confidence == WorkshopModPathDetector.Confidence.HIGH
                )

        val dllNames = setOf("steam_api.dll", "steam_api64.dll", "steamclient.dll", "steamclient64.dll")
        val steamRootDir = workshopContentDir.parentFile?.parentFile?.parentFile?.parentFile

        if (gameRootDir.isDirectory) {
            forEachFileTree(gameRootDir, 10) { file ->
                if (!file.isFile || file.name.lowercase() !in dllNames) return@forEachFileTree
                val settingsDir = File(file.parentFile, "steam_settings")
                settingsDir.mkdirs()
                ensureSteamAppId(settingsDir, workshopContentDir.name)
                val modsDir = File(settingsDir, "mods")
                if (!willUseFilesystemMods) {
                    recreateModsDir(modsDir)
                    effectiveModDirs.forEach { itemDir ->
                        runCatching {
                            Files.createSymbolicLink(File(modsDir, itemDir.name).toPath(), itemDir.toPath())
                        }.onFailure {
                            Timber.tag(TAG).w(it, "Failed creating workshop symlink for ${itemDir.name}")
                        }
                    }
                    writeWorkshopMetadata(settingsDir, effectiveModDirs, items)
                } else {
                    if (modsDir.isDirectory) {
                        modsDir.listFiles()?.forEach { entry ->
                            if (Files.isSymbolicLink(entry.toPath())) {
                                Files.deleteIfExists(entry.toPath())
                            }
                        }
                    }
                    File(settingsDir, "mods.json").writeText("{}")
                    File(settingsDir, "subscribed_ids.txt").delete()
                }
            }
        }

        if (steamRootDir != null) {
            val globalSettingsDir = File(steamRootDir, "steam_settings")
            globalSettingsDir.mkdirs()
            ensureSteamAppId(globalSettingsDir, workshopContentDir.name)
            if (!willUseFilesystemMods) {
                writeWorkshopMetadata(globalSettingsDir, effectiveModDirs, items)
            } else {
                File(globalSettingsDir, "mods.json").writeText("{}")
                File(globalSettingsDir, "subscribed_ids.txt").delete()
            }
            val globalModsDir = File(globalSettingsDir, "mods")
            if (globalModsDir.isDirectory) {
                globalModsDir.listFiles()?.forEach { entry ->
                    if (Files.isSymbolicLink(entry.toPath())) {
                        Files.deleteIfExists(entry.toPath())
                    }
                }
            }
        }

        if (winePrefix.isNotBlank()) {
            if (isHaydee) {
                syncHaydeeWorkshopMods(gameRootDir, effectiveModDirs)
            } else if (!isBrotato && routing.strategy !is WorkshopModPathStrategy.Standard) {
                val titlesByItemId = if (isNoita) {
                    resolveNoitaModNames(effectiveModDirs, items)
                } else {
                    items.associate { it.publishedFileId to it.title }
                }
                val symlinker = WorkshopSymlinker()
                symlinker.sync(
                    strategy = routing.strategy,
                    activeItemDirs = linkedMapOf<Long, File>().apply {
                        effectiveModDirs.forEach { put(it.name.toLong(), it) }
                    },
                    workshopContentBase = workshopContentDir,
                    itemTitles = titlesByItemId,
                )
            }

            val isSkyrim = workshopContentDir.name == "72850" || gameName.contains("skyrim", ignoreCase = true)
            if (isSkyrim) {
                runCatching {
                    syncSkyrimWorkshopMods(gameRootDir, modDirs, winePrefix)
                }.onFailure {
                    Timber.tag(TAG).w(it, "Skyrim workshop sync failed")
                }
            }
        }
    }

    fun clearWorkshopData(
        gameRootDir: File,
        workshopContentDir: File,
        winePrefix: String = "",
        gameName: String = "",
        developerName: String = "",
    ) {
        val workshopContentBase = workshopContentDir.absoluteFile
        val workshopCleanupBase = workshopContentBase.parentFile ?: workshopContentBase
        val legacyLocalModsContentDir = workshopContentDir.name.toIntOrNull()
            ?.let { appId -> File(winePrefix).parentFile?.let { getLegacyLocalModsContentDir(it, appId) } }
            ?.absoluteFile
        val legacyCleanupBase = legacyLocalModsContentDir?.parentFile ?: legacyLocalModsContentDir
        clearSteamSettingsWorkshopData(gameRootDir, workshopCleanupBase)
        if (legacyLocalModsContentDir != null) {
            clearSteamSettingsWorkshopData(gameRootDir, legacyCleanupBase ?: legacyLocalModsContentDir)
        }
        clearStaleSteamSettingsDirectories(gameRootDir)

        val steamRootDir = workshopContentBase.parentFile?.parentFile?.parentFile?.parentFile
        if (steamRootDir != null) {
            clearSteamSettingsDirectory(File(steamRootDir, "steam_settings"), workshopCleanupBase)
            if (legacyLocalModsContentDir != null) {
                clearSteamSettingsDirectory(
                    File(steamRootDir, "steam_settings"),
                    legacyCleanupBase ?: legacyLocalModsContentDir,
                )
            }
        }

        if (winePrefix.isNotBlank()) {
            val isHaydee = workshopContentDir.name == "530890" || gameName.contains("haydee", ignoreCase = true)
            val windowsUserDir = detectWindowsUserDir(winePrefix)
            val pathDetector = WorkshopModPathDetector()
            val routing = pathDetector.detect(
                gameInstallDir = gameRootDir,
                appDataRoaming = File(windowsUserDir, "AppData/Roaming"),
                appDataLocal = File(windowsUserDir, "AppData/Local"),
                appDataLocalLow = File(windowsUserDir, "AppData/LocalLow"),
                documentsMyGames = File(windowsUserDir, "Documents/My Games"),
                documentsDir = File(windowsUserDir, "Documents"),
                gameName = gameName,
                developerName = developerName,
            )
            val isEndlessLegend = workshopContentDir.name == "289130" || gameName.contains("endless legend", ignoreCase = true)
            if (isHaydee) {
                syncHaydeeWorkshopMods(gameRootDir, emptyList())
            } else if (!isEndlessLegend && routing.strategy !is WorkshopModPathStrategy.Standard) {
                val symlinker = WorkshopSymlinker()
                symlinker.sync(
                    strategy = routing.strategy,
                    activeItemDirs = emptyMap(),
                    workshopContentBase = workshopCleanupBase,
                    itemTitles = emptyMap(),
                )
                if (legacyLocalModsContentDir != null) {
                    symlinker.sync(
                        strategy = routing.strategy,
                        activeItemDirs = emptyMap(),
                        workshopContentBase = legacyCleanupBase ?: legacyLocalModsContentDir,
                        itemTitles = emptyMap(),
                    )
                }
            }
        }

        cleanupInstalledWorkshopEntries(
            gameRootDir = gameRootDir,
            ownedBases = listOfNotNull(
                workshopCleanupBase,
                workshopContentBase,
                legacyCleanupBase,
                legacyLocalModsContentDir,
            ),
        )
        cleanupSourceEngineWorkshopLinks(
            gameRootDir = gameRootDir,
            ownedBases = listOfNotNull(
                workshopCleanupBase,
                workshopContentBase,
                legacyCleanupBase,
                legacyLocalModsContentDir,
            ),
        )
        revertSourceGameInfoPatches(gameRootDir)
        clearCookedModsWorkshopFiles(gameRootDir)

        val isSkyrim = workshopContentDir.name == "72850" || gameName.contains("skyrim", ignoreCase = true)
        if (isSkyrim && winePrefix.isNotBlank()) {
            clearSkyrimWorkshopData(gameRootDir, winePrefix)
        }

        workshopContentDir.deleteRecursively()
        legacyLocalModsContentDir?.deleteRecursively()
    }

    private fun syncHaydeeWorkshopMods(
        gameRootDir: File,
        modDirs: List<File>,
    ) {
        if (!gameRootDir.isDirectory) return

        val targetDirs = linkedMapOf(
            "packs" to File(gameRootDir, "Packs"),
            "maps" to File(gameRootDir, "Maps"),
            "outfits" to File(gameRootDir, "Outfits"),
            "scenes" to File(gameRootDir, "Scenes"),
            "modding" to File(gameRootDir, "Modding"),
        )

        targetDirs.values.forEach { dir ->
            dir.mkdirs()
            dir.listFiles()?.forEach { entry ->
                val shouldDelete = when {
                    Files.isSymbolicLink(entry.toPath()) -> File(entry.parentFile, "${entry.name}.$HAYDEE_ROUTE_MARKER").isFile
                    entry.isDirectory -> File(entry, HAYDEE_ROUTE_MARKER).isFile
                    entry.isFile -> File(entry.parentFile, "${entry.name}.$HAYDEE_ROUTE_MARKER").isFile
                    else -> false
                }
                if (shouldDelete) {
                    runCatching {
                        if (Files.isSymbolicLink(entry.toPath())) {
                            Files.deleteIfExists(entry.toPath())
                        } else {
                            entry.deleteRecursively()
                        }
                        File(entry.parentFile, "${entry.name}.$HAYDEE_ROUTE_MARKER").delete()
                    }
                }
            }
        }

        modDirs.forEach { itemDir ->
            routeHaydeeItem(itemDir, targetDirs)
        }
    }

    private fun routeHaydeeItem(
        itemDir: File,
        targetDirs: Map<String, File>,
    ) {
        itemDir.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.forEach { child ->
                val targetRoot = when {
                    child.isDirectory && child.name.equals("Maps", ignoreCase = true) -> targetDirs["maps"]
                    child.isDirectory && child.name.equals("Outfits", ignoreCase = true) -> targetDirs["outfits"]
                    child.isDirectory && child.name.equals("Scenes", ignoreCase = true) -> targetDirs["scenes"]
                    child.isDirectory && child.name.equals("Modding", ignoreCase = true) -> targetDirs["modding"]
                    child.isDirectory && child.name.equals("Packs", ignoreCase = true) -> targetDirs["packs"]
                    child.isFile && child.extension.equals("pack", ignoreCase = true) -> targetDirs["packs"]
                    child.isFile && child.extension.equals("scene", ignoreCase = true) -> targetDirs["scenes"]
                    else -> null
                } ?: return@forEach

                if (child.isDirectory && child.name in setOf("Maps", "Outfits", "Scenes", "Modding", "Packs")) {
                    child.listFiles()
                        ?.filter { !it.name.startsWith(".") }
                        ?.forEach { nested ->
                            ensureHaydeeLink(File(targetRoot, nested.name), nested)
                        }
                } else {
                    ensureHaydeeLink(File(targetRoot, child.name), child)
                }
            }
    }

    private fun ensureHaydeeLink(
        target: File,
        source: File,
    ) {
        runCatching {
            if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                if (Files.isSymbolicLink(target.toPath())) {
                    Files.deleteIfExists(target.toPath())
                } else {
                    return
                }
            }

            target.parentFile?.mkdirs()
            Files.createSymbolicLink(target.toPath(), source.toPath().toAbsolutePath().normalize())
            File(target.parentFile, "${target.name}.$HAYDEE_ROUTE_MARKER").writeText("1")
        }.recoverCatching {
            if (source.isDirectory) {
                if (target.exists()) return
                source.copyRecursively(target, overwrite = true)
                File(target, HAYDEE_ROUTE_MARKER).writeText("1")
            } else {
                if (target.exists()) return
                Files.copy(source.toPath(), target.toPath())
                File(target.parentFile, "${target.name}.$HAYDEE_ROUTE_MARKER").writeText("1")
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed routing Haydee workshop item ${source.absolutePath}")
        }
    }

    private fun cleanupInstalledWorkshopEntries(
        gameRootDir: File,
        ownedBases: List<File>,
    ) {
        if (ownedBases.isEmpty() || !gameRootDir.isDirectory) return
        val normalizedBases = ownedBases.map { it.toPath().toAbsolutePath().normalize() }

        forEachFileTree(gameRootDir, 6) { entry ->
            if (entry.absolutePath.contains("steam_settings")) return@forEachFileTree

            when {
                Files.isSymbolicLink(entry.toPath()) -> {
                    val target = runCatching {
                        val rawTarget = Files.readSymbolicLink(entry.toPath())
                        (if (rawTarget.isAbsolute) rawTarget else entry.toPath().parent.resolve(rawTarget))
                            .normalize()
                            .toAbsolutePath()
                    }.getOrNull() ?: return@forEachFileTree
                    if (normalizedBases.any { base -> target.startsWith(base) }) {
                        runCatching {
                            Files.deleteIfExists(entry.toPath())
                            File(entry.parentFile, "${entry.name}.$HAYDEE_ROUTE_MARKER").delete()
                        }
                    }
                }
                entry.isDirectory && File(entry, ".gamenative_workshop").isFile -> {
                    runCatching { entry.deleteRecursively() }
                }
                entry.isFile && File(entry.parentFile, "${entry.name}.$HAYDEE_ROUTE_MARKER").isFile -> {
                    runCatching {
                        entry.delete()
                        File(entry.parentFile, "${entry.name}.$HAYDEE_ROUTE_MARKER").delete()
                    }
                }
            }
        }
    }

    private fun clearStaleSteamSettingsDirectories(gameRootDir: File) {
        if (!gameRootDir.isDirectory) return
        forEachFileTree(gameRootDir, 10) { dir ->
            if (!dir.isDirectory || dir.name != "steam_settings") return@forEachFileTree
            val hasCoreConfig =
                File(dir, "steam_appid.txt").isFile ||
                    File(dir, "configs.user.ini").isFile ||
                    File(dir, "configs.app.ini").isFile
            if (hasCoreConfig) return@forEachFileTree

            runCatching {
                val modsDir = File(dir, "mods")
                if (modsDir.isDirectory) {
                    modsDir.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
                    modsDir.delete()
                }
                File(dir, "mods.json").delete()
                if (dir.listFiles()?.isEmpty() == true) {
                    dir.delete()
                }
            }
        }

        val rootSettingsDir = File(gameRootDir, "steam_settings")
        if (
            rootSettingsDir.isDirectory &&
            !File(rootSettingsDir, "steam_appid.txt").exists() &&
            !File(rootSettingsDir, "configs.app.ini").exists()
        ) {
            runCatching {
                val rootModsDir = File(rootSettingsDir, "mods")
                if (rootModsDir.isDirectory) {
                    rootModsDir.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
                    rootModsDir.delete()
                }
                File(rootSettingsDir, "mods.json").delete()
                if (rootSettingsDir.listFiles()?.isEmpty() == true) {
                    rootSettingsDir.delete()
                }
            }
        }
    }

    private fun cleanupSourceEngineWorkshopLinks(
        gameRootDir: File,
        ownedBases: List<File>,
    ) {
        if (!gameRootDir.isDirectory) return
        val normalizedBases = ownedBases.map { it.toPath().toAbsolutePath().normalize() }

        forEachFileTree(gameRootDir, 5) { dir ->
            if (!dir.isDirectory || dir.name != "addons") return@forEachFileTree
            if (dir.absolutePath.contains("steam_settings")) return@forEachFileTree
            dir.listFiles()?.forEach { entry ->
                if (!Files.isSymbolicLink(entry.toPath())) return@forEach
                val target = runCatching {
                    val rawTarget = Files.readSymbolicLink(entry.toPath())
                    (if (rawTarget.isAbsolute) rawTarget else entry.toPath().parent.resolve(rawTarget))
                        .normalize()
                        .toAbsolutePath()
                }.getOrNull() ?: return@forEach
                if (normalizedBases.any { base -> target.startsWith(base) }) {
                    runCatching { Files.deleteIfExists(entry.toPath()) }
                }
            }
        }

        forEachFileTree(gameRootDir, 5) { dir ->
            if (!dir.isDirectory || dir.name != "maps") return@forEachFileTree
            if (dir.absolutePath.contains("steam_settings") || dir.absolutePath.contains(".DepotDownloader")) return@forEachFileTree

            val workshopMapsDir = File(dir, "workshop")
            if (!workshopMapsDir.exists()) return@forEachFileTree
            runCatching {
                workshopMapsDir.listFiles()?.forEach { entry ->
                    if (Files.isSymbolicLink(entry.toPath())) {
                        Files.deleteIfExists(entry.toPath())
                    } else if (entry.isDirectory) {
                        entry.listFiles()?.forEach { inner ->
                            if (Files.isSymbolicLink(inner.toPath())) {
                                Files.deleteIfExists(inner.toPath())
                            }
                        }
                        if (entry.listFiles()?.isEmpty() == true) {
                            entry.delete()
                        }
                    }
                }
                if (workshopMapsDir.listFiles()?.isEmpty() == true) {
                    workshopMapsDir.delete()
                }
            }
        }
    }

    private fun revertSourceGameInfoPatches(gameRootDir: File) {
        if (!gameRootDir.isDirectory) return
        forEachFileTree(gameRootDir, 5) { file ->
            if (!file.isFile || !file.name.equals("gameinfo.txt", ignoreCase = true)) return@forEachFileTree
            if (file.absolutePath.contains("steam_settings") || file.absolutePath.contains(".DepotDownloader")) return@forEachFileTree
            runCatching {
                val content = file.readText()
                if (!content.contains("workshop_mods")) return@runCatching
                val reverted = content.lineSequence()
                    .filterNot { line -> line.contains("workshop_mods") }
                    .joinToString("\n")
                file.writeText(reverted)
            }
        }
    }

    private fun clearCookedModsWorkshopFiles(gameRootDir: File) {
        if (!gameRootDir.isDirectory) return
        var cookedModsDir: File? = null
        forEachFileTree(gameRootDir, 3) { file ->
            if (cookedModsDir == null && file.isDirectory && file.name.equals("CookedMods", ignoreCase = true)) {
                cookedModsDir = file
            }
        }
        val resolvedCookedModsDir = cookedModsDir ?: return
        val manifestFile = File(resolvedCookedModsDir, ".gamenative_workshop_files")
        if (!manifestFile.isFile) return

        manifestFile.readText().lines().filter { it.isNotBlank() }.forEach { name ->
            runCatching { Files.deleteIfExists(File(resolvedCookedModsDir, name).toPath()) }
        }
        manifestFile.delete()
    }

    private fun detectWindowsUserDir(winePrefix: String): File {
        val usersDir = File(winePrefix, "drive_c/users")
        val preferred = listOf("xuser", "steamuser")
            .map { File(usersDir, it) }
            .firstOrNull { it.isDirectory }
        if (preferred != null) return preferred

        return usersDir.listFiles()
            ?.firstOrNull { dir ->
                dir.isDirectory &&
                    !dir.name.equals("Public", ignoreCase = true) &&
                    !dir.name.equals("Default", ignoreCase = true) &&
                    !dir.name.equals("Default User", ignoreCase = true) &&
                    !dir.name.equals("All Users", ignoreCase = true)
            }
            ?: File(usersDir, "xuser")
    }

    private fun ensureSteamAppId(settingsDir: File, appIdText: String) {
        val appIdFile = File(settingsDir, "steam_appid.txt")
        if (!appIdFile.exists() && appIdText.toLongOrNull() != null) {
            appIdFile.writeText(appIdText)
        }
    }

    private fun recreateModsDir(modsDir: File) {
        if (modsDir.exists()) {
            modsDir.listFiles()?.forEach { entry ->
                if (Files.isSymbolicLink(entry.toPath())) {
                    Files.deleteIfExists(entry.toPath())
                } else if (entry.isDirectory) {
                    entry.deleteRecursively()
                }
            }
            modsDir.delete()
        }
        modsDir.mkdirs()
    }

    fun fixItemFileNames(
        items: List<WorkshopItemSubscription>,
        workshopContentDir: File,
    ) {
        var renamedCount = 0
        for (item in items) {
            val baseName = item.fileName.substringAfterLast('/')
            if (baseName.isEmpty()) continue
            val itemDir = File(workshopContentDir, item.publishedFileId.toString())
            if (!itemDir.isDirectory) continue

            val goodFile = File(itemDir, baseName)
            if (goodFile.exists()) continue

            val contentFiles = itemDir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?: continue
            if (contentFiles.size != 1) continue

            val badFile = contentFiles[0]
            val targetExt = baseName.substringAfterLast('.', "").lowercase()
            val currentExt = badFile.extension.lowercase()
            if (targetExt == "ckm" && currentExt in setOf("esp", "esm", "bsa", "bsl")) continue

            if (badFile.renameTo(goodFile)) {
                renamedCount++
            }
        }
        if (renamedCount > 0) {
            Timber.tag(TAG).i("Fixed $renamedCount workshop item filenames")
        }
    }

    private fun resolveNoitaModNames(
        modDirs: List<File>,
        items: List<WorkshopItemSubscription>,
    ): Map<Long, String> {
        val fallbackTitles = items.associate { it.publishedFileId to it.title }
        return buildMap {
            modDirs.forEach { itemDir ->
                val id = itemDir.name.toLongOrNull() ?: return@forEach
                val modId = runCatching {
                    File(itemDir, "mod_id.txt")
                        .takeIf { it.isFile }
                        ?.readText()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()
                put(id, modId ?: fallbackTitles[id].orEmpty())
            }
        }
    }

    private fun resolveNoitaModRoot(itemDir: File): File {
        val modId = runCatching {
            File(itemDir, "mod_id.txt")
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (modId.isNullOrBlank()) return itemDir

        val nestedDir = File(itemDir, "Mods/$modId")
        return if (nestedDir.isDirectory) nestedDir else itemDir
    }

    fun extractCkmFiles(workshopContentDir: File) {
        if (!workshopContentDir.exists()) return
        var extractedCount = 0

        workshopContentDir.listFiles()?.forEach { itemDir ->
            if (!itemDir.isDirectory) return@forEach
            val ckmFiles = itemDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".ckm", ignoreCase = true) }
                ?: return@forEach

            for (ckmFile in ckmFiles) {
                try {
                    val baseName = ckmFile.nameWithoutExtension
                    val data = ckmFile.readBytes()
                    if (data.size < 8) continue

                    if (
                        data[0] == 0x54.toByte() &&
                        data[1] == 0x45.toByte() &&
                        data[2] == 0x53.toByte() &&
                        data[3] == 0x34.toByte()
                    ) {
                        val espFile = File(ckmFile.parentFile, "$baseName.esp")
                        if (!espFile.exists()) {
                            Files.move(ckmFile.toPath(), espFile.toPath())
                        }
                        continue
                    }

                    val bsaLen = (data[0].toInt() and 0xFF) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        ((data[2].toInt() and 0xFF) shl 16) or
                        ((data[3].toInt() and 0xFF) shl 24)

                    var offset = 4
                    if (bsaLen > 0 && offset + bsaLen <= data.size) {
                        File(itemDir, "$baseName.bsa").writeBytes(data.copyOfRange(offset, offset + bsaLen))
                        offset += bsaLen
                    }
                    if (offset + 4 <= data.size) {
                        offset += 4
                    }
                    if (offset < data.size) {
                        File(itemDir, "$baseName.esp").writeBytes(data.copyOfRange(offset, data.size))
                    }

                    ckmFile.delete()
                    extractedCount++
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to extract CKM: ${ckmFile.name} in ${itemDir.name}")
                }
            }
        }

        if (extractedCount > 0) {
            Timber.tag(TAG).i("Extracted $extractedCount CKM files into BSA/ESP")
        }
    }

    fun fixFileExtensions(workshopContentDir: File) {
        if (!workshopContentDir.exists()) return
        var fixedCount = 0

        workshopContentDir.listFiles()?.forEach { itemDir ->
            if (!itemDir.isDirectory) return@forEach
            itemDir.listFiles()?.forEach fileLoop@{ file ->
                if (!file.isFile || file.name.startsWith(".")) return@fileLoop
                if (file.extension.lowercase() in KNOWN_EXTENSIONS) return@fileLoop

                val magic = ByteArray(4)
                val bytesRead = runCatching {
                    file.inputStream().use { it.read(magic) }
                }.getOrDefault(-1)
                if (bytesRead < 4) return@fileLoop

                val detectedExt = detectExtension(magic) ?: return@fileLoop
                val currentExt = file.extension.lowercase()
                val newName = if (
                    currentExt.isNotEmpty() &&
                    detectedExt.startsWith(currentExt) &&
                    currentExt != detectedExt
                ) {
                    file.nameWithoutExtension + "." + detectedExt
                } else {
                    file.name + "." + detectedExt
                }
                val newFile = File(file.parentFile, newName)
                if (newFile.exists()) return@fileLoop

                runCatching {
                    Files.move(file.toPath(), newFile.toPath())
                    fixedCount++
                }.onFailure {
                    Timber.tag(TAG).w(it, "Failed to rename ${file.absolutePath} -> ${newFile.name}")
                }
            }
        }

        if (fixedCount > 0) {
            Timber.tag(TAG).i("Fixed $fixedCount workshop file extensions via magic-byte detection")
        }
    }

    fun decompressLzmaFiles(workshopContentDir: File) {
        if (!workshopContentDir.exists()) return
        var decompressedCount = 0

        workshopContentDir.listFiles()?.forEach { itemDir ->
            if (!itemDir.isDirectory) return@forEach
            itemDir.listFiles()?.forEach fileLoop@{ file ->
                if (!file.isFile || file.name.startsWith(".")) return@fileLoop
                val firstByte = runCatching {
                    file.inputStream().use { it.read() }
                }.getOrDefault(-1)
                if (firstByte != 0x5D) return@fileLoop

                val tmpFile = File(file.parentFile, file.name + ".lzma_tmp")
                runCatching {
                    file.inputStream().buffered(262144).use { input ->
                        LZMAInputStream(input).use { lzma ->
                            tmpFile.outputStream().buffered(262144).use { output ->
                                lzma.copyTo(output, 262144)
                            }
                        }
                    }
                    if (tmpFile.length() > 0) {
                        if (tmpFile.renameTo(file)) {
                            decompressedCount++
                        } else {
                            tmpFile.delete()
                        }
                    } else {
                        tmpFile.delete()
                    }
                }.onFailure {
                    tmpFile.delete()
                    Timber.tag(TAG).d("Skipping ${file.name}: not valid LZMA")
                }
            }
        }

        if (decompressedCount > 0) {
            Timber.tag(TAG).i("Decompressed $decompressedCount workshop files")
        }
    }

    private fun clearSteamSettingsWorkshopData(
        gameRootDir: File,
        workshopContentBase: File,
    ) {
        if (!gameRootDir.isDirectory) return
        val dllNames = setOf("steam_api.dll", "steam_api64.dll", "steamclient.dll", "steamclient64.dll")
        forEachFileTree(gameRootDir, 10) { file ->
            if (!file.isFile || file.name.lowercase() !in dllNames) return@forEachFileTree
            clearSteamSettingsDirectory(File(file.parentFile, "steam_settings"), workshopContentBase)
        }
    }

    private fun clearSteamSettingsDirectory(
        settingsDir: File,
        workshopContentBase: File,
    ) {
        if (!settingsDir.isDirectory) return

        val modsDir = File(settingsDir, "mods")
        if (modsDir.isDirectory) {
            modsDir.listFiles()?.forEach { entry ->
                val delete = when {
                    Files.isSymbolicLink(entry.toPath()) -> runCatching {
                        Files.readSymbolicLink(entry.toPath()).toAbsolutePath().normalize()
                            .startsWith(workshopContentBase.toPath().toAbsolutePath().normalize())
                    }.getOrDefault(false)
                    entry.isDirectory -> true
                    else -> false
                }
                if (delete) {
                    runCatching {
                        if (Files.isSymbolicLink(entry.toPath())) {
                            Files.deleteIfExists(entry.toPath())
                        } else {
                            entry.deleteRecursively()
                        }
                    }.onFailure {
                        Timber.tag(TAG).w(it, "Failed clearing workshop entry ${entry.absolutePath}")
                    }
                }
            }
            modsDir.delete()
        }

        File(settingsDir, "mods.json").delete()
        File(settingsDir, "subscribed_ids.txt").delete()
    }

    private fun writeWorkshopMetadata(
        settingsDir: File,
        modDirs: List<File>,
        items: List<WorkshopItemSubscription>,
    ) {
        File(settingsDir, "mods.json").writeText(buildModsJson(modDirs, items).toString(2))
        val subscribedIds = modDirs.mapNotNull { it.name.toLongOrNull() }
        if (subscribedIds.isNotEmpty()) {
            File(settingsDir, "subscribed_ids.txt").writeText(subscribedIds.joinToString("\n"))
        }
    }

    private fun loadPersistedLoadOrder(workshopContentDir: File): Map<Long, Int> {
        val metadataFile = workshopContentDir.parentFile?.parentFile?.parentFile?.parentFile
            ?.resolve("steam_settings/$LOCAL_WORKSHOP_UI_METADATA_FILE")
            ?: return emptyMap()
        if (!metadataFile.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(metadataFile.readText())
            buildMap {
                root.keys().asSequence().forEach { key ->
                    val publishedFileId = key.toLongOrNull() ?: return@forEach
                    val metadata = root.optJSONObject(key) ?: return@forEach
                    if (metadata.has("load_order")) {
                        put(publishedFileId, metadata.optInt("load_order"))
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private data class SubscribedFilesPage(
        val items: List<WorkshopItemSubscription>,
        val totalResults: Int,
    )

    private suspend fun fetchSubscribedFilesViaRpc(
        publishedFile: PublishedFile,
        appId: Int,
        steamId: SteamID,
        page: Int,
    ): SubscribedFilesPage? = withContext(Dispatchers.IO) {
        try {
            val request = CPublishedFile_GetUserFiles_Request.newBuilder().apply {
                this.steamid = steamId.convertToUInt64()
                this.appid = appId
                this.page = page
                this.numperpage = PAGE_SIZE
                this.type = "mysubscriptions"
                this.filetype = 0xFFFFFFFF.toInt()
            }.build()
            val response = withTimeoutOrNull(30_000L) {
                publishedFile.getUserFiles(request).toFuture().await()
            } ?: return@withContext null
            if (response.result != EResult.OK) return@withContext null
            val body = response.body.build()
            SubscribedFilesPage(
                items = body.publishedfiledetailsList.map { details ->
                    WorkshopItemSubscription(
                        publishedFileId = details.publishedfileid,
                        appId = if (details.consumerAppid != 0) details.consumerAppid else appId,
                        title = details.title.ifEmpty { details.publishedfileid.toString() },
                        fileSizeBytes = details.fileSize,
                        manifestId = details.hcontentFile,
                        timeUpdated = details.timeUpdated.toLong(),
                        fileUrl = details.fileUrl ?: "",
                        fileName = details.filename ?: "",
                        previewUrl = details.previewUrl ?: "",
                    )
                },
                totalResults = body.total,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Workshop subscription fetch failed for appId=$appId page=$page")
            null
        }
    }

    private fun buildModsJson(modDirs: List<File>, items: List<WorkshopItemSubscription>): JSONObject {
        val itemsById = items.associateBy { it.publishedFileId }
        val modsObj = JSONObject()
        modDirs.forEach { itemDir ->
            val id = itemDir.name.toLongOrNull() ?: return@forEach
            val item = itemsById[id]
            val entry = JSONObject()
            entry.put("title", item?.title ?: itemDir.name)
            val contentFile = itemDir.listFiles()?.firstOrNull { it.isFile && !it.name.startsWith(".") }
            if (contentFile != null) {
                entry.put("primary_filename", contentFile.name)
                entry.put("primary_filesize", contentFile.length())
            }
            val totalSize = itemDir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .sumOf { it.length() }
            entry.put("total_files_sizes", totalSize)
            if (item != null && item.timeUpdated > 0) {
                entry.put("time_updated", item.timeUpdated)
            }
            modsObj.put(itemDir.name, entry)
        }
        return modsObj
    }

    private fun patchSupportedWorkshopFileTypes() {
        if (workshopTypesPatched) return
        runCatching {
            val field = DepotDownloader::class.java.getDeclaredField("SupportedWorkshopFileTypes")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val existingSet = field.get(null) as Set<EWorkshopFileType>
            if (!existingSet.contains(EWorkshopFileType.First)) {
                val patchedSet = LinkedHashSet(existingSet)
                patchedSet.add(EWorkshopFileType.First)
                field.set(null, patchedSet)
            }
            workshopTypesPatched = true
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to patch SupportedWorkshopFileTypes")
        }
    }

    private fun computeDownloadTimeout(fileSizeBytes: Long): Long {
        val baseMins = 3L
        val sizeMb = fileSizeBytes / (1024 * 1024)
        val extraMins = (sizeMb * 30) / 60
        return ((baseMins + extraMins).coerceIn(3, 120)) * 60 * 1000
    }

    private fun computeDownloadThreads(concurrentLimit: Int): Pair<Int, Int> {
        var downloadRatio = 1.5
        var decompressRatio = 0.5
        when (PrefManager.downloadSpeed) {
            8 -> {
                downloadRatio = 1.4
                decompressRatio = 0.45
            }
            16 -> {
                downloadRatio = 2.4
                decompressRatio = 0.75
            }
            24 -> {
                downloadRatio = 4.0
                decompressRatio = 1.1
            }
            32 -> {
                downloadRatio = 6.0
                decompressRatio = 1.8
            }
        }
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val maxDownloads = ((cpuCores * downloadRatio).toInt() / concurrentLimit)
            .coerceAtLeast(if (concurrentLimit >= 7) 2 else 1)
        val maxDecompress = ((cpuCores * decompressRatio).toInt() / concurrentLimit)
            .coerceAtLeast(if (concurrentLimit >= 10) 2 else 1)
        return maxDownloads to maxDecompress
    }

    private fun syncSkyrimWorkshopMods(gameRootDir: File, modDirs: List<File>, winePrefix: String) {
        val dataDir = File(gameRootDir, "Data")
        if (!dataDir.isDirectory) return

        val pluginExts = setOf("esp", "esm", "esl")
        val archiveExts = setOf("bsa", "bsl")
        val activatedPlugins = linkedSetOf<String>()
        val manifestFile = File(dataDir, ".gamenative_workshop_files")
        val previouslyManagedFiles = if (manifestFile.isFile) {
            manifestFile.readText().lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } else {
            emptySet()
        }

        if (manifestFile.isFile) {
            manifestFile.readText().lines().filter { it.isNotBlank() }.forEach { name ->
                if (isProtectedSkyrimGameFile(name)) return@forEach
                runCatching { Files.deleteIfExists(File(dataDir, name).toPath()) }
            }
        }

        dataDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val shouldDelete = file.name.toLongOrNull() != null ||
                file.extension.equals("ckm", ignoreCase = true)
            if (shouldDelete) {
                runCatching { Files.deleteIfExists(file.toPath()) }
            }
        }

        val placedFiles = mutableListOf<String>()
        modDirs.forEach { itemDir ->
            itemDir.walkTopDown().maxDepth(4).forEach { file ->
                if (!file.isFile || file.name.startsWith(".")) return@forEach
                val ext = file.extension.lowercase()
                if (ext !in (pluginExts + archiveExts)) return@forEach

                val outFile = File(dataDir, file.name)
                if (!canMaterializeSkyrimWorkshopFile(outFile, file.name in previouslyManagedFiles)) {
                    Timber.tag(TAG).i("Skipping Skyrim workshop file placement for ${file.name}")
                    return@forEach
                }
                if (materializeWorkshopFile(file, outFile)) {
                    placedFiles.add(file.name)
                    if (ext in pluginExts) {
                        activatedPlugins.add(file.name)
                    }
                }
            }
        }

        if (placedFiles.isNotEmpty()) {
            manifestFile.writeText(placedFiles.joinToString("\n", postfix = "\n"))
        } else {
            manifestFile.delete()
        }

        val skyrimLocalDir = File(appDataLocal(winePrefix), "Skyrim")
        skyrimLocalDir.mkdirs()

        val basePlugins = listOf(
            "Skyrim.esm",
            "Update.esm",
            "Dawnguard.esm",
            "HearthFires.esm",
            "Dragonborn.esm",
        ).filter { File(dataDir, it).isFile }

        val finalOrder = (basePlugins + activatedPlugins.toList()).distinct()
        File(skyrimLocalDir, "plugins.txt").writeText(finalOrder.joinToString("\n", postfix = "\n"))
        File(skyrimLocalDir, "loadorder.txt").writeText(finalOrder.joinToString("\n", postfix = "\n"))
        Timber.tag(TAG).i(
            "Skyrim workshop sync: ${activatedPlugins.size} plugins activated, ${placedFiles.size} files placed",
        )
    }

    private fun clearSkyrimWorkshopData(gameRootDir: File, winePrefix: String) {
        val dataDir = File(gameRootDir, "Data")
        val removedNames = mutableSetOf<String>()
        if (dataDir.isDirectory) {
            val manifestFile = File(dataDir, ".gamenative_workshop_files")
            if (manifestFile.isFile) {
                manifestFile.readText().lines().filter { it.isNotBlank() }.forEach { name ->
                    if (isProtectedSkyrimGameFile(name)) return@forEach
                    val target = File(dataDir, name)
                    runCatching { Files.deleteIfExists(target.toPath()) }
                        .onSuccess { removedNames += name }
                }
                manifestFile.delete()
            }
        }

        val skyrimLocalDir = File(appDataLocal(winePrefix), "Skyrim")
        val removedPlugins = removedNames
            .filter { name ->
                val ext = name.substringAfterLast('.', "").lowercase()
                ext in setOf("esp", "esm", "esl")
            }
            .toSet()
        if (removedPlugins.isNotEmpty()) {
            trimSkyrimPluginList(File(skyrimLocalDir, "plugins.txt"), removedPlugins)
            trimSkyrimPluginList(File(skyrimLocalDir, "loadorder.txt"), removedPlugins)
        }
    }

    private fun trimSkyrimPluginList(file: File, removedPlugins: Set<String>) {
        if (!file.isFile) return
        val remainingLines = file.readLines().filterNot { line ->
            val normalized = line.trim().removePrefix("*")
            normalized in removedPlugins
        }
        if (remainingLines.isEmpty()) {
            file.delete()
        } else {
            file.writeText(remainingLines.joinToString("\n", postfix = "\n"))
        }
    }

    private fun canMaterializeSkyrimWorkshopFile(destination: File, wasPreviouslyManaged: Boolean): Boolean {
        if (isProtectedSkyrimGameFile(destination.name)) {
            return false
        }
        if (!destination.exists()) {
            return true
        }
        if (Files.isSymbolicLink(destination.toPath())) {
            return true
        }
        return wasPreviouslyManaged
    }

    private fun isProtectedSkyrimGameFile(name: String): Boolean {
        return name.lowercase() in PROTECTED_SKYRIM_GAME_FILES
    }

    private fun materializeWorkshopFile(source: File, destination: File): Boolean {
        if (!source.isFile) return false
        destination.parentFile?.mkdirs()

        val destPath = destination.toPath()
        val srcPath = source.toPath()

        if (Files.exists(destPath)) {
            if (Files.isSymbolicLink(destPath)) {
                Files.deleteIfExists(destPath)
            } else {
                if (destination.isFile && destination.length() == source.length()) {
                    return false
                }
                Files.deleteIfExists(destPath)
            }
        }

        return try {
            Files.createLink(destPath, srcPath)
            true
        } catch (_: Exception) {
            Files.copy(srcPath, destPath)
            true
        }
    }

    private fun appDataLocal(winePrefix: String): File =
        File(detectWindowsUserDir(winePrefix), "AppData/Local")

    private val PROTECTED_SKYRIM_GAME_FILES = setOf(
        "skyrim.esm",
        "update.esm",
        "dawnguard.esm",
        "hearthfires.esm",
        "dragonborn.esm",
        "skyrim - animations.bsa",
        "skyrim - interface.bsa",
        "skyrim - meshes.bsa",
        "skyrim - misc.bsa",
        "skyrim - shaders.bsa",
        "skyrim - sounds.bsa",
        "skyrim - textures.bsa",
        "skyrim - textures0.bsa",
        "skyrim - textures1.bsa",
        "skyrim - textures2.bsa",
        "skyrim - voices.bsa",
        "skyrim - voicesextra.bsa",
        "update.bsa",
        "dawnguard.bsa",
        "hearthfires.bsa",
        "dragonborn.bsa",
    )

    private val KNOWN_EXTENSIONS = setOf(
        "gma", "vpk", "bsp", "zip", "rar", "7z",
        "bsa", "esp", "esm", "ckm", "pak", "bin",
        "txt", "cfg", "lua", "mdl", "vmt", "vtf",
        "wav", "mp3", "ogg", "png", "jpg", "jpeg",
    )

    private fun detectExtension(magic: ByteArray): String? {
        if (magic[0] == 0x47.toByte() && magic[1] == 0x4D.toByte() &&
            magic[2] == 0x41.toByte() && magic[3] == 0x44.toByte()) return "gma"
        if (magic[0] == 0x34.toByte() && magic[1] == 0x12.toByte() &&
            magic[2] == 0xAA.toByte() && magic[3] == 0x55.toByte()) return "vpk"
        if (magic[0] == 0x56.toByte() && magic[1] == 0x42.toByte() &&
            magic[2] == 0x53.toByte() && magic[3] == 0x50.toByte()) return "bsp"
        if (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()) return "zip"
        return null
    }

    private suspend fun downloadPreviewImage(item: WorkshopItemSubscription, itemDir: File) {
        if (item.previewUrl.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val ext = item.previewUrl.substringAfterLast('.').substringBefore('?')
                    .lowercase()
                    .let { if (it in listOf("jpg", "jpeg", "png", "gif")) it else "jpg" }
                val previewFile = File(itemDir, "preview.$ext")
                if (previewFile.exists()) return@withContext
                Net.http.newCall(Request.Builder().url(item.previewUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    body.byteStream().use { input ->
                        previewFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadViaHttp(
        item: WorkshopItemSubscription,
        installDirectory: String,
        onBytesProgress: (downloadedBytes: Long, estimatedTotalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val itemDir = File(installDirectory)
        itemDir.mkdirs()
        val fileName = item.fileName.substringAfterLast('/').ifEmpty {
            item.fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty { item.publishedFileId.toString() }
        }
        val outputFile = File(itemDir, fileName)
        var existingBytes = 0L
        if (outputFile.isFile && outputFile.length() > 0) {
            existingBytes = outputFile.length()
        }
        val requestBuilder = Request.Builder().url(item.fileUrl)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        Net.http.newCall(requestBuilder.build()).execute().use { response ->
            val isResuming = response.code == 206 && existingBytes > 0
            if (!response.isSuccessful && response.code != 206) {
                error("HTTP ${response.code} for ${item.publishedFileId}")
            }
            val body = response.body ?: error("Empty workshop body for ${item.publishedFileId}")
            val resumeOffset = if (isResuming) existingBytes else 0L
            val totalBytes = if (isResuming) {
                existingBytes + body.contentLength().coerceAtLeast(0L)
            } else {
                body.contentLength().takeIf { it > 0 } ?: item.fileSizeBytes
            }
            body.byteStream().use { input ->
                BufferedOutputStream(java.io.FileOutputStream(outputFile, isResuming)).use { output ->
                    val buffer = ByteArray(262144)
                    var downloaded = resumeOffset
                    while (true) {
                        ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onBytesProgress(downloaded, totalBytes)
                    }
                }
            }
        }
    }

    private suspend fun downloadViaDepotDownloader(
        item: WorkshopItemSubscription,
        steamClient: SteamClient,
        licenses: List<License>,
        installDirectory: String,
        maxDownloads: Int,
        maxDecompress: Int,
        onBytesProgress: (downloadedBytes: Long, estimatedTotalBytes: Long) -> Unit,
    ) = coroutineScope {
        val depotDownloader = DepotDownloader(
            steamClient,
            licenses,
            debug = false,
            androidEmulation = true,
            maxDownloads = maxDownloads,
            maxDecompress = maxDecompress,
            parentJob = coroutineContext[Job],
            autoStartDownload = false,
        )
        try {
            val listener = WorkshopDownloadListener(item, onBytesProgress)
            depotDownloader.addListener(listener)
            depotDownloader.add(
                PubFileItem(
                    appId = item.appId,
                    pubFile = item.publishedFileId,
                    installDirectory = installDirectory,
                ),
            )
            depotDownloader.finishAdding()
            depotDownloader.startDownloading()
            val timeoutMs = computeDownloadTimeout(item.fileSizeBytes)
            Timber.tag(TAG).d(
                "Awaiting depot download for '${item.title}' (${item.publishedFileId}) timeout=${timeoutMs / 1000}s manifest=${item.manifestId}",
            )
            val completed = withTimeoutOrNull(timeoutMs) {
                depotDownloader.getCompletion().await()
            }
            if (completed == null) {
                val itemDir = File(installDirectory)
                val hasFiles = itemDir.exists() &&
                    (itemDir.listFiles()?.any { !it.name.startsWith(".") } == true)
                if (!hasFiles) {
                    error("Workshop depot download timed out after ${timeoutMs / 1000}s")
                }
                Timber.tag(TAG).w(
                    "Depot download timed out for ${item.publishedFileId} but files exist on disk",
                )
            } else if (listener.failed) {
                val itemDir = File(installDirectory)
                val hasFiles = itemDir.exists() &&
                    (itemDir.listFiles()?.any { !it.name.startsWith(".") } == true)
                if (!hasFiles) {
                    error(listener.failureMessage ?: "Workshop depot download failed")
                }
                Timber.tag(TAG).w(
                    "Depot download listener reported failure for ${item.publishedFileId} but files exist on disk",
                )
            }
        } finally {
            Thread { runCatching { depotDownloader.close() } }.start()
        }
    }

    private class WorkshopDownloadListener(
        private val item: WorkshopItemSubscription,
        private val onBytesProgress: (downloadedBytes: Long, estimatedTotalBytes: Long) -> Unit,
    ) : IDownloadListener {
        var failed = false
            private set
        var failureMessage: String? = null
            private set

        override fun onDownloadStarted(item: DownloadItem) = Unit

        override fun onDownloadCompleted(item: DownloadItem) = Unit

        override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
            failed = true
            failureMessage = error.message
        }

        override fun onStatusUpdate(message: String) = Unit

        override fun onChunkCompleted(
            depotId: Int,
            depotPercentComplete: Float,
            compressedBytes: Long,
            uncompressedBytes: Long,
        ) {
            val estimatedTotal = if (depotPercentComplete > 0.001f) {
                (uncompressedBytes / depotPercentComplete).toLong()
            } else {
                item.fileSizeBytes
            }
            onBytesProgress(uncompressedBytes, estimatedTotal)
        }

        override fun onDepotCompleted(
            depotId: Int,
            compressedBytes: Long,
            uncompressedBytes: Long,
        ) = Unit
    }
}
