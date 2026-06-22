package dev.jigen.zimusic.utils

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import dev.jigen.core.ui.Appearance
import dev.jigen.core.ui.LocalAppearance
import dev.jigen.zimusic.BuildConfig
import dev.jigen.zimusic.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import dev.jigen.core.ui.R as CoreUiR

val poppinsFontFamily = FontFamily(
    Font(resId = CoreUiR.font.poppins_w300, weight = FontWeight.Light),
    Font(resId = CoreUiR.font.poppins_w400, weight = FontWeight.Normal),
    Font(resId = CoreUiR.font.poppins_w500, weight = FontWeight.Medium),
    Font(resId = CoreUiR.font.poppins_w600, weight = FontWeight.SemiBold),
    Font(resId = CoreUiR.font.poppins_w700, weight = FontWeight.Bold)
)

@Composable
fun ShareCard(
    lyrics: String,
    songTitle: String,
    songArtist: String,
    albumArtBitmap: Bitmap?,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 540.dp
) {
    val (colorPalette, _, _) = LocalAppearance.current
    val totalLyricsChars = lyrics.length
    val lyricsLinesCount = lyrics.count { it == '\n' } + 1

    val lyricsFontSize = when {
        lyricsLinesCount > 8 -> 20.sp
        totalLyricsChars > 250 -> 22.sp
        totalLyricsChars > 150 -> 26.sp
        else -> 30.sp
    }

    Column(
        modifier = modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 280.dp, max = maxWidth)
            .wrapContentHeight()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colorPalette.background1,
                        colorPalette.background0
                    )
                ),
                shape = RoundedCornerShape(28.dp)
            )
            .clip(RoundedCornerShape(28.dp))
            .padding(all = 28.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            lyrics.split('\n').forEach { line ->
                if (line.isNotBlank()) {
                    Text(
                        text = line,
                        color = colorPalette.text,
                        fontSize = lyricsFontSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = poppinsFontFamily,
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (albumArtBitmap != null) {
                    Image(
                        bitmap = albumArtBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = songTitle,
                        color = colorPalette.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = poppinsFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = songArtist,
                        color = colorPalette.textSecondary,
                        fontSize = 13.sp,
                        fontFamily = poppinsFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = "Shared from ZiMusic",
                color = colorPalette.textDisabled,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = poppinsFontFamily,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

suspend fun captureComposableAsBitmap(
    context: Context,
    appearance: Appearance,
    content: @Composable () -> Unit
): Bitmap {
    val activity = context as? Activity
        ?: throw IllegalArgumentException("Context must be an Activity to capture a composable.")

    val decorView = activity.window.decorView as ViewGroup

    val targetSizePx = 3840
    val designSizeDp = 1080.dp
    val requiredDensity = targetSizePx / designSizeDp.value

    val composeView = ComposeView(context).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setContent {
            CompositionLocalProvider(
                LocalAppearance provides appearance,
                LocalDensity provides Density(density = requiredDensity, fontScale = LocalDensity.current.fontScale)
            ) {
                Box(modifier = Modifier.background(androidx.compose.ui.graphics.Color.Transparent)) {
                    content()
                }
            }
        }
    }

    val unconstrainedContainer = object : android.widget.FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            super.onMeasure(widthSpec, heightSpec)
        }
    }.apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        alpha = 0f
        addView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    try {
        withContext(Dispatchers.Main) {
            decorView.addView(
                unconstrainedContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        suspendCancellableCoroutine { continuation ->
            val drawListener = ViewTreeObserver.OnDrawListener {
                if (composeView.width > 0 && composeView.height > 0) {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
            composeView.viewTreeObserver.addOnDrawListener(drawListener)
            continuation.invokeOnCancellation {
                composeView.viewTreeObserver.removeOnDrawListener(drawListener)
            }
        }

        val bitmap = createBitmap(composeView.width, composeView.height)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        composeView.draw(canvas)
        return bitmap

    } finally {
        withContext(Dispatchers.Main) {
            decorView.removeView(unconstrainedContainer)
        }
    }
}

suspend fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
    val cachePath = File(context.cacheDir, "images/")
    cachePath.mkdirs()

    val file = File(cachePath, "lyrics_share.png")
    FileOutputStream(file).use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }

    return@withContext FileProvider.getUriForFile(
        context,
        "${BuildConfig.APPLICATION_ID}.provider",
        file
    )
}

suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean = withContext(Dispatchers.IO) {
    val imageCollection =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ZiMusic")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    var uri: Uri? = null
    try {
        uri = resolver.insert(imageCollection, contentValues)
        uri?.let {
            val stream: OutputStream? = resolver.openOutputStream(it)
            stream?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Failed to save bitmap.")
                }
            } ?: throw IOException("Failed to get output stream.")

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(it, contentValues, null, null)
            return@withContext true
        } ; throw IOException("Failed to create new MediaStore record.")
    } catch (e: Exception) {
        Log.e("SaveToGallery", "Failed to save image", e)
        uri?.let { orphanUri ->
            resolver.delete(orphanUri, null, null)
        }
        return@withContext false
    }
}

fun shareImageUri(context: Context, imageUri: Uri) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, imageUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_lyrics))

    val resInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.queryIntentActivities(
            chooser,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
    }

    for (resolveInfo in resInfoList) {
        val packageName = resolveInfo.activityInfo.packageName
        context.grantUriPermission(
            packageName,
            imageUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
