package app.gamenative.ui.screen.library

import android.content.Context
import app.gamenative.data.WorkshopItem
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.ContainerUtils
import app.gamenative.data.GameSource
import app.gamenative.workshop.WorkshopItemSubscription
import app.gamenative.utils.CustomGameScanner

sealed class GameResolutionResult {
    data class Success(
        val finalAppId: String,
        val gameId: Int,
        val isSteamInstalled: Boolean,
        val isCustomGame: Boolean,
    ) : GameResolutionResult()

    data class NotFound(
        val gameId: Int,
        val originalAppId: String,
    ) : GameResolutionResult()
}

fun resolveGameAppId(context: Context, appId: String): GameResolutionResult {
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
    val isInstalled = when (gameSource) {
        GameSource.STEAM -> {
            if (SteamService.getAppInfoOf(gameId) != null) {
                SteamService.isAppInstalled(gameId)
            } else {
                ContainerUtils.hasContainer(context, appId)
            }
        }
        GameSource.GOG -> GOGService.isGameInstalled(gameId.toString())
        GameSource.EPIC -> EpicService.isGameInstalled(context, gameId)
        GameSource.AMAZON -> AmazonService.isGameInstalledByAppId(context, gameId)
        GameSource.CUSTOM_GAME -> CustomGameScanner.isGameInstalled(gameId)
    }

    if (!isInstalled) {
        return GameResolutionResult.NotFound(gameId = gameId, originalAppId = appId)
    }

    return GameResolutionResult.Success(
        finalAppId = appId,
        gameId = gameId,
        isSteamInstalled = gameSource == GameSource.STEAM,
        isCustomGame = gameSource == GameSource.CUSTOM_GAME,
    )
}

fun needsDeferLaunch(context: Context, appId: String): Boolean {
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    return when (gameSource) {
        GameSource.STEAM -> !SteamService.isConnected
        GameSource.GOG -> !GOGService.isRunning
        GameSource.EPIC -> !EpicService.isRunning
        GameSource.AMAZON -> !AmazonService.isRunning
        else -> false
    }
}

fun WorkshopItem.toSubscription(): WorkshopItemSubscription = WorkshopItemSubscription(
    publishedFileId = publishedFileId,
    appId = appId,
    title = title,
    fileSizeBytes = sizeBytes,
    manifestId = manifestId,
    timeUpdated = timeUpdated,
    fileUrl = fileUrl,
    fileName = fileName,
    previewUrl = previewUrl
)
