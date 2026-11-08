package com.kendang.realpads.skin

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kendang.realpads.CtrlPanelColor
import com.kendang.realpads.PadTextColor
import com.kendang.realpads.PanelColor
import com.kendang.realpads.TextDim
import com.kendang.realpads.ThemeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pemilih skin. Tiga tab: MIXER, KOMPRESOR, dan EQUALIZER (masing-masing punya repo, cache, dan pilihan sendiri).
 * Daftar = tampilan klasik (default) + katalog dari repository skin (online, atau daftar tersimpan bila offline).
 * Aksi per skin: UNDUH (+ langsung dipakai), PAKAI (sudah terunduh), UPDATE.
 * Skin yang gagal diunduh / ditolak validator tidak mengubah skin yang sedang dipakai.
 * Dipanggil dari tombol EXIT di skin (function skin.pick) dan tombol SKIN di UI klasik.
 *
 * Gaya SENGAJA disamakan dengan dialog lain di app (OnlinePresetListDialog dkk): AlertDialog dengan
 * containerColor = PanelColor, judul PadTextColor tebal + ikon refresh di kanan, baris daftar
 * CtrlPanelColor (rounded 8dp), ikon/aksi warna aksen tema, tombol tutup "TUTUP" CtrlPanelColor.
 */
@Composable
fun SkinPickerDialog(onDismiss: () -> Unit, initialTab: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(initialTab.coerceIn(0, 2)) }
    // Satu pemegang state per tab; ganti tab = pemegang lain (daftar, status unduh, pesan masing-masing).
    val st = remember(tab) { PickerState(when (tab) { 0 -> MixerSource; 1 -> CompSource; else -> EqSource }, context, scope) }
    LaunchedEffect(tab) { st.refresh() }
    val source = st.source
    val busyId = st.busyId

    AlertDialog(
        // Selama unduh/pakai berjalan, dialog tidak boleh tertutup tak sengaja.
        onDismissRequest = { if (busyId == null) onDismiss() },
        containerColor = PanelColor,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pilih Skin",
                    color = PadTextColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // Kotak 28dp tetap: ikon refresh <-> spinner tidak bergeser.
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    if (st.loading) {
                        CircularProgressIndicator(
                            color = ThemeState.accentColor,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        IconButton(onClick = { if (busyId == null) st.refresh() }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Muat ulang daftar skin",
                                tint = TextDim,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        text = {
            // Seluruh isi bisa di-scroll: di layar landscape yang pendek, pesan status & slider tidak lagi terpotong.
            Column(modifier = Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                // Tab MIXER | KOMPRESOR | EQUALIZER
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PickerTab("MIXER", tab == 0, busyId == null, Modifier.weight(1f)) { tab = 0 }
                    PickerTab("KOMPRESOR", tab == 1, busyId == null, Modifier.weight(1f)) { tab = 1 }
                    PickerTab("EQUALIZER", tab == 2, busyId == null, Modifier.weight(1f)) { tab = 2 }
                }
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Default: tampilan ASLI APK (tanpa skin). Selalu ada, offline, tanpa loading.
                    key(source.classicId) {
                        val isActive = st.selected == source.classicId
                        SkinRow(
                            title = source.classicTitle, subtitle = source.classicSubtitle,
                            active = isActive,
                            action = if (isActive) null else "PAKAI",
                            busy = busyId == source.classicId,
                            enabled = busyId == null
                        ) { st.useInstalled(source.classicId, source.classicTitle) }
                    }
                    st.entries.forEach { e ->
                        key(e.id) {
                            // tick di key remember: dihitung ulang setelah unduhan selesai
                            val downloaded = remember(st.tick, e.id) { source.isDownloaded(context, e.id) }
                            val cachedVer = remember(st.tick, e.id) { if (downloaded) source.cachedVersion(context, e.id) else null }
                            val needsUpdate = downloaded && cachedVer != null && cachedVer != e.version
                            val isActive = st.selected == e.id
                            val kind = if (e.padMax <= 0) source.kindLabel else if (e.padMin == e.padMax) "${e.padMax} pad" else "${e.padMin}-${e.padMax} pad"
                            val sub = buildString {
                                append("v${e.version}")
                                if (e.author.isNotBlank()) append(" · ${e.author}")
                                append(" · $kind")
                                if (needsUpdate) append(" · update dari v$cachedVer")
                                if (!downloaded) append(" · belum diunduh")
                            }
                            val action = when {
                                needsUpdate -> "UPDATE"
                                !downloaded -> "UNDUH"
                                isActive -> null
                                else -> "PAKAI"
                            }
                            SkinRow(
                                title = e.name, subtitle = sub, active = isActive, action = action,
                                busy = busyId == e.id, enabled = busyId == null
                            ) {
                                if (!downloaded || needsUpdate) st.download(e) else st.useInstalled(e.id, e.name)
                            }
                        }
                    }
                }

                // Status daftar - di BAWAH daftar (bukan di atas) supaya muncul/hilangnya tidak menggeser baris di atasnya.
                // Saat daftar sudah ada, proses muat ulang cukup ditandai spinner di judul (tanpa teks tambahan).
                val status = when {
                    st.loading && st.entries.isEmpty() -> "Mengambil daftar skin..."
                    st.loading -> null
                    !st.configured && st.entries.isEmpty() -> "Repository skin belum dikonfigurasi. Hanya tampilan klasik yang tersedia."
                    !st.online && st.entries.isEmpty() -> "Tidak ada koneksi dan belum ada daftar tersimpan."
                    !st.online -> "Offline - menampilkan daftar tersimpan."
                    else -> null
                }
                if (status != null) {
                    Text(status, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (st.message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(st.message!!, color = PadTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                // Lebar skin (hanya skin MIXER; bagian EQUALIZER selalu selebar mixer di atasnya, tanpa celah): hanya relevan saat skin aktif.
                // Geser ke kiri = mengecil sampai proporsi asli skin (di tengah, sisi kosong); kanan = di-stretch memenuhi lebar area.
                if (source.hasWidthSlider && st.selected != source.classicId) {
                    var width by remember { mutableStateOf(SkinManager.widthPercent.toFloat()) }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lebar skin", color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (width.roundToInt() >= 100) "Penuh" else if (width.roundToInt() <= 0) "Asli" else "${width.roundToInt()}%",
                            color = ThemeState.accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = width,
                        onValueChange = { width = it; SkinManager.setWidthPercent(context, it.roundToInt(), save = false) },
                        onValueChangeFinished = { SkinManager.setWidthPercent(context, width.roundToInt()) },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = ThemeState.accentColor,
                            activeTrackColor = ThemeState.accentColor,
                            inactiveTrackColor = CtrlPanelColor
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Asli", color = TextDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text("Penuh", color = TextDim, fontSize = 10.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                enabled = busyId == null,
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("TUTUP", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

/** Sumber skin satu tab (mixer / equalizer): menyatukan SkinManager+SkinRepository dan EqSkinManager+EqSkinRepository. */
private interface PickerSource {
    val classicId: String
    val classicTitle: String
    val classicSubtitle: String
    /** Keterangan jenis skin di baris daftar (untuk skin tanpa pad). */
    val kindLabel: String
    val hasWidthSlider: Boolean
    fun selectedId(c: Context): String
    fun cachedEntries(c: Context): List<SkinEntry>
    fun configured(c: Context): Boolean
    suspend fun refreshCatalog(c: Context): List<SkinEntry>
    fun lastRefreshOnline(): Boolean
    fun isDownloaded(c: Context, id: String): Boolean
    fun cachedVersion(c: Context, id: String): String?
    suspend fun useSkin(c: Context, id: String): Boolean
    suspend fun downloadAndUse(c: Context, e: SkinEntry): String?
}

private object MixerSource : PickerSource {
    override val classicId = SkinManager.CLASSIC_ID
    override val classicTitle = "APK Asli (Klasik)"
    override val classicSubtitle = "Tampilan bawaan APK, tanpa skin"
    override val kindLabel = "mixer"
    override val hasWidthSlider = true
    override fun selectedId(c: Context) = SkinManager.selectedId(c)
    override fun cachedEntries(c: Context) = SkinRepository.cachedEntries(c)
    override fun configured(c: Context) = SkinRepository.indexUrl(c) != null
    override suspend fun refreshCatalog(c: Context) = SkinManager.refreshCatalog(c)
    override fun lastRefreshOnline() = SkinRepository.lastRefreshOnline
    override fun isDownloaded(c: Context, id: String) = SkinRepository.isDownloaded(c, id)
    override fun cachedVersion(c: Context, id: String) = SkinRepository.cachedVersion(c, id)
    override suspend fun useSkin(c: Context, id: String) = SkinManager.useSkin(c, id)
    override suspend fun downloadAndUse(c: Context, e: SkinEntry) = SkinManager.downloadAndUse(c, e)
}

private object CompSource : PickerSource {
    override val classicId = CompSkinManager.CLASSIC_ID
    override val classicTitle = "Kompresor Klasik"
    override val classicSubtitle = "Tampilan bawaan APK (knob gaya Poweramp), tanpa skin"
    override val kindLabel = "kompresor"
    override val hasWidthSlider = false
    override fun selectedId(c: Context) = CompSkinManager.selectedId(c)
    override fun cachedEntries(c: Context) = CompSkinRepository.cachedEntries(c)
    override fun configured(c: Context) = CompSkinRepository.indexUrl(c) != null
    override suspend fun refreshCatalog(c: Context) = CompSkinManager.refreshCatalog(c)
    override fun lastRefreshOnline() = CompSkinRepository.lastRefreshOnline
    override fun isDownloaded(c: Context, id: String) = CompSkinRepository.isDownloaded(c, id)
    override fun cachedVersion(c: Context, id: String) = CompSkinRepository.cachedVersion(c, id)
    override suspend fun useSkin(c: Context, id: String) = CompSkinManager.useSkin(c, id)
    override suspend fun downloadAndUse(c: Context, e: SkinEntry) = CompSkinManager.downloadAndUse(c, e)
}

private object EqSource : PickerSource {
    override val classicId = EqSkinManager.CLASSIC_ID
    override val classicTitle = "Equalizer Klasik"
    override val classicSubtitle = "Tampilan bawaan APK (kartu L / R), tanpa skin"
    override val kindLabel = "equalizer"
    override val hasWidthSlider = false
    override fun selectedId(c: Context) = EqSkinManager.selectedId(c)
    override fun cachedEntries(c: Context) = EqSkinRepository.cachedEntries(c)
    override fun configured(c: Context) = EqSkinRepository.indexUrl(c) != null
    override suspend fun refreshCatalog(c: Context) = EqSkinManager.refreshCatalog(c)
    override fun lastRefreshOnline() = EqSkinRepository.lastRefreshOnline
    override fun isDownloaded(c: Context, id: String) = EqSkinRepository.isDownloaded(c, id)
    override fun cachedVersion(c: Context, id: String) = EqSkinRepository.cachedVersion(c, id)
    override suspend fun useSkin(c: Context, id: String) = EqSkinManager.useSkin(c, id)
    override suspend fun downloadAndUse(c: Context, e: SkinEntry) = EqSkinManager.downloadAndUse(c, e)
}

/** State pemilih untuk satu tab. Mulai dari index tersimpan (baca file kecil), BUKAN daftar kosong (hindari baris berkedip UNDUH/PAKAI). */
private class PickerState(
    val source: PickerSource,
    private val context: Context,
    private val scope: CoroutineScope
) {
    var entries by mutableStateOf(source.cachedEntries(context))
    var loading by mutableStateOf(true)
    var online by mutableStateOf(false)
    var busyId by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var selected by mutableStateOf(source.selectedId(context))
    var tick by mutableStateOf(0)   // naik tiap status unduhan berubah
    val configured: Boolean = source.configured(context)

    fun refresh() {
        scope.launch {
            loading = true
            entries = source.refreshCatalog(context)
            online = source.lastRefreshOnline()
            loading = false
        }
    }

    private fun finish(ok: Boolean, okMsg: String, failMsg: String) {
        selected = source.selectedId(context)
        tick++
        message = if (ok) okMsg else failMsg
        busyId = null
    }

    fun useInstalled(id: String, name: String) {
        if (busyId != null) return
        busyId = id; message = null
        scope.launch {
            val ok = source.useSkin(context, id)
            finish(ok, "Skin \"$name\" dipakai", "Skin \"$name\" tidak bisa dipakai; skin sebelumnya tetap aktif")
        }
    }

    fun download(e: SkinEntry) {
        if (busyId != null) return
        busyId = e.id; message = null
        scope.launch {
            val err = source.downloadAndUse(context, e)
            finish(err == null, "Skin \"${e.name}\" diunduh dan dipakai", "Gagal: $err")
        }
    }
}

@Composable
private fun PickerTab(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(32.dp)
            .background(if (selected) ThemeState.accentColor else CtrlPanelColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled && !selected, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) ThemeState.accentTextColor else PadTextColor,
            fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1
        )
    }
}

// Baris skin: sama bentuknya dengan OnlinePresetRow (CtrlPanelColor, rounded 8dp, seluruh baris bisa di-tap).
// Kanan: spinner aksen saat proses, ikon unduh untuk UNDUH/UPDATE, teks aksen untuk PAKAI, centang untuk yang aktif.
@Composable
private fun SkinRow(
    title: String, subtitle: String, active: Boolean, action: String?, busy: Boolean, enabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled && action != null, onClick = onAction)
            .padding(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (!enabled && !busy) TextDim else PadTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
            Text(subtitle, color = TextDim, fontSize = 10.sp, maxLines = 2)
        }
        Box(Modifier.width(8.dp))
        when {
            busy -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
            action == "UNDUH" || action == "UPDATE" -> Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = if (action == "UPDATE") "Update skin" else "Unduh skin",
                tint = if (enabled) Color.White else TextDim,
                modifier = Modifier.size(18.dp)
            )
            action != null -> Text(
                action,
                color = if (enabled) Color.White else TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            active -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Sedang dipakai",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
