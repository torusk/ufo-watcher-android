# 今後の変更メモ

## 最優先: ライブ壁紙 → オーバーレイ方式への移行

### 背景・目的

現在はライブ壁紙として実装しているため、背景が真っ黒になってしまう。
ユーザーが設定している壁紙をそのまま残しつつ、UFOだけを画面上に浮かせるには
**オーバーレイ方式（他アプリの上に重ねて表示）** への移行が必要。

### 技術的な変更内容

| 現在 | 変更後 |
|---|---|
| `WallpaperService` | `Service`（通常のバックグラウンドサービス） |
| `SurfaceHolder` + `Canvas` で描画 | `WindowManager` で UFO ビューを画面に追加 |
| ライブ壁紙として設定 | アプリ起動 or 再起動後に自動で常駐 |

#### 主な実装変更点

1. **`UfoWallpaperService.kt` を廃止**
   - 新たに `UfoOverlayService.kt`（`Service` を継承）を作成
   - `WindowManager.addView()` で UFO の View を画面に重ねる
   - `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` を使用

2. **必要な権限の追加**
   ```xml
   <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   ```

3. **フォアグラウンドサービス化**
   - `startForeground()` で通知を出しながら常駐させる（OSに殺されないため）
   - 通知チャンネルの作成が必要（Android 8.0以降）
   - 通知アイコンに「UFO監視中」と表示する

4. **権限付与フローを MainActivity に追加**
   - `Settings.canDrawOverlays()` で権限チェック
   - 未許可なら `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` を開いて誘導

5. **バッテリー最適化除外の案内**
   - 設定画面にバッテリー最適化除外を促すボタンを追加
   - `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` で設定画面を開く

### ユーザー側でやること（インストール後）

1. アプリを開く → 「他のアプリの上に重ねて表示」を許可
2. バッテリー最適化を除外に設定（任意だが推奨）
3. URL・間隔を設定して「監視を開始」

### 懸念点・トレードオフ

- 通知バーに常駐アイコンが出る（Androidの仕様上避けられない）
- セットアップが2〜3ステップ増える
- パフォーマンス自体はライブ壁紙とほぼ同等

---

## その他 検討中の機能

- [ ] UFO画像を🛸絵文字からカスタム画像（PNG）に変更
- [ ] 変化検出時に振動（バイブ）でも通知
- [ ] ウィジェット対応（ホーム画面に監視状態を表示）
- [ ] CSS セレクタ・ignore_patterns の設定画面（macOS版と同等の精度）
