package com.kendang.realpads

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.pow
import kotlinx.coroutines.flow.asStateFlow

// EQ GLOBAL: ngubah suara SEMUA app (YouTube, TikTok, app musik, dll).
//
// Caranya sama kayak Equalizer314 / Wavelet: pasang efek DynamicsProcessing
// (API resmi Android 9+) di audio session 0 = "output mix" - mixer akhir
// sebelum suara keluar ke speaker/earphone. Efeknya berlaku ke semua app
// tanpa app lain perlu ikut kerja sama.
//
// Isinya: 31 band PER SISI (L dan R terpisah, 1/3 oktaf ISO) + limiter tipis biar boost gak bikin suara
// pecah (clipping). Dua fitur memakai SATU efek ini dan dijumlahkan dalam dB per band:
//   - EQ AUDIO  (gain + Sub/Low/Mid/High)  -> nilainya disebar ke band yang masuk kelompoknya, sama untuk L dan R
//   - EQ 31 BAND (Eq31.kt, knob GAIN L/R + 31 band L + 31 band R) -> langsung per band per sisi
// Masing-masing hanya ikut bila saklarnya ON. Efek hanya dilepas bila keduanya OFF (lihat syncGlobalEqService).
//
// Catatan jujur: sebagian HP/ROM (terutama beberapa merek tertentu) mengabaikan
// efek di session 0. Kalau itu kejadian, efek gagal dibuat / gak kedengeran
// dan alasannya ditampilin lewat [error] ke UI - bukan crash.
object GlobalEqEngine {
    // Batas ATAS tiap band (Hz). DynamicsProcessing membagi spektrum jadi 31 band berurutan (1/3 oktaf) per sisi.
    private val CUTOFFS_HZ = Eq31.CUTOFFS_HZ
    private const val BAND_COUNT = Eq31.BANDS
    private const val CHANNELS = 2
    // Batas aman gain gabungan per band (dB): EQ AUDIO + EQ 31 band + gain sisi bisa menumpuk.
    private const val MAX_BAND_DB = 20f

    private var dp: DynamicsProcessing? = null

    // Pengaturan EQ AUDIO terakhir (untuk refresh() saat EQ 31 band berubah) + nilai yang SUDAH terkirim ke efek.
    // Hanya band yang berubah yang dikirim ulang -> menggeser satu slider = 1 panggilan, bukan 62.
    private var lastS: ReverbSettings? = null
    private val sent = Array(CHANNELS) { FloatArray(BAND_COUNT) { Float.NaN } }
    private val sentInGain = FloatArray(CHANNELS) { Float.NaN }
    private var baseInputGainDb = 0f
    // FX (FxUi): post-EQ parametrik, Virtualizer, LoudnessEnhancer, 8D pan.
    private val sentPeq = FloatArray(Fx.PEQ_BANDS) { Float.NaN }
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var sentVirtKey: String? = null
    private var sentLoudMb = Int.MIN_VALUE
    private var panThread: HandlerThread? = null
    private var panHandler: Handler? = null
    private var sentLimOn: Boolean? = null
    private val _fxStatus = MutableStateFlow<String?>(null)
    /** Pesan efek FX yang tidak didukung HP (null = tidak ada masalah). */
    val fxStatus = _fxStatus.asStateFlow()
    // Kompresor (MBC) + post-gain limiter per sisi: tanda tangan nilai yang SUDAH terkirim (null = belum pernah).
    private var sentCompKey: String? = null
    private val sentPostGain = FloatArray(CHANNELS) { Float.NaN }

    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    // Pesan kenapa EQ global gak bisa nyala (null = gak ada masalah).
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    // Knob 0..1 -> dB. 0.5 = tengah = 0 dB (flat).
    fun gainKnobToDb(knob: Float): Float = (knob - 0.5f) * 24f // +-12 dB
    fun bandKnobToDb(knob: Float): Float = (knob - 0.5f) * 24f // +-12 dB

    @Synchronized
    fun start(s: ReverbSettings): Boolean {
        _error.value = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _error.value = "EQ Audio butuh Android 9 ke atas"
            return false
        }
        if (dp == null) {
            try {
                val effect = DynamicsProcessing(0, 0, buildConfig())
                val r = effect.setEnabled(true)
                if (r != AudioEffect.SUCCESS) {
                    runCatching { effect.release() }
                    _error.value = "HP ini menolak EQ global (kode $r)"
                    return false
                }
                dp = effect
                resetSent()
            } catch (t: Throwable) {
                _error.value = "HP ini tidak mendukung EQ global"
                return false
            }
        }
        applySettings(s)
        _active.value = true
        return true
    }

    @Synchronized
    fun stop() {
        stopPan()
        runCatching { virt?.setEnabled(false) }
        runCatching { virt?.release() }
        runCatching { loud?.setEnabled(false) }
        runCatching { loud?.release() }
        virt = null
        loud = null
        runCatching { dp?.setEnabled(false) }
        runCatching { dp?.release() }
        dp = null
        resetSent()
        _active.value = false
    }

    private fun resetSent() {
        for (c in sent) c.fill(Float.NaN)
        sentInGain.fill(Float.NaN)
        sentPeq.fill(Float.NaN)
        sentVirtKey = null
        sentLoudMb = Int.MIN_VALUE
        sentLimOn = null
        sentCompKey = null
        sentPostGain.fill(Float.NaN)
    }

    // Simpan pengaturan EQ AUDIO lalu kirim semuanya (EQ AUDIO + EQ 31 band) ke efek yang lagi jalan.
    // Aman dipanggil kapan pun (kalau efek belum nyala, gak ngapa-ngapain).
    @Synchronized
    fun applySettings(s: ReverbSettings) {
        lastS = s
        apply()
    }

    // Dipanggil saat EQ 31 band berubah (Eq31Ui.update): pakai pengaturan EQ AUDIO terakhir.
    @Synchronized
    fun refresh() {
        apply()
    }

    private fun apply() {
        val effect = dp ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val s = lastS
        val eq = Eq31Ui.s
        val broadOn = s != null && s.gEqEnabled
        val fineOn = eq.enabled
        // EQ AUDIO: dB per kelompok (Sub / Low / Mid / High); gain masuk dipakai di semua sisi.
        val broadDb = if (broadOn && s != null) floatArrayOf(
            bandKnobToDb(s.gEqSub), bandKnobToDb(s.gEqLow), bandKnobToDb(s.gEqMid), bandKnobToDb(s.gEqHigh)
        ) else FloatArray(4)
        baseInputGainDb = if (broadOn && s != null) gainKnobToDb(s.gEqGain) else 0f
        val fxs = FxUi.s
        try {
            pushInputGains(effect)
            for (ch in 0 until CHANNELS) {
                val side = if (ch == 0) Eq31.LEFT else Eq31.RIGHT
                // Knob GAIN sisi ini dilipat ke semua band sisi itu (hasilnya sama dengan gain keluaran per sisi).
                val sideGain = if (fineOn) Eq31.knobToDb(eq.gain(side)) else 0f
                for (i in 0 until BAND_COUNT) {
                    val fine = if (fineOn) Eq31.knobToDb(eq.band(side, i)) else 0f
                    val db = (broadDb[Eq31.groupOf(i)] + fine + sideGain + Fx.preBandDb(fxs, i)).coerceIn(-MAX_BAND_DB, MAX_BAND_DB)
                    val prev = sent[ch][i]
                    if (!prev.isNaN() && kotlin.math.abs(prev - db) < 0.05f) continue
                    effect.setPreEqBandByChannelIndex(ch, i, DynamicsProcessing.EqBand(true, CUTOFFS_HZ[i], db))
                    sent[ch][i] = db
                }
            }
            applyCompressor(effect)
            applyParametric(effect, fxs)
        } catch (t: Throwable) {
            // Efek bisa "dicabut" sistem (mis. app lain ambil alih) - jangan crash.
            _error.value = "EQ global terputus, nyalakan ulang"
        }
        applyFxEffects(fxs)
    }

    // ---- gain masuk per sisi (EQ AUDIO GAIN + 8D PAN) ----
    private fun panOffsetDb(ch: Int, f: FxSettings): Float {
        if (!f.enabled || !f.panOn) return 0f
        val period = Fx.panPeriodSec(f.panCycle)
        val t = SystemClock.elapsedRealtime() / 1000.0
        val pos = kotlin.math.sin(2.0 * Math.PI * t / period).toFloat()   // -1 kiri .. +1 kanan
        val atten = if (ch == 0) kotlin.math.max(0f, pos) else kotlin.math.max(0f, -pos)
        return -Fx.panDepthDb(f.panDepth) * atten
    }

    private fun pushInputGains(effect: DynamicsProcessing) {
        val f = FxUi.s
        for (ch in 0 until CHANNELS) {
            val g = baseInputGainDb + panOffsetDb(ch, f)
            val prev = sentInGain[ch]
            if (!prev.isNaN() && kotlin.math.abs(prev - g) < 0.05f) continue
            effect.setInputGainbyChannel(ch, g)
            sentInGain[ch] = g
        }
    }

    @Synchronized
    private fun panTick() {
        val effect = dp ?: return
        val f = FxUi.s
        if (!f.enabled || !f.panOn) return
        runCatching { pushInputGains(effect) }
        panHandler?.postDelayed({ panTick() }, 40L)
    }

    private fun startPan() {
        if (panThread != null) return
        val t = HandlerThread("fx-pan").also { it.start() }
        panThread = t
        panHandler = Handler(t.looper).also { it.post { panTick() } }
    }

    private fun stopPan() {
        panHandler?.removeCallbacksAndMessages(null)
        panHandler = null
        panThread?.quitSafely()
        panThread = null
    }

    // ---- PARAMETRIC (post-EQ 64 band, sama untuk L dan R) ----
    private fun applyParametric(effect: DynamicsProcessing, f: FxSettings) {
        val db = if (f.enabled && f.peqOn && f.points.isNotEmpty()) Fx.peqBandDb(f.points) else FloatArray(Fx.PEQ_BANDS)
        for (i in 0 until Fx.PEQ_BANDS) {
            val prev = sentPeq[i]
            if (!prev.isNaN() && kotlin.math.abs(prev - db[i]) < 0.05f) continue
            val band = DynamicsProcessing.EqBand(true, Fx.PEQ_CUTOFFS[i], db[i])
            for (ch in 0 until CHANNELS) effect.setPostEqBandByChannelIndex(ch, i, band)
            sentPeq[i] = db[i]
        }
    }

    // ---- WIDENER (Virtualizer) + TUBE DRIVE (LoudnessEnhancer) + 8D PAN: efek terpisah di session 0 ----
    private fun applyFxEffects(f: FxSettings) {
        val msgs = ArrayList<String>()
        // WIDENER
        val wantVirt = f.enabled && f.widenerOn
        if (wantVirt) {
            if (virt == null) virt = runCatching { Virtualizer(0, 0) }.getOrNull()
            val v = virt
            if (v == null) {
                msgs += "WIDENER tidak didukung HP ini"
            } else {
                val key = "${f.widener}|${f.speaker}"
                if (key != sentVirtKey) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            v.forceVirtualizationMode(if (f.speaker) Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL else Virtualizer.VIRTUALIZATION_MODE_BINAURAL)
                        }
                    }
                    if (runCatching { v.setStrength(Fx.widenerStrength(f.widener).toShort()) }.isFailure) msgs += "WIDENER: kekuatan tidak didukung"
                    if (runCatching { v.setEnabled(true) }.getOrDefault(AudioEffectError) != AudioEffect.SUCCESS) msgs += "WIDENER ditolak HP ini"
                    sentVirtKey = key
                }
            }
        } else if (virt != null) {
            runCatching { virt?.setEnabled(false) }
            runCatching { virt?.release() }
            virt = null
            sentVirtKey = null
        }
        // TUBE DRIVE
        val wantLoud = f.enabled && f.tubeOn
        if (wantLoud) {
            if (loud == null) loud = runCatching { LoudnessEnhancer(0) }.getOrNull()
            val l = loud
            if (l == null) {
                msgs += "TUBE DRIVE tidak didukung HP ini"
            } else {
                val mb = (Fx.tubeDb(f.tube) * 100f).toInt()
                if (mb != sentLoudMb) {
                    runCatching { l.setTargetGain(mb) }
                    if (runCatching { l.setEnabled(true) }.getOrDefault(AudioEffectError) != AudioEffect.SUCCESS) msgs += "TUBE DRIVE ditolak HP ini"
                    sentLoudMb = mb
                }
            }
        } else if (loud != null) {
            runCatching { loud?.setEnabled(false) }
            runCatching { loud?.release() }
            loud = null
            sentLoudMb = Int.MIN_VALUE
        }
        // 8D PAN
        if (f.enabled && f.panOn) startPan() else {
            stopPan()
            dp?.let { runCatching { pushInputGains(it) } }
        }
        _fxStatus.value = if (msgs.isEmpty()) null else msgs.joinToString(" · ")
    }
    private const val AudioEffectError = -1

    // KOMPRESOR: 2 band MBC. Band 0 (0..Frekuensi) = dikompresi (Attack/Release/Rasio/Threshold + Gain sebagai makeup);
    // band 1 (Frekuensi..20 kHz) = lewat apa adanya. Keseimbangan + Volume = post-gain limiter tiap sisi (hanya meredam).
    private const val MBC_BANDS = 10
    private const val LIMITER_THRESHOLD_DB = -2f

    private fun neutralBand(cutoff: Float, enabled: Boolean) =
        DynamicsProcessing.MbcBand(enabled, cutoff, 1f, 100f, 1f, 0f, 0f, -90f, 1f, 0f, 0f)

    // limiterOn = false (saklar LIMITER di FX): rasio 1 + ambang 0 dB = tidak membatasi, post-gain (Keseimbangan/Volume) tetap bekerja.
    private fun limiter(postGainDb: Float, limiterOn: Boolean = true) =
        if (limiterOn) DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, LIMITER_THRESHOLD_DB, postGainDb)
        else DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 1f, 0f, postGainDb)

    // Batas atas 10 band MBC. MBC 10 BAND ON: 10 band log, semuanya dikompresi dengan parameter yang sama.
    // OFF: band 0 (0..Frekuensi) dikompresi, 9 band sisanya netral (sebar log sampai 20 kHz) = perilaku 2 band sebelumnya.
    private val MBC10_CUTOFFS = floatArrayOf(60f, 120f, 250f, 500f, 1000f, 2000f, 4000f, 7000f, 12000f, 20000f)
    private fun mbcCutoffs(mbc10: Boolean, freqHz: Float): FloatArray =
        if (mbc10) MBC10_CUTOFFS
        else FloatArray(MBC_BANDS) { k -> if (k == 0) freqHz else freqHz * (20000f / freqHz).pow(k / (MBC_BANDS - 1f)) }

    private fun applyCompressor(effect: DynamicsProcessing) {
        val c = CompUi.s
        val fx = FxUi.s
        val on = c.enabled
        val mbc10 = on && fx.enabled && fx.mbc10
        val limOn = !(fx.enabled && !fx.limiterOn)
        val key = if (!on) "off" else listOf(c.attack, c.release, c.ratio, c.freq, c.threshold, c.gain)
            .joinToString(",") { "%.4f".format(java.util.Locale.US, it) } + "|" + mbc10
        if (key != sentCompKey) {
            val cut = mbcCutoffs(mbc10, if (on) Comp.freqHz(c.freq) else Comp.FREQ_MAX_HZ)
            for (k in 0 until MBC_BANDS) {
                val compressed = on && (mbc10 || k == 0)
                val band = if (compressed) DynamicsProcessing.MbcBand(
                    true, cut[k], Comp.attackMs(c.attack), Comp.releaseMs(c.release),
                    Comp.ratio(c.ratio), Comp.thresholdDb(c.threshold), 6f, -90f, 1f, 0f, Comp.gainDb(c.gain)
                ) else neutralBand(cut[k], on)
                for (ch in 0 until CHANNELS) effect.setMbcBandByChannelIndex(ch, k, band)
            }
            sentCompKey = key
        }
        for (ch in 0 until CHANNELS) {
            val side = if (ch == 0) Eq31.LEFT else Eq31.RIGHT
            val pg = if (on) Comp.sideAttenDb(c, side) else 0f
            val prev = sentPostGain[ch]
            if (!prev.isNaN() && kotlin.math.abs(prev - pg) < 0.05f && sentLimOn == limOn) continue
            effect.setLimiterByChannelIndex(ch, limiter(pg, limOn))
            sentPostGain[ch] = pg
        }
        sentLimOn = limOn
    }

    private fun buildConfig(): DynamicsProcessing.Config {
        val eq = DynamicsProcessing.Eq(true, true, BAND_COUNT)
        for (i in 0 until BAND_COUNT) {
            eq.setBand(i, DynamicsProcessing.EqBand(true, CUTOFFS_HZ[i], 0f))
        }
        // Limiter pengaman: boost gain/band gak bikin suara pecah.
        val lim = limiter(0f)
        // Kompresor: MBC 2 band, awalnya netral + dimatikan (diatur lewat applyCompressor).
        val mbc = DynamicsProcessing.Mbc(true, true, MBC_BANDS)
        val cut0 = mbcCutoffs(false, Comp.FREQ_MAX_HZ)
        for (k in 0 until MBC_BANDS) mbc.setBand(k, neutralBand(cut0[k], false))
        // Post-EQ 64 band: dipakai PARAMETRIC (FX), awalnya flat.
        val peq = DynamicsProcessing.Eq(true, true, Fx.PEQ_BANDS)
        for (i in 0 until Fx.PEQ_BANDS) peq.setBand(i, DynamicsProcessing.EqBand(true, Fx.PEQ_CUTOFFS[i], 0f))
        return DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, BAND_COUNT,   // pre-EQ: dipakai
            true, MBC_BANDS,    // MBC: dipakai kompresor (2 band / 10 band)
            true, Fx.PEQ_BANDS, // post-EQ: dipakai PARAMETRIC (FX)
            true                // limiter: dipakai
        )
            .setPreEqAllChannelsTo(eq)
            .setPostEqAllChannelsTo(peq)
            .setMbcAllChannelsTo(mbc)
            .setLimiterAllChannelsTo(lim)
            .build()
    }
}
