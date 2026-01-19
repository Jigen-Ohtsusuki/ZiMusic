package dev.jigen.zimusic.ui.screens.player

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.jigen.zimusic.Database
import dev.jigen.zimusic.LocalPlayerServiceBinder
import dev.jigen.zimusic.R
import dev.jigen.zimusic.models.ui.toUiMedia
import dev.jigen.zimusic.preferences.PlayerPreferences
import dev.jigen.zimusic.query
import dev.jigen.zimusic.service.PlayerService
import dev.jigen.zimusic.transaction
import dev.jigen.zimusic.ui.components.BottomSheet
import dev.jigen.zimusic.ui.components.BottomSheetState
import dev.jigen.zimusic.ui.components.LocalMenuState
import dev.jigen.zimusic.ui.components.rememberBottomSheetState
import dev.jigen.zimusic.ui.components.themed.BaseMediaItemMenu
import dev.jigen.zimusic.ui.components.themed.IconButton
import dev.jigen.zimusic.ui.components.themed.SecondaryTextButton
import dev.jigen.zimusic.ui.components.themed.SliderDialog
import dev.jigen.zimusic.ui.components.themed.SliderDialogBody
import dev.jigen.zimusic.ui.modifiers.onSwipe
import dev.jigen.zimusic.utils.DisposableListener
import dev.jigen.zimusic.utils.forceSeekToNext
import dev.jigen.zimusic.utils.forceSeekToPrevious
import dev.jigen.zimusic.utils.positionAndDurationState
import dev.jigen.zimusic.utils.rememberEqualizerLauncher
import dev.jigen.zimusic.utils.seamlessPlay
import dev.jigen.zimusic.utils.secondary
import dev.jigen.zimusic.utils.semiBold
import dev.jigen.zimusic.utils.shouldBePlaying
import dev.jigen.zimusic.utils.thumbnail
import dev.jigen.compose.persist.PersistMapCleanup
import dev.jigen.compose.routing.OnGlobalRoute
import dev.jigen.core.ui.Dimensions
import dev.jigen.core.ui.LocalAppearance
import dev.jigen.core.ui.ThumbnailRoundness
import dev.jigen.core.ui.collapsedPlayerProgressBar
import dev.jigen.core.ui.utils.isLandscape
import dev.jigen.core.ui.utils.px
import dev.jigen.core.ui.utils.roundedShape
import dev.jigen.core.ui.utils.songBundle
import dev.jigen.providers.innertube.models.NavigationEndpoint
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.absoluteValue

private enum class PlayerContentState {
    Thumbnail, Lyrics
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
fun Player(
    layoutState: BottomSheetState,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp
    ),
    windowInsets: WindowInsets = WindowInsets.systemBars
) = with(PlayerPreferences) {
    val menuState = LocalMenuState.current
    val (colorPalette, typography, thumbnailCornerSize) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current

    PersistMapCleanup(prefix = "queue/suggestions")

    var mediaItem by remember(binder) {
        mutableStateOf(
            value = binder?.player?.currentMediaItem,
            policy = neverEqualPolicy()
        )
    }
    var shouldBePlaying by remember(binder) { mutableStateOf(binder?.player?.shouldBePlaying == true) }

    var likedAt by remember(mediaItem) {
        mutableStateOf(
            value = null,
            policy = object : SnapshotMutationPolicy<Long?> {
                override fun equivalent(a: Long?, b: Long?): Boolean {
                    mediaItem?.mediaId?.let { mediaId ->
                        query {
                            Database.instance.like(mediaId, b)
                        }
                    }
                    return a == b
                }
            }
        )
    }

    LaunchedEffect(mediaItem) {
        mediaItem?.mediaId?.let { mediaId ->
            Database.instance
                .likedAt(mediaId)
                .distinctUntilChanged()
                .collect { likedAt = it }
        }
    }

    binder?.player.DisposableListener {
        object : Player.Listener {
            override fun onMediaItemTransition(newMediaItem: MediaItem?, reason: Int) {
                mediaItem = newMediaItem
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                shouldBePlaying = player.shouldBePlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                shouldBePlaying = player.shouldBePlaying
            }
        }
    }

    val (position, duration) = binder?.player.positionAndDurationState()
    val metadata = remember(mediaItem) { mediaItem?.mediaMetadata }
    val extras = remember(metadata) { metadata?.extras?.songBundle }

    val horizontalBottomPaddingValues = windowInsets
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        .asPaddingValues()

    OnGlobalRoute { if (layoutState.expanded) layoutState.collapseSoft() }

    if (mediaItem != null && binder != null) BottomSheet(
        state = layoutState,
        modifier = modifier.fillMaxSize(),
        onDismiss = {
            dismissPlayer(binder)
            layoutState.dismissSoft()
        },
        backHandlerEnabled = !menuState.isDisplayed,
        collapsedContent = { innerModifier ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .let { modifier ->
                        if (horizontalSwipeToClose) modifier.onSwipe(
                            animateOffset = true,
                            onSwipeOut = { animationJob ->
                                dismissPlayer(binder)
                                animationJob.join()
                                layoutState.dismissSoft()
                            }
                        ) else modifier
                    }
                    .fillMaxSize()
                    .clip(shape)
                    .background(colorPalette.background1)
                    .drawBehind {
                        drawRect(
                            color = colorPalette.collapsedPlayerProgressBar,
                            topLeft = Offset.Zero,
                            size = Size(
                                width = runCatching {
                                    size.width * (position.toFloat() / duration.absoluteValue)
                                }.getOrElse { 0f },
                                height = size.height
                            )
                        )
                    }
                    .then(innerModifier)
                    .padding(horizontalBottomPaddingValues)
            ) {
                Spacer(modifier = Modifier.width(2.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.height(Dimensions.items.collapsedPlayerHeight)
                ) {
                    AsyncImage(
                        model = metadata?.artworkUri?.thumbnail(Dimensions.thumbnails.song.px),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .clip(thumbnailCornerSize.coerceAtMost(ThumbnailRoundness.Heavy.dp).roundedShape)
                            .background(colorPalette.background0)
                            .size(48.dp)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .height(Dimensions.items.collapsedPlayerHeight)
                        .weight(1f)
                ) {
                    AnimatedContent(
                        targetState = metadata?.title?.toString().orEmpty(),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = ""
                    ) { text ->
                        BasicText(
                            text = text,
                            style = typography.xs.semiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    AnimatedVisibility(visible = metadata?.artist != null) {
                        AnimatedContent(
                            targetState = metadata?.artist?.toString().orEmpty(),
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = ""
                        ) { text ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                BasicText(
                                    text = text,
                                    style = typography.xs.semiBold.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                AnimatedVisibility(visible = extras?.explicit == true) {
                                    Image(
                                        painter = painterResource(R.drawable.explicit),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(colorPalette.text),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(Dimensions.items.collapsedPlayerHeight)
                ) {
                    AnimatedVisibility(visible = isShowingPrevButtonCollapsed) {
                        IconButton(
                            icon = R.drawable.play_skip_back,
                            color = colorPalette.text,
                            onClick = { binder.player.forceSeekToPrevious() },
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .size(20.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clickable(
                                onClick = {
                                    if (shouldBePlaying) binder.player.pause()
                                    else {
                                        if (binder.player.playbackState == Player.STATE_IDLE) binder.player.prepare()
                                        binder.player.play()
                                    }
                                },
                                indication = ripple(bounded = false),
                                interactionSource = remember { MutableInteractionSource() }
                            )
                            .clip(CircleShape)
                    ) {
                        AnimatedPlayPauseButton(
                            playing = shouldBePlaying,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .size(23.dp)
                        )
                    }

                    IconButton(
                        icon = R.drawable.play_skip_forward,
                        color = colorPalette.text,
                        onClick = { binder.player.forceSeekToNext() },
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                            .size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))
            }
        }
    ) {
        var playerContentState by rememberSaveable { mutableStateOf(PlayerContentState.Thumbnail) }

        // When the song changes, reset to thumbnail view
        LaunchedEffect(mediaItem?.mediaId) {
            playerContentState = PlayerContentState.Thumbnail
        }

        val playerBottomSheetState = rememberBottomSheetState(
            dismissedBound = 64.dp + horizontalBottomPaddingValues.calculateBottomPadding(),
            expandedBound = layoutState.expandedBound
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0.5f to colorPalette.background1,
                        1f to colorPalette.background0
                    )
                )
        ) {
            val containerModifier = Modifier
                .padding(
                    windowInsets
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        .asPaddingValues()
                )
                .padding(bottom = playerBottomSheetState.collapsedBound)

            val controlsContent: @Composable (modifier: Modifier) -> Unit = { innerModifier ->
                Controls(
                    media = mediaItem?.toUiMedia(duration),
                    binder = binder,
                    likedAt = likedAt,
                    setLikedAt = { likedAt = it },
                    shouldBePlaying = shouldBePlaying,
                    position = position,
                    modifier = innerModifier
                )
            }

            if (isLandscape) Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = containerModifier.padding(top = 32.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(0.66f)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    val currentMediaItem = mediaItem
                    val currentLikedAt = likedAt

                    AnimatedContent(
                        targetState = playerContentState,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        },
                        label = "PlayerContent"
                    ) { state ->
                        when (state) {
                            PlayerContentState.Thumbnail -> {
                                Thumbnail(
                                    onTap = { playerContentState = PlayerContentState.Lyrics },
                                    onDoubleTap = { newLikedAt -> likedAt = newLikedAt },
                                    likedAt = currentLikedAt,
                                    modifier = Modifier
                                        .nestedScroll(layoutState.preUpPostDownNestedScrollConnection)
                                )
                            }
                            PlayerContentState.Lyrics -> {
                                if (currentMediaItem != null) {
                                    Lyrics(
                                        mediaId = currentMediaItem.mediaId,
                                        isDisplayed = true,
                                        onDismiss = { playerContentState = PlayerContentState.Thumbnail },
                                        mediaMetadataProvider = { currentMediaItem.mediaMetadata },
                                        durationProvider = { binder.player.duration },
                                        ensureSongInserted = { transaction { Database.instance.insert(currentMediaItem) } },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(thumbnailCornerSize.roundedShape),
                                    )
                                }
                            }
                        }
                    }
                }

                controlsContent(
                    Modifier
                        .padding(vertical = 8.dp)
                        .fillMaxHeight()
                        .weight(1f)
                )
            } else Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = containerModifier.padding(top = 54.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1.25f)
                        .padding(horizontal = 32.dp, vertical = 8.dp)
                ) {
                    val currentMediaItem = mediaItem
                    val currentLikedAt = likedAt

                    AnimatedContent(
                        targetState = playerContentState,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
                        },
                        label = "PlayerContent"
                    ) { state ->
                        when (state) {
                            PlayerContentState.Thumbnail -> {
                                Thumbnail(
                                    onTap = { playerContentState = PlayerContentState.Lyrics },
                                    onDoubleTap = { newLikedAt -> likedAt = newLikedAt },
                                    likedAt = currentLikedAt,
                                    modifier = Modifier
                                        .nestedScroll(layoutState.preUpPostDownNestedScrollConnection)
                                )
                            }
                            PlayerContentState.Lyrics -> {
                                if (currentMediaItem != null) {
                                    Lyrics(
                                        mediaId = currentMediaItem.mediaId,
                                        isDisplayed = true,
                                        onDismiss = { playerContentState = PlayerContentState.Thumbnail },
                                        mediaMetadataProvider = { currentMediaItem.mediaMetadata },
                                        durationProvider = { binder.player.duration },
                                        ensureSongInserted = { transaction { Database.instance.insert(currentMediaItem) } },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(thumbnailCornerSize.roundedShape),
                                    )
                                }
                            }
                        }
                    }
                }

                controlsContent(
                    Modifier
                        .padding(vertical = 8.dp)
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }

        // REMOVED: Speed/Pitch Slider Dialog Logic

        var boostDialogOpen by rememberSaveable { mutableStateOf(false) }

        if (boostDialogOpen) {
            fun submit(state: Float) = transaction {
                mediaItem?.mediaId?.let { mediaId ->
                    Database.instance.setLoudnessBoost(
                        songId = mediaId,
                        loudnessBoost = state.takeUnless { it == 0f }
                    )
                }
            }

            SliderDialog(
                onDismiss = {},
                title = stringResource(R.string.volume_boost)
            ) {
                SliderDialogBody(
                    provideState = {
                        val state = remember { mutableFloatStateOf(0f) }

                        LaunchedEffect(mediaItem) {
                            mediaItem?.mediaId?.let { mediaId ->
                                Database.instance
                                    .loudnessBoost(mediaId)
                                    .distinctUntilChanged()
                                    .collect { boost: Float? -> state.floatValue = boost ?: 0f }
                            }
                        }

                        state
                    },
                    onSlideComplete = { submit(it) },
                    min = -20f,
                    max = 20f,
                    toDisplay = { stringResource(R.string.format_db, "%.2f".format(it)) }
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    SecondaryTextButton(
                        text = stringResource(R.string.reset),
                        onClick = { submit(0f) }
                    )
                }
            }
        }
        Queue(
            layoutState = playerBottomSheetState,
            binder = binder,
            beforeContent = {
                if (playerLayout == PlayerPreferences.PlayerLayout.New) IconButton(
                    onClick = { trackLoopEnabled = !trackLoopEnabled },
                    icon = R.drawable.infinite,
                    enabled = trackLoopEnabled,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .size(20.dp)
                ) else Spacer(modifier = Modifier.width(20.dp))
            },
            afterContent = {
                IconButton(
                    icon = R.drawable.ellipsis_horizontal,
                    color = colorPalette.text,
                    onClick = {
                        mediaItem?.let {
                            menuState.display {
                                PlayerMenu(
                                    onDismiss = menuState::hide,
                                    mediaItem = it,
                                    binder = binder,
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .size(20.dp)
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            shape = shape
        )
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun PlayerMenu(
    binder: PlayerService.Binder,
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
    onShowNormalizationDialog: (() -> Unit)? = null
) {
    val launchEqualizer by rememberEqualizerLauncher(audioSessionId = { binder.player.audioSessionId })

    BaseMediaItemMenu(
        mediaItem = mediaItem,
        onStartRadio = {
            binder.stopRadio()
            binder.player.seamlessPlay(mediaItem)
            binder.setupRadio(NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId))
        },
        onGoToEqualizer = launchEqualizer,
        onShowSleepTimer = {},
        onDismiss = onDismiss,
        onShowSpeedDialog = null,
        onShowNormalizationDialog = onShowNormalizationDialog
    )
}

private fun dismissPlayer(binder: PlayerService.Binder) {
    binder.stopRadio()
    binder.player.clearMediaItems()
}
