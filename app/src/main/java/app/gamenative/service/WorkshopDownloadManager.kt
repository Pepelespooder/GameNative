package app.gamenative.service

import app.gamenative.data.WorkshopDownloadProgress
import app.gamenative.data.WorkshopItem
import app.gamenative.data.WorkshopItemState
import app.gamenative.db.dao.WorkshopItemDao
import app.gamenative.utils.WorkshopEnvironmentSetup
import app.gamenative.workshop.WorkshopManager
import app.gamenative.workshop.WorkshopItemSubscription
import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import com.winlator.container.Container
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetItemInfo_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_Subscribe_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_Unsubscribe_Request
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class WorkshopDownloadManager @Inject constructor(
    private val workshopItemDao: WorkshopItemDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadProgress = MutableStateFlow<Map<Long, WorkshopDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<Long, WorkshopDownloadProgress>> = _downloadProgress.asStateFlow()

    fun subscribe(container: Container, appId: Int, publishedFileId: Long) {
        scope.launch {
            try {
                val publishedFile = getPublishedFileRpc() ?: return@launch

                val subReq = CPublishedFile_Subscribe_Request.newBuilder()
                    .setAppid(appId)
                    .setPublishedfileid(publishedFileId)
                    .build()
                publishedFile.subscribe(subReq).await()

                val detailReq = CPublishedFile_GetDetails_Request.newBuilder()
                    .addPublishedfileids(publishedFileId)
                    .build()
                val detailResult = publishedFile.getDetails(detailReq).await()
                val detail = detailResult.body.getPublishedfiledetailsList().firstOrNull()
                val ugcHandle = detail?.hcontentFile ?: 0L
                val manifestId = ugcHandle
                val sizeBytes = detail?.fileSize ?: 0L
                val timeUpdated = detail?.timeUpdated?.toLong() ?: 0L
                val title = detail?.title ?: ""
                val previewUrl = detail?.previewUrl ?: ""
                val fileUrl = detail?.fileUrl ?: ""
                val fileName = detail?.filename ?: ""

                workshopItemDao.insert(
                    WorkshopItem(
                        publishedFileId = publishedFileId,
                        appId = appId,
                        title = title,
                        previewUrl = previewUrl,
                        ugcHandle = ugcHandle,
                        sizeBytes = sizeBytes,
                        timeUpdated = timeUpdated,
                        state = WorkshopItemState.DOWNLOADING,
                        fileUrl = fileUrl,
                        fileName = fileName,
                    )
                )

                _downloadProgress.update { it + (publishedFileId to WorkshopDownloadProgress(0L, sizeBytes, "Starting")) }

                val winePrefix = container.rootDir.absolutePath + "/.wine"
                val workshopContentDir = WorkshopManager.getWorkshopContentDir(winePrefix, appId)
                val steamClient = SteamService.instance?.steamClient ?: run {
                    workshopItemDao.updateState(publishedFileId, WorkshopItemState.FAILED)
                    _downloadProgress.update { it - publishedFileId }
                    return@launch
                }
                val licenses = SteamService.licenses
                val workshopItem = WorkshopItemSubscription(
                    publishedFileId = publishedFileId,
                    appId = appId,
                    title = title,
                    fileSizeBytes = sizeBytes,
                    manifestId = manifestId,
                    timeUpdated = timeUpdated,
                    fileUrl = fileUrl,
                    fileName = fileName,
                )

                val successCount = WorkshopManager.downloadItems(
                    items = listOf(workshopItem),
                    steamClient = steamClient,
                    licenses = licenses,
                    workshopContentDir = workshopContentDir,
                    onItemProgress = { _, _, _ -> },
                    onBytesProgress = { downloaded, total ->
                        _downloadProgress.update {
                            it + (publishedFileId to WorkshopDownloadProgress(downloaded, total, "Downloading"))
                        }
                    },
                    onOverallProgress = {},
                )

                if (successCount > 0) {
                    workshopItemDao.updateManifest(publishedFileId, manifestId, timeUpdated, WorkshopItemState.INSTALLED)
                    val allInstalled = workshopItemDao.getByAppId(appId).first()
                        .filter { it.state == WorkshopItemState.INSTALLED }
                    WorkshopEnvironmentSetup.writeAcf(container, appId, allInstalled)
                    WorkshopEnvironmentSetup.setupRegistry(container)
                } else {
                    workshopItemDao.updateState(publishedFileId, WorkshopItemState.FAILED)
                }
                _downloadProgress.update { it - publishedFileId }
            } catch (e: Exception) {
                Timber.e(e, "WorkshopDownloadManager: subscribe failed")
                workshopItemDao.updateState(publishedFileId, WorkshopItemState.FAILED)
                _downloadProgress.update { it - publishedFileId }
            }
        }
    }

    fun unsubscribe(container: Container, appId: Int, publishedFileId: Long) {
        scope.launch {
            try {
                val publishedFile = getPublishedFileRpc()
                if (publishedFile != null) {
                    val req = CPublishedFile_Unsubscribe_Request.newBuilder()
                        .setAppid(appId)
                        .setPublishedfileid(publishedFileId)
                        .build()
                    publishedFile.unsubscribe(req).await()
                }

                val winePrefix = container.rootDir.absolutePath + "/.wine"
                WorkshopManager.getWorkshopContentDir(winePrefix, appId)
                    .resolve(publishedFileId.toString())
                    .deleteRecursively()

                val item = workshopItemDao.getById(publishedFileId)
                if (item != null) workshopItemDao.delete(item)

                val remaining = workshopItemDao.getByAppId(appId).first()
                    .filter { it.state == WorkshopItemState.INSTALLED }
                WorkshopEnvironmentSetup.writeAcf(container, appId, remaining)

                _downloadProgress.update { it - publishedFileId }
            } catch (e: Exception) {
                Timber.e(e, "WorkshopDownloadManager: unsubscribe failed")
            }
        }
    }

    suspend fun checkForUpdates(container: Container, appId: Int) {
        try {
            val publishedFile = getPublishedFileRpc() ?: return
            val items = workshopItemDao.getByAppId(appId).first()
                .filter { it.state == WorkshopItemState.INSTALLED || it.state == WorkshopItemState.UPDATE_AVAILABLE || it.state == WorkshopItemState.FAILED }
            if (items.isEmpty()) return

            // Reset UPDATE_AVAILABLE back to INSTALLED before re-checking so we get a fresh result
            items.filter { it.state == WorkshopItemState.UPDATE_AVAILABLE }.forEach {
                workshopItemDao.updateState(it.publishedFileId, WorkshopItemState.INSTALLED)
            }

            val reqBuilder = CPublishedFile_GetItemInfo_Request.newBuilder().setAppid(appId)
            items.forEach { item ->
                reqBuilder.addWorkshopItems(
                    CPublishedFile_GetItemInfo_Request.WorkshopItem.newBuilder()
                        .setPublishedFileId(item.publishedFileId)
                        .setTimeUpdated(0)
                        .build()
                )
            }
            val result = publishedFile.getItemInfo(reqBuilder.build()).await()
            val serverItems = result.body.getWorkshopItemsList()
            val localMap = items.associateBy { it.publishedFileId }

            serverItems.forEach { serverItem ->
                val local = localMap[serverItem.publishedFileId] ?: return@forEach
                if (serverItem.timeUpdated.toLong() > local.timeUpdated) {
                    workshopItemDao.updateState(serverItem.publishedFileId, WorkshopItemState.UPDATE_AVAILABLE)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "WorkshopDownloadManager: checkForUpdates failed")
        }
    }

    fun updateItem(container: Container, appId: Int, publishedFileId: Long) {
        scope.launch {
            try {
                val item = workshopItemDao.getById(publishedFileId) ?: return@launch
                workshopItemDao.updateState(publishedFileId, WorkshopItemState.DOWNLOADING)
                _downloadProgress.update { it + (publishedFileId to WorkshopDownloadProgress(0L, item.sizeBytes, "Updating")) }

                val winePrefix = container.rootDir.absolutePath + "/.wine"
                val workshopContentDir = WorkshopManager.getWorkshopContentDir(winePrefix, appId)
                val steamClient = SteamService.instance?.steamClient ?: run {
                    workshopItemDao.updateState(publishedFileId, WorkshopItemState.FAILED)
                    _downloadProgress.update { it - publishedFileId }
                    return@launch
                }
                val licenses = SteamService.licenses
                val workshopItem = WorkshopItemSubscription(
                    publishedFileId = publishedFileId,
                    appId = appId,
                    title = item.title,
                    fileSizeBytes = item.sizeBytes,
                    manifestId = item.manifestId,
                    timeUpdated = item.timeUpdated,
                    fileUrl = item.fileUrl,
                    fileName = item.fileName,
                )
                val successCount = WorkshopManager.downloadItems(
                    items = listOf(workshopItem),
                    steamClient = steamClient,
                    licenses = licenses,
                    workshopContentDir = workshopContentDir,
                    onItemProgress = { _, _, _ -> },
                    onBytesProgress = { downloaded, total ->
                        _downloadProgress.update {
                            it + (publishedFileId to WorkshopDownloadProgress(downloaded, total, "Updating"))
                        }
                    },
                    onOverallProgress = {},
                )
                if (successCount > 0) {
                    val now = System.currentTimeMillis() / 1000L
                    workshopItemDao.updateManifest(publishedFileId, item.manifestId, now, WorkshopItemState.INSTALLED)
                    val allInstalled = workshopItemDao.getByAppId(appId).first()
                        .filter { it.state == WorkshopItemState.INSTALLED }
                    WorkshopEnvironmentSetup.writeAcf(container, appId, allInstalled)
                } else {
                    workshopItemDao.updateState(publishedFileId, WorkshopItemState.FAILED)
                }
                _downloadProgress.update { it - publishedFileId }
            } catch (e: Exception) {
                Timber.e(e, "WorkshopDownloadManager: updateItem failed")
                workshopItemDao.updateState(publishedFileId, WorkshopItemState.FAILED)
                _downloadProgress.update { it - publishedFileId }
            }
        }
    }

    private fun getPublishedFileRpc(): PublishedFile? {
        val unifiedMessages = SteamService.instance?.steamClient
            ?.getHandler(SteamUnifiedMessages::class.java) ?: return null
        return unifiedMessages.createService(PublishedFile::class.java)
    }
}
