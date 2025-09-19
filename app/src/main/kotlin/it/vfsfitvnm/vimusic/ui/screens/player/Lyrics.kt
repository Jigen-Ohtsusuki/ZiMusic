package it.vfsfitvnm.vimusic.ui.screens.player

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.valentinilk.shimmer.shimmer
import it.vfsfitvnm.core.ui.LocalAppearance
import it.vfsfitvnm.core.ui.onOverlay
import it.vfsfitvnm.core.ui.onOverlayShimmer
import it.vfsfitvnm.core.ui.utils.dp
import it.vfsfitvnm.core.ui.utils.roundedShape
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.bodies.NextBody
import it.vfsfitvnm.providers.innertube.requests.lyrics
import it.vfsfitvnm.providers.kugou.KuGou
import it.vfsfitvnm.providers.lrclib.LrcLib
import it.vfsfitvnm.providers.lrclib.LrcParser
import it.vfsfitvnm.providers.lrclib.models.Track
import it.vfsfitvnm.providers.lrclib.toLrcFile
import it.vfsfitvnm.providers.lyricsplus.LyricsPlus
import it.vfsfitvnm.providers.lyricsplus.LyricsPlusSyncManager
import it.vfsfitvnm.providers.lyricsplus.models.LyricLine
import it.vfsfitvnm.vimusic.BuildConfig
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.LocalPlayerServiceBinder
import it.vfsfitvnm.vimusic.R
import it.vfsfitvnm.vimusic.models.Lyrics
import it.vfsfitvnm.vimusic.preferences.PlayerPreferences
import it.vfsfitvnm.vimusic.query
import it.vfsfitvnm.vimusic.service.LOCAL_KEY_PREFIX
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.vimusic.ui.components.LocalMenuState
import it.vfsfitvnm.vimusic.ui.components.themed.*
import it.vfsfitvnm.vimusic.ui.modifiers.verticalFadingEdge
import it.vfsfitvnm.vimusic.utils.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val UPDATE_DELAY = 50L
private val wordSyncCache = mutableMapOf<String, LyricsPlusSyncManager>()

private fun isWordLevelJson(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    val trimmed = text.trim()
    return trimmed.startsWith("[") && trimmed.endsWith("]") &&
        trimmed.length > 10 &&
        !trimmed.contains('\n') &&
        trimmed.contains("words")
}

private fun isLrcFormat(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    return text.contains(Regex("\\[\\d{2}:\\d{2}[.:]\\d{2,3}]"))
}

private fun parseWordLevelLyrics(jsonText: String): List<LyricLine>? {
    return try {
        val parsed = Json.decodeFromString<List<LyricLine>>(jsonText)
        if (parsed.any { it.words.isNotEmpty() }) parsed else null
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun getCachedWordLevelLyrics(context: android.content.Context, mediaId: String): List<LyricLine>? {
    return try {
        val cacheFile = File(File(context.cacheDir, "lyrics"), "${mediaId}_word.json")
        if (cacheFile.exists()) {
            val jsonText = cacheFile.readText()
            parseWordLevelLyrics(jsonText)
        } else null
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
fun Lyrics(
    mediaId: String,
    isDisplayed: Boolean,
    onDismiss: () -> Unit,
    mediaMetadataProvider: () -> MediaMetadata,
    durationProvider: () -> Long,
    ensureSongInserted: () -> Unit,
    modifier: Modifier = Modifier,
    onMenuLaunch: () -> Unit = { },
    shouldShowSynchronizedLyrics: Boolean = PlayerPreferences.isShowingSynchronizedLyrics,
    setShouldShowSynchronizedLyrics: (Boolean) -> Unit = {
        PlayerPreferences.isShowingSynchronizedLyrics = it
    },
    shouldKeepScreenAwake: Boolean = PlayerPreferences.lyricsKeepScreenAwake,
    shouldUpdateLyrics: Boolean = true,
    showControls: Boolean = true
) {
    val currentEnsureSongInserted by rememberUpdatedState(ensureSongInserted)
    val currentMediaMetadataProvider by rememberUpdatedState(mediaMetadataProvider)
    val currentDurationProvider by rememberUpdatedState(durationProvider)

    val appearance = LocalAppearance.current
    val (colorPalette, typography) = appearance

    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val binder = LocalPlayerServiceBinder.current
    val density = LocalDensity.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    var isSelectingForShare by remember(mediaId) { mutableStateOf(false) }
    val selectedTimestamps = remember(mediaId) { mutableStateListOf<Long>() }

    val pip = isInPip()

    var lyrics by remember { mutableStateOf<Lyrics?>(null) }

    val showSynchronizedLyrics = remember(shouldShowSynchronizedLyrics, lyrics) {
        shouldShowSynchronizedLyrics && (
            isWordLevelJson(lyrics?.synced) ||
                isWordLevelJson(lyrics?.fixed) ||
                (!lyrics?.synced.isNullOrBlank() && !isWordLevelJson(lyrics?.synced))
            )
    }

    var editing by remember(mediaId, shouldShowSynchronizedLyrics) { mutableStateOf(false) }
    var picking by remember(mediaId, shouldShowSynchronizedLyrics) { mutableStateOf(false) }
    var error by remember(mediaId, shouldShowSynchronizedLyrics) { mutableStateOf(false) }

    val text = remember(lyrics, showSynchronizedLyrics) {
        when {
            showSynchronizedLyrics -> {
                val syncedText = lyrics?.synced
                if (isWordLevelJson(syncedText)) {
                    syncedText
                } else {
                    val fixedText = lyrics?.fixed
                    if (isWordLevelJson(fixedText)) {
                        fixedText
                    } else {
                        if (!syncedText.isNullOrBlank()) syncedText else null
                    }
                }
            }
            else -> {
                val fixedText = lyrics?.fixed
                if (!isWordLevelJson(fixedText) && !fixedText.isNullOrBlank()) {
                    fixedText
                } else {
                    val syncedText = lyrics?.synced
                    if (!isWordLevelJson(syncedText)) syncedText else null
                }
            }
        }
    }

    var invalidLrc by remember(text) { mutableStateOf(false) }

    val lyricsPlusSyncManagerStateSaver = remember(binder) {
        Saver<MutableState<LyricsPlusSyncManager?>, String>(
            save = { state ->
                state.value?.let { manager -> Json.encodeToString(manager.getLyrics()) } ?: ""
            },
            restore = { jsonString ->
                val restoredManager = if (jsonString.isNotBlank()) {
                    val lyricsList = Json.decodeFromString<List<LyricLine>>(jsonString)
                    LyricsPlusSyncManager(
                        lyrics = lyricsList,
                        positionProvider = { binder?.player?.currentPosition ?: 0L }
                    )
                } else {
                    null
                }
                mutableStateOf(restoredManager)
            }
        )
    }

    var wordSyncedManager by rememberSaveable(
        mediaId,
        shouldShowSynchronizedLyrics,
        saver = lyricsPlusSyncManagerStateSaver
    ) {
        mutableStateOf(null)
    }

    var wordSyncedAvailable by rememberSaveable(mediaId, shouldShowSynchronizedLyrics) {
        mutableStateOf(false)
    }

    DisposableEffect(shouldKeepScreenAwake) {
        view.keepScreenOn = shouldKeepScreenAwake

        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(mediaId, shouldUpdateLyrics) {
        runCatching {
            withContext(Dispatchers.IO) {
                wordSyncedManager = null
                wordSyncedAvailable = false

                val cachedWordLyrics = getCachedWordLevelLyrics(context, mediaId)
                if (cachedWordLyrics != null) {
                    wordSyncedAvailable = true
                    val manager = LyricsPlusSyncManager(
                        lyrics = cachedWordLyrics,
                        positionProvider = { binder?.player?.currentPosition ?: 0L }
                    )
                    wordSyncCache[mediaId] = manager
                    wordSyncedManager = manager

                    val jsonString = Json.encodeToString(cachedWordLyrics)
                    Database.instance.lyrics(mediaId).distinctUntilChanged().cancellable().collect { currentLyrics ->
                        if (currentLyrics?.synced != jsonString) {
                            val newLyrics = Lyrics(
                                songId = mediaId,
                                fixed = currentLyrics?.fixed,
                                synced = jsonString
                            )
                            ensureActive()
                            transaction {
                                runCatching {
                                    currentEnsureSongInserted()
                                    Database.instance.upsert(newLyrics)
                                }
                            }
                            lyrics = newLyrics
                        } else {
                            lyrics = currentLyrics
                        }
                        return@collect
                    }
                } else {
                    Database.instance
                        .lyrics(mediaId)
                        .distinctUntilChanged()
                        .cancellable()
                        .collect { currentLyrics ->

                            val wordLevelText = when {
                                isWordLevelJson(currentLyrics?.synced) -> currentLyrics?.synced
                                isWordLevelJson(currentLyrics?.fixed) -> currentLyrics?.fixed
                                else -> null
                            }

                            if (wordLevelText != null) {
                                val parsedWordLyrics = parseWordLevelLyrics(wordLevelText)
                                if (parsedWordLyrics != null) {
                                    wordSyncedAvailable = true
                                    val manager = LyricsPlusSyncManager(
                                        lyrics = parsedWordLyrics,
                                        positionProvider = { binder?.player?.currentPosition ?: 0L }
                                    )
                                    wordSyncCache[mediaId] = manager
                                    wordSyncedManager = manager
                                    lyrics = currentLyrics
                                    return@collect
                                }
                            }

                            val hasFixedLyrics = !currentLyrics?.fixed.isNullOrBlank()
                            val hasSyncedLyrics = !currentLyrics?.synced.isNullOrBlank()

                            if (!shouldUpdateLyrics || (hasFixedLyrics && hasSyncedLyrics)) {
                                lyrics = currentLyrics
                            } else {
                                val mediaMetadata = currentMediaMetadataProvider()
                                var duration = withContext(Dispatchers.Main) {
                                    currentDurationProvider()
                                }
                                while (duration == C.TIME_UNSET) {
                                    delay(100)
                                    duration = withContext(Dispatchers.Main) {
                                        currentDurationProvider()
                                    }
                                }
                                val album = mediaMetadata.albumTitle?.toString()
                                val artist = mediaMetadata.artist?.toString().orEmpty()
                                val title = mediaMetadata.title?.toString().orEmpty().let {
                                    if (mediaId.startsWith(LOCAL_KEY_PREFIX)) {
                                        it.substringBeforeLast('.').trim()
                                    } else it
                                }

                                lyrics = null
                                error = false

                                val wordLevelLyrics = try {
                                    LyricsPlus.fetchLyrics(
                                        baseUrl = BuildConfig.LYRICS_API_BASE,
                                        title = title,
                                        artist = artist,
                                        album = album
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    null
                                }

                                val hasActualWords = wordLevelLyrics?.any { it.words.isNotEmpty() } == true

                                if (hasActualWords) {
                                    wordSyncedAvailable = true
                                    val nonNullWordLevelLyrics = wordLevelLyrics

                                    val jsonString = try {
                                        Json.encodeToString(nonNullWordLevelLyrics)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        null
                                    }

                                    if (jsonString != null) {
                                        LyricsCacheManager.save(context, mediaId, jsonString)

                                        val newLyrics = Lyrics(
                                            songId = mediaId,
                                            fixed = currentLyrics?.fixed,
                                            synced = jsonString
                                        )

                                        ensureActive()
                                        transaction {
                                            runCatching {
                                                currentEnsureSongInserted()
                                                Database.instance.upsert(newLyrics)
                                            }
                                        }

                                        val manager = LyricsPlusSyncManager(
                                            lyrics = nonNullWordLevelLyrics,
                                            positionProvider = { binder?.player?.currentPosition ?: 0L }
                                        )
                                        wordSyncCache[mediaId] = manager
                                        wordSyncedManager = manager
                                        lyrics = newLyrics
                                    }
                                } else {
                                    wordSyncedAvailable = false

                                    val fixed = currentLyrics?.fixed ?: try {
                                        Innertube.lyrics(NextBody(videoId = mediaId))?.getOrNull()
                                            ?: LrcLib.bestLyrics(
                                                artist = artist,
                                                title = title,
                                                duration = duration.milliseconds,
                                                album = album,
                                                synced = false
                                            )?.map { it?.text }?.getOrNull()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        null
                                    }

                                    val synced = currentLyrics?.synced ?: try {
                                        LrcLib.bestLyrics(
                                            artist = artist,
                                            title = title,
                                            duration = duration.milliseconds,
                                            album = album
                                        )?.map { it?.text }?.getOrNull()
                                            ?: LrcLib.bestLyrics(
                                                artist = artist,
                                                title = title.split("(")[0].trim(),
                                                duration = duration.milliseconds,
                                                album = album
                                            )?.map { it?.text }?.getOrNull()
                                            ?: KuGou.lyrics(
                                                artist = artist,
                                                title = title,
                                                duration = duration / 1000
                                            )?.map { it?.value }?.getOrNull()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        null
                                    }

                                    val newLyrics = Lyrics(
                                        songId = mediaId,
                                        fixed = fixed.orEmpty(),
                                        synced = synced.orEmpty()
                                    )

                                    ensureActive()
                                    transaction {
                                        runCatching {
                                            currentEnsureSongInserted()
                                            Database.instance.upsert(newLyrics)
                                        }
                                    }
                                }
                            }

                            error = when {
                                shouldShowSynchronizedLyrics -> {
                                    !wordSyncedAvailable &&
                                        !isWordLevelJson(lyrics?.synced) &&
                                        !isWordLevelJson(lyrics?.fixed) &&
                                        lyrics?.synced.isNullOrBlank()
                                }
                                else -> {
                                    val fixedText = lyrics?.fixed
                                    val syncedText = lyrics?.synced
                                    (isWordLevelJson(fixedText) || fixedText.isNullOrBlank()) &&
                                        (isWordLevelJson(syncedText) || syncedText.isNullOrBlank())
                                }
                            }
                        }
                }
            }
        }.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            else it.printStackTrace()
        }
    }

    if (editing) TextFieldDialog(
        hintText = stringResource(R.string.enter_lyrics),
        initialTextInput = (if (shouldShowSynchronizedLyrics) lyrics?.synced else lyrics?.fixed)
            .orEmpty(),
        singleLine = false,
        maxLines = 10,
        isTextInputValid = { true },
        onDismiss = { editing = false },
        onAccept = {
            transaction {
                runCatching {
                    currentEnsureSongInserted()

                    Database.instance.upsert(
                        if (shouldShowSynchronizedLyrics) Lyrics(
                            songId = mediaId,
                            fixed = lyrics?.fixed,
                            synced = it
                        ) else Lyrics(
                            songId = mediaId,
                            fixed = it,
                            synced = lyrics?.synced
                        )
                    )
                }
            }
        }
    )

    if (picking && shouldShowSynchronizedLyrics) {
        var query by rememberSaveable {
            mutableStateOf(
                currentMediaMetadataProvider().title?.toString().orEmpty().let {
                    if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it
                        .substringBeforeLast('.')
                        .trim()
                    else it
                }
            )
        }

        LrcLibSearchDialog(
            query = query,
            setQuery = { query = it },
            onDismiss = { picking = false },
            onPick = {
                runCatching {
                    transaction {
                        Database.instance.upsert(
                            Lyrics(
                                songId = mediaId,
                                fixed = lyrics?.fixed,
                                synced = it.syncedLyrics
                            )
                        )
                    }
                }
            }
        )
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { if (!isSelectingForShare) onDismiss() })
            }
            .background(Color.Transparent)
    ) {
        val animatedHeight by animateDpAsState(
            targetValue = this.maxHeight,
            label = ""
        )

        AnimatedVisibility(
            visible = error,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            BasicText(
                text = stringResource(
                    if (shouldShowSynchronizedLyrics) R.string.synchronized_lyrics_not_available
                    else R.string.lyrics_not_available
                ),
                style = typography.xs.center.bold.color(colorPalette.onOverlay),
                modifier = Modifier
                    .background(Color.Black.copy(0.4f))
                    .padding(all = 8.dp)
                    .fillMaxWidth(),
                maxLines = if (pip) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
        }

        AnimatedVisibility(
            visible = !text.isNullOrBlank() && !error && invalidLrc && shouldShowSynchronizedLyrics && !wordSyncedAvailable,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            BasicText(
                text = stringResource(R.string.invalid_synchronized_lyrics),
                style = typography.xs.center.bold.color(colorPalette.onOverlay),
                modifier = Modifier
                    .background(Color.Black.copy(0.4f))
                    .padding(all = 8.dp)
                    .fillMaxWidth(),
                maxLines = if (pip) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
        }

        val lyricsState = rememberSaveable(text) {
            val syncedText = text?.takeIf { it.isNotBlank() }
            val isJson = isWordLevelJson(syncedText)

            if (isJson) {
                SynchronizedLyricsState(sentences = null, offset = 0L)
            } else {
                val file = syncedText?.let { LrcParser.parse(it)?.toLrcFile() }
                SynchronizedLyricsState(
                    sentences = file?.lines,
                    offset = file?.offset?.inWholeMilliseconds ?: 0L
                )
            }
        }

        val synchronizedLyrics = remember(lyricsState) {
            invalidLrc = lyricsState.sentences == null && !isWordLevelJson(text)
            lyricsState.sentences?.let {
                SynchronizedLyrics(it.toImmutableMap()) {
                    binder?.player?.let { player ->
                        player.currentPosition + UPDATE_DELAY + lyricsState.offset -
                            (lyrics?.startTime ?: 0L)
                    } ?: 0L
                }
            }
        }

        AnimatedContent(
            targetState = showSynchronizedLyrics,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = ""
        ) { synchronized ->
            when {
                synchronized && wordSyncedAvailable && wordSyncedManager != null -> {
                    LaunchedEffect(wordSyncedManager, isDisplayed) {
                        if (isDisplayed && wordSyncedManager != null) {
                            wordSyncedManager?.forceSync()
                        }
                    }

                    LaunchedEffect(wordSyncedManager) {
                        wordSyncedManager?.updatePosition()
                        while (isActive) {
                            delay(UPDATE_DELAY)
                            wordSyncedManager?.updatePosition()
                        }
                    }

                    WordSyncedLyrics(
                        manager = wordSyncedManager!!,
                        isVisible = isDisplayed,
                        binder = binder
                    )
                }

                synchronized && synchronizedLyrics != null -> {
                    val lazyListState = rememberLazyListState()

                    LaunchedEffect(synchronizedLyrics, density, animatedHeight) {
                        val currentSynchronizedLyrics = synchronizedLyrics
                        val centerOffset = with(density) { (-animatedHeight / 3).roundToPx() }

                        if (!lazyListState.isScrollInProgress) {
                            lazyListState.animateScrollToItem(
                                index = currentSynchronizedLyrics.index + 1,
                                scrollOffset = centerOffset
                            )
                        }

                        while (true) {
                            delay(UPDATE_DELAY)
                            if (!currentSynchronizedLyrics.update()) continue

                            if (!lazyListState.isScrollInProgress) {
                                lazyListState.animateScrollToItem(
                                    index = currentSynchronizedLyrics.index + 1,
                                    scrollOffset = centerOffset
                                )
                            }
                        }
                    }

                    val lyricsLines = remember(synchronizedLyrics) {
                        synchronizedLyrics.sentences.entries.toImmutableList()
                    }

                    LazyColumn(
                        state = lazyListState,
                        userScrollEnabled = true,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .verticalFadingEdge()
                            .fillMaxWidth()
                    ) {
                        item(key = "header", contentType = 0) {
                            Spacer(modifier = Modifier.height(maxHeight / 2))
                        }

                        itemsIndexed(
                            items = lyricsLines,
                            key = { _, (timestamp, _) -> timestamp }
                        ) { index, (timestamp, sentence) ->
                            val isSelected = remember(selectedTimestamps.size) { selectedTimestamps.contains(timestamp) }

                            val isSelectable = remember(selectedTimestamps.size) {
                                when {
                                    isSelected -> true
                                    selectedTimestamps.size >= 6 -> false
                                    selectedTimestamps.isEmpty() -> true
                                    else -> {
                                        val selectedIndices = selectedTimestamps.mapNotNull { ts -> lyricsLines.find { it.key == ts }?.let { lyricsLines.indexOf(it) } }
                                        if (selectedIndices.isEmpty()) {
                                            true
                                        } else {
                                            val minIndex = selectedIndices.minOrNull()!!
                                            val maxIndex = selectedIndices.maxOrNull()!!
                                            index == minIndex - 1 || index == maxIndex + 1
                                        }
                                    }
                                }
                            }

                            val animatedLineBackgroundColor by animateColorAsState(
                                targetValue = if (isSelected) colorPalette.accent.copy(alpha = 0.25f) else Color.Transparent,
                                label = "lineSelectionBackgroundColor"
                            )

                            val activeColor = if (colorPalette.isDark) Color.White else Color.Black
                            val inactiveColor = if (colorPalette.isDark) colorPalette.textDisabled else Color.Gray

                            val color by animateColorAsState(
                                if (index == synchronizedLyrics.index && !isSelectingForShare) activeColor
                                else inactiveColor
                            )

                            val itemAlpha by animateFloatAsState(
                                targetValue = if (isSelectingForShare && !isSelectable) 0.5f else 1.0f,
                                label = "selectableAlpha"
                            )

                            val seekPosition = remember(timestamp, lyricsState.offset, lyrics?.startTime) {
                                timestamp - lyricsState.offset + (lyrics?.startTime ?: 0L)
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .alpha(itemAlpha)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(animatedLineBackgroundColor)
                                    .clickable(
                                        enabled = !isSelectingForShare || isSelectable,
                                        onClick = {
                                            if (isSelectingForShare) {
                                                if (isSelected) {
                                                    selectedTimestamps.remove(timestamp)
                                                } else {
                                                    selectedTimestamps.add(timestamp)
                                                }
                                            } else {
                                                binder?.player?.seekTo(seekPosition)
                                            }
                                        }
                                    )
                            ) {
                                if (sentence.isBlank()) {
                                    Image(
                                        painter = painterResource(R.drawable.musical_notes),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(color),
                                        modifier = Modifier
                                            .padding(vertical = 8.dp, horizontal = 16.dp)
                                            .size(typography.l.fontSize.dp)
                                    )
                                } else {
                                    BasicText(
                                        text = sentence,
                                        style = typography.l.center.bold.color(color),
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
                                    )
                                }
                            }
                        }

                        item(key = "footer", contentType = 0) {
                            Spacer(modifier = Modifier.height(maxHeight / 2))
                        }
                    }
                }

                !synchronized -> {
                    val displayText = if (!isWordLevelJson(text) && !isLrcFormat(text) && !text.isNullOrBlank()) {
                        text
                    } else {
                        ""
                    }

                    BasicText(
                        text = displayText,
                        style = typography.l.center.bold.color(if (colorPalette.isDark) Color.White else Color.Black),
                        modifier = Modifier
                            .verticalFadingEdge()
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth()
                            .padding(vertical = maxHeight / 4, horizontal = 32.dp)
                    )
                }
            }
        }

        if (text == null && !error && !wordSyncedAvailable) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.shimmer()
        ) {
            repeat(4) {
                TextPlaceholder(
                    color = colorPalette.onOverlayShimmer,
                    modifier = Modifier.alpha(1f - it * 0.2f)
                )
            }
        }

        if (showControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ellipsis_horizontal),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (colorPalette.isDark) colorPalette.onOverlay else Color.Black),
                    modifier = Modifier
                        .clickable(
                            indication = ripple(bounded = false),
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {
                                onMenuLaunch()
                                menuState.display {
                                    Menu {
                                        MenuEntry(
                                            icon = R.drawable.share_social,
                                            text = stringResource(R.string.share_lyrics),
                                            onClick = {
                                                menuState.hide()
                                                if (isWordLevelJson(text)) {
                                                    context.toast("Sharing is not supported for word-synced lyrics yet.")
                                                } else {
                                                    selectedTimestamps.clear()
                                                    isSelectingForShare = true
                                                }
                                            }
                                        )

                                        MenuEntry(
                                            icon = R.drawable.time,
                                            text = stringResource(
                                                if (shouldShowSynchronizedLyrics) R.string.show_unsynchronized_lyrics
                                                else R.string.show_synchronized_lyrics
                                            ),
                                            onClick = {
                                                menuState.hide()
                                                setShouldShowSynchronizedLyrics(!shouldShowSynchronizedLyrics)
                                            }
                                        )

                                        MenuEntry(
                                            icon = R.drawable.pencil,
                                            text = stringResource(R.string.edit_lyrics),
                                            onClick = {
                                                menuState.hide()
                                                editing = true
                                            }
                                        )

                                        MenuEntry(
                                            icon = R.drawable.search,
                                            text = stringResource(R.string.search_lyrics_online),
                                            onClick = {
                                                menuState.hide()
                                                val mediaMetadata = currentMediaMetadataProvider()
                                                try {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                                                            putExtra(
                                                                SearchManager.QUERY,
                                                                "${mediaMetadata.title} ${mediaMetadata.artist} lyrics"
                                                            )
                                                        }
                                                    )
                                                } catch (_: ActivityNotFoundException) {
                                                    context.toast(context.getString(R.string.no_browser_installed))
                                                }
                                            }
                                        )

                                        MenuEntry(
                                            icon = R.drawable.sync,
                                            text = stringResource(R.string.refetch_lyrics),
                                            enabled = lyrics != null,
                                            onClick = {
                                                menuState.hide()
                                                try {
                                                    File(
                                                        File(context.cacheDir, "lyrics"),
                                                        "${mediaId}_word.json"
                                                    ).delete()
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                }

                                                wordSyncCache.remove(mediaId)
                                                wordSyncedManager = null
                                                wordSyncedAvailable = false

                                                transaction {
                                                    runCatching {
                                                        currentEnsureSongInserted()

                                                        Database.instance.upsert(
                                                            Lyrics(
                                                                songId = mediaId,
                                                                fixed = null,
                                                                synced = null
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        )

                                        if (shouldShowSynchronizedLyrics) {
                                            MenuEntry(
                                                icon = R.drawable.download,
                                                text = stringResource(R.string.pick_from_lrclib),
                                                onClick = {
                                                    menuState.hide()
                                                    picking = true
                                                }
                                            )
                                            MenuEntry(
                                                icon = R.drawable.play_skip_forward,
                                                text = stringResource(R.string.set_lyrics_start_offset),
                                                secondaryText = stringResource(
                                                    R.string.set_lyrics_start_offset_description
                                                ),
                                                onClick = {
                                                    menuState.hide()
                                                    lyrics?.let {
                                                        val startTime =
                                                            binder?.player?.currentPosition
                                                        query {
                                                            Database.instance.upsert(
                                                                it.copy(
                                                                    startTime = startTime
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        .padding(all = 8.dp)
                        .size(20.dp)
                )

                Image(
                    painter = painterResource(R.drawable.close),
                    contentDescription = stringResource(R.string.close),
                    colorFilter = ColorFilter.tint(if (colorPalette.isDark) colorPalette.onOverlay else Color.Black),
                    modifier = Modifier
                        .clickable(
                            indication = ripple(bounded = false),
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onDismiss
                        )
                        .padding(all = 8.dp)
                        .size(20.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = isSelectingForShare,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            val isEnabled = selectedTimestamps.isNotEmpty()

            @Composable
            fun Buttons() {
                suspend fun generateLyricsBitmap(): Bitmap? {
                    try {
                        val synchronizedLyrics = synchronizedLyrics ?: return null
                        val mediaMetadata = currentMediaMetadataProvider()
                        val artworkUri = mediaMetadata.artworkUri

                        val lyricsMap = synchronizedLyrics.sentences
                        val sortedTimestamps = selectedTimestamps.sorted()
                        val lyricsToShare = sortedTimestamps.mapNotNull { lyricsMap[it] }.joinToString("\n")

                        if (lyricsToShare.isBlank()) {
                            context.toast("Please select some lyrics to share.")
                            return null
                        }

                        val highQualityArtworkUri = artworkUri?.let {
                            val originalUrl = it.toString()
                            if (originalUrl.contains("=w")) {
                                Uri.parse(originalUrl.replace(Regex("=w\\d+-h\\d+"), "=w1080-h1080"))
                            } else it
                        }

                        val albumArtBitmap: Bitmap? = highQualityArtworkUri?.let { uri ->
                            val request = ImageRequest.Builder(context)
                                .data(uri)
                                .allowHardware(false)
                                .build()
                            context.imageLoader.execute(request).image?.toBitmap()
                        }

                        return captureComposableAsBitmap(context, appearance) {
                            ShareCard(
                                lyrics = lyricsToShare,
                                songTitle = mediaMetadata.title?.toString() ?: "Unknown Title",
                                songArtist = mediaMetadata.artist?.toString() ?: "Unknown Artist",
                                albumArtBitmap = albumArtBitmap
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("LyricsShare", "Bitmap generation failed", e)
                        context.toast("Failed to generate image: ${e.message}")
                        return null
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SecondaryTextButton(
                        text = stringResource(id = android.R.string.cancel),
                        onClick = {
                            isSelectingForShare = false
                            selectedTimestamps.clear()
                        }
                    )

                    IconButton(
                        enabled = isEnabled,
                        onClick = {
                            coroutineScope.launch {
                                val bitmap = generateLyricsBitmap()
                                if (bitmap != null) {
                                    val mediaMetadata = currentMediaMetadataProvider()
                                    val displayName = "${mediaMetadata.artist} - ${mediaMetadata.title}"
                                    val success = saveBitmapToGallery(context, bitmap, displayName)
                                    if (success) {
                                        context.toast("Saved to Gallery")
                                        isSelectingForShare = false
                                        selectedTimestamps.clear()
                                    } else {
                                        context.toast("Failed to save image")
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.download),
                            contentDescription = "Save to Gallery",
                            tint = if (isEnabled) colorPalette.onOverlay else colorPalette.onOverlay.copy(alpha = 0.5f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(32.dp.roundedShape)
                            .clickable(enabled = isEnabled) {
                                coroutineScope.launch {
                                    val bitmap = generateLyricsBitmap()
                                    if (bitmap != null) {
                                        val uri = saveBitmapToCache(context, bitmap)
                                        shareImageUri(context, uri)
                                        isSelectingForShare = false
                                        selectedTimestamps.clear()
                                    }
                                }
                            }
                            .background(if (isEnabled) colorPalette.accent else colorPalette.background2)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        BasicText(
                            text = stringResource(R.string.share),
                            style = typography.s.semiBold.color(
                                if (isEnabled) colorPalette.text else colorPalette.textDisabled
                            )
                        )
                    }
                }
            }

            Buttons()
        }
    }
}

@Composable
fun LrcLibSearchDialog(
    query: String,
    setQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onPick: (Track) -> Unit,
    modifier: Modifier = Modifier
) = DefaultDialog(
    onDismiss = onDismiss,
    horizontalPadding = 0.dp,
    modifier = modifier
) {
    val (_, typography) = LocalAppearance.current

    val tracks = remember { mutableStateListOf<Track>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        loading = true
        error = false

        delay(1000)

        LrcLib.lyrics(
            query = query,
            synced = true
        )?.onSuccess { newTracks ->
            tracks.clear()
            tracks.addAll(newTracks.filter { !it.syncedLyrics.isNullOrBlank() })
            loading = false
            error = false
        }?.onFailure {
            loading = false
            error = true
            it.printStackTrace()
        } ?: run { loading = false }
    }

    TextField(
        value = query,
        onValueChange = setQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        maxLines = 1,
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))

    when {
        loading -> CircularProgressIndicator(
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        error || tracks.isEmpty() -> BasicText(
            text = stringResource(R.string.no_lyrics_found),
            style = typography.s.semiBold.center,
            modifier = Modifier
                .padding(all = 24.dp)
                .align(Alignment.CenterHorizontally)
        )

        else -> ValueSelectorDialogBody(
            onDismiss = onDismiss,
            title = stringResource(R.string.choose_lyric_track),
            selectedValue = null,
            values = tracks.toImmutableList(),
            onValueSelect = {
                transaction {
                    onPick(it)
                    onDismiss()
                }
            },
            valueText = {
                "${it.artistName} - ${it.trackName} (${
                    it.duration.seconds.toComponents { minutes, seconds, _ ->
                        "$minutes:${seconds.toString().padStart(2, '0')}"
                    }
                })"
            }
        )
    }
}
