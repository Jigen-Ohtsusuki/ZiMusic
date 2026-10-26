package dev.jigen.zimusic.discord

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

data class DiscordUser(
    val id: String,
    val username: String,
    val name: String,
    val avatar: String?,
)

object DiscordRpcManager {
    internal const val TAG = "DiscordSvc"

    @Volatile
    internal var initialized: Boolean = false

    @Volatile
    internal var _ready: Boolean = false

    @Volatile
    internal var _authorized: Boolean = false

    @Volatile
    internal var accessToken: String? = null

    @Volatile
    internal var authorizeInProgress: Boolean = false

    @Volatile
    internal var lastActivitySentAtMs: Long = 0L

    @Volatile
    internal var lastActivity: ActivityPayload? = null

    @Volatile
    internal var currentSongId: String? = null
    @Volatile
    internal var currentIsPlaying: Boolean = false
    @Volatile
    internal var currentLargeImageUrl: String? = null
    @Volatile
    internal var currentlyResolvingImageUrl: String? = null
    @Volatile
    internal var imageResolutionJob: Job? = null
    @Volatile
    internal var trailingUpdateJob: Job? = null
    internal val reconnectMutex = Mutex()
    @Volatile
    internal var pendingActivity: DiscordActivity? = null
    @Volatile
    internal var pendingSongId: String? = null
    @Volatile
    internal var pendingIsPlaying: Boolean = false
    @Volatile
    internal var pendingStatus: PresenceStatus = PresenceStatus.Online

    internal val _accessTokenFlow = MutableStateFlow<String?>(null)
    val accessTokenFlow: StateFlow<String?> = _accessTokenFlow

    internal val _connectionStatus = MutableStateFlow(Status.Disconnected)
    val connectionStatus: StateFlow<Status> = _connectionStatus

    internal val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    internal val _currentUser = MutableStateFlow<DiscordUser?>(null)
    val currentUser: StateFlow<DiscordUser?> = _currentUser

    enum class Status { Disconnected, Authorizing, Connected }

    fun isInitialized(): Boolean = initialized

    fun isAuthorized(): Boolean = _authorized

    fun isReady(): Boolean = _ready

    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val appId: String = DiscordDefaults.DISCORD_APP_ID

    internal val auth: DiscordAuth = DiscordAuth()

    internal var gateway: DiscordGateway = createGateway(scope)

    internal fun createGateway(scope: CoroutineScope): DiscordGateway {
        return DiscordGateway(scope, accessToken ?: "")
    }

    internal fun startEventCollection() {
        scope.launch {
            gateway.events.collect { event -> handleGatewayEvent(event) }
        }
    }

    fun init(context: Context) {
        DiscordTokenStore.init(context.applicationContext)
        if (initialized && scope.isActive) {
            DiscordLog.i(TAG, "init: already initialized and active, skipping")
            return
        }
        if (!scope.isActive) {
            DiscordLog.i(TAG, "init: recreating scope after previous destroy")
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            gateway = createGateway(scope)
        }
        initialized = true
        _connectionStatus.value = Status.Disconnected
        startEventCollection()
        DiscordLog.i(TAG, "init: token store initialized, scheduling auto-rehydrate")

        scope.launch {
            val saved = DiscordTokenStore.retrieveSuspend()
            if (!saved.isNullOrEmpty()) {
                DiscordLog.i(TAG, "init: found persisted token, reconnecting")
                reconnectWithToken(saved)
            } else {
                DiscordLog.i(TAG, "init: no persisted token, waiting for explicit authorize")
            }
        }
    }

    fun authorize(activity: Activity, onComplete: (Boolean) -> Unit) {
        if (authorizeInProgress) {
            DiscordLog.w(TAG, "authorize: already in progress, ignoring double-tap")
            scope.launch(Dispatchers.Main) { onComplete(false) }
            return
        }
        authorizeInProgress = true

        fun completeWith(success: Boolean) {
            scope.launch(Dispatchers.Main) { onComplete(success) }
        }

        if (_ready && _authorized) {
            DiscordLog.d(TAG, "authorize: short-circuit — already ready and authorized")
            authorizeInProgress = false
            scope.launch(Dispatchers.Main) { onComplete(true) }
            return
        }
        if (_authorized) {
            DiscordLog.d(TAG, "authorize: short-circuit — authorized but not ready, reconnecting")
            authorizeInProgress = false
            reconnectWithToken(accessToken ?: "")
            scope.launch(Dispatchers.Main) { onComplete(true) }
            return
        }

        _connectionStatus.value = Status.Authorizing
        _lastError.value = null

        scope.launch {
            try {
                val result = auth.authorize(activity)
                DiscordTokenStore.storeFull(
                    result.accessToken,
                    result.refreshToken,
                    result.expiresInSec,
                )
                accessToken = result.accessToken
                _accessTokenFlow.value = result.accessToken
                _authorized = true

                try {
                    reconnectMutex.withLock {
                        runCatching { gateway.close() }
                        gateway.connect()
                        gateway.identify("Bearer ${result.accessToken}")
                    }
                    completeWith(true)
                } catch (e: Throwable) {
                    DiscordLog.e(TAG, "authorize: gateway connect/identify failed", e)
                    _lastError.value = "discord_error_loopback_timeout"
                    _connectionStatus.value = Status.Disconnected
                    _ready = false
                    _authorized = false
                    completeWith(false)
                }
            } catch (e: DiscordAuthException.UserCancelled) {
                DiscordLog.i(TAG, "authorize: user cancelled")
                _lastError.value = "discord_error_loopback_timeout"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.StateMismatch) {
                DiscordLog.w(TAG, "authorize: state mismatch", e)
                _lastError.value = "discord_error_invalid_scope"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.NetworkFailure) {
                DiscordLog.e(TAG, "authorize: network failure", e)
                _lastError.value = "discord_error_loopback_timeout"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.NoBrowser) {
                DiscordLog.w(TAG, "authorize: no browser available", e)
                _lastError.value = "discord_error_no_browser"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.InvalidGrant) {
                DiscordLog.w(TAG, "authorize: invalid grant", e)
                _lastError.value = "discord_error_token_refresh_failed"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: Throwable) {
                DiscordLog.e(TAG, "authorize: unexpected failure", e)
                _lastError.value = "discord_error_loopback_timeout"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } finally {
                authorizeInProgress = false
            }
        }
    }

    suspend fun fetchCurrentUser(token: String): DiscordUser? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("https://discord.com/api/v10/users/@me")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", token)
                conn.setRequestProperty("Accept", "application/json")

                val responseCode = conn.responseCode
                val responseBody = if (responseCode in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                }
                conn.disconnect()

                if (responseCode !in 200..299) {
                    DiscordLog.w(TAG, "fetchCurrentUser: HTTP $responseCode body=$responseBody")
                    return@withContext null
                }

                val json = org.json.JSONObject(responseBody)
                val id = json.getString("id")
                val username = json.getString("username")
                val name = json.optString("global_name", username)
                val avatarHash = json.optString("avatar")
                val avatar = if (avatarHash.isNotEmpty() && avatarHash != "null") {
                    "https://cdn.discordapp.com/avatars/$id/$avatarHash.png"
                } else null

                DiscordUser(id, username, name, avatar)
            } catch (e: Exception) {
                DiscordLog.e(TAG, "fetchCurrentUser: exception", e)
                null
            }
        }

    fun setActivity(
        activity: DiscordActivity,
        songId: String? = null,
        isPlaying: Boolean = true,
        status: PresenceStatus = PresenceStatus.Online,
    ) {
        pendingActivity = activity
        pendingSongId = songId
        pendingIsPlaying = isPlaying
        pendingStatus = status

        if (!_ready) {
            android.util.Log.w("DiscordRPC", "[RpcManager] setActivity: NOT READY, queued as pending")
            return
        }

        val songChanged = songId != currentSongId
        val playStateChanged = isPlaying != currentIsPlaying
        val now = System.currentTimeMillis()
        val elapsed = now - lastActivitySentAtMs

        val targetStartMs = if (isPlaying) activity.startTimestamp.takeIf { it > 0L } else null
        val targetEndMs = if (isPlaying) activity.endTimestamp?.takeIf { it > 0L } else null

        val timestampsChanged = (lastActivity?.endMs != targetEndMs) ||
                (targetStartMs != null && lastActivity?.startMs != null &&
                        kotlin.math.abs(targetStartMs - (lastActivity?.startMs ?: 0L)) > 3000L)

        // If identical state already displayed, ignore completely
        if (!songChanged && !playStateChanged && !timestampsChanged &&
            activity.details == lastActivity?.details &&
            activity.state == lastActivity?.state &&
            activity.largeImage == currentLargeImageUrl
        ) {
            return
        }

        // Discord Op 3 Rate Limit: maximum 1 update every 2.5 seconds
        if (lastActivitySentAtMs > 0L && elapsed < 2_500L) {
            val delayMs = 2_500L - elapsed
            android.util.Log.d("DiscordRPC", "[RpcManager] Rate-limited (<2.5s since last send). Scheduling trailing update in ${delayMs}ms")
            trailingUpdateJob?.cancel()
            trailingUpdateJob = scope.launch {
                kotlinx.coroutines.delay(delayMs)
                val latestActivity = pendingActivity ?: activity
                val latestSongId = pendingSongId ?: songId
                val latestPlaying = pendingIsPlaying
                sendActivityNow(latestActivity, latestSongId, latestPlaying, status)
            }
            return
        }

        trailingUpdateJob?.cancel()
        sendActivityNow(activity, songId, isPlaying, status)
    }

    private fun sendActivityNow(
        activity: DiscordActivity,
        songId: String?,
        isPlaying: Boolean,
        status: PresenceStatus
    ) {
        lastActivitySentAtMs = System.currentTimeMillis()

        val isSameSong = (currentSongId != null && currentSongId == songId)
        currentSongId = songId
        currentLargeImageUrl = activity.largeImage
        currentIsPlaying = isPlaying

        val buttons = buildList {
            if (!activity.button1Label.isNullOrEmpty() && !activity.button1Url.isNullOrEmpty()) {
                add(activity.button1Label to activity.button1Url)
            }
            if (!activity.button2Label.isNullOrEmpty() && !activity.button2Url.isNullOrEmpty()) {
                add(activity.button2Label to activity.button2Url)
            }
        }

        val cachedLargeImage = DiscordExternalAssets.getCached(activity.largeImage)
            ?: (if (lastActivity?.largeImage?.startsWith("mp:") == true && isSameSong) lastActivity?.largeImage else null)
        val cachedSmallImage = DiscordExternalAssets.getCached(activity.smallImage)
            ?: (if (lastActivity?.smallImage?.startsWith("mp:") == true && isSameSong) lastActivity?.smallImage else null)

        val payload = DiscordPresence.buildActivity(
            name = activity.name.orEmpty(),
            type = activityTypeToEnum(activity.activityType),
            details = activity.details,
            state = activity.state,
            largeImage = cachedLargeImage,
            largeText = null,
            smallImage = cachedSmallImage,
            smallText = activity.smallText,
            startMs = if (isPlaying) activity.startTimestamp.takeIf { it > 0L } else null,
            endMs = if (isPlaying) activity.endTimestamp?.takeIf { it > 0L } else null,
            buttons = buttons,
        )

        lastActivity = payload

        try {
            val presenceJson = DiscordPresence.buildPresenceUpdate(
                status = status,
                activities = listOf(payload),
            )
            android.util.Log.i("DiscordRPC",
                "[RpcManager] >>> SENT to gateway: songId=$songId, playing=$isPlaying, " +
                "title=${activity.details}, hasTimestamps=${payload.startMs != null}, hasImage=${cachedLargeImage != null}")
            gateway.send(org.json.JSONObject(presenceJson))
        } catch (e: Exception) {
            android.util.Log.e("DiscordRPC", "[RpcManager] gateway.send FAILED", e)
        }

        // Background external asset image resolution
        val needsLargeResolve = !activity.largeImage.isNullOrEmpty() && cachedLargeImage == null
        val needsSmallResolve = !activity.smallImage.isNullOrEmpty() && cachedSmallImage == null

        if (!needsLargeResolve && !needsSmallResolve) return

        val largeImageUrl = activity.largeImage
        val smallImageUrl = activity.smallImage
        val tokenHeader =
            if (accessToken?.startsWith("Bot ") == true) accessToken!! else accessToken ?: ""
        val appId = dev.jigen.zimusic.discord.DiscordDefaults.DISCORD_APP_ID.toString()
        val capturedSongId = songId

        if (currentlyResolvingImageUrl != largeImageUrl || imageResolutionJob?.isActive != true) {
            imageResolutionJob?.cancel()
            currentlyResolvingImageUrl = largeImageUrl
            imageResolutionJob = scope.launch {
                val largeResolved = if (needsLargeResolve && !largeImageUrl.isNullOrEmpty()) {
                    DiscordExternalAssets.resolve(largeImageUrl, appId, tokenHeader)
                } else cachedLargeImage

                val smallResolved = if (needsSmallResolve && !smallImageUrl.isNullOrEmpty()) {
                    DiscordExternalAssets.resolve(smallImageUrl, appId, tokenHeader)
                } else cachedSmallImage

                if (currentSongId != capturedSongId) return@launch

                val latestPayload = lastActivity ?: return@launch
                val nowPlaying = currentIsPlaying
                val payloadWithImages = latestPayload.copy(
                    largeImage = largeResolved ?: latestPayload.largeImage,
                    smallImage = smallResolved ?: latestPayload.smallImage,
                    startMs = if (nowPlaying) latestPayload.startMs else null,
                    endMs = if (nowPlaying) latestPayload.endMs else null
                )

                lastActivity = payloadWithImages
                lastActivitySentAtMs = System.currentTimeMillis()

                try {
                    val presenceJson = DiscordPresence.buildPresenceUpdate(
                        status = status,
                        activities = listOf(payloadWithImages),
                    )
                    gateway.send(org.json.JSONObject(presenceJson))
                    android.util.Log.i("DiscordRPC",
                        "[RpcManager] >>> IMAGE RESOLVED & re-sent for songId=$capturedSongId, " +
                        "nowPlaying=$nowPlaying, hasTimestamps=${payloadWithImages.startMs != null}")
                } catch (e: Exception) {
                    DiscordLog.e(TAG, "setActivity: image re-send failed", e)
                }
            }
        }
    }

    fun clear() {
        android.util.Log.d("DiscordRPC", "[RpcManager] clear() called, currentSongId=$currentSongId, ready=$_ready")
        trailingUpdateJob?.cancel()
        pendingActivity = null
        pendingIsPlaying = false
        lastActivitySentAtMs = 0L
        if (!_ready) {
            DiscordLog.w(TAG, "clear: skipping — not ready")
            return
        }
        if (lastActivity == null && currentSongId == null) {
            DiscordLog.d(TAG, "clear: already cleared, skipping")
            return
        }
        lastActivity = null
        currentSongId = null
        currentLargeImageUrl = null
        currentlyResolvingImageUrl = null
        currentIsPlaying = false
        imageResolutionJob?.cancel()
        try {
            gateway.send(
                org.json.JSONObject(
                    DiscordPresence.buildPresenceUpdate(
                        status = PresenceStatus.Online,
                        activities = emptyList()
                    )
                )
            )
        } catch (e: IllegalStateException) {
            DiscordLog.w(TAG, "clear: gateway not open", e)
        } catch (e: Throwable) {
            DiscordLog.e(TAG, "clear: send failed", e)
        }
    }

    fun reconnectWithToken(token: String) {
        if (!initialized) {
            DiscordLog.w(TAG, "reconnectWithToken: not initialized, ignoring")
            return
        }
        if (_connectionStatus.value == Status.Connected && accessToken == token && _ready) {
            DiscordLog.d(TAG, "reconnectWithToken: already connected with this token")
            return
        }
        if (_connectionStatus.value == Status.Authorizing && accessToken == token) {
            DiscordLog.d(TAG, "reconnectWithToken: already authorizing with this token")
            return
        }

        scope.launch {
            reconnectMutex.withLock {
                accessToken = token
                _accessTokenFlow.value = token
                DiscordTokenStore.storeAccessToken(token)
                _connectionStatus.value = Status.Authorizing
                try {
                    val refreshToken = DiscordTokenStore.getRefreshToken()
                    val expiresAt = DiscordTokenStore.getExpiresAt()
                    val nowSec = System.currentTimeMillis() / 1000L
                    val needsRefresh = !refreshToken.isNullOrEmpty() &&
                        expiresAt > 0L &&
                        (expiresAt - nowSec) < 3600L


                    if (needsRefresh) {
                        DiscordLog.i(TAG, "reconnectWithToken: proactive token refresh")
                        val refreshed = try {
                            auth.refresh(refreshToken)
                        } catch (e: DiscordAuthException.InvalidGrant) {
                            DiscordLog.w(
                                TAG,
                                "reconnectWithToken: refresh invalid_grant, logging out",
                                e
                            )
                            _lastError.value = "discord_error_token_refresh_failed"
                            logout()
                            return@withLock
                        } catch (e: Throwable) {
                            DiscordLog.w(
                                TAG,
                                "reconnectWithToken: refresh failed, continuing with old token",
                                e
                            )
                            null
                        }
                        if (refreshed != null) {
                                    accessToken = refreshed.accessToken
                            _accessTokenFlow.value = refreshed.accessToken
                            DiscordTokenStore.storeFull(
                                refreshed.accessToken,
                                refreshed.refreshToken,
                                refreshed.expiresInSec,
                            )
                        }
                    }

                    runCatching { gateway.close() }
                    gateway.token = accessToken ?: token
                    gateway.connect()
                } catch (e: Throwable) {
                    DiscordLog.e(TAG, "reconnectWithToken: connect/identify failed", e)
                    _lastError.value = "discord_error_loopback_timeout"
                    _connectionStatus.value = Status.Disconnected
                }
            }
        }
    }

    internal suspend fun refreshAndReconnect() {
        val refreshToken = DiscordTokenStore.getRefreshToken()
        if (refreshToken.isNullOrEmpty()) {
            DiscordLog.w(TAG, "refreshAndReconnect: no refresh token available, logging out")
            _lastError.value = "discord_error_token_refresh_failed"
            logout()
            return
        }

        val refreshed = try {
            auth.refresh(refreshToken)
        } catch (e: DiscordAuthException.InvalidGrant) {
            DiscordLog.w(TAG, "refreshAndReconnect: refresh token rejected, logging out", e)
            _lastError.value = "discord_error_token_refresh_failed"
            logout()
            return
        } catch (e: Throwable) {
            DiscordLog.e(TAG, "refreshAndReconnect: token refresh failed", e)
            _lastError.value = "discord_error_token_refresh_failed"
            return
        }

        DiscordTokenStore.storeFull(
            refreshed.accessToken,
            refreshed.refreshToken,
            refreshed.expiresInSec,
        )
        reconnectWithToken(refreshed.accessToken)
    }

    fun disconnect() {
        DiscordLog.i(TAG, "disconnect: closing gateway, clearing ready/authorized")
        imageResolutionJob?.cancel()
        pendingActivity = null
        pendingIsPlaying = false
        runCatching { gateway.close() }
        _connectionStatus.value = Status.Disconnected
        _ready = false
        _authorized = false
        currentSongId = null
        currentIsPlaying = false
    }

    fun destroy() {
        DiscordLog.i(
            TAG,
            String.format(
                "destroy: cancelling scope and tearing down (initialized=%s)",
                initialized
            )
        )
        imageResolutionJob?.cancel()
        pendingActivity = null
        pendingIsPlaying = false
        runCatching { gateway.close() }

        scope.cancel()
        _ready = false
        _authorized = false
        initialized = false
        _connectionStatus.value = Status.Disconnected
        lastActivity = null
        currentSongId = null
        currentIsPlaying = false
    }

    fun logout() {
        DiscordLog.i(TAG, "logout: clearing tokens and disconnecting")
        disconnect()
        accessToken = null
        _accessTokenFlow.value = null
        _currentUser.value = null
        DiscordTokenStore.clear()
        _lastError.value = null
        lastActivity = null
    }

    internal fun handleGatewayEvent(event: GatewayEvent) {
        scope.launch {
            when (event) {
                is GatewayEvent.Ready -> {
                    DiscordLog.i(
                        TAG,
                        String.format(
                            "gateway: READY (sessionId prefix=%s)",
                            event.sessionId.take(8)
                        )
                    )
                    _ready = true
                    _authorized = true
                    _connectionStatus.value = Status.Connected
                    _lastError.value = null
                    val token = accessToken ?: return@launch
                    scope.launch {
                        val user = fetchCurrentUser(token)
                        _currentUser.value = user
                    }
                    val pending = pendingActivity
                    if (pending != null) {
                        DiscordLog.i(TAG, "gateway: READY, re-sending pending presence (${pending.name}, playing=${pendingIsPlaying})")
                        setActivity(pending, pendingSongId, pendingIsPlaying, pendingStatus)
                    }
                }

                is GatewayEvent.Resumed -> {
                    DiscordLog.i(TAG, "gateway: RESUMED")
                    _ready = true
                    _authorized = true
                    _connectionStatus.value = Status.Connected
                    _lastError.value = null
                    val pending = pendingActivity
                    if (pending != null) {
                        DiscordLog.i(TAG, "gateway: RESUMED, re-sending pending presence (${pending.name}, playing=${pendingIsPlaying})")
                        setActivity(pending, pendingSongId, pendingIsPlaying, pendingStatus)
                    }
                }

                is GatewayEvent.Disconnected -> {
                    DiscordLog.i(TAG, "gateway: Disconnected (auto-reconnecting in background)")
                    _ready = false
                    _connectionStatus.value = Status.Disconnected
                    imageResolutionJob?.cancel()
                    imageResolutionJob = null
                }

                is GatewayEvent.Reconnect -> {
                    DiscordLog.i(TAG, "gateway: Reconnect event received")
                }

                is GatewayEvent.InvalidSession -> {
                    DiscordLog.w(TAG, "gateway: Invalid Session (resumable=${event.resumable})")
                }

                is GatewayEvent.Hello -> Unit
                is GatewayEvent.HeartbeatAck -> Unit
            }
        }
    }

    internal fun activityTypeToEnum(code: Int): ActivityType = when (code) {
        0 -> ActivityType.Playing
        1 -> ActivityType.Streaming
        2 -> ActivityType.Listening
        3 -> ActivityType.Watching
        4 -> ActivityType.Custom
        5 -> ActivityType.Competing
        else -> ActivityType.Listening
    }
}

