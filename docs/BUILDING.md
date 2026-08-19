# 建置與驗證

本文件把驗證分成 deterministic tests、target compile／package、Simulator／Desktop GUI 與真機／外部服務四層。前兩層通過只能證明程式與本地 fixture 可建置，不代表硬體按鍵、Apple entitlement、背景排程、真實 OAuth、Cloudflare 或來源網站已完成 smoke test。

## 共通環境

- macOS（iOS 與 DMG 建置必須）
- JDK 17
- Android SDK Platform 36、Build Tools 與 platform-tools
- Xcode（專案 deployment target 為 iOS 16）
- XcodeGen

專案使用 Gradle Wrapper，不需要另外安裝 Gradle。若 shell 內的 `ANDROID_SDK_ROOT` 指向無效 SDK，可使用 `env -u ANDROID_SDK_ROOT` 讓 Android Gradle Plugin 改讀 `local.properties` 或預設 SDK 路徑。

主要版本可在 `gradle/libs.versions.toml` 查閱；目前 Android `minSdk` 26、`compileSdk`／`targetSdk` 36，Kotlin/JVM target 為 17。

## Workspace 佈局

官方擴充套件 compatibility fixture 取自相鄰的 `shinsou_plugin`：

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

這會執行 common／Desktop 測試，涵蓋 repository、分類、下載 completion manifest／隱藏完成列、Reader navigation／來源特例、Local v2 manifest、ZIP traversal 防護、bounded document read、備份／自動備份、只保留本機 download queue 的 snapshot merge、extension repository reconciliation、執行授權撤銷、redirect／`Set-Cookie` 防護、Web challenge cookie 驗證、tracking adapter 與 Rhino extension contract。Workspace fixture 也會確認相鄰專案中的官方 JavaScript 腳本可在 Rhino 初始化。

測試使用 memory filesystem、mock transport 與本地 HTML／script fixture；不會登入 AniList／MAL、不會向正式來源站發 request，也不會真的通過 Cloudflare challenge。

### 2. 三平台 Kotlin 編譯與 Android APK

```bash
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Debug APK 位於 `composeApp/build/outputs/apk/debug/`。這些命令驗證 expect／actual 與平台 API 可編譯，包括三平台 bounded picker、Android volume-key dispatch、iOS Keychain 及 Desktop Security.framework binding；不會啟動 Activity、文件 provider、Keychain prompt、WebView、WKWebView 或背景 scheduler。

### 3. iOS Kotlin/Native tests

```bash
./gradlew :composeApp:iosSimulatorArm64Test
```

iOS test 覆蓋 JavaScriptCore 同步 extension contract 與共用測試。Simulator test 不會驗證實體音量鍵、系統音量／HUD、iCloud 帳號與 entitlement，也不代表 workspace 中的官方腳本已逐一在 JavaScriptCore 執行。

### 4. iOS Xcode Simulator app

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

### 5. Desktop 執行與 DMG

```bash
./gradlew :composeApp:run
./gradlew :composeApp:packageDmg
```

DMG 由 Compose Desktop 設定產生，bundle id 為 `dev.aluo.shinsoux`，並使用 `composeApp/src/desktopMain/resources/shinsou.icns`。Desktop 透過 JNA 直接呼叫 macOS Security.framework Keychain；打包成功只證明 linkage 與 package 設定，不代表 Keychain access prompt、簽章後存取或 legacy key migration 已人工檢查。

## iOS 簽章與 capability

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

- AniList client 已配置，共用 Tracking sheet 可開啟 OAuth、接受貼上的 redirect URL／access token，並支援搜尋、綁定、refresh、進度／狀態／分數／日期更新與登出。正式帳號流程仍需用外部網路與帳號 smoke。
- iOS 目前以貼上 OAuth callback／token 作為可靠保底，不能只靠 custom URL callback 假設登入已完成。
- 原版 MyAnimeList client ID 是 placeholder；KMP UI 會顯示未配置，未提供正式 client ID 前不能登入 MAL。
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
- AniList 真實帳號 OAuth、搜尋、綁定與進度同步

### Android 真機限定

- Reader 開啟且 Volume keys 啟用時，Volume Up／Down 對應上一頁／下一頁，只觸發一次且不改變系統音量或顯示 HUD
- 離開 Reader 或停用設定後，音量鍵恢復系統預設行為

### iOS 真機限定

- 實體 Volume Up／Down 對應上一頁／下一頁
- 離開 Reader、進入背景、音訊中斷與 media-services reset 後，使用者音量及 audio session 可恢復
- Reader 啟用期間的系統音量 HUD 行為
- App Group Widget timeline 與 iCloud Drive container

### Desktop GUI

- macOS Menu Bar、sidebar、寬視窗雙欄 layout 與最小視窗尺寸
- `⌘1`～`⌘5`、`⌘,`、`⌘Q`、最小化與關閉確認
- Updates Upcoming、Local import、Reader 鍵盤／章節切換
- Settings 的 Backup／Sync 說明、More 模式切換與 Browse pinned sources
- Cloudflare fallback 只開外部瀏覽器，且不誤報已匯入外部 cookie
- 首次保存敏感來源資料時確認 macOS Keychain 存取；若有舊 `plugin-secrets.key`，確認只有在 Keychain 回讀與既有 AES-GCM 密文驗證都成功後才移除舊檔
- Security settings 中 App lock／Secure screen 顯示 unavailable 且不可切換；Desktop 不應以固定成功的假驗證繞過 capability gate

## 文件匯入記憶體邊界

Android 使用同一個 `ParcelFileDescriptor.statSize` 與 `AutoCloseInputStream`，Desktop 先檢查 regular file／`Files.size` 再開 stream，iOS 則在 security-scoped URL 取得 `NSURLFileSizeKey` 後以 `NSFileHandle` 分塊讀取。三者都透過共用 bounded reader：先拒絕未知或超限 size、精確讀 declared bytes，再以 1-byte probe 偵測讀取期間 grow／shrink。

- cookie import：1 MiB
- 一般 snapshot／backup import：64 MiB
- Local 直接圖片：每檔 64 MiB
- Local archive 與整批 Local import：512 MiB

這避免在檢查前讀入任意大小的檔案，但 `ImportedDocument` 在通過限制後仍會配置一個上限內的完整 `ByteArray`；目前不是 end-to-end streaming import。無法回報可靠 size metadata 的 cloud／stream provider 會被拒絕，需以實際 provider 做 picker smoke。

## Local source 與備份注意事項

Local import 會把頁面複製到各平台 app-private storage，並限制不安全 archive path、加密 archive、ZIP64、多卷 archive、頁面／總容量與 entry 數量。所有頁面寫入並核對後才原子發布 v2 manifest；Reader 只接受 manifest 中連續、受控且與目錄精確一致的檔名。舊 v1 marker 只會在 canonical marker 與完整頁面集合一致時升級。`epub` 以 ZIP 圖片 archive 處理。

Download 會在所有 page／transform sidecar 完成並核對後，最後原子發布 completion manifest。Clear completed 只把完成 queue row 設成 hidden；manifest、下載徽章與離線內容仍保留。缺少、損壞或與檔案集合不一致的 manifest 不會被 Reader 當作完整離線章節。

Portable backup、automatic backup envelope 與 iCloud snapshot 以 `AppSnapshot` 為資料模型。它們會保留 extension repository 設定，但不內嵌已安裝的 JavaScript package、per-source plugin key-value state、Local／download page bytes、credentials、cookies、OAuth token 或 proxy API key；proxy key 在 encode 前 redaction，restore／sync 保留本機 secure-store 值。跨裝置還原後，擴充套件需重新安裝，Local source 需重新匯入原檔，遠端章節則需重新下載。

同步 conflict resolver 不重映射或套用遠端 `downloadQueue`，合併結果保留本機 queue；備份還原同樣保留並移除本機 dangling rows，不會啟動另一台裝置的下載工作。`AppSnapshot.extensionRepositories` 則是可攜 source of truth：舊 KV 清單只做一次性遷移，之後 snapshot 會精確覆蓋 KV mirror。Repository 與其他 entity 的同步合併目前沒有 deletion tombstone，因此 stale replica 仍可能在衝突時帶回已刪除 record；不可把這描述成 record-level CloudKit 等價物。
