package dev.jigen.providers.innertube.requests

import dev.jigen.providers.innertube.Innertube
import dev.jigen.providers.innertube.models.GetQueueResponse
import dev.jigen.providers.innertube.models.bodies.QueueBody
import dev.jigen.providers.innertube.utils.from
import dev.jigen.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody

suspend fun Innertube.queue(body: QueueBody) = runCatchingCancellable {
    val response = Innertube.withRetry {
        client.post(QUEUE) {
            setBody(body)
            mask("queueDatas.content.$PLAYLIST_PANEL_VIDEO_RENDERER_MASK")
        }.body<GetQueueResponse>()
    }

    response
        .queueData
        ?.mapNotNull { queueData ->
            queueData
                .content
                ?.playlistPanelVideoRenderer
                ?.let(Innertube.SongItem::from)
        }
}

suspend fun Innertube.song(videoId: String): Result<Innertube.SongItem?>? =
    queue(QueueBody(videoIds = listOf(videoId)))?.map { it?.firstOrNull() }
