package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity("workshop_item")
data class WorkshopItem(
    @PrimaryKey @ColumnInfo("published_file_id") val publishedFileId: Long,
    @ColumnInfo("app_id") val appId: Int,
    @ColumnInfo("title") val title: String = "",
    @ColumnInfo("preview_url") val previewUrl: String = "",
    @ColumnInfo("manifest_id") val manifestId: Long = -1L,
    @ColumnInfo("ugc_handle") val ugcHandle: Long = 0L,
    @ColumnInfo("size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo("time_updated") val timeUpdated: Long = 0L,
    @ColumnInfo("state") val state: WorkshopItemState = WorkshopItemState.SUBSCRIBED,
    @ColumnInfo(name = "file_url", defaultValue = "") val fileUrl: String = "",
    @ColumnInfo(name = "file_name", defaultValue = "") val fileName: String = "",
)
