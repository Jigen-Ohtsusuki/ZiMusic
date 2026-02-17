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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.Request

private val streamValidatorClient by lazy {
    YouTube.client.newBuilder()
        .build()
}

private val MAIN_CONTEXT = Context.DefaultVR

private val FALLBACK_CONTEXTS = arrayOf(
    Context.DefaultIOS,
    Context.DefaultWebNoLang,
    Context.DefaultAndroid,
    Context.DefaultTV,
    Context.OnlyWeb,
    Context.WebCreator
)

private val PlayerResponse.isValid
    get() = playabilityStatus.status == "OK" &&
        streamingData?.adaptiveFormats?.any { it.url != null || it.signatureCipher != null } == true

private val PlayerResponse.StreamingData.highestQualityFormat: PlayerResponse.StreamingData.Format?
    get() = (adaptiveFormats + formats.orEmpty())
        .filter { it.isAudio }
        .maxByOrNull { it.bitrate }

private fun validateStreamUrl(url: String): Boolean {
    return try {
        val request = Request.Builder().url(url).head().build()
        val response = streamValidatorClient.newCall(request).execute()
        response.isSuccessful.also { response.close() }
    } catch (_: Exception) {
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

    json.decodeFromString<PlayerResponse>(responseAsText)
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

    val mainPlayerResponse = getPlayerResponse(bodyWithAuth, MAIN_CONTEXT).getOrThrow()

    if (!mainPlayerResponse.isValid) {
        logger.warn("Main client response is not valid: ${mainPlayerResponse.playabilityStatus.reason}")
        return@runCatchingCancellable mainPlayerResponse
    }

    val allContexts = listOf(MAIN_CONTEXT) + FALLBACK_CONTEXTS

    for (context in allContexts) {
        if (!currentCoroutineContext().isActive) return@runCatchingCancellable null

        val currentPlayerResponse = if (context == MAIN_CONTEXT) {
            mainPlayerResponse
        } else {
            getPlayerResponse(bodyWithAuth, context).getOrNull()
        }

        if (currentPlayerResponse == null || !currentPlayerResponse.isValid) {
            logger.warn("Skipping invalid response from ${context.client.clientName}")
            continue
        }

        val format = currentPlayerResponse.streamingData?.highestQualityFormat
        if (format == null) {
            logger.warn("No suitable format found for client: ${context.client.clientName}")
            continue
        }

        val streamUrl = NewPipeUtils.getStreamUrl(format, body.videoId).getOrNull()
        if (streamUrl == null) {
            logger.warn("Could not resolve stream URL for client: ${context.client.clientName}")
            continue
        }

        logger.info("Validating stream from ${context.client.clientName}...")
        if (validateStreamUrl(streamUrl)) {
            logger.info("Success! Found working stream with ${context.client.clientName}")

            return@runCatchingCancellable if (context != MAIN_CONTEXT) {
                mainPlayerResponse.copy(streamingData = currentPlayerResponse.streamingData)
            } else {
                mainPlayerResponse
            }
        } else {
            logger.warn("Stream validation failed for ${context.client.clientName}")
        }
    }

    logger.error("Could not find any working stream for videoId: ${body.videoId}")
    return@runCatchingCancellable null
}
