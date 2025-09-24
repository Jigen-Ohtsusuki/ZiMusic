package it.vfsfitvnm.vimusic.ui.screens.player

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.vfsfitvnm.core.ui.LocalAppearance
import it.vfsfitvnm.providers.lyricsplus.LyricsPlusSyncManager
import it.vfsfitvnm.vimusic.service.PlayerService
import it.vfsfitvnm.vimusic.ui.modifiers.verticalFadingEdge
import it.vfsfitvnm.vimusic.utils.bold
import it.vfsfitvnm.vimusic.utils.center
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.coroutineScope

private const val STRETCHED_WORD_THRESHOLD_MS = 1270L

@RequiresApi(Build.VERSION_CODES.P)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordSyncedLyrics(
    manager: LyricsPlusSyncManager,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    binder: PlayerService.Binder?
) {
    val (colorPalette, typography) = LocalAppearance.current
    val baseStyle = typography.l.center.bold
    val density = LocalDensity.current

    val textPaint = remember {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = with(density) { baseStyle.fontSize.toPx() }
            this.typeface = Typeface.create(Typeface.DEFAULT, baseStyle.fontWeight?.weight ?: FontWeight.Normal.weight, false)
        }
    }

    val lazyListState = rememberLazyListState()
    val lyrics = manager.getLyrics()

    val currentLineIndex by manager.currentLineIndex.collectAsState()
    val currentPosition by manager.currentPosition.collectAsState()
    val previousLineIndex = remember { mutableIntStateOf(-2) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            manager.forceSync()
        }
    }

    LaunchedEffect(isVisible, currentLineIndex) {
        if (isVisible && currentLineIndex >= 0 && !lazyListState.isScrollInProgress) {
            val targetIndex = currentLineIndex.coerceAtLeast(0)
            if (targetIndex >= lyrics.size) return@LaunchedEffect

            val layoutInfo = lazyListState.layoutInfo
            val viewportCenter = layoutInfo.viewportSize.height / 2

            val targetItemInfo = layoutInfo.visibleItemsInfo.find { it.index == targetIndex + 1 }

            coroutineScope {
                if (targetItemInfo != null) {
                    val itemCenter = targetItemInfo.offset + targetItemInfo.size / 2
                    val scrollAmount = (itemCenter - viewportCenter).toFloat()

                    lazyListState.animateScrollBy(
                        value = scrollAmount,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                } else {
                    lazyListState.animateScrollToItem(
                        index = targetIndex + 1,
                        scrollOffset = -viewportCenter
                    )
                }
            }
            previousLineIndex.intValue = currentLineIndex
        }
    }


    LazyColumn(
        state = lazyListState,
        userScrollEnabled = true,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .verticalFadingEdge()
            .fillMaxWidth()
    ) {
        item {
            val viewportHeightDp = with(density) {
                lazyListState.layoutInfo.viewportSize.height.toDp()
            }
            Spacer(modifier = Modifier.height(viewportHeightDp / 2))
        }

        itemsIndexed(lyrics.toImmutableList()) { lineIndex, line ->
            val endTimeMs = line.startTimeMs + line.durationMs
            val isActiveLine = (currentPosition in line.startTimeMs..endTimeMs) || (lineIndex == currentLineIndex)

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { binder?.player?.seekTo(line.startTimeMs) },
                horizontalArrangement = Arrangement.Center
            ) {
                line.words.forEach { word ->
                    val animatedLineColor by animateColorAsState(
                        targetValue = if (isActiveLine) colorPalette.text else colorPalette.textDisabled,
                        animationSpec = tween(durationMillis = 300),
                        label = "lineColorAnimation"
                    )

                    val textBrush = if (isActiveLine) {
                        val timeProgress = when {
                            currentPosition < word.startTimeMs -> 0f
                            currentPosition >= (word.startTimeMs + word.durationMs) -> 1f
                            else -> if (word.durationMs > 0) {
                                ((currentPosition - word.startTimeMs) / word.durationMs.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                        }

                        val wipeWidthDp = 16.dp
                        val wipeWidthPx = with(density) { wipeWidthDp.toPx() }
                        val wordWidthPx = textPaint.measureText(word.text)
                        val wipeFraction = if (wordWidthPx > 0) wipeWidthPx / wordWidthPx else 0f

                        val totalTravel = 1f + wipeFraction
                        val wipeCenter = (timeProgress * totalTravel) - (wipeFraction / 2)

                        val transitionStart = wipeCenter - (wipeFraction / 2)
                        val transitionEnd = wipeCenter + (wipeFraction / 2)

                        val activeColor = animatedLineColor
                        val upcomingColor = animatedLineColor.copy(alpha = 0.6f)

                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                transitionStart.coerceIn(0f, 1f) to activeColor,
                                transitionEnd.coerceIn(0f, 1f) to upcomingColor
                            )
                        )
                    } else {
                        Brush.horizontalGradient(colors = listOf(animatedLineColor, animatedLineColor))
                    }

                    val isStretchedWord = word.durationMs > STRETCHED_WORD_THRESHOLD_MS
                    val isWordCurrentlyBeingSung = currentPosition in word.startTimeMs..(word.startTimeMs + word.durationMs)

                    val glowIntensity = if (isStretchedWord && isWordCurrentlyBeingSung) {
                        val wordProgress = if (word.durationMs > 0) {
                            ((currentPosition - word.startTimeMs) / word.durationMs.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        val fadeInOut = 1f - kotlin.math.abs(wordProgress - 0.5f) * 2f
                        fadeInOut.coerceIn(0f, 1f)
                    } else 0f

                    val animatedGlowRadius by animateFloatAsState(
                        targetValue = glowIntensity * 16f,
                        animationSpec = tween(durationMillis = 300),
                        label = "glowRadiusAnimation"
                    )

                    val wordStyle = baseStyle.merge(TextStyle(brush = textBrush))

                    BasicText(
                        text = word.text,
                        style = wordStyle.copy(
                            shadow = if (animatedGlowRadius > 0f) {
                                Shadow(
                                    color = animatedLineColor.copy(alpha = 0.425f),
                                    blurRadius = animatedGlowRadius
                                )
                            } else null
                        )
                    )
                }
            }
        }

        item {
            val viewportHeightDp = with(density) {
                lazyListState.layoutInfo.viewportSize.height.toDp()
            }
            Spacer(modifier = Modifier.height(viewportHeightDp / 2))
        }
    }
}
