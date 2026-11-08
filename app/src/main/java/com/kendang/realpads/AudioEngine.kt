package com.kendang.realpads

// Wrapper tipis ke KaraokeEngine (C++/Oboe). Semua kerjaan berat (baca mic,
// proses reverb, tulis speaker) terjadi di native audio thread - kelas ini
// cuma jembatan start/stop/set parameter dari UI Compose.
object AudioEngine {
    init {
        System.loadLibrary("karaokeengine")
    }

    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeIsRunning(): Boolean
    external fun nativeSetReverbParams(wet: Float, dry: Float, width: Float, damp: Float, roomSize: Float)
    external fun nativeSetMicGain(gain: Float)
    external fun nativeSetReverbEnabled(enabled: Boolean)
    external fun nativeSetCess(cess: Float)
    external fun nativeSetGate(amount: Float)
    external fun nativeSetPreDelayMs(ms: Float)
    external fun nativeApplyNyesPreset()
    external fun nativeSetEQParams(lowDb: Float, midDb: Float, highDb: Float)
    external fun nativeSetEQEnabled(enabled: Boolean)
    external fun nativeSetEchoParams(delayMs: Float, feedback: Float, mix: Float)
    external fun nativeSetEchoEnabled(enabled: Boolean)
    external fun nativeGetMicMeanSquare(): Float
    external fun nativeGetOutputMeanSquare(): Float
    external fun nativeShutdown()
}

// Kirim SEMUA pengaturan yang kesimpen ke engine native sekaligus. Dipakai
// setiap kali engine baru dinyalain (dari layar utama MAUPUN panel floating)
// biar dua jalur itu SELALU identik - dulu panel floating lupa ngirim EQ,
// jadi EQ "hilang" kalau mic dinyalain dari floating.
fun pushAllSettingsToEngine(s: ReverbSettings) {
    AudioEngine.nativeSetReverbParams(s.wet, s.dry, s.width, s.damp, s.roomSize)
    AudioEngine.nativeSetCess(s.cess)
    AudioEngine.nativeSetGate(s.gate)
    AudioEngine.nativeSetMicGain(s.micVol * 2f)
    AudioEngine.nativeSetReverbEnabled(s.reverbEnabled)
    AudioEngine.nativeSetEchoParams(50f + s.echoDelay * 550f, s.echoFeedback * 0.75f, s.echoMix)
    AudioEngine.nativeSetEchoEnabled(s.echoEnabled)
    AudioEngine.nativeSetEQParams(eqKnobToDb(s.eqLow), eqKnobToDb(s.eqMid), eqKnobToDb(s.eqHigh))
    AudioEngine.nativeSetEQEnabled(s.eqEnabled)
}
