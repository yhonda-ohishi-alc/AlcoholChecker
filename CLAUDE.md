# AlcoholChecker - Android アルコールチェッカー

## プロジェクト概要

業務用アルコールチェックを行う Android アプリ。ドライバーが出勤前・退勤後にアルコール測定を記録し、管理者が履歴を確認できるシステム。

- **リポジトリ**: https://github.com/yhonda-ohishi-alc/AlcoholChecker (private)
- **組織**: yhonda-ohishi-alc
- **Git**: yhonda-ohishi / m.tama.ramu@gmail.com

## システム全体構成

```
AlcoholChecker (このリポジトリ)  ← Android ネイティブクライアント
alc-app                          ← Web/PWA 版 (Nuxt 4 + Rust, Cloudflare Workers)
fc1200-wasm                      ← Tanita FC-1200 RS232C プロトコル (Rust → WASM)
rust-nfc-bridge                  ← NFC カードリーダー → WebSocket ブリッジ (Rust, Windows)
```

### 参考リポジトリ (ローカル)

| リポジトリ | パス | 概要 |
|---|---|---|
| alc-app | `/home/yutaka/android/alc-app` | Web/PWA フルスタック版。WebSerial, WebRTC, 顔認識等 |
| fc1200-wasm | `/home/yutaka/android/fc1200-wasm` | Tanita FC-1200 プロトコル実装 (Rust→WASM) |
| rust-nfc-bridge | `/home/yutaka/android/rust-nfc-bridge` | Windows NFC→WebSocket ブリッジ (Rust) |

alc-app が本番 Web 版であり、AlcoholChecker はその Android ネイティブ版という位置づけ。

## 技術スタック

| カテゴリ | 技術 | バージョン |
|---|---|---|
| 言語 | Kotlin | 1.9.22 |
| ビルド | Gradle (KTS) | AGP 8.2.2 |
| KSP | Kotlin Symbol Processing | 1.9.22-1.0.17 |
| Compile SDK | Android 14 | API 34 |
| Min SDK | Android 8.0 | API 26 |
| Target SDK | Android 14 | API 34 |
| DB | Room | 2.6.1 |
| 通信 | Retrofit + OkHttp | 2.9.0 / 4.12.0 |
| 非同期 | Coroutines + Flow | 1.7.3 |
| ライフサイクル | ViewModel + LiveData | 2.7.0 |
| ナビゲーション | Navigation Component | 2.7.7 |
| カメラ | CameraX | 1.3.1 |
| 位置情報 | Play Services Location | 21.1.0 |
| UI | ViewBinding + Material Design | 1.11.0 |

## ディレクトリ構成

```
app/src/main/
├── java/com/example/alcoholchecker/
│   ├── ui/                          # UI 層 (Activity)
│   │   ├── login/LoginActivity      #   ログイン画面
│   │   ├── home/HomeActivity        #   ホーム (出勤前/退勤後/履歴)
│   │   ├── check/AlcoholCheckActivity  # 測定記録画面
│   │   ├── history/HistoryActivity  #   測定履歴画面
│   │   └── admin/AdminActivity      #   管理者画面 (未実装)
│   ├── data/                        # データ層
│   │   ├── local/                   #   Room DB (AppDatabase, DAO)
│   │   ├── model/                   #   データクラス (User, AlcoholCheckRecord)
│   │   ├── repository/              #   Repository パターン
│   │   └── remote/                  #   API 通信 (未実装)
│   ├── service/                     # サービス層 (未実装)
│   └── util/                        # ユーティリティ (未実装)
└── res/
    ├── layout/                      # XML レイアウト
    └── values/                      # 文字列, 色, テーマ
```

## アーキテクチャ

**MVVM + Repository パターン** (Clean Architecture 志向)

```
Activity (UI) → ViewModel → Repository → DAO (Room DB)
                                       → Remote API (未実装)
```

### データモデル

- **User**: userId, name, department, role (driver/admin), createdAt
- **AlcoholCheckRecord**: userId, userName, checkType (出勤前/退勤後), alcoholLevel, result (正常/検出), photoPath, latitude, longitude, note, checkedAt

### Room DB

- DB 名: `alcohol_checker_db` (version 1)
- Singleton パターン (`AppDatabase.getDatabase()`)
- DAO は `suspend fun` + `Flow<>` ベース

## 開発ルール

### Kotlin 規約
- **命名**: キャメルケース (変数/関数), パスカルケース (クラス)
- **非同期**: Coroutines + Flow (コールバック不可)
- **DI**: 現時点では手動 (将来 Hilt 導入検討)
- **UI バインディング**: ViewBinding (findViewById 不可)

### コミット
- 日本語コミットメッセージ可
- プレフィックス: `feat:`, `fix:`, `refactor:`, `docs:`

### パーミッション (AndroidManifest.xml)
INTERNET, CAMERA, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, BLUETOOTH, BLUETOOTH_CONNECT, BLUETOOTH_SCAN

## ビルド・実行

```bash
# ビルド
cd /home/yutaka/android/AlcoholChecker
./gradlew assembleDebug

# テスト
./gradlew test              # ユニットテスト
./gradlew connectedCheck    # Android テスト

# lint
./gradlew lint
```

## TODO / 未実装機能

- [ ] 認証機能 (現在ハードコード)
- [ ] カメラ撮影 (CameraX レイアウト準備済み、実装 TODO)
- [ ] GPS 位置情報取得 (パーミッション準備済み、実装 TODO)
- [ ] 履歴画面の RecyclerView Adapter
- [ ] 管理者画面 (AdminActivity)
- [ ] API 通信 (data/remote 層)
- [ ] Bluetooth でのアルコール検知器連携
- [ ] テスト実装 (unit / instrumented)
- [ ] ViewModel 導入 (現在 Activity に直接ロジック)
- [ ] ユーザー名の取得・表示

## デプロイ

### alc-app (Web/PWA 版)

alc-app のデプロイ時は環境変数が必要なため、SSH 経由で実行すること。

```bash
ssh yhonda@192.168.11.60
```
