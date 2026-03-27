package app.gamenative.ui.data

import androidx.compose.ui.graphics.vector.ImageVector

data class GameHeaderAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
)
