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
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
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
import androidx.core.graphics.createBitmap

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
    albumArtBitmap: Bitmap?
) {
    val imageSize = 1080.dp
    val (colorPalette, _, _) = LocalAppearance.current
    val totalLyricsChars = lyrics.length
    val lyricsLinesCount = lyrics.count { it == '\n' } + 1

    val lyricsFontSize = when {
        lyricsLinesCount > 8 -> 32.sp
        totalLyricsChars > 250 -> 38.sp
        totalLyricsChars > 150 -> 42.sp
        else -> 48.sp
    }

    Box(
        modifier = Modifier
            .size(imageSize)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colorPalette.background1,
                        colorPalette.background0
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(all = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                horizontalAlignment = Alignment.Start, // Align song details block to the left
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(), // Use full width for alignment
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    if (albumArtBitmap != null) {
                        Image(
                            bitmap = albumArtBitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = songTitle,
                            color = colorPalette.text,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = poppinsFontFamily,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = songArtist,
                            color = colorPalette.textSecondary,
                            fontSize = 20.sp,
                            fontFamily = poppinsFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = "Shared from ZiMusic",
                    color = colorPalette.textDisabled,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = poppinsFontFamily,
                    textAlign = TextAlign.Start, // Align watermark text to the left
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        setContent {
            CompositionLocalProvider(
                LocalAppearance provides appearance,
                LocalDensity provides Density(density = requiredDensity, fontScale = LocalDensity.current.fontScale)
            ) {
                content()
            }
        }
        alpha = 0f
    }

    try {
        withContext(Dispatchers.Main) {
            decorView.addView(
                composeView,
                ViewGroup.LayoutParams(
                    targetSizePx,
                    targetSizePx
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

        val bitmap = createBitmap(targetSizePx, targetSizePx)
        val canvas = android.graphics.Canvas(bitmap)
        composeView.draw(canvas)
        return bitmap

    } finally {
        withContext(Dispatchers.Main) {
            decorView.removeView(composeView)
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
    val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ZiMusic")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }
            return@withContext true
        } ?: throw IOException("Failed to create new MediaStore record.")
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
