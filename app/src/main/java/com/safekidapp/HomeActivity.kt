package com.safekidapp

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "SafeKid cargando..."
        tv.textSize = 24f
        tv.setPadding(32, 32, 32, 32)
        setContentView(tv)
    }
}
