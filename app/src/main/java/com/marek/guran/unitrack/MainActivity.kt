package com.marek.guran.unitrack

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.fragment.NavHostFragment
import com.marek.guran.unitrack.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.marek.guran.unitrack.ui.login.LoginActivity
import androidx.core.view.get
import androidx.core.view.size
import androidx.core.view.WindowCompat

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

        // Start periodic internet check
        startPeriodicInternetCheck()

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val navController = navHostFragment.navController
        val navView: BottomNavigationView = binding.navView

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
                R.id.navigation_settings -> {
                    navController.navigate(R.id.navigation_settings)
                    true
                }
                else -> false
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.navigation_home -> {
                    navView.menu.setGroupCheckable(0, true, true)
                    navView.menu.findItem(R.id.navigation_home).isChecked = true
                }
                R.id.navigation_settings -> {
                    navView.menu.setGroupCheckable(0, true, true)
                    navView.menu.findItem(R.id.navigation_settings).isChecked = true
                }
                R.id.subjectDetailFragment -> {
                    navView.menu.setGroupCheckable(0, false, true)
                    for (i in 0 until navView.menu.size) {
                        navView.menu[i].isChecked = false
                    }
                }
                else -> {
                    navView.menu.setGroupCheckable(0, true, true)
                }
            }
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
}