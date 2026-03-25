package app.gamenative.ui.screen.workshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.WorkshopDownloadProgress
import app.gamenative.data.WorkshopItem
import app.gamenative.data.WorkshopQueryResult
import app.gamenative.ui.model.WorkshopSortType
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseTab(
    browseResults: List<WorkshopQueryResult>,
    installedItems: List<WorkshopItem>,
    downloadProgress: Map<Long, WorkshopDownloadProgress>,
    sortType: WorkshopSortType,
    searchQuery: String,
    gridState: LazyGridState,
    onSortTypeChange: (WorkshopSortType) -> Unit,
    onLoadNextPage: () -> Unit,
    onSubscribe: (Long) -> Unit,
    onItemClick: (WorkshopQueryResult) -> Unit,
) {
    val installedIds = remember(installedItems) { installedItems.map { it.publishedFileId }.toSet() }

    LaunchedEffect(gridState, browseResults.size) {
        snapshotFlow { gridState.layoutInfo }
            .map { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = info.totalItemsCount
                totalItems > 0 && lastVisible >= totalItems - 6
            }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) onLoadNextPage() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (searchQuery.isNotEmpty()) {
                item {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(WorkshopSortType.RELEVANCE.title) },
                    )
                }
            } else {
                items(WorkshopSortType.entries.filter { it != WorkshopSortType.RELEVANCE }.toTypedArray()) { type ->
                    FilterChip(
                        selected = sortType == type,
                        onClick = { onSortTypeChange(type) },
                        label = { Text(type.title) },
                    )
                }
            }
        }

        if (browseResults.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.workshop_no_results), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(browseResults, key = { it.publishedFileId }) { item ->
                    WorkshopItemCard(
                        item = item,
                        isInstalled = item.publishedFileId in installedIds,
                        progress = downloadProgress[item.publishedFileId],
                        onSubscribe = { onSubscribe(item.publishedFileId) },
                        onItemClick = { onItemClick(item) },
                    )
                }
            }
        }
    }
}
