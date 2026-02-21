package com.marek.guran.unitrack

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.navigation.fragment.NavHostFragment
import com.marek.guran.unitrack.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.marek.guran.unitrack.ui.login.LoginActivity
import androidx.core.view.get
import androidx.core.view.size
import androidx.core.view.WindowCompat
import com.google.android.material.color.MaterialColors
import com.marek.guran.unitrack.data.OfflineMode

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var noInternetDialog: AlertDialog? = null
    private var dialogDismissedManually = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval: Long = 10000 // 10 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("app_settings", 0)
        val useDarkMode = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (useDarkMode)
                AppCompatDelegate.MODE_NIGHT_YES
            else
                AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        supportActionBar?.hide()

        val isOffline = OfflineMode.isOffline(this)

        // Start periodic internet check only in online mode
        if (!isOffline) {
            startPeriodicInternetCheck()
        }

        if (!isOffline) {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val navController = navHostFragment.navController
        val navView: BottomNavigationView = binding.navView

        // Show Students and Subjects tabs only in offline mode
        navView.menu.findItem(R.id.navigation_students).isVisible = isOffline
        navView.menu.findItem(R.id.navigation_subjects).isVisible = isOffline

        navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    navController.popBackStack(R.id.navigation_home, false)
                    navController.navigate(R.id.navigation_home)
                    true
                }
                R.id.navigation_dashboard -> {
                    navController.navigate(R.id.navigation_dashboard)
                    true
                }
                R.id.navigation_students -> {
                    navController.navigate(R.id.navigation_students)
                    true
                }
                R.id.navigation_subjects -> {
                    navController.navigate(R.id.navigation_subjects)
                    true
                }
                R.id.navigation_settings -> {
                    navController.navigate(R.id.navigation_settings)
                    true
                }
                else -> false
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Subtle scale animation on bottom nav when switching tabs
            navView.animate()
                .scaleX(0.98f)
                .scaleY(0.98f)
                .setDuration(100)
                .withEndAction {
                    navView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()

            when (destination.id) {
                R.id.navigation_home -> {
                    navView.menu.setGroupCheckable(0, true, true)
                    navView.menu.findItem(R.id.navigation_home).isChecked = true
                }
                R.id.navigation_settings -> {
                    navView.menu.setGroupCheckable(0, true, true)
                    navView.menu.findItem(R.id.navigation_settings).isChecked = true
                }
                R.id.navigation_students -> {
                    navView.menu.setGroupCheckable(0, true, true)
                    navView.menu.findItem(R.id.navigation_students).isChecked = true
                }
                R.id.navigation_subjects -> {
                    navView.menu.setGroupCheckable(0, true, true)
                    navView.menu.findItem(R.id.navigation_subjects).isChecked = true
                }
                R.id.subjectDetailFragment -> {
                    // Keep Home selected since subject detail is a sub-destination of Home
                    navView.menu.setGroupCheckable(0, true, true)
                    navView.menu.findItem(R.id.navigation_home).isChecked = true
                }
                else -> {
                    navView.menu.setGroupCheckable(0, true, true)
                }
            }
        }

        // Set up frosted glass blur effect on bottom nav
        val blurView = binding.blurView
        val blurTarget = binding.blurTarget
        val windowBackground = window.decorView.background

        blurView.setupWith(blurTarget)
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(20f)

        // Derive nav overlay color from theme's primary color, darkened for distinction
        applyDarkenedPrimaryNavBackground(blurView)

        // Animate bottom nav entrance
        blurView.translationY = 100f
        blurView.alpha = 0f
        blurView.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(300)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
    }

    override fun onResume() {
        super.onResume()
        // Re-apply nav styling when returning (e.g. after dark mode toggle in settings)
        if (::binding.isInitialized) {
            applyDarkenedPrimaryNavBackground(binding.blurView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        noInternetDialog?.dismiss()
    }

    private fun startPeriodicInternetCheck() {
        handler.post(object : Runnable {
            override fun run() {
                if (!isInternetAvailable()) {
                    // Only show dialog if not already showing and not previously dismissed manually
                    if ((noInternetDialog == null || !noInternetDialog!!.isShowing) && !dialogDismissedManually) {
                        showNoInternetDialog()
                    }
                } else {
                    // Connection is back, dismiss dialog if showing and reset manual dismissal flag
                    noInternetDialog?.dismiss()
                    noInternetDialog = null
                    dialogDismissedManually = false // <--- Only reset here!
                }
                handler.postDelayed(this, checkInterval)
            }
        })
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Show dialog if no internet, with an option to open WiFi settings
    private fun showNoInternetDialog() {
        noInternetDialog = AlertDialog.Builder(this)
            .setTitle("Bez pripojenia na internet")
            .setMessage("Na používanie tejto aplikácie je potrebné pripojenie na internet. Pripojte sa k Wi-Fi alebo k mobilným dátam.")
            .setCancelable(false)
            .setPositiveButton("Nastavenia") { _, _ ->
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .setNegativeButton("Zrušiť") { dialog, _ ->
                dialog.dismiss()
                dialogDismissedManually = true // Only set when user cancels
            }
            .setOnDismissListener {
                // Don't set dialogDismissedManually here; only on user cancel!
            }
            .show()
    }

    /**
     * Derives a darkened shade of the theme's primary color and applies it as
     * a semi-transparent rounded background on the bottom nav BlurView overlay.
     * This ensures the nav is always visually distinct from card backgrounds
     * while respecting dynamic/wallpaper colors.
     */
    private fun applyDarkenedPrimaryNavBackground(blurView: eightbitlab.com.blurview.BlurView) {
        // Resolve colorPrimary from the current theme (respects dynamic colors)
        val primaryColor = MaterialColors.getColor(blurView, android.R.attr.colorPrimary, Color.GRAY)

        // Darken the primary color by blending it with black (30% darker)
        val darkenedColor = ColorUtils.blendARGB(primaryColor, Color.BLACK, 0.30f)

        // Apply semi-transparency (alpha ~70% = 0xB3) for frosted glass effect
        val overlayColor = ColorUtils.setAlphaComponent(darkenedColor, 0xB3)

        // Create a rounded shape drawable programmatically
        val navBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * resources.displayMetrics.density // 24dp in pixels
            setColor(overlayColor)
        }

        blurView.background = navBackground

        // Set contrasting icon/text colors for the dark nav overlay
        val navView: BottomNavigationView = binding.navView
        val unselectedColor = ColorUtils.setAlphaComponent(Color.WHITE, 0xB3)
        val pillColor = MaterialColors.getColor(blurView, com.google.android.material.R.attr.colorPrimaryContainer, Color.WHITE)

        navView.itemIconTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(darkenedColor, unselectedColor)
        )

        // In dark mode, white text for readability; in light mode, text uses pill color
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val selectedTextColor = if (isDarkMode) Color.WHITE else pillColor
        navView.itemTextColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(selectedTextColor, unselectedColor)
        )
    }
}