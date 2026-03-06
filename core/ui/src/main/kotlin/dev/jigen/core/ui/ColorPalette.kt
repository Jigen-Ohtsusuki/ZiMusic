package dev.jigen.core.ui

import android.graphics.Bitmap
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith

typealias ParcelableColor = @WriteWith<ColorParceler> Color
typealias ParcelableDp = @WriteWith<DpParceler> Dp

@Parcelize
@Immutable
data class ColorPalette(
    val background0: ParcelableColor,
    val background1: ParcelableColor,
    val background2: ParcelableColor,
    val accent: ParcelableColor,
    val onAccent: ParcelableColor,
    val red: ParcelableColor = Color(0xffbf4040),
    val blue: ParcelableColor = Color(0xff4472cf),
    val yellow: ParcelableColor = Color(0xfffff176),
    val text: ParcelableColor,
    val textSecondary: ParcelableColor,
    val textDisabled: ParcelableColor,
    val isDefault: Boolean
) : Parcelable

private val defaultAccentColor = Color(0xff00b3a4)

private fun darkColorPalette(baseColor: Color, accentColor: Color): ColorPalette {
    // 100% MATHEMATICAL MATCH TO AURORA BACKGROUND
    // AuroraBackground paints:
    // 1. Black background
    // 2. color1 (baseColor) at 50% opacity
    // 3. Black at 45% opacity on top
    // The visual result of this blend is exactly: baseColor * 0.275 + Black * 0.725
    val auroraBase = lerp(Color.Black, baseColor, 0.275f)

    // We use this exact matched color for the UI backgrounds so it blends invisibly
    val bg0 = lerp(Color.Black, baseColor, 0.15f)   // Slightly darker for deep background
    val bg1 = auroraBase                            // Exact match for sheets and cards
    val bg2 = lerp(Color.Black, baseColor, 0.35f)   // Slightly lighter for raised elements

    // Accent logic: ensure it pops but keeps the exact hue from the Aurora blob
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(accentColor.toArgb(), hsl)

    val finalAccent = if (hsl[1] < 0.1f) {
        Color.White // Grayscale album -> White buttons
    } else {
        Color.hsl(
            hue = hsl[0],
            saturation = hsl[1].coerceAtLeast(0.5f),
            lightness = hsl[2].coerceIn(0.6f, 0.8f)
        )
    }

    return ColorPalette(
        background0 = bg0,
        background1 = bg1,
        background2 = bg2,
        text = Color.White,
        textSecondary = Color.White.copy(alpha = 0.7f),
        textDisabled = Color.White.copy(alpha = 0.4f),
        accent = finalAccent,
        onAccent = Color.Black,
        isDefault = false
    )
}

val defaultDarkPalette = darkColorPalette(defaultAccentColor, defaultAccentColor).copy(isDefault = true)

fun extractThemeColors(bitmap: Bitmap): Pair<Color, Color> {
    // 100% IDENTICAL EXTRACTION TO AURORA BACKGROUND
    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
    val swatches = palette.swatches.sortedByDescending { it.population }

    // Aurora's 'color1' is literally the 0th index swatch
    val color1 = swatches.getOrNull(0)?.rgb ?: palette.getDominantColor(Color.Black.toArgb())

    // Aurora's blobs ('color2', 'color3', 'color4')
    val color2 = swatches.getOrNull(1)?.rgb ?: color1
    val color3 = swatches.getOrNull(2)?.rgb ?: color2
    val color4 = swatches.getOrNull(3)?.rgb ?: color3

    // We use color1 for the UI backgrounds so it perfectly matches the Aurora wash
    val baseColor = Color(color1)

    // For the UI accent, we grab the most colorful blob out of the 3 used in the Aurora
    val blobColors = listOf(color2, color3, color4)
    val accentRgb = blobColors.maxByOrNull { rgb ->
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] // Find the one with the highest saturation
    } ?: color2

    return baseColor to Color(accentRgb)
}

fun colorPaletteOf(
    source: ColorSource,
    materialAccentColor: Color?,
    sampleBitmap: Bitmap?
): ColorPalette {
    val (bgColor, accentColor) = when (source) {
        ColorSource.Default -> defaultAccentColor to defaultAccentColor
        ColorSource.Dynamic -> sampleBitmap?.let { extractThemeColors(it) }
            ?: (defaultAccentColor to defaultAccentColor)
        ColorSource.MaterialYou -> (materialAccentColor ?: defaultAccentColor) to (materialAccentColor ?: defaultAccentColor)
    }

    val palette = darkColorPalette(bgColor, accentColor)
    return palette.copy(isDefault = accentColor == defaultAccentColor)
}

inline val ColorPalette.collapsedPlayerProgressBar get() = background2
inline val ColorPalette.favoritesIcon get() = if (isDefault) red else accent
inline val ColorPalette.shimmer get() = if (isDefault) Color(0xff838383) else accent
inline val ColorPalette.surface get() = background2

@Suppress("UnusedReceiverParameter")
inline val ColorPalette.overlay get() = Color.Black.copy(alpha = 0.75f)

@Suppress("UnusedReceiverParameter")
inline val ColorPalette.onOverlay get() = defaultDarkPalette.text

@Suppress("UnusedReceiverParameter")
inline val ColorPalette.onOverlayShimmer get() = defaultDarkPalette.shimmer

object ColorParceler : Parceler<Color> {
    override fun Color.write(parcel: Parcel, flags: Int) = parcel.writeLong(value.toLong())
    override fun create(parcel: Parcel) = Color(parcel.readLong())
}

object DpParceler : Parceler<Dp> {
    override fun Dp.write(parcel: Parcel, flags: Int) = parcel.writeFloat(value)
    override fun create(parcel: Parcel) = parcel.readFloat().dp
}
