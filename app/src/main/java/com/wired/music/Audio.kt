package com.wired.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.*

/** Passes audio through untouched while keeping the last 1024 samples for the visualiser (no permission needed). */
@UnstableApi
object AudioTap : BaseAudioProcessor() {
    private val ring = FloatArray(1024)
    @Volatile private var pos = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val frame = 2 * inputAudioFormat.channelCount.coerceAtLeast(1)
        val frames = n / frame
        var q = inputBuffer.position() + max(0, frames - 1024) * frame
        val end = inputBuffer.limit()
        var w = pos
        while (q + frame <= end) {
            ring[w] = inputBuffer.getShort(q) / 32768f
            w = (w + 1) and 1023
            q += frame
        }
        pos = w
        val out = replaceOutputBuffer(n)
        out.put(inputBuffer)
        out.flip()
    }

    fun snap(d: FloatArray) {
        val p = pos
        for (i in 0 until 1024) d[i] = ring[(p + i) and 1023]
    }
}

private val ICE = Color(0xFF96D6E8)
private val ICE2 = Color(0xFFD6F2FA)
private val OFFS = floatArrayOf(0f, 12f, 26f)
private val FAC = floatArrayOf(1f, .72f, .5f)
private val ALP = floatArrayOf(40f, 32f, 26f)

/** 48-band spectrum + the liquid physics, ported from the Windows visualiser. */
class VisState {
    val bars = FloatArray(48)
    val liq = FloatArray(48)
    private val lvel = FloatArray(48)
    private val spec = FloatArray(48)
    private val snap = FloatArray(1024)
    private val re = FloatArray(1024)
    private val im = FloatArray(1024)
    private val win = FloatArray(1024) { (0.54 - 0.46 * cos(2.0 * PI * it / 1023.0)).toFloat() }
    private val bandA = IntArray(48) { (511.0.pow(it / 48.0)).toInt() + 1 }
    private val bandZ = IntArray(48) { min(511, (511.0.pow((it + 1) / 48.0)).toInt() + 1) }
    var avg = 0f
    var flash = 0f
    private var bassAvg = 0f
    private var beatCd = 0f

    private val rnd = java.util.Random(7)
    val bx = FloatArray(28) { rnd.nextFloat() }
    val by = FloatArray(28) { rnd.nextFloat() }
    private val bv = FloatArray(28) { .5f + rnd.nextFloat() }
    val bsz = FloatArray(28) { 3f + rnd.nextFloat() * 6f }
    val bph = FloatArray(28) { rnd.nextFloat() * 6.28f }

    val px = FloatArray(96)
    val py = FloatArray(96)
    val curve = Path()
    val fill = Path()
    private var sd = 0f
    lateinit var glow: Stroke; lateinit var main: Stroke; lateinit var thin: Stroke; lateinit var bub: Stroke
    fun strokes(d: Float) {
        if (d == sd) return
        sd = d
        glow = Stroke(7f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
        main = Stroke(1.8f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
        thin = Stroke(1.1f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
        bub = Stroke(1f * d)
    }

    private fun fft() {
        val n = 1024
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun step(dt: Float, playing: Boolean) {
        java.util.Arrays.fill(spec, 0f)
        if (playing) {
            AudioTap.snap(snap)
            for (i in 0 until 1024) { re[i] = snap[i] * win[i]; im[i] = 0f }
            fft()
            for (k in 0 until 48) {
                var m = 0f
                for (j in bandA[k]..bandZ[k]) { val mag = sqrt(re[j] * re[j] + im[j] * im[j]) / 1024f; if (mag > m) m = mag }
                spec[k] = min(1f, m * (1.5f + k * .12f))
            }
        }
        val dec = .88f.pow(dt * 30f)
        for (k in 0 until 48) bars[k] = if (spec[k] > bars[k]) bars[k] + (spec[k] - bars[k]) * .75f else bars[k] * dec

        var bass = 0f
        for (k in 0 until 5) bass += bars[k]
        bass /= 5f
        if (beatCd > 0) beatCd -= dt
        if (playing && beatCd <= 0 && bass > bassAvg * 1.3f + .06f) {
            beatCd = .22f; flash = 1f
            for (k in 0 until 12) lvel[k] += 3f * (1 - k / 12f)
        }
        bassAvg += (bass - bassAvg) * min(1f, dt * 1.8f)
        flash *= .86f.pow(dt * 30f)
        for (k in 0 until 48) {
            val acc = (bars[k] - liq[k]) * 110f - lvel[k] * 9f
            lvel[k] += acc * dt
            liq[k] = (liq[k] + lvel[k] * dt).coerceIn(-.15f, 1.4f)
        }
        var s = 0f
        for (k in 0 until 48) s += bars[k]
        avg = s / 48f
        for (i in bx.indices) {
            by[i] -= bv[i] * dt * (.03f + avg * .3f + flash * .1f)
            if (by[i] < -.05f) { by[i] = 1.05f; bx[i] = rnd.nextFloat() }
        }
    }

    private fun liqAt(idx: Float): Float {
        val x = idx.coerceIn(0f, 46.999f); val i = x.toInt(); val f = x - i
        return liq[i] * (1 - f) + liq[i + 1] * f
    }

    /** Three layered liquid waves + bubbles. Allocation-free per frame. */
    fun draw(ds: DrawScope, t: Float) = with(ds) {
        val w = size.width; val h = size.height; val d = density
        strokes(d)
        val y0 = h * .56f; val amp = h * .36f; val calm = (3f + avg * 9f) * d
        val n = min(95, max(24, (w / d / 12f).toInt()))
        val tilt = sin(t * .4f) * h * .025f
        for (L in 2 downTo 0) {
            for (i in 0 until n) {
                val u = i / (n - 1f); val dd = abs(u - .5f) * 2f
                px[i] = u * w
                py[i] = y0 + OFFS[L] * (h / 350f) - liqAt(dd * 47f) * amp * FAC[L] + tilt * (u - .5f) * 2f +
                    sin(u * 8.2f + t * (.9f + L * .3f) + L * 2f) * calm + sin(u * 17f - t * (1.4f + L * .2f) + L) * calm * .45f
            }
            curve.reset(); curve.moveTo(px[0], py[0])
            for (i in 1 until n - 1) curve.quadraticBezierTo(px[i], py[i], (px[i] + px[i + 1]) / 2f, (py[i] + py[i + 1]) / 2f)
            curve.lineTo(px[n - 1], py[n - 1])
            fill.reset(); fill.addPath(curve); fill.lineTo(w, h + 2f); fill.lineTo(0f, h + 2f); fill.close()
            drawPath(fill, (if (L == 0) ICE else ICE2).copy(alpha = ((ALP[L] + flash * 14f) / 255f).coerceIn(0f, 1f)))
            if (L == 0) {
                drawPath(curve, ICE.copy(alpha = 34f / 255f), style = glow)
                drawPath(curve, ICE2.copy(alpha = 235f / 255f), style = main)
            } else drawPath(curve, ICE.copy(alpha = (70f - L * 18f) / 255f), style = thin)
        }
        val ba = ((36f + avg * 90f) / 255f).coerceIn(0f, 1f)
        for (i in bx.indices) {
            val x = bx[i] * w + sin(t * .7f + bph[i]) * 6f * d; val y = by[i] * h
            if (y > y0 + 6f * d) drawCircle(ICE2.copy(alpha = ba), radius = bsz[i] * d / 2f, center = Offset(x, y), style = bub)
        }
    }
}
