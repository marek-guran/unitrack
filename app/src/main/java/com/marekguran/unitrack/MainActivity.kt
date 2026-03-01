package com.marekguran.unitrack

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
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
import com.marekguran.unitrack.update.UpdateChecker

class MainActivity : AppCompatActivity() {

    companion object {
        /** Snapshot of the screen taken before a dark-mode toggle so we can
         *  animate the old→new theme transition with a paint-drop splash. */
        @JvmStatic
        var pendingThemeBitmap: Bitmap? = null

        /** Set to true right before [AppCompatDelegate.setDefaultNightMode] so
         *  the old activity's [onDestroy] does not recycle the bitmap that the
         *  new activity still needs. */
        @Volatile
        @JvmStatic
        var themeChangeInProgress = false

        private const val NOTIFICATION_ID_UPDATE = 5000
    }

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
        // Reset the flag early so it never stays stuck true after a failed onCreate
        val wasThemeChange = themeChangeInProgress
        themeChangeInProgress = false

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

        // "Paint-drop splash" animation from the dark-mode switch when activity
        // is recreated after a theme change.
        // 1. An old-theme screenshot overlays the new content.
        // 2. A paint drop falls from the switch to the bottom of the screen.
        // 3. On impact the drop "splashes" and an expanding hole in the overlay
        //    reveals the new theme from the bottom up — like a bucket of paint.
        if (savedInstanceState != null) {
            val cx = prefs.getInt("theme_anim_cx", -1)
            val cy = prefs.getInt("theme_anim_cy", -1)
            val oldBitmap = pendingThemeBitmap
            pendingThemeBitmap = null

            if (cx >= 0 && cy >= 0 && oldBitmap != null) {
                prefs.edit().remove("theme_anim_cx").remove("theme_anim_cy").apply()

                val decorView = window.decorView as? ViewGroup
                if (decorView != null) {
                    // --- Old-theme overlay (draws bitmap with an expanding circular hole) ---
                    // Added SYNCHRONOUSLY so it is drawn on the very first frame,
                    // eliminating any flicker of the new theme.
                    val overlay = object : View(this@MainActivity) {
                        var holeCx = 0f
                        var holeCy = 0f
                        var holeRadius = 0f

                        override fun onDraw(canvas: android.graphics.Canvas) {
                            if (oldBitmap.isRecycled) return
                            canvas.save()
                            if (holeRadius > 0f) {
                                val path = android.graphics.Path()
                                path.fillType = android.graphics.Path.FillType.EVEN_ODD
                                path.addRect(
                                    0f, 0f, width.toFloat(), height.toFloat(),
                                    android.graphics.Path.Direction.CW
                                )
                                path.addCircle(holeCx, holeCy, holeRadius,
                                    android.graphics.Path.Direction.CW
                                )
                                canvas.clipPath(path)
                            }
                            canvas.drawBitmap(oldBitmap, 0f, 0f, null)
                            canvas.restore()
                        }
                    }
                    decorView.addView(overlay, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))

                    // Start the drop animation after layout so we have screen dimensions
                    overlay.post {
                        val screenWidth = decorView.width
                        val screenHeight = decorView.height
                        val density = resources.displayMetrics.density

                        // --- Paint drop ---
                        val dropSize = (40 * density).toInt()
                        val drop = View(this@MainActivity)
                        val shape = android.graphics.drawable.GradientDrawable()
                        shape.shape = android.graphics.drawable.GradientDrawable.OVAL
                        val tv = TypedValue()
                        theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
                        shape.setColor(tv.data)
                        drop.background = shape
                        drop.elevation = 12 * density
                        decorView.addView(drop, FrameLayout.LayoutParams(dropSize, dropSize))
                        drop.translationX = cx - dropSize / 2f
                        drop.translationY = cy - dropSize / 2f
                        drop.scaleX = 0.6f
                        drop.scaleY = 0.6f
                        drop.alpha = 0.9f

                        // Phase 1 — drop falls from the switch to the bottom of the screen
                        val targetY = (screenHeight - dropSize).toFloat()
                        var cancelled = false
                        drop.animate()
                            .translationY(targetY)
                            .scaleX(1f)
                            .scaleY(1.3f)
                            .alpha(1f)
                            .setDuration(520)
                            .setInterpolator(AccelerateInterpolator(1.8f))
                            .setListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    if (cancelled) return

                                    // Phase 2 — splash: drop rapidly expands + fades
                                    drop.animate()
                                        .scaleX(6f)
                                        .scaleY(6f)
                                        .alpha(0f)
                                        .setDuration(250)
                                        .setInterpolator(DecelerateInterpolator())
                                        .setListener(null)
                                        .withEndAction {
                                            if (drop.parent != null) decorView.removeView(drop)
                                        }
                                        .start()

                                    // Phase 3 — inverted circular reveal
                                    val splashX = cx.toFloat()
                                    val splashY = screenHeight.toFloat()
                                    overlay.holeCx = splashX
                                    overlay.holeCy = splashY
                                    val maxRadius = kotlin.math.hypot(
                                        kotlin.math.max(splashX, screenWidth - splashX).toDouble(),
                                        screenHeight.toDouble()
                                    ).toFloat()

                                    val revealAnim = android.animation.ValueAnimator.ofFloat(0f, maxRadius)
                                    revealAnim.duration = 700
                                    revealAnim.interpolator = DecelerateInterpolator(1.8f)
                                    revealAnim.addUpdateListener { anim ->
                                        overlay.holeRadius = anim.animatedValue as Float
                                        overlay.invalidate()
                                    }
                                    revealAnim.addListener(object : android.animation.AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(a: android.animation.Animator) {
                                            if (overlay.parent != null) decorView.removeView(overlay)
                                            if (!oldBitmap.isRecycled) oldBitmap.recycle()
                                        }
                                    })
                                    revealAnim.start()
                                }
                                override fun onAnimationCancel(animation: android.animation.Animator) {
                                    cancelled = true
                                    if (drop.parent != null) decorView.removeView(drop)
                                    if (overlay.parent != null) decorView.removeView(overlay)
                                    if (!oldBitmap.isRecycled) oldBitmap.recycle()
                                }
                            })
                            .start()
                    }
                } else {
                    oldBitmap.recycle()
                }
            } else {
                oldBitmap?.recycle()
                if (cx >= 0 && cy >= 0) {
                    // Coordinates but no bitmap — simple circular reveal fallback
                    prefs.edit().remove("theme_anim_cx").remove("theme_anim_cy").apply()
                    binding.root.visibility = View.INVISIBLE
                    binding.root.post {
                        val maxR = kotlin.math.hypot(
                            binding.root.width.toDouble(), binding.root.height.toDouble()
                        ).toFloat()
                        val reveal = android.view.ViewAnimationUtils.createCircularReveal(
                            binding.root, cx, cy, 0f, maxR
                        )
                        reveal.duration = 500
                        reveal.interpolator = DecelerateInterpolator()
                        binding.root.visibility = View.VISIBLE
                        reveal.start()
                    }
                } else {
                    // Fallback crossfade for non-theme recreations
                    binding.root.alpha = 0f
                    binding.root.animate()
                        .alpha(1f)
                        .setDuration(400)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
        }

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

        // Periodic update check (debug builds only)
        if (BuildConfig.DEBUG) {
            scheduleUpdateCheck()
        }
    }

    private fun scheduleUpdateCheck() {
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (UpdateChecker.shouldCheck(this@MainActivity)) {
                    UpdateChecker.checkForUpdate(this@MainActivity, BuildConfig.VERSION_NAME) { hasUpdate, latestVersion ->
                        if (hasUpdate && latestVersion != null) {
                            showUpdateNotification(latestVersion)
                        }
                    }
                }
                if (!isFinishing && !isDestroyed) {
                    handler.postDelayed(this, UpdateChecker.CHECK_INTERVAL_MS)
                }
            }
        }
        // Run first check shortly after launch
        handler.postDelayed(updateRunnable, 5_000L)
    }

    private fun showUpdateNotification(latestVersion: String) {
        val channelId = "updates_channel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = android.app.NotificationChannel(
            channelId,
            getString(R.string.notification_channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_updates_desc)
        }
        nm.createNotificationChannel(channel)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.getReleaseUrl(latestVersion)))
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_update_available, latestVersion))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID_UPDATE, notification)
    }

    private fun buildNavigation(
        pillNav: PillNavigationBar,
        navController: androidx.navigation.NavController,
        includeAdminTabs: Boolean,
        isOnline: Boolean,
        showConsulting: Boolean = true
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
        } else if (isOnline && showConsulting) {
            // Students (non-admin, online) get the consulting hours tab
            labels.add(getString(R.string.title_consulting))
            icons.add(ContextCompat.getDrawable(this, R.drawable.ic_consulting)!!)
            destinations.add(R.id.navigation_consulting)
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
        val dbRef = FirebaseDatabase.getInstance().reference
        dbRef.child("admins").child(user.uid).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                // User is admin — rebuild navigation with admin tabs
                buildNavigation(pillNav, navController, includeAdminTabs = true, isOnline = true)
            } else {
                // Not admin — check if teacher (teachers should not see consulting booking)
                dbRef.child("teachers").child(user.uid).get().addOnSuccessListener { teacherSnap ->
                    if (teacherSnap.exists()) {
                        buildNavigation(pillNav, navController, includeAdminTabs = false, isOnline = true, showConsulting = false)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        noInternetDialog?.dismiss()
        // Prevent bitmap leak — but not if the activity is being recreated
        // for a theme change, because the new activity still needs the bitmap.
        if (!themeChangeInProgress) {
            pendingThemeBitmap?.recycle()
            pendingThemeBitmap = null
        }
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