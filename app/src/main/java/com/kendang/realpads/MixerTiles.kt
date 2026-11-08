package com.kendang.realpads

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// Komponen mixer: saklar (LED), knob putar, meter. Palet = DTX; warna INDIKATOR = hijau tetap (tidak ikut tema).

private val TileShape = RoundedCornerShape(8.dp)
private val CardColor = Color(0xFF2F2F32)        // latar kartu knob / saklar (lebih gelap dari pad DTX supaya knob menonjol)
private val CardOnColor = Color(0xFF1E3A2A)      // latar saklar saat ON (hijau gelap)
private val ValueColor = Color(0xFFE6E6EA)

private fun tileBg(c: Color, opacity: Float): Color = c.copy(alpha = c.alpha * opacity.coerceIn(0f, 1f))

/**
 * Ketukan sungguhan: baru dihitung saat jari DIANGKAT di tempat yang sama. Jari yang menggeser (scroll panel, menyeret
 * jendela, lewat di atas tombol) TIDAK mengubah saklar. null = tidak bisa disentuh.
 */
@Composable
private fun Modifier.tapOnPress(onTap: (() -> Unit)?): Modifier {
    val latest by rememberUpdatedState(onTap)
    return if (onTap == null) this
    else this.pointerInput(Unit) { detectTapGestures(onTap = { latest?.invoke() }) }
}

/** Tampilan saklar ON/OFF (lampu LED + teks). Area sentuhnya = seluruh baris judul pemiliknya (lihat GroupCard/MixSection). */
@Composable
private fun GroupSwitch(on: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .size(width = 48.dp, height = 22.dp)
            .background(if (on) CardOnColor else CardColor, shape)
            .then(if (on) Modifier.border(1.dp, IndicatorGreen.copy(alpha = 0.8f), shape) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(8.dp).background(if (on) IndicatorGreen else Color(0xFF55555A), CircleShape))
        Text(
            if (on) "ON" else "OFF",
            color = if (on) IndicatorGreen else TextDim,
            fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1,
            modifier = Modifier.padding(start = 5.dp)
        )
    }
}

/**
 * Kartu kelompok (MIC, REVERB, ECHO, EQ MIC, LEVEL): satu baris judul (SELURUH baris bisa diketuk untuk ON/OFF) lalu isinya.
 * Datar: tanpa garis tepi. Judul & penanda mengikuti warna TEMA; lampu/indikator tetap hijau.
 * onToggle = null -> tanpa saklar (mis. kartu LEVEL). Isi diberi tinggi sisa kartu (weight), jadi kartu harus punya tinggi pasti.
 * Dipakai PERSIS sama oleh layar utama dan panel floating.
 */
@Composable
fun GroupCard(
    title: String,
    on: Boolean,
    onToggle: (() -> Unit)?,
    opacity: Float,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val accent = ThemeState.accentColor
    Column(
        modifier = modifier
            .background(tileBg(CtrlPanelColor, opacity), RoundedCornerShape(8.dp))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp).tapOnPress(onToggle).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(width = 3.dp, height = 14.dp).background(accent, RoundedCornerShape(2.dp)))
            Text(
                title, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).weight(1f)
            )
            if (onToggle != null) GroupSwitch(on)
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

/**
 * BAGIAN besar layar ("MIKROFON", "EQUALIZER AUDIO"): pita judul berwarna TEMA (hanya judul, tanpa keterangan),
 * isinya di bawah pita. onToggle != null -> saklar ON/OFF untuk seluruh bagian ada di pita (seluruh pita bisa diketuk).
 * fill = true: isi mengisi sisa tinggi (susunan lebar). fill = false: tinggi mengikuti isi (susunan tegak, bisa digulir).
 */
@Composable
fun MixSection(
    title: String,
    on: Boolean,
    onToggle: (() -> Unit)?,
    opacity: Float,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val accent = ThemeState.accentColor
    val onAccent = ThemeState.accentTextColor
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .background(tileBg(BgColor, opacity), shape)
            .clip(shape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp).background(accent).tapOnPress(onToggle).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (onToggle != null) GroupSwitch(on)
        }
        Column(
            modifier = (if (fill) Modifier.weight(1f) else Modifier).fillMaxWidth().padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            content = content
        )
    }
}

/** Tombol biasa (tap) dengan teks tunggal. */
@Composable
fun ButtonTile(label: String, opacity: Float, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val latest by rememberUpdatedState(onTap)
    Box(
        modifier = modifier
            .background(tileBg(PanelColor, opacity), TileShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { latest() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = AppTextSize, maxLines = 1)
    }
}

@Composable
fun EmptyTile(opacity: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(tileBg(CardColor, opacity * 0.45f), TileShape))
}

private const val ROT_RANGE = 1.5f * PI.toFloat()   // satu putaran penuh knob = 270 derajat

/**
 * Gestur knob PUTAR (rotary): jari memutar mengelilingi pusat knob. Nilai berubah mengikuti SELISIH sudut jari
 * (searah jarum jam = naik, berlawanan = turun), jadi knob berputar halus dan TIDAK loncat saat disentuh -
 * 270 derajat putaran jari = 0..100%. Dekat titik pusat diabaikan (sudutnya tidak stabil).
 * Ketuk dua kali = kembali ke nilai awal (bila onDoubleTap diberikan).
 */
@Composable
private fun Modifier.knobGesture(
    value: Float,
    onChange: (Float) -> Unit,
    onActive: (Boolean) -> Unit = {},
    onDoubleTap: (() -> Unit)? = null
): Modifier {
    val latest by rememberUpdatedState(onChange)
    val latestValue by rememberUpdatedState(value.coerceIn(0f, 1f))
    val latestActive by rememberUpdatedState(onActive)
    val latestDouble by rememberUpdatedState(onDoubleTap)
    return this.pointerInput(Unit) {
        var lastTapUp = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            latestActive(true)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val dead = 12.dp.toPx()
            val slop = viewConfiguration.touchSlop
            fun angleOf(p: Offset): Float? {
                val dx = p.x - cx
                val dy = p.y - cy
                return if (hypot(dx, dy) < dead) null else atan2(dy, dx)
            }
            var raw = latestValue
            var last: Float? = angleOf(down.position)
            var moved = false
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    if (!moved && ch.uptimeMillis - down.uptimeMillis < 250L) {
                        val dbl = latestDouble
                        if (dbl != null && down.uptimeMillis - lastTapUp < 350L) {
                            dbl()
                            lastTapUp = 0L
                        } else {
                            lastTapUp = ch.uptimeMillis
                        }
                    }
                    break
                }
                if ((ch.position - down.position).getDistance() > slop) moved = true
                val a = angleOf(ch.position)
                val prev = last
                if (a != null && prev != null) {
                    var d = a - prev
                    val twoPi = 2f * PI.toFloat()
                    while (d > PI.toFloat()) d -= twoPi
                    while (d < -PI.toFloat()) d += twoPi
                    raw = (raw + d / ROT_RANGE).coerceIn(0f, 1f)
                    latest(raw)
                }
                last = a
                ch.consume()
            }
            latestActive(false)
        }
    }
}

/**
 * Gambar knob (tampilan asli): jalur 270 derajat, busur terisi hijau, badan logam, penunjuk hijau.
 * bipolar = true (knob EQ): busur terisi dari tengah (12 jam) ke kiri/kanan, tengah = 0 dB.
 */
@Composable
private fun KnobDial(value: Float, bipolar: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val v = value.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        val d = minOf(size.width, size.height)
        val r = d / 2f * 0.94f
        val c = Offset(size.width / 2f, size.height / 2f)
        val stroke = r * (if (active) 0.17f else 0.13f)
        val arcR = r - stroke / 2f
        val tl = Offset(c.x - arcR, c.y - arcR)
        val sz = Size(arcR * 2f, arcR * 2f)
        drawArc(PadLineColor, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        val startA = if (bipolar) 270f else 135f
        val sweep = if (bipolar) 270f * (v - 0.5f) else 270f * v
        if (kotlin.math.abs(sweep) > 0.5f) {
            if (active) drawArc(IndicatorGreen.copy(alpha = 0.22f), startA, sweep, false, tl, sz, style = Stroke(stroke * 1.9f, cap = StrokeCap.Round))
            drawArc(IndicatorGreen, startA, sweep, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val br = r * 0.68f
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF77777B), Color(0xFF2A2A2D)),
                center = Offset(c.x - br * 0.3f, c.y - br * 0.35f),
                radius = br * 1.7f
            ),
            br, c
        )
        val ang = Math.toRadians((135f + 270f * v).toDouble())
        val ux = cos(ang).toFloat()
        val uy = sin(ang).toFloat()
        drawLine(
            IndicatorGreen,
            Offset(c.x + ux * br * 0.35f, c.y + uy * br * 0.35f),
            Offset(c.x + ux * br * 0.88f, c.y + uy * br * 0.88f),
            strokeWidth = stroke * 0.9f,
            cap = StrokeCap.Round
        )
    }
}

/** Knob berdiri sendiri (dipakai panel floating). Seluruh area modifier bisa disentuh & diputar. */
@Composable
fun Knob(value: Float, modifier: Modifier = Modifier, bipolar: Boolean = false, onChange: (Float) -> Unit) {
    var active by remember { mutableStateOf(false) }
    KnobDial(value, bipolar, active, modifier.knobGesture(value, onChange, { active = it }))
}

/** Kartu knob: nama di atas, knob di tengah (area putar = seluruh bagian tengah), nilai di bawah. */
@Composable
fun KnobTile(
    label: String,
    value: Float,
    display: String,
    opacity: Float,
    modifier: Modifier = Modifier,
    bipolar: Boolean = false,
    dim: Boolean = false,
    onReset: (() -> Unit)? = null,
    onChange: (Float) -> Unit
) {
    var active by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .alpha(if (dim && !active) 0.55f else 1f)
            .background(tileBg(CardColor, opacity), TileShape)
            .then(if (active) Modifier.border(1.5.dp, IndicatorGreen.copy(alpha = 0.75f), TileShape) else Modifier)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            color = if (active) Color.White else PadTextColor,
            fontWeight = FontWeight.SemiBold, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center
        )
        KnobDial(
            value, bipolar, active,
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 1.dp)
                .knobGesture(value, onChange, { active = it }, onReset)
        )
        Text(
            display,
            color = if (active) IndicatorGreen else ValueColor,
            fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center
        )
    }
}

/**
 * Meter level (segmen): hijau -> kuning -> merah. level() dibaca saat menggambar saja.
 * Bentuk menyesuaikan tempat: kartu lebar -> batang mendatar; kartu sempit/tinggi -> batang tegak (bawah = pelan).
 * label kosong -> tanpa teks (judul sudah ada di kartu pemiliknya).
 */
@Composable
fun MeterTile(label: String, level: () -> Float, opacity: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(tileBg(CardColor, opacity), TileShape).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (label.isNotEmpty()) {
            Text(label, color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, maxLines = 1)
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(top = if (label.isNotEmpty()) 3.dp else 0.dp)) {
            val horizontal = size.width >= size.height * 1.4f
            val n = if (horizontal) 16 else 12
            val gap = 2.dp.toPx()
            val lit = (level().coerceIn(0f, 1f) * n).roundToInt()
            val corner = CornerRadius(1.5.dp.toPx())
            fun segColor(i: Int): Color = when {
                i >= lit -> PadLineColor
                i < n * 0.6f -> IndicatorGreen
                i < n * 0.85f -> Color(0xFFFFD43B)
                else -> Color(0xFFFF4B4B)
            }
            if (horizontal) {
                val barH = minOf(size.height, 24.dp.toPx())
                val top = (size.height - barH) / 2f
                val w = (size.width - gap * (n - 1)) / n
                for (i in 0 until n) {
                    drawRoundRect(segColor(i), topLeft = Offset(i * (w + gap), top), size = Size(w, barH), cornerRadius = corner)
                }
            } else {
                val barW = minOf(size.width, 28.dp.toPx())
                val left = (size.width - barW) / 2f
                val h = (size.height - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val y = size.height - (i + 1) * h - i * gap
                    drawRoundRect(segColor(i), topLeft = Offset(left, y), size = Size(barW, h), cornerRadius = corner)
                }
            }
        }
    }
}

// Compose gak nyediain Slider vertikal bawaan -> ukur Slider biasa dengan constraint width/height ditukar, lalu diputar 90 derajat.
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = IndicatorGreen,
            activeTrackColor = IndicatorGreen,
            inactiveTrackColor = PanelColor
        ),
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        x = -(placeable.width / 2 - placeable.height / 2),
                        y = -(placeable.height / 2 - placeable.width / 2)
                    )
                }
            }
            .rotate(-90f)
    )
}
