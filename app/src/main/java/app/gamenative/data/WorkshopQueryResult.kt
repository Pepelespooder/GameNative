package app.gamenative.data

data class WorkshopQueryResult(
    val publishedFileId: Long,
    val title: String,
    val previewUrl: String,
    val subscriberCount: Int,
    val sizeBytes: Long,
    val timeUpdated: Long,
    val description: String = "",
    val favoritedCount: Int = 0,
    val viewCount: Int = 0,
    val timeCreated: Long = 0L,
    val tags: List<String> = emptyList(),
)
