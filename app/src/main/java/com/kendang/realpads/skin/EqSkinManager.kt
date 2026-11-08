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
 * Skin Engine untuk bagian EQUALIZER 31 BAND. Sama dengan [SkinManager] (mixer) tetapi berdiri sendiri: pilihan skin sendiri,
 * repo sendiri ([EqSkinRepository], config/eq-skin-repo.json), cache sendiri (eq-skin-cache). Format skin.svg/skin.json SAMA dengan
 * skin mixer (lihat PROMPT_SKIN_EQUALIZER.md); yang beda hanya function yang dipakai (eq31.*).
 *
 * Default = tampilan klasik EQUALIZER (kartu L / R bawaan APK, tanpa skin). Skin rusak / gagal render -> pilihan di-reset ke klasik
 * + State.Failed (alasannya ditampilkan sebagai toast). Tidak crash.
 *
 * Bagian EQUALIZER tampil MENYATU di bawah mixer (layar utama & floating), jadi tingginya mengikuti proporsi asli skin
 * pada lebar yang tersedia (tanpa celah dengan mixer di atasnya).
 */
object EqSkinManager {
    private const val TAG = "EqSkinManager"
    private const val PREFS = "eq_skin_prefs"
    private const val KEY_SELECTED = "selected_skin_id"

    const val CLASSIC_ID = "classic"

    sealed class State {
        object Loading : State()
        /** Tampilan klasik EQUALIZER bawaan APK (default). Bukan skin. */
        object Classic : State()
        data class Active(val skin: SkinDefinition) : State()
        data class Failed(val reason: String) : State()
    }

    @Volatile private var appContext: Context? = null

    var state: State by mutableStateOf<State>(State.Loading)
        private set

    fun selectedId(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, CLASSIC_ID) ?: CLASSIC_ID

    private fun saveSelected(context: Context, id: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, id).apply()
    }

    @Volatile private var loadStarted = false

    suspend fun ensureLoaded(context: Context) {
        if (state !is State.Loading || loadStarted) return
        loadStarted = true
        val app = context.applicationContext
        appContext = app
        try {
            if (!BuildConfig.IS_PREMIUM && selectedId(app) != CLASSIC_ID) saveSelected(app, CLASSIC_ID)
            state = if (selectedId(app) == CLASSIC_ID) State.Classic else withContext(Dispatchers.IO) { loadSelected(app) }
        } finally {
            if (state is State.Loading) loadStarted = false
        }
    }

    private fun loadSelected(app: Context): State {
        val id = selectedId(app)
        if (id == CLASSIC_ID) return State.Classic
        val dir = EqSkinRepository.cachedSkinDir(app, id)
        val r: State = if (dir == null) State.Failed("skin equalizer '$id' tidak ada di cache") else loadFrom { SkinLoader.loadFromDir(dir) }
        if (r is State.Active) return r
        Log.w(TAG, "skin equalizer '$id' gagal dimuat, kembali ke tampilan klasik")
        saveSelected(app, CLASSIC_ID)
        return r
    }

    /** Pakai pilihan [id] (CLASSIC_ID atau skin terunduh). true bila aktif; gagal -> pilihan sebelumnya tetap. */
    suspend fun useSkin(context: Context, id: String): Boolean {
        val app = context.applicationContext
        appContext = app
        if (!BuildConfig.IS_PREMIUM && id != CLASSIC_ID) return false
        return withContext(Dispatchers.IO) {
            val r: State = when (id) {
                CLASSIC_ID -> State.Classic
                else -> {
                    val dir = EqSkinRepository.cachedSkinDir(app, id)
                    if (dir == null) State.Failed("skin equalizer '$id' belum diunduh") else loadFrom { SkinLoader.loadFromDir(dir) }
                }
            }
            if (r is State.Active || r is State.Classic) { saveSelected(app, id); state = r; true } else false
        }
    }

    suspend fun refreshCatalog(context: Context): List<SkinEntry> =
        withContext(Dispatchers.IO) { EqSkinRepository.refreshIndex(context.applicationContext) }

    /** Unduh skin lalu langsung dipakai. null = sukses, selain itu pesan error. */
    suspend fun downloadAndUse(context: Context, entry: SkinEntry): String? {
        val app = context.applicationContext
        return try {
            withContext(Dispatchers.IO) { EqSkinRepository.download(app, entry) }
            if (useSkin(app, entry.id)) null else "skin terunduh tetapi tidak dapat dipakai"
        } catch (e: Throwable) {
            Log.w(TAG, "downloadAndUse gagal", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Muat + validasi; tidak pernah melempar. */
    private fun loadFrom(loader: () -> SkinDefinition): State {
        return try {
            val skin = loader()
            val v = SkinValidator.validate(skin)
            v.warnings.forEach { Log.w(TAG, "peringatan skin equalizer '${skin.name}': $it") }
            if (v.ok) State.Active(skin) else State.Failed(v.summary())
        } catch (e: Throwable) {
            Log.e(TAG, "skin equalizer gagal dimuat", e)
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Dipanggil SkinHost bila render fatal: pilihan di-reset ke klasik, state = Failed (tampilan klasik + alasan). */
    fun reportRenderFailure(reason: String) {
        Log.e(TAG, "render skin equalizer gagal: $reason")
        appContext?.let { saveSelected(it, CLASSIC_ID) }
        state = State.Failed(reason)
    }
}
