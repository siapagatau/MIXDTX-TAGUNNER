package com.kendang.realpads.skin

import android.util.Log
import kotlin.math.roundToInt

/** Menghubungkan sentuhan pada kontrol skin ke [SkinActions]. Hanya function dari [FunctionRegistry] yang dikenal. */
class SkinActionDispatcher(
    private val actions: () -> SkinActions,
    private val ui: () -> SkinUiState
) {
    /** Tombol: tap. Saklar: balik status. @return true bila ditangani. */
    fun trigger(c: SkinControl): Boolean {
        val a = actions()
        return when (c.type) {
            ControlType.TOGGLE -> { a.setToggle(c.function, !(ui().toggles[c.function] ?: false)); true }
            ControlType.BUTTON -> { a.press(c.function); true }
            else -> { Log.w("SkinDispatcher", "tipe ${c.type} tidak dipicu lewat tap: ${c.id}"); false }
        }
    }

    /** Knob / slider: nilai 0..1. */
    fun setValue(c: SkinControl, value: Float) {
        actions().setValue(c.function, value.coerceIn(0f, 1f))
    }
}

/**
 * Mengisi template display, mis. "MIC {mic:ONOFF}  REV {reverb:ONOFF}" -> "MIC ON  REV OFF".
 * {nama:ONOFF} = status saklar (nama = "mic" atau "mic.toggle"); {function:PCT} = nilai knob 0-100. Token tak dikenal dibiarkan.
 */
object DisplayTemplate {
    private val token = Regex("\\{([\\w.]+)(?::(\\w+))?\\}")

    fun render(line: String, ui: SkinUiState): String = token.replace(line) { m ->
        val name = m.groupValues[1]
        val fmt = m.groupValues[2]
        val tog = ui.toggles[name] ?: ui.toggles["$name.toggle"]
        val v = ui.values[name]
        when {
            tog != null -> if (fmt == "ONOFF") (if (tog) "ON" else "OFF") else tog.toString()
            v != null -> if (fmt == "PCT") (v * 100f).roundToInt().toString() else "%.2f".format(v)
            else -> m.value
        }
    }
}
