# GitHub Actions 發布

推送格式為 `vMAJOR.MINOR.PATCH` 或 `vMAJOR.MINOR.PATCH-beta.N` 的 tag 會觸發 `.github/workflows/release.yml`。流程分別在 Linux、macOS 與 Windows runner 建置，通過產物與簽章驗證後才會發布同一個 GitHub Release。正式 tag 必須包含 Android、iOS、macOS 與 Windows 全部平台；beta tag 可在某平台完全沒有正式簽章設定時如實略過該平台，並在 release notes 列出包含及略過的平台。Beta 會建立 GitHub prerelease，且不會標記為 Latest。

例如：

```bash
git tag -a v1.0.0 -m "Shinsou X 1.0.0"
git push origin v1.0.0

# Beta prerelease
git tag -a v1.1.0-beta.1 -m "Shinsou X 1.1.0 beta 1"
git push origin v1.1.0-beta.1
```

正式版本必須是三段純數字；beta 序號 `N` 限 `1..98`。為同時符合 MSI、Apple 與跨商店的單調 build number，major 不得超過 81、minor 不得超過 255、patch 不得超過 999，且不可使用 `v0.0.0`。Android `versionName` 與產物名稱使用完整顯示版本；iOS `CFBundleShortVersionString` 與 Desktop `packageVersion` 使用不含 beta suffix 的三段 package version。

Android `versionCode` 與 iOS `CFBundleVersion` 使用以下公式：

```text
ordinal = ((MAJOR × 256 + MINOR) × 1000 + PATCH)
build = ordinal × 100 + stage
stage = beta N 時為 N；正式版為 99
```

因此同一 package version 的 `beta.1…beta.98` 都小於正式版，下一個 patch 的 beta 又大於前一個正式版。Tag 必須按此順序遞增，不能發布較新版後再發布較小版本或 beta 序號。

## 發布產物

完整的正式 tag workflow 會建立以下檔案，並附加只涵蓋實際發布資產的 `SHA256SUMS.txt`：

```text
Shinsou-X-<version>-android.apk
Shinsou-X-<version>-ios.ipa
Shinsou-X-<version>-macos-<arch>.dmg
Shinsou-X-<version>-windows-x64.msi
Shinsou-X-<version>-windows-x64.exe
```

macOS 架構以 runner 的實際 `arm64` 或 `x64` 命名。各 producer job 也會保留 14 天的 Actions artifact；GitHub Release assets 是長期下載來源。

Beta 的 macOS DMG 與 Windows MSI／EXE 仍是必要產物。Android APK 或 iOS IPA 只有在對應的正式簽章 secrets 全數缺少時才可略過；只設定一部分 secrets、無效的 Base64、錯誤密碼、錯誤憑證／profile 或簽章驗證失敗都會讓 workflow 失敗，不會被當成可略過的平台。正式版本永遠要求上列五種檔案。

## Release environment 與 secrets

在 repository 的 Settings → Environments 建立 `release` environment。若需要人工批准正式發布，可在這個 environment 加入 required reviewers。Android 與 iOS signing job 都綁定此 environment。

Android 必須設定：

- `ANDROID_KEYSTORE_BASE64`：release JKS／keystore 的單行 Base64
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

iOS 必須設定：

- `IOS_DISTRIBUTION_CERTIFICATE_BASE64`：包含私鑰的 Apple Distribution `.p12` 單行 Base64
- `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`
- `IOS_APP_PROVISIONING_PROFILE_BASE64`：`dev.aluo.shinsoux` 的 App Store distribution profile
- `IOS_WIDGET_PROVISIONING_PROFILE_BASE64`：`dev.aluo.shinsoux.widget` 的 App Store distribution profile

iOS profiles 必須屬於 Team `5UKY38ZLK6`。App profile 必須包含 `group.dev.aluo.shinsoux` 與 `iCloud.dev.aluo.shinsoux`；Widget profile 必須包含相同 App Group。Workflow 會驗證 team、bundle identifier、capability、profile 類型、IPA 簽章及最終版本，任何不符都會停止發布。

App Store distribution 憑證與 profiles 需要有效的付費 Apple Developer Program 會籍。免費 Personal Team 的 Apple Development 憑證／development profile 不能替代上述資料，也不得將 development-signed 或未簽章產物命名為 App Store IPA。尚未具備付費會籍時可發布省略 iOS 的 beta，但不能發布正式版本。

在 macOS 產生不換行的 Base64 字串可使用：

```bash
base64 -i release.jks | tr -d '\n'
base64 -i distribution.p12 | tr -d '\n'
base64 -i app.mobileprovision | tr -d '\n'
base64 -i widget.mobileprovision | tr -d '\n'
```

只把輸出貼入 GitHub secret；不得提交 keystore、P12、provisioning profile 或密碼。Workflow 會把簽章檔案放進 runner 的暫存目錄，完成後移除臨時 keychain 與 profiles。

## 分發邊界

- Android APK 是可安裝且可持續升級的 release-signed APK；遺失 keystore 會使既有安裝無法升級。
- IPA 使用 `app-store-connect` export method，供 App Store Connect／TestFlight 流程使用，不能當作公開直接側載包。Ad Hoc IPA 只能安裝在 profile 已登記 UDID 的裝置，必須使用不同 profiles 與 export method。
- DMG 目前尚未 Developer ID 簽章與 Apple notarize，macOS Gatekeeper 可能警告。
- MSI／EXE 目前尚未 Authenticode 簽章，Windows SmartScreen 可能顯示未知發行者。Windows producer 會先在 Java Access Bridge 啟用條件下驗證 app image，再靜默安裝 MSI 並驗證已安裝的 launcher/runtime；任一步無法啟動都不會發布 release。

發布正式或 beta tag 前，應先確認 master 的一般 Windows workflow 與本機／CI 測試皆通過。若 tag workflow 因 signing 或建置失敗，修正後可重新執行同一個 workflow run；release job 會以 draft 聚合產物，最後一步才公開，並可安全覆蓋同名 assets。首次建立 Android release key 後，必須把 JKS 與密碼備份到獨立且受保護的位置；遺失或重建 key 會讓既有安裝無法用後續 APK 原地升級。
