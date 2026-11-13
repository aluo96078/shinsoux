# Shinsou X 插件系統

- 狀態：第三方沙箱套件生態已接通；部分 V2 能力仍為保留設計
- 文件基準：2026-11 的 `master` 實作與 [ADR-0002](architecture/0002-sandboxed-third-party-plugin-runtime.md)
- 想寫套件？請先看 **[套件作者指南](PLUGIN_AUTHOR_GUIDE.md)**（CLI、金鑰、權限、發佈、更新與撤回的完整流程）

本文件是 Shinsou X 插件系統的總覽與目前行為基準。Repository 格式細節見
[統一插件儲存庫契約](UNIFIED_PLUGIN_REPOSITORY.md)，system-event 的 wire、安全與排程設計見
[插件系統事件接口架構](PLUGIN_SYSTEM_EVENT_ARCHITECTURE.md)。

> [!IMPORTANT]
> **Shinsou X 沒有官方套件。** 每個套件，包含專案擁有者自己維護的，都是第三方套件：由作者自己的 Ed25519
> 發佈者金鑰簽章，在 App 的沙箱中執行，只取得使用者逐項同意的權限。名稱含「官方」「公式」`official`
> （含 `offical`，不分大小寫與全形、忽略分隔符號）的套件、來源或發佈者，會在安裝與信任發佈者時被拒絕；
> `Unofficial`、`非官方`、`非公式` 這類明確的免責聲明則可以。
>
> 套件程式碼會執行 JavaScript、發送第三方網路請求並可能處理 Cookie／登入狀態。簽章只證明「這份內容出自持有該金鑰的作者」，
> SHA-256 與 sidecar cross-check 只證明下載的 artifact 與作者的宣告一致；兩者都不代表程式行為安全，也不代表任何人審核過它。
> 安全性來自**沙箱隔離＋最小權限＋使用者逐項授權**，不是來自誰發佈。

## 目前 production 狀態

Shinsou X 將來源實作與 App 核心分開：Plugin 負責瀏覽、搜尋、作品 metadata、章節與內容解析；
書庫、Reader、下載、備份、同步、權利判斷與敏感資料儲存仍由 Host 控制。

- **所有套件一律在沙箱中執行**（[ADR-0002 §22](architecture/0002-sandboxed-third-party-plugin-runtime.md)）：
  Android 為每個套件一個 isolated process，macOS／Windows 為 OS 隔離的 helper，iOS 為 WASM 沙箱。
  平台沒有沙箱時一律拒絕執行，絕不退回 in-process 引擎。Rhino／JavaScriptCore 只留在測試與 parity 比對。
- **第三方簽章發佈**：作者用 Ed25519 金鑰簽署整個儲存庫（`shinsou-signed-envelope-v1`），使用者信任**發佈者金鑰**；
  發佈者自己簽署並定期續簽撤回清單，可輪替金鑰、可自我撤銷金鑰。見下方「發佈者信任、撤回與更新」。
- **最小權限**：`EXECUTE_SCRIPT` 與 `NETWORK` 隨安裝授予；敏感權限與每個 `contentHostSuffixes` 家族是使用者在權限頁的個別開關，
  預設關閉，之後可在該套件的「權限」撤銷。
- **同一 `packageId` 可由不同發佈者並存安裝**（見「套件身分與並存安裝」）。
- **開發者模式**：可從本機或使用者輸入的區網位址載入未簽章的開發中套件（見「開發者模式」）。
- 漫畫 `IMAGE_SEQUENCE` 與小說 `PLAIN_TEXT` 來源，可在同一個 package 內混合（見「Package、內容與輸出契約」）。
- Android／Desktop／iOS 共用來源級 Cookie、帳密、偏好、User-Agent、Web challenge 與 Cloudflare Worker Proxy 設定；
  Browse、Reader 與 Download 共用來源身分和 Host 網路層。

這裡的「共用」只表示同一個 Host 安全邊界，不表示共用 credentials。API request plane 可使用已核准的 source headers、Cookie、Referer、UA 與 proxy；Reader 圖片／文字及 Download 則走獨立 host content plane，只允許 exact `contentOrigins`（與使用者授權的 `contentHostSuffixes`）、`GET`／`HEAD` 與安全 headers，明確移除 Cookie、Authorization、proxy key 及其他 credential/proxy secret。Host 取得 bytes 後交給 Coil 或相應內容 consumer。

> 早期的內建 reviewed catalog 與 reviewed ShuYue 執行路徑已移除（ADR-0002 §24）。所有套件都走下方的一般簽章流程，
> App 內沒有任何以套件名稱、網址或 SHA 例外放行的清單。

## 名詞與身分

| 名詞 | 意義 |
|---|---|
| Repository | 提供 `repo.json`、索引、script 與 sidecar 的靜態來源，不是內容託管服務。 |
| Publisher | 以一把 Ed25519 金鑰簽署 repository 的作者；發佈者 ID 是其第一把金鑰的完整指紋（輪替後不變）。 |
| Package | 可版本化的來源套件；可能宣告一個或多個 source。 |
| Artifact | 某一 package version 的確切 JavaScript bytes，以版本、version code、SHA-256 與 byte size 識別。 |
| Sidecar | 安裝前可讀的宣告資料；Host 會把它和索引中的 artifact、來源、內容、權限及事件宣告逐欄交叉核對，且 sidecar 本身也由發佈者簽章。 |
| Source | 使用者在「瀏覽 → 來源」看到並呼叫的內容來源。 |
| Host | Shinsou X App；負責 admission、runtime、網路、敏感儲存、UI 與資料生命週期。 |

### 套件身分與並存安裝

同一個 `packageId` 可以由不同發佈者各自發佈，App 把它們視為**不同的套件**：本機的安裝識別（install key）
把發佈者納入考量，授權、信任、書庫綁定都分開計算。規則（`PluginInstallKeys`）：

- 有發佈者金鑰的套件：`packageId~<發佈者 ID 的 SHA-256 前 16 位十六進位>`；
- 從原社群儲存庫網址安裝、或沒有發佈者金鑰的套件：保留裸 `packageId`，讓既有書庫、進度與下載維持綁定；
- 開發者模式的未簽章套件：`packageId~dev`，永遠不會取代同 id 的簽章安裝；
- `packageId` 不可含 `~`；已發佈的 id 最長 111 字元，確保加上 `~` 與 16 位十六進位後不超過 128 字元。

有發佈者範圍的來源，其數字 source id 由 install key 與 `sourceId` 以 SHA-256 決定性地投影到 `[2^62, 2^63)`，
各裝置推導結果相同（同步以它綁定書庫），發生碰撞則由來源保留檢查拒絕。細節與設計理由見
[ADR-0001「套件身分」](architecture/0001-unified-publication-content-foundation.md)。

來源在 V2 的 canonical 身分仍是：

```text
SourceKey(contractVersion, packageId, sourceId, legacyLongId?)
```

- `packageId + sourceId` 是 V2 的 canonical identity；`legacyLongId` 只用於需要和既有資料／adapter 對接的來源；
- 多來源 package 必須以 exact `sourceId` 選擇來源，不能依陣列位置、顯示名稱或雜湊猜測；
- 書庫 binding、閱讀進度、下載與遷移都應保留完整身分。

目前 installable 的 Shinsou V2 項目仍會投影到 `Long` source id，因此 `sourceId` 必須可無損轉成 64-bit 整數。

## 從 Repository 到 Reader

```text
repository metadata（簽章 envelope，或開發者模式的未簽章文件）
  → 發佈者信任（金鑰 pin、撤回清單新鮮度）
  → index shape / contract detection
  → installable 與相容性檢查（含名稱政策與 tier ceiling）
  → sidecar cross-check
  → script byte size + SHA-256 驗證
  → 權限審核（使用者逐項授權）
  → 沙箱 runtime（exact source）
  → capability-gated Host facade
  → Browse / Library / Reader / Download
```

URL 名稱不是 protocol 判斷依據。Host 先讀取 JSON shape，再判斷是 Shinsou array、
Mihon/Tachiyomi metadata、`shinsou-unified-v1` envelope 或 `shinsou-extension-v2`。

## Repository 相容矩陣

| 索引格式 | Host 行為 | 可執行性 | 主要限制 |
|---|---|---|---|
| 傳統 Shinsou `index.json` array | 讀取 JavaScript package metadata | 只有簽章發佈者的套件、或開發者模式下的開發中套件可執行；其餘（含未簽章且不在開發者模式者）保持 inert | 舊索引可能沒有發布端 digest；不執行沒有發佈者金鑰的未簽章套件。 |
| Mihon／Tachiyomi `index.min.json` | 建立 metadata-only stub source | APK 不執行 | 只提供相容 metadata，不代表支援 Android APK extension runtime。 |
| `shinsou-unified-v1` | 同一 URL 投影 `shinsou`／`legacy` 與 `shuyue` 清單 | 依各自 admission 路徑 | 保留給遷移與相容；不要由 URL 或 hostname 猜 contract。 |
| `shinsou-extension-v2`、`contractVersion: 2` | 驗證 V2 package、sidecar、digest、內容與權限宣告 | `contract: "shinsou"` 在沙箱執行（需簽章與使用者授權）；`shuyue` 項目會被略過 | `referenceOnly`、`installable: false`、`legacyCompatibilityOnly` 不可作一般新安裝。 |

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
permissions、requested Host permissions、system events、script URL、SHA-256 與 byte size。作者 CLI 的 `build` 會完整比對這些欄位。App 的 generic Shinsou admission 目前會強制比對 package、
version、artifact、內容 type、來源、browser origins、browser image origins、events 與 requested Host permissions。`contentKinds`、generic `capabilities` 與 `runtimePermissions` 的
完整三方 parity 目前仍以作者端的 `build` 檢查為主，尚未全部成為 generic Host 的 runtime admission gate。

核心 V2 內容種類為：

| `ContentKind` | 內容形式 |
|---|---|
| `IMAGE_SEQUENCE` | 有界圖片頁序列，可攜帶 progression、layout 與受控 transform plan。 |
| `PLAIN_TEXT` | inline UTF-8、Host-fetch resource 或有界 cursor/chunk stream。 |
| `EPUB_SPINE` | 遠端 EPUB package graph、spine documents 與資源描述。 |

V2 Host facade 在每次呼叫前檢查 exact `SourceKey` 與 capability，並再次驗證輸入範圍、URI、header
hint、頁數、資源數、文字 chunk、回應 bytes 與輸出 source scope。Plugin 取得的是受限 Host bridge，
不是任意核心服務或原始 credential store。

### 小說（`PLAIN_TEXT`）與混合套件

- 宣告 `PLAIN_TEXT` 的來源實作 `getChapterText(chapter)`，回傳一個字串（或 `{ text }`），**最多 4 MiB**
  （約 130 萬個中文字；沙箱單一 Result frame 的上限）。這個值比一般結果的 1 MiB 單字串上限大，只套用在宣告了
  `PLAIN_TEXT` 的來源。超過上限會以 `LIMIT_RESULT`（結果過大）失敗，不會讓套件被判定為當機。
- `IMAGE_SEQUENCE` 來源實作 `getPageList(chapter)`。
- **一個 package 可以混合**：同一個 package 內的每個來源各自宣告 `contentKinds`，漫畫來源用 `getPageList`、
  小說來源用 `getChapterText`。Host 依 exact `sourceId` 選擇來源，不依陣列順序。
- 沙箱把所有套件的結果限制在 4 MiB 內。
- 對仍使用舊式 hook 的腳本，Generic adapter 保留既有 image-oriented hook 與同步 JavaScript 假設；
  「V2 核心能表示某種 payload」不等於所有 legacy script 都已能產生該 payload。

## 發佈者信任、撤回與更新

### 簽章與信任

- 發佈者以 Ed25519 簽署 `repo.json`、`index.json`、每個 sidecar 與 `publisher-revocations.json`
  （`shinsou-signed-envelope-v1`；簽章訊息綁定文件 ID 與儲存庫網址，所以每個鏡像各簽一份）。
  每種文件各自有嚴格遞增的 `sequence`，App 拒絕重播與同 sequence 不同內容。
- 使用者**首次使用即信任**（TOFU）發佈者金鑰，或輸入／經 `shinsou://add-repository?url=…&fp=…` 連結帶入
  **完整指紋**並比對。「套件安全」中的「一律要求比對指紋」是嚴格模式：沒有完整指紋或指紋不符就不能信任；
  切換為嚴格模式後，只用 TOFU 信任的發佈者顯示為「待比對指紋」，已安裝套件繼續執行，但在比對完成前不安裝新套件、不更新。
- 信任的單位是**發佈者金鑰**：同一把金鑰從不同鏡像網址出現不需重新信任；同一網址出示另一把金鑰一律視為
  金鑰替換（`KeySubstitution`）而被拒絕。
- 連結不會自行加入 repository：有簽章的 repository 顯示信任對話框，連結帶的指紋唯讀顯示且一定比對。
- 名稱含「官方」「公式」`official` 的發佈者在信任時被拒絕。

### 撤回、到期與金鑰事件

| 機制 | 文件 | Host 行為 |
|---|---|---|
| 版本撤回 | 發佈者簽署的 `publisher-revocations`（artifact SHA-256 清單與 `expiresAt`） | 對應已安裝 artifact 立即卸載 runtime、轉為 inert，提示更新 |
| 撤回清單新鮮度 | 同上 | **新安裝與更新一律要求**該發佈者有一份已驗證、未過期的撤回清單（可為空）；過期後不再從該發佈者安裝或更新，已安裝的繼續執行並持續顯示警示 |
| 金鑰輪替 | `publisher-key-rotation`（舊金鑰簽 envelope、新金鑰簽聲明，雙簽） | 自動更新 pin，舊金鑰退休，不需重新信任 |
| 金鑰自我撤銷 | `publisher-key-revoked`（由被撤銷的金鑰簽署） | 撤銷目前金鑰：該發佈者全部套件 inert，須重新信任新金鑰 |
| 遺失私鑰 | 無 | 無法自動恢復；使用者須明確同意改信任新金鑰，新金鑰視為新的發佈者，授權重新審核 |
| 使用者本機封鎖 | 不簽章、不下載，只存在本機 | 可封鎖單一套件、單一 artifact 或整個發佈者；不進備份與同步 |

沒有中央 denylist：Shinsou 不簽署、不發佈、不維護任何遠端封鎖清單，App 不會因為任何第三方的判斷而遠端停用使用者安裝的套件。
唯一的遠端停用來源是發佈者自己簽署的撤回清單與金鑰撤銷。降版（`versionCode`／digest 回退）一律拒絕。

### 從未簽章改為簽章發佈

使用者已加入的未簽章 repository 若改為簽章發佈，App 會顯示「已改為由發佈者簽章：請確認發佈者以繼續更新」，
使用者確認發佈者後才繼續更新。未簽章時期的授權，在第一個簽章版本**沒有要求任何新東西**時延續。

## 開發者模式

「套件安全（Package security）」對話框中的「開發者模式（Developer mode）」（預設關閉，僅限本機設定）：

- 載入**未簽章**的開發儲存庫，來源只能是這台裝置（`127.0.0.1`、`localhost`、`::1`）或使用者輸入的私有區網 IPv4
  位址（`10/8`、`172.16/12`、`192.168/16`；只接受數字位址，**不接受主機名稱**，最多 8 個）。
- 開發儲存庫可使用 HTTP，但**套件自己的請求仍然只能是 HTTPS**，網路規則不放寬。
- 這類套件以 `packageId~dev` 安裝、標示「開發中」，**只在開發者模式開啟時執行**；重新 build 後授權維持，
  每當 artifact digest 改變就顯示一次更新。仍在沙箱中執行，沒有任何額外放寬。
- 搭配作者 CLI 的 `shinsou-plugin serve` 使用。流程見[作者指南](PLUGIN_AUTHOR_GUIDE.md)。

## 安裝、更新與撤銷生命週期

### 第三方套件（`contract: "shinsou"`）

1. Host 讀取 repository metadata，驗證發佈者簽章與撤回清單新鮮度，拒絕不可安裝狀態、誤導性名稱與不安全的檔名／路徑。
2. 將 package、version、artifact、內容、來源、事件與 requested permissions 的宣告在 index、sidecar 與腳本三處交叉核對。
3. `SandboxTierCeiling` 在寫入任何 byte 之前拒絕超出上限的宣告（例如 `REPORT_USER_MESSAGE`、公開後綴的 `contentHostSuffixes`）。
4. 下載 script，驗證 byte size 與 SHA-256。
5. 使用者在權限頁逐項審核：`EXECUTE_SCRIPT` 與 `NETWORK` 隨安裝授予；敏感權限與每個 `contentHostSuffixes` 家族預設關閉。
6. 先寫 content-addressed script，再以原子寫入 `package.json` 作為 package commit point；之後在沙箱建立 runtime。
7. 重啟時只重建仍符合信任與授權的 package；被撤回、被封鎖、發佈者金鑰被撤銷或被替換者保持 inert。

每個 package 獨立儲存。V2 package store 以 script 的 SHA-256 作為 immutable content address，先寫入
`script-<sha256>.js`，再以原子 `package.json` 作為 commit point；寫入後會 read back 並重新驗證 script
SHA-256、嚴格 UTF-8、metadata 與 package ID。單一損壞或不相容 package 會保持已安裝但不執行，不會
阻塞其他 package 啟動；舊的未引用 script 即使清理失敗也不會再次被發現。Key-value store 的 V2
record 一旦存在但損壞，會 fail closed，絕不退回舊 V1 metadata/script split keys；只有沒有 V2 record
時才可進行受限的 legacy migration。

### 原 ShuYue 格式套件

App 不再執行 ShuYue 格式（`contract: "shuyue"`）的套件；儲存庫中這類項目會被略過。原本的 ShuYue 小說來源改寫成一般
Shinsou 套件後重新簽章發佈，只要保留下表的 package ID、字串 `sourceId` 與數字 `legacyLongId`，書庫、閱讀進度與已儲存的登入
就會直接沿用（`migration/shuyue/ShuYueLegacySources.kt`）。改寫方式見作者指南〈從 ShuYue 格式改寫〉。

| package | `sourceId` | `legacyLongId` |
|---|---|---|
| `zh.wenku8` | `zh.wenku8` | `-9110000000000001` |
| `zh.wenku8.api` | `zh.wenku8.api` | `-9110000000000002` |
| `zh.biquge.tw` | `zh.biquge.tw` | `-9110000000000003` |
| `zh.bilimanga` | `zh.bilimanga.novel` | `-9110000000000004` |
| `zh.bilimanga` | `zh.bilimanga.manga` | `-9110000000000005` |

Repository fetcher 的 index／sidecar response 預設上限仍為 2 MiB／512 KiB；已安裝 package store 的
V2 bounds 則是 128 KiB key-value index、8 MiB script、512 KiB package metadata、256 packages、每包
256 sources，以及 package store 目錄最多 1,024 個 discovered files。Repository／package loader 遇到
超限、無效 UTF-8、SHA-256 不符、metadata/readback 不一致或不安全路徑時拒絕或 fail closed；這些是
目前 Host 預設值，不是套件可自行提高的額度。Repository transport 另受 redirect hop policy 約束。

內容與 runtime 另受 response bytes、log、頁數／資源數、文字 chunk、EPUB graph 等有界 resource limits 約束；任何超限均拒絕或 fail closed。Runtime／system-event 權限與 artifact approval 是獨立檢查，repository 宣告不會自動授權。

### 更新、重新授權與卸載

- 授權綁定**確切版本**與發佈者金鑰：同一 `packageId` 由不同發佈者發佈是不同身分，授權不互通。
- **更新沒有要求任何新東西時，授權自動沿用（D4）並通知使用者。** 必須全部成立：新舊版本來自同一個發佈者金鑰；
  新版本要求的 host／runtime 權限與 `contentHostSuffixes` 都是舊版本要求過的子集；新版本的每個來源舊版都存在，
  且 request／credential／content／browser-session origin 都沒有新增（逐項 exact 比對）。沿用的是「舊授權縮到新版要求的範圍」：
  使用者拒絕過的仍然拒絕，不會多出任何東西。
- **否則**（新權限、新後綴、新來源、新 origin，或換了發佈者金鑰、套件沒有發佈者）使用者必須在**安裝前**審核這次更新，
  **舊版本在使用者接受之前繼續執行**。
- 降版（`versionCode`／digest 回退）一律拒絕。
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

下列概念用途不同，不應合稱為同一種「插件權限」。有效權限 =
套件宣告（index ∩ sidecar ∩ script，三方一致）∩ Host 支援 ∩ 沙箱 tier 上限（Host 常數，套件與使用者都不能提高）
∩ 使用者對 exact artifact 的授權。

| 層級 | 目前識別字 | 意義 |
|---|---|---|
| Source capability | `CATALOGUE`、`LATEST`、`BROWSE`、`METADATA`、`UNITS`、`CONTENT`、`SEARCH`、`LOGIN`、`FAVORITE`、`PREFERENCES` | Host 可以向來源呼叫哪些功能；每次呼叫由 V2 facade 檢查。 |
| Runtime permission（隨安裝） | `EXECUTE_SCRIPT`、`NETWORK` | 執行腳本，並連到宣告的 exact origin。 |
| Runtime permission（敏感，逐項開關、預設關閉、可隨時撤銷） | `COOKIE_STORAGE`、`COOKIE_READ`、`CREDENTIAL_ACCESS`、`LOGIN_PROMPT`、`FAVORITE_MUTATION`、`BROWSER_CHALLENGE`、`BROWSER_IMAGES` | 接觸敏感 Host facility：寫入 Cookie、讀取 Cookie 值、帳密、登入提示、修改收藏、Web challenge、以私密瀏覽器載入 `browserImageOrigins` 的圖片。 |
| 網路範圍（敏感，逐項） | 每個 `contentHostSuffixes` 家族 | 動態 CDN 後綴，只放寬無憑證的內容平面，限 443（後綴加 `:1024-65535` 時也允許非特權連接埠）。 |
| Host permission | `REQUEST_LOGIN_UI`、`REQUEST_SOURCE_REFRESH`、`REQUEST_LOGOUT`、`REPORT_DIAGNOSTIC`、`REPORT_USER_MESSAGE`、`REQUEST_BROWSER_CHALLENGE` | Plugin 可要求 Host 處理哪些 system event；只是 request metadata，必須另行 grant。`REPORT_USER_MESSAGE` 永遠不授予第三方。 |
| Negotiated system event | `command.auth.login.request`、`command.source.refresh.request`、`command.auth.logout.request`、`event.diagnostic.message.report` | Package 與 Host 協商的 V1 capability ID。 |

登入在**一次性沙箱**中執行：只有 App 啟動的 `login` hook 能讀寫密碼，網路只允許 `credentialOrigins`，結束即銷毀；
一般 runtime 永遠拿不到密碼。沒有 `COOKIE_READ` 時，Host 仍會在請求中帶上它代管的 Cookie，但腳本看不到值。

V1 實際 wire message name 與 production handler：

| Wire name | 類型 | 所需條件 | Production 行為 |
|---|---|---|---|
| `auth.login.request` | command | `LOGIN` + `REQUEST_LOGIN_UI` | 交給 exact-source login coordinator。 |
| `source.refresh.request` | command | `REQUEST_SOURCE_REFRESH` | 只 invalidates exact live source，不刷新整個 repository。 |
| `auth.logout.request` | command | `LOGIN` + `REQUEST_LOGOUT` | Host 確認後清理 exact session owner。 |
| `diagnostic.message.report` | event | `REPORT_DIAGNOSTIC` | 寫入有界、會過期的 Host diagnostic log，不直接顯示給使用者。 |

`REPORT_USER_MESSAGE` 沒有安全的 production presenter，admission 會拒絕；
`REQUEST_BROWSER_CHALLENGE` 目前只有 permission enum／保留設計，沒有 production system-event handler。
App 自身能從來源設定開啟 Web challenge，不代表 Plugin 已能透過 system event 呼叫它。
（腳本直接使用 `bridge.browserSessionRequest` 則屬於 runtime permission `BROWSER_CHALLENGE`，見「網路」一節。）

事件 ingress 同步且有界，只回傳 admission receipt；`accepted` 代表已入列，不代表登入、刷新或登出
完成。Host 會注入 Plugin 無法偽造的 exact artifact、source、runtime generation 與 active context，
再做 schema、grant、TTL、去重、rate limit 與 queue 檢查。


## JavaScript runtime 與跨平台限制

所有套件在**沙箱**中以 QuickJS 執行，沒有網路、檔案與子程序權限，只能經 SandboxProtocol v1 的 bridge 向 Host 請求：

| 平台 | 沙箱 | Web challenge |
|---|---|---|
| Android | 每個套件一個 isolated process | System WebView 支援專用 profile 與完整 browsing-data deletion 時使用 App 內隔離 WebView；否則只開外部瀏覽器。 |
| iOS／iPadOS | WAMR WebAssembly 直譯器 | App 內 non-persistent WKWebView。 |
| macOS | `sandbox_init` deny-all 的 helper | 獨立原生 non-persistent WKWebView 視窗。 |
| Windows | Low Privilege AppContainer＋Job object 的 helper | 只開外部預設瀏覽器；Host 無法自動讀回 Edge／Chrome Cookie。 |

- 平台沒有可用的沙箱時一律拒絕執行（fail closed），絕不退回 in-process 引擎。Rhino 與 JavaScriptCore 不再用來執行
  repository 套件，只留在測試與離線 parity 比對。
- 沙箱為每個套件提供獨立的記憶體、時間與 bridge 呼叫上限（JS heap 64 MiB、單次 hook 30 秒、每次 4,096 次 bridge 呼叫、
  結果 4 MiB 等）；超限以穩定錯誤碼失敗，不回傳部分結果；短時間內重複崩潰會自動停用該套件。Host 以 LRU 與閒置回收限制同時存活的沙箱數量。
- Hook 可回傳 Promise；非阻塞的網路請求用 `bridge.net.fetch`（同時最多 6 個）。其餘 bridge API 見[作者指南](PLUGIN_AUTHOR_GUIDE.md)。
- 每個 exact source 建立自己的 runtime binding，不能用 package 內來源陣列順序選擇另一個來源。

JVM（Android／Desktop）repository/plugin transport 會將 DNS 結果 pin 到 socket 連線，並透過 OkHttp `newBuilder()` 保留 shared connection pool；iOS generic network/repository transport 無法安全 pinning 時 fail closed。這會增加 DNS、SHA-256、Ed25519 與 admission 成本，但避免 DNS rebinding。

Runtime identity 是由 exact artifact（package、version、version code、SHA-256）與 exact `SourceKey` 推導的 stable logical ID；runtime 每次載入另分配遞增 generation。這讓 reload 可以保留同一 logical identity，同時讓舊 generation 的 handle、事件與 queued work 失效；package ID 或 source 陣列位置本身都不是 runtime identity。

所有 plugin／repository response headers 在交給 redirect、cookie 或 plugin boundary 前先做 bounded
copy：最多 128 fields；name 最多 256 UTF-8 bytes；單一 value 最多 16 KiB；合計最多 64 KiB；最多
64 個 `Set-Cookie`，每個最多 32 個 attributes；`Location` 最多一個且最多 4 KiB。重複或超限的
`Location`、非法字元與其他超限 header 都拒絕，不以截斷方式繼續處理。Response body 以 bounded
streaming 讀取，`Content-Length` 必須是唯一、合法且不超過該請求 cap；`Content-Encoding` 只接受
identity／gzip／deflate，decoded bytes 仍逐 chunk 受 cap。Framing 矛盾、壓縮後超限或 transport 無法
在讀取時套用 cap 都直接 fail closed，而不是先完整緩衝再補救。套件不能設定或繞過 transport-owned
hop-by-hop／framing headers。


## 網路、Cookie、登入與 Web challenge

### 共用 request builder

API Browse 等 request 使用來源 request builder；Reader 圖片／文字與 Download 使用獨立 host content plane。Content plane 只接受 manifest 的 exact `contentOrigins`、使用者已授權的 `contentHostSuffixes`（可註冊網域或其子網域，預設只限 443；後綴加 `:1024-65535` 時也允許非特權連接埠；上限 8 項、不得為 public suffix 或 IP）、`GET`／`HEAD` 和安全 headers，沒有 Cookie、Authorization、proxy key、request body 或其他 secrets；Host fetch bytes 後交給 Coil／內容 consumer。Redirect 仍逐 hop 限制並拒絕 HTTPS downgrade，不能擴大 origin。

Cookie parser 會驗證名稱、值、domain、path、expiry、Secure／HttpOnly 與 public-suffix 防護；
`contentHostSuffixes` 與 Cookie domain 都使用 App 內建的 Public Suffix List 判斷是否為公開後綴。

### 來源隔離

以下資料都按來源 scope 儲存，不能由任意另一來源查詢：

- Cookie jar；
- username／password 或 token reference；
- source preferences；
- challenge 取得的 User-Agent 與 local-storage allowlist 值；
- Cloudflare Worker Proxy override。

`browserSessionOrigins` 只能額外列出 exact HTTPS API origin，讓同一來源的受控 browser transport
存取必要 API；它不是任意跨站 allowlist。`BROWSER_CHALLENGE` 是敏感 runtime permission，第三方套件同樣可以宣告，使用者在權限頁開啟後才生效，且 target origin 必須命中 `browserSessionOrigins`（Cloudflare 等 Web challenge 即以此為前提）。若已保存帳密，Host 的 challenge helper 只會在同源頁面
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

長文字章節（`PLAIN_TEXT`，最多 4 MiB）會在沙箱結果、Host 與 Reader 之間各有一份複本，記憶體成本與文字大小成正比；超過 4 MiB 以 `LIMIT_RESULT` 失敗。

首次 repository load 需 DNS、hash、signature 驗證；content fetch 可能增加 Host bytes/cache 路徑；SourceKey／content identity migration 可能使舊 cache key 失效並重建。OkHttp shared pool 可降低 client 重建成本。Legacy 同步 hook 假設、exact permission／artifact approval 與 Mihon metadata-only 仍是相容性邊界。

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
- `referenceOnly`、`installable: false` 與 `legacyCompatibilityOnly` 項目可能不投影為一般可安裝套件；
- 簽章儲存庫需要有效的 `publisher-revocations.json`；過期或缺少時不能安裝或更新（作者需執行 `shinsou-plugin refresh`）；
- 未簽章的儲存庫只有在開發者模式且位址為本機／已輸入的區網位址時才會載入。

### 安裝時顯示 digest、sidecar 或契約錯誤

不要略過。重新下載索引後，確認 script bytes、SHA-256、byte size、sidecar、version、source ID、內容
與權限宣告完全一致。更新 script 後卻沒有重算 digest／size，是最常見的發布錯誤。

### 更新後要求重新授權

若更新要求了任何新的權限、後綴、來源或 origin（或換了發佈者金鑰），App 會在安裝前請你審核，舊版本在你接受前繼續執行；
只有更新沒有要求任何新東西時，授權才會自動沿用並通知你（D4）。

### 網站瀏覽器可開啟，但來源請求失敗

依序檢查來源登入、Cookie domain/path/expiry、UA、Referer、Web challenge、`browserSessionOrigins`、
proxy override 與 HTTP status。Windows 外部瀏覽器完成驗證後不會自動回傳 Cookie。真實第三方網站
的反爬蟲與 API 變更仍需要來源層 external smoke test。

### 從另一裝置或還原後找不到來源

Repository 清單、書庫 binding 與 Plugin package 是不同資料。重新安裝／核准 exact package，並在
該裝置重新建立登入／Cookie；不要期待 backup 或 sync 攜帶 executable 與 secrets。


## 發布者清單

完整步驟見[套件作者指南](PLUGIN_AUTHOR_GUIDE.md)。摘要：

1. `shinsou-plugin keygen` 產生並備份發佈者金鑰；公布**完整指紋**。
2. 固定 `packageId` 與每個 exact `sourceId`；多來源只能按 ID 選擇。名稱不可含「官方」「official」「公式」。
3. 如實宣告 `contentType`、每來源 `contentKinds` 與 package union；小說來源實作 `getChapterText`。
4. 只宣告實際使用的 capabilities、runtime permissions、Host permissions 與 events；origin 一律 exact HTTPS。
5. `build`（三方宣告一致）→ `lint` → `test` → 用 `serve` 搭配 App 開發者模式試用。
6. 每次發佈遞增 `versionCode`，`sign` 並把 `.shinsou/state.json` 一起提交。
7. 定期 `refresh` 撤回清單（預設 30 天到期），否則使用者無法安裝或更新。
8. 需要時用 `revoke` 撤回版本、`rotate` 輪替金鑰、`revoke-key` 自我撤銷。
9. 另在各平台做 Host 相容測試；離線測試不會驗證第三方登入、Cloudflare、限流或站點 API。


## 已知限制

- 簽章發佈需要使用者自行確認發佈者（TOFU 或比對指紋）；Shinsou 不替任何發佈者背書。
- 遺失發佈者私鑰無法自動恢復，只能發佈新金鑰並由使用者明確改信任。
- 撤回清單過期後，該發佈者不能再安裝或更新套件；已安裝的套件繼續執行並顯示警示。
- Mihon／Tachiyomi APK 只作 metadata 相容，不能在 Shinsou X 執行。
- Installable `sourceId` 須是 64-bit 整數，或是小寫點分隔識別字（如 `zh.wenku8`）並以 `legacyLongId` 提供數字 ID。
- 沙箱把單一結果限制在 4 MiB；單一章節文字超過約 130 萬個中文字會失敗。
- `REPORT_USER_MESSAGE` 永遠不授予第三方；system-event `REQUEST_BROWSER_CHALLENGE` 尚未接通 production handler。
- 開發者模式的區網位址只接受私有 IPv4 數字位址，不接受主機名稱。
- Windows 外部瀏覽器 challenge 無法自動匯入 Cookie。
- Deterministic test 與 contract 驗證不代表第三方網站、帳號或反爬蟲流程持續可用。


## 實作與規格入口

- [套件作者指南](PLUGIN_AUTHOR_GUIDE.md)：作者端完整流程。
- [ADR-0002](architecture/0002-sandboxed-third-party-plugin-runtime.md)：沙箱、權限模型與發佈者信任的設計與決策。
- [ADR-0001「套件身分」](architecture/0001-unified-publication-content-foundation.md)：install key 與 source id 投影。
- [`ExtensionRepositoryClient.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/ExtensionRepositoryClient.kt)：索引 shape 偵測、V2 投影與 sidecar cross-check。
- [`PluginManager.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PluginManager.kt)：安裝、更新、runtime、trust、撤銷與重啟恢復。
- [`PublisherTrust.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PublisherTrust.kt)：發佈者信任、輪替、撤回清單與金鑰撤銷。
- [`RepositoryTrust.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/RepositoryTrust.kt)：signed envelope 驗證與 replay／equivocation watermark。
- [`PluginInstallKeys.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PluginInstallKeys.kt)：install key 與 source id 投影。
- [`SandboxTierCeiling.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/SandboxTierCeiling.kt)：第三方套件永遠拿不到的上限。
- [`UpdateGrantContinuation.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/UpdateGrantContinuation.kt)：更新時沿用授權（D4）。
- [`PluginDeveloperMode.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PluginDeveloperMode.kt)：開發者模式。
- [`PluginNamePolicy.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PluginNamePolicy.kt)：名稱政策。
- [`sandbox/SandboxBridgeDispatcher.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/sandbox/SandboxBridgeDispatcher.kt)：bridge 操作與權限檢查。
- [`PluginVerifier.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/PluginVerifier.kt)：SHA-256 與 execution grant。
- [`FilePluginPackageStore.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/FilePluginPackageStore.kt)：獨立、content-addressed、原子 package 儲存。
- [`ExtensionV2Contracts.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/v2/ExtensionV2Contracts.kt)：內容、capability 與 Host facade。
- [`PluginSystemEventContracts.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/events/PluginSystemEventContracts.kt)：event wire、Host permission 與 limits。
- [`fixtures/plugin-contract/v1/`](../fixtures/plugin-contract/v1/README.md)：App 與作者 CLI 共用的位元組級契約測試向量。
- [插件系統事件接口架構](PLUGIN_SYSTEM_EVENT_ARCHITECTURE.md)：完整 V1 event 威脅模型與 handler 設計。
- [統一插件儲存庫契約](UNIFIED_PLUGIN_REPOSITORY.md)：repository 格式與本地 fixture。


### 歷史紀錄：Android 內容相容性修正（2026-09-11）

> 以下是內建 reviewed catalog 時期對特定來源的修正紀錄，只對當時 catalog 內的 exact artifact 有意義，
> 不構成第三方套件的規則，也不表示這些來源在任何意義上是「官方」。

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
