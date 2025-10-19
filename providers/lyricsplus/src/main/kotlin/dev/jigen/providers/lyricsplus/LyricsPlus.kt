@file:Suppress("JSON_FORMAT_REDUNDANT")

package dev.jigen.providers.lyricsplus

import dev.jigen.providers.lyricsplus.models.LyricLine
import dev.jigen.providers.lyricsplus.models.LyricWord
import dev.jigen.providers.lyricsplus.models.LyricsResponse
import io.ktor.client.*
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object LyricsPlus {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun fetchLyrics(
        baseUrl: String,
        title: String,
        artist: String,
        album: String? = null,
    ): List<LyricLine>? {
        val url = "$baseUrl/v2/lyrics/get"

        try {
            val response = makeLyricsRequest(url, title, artist, album)

            val isLineLevelOnly = response.lyrics.isNotEmpty() && response.lyrics.all { it.syllabus.isEmpty() }

            if (isLineLevelOnly && album != null) {
                val fallbackResponse = makeLyricsRequest(url, title, artist, null)
                return mapResponseToLyricLines(fallbackResponse)
            }

            return mapResponseToLyricLines(response)

        } catch (e: ClientRequestException) {
            return if (e.response.status == HttpStatusCode.NotFound && album != null) {
                try {
                    val fallbackResponse = makeLyricsRequest(url, title, artist, null)
                    mapResponseToLyricLines(fallbackResponse)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun makeLyricsRequest(
        url: String,
        title: String,
        artist: String,
        album: String?
    ): LyricsResponse {
        val response: HttpResponse = client.get(url) {
            parameter("title", title)
            parameter("artist", artist)
            parameter("album", album)
        }

        val responseText = response.bodyAsText()

        return Json { ignoreUnknownKeys = true }
            .decodeFromString<LyricsResponse>(responseText)
    }

    private fun mapResponseToLyricLines(response: LyricsResponse): List<LyricLine> {
        return response.lyrics.map { line ->
            LyricLine(
                fullText = line.text,
                startTimeMs = line.time,
                durationMs = line.duration,
                words = line.syllabus.map {
                    LyricWord(
                        text = it.text,
                        startTimeMs = it.time,
                        durationMs = it.duration
                    )
                }
            )
        }
    }
}
