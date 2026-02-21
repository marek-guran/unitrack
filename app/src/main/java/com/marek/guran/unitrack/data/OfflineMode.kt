package com.marek.guran.unitrack.data

import android.content.Context

object OfflineMode {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_OFFLINE_MODE = "offline_mode"
    const val LOCAL_USER_UID = "local_user"

    fun isOffline(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_OFFLINE_MODE, false)
    }

    fun setOffline(context: Context, offline: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, offline).apply()
    }

    fun resetMode(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_OFFLINE_MODE).apply()
    }
}
