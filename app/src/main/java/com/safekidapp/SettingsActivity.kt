package com.safekidapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        setupButtons()
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

        findViewById<MaterialButton>(R.id.btnStartKiosk).setOnClickListener {
            startKioskMode()
        }

        findViewById<MaterialButton>(R.id.btnExitAdmin).setOnClickListener {
            val tokenManager = TokenManager(this)
            val target = when {
                tokenManager.isLoggedIn() && tokenManager.isParent() -> ParentDashboardActivity::class.java
                tokenManager.isLoggedIn() && tokenManager.isChild() -> ChildDashboardActivity::class.java
                else -> HomeActivity::class.java
            }
            startActivity(Intent(this, target).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }

        setupCloudSection()

        findViewById<MaterialButton>(R.id.btnOverlayPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        }

        findViewById<MaterialButton>(R.id.btnDeleteAccount).setOnClickListener {
            val tm = TokenManager(this)
            if (!tm.isLoggedIn()) {
                Toast.makeText(this, "No hay sesión iniciada", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Eliminar cuenta")
                .setMessage("¿Estás seguro? Se eliminarán todos tus datos y no se podrá recuperar.")
                .setPositiveButton("Eliminar") { _, _ ->
                    val sc = SyncClient(this)
                    sc.deleteAccount { ok, error ->
                        if (ok) {
                            tm.logout()
                            startActivity(Intent(this, LoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                            finish()
                            Toast.makeText(this, "Cuenta eliminada", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Error al eliminar: ${error ?: "conexión"}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun setupCloudSection() {
        val syncClient = SyncClient(this)
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)

        val etUrl = findViewById<TextInputEditText>(R.id.etServerUrl)
        val etName = findViewById<TextInputEditText>(R.id.etDeviceName)
        val tvStatus = findViewById<TextView>(R.id.tvCloudStatus)

        etUrl.setText(prefs.getString("server_url", ""))
        etName.setText(syncClient.getDeviceName() ?: "")

        updateCloudStatus(tvStatus, syncClient)

        findViewById<MaterialButton>(R.id.btnCloudRegister).setOnClickListener {
            val url = etUrl.text?.toString()?.trim() ?: ""
            val name = etName.text?.toString()?.trim() ?: ""

            if (url.isEmpty()) {
                Toast.makeText(this, "Ingresa la URL del servidor", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (name.isEmpty()) {
                Toast.makeText(this, "Ingresa un nombre para el dispositivo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putString("server_url", url.trimEnd('/')).apply()

            doRegister(tvStatus, syncClient, name)
        }

        findViewById<MaterialButton>(R.id.btnCloudSync).setOnClickListener {
            if (!syncClient.isConfigured()) {
                Toast.makeText(this, "Primero registra el dispositivo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvStatus.text = "◌ Sincronizando..."
            tvStatus.setTextColor(0xFFFFA500.toInt())

            val tracker = UsageTracker(this)
            val used = tracker.getAccumulatedUsage().toInt() / 1000

            syncClient.syncToday(used) { success, error ->
                if (success) {
                    tvStatus.text = "● Sincronizado"
                    tvStatus.setTextColor(0xFF4CAF50.toInt())
                    Toast.makeText(this, "Datos sincronizados", Toast.LENGTH_SHORT).show()
                } else {
                    tvStatus.text = "✕ Error: ${error ?: "desconocido"}"
                    tvStatus.setTextColor(0xFFFF5252.toInt())
                }
            }
        }
    }

    private fun updateCloudStatus(tvStatus: TextView, client: SyncClient) {
        if (client.isConfigured()) {
            tvStatus.text = "● Conectado (${client.getDeviceName() ?: "sin nombre"})"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvStatus.text = "◎ Desconectado"
            tvStatus.setTextColor(0xFF999999.toInt())
        }
    }

    private fun doRegister(tvStatus: TextView, client: SyncClient, deviceName: String) {
        tvStatus.text = "◌ Registrando..."
        tvStatus.setTextColor(0xFFFFA500.toInt())

        client.registerDevice(deviceName) { success, error ->
            if (success) {
                tvStatus.text = "● Conectado"
                tvStatus.setTextColor(0xFF4CAF50.toInt())
                Toast.makeText(this, "Dispositivo registrado correctamente", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = "✕ Error: ${error ?: "desconocido"}"
                tvStatus.setTextColor(0xFFFF5252.toInt())
            }
        }
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
        } catch (_: SecurityException) {}

        try {
            startLockTask()
        } catch (e: SecurityException) {
            showAdbDialog()
            return
        }

        prefs.edit().putBoolean("kiosk_active", true).apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
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
