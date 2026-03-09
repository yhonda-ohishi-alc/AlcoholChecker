# AlcoholChecker Android App

WebView ベースのアルコールチェックアプリ。NFC/BLE/シリアル通信ブリッジ + Device Owner 対応。

## ビルド

- デバッグ: `./gradlew installDebug`
- リリース: CI/CD で自動ビルド (手動ビルドには release.keystore が必要)
- **署名不一致エラー**: リリース署名 APK がある端末には `adb uninstall com.example.alcoholchecker` してからデバッグビルドをインストール

## リリース (CI/CD 自動化済み)

1. `app/build.gradle.kts` の `versionName` を変更 (例: `"1.3.1"` → `"1.4.0"`)
2. コミット & `master` に push
3. GitHub Actions が自動で:
   - `versionCode` を `github.run_number` に置換 (手動管理不要)
   - release APK ビルド
   - SHA-256 チェックサム生成
   - `v{versionName}` タグ作成
   - GitHub Release に APK + `.sha256` ファイル添付
- **トリガー条件**: `app/build.gradle.kts` が変更された push のみ
- **重複防止**: 同じタグが既に存在する場合はスキップ
- `versionCode` はローカルデバッグ用の初期値。リリースでは `run_number` で上書き

## Device Owner プロビジョニング

工場出荷リセット → QR スキャン → アプリ自動インストール + バックエンド自動登録

### フロー
1. 管理者ダッシュボード「デバイス管理」→「Device Owner プロビジョニング」でコード生成 + APK URL 入力 → QR 表示
2. 端末を工場出荷リセット → 初期設定画面で QR スキャン
3. `AppDeviceAdminReceiver.onProfileProvisioningComplete` がプロビジョニング extras から `registration_code` を保存
4. アプリ起動時に `WebViewActivity.autoRegisterDeviceOwner()` が `POST /api/devices/register/claim` を自動呼び出し → 即承認
5. フロントエンド `useAuth.ts` が自動アクティベーション

### プロビジョニング QR 形式
```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
    "com.example.alcoholchecker/com.example.alcoholchecker.admin.AppDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "<APK URL>",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": "<SHA-256 Base64URL>",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
  "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
    "registration_code": "<generated_code>",
    "device_name": "営業車X",
    "is_dev_device": "false"
  }
}
```

## 主要コンポーネント

| ファイル | 役割 |
|---------|------|
| `WebViewActivity.kt` | メイン Activity。WebView + NFC/BLE/Serial/ScreenCapture ブリッジ |
| `AppDeviceAdminReceiver.kt` | Device Owner/Admin。プロビジョニング extras 読み取り |
| `WatchdogService.kt` | ヘルスチェック + 自動再起動 |
| `RoomWatcher.kt` | WebSocket で遠隔点呼の着信監視 |
| `IncomingCallActivity.kt` | 着信 UI (フルスクリーン、ロック画面対応) |
| `MyFirebaseMessagingService.kt` | FCM 着信通知 + OTA アップデート |
| `device_admin.xml` | Device Admin ポリシー (force-lock) |

## SharedPreferences

- `device_settings`: `device_id`, `fcm_token`, `is_dev_device`, `registration_code`, `device_name`
- `call_settings`: `schedule` (JSON)

## 関連リポジトリ

- バックエンド: `rust-alc-api` (Axum + PostgreSQL)
- フロントエンド: `alc-app/web` (Nuxt 4 on Cloudflare Workers)
- シグナリング: `cf-alc-signaling` (Cloudflare Durable Objects)
