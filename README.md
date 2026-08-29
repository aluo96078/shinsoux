# Shinsou X

**Shinsou X** 是一款本機優先、可擴充的跨平台漫畫書庫與閱讀器，也是原生 iOS 專案 [Shinsou](https://github.com/aluo96078/shinsou) 的正式後繼版本。它以 Kotlin Multiplatform 與 Compose Multiplatform 建構，讓 Android、iOS、macOS 與 Windows 共用核心資料、來源擴充套件、下載、備份與主要介面，同時保留各平台的安全儲存與系統整合。

> [!IMPORTANT]
> **Shinsou X 是目前唯一持續開發的版本。** 舊版 Shinsou（Swift／SwiftUI）已停止開發，只保留作為歷史參考。新功能、問題修復與文件更新都會在本專案進行。

目前程式已涵蓋原版的主要使用流程，macOS 版另提供貼近桌面習慣的 sidebar、雙欄 detail、系統 Menu Bar 與快捷鍵。專案仍在持續驗證與收斂階段；「可編譯」或「已有 deterministic test」不代表所有真機、帳號及第三方來源網站都已完成驗證，詳細狀態請見 [功能對齊狀態](docs/PARITY.md)。

## 平台

| 平台 | 最低目標／產物 | 平台整合 |
|------|---------------|----------|
| Android | Android 8.0（API 26）以上；GitHub Release 提供測試用 Debug APK | Android Keystore、WorkManager、Biometric、WebView、文件選擇器 |
| iOS／iPadOS | iOS 16 以上；僅提供原始碼，需在 macOS／Xcode 自行建置 App＋Widget | Keychain、iCloud Drive、BGTask、WKWebView、Widget |
| macOS | Compose Desktop App／DMG；目前打包目標為 Apple Silicon | Menu Bar、快捷鍵、系統文件選擇器、macOS Keychain |
| Windows | Windows 10／11 x64；MSI／EXE | `%USERPROFILE%` 資料根目錄、使用者範圍 DPAPI、Ctrl 快捷鍵、原生文件選擇器 |

## 核心特色

- 書庫分類、搜尋、篩選、排序、批次操作與閱讀進度管理
- LTR、RTL、直向、Webtoon 等閱讀模式，支援縮放、濾鏡、預取與離線閱讀
- 可安裝的 JavaScript 來源擴充套件，以及來源登入、Cookie、偏好與網路設定
- 下載佇列、本地 TXT／ZIP／CBZ／完整 EPUB／圖片匯入、備份還原與端對端加密事件同步
- 多語系介面，以及 Android／iOS／macOS／Windows 各自的安全儲存
- Local-first 設計：專案本身不提供廣告或分析服務，主要資料保存在使用者裝置

## ShuYue 風格小說閱讀器

小說閱讀器以 ShuYue 的閱讀操作為基礎，延展到 Android、iOS、macOS 與 Windows 的統一內容層。純文字章節與 EPUB 都保留可恢復的閱讀定位，重新排版或切換裝置後仍能回到接近原位置。

- 閱讀正文時工具列預設隱藏；點擊畫面中央 40% 區域即可顯示／隱藏底部功能欄
- 左右兩側各 30% 為上一頁／下一頁點擊區；共用操作包含水平拖曳，Desktop 另支援滑鼠滾輪與鍵盤，實體音量鍵翻頁只在 Android／iOS 提供
- 可使用連續向下滾動、LTR、RTL 與垂直翻頁模式；章節邊界可直接進入前一章／下一章
- 底部欄只保留必要圖示（設定、章節、上一頁、下一頁），正文不插入分割線、進度條或多餘按鈕
- 「Page turn animation」可在閱讀設定中關閉；關閉後翻頁與定位會直接落位，不等待動畫完成
- 頂部標題欄與底部工具列遵守 iOS safe area，避開 Face ID 感測區與 Home Indicator；Android／Desktop 也使用相同的內容留白規則
- 來源 HTML 會先清理常見標籤與 entity，再分頁並保留 UTF-16 offset；正文中的合法圖片標記仍會以 inline image 顯示

## 已實作範圍

- 圖書館、多分類、搜尋／篩選／排序與批次操作
- Updates（含 Upcoming）、History、Browse、Migration、More、Statistics 與 Settings
- 漫畫詳情、章節篩選／排序、scanlator 排除、重複／缺章處理、筆記與批次操作
- Reader 的 LTR、RTL、直向、Webtoon、縮放、濾鏡、章節清單、章末切換、錯誤重試與預取
- 下載佇列、暫停／重試／重排，以及原子 completion manifest 驗證的離線頁面；「清除完成項目」只隱藏完成列，不刪除離線狀態
- 可攜式 snapshot 備份／還原、選擇性還原、deterministic 衝突合併，以及 app-private 自動備份
- Shinsou X JavaScript extension repository 的安裝、更新、卸載、執行授權、偏好、登入與來源瀏覽；repository 設定納入可攜 snapshot
- ShuYue v2 repository／runtime 相容層：保留 package／source identity，支援登入、登出、來源刷新與精確事件授權，不把 v2 套件誤當成舊版單一來源
- 統一內容基礎：TXT、圖片序列與完整 EPUB package/resource graph 都寫入 app-private immutable blob store，metadata/ref/outbox 由 shared SQLite 原子提交
- 統一 Reader：文字定位、圖片頁面與 EPUB spine 共用 portable locator；EPUB XHTML、CSS、font、image 由 Android WebView、iOS WKWebView 與 Desktop WebKit private scheme 按需讀取
- 內建 Local source（source `0`）：直接圖片與 TXT／ZIP／CBZ／EPUB；匯入以原子發布的 v2 manifest 拒絕部分或損毀內容
- MyAnimeList provider 目前明示停用，待補入正式 client ID 後再開放
- iOS Widget payload、iCloud Drive snapshot sync，以及 Android／iOS Reader 實體音量鍵接線
- Browse 啟動時先顯示本地與已安裝來源，再以有界 timeout 背景刷新遠端 repository；不再用全畫面 loading 阻塞瀏覽頁

## Cloudflare 端對端加密同步

Shinsou X 內含可自行部署的 Cloudflare Worker + D1 + 兩個隔離的 private R2 bucket + Durable Object 同步服務。App 的設定頁已接入首次 setup、使用者邀請、裝置 QR／短碼配對、裝置撤銷、Recovery Kit、即時／Lite catch-up，以及 checkpoint 建立與回退。Metadata 走經 Ed25519 驗證、ChaCha20-Poly1305 加密的 deterministic event／checkpoint；經使用者選擇且 `SYNC_BLOB` rights 明確允許的 immutable content body，另走可續傳、分塊、端對端加密的 body plane。Worker 不持有 workspace 明文金鑰、正文 plaintext 或 blob DEK。

本機寫入會先與 CRDT replica、HLC、draft/outbox 一起提交至 SQLite WAL，取得 server receipt 後才移除 outbox。Reader 會同步 logical page 或 Webtoon page 內的 normalized offset；離線、程序終止、WebSocket 中斷、事件重播與 cursor GC 都走可恢復路徑。撤銷或 Recovery 後會輪替獨立的新 epoch key，Recovery Kit 遺失時伺服器管理員也無法解密資料。

以下資料預設維持裝置本機：一般 download cache／queue、尚未明確加入 body sync 的 Local content、Local source 原始容器、extension packages、cookies、OAuth token、密碼、API key、tracker credentials 及平台專屬設定。Portable backup 在已連線 workspace 中還原或 reset 時，必須明確選擇「產生 mutations/tombstones 並同步所有裝置」或「先離開 workspace，只修改本機」，不會直接覆寫同步權威狀態。

伺服器實作、部署與本機測試位於 [`syncWorker`](syncWorker/README.md)，完整協定與信任邊界見[跨平台同步架構](docs/CROSS_PLATFORM_SYNC_ARCHITECTURE.md)。程式的 deterministic tests 不等同 Cloudflare 新帳戶部署、真實額度、各平台 secure-store 重啟與六組跨裝置矩陣已通過；這些仍屬正式標示 stable 前的外部上線 Gate。

Local source 支援 `txt`、`jpg`、`jpeg`、`png`、`webp`、`gif`、`avif`、`heic`、`bmp`，以及 `zip`、`cbz`、`epub`。EPUB 會保留 OCF／OPF、manifest、spine、nav、XHTML、CSS、font、image 與 rendition metadata；renderer 不會在開書時一次 hydration 全部資源。

## 擴充套件與網路

JVM（Android／Desktop）使用 Rhino，iOS 使用 JavaScriptCore。Browse、Reader 與 Download 共用同一套來源儲存與 request builder，因此來源 headers、cookies、Referer、per-source proxy 與 User-Agent 會沿用到圖片和下載請求。

- Desktop deterministic compatibility test 會載入相鄰 `shinsou_plugin` 的官方腳本並在 Rhino 初始化；這不會連正式來源網站。
- iOS Simulator test 覆蓋 JavaScriptCore 的同步 extension contract；任意第三方 Promise／async／`fetch` 腳本不保證相容。
- `AppSnapshot.extensionRepositories` 是可攜的 repository source of truth。升級時只會一次性遷移舊 KV 清單；之後 add／remove／default、備份還原與同步結果都會精確鏡像回 KV，不會再把 stale KV 無條件 union 回 snapshot。
- Extension 的 execution trust token 綁定 `{pluginId}:{versionCode}:{SHA-256}`，不代表 repository 作者身分已由 PKI 驗證。System-event grant 另以完整的 `(packageId, version, versionCode, SHA-256, SourceKey)` artifact／來源身分核准，不能與 execution token 混為一談。撤銷會立即卸載 runtime、保留 package 供重新授權或卸載；啟動時不會只因 bytes 仍符合 manifest digest 就重建已撤銷的授權。
- Source settings 可手動管理 cookie，或匯入 Netscape `cookies.txt`／常見 JSON 匯出。匯入器限制 1 MiB／500 筆，並驗證來源 domain、path、expiry 與 cookie 字元；平台 picker 會在配置完整 ByteArray 前先檢查 declared size。
- Plugin HTTP redirect 由共用層逐 hop 處理：限制 hop 數、拒絕 HTTPS 降級、跨 origin 丟棄 credentials／自訂秘密 header，並依新目標重新選 cookie。`Set-Cookie` 支援 `Max-Age`／`Expires`、精確 path 刪除與保守 suffix 防護；這套 suffix 規則不是完整 Public Suffix List。
- Android 以 WebView、iOS 以 non-persistent WKWebView 完成 Cloudflare／Web challenge 後擷取並驗證 cookie。macOS Desktop 會開啟獨立原生、non-persistent WKWebView 視窗，擷取該隔離 session 的 cookie 與真實 User-Agent；若 challenge request 明確屬於某來源且該來源已保存帳密，Host 只會在同源頁面、同源 form action 下填入並提交，帳密經 helper stdin 傳送而不放入 command line。Windows Desktop 只開外部瀏覽器，無法讀取或自動匯入 Safari／Chrome／Edge 的既有 cookie；可改用 `cookies.txt`／JSON 匯入或手動輸入。
- DNS over HTTPS 設定目前只會保存，不會改變 runtime DNS。專案刻意不採「直接連 IP 再覆寫 Host」的作法，因其無法安全保留 HTTPS SNI／憑證語義。

## Desktop

Desktop 是共用的 Compose Desktop 實作，目前支援 macOS 與 Windows；它不是 AppKit 或 WinUI 原生介面。目前包含：

- sidebar 與寬視窗雙欄 master/detail
- 系統 Menu Bar
- macOS 使用 `⌘`、Windows 使用 `Ctrl` 的主區切換、設定及結束快捷鍵
- 可選的關閉確認、視窗最小尺寸，以及 DMG／MSI／EXE 平台安裝包
- 鍵盤 Reader 操作與 app 前景期間的自動備份排程
- 敏感 plugin KV 使用 AES-256-GCM；master key 在 macOS 存入 Keychain，在 Windows 經目前使用者範圍 DPAPI 保護；Desktop app lock／secure screen 則明示為不可用

## 專案結構

- `composeApp/src/commonMain`：跨平台資料模型、repository、網路／外掛 runtime、下載、備份、同步與 Compose UI
- `composeApp/src/androidMain`：Android Activity、文件選擇器、Biometric、WebView、WorkManager 與持久化接線
- `composeApp/src/iosMain`：iOS framework entry、JavaScriptCore、Keychain、WKWebView、iCloud Drive、BGTask、文件選擇器與 Widget payload
- `composeApp/src/jvmCommonMain`：Android／Desktop 共用 Rhino JavaScript runtime
- `composeApp/src/desktopMain`：macOS／Windows Desktop host、Menu Bar、bounded 檔案選擇器、平台資料目錄與 Keychain／DPAPI 接線
- `iosApp`：SwiftUI host、Reader 音量鍵 bridge、Widget extension、entitlements 與 XcodeGen 規格
- `syncWorker`：Cloudflare Worker、D1 migrations、隔離的 checkpoint／content-body R2、WorkspaceHub Durable Object、部署／備份工具與伺服器測試

## 開始使用與建置

需求：JDK 17、Android SDK Platform 36；macOS Desktop 的 `run`、`desktopTest`、資源準備與 DMG 會透過 `xcrun --sdk macosx swiftc` 編譯原生 WKWebView helper，因此也需要可用的 Xcode Command Line Tools、macOS SDK 與 Swift compiler。iOS 另需完整 Xcode、XcodeGen 與適用的 Apple 開發簽章設定；MSI／EXE 需在 Windows 與 WiX Toolset 3.x 環境執行。公開 fork 需將 `iosApp/project.yml` 中的 Development Team 換成自己的團隊。

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

Android 產物位於 `composeApp/build/outputs/apk/debug/` 或
`composeApp/build/outputs/apk/release/`。專案的 GitHub Release 固定發布
`Shinsou-X-<version>-android-debug.apk`：它使用 debug key 簽章、保留
`android:debuggable=true`，只供測試，不是 production release build。CI runner 產生的 debug
key 不保證跨版本相同，因此新 APK 可能無法覆蓋安裝舊 APK；請先在 App 內建立備份，必要時解除安裝舊版再安裝。若要長期分發及原地升級，必須自行保存 keystore 並建置 release APK。
自行簽署 release APK 的環境變數與命令見 [GitHub Actions 發布](docs/RELEASING.md)。

GitHub Release 不建置或發布 iOS IPA。iOS 使用者／維護者必須在 macOS 安裝完整 Xcode
與 XcodeGen，執行上方 Simulator 命令，或以 `open iosApp/Shinsou.xcodeproj` 在 Xcode
選擇自己的 Development Team 後建置實機版本。真機、Archive、Ad Hoc、TestFlight 與 App
Store 分發所需的憑證、profiles、entitlements 與 Apple Developer 會籍均由自行建置者負責。

只有已公開的 GitHub Release 才代表有可下載的 Android／Desktop 安裝包；repository 內存在
workflow 或產物設計，不等於目前已發布版本。每個 tag release 固定包含 Android debug APK、
macOS DMG 與 Windows MSI／EXE，不包含 iOS IPA。若 Releases 頁面沒有項目，請依本節自行建置。

Windows Desktop 安裝包必須在 Windows x64 主機執行（不能由 macOS 交叉產生）：

```powershell
.\gradlew.bat :composeApp:packageReleaseMsi
.\gradlew.bat :composeApp:packageReleaseExe
```

產物位於 `composeApp/build/compose/binaries/main-release/msi/` 與
`composeApp/build/compose/binaries/main-release/exe/`。macOS 請使用上面的 `packageDmg` 產生 DMG。

完整環境、打包與驗證層級請見 [建置與驗證](docs/BUILDING.md)。

## 文件與相關專案

- [建置與驗證](docs/BUILDING.md)：環境需求、各平台建置、簽章與驗證清單
- [GitHub Actions 發布](docs/RELEASING.md)：`vX.Y.Z` 正式 tag、`vX.Y.Z-beta.N` prerelease、Android debug APK、DMG／MSI／EXE，以及 iOS 自行建置邊界
- [Windows 建置與發布](docs/WINDOWS.md)：Windows x64 環境、DPAPI、MSI／EXE、CI 與 smoke checklist
- [功能對齊狀態](docs/PARITY.md)：Android、iOS、macOS、Windows 的實作與外部驗證狀態
- [插件系統事件接口架構](docs/PLUGIN_SYSTEM_EVENT_ARCHITECTURE.md)：production V1 handler、exact-artifact grant、安全邊界與尚未接通的保留能力
- [跨平台同步架構](docs/CROSS_PLATFORM_SYNC_ARCHITECTURE.md)：事件模型、E2EE、配對、checkpoint、Recovery、quota 與驗收 Gate
- [Cloudflare Sync Worker](syncWorker/README.md)：本機測試、部署、D1/R2 bindings 與 API
- [shinsou_plugin](https://github.com/aluo96078/shinsou_plugin)：Shinsou X JavaScript 擴充套件儲存庫
- [Shinsou（停止開發）](https://github.com/aluo96078/shinsou)：舊 Swift／SwiftUI 版本的歷史封存

## 資料與安全邊界

- Android snapshot 與本地頁面位於 app-private files；credentials、cookies、OAuth token、proxy API key 等敏感 KV 使用 Android Keystore AES-GCM key 加密。
- iOS snapshot 位於 Application Support；credentials、cookies、OAuth token、proxy API key 與其他 secrets 由 Keychain 保存。
- Desktop snapshot 位於 `~/Library/Application Support/Shinsou`；敏感 KV 使用 AES-256-GCM，master key 存在 macOS Keychain。舊 `plugin-secrets.key` 只有在 Keychain 回讀及既有密文解密都成功後才會刪除；Keychain 失敗時不建立新的 file fallback。
- Windows snapshot 與內容位於 `%USERPROFILE%\ShinsouXData`，避開 MSI 安裝器管理的程式目錄；Windows MSI 使用 machine-scoped 安裝以避免 jpackage per-user 卸載器清理使用者資料。敏感 KV 同樣使用 AES-256-GCM，master key 只以目前使用者範圍 DPAPI protected blob 落盤。舊版 `%APPDATA%\ShinsouXData` 與 `%LOCALAPPDATA%\Shinsou X` 會在首次啟動時遷移至新目錄；若新目錄已存在，舊目錄會保留供手動合併。
- 舊版可攜 snapshot、自動備份與 iCloud snapshot 仍以 `AppSnapshot` 為資料模型；Content Backup v2 另保存 checksummed publication graph、body-free content metadata、legacy aliases 與 migration ledgers，並可選擇納入當下 rights 明確允許匯出的 immutable blobs。兩者都不包含已安裝的 JavaScript package、credentials、cookies、OAuth token、proxy API key 或其他 secrets。
- Local source 與已下載頁面的檔案 bytes 不在舊 snapshot envelope 內。Content Backup v2 未附 body 或 Cloudflare body sync 未獲授權時，目的裝置只還原 metadata/ref，並要求合法重新匯入、重新取得或授權後下載；不會虛構本機 blob 已存在。
- Download completion manifest 與 Local v2 manifest 都留在 app-private storage，不會隨 portable snapshot 移動。同步合併不採用遠端 `downloadQueue`，只保留本機 queue；備份還原也保留並清理本機 queue，而不啟動另一台裝置的下載工作。
- 自動備份是 app-private 原子快照，提供立即建立、列表、損毀標示、還原、刪除與 retention。Android 使用 WorkManager、iOS 使用 BGProcessingTask；實際背景執行時間由作業系統決定，Desktop 則只在 app 存活時以前景 scheduler 檢查。
- Cloudflare v2 只同步明確 allowlist 的領域 metadata；使用者選擇且 `SYNC_BLOB` 允許的 content body 走隔離、分塊、E2EE 的 R2 body plane。CRDT state、keyring、device directory pin、draft/outbox 與 cursor 留在獨立 sync store，不會塞進 portable `AppSnapshot`。
- 舊版 iOS iCloud 同步仍使用 `Documents/Shinsou/shinsou-sync.shinsoubackup` 單檔快照與 NSFileCoordinator；它不是 record-level CloudKit，也不能與 Cloudflare v2 同時作為寫入端。

## 使用責任

Shinsou X 只提供書庫、閱讀器與來源擴充機制，不營運或託管第三方漫畫內容。使用者應只安裝可信任的擴充套件、遵守所在地法律與內容來源的使用條款，並自行確認其存取內容的權利。

## 授權

本專案採用 [MIT License](LICENSE)。Copyright (c) 2026 Aluo.
