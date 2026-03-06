package dev.jigen.core.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat
import dev.jigen.core.ui.utils.isAtLeastAndroid6
import dev.jigen.core.ui.utils.isAtLeastAndroid8
import dev.jigen.core.ui.utils.isCompositionLaunched
import dev.jigen.core.ui.utils.roundedShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
@Immutable
data class Appearance(
    val colorPalette: ColorPalette,
    val typography: Typography,
    val thumbnailShapeCorners: ParcelableDp
) : Parcelable {
    @IgnoredOnParcel
    val thumbnailShape = thumbnailShapeCorners.roundedShape
    operator fun component4() = thumbnailShape
}

val LocalAppearance = staticCompositionLocalOf<Appearance> { error("No appearance provided") }

@Composable
inline fun rememberAppearance(
    vararg keys: Any = arrayOf(Unit),
    crossinline provide: () -> Appearance
) = rememberSaveable(keys, isCompositionLaunched()) {
    mutableStateOf(provide())
}

@Composable
fun appearance(
    source: ColorSource,
    materialAccentColor: Color?,
    sampleBitmap: Bitmap?,
    fontFamily: BuiltInFontFamily,
    applyFontPadding: Boolean,
    thumbnailRoundness: Dp
): Appearance {
    val colorPalette = rememberSaveable(
        source,
        materialAccentColor,
        sampleBitmap
    ) {
        colorPaletteOf(
            source = source,
            materialAccentColor = materialAccentColor,
            sampleBitmap = sampleBitmap
        )
    }

    return rememberAppearance(
        colorPalette,
        fontFamily,
        applyFontPadding,
        thumbnailRoundness
    ) {
        Appearance(
            colorPalette = colorPalette,
            typography = typographyOf(
                color = colorPalette.text,
                fontFamily = fontFamily,
                applyFontPadding = applyFontPadding
            ),
            thumbnailShapeCorners = thumbnailRoundness
        )
    }.value
}

fun Activity.setSystemBarAppearance() {
    with(WindowCompat.getInsetsController(window, window.decorView.rootView)) {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }

    val color = Color.Transparent.toArgb()

    @Suppress("DEPRECATION")
    if (!isAtLeastAndroid6) window.statusBarColor = color
    @Suppress("DEPRECATION")
    if (!isAtLeastAndroid8) window.navigationBarColor = color
}

@Composable
fun Activity.SystemBarAppearance(palette: ColorPalette) = LaunchedEffect(palette) {
    withContext(Dispatchers.Main) {
        setSystemBarAppearance()
    }
}
