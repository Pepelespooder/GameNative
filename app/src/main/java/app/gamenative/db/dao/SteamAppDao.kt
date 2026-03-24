package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService.Companion.INVALID_PKG_ID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

private const val OWNED_APPS_WHERE =
    "WHERE app.id != 480 " + // Actively filter out Spacewar
    "AND app.package_id != :invalidPkgId " +
    "AND app.type != 0 " +
    "AND EXISTS (" +
    "  SELECT 1 FROM steam_license AS license " +
    "  WHERE license.packageId = app.package_id " +
    "  AND (license.license_flags & 8 = 0) " + // exclude expired licenses (e.g. free weekends)
    ") "

private const val PAGE_SIZE = 50

@Dao
interface SteamAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(apps: SteamApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<SteamApp>)

    @Update
    suspend fun update(app: SteamApp)

    // observe change count — triggers re-load without pulling all blobs into one CursorWindow
    @Query(
        "SELECT COUNT(*) FROM steam_app AS app " + OWNED_APPS_WHERE,
    )
    fun _observeOwnedAppCount(
        invalidPkgId: Int = INVALID_PKG_ID,
    ): Flow<Int>

    // paged data load — each page fits comfortably in a CursorWindow
    @Query(
        "SELECT * FROM steam_app AS app " + OWNED_APPS_WHERE +
            "ORDER BY LOWER(app.name), app.id LIMIT :limit OFFSET :offset",
    )
    suspend fun _getOwnedAppsPage(
        limit: Int,
        offset: Int,
        invalidPkgId: Int = INVALID_PKG_ID,
    ): List<SteamApp>

    @Transaction
    suspend fun _getAllOwnedAppsPaged(invalidPkgId: Int = INVALID_PKG_ID): List<SteamApp> {
        val result = mutableListOf<SteamApp>()
        var offset = 0
        while (true) {
            // reset per-offset: try full fetch on first page, PAGE_SIZE thereafter
            var pageSize = if (offset == 0) Int.MAX_VALUE else PAGE_SIZE
            while (true) {
                try {
                    val page = _getOwnedAppsPage(pageSize, offset, invalidPkgId)
                    result.addAll(page)
                    offset += page.size
                    if (page.size < pageSize) return result
                    break
                } catch (e: android.database.sqlite.SQLiteBlobTooBigException) {
                    if (pageSize <= 1) throw e
                    pageSize /= 2 // back off and try again with smaller window
                }
            }
        }
    }

    /**
     * Emits the full list of owned Steam apps, using paging to avoid [SQLiteBlobTooBigException]
     * crashes on large libraries.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOwnedApps(invalidPkgId: Int = INVALID_PKG_ID): Flow<List<SteamApp>> {
        return _observeOwnedAppCount(invalidPkgId)
            .distinctUntilChanged()
            .flatMapLatest {
                flow {
                    emit(_getAllOwnedAppsPaged(invalidPkgId))
                }
            }
    }

    @Query("SELECT * FROM steam_app WHERE id = :appId")
    suspend fun findApp(appId: Int): SteamApp?

    @Query("SELECT * FROM steam_app AS app WHERE dlc_for_app_id = :appId AND depots <> '{}' AND " +
            " EXISTS (" +
            "   SELECT * FROM steam_license AS license " +
            "     WHERE license.license_type <> 0 AND " +
            "       REPLACE(REPLACE(license.app_ids, '[', ','), ']', ',') LIKE ('%,' || app.id || ',%') " +
            ")"
    )
    suspend fun findDownloadableDLCApps(appId: Int): List<SteamApp>?

    @Query("SELECT * FROM steam_app AS app WHERE dlc_for_app_id = :appId AND depots = '{}' AND " +
            " EXISTS (" +
            "   SELECT * FROM steam_license AS license " +
            "     WHERE license.license_type <> 0 AND " +
            "       REPLACE(REPLACE(license.app_ids, '[', ','), ']', ',') LIKE ('%,' || app.id || ',%') " +
            ")"
    )
    suspend fun findHiddenDLCApps(appId: Int): List<SteamApp>?

    @Query("DELETE from steam_app")
    suspend fun deleteAll()

    @Query("SELECT id FROM steam_app")
    suspend fun getAllAppIds(): List<Int>

    @Query("UPDATE steam_app SET last_played = :lastPlayed WHERE id = :appId")
    suspend fun updateLastPlayed(appId: Int, lastPlayed: Long)

    @Query("UPDATE steam_app SET last_played = :lastPlayed, playtime_forever = :playtimeMinutes WHERE id = :appId")
    suspend fun updatePlayStats(appId: Int, lastPlayed: Long, playtimeMinutes: Int)
}
