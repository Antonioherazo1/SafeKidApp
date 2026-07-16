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
    private var hasError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        try {
            tracker = UsageTracker(this)

            val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            if (!prefs.contains("password_hash")) {
                startActivity(Intent(this, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                return
            }

            findViewById<Button>(R.id.btnAdminAccess).setOnClickListener {
                showPasswordDialog()
            }
        } catch (e: Exception) {
            hasError = true
            findViewById<TextView>(R.id.tvHomeStatus).text =
                "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasError) {
            updateDisplay()
            startAutoUpdate()
            syncCloudData()
        }
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
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val tvTime = findViewById<TextView>(R.id.tvHomeRemainingTime)
        val tvStatus = findViewById<TextView>(R.id.tvHomeStatus)
        val tvUsed = findViewById<TextView>(R.id.tvTimeUsed)
        val tvBlock = findViewById<TextView>(R.id.tvBlockStatus)

        val tracking = tracker.isTrackingEnabled()
        val blocked = prefs.getBoolean("kiosk_active", false)
        val limit = tracker.getDailyLimit()
        val used = tracker.getAccumulatedUsage()
        val currentSession = if (tracker.getScreenOnTimestamp() > 0)
            System.currentTimeMillis() - tracker.getScreenOnTimestamp() else 0
        val total = used + if (currentSession > 0) currentSession else 0

        tvUsed.text = formatTime(used)

        if (blocked) {
            tvTime.text = "BLOQUEADO"
            tvTime.setTextColor(0xFFFF5252.toInt())
            tvStatus.text = "Dispositivo bloqueado"
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
            tvStatus.text = "Sin restricción"
            return
        }

        if (total >= limit) {
            tvTime.text = "Agotado"
            tvTime.setTextColor(0xFFFF5252.toInt())
            tvStatus.text = "Límite alcanzado"
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

    private fun syncCloudData() {
        val syncClient = SyncClient(this)
        val tvCloud = findViewById<TextView>(R.id.tvCloudInfo)

        if (!syncClient.isConfigured()) {
            tvCloud.text = "◎ No configurado"
            tvCloud.setTextColor(0xFF999999.toInt())
            return
        }

        val tracker = UsageTracker(this)
        val usedSeconds = (tracker.getAccumulatedUsage() / 1000).toInt()

        tvCloud.text = "◌ Sincronizando..."
        tvCloud.setTextColor(0xFFFFA500.toInt())

        syncClient.syncToday(usedSeconds) { syncOk, _ ->
            if (syncOk) {
                syncClient.fetchStats(7) { stats, _ ->
                    if (stats != null) {
                        val usedMin = stats.todaySeconds / 60
                        val limitMin = stats.limitMinutes
                        val text = if (limitMin > 0) {
                            "$usedMin min usado de $limitMin min • ${stats.history.size} días"
                        } else {
                            "$usedMin min usado • ${stats.history.size} días"
                        }
                        tvCloud.text = "● $text"
                        tvCloud.setTextColor(0xFF4CAF50.toInt())
                    } else {
                        tvCloud.text = "● Sincronizado"
                        tvCloud.setTextColor(0xFF4CAF50.toInt())
                    }
                }
            } else {
                tvCloud.text = "✕ Error de conexión"
                tvCloud.setTextColor(0xFFFF5252.toInt())
            }
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
        return storedHash == hashPassword(password)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
