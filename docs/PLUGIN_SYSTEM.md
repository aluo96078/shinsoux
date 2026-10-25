# Shinsou X 插件系統

- 狀態：Production 路徑已接通；部分 V2 能力仍為保留設計
- 文件基準：2026-09-03 的 `master` 實作與官方插件索引
- 官方插件倉庫：<https://github.com/aluo96078/shinsou_plugin>
- 官方儲存庫基底 URL：
  `https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master`

本文件是 Shinsou X 插件系統的總覽與目前行為基準。Repository 格式細節見
[統一插件儲存庫契約](UNIFIED_PLUGIN_REPOSITORY.md)，system-event 的 wire、安全與排程設計見
[插件系統事件接口架構](PLUGIN_SYSTEM_EVENT_ARCHITECTURE.md)。

> [!IMPORTANT]
> Plugin artifact 可能包含會執行 JavaScript、發送第三方網路請求並處理 Cookie／登入狀態的程式碼。
> SHA-256 與 sidecar cross-check 能確認下載的 artifact 和已審核宣告一致，但不等於作者身分已由
> PKI 驗證，也不保證程式行為安全。Production 只執行 Host 內建 catalog 精確審核的 ShuYue
> artifact；generic repository JavaScript 即使已安裝與核准仍保持 inert。

## 目前 production 狀態

Shinsou X 將來源實作與 App 核心分開：Plugin 負責瀏覽、搜尋、作品 metadata、章節與內容解析；
書庫、Reader、下載、備份、同步、權利判斷與敏感資料儲存仍由 Host 控制。

目前正式路徑包含：

- `contract: "shinsou"`：解析、驗證與安裝 generic legacy Shinsou metadata／artifact，但 production
  不把其 JavaScript 送入 in-process runtime；
- `contract: "shuyue"`：只執行內建 reviewed catalog 精確核准的 ShuYue artifact；
- 漫畫 `IMAGE_SEQUENCE` 與小說 `PLAIN_TEXT` 套件；
- V2 exact-artifact digest、sidecar cross-check、execution grant 與 system-event grant；
- Android／Desktop Rhino 與 iOS JavaScriptCore runtime；
- 來源級 Cookie、帳密、偏好、User-Agent、Web challenge 與 Cloudflare Worker Proxy 設定；
- Browse、Reader 與 Download 共用來源身分和 Host 網路層。

這裡的「共用」只表示同一個 Host 安全邊界，不表示共用 credentials。API request plane 可使用已核准的 source headers、Cookie、Referer、UA 與 proxy；Reader 圖片／文字及 Download 則走獨立 host content plane，只允許 exact `contentOrigins`、`GET`／`HEAD` 與安全 headers，明確移除 Cookie、Authorization、proxy key 及其他 credential/proxy secret。Host 取得 bytes 後交給 Coil 或相應內容 consumer。

V2 schema 能描述同一 package 內同時存在漫畫與小說來源，但目前 production generic Shinsou adapter
與 reviewed ShuYue 安裝流程尚未保證任意 mixed package 的完整執行。官方 `example.dual` 因此明確為
`referenceOnly: true`、`installable: false`；目前可安裝的漫畫與小說來源採分包發布。

## 名詞與身分

| 名詞 | 意義 |
|---|---|
| Repository | 提供 `repo.json`、索引、script 與 sidecar 的靜態來源，不是內容託管服務。 |
| Package | 可版本化的來源套件；可能宣告一個或多個 source。 |
| Artifact | 某一 package version 的確切 JavaScript bytes，以版本、version code、SHA-256 與 byte size 識別。 |
| Sidecar | 安裝前可讀的審核資料；Host 會把它和索引中的 artifact、來源、內容、權限及事件宣告逐欄交叉核對。Repository schema 沒有發布者宣告的獨立「sidecar digest」；reviewed loader 另會把實際下載 sidecar bytes 的 SHA-256／size 記入本機 quarantine metadata。 |
| Source | 使用者在「瀏覽 → 來源」看到並呼叫的內容來源。 |
| Host | Shinsou X App；負責 admission、runtime、網路、敏感儲存、UI 與資料生命週期。 |

來源的穩定主鍵是：

```text
SourceKey(contractVersion, packageId, sourceId, legacyLongId?)
```

- `packageId + sourceId` 是 V2 的 canonical identity；
- `legacyLongId` 只用於需要和既有資料／adapter 對接的來源；
- 多來源 package 必須以 exact `sourceId` 選擇來源，不能依陣列位置、顯示名稱或雜湊猜測；
- 書庫 binding、閱讀進度、下載與遷移都應保留完整 `SourceKey`。

目前 installable generic Shinsou V2 項目仍會投影到 legacy `Long` source ID，因此 `sourceId` 必須可無損
轉成 `Long`。不可安裝的參考項目可使用 opaque ID；reviewed ShuYue 路徑也可保留 opaque string ID。

## 從 Repository 到 Reader

```text
repository metadata
  → index shape / contract detection
  → installable 與相容性檢查
  → sidecar cross-check
  → script byte size + SHA-256 驗證
  → execution / event admission
  → exact-source runtime
  → capability-gated Host facade
  → Browse / Library / Reader / Download
```

URL 名稱不是 protocol 判斷依據。Host 先讀取 JSON shape，再判斷是 Shinsou array、
Mihon/Tachiyomi metadata、`shinsou-unified-v1` envelope 或 `shinsou-extension-v2`。

## Repository 相容矩陣

| 索引格式 | Host 行為 | 可執行性 | 主要限制 |
|---|---|---|---|
| 傳統 Shinsou `index.json` array | 讀取 JavaScript package metadata | 可安裝但 production runtime 保持 inert | 舊索引可能沒有發布端 digest；developer compatibility 可明示測試 legacy adapter，但 production 不執行。 |
| Mihon／Tachiyomi `index.min.json` | 建立 metadata-only stub source | APK 不執行 | 只提供相容 metadata，不代表支援 Android APK extension runtime。 |
| `shinsou-unified-v1` | 同一 URL 投影 `shinsou`／`legacy` 與 `shuyue` 清單 | 依各自 admission 路徑 | 保留給遷移與相容；不要由 URL 或 hostname 猜 contract。 |
| `shinsou-extension-v2`、`contractVersion: 2` | 驗證 V2 package、sidecar、digest、內容與權限宣告 | generic `shinsou` production inert；`shuyue` 必須命中 reviewed catalog | `referenceOnly`、`installable: false`、`legacyCompatibilityOnly` 不可作一般新安裝。 |

V2 根索引可以同時包含 `contract: "shinsou"` 與 `contract: "shuyue"`。這表示共用發布目錄，
不表示兩種 script contract 可以互換。完整欄位與偵測規則見
[統一插件儲存庫契約](UNIFIED_PLUGIN_REPOSITORY.md)。

## Package、內容與輸出契約

一個可發布的 V2 package 至少由以下資料互相綁定：

```text
index.json package entry
plugins/<package-id>.js
sidecars/<package-id>.json
```

索引、sidecar 與 script metadata 應對齊 package／version、來源、content kinds、capabilities、runtime
permissions、requested Host permissions、system events、script URL、SHA-256 與 byte size。官方
repository smoke test 會完整比對這些欄位。App 的 generic Shinsou admission 目前會強制比對 package、
version、artifact、內容 type、來源、browser origins、events 與 requested Host permissions；reviewed
ShuYue admission 另比對 capabilities。`contentKinds`、generic `capabilities` 與 `runtimePermissions` 的
完整三方 parity 目前仍以官方發布檢查為主，尚未全部成為 generic Host 的 runtime admission gate。

核心 V2 內容種類為：

| `ContentKind` | 內容形式 |
|---|---|
| `IMAGE_SEQUENCE` | 有界圖片頁序列，可攜帶 progression、layout 與受控 transform plan。 |
| `PLAIN_TEXT` | inline UTF-8、Host-fetch resource 或有界 cursor/chunk stream。 |
| `EPUB_SPINE` | 遠端 EPUB package graph、spine documents 與資源描述。 |

V2 Host facade 在每次呼叫前檢查 exact `SourceKey` 與 capability，並再次驗證輸入範圍、URI、header
hint、頁數、資源數、文字 chunk、回應 bytes 與輸出 source scope。Plugin 取得的是受限 Host bridge，
不是任意核心服務或原始 credential store。

### Reviewed ShuYue large-text profile

只有 exact reviewed ShuYue artifact 會套用 large-text profile。相對於 generic 的 Host defaults，
invocation instruction budget 由 100M 提高至 500M，default result string cap 由 1 MiB 提高至 8 MiB，
result envelope cap 由 4 MiB 提高至 17 MiB；generic runtime 的 defaults 不變。這不是任意 repository
宣告可取得的額度：caller 明示的更嚴值保留，只有仍等於 generic default 的值才會提升，任何非
default 的 raised value 仍 capped 於 reviewed maximum。

4–8 MiB 的多頁 `PLAIN_TEXT` 現在可讀；大於 8 MiB 的正文 fail closed。正文以 64 KiB chunk 的
single-use、可取消 stream 傳遞，pending 與 active stream 的 aggregate reservation 合計最多 8 MiB；
超過任一 stream 或 aggregate bound 即拒絕。最壞情況可能同時存在 JS String、JSON envelope 與 Kotlin
String／ByteArray copies，並使用較高 CPU instruction budget，因此放寬只影響 exact reviewed runtime，
不改變 generic runtime 的 steady-state 記憶體或效能。

Generic Shinsou adapter 保留既有 image-oriented hook（例如 `getPageList`）與同步 JavaScript 假設，
因此「V2 核心能表示某種 payload」不等於所有 legacy script 都已能產生該 payload。Reviewed ShuYue
runtime 會把已審核的小說／漫畫輸出轉成 V2 內容。

## 安裝、更新與撤銷生命週期

### Generic Shinsou package

1. Host 讀取 inert repository metadata，拒絕不可安裝狀態與不安全的檔名／路徑。
2. 若有 V2 sidecar，先把 package、version、artifact、內容、來源、事件與 requested permissions 和索引交叉核對。
3. 下載 script，驗證 byte size 與 SHA-256；production V2 repository 另要求 pinned Ed25519 envelope，並驗證 sequence replay／equivocation。舊格式沒有發布端 digest 時，只有明示的 unsigned developer compatibility 流程可記錄收到 bytes 的 legacy trust-on-install token。
4. Production 保持 artifact inert；只有明示的 internal test／developer compatibility factory 才建立
   generic runtime candidate 並做相容性檢查。
5. 先寫 content-addressed script，再以原子寫入 `package.json` 作為 package commit point；production
   不因安裝、digest 或 execution grant 自動建立 generic live runtime。
6. 有 Host event 權限要求時建立待審 review；repository 的要求不會自動變成 grant。

每個 package 獨立儲存。V2 package store 以 script 的 SHA-256 作為 immutable content address，先寫入
`script-<sha256>.js`，再以原子 `package.json` 作為 commit point；寫入後會 read back 並重新驗證 script
SHA-256、嚴格 UTF-8、metadata 與 package ID。單一損壞或不相容 package 會保持已安裝但不執行，不會
阻塞其他 package 啟動；舊的未引用 script 即使清理失敗也不會再次被發現。Key-value store 的 V2
record 一旦存在但損壞，會 fail closed，絕不退回舊 V1 metadata/script split keys；只有沒有 V2 record
時才可進行受限的 legacy migration。

### Reviewed ShuYue package

Reviewed ShuYue 使用更窄的 admission 流程：

1. 載入 inert metadata；
2. 只顯示命中內建 reviewed catalog 的 exact package／version／versionCode／SHA／source metadata；
3. 下載 bytes 後放入 quarantine，不執行；
4. 再次 hash、review，要求使用者明確批准完整 least-privilege permission set；
5. 批准後才建立 guarded runtime；
6. 重啟時只 rehydrate 仍精確匹配且仍獲批准的 artifact；損壞、撤銷或 compatibility-only 項目 fail closed。

Repository fetcher 的 index／sidecar response 預設上限仍為 2 MiB／512 KiB；已安裝 package store 的
V2 bounds 則是 128 KiB key-value index、8 MiB script、512 KiB package metadata、256 packages、每包
256 sources，以及 package store 目錄最多 1,024 個 discovered files。Repository／package loader 遇到
超限、無效 UTF-8、SHA-256 不符、metadata/readback 不一致或不安全路徑時拒絕或 fail closed；這些是
目前 Host 預設值，不是套件可自行提高的額度。Repository transport 另受 redirect hop policy 約束。

內容與 runtime 另受 response bytes、log、頁數／資源數、文字 chunk、EPUB graph 等有界 resource limits 約束；任何超限均拒絕或 fail closed。Runtime／system-event 權限與 artifact approval 是獨立檢查，repository 宣告不會自動授權。

### 更新、重新授權與卸載

- Execution grant 綁定 `{pluginId, versionCode, sha256}`；只證明指定 bytes 可執行。
- System-event grant 是另一套授權，綁定完整
  `{packageId, version, versionCode, sha256, SourceKey}` 與要求的 Host permissions。
- 更新造成 version、versionCode、digest、SourceKey 或權限 review 改變時，不能假設舊 grant 自動延伸。
- 撤銷 execution trust 會立即卸載 live runtime，但保留 package 供重新授權或卸載。
- 只撤銷 event grant 時，來源可在沒有該事件權限的狀態重新載入。
- App 啟動只重建仍有 exact trust 的 package；「檔案 digest 正確」本身不會恢復使用者已撤銷的授權。
- Uninstall 會移除 package、對應 grant 與 live runtime。

Uninstall 先在本程序安裝 irrevocable 的 local denial，再卸載 runtime、撤銷 execution／event grant，最後嘗試刪除 package。若 package-store removal 失敗，呼叫會回報錯誤，但已撤銷的 trust、event grant 與 legacy trust-on-install 會讓殘留 bytes 在本程序及下一次重啟都保持 inert；只有確認 durable deletion 後才會清除 denial floor，供之後重新安裝。

### System-event grant 的單一 durable record

每個 exact artifact 只有一個 authoritative V2 admission record，狀態為 `PENDING`、`GRANTED` 或
`REVOKED`。Record 同時保存完整 artifact identity（package ID、version、version code、SHA-256）、
精確 `SourceKey` 清單與已核准的 Host／runtime permissions；因此不會以分開寫入的 permission half
拼出使用者未批准的 grant。`GRANTED` 只在完整 review 的單次 durable commit 後生效，`PENDING` 不可執行，
`REVOKED` 是明確 tombstone，啟動 hydrate、更新、卸載與事件執行都必須尊重它。

Legacy split grant migration 只在兩個舊半部都能嚴格解碼、未超過 bounds，且 artifact、所有
`SourceKey` 與 Host／runtime permissions 完全相符時轉成單一 V2 record；缺半部、損壞、超限或不相符
一律 fail closed，不能恢復 authority。Legacy pending 可安全轉成 V2 `PENDING`。撤銷先撤掉 live
authorizer 並寫入 `REVOKED`；即使 durable write 失敗，本程序也會保持本地 revoked floor，不會重新
hydrate 舊 grant。

## 四層能力與權限

下列概念用途不同，不應合稱為同一種「插件權限」。

| 層級 | 目前識別字 | 意義 |
|---|---|---|
| Source capability | `CATALOGUE`、`LATEST`、`BROWSE`、`METADATA`、`UNITS`、`CONTENT`、`SEARCH`、`LOGIN`、`FAVORITE`、`PREFERENCES` | Host 可以向來源呼叫哪些功能；每次呼叫由 V2 facade 檢查。 |
| Reviewed runtime permission | `EXECUTE_SCRIPT`、`NETWORK`、`COOKIE_STORAGE`、`CREDENTIAL_ACCESS`、`LOGIN_PROMPT`、`FAVORITE_MUTATION`、`BROWSER_CHALLENGE` | Reviewed ShuYue artifact 執行時可以接觸哪些敏感 Host facility。 |
| Host permission | `REQUEST_LOGIN_UI`、`REQUEST_SOURCE_REFRESH`、`REQUEST_LOGOUT`、`REPORT_DIAGNOSTIC`、`REPORT_USER_MESSAGE`、`REQUEST_BROWSER_CHALLENGE` | Plugin 可要求 Host 處理哪些 system event；只是 request metadata，必須另行 grant。 |
| Negotiated system event | `command.auth.login.request`、`command.source.refresh.request`、`command.auth.logout.request`、`event.diagnostic.message.report` | Package 與 Host 協商的 V1 capability ID。 |

V1 實際 wire message name 與 production handler：

| Wire name | 類型 | 所需條件 | Production 行為 |
|---|---|---|---|
| `auth.login.request` | command | `LOGIN` + `REQUEST_LOGIN_UI` | 交給 exact-source login coordinator。 |
| `source.refresh.request` | command | `REQUEST_SOURCE_REFRESH` | 只 invalidates exact live source，不刷新整個 repository。 |
| `auth.logout.request` | command | `LOGIN` + `REQUEST_LOGOUT` | Host 確認後清理 exact session owner。 |
| `diagnostic.message.report` | event | `REPORT_DIAGNOSTIC` | 寫入有界、會過期的 Host diagnostic log，不直接顯示給使用者。 |

`REPORT_USER_MESSAGE` 目前沒有安全的 production presenter，admission 會拒絕；
`REQUEST_BROWSER_CHALLENGE` 目前只有 permission enum／保留設計，沒有 production system-event handler。
App 自身能從來源設定開啟 Web challenge，不代表 Plugin 已能透過 system event 呼叫它。

事件 ingress 同步且有界，只回傳 admission receipt；`accepted` 代表已入列，不代表登入、刷新或登出
完成。Host 會注入 Plugin 無法偽造的 exact artifact、source、runtime generation 與 active context，
再做 schema、grant、TTL、去重、rate limit 與 queue 檢查。

## JavaScript runtime 與跨平台限制

| 平台 | Script runtime | Web challenge |
|---|---|---|
| Android | Rhino | System WebView 支援專用 profile 與完整 browsing-data deletion 時使用 App 內隔離 WebView；否則只開外部瀏覽器。 |
| iOS／iPadOS | JavaScriptCore | App 內 non-persistent WKWebView。 |
| macOS | Rhino | 獨立原生 non-persistent WKWebView 視窗。 |
| Windows | Rhino | 只開外部預設瀏覽器；Host 無法自動讀回 Edge／Chrome Cookie。 |

Rhino 與公開 JavaScriptCore API 都沒有可依賴的 per-runtime heap／強制中止隔離。Production 因此不執行一般 repository JavaScript，即使它具有 repository 簽章、使用者 execution approval 或自行宣告 digest；這些都不等同 Host code review。只有命中內建 reviewed catalog 的 exact ShuYue artifact，且 Host 核發的 process-local provenance 同時綁定 package/version/original SHA-256、exact `SourceKey` 與最終 evaluated bytes（包含 Host shim），才可進入 in-process engine。Generic manager 會明確清除此 capability。未來若加入獨立 process／OS memory sandbox，可另建 production runtime，不可用 `Runtime.freeMemory` 輪詢等事後檢測取代隔離。測試與離線 compatibility smoke test 只能透過 internal `unsafeForTests()` factory 明示繞過，正式 App 無此公開入口。

Legacy hooks 以同步呼叫為共同相容基線。任意第三方 Promise、`async`、動態 module 或直接 `fetch`
不保證在 Rhino 與 JavaScriptCore 有一致行為；網路應使用 Host bridge。每個 exact source 建立自己的
runtime binding，不能用 package 內來源陣列順序選擇另一個來源。

JVM（Android／Desktop）repository/plugin transport 會將 DNS 結果 pin 到 socket 連線，並透過 OkHttp `newBuilder()` 保留 shared connection pool；iOS production generic network/repository transport 無法安全 pinning 時 fail closed。這會增加 DNS、SHA-256、Ed25519 與 admission 成本，但避免 DNS rebinding。

Runtime identity 是由 exact artifact（package、version、version code、SHA-256）與 exact `SourceKey` 推導的 stable logical ID；runtime 每次載入另分配遞增 generation。這讓 reload 可以保留同一 logical identity，同時讓舊 generation 的 handle、事件與 queued work 失效；package ID 或 source 陣列位置本身都不是 runtime identity。

所有 plugin／repository response headers 在交給 redirect、cookie 或 plugin boundary 前先做 bounded
copy：最多 128 fields；name 最多 256 UTF-8 bytes；單一 value 最多 16 KiB；合計最多 64 KiB；最多
64 個 `Set-Cookie`，每個最多 32 個 attributes；`Location` 最多一個且最多 4 KiB。重複或超限的
`Location`、非法字元與其他超限 header 都拒絕，不以截斷方式繼續處理。Response body 以 bounded
streaming 讀取，`Content-Length` 必須是唯一、合法且不超過該請求 cap；`Content-Encoding` 只接受
identity／gzip／deflate，decoded bytes 仍逐 chunk 受 cap。Framing 矛盾、壓縮後超限或 transport 無法
在讀取時套用 cap 都直接 fail closed，而不是先完整緩衝再補救。只有 exact reviewed ShuYue
compatibility path 會移除 legacy `Connection` header；generic/repository plugin 不能設定或繞過
transport-owned hop-by-hop／framing headers。

## 網路、Cookie、登入與 Web challenge

### 共用 request builder

API Browse 等 request 使用來源 request builder；Reader 圖片／文字與 Download 使用獨立 host content plane。Content plane 只接受 manifest 的 exact `contentOrigins`、`GET`／`HEAD` 和安全 headers，沒有 Cookie、Authorization、proxy key、request body 或其他 secrets；Host fetch bytes 後交給 Coil／內容 consumer。Redirect 仍逐 hop 限制並拒絕 HTTPS downgrade，不能擴大 origin。

Cookie parser 會驗證名稱、值、domain、path、expiry、Secure／HttpOnly 與保守的 public-suffix 防護。
這套 suffix 清單不是完整 Public Suffix List，因此它是額外邊界，不是瀏覽器等級 PSL 的替代品。

### 來源隔離

以下資料都按來源 scope 儲存，不能由任意另一來源查詢：

- Cookie jar；
- username／password 或 token reference；
- source preferences；
- challenge 取得的 User-Agent 與 local-storage allowlist 值；
- Cloudflare Worker Proxy override。

`browserSessionOrigins` 只能額外列出 exact HTTPS API origin，讓同一來源的受控 browser transport
存取必要 API；它不是任意跨站 allowlist。`BROWSER_CHALLENGE` 必須具備 exact artifact 的 reviewed runtime permission，且 target origin 命中 `browserSessionOrigins`。若已保存帳密，Host 的 challenge helper 只會在同源頁面
且 form action 仍同源時自動填入／提交。

### User-Agent 與 Proxy

沒有手動自訂 UA 時，Host 會使用對應設備可用的瀏覽器 UA：Android 取 System WebView、iOS 取
WKWebView、Desktop 取 bundled JavaFX WebKit；Web challenge 擷取到的合法 UA 也會保存在該來源
scope。設定了自訂 UA 時則以自訂值為準。

每個來源的 Cloudflare Worker Proxy 有三種模式：

- 跟隨全域（`global`）：使用「進階設定」中的全域開關；
- 強制啟用（`on`）：只對該來源啟用；
- 強制關閉（`off`）：只對該來源停用。

Worker endpoint 透過 `url` query 傳入目標；API key 以 `X-Proxy-Key` header 傳送。Proxy 不會繞過
來源條款、登入或反自動化政策，也不能保證第三方站點長期可用。

Plugin batch POST 以 128 items 為整批上限，最多 32 個 request 同時 in flight；每 item 的 decoded
response cap 是 128 KiB，整批 aggregate cap 是 4 MiB。transport read-ahead 與已接收結果合計的
decoded peak 約 8 MiB；任何 item 或 aggregate 超限都使整批失敗且不回傳部分結果。較大的單一文件
應改走普通單次請求，不能藉由 batch 擴大額度。

### 平台 challenge 差異

- Android 嗶咔的互動登入仍用 WebView，匯入 token 後的 `picaapi.go2778.com` API 改用原生 DNS-pinned HTTPS。API 保留 token、nonce 與簽章，Origin／Referer 由 Host 固定，不沿用 WebView Cookie jar。此路徑不提供瀏覽器 TLS 指紋；其他 browser-session 來源仍須具備各自可用的綁定傳輸，不能藉此跳過 DNS 檢查。

- Android 只有在 System WebView 同時支援 `MULTI_PROFILE` 與 `DELETE_BROWSING_DATA` 時才提供 embedded challenge。Host 以固定的 non-default dedicated profile 執行，並以 process-global lease 保證同時只有一個 active challenge；不支援時只開外部瀏覽器，隔離準備／清除失敗時則停止該次 challenge。兩種情況都絕不退回 default profile 或 process-global CookieManager。
- Android 每次 challenge 導航前會完整清除 dedicated profile 的 Cookie、network cache 與 JavaScript-readable storage；結束時先 destroy WebView，再完整清除，清除 callback 完成後才釋放 lease。這些前後清除會讓 challenge 的開啟與關閉稍有延遲。
- Android 專用 profile 禁止 Service Worker 的網路與檔案存取，前景導覽與可攔截資源沿用審核 origin 限制。WebViewClient 並非完整網路防火牆，無法覆蓋 WebSocket 等所有瀏覽器通道；此入口僅供已授權的來源互動驗證，不宣稱具有一般 plugin HTTP transport 的完整網路隔離。Cookie 與 User-Agent 從專用 profile 一起擷取，localStorage 僅在頁面回到原來源 origin 時擷取；逾時或頁面改變會要求重試。
- Android、iOS 與 macOS 的 challenge session 和使用者一般瀏覽器資料分離，不會直接讀取既有 Chrome／Safari session；完成後只匯入 Host 驗證過的來源資料。
- Windows 只能開外部瀏覽器，不能自動擷取該瀏覽器的 Cookie。可改用來源設定手動輸入，或匯入
  Netscape `cookies.txt`／常見 JSON cookie export。
- Cookie 匯入上限為 1 MiB／500 筆，並檢查 domain、path、expiry 與字元。

## 書庫、下載、備份與同步邊界

Plugin 只產生遠端 publication／unit／content 結果。Host 會把 exact source binding 寫入共用內容
基礎，Reader 以 portable locator 保存位置；Content plane host-fetch bytes 後交給 Coil／內容 consumer，Download 也不會把 API credentials/proxy secrets 帶入內容取得。

Reviewed ShuYue 的 large-text profile 會增加大文本 materialization／streaming 成本：4–8 MiB 多頁正文
可讀，但 >8 MiB 仍拒絕；正文以 64 KiB、single-use、可取消 stream 傳遞，pending+active aggregate
reservation 限 8 MiB。最壞情況可能同時存在 JS String、JSON 與 Kotlin copies，並使用較高 CPU budget；
影響只在 exact reviewed runtime，generic runtime 沒有 steady-state 影響。

首次 repository load 需 DNS、hash、signature 驗證；content fetch 可能增加 Host bytes/cache 路徑；SourceKey／content identity migration 可能使舊 cache key 失效並重建。OkHttp shared pool 可降低 client 重建成本。Legacy synchronous runtime、exact permission／artifact approval、Mihon metadata-only 與 mixed-package 限制仍是相容性邊界。

| 資料 | Portable backup／sync 狀態 |
|---|---|
| Repository 清單與 metadata | `AppSnapshot.extensionRepositories` 是可攜 source of truth；可備份並依同步規則傳遞。 |
| 書庫、分類、來源 binding、閱讀進度 | 屬可攜 metadata；依備份選項與 E2EE sync allowlist 處理。 |
| 已安裝 Plugin package／script／quarantine | 裝置本機，不進一般 portable backup 或 sync；新裝置需重新取得與審核。 |
| Execution／system-event grants | 裝置與 exact artifact 綁定，不由另一裝置的 metadata 自動授權。 |
| Cookie、密碼、OAuth token、API key、challenge session | 敏感裝置資料，不進 portable backup 或一般 sync。 |
| Download cache／queue、Local source 原始容器 | 預設裝置本機；只有明確的 Content Backup v2／body sync 權利與選項可攜帶內容 body。 |

敏感 KV 的平台保護為 Android Keystore、iOS Keychain、macOS Keychain 與 Windows user-scope DPAPI。
Repository metadata 被同步不代表另一裝置已安裝 package、取得 Cookie 或繼承信任。

## 常見故障判讀

### Repository 沒有顯示套件

- 使用 repository 基底 URL，而不是 GitHub HTML 專案頁；
- 確認基底同層可直接讀取 `repo.json` 與 `index.json`；
- 不要假設檔名決定格式，先檢查回應是否真的是 JSON，而不是登入頁／Cloudflare HTML；
- `referenceOnly`、`installable: false` 與 `legacyCompatibilityOnly` 項目可能不投影為一般可安裝套件。

### 安裝時顯示 digest、sidecar 或契約錯誤

不要略過。重新下載索引後，確認 script bytes、SHA-256、byte size、sidecar、version、source ID、內容
與權限宣告完全一致。更新 script 後卻沒有重算 digest／size，是最常見的發布錯誤。

### 更新後要求重新授權

這是 exact-artifact 模型的預期行為。新 version／digest 或權限集合不是舊 grant 的同一對象；閱讀
差異後重新批准。單純把舊 grant 複製到新 artifact 會破壞更新審核邊界。

### 網站瀏覽器可開啟，但來源請求失敗

依序檢查來源登入、Cookie domain/path/expiry、UA、Referer、Web challenge、`browserSessionOrigins`、
proxy override 與 HTTP status。Windows 外部瀏覽器完成驗證後不會自動回傳 Cookie。真實第三方網站
的反爬蟲與 API 變更仍需要來源層 external smoke test。

### 從另一裝置或還原後找不到來源

Repository 清單、書庫 binding 與 Plugin package 是不同資料。重新安裝／核准 exact package，並在
該裝置重新建立登入／Cookie；不要期待 backup 或 sync 攜帶 executable 與 secrets。

## 發布者清單

1. 先選擇 runtime contract：generic `shinsou` 或已存在 reviewed profile 的 `shuyue`。
2. 固定 package ID 與每個 exact source ID；多來源只能按 ID 選擇。
3. 如實宣告 `contentType`、每來源 `contentKinds` 與 package union。
4. 只宣告實際實作的 capabilities、runtime permissions、Host permissions 與 events。
5. 保持 script metadata、index 與 sidecar 的 package、版本、來源、內容、權限、事件完全一致。
6. 每次改 script 後重算 SHA-256 與 byte size。
7. 尚未由目標 Host 完整支援的教學 fixture 必須設為 `referenceOnly: true`、`installable: false`。
8. 從插件 repository 根目錄執行：

   ```bash
   node test/v2-migration-smoke.js
   git diff --check
   ```

9. 另在 Android／Desktop Rhino 與 iOS JavaScriptCore 做 Host 相容測試；離線 smoke test 不會驗證
   第三方登入、Cloudflare、限流或站點 API。

官方 repository 提供可重用的
[`shinsou-extension-creator` skill](https://github.com/aluo96078/shinsou_plugin/blob/master/skills/shinsou-extension-creator/SKILL.md)
與
[`v2-package-schema.md`](https://github.com/aluo96078/shinsou_plugin/blob/master/skills/shinsou-extension-creator/references/v2-package-schema.md)。

## 已知限制

- Production repository 必須使用 pinned Ed25519 envelope，並以 durable sequence watermark 防 replay／equivocation，同時檢查 payload SHA-256／size。Unsigned 只供明示 developer/local compatibility；目前沒有宣稱官方簽章已部署，發布前必須配置 out-of-band trust root、簽署所有 envelope 並完成 rotation／撤銷與跨平台驗證。
- 官方 smoke test 會比對 index／script metadata／sidecar 的 `contentKinds`、capabilities 與 runtime
  permissions；generic Shinsou Host admission 尚未把這三組欄位全部保留為 runtime enforcement。
- Mihon／Tachiyomi APK 只作 metadata 相容，不能在 Shinsou X 執行。
- Generic Shinsou installable source ID 目前必須可無損轉成 `Long`。
- 任意 Promise／async／direct `fetch` 不保證跨 Rhino／JavaScriptCore 相容。
- Mixed manga + novel package 可由 schema 描述，但 production 安裝路徑尚未完整支援；`example.dual`
  僅是不可安裝的契約 fixture。
- Reviewed ShuYue 只允許內建 catalog 的 exact artifact，不是通用任意 ShuYue script loader。
- `REPORT_USER_MESSAGE` 與 system-event `REQUEST_BROWSER_CHALLENGE` 尚未接通 production handler。
- Windows 外部瀏覽器 challenge 無法自動匯入 Cookie。
- Deterministic test 與 contract 驗證不代表第三方網站、帳號或反爬蟲流程持續可用。

## 實作與規格入口

- [`ExtensionRepositoryClient.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/ExtensionRepositoryClient.kt)：索引 shape 偵測、V2 投影與 sidecar cross-check。
- [`PluginManager.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PluginManager.kt)：安裝、更新、runtime、trust、撤銷與重啟恢復。
- [`PluginVerifier.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PluginVerifier.kt)：SHA-256 與 execution grant。
- [`FilePluginPackageStore.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/FilePluginPackageStore.kt)：獨立、content-addressed、原子 package 儲存。
- [`ExtensionV2Contracts.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/v2/ExtensionV2Contracts.kt)：內容、capability 與 Host facade。
- [`ShuYueReviewedExtensionV2.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/shuyue/ShuYueReviewedExtensionV2.kt)：reviewed catalog、quarantine 與 admission。
- [`PluginSystemEventContracts.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/events/PluginSystemEventContracts.kt)：event wire、Host permission 與 limits。
- [插件系統事件接口架構](PLUGIN_SYSTEM_EVENT_ARCHITECTURE.md)：完整 V1 event 威脅模型與 handler 設計。
- [統一插件儲存庫契約](UNIFIED_PLUGIN_REPOSITORY.md)：repository 格式與本地 fixture。


### Android 內容相容性修正（2026-09-11）

- 嗶咔 1.0.12 的已審核 content origins 增加 storage1.picacomic.com、storage-b.picacomic.com 與實際 redirect 目的地 img.picacomic.com；API、瀏覽器驗證及 credential origins 不變。
- MyComic 增加 biccam.com；動漫屋／漫畫人補已觀察的 cdndm5 精確圖片 shard；包子漫畫增加 s2.bzcdn.net。這些均僅屬內容網路範圍。
- 紳士漫畫 1.3.3 的單張內容上限為 16 MiB，腳本請求仍為 4 MiB。maxContentResponseBytes 為 host-only policy，不可由套件宣告提高；串流讀取保留總上限。較大圖片會增加暫存與解碼記憶體成本。
- DownloadPage 標頭在進入內容平面前使用與封面相同的 sanitizer，避免 DM5 來源 Cookie 令下載全數遭拒，也不把來源憑證傳到圖片 CDN。
- 包子漫畫 1.0.9 使用一次 bounded parseHtml 取得章節欄位，以精簡物件回傳，避免長篇作品超過 bridge call/result element 預算；4,000 章 fixture 與 Android 3,874 章實測通過。未提高通用腳本限制。
- 手機抽樣與限制見 work/android-plugin-validation-20260911/status.md；不代表全部站點、帳號狀態或作品都可用。
- 接續修正：Komiic 的 GraphQL `QUOTA_EXCEEDED` 使用固定 `SHINSOU_SOURCE_QUOTA_EXCEEDED` 分類，閱讀與下載錯誤顯示額度重置提示；不顯示遠端訊息或票券，不自動反覆重試。
- 漫畫櫃的公開 HTTP 操作使用結構化回應，網路失敗／空本文／403／5xx／驗證頁會回報固定失敗分類，僅成功本文可進入短期快取。有效 HTML 中的空結果仍保留原語意。
- 本機倉庫更新需同時更新精確 profile 與 digest/byte-size 准入表；新增 Android LAN fixture 驗證修復套件的 index、sidecar、腳本可取得，且不符大小仍拒絕。
- 嗶哩漫畫公開請求區分 HTTP 失敗與正常空結果，登入／登出保留原路徑。新的來源查詢失敗時不沿用上一查詢的作品列表，避免把舊熱門結果當成新搜尋結果；追加頁失敗仍保留已載入列表。
