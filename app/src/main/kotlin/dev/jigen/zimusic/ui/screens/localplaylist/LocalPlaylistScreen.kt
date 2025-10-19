package dev.jigen.zimusic.ui.screens.localplaylist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import dev.jigen.zimusic.Database
import dev.jigen.zimusic.R
import dev.jigen.zimusic.models.Playlist
import dev.jigen.zimusic.ui.components.themed.Scaffold
import dev.jigen.zimusic.ui.components.themed.adaptiveThumbnailContent
import dev.jigen.zimusic.ui.screens.GlobalRoutes
import dev.jigen.zimusic.ui.screens.Route
import dev.jigen.compose.persist.PersistMapCleanup
import dev.jigen.compose.persist.persist
import dev.jigen.compose.routing.RouteHandler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

@Route
@Composable
fun LocalPlaylistScreen(playlistId: Long) {
    val saveableStateHolder = rememberSaveableStateHolder()

    PersistMapCleanup(prefix = "localPlaylist/$playlistId/")

    RouteHandler {
        GlobalRoutes()

        Content {
            var playlist by persist<Playlist?>("localPlaylist/$playlistId/playlist")

            LaunchedEffect(Unit) {
                Database.instance
                    .playlist(playlistId)
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { playlist = it }
            }

            // Song fetching logic is removed from here

            val thumbnailContent = remember(playlist) {
                playlist?.thumbnail?.let { url ->
                    adaptiveThumbnailContent(
                        isLoading = false,
                        url = url
                    )
                } ?: { }
            }

            Scaffold(
                key = "localplaylist",
                topIconButtonId = R.drawable.chevron_back,
                onTopIconButtonClick = pop,
                tabIndex = 0,
                onTabChange = { },
                tabColumnContent = {
                    tab(0, R.string.songs, R.drawable.musical_notes)
                }
            ) { currentTabIndex ->
                saveableStateHolder.SaveableStateProvider(currentTabIndex) {
                    playlist?.let {
                        when (currentTabIndex) {
                            0 -> LocalPlaylistSongs( // Pass the playlist object directly
                                playlist = it,
                                thumbnailContent = thumbnailContent,
                                onDelete = pop
                            )
                        }
                    }
                }
            }
        }
    }
}
