package com.example.ufowatcher

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val urlField = findViewById<EditText>(R.id.editUrl)
        val intervalField = findViewById<EditText>(R.id.editInterval)
        val saveButton = findViewById<Button>(R.id.btnSave)
        val setWallpaperButton = findViewById<Button>(R.id.btnSetWallpaper)

        // 保存済みの設定値をフィールドに表示
        urlField.setText(prefs.getString(KEY_URL, "https://example.com"))
        intervalField.setText(prefs.getInt(KEY_INTERVAL, 60).toString())

        // 「設定を保存」: SharedPreferences に書き込む
        saveButton.setOnClickListener {
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

            Toast.makeText(this, "保存しました。壁紙を再設定すると反映されます", Toast.LENGTH_LONG).show()
        }

        // 「ライブ壁紙として設定」: システムの壁紙選択画面を直接このサービスで開く
        setWallpaperButton.setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, UfoWallpaperService::class.java)
                )
            }
            startActivity(intent)
        }
    }
}
