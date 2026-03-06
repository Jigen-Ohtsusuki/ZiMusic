package dev.jigen.core.ui

import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rippleConfiguration(appearance: Appearance = LocalAppearance.current) = remember(
    appearance.colorPalette.text
) {
    val (colorPalette) = appearance

    RippleConfiguration(
        color = if (colorPalette.text.luminance() < 0.5) Color.White
        else colorPalette.text,
        rippleAlpha = DarkThemeRippleAlpha
    )
}

private val DarkThemeRippleAlpha = RippleAlpha(
    pressedAlpha = 0.10f,
    focusedAlpha = 0.12f,
    draggedAlpha = 0.08f,
    hoveredAlpha = 0.04f
)
