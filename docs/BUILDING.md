# 建置與驗證

本文件把驗證分成 deterministic tests、target compile／package、Simulator／Desktop GUI 與真機／外部服務四層。前兩層通過只能證明程式與本地 fixture 可建置，不代表硬體按鍵、Apple entitlement、背景排程、真實 OAuth、Cloudflare 或來源網站已完成 smoke test。

## 共通環境

- JDK 17（Desktop 安裝包需使用包含 `jpackage` 的完整 JDK）
- Android SDK Platform 36、Build Tools 與 platform-tools
- macOS Desktop：`run`、`desktopTest`、資源準備與 DMG 會使用 `xcrun --sdk macosx swiftc` 編譯原生 WKWebView challenge helper，因此需要 Xcode Command Line Tools、可用的 macOS SDK 與 Swift compiler
- iOS／iOS Simulator：需完整 Xcode（專案 deployment target 為 iOS 16）及 XcodeGen
- Windows 11 x64：Windows Desktop 執行與 MSI／EXE 的權威建置環境
- WiX Toolset 3.x：Compose Desktop／`jpackage` 建立 Windows MSI／EXE 時需要；`candle.exe` 與 `light.exe` 必須可由 `PATH` 找到

專案使用 Gradle Wrapper，不需要另外安裝 Gradle。若 shell 內的 `ANDROID_SDK_ROOT` 指向無效 SDK，可使用 `env -u ANDROID_SDK_ROOT` 讓 Android Gradle Plugin 改讀 `local.properties` 或預設 SDK 路徑。

主要版本可在 `gradle/libs.versions.toml` 查閱；目前 Android `minSdk` 26、`compileSdk`／`targetSdk` 36，Kotlin/JVM target 為 17。

Windows 可建置及執行 Android／Desktop target，但不能取代 Xcode；iOS App、Widget、簽章、Simulator 與真機部署仍必須在 macOS 完成。DMG 與 MSI／EXE 也必須各自在其目標作業系統建立，不能跨平台產生。

建立正式或 beta tag 時，GitHub Actions 會產生 Android debug APK、macOS DMG 與 Windows
MSI／EXE；不建置 iOS App，也不發布 IPA。iOS 必須在 macOS／Xcode 自行建置。版本格式、
公開產物與分發邊界請見 [GitHub Actions 發布](RELEASING.md)。本機或 CI 可使用
`-PreleaseVersion=X.Y.Z -PreleaseVersionCode=N` 覆寫 package version 與正整數 build
number；Android 若需顯示 prerelease suffix，可另傳
`-PreleaseDisplayVersion=X.Y.Z-beta.N`。Windows installer 還需傳入 tag parser 產生的
`-PwindowsPackageVersion=MAJOR.MINOR.BUILD`；公式及限制見發布文件。

目前原始碼中的預設版本為 `1.0.1-beta.4`：Android `versionName` 與應用內顯示使用完整
beta 版本，Android `versionCode`／iOS build number 為 `25600104`，Desktop／iOS 的
package／marketing version 為 `1.0.1`，Windows installer package version 為
`1.0.104`。上述 `-P` 參數只用於後續 tag 發布時覆寫這些預設值。

## Workspace 佈局

官方擴充套件 compatibility fixture 取自相鄰的
[`shinsou_plugin`](https://github.com/aluo96078/shinsou_plugin)：

```text
project/
├── shinsou/
├── shinsou_kmp/
└── shinsou_plugin/
```

`composeApp/src/desktopTest` 會把 `../../shinsou_plugin` 加入 test resources。若只複製 `shinsou_kmp`，需另外提供相同 fixture 或調整 resource path；否則官方腳本 compatibility test 不具完整輸入。

## 建議驗證順序

### 1. 共用與 Desktop deterministic tests

```bash
./gradlew :composeApp:desktopTest --rerun-tasks
```

這會執行 common／Desktop 測試，涵蓋 repository、分類、下載 completion manifest、統一 content transaction/blob recovery、TXT／完整 EPUB／圖片 reader、全文索引／TTS／註記 rights、Backup v2／[ShuYue](https://github.com/aluo96078/shuyue) transactional import、encrypted body sync、Reader navigation／來源特例、ZIP traversal 防護、extension v2／repository reconciliation、redirect／`Set-Cookie` 防護、Web challenge、tracking adapter 與 Rhino extension contract。Workspace fixture 也會確認相鄰專案中的官方 JavaScript 腳本可在 Rhino 初始化。在 macOS 上，此 task 會先編譯原生 WKWebView challenge helper；缺少 `xcrun`、macOS SDK 或 `swiftc` 時會在測試前失敗。

Cloudflare Worker 的協定、雙 R2 bucket 與維運工具另需執行：

```bash
cd syncWorker
npm test
npm run typecheck
npm run smoke
npm run ops:test
```

測試使用 memory filesystem、mock transport 與本地 HTML／script fixture；不會登入第三方帳號、不會向正式來源站發 request，也不會真的通過 Cloudflare challenge。

### 2. 各 target Kotlin 編譯與 Android APK

```bash
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Debug APK 位於 `composeApp/build/outputs/apk/debug/`。Tag workflow 也只發布這類
debug-signed、`android:debuggable=true` 的測試 APK，不是 production release build。
自 beta.5 起，官方 CI 使用 GitHub Actions secret 中固定的公開測試 signer；fingerprint 與
升級限制見 [發布文件](RELEASING.md)。beta.3 及更舊版本必須先備份並解除安裝一次，beta.5
之後才可直接覆蓋升級。需要 production identity 時，請自行保存 keystore 並執行
`assembleRelease`。這些命令驗證 expect／actual 與平台 API
可編譯，包括各平台 bounded picker、Android volume-key dispatch、iOS Keychain，以及
Desktop 的 macOS Security.framework／Windows DPAPI JNA binding；不會啟動 Activity、
文件 provider、Keychain prompt、DPAPI、WebView、WKWebView 或背景 scheduler。

### 3. iOS Kotlin/Native tests

```bash
./gradlew :composeApp:iosSimulatorArm64Test
```

iOS test 覆蓋 JavaScriptCore 同步 extension contract 與共用測試。Simulator test 不會驗證實體音量鍵、系統音量／HUD、iCloud 帳號與 entitlement，也不代表 workspace 中的官方腳本已逐一在 JavaScriptCore 執行。

### 4. iOS Xcode Simulator app

> GitHub Release workflow 不建置 iOS，也不提供 IPA。以下 iOS 步驟必須由使用者在
> macOS／Xcode 自行執行；實機或 Archive 還需自己的 Apple 開發帳號、憑證與
> provisioning profiles。

`iosApp/project.yml` 是 XcodeGen 的來源。修改 target、entitlement、Info.plist property 或 build setting 後必須重新產生 project：

```bash
cd iosApp
xcodegen generate
```

無簽章 Simulator build：

```bash
xcodebuild -project Shinsou.xcodeproj \
  -scheme Shinsou \
  -sdk iphonesimulator \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

這會一併驗證 SwiftUI host、Widget extension 與 Kotlin framework linkage，但無法驗證需要簽章或帳號的 capability。

### 5. Desktop 執行與平台安裝包

在 macOS 執行 Desktop、編譯並建立 DMG：

```bash
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:run
./gradlew :composeApp:packageDmg
```

DMG 由 Compose Desktop 設定產生，bundle id 為 `dev.aluo.shinsoux`，並使用 `composeApp/src/desktopMain/resources/shinsou.icns`。Gradle 會先以 `xcrun --sdk macosx swiftc` 將 `composeApp/src/desktopMain/swift/ShinsouWebChallenge/main.swift` 編譯成隨 App 封裝的 helper；`run` 與 `desktopTest` 也依賴同一 task。Release workflow 會掛載 DMG，確認縮減後的 `sqlite-jdbc` 同時包含 `org/sqlite/JDBC.class` 與 service descriptor，再以隔離 home 執行 `--verify-desktop-startup`；只有 SQLite database 建立且 Compose 下一幀寫出隨機 marker 才算通過。macOS Desktop 透過 JNA 直接呼叫 Security.framework Keychain；這項自動檢查仍不代表 WKWebView 真實來源 challenge、Keychain access prompt、簽章後存取或 legacy key migration 已人工檢查。

macOS Keychain 的「永遠允許」依賴 App 的 designated requirement。未設定憑證時，Compose
只能建立 ad-hoc 簽章；其 requirement 是每次建置都會改變的 `cdhash`，因此更新後必須重新
輸入 Mac 密碼。開發機可把固定憑證的完整名稱放在使用者層級的
`~/.gradle/gradle.properties`（該檔不可提交）。不能只填 SHA-1 指紋：

```properties
shinsouMacOsSigningIdentity=<完整 Apple Development 或 Developer ID Application 名稱>
# 只有憑證不在預設 login keychain 時才需要：
# shinsouMacOsSigningKeychain=/absolute/path/to/build.keychain-db
```

CI 可改用 `SHINSOU_MACOS_SIGNING_IDENTITY` 與選用的
`SHINSOU_MACOS_SIGNING_KEYCHAIN`。Compose Desktop 內建 signer 只支援 Developer ID／Mac
App Store identity；設定 Apple Development 時，Gradle 會在 `createDistributable` 或
`createReleaseDistributable` 產生 App 後，透過 `scripts/sign_macos_app.sh` 保留 JVM
entitlements 並覆上固定開發簽章。由於 jpackage 在包裝 DMG 時會再次改成 ad-hoc 簽章，
`scripts/sign_macos_dmg.sh` 會在最終映像內再次簽署並驗證後才原子替換 Gradle 產物。這只
適合安裝在開發機的本機測試版；公開 DMG 應使用同一個長期保存的 Developer ID
Application 憑證並完成 notarization。

第一次從 ad-hoc 版切換到固定簽章版時，既有 Keychain 項目仍會再要求一次授權；按下
「永遠允許」後，同一簽章身分與 bundle id 的後續更新不應再次詢問。更換憑證或退回 ad-hoc
簽章都會再次觸發授權。App 會先以禁止互動的方式讀取 Keychain；只有 macOS 明確回報需要
使用者授權時，才顯示用途說明並觸發系統密碼提示。不得把 Keychain ACL 放寬為允許所有程式存取。

在 Windows 11 x64 的 PowerShell／Windows Terminal 執行 Desktop build、test 與 app：

```powershell
.\gradlew.bat :composeApp:compileKotlinDesktop
.\gradlew.bat :composeApp:desktopTest --rerun-tasks
.\gradlew.bat :composeApp:run
```

使用 WiX Toolset 3.x 建立 release MSI／EXE：

```powershell
.\gradlew.bat :composeApp:createReleaseDistributable
.\gradlew.bat :composeApp:packageReleaseMsi
.\gradlew.bat :composeApp:packageReleaseExe
```

產物位於：

```text
composeApp/build/compose/binaries/main-release/msi/
composeApp/build/compose/binaries/main-release/exe/
```

精簡的 Desktop runtime 明確包含 `jdk.accessibility`。若 Windows 已啟用 Java Access
Bridge 而 runtime 缺少此模組，AWT 會在視窗出現前終止，jpackage launcher 通常只顯示
`Failed to launch JVM`。另一個啟動前故障來源是 shrinker 移除由 JDBC service loader／JNI
間接使用的 Xerial SQLite 類別；release 規則會保留 `org.sqlite`，程式也會明確初始化 driver。
Windows CI 會強制設定 Access Bridge，對 release app image 執行 runtime 與完整 startup
probe；release workflow 再靜默安裝 MSI 並對已安裝程式重跑。完整 probe 必須建立
`%USERPROFILE%\ShinsouXData\SyncV2\local-sync.db`、渲染 Compose 首幀、寫出隨機 marker，
並確認執行中的原生視窗已載入品牌 icon 且沒有額外的 Windows menu bar。Runtime probe
另會從縮減後 JAR 執行 ShuYue 隔離資料與權限的 durable round-trip，防止 ProGuard 導致
正式包無法安裝已審核插件。

Windows installer 採 machine-scoped install，程式固定位於 `%ProgramFiles%\Shinsou X`，不提供安裝目錄選擇，並建立桌面捷徑與開始功能表項目；穩定的 UpgradeCode 與單調的 `MAJOR.MINOR.(PATCH × 100 + stage)` package version 讓後續版本取得新 ProductCode 並覆蓋升級，而不是並存或被判定為相同版本。beta.3 錯誤重用了 `1.0.0`；修復版的 release workflow 會先安裝公開 beta.3 MSI，再驗證新 MSI 移除舊 ProductCode。固定安裝根目錄是為了避免 jpackage `RemoveFolderEx` 在使用者選取既有目錄時遞迴清理該目錄；將 MSI 改為 machine scope 則避開 per-user 卸載器可能清理使用者 profile。程式安裝目錄與 `%USERPROFILE%\ShinsouXData` 使用者資料目錄完全分離，資料根目錄也避開 `%APPDATA%`／`%LOCALAPPDATA%`。舊版 `%APPDATA%\ShinsouXData` 與 `%LOCALAPPDATA%\Shinsou X` 會在新目錄不存在時於首次啟動遷移；若新目錄已存在，舊目錄會保留供手動合併。Windows Desktop 的敏感 plugin KV 仍使用 AES-256-GCM，但 master key 只以目前使用者範圍 DPAPI 保護後寫入 `%USERPROFILE%\ShinsouXData\plugin-secrets.dpapi`；未使用 machine scope，也沒有明文 file fallback。只有 Windows 原生執行與 native DPAPI round-trip test 能驗證這條路徑，macOS 上的 Desktop compile 不等同於 DPAPI 已驗證。

## iOS 簽章與 capability

本專案只提供 iOS 原始碼與建置設定，不提供官方 IPA。Simulator 可使用上方無簽章命令；
真機請以 Xcode 開啟 `iosApp/Shinsou.xcodeproj`，替 App 與 Widget 選擇自己的 Team 後
Build／Run。Archive、Ad Hoc、TestFlight 或 App Store 分發則由自行建置者管理對應的
distribution certificate、profiles 與 export method。

實機／Archive 目前使用 Team `5UKY38ZLK6`；若改用其他 Apple 開發者帳號，需在 Xcode 更新 Team，並建立與以下 identifier 相符的 provisioning：

- App：`dev.aluo.shinsoux`
- Widget：`dev.aluo.shinsoux.widget`
- App Group：`group.dev.aluo.shinsoux`
- iCloud container：`iCloud.dev.aluo.shinsoux`

來源檔是 `iosApp/Shinsou/Shinsou.entitlements`、`iosApp/ShinsouWidgetExtension/ShinsouWidgetExtension.entitlements` 與 `iosApp/project.yml`。若組織使用不同 bundle prefix，需同步修改 app、Widget、App Group、iCloud container、Info.plist／XcodeGen 設定；只改 app target 會使 Widget、Keychain 或 iCloud capability 無法簽章。

Apple Personal Team 不支援 iCloud／App Group capability，因此 Debug 真機部署使用 `Shinsou/Shinsou.debug.entitlements` 與 `ShinsouWidgetExtension/ShinsouWidgetExtension.debug.entitlements`；Release 仍使用完整 entitlements，以保留正式版 iCloud 與 Widget 功能。

iCloud Drive 實作會存取：

```text
Documents/Shinsou/shinsou-sync.shinsoubackup
```

它使用 `NSFileCoordinator` 讀寫單一 `BackupEnvelope` 並在共用層合併 snapshot。這不是 record-level CloudKit；無簽章 Simulator 只能確認程式可編譯，不能確認 ubiquity container 可用。

## 平台背景工作

- Android 自動備份使用 unique periodic WorkManager work，沒有電源或網路 constraint。
- iOS 自動備份註冊 `dev.aluo.shinsoux.backup` 的 BGProcessingTask，`requiresExternalPower` 與 `requiresNetworkConnectivity` 皆為 `false`。
- Android／iOS 的間隔與 `earliestBeginDate` 都只是排程條件；作業系統決定實際執行時間。
- Desktop 使用前景 coroutine scheduler，每 15 分鐘檢查一次 due 狀態；app 關閉後不會在背景執行。
- 一般來源更新已有手動共用流程，但 Android／iOS 尚未接 OS-managed 自動背景來源更新 scheduler。

## 外部服務設定

- [舊版 Shinsou](https://github.com/aluo96078/shinsou) 的 MyAnimeList client ID 是 placeholder；KMP UI 會顯示未配置，未提供正式 client ID 前不能登入 MAL。
- 正式來源站可能要求登入、Cloudflare、地區條件或 rate limit，不應作為 deterministic CI 健康檢查。
- DNS over HTTPS toggle 目前只保存設定。沒有為 OkHttp、Darwin 與 CIO 接入可保留原 hostname、TLS SNI 與憑證驗證的 resolver，因此 runtime 不會啟用 DoH。

## 手動 smoke checklist

完成 compile/test matrix 後，依發布平台執行以下人工檢查。若沒有執行，不應在文件或 release note 宣稱已真機驗證。

### Android／iOS

- Library 多分類、Always Ask 收藏分類、批次分類與持久化
- Browse pin/unpin、來源偏好、登入／登出、手動 cookie 與 `cookies.txt`／JSON 匯入
- 新增／刪除／預設 extension repository 後重新啟動，並在備份還原／同步後確認 Browse 使用 snapshot 清單而非 stale KV
- 安裝 extension 後撤銷執行授權，確認 runtime／source 立即消失、package 仍可重新授權或卸載，重啟不會自行恢復已撤銷授權
- Android WebView／iOS non-persistent WKWebView challenge；只匯入符合來源 domain/path 且未過期的 cookie
- Local source 分別匯入直接圖片、ZIP、CBZ、EPUB，重啟後離線閱讀
- 使用超限檔案、無 size metadata 的 cloud provider 與讀取期間變更大小的檔案，確認 picker 會拒絕而非先配置無界 ByteArray
- Reader 四種 reading mode、章末切換、章節清單、retry、以瀏覽器開啟本章、離線頁面與來源特例；下載完成後執行 Clear completed，確認只隱藏 queue 列且仍可離線閱讀
- 保存 credentials／cookies／OAuth token／proxy API key 後重啟，確認 Android Keystore 或 iOS Keychain 能解密，且 plaintext legacy copy 已移除
- 自動備份立即建立／列表／還原／刪除，並觀察 OS 背景排程

### Android 真機限定

- Reader 開啟且 Volume keys 啟用時，Volume Up／Down 對應上一頁／下一頁，只觸發一次且不改變系統音量或顯示 HUD
- 離開 Reader 或停用設定後，音量鍵恢復系統預設行為

### iOS 真機限定

- 實體 Volume Up／Down 對應上一頁／下一頁
- 離開 Reader、進入背景、音訊中斷與 media-services reset 後，使用者音量及 audio session 可恢復
- Reader 啟用期間的系統音量 HUD 行為
- App Group Widget timeline 與 iCloud Drive container

### Desktop GUI（macOS／Windows）

- Menu Bar、sidebar、寬視窗雙欄 layout 與最小視窗尺寸
- macOS 的 `⌘1`～`⌘5`、`⌘,`、`⌘Q`，以及 Windows 的 `Ctrl+1`～`Ctrl+5`、`Ctrl+,`、`Ctrl+Q`
- 最小化、一般關閉與 Alt+F4 關閉確認
- Updates Upcoming、Local import、Reader 鍵盤／章節切換
- Settings 的 Backup／Sync 說明、More 模式切換與 Browse pinned sources
- macOS Web challenge 開啟獨立 non-persistent WKWebView 視窗；確認 seed cookie、擷取後的 cookie／真實 User-Agent 只寫回該來源，關閉後不保留可供其他 challenge 共用的 WebKit session
- macOS 只有在 challenge request 明確帶入該來源已保存帳密時，才會於同源頁面與同源 form action 自動填入並提交；跨 origin 頁面／form 必須拒絕，帳密不可出現在 process command line、log 或錯誤訊息
- macOS challenge 不會讀取 Safari／Chrome 的 cookie database；Windows fallback 只開外部瀏覽器，且不誤報已匯入 Edge／Chrome 等外部 cookie
- macOS 首次保存敏感來源資料時確認 Keychain 存取；若有舊 `plugin-secrets.key`，確認只有在 Keychain 回讀與既有 AES-GCM 密文驗證都成功後才移除舊檔
- Security settings 中 App lock／Secure screen 顯示 unavailable 且不可切換；Desktop 不應以固定成功的假驗證繞過 capability gate

### Windows x64 安裝與 DPAPI

- 以管理員與非管理員帳號測試 MSI 及 EXE 的首次安裝、UAC 提權、固定 `%ProgramFiles%\Shinsou X` 安裝目錄、桌面捷徑、開始功能表啟動與解除安裝
- 使用前一版安裝包後再安裝新版，確認 stable upgrade UUID 會原地升級而非產生並存安裝；首次啟動確認 `%APPDATA%\ShinsouXData` 與 `%LOCALAPPDATA%\Shinsou X` 會遷移至 `%USERPROFILE%\ShinsouXData`，解除安裝後確認新資料目錄仍保留
- 首次保存 credentials／cookies／OAuth token／proxy API key 後重新啟動，確認同一 Windows 使用者可透過 DPAPI 解密，且磁碟上只有 protected `plugin-secrets.dpapi`，沒有明文 master-key fallback
- 改用另一個 Windows 使用者或損毀／替換 DPAPI blob，確認解密會 fail closed，不會靜默產生新 key 覆蓋仍需解密的資料
- 以 `%LOCALAPPDATA%`、使用者名稱與匯入路徑含空白、中文及非 ASCII 字元的環境測試書庫、擴充套件安裝、全域搜尋、Local import、下載與備份還原
- 在 100%、150%、200% DPI 與 Windows Defender 即時掃描開啟時檢查冷啟動、大書庫搜尋、擴充套件安裝及大量小檔下載

## 文件匯入記憶體邊界

Android 使用同一個 `ParcelFileDescriptor.statSize` 與 `AutoCloseInputStream`，Desktop 先檢查 regular file／`Files.size` 再開 stream，iOS 則在 security-scoped URL 取得 `NSURLFileSizeKey` 後以 `NSFileHandle` 分塊讀取。三者都透過共用 bounded reader：先拒絕未知或超限 size、精確讀 declared bytes，再以 1-byte probe 偵測讀取期間 grow／shrink。

- cookie import：1 MiB
- 一般 snapshot／backup import：64 MiB
- Local 直接圖片：每檔 64 MiB
- Local archive 與整批 Local import：512 MiB

這避免在檢查前讀入任意大小的檔案，但 `ImportedDocument` 在通過限制後仍會配置一個上限內的完整 `ByteArray`；目前不是 end-to-end streaming import。無法回報可靠 size metadata 的 cloud／stream provider 會被拒絕，需以實際 provider 做 picker smoke。

## Local source 與備份注意事項

圖片、ZIP 與 CBZ import 會把頁面複製到各平台 app-private storage，並限制不安全 archive path、加密 archive、ZIP64、多卷 archive、頁面／總容量與 entry 數量。所有頁面寫入並核對後才原子發布 v2 manifest；Reader 只接受 manifest 中連續、受控且與目錄精確一致的檔名。舊 v1 marker 只會在 canonical marker 與完整頁面集合一致時升級。

EPUB 不走圖片 archive 相容路徑。它由 typed acquisition pipeline 解析 OCF／OPF、manifest、spine、nav／NCX 與 encryption metadata，保留原始 package、XHTML、CSS、font、image、relative URL 與 rendition properties，並把 immutable resources 與 shared SQLite metadata／refs 原子提交。Reader 再由 Android WebView、iOS WKWebView 或 Desktop JavaFX WebKit 透過 app-private scheme 按需載入資源；不支援的 protection scheme 會 fail closed。

Download 會在所有 page／transform sidecar 完成並核對後，最後原子發布 completion manifest。Clear completed 只把完成 queue row 設成 hidden；manifest、下載徽章與離線內容仍保留。缺少、損壞或與檔案集合不一致的 manifest 不會被 Reader 當作完整離線章節。

舊版 portable snapshot、automatic backup envelope 與 iCloud snapshot 仍以 `AppSnapshot` 為資料模型。Content Backup v2 則保存 checksummed publication／manifest／rights graph，以及 body-free content metadata、legacy aliases 與 migration ledgers，並可依當下 `EXPORT` rights 明確選擇納入 immutable content blobs；metadata-only restore 不會宣告缺失 body 已存在。auxiliary rows 與 portable graph 由同一 restore transaction 驗證／提交，舊 v2 archive 缺少該 optional 欄位時仍可 decode。兩種格式都不包含已安裝的 JavaScript package、per-source plugin key-value state、credentials、cookies、OAuth token、proxy API key 或其他 secrets；proxy key 在 encode 前 redaction，restore／sync 保留本機 secure-store 值。未納入 v2 body、rights 不允許 export，或只還原舊 snapshot 時，目的裝置仍需合法重新匯入、重新取得或下載內容。

同步 conflict resolver 不重映射或套用遠端 `downloadQueue`，合併結果保留本機 queue；備份還原同樣保留並移除本機 dangling rows，不會啟動另一台裝置的下載工作。`AppSnapshot.extensionRepositories` 則是可攜 source of truth：舊 KV 清單只做一次性遷移，之後 snapshot 會精確覆蓋 KV mirror。Repository 與其他 entity 的同步合併目前沒有 deletion tombstone，因此 stale replica 仍可能在衝突時帶回已刪除 record；不可把這描述成 record-level CloudKit 等價物。
