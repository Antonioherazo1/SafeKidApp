package com.safekidapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private var tapCount = 0
    private var tapTimer: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableFullScreen()
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("kiosk_active", false)) {
            startLockTask()
        }

        findViewById<View>(R.id.blockImage).setOnClickListener {
            handleTap()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullScreen()
        }
    }

    private fun enableFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun handleTap() {
        tapCount++
        if (tapTimer == null) {
            tapTimer = Handler(Looper.getMainLooper())
            tapTimer?.postDelayed({
                tapCount = 0
                tapTimer = null
            }, 2000)
        }

        if (tapCount >= 5) {
            tapCount = 0
            tapTimer?.removeCallbacksAndMessages(null)
            tapTimer = null
            showPasswordDialog()
        }
    }

    private fun showPasswordDialog() {
        val inputLayout = TextInputLayout(this)
        val input = TextInputEditText(inputLayout)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        inputLayout.addView(input)
        inputLayout.setPadding(48, 16, 48, 16)

        AlertDialog.Builder(this)
            .setTitle("Desbloquear dispositivo")
            .setMessage("Ingresa la contraseña de adulto")
            .setView(inputLayout)
            .setPositiveButton("Desbloquear") { _, _ ->
                val password = input.text?.toString() ?: ""
                if (checkPassword(password)) {
                    unlockDevice()
                } else {
                    Toast.makeText(this, "Contraseña incorrecta", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .setCancelable(false)
            .show()
    }

    private fun checkPassword(password: String): Boolean {
        val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
        val storedHash = prefs.getString("password_hash", null) ?: return false
        val inputHash = hashPassword(password)
        return storedHash == inputHash
    }

    private fun unlockDevice() {
        try {
            val prefs = getSharedPreferences("safe_kid_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("kiosk_active", false).apply()

            stopLockTask()

            val intent = Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al desbloquear: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
