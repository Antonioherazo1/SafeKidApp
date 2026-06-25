package com.safekidapp

import android.content.Context
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.util.UUID
import java.util.concurrent.Executors

class MqttManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
    private var client: MqttClient? = null
    private val listeners = mutableListOf<(String, String) -> Unit>()
    private val statusListeners = mutableListOf<(String, String) -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()

    fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().take(8)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun getBrokerUrl(): String =
        prefs.getString("mqtt_broker", "tcp://thinc.site:1883") ?: "tcp://thinc.site:1883"

    fun setBrokerUrl(url: String) {
        prefs.edit().putString("mqtt_broker", url).apply()
    }

    fun getParentId(): String? = prefs.getString("parent_id", null)

    fun setParentId(id: String) {
        prefs.edit().putString("parent_id", id).apply()
    }

    fun getLinkedChildren(): MutableSet<String> {
        return prefs.getStringSet("linked_children", mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()
    }

    fun addLinkedChild(childId: String) {
        val children = getLinkedChildren()
        children.add(childId)
        prefs.edit().putStringSet("linked_children", children).apply()
    }

    fun connect(callback: (Boolean) -> Unit) {
        executor.execute {
            try {
                if (client?.isConnected == true) {
                    callback(true)
                    return@execute
                }
                val brokerUrl = getBrokerUrl()
                val clientId = "safekid_${getDeviceId()}_${(1000..9999).random()}"
                val tempDir = context.cacheDir.absolutePath
                client = MqttClient(brokerUrl, clientId, MqttDefaultFilePersistence(tempDir))
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    setCleanSession(true)
                }
                client?.connect(options)
                client?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic != null && message != null) {
                            val payload = String(message.payload)
                            listeners.forEach { it(topic, payload) }
                            if (topic.endsWith("/status")) {
                                statusListeners.forEach { it(topic, payload) }
                            }
                        }
                    }
                })
                callback(true)
            } catch (e: Exception) {
                callback(false)
            }
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                client?.disconnect()
                client?.close()
            } catch (_: Exception) {}
            client = null
        }
    }

    fun publish(topic: String, payload: String) {
        executor.execute {
            try {
                if (client?.isConnected == true) {
                    client?.publish(topic, MqttMessage(payload.toByteArray()))
                }
            } catch (_: Exception) {}
        }
    }

    fun subscribe(topic: String) {
        executor.execute {
            try {
                if (client?.isConnected == true) {
                    client?.subscribe(topic, 2)
                }
            } catch (_: Exception) {}
        }
    }

    fun addMessageListener(listener: (String, String) -> Unit) {
        listeners.add(listener)
    }

    fun removeMessageListener(listener: (String, String) -> Unit) {
        listeners.remove(listener)
    }

    fun addStatusListener(listener: (String, String) -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: (String, String) -> Unit) {
        statusListeners.remove(listener)
    }

    fun pairWithParent(code: String, childName: String, callback: (Boolean) -> Unit) {
        connect { success ->
            if (!success) {
                callback(false)
                return@connect
            }
            val payload = """{"deviceId":"${getDeviceId()}","name":"$childName"}"""
            publish("safekid/pair/$code", payload)
            callback(true)
        }
    }

    fun generatePairingCode(): String {
        val code = (100000..999999).random().toString()
        prefs.edit().putString("current_pairing_code", code).apply()
        return code
    }

    fun listenForPairing(callback: (String, String) -> Unit) {
        val code = prefs.getString("current_pairing_code", null) ?: return
        subscribe("safekid/pair/$code")
        addMessageListener { topic, message ->
            if (topic == "safekid/pair/$code") {
                try {
                    val json = org.json.JSONObject(message)
                    val childId = json.getString("deviceId")
                    val name = json.optString("name", "Hijo")
                    addLinkedChild(childId)
                    callback(childId, name)
                } catch (_: Exception) {}
            }
        }
    }
}
