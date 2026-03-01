package com.marekguran.unitrack.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.net.URL

object UpdateChecker {

    private const val UPDATES_URL =
        "https://raw.githubusercontent.com/marek-guran/unitrack/main/updates.txt"
    private const val RELEASE_BASE_URL =
        "https://github.com/marek-guran/unitrack/releases/tag/"
    private const val PREFS_NAME = "update_checker"
    private const val KEY_LATEST_VERSION = "latest_version"
    private const val KEY_LAST_CHECK = "last_check_time"
    const val CHECK_INTERVAL_MS = 3_600_000L // 1 hour

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldCheck(context: Context): Boolean {
        val lastCheck = getPrefs(context).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS
    }

    fun checkForUpdate(
        context: Context,
        currentVersion: String,
        callback: (updateAvailable: Boolean, latestVersion: String?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val connection = URL(UPDATES_URL).openConnection()
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                val latestVersion = connection.getInputStream().bufferedReader().readText().trim()
                getPrefs(context).edit()
                    .putString(KEY_LATEST_VERSION, latestVersion)
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .apply()
                val hasUpdate = latestVersion != currentVersion
                mainHandler.post { callback(hasUpdate, if (hasUpdate) latestVersion else null) }
            } catch (_: Exception) {
                mainHandler.post { callback(false, null) }
            }
        }.start()
    }

    fun getCachedLatestVersion(context: Context): String? =
        getPrefs(context).getString(KEY_LATEST_VERSION, null)

    fun isUpdateAvailable(context: Context, currentVersion: String): Boolean {
        val latest = getCachedLatestVersion(context) ?: return false
        return latest != currentVersion
    }

    fun getReleaseUrl(version: String): String = RELEASE_BASE_URL + version
}
