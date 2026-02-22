package com.marekguran.unitrack

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class UniTrackApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Apply the user's chosen theme mode as early as possible so every
        // Activity (including any that launch before MainActivity) starts
        // with the correct day/night configuration.
        val prefs = getSharedPreferences("app_settings", 0)
        val useDark = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (useDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
