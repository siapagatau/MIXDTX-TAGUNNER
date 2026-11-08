package com.kendang.realpads

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palet & ukuran DTX ASLI (disalin apa adanya dari MainActivity APK DTX) - flat, abu gelap, tanpa tint.
// Hanya warna aksen yang berubah-ubah (ThemeState). Jangan diturunkan/ditint lagi dari warna tema.
val BgColor = Color(0xFF111113)
val PanelColor = Color(0xFF3C3C3F)
val PadColor = Color(0xFF4D4D50)
val PadLineColor = Color(0xFF232325)
val PadTextColor = Color(0xFFA9A9AD)
val CtrlPanelColor = Color(0xFF1C1C1E)
val TextDim = Color(0xFF8C8C90)
val BackdropColor = BgColor

val AppTextSize = 12.sp
val GroupOuterPaddingV = 8.dp
val GroupOuterPaddingH = 8.dp
val GroupItemSpacing = 6.dp
val ControlIconSize = 24.dp

// Warna INDIKATOR (knob terisi, meter, saklar ON, tombol aktif): selalu hijau, TIDAK mengikuti tema.
val IndicatorGreen = Color(0xFF3DDC84)
val IndicatorGreenText = Color(0xFF08200F)
