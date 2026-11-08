package com.kendang.realpads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import com.kendang.realpads.skin.SkinManager
import com.kendang.realpads.skin.SkinZones
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Service yang nampilin panel kontrol mic sebagai jendela FLOATING (nongol di
// atas app lain), bisa DIGESER, di-RESIZE, dan di-MINIMIZE jadi bulatan.
// Isi panel: KaraokeControls (Compose) yang SAMA dengan layar utama APK -
// Mic, Volume, Gate, EQ, Reverb, Echo - jadi tampilan & kontrolnya live
// persis seperti saat tidak floating.
//
// Soal delay mic: service ini cuma manggil fungsi start/stop/set-parameter
// yang SAMA dengan MainActivity (lihat AudioEngine.kt). Jalur mic->speaker di
// native C++ gak disentuh - yang ditambahin di sini murni UI overlay.
class FloatingWindowService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_mic_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.kendang.realpads.action.STOP_FLOATING"

        private const val DEFAULT_WIDTH_DP = 340
        private const val DEFAULT_HEIGHT_DP = 560
        private const val MIN_WIDTH_DP = 300
        private const val MIN_HEIGHT_DP = 240

        // Harus sama dengan ukuran di floating_bubble.xml
        private const val BUBBLE_DP = 64
        // Jarak aman dari tepi layar - jendela TIDAK PERNAH keluar layar,
        // jadi di pojokan pun tetap bisa dipegang & digeser.
        private const val EDGE_MARGIN_DP = 8

        // Status service (nyala/mati) - dibaca MainActivity buat switch
        // Floating, jadi switch-nya selalu nunjukin kondisi sebenarnya.
        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()
    }

    private lateinit var windowManager: WindowManager
    private var panelView: View? = null
    private var bubbleView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var snapAnimator: ValueAnimator? = null
    // Pengumpul flow milik panel yang sedang tampil (label JALUR / zoom). Dibatalkan saat panel dilepas supaya tidak menumpuk.
    private val panelJobs = mutableListOf<Job>()

    // Pemilik lifecycle buat ComposeView di jendela overlay (Service tidak
    // punya lifecycle UI sendiri, sedangkan Compose mewajibkannya).
    private var panelOwner: OverlayOwner? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        registerMicStateBusListener()
        registerSkinListener()
        _running.value = true
    }

    // Skin diganti di APK -> jendela floating menyesuaikan tinggi dengan proporsi skin baru (sekali per skin; ukuran
    // yang diatur manual user tidak ditimpa lagi untuk skin yang sama). Gambar skin sendiri berganti real time lewat Compose.
    private fun registerSkinListener() {
        serviceScope.launch {
            snapshotFlow { SkinManager.state }.distinctUntilChanged().collect { FloatingZoom.reset(); fitPanelToSkin() }
        }
    }

    private fun fitPanelToSkin() {
        val p = panelParams ?: return
        val v = panelView ?: return
        val skin = (SkinManager.state as? SkinManager.State.Active)?.skin ?: return
        val id = SkinManager.selectedId(this)
        val prefs = getSharedPreferences("floating_fit", Context.MODE_PRIVATE)
        if (prefs.getString("skin_id", null) == id) return
        prefs.edit().putString("skin_id", id).apply()
        val zone = SkinZones.fullZone(skin)
        if (zone.w <= 0f || zone.h <= 0f) return
        // Tinggi tetap di luar isi: header 44 + margin 2 + baris bawah 28 + padding atas/bawah 12; lebar: padding kiri/kanan 24.
        val chrome = dp(44 + 2 + 28 + 12)
        val contentW = (p.width - dp(24)).coerceAtLeast(1)
        val wanted = chrome + (contentW * zone.h / zone.w).roundToInt()
        val area = usableArea()
        p.height = wanted.coerceIn(min(dp(MIN_HEIGHT_DP), area.height()), max(dp(MIN_HEIGHT_DP), area.bottom - p.y))
        clampToScreen(p, p.width, p.height)
        runCatching { windowManager.updateViewLayout(v, p) }
        saveCurrentPanelGeometry(minimized = false)
    }

    private fun registerMicStateBusListener() {
        serviceScope.launch {
            MicStateBus.updates.collect { refreshMicUi() }
        }
    }

    // Warna bubble ikut status AudioEngine yang sebenarnya. (Isi panel sudah
    // ngurus dirinya sendiri lewat SettingsBus/MicStateBus di KaraokeControls.)
    private fun refreshMicUi() {
        val running = AudioEngine.nativeIsRunning()
        bubbleView?.let { bubble ->
            bubble.background = themedCircle(running)
            bubble.findViewById<ImageView>(R.id.bubble_icon)?.alpha = if (running) 1f else 0.6f
        }
    }

    // Bubble & panel mengikuti warna tema (sama dengan layar utama).
    private fun themedCircle(running: Boolean): android.graphics.drawable.GradientDrawable {
        val accent = ThemeState.uiAccent
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (running) accent.copy(alpha = 0.30f).toArgb() else CtrlPanelColor.toArgb())
            setStroke(dp(2), if (running) accent.toArgb() else PadLineColor.toArgb())
        }
    }

    private fun themedPanelBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(BackdropColor.toArgb())
            setStroke(dp(1), PadLineColor.toArgb())
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (panelView == null && bubbleView == null) {
            val geo = loadGeo()
            if (geo.minimized) showBubble(geo) else showPanel(geo)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        snapAnimator?.cancel()
        serviceScope.cancel()
        removeBubble()
        removePanel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Notifikasi foreground (wajib biar mic tetap boleh nyala pas di-bg)
    // ---------------------------------------------------------------------

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Panel Floating MIXJOS", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, FloatingWindowService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MIXJOS")
            .setContentText("Floating aktif")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(0, "Tutup", stopPending)
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    // ---------------------------------------------------------------------
    // Panel penuh
    // ---------------------------------------------------------------------

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // FLAG_LAYOUT_IN_SCREEN: koordinat x,y selalu relatif ke LAYAR PENUH, jadi
    // hitungan batas di clampToScreen() sama persis dengan posisi aslinya di
    // layar (sebelumnya meleset di tepi/pojok -> terasa "nyangkut").
    private val overlayFlags: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun loadGeo(): FloatingGeometry =
        FloatingPrefs.load(this, dp(DEFAULT_WIDTH_DP), dp(DEFAULT_HEIGHT_DP))

    private fun showPanel(geo: FloatingGeometry) {
        ThemeState.load(this)
        val view = LayoutInflater.from(this).inflate(R.layout.floating_panel, null)
        view.background = themedPanelBg()
        panelView = view

        val area = usableArea()
        val params = WindowManager.LayoutParams(
            geo.widthPx.coerceIn(min(dp(MIN_WIDTH_DP), area.width()), area.width()),
            geo.heightPx.coerceIn(min(dp(MIN_HEIGHT_DP), area.height()), area.height()),
            overlayType(),
            overlayFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = geo.x
            y = geo.y
        }
        clampToScreen(params, params.width, params.height)
        panelParams = params

        // Isi panel = KaraokeControls yang sama dengan layar utama APK.
        val owner = OverlayOwner()
        panelOwner = owner
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val host = view.findViewById<ComposeView>(R.id.compose_host)
        host.setContent { KaraokeFloatingBody(hasMicPermission = hasMic) }
        owner.start()

        windowManager.addView(view, params)

        val titleBar = view.findViewById<View>(R.id.title_bar)
        val resizeHandle = view.findViewById<View>(R.id.resize_handle)
        setupDrag(
            handle = titleBar, target = view, params = params,
            onTap = null,
            onRelease = { saveCurrentPanelGeometry(minimized = false) }
        )
        setupResize(resizeHandle, params, view)
        excludeFromSystemGestures(titleBar)
        excludeFromSystemGestures(resizeHandle)
        excludeFromSystemGestures(host)

        setupBottomButtons(view)

        view.findViewById<View>(R.id.btn_minimize).setOnClickListener { minimizeToBubble() }
        view.findViewById<View>(R.id.btn_close).setOnClickListener {
            saveCurrentPanelGeometry(minimized = false)
            stopSelf()
        }
    }

    // Tombol baris bawah: zoom skin (-, 1x, +).
    // Zoom hanya tampil saat skin aktif; label ikut keadaan sebenarnya lewat snapshotFlow / StateFlow.
    private fun setupBottomButtons(view: View) {
        val zoomBar = view.findViewById<View>(R.id.zoom_bar)
        val zoomLabel = view.findViewById<TextView>(R.id.btn_zoom_reset)

        view.findViewById<View>(R.id.btn_zoom_in).setOnClickListener { FloatingZoom.step(+1) }
        view.findViewById<View>(R.id.btn_zoom_out).setOnClickListener { FloatingZoom.step(-1) }
        zoomLabel.setOnClickListener { FloatingZoom.reset() }

        // Tombol EQ (mode equalizer): bagian EQUALIZER menyambung di bawah mixer. Hijau saat mode aktif; status dibagi dengan layar utama.
        Eq31Ui.ensureLoaded(this)
        val eqBtn = view.findViewById<TextView>(R.id.btn_eq_mode)
        eqBtn.setOnClickListener { Eq31Ui.setMode(this, !Eq31Ui.s.mode) }
        panelJobs += serviceScope.launch {
            snapshotFlow { Eq31Ui.s.mode }.distinctUntilChanged().collect { on ->
                eqBtn.setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill)
                eqBtn.setTextColor(if (on) 0xFF08200F.toInt() else 0xFFFFFFFF.toInt())
            }
        }

        // Tombol KOMP (mode kompresor): bagian KOMPRESOR di atas equalizer. Status dibagi dengan layar utama.
        CompUi.ensureLoaded(this)
        val compBtn = view.findViewById<TextView>(R.id.btn_comp_mode)
        compBtn.setOnClickListener { CompUi.setMode(this, !CompUi.s.mode) }
        panelJobs += serviceScope.launch {
            snapshotFlow { CompUi.s.mode }.distinctUntilChanged().collect { on ->
                compBtn.setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill)
                compBtn.setTextColor(if (on) 0xFF08200F.toInt() else 0xFFFFFFFF.toInt())
            }
        }

        // Tombol FX (mode FX): bagian FX di bawah kompresor. Status dibagi dengan layar utama.
        FxUi.ensureLoaded(this)
        val fxBtn = view.findViewById<TextView>(R.id.btn_fx_mode)
        fxBtn.setOnClickListener { FxUi.setMode(this, !FxUi.s.mode) }
        panelJobs += serviceScope.launch {
            snapshotFlow { FxUi.s.mode }.distinctUntilChanged().collect { on ->
                fxBtn.setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill)
                fxBtn.setTextColor(if (on) 0xFF08200F.toInt() else 0xFFFFFFFF.toInt())
            }
        }

        panelJobs += serviceScope.launch {
            snapshotFlow { SkinManager.state is SkinManager.State.Active }.distinctUntilChanged().collect { hasSkin ->
                zoomBar.visibility = if (hasSkin) View.VISIBLE else View.GONE
            }
        }
        panelJobs += serviceScope.launch {
            snapshotFlow { FloatingZoom.scale }.distinctUntilChanged().collect { sc ->
                zoomLabel.text = if (sc < 1.05f) "1×" else String.format(java.util.Locale.US, "%.1f×", sc)
            }
        }
    }

    private fun removePanel() {
        panelJobs.forEach { it.cancel() }
        panelJobs.clear()
        panelView?.let { v ->
            runCatching { v.findViewById<ComposeView>(R.id.compose_host)?.disposeComposition() }
            runCatching { windowManager.removeView(v) }
        }
        panelOwner?.destroy()
        panelOwner = null
        panelView = null
        panelParams = null
    }

    // ---------------------------------------------------------------------
    // Bubble (minimize)
    // ---------------------------------------------------------------------

    // Posisi awal bubble: pakai posisi bubble yang tersimpan; kalau belum ada
    // (data lama), tempel ke tepi layar di sisi tempat panel berada.
    private fun initialBubblePos(geo: FloatingGeometry): Pair<Int, Int> {
        val area = usableArea()
        val size = dp(BUBBLE_DP)
        if (geo.bubbleX >= 0 && geo.bubbleY >= 0) return geo.bubbleX to geo.bubbleY
        val onRight = geo.x + geo.widthPx / 2 >= (area.left + area.right) / 2
        return (if (onRight) area.right - size else area.left) to geo.y
    }

    private fun showBubble(geo: FloatingGeometry) {
        ThemeState.load(this)
        val view = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        bubbleView = view
        refreshMicUi()

        val size = dp(BUBBLE_DP)
        val (bx, by) = initialBubblePos(geo)
        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            overlayFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bx
            y = by
        }
        clampToScreen(params, size, size)
        bubbleParams = params
        windowManager.addView(view, params)

        refreshMicUi()
        setupDrag(
            handle = view, target = view, params = params,
            onTap = { restorePanelFromBubble() },
            onRelease = { saveBubbleGeometry() }
        )
        excludeFromSystemGestures(view)
    }

    private fun removeBubble() {
        snapAnimator?.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleParams = null
    }

    // Minimize: bubble muncul di TEPI SISI tempat panel berada (kanan tetap
    // kanan, kiri tetap kiri), setinggi panel - bukan di x panel (yang bikin
    // bubble loncat ke tengah kalau panel ada di kanan).
    // Minimize: bubble kembali PERSIS ke posisi terakhirnya sebelum dibuka
    // (bubbleX/bubbleY tidak pernah ditimpa saat panel digeser/di-resize).
    // Hanya kalau belum pernah ada posisi bubble tersimpan, dipakai pojok panel.
    private fun minimizeToBubble() {
        val p = panelParams ?: return
        val prev = loadGeo()
        val size = dp(BUBBLE_DP)
        val bp = WindowManager.LayoutParams()
        if (prev.bubbleX >= 0 && prev.bubbleY >= 0) {
            bp.x = prev.bubbleX
            bp.y = prev.bubbleY
        } else {
            val area = usableArea()
            val onRight = p.x + p.width / 2 >= (area.left + area.right) / 2
            val onBottom = p.y + p.height / 2 >= (area.top + area.bottom) / 2
            bp.x = if (onRight) p.x + p.width - size else p.x
            bp.y = if (onBottom) p.y + p.height - size else p.y
        }
        clampToScreen(bp, size, size)
        FloatingPrefs.save(this, FloatingGeometry(p.x, p.y, p.width, p.height, true, bp.x, bp.y))
        removePanel()
        showBubble(loadGeo())
    }

    // Buka lagi: panel muncul di sekitar bubble (pojok panel yang sama dengan
    // pojok bubble), lalu dijaga tetap di dalam layar.
    private fun restorePanelFromBubble() {
        val bp = bubbleParams ?: return
        val geo = loadGeo()
        val area = usableArea()
        val size = dp(BUBBLE_DP)
        val w = geo.widthPx.coerceIn(min(dp(MIN_WIDTH_DP), area.width()), area.width())
        val h = geo.heightPx.coerceIn(min(dp(MIN_HEIGHT_DP), area.height()), area.height())
        val onRight = bp.x + size / 2 >= (area.left + area.right) / 2
        val onBottom = bp.y + size / 2 >= (area.top + area.bottom) / 2
        val px = if (onRight) bp.x + size - w else bp.x
        val py = if (onBottom) bp.y + size - h else bp.y
        FloatingPrefs.save(
            this,
            geo.copy(x = px, y = py, minimized = false, bubbleX = bp.x, bubbleY = bp.y)
        )
        removeBubble()
        showPanel(loadGeo())
    }

    private fun saveCurrentPanelGeometry(minimized: Boolean) {
        val params = panelParams ?: return
        FloatingPrefs.save(
            this,
            loadGeo().copy(x = params.x, y = params.y, widthPx = params.width, heightPx = params.height, minimized = minimized)
        )
    }

    // Simpan posisi bubble SAJA - posisi/ukuran panel jangan ikut ketimpa.
    private fun saveBubbleGeometry() {
        val params = bubbleParams ?: return
        FloatingPrefs.save(this, loadGeo().copy(bubbleX = params.x, bubbleY = params.y, minimized = true))
    }

    // Setelah dilepas, bubble otomatis "nempel" ke tepi kiri/kanan terdekat
    // (tetap utuh kelihatan) - jadi gak pernah nyangkut di pojokan.
    private fun snapBubbleToEdge() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val area = usableArea()
        val size = dp(BUBBLE_DP)
        val center = params.x + size / 2
        val targetX = if (center < (area.left + area.right) / 2) area.left else area.right - size
        params.y = params.y.coerceIn(area.top, max(area.top, area.bottom - size))
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    saveBubbleGeometry()
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------------------
    // Batas layar & gesture
    // ---------------------------------------------------------------------

    // Area layar yang boleh dipakai jendela floating (dalam koordinat layar
    // penuh): di dalam status bar / nav bar / cutout, plus sedikit margin.
    private fun usableArea(): Rect {
        val margin = dp(EDGE_MARGIN_DP)
        val full: Rect
        var top = 0
        var bottom = 0
        var left = 0
        var right = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.maximumWindowMetrics
            full = Rect(metrics.bounds)
            runCatching {
                val ins = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                left = ins.left; top = ins.top; right = ins.right; bottom = ins.bottom
            }
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            full = Rect(0, 0, dm.widthPixels, dm.heightPixels)
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) top = resources.getDimensionPixelSize(id)
            val nid = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (nid > 0) bottom = resources.getDimensionPixelSize(nid)
        }
        // Cadangan kalau sistem melaporkan inset 0 (service bukan jendela UI).
        if (top == 0) {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) top = resources.getDimensionPixelSize(id)
        }
        return Rect(
            full.left + left + margin,
            full.top + top + margin,
            full.right - right - margin,
            full.bottom - bottom - margin
        )
    }

    // Jendela dijaga SEPENUHNYA di dalam layar (bukan setengah keluar layar
    // seperti sebelumnya, yang bikin bagian yang bisa dipegang jatuh di zona
    // gesture back/tepi layar dan susah digeser).
    private fun clampToScreen(params: WindowManager.LayoutParams, w: Int, h: Int) {
        val area = usableArea()
        params.x = params.x.coerceIn(area.left, max(area.left, area.right - w))
        params.y = params.y.coerceIn(area.top, max(area.top, area.bottom - h))
    }

    // Minta sistem jangan menganggap sentuhan di view ini sebagai gesture
    // back / swipe tepi layar (Android 10+). Penting buat pojok & tepi layar.
    private fun excludeFromSystemGestures(v: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        v.addOnLayoutChangeListener { view, l, t, r, b, _, _, _, _ ->
            runCatching {
                view.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
            }
        }
    }

    // Geser dengan ambang "touch slop": ketukan kecil = tap (onTap), gerakan
    // lebih jauh = geser. ACTION_CANCEL juga ditangani supaya geseran gak
    // putus di tengah jalan kalau sistem mengambil alih sentuhan di tepi.
    private fun setupDrag(
        handle: View,
        target: View,
        params: WindowManager.LayoutParams,
        onTap: (() -> Unit)?,
        onRelease: () -> Unit,
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false

        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > slop) dragging = true
                    if (dragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        clampToScreen(params, params.width, params.height)
                        runCatching { windowManager.updateViewLayout(target, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) onRelease() else onTap?.invoke()
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onRelease()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupResize(handle: View, params: WindowManager.LayoutParams, targetView: View) {
        var initialWidth = 0
        var initialHeight = 0
        var touchX = 0f
        var touchY = 0f
        val minW = dp(MIN_WIDTH_DP)
        val minH = dp(MIN_HEIGHT_DP)

        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    initialWidth = params.width
                    initialHeight = params.height
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Ukuran maksimum = sisa ruang dari posisi panel ke tepi
                    // layar, jadi panel gak bisa melebar keluar layar.
                    val area = usableArea()
                    val maxW = max(minW, area.right - params.x)
                    val maxH = max(minH, area.bottom - params.y)
                    params.width = (initialWidth + (event.rawX - touchX).toInt()).coerceIn(minW, maxW)
                    params.height = (initialHeight + (event.rawY - touchY).toInt()).coerceIn(minH, maxH)
                    runCatching { windowManager.updateViewLayout(targetView, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveCurrentPanelGeometry(minimized = false)
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    saveCurrentPanelGeometry(minimized = false)
                    true
                }
                else -> false
            }
        }
    }
}

// Lifecycle + SavedState + ViewModelStore minimal buat ComposeView yang
// ditempel langsung ke WindowManager dari Service (tanpa Activity).
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
