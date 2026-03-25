package app.gamenative.ui.screen.workshop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.WorkshopDownloadProgress
import app.gamenative.data.WorkshopQueryResult
import com.skydoves.landscapist.coil.CoilImage

@Composable
internal fun WorkshopItemCard(
    item: WorkshopQueryResult,
    isInstalled: Boolean,
    progress: WorkshopDownloadProgress?,
    onSubscribe: () -> Unit,
    onItemClick: () -> Unit,
) {
    val isDownloading = progress != null

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onItemClick)) {
        Column {
            if (item.previewUrl.isNotBlank()) {
                CoilImage(
                    imageModel = { item.previewUrl },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            }
            Column(modifier = Modifier.padding(4.dp)) {
                Text(
                    text = item.title.ifBlank { stringResource(R.string.workshop_item_unnamed) },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Button(
                    onClick = onSubscribe,
                    enabled = !isInstalled && !isDownloading,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 0.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = when {
                            isInstalled -> stringResource(R.string.workshop_btn_installed)
                            isDownloading -> stringResource(R.string.workshop_btn_downloading_short)
                            else -> stringResource(R.string.workshop_btn_subscribe)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isDownloading && progress != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    if (progress.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.bytesDownloaded.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    }
                }
            }
        }
    }
}
