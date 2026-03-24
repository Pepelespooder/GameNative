package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PluviaApp
import app.gamenative.data.AmazonGame
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.data.LibraryItem
import app.gamenative.enums.GameSource
import app.gamenative.enums.PathType
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicCloudSavesManager
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.screen.MainUiEvent
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.IntentLaunchManager
import com.winlator.container.Container
import com.winlator.x11.Window
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.file.Paths
import javax.inject.Inject
import kotlin.io.path.name

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiEvent = Channel<MainUiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _showBootingSplash = MutableStateFlow(false)
    val showBootingSplash: StateFlow<Boolean> = _showBootingSplash.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private var bootingSplashTimeoutJob: Job? = null

    fun setShowBootingSplash(show: Boolean) {
        _showBootingSplash.value = show
    }

    fun onGameStarted(appId: String) {
        setShowBootingSplash(true)
        // Set a timeout to hide the splash if it doesn't close normally
        bootingSplashTimeoutJob?.cancel()
        bootingSplashTimeoutJob = viewModelScope.launch {
            delay(30000) // 30 seconds timeout
            setShowBootingSplash(false)
        }
    }

    fun onGameExited(context: Context, appId: String, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                Timber.tag("Exit").i("Exiting, getting feedback for appId: $appId")
                bootingSplashTimeoutJob?.cancel()
                bootingSplashTimeoutJob = null
                setShowBootingSplash(false)
                // Check if we have a temporary override before doing anything
                val hadTemporaryOverride = IntentLaunchManager.hasTemporaryOverride(appId)

                val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                Timber.tag("Exit").i("Got game id: $gameId")
                SteamService.notifyRunningProcesses()

                handleExitCloudSync(context, appId, gameId)

                // Prompt user to save temporary container configuration if one was applied
                if (hadTemporaryOverride) {
                    PluviaApp.events.emit(AndroidEvent.PromptSaveContainerConfig(appId))
                    // Dialog handler in PluviaMain manages the save/discard logic
                }

                // After app closes, check if we need to show the feedback dialog
                // Show feedback if: first time running this game OR config was changed
                try {
                    // Do not show the Feedback form for non-steam games until we can support.
                    val feedbackGameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
                    if (feedbackGameSource == GameSource.STEAM) {
                        val container = ContainerUtils.getContainer(context, appId)

                        val shown = container.getExtra("discord_support_prompt_shown", "false") == "true"
                        val configChanged = container.getExtra("config_changed", "false") == "true"
                        if (!shown) {
                            container.putExtra("discord_support_prompt_shown", "true")
                            container.saveData()
                            _uiEvent.send(MainUiEvent.ShowGameFeedbackDialog(appId))
                        }

                        // Only show feedback if container config was changed before this game run
                        if (configChanged) {
                            // Clear the flag
                            container.putExtra("config_changed", "false")
                            container.saveData()
                            // Show the feedback dialog
                            _uiEvent.send(MainUiEvent.ShowGameFeedbackDialog(appId))
                        }
                    } else {
                        Timber.d("Non-Steam Game Detected, not showing feedback")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to check/update feedback dialog state for $appId")
                }
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private suspend fun handleExitCloudSync(context: Context, appId: String, gameId: Int) {
        val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
        if (ContainerUtils.isLocalSavesOnly(context, appId) || isOffline.value) {
            Timber.tag("Exit").i("Local saves only or offline mode enabled for $appId — skipping cloud sync on exit")
            
            // For Steam games, we still want to update local last played even if sync is skipped
            if (gameSource == GameSource.STEAM) {
                SteamService.updateLastPlayed(gameId, System.currentTimeMillis() / 1000L)
            }
            return
        }

        if (gameSource == GameSource.GOG) {
            Timber.tag("GOG").i("[Cloud Saves] GOG Game detected for $appId — syncing cloud saves after close")
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    Timber.tag("GOG").d("[Cloud Saves] Starting post-game upload sync for $appId")
                    val syncSuccess = app.gamenative.service.gog.GOGService.syncCloudSaves(
                        context = context,
                        appId = appId,
                        preferredAction = "upload",
                    )
                    if (syncSuccess) {
                        Timber.tag("GOG").i("[Cloud Saves] Upload sync completed successfully for $appId")
                    } else {
                        Timber.tag("GOG").w("[Cloud Saves] Upload sync failed for $appId")
                    }
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "[Cloud Saves] Exception during upload sync for $appId")
                }
            }
            return
        }

        if (gameSource == GameSource.EPIC) {
            Timber.tag("Epic").i("[Cloud Saves] Epic Game detected for $appId — syncing cloud saves after close")
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    Timber.tag("Epic").d("[Cloud Saves] Starting post-game upload sync for $gameId")
                    val syncSuccess = EpicCloudSavesManager.syncCloudSaves(
                        context = context,
                        appId = gameId,
                        preferredAction = "upload",
                    )
                    if (syncSuccess) {
                        Timber.tag("Epic").i("[Cloud Saves] Upload sync completed successfully for $gameId")
                    } else {
                        Timber.tag("Epic").w("[Cloud Saves] Upload sync failed for $gameId")
                    }
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "[Cloud Saves] Exception during upload sync for $gameId")
                }
            }
            return
        }

        if (gameSource == GameSource.STEAM) {
            // Update last played timestamp locally for the Steam game that just exited
            SteamService.updateLastPlayed(gameId, System.currentTimeMillis() / 1000L)

            try {
                SteamService.closeApp(context, gameId, isOffline.value) { prefix ->
                    PathType.from(prefix).toAbsPath(context, gameId, SteamService.userSteamId!!.accountID)
                }.await()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.tag("Steam").e(t, "[Cloud Saves] Exception during close app sync for $gameId")
            }

            // Pull updated playtime and last_played from Steam after the session ends
            try {
                SteamService.refreshOwnedGamesFromServer()
            } catch (t: Throwable) {
                Timber.tag("Steam").w(t, "Failed to refresh owned games after game exit")
            }
        }
    }

    fun onWindowMapped(context: Context, window: Window, appId: String) {
        viewModelScope.launch {
            // Hide the booting splash when a window is mapped
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)

            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)

            SteamService.getAppInfoOf(gameId)?.let { appInfo ->
                // TODO: this should not be a search, the app should have been launched with a specific launch config that we then use to compare
                val launchConfig = SteamService.getWindowsLaunchInfos(gameId).firstOrNull {
                    val gameExe = Paths.get(it.executable.replace('\\', '/')).name.lowercase()
                    val windowExe = window.className.lowercase()
                    gameExe == windowExe || gameExe == windowExe + ".exe"
                }

                if (launchConfig != null) {
                    Timber.tag("Window").i("Mapped window matches launch config: ${launchConfig.executable}")
                }
            }
        }
    }
}
