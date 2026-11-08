package com.kendang.realpads.skin

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.snapshotFlow
import com.kendang.realpads.AudioEngine
import com.kendang.realpads.Eq31
import com.kendang.realpads.Comp
import com.kendang.realpads.CompUi
import com.kendang.realpads.Eq31Ui
import com.kendang.realpads.FxUi
import com.kendang.realpads.GlobalEqEngine
import com.kendang.realpads.GlobalEqService
import com.kendang.realpads.MicStateBus
import com.kendang.realpads.MusicMeter
import com.kendang.realpads.ReverbSettings
import com.kendang.realpads.SettingsBus
import com.kendang.realpads.SettingsStore
import com.kendang.realpads.SysReverbService
import com.kendang.realpads.SysReverbSettings
import com.kendang.realpads.SysReverbUi
import com.kendang.realpads.SysReverbEngine
import com.kendang.realpads.eqKnobToDb
import com.kendang.realpads.pushAllSettingsToEngine
import com.kendang.realpads.startGlobalEq
import com.kendang.realpads.syncGlobalEqService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Jembatan skin -> mixer. Pola SAMA dengan KaraokeControls & panel floating: ubah ReverbSettings, kirim ke engine native,
 * simpan (SettingsStore), umumkan (SettingsBus / MicStateBus) supaya UI klasik & floating ikut sinkron.
 * Penerima bus hanya menyalin nilai ke [SkinUiState] (tidak menyimpan ulang -> tidak ada loop).
 */
class MixerBridge(private val context: Context, val ui: SkinUiState) : SkinActions {
    fun context(): Context = context

    var hasMicPermission: () -> Boolean = { false }
    var requestMicPermission: () -> Unit = {}
    var onFloating: () -> Unit = {}
    var onTheme: () -> Unit = {}
    var onSkinPick: () -> Unit = {}
    var onSysReverb: () -> Unit = {}

    private var cur: ReverbSettings = SettingsStore.load(context)
    private var micRunning: Boolean = AudioEngine.nativeIsRunning()

    private class V(val get: (ReverbSettings) -> Float, val set: (ReverbSettings, Float) -> ReverbSettings)

    private val values: Map<String, V> = mapOf(
        "mic.volume" to V({ it.micVol }, { s, v -> s.copy(micVol = v) }),
        "mic.gate" to V({ it.gate }, { s, v -> s.copy(gate = v) }),
        "reverb.wet" to V({ it.wet }, { s, v -> s.copy(wet = v) }),
        "reverb.dry" to V({ it.dry }, { s, v -> s.copy(dry = v) }),
        "reverb.width" to V({ it.width }, { s, v -> s.copy(width = v) }),
        "reverb.damp" to V({ it.damp }, { s, v -> s.copy(damp = v) }),
        "reverb.room" to V({ it.roomSize }, { s, v -> s.copy(roomSize = v) }),
        "reverb.cess" to V({ it.cess }, { s, v -> s.copy(cess = v) }),
        "echo.delay" to V({ it.echoDelay }, { s, v -> s.copy(echoDelay = v) }),
        "echo.feedback" to V({ it.echoFeedback }, { s, v -> s.copy(echoFeedback = v) }),
        "echo.mix" to V({ it.echoMix }, { s, v -> s.copy(echoMix = v) }),
        "eqmic.low" to V({ it.eqLow }, { s, v -> s.copy(eqLow = v) }),
        "eqmic.mid" to V({ it.eqMid }, { s, v -> s.copy(eqMid = v) }),
        "eqmic.high" to V({ it.eqHigh }, { s, v -> s.copy(eqHigh = v) }),
        "eqaudio.gain" to V({ it.gEqGain }, { s, v -> s.copy(gEqGain = v) }),
        "eqaudio.sub" to V({ it.gEqSub }, { s, v -> s.copy(gEqSub = v) }),
        "eqaudio.low" to V({ it.gEqLow }, { s, v -> s.copy(gEqLow = v) }),
        "eqaudio.mid" to V({ it.gEqMid }, { s, v -> s.copy(gEqMid = v) }),
        "eqaudio.high" to V({ it.gEqHigh }, { s, v -> s.copy(gEqHigh = v) }),
    )

    // REVERB AUDIO (SysReverbPanel.kt): nilainya disimpan di SysReverbUi, bukan di ReverbSettings.
    private class SV(val get: (SysReverbSettings) -> Float, val set: (SysReverbSettings, Float) -> SysReverbSettings)

    private fun band(list: List<Float>, i: Int, v: Float): List<Float> = list.toMutableList().also { it[i] = v }

    private val sysValues: Map<String, SV> = mapOf(
        "sysreverb.wet" to SV({ it.wet }, { s, v -> s.copy(wet = v) }),
        "sysreverb.room" to SV({ it.room }, { s, v -> s.copy(room = v) }),
        "sysreverb.damp" to SV({ it.damp }, { s, v -> s.copy(damp = v) }),
        "sysreverb.width" to SV({ it.width }, { s, v -> s.copy(width = v) }),
        "sysreverb.cess" to SV({ it.cess }, { s, v -> s.copy(cess = v) }),
        "sysreverb.predelay" to SV({ it.preDelay }, { s, v -> s.copy(preDelay = v) }),
        "sysreverb.delay" to SV({ it.echoDelay }, { s, v -> s.copy(echoDelay = v) }),
        "sysreverb.feedback" to SV({ it.echoFeedback }, { s, v -> s.copy(echoFeedback = v) }),
        "sysreverb.mix" to SV({ it.echoMix }, { s, v -> s.copy(echoMix = v) }),
        "sysreverb.gain" to SV({ it.gain }, { s, v -> s.copy(gain = v) }),
        "sysreverb.rsend.sub" to SV({ it.revSend[0] }, { s, v -> s.copy(revSend = band(s.revSend, 0, v)) }),
        "sysreverb.rsend.low" to SV({ it.revSend[1] }, { s, v -> s.copy(revSend = band(s.revSend, 1, v)) }),
        "sysreverb.rsend.mid" to SV({ it.revSend[2] }, { s, v -> s.copy(revSend = band(s.revSend, 2, v)) }),
        "sysreverb.rsend.high" to SV({ it.revSend[3] }, { s, v -> s.copy(revSend = band(s.revSend, 3, v)) }),
        "sysreverb.esend.sub" to SV({ it.echoSend[0] }, { s, v -> s.copy(echoSend = band(s.echoSend, 0, v)) }),
        "sysreverb.esend.low" to SV({ it.echoSend[1] }, { s, v -> s.copy(echoSend = band(s.echoSend, 1, v)) }),
        "sysreverb.esend.mid" to SV({ it.echoSend[2] }, { s, v -> s.copy(echoSend = band(s.echoSend, 2, v)) }),
        "sysreverb.esend.high" to SV({ it.echoSend[3] }, { s, v -> s.copy(echoSend = band(s.echoSend, 3, v)) }),
    )

    /** Salin nilai & saklar REVERB AUDIO ke state skin (dipanggil juga saat service menyala/mati). */
    fun syncSys() {
        SysReverbUi.ensureLoaded(context)
        val s = SysReverbUi.s
        for ((fn, v) in sysValues) {
            val nv = v.get(s)
            if (abs((ui.values[fn] ?: -1f) - nv) > 1e-4f) ui.values[fn] = nv
        }
        ui.toggles["sysreverb.toggle"] = SysReverbService.running.value
        ui.toggles["sysreverb.reverb.toggle"] = s.reverbOn
        ui.toggles["sysreverb.echo.toggle"] = s.echoOn
    }

    /** Salin nilai & saklar EQ 31 BAND ke state skin (juga dipanggil tiap Eq31Ui.s berubah, lihat SkinBinding). */
    fun syncEq31() {
        Eq31Ui.ensureLoaded(context)
        val e = Eq31Ui.s
        for (side in 0..1) {
            for (i in 0 until Eq31.BANDS) {
                val fn = Eq31.fn(side, i)
                val nv = e.band(side, i)
                if (abs((ui.values[fn] ?: -1f) - nv) > 1e-4f) ui.values[fn] = nv
            }
            val gfn = Eq31.gainFn(side)
            val gv = e.gain(side)
            if (abs((ui.values[gfn] ?: -1f) - gv) > 1e-4f) ui.values[gfn] = gv
        }
        if (ui.toggles[Eq31.TOGGLE] != e.enabled) ui.toggles[Eq31.TOGGLE] = e.enabled
        if (ui.toggles[Eq31.MODE] != e.mode) ui.toggles[Eq31.MODE] = e.mode
    }

    /** Salin nilai & saklar KOMPRESOR ke state skin (juga dipanggil tiap CompUi.s berubah, lihat SkinBinding). */
    fun syncComp() {
        CompUi.ensureLoaded(context)
        val c = CompUi.s
        for (fn in Comp.VALUE_FUNCTIONS) {
            val nv = c.get(fn)
            if (abs((ui.values[fn] ?: -1f) - nv) > 1e-4f) ui.values[fn] = nv
        }
        if (ui.toggles[Comp.TOGGLE] != c.enabled) ui.toggles[Comp.TOGGLE] = c.enabled
        if (ui.toggles[Comp.MODE] != c.mode) ui.toggles[Comp.MODE] = c.mode
    }

    init { syncFrom(cur) }   // nilai awal langsung tersedia (tanpa kedip 0 sebelum SkinBinding berjalan)

    private fun toggleValue(function: String, s: ReverbSettings): Boolean = when (function) {
        "mic.toggle" -> micRunning
        "reverb.toggle" -> s.reverbEnabled
        "echo.toggle" -> s.echoEnabled
        "eqmic.toggle" -> s.eqEnabled
        "eqaudio.toggle" -> s.gEqEnabled
        else -> false
    }

    // ---- aplikasi -> skin ----

    fun syncFrom(s: ReverbSettings) {
        cur = s
        for ((fn, v) in values) {
            val nv = v.get(s)
            if (abs((ui.values[fn] ?: -1f) - nv) > 1e-4f) ui.values[fn] = nv
        }
        for (fn in FunctionRegistry.toggleFunctions) if (!fn.startsWith("sysreverb.") && !fn.startsWith("eq31.") && !fn.startsWith("comp.")) ui.toggles[fn] = toggleValue(fn, s)
        syncSys()
        syncEq31()
        syncComp()
    }

    fun syncAll() {
        micRunning = AudioEngine.nativeIsRunning()
        syncFrom(SettingsStore.load(context))
    }

    fun setMicRunning(running: Boolean) {
        micRunning = running
        syncFrom(cur)
    }

    // ---- skin -> aplikasi ----

    override fun setValue(function: String, value: Float) {
        Eq31.parse(function)?.let { a ->
            val v = value.coerceIn(0f, 1f)
            if (a.band >= 0) Eq31Ui.setBand(context, a.side, a.band, v) else Eq31Ui.setGain(context, a.side, v)
            ui.values[function] = v
            return
        }
        if (function in Comp.VALUE_FUNCTIONS) {
            val v = value.coerceIn(0f, 1f)
            CompUi.setValue(context, function, v)
            ui.values[function] = v
            return
        }
        sysValues[function]?.let { sv ->
            val v = value.coerceIn(0f, 1f)
            SysReverbUi.update(context) { sv.set(it, v) }
            ui.values[function] = v
            return
        }
        val entry = values[function] ?: return
        val v = value.coerceIn(0f, 1f)
        val s = entry.set(cur, v)
        cur = s
        ui.values[function] = v
        pushValue(function, s)
        persist(s)
    }

    private fun pushValue(function: String, s: ReverbSettings) {
        when {
            function == "mic.volume" -> AudioEngine.nativeSetMicGain(s.micVol * 2f)
            function == "mic.gate" -> AudioEngine.nativeSetGate(s.gate)
            function == "reverb.cess" -> AudioEngine.nativeSetCess(s.cess)
            function.startsWith("reverb.") -> AudioEngine.nativeSetReverbParams(s.wet, s.dry, s.width, s.damp, s.roomSize)
            function.startsWith("echo.") -> AudioEngine.nativeSetEchoParams(50f + s.echoDelay * 550f, s.echoFeedback * 0.75f, s.echoMix)
            function.startsWith("eqmic.") -> AudioEngine.nativeSetEQParams(eqKnobToDb(s.eqLow), eqKnobToDb(s.eqMid), eqKnobToDb(s.eqHigh))
            function.startsWith("eqaudio.") -> GlobalEqEngine.applySettings(s)
        }
    }

    /** Kembalikan kumpulan knob/fader ke nilai awal (tombol RESET di layar utama). */
    fun resetValues(functions: List<String>) {
        val sysDefault = SysReverbSettings()
        for (fn in functions) {
            val sv = sysValues[fn]
            if (Eq31.parse(fn) != null) setValue(fn, 0.5f)
            else if (fn in Comp.VALUE_FUNCTIONS) setValue(fn, Comp.DEFAULTS.getValue(fn))
            else if (sv != null) setValue(fn, sv.get(sysDefault)) else DEFAULT_VALUES[fn]?.let { setValue(fn, it) }
        }
    }

    override fun setToggle(function: String, on: Boolean) {
        when (function) {
            "sysreverb.toggle" -> { onSysReverb(); return }
            "sysreverb.reverb.toggle" -> { SysReverbUi.update(context) { it.copy(reverbOn = on) }; syncSys(); return }
            "sysreverb.echo.toggle" -> { SysReverbUi.update(context) { it.copy(echoOn = on) }; syncSys(); return }
            Eq31.TOGGLE -> { Eq31Ui.setEnabled(context, on); syncEq31(); return }
            Eq31.MODE -> { Eq31Ui.setMode(context, on); syncEq31(); return }
            Comp.TOGGLE -> { CompUi.setEnabled(context, on); syncComp(); return }
            Comp.MODE -> { CompUi.setMode(context, on); syncComp(); return }
            "mic.toggle" -> toggleMic()
            "reverb.toggle" -> { cur = cur.copy(reverbEnabled = on); AudioEngine.nativeSetReverbEnabled(on); persist(cur) }
            "echo.toggle" -> { cur = cur.copy(echoEnabled = on); AudioEngine.nativeSetEchoEnabled(on); persist(cur) }
            "eqmic.toggle" -> { cur = cur.copy(eqEnabled = on); AudioEngine.nativeSetEQEnabled(on); persist(cur) }
            "eqaudio.toggle" -> {
                cur = cur.copy(gEqEnabled = on)
                persist(cur)   // simpan dulu: service membaca status ON dari SettingsStore
                GlobalEqEngine.applySettings(cur)   // ON/OFF EQ AUDIO ikut dijumlahkan ke efek yang sama dengan EQ 31 band
                if (on) startGlobalEq(context) else syncGlobalEqService(context)   // efek baru dilepas bila EQ 31 band juga OFF
            }
        }
        syncFrom(cur)
    }

    override fun press(function: String) {
        when (function) {
            "floating.toggle" -> onFloating()
            "theme.open" -> onTheme()
            "skin.pick" -> onSkinPick()
            Eq31.RESET -> { Eq31Ui.resetFlat(context); syncEq31() }
            Comp.RESET -> { CompUi.resetAll(context); syncComp() }
        }
    }

    /** Sama dengan toggleEngine() di KaraokeControls. Status saklar dikoreksi dari kenyataan engine. */
    private fun toggleMic() {
        if (!hasMicPermission()) {
            requestMicPermission()
            return
        }
        if (micRunning) {
            AudioEngine.nativeStop()
            micRunning = false
            MicStateBus.publish(false)
        } else if (AudioEngine.nativeStart()) {
            pushAllSettingsToEngine(cur)
            micRunning = true
            MicStateBus.publish(true)
        }
    }

    private fun persist(s: ReverbSettings) {
        SettingsStore.save(context, s)
        SettingsBus.publish(s)
    }
}

private val DEFAULT_VALUES: Map<String, Float> = mapOf(
    "mic.volume" to 0.5f, "mic.gate" to 0.5f,
    "reverb.wet" to 0.35f, "reverb.dry" to 0.85f, "reverb.width" to 1.0f,
    "reverb.damp" to 0.3f, "reverb.room" to 0.6f, "reverb.cess" to 0.6f,
    "echo.delay" to 0.45f, "echo.feedback" to 0.45f, "echo.mix" to 0.3f,
    "eqmic.low" to 0.5f, "eqmic.mid" to 0.5f, "eqmic.high" to 0.5f,
    "eqaudio.gain" to 0.5f, "eqaudio.sub" to 0.5f, "eqaudio.low" to 0.5f, "eqaudio.mid" to 0.5f, "eqaudio.high" to 0.5f,
)

private fun levelFromAmp(amp: Float): Float =
    ((20f * log10(amp + 1e-6f) + 60f) / 60f).coerceIn(0f, 1f)

/**
 * Pasang jembatan ke siklus hidup layar: sinkron awal, dengar SettingsBus/MicStateBus (UI klasik & floating),
 * dan polling meter ~30 fps hanya saat layar RESUMED. Hasil meter masuk ke ui.meters["meter.mic" / "meter.output"].
 */
@Composable
fun SkinBinding(bridge: MixerBridge, hasMicPermission: Boolean) {
    val context = bridge.context()
    val lifecycleOwner = LocalLifecycleOwner.current
    var active by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner, bridge) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { active = true; bridge.syncAll() }
            else if (event == Lifecycle.Event.ON_PAUSE) active = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(bridge) {
        bridge.syncAll()
        // EQ Audio tersimpan ON tapi proses sempat mati -> nyalakan lagi (sama dengan KaraokeControls).
        Eq31Ui.ensureLoaded(context)
        CompUi.ensureLoaded(context)
        FxUi.ensureLoaded(context)
        if ((SettingsStore.load(context).gEqEnabled || Eq31Ui.s.enabled || CompUi.s.enabled || FxUi.s.enabled) && !GlobalEqService.running.value) startGlobalEq(context)
    }
    // EQ 31 band berubah dari mana pun (panel klasik, skin, jendela lain) -> salin ke state skin ini.
    LaunchedEffect(bridge) { snapshotFlow { Eq31Ui.s }.collect { bridge.syncEq31() } }
    LaunchedEffect(bridge) { snapshotFlow { CompUi.s }.collect { bridge.syncComp() } }
    LaunchedEffect(bridge) { SettingsBus.updates.collect { bridge.syncFrom(it) } }
    LaunchedEffect(bridge) { MicStateBus.updates.collect { bridge.setMicRunning(it) } }
    LaunchedEffect(bridge) { SysReverbService.running.collect { bridge.syncSys() } }

    LaunchedEffect(bridge, active, hasMicPermission) {
        val meters = bridge.ui.meters
        if (!active) {
            meters["meter.mic"] = 0f
            meters["meter.output"] = 0f
            meters["meter.sysreverb"] = 0f
            return@LaunchedEffect
        }
        if (hasMicPermission) MusicMeter.acquire()
        try {
            while (true) {
                delay(33)
                val (micRms, outRms) = withContext(Dispatchers.Default) {
                    val running = AudioEngine.nativeIsRunning()
                    val mic = if (running) sqrt(AudioEngine.nativeGetMicMeanSquare()) else 0f
                    val engineOut = if (running) sqrt(AudioEngine.nativeGetOutputMeanSquare()) else 0f
                    // output = campuran akhir (musik + mic) bila Visualizer tersedia; kalau tidak, keluaran engine.
                    val mix = if (hasMicPermission) MusicMeter.readMixRms() else -1f
                    mic to (if (mix >= 0f) mix else engineOut)
                }
                val sysAmp = if (SysReverbService.running.value) withContext(Dispatchers.Default) { sqrt(SysReverbEngine.nativeGetMeanSquare()) } else 0f
                val nowSys = levelFromAmp(sysAmp)
                val oldSys = meters["meter.sysreverb"] ?: 0f
                meters["meter.sysreverb"] = if (nowSys > oldSys) nowSys else max(nowSys, oldSys - 0.09f)
                val nowMic = levelFromAmp(micRms)
                val nowOut = levelFromAmp(outRms)
                val oldMic = meters["meter.mic"] ?: 0f
                val oldOut = meters["meter.output"] ?: 0f
                // naik langsung, turun cepat -> lampu "mukul" mengikuti suara
                meters["meter.mic"] = if (nowMic > oldMic) nowMic else max(nowMic, oldMic - 0.09f)
                meters["meter.output"] = if (nowOut > oldOut) nowOut else max(nowOut, oldOut - 0.09f)
            }
        } finally {
            if (hasMicPermission) MusicMeter.release()
        }
    }
}
