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
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class UsageService : Service() {

    private lateinit var tracker: UsageTracker
    private lateinit var nm: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var userPresent = false
    private var screenOn = false
    private var checkRunnable: Runnable? = null
    private var notifRunnable: Runnable? = null
    private var blockTriggered = false
    private var overlayView: View? = null
    private var overlayTapCount = 0
    private val overlayHandler = Handler(Looper.getMainLooper())

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
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, buildNotificationText("Iniciando..."))

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

        startNotificationUpdater()

        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("kiosk_active", false)) {
            handler.post { triggerBlock() }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopPeriodicCheck()
        stopNotificationUpdater()
        removeBlockOverlay()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel("usage_tracker")
            if (existing == null || existing.importance != NotificationManager.IMPORTANCE_HIGH) {
                if (existing != null) nm.deleteNotificationChannel("usage_tracker")
                val channel = NotificationChannel("usage_tracker", "SafeKid", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotificationText(text: String) = NotificationCompat.Builder(this, "usage_tracker")
        .setContentTitle("SafeKid")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    private fun startNotificationUpdater() {
        stopNotificationUpdater()
        notifRunnable = Runnable {
            updateNotification()
            checkTimeLimit()
            ensureBlockActive()
            handler.postDelayed(notifRunnable!!, 10000)
        }
        handler.postDelayed(notifRunnable!!, 1000)
    }

    private fun stopNotificationUpdater() {
        notifRunnable?.let { handler.removeCallbacks(it) }
        notifRunnable = null
    }

    private fun updateNotification() {
        val text = formatRemaining()
        val used = tracker.getAccumulatedUsage()
        val limit = tracker.getDailyLimit()
        val tracking = isTracking()
        val debug = if (blockTriggered) " [BLOQUEADO]" else ""
        val trackInfo = if (tracking && limit > 0) " (${used/60000}/${limit/60000}min)" else ""
        val fullText = if (tracking) "$text$trackInfo$debug" else "Control desactivado"
        nm.notify(1, buildNotificationText(fullText))
    }

    private fun formatRemaining(): String {
        if (!isTracking()) return ""

        val limit = tracker.getDailyLimit()
        val used = tracker.getAccumulatedUsage()
        val currentSession = if (userPresent && screenOn) {
            val ts = tracker.getScreenOnTimestamp()
            if (ts > 0) System.currentTimeMillis() - ts else 0
        } else 0
        val total = used + currentSession

        return when {
            limit <= 0 -> "Control activo (sin límite)"
            total >= limit -> "Tiempo agotado"
            else -> {
                val remaining = limit - total
                val min = remaining / 60000
                val sec = (remaining % 60000) / 1000
                if (min > 0) "${min}m ${sec}s restantes"
                else "${sec}s restantes"
            }
        }
    }

    private fun startPeriodicCheck() {
        stopPeriodicCheck()
        checkRunnable = Runnable {
            checkTimeLimit()
            if (userPresent && screenOn) {
                handler.postDelayed(checkRunnable!!, 10000)
            }
        }
        handler.postDelayed(checkRunnable!!, 10000)
    }

    private fun stopPeriodicCheck() {
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
    }

    private fun ensureBlockActive() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("kiosk_active", false)) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        scheduleAlarm(intent, 1)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Settings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
            } catch (_: Exception) {}
        }

        if (overlayView == null) {
            showBlockOverlay()
        }
    }

    private fun removeBlockOverlay() {
        overlayView?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
        overlayTapCount = 0
        overlayHandler.removeCallbacksAndMessages(null)
    }

    private fun autoUnlock() {
        blockTriggered = false
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("kiosk_active", false)
            .putBoolean("time_exceeded", false)
            .putLong("last_unlock_time", System.currentTimeMillis())
            .apply()
        removeBlockOverlay()
        sendBroadcast(Intent("com.safekidapp.AUTO_UNLOCK"))
    }

    private fun checkTimeLimit() {
        if (!isTracking()) return

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

            tracker.setTimeExceeded(true)
            triggerBlock()
        }
    }

    private fun scheduleAlarm(intent: Intent, requestCode: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                alarmManager.setAlarmClock(
                    android.app.AlarmManager.AlarmClockInfo(System.currentTimeMillis(), pendingIntent),
                    pendingIntent
                )
                return
            } catch (_: SecurityException) {}
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pendingIntent
                )
                return
            } catch (_: SecurityException) {}
        }
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

    private fun triggerBlock() {
        if (blockTriggered) return
        blockTriggered = true

        android.util.Log.e("SafeKid", "triggerBlock called")

        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("kiosk_active", true).apply()

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            try {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            } catch (_: SecurityException) {}
        }

        scheduleAlarm(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }, 0)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            } catch (_: Exception) {}
        }

        showBlockOverlay()
    }

    private fun showBlockOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.TRANSLUCENT
        }

        val tv = TextView(this)
        tv.apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            text = "Tiempo agotado\n\nToca 5 veces para desbloquear"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        tv.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                overlayTapCount++
                if (overlayTapCount >= 5) {
                    overlayTapCount = 0
                    try {
                        startActivity(Intent(this@UsageService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                    } catch (_: Exception) {}
                } else {
                    val remaining = 5 - overlayTapCount
                    tv.text = "Tiempo agotado\n\nToca $remaining veces más para desbloquear"
                    overlayHandler.removeCallbacksAndMessages(null)
                    overlayHandler.postDelayed({
                        overlayTapCount = 0
                        tv.text = "Tiempo agotado\n\nToca 5 veces para desbloquear"
                    }, 3000)
                }
            }
            true
        }

        overlayView = tv
        try {
            wm.addView(tv, layoutParams)
        } catch (_: Exception) {}
    }
}
