package com.marekguran.unitrack

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID

class QrAttendanceActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanListener: ValueEventListener? = null
    private var failListener: ValueEventListener? = null
    private val presentStudentUids = mutableSetOf<String>()
    private var currentCode = ""
    private var lastScanTimestamp: Long = 0
    private var lastFailTimestamp: Long = 0
    private var scanListenerReady = false
    private var failListenerReady = false
    private var currentFilter: Int = FILTER_ALL
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null

    companion object {
        const val EXTRA_SUBJECT_KEY = "extra_subject_key"
        const val EXTRA_SUBJECT_NAME = "extra_subject_name"
        const val EXTRA_SCHOOL_YEAR = "extra_school_year"
        const val EXTRA_SEMESTER = "extra_semester"
        const val EXTRA_STUDENT_UIDS = "extra_student_uids"
        const val EXTRA_STUDENT_NAMES = "extra_student_names"

        const val FILTER_ALL = 0
        const val FILTER_PRESENT = 1
        const val FILTER_ERRORS = 2
    }

    private lateinit var subjectKey: String
    private lateinit var subjectName: String
    private lateinit var schoolYear: String
    private lateinit var semester: String
    private lateinit var studentUids: Array<String>
    private lateinit var studentNames: Array<String>

    private lateinit var qrImageView: ImageView
    private lateinit var confirmationOverlay: View
    private lateinit var confirmStudentName: TextView
    private lateinit var confirmCheckmark: ImageView
    private lateinit var confirmLabel: TextView
    private lateinit var scannedCountView: TextView

    private val db = FirebaseDatabase.getInstance(
        "https://unitrack-ku-default-rtdb.europe-west1.firebasedatabase.app"
    ).reference

    // Scan log entries
    data class ScanLogEntry(
        val name: String,
        val timestampMs: Long,
        val isWarning: Boolean = false,
        val warningReason: String = "",
        var expanded: Boolean = false
    )

    private val scanLogEntries = mutableListOf<ScanLogEntry>()
    private val filteredEntries = mutableListOf<ScanLogEntry>()
    private lateinit var scanLogAdapter: ScanLogAdapter
    private lateinit var scanLogRecycler: RecyclerView
    private lateinit var chipGroup: ChipGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_attendance)

        // Keep screen awake while showing QR codes
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        subjectKey = intent.getStringExtra(EXTRA_SUBJECT_KEY) ?: ""
        subjectName = intent.getStringExtra(EXTRA_SUBJECT_NAME) ?: ""
        schoolYear = intent.getStringExtra(EXTRA_SCHOOL_YEAR) ?: ""
        semester = intent.getStringExtra(EXTRA_SEMESTER) ?: ""
        studentUids = intent.getStringArrayExtra(EXTRA_STUDENT_UIDS) ?: emptyArray()
        studentNames = intent.getStringArrayExtra(EXTRA_STUDENT_NAMES) ?: emptyArray()

        qrImageView = findViewById(R.id.qrImageView)
        confirmationOverlay = findViewById(R.id.confirmationOverlay)
        confirmStudentName = findViewById(R.id.confirmStudentName)
        confirmCheckmark = findViewById(R.id.confirmCheckmark)
        confirmLabel = findViewById(R.id.confirmLabel)
        scannedCountView = findViewById(R.id.qrScannedCount)

        // Set up scan log RecyclerView
        scanLogRecycler = findViewById(R.id.scanLogRecycler)
        scanLogAdapter = ScanLogAdapter(filteredEntries)
        scanLogRecycler.layoutManager = LinearLayoutManager(this)
        scanLogRecycler.adapter = scanLogAdapter

        // Set up filter chips
        chipGroup = findViewById(R.id.filterChipGroup)
        findViewById<Chip>(R.id.chipAll).setOnClickListener { setFilter(FILTER_ALL) }
        findViewById<Chip>(R.id.chipPresent).setOnClickListener { setFilter(FILTER_PRESENT) }
        findViewById<Chip>(R.id.chipErrors).setOnClickListener { setFilter(FILTER_ERRORS) }

        findViewById<TextView>(R.id.qrTitle).text = subjectName
        findViewById<TextView>(R.id.qrSubjectName).text = "QR Prezenčka"
        updateScannedCount()

        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { confirmClose() }
        findViewById<MaterialButton>(R.id.btnEndAttendance).setOnClickListener { endAttendance() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmClose()
            }
        })

        // Generate first QR code and start listening
        generateNewCode()
        // Clear stale data from previous sessions before attaching listeners
        qrLastScanRef().removeValue()
        qrFailRef().removeValue()
        startListeningForScans()
        startListeningForFailedAttempts()

        // Start periodic timestamp refresh
        startTimestampRefresh()

        // Animate QR card entrance
        val qrCard = findViewById<View>(R.id.qrCard)
        qrCard.scaleX = 0f
        qrCard.scaleY = 0f
        qrCard.alpha = 0f
        qrCard.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    private fun qrCodeRef() = db.child("pritomnost")
        .child(schoolYear).child(semester).child(subjectKey).child("qr_code")

    private fun qrLastScanRef() = db.child("pritomnost")
        .child(schoolYear).child(semester).child(subjectKey).child("qr_last_scan")

    private fun qrFailRef() = db.child("pritomnost")
        .child(schoolYear).child(semester).child(subjectKey).child("qr_fail")

    private fun generateNewCode() {
        currentCode = UUID.randomUUID().toString().replace("-", "").take(16)
        val qrContent = "UNITRACK|$schoolYear|$semester|$subjectKey|$currentCode"
        val bitmap = generateQrBitmap(qrContent, 512)
        // Crossfade QR image
        qrImageView.animate().alpha(0f).setDuration(150).withEndAction {
            qrImageView.setImageBitmap(bitmap)
            qrImageView.animate().alpha(1f).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
        // Write code to Firebase (single item, rewritten each time)
        qrCodeRef().setValue(currentCode)
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }

    private fun startListeningForScans() {
        scanListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Skip the initial callback (may contain stale data from a previous session)
                if (!scanListenerReady) {
                    scanListenerReady = true
                    return
                }
                val uid = snapshot.child("uid").getValue(String::class.java) ?: return
                val time = snapshot.child("time").getValue(Long::class.java) ?: 0L
                if (uid.isBlank() || time <= lastScanTimestamp) return
                lastScanTimestamp = time

                // Resolve real name from enrolled student list, fall back to Firebase name
                val name = resolveStudentName(uid)
                    ?: snapshot.child("name").getValue(String::class.java)
                    ?: return

                if (uid !in presentStudentUids) {
                    presentStudentUids.add(uid)
                    addScanLogEntry(name, false)
                    showConfirmationAndRegenerate(name)
                } else {
                    // Duplicate scan — regenerate QR so remaining students can continue
                    generateNewCode()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        qrLastScanRef().addValueEventListener(scanListener!!)
    }

    private fun startListeningForFailedAttempts() {
        failListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Skip the initial callback (may contain stale data from a previous session)
                if (!failListenerReady) {
                    failListenerReady = true
                    return
                }
                val reason = snapshot.child("reason").getValue(String::class.java) ?: return
                val time = snapshot.child("time").getValue(Long::class.java) ?: 0L
                if (reason.isBlank() || time <= lastFailTimestamp) return
                lastFailTimestamp = time

                // Regenerate QR code so the next student can scan immediately
                generateNewCode()

                // Resolve real name from uid if available, fall back to Firebase name
                val uid = snapshot.child("uid").getValue(String::class.java)
                val localName = if (uid != null) resolveStudentName(uid) else null
                val fbName = snapshot.child("name").getValue(String::class.java)

                if (localName != null) {
                    addScanLogEntry(localName, true, reason)
                } else if (fbName != null && fbName != "Študent") {
                    addScanLogEntry(fbName, true, reason)
                } else if (uid != null) {
                    // Student not enrolled — look up their name from the database
                    db.child("students").child(uid).child("name").get()
                        .addOnSuccessListener { nameSnap ->
                            val dbName = nameSnap.getValue(String::class.java)
                                ?.takeIf { it.isNotBlank() } ?: fbName ?: "Študent"
                            addScanLogEntry(dbName, true, reason)
                        }
                        .addOnFailureListener {
                            addScanLogEntry(fbName ?: "Študent", true, reason)
                        }
                } else {
                    addScanLogEntry(fbName ?: "Študent", true, reason)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        qrFailRef().addValueEventListener(failListener!!)
    }

    private fun resolveStudentName(uid: String): String? {
        val idx = studentUids.indexOf(uid)
        return if (idx >= 0 && idx < studentNames.size) studentNames[idx] else null
    }

    private fun addScanLogEntry(name: String, isWarning: Boolean, reason: String = "") {
        scanLogEntries.add(0, ScanLogEntry(name, System.currentTimeMillis(), isWarning, reason))
        rebuildFilteredList()
    }

    private fun setFilter(filter: Int) {
        currentFilter = filter
        rebuildFilteredList()
    }

    private fun rebuildFilteredList() {
        filteredEntries.clear()
        filteredEntries.addAll(
            when (currentFilter) {
                FILTER_PRESENT -> scanLogEntries.filter { !it.isWarning }
                FILTER_ERRORS -> scanLogEntries.filter { it.isWarning }
                else -> scanLogEntries
            }
        )
        scanLogAdapter.notifyDataSetChanged()
        if (filteredEntries.isNotEmpty()) {
            scanLogRecycler.scrollToPosition(0)
        }
    }

    private fun startTimestampRefresh() {
        val refreshRunnable = object : Runnable {
            override fun run() {
                scanLogAdapter.notifyItemRangeChanged(0, filteredEntries.size)
                handler.postDelayed(this, 30_000) // Refresh every 30 seconds
            }
        }
        handler.postDelayed(refreshRunnable, 30_000)
    }

    private fun showConfirmationAndRegenerate(studentName: String) {
        updateScannedCount()

        // Show confirmation overlay with animations
        confirmStudentName.text = studentName
        confirmationOverlay.visibility = View.VISIBLE

        // Reset animation state
        confirmCheckmark.scaleX = 0f
        confirmCheckmark.scaleY = 0f
        confirmLabel.alpha = 0f
        confirmStudentName.alpha = 0f

        // Fade in overlay
        confirmationOverlay.animate().alpha(1f).setDuration(150).withEndAction {
            // Animate student name
            confirmStudentName.animate().alpha(1f).setDuration(150)
                .setInterpolator(DecelerateInterpolator()).start()

            // Animate checkmark with bounce
            val scaleX = ObjectAnimator.ofFloat(confirmCheckmark, "scaleX", 0f, 1f)
            val scaleY = ObjectAnimator.ofFloat(confirmCheckmark, "scaleY", 0f, 1f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 300
                interpolator = OvershootInterpolator(2f)
                start()
            }

            // Fade in label
            confirmLabel.animate().alpha(1f).setDuration(200).setStartDelay(100).start()
        }.start()

        // After 1 second, hide overlay and show new QR code
        handler.postDelayed({
            confirmationOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                confirmationOverlay.visibility = View.GONE
                generateNewCode()
            }.start()
        }, 1000)
    }

    private fun updateScannedCount() {
        scannedCountView.text = "${presentStudentUids.size}/${studentUids.size}"
    }

    private fun endAttendance() {
        val presentCount = presentStudentUids.size
        val absentCount = studentUids.size - presentCount

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Ukončiť prezenčku?")
            .setMessage("Prítomní: $presentCount\nNeprítomní: $absentCount")
            .setPositiveButton("Ukončiť") { _, _ ->
                saveAttendanceAndFinish()
            }
            .setNegativeButton("Neukončiť", null)
            .show()
        activeDialog = dialog

        // Style buttons: make "Ukončiť" bold/filled, "Neukončiť" subdued
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.let { btn ->
            btn.isAllCaps = false
            btn.setTextColor(getColor(android.R.color.white))
            btn.setBackgroundColor(resolveThemeColor(android.R.attr.colorError))
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.let { btn ->
            btn.isAllCaps = false
        }
    }

    private fun saveAttendanceAndFinish() {
        val today = java.time.LocalDate.now().toString()
        val now = java.time.LocalTime.now().toString()

        var completed = 0
        val total = studentUids.size

        for (i in studentUids.indices) {
            val uid = studentUids[i]
            val isPresent = uid in presentStudentUids
            val entry = mapOf(
                "date" to today,
                "time" to now,
                "note" to "",
                "absent" to !isPresent
            )
            db.child("pritomnost")
                .child(schoolYear).child(semester).child(subjectKey)
                .child(uid).push().setValue(entry) { _, _ ->
                    completed++
                    if (completed == total) {
                        cleanupAndFinish()
                    }
                }
        }

        if (total == 0) cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
        // Remove QR code and last scan data from Firebase
        qrCodeRef().removeValue()
        qrLastScanRef().removeValue()
        qrFailRef().removeValue()
        activeDialog?.let { if (it.isShowing) it.dismiss() }
        activeDialog = null
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmClose() {
        if (presentStudentUids.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Zrušiť prezenčku?")
                .setMessage("Údaje o dochádzke nebudú uložené.")
                .setPositiveButton("Zrušiť") { _, _ ->
                    qrCodeRef().removeValue()
                    qrLastScanRef().removeValue()
                    qrFailRef().removeValue()
                    finish()
                }
                .setNegativeButton("Pokračovať", null)
                .show()
        } else {
            qrCodeRef().removeValue()
            qrLastScanRef().removeValue()
            qrFailRef().removeValue()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeDialog?.let { if (it.isShowing) it.dismiss() }
        activeDialog = null
        confirmationOverlay.animate().cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scanListener?.let { qrLastScanRef().removeEventListener(it) }
        failListener?.let { qrFailRef().removeEventListener(it) }
        handler.removeCallbacksAndMessages(null)
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    // --- Scan log adapter ---
    inner class ScanLogAdapter(
        private val entries: List<ScanLogEntry>
    ) : RecyclerView.Adapter<ScanLogAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.scanLogIcon)
            val name: TextView = view.findViewById(R.id.scanLogName)
            val time: TextView = view.findViewById(R.id.scanLogTime)
            val card: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.scanLogCard)
            val reason: TextView = view.findViewById(R.id.scanLogReason)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_scan_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]

            // Alternating card background
            val bgColor = if (position % 2 == 0) {
                resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerLowest)
            } else {
                resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainer)
            }
            holder.card.setCardBackgroundColor(bgColor)

            if (entry.isWarning) {
                holder.icon.setImageResource(R.drawable.baseline_assignment_late_24)
                holder.icon.setColorFilter(resolveThemeColor(android.R.attr.colorError))
                holder.icon.contentDescription = "Varovanie"
                holder.name.text = entry.name
                holder.name.setTextColor(resolveThemeColor(android.R.attr.colorError))

                // Set up expand/collapse
                holder.reason.text = entry.warningReason
                if (entry.expanded) {
                    holder.reason.visibility = View.VISIBLE
                    holder.reason.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    holder.reason.visibility = View.GONE
                }
                holder.card.setOnClickListener {
                    entry.expanded = !entry.expanded
                    if (entry.expanded) {
                        holder.reason.visibility = View.VISIBLE
                        holder.reason.measure(
                            View.MeasureSpec.makeMeasureSpec(holder.card.width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        val targetHeight = holder.reason.measuredHeight
                        holder.reason.layoutParams.height = 0
                        val anim = ValueAnimator.ofInt(0, targetHeight)
                        anim.addUpdateListener { v ->
                            holder.reason.layoutParams.height = v.animatedValue as Int
                            holder.reason.requestLayout()
                        }
                        anim.duration = 200
                        anim.interpolator = DecelerateInterpolator()
                        anim.start()
                    } else {
                        val startHeight = holder.reason.height
                        val anim = ValueAnimator.ofInt(startHeight, 0)
                        anim.addUpdateListener { v ->
                            holder.reason.layoutParams.height = v.animatedValue as Int
                            holder.reason.requestLayout()
                        }
                        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                holder.reason.visibility = View.GONE
                                holder.reason.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                        })
                        anim.duration = 200
                        anim.interpolator = DecelerateInterpolator()
                        anim.start()
                    }
                }
            } else {
                holder.icon.setImageResource(R.drawable.baseline_check_circle_24)
                holder.icon.setColorFilter(resolveThemeColor(androidx.appcompat.R.attr.colorPrimary))
                holder.icon.contentDescription = "Prítomný"
                holder.name.text = entry.name
                holder.name.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                holder.reason.visibility = View.GONE
                holder.card.setOnClickListener(null)
            }
            holder.time.text = formatRelativeTime(entry.timestampMs)
        }

        override fun getItemCount() = entries.size
    }

    private fun formatRelativeTime(timestampMs: Long): String {
        val diffMs = System.currentTimeMillis() - timestampMs
        val seconds = diffMs / 1000
        val minutes = seconds / 60
        return when {
            seconds < 5 -> "Teraz"
            seconds < 60 -> "${seconds}s"
            minutes < 60 -> "${minutes} min"
            else -> "${minutes / 60}h"
        }
    }
}
