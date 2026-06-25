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
    private lateinit var prefs: android.content.SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)

        if (!prefs.contains("password_hash")) {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        tracker = UsageTracker(this)

        findViewById<TextView>(R.id.tvDeviceId).text = "ID: ${loadDeviceId()}"

        findViewById<Button>(R.id.btnAdminAccess).setOnClickListener {
            showPasswordDialog()
        }
    }

    private fun loadDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().take(8)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
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
            handler.postDelayed(updateRunnable!!, 2000)
        }
        handler.postDelayed(updateRunnable!!, 500)
    }

    private fun stopAutoUpdate() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }

    private fun updateDisplay() {
        val tvTime = findViewById<TextView>(R.id.tvHomeRemainingTime)
        val tvStatus = findViewById<TextView>(R.id.tvHomeStatus)
        val tvTracking = findViewById<TextView>(R.id.tvTrackingStatus)
        val tvTimeLimit = findViewById<TextView>(R.id.tvTimeLimit)
        val tvTimeUsed = findViewById<TextView>(R.id.tvTimeUsed)
        val tvBlock = findViewById<TextView>(R.id.tvBlockStatus)
        val tvMqtt = findViewById<TextView>(R.id.tvMqttStatus)

        val connected = prefs.getBoolean("mqtt_connected", false)
        tvMqtt.text = if (connected) "MQTT: Conectado ✓" else "MQTT: Desconectado ✗"
        tvMqtt.setTextColor(if (connected) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt())

        val tracking = tracker.isTrackingEnabled()
        val blocked = prefs.getBoolean("kiosk_active", false)
        val limit = tracker.getDailyLimit()
        val used = tracker.getAccumulatedUsage()
        val currentSession = if (tracker.getScreenOnTimestamp() > 0) System.currentTimeMillis() - tracker.getScreenOnTimestamp() else 0
        val total = used + if (currentSession in 1..3600000) currentSession else 0

        tvTracking.text = if (tracking) "Activo" else "Desactivado"
        tvTracking.setTextColor(if (tracking) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt())

        tvTimeLimit.text = if (limit > 0) "${limit / 60000} minutos" else "Sin límite"

        tvTimeUsed.text = formatTime(used)

        if (blocked) {
            tvTime.text = "BLOQUEADO"
            tvTime.setTextColor(0xFFFF5252.toInt())
            tvStatus.text = "El dispositivo está bloqueado"
            tvBlock.text = "BLOQUEADO"
            tvBlock.setTextColor(0xFFFF5252.toInt())
            return
        }
        tvBlock.text = "Desbloqueado"
        tvBlock.setTextColor(0xFF4CAF50.toInt())

        if (!tracking) {
            tvTime.text = "Inactivo"
            tvTime.setTextColor(0xFFB0B0B0.toInt())
            tvStatus.text = "Control de tiempo desactivado"
            return
        }

        if (limit <= 0) {
            tvTime.text = "Sin límite"
            tvTime.setTextColor(0xFF4CAF50.toInt())
            tvStatus.text = "Sin restricción de tiempo"
            return
        }

        if (total >= limit) {
            tvTime.text = "Agotado"
            tvTime.setTextColor(0xFFFF5252.toInt())
            tvStatus.text = "Límite diario alcanzado"
            return
        }

        val remaining = limit - total
        val hours = remaining / 3600000
        val minutes = (remaining % 3600000) / 60000
        val seconds = (remaining % 60000) / 1000

        tvTime.setTextColor(0xFFFFFFFF.toInt())
        if (hours > 0) {
            tvTime.text = String.format("%d:%02d:%02d", hours, minutes, seconds)
            tvStatus.text = "${hours}h restantes"
        } else {
            tvTime.text = String.format("%02d:%02d", minutes, seconds)
            tvStatus.text = "${minutes}m ${seconds}s restantes"
        }
    }

    private fun formatTime(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        return if (h > 0) "${h}h ${m}m" else "${m} minutos"
    }

    private fun showPasswordDialog() {
        val inputLayout = TextInputLayout(this)
        val input = TextInputEditText(this)
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
