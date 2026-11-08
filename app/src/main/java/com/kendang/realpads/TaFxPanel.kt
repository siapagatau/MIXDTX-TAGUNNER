package com.kendang.realpads

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot

// ---------------------------------------------------------------------------------------------------------------------
// BAGIAN FX (gaya taGunner) - tampil MENYATU di bawah kompresor dan DI ATAS equalizer saat "mode FX" aktif (layar utama &
// floating). Memakai komponen mixer yang sama (MixSection, GroupCard, KnobTile) -> tampilan mengikuti APK.
//   FX RACK    : SUBSONIC, SUB BASS, WIDENER, TUBE DRIVE, 8D PAN, LIMITER, MBC 10 BAND
//   PARAMETRIC : grafik respons + titik (Hz / dB / Q), ketuk grafik = tambah titik, seret = pindah
// ---------------------------------------------------------------------------------------------------------------------

private const val FX_WIDE_MIN_DP = 600
private const val GRAPH_H_DP = 140
private const val GRAPH_DB = 20f

@Composable
fun FxSection(opacity: Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { FxUi.ensureLoaded(ctx); Eq31Ui.ensureLoaded(ctx); CompUi.ensureLoaded(ctx) }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= FX_WIDE_MIN_DP.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FxRack(wide, opacity)
            FxParametric(opacity)
        }
    }
}

// ---- komponen kecil ----

/** Tombol kecil dengan keadaan ON (hijau). onLong = tahan lama (opsional). */
@Composable
private fun FxChip(label: String, on: Boolean, modifier: Modifier = Modifier, onLong: (() -> Unit)? = null, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .background(if (on) IndicatorGreen else PanelColor, shape)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { long?.invoke() }) }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (on) IndicatorGreenText else Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center
        )
    }
}

private fun fxKnob(v: Float) = v.coerceIn(0f, 1f)

// ---- FX RACK ----

@Composable
private fun FxRack(wide: Boolean, opacity: Float) {
    val ctx = LocalContext.current
    val s = FxUi.s
    val status by GlobalEqEngine.fxStatus.collectAsState()
    val master = s.enabled
    val cardH = cardHeight(1)

    @Composable
    fun subsonic(m: Modifier) = GroupCard("SUBSONIC", s.subsonicOn, { FxUi.update(ctx) { it.copy(subsonicOn = !it.subsonicOn) } }, opacity, m) {
        KnobTile("Potong", s.subsonic, Fx.subsonicText(s.subsonic), opacity, Modifier.weight(1f).fillMaxWidth(), dim = !(master && s.subsonicOn),
            onReset = { FxUi.update(ctx) { it.copy(subsonic = 0.3f) } }) { v -> FxUi.update(ctx) { it.copy(subsonic = fxKnob(v)) } }
    }

    @Composable
    fun subBass(m: Modifier) = GroupCard("SUB BASS", s.subBassOn, { FxUi.update(ctx) { it.copy(subBassOn = !it.subBassOn) } }, opacity, m) {
        KnobTile("40 Hz", s.subBass, "+" + Fx.dbText(Fx.subBassDb(s.subBass)), opacity, Modifier.weight(1f).fillMaxWidth(), dim = !(master && s.subBassOn),
            onReset = { FxUi.update(ctx) { it.copy(subBass = 0.4f) } }) { v -> FxUi.update(ctx) { it.copy(subBass = fxKnob(v)) } }
    }

    @Composable
    fun widener(m: Modifier) = GroupCard("WIDENER", s.widenerOn, { FxUi.update(ctx) { it.copy(widenerOn = !it.widenerOn) } }, opacity, m) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KnobTile("Lebar", s.widener, Fx.widenerText(s.widener), opacity, Modifier.weight(1f).fillMaxHeight(), dim = !(master && s.widenerOn),
                onReset = { FxUi.update(ctx) { it.copy(widener = 0.5f) } }) { v -> FxUi.update(ctx) { it.copy(widener = fxKnob(v)) } }
            FxChip(if (s.speaker) "SPEAKER" else "HEADPHONE", s.speaker, Modifier.width(58.dp).fillMaxHeight()) {
                FxUi.update(ctx) { it.copy(speaker = !it.speaker) }
            }
        }
    }

    @Composable
    fun tube(m: Modifier) = GroupCard("TUBE DRIVE", s.tubeOn, { FxUi.update(ctx) { it.copy(tubeOn = !it.tubeOn) } }, opacity, m) {
        KnobTile("Drive", s.tube, "+" + Fx.dbText(Fx.tubeDb(s.tube)), opacity, Modifier.weight(1f).fillMaxWidth(), dim = !(master && s.tubeOn),
            onReset = { FxUi.update(ctx) { it.copy(tube = 0.3f) } }) { v -> FxUi.update(ctx) { it.copy(tube = fxKnob(v)) } }
    }

    @Composable
    fun pan8d(m: Modifier) = GroupCard("8D PAN", s.panOn, { FxUi.update(ctx) { it.copy(panOn = !it.panOn) } }, opacity, m) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KnobTile("Depth", s.panDepth, Fx.dbText(Fx.panDepthDb(s.panDepth)), opacity, Modifier.weight(1f).fillMaxHeight(), dim = !(master && s.panOn),
                onReset = { FxUi.update(ctx) { it.copy(panDepth = 0.7f) } }) { v -> FxUi.update(ctx) { it.copy(panDepth = fxKnob(v)) } }
            KnobTile("Putaran", s.panCycle, Fx.panPeriodText(s.panCycle), opacity, Modifier.weight(1f).fillMaxHeight(), dim = !(master && s.panOn),
                onReset = { FxUi.update(ctx) { it.copy(panCycle = 0.4f) } }) { v -> FxUi.update(ctx) { it.copy(panCycle = fxKnob(v)) } }
        }
    }

    @Composable
    fun dynamics(m: Modifier) = GroupCard("DINAMIKA", false, null, opacity, m) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FxChip("LIMITER", s.limiterOn, Modifier.weight(1f).fillMaxHeight()) { FxUi.update(ctx) { it.copy(limiterOn = !it.limiterOn) } }
            FxChip("MBC 10 BAND", s.mbc10, Modifier.weight(1f).fillMaxHeight()) { FxUi.update(ctx) { it.copy(mbc10 = !it.mbc10) } }
        }
    }

    MixSection("FX RACK", master, { FxUi.setEnabled(ctx, !master) }, opacity, Modifier.fillMaxWidth(), fill = false) {
        if (wide) {
            Row(Modifier.fillMaxWidth().height(cardH), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subsonic(Modifier.weight(1f).fillMaxHeight()); subBass(Modifier.weight(1f).fillMaxHeight())
                widener(Modifier.weight(1.4f).fillMaxHeight()); tube(Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().height(cardH), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pan8d(Modifier.weight(2f).fillMaxHeight()); dynamics(Modifier.weight(2f).fillMaxHeight())
            }
        } else {
            Row(Modifier.fillMaxWidth().height(cardH), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subsonic(Modifier.weight(1f).fillMaxHeight()); subBass(Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().height(cardH), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                widener(Modifier.weight(1.4f).fillMaxHeight()); tube(Modifier.weight(1f).fillMaxHeight())
            }
            pan8d(Modifier.fillMaxWidth().height(cardH))
            dynamics(Modifier.fillMaxWidth().height(cardH))
        }
        status?.let {
            Text(it, color = Color(0xFFFFB74D), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

// ---- PARAMETRIC ----

@Composable
private fun FxParametric(opacity: Float) {
    val ctx = LocalContext.current
    val s = FxUi.s
    val sel = FxUi.selected
    val eq = Eq31Ui.s
    val on = s.enabled && s.peqOn
    MixSection("PARAMETRIC", s.peqOn, { FxUi.update(ctx) { it.copy(peqOn = !it.peqOn) } }, opacity, Modifier.fillMaxWidth(), fill = false) {
        PeqGraph(s.points, sel, eq, on, Modifier.fillMaxWidth().height(GRAPH_H_DP.dp))
        Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                s.points.forEachIndexed { i, p ->
                    FxChip(Fx.hzText(p.hz), i == sel, Modifier.height(30.dp).width(62.dp)) { FxUi.selected = i }
                }
            }
            FxChip("+ TITIK", false, Modifier.height(30.dp).width(64.dp)) { addPeqPoint(ctx, 1000f, 0f) }
            FxChip("HAPUS", false, Modifier.height(30.dp).width(56.dp)) { FxUi.removeSelected(ctx) }
            FxChip("RESET", false, Modifier.height(30.dp).width(56.dp)) { FxUi.clearPoints(ctx) }
        }
        val p = s.points.getOrNull(sel)
        if (p != null) {
            Row(Modifier.fillMaxWidth().height(KNOB_ROW_DP.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KnobTile("Frekuensi", Fx.hzToKnob(p.hz), Fx.hzText(p.hz), opacity, Modifier.weight(1f).fillMaxHeight(), dim = !on) { v ->
                    FxUi.setPoint(ctx, sel) { it.copy(hz = Fx.hzOf(v)) }
                }
                KnobTile("Gain", Fx.dbToKnob(p.db), Fx.dbText(p.db), opacity, Modifier.weight(1f).fillMaxHeight(), bipolar = true, dim = !on,
                    onReset = { FxUi.setPoint(ctx, sel) { it.copy(db = 0f) } }) { v ->
                    FxUi.setPoint(ctx, sel) { it.copy(db = Fx.dbOf(v)) }
                }
                KnobTile("Q", Fx.qToKnob(p.q), String.format(java.util.Locale("id", "ID"), "%.1f", p.q), opacity, Modifier.weight(1f).fillMaxHeight(), dim = !on,
                    onReset = { FxUi.setPoint(ctx, sel) { it.copy(q = 1.4f) } }) { v ->
                    FxUi.setPoint(ctx, sel) { it.copy(q = Fx.qOf(v)) }
                }
            }
        } else {
            Text(
                "Ketuk grafik untuk menambah titik, seret titik untuk memindah (maks. ${Fx.MAX_POINTS}).",
                color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

private fun addPeqPoint(ctx: Context, hz: Float, db: Float) {
    FxUi.ensureLoaded(ctx)
    if (FxUi.s.points.size >= Fx.MAX_POINTS) {
        Toast.makeText(ctx, "Maksimal ${Fx.MAX_POINTS} titik", Toast.LENGTH_SHORT).show()
        return
    }
    FxUi.update(ctx) { it.copy(points = it.points + PeqPoint(hz.coerceIn(Fx.HZ_MIN, Fx.HZ_MAX), db.coerceIn(-Fx.PEQ_MAX_DB, Fx.PEQ_MAX_DB), 1.4f)) }
    FxUi.selected = FxUi.s.points.size - 1
}

/** Respons EQ 31 band (rata-rata L dan R) di frekuensi f, dB - ditampilkan bersama kurva parametrik di grafik. */
private fun eq31Db(eq: Eq31Settings, f: Float): Float {
    if (!eq.enabled) return 0f
    val c = Eq31.CENTERS
    if (f <= c[0]) return Eq31.knobToDb((eq.band(0, 0) + eq.band(1, 0)) / 2f)
    if (f >= c[c.size - 1]) return Eq31.knobToDb((eq.band(0, c.size - 1) + eq.band(1, c.size - 1)) / 2f)
    var i = 0
    while (i < c.size - 2 && f > c[i + 1]) i++
    val t = (kotlin.math.ln(f / c[i]) / kotlin.math.ln(c[i + 1] / c[i])).coerceIn(0f, 1f)
    val a = Eq31.knobToDb((eq.band(0, i) + eq.band(1, i)) / 2f)
    val b = Eq31.knobToDb((eq.band(0, i + 1) + eq.band(1, i + 1)) / 2f)
    return a + (b - a) * t
}

@Composable
private fun PeqGraph(points: List<PeqPoint>, selected: Int, eq: Eq31Settings, on: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val accent = ThemeState.accentColor
    val pts by rememberUpdatedState(points)
    Canvas(
        modifier
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                val hitPx = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun xOf(hz: Float) = Fx.hzToKnob(hz) * w
                    fun yOf(db: Float) = (0.5f - db / (2f * GRAPH_DB)) * h
                    val cur = pts
                    var hit = -1
                    var best = hitPx
                    cur.forEachIndexed { i, p ->
                        val d = hypot(xOf(p.hz) - down.position.x, yOf(p.db) - down.position.y)
                        if (d < best) { best = d; hit = i }
                    }
                    if (hit >= 0) {
                        FxUi.selected = hit
                        down.consume()
                        drag(down.id) { change ->
                            val hz = Fx.hzOf((change.position.x / w).coerceIn(0f, 1f))
                            val db = ((0.5f - change.position.y / h) * 2f * GRAPH_DB).coerceIn(-GRAPH_DB, GRAPH_DB)
                            FxUi.setPoint(ctx, hit) { it.copy(hz = hz, db = db) }
                            change.consume()
                        }
                    } else {
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            val hz = Fx.hzOf((up.position.x / w).coerceIn(0f, 1f))
                            val db = ((0.5f - up.position.y / h) * 2f * GRAPH_DB).coerceIn(-GRAPH_DB, GRAPH_DB)
                            addPeqPoint(ctx, hz, db)
                        }
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val grid = Color(0xFF3A3A3E)
        // garis 0 dB + +-10 dB
        for (db in floatArrayOf(-10f, 0f, 10f)) {
            val y = (0.5f - db / (2f * GRAPH_DB)) * h
            drawLine(if (db == 0f) Color(0xFF6A6A70) else grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        // garis frekuensi: 100, 1k, 10k
        for (hz in floatArrayOf(100f, 1000f, 10000f)) {
            val x = Fx.hzToKnob(hz) * w
            drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        // kurva gabungan (EQ 31 band + parametrik)
        val path = androidx.compose.ui.graphics.Path()
        val n = 120
        for (k in 0..n) {
            val x = w * k / n
            val f = Fx.hzOf(k / n.toFloat())
            val db = (eq31Db(eq, f) + Fx.peqDb(if (on) points else emptyList(), f)).coerceIn(-GRAPH_DB, GRAPH_DB)
            val y = (0.5f - db / (2f * GRAPH_DB)) * h
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (on) IndicatorGreen else Color(0xFF6A6A70), style = Stroke(width = 3f))
        // titik
        points.forEachIndexed { i, p ->
            val c = Offset(Fx.hzToKnob(p.hz) * w, (0.5f - p.db / (2f * GRAPH_DB)) * h)
            drawCircle(if (i == selected) accent else Color.White, radius = if (i == selected) 9f else 6f, center = c)
            drawCircle(Color.Black, radius = if (i == selected) 9f else 6f, center = c, style = Stroke(width = 1.5f))
        }
    }
}
