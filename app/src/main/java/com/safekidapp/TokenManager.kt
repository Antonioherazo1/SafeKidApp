package com.safekidapp

import android.content.Context

class TokenManager(context: Context) {

    private val prefs = context.getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)

    fun saveLogin(token: String, userId: String, username: String, role: String, deviceId: String? = null, apiKey: String? = null, parentUsername: String? = null) {
        prefs.edit()
            .putString("jwt_token", token)
            .putString("user_id", userId)
            .putString("username", username)
            .putString("role", role)
            .putString("device_id", deviceId)
            .putString("api_key", apiKey)
            .putString("parent_username", parentUsername)
            .apply()
    }

    fun getToken(): String? = prefs.getString("jwt_token", null)

    fun getUserId(): String? = prefs.getString("user_id", null)

    fun getUsername(): String? = prefs.getString("username", null)

    fun getRole(): String? = prefs.getString("role", null)

    fun getDeviceId(): String? = prefs.getString("device_id", null)

    fun getApiKey(): String? = prefs.getString("api_key", null)

    fun getParentUsername(): String? = prefs.getString("parent_username", null)

    fun isLoggedIn(): Boolean = getToken() != null

    fun isParent(): Boolean = getRole() == "parent"

    fun isChild(): Boolean = getRole() == "child"

    fun getServerUrl(): String? {
        val url = prefs.getString("server_url", null)
        if (url.isNullOrBlank()) return null
        return url.trimEnd('/')
    }

    fun logout() {
        prefs.edit()
            .remove("jwt_token")
            .remove("user_id")
            .remove("username")
            .remove("role")
            .remove("device_id")
            .remove("api_key")
            .remove("parent_username")
            .apply()
    }
}
