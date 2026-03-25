package app.gamenative.utils

import app.gamenative.data.WorkshopItem
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import timber.log.Timber
import java.io.File
import java.nio.file.Files

object WorkshopEnvironmentSetup {

    private const val STEAM_ROOT = "C:\\Program Files (x86)\\Steam"
    private const val WORKSHOP_BASE = ".wine/drive_c/Program Files (x86)/Steam/steamapps/workshop"
    private const val STEAM_SETTINGS = ".wine/drive_c/Program Files (x86)/Steam/steam_settings"

    // Clean path with no spaces or special chars — P: drive points here.
    // Used for mod content so Wine's FindFirstFileW can enumerate files inside.
    private const val MODS_BASE = "workshopmods"

    // ── Registry ─────────────────────────────────────────────────────────────

    /**
     * Writes HKLM InstallPath keys to system.reg so 32-bit games and older
     * Steamworks SDK versions can locate steamapps. HKCU is already written
     * by SteamUtils.autoLoginUserChanges(); only HKLM is missing.
     */
    fun setupRegistry(container: Container) {
        val systemRegFile = File(container.rootDir, ".wine/system.reg")
        try {
            WineRegistryEditor(systemRegFile).use { reg ->
                reg.setStringValue("Software\\Valve\\Steam", "InstallPath", STEAM_ROOT)
                reg.setStringValue("Software\\Wow6432Node\\Valve\\Steam", "InstallPath", STEAM_ROOT)
            }
        } catch (e: Exception) {
            Timber.w(e, "WorkshopEnvironmentSetup: failed to write HKLM registry keys")
        }
    }

    /**
     * Writes steam_settings/subscribed_ids.txt for the Goldberg Steam emulator.
     * ISteamUGC::GetSubscribedItems() reads this file to know which workshop items are installed.
     * Written to both the Wine C: path and next to the steam_api DLL in the game directory.
     */
    fun writeSubscribedIds(container: Container, appId: Int, items: List<WorkshopItem>) {
        val content = items.joinToString("\n") { it.publishedFileId.toString() }
        // Wine C: path (old Goldberg fallback)
        val wineFile = File(container.rootDir, "$STEAM_SETTINGS/subscribed_ids.txt")
        try {
            wineFile.parentFile?.mkdirs()
            wineFile.writeText(content)
        } catch (e: Exception) {
            Timber.w(e, "WorkshopEnvironmentSetup: failed to write subscribed_ids.txt (Wine) for appId=$appId")
        }
        // DLL-relative path (where our Goldberg DLL actually reads from)
        dllSteamSettingsDir(appId)?.let { dllSettings ->
            try {
                dllSettings.mkdirs()
                File(dllSettings, "subscribed_ids.txt").writeText(content)
            } catch (e: Exception) {
                Timber.w(e, "WorkshopEnvironmentSetup: failed to write subscribed_ids.txt (DLL-relative) for appId=$appId")
            }
        }
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    /** Real filesystem path for mod content — no spaces or parens, Wine can enumerate files here. */
    fun getWorkshopContentPath(container: Container, appId: Int, itemId: Long): File =
        File(container.rootDir, "$MODS_BASE/content/$appId/$itemId")

    fun getAcfPath(container: Container, appId: Int): File =
        File(container.rootDir, "$WORKSHOP_BASE/appworkshop_$appId.acf")

    /**
     * Writes steam_settings/mods/<itemId>/mod_metadata for each item so Goldberg's
     * GetItemInstallInfo() returns the P: drive path, which Wine can enumerate cleanly.
     * P: is mapped to <containerRoot>/workshopmods/ by WineUtils.createDosdevicesSymlinks().
     * Written to both the Wine C: path and next to the steam_api DLL in the game directory.
     */
    fun writeGoldbergMods(container: Container, appId: Int, items: List<WorkshopItem>) {
        items.forEach { item ->
            // Wine C: path (old Goldberg fallback)
            val wineModDir = File(container.rootDir, "$STEAM_SETTINGS/mods/${item.publishedFileId}")
            try {
                wineModDir.mkdirs()
                File(wineModDir, "mod_metadata").writeText(
                    "install_path=p:\\content\\$appId\\${item.publishedFileId}\n"
                )
            } catch (e: Exception) {
                Timber.w(e, "WorkshopEnvironmentSetup: failed to write mod_metadata (Wine) for ${item.publishedFileId}")
            }
            // DLL-relative path — gbe_fork returns this folder directly as GetItemInstallInfo() result.
            // Symlink it to the real content dir so the actual mod files are inside it.
            dllSteamSettingsDir(appId)?.let { dllSettings ->
                val dllModDir = File(dllSettings, "mods/${item.publishedFileId}")
                val contentDir = getWorkshopContentPath(container, appId, item.publishedFileId)
                try {
                    dllModDir.parentFile?.mkdirs()
                    // Replace any existing entry (symlink, file, or dir) with the symlink
                    val dllModPath = dllModDir.toPath()
                    if (Files.isSymbolicLink(dllModPath)) Files.deleteIfExists(dllModPath)
                    else if (dllModDir.exists()) dllModDir.deleteRecursively()
                    com.winlator.core.FileUtils.symlink(contentDir.absolutePath, dllModDir.absolutePath)
                } catch (e: Exception) {
                    Timber.w(e, "WorkshopEnvironmentSetup: failed to symlink DLL-relative mod dir for ${item.publishedFileId}")
                }
            }
        }
    }

    // ── ACF writing ───────────────────────────────────────────────────────────

    /**
     * Writes (or overwrites) appworkshop_<appId>.acf so games enumerate installed items.
     * If an ACF already exists and is parseable, items not in [items] are preserved.
     * If the file is missing or malformed, a fresh ACF is written.
     */
    fun writeAcf(container: Container, appId: Int, items: List<WorkshopItem>) {
        val acfFile = getAcfPath(container, appId)
        try {
            acfFile.parentFile?.mkdirs()

            // Merge: start with existing items from disk (if parseable), then overwrite with supplied list
            val existingItems: MutableMap<Long, WorkshopItem> = mutableMapOf()
            if (acfFile.exists()) {
                try {
                    parseAcfItems(acfFile, appId)?.forEach { existingItems[it.publishedFileId] = it }
                } catch (_: Exception) {
                    // Malformed ACF — start fresh
                }
            }
            items.forEach { existingItems[it.publishedFileId] = it }

            val allItems = existingItems.values
            val totalSize = allItems.sumOf { it.sizeBytes }
            val now = System.currentTimeMillis() / 1000L

            val sb = StringBuilder()
            sb.appendLine("\"AppWorkshop\"")
            sb.appendLine("{")
            sb.appendLine("\t\"appid\"\t\t\t\"$appId\"")
            sb.appendLine("\t\"SizeOnDisk\"\t\t\"$totalSize\"")
            sb.appendLine("\t\"NeedsUpdate\"\t\t\"0\"")
            sb.appendLine("\t\"NeedsDownload\"\t\t\"0\"")
            sb.appendLine("\t\"TimeLastUpdated\"\t\"$now\"")
            sb.appendLine("\t\"TimeLastAppRan\"\t\"$now\"")
            sb.appendLine("\t\"WorkshopItemsInstalled\"")
            sb.appendLine("\t{")
            allItems.forEach { item ->
                sb.appendLine("\t\t\"${item.publishedFileId}\"")
                sb.appendLine("\t\t{")
                sb.appendLine("\t\t\t\"size\"\t\t\t\"${item.sizeBytes}\"")
                sb.appendLine("\t\t\t\"timeupdated\"\t\"${item.timeUpdated}\"")
                sb.appendLine("\t\t\t\"manifest\"\t\t\"${item.manifestId}\"")
                sb.appendLine("\t\t\t\"ugchandle\"\t\t\"${item.ugcHandle}\"")
                sb.appendLine("\t\t\t\"download_folder\"\t\"steamapps/workshop/content/$appId/${item.publishedFileId}\"")
                sb.appendLine("\t\t}")
            }
            sb.appendLine("\t}")
            sb.appendLine("\t\"WorkshopItemDetails\"")
            sb.appendLine("\t{")
            allItems.forEach { item ->
                sb.appendLine("\t\t\"${item.publishedFileId}\"")
                sb.appendLine("\t\t{")
                sb.appendLine("\t\t\t\"manifest\"\t\t\"${item.manifestId}\"")
                sb.appendLine("\t\t\t\"ugchandle\"\t\t\"${item.ugcHandle}\"")
                sb.appendLine("\t\t\t\"timeupdated\"\t\"${item.timeUpdated}\"")
                sb.appendLine("\t\t\t\"timetouched\"\t\"$now\"")
                sb.appendLine("\t\t}")
            }
            sb.appendLine("\t}")
            sb.append("}")

            acfFile.writeText(sb.toString())
            val allItemsList = existingItems.values.toList()
            writeSubscribedIds(container, appId, allItemsList)
            writeGoldbergMods(container, appId, allItemsList)
        } catch (e: Exception) {
            Timber.w(e, "WorkshopEnvironmentSetup: failed to write ACF for appId=$appId")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the steam_settings directory next to the game's steam_api DLL, or null if not found.
     * Our Goldberg DLL reads subscribed_ids.txt and mods/ relative to itself, not from Wine C:.
     */
    private fun dllSteamSettingsDir(appId: Int): File? {
        val appDirPath = SteamService.getAppDirPath(appId)
        val dllDir = SteamUtils.findSteamApiDllRootFile(File(appDirPath), 10) ?: return null
        return File(dllDir, "steam_settings")
    }

    /**
     * Minimal ACF parser — extracts only the publishedFileId, sizeBytes, timeUpdated,
     * manifestId, ugcHandle from an existing ACF file. Returns null if the file cannot
     * be parsed.
     */
    private fun parseAcfItems(acfFile: File, appId: Int): List<WorkshopItem>? {
        return try {
            val items = mutableListOf<WorkshopItem>()
            val lines = acfFile.readLines()
            var inInstalled = false
            var depth = 0
            var currentId: Long? = null
            var size = 0L
            var timeUpdated = 0L
            var manifestId = -1L
            var ugcHandle = 0L

            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed == "{" -> {
                        depth++
                        if (depth == 2 && inInstalled && currentId != null) {
                            // entering item block
                        }
                    }
                    trimmed == "}" -> {
                        if (depth == 3 && inInstalled && currentId != null) {
                            items += WorkshopItem(
                                publishedFileId = currentId!!,
                                appId = appId,
                                sizeBytes = size,
                                timeUpdated = timeUpdated,
                                manifestId = manifestId,
                                ugcHandle = ugcHandle,
                                state = app.gamenative.data.WorkshopItemState.INSTALLED,
                            )
                            currentId = null; size = 0L; timeUpdated = 0L; manifestId = -1L; ugcHandle = 0L
                        }
                        depth--
                        if (depth == 1) inInstalled = false
                    }
                    depth == 1 && trimmed.startsWith("\"WorkshopItemsInstalled\"") -> inInstalled = true
                    depth == 2 && inInstalled && currentId == null -> {
                        currentId = trimmed.removeSurrounding("\"").toLongOrNull()
                    }
                    depth == 3 && inInstalled -> {
                        val kv = trimmed.split("\t+".toRegex()).filter { it.isNotEmpty() }
                        if (kv.size >= 2) {
                            val k = kv[0].removeSurrounding("\"")
                            val v = kv[1].removeSurrounding("\"")
                            when (k) {
                                "size" -> size = v.toLongOrNull() ?: 0L
                                "timeupdated" -> timeUpdated = v.toLongOrNull() ?: 0L
                                "manifest" -> manifestId = v.toLongOrNull() ?: -1L
                                "ugchandle" -> ugcHandle = v.toLongOrNull() ?: 0L
                            }
                        }
                    }
                }
            }
            items
        } catch (_: Exception) {
            null
        }
    }

}
