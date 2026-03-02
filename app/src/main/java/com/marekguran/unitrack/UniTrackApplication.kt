package com.marekguran.unitrack

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.database.FirebaseDatabase

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

        // Enable Firebase Realtime Database disk persistence so that data is
        // cached locally. This lets the app serve cached data instantly and
        // continue working when the device temporarily loses connectivity.
        // Must be called before any other FirebaseDatabase usage.
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)

        val firebaseAppCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            // Debug builds use the App Check debug provider.
            // On first run the debug token is printed to logcat —
            // register it in Firebase Console → App Check → Debug tokens.
            firebaseAppCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        } else {
            // Release builds are signed by Google Play (SHA-256),
            // so Play Integrity attestation is used automatically.
            firebaseAppCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }
    }
}
