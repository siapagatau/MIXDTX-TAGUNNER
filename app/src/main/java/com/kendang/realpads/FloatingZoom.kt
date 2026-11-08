package com.kendang.realpads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Keadaan ZOOM panel floating saat memakai SKIN. Satu objek dipakai bersama oleh isi Compose (KaraokeFloatingBody:
// gestur cubit dua jari + geser dua jari) dan tombol native di baris bawah panel (-, 1x, +), jadi keduanya selalu sinkron.
// Zoom hanya mengubah TAMPILAN skin di dalam jendela (skala + geser); ukuran jendela tidak berubah, dan sentuhan
// tetap tepat sasaran karena Compose memetakan posisi jari lewat transformasi yang sama.
object FloatingZoom {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f

    /** Resolusi render DASAR floating (pengali ukuran jendela): skin selalu dirender sebesar ini (= zoom mentok), seperti avatar VTuber, jadi zoom apa pun langsung tajam sejauh batas bitmap di SkinRenderer. */
    const val BASE_SHARP = MAX_SCALE

    var scale by mutableStateOf(1f)
        private set
    var tx by mutableStateOf(0f)
        private set
    var ty by mutableStateOf(0f)
        private set

    /** true selama gestur dua jari berlangsung -> skin berhenti membaca sentuhan (knob/tombol tidak ikut terputar). */
    var pinching by mutableStateOf(false)

    // Ukuran area skin (px) - dipakai membatasi geseran supaya tepi skin tidak ditarik masuk ke tengah.
    var contentW = 0f
    var contentH = 0f

    // ---- KETAJAMAN (anti-buram) ----
    // graphicsLayer hanya MEMBESARKAN gambar yang sudah jadi, jadi skin yang dirender 1x pasti pecah saat dizoom.
    // Cara avatar VTuber: gambar dirender LEBIH BESAR dari jendelanya, zoom tinggal memperbesar -> tetap tajam.
    // Di sini: begitu zoom berhenti, SkinHost merender ulang skin pada resolusi yang cocok dengan zoom (kelipatan tetap
    // di bawah). Selama gestur resolusi tidak diubah (tetap lancar), sesudah jari diangkat gambar langsung diganti yang tajam.
    private val SHARP_STEPS = floatArrayOf(1f, 1.5f, 2f, 3f, 4f)

    /** Pengali resolusi render yang diminta SkinHost (1 = ukuran jendela). Awal = BASE_SHARP; naik hanya SETELAH zoom berhenti di atas BASE_SHARP. */
    var sharpScale by mutableStateOf(BASE_SHARP)
        private set

    // Batas atas; turun otomatis bila render beresolusi tinggi gagal (mis. memori HP kecil).
    private var sharpCeiling = MAX_SCALE

    /** Langkah resolusi terkecil yang cukup untuk zoom [s] (dibulatkan ke atas), tidak melewati batas perangkat. */
    fun sharpStepFor(s: Float): Float {
        val step = SHARP_STEPS.firstOrNull { it >= s - 0.01f } ?: MAX_SCALE
        return min(max(step, BASE_SHARP), sharpCeiling)
    }

    /** Dipanggil setelah zoom berhenti: samakan resolusi render dengan zoom sekarang. */
    fun settleSharpness() {
        sharpScale = sharpStepFor(scale)
    }

    /** Render beresolusi tinggi gagal: turun satu langkah dan jangan coba lagi di atasnya. */
    fun onSharpRenderFailed() {
        val lower = SHARP_STEPS.lastOrNull { it < sharpScale } ?: 1f
        sharpCeiling = lower
        sharpScale = lower
    }

    private fun clampPan() {
        val maxX = contentW * (scale - 1f) / 2f
        val maxY = contentH * (scale - 1f) / 2f
        tx = tx.coerceIn(-maxX, maxX)
        ty = ty.coerceIn(-maxY, maxY)
    }

    /**
     * Cubit/geser dua jari. (cx,cy) = titik tengah dua jari sekarang, (pcx,pcy) = sebelumnya, relatif ke pusat area (vcx,vcy).
     * Titik skin di bawah jari dijaga tetap di bawah jari.
     */
    fun pinch(factor: Float, cx: Float, cy: Float, pcx: Float, pcy: Float, vcx: Float, vcy: Float) {
        val s = scale
        val s2 = (s * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val r = s2 / s
        tx = cx - vcx - r * (pcx - vcx - tx)
        ty = cy - vcy - r * (pcy - vcy - ty)
        scale = s2
        snapIfNearOne()
        clampPan()
    }

    /** Tombol +/- : zoom ke pusat. dir > 0 = perbesar, dir < 0 = perkecil. */
    fun step(dir: Int) {
        val s2 = (if (dir > 0) scale * 1.25f else scale / 1.25f).coerceIn(MIN_SCALE, MAX_SCALE)
        val r = s2 / scale
        tx *= r
        ty *= r
        scale = s2
        snapIfNearOne()
        clampPan()
    }

    fun reset() {
        scale = 1f
        tx = 0f
        ty = 0f
        sharpScale = min(BASE_SHARP, sharpCeiling)   // kembali ke resolusi dasar (bukan 1x): zoom berikutnya langsung tajam
    }

    private fun snapIfNearOne() {
        if (abs(scale - 1f) < 0.03f) { scale = 1f; tx = 0f; ty = 0f }
    }

    val isZoomed: Boolean get() = scale > 1f
}
