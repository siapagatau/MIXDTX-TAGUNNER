package com.kendang.realpads

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kendang.realpads.skin.EqSkinManager
import com.kendang.realpads.skin.MixerBridge
import com.kendang.realpads.skin.SkinHost
import com.kendang.realpads.skin.SkinUiState
import com.kendang.realpads.skin.SkinZones
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------------------------
// BAGIAN EQUALIZER 31 BAND - tampil MENYATU di bawah mixer saat "mode equalizer" aktif (layar utama & floating, klasik & skin).
//
// EqualizerSection memilih tampilan:
//   - skin equalizer aktif -> SkinHost (skin dari repo equalizer), tinggi = proporsi asli skin pada lebar yang tersedia
//     (tanpa celah dengan mixer di atasnya)
//   - selain itu           -> EqualizerClassic: dua kartu, L (atas) dan R (bawah), masing-masing knob GAIN + 31 slider band.
//                              Bila terlalu sempit (floating tegak) tiap kartu bisa digeser mendatar; slider tidak menciut di bawah 17dp.
// ---------------------------------------------------------------------------------------------------------------------

/** Tinggi bagian EQUALIZER klasik. */
internal const val EQ_SECTION_HEIGHT_DP = 400
private const val EQ_MIN_BAND_DP = 17

@Composable
fun EqualizerSection(
    ui: SkinUiState,
    bridge: MixerBridge,
    opacity: Float,
    modifier: Modifier = Modifier,
    /** Hanya floating: resolusi render skin (zoom) + penguncian sentuhan saat cubit. */
    renderScale: Float = 1f,
    onRenderScaleFailed: () -> Unit = {},
    inputLocked: () -> Boolean = { false },
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { EqSkinManager.ensureLoaded(ctx) }
    val st = EqSkinManager.state
    val failedReason = (st as? EqSkinManager.State.Failed)?.reason
    LaunchedEffect(failedReason) {
        if (failedReason != null) Toast.makeText(ctx, "Skin equalizer gagal dipakai: $failedReason", Toast.LENGTH_LONG).show()
    }
    val skin = (st as? EqSkinManager.State.Active)?.skin
    when {
        skin != null -> {
            val zone = SkinZones.fullZone(skin)
            val ratio = if (zone.h > 0f && zone.w > 0f) zone.w / zone.h else 2f
            SkinHost(
                skin = skin,
                actions = bridge,
                ui = ui,
                onFatal = { EqSkinManager.reportRenderFailure(it) },
                modifier = modifier.fillMaxWidth().aspectRatio(ratio),
                widthPercent = 100,
                inputLocked = inputLocked,
                renderScale = renderScale,
                onRenderScaleFailed = onRenderScaleFailed
            )
        }
        st is EqSkinManager.State.Loading && EqSkinManager.selectedId(ctx) != EqSkinManager.CLASSIC_ID ->
            Box(modifier.fillMaxWidth().height(EQ_SECTION_HEIGHT_DP.dp).background(BgColor))
        else -> EqualizerClassic(ui, bridge, opacity, modifier.fillMaxWidth())
    }
}

@Composable
fun EqualizerClassic(ui: SkinUiState, bridge: MixerBridge, opacity: Float, modifier: Modifier = Modifier) {
    val on = ui.toggles[Eq31.TOGGLE] == true
    MixSection(
        "EQUALIZER 31 BAND",
        on, { bridge.setToggle(Eq31.TOGGLE, !on) }, opacity,
        modifier.height(EQ_SECTION_HEIGHT_DP.dp)
    ) {
        EqChannelCard("L  KIRI", Eq31.LEFT, on, ui, bridge, opacity, Modifier.weight(1f).fillMaxWidth())
        EqChannelCard("R  KANAN", Eq31.RIGHT, on, ui, bridge, opacity, Modifier.weight(1f).fillMaxWidth())
    }
}

/** Satu sisi (L atau R): knob GAIN di kiri, 31 slider band di kanan. */
@Composable
private fun EqChannelCard(
    title: String, side: Int, on: Boolean, ui: SkinUiState, bridge: MixerBridge, opacity: Float, modifier: Modifier
) {
    GroupCard(title, on, null, opacity, modifier) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val gfn = Eq31.gainFn(side)
            val gv = ui.values[gfn] ?: 0.5f
            KnobTile(
                "GAIN", gv, dbText(gv), opacity, Modifier.width(64.dp).fillMaxHeight(),
                bipolar = true, dim = !on, onReset = { bridge.resetValues(listOf(gfn)) }
            ) { bridge.setValue(gfn, it) }

            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val natural = maxWidth / Eq31.BANDS
                val minW = EQ_MIN_BAND_DP.dp
                val scrolls = natural < minW
                val bandW: Dp = if (scrolls) minW else natural
                val hScroll = rememberScrollState()
                Row(
                    Modifier.fillMaxHeight().then(if (scrolls) Modifier.horizontalScroll(hScroll) else Modifier.fillMaxWidth())
                ) {
                    for (i in 0 until Eq31.BANDS) {
                        val fn = Eq31.fn(side, i)
                        EqBand(
                            label = Eq31.LABELS[i],
                            value = ui.values[fn] ?: 0.5f,
                            dim = !on,
                            modifier = Modifier.width(bandW).fillMaxHeight(),
                            onReset = { bridge.resetValues(listOf(fn)) }
                        ) { bridge.setValue(fn, it) }
                    }
                }
            }
        }
    }
}

private fun dbText(knob: Float): String {
    val d = Eq31.knobToDb(knob).roundToInt()
    return if (d > 0) "+$d dB" else "$d dB"
}

private fun dbShort(knob: Float): String {
    val d = Eq31.knobToDb(knob).roundToInt()
    return if (d > 0) "+$d" else "$d"
}

/** Satu band: nilai dB di atas, slider tegak di tengah, frekuensi di bawah. */
@Composable
private fun EqBand(
    label: String,
    value: Float,
    dim: Boolean,
    modifier: Modifier,
    onReset: () -> Unit,
    onChange: (Float) -> Unit
) {
    var active by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.alpha(if (dim && !active) 0.55f else 1f).padding(horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            dbShort(value),
            color = if (active) IndicatorGreen else TextDim,
            fontWeight = FontWeight.SemiBold, fontSize = 8.sp, maxLines = 1, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        BandFader(
            value, active,
            Modifier.weight(1f).fillMaxWidth().bandGesture(onChange, { active = it }, onReset)
        )
        Text(
            label,
            color = if (active) Color.White else PadTextColor,
            fontWeight = FontWeight.SemiBold, fontSize = 8.sp, maxLines = 1, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private val THUMB_H = 12.dp

/** Gambar fader tegak: jalur, tanda 0 dB, isi hijau dari tengah, kenop. Bawah = -12 dB, atas = +12 dB. */
@Composable
private fun BandFader(value: Float, active: Boolean, modifier: Modifier) {
    val v = value.coerceIn(0f, 1f)
    Canvas(modifier) {
        val th = THUMB_H.toPx()
        val top = th / 2f
        val bottom = size.height - th / 2f
        val cx = size.width / 2f
        val trackW = 3.dp.toPx()
        fun yOf(x: Float) = bottom - (bottom - top) * x
        val yMid = yOf(0.5f)
        val yVal = yOf(v)
        drawLine(PadLineColor, Offset(cx, top), Offset(cx, bottom), strokeWidth = trackW, cap = StrokeCap.Round)
        // tanda 0 dB
        drawLine(PanelColor, Offset(cx - 6.dp.toPx(), yMid), Offset(cx + 6.dp.toPx(), yMid), strokeWidth = 1.dp.toPx())
        // isi dari tengah ke nilai
        if (abs(yVal - yMid) > 0.5f) {
            drawLine(IndicatorGreen.copy(alpha = if (active) 1f else 0.85f), Offset(cx, yMid), Offset(cx, yVal), strokeWidth = trackW, cap = StrokeCap.Round)
        }
        // kenop
        val tw = minOf(size.width * 0.86f, 18.dp.toPx())
        val tl = Offset(cx - tw / 2f, yVal - th / 2f)
        drawRoundRect(
            Brush.verticalGradient(
                listOf(Color(0xFF8A8A8F), Color(0xFF39393C)),
                startY = tl.y, endY = tl.y + th
            ),
            tl, Size(tw, th), CornerRadius(2.dp.toPx())
        )
        drawLine(
            if (active) Color.White else IndicatorGreen,
            Offset(tl.x + 2.dp.toPx(), yVal), Offset(tl.x + tw - 2.dp.toPx(), yVal),
            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round
        )
    }
}

/**
 * Gestur fader tegak: nilai = posisi jari (menyentuh langsung melompat ke titik itu, seperti fader skin), geser = mengikuti.
 * Ketuk dua kali = kembali ke 0 dB. Tiap fader menangani jarinya sendiri -> beberapa fader bisa digeser bersamaan.
 */
@Composable
private fun Modifier.bandGesture(
    onChange: (Float) -> Unit,
    onActive: (Boolean) -> Unit,
    onDoubleTap: () -> Unit
): Modifier {
    val latest by rememberUpdatedState(onChange)
    val latestActive by rememberUpdatedState(onActive)
    val latestDouble by rememberUpdatedState(onDoubleTap)
    return this.pointerInput(Unit) {
        var lastTapUp = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            latestActive(true)
            val pad = THUMB_H.toPx() / 2f
            fun valueAt(y: Float): Float {
                val span = (size.height - 2f * pad).coerceAtLeast(1f)
                return (1f - (y - pad) / span).coerceIn(0f, 1f)
            }
            val slop = viewConfiguration.touchSlop
            var moved = false
            latest(valueAt(down.position.y))
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    if (!moved && ch.uptimeMillis - down.uptimeMillis < 250L) {
                        if (down.uptimeMillis - lastTapUp < 350L) {
                            latestDouble()
                            lastTapUp = 0L
                        } else {
                            lastTapUp = ch.uptimeMillis
                        }
                    }
                    break
                }
                if ((ch.position - down.position).getDistance() > slop) moved = true
                latest(valueAt(ch.position.y))
                ch.consume()
            }
            latestActive(false)
        }
    }
}
