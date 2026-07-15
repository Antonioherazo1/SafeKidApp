package com.safekidapp

import android.content.Context
import java.util.Calendar

class UsageTracker(context: Context) {

    private val prefs = context.getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)

    fun getDailyLimit(): Long = prefs.getLong("daily_limit_ms", 0)

    fun setDailyLimit(minutes: Int) {
        prefs.edit().putLong("daily_limit_ms", minutes * 60 * 1000L).apply()
    }

    fun getAccumulatedUsage(): Long {
        resetIfNewDay()
        return prefs.getLong("daily_usage_ms", 0)
    }

    fun addUsage(ms: Long) {
        resetIfNewDay()
        val current = prefs.getLong("daily_usage_ms", 0)
        prefs.edit().putLong("daily_usage_ms", current + ms).apply()
    }

    fun getScreenOnTimestamp(): Long = prefs.getLong("screen_on_timestamp", 0)

    fun setScreenOnTimestamp(ts: Long) {
        prefs.edit().putLong("screen_on_timestamp", ts).apply()
    }

    fun isTimeExceeded(): Boolean = prefs.getBoolean("time_exceeded", false)

    fun setTimeExceeded(value: Boolean) {
        prefs.edit().putBoolean("time_exceeded", value).apply()
    }

    fun isTrackingEnabled(): Boolean = prefs.getBoolean("tracking_enabled", false)

    fun setTrackingEnabled(value: Boolean) {
        prefs.edit().putBoolean("tracking_enabled", value).apply()
    }

    fun resetDaily() {
        prefs.edit()
            .putLong("daily_usage_ms", 0)
            .putBoolean("time_exceeded", false)
            .putLong("last_usage_reset_date", getTodayStart())
            .apply()
    }

    fun getRemainingMinutes(): Int {
        val limit = getDailyLimit()
        if (limit <= 0) return -1
        val used = getAccumulatedUsage()
        val remaining = limit - used
        return (remaining / 60000).toInt()
    }

    fun getUsedMinutes(): Int {
        return (getAccumulatedUsage() / 60000).toInt()
    }

    fun getLimitMinutes(): Int {
        return (getDailyLimit() / 60000).toInt()
    }

    fun isDifferentDay(): Boolean {
        val lastReset = prefs.getLong("last_usage_reset_date", 0)
        return getTodayStart() != lastReset
    }

    private fun resetIfNewDay() {
        val lastReset = prefs.getLong("last_usage_reset_date", 0)
        val today = getTodayStart()
        if (today != lastReset) {
            resetDaily()
        }
    }

    private fun getTodayStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
