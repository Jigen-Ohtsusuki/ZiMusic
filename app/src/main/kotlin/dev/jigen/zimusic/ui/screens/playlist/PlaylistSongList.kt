package dev.jigen.zimusic.ui.screens.playlist

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FractionalThreshold
import androidx.compose.material.rememberSwipeableState
import androidx.compose.material.swipeable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.shimmer
import dev.jigen.compose.persist.persist
import dev.jigen.core.ui.Dimensions
import dev.jigen.core.ui.LocalAppearance
import dev.jigen.core.ui.utils.isLandscape
import dev.jigen.providers.innertube.Innertube
import dev.jigen.providers.innertube.models.bodies.BrowseBody
import dev.jigen.providers.innertube.requests.playlistPage
import dev.jigen.zimusic.Database
import dev.jigen.zimusic.LocalPlayerAwareWindowInsets
import dev.jigen.zimusic.LocalPlayerServiceBinder
import dev.jigen.zimusic.R
import dev.jigen.zimusic.models.Playlist
import dev.jigen.zimusic.models.SongPlaylistMap
import dev.jigen.zimusic.query
import dev.jigen.zimusic.transaction
import dev.jigen.zimusic.ui.components.LocalMenuState
import dev.jigen.zimusic.ui.components.ShimmerHost
import dev.jigen.zimusic.ui.components.themed.*
import dev.jigen.zimusic.ui.items.SongItem
import dev.jigen.zimusic.ui.items.SongItemPlaceholder
import dev.jigen.zimusic.utils.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SwipeState {
    Covered,
    Revealed
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun PlaylistSongList(
    browseId: String,
    params: String?,
    maxDepth: Int?,
    shouldDedup: Boolean,
    modifier: Modifier = Modifier
) {
    val (colorPalette) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val context = LocalContext.current
    val menuState = LocalMenuState.current

    var playlistPage by persist<Innertube.PlaylistOrAlbumPage?>("playlist/$browseId/playlistPage")

    LaunchedEffect(Unit) {
        if (playlistPage != null && playlistPage?.songsPage?.continuation == null) return@LaunchedEffect

        playlistPage = withContext(Dispatchers.IO) {
            Innertube
                .playlistPage(BrowseBody(browseId = browseId, params = params))
                ?.completed(
                    maxDepth = maxDepth ?: Int.MAX_VALUE,
                    shouldDedup = shouldDedup
                )
                ?.getOrNull()
        }
    }

    var isImportingPlaylist by rememberSaveable { mutableStateOf(false) }

    if (isImportingPlaylist) TextFieldDialog(
        hintText = stringResource(R.string.enter_playlist_name_prompt),
        initialTextInput = playlistPage?.title.orEmpty(),
        onDismiss = { isImportingPlaylist = false },
        onAccept = { text ->
            query {
                transaction {
                    val playlistId = Database.instance.insert(
                        Playlist(
                            name = text,
                            browseId = browseId,
                            thumbnail = playlistPage?.thumbnail?.url
                        )
                    )

                    playlistPage?.songsPage?.items
                        ?.map(Innertube.SongItem::asMediaItem)
                        ?.onEach(Database.instance::insert)
                        ?.mapIndexed { index, mediaItem ->
                            SongPlaylistMap(
                                songId = mediaItem.mediaId,
                                playlistId = playlistId,
                                position = index
                            )
                        }?.let(Database.instance::insertSongPlaylistMaps)
                }
            }
        }
    )

    val headerContent: @Composable () -> Unit = {
        if (playlistPage == null) HeaderPlaceholder(modifier = Modifier.shimmer())
        else Header(title = playlistPage?.title ?: stringResource(R.string.unknown)) {
            SecondaryTextButton(
                text = stringResource(R.string.enqueue),
                enabled = playlistPage?.songsPage?.items?.isNotEmpty() == true,
                onClick = {
                    playlistPage?.songsPage?.items?.map(Innertube.SongItem::asMediaItem)
                        ?.let { mediaItems ->
                            binder?.player?.enqueue(mediaItems)
                        }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            playlistPage?.songsPage?.items?.map(Innertube.SongItem::asMediaItem)
                ?.let { PlaylistDownloadIcon(songs = it.toImmutableList()) }

            HeaderIconButton(
                icon = R.drawable.add,
                color = colorPalette.text,
                onClick = { isImportingPlaylist = true }
            )

            HeaderIconButton(
                icon = R.drawable.share_social,
                color = colorPalette.text,
                onClick = {
                    val url = playlistPage?.url
                        ?: "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}"

                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }

                    context.startActivity(Intent.createChooser(sendIntent, null))
                }
            )
        }
    }

    val thumbnailContent = adaptiveThumbnailContent(
        isLoading = playlistPage == null,
        url = playlistPage?.thumbnail?.url
    )

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
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues(),
                modifier = Modifier
                    .background(colorPalette.background0)
                    .fillMaxSize()
            ) {
                item(
                    key = "header",
                    contentType = 0
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        headerContent()
                        if (!isLandscape) thumbnailContent()
                        PlaylistInfo(playlist = playlistPage)
                    }
                }
                itemsIndexed(
                    items = playlistPage?.songsPage?.items ?: emptyList(),
                    key = { index, song -> "${song.key}-$index" }
                ) { index, song ->
                    val swipeableState = rememberSwipeableState(initialValue = SwipeState.Covered)
                    val density = LocalDensity.current

                    val revealWidth = 96.dp
                    val revealWidthPx = with(density) { revealWidth.toPx() }

                    val anchors = mapOf(
                        0f to SwipeState.Covered,
                        revealWidthPx to SwipeState.Revealed
                    )

                    LaunchedEffect(swipeableState.currentValue) {
                        if (swipeableState.currentValue == SwipeState.Revealed) {
                            binder?.player?.addNext(song.asMediaItem)
                            swipeableState.animateTo(SwipeState.Covered)
                        }
                    }

                    val swipeProgress = (swipeableState.offset.value / revealWidthPx).coerceIn(0f, 1f)

                    Box(
                        modifier = Modifier
                            .background(colorPalette.background0)
                    ) {
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
                                        alpha = swipeProgress
                                        scaleX = swipeProgress
                                        scaleY = swipeProgress
                                    }
                            )
                        }

                        SongItem(
                            song = song,
                            thumbnailSize = Dimensions.thumbnails.song,
                            modifier = Modifier
                                .graphicsLayer {
                                    translationX = swipeableState.offset.value
                                }
                                .swipeable(
                                    state = swipeableState,
                                    anchors = anchors,
                                    thresholds = { _, _ -> FractionalThreshold(0.5f) },
                                    orientation = Orientation.Horizontal,
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
                                        playlistPage?.songsPage?.items?.map(Innertube.SongItem::asMediaItem)
                                            ?.let { mediaItems ->
                                                binder?.stopRadio()
                                                binder?.player?.forcePlayAtIndex(mediaItems, index)
                                            }
                                    }
                                ),
                            isPlaying = playing && currentMediaId == song.key
                        )
                    }
                }

                if (playlistPage == null) item(key = "loading") {
                    ShimmerHost(modifier = Modifier.fillParentMaxSize()) {
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
                    playlistPage?.songsPage?.items?.let { songs ->
                        if (songs.isNotEmpty()) {
                            binder?.stopRadio()
                            binder?.player?.forcePlayFromBeginning(
                                songs.shuffled().map(Innertube.SongItem::asMediaItem)
                            )
                        }
                    }
                }
            )
        }
    }
}
