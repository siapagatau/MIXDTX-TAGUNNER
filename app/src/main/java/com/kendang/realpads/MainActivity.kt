package com.kendang.realpads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kendang.realpads.skin.MixerBridge
import com.kendang.realpads.skin.SkinBinding
import com.kendang.realpads.skin.SkinHost
import com.kendang.realpads.skin.SkinLoadingScreen
import com.kendang.realpads.skin.SkinManager
import com.kendang.realpads.skin.SkinPickerDialog
import com.kendang.realpads.skin.SkinUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var hasMicPermission by mutableStateOf(false)

    // True kalau user menekan switch Floating sebelum izin mic ada - begitu
    // izin diberikan, alur Floating dilanjutkan otomatis.
    private var pendingFloating = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted && pendingFloating) requestFloatingMode()
        pendingFloating = false
        if (pendingSysReverb) {
            pendingSysReverb = false
            if (granted) requestSysReverb() else finishSysReverbRequest()
        }
    }

    // Panel floating butuh 2 izin: notifikasi (Android 13+, wajib buat
    // foreground service-nya) dan "gambar di atas app lain". Alurnya:
    // minta notifikasi dulu (kalau perlu) -> baru cek/minta izin overlay ->
    // baru nyalain FloatingWindowService. Ini SAMA SEKALI gak nyentuh
    // AudioEngine, jadi gak ada risiko nambah delay mic.
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> proceedToOverlayCheck() }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (Settings.canDrawOverlays(this)) startFloatingService(this) }

    // REVERB AUDIO (SysReverbService): minta izin "rekam layar" sistem (MediaProjection) - dipakai HANYA untuk menangkap
    // audio app lain. Hasilnya diteruskan ke service; izin ini sekali pakai, jadi diminta tiap kali dinyalakan.
    // Android 14+: langsung "seluruh layar" (tanpa pilihan satu app), jadi pengguna cukup menekan Mulai.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            SysReverbService.start(this, result.resultCode, data)
        } else {
            Toast.makeText(this, "Izin ditolak", Toast.LENGTH_SHORT).show()
        }
        finishSysReverbRequest()
    }

    private var pendingSysReverb = false

    // True kalau permintaan datang dari panel floating (activity dibuka sebentar hanya untuk izin) -> setelah izin
    // selesai, kembali ke app yang tadi dipakai.
    private var returnAfterSysReverb = false

    private fun finishSysReverbRequest() {
        if (returnAfterSysReverb) {
            returnAfterSysReverb = false
            moveTaskToBack(true)
        }
    }

    private fun handleLaunchIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_REQUEST_SYS_REVERB, false) == true) {
            i.removeExtra(EXTRA_REQUEST_SYS_REVERB)
            returnAfterSysReverb = true
            requestSysReverb()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    fun requestSysReverb() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Reverb Audio butuh Android 10 ke atas", Toast.LENGTH_LONG).show()
            finishSysReverbRequest()
            return
        }
        // AudioRecord (capture) wajib izin Mikrofon.
        if (!hasMicPermission) {
            pendingSysReverb = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    fun requestFloatingMode() {
        // Foreground service bertipe microphone WAJIB punya izin RECORD_AUDIO
        // (Android 14+ langsung crash kalau belum), jadi minta dulu.
        if (!hasMicPermission) {
            pendingFloating = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedToOverlayCheck()
    }

    private fun proceedToOverlayCheck() {
        if (Settings.canDrawOverlays(this)) {
            startFloatingService(this)
        } else {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // Landscape penuh: sembunyikan status/navigation bar (muncul sementara bila digeser dari tepi).
    private fun applyImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()   // dialog/izin bisa mengembalikan bar -> sembunyikan lagi
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seperti DTX: konten edge-to-edge, jarak aman diatur sendiri lewat safeDrawingPadding.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Output mic->speaker memakai Usage::Media (lihat openStreams() di
        // KaraokeEngine.cpp), jadi tombol fisik volume harus ke STREAM_MUSIC.
        // Dulu diarahkan ke STREAM_ALARM (sisa percobaan lama) -> tombol
        // malah menaikkan volume alarm, bukan suara mic.
        volumeControlStream = AudioManager.STREAM_MUSIC

        ThemeState.load(this)

        hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                MixerScreen(
                    hasMicPermission = hasMicPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onRequestFloatingMode = { requestFloatingMode() },
                    onRequestSysReverb = { requestSysReverb() },
                )
            }
        }

        // Minta izin mic langsung saat app pertama kali dibuka.
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Visual sentuhan global (sama dengan APK DTX): overlay di atas seluruh konten, tidak menangani input.
        val fxOverlay = TouchFxOverlay(this)
        touchFxOverlay = fxOverlay
        addContentView(
            fxOverlay,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val appCtx = applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val loaded = TouchFxState.loadFromDisk(appCtx)
            android.os.Handler(android.os.Looper.getMainLooper()).post { TouchFxState.applyLoaded(loaded) }
        }

        handleLaunchIntent(intent)
    }

    private var touchFxOverlay: TouchFxOverlay? = null

    // Hanya MEMBACA event setelah jalur normal (kontrol/audio/UI) selesai - tidak mengubah,
    // menunda, atau mengonsumsi sentuhan.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        try { touchFxOverlay?.onTouch(ev) } catch (_: Throwable) {}
        return handled
    }

    override fun onDestroy() {
        super.onDestroy()
        // Lepas engine hanya bila panel floating tidak sedang memakainya (floating tetap boleh jalan tanpa layar utama).
        if (!FloatingWindowService.running.value) AudioEngine.nativeShutdown()
    }
}

const val EXTRA_REQUEST_SYS_REVERB = "request_sys_reverb"

/** Dari panel floating: buka app sebentar untuk meminta izin rekam layar Reverb Audio, lalu kembali otomatis. */
fun openSysReverbRequest(context: Context) {
    val i = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_REQUEST_SYS_REVERB, true)
    runCatching { context.startActivity(i) }
}

fun startFloatingService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, FloatingWindowService::class.java))
}

fun stopFloatingService(context: Context) {
    context.stopService(Intent(context, FloatingWindowService::class.java))
}

// Knob EQ 0..1 -> gain asli -15..+15 dB (0.5 = tengah = 0dB/flat), persis
// rentang EQ channel strip Yamaha MG yang jadi acuan fitur ini.
fun eqKnobToDb(knob: Float): Float = (knob - 0.5f) * 30f
fun dbToEqKnob(db: Float): Float = db / 30f + 0.5f

// ---------------------------------------------------------------------------
// Kelompok mixer: MIC, REVERB, ECHO, EQ MIC, EQ AUDIO. Semua kelompok tampil SEKALIGUS (tanpa halaman) lewat MixerGrid (MixerPanel.kt),
// tiap kelompok punya judul + saklar ON/OFF sendiri. Nilai berkunci nama function -> sinkron dengan skin & panel floating.
// ---------------------------------------------------------------------------
class MixControl(val fn: String, val label: String, val bipolar: Boolean = false)   // bipolar = knob EQ (tengah = 0 dB)
class MixGroup(val title: String, val toggleFn: String, val controls: List<MixControl>)

val MixGroups: List<MixGroup> = listOf(
    MixGroup("MIC", "mic.toggle", listOf(MixControl("mic.volume", "VOLUME"), MixControl("mic.gate", "GATE"))),
    MixGroup(
        "REVERB", "reverb.toggle",
        listOf(
            MixControl("reverb.wet", "WET"), MixControl("reverb.dry", "DRY"), MixControl("reverb.width", "WIDTH"),
            MixControl("reverb.damp", "DAMP"), MixControl("reverb.room", "ROOM"), MixControl("reverb.cess", "CESS")
        )
    ),
    MixGroup(
        "ECHO", "echo.toggle",
        listOf(MixControl("echo.delay", "DELAY"), MixControl("echo.feedback", "FEEDBACK"), MixControl("echo.mix", "MIX"))
    ),
    MixGroup(
        "EQ MIC", "eqmic.toggle",
        listOf(MixControl("eqmic.low", "LOW", true), MixControl("eqmic.mid", "MID", true), MixControl("eqmic.high", "HIGH", true))
    ),
    MixGroup(
        "EQ AUDIO", "eqaudio.toggle",
        listOf(
            MixControl("eqaudio.gain", "GAIN", true), MixControl("eqaudio.sub", "SUB", true), MixControl("eqaudio.low", "LOW", true),
            MixControl("eqaudio.mid", "MID", true), MixControl("eqaudio.high", "HIGH", true)
        )
    ),
)

fun formatMixValue(fn: String, v: Float): String =
    if (fn.startsWith("eqmic.") || fn.startsWith("eqaudio.")) {
        val d = eqKnobToDb(v).roundToInt()
        if (d > 0) "+$d dB" else "$d dB"
    } else "${(v * 100).roundToInt()}%"

@Composable
fun MixerScreen(
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRequestFloatingMode: () -> Unit = {},
    onRequestSysReverb: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val floatingOn by FloatingWindowService.running.collectAsState()
    var showTheme by remember { mutableStateOf(false) }
    var showSkinPicker by remember { mutableStateOf(false) }
    var classicBarVisible by remember { mutableStateOf(SkinManager.uiFlag(context, "classic_bar_visible", true)) }
    var skinBarVisible by remember { mutableStateOf(SkinManager.uiFlag(context, "bar_visible", true)) }
    // Gulir bersama: mixer (atas) + REVERB AUDIO + EQUALIZER 31 BAND (bawah, mode equalizer). Dipegang di sini supaya tombol
    // lompat di tab kanan bisa menggulirnya.
    val mainScroll = rememberScrollState()

    LaunchedEffect(Unit) { SkinManager.ensureLoaded(context) }
    // Skin gagal dimuat/dirender -> jatuh ke tampilan klasik; tampilkan alasannya (seperti APK DTX).
    val skinFailedReason = (SkinManager.state as? SkinManager.State.Failed)?.reason
    LaunchedEffect(skinFailedReason) {
        if (skinFailedReason != null) Toast.makeText(context, "Skin gagal dipakai: $skinFailedReason", Toast.LENGTH_LONG).show()
    }

    // Background gambar (Tema -> Background): dibaca & disalin ke internal storage (sama dengan DTX).
    LaunchedEffect(Unit) {
        val loadedBg = withContext(Dispatchers.IO) { BackgroundState.loadFromDisk(context) }
        BackgroundState.applyLoaded(loadedBg)
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                val decoded = bytes?.let { b -> withContext(Dispatchers.IO) { BackgroundState.decodeAndSave(context, b) } }
                if (decoded != null) BackgroundState.applyImage(context, decoded)
                else Toast.makeText(context, "Gagal muat gambar background", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val touchFxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.let { TouchFxState.decodeAndSave(context, it) }
                }
                if (bmp != null) TouchFxState.applyImage(bmp)
                else Toast.makeText(context, "Gagal muat gambar sentuhan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val skinUi = remember { SkinUiState() }
    val bridge = remember { MixerBridge(context.applicationContext, skinUi) }
    val onFloat = { if (floatingOn) stopFloatingService(context) else onRequestFloatingMode() }
    val onSysReverbToggle = {
        if (SysReverbService.running.value) SysReverbService.stop(context) else onRequestSysReverb()
    }
    SideEffect {
        bridge.onSysReverb = onSysReverbToggle
        bridge.hasMicPermission = { hasMicPermission }
        bridge.requestMicPermission = onRequestPermission
        bridge.onFloating = onFloat
        bridge.onTheme = { showTheme = true }
        bridge.onSkinPick = { showSkinPicker = true }
    }
    SkinBinding(bridge, hasMicPermission)
    val eqMode = Eq31Ui.s.mode
    val onEqMode = { Eq31Ui.setMode(context, !Eq31Ui.s.mode) }
    // Mode kompresor: bagian KOMPRESOR tampil di bawah mixer dan DI ATAS equalizer.
    CompUi.ensureLoaded(context)
    val compMode = CompUi.s.mode
    val onCompMode = { CompUi.setMode(context, !CompUi.s.mode) }
    // Mode FX (taGunner): bagian FX di bawah kompresor, di atas equalizer.
    FxUi.ensureLoaded(context)
    val fxMode = FxUi.s.mode
    val onFxMode = { FxUi.setMode(context, !FxUi.s.mode) }
    val extraMode = eqMode || compMode || fxMode

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val skinState = SkinManager.state
        val activeSkin = (skinState as? SkinManager.State.Active)?.skin
        val loadingSkin = skinState is SkinManager.State.Loading && SkinManager.selectedId(context) != SkinManager.CLASSIC_ID
        val showActionStrip = if (activeSkin == null) classicBarVisible else skinBarVisible
        // Susunan: [grid tunggal semua kontrol + strip tombol bawah] [tab tipis].
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (activeSkin != null && extraMode) {
                        // MODE EQUALIZER + skin: skin mixer mengisi layar seperti biasa, bagian EQUALIZER MENYAMBUNG tepat di bawahnya
                        // (tanpa celah) - gulir ke bawah untuk menuju equalizer.
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val viewportH = maxHeight
                            Column(Modifier.fillMaxSize().verticalScroll(mainScroll)) {
                                Box(Modifier.fillMaxWidth().height(viewportH)) {
                                    SkinHost(
                                        skin = activeSkin,
                                        actions = bridge,
                                        ui = skinUi,
                                        onFatal = { SkinManager.reportRenderFailure(it) },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                if (compMode) CompressorSection(skinUi, bridge, 1f, Modifier.fillMaxWidth())
                                if (fxMode) FxSection(1f, Modifier.fillMaxWidth())
                                if (eqMode) EqualizerSection(skinUi, bridge, 1f, Modifier.fillMaxWidth())
                            }
                        }
                    } else if (activeSkin != null) {
                        SkinHost(
                            skin = activeSkin,
                            actions = bridge,
                            ui = skinUi,
                            onFatal = { SkinManager.reportRenderFailure(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (loadingSkin) {
                        SkinLoadingScreen()
                    } else {
                        val bgImage = BackgroundState.imageBitmap
                        if (bgImage != null) {
                            Image(
                                bitmap = bgImage,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                alpha = BackgroundState.opacity,
                                modifier = Modifier.matchParentSize()
                            )
                        }
                        MixerGrid(
                            ui = skinUi,
                            bridge = bridge,
                            hasBackgroundImage = bgImage != null,
                            opacity = if (bgImage != null) BackgroundState.padOpacity else 1f,
                            modifier = Modifier.fillMaxSize(),
                            onSysReverbToggle = onSysReverbToggle,
                            scroll = mainScroll
                        )
                    }
                }
                if (showActionStrip) {
                    MixActionStrip(
                        floatingOn = floatingOn,
                        onFloating = onFloat,
                        eqMode = eqMode,
                        onEqMode = onEqMode,
                        compMode = compMode,
                        onCompMode = onCompMode,
                        fxMode = fxMode,
                        onFxMode = onFxMode,
                        onTheme = { showTheme = true },
                        onSkin = { showSkinPicker = true }
                    )
                }
            }

            BarToggleTab(
                barVisible = showActionStrip,
                onToggle = {
                    if (activeSkin != null) {
                        skinBarVisible = !skinBarVisible
                        SkinManager.setUiFlag(context, "bar_visible", skinBarVisible)
                    } else {
                        classicBarVisible = !classicBarVisible
                        SkinManager.setUiFlag(context, "classic_bar_visible", classicBarVisible)
                    }
                },
                eqMode = extraMode,
                eqAtBottom = mainScroll.maxValue > 0 && mainScroll.value >= mainScroll.maxValue / 2,
                onEqJump = {
                    scope.launch {
                        if (mainScroll.value >= mainScroll.maxValue / 2) mainScroll.animateScrollTo(0) else mainScroll.animateScrollTo(mainScroll.maxValue)
                    }
                },
                modifier = Modifier.width(22.dp).fillMaxHeight()
            )
        }

        if (showSkinPicker) SkinPickerDialog(onDismiss = { showSkinPicker = false })

        if (showTheme) {
            ColorPickerDialog(
                initial = ThemeState.accentColor,
                backgroundImage = BackgroundState.imageBitmap,
                backgroundOpacity = BackgroundState.opacity,
                padOpacity = BackgroundState.padOpacity,
                onDismiss = { showTheme = false },
                onConfirm = { color ->
                    ThemeState.set(context, color)
                    showTheme = false
                },
                onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                onClearBackground = { BackgroundState.clearImage(context) },
                onOpacityChange = { BackgroundState.setOpacity(context, it) },
                onPadOpacityChange = { BackgroundState.setPadOpacity(context, it) },
                touchFxPreview = TouchFxState.preview,
                touchFxSizeDp = TouchFxState.sizeDp,
                onPickTouchFx = { touchFxPicker.launch(arrayOf("image/*")) },
                onClearTouchFx = { TouchFxState.clearImage(context) },
                onTouchFxSizeChange = { TouchFxState.setSize(context, it) },
                touchFxFadeMs = TouchFxState.fadeMs,
                onTouchFxFadeChange = { TouchFxState.setFade(context, it) }
            )
        }
    }
}

// Panel mixer (MixerGrid) ada di MixerPanel.kt - dipakai SAMA oleh layar utama ini dan panel floating.

// Strip tombol bawah: gaya tombol DTX (PanelColor, sudut 4dp, 12sp), teks putih netral (tidak ikut warna tema). FLOATING hijau saat aktif.
@Composable
fun MixActionStrip(
    floatingOn: Boolean,
    onFloating: () -> Unit,
    eqMode: Boolean,
    onEqMode: () -> Unit,
    compMode: Boolean,
    onCompMode: () -> Unit,
    fxMode: Boolean,
    onFxMode: () -> Unit,
    onTheme: () -> Unit,
    onSkin: () -> Unit
) {
    val padding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CtrlPanelColor)
            .padding(vertical = 5.dp, horizontal = GroupOuterPaddingH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GroupItemSpacing)
    ) {
        Button(
            onClick = onFloating,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (floatingOn) IndicatorGreen else PanelColor,
                contentColor = if (floatingOn) IndicatorGreenText else Color.White
            ),
            shape = shape,
            contentPadding = padding,
            modifier = Modifier.weight(1f).height(36.dp)
        ) { Text("FLOATING", fontWeight = FontWeight.SemiBold, fontSize = AppTextSize, maxLines = 1) }
        // Mode kompresor: bagian KOMPRESOR (gaya Poweramp) muncul di bawah mixer, di atas equalizer. Hijau saat aktif.
        Button(
            onClick = onCompMode,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (compMode) IndicatorGreen else PanelColor,
                contentColor = if (compMode) IndicatorGreenText else Color.White
            ),
            shape = shape,
            contentPadding = padding,
            modifier = Modifier.weight(1f).height(36.dp)
        ) { Text("KOMPRESOR", fontWeight = FontWeight.SemiBold, fontSize = AppTextSize, maxLines = 1) }
        // Mode FX (gaya taGunner): SUBSONIC, SUB BASS, WIDENER, TUBE DRIVE, 8D PAN, PARAMETRIC, PRESET. Hijau saat aktif.
        Button(
            onClick = onFxMode,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (fxMode) IndicatorGreen else PanelColor,
                contentColor = if (fxMode) IndicatorGreenText else Color.White
            ),
            shape = shape,
            contentPadding = padding,
            modifier = Modifier.weight(1f).height(36.dp)
        ) { Text("FX", fontWeight = FontWeight.SemiBold, fontSize = AppTextSize, maxLines = 1) }
        // Mode equalizer: bagian EQUALIZER 31 BAND muncul menyatu di bawah mixer. Hijau saat aktif; ketuk lagi = kembali ke mixer saja.
        Button(
            onClick = onEqMode,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (eqMode) IndicatorGreen else PanelColor,
                contentColor = if (eqMode) IndicatorGreenText else Color.White
            ),
            shape = shape,
            contentPadding = padding,
            modifier = Modifier.weight(1f).height(36.dp)
        ) { Text("EQUALIZER", fontWeight = FontWeight.SemiBold, fontSize = AppTextSize, maxLines = 1) }
        Button(
            onClick = onTheme,
            colors = ButtonDefaults.buttonColors(containerColor = PanelColor, contentColor = Color.White),
            shape = shape,
            contentPadding = padding,
            modifier = Modifier.weight(1f).height(36.dp)
        ) { Text("TEMA", fontWeight = FontWeight.SemiBold, fontSize = AppTextSize, maxLines = 1) }
        Button(
            onClick = onSkin,
            colors = ButtonDefaults.buttonColors(containerColor = PanelColor, contentColor = Color.White),
            shape = shape,
            contentPadding = padding,
            modifier = Modifier.weight(1f).height(36.dp)
        ) { Text("SKIN", fontWeight = FontWeight.SemiBold, fontSize = AppTextSize, maxLines = 1) }
    }
}

// Tab tipis di tepi kanan: sembunyi/tampil strip tombol bawah. Saat mode equalizer aktif ada segmen kedua (hijau):
// lompat ke bagian EQUALIZER (di bawah) / kembali ke mixer (di atas) - berguna bila skin hampir seluruhnya berisi kontrol sehingga susah digulir dengan jari.
@Composable
private fun BarToggleTab(
    barVisible: Boolean,
    onToggle: () -> Unit,
    eqMode: Boolean,
    eqAtBottom: Boolean,
    onEqJump: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(CtrlPanelColor)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(onToggle) { detectTapGestures(onTap = { onToggle() }) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (barVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (barVisible) "Sembunyikan tombol bawah" else "Tampilkan tombol bawah",
                tint = if (barVisible) PadTextColor else TextDim,
                modifier = Modifier.size(16.dp)
            )
        }
        if (eqMode) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(onEqJump) { detectTapGestures(onTap = { onEqJump() }) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (eqAtBottom) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                    contentDescription = if (eqAtBottom) "Kembali ke mixer" else "Ke equalizer",
                    tint = IndicatorGreen,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
