package com.wired.music

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        Ctl.connect(this)
        setContent { WiredTheme { WiredRoot() } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A link shared to the app from Spotify / YouTube / a browser. */
    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND || i.type != "text/plain") return
        val txt = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("""https?://\S+""").find(txt)?.value ?: txt.trim()
        if (Spotify.isLink(url)) Actions.addSpotify(url) else if (YT.isLink(url)) Actions.addYouTube(url) else Status.set("That isn't a Spotify or YouTube link", 5000)
        intent = Intent()
    }
}

@Composable
fun WiredRoot() {
    val ctx = LocalContext.current
    val tracks by Repo.tracks.collectAsState()
    val index by Ctl.index.collectAsState()
    val wantPlay by Ctl.wantPlay.collectAsState()
    val playing by Ctl.playing.collectAsState()
    val shuffle by Ctl.shuffle.collectAsState()
    val repeatOne by Ctl.repeatOne.collectAsState()
    val durMs by Ctl.durMs.collectAsState()
    val cur = tracks.getOrNull(index)

    var menu by remember { mutableStateOf(false) }
    var search by remember { mutableIntStateOf(0) }     // 0 none, 1 YouTube, 2 Spotify
    var link by remember { mutableIntStateOf(0) }       // 0 none, 1 YouTube, 2 Spotify, 3 Spotify download
    var keys by remember { mutableStateOf(false) }
    var pendingSpotifySearch by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Actions.addFiles(ctx, uris)
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(cur?.id) { Lyrics.onTrack(cur) }

    fun openSearch(k: Int) {
        if (k == 2 && !Spotify.hasKeys()) { pendingSpotifySearch = true; keys = true } else search = k
    }
    val files = { picker.launch(arrayOf("audio/*")) }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A121A), Color(0xFF04070B))))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF96D6E8).copy(alpha = .13f), Color.Transparent), center = Offset(size.width / 2f, 0f), radius = size.width * .95f),
                    radius = size.width * .95f, center = Offset(size.width / 2f, 0f)
                )
            }
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Header(menu, { menu = it }, { openSearch(1) }) {
                AppMenu(menu, { menu = false }, files, { openSearch(it) }, { link = it }, { keys = true }, { about = true })
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                val wide = maxWidth >= 840.dp
                if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NowCard(cur, wantPlay, shuffle, repeatOne, durMs, playing)
                        VisPanel(cur, Modifier.weight(1f).fillMaxWidth())
                    }
                    PlaylistPanel(tracks, index, files, { link = 1 }, { link = 2 }, Modifier.width(380.dp).fillMaxHeight())
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NowCard(cur, wantPlay, shuffle, repeatOne, durMs, playing)
                    VisPanel(cur, Modifier.weight(.8f).fillMaxWidth())
                    PlaylistPanel(tracks, index, files, { link = 1 }, { link = 2 }, Modifier.weight(1.2f).fillMaxWidth())
                }
            }
        }
    }

    if (search != 0) SearchDialog(search) { search = 0 }
    if (link != 0) LinkDialog(link) { link = 0 }
    if (keys) KeysDialog(onClose = { keys = false; pendingSpotifySearch = false }, onSaved = { keys = false; if (pendingSpotifySearch) { pendingSpotifySearch = false; search = 2 } })
    if (about) AboutDialog { about = false }
}
