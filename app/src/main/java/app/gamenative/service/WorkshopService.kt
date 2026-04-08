package app.gamenative.service

import android.content.Context
import android.text.Html
import android.util.Log
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.Net
import app.gamenative.workshop.WorkshopItemSubscription
import app.gamenative.workshop.WorkshopSyncManager
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_Subscribe_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_Unsubscribe_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.PublishedFileDetails
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject

enum class WorkshopSortType(
    val queryType: Int,
    val label: String,
) {
    TRENDING(3, "Trending"),
    MOST_SUBSCRIBED(9, "Most Popular"),
    TOP_RATED(0, "Top Rated"),
    MOST_RECENT(1, "Most Recent"),
}

data class WorkshopItemUi(
    val publishedFileId: Long,
    val title: String,
    val previewUrl: String,
    val description: String,
    val subscriberCount: Int,
    val favoritedCount: Int = 0,
    val viewCount: Int = 0,
    val sizeBytes: Long,
    val timeCreated: Long = 0L,
    val timeUpdated: Long,
    val tags: List<String> = emptyList(),
    val loadOrder: Int? = null,
    val dependencyIds: List<Long> = emptyList(),
    val dependencyTitles: Map<Long, String> = emptyMap(),
    val missingDependencyIds: List<Long> = emptyList(),
)

data class WorkshopDownloadState(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val stage: String = "",
)

private const val WORKSHOP_DETAILS_TIMEOUT_MS = 15_000L
private const val LOCAL_WORKSHOP_UI_METADATA_FILE = "gamenative_workshop_ui.json"

object WorkshopService {
    const val BROWSE_PAGE_SIZE = 30

    suspend fun getItemsByIds(
        appId: Int,
        publishedFileIds: List<Long>,
    ): List<WorkshopItemUi> {
        if (publishedFileIds.isEmpty()) return emptyList()
        val publishedFile = getPublishedFileRpc() ?: return emptyList()
        val request = CPublishedFile_GetDetails_Request.newBuilder()
            .apply { publishedFileIds.distinct().forEach(::addPublishedfileids) }
            .build()

        return withTimeoutOrNull(WORKSHOP_DETAILS_TIMEOUT_MS) {
            publishedFile.getDetails(request).await()
                .body
                .getPublishedfiledetailsList()
                .map { detail ->
                    detail.toWorkshopItemUi().let { item ->
                        if (item.title.isBlank()) item.copy(title = item.publishedFileId.toString()) else item
                    }
                }
        } ?: publishedFileIds.distinct().map { publishedFileId ->
            WorkshopItemUi(
                publishedFileId = publishedFileId,
                title = publishedFileId.toString(),
                previewUrl = "",
                description = "",
                subscriberCount = 0,
                sizeBytes = 0L,
                timeUpdated = 0L,
            )
        }
    }

    suspend fun enrichWithHtmlDependencies(item: WorkshopItemUi): WorkshopItemUi = withContext(Dispatchers.IO) {
        val dependencyTitles = fetchRequiredItemsFromWorkshopPage(item.publishedFileId)
        if (dependencyTitles.isEmpty()) return@withContext item.copy(
            dependencyIds = emptyList(),
            dependencyTitles = emptyMap(),
        )
        item.copy(
            dependencyIds = dependencyTitles.keys.toList(),
            dependencyTitles = dependencyTitles,
        )
    }

    suspend fun saveLoadOrder(
        context: Context,
        containerAppId: String,
        appId: Int,
        orderedItems: List<WorkshopItemUi>,
    ) = withContext(Dispatchers.IO) {
        if (orderedItems.isEmpty()) return@withContext
        val normalizedItems = orderedItems.mapIndexed { index, item ->
            item.copy(loadOrder = index)
        }
        persistLocalUiMetadata(
            context = context,
            containerAppId = containerAppId,
            appId = appId,
            items = normalizedItems,
        )

        val container = ContainerUtils.getOrCreateContainer(context, containerAppId)
        val winePrefix = container.rootDir.absolutePath + "/.wine"
        val workshopContentDir = WorkshopSyncManager.getWorkshopContentDir(winePrefix, appId)
        WorkshopSyncManager.mergeLocalModsIntoContentDir(
            localModsContentDir = WorkshopSyncManager.getLegacyLocalModsContentDir(container.rootDir, appId),
            workshopContentDir = workshopContentDir,
        )
        val orderById = normalizedItems.associate { it.publishedFileId to (it.loadOrder ?: Int.MAX_VALUE) }
        val orderedSubscriptions = if (isLocalOnlyMods(context, containerAppId)) {
            getLocalItemSubscriptions(workshopContentDir, appId)
        } else {
            getSubscriptionDetailsList(appId)
        }.sortedWith(
            compareBy<WorkshopItemSubscription> { orderById[it.publishedFileId] ?: Int.MAX_VALUE }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.publishedFileId },
        )

        WorkshopSyncManager.configureModSymlinks(
            gameRootDir = File(SteamService.getAppDirPath(appId)),
            workshopContentDir = workshopContentDir,
            items = orderedSubscriptions,
            winePrefix = winePrefix,
            gameName = SteamService.getAppInfoOf(appId)?.name ?: "",
        )
    }

    suspend fun supportsWorkshop(appId: Int): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://store.steampowered.com/app/$appId/?l=english")
            .get()
            .build()

        runCatching {
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body?.string().orEmpty()
                body.contains("Steam Workshop", ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    suspend fun browse(
        appId: Int,
        query: String,
        sortType: WorkshopSortType,
        page: Int,
    ): List<WorkshopItemUi> {
        val publishedFile = getPublishedFileRpc() ?: return emptyList()
        val request = CPublishedFile_QueryFiles_Request.newBuilder()
            .setAppid(appId)
            .setQueryType(sortType.queryType)
            .setNumperpage(BROWSE_PAGE_SIZE)
            .setPage(page)
            .setReturnDetails(true)
            .apply {
                if (query.isNotBlank()) {
                    setSearchText(query)
                }
            }
            .build()

        return publishedFile.queryFiles(request)
            .await()
            .body
            .getPublishedfiledetailsList()
            .map { it.toWorkshopItemUi() }
            .let(::applyMissingDependencies)
    }

    suspend fun getSubscriptions(
        context: Context,
        containerAppId: String,
        appId: Int,
    ): List<WorkshopItemUi> {
        val persistedMetadataById = loadPersistedMetadata(context, containerAppId, appId)
        val localItems = getLocalSubscriptions(context, containerAppId, appId)
        if (isLocalOnlyMods(context, containerAppId)) {
            return localItems
        }

        val publishedFile = getPublishedFileRpc() ?: return emptyList()
        val items = mutableListOf<WorkshopItemUi>()
        var page = 1

        while (true) {
            val request = CPublishedFile_GetUserFiles_Request.newBuilder()
                .setAppid(appId)
                .setPage(page)
                .setNumperpage(100)
                .setType("mysubscriptions")
                .setFiletype(0xFFFFFFFF.toInt())
                .build()

            val response = publishedFile.getUserFiles(request).await()
            val pageItems = response.body.getPublishedfiledetailsList()
                .map { it.toWorkshopItemUi() }
                .map { item -> applyPersistedMetadata(item, persistedMetadataById[item.publishedFileId.toString()]) }
            items += pageItems

            if (pageItems.isEmpty() || pageItems.size < 100) {
                break
            }
            page++
        }

        persistLocalUiMetadata(
            context = context,
            containerAppId = containerAppId,
            appId = appId,
            items = items,
        )

        return (items + localItems)
            .groupBy { it.publishedFileId }
            .values
            .map { candidates -> candidates.reduce(::mergeWorkshopItemUi) }
            .sortedByDescending { it.timeUpdated }
            .let(::applyMissingDependencies)
    }

    suspend fun subscribe(appId: Int, publishedFileId: Long) {
        val publishedFile = getPublishedFileRpc() ?: return
        val request = CPublishedFile_Subscribe_Request.newBuilder()
            .setAppid(appId)
            .setPublishedfileid(publishedFileId)
            .build()
        val response = publishedFile.subscribe(request).await()
        if (response.result != EResult.OK) {
            error("Subscribe failed: ${response.result}")
        }
    }

    suspend fun unsubscribe(appId: Int, publishedFileId: Long) {
        val publishedFile = getPublishedFileRpc() ?: return
        val request = CPublishedFile_Unsubscribe_Request.newBuilder()
            .setAppid(appId)
            .setPublishedfileid(publishedFileId)
            .build()
        val response = publishedFile.unsubscribe(request).await()
        if (response.result != EResult.OK) {
            error("Unsubscribe failed: ${response.result}")
        }
    }

    suspend fun subscribeAndSync(
        context: Context,
        containerAppId: String,
        appId: Int,
        item: WorkshopItemUi,
        onState: ((WorkshopDownloadState) -> Unit)? = null,
    ) {
        if (isLocalOnlyMods(context, containerAppId)) {
            syncLocalSubscription(
                context = context,
                containerAppId = containerAppId,
                appId = appId,
                item = item,
                onState = onState,
            )
            return
        }

        onState?.invoke(WorkshopDownloadState(stage = "Subscribing"))
        subscribe(appId, item.publishedFileId)

        try {
            withContext(Dispatchers.IO) {
                onState?.invoke(WorkshopDownloadState(stage = "Preparing download"))
                val container = ContainerUtils.getOrCreateContainer(context, containerAppId)
                val winePrefix = container.rootDir.absolutePath + "/.wine"
                val workshopContentDir = WorkshopSyncManager.getWorkshopContentDir(winePrefix, appId)
                onState?.invoke(WorkshopDownloadState(stage = "Fetching metadata"))
                val detail = getSubscriptionDetails(appId, item.publishedFileId, item)
                persistLocalUiMetadata(
                    context = context,
                    containerAppId = containerAppId,
                    appId = appId,
                    items = listOf(item),
                )
                val steamClient = SteamService.instance?.steamClient
                    ?: error("Steam client unavailable for workshop download")
                val licenses = SteamService.getLicensesFromDb()
                if (licenses.isEmpty()) {
                    error("No Steam licenses available for workshop download")
                }

            onState?.invoke(WorkshopDownloadState(stage = "Starting download"))
            val downloadedCount = WorkshopSyncManager.downloadItems(
                items = listOf(detail),
                steamClient = steamClient,
                licenses = licenses,
                workshopContentDir = workshopContentDir,
                onItemProgress = { _, _, _ -> },
                onBytesProgress = { downloadedBytes, totalBytes ->
                    onState?.invoke(
                        WorkshopDownloadState(
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            stage = "Downloading",
                        ),
                    )
                },
            )
            if (downloadedCount != 1) {
                error("Workshop download did not complete for ${item.publishedFileId}")
            }

                onState?.invoke(
                    WorkshopDownloadState(
                        downloadedBytes = detail.fileSizeBytes,
                        totalBytes = detail.fileSizeBytes,
                        stage = "Installing",
                    ),
                )
                WorkshopSyncManager.finalizeDownloadedItems(listOf(detail), workshopContentDir)
                WorkshopSyncManager.updateMarkerTimestamps(listOf(detail), workshopContentDir)
                onState?.invoke(
                    WorkshopDownloadState(
                        downloadedBytes = detail.fileSizeBytes,
                        totalBytes = detail.fileSizeBytes,
                        stage = "Configuring",
                    ),
                )
                WorkshopSyncManager.configureModSymlinks(
                    gameRootDir = java.io.File(SteamService.getAppDirPath(appId)),
                    workshopContentDir = workshopContentDir,
                    items = getSubscriptionDetailsList(appId),
                    winePrefix = winePrefix,
                    gameName = SteamService.getAppInfoOf(appId)?.name ?: "",
                )
            }
        } catch (t: Throwable) {
            runCatching {
                unsubscribeAndSync(
                    context = context,
                    containerAppId = containerAppId,
                    appId = appId,
                    publishedFileId = item.publishedFileId,
                )
            }.onFailure {
                Log.w(
                    "WorkshopService",
                    "Failed rolling back workshop subscribe for $appId/${item.publishedFileId}",
                    it,
                )
            }
            throw t
        }
    }

    suspend fun unsubscribeAndSync(
        context: Context,
        containerAppId: String,
        appId: Int,
        publishedFileId: Long,
    ) {
        if (isLocalOnlyMods(context, containerAppId)) {
            removeLocalSubscription(
                context = context,
                containerAppId = containerAppId,
                appId = appId,
                publishedFileId = publishedFileId,
            )
            return
        }

        unsubscribe(appId, publishedFileId)

        runCatching {
            withContext(Dispatchers.IO) {
                val container = ContainerUtils.getOrCreateContainer(context, containerAppId)
                val winePrefix = container.rootDir.absolutePath + "/.wine"
                val workshopContentDir = WorkshopSyncManager.getWorkshopContentDir(winePrefix, appId)
                java.io.File(workshopContentDir, publishedFileId.toString()).deleteRecursively()
                java.io.File(workshopContentDir, "$publishedFileId.partial").deleteRecursively()

                WorkshopSyncManager.configureModSymlinks(
                    gameRootDir = java.io.File(SteamService.getAppDirPath(appId)),
                    workshopContentDir = workshopContentDir,
                    items = getSubscriptionDetailsList(appId),
                    winePrefix = winePrefix,
                    gameName = SteamService.getAppInfoOf(appId)?.name ?: "",
                )
            }
        }.onFailure {
            Log.w("WorkshopService", "Unsubscribe sync follow-up failed for $appId/$publishedFileId", it)
        }
    }

    private suspend fun isLocalOnlyMods(
        context: Context,
        containerAppId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        ContainerUtils.getOrCreateContainer(context, containerAppId)
            .getExtra("localOnlyMods", "false")
            .toBoolean()
    }

    private suspend fun getLocalSubscriptions(
        context: Context,
        containerAppId: String,
        appId: Int,
    ): List<WorkshopItemUi> = withContext(Dispatchers.IO) {
        val container = ContainerUtils.getOrCreateContainer(context, containerAppId)
        val workshopContentDir = WorkshopSyncManager.getWorkshopContentDir(
            winePrefix = container.rootDir.absolutePath + "/.wine",
            appId = appId,
        )
        WorkshopSyncManager.mergeLocalModsIntoContentDir(
            localModsContentDir = WorkshopSyncManager.getLegacyLocalModsContentDir(container.rootDir, appId),
            workshopContentDir = workshopContentDir,
        )
        if (!workshopContentDir.isDirectory) return@withContext emptyList()

        val metadataById = loadLocalMetadata(workshopContentDir)
        workshopContentDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.map { dir -> dir.toLocalWorkshopItemUi(metadataById[dir.name]) }
            ?.sortedByDescending { it.timeUpdated }
            ?.toList()
            ?: emptyList()
    }

    private suspend fun syncLocalSubscription(
        context: Context,
        containerAppId: String,
        appId: Int,
        item: WorkshopItemUi,
        onState: ((WorkshopDownloadState) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            onState?.invoke(WorkshopDownloadState(stage = "Preparing download"))
            val container = ContainerUtils.getOrCreateContainer(context, containerAppId)
            val winePrefix = container.rootDir.absolutePath + "/.wine"
            val workshopContentDir = WorkshopSyncManager.getWorkshopContentDir(winePrefix, appId)
            WorkshopSyncManager.mergeLocalModsIntoContentDir(
                localModsContentDir = WorkshopSyncManager.getLegacyLocalModsContentDir(container.rootDir, appId),
                workshopContentDir = workshopContentDir,
            )
            onState?.invoke(WorkshopDownloadState(stage = "Fetching metadata"))
            val detail = getSubscriptionDetails(appId, item.publishedFileId, item)
            persistLocalUiMetadata(
                context = context,
                containerAppId = containerAppId,
                appId = appId,
                items = listOf(item),
            )
            val steamClient = SteamService.instance?.steamClient ?: return@withContext
            val licenses = SteamService.getLicensesFromDb()
            if (licenses.isEmpty()) return@withContext

            onState?.invoke(WorkshopDownloadState(stage = "Starting download"))
            val downloadedCount = WorkshopSyncManager.downloadItems(
                items = listOf(detail),
                steamClient = steamClient,
                licenses = licenses,
                workshopContentDir = workshopContentDir,
                onItemProgress = { _, _, _ -> },
                onBytesProgress = { downloadedBytes, totalBytes ->
                    onState?.invoke(
                        WorkshopDownloadState(
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            stage = "Downloading",
                        ),
                    )
                },
            )
            if (downloadedCount != 1) {
                error("Workshop download did not complete for ${item.publishedFileId}")
            }

            onState?.invoke(
                WorkshopDownloadState(
                    downloadedBytes = detail.fileSizeBytes,
                    totalBytes = detail.fileSizeBytes,
                    stage = "Installing",
                ),
            )
            WorkshopSyncManager.finalizeDownloadedItems(listOf(detail), workshopContentDir)
            WorkshopSyncManager.updateMarkerTimestamps(listOf(detail), workshopContentDir)
            onState?.invoke(
                WorkshopDownloadState(
                    downloadedBytes = detail.fileSizeBytes,
                    totalBytes = detail.fileSizeBytes,
                    stage = "Configuring",
                ),
            )
            val localItems = getLocalItemSubscriptions(workshopContentDir, appId, listOf(detail))
            WorkshopSyncManager.configureModSymlinks(
                gameRootDir = File(SteamService.getAppDirPath(appId)),
                workshopContentDir = workshopContentDir,
                items = localItems,
                winePrefix = winePrefix,
                gameName = SteamService.getAppInfoOf(appId)?.name ?: "",
            )
        }
    }

    private suspend fun removeLocalSubscription(
        context: Context,
        containerAppId: String,
        appId: Int,
        publishedFileId: Long,
    ) {
        withContext(Dispatchers.IO) {
            val container = ContainerUtils.getOrCreateContainer(context, containerAppId)
            val winePrefix = container.rootDir.absolutePath + "/.wine"
            val workshopContentDir = WorkshopSyncManager.getWorkshopContentDir(winePrefix, appId)
            val legacyLocalModsContentDir = WorkshopSyncManager.getLegacyLocalModsContentDir(container.rootDir, appId)
            File(workshopContentDir, publishedFileId.toString()).deleteRecursively()
            File(workshopContentDir, "$publishedFileId.partial").deleteRecursively()
            File(legacyLocalModsContentDir, publishedFileId.toString()).deleteRecursively()

            WorkshopSyncManager.configureModSymlinks(
                gameRootDir = File(SteamService.getAppDirPath(appId)),
                workshopContentDir = workshopContentDir,
                items = getLocalItemSubscriptions(workshopContentDir, appId),
                winePrefix = winePrefix,
                gameName = SteamService.getAppInfoOf(appId)?.name ?: "",
            )
        }
    }

    private fun getPublishedFileRpc(): PublishedFile? {
        val unifiedMessages = SteamService.instance?.steamClient
            ?.getHandler(SteamUnifiedMessages::class.java) ?: return null
        return unifiedMessages.createService(PublishedFile::class.java)
    }

    private suspend fun getSubscriptionDetails(
        appId: Int,
        publishedFileId: Long,
        fallback: WorkshopItemUi,
    ): WorkshopItemSubscription {
        val publishedFile = getPublishedFileRpc() ?: error("Workshop service unavailable")
        val detail = withTimeoutOrNull(WORKSHOP_DETAILS_TIMEOUT_MS) {
            val request = CPublishedFile_GetDetails_Request.newBuilder()
                .addPublishedfileids(publishedFileId)
                .build()
            publishedFile.getDetails(request).await()
                .body
                .getPublishedfiledetailsList()
                .firstOrNull()
        } ?: run {
            Log.w(
                "WorkshopService",
                "GetDetails timed out for $appId/$publishedFileId, falling back to subscription list",
            )
            getSubscriptionDetailsList(appId).firstOrNull { it.publishedFileId == publishedFileId }?.let { return it }
            return WorkshopItemSubscription(
                publishedFileId = fallback.publishedFileId,
                appId = appId,
                title = fallback.title,
                fileSizeBytes = fallback.sizeBytes,
                manifestId = 0L,
                timeUpdated = fallback.timeUpdated,
                previewUrl = fallback.previewUrl,
            )
        }

        return WorkshopItemSubscription(
            publishedFileId = detail.publishedfileid,
            appId = if (detail.consumerAppid != 0) detail.consumerAppid else appId,
            title = detail.title.ifEmpty { fallback.title },
            fileSizeBytes = detail.fileSize,
            manifestId = detail.hcontentFile,
            timeUpdated = detail.timeUpdated.toLong(),
            fileUrl = detail.fileUrl ?: "",
            fileName = detail.filename ?: "",
            previewUrl = detail.previewUrl ?: fallback.previewUrl,
        )
    }

    private suspend fun getSubscriptionDetailsList(appId: Int): List<WorkshopItemSubscription> {
        val publishedFile = getPublishedFileRpc() ?: return emptyList()
        val items = mutableListOf<WorkshopItemSubscription>()
        var page = 1

        while (true) {
            val request = CPublishedFile_GetUserFiles_Request.newBuilder()
                .setAppid(appId)
                .setPage(page)
                .setNumperpage(100)
                .setType("mysubscriptions")
                .setFiletype(0xFFFFFFFF.toInt())
                .build()
            val response = publishedFile.getUserFiles(request).await()
            val pageItems = response.body.getPublishedfiledetailsList().map { details ->
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
            }
            items += pageItems
            if (pageItems.isEmpty() || pageItems.size < 100) break
            page++
        }

        return items.distinctBy { it.publishedFileId }
    }

    private fun getLocalItemSubscriptions(
        workshopContentDir: File,
        appId: Int,
        preferredItems: List<WorkshopItemSubscription> = emptyList(),
    ): List<WorkshopItemSubscription> {
        val metadataById = loadLocalMetadata(workshopContentDir)
        val preferredById = preferredItems.associateBy { it.publishedFileId }

        return workshopContentDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.mapNotNull { dir ->
                val publishedFileId = dir.name.toLongOrNull() ?: return@mapNotNull null
                preferredById[publishedFileId] ?: dir.toLocalWorkshopSubscription(
                    appId = appId,
                    metadata = metadataById[dir.name],
                )
            }
            ?.sortedByDescending { it.timeUpdated }
            ?.toList()
            ?: emptyList()
    }

    private fun loadLocalMetadata(workshopContentDir: File): Map<String, JSONObject> {
        val metadataFiles = sequenceOf(
            File(workshopContentDir.parentFile?.parentFile?.parentFile?.parentFile, "steam_settings/mods.json"),
            File(workshopContentDir.parentFile?.parentFile?.parentFile?.parentFile, "steam_settings/$LOCAL_WORKSHOP_UI_METADATA_FILE"),
        ).filterNotNull()

        val merged = linkedMapOf<String, JSONObject>()
        metadataFiles.forEach { file ->
            if (!file.isFile) return@forEach
            runCatching {
                val root = JSONObject(file.readText())
                root.keys().asSequence().forEach { key ->
                    val incoming = root.optJSONObject(key) ?: return@forEach
                    val current = merged[key]
                    merged[key] = if (current == null) {
                        incoming
                    } else {
                        JSONObject(current.toString()).apply {
                            incoming.keys().forEach { incomingKey ->
                                put(incomingKey, incoming.get(incomingKey))
                            }
                        }
                    }
                }
            }.onFailure {
                Log.w("WorkshopService", "Failed reading local workshop metadata from ${file.absolutePath}", it)
            }
        }

        return merged
    }

    private suspend fun loadPersistedMetadata(
        context: Context,
        containerAppId: String,
        appId: Int,
    ): Map<String, JSONObject> = withContext(Dispatchers.IO) {
        val container = ContainerUtils.getOrCreateContainer(context, containerAppId)
        val workshopContentDir = WorkshopSyncManager.getWorkshopContentDir(
            winePrefix = container.rootDir.absolutePath + "/.wine",
            appId = appId,
        )
        loadLocalMetadata(workshopContentDir)
    }

    private suspend fun persistLocalUiMetadata(
        context: Context,
        containerAppId: String,
        appId: Int,
        items: List<WorkshopItemUi>,
    ) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val container = ContainerUtils.getOrCreateContainer(context, containerAppId)
        val workshopContentDir = WorkshopSyncManager.getWorkshopContentDir(
            winePrefix = container.rootDir.absolutePath + "/.wine",
            appId = appId,
        )
        val settingsDir = workshopContentDir.parentFile?.parentFile?.parentFile?.parentFile
            ?.resolve("steam_settings")
            ?: return@withContext
        settingsDir.mkdirs()
        val metadataFile = File(settingsDir, LOCAL_WORKSHOP_UI_METADATA_FILE)
        val root = runCatching {
            if (metadataFile.isFile) JSONObject(metadataFile.readText()) else JSONObject()
        }.getOrDefault(JSONObject())
        items.forEach { item ->
            root.put(item.publishedFileId.toString(), JSONObject().apply {
                put("title", item.title)
                put("description", item.description)
                put("preview_url", item.previewUrl)
                put("subscriber_count", item.subscriberCount)
                put("favorited_count", item.favoritedCount)
                put("view_count", item.viewCount)
                put("size_bytes", item.sizeBytes)
                put("time_created", item.timeCreated)
                put("time_updated", item.timeUpdated)
                put("tags", org.json.JSONArray(item.tags))
                item.loadOrder?.let { put("load_order", it) }
                put("dependency_ids", org.json.JSONArray(item.dependencyIds))
                put(
                    "dependency_titles",
                    JSONObject().apply {
                        item.dependencyTitles.forEach { (id, title) -> put(id.toString(), title) }
                    },
                )
            })
        }
        metadataFile.writeText(root.toString(2))
    }

    private fun applyMissingDependencies(items: List<WorkshopItemUi>): List<WorkshopItemUi> {
        if (items.isEmpty()) return items
        val subscribedIds = items.map { it.publishedFileId }.toSet()
        return items.map { item ->
            item.copy(
                missingDependencyIds = item.dependencyIds.filterNot { it in subscribedIds },
            )
        }
    }
}

private fun PublishedFileDetails.toWorkshopItemUi(): WorkshopItemUi = WorkshopItemUi(
    publishedFileId = publishedfileid,
    title = title,
    previewUrl = previewUrl,
    description = fileDescription,
    subscriberCount = subscriptions,
    favoritedCount = favorited,
    viewCount = views,
    sizeBytes = fileSize,
    timeCreated = timeCreated.toLong(),
    timeUpdated = timeUpdated.toLong(),
    tags = tagsList.map { it.tag },
)

private fun mergeWorkshopItemUi(
    preferred: WorkshopItemUi,
    fallback: WorkshopItemUi,
): WorkshopItemUi {
    return preferred.copy(
        title = preferred.title.takeUnless { it.isBlank() || it == preferred.publishedFileId.toString() }
            ?: fallback.title,
        previewUrl = preferred.previewUrl.ifBlank { fallback.previewUrl },
        description = preferred.description.ifBlank { fallback.description },
        subscriberCount = preferred.subscriberCount.takeIf { it > 0 } ?: fallback.subscriberCount,
        favoritedCount = preferred.favoritedCount.takeIf { it > 0 } ?: fallback.favoritedCount,
        viewCount = preferred.viewCount.takeIf { it > 0 } ?: fallback.viewCount,
        sizeBytes = preferred.sizeBytes.takeIf { it > 0L } ?: fallback.sizeBytes,
        timeCreated = preferred.timeCreated.takeIf { it > 0L } ?: fallback.timeCreated,
        timeUpdated = maxOf(preferred.timeUpdated, fallback.timeUpdated),
        tags = preferred.tags.ifEmpty { fallback.tags },
        loadOrder = preferred.loadOrder ?: fallback.loadOrder,
        dependencyIds = preferred.dependencyIds.ifEmpty { fallback.dependencyIds },
        dependencyTitles = preferred.dependencyTitles.ifEmpty { fallback.dependencyTitles },
        missingDependencyIds = preferred.missingDependencyIds.ifEmpty { fallback.missingDependencyIds },
    )
}

private fun applyPersistedMetadata(
    item: WorkshopItemUi,
    metadata: JSONObject?,
): WorkshopItemUi {
    if (metadata == null) return item
    val persisted = WorkshopItemUi(
        publishedFileId = item.publishedFileId,
        title = metadata.optString("title"),
        previewUrl = metadata.optString("preview_url"),
        description = metadata.optString("description"),
        subscriberCount = metadata.optInt("subscriber_count"),
        favoritedCount = metadata.optInt("favorited_count"),
        viewCount = metadata.optInt("view_count"),
        sizeBytes = metadata.optLong("size_bytes"),
        timeCreated = metadata.optLong("time_created").takeIf { it > 0L } ?: item.timeCreated,
        timeUpdated = metadata.optLong("time_updated").takeIf { it > 0L } ?: item.timeUpdated,
        loadOrder = metadata.takeIf { it.has("load_order") }?.optInt("load_order"),
        tags = metadata.optJSONArray("tags")?.let { tags ->
            List(tags.length()) { index -> tags.optString(index) }.filter { it.isNotBlank() }
        } ?: emptyList(),
        dependencyIds = metadata.optJSONArray("dependency_ids")?.let { ids ->
            List(ids.length()) { index -> ids.optLong(index) }.filter { it > 0L }
        } ?: emptyList(),
        dependencyTitles = metadata.optJSONObject("dependency_titles")?.let { titles ->
            titles.keys().asSequence()
                .mapNotNull { key ->
                    key.toLongOrNull()?.let { id ->
                        id to titles.optString(key).trim()
                    }
                }
                .filter { (_, title) -> title.isNotBlank() }
                .toMap()
        } ?: emptyMap(),
    )
    return mergeWorkshopItemUi(persisted, item)
}

private fun File.toLocalWorkshopItemUi(metadata: JSONObject?): WorkshopItemUi {
    val publishedFileId = name.toLong()
    val primaryFile = listFiles()
        ?.firstOrNull { file ->
            file.isFile &&
                !file.name.startsWith(".") &&
                !file.name.equals("preview.jpg", ignoreCase = true)
        }
    val markerTime = File(this, ".workshop_complete").takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.toLongOrNull()
    val previewFile = File(this, "preview.jpg").takeIf { it.isFile }
    val totalSize = if (isDirectory) {
        walkTopDown()
            .filter { file -> file.isFile && !file.name.startsWith(".") }
            .sumOf { it.length() }
    } else {
        0L
    }

    return WorkshopItemUi(
        publishedFileId = publishedFileId,
        title = metadata?.optString("title").orEmpty()
            .ifBlank { WorkshopSyncManager.readModNameFromDir(this).orEmpty() }
            .ifBlank { primaryFile?.nameWithoutExtension?.replace('_', ' ') ?: name },
        previewUrl = previewFile?.absolutePath ?: metadata?.optString("preview_url").orEmpty(),
        description = metadata?.optString("description").orEmpty(),
        subscriberCount = metadata?.optInt("subscriber_count") ?: 0,
        favoritedCount = metadata?.optInt("favorited_count") ?: 0,
        viewCount = metadata?.optInt("view_count") ?: 0,
        sizeBytes = metadata?.optLong("size_bytes")?.takeIf { it > 0L } ?: totalSize,
        timeCreated = metadata?.optLong("time_created")?.takeIf { it > 0L } ?: 0L,
        timeUpdated = metadata?.optLong("time_updated")?.takeIf { it > 0L }
            ?: markerTime
            ?: (lastModified() / 1000L),
        loadOrder = metadata?.takeIf { it.has("load_order") }?.optInt("load_order"),
        tags = metadata?.optJSONArray("tags")?.let { tags ->
            List(tags.length()) { index -> tags.optString(index) }.filter { it.isNotBlank() }
        } ?: emptyList(),
        dependencyIds = metadata?.optJSONArray("dependency_ids")?.let { ids ->
            List(ids.length()) { index -> ids.optLong(index) }.filter { it > 0L }
        } ?: emptyList(),
        dependencyTitles = metadata?.optJSONObject("dependency_titles")?.let { titles ->
            titles.keys().asSequence()
                .mapNotNull { key ->
                    key.toLongOrNull()?.let { id ->
                        id to titles.optString(key).trim()
                    }
                }
                .filter { (_, title) -> title.isNotBlank() }
                .toMap()
        } ?: emptyMap(),
    )
}

private fun File.toLocalWorkshopSubscription(
    appId: Int,
    metadata: JSONObject?,
): WorkshopItemSubscription {
    val itemUi = toLocalWorkshopItemUi(metadata)
    val primaryFile = listFiles()
        ?.firstOrNull { file ->
            file.isFile &&
                !file.name.startsWith(".") &&
                !file.name.equals("preview.jpg", ignoreCase = true)
        }

    return WorkshopItemSubscription(
        publishedFileId = itemUi.publishedFileId,
        appId = appId,
        title = itemUi.title,
        fileSizeBytes = itemUi.sizeBytes,
        manifestId = 0L,
        timeUpdated = itemUi.timeUpdated,
        fileUrl = "",
        fileName = primaryFile?.name ?: "",
        previewUrl = itemUi.previewUrl,
    )
}

private fun fetchRequiredItemsFromWorkshopPage(publishedFileId: Long): Map<Long, String> {
    val request = Request.Builder()
        .url("https://steamcommunity.com/sharedfiles/filedetails/?id=$publishedFileId")
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        )
        .get()
        .build()

    val html = runCatching {
        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyMap()
            response.body?.string().orEmpty()
        }
    }.getOrDefault("")
    if (html.isBlank()) return emptyMap()

    val requiredItemsStart = html.indexOf("id=\"RequiredItems\"", ignoreCase = true)
    if (requiredItemsStart == -1) return emptyMap()
    val section = html.substring(
        startIndex = requiredItemsStart,
        endIndex = minOf(html.length, requiredItemsStart + 4_000),
    )
    if (section.isBlank()) return emptyMap()

    val dependencyTitles = linkedMapOf<Long, String>()
    Regex(
        """<a[^>]+href="(?:https://steamcommunity\.com)?/(?:workshop|sharedfiles)/filedetails/\?id=(\d+)"[^>]*>.*?<div[^>]*class="requiredItem"[^>]*>\s*(.*?)\s*</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).findAll(section).forEach { match ->
        val id = match.groupValues[1].toLongOrNull() ?: return@forEach
        val rawTitle = match.groupValues[2]
        val title = Html.fromHtml(rawTitle, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        if (title.isNotBlank()) {
            dependencyTitles[id] = title
        }
    }
    return dependencyTitles
}
