package com.kendang.realpads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.widget.Toast
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

// REVERB AUDIO (manual, tanpa efek bawaan HP).
//
// Alur: audio app lain -> AudioPlaybackCapture (butuh izin "rekam layar" sekali tiap dinyalakan) -> reverb/echo buatan
// sendiri di C++ (SysReverb) -> AudioTrack memutar HANYA ekornya (wet-only). Suara asli tetap diputar app sumbernya,
// jadi tidak dobel. Audio app ini sendiri (mic karaoke) DIKECUALIKAN dari capture supaya tidak jadi lingkaran umpan balik.
//
// Batas yang tidak bisa dihindari: Android 10+, dan app yang melarang capture (sebagian app streaming/DRM) tidak akan
// terkena reverb. Latensi capture sekitar puluhan ms - wajar untuk reverb, ekornya sedikit tertinggal dari suara asli.
class SysReverbService : Service() {

    companion object {
        private const val TAG = "SysReverb"
        private const val CHANNEL_ID = "sys_reverb_channel"
        private const val NOTIF_ID = 1003
        const val ACTION_START = "com.kendang.realpads.action.START_SYS_REVERB"
        const val ACTION_STOP = "com.kendang.realpads.action.STOP_SYS_REVERB"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val SAMPLE_RATE = 48000
        private const val FRAMES = 960   // 20 ms per blok
        // Pengaman anti-delay: capture & putar beda clock -> backlog bisa menumpuk. Bila antrean rekam > batas ini,
        // blok lama dibuang dan blok terbaru dipakai. Backlog wajar (<= batas) tidak disentuh.
        private const val MAX_BACKLOG_BLOCKS = 5   // 100 ms

        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()

        // Alasan gagal nyala (null = tidak ada masalah). Ditampilkan di dialog REVERB AUDIO.
        private val _error = MutableStateFlow<String?>(null)
        val error = _error.asStateFlow()

        /** resultCode + data = hasil dialog izin dari MediaProjectionManager.createScreenCaptureIntent(). */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, SysReverbService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SysReverbService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var looping = false
    // Naik tiap shutdownCapture(): loop lama yang selesai karena kita matikan TIDAK boleh ikut menghentikan service baru.
    @Volatile private var session = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Harus langsung foreground (maks 5 detik) - dan untuk MediaProjection harus SEBELUM getMediaProjection().
        startForegroundNotification()
        if (intent == null || intent.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        shutdownCapture()   // kalau dinyalakan ulang saat masih jalan
        _error.value = null

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fail("Reverb Audio butuh Android 10 ke atas")
            return START_NOT_STICKY
        }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            fail("Izin rekam audio sistem tidak lengkap")
            return START_NOT_STICKY
        }
        val proj = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection gagal", t)
            null
        }
        if (proj == null) {
            fail("Izin rekam audio sistem ditolak")
            return START_NOT_STICKY
        }
        projection = proj
        // Android 14+: callback wajib didaftarkan; juga dipakai bila user mencabut izin dari status bar.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        if (!startCapture(proj)) {
            stopSelf()
            return START_NOT_STICKY
        }
        _running.value = true
        return START_NOT_STICKY   // izin projection tidak bisa dipakai ulang -> jangan restart otomatis
    }

    // Pesan singkat lewat toast (bisa dipakai dari layar utama maupun floating); tampilan terbaru tidak punya dialog.
    private fun report(message: String) {
        Log.w(TAG, message)
        _error.value = message
        val app = applicationContext
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
    }

    private fun fail(message: String) {
        report(message)
        stopSelf()
    }

    // Rantai capture dibuat dua kali dengan aturan berbeda, tapi SELALU mengecualikan uid app ini; kalau dua-duanya
    // ditolak sistem, lebih baik gagal daripada berisiko menangkap keluaran sendiri (umpan balik keras).
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildCaptureConfig(proj: MediaProjection): AudioPlaybackCaptureConfiguration? {
        val uid = Process.myUid()
        try {
            return AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(uid)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "capture config (usage + exclude uid) ditolak", t)
        }
        return try {
            AudioPlaybackCaptureConfiguration.Builder(proj).excludeUid(uid).build()
        } catch (t: Throwable) {
            Log.e(TAG, "capture config (exclude uid) ditolak", t)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCapture(proj: MediaProjection): Boolean {
        val config = buildCaptureConfig(proj)
        if (config == null) {
            report("HP ini menolak konfigurasi capture audio")
            return false
        }
        val rec = try {
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(max(min, FRAMES * 4) * 2)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord gagal dibuat", t)
            report("Gagal membuka capture audio (izin Mikrofon sudah diberikan?)")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            report("Capture audio tidak bisa dimulai di HP ini")
            return false
        }
        val trk = try {
            val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(max(min, FRAMES * 4) * 2)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack gagal dibuat", t)
            runCatching { rec.release() }
            report("Gagal membuka output audio")
            return false
        }

        // Mode shared bisa memberi buffer lebih besar dari yang diminta -> kunci ke 2 blok (40 ms) supaya antrean putar tidak menumpuk.
        runCatching { trk.bufferSizeInFrames = FRAMES * 2 }

        // Semua buffer reverb/echo dialokasi di sini, SEBELUM thread audio mulai.
        SysReverbEngine.nativePrepare(SAMPLE_RATE)
        SysReverbEngine.push(SysReverbStore.load(this))

        record = rec
        track = trk
        looping = true
        val myId = session
        worker = Thread({ runLoop(rec, trk, myId) }, "SysReverbLoop").also { it.start() }
        return true
    }

    private fun runLoop(rec: AudioRecord, trk: AudioTrack, myId: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = FRAMES * 2                         // jumlah short per blok (stereo)
        val inBuf = ShortArray(block)
        val outBuf = ShortArray(block)
        val q = ShortArray(block * (MAX_BACKLOG_BLOCKS + 4))   // antrean rekam
        var qLen = 0
        try {
            rec.startRecording()
            trk.play()
            while (looping) {
                // 1) pastikan ada minimal satu blok (read blocking = dipacu clock capture, seperti versi lama)
                while (qLen < block && looping) {
                    val n = rec.read(q, qLen, block - qLen)
                    if (n < 0) {
                        if (looping) report("Capture audio terputus (kode $n)")
                        looping = false
                        break
                    }
                    qLen += n
                }
                if (!looping) break
                // 2) serap sisa backlog yang sudah menunggu di AudioRecord (tanpa menunggu)
                while (looping && qLen < q.size) {
                    val n = rec.read(q, qLen, q.size - qLen, AudioRecord.READ_NON_BLOCKING)
                    if (n < 0) {
                        if (looping) report("Capture audio terputus (kode $n)")
                        looping = false
                        break
                    }
                    if (n == 0) break
                    qLen += n
                }
                if (!looping) break
                // 3) backlog melebihi batas -> buang yang lama, simpan blok terbaru. Backlog wajar tidak dibuang.
                if (qLen > MAX_BACKLOG_BLOCKS * block) {
                    val drop = (qLen / block - 1) * block
                    System.arraycopy(q, drop, q, 0, qLen - drop)
                    qLen -= drop
                }
                // 4) ambil satu blok tertua, proses, putar
                System.arraycopy(q, 0, inBuf, 0, block)
                qLen -= block
                if (qLen > 0) System.arraycopy(q, block, q, 0, qLen)
                SysReverbEngine.nativeProcess(inBuf, outBuf, FRAMES)
                trk.write(outBuf, 0, outBuf.size)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "loop berhenti karena error", t)
            if (looping) report("Reverb Audio berhenti: ${t.javaClass.simpleName}")
        } finally {
            looping = false
            // Service ikut berhenti kalau loop mati sendiri (error / capture terputus).
            if (myId == session) Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    // Aman dipanggil berulang. rec.stop() dulu supaya read() yang sedang menunggu langsung kembali.
    private fun shutdownCapture() {
        session++
        looping = false
        runCatching { record?.stop() }
        runCatching { worker?.join(800) }
        worker = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        runCatching { record?.release() }
        track = null
        record = null
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        shutdownCapture()
        _running.value = false
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Reverb Audio", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, SysReverbService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Karaoke Mic")
            .setContentText("Reverb Audio aktif")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(0, "Matikan", stopPending)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }
}
