package com.kendang.realpads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Foreground service yang "memegang" efek EQ global. Efek audio hidup di
// proses app ini - kalau proses dimatikan Android (app di-background), efeknya
// ikut hilang. Service foreground (dengan notifikasi) menjaga proses tetap
// hidup selama EQ Audio dinyalakan. Terpisah dari FloatingWindowService dan
// jalur mic native, jadi gak nyentuh delay mic sama sekali.
class GlobalEqService : Service() {

    companion object {
        private const val CHANNEL_ID = "global_eq_channel"
        private const val NOTIF_ID = 1002
        const val ACTION_STOP = "com.kendang.realpads.action.STOP_GLOBAL_EQ"

        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            turnOffSetting()
            stopSelf()
            return START_NOT_STICKY
        }
        val s = SettingsStore.load(this)
        Eq31Ui.ensureLoaded(this)
        CompUi.ensureLoaded(this)
        FxUi.ensureLoaded(this)
        // Service restart otomatis oleh sistem tapi user sudah mematikan EQ (EQ AUDIO dan EQ 31 band sama-sama OFF).
        if (!s.gEqEnabled && !Eq31Ui.s.enabled && !CompUi.s.enabled && !FxUi.s.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!GlobalEqEngine.start(s)) {
            // Gagal (HP gak support, dll): switch di UI dikembalikan ke OFF,
            // alasannya tetap tampil lewat GlobalEqEngine.error.
            turnOffSetting()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        GlobalEqEngine.stop()
        super.onDestroy()
    }

    // Simpan "EQ Audio = OFF" (dan EQ 31 band = OFF) + kabari layar utama / panel floating.
    private fun turnOffSetting() {
        val off = SettingsStore.load(this).copy(gEqEnabled = false)
        SettingsStore.save(this, off)
        SettingsBus.publish(off)
        Eq31Ui.forceDisabled(this)
        CompUi.forceDisabled(this)
        FxUi.forceDisabled(this)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "EQ Audio Karaoke Mic", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, GlobalEqService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Karaoke Mic")
            .setContentText("EQ Audio aktif")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(0, "Matikan", stopPending)
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }
}

fun startGlobalEq(context: Context) {
    runCatching {
        ContextCompat.startForegroundService(context, Intent(context, GlobalEqService::class.java))
    }
}

fun stopGlobalEq(context: Context) {
    context.stopService(Intent(context, GlobalEqService::class.java))
}

/**
 * Efek EQ global dipakai bersama oleh EQ AUDIO dan EQ 31 BAND: nyalakan bila SALAH SATU ON, matikan hanya bila KEDUANYA OFF.
 * Panggil setelah mengubah salah satu saklar (status ON/OFF sudah tersimpan).
 */
fun syncGlobalEqService(context: Context) {
    Eq31Ui.ensureLoaded(context)
    CompUi.ensureLoaded(context)
    FxUi.ensureLoaded(context)
    val needed = SettingsStore.load(context).gEqEnabled || Eq31Ui.s.enabled || CompUi.s.enabled || FxUi.s.enabled
    if (needed) {
        if (GlobalEqService.running.value) GlobalEqEngine.refresh() else startGlobalEq(context)
    } else {
        stopGlobalEq(context)
    }
}
