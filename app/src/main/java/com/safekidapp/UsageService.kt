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
    private val handler = Handler(Looper.getMainLooper())
    private var screenOn = false
    private var checkRunnable: Runnable? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, buildNotification())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        screenOn = true
        tracker.setScreenOnTimestamp(System.currentTimeMillis())
        startPeriodicCheck()

        return START_STICKY
    }

    override fun onDestroy() {
        stopPeriodicCheck()
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
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, "usage_tracker")
        .setContentTitle("SafeKid")
        .setContentText("Controlando tiempo de uso")
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

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
        val limit = tracker.getDailyLimit()
        if (limit <= 0) return

        val currentSession = if (screenOn) {
            val ts = tracker.getScreenOnTimestamp()
            if (ts > 0) System.currentTimeMillis() - ts else 0
        } else 0

        if (tracker.getAccumulatedUsage() + currentSession >= limit) {
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
