package com.safekidapp

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.security.MessageDigest

class SettingsActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var tracker: UsageTracker
    private lateinit var mqttManager: MqttManager
    private var dialogShown = false
    private var pendingTrackingStart = false
    private val childStatus = mutableMapOf<String, String>()
    private val statusListener: (String, String) -> Unit = { topic, payload ->
        val childId = topic.removePrefix("safekid/child/").removeSuffix("/status")
        childStatus[childId] = payload
        runOnUiThread { refreshChildrenList() }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)
        tracker = UsageTracker(this)
        mqttManager = MqttManager(this)

        updateUsageInfo()
        setupButtons()
        setupMqtt()
    }

    override fun onResume() {
        super.onResume()
        updateUsageInfo()
        if (!dialogShown && tracker.getDailyLimit() > 0 && tracker.getAccumulatedUsage() >= tracker.getDailyLimit()) {
            dialogShown = true
            showTimeExceededDialog()
        }
    }

    private fun showTimeExceededDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tiempo agotado")
            .setMessage("El límite diario de ${tracker.getLimitMinutes()} minutos se ha alcanzado.\n\n" +
                    "Puedes agregar más tiempo o reiniciar el contador de hoy.")
            .setPositiveButton("Agregar 15 min") { _, _ ->
                val newLimit = tracker.getLimitMinutes() + 15
                tracker.setDailyLimit(newLimit)
                updateUsageInfo()
                Toast.makeText(this, "Límite extendido a $newLimit min", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Reiniciar hoy") { _, _ ->
                tracker.resetDaily()
                updateUsageInfo()
                Toast.makeText(this, "Contador reiniciado", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cerrar", null)
            .show()
    }

    private fun updateUsageInfo() {
        val tvUsage = findViewById<TextView>(R.id.tvUsageInfo)
        val used = tracker.getUsedMinutes()
        val limit = tracker.getLimitMinutes()
        if (limit > 0) {
            tvUsage.text = "$used min usado de $limit min"
        } else {
            tvUsage.text = "$used min usado (sin límite)"
        }
    }

    private fun setupMqtt() {
        val deviceId = mqttManager.getDeviceId()
        findViewById<TextView>(R.id.tvDeviceId).text = deviceId

        findViewById<TextInputEditText>(R.id.etBrokerUrl).setText(mqttManager.getBrokerUrl())

        findViewById<MaterialButton>(R.id.btnSaveBroker).setOnClickListener {
            val url = findViewById<TextInputEditText>(R.id.etBrokerUrl).text?.toString() ?: ""
            if (url.isNotBlank()) {
                mqttManager.setBrokerUrl(url)
                Toast.makeText(this, "Broker guardado", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnPairWithParent).setOnClickListener {
            val code = findViewById<TextInputEditText>(R.id.etPairingCode).text?.toString() ?: ""
            if (code.length != 6) {
                Toast.makeText(this, "Ingresa un código de 6 dígitos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findViewById<TextView>(R.id.tvPairingStatus).text = "Vinculando..."
            mqttManager.pairWithParent(code, "Dispositivo") { success ->
                runOnUiThread {
                    if (success) {
                        findViewById<TextView>(R.id.tvPairingStatus).text = "¡Vinculado con padre!"
                        Toast.makeText(this, "Vinculado exitosamente", Toast.LENGTH_SHORT).show()
                    } else {
                        findViewById<TextView>(R.id.tvPairingStatus).text = "Error de conexión"
                        Toast.makeText(this, "No se pudo conectar al broker", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnGenerateCode).setOnClickListener {
            mqttManager.connect { connected ->
                if (connected) {
                    val code = mqttManager.generatePairingCode()
                    runOnUiThread {
                        findViewById<TextView>(R.id.tvGeneratedCode).text = code
                        Toast.makeText(this, "Código: $code", Toast.LENGTH_LONG).show()
                    }
                    mqttManager.listenForPairing { childId, childName ->
                        runOnUiThread {
                            Toast.makeText(this, "Hijo vinculado: $childName ($childId)", Toast.LENGTH_LONG).show()
                            refreshChildrenList()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "No se pudo conectar al broker", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        refreshChildrenList()
    }

    private fun refreshChildrenList() {
        val container = findViewById<LinearLayout>(R.id.llChildrenList)
        container.removeAllViews()

        val children = mqttManager.getLinkedChildren()
        if (children.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Ningún hijo vinculado aún"
                setTextColor(0xFF666666.toInt())
                textSize = 14f
            }
            container.addView(tv)
            return
        }

        mqttManager.removeStatusListener(statusListener)
        mqttManager.addStatusListener(statusListener)
        mqttManager.connect { connected ->
            if (connected) {
                for (childId in children) {
                    mqttManager.subscribe("safekid/child/$childId/status")
                }
            }
        }

        for (childId in children) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                setBackgroundColor(0xFFF5F5F5.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
            }

            val tvId = TextView(this).apply {
                text = "ID: $childId"
                setTextColor(0xFF333333.toInt())
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            card.addView(tvId)

            val tvStatus = TextView(this).apply {
                id = View.generateViewId()
                text = formatChildStatus(childId)
                setTextColor(0xFF666666.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 8) }
            }
            card.addView(tvStatus)

            val btnSetLimit = MaterialButton(this).apply {
                text = "Cambiar límite"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF6200EE.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 4) }
                setOnClickListener {
                    showSetLimitDialog(childId)
                }
            }
            card.addView(btnSetLimit)

            val btnAddTime = MaterialButton(this).apply {
                text = "Agregar tiempo extra"
                setBackgroundColor(0xFF6200EE.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
                setOnClickListener {
                    showAddTimeDialog(childId)
                }
            }
            card.addView(btnAddTime)

            val btnBlock = MaterialButton(this).apply {
                text = "Bloquear ahora"
                setBackgroundColor(0xFFD32F2F.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
                setOnClickListener {
                    sendCommand(childId, """{"action":"block"}""")
                    Toast.makeText(this@SettingsActivity, "Comando de bloqueo enviado", Toast.LENGTH_SHORT).show()
                }
            }
            card.addView(btnBlock)

            val btnUnblock = MaterialButton(this).apply {
                text = "Desbloquear"
                setBackgroundColor(0xFF6200EE.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
                setOnClickListener {
                    sendCommand(childId, """{"action":"unblock"}""")
                    Toast.makeText(this@SettingsActivity, "Comando de desbloqueo enviado", Toast.LENGTH_SHORT).show()
                }
            }
            card.addView(btnUnblock)

            val btnStartTracking = MaterialButton(this).apply {
                text = "Iniciar control de tiempo"
                setBackgroundColor(0xFF6200EE.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
                setOnClickListener {
                    sendCommand(childId, """{"action":"start_tracking"}""")
                    Toast.makeText(this@SettingsActivity, "Comando iniciar control enviado", Toast.LENGTH_SHORT).show()
                }
            }
            card.addView(btnStartTracking)

            val btnStopTracking = MaterialButton(this).apply {
                text = "Detener control de tiempo"
                setBackgroundColor(0xFFD32F2F.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
                setOnClickListener {
                    sendCommand(childId, """{"action":"stop_tracking"}""")
                    Toast.makeText(this@SettingsActivity, "Comando detener control enviado", Toast.LENGTH_SHORT).show()
                }
            }
            card.addView(btnStopTracking)

            container.addView(card)
        }
    }

    private fun formatChildStatus(childId: String): String {
        val raw = childStatus[childId] ?: return "Esperando datos..."
        return try {
            val json = org.json.JSONObject(raw)
            val used = json.optInt("used", 0)
            val limit = json.optInt("limit", 0)
            val remaining = json.optInt("remaining", 0)
            val locked = json.optBoolean("locked", false)
            val status = if (locked) "BLOQUEADO" else "Desbloqueado"
            "Usado: ${used}min | Límite: ${limit}min | Restante: ${remaining}min | $status"
        } catch (_: Exception) {
            raw
        }
    }

    private fun showSetLimitDialog(childId: String) {
        val input = TextInputEditText(this).apply {
            hint = "Minutos"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 16, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Establecer límite")
            .setMessage("Nuevo límite en minutos para $childId")
            .setView(input)
            .setPositiveButton("Enviar") { _, _ ->
                val mins = input.text?.toString()?.toIntOrNull()
                if (mins != null && mins > 0) {
                    sendCommand(childId, """{"action":"set_limit","value":$mins}""")
                    Toast.makeText(this, "Límite enviado: $mins min", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAddTimeDialog(childId: String) {
        val input = TextInputEditText(this).apply {
            hint = "Minutos extra"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 16, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Agregar tiempo")
            .setMessage("Minutos extra para $childId")
            .setView(input)
            .setPositiveButton("Enviar") { _, _ ->
                val mins = input.text?.toString()?.toIntOrNull()
                if (mins != null && mins > 0) {
                    sendCommand(childId, """{"action":"add_time","value":$mins}""")
                    Toast.makeText(this, "Tiempo agregado: $mins min", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun sendCommand(childId: String, payload: String) {
        mqttManager.connect { connected ->
            if (connected) {
                mqttManager.publish("safekid/child/$childId/commands", payload)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Error de conexión MQTT", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupButtons() {
        findViewById<MaterialButton>(R.id.btnSavePassword).setOnClickListener {
            val password = findViewById<TextInputEditText>(R.id.etPassword).text?.toString() ?: ""
            val confirm = findViewById<TextInputEditText>(R.id.etConfirmPassword).text?.toString() ?: ""

            when {
                password.isEmpty() -> Toast.makeText(this, "La contraseña no puede estar vacía", Toast.LENGTH_SHORT).show()
                password != confirm -> Toast.makeText(this, "Las contraseñas no coinciden", Toast.LENGTH_SHORT).show()
                password.length < 4 -> Toast.makeText(this, "La contraseña debe tener al menos 4 caracteres", Toast.LENGTH_SHORT).show()
                else -> {
                    savePassword(password)
                    Toast.makeText(this, "Contraseña guardada", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnActivateAdmin).setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Necesario para activar el control parental que bloquea el teléfono")
            }
            startActivity(intent)
        }

        findViewById<MaterialButton>(R.id.btnDeactivateAdmin).setOnClickListener {
            try {
                dpm.removeActiveAdmin(adminComponent)
                Toast.makeText(this, "Administrador desactivado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnSetTimeLimit).setOnClickListener {
            val text = findViewById<TextInputEditText>(R.id.etTimeLimit).text?.toString() ?: ""
            val minutes = text.toIntOrNull()
            if (minutes == null || minutes <= 0) {
                Toast.makeText(this, "Ingresa un número válido de minutos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tracker.setDailyLimit(minutes)
            updateUsageInfo()
            Toast.makeText(this, "Límite establecido: $minutes min por día", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnResetUsage).setOnClickListener {
            tracker.resetDaily()
            updateUsageInfo()
            Toast.makeText(this, "Uso de hoy reiniciado", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnStartTracking).setOnClickListener {
            if (tracker.getDailyLimit() <= 0) {
                Toast.makeText(this, "Primero establece un límite de tiempo", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingTrackingStart = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_CODE)
                return@setOnClickListener
            }
            startTracking()
        }

        findViewById<MaterialButton>(R.id.btnStopTracking).setOnClickListener {
            tracker.setTrackingEnabled(false)
            stopUsageService()
            Toast.makeText(this, "Control de tiempo detenido", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnStartKiosk).setOnClickListener {
            startKioskMode()
        }

        findViewById<MaterialButton>(R.id.btnExitAdmin).setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }

        findViewById<MaterialButton>(R.id.btnOverlayPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE && pendingTrackingStart) {
            pendingTrackingStart = false
            startTracking()
        }
    }

    private fun startTracking() {
        if (tracker.getDailyLimit() <= 0) {
            Toast.makeText(this, "Primero establece un límite de tiempo", Toast.LENGTH_LONG).show()
            return
        }
        tracker.setTrackingEnabled(true)
        tracker.resetDaily()
        startUsageService()
        updateUsageInfo()
        Toast.makeText(this, "Control de tiempo iniciado", Toast.LENGTH_SHORT).show()
    }

    private fun startUsageService() {
        val intent = Intent(this, UsageService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopUsageService() {
        stopService(Intent(this, UsageService::class.java))
    }

    private fun startKioskMode() {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)

        if (!dpm.isAdminActive(adminComponent)) {
            Toast.makeText(this, "Primero activa el administrador del dispositivo", Toast.LENGTH_LONG).show()
            return
        }

        val hash = prefs.getString("password_hash", null)
        if (hash.isNullOrEmpty()) {
            Toast.makeText(this, "Primero establece una contraseña", Toast.LENGTH_LONG).show()
            return
        }

        try {
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
        } catch (_: SecurityException) {
        }

        try {
            startLockTask()
        } catch (e: SecurityException) {
            showAdbDialog()
            return
        }

        prefs.edit().putBoolean("kiosk_active", true).apply()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    private fun showAdbDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso requerido")
            .setMessage("Tu dispositivo necesita una autorización.\n\n" +
                    "Conecta el celular al PC por USB (con depuración USB activada) y ejecuta:\n\n" +
                    "adb shell settings put global device_policy_lock_task_packages com.safekidapp\n\n" +
                    "Luego presiona 'Reintentar'.")
            .setPositiveButton("Reintentar") { _, _ -> startKioskMode() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun savePassword(password: String) {
        val hash = hashPassword(password)
        getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("password_hash", hash)
            .apply()
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
