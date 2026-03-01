package com.marekguran.unitrack.ui.login

import android.content.Intent
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.marekguran.unitrack.R
import com.marekguran.unitrack.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.marekguran.unitrack.MainActivity
import com.marekguran.unitrack.data.OfflineMode
import com.marekguran.unitrack.data.model.AppConstants

class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DB_UNAVAILABLE = "db_unavailable"
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var firebaseAuth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dbUnavailable = intent.getBooleanExtra(EXTRA_DB_UNAVAILABLE, false)

        // Check if already in offline mode
        if (!dbUnavailable && OfflineMode.isOffline(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        firebaseAuth = FirebaseAuth.getInstance()
        // Check if user is already logged in (skip if showing unavailable screen)
        if (!dbUnavailable && firebaseAuth.currentUser != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (dbUnavailable) {
            showDatabaseUnavailableMode()
            return
        }

        // Animate entrance of login elements
        animateEntrance()

        val username = binding.usernameLayout.editText!!
        val password = binding.passwordLayout.editText!!
        val login = binding.login
        val loading = binding.loading

        // Offline mode button
        binding.btnOfflineMode.setOnClickListener {
            OfflineMode.setOffline(this, true)
            Toast.makeText(this, getString(R.string.offline_mode_activated), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // Enable the login button only if both fields are non-empty
        val textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                login.isEnabled = username.text.isNotEmpty() && password.text.isNotEmpty()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        username.addTextChangedListener(textWatcher)
        password.addTextChangedListener(textWatcher)

        // Handle keyboard "Done" action
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (login.isEnabled) {
                    loading.visibility = View.VISIBLE
                    performLogin(username.text.toString(), password.text.toString())
                }
                true
            } else {
                false
            }
        }

        // Handle login button click
        login.setOnClickListener {
            loading.visibility = View.VISIBLE
            performLogin(username.text.toString(), password.text.toString())
        }

        // Forgot password button
        binding.btnForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }

        // Create account button
        binding.btnCreateAccount.setOnClickListener {
            showCreateAccountDialog()
        }
    }

    private fun performLogin(email: String, password: String) {
        if (email.isEmpty() || password.isEmpty()) {
            showLoginFailed(R.string.invalid_username)
            binding.loading.visibility = View.GONE
            return
        }

        firebaseLogin(email, password)
    }

    private fun firebaseLogin(email: String, password: String) {
        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                binding.loading.visibility = View.GONE
                if (task.isSuccessful) {
                    val uid = firebaseAuth.currentUser?.uid
                    if (uid == null) {
                        showLoginFailed(R.string.login_failed)
                        return@addOnCompleteListener
                    }
                    // Verify user has an active role or is pending
                    val db = FirebaseDatabase.getInstance().reference
                    db.child("pending_users").child(uid).get().addOnSuccessListener { pendingSnap ->
                        if (pendingSnap.exists()) { proceedToMain(); return@addOnSuccessListener }
                        db.child("teachers").child(uid).get().addOnSuccessListener { teacherSnap ->
                            if (teacherSnap.exists()) { proceedToMain(); return@addOnSuccessListener }
                            db.child("students").child(uid).get().addOnSuccessListener { studentSnap ->
                                if (studentSnap.exists()) { proceedToMain(); return@addOnSuccessListener }
                                db.child("admins").child(uid).get().addOnSuccessListener { adminSnap ->
                                    if (adminSnap.exists()) { proceedToMain(); return@addOnSuccessListener }
                                    // No role found — account was rejected, block access
                                    firebaseAuth.signOut()
                                    Toast.makeText(this, getString(R.string.pending_rejected_user), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }.addOnFailureListener {
                        // If check fails, allow login anyway
                        proceedToMain()
                    }
                } else {
                    showLoginFailed(R.string.login_failed)
                }
            }
    }

    private fun proceedToMain() {
        updateUiWithUser(firebaseAuth.currentUser?.email)
        startActivity(Intent(this, MainActivity::class.java))
        setResult(RESULT_OK)
        finish()
    }

    private fun updateUiWithUser(displayName: String?) {
        val welcome = getString(R.string.welcome)
        Toast.makeText(
            applicationContext,
            "$welcome $displayName",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showLoginFailed(@StringRes errorString: Int) {
        Toast.makeText(applicationContext, errorString, Toast.LENGTH_SHORT).show()
    }

    private fun showDatabaseUnavailableMode() {
        // Hide login fields and offline mode button
        binding.usernameLayout.visibility = View.GONE
        binding.passwordLayout.visibility = View.GONE
        binding.login.visibility = View.GONE
        binding.loading.visibility = View.GONE
        binding.dividerText.visibility = View.GONE
        binding.btnOfflineMode.visibility = View.GONE
        binding.authActionsRow.visibility = View.GONE

        // Update subtitle
        binding.subtitleText.text = "Aplikácia dočasne nedostupná"

        // Show unavailability message and logout button
        binding.unavailableMessage.visibility = View.VISIBLE
        binding.unavailableMessage.text =
            "Databáza vyžaduje aktualizáciu. Počkajte, kým administrátor vykoná migráciu v nastaveniach."
        binding.btnLogoutUnavailable.visibility = View.VISIBLE
        binding.btnLogoutUnavailable.setOnClickListener {
            firebaseAuth.signOut()
            // Restart LoginActivity in normal login mode
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        // Animate entrance with unavailable-specific views
        val views = listOf(
            binding.logoCard,
            binding.titleText,
            binding.subtitleText,
            binding.unavailableMessage,
            binding.btnLogoutUnavailable
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
    }

    private fun animateEntrance() {
        val views = listOf(
            binding.logoCard,
            binding.titleText,
            binding.subtitleText,
            binding.usernameLayout,
            binding.passwordLayout,
            binding.login,
            binding.authActionsRow,
            binding.dividerText,
            binding.btnOfflineMode
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
        binding.logoCard.scaleX = 0.8f
        binding.logoCard.scaleY = 0.8f
        binding.logoCard.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setStartDelay(100)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
            .start()
    }

    // ── Forgot Password Dialog ────────────────────────────────────────────────

    private fun showForgotPasswordDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.reset_password_title)
        val input = dialogView.findViewById<TextInputEditText>(R.id.dialogInput)
        input.hint = getString(R.string.reset_password_hint)
        input.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

        val dialog = android.app.Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialogView.findViewById<MaterialButton>(R.id.confirmButton).apply {
            text = "Odoslať"
            setOnClickListener {
                val email = input.text?.toString()?.trim() ?: ""
                if (email.isBlank()) return@setOnClickListener
                firebaseAuth.sendPasswordResetEmail(email)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Toast.makeText(this@LoginActivity, getString(R.string.reset_password_sent), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@LoginActivity, getString(R.string.reset_password_error, task.exception?.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                        dialog.dismiss()
                    }
            }
        }
        dialogView.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ── Create Account Dialog ─────────────────────────────────────────────────

    private fun showCreateAccountDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_register, null)

        val editEmail = dialogView.findViewById<TextInputEditText>(R.id.editRegisterEmail)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.editRegisterName)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnConfirmRegister)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelRegister)

        val dialog = android.app.Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        btnSave.setOnClickListener {
            val email = editEmail.text?.toString()?.trim() ?: ""
            val name = editName.text?.toString()?.trim() ?: ""
            if (email.isBlank() || name.isBlank()) return@setOnClickListener
            // Check allowed domains
            checkAllowedDomainsAndRegister(email, name, dialog)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun checkAllowedDomainsAndRegister(email: String, name: String, dialog: android.app.Dialog) {
        val db = FirebaseDatabase.getInstance().reference
        db.child("settings").child("allowed_domains").get().addOnSuccessListener { snapshot ->
            val domains = snapshot.getValue(String::class.java)
            val allowedDomains = if (!domains.isNullOrBlank()) {
                domains.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
            } else {
                listOf(AppConstants.DEFAULT_ALLOWED_DOMAIN)
            }
            val emailDomain = email.substringAfter("@").lowercase()
            if (emailDomain !in allowedDomains) {
                Toast.makeText(this, getString(R.string.register_invalid_domain, allowedDomains.joinToString(", ")), Toast.LENGTH_LONG).show()
                return@addOnSuccessListener
            }
            registerNewStudent(email, name, dialog)
        }.addOnFailureListener {
            val emailDomain = email.substringAfter("@").lowercase()
            if (emailDomain != AppConstants.DEFAULT_ALLOWED_DOMAIN) {
                Toast.makeText(this, getString(R.string.register_invalid_domain, AppConstants.DEFAULT_ALLOWED_DOMAIN), Toast.LENGTH_LONG).show()
                return@addOnFailureListener
            }
            registerNewStudent(email, name, dialog)
        }
    }

    private fun registerNewStudent(email: String, name: String, dialog: android.app.Dialog) {
        binding.loading.visibility = View.VISIBLE
        val tempPassword = java.util.UUID.randomUUID().toString()
        firebaseAuth.createUserWithEmailAndPassword(email, tempPassword)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val userId = firebaseAuth.currentUser?.uid ?: return@addOnCompleteListener
                    val db = FirebaseDatabase.getInstance().reference
                    val pendingObj = mapOf(
                        "email" to email,
                        "name" to name,
                        "tempKey" to tempPassword
                    )
                    db.child("pending_users").child(userId).setValue(pendingObj)
                        .addOnCompleteListener {
                            // Send password setup email so the user can set their own password and log in
                            firebaseAuth.sendPasswordResetEmail(email)
                            firebaseAuth.signOut()
                            binding.loading.visibility = View.GONE
                            dialog.dismiss()
                            Toast.makeText(this, getString(R.string.register_success), Toast.LENGTH_LONG).show()
                        }
                } else {
                    binding.loading.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.register_error, task.exception?.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
    }
}

// Extension function stays the same
fun EditText.afterTextChanged(afterTextChanged: (String) -> Unit) {
    this.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(editable: Editable?) {
            afterTextChanged.invoke(editable.toString())
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}