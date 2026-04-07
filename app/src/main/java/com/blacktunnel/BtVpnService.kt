package com.blacktunnel

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BtVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null
    private var rawTunFd: Int? = null
    @Volatile private var isStopping = false
    @Volatile private var desiredRunning = false
    private var networkReceiverRegistered = false
    private val networkChangeReceiver = NetworkChangeReceiver()

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("BTCRASH", "Crash en ${thread.name}: ${throwable.message}")
            if (desiredRunning && !isStopping) scheduleRestart(applicationContext, 3000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
            when (intent?.action) {
                ACTION_STOP -> {
                    desiredRunning = false
                    TunnelPrefs.setWasConnected(this, false)
                    stopTunnel()
                    START_NOT_STICKY
                }
                else -> {
                    desiredRunning = true
                    TunnelPrefs.setWasConnected(this, true)
                    startTunnel()
                    START_STICKY
                }
            }
        }.getOrElse {
            android.util.Log.e("BTCRASH", "onStartCommand crash: ${it.message}")
            START_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { super.onTaskRemoved(rootIntent) }
        if (desiredRunning) runCatching { scheduleRestart(applicationContext, 1000) }
    }

    override fun onDestroy() {
        runCatching { if (desiredRunning) scheduleRestart(applicationContext, 2000) }
        runCatching { stopTunnel() }
        runCatching { super.onDestroy() }
    }

    private fun scheduleRestart(ctx: Context, delayMs: Long) {
        val pending = PendingIntent.getService(
            ctx, 99, startIntent(ctx),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        (ctx.getSystemService(ALARM_SERVICE) as AlarmManager)
            .setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending)
    }

    private fun startTunnel
