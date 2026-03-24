# Branch Switcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to switch between available (non-password-protected) Steam depot branches from the game detail options menu, with an immediate re-download triggered when a new branch is selected.

**Architecture:** Branch is stored in the Java `Container` object via `getExtra("branch", "public")` / `putExtra("branch", value)` — the established pattern for non-first-class container fields. It is mirrored into the Kotlin `ContainerData` snapshot via `ContainerUtils.toContainerData` and `applyToContainer`. The options menu gains a `SelectBranch` entry that opens a `SingleChoiceDialog` listing available branch names only. Selecting a branch persists it via `ContainerUtils.applyToContainer` and immediately starts a download. The existing `downloadApp` overload reads the branch from the container instead of hardcoding `"public"`.

**ID note:** In `SteamAppScreen`, `libraryItem.appId` is a `String` like `"STEAM_12345"`. This is the container ID. `gameId` is the `Int` (12345) used for Steam API calls. Both resolve to the same container — `SteamService` uses `ContainerManager.getContainerById("STEAM_${appId}")` and `ContainerUtils.getOrCreateContainer(context, appId)` uses the String directly.

**Tech Stack:** Kotlin, Java (`Container`), Jetpack Compose, existing `SingleChoiceDialog`, `ContainerUtils.getExtra`/`putExtra` pattern.

---

### Task 1: Add `branch` to `ContainerData` and wire through `ContainerUtils`

**Files:**
- Modify: `app/src/main/java/com/winlator/container/ContainerData.kt`
- Modify: `app/src/main/java/app/gamenative/utils/ContainerUtils.kt`

- [ ] **Step 1: Add the field to `ContainerData`**

After `val sharpnessDenoise: Int = 100,`, add:

```kotlin
    /** Selected Steam depot branch (e.g. "public", "beta") */
    val branch: String = "public",
```

- [ ] **Step 2: Add to `save` block**

After `"sharpnessDenoise" to state.sharpnessDenoise,`, add:

```kotlin
                    "branch" to state.branch,
```

- [ ] **Step 3: Add to `restore` block**

After `sharpnessDenoise = (savedMap["sharpnessDenoise"] as? Int) ?: 100,`, add:

```kotlin
                    branch = (savedMap["branch"] as? String) ?: "public",
```

- [ ] **Step 4: Add to `ContainerUtils.toContainerData`**

After:
```kotlin
            sharpnessDenoise = container.getExtra("sharpnessDenoise", "100").toIntOrNull() ?: 100,
```
add:
```kotlin
            branch = container.getExtra("branch", "public"),
```

- [ ] **Step 5: Add to `ContainerUtils.applyToContainer`**

After:
```kotlin
        container.putExtra("sharpnessDenoise", containerData.sharpnessDenoise.toString())
```
add:
```kotlin
        container.putExtra("branch", containerData.branch)
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/winlator/container/ContainerData.kt
git add app/src/main/java/app/gamenative/utils/ContainerUtils.kt
git commit -m "feat: add branch field to ContainerData with Container extra round-trip"
```

---

### Task 2: Add `SelectBranch` to menu enum and panel

**Files:**
- Modify: `app/src/main/java/app/gamenative/ui/enums/AppOptionMenuType.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/GameOptionsPanel.kt`

> **Important:** `getIconForOption` uses an exhaustive `when` with no `else`. Do NOT build between Step 1 and Step 2 — the project will fail to compile until both the enum entry and its icon mapping are present.

- [ ] **Step 1: Add enum entry**

In `AppOptionMenuType.kt`, change:

```kotlin
    ManageGameContent("Manage DLC");
```

to:

```kotlin
    ManageGameContent("Manage DLC"),
    SelectBranch("Select branch");
```

- [ ] **Step 2: Add icon mapping (do immediately after Step 1)**

In `GameOptionsPanel.kt`, inside `getIconForOption`, after:

```kotlin
        AppOptionMenuType.ManageGameContent -> Icons.Default.Apps
```

add:

```kotlin
        AppOptionMenuType.SelectBranch -> Icons.Default.AccountTree
```

- [ ] **Step 3: Add to `groupOptions` containerSettings branch**

In `GameOptionsPanel.kt`, find the `containerSettings` when-branch (lines ~371-377):

```kotlin
            // Container Settings
            AppOptionMenuType.ResetToDefaults,
            AppOptionMenuType.ResetDrm,
            AppOptionMenuType.UseKnownConfig,
            AppOptionMenuType.ImportConfig,
            AppOptionMenuType.ExportConfig,
            -> containerSettings.add(option)
```

Change to:

```kotlin
            // Container Settings
            AppOptionMenuType.ResetToDefaults,
            AppOptionMenuType.ResetDrm,
            AppOptionMenuType.UseKnownConfig,
            AppOptionMenuType.ImportConfig,
            AppOptionMenuType.ExportConfig,
            AppOptionMenuType.SelectBranch,
            -> containerSettings.add(option)
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/gamenative/ui/enums/AppOptionMenuType.kt
git add app/src/main/java/app/gamenative/ui/screen/library/components/GameOptionsPanel.kt
git commit -m "feat: add SelectBranch menu option type, icon, and panel grouping"
```

---

### Task 3: Read branch from container in `SteamService.downloadApp`

**Files:**
- Modify: `app/src/main/java/app/gamenative/service/SteamService.kt`

- [ ] **Step 1: Replace hardcoded branch**

Find the `downloadApp(appId: Int, dlcAppIds: List<Int>, isUpdateOrVerify: Boolean)` overload (~line 1095). The `container` variable is already fetched just above. Replace:

```kotlin
            val depots = getDownloadableDepots(appId = appId, preferredLanguage = containerLanguage)
            downloadApp(
                appId = appId,
                downloadableDepots = depots,
                userSelectedDlcAppIds = dlcAppIds,
                branch = "public",
                containerLanguage = containerLanguage,
                isUpdateOrVerify = isUpdateOrVerify)
```

with:

```kotlin
            val branch = container?.getExtra("branch", "public") ?: "public"
            Timber.tag("SteamService").d("downloadApp: appId=$appId language=$containerLanguage branch=$branch")

            val depots = getDownloadableDepots(appId = appId, preferredLanguage = containerLanguage)
            downloadApp(
                appId = appId,
                downloadableDepots = depots,
                userSelectedDlcAppIds = dlcAppIds,
                branch = branch,
                containerLanguage = containerLanguage,
                isUpdateOrVerify = isUpdateOrVerify)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/app/gamenative/service/SteamService.kt
git commit -m "feat: read branch from container extra instead of hardcoding public"
```

---

### Task 4: Add branch selection dialog to `SteamAppScreen`

**Files:**
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/appscreen/SteamAppScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

Throughout this task: `appId` is the `String` container ID (e.g. `"STEAM_12345"`), `gameId` is the `Int` (e.g. `12345`). Use `appId` for container operations, `gameId` for Steam API calls.

- [ ] **Step 1: Add dialog state to companion object**

Near the other dialog state lists (e.g. `uninstallDialogAppIds`), add:

```kotlin
private val branchDialogAppIds = mutableStateListOf<String>()

fun showBranchDialog(appId: String) {
    if (!branchDialogAppIds.contains(appId)) branchDialogAppIds.add(appId)
}

fun hideBranchDialog(appId: String) {
    branchDialogAppIds.remove(appId)
}

fun shouldShowBranchDialog(appId: String): Boolean = branchDialogAppIds.contains(appId)
```

- [ ] **Step 2: Render the dialog**

In the main composable where other per-game dialogs are rendered (find the block calling `shouldShowUninstallDialog` or similar, where both `appId: String` and `gameId: Int` are in scope), add:

> Note: `SingleChoiceDialog` calls `onSelected` immediately on item tap — there is no separate confirm step. Branch names only are shown (no build ID or date).

```kotlin
if (shouldShowBranchDialog(appId)) {
    val availableBranches = SteamService.getAppInfoOf(gameId)
        ?.branches
        ?.filterValues { !it.pwdRequired }
        ?.keys
        ?.sortedWith(compareBy { if (it == "public") 0 else 1 })
        ?: emptyList()

    val currentBranch = ContainerUtils.getOrCreateContainer(context, appId)
        .getExtra("branch", "public")
    val currentIndex = availableBranches.indexOf(currentBranch).coerceAtLeast(0)
    val scope = rememberCoroutineScope()

    SingleChoiceDialog(
        openDialog = true,
        icon = Icons.Default.AccountTree,
        title = stringResource(R.string.select_branch),
        items = availableBranches,
        currentItem = currentIndex,
        onSelected = { index ->
            val selected = availableBranches[index]
            hideBranchDialog(appId)
            if (selected != currentBranch) {
                val container = ContainerUtils.getOrCreateContainer(context, appId)
                val updatedData = ContainerUtils.toContainerData(container).copy(branch = selected)
                ContainerUtils.applyToContainer(context, appId, updatedData)
                scope.launch(Dispatchers.IO) {
                    SteamService.downloadApp(gameId, emptyList(), isUpdateOrVerify = false)
                }
            }
        },
        onDismiss = { hideBranchDialog(appId) },
    )
}
```

- [ ] **Step 3: Add the menu option**

In `getSourceSpecificMenuOptions`, inside the installed-only block, after the existing options, add:

```kotlin
val accessibleBranches = SteamService.getAppInfoOf(gameId)
    ?.branches
    ?.filterValues { !it.pwdRequired }
    ?: emptyMap()

if (accessibleBranches.size > 1) {
    options += AppMenuOption(
        AppOptionMenuType.SelectBranch,
        onClick = { showBranchDialog(appId) },
    )
}
```

- [ ] **Step 4: Add string resource**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="select_branch">Select branch</string>
```

- [ ] **Step 5: Verify imports are present in `SteamAppScreen.kt`**

```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import app.gamenative.ui.component.dialog.SingleChoiceDialog
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/gamenative/ui/screen/library/appscreen/SteamAppScreen.kt
git add app/src/main/res/values/strings.xml
git commit -m "feat: branch selection dialog in Steam game options menu"
```

---

### Task 5: Verify end-to-end

- [ ] Build the project — confirm no compile errors
- [ ] Install on device — open a Steam game that has multiple non-password branches (e.g. a game with `public` and `beta`)
- [ ] Open game options → confirm "Select branch" appears under Container settings
- [ ] Tap it — confirm dialog shows branch names only, with current branch pre-selected
- [ ] Select a different branch — confirm dialog closes and download starts immediately
- [ ] Re-open the dialog — confirm the new branch is now pre-selected
- [ ] For a game with only one accessible branch — confirm "Select branch" does not appear
- [ ] Trigger a normal update/verify — confirm it uses the persisted branch, not `"public"`

- [ ] **Final commit if any cleanup needed**

```bash
git commit -m "feat: steam branch switcher — select and switch depot branches from game options"
```
