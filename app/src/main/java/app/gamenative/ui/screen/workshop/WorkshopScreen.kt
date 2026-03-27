package app.gamenative.ui.screen.workshop

import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import app.gamenative.R
import app.gamenative.service.WorkshopDownloadState
import app.gamenative.service.WorkshopItemUi
import app.gamenative.service.WorkshopService
import app.gamenative.service.WorkshopSortType
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.util.SnackbarManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopScreen(
    containerAppId: String,
    appId: Int,
    gameTitle: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = remember(appId) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedItem by remember(appId) { mutableStateOf<WorkshopItemUi?>(null) }
    var isSearchVisible by remember(appId) { mutableStateOf(false) }
    var searchDraft by remember(appId) { mutableStateOf("") }
    var committedQuery by remember(appId) { mutableStateOf("") }
    var sortType by remember(appId) { mutableStateOf(WorkshopSortType.TRENDING) }
    var browsePage by remember(appId) { mutableIntStateOf(1) }
    var canLoadMoreBrowse by remember(appId) { mutableStateOf(true) }
    var isLoadingBrowse by remember(appId) { mutableStateOf(false) }
    var isLoadingSubscriptions by remember(appId) { mutableStateOf(false) }
    val busyIds = remember(appId) { mutableStateListOf<Long>() }
    val optimisticSubscribedIds = remember(appId) { mutableStateListOf<Long>() }
    val downloadProgress = remember(appId) { mutableStateMapOf<Long, WorkshopDownloadState>() }
    var browseItems by remember(appId) { mutableStateOf(emptyList<WorkshopItemUi>()) }
    var subscribedItems by remember(appId) { mutableStateOf(emptyList<WorkshopItemUi>()) }
    var selectedDependencyItems by remember(appId) { mutableStateOf(emptyList<WorkshopItemUi>()) }
    val browseGridState = rememberLazyGridState()
    val browseTabFocusRequester = remember { FocusRequester() }

    DisposableEffect(scope) {
        onDispose {
            scope.cancel()
        }
    }

    fun runBrowse(reset: Boolean) {
        scope.launch {
            if (isLoadingBrowse) return@launch
            if (!reset && !canLoadMoreBrowse) return@launch
            isLoadingBrowse = true
            val nextPage = if (reset) 1 else browsePage + 1
            runCatching {
                WorkshopService.browse(
                    appId = appId,
                    query = committedQuery,
                    sortType = sortType,
                    page = nextPage,
                )
            }.onSuccess { items ->
                val mergedItems = if (reset) {
                    items
                } else {
                    (browseItems + items).distinctBy { it.publishedFileId }
                }
                browsePage = nextPage
                canLoadMoreBrowse = if (reset) {
                    items.isNotEmpty()
                } else {
                    items.isNotEmpty() && mergedItems.size > browseItems.size
                }
                browseItems = mergedItems
            }.onFailure {
                SnackbarManager.show(it.message ?: "Failed to load workshop items")
            }
            isLoadingBrowse = false
        }
    }

    fun closeSearch(clearQuery: Boolean = false) {
        isSearchVisible = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (clearQuery) {
            searchDraft = ""
            if (committedQuery.isNotBlank()) {
                committedQuery = ""
                runBrowse(reset = true)
            }
        }
    }

    fun submitSearch() {
        committedQuery = searchDraft.trim()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        runBrowse(reset = true)
    }

    val refreshSelectedItemDependenciesNow: suspend (WorkshopItemUi) -> Unit = { item ->
        val enrichedItem = runCatching {
            WorkshopService.enrichWithHtmlDependencies(item)
        }.getOrElse {
            SnackbarManager.show(it.message ?: "Failed to load dependency details")
            item
        }
        val subscribedSnapshot = subscribedItems.map { it.publishedFileId }.toSet() + optimisticSubscribedIds
        val rootWithMissing = enrichedItem.copy(
            missingDependencyIds = enrichedItem.dependencyIds.filterNot {
                it in subscribedSnapshot || it == enrichedItem.publishedFileId
            },
        )
        if (selectedItem?.publishedFileId == rootWithMissing.publishedFileId) {
            selectedItem = rootWithMissing
        }
        selectedDependencyItems = runCatching {
            WorkshopService.getItemsByIds(appId, rootWithMissing.dependencyIds).map { dependencyItem ->
                dependencyItem.copy(
                    title = rootWithMissing.dependencyTitles[dependencyItem.publishedFileId]
                        ?: dependencyItem.title.ifBlank { dependencyItem.publishedFileId.toString() },
                )
            }
        }.getOrElse { emptyList() }
    }

    val refreshSelectedItemDependencies: (WorkshopItemUi) -> Unit = { item ->
        scope.launch {
            refreshSelectedItemDependenciesNow(item)
        }
    }

    fun refreshSubscriptions() {
        scope.launch {
            if (isLoadingSubscriptions) return@launch
            isLoadingSubscriptions = true
            runCatching {
                WorkshopService.getSubscriptions(
                    context = context,
                    containerAppId = containerAppId,
                    appId = appId,
                )
            }.onSuccess {
                val knownItems = (browseItems + subscribedItems).associateBy { item -> item.publishedFileId }
                val merged = buildList {
                    addAll(it)
                    optimisticSubscribedIds.forEach { optimisticId ->
                        if (none { item -> item.publishedFileId == optimisticId }) {
                            knownItems[optimisticId]?.let(::add)
                        }
                    }
                }
                subscribedItems = merged
                    .groupBy { item -> item.publishedFileId }
                    .map { (publishedFileId, candidates) ->
                        candidates.fold(knownItems[publishedFileId]) { best, candidate ->
                            mergeWorkshopItemUi(best, candidate)
                        } ?: candidates.reduce(::mergeWorkshopItemUi)
                    }
                    .let { items ->
                        val activeIds = items.map { it.publishedFileId }.toSet() + optimisticSubscribedIds
                        items.map { workshopItem ->
                            workshopItem.copy(
                                missingDependencyIds = workshopItem.dependencyIds.filterNot { it in activeIds },
                            )
                        }
                    }
                    .sortedWith(
                        compareBy<WorkshopItemUi> { it.loadOrder ?: Int.MAX_VALUE }
                            .thenByDescending { downloadProgress.containsKey(it.publishedFileId) }
                            .thenByDescending { item -> item.timeUpdated },
                    )
                optimisticSubscribedIds.removeAll { optimisticId ->
                    subscribedItems.any { item -> item.publishedFileId == optimisticId }
                }
                selectedItem?.let {
                    refreshSelectedItemDependencies(it)
                }
            }.onFailure {
                SnackbarManager.show(it.message ?: "Failed to load subscriptions")
            }
            isLoadingSubscriptions = false
        }
    }

    suspend fun resolveDependencyGraph(
        rootItem: WorkshopItemUi,
        subscribedIds: Set<Long>,
    ): Pair<WorkshopItemUi, List<WorkshopItemUi>> {
        val resolved = linkedMapOf<Long, WorkshopItemUi>()
        val plannedIds = subscribedIds.toMutableSet()
        val queue = ArrayDeque<WorkshopItemUi>()
        val enrichedRoot = WorkshopService.enrichWithHtmlDependencies(rootItem)
        queue.add(enrichedRoot)
        val seen = mutableSetOf<Long>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current.publishedFileId)) continue
            val missingIds = current.dependencyIds.filterNot { it in plannedIds || it == current.publishedFileId }
            val nextIds = current.dependencyIds.filterNot { it == current.publishedFileId }
            val currentWithMissing = current.copy(missingDependencyIds = missingIds)
            if (current.publishedFileId != rootItem.publishedFileId) {
                resolved[current.publishedFileId] = currentWithMissing
            }
            if (nextIds.isEmpty()) continue

            val fetchedItems = WorkshopService.getItemsByIds(appId, nextIds).associateBy { it.publishedFileId }
            nextIds.forEach { dependencyId ->
                if (dependencyId !in plannedIds) {
                    plannedIds += dependencyId
                }
                val baseItem = fetchedItems[dependencyId] ?: WorkshopItemUi(
                    publishedFileId = dependencyId,
                    title = current.dependencyTitles[dependencyId] ?: dependencyId.toString(),
                    previewUrl = "",
                    description = "",
                    subscriberCount = 0,
                    sizeBytes = 0L,
                    timeUpdated = 0L,
                )
                val titledItem = baseItem.copy(
                    title = current.dependencyTitles[dependencyId]
                        ?: baseItem.title.ifBlank { dependencyId.toString() },
                )
                queue.add(WorkshopService.enrichWithHtmlDependencies(titledItem))
            }
        }

        fun installOrder(
            item: WorkshopItemUi,
            pool: Map<Long, WorkshopItemUi>,
            out: MutableList<WorkshopItemUi>,
            visited: MutableSet<Long>,
        ) {
            if (!visited.add(item.publishedFileId)) return
            item.dependencyIds.forEach { dependencyId ->
                pool[dependencyId]?.let { dependencyItem ->
                    installOrder(dependencyItem, pool, out, visited)
                }
            }
            if (item.publishedFileId != rootItem.publishedFileId && item.publishedFileId !in subscribedIds) {
                out += item
            }
        }

        val orderedDependencies = mutableListOf<WorkshopItemUi>()
        installOrder(
            enrichedRoot.copy(
                missingDependencyIds = enrichedRoot.dependencyIds.filterNot { it in subscribedIds },
            ),
            resolved,
            orderedDependencies,
            mutableSetOf(),
        )
        val rootWithMissing = enrichedRoot.copy(
            missingDependencyIds = enrichedRoot.dependencyIds.filterNot { it in subscribedIds || it == enrichedRoot.publishedFileId },
        )
        return rootWithMissing to orderedDependencies.distinctBy { it.publishedFileId }
    }

    suspend fun subscribeSingleItem(item: WorkshopItemUi): Boolean {
        busyIds.add(item.publishedFileId)
        val wasAlreadySubscribed = subscribedItems.any { it.publishedFileId == item.publishedFileId }
        if (!optimisticSubscribedIds.contains(item.publishedFileId)) {
            optimisticSubscribedIds.add(item.publishedFileId)
        }
        subscribedItems = (subscribedItems + item).distinctBy { it.publishedFileId }
        val success = runCatching {
            WorkshopService.subscribeAndSync(
                context = context,
                containerAppId = containerAppId,
                appId = appId,
                item = item,
                onState = { state ->
                    downloadProgress[item.publishedFileId] = state
                },
            )
        }.isSuccess
        if (success) {
            refreshSubscriptions()
        } else {
            optimisticSubscribedIds.remove(item.publishedFileId)
            if (!wasAlreadySubscribed) {
                subscribedItems = subscribedItems.filterNot { it.publishedFileId == item.publishedFileId }
            }
            SnackbarManager.show("Failed to subscribe ${item.title.ifBlank { item.publishedFileId.toString() }}")
        }
        downloadProgress.remove(item.publishedFileId)
        busyIds.remove(item.publishedFileId)
        return success
    }

    suspend fun installDependencyItems(items: List<WorkshopItemUi>): Int {
        var installedCount = 0
        for (dependencyItem in items) {
            if (busyIds.contains(dependencyItem.publishedFileId)) continue
            val success = subscribeSingleItem(dependencyItem)
            if (!success) return installedCount
            installedCount++
        }
        return installedCount
    }

    fun moveSubscribedItem(item: WorkshopItemUi, direction: Int) {
        scope.launch {
            val currentIndex = subscribedItems.indexOfFirst { it.publishedFileId == item.publishedFileId }
            if (currentIndex == -1) return@launch
            val targetIndex = (currentIndex + direction).coerceIn(0, subscribedItems.lastIndex)
            if (targetIndex == currentIndex) return@launch
            val reordered = subscribedItems.toMutableList().apply {
                val moved = removeAt(currentIndex)
                add(targetIndex, moved)
            }.mapIndexed { index, subscribedItem ->
                subscribedItem.copy(loadOrder = index)
            }
            subscribedItems = reordered
            if (selectedItem?.publishedFileId == item.publishedFileId) {
                selectedItem = reordered.firstOrNull { it.publishedFileId == item.publishedFileId }
            }
            runCatching {
                WorkshopService.saveLoadOrder(
                    context = context,
                    containerAppId = containerAppId,
                    appId = appId,
                    orderedItems = reordered,
                )
            }.onFailure {
                SnackbarManager.show(it.message ?: "Failed to update mod load order")
                refreshSubscriptions()
            }
        }
    }

    fun subscribe(item: WorkshopItemUi) {
        scope.launch {
            if (busyIds.contains(item.publishedFileId)) return@launch
            val subscribedSnapshot = subscribedItems.map { it.publishedFileId }.toMutableSet().apply {
                addAll(optimisticSubscribedIds)
            }
            val (enrichedItem, dependencyItems) = resolveDependencyGraph(item, subscribedSnapshot)
            val installedDependencies = installDependencyItems(dependencyItems)
            if (dependencyItems.isNotEmpty() && installedDependencies != dependencyItems.size) {
                SnackbarManager.show("Failed to install all required dependencies")
                return@launch
            }
            subscribeSingleItem(enrichedItem)
            if (selectedItem?.publishedFileId == enrichedItem.publishedFileId) {
                refreshSelectedItemDependenciesNow(enrichedItem)
            }
        }
    }

    fun unsubscribe(item: WorkshopItemUi) {
        scope.launch {
            if (busyIds.contains(item.publishedFileId)) return@launch
            busyIds.add(item.publishedFileId)
            runCatching {
                WorkshopService.unsubscribeAndSync(
                    context = context,
                    containerAppId = containerAppId,
                    appId = appId,
                    publishedFileId = item.publishedFileId,
                )
            }.onSuccess {
                optimisticSubscribedIds.remove(item.publishedFileId)
                subscribedItems = subscribedItems.filterNot { it.publishedFileId == item.publishedFileId }
                refreshSubscriptions()
            }.onFailure {
                SnackbarManager.show(it.message ?: "Failed to unsubscribe")
            }
            downloadProgress.remove(item.publishedFileId)
            busyIds.remove(item.publishedFileId)
        }
    }

    fun installMissingDependencies(item: WorkshopItemUi) {
        scope.launch {
            val subscribedSnapshot = subscribedItems.map { it.publishedFileId }.toMutableSet().apply {
                addAll(optimisticSubscribedIds)
            }
            val (enrichedItem, dependencyItems) = resolveDependencyGraph(item, subscribedSnapshot)
            selectedItem = enrichedItem
            selectedDependencyItems = dependencyItems
            if (dependencyItems.isEmpty()) {
                SnackbarManager.show("No missing dependencies found")
                return@launch
            }
            val installedCount = installDependencyItems(dependencyItems)
            if (installedCount > 0) {
                refreshSelectedItemDependenciesNow(enrichedItem)
                SnackbarManager.show("Installed $installedCount dependencies")
            }
        }
    }

    LaunchedEffect(appId) {
        browseTabFocusRequester.requestFocus()
        runBrowse(reset = true)
        refreshSubscriptions()
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != 0 && isSearchVisible) {
            closeSearch()
        }
    }

    LaunchedEffect(selectedItem?.publishedFileId) {
        val item = selectedItem
        if (item == null) {
            selectedDependencyItems = emptyList()
            return@LaunchedEffect
        }
        refreshSelectedItemDependenciesNow(item)
    }

    BackHandler(onBack = onBack)

    val subscribedIds = remember(subscribedItems) {
        subscribedItems.map { it.publishedFileId }.toSet()
    }

    selectedItem?.let { item ->
        WorkshopItemDetailPage(
            item = item,
            dependencyItems = selectedDependencyItems,
            isSubscribed = item.publishedFileId in subscribedIds,
            isBusy = busyIds.contains(item.publishedFileId) || item.missingDependencyIds.any { it in busyIds },
            progress = downloadProgress[item.publishedFileId],
            onSubscribe = { subscribe(item) },
            onUnsubscribe = { unsubscribe(item) },
            onInstallMissingDependencies = null,
            onBack = { selectedItem = null },
        )
        return
    }

    LaunchedEffect(browseGridState, selectedTab) {
        snapshotFlow { browseGridState.layoutInfo }
            .map { layoutInfo ->
                val totalItems = layoutInfo.totalItemsCount
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                Triple(totalItems, lastVisible, totalItems > 0 && lastVisible >= totalItems - 6)
            }
            .distinctUntilChanged()
            .collect { (_, _, nearEnd) ->
                if (selectedTab == 0 && nearEnd && canLoadMoreBrowse && !isLoadingBrowse) {
                    runBrowse(reset = false)
                }
            }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        selectedTab = (selectedTab - 1).coerceAtLeast(0)
                        true
                    }
                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        selectedTab = (selectedTab + 1).coerceAtMost(1)
                        true
                    }
                    else -> false
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (selectedTab == 0 && isSearchVisible) {
                            WorkshopSearchField(
                                value = searchDraft,
                                onValueChange = { searchDraft = it },
                                onSearch = ::submitSearch,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.workshop_title, gameTitle),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    },
                    expandedHeight = (configuration.screenHeightDp * 0.042f).dp.coerceAtLeast(32.dp),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        if (selectedTab == 0) {
                            IconButton(
                                onClick = {
                                    if (isSearchVisible) {
                                        closeSearch(clearQuery = true)
                                    } else {
                                        isSearchVisible = true
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = stringResource(R.string.workshop_search),
                                )
                            }
                        } else {
                            IconButton(
                                onClick = ::refreshSubscriptions,
                                enabled = !isLoadingSubscriptions,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.workshop_refresh),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.height(40.dp),
                    divider = {},
                ) {
                    Tab(
                        modifier = Modifier.focusRequester(browseTabFocusRequester),
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = stringResource(R.string.workshop_browse_tab),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = stringResource(R.string.workshop_subscribed_tab),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        },
                    )
                }

                when (selectedTab) {
                    0 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 16.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = stringResource(R.string.workshop_filters),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                WorkshopSortType.entries.forEach { option ->
                                    FilterChip(
                                        selected = sortType == option,
                                        onClick = {
                                            sortType = option
                                            runBrowse(reset = true)
                                        },
                                        label = { Text(option.label) },
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (isLoadingBrowse && browseItems.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else if (browseItems.isEmpty()) {
                                WorkshopEmptyState(text = stringResource(R.string.workshop_no_items))
                            } else {
                                LazyVerticalGrid(
                                    modifier = Modifier.weight(1f),
                                    state = browseGridState,
                                    columns = GridCells.Fixed(4),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(browseItems, key = { it.publishedFileId }) { item ->
                                        WorkshopItemCard(
                                            item = item,
                                            buttonText = if (item.publishedFileId in subscribedIds) {
                                                stringResource(R.string.workshop_subscribed)
                                            } else {
                                                stringResource(R.string.workshop_subscribe)
                                            },
                                            isPrimaryAction = item.publishedFileId !in subscribedIds,
                                            buttonEnabled = item.publishedFileId !in subscribedIds &&
                                                !busyIds.contains(item.publishedFileId),
                                            progress = downloadProgress[item.publishedFileId],
                                            onButtonClick = { subscribe(item) },
                                            onItemClick = { selectedItem = item },
                                        )
                                    }

                                    if (isLoadingBrowse) {
                                        item(key = "loading") {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 12.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        if (isLoadingSubscriptions && subscribedItems.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (subscribedItems.isEmpty()) {
                            WorkshopEmptyState(text = stringResource(R.string.workshop_no_subscriptions))
                        } else {
                            val activeDownloadItems = subscribedItems.filter {
                                downloadProgress.containsKey(it.publishedFileId)
                            }
                            val installedItems = subscribedItems.filterNot {
                                downloadProgress.containsKey(it.publishedFileId)
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (activeDownloadItems.isNotEmpty()) {
                                    item(key = "downloads_header") {
                                        WorkshopSectionHeader(
                                            title = stringResource(R.string.workshop_downloading_section),
                                            subtitle = stringResource(
                                                R.string.workshop_downloading_count,
                                                activeDownloadItems.size,
                                            ),
                                        )
                                    }
                                    items(activeDownloadItems, key = { it.publishedFileId }) { item ->
                                        WorkshopSubscribedRow(
                                            item = item,
                                            buttonEnabled = !busyIds.contains(item.publishedFileId),
                                            progress = downloadProgress[item.publishedFileId],
                                            canMoveUp = subscribedItems.indexOfFirst { it.publishedFileId == item.publishedFileId } > 0,
                                            canMoveDown = subscribedItems.indexOfFirst { it.publishedFileId == item.publishedFileId } < subscribedItems.lastIndex,
                                            onMoveUp = { moveSubscribedItem(item, -1) },
                                            onMoveDown = { moveSubscribedItem(item, 1) },
                                            onButtonClick = { unsubscribe(item) },
                                            onItemClick = { selectedItem = item },
                                        )
                                    }
                                }
                                if (installedItems.isNotEmpty()) {
                                    item(key = "installed_header") {
                                        WorkshopSectionHeader(
                                            title = stringResource(R.string.workshop_installed_section),
                                            subtitle = stringResource(
                                                R.string.workshop_installed_count,
                                                installedItems.size,
                                            ),
                                        )
                                    }
                                    items(installedItems, key = { it.publishedFileId }) { item ->
                                        WorkshopSubscribedRow(
                                            item = item,
                                            buttonEnabled = !busyIds.contains(item.publishedFileId),
                                            progress = downloadProgress[item.publishedFileId],
                                            canMoveUp = subscribedItems.indexOfFirst { it.publishedFileId == item.publishedFileId } > 0,
                                            canMoveDown = subscribedItems.indexOfFirst { it.publishedFileId == item.publishedFileId } < subscribedItems.lastIndex,
                                            onMoveUp = { moveSubscribedItem(item, -1) },
                                            onMoveDown = { moveSubscribedItem(item, 1) },
                                            onButtonClick = { unsubscribe(item) },
                                            onItemClick = { selectedItem = item },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkshopItemCard(
    item: WorkshopItemUi,
    buttonText: String,
    isPrimaryAction: Boolean,
    buttonEnabled: Boolean,
    progress: WorkshopDownloadState?,
    onButtonClick: () -> Unit,
    onItemClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .focusable(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        onClick = onItemClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (item.previewUrl.isNotBlank()) {
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { item.previewUrl },
                        imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                        loading = {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        failure = {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        previewPlaceholder = painterResource(R.drawable.testhero),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = item.title.ifBlank { item.publishedFileId.toString() },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(formatCount(item.subscriberCount)) },
                )
                if (item.sizeBytes > 0L) {
                    Text(
                        text = formatBytes(item.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (progress != null) {
                WorkshopItemProgress(progress = progress, compact = true)
            }

            if (isPrimaryAction) {
                Button(
                    onClick = onButtonClick,
                    enabled = buttonEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 0.dp),
                ) {
                    Text(
                        text = if (progress != null) progress.stage.ifBlank { buttonText } else buttonText,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onButtonClick,
                    enabled = buttonEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 0.dp),
                ) {
                    Text(buttonText, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun WorkshopSubscribedRow(
    item: WorkshopItemUi,
    buttonEnabled: Boolean,
    progress: WorkshopDownloadState?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onButtonClick: () -> Unit,
    onItemClick: () -> Unit,
) {
    Card(
        modifier = Modifier.focusable(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        onClick = onItemClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (item.previewUrl.isNotBlank()) {
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { item.previewUrl },
                        imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                        loading = {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        failure = {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        previewPlaceholder = painterResource(R.drawable.testhero),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { item.publishedFileId.toString() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Load order ${item.loadOrder?.plus(1) ?: "-"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description.ifBlank { stringResource(R.string.workshop_no_description) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatBytes(item.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatCount(item.subscriberCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.timeUpdated > 0L) {
                        Text(
                            text = formatUpdatedDate(item.timeUpdated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (item.missingDependencyIds.isNotEmpty() || item.dependencyIds.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            item.missingDependencyIds.isNotEmpty() ->
                                "Missing dependencies: ${item.missingDependencyIds.size}"
                            item.dependencyIds.isNotEmpty() ->
                                "Dependencies: ${item.dependencyIds.size}"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.missingDependencyIds.isNotEmpty()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                progress?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it.stage.ifBlank { stringResource(R.string.downloading) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    WorkshopItemProgress(progress = progress)
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp && buttonEnabled,
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Move up",
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown && buttonEnabled,
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Move down",
                        )
                    }
                }

                OutlinedButton(
                    onClick = onButtonClick,
                    enabled = buttonEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.workshop_unsubscribe))
                }
            }
        }
    }
}

@Composable
private fun WorkshopSectionHeader(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkshopItemProgress(progress: WorkshopDownloadState) {
    val ratio = if (progress.totalBytes > 0L) {
        (progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        -1f
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (ratio >= 0f) {
                "${progress.stage.ifBlank { "Downloading" }} ${(ratio * 100).toInt()}%"
            } else {
                progress.stage.ifBlank { "Downloading" }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (ratio >= 0f) {
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WorkshopItemProgress(
    progress: WorkshopDownloadState,
    compact: Boolean,
) {
    val ratio = if (progress.totalBytes > 0L) {
        (progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        -1f
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
    ) {
        Text(
            text = if (ratio >= 0f) {
                "${progress.stage.ifBlank { "Downloading" }} ${(ratio * 100).toInt()}%"
            } else {
                progress.stage.ifBlank { "Downloading" }
            },
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (ratio >= 0f) {
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 3.dp else 4.dp),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 3.dp else 4.dp),
            )
        }
    }
}

@Composable
private fun WorkshopEmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatUpdatedDate(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "Unknown"
    val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return formatter.format(Date(epochSeconds * 1000))
}

private fun mergeWorkshopItemUi(
    preferred: WorkshopItemUi?,
    fallback: WorkshopItemUi,
): WorkshopItemUi {
    if (preferred == null) return fallback
    return fallback.copy(
        title = preferred.title.ifBlank { fallback.title },
        previewUrl = preferred.previewUrl.ifBlank { fallback.previewUrl },
        description = preferred.description.ifBlank { fallback.description },
        subscriberCount = preferred.subscriberCount.takeIf { it > 0 } ?: fallback.subscriberCount,
        favoritedCount = preferred.favoritedCount.takeIf { it > 0 } ?: fallback.favoritedCount,
        viewCount = preferred.viewCount.takeIf { it > 0 } ?: fallback.viewCount,
        sizeBytes = preferred.sizeBytes.takeIf { it > 0L } ?: fallback.sizeBytes,
        timeCreated = maxOf(preferred.timeCreated, fallback.timeCreated),
        timeUpdated = maxOf(preferred.timeUpdated, fallback.timeUpdated),
        tags = preferred.tags.ifEmpty { fallback.tags },
        loadOrder = preferred.loadOrder ?: fallback.loadOrder,
        dependencyIds = preferred.dependencyIds.ifEmpty { fallback.dependencyIds },
        missingDependencyIds = preferred.missingDependencyIds.ifEmpty { fallback.missingDependencyIds },
    )
}

@Composable
private fun WorkshopSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    val placeholderText = stringResource(R.string.workshop_search_label)

    AndroidView(
        factory = { context ->
            EditText(context).apply {
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_SEARCH
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                isSingleLine = true
                hint = placeholderText
                background = ColorDrawable(android.graphics.Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                setTextColor(textColor.toArgb())
                setHintTextColor(hintColor.toArgb())
                textSize = 16f
                doAfterTextChanged { editable ->
                    val updated = editable?.toString().orEmpty()
                    if (updated != value) {
                        onValueChange(updated)
                    }
                }
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        keyboardController?.hide()
                        onSearch()
                        true
                    } else {
                        false
                    }
                }
                requestFocus()
            }
        },
        update = { editText ->
            if (editText.text.toString() != value) {
                editText.setText(value)
                editText.setSelection(value.length)
            }
            editText.setTextColor(textColor.toArgb())
            editText.setHintTextColor(hintColor.toArgb())
        },
        modifier = modifier,
    )
}
