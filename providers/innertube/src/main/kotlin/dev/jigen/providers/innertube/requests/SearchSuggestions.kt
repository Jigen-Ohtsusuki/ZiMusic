package dev.jigen.providers.innertube.requests

import dev.jigen.providers.innertube.Innertube
import dev.jigen.providers.innertube.models.SearchSuggestionsResponse
import dev.jigen.providers.innertube.models.bodies.SearchSuggestionsBody
import dev.jigen.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody

suspend fun Innertube.searchSuggestions(body: SearchSuggestionsBody) = runCatchingCancellable {
    val response = Innertube.withRetry {
        client.post(SEARCH_SUGGESTIONS) {
            setBody(body)
            @Suppress("all")
            mask(
                "contents.searchSuggestionsSectionRenderer.contents.searchSuggestionRenderer.navigationEndpoint.searchEndpoint.query"
            )
        }.body<SearchSuggestionsResponse>()
    }

    response
        .contents
        ?.firstOrNull()
        ?.searchSuggestionsSectionRenderer
        ?.contents
        ?.mapNotNull { content ->
            content
                .searchSuggestionRenderer
                ?.navigationEndpoint
                ?.searchEndpoint
                ?.query
        }
}
