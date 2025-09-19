package it.vfsfitvnm.vimusic.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Left
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Right
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import it.vfsfitvnm.vimusic.LocalPlayerServiceBinder
import it.vfsfitvnm.vimusic.R
import it.vfsfitvnm.vimusic.ui.modifiers.onSwipe
import it.vfsfitvnm.vimusic.utils.forceSeekToNext
import it.vfsfitvnm.vimusic.utils.forceSeekToPrevious
import it.vfsfitvnm.vimusic.utils.thumbnail
import it.vfsfitvnm.vimusic.utils.windowState
import it.vfsfitvnm.core.ui.Dimensions
import it.vfsfitvnm.core.ui.LocalAppearance
import it.vfsfitvnm.core.ui.utils.px
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun Thumbnail(
    onTap: () -> Unit,
    onDoubleTap: (Long?) -> Unit,
    likedAt: Long?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
) {
    val binder = LocalPlayerServiceBinder.current
    val (colorPalette, _, _, thumbnailShape) = LocalAppearance.current

    val (window, _) = windowState()

    val coroutineScope = rememberCoroutineScope()
    val transitionState = remember { SeekableTransitionState(false) }
    val transition = rememberTransition(transitionState)
    val opacity by transition.animateFloat(label = "heartOpacity") { if (it) 1f else 0f }
    val scale by transition.animateFloat(
        label = "heartScale",
        transitionSpec = {
            spring(dampingRatio = Spring.DampingRatioLowBouncy)
        }
    ) { if (it) 1f else 0f }

    AnimatedContent(
        targetState = window,
        transitionSpec = {
            val duration = 500
            val initial = initialState
            val target = targetState

            if (initial == null || target == null) return@AnimatedContent ContentTransform(
                targetContentEnter = fadeIn(tween(duration)),
                initialContentExit = fadeOut(tween(duration)),
                sizeTransform = null
            )

            val sizeTransform = SizeTransform(clip = false) { _, _ ->
                tween(durationMillis = duration, delayMillis = duration)
            }

            val direction = if (target.firstPeriodIndex < initial.firstPeriodIndex) Right else Left

            ContentTransform(
                targetContentEnter = slideIntoContainer(direction, tween(duration)) +
                    fadeIn(tween(duration)) +
                    scaleIn(tween(duration), 0.85f),
                initialContentExit = slideOutOfContainer(direction, tween(duration)) +
                    fadeOut(tween(duration)) +
                    scaleOut(tween(duration), 0.85f),
                sizeTransform = sizeTransform
            )
        },
        modifier = modifier.onSwipe(
            onSwipeLeft = {
                binder?.player?.forceSeekToNext()
            },
            onSwipeRight = {
                binder?.player?.forceSeekToPrevious(seekToStart = false)
            }
        ),
        contentAlignment = Alignment.Center,
        label = "ThumbnailSongChange"
    ) { currentWindow ->
        val shadowElevation by animateDpAsState(
            targetValue = if (window == currentWindow) 8.dp else 0.dp,
            animationSpec = tween(
                durationMillis = 500,
                easing = LinearEasing
            ),
            label = "shadow"
        )

        if (currentWindow != null) Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(thumbnailShape)
                .shadow(
                    elevation = shadowElevation,
                    shape = thumbnailShape,
                    clip = false
                )
        ) {
            AsyncImage(
                model = currentWindow.mediaItem.mediaMetadata.artworkUri
                    ?.thumbnail((Dimensions.thumbnails.player.song - 64.dp).px),
                placeholder = painterResource(id = R.drawable.ic_launcher_foreground),
                error = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .pointerInput(likedAt) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                val newLikedAt =
                                    if (likedAt == null) System.currentTimeMillis() else null
                                onDoubleTap(newLikedAt)

                                coroutineScope.launch {
                                    val spec = tween<Float>(durationMillis = 500)
                                    transitionState.animateTo(true, spec)
                                    transitionState.animateTo(false, spec)
                                }
                            }
                        )
                    }
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .background(colorPalette.background0)
                    .animateContentSize()
            )

            Image(
                painter = painterResource(R.drawable.heart),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.accent),
                modifier = Modifier
                    .fillMaxSize(0.5f)
                    .aspectRatio(1f)
                    .align(Alignment.Center)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        alpha = opacity,
                        shadowElevation = 8.dp.px.toFloat()
                    )
            )
        }
    }
}
