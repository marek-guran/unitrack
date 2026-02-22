package com.marekguran.unitrack.ui.login

import android.app.Activity
import android.content.Intent
import android.view.animation.DecelerateInterpolator
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import com.marekguran.unitrack.R
import com.marekguran.unitrack.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.marekguran.unitrack.MainActivity
import com.marekguran.unitrack.data.OfflineMode

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var firebaseAuth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if already in offline mode
        if (OfflineMode.isOffline(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        firebaseAuth = FirebaseAuth.getInstance()
        // Check if user is already logged in
        if (firebaseAuth.currentUser != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Animate entrance of login elements
        animateEntrance()

        val username = binding.usernameLayout?.editText!!
        val password = binding.passwordLayout?.editText!!
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
    }

    private fun performLogin(email: String, password: String) {
        if (email.isEmpty() || password.isEmpty()) {
            showLoginFailed(R.string.invalid_username)
            binding.loading.visibility = View.GONE
            return
        }

        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                binding.loading.visibility = View.GONE
                if (task.isSuccessful) {
                    updateUiWithUser(firebaseAuth.currentUser?.email)
                    startActivity(Intent(this, MainActivity::class.java))
                    setResult(RESULT_OK)
                    finish()
                } else {
                    showLoginFailed(R.string.login_failed)
                }
            }
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

    private fun animateEntrance() {
        val views = listOf(
            binding.logoCard,
            binding.titleText,
            binding.subtitleText,
            binding.usernameLayout,
            binding.passwordLayout,
            binding.login,
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