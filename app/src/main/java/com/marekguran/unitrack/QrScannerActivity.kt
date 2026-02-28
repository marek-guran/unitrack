package com.marekguran.unitrack

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class QrScannerActivity : AppCompatActivity() {

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var successOverlay: View
    private lateinit var errorOverlay: View
    private lateinit var errorLabel: TextView

    private val db = FirebaseDatabase.getInstance(
        "https://unitrack-ku-default-rtdb.europe-west1.firebasedatabase.app"
    ).reference

    private val handler = Handler(Looper.getMainLooper())
    private var hasScanned = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startScanning()
        } else {
            Toast.makeText(this, "Na skenovanie QR kódu je potrebná kamera", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        barcodeView = findViewById(R.id.barcodeView)
        successOverlay = findViewById(R.id.successOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        errorLabel = findViewById(R.id.errorLabel)

        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startScanning()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScanning() {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (hasScanned) return
                val text = result?.text ?: return
                handleScanResult(text)
            }
        })
    }

    private fun handleScanResult(scannedText: String) {
        // Parse: UNITRACK|{year}|{semester}|{subjectKey}|{code}
        val parts = scannedText.split("|")
        if (parts.size != 5 || parts[0] != "UNITRACK") {
            showError("Neplatný QR kód")
            return
        }

        val year = parts[1]
        val semester = parts[2]
        val subjectKey = parts[3]
        val scannedCode = parts[4]

        // Validate path components to prevent Firebase path traversal
        if (!isValidFirebasePath(year) || !isValidFirebasePath(semester) ||
            !isValidFirebasePath(subjectKey) || scannedCode.isBlank()) {
            showError("Neplatný QR kód")
            return
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            showError("Nie ste prihlásený")
            return
        }

        hasScanned = true
        val uid = user.uid

        // Step 1: Check if student is enrolled in this subject
        db.child("students").child(uid).child("subjects").child(year).child(semester)
            .get().addOnSuccessListener { enrollSnap ->
                val enrolledSubjects = mutableListOf<String>()
                if (enrollSnap.exists()) {
                    for (child in enrollSnap.children) {
                        val value = child.getValue(String::class.java)
                        if (value != null) enrolledSubjects.add(value)
                    }
                }

                if (subjectKey !in enrolledSubjects) {
                    reportFailedAttempt(year, semester, subjectKey, "Študent nie je zapísaný v predmete")
                    runOnUiThread {
                        showError("Nie ste zapísaný v tomto predmete", resetScanLock = true)
                    }
                    return@addOnSuccessListener
                }

                // Step 2: Fetch subject name for confirmation display
                db.child("school_years").child(year).child("predmety").child(subjectKey)
                    .child("name").get().addOnSuccessListener { nameSnap ->
                        val subjectName = nameSnap.getValue(String::class.java)
                            ?: subjectKey.replaceFirstChar { it.uppercaseChar() }

                        // Step 3: Verify QR code via transaction
                        verifyAndMarkAttendance(year, semester, subjectKey, subjectName, scannedCode)
                    }.addOnFailureListener {
                        runOnUiThread {
                            showError("Chyba pri načítaní predmetu", resetScanLock = true)
                        }
                    }
            }.addOnFailureListener {
                runOnUiThread {
                    showError("Chyba pri overení zápisu", resetScanLock = true)
                }
            }
    }

    private fun verifyAndMarkAttendance(
        year: String, semester: String, subjectKey: String,
        subjectName: String, scannedCode: String
    ) {
        val qrCodeRef = db.child("pritomnost")
            .child(year).child(semester).child(subjectKey).child("qr_code")

        // Use transaction to atomically verify and consume the code
        qrCodeRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(mutableData: MutableData): Transaction.Result {
                val currentCode = mutableData.getValue(String::class.java)
                if (currentCode.isNullOrBlank() || currentCode != scannedCode) {
                    return Transaction.abort()
                }
                // Consume the code (teacher will generate a new one)
                mutableData.value = null
                return Transaction.success(mutableData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                snapshot: DataSnapshot?
            ) {
                if (committed) {
                    markAttendance(year, semester, subjectKey, subjectName)
                } else {
                    runOnUiThread {
                        showError("QR kód expiroval, počkajte na nový", resetScanLock = true)
                    }
                }
            }
        })
    }

    private fun markAttendance(
        year: String, semester: String, subjectKey: String, subjectName: String
    ) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid

        // Notify teacher via qr_last_scan (single rewritten item)
        val scanRef = db.child("pritomnost")
            .child(year).child(semester).child(subjectKey).child("qr_last_scan")

        resolveStudentName(uid) { name ->
            scanRef.setValue(mapOf(
                "uid" to uid,
                "name" to name,
                "time" to ServerValue.TIMESTAMP
            ))
            runOnUiThread { showSuccess(name, subjectName) }
        }
    }

    private fun showSuccess(studentName: String, subjectName: String) {
        barcodeView.pause()
        successOverlay.visibility = View.VISIBLE

        val nameView = findViewById<TextView>(R.id.successStudentName)
        val subjectView = findViewById<TextView>(R.id.successSubjectName)
        val checkmark = findViewById<ImageView>(R.id.successCheckmark)
        val label = findViewById<TextView>(R.id.successLabel)
        val closeBtn = findViewById<MaterialButton>(R.id.successCloseBtn)

        nameView.text = studentName
        subjectView.text = subjectName

        // Reset animation state
        nameView.alpha = 0f
        subjectView.alpha = 0f
        checkmark.scaleX = 0f
        checkmark.scaleY = 0f
        label.alpha = 0f
        closeBtn.alpha = 0f

        // Fade in overlay
        successOverlay.animate().alpha(1f).setDuration(200).withEndAction {
            // Animate student name
            nameView.animate().alpha(1f).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()

            // Animate subject name
            subjectView.animate().alpha(1f).setDuration(200).setStartDelay(50)
                .setInterpolator(DecelerateInterpolator()).start()

            // Bounce checkmark
            val scaleX = ObjectAnimator.ofFloat(checkmark, "scaleX", 0f, 1f)
            val scaleY = ObjectAnimator.ofFloat(checkmark, "scaleY", 0f, 1f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 400
                interpolator = OvershootInterpolator(2f)
                startDelay = 100
                start()
            }

            // Fade label
            label.animate().alpha(1f).setDuration(300).setStartDelay(250)
                .setInterpolator(DecelerateInterpolator()).start()

            // Fade close button
            closeBtn.animate().alpha(1f).setDuration(300).setStartDelay(400)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()

        // Close button instead of auto-close
        closeBtn.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun showError(message: String, resetScanLock: Boolean = false) {
        errorLabel.text = message
        errorOverlay.visibility = View.VISIBLE
        errorOverlay.animate().alpha(1f).setDuration(200).start()

        handler.postDelayed({
            errorOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                errorOverlay.visibility = View.GONE
                if (resetScanLock) {
                    hasScanned = false
                }
            }.start()
        }, 2000)
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    /** Validate that a string is safe to use as a Firebase path segment. */
    private fun isValidFirebasePath(segment: String): Boolean {
        if (segment.isBlank() || segment.length > 200) return false
        // Firebase disallows: . $ # [ ] / and control characters
        val forbidden = charArrayOf('.', '$', '#', '[', ']', '/')
        return segment.none { it in forbidden || it.code < 32 }
    }

    private fun reportFailedAttempt(year: String, semester: String, subjectKey: String, reason: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid
        val failRef = db.child("pritomnost")
            .child(year).child(semester).child(subjectKey).child("qr_fail")

        resolveStudentName(uid) { name ->
            failRef.setValue(mapOf(
                "uid" to uid,
                "name" to name,
                "reason" to reason,
                "time" to ServerValue.TIMESTAMP
            ))
        }
    }

    /** Look up real name from students/{uid}/name, fall back to displayName. */
    private fun resolveStudentName(uid: String, callback: (String) -> Unit) {
        val fallback = FirebaseAuth.getInstance().currentUser?.displayName ?: "Študent"
        db.child("students").child(uid).child("name").get()
            .addOnSuccessListener { snap ->
                callback(snap.getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: fallback)
            }
            .addOnFailureListener {
                callback(fallback)
            }
    }
}
