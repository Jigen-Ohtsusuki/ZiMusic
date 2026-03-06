package dev.jigen.zimusic.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import dev.jigen.zimusic.utils.thumbnail

@Composable
fun AuroraBackground(
    artworkUri: android.net.Uri?,
    fallbackColor: Color,
    modifier: Modifier = Modifier
) {
    var palette by remember { mutableStateOf<Palette?>(null) }
    val context = LocalContext.current

    LaunchedEffect(artworkUri) {
        if (artworkUri == null) return@LaunchedEffect
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(artworkUri.thumbnail(200))
            .allowHardware(false)
            .scale(Scale.FILL)
            .build()

        val result = (loader.execute(request) as? SuccessResult)?.image
        val bitmap = result?.asDrawable(context.resources)?.toBitmap()

        if (bitmap != null) {
            palette = Palette.from(bitmap).maximumColorCount(16).generate()
        }
    }

    // Extract the most populated colors directly instead of relying on "Vibrant" or "Muted"
    val swatches = remember(palette) {
        palette?.swatches?.sortedByDescending { it.population } ?: emptyList()
    }

    val dominantColor = swatches.getOrNull(0)?.rgb ?: palette?.getDominantColor(fallbackColor.toArgb()) ?: fallbackColor.toArgb()
    val secondaryColor = swatches.getOrNull(1)?.rgb ?: dominantColor
    val tertiaryColor = swatches.getOrNull(2)?.rgb ?: secondaryColor
    val quaternaryColor = swatches.getOrNull(3)?.rgb ?: tertiaryColor

    val color1 by animateColorAsState(
        targetValue = Color(dominantColor),
        animationSpec = tween(1000), label = "color1"
    )
    val color2 by animateColorAsState(
        targetValue = Color(secondaryColor),
        animationSpec = tween(1000), label = "color2"
    )
    val color3 by animateColorAsState(
        targetValue = Color(tertiaryColor),
        animationSpec = tween(1000), label = "color3"
    )
    val color4 by animateColorAsState(
        targetValue = Color(quaternaryColor),
        animationSpec = tween(1000), label = "color4"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "aurora_movement")

    val move1 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(28000, easing = LinearEasing), RepeatMode.Reverse),
        label = "m1"
    )
    val move2 by infiniteTransition.animateFloat(
        initialValue = 0.1f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(35000, easing = LinearEasing), RepeatMode.Reverse),
        label = "m2"
    )
    val move3 by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(42000, easing = LinearEasing), RepeatMode.Reverse),
        label = "m3"
    )

    val rotate by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(40000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotate"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val blurRadius = 120.dp.toPx()
                    renderEffect = RenderEffect
                        .createBlurEffect(blurRadius, blurRadius, Shader.TileMode.MIRROR)
                        .asComposeRenderEffect()

                    rotationZ = rotate
                    scaleX = 1.4f
                    scaleY = 1.4f
                }
        ) {
            val width = size.width
            val height = size.height

            drawRect(color = color1.copy(alpha = 0.5f))

            drawCircle(
                color = color2,
                radius = width * 0.6f,
                center = Offset(
                    x = width * move1,
                    y = height * move2
                ),
                alpha = 0.7f
            )

            drawCircle(
                color = color3,
                radius = width * 0.7f,
                center = Offset(
                    x = width * (1 - move2),
                    y = height * move3
                ),
                alpha = 0.6f
            )

            drawCircle(
                color = color4,
                radius = width * 0.5f,
                center = Offset(
                    x = width * move3,
                    y = height * (1 - move1)
                ),
                alpha = 0.7f
            )

            drawCircle(
                color = color1,
                radius = width * 0.8f,
                center = Offset(
                    x = width * 0.2f,
                    y = height * 0.8f
                ),
                alpha = 0.6f
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )
    }
}
