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

class ChildDashboardActivity : AppCompatActivity() {

    private lateinit var tracker: UsageTracker
    private lateinit var syncClient: SyncClient
    private lateinit var tokenManager: TokenManager
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var hasError = false
    private var commandPollRunnable: Runnable? = null

    private val tvUser: TextView get() = findViewById(R.id.tvChildUser)
    private val tvLimit: TextView get() = findViewById(R.id.tvChildLimit)
    private val tvStatus: TextView get() = findViewById(R.id.tvChildStatus)
    private val tvRemaining: TextView get() = findViewById(R.id.tvChildRemaining)
    private val tvUsed: TextView get() = findViewById(R.id.tvChildUsed)
    private val tvCloud: TextView get() = findViewById(R.id.tvChildCloud)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_dashboard)

        try {
            tracker = UsageTracker(this)
            syncClient = SyncClient(this)
            tokenManager = TokenManager(this)

            tvUser.text = "Hola, ${tokenManager.getUsername() ?: "Hijo"}"

            findViewById<Button>(R.id.btnChildAdminAccess).setOnClickListener {
                showPasswordDialog()
            }

            findViewById<Button>(R.id.btnChildLogout).setOnClickListener {
                tokenManager.logout()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        } catch (e: Exception) {
            hasError = true
            tvStatus.text = "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (!tokenManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        if (!hasError) {
            updateDisplay()
            startAutoUpdate()
            startCommandPolling()
            syncCloudData()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAutoUpdate()
        stopCommandPolling()
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

    private fun startCommandPolling() {
        stopCommandPolling()
        if (!tokenManager.isChild()) return
        commandPollRunnable = Runnable {
            checkPendingCommands()
            handler.postDelayed(commandPollRunnable!!, 15000)
        }
        handler.postDelayed(commandPollRunnable!!, 5000)
    }

    private fun stopCommandPolling() {
        commandPollRunnable?.let { handler.removeCallbacks(it) }
        commandPollRunnable = null
    }

    private fun checkPendingCommands() {
        syncClient.getPendingCommands { commands, _ ->
            if (commands != null && commands.isNotEmpty()) {
                for (cmd in commands) {
                    syncClient.markCommandDelivered(cmd.id) { _, _ -> }
                    when (cmd.commandType) {
                        "block" -> executeBlock()
                        "unblock" -> executeUnblock()
                        "start_tracking" -> executeStartTracking()
                        "stop_tracking" -> executeStopTracking()
                    }
                    syncClient.markCommandExecuted(cmd.id) { _, _ -> }
                }
            }
        }
    }

    private fun executeBlock() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("kiosk_active", true).apply()
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun executeUnblock() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("kiosk_active", false)
            .putBoolean("time_exceeded", false)
            .putBoolean("tracking_enabled", false)
            .apply()
        try { stopService(Intent(this, UsageService::class.java)) } catch (_: Exception) {}
        Toast.makeText(this, "Dispositivo desbloqueado por el padre", Toast.LENGTH_LONG).show()
    }

    private fun executeStartTracking() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tracking_enabled", true).apply()
        startService(Intent(this, UsageService::class.java))
        Toast.makeText(this, "Control de tiempo activado por el padre", Toast.LENGTH_LONG).show()
    }

    private fun executeStopTracking() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tracking_enabled", false).apply()
        try { stopService(Intent(this, UsageService::class.java)) } catch (_: Exception) {}
        Toast.makeText(this, "Control de tiempo desactivado por el padre", Toast.LENGTH_LONG).show()
    }

    private fun updateDisplay() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val blocked = prefs.getBoolean("kiosk_active", false)
        val tracking = tracker.isTrackingEnabled()
        val limit = tracker.getDailyLimit()
        val used = tracker.getAccumulatedUsage()
        val currentSession = if (tracker.getScreenOnTimestamp() > 0)
            System.currentTimeMillis() - tracker.getScreenOnTimestamp() else 0
        val total = used + if (currentSession > 0) currentSession else 0

        tvUsed.text = "Usado: ${formatTime(used)}"

        if (blocked) {
            tvStatus.text = "BLOQUEADO"
            tvRemaining.text = "BLOQUEADO"
            tvRemaining.setTextColor(0xFFFF5252.toInt())
            return
        }

        if (!tracking) {
            tvStatus.text = "Control desactivado"
            tvRemaining.text = "—"
            tvRemaining.setTextColor(0xFFB0B0B0.toInt())
            return
        }

        if (limit <= 0) {
            tvStatus.text = "Sin límite"
            tvRemaining.text = "∞"
            tvRemaining.setTextColor(0xFF4CAF50.toInt())
            return
        }

        if (total >= limit) {
            tvStatus.text = "Límite alcanzado"
            tvRemaining.text = "AGOTADO"
            tvRemaining.setTextColor(0xFFFF5252.toInt())
            return
        }

        val remaining = limit - total
        val hours = remaining / 3600000
        val minutes = (remaining % 3600000) / 60000
        val seconds = (remaining % 60000) / 1000

        tvStatus.text = if (hours > 0) "${hours}h ${minutes}m restantes" else "${minutes}m ${seconds}s restantes"
        if (hours > 0) {
            tvRemaining.text = String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            tvRemaining.text = String.format("%02d:%02d", minutes, seconds)
        }
        tvRemaining.setTextColor(0xFFFFFFFF.toInt())

        val limitMin = limit / 60000
        tvLimit.text = "Límite: $limitMin min"
    }

    private fun syncCloudData() {
        if (!syncClient.isConfigured()) {
            tvCloud.text = "◎ No configurado"
            tvCloud.setTextColor(0xFF999999.toInt())
            return
        }

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
