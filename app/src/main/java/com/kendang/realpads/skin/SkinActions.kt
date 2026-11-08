package com.kendang.realpads.skin

import androidx.compose.runtime.mutableStateMapOf

/**
 * Kontrak antara Skin Engine dan fungsi APK mixer. Diimplementasikan oleh [MixerBridge]; modul skin tidak bergantung
 * pada MainActivity. Semua parameter berkunci nama function (lihat [FunctionRegistry]).
 */
interface SkinActions {
    fun setValue(function: String, value: Float)       // knob / slider, 0..1
    fun setToggle(function: String, on: Boolean)       // saklar
    fun press(function: String)                        // tombol (floating.toggle / theme.open / skin.pick)
}

/**
 * Keadaan yang dibaca skin saat menggambar. Peta berbasis snapshot-state -> kanvas skin menggambar ulang sendiri
 * begitu ada nilai yang berubah. Kunci = nama function.
 */
class SkinUiState {
    val values = mutableStateMapOf<String, Float>()      // knob / slider 0..1
    val toggles = mutableStateMapOf<String, Boolean>()   // saklar
    val meters = mutableStateMapOf<String, Float>()      // "meter.mic" / "meter.output" 0..1
}
