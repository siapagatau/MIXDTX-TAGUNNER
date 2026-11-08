package com.kendang.realpads.skin

import com.kendang.realpads.Comp
import com.kendang.realpads.Eq31

/**
 * Katalog function RESMI skin mixer (mapping[id].function di skin.json). Function di luar katalog = skin ditolak SkinValidator.
 * Pad DTX diganti kontrol mixer: tombol (button), saklar (toggle), knob (rotary), slider, lampu meter (indicators) dan display.
 * Semua nilai berkunci NAMA FUNCTION (bukan id kontrol) -> satu sumber kebenaran untuk skin, UI klasik, dan panel floating.
 */
object FunctionRegistry {
    // toggle (saklar ON/OFF, tap)
    val toggleFunctions: Set<String> = setOf(
        "mic.toggle", "reverb.toggle", "echo.toggle", "eqmic.toggle", "eqaudio.toggle",
        "sysreverb.toggle", "sysreverb.reverb.toggle", "sysreverb.echo.toggle",
        // EQ 31 BAND: eq31.toggle = efek ON/OFF; eq31.mode = tampilkan bagian EQUALIZER di bawah mixer
        Eq31.TOGGLE, Eq31.MODE,
        // KOMPRESOR: comp.toggle = efek ON/OFF; comp.mode = tampilkan bagian KOMPRESOR di atas equalizer
        Comp.TOGGLE, Comp.MODE
    )

    // knob (rotary) ATAU slider, nilai 0..1
    val valueFunctions: Set<String> = setOf(
        "mic.volume", "mic.gate",
        "reverb.wet", "reverb.dry", "reverb.width", "reverb.damp", "reverb.room", "reverb.cess",
        "echo.delay", "echo.feedback", "echo.mix",
        "eqmic.low", "eqmic.mid", "eqmic.high",
        "eqaudio.gain", "eqaudio.sub", "eqaudio.low", "eqaudio.mid", "eqaudio.high",
        "sysreverb.wet", "sysreverb.room", "sysreverb.damp", "sysreverb.width", "sysreverb.cess", "sysreverb.predelay",
        "sysreverb.delay", "sysreverb.feedback", "sysreverb.mix", "sysreverb.gain",
        "sysreverb.rsend.sub", "sysreverb.rsend.low", "sysreverb.rsend.mid", "sysreverb.rsend.high",
        "sysreverb.esend.sub", "sysreverb.esend.low", "sysreverb.esend.mid", "sysreverb.esend.high"
    ) + Eq31.VALUE_FUNCTIONS +   // eq31.l.01..31, eq31.r.01..31, eq31.l.gain, eq31.r.gain
        Comp.VALUE_FUNCTIONS          // comp.attack/release/ratio/freq/threshold/gain/balance/volume

    // tombol (tap)
    val buttonFunctions: Set<String> = setOf("floating.toggle", "theme.open", "skin.pick", Eq31.RESET, Comp.RESET)

    // lampu meter (hanya untuk "indicators" dengan "meter")
    val meterFunctions: Set<String> = setOf("meter.mic", "meter.output", "meter.sysreverb")

    // display (LCD teks)
    val displayFunctions: Set<String> = setOf("display.status")

    val official: Set<String> = toggleFunctions + valueFunctions + buttonFunctions + meterFunctions + displayFunctions

    /** Fungsi yang cocok untuk tiap tipe kontrol (dipakai validator). */
    fun allowedFor(type: ControlType): Set<String> = when (type) {
        ControlType.PAD -> emptySet()   // pad sampler DTX tidak ada di APK mixer
        ControlType.BUTTON -> buttonFunctions
        ControlType.TOGGLE -> toggleFunctions
        ControlType.ROTARY, ControlType.SLIDER -> valueFunctions
        ControlType.DISPLAY -> displayFunctions
    }
}
