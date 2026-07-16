package com.safekidapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class ParentDashboardActivity : AppCompatActivity() {

    private lateinit var syncClient: SyncClient
    private lateinit var tokenManager: TokenManager
    private val handler = Handler(Looper.getMainLooper())
    private var children = listOf<ChildInfo>()

    private val lvChildren: ListView get() = findViewById(R.id.lvChildren)
    private val tvStatus: TextView get() = findViewById(R.id.tvParentStatus)
    private val tvUsername: TextView get() = findViewById(R.id.tvParentUsername)
    private val etLinkChild: TextInputEditText get() = findViewById(R.id.etLinkChild)
    private val btnLink: Button get() = findViewById(R.id.btnLinkChild)
    private val btnLogout: Button get() = findViewById(R.id.btnLogoutParent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_dashboard)

        syncClient = SyncClient(this)
        tokenManager = TokenManager(this)

        tvUsername.text = "Bienvenido, ${tokenManager.getUsername() ?: "Padre"}"

        btnLink.setOnClickListener { linkChild() }

        findViewById<Button>(R.id.btnParentSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnLogout.setOnClickListener {
            tokenManager.logout()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        lvChildren.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val child = children[position]
            val intent = Intent(this, ChildDetailActivity::class.java)
            intent.putExtra("device_id", child.deviceId)
            intent.putExtra("name", child.name)
            intent.putExtra("api_key", child.apiKey)
            intent.putExtra("daily_limit", child.dailyLimitMinutes)
            intent.putExtra("today_seconds", child.todaySeconds)
            startActivity(intent)
        }

        loadChildren()
    }

    override fun onResume() {
        super.onResume()
        if (!tokenManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        loadChildren()
    }

    private fun loadChildren() {
        tvStatus.text = "Cargando hijos..."
        syncClient.getChildren { list, error ->
            if (list != null) {
                children = list
                if (list.isEmpty()) {
                    tvStatus.text = "No tienes hijos vinculados. Ingresa el username del hijo y presiona Vincular."
                    lvChildren.adapter = null
                } else {
                    tvStatus.text = "${list.size} hijo(s) vinculado(s)"
                    lvChildren.adapter = ArrayAdapter(
                        this@ParentDashboardActivity,
                        android.R.layout.simple_list_item_1,
                        list.map { "${it.name} - ${it.todaySeconds / 60} min de ${it.dailyLimitMinutes} min" }
                    )
                }
            } else {
                tvStatus.text = "Error: ${error ?: "conexión"}"
            }
        }
    }

    private fun linkChild() {
        val childUsername = etLinkChild.text.toString().trim()
        if (childUsername.isBlank()) {
            Toast.makeText(this, "Ingresa el username del hijo", Toast.LENGTH_SHORT).show()
            return
        }

        btnLink.isEnabled = false
        syncClient.linkChild(childUsername) { ok, error ->
            btnLink.isEnabled = true
            if (ok) {
                etLinkChild.text?.clear()
                Toast.makeText(this, "Hijo vinculado correctamente", Toast.LENGTH_SHORT).show()
                loadChildren()
            } else {
                Toast.makeText(this, error ?: "Error al vincular", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
