package com.safekidapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

class ParentDashboardActivity : AppCompatActivity() {

    private lateinit var syncClient: SyncClient
    private lateinit var tokenManager: TokenManager
    private val handler = Handler(Looper.getMainLooper())
    private var children = listOf<ChildInfo>()

    private val lvChildren: ListView get() = findViewById(R.id.lvChildren)
    private val tvStatus: TextView get() = findViewById(R.id.tvParentStatus)
    private val tvUsername: TextView get() = findViewById(R.id.tvParentUsername)
    private val btnLogout: Button get() = findViewById(R.id.btnLogoutParent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_dashboard)

        syncClient = SyncClient(this)
        tokenManager = TokenManager(this)

        tvUsername.text = "Bienvenido, ${tokenManager.getUsername() ?: "Padre"}"
        loadParentCode()

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
            intent.putExtra("username", child.username)
            intent.putExtra("api_key", child.apiKey)
            intent.putExtra("daily_limit", child.dailyLimitMinutes)
            intent.putExtra("today_seconds", child.todaySeconds)
            intent.putExtra("schedule_start_min", child.scheduleStartMin)
            intent.putExtra("schedule_end_min", child.scheduleEndMin)
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

    private fun loadParentCode() {
        syncClient.getParentCode { code ->
            runOnUiThread {
                if (code != null) {
                    tvUsername.text = "${tokenManager.getUsername() ?: "Padre"} • Código: $code"
                }
            }
        }
    }

    private fun loadChildren() {
        tvStatus.text = "Cargando hijos..."
        syncClient.getChildren { list, error ->
            if (list != null) {
                children = list
                if (list.isEmpty()) {
                    tvStatus.text = "Tus hijos se vincularán cuando se registren con tu código"
                    lvChildren.adapter = null
                } else {
                    tvStatus.text = "${list.size} hijo(s) vinculado(s)"
                    lvChildren.adapter = ChildCardAdapter(list)
                }
            } else {
                tvStatus.text = "Error: ${error ?: "conexión"}"
            }
        }
    }

    private val avatarColors = intArrayOf(
        0xFF7C4DFF.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(),
        0xFFEF4444.toInt(), 0xFF3B82F6.toInt(), 0xFFEC4899.toInt()
    )

    inner class ChildCardAdapter(private val items: List<ChildInfo>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@ParentDashboardActivity)
                .inflate(R.layout.item_child_card, parent, false)
            val child = items[pos]
            val name = child.name.ifBlank { child.username }

            val tvAvatar = v.findViewById<TextView>(R.id.tvChildAvatar)
            tvAvatar.text = name.take(1).uppercase()
            tvAvatar.setBackgroundColor(avatarColors[pos % avatarColors.size])

            v.findViewById<TextView>(R.id.tvChildName).text = name
            v.findViewById<TextView>(R.id.tvChildUsage).text =
                "${child.todaySeconds / 60} min usado de ${child.dailyLimitMinutes} min"

            val tvStatus = v.findViewById<TextView>(R.id.tvChildStatus)
            val parts = mutableListOf<String>()

            val sStart = child.scheduleStartMin
            val sEnd = child.scheduleEndMin
            if (sStart >= 0 && sEnd >= 0) {
                val cal = Calendar.getInstance()
                val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val within = if (sEnd > sStart) now in sStart until sEnd else now >= sStart || now < sEnd
                val h1 = sStart / 60; val m1 = sStart % 60
                val h2 = sEnd / 60; val m2 = sEnd % 60
                val label = if (within) "Dentro del horario" else "Fuera del horario"
                parts.add("$label (%02d:%02d-%02d:%02d)".format(h1, m1, h2, m2))
            }

            val limitMin = child.dailyLimitMinutes
            if (limitMin > 0) {
                val usedMin = child.todaySeconds / 60
                if (usedMin >= limitMin) {
                    parts.add("Límite agotado")
                } else {
                    parts.add("${limitMin - usedMin} min restantes")
                }
            }

            tvStatus.text = parts.joinToString(" • ")
            tvStatus.setTextColor(
                if (parts.any { it.startsWith("Fuera") || it.startsWith("Límite agotado") })
                    0xFFFF5252.toInt() else 0xFF10B981.toInt()
            )
            return v
        }
    }
}
