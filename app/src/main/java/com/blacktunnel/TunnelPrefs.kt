package com.blacktunnel

import android.content.Context

object TunnelPrefs {
    private const val PREFS = "tunnel_prefs"
    private const val KEY_MTU = "mtu"
    private const val KEY_MUX = "mux"
    private const val KEY_PROFILE = "profile"
    private const val KEY_INCLUDED_APPS = "included_apps"
    private const val KEY_CENTRAL_SERVERS = "central_servers"
    private const val KEY_SELECTED_SERVER = "selected_server"
    private const val KEY_HOTSPOT_PROXY = "hotspot_proxy"

    fun getMtu(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MTU, 1300)

    fun getMux(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MUX, 16)

    fun setMtu(ctx: Context, mtu: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MTU, mtu).apply()
    }

    fun setMux(ctx: Context, mux: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MUX, mux).apply()
    }

    fun getProfile(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROFILE, "normal") ?: "normal"

    fun setProfile(ctx: Context, profile: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROFILE, profile).apply()
    }

    fun getIncludedApps(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INCLUDED_APPS, "")
            .orEmpty()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun setIncludedApps(ctx: Context, packages: List<String>) {
        val raw = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_INCLUDED_APPS, raw).apply()
    }

    fun getCentralServers(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CENTRAL_SERVERS, "")
            .orEmpty()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun setCentralServers(ctx: Context, servers: List<String>) {
        val raw = servers.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CENTRAL_SERVERS, raw).apply()
    }

    fun getSelectedServer(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_SERVER, "")
            ?: ""

    fun setSelectedServer(ctx: Context, server: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED_SERVER, server).apply()
    }


    fun isHotspotProxyEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOTSPOT_PROXY, false)

    fun setHotspotProxyEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOTSPOT_PROXY, enabled).apply()
    }

}
