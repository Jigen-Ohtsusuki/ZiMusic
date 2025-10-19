@file:Suppress("DEPRECATION")

package dev.jigen.zimusic.ui.screens.searchresult

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FractionalThreshold
import androidx.compose.material.rememberSwipeableState
import androidx.compose.material.swipeable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jigen.compose.persist.LocalPersistMap
import dev.jigen.compose.persist.PersistMapCleanup
import dev.jigen.compose.routing.RouteHandler
import dev.jigen.core.ui.Dimensions
import dev.jigen.core.ui.LocalAppearance
import dev.jigen.providers.innertube.Innertube
import dev.jigen.providers.innertube.models.bodies.ContinuationBody
import dev.jigen.providers.innertube.models.bodies.SearchBody
import dev.jigen.providers.innertube.requests.searchPage
import dev.jigen.providers.innertube.utils.from
import dev.jigen.zimusic.LocalPlayerServiceBinder
import dev.jigen.zimusic.R
import dev.jigen.zimusic.preferences.UIStatePreferences
import dev.jigen.zimusic.ui.components.LocalMenuState
import dev.jigen.zimusic.ui.components.themed.Header
import dev.jigen.zimusic.ui.components.themed.NonQueuedMediaItemMenu
import dev.jigen.zimusic.ui.components.themed.Scaffold
import dev.jigen.zimusic.ui.items.*
import dev.jigen.zimusic.ui.screens.*
import dev.jigen.zimusic.utils.addNext
import dev.jigen.zimusic.utils.asMediaItem
import dev.jigen.zimusic.utils.forcePlay
import dev.jigen.zimusic.utils.playingSong
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.gestures.detectTapGestures


private enum class SwipeState {
    Covered,
    Revealed
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Route
@Composable
fun SearchResultScreen(query: String, onSearchAgain: () -> Unit) {
    val persistMap = LocalPersistMap.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val (colorPalette) = LocalAppearance.current

    val saveableStateHolder = rememberSaveableStateHolder()

    PersistMapCleanup(prefix = "searchResults/$query/")

    val (currentMediaId, playing) = playingSong(binder)

    RouteHandler {
        GlobalRoutes()

        Content {
            val headerContent: @Composable (textButton: (@Composable () -> Unit)?) -> Unit = {
                Header(
                    title = query,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures {
                            persistMap?.clean("searchResults/$query/")
                            onSearchAgain()
                        }
                    }
                )
            }

            Scaffold(
                key = "searchresult",
                topIconButtonId = R.drawable.chevron_back,
                onTopIconButtonClick = pop,
                tabIndex = UIStatePreferences.searchResultScreenTabIndex,
                onTabChange = { UIStatePreferences.searchResultScreenTabIndex = it },
                tabColumnContent = {
                    tab(0, R.string.songs, R.drawable.musical_notes)
                    tab(1, R.string.albums, R.drawable.disc)
                    tab(2, R.string.artists, R.drawable.person)
                    tab(3, R.string.videos, R.drawable.film)
                    tab(4, R.string.playlists, R.drawable.playlist)
                }
            ) { tabIndex ->
                saveableStateHolder.SaveableStateProvider(tabIndex) {
                    when (tabIndex) {
                        0 -> ItemsPage(
                            tag = "searchResults/$query/songs",
                            provider = { continuation ->
                                if (continuation == null) Innertube.searchPage(
                                    body = SearchBody(
                                        query = query,
                                        params = Innertube.SearchFilter.Song.value
                                    ),
                                    fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                                ) else Innertube.searchPage(
                                    body = ContinuationBody(continuation = continuation),
                                    fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                                )
                            },
                            emptyItemsText = stringResource(R.string.no_search_results),
                            header = headerContent,
                            itemContent = { song ->
                                val swipeableState = rememberSwipeableState(initialValue = SwipeState.Covered)
                                val density = LocalDensity.current

                                // The total width of the swipeable area. This is our cap.
                                val revealWidth = 96.dp
                                val revealWidthPx = with(density) { revealWidth.toPx() }

                                // Simplified anchors. The swipe is capped at revealWidthPx.
                                val anchors = mapOf(
                                    0f to SwipeState.Covered,
                                    revealWidthPx to SwipeState.Revealed
                                )

                                // Effect to handle the action when swipe is completed.
                                LaunchedEffect(swipeableState.currentValue) {
                                    if (swipeableState.currentValue == SwipeState.Revealed) {
                                        binder?.player?.addNext(song.asMediaItem)
                                        // Animate back to the original position after the action.
                                        swipeableState.animateTo(SwipeState.Covered)
                                    }
                                }

                                // Calculate progress for the new animation (from 0.0 to 1.0)
                                val swipeProgress = (swipeableState.offset.value / revealWidthPx).coerceIn(0f, 1f)

                                Box(
                                    modifier = Modifier
                                        .background(colorPalette.background0) // Set background on the whole Box
                                ) {
                                    // Background content (Action Icon) with new animation
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(revealWidth)
                                            .align(Alignment.CenterStart),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Image(
                                            painter = painterResource(R.drawable.play_skip_forward),
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(colorPalette.accent),
                                            modifier = Modifier
                                                .padding(start = 24.dp)
                                                .graphicsLayer {
                                                    // Icon scales and fades in with the swipe
                                                    alpha = swipeProgress
                                                    scaleX = swipeProgress
                                                    scaleY = swipeProgress
                                                }
                                        )
                                    }

                                    // Foreground content (Song Item)
                                    SongItem(
                                        song = song,
                                        thumbnailSize = Dimensions.thumbnails.song,
                                        modifier = Modifier
                                            .graphicsLayer {
                                                // Simple translation based on the swipe offset
                                                translationX = swipeableState.offset.value
                                            }
                                            .swipeable(
                                                state = swipeableState,
                                                anchors = anchors,
                                                thresholds = { _, _ -> FractionalThreshold(0.5f) },
                                                orientation = Orientation.Horizontal,
                                                // Disable resistance to cap the swipe
                                                resistance = null
                                            )
                                            .combinedClickable(
                                                onLongClick = {
                                                    menuState.display {
                                                        NonQueuedMediaItemMenu(
                                                            onDismiss = menuState::hide,
                                                            mediaItem = song.asMediaItem
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    binder?.stopRadio()
                                                    binder?.player?.forcePlay(song.asMediaItem)
                                                    binder?.setupRadio(song.info?.endpoint)
                                                }
                                            ),
                                        isPlaying = playing && currentMediaId == song.key
                                    )
                                }
                            },
                            itemPlaceholderContent = {
                                SongItemPlaceholder(thumbnailSize = Dimensions.thumbnails.song)
                            }
                        )

                        1 -> ItemsPage(
                            tag = "searchResults/$query/albums",
                            provider = { continuation ->
                                if (continuation == null) {
                                    Innertube.searchPage(
                                        body = SearchBody(
                                            query = query,
                                            params = Innertube.SearchFilter.Album.value
                                        ),
                                        fromMusicShelfRendererContent = Innertube.AlbumItem::from
                                    )
                                } else {
                                    Innertube.searchPage(
                                        body = ContinuationBody(continuation = continuation),
                                        fromMusicShelfRendererContent = Innertube.AlbumItem::from
                                    )
                                }
                            },
                            emptyItemsText = stringResource(R.string.no_search_results),
                            header = headerContent,
                            itemContent = { album ->
                                AlbumItem(
                                    album = album,
                                    thumbnailSize = Dimensions.thumbnails.album,
                                    modifier = Modifier.clickable(onClick = { albumRoute(album.key) })
                                )
                            },
                            itemPlaceholderContent = {
                                AlbumItemPlaceholder(thumbnailSize = Dimensions.thumbnails.album)
                            }
                        )

                        2 -> ItemsPage(
                            tag = "searchResults/$query/artists",
                            provider = { continuation ->
                                if (continuation == null) {
                                    Innertube.searchPage(
                                        body = SearchBody(
                                            query = query,
                                            params = Innertube.SearchFilter.Artist.value
                                        ),
                                        fromMusicShelfRendererContent = Innertube.ArtistItem::from
                                    )
                                } else {
                                    Innertube.searchPage(
                                        body = ContinuationBody(continuation = continuation),
                                        fromMusicShelfRendererContent = Innertube.ArtistItem::from
                                    )
                                }
                            },
                            emptyItemsText = stringResource(R.string.no_search_results),
                            header = headerContent,
                            itemContent = { artist ->
                                ArtistItem(
                                    artist = artist,
                                    thumbnailSize = 64.dp,
                                    modifier = Modifier
                                        .clickable(onClick = { artistRoute(artist.key) })
                                )
                            },
                            itemPlaceholderContent = {
                                ArtistItemPlaceholder(thumbnailSize = 64.dp)
                            }
                        )

                        3 -> ItemsPage(
                            tag = "searchResults/$query/videos",
                            provider = { continuation ->
                                if (continuation == null) Innertube.searchPage(
                                    body = SearchBody(
                                        query = query,
                                        params = Innertube.SearchFilter.Video.value
                                    ),
                                    fromMusicShelfRendererContent = Innertube.VideoItem::from
                                ) else Innertube.searchPage(
                                    body = ContinuationBody(continuation = continuation),
                                    fromMusicShelfRendererContent = Innertube.VideoItem::from
                                )
                            },
                            emptyItemsText = stringResource(R.string.no_search_results),
                            header = headerContent,
                            itemContent = { video ->
                                VideoItem(
                                    video = video,
                                    thumbnailWidth = 128.dp,
                                    thumbnailHeight = 72.dp,
                                    modifier = Modifier.combinedClickable(
                                        onLongClick = {
                                            menuState.display {
                                                NonQueuedMediaItemMenu(
                                                    mediaItem = video.asMediaItem,
                                                    onDismiss = menuState::hide
                                                )
                                            }
                                        },
                                        onClick = {
                                            binder?.stopRadio()
                                            binder?.player?.forcePlay(video.asMediaItem)
                                            binder?.setupRadio(video.info?.endpoint)
                                        }
                                    )
                                )
                            },
                            itemPlaceholderContent = {
                                VideoItemPlaceholder(
                                    thumbnailWidth = 128.dp,
                                    thumbnailHeight = 72.dp
                                )
                            }
                        )

                        4 -> ItemsPage(
                            tag = "searchResults/$query/playlists",
                            provider = { continuation ->
                                if (continuation == null) Innertube.searchPage(
                                    body = SearchBody(
                                        query = query,
                                        params = Innertube.SearchFilter.CommunityPlaylist.value
                                    ),
                                    fromMusicShelfRendererContent = Innertube.PlaylistItem::from
                                ) else Innertube.searchPage(
                                    body = ContinuationBody(continuation = continuation),
                                    fromMusicShelfRendererContent = Innertube.PlaylistItem::from
                                )
                            },
                            emptyItemsText = stringResource(R.string.no_search_results),
                            header = headerContent,
                            itemContent = { playlist ->
                                PlaylistItem(
                                    playlist = playlist,
                                    thumbnailSize = Dimensions.thumbnails.playlist,
                                    modifier = Modifier.clickable {
                                        playlistRoute(playlist.key, null, null, false)
                                    }
                                )
                            },
                            itemPlaceholderContent = {
                                PlaylistItemPlaceholder(thumbnailSize = Dimensions.thumbnails.playlist)
                            }
                        )
                    }
                }
            }
        }
    }
}
