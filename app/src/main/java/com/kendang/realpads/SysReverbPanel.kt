package com.kendang.realpads

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------------------------------
// REVERB AUDIO sebagai BAGIAN panel mixer (bukan dialog/tombol terpisah). Memakai komponen yang sama dengan bagian lain
// (MixSection, GroupCard, KnobTile, MeterTile), jadi warna, ukuran, dan perilaku sentuhnya identik.
//   - saklar di judul bagian = nyalakan/matikan capture audio (menyalakan -> izin rekam layar)
//   - kartu REVERB dan DELAY punya saklar sendiri; knob KIRIM per band (SUB/LOW/MID/HIGH) ke reverb dan ke delay
// Susunan LEBAR (layar utama): satu pita dua baris di bawah MIKROFON/EQ. Susunan TEGAK (floating): kartu bertumpuk.
// ---------------------------------------------------------------------------------------------------------------------

/** Tinggi pita REVERB AUDIO pada susunan lebar: judul 30 + isi (padding 10 + dua baris kartu + jarak 5). */
internal const val SYS_WIDE_HEIGHT_DP = 300

/** Pengaturan yang dipakai bersama layar utama & floating (satu sumber, tersimpan ke SysReverbStore). */
object SysReverbUi {
    var s: SysReverbSettings by mutableStateOf(SysReverbSettings())
        private set
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (!loaded) {
            s = SysReverbStore.load(context.applicationContext)
            loaded = true
        }
    }

    fun update(context: Context, f: (SysReverbSettings) -> SysReverbSettings) {
        ensureLoaded(context)
        val n = f(s)
        s = n
        SysReverbStore.save(context.applicationContext, n)
        if (SysReverbService.running.value) SysReverbEngine.push(n)
    }
}

private class SysKnob(
    val label: String,
    val value: Float,
    val display: String,
    val reset: Float,
    val onChange: (Float) -> Unit
)

private val Defaults = SysReverbSettings()

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

private fun setBand(list: List<Float>, i: Int, v: Float): List<Float> = list.toMutableList().also { it[i] = v }

private fun reverbKnobs(c: Context, s: SysReverbSettings): List<SysKnob> = listOf(
    SysKnob("WET", s.wet, pct(s.wet), Defaults.wet) { v -> SysReverbUi.update(c) { it.copy(wet = v) } },
    SysKnob("ROOM", s.room, pct(s.room), Defaults.room) { v -> SysReverbUi.update(c) { it.copy(room = v) } },
    SysKnob("DAMP", s.damp, pct(s.damp), Defaults.damp) { v -> SysReverbUi.update(c) { it.copy(damp = v) } },
    SysKnob("WIDTH", s.width, pct(s.width), Defaults.width) { v -> SysReverbUi.update(c) { it.copy(width = v) } },
    SysKnob("CESS", s.cess, pct(s.cess), Defaults.cess) { v -> SysReverbUi.update(c) { it.copy(cess = v) } },
    SysKnob("PRE-DLY", s.preDelay, "${(s.preDelay * 80).roundToInt()} ms", Defaults.preDelay) { v ->
        SysReverbUi.update(c) { it.copy(preDelay = v) }
    },
)

private fun delayKnobs(c: Context, s: SysReverbSettings): List<SysKnob> = listOf(
    SysKnob("DELAY", s.echoDelay, "${(50 + s.echoDelay * 550).roundToInt()} ms", Defaults.echoDelay) { v ->
        SysReverbUi.update(c) { it.copy(echoDelay = v) }
    },
    SysKnob("FEEDBACK", s.echoFeedback, pct(s.echoFeedback), Defaults.echoFeedback) { v ->
        SysReverbUi.update(c) { it.copy(echoFeedback = v) }
    },
    SysKnob("MIX", s.echoMix, pct(s.echoMix), Defaults.echoMix) { v -> SysReverbUi.update(c) { it.copy(echoMix = v) } },
)

private val BandNames = listOf("SUB", "LOW", "MID", "HIGH")

private fun reverbSendKnobs(c: Context, s: SysReverbSettings): List<SysKnob> = BandNames.mapIndexed { i, name ->
    SysKnob(name, s.revSend[i], pct(s.revSend[i]), Defaults.revSend[i]) { v ->
        SysReverbUi.update(c) { it.copy(revSend = setBand(it.revSend, i, v)) }
    }
}

private fun echoSendKnobs(c: Context, s: SysReverbSettings): List<SysKnob> = BandNames.mapIndexed { i, name ->
    SysKnob(name, s.echoSend[i], pct(s.echoSend[i]), Defaults.echoSend[i]) { v ->
        SysReverbUi.update(c) { it.copy(echoSend = setBand(it.echoSend, i, v)) }
    }
}

/** Kartu knob: sama dengan MixGroupCard (judul + saklar opsional, knob per baris). */
@Composable
private fun SysKnobCard(
    title: String,
    on: Boolean,
    onToggle: (() -> Unit)?,
    dim: Boolean,
    knobs: List<SysKnob>,
    perRow: Int,
    opacity: Float,
    modifier: Modifier
) {
    GroupCard(title, on, onToggle, opacity, modifier) {
        for (row in knobs.chunked(perRow)) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (k in row) {
                    KnobTile(
                        k.label, k.value, k.display, opacity, Modifier.weight(1f).fillMaxHeight(),
                        dim = dim,
                        onReset = { k.onChange(k.reset) },
                        onChange = k.onChange
                    )
                }
                repeat(perRow - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Kartu OUTPUT: knob GAIN + meter keluaran efek. */
@Composable
private fun SysOutputCard(c: Context, s: SysReverbSettings, dim: Boolean, level: () -> Float, opacity: Float, modifier: Modifier) {
    GroupCard("OUTPUT", false, null, opacity, modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            KnobTile(
                "GAIN", s.gain, "${(s.gain * 200).roundToInt()}%", opacity, Modifier.weight(1f).fillMaxHeight(),
                dim = dim,
                onReset = { SysReverbUi.update(c) { it.copy(gain = Defaults.gain) } }
            ) { v -> SysReverbUi.update(c) { it.copy(gain = v) } }
            MeterTile("OUT", level, opacity, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun rememberSysLevel(running: Boolean): () -> Float {
    var level by remember { mutableStateOf(0f) }
    LaunchedEffect(running) {
        while (running) {
            val amp = sqrt(SysReverbEngine.nativeGetMeanSquare())
            level = ((20f * log10(amp + 1e-6f) + 60f) / 60f).coerceIn(0f, 1f)
            delay(33)
        }
        level = 0f
    }
    return { level }
}

/** Pita REVERB AUDIO untuk susunan LEBAR. Pemanggil memberi tinggi pasti (SYS_WIDE_HEIGHT_DP). */
@Composable
fun SysReverbWide(opacity: Float, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current.applicationContext
    remember { SysReverbUi.ensureLoaded(c); true }
    val s = SysReverbUi.s
    val running by SysReverbService.running.collectAsState()
    val level = rememberSysLevel(running)
    MixSection("REVERB AUDIO", running, onToggle, opacity, modifier) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SysKnobCard(
                "REVERB", s.reverbOn, { SysReverbUi.update(c) { it.copy(reverbOn = !it.reverbOn) } },
                !(running && s.reverbOn), reverbKnobs(c, s), 6, opacity, Modifier.weight(6.5f).fillMaxHeight()
            )
            SysKnobCard(
                "DELAY", s.echoOn, { SysReverbUi.update(c) { it.copy(echoOn = !it.echoOn) } },
                !(running && s.echoOn), delayKnobs(c, s), 3, opacity, Modifier.weight(3.5f).fillMaxHeight()
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SysKnobCard(
                "KIRIM KE REVERB", false, null, !running, reverbSendKnobs(c, s), 4, opacity,
                Modifier.weight(4f).fillMaxHeight()
            )
            SysKnobCard(
                "KIRIM KE DELAY", false, null, !running, echoSendKnobs(c, s), 4, opacity,
                Modifier.weight(4f).fillMaxHeight()
            )
            SysOutputCard(c, s, !running, level, opacity, Modifier.weight(2.5f).fillMaxHeight())
        }
    }
}

/** Bagian REVERB AUDIO untuk susunan TEGAK (floating): kartu bertumpuk, tinggi mengikuti isi. */
@Composable
fun SysReverbNarrow(opacity: Float, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current.applicationContext
    remember { SysReverbUi.ensureLoaded(c); true }
    val s = SysReverbUi.s
    val running by SysReverbService.running.collectAsState()
    val level = rememberSysLevel(running)
    MixSection("REVERB AUDIO", running, onToggle, opacity, modifier, fill = false) {
        SysKnobCard(
            "REVERB", s.reverbOn, { SysReverbUi.update(c) { it.copy(reverbOn = !it.reverbOn) } },
            !(running && s.reverbOn), reverbKnobs(c, s), 3, opacity, Modifier.fillMaxWidth().height(cardHeight(2))
        )
        SysKnobCard(
            "DELAY", s.echoOn, { SysReverbUi.update(c) { it.copy(echoOn = !it.echoOn) } },
            !(running && s.echoOn), delayKnobs(c, s), 3, opacity, Modifier.fillMaxWidth().height(cardHeight(1))
        )
        SysKnobCard(
            "KIRIM KE REVERB", false, null, !running, reverbSendKnobs(c, s), 4, opacity,
            Modifier.fillMaxWidth().height(cardHeight(1))
        )
        SysKnobCard(
            "KIRIM KE DELAY", false, null, !running, echoSendKnobs(c, s), 4, opacity,
            Modifier.fillMaxWidth().height(cardHeight(1))
        )
        SysOutputCard(c, s, !running, level, opacity, Modifier.fillMaxWidth().height(cardHeight(1)))
    }
}
