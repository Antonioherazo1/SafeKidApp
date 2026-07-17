package com.safekidapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.security.MessageDigest
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private var tapCount = 0
    private var tapTimer: Handler? = null
    private var lockTaskStarted = false
    private var unlockReceiver: BroadcastReceiver? = null
    private var isDialogShowing = false
    private var commandPollRunnable: Runnable? = null
    private var commandHandler = Handler(Looper.getMainLooper())
    private lateinit var syncClient: SyncClient
    private lateinit var tokenManager: TokenManager
    private lateinit var tracker: UsageTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        enableFullScreen()
        setContentView(R.layout.activity_main)

        syncClient = SyncClient(this)
        tokenManager = TokenManager(this)
        tracker = UsageTracker(this)

        updateBlockMessage()

        findViewById<View>(R.id.blockImage).setOnClickListener {
            handleTap()
        }
    }

    private fun updateBlockMessage() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val reason = prefs.getString("block_reason", null)
        val tvTitle = findViewById<TextView>(R.id.tvBlockTitle)
        val tvReason = findViewById<TextView>(R.id.tvBlockReason)
        val tvSchedule = findViewById<TextView>(R.id.tvBlockSchedule)

        val sStart = prefs.getInt("schedule_start_min", -1)
        val sEnd = prefs.getInt("schedule_end_min", -1)
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val within = if (sEnd > sStart) now in sStart until sEnd else now >= sStart || now < sEnd
        val dbg = "S:$sStart-$sEnd AHORA:$now DENTRO:$within LMT:${tracker.getDailyLimit()} USO:${tracker.getAccumulatedUsage()} TZ:${cal.timeZone.id}"

        if (reason == "schedule") {
            tvTitle.text = "Equipo bloqueado"
            tvReason.text = "Fuera del horario de uso"
            val start = prefs.getString("block_schedule_start", "--:--")
            val end = prefs.getString("block_schedule_end", "--:--")
            tvSchedule.text = "Horario permitido: $start – $end\n$dbg"
            tvSchedule.visibility = View.VISIBLE
        } else if (reason == "time_limit") {
            tvTitle.text = "Tiempo de pantalla terminado"
            tvReason.text = "Alcanzaste el límite diario"
            tvSchedule.text = dbg
            tvSchedule.visibility = View.VISIBLE
        } else {
            tvTitle.text = "Dispositivo bloqueado"
            tvReason.text = dbg
            tvSchedule.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()

        if (unlockReceiver == null) {
            unlockReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == "com.safekidapp.AUTO_UNLOCK") {
                        autoFinish()
                    }
                }
            }
            val filter = IntentFilter("com.safekidapp.AUTO_UNLOCK")
            if (Build.VERSION.SDK_INT >= 34) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
        }

        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("kiosk_active", false)) {
            finishAffinity()
            return
        }

        if (!lockTaskStarted) {
            lockTaskStarted = true
            try {
                startLockTask()
            } catch (_: SecurityException) {}
        }

        startCommandPolling()

        // Check if time is now within schedule → auto-unlock
        val sStart = prefs.getInt("schedule_start_min", -1)
        val sEnd = prefs.getInt("schedule_end_min", -1)
        if (sStart >= 0 && sEnd >= 0) {
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val within = if (sEnd > sStart) now in sStart until sEnd else now >= sStart || now < sEnd
            if (within) {
                val limit = tracker.getDailyLimit()
                val usage = tracker.getAccumulatedUsage()
                if (limit <= 0 || usage < limit) {
                    autoFinish()
                    return
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isDialogShowing = false
        stopCommandPolling()
        unlockReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            unlockReceiver = null
        }
    }

    private fun autoFinish() {
        try { stopLockTask() } catch (_: Exception) {}
        finishAffinity()
    }

    private fun startCommandPolling() {
        stopCommandPolling()
        commandPollRunnable = Runnable {
            checkPendingCommands()
            commandHandler.postDelayed(commandPollRunnable!!, 15000)
        }
        commandHandler.postDelayed(commandPollRunnable!!, 5000)
    }

    private fun stopCommandPolling() {
        commandPollRunnable?.let { commandHandler.removeCallbacks(it) }
        commandPollRunnable = null
    }

    private fun checkPendingCommands() {
        syncClient.getPendingCommands { commands, _ ->
            if (commands != null) {
                for (cmd in commands) {
                    syncClient.markCommandDelivered(cmd.id) { _, _ -> }
                    if (cmd.commandType == "unblock") {
                        unlockDevice()
                    }
                    syncClient.markCommandExecuted(cmd.id) { _, _ -> }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("kiosk_active", false)) {
            try { startLockTask() } catch (_: SecurityException) {}
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullScreen()
        if (!hasFocus && !isDialogShowing) {
            val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("kiosk_active", false)) {
                try { startLockTask() } catch (_: SecurityException) {}
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
        }
    }

    private fun enableFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun handleTap() {
        tapCount++
        if (tapTimer == null) {
            tapTimer = Handler(Looper.getMainLooper())
            tapTimer?.postDelayed({
                tapCount = 0
                tapTimer = null
            }, 2000)
        }

        if (tapCount >= 5) {
            tapCount = 0
            tapTimer?.removeCallbacksAndMessages(null)
            tapTimer = null
            showPasswordDialog()
        }
    }

    private fun showPasswordDialog() {
        isDialogShowing = true
        val inputLayout = TextInputLayout(this)
        val input = TextInputEditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        inputLayout.addView(input)
        inputLayout.setPadding(48, 16, 48, 16)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Desbloquear dispositivo")
            .setMessage("Ingresa la contraseña de adulto")
            .setView(inputLayout)
            .setPositiveButton("Desbloquear") { _, _ ->
                val password = input.text?.toString() ?: ""
                if (checkPassword(password)) {
                    unlockDevice()
                } else {
                    Toast.makeText(this, "Contraseña incorrecta", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .setCancelable(false)
            .show()

        dialog.setOnDismissListener { isDialogShowing = false }
    }

    private fun checkPassword(password: String): Boolean {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val storedHash = prefs.getString("password_hash", null)
        if (storedHash.isNullOrEmpty()) return true
        return storedHash == hashPassword(password)
    }

    private fun unlockDevice() {
        try {
            val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("kiosk_active", false)
                .putBoolean("time_exceeded", false)
                .putBoolean("tracking_enabled", false)
                .putLong("last_unlock_time", System.currentTimeMillis())
                .putLong("daily_usage_ms", 0)
                .apply()

            stopService(Intent(this, UsageService::class.java))
            stopLockTask()

            startActivity(Intent(this, ChildDashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finishAffinity()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al desbloquear: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
