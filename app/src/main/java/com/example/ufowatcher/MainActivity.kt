package com.example.ufowatcher

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnSave: Button
    private lateinit var urlField: EditText
    private var savedUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        urlField = findViewById(R.id.editUrl)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnOverlay = findViewById(R.id.btnOverlayPermission)
        btnSave = findViewById(R.id.btnSave)

        // 保存済みURLを表示
        savedUrl = prefs.getString(KEY_URL, "https://example.com") ?: "https://example.com"
        urlField.setText(savedUrl)

        // 変更があるときだけ保存ボタンを有効に
        btnSave.isEnabled = false
        urlField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                btnSave.isEnabled = s.toString().trim() != savedUrl
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

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
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
            )
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
        updateButtons()
    }

    private fun updateButtons() {
        val hasPermission = Settings.canDrawOverlays(this)
        btnOverlay.text = if (hasPermission) "オーバーレイ許可済み" else "オーバーレイを許可"
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
