package com.example.ufowatcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

// SharedPreferences キー（MainActivity・MenuActivity からも参照）
const val PREFS_NAME   = "ufo_prefs"
const val KEY_URL      = "url"
const val KEY_INTERVAL = "interval_sec"
const val KEY_IDLE_X   = "idle_x"
const val KEY_IDLE_Y   = "idle_y"

// アニメーション定数
private const val UFO_SIZE     = 80f
private const val WOBBLE_AMP   = 10f
private const val WOBBLE_FREQ  = 0.6
private const val FLY_SPEED    = 1.2
private const val FLY_DURATION = 5.0
private const val TICK_STEP    = 1.0 / 60.0
private const val DRAG_THRESHOLD = 20f

private const val CHANNEL_ID = "ufo_watcher"
private const val NOTIF_ID   = 1

class UfoOverlayService : Service() {

    companion object {
        var instance: UfoOverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var ufoView: UfoView
    private lateinit var layoutParams: WindowManager.LayoutParams

    // スクリーンサイズ
    private var screenW = 0f
    private var screenH = 0f

    // アニメーション状態
    private var tick = 0.0
    private var flying = false
    private var flyT = 0.0
    private var flyElapsed = 0.0
    @Volatile private var alertFlag = false

    // 変化を検出済みで未確認の状態（タップで解除）
    @Volatile var alertState = false

    // アイドル位置（ドラッグで変更・保存）
    private var idleX = 0f
    private var idleY = 0f

    // ドラッグ状態
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartIdleX = 0f
    private var dragStartIdleY = 0f
    private var isDragging = false

    // 現フレームのUFO描画位置（tick() で更新、onDraw で参照）
    private var ufoX = 0f
    private var ufoY = 0f

    // 描画ループ（約60fps）
    private val handler = Handler(Looper.getMainLooper())
    private val drawRunnable = object : Runnable {
        override fun run() {
            updateFrame()
            handler.postDelayed(this, 16L)
        }
    }

    // URLポーリングスレッド
    @Volatile private var watcherStopped = false
    private var watcherThread: Thread? = null
    private var prevHash: String? = null

    var isMonitoring = false
        private set

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // スクリーンサイズ取得
        val pt = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getSize(pt)
        screenW = pt.x.toFloat()
        screenH = pt.y.toFloat()

        // アイドル位置を読み込み（なければ中央）
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        idleX = prefs.getFloat(KEY_IDLE_X, (screenW - UFO_SIZE) / 2f)
        idleY = prefs.getFloat(KEY_IDLE_Y, (screenH - UFO_SIZE) / 2f)
        ufoX = idleX
        ufoY = idleY

        // UFO の小ウィンドウを作成（UFO_SIZE × UFO_SIZE）
        ufoView = UfoView(this)
        layoutParams = WindowManager.LayoutParams(
            UFO_SIZE.toInt(),
            UFO_SIZE.toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ufoX.toInt()
            y = ufoY.toInt()
        }
        windowManager.addView(ufoView, layoutParams)

        startForegroundWithNotification()
        handler.post(drawRunnable)
        startWatcher()
    }

    override fun onDestroy() {
        handler.removeCallbacks(drawRunnable)
        stopWatcher()
        if (::ufoView.isInitialized) windowManager.removeView(ufoView)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── MenuActivity から呼ばれる公開メソッド ─────────────────────────────

    fun toggleMonitoring() {
        if (isMonitoring) stopWatcher() else startWatcher()
    }

    private fun openUrl() {
        val url = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_URL, "") ?: ""
        if (url.isEmpty()) return
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.android.chrome")
        }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    // ── フレーム更新 ──────────────────────────────────────────────────────

    private fun updateFrame() {
        tick += TICK_STEP

        // フライト状態の更新
        if (alertFlag) {
            if (!flying) {
                flying = true
                flyT = 0.0
                flyElapsed = 0.0
            } else {
                flyElapsed += TICK_STEP
                if (flyElapsed >= FLY_DURATION) {
                    flying = false
                    alertFlag = false
                }
            }
        } else {
            flying = false
        }

        // UFO 位置の計算
        if (flying) {
            flyT += TICK_STEP * FLY_SPEED
            val margin = UFO_SIZE
            val cx = (screenW - UFO_SIZE) / 2f
            val cy = (screenH - UFO_SIZE) / 2f
            ufoX = cx + ((cx - margin) * sin(3 * flyT + PI / 2)).toFloat()
            ufoY = cy + ((cy - margin) * sin(2 * flyT)).toFloat()
        } else {
            val dy = (WOBBLE_AMP * sin(2 * PI * WOBBLE_FREQ * tick)).toFloat()
            ufoX = idleX
            ufoY = idleY + dy
        }

        // ウィンドウ位置を更新してUFOを移動
        layoutParams.x = ufoX.toInt()
        layoutParams.y = ufoY.toInt()
        windowManager.updateViewLayout(ufoView, layoutParams)
        ufoView.invalidate()
    }

    // ── URLポーリング ─────────────────────────────────────────────────────

    private fun startWatcher() {
        if (isMonitoring) return
        watcherStopped = false
        prevHash = null
        isMonitoring = true

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, "https://example.com") ?: "https://example.com"
        val intervalSec = prefs.getInt(KEY_INTERVAL, 60).toLong()

        watcherThread = Thread {
            while (!watcherStopped) {
                try {
                    val body = fetchBody(url)
                    val hash = sha256(body)
                    if (prevHash == null) {
                        prevHash = hash
                    } else if (hash != prevHash) {
                        prevHash = hash
                        alertFlag = true
                        alertState = true  // タップで確認するまで保持
                    }
                } catch (_: Exception) {}

                val deadline = System.currentTimeMillis() + intervalSec * 1000L
                while (!watcherStopped && System.currentTimeMillis() < deadline) {
                    Thread.sleep(500)
                }
            }
            isMonitoring = false
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopWatcher() {
        watcherStopped = true
    }

    private fun fetchBody(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "ufo-watcher-android/1.0")
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── 通知・フォアグラウンド起動 ────────────────────────────────────────

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "UFO Watcher", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UFO Watcher 起動中")
            .setContentText("URLの変化をチェックしています")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ── UFO描画View ───────────────────────────────────────────────────────

    inner class UfoView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = UFO_SIZE
            textAlign = Paint.Align.CENTER
        }

        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.drawText("🛸", UFO_SIZE / 2f, UFO_SIZE, paint)
            // 未確認の変化があれば右上に赤バッジを表示
            if (alertState) {
                canvas.drawCircle(UFO_SIZE * 0.82f, UFO_SIZE * 0.18f, UFO_SIZE * 0.16f, badgePaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartIdleX = idleX
                    dragStartIdleY = idleY
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (isDragging || dx * dx + dy * dy > DRAG_THRESHOLD * DRAG_THRESHOLD) {
                        isDragging = true
                        idleX = (dragStartIdleX + dx).coerceIn(0f, screenW - UFO_SIZE)
                        idleY = (dragStartIdleY + dy).coerceIn(0f, screenH - UFO_SIZE)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putFloat(KEY_IDLE_X, idleX)
                            .putFloat(KEY_IDLE_Y, idleY)
                            .apply()
                    } else {
                        alertState = false  // タップ = 確認済み
                        openUrl()
                    }
                    isDragging = false
                }
            }
            return true
        }
    }
}
