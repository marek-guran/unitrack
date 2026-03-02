package com.marekguran.unitrack.data

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.marekguran.unitrack.R

private const val OFFLINE_MESSAGE = "Ste offline – môžete iba prezerať"

/**
 * Applies the same custom look used by the app's other Snackbars:
 * rounded corners, theme-derived colours, pill-nav anchor, high
 * elevation so it floats above dialogs.
 */
private fun styleOfflineSnackbar(snackbar: Snackbar) {
    val context = snackbar.view.context
    val tv = android.util.TypedValue()

    context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerLow, tv, true)
    val bgColor = tv.data
    context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, tv, true)
    val strokeColor = tv.data
    context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
    val textColor = tv.data

    val density = context.resources.displayMetrics.density
    val radius = 16 * density
    val strokeWidth = (1 * density).toInt()

    val bg = android.graphics.drawable.GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = radius
        setStroke(strokeWidth, strokeColor)
    }

    snackbar.view.background = bg
    snackbar.view.backgroundTintList = null
    snackbar.setTextColor(textColor)

    // Sit above the pill navigation bar when it exists
    try {
        (snackbar.view.context as? Activity)?.findViewById<View>(R.id.pillNavBar)?.let {
            snackbar.anchorView = it
        }
    } catch (_: Exception) { }

    val params = snackbar.view.layoutParams
    if (params is ViewGroup.MarginLayoutParams) {
        val margin = (12 * density).toInt()
        params.setMargins(margin, margin, margin, margin)
        snackbar.view.layoutParams = params
    }

    // Float above any open dialogs
    snackbar.view.elevation = 100 * density
}

/**
 * Returns `true` when Firebase is connected and writes are safe.
 * When offline, shows a styled Snackbar on the given [view] (or the
 * fragment's root view) and returns `false` so the caller can abort.
 *
 * In local / offline-mode ([OfflineMode.isOffline]) writes target the
 * local SQLite database, so the guard always returns `true`.
 */
fun Fragment.requireOnline(view: View? = this.view): Boolean {
    if (OfflineMode.isOffline(requireContext())) return true
    if (FirebaseConnectionMonitor.isConnected) return true
    val anchor = view ?: return false
    Snackbar.make(anchor, OFFLINE_MESSAGE, Snackbar.LENGTH_SHORT)
        .also { styleOfflineSnackbar(it) }
        .show()
    return false
}

/**
 * Activity variant of the same guard.
 */
fun Activity.requireOnline(view: View? = findViewById(android.R.id.content)): Boolean {
    if (OfflineMode.isOffline(this)) return true
    if (FirebaseConnectionMonitor.isConnected) return true
    val anchor = view ?: return false
    Snackbar.make(anchor, OFFLINE_MESSAGE, Snackbar.LENGTH_SHORT)
        .also { styleOfflineSnackbar(it) }
        .show()
    return false
}
