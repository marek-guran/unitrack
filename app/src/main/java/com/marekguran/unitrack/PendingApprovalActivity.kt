package com.marekguran.unitrack

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.marekguran.unitrack.ui.students.StudentsManageFragment

/**
 * Full-screen activity shown to users whose account is pending admin approval.
 * The user cannot navigate away — only log out.
 *
 * A real-time listener on `pending_users/{uid}` detects approval or rejection:
 * - Approved (entry removed, user exists in students/ or teachers/) → go to MainActivity.
 * - Rejected (entry removed, user not in any role) → sign out and redirect to login.
 */
class PendingApprovalActivity : AppCompatActivity() {

    private var pendingRef: DatabaseReference? = null
    private var pendingListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("app_settings", 0)
        val useDarkMode = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (useDarkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending_approval)

        animateEntrance()

        // Block back navigation — user must log out or wait for approval
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* blocked */ }
        })

        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            val intent = Intent(this, com.marekguran.unitrack.ui.login.LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        findViewById<MaterialButton>(R.id.btnReapply).setOnClickListener {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@setOnClickListener
            FirebaseDatabase.getInstance().reference
                .child("pending_users").child(uid).child("status").removeValue()
                .addOnSuccessListener {
                    Toast.makeText(this, getString(R.string.pending_reapply_success), Toast.LENGTH_SHORT).show()
                }
        }

        // Listen for status changes in real time
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseDatabase.getInstance().reference
        pendingRef = db.child("pending_users").child(uid)
        pendingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isFinishing || isDestroyed) return
                if (!snapshot.exists()) {
                    // Pending entry removed — check if approved or rejected
                    db.child("teachers").child(uid).get().addOnSuccessListener { teacherSnap ->
                        if (isFinishing || isDestroyed) return@addOnSuccessListener
                        if (teacherSnap.exists()) {
                            goToMain()
                            return@addOnSuccessListener
                        }
                        db.child("students").child(uid).get().addOnSuccessListener { studentSnap ->
                            if (isFinishing || isDestroyed) return@addOnSuccessListener
                            if (studentSnap.exists()) {
                                goToMain()
                            } else {
                                // Entry removed without role — sign out
                                FirebaseAuth.getInstance().signOut()
                                val intent = Intent(this@PendingApprovalActivity, com.marekguran.unitrack.ui.login.LoginActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                finish()
                            }
                        }
                    }
                } else {
                    val status = snapshot.child("status").getValue(String::class.java)
                    if (status == StudentsManageFragment.STATUS_REJECTED) {
                        showRejectedUI()
                    } else {
                        showPendingUI()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        pendingRef?.addValueEventListener(pendingListener!!)
    }

    private fun showPendingUI() {
        findViewById<TextView>(R.id.pendingIcon).text = "⏳"
        findViewById<TextView>(R.id.subtitleText).text = getString(R.string.pending_approval)
        findViewById<TextView>(R.id.pendingTitle).text = getString(R.string.pending_approval)
        findViewById<TextView>(R.id.pendingMessage).text = getString(R.string.pending_message)
        findViewById<MaterialButton>(R.id.btnReapply).visibility = View.GONE
    }

    private fun showRejectedUI() {
        findViewById<TextView>(R.id.pendingIcon).text = "❌"
        findViewById<TextView>(R.id.subtitleText).text = getString(R.string.pending_rejected_title)
        findViewById<TextView>(R.id.pendingTitle).text = getString(R.string.pending_rejected_title)
        findViewById<TextView>(R.id.pendingMessage).text = getString(R.string.pending_rejected_message)
        findViewById<MaterialButton>(R.id.btnReapply).visibility = View.VISIBLE
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun animateEntrance() {
        val views = listOf(
            findViewById<MaterialCardView>(R.id.logoCard),
            findViewById<TextView>(R.id.titleText),
            findViewById<TextView>(R.id.subtitleText),
            findViewById<TextView>(R.id.pendingIcon),
            findViewById<MaterialCardView>(R.id.messageCard),
            findViewById<MaterialButton>(R.id.btnReapply),
            findViewById<MaterialButton>(R.id.btnLogout)
        )

        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 60f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setStartDelay((index * 80).toLong())
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }

        // Extra bounce animation for logo
        val logoCard = findViewById<MaterialCardView>(R.id.logoCard)
        logoCard.scaleX = 0.8f
        logoCard.scaleY = 0.8f
        logoCard.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setStartDelay(100)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
            .start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (pendingRef != null && pendingListener != null) {
            pendingRef!!.removeEventListener(pendingListener!!)
        }
    }
}
