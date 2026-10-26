package dev.jigen.zimusic.discord

import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object DiscordExternalAssets {

    private const val TAG = "DiscordSvc"
    private const val EXTERNAL_ASSETS_API = "https://discord.com/api/v9/applications/%s/external-assets"

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, String>()
    private const val CACHE_MAX_SIZE = 128

    fun getCached(imageUrl: String?): String? {
        if (imageUrl.isNullOrBlank()) return null
        if (imageUrl.startsWith("mp:")) return imageUrl
        return cache[imageUrl]
    }

    suspend fun resolve(
        imageUrl: String,
        appId: String,
        token: String,
    ): String? {
        if (imageUrl.isBlank()) return null
        if (imageUrl.startsWith("mp:")) return imageUrl

        cache[imageUrl]?.let {
            DiscordLog.d(TAG, "resolve: cache hit for %s -> %s", imageUrl.take(60), it)
            return it
        }

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try {
            val url = URL(EXTERNAL_ASSETS_API.format(appId))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", token)
            conn.setRequestProperty("User-Agent", DiscordSuperProperties.USER_AGENT)
            conn.setRequestProperty("X-Super-Properties", DiscordSuperProperties.base64)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val bodyJson = "{\"urls\":[\"$imageUrl\"]}"
            conn.outputStream.use { os ->
                os.write(bodyJson.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()

            if (responseCode in 200..299 && responseBody.isNotBlank()) {
                val parsed = json.decodeFromString<List<ExternalAssetResponse>>(responseBody)
                val assetPath = parsed.firstOrNull()?.externalAssetPath
                if (assetPath != null) {
                    val result = "mp:$assetPath"
                    cache[imageUrl] = result
                    trimCache()
                    DiscordLog.i(TAG, "external-assets: resolved %s -> %s", imageUrl.take(60), result)
                    return@withContext result
                } else {
                    DiscordLog.w(TAG, "external-assets: JSON missing external_asset_path: $responseBody")
                }
            } else {
                DiscordLog.w(TAG, "external-assets: HTTP error code=$responseCode body=$responseBody")
            }
            null
} catch (e: Exception) { DiscordLog.e(TAG, "external-assets: failed for " + imageUrl.take(60), e); null } }
    }

    private fun trimCache() {
        if (cache.size > CACHE_MAX_SIZE) {
            val toRemove = cache.size - CACHE_MAX_SIZE
            cache.keys.take(toRemove).forEach { cache.remove(it) }
        }
    }

    fun clearCache() {
        cache.clear()
    }

    @kotlinx.serialization.Serializable
    private data class ExternalAssetResponse(
        val url: String? = null,
        @kotlinx.serialization.SerialName("external_asset_path")
        val externalAssetPath: String? = null,
    )
}



