package com.wired.music

import android.content.Context
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import android.util.Base64

/** Spotify: reads song info (no login). Audio always comes from the matching YouTube video, in full. */
object Spotify {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val rx = Regex("""(?:spotify\.com/(?:[^\s?#]*/)?|spotify:(?:user:[^:\s]+:)?)(track|album|playlist)[/:]([A-Za-z0-9]{22})""", RegexOption.IGNORE_CASE)

    class Info(val kind: String) { var name: String? = null; var art: String? = null; val tracks = ArrayList<Track>() }

    fun isLink(s: String?): Boolean {
        val x = s?.trim() ?: return false
        return x.length < 400 && !x.contains('\n') && (x.contains("spotify.com/") || x.contains("spotify.link/") || x.startsWith("spotify:"))
    }

    private fun get(url: String): String {
        val r = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en-US,en;q=0.9").build()
        YT.client.newCall(r).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            return it.body?.string() ?: ""
        }
    }

    private fun JSONObject.s(k: String): String? = optString(k, "").ifBlank { null }

    private fun sp(title: String, artist: String?, album: String?, sec: Double, art: String?) = Track(
        title = title.trim(), artist = (artist ?: "").replace('\u00a0', ' ').trim().ifEmpty { "Spotify" },
        album = album?.ifBlank { null } ?: "Spotify", durSec = sec, spot = true, artUrl = art
    )

    private fun bestImg(e: JSONObject): String? {
        var best: String? = null; var bw = -1.0
        fun scan(a: JSONArray?) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                val u = x.s("url") ?: continue
                val w = maxOf(x.optDouble("width", 0.0), x.optDouble("maxWidth", 0.0))
                if (w >= bw) { bw = w; best = u }
            }
        }
        scan(e.optJSONObject("coverArt")?.optJSONArray("sources"))
        if (best == null) scan(e.optJSONObject("visualIdentity")?.optJSONArray("image"))
        return best
    }

    private fun parseEmbed(json: String, info: Info) {
        val ent = JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        info.name = ent.s("name") ?: ent.s("title"); info.art = bestImg(ent)
        val tl = ent.optJSONArray("trackList")
        if (info.kind != "track" && tl != null) {
            for (i in 0 until tl.length()) {
                val e = tl.optJSONObject(i) ?: continue
                val title = e.s("title") ?: continue
                info.tracks.add(sp(title, e.s("subtitle"), if (info.kind == "album") info.name else null,
                    e.optDouble("duration", 0.0) / 1000.0, if (info.kind == "album") info.art else null))
            }
        } else {
            val ar = ent.optJSONArray("artists")
            var artists: String? = null
            if (ar != null) artists = (0 until ar.length()).mapNotNull { ar.optJSONObject(it)?.s("name") }.joinToString(", ")
            if (artists.isNullOrBlank()) artists = ent.s("subtitle")
            val title = ent.s("name") ?: ent.s("title")
            if (title != null) info.tracks.add(sp(title, artists, null, ent.optDouble("duration", 0.0) / 1000.0, info.art))
        }
    }

    /** Reads a Spotify song / album / playlist link (no account or keys needed). */
    fun fetch(input: String): Info {
        val s = input.trim()
        var m = rx.find(s)
        if (m == null && s.contains("spotify.link/")) { // short share link: follow the redirect
            val r = Request.Builder().url(s).header("User-Agent", UA).build()
            YT.client.newCall(r).execute().use { resp -> m = rx.find(resp.request.url.toString()) ?: rx.find(resp.body?.string() ?: "") }
        }
        val mm = m ?: throw IOException("that isn't a Spotify song, album or playlist link")
        val kind = mm.groupValues[1].lowercase(); val id = mm.groupValues[2]
        val info = Info(kind)
        try {
            val html = get("https://open.spotify.com/embed/$kind/$id")
            val nd = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            if (nd != null) parseEmbed(nd.groupValues[1], info)
        } catch (_: Exception) { info.tracks.clear() }
        if (info.tracks.isEmpty() && kind == "track") { // fallback: the public page's meta tags
            try {
                val page = get("https://open.spotify.com/track/$id")
                fun og(p: String) = Regex("<meta[^>]+property=\"$p\"[^>]+content=\"([^\"]*)\"").find(page)?.groupValues?.get(1)
                    ?.let { android.text.Html.fromHtml(it, 0).toString() }
                val title = og("og:title")
                val parts = (og("og:description") ?: "").split('·').map { it.trim() }.filter { it.isNotEmpty() }
                if (!title.isNullOrBlank() && parts.isNotEmpty())
                    info.tracks.add(sp(title, parts[0], if (parts.size >= 4) parts[1] else null, 0.0, og("og:image")))
            } catch (_: Exception) { }
        }
        if (info.tracks.isEmpty()) throw IOException("couldn't read it (is the playlist private?)")
        return info
    }

    // ----- search (needs free developer keys: Client ID + Secret) -----
    private fun prefs() = WiredApp.ctx.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    fun hasKeys(): Boolean = !prefs().getString("id", "").isNullOrBlank() && !prefs().getString("secret", "").isNullOrBlank()
    fun saveKeys(id: String, secret: String) { prefs().edit().putString("id", id.trim()).putString("secret", secret.trim()).apply(); token = null }
    @Volatile private var token: String? = null
    @Volatile private var exp = 0L

    private fun token(): String {
        token?.let { if (System.currentTimeMillis() < exp) return it }
        val id = prefs().getString("id", "") ?: ""; val sec = prefs().getString("secret", "") ?: ""
        if (id.isBlank() || sec.isBlank()) throw IOException("no Spotify keys yet (menu > Spotify keys)")
        val auth = Base64.encodeToString("$id:$sec".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder().url("https://accounts.spotify.com/api/token")
            .header("Authorization", "Basic $auth").post(FormBody.Builder().add("grant_type", "client_credentials").build()).build()
        YT.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Spotify rejected the keys (menu > Spotify keys)")
            val o = JSONObject(r.body?.string() ?: "{}")
            token = o.getString("access_token"); exp = System.currentTimeMillis() + maxOf(60, o.optInt("expires_in", 3600) - 60) * 1000L
            return token!!
        }
    }

    fun search(q: String): List<Track> {
        val tok = token()
        val req = Request.Builder().url("https://api.spotify.com/v1/search?type=track&limit=10&q=" + java.net.URLEncoder.encode(q, "UTF-8"))
            .header("Authorization", "Bearer $tok").build()
        YT.client.newCall(req).execute().use { r ->
            if (r.code == 429) throw IOException("Spotify says slow down - try again in a bit")
            if (!r.isSuccessful) throw IOException("Spotify search failed (${r.code})")
            val items = JSONObject(r.body?.string() ?: "{}").optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
            val out = ArrayList<Track>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val title = item.s("name") ?: continue
                val ar = item.optJSONArray("artists")
                val artists = if (ar != null) (0 until ar.length()).mapNotNull { k -> ar.optJSONObject(k)?.s("name") }.joinToString(", ") else null
                val al = item.optJSONObject("album")
                var art: String? = null; var bw = -1.0
                al?.optJSONArray("images")?.let { ims ->
                    for (k in 0 until ims.length()) { val im = ims.optJSONObject(k) ?: continue; val w = im.optDouble("width", 0.0); val u = im.s("url"); if (u != null && w >= bw) { bw = w; art = u } }
                }
                out.add(sp(title, artists, al?.s("name"), item.optDouble("duration_ms", 0.0) / 1000.0, art))
            }
            return out
        }
    }
}
