package com.safekidapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            val track = prefs.getBoolean("tracking_enabled", false)
            val kiosk = prefs.getBoolean("kiosk_active", false)

            if (track || kiosk) {
                val serviceIntent = Intent(context, UsageService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }

            if (kiosk) {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminComponent = ComponentName(context, AdminReceiver::class.java)
                if (dpm.isAdminActive(adminComponent)) {
                    try {
                        dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
                    } catch (_: SecurityException) {}
                }

                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                val pendingIntent = PendingIntent.getActivity(
                    context, 3, activityIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        alarmManager.setAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pendingIntent
                        )
                    } catch (_: Exception) {
                        alarmManager.set(
                            android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pendingIntent
                        )
                    }
                } else {
                    alarmManager.set(
                        android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pendingIntent
                    )
                }

                try {
                    context.startActivity(activityIntent)
                } catch (_: Exception) {}
            }
        }
    }
}
