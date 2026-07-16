package com.safekidapp

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class ChildDetailActivity : AppCompatActivity() {

    private lateinit var syncClient: SyncClient
    private lateinit var tokenManager: TokenManager

    private var deviceId: String = ""
    private var childName: String = ""
    private var childUsername: String = ""
    private var childApiKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_detail)

        syncClient = SyncClient(this)
        tokenManager = TokenManager(this)

        deviceId = intent.getStringExtra("device_id") ?: ""
        childName = intent.getStringExtra("name") ?: ""
        childUsername = intent.getStringExtra("username") ?: ""
        childApiKey = intent.getStringExtra("api_key") ?: ""

        val title = findViewById<TextView>(R.id.tvChildDetailTitle)
        val info = findViewById<TextView>(R.id.tvChildDetailInfo)
        val etLimit = findViewById<TextInputEditText>(R.id.etLimit)
        val btnSetLimit = findViewById<Button>(R.id.btnSetLimit)
        val btnBlock = findViewById<Button>(R.id.btnCmdBlock)
        val btnUnblock = findViewById<Button>(R.id.btnCmdUnblock)
        val btnStartTracking = findViewById<Button>(R.id.btnCmdStartTracking)
        val btnStopTracking = findViewById<Button>(R.id.btnCmdStopTracking)
        val tvStatus = findViewById<TextView>(R.id.tvChildDetailStatus)

        val limit = intent.getIntExtra("daily_limit", 0)
        val todaySeconds = intent.getIntExtra("today_seconds", 0)

        title.text = childName
        info.text = "${todaySeconds / 60} min usado de $limit min límite • API Key: ${childApiKey.take(8)}..."
        etLimit.setText(limit.toString())

        btnSetLimit.setOnClickListener {
            val minutes = etLimit.text.toString().trim().toIntOrNull()
            if (minutes == null || minutes < 0) {
                Toast.makeText(this, "Ingresa un número válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatus.text = "Actualizando límite..."
            btnSetLimit.isEnabled = false
            syncClient.setChildLimit(childUsername, minutes) { ok, error ->
                btnSetLimit.isEnabled = true
                tvStatus.text = if (ok) "Límite actualizado a $minutes min" else "Error: ${error ?: "conexión"}"
            }
        }

        btnBlock.setOnClickListener { sendCommand("block", tvStatus) }
        btnUnblock.setOnClickListener { sendCommand("unblock", tvStatus) }
        btnStartTracking.setOnClickListener { sendCommand("start_tracking", tvStatus) }
        btnStopTracking.setOnClickListener { sendCommand("stop_tracking", tvStatus) }
    }

    private fun sendCommand(type: String, tvStatus: TextView) {
        tvStatus.text = "Enviando comando $type..."
        syncClient.sendCommand(deviceId, type) { ok, error ->
            tvStatus.text = if (ok) "Comando $type enviado" else "Error: ${error ?: "conexión"}"
        }
    }
}
