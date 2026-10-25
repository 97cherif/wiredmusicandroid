package com.wired.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

data class Lrc(val t: Double, val s: String)

/** status: 0 idle, 1 searching, 2 found, 3 not found */
data class LyState(
    val trackId: String? = null, val status: Int = 0, val plain: String = "",
    val sync: List<Lrc>? = null, val hidden: Boolean = false,
)

/** Lyrics come from LRCLIB (free, no login) and are only searched when the user taps "Search lyrics". */
object Lyrics {
    val state = MutableStateFlow(LyState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tag = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)\]""")

    fun parseLrc(text: String): List<Lrc> {
        val out = ArrayList<Lrc>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val ms = tag.findAll(line).toList()
            if (ms.isEmpty()) continue
            val txt = tag.replace(line, "").trim()
            for (m in ms) out.add(Lrc(m.groupValues[1].toInt() * 60 + m.groupValues[2].replace(':', '.').toDouble(), txt))
        }
        out.sortBy { it.t }
        return out
    }

    private fun build(id: String, plain: String?, synced: String?): LyState {
        val sy = if (!synced.isNullOrBlank()) parseLrc(synced).takeIf { it.size >= 3 } else null
        val p = if (sy != null) sy.joinToString("\n") { it.s } else (plain ?: synced ?: "").replace(Regex("""\[[^\]]*\]"""), "").trim()
        return LyState(id, 2, p, sy)
    }

    private fun cacheFile(t: Track): File {
        val key = (t.artist + "|" + t.title).lowercase()
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val d = File(WiredApp.ctx.filesDir, "lyrics"); d.mkdirs()
        return File(d, "$h.json")
    }

    /** Called when the playing track changes: shows lyrics only if you already searched this song before. */
    fun onTrack(t: Track?) {
        if (t == null) { state.value = LyState(); return }
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                try {
                    val f = cacheFile(t)
                    if (f.exists()) JSONObject(f.readText()).let { it.optString("plain", "") to it.optString("synced", "") } else null
                } catch (_: Exception) { null }
            }
            state.value = if (cached != null && (cached.first.isNotBlank() || cached.second.isNotBlank())) build(t.id, cached.first, cached.second) else LyState(t.id)
        }
    }

    fun click(t: Track) {
        val s = state.value
        if (s.trackId != t.id) return
        when (s.status) {
            2 -> state.value = s.copy(hidden = !s.hidden)
            1 -> {}
            else -> search(t)
        }
    }

    private fun search(t: Track) {
        state.value = LyState(t.id, 1)
        scope.launch {
            val res = try { withContext(Dispatchers.IO) { fetch(t) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (res == null || (res.first.isNullOrBlank() && res.second.isNullOrBlank())) {
                if (state.value.trackId == t.id) state.value = LyState(t.id, 3)
            } else {
                withContext(Dispatchers.IO) {
                    try { cacheFile(t).writeText(JSONObject().put("plain", res.first ?: "").put("synced", res.second ?: "").toString()) } catch (_: Exception) { }
                }
                if (state.value.trackId == t.id) state.value = build(t.id, res.first, res.second)
            }
        }
    }

    private fun get(url: String): String? {
        val r = Request.Builder().url(url).header("User-Agent", "TheWiredMusic/1.0").build()
        return try { YT.client.newCall(r).execute().use { if (it.isSuccessful) it.body?.string() else null } } catch (_: Exception) { null }
    }

    private fun clean(s: String): String =
        s.replace(Regex("""\s*[\(\[][^)\]]*(feat|ft\.|with|remaster|live|version|edit|mix|official|lyric|audio|video)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+-\s+(remaster|live|.*version|.*mix|.*edit).*$""", RegexOption.IGNORE_CASE), "").trim()

    private fun best(arr: JSONArray, dur: Double, maxDiff: Double): Pair<String?, String?> {
        var bp: String? = null; var bs: String? = null; var score = -1
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val p = e.optString("plainLyrics", "").ifBlank { null }; val s = e.optString("syncedLyrics", "").ifBlank { null }
            if (p == null && s == null) continue
            val du = e.optDouble("duration", 0.0)
            if (dur > 0 && du > 0 && abs(du - dur) > maxDiff) continue
            val sc = (if (s != null) 2 else 0) + (if (dur > 0 && du > 0 && abs(du - dur) <= 3) 1 else 0)
            if (sc > score) { score = sc; bp = p; bs = s }
        }
        return bp to bs
    }

    private fun fetch(t: Track): Pair<String?, String?> {
        fun e(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        var artist = t.artist.trim(); val title = t.title.trim()
        if (artist.isEmpty() || artist == "YouTube" || artist == "Spotify" || artist.startsWith("Unknown")) artist = ""
        val alb = t.album.isNotBlank() && t.album != "YouTube" && t.album != "Spotify"
        if (artist.isNotEmpty() && title.isNotEmpty()) { // exact match first
            val u = "https://lrclib.net/api/get?artist_name=${e(artist)}&track_name=${e(title)}" +
                (if (alb) "&album_name=${e(t.album)}" else "") + (if (t.durSec > 0) "&duration=${Math.round(t.durSec)}" else "")
            get(u)?.let {
                val o = JSONObject(it)
                val p = o.optString("plainLyrics", "").ifBlank { null }; val s = o.optString("syncedLyrics", "").ifBlank { null }
                if (p != null || s != null) return p to s
            }
        }
        val ct = clean(title)
        val urls = ArrayList<String>()
        if (artist.isNotEmpty()) urls.add("https://lrclib.net/api/search?track_name=${e(ct)}&artist_name=${e(artist)}")
        urls.add("https://lrclib.net/api/search?q=${e("$artist $ct".trim())}")
        for (u in urls) {
            val body = get(u) ?: continue
            val b = try { best(JSONArray(body), t.durSec, if (u.contains("track_name")) 30.0 else 12.0) } catch (_: Exception) { null to null }
            if (b.first != null || b.second != null) return b
        }
        return null to null
    }
}
