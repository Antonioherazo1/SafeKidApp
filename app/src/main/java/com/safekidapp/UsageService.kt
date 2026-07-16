package com.safekidapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.Calendar

class UsageService : Service() {

    private lateinit var tracker: UsageTracker
    private lateinit var nm: NotificationManager
    private lateinit var syncClient: SyncClient
    private val handler = Handler(Looper.getMainLooper())
    private var userPresent = false
    private var screenOn = false
    private var periodicRunnable: Runnable? = null
    private var notificationRunnable: Runnable? = null
    private var scheduleMonitorRunnable: Runnable? = null

    private fun isTracking(): Boolean {
        return getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            .getBoolean("tracking_enabled", false)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                }
                Intent.ACTION_USER_PRESENT -> {
                    userPresent = true
                    if (screenOn && isTracking()) {
                        tracker.setScreenOnTimestamp(System.currentTimeMillis())
                        startPeriodicCheck()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    userPresent = false
                    stopPeriodicCheck()
                    val ts = tracker.getScreenOnTimestamp()
                    if (ts > 0) {
                        tracker.addUsage(System.currentTimeMillis() - ts)
                        tracker.setScreenOnTimestamp(0)
                    }
                    checkTimeLimit()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        tracker = UsageTracker(this)
        syncClient = SyncClient(this)
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("Iniciando..."))

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, filter)

        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        screenOn = pm.isInteractive
        userPresent = !km.isKeyguardLocked

        if (screenOn && userPresent && isTracking()) {
            tracker.setScreenOnTimestamp(System.currentTimeMillis())
            startPeriodicCheck()
        }

        startNotificationUpdates()
        startScheduleMonitor()

        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("kiosk_active", false)) {
            handler.post { triggerBlock() }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopPeriodicCheck()
        stopNotificationUpdates()
        stopScheduleMonitor()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("usage_tracker", "SafeKid", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, "usage_tracker")
        .setContentTitle("SafeKid")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)
        .setOngoing(true)
        .build()

    private fun startNotificationUpdates() {
        stopNotificationUpdates()
        notificationRunnable = Runnable {
            updateNotification()
            handler.postDelayed(notificationRunnable!!, 5000)
        }
        handler.postDelayed(notificationRunnable!!, 1000)
    }

    private fun stopNotificationUpdates() {
        notificationRunnable?.let { handler.removeCallbacks(it) }
        notificationRunnable = null
    }

    private fun updateNotification() {
        val text = if (isTracking()) {
            val used = tracker.getAccumulatedUsage()
            val limit = tracker.getDailyLimit()
            if (limit > 0) "${used / 60000}/${limit / 60000} min" else "${used / 60000} min"
        } else {
            "Control desactivado"
        }
        nm.notify(1, buildNotification(text))
    }

    private fun startPeriodicCheck() {
        stopPeriodicCheck()
        periodicRunnable = Runnable {
            periodicCheck()
            if (userPresent && screenOn) {
                handler.postDelayed(periodicRunnable!!, 10000)
            }
        }
        handler.postDelayed(periodicRunnable!!, 10000)
    }

    private fun stopPeriodicCheck() {
        periodicRunnable?.let { handler.removeCallbacks(it) }
        periodicRunnable = null
    }

    // ── Background schedule monitor (runs always, even with screen off) ──

    private fun startScheduleMonitor() {
        stopScheduleMonitor()
        scheduleMonitorRunnable = Runnable {
            syncCloudSchedule()
            checkSchedule()
            handler.postDelayed(scheduleMonitorRunnable!!, 30000)
        }
        handler.postDelayed(scheduleMonitorRunnable!!, 5000)
    }

    private fun stopScheduleMonitor() {
        scheduleMonitorRunnable?.let { handler.removeCallbacks(it) }
        scheduleMonitorRunnable = null
    }

    private fun syncCloudSchedule() {
        if (!syncClient.isConfigured()) return
        val usedSeconds = (tracker.getAccumulatedUsage() / 1000).toInt()
        syncClient.syncToday(usedSeconds) { _, _ -> }
    }

    private fun checkSchedule() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val withinSchedule = isWithinSchedule()

        if (prefs.getBoolean("kiosk_active", false)) {
            if (tracker.isDifferentDay()) {
                autoUnlock()
                return
            }
            if (withinSchedule) {
                val limit = tracker.getDailyLimit()
                val usage = tracker.getAccumulatedUsage()
                if (limit <= 0 || usage < limit) {
                    autoUnlock()
                    return
                }
            }
            return
        }

        if (!withinSchedule && prefs.getInt("schedule_start_min", -1) >= 0) {
            val hourStart = prefs.getInt("schedule_start_min", 0) / 60
            val minStart = prefs.getInt("schedule_start_min", 0) % 60
            val hourEnd = prefs.getInt("schedule_end_min", 0) / 60
            val minEnd = prefs.getInt("schedule_end_min", 0) % 60
            prefs.edit()
                .putString("block_reason", "schedule")
                .putString("block_schedule_start", String.format("%02d:%02d", hourStart, minStart))
                .putString("block_schedule_end", String.format("%02d:%02d", hourEnd, minEnd))
                .apply()
            triggerBlock()
        }
    }

    // ── Periodic check (runs when user is active) ──

    private fun periodicCheck() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val withinSchedule = isWithinSchedule()

        if (prefs.getBoolean("kiosk_active", false)) {
            if (tracker.isDifferentDay()) {
                autoUnlock()
                return
            }
            if (withinSchedule) {
                val limit = tracker.getDailyLimit()
                val usage = tracker.getAccumulatedUsage()
                if (limit <= 0 || usage < limit) {
                    autoUnlock()
                    return
                }
            }
            ensureBlockScreen()
            return
        }

        // Schedule check first — independent of tracking
        if (!withinSchedule && prefs.getInt("schedule_start_min", -1) >= 0) {
            val hourStart = prefs.getInt("schedule_start_min", 0) / 60
            val minStart = prefs.getInt("schedule_start_min", 0) % 60
            val hourEnd = prefs.getInt("schedule_end_min", 0) / 60
            val minEnd = prefs.getInt("schedule_end_min", 0) % 60
            prefs.edit()
                .putString("block_reason", "schedule")
                .putString("block_schedule_start", String.format("%02d:%02d", hourStart, minStart))
                .putString("block_schedule_end", String.format("%02d:%02d", hourEnd, minEnd))
                .apply()
            triggerBlock()
            return
        }

        if (!isTracking()) return

        saveCurrentSession()
        checkTimeLimit()
    }

    private fun isWithinSchedule(): Boolean {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val sStart = prefs.getInt("schedule_start_min", -1)
        val sEnd = prefs.getInt("schedule_end_min", -1)
        if (sStart < 0 || sEnd < 0) return true

        val cal = Calendar.getInstance()
        val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        return if (sEnd > sStart) {
            currentMin in sStart until sEnd
        } else {
            currentMin >= sStart || currentMin < sEnd
        }
    }

    private fun saveCurrentSession() {
        if (userPresent && screenOn) {
            val ts = tracker.getScreenOnTimestamp()
            if (ts > 0) {
                val now = System.currentTimeMillis()
                val elapsed = now - ts
                if (elapsed > 0) {
                    tracker.addUsage(elapsed)
                    tracker.setScreenOnTimestamp(now)
                }
            }
        }
    }

    private fun checkTimeLimit() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val limit = tracker.getDailyLimit()
        if (limit <= 0) return

        val currentSession = if (userPresent && screenOn) {
            val ts = tracker.getScreenOnTimestamp()
            if (ts > 0) System.currentTimeMillis() - ts else 0
        } else 0

        if (tracker.getAccumulatedUsage() + currentSession >= limit) {
            val lastUnlock = prefs.getLong("last_unlock_time", 0)
            if (System.currentTimeMillis() - lastUnlock < 3 * 60 * 1000L) return
            prefs.edit().putString("block_reason", "time_limit").apply()
            triggerBlock()
        }
    }

    private fun triggerBlock() {
        val ts = tracker.getScreenOnTimestamp()
        if (ts > 0) {
            tracker.addUsage(System.currentTimeMillis() - ts)
            tracker.setScreenOnTimestamp(0)
        }

        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("kiosk_active", true).apply()

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            try {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            } catch (_: SecurityException) {}
        }

        ensureBlockScreen()
    }

    private fun ensureBlockScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        scheduleAlarm(intent)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Settings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun scheduleAlarm(intent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pendingIntent
                )
                return
            } catch (_: Exception) {}
        }
        try {
            alarmManager.set(
                android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pendingIntent
            )
        } catch (_: Exception) {}
    }

    private fun autoUnlock() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("kiosk_active", false)
            .putBoolean("time_exceeded", false)
            .putLong("last_unlock_time", System.currentTimeMillis())
            .apply()
        tracker.setScreenOnTimestamp(0)
        sendBroadcast(Intent("com.safekidapp.AUTO_UNLOCK"))
    }
}
