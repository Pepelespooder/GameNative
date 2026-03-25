package app.gamenative.data

data class WorkshopDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val stage: String,
)
