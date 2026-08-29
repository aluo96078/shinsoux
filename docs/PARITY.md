# 功能對齊狀態

本表描述目前 repository 中的實作與已知平台邊界，不把「UI 已出現」、「能編譯」或「local fixture test 通過」等同於真機／外部服務已驗證，也不以 `完整` 宣稱手機版已達逐像素、逐來源完全一致。

狀態定義：

- `已實作`：共用流程及該 target 接線存在，並有對應 compile 或 deterministic test 路徑。
- `受限`：核心已接，但有刻意的平台差異、缺少外部設定，或只能由真機／帳號／GUI 驗證的部分。
- `未接`：只有 model／setting／UI，或尚未提供會改變 runtime 行為的平台接線。
- `不適用`：該平台不提供此能力。

## 功能矩陣

| 功能 | Android | iOS | Desktop（macOS／Windows） | 實作與邊界 |
|---|---|---|---|---|
| 圖書館、搜尋、篩選、排序、批次操作 | 已實作 | 已實作 | 已實作 | 共用 repository／Compose UI；含多分類 picker、批次多分類與 default-category Always Ask |
| Updates／Upcoming、History、Browse、More、Statistics | 已實作 | 已實作 | 已實作 | Browse 含 source pinning；More 含 Incognito／Download-only 快速切換與 active banner |
| 漫畫詳情與章節管理 | 已實作 | 已實作 | 已實作 | filter／sort、筆記、批次操作、scanlator 排除、重複與缺章處理 |
| Reader：LTR／RTL／直向／Webtoon | 已實作 | 已實作 | 已實作 | zoom、濾鏡、預取、retry、章節清單、章末轉場及「以瀏覽器開啟本章」救援入口 |
| Reader 平台整合 | 受限 | 受限 | 受限 | Android／iOS 接 keep-awake、fullscreen、orientation 與 Reader-only volume-key gate；Android 消耗 Volume Down／Up 且忽略 repeat，iOS 另處理音量恢復／audio session。兩者皆需真機驗證；Desktop 以鍵盤操作為主 |
| Reader／Download 來源特例 | 已實作 | 已實作 | 已實作 | viewer HTML 解析、header／cookie／Referer、JM 分段反轉、離線 transform sidecar 與 E-Hentai 類 viewer 路徑 |
| 下載佇列與離線閱讀 | 已實作 | 已實作 | 已實作 | 暫停、重試、重排、刪除與持久化 queue；page／transform 完成後最後原子發布 completion manifest。Clear completed 只把完成 row 設為 hidden，保留徽章、manifest 與離線閱讀 |
| 統一內容／Local source `0` | 已實作 | 已實作 | 已實作 | TXT、圖片、ZIP、CBZ 與完整 EPUB package/resource graph；正文與資源存入 immutable blob store，metadata/ref/outbox 由 shared SQLite 原子提交。EPUB 保留 XHTML／CSS／font／image，三平台 browser-grade renderer 以 private scheme 按需讀取 |
| 統一 Reader／全文搜尋／TTS／註記 | 已實作 | 已實作 | 已實作 | 文字、圖片、EPUB 共用 portable locator；搜尋、TTS 與 range annotation 都經 host rights gate。平台 TTS／browser engine 的真實 voice、排版與 accessibility 行為仍需各平台 smoke |
| JavaScript extension 管理 | 已實作 | 已實作 | 已實作 | 安裝／更新／卸載、execution grant、preferences、credentials、login/logout；撤銷會立即卸載 runtime 但保留 package，重啟不會自行恢復授權。JVM Rhino、iOS JavaScriptCore |
| Extension repository 可攜狀態 | 已實作 | 已實作 | 已實作 | `AppSnapshot.extensionRepositories` 是 source of truth；舊 KV 只做一次性遷移，之後 add／remove／default 及 restore／sync 結果會精確覆蓋 KV mirror，不會 union stale KV |
| 官方 extension fixture | 已實作 | 受限 | 已實作 | Desktop test 在 Rhino 初始化 workspace 官方腳本；iOS test 驗證同步 JavaScriptCore contract，但未逐一執行全部 workspace 腳本 |
| 手動 cookie 與檔案匯入 | 已實作 | 已實作 | 已實作 | 手動編輯及 Netscape `cookies.txt`／JSON；1 MiB、500 筆上限，驗證 domain/path/expiry／字元。picker 先驗 declared size 再 bounded read；macOS Desktop file picker 已做 packaged-app 開啟／取消 smoke，Windows Desktop 及 Android／iOS provider 仍需各平台驗證 |
| Cloudflare／Web challenge | 已實作 | 已實作 | 受限 | Android WebView 先清 cookie jar 再 seed；iOS 使用 non-persistent WKWebView；兩者擷取後再次驗證。macOS Desktop 使用獨立原生 non-persistent WKWebView 視窗，可擷取該隔離 session 的 cookie／真實 User-Agent，並在來源帳密已保存時只對同源頁面與同源 form 自動登入。Windows Desktop 只開外部瀏覽器，無法自動讀取或匯入外部 cookie |
| 可攜式備份／還原 | 已實作 | 已實作 | 已實作 | legacy BackupEnvelope 與 checksummed Content Backup v2 並存；v2 同時保存 publication graph 與 body-free metadata／aliases／migration ledgers，可選擇納入 rights 允許的 immutable body，metadata-only restore 不宣告缺失 body 已存在。ShuYue v1 先 staging/report/quarantine，再以 shared transaction 匯入；secrets 只經明確 consent 進平台安全儲存 |
| Cloudflare schema v2／encrypted body sync | 已實作 | 已實作 | 已實作 | publication/unit/annotation/blob-ref metadata 走 event/checkpoint；使用者選擇且 `SYNC_BLOB` 允許的 body 走隔離 R2、分塊 AEAD、resume、DEK re-wrap 與 checkpoint-ack GC。真實四平台跨裝置矩陣仍是外部上線 Gate |
| App-private 自動備份 | 已實作 | 已實作 | 已實作 | 原子快照、due check、retention、損毀標示、立即建立／列表／還原／刪除；WorkManager、BGProcessingTask、Desktop 前景 scheduler |
| iCloud Drive snapshot sync | 不適用 | 受限 | 不適用 | `Documents/Shinsou/shinsou-sync.shinsoubackup` 單檔、NSFileCoordinator、repository CAS 與 deterministic merge；不採用遠端 download queue、保留本機 proxy secret。無 deletion tombstone、非 record-level CloudKit，entitlement 尚需簽章真機驗證 |
| MyAnimeList tracking | 未接 | 未接 | 未接 | adapter/test 存在，但原版未提供可用 client ID；UI 明示未配置，不能登入 |
| App lock／secure screen | 已實作 | 已實作 | 未接 | Android Biometric 與 secure window、iOS LocalAuthentication／privacy cover；手機端會依 passcode／PIN／密碼／biometric 的執行時可用性動態 gate app lock，驗證不可用時忽略 stale enabled 值、恢復可用時重新鎖定；secure screen 仍可用。Desktop capability 明示 unavailable，Settings 停用且 `authenticate` 不會回傳假成功 |
| 敏感狀態儲存 | 已實作 | 已實作 | 受限 | Android 敏感 KV 使用 Keystore AES-GCM；iOS 使用 Keychain；Desktop 使用 AES-256-GCM，master key 在 macOS 存入 Keychain，在 Windows 以目前使用者範圍 DPAPI 保護且不使用 machine scope。macOS Keychain prompt／簽章後存取／legacy file migration，以及 Windows 安裝版重啟解密與不同使用者 fail-closed 行為仍需各平台 smoke |
| App deep links | 已實作 | 已實作 | 受限 | Android intent 與 iOS `shinsou://` custom URL 可導向 manga／chapter／section／settings；目前 parser 不接受 HTTPS universal link。Desktop 主要是 Menu Bar 產生的 app 內導航事件 |
| Widget | 不適用 | 受限 | 不適用 | App Group payload 與 WidgetKit timeline 已建置；需簽章 provisioning／真機確認 shared container |
| 自動背景來源更新 | 未接 | 未接 | 未接 | 手動共用 refresh 流程可用；尚未接 Android／iOS OS-managed 來源更新 scheduler |
| DNS over HTTPS runtime | 未接 | 未接 | 未接 | toggle 只保存設定；尚無跨 OkHttp／Darwin／CIO 且保留 hostname、TLS SNI 與憑證驗證的 resolver |
| Desktop chrome 與平台安裝包 | 不適用 | 不適用 | 受限 | Compose Desktop sidebar、雙欄、系統 Menu Bar、macOS `⌘`／Windows `Ctrl` 快捷鍵與 close confirmation 已接；macOS 使用 DMG／ICNS，Windows 使用 machine-scoped MSI／EXE／ICO、stable upgrade UUID、固定安裝目錄、桌面捷徑與開始功能表。MSI 刻意避開 jpackage per-user `RemoveFolderEx` 清理風險，且不允許把卸載根目錄改為使用者既有資料夾；安裝可能需要 UAC 提權。macOS unsigned packaged app 已 smoke 主要導航與關閉流程；Windows installer、DPI、快捷鍵與升級／解除安裝仍需原生 Windows 人工驗證。介面不是原生 AppKit 或 WinUI |

## Local source 邊界

支援直接圖片：

```text
jpg jpeg png webp gif avif heic bmp
```

支援 ZIP-based archive：

```text
zip cbz epub
```

平台 picker 會先取得 declared size，再由共用 bounded reader 精確讀取並以 1-byte probe 偵測 grow／shrink：直接圖片每檔最多 64 MiB，archive 與整批匯入最多 512 MiB。無法提供可靠 size metadata 的 cloud／stream provider 會被拒絕；通過檢查後仍會配置上限內的完整 `ByteArray`，不是 end-to-end streaming。

匯入器使用 natural page ordering，拒絕 path traversal、absolute／Windows drive path、symlink page、加密 archive、ZIP64 與多卷 ZIP，並限制單頁 64 MiB、整次圖片 512 MiB 及最多 10,000 entries。所有頁面寫入並核對後，才原子發布列出連續受控檔名的 v2 manifest；Reader 要求 manifest 與實際頁面集合完全一致。舊 v1 marker 只有在 canonical marker 與完整連續頁面集吻合時才原子升級。這些限制屬於安全邊界，不是一般 archive 解壓器的完整格式支援，也不是 page-content checksum。

頁面 bytes 與 v2 manifest 存在裝置的 app-private storage，不會寫入 `AppSnapshot`、portable backup 或 iCloud snapshot。若只搬移 snapshot，Local manga record 仍在，但 Reader 會要求重新匯入原始圖片／archive。

## Cookie 與 Cloudflare 邊界

- Cookie file parser 接受 Netscape tab-separated 格式、JSON array，以及 `{ "cookies": [...] }`；JSON expiry 可使用 `expirationDate`、`expires` 或 `expiry`。
- 匯入只接受 source base URL 的 host 或合法 parent-domain cookie，排除 foreign／expired／malformed value，重複的 domain／path／name 以最後一筆為準。
- 平台 picker 在配置內容前先驗 declared size，cookie 文件上限 1 MiB；size 不明、讀取途中變長／變短或超限都會拒絕。
- Plugin HTTP client 關閉 Ktor 自動 redirect，最多手動處理 10 hop，拒絕 HTTPS→HTTP、危險的跨 origin request-body redirect，並在跨 origin hop 只保留 allowlist header；Cookie 會依每個新 target 重新組裝，不會直接轉送上一站的 `Cookie`／Authorization／API key。
- 每一 hop 的 `Set-Cookie` 都會先驗 name／value／domain／path，解析 Secure／HttpOnly，且讓 `Max-Age` 優先於 `Expires` 並做飽和運算；過期 cookie 只刪除相同 name＋domain＋path 的 identity。
- Domain 防護拒絕單 label、常見 multi-label country suffix 與明顯無效 domain。這是保守 suffix 規則，不是完整 Mozilla Public Suffix List；不可據此宣稱涵蓋所有 registry suffix。
- Android WebView cookie API 無法回傳完整 attribute，因此 challenge 擷取值會縮限為當前 host/path，再經共用 validator；它不是可攜的完整瀏覽器 cookie database。
- iOS WKWebView 使用 non-persistent data store，challenge cookies 只有通過 domain/path/expiry/Secure 驗證後才寫回 source storage。
- macOS Desktop challenge 使用獨立原生 non-persistent WKWebView 視窗，不與 Compose／Skiko 共用 rendering surface，也不讀取 Safari／Chrome cookie database。Host 可 seed 來源 cookie，完成後擷取該隔離 session 的 cookie 與真實 User-Agent，再經共用 validator 寫回來源 storage。
- macOS 自動登入只在 challenge request 明確綁定來源且該來源已保存帳密時啟用；帳密經 helper stdin 傳送，不放入 command line，頁面 origin 與 form action 都必須與初始 challenge origin 相同。這不代表任意登入頁、跨 origin SSO 或 CAPTCHA 一定可自動完成。
- Windows Desktop 只開外部瀏覽器，Shinsou 無法讀取 Edge／Chrome／Firefox 的 cookie；可改用 `cookies.txt`／JSON 匯入或手動輸入。
- 真實 Cloudflare 頁面、CAPTCHA 與來源站策略會隨外部服務改變，local tests 只驗證隔離、seed、capture 與 cookie trust boundary。

## Tracking 邊界

MyAnimeList HTTP adapter 與 deterministic tests 已存在，但 composition 刻意把 provider 標成 `configured = false`。在取得合法 client ID、補入安全設定並完成登入流程前，不應把 MAL 標成可用。

## 備份與同步邊界

- Portable backup、automatic backup 與 iCloud sync 共用 versioned `BackupEnvelope`，但目的地與排程不同。
- Envelope 會保存 `AppSnapshot.extensionRepositories`，但 installed JavaScript packages 與 per-source plugin key-value store 在 snapshot 外；跨裝置仍需重新安裝 extension 並重新設定來源 secrets／preferences。舊版 KV repository 清單只在首次升級遷移，之後 restore／sync 的 snapshot 清單會精確回填 KV mirror。
- proxy API key 雖由 live settings 提供 runtime 使用，但在 snapshot／backup encode 前固定 redaction，並路由到各平台敏感 KV；restore 與 merge 只保留目的裝置的本機值。
- Download completion manifest、Local v2 manifest 與 page bytes 都是裝置本機檔案。Clear completed 只把完成 queue row 設為 `visibleInQueue=false`，因此不會讓 Reader 或下載徽章失去完成狀態。
- Automatic backup 是每台裝置的 app-private recoverable 檔案；Android／iOS 的 OS scheduler 不保證精確執行時間，Desktop 只在 app 存活時檢查。
- iCloud 只在 iOS 提供 transport。流程是 pull 單檔、decode、deterministic merge、以 repository revision CAS 寫回本地，再原子 replace 遠端單檔。Resolver 不重映射或採用遠端 `downloadQueue`，合併結果保留本機 queue；備份 restore 同樣保留並清理本機 dangling rows。
- 此模型沒有 CloudKit record、subscription、push notification、逐筆 server conflict 或 deletion tombstone。包含 extension repository 在內的 key-based merge 可能讓 stale replica 在衝突時帶回已刪除 record，因此不能描述為原版 record-level CloudKit 的等價實作。

## 驗證層級

Repository 提供以下可重現驗證路徑；每次整合或發布前仍應重新執行 [BUILDING.md](BUILDING.md) 的完整命令矩陣。

1. `desktopTest`：common／Desktop deterministic tests、本地 mock transport、Local ZIP、cookie parser、backup/sync、Reader／plugin fixture；在 Windows runner 另會執行 native DPAPI round-trip。
2. Android `assembleDebug`、macOS／Windows Desktop compile、iOS Simulator compile/link：target API 與 expect/actual 編譯。macOS 的 `desktopTest`／Desktop application task 會先以 `xcrun --sdk macosx swiftc` 編譯原生 WKWebView helper，因此還會驗證 Xcode Command Line Tools、macOS SDK 與 Swift compiler 可用。
3. `iosSimulatorArm64Test`：共用 tests 與 JavaScriptCore contract。
4. 無簽章 Xcode Simulator build：SwiftUI host、Widget target 與 Kotlin framework linkage。
5. 平台原生安裝包：macOS 執行 `packageDmg` 驗證 bundle／DMG；Windows x64 執行 `packageReleaseMsi`／`packageReleaseExe` 驗證 MSI／EXE 與 WiX 設定，並在強制啟用 Java Access Bridge 時啟動 app image 與 MSI 已安裝程式，確認精簡 runtime 包含 `jdk.accessibility`。

上述項目都不能代替：

- Android／iOS 真機互動與 OS 背景排程
- Android 實體音量鍵攔截／HUD／系統音量不變，以及 iOS 實體音量鍵、HUD、audio-session／使用者音量恢復
- Android Keystore／iOS Keychain／macOS Keychain／Windows 使用者範圍 DPAPI 的首次建立、legacy plaintext migration、重啟後解密與作業系統 access-control 行為
- App Group、Widget、Keychain sharing 與 iCloud entitlement
- 來源登入、實際下載或 Cloudflare challenge
- Android content provider、iOS security-scoped URL，以及 Desktop 實際選取檔案後的 bounded-import 路徑
- macOS／Windows Desktop 實體鍵盤 accelerator、含資料的 master/detail／Reader，以及 packaged app 的 macOS Keychain prompt／ACL／legacy key migration 與 Windows DPAPI 使用者隔離／fail-closed 行為
- Windows MSI／EXE 的非管理員安裝、stable-UUID 覆蓋升級、捷徑、開始功能表、DPI、解除安裝與 Authenticode／SmartScreen 行為

## 其他已知限制

- DoH toggle 不會改變 runtime。直接改寫目的 IP 並覆寫 `Host` 會破壞 HTTPS SNI／憑證語義，因此未複製不安全行為。
- JavaScript runtime 以官方同步式 extension contract 為準；任意第三方 Promise／async／`fetch` 腳本不保證相容。
- Extension execution trust token 綁定 `pluginId + versionCode + SHA-256`，不是作者身分或 repository signing chain 的完整驗證；system-event grant 則另以 `packageId + version + versionCode + SHA-256` 及 exact `SourceKey` 核准。沒有 repository digest 的現行格式在明確 install／update 時採下載 bytes 的 SHA-256 作 TOFU execution grant。
- UI 已有字串 provider，但仍存在不少直接寫在 Compose 中的英文；多語介面尚未達原版完整度。
- macOS Keychain 與 Windows DPAPI 只保護 Desktop 敏感 KV 的 AES master key，不等於 app lock。Desktop app lock／secure screen capability 明示 unavailable；若需要獨立驗證，仍需 LocalAuthentication 或等價桌面安全設計。
- Bounded picker 仍會在核准上限內建立完整 `ByteArray`，且 size metadata 不可靠的 provider 會安全拒絕；實際 cloud provider 相容性尚需逐平台 smoke。
