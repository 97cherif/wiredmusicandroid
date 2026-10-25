package com.wired.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun Header(menuOpen: Boolean, onMenu: (Boolean) -> Unit, onSearch: () -> Unit, menu: @Composable () -> Unit) {
    val status by Status.text.collectAsState()
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box { IconBtn(Ic.MENU) { onMenu(!menuOpen) }; menu() }
        IconBtn(Ic.SEARCH, onClick = onSearch)
        Spacer(Modifier.width(8.dp))
        Text(
            if (status.isEmpty()) "THE WIRED MUSIC" else status,
            color = if (status.isEmpty()) DM else ICE, fontSize = 12.sp, letterSpacing = if (status.isEmpty()) 2.sp else 0.sp,
            fontWeight = if (status.isEmpty()) FontWeight.Light else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CtlBtn(k: Ic, on: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Ico(k, if (on) ICE else TX, 24.dp) }
}

@Composable
fun NowCard(t: Track?, wantPlay: Boolean, shuffle: Boolean, repeatOne: Boolean, durMs: Long, playing: Boolean, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(t, 92.dp, 14.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text((t?.artist ?: "").uppercase(), color = ICE, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t?.title ?: "Nothing playing", color = if (t == null) DM else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light, lineHeight = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = if (t == null) "" else t.album + (if (t.year.isNotEmpty() && t.album.isNotEmpty()) "  ·  " else "") + t.year
                    if (sub.isNotEmpty()) Text(sub, color = DM, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            SeekBar(playing, if (durMs > 0) durMs else ((t?.durSec ?: 0.0) * 1000).toLong())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CtlBtn(Ic.SHUFFLE, shuffle) { Ctl.toggleShuffle() }
                CtlBtn(Ic.PREV) { Ctl.prev() }
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(ICE.copy(alpha = .18f)).border(1.dp, ICE.copy(alpha = .5f), CircleShape).clickable { Ctl.toggle() },
                    contentAlignment = Alignment.Center
                ) { Ico(if (wantPlay) Ic.PAUSE else Ic.PLAY, ICE2, 28.dp) }
                CtlBtn(Ic.NEXT) { Ctl.next() }
                CtlBtn(if (repeatOne) Ic.REPEAT1 else Ic.REPEAT, repeatOne) { Ctl.toggleRepeat() }
            }
        }
    }
}

@Composable
fun VisPanel(cur: Track?, modifier: Modifier = Modifier) {
    val vs = remember { VisState() }
    var tick by remember { mutableLongStateOf(0L) }
    val act = androidx.compose.ui.platform.LocalContext.current.findActivity()
    LaunchedEffect(Unit) {
        act.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var last = 0L
            while (true) {
                val n = withFrameNanos { it }
                val dt = if (last == 0L) .016f else ((n - last) / 1e9f).coerceIn(.001f, .05f)
                last = n
                vs.step(dt, Ctl.playing.value)
                tick = n
                if (!Ctl.playing.value) delay(40) // calm animation when paused (saves battery)
            }
        }
    }
    val ly by Lyrics.state.collectAsState()
    Panel(modifier) {
        Canvas(Modifier.fillMaxSize()) { vs.draw(this, ((tick / 1_000_000L) % 36_000_000L) / 1000f) }
        if (cur != null && ly.trackId == cur.id) {
            if (ly.status == 2 && !ly.hidden) LyricsOverlay(ly)
            val label = when (ly.status) { 1 -> "Searching..."; 2 -> if (ly.hidden) "Show lyrics" else "Hide lyrics"; 3 -> "Not found - retry"; else -> "Search lyrics" }
            Pill(label, Modifier.align(Alignment.TopEnd).padding(12.dp), accent = ly.status == 2 && !ly.hidden) { Lyrics.click(cur) }
        }
    }
}

@Composable
fun LyricsOverlay(st: LyState) {
    Box(Modifier.fillMaxSize().background(BG.copy(alpha = .6f))) {
        val sy = st.sync
        if (sy != null && sy.isNotEmpty()) SyncedLyrics(sy) else
            Text(
                st.plain, color = TX.copy(alpha = .92f), fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 24.dp)
            )
    }
}

@Composable
private fun SyncedLyrics(lines: List<Lrc>) {
    var idx by remember(lines) { mutableIntStateOf(-1) }
    ResumedLoop(lines, periodMs = 120L) {
        val ps = Ctl.positionMs / 1000.0 + .1
        var k = -1; var lo = 0; var hi = lines.size - 1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (lines[mid].t <= ps) { k = mid; lo = mid + 1 } else hi = mid - 1 }
        if (k != idx) idx = k
    }
    val pos = remember(lines) { Animatable(0f) }
    LaunchedEffect(idx) { pos.animateTo(idx.toFloat(), tween(380, easing = FastOutSlowInEasing)) }
    val step = with(LocalDensity.current) { 46.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        val from = max(0, idx - 4); val to = min(lines.size - 1, idx + 4)
        for (k in from..to) key(k) {
            val on = k == idx
            val txt = lines[k].s.ifBlank { "♪" }
            Text(
                txt, color = if (on) Color.White else TX, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = if (on) (if (txt.length > 40) 17.sp else 22.sp) else 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp)
                    .offset { IntOffset(0, ((k - pos.value) * step).roundToInt()) }
                    .graphicsLayer { alpha = (1f - abs(k - pos.value) * .27f).coerceIn(0f, 1f) * (if (k == idx) 1f else .8f) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(t: Track, current: Boolean, onPlay: () -> Unit, onMenu: () -> Unit, menu: @Composable () -> Unit) {
    Box {
        Row(
            Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(14.dp))
                .background(if (current) ICE.copy(alpha = .13f) else Color.Transparent)
                .combinedClickable(onClick = onPlay, onLongClick = onMenu).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Art(t, 42.dp, 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = if (current) ICE2 else TX, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, color = DM, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t.durSec > 0) Text(fmt(t.durSec), color = DM, fontSize = 12.sp)
            if (t.online) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { if (!t.saved) Actions.download(listOf(t)) }, contentAlignment = Alignment.Center) {
                    Ico(if (t.saved) Ic.CHECK else Ic.DOWN, if (t.saved) ICE else DM, 18.dp)
                }
            }
        }
        menu()
    }
}

@Composable
fun PlaylistPanel(tracks: List<Track>, index: Int, onFiles: () -> Unit, onYt: () -> Unit, onSpotify: () -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    var menuFor by remember { mutableIntStateOf(-1) }
    var armed by remember { mutableStateOf(false) }
    val busy by DownloadService.busy.collectAsState()
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    LaunchedEffect(index) { if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index) }
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(top = 14.dp, bottom = 12.dp, start = 10.dp, end = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PLAYLIST", color = DM, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
                if (tracks.isNotEmpty()) Text("  ${tracks.size}", color = ICE, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (tracks.isNotEmpty()) Text(
                    if (armed) "Sure?" else "Clear", color = if (armed) ICE2 else DM, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (armed) { Ctl.clear(); armed = false } else armed = true }.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tracks.isEmpty()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your playlist is empty", color = TX, fontSize = 15.sp)
                        Text("Add files, a YouTube or Spotify link", color = DM, fontSize = 12.sp)
                    }
                } else LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                        TrackRow(t, i == index, onPlay = { Ctl.playIndex(i) }, onMenu = { menuFor = i }) {
                            DropdownMenu(expanded = menuFor == i, onDismissRequest = { menuFor = -1 }, containerColor = Color(0xFF0E1822)) {
                                DropdownMenuItem(text = { Text("Play", color = TX) }, onClick = { menuFor = -1; Ctl.playIndex(i) })
                                if (t.online) DropdownMenuItem(text = { Text("Download", color = TX) }, onClick = { menuFor = -1; Actions.download(listOf(t)) })
                                DropdownMenuItem(text = { Text("Remove", color = TX) }, onClick = { menuFor = -1; Ctl.removeAt(i) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Files", Modifier.weight(1f), onClick = onFiles)
                Pill("YouTube", Modifier.weight(1f), onClick = onYt)
                Pill("Spotify", Modifier.weight(1f), onClick = onSpotify)
                Pill(if (busy) "Stop" else "↓ All", Modifier.weight(1f), accent = true) {
                    if (busy) DownloadService.stop() else Actions.download(tracks.filter { it.online && !it.saved })
                }
            }
        }
    }
}

@Composable
fun AppMenu(open: Boolean, onClose: () -> Unit, onFiles: () -> Unit, onSearch: (Int) -> Unit, onLink: (Int) -> Unit, onKeys: () -> Unit, onAbout: () -> Unit) {
    val c = TX
    fun item(label: String, a: () -> Unit): @Composable () -> Unit = { DropdownMenuItem(text = { Text(label, color = c) }, onClick = { onClose(); a() }) }
    DropdownMenu(expanded = open, onDismissRequest = onClose, containerColor = Color(0xFF0E1822)) {
        item("Open files...", onFiles)()
        HorizontalDivider(color = Color.White.copy(alpha = .08f))
        item("Search YouTube...") { onSearch(1) }()
        item("Add YouTube link...") { onLink(1) }()
        HorizontalDivider(color = Color.White.copy(alpha = .08f))
        item("Search Spotify...") { onSearch(2) }()
        item("Add Spotify link...") { onLink(2) }()
        item("Download from Spotify link...") { onLink(3) }()
        item("Spotify keys...", onKeys)()
        HorizontalDivider(color = Color.White.copy(alpha = .08f))
        item("Download all online songs") { Actions.download(Repo.tracks.value.filter { it.online && !it.saved }) }()
        item("About", onAbout)()
    }
}
