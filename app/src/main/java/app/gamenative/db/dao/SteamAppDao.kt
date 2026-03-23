package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService.Companion.INVALID_PKG_ID
import kotlinx.coroutines.flow.Flow

@Dao
interface SteamAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(apps: SteamApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<SteamApp>)

    @Update
    suspend fun update(app: SteamApp)

    @Query(
        // Use '{}' AS depots instead of app.depots — the actual column is large JSON that overflows
        // the 2MB Android CursorWindow across 2000+ rows, causing a fatal crash. The library list
        // never needs depot data; an empty map is the correct value for all returned rows.
        "SELECT app.id, app.package_id, app.owner_account_id, app.license_flags, app.received_pics, " +
            "'{}' AS depots, " +
            "app.last_change_number, app.branches, app.name, app.type, app.os_list, app.release_state, " +
            "app.release_date, app.metacritic_score, app.metacritic_full_url, app.logo_hash, " +
            "app.logo_small_hash, app.icon_hash, app.client_icon_hash, app.client_tga_hash, " +
            "app.small_capsule, app.header_image, app.library_assets, app.primary_genre, " +
            "app.review_score, app.review_percentage, app.controller_support, app.demo_of_app_id, " +
            "app.developer, app.publisher, app.homepage_url, app.game_manual_url, " +
            "app.load_all_before_launch, app.dlc_app_ids, app.is_free_app, app.dlc_for_app_id, " +
            "app.must_own_app_to_purchase, app.dlc_available_on_store, app.optional_dlc, " +
            "app.game_dir, app.install_script, app.no_servers, app.`order`, app.primary_cache, " +
            "app.valid_os_list, app.third_party_cd_key, app.visible_only_when_installed, " +
            "app.visible_only_when_subscribed, app.launch_eula_url, app.require_default_install_folder, " +
            "app.content_type, app.install_dir, app.use_launch_cmd_line, " +
            "app.launch_without_workshop_updates, app.use_mms, app.install_script_signature, " +
            "app.install_script_override, app.config, app.ufs, app.last_played, app.playtime_forever " +
            "FROM steam_app AS app " +
            "WHERE app.id != 480 " + // Actively filter out Spacewar
            // "AND (owner_account_id IN (:ownerIds) OR license_flags & :borrowedCode = :borrowedCode) " +
            "AND app.package_id != :invalidPkgId " +
            "AND app.type != 0 " +
            "AND EXISTS (" +
            "  SELECT 1 FROM steam_license AS license " +
            "  WHERE (license.license_flags & 8 = 0) " + // exclude Expired licenses (e.g. free weekends)
            "  AND (" +
            "    (license.packageId = app.package_id AND license.app_ids = '[]') " + // this app's package PICS not yet fetched — allow provisionally
            "    OR REPLACE(REPLACE(license.app_ids, '[', ','), ']', ',') LIKE ('%,' || app.id || ',%')" + // any non-expired license lists this appId
            "  )" +
            ") " +
            "ORDER BY LOWER(app.name)",
    )
    fun getAllOwnedApps(
        // ownerIds: List<Int>,
        invalidPkgId: Int = INVALID_PKG_ID,
        // borrowedCode: Int = ELicenseFlags.Borrowed.code(),
    ): Flow<List<SteamApp>>

    @Query("SELECT * FROM steam_app WHERE received_pics = 0 AND package_id != :invalidPkgId AND owner_account_id = :ownerId")
    fun getAllOwnedAppsWithoutPICS(
        ownerId: Int,
        invalidPkgId: Int = INVALID_PKG_ID,
    ): List<SteamApp>

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
