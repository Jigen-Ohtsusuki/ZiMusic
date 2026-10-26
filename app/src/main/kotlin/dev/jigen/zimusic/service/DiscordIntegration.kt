package dev.jigen.zimusic.service

import androidx.media3.common.MediaItem
import dev.jigen.core.ui.utils.songBundle
import dev.jigen.zimusic.discord.DiscordActivity
import dev.jigen.zimusic.discord.DiscordRpcManager
import dev.jigen.zimusic.discord.DiscordTokenStore
import dev.jigen.zimusic.discord.PresenceStatus
import dev.jigen.zimusic.preferences.DiscordPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

object DiscordIntegration {
    private const val TAG = "DiscordIntegration"

    fun init(service: PlayerService, scope: CoroutineScope) {
        if (!DiscordRpcManager.isInitialized()) {
            DiscordRpcManager.init(service)
        }

        scope.launch {
            DiscordPreferences.enableDiscordRPCProperty.stateFlow.drop(1).collectLatest { enabled ->
                if (enabled) {
                    if (!DiscordRpcManager.isReady() && DiscordTokenStore.retrieve() != null) {
                        DiscordRpcManager.reconnectWithToken(DiscordTokenStore.retrieve()!!)
                    }
                } else if (DiscordRpcManager.isReady()) {
                    DiscordRpcManager.disconnect()
                }
            }
        }
    }

    private fun parseDurationText(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            val parts = text.trim().split(":").map { it.filter { ch -> ch.isDigit() }.toLong() }
            when (parts.size) {
                2 -> (parts[0] * 60 + parts[1]) * 1000L
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
                1 -> if (parts[0] > 0L) parts[0] * 1000L else null
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun updatePresence(
        mediaItem: MediaItem?,
        isPlaying: Boolean,
        currentPositionMs: Long = 0L,
        durationMs: Long? = null,
    ) {
        if (!DiscordPreferences.enableDiscordRPC || mediaItem == null) {
            DiscordRpcManager.clear()
            return
        }

        if (!DiscordRpcManager.isReady() && DiscordTokenStore.retrieve() != null) {
            if (DiscordRpcManager.connectionStatus.value == DiscordRpcManager.Status.Disconnected) {
                DiscordRpcManager.reconnectWithToken(DiscordTokenStore.retrieve()!!)
            }
        }

        val resolvedDurationMs = durationMs?.takeIf { it > 0L }
            ?: parseDurationText(mediaItem.mediaMetadata.extras?.songBundle?.durationText)

        val now = System.currentTimeMillis()
        val startMs = if (isPlaying) now - currentPositionMs.coerceAtLeast(0L) else 0L
        val endMs = if (isPlaying && resolvedDurationMs != null && resolvedDurationMs > 0L) {
            startMs + resolvedDurationMs
        } else {
            null
        }

        val artist = mediaItem.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }?.take(128) ?: "Unknown Artist"
        val stateText = if (isPlaying) {
            artist
        } else {
            "Paused • $artist".take(128)
        }

        val songTitle = mediaItem.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }?.take(128) ?: "ZiMusic"
        val activity = DiscordActivity(
            activityType = DiscordActivity.TYPE_LISTENING,
            name = songTitle,
            state = stateText,
            details = songTitle,
            startTimestamp = startMs,
            endTimestamp = endMs,
            largeImage = mediaItem.mediaMetadata.artworkUri?.toString()?.replace("w120", "w512")?.replace("h120", "h512"),
            largeText = null,
            smallImage = null,
            smallText = null,
            button1Label = "Listen on YouTube Music",
            button1Url = "https://music.youtube.com/watch?v=${mediaItem.mediaId}",
            button2Label = "Listening via ZiMusic",
            button2Url = "https://github.com/Jigen-Ohtsusuki/ZiMusic"
        )

        DiscordRpcManager.setActivity(activity, songId = mediaItem.mediaId, isPlaying = isPlaying, status = PresenceStatus.Online)
    }

    fun clear() {
        DiscordRpcManager.clear()
    }
}

