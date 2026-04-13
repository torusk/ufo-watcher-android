package com.example.ufowatcher

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

// SharedPreferences のキー（MainActivity からも参照する）
const val PREFS_NAME = "ufo_prefs"
const val KEY_URL = "url"
const val KEY_INTERVAL = "interval_sec"

// アニメーション定数（inner class に companion object は置けないのでトップレベルに定義）
private const val UFO_SIZE = 80f         // UFO絵文字の描画サイズ（px）
private const val WOBBLE_AMP = 10f       // アイドル時の上下振幅（px）
private const val WOBBLE_FREQ = 0.6      // アイドル時のホバー周波数（Hz）
private const val FLY_SPEED = 1.2        // Lissajousパラメータの進み速度
private const val FLY_DURATION = 5.0     // フライト最大継続時間（秒）
private const val TICK_STEP = 1.0 / 60.0 // 1フレームあたりの時間（秒）

class UfoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = UfoEngine()

    inner class UfoEngine : Engine() {

        // ── アニメーション状態 ────────────────────────────────────────────
        private var tick = 0.0          // アイドルホバーのsin波フェーズ用タイマー
        private var flying = false      // フライト中かどうか
        private var flyT = 0.0          // Lissajousパラメータ t
        private var flyElapsed = 0.0    // フライト開始からの経過時間（秒）

        // Watcherスレッドから描画ループへの変化通知フラグ
        // @Volatile で書き込みが即座に他スレッドから見えるようにする
        @Volatile private var alertFlag = false

        // ── スクリーンサイズ（onSurfaceChanged で更新） ───────────────────
        private var screenW = 1080f
        private var screenH = 1920f

        // ── 描画ループ（約60fps、Handler で繰り返し呼び出す） ────────────
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val drawRunnable = object : Runnable {
            override fun run() {
                draw()
                handler.postDelayed(this, 16L) // 16ms ≒ 60fps
            }
        }

        // ── URLポーリングスレッド ─────────────────────────────────────────
        @Volatile private var stopWatcher = false
        private var watcherThread: Thread? = null
        private var prevHash: String? = null // 前回フェッチのSHA-256ハッシュ

        // ── 描画用Paint ───────────────────────────────────────────────────
        private val bgPaint = Paint().apply { color = Color.BLACK }
        private val ufoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = UFO_SIZE
            textAlign = Paint.Align.CENTER
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // タップイベントを受け取れるようにする（タップでフライト停止）
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            screenW = width.toFloat()
            screenH = height.toFloat()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                // 壁紙が表示されたら描画ループ＆ポーリング開始
                handler.post(drawRunnable)
                startWatcher()
            } else {
                // 非表示になったら停止（バッテリー節約）
                handler.removeCallbacks(drawRunnable)
                stopWatcher()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            // 指を離したタイミング（ACTION_UP）でフライト中ならUFOを止める
            if (event.action == MotionEvent.ACTION_UP && flying) {
                flying = false
                alertFlag = false
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(drawRunnable)
            stopWatcher()
            super.onSurfaceDestroyed(holder)
        }

        // ── URLポーリングスレッドを起動 ───────────────────────────────────
        private fun startWatcher() {
            stopWatcher = false
            prevHash = null // 再起動時はベースラインをリセット

            val prefs = this@UfoWallpaperService.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val url = prefs.getString(KEY_URL, "https://example.com") ?: "https://example.com"
            val intervalSec = prefs.getInt(KEY_INTERVAL, 60).toLong()

            watcherThread = Thread {
                while (!stopWatcher) {
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
                        // ネットワークエラーなどは無視して次のポーリングまで待機
                    }

                    // intervalSec秒待機。stopWatcherがtrueになったら500msごとに早期終了
                    val deadline = System.currentTimeMillis() + intervalSec * 1000L
                    while (!stopWatcher && System.currentTimeMillis() < deadline) {
                        Thread.sleep(500)
                    }
                }
            }.also {
                it.isDaemon = true  // メインプロセス終了時に自動で終了
                it.start()
            }
        }

        private fun stopWatcher() {
            stopWatcher = true
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
                // 背景を黒で塗りつぶし
                canvas.drawRect(0f, 0f, screenW, screenH, bgPaint)

                tick += TICK_STEP  // ホバーフェーズを進める

                // ── alertFlag に応じてフライト状態を更新 ─────────────────
                if (alertFlag) {
                    if (!flying) {
                        // フライト開始
                        flying = true
                        flyT = 0.0
                        flyElapsed = 0.0
                    } else {
                        // フライト継続中: 経過時間を加算して上限に達したら自動停止
                        flyElapsed += TICK_STEP
                        if (flyElapsed >= FLY_DURATION) {
                            flying = false
                            alertFlag = false
                        }
                    }
                } else {
                    // alertFlagがクリアされた（タップdismiss等）なら即停止
                    flying = false
                }

                // ── UFOの位置を計算 ───────────────────────────────────────
                val ufoX: Float
                val ufoY: Float

                if (flying) {
                    // フライト: (3,2)リサージュ曲線で画面を縦横無尽に飛び回る
                    // x = cx + rx * sin(3t + π/2)
                    // y = cy + ry * sin(2t)
                    flyT += TICK_STEP * FLY_SPEED
                    val margin = UFO_SIZE
                    val cx = (screenW - UFO_SIZE) / 2f
                    val cy = (screenH - UFO_SIZE) / 2f
                    val rx = cx - margin  // X方向の振幅
                    val ry = cy - margin  // Y方向の振幅
                    ufoX = cx + (rx * sin(3 * flyT + PI / 2)).toFloat()
                    ufoY = cy + (ry * sin(2 * flyT)).toFloat()
                } else {
                    // アイドル: 右下でサイン波ホバー（±WOBBLE_AMP px）
                    val dy = (WOBBLE_AMP * sin(2 * PI * WOBBLE_FREQ * tick)).toFloat()
                    ufoX = screenW - UFO_SIZE - 40f
                    ufoY = screenH - UFO_SIZE * 2.5f + dy
                }

                // UFOを🛸絵文字で描画
                // drawText の y 座標はベースライン指定なので UFO_SIZE 分オフセット
                canvas.drawText("🛸", ufoX + UFO_SIZE / 2f, ufoY + UFO_SIZE, ufoPaint)

            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
