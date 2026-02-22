package com.marek.guran.unitrack

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.marek.guran.unitrack.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.marek.guran.unitrack.data.OfflineMode
import com.marek.guran.unitrack.ui.PillNavigationBar
import com.marek.guran.unitrack.ui.login.LoginActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var noInternetDialog: AlertDialog? = null
    private var dialogDismissedManually = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval: Long = 10000

    // Navigation destination IDs mapped to pill nav indices
    private lateinit var navDestinations: List<Int>

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("app_settings", 0)
        val useDarkMode = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (useDarkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportActionBar?.hide()

        val isOffline = OfflineMode.isOffline(this)

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

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val navController = navHostFragment.navController
        val pillNav: PillNavigationBar = binding.pillNavBar

        // Build nav items based on online/offline mode
        val labels = mutableListOf<String>()
        val icons = mutableListOf<Drawable>()
        val destinations = mutableListOf<Int>()

        labels.add(getString(R.string.title_home))
        icons.add(ContextCompat.getDrawable(this, R.drawable.home)!!)
        destinations.add(R.id.navigation_home)

        if (isOffline) {
            labels.add(getString(R.string.title_students))
            icons.add(ContextCompat.getDrawable(this, R.drawable.ic_people)!!)
            destinations.add(R.id.navigation_students)
            labels.add(getString(R.string.title_subjects))
            icons.add(ContextCompat.getDrawable(this, R.drawable.ic_book)!!)
            destinations.add(R.id.navigation_subjects)
        }

        labels.add(getString(R.string.title_settings))
        icons.add(ContextCompat.getDrawable(this, R.drawable.settings)!!)
        destinations.add(R.id.navigation_settings)

        navDestinations = destinations

        // NavOptions with fade animations for fragment transitions
        val navOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.fade_in)
            .setExitAnim(R.anim.fade_out)
            .setPopEnterAnim(R.anim.fade_in)
            .setPopExitAnim(R.anim.fade_out)
            .build()

        // Use icons on phone, text on tablet
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        if (isTablet) {
            pillNav.setItems(labels)
        } else {
            pillNav.setIconItems(icons)
        }

        // Handle pill nav selection
        pillNav.onItemSelected = { index ->
            if (index in navDestinations.indices) {
                val destId = navDestinations[index]
                if (destId == R.id.navigation_home) {
                    navController.popBackStack(R.id.navigation_home, false)
                }
                navController.navigate(destId, null, navOptions)
            }
        }

        // Also allow re-tapping "Domov" to go home even if already selected
        pillNav.onItemReselected = { index ->
            if (index in navDestinations.indices) {
                val destId = navDestinations[index]
                if (destId == R.id.navigation_home) {
                    navController.popBackStack(R.id.navigation_home, false)
                }
            }
        }

        // Sync pill nav when destination changes (e.g. back navigation)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val idx = navDestinations.indexOf(destination.id)
            if (idx >= 0) {
                pillNav.setSelectedIndex(idx)
            } else if (destination.id == R.id.subjectDetailFragment) {
                // Subject detail is child of Home
                val homeIdx = navDestinations.indexOf(R.id.navigation_home)
                if (homeIdx >= 0) pillNav.setSelectedIndex(homeIdx)
            }
        }

        // Animate pill nav entrance (slide from top on tablet, bottom on phone)
        if (isTablet) {
            pillNav.translationY = -80f
        } else {
            pillNav.translationY = 80f
        }
        pillNav.alpha = 0f
        pillNav.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(200)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .start()
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
                    if ((noInternetDialog == null || !noInternetDialog!!.isShowing) && !dialogDismissedManually) {
                        showNoInternetDialog()
                    }
                } else {
                    noInternetDialog?.dismiss()
                    noInternetDialog = null
                    dialogDismissedManually = false
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
                dialogDismissedManually = true
            }
            .setOnDismissListener { }
            .show()
    }
}