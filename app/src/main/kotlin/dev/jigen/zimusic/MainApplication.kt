package dev.jigen.zimusic

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.work.Configuration
import dev.jigen.zimusic.preferences.AppearancePreferences
import dev.jigen.zimusic.preferences.DataPreferences
import dev.jigen.zimusic.service.PlayerService
import dev.jigen.zimusic.service.ServiceNotifications
import dev.jigen.zimusic.service.downloadState
import dev.jigen.zimusic.ui.components.BottomSheetMenu
import dev.jigen.zimusic.ui.components.rememberBottomSheetState
import dev.jigen.zimusic.ui.components.themed.LinearProgressIndicator
import dev.jigen.zimusic.ui.screens.albumRoute
import dev.jigen.zimusic.ui.screens.artistRoute
import dev.jigen.zimusic.ui.screens.home.HomeScreen
import dev.jigen.zimusic.ui.screens.player.Player
import dev.jigen.zimusic.ui.screens.playlistRoute
import dev.jigen.zimusic.ui.screens.searchResultRoute
import dev.jigen.zimusic.ui.screens.settingsRoute
import dev.jigen.zimusic.utils.DisposableListener
import dev.jigen.zimusic.utils.LocalMonetCompat
import dev.jigen.zimusic.utils.asMediaItem
import dev.jigen.zimusic.utils.collectProvidedBitmapAsState
import dev.jigen.zimusic.utils.forcePlay
import dev.jigen.zimusic.utils.intent
import dev.jigen.zimusic.utils.invokeOnReady
import dev.jigen.zimusic.utils.setDefaultPalette
import dev.jigen.zimusic.utils.toast
import dev.jigen.compose.persist.LocalPersistMap
import dev.jigen.compose.persist.PersistMap
import dev.jigen.compose.preferences.PreferencesHolder
import dev.jigen.core.ui.Darkness
import dev.jigen.core.ui.Dimensions
import dev.jigen.core.ui.LocalAppearance
import dev.jigen.core.ui.SystemBarAppearance
import dev.jigen.core.ui.amoled
import dev.jigen.core.ui.appearance
import dev.jigen.core.ui.rippleConfiguration
import dev.jigen.core.ui.shimmerTheme
import dev.jigen.core.ui.utils.activityIntentBundle
import dev.jigen.core.ui.utils.isAtLeastAndroid12
import dev.jigen.core.ui.utils.songBundle
import dev.jigen.providers.innertube.Innertube
import dev.jigen.providers.innertube.models.bodies.BrowseBody
import dev.jigen.providers.innertube.requests.playlistPage
import dev.jigen.providers.innertube.requests.song
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.bitmapFactoryExifOrientationStrategy
import coil3.decode.ExifOrientationStrategy
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.kieronquinn.monetcompat.core.MonetActivityAccessException
import com.kieronquinn.monetcompat.core.MonetCompat
import com.kieronquinn.monetcompat.interfaces.MonetColorsChangedListener
import com.valentinilk.shimmer.LocalShimmerTheme
import dev.kdrag0n.monet.theme.ColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainActivity"
private val coroutineScope = CoroutineScope(Dispatchers.IO)

// Viewmodel in order to avoid recreating the entire Player state (WORKAROUND)
class MainViewModel : ViewModel() {
    var binder: PlayerService.Binder? by mutableStateOf(null)

    suspend fun awaitBinder(): PlayerService.Binder =
        binder ?: snapshotFlow { binder }.filterNotNull().first()
}

class MainActivity : ComponentActivity(), MonetColorsChangedListener {
    private val vm: MainViewModel by viewModels()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is PlayerService.Binder) vm.binder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vm.binder = null
            // Try to rebind, otherwise fail
            unbindService(this)
            bindService(intent<PlayerService>(), this, BIND_AUTO_CREATE)
        }
    }

    private var _monet: MonetCompat? by mutableStateOf(null)
    private val monet get() = _monet ?: throw MonetActivityAccessException()

    override fun onStart() {
        super.onStart()
        bindService(intent<PlayerService>(), serviceConnection, BIND_AUTO_CREATE)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        MonetCompat.setup(this)
        _monet = MonetCompat.getInstance()
        monet.setDefaultPalette()
        monet.addMonetColorsChangedListener(
            listener = this,
            notifySelf = false
        )
        monet.updateMonetColors()
        monet.invokeOnReady {
            setContent()
        }

        intent?.let { handleIntent(it) }
        addOnNewIntentListener(::handleIntent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppWrapper(
        modifier: Modifier = Modifier,
        content: @Composable BoxWithConstraintsScope.() -> Unit
    ) = with(AppearancePreferences) {
        val sampleBitmap = vm.binder.collectProvidedBitmapAsState()
        val appearance = appearance(
            source = colorSource,
            mode = colorMode,
            darkness = darkness,
            fontFamily = fontFamily,
            materialAccentColor = Color(monet.getAccentColor(this@MainActivity)),
            sampleBitmap = sampleBitmap,
            applyFontPadding = applyFontPadding,
            thumbnailRoundness = thumbnailRoundness.dp
        )

        SystemBarAppearance(palette = appearance.colorPalette)

        BoxWithConstraints(
            modifier = Modifier
                .background(appearance.colorPalette.background0)
                .then(modifier)
                .fillMaxSize()
        ) {
            CompositionLocalProvider(
                LocalAppearance provides appearance,
                LocalPlayerServiceBinder provides vm.binder,
                LocalCredentialManager provides Dependencies.credentialManager,
                LocalIndication provides ripple(),
                LocalRippleConfiguration provides rippleConfiguration(appearance = appearance),
                LocalShimmerTheme provides shimmerTheme(),
                LocalLayoutDirection provides LayoutDirection.Ltr,
                LocalPersistMap provides Dependencies.application.persistMap,
                LocalMonetCompat provides monet
            ) {
                content()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @Suppress("CyclomaticComplexMethod")
    @OptIn(ExperimentalLayoutApi::class)
    fun setContent() = setContent {
        val windowInsets = WindowInsets.systemBars

        AppWrapper(
            modifier = Modifier.padding(
                WindowInsets
                    .displayCutout
                    .only(WindowInsetsSides.Horizontal)
                    .asPaddingValues()
            )
        ) {
            val density = LocalDensity.current
            val bottomDp = with(density) { windowInsets.getBottom(density).toDp() }

            val imeVisible = WindowInsets.isImeVisible
            val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
            val animatedBottomDp by animateDpAsState(
                targetValue = if (imeVisible) 0.dp else bottomDp,
                label = ""
            )

            val playerBottomSheetState = rememberBottomSheetState(
                key = vm.binder,
                dismissedBound = 0.dp,
                collapsedBound = Dimensions.items.collapsedPlayerHeight + bottomDp,
                expandedBound = maxHeight
            )

            val playerAwareWindowInsets = remember(
                bottomDp,
                animatedBottomDp,
                playerBottomSheetState.value,
                imeVisible,
                imeBottomDp
            ) {
                val bottom =
                    if (imeVisible) imeBottomDp.coerceAtLeast(playerBottomSheetState.value)
                    else playerBottomSheetState.value.coerceIn(
                        animatedBottomDp..playerBottomSheetState.collapsedBound
                    )

                windowInsets
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                    .add(WindowInsets(bottom = bottom))
            }

            CompositionLocalProvider(
                LocalPlayerAwareWindowInsets provides playerAwareWindowInsets
            ) {
                val isDownloading by downloadState.collectAsState()

                Box {
                    HomeScreen()
                }

                AnimatedVisibility(
                    visible = isDownloading,
                    modifier = Modifier.padding(playerAwareWindowInsets.asPaddingValues())
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    )
                }

                CompositionLocalProvider(
                    LocalAppearance provides LocalAppearance.current.let {
                        if (it.colorPalette.isDark && AppearancePreferences.darkness == Darkness.AMOLED) {
                            it.copy(colorPalette = it.colorPalette.amoled())
                        } else it
                    }
                ) {
                    Player(
                        layoutState = playerBottomSheetState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                BottomSheetMenu(
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            vm.binder?.player.DisposableListener {
                object : Player.Listener {
                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int
                    ) = when {
                        mediaItem == null -> {
                            playerBottomSheetState.dismissSoft()
                        }

                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                            mediaItem.mediaMetadata.extras?.songBundle?.isFromPersistentQueue != true -> {
                            if (AppearancePreferences.openPlayer) playerBottomSheetState.expandSoft()
                            else Unit
                        }

                        playerBottomSheetState.dismissed -> playerBottomSheetState.collapseSoft()

                        else -> Unit
                    }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun handleIntent(intent: Intent) = lifecycleScope.launch(Dispatchers.IO) {
        val extras = intent.extras?.activityIntentBundle

        when (intent.action) {
            Intent.ACTION_SEARCH -> {
                val query = extras?.query ?: return@launch
                extras.query = null

                searchResultRoute.ensureGlobal(query)
            }

            Intent.ACTION_APPLICATION_PREFERENCES -> settingsRoute.ensureGlobal()

            Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
                val uri = intent.data
                    ?: runCatching { extras?.text?.toUri() }.getOrNull()
                    ?: return@launch

                intent.data = null
                extras?.text = null

                handleUrl(uri, vm.awaitBinder())
            }

            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = when (extras?.mediaFocus) {
                    null, "vnd.android.cursor.item/*" -> extras?.query ?: extras?.text
                    MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE -> extras.genre
                    MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> extras.artist
                    MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> extras.album
                    "vnd.android.cursor.item/audio" -> listOfNotNull(
                        extras.album,
                        extras.artist,
                        extras.genre,
                        extras.title
                    ).joinToString(separator = " ")

                    @Suppress("deprecation")
                    MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> extras.playlist

                    else -> null
                }

                if (!query.isNullOrBlank()) vm.awaitBinder().playFromSearch(query)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        monet.removeMonetColorsChangedListener(this)
        _monet = null

        removeOnNewIntentListener(::handleIntent)
    }

    override fun onStop() {
        unbindService(serviceConnection)
        super.onStop()
    }

    override fun onMonetColorsChanged(
        monet: MonetCompat,
        monetColors: ColorScheme,
        isInitialChange: Boolean
    ) {
        if (!isInitialChange) recreate()
    }
}

context(Context)
@Suppress("CyclomaticComplexMethod")
fun handleUrl(
    uri: Uri,
    binder: PlayerService.Binder?
) {
    val path = uri.pathSegments.firstOrNull()
    Log.d(TAG, "Opening url: $uri ($path)")

    coroutineScope.launch {
        when (path) {
            "search" -> uri.getQueryParameter("q")?.let { query ->
                searchResultRoute.ensureGlobal(query)
            }

            "playlist" -> uri.getQueryParameter("list")?.let { playlistId ->
                val browseId = "VL$playlistId"

                if (playlistId.startsWith("OLAK5uy_")) Innertube.playlistPage(
                    body = BrowseBody(browseId = browseId)
                )
                    ?.getOrNull()
                    ?.let { page ->
                        page.songsPage?.items?.firstOrNull()?.album?.endpoint?.browseId
                            ?.let { albumRoute.ensureGlobal(it) }
                    } ?: withContext(Dispatchers.Main) {
                    toast(getString(R.string.error_url, uri))
                }
                else playlistRoute.ensureGlobal(
                    p0 = browseId,
                    p1 = uri.getQueryParameter("params"),
                    p2 = null,
                    p3 = playlistId.startsWith("RDCLAK5uy_")
                )
            }

            "channel", "c" -> uri.lastPathSegment?.let { channelId ->
                artistRoute.ensureGlobal(channelId)
            }

            else -> when {
                path == "watch" -> uri.getQueryParameter("v")
                uri.host == "youtu.be" -> path
                else -> {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.error_url, uri))
                    }
                    null
                }
            }?.let { videoId ->
                Innertube.song(videoId)?.getOrNull()?.let { song ->
                    withContext(Dispatchers.Main) {
                        binder?.player?.forcePlay(song.asMediaItem)
                    }
                }
            }
        }
    }
}

val LocalPlayerServiceBinder = staticCompositionLocalOf<PlayerService.Binder?> { null }
val LocalPlayerAwareWindowInsets =
    compositionLocalOf<WindowInsets> { error("No player insets provided") }
val LocalCredentialManager = staticCompositionLocalOf { Dependencies.credentialManager }

class MainApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    override fun onCreate() {
        StrictMode.setVmPolicy(
            VmPolicy.Builder()
                .let {
                    if (isAtLeastAndroid12) it.detectUnsafeIntentLaunch()
                    else it
                }
                .penaltyLog()
                .penaltyDeath()
                .build()
        )

        MonetCompat.debugLog = BuildConfig.DEBUG
        super.onCreate()

        Dependencies.init(this)
        MonetCompat.enablePaletteCompat()
        ServiceNotifications.createAll()
    }

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(this)
        .crossfade(true)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.1)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("coil"))
                .maxSizeBytes(DataPreferences.coilDiskCacheMaxSize.bytes)
                .build()
        }
        .bitmapFactoryExifOrientationStrategy(ExifOrientationStrategy.IGNORE)
        .let { if (BuildConfig.DEBUG) it.logger(DebugLogger()) else it }
        .build()

    val persistMap = PersistMap()

    override val workManagerConfiguration = Configuration.Builder()
        .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
        .build()
}

object Dependencies {
    lateinit var application: MainApplication
        private set

    val credentialManager by lazy { CredentialManager.create(application) }

    internal fun init(application: MainApplication) {
        this.application = application
        DatabaseInitializer()
    }
}

open class GlobalPreferencesHolder : PreferencesHolder(Dependencies.application, "preferences")
