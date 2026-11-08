package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------------------------------
// EQUALIZER 31 BAND (1/3 oktaf ISO, 20 Hz - 20 kHz), KIRI (L) dan KANAN (R) MASING-MASING 31 band + knob GAIN per sisi.
// Bekerja di suara SEMUA app (sama jalurnya dengan EQ AUDIO: DynamicsProcessing di audio session 0, lihat GlobalEqEngine),
// BUKAN di jalur mic. Nilai berkunci NAMA FUNCTION (eq31.l.01 .. eq31.l.31, eq31.r.01 .. eq31.r.31, eq31.l.gain, eq31.r.gain)
// -> sama untuk panel klasik, skin equalizer, dan panel floating.
//
// "Mode equalizer" (eq31.mode) = bagian EQUALIZER tampil menyatu DI BAWAH mixer (digulir ke bawah). Tampil/tidak itu terpisah
// dari saklar eq31.toggle (efek audio ON/OFF), tetapi menyalakan mode ikut menyalakan efeknya.
// ---------------------------------------------------------------------------------------------------------------------
object Eq31 {
    const val BANDS = 31
    const val LEFT = 0
    const val RIGHT = 1

    val CENTERS: FloatArray = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f, 800f,
        1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
    )
    val LABELS: Array<String> = arrayOf(
        "20", "25", "31", "40", "50", "63", "80", "100", "125", "160", "200", "250", "315", "400", "500", "630", "800",
        "1k", "1.2k", "1.6k", "2k", "2.5k", "3.1k", "4k", "5k", "6.3k", "8k", "10k", "12k", "16k", "20k"
    )

    /** Batas ATAS tiap band (Hz) untuk DynamicsProcessing: rata-rata geometrik dua pusat band bertetangga; band terakhir sampai 20 kHz. */
    val CUTOFFS_HZ: FloatArray = FloatArray(BANDS) { i ->
        if (i == BANDS - 1) 20000f else sqrt(CENTERS[i] * CENTERS[i + 1])
    }

    /** Knob 0..1 -> dB. 0.5 = tengah = 0 dB (flat). */
    const val RANGE_DB = 12f
    fun knobToDb(knob: Float): Float = (knob - 0.5f) * 2f * RANGE_DB
    fun dbToKnob(db: Float): Float = db / (2f * RANGE_DB) + 0.5f

    fun fn(side: Int, band: Int): String = FN_BAND[side][band]
    fun gainFn(side: Int): String = FN_GAIN[side]

    private val FN_BAND: Array<Array<String>> = Array(2) { side ->
        Array(BANDS) { i -> "eq31.${if (side == LEFT) "l" else "r"}.%02d".format(i + 1) }
    }
    private val FN_GAIN: Array<String> = arrayOf("eq31.l.gain", "eq31.r.gain")

    /** Alamat satu kontrol: side = LEFT/RIGHT, band = 0..30, atau -1 untuk knob GAIN. */
    class Addr(val side: Int, val band: Int)

    private val ADDR: Map<String, Addr> = buildMap {
        for (side in 0..1) {
            for (i in 0 until BANDS) put(FN_BAND[side][i], Addr(side, i))
            put(FN_GAIN[side], Addr(side, -1))
        }
    }

    fun parse(function: String): Addr? = ADDR[function]

    /** Semua function nilai (62 band + 2 gain) - dipakai FunctionRegistry. */
    val VALUE_FUNCTIONS: Set<String> = ADDR.keys

    const val TOGGLE = "eq31.toggle"   // efek ON/OFF
    const val MODE = "eq31.mode"       // tampilkan bagian EQUALIZER di bawah mixer
    const val RESET = "eq31.reset"     // tombol: semua band & gain kembali flat

    /** Kelompok lebar (kompatibel dengan EQ AUDIO 4 band): 0 Sub (<90 Hz), 1 Low (<400 Hz), 2 Mid (<4 kHz), 3 High. */
    fun groupOf(band: Int): Int {
        val c = CENTERS[band]
        return when {
            c < 90f -> 0
            c < 400f -> 1
            c < 4000f -> 2
            else -> 3
        }
    }
}

data class Eq31Settings(
    val left: List<Float> = List(Eq31.BANDS) { 0.5f },
    val right: List<Float> = List(Eq31.BANDS) { 0.5f },
    val gainL: Float = 0.5f,
    val gainR: Float = 0.5f,
    /** Efek audio nyala (butuh GlobalEqService). Default OFF. */
    val enabled: Boolean = false,
    /** Bagian EQUALIZER tampil di bawah mixer. */
    val mode: Boolean = false,
) {
    fun band(side: Int, i: Int): Float = (if (side == Eq31.LEFT) left else right)[i]
    fun gain(side: Int): Float = if (side == Eq31.LEFT) gainL else gainR
}

object Eq31Store {
    private const val PREFS = "eq31_settings"

    private fun parseList(s: String?): List<Float> {
        val parts = s?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()
        return if (parts.size == Eq31.BANDS) parts.map { it.coerceIn(0f, 1f) } else List(Eq31.BANDS) { 0.5f }
    }

    fun load(context: Context): Eq31Settings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Eq31Settings(
            left = parseList(p.getString("left", null)),
            right = parseList(p.getString("right", null)),
            gainL = p.getFloat("gainL", 0.5f).coerceIn(0f, 1f),
            gainR = p.getFloat("gainR", 0.5f).coerceIn(0f, 1f),
            enabled = p.getBoolean("enabled", false),
            mode = p.getBoolean("mode", false),
        )
    }

    fun save(context: Context, s: Eq31Settings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("left", s.left.joinToString(","))
            .putString("right", s.right.joinToString(","))
            .putFloat("gainL", s.gainL)
            .putFloat("gainR", s.gainR)
            .putBoolean("enabled", s.enabled)
            .putBoolean("mode", s.mode)
            .apply()
    }
}

/**
 * Keadaan EQ 31 band yang dipakai BERSAMA oleh layar utama dan panel floating (satu proses). Berupa state Compose:
 * panel klasik membacanya langsung, skin disalin ke SkinUiState oleh MixerBridge.syncEq31 (lewat snapshotFlow di SkinBinding).
 */
object Eq31Ui {
    var s: Eq31Settings by mutableStateOf(Eq31Settings())
        private set
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (!loaded) {
            s = Eq31Store.load(context.applicationContext)
            loaded = true
        }
    }

    /** Terapkan perubahan: simpan, kirim ke efek yang sedang jalan. */
    fun update(context: Context, f: (Eq31Settings) -> Eq31Settings) {
        ensureLoaded(context)
        val n = f(s)
        s = n
        Eq31Store.save(context, n)
        GlobalEqEngine.refresh()
    }

    private fun set(list: List<Float>, i: Int, v: Float): List<Float> = list.toMutableList().also { it[i] = v }

    fun setBand(context: Context, side: Int, band: Int, v: Float) {
        val x = v.coerceIn(0f, 1f)
        update(context) { if (side == Eq31.LEFT) it.copy(left = set(it.left, band, x)) else it.copy(right = set(it.right, band, x)) }
    }

    fun setGain(context: Context, side: Int, v: Float) {
        val x = v.coerceIn(0f, 1f)
        update(context) { if (side == Eq31.LEFT) it.copy(gainL = x) else it.copy(gainR = x) }
    }

    /** Efek audio ON/OFF (menyalakan/mematikan service EQ global sesuai kebutuhan). */
    fun setEnabled(context: Context, on: Boolean) {
        update(context) { it.copy(enabled = on) }
        syncGlobalEqService(context)
    }

    /** Mode equalizer. Dinyalakan -> efek ikut dinyalakan. Dimatikan -> hanya menyembunyikan bagian EQUALIZER (efek tetap sesuai saklarnya). */
    fun setMode(context: Context, on: Boolean) {
        ensureLoaded(context)
        val enableNow = on && !s.enabled
        update(context) { it.copy(mode = on, enabled = it.enabled || on) }
        if (enableNow) syncGlobalEqService(context)
    }

    /** Semua band + gain kembali flat (0 dB). Status ON/OFF dan mode tidak berubah. */
    fun resetFlat(context: Context) {
        update(context) { it.copy(left = List(Eq31.BANDS) { 0.5f }, right = List(Eq31.BANDS) { 0.5f }, gainL = 0.5f, gainR = 0.5f) }
    }

    /** Dipanggil GlobalEqService bila efek gagal dibuat (HP tidak mendukung) -> saklar kembali OFF. */
    fun forceDisabled(context: Context) {
        update(context) { it.copy(enabled = false) }
    }
}
