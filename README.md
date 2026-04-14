# UFO Watcher Android

URLが変化したら🛸が画面上を飛び回るAndroidアプリ。

- **平常時**: 画面上の好きな位置でホバー
- **変化検出時**: 画面を一周飛び回り、赤バッジが点灯
- **タップ**: バッジを消してURLをブラウザで開く
- **ドラッグ**: UFOを好きな位置に移動（再起動後も保持）

> macOS版 [ufo-watcher](https://github.com/torusk/ufo-watcher) のAndroid移植版

---

## ダウンロード＆インストール

### 1. APKをダウンロード

**[Releases](https://github.com/torusk/ufo-watcher-android/releases)** から最新の `app-debug.apk` をダウンロード。

スマホで直接開くと手順が少なくて済みます。

### 2. 提供元不明のアプリを許可

APKをタップするとインストールを求められますが、その前にAndroid側の設定が必要です。

**Android 8.0以降:**
- APKをタップ →「設定」に誘導されるので「この提供元のアプリを許可」をONにする

**Android 7.x:**
- 設定 → セキュリティ → 提供元不明のアプリ → ON

### 3. インストール

APKをタップしてインストール。

---

## 使い方

### 初回セットアップ

1. アプリアイコンを開く
2. **「オーバーレイを許可」** をタップ → システム設定で「UFO Watcher」をONにして戻る
3. チェックしたいURLを入力して **「設定を保存」**
4. **「ON」** ボタンでUFOを起動 → UFOが画面に浮かぶ

### 操作

| 操作 | 動作 |
|---|---|
| タップ | 赤バッジを消す ＋ URLをブラウザで開く |
| ドラッグ | UFOを好きな位置に移動 |
| アプリ画面の「OFF」 | UFOを非表示にする |

### 通知バーについて

Androidの仕様上、バックグラウンドで動かし続けるために通知バーに「UFO Watcher 起動中」が常駐します。タップするとアプリ画面を開けます。

---

## 動作の仕組み

```
メインスレッド（Handler）               ポーリングスレッド
──────────────────────────             ──────────────────────────
描画ループ（飛行中60fps・                URLをフェッチしてハッシュ比較
          アイドル10fps）    ←─ 変化検知 → handler.post { startFlight() }
  └─ WindowManager でUFOを移動
```

- **`UfoOverlayService.kt`**: サービス本体。`WindowManager` でUFOを画面上に重ねて表示
- **`MainActivity.kt`**: 設定画面。URL入力・ON/OFFボタン・権限案内

---

## ビルド方法（開発者向け）

```bash
git clone https://github.com/torusk/ufo-watcher-android.git
```

Android Studio で開き、USBデバッグ接続したスマホに ▶ で転送。

**動作環境**: Android 7.0（API 24）以上 / インターネット接続必須
