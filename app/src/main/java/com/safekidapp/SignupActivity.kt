package com.safekidapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

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
        val etParentCode = findViewById<TextInputEditText>(R.id.etParentCode)
        val tilParentCode = findViewById<TextInputLayout>(R.id.tilParentCode)
        val btnSignup = findViewById<Button>(R.id.btnSignup)
        val tvStatus = findViewById<TextView>(R.id.tvSignupStatus)

        rgRole.setOnCheckedChangeListener { _, checkedId ->
            val isChild = checkedId == R.id.rbChild
            tilParentCode.visibility = if (isChild) View.VISIBLE else View.GONE
            etParentCode.text?.clear()
        }

        btnSignup.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            val confirm = etConfirm.text.toString()
            val isChild = findViewById<RadioButton>(R.id.rbChild).isChecked
            val role = if (isChild) "child" else "parent"
            val parentCode = etParentCode.text.toString().trim()

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

            syncClient.register(username, password, role, parentCode) { ok, error ->
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
