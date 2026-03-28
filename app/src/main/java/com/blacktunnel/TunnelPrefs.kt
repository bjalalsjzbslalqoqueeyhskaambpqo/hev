package com.blacktunnel

import android.content.Context

object TunnelPrefs {
    private const val PREFS = "tunnel_prefs"
    private const val KEY_MUX = "mux"
    private const val KEY_PROFILE = "profile"
    private const val KEY_INCLUDED_APPS = "included_apps"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_HOTSPOT_PROXY = "hotspot_proxy"
    private const val KEY_BLOCK_NON_SELECTED = "block_non_selected"

    fun getMux(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MUX, 16)

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

    fun getOrCreateClientId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_CLIENT_ID, "").orEmpty().trim()
        if (current.isNotEmpty()) return current
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_CLIENT_ID, generated).apply()
        return generated
    }

    fun isHotspotProxyEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOTSPOT_PROXY, false)

    fun setHotspotProxyEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOTSPOT_PROXY, enabled).apply()
    }

    fun isBlockNonSelectedEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BLOCK_NON_SELECTED, false)

    fun setBlockNonSelectedEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BLOCK_NON_SELECTED, enabled).apply()
    }

}
