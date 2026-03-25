package app.gamenative.ui.screen.workshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.WorkshopDownloadProgress
import app.gamenative.data.WorkshopItem
import app.gamenative.data.WorkshopItemState

@Composable
internal fun InstalledTab(
    installedItems: List<WorkshopItem>,
    downloadProgress: Map<Long, WorkshopDownloadProgress>,
    onUnsubscribe: (Long) -> Unit,
    onUpdate: (Long) -> Unit,
) {
    if (installedItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.workshop_no_installed), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(installedItems, key = { it.publishedFileId }) { item ->
            InstalledItemRow(
                item = item,
                progress = downloadProgress[item.publishedFileId],
                onUnsubscribe = { onUnsubscribe(item.publishedFileId) },
                onUpdate = { onUpdate(item.publishedFileId) },
            )
        }
    }
}

@Composable
private fun InstalledItemRow(
    item: WorkshopItem,
    progress: WorkshopDownloadProgress?,
    onUnsubscribe: () -> Unit,
    onUpdate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { stringResource(R.string.workshop_item_unknown, item.publishedFileId) },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatBytes(item.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (item.state) {
                        WorkshopItemState.UPDATE_AVAILABLE -> Text(
                            stringResource(R.string.workshop_label_update_available),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        WorkshopItemState.FAILED -> Text(
                            stringResource(R.string.workshop_label_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> {}
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.state == WorkshopItemState.UPDATE_AVAILABLE || item.state == WorkshopItemState.FAILED) {
                        OutlinedButton(onClick = onUpdate) {
                            Text(stringResource(if (item.state == WorkshopItemState.FAILED) R.string.workshop_btn_retry else R.string.workshop_btn_update))
                        }
                    }
                    OutlinedButton(
                        onClick = onUnsubscribe,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.workshop_btn_remove))
                    }
                }
            }
            if (item.state == WorkshopItemState.DOWNLOADING) {
                Spacer(modifier = Modifier.height(8.dp))
                if (progress != null && progress.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { (progress.bytesDownloaded.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
