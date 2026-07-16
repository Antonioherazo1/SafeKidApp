package com.safekidapp

import android.content.Context

class TokenManager(context: Context) {

    private val prefs = context.getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)

    fun saveLogin(token: String, userId: String, username: String, role: String) {
        prefs.edit()
            .putString("jwt_token", token)
            .putString("user_id", userId)
            .putString("username", username)
            .putString("role", role)
            .apply()
    }

    fun getToken(): String? = prefs.getString("jwt_token", null)

    fun getUserId(): String? = prefs.getString("user_id", null)

    fun getUsername(): String? = prefs.getString("username", null)

    fun getRole(): String? = prefs.getString("role", null)

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
            .apply()
    }
}
