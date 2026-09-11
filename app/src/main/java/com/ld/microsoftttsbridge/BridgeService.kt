package com.ld.microsoftttsbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class BridgeService : Service() {
    private val logTag = "MicrosoftTtsBridge"
    private var server: LocalHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val backend = intent?.getStringExtra(EXTRA_BACKEND) ?: "translator"
        val bindAddress = if (intent?.getBooleanExtra(EXTRA_BIND_ALL, false) == true) {
            "0.0.0.0"
        } else {
            "127.0.0.1"
        }

        createChannel()
        Log.i(logTag, "starting server bind=$bindAddress port=$port backend=$backend")
        val notification = notification(bindAddress, port, backend)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()

        val sharedCore = (application as MicrosoftTtsApplication).ttsCore.apply { warmUp() }

        server?.stop()
        server = LocalHttpServer(
            bindAddress = bindAddress,
            port = port,
            defaultBackend = backend,
            translator = sharedCore,
            log = { message -> Log.i(logTag, message) },
        ).also { it.start() }

        Log.i(logTag, "server start returned with warm-up dispatched")
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "TTS Bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:bridge")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun notification(bindAddress: String, port: Int, backend: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle("Microsoft TTS Bridge")
            .setContentText("$bindAddress:$port · $backend")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        const val ACTION_START = "com.ld.microsoftttsbridge.START"
        const val ACTION_STOP = "com.ld.microsoftttsbridge.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_BIND_ALL = "bindAll"
        const val DEFAULT_PORT = 8765
        private const val CHANNEL_ID = "microsoft_tts_bridge"
        private const val NOTIFICATION_ID = 8765
    }
}
