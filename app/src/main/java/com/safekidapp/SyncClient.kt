package com.safekidapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

data class CloudStats(
    val todaySeconds: Int,
    val limitMinutes: Int,
    val history: List<DayEntry>
)

data class DayEntry(
    val date: String,
    val totalSeconds: Int,
    val limitMinutes: Int
)

data class ChildInfo(
    val deviceId: String,
    val name: String,
    val apiKey: String,
    val dailyLimitMinutes: Int,
    val todaySeconds: Int
)

data class PendingCommandInfo(
    val id: Int,
    val commandType: String,
    val createdAt: String
)

class SyncClient(private val context: Context) {

    private val prefs = context.getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serverUrl: String?
        get() {
            val url = prefs.getString("server_url", null)
            if (url.isNullOrBlank()) return null
            return url.trimEnd('/')
        }

    private val apiKey: String?
        get() {
            val key = prefs.getString("api_key", null)
            if (key.isNullOrBlank()) return null
            return key
        }

    private val tokenManager by lazy { TokenManager(context) }

    private fun apiRequest(
        path: String,
        method: String = "GET",
        json: String? = null,
        token: String? = null,
        apiKey: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        val url = serverUrl ?: run { callback(false, "Server URL not configured"); return }

        var builder = Request.Builder().url("$url$path")

        when (method) {
            "POST" -> builder = builder.post(
                (json ?: "{}").toRequestBody(MEDIA_JSON)
            )
            "PUT" -> builder = builder.put(
                (json ?: "{}").toRequestBody(MEDIA_JSON)
            )
            else -> builder = builder.get()
        }

        if (token != null) {
            builder = builder.header("Authorization", "Bearer $token")
        } else if (apiKey != null) {
            builder = builder.header("Authorization", "Bearer $apiKey")
        }

        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, e.message ?: "Connection failed") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    mainHandler.post { callback(true, body) }
                } else {
                    val msg = JSONObject(body).optString("detail", "Error ${response.code}")
                    mainHandler.post { callback(false, msg) }
                }
            }
        })
    }

    fun isConfigured(): Boolean = serverUrl != null && apiKey != null

    fun getDeviceName(): String? = prefs.getString("device_name", null)

    // ── Auth ──

    fun register(username: String, password: String, role: String, callback: (Boolean, String?) -> Unit) {
        val json = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("role", role)

        apiRequest("/auth/register", "POST", json.toString()) { ok, body ->
            if (ok && body != null) {
                val obj = JSONObject(body)
                tokenManager.saveLogin(
                    token = obj.getString("token"),
                    userId = obj.getString("user_id"),
                    username = obj.getString("username"),
                    role = obj.getString("role")
                )
            }
            callback(ok, if (ok) null else body)
        }
    }

    fun login(username: String, password: String, callback: (Boolean, String?) -> Unit) {
        val json = JSONObject()
            .put("username", username)
            .put("password", password)

        apiRequest("/auth/login", "POST", json.toString()) { ok, body ->
            if (ok && body != null) {
                val obj = JSONObject(body)
                tokenManager.saveLogin(
                    token = obj.getString("token"),
                    userId = obj.getString("user_id"),
                    username = obj.getString("username"),
                    role = obj.getString("role")
                )
            }
            callback(ok, if (ok) null else body)
        }
    }

    // ── Device registration (now uses JWT) ──

    fun registerDevice(name: String, callback: (Boolean, String?) -> Unit) {
        val token = tokenManager.getToken() ?: run {
            callback(false, "Not logged in")
            return
        }

        val json = JSONObject().put("name", name)
        apiRequest("/device/register", "POST", json.toString(), token = token) { ok, body ->
            if (ok && body != null) {
                val obj = JSONObject(body)
                prefs.edit()
                    .putString("api_key", obj.getString("api_key"))
                    .putString("device_id", obj.getString("device_id"))
                    .putString("device_name", name)
                    .apply()
            }
            callback(ok, if (ok) null else body)
        }
    }

    // ── Sync (uses api_key, unchanged) ──

    fun syncToday(totalSeconds: Int, callback: (Boolean, String?) -> Unit) {
        val key = apiKey ?: run { callback(false, "Not configured"); return }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val json = JSONObject()
            .put("date", today)
            .put("total_seconds", totalSeconds)
            .put("sessions", JSONArray())

        apiRequest("/sync", "POST", json.toString(), apiKey = key) { ok, body ->
            if (ok && body != null) {
                val obj = JSONObject(body)
                val limit = obj.optInt("daily_limit_minutes", 0)
                prefs.edit().putInt("cloud_limit_minutes", limit).apply()
            }
            callback(ok, if (ok) null else body)
        }
    }

    // ── Stats (uses api_key, unchanged) ──

    fun fetchStats(days: Int = 7, callback: (CloudStats?, String?) -> Unit) {
        val key = apiKey ?: run { callback(null, "Not configured"); return }

        val request = Request.Builder()
            .url("$serverUrl/stats?days=$days")
            .header("Authorization", "Bearer $key")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(null, e.message ?: "Connection failed") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    val msg = JSONObject(body).optString("detail", "Error ${response.code}")
                    mainHandler.post { callback(null, msg) }
                    return
                }

                val json = JSONObject(body)
                val todayJson = json.optJSONObject("today") ?: JSONObject()
                val historyArray = json.optJSONArray("history") ?: JSONArray()

                val history = mutableListOf<DayEntry>()
                for (i in 0 until historyArray.length()) {
                    val h = historyArray.getJSONObject(i)
                    history.add(
                        DayEntry(
                            date = h.optString("date", ""),
                            totalSeconds = h.optInt("total_seconds", 0),
                            limitMinutes = h.optInt("limit_minutes", 0),
                        )
                    )
                }

                val stats = CloudStats(
                    todaySeconds = todayJson.optInt("total_seconds", 0),
                    limitMinutes = todayJson.optInt("limit_minutes", 0),
                    history = history,
                )
                mainHandler.post { callback(stats, null) }
            }
        })
    }

    // ── Parent endpoints ──

    fun getChildren(callback: (List<ChildInfo>?, String?) -> Unit) {
        val token = tokenManager.getToken() ?: run { callback(null, "Not logged in"); return }

        apiRequest("/parent/children", "GET", token = token) { ok, body ->
            if (ok && body != null) {
                val json = JSONObject(body)
                val arr = json.optJSONArray("children") ?: JSONArray()
                val list = mutableListOf<ChildInfo>()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    list.add(
                        ChildInfo(
                            deviceId = c.getString("device_id"),
                            name = c.getString("name"),
                            apiKey = c.getString("api_key"),
                            dailyLimitMinutes = c.getInt("daily_limit_minutes"),
                            todaySeconds = c.getInt("today_seconds"),
                        )
                    )
                }
                callback(list, null)
            } else {
                callback(null, body)
            }
        }
    }

    fun linkChild(childUsername: String, callback: (Boolean, String?) -> Unit) {
        val token = tokenManager.getToken() ?: run { callback(false, "Not logged in"); return }
        val json = JSONObject().put("child_username", childUsername)
        apiRequest("/parent/link-child", "POST", json.toString(), token = token) { ok, body ->
            callback(ok, if (ok) null else body)
        }
    }

    fun setChildLimit(childUsername: String, limitMinutes: Int, callback: (Boolean, String?) -> Unit) {
        val token = tokenManager.getToken() ?: run { callback(false, "Not logged in"); return }
        apiRequest("/parent/set-limit?child_username=$childUsername&limit_minutes=$limitMinutes", "POST", token = token) { ok, body ->
            callback(ok, if (ok) null else body)
        }
    }

    fun sendCommand(toDeviceId: String, commandType: String, callback: (Boolean, String?) -> Unit) {
        val token = tokenManager.getToken() ?: run { callback(false, "Not logged in"); return }
        val json = JSONObject()
            .put("to_device_id", toDeviceId)
            .put("command_type", commandType)
        apiRequest("/commands/send", "POST", json.toString(), token = token) { ok, body ->
            callback(ok, if (ok) null else body)
        }
    }

    // ── Child / command endpoints ──

    fun getPendingCommands(callback: (List<PendingCommandInfo>?, String?) -> Unit) {
        val token = tokenManager.getToken() ?: run { callback(null, "Not logged in"); return }

        apiRequest("/commands/pending", "GET", token = token) { ok, body ->
            if (ok && body != null) {
                val json = JSONObject(body)
                val arr = json.optJSONArray("commands") ?: JSONArray()
                val list = mutableListOf<PendingCommandInfo>()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    list.add(
                        PendingCommandInfo(
                            id = c.getInt("id"),
                            commandType = c.getString("command_type"),
                            createdAt = c.optString("created_at", ""),
                        )
                    )
                }
                callback(list, null)
            } else {
                callback(null, body)
            }
        }
    }

    fun markCommandDelivered(commandId: Int, callback: (Boolean, String?) -> Unit) {
        val token = tokenManager.getToken() ?: run { callback(false, "Not logged in"); return }
        apiRequest("/commands/$commandId/delivered", "POST", token = token) { ok, body ->
            callback(ok, if (ok) null else body)
        }
    }

    fun markCommandExecuted(commandId: Int, callback: (Boolean, String?) -> Unit) {
        val token = tokenManager.getToken() ?: run { callback(false, "Not logged in"); return }
        apiRequest("/commands/$commandId/executed", "POST", token = token) { ok, body ->
            callback(ok, if (ok) null else body)
        }
    }

    // ── Server URL check ──

    fun checkServerUrl(url: String, callback: (Boolean, String?) -> Unit) {
        val cleanUrl = url.trimEnd('/')
        val request = Request.Builder()
            .url("$cleanUrl/health")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, e.message ?: "Connection failed") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    mainHandler.post { callback(true, null) }
                } else {
                    mainHandler.post { callback(false, "Server returned ${response.code}") }
                }
            }
        })
    }

    companion object {
        private val MEDIA_JSON = "application/json; charset=utf-8".toMediaType()
    }
}
