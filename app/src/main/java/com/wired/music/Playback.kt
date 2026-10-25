package com.wired.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

fun Track.toMediaItem(): MediaItem {
    val u = uri ?: "wired://t/$id"
    return MediaItem.Builder().setMediaId(id).setUri(u)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(u)).build())
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album.ifBlank { null })
                .setArtworkUri(artUrl?.let { Uri.parse(it) }).build()
        ).build()
}

/** Songs are turned into real stream URLs only when the player is about to load them. */
@UnstableApi
object TrackResolver : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val u = dataSpec.uri
        if (u.scheme != "wired") return dataSpec
        val id = u.lastPathSegment ?: throw IOException("bad track")
        val t = Repo.byId(id) ?: throw IOException("track removed")
        try {
            return dataSpec.withUri(Uri.parse(Resolve.audioFor(t).url))
        } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException(e.message ?: "couldn't load stream", e) }
    }
}

/** Plays in a foreground service, so the next song always starts even with the screen off or the app closed. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val retries = HashMap<String, Int>()
    private var fails = 0

    override fun onCreate() {
        super.onCreate()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(AudioTap))
                    .build()
        }
        val http = DefaultHttpDataSource.Factory().setUserAgent(YT.UA).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        val factory = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http), TrackResolver)
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { fails = 0; player.currentMediaItem?.mediaId?.let { retries.remove(it) } }
            }
            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId
                if (id != null) { // stream links expire: fetch a fresh one and retry twice
                    Resolve.forget(id)
                    val n = retries[id] ?: 0
                    if (n < 2) { retries[id] = n + 1; val pos = player.currentPosition; player.prepare(); player.seekTo(pos); return }
                }
                fails++
                if (fails < 3 && player.hasNextMediaItem()) {
                    Status.set("Couldn't play that one, skipping", 4000)
                    player.seekToNextMediaItem(); player.prepare(); player.play()
                } else { player.pause(); Status.set("Playback failed - check your internet", 6000) }
            }
        })
        session = MediaSession.Builder(this, player).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_stat) })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** What the UI talks to: a MediaController connected to the service + observable state. */
object Ctl {
    private var mc: MediaController? = null
    private var connecting = false

    val index = MutableStateFlow(-1)
    val playing = MutableStateFlow(false)
    val wantPlay = MutableStateFlow(false)
    val shuffle = MutableStateFlow(false)
    val repeatOne = MutableStateFlow(false)
    val durMs = MutableStateFlow(0L)
    val positionMs: Long get() = mc?.currentPosition ?: 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh(player) }
    }

    private fun refresh(p: Player) {
        val i = if (p.mediaItemCount == 0) -1 else p.currentMediaItemIndex
        index.value = i
        if (i >= 0) Repo.setIndex(i)
        playing.value = p.isPlaying
        wantPlay.value = p.playWhenReady && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
        shuffle.value = p.shuffleModeEnabled
        repeatOne.value = p.repeatMode == Player.REPEAT_MODE_ONE
        durMs.value = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
    }

    fun connect(ctx: Context) {
        if (mc != null || connecting) return
        connecting = true
        val app = ctx.applicationContext
        try {
            val f = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
            f.addListener({
                try {
                    val c = f.get()
                    mc = c
                    c.addListener(listener)
                    val tr = Repo.tracks.value
                    if (c.mediaItemCount == 0 && tr.isNotEmpty()) { // fresh start: load the saved playlist (nothing is downloaded yet)
                        c.setMediaItems(tr.map { it.toMediaItem() }, Repo.savedIndex.coerceIn(0, tr.size - 1), 0L)
                        c.shuffleModeEnabled = Repo.shuffle
                        c.repeatMode = if (Repo.repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
                    }
                    refresh(c)
                } catch (e: Exception) { Status.set("Player unavailable", 5000) } finally { connecting = false }
            }, ContextCompat.getMainExecutor(app))
        } catch (e: Exception) { connecting = false }
    }

    fun playIndex(i: Int) {
        val c = mc ?: return
        if (i !in 0 until c.mediaItemCount) return
        c.seekTo(i, 0L)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun toggle() {
        val c = mc ?: return
        if (c.mediaItemCount == 0) return
        if (wantPlay.value) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
            c.play()
        }
    }

    fun next() { val c = mc ?: return; if (c.mediaItemCount == 0) return; c.seekToNext(); if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
    fun prev() { val c = mc ?: return; if (c.mediaItemCount == 0) return; c.seekToPrevious(); if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
    fun seekTo(ms: Long) { mc?.seekTo(ms) }

    fun toggleShuffle() { val c = mc ?: return; c.shuffleModeEnabled = !c.shuffleModeEnabled; Repo.setModes(c.shuffleModeEnabled, c.repeatMode == Player.REPEAT_MODE_ONE) }
    fun toggleRepeat() {
        val c = mc ?: return
        c.repeatMode = if (c.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE
        Repo.setModes(c.shuffleModeEnabled, c.repeatMode == Player.REPEAT_MODE_ONE)
    }

    fun addTracks(l: List<Track>) {
        if (l.isEmpty()) return
        Repo.add(l)
        mc?.addMediaItems(l.map { it.toMediaItem() })
    }

    fun removeAt(i: Int) {
        val t = Repo.tracks.value.getOrNull(i) ?: return
        Repo.remove(t.id)
        mc?.removeMediaItem(i)
    }

    fun clear() {
        Repo.clear()
        mc?.apply { stop(); clearMediaItems() }
    }
}
