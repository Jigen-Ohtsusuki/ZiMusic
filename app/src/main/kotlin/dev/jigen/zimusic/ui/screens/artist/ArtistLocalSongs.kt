package dev.jigen.zimusic.ui.screens.artist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.jigen.zimusic.Database
import dev.jigen.zimusic.LocalPlayerAwareWindowInsets
import dev.jigen.zimusic.LocalPlayerServiceBinder
import dev.jigen.zimusic.R
import dev.jigen.zimusic.models.Song
import dev.jigen.zimusic.ui.components.LocalMenuState
import dev.jigen.zimusic.ui.components.ShimmerHost
import dev.jigen.zimusic.ui.components.themed.FloatingActionsContainerWithScrollToTop
import dev.jigen.zimusic.ui.components.themed.LayoutWithAdaptiveThumbnail
import dev.jigen.zimusic.ui.components.themed.NonQueuedMediaItemMenu
import dev.jigen.zimusic.ui.components.themed.SecondaryTextButton
import dev.jigen.zimusic.ui.items.SongItem
import dev.jigen.zimusic.ui.items.SongItemPlaceholder
import dev.jigen.zimusic.utils.asMediaItem
import dev.jigen.zimusic.utils.enqueue
import dev.jigen.zimusic.utils.forcePlayAtIndex
import dev.jigen.zimusic.utils.forcePlayFromBeginning
import dev.jigen.zimusic.utils.playingSong
import dev.jigen.compose.persist.persist
import dev.jigen.core.ui.Dimensions
import dev.jigen.core.ui.LocalAppearance
import dev.jigen.core.ui.utils.isLandscape

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistLocalSongs(
    browseId: String,
    headerContent: @Composable (textButton: (@Composable () -> Unit)?) -> Unit,
    thumbnailContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val binder = LocalPlayerServiceBinder.current
    val (colorPalette) = LocalAppearance.current
    val menuState = LocalMenuState.current

    var songs by persist<List<Song>?>("artist/$browseId/localSongs")

    LaunchedEffect(Unit) {
        Database.instance.artistSongs(browseId).collect { songs = it }
    }

    val lazyListState = rememberLazyListState()

    val (currentMediaId, playing) = playingSong(binder)

    LayoutWithAdaptiveThumbnail(
        thumbnailContent = thumbnailContent,
        modifier = modifier
    ) {
        Box {
            LazyColumn(
                state = lazyListState,
                contentPadding = LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End).asPaddingValues(),
                modifier = Modifier
                    .background(colorPalette.background0)
                    .fillMaxSize()
            ) {
                item(
                    key = "header",
                    contentType = 0
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        headerContent {
                            SecondaryTextButton(
                                text = stringResource(R.string.enqueue),
                                enabled = !songs.isNullOrEmpty(),
                                onClick = {
                                    binder?.player?.enqueue(songs!!.map(Song::asMediaItem))
                                }
                            )
                        }

                        if (!isLandscape) thumbnailContent()
                    }
                }

                songs?.let { songs ->
                    itemsIndexed(
                        items = songs,
                        key = { _, song -> song.id }
                    ) { index, song ->
                        SongItem(
                            modifier = Modifier.combinedClickable(
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
                                    binder?.player?.forcePlayAtIndex(
                                        items = songs.map(Song::asMediaItem),
                                        index = index
                                    )
                                }
                            ),
                            song = song,
                            thumbnailSize = Dimensions.thumbnails.song,
                            isPlaying = playing && currentMediaId == song.id
                        )
                    }
                } ?: item(key = "loading") {
                    ShimmerHost {
                        repeat(4) {
                            SongItemPlaceholder(thumbnailSize = Dimensions.thumbnails.song)
                        }
                    }
                }
            }

            FloatingActionsContainerWithScrollToTop(
                lazyListState = lazyListState,
                icon = R.drawable.shuffle,
                onClick = {
                    songs?.let { songs ->
                        if (songs.isNotEmpty()) {
                            binder?.stopRadio()
                            binder?.player?.forcePlayFromBeginning(
                                songs.shuffled().map(Song::asMediaItem)
                            )
                        }
                    }
                }
            )
        }
    }
}
