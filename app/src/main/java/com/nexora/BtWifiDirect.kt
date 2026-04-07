package com.nexora

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build

object BtWifiDirect {

    private const val GATEWAY_IP = "192.168.49.1"
    private const val SOCKS5_PORT = 1080
    private const val HTTP_PORT = 8282

    @Volatile
    var isActive = false
        private set

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    fun getSavedPassword(ctx: Context): String {
        return ctx.getSharedPreferences("bt_prefs", Context.MODE_PRIVATE)
            .getString("wifidirect_pass", "12345678") ?: "12345678"
    }

    fun savePassword(ctx: Context, password: String) {
        ctx.getSharedPreferences("bt_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("wifidirect_pass", password)
            .apply()
    }

    fun start(ctx: Context, onResult: (success: Boolean) -> Unit) {
        val appCtx = ctx.applicationContext
        manager = appCtx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(appCtx, appCtx.mainLooper, null)
        val activeChannel = channel ?: run {
            isActive = false
            onResult(false)
            return
        }

        receiver?.let {
            runCatching { appCtx.unregisterReceiver(it) }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    manager?.requestGroupInfo(activeChannel) { group ->
                        isActive = group != null && group.isGroupOwner
                    }
                }
            }
        }
        appCtx.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-Nexora")
                .setPassphrase(getSavedPassword(appCtx))
                .build()
            manager?.createGroup(activeChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isActive = true
                    onResult(true)
                }

                override fun onFailure(reason: Int) {
                    isActive = false
                    onResult(false)
                }
            })
        } else {
            manager?.createGroup(activeChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isActive = true
                    onResult(true)
                }

                override fun onFailure(reason: Int) {
                    isActive = false
                    onResult(false)
                }
            })
        }
    }

    fun stop(ctx: Context) {
        val appCtx = ctx.applicationContext
        val activeChannel = channel
        if (activeChannel != null) manager?.removeGroup(activeChannel, null)
        receiver?.let { runCatching { appCtx.unregisterReceiver(it) } }
        receiver = null
        isActive = false
    }

    fun getConnectionInfo(ctx: Context) = mapOf(
        "ssid" to "DIRECT-Nexora",
        "password" to getSavedPassword(ctx),
        "ip" to GATEWAY_IP,
        "socks5" to SOCKS5_PORT,
        "http" to HTTP_PORT
    )
}
