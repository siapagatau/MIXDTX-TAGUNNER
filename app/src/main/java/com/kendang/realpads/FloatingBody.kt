package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kendang.realpads.skin.MixerBridge
import com.kendang.realpads.skin.SkinBinding
import com.kendang.realpads.skin.SkinHost
import com.kendang.realpads.skin.SkinManager
import com.kendang.realpads.skin.SkinUiState
import com.kendang.realpads.skin.SkinZones
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// Isi panel FLOATING (jendela kecil di atas app lain). SAMA dengan layar utama:
//  - skin aktif di APK  -> floating ikut menampilkan skin yang sama (SkinHost), real time (SkinManager.state adalah state Compose,
//    jadi ganti skin di APK langsung terlihat di floating tanpa menutup panel);
//  - tanpa skin (klasik) -> MixerGrid() yang sama dengan layar utama.
// Mode equalizer (tombol EQ di baris bawah panel): bagian EQUALIZER 31 BAND menyambung tepat di bawah mixer (tanpa celah), digulir ke bawah;
// klasik lewat MixerGrid, skin lewat kolom gulir di bawah (skin mixer di atas, skin equalizer / kartu klasik di bawahnya).
// Nilai, saklar, dan meter live dua arah lewat SettingsBus / MicStateBus. Skin ditampilkan dengan PROPORSI ASLI (tidak
// ditarik/gepeng), di tengah jendela. Header (geser/minimize/tutup) dan handle resize ada di layout native floating_panel.xml.
@Composable
fun KaraokeFloatingBody(hasMicPermission: Boolean) {
    val ctx = LocalContext.current
    remember { ThemeState.load(ctx) }
    val ui = remember { SkinUiState() }
    val bridge = remember { MixerBridge(ctx.applicationContext, ui) }
    SideEffect {
        bridge.hasMicPermission = { hasMicPermission }
        // Tombol FLOATING di skin saat sudah di dalam floating = tutup panel. TEMA / SKIN hanya ada di layar utama (tidak melakukan apa-apa di sini).
        bridge.onFloating = { stopFloatingService(ctx) }
        bridge.onSysReverb = { toggleSysReverbFromFloating(ctx) }
    }
    SkinBinding(bridge, hasMicPermission)
    LaunchedEffect(Unit) { SkinManager.ensureLoaded(ctx) }

    val skin = (SkinManager.state as? SkinManager.State.Active)?.skin
    // Tanpa skin tidak ada zoom -> kembalikan ke 1x (dan jangan biarkan kunci sentuhan tersisa).
    LaunchedEffect(skin == null) { if (skin == null) { FloatingZoom.reset(); FloatingZoom.pinching = false } }
    // Zoom berhenti (jari diangkat / tombol +/- tidak ditekan lagi ~0,25 dtk) -> minta skin dirender ulang pada resolusi yang
    // cocok dengan zoom, supaya TAJAM. Selama gestur berlangsung (pinching) tidak ada render ulang.
    LaunchedEffect(skin != null) {
        if (skin == null) return@LaunchedEffect
        snapshotFlow { if (FloatingZoom.pinching) -1f else FloatingZoom.scale }
            .collectLatest { s ->
                if (s < 0f) return@collectLatest
                delay(250)
                FloatingZoom.settleSharpness()
            }
    }
    if (skin != null) {
        val zone = SkinZones.fullZone(skin)
        val ratio = if (zone.h > 0f && zone.w > 0f) zone.w / zone.h else 1f
        val eqMode = Eq31Ui.s.mode
        val compMode = CompUi.s.mode
        val fxMode = FxUi.s.mode
        val extraMode = eqMode || compMode || fxMode
        val eqScroll = rememberScrollState()
        // ZOOM: cubit dua jari = zoom, geser dua jari = pindah; tombol -/1x/+ di baris bawah panel. Satu jari tetap
        // mengoperasikan kontrol skin seperti biasa; begitu gestur dua jari terdeteksi, skin berhenti membaca sentuhan.
        Box(
            Modifier
                .fillMaxSize()
                .background(BgColor)
                .clipToBounds()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitPointerEventScope {
                        var moved = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.filter { it.pressed }
                            if (down.isEmpty()) {
                                moved = 0f
                                FloatingZoom.pinching = false
                                continue
                            }
                            if (down.size >= 2) {
                                val a = down[0]
                                val b = down[1]
                                val dist = (a.position - b.position).getDistance()
                                val pDist = (a.previousPosition - b.previousPosition).getDistance()
                                val c = (a.position + b.position) / 2f
                                val pc = (a.previousPosition + b.previousPosition) / 2f
                                if (!FloatingZoom.pinching) {
                                    moved += abs(dist - pDist) + (c - pc).getDistance()
                                    if (moved > slop) FloatingZoom.pinching = true
                                }
                                if (FloatingZoom.pinching && pDist > 1f && dist > 1f) {
                                    FloatingZoom.pinch(
                                        dist / pDist, c.x, c.y, pc.x, pc.y,
                                        size.width / 2f, size.height / 2f
                                    )
                                }
                            }
                            if (FloatingZoom.pinching) down.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Zoom = graphicsLayer (instan, lancar). Ketajaman: sesudah zoom berhenti SkinHost merender ulang skin pada resolusi
            // zoom (renderScale) -> gambar tidak buram. Selama render ulang, gambar lama tetap tampil.
            val zoomLayer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
                scaleX = FloatingZoom.scale
                scaleY = FloatingZoom.scale
                translationX = FloatingZoom.tx
                translationY = FloatingZoom.ty
            }
            if (!extraMode) {
                SkinHost(
                    skin = skin,
                    actions = bridge,
                    ui = ui,
                    onFatal = { SkinManager.reportRenderFailure(it) },
                    modifier = Modifier
                        .aspectRatio(ratio)
                        .onSizeChanged {
                            FloatingZoom.contentW = it.width.toFloat()
                            FloatingZoom.contentH = it.height.toFloat()
                        }
                        .graphicsLayer(block = zoomLayer),
                    widthPercent = 100,
                    inputLocked = { FloatingZoom.pinching },
                    renderScale = FloatingZoom.sharpScale,
                    onRenderScaleFailed = { FloatingZoom.onSharpRenderFailed() }
                )
            } else {
                // MODE KOMPRESOR/EQUALIZER: kolom gulir selebar jendela - skin mixer (proporsi asli, sama dengan tanpa mode) lalu bagian
                // EQUALIZER menyambung di bawahnya tanpa celah. Zoom berlaku untuk seluruh kolom.
                Column(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged {
                            FloatingZoom.contentW = it.width.toFloat()
                            FloatingZoom.contentH = it.height.toFloat()
                        }
                        .graphicsLayer(block = zoomLayer)
                        .verticalScroll(eqScroll)
                ) {
                    SkinHost(
                        skin = skin,
                        actions = bridge,
                        ui = ui,
                        onFatal = { SkinManager.reportRenderFailure(it) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                        widthPercent = 100,
                        inputLocked = { FloatingZoom.pinching },
                        renderScale = FloatingZoom.sharpScale,
                        onRenderScaleFailed = { FloatingZoom.onSharpRenderFailed() }
                    )
                    // Urutan: mixer -> KOMPRESOR -> EQUALIZER
                    if (compMode) CompressorSection(
                        ui, bridge, 1f, Modifier.fillMaxWidth(),
                        renderScale = FloatingZoom.sharpScale,
                        onRenderScaleFailed = { FloatingZoom.onSharpRenderFailed() },
                        inputLocked = { FloatingZoom.pinching }
                    )
                    if (fxMode) FxSection(1f, Modifier.fillMaxWidth())
                    if (eqMode) EqualizerSection(
                        ui, bridge, 1f, Modifier.fillMaxWidth(),
                        renderScale = FloatingZoom.sharpScale,
                        onRenderScaleFailed = { FloatingZoom.onSharpRenderFailed() },
                        inputLocked = { FloatingZoom.pinching }
                    )
                }
            }
        }
    } else {
        MixerGrid(
            ui = ui,
            bridge = bridge,
            hasBackgroundImage = false,
            opacity = 1f,
            modifier = Modifier.fillMaxSize(),
            onSysReverbToggle = { toggleSysReverbFromFloating(ctx) }
        )
    }
}

/** Dari panel floating: mati -> hentikan; nyala -> buka app sebentar untuk izin rekam layar (kembali otomatis). */
private fun toggleSysReverbFromFloating(ctx: android.content.Context) {
    if (SysReverbService.running.value) SysReverbService.stop(ctx) else openSysReverbRequest(ctx)
}
