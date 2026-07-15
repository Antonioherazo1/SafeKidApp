package com.safekidapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "SafeKid cargando..."
        tv.textSize = 24f
        tv.setPadding(32, 32, 32, 32)
        setContentView(tv)
    }
}
