package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.WorkshopDownloadProgress
import app.gamenative.data.WorkshopItem
import app.gamenative.data.WorkshopQueryResult
import app.gamenative.db.dao.WorkshopItemDao
import app.gamenative.service.SteamService
import app.gamenative.service.WorkshopDownloadManager
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.PublishedFileDetails
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

enum class WorkshopSortType(val queryType: Int, val title: String) {
    TRENDING(3, "Trending"),
    MOST_SUBSCRIBED(9, "Most Popular"),
    TOP_RATED(0, "Top Rated"),
    MOST_RECENT(1, "Most Recent"),
    RELEVANCE(12, "Relevance"),
}

@HiltViewModel
class WorkshopViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val workshopDownloadManager: WorkshopDownloadManager,
    private val workshopItemDao: WorkshopItemDao,
) : ViewModel() {

    val appId: Int = checkNotNull(savedStateHandle[PluviaScreen.Workshop.ARG_APP_ID])

    val installedItems: Flow<List<WorkshopItem>> = workshopItemDao.getByAppId(appId)

    val downloadProgress: StateFlow<Map<Long, WorkshopDownloadProgress>>
        get() = workshopDownloadManager.downloadProgress

    private val _browseResults = MutableStateFlow<List<WorkshopQueryResult>>(emptyList())
    val browseResults: StateFlow<List<WorkshopQueryResult>> = _browseResults.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortType = MutableStateFlow(WorkshopSortType.TRENDING)
    val sortType: StateFlow<WorkshopSortType> = _sortType.asStateFlow()
    private var lastBrowseSortType = WorkshopSortType.TRENDING

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private var browseCurrentPage = 0
    private var isFetching = false

    init {
        loadNextPage()
    }

    fun search(query: String) {
        _searchQuery.value = query
        browseCurrentPage = 0
        _browseResults.value = emptyList()
        _sortType.value = if (query.isNotBlank()) WorkshopSortType.RELEVANCE else lastBrowseSortType
        loadNextPage()
    }

    fun setSortType(type: WorkshopSortType) {
        if (type == WorkshopSortType.RELEVANCE) return
        lastBrowseSortType = type
        _sortType.value = type
        browseCurrentPage = 0
        _browseResults.value = emptyList()
        loadNextPage()
    }

    fun loadNextPage() {
        if (isFetching) return
        isFetching = true
        val q = _searchQuery.value
        val st = _sortType.value
        viewModelScope.launch {
            fetchBrowsePage(q, st, ++browseCurrentPage)
            isFetching = false
        }
    }

    fun subscribe(publishedFileId: Long) {
        withContainer { workshopDownloadManager.subscribe(it, appId, publishedFileId) }
    }

    fun unsubscribe(publishedFileId: Long) {
        withContainer { workshopDownloadManager.unsubscribe(it, appId, publishedFileId) }
    }

    fun checkForUpdates() {
        if (_isCheckingForUpdates.value) return
        withContainer { container ->
            _isCheckingForUpdates.value = true
            viewModelScope.launch {
                workshopDownloadManager.checkForUpdates(container, appId)
                _isCheckingForUpdates.value = false
            }
        }
    }

    fun updateItem(publishedFileId: Long) {
        withContainer { workshopDownloadManager.updateItem(it, appId, publishedFileId) }
    }

    private fun withContainer(block: (Container) -> Unit) {
        val container = try {
            ContainerUtils.getContainer(context, "STEAM_$appId")
        } catch (e: Exception) {
            Timber.w(e, "WorkshopViewModel: container not found for appId=$appId")
            null
        }
        if (container != null) {
            block(container)
        } else {
            Timber.w("WorkshopViewModel: no container for appId=$appId")
        }
    }

    private suspend fun fetchBrowsePage(query: String, sortType: WorkshopSortType, page: Int) {
        try {
            val pf = getPublishedFileRpc() ?: return
            val reqBuilder = CPublishedFile_QueryFiles_Request.newBuilder()
                .setAppid(appId)
                .setQueryType(sortType.queryType)
                .setNumperpage(30)
                .setPage(page)
                .setReturnDetails(true)

            if (query.isNotBlank()) reqBuilder.setSearchText(query)

            val result = pf.queryFiles(reqBuilder.build()).await()
            val newItems = result.body.getPublishedfiledetailsList().map { it.toQueryResult() }
            _browseResults.update { current -> if (page == 1) newItems else current + newItems }
        } catch (e: Exception) {
            Timber.e(e, "WorkshopViewModel: search failed query=$query page=$page")
        }
    }

    private fun getPublishedFileRpc(): PublishedFile? {
        val unifiedMessages = SteamService.instance?.steamClient
            ?.getHandler(SteamUnifiedMessages::class.java) ?: return null
        return unifiedMessages.createService(PublishedFile::class.java)
    }
}

private fun PublishedFileDetails.toQueryResult(): WorkshopQueryResult = WorkshopQueryResult(
    publishedFileId = publishedfileid,
    title = title,
    previewUrl = previewUrl,
    subscriberCount = subscriptions,
    sizeBytes = fileSize,
    timeUpdated = timeUpdated.toLong(),
    description = fileDescription,
    favoritedCount = favorited,
    viewCount = views,
    timeCreated = timeCreated.toLong(),
    tags = tagsList.map { it.tag },
)
