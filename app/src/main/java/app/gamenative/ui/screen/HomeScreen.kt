package app.gamenative.ui.screen

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import app.gamenative.ui.model.HomeViewModel
import app.gamenative.ui.screen.library.HomeLibraryScreen
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToWorkshop: (Int) -> Unit,
    onChat: (Long) -> Unit,
    onClickExit: () -> Unit,
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onLogout: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onGoOnline: () -> Unit,
    onEditContainer: (String) -> Unit,
    isOffline: Boolean = false
) {
    // Pressing back while logged in, confirm we want to close the app.
    BackHandler {
        onClickExit()
    }

    // Always show the Library screen
    HomeLibraryScreen(
        onClickPlay = onClickPlay,
        onTestGraphics = onTestGraphics,
        onNavigateRoute = onNavigateRoute,
        onLogout = onLogout,
        onGoOnline = onGoOnline,
        isOffline = isOffline,
    )
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Preview_HomeScreen() {
    PluviaTheme {
        HomeScreen(
            onNavigateToWorkshop = {},
            onChat = {},
            onClickPlay = { _, _ -> },
            onTestGraphics = { },
            onLogout = {},
            onNavigateRoute = {},
            onClickExit = {},
            onGoOnline = {},
            onEditContainer = {},
        )
    }
}
