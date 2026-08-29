# Windows 建置與發布

Shinsou X 的 Windows 版本使用既有 Compose Desktop JVM target，支援 Windows 10／11 x64。書庫、Reader、下載、備份、Rhino 擴充套件與主要 Compose UI 皆與 macOS Desktop 共用；Windows 專屬層負責資料目錄、DPAPI 安全儲存、快捷鍵與安裝包。

## 環境需求

- 64-bit Windows 10 或 Windows 11
- JDK 17（需包含 `jpackage`）
- Android SDK Platform 36；目前 Desktop 與 Android 位於同一 Gradle module
- Git
- WiX Toolset 3.x（建立 MSI／EXE 時需要）

專案的 Desktop compatibility tests 會載入相鄰的官方擴充套件 fixture，建議使用以下目錄佈局：

```text
project/
├── shinsou_kmp/
└── shinsou_plugin/
```

若只需編譯程式，可略過 `shinsou_plugin`；完整 `desktopTest` 則必須提供 fixture。

## 本機建置

在 PowerShell 或 Windows Terminal 中執行：

```powershell
git clone https://github.com/aluo96078/shinsoux.git shinsou_kmp
git clone https://github.com/aluo96078/shinsou_plugin.git shinsou_plugin
cd shinsou_kmp

.\gradlew.bat :composeApp:desktopTest
.\gradlew.bat :composeApp:compileKotlinDesktop
.\gradlew.bat :composeApp:run
```

建立 release installer：

```powershell
.\gradlew.bat :composeApp:createReleaseDistributable
.\gradlew.bat :composeApp:packageReleaseMsi
.\gradlew.bat :composeApp:packageReleaseExe
```

產物位置：

```text
composeApp/build/compose/binaries/main-release/msi/
composeApp/build/compose/binaries/main-release/exe/
```

MSI 與 EXE 必須在 Windows host 建立；macOS 無法交叉產生 Windows installer。

打包設定會把 `jdk.accessibility` 明確加入精簡 runtime。Windows 啟用 Java Access Bridge
時，AWT 會在啟動階段動態載入此模組；若遺漏，jpackage 只會顯示
`Failed to launch JVM`，不會顯示底層 `AccessBridge` 錯誤。Release shrinker 也會保留
`org.sqlite`，避免 JDBC service provider 及 JNI 類別被當成未使用而移除。一般 Windows
workflow 會在強制啟用 Access Bridge 的條件下，對 release app image 執行 runtime 及完整
startup probe；tag release workflow 還會靜默安裝 MSI，再執行同一項檢查。完整 probe 使用
隔離 `%USERPROFILE%`，要求 SQLite database 建立並在 Compose 首幀後寫出隨機 marker，防止
再次發布只能打包、卻在啟動時顯示 `Failed to launch JVM` 的安裝程式。

machine-scoped installer 固定把程式放在 `%ProgramFiles%\Shinsou X`，不提供安裝目錄選擇，與下方 `%USERPROFILE%\ShinsouXData` 使用者資料目錄分離。固定安裝根目錄可避免 jpackage `RemoveFolderEx` 在使用者選取既有目錄時遞迴清理該目錄；MSI 刻意不採 jpackage per-user 安裝，避免其卸載清理可能觸及使用者 profile。資料目錄位於使用者 profile 根目錄，避開 `%APPDATA%`／`%LOCALAPPDATA%` 樹及程式安裝路徑。升級與解除安裝可以替換或移除程式檔，但不得碰觸書庫、內容與 DPAPI protected blob；machine-scoped MSI 需要管理員或 UAC 提權。

MSI／EXE 使用 `MAJOR.MINOR.(PATCH × 100 + stage)` 的 Windows package version，其中 beta N
的 stage 為 N、正式版為 99；例如 beta.4 是 `1.0.4`。穩定 UpgradeCode 配合每版不同的
ProductCode 才能原地升級。Beta.2／beta.3 曾同時使用 `1.0.0`，導致 beta.3 不被 Windows
視為升級；修復版已改用單調版本，CI 會先安裝公開 beta.3 再驗證舊 ProductCode 被取代。

## 儲存與安全

Windows 資料預設位於：

```text
%USERPROFILE%\ShinsouXData
```

- `shinsou-state.json` 保存書庫、設定與可攜 snapshot 狀態。
- `Content` 保存 Local source、擴充套件 package 與下載頁面。
- 舊版若仍有 `%APPDATA%\ShinsouXData` 或 `%LOCALAPPDATA%\Shinsou X`，首次啟動會在新目錄不存在時先整體遷移；若新舊目錄同時存在，舊目錄會保留供手動合併，避免覆蓋資料。
- 敏感 plugin KV 使用 AES-256-GCM；AES master key 經目前 Windows 使用者範圍的 DPAPI 保護後，才寫入 `plugin-secrets.dpapi`。
- 程式不使用 `CRYPTPROTECT_LOCAL_MACHINE`，因此其他 Windows 使用者及直接複製到另一台電腦的 blob 無法解密。
- macOS 仍使用既有 `~/Library/Application Support/Shinsou` 與 Keychain，不會因 Windows 支援而搬移現有資料。

跨 macOS／Windows 搬移資料應使用 Shinsou X 備份功能。可攜備份不包含密碼、Cookie、OAuth token、已安裝擴充套件或漫畫頁面，因此目的裝置仍需重新登入、安裝擴充套件及重新匯入／下載內容。

## CI 與簽章

`.github/workflows/windows.yml` 會在 Windows x64 runner：

1. checkout Shinsou X 與相鄰的 [`shinsou_plugin`](https://github.com/aluo96078/shinsou_plugin)；
2. 執行 Desktop tests 與 Kotlin compile；
3. 建立 release app image，並在強制啟用 Java Access Bridge 時執行 SQLite／首幀啟動檢查；
4. 不建立或上傳 Windows MSI／EXE。

一般 push／pull request 會做 Windows 編譯、測試與 app image 啟動檢查。推送 `vMAJOR.MINOR.PATCH` 或
`vMAJOR.MINOR.PATCH-beta.N` tag 觸發的
`.github/workflows/release.yml` 才會在 Windows runner 建立 MSI／EXE，並與 Android debug
APK 及 macOS DMG 聚合發布；beta tag 會建立 GitHub prerelease。Release workflow 不建置
iOS，也不發布 IPA；iOS 必須在 macOS／Xcode 自行建置。該流程也會核對 ProductVersion、
ProductCode、UpgradeCode，靜默安裝 MSI，並啟動已安裝程式的完整 startup probe；第一個
修復版另包含從公開 beta.3 覆蓋升級的測試，正式 `v1.0.0` 也必須通過從公開 beta.5
覆蓋升級的測試。若需在本機驗證安裝器，
仍可依照上方的 WiX 建置命令手動產生。

Release workflow 產物目前是未簽章 installer。公開正式版本應另以受信任的 Authenticode 憑證簽章；否則 Windows SmartScreen 可能顯示未知發行者警告。請勿將 PFX、密碼或簽章 token 提交至 repository。

## Windows smoke checklist

- 非管理員首次安裝、啟動、覆蓋升級與解除安裝；解除安裝後使用者資料仍保留
- 首次寫入敏感來源資料、重啟後解密，以及損毀 DPAPI blob 的 fail-closed 行為
- `%LOCALAPPDATA%`、匯入路徑及使用者名稱含空白、中文或非 ASCII 字元
- `Ctrl+,`、`Ctrl+1`～`Ctrl+5`、`Ctrl+Q`、方向鍵、Page Up／Down、Escape 與 Alt+F4
- 100%、150%、200% DPI、多螢幕、中文輸入法與滑鼠返回鍵
- 擴充套件安裝／更新／卸載、全域搜尋、下載、備份還原、本地 ZIP／CBZ／EPUB 與 Reader
- Windows Defender 即時掃描下的冷啟動、大書庫搜尋、擴充安裝及大量小檔下載

Windows 可建置 Android 與 Desktop target，但不能取代 Xcode。iOS App、Widget、簽章與真機部署仍需 macOS。
