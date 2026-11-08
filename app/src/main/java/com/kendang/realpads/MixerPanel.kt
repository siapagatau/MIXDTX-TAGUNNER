package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kendang.realpads.skin.MixerBridge
import com.kendang.realpads.skin.SkinUiState

// ---------------------------------------------------------------------------------------------------------------------
// PANEL MIXER TUNGGAL. Layar utama DAN panel floating memanggil MixerGrid() yang sama -> kartu, urutan, warna, ukuran
// huruf, dan perilaku sentuh IDENTIK. Satu-satunya perbedaan: susunan menyesuaikan bentuk ruang yang tersedia
//   - LEBAR (layar utama landscape / floating dilebarkan): dua bagian berdampingan, semua muat tanpa gulir.
//   - TEGAK (floating sempit): dua bagian ditumpuk, 3 knob per baris, digulir ke bawah.
// Bagian ketiga REVERB AUDIO (SysReverbPanel.kt) memakai komponen yang sama: pita di bawah (lebar) / kartu bertumpuk (tegak).
// Bagian keempat (mode equalizer) = EQUALIZER 31 BAND (EqualizerPanel.kt), selalu paling bawah, menyatu dalam gulir yang sama.
// Isi kartu diambil dari MixGroups (MainActivity.kt) -> menambah kontrol baru cukup di sana, kedua tampilan ikut.
//
// Peta kolom (satu kolom = satu knob):
//   LEBAR  MIKROFON (8 kolom): [MIC 2][REVERB 6]  /  [ECHO 3][EQ MIC 3][LEVEL 2]      EQUALIZER AUDIO (3 kolom): [GAIN SUB LOW] / [MID HIGH OUT]
//   TEGAK  MIKROFON (3 kolom): [MIC 2][LEVEL 1] / [REVERB 3+3] / [ECHO 3] / [EQ MIC 3]  EQUALIZER AUDIO (3 kolom): sama dengan LEBAR
// ---------------------------------------------------------------------------------------------------------------------

internal const val KNOB_ROW_DP = 84        // tinggi satu baris knob pada susunan tegak
private const val WIDE_MIN_WIDTH_DP = 640 // lebih sempit dari ini -> susunan tegak
private const val WIDE_MIN_HEIGHT_DP = 260 // lebih pendek dari ini (susunan lebar) -> digulir, knob tidak menciut lagi

private fun groupOf(toggleFn: String): MixGroup = MixGroups.first { it.toggleFn == toggleFn }

// tinggi kartu = padding 8 + judul 30 + jarak 4 + baris knob (+ jarak antar baris 4)
internal fun cardHeight(rows: Int): Dp = (8 + 30 + 4 + rows * KNOB_ROW_DP + (rows - 1) * 4).dp

@Composable
private fun MixKnob(k: MixControl, groupOn: Boolean, ui: SkinUiState, bridge: MixerBridge, opacity: Float, modifier: Modifier) {
    val v = ui.values[k.fn] ?: 0f
    KnobTile(
        k.label, v, formatMixValue(k.fn, v), opacity, modifier,
        bipolar = k.bipolar,
        dim = !groupOn,
        onReset = { bridge.resetValues(listOf(k.fn)) }
    ) { bridge.setValue(k.fn, it) }
}

/** Kartu satu kelompok dengan saklar ON/OFF sendiri. perRow = jumlah knob per baris (lebih banyak dari itu -> baris baru). */
@Composable
private fun MixGroupCard(
    g: MixGroup,
    ui: SkinUiState,
    bridge: MixerBridge,
    opacity: Float,
    modifier: Modifier,
    perRow: Int = g.controls.size
) {
    val on = ui.toggles[g.toggleFn] == true
    GroupCard(g.title, on, { bridge.setToggle(g.toggleFn, !on) }, opacity, modifier) {
        for (row in g.controls.chunked(perRow)) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (k in row) MixKnob(k, on, ui, bridge, opacity, Modifier.weight(1f).fillMaxHeight())
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Kartu meter level mic (tanpa saklar). */
@Composable
private fun LevelCard(ui: SkinUiState, opacity: Float, modifier: Modifier) {
    GroupCard("LEVEL", false, null, opacity, modifier) {
        MeterTile("", { ui.meters["meter.mic"] ?: 0f }, opacity, Modifier.weight(1f).fillMaxWidth())
    }
}

/** Isi bagian EQUALIZER AUDIO: knob 3 per baris, sisa kotak kosong diisi meter OUT. rowH = null -> baris mengisi tinggi (weight). */
@Composable
private fun ColumnScope.EqAudioRows(ui: SkinUiState, bridge: MixerBridge, opacity: Float, rowH: Dp?) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    for (chunk in audio.controls.chunked(3)) {
        val rowMod = if (rowH != null) Modifier.fillMaxWidth().height(rowH) else Modifier.fillMaxWidth().weight(1f)
        Row(modifier = rowMod, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in chunk) MixKnob(k, audioOn, ui, bridge, opacity, Modifier.weight(1f).fillMaxHeight())
            if (chunk.size < 3) {
                MeterTile("OUT", { ui.meters["meter.output"] ?: 0f }, opacity, Modifier.weight((3 - chunk.size).toFloat()).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun MixerWide(ui: SkinUiState, bridge: MixerBridge, opacity: Float, modifier: Modifier) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MixSection("MIKROFON", false, null, opacity, Modifier.weight(8f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MixGroupCard(groupOf("mic.toggle"), ui, bridge, opacity, Modifier.weight(2f).fillMaxHeight())
                MixGroupCard(groupOf("reverb.toggle"), ui, bridge, opacity, Modifier.weight(6f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MixGroupCard(groupOf("echo.toggle"), ui, bridge, opacity, Modifier.weight(3f).fillMaxHeight())
                MixGroupCard(groupOf("eqmic.toggle"), ui, bridge, opacity, Modifier.weight(3f).fillMaxHeight())
                LevelCard(ui, opacity, Modifier.weight(2f).fillMaxHeight())
            }
        }
        MixSection(
            "EQUALIZER AUDIO",
            audioOn, { bridge.setToggle(audio.toggleFn, !audioOn) }, opacity,
            Modifier.weight(3.2f).fillMaxHeight()
        ) {
            EqAudioRows(ui, bridge, opacity, null)
        }
    }
}

@Composable
private fun MixerNarrow(
    ui: SkinUiState, bridge: MixerBridge, opacity: Float, onSysReverbToggle: () -> Unit, scroll: ScrollState, modifier: Modifier
) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    Column(
        modifier = modifier.verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MixSection("MIKROFON", false, null, opacity, Modifier.fillMaxWidth(), fill = false) {
            Row(Modifier.fillMaxWidth().height(cardHeight(1)), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MixGroupCard(groupOf("mic.toggle"), ui, bridge, opacity, Modifier.weight(2f).fillMaxHeight())
                LevelCard(ui, opacity, Modifier.weight(1f).fillMaxHeight())
            }
            MixGroupCard(groupOf("reverb.toggle"), ui, bridge, opacity, Modifier.fillMaxWidth().height(cardHeight(2)), perRow = 3)
            MixGroupCard(groupOf("echo.toggle"), ui, bridge, opacity, Modifier.fillMaxWidth().height(cardHeight(1)))
            MixGroupCard(groupOf("eqmic.toggle"), ui, bridge, opacity, Modifier.fillMaxWidth().height(cardHeight(1)))
        }
        MixSection(
            "EQUALIZER AUDIO",
            audioOn, { bridge.setToggle(audio.toggleFn, !audioOn) }, opacity,
            Modifier.fillMaxWidth(), fill = false
        ) {
            EqAudioRows(ui, bridge, opacity, KNOB_ROW_DP.dp)
        }
        SysReverbNarrow(opacity, onSysReverbToggle, Modifier.fillMaxWidth())
        // Mode equalizer: bagian EQUALIZER 31 BAND tepat di bawah (gulir ke bawah).
        if (CompUi.s.mode) CompressorSection(ui, bridge, opacity, Modifier.fillMaxWidth())
        if (FxUi.s.mode) FxSection(opacity, Modifier.fillMaxWidth())
        if (Eq31Ui.s.mode) EqualizerSection(ui, bridge, opacity, Modifier.fillMaxWidth())
    }
}

/** Satu-satunya panel mixer: dipanggil layar utama (MainActivity) dan panel floating (FloatingBody). */
@Composable
fun MixerGrid(
    ui: SkinUiState,
    bridge: MixerBridge,
    hasBackgroundImage: Boolean,
    opacity: Float,
    modifier: Modifier = Modifier,
    onSysReverbToggle: () -> Unit = {},
    /** Dari pemanggil (layar utama) supaya tombol lompat ke EQUALIZER bisa menggulir panel ini. */
    scroll: ScrollState = rememberScrollState()
) {
    val ctx = LocalContext.current
    remember { Eq31Ui.ensureLoaded(ctx); CompUi.ensureLoaded(ctx); FxUi.ensureLoaded(ctx) }
    BoxWithConstraints(
        modifier = modifier
            .background(if (hasBackgroundImage) Color.Transparent else PadLineColor)
            .padding(6.dp)
    ) {
        val boxMaxWidth = this.maxWidth
        val boxMaxHeight = this.maxHeight
        val wide = boxMaxWidth >= WIDE_MIN_WIDTH_DP.dp && boxMaxWidth >= boxMaxHeight * 1.3f
        if (!wide) {
            MixerNarrow(ui, bridge, opacity, onSysReverbToggle, scroll, Modifier.fillMaxSize())
        } else {
            // MIKROFON + EQUALIZER AUDIO mengisi layar; pita REVERB AUDIO tepat di bawahnya (gulir ke bawah),
            // lalu (mode equalizer) bagian EQUALIZER 31 BAND.
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MixerWide(ui, bridge, opacity, Modifier.fillMaxWidth().height(maxOf(boxMaxHeight, WIDE_MIN_HEIGHT_DP.dp)))
                SysReverbWide(opacity, onSysReverbToggle, Modifier.fillMaxWidth().height(SYS_WIDE_HEIGHT_DP.dp))
                if (CompUi.s.mode) CompressorSection(ui, bridge, opacity, Modifier.fillMaxWidth())
                if (FxUi.s.mode) FxSection(opacity, Modifier.fillMaxWidth())
                if (Eq31Ui.s.mode) EqualizerSection(ui, bridge, opacity, Modifier.fillMaxWidth())
            }
        }
    }
}
