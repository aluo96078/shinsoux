# Shinsou X

**Shinsou X** 是一款本機優先、可擴充的跨平台漫畫書庫與閱讀器，也是原生 iOS 專案 [Shinsou](https://github.com/aluo96078/shinsou) 的正式後繼版本。它以 Kotlin Multiplatform 與 Compose Multiplatform 建構，讓 Android、iOS 與 macOS 共用核心資料、來源擴充套件、下載、備份與主要介面，同時保留各平台的安全儲存與系統整合。

> [!IMPORTANT]
> **Shinsou X 是目前唯一持續開發的版本。** 舊版 Shinsou（Swift／SwiftUI）已停止開發，只保留作為歷史參考。新功能、問題修復與文件更新都會在本專案進行。

目前程式已涵蓋原版的主要使用流程，macOS 版另提供貼近桌面習慣的 sidebar、雙欄 detail、系統 Menu Bar 與快捷鍵。專案仍在持續驗證與收斂階段；「可編譯」或「已有 deterministic test」不代表所有真機、帳號及第三方來源網站都已完成驗證，詳細狀態請見 [功能對齊狀態](docs/PARITY.md)。

## 平台

| 平台 | 最低目標／產物 | 平台整合 |
|------|---------------|----------|
| Android | Android 8.0（API 26）以上；Debug APK | Android Keystore、WorkManager、Biometric、WebView、文件選擇器 |
| iOS／iPadOS | iOS 16 以上；Xcode App＋Widget | Keychain、iCloud Drive、BGTask、WKWebView、Widget |
| macOS | Compose Desktop App／DMG；目前打包目標為 Apple Silicon | Menu Bar、快捷鍵、系統文件選擇器、macOS Keychain |

## 核心特色

- 書庫分類、搜尋、篩選、排序、批次操作與閱讀進度管理
- LTR、RTL、直向、Webtoon 等閱讀模式，支援縮放、濾鏡、預取與離線閱讀
- 可安裝的 JavaScript 來源擴充套件，以及來源登入、Cookie、偏好與網路設定
- 下載佇列、本地 ZIP／CBZ／EPUB／圖片匯入、備份還原與跨裝置快照同步
- AniList 追蹤流程、多語系介面，以及 Android／iOS／macOS 各自的安全儲存
- Local-first 設計：專案本身不提供廣告或分析服務，主要資料保存在使用者裝置

## 已實作範圍

- 圖書館、多分類、搜尋／篩選／排序與批次操作
- Updates（含 Upcoming）、History、Browse、Migration、More、Statistics 與 Settings
- 漫畫詳情、章節篩選／排序、scanlator 排除、重複／缺章處理、筆記與批次操作
- Reader 的 LTR、RTL、直向、Webtoon、縮放、濾鏡、章節清單、章末切換、錯誤重試與預取
- 下載佇列、暫停／重試／重排，以及原子 completion manifest 驗證的離線頁面；「清除完成項目」只隱藏完成列，不刪除離線狀態
- 可攜式 snapshot 備份／還原、選擇性還原、deterministic 衝突合併，以及 app-private 自動備份
- Shinsou X JavaScript extension repository 的安裝、更新、卸載、執行授權、偏好、登入與來源瀏覽；repository 設定納入可攜 snapshot
- 內建 Local source（source `0`）：直接圖片與 ZIP／CBZ／EPUB；匯入後複製至 app-private storage，並以原子發布的 v2 manifest 拒絕部分匯入
- AniList 登入／搜尋／綁定／編輯 UI 與 OAuth token 流程；MyAnimeList 因缺少正式 client ID 而明示停用
- iOS Widget payload、iCloud Drive snapshot sync，以及 Android／iOS Reader 實體音量鍵接線

Local source 支援 `jpg`、`jpeg`、`png`、`webp`、`gif`、`avif`、`heic`、`bmp`，以及以 ZIP 容器讀取的 `zip`、`cbz`、`epub`。EPUB 目前依原版作法抽取圖片並以漫畫頁排序，不解析書籍排版或文字 spine。

## 擴充套件與網路

JVM（Android／Desktop）使用 Rhino，iOS 使用 JavaScriptCore。Browse、Reader 與 Download 共用同一套來源儲存與 request builder，因此來源 headers、cookies、Referer、per-source proxy 與 User-Agent 會沿用到圖片和下載請求。

- Desktop deterministic compatibility test 會載入相鄰 `shinsou_plugin` 的官方腳本並在 Rhino 初始化；這不會連正式來源網站。
- iOS Simulator test 覆蓋 JavaScriptCore 的同步 extension contract；任意第三方 Promise／async／`fetch` 腳本不保證相容。
- `AppSnapshot.extensionRepositories` 是可攜的 repository source of truth。升級時只會一次性遷移舊 KV 清單；之後 add／remove／default、備份還原與同步結果都會精確鏡像回 KV，不會再把 stale KV 無條件 union 回 snapshot。
- Extension 的 `trusted` 狀態是 `{id}:{version}:{SHA-256}` 的執行授權，不代表 repository 作者身分已由 PKI 驗證。撤銷會立即卸載 runtime、保留 package 供重新授權或卸載；啟動時不會只因 bytes 仍符合 manifest digest 就重建已撤銷的授權。
- Source settings 可手動管理 cookie，或匯入 Netscape `cookies.txt`／常見 JSON 匯出。匯入器限制 1 MiB／500 筆，並驗證來源 domain、path、expiry 與 cookie 字元；平台 picker 會在配置完整 ByteArray 前先檢查 declared size。
- Plugin HTTP redirect 由共用層逐 hop 處理：限制 hop 數、拒絕 HTTPS 降級、跨 origin 丟棄 credentials／自訂秘密 header，並依新目標重新選 cookie。`Set-Cookie` 支援 `Max-Age`／`Expires`、精確 path 刪除與保守 suffix 防護；這套 suffix 規則不是完整 Public Suffix List。
- Android 以 WebView、iOS 以 non-persistent WKWebView 完成 Cloudflare／Web challenge 後擷取並驗證 cookie。Desktop 只能開預設瀏覽器；外部瀏覽器 cookie 不會自動匯入，需使用檔案匯入或手動輸入。
- DNS over HTTPS 設定目前只會保存，不會改變 runtime DNS。專案刻意不採「直接連 IP 再覆寫 Host」的作法，因其無法安全保留 HTTPS SNI／憑證語義。

## Desktop

Desktop 是 Compose Desktop 的 macOS 風格實作，不是 AppKit 原生介面。目前包含：

- sidebar 與寬視窗雙欄 master/detail
- 系統 Menu Bar
- `⌘1`～`⌘5` 切換主區、`⌘,` 開啟設定、`⌘Q` 結束
- 可選的關閉確認、視窗最小尺寸與 DMG／ICNS 打包設定
- 鍵盤 Reader 操作與 app 前景期間的自動備份排程
- 敏感 plugin KV 使用 AES-256-GCM，master key 由 JNA 直接存取 macOS Security.framework Keychain；Desktop app lock／secure screen 則明示為不可用

## 專案結構

- `composeApp/src/commonMain`：跨平台資料模型、repository、網路／外掛 runtime、下載、備份、同步與 Compose UI
- `composeApp/src/androidMain`：Android Activity、文件選擇器、Biometric、WebView、WorkManager 與持久化接線
- `composeApp/src/iosMain`：iOS framework entry、JavaScriptCore、Keychain、WKWebView、iCloud Drive、BGTask、文件選擇器與 Widget payload
- `composeApp/src/jvmCommonMain`：Android／Desktop 共用 Rhino JavaScript runtime
- `composeApp/src/desktopMain`：macOS Desktop host、Menu Bar、bounded 檔案選擇器、Application Support 儲存與 Keychain master-key 接線
- `iosApp`：SwiftUI host、Reader 音量鍵 bridge、Widget extension、entitlements 與 XcodeGen 規格

## 開始使用與建置

需求：JDK 17、Android SDK Platform 36；iOS 與 DMG 建置需在 macOS 執行，iOS 另需 Xcode、XcodeGen 與適用的 Apple 開發簽章設定。公開 fork 需將 `iosApp/project.yml` 中的 Development Team 換成自己的團隊。

```bash
# 取得原始碼
git clone https://github.com/aluo96078/shinsoux.git
cd shinsoux

# 共用／Desktop deterministic tests
./gradlew :composeApp:desktopTest

# Desktop 執行與 DMG
./gradlew :composeApp:run
./gradlew :composeApp:packageDmg

# Android debug APK
./gradlew :composeApp:assembleDebug

# iOS Simulator framework 與 tests
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
./gradlew :composeApp:iosSimulatorArm64Test

# 產生並建置 iOS app（無簽章 Simulator）
cd iosApp
xcodegen generate
xcodebuild -project Shinsou.xcodeproj \
  -scheme Shinsou \
  -sdk iphonesimulator \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

完整環境、打包與驗證層級請見 [建置與驗證](docs/BUILDING.md)。

## 文件與相關專案

- [建置與驗證](docs/BUILDING.md)：環境需求、各平台建置、簽章與驗證清單
- [功能對齊狀態](docs/PARITY.md)：Android、iOS、macOS 的實作與外部驗證狀態
- [shinsou_plugin](https://github.com/aluo96078/shinsou_plugin)：Shinsou X JavaScript 擴充套件儲存庫
- [Shinsou（停止開發）](https://github.com/aluo96078/shinsou)：舊 Swift／SwiftUI 版本的歷史封存

## 資料與安全邊界

- Android snapshot 與本地頁面位於 app-private files；credentials、cookies、OAuth token、proxy API key 等敏感 KV 使用 Android Keystore AES-GCM key 加密。
- iOS snapshot 位於 Application Support；credentials、cookies、OAuth token、proxy API key 與其他 secrets 由 Keychain 保存。
- Desktop snapshot 位於 `~/Library/Application Support/Shinsou`；敏感 KV 使用 AES-256-GCM，master key 存在 macOS Keychain。舊 `plugin-secrets.key` 只有在 Keychain 回讀及既有密文解密都成功後才會刪除；Keychain 失敗時不建立新的 file fallback。
- 可攜式備份、自動備份與 iCloud snapshot 以 `AppSnapshot` 為資料模型：會保存 extension repository 設定，但不包含已安裝的 JavaScript package、per-source plugin key-value state、credentials、cookies、OAuth token 或 proxy API key。proxy key 在序列化前會 redaction，restore／sync 也保留目的裝置的本機 secret。
- Local source 與已下載頁面的檔案 bytes 不在 snapshot envelope 內。跨裝置還原會保留資料記錄，但仍需在目的裝置重新匯入原始檔或重新下載頁面。
- Download completion manifest 與 Local v2 manifest 都留在 app-private storage，不會隨 portable snapshot 移動。同步合併不採用遠端 `downloadQueue`，只保留本機 queue；備份還原也保留並清理本機 queue，而不啟動另一台裝置的下載工作。
- 自動備份是 app-private 原子快照，提供立即建立、列表、損毀標示、還原、刪除與 retention。Android 使用 WorkManager、iOS 使用 BGProcessingTask；實際背景執行時間由作業系統決定，Desktop 則只在 app 存活時以前景 scheduler 檢查。
- iOS 同步使用 `Documents/Shinsou/shinsou-sync.shinsoubackup` 單檔快照與 NSFileCoordinator；它不是 record-level CloudKit，也不是即時逐筆同步。

## 尚需外部或真機驗證

- AniList 的真實帳號 OAuth、來源網站登入與各站 Cloudflare 行為會受外部服務影響，deterministic tests 不會連這些服務。
- iOS OAuth callback 目前以貼上 redirect URL／access token 的 UI 流程為主要保底。
- Android 音量鍵攔截只在 Reader 開啟且設定啟用時生效，會消耗 Down／Up 以避免改變音量；仍需 Android 實機確認各廠牌按鍵與 HUD 行為。
- iOS 音量鍵事件、系統音量恢復與 HUD 抑制已接線且可編譯，但必須用實體 iPhone 驗證。
- Android Keystore 與 iOS Keychain 的首次建立、重啟後解密及裝置 access-control 行為仍需簽章／真機 smoke；target compile 不等於 secure store 已在發布環境驗證。
- 無簽章 Simulator build 無法驗證 App Group、Widget、iCloud entitlement、Keychain sharing 或 iOS 背景排程。
- 三平台文件 picker 的 declared-size／bounded-read 路徑已接；Desktop packaged app 已確認 picker 可開啟與取消，但實際選取檔案及 Android／iOS cloud／stream provider 仍需 smoke。無法提供可靠 size metadata 的 provider 會被安全拒絕。
- Desktop unsigned packaged app 已確認 sidebar、Menu Bar 導航、Updates Upcoming、Local picker、Backup／Sync、停用的 app-lock／secure-screen 控制與關閉確認。實體鍵盤 accelerator、含資料的 master/detail／Reader、Keychain access prompt／ACL 及 legacy key migration 仍需人工驗證。

## 使用責任

Shinsou X 只提供書庫、閱讀器與來源擴充機制，不營運或託管第三方漫畫內容。使用者應只安裝可信任的擴充套件、遵守所在地法律與內容來源的使用條款，並自行確認其存取內容的權利。

## 授權

本專案採用 [MIT License](LICENSE)。Copyright (c) 2026 Aluo.
