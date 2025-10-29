package dev.jigen.zimusic.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import dev.jigen.zimusic.Database
import dev.jigen.zimusic.LocalPlayerServiceBinder
import dev.jigen.zimusic.R
import dev.jigen.zimusic.handleUrl
import dev.jigen.zimusic.models.Mood
import dev.jigen.zimusic.models.SearchQuery
import dev.jigen.zimusic.preferences.DataPreferences
import dev.jigen.zimusic.query
import dev.jigen.zimusic.ui.screens.album.AlbumScreen
import dev.jigen.zimusic.ui.screens.artist.ArtistScreen
import dev.jigen.zimusic.ui.screens.playlist.PlaylistScreen
import dev.jigen.zimusic.ui.screens.search.SearchScreen
import dev.jigen.zimusic.ui.screens.searchresult.SearchResultScreen
import dev.jigen.zimusic.ui.screens.settings.LogsScreen
import dev.jigen.zimusic.ui.screens.settings.SettingsScreen
import dev.jigen.zimusic.utils.toast
import dev.jigen.compose.routing.Route0
import dev.jigen.compose.routing.Route1
import dev.jigen.compose.routing.Route4
import dev.jigen.compose.routing.RouteHandlerScope
import dev.jigen.core.data.enums.BuiltInPlaylist

/**
 * Marker class for linters that a composable is a route and should not be handled like a regular
 * composable, but rather as an entrypoint.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class Route

val albumRoute = Route1<String>("albumRoute")
val artistRoute = Route1<String>("artistRoute")
val builtInPlaylistRoute = Route1<BuiltInPlaylist>("builtInPlaylistRoute")
val localPlaylistRoute = Route1<Long>("localPlaylistRoute")
val logsRoute = Route0("logsRoute")
val playlistRoute = Route4<String, String?, Int?, Boolean>("playlistRoute")
val moodRoute = Route1<Mood>("moodRoute")
val searchResultRoute = Route1<String>("searchResultRoute")
val searchRoute = Route1<String>("searchRoute")
val settingsRoute = Route0("settingsRoute")

@Composable
fun RouteHandlerScope.GlobalRoutes() {
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current

    albumRoute { browseId ->
        AlbumScreen(browseId = browseId)
    }

    artistRoute { browseId ->
        ArtistScreen(browseId = browseId)
    }

    logsRoute {
        LogsScreen()
    }

    playlistRoute { browseId, params, maxDepth, shouldDedup ->
        PlaylistScreen(
            browseId = browseId,
            params = params,
            maxDepth = maxDepth,
            shouldDedup = shouldDedup
        )
    }

    settingsRoute {
        SettingsScreen()
    }

    searchRoute { initialTextInput ->
        SearchScreen(
            initialTextInput = initialTextInput,
            onSearch = { query ->
                searchResultRoute(query)

                if (!DataPreferences.pauseSearchHistory) query {
                    Database.instance.insert(SearchQuery(query = query))
                }
            },
            onViewPlaylist = { url ->
                with(context) {
                    runCatching {
                        handleUrl(url.toUri(), binder)
                    }.onFailure {
                        toast(getString(R.string.error_url, url))
                    }
                }
            }
        )
    }

    searchResultRoute { query ->
        SearchResultScreen(
            query = query,
            onSearchAgain = { searchRoute(query) }
        )
    }
}
