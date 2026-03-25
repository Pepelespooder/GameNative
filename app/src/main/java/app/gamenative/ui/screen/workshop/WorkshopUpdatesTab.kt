package app.gamenative.ui.screen.workshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.WorkshopItem
import app.gamenative.data.WorkshopItemState

@Composable
internal fun UpdatesTab(
    installedItems: List<WorkshopItem>,
    isCheckingForUpdates: Boolean,
    onCheckForUpdates: () -> Unit,
    onUpdateItem: (Long) -> Unit,
) {
    val updatableItems = remember(installedItems) {
        installedItems.filter { it.state == WorkshopItemState.UPDATE_AVAILABLE }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onCheckForUpdates, enabled = !isCheckingForUpdates) {
                if (isCheckingForUpdates) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.workshop_check_for_updates))
                }
            }
            if (updatableItems.isNotEmpty()) {
                Button(onClick = { updatableItems.forEach { onUpdateItem(it.publishedFileId) } }) {
                    Text(stringResource(R.string.workshop_update_all, updatableItems.size))
                }
            }
        }

        if (updatableItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.workshop_no_updates), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(updatableItems, key = { it.publishedFileId }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.title.ifBlank { stringResource(R.string.workshop_item_unknown, item.publishedFileId) },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Button(onClick = { onUpdateItem(item.publishedFileId) }) {
                                Text(stringResource(R.string.workshop_btn_update))
                            }
                        }
                    }
                }
            }
        }
    }
}
