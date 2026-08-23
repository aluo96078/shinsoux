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
.\gradlew.bat :composeApp:packageReleaseMsi
.\gradlew.bat :composeApp:packageReleaseExe
```

產物位置：

```text
composeApp/build/compose/binaries/main-release/msi/
composeApp/build/compose/binaries/main-release/exe/
```

MSI 與 EXE 必須在 Windows host 建立；macOS 無法交叉產生 Windows installer。

machine-scoped installer 固定把程式放在 `%ProgramFiles%\Shinsou X`，不提供安裝目錄選擇，與下方 `%USERPROFILE%\ShinsouXData` 使用者資料目錄分離。固定安裝根目錄可避免 jpackage `RemoveFolderEx` 在使用者選取既有目錄時遞迴清理該目錄；MSI 刻意不採 jpackage per-user 安裝，避免其卸載清理可能觸及使用者 profile。資料目錄位於使用者 profile 根目錄，避開 `%APPDATA%`／`%LOCALAPPDATA%` 樹及程式安裝路徑。升級與解除安裝可以替換或移除程式檔，但不得碰觸書庫、內容與 DPAPI protected blob；machine-scoped MSI 需要管理員或 UAC 提權。

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

1. checkout Shinsou X 與相鄰的 `shinsou_plugin`；
2. 執行 Desktop tests 與 Kotlin compile；
3. 不建立或上傳 Windows 安裝包。

一般 push／pull request 只做 Windows 編譯與測試。推送 `vMAJOR.MINOR.PATCH` tag 觸發的
`.github/workflows/release.yml` 才會在 Windows runner 建立 MSI／EXE，並與 Android APK、iOS IPA
及 macOS DMG 一起發布；該流程也會執行 Windows 產物完整性檢查。若需在本機驗證安裝器，仍可依照
上方的 WiX 建置命令手動產生。

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
