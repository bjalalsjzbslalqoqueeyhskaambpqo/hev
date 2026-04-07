package com.blacktunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build

class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!TunnelPrefs.wasConnected(context)) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val isConnected = cm.activeNetworkInfo?.isConnected == true
        if (!isConnected) return

        val vpnIntent = BtVpnService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(vpnIntent)
        else context.startService(vpnIntent)
    }
}
