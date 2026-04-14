package com.example.ufowatcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var btnOverlay: Button
    private lateinit var btnStartStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val urlField = findViewById<EditText>(R.id.editUrl)
        val intervalField = findViewById<EditText>(R.id.editInterval)
        btnOverlay = findViewById(R.id.btnOverlayPermission)
        btnStartStop = findViewById(R.id.btnStartStop)

        // 保存済みの設定値をフィールドに表示
        urlField.setText(prefs.getString(KEY_URL, "https://example.com"))
        intervalField.setText(prefs.getInt(KEY_INTERVAL, 60).toString())

        // 「設定を保存」
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val url = urlField.text.toString().trim()
            val interval = intervalField.text.toString().toIntOrNull() ?: 60

            if (url.isEmpty()) {
                Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString(KEY_URL, url)
                .putInt(KEY_INTERVAL, interval)
                .apply()

            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        }

        // 「オーバーレイを許可」: 権限設定画面を開く
        btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
            )
        }

        // 「監視を開始 / 停止」
        btnStartStop.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "先にオーバーレイを許可してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (UfoOverlayService.instance != null) {
                stopService(Intent(this, UfoOverlayService::class.java))
            } else {
                ContextCompat.startForegroundService(this, Intent(this, UfoOverlayService::class.java))
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

        val running = UfoOverlayService.instance != null
        btnStartStop.text = if (running) "監視を停止" else "監視を開始"
        btnStartStop.isEnabled = hasPermission
    }
}
