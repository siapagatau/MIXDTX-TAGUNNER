package com.kendang.realpads

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Bus IN-PROCESS buat nyebar perubahan ReverbSettings (mic vol,
// wet/dry/width/damp/roomSize, echo, reverb on/off) secara REAL-TIME
// antara MainActivity <-> FloatingWindowService.
//
// Kenapa ini perlu, padahal udah ada SettingsStore (disk) yang sama-sama
// dibaca dua sisi: SettingsStore itu cuma persist ke disk - gak ada sinyal
// apa pun ke pihak lain kalau isinya berubah. MainActivity baca ulang
// SettingsStore CUMA pas ON_RESUME, dan panel floating baca ulang CUMA pas
// showPanel() dipanggil (buka dari bubble). Kalau overlay lagi nongol DI
// ATAS MainActivity yang masih kebuka (overlay-nya FLAG_NOT_FOCUSABLE, jadi
// activity di baliknya TETAP RESUMED, gak pernah pause/resume ulang), geser
// slider Mic Vol di floating panel gak bakal ke-refresh ke MainActivity
// sampai kapanpun. Mic Vol murni data internal app, gak ada broadcast
// sistem yang bisa ditumpangi, jadi kita bikin sendiri versi in-process-nya -
// cukup singleton Kotlin biasa karena Activity & Service ini jalan di
// PROCESS YANG SAMA (bukan app terpisah), gak butuh IPC.
object SettingsBus {
    private val _updates = MutableSharedFlow<ReverbSettings>(replay = 0, extraBufferCapacity = 1)
    val updates = _updates.asSharedFlow()

    // Dipanggil oleh SIAPAPUN (MainActivity ATAU FloatingWindowService) tiap
    // kali dia BARUSAN nyimpen perubahan ke SettingsStore - biar pihak lain
    // (kalau lagi kelihatan aktif) langsung dapet nilai terbaru buat
    // nge-update tampilannya sendiri, tanpa perlu nunggu lifecycle apa pun.
    // Sisi yang NERIMA update ini CUKUP update tampilan lokalnya doang -
    // JANGAN ikut manggil SettingsStore.save()/publish() lagi di situ, biar
    // gak muter balik jadi loop tak berujung.
    fun publish(settings: ReverbSettings) {
        _updates.tryEmit(settings)
    }
}

// Bus kedua, KHUSUS status ON/OFF mic (AudioEngine.nativeIsRunning()).
//
// Kenapa perlu terpisah dari SettingsBus di atas: status running BUKAN
// bagian dari ReverbSettings (gak kesimpen di SettingsStore, sumbernya
// langsung dari native AudioEngine yang singleton se-process). Sebelum ini
// ditambahin, tombol ON/OFF mic besar di MainActivity dan tombol
// "Mic: ON/OFF" di panel floating SAMA SEKALI gak saling ngasih tau kalau
// salah satu abis di-tap - beda sama slider Mic Vol yang emang udah lewat
// SettingsBus. Makanya laporannya: geser slider vol langsung ke-refresh di
// dua sisi, tapi tap tombol on/off mic gak ikut ke-refresh ke sisi lainnya.
object MicStateBus {
    private val _updates = MutableSharedFlow<Boolean>(replay = 0, extraBufferCapacity = 1)
    val updates = _updates.asSharedFlow()

    // Dipanggil SIAPAPUN (MainActivity ATAU FloatingWindowService) tiap kali
    // dia BARUSAN nge-start/stop AudioEngine, biar pihak lain (kalau lagi
    // kelihatan aktif) langsung update tampilan tombolnya sendiri tanpa
    // nunggu lifecycle apa pun. Sisi PENERIMA cukup update tampilan lokal
    // doang - JANGAN ikut manggil nativeStart()/nativeStop() lagi di situ.
    fun publish(isRunning: Boolean) {
        _updates.tryEmit(isRunning)
    }
}
