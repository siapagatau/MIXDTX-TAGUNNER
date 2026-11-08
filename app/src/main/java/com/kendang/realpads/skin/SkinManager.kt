package com.kendang.realpads.skin

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kendang.realpads.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pusat Skin Engine. DEFAULT = UI KLASIK (tampilan asli APK, tanpa skin, tanpa loading).
 * Skin hanya dipakai bila user memilihnya di pemilih skin (unduhan dari repository, tersimpan di cache sehingga tetap bisa dipakai offline).
 * Skin rusak / gagal render -> pilihan di-reset ke klasik -> state Failed (PadScreen menampilkan UI klasik + pesan). Tidak crash.
 * Skin unduhan + cache offline: lihat SkinRepository (Stage 08).
 */
object SkinManager {
    private const val TAG = "SkinManager"

    sealed class State {
        object Loading : State()
        /** UI klasik asli APK (default). Bukan skin. */
        object Classic : State()
        data class Active(val skin: SkinDefinition) : State()
        data class Failed(val reason: String) : State()
    }

    // Disimpan agar reportRenderFailure (tanpa parameter Context) bisa mereset pilihan skin.
    @Volatile private var appContext: Context? = null

    var state: State by mutableStateOf<State>(State.Loading)
        private set

    private const val PREFS = "skin_prefs"
    private const val KEY_SELECTED = "selected_skin_id"
    /** Pilihan default: UI klasik asli APK (tanpa skin). */
    const val CLASSIC_ID = "classic"
    /** ID lama skin contoh yang dulu dibundel di APK (sudah dihapus). Pilihan tersimpan dengan ID ini di-reset ke klasik bila tidak ada di cache (skin repo ber-ID sama tetap jalan). */
    private const val LEGACY_BUNDLED_ID = "default"

    fun selectedId(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, CLASSIC_ID) ?: CLASSIC_ID

    private fun saveSelected(context: Context, id: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, id).apply()
    }

    /** Preferensi tampilan skin (mis. panel skin tampil / tombol bawah tampil) - bertahan antar sesi. */
    fun uiFlag(context: Context, key: String, default: Boolean): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ui_$key", default)

    fun setUiFlag(context: Context, key: String, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ui_$key", value).apply()
    }

    /**
     * Lebar skin di layar, 0..100 (persen). 100 = di-stretch memenuhi lebar area (perilaku lama, default);
     * 0 = proporsi asli skin (lebar mengikuti tinggi area, di tengah, sisi kiri-kanan kosong).
     * State Compose: mengubahnya langsung menggambar ulang skin, tanpa render ulang kecuali ukuran berubah jauh.
     */
    var widthPercent: Int by mutableStateOf(100)
        private set

    private const val KEY_WIDTH = "skin_width_percent"

    fun loadWidth(context: Context) {
        widthPercent = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WIDTH, 100).coerceIn(0, 100)
    }

    fun setWidthPercent(context: Context, value: Int, save: Boolean = true) {
        val v = value.coerceIn(0, 100)
        widthPercent = v
        if (save) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_WIDTH, v).apply()
        }
    }

    // Mencegah muat ganda: ensureLoaded kini dipanggil lebih awal dari MainActivity.onCreate
    // (supaya skin sudah siap saat layar pertama tergambar) DAN dari PadScreen.
    @Volatile private var loadStarted = false

    suspend fun ensureLoaded(context: Context) {
        if (state !is State.Loading || loadStarted) return
        loadStarted = true
        val app = context.applicationContext
        appContext = app
        loadWidth(app)
        try {
            // Versi FREE: fitur skin digembok -> selalu UI klasik. Pilihan skin lama (dari versi
            // sebelumnya yang belum digembok) di-reset supaya tidak terpakai diam-diam.
            if (!BuildConfig.IS_PREMIUM && selectedId(app) != CLASSIC_ID) saveSelected(app, CLASSIC_ID)
            // Klasik = default: langsung siap, tanpa baca berkas / layar tunggu.
            state = if (selectedId(app) == CLASSIC_ID) State.Classic else withContext(Dispatchers.IO) { loadSelected(app) }
        } finally {
            // Dibatalkan sebelum selesai -> izinkan percobaan berikutnya, jangan nyangkut di Loading.
            if (state is State.Loading) loadStarted = false
        }
    }

    /** Skin pilihan: klasik / cache unduhan (offline OK). Gagal -> pilihan di-reset ke klasik + State.Failed. */
    private fun loadSelected(app: Context): State {
        val id = selectedId(app)
        if (id == CLASSIC_ID) return State.Classic
        val dir = SkinRepository.cachedSkinDir(app, id)
        // Pilihan lama (skin contoh yang dulu dibundel) dan tidak ada di cache -> diam-diam kembali ke klasik, tanpa pesan error.
        if (dir == null && id == LEGACY_BUNDLED_ID) { saveSelected(app, CLASSIC_ID); return State.Classic }
        val r: State = if (dir == null) State.Failed("skin '$id' tidak ada di cache") else loadFrom { SkinLoader.loadFromDir(dir) }
        if (r is State.Active) return r
        Log.w(TAG, "skin '$id' gagal dimuat, kembali ke UI klasik")
        saveSelected(app, CLASSIC_ID)
        return r   // State.Failed -> PadScreen menampilkan UI klasik + alasan
    }

    /** Pakai pilihan [id] (CLASSIC_ID atau skin terunduh). true bila aktif; gagal -> pilihan sebelumnya tetap. */
    suspend fun useSkin(context: Context, id: String): Boolean {
        val app = context.applicationContext
        // Versi FREE: skin digembok, hanya UI klasik yang boleh dipilih.
        if (!BuildConfig.IS_PREMIUM && id != CLASSIC_ID) return false
        return withContext(Dispatchers.IO) {
            val r: State = when (id) {
                CLASSIC_ID -> State.Classic
                else -> {
                    val dir = SkinRepository.cachedSkinDir(app, id)
                    if (dir == null) State.Failed("skin '$id' belum diunduh") else loadFrom { SkinLoader.loadFromDir(dir) }
                }
            }
            if (r is State.Active || r is State.Classic) { saveSelected(app, id); state = r; true } else false
        }
    }

    /** Segarkan index (online, atau cache bila offline). Tidak mengubah skin aktif. */
    suspend fun refreshCatalog(context: Context): List<SkinEntry> =
        withContext(Dispatchers.IO) { SkinRepository.refreshIndex(context.applicationContext) }

    /** Unduh skin lalu langsung dipakai. Mengembalikan null bila sukses, atau pesan error. */
    suspend fun downloadAndUse(context: Context, entry: SkinEntry): String? {
        val app = context.applicationContext
        return try {
            withContext(Dispatchers.IO) { SkinRepository.download(app, entry) }
            if (useSkin(app, entry.id)) null else "skin terunduh tetapi tidak dapat dipakai"
        } catch (e: Throwable) {
            Log.w(TAG, "downloadAndUse gagal", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Muat + validasi; tidak pernah melempar. */
    fun loadFrom(loader: () -> SkinDefinition): State {
        return try {
            val skin = loader()
            val v = SkinValidator.validate(skin)
            v.warnings.forEach { Log.w(TAG, "peringatan skin '${skin.name}': $it") }
            if (v.ok) {
                Log.i(TAG, "skin '${skin.name}' v${skin.version} aktif (${skin.padCount} pad, ${skin.source})")
                State.Active(skin)
            } else {
                Log.e(TAG, "skin '${skin.name}' ditolak: ${v.summary()}")
                State.Failed(v.summary())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "skin gagal dimuat", e)
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Dipanggil SkinHost bila render fatal (OOM / AndroidSVG gagal): pilihan di-reset ke UI klasik (agar start berikutnya
     * tidak mengulang masalah) dan state = Failed -> PadScreen memakai UI klasik + menampilkan alasannya. Tidak crash.
     */
    fun reportRenderFailure(reason: String) {
        Log.e(TAG, "render skin gagal: $reason")
        appContext?.let { saveSelected(it, CLASSIC_ID) }
        state = State.Failed(reason)
    }
}
