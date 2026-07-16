package com.safekidapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar

class ChildDetailActivity : AppCompatActivity() {

    private lateinit var syncClient: SyncClient
    private lateinit var tokenManager: TokenManager

    private var deviceId: String = ""
    private var childName: String = ""
    private var childUsername: String = ""
    private var childApiKey: String = ""
    private var scheduleStartMin: Int = -1
    private var scheduleEndMin: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_detail)

        syncClient = SyncClient(this)
        tokenManager = TokenManager(this)

        deviceId = intent.getStringExtra("device_id") ?: ""
        childName = intent.getStringExtra("name") ?: ""
        childUsername = intent.getStringExtra("username") ?: ""
        childApiKey = intent.getStringExtra("api_key") ?: ""
        scheduleStartMin = intent.getIntExtra("schedule_start_min", -1)
        scheduleEndMin = intent.getIntExtra("schedule_end_min", -1)

        val title = findViewById<TextView>(R.id.tvChildDetailTitle)
        val info = findViewById<TextView>(R.id.tvChildDetailInfo)
        val etLimit = findViewById<TextInputEditText>(R.id.etLimit)
        val btnSetLimit = findViewById<Button>(R.id.btnSetLimit)
        val btnBlock = findViewById<Button>(R.id.btnCmdBlock)
        val btnUnblock = findViewById<Button>(R.id.btnCmdUnblock)
        val btnStartTracking = findViewById<Button>(R.id.btnCmdStartTracking)
        val btnStopTracking = findViewById<Button>(R.id.btnCmdStopTracking)
        val tvStatus = findViewById<TextView>(R.id.tvChildDetailStatus)
        val btnScheduleStart = findViewById<Button>(R.id.btnScheduleStart)
        val btnScheduleEnd = findViewById<Button>(R.id.btnScheduleEnd)
        val btnClearSchedule = findViewById<Button>(R.id.btnClearSchedule)

        val limit = intent.getIntExtra("daily_limit", 0)
        val todaySeconds = intent.getIntExtra("today_seconds", 0)

        title.text = childName
        info.text = "${todaySeconds / 60} min usado de $limit min límite • API Key: ${childApiKey.take(8)}..."
        etLimit.setText(limit.toString())

        updateScheduleButtons(btnScheduleStart, btnScheduleEnd)
        updateStatusInfo(limit, todaySeconds)

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

        btnScheduleStart.setOnClickListener { showTimePicker(true, btnScheduleStart, btnScheduleEnd) }
        btnScheduleEnd.setOnClickListener { showTimePicker(false, btnScheduleStart, btnScheduleEnd) }

        btnClearSchedule.setOnClickListener {
            scheduleStartMin = -1
            scheduleEndMin = -1
            updateScheduleButtons(btnScheduleStart, btnScheduleEnd)
            saveSchedule()
        }

        findViewById<Button>(R.id.btnDeleteChild).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Eliminar hijo")
                .setMessage("¿Eliminar a $childName? Se borrarán todos sus datos.")
                .setPositiveButton("Eliminar") { _, _ ->
                    tvStatus.text = "Eliminando hijo..."
                    syncClient.deleteChild(childUsername) { ok, error ->
                        if (ok) {
                            Toast.makeText(this, "Hijo eliminado", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            tvStatus.text = "Error: ${error ?: "conexión"}"
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun showTimePicker(isStart: Boolean, btnStart: Button, btnEnd: Button) {
        val currentVal = if (isStart) scheduleStartMin else scheduleEndMin
        val currentHour: Int
        val currentMinute: Int
        if (currentVal >= 0) {
            currentHour = currentVal / 60
            currentMinute = currentVal % 60
        } else {
            currentHour = if (isStart) 8 else 20
            currentMinute = 0
        }

        MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(currentHour)
            .setMinute(currentMinute)
            .setTitleText(if (isStart) "Hora de inicio" else "Hora de fin")
            .build()
            .apply {
                addOnPositiveButtonClickListener {
                    val totalMin = hour * 60 + minute
                    if (isStart) {
                        scheduleStartMin = totalMin
                    } else {
                        scheduleEndMin = totalMin
                    }
                    updateScheduleButtons(btnStart, btnEnd)
                    saveSchedule()
                }
                show(supportFragmentManager, "time_picker_${if (isStart) "start" else "end"}")
            }
    }

    private fun updateScheduleButtons(btnStart: Button, btnEnd: Button) {
        btnStart.text = if (scheduleStartMin >= 0) {
            String.format("%02d:%02d", scheduleStartMin / 60, scheduleStartMin % 60)
        } else "--:--"

        btnEnd.text = if (scheduleEndMin >= 0) {
            String.format("%02d:%02d", scheduleEndMin / 60, scheduleEndMin % 60)
        } else "--:--"
    }

    private fun updateStatusInfo(limitMinutes: Int, todaySeconds: Int) {
        val tvStatusInfo = findViewById<TextView>(R.id.tvChildDetailStatusInfo)
        val parts = mutableListOf<String>()

        val sStart = scheduleStartMin
        val sEnd = scheduleEndMin
        if (sStart >= 0 && sEnd >= 0) {
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val within = if (sEnd > sStart) now in sStart until sEnd else now >= sStart || now < sEnd
            val h1 = sStart / 60; val m1 = sStart % 60
            val h2 = sEnd / 60; val m2 = sEnd % 60
            val label = if (within) "Dentro del horario" else "Fuera del horario"
            parts.add("$label (%02d:%02d-%02d:%02d)".format(h1, m1, h2, m2))
        }

        if (limitMinutes > 0) {
            val usedMin = todaySeconds / 60
            if (usedMin >= limitMinutes) {
                parts.add("Límite diario agotado")
            } else {
                parts.add("${limitMinutes - usedMin} min disponibles")
            }
        }

        tvStatusInfo.text = parts.joinToString("\n")
        tvStatusInfo.setTextColor(
            if (parts.any { it.startsWith("Fuera") || it.startsWith("Límite") && it.contains("agotado") })
                0xFFFF5252.toInt() else 0xFF10B981.toInt()
        )
    }

    private fun saveSchedule() {
        val start = scheduleStartMin
        val end = scheduleEndMin
        val tvStatus = findViewById<TextView>(R.id.tvChildDetailStatus)
        tvStatus.text = "Guardando horario..."
        syncClient.setChildSchedule(childUsername, start, end) { ok, error ->
            if (ok) {
                tvStatus.text = "Horario guardado"
                val payload = org.json.JSONObject()
                    .put("start_min", start)
                    .put("end_min", end)
                syncClient.sendCommandWithPayload(deviceId, "set_schedule", payload) { _, _ -> }
            } else {
                tvStatus.text = "Error: ${error ?: "conexión"}"
            }
        }
    }

    private fun sendCommand(type: String, tvStatus: TextView) {
        tvStatus.text = "Enviando comando $type..."
        syncClient.sendCommand(deviceId, type) { ok, error ->
            tvStatus.text = if (ok) "Comando $type enviado" else "Error: ${error ?: "conexión"}"
        }
    }
}
