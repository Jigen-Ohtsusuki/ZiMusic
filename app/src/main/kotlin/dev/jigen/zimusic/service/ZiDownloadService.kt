package dev.jigen.zimusic.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.MediaItem
import dev.jigen.providers.innertube.Innertube
import dev.jigen.providers.innertube.models.bodies.PlayerBody
import dev.jigen.providers.innertube.requests.player
import dev.jigen.zimusic.Database
import dev.jigen.zimusic.R
import dev.jigen.zimusic.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

val _ziDownloadState = MutableStateFlow(false)
val ziDownloadState = _ziDownloadState.asStateFlow()

private const val DOWNLOAD_NOTIFICATION_ID = 9001
private const val NOTIFICATION_TICK_MS = 500L

private enum class TaskStatus { Queued, Downloading, Done, Error }

private data class DownloadTask(
    val mediaId: String,
    val title: String,
    val parentName: String?,
    @Volatile var progress: Int = 0,
    @Volatile var status: TaskStatus = TaskStatus.Queued
)

private object DownloadManager {
    val tasks = CopyOnWriteArrayList<DownloadTask>()
    val downloadQueue = Channel<DownloadTask>(Channel.UNLIMITED)
    val currentParentName = MutableStateFlow<String?>(null)

    fun findTask(mediaId: String) = tasks.firstOrNull { it.mediaId == mediaId }

    fun clearCompleted() {
        tasks.removeAll { it.status == TaskStatus.Done }
        val hasAny = tasks.isNotEmpty()
        if (!hasAny) currentParentName.value = null
    }
}

class ZiDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickerJob: Job? = null
    private var isForeground = false
    private var doneNotifPosted = false

    private val channelId get() = ServiceNotifications.download.id

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceNotifications.download.upsertChannel(this)
        serviceScope.launch {
            for (task in DownloadManager.downloadQueue) processDownload(task)
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mediaId    = intent?.getStringExtra("mediaId")   ?: return START_NOT_STICKY
        val title      = intent.getStringExtra("title")      ?: "Unknown"
        val parentName = intent.getStringExtra("parentName")

        serviceScope.launch {
            val existing = DownloadManager.findTask(mediaId)
            if (existing != null && existing.status != TaskStatus.Error) return@launch

            val existingSong = Database.instance.songSync(mediaId)
            if (existingSong?.isDownloaded == true) {
                val file = existingSong.downloadPath?.let { File(it) }
                if (file?.exists() == true) return@launch
            }

            val hasActive = DownloadManager.tasks.any {
                it.status == TaskStatus.Queued || it.status == TaskStatus.Downloading
            }
            if (!hasActive) DownloadManager.clearCompleted()

            if (DownloadManager.tasks.isEmpty()) {
                DownloadManager.currentParentName.value = parentName
            }

            if (existing?.status == TaskStatus.Error) DownloadManager.tasks.remove(existing)

            val task = DownloadTask(mediaId, title, parentName)
            DownloadManager.tasks.add(task)
            DownloadManager.downloadQueue.send(task)

            withContext(Dispatchers.Main) {
                if (!isForeground) {
                    startForegroundCompat(buildNotification(DownloadManager.tasks.toList(), true))
                    isForeground = true
                }
                if (tickerJob?.isActive != true) {
                    doneNotifPosted = false
                    startTicker()
                }
            }
        }

        return START_STICKY
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_TICK_MS.milliseconds)
                tick()
            }
        }
    }

    private suspend fun tick() {
        val tasks = DownloadManager.tasks.toList()
        val total = tasks.size
        if (total == 0) return

        val hasActive = tasks.any { it.status == TaskStatus.Queued || it.status == TaskStatus.Downloading }
        _ziDownloadState.value = hasActive

        val notification = buildNotification(tasks, hasActive)

        withContext(Dispatchers.Main) {
            if (hasActive) {
                notify(notification)
            } else {
                if (isForeground) {
                    stopForeground(false)
                    isForeground = false
                }
                if (!doneNotifPosted) {
                    doneNotifPosted = true
                    notify(notification)
                    tickerJob?.cancel()
                }
            }
        }
    }

    private fun buildNotification(tasks: List<DownloadTask>, isActive: Boolean): Notification {
        val total            = tasks.size
        val parentName       = DownloadManager.currentParentName.value
        val downloadingTasks = tasks.filter { it.status == TaskStatus.Downloading }
        val queuedTasks      = tasks.filter { it.status == TaskStatus.Queued }
        val doneTasks        = tasks.filter { it.status == TaskStatus.Done }
        val failedTasks      = tasks.filter { it.status == TaskStatus.Error }
        val finishedCount    = doneTasks.size + failedTasks.size

        val totalProgressSum = tasks.sumOf {
            when (it.status) {
                TaskStatus.Done        -> 100
                TaskStatus.Downloading -> it.progress
                else                   -> 0
            }
        }
        val totalPercentage = if (total > 0) (totalProgressSum.toFloat() / total).toInt().coerceIn(0, 100) else 0

        val title = if (!parentName.isNullOrBlank()) {
            if (isActive)
                getString(R.string.downloading_from_playlist, minOf(finishedCount + 1, total), total, parentName)
            else if (failedTasks.isNotEmpty())
                getString(R.string.download_finished_with_errors, doneTasks.size, failedTasks.size)
            else
                getString(R.string.downloaded_from_playlist, total, parentName)
        } else if (total == 1) {
            val task = tasks.first()
            if (isActive)
                "${getString(R.string.downloading)}: ${task.title}"
            else if (task.status == TaskStatus.Error)
                getString(R.string.download_failed_title)
            else
                getString(R.string.downloaded_song, task.title)
        } else {
            if (isActive)
                getString(R.string.downloading_generic, minOf(finishedCount + 1, total), total)
            else if (failedTasks.isNotEmpty())
                getString(R.string.download_finished_with_errors, doneTasks.size, failedTasks.size)
            else
                "Downloaded $total songs"
        }
        val content = when {
            isActive && downloadingTasks.isNotEmpty() ->
                "↓ ${downloadingTasks.first().title} (${downloadingTasks.first().progress}%)"
            isActive ->
                "${queuedTasks.size} song${if (queuedTasks.size == 1) "" else "s"} queued"
            failedTasks.isNotEmpty() ->
                getString(R.string.download_tap_to_retry, failedTasks.size)
            else ->
                getString(R.string.done)
        }
        val listText = buildString {
            downloadingTasks.forEach { task ->
                append("↓ ${task.title}: ${task.progress}%\n")
            }
            queuedTasks.take(15).forEach { task ->
                append("• ${task.title}: ${getString(R.string.queued)}\n")
            }
            val hiddenQueued = queuedTasks.size - minOf(queuedTasks.size, 15)
            if (hiddenQueued > 0) append("  … and $hiddenQueued more queued\n")
            if (failedTasks.isNotEmpty()) {
                if (isActive) {
                    failedTasks.forEach { task ->
                        append("✕ ${task.title}: ${getString(R.string.download_error)}\n")
                    }
                } else {
                    append("\n${getString(R.string.download_error_retry_hint)}\n")
                    failedTasks.forEach { task ->
                        append("✕ ${task.title}\n")
                    }
                }
            }
            if (!isActive && failedTasks.isEmpty()) append(getString(R.string.done))
        }.trim()

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(content)
            .setSubText("$totalPercentage%")
            .setProgress(100, totalPercentage, isActive && totalPercentage == 0)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setOngoing(isActive)
            .setAutoCancel(failedTasks.isEmpty() && !isActive)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(
            DOWNLOAD_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(DOWNLOAD_NOTIFICATION_ID, notification)
        }
    }

    private suspend fun processDownload(task: DownloadTask) {
        task.status = TaskStatus.Downloading

        runCatching {
            val downloadsDir = File(applicationContext.filesDir, "downloads")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val file = File(downloadsDir, "${task.mediaId}.zdl")

            var streamUrl    = ""
            var targetLength = 0L

            suspend fun refreshStreamUrl() {
                val result = Innertube.player(PlayerBody(videoId = task.mediaId))
                val body   = result?.getOrNull() ?: error("Player response body is null")
                val format = body.streamingData?.highestQualityFormat ?: error("No format found")
                streamUrl    = format.findUrl(task.mediaId) ?: error("URL could not be resolved")
                targetLength = format.contentLength ?: error("Missing content length")
            }

            refreshStreamUrl()

            var downloaded = if (file.exists()) file.length() else 0L
            val chunkSize  = 512 * 1024L
            var retries    = 0

            while (downloaded < targetLength && retries < 15) {
                val endByte = minOf(downloaded + chunkSize - 1, targetLength - 1)
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(streamUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.setRequestProperty("Range", "bytes=$downloaded-$endByte")
                    conn.connectTimeout = 15_000
                    conn.readTimeout    = 15_000
                    conn.connect()

                    when (conn.responseCode) {
                        in 200..206 -> {
                            conn.inputStream.use { input ->
                                FileOutputStream(file, true).use { output ->
                                    val buf = ByteArray(32 * 1024)
                                    var n: Int
                                    while (input.read(buf).also { n = it } != -1) {
                                        output.write(buf, 0, n)
                                        downloaded += n
                                        if (targetLength > 0) {
                                            task.progress = ((downloaded.toFloat() / targetLength) * 100)
                                                .toInt().coerceIn(0, 100)
                                        }
                                    }
                                }
                            }
                            retries = 0
                        }
                        403 -> { refreshStreamUrl(); retries++ }
                        else -> break
                    }
                } catch (_: Exception) {
                    retries++
                    delay((2_000L * retries).milliseconds)
                } finally {
                    conn?.disconnect()
                }
            }

            if (downloaded >= targetLength) {
                query { Database.instance.markAsDownloaded(task.mediaId, file.absolutePath) }
                task.progress = 100
                task.status   = TaskStatus.Done
            } else {
                task.status = TaskStatus.Error
            }
        }.onFailure {
            task.status = TaskStatus.Error
        }
    }

    companion object {
        fun scheduleDownload(context: Context, mediaItem: MediaItem, parentName: String? = null) {
            context.startService(Intent(context, ZiDownloadService::class.java).apply {
                putExtra("mediaId",    mediaItem.mediaId)
                putExtra("title",      mediaItem.mediaMetadata.title?.toString())
                putExtra("parentName", parentName)
            })
        }
    }
}
