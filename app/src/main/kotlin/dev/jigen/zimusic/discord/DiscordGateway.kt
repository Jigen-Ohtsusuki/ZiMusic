package dev.jigen.zimusic.discord

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import dev.jigen.zimusic.discord.DiscordLog
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import io.ktor.websocket.send

sealed interface GatewayEvent {
    data class Hello(val heartbeatIntervalMs: Long) : GatewayEvent
    data class Ready(val sessionId: String, val resumeGatewayUrl: String?) : GatewayEvent
    data class Resumed(val sessionId: String) : GatewayEvent
    data class HeartbeatAck(val lastSeq: Int?) : GatewayEvent
    data class InvalidSession(val resumable: Boolean) : GatewayEvent
    object Reconnect : GatewayEvent
    object Disconnected : GatewayEvent
}

class DiscordGateway(
    internal val scope: CoroutineScope,
    var token: String
) {
    val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)

    companion object {
        internal const val TAG = "DiscordGateway"
        internal const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        
        internal val client = HttpClient(CIO) {
            install(WebSockets)
        }
    }

    internal var session: io.ktor.websocket.DefaultWebSocketSession? = null
    internal var heartbeatJob: Job? = null
    internal var reconnectJob: Job? = null
    internal var sequenceNumber = AtomicLong(0)
    internal var sessionId: String? = null
    internal var resumeGatewayUrl: String? = null
    internal var heartbeatInterval = 0L
    @Volatile internal var heartbeatAckReceived = true
    @Volatile internal var userExplicitlyClosed = false
    @Volatile internal var isConnecting = false
    internal var currentReconnectDelay = 1000L

    fun connect() {
        if (session != null && session?.isActive == true) {
            DiscordLog.i(TAG, "Gateway already connected.")
            return
        }
        if (isConnecting) {
            DiscordLog.d(TAG, "Gateway connection attempt already in progress.")
            return
        }
        userExplicitlyClosed = false
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            isConnecting = true
            try {
                val url = resumeGatewayUrl ?: GATEWAY_URL
                DiscordLog.i(TAG, "Connecting to gateway at $url...")
                client.webSocket(url) {
                    session = this
                    isConnecting = false
                    currentReconnectDelay = 1000L
                    heartbeatAckReceived = true
                    DiscordLog.i(TAG, "WebSocket connected!")
                    
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                handleMessage(text)
                            }
                        }
                    } catch (e: Exception) {
                        DiscordLog.e(TAG, "WebSocket error", e)
                    } finally {
                        DiscordLog.i(TAG, "WebSocket closed")
                        session = null
                        heartbeatJob?.cancel()
                        events.tryEmit(GatewayEvent.Disconnected)
                        if (!userExplicitlyClosed) {
                            scheduleReconnect()
                        }
                    }
                }
            } catch (e: Exception) {
                DiscordLog.e(TAG, "Failed to connect", e)
                session = null
                heartbeatJob?.cancel()
                events.tryEmit(GatewayEvent.Disconnected)
                if (!userExplicitlyClosed) {
                    scheduleReconnect()
                }
            } finally {
                isConnecting = false
            }
        }
    }

    fun scheduleReconnect() {
        if (userExplicitlyClosed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            DiscordLog.i(TAG, "Scheduling gateway reconnection in ${currentReconnectDelay}ms...")
            delay(currentReconnectDelay)
            currentReconnectDelay = (currentReconnectDelay * 2).coerceAtMost(30_000L)
            connect()
        }
    }

    fun close() {
        userExplicitlyClosed = true
        isConnecting = false
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        sessionId = null
        resumeGatewayUrl = null
        sequenceNumber.set(0)
        scope.launch {
            try {
                session?.close()
            } catch (e: Exception) {
                DiscordLog.w(TAG, "Error closing session", e)
            } finally {
                session = null
            }
        }
    }

    fun send(json: JSONObject) {
        scope.launch {
            try {
                session?.send(json.toString())
            } catch (e: Exception) {
                DiscordLog.e(TAG, "Failed to send message", e)
            }
        }
    }

    internal fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val op = json.getInt("op")
            val s = if (json.has("s") && !json.isNull("s")) json.getLong("s") else null
            
            if (s != null) {
                sequenceNumber.set(s)
            }
            
            when (op) {
                10 -> { // Hello
                    val d = json.optJSONObject("d")
                    heartbeatInterval = d?.getLong("heartbeat_interval") ?: 41250L
                    events.tryEmit(GatewayEvent.Hello(heartbeatInterval))
                    startHeartbeat(heartbeatInterval)
                    
                    if (sequenceNumber.get() > 0 && !sessionId.isNullOrBlank()) {
                        resume()
                    } else {
                        identify()
                    }
                }
                0 -> { // Dispatch
                    val t = json.getString("t")
                    val d = json.optJSONObject("d")
                    when (t) {
                        "READY" -> {
                            sessionId = d?.getString("session_id") ?: ""
                            val resumeUrl = if (d != null && d.has("resume_gateway_url")) d.getString("resume_gateway_url") else null
                            resumeGatewayUrl = if (!resumeUrl.isNullOrEmpty()) "$resumeUrl/?v=10&encoding=json" else null
                            events.tryEmit(GatewayEvent.Ready(sessionId!!, resumeGatewayUrl))
                        }
                        "RESUMED" -> {
                            events.tryEmit(GatewayEvent.Resumed(sessionId!!))
                        }
                    }
                }
                11 -> { // Heartbeat ACK
                    heartbeatAckReceived = true
                    events.tryEmit(GatewayEvent.HeartbeatAck(sequenceNumber.get().toInt()))
                }
                9 -> { // Invalid Session
                    val resumable = json.optBoolean("d", false)
                    DiscordLog.w(TAG, "Invalid session (resumable=$resumable)")
                    if (!resumable) {
                        sessionId = null
                        sequenceNumber.set(0)
                        resumeGatewayUrl = null
                    }
                    scope.launch {
                        delay(200)
                        if (resumable && !sessionId.isNullOrBlank()) {
                            resume()
                        } else {
                            identify()
                        }
                    }
                }
                7 -> { // Reconnect
                    DiscordLog.i(TAG, "Discord requested reconnect (Opcode 7)")
                    scope.launch {
                        try {
                            session?.close()
                        } catch (e: Exception) {
                            DiscordLog.w(TAG, "Error closing session for reconnect", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DiscordLog.e(TAG, "Error handling message: $text", e)
        }
    }

    fun identify(newToken: String? = null) {
        val identify = JSONObject().apply {
            put("op", 2)
            put("d", JSONObject().apply {
                put("token", newToken ?: token)
                put("properties", DiscordSuperProperties.superProperties)
                put("presence", JSONObject().apply {
                    put("status", "online")
                    put("since", 0)
                    put("activities", org.json.JSONArray())
                    put("afk", false)
                })
            })
        }
        send(identify)
    }

    internal fun resume() {
        val resume = JSONObject().apply {
            put("op", 6)
            put("d", JSONObject().apply {
                put("token", token)
                put("session_id", sessionId)
                put("seq", sequenceNumber.get())
            })
        }
        send(resume)
    }

    internal fun startHeartbeat(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatAckReceived = true
        heartbeatJob = scope.launch {
            // Jitter for first heartbeat
            delay((interval * Random.nextDouble(0.0, 1.0)).toLong())
            while (isActive) {
                if (!heartbeatAckReceived) {
                    DiscordLog.w(TAG, "Missed Heartbeat ACK! Dead connection detected, closing to force reconnect...")
                    try {
                        session?.close()
                    } catch (e: Exception) {
                        DiscordLog.w(TAG, "Error closing dead session", e)
                    }
                    break
                }
                heartbeatAckReceived = false
                val heartbeat = JSONObject().apply {
                    put("op", 1)
                    put("d", if (sequenceNumber.get() > 0) sequenceNumber.get() else JSONObject.NULL)
                }
                send(heartbeat)
                delay(interval)
            }
        }
    }
}
