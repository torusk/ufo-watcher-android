# UFO Watcher Android

URLが変化したら🛸が画面を飛び回るAndroidライブ壁紙アプリ。

- **平常時**: 画面中央（任意の位置に変更可）でサイン波ホバー
- **変化検出時**: 画面内をリサージュ曲線（3:2）で5秒間飛び回る
- **タップ**: メニューを表示
- **ドラッグ**: UFOを好きな位置に移動（再起動後も保持）

> macOS版 [ufo-watcher](https://github.com/torusk/ufo-watcher) のAndroid移植版

---

## セットアップ

### 必要なもの

- Android Studio（最新版推奨）
- Android 7.0（API 24）以降のスマートフォン
- USBケーブル

### ビルド＆インストール

```bash
git clone https://github.com/torusk/ufo-watcher-android.git
```

Android Studio で開き、USBでスマホを接続して ▶ ボタンを押すだけ。

**初回のみ: スマホ側でUSBデバッグを有効にする**
1. 設定 → 端末情報 → ビルド番号を7回タップ
2. 開発者向けオプション → USBデバッグをON

---

## 使い方

### 1. 設定

アプリアイコンを開いて監視URLとポーリング間隔を入力 → 「設定を保存」

### 2. ライブ壁紙として設定

「ライブ壁紙として設定」ボタン → システム画面で壁紙を適用

### 3. 操作

| 操作 | 動作 |
|---|---|
| タップ | メニューを表示 |
| ドラッグ | UFOを好きな位置に移動 |

### メニュー項目

| 項目 | 内容 |
|---|---|
| リンクを開く | 監視中のURLをChromeで開く |
| URLを変更 | その場でURLを変更（次のポーリングから反映） |
| 飛行を停止 | フライト中のUFOをアイドルに戻す |
| 監視を停止 / 再開 | URLポーリングのON/OFF切り替え |

---

## アーキテクチャ

```
メインスレッド (Handler @ 60fps)          Watcherスレッド
─────────────────────────────             ─────────────────────
alertFlag を確認           ←── YES ── ハッシュ変化 → alertFlag = true
  → フライトアニメーション開始              │
自動停止（5秒後）                          sleep(interval_sec)
  → alertFlag = false → アイドルへ         └─ ループ
```

- **`UfoWallpaperService.kt`**: ライブ壁紙サービス本体
  - `WallpaperService.Engine` を継承
  - `Handler` + `Runnable` で60fps の描画ループ
  - `Thread` でURLポーリング（SHA-256ハッシュ比較）
  - アイドル: サイン波ホバー / 変化検出時: (3,2)リサージュ曲線
  - `companion object` でエンジン参照を公開し `MenuActivity` と連携

- **`MenuActivity.kt`**: タップ時に表示される透明Activity
  - `AlertDialog` でメニューを表示
  - リンクを開く・URL変更・飛行停止・監視トグル

- **`MainActivity.kt`**: 設定画面
  - 監視URL・ポーリング間隔の入力と保存
  - ライブ壁紙設定画面へのショートカット

---

## 設定値

`SharedPreferences`（`ufo_prefs`）に保存される。

| キー | 内容 | デフォルト |
|---|---|---|
| `url` | 監視対象URL | `https://example.com` |
| `interval_sec` | ポーリング間隔（秒） | `60` |
| `idle_x` / `idle_y` | UFOのアイドル位置 | 画面中央 |

---

## 動作環境

- Android 7.0（API 24）以降
- インターネット接続

## 既知の制限

- ライブ壁紙の仕様上、背景は自前で描く必要があり現在は**黒一色**
- 壁紙をそのまま残してUFOだけ浮かせるには別方式（オーバーレイ）が必要 → [FUTURE.md](FUTURE.md) 参照
