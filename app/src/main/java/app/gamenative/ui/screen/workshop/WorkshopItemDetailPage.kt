package app.gamenative.ui.screen.workshop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.WorkshopDownloadProgress
import app.gamenative.data.WorkshopQueryResult
import com.skydoves.landscapist.coil.CoilImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WorkshopItemDetailPage(
    item: WorkshopQueryResult,
    isInstalled: Boolean,
    progress: WorkshopDownloadProgress?,
    onSubscribe: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val isDownloading = progress != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = item.title.ifBlank { stringResource(R.string.workshop_detail_fallback_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                expandedHeight = 48.dp,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (item.previewUrl.isNotBlank()) {
                item {
                    CoilImage(
                        imageModel = { item.previewUrl },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = onSubscribe,
                        enabled = !isInstalled && !isDownloading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = when {
                                isInstalled -> stringResource(R.string.workshop_btn_installed)
                                isDownloading -> stringResource(R.string.workshop_btn_downloading)
                                else -> stringResource(R.string.workshop_btn_subscribe)
                            }
                        )
                    }
                    if (isDownloading && progress != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (progress.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { (progress.bytesDownloaded.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    StatColumn(label = stringResource(R.string.workshop_stat_subscribers), value = formatCount(item.subscriberCount))
                    StatColumn(label = stringResource(R.string.workshop_stat_favorites), value = formatCount(item.favoritedCount))
                    StatColumn(label = stringResource(R.string.workshop_stat_views), value = formatCount(item.viewCount))
                    StatColumn(label = stringResource(R.string.workshop_stat_size), value = formatBytes(item.sizeBytes))
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (item.timeCreated > 0L) {
                        StatColumn(
                            label = stringResource(R.string.workshop_stat_posted),
                            value = dateFormat.format(Date(item.timeCreated * 1000L)),
                        )
                    }
                    if (item.timeUpdated > 0L) {
                        StatColumn(
                            label = stringResource(R.string.workshop_stat_updated),
                            value = dateFormat.format(Date(item.timeUpdated * 1000L)),
                        )
                    }
                }
            }

            if (item.tags.isNotEmpty()) {
                item {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.tags.forEach { tag ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }

            if (item.description.isNotBlank()) {
                item {
                    Text(
                        text = stringResource(R.string.workshop_label_description),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
