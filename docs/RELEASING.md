# GitHub Actions 發布

推送格式為 `vMAJOR.MINOR.PATCH` 或 `vMAJOR.MINOR.PATCH-beta.N` 的 tag 會觸發
`.github/workflows/release.yml`。流程在 Linux 建置 Android debug APK、在 macOS 建置 DMG、
在 Windows 建置 MSI／EXE；所有必要產物與啟動驗證通過後，才會發布同一個 GitHub
Release。流程不建置 iOS App，也不發布 IPA。Beta 會建立 GitHub prerelease，且不會標記為
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
單調 build number，major 不得超過 81、minor 不得超過 255、patch 不得超過 999，且不可
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

## 發布產物

每個 tag workflow 都必須建立以下檔案，並附加涵蓋四個二進位產物的
`SHA256SUMS.txt`：

```text
Shinsou-X-<version>-android-debug.apk
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

GitHub-hosted runner 使用自動建立的 debug keystore；該 key 不保證在不同 workflow run
之間保持相同。若新舊 APK 的 signer 不同，Android 會拒絕覆蓋安裝。更新前應先在 App 內
建立備份，必要時解除安裝舊版再安裝。需要穩定原地升級或正式商店／企業分發時，請自行
建立並安全保存 release keystore，再於自己的受控環境執行：

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

不得提交 keystore 或密碼。專案的公開 Release workflow 不讀取 Android signing secrets，
也不會把自行建置的 release APK 加入官方 Release。

## iOS：不發布 IPA，必須自行建置

GitHub Release workflow 完全不執行 iOS job，也不接受或上傳 IPA。iOS App 包含 SwiftUI
host、Widget、App Group、Keychain 與 iCloud entitlements；建置者必須在 macOS 使用完整
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
- iCloud container：`iCloud.dev.aluo.shinsoux`

App Store／TestFlight distribution 需要有效的付費 Apple Developer Program 會籍、Apple
Distribution certificate 與 App Store profiles。免費 Personal Team 只能用支援範圍較小的
development signing，不能產生可公開分發的 App Store IPA。自行產生的 IPA 不屬於官方
GitHub Release 產物；完整 entitlement 與真機限制見 [建置與驗證](BUILDING.md)。

## Desktop 分發邊界

- DMG 目前尚未 Developer ID 簽章與 Apple notarize，macOS Gatekeeper 可能警告。
- MSI／EXE 目前尚未 Authenticode 簽章，Windows SmartScreen 可能顯示未知發行者。
- Windows producer 會先在 Java Access Bridge 啟用條件下驗證 app image，再靜默安裝 MSI
  並驗證已安裝的 launcher/runtime；任一步無法啟動都不會發布 Release。

發布正式或 beta tag 前，應先確認 master 的一般 Windows workflow 與本機／CI 測試皆
通過。Release job 以 draft 聚合產物，核對實際 asset 清單後才公開；重跑同一 tag 時會先
清除舊 assets，再以該次已驗證的完整集合取代。
