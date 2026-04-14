package com.example.ufowatcher

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnSave: Button
    private lateinit var urlField: EditText
    private var savedUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlField     = findViewById(R.id.editUrl)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnOverlay   = findViewById(R.id.btnOverlayPermission)
        btnSave      = findViewById(R.id.btnSave)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        savedUrl = prefs.getString(KEY_URL, "https://example.com") ?: "https://example.com"
        urlField.setText(savedUrl)

        btnSave.isEnabled = false
        urlField.doAfterTextChanged { s ->
            btnSave.isEnabled = s.toString().trim() != savedUrl
        }

        btnSave.setOnClickListener {
            val url = urlField.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_URL, url).apply()
            savedUrl = url
            btnSave.isEnabled = false
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        }

        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
        }

        btnStartStop.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "先にオーバーレイを許可してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (UfoOverlayService.instance != null) {
                stopService(Intent(this, UfoOverlayService::class.java))
                setStartStopButton(running = false)
            } else {
                ContextCompat.startForegroundService(this, Intent(this, UfoOverlayService::class.java))
                setStartStopButton(running = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val hasPermission = Settings.canDrawOverlays(this)
        btnOverlay.text      = if (hasPermission) "オーバーレイ許可済み" else "オーバーレイを許可"
        btnOverlay.isEnabled = !hasPermission
        setStartStopButton(running = UfoOverlayService.instance != null)
        btnStartStop.isEnabled = hasPermission
    }

    private fun setStartStopButton(running: Boolean) {
        btnStartStop.text = if (running) "OFF" else "ON"
        btnStartStop.backgroundTintList = ColorStateList.valueOf(
            if (running) Color.parseColor("#F44336") else Color.parseColor("#2196F3")
        )
    }
}
