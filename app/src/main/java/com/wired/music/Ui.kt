package com.wired.music

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

val BG = Color(0xFF060A0F)
val ICE = Color(0xFF96D6E8)
val ICE2 = Color(0xFFD6F2FA)
val TX = Color(0xFFDAE6EE)
val DM = Color(0xFF6C8492)
val WH = Color.White
val Covers = listOf(R.drawable.c1, R.drawable.c2, R.drawable.c3, R.drawable.c4, R.drawable.c5, R.drawable.c6, R.drawable.c7)

@Composable
fun WiredTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = ICE, onPrimary = BG, background = BG, surface = Color(0xFF0A1016), onSurface = TX, onBackground = TX),
        content = content
    )
}

fun Context.findActivity(): ComponentActivity {
    var c = this
    while (c is ContextWrapper) { if (c is ComponentActivity) return c; c = c.baseContext }
    error("no activity")
}

/** Runs [block] repeatedly, but only while the app is on screen (saves battery). */
@Composable
fun ResumedLoop(vararg keys: Any?, periodMs: Long, block: () -> Unit) {
    val act = LocalContext.current.findActivity()
    val cur by rememberUpdatedState(block)
    LaunchedEffect(*keys) {
        act.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { cur(); delay(periodMs) }
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(WH.copy(alpha = .06f), WH.copy(alpha = .016f))))
            .border(1.dp, WH.copy(alpha = .09f), shape),
        content = content
    )
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val c = if (accent) ICE else WH
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier.height(34.dp).clip(shape).background(c.copy(alpha = if (accent) .12f else .06f))
            .border(1.dp, c.copy(alpha = if (accent) .35f else .11f), shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (accent) ICE2 else TX, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

enum class Ic { PLAY, PAUSE, NEXT, PREV, SHUFFLE, REPEAT, REPEAT1, SEARCH, CLOSE, DOWN, CHECK, MENU }

@Composable
fun Ico(k: Ic, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val st = Stroke(width = s * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        fun ln(a: Offset, b: Offset) = drawLine(color, a, b, st.width, StrokeCap.Round)
        when (k) {
            Ic.PLAY -> drawPath(Path().apply { moveTo(s * .3f, s * .18f); lineTo(s * .82f, s * .5f); lineTo(s * .3f, s * .82f); close() }, color)
            Ic.PAUSE -> {
                drawRoundRect(color, o(.24f, .2f), Size(s * .17f, s * .6f), CornerRadius(s * .04f))
                drawRoundRect(color, o(.59f, .2f), Size(s * .17f, s * .6f), CornerRadius(s * .04f))
            }
            Ic.NEXT -> {
                drawPath(Path().apply { moveTo(s * .2f, s * .22f); lineTo(s * .68f, s * .5f); lineTo(s * .2f, s * .78f); close() }, color)
                drawRoundRect(color, o(.74f, .22f), Size(s * .09f, s * .56f), CornerRadius(s * .03f))
            }
            Ic.PREV -> {
                drawPath(Path().apply { moveTo(s * .8f, s * .22f); lineTo(s * .32f, s * .5f); lineTo(s * .8f, s * .78f); close() }, color)
                drawRoundRect(color, o(.17f, .22f), Size(s * .09f, s * .56f), CornerRadius(s * .03f))
            }
            Ic.SHUFFLE -> {
                drawPath(Path().apply { moveTo(s * .12f, s * .3f); lineTo(s * .36f, s * .3f); cubicTo(s * .56f, s * .3f, s * .46f, s * .7f, s * .66f, s * .7f); lineTo(s * .82f, s * .7f) }, color, style = st)
                drawPath(Path().apply { moveTo(s * .12f, s * .7f); lineTo(s * .36f, s * .7f); cubicTo(s * .56f, s * .7f, s * .46f, s * .3f, s * .66f, s * .3f); lineTo(s * .82f, s * .3f) }, color, style = st)
                ln(o(.72f, .2f), o(.84f, .3f)); ln(o(.72f, .4f), o(.84f, .3f)); ln(o(.72f, .6f), o(.84f, .7f)); ln(o(.72f, .8f), o(.84f, .7f))
            }
            Ic.REPEAT, Ic.REPEAT1 -> {
                drawPath(Path().apply { moveTo(s * .2f, s * .5f); lineTo(s * .2f, s * .4f); quadraticBezierTo(s * .2f, s * .3f, s * .3f, s * .3f); lineTo(s * .78f, s * .3f) }, color, style = st)
                ln(o(.68f, .2f), o(.8f, .3f)); ln(o(.68f, .4f), o(.8f, .3f))
                drawPath(Path().apply { moveTo(s * .8f, s * .5f); lineTo(s * .8f, s * .6f); quadraticBezierTo(s * .8f, s * .7f, s * .7f, s * .7f); lineTo(s * .22f, s * .7f) }, color, style = st)
                ln(o(.32f, .6f), o(.2f, .7f)); ln(o(.32f, .8f), o(.2f, .7f))
                if (k == Ic.REPEAT1) ln(o(.5f, .42f), o(.5f, .58f))
            }
            Ic.SEARCH -> { drawCircle(color, s * .22f, o(.44f, .44f), style = st); ln(o(.6f, .6f), o(.82f, .82f)) }
            Ic.CLOSE -> { ln(o(.24f, .24f), o(.76f, .76f)); ln(o(.76f, .24f), o(.24f, .76f)) }
            Ic.DOWN -> { ln(o(.5f, .18f), o(.5f, .66f)); ln(o(.3f, .48f), o(.5f, .68f)); ln(o(.7f, .48f), o(.5f, .68f)); ln(o(.22f, .84f), o(.78f, .84f)) }
            Ic.CHECK -> { ln(o(.2f, .52f), o(.42f, .72f)); ln(o(.42f, .72f), o(.8f, .3f)) }
            Ic.MENU -> { ln(o(.2f, .3f), o(.8f, .3f)); ln(o(.2f, .5f), o(.8f, .5f)); ln(o(.2f, .7f), o(.8f, .7f)) }
        }
    }
}

@Composable
fun IconBtn(k: Ic, color: Color = TX, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Ico(k, color, 22.dp) }
}

/** Album art: online/embedded cover if there is one, otherwise one of the app's covers. */
@Composable
fun Art(t: Track?, size: Dp, radius: Dp, modifier: Modifier = Modifier) {
    val cover = painterResource(Covers[(t?.id?.hashCode() ?: 0).absoluteValue % Covers.size])
    val shape = RoundedCornerShape(radius)
    Box(modifier.size(size).clip(shape).border(1.dp, WH.copy(alpha = .14f), shape)) {
        if (t?.artUrl != null) AsyncImage(model = t.artUrl, contentDescription = null, contentScale = ContentScale.Crop, placeholder = cover, error = cover, modifier = Modifier.fillMaxSize())
        else Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0x45063654)))
    }
}

@Composable
fun FieldBox(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, password: Boolean = false, search: Boolean = false, onGo: () -> Unit = {}) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = ICE2, fontSize = 15.sp), cursorBrush = SolidColor(ICE),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (search) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = { onGo() }, onDone = { onGo() }),
        modifier = modifier.fillMaxWidth().clip(shape).background(Color(0xFF0F1921)).border(1.dp, WH.copy(alpha = .12f), shape).padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = DM, fontSize = 15.sp); inner() } }
    )
}

@Composable
fun SmallDialog(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFF0A1016))
                .border(1.dp, ICE.copy(alpha = .24f), RoundedCornerShape(22.dp)).padding(20.dp),
            content = content
        )
    }
}

@Composable
fun SeekBar(playing: Boolean, durMs: Long, modifier: Modifier = Modifier) {
    var pos by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    ResumedLoop(playing, periodMs = if (playing) 250L else 600L) { if (drag == null) pos = Ctl.positionMs }
    val d = durMs.coerceAtLeast(1L)
    val frac = drag ?: (pos.toFloat() / d).coerceIn(0f, 1f)
    val shownMs = if (drag != null) (drag!! * d).toLong() else pos
    fun commit(f: Float) { if (durMs > 0) { val ms = (f * durMs).toLong(); Ctl.seekTo(ms); pos = ms } }
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(durMs) { detectTapGestures { o -> commit((o.x / size.width).coerceIn(0f, 1f)) } }
                .pointerInput(durMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { commit(it) }; drag = null },
                        onDragCancel = { drag = null },
                        onHorizontalDrag = { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = 4.dp.toPx(); val y = size.height / 2f; val w = size.width; val fw = w * frac
                drawRoundRect(WH.copy(alpha = .1f), Offset(0f, y - h / 2), Size(w, h), CornerRadius(h / 2))
                if (fw > 2f) {
                    drawRoundRect(ICE.copy(alpha = .2f), Offset(0f, y - h), Size(fw, h * 2), CornerRadius(h))
                    drawRoundRect(ICE, Offset(0f, y - h / 2), Size(fw, h), CornerRadius(h / 2))
                }
                if (drag != null) drawCircle(ICE2, 6.dp.toPx(), Offset(fw, y))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(shownMs / 1000.0), color = DM, fontSize = 11.sp)
            Text(if (durMs > 0) fmt(durMs / 1000.0) else "", color = DM, fontSize = 11.sp)
        }
    }
}
