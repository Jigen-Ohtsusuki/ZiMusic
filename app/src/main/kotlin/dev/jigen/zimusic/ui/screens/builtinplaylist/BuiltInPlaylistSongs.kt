package dev.jigen.zimusic.ui.screens.builtinplaylist

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jigen.compose.persist.persistList
import dev.jigen.core.data.enums.BuiltInPlaylist
import dev.jigen.core.data.enums.SongSortBy
import dev.jigen.core.data.enums.SortOrder
import dev.jigen.core.ui.Dimensions
import dev.jigen.core.ui.LocalAppearance
import dev.jigen.core.ui.utils.enumSaver
import dev.jigen.zimusic.Database
import dev.jigen.zimusic.LocalPlayerAwareWindowInsets
import dev.jigen.zimusic.LocalPlayerServiceBinder
import dev.jigen.zimusic.R
import dev.jigen.zimusic.models.Song
import dev.jigen.zimusic.preferences.DataPreferences
import dev.jigen.zimusic.ui.components.LocalMenuState
import dev.jigen.zimusic.ui.components.themed.*
import dev.jigen.zimusic.ui.items.SongItem
import dev.jigen.zimusic.ui.screens.home.HeaderSongSortBy
import dev.jigen.zimusic.utils.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

private enum class SwipeState {
    Covered,
    Revealed
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalCoroutinesApi::class, ExperimentalMaterialApi::class)
@Composable
fun BuiltInPlaylistSongs(
    builtInPlaylist: BuiltInPlaylist,
    modifier: Modifier = Modifier
) = with(DataPreferences) {
    val (colorPalette) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current

    var songs by persistList<Song>("${builtInPlaylist.name}/songs")

    var sortBy by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SongSortBy.DateAdded) }
    var sortOrder by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SortOrder.Descending) }

    LaunchedEffect(binder, sortBy, sortOrder) {
        when (builtInPlaylist) {
            BuiltInPlaylist.Favorites -> Database.instance.favorites(
                sortBy = sortBy,
                sortOrder = sortOrder
            )

            BuiltInPlaylist.Offline ->
                Database.instance
                    .songsWithContentLength(
                        sortBy = sortBy,
                        sortOrder = sortOrder
                    )
                    .map { songs ->
                        songs.filter { binder?.isCached(it) ?: false }.map { it.song }
                    }

            BuiltInPlaylist.Top -> combine(
                flow = topListPeriodProperty.stateFlow,
                flow2 = topListLengthProperty.stateFlow
            ) { period, length -> period to length }.flatMapLatest { (period, length) ->
                if (period.duration == null) Database.instance
                    .songsByPlayTimeDesc(limit = length)
                    .distinctUntilChanged()
                    .cancellable()
                else Database.instance
                    .trending(
                        limit = length,
                        period = period.duration.inWholeMilliseconds
                    )
                    .distinctUntilChanged()
                    .cancellable()
            }

            BuiltInPlaylist.History -> Database.instance.history()
        }.collect { songs = it.toImmutableList() }
    }

    val lazyListState = rememberLazyListState()

    val (currentMediaId, playing) = playingSong(binder)

    Box(modifier = modifier) {
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
                Header(
                    title = when (builtInPlaylist) {
                        BuiltInPlaylist.Favorites -> stringResource(R.string.favorites)
                        BuiltInPlaylist.Offline -> stringResource(R.string.offline)
                        BuiltInPlaylist.Top -> stringResource(
                            R.string.format_my_top_playlist,
                            topListLength
                        )

                        BuiltInPlaylist.History -> stringResource(R.string.history)
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    SecondaryTextButton(
                        text = stringResource(R.string.enqueue),
                        enabled = songs.isNotEmpty(),
                        onClick = {
                            binder?.player?.enqueue(songs.map(Song::asMediaItem))
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    if (builtInPlaylist != BuiltInPlaylist.Offline) PlaylistDownloadIcon(
                        songs = songs.map(Song::asMediaItem).toImmutableList()
                    )

                    if (builtInPlaylist.sortable) HeaderSongSortBy(
                        sortBy = sortBy,
                        setSortBy = { sortBy = it },
                        sortOrder = sortOrder,
                        setSortOrder = { sortOrder = it }
                    )

                    if (builtInPlaylist == BuiltInPlaylist.Top) {
                        var dialogShowing by rememberSaveable { mutableStateOf(false) }

                        SecondaryTextButton(
                            text = topListPeriod.displayName(),
                            onClick = { dialogShowing = true }
                        )

                        if (dialogShowing) ValueSelectorDialog(
                            onDismiss = { dialogShowing = false },
                            title = stringResource(
                                R.string.format_view_top_of_header,
                                topListLength
                            ),
                            selectedValue = topListPeriod,
                            values = DataPreferences.TopListPeriod.entries.toImmutableList(),
                            onValueSelect = { topListPeriod = it },
                            valueText = { it.displayName() }
                        )
                    }
                }
            }

            itemsIndexed(
                items = songs,
                key = { _, song -> song.id },
                contentType = { _, song -> song }
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
                                        when (builtInPlaylist) {
                                            BuiltInPlaylist.Offline -> InHistoryMediaItemMenu(
                                                song = song,
                                                onDismiss = menuState::hide
                                            )

                                            BuiltInPlaylist.Favorites,
                                            BuiltInPlaylist.Top,
                                            BuiltInPlaylist.History -> NonQueuedMediaItemMenu(
                                                mediaItem = song.asMediaItem,
                                                onDismiss = menuState::hide
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    binder?.stopRadio()
                                    binder?.player?.forcePlayAtIndex(
                                        items = songs.map(Song::asMediaItem),
                                        index = index
                                    )
                                }
                            )
                            .animateItem(),
                        song = song,
                        index = if (builtInPlaylist == BuiltInPlaylist.Top) index else null,
                        thumbnailSize = Dimensions.thumbnails.song,
                        isPlaying = playing && currentMediaId == song.id
                    )
                }
            }
        }

        FloatingActionsContainerWithScrollToTop(
            lazyListState = lazyListState,
            icon = R.drawable.shuffle,
            onClick = {
                if (songs.isEmpty()) return@FloatingActionsContainerWithScrollToTop
                binder?.stopRadio()
                binder?.player?.forcePlayFromBeginning(
                    songs.shuffled().map(Song::asMediaItem)
                )
            }
        )
    }
}
