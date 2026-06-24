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
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class UsageService : Service() {

    private lateinit var tracker: UsageTracker
    private lateinit var nm: NotificationManager
    private lateinit var mqttManager: MqttManager
    private val handler = Handler(Looper.getMainLooper())
    private var userPresent = false
    private var screenOn = false
    private var checkRunnable: Runnable? = null
    private var notifRunnable: Runnable? = null
    private var blockTriggered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                }
                Intent.ACTION_USER_PRESENT -> {
                    userPresent = true
                    if (screenOn) {
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
        mqttManager = MqttManager(this)
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

        if (screenOn && userPresent) {
            tracker.setScreenOnTimestamp(System.currentTimeMillis())
            startPeriodicCheck()
        }

        connectMqtt()

        startNotificationUpdater()
        return START_STICKY
    }

    private fun connectMqtt() {
        mqttManager.connect { connected ->
            if (connected) {
                val deviceId = mqttManager.getDeviceId()
                mqttManager.subscribe("safekid/child/$deviceId/commands")
                mqttManager.addMessageListener { topic, message ->
                    handleCommand(topic, message)
                }
            }
        }
    }

    private fun handleCommand(topic: String, message: String) {
        if (!topic.endsWith("/commands")) return
        try {
            val json = JSONObject(message)
            val action = json.getString("action")
            when (action) {
                "set_limit" -> {
                    val minutes = json.optInt("value", 0)
                    if (minutes > 0) tracker.setDailyLimit(minutes)
                }
                "add_time" -> {
                    val minutes = json.optInt("value", 0)
                    if (minutes > 0) {
                        val current = tracker.getDailyLimit()
                        tracker.setDailyLimit((current / 60000).toInt() + minutes)
                    }
                }
                "block" -> {
                    triggerBlock()
                }
                "unblock" -> {
                    val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("kiosk_active", false)
                        .putBoolean("time_exceeded", false)
                        .apply()
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopPeriodicCheck()
        stopNotificationUpdater()
        mqttManager.disconnect()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("usage_tracker", "SafeKid", NotificationManager.IMPORTANCE_HIGH).apply {
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
            publishStatus()
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

    private fun publishStatus() {
        val used = tracker.getAccumulatedUsage()
        val limit = tracker.getDailyLimit()
        val remaining = if (limit > 0) (limit - used).coerceAtLeast(0) else -1
        val locked = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            .getBoolean("kiosk_active", false)

        val json = JSONObject().apply {
            put("used", used / 60000)
            put("limit", limit / 60000)
            put("remaining", remaining / 60000)
            put("locked", locked)
            put("timestamp", System.currentTimeMillis())
        }

        val deviceId = mqttManager.getDeviceId()
        mqttManager.publish("safekid/child/$deviceId/status", json.toString())
    }

    private fun updateNotification() {
        val text = formatRemaining()
        nm.notify(1, buildNotificationText(text))
    }

    private fun formatRemaining(): String {
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
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (_: Exception) {}
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

            tracker.setTimeExceeded(true)
            triggerBlock()
        }
    }

    private fun triggerBlock() {
        if (blockTriggered) return
        blockTriggered = true

        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("kiosk_active", true).apply()

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            try {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            } catch (_: SecurityException) {}
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        nm.notify(2, NotificationCompat.Builder(this, "usage_tracker")
            .setContentTitle("SafeKid — Tiempo agotado")
            .setContentText("La pantalla de bloqueo se abrirá")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setFullScreenIntent(pendingIntent, true)
            .build())

        try {
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("SafeKid", "startActivity falló", e)
        }
    }
}
