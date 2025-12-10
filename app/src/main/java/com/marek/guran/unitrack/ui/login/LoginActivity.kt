package com.marek.guran.unitrack.ui.login

import android.app.Activity
import android.content.Intent
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import com.marek.guran.unitrack.R
import com.marek.guran.unitrack.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.marek.guran.unitrack.MainActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var firebaseAuth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        firebaseAuth = FirebaseAuth.getInstance()
        // Check if user is already logged in
        if (firebaseAuth.currentUser != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val username = binding.usernameLayout?.editText!!
        val password = binding.passwordLayout?.editText!!
        val login = binding.login
        val loading = binding.loading

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