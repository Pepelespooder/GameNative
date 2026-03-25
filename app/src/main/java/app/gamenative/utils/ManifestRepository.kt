package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

object ManifestRepository {
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/utkarshdalal/GameNative/refs/heads/master/manifest.json"
    private val json = Json { ignoreUnknownKeys = true }

    // Short-timeout client so a slow GitHub fetch never blocks the launch flow.
    private val manifestClient: OkHttpClient by lazy {
        Net.http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun loadManifest(context: Context): ManifestData {
        val cachedJson = PrefManager.componentManifestJson
        val cachedManifest = parseManifest(cachedJson) ?: ManifestData.empty()
        val lastFetchedAt = PrefManager.componentManifestFetchedAt
        val isStale = System.currentTimeMillis() - lastFetchedAt >= ONE_DAY_MS

        // Always return cached data immediately so the launch flow is never blocked.
        // If the cache is fresh, we're done.
        if (cachedJson.isNotEmpty() && !isStale) {
            return cachedManifest
        }

        // Cache is missing or stale. If we have something cached, return it now and
        // refresh in the background so this launch isn't delayed.
        if (cachedJson.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch { refreshManifest() }
            return cachedManifest
        }

        // No cache at all — fetch synchronously (with short timeout) so we have
        // something for this launch. Still won't hang more than ~15s.
        val fetched = fetchManifestJson()
        if (fetched != null) {
            val parsed = parseManifest(fetched)
            if (parsed != null) {
                PrefManager.componentManifestJson = fetched
                PrefManager.componentManifestFetchedAt = System.currentTimeMillis()
                return parsed
            }
        }

        return cachedManifest
    }

    private suspend fun refreshManifest() {
        val fetched = fetchManifestJson() ?: return
        val parsed = parseManifest(fetched) ?: return
        PrefManager.componentManifestJson = fetched
        PrefManager.componentManifestFetchedAt = System.currentTimeMillis()
        Timber.d("ManifestRepository: refreshed in background")
    }

    private suspend fun fetchManifestJson(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(MANIFEST_URL).build()
            manifestClient.newCall(request).execute().use { response ->
                response.takeIf { it.isSuccessful }?.body?.string()
            }
        } catch (e: Exception) {
            Timber.e(e, "ManifestRepository: fetch failed")
            null
        }
    }

    fun parseManifest(jsonString: String?): ManifestData? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            json.decodeFromString<ManifestData>(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "ManifestRepository: parse failed")
            null
        }
    }
}
