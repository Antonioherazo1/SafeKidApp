package com.safekidapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.security.MessageDigest

class SettingsActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var tracker: UsageTracker
    private var dialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)
        tracker = UsageTracker(this)

        updateUsageInfo()
        setupButtons()
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
            tracker.setTrackingEnabled(true)
            tracker.resetDaily()
            startUsageService()
            Toast.makeText(this, "Control de tiempo iniciado", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnStopTracking).setOnClickListener {
            tracker.setTrackingEnabled(false)
            stopUsageService()
            Toast.makeText(this, "Control de tiempo detenido", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnStartKiosk).setOnClickListener {
            startKioskMode()
        }
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
