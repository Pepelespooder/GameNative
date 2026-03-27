package app.gamenative.workshop

import java.io.File
import java.nio.file.Files
import timber.log.Timber

class WorkshopSymlinker {
    companion object {
        private const val TAG = "WorkshopSymlinker"
        private const val COPY_SENTINEL = ".gamenative_workshop"

        private val MOD_CONTAINER_NAMES = setOf(
            "mods", "mod", "addons", "addon", "plugins", "plugin",
            "workshop_mods", "usermods", "user_mods", "modules", "module", "ugc",
        )
    }

    data class SyncResult(
        val created: Int,
        val skipped: Int,
        val removed: Int,
        val errors: Map<String, String>,
    )

    fun sync(
        strategy: WorkshopModPathStrategy,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
        itemTitles: Map<Long, String> = emptyMap(),
    ): SyncResult {
        return when (strategy) {
            WorkshopModPathStrategy.Standard -> SyncResult(0, activeItemDirs.size, 0, emptyMap())
            is WorkshopModPathStrategy.SymlinkIntoDir -> {
                syncIntoAllDirs(strategy.effectiveDirs, activeItemDirs, workshopContentBase, true, itemTitles)
            }
            is WorkshopModPathStrategy.CopyIntoDir -> {
                syncIntoAllDirs(strategy.effectiveDirs, activeItemDirs, workshopContentBase, false, itemTitles)
            }
        }
    }

    private fun syncIntoAllDirs(
        targetDirs: List<File>,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
        useSymlinks: Boolean,
        itemTitles: Map<Long, String>,
    ): SyncResult {
        var created = 0
        var skipped = 0
        var removed = 0
        val errors = linkedMapOf<String, String>()
        targetDirs.forEach { dir ->
            val result = syncIntoOneDir(dir, activeItemDirs, workshopContentBase, useSymlinks, itemTitles)
            created += result.created
            skipped += result.skipped
            removed += result.removed
            errors += result.errors.mapKeys { "${dir.name}/${it.key}" }
        }
        return SyncResult(created, skipped, removed, errors)
    }

    private fun syncIntoOneDir(
        targetDir: File,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
        useSymlinks: Boolean,
        itemTitles: Map<Long, String>,
    ): SyncResult {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val errors = linkedMapOf<String, String>()
        var created = 0
        var skipped = 0
        var removed = 0

        val isModContainerDir = targetDir.name.lowercase() in MOD_CONTAINER_NAMES
        val allSingleFile = !isModContainerDir && activeItemDirs.values.all { srcDir ->
            val children = srcDir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
            children.size == 1 && children.single().isFile
        }

        if (allSingleFile && useSymlinks) {
            return syncFlatFilesIntoDir(targetDir, activeItemDirs, workshopContentBase)
        }

        val usedNames = mutableSetOf<String>()
        val entryNameForId = mutableMapOf<Long, String>()
        activeItemDirs.keys.forEach { id ->
            val baseName = sanitizeFileName(itemTitles[id].orEmpty()).ifBlank { id.toString() }
            var candidate = baseName
            var suffix = 1
            while (candidate in usedNames) {
                candidate = "${baseName}_$suffix"
                suffix++
            }
            usedNames += candidate
            entryNameForId[id] = candidate
        }
        val expectedNames = entryNameForId.values.toSet()

        targetDir.listFiles()?.forEach { entry ->
            if (entry.name in expectedNames) return@forEach
            val ours = when {
                Files.isSymbolicLink(entry.toPath()) -> isOurSymlink(entry, workshopContentBase)
                entry.isDirectory -> hasCopySentinel(entry)
                else -> false
            }
            if (ours && deleteEntry(entry)) {
                removed++
            }
        }

        activeItemDirs.forEach { (itemId, sourceDir) ->
            val entryPath = File(targetDir, entryNameForId[itemId] ?: itemId.toString())
            try {
                val result = if (useSymlinks) {
                    ensureSymlink(entryPath, sourceDir, workshopContentBase)
                } else {
                    ensureCopy(entryPath, sourceDir)
                }
                if (result) created++ else skipped++
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed routing workshop item $itemId into ${targetDir.absolutePath}")
                errors[itemId.toString()] = e.message ?: "Unknown error"
            }
        }

        return SyncResult(created, skipped, removed, errors)
    }

    private fun syncFlatFilesIntoDir(
        targetDir: File,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
    ): SyncResult {
        val expectedNames = activeItemDirs.values.mapNotNull { dir ->
            dir.listFiles()?.firstOrNull { !it.name.startsWith(".") && it.isFile }?.name
        }.toSet()
        var created = 0
        var skipped = 0
        var removed = 0

        targetDir.listFiles()?.forEach { entry ->
            if (entry.name in expectedNames) return@forEach
            if (Files.isSymbolicLink(entry.toPath()) && isOurSymlink(entry, workshopContentBase)) {
                if (deleteEntry(entry)) removed++
            }
        }

        activeItemDirs.values.forEach { srcDir ->
            val srcFile = srcDir.listFiles()?.firstOrNull { !it.name.startsWith(".") && it.isFile } ?: return@forEach
            val target = File(targetDir, srcFile.name)
            try {
                if (ensureSymlink(target, srcFile, workshopContentBase)) created++ else skipped++
            } catch (_: Exception) {
                skipped++
            }
        }

        return SyncResult(created, skipped, removed, emptyMap())
    }

    private fun ensureSymlink(
        entryPath: File,
        source: File,
        workshopContentBase: File,
    ): Boolean {
        if (Files.isSymbolicLink(entryPath.toPath())) {
            val target = Files.readSymbolicLink(entryPath.toPath()).toAbsolutePath().normalize().toString()
            if (target == source.toPath().toAbsolutePath().normalize().toString()) {
                return false
            }
            Files.deleteIfExists(entryPath.toPath())
        } else if (entryPath.exists()) {
            return false
        }

        entryPath.parentFile?.mkdirs()
        val absoluteSource = source.toPath().toAbsolutePath().normalize()
        if (!absoluteSource.startsWith(workshopContentBase.toPath().toAbsolutePath().normalize())) {
            return false
        }
        Files.createSymbolicLink(entryPath.toPath(), absoluteSource)
        return true
    }

    private fun ensureCopy(entryPath: File, sourceDir: File): Boolean {
        if (entryPath.exists()) return false
        sourceDir.copyRecursively(entryPath, overwrite = true)
        File(entryPath, COPY_SENTINEL).writeText("1")
        return true
    }

    private fun hasCopySentinel(entry: File): Boolean = File(entry, COPY_SENTINEL).isFile

    private fun isOurSymlink(entry: File, workshopContentBase: File): Boolean {
        return runCatching {
            val target = Files.readSymbolicLink(entry.toPath()).toAbsolutePath().normalize()
            target.startsWith(workshopContentBase.toPath().toAbsolutePath().normalize())
        }.getOrDefault(false)
    }

    private fun deleteEntry(entry: File): Boolean {
        return if (Files.isSymbolicLink(entry.toPath())) {
            Files.deleteIfExists(entry.toPath())
        } else {
            entry.deleteRecursively()
        }
    }

    private fun sanitizeFileName(input: String): String {
        return input.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.')
            .take(80)
    }
}
