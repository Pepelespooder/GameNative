package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.WorkshopItem
import app.gamenative.data.WorkshopItemState
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkshopItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WorkshopItem)

    @Delete
    suspend fun delete(item: WorkshopItem)

    @Query("SELECT * FROM workshop_item WHERE app_id = :appId")
    fun getByAppId(appId: Int): Flow<List<WorkshopItem>>

    @Query("SELECT * FROM workshop_item WHERE published_file_id = :id")
    suspend fun getById(id: Long): WorkshopItem?

    @Query("DELETE FROM workshop_item WHERE app_id = :appId")
    suspend fun deleteByAppId(appId: Int)

    @Query("UPDATE workshop_item SET state = :state WHERE published_file_id = :id")
    suspend fun updateState(id: Long, state: WorkshopItemState)

    @Query("UPDATE workshop_item SET manifest_id = :manifestId, time_updated = :timeUpdated, state = :state WHERE published_file_id = :id")
    suspend fun updateManifest(id: Long, manifestId: Long, timeUpdated: Long, state: WorkshopItemState)
}
