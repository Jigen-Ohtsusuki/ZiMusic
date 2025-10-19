package dev.jigen.providers.sponsorblock.requests

import dev.jigen.providers.sponsorblock.SponsorBlock
import dev.jigen.providers.sponsorblock.models.Action
import dev.jigen.providers.sponsorblock.models.Category
import dev.jigen.providers.sponsorblock.models.Segment
import dev.jigen.providers.utils.SerializableUUID
import dev.jigen.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

suspend fun SponsorBlock.segments(
    videoId: String,
    categories: List<Category>? = listOf(Category.Sponsor, Category.OfftopicMusic, Category.PoiHighlight),
    actions: List<Action>? = listOf(Action.Skip, Action.POI),
    segments: List<SerializableUUID>? = null
) = runCatchingCancellable {
    httpClient.get("/api/skipSegments") {
        parameter("videoID", videoId)
        if (!categories.isNullOrEmpty()) categories.forEach { parameter("category", it.serialName) }
        if (!actions.isNullOrEmpty()) actions.forEach { parameter("action", it.serialName) }
        if (!segments.isNullOrEmpty()) segments.forEach { parameter("requiredSegment", it) }
        parameter("service", "YouTube")
    }.body<List<Segment>>()
}
