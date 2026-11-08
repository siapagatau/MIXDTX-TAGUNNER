package com.kendang.realpads

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kendang.realpads.skin.CompSkinManager
import com.kendang.realpads.skin.MixerBridge
import com.kendang.realpads.skin.SkinHost
import com.kendang.realpads.skin.SkinUiState
import com.kendang.realpads.skin.SkinZones

// ---------------------------------------------------------------------------------------------------------------------
// BAGIAN KOMPRESOR (gaya Poweramp) - tampil MENYATU di bawah mixer dan DI ATAS equalizer saat "mode kompresor" aktif
// (layar utama & floating, klasik & skin). Pola sama dengan EqualizerSection:
//   - skin kompresor aktif -> SkinHost (skin dari repo kompresor), tinggi = proporsi asli skin pada lebar yang tersedia
//   - selain itu           -> CompressorClassic: knob Attack, Release, Rasio, Frekuensi, Threshold, Gain, Keseimbangan, Volume
//                             + tombol "Setel ulang". Lebar >= 560dp: satu baris; lebih sempit: 3 baris.
// ---------------------------------------------------------------------------------------------------------------------

private const val COMP_WIDE_MIN_DP = 560
private const val COMP_WIDE_HEIGHT_DP = 170
private const val COMP_NARROW_HEIGHT_DP = 330

@Composable
fun CompressorSection(
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
    LaunchedEffect(Unit) { CompSkinManager.ensureLoaded(ctx) }
    val st = CompSkinManager.state
    val failedReason = (st as? CompSkinManager.State.Failed)?.reason
    LaunchedEffect(failedReason) {
        if (failedReason != null) Toast.makeText(ctx, "Skin kompresor gagal dipakai: $failedReason", Toast.LENGTH_LONG).show()
    }
    val skin = (st as? CompSkinManager.State.Active)?.skin
    when {
        skin != null -> {
            val zone = SkinZones.fullZone(skin)
            val ratio = if (zone.h > 0f && zone.w > 0f) zone.w / zone.h else 2f
            SkinHost(
                skin = skin,
                actions = bridge,
                ui = ui,
                onFatal = { CompSkinManager.reportRenderFailure(it) },
                modifier = modifier.fillMaxWidth().aspectRatio(ratio),
                widthPercent = 100,
                inputLocked = inputLocked,
                renderScale = renderScale,
                onRenderScaleFailed = onRenderScaleFailed
            )
        }
        st is CompSkinManager.State.Loading && CompSkinManager.selectedId(ctx) != CompSkinManager.CLASSIC_ID ->
            Box(modifier.fillMaxWidth().height(COMP_WIDE_HEIGHT_DP.dp).background(BgColor))
        else -> CompressorClassic(ui, bridge, opacity, modifier.fillMaxWidth())
    }
}

@Composable
fun CompressorClassic(ui: SkinUiState, bridge: MixerBridge, opacity: Float, modifier: Modifier = Modifier) {
    val on = ui.toggles[Comp.TOGGLE] == true
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= COMP_WIDE_MIN_DP.dp
        val h = if (wide) COMP_WIDE_HEIGHT_DP.dp else COMP_NARROW_HEIGHT_DP.dp
        MixSection(
            "KOMPRESOR",
            on, { bridge.setToggle(Comp.TOGGLE, !on) }, opacity,
            Modifier.fillMaxWidth().height(h)
        ) {
            if (wide) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (fn in Comp.VALUE_FUNCTIONS) CompKnob(fn, on, ui, bridge, opacity, Modifier.weight(1f).fillMaxHeight())
                    CompResetButton(bridge, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                val fns = Comp.VALUE_FUNCTIONS.toList()
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (fn in fns.subList(0, 3)) CompKnob(fn, on, ui, bridge, opacity, Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (fn in fns.subList(3, 6)) CompKnob(fn, on, ui, bridge, opacity, Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (fn in fns.subList(6, 8)) CompKnob(fn, on, ui, bridge, opacity, Modifier.weight(1f).fillMaxHeight())
                    CompResetButton(bridge, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun CompKnob(fn: String, on: Boolean, ui: SkinUiState, bridge: MixerBridge, opacity: Float, modifier: Modifier) {
    val v = ui.values[fn] ?: Comp.DEFAULTS.getValue(fn)
    KnobTile(
        Comp.LABELS.getValue(fn), v, Comp.display(fn, v), opacity, modifier,
        bipolar = fn == Comp.BALANCE, dim = !on, onReset = { bridge.resetValues(listOf(fn)) }
    ) { bridge.setValue(fn, it) }
}

@Composable
private fun CompResetButton(bridge: MixerBridge, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .border(1.dp, Color(0xFFA9A9AD), shape)
            .clickable { bridge.press(Comp.RESET) }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Setel ulang", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
            maxLines = 2, textAlign = TextAlign.Center
        )
    }
}
