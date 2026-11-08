package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.exp
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------------------------
// KOMPRESOR (gaya Poweramp): Attack, Release, Rasio, Frekuensi, Threshold, Gain + Keseimbangan (fokus L/R) + Volume.
// Bekerja di suara SEMUA app lewat efek yang SAMA dengan EQ (DynamicsProcessing di audio session 0, lihat GlobalEqEngine):
//   - Attack/Release/Rasio/Threshold/Gain -> band MBC (multiband compressor) di bawah Frekuensi
//   - Frekuensi = batas ATAS band yang dikompresi; di atasnya lewat tanpa kompresi. Knob penuh = seluruh frekuensi.
//   - Keseimbangan + Volume -> post-gain limiter per sisi (hanya meredam, tidak pernah menaikkan)
// Nilai berkunci NAMA FUNCTION (comp.*) -> sama untuk panel klasik, skin kompresor, dan panel floating.
// "Mode kompresor" (comp.mode) = bagian KOMPRESOR tampil menyatu DI ATAS equalizer (di bawah mixer). Terpisah dari saklar
// comp.toggle (efek ON/OFF), tetapi menyalakan mode ikut menyalakan efeknya.
// ---------------------------------------------------------------------------------------------------------------------
object Comp {
    const val ATTACK = "comp.attack"
    const val RELEASE = "comp.release"
    const val RATIO = "comp.ratio"
    const val FREQ = "comp.freq"
    const val THRESHOLD = "comp.threshold"
    const val GAIN = "comp.gain"
    const val BALANCE = "comp.balance"
    const val VOLUME = "comp.volume"

    const val TOGGLE = "comp.toggle"   // efek ON/OFF
    const val MODE = "comp.mode"       // tampilkan bagian KOMPRESOR
    const val RESET = "comp.reset"     // tombol: semua kembali ke nilai awal

    /** Urutan = urutan knob di panel klasik (3 x 2 lalu Keseimbangan dan Volume). */
    val VALUE_FUNCTIONS: Set<String> = linkedSetOf(ATTACK, RELEASE, RATIO, FREQ, THRESHOLD, GAIN, BALANCE, VOLUME)

    // Rentang nyata tiap knob
    const val ATTACK_MIN_MS = 0.1f
    const val ATTACK_MAX_MS = 200f
    const val RELEASE_MIN_MS = 1f
    const val RELEASE_MAX_MS = 2000f
    const val RATIO_MIN = 1f
    const val RATIO_MAX = 20f
    const val FREQ_MIN_HZ = 20f
    const val FREQ_MAX_HZ = 19000f
    const val THRESHOLD_MIN_DB = -80f
    const val THRESHOLD_MAX_DB = 0f
    const val GAIN_MAX_DB = 40f

    private fun logMap(k: Float, lo: Float, hi: Float): Float = lo * exp(ln(hi / lo) * k.coerceIn(0f, 1f))
    private fun logInv(v: Float, lo: Float, hi: Float): Float = (ln(v.coerceIn(lo, hi) / lo) / ln(hi / lo)).coerceIn(0f, 1f)

    fun attackMs(k: Float) = logMap(k, ATTACK_MIN_MS, ATTACK_MAX_MS)
    fun releaseMs(k: Float) = logMap(k, RELEASE_MIN_MS, RELEASE_MAX_MS)
    fun ratio(k: Float) = RATIO_MIN + (RATIO_MAX - RATIO_MIN) * k.coerceIn(0f, 1f)
    fun freqHz(k: Float) = logMap(k, FREQ_MIN_HZ, FREQ_MAX_HZ)
    fun thresholdDb(k: Float) = THRESHOLD_MIN_DB + (THRESHOLD_MAX_DB - THRESHOLD_MIN_DB) * k.coerceIn(0f, 1f)
    fun gainDb(k: Float) = GAIN_MAX_DB * k.coerceIn(0f, 1f)
    /** -1 (penuh kiri) .. 0 .. +1 (penuh kanan). */
    fun balance(k: Float) = (k.coerceIn(0f, 1f) - 0.5f) * 2f
    fun volumePct(k: Float) = (k.coerceIn(0f, 1f) * 100f).roundToInt()

    /** Peredaman (dB, <= 0) untuk sisi kiri/kanan dari Keseimbangan + Volume. */
    fun sideAttenDb(s: CompSettings, side: Int): Float {
        val b = balance(s.balance)
        val other = if (side == Eq31.LEFT) b else -b      // sisi yang berlawanan dengan arah fokus diredam
        val balDb = if (other <= 0f) 0f else (20f * log10((1f - other).coerceAtLeast(0.001f)))
        val volDb = 20f * log10(s.volume.coerceAtLeast(0.001f))
        return (balDb + volDb).coerceIn(-60f, 0f)
    }

    // Nilai awal (knob 0..1)
    val DEFAULTS: Map<String, Float> by lazy {
        mapOf(
            ATTACK to logInv(10f, ATTACK_MIN_MS, ATTACK_MAX_MS),
            RELEASE to logInv(100f, RELEASE_MIN_MS, RELEASE_MAX_MS),
            RATIO to (2f - RATIO_MIN) / (RATIO_MAX - RATIO_MIN),
            FREQ to 1f,
            THRESHOLD to (-20f - THRESHOLD_MIN_DB) / (THRESHOLD_MAX_DB - THRESHOLD_MIN_DB),
            GAIN to 0f,
            BALANCE to 0.5f,
            VOLUME to 1f,
        )
    }

    private fun f1(v: Float): String = String.format(Locale("id", "ID"), "%.1f", v)

    /** Teks nilai gaya Poweramp (koma desimal, satuan "mdtk"). */
    fun display(fn: String, k: Float): String = when (fn) {
        ATTACK -> f1(attackMs(k)) + "mdtk"
        RELEASE -> f1(releaseMs(k)) + "mdtk"
        RATIO -> f1(ratio(k))
        FREQ -> freqHz(k).roundToInt().toString()
        THRESHOLD -> f1(thresholdDb(k)) + "dB"
        GAIN -> f1(gainDb(k)) + "dB"
        BALANCE -> {
            val b = balance(k)
            val t = String.format(Locale("id", "ID"), "%.2f", abs(b))
            if (abs(b) < 0.005f) "0,00" else if (b < 0) "L $t" else "R $t"
        }
        VOLUME -> volumePct(k).toString() + "%"
        else -> ""
    }

    val LABELS: Map<String, String> = mapOf(
        ATTACK to "Attack", RELEASE to "Release", RATIO to "Rasio", FREQ to "Frekuensi",
        THRESHOLD to "Threshold", GAIN to "Gain", BALANCE to "Keseimbangan", VOLUME to "Volume"
    )
}

data class CompSettings(
    val attack: Float = Comp.DEFAULTS.getValue(Comp.ATTACK),
    val release: Float = Comp.DEFAULTS.getValue(Comp.RELEASE),
    val ratio: Float = Comp.DEFAULTS.getValue(Comp.RATIO),
    val freq: Float = Comp.DEFAULTS.getValue(Comp.FREQ),
    val threshold: Float = Comp.DEFAULTS.getValue(Comp.THRESHOLD),
    val gain: Float = Comp.DEFAULTS.getValue(Comp.GAIN),
    val balance: Float = Comp.DEFAULTS.getValue(Comp.BALANCE),
    val volume: Float = Comp.DEFAULTS.getValue(Comp.VOLUME),
    /** Efek audio nyala (butuh GlobalEqService). Default OFF. */
    val enabled: Boolean = false,
    /** Bagian KOMPRESOR tampil di atas equalizer. */
    val mode: Boolean = false,
) {
    fun get(fn: String): Float = when (fn) {
        Comp.ATTACK -> attack; Comp.RELEASE -> release; Comp.RATIO -> ratio; Comp.FREQ -> freq
        Comp.THRESHOLD -> threshold; Comp.GAIN -> gain; Comp.BALANCE -> balance; Comp.VOLUME -> volume
        else -> 0.5f
    }

    fun with(fn: String, v: Float): CompSettings = when (fn) {
        Comp.ATTACK -> copy(attack = v); Comp.RELEASE -> copy(release = v); Comp.RATIO -> copy(ratio = v)
        Comp.FREQ -> copy(freq = v); Comp.THRESHOLD -> copy(threshold = v); Comp.GAIN -> copy(gain = v)
        Comp.BALANCE -> copy(balance = v); Comp.VOLUME -> copy(volume = v)
        else -> this
    }
}

object CompStore {
    private const val PREFS = "comp_settings"

    fun load(context: Context): CompSettings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var s = CompSettings(enabled = p.getBoolean("enabled", false), mode = p.getBoolean("mode", false))
        for (fn in Comp.VALUE_FUNCTIONS) s = s.with(fn, p.getFloat(fn, Comp.DEFAULTS.getValue(fn)).coerceIn(0f, 1f))
        return s
    }

    fun save(context: Context, s: CompSettings) {
        val e = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (fn in Comp.VALUE_FUNCTIONS) e.putFloat(fn, s.get(fn))
        e.putBoolean("enabled", s.enabled).putBoolean("mode", s.mode).apply()
    }
}

/** Keadaan kompresor yang dipakai BERSAMA oleh layar utama dan panel floating (satu proses), state Compose. */
object CompUi {
    var s: CompSettings by mutableStateOf(CompSettings())
        private set
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (!loaded) {
            s = CompStore.load(context.applicationContext)
            loaded = true
        }
    }

    /** Simpan + kirim ke efek yang sedang jalan. */
    fun update(context: Context, f: (CompSettings) -> CompSettings) {
        ensureLoaded(context)
        val n = f(s)
        s = n
        CompStore.save(context, n)
        GlobalEqEngine.refresh()
    }

    fun setValue(context: Context, fn: String, v: Float) {
        val x = v.coerceIn(0f, 1f)
        update(context) { it.with(fn, x) }
    }

    fun setEnabled(context: Context, on: Boolean) {
        update(context) { it.copy(enabled = on) }
        syncGlobalEqService(context)
    }

    /** Mode dinyalakan -> efek ikut dinyalakan. Dimatikan -> hanya menyembunyikan bagian KOMPRESOR. */
    fun setMode(context: Context, on: Boolean) {
        ensureLoaded(context)
        val enableNow = on && !s.enabled
        update(context) { it.copy(mode = on, enabled = it.enabled || on) }
        if (enableNow) syncGlobalEqService(context)
    }

    /** Semua knob kembali ke nilai awal. Status ON/OFF dan mode tidak berubah. */
    fun resetAll(context: Context) {
        update(context) { cur ->
            var n = cur
            for (fn in Comp.VALUE_FUNCTIONS) n = n.with(fn, Comp.DEFAULTS.getValue(fn))
            n
        }
    }

    /** Dipanggil GlobalEqService bila efek gagal dibuat -> saklar kembali OFF. */
    fun forceDisabled(context: Context) {
        update(context) { it.copy(enabled = false) }
    }
}
