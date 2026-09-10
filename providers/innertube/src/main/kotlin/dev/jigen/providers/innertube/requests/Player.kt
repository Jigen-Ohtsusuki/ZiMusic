package dev.jigen.providers.innertube.requests

import dev.jigen.providers.innertube.Innertube
import dev.jigen.providers.innertube.NewPipeUtils
import dev.jigen.providers.innertube.YouTube
import dev.jigen.providers.innertube.json
import dev.jigen.providers.innertube.models.Context
import dev.jigen.providers.innertube.models.PlayerResponse
import dev.jigen.providers.innertube.models.bodies.PlayerBody
import dev.jigen.providers.utils.runCatchingCancellable
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.util.generateNonce
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.Request

private val streamValidatorClient by lazy {
    YouTube.client.newBuilder()
        .build()
}

private val MAIN_CONTEXT = Context.DefaultVisionOS

private val FALLBACK_CONTEXTS = arrayOf(
    Context.DefaultVR,
    Context.YouTubeIOS,
    Context.DefaultWebNoLang,
    Context.OnlyWeb,
    Context.DefaultTV,
    Context.WebCreator,
    Context.YouTubeAndroid,
    Context.DefaultAndroid
)

private val PlayerResponse.isValid
    get() = playabilityStatus.status == "OK" &&
        (streamingData?.adaptiveFormats?.any { it.url != null || it.signatureCipher != null } == true ||
         streamingData?.formats?.any { it.url != null || it.signatureCipher != null } == true)

private val PlayerResponse.StreamingData.highestQualityFormat: PlayerResponse.StreamingData.Format?
    get() = (adaptiveFormats + formats.orEmpty())
        .filter { it.isAudio }
        .maxWithOrNull(
            compareBy<PlayerResponse.StreamingData.Format> { it.averageBitrate ?: it.bitrate ?: 0 }
                .thenBy { it.itag == 251 }
                .thenBy { it.mimeType.contains("webm") }
        )

private fun validateStreamUrl(url: String, userAgent: String?, clientName: String): Boolean {
    return try {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-1")
        userAgent?.let { requestBuilder.header("User-Agent", it) }
        val response = streamValidatorClient.newCall(requestBuilder.build()).execute()
        val isSuccess = response.isSuccessful
        if (!isSuccess) {
            System.err.println("Validation failed for $clientName with code ${response.code} and message ${response.message}")
        }
        isSuccess.also { response.close() }
    } catch (e: Exception) {
        System.err.println("Validation exception for $clientName: ${e.message}")
        false
    }
}

private suspend fun Innertube.getPlayerResponse(
    body: PlayerBody,
    context: Context
): Result<PlayerResponse> = runCatching {
    logger.info("Trying ${context.client.clientName} ${context.client.clientVersion} ${context.client.platform}")

    context.client.getConfiguration()
        ?: error("Failed to get configuration for client ${context.client.clientName}")

    val cpn = generateNonce(16).decodeToString()

    // Fixed: Applied withRetry to handle network flakes automatically
    val responseAsText = Innertube.withRetry {
        val httpResponse = client.post(if (context.client.music) PLAYER_MUSIC else PLAYER) {
            setBody(
                body.copy(
                    context = context,
                    cpn = cpn
                )
            )
            context.apply()
            header("X-Goog-Api-Format-Version", "2")
        }
        httpResponse.bodyAsText()
    }

    val response = json.decodeFromString<PlayerResponse>(responseAsText)
    response.userAgent = context.client.userAgent
    response
}

suspend fun Innertube.player(body: PlayerBody): Result<PlayerResponse?>? = runCatchingCancellable {
    val timestamp = NewPipeUtils.getSignatureTimestamp(body.videoId).getOrNull()

    val bodyWithAuth = if (timestamp != null) {
        body.copy(
            playbackContext = PlayerBody.PlaybackContext(
                PlayerBody.PlaybackContext.ContentPlaybackContext(
                    signatureTimestamp = timestamp.toString()
                )
            )
        )
    } else {
        body
    }

    val mainPlayerResponse = getPlayerResponse(bodyWithAuth, MAIN_CONTEXT).getOrNull()

    // 1. Check Main Context
    if (mainPlayerResponse != null && mainPlayerResponse.isValid) {
        val format = mainPlayerResponse.streamingData?.highestQualityFormat
        if (format != null) {
            val streamUrl = NewPipeUtils.getStreamUrl(format, body.videoId).getOrNull()
            if (streamUrl != null && validateStreamUrl(streamUrl, MAIN_CONTEXT.client.userAgent, MAIN_CONTEXT.client.clientName)) {
                logger.info("Success! Found working stream with ${MAIN_CONTEXT.client.clientName}")
                return@runCatchingCancellable mainPlayerResponse
            }
        }
    }

    logger.warn("Main client failed. Firing parallel fallback requests for ultimate speed...")

    // 2. Parallel Fallback Fetching
    return@runCatchingCancellable coroutineScope {
        val resultChannel = Channel<PlayerResponse?>(Channel.CONFLATED)

        val jobs = FALLBACK_CONTEXTS.map { context ->
            launch {
                if (!isActive) return@launch
                
                val currentPlayerResponse = getPlayerResponse(bodyWithAuth, context).getOrNull()
                
                if (currentPlayerResponse != null && currentPlayerResponse.isValid) {
                    val format = currentPlayerResponse.streamingData?.highestQualityFormat
                    if (format != null) {
                        val streamUrl = NewPipeUtils.getStreamUrl(format, body.videoId).getOrNull()
                        if (streamUrl != null) {
                            logger.info("Validating stream from ${context.client.clientName}...")
                            if (validateStreamUrl(streamUrl, context.client.userAgent, context.client.clientName)) {
                                logger.info("Success! Found working stream with ${context.client.clientName}")
                                val finalResponse = mainPlayerResponse?.copy(streamingData = currentPlayerResponse.streamingData) ?: currentPlayerResponse
                                finalResponse.userAgent = currentPlayerResponse.userAgent
                                resultChannel.trySend(finalResponse)
                            }
                        }
                    }
                }
            }
        }

        launch {
            jobs.joinAll()
            // If we get here and all jobs finished without success, return null
            resultChannel.trySend(null)
        }

        // Suspend until the first success is sent, or all jobs fail
        val result = resultChannel.receive()
        
        if (result == null) {
            logger.error("Could not find any working stream for videoId: ${body.videoId}")
        }
        
        // Cancel any slower requests still pending in the background
        coroutineContext.cancelChildren()
        
        result
    }
}
