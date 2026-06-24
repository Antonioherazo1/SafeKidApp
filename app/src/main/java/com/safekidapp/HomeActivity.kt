package com.safekidapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.security.MessageDigest

class HomeActivity : AppCompatActivity() {

    private lateinit var tracker: UsageTracker
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        tracker = UsageTracker(this)

        findViewById<Button>(R.id.btnAdminAccess).setOnClickListener {
            showPasswordDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDisplay()
        startAutoUpdate()
    }

    override fun onPause() {
        super.onPause()
        stopAutoUpdate()
    }

    private fun startAutoUpdate() {
        stopAutoUpdate()
        updateRunnable = Runnable {
            updateDisplay()
            handler.postDelayed(updateRunnable!!, 10000)
        }
        handler.postDelayed(updateRunnable!!, 10000)
    }

    private fun stopAutoUpdate() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }

    private fun updateDisplay() {
        val tvTime = findViewById<TextView>(R.id.tvHomeRemainingTime)
        val tvStatus = findViewById<TextView>(R.id.tvHomeStatus)

        if (!tracker.isTrackingEnabled()) {
            tvTime.text = "Inactivo"
            tvStatus.text = "Control de tiempo desactivado"
            return
        }

        val limit = tracker.getDailyLimit()
        if (limit <= 0) {
            tvTime.text = "Sin límite"
            tvStatus.text = "Tiempo de uso sin restricción"
            return
        }

        val used = tracker.getAccumulatedUsage()
        val currentSession = System.currentTimeMillis() - tracker.getScreenOnTimestamp()
        val sessionMs = if (tracker.getScreenOnTimestamp() > 0) currentSession else 0
        val total = used + if (sessionMs > 0 && sessionMs < 60000 * 60) sessionMs else 0

        if (total >= limit) {
            tvTime.text = "Agotado"
            tvStatus.text = "Límite diario alcanzado"
            return
        }

        val remainingMin = (limit - total) / 60000

        when {
            remainingMin < 1L -> { tvTime.text = "< 1 min"; tvStatus.text = "Tiempo por agotarse" }
            remainingMin == 1L -> { tvTime.text = "1 min"; tvStatus.text = "1 minuto restante" }
            remainingMin < 60L -> { tvTime.text = "$remainingMin min"; tvStatus.text = "$remainingMin minutos restantes" }
            else -> {
                val h = remainingMin / 60
                val m = remainingMin % 60
                tvTime.text = "${h}h ${m}m"
                tvStatus.text = "${h}h ${m}m restantes"
            }
        }
    }

    private fun showPasswordDialog() {
        val inputLayout = TextInputLayout(this)
        val input = TextInputEditText(inputLayout)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        inputLayout.addView(input)
        inputLayout.setPadding(48, 16, 48, 16)

        AlertDialog.Builder(this)
            .setTitle("Acceso administrador")
            .setMessage("Ingresa la contraseña de adulto")
            .setView(inputLayout)
            .setPositiveButton("Ingresar") { _, _ ->
                val password = input.text?.toString() ?: ""
                if (checkPassword(password)) {
                    openSettings()
                } else {
                    Toast.makeText(this, "Contraseña incorrecta", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checkPassword(password: String): Boolean {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val storedHash = prefs.getString("password_hash", null) ?: return false
        val inputHash = hashPassword(password)
        return storedHash == inputHash
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
