package com.example.ufowatcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
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
import kotlin.math.cos
import kotlin.math.sin

// SharedPreferences キー
const val PREFS_NAME = "ufo_prefs"
const val KEY_URL    = "url"
const val KEY_IDLE_X = "idle_x"
const val KEY_IDLE_Y = "idle_y"

private const val UFO_SIZE       = 80f
private const val WOBBLE_AMP     = 10f
private const val WOBBLE_FREQ    = 0.6
private const val FLY_DURATION   = 3.0
private val       FLY_SPEED      = 2.0 * PI / FLY_DURATION  // 3秒でちょうど一周
private const val TICK_STEP      = 1.0 / 60.0
private const val DRAG_THRESHOLD = 20f
private const val POLL_INTERVAL  = 60_000L  // ポーリング間隔（ms）

private const val CHANNEL_ID = "ufo_watcher"
private const val NOTIF_ID   = 1

class UfoOverlayService : Service() {

    companion object {
        var instance: UfoOverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var ufoView: UfoView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var screenW = 0f
    private var screenH = 0f

    // アニメーション状態（すべてメインスレッドから操作）
    private var tick       = 0.0
    private var flying     = false
    private var flyT       = 0.0
    private var flyElapsed = 0.0

    // 未確認の変化あり（タップで解除）
    var alertState = false

    // アイドル位置
    private var idleX = 0f
    private var idleY = 0f

    // ドラッグ状態
    private var dragStartRawX  = 0f
    private var dragStartRawY  = 0f
    private var dragStartIdleX = 0f
    private var dragStartIdleY = 0f
    private var isDragging     = false

    // 描画ループ（飛行中60fps・アイドル時10fps）
    private val handler = Handler(Looper.getMainLooper())
    private val drawRunnable = object : Runnable {
        override fun run() {
            updateFrame()
            handler.postDelayed(this, if (flying) 16L else 100L)
        }
    }

    // ポーリングスレッド
    @Volatile private var watcherStopped = false
    @Volatile private var isMonitoring   = false
    private var prevHash: String?        = null

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dm = resources.displayMetrics
        screenW = dm.widthPixels.toFloat()
        screenH = dm.heightPixels.toFloat()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        idleX = prefs.getFloat(KEY_IDLE_X, (screenW - UFO_SIZE) / 2f)
        idleY = prefs.getFloat(KEY_IDLE_Y, (screenH - UFO_SIZE) / 2f)

        ufoView = UfoView(this)
        layoutParams = WindowManager.LayoutParams(
            UFO_SIZE.toInt(), UFO_SIZE.toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = idleX.toInt()
            y = idleY.toInt()
        }
        windowManager.addView(ufoView, layoutParams)

        startForegroundWithNotification()
        handler.post(drawRunnable)
        startWatcher()
    }

    override fun onDestroy() {
        handler.removeCallbacks(drawRunnable)
        watcherStopped = true
        if (::ufoView.isInitialized) windowManager.removeView(ufoView)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── フライト開始（ポーリングスレッドから handler.post で呼ばれる） ───────

    private fun startFlight() {
        alertState = true
        flying     = true
        flyT       = 0.0
        flyElapsed = 0.0
    }

    // ── 描画フレーム ──────────────────────────────────────────────────────────

    private fun updateFrame() {
        tick += TICK_STEP

        if (flying) {
            flyElapsed += TICK_STEP
            if (flyElapsed >= FLY_DURATION) flying = false
        }

        if (flying) {
            flyT += TICK_STEP * FLY_SPEED
            val margin = UFO_SIZE * 1.5f
            ufoX = (screenW - UFO_SIZE) / 2f + ((screenW / 2f - margin) * cos(flyT)).toFloat()
            ufoY = (screenH - UFO_SIZE) / 2f + ((screenH / 2f - margin) * sin(flyT)).toFloat()
        } else {
            ufoX = idleX
            ufoY = idleY + (WOBBLE_AMP * sin(2 * PI * WOBBLE_FREQ * tick)).toFloat()
        }

        layoutParams.x = ufoX.toInt()
        layoutParams.y = ufoY.toInt()
        windowManager.updateViewLayout(ufoView, layoutParams)
        ufoView.invalidate()
    }

    private var ufoX = 0f
    private var ufoY = 0f

    // ── URLポーリング ─────────────────────────────────────────────────────────

    private fun startWatcher() {
        if (isMonitoring) return
        watcherStopped = false
        prevHash       = null
        isMonitoring   = true

        val url = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_URL, "https://example.com") ?: "https://example.com"

        Thread {
            while (!watcherStopped) {
                try {
                    val hash = sha256(fetchBody(url))
                    if (prevHash == null) {
                        prevHash = hash
                    } else if (hash != prevHash) {
                        prevHash = hash
                        handler.post { startFlight() }
                    }
                } catch (_: Exception) {}

                val deadline = System.currentTimeMillis() + POLL_INTERVAL
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

    private fun fetchBody(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 15_000
        conn.setRequestProperty("User-Agent", "ufo-watcher-android/1.0")
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ── URL を開く ────────────────────────────────────────────────────────────

    private fun openUrl() {
        val url = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_URL, "") ?: ""
        if (url.isEmpty()) return
        val uri = Uri.parse(url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.android.chrome")
            })
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    // ── 通知 ──────────────────────────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "UFO Watcher", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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

    // ── UFO描画View ───────────────────────────────────────────────────────────

    inner class UfoView(context: Context) : View(context) {

        private val ufoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize  = UFO_SIZE
            textAlign = Paint.Align.CENTER
        }
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.drawText("🛸", UFO_SIZE / 2f, UFO_SIZE, ufoPaint)
            if (alertState) {
                canvas.drawCircle(UFO_SIZE * 0.82f, UFO_SIZE * 0.18f, UFO_SIZE * 0.16f, badgePaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX  = event.rawX
                    dragStartRawY  = event.rawY
                    dragStartIdleX = idleX
                    dragStartIdleY = idleY
                    isDragging     = false
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
                        alertState = false
                        openUrl()
                    }
                    isDragging = false
                }
            }
            return true
        }
    }
}
