# GitHub Actions 發布

推送格式為 `vMAJOR.MINOR.PATCH` 或 `vMAJOR.MINOR.PATCH-beta.N` 的 tag 會觸發
`.github/workflows/release.yml`。流程在 Linux 建置 Android debug APK、在 macOS 建置 DMG 與
未簽章的 iOS IPA、在 Windows 建置 MSI／EXE；所有必要產物與驗證通過後，才會發布同一個 GitHub
Release（任一平台失敗，包括 iOS，都不會公開）。`ios-unsigned.yml` 只保留為手動重建既有 tag 的 IPA 之用。Beta 會建立 GitHub prerelease，且不會標記為
Latest。

例如：

```bash
git tag -a v1.0.0 -m "Shinsou X 1.0.0"
git push origin v1.0.0

# Beta prerelease
git tag -a v1.1.0-beta.1 -m "Shinsou X 1.1.0 beta 1"
git push origin v1.1.0-beta.1
```

正式版本必須是三段純數字；beta 序號 `N` 限 `1..98`。為同時符合 MSI 與 Android 的
單調 build number，major 不得超過 81、minor 不得超過 255、patch 不得超過 654，且不可
使用 `v0.0.0`。Android `versionName` 與產物名稱使用完整顯示版本；Desktop
`packageVersion` 使用不含 beta suffix 的三段 package version。

Android `versionCode` 使用以下公式；自行建置 iOS 時，也可將同一數字用作
`CURRENT_PROJECT_VERSION`：

```text
ordinal = ((MAJOR × 256 + MINOR) × 1000 + PATCH)
build = ordinal × 100 + stage
stage = beta N 時為 N；正式版為 99
```

因此同一 package version 的 `beta.1…beta.98` 都小於正式版，下一個 patch 的 beta 又大於
前一個正式版。Tag 必須按此順序遞增，不能發布較新版後再發布較小版本或 beta 序號。

Windows Installer 只能使用 `MAJOR.MINOR.BUILD`，因此 MSI／EXE 使用另一個單調版本：

```text
windows build = PATCH × 100 + stage
windows package version = MAJOR.MINOR.windows build
```

例如 `v1.0.0-beta.4` 是 `1.0.4`，`v1.0.0` 是 `1.0.99`，而
`v1.0.1-beta.1` 是 `1.0.101`。這也是 patch 上限為 654 的原因：Windows 的 BUILD 不得超過
65535。穩定的 UpgradeCode 配合每個 package version 產生的新 ProductCode，讓 MSI 能辨識
並取代舊版；不可讓兩個公開 tag 重用相同 Windows package version。

## 發布前準備

1. 把 `composeApp/build.gradle.kts` 的預設版本（`releaseDisplayVersion`、`windowsPackageVersion`、
   `releaseVersionCode`）與 `iosApp/project.yml` 的 `CURRENT_PROJECT_VERSION` 改成新版本。應用內顯示的
   版本由 Gradle 產生，不需要改其他原始碼。
2. 在 `release.yml` 的「Install previous Windows upgrade baseline」步驟加入新 tag：`if:` 清單與
   基準分支（前一個公開版本、其 MSI 的 SHA-256 與 ProductCode）。沒有加入的 tag 會略過升級測試。
3. 在 `release.yml` 的「Prepare release notes」加入該版本的說明。

需要的 repository secrets：

- `RELEASE_PAT`：具 repository `contents: write` 權限的 token，Publish job 用它建立並公開 GitHub Release。
- `ANDROID_PUBLIC_DEBUG_KEYSTORE_BASE64`：固定的公開 Android 測試 signer。

## 發布產物

每個 tag workflow 都必須建立以下檔案，並附加涵蓋五個二進位產物的
`SHA256SUMS.txt`：

```text
Shinsou-X-<version>-android-debug.apk
Shinsou-X-<version>-ios-unsigned.ipa
Shinsou-X-<version>-macos-<arch>.dmg
Shinsou-X-<version>-windows-x64.msi
Shinsou-X-<version>-windows-x64.exe
SHA256SUMS.txt
```

macOS 架構以 runner 的實際 `arm64` 或 `x64` 命名。各 producer job 也會保留 14 天的
Actions artifact；GitHub Release assets 是長期下載來源。Android、macOS 或 Windows
任一必要產物缺少、空白或驗證失敗，workflow 都不會公開 Release。

### Android debug APK 的限制

公開的 Android 檔名刻意包含 `android-debug`。Workflow 會驗證 APK 簽章、application ID、
tag 對應的 `versionName`／`versionCode`，以及 `android:debuggable=true`。它是方便測試的
debug build，不是使用正式 keystore 簽章的 production release APK。

自 `v1.0.0-beta.5` 起，官方 workflow 從 repository secret 還原專用的固定公開測試 key，
並拒絕 signer SHA-256 不符合下列 fingerprint 的 APK：

```text
27:73:A2:63:48:F6:3B:F9:48:6C:44:9D:73:62:E0:86:
32:0C:0A:89:63:A4:C1:77:8C:EB:28:8C:CA:F2:DF:5A
```

這把 key 只提供 beta 測試 APK 的連續覆蓋升級，不是 production identity。Beta.3 及更舊的
APK 由 GitHub-hosted runner 臨時產生不同 signer；升到 beta.5 前必須先在 App 內備份、
解除安裝舊版一次，再安裝 beta.5。beta.5 之後的官方 debug APK 可直接升級。正式商店／
企業分發仍應自行建立並安全保存 release keystore，再於自己的受控環境執行：

```bash
export ANDROID_KEYSTORE_PATH=/absolute/path/to/release.jks
export ANDROID_KEYSTORE_PASSWORD='...'
export ANDROID_KEY_ALIAS='...'
export ANDROID_KEY_PASSWORD='...'

./gradlew :composeApp:assembleRelease \
  -PreleaseVersion=1.0.0 \
  -PreleaseDisplayVersion=1.0.0 \
  -PreleaseVersionCode=25600099
```

不得提交 keystore 或密碼。官方測試 keystore 只存在 GitHub Actions secret，repository 中
沒有私鑰；自行建置的 release APK 不會加入官方 Release。

## iOS：未簽章 IPA 與自行建置

Release workflow 的 iOS job 以 Debug 組態、`CODE_SIGNING_ALLOWED=NO` 封存 App，核對 bundle identifier、
`CFBundleShortVersionString`／`CFBundleVersion` 與 tag 相符、內含 Widget extension 且沒有 provisioning
profile，再封裝為 `Shinsou-X-<version>-ios-unsigned.ipa` 附加到 Release。這份 IPA **沒有簽章，不能直接安裝**：
使用者必須以自己的 Apple ID 或憑證重新簽章（例如 AltStore、Sideloadly 或 Xcode）。它使用 Debug
entitlements（不含 App Group），重新簽章工具通常也不提供 App Group，因此主畫面小工具不會有資料；
免費 Apple ID 簽章的 App 每 7 天需要重新簽一次。官方不提供已簽章、TestFlight 或 App Store 版本。

要取得完整 entitlements 的 App 必須自行建置。iOS App 包含 SwiftUI
host、Widget、App Group 與 Keychain entitlements；建置者必須在 macOS 使用完整
Xcode 與 XcodeGen，並自行提供與 bundle identifiers／capabilities 相符的 Apple 開發帳號、
憑證及 provisioning profiles。

先產生 Xcode project：

```bash
cd iosApp
xcodegen generate
```

不需要簽章的 Simulator build：

```bash
xcodebuild -project Shinsou.xcodeproj \
  -scheme Shinsou \
  -sdk iphonesimulator \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

真機建置請開啟 `iosApp/Shinsou.xcodeproj`，將 App 與 Widget target 改成自己的
Development Team，再由 Xcode 執行或 Archive。若使用不同 bundle prefix，還必須同步修改：

- App：`dev.aluo.shinsoux`
- Widget：`dev.aluo.shinsoux.widget`
- App Group：`group.dev.aluo.shinsoux`

App Store／TestFlight distribution 需要有效的付費 Apple Developer Program 會籍、Apple
Distribution certificate 與 App Store profiles。免費 Personal Team 只能用支援範圍較小的
development signing，不能產生可公開分發的 App Store IPA。自行簽章的 IPA 不屬於官方
GitHub Release 產物；完整 entitlement 與真機限制見 [建置與驗證](BUILDING.md)。

## Desktop 分發邊界

- DMG workflow 目前尚未配置 Developer ID 與 notarization secrets，因此 GitHub 產物仍可能
  觸發 Gatekeeper 警告，也無法讓 Keychain 的「永遠允許」跨每次 ad-hoc 建置持續生效。
  正式配置時必須長期沿用同一個 Developer ID Application identity，透過
  `SHINSOU_MACOS_SIGNING_IDENTITY`（以及自訂 keychain 時的
  `SHINSOU_MACOS_SIGNING_KEYCHAIN`）交給 Gradle，且不得把憑證或密碼提交到 repository。
- MSI／EXE 目前尚未 Authenticode 簽章，Windows SmartScreen 可能顯示未知發行者。
- macOS producer 會掛載 DMG，檢查縮減後的 SQLite JDBC provider，使用隔離資料目錄啟動
  其中的 App，並要求 SQLite 初始化及 Compose 首幀 marker 完成。
- Windows producer 會在 Java Access Bridge 啟用條件下，對 app image 與安裝後程式執行
  同樣的 SQLite／首幀 marker probe；runtime probe 也會確認精簡後的 runtime 含 `jdk.accessibility` 與
  `jdk.unsupported`，並從封裝內的 JAR 載入 libsodium 完成一次 Ed25519 簽章往返。每個 tag 都會先安裝
  前一個公開版本的 MSI（`v1.0.1` 先安裝 `v1.0.1-beta.25`；基準版本、SHA-256 與 ProductCode 寫在
  `release.yml`，新增 tag 前必須補上，否則該步驟會被略過），再驗證新 ProductCode 真正取代舊版。任一步無法啟動或升級都不會發布 Release。

Beta.3 的 Desktop 安裝包雖可完成打包，縮減後的 `sqlite-jdbc` 遺失 provider class，啟動時
會出現 `No suitable driver found`，Windows launcher 通常只顯示 `Failed to launch JVM`；
同時 beta.2／beta.3 重用了 `1.0.0`，無法形成 MSI 升級。修復版同時修正 runtime 保留規則
與 Windows package version，以上述完整啟動及覆蓋安裝檢查防止回歸。Beta.4 的 CI 已
通過 macOS／Windows 修復驗證，但因 Android 驗證器輸出格式差異而未發布；beta.5 才是
第一個公開包含這些修復的 Release。

發布正式或 beta tag 前，應先確認 master 的一般 Windows workflow 與本機／CI 測試皆
通過。Release job 以 draft 聚合產物，核對實際 asset 清單後才公開；重跑同一 tag 時會先
清除舊 assets，再以該次已驗證的完整集合取代。
