package com.blacktunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.ACTION_BOOT_COMPLETED"
        )
        if (intent.action !in validActions) return
        if (!TunnelPrefs.wasConnected(context)) return

        val vpnIntent = Intent(context, BtVpnService::class.java).setAction(BtVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(vpnIntent)
        else context.startService(vpnIntent)
    }
}
