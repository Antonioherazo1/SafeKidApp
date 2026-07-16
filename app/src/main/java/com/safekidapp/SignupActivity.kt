package com.safekidapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class SignupActivity : AppCompatActivity() {

    private lateinit var syncClient: SyncClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        syncClient = SyncClient(this)

        val etUsername = findViewById<TextInputEditText>(R.id.etSignupUsername)
        val etPassword = findViewById<TextInputEditText>(R.id.etSignupPassword)
        val etConfirm = findViewById<TextInputEditText>(R.id.etSignupConfirm)
        val rgRole = findViewById<RadioGroup>(R.id.rgRole)
        val btnSignup = findViewById<Button>(R.id.btnSignup)
        val tvStatus = findViewById<TextView>(R.id.tvSignupStatus)

        btnSignup.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            val confirm = etConfirm.text.toString()
            val role = if (findViewById<RadioButton>(R.id.rbParent).isChecked) "parent" else "child"

            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != confirm) {
                Toast.makeText(this, "Las contraseñas no coinciden", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password.length < 4) {
                Toast.makeText(this, "La contraseña debe tener al menos 4 caracteres", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvStatus.text = "Registrando..."
            btnSignup.isEnabled = false

            syncClient.register(username, password, role) { ok, error ->
                btnSignup.isEnabled = true
                if (ok) {
                    val intent = if (role == "parent") {
                        Intent(this, ParentDashboardActivity::class.java)
                    } else {
                        Intent(this, ChildDashboardActivity::class.java)
                    }
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    tvStatus.text = error ?: "Error de conexión"
                }
            }
        }
    }
}
