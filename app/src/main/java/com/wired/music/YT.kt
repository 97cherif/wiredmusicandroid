package com.wired.music

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class NpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: NpRequest): NpResponse {
        val body = request.dataToSend()?.toRequestBody()
        val b = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body).header("User-Agent", YT.UA)
        for ((k, v) in request.headers()) { b.removeHeader(k); v.forEach { b.addHeader(k, it) } }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return NpResponse(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

data class Aud(val url: String, val ext: String, val mime: String)

/** YouTube access through NewPipeExtractor. Every function here is blocking: call from a background thread. */
object YT {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private var inited = false

    @Synchronized fun init() {
        if (!inited) { NewPipe.init(NpDownloader(client)); inited = true }
    }

    fun idOf(url: String): String? =
        Regex("""(?:v=|youtu\.be/|/shorts/|/embed/|/live/)([\w-]{11})""").find(url)?.groupValues?.get(1)

    fun isLink(s: String): Boolean = s.contains("youtu.be/") || s.contains("youtube.com/")
    fun isPlaylist(u: String): Boolean =
        u.contains("/playlist") || (u.contains("list=") && !Regex("[?&]v=").containsMatchIn(u) && !u.contains("youtu.be/"))

    private fun toItem(i: StreamInfoItem): YtItem? {
        val id = idOf(i.url) ?: return null
        return YtItem(id, i.name ?: "", i.uploaderName ?: "", i.duration.toDouble().coerceAtLeast(0.0), i.thumbnails.lastOrNull()?.url)
    }

    fun search(q: String, music: Boolean): List<YtItem> {
        init()
        val s = ServiceList.YouTube
        val filter = if (music) YoutubeSearchQueryHandlerFactory.MUSIC_SONGS else YoutubeSearchQueryHandlerFactory.VIDEOS
        val info = SearchInfo.getInfo(s, s.searchQHFactory.fromQuery(q, listOf(filter), ""))
        return info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { toItem(it) }
    }

    fun playlist(url: String, cap: Int = 300): Pair<String, List<YtItem>> {
        init()
        val s = ServiceList.YouTube
        val info = PlaylistInfo.getInfo(s, url)
        val out = ArrayList<YtItem>()
        info.relatedItems.forEach { i -> toItem(i)?.let { out.add(it) } }
        var page = info.nextPage
        while (page != null && out.size < cap) {
            val more = PlaylistInfo.getMoreItems(s, url, page)
            more.items.forEach { i -> toItem(i)?.let { out.add(it) } }
            page = more.nextPage
        }
        return (info.name ?: "playlist") to out
    }

    fun video(url: String): YtItem {
        init()
        val ex = ServiceList.YouTube.getStreamExtractor(url)
        ex.fetchPage()
        val id = idOf(url) ?: throw IOException("not a YouTube video link")
        return YtItem(id, ex.name ?: "", ex.uploaderName ?: "", ex.length.toDouble().coerceAtLeast(0.0), ex.thumbnails.lastOrNull()?.url)
    }

    fun audio(vid: String): Aud {
        init()
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$vid")
        ex.fetchPage()
        val list = ex.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val pick = list.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: list.maxByOrNull { it.averageBitrate }
            ?: throw IOException("no audio stream found")
        val m4a = pick.format == MediaFormat.M4A
        return Aud(pick.content, if (m4a) "m4a" else "webm", if (m4a) "audio/mp4" else "audio/webm")
    }

    /** Finds the YouTube video for a Spotify song: YouTube Music first, closest length wins. */
    fun match(artist: String, title: String, dur: Double): String? {
        val q = "$artist - $title".trim()
        var items = try { search(q, true) } catch (_: Exception) { emptyList() }
        if (items.isEmpty()) items = search(q, false)
        if (items.isEmpty()) return null
        if (dur > 0) {
            items.firstOrNull { it.durSec > 0 && abs(it.durSec - dur) <= 4 }?.let { return it.id }
            val best = items.filter { it.durSec > 0 }.minByOrNull { abs(it.durSec - dur) }
            if (best != null && abs(best.durSec - dur) <= 8) return best.id
        }
        return items.first().id
    }
}

/** Turns a track into a playable stream URL (and remembers YouTube matches for Spotify songs). */
object Resolve {
    private val cache = ConcurrentHashMap<String, Pair<Aud, Long>>()

    fun audio(vid: String): Aud {
        val c = cache[vid]
        if (c != null && System.currentTimeMillis() - c.second < 4 * 3600_000L) return c.first
        val a = YT.audio(vid)
        cache[vid] = a to System.currentTimeMillis()
        return a
    }

    fun forget(trackId: String) { Repo.byId(trackId)?.ytId?.let { cache.remove(it) } }

    fun videoId(t: Track): String {
        t.ytId?.let { return it }
        Repo.byId(t.id)?.ytId?.let { return it }
        val id = YT.match(t.artist, t.title, t.durSec) ?: throw IOException("couldn't find this song on YouTube")
        Repo.update(t.id) { it.copy(ytId = id) }
        return id
    }

    fun audioFor(t: Track): Aud = audio(videoId(t))
}

/** "Artist - Title" clean-up for YouTube video results (port of the Windows YtFix). */
fun ytFix(t: Track): Track {
    val title = t.title.replace(Regex("""[\(\[][^)\]]*(official|lyric|video|audio|visuali[sz]er|hd|hq|4k)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim()
    val ch = t.artist.replace(Regex("""\s*-\s*Topic$|VEVO$|\s*Official$""", RegexOption.IGNORE_CASE), "").trim()
    val p = title.split(Regex("""\s+[-–—]\s+"""))
    return if (p.size >= 2 && p[0].length <= 50) t.copy(artist = p[0].trim(), title = p.drop(1).joinToString(" - ").trim())
    else t.copy(artist = ch.ifEmpty { "YouTube" }, title = title.ifEmpty { t.title })
}

fun YtItem.toTrack(): Track = ytFix(Track(title = title, artist = artist, album = "YouTube", durSec = durSec, ytId = id, artUrl = thumb))
