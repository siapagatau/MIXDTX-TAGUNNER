package com.kendang.realpads

import android.content.Context

// Nyimpen posisi semua knob + status on/off reverb ke SharedPreferences, biar
// gak balik ke default tiap kali app ditutup-buka lagi. SharedPreferences
// dipilih (bukan database) karena datanya cuma beberapa angka/boolean kecil -
// paling ringan & paling cepat buat kasus kayak gini.
data class ReverbSettings(
    val wet: Float,
    val dry: Float,
    val width: Float,
    val damp: Float,
    val roomSize: Float,
    val micVol: Float,
    val reverbEnabled: Boolean,
    // Echo terpisah dari Reverb - reverb bikin "dengung" nyampur, echo bikin
    // "repeat"/gema yang jelas kedengeran. Nilainya 0..1 sama kayak knob
    // lain (di-scale ke ms/feedback/mix asli pas dikirim ke native, lihat
    // MainActivity). echoEnabled default false biar instalasi baru /
    // update dari versi lama perilakunya SAMA PERSIS kayak sebelum fitur
    // ini ada - gak ada delay tambahan sama sekali sampai user sendiri
    // yang nyalain lewat tombol power di panel Echo.
    val echoDelay: Float,
    val echoFeedback: Float,
    val echoMix: Float,
    val echoEnabled: Boolean,
    // EQ 3-band ala channel strip Yamaha MG (Low 100Hz shelving, Mid 2.5kHz
    // peaking, High 10kHz shelving, +-15dB). Knob 0..1, 0.5 = tengah = 0dB
    // (flat, gak ngubah suara) - makanya eqEnabled default TRUE (beda sama
    // echoEnabled yang default false): EQ flat gak kedengeran bedanya sama
    // sekali sampai user geser salah satu knob-nya, jadi aman dinyalain
    // dari awal, persis kayak EQ di mixer fisik yang emang selalu "in
    // circuit" walau semua knob-nya di posisi jam 12.
    val eqLow: Float,
    val eqMid: Float,
    val eqHigh: Float,
    val eqEnabled: Boolean,
    // "Cess": panjang & terangnya desis huruf S di ekor reverb (karakter
    // reverb Alesis). 0 = reverb gelap/halus, 1 = desis paling panjang.
    val cess: Float = 0.6f,
    // Noise gate mic (0 = mati). Meredam desis/noise lantai saat tidak ada
    // suara - lihat NoiseGate di KaraokeEngine.h.
    val gate: Float = 0.5f,
    // EQ AUDIO GLOBAL (efek ke suara app lain: YouTube, TikTok, musik).
    // Terpisah dari EQ mic di atas. Knob 0..1, 0.5 = flat (0 dB).
    // Default OFF: butuh foreground service, jadi user yang nyalain sendiri.
    val gEqGain: Float = 0.5f,
    val gEqSub: Float = 0.5f,
    val gEqLow: Float = 0.5f,
    val gEqMid: Float = 0.5f,
    val gEqHigh: Float = 0.5f,
    val gEqEnabled: Boolean = false,
)

object SettingsStore {
    private const val PREFS_NAME = "karaoke_settings"

    // Versi tuning reverb. Engine reverb dirombak total (lebih terang, ekor
    // desis panjang), jadi nilai Damp/Room Size lama yang kesimpen di HP
    // (dibuat buat engine lama yang gelap) bakal bikin suara baru tetap
    // terdengar kurang "cess". Pas naik versi, 3 nilai itu direset SEKALI ke
    // default baru; knob lain (wet/dry/echo/eq/mic) tetap seperti punya user.
    private const val TUNING_VERSION = 2

    fun load(context: Context): ReverbSettings {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (p.getInt("tuningVersion", 0) < TUNING_VERSION) {
            p.edit()
                .putFloat("damp", 0.3f)
                .putFloat("roomSize", 0.6f)
                .putFloat("cess", 0.6f)
                .putInt("tuningVersion", TUNING_VERSION)
                .apply()
        }
        return ReverbSettings(
            wet = p.getFloat("wet", 0.35f),
            dry = p.getFloat("dry", 0.85f),
            width = p.getFloat("width", 1.0f),
            damp = p.getFloat("damp", 0.3f),
            roomSize = p.getFloat("roomSize", 0.6f),
            micVol = p.getFloat("micVol", 0.5f),
            reverbEnabled = p.getBoolean("reverbEnabled", true),
            echoDelay = p.getFloat("echoDelay", 0.45f),
            echoFeedback = p.getFloat("echoFeedback", 0.45f),
            echoMix = p.getFloat("echoMix", 0.3f),
            echoEnabled = p.getBoolean("echoEnabled", false),
            eqLow = p.getFloat("eqLow", 0.5f),
            eqMid = p.getFloat("eqMid", 0.5f),
            eqHigh = p.getFloat("eqHigh", 0.5f),
            eqEnabled = p.getBoolean("eqEnabled", true),
            cess = p.getFloat("cess", 0.6f),
            gate = p.getFloat("gate", 0.5f),
            gEqGain = p.getFloat("gEqGain", 0.5f),
            gEqSub = p.getFloat("gEqSub", 0.5f),
            gEqLow = p.getFloat("gEqLow", 0.5f),
            gEqMid = p.getFloat("gEqMid", 0.5f),
            gEqHigh = p.getFloat("gEqHigh", 0.5f),
            gEqEnabled = p.getBoolean("gEqEnabled", false),
        )
    }

    fun save(context: Context, s: ReverbSettings) {
        // apply() nulis di background thread sendiri (non-blocking), jadi
        // aman dipanggil tiap knob digeser tanpa bikin UI nge-lag.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat("wet", s.wet)
            .putFloat("dry", s.dry)
            .putFloat("width", s.width)
            .putFloat("damp", s.damp)
            .putFloat("roomSize", s.roomSize)
            .putFloat("micVol", s.micVol)
            .putBoolean("reverbEnabled", s.reverbEnabled)
            .putFloat("echoDelay", s.echoDelay)
            .putFloat("echoFeedback", s.echoFeedback)
            .putFloat("echoMix", s.echoMix)
            .putBoolean("echoEnabled", s.echoEnabled)
            .putFloat("eqLow", s.eqLow)
            .putFloat("eqMid", s.eqMid)
            .putFloat("eqHigh", s.eqHigh)
            .putBoolean("eqEnabled", s.eqEnabled)
            .putFloat("cess", s.cess)
            .putFloat("gate", s.gate)
            .putFloat("gEqGain", s.gEqGain)
            .putFloat("gEqSub", s.gEqSub)
            .putFloat("gEqLow", s.gEqLow)
            .putFloat("gEqMid", s.gEqMid)
            .putFloat("gEqHigh", s.gEqHigh)
            .putBoolean("gEqEnabled", s.gEqEnabled)
            .apply()
    }
}
