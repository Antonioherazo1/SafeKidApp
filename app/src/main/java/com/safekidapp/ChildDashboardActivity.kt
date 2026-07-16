package com.safekidapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

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
    private val tvSchedule: TextView get() = findViewById(R.id.tvChildSchedule)
    private val btnLogout: Button get() = findViewById(R.id.btnChildLogout)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_dashboard)

        try {
            tracker = UsageTracker(this)
            syncClient = SyncClient(this)
            tokenManager = TokenManager(this)

            val parentName = tokenManager.getParentUsername()
            tvUser.text = if (parentName != null) {
                "Hola, ${tokenManager.getUsername() ?: "Hijo"} • Padre: $parentName"
            } else {
                "Hola, ${tokenManager.getUsername() ?: "Hijo"}"
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
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("kiosk_active", false)) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        if (!hasError) {
            updateDisplay()
            startAutoUpdate()
            startCommandPolling()
            startCloudSync()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAutoUpdate()
        stopCommandPolling()
        stopCloudSync()
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
        prefs.edit()
            .putBoolean("kiosk_active", true)
            .putString("block_reason", null)
            .apply()
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
            .putLong("daily_usage_ms", 0)
            .apply()
        try { stopService(Intent(this, UsageService::class.java)) } catch (_: Exception) {}
        try { stopLockTask() } catch (_: Exception) {}
        Toast.makeText(this, "Dispositivo desbloqueado por el padre", Toast.LENGTH_LONG).show()
    }

    private fun executeStartTracking() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("tracking_enabled", true)
            .putLong("daily_usage_ms", 0)
            .putBoolean("time_exceeded", false)
            .apply()

        // 1. Immediate check from prefs (may be stale)
        if (outsideScheduleFromPrefs()) {
            blockNow("schedule")
            return
        }

        // 2. Always start the service immediately (do NOT wait for sync callback)
        startService(Intent(this, UsageService::class.java))
        Toast.makeText(this, "Control de tiempo activado por el padre", Toast.LENGTH_LONG).show()

        // 3. Background sync + re-check (async)
        if (syncClient.isConfigured()) {
            val usedSeconds = (tracker.getAccumulatedUsage() / 1000).toInt()
            syncClient.syncToday(usedSeconds) { ok, _ ->
                if (ok && outsideScheduleFromPrefs()) {
                    blockNow("schedule")
                }
            }
        }
    }

    private fun outsideScheduleFromPrefs(): Boolean {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val sStart = prefs.getInt("schedule_start_min", -1)
        val sEnd = prefs.getInt("schedule_end_min", -1)
        if (sStart < 0 || sEnd < 0) return false
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val within = if (sEnd > sStart) now in sStart until sEnd else now >= sStart || now < sEnd
        return !within
    }

    private fun blockNow(reason: String) {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val sStart = prefs.getInt("schedule_start_min", 0)
        val sEnd = prefs.getInt("schedule_end_min", 0)
        prefs.edit()
            .putBoolean("kiosk_active", true)
            .putString("block_reason", reason)
            .putString("block_schedule_start", String.format("%02d:%02d", sStart / 60, sStart % 60))
            .putString("block_schedule_end", String.format("%02d:%02d", sEnd / 60, sEnd % 60))
            .apply()
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
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
        btnLogout.isEnabled = !tracking
        btnLogout.alpha = if (tracking) 0.4f else 1.0f

        val sStart = prefs.getInt("schedule_start_min", -1)
        val sEnd = prefs.getInt("schedule_end_min", -1)
        tvSchedule.text = if (sStart >= 0 && sEnd >= 0) {
            val h1 = sStart / 60; val m1 = sStart % 60
            val h2 = sEnd / 60; val m2 = sEnd % 60
            "Horario: %02d:%02d – %02d:%02d".format(h1, m1, h2, m2)
        } else {
            "Sin horario configurado"
        }
        val limit = tracker.getDailyLimit()
        val used = tracker.getAccumulatedUsage()
        val currentSession = if (tracker.getScreenOnTimestamp() > 0)
            System.currentTimeMillis() - tracker.getScreenOnTimestamp() else 0
        val total = used + if (currentSession > 0) currentSession else 0

        tvUsed.text = if (tracking) "Usado: ${formatTime(used)}" else "Usado: 0 min"

        tvLimit.text = if (limit > 0) "Límite: ${limit / 60000} min" else "Límite: —"

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
    }

    private var cloudSyncRunnable: Runnable? = null

    private fun startCloudSync() {
        stopCloudSync()
        cloudSyncRunnable = Runnable {
            syncCloudData()
            handler.postDelayed(cloudSyncRunnable!!, 30000)
        }
        handler.postDelayed(cloudSyncRunnable!!, 3000)
    }

    private fun stopCloudSync() {
        cloudSyncRunnable?.let { handler.removeCallbacks(it) }
        cloudSyncRunnable = null
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
            val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            val sStart = prefs.getInt("schedule_start_min", -1)
            val sEnd = prefs.getInt("schedule_end_min", -1)
            val sStr = if (sStart >= 0) "H:%02d:%02d-%02d:%02d".format(sStart/60,sStart%60,sEnd/60,sEnd%60) else "Sin horario"
            val errStr = if (sStart >= 0) sStr else "Error conexión"
            tvCloud.text = if (syncOk) "● $sStr" else "✕ $errStr"
            tvCloud.setTextColor(if (syncOk) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt())
            if (syncOk) {
                syncClient.fetchStats(7) { stats, _ ->
                    if (stats != null) {
                        val usedMin = stats.todaySeconds / 60
                        val limitMin = stats.limitMinutes
                        val extra = if (limitMin > 0) "$usedMin min de $limitMin min" else "$usedMin min usado"
                        tvCloud.text = "● $sStr | $extra"
                    }
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        return if (h > 0) "${h}h ${m}m" else "${m} minutos"
    }
}
