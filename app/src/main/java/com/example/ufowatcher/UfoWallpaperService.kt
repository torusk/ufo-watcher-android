package com.example.ufowatcher

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

// SharedPreferences のキー（MainActivity・MenuActivity からも参照する）
const val PREFS_NAME = "ufo_prefs"
const val KEY_URL = "url"
const val KEY_INTERVAL = "interval_sec"
private const val KEY_IDLE_X = "idle_x"  // ユーザーが設定したアイドル位置X
private const val KEY_IDLE_Y = "idle_y"  // ユーザーが設定したアイドル位置Y

// アニメーション定数（inner class に companion object は置けないのでトップレベルに定義）
private const val UFO_SIZE = 80f          // UFO絵文字の描画サイズ（px）
private const val WOBBLE_AMP = 10f        // アイドル時の上下振幅（px）
private const val WOBBLE_FREQ = 0.6       // アイドル時のホバー周波数（Hz）
private const val FLY_SPEED = 1.2         // Lissajousパラメータの進み速度
private const val FLY_DURATION = 5.0      // フライト最大継続時間（秒）
private const val TICK_STEP = 1.0 / 60.0  // 1フレームあたりの時間（秒）
private const val DRAG_THRESHOLD = 20f    // ドラッグ判定の最小移動距離（px）

class UfoWallpaperService : WallpaperService() {

    // MenuActivity からエンジンを操作するための参照（同一プロセス内で共有）
    companion object {
        var engine: UfoEngine? = null
    }

    override fun onCreateEngine(): Engine = UfoEngine().also { engine = it }

    inner class UfoEngine : Engine() {

        // ── アニメーション状態 ────────────────────────────────────────────
        private var tick = 0.0          // アイドルホバーのsin波フェーズ用タイマー
        private var flying = false      // フライト中かどうか
        private var flyT = 0.0          // Lissajousパラメータ t
        private var flyElapsed = 0.0    // フライト開始からの経過時間（秒）

        // Watcherスレッドから描画ループへの変化通知フラグ
        @Volatile private var alertFlag = false

        // ── スクリーンサイズ（onSurfaceChanged で更新） ───────────────────
        private var screenW = 1080f
        private var screenH = 1920f

        // ── アイドル位置（ドラッグで変更・SharedPreferencesに保存） ────────
        private var idleX = 0f
        private var idleY = 0f
        private var idleInitialized = false

        // ── ドラッグ追跡用 ────────────────────────────────────────────────
        private var dragStartTouchX = 0f
        private var dragStartTouchY = 0f
        private var dragStartIdleX = 0f
        private var dragStartIdleY = 0f
        private var isDragging = false

        // ── 描画ループ（約60fps、Handler で繰り返し呼び出す） ────────────
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val drawRunnable = object : Runnable {
            override fun run() {
                draw()
                handler.postDelayed(this, 16L) // 16ms ≒ 60fps
            }
        }

        // ── URLポーリングスレッド ─────────────────────────────────────────
        @Volatile private var watcherStopped = false  // stop フラグ
        private var watcherThread: Thread? = null
        private var prevHash: String? = null

        // MenuActivity から参照できる監視状態（true = 監視中）
        var isMonitoring = false
            private set

        // ── 描画用Paint ───────────────────────────────────────────────────
        private val bgPaint = Paint().apply { color = Color.BLACK }
        private val ufoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = UFO_SIZE
            textAlign = Paint.Align.CENTER
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)  // タップ・ドラッグを受け取る
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            screenW = width.toFloat()
            screenH = height.toFloat()

            // アイドル位置の初期化（初回のみ）
            // 保存済みがあればそれを使い、なければ画面中央をデフォルトにする
            if (!idleInitialized) {
                val prefs = this@UfoWallpaperService.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                idleX = prefs.getFloat(KEY_IDLE_X, (screenW - UFO_SIZE) / 2f)
                idleY = prefs.getFloat(KEY_IDLE_Y, (screenH - UFO_SIZE) / 2f)
                idleInitialized = true
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                handler.post(drawRunnable)
                startWatcher()
            } else {
                handler.removeCallbacks(drawRunnable)
                stopWatcher()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartTouchX = event.x
                    dragStartTouchY = event.y
                    dragStartIdleX = idleX
                    dragStartIdleY = idleY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - dragStartTouchX
                    val dy = event.y - dragStartTouchY
                    if (isDragging || dx * dx + dy * dy > DRAG_THRESHOLD * DRAG_THRESHOLD) {
                        isDragging = true
                        idleX = (dragStartIdleX + dx).coerceIn(0f, screenW - UFO_SIZE)
                        idleY = (dragStartIdleY + dy).coerceIn(0f, screenH - UFO_SIZE)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // ドラッグ終了: 新しい位置を保存
                        this@UfoWallpaperService.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putFloat(KEY_IDLE_X, idleX)
                            .putFloat(KEY_IDLE_Y, idleY)
                            .apply()
                    } else {
                        // タップ: MenuActivity を起動してメニューを表示
                        val intent = Intent(this@UfoWallpaperService, MenuActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        this@UfoWallpaperService.startActivity(intent)
                    }
                    isDragging = false
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(drawRunnable)
            stopWatcher()
            UfoWallpaperService.engine = null
            super.onSurfaceDestroyed(holder)
        }

        // ── MenuActivity から呼ばれる公開メソッド ─────────────────────────

        /** フライト中のUFOを止めてアイドルに戻す */
        fun stopFlight() {
            flying = false
            alertFlag = false
        }

        /** 監視を停止 / 再開のトグル */
        fun toggleMonitoring() {
            if (isMonitoring) stopWatcher() else startWatcher()
        }

        // ── URLポーリングスレッドの起動・停止 ────────────────────────────

        private fun startWatcher() {
            if (isMonitoring) return  // 多重起動しない
            watcherStopped = false
            prevHash = null
            isMonitoring = true

            val prefs = this@UfoWallpaperService.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val url = prefs.getString(KEY_URL, "https://example.com") ?: "https://example.com"
            val intervalSec = prefs.getInt(KEY_INTERVAL, 60).toLong()

            watcherThread = Thread {
                while (!watcherStopped) {
                    try {
                        val body = fetchBody(url)
                        val hash = sha256(body)

                        if (prevHash == null) {
                            // 初回: ベースラインとして記録するだけ（UFOは飛ばさない）
                            prevHash = hash
                        } else if (hash != prevHash) {
                            // ハッシュ変化 → UFOフライト開始フラグをセット
                            prevHash = hash
                            alertFlag = true
                        }
                    } catch (_: Exception) {
                        // ネットワークエラーは無視して次のポーリングまで待機
                    }

                    // intervalSec秒待機（watcherStopped がtrueになったら早期終了）
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
            // isMonitoring はスレッド内で false に更新される
        }

        // ── HTTPフェッチ（タイムアウト15秒） ─────────────────────────────
        private fun fetchBody(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "ufo-watcher-android/1.0")
            return try {
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }

        // ── SHA-256ハッシュを計算して16進数文字列で返す ──────────────────
        private fun sha256(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        // ── 1フレームの描画処理（約60fps で呼ばれる） ────────────────────
        private fun draw() {
            val holder = surfaceHolder
            val canvas: Canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawRect(0f, 0f, screenW, screenH, bgPaint)

                tick += TICK_STEP

                // alertFlag に応じてフライト状態を更新
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

                // UFOの位置を計算
                val ufoX: Float
                val ufoY: Float

                if (flying) {
                    // フライト: (3,2)リサージュ曲線
                    flyT += TICK_STEP * FLY_SPEED
                    val margin = UFO_SIZE
                    val cx = (screenW - UFO_SIZE) / 2f
                    val cy = (screenH - UFO_SIZE) / 2f
                    ufoX = cx + ((cx - margin) * sin(3 * flyT + PI / 2)).toFloat()
                    ufoY = cy + ((cy - margin) * sin(2 * flyT)).toFloat()
                } else {
                    // アイドル: 保存済み位置でサイン波ホバー
                    val dy = (WOBBLE_AMP * sin(2 * PI * WOBBLE_FREQ * tick)).toFloat()
                    ufoX = idleX
                    ufoY = idleY + dy
                }

                // drawText の y はベースライン指定なので UFO_SIZE 分オフセット
                canvas.drawText("🛸", ufoX + UFO_SIZE / 2f, ufoY + UFO_SIZE, ufoPaint)

            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
