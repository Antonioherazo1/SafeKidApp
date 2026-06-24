package com.safekidapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class UsageService : Service() {

    private lateinit var tracker: UsageTracker
    private lateinit var nm: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var screenOn = false
    private var checkRunnable: Runnable? = null
    private var notifRunnable: Runnable? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    tracker.setScreenOnTimestamp(System.currentTimeMillis())
                    startPeriodicCheck()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    stopPeriodicCheck()
                    val onTimestamp = tracker.getScreenOnTimestamp()
                    if (onTimestamp > 0) {
                        tracker.addUsage(System.currentTimeMillis() - onTimestamp)
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
        }
        registerReceiver(screenReceiver, filter)

        screenOn = true
        tracker.setScreenOnTimestamp(System.currentTimeMillis())
        startPeriodicCheck()
        startNotificationUpdater()

        return START_STICKY
    }

    override fun onDestroy() {
        stopPeriodicCheck()
        stopNotificationUpdater()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "usage_tracker", "SafeKid",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
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
            handler.postDelayed(notifRunnable!!, 30000)
        }
        handler.postDelayed(notifRunnable!!, 1000)
    }

    private fun stopNotificationUpdater() {
        notifRunnable?.let { handler.removeCallbacks(it) }
        notifRunnable = null
    }

    private fun updateNotification() {
        val limit = tracker.getDailyLimit()
        val used = tracker.getAccumulatedUsage()
        val currentSession = if (screenOn) {
            val ts = tracker.getScreenOnTimestamp()
            if (ts > 0) System.currentTimeMillis() - ts else 0
        } else 0
        val total = used + currentSession

        val text = if (limit <= 0) {
            "Control activo (sin límite)"
        } else if (total >= limit) {
            "Tiempo agotado"
        } else {
            val remaining = (limit - total) / 60000
            if (remaining < 1) "Menos de 1 min restante"
            else if (remaining == 1) "1 min restante"
            else "$remaining min restantes"
        }

        nm.notify(1, buildNotificationText(text))
    }

    private fun startPeriodicCheck() {
        stopPeriodicCheck()
        checkRunnable = Runnable {
            checkTimeLimit()
            if (screenOn) {
                handler.postDelayed(checkRunnable!!, 10000)
            }
        }
        handler.postDelayed(checkRunnable!!, 10000)
    }

    private fun stopPeriodicCheck() {
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
    }

    private fun checkTimeLimit() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val limit = tracker.getDailyLimit()
        if (limit <= 0) return

        val currentSession = if (screenOn) {
            val ts = tracker.getScreenOnTimestamp()
            if (ts > 0) System.currentTimeMillis() - ts else 0
        } else 0

        if (tracker.getAccumulatedUsage() + currentSession >= limit) {
            val lastUnlock = prefs.getLong("last_unlock_time", 0)
            val graceMs = 3 * 60 * 1000L
            if (System.currentTimeMillis() - lastUnlock < graceMs) return

            tracker.setTimeExceeded(true)
            triggerBlock()
        }
    }

    private fun triggerBlock() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("kiosk_active", true).apply()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }
}
