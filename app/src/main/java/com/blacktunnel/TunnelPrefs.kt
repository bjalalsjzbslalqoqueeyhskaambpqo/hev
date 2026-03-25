package com.blacktunnel

import android.content.Context

object TunnelPrefs {
    private const val PREFS = "tunnel_prefs"
    private const val KEY_MTU = "mtu"
    private const val KEY_MUX = "mux"

    fun getMtu(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MTU, 1300)

    fun getMux(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MUX, 8)

    fun setMtu(ctx: Context, mtu: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MTU, mtu).apply()
    }

    fun setMux(ctx: Context, mux: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MUX, mux).apply()
    }
}
