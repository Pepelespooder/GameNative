# Steam Workshop Support — Design Spec

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add full Steam Workshop support to GameNative — browse, subscribe, download, update, and uninstall workshop items — with proper Wine registry and ACF manifest setup so games actually find the installed mods.

**Architecture:** Five layers — Wine/registry setup, data (Room), download pipeline, ACF writer, and UI. Each layer is independent and testable. The download pipeline reuses the existing DepotDownloader infrastructure via the already-present `UgcItem`/`PubFileItem` classes.

**Tech Stack:** Kotlin, Jetpack Compose, Room, JavaSteam (`PublishedFile` RPC + `UgcItem`/`PubFileItem`/DepotDownloader), `WineRegistryEditor`, Valve KeyValues ACF format.

---

## Background & Root Cause

Previous workshop attempts failed because games couldn't find installed mods. The root cause is two-fold:

1. **Missing HKLM registry keys** — `SteamUtils.autoLoginUserChanges()` already writes `HKCU\Software\Valve\Steam\SteamPath = C:\Program Files (x86)\Steam` into `user.reg` for every game launch. However, `HKLM\Software\Valve\Steam\InstallPath` and its 32-bit compat node `HKLM\Software\Wow6432Node\Valve\Steam\InstallPath` are never written. Older Steamworks SDK versions (and many 32-bit games) fall back to HKLM when HKCU is absent or when running as 32-bit. Without the HKLM key, those games cannot locate `steamapps`.

2. **Missing/malformed ACF manifest** — Games read `steamapps/workshop/appworkshop_<appId>.acf` to enumerate installed workshop items. Without this file (or with `NeedsUpdate "1"` / `NeedsDownload "1"`), games ignore installed content or loop trying to re-download.

### Confirmed path layout

The Wine prefix for every game container stores its fake Steam at:
```
<container.rootDir>/.wine/drive_c/Program Files (x86)/Steam/
```
Which inside Wine resolves to `C:\Program Files (x86)\Steam\`. This is already set as `SteamPath` by `autoLoginUserChanges()`. No new drive mapping is needed.

Workshop content must therefore live at (Android path):
```
<container.rootDir>/.wine/drive_c/Program Files (x86)/Steam/steamapps/workshop/content/<appId>/<itemId>/
```
And the ACF at:
```
<container.rootDir>/.wine/drive_c/Program Files (x86)/Steam/steamapps/workshop/appworkshop_<appId>.acf
```

---

## Layer 1: Wine Registry + ACF Setup

### `WorkshopEnvironmentSetup.kt`
New singleton object in `app.gamenative.utils`.

**Registry setup** — `fun setupRegistry(container: Container)` — called from `ContainerUtils` when creating or updating a Steam container. Writes only the missing HKLM keys to `system.reg` (HKCU keys are already handled by `autoLoginUserChanges`):

```kotlin
val systemRegFile = File(container.rootDir, ".wine/system.reg")
WineRegistryEditor(systemRegFile).use { reg ->
    reg.setStringValue("Software\\Valve\\Steam", "InstallPath", steamRoot)
    reg.setStringValue("Software\\Wow6432Node\\Valve\\Steam", "InstallPath", steamRoot)
}
// steamRoot = "C:\\Program Files (x86)\\Steam"
```

Note: use the bare `.wine/system.reg` relative path, consistent with `ContainerUtils` which uses `File(container.rootDir, ".wine/user.reg")`. Do not use `ImageFs.WINEPREFIX` (it resolves to an absolute path inside the Wine prefix, not a relative path from `container.rootDir`).

**Workshop base path helper:**
```kotlin
fun getWorkshopContentPath(container: Container, appId: Int, itemId: Long): File =
    File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam/steamapps/workshop/content/$appId/$itemId")

fun getAcfPath(container: Container, appId: Int): File =
    File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam/steamapps/workshop/appworkshop_$appId.acf")
```

**ACF writing** — `fun writeAcf(container: Container, appId: Int, items: List<WorkshopItem>)`. If the ACF file exists and is parseable, reads it first to preserve any items not present in `items`. If the file does not exist or cannot be parsed (malformed KeyValues), starts with an empty item map — never fails. Then writes a complete Valve KeyValues file:

```
"AppWorkshop"
{
    "appid"             "<appId>"
    "SizeOnDisk"        "<total bytes of all items>"
    "NeedsUpdate"       "0"
    "NeedsDownload"     "0"
    "TimeLastUpdated"   "<unix timestamp>"
    "TimeLastAppRan"    "<unix timestamp>"
    "WorkshopItemsInstalled"
    {
        "<itemId>"
        {
            "size"            "<bytes>"
            "timeupdated"     "<timestamp>"
            "manifest"        "<manifestId>"
            "ugchandle"       "<ugcHandle>"
            "download_folder" "steamapps/workshop/content/<appId>/<itemId>"
        }
        ...
    }
    "WorkshopItemDetails"
    {
        "<itemId>"
        {
            "manifest"      "<manifestId>"
            "ugchandle"     "<ugcHandle>"
            "timeupdated"   "<timestamp>"
            "timetouched"   "<timestamp>"
        }
        ...
    }
}
```

Note: `download_folder` is required by some games that read this field directly rather than computing the path from `SteamPath + itemId`.

**Symlink fallback** — `fun applyModSymlinkIfNeeded(container: Container, appId: Int)`. For games that ignore `ISteamUGC` and look for a hardcoded local mods folder, a symlink is created from the game's expected path into `workshop/content/<appId>/`. The per-game override path is stored in a new `WorkshopSymlinkRegistry` object in `app.gamenative.utils` (a simple `Map<Int, String>` keyed by appId) rather than modifying `KeyedGameFix` — this avoids breaking every existing `KeyedGameFix` implementation. See file map.

---

## Layer 2: Data Layer

### `WorkshopItemState.kt`
```kotlin
enum class WorkshopItemState {
    SUBSCRIBED,        // subscribed but not yet downloaded
    DOWNLOADING,       // download in progress
    INSTALLED,         // downloaded and ACF written
    UPDATE_AVAILABLE,  // newer version exists on server
    FAILED,            // download failed, retry available
}
```

### `WorkshopItem.kt`
New Room entity in `app.gamenative.data`:

```kotlin
@Entity("workshop_item")
data class WorkshopItem(
    @PrimaryKey val publishedFileId: Long,
    @ColumnInfo("app_id") val appId: Int,
    @ColumnInfo("title") val title: String = "",
    @ColumnInfo("preview_url") val previewUrl: String = "",
    @ColumnInfo("manifest_id") val manifestId: Long = -1L,
    @ColumnInfo("ugc_handle") val ugcHandle: Long = 0L,
    @ColumnInfo("size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo("time_updated") val timeUpdated: Long = 0L,
    @ColumnInfo("state") val state: WorkshopItemState = WorkshopItemState.SUBSCRIBED,
)
```

Note: `installPath` is omitted — it is always deterministic:
`WorkshopEnvironmentSetup.getWorkshopContentPath(container, appId, publishedFileId)`.

### `WorkshopItemDao.kt`
```kotlin
@Dao interface WorkshopItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WorkshopItem)

    @Delete
    suspend fun delete(item: WorkshopItem)

    @Query("SELECT * FROM workshop_item WHERE app_id = :appId")
    fun getByAppId(appId: Int): Flow<List<WorkshopItem>>

    @Query("SELECT * FROM workshop_item WHERE published_file_id = :id")
    suspend fun getById(id: Long): WorkshopItem?

    @Query("UPDATE workshop_item SET state = :state WHERE published_file_id = :id")
    suspend fun updateState(id: Long, state: WorkshopItemState)

    @Query("UPDATE workshop_item SET manifest_id = :manifestId, time_updated = :timeUpdated, state = :state WHERE published_file_id = :id")
    suspend fun updateManifest(id: Long, manifestId: Long, timeUpdated: Long, state: WorkshopItemState)
}
```

### `PluviaDatabase.kt`
- Add `WorkshopItem::class` to `entities`
- Add `abstract fun workshopItemDao(): WorkshopItemDao`
- Bump version 15 → 16, add `AutoMigration(from = 15, to = 16)`

### `WorkshopItemConverter.kt`
Type converter for `WorkshopItemState` ↔ `String`.

---

## Layer 3: Download Pipeline

### `WorkshopDownloadManager.kt`
New class in `app.gamenative.service`, injected as a Hilt `@Singleton` (consistent with project DI pattern), following the `EpicDownloadManager`/`GOGDownloadManager` pattern.

**Subscribe + download:**
```
subscribe(container, appId, publishedFileId) {
  1. PublishedFile.Subscribe(appId, publishedFileId)
  2. PublishedFile.GetDetails(publishedFileId)
     → ugcHandle, manifestId, sizeBytes, timeUpdated, title
  3. Insert WorkshopItem(state = DOWNLOADING) into Room
  4. Determine download item:
     - if ugcHandle != 0L → UgcItem(appId, ugcId = ugcHandle,
         installDirectory = WorkshopEnvironmentSetup.getWorkshopContentPath(...).absolutePath)
     - if ugcHandle == 0L → PubFileItem(appId, pubFile = publishedFileId,
         installDirectory = WorkshopEnvironmentSetup.getWorkshopContentPath(...).absolutePath)
  5. DepotDownloader.download(item) — emits progress via downloadProgress StateFlow
  6. On success:
     - updateManifest(publishedFileId, manifestId, timeUpdated, INSTALLED)
     - WorkshopEnvironmentSetup.writeAcf(container, appId, allInstalledItems)
     - WorkshopEnvironmentSetup.applyModSymlinkIfNeeded(container, appId)
  7. On failure:
     - updateState(publishedFileId, FAILED)
}
```

**Unsubscribe:**
```
unsubscribe(container, appId, publishedFileId) {
  1. PublishedFile.Unsubscribe(appId, publishedFileId)
  2. Delete files at WorkshopEnvironmentSetup.getWorkshopContentPath(container, appId, publishedFileId)
  3. Delete WorkshopItem from Room
  4. WorkshopEnvironmentSetup.writeAcf(container, appId, remainingItems)
}
```

**Check for updates:**
```
checkForUpdates(container, appId) {
  1. Load all installed WorkshopItems for appId from Room
  2. PublishedFile.GetItemInfo(publishedFileIds) → per-item timeUpdated
  3. For each item where server timeUpdated > local timeUpdated:
       updateState(id, UPDATE_AVAILABLE)
}
```

**Update item:**
```
updateItem(container, appId, publishedFileId) {
  // Same as subscribe flow with verify = true on the DownloadItem
}
```

**Progress tracking:**
```kotlin
val downloadProgress: StateFlow<Map<Long, WorkshopDownloadProgress>> // keyed by publishedFileId

data class WorkshopDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val stage: String,
)
```

---

## Layer 4: UI Layer

### `WorkshopQueryResult.kt`
New data class in `app.gamenative.data`:
```kotlin
data class WorkshopQueryResult(
    val publishedFileId: Long,
    val title: String,
    val previewUrl: String,
    val subscriberCount: Int,
    val sizeBytes: Long,
    val timeUpdated: Long,
)
```

### `WorkshopViewModel.kt`
New ViewModel in `app.gamenative.ui.model`. Uses plain `@HiltViewModel` — `appId` is read from `SavedStateHandle`, which Hilt automatically populates from the nav backstack argument (`PluviaScreen.Workshop.ARG_APP_ID`) when the screen is composed:

```kotlin
@HiltViewModel
class WorkshopViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workshopDownloadManager: WorkshopDownloadManager,
    private val workshopItemDao: WorkshopItemDao,
) : ViewModel() {
    val appId: Int = checkNotNull(savedStateHandle[PluviaScreen.Workshop.ARG_APP_ID])
    val installedItems: Flow<List<WorkshopItem>> = workshopItemDao.getByAppId(appId)
    val downloadProgress: StateFlow<Map<Long, WorkshopDownloadProgress>>
        get() = workshopDownloadManager.downloadProgress
    val topMods: StateFlow<List<WorkshopQueryResult>>       // ranked_by_total_unique_subscriptions, 10 items
    val trendingMods: StateFlow<List<WorkshopQueryResult>>  // ranked_by_trend, 10 items
    val browseResults: StateFlow<List<WorkshopQueryResult>> // search results, paginated 30/page
    val searchQuery: MutableStateFlow<String>
    fun subscribe(publishedFileId: Long)
    fun unsubscribe(publishedFileId: Long)
    fun checkForUpdates()
    fun updateItem(publishedFileId: Long)
    fun search(query: String)
    fun loadNextPage()
}
```

### `WorkshopScreen.kt`
New composable in `app.gamenative.ui.screen.workshop`. Signature: `fun WorkshopScreen(onBack: () -> Unit)`. The ViewModel is obtained via plain `hiltViewModel()` — `appId` is already in `SavedStateHandle` from the nav route.

**Three tabs:**

**Browse tab:**
- When `searchQuery` is blank:
  - "Top Mods" horizontal carousel — `topMods` StateFlow, 10 items, `ranked_by_total_unique_subscriptions`
  - "Trending This Week" horizontal carousel — `trendingMods` StateFlow, 10 items, `ranked_by_trend`
  - Both carousels load on screen open (Steam Workshop homepage feel)
- When `searchQuery` is non-blank:
  - Carousels collapse, vertical paginated grid of `browseResults`
- Each `WorkshopItemCard`: Coil preview image, title, subscriber count, file size, Subscribe button
  - Subscribe button disabled with inline progress bar if `downloadProgress` contains this item
  - Subscribe button shows "Installed" if already in `installedItems`

**Installed tab:**
- Vertical list of `installedItems` from Room Flow
- Each row: title, size on disk
- `UPDATE AVAILABLE` badge when `state == UPDATE_AVAILABLE`
- `FAILED` badge with Retry button when `state == FAILED`
- Inline download progress bar when `state == DOWNLOADING`
- Per-item: Unsubscribe button, Update button (only shown when `UPDATE_AVAILABLE`)

**Updates tab:**
- "Check for Updates" button → `viewModel.checkForUpdates()`
- Lists all items where `state == UPDATE_AVAILABLE`
- "Update All" button at top
- Per-item "Update" button

### `AppOptionMenuType.kt`
Add `BrowseWorkshop("Workshop")`.

### `GameOptionsPanel.kt`
Both `groupOptions()` and `getIconForOption()` are exhaustive `when` expressions with no `else` branch — adding `BrowseWorkshop` to the enum will cause a compile error unless both `when` blocks are updated:
- Add `BrowseWorkshop` branch to `groupOptions()` returning `GAME_MANAGEMENT`
- Add `BrowseWorkshop` branch to `getIconForOption()` returning `Icons.Default.Extension`

### `SteamAppScreen.kt`
`SteamAppScreen` is a strategy object — navigation is triggered via the global event bus (`PluviaApp.events`), which is already used in `BaseAppScreen` (e.g. `PluviaApp.events.emit(AndroidEvent.ShowGameFeedback(...))`). No callback threading is needed:
- Add `NavigateWorkshop(val appId: Int) : AndroidEvent<Unit>` to `AndroidEvent.kt`
- Add `BrowseWorkshop` option in `getSourceSpecificMenuOptions()`, shown only when `SteamService.isAppInstalled(gameId)` is true, with `onClick = { PluviaApp.events.emit(AndroidEvent.NavigateWorkshop(libraryItem.gameId)) }`
- In `PluviaMain.kt`, subscribe to `AndroidEvent.NavigateWorkshop` and call `navController.navigate(PluviaScreen.Workshop.route(it.appId))`

### `PluviaScreen.kt`
Add `Workshop` destination:
```kotlin
data object Workshop : PluviaScreen("workshop/{appId}") {
    fun route(appId: Int) = "workshop/$appId"
    const val ARG_APP_ID = "appId"
}
```

### `PluviaMain.kt`
Add composable block for `PluviaScreen.Workshop`:
```kotlin
composable(
    route = PluviaScreen.Workshop.route,
    arguments = listOf(navArgument(PluviaScreen.Workshop.ARG_APP_ID) { type = NavType.IntType }),
) {
    WorkshopScreen(onBack = { navController.popBackStack() })
}
```

---

## File Map

| File | Action |
|---|---|
| `utils/WorkshopEnvironmentSetup.kt` | Create |
| `data/WorkshopItem.kt` | Create |
| `data/WorkshopItemState.kt` | Create |
| `data/WorkshopQueryResult.kt` | Create |
| `db/dao/WorkshopItemDao.kt` | Create |
| `db/converters/WorkshopItemConverter.kt` | Create |
| `db/PluviaDatabase.kt` | Modify — add entity, dao, version 15→16 |
| `service/WorkshopDownloadManager.kt` | Create |
| `ui/model/WorkshopViewModel.kt` | Create |
| `ui/screen/workshop/WorkshopScreen.kt` | Create |
| `ui/enums/AppOptionMenuType.kt` | Modify — add BrowseWorkshop |
| `ui/screen/library/components/GameOptionsPanel.kt` | Modify — add BrowseWorkshop to groupOptions + icon |
| `ui/screen/library/appscreen/SteamAppScreen.kt` | Modify — add BrowseWorkshop menu option, emit `AndroidEvent.NavigateWorkshop` |
| `events/AndroidEvent.kt` | Modify — add `NavigateWorkshop(val appId: Int) : AndroidEvent<Unit>` |
| `ui/screen/PluviaScreen.kt` | Modify — add `Workshop` sealed object with route |
| `ui/PluviaMain.kt` | Modify — add `composable` block for `PluviaScreen.Workshop` + subscribe to `AndroidEvent.NavigateWorkshop` |
| `utils/ContainerUtils.kt` | Modify — call `WorkshopEnvironmentSetup.setupRegistry(container)` at Steam container create/update |
| `utils/WorkshopSymlinkRegistry.kt` | Create — `Map<Int, String>` of appId → local mods folder path for symlink fallback games |

---

## Error Handling

- `PublishedFile` RPC failures: surface as snackbar in `WorkshopScreen`, do not crash
- Download failures: set `WorkshopItem.state = FAILED`, show Retry button in Installed tab
- ACF write failure: log warning, non-fatal — game may not find mods but app stays stable
- Registry write failure: log warning, non-fatal (HKCU keys already written by `autoLoginUserChanges`)

---

## Testing

- Unit test `WorkshopEnvironmentSetup.writeAcf()` — given a list of `WorkshopItem`s, assert output matches expected Valve KeyValues format with `NeedsUpdate "0"`, `NeedsDownload "0"`, correct `download_folder` per item
- Unit test ACF merge — existing items preserved when new items added or removed
- `WorkshopItemDao` instrumented test — insert, query by appId, update state, update manifest
- Manual: install workshop item, verify files at correct Android path, verify ACF written, verify game detects mod in-game
