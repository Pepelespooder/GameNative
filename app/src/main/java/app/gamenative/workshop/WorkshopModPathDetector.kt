package app.gamenative.workshop

import java.io.File
import timber.log.Timber

class WorkshopModPathDetector {
    enum class Confidence { LOW, MEDIUM, HIGH }

    data class DetectionResult(
        val strategy: WorkshopModPathStrategy,
        val confidence: Confidence,
        val reason: String,
    )

    companion object {
        private const val TAG = "WorkshopModPathDetector"
        private const val INSTALL_SCAN_DEPTH = 2
        private const val APPDATA_SCAN_DEPTH = 3

        val HIGH_CONFIDENCE_NAMES = setOf(
            "mods", "mod", "addons", "addon", "plugins", "plugin",
            "workshop_mods", "usermods", "user_mods", "modules", "module", "ugc",
        )
        private val MEDIUM_CONFIDENCE_NAMES = setOf(
            "levels", "level", "scenarios", "scenario", "missions", "mission",
            "workshop", "override", "gamedata", "maps",
        )
        private val LOW_CONFIDENCE_NAMES = setOf(
            "custom", "usercontent", "user_content", "community", "packages", "downloads",
        )
        private val ALL_MOD_DIR_NAMES = HIGH_CONFIDENCE_NAMES + MEDIUM_CONFIDENCE_NAMES + LOW_CONFIDENCE_NAMES
        private val INSTALL_SKIP_DIRS = setOf(
            "steam_settings", ".git", ".svn", "_commonredist", "__macosx", "directx", "engine", "binaries", "help",
        )
    }

    fun detect(
        gameInstallDir: File,
        appDataRoaming: File,
        appDataLocal: File,
        appDataLocalLow: File,
        documentsMyGames: File = File(""),
        documentsDir: File = File(""),
        gameName: String,
        developerName: String = "",
    ): DetectionResult {
        val candidates = linkedMapOf<String, Candidate>()
        fun add(dir: File, confidence: Confidence, source: String) {
            val allowMissingSynthetic = source.startsWith("appdata-synthetic:")
            if (!dir.isDirectory && !allowMissingSynthetic) return
            val key = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            val existing = candidates[key]
            if (existing == null || confidence > existing.confidence) {
                candidates[key] = Candidate(dir, confidence, source)
            }
        }

        if (gameInstallDir.isDirectory) {
            collectInstallCandidates(gameInstallDir).forEach { add(it.dir, it.confidence, it.source) }
        } else {
            Timber.tag(TAG).d("Skipping install-dir workshop routing scan: ${gameInstallDir.absolutePath}")
        }
        collectAppDataCandidates(
            roots = listOf(appDataRoaming, appDataLocal, appDataLocalLow, documentsMyGames, documentsDir),
            gameName = gameName,
            developerName = developerName,
        ).forEach { add(it.dir, it.confidence, it.source) }

        if (candidates.isEmpty()) {
            return DetectionResult(
                strategy = WorkshopModPathStrategy.Standard,
                confidence = Confidence.LOW,
                reason = "No local workshop routing targets found",
            )
        }

        val sorted = candidates.values.sortedByDescending { it.confidence }
        val targetDirs = sorted.map { it.dir }
        val fanOut = if (sorted.count { it.confidence >= Confidence.MEDIUM } > 1) {
            WorkshopModPathStrategy.FanOutPolicy.ALL_DIRS
        } else {
            WorkshopModPathStrategy.FanOutPolicy.PRIMARY_ONLY
        }
        val confidence = sorted.first().confidence
        val reason = sorted.joinToString("; ") { "${it.dir.name}[${it.confidence}](${it.source})" }
        Timber.tag(TAG).i("Detected workshop routing for '$gameName': $reason")
        return DetectionResult(
            strategy = WorkshopModPathStrategy.SymlinkIntoDir(targetDirs, fanOut),
            confidence = confidence,
            reason = reason,
        )
    }

    private data class Candidate(
        val dir: File,
        val confidence: Confidence,
        val source: String,
    )

    private fun walkDirectories(root: File, maxDepth: Int, action: (File) -> Unit) {
        if (!root.isDirectory || maxDepth < 0) return

        fun walk(current: File, depth: Int) {
            if (!current.isDirectory) return
            action(current)
            if (depth >= maxDepth) return
            current.listFiles()?.forEach { child ->
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
    }

    private fun collectInstallCandidates(gameInstallDir: File): List<Candidate> {
        val results = mutableListOf<Candidate>()
        walkDirectories(gameInstallDir, INSTALL_SCAN_DEPTH + 1) { dir ->
            if (
                dir != gameInstallDir &&
                dir.name.lowercase() !in INSTALL_SKIP_DIRS &&
                dir.name.lowercase() in ALL_MOD_DIR_NAMES
            ) {
                results += Candidate(
                    dir = dir,
                    confidence = confidenceForName(dir.name),
                    source = "install",
                )
            }
        }
        return results
    }

    private fun collectAppDataCandidates(
        roots: List<File>,
        gameName: String,
        developerName: String,
    ): List<Candidate> {
        val gameTokens = tokenize(gameName) + tokenize(developerName)
        if (gameTokens.isEmpty()) return emptyList()

        val results = mutableListOf<Candidate>()
        roots.filter { it.isDirectory }.forEach { root ->
            walkDirectories(root, APPDATA_SCAN_DEPTH + 1) { dir ->
                val pathTokens = tokenize(dir.absolutePath)
                if (gameTokens.none { it in pathTokens }) return@walkDirectories
                val modChildren = dir.listFiles()
                    ?.filter { it.isDirectory && it.name.lowercase() in ALL_MOD_DIR_NAMES }
                    .orEmpty()
                modChildren.forEach { child ->
                    results += Candidate(
                        dir = child,
                        confidence = if (child.name.lowercase() in HIGH_CONFIDENCE_NAMES) {
                            Confidence.HIGH
                        } else {
                            Confidence.MEDIUM
                        },
                        source = "appdata",
                    )
                }
                if (modChildren.isEmpty() && dir != root) {
                    results += Candidate(
                        dir = File(dir, "Mods"),
                        confidence = Confidence.MEDIUM,
                        source = "appdata-synthetic:${dir.name}",
                    )
                }
            }
        }
        return results
    }

    private fun confidenceForName(name: String): Confidence {
        val lower = name.lowercase()
        return when (lower) {
            in HIGH_CONFIDENCE_NAMES -> Confidence.HIGH
            in MEDIUM_CONFIDENCE_NAMES -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()
    }
}
