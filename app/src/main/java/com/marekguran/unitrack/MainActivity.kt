package com.marekguran.unitrack

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.marekguran.unitrack.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.marekguran.unitrack.data.OfflineMode
import com.marekguran.unitrack.notification.NextClassAlarmReceiver
import com.marekguran.unitrack.ui.PillNavigationBar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var noInternetDialog: AlertDialog? = null
    private var dialogDismissedManually = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval: Long = 10000

    // Navigation destination IDs mapped to pill nav indices
    private lateinit var navDestinations: List<Int>

    // Notification permission request for Android 13+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, we proceed gracefully */ }

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

        // Update status bar icon colors to match current theme
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isDark

        val isOffline = OfflineMode.isOffline(this)

        if (!isOffline) {
            startPeriodicInternetCheck()
        }

        if (!isOffline) {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                startActivity(Intent(this, com.marekguran.unitrack.ui.login.LoginActivity::class.java))
                finish()
                return
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle navigation bar inset for the pill nav so it sits above the system nav bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.pillNavBar) { view, insets ->
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val lp = view.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            lp.bottomMargin = navBarInset + (4 * resources.displayMetrics.density).toInt()
            view.layoutParams = lp
            insets
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val navController = navHostFragment.navController
        val pillNav: PillNavigationBar = binding.pillNavBar

        if (isOffline) {
            // Offline mode — always show Students + Subjects tabs
            buildNavigation(pillNav, navController, includeAdminTabs = true, isOnline = false)
        } else {
            // Online mode — start without admin tabs, then check if admin
            buildNavigation(pillNav, navController, includeAdminTabs = false, isOnline = true)
            checkAdminAndRebuildNav(pillNav, navController)
        }

        // Request notification permission for Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Initialize Live Update notification for next class
        NextClassAlarmReceiver.createNotificationChannels(this)
        NextClassAlarmReceiver.triggerNextClassCheck(this)
        NextClassAlarmReceiver.scheduleNextClass(this)
        NextClassAlarmReceiver.scheduleChangesCheck(this)
    }

    private fun buildNavigation(
        pillNav: PillNavigationBar,
        navController: androidx.navigation.NavController,
        includeAdminTabs: Boolean,
        isOnline: Boolean
    ) {
        val labels = mutableListOf<String>()
        val icons = mutableListOf<Drawable>()
        val destinations = mutableListOf<Int>()

        labels.add(getString(R.string.title_home))
        icons.add(ContextCompat.getDrawable(this, R.drawable.home)!!)
        destinations.add(R.id.navigation_home)

        labels.add(getString(R.string.title_timetable))
        icons.add(ContextCompat.getDrawable(this, R.drawable.ic_timetable)!!)
        destinations.add(R.id.navigation_timetable)

        if (includeAdminTabs) {
            // Use "Účty" for online, "Študenti" for offline
            val studentsLabel = if (isOnline) getString(R.string.title_accounts) else getString(R.string.title_students)
            labels.add(studentsLabel)
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

    private fun checkAdminAndRebuildNav(
        pillNav: PillNavigationBar,
        navController: androidx.navigation.NavController
    ) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val adminsRef = FirebaseDatabase.getInstance().reference.child("admins")
        adminsRef.child(user.uid).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                // User is admin — rebuild navigation with admin tabs
                buildNavigation(pillNav, navController, includeAdminTabs = true, isOnline = true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        noInternetDialog?.dismiss()
    }

    private fun startPeriodicInternetCheck() {
        // Delay the first check to avoid showing the dialog immediately on activity
        // creation (e.g. after screen unlock or rotation)
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
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
        }, checkInterval)
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showNoInternetDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Bez pripojenia na internet"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Na používanie tejto aplikácie je potrebné pripojenie na internet. Pripojte sa k Wi-Fi alebo k mobilným dátam."
        val confirmBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Nastavenia"
        val cancelBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
        cancelBtn.text = "Zrušiť"
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        cancelBtn.setOnClickListener {
            dialog.dismiss()
            dialogDismissedManually = true
        }
        dialog.setOnDismissListener {
            dialogDismissedManually = true
        }
        noInternetDialog = dialog
        dialog.show()
    }
}