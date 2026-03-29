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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.WorkshopDownloadState
import app.gamenative.service.WorkshopItemUi
import app.gamenative.ui.component.LoadingScreen
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WorkshopItemDetailPage(
    item: WorkshopItemUi,
    dependencyItems: List<WorkshopItemUi>,
    isSubscribed: Boolean,
    isBusy: Boolean,
    progress: WorkshopDownloadState?,
    onSubscribe: () -> Unit,
    onUnsubscribe: () -> Unit,
    onInstallMissingDependencies: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    BackHandler(onBack = onBack)

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val compactBarHeight = (configuration.screenHeightDp * 0.06f).dp.coerceAtLeast(40.dp)
    val previewHeight = (configuration.screenHeightDp * 0.25f).dp

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = item.title.ifBlank { stringResource(R.string.workshop_detail_fallback_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                expandedHeight = compactBarHeight,
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
                        imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight),
                        loading = { LoadingScreen() },
                        previewPlaceholder = painterResource(R.drawable.testhero),
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (isSubscribed) {
                        OutlinedButton(
                            onClick = onUnsubscribe,
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (progress != null) {
                                    progress.stage.ifBlank { stringResource(R.string.workshop_subscribed) }
                                } else {
                                    stringResource(R.string.workshop_unsubscribe)
                                },
                            )
                        }
                    } else {
                        Button(
                            onClick = onSubscribe,
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (progress != null) {
                                    progress.stage.ifBlank { stringResource(R.string.downloading) }
                                } else {
                                    stringResource(R.string.workshop_subscribe)
                                },
                            )
                        }
                    }
                    if (progress != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        WorkshopDetailProgress(progress = progress)
                    }
                    if (item.missingDependencyIds.isNotEmpty() && onInstallMissingDependencies != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onInstallMissingDependencies,
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Install missing dependencies")
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
                    StatColumn(
                        label = stringResource(R.string.workshop_stat_subscribers),
                        value = formatCount(item.subscriberCount),
                    )
                    if (item.favoritedCount > 0) {
                        StatColumn(
                            label = stringResource(R.string.workshop_stat_favorites),
                            value = formatCount(item.favoritedCount),
                        )
                    }
                    if (item.viewCount > 0) {
                        StatColumn(
                            label = stringResource(R.string.workshop_stat_views),
                            value = formatCount(item.viewCount),
                        )
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
                    StatColumn(
                        label = stringResource(R.string.workshop_stat_size),
                        value = formatBytes(item.sizeBytes),
                    )
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

            if (item.dependencyIds.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Dependencies",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    Text(
                        text = if (item.missingDependencyIds.isNotEmpty()) {
                            "Missing ${item.missingDependencyIds.size} required item(s)"
                        } else {
                            "${item.dependencyIds.size} required item(s) detected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.missingDependencyIds.isNotEmpty()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val dependencyItemsById = dependencyItems.associateBy { it.publishedFileId }
                        item.dependencyIds.forEach { dependencyId ->
                            val dependencyItem = dependencyItemsById[dependencyId]
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = if (dependencyId in item.missingDependencyIds) {
                                            "Missing ${dependencyItem?.title ?: item.dependencyTitles[dependencyId] ?: dependencyId}"
                                        } else {
                                            dependencyItem?.title ?: item.dependencyTitles[dependencyId] ?: dependencyId.toString()
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.workshop_label_description),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                Text(
                    text = item.description.ifBlank { stringResource(R.string.workshop_no_description) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
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

@Composable
private fun WorkshopDetailProgress(progress: WorkshopDownloadState) {
    val ratio = if (progress.totalBytes > 0L) {
        (progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        -1f
    }
    Column(
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
        )
        if (ratio >= 0f) {
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
