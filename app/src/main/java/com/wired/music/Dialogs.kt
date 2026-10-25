package com.wired.music

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** kind: 1 = YouTube link, 2 = Spotify link, 3 = Spotify link to download straight away */
@Composable
fun LinkDialog(kind: Int, onClose: () -> Unit) {
    val clip = LocalClipboardManager.current
    var text by remember {
        mutableStateOf(
            clip.getText()?.text?.trim()?.takeIf { if (kind == 1) YT.isLink(it) else Spotify.isLink(it) } ?: ""
        )
    }
    val go = {
        val u = text.trim()
        if (u.isNotEmpty()) {
            when (kind) {
                1 -> if (Spotify.isLink(u)) Status.set("That is a Spotify link - use Add Spotify link", 5000) else Actions.addYouTube(u)
                2 -> Actions.addSpotify(u)
                else -> Actions.addSpotify(u, true)
            }
            onClose()
        }
    }
    SmallDialog(onClose) {
        Text(
            when (kind) { 1 -> "Paste a YouTube video or playlist link"; 2 -> "Paste a Spotify song, album or playlist link"; else -> "Paste a Spotify link to download" },
            color = TX, fontSize = 15.sp, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(14.dp))
        FieldBox(text, { text = it }, "https://...", onGo = go)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Pill("Cancel", onClick = onClose)
            Spacer(Modifier.width(8.dp))
            Pill(if (kind == 3) "Download" else "Add", accent = true, onClick = go)
        }
    }
}

@Composable
fun KeysDialog(onClose: () -> Unit, onSaved: () -> Unit) {
    var id by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf("") }
    SmallDialog(onClose) {
        Text("Spotify search needs free developer keys", color = TX, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Go to developer.spotify.com/dashboard, create an app, then copy its Client ID and Client Secret here. (Pasting a Spotify link works without any keys.)",
            color = DM, fontSize = 12.sp, lineHeight = 17.sp
        )
        Spacer(Modifier.height(14.dp))
        FieldBox(id, { id = it }, "Client ID")
        Spacer(Modifier.height(8.dp))
        FieldBox(sec, { sec = it }, "Client Secret", password = true)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Pill("Cancel", onClick = onClose)
            Spacer(Modifier.width(8.dp))
            Pill("Save", accent = true) { if (id.isNotBlank() && sec.isNotBlank()) { Spotify.saveKeys(id, sec); onSaved() } }
        }
    }
}

@Composable
fun AboutDialog(onClose: () -> Unit) {
    SmallDialog(onClose) {
        Text("THE WIRED MUSIC", color = ICE2, fontSize = 18.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text("No matter where you are, everyone is always connected.", color = DM, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Pill("Close", accent = true, onClick = onClose) }
    }
}

/** kind: 1 = YouTube search, 2 = Spotify search */
@Composable
fun SearchDialog(kind: Int, onClose: () -> Unit) {
    val spotify = kind == 2
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val fr = remember { FocusRequester() }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var sel by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var emptyT by remember { mutableStateOf("Find something to play") }
    var emptyS by remember { mutableStateOf("Type a song or an artist, then press Search") }
    var job by remember { mutableStateOf<Job?>(null) }
    val tracks by Repo.tracks.collectAsState()
    LaunchedEffect(Unit) { fr.requestFocus() }

    fun go() {
        val text = q.trim()
        if (text.isEmpty() || busy) return
        if (Spotify.isLink(text)) {
            if (!spotify) { note = "That is a Spotify link - use Search Spotify or Add Spotify link"; return }
            Actions.addSpotify(text); onClose(); return
        }
        focus.clearFocus()
        busy = true; note = ""
        job = scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { if (spotify) Spotify.search(text) else YT.search(text, false).take(15).map { it.toTrack() } }
                results = r; sel = if (r.isEmpty()) -1 else 0
                if (r.isEmpty()) { emptyT = "No results"; emptyS = "Try different keywords" }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                results = emptyList(); sel = -1; emptyT = "Search failed"
                emptyS = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(90)
            } finally { busy = false }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(BG).systemBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (spotify) "SEARCH SPOTIFY" else "SEARCH YOUTUBE", color = DM, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    IconBtn(Ic.CLOSE, onClick = onClose)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldBox(q, { q = it }, if (spotify) "Search Spotify songs, artists…" else "Search YouTube songs, artists…", Modifier.weight(1f).focusRequester(fr), search = true, onGo = ::go)
                    Spacer(Modifier.width(8.dp))
                    Pill("Search", accent = true, onClick = ::go)
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(color = ICE, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(28.dp))
                    else if (results.isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(emptyT, color = TX, fontSize = 15.sp); Text(emptyS, color = DM, fontSize = 12.sp)
                    } else LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(results, key = { i, t -> "$i${t.id}" }) { i, t ->
                            val inPl = tracks.any { it.id == t.id || (it.ytId != null && it.ytId == t.ytId) || (it.spot && t.spot && it.title == t.title && it.artist == t.artist) }
                            Row(
                                Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (i == sel) ICE.copy(alpha = .14f) else Color.Transparent)
                                    .clickable { sel = i }.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Art(t, 46.dp, 10.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, color = if (i == sel) ICE2 else TX, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(t.artist, color = DM, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (inPl) Ico(Ic.CHECK, ICE, 18.dp) else if (t.durSec > 0) Text(fmt(t.durSec), color = DM, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (note.isNotEmpty()) note else if (busy) (if (spotify) "Searching Spotify…" else "Searching YouTube…") else if (results.isNotEmpty()) (if (spotify) "Plays via YouTube" else "Results from YouTube") else "",
                        color = DM, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f)
                    )
                    val t = results.getOrNull(sel)
                    Pill("Play", accent = true) { if (t != null) { Actions.playNow(t); onClose() } }
                    Pill("+ Queue") { if (t != null) note = if (Actions.queue(t)) "Added to playlist" else "Already in your playlist" }
                    Pill("Download") { if (t != null) { Actions.download(listOf(t)); onClose() } }
                }
            }
        }
    }
}
