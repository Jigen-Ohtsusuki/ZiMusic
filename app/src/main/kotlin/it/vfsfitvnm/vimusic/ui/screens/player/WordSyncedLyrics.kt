package it.vfsfitvnm.vimusic.ui.screens.player

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
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
            val viewHeight = lazyListState.layoutInfo.viewportSize.height
            val centerOffset = viewHeight / 2

            if (targetIndex < lyrics.size) {
                lazyListState.animateScrollToItem(
                    index = targetIndex + 1,
                    scrollOffset = -centerOffset
                )
                previousLineIndex.intValue = currentLineIndex
            }
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
            val isActiveLine = currentPosition in line.startTimeMs..endTimeMs

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { binder?.player?.seekTo(line.startTimeMs) },
                horizontalArrangement = Arrangement.Center
            ) {
                line.words.forEach { word ->
                    val wordStyle: TextStyle = if (isActiveLine) {
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

                        val activeColor = colorPalette.text
                        val upcomingColor = colorPalette.text.copy(alpha = 0.6f)

                        val textBrush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                transitionStart.coerceIn(0f, 1f) to activeColor,
                                transitionEnd.coerceIn(0f, 1f) to upcomingColor
                            )
                        )
                        baseStyle.merge(TextStyle(brush = textBrush))
                    } else {
                        val animatedColor by animateColorAsState(
                            targetValue = colorPalette.textDisabled,
                            animationSpec = tween(durationMillis = 300),
                            label = "inactiveLineColor"
                        )
                        baseStyle.copy(color = animatedColor)
                    }

                    BasicText(
                        text = word.text,
                        style = wordStyle
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
