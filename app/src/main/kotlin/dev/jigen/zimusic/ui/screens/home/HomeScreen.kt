package dev.jigen.zimusic.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.jigen.zimusic.R
import dev.jigen.zimusic.models.toUiMood
import dev.jigen.zimusic.preferences.UIStatePreferences
import dev.jigen.zimusic.ui.components.themed.Scaffold
import dev.jigen.zimusic.ui.screens.GlobalRoutes
import dev.jigen.zimusic.ui.screens.Route
import dev.jigen.zimusic.ui.screens.albumRoute
import dev.jigen.zimusic.ui.screens.artistRoute
import dev.jigen.zimusic.ui.screens.builtInPlaylistRoute
import dev.jigen.zimusic.ui.screens.builtinplaylist.BuiltInPlaylistScreen
import dev.jigen.zimusic.ui.screens.localPlaylistRoute
import dev.jigen.zimusic.ui.screens.localplaylist.LocalPlaylistScreen
import dev.jigen.zimusic.ui.screens.mood.MoodScreen
import dev.jigen.zimusic.ui.screens.mood.MoreAlbumsScreen
import dev.jigen.zimusic.ui.screens.mood.MoreMoodsScreen
import dev.jigen.zimusic.ui.screens.moodRoute
import dev.jigen.zimusic.ui.screens.playlistRoute
import dev.jigen.zimusic.ui.screens.searchRoute
import dev.jigen.zimusic.ui.screens.settingsRoute
import dev.jigen.compose.persist.PersistMapCleanup
import dev.jigen.compose.routing.Route0
import dev.jigen.compose.routing.RouteHandler

private val moreMoodsRoute = Route0("moreMoodsRoute")
private val moreAlbumsRoute = Route0("moreAlbumsRoute")

@Route
@Composable
fun HomeScreen() {
    val saveableStateHolder = rememberSaveableStateHolder()

    PersistMapCleanup("home/")

    RouteHandler {
        GlobalRoutes()

        localPlaylistRoute { playlistId ->
            LocalPlaylistScreen(playlistId = playlistId)
        }

        builtInPlaylistRoute { builtInPlaylist ->
            BuiltInPlaylistScreen(builtInPlaylist = builtInPlaylist)
        }

        moodRoute { mood ->
            MoodScreen(mood = mood)
        }

        moreMoodsRoute {
            MoreMoodsScreen()
        }

        moreAlbumsRoute {
            MoreAlbumsScreen()
        }

        Content {
            Scaffold(
                key = "home",
                topIconButtonId = R.drawable.equalizer,
                onTopIconButtonClick = { settingsRoute() },
                tabIndex = UIStatePreferences.homeScreenTabIndex,
                onTabChange = { UIStatePreferences.homeScreenTabIndex = it },
                tabColumnContent = {
                    tab(0, R.string.quick_picks, R.drawable.sparkles)
                    tab(1, R.string.discover, R.drawable.globe)
                    tab(2, R.string.songs, R.drawable.musical_notes)
                    tab(3, R.string.playlists, R.drawable.playlist)
                    tab(4, R.string.artists, R.drawable.person)
                    tab(5, R.string.albums, R.drawable.disc)
                    tab(6, R.string.local, R.drawable.download)
                }
            ) { currentTabIndex ->
                saveableStateHolder.SaveableStateProvider(key = currentTabIndex) {
                    val onSearchClick = { searchRoute("") }
                    when (currentTabIndex) {
                        0 -> QuickPicks(
                            onAlbumClick = { albumRoute(it.key) },
                            onArtistClick = { artistRoute(it.key) },
                            onPlaylistClick = {
                                playlistRoute(
                                    p0 = it.key,
                                    p1 = null,
                                    p2 = null,
                                    p3 = it.channel?.name == "YouTube Music"
                                )
                            },
                            onSearchClick = onSearchClick
                        )

                        1 -> HomeDiscovery(
                            onMoodClick = { mood -> moodRoute(mood.toUiMood()) },
                            onNewReleaseAlbumClick = { albumRoute(it) },
                            onSearchClick = onSearchClick,
                            onMoreMoodsClick = { moreMoodsRoute() },
                            onMoreAlbumsClick = { moreAlbumsRoute() },
                            onPlaylistClick = { playlistRoute(it, null, null, true) }
                        )

                        2 -> HomeSongs(
                            onSearchClick = onSearchClick
                        )

                        3 -> HomePlaylists(
                            onBuiltInPlaylist = { builtInPlaylistRoute(it) },
                            onPlaylistClick = { localPlaylistRoute(it.id) },
                            onSearchClick = onSearchClick
                        )

                        4 -> HomeArtistList(
                            onArtistClick = { artistRoute(it.id) },
                            onSearchClick = onSearchClick
                        )

                        5 -> HomeAlbums(
                            onAlbumClick = { albumRoute(it.id) },
                            onSearchClick = onSearchClick
                        )

                        6 -> HomeLocalSongs(
                            onSearchClick = onSearchClick
                        )
                    }
                }
            }
        }
    }
}
