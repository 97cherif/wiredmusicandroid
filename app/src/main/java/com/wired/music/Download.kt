package com.wired.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/** Saves songs to Music/TheWiredMusic as M4A (no re-encoding, so it is fast and keeps the original quality). */
class DownloadService : Service() {
    companion object {
        const val ACTION_STOP = "com.wired.music.STOP_DL"
        private const val CH = "downloads"
        private val queue = LinkedBlockingQueue<Track>()
        val busy = MutableStateFlow(false)
        @Volatile var cancel = false
        @Volatile private var call: Call? = null
        @Volatile private var total = 0
        @Volatile private var done = 0
        @Volatile private var ok = 0

        fun enqueue(ctx: Context, list: List<Track>) {
            val l = list.filter { it.online }
            if (l.isEmpty()) { Status.set("Nothing to download", 3000); return }
            if (!busy.value) { total = 0; done = 0; ok = 0; cancel = false }
            busy.value = true
            total += l.size
            l.forEach { queue.add(it) }
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        fun stop() { cancel = true; queue.clear(); call?.cancel() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notif("Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive != true) job = scope.launch { process() }
        return START_NOT_STICKY
    }

    private fun notif(text: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, DownloadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle("THE WIRED MUSIC")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setProgress(total, (done - 1).coerceAtLeast(0), false)
            .addAction(0, "Stop", stop).build()
    }

    private suspend fun process() {
        do {
            while (true) {
                val t = queue.poll() ?: break
                if (cancel) break
                done++
                val msg = "Downloading $done/$total: ${t.title}"
                Status.set(msg)
                getSystemService(NotificationManager::class.java).notify(1, notif(msg))
                try {
                    withContext(Dispatchers.IO) { downloadOne(t) }
                    ok++
                    Repo.update(t.id) { it.copy(saved = true) }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        } while (queue.isNotEmpty() && !cancel)
        val summary = if (cancel) "Stopped - $ok song(s) saved" else "Downloaded $ok of $total"
        Status.set(summary, 7000)
        busy.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle("THE WIRED MUSIC")
            .setContentText(summary + " - in Music/TheWiredMusic").setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(2, n)
        stopSelf()
    }

    private fun safe(s: String) = s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "track" }

    private fun downloadOne(t: Track) {
        val aud = Resolve.audioFor(t)
        val name = safe(if (t.artist.isNotBlank() && t.artist != "YouTube" && t.artist != "Spotify") "${t.artist} - ${t.title}" else t.title)
        val cr = contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.${aud.ext}")
            put(MediaStore.Audio.Media.MIME_TYPE, aud.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/TheWiredMusic")
            put(MediaStore.Audio.Media.TITLE, t.title)
            put(MediaStore.Audio.Media.ARTIST, t.artist)
            if (t.album.isNotBlank() && t.album != "YouTube" && t.album != "Spotify") put(MediaStore.Audio.Media.ALBUM, t.album)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: throw IOException("can't create file")
        try {
            (cr.openOutputStream(uri) ?: throw IOException("can't write file")).use { fetchTo(aud.url, it) }
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            cr.update(uri, done, null, null)
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
    }

    private fun copy(input: InputStream, out: OutputStream, buf: ByteArray): Long {
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            if (cancel) throw IOException("stopped")
            out.write(buf, 0, r); n += r
        }
        return n
    }

    /** Downloads in 2 MB ranges (like yt-dlp does) so YouTube doesn't throttle the connection. */
    private fun fetchTo(url: String, out: OutputStream) {
        var pos = 0L; var total = -1L
        val chunk = 2L * 1024 * 1024
        val buf = ByteArray(32 * 1024)
        while (total < 0 || pos < total) {
            if (cancel) throw IOException("stopped")
            val req = Request.Builder().url(url).header("User-Agent", YT.UA).header("Range", "bytes=$pos-${pos + chunk - 1}").build()
            val c = YT.client.newCall(req); call = c
            c.execute().use { r ->
                val body = r.body ?: throw IOException("empty response")
                if (r.code == 200) { copy(body.byteStream(), out, buf); total = pos; return }
                if (r.code != 206) throw IOException("HTTP ${r.code}")
                total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: throw IOException("unknown size")
                val n = copy(body.byteStream(), out, buf)
                if (n == 0L) throw IOException("stalled")
                pos += n
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
