package it.vfsfitvnm.vimusic.ui.screens.localplaylist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FractionalThreshold
import androidx.compose.material.rememberSwipeableState
import androidx.compose.material.swipeable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.vfsfitvnm.compose.persist.persistList
import it.vfsfitvnm.compose.reordering.draggedItem
import it.vfsfitvnm.compose.reordering.rememberReorderingState
import it.vfsfitvnm.core.data.enums.SongSortBy
import it.vfsfitvnm.core.data.enums.SortOrder
import it.vfsfitvnm.core.ui.Dimensions
import it.vfsfitvnm.core.ui.LocalAppearance
import it.vfsfitvnm.core.ui.utils.enumSaver
import it.vfsfitvnm.core.ui.utils.isLandscape
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.bodies.BrowseBody
import it.vfsfitvnm.providers.innertube.requests.playlistPage
import it.vfsfitvnm.vimusic.*
import it.vfsfitvnm.vimusic.models.Playlist
import it.vfsfitvnm.vimusic.models.Song
import it.vfsfitvnm.vimusic.models.SongPlaylistMap
import it.vfsfitvnm.vimusic.preferences.DataPreferences
import it.vfsfitvnm.vimusic.service.PrecacheService
import it.vfsfitvnm.vimusic.ui.components.LocalMenuState
import it.vfsfitvnm.vimusic.ui.components.themed.*
import it.vfsfitvnm.vimusic.ui.items.SongItem
import it.vfsfitvnm.vimusic.ui.screens.home.HeaderSongSortBy
import it.vfsfitvnm.vimusic.utils.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import it.vfsfitvnm.vimusic.R

private enum class SwipeState {
    Covered,
    Revealed
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalMaterialApi::class)
@Composable
fun LocalPlaylistSongs(
    playlist: Playlist,
    onDelete: () -> Unit,
    thumbnailContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) = LayoutWithAdaptiveThumbnail(
    thumbnailContent = thumbnailContent,
    modifier = modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    var songs by persistList<Song>("localPlaylist/${playlist.id}/songs")

    var filter: String? by rememberSaveable { mutableStateOf(null) }

    val filteredSongs by remember {
        derivedStateOf {
            filter?.lowercase()?.ifBlank { null }?.let { f ->
                songs.filter {
                    f in it.title.lowercase() || f in it.artistsText?.lowercase().orEmpty()
                }.sortedBy { it.title }
            } ?: songs
        }
    }

    var sortBy by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SongSortBy.Position) }
    var sortOrder by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SortOrder.Ascending) }

    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(playlist.id, sortBy, sortOrder) {
        Database.instance
            .playlistSongs(playlist.id, sortBy, sortOrder)
            .filterNotNull()
            .distinctUntilChanged()
            .collect { songs = it.toImmutableList() }
    }

    LaunchedEffect(Unit) {
        if (DataPreferences.autoSyncPlaylists) playlist.browseId?.let { browseId ->
            loading = true
            sync(playlist, browseId)
            loading = false
        }
    }

    val reorderingState = rememberReorderingState(
        lazyListState = lazyListState,
        key = songs,
        onDragEnd = { fromIndex, toIndex ->
            transaction {
                Database.instance.move(playlist.id, fromIndex, toIndex)
            }
        },
        extraItemCount = 1
    )

    var isRenaming by rememberSaveable { mutableStateOf(false) }

    if (isRenaming) TextFieldDialog(
        hintText = stringResource(R.string.enter_playlist_name_prompt),
        initialTextInput = playlist.name,
        onDismiss = { isRenaming = false },
        onAccept = { text ->
            query {
                Database.instance.update(playlist.copy(name = text))
            }
        }
    )

    var isDeleting by rememberSaveable { mutableStateOf(false) }

    if (isDeleting) ConfirmationDialog(
        text = stringResource(R.string.confirm_delete_playlist),
        onDismiss = { isDeleting = false },
        onConfirm = {
            query {
                Database.instance.delete(playlist)
            }
            onDelete()
        }
    )

    val (currentMediaId, playing) = playingSong(binder)

    Box {
        LookaheadScope {
            LazyColumn(
                state = reorderingState.lazyListState,
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
                        Header(
                            title = playlist.name,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            var searching by rememberSaveable { mutableStateOf(false) }

                            AnimatedContent(
                                targetState = searching,
                                label = ""
                            ) { state ->
                                if (state) {
                                    val focusRequester = remember { FocusRequester() }

                                    LaunchedEffect(Unit) {
                                        focusRequester.requestFocus()
                                    }

                                    TextField(
                                        value = filter.orEmpty(),
                                        onValueChange = { filter = it },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = {
                                            if (filter.isNullOrBlank()) filter = ""
                                            focusManager.clearFocus()
                                        }),
                                        hintText = stringResource(R.string.filter_placeholder),
                                        modifier = Modifier
                                            .focusRequester(focusRequester)
                                            .onFocusChanged {
                                                if (!it.hasFocus) {
                                                    keyboardController?.hide()
                                                    if (filter?.isBlank() == true) {
                                                        filter = null
                                                        searching = false
                                                    }
                                                }
                                            }
                                    )
                                } else Row(verticalAlignment = Alignment.CenterVertically) {
                                    HeaderIconButton(
                                        onClick = { searching = true },
                                        icon = R.drawable.search,
                                        color = colorPalette.text
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    if (songs.isNotEmpty()) BasicText(
                                        text = pluralStringResource(
                                            R.plurals.song_count_plural,
                                            songs.size,
                                            songs.size
                                        ),
                                        style = typography.xs.secondary.semiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(0.2825f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            if (playlist.sortable) {
                                HeaderSongSortBy(
                                    sortBy = sortBy,
                                    setSortBy = { sortBy = it },
                                    sortOrder = sortOrder,
                                    setSortOrder = { sortOrder = it }
                                )
                            }

                            HeaderIconButton(
                                icon = R.drawable.ellipsis_horizontal,
                                color = colorPalette.text,
                                modifier = Modifier.size(24.dp),
                                onClick = {
                                    menuState.display {
                                        Menu {
                                            MenuEntry(
                                                icon = R.drawable.download,
                                                text = stringResource(R.string.pre_cache),
                                                onClick = {
                                                    menuState.hide()
                                                    filteredSongs.forEach { song ->
                                                        PrecacheService.scheduleCache(context, song.asMediaItem)
                                                    }
                                                }
                                            )
                                            playlist.browseId?.let { browseId ->
                                                MenuEntry(
                                                    icon = R.drawable.sync,
                                                    text = stringResource(R.string.sync),
                                                    enabled = !loading,
                                                    onClick = {
                                                        menuState.hide()
                                                        coroutineScope.launch {
                                                            loading = true
                                                            sync(playlist, browseId)
                                                            loading = false
                                                        }
                                                    }
                                                )

                                                songs.firstOrNull()?.id?.let { firstSongId ->
                                                    MenuEntry(
                                                        icon = R.drawable.play,
                                                        text = stringResource(R.string.watch_playlist_on_youtube),
                                                        onClick = {
                                                            menuState.hide()
                                                            binder?.player?.pause()
                                                            uriHandler.openUri(
                                                                "https://youtube.com/watch?v=$firstSongId&list=${
                                                                    playlist.browseId.drop(2)
                                                                }"
                                                            )
                                                        }
                                                    )

                                                    MenuEntry(
                                                        icon = R.drawable.musical_notes,
                                                        text = stringResource(R.string.open_in_youtube_music),
                                                        onClick = {
                                                            menuState.hide()
                                                            binder?.player?.pause()
                                                            if (
                                                                !launchYouTubeMusic(
                                                                    context = context,
                                                                    endpoint = "watch?v=$firstSongId&list=${
                                                                        playlist.browseId.drop(2)
                                                                    }"
                                                                )
                                                            ) context.toast(
                                                                context.getString(R.string.youtube_music_not_installed)
                                                            )
                                                        }
                                                    )
                                                }
                                            }

                                            MenuEntry(
                                                icon = R.drawable.pencil,
                                                text = stringResource(R.string.rename),
                                                onClick = {
                                                    menuState.hide()
                                                    isRenaming = true
                                                }
                                            )

                                            MenuEntry(
                                                icon = R.drawable.trash,
                                                text = stringResource(R.string.delete),
                                                onClick = {
                                                    menuState.hide()
                                                    isDeleting = true
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        if (!isLandscape) thumbnailContent()
                    }
                }

                itemsIndexed(
                    items = filteredSongs,
                    key = { index, song -> "${song.id}-$index" },
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
                            .draggedItem(reorderingState = reorderingState, index = index)
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
                                            InPlaylistMediaItemMenu(
                                                playlistId = playlist.id,
                                                song = song,
                                                onDismiss = menuState::hide
                                            )
                                        }
                                    },
                                    onClick = {
                                        binder?.stopRadio()
                                        binder?.player?.forcePlayAtIndex(
                                            items = filteredSongs.map { it.asMediaItem },
                                            index = index
                                        )
                                    }
                                )
                                .animateItem(),
                            song = song,
                            thumbnailSize = Dimensions.thumbnails.song,
                            trailingContent = {
                                if (sortBy == SongSortBy.Position && filter == null) {
                                    ReorderHandle(
                                        reorderingState = reorderingState,
                                        index = index
                                    )
                                }
                            },
                            clip = !reorderingState.isDragging,
                            isPlaying = playing && currentMediaId == song.id
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = 16.dp
                )
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues())
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                AnimatedVisibility(
                    visible = !reorderingState.isDragging && filteredSongs.isNotEmpty(),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = colorPalette.background2,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .combinedClickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = {
                                    binder?.player?.enqueue(filteredSongs.map { it.asMediaItem })
                                }
                            )
                            .padding(18.dp)
                            .size(26.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.enqueue),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colorPalette.text),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    AnimatedVisibility(
                        visible = remember {
                            derivedStateOf {
                                !reorderingState.isDragging &&
                                    (lazyListState.firstVisibleItemIndex > 0 ||
                                        lazyListState.firstVisibleItemScrollOffset > 0)
                            }
                        }.value,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = colorPalette.background2,
                                    shape = CircleShape
                                )
                                .combinedClickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = {
                                        coroutineScope.launch {
                                            lazyListState.animateScrollToItem(0)
                                        }
                                    }
                                )
                                .padding(18.dp)
                                .size(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.chevron_up),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(colorPalette.text),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = !reorderingState.isDragging,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = colorPalette.background2,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .combinedClickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = {
                                        if (filteredSongs.isEmpty()) return@combinedClickable
                                        binder?.stopRadio()
                                        binder?.player?.forcePlayFromBeginning(
                                            filteredSongs.shuffled().map { it.asMediaItem }
                                        )
                                    }
                                )
                                .padding(18.dp)
                                .size(26.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.shuffle),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(colorPalette.text),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun sync(
    playlist: Playlist,
    browseId: String
) = runCatching {
    Innertube.playlistPage(
        BrowseBody(browseId = browseId)
    )?.completed()?.getOrNull()?.let { remotePlaylist ->
        transaction {
            Database.instance.clearPlaylist(playlist.id)

            remotePlaylist.songsPage
                ?.items
                ?.map { it.asMediaItem }
                ?.onEach { Database.instance.insert(it) }
                ?.mapIndexed { position, mediaItem ->
                    SongPlaylistMap(
                        songId = mediaItem.mediaId,
                        playlistId = playlist.id,
                        position = position
                    )
                }
                ?.let(Database.instance::insertSongPlaylistMaps)
        }
    }
}.onFailure {
    if (it is CancellationException) throw it
    it.printStackTrace()
}
