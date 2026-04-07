package com.nexora

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class TunnelCoreService : Service() {

    @Volatile
    private var desiredRunning = false

    private val sessionListener: (TunnelSessionSnapshot) -> Unit = { snapshot ->
        if (desiredRunning && (snapshot.state == "ERROR" || snapshot.state == "DISCONNECTED")) {
            startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
        }
    }

    override fun onCreate() {
        super.onCreate()
        TunnelSessionStore.addListener(sessionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                desiredRunning = false
                TunnelPrefs.setWasConnected(this, false)
                stopVpnAndSelf()
                START_NOT_STICKY
            }
            else -> {
                desiredRunning = true
                TunnelPrefs.setWasConnected(this, true)
                startCoreForeground()
                startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_START))
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        TunnelSessionStore.removeListener(sessionListener)
        if (desiredRunning) {
            val restartIntent = Intent(this, TunnelCoreService::class.java).setAction(ACTION_START)
            val pending = PendingIntent.getService(
                this,
                301,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1500,
                pending
            )
        }
        super.onDestroy()
    }

    private fun stopVpnAndSelf() {
        startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startCoreForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CORE_CHANNEL_ID, "Nexora Core", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelCoreService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CORE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Nexora túnel")
            .setContentText("Servicio núcleo activo")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopIntent)
            .build()

        startForeground(CORE_NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.nexora.CORE_START"
        const val ACTION_STOP = "com.nexora.CORE_STOP"
        private const val CORE_CHANNEL_ID = "nexora_core"
        private const val CORE_NOTIFICATION_ID = 9102
    }
}
