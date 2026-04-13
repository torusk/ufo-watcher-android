package com.example.ufowatcher

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * UFOをタップしたときに表示されるメニュー画面。
 * 透明テーマで起動してAlertDialogだけを表示し、選択後すぐ finish() する。
 */
class MenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val engine = UfoWallpaperService.engine

        // 監視状態に応じてメニュー項目を動的に切り替える
        val monitorLabel = if (engine?.isMonitoring == true) "監視を停止" else "監視を再開"

        val items = arrayOf(
            "リンクを開く",
            "URLを変更",
            "飛行を停止",
            monitorLabel,
        )

        AlertDialog.Builder(this)
            .setTitle("🛸 UFO Watcher")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openUrl()
                    1 -> changeUrl()
                    2 -> { engine?.stopFlight(); finish() }
                    3 -> { engine?.toggleMonitoring(); finish() }
                }
            }
            .setOnCancelListener { finish() }  // 背景タップやBackキーで閉じる
            .show()
    }

    // ── リンクを開く ──────────────────────────────────────────────────────
    private fun openUrl() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, "") ?: ""
        if (url.isEmpty()) {
            Toast.makeText(this, "URLが設定されていません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Chrome で開く。未インストールならデフォルトブラウザにフォールバック
        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.android.chrome")
        }
        try {
            startActivity(chromeIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        finish()
    }

    // ── URLを変更（インラインダイアログ） ───────────────────────────────
    private fun changeUrl() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentUrl = prefs.getString(KEY_URL, "") ?: ""

        val field = EditText(this).apply {
            setText(currentUrl)
            hint = "https://example.com"
            // テキスト全選択して入力しやすくする
            post { selectAll() }
        }

        AlertDialog.Builder(this)
            .setTitle("URLを変更")
            .setMessage("次のポーリングから反映されます")
            .setView(field)
            .setPositiveButton("変更") { _, _ ->
                val newUrl = field.text.toString().trim()
                if (newUrl.isNotEmpty() && newUrl != currentUrl) {
                    prefs.edit().putString(KEY_URL, newUrl).apply()
                    Toast.makeText(this, "URLを変更しました", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setNegativeButton("キャンセル") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
