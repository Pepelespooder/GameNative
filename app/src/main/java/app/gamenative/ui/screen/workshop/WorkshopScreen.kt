package app.gamenative.ui.screen.workshop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.data.WorkshopDownloadProgress
import app.gamenative.data.WorkshopItem
import app.gamenative.data.WorkshopItemState
import app.gamenative.data.WorkshopQueryResult
import app.gamenative.ui.model.WorkshopSortType
import app.gamenative.ui.model.WorkshopViewModel
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun WorkshopScreen(
    onBack: () -> Unit,
    viewModel: WorkshopViewModel = hiltViewModel(),
) {
    val browseResults by viewModel.browseResults.collectAsStateWithLifecycle()
    val installedItems by viewModel.installedItems.collectAsStateWithLifecycle(emptyList())
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortType by viewModel.sortType.collectAsStateWithLifecycle()
    val isCheckingForUpdates by viewModel.isCheckingForUpdates.collectAsStateWithLifecycle()

    WorkshopScreenContent(
        browseResults = browseResults,
        installedItems = installedItems,
        downloadProgress = downloadProgress,
        searchQuery = searchQuery,
        sortType = sortType,
        isCheckingForUpdates = isCheckingForUpdates,
        onBack = onBack,
        onSearch = viewModel::search,
        onSortTypeChange = viewModel::setSortType,
        onLoadNextPage = viewModel::loadNextPage,
        onSubscribe = viewModel::subscribe,
        onUnsubscribe = viewModel::unsubscribe,
        onUpdateItem = viewModel::updateItem,
        onCheckForUpdates = viewModel::checkForUpdates,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkshopScreenContent(
    browseResults: List<WorkshopQueryResult>,
    installedItems: List<WorkshopItem>,
    downloadProgress: Map<Long, WorkshopDownloadProgress>,
    searchQuery: String,
    sortType: WorkshopSortType,
    isCheckingForUpdates: Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onSortTypeChange: (WorkshopSortType) -> Unit,
    onLoadNextPage: () -> Unit,
    onSubscribe: (Long) -> Unit,
    onUnsubscribe: (Long) -> Unit,
    onUpdateItem: (Long) -> Unit,
    onCheckForUpdates: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedItem by remember { mutableStateOf<WorkshopQueryResult?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }
    val browseGridState = rememberLazyGridState()
    val tabs = listOf(
        stringResource(R.string.workshop_tab_browse),
        stringResource(R.string.workshop_tab_installed),
        stringResource(R.string.workshop_tab_updates),
    )

    // Auto-switch to Browse tab when a search query is active
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) selectedTab = 0
    }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        if (searchQuery.isNotEmpty()) onSearch("")
    }

    if (selectedItem != null) {
        val item = selectedItem!!
        val installedIds = remember(installedItems) { installedItems.map { it.publishedFileId }.toSet() }
        WorkshopItemDetailPage(
            item = item,
            isInstalled = item.publishedFileId in installedIds,
            progress = downloadProgress[item.publishedFileId],
            onSubscribe = { onSubscribe(item.publishedFileId) },
            onBack = { selectedItem = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        val textColor = MaterialTheme.colorScheme.onSurface.let {
                            android.graphics.Color.argb(
                                (it.alpha * 255).toInt(),
                                (it.red * 255).toInt(),
                                (it.green * 255).toInt(),
                                (it.blue * 255).toInt(),
                            )
                        }
                        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.let {
                            android.graphics.Color.argb(
                                (it.alpha * 255).toInt(),
                                (it.red * 255).toInt(),
                                (it.green * 255).toInt(),
                                (it.blue * 255).toInt(),
                            )
                        }
                        AndroidView(
                            factory = { ctx ->
                                EditText(ctx).apply {
                                    isSingleLine = true
                                    background = null
                                    imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                                    hint = ctx.getString(R.string.workshop_search_placeholder)
                                    setTextColor(textColor)
                                    setHintTextColor(hintColor)
                                    addTextChangedListener(object : android.text.TextWatcher {
                                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                            val newText = s?.toString() ?: ""
                                            if (newText != searchQuery) onSearch(newText)
                                        }
                                        override fun afterTextChanged(s: android.text.Editable?) {}
                                    })
                                    requestFocus()
                                }
                            },
                            update = { editText ->
                                if (editText.text.toString() != searchQuery) {
                                    editText.setText(searchQuery)
                                    editText.setSelection(searchQuery.length)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.workshop_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearchActive) {
                            isSearchActive = false
                            if (searchQuery.isNotEmpty()) onSearch("")
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSearchActive) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearch("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    }
                },
                expandedHeight = 40.dp,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab, modifier = Modifier.height(36.dp)) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            when (selectedTab) {
                0 -> BrowseTab(
                    browseResults = browseResults,
                    installedItems = installedItems,
                    downloadProgress = downloadProgress,
                    sortType = sortType,
                    searchQuery = searchQuery,
                    gridState = browseGridState,
                    onSortTypeChange = onSortTypeChange,
                    onLoadNextPage = onLoadNextPage,
                    onSubscribe = onSubscribe,
                    onItemClick = { selectedItem = it },
                )
                1 -> InstalledTab(
                    installedItems = installedItems,
                    downloadProgress = downloadProgress,
                    onUnsubscribe = onUnsubscribe,
                    onUpdate = onUpdateItem,
                )
                2 -> UpdatesTab(
                    installedItems = installedItems,
                    isCheckingForUpdates = isCheckingForUpdates,
                    onCheckForUpdates = onCheckForUpdates,
                    onUpdateItem = onUpdateItem,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WorkshopScreenPreview() {
    val sampleMods = listOf(
        WorkshopQueryResult(1L, "HD Texture Pack", "", 12500, 524288000L, 1715000000L, "Beautiful textures", favoritedCount = 3200, viewCount = 98000, timeCreated = 1700000000L, tags = listOf("Graphics", "Textures")),
        WorkshopQueryResult(2L, "Extra Maps", "", 8200, 104857600L, 1715100000L, "More places to go", favoritedCount = 1100, viewCount = 42000, tags = listOf("Map")),
        WorkshopQueryResult(3L, "Balance Patch", "", 4500, 1024000L, 1715200000L, "Fairer gameplay", favoritedCount = 900, viewCount = 21000, tags = listOf("Gameplay")),
    )
    val installedItems = listOf(
        WorkshopItem(publishedFileId = 1L, appId = 440, title = "HD Texture Pack", sizeBytes = 524288000L, state = WorkshopItemState.SUBSCRIBED),
        WorkshopItem(publishedFileId = 4L, appId = 440, title = "Old Mod", sizeBytes = 2048000L, state = WorkshopItemState.UPDATE_AVAILABLE),
    )
    PluviaTheme {
        WorkshopScreenContent(
            browseResults = sampleMods,
            installedItems = installedItems,
            downloadProgress = emptyMap(),
            searchQuery = "",
            sortType = WorkshopSortType.TRENDING,
            isCheckingForUpdates = false,
            onBack = {},
            onSearch = {},
            onSortTypeChange = {},
            onLoadNextPage = {},
            onSubscribe = {},
            onUnsubscribe = {},
            onUpdateItem = {},
            onCheckForUpdates = {},
        )
    }
}
