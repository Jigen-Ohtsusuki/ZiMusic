package dev.jigen.zimusic.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import dev.jigen.core.ui.LocalAppearance
import dev.jigen.zimusic.models.ui.UiMedia
import dev.jigen.zimusic.preferences.PlayerPreferences
import dev.jigen.zimusic.service.PlayerService
import dev.jigen.zimusic.utils.formatAsDuration
import dev.jigen.zimusic.utils.semiBold
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun SeekBar(
    binder: PlayerService.Binder,
    position: Long,
    media: UiMedia,
    modifier: Modifier = Modifier,
    color: Color = LocalAppearance.current.colorPalette.text,
    alwaysShowDuration: Boolean = false
) {
    var scrubbingPosition by remember(media) { mutableStateOf<Long?>(null) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }

    val animatedPosition = remember { Animatable(position.toFloat()) }
    val scope = rememberCoroutineScope()
    val duration = media.duration
    val range = 0L..duration

    LaunchedEffect(position, scrubbingPosition) {
        if (scrubbingPosition != null) {
            animatedPosition.snapTo(scrubbingPosition!!.toFloat())
            return@LaunchedEffect
        }

        if (System.currentTimeMillis() - lastInteractionTime < 350L) {
            return@LaunchedEffect
        }

        val target = position.toFloat()
        if (abs(target - animatedPosition.value) > 2000) {
            animatedPosition.snapTo(target)
        } else {
            animatedPosition.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 1000, easing = LinearEasing)
            )
        }
    }

    var isDragging by remember { mutableStateOf(false) }

    val onSeekStart: (Long) -> Unit = { scrubbingPosition = it }
    val onSeek: (Long) -> Unit = { delta ->
        scrubbingPosition = if (duration == C.TIME_UNSET) null
        else scrubbingPosition?.let { (it + delta).coerceIn(range) }
    }
    val onSeekEnd = {
        scrubbingPosition?.let(binder.player::seekTo)
        scrubbingPosition = null
        lastInteractionTime = System.currentTimeMillis()
    }

    val onTap: (Long) -> Unit = { time ->
        lastInteractionTime = System.currentTimeMillis()
        scope.launch {
            animatedPosition.animateTo(
                targetValue = time.toFloat(),
                animationSpec = tween(durationMillis = 150, easing = LinearEasing)
            )
        }
        binder.player.seekTo(time)
    }

    val transition = updateTransition(targetState = isDragging, label = "")
    val trackHeight by transition.animateDp(label = "") { if (it) 12.dp else 6.dp }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(range) {
                    if (range.endInclusive < range.start) return@pointerInput
                    detectDrags(
                        setIsDragging = { },
                        range = range,
                        onSeekStart = onSeekStart,
                        onSeek = onSeek,
                        onSeekEnd = onSeekEnd
                    )
                }
                .pointerInput(range) {
                    detectTaps(
                        range = range,
                        onTap = onTap
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val barHeightPx = trackHeight.toPx()

                val startX = barHeightPx / 2f
                val endX = canvasWidth - barHeightPx / 2f
                val length = endX - startX

                val currentPos = scrubbingPosition ?: animatedPosition.value.toLong()
                val progress = if (duration > 0) (currentPos.toFloat() / duration).coerceIn(0f, 1f) else 0f

                drawLine(
                    color = color.copy(alpha = 0.25f),
                    start = Offset(startX, canvasHeight / 2),
                    end = Offset(endX, canvasHeight / 2),
                    strokeWidth = barHeightPx,
                    cap = StrokeCap.Round
                )

                if (length > 0 && progress > 0) {
                    val pX = startX + (length * progress)
                    drawLine(
                        color = color,
                        start = Offset(startX, canvasHeight / 2),
                        end = Offset(pX, canvasHeight / 2),
                        strokeWidth = barHeightPx,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        Duration(
            position = scrubbingPosition ?: animatedPosition.value.toLong(),
            duration = duration,
            show = alwaysShowDuration || scrubbingPosition != null
        )
    }
}

private suspend fun PointerInputScope.detectDrags(
    setIsDragging: (Boolean) -> Unit,
    range: ClosedRange<Long>,
    onSeekStart: (updated: Long) -> Unit,
    onSeek: (delta: Long) -> Unit,
    onSeekEnd: () -> Unit
) {
    val accumulator = object { var value = 0f }

    detectHorizontalDragGestures(
        onDragStart = { offset ->
            setIsDragging(true)
            onSeekStart((offset.x / size.width * (range.endInclusive - range.start).toFloat() + range.start).roundToLong())
        },
        onHorizontalDrag = { _, delta ->
            accumulator.value += delta / size.width * (range.endInclusive - range.start).toFloat()

            if (accumulator.value !in -1f..1f) {
                val step = accumulator.value.toLong()
                onSeek(step)
                accumulator.value -= step
            }
        },
        onDragEnd = {
            setIsDragging(false)
            onSeekEnd()
        },
        onDragCancel = {
            setIsDragging(false)
            onSeekEnd()
        }
    )
}

private suspend fun PointerInputScope.detectTaps(
    range: ClosedRange<Long>,
    onTap: (Long) -> Unit
) {
    if (range.endInclusive < range.start) return

    detectTapGestures(
        onTap = { offset ->
            val targetTime = (offset.x / size.width * (range.endInclusive - range.start).toFloat() + range.start).roundToLong()
            onTap(targetTime)
        }
    )
}

@Composable
private fun Duration(
    position: Long,
    duration: Long,
    show: Boolean
) = AnimatedVisibility(
    visible = show,
    enter = fadeIn() + expandVertically { -it },
    exit = fadeOut() + shrinkVertically { -it }
) {
    val typography = LocalAppearance.current.typography

    Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            BasicText(
                text = if (PlayerPreferences.showRemaining) "-${formatAsDuration(duration - position)}"
                else formatAsDuration(position),
                style = typography.xxs.semiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    PlayerPreferences.showRemaining = !PlayerPreferences.showRemaining
                }
            )

            if (duration != C.TIME_UNSET) BasicText(
                text = formatAsDuration(duration),
                style = typography.xxs.semiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
