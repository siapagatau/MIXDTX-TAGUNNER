package com.kendang.realpads

import android.content.Context

// Nyimpen posisi (x,y) + ukuran panel floating, posisi bubble (bulatan
// minimize) TERPISAH dari posisi panel, dan status minimize - biar pas service
// restart / HP di-reboot, semuanya balik ke terakhir kali user atur.
//
// bubbleX/bubbleY dipisah dari x/y panel: dulu bubble dipasang di x panel
// (sisi KIRI panel), jadi kalau panel ada di kanan, bubble muncul di tengah
// layar. Sekarang bubble punya posisinya sendiri (menempel di tepi).
data class FloatingGeometry(
    val x: Int,
    val y: Int,
    val widthPx: Int,
    val heightPx: Int,
    val minimized: Boolean,
    val bubbleX: Int = -1, // -1 = belum pernah disimpan
    val bubbleY: Int = -1,
)

object FloatingPrefs {
    private const val PREFS_NAME = "floating_window_prefs"

    // Panel sekarang berisi layar kontrol penuh (sama dengan APK), jadi ukuran
    // lama yang kesimpen (layout_v < 5) diabaikan sekali.
    private const val LAYOUT_VERSION = 5

    fun load(context: Context, defaultWidthPx: Int, defaultHeightPx: Int): FloatingGeometry {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sizeIsCurrent = p.getInt("layout_v", 0) >= LAYOUT_VERSION
        return FloatingGeometry(
            x = p.getInt("x", 0),
            y = p.getInt("y", 200),
            widthPx = if (sizeIsCurrent) p.getInt("width", defaultWidthPx) else defaultWidthPx,
            heightPx = if (sizeIsCurrent) p.getInt("height", defaultHeightPx) else defaultHeightPx,
            minimized = p.getBoolean("minimized", false),
            bubbleX = p.getInt("bx", -1),
            bubbleY = p.getInt("by", -1),
        )
    }

    fun save(context: Context, geo: FloatingGeometry) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("x", geo.x)
            .putInt("y", geo.y)
            .putInt("layout_v", LAYOUT_VERSION)
            .putInt("width", geo.widthPx)
            .putInt("height", geo.heightPx)
            .putBoolean("minimized", geo.minimized)
            .putInt("bx", geo.bubbleX)
            .putInt("by", geo.bubbleY)
            .apply()
    }
}
