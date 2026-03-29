package app.gamenative.ui.screen.workshop

internal fun formatCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> "${count / 1_000}K"
    else -> "${count / 1_000_000}M"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
