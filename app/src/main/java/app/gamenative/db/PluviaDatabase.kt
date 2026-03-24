package app.gamenative.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.gamenative.data.AmazonGame
import app.gamenative.data.DownloadingAppInfo
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamLicense
import app.gamenative.data.WorkshopItem
import app.gamenative.db.converters.AppConverter
import app.gamenative.db.converters.ByteArrayConverter
import app.gamenative.db.converters.EnumConverter
import app.gamenative.db.converters.ListConverter
import app.gamenative.db.converters.MapConverter
import app.gamenative.db.converters.WorkshopItemConverter
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.DownloadingAppInfoDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.SteamLicenseDao
import app.gamenative.db.dao.WorkshopItemDao
import app.gamenative.db.entities.ChangeNumbers
import app.gamenative.db.entities.EncryptedAppTicket
import app.gamenative.db.entities.FileChangeLists

@Database(
    entities = [
        ChangeNumbers::class,
        EncryptedAppTicket::class,
        FileChangeLists::class,
        SteamApp::class,
        SteamLicense::class,
        GOGGame::class,
        EpicGame::class,
        AmazonGame::class,
        DownloadingAppInfo::class,
        WorkshopItem::class,
    ],
    version = 16,
    // For db migration, visit https://developer.android.com/training/data-storage/room/migrating-db-versions for more information
    exportSchema = true, // It is better to handle db changes carefully, as GN is getting much more users.
    autoMigrations = [
        // For every version change, if it is automatic, please add a new migration here.
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13), // Added amazon_games table
        AutoMigration(from = 13, to = 14), // Added GOG background image column
        AutoMigration(from = 14, to = 15), // Added last_played to steam_app
        AutoMigration(from = 15, to = 16), // Added playtime_forever to steam_app
    ]
)
@TypeConverters(
    AppConverter::class,
    ByteArrayConverter::class,
    EnumConverter::class,
    ListConverter::class,
    MapConverter::class,
    WorkshopItemConverter::class,
)
abstract class PluviaDatabase : RoomDatabase() {
    abstract fun steamAppDao(): SteamAppDao
    abstract fun steamLicenseDao(): SteamLicenseDao
    abstract fun gogGameDao(): GOGGameDao
    abstract fun epicGameDao(): EpicGameDao
    abstract fun amazonGameDao(): AmazonGameDao
    abstract fun downloadingAppInfoDao(): DownloadingAppInfoDao
    abstract fun workshopItemDao(): WorkshopItemDao
}
