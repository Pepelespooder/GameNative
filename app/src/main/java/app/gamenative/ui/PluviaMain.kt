package app.gamenative.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import app.gamenative.BuildConfig
import app.gamenative.Constants
import app.gamenative.MainActivity
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.WorkshopItem
import app.gamenative.enums.AppTheme
import app.gamenative.enums.LoginResult
import app.gamenative.enums.PathType
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.component.AchievementOverlay
import app.gamenative.ui.component.ConnectionStatusBanner
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.GameFeedbackDialog
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.GameFeedbackDialogState
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.components.BootingSplash
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.ConnectionState
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.model.MainViewModel
import app.gamenative.ui.model.MainViewModel.MainUiEvent
import app.gamenative.ui.screen.HomeScreen
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.screen.login.UserLoginScreen
import app.gamenative.ui.screen.settings.SettingsScreen
import app.gamenative.ui.screen.xserver.XServerScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.PlatformAuthUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.GameFeedbackUtils
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.UpdateChecker
import app.gamenative.utils.UpdateInfo
import app.gamenative.utils.UpdateInstaller
import app.gamenative.utils.LaunchDependencies
import app.gamenative.workshop.WorkshopManager
import app.gamenative.ui.screen.library.toSubscription
import app.gamenative.ui.screen.workshop.WorkshopScreen
import app.gamenative.ui.screen.library.resolveGameAppId
import app.gamenative.ui.screen.library.needsDeferLaunch
import app.gamenative.ui.screen.library.GameResolutionResult
import com.google.android.play.core.splitcompat.SplitCompat
import com.winlator.container.Container
import com.winlator.container.ContainerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.io.File
import java.util.EnumSet
import kotlin.reflect.KFunction2

private fun NavController.navigateFromLoginIfNeeded(targetRoute: String, reason: String) {
    val currentRoute = currentDestination?.route
    if (currentRoute == PluviaScreen.LoginUser.route) {
        Timber.i("[Navigation]: Auto-navigating from Login to $targetRoute (reason: $reason)")
        navigate(targetRoute) {
            popUpTo(PluviaScreen.LoginUser.route) { inclusive = true }
        }
    }
}

private fun trackGameLaunched(appId: String) {
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    val gameName = ContainerUtils.resolveGameName(appId)

    com.posthog.PostHog.capture(
        event = "game_launched",
        properties = mapOf(
            "app_id" to appId,
            "game_name" to gameName,
            "game_source" to gameSource.name,
        ),
    )
}

/** Consume pending launch request only if it's a Steam login failure, and show failure snackbar. */
private fun consumePendingSteamLoginError(context: Context) {
    val request = MainActivity.peekPendingLaunchRequest() ?: return
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(request.appId)
    if (gameSource != GameSource.STEAM || SteamService.isLoggedIn) return
    MainActivity.consumePendingLaunchRequest()
    SnackbarManager.show(context.getString(R.string.intent_launch_steam_login_failed))
}

/** Show snackbar for a deferred launch based on the game's source. Returns true if shown. */
private fun showDeferredLaunchSnackbar(context: Context, appId: String): Boolean {
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    return when {
        gameSource == GameSource.STEAM && SteamService.isConnected -> {
            SnackbarManager.show(context.getString(R.string.intent_launch_steam_pending))
            true
        }
        gameSource != GameSource.STEAM -> {
            SnackbarManager.show(context.getString(R.string.intent_launch_service_pending))
            true
        }
        else -> false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluviaMain(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val isOfflineState by viewModel.isOffline.collectAsStateWithLifecycle()

    var msgDialogState by remember { mutableStateOf(MessageDialogState(false)) }
    fun updateMessageDialogState(newState: MessageDialogState) {
        msgDialogState = newState
    }

    var gameFeedbackState by remember { mutableStateOf(GameFeedbackDialogState(visible = false)) }

    var hasBack by remember { mutableStateOf(false) }
    var gameBackAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var shownPendingLaunchSnackbar by remember { mutableStateOf(false) }
    var pendingLaunchGeneration by remember { mutableIntStateOf(0) }

    var openContainerConfigForAppId by remember { mutableStateOf<String?>(null) }

    var isConnecting by remember { mutableStateOf(false) }
    var connectionBannerDismissed by remember { mutableStateOf(false) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    var pendingSaveAppId by remember { mutableStateOf<String?>(null) }

    var showContainerConfigDialog by remember { mutableStateOf(false) }
    var configDialogAppId by remember { mutableStateOf("") }

    val onNavigateToWorkshop: (Int) -> Unit = { appId ->
        navController.navigate(PluviaScreen.Workshop.route(appId))
    }

    val onProcessPendingLaunch: (String) -> Unit = { reason ->
        MainActivity.consumePendingLaunchRequest()?.let { launchRequest ->
            Timber.tag("IntentLaunch")
                .i("Processing pending launch for ${launchRequest.appId} ($reason)")
            when (val resolution = resolveGameAppId(context, launchRequest.appId)) {
                is GameResolutionResult.NotFound -> {
                    val appName = ContainerUtils.resolveGameName(resolution.originalAppId)
                    Timber.tag("IntentLaunch").w("Game not installed: $appName (${launchRequest.appId})")
                    msgDialogState = MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = context.getString(R.string.game_not_installed_title),
                        message = context.getString(R.string.game_not_installed_message, appName),
                        dismissBtnText = context.getString(R.string.ok),
                    )
                }

                is GameResolutionResult.Success -> {
                    if (launchRequest.containerConfig != null) {
                        IntentLaunchManager.applyTemporaryConfigOverride(
                            context, launchRequest.appId, launchRequest.containerConfig,
                        )
                    }
                    val homeRoute = PluviaScreen.Home.route + "?offline={offline}"
                    if (navController.currentDestination?.route != homeRoute) {
                        navController.navigate(PluviaScreen.Home.route + "?offline=false") {
                            popUpTo(navController.graph.startDestinationId) { saveState = false }
                        }
                    }
                    MainActivity.wasLaunchedViaExternalIntent = true
                    trackGameLaunched(resolution.finalAppId)
                    viewModel.setLaunchedAppId(resolution.finalAppId)
                    viewModel.setBootToContainer(false)
                    preLaunchApp(
                        context = context,
                        appId = resolution.finalAppId,
                        useTemporaryOverride = launchRequest.containerConfig != null,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = ::updateMessageDialogState,
                        onSuccess = viewModel::launchApp,
                        isOffline = isOfflineState,
                        getLocalWorkshopItems = viewModel::getLocalWorkshopItems,
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                MainUiEvent.LaunchApp -> {
                    navController.navigate(PluviaScreen.XServer.route)
                }

                is MainUiEvent.ExternalGameLaunch -> {
                    if (needsDeferLaunch(context, event.appId)) {
                        MainActivity.setPendingLaunchRequest(
                            IntentLaunchManager.LaunchRequest(
                                appId = event.appId,
                                containerConfig = IntentLaunchManager.getTemporaryOverride(event.appId),
                            )
                        )
                        pendingLaunchGeneration++
                        shownPendingLaunchSnackbar = showDeferredLaunchSnackbar(context, event.appId)
                        return@collect
                    }

                    when (val resolution = resolveGameAppId(context, event.appId)) {
                        is GameResolutionResult.Success -> {
                            MainActivity.wasLaunchedViaExternalIntent = true
                            trackGameLaunched(resolution.finalAppId)
                            viewModel.setLaunchedAppId(resolution.finalAppId)
                            viewModel.setBootToContainer(false)
                            preLaunchApp(
                                context = context,
                                appId = resolution.finalAppId,
                                useTemporaryOverride = IntentLaunchManager.hasTemporaryOverride(resolution.finalAppId),
                                setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                setLoadingProgress = viewModel::setLoadingDialogProgress,
                                setLoadingMessage = viewModel::setLoadingDialogMessage,
                                setMessageDialogState = ::updateMessageDialogState,
                                onSuccess = viewModel::launchApp,
                                getLocalWorkshopItems = viewModel::getLocalWorkshopItems,
                            )
                        }

                        is GameResolutionResult.NotFound -> {
                            val appName = ContainerUtils.resolveGameName(resolution.originalAppId)
                            msgDialogState = MessageDialogState(
                                visible = true,
                                type = DialogType.SYNC_FAIL,
                                title = context.getString(R.string.game_not_installed_title),
                                message = context.getString(R.string.game_not_installed_message, appName),
                                dismissBtnText = context.getString(R.string.ok),
                            )
                        }
                    }
                }

                MainUiEvent.OnBackPressed -> {
                    if (SteamService.keepAlive){
                        gameBackAction?.invoke() ?: run { navController.popBackStack() }
                    } else if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                }

                MainUiEvent.OnLoggedOut -> {
                    viewModel.clearPersistedRoute()
                    navController.popBackStack(
                        route = PluviaScreen.LoginUser.route,
                        inclusive = false,
                        saveState = false,
                    )
                }

                is MainUiEvent.OnLogonEnded -> {
                    when (event.result) {
                        LoginResult.Success -> {
                            val pending = MainActivity.peekPendingLaunchRequest()
                            if (pending != null && !needsDeferLaunch(context, pending.appId)) {
                                onProcessPendingLaunch("user is now logged in")
                            } else if (pending == null && PluviaApp.xEnvironment == null) {
                                val currentRoute = navController.currentDestination?.route
                                val targetRoute = viewModel.getPersistedRoute() ?: PluviaScreen.Home.route
                                if (currentRoute == PluviaScreen.LoginUser.route) {
                                    navController.navigateFromLoginIfNeeded(targetRoute, "LogonEnded")
                                } else if (currentRoute == PluviaScreen.Home.route + "?offline={offline}") {
                                    val isCurrentlyOffline = navController.currentBackStackEntry
                                        ?.arguments?.getBoolean("offline") ?: false
                                    if (isCurrentlyOffline) {
                                        navController.navigate(PluviaScreen.Home.route + "?offline=false") {
                                            popUpTo(PluviaScreen.Home.route + "?offline={offline}") {
                                                inclusive = true
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        LoginResult.Failed -> {
                            consumePendingSteamLoginError(context)
                        }

                        else -> { }
                    }
                }

                is MainUiEvent.SteamDisconnected -> {
                    if (event.isTerminal) {
                        shownPendingLaunchSnackbar = false
                        consumePendingSteamLoginError(context)
                    } else if (!shownPendingLaunchSnackbar) {
                        val appId = MainActivity.peekPendingLaunchRequest()?.appId
                        if (appId != null && needsDeferLaunch(context, appId)) {
                            shownPendingLaunchSnackbar = true
                            SnackbarManager.show(context.getString(R.string.intent_launch_steam_pending))
                        }
                    }
                }

                MainUiEvent.ServiceReady -> {
                    val pending = MainActivity.peekPendingLaunchRequest()
                    if (pending != null && !needsDeferLaunch(context, pending.appId)) {
                        onProcessPendingLaunch("service now ready")
                    }
                }

                MainUiEvent.ShowDiscordSupportDialog -> {
                    msgDialogState = MessageDialogState(
                        visible = true,
                        type = DialogType.DISCORD,
                        title = context.getString(R.string.main_discord_support_title),
                        message = context.getString(R.string.main_discord_support_message),
                        confirmBtnText = context.getString(R.string.main_open_discord),
                        dismissBtnText = context.getString(R.string.close),
                    )
                }

                is MainUiEvent.ShowGameFeedbackDialog -> {
                    gameFeedbackState = GameFeedbackDialogState(
                        visible = true,
                        appId = event.appId,
                    )
                }

                is MainUiEvent.NavigateToWorkshop -> {
                    onNavigateToWorkshop(event.appId)
                }
            }
        }
    }

    LaunchedEffect(navController) {
        if (!state.hasLaunched) {
            viewModel.setHasLaunched(true)
            PluviaApp.onDestinationChangedListener = NavController.OnDestinationChangedListener { _, destination, _ ->
                viewModel.setCurrentScreen(destination.route)
            }
            PluviaApp.events.emit(AndroidEvent.StartOrientator)
        } else {
            PluviaApp.onDestinationChangedListener?.let {
                navController.removeOnDestinationChangedListener(it)
            }
        }
        PluviaApp.onDestinationChangedListener?.let {
            navController.addOnDestinationChangedListener(it)
        }
    }

    LaunchedEffect(state.currentScreen) {
        if (state.resettedScreen != state.currentScreen) {
            viewModel.setScreen()
            if (state.currentScreen != PluviaScreen.XServer) {
                val shouldShowStatusBar = !PrefManager.hideStatusBarWhenNotInGame
                PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(shouldShowStatusBar))
                PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(EnumSet.of(Orientation.UNSPECIFIED)))
            }
            hasBack = navController.previousBackStackEntry?.destination?.route != null
        }
    }

    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val shouldAttemptReconnect = !state.isSteamConnected && !isConnecting && !SteamService.keepAlive
            if (shouldAttemptReconnect) {
                isConnecting = true
                viewModel.startConnecting()
                context.startForegroundService(Intent(context, SteamService::class.java))
            }
            if (GOGService.hasStoredCredentials(context) && !GOGService.isRunning) {
                GOGService.start(context)
            }
            if (EpicService.hasStoredCredentials(context) && !EpicService.isRunning) {
                EpicService.start(context)
            }
            if (AmazonService.hasStoredCredentials(context) && !AmazonService.isRunning) {
                AmazonService.start(context)
            }
            if (PlatformAuthUtils.isSignedInToAnyPlatform(context) && !SteamService.keepAlive) {
                val baseRoute = viewModel.getPersistedRoute() ?: PluviaScreen.Home.route
                val targetRoute = if (SteamService.isLoggedIn) baseRoute else {
                    if (baseRoute.startsWith(PluviaScreen.Home.route)) PluviaScreen.Home.route + "?offline=true" else baseRoute
                }
                navController.navigateFromLoginIfNeeded(targetRoute, "ResumeSession")
            }
        }
    }

    DisposableEffect(Unit) {
        val promptListener: (AndroidEvent.PromptSaveContainerConfig) -> Unit = { event ->
            pendingSaveAppId = event.appId
            msgDialogState = MessageDialogState(
                visible = true,
                type = DialogType.SAVE_CONTAINER_CONFIG,
                title = context.getString(R.string.save_container_settings_title),
                message = context.getString(R.string.save_container_settings_message),
                confirmBtnText = context.getString(R.string.save),
                dismissBtnText = context.getString(R.string.discard),
            )
        }
        PluviaApp.events.on<AndroidEvent.PromptSaveContainerConfig, Unit>(promptListener)
        onDispose {
            PluviaApp.events.off<AndroidEvent.PromptSaveContainerConfig, Unit>(promptListener)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    PluviaTheme(
        isDark = when (state.appTheme) {
            AppTheme.AUTO -> isSystemInDarkTheme()
            AppTheme.DAY -> false
            AppTheme.NIGHT, AppTheme.AMOLED -> true
        },
        isAmoled = (state.appTheme == AppTheme.AMOLED),
        style = state.paletteStyle,
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                NavHost(
                    navController = navController,
                    startDestination = if (PlatformAuthUtils.isSignedInToAnyPlatform(context)) {
                        PluviaScreen.Home.route + "?offline=false"
                    } else {
                        PluviaScreen.LoginUser.route
                    },
                ) {
                    composable(route = PluviaScreen.LoginUser.route) {
                        UserLoginScreen(
                            connectionState = state.connectionState,
                            onRetryConnection = viewModel::retryConnection,
                            onContinueOffline = {
                                navController.navigate(PluviaScreen.Home.route + "?offline=true")
                            },
                            onPlatformSignedIn = {
                                onProcessPendingLaunch("signed in")
                            },
                        )
                    }
                    composable(
                        route = PluviaScreen.Home.route + "?offline={offline}",
                        arguments = listOf(
                            navArgument("offline") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ),
                    ) { backStackEntry ->
                        val isOfflineMode = backStackEntry.arguments?.getBoolean("offline") ?: false
                        LaunchedEffect(isOfflineMode) {
                            viewModel.setOffline(isOfflineMode)
                        }

                        HomeScreen(
                            onNavigateToWorkshop = onNavigateToWorkshop,
                            onClickPlay = { appId, bootToContainer ->
                                trackGameLaunched(appId)
                                viewModel.setLaunchedAppId(appId)
                                viewModel.setBootToContainer(bootToContainer)
                                viewModel.setOffline(isOfflineMode)
                                preLaunchApp(
                                    context = context,
                                    appId = appId,
                                    bootToContainer = bootToContainer,
                                    setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                    setLoadingProgress = viewModel::setLoadingDialogProgress,
                                    setLoadingMessage = viewModel::setLoadingDialogMessage,
                                    setMessageDialogState = ::updateMessageDialogState,
                                    onSuccess = viewModel::launchApp,
                                    isOffline = isOfflineMode,
                                    getLocalWorkshopItems = viewModel::getLocalWorkshopItems,
                                )
                            },
                            onTestGraphics = { appId ->
                                viewModel.setLaunchedAppId(appId)
                                viewModel.setBootToContainer(true)
                                viewModel.setTestGraphics(true)
                                viewModel.setOffline(isOfflineMode)
                                preLaunchApp(
                                    context = context,
                                    appId = appId,
                                    bootToContainer = true,
                                    setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                    setLoadingProgress = viewModel::setLoadingDialogProgress,
                                    setLoadingMessage = viewModel::setLoadingDialogMessage,
                                    setMessageDialogState = ::updateMessageDialogState,
                                    onSuccess = viewModel::launchApp,
                                    isOffline = isOfflineMode,
                                    getLocalWorkshopItems = viewModel::getLocalWorkshopItems,
                                )
                            },
                            onEditContainer = { appId ->
                                configDialogAppId = appId
                                showContainerConfigDialog = true
                            },
                            onClickExit = { PluviaApp.events.emit(AndroidEvent.EndProcess) },
                            onChat = { },
                            onNavigateRoute = { navController.navigate(it) },
                            onLogout = { SteamService.logOut() },
                            onGoOnline = {
                                navController.navigate(
                                    if (!SteamService.isLoggedIn) PluviaScreen.LoginUser.route
                                    else PluviaScreen.Home.route
                                )
                            },
                            isOffline = isOfflineMode,
                        )
                    }

                    composable(
                        route = PluviaScreen.Workshop.route,
                        arguments = listOf(
                            navArgument(PluviaScreen.Workshop.ARG_APP_ID) { type = NavType.IntType },
                        ),
                    ) {
                        WorkshopScreen(onBack = { navController.popBackStack() })
                    }

                    composable(route = PluviaScreen.XServer.route) {
                        XServerScreen(
                            appId = state.launchedAppId,
                            bootToContainer = state.bootToContainer,
                            testGraphics = state.testGraphics,
                            registerBackAction = { cb -> gameBackAction = cb },
                            navigateBack = {
                                scope.launch {
                                    val currentRoute = navController.currentBackStackEntry?.destination?.route
                                    if (currentRoute == PluviaScreen.XServer.route) {
                                        if (MainActivity.wasLaunchedViaExternalIntent) {
                                            MainActivity.wasLaunchedViaExternalIntent = false
                                            (context as? android.app.Activity)?.finish()
                                        } else {
                                            navController.popBackStack()
                                        }
                                    }
                                }
                            },
                            onWindowMapped = { ctx, win -> viewModel.onWindowMapped(ctx, win, state.launchedAppId) },
                            onExit = { onComplete -> viewModel.exitSteamApp(context, state.launchedAppId, onComplete) },
                            onGameLaunchError = { error -> viewModel.onGameLaunchError(error) },
                        )
                    }

                    composable(route = PluviaScreen.Settings.route) {
                        SettingsScreen(
                            appTheme = state.appTheme,
                            paletteStyle = state.paletteStyle,
                            onAppTheme = viewModel::setTheme,
                            onPaletteStyle = viewModel::setPalette,
                            onBack = { navController.navigateUp() },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = state.showBootingSplash,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    BootingSplash(
                        visible = true,
                        text = state.bootingSplashText,
                    )
                }

                GlobalDialogs(
                    state = state,
                    msgDialogState = msgDialogState,
                    setMessageDialogState = ::updateMessageDialogState,
                    showFeedbackDialog = false,
                    setShowFeedbackDialog = { },
                    feedbackAppId = "",
                    showUpdateDialog = false,
                    setShowUpdateDialog = { },
                    updateInfo = null,
                    showContainerConfigDialog = showContainerConfigDialog,
                    setShowContainerConfigDialog = { showContainerConfigDialog = it },
                    configDialogAppId = configDialogAppId,
                    viewModel = viewModel,
                    pendingSaveAppId = pendingSaveAppId,
                    setPendingSaveAppId = { pendingSaveAppId = it },
                    isOffline = isOfflineState,
                )

                AchievementOverlay()
            }
        }
    }
}

@Composable
private fun GlobalDialogs(
    state: app.gamenative.ui.data.MainState,
    msgDialogState: MessageDialogState,
    setMessageDialogState: (MessageDialogState) -> Unit,
    showFeedbackDialog: Boolean,
    setShowFeedbackDialog: (Boolean) -> Unit,
    feedbackAppId: String,
    showUpdateDialog: Boolean,
    setShowUpdateDialog: (Boolean) -> Unit,
    updateInfo: UpdateInfo?,
    showContainerConfigDialog: Boolean,
    setShowContainerConfigDialog: (Boolean) -> Unit,
    configDialogAppId: String,
    viewModel: MainViewModel,
    pendingSaveAppId: String?,
    setPendingSaveAppId: (String?) -> Unit,
    isOffline: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LoadingDialog(
        visible = state.loadingDialogVisible,
        progress = state.loadingDialogProgress,
        message = state.loadingDialogMessage,
    )

    if (msgDialogState.visible) {
        var onConfirmClick: (() -> Unit)? = null
        var onDismissClick: (() -> Unit)? = { setMessageDialogState(MessageDialogState(false)) }
        var onDismissRequest: (() -> Unit)? = { setMessageDialogState(MessageDialogState(false)) }

        when (msgDialogState.type) {
            DialogType.SAVE_CONTAINER_CONFIG -> {
                onConfirmClick = {
                    pendingSaveAppId?.let { appId ->
                        IntentLaunchManager.persistConfigurationOverride(context, appId)
                        IntentLaunchManager.clearTemporaryOverride(appId)
                    }
                    setPendingSaveAppId(null)
                    setMessageDialogState(MessageDialogState(false))
                }
                onDismissClick = {
                    pendingSaveAppId?.let { appId ->
                        IntentLaunchManager.restoreOriginalConfiguration(context, appId)
                        IntentLaunchManager.clearTemporaryOverride(appId)
                    }
                    setPendingSaveAppId(null)
                    setMessageDialogState(MessageDialogState(false))
                }
            }
            DialogType.SYNC_CONFLICT -> {
                onConfirmClick = {
                    setMessageDialogState(MessageDialogState(false))
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        preferredSave = SaveLocation.Remote,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = setMessageDialogState,
                        onSuccess = viewModel::launchApp,
                        isOffline = isOffline,
                        bootToContainer = state.bootToContainer,
                        getLocalWorkshopItems = viewModel::getLocalWorkshopItems,
                    )
                }
                onDismissClick = {
                    setMessageDialogState(MessageDialogState(false))
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        preferredSave = SaveLocation.Local,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = setMessageDialogState,
                        onSuccess = viewModel::launchApp,
                        isOffline = isOffline,
                        bootToContainer = state.bootToContainer,
                        getLocalWorkshopItems = viewModel::getLocalWorkshopItems,
                    )
                }
            }
            DialogType.APP_SESSION_ACTIVE -> {
                onConfirmClick = {
                    setMessageDialogState(MessageDialogState(false))
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        ignorePendingOperations = true,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = setMessageDialogState,
                        onSuccess = viewModel::launchApp,
                        getLocalWorkshopItems = viewModel::getLocalWorkshopItems,
                    )
                }
            }
            DialogType.DISCORD -> {
                onConfirmClick = {
                    setMessageDialogState(MessageDialogState(false))
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Constants.DISCORD_URL)))
                }
            }
            else -> { }
        }

        MessageDialog(
            visible = msgDialogState.visible,
            title = msgDialogState.title,
            message = msgDialogState.message,
            confirmBtnText = msgDialogState.confirmBtnText,
            dismissBtnText = msgDialogState.dismissBtnText,
            onConfirmClick = onConfirmClick,
            onDismissClick = onDismissClick,
            onDismissRequest = { setMessageDialogState(MessageDialogState(false)) },
        )
    }

    if (showContainerConfigDialog) {
        var containerConfigForDialog by remember(configDialogAppId) { mutableStateOf<ContainerData?>(null) }
        LaunchedEffect(configDialogAppId) {
            containerConfigForDialog = withContext(Dispatchers.IO) {
                val container = ContainerUtils.getOrCreateContainer(context, configDialogAppId)
                ContainerUtils.toContainerData(container)
            }
        }
        containerConfigForDialog?.let { config ->
            ContainerConfigDialog(
                visible = true,
                title = context.getString(R.string.container_config_title),
                initialConfig = config,
                onDismissRequest = { setShowContainerConfigDialog(false) },
                onSave = { newConfig ->
                    scope.launch {
                        withContext(Dispatchers.IO) { ContainerUtils.applyToContainer(context, configDialogAppId, newConfig) }
                        setShowContainerConfigDialog(false)
                    }
                },
                onDeleteWorkshopMods = {
                    scope.launch(Dispatchers.IO) {
                        val container = ContainerUtils.getContainer(context, configDialogAppId)
                        val drives = container.drives
                        val gameRootDir = ContainerUtils.getADrivePath(drives)?.let { java.io.File(it) }
                        val gameName = ContainerUtils.resolveGameName(configDialogAppId)

                        WorkshopManager.deleteWorkshopMods(
                            context = context,
                            containerId = configDialogAppId,
                            gameRootDir = gameRootDir,
                            gameName = gameName,
                        )

                        withContext(Dispatchers.Main) {
                            app.gamenative.ui.util.SnackbarManager.show(context.getString(R.string.workshop_mods_deleted))
                        }
                    }
                },
            )
        }
    }
}

fun preLaunchApp(
    context: Context,
    appId: String,
    ignorePendingOperations: Boolean = false,
    preferredSave: SaveLocation = SaveLocation.None,
    useTemporaryOverride: Boolean = false,
    skipCloudSync: Boolean = false,
    setLoadingDialogVisible: (Boolean) -> Unit,
    setLoadingProgress: (Float) -> Unit,
    setLoadingMessage: (String) -> Unit,
    setMessageDialogState: (MessageDialogState) -> Unit,
    onSuccess: KFunction2<Context, String, Unit>,
    retryCount: Int = 0,
    isOffline: Boolean = false,
    bootToContainer: Boolean = false,
    getLocalWorkshopItems: ((Int) -> List<WorkshopItem>)? = null,
) {
    setLoadingDialogVisible(true)
    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)

    CoroutineScope(Dispatchers.IO).launch {
        val container = if (useTemporaryOverride) {
            ContainerUtils.getOrCreateContainerWithOverride(context, appId)
        } else {
            ContainerUtils.getOrCreateContainer(context, appId)
        }

        container.clearSessionMetadata()
        val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
        val isLocalSavesOnly = ContainerUtils.isLocalSavesOnly(context, appId)

        if (!bootToContainer) {
            val effectiveExe = when (gameSource) {
                GameSource.STEAM -> SteamService.getLaunchExecutable(appId, container)
                GameSource.GOG -> GOGService.getLaunchExecutable(appId, container)
                GameSource.EPIC -> EpicService.getLaunchExecutable(appId)
                GameSource.CUSTOM_GAME -> CustomGameScanner.getLaunchExecutable(container)
                GameSource.AMAZON -> AmazonService.getLaunchExecutable(appId)
            }
            if (effectiveExe.isBlank()) {
                setLoadingDialogVisible(false)
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.EXECUTABLE_NOT_FOUND,
                        title = context.getString(R.string.game_executable_not_found_title),
                        message = context.getString(R.string.game_executable_not_found),
                        dismissBtnText = context.getString(R.string.ok),
                        actionBtnText = AppOptionMenuType.EditContainer.text,
                    ),
                )
                return@launch
            }
        }

        if (gameSource == GameSource.STEAM) {
            try {
                val configJson = Json.parseToJsonElement(container.containerJson).jsonObject
                val missingRequests = BestConfigService.resolveMissingManifestInstallRequests(
                    context, configJson, "exact_gpu_match",
                )
                for (request in missingRequests) {
                    setLoadingMessage(context.getString(R.string.main_downloading_entry, request.entry.name))
                    ManifestInstaller.installManifestEntry(
                        context, request.entry, request.isDriver, request.contentType,
                    ) { progress -> setLoadingProgress(progress.coerceIn(0f, 1f)) }
                }
            } catch (e: Exception) {
                setLoadingDialogVisible(false)
                return@launch
            }
        }

        SplitCompat.install(context)
        try {
            if (!SteamService.isImageFsInstallable(context, container.containerVariant)) {
                setLoadingMessage("Downloading first-time files")
                SteamService.downloadImageFs(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    variant = container.containerVariant,
                    context = context,
                ).await()
            }
            if (container.containerVariant == Container.GLIBC &&
                !SteamService.isFileInstallable(context, "imagefs_patches_gamenative.tzst")
            ) {
                setLoadingMessage("Downloading Wine")
                SteamService.downloadImageFsPatches(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    context = context,
                ).await()
            } else if (container.wineVersion.contains("proton-9.0-arm64ec") &&
                !SteamService.isFileInstallable(context, "proton-9.0-arm64ec.txz")
            ) {
                setLoadingMessage("Downloading arm64ec Proton")
                SteamService.downloadFile(
                    { setLoadingProgress(it / 1.0f) },
                    this,
                    context,
                    "proton-9.0-arm64ec.txz"
                ).await()
            }
        } catch (e: Exception) {
            setLoadingDialogVisible(false)
            return@launch
        }

        if (gameSource == GameSource.STEAM && !isOffline) {
            try {
                val winePrefix = container.rootDir.absolutePath + "/.wine"
                val workshopContentDir = WorkshopManager.getWorkshopContentDir(winePrefix, gameId)
                val gameRootDir = File(SteamService.getAppDirPath(gameId))
                val gameName = SteamService.getAppInfoOf(gameId)?.name ?: ""
                
                if (container.isWorkshopMods) {
                    setLoadingMessage("Syncing Workshop mods...")
                    val localItems = getLocalWorkshopItems?.invoke(gameId) ?: emptyList()
                    val subscriptions = localItems.map { it.toSubscription() }

                    WorkshopManager.configureModSymlinks(
                        gameRootDir = gameRootDir,
                        workshopContentDir = workshopContentDir,
                        items = subscriptions,
                        winePrefix = winePrefix,
                        gameName = gameName
                    )
                } else if (workshopContentDir.exists()) {
                    // Only clean up if mods were previously synced
                    setLoadingMessage("Cleaning up Workshop mods...")
                    WorkshopManager.cleanupModSymlinks(
                        gameRootDir = gameRootDir,
                        workshopContentDir = workshopContentDir,
                        winePrefix = winePrefix,
                        gameName = gameName
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Workshop sync failed")
            }
        }

        if (!bootToContainer && !isOffline && !isLocalSavesOnly && !skipCloudSync) {
            setLoadingMessage("Checking cloud saves...")
            val syncResult = SteamService.beginLaunchApp(
                appId = gameId,
                parentScope = this,
                ignorePendingOperations = ignorePendingOperations,
                preferredSave = preferredSave,
                prefixToPath = { prefix ->
                    PathType.from(prefix).toAbsPath(context, gameId, SteamService.userSteamId!!.accountID)
                },
                isOffline = isOffline,
                onProgress = { message, progress ->
                    setLoadingMessage(message)
                    setLoadingProgress(progress)
                }
            ).await()

            if (syncResult.syncResult != SyncResult.Success && syncResult.syncResult != SyncResult.UpToDate) {
                withContext(Dispatchers.Main) {
                    setLoadingDialogVisible(false)
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = when (syncResult.syncResult) {
                                SyncResult.Conflict -> DialogType.SYNC_CONFLICT
                                SyncResult.PendingOperations -> DialogType.PENDING_UPLOAD
                                SyncResult.AppSessionActive -> DialogType.APP_SESSION_ACTIVE
                                else -> DialogType.SYNC_FAIL
                            },
                            title = context.getString(R.string.steam_cloud_sync_failed, syncResult.syncResult.toString()),
                            message = syncResult.syncResult.toString(),
                            confirmBtnText = context.getString(R.string.proceed),
                            dismissBtnText = context.getString(R.string.cancel),
                        )
                    )
                }
                return@launch
            }
        }

        withContext(Dispatchers.Main) {
            setLoadingDialogVisible(false)
            onSuccess(context, appId)
        }
    }
}
