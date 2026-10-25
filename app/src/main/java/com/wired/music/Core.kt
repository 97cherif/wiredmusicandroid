package com.wired.music

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class WiredApp : Application() {
    companion object { lateinit var ctx: Context }
    override fun onCreate() {
        super.onCreate()
        ctx = this
        Repo.init(this)
    }
}

data class Track(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val title: String,
    val artist: String,
    val album: String = "",
    val year: String = "",
    val durSec: Double = 0.0,
    val ytId: String? = null,
    val spot: Boolean = false,
    val artUrl: String? = null,
    val uri: String? = null,
    val saved: Boolean = false,
) {
    val online: Boolean get() = uri == null
}

data class YtItem(val id: String, val title: String, val artist: String, val durSec: Double, val thumb: String?)

fun fmt(sec: Double): String {
    if (sec.isNaN() || sec <= 0) return "0:00"
    val s = sec.toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Status line shown in the header (like the Windows app). */
object Status {
    val text = MutableStateFlow("")
    private val h = Handler(Looper.getMainLooper())
    private var clear: Runnable? = null
    fun set(s: String, ms: Long = 0) {
        text.value = s
        clear?.let { h.removeCallbacks(it) }
        clear = null
        if (ms > 0) {
            val r = Runnable { text.value = "" }
            clear = r
            h.postDelayed(r, ms)
        }
    }
}

/** The playlist (kept on disk) shared by the UI, the player service and the downloader. */
object Repo {
    private lateinit var app: Context
    private val io = Executors.newSingleThreadExecutor()
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    @Volatile var savedIndex = -1
    @Volatile var shuffle = false
    @Volatile var repeatOne = false

    private val file: File get() = File(app.filesDir, "playlist.json")
    private fun prefs() = app.getSharedPreferences("wired", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        try {
            val a = JSONArray(if (file.exists()) file.readText() else "[]")
            val l = ArrayList<Track>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                l.add(Track(
                    id = o.getString("id"), title = o.optString("title"), artist = o.optString("artist"),
                    album = o.optString("album"), year = o.optString("year"), durSec = o.optDouble("dur", 0.0),
                    ytId = o.optString("ytId", "").ifEmpty { null }, spot = o.optBoolean("spot"),
                    artUrl = o.optString("art", "").ifEmpty { null }, uri = o.optString("uri", "").ifEmpty { null },
                    saved = o.optBoolean("saved")
                ))
            }
            tracks.value = l
        } catch (_: Exception) { }
        val p = prefs()
        savedIndex = p.getInt("index", -1); shuffle = p.getBoolean("shuffle", false); repeatOne = p.getBoolean("repeatOne", false)
    }

    fun byId(id: String): Track? = tracks.value.firstOrNull { it.id == id }

    fun indexOfMatch(t: Track): Int = tracks.value.indexOfFirst {
        it.id == t.id || (it.ytId != null && it.ytId == t.ytId) ||
            (it.spot && t.spot && it.title == t.title && it.artist == t.artist)
    }

    fun add(l: List<Track>) { tracks.update { it + l }; save() }
    fun remove(id: String) { tracks.update { l -> l.filter { it.id != id } }; save() }
    fun clear() { tracks.value = emptyList(); savedIndex = -1; prefs().edit().putInt("index", -1).apply(); save() }
    fun update(id: String, f: (Track) -> Track) { tracks.update { l -> l.map { if (it.id == id) f(it) else it } }; save() }

    fun setIndex(i: Int) { if (i != savedIndex) { savedIndex = i; prefs().edit().putInt("index", i).apply() } }
    fun setModes(sh: Boolean, r1: Boolean) {
        shuffle = sh; repeatOne = r1
        prefs().edit().putBoolean("shuffle", sh).putBoolean("repeatOne", r1).apply()
    }

    private fun save() {
        val snap = tracks.value
        io.execute {
            try {
                val a = JSONArray()
                for (t in snap) a.put(JSONObject().apply {
                    put("id", t.id); put("title", t.title); put("artist", t.artist); put("album", t.album)
                    put("year", t.year); put("dur", t.durSec); put("spot", t.spot); put("saved", t.saved)
                    t.ytId?.let { put("ytId", it) }; t.artUrl?.let { put("art", it) }; t.uri?.let { put("uri", it) }
                })
                file.writeText(a.toString())
            } catch (_: Exception) { }
        }
    }
}
