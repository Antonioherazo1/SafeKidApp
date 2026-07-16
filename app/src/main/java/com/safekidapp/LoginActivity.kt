package com.safekidapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class LoginActivity : AppCompatActivity() {

    private lateinit var syncClient: SyncClient
    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            syncClient = SyncClient(this)
            tokenManager = TokenManager(this)

            if (tokenManager.isLoggedIn()) {
                routeToDashboard()
                return
            }

            setContentView(R.layout.activity_login)

            val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            if (prefs.getString("server_url", null) == null) {
                prefs.edit().putString("server_url", "https://thinc.site/api/safekid").apply()
            }

            val etUsername = findViewById<TextInputEditText>(R.id.etUsername)
            val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
            val btnLogin = findViewById<Button>(R.id.btnLogin)
            val btnGoSignup = findViewById<Button>(R.id.btnGoSignup)
            val tvStatus = findViewById<TextView>(R.id.tvLoginStatus)

            btnLogin.setOnClickListener {
                val username = etUsername.text.toString().trim()
                val password = etPassword.text.toString()

                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                tvStatus.text = "Iniciando sesión..."
                btnLogin.isEnabled = false

                syncClient.login(username, password) { ok, error ->
                    btnLogin.isEnabled = true
                    if (ok) {
                        routeToDashboard()
                    } else {
                        tvStatus.text = error ?: "Error de conexión"
                    }
                }
            }

            btnGoSignup.setOnClickListener {
                startActivity(Intent(this, SignupActivity::class.java))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (tokenManager.isLoggedIn()) {
            routeToDashboard()
        }
    }

    private fun routeToDashboard() {
        val intent = if (tokenManager.isParent()) {
            Intent(this, ParentDashboardActivity::class.java)
        } else {
            Intent(this, ChildDashboardActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
