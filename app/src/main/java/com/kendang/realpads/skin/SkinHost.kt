package com.kendang.realpads.skin

import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kendang.realpads.BgColor
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "SkinHost"

/** Lama lampu indikator pad padam bertahap setelah pad dipukul (ms). Lebih panjang dari kedip pad (90 ms) supaya lampu kecil tetap terlihat. */
private const val LAMP_FADE_MS = 240f

/** Gambar siap pakai. [active] diisi BERTAHAP di latar belakang (state map -> layar menggambar ulang sendiri saat ada yang siap). */
private class LayerImages(
    val base: ImageBitmap,
    val rotary: Map<String, ImageBitmap>,
    val active: Map<String, ImageBitmap>
)

/** Pelacak satu jari yang sedang menekan satu kontrol. */
private class Tracked(val control: SkinControl) {
    var lastAngle = 0f
    var lastY = 0f
    var knobValue = 0f
    var startValue = 0f   // nilai saat jari menyentuh: dipakai mengembalikan kontrol bila gestur ternyata zoom dua jari (floating)
}

/**
 * Skin v3.2 - host. Menggambar skin di area yang diberikan induk (PadScreen), di antara panel preset & panel
 * volume/musik ASLI APK.
 *
 *  - SELURUH skin (pad + panel kontrol) di-STRETCH memenuhi area: satu gambar, satu perlakuan untuk semua bagian,
 *    tanpa area kosong dan tanpa pemisahan zona. Desain asli skin tidak diubah.
 *  - Panel kontrol skin bisa disembunyikan ([showPanel] = false): zona pad saja yang di-stretch memenuhi area.
 *  - TIDAK ada dialog buatan skin. Tombol skin memanggil fungsi/dialog ASLI APK lewat [SkinActions].
 *  - Penanganan sentuhan tidak pernah di-restart di tengah gestur dan, begitu semua jari terangkat,
 *    status "ditekan" dibersihkan paksa -> tidak ada highlight pad yang nyangkut.
 *  - Saat ukuran berubah, gambar lama tetap tampil sampai gambar baru siap.
 *
 * @param padFlash intensitas kedip pad 0..1 dari PadScreen (flashIntensity).
 * @param showPanel tampilkan panel kontrol skin (LCD/tombol/knob).
 * @param onFatal dipanggil bila render skin gagal total -> PadScreen kembali ke UI klasik.
 */
@Composable
fun SkinHost(
    skin: SkinDefinition,
    actions: SkinActions,
    ui: SkinUiState,
    padFlash: (Int) -> Float = { 0f },
    onFatal: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Pilihan mode 16 pad dari APK; hanya berlaku jika skin mendukung 12 DAN 16. */
    wants16Pads: Boolean = false,
    showPanel: Boolean = true,
    /** Lebar skin 0..100 (lihat SkinManager.widthPercent). Dibaca sebagai state -> slider di pemilih skin langsung terlihat efeknya. */
    widthPercent: Int = SkinManager.widthPercent,
    /** Posisi tombol LOAD skin (koordinat root) begitu skin sudah tergambar; null selama belum siap / panel disembunyikan. Dipakai tutorial. */
    onLoadButtonBounds: (Rect?) -> Unit = {},
    /** true = abaikan semua sentuhan (mis. saat floating sedang dizoom dua jari). Kontrol yang sedang ditahan dilepas. */
    inputLocked: () -> Boolean = { false },
    /**
     * Pengali resolusi render (1..4). Floating memakainya saat zoom: skin dirender 1x / 1,5x / 2x.. ukuran areanya, lalu graphicsLayer
     * tinggal memperbesar -> tetap tajam (tidak buram). Nilai 1 = perilaku biasa (layar utama).
     */
    renderScale: Float = 1f,
    /** Dipanggil bila render ulang beresolusi lebih tinggi gagal (mis. memori habis); gambar yang sedang tampil tetap dipakai. */
    onRenderScaleFailed: () -> Unit = {}
) {
    val density = LocalDensity.current.density
    val appContext = LocalContext.current.applicationContext
    val latestActions by rememberUpdatedState(actions)
    val latestUi by rememberUpdatedState(ui)
    val latestFatal by rememberUpdatedState(onFatal)
    val latestLoadBounds by rememberUpdatedState(onLoadButtonBounds)
    val latestInputLocked by rememberUpdatedState(inputLocked)
    val latestScaleFailed by rememberUpdatedState(onRenderScaleFailed)
    val rs = renderScale.coerceIn(1f, 4f)
    var hostOrigin by remember { mutableStateOf<Offset?>(null) }

    val dispatcher = remember(skin) {
        SkinActionDispatcher(actions = { latestActions }, ui = { latestUi })
    }
    val visiblePads = PadModes.visibleCount(skin, wants16Pads)
    val pressed = remember(skin) { mutableStateMapOf<String, Int>() }
    // Lampu indikator pad (mis. keypad DTX-Multi 12): nomor pad -> kecerahan 0..1. Diisi oleh loop frame di bawah.
    val lamp = remember(skin) { mutableStateMapOf<Int, Float>() }
    val latestPadFlash by rememberUpdatedState(padFlash)
    var renderFailed by remember(skin) { mutableStateOf(false) }
    var renderError by remember(skin) { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { latestLoadBounds(null) } }

    // Penggerak lampu indikator. Sumber kebenarannya = padFlash (flashIntensity milik PadScreen) yang sudah diisi oleh SEMUA jalur
    // pukulan (sentuh skin, MIDI, keyboard) lewat onPadDown -> lampu ikut menyala untuk semuanya. Pukulan baru = nilai padFlash
    // naik -> lampu langsung terang (ikut kekuatan pukulan), lalu padam bertahap selama LAMP_FADE_MS.
    LaunchedEffect(skin, visiblePads) {
        val pads = skin.indicators.map { it.pad }.filter { it in 1..visiblePads }.distinct()
        if (pads.isEmpty()) return@LaunchedEffect
        val prev = HashMap<Int, Float>()
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dtMs = if (lastNanos == 0L) 0f else (now - lastNanos) / 1_000_000f
                lastNanos = now
                for (p in pads) {
                    val f = latestPadFlash(p)
                    val before = lamp[p] ?: 0f
                    val v = if (f > 0f && f > (prev[p] ?: 0f) + 0.02f) {
                        0.45f + 0.55f * f.coerceIn(0f, 1f)          // pukulan baru
                    } else {
                        max(0f, before - dtMs / LAMP_FADE_MS)       // padam bertahap
                    }
                    prev[p] = f
                    if (v > 0f) { if (v != before) lamp[p] = v } else if (before != 0f) lamp.remove(p)
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(BgColor)
            .onGloballyPositioned { hostOrigin = it.positionInRoot() }
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        // Seluruh skin di-stretch memenuhi area (panel disembunyikan -> hanya zona pad yang di-stretch).
        val widthFraction = widthPercent.coerceIn(0, 100) / 100f
        val layout = remember(skin, wPx, hPx, showPanel, widthFraction) { SkinLayout(skin, wPx, hPx, showPanel, widthFraction) }
        // Lebar acuan render = lebar skin LENGKAP (tidak bergantung status panel) -> tampil/sembunyi panel tidak memicu render ulang.
        val renderW = remember(skin, wPx, hPx, widthFraction) { SkinLayout(skin, wPx, hPx, true, widthFraction).zone.dst.w }
        val hitTester = remember(skin, layout, visiblePads) {
            HitTester(
                skin.controls
                    .filter { it.hit != null && !(it.type == ControlType.PAD && it.index > visiblePads) }
                    .mapNotNull { c -> layout.rectOf(c)?.let { c to it } }
            )
        }

        // Ukuran acuan render. Berubah HANYA bila ukuran area berubah jauh (rotasi dst). Tampil/sembunyi panel atau tombol bawah
        // tidak mengubahnya -> tidak ada render ulang, panel tidak pernah "hilang" menunggu gambar baru.
        // renderScale (zoom floating) ikut menentukan ukuran render: berubah langkah -> render ulang pada ukuran baru.
        var target by remember(skin) { mutableStateOf(IntSize((renderW * rs).roundToInt(), (hPx * rs).roundToInt())) }
        var targetScale by remember(skin) { mutableStateOf(rs) }
        LaunchedEffect(skin, renderW, hPx, rs) {
            val needW = renderW * rs
            val needH = hPx * rs
            if (rs != targetScale || !SkinRenderCache.fits(target.width, target.height, needW, needH)) {
                target = IntSize(needW.roundToInt(), needH.roundToInt())
                targetScale = rs
            }
        }

        var layers by remember(skin) { mutableStateOf<LayerImages?>(null) }
        LaunchedEffect(skin, visiblePads, target) {
            if (target.width < 2 || target.height < 2) return@LaunchedEffect
            val spec = RenderSpec(target.width, target.height)

            // 1) cache disk: sudah pernah dirender -> tinggal memuat gambar (cepat)
            val cached = withContext(Dispatchers.IO) { runCatching { SkinRenderCache.load(appContext, skin, visiblePads, spec) }.getOrNull() }
            if (cached != null) {
                layers = LayerImages(
                    cached.core.base.asImageBitmap(),
                    cached.core.rotary.mapValues { it.value.asImageBitmap() },
                    cached.active.mapValues { it.value.asImageBitmap() }
                )
                return@LaunchedEffect
            }

            // 2) render INTI (seluruh skin + knob): skin langsung tampil begitu ini selesai
            val core = withContext(Dispatchers.Default) {
                try {
                    SkinRenderer.buildCore(skin, spec, visiblePads)
                } catch (e: Throwable) {
                    Log.e(TAG, "render gagal", e)
                    renderError = "${e.javaClass.simpleName}: ${e.message}"
                    null
                }
            }
            if (core == null) {
                // Render ulang beresolusi tinggi (zoom) gagal sementara gambar lama masih tampil: JANGAN jatuhkan skin,
                // cukup minta pemanggil turun resolusi.
                if (rs > 1f && layers != null) latestScaleFailed() else renderFailed = true
                return@LaunchedEffect
            }
            val activeMap = mutableStateMapOf<String, ImageBitmap>()
            // Selama render ulang, pakai dulu gambar state-tekan yang lama (resolusi lebih rendah) -> tidak ada kotak putih.
            layers?.active?.let { activeMap.putAll(it) }
            layers = LayerImages(
                core.base.asImageBitmap(),
                core.rotary.mapValues { it.value.asImageBitmap() },
                activeMap
            )

            // 3) state tekan pad/tombol, satu per satu di latar belakang (sebelum siap: highlight bawaan)
            val collected = HashMap<String, Bitmap>()
            val detailsOk = withContext(Dispatchers.Default) {
                try {
                    SkinRenderer.buildDetails(skin, spec, visiblePads, { isActive }) { id, bmp ->
                        collected[id] = bmp
                        activeMap[id] = bmp.asImageBitmap()
                    }
                    true
                } catch (e: Throwable) {
                    // mis. memori habis di resolusi tinggi: skin tetap jalan (state tekan lama / highlight bawaan)
                    Log.w(TAG, "state tekan gagal dirender: ${e.message}")
                    false
                }
            }

            // 4) simpan ke cache disk untuk pembukaan berikutnya (dilewati bila dibatalkan di tengah jalan atau tidak lengkap)
            if (detailsOk) withContext(Dispatchers.IO) { runCatching { SkinRenderCache.save(appContext, skin, visiblePads, spec, core, collected) } }
        }
        LaunchedEffect(renderFailed) { if (renderFailed) latestFatal("render skin gagal (${renderError ?: "?"})") }

        // Layar tunggu polos sampai gambar skin siap, lalu fade-in. Tanpa menu/kontrol apa pun.
        val ready = layers != null
        val fade by animateFloatAsState(if (ready) 1f else 0f, tween(250), label = "skin_fade")

        // Lapor posisi tombol LOAD skin (untuk tutorial) hanya setelah skin benar-benar tampil dan panelnya terlihat.
        val loadCtrl: SkinControl? = null
        LaunchedEffect(ready, layout, hostOrigin, loadCtrl) {
            val o = hostOrigin
            val r = loadCtrl?.let { layout.rectOf(it) }
            latestLoadBounds(
                if (ready && o != null && r != null) Rect(o.x + r.l, o.y + r.t, o.x + r.r, o.y + r.b) else null
            )
        }

        val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE } }

        if (!ready) SkinLoadingScreen()

        // Sentuhan dibaca lewat state terbaru, BUKAN lewat key pointerInput -> handler tidak pernah di-restart di tengah gestur.
        val curLayout by rememberUpdatedState(layout)
        val curHit by rememberUpdatedState(hitTester)
        val curDispatcher by rememberUpdatedState(dispatcher)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade }
                .pointerInput(skin) {
                    val tracked = HashMap<Long, Tracked>()
                    pressed.clear()   // handler baru = tidak ada jari yang sedang menekan
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (latestInputLocked()) {
                                // gestur zoom: kembalikan knob/slider yang sempat bergeser sebelum cubit terdeteksi, lalu lepaskan
                                for (tr in tracked.values) {
                                    val k = tr.control
                                    if (k.type == ControlType.ROTARY || k.type == ControlType.SLIDER) curDispatcher.setValue(k, tr.startValue)
                                }
                                if (tracked.isNotEmpty()) tracked.clear()
                                if (pressed.isNotEmpty()) pressed.clear()
                                continue
                            }
                            val lay = curLayout
                            for (ch in event.changes) {
                                val key = ch.id.value
                                val t = tracked[key]
                                if (ch.pressed && !ch.previousPressed) {
                                    // --- jari baru menyentuh layar (multi-touch: tiap jari dilacak sendiri) ---
                                    val c = curHit.hit(ch.position.x, ch.position.y, density)
                                    if (c != null) {
                                        val tr = Tracked(c)
                                        tracked[key] = tr
                                        pressed[c.id] = (pressed[c.id] ?: 0) + 1
                                        if (c.type == ControlType.ROTARY) {
                                            val r = c.rotary
                                            val z = lay.zone
                                            if (r != null && lay.rectOf(c) != null) {
                                                tr.lastAngle = angleDeg(ch.position.x - z.x(r.cx), ch.position.y - z.y(r.cy))
                                                tr.lastY = ch.position.y
                                                tr.knobValue = latestUi.values[c.function] ?: r.defaultValue
                                            }
                                            tr.startValue = tr.knobValue
                                        } else if (c.type == ControlType.SLIDER) {
                                            tr.startValue = latestUi.values[c.function] ?: 0f
                                            val sv = sliderValue(c, lay, ch.position.x, ch.position.y)
                                            if (sv != null) { tr.knobValue = sv; curDispatcher.setValue(c, sv) }
                                        } else if (c.trigger == Trigger.DOWN) {
                                            curDispatcher.trigger(c)
                                        }
                                        ch.consume()
                                    }
                                } else if (t != null && !ch.pressed) {
                                    // --- jari diangkat ---
                                    val c = t.control
                                    val left = (pressed[c.id] ?: 1) - 1
                                    if (left <= 0) pressed.remove(c.id) else pressed[c.id] = left
                                    if (c.type != ControlType.ROTARY && c.type != ControlType.SLIDER && c.trigger == Trigger.TAP) {
                                        // tombol: dieksekusi hanya bila diangkat di target yang sama
                                        val up = curHit.hit(ch.position.x, ch.position.y, density)
                                        if (up?.id == c.id) curDispatcher.trigger(c)
                                    }
                                    tracked.remove(key)
                                    ch.consume()
                                } else if (t != null && ch.pressed) {
                                    // --- jari bergeser ---
                                    val c = t.control
                                    val r = c.rotary
                                    val z = lay.zone
                                    val rect = lay.rectOf(c)
                                    if (c.type == ControlType.ROTARY && r != null && rect != null) {
                                        val dx = ch.position.x - z.x(r.cx)
                                        val dy = ch.position.y - z.y(r.cy)
                                        val radius = min(rect.w, rect.h) / 2f
                                        val sweep = r.angleMax - r.angleMin
                                        if (hypot(dx, dy) > radius * 0.3f) {
                                            // gestur melingkar: ikuti putaran jari di sekitar knob
                                            val a = angleDeg(dx, dy)
                                            var d = a - t.lastAngle
                                            while (d > 180f) d -= 360f
                                            while (d < -180f) d += 360f
                                            t.lastAngle = a
                                            if (sweep != 0f) t.knobValue = (t.knobValue + d / sweep).coerceIn(0f, 1f)
                                        } else {
                                            // gestur vertikal (jari dekat pusat): geser atas = naik
                                            t.knobValue = (t.knobValue + (t.lastY - ch.position.y) / (160f * density)).coerceIn(0f, 1f)
                                            t.lastAngle = angleDeg(dx, dy)
                                        }
                                        t.lastY = ch.position.y
                                        curDispatcher.setValue(c, t.knobValue)
                                    } else if (c.type == ControlType.SLIDER) {
                                        val sv = sliderValue(c, lay, ch.position.x, ch.position.y)
                                        if (sv != null) { t.knobValue = sv; curDispatcher.setValue(c, sv) }
                                    }
                                    ch.consume()
                                }
                            }
                            // Jaring pengaman: semua jari sudah terangkat -> tidak boleh ada kontrol yang masih "ditekan".
                            if (event.changes.none { it.pressed }) {
                                tracked.clear()
                                pressed.clear()
                            }
                        }
                    }
                }
        ) {
            val imgs = layers ?: return@Canvas
            fun offsetOf(r: PxRect) = IntOffset(r.l.roundToInt(), r.t.roundToInt())
            fun sizeOf(r: PxRect) = IntSize(max(1, r.w.roundToInt()), max(1, r.h.roundToInt()))

            // 1) dasar: SATU gambar skin di-stretch memenuhi area. Panel disembunyikan -> hanya bagian zona pad (potongan atas gambar).
            val area = layout.zone.dst
            val full = layout.full
            val reg = layout.region
            val bw = imgs.base.width
            val bh = imgs.base.height
            val srcTop = (((reg.y - full.y) / full.h) * bh).roundToInt().coerceIn(0, bh - 1)
            val srcH = ((reg.h / full.h) * bh).roundToInt().coerceIn(1, bh - srcTop)
            drawImage(
                imgs.base, srcOffset = IntOffset(0, srcTop), srcSize = IntSize(bw, srcH),
                dstOffset = offsetOf(area), dstSize = sizeOf(area), filterQuality = FilterQuality.Medium
            )

            // 2) state tekan / kedip
            for (c in skin.controls) {
                val rect = layout.rectOf(c) ?: continue   // null = kontrol tidak tampil (panel disembunyikan) / tanpa hit area
                when (c.type) {
                    ControlType.PAD, ControlType.BUTTON, ControlType.TOGGLE -> {
                        if (c.type == ControlType.PAD && c.index > visiblePads) continue
                        val held = if (c.type == ControlType.TOGGLE) (if (ui.toggles[c.function] == true) 1f else 0f) else if ((pressed[c.id] ?: 0) > 0) 1f else 0f
                        val flash = if (c.type == ControlType.PAD) padFlash(c.index) else 0f
                        val alpha = max(held, flash).coerceIn(0f, 1f)
                        if (alpha <= 0.01f) continue
                        val img = imgs.active[c.id]
                        if (img != null) {
                            drawImage(img, dstOffset = offsetOf(rect), dstSize = sizeOf(rect), alpha = alpha, filterQuality = FilterQuality.Medium)
                        } else {
                            // State tekan belum siap (masih dirender di latar belakang) / skin tanpa state active:
                            // highlight bawaan (kotak putih transparan).
                            val cr = if (c.type == ControlType.PAD) 4f else 3f * layout.zone.sx
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.30f * alpha),
                                topLeft = Offset(rect.l, rect.t),
                                size = Size(rect.w, rect.h),
                                cornerRadius = CornerRadius(cr, cr)
                            )
                        }
                    }
                    ControlType.ROTARY -> {
                        val img = imgs.rotary[c.id] ?: continue
                        val r = c.rotary ?: continue
                        val hit = c.hit ?: continue
                        val z = layout.zone
                        // Knob digambar UTUH lalu di-stretch bersama skin: putar dulu (di ruang bitmap yang bulat), baru di-stretch.
                        // MENIMPA rotate(..) bawaan SVG: sudut = min + (max-min) * nilai.
                        val angle = r.angleMin + (r.angleMax - r.angleMin) * (ui.values[c.function] ?: r.defaultValue).coerceIn(0f, 1f)
                        val m = img.width / hit.w                    // piksel bitmap per unit SVG
                        val offX = ((hit.x - r.cx) * m).roundToInt() // pojok kiri-atas gambar relatif ke pusat putar
                        val offY = ((hit.y - r.cy) * m).roundToInt()
                        withTransform({
                            translate(z.x(r.cx), z.y(r.cy))
                            scale(z.sx / m, z.sy / m, Offset.Zero)
                            rotate(angle, Offset.Zero)
                        }) {
                            drawImage(img, dstOffset = IntOffset(offX, offY), filterQuality = FilterQuality.Medium)
                        }
                    }
                    ControlType.SLIDER -> {
                        // Handle slider: gambar handle digeser dari posisi nilai 0 ke nilai sekarang, ikut di-stretch bersama skin.
                        val img = imgs.rotary[c.id] ?: continue
                        val s = c.slider ?: continue
                        val v = (ui.values[c.function] ?: s.defaultValue).coerceIn(0f, 1f)
                        val hb = s.handleBox
                        val pr = layout.zone.rect(SvgRect(hb.x + (s.x2 - s.x1) * v, hb.y + (s.y2 - s.y1) * v, hb.w, hb.h))
                        drawImage(img, dstOffset = offsetOf(pr), dstSize = sizeOf(pr), filterQuality = FilterQuality.Medium)
                    }
                    else -> {}
                }
            }

            // 2b) lampu indikator pad: gambar state "menyala" di atas dasar (dasar menampilkan lampu redup)
            for (ind in skin.indicators) {
                if ((ind.meter == null && ind.pad > visiblePads) || !layout.visible(ind.rect)) continue
                // lampu meter: nyala bila level >= ambang; lampu pad: kecerahan dari loop lampu
                val a = if (ind.meter != null) { if ((ui.meters[ind.meter] ?: 0f) >= ind.level) 1f else 0f } else (lamp[ind.pad] ?: 0f).coerceIn(0f, 1f)
                if (a <= 0.01f) continue
                val r = layout.zone.rect(ind.rect)
                val img = imgs.active[ind.layerKey]
                if (img != null) {
                    drawImage(img, dstOffset = offsetOf(r), dstSize = sizeOf(r), alpha = a, filterQuality = FilterQuality.Medium)
                } else {
                    // Gambar lampu belum siap (masih dirender di latar belakang): kotak terang bawaan.
                    drawRoundRect(
                        color = Color(0xFFFFB8A0).copy(alpha = 0.85f * a),
                        topLeft = Offset(r.l, r.t),
                        size = Size(r.w, r.h),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }
            }

            // 3) teks display (LCD) - teks placeholder SVG sudah dibuang dari base. Hanya bila bagian itu sedang tampil.
            for (c in skin.controls) {
                val d = c.display ?: continue
                if (d.lines.isEmpty() || !layout.visible(d.rect)) continue
                val lines = d.lines.map { DisplayTemplate.render(it, ui) }
                val rect = layout.zone.rect(d.rect)
                val padX = rect.w * 0.03f
                val boxW = rect.w - 2 * padX
                val lineH = rect.h / lines.size
                val maxChars = max(1, lines.maxOf { it.length })
                val size = min(lineH * 0.82f, boxW / (0.6f * maxChars))
                textPaint.textSize = size
                textPaint.color = d.textColor
                val left = rect.l + padX
                val top = rect.t
                drawContext.canvas.nativeCanvas.apply {
                    lines.forEachIndexed { i, line -> drawText(line, left, top + lineH * (i + 1) - lineH * 0.2f, textPaint) }
                }
            }
        }
    }
}

/** Nilai slider 0..1 dari posisi jari (layar), diproyeksikan ke garis (x1,y1)->(x2,y2) yang sudah dipetakan ke layar. */
private fun sliderValue(c: SkinControl, lay: SkinLayout, px: Float, py: Float): Float? {
    val s = c.slider ?: return null
    val z = lay.zone
    val ax = z.x(s.x1); val ay = z.y(s.y1)
    val dx = z.x(s.x2) - ax; val dy = z.y(s.y2) - ay
    val len2 = dx * dx + dy * dy
    if (len2 < 1f) return null
    return (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
}

/** Sudut (derajat) vektor (dx, dy) di koordinat layar (y ke bawah): searah jarum jam = positif. */
private fun angleDeg(dx: Float, dy: Float): Float = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
