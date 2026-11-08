package com.kendang.realpads

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

// Pengukur level suara MUSIK (app lain: YouTube, TikTok, dll).
//
// Pakai android.media.audiofx.Visualizer di audio session 0 (output mix) -
// pasangan dari EQ global. Visualizer membaca MIX AKHIR, jadi suara mic
// karaoke kita sendiri ikut terbaca. Untuk memisahkan, UI mengurangi daya
// output engine mic (lihat nativeGetOutputMeanSquare) dari daya mix ini -
// hasilnya PERKIRAAN level musik, bukan ukuran yang presisi.
//
// Visualizer cuma dibuat selama ada layar yang menampilkan meter (acquire /
// release dihitung referensi) supaya hemat baterai.
object MusicMeter {
    private var viz: Visualizer? = null
    private var users = 0
    private var buf = ByteArray(0)

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    @Synchronized
    fun acquire() {
        users++
        if (viz != null) return
        try {
            val v = Visualizer(0)
            val size = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
            v.captureSize = size
            buf = ByteArray(size)
            v.enabled = true
            viz = v
            _error.value = null
        } catch (t: Throwable) {
            viz = null
            _error.value = "Meter musik tidak didukung di HP ini"
        }
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            runCatching { viz?.enabled = false }
            runCatching { viz?.release() }
            viz = null
        }
    }

    // RMS (0..1) dari mix output saat ini; -1 kalau Visualizer tidak tersedia.
    @Synchronized
    fun readMixRms(): Float {
        val v = viz ?: return -1f
        return try {
            if (v.getWaveForm(buf) != Visualizer.SUCCESS) return -1f
            var sum = 0.0
            for (b in buf) {
                val x = ((b.toInt() and 0xFF) - 128) / 128.0 // 8-bit unsigned, pusat 128
                sum += x * x
            }
            sqrt(sum / buf.size).toFloat()
        } catch (t: Throwable) {
            -1f
        }
    }
}
