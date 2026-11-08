package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------------------------------
// FX (fitur gaya taGunner yang belum ada di MIXDTX). Semuanya bekerja di suara SEMUA app lewat efek global yang SAMA dengan
// EQ / kompresor (GlobalEqEngine, audio session 0):
//   - SUBSONIC     : memotong bass terendah (di bawah frekuensi potong, 12 dB/oktaf) -> pre-EQ 31 band
//   - SUB BASS     : dorongan bass 40 Hz (0..+12 dB) -> pre-EQ 31 band
//   - WIDENER      : Virtualizer (mode headphone = Super Stereo, mode speaker = Surround)
//   - TUBE DRIVE   : LoudnessEnhancer (hangat/padat ala tabung)
//   - 8D PAN       : suara berputar kiri-kanan (depth + kecepatan) lewat gain masuk per sisi
//   - LIMITER      : saklar limiter pengaman (default ON seperti sebelumnya)
//   - MBC 10 BAND  : kompresor dibagi 10 band (parameter kompresor yang sama dipakai tiap band)
//   - PARAMETRIC   : sampai 8 titik (Hz / dB / Q) -> post-EQ 64 band
// "Master" FX (fx.enabled) memegang semua efek di atas. Nilai awal semua efek OFF.
// ---------------------------------------------------------------------------------------------------------------------
object Fx {
    const val MAX_POINTS = 8
    const val PEQ_BANDS = 64
    const val PEQ_MAX_DB = 20f
    const val Q_MIN = 0.3f
    const val Q_MAX = 8f
    const val HZ_MIN = 20f
    const val HZ_MAX = 20000f

    // knob 0..1 -> nilai nyata
    fun subsonicHz(k: Float) = 15f + 45f * k.coerceIn(0f, 1f)
    fun subBassDb(k: Float) = 12f * k.coerceIn(0f, 1f)
    fun tubeDb(k: Float) = 12f * k.coerceIn(0f, 1f)
    fun widenerStrength(k: Float) = (k.coerceIn(0f, 1f) * 1000f).roundToInt()
    fun panDepthDb(k: Float) = 30f * k.coerceIn(0f, 1f)
    fun panPeriodSec(k: Float) = 20f - 18f * k.coerceIn(0f, 1f)

    private fun f0(v: Float) = String.format(Locale("id", "ID"), "%.0f", v)
    private fun f1(v: Float) = String.format(Locale("id", "ID"), "%.1f", v)
    fun subsonicText(k: Float) = f0(subsonicHz(k)) + " Hz"
    fun dbText(db: Float) = f1(db) + " dB"
    fun widenerText(k: Float) = f0(k * 100f) + "%"
    fun panPeriodText(k: Float) = f1(panPeriodSec(k)) + " dtk"

    // Titik parametrik: knob 0..1 <-> nilai nyata
    fun hzOf(k: Float): Float = HZ_MIN * (HZ_MAX / HZ_MIN).pow(k.coerceIn(0f, 1f))
    fun hzToKnob(hz: Float): Float = (ln(hz.coerceIn(HZ_MIN, HZ_MAX) / HZ_MIN) / ln(HZ_MAX / HZ_MIN)).coerceIn(0f, 1f)
    fun dbOf(k: Float): Float = (k.coerceIn(0f, 1f) - 0.5f) * 2f * PEQ_MAX_DB
    fun dbToKnob(db: Float): Float = (db / (2f * PEQ_MAX_DB) + 0.5f).coerceIn(0f, 1f)
    fun qOf(k: Float): Float = Q_MIN * (Q_MAX / Q_MIN).pow(k.coerceIn(0f, 1f))
    fun qToKnob(q: Float): Float = (ln(q.coerceIn(Q_MIN, Q_MAX) / Q_MIN) / ln(Q_MAX / Q_MIN)).coerceIn(0f, 1f)
    fun hzText(hz: Float): String = if (hz >= 1000f) f1(hz / 1000f) + " kHz" else f0(hz) + " Hz"

    /** 64 pusat band post-EQ (20 Hz .. 20 kHz, log). */
    val PEQ_CENTERS: FloatArray = FloatArray(PEQ_BANDS) { i -> HZ_MIN * (HZ_MAX / HZ_MIN).pow(i / (PEQ_BANDS - 1f)) }

    /** Batas ATAS tiap band post-EQ (rata-rata geometrik pusat bertetangga; band terakhir 20 kHz). */
    val PEQ_CUTOFFS: FloatArray = FloatArray(PEQ_BANDS) { i ->
        if (i == PEQ_BANDS - 1) 20000f else sqrt(PEQ_CENTERS[i] * PEQ_CENTERS[i + 1])
    }

    /** Respons satu titik di frekuensi f (dB): lonceng Gaussian di skala log; lebar dari Q (BW oktaf ~ 1,44 / Q). */
    fun bellDb(p: PeqPoint, f: Float): Float {
        val bwOct = 1.4427f / p.q.coerceAtLeast(0.1f)
        val sigmaLn = (bwOct * 0.6931f / 2.355f).coerceAtLeast(0.01f)
        val d = ln(f / p.hz) / sigmaLn
        return p.db * exp(-0.5f * d * d)
    }

    fun peqDb(points: List<PeqPoint>, f: Float): Float {
        var t = 0f
        for (p in points) t += bellDb(p, f)
        return t
    }

    /** Gain tiap band post-EQ (dB): rata-rata 5 contoh di dalam band supaya titik sempit tetap terasa. */
    fun peqBandDb(points: List<PeqPoint>): FloatArray = FloatArray(PEQ_BANDS) { i ->
        val lo = if (i == 0) HZ_MIN else PEQ_CUTOFFS[i - 1]
        val hi = PEQ_CUTOFFS[i]
        var sum = 0f
        for (k in 0 until 5) {
            val fr = lo * (hi / lo).pow((k + 0.5f) / 5f)
            sum += peqDb(points, fr)
        }
        (sum / 5f).coerceIn(-PEQ_MAX_DB, PEQ_MAX_DB)
    }

    /** Tambahan dB per band pre-EQ 31 band dari SUBSONIC + SUB BASS (sama untuk L dan R). */
    fun preBandDb(s: FxSettings, band: Int): Float {
        if (!s.enabled) return 0f
        val c = Eq31.CENTERS[band]
        var db = 0f
        if (s.subsonicOn) {
            val hz = subsonicHz(s.subsonic)
            if (c < hz) db -= minOf(20f, 12f * (ln(hz / c) / 0.6931f))
        }
        if (s.subBassOn) {
            val d = ln(c / 40f) / 0.5f
            db += subBassDb(s.subBass) * exp(-0.5f * d * d)
        }
        return db
    }
}

data class PeqPoint(val hz: Float, val db: Float, val q: Float)

data class FxSettings(
    /** Master FX (butuh GlobalEqService). Default OFF. */
    val enabled: Boolean = false,
    /** Bagian FX tampil di bawah kompresor. */
    val mode: Boolean = false,
    val subsonicOn: Boolean = false, val subsonic: Float = 0.3f,
    val subBassOn: Boolean = false, val subBass: Float = 0.4f,
    val widenerOn: Boolean = false, val widener: Float = 0.5f, val speaker: Boolean = false,
    val tubeOn: Boolean = false, val tube: Float = 0.3f,
    val panOn: Boolean = false, val panDepth: Float = 0.7f, val panCycle: Float = 0.4f,
    val limiterOn: Boolean = true,
    val mbc10: Boolean = false,
    val peqOn: Boolean = true,
    val points: List<PeqPoint> = emptyList(),
)

object FxStore {
    private const val PREFS = "fx_settings"

    fun encodePoints(list: List<PeqPoint>): String =
        list.joinToString(";") { "%.2f,%.2f,%.3f".format(Locale.US, it.hz, it.db, it.q) }

    fun decodePoints(s: String?): List<PeqPoint> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(';').mapNotNull { part ->
            val v = part.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (v.size == 3) PeqPoint(
                v[0].coerceIn(Fx.HZ_MIN, Fx.HZ_MAX), v[1].coerceIn(-Fx.PEQ_MAX_DB, Fx.PEQ_MAX_DB), v[2].coerceIn(Fx.Q_MIN, Fx.Q_MAX)
            ) else null
        }.take(Fx.MAX_POINTS)
    }

    fun load(context: Context): FxSettings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = FxSettings()
        return FxSettings(
            enabled = p.getBoolean("enabled", d.enabled), mode = p.getBoolean("mode", d.mode),
            subsonicOn = p.getBoolean("subsonicOn", d.subsonicOn), subsonic = p.getFloat("subsonic", d.subsonic).coerceIn(0f, 1f),
            subBassOn = p.getBoolean("subBassOn", d.subBassOn), subBass = p.getFloat("subBass", d.subBass).coerceIn(0f, 1f),
            widenerOn = p.getBoolean("widenerOn", d.widenerOn), widener = p.getFloat("widener", d.widener).coerceIn(0f, 1f),
            speaker = p.getBoolean("speaker", d.speaker),
            tubeOn = p.getBoolean("tubeOn", d.tubeOn), tube = p.getFloat("tube", d.tube).coerceIn(0f, 1f),
            panOn = p.getBoolean("panOn", d.panOn), panDepth = p.getFloat("panDepth", d.panDepth).coerceIn(0f, 1f),
            panCycle = p.getFloat("panCycle", d.panCycle).coerceIn(0f, 1f),
            limiterOn = p.getBoolean("limiterOn", d.limiterOn), mbc10 = p.getBoolean("mbc10", d.mbc10),
            peqOn = p.getBoolean("peqOn", d.peqOn), points = decodePoints(p.getString("points", null)),
        )
    }

    fun save(context: Context, s: FxSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled).putBoolean("mode", s.mode)
            .putBoolean("subsonicOn", s.subsonicOn).putFloat("subsonic", s.subsonic)
            .putBoolean("subBassOn", s.subBassOn).putFloat("subBass", s.subBass)
            .putBoolean("widenerOn", s.widenerOn).putFloat("widener", s.widener).putBoolean("speaker", s.speaker)
            .putBoolean("tubeOn", s.tubeOn).putFloat("tube", s.tube)
            .putBoolean("panOn", s.panOn).putFloat("panDepth", s.panDepth).putFloat("panCycle", s.panCycle)
            .putBoolean("limiterOn", s.limiterOn).putBoolean("mbc10", s.mbc10)
            .putBoolean("peqOn", s.peqOn).putString("points", encodePoints(s.points))
            .apply()
    }
}

/** Keadaan FX yang dipakai BERSAMA oleh layar utama dan panel floating (satu proses), state Compose. */
object FxUi {
    var s: FxSettings by mutableStateOf(FxSettings())
        private set
    /** Titik parametrik yang sedang dipilih (tidak disimpan). */
    var selected: Int by mutableIntStateOf(-1)
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (!loaded) {
            s = FxStore.load(context.applicationContext)
            loaded = true
        }
    }

    fun update(context: Context, f: (FxSettings) -> FxSettings) {
        ensureLoaded(context)
        val n = f(s)
        s = n
        FxStore.save(context, n)
        GlobalEqEngine.refresh()
    }

    fun setEnabled(context: Context, on: Boolean) {
        update(context) { it.copy(enabled = on) }
        syncGlobalEqService(context)
    }

    /** Mode dinyalakan -> master ikut dinyalakan. Dimatikan -> hanya menyembunyikan bagian FX. */
    fun setMode(context: Context, on: Boolean) {
        ensureLoaded(context)
        val enableNow = on && !s.enabled
        update(context) { it.copy(mode = on, enabled = it.enabled || on) }
        if (enableNow) syncGlobalEqService(context)
    }

    fun forceDisabled(context: Context) {
        update(context) { it.copy(enabled = false) }
    }

    fun addPoint(context: Context, hz: Float = 1000f) {
        ensureLoaded(context)
        if (s.points.size >= Fx.MAX_POINTS) return
        update(context) { it.copy(points = it.points + PeqPoint(hz, 0f, 1.4f)) }
        selected = s.points.size - 1
    }

    fun removeSelected(context: Context) {
        ensureLoaded(context)
        val i = selected
        if (i !in s.points.indices) return
        update(context) { it.copy(points = it.points.filterIndexed { idx, _ -> idx != i }) }
        selected = if (s.points.isEmpty()) -1 else (i - 1).coerceAtLeast(0)
    }

    fun setPoint(context: Context, i: Int, f: (PeqPoint) -> PeqPoint) {
        ensureLoaded(context)
        if (i !in s.points.indices) return
        update(context) { cur ->
            cur.copy(points = cur.points.mapIndexed { idx, p ->
                if (idx == i) f(p).let { PeqPoint(it.hz.coerceIn(Fx.HZ_MIN, Fx.HZ_MAX), it.db.coerceIn(-Fx.PEQ_MAX_DB, Fx.PEQ_MAX_DB), it.q.coerceIn(Fx.Q_MIN, Fx.Q_MAX)) } else p
            })
        }
    }

    fun clearPoints(context: Context) {
        update(context) { it.copy(points = emptyList()) }
        selected = -1
    }
}
