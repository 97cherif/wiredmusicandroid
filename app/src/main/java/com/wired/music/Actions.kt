package com.wired.music

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

/** Everything the buttons do. */
object Actions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun msg(e: Exception) = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(90)

    fun playNow(t: Track) {
        val i = Repo.indexOfMatch(t)
        if (i >= 0) Ctl.playIndex(i) else { Ctl.addTracks(listOf(t)); Ctl.playIndex(Repo.tracks.value.size - 1) }
    }

    fun queue(t: Track): Boolean {
        if (Repo.indexOfMatch(t) >= 0) return false
        Ctl.addTracks(listOf(t))
        return true
    }

    fun addYouTube(url: String) = scope.launch {
        Status.set("Connecting...")
        try {
            val l = withContext(Dispatchers.IO) {
                val u = url.trim()
                if (YT.isPlaylist(u)) YT.playlist(u).second.map { it.toTrack() } else listOf(YT.video(u).toTrack())
            }
            if (l.isEmpty()) { Status.set("Nothing found at that link", 5000); return@launch }
            val first = Repo.tracks.value.size
            Ctl.addTracks(l)
            Status.set(if (l.size == 1) "Added 1 song" else "Added ${l.size} songs", 5000)
            if (Ctl.index.value < 0) Ctl.playIndex(first)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Status.set("Couldn't connect: ${msg(e)}", 7000) }
    }

    fun addSpotify(url: String, dl: Boolean = false) = scope.launch {
        Status.set("Reading Spotify link...")
        try {
            val info = withContext(Dispatchers.IO) { Spotify.fetch(url) }
            val first = Repo.tracks.value.size
            Ctl.addTracks(info.tracks)
            Status.set(
                if (info.kind == "track") "Added 1 song"
                else "Added ${info.tracks.size} songs from ${info.name ?: "Spotify"}" +
                    (if (info.kind == "playlist" && info.tracks.size >= 100) " (Spotify only shows the first 100)" else ""), 6000
            )
            if (Ctl.index.value < 0) Ctl.playIndex(first)
            if (dl) download(info.tracks)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Status.set("Couldn't add Spotify link: ${msg(e)}", 7000) }
    }

    fun download(l: List<Track>) = DownloadService.enqueue(WiredApp.ctx, l)

    fun addFiles(ctx: Context, uris: List<Uri>) = scope.launch {
        Status.set("Reading files...")
        val l = withContext(Dispatchers.IO) { uris.mapNotNull { readLocal(ctx, it) } }
        if (l.isEmpty()) { Status.set("Couldn't read those files", 5000); return@launch }
        val first = Repo.tracks.value.size
        Ctl.addTracks(l)
        Status.set("Added ${l.size} song(s)", 4000)
        if (Ctl.index.value < 0) Ctl.playIndex(first)
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0).substringBeforeLast('.')
            }
        } catch (_: Exception) { }
        return "Unknown"
    }

    private fun readLocal(ctx: Context, uri: Uri): Track? {
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val name = displayName(ctx, uri)
            var title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.ifBlank { null }
            var artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifBlank { null }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.ifBlank { null }
            if (title == null) {
                val p = name.split(" - ", limit = 2)
                if (p.size == 2) { if (artist == null) artist = p[0].trim(); title = p[1].trim() } else title = name
            }
            val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifBlank { null }
            val year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4) ?: ""
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0
            val art = r.embeddedPicture?.let { bytes ->
                val f = File(ctx.filesDir, "art/${abs(uri.toString().hashCode())}.jpg")
                f.parentFile?.mkdirs(); f.writeBytes(bytes); Uri.fromFile(f).toString()
            }
            return Track(title = title, artist = artist ?: "Unknown artist", album = album ?: "", year = year, durSec = dur / 1000.0, uri = uri.toString(), artUrl = art)
        } catch (_: Exception) { return null } finally { try { r.release() } catch (_: Exception) { } }
    }
}
