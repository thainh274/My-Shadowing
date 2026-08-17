package com.enn.chi.shadow.myshadowing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStartCamera)
        btnStart.setOnClickListener {
            startActivity(Intent(this, ShadowActivity::class.java))
        }
    }
}
