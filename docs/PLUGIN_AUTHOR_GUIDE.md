# Shinsou X 套件作者指南

- 對象：想為 Shinsou X 寫、簽章並發佈來源套件（plugin）的作者
- 內容：從零開始，到使用者能安裝、更新、撤回與輪替金鑰
- 相關文件：[插件系統總覽](PLUGIN_SYSTEM.md)（App 端行為基準）、
  [ADR-0002](architecture/0002-sandboxed-third-party-plugin-runtime.md)（沙箱與權限設計）、
  `@shinsou/plugin` CLI 的 `README.md`（指令細節）

> [!IMPORTANT]
> **Shinsou X 沒有官方套件。** 每一個套件，包含專案擁有者自己維護的，都是第三方套件：由作者自己的
> Ed25519 發佈者金鑰簽章，在 App 的沙箱中執行，只拿到使用者逐項同意的權限。沒有審核名單、沒有中央上架流程，
> 也沒有任何人能代你背書或遠端停用你的套件；唯一的遠端停用來源是**你自己**簽署的撤回清單。

本文以 App 與 CLI 目前的程式碼為準。無法由程式碼確認的地方標記為「**【未驗證】**」；
CLI 與 App 之間目前已知不一致的地方集中在[最後一節](#已知落差與未驗證項目)。

## 目錄

1. [先備知識與整體流程](#1-先備知識與整體流程)
2. [安裝 CLI](#2-安裝-cli)
3. [產生金鑰與金鑰安全](#3-產生金鑰與金鑰安全)
4. [建立專案：`init`](#4-建立專案init)
5. [`shinsou.plugin.json` 欄位](#5-shinsouplugin-json-欄位)
6. [腳本可以呼叫的 API](#6-腳本可以呼叫的-api)
7. [權限與網路規則](#7-權限與網路規則)
8. [命名規則](#8-命名規則)
9. [`build`、`lint`、`test`](#9-buildlint-與-test)
10. [在 App 上試用：開發者模式與 `serve`](#10-在-app-上試用開發者模式與-serve)
11. [簽章與發佈](#11-簽章與發佈)
12. [使用者如何加入儲存庫與核對指紋](#12-使用者如何加入儲存庫與核對指紋)
13. [發佈更新與 D4 自動沿用授權](#13-發佈更新與-d4-自動沿用授權)
14. [撤回某個版本](#14-撤回某個版本)
15. [續簽撤回清單（必做）](#15-續簽撤回清單必做)
16. [金鑰輪替、自我撤銷與遺失金鑰](#16-金鑰輪替自我撤銷與遺失金鑰)
17. [從未簽章改為簽章發佈](#17-從未簽章改為簽章發佈)
18. [已知落差與未驗證項目](#已知落差與未驗證項目)

## 1. 先備知識與整體流程

- 套件是**一支 JavaScript 腳本**加上宣告（`shinsou.plugin.json`）。腳本在沙箱裡執行，沒有網路、檔案或子程序權限，
  只能經 bridge 向 App 提出請求（見[第 6 節](#6-腳本可以呼叫的-api)）。
- 儲存庫是一組**靜態檔案**（`repo.json`、`index.json`、`sidecars/`、`plugins/`、`publisher-revocations.json`），
  可以放在 GitHub Pages、raw GitHub 或任何靜態主機。你不需要伺服器。
- 每份 JSON 都由你的發佈者金鑰簽成 `shinsou-signed-envelope-v1`。使用者信任的是**你的金鑰**，不是網址。

```text
keygen → init → 寫腳本 → build → lint → test
       → serve（App 開發者模式試用，未簽章）
       → sign → 放到靜態主機 → 使用者加入儲存庫、核對指紋、安裝
之後：改程式 → 遞增 versionCode → build/lint/test → sign
      revoke（撤回版本）／ refresh（定期續簽）／ rotate／revoke-key
```

## 2. 安裝 CLI

需要 Node.js 20 以上。

`@shinsou/plugin` **目前尚未發佈到 npm**，`npm install @shinsou/plugin` 暫時不會成功。請從原始碼使用：

```bash
git clone <shinsou-plugin-cli 的位置> shinsou-plugin-cli
cd shinsou-plugin-cli
npm install
npm run build        # 產生 dist/cli.js
npm link             # 讓 shinsou-plugin 指令全域可用
```

不想 `npm link` 的話，也可以直接用 Node 執行建置後的檔案：

```bash
node /path/to/shinsou-plugin-cli/dist/cli.js <指令> [選項]
```

下文一律寫成 `shinsou-plugin <指令>`。`shinsou-plugin help` 會列出全部指令。

> `init` 產生的 `package.json` 內有 `"@shinsou/plugin": "^0.1.0"` 這個 devDependency。CLI 發佈到 npm 之前，
> 對它做 `npm install` 會失敗；請改用上面的 `npm link` 或 `node dist/cli.js`，必要時把該行從 `package.json` 刪掉。

## 3. 產生金鑰與金鑰安全

```bash
shinsou-plugin keygen --name mykey            # 互動模式會詢問密語（留空表示不加密）
shinsou-plugin keygen --name mykey --passphrase   # 強制設定密語
shinsou-plugin fingerprint --key mykey         # 隨時再看指紋
```

- 金鑰檔存在 `~/.config/shinsou-plugin/keys/<名稱>.json`（目錄 `0700`、檔案 `0600`），
  可用環境變數 `SHINSOU_PLUGIN_HOME` 改位置。已存在的名稱需加 `--force` 才會覆寫，而覆寫會讓舊金鑰簽過的儲存庫無法再更新。
- 設了密語時，種子（私鑰）以 scrypt（N=2^17, r=8, p=1）＋ XChaCha20-Poly1305 加密。非互動環境用
  `SHINSOU_PLUGIN_PASSPHRASE`。
- 會印出兩種指紋：
  - **完整指紋** `sha256:<SHA-256(公鑰) 的 base64url>`：App 比對一律用這個。
  - **顯示指紋**：前 16 bytes 的大寫十六進位、每 4 字元一組，只供人眼看，**不能拿來比對**。
- **發佈者 ID** 是你第一把金鑰的完整指紋，之後輪替也不會變。

### 金鑰安全與備份

- 私鑰是你身分的唯一憑證：誰拿到它，誰就能以你的名義發佈會在使用者裝置上執行的程式。**加密並備份金鑰檔**
  （例如放進密碼管理器或離線儲存），不要提交到版本控制。
- **遺失私鑰無法自動恢復**。你只能產生新金鑰、發佈新的儲存庫，讓使用者手動改信任新金鑰；
  該發佈者的既有授權會重新進入待審（見[第 16 節](#16-金鑰輪替自我撤銷與遺失金鑰)）。
- 私鑰外洩時，立刻用 `revoke-key` 自我撤銷（同樣見第 16 節）。
- 把完整指紋公布在你自己的網站、README 與儲存庫頁面，讓使用者能獨立核對。
- 用 CI 簽章時，把金鑰檔內容放進 CI 的 secret（`SHINSOU_PLUGIN_KEY`），密語放 `SHINSOU_PLUGIN_PASSPHRASE`；
  不要把金鑰寫進儲存庫。

## 4. 建立專案：`init`

```bash
shinsou-plugin init my-plugin --template manga --id zh.example --name "範例漫畫" --lang zh
```

| 範本 | 內容 | 內容種類 |
|---|---|---|
| `manga` | `bridge.httpGet` ＋ `Jsoup` 解析 HTML，回傳圖片頁 | `IMAGE_SEQUENCE` |
| `novel` | 小說來源：實作 `getChapterText(chapter)` 回傳章節文字（最多 4 MiB） | `PLAIN_TEXT` |
| `mixed` | 一個套件兩個來源：漫畫來源（`IMAGE_SEQUENCE`）＋小說來源（`PLAIN_TEXT`），各自宣告 `contentKinds` | 兩者 |
| `async` | JSON API：`async` hook、非阻塞的 `bridge.net.fetch`、多檔 ES module（打包成單一腳本） | `IMAGE_SEQUENCE` |

> **小說來源請實作 `getChapterText`。** App 對宣告了 `PLAIN_TEXT` 的來源會呼叫
> `getChapterText(chapter)`，期望回傳一個字串（或 `{ text }`），最多 4 MiB；`shinsou-plugin test` 也會檢查它。

產生的檔案：

```text
shinsou.plugin.json      ← 唯一的事實來源：index、sidecar 與腳本內的宣告都由它產生
src/index.js             ← 進入點；頂層必須有 var source = {...}（多來源用 var sources = [...]）
test/cases.json          ← hook 測試案例
test/fixtures/*.json     ← 錄製的 HTTP 回應
package.json             ← build/lint/test/dev/sign 的 npm scripts
.github/workflows/shinsou-refresh.yml   ← 定期重簽撤回清單
.gitignore               ← 忽略 build/
```

`examples/sample-repository/`（在 CLI 專案內）是用 CLI 實際產生並簽章的完整範例，使用示範用 TEST 金鑰，
只供對照檔案格式。

## 5. `shinsou.plugin.json` 欄位

```jsonc
{
  "manifestVersion": 1,
  "packageId": "zh.example",            // 小寫 [a-z0-9._-]；不可含 '~'；發佈時長度有上限（見下）
  "name": "範例漫畫",
  "version": "1.0.0",
  "versionCode": 1,                      // 每次發佈都必須遞增；App 拒絕降版
  "lang": "zh",
  "nsfw": false,
  "contract": "shinsou",
  "runtime": "legacy-shinsou-adapter-v2",
  "contentType": "manga",                // manga | novel | both
  "contentKinds": ["IMAGE_SEQUENCE"],    // IMAGE_SEQUENCE | PLAIN_TEXT | EPUB_SPINE（package 的聯集）
  "capabilities": ["CATALOGUE", "LATEST", "BROWSE", "METADATA", "UNITS", "CONTENT", "SEARCH"],
  "runtimePermissions": ["EXECUTE_SCRIPT", "NETWORK"],
  "requestedHostPermissions": [],
  "systemEvents": { "required": [], "optional": [] },
  "entry": "src/index.js",
  "sources": [{
    "sourceId": "1234567890123456789",   // 十進位 64-bit 整數字串（init 自動產生）
    "legacyLongId": null,
    "name": "範例漫畫",
    "lang": "zh",
    "baseUrl": "https://example.com",
    "requestOrigins": ["https://example.com"],
    "credentialOrigins": [],
    "contentOrigins": ["https://img.example.com"],
    "browserSessionOrigins": [],
    "contentHostSuffixes": []
    // 選用："contentKinds": ["PLAIN_TEXT"]  ← 每個來源自己的內容種類（混合套件用）；省略時沿用 package 的 contentKinds
  }],
  "repository": {
    "name": "我的套件",                  // 發佈者自稱的名稱；App 顯示時標示「未驗證」
    "shortName": "我的套件",
    "website": "https://github.com/you/plugins",
    "mirrors": ["https://you.github.io/plugins"]   // 每個發佈網址；每個都會各簽一份
  },
  "test": { "cases": "test/cases.json", "fixtures": "test/fixtures" }
}
```

- **`packageId`**：套件的穩定識別。同一個 `packageId` 可以由不同發佈者各自發佈，App 把它們視為**不同套件**，
  可並存安裝（見[第 7 節](#7-權限與網路規則)與 [ADR-0001](architecture/0001-unified-publication-content-foundation.md)）。
  App 的程式碼要求已發佈的 package id 加上 `~` 與 16 位十六進位後仍不超過 128 字元，因此 `packageId` 最長 111 字元，
  且不能含 `~`。
- **`versionCode`**：整數，單調遞增。App 以 `versionCode`／digest 防止降版。
- **`sourceId`**：64-bit 整數的十進位字串，同一個 package 內固定不變；多來源套件必須用 `sourceId` 區分來源。
- **`contentType` ／ `contentKinds`**：如實宣告。包含 `PLAIN_TEXT` 的來源要實作 `getChapterText`；
  `IMAGE_SEQUENCE` 的來源實作 `getPageList`。一個 package 可以混合，每個來源各自使用其中一種。
- **`capabilities`**：只宣告你真的實作的功能（`CATALOGUE`、`LATEST`、`BROWSE`、`METADATA`、`UNITS`、`CONTENT`、
  `SEARCH`，以及需要時的 `LOGIN`、`FAVORITE`、`PREFERENCES`）。
- **`runtimePermissions` ／ `requestedHostPermissions` ／ `systemEvents`**：見[第 7 節](#7-權限與網路規則)。
  只宣告需要的，宣告了卻沒用到的權限會被 `lint` 警告。
- **origin 欄位**：全部是 exact HTTPS origin（沒有路徑），詳見第 7 節。
- **`repository`**：發佈者資訊。`name` 與 `shortName` 是自稱，App 會標示「未驗證」。

## 6. 腳本可以呼叫的 API

### 6.1 腳本結構

腳本是 classic script（不是 ES module；`build` 會用 esbuild 把 `import` 打包成單一檔案，目標 ES2020）。
進入點必須在頂層宣告 `var source = {...}`（多來源用 `var sources = [...]`）。`build` 會在檔頭寫入
`var __shinsouExtensionV2 = {...};`，檔尾寫入 `source.v2 = __shinsouExtensionV2`，不要手動修改。

`source` 的常用成員（取自 CLI 範本與 `SandboxScriptPluginRuntime`）：

| 成員 | 說明 |
|---|---|
| `baseUrl`、`supportsLatest`、`supportsLogin`、`supportsFavorites` | 來源中繼資料 |
| `headers` | 預設請求標頭；沒有 `Referer` 時 App 會補上 `baseUrl` |
| `webChallengeUrl`、`webChallengeLocalStorageKeys`、`requiredWebChallengeLocalStorageKeys` | Web challenge 設定 |
| `getPopularManga(page)`、`getLatestUpdates(page)` | 回傳 `new MangasPage(list, hasNext)` |
| `getSearchManga(page, query, filters)` | 同上 |
| `getMangaDetails(manga)` | 補完作品資料，設 `manga.initialized = true` |
| `getChapterList(manga)` | 回傳 `SChapter` 陣列 |
| `getPageList(chapter)` | `IMAGE_SEQUENCE` 來源：回傳 `new Page(index, "", imageUrl)` 陣列 |
| `getChapterText(chapter)` | `PLAIN_TEXT` 來源：回傳字串（或 `{ text }`），最多 4 MiB |
| `resolveImageUrl(pageUrl)` | 選用：延遲解析圖片網址 |
| `getFilterList()` | 選用：篩選器 |
| `getFavoriteManga(page)` | 選用：收藏（需 `FAVORITE`） |
| `login(username, password)` | 選用：由 App 啟動的登入 hook（見 6.4） |

Hook 可以回傳 Promise（`async`）。

全域物件：`SManga.create()`（含 `SManga.ONGOING` 等常數）、`SChapter.create()`、`new Page(...)`、
`new MangasPage(list, hasNext)`，以及 `Jsoup.parse(html, baseUrl)`（`select`、`selectFirst`、`attr`、`absUrl`、`text`
等，在沙箱內解析 HTML，DOM 操作不會離開沙箱）。

### 6.2 `bridge.*`（呼叫 App）

下表取自 `SandboxBridgeDispatcher`。「權限」欄是 App 實際檢查的 runtime permission；缺少時呼叫會以
`PERMISSION_DENIED` 失敗。

| 呼叫 | 說明 | 權限 |
|---|---|---|
| `bridge.httpGet(url, headers?)` | 同步 GET，回傳本文字串 | `NETWORK` |
| `bridge.httpGetWithHeaders(url, headers)` | 同上，帶自訂標頭 | `NETWORK` |
| `bridge.httpGetResponse(url, headers?)` | 回傳 `{ status, body }` | `NETWORK` |
| `bridge.httpPost(url, body, headers?)` | 同步 POST | `NETWORK` |
| `bridge.httpPostResponse(url, body, headers?)` | 回傳 `{ status, body }` | `NETWORK` |
| `bridge.httpPostBatch(urls, bodies, headers?)` | 批次 POST（128 項、32 同時、每項 128 KiB、整批 4 MiB） | `NETWORK` |
| `await bridge.net.fetch(url, { method, headers, body })` | **非阻塞**；`method` 只能 `GET` 或 `POST`，回傳 `{ status, headers, body }`；回應標頭不含 `Set-Cookie`、`Authorization` 等帶憑證的欄位；同時最多 6 個 | `NETWORK` |
| `bridge.browserSessionRequest(url, method, body, headers)` | 經 App 的瀏覽器傳輸送請求，回傳 `{ status, body }` | `BROWSER_CHALLENGE`（目標 origin 須在 `browserSessionOrigins`） |
| `bridge.getPreference(key)` / `bridge.setPreference(key, value)` | 來源偏好設定 | 無 |
| `bridge.getCredentialUsername()`、`bridge.hasCredential()` | 讀取已保存帳號（不含密碼） | `CREDENTIAL_ACCESS` |
| `bridge.getCredentialPassword()`、`bridge.setCredential(user, pass)`、`bridge.clearCredential()` | **只能在 App 啟動的 `login` hook 內**使用 | `CREDENTIAL_ACCESS` |
| `bridge.setCookie(name, value, domain, path, maxAgeSeconds)`、`bridge.deleteCookie(name, domain)`、`bridge.clearCookies()` | 寫入 App 代管的 Cookie；domain 必須是 `credentialOrigins` 的主機或其可註冊父網域 | `COOKIE_STORAGE` |
| `bridge.getCookie(name, url)`、`bridge.getCookies(url)` | 讀取 Cookie **值**；url 的主機必須在 `credentialOrigins` | `COOKIE_STORAGE` ＋ `COOKIE_READ` |
| `bridge.requestLogin(reason)` | 要求 App 顯示登入畫面，回傳布林（失敗一律 `false`） | `LOGIN_PROMPT` |
| `bridge.requestHostEvent(json)`、`bridge.getHostEventContext()`、`bridge.getHostEventCapabilities()`、`bridge.system.*` | 系統事件：`requestLogin`、`requestRefresh`、`requestLogout`、`reportMessage`（對應 wire name `auth.login.request`、`source.refresh.request`、`auth.logout.request`、`diagnostic.message.report`） | 需在 manifest 宣告對應的 `systemEvents` 與 `requestedHostPermissions` |
| `bridge.log(...)`、`bridge.parseHtml`、`bridge.domRelease*` | 在沙箱內處理，不會到達 App | 無 |

不在這張表上的 `bridge.*` 會以 `UNKNOWN_OPERATION` 失敗，`lint` 也會把它當成錯誤。

說明：

- 就算沒有 `COOKIE_READ`，App 仍會在請求中自動帶上它代管的 Cookie；`COOKIE_READ` 只是讓腳本**看得到值**，
  因此是敏感權限。
- `REPORT_USER_MESSAGE`（讓套件直接對使用者顯示訊息）永遠不會授予第三方套件；宣告它的套件會在安裝前被拒絕。

### 6.3 資源限制

以下是 App 預設值，套件不能提高（出自 `PluginExecutionLimits` 與沙箱設定）。超限一律失敗，不回傳部分結果。

| 項目 | 上限 |
|---|---|
| 初始化時間／單次 hook 時間 | 10 秒／30 秒 |
| 每次 hook 的 bridge 呼叫數 | 4,096 |
| 單一結果 | 4 MiB；單一字串 1 MiB（宣告 `PLAIN_TEXT` 的來源，單一字串可到 4 MiB） |
| 結果結構 | 每個陣列／物件 4,096 項、總計 16,384 個值、深度 32 |
| 每次 hook 的輸入 | 1 MiB |
| 單次 bridge 參數／結果 | 各 4 MiB |
| 沙箱 JS heap／stack | 64 MiB／1 MiB |
| DOM | 65,536 個節點、深度 256 |
| Log | 256 筆、256 KiB |
| 腳本大小 | `lint` 以 2 MiB 為上限（App 端對第三方腳本的確切上限【未驗證】；儲存庫 client 的硬上限是 8 MiB） |

ADR-0002 §7 另外規劃了請求速率（每 package 10 req/s、burst 20）、每 invocation 回應總量 32 MiB、
crash-loop 自動停用等限制；這些是否已全部在程式中落實【未驗證】。

### 6.4 登入

帳密只會在 App 啟動的 `login(username, password)` hook 內出現：App 為此建立**一次性**的沙箱，網路只允許
`credentialOrigins`，hook 結束就銷毀。一般執行中的沙箱永遠拿不到密碼。使用者在權限頁看到的警語是
「你的帳號名稱與密碼會送往：<`credentialOrigins`>」，所以 `credentialOrigins` 只放真正需要登入的主機。

## 7. 權限與網路規則

### 7.1 權限分級

| 層級 | 項目 | 取得方式 |
|---|---|---|
| 隨安裝授予 | `EXECUTE_SCRIPT`、`NETWORK` | 安裝即有；`NETWORK` 僅限你宣告的 exact origin |
| 敏感（逐項開關，**預設關閉**，之後可在該套件的「權限」撤銷） | `COOKIE_STORAGE`、`COOKIE_READ`、`CREDENTIAL_ACCESS`、`LOGIN_PROMPT`、`FAVORITE_MUTATION`、`BROWSER_CHALLENGE` | 使用者在權限頁自行開啟 |
| 敏感（逐項開關） | 每一個 `contentHostSuffixes` 後綴家族 | 同上 |
| 永不授予 | `REPORT_USER_MESSAGE` | — |

重點：

- 使用者可以**不給**敏感權限；你的腳本要能在被拒絕時降級（例如沒有 `COOKIE_READ` 就不要依賴 `getCookie`）。
- 權限只適用於**確切版本**；更新時是否沿用見[第 13 節](#13-發佈更新與-d4-自動沿用授權)。
- 有效權限 = 你的宣告 ∩ App 支援 ∩ 沙箱上限 ∩ 使用者授權。你的宣告在 index、sidecar 與腳本內三處必須一致
  （`build` 會替你檢查）。

### 7.2 網路：只有 exact HTTPS origin

| 欄位 | 用途 |
|---|---|
| `requestOrigins` | 腳本的 API 請求（`httpGet`、`net.fetch` 等）可連的 exact HTTPS origin |
| `credentialOrigins` | App 代管 Cookie、帳密只送往這裡；必須也是 `requestOrigins` 的成員 |
| `contentOrigins` | 讀圖／下載等「內容平面」的 exact origin；只允許 `GET`／`HEAD`，**不帶 Cookie、Authorization 或任何來源憑證** |
| `browserSessionOrigins` | 可用瀏覽器傳輸的 API origin（最多 4 個），需要 `BROWSER_CHALLENGE` |
| `contentHostSuffixes` | 動態 CDN 後綴，見下方 |

- 一律是 exact HTTPS origin：不接受 HTTP、路徑、IP、`localhost`、私有網路主機。App 在連線時還會做 DNS 解析結果的
  釘選（只連公開位址），並逐跳檢查 redirect、拒絕降級到 HTTP。
- 內容平面與 API 平面分離：圖片與正文由 App 代為下載再交給閱讀器，套件拿不到內容平面的憑證。

### 7.3 `contentHostSuffixes`

給輪替主機的 CDN（例如圖片節點的子網域會變動）使用，**只放寬無憑證的內容平面**，不能用於 API 請求或憑證。

- 一個來源最多 8 項。
- 必須是小寫 ASCII 主機名稱，且至少到**可註冊網域**（依 App 內建的 Public Suffix List 判斷）：
  `cdn.example.com`、`example.com` 可以；`com`、`co.jp`、`github.io` 這類公開後綴不行，IP 位址也不行。
- 預設只允許 443 連接埠。在後綴後面加 `:1024-65535`（例如 `hath.network:1024-65535`）則同時允許非特權連接埠；
  特權連接埠（除 443 以外）永遠不允許。
- 符合條件的主機是該網域本身或其任何子網域。
- 使用者看到的是「從 {後綴} 底下的所有網站下載」，並自行決定是否開啟，所以請只宣告真正需要的。

### 7.4 Web challenge（例如 Cloudflare）

第三方來源也可以使用 Web challenge：宣告 `browserSessionOrigins`、`runtimePermissions` 含 `BROWSER_CHALLENGE`，
使用者在權限頁開啟後，請求 origin 命中 `browserSessionOrigins` 時才可使用。平台行為不同（Windows 只能開外部瀏覽器、
無法自動帶回 Cookie），細節見[插件系統總覽](PLUGIN_SYSTEM.md)。

### 7.5 同一 `packageId`、不同發佈者：並存安裝

App 以 `packageId~<發佈者摘要前 16 位十六進位>` 作為本機的安裝識別（原社群儲存庫網址例外，保留原本的 `packageId`）；
開發者模式的套件是 `packageId~dev`。所以別人也發佈 `zh.example` 並不會覆蓋你的，反之亦然；
授權、信任與書庫綁定都分開計算。細節見 [ADR-0001 的「套件身分」一節](architecture/0001-unified-publication-content-foundation.md)。

## 8. 命名規則

- App 在**安裝時**與**信任發佈者時**，會拒絕名稱含「官方」「公式」`official`（含 `offical`）的套件、來源或儲存庫。
  比對不分大小寫、全形／半形，並忽略分隔符號，所以 `Shinsou_Official`、`ＯＦＦＩＣＩＡＬ`、`官 方` 都會被擋。
- 明確的免責聲明可以：`Unofficial`、`非官方`、`非公式`。
- `lint` 比 App 更嚴：還會把「官網」、重音符號、零寬字元、`0fficial` 這類數字替代視為錯誤，且檢查
  `packageId`、套件名稱、每個來源的名稱與 `sourceId`、`repository.name`、`repository.shortName`。
  請以 `lint` 通過為準。
- 理由：Shinsou 沒有官方套件，任何暗示官方背書的名稱都會誤導使用者。

## 9. `build`、`lint` 與 `test`

```bash
shinsou-plugin build     # 輸出 build/repo/{index.json, repo.json, plugins/<id>.js, sidecars/<id>.json}（未簽章）
shinsou-plugin lint
shinsou-plugin test
```

### build

esbuild 打包成單一 classic script，計算 SHA-256 與 byte size，產生 index 條目與 sidecar
（`shinsou-extension-sidecar-v2`），並把寫出的檔案讀回來檢查腳本宣告／index／sidecar 三方一致（版本、內容種類、
capability、事件、權限、來源與 origin、digest）。任何不一致都讓 build 失敗。

### lint

錯誤（exit code 1）：

- 誤導性名稱（見[第 8 節](#8-命名規則)）。
- 腳本用到的 `bridge.*` 沒有對應的宣告權限；用了不存在的 op。
- origin 不是 exact HTTPS、`credentialOrigins` 不在 `requestOrigins` 內、宣告了 `NETWORK` 卻沒有任何 `requestOrigins`。
- 腳本超過 2 MiB、`sourceId` 不是 64-bit 整數字串、申請了 `REPORT_USER_MESSAGE`。
- `contentHostSuffixes` 太寬（只用 CLI 內建的常見後綴清單判斷，不是完整 Public Suffix List）。

警告：宣告了卻沒用到的權限、宣告了 capability 卻找不到對應 hook、腳本裡出現沒宣告的網址、
`COOKIE_READ` 與 `contentHostSuffixes` 的「App 尚未支援」提示（這兩則與 App 目前行為不符，見[已知落差](#已知落差與未驗證項目)）。

### test

hook 在 QuickJS WASM 沙箱（quickjs-emscripten）中重播錄製的 HTTP 回應，載入與 App 相同的 bridge bootstrap。
測試用的 bridge host 會照 App 的規則拒絕未宣告的權限、`requestOrigins` 以外的網址與非 HTTPS。

```bash
shinsou-plugin test               # 以 test/fixtures 重播
shinsou-plugin test --record      # 缺少的回應實際連線並存成 fixture（不保存 Set-Cookie／Authorization／Cookie）
shinsou-plugin test --update      # 更新 snapshot
shinsou-plugin test --only pages
```

`test/cases.json` 的案例格式：

```json
[
  { "name": "popular", "hook": "getPopularManga", "args": [1],
    "expect": { "path": "mangas", "minItems": 2, "snapshot": true } },
  { "name": "pages", "hook": "getPageList", "args": [{ "url": "/manga/1/chapter/1" }],
    "expect": { "minItems": 2, "matches": { "0.imageUrl": "https://example.com/img/1.jpg" } } }
]
```

`expect` 可用 `path`、`minItems`、`notEmpty`、`matches`、`snapshot`；也可設定 `sourceId`、`preferences`、
`credential`（只提供帳號）。fixture 格式為
`[{ "request": { "method": "GET", "url": "…" }, "response": { "status": 200, "headers": {…}, "body": "…" } }]`。

> `test` 使用 CLI 內建的 QuickJS 引擎，與 App 沙箱內的引擎同系列但**不是同一份程式**；CLI README 的 TODO 寫明之後會改用
> App 的 `sandbox-core` WASM guest。通過 `test` 不保證在 App 上一定行為相同，請另外在 App 上用開發者模式試過。

## 10. 在 App 上試用：開發者模式與 `serve`

開發者模式讓你不必簽章、不必上傳，就能在真實的 App 與沙箱上試用還在開發的套件。

### 10.1 App 端

1. 在 App 開啟 **套件安全（Package security）** 對話框，找到 **開發者模式（Developer mode）** 並開啟。
2. 預設允許的開發伺服器位址是這台裝置本身：`127.0.0.1`、`localhost`、`::1`。
3. 若開發伺服器在另一台電腦（手機測試時就是這種情況），在 **開發伺服器位址** 輸入該電腦的區網 **IPv4 位址**，
   每行一個（例如 `192.168.1.20`），再按「儲存位址」。只接受私有網段 `10/8`、`172.16/12`、`192.168/16` 的 IPv4 數字位址，
   **不接受主機名稱**（連 `.local` 都不行），最多 8 個。
4. 到「新增儲存庫」輸入 `http://<位址>:<埠>`。開發儲存庫可以用 HTTP，但**套件自己發出的請求仍然只能是 HTTPS**。

開發者模式的行為：

- 套件以 `packageId~dev` 安裝，標示 **「開發中」**，絕不會取代你已簽章安裝的同 id 套件。
- **只在開發者模式開啟時執行**；關閉後這些套件停止執行。
- 重新 build 之後**授權維持**；每當 artifact 的 digest 改變，App 就顯示一次更新。
- 仍在沙箱中執行，沒有任何放寬；敏感權限一樣要使用者逐項開啟。

### 10.2 作者端：`serve`

```bash
shinsou-plugin serve                          # 只在本機 127.0.0.1:8787
shinsou-plugin serve --host lan               # 自動選本機的區網 IPv4
shinsou-plugin serve --host 192.168.1.20 --port 9000
```

- 提供 `build/repo/` 的**未簽章**開發儲存庫，檔案一改就自動重建；重建失敗時繼續提供上一個成功的版本。
- 只接受 `127.0.0.1`、`::1` 或這台電腦的私有區網位址；拒絕 `0.0.0.0` 與公開 IP。
- `GET /__shinsou-dev/status` 回傳目前的 build revision 與 SHA-256。

### 10.3 桌面（macOS／Windows）流程

1. 執行 `shinsou-plugin serve`。
2. App：套件安全 → 開發者模式（開）；新增儲存庫 `http://127.0.0.1:8787`。
3. 安裝套件、在「權限」開啟需要的敏感權限，邊改程式邊重新整理試用。

### 10.4 手機流程

1. 電腦與手機連同一個區網；電腦執行 `shinsou-plugin serve --host lan`，記下印出的位址（例如 `192.168.1.20:8787`）。
2. 手機 App：套件安全 → 開發者模式（開）→ 開發伺服器位址填入 `192.168.1.20` → 儲存位址。
3. 新增儲存庫 `http://192.168.1.20:8787`。
4. 電腦防火牆需允許該埠的區網連入。

> **捷徑：** `serve` 也會印出 `shinsou://add-dev-repository?url=…` 連結與 QR code。開發者模式開啟、且該位址是本機或你已加入的區網位址時，用手機開啟連結（或掃 QR code）會直接跳出「新增儲存庫」確認；否則 App 會提示你先開啟開發者模式並加入位址。

## 11. 簽章與發佈

```bash
shinsou-plugin sign --key mykey
shinsou-plugin sign --key mykey --mirror https://you.github.io/plugins --mirror https://cdn.example.com/plugins
```

- 為 `repo.json`、`index.json`、每個 sidecar 與 `publisher-revocations.json` 產生 `shinsou-signed-envelope-v1`。
  `repo.json` 內的 `signingKeyFingerprint` 自動填成簽章金鑰的完整指紋。
- **簽章綁定儲存庫網址**，所以每個鏡像都各簽一份，輸出到 `public/<鏡像網址轉成目錄名>/`
  （例如 `public/you.github.io_plugins/`）。`plugins/<id>.js` 原樣複製，App 以 index 中的 SHA-256 驗證。
- 每份文件的 `sequence` 記在 `.shinsou/state.json`，**嚴格遞增**：內容沒變就沿用原值，內容一變就 +1。
  App 拒絕較小的 sequence（重播），也拒絕同 sequence 但內容不同的文件。
  **`.shinsou/state.json` 必須提交到版本控制**（它不含秘密），否則換電腦或 CI 會從 1 重新開始而被 App 拒絕。

### 發佈到靜態主機

把 `public/<鏡像>/` 的內容**原樣**放到對應網址即可：

- **GitHub Pages**：把 `public/you.github.io_plugins/` 的內容放到 Pages 的發佈來源，使用者加入的網址是
  `https://you.github.io/plugins`。
- **raw GitHub**：把內容提交到儲存庫，使用者加入的基底網址形如
  `https://raw.githubusercontent.com/you/plugins/refs/heads/main/<子目錄>`。
- 其他靜態主機或 CDN 也可以，只要 `repo.json`、`index.json` 等就在基底網址旁邊、而且回應真的是 JSON
  （不是登入頁或 HTML 錯誤頁）。

網址必須與 `sign` 時指定的鏡像**完全一致**，否則簽章驗證會失敗。第一次簽章時，這把金鑰成為你的發佈者 ID。

### 儲存庫檔案

```text
repo.json                       ← payloadType repository-meta
index.json                      ← repository-index
sidecars/<packageId>.json       ← plugin-sidecar
plugins/<packageId>.js          ← 腳本
publisher-revocations.json      ← publisher-revocations（必須存在且未過期，才能安裝或更新）
publisher-key-rotation.json     ← 輪替後才有，請長期保留
publisher-key-revoked.json      ← 自我撤銷後才有
```

## 12. 使用者如何加入儲存庫與核對指紋

給使用者的步驟，請寫在你的網站或 README：

1. 在 App 的「瀏覽」→ 儲存庫 →「新增儲存庫」，貼上基底網址（例如 `https://you.github.io/plugins`），
   **或**直接點你提供的連結：

   ```text
   shinsou://add-repository?url=https://you.github.io/plugins&fp=sha256:<完整指紋>
   ```

   連結只接受 HTTPS 網址與完整指紋；連結本身**不會**自動加入，仍會跳出信任對話框。
2. 對話框「信任此發佈者？」顯示發佈者名稱（自稱、未驗證）與金鑰指紋。使用者要把它與**你在自己網站公布的完整指紋**比對。
   - 預設模式是**首次使用即信任**（TOFU），也可以選填完整指紋；填了就一定要相符。
   - 開啟「套件安全」中的「**一律要求比對指紋**」後，沒有提供完整指紋或指紋不符就不能信任。切換為嚴格模式時，
     只用 TOFU 信任過的發佈者會變成「待比對指紋」：已安裝的繼續執行，但在比對完成前不安裝新套件、不更新。
   - 帶 `fp` 的連結會把指紋預先填入並**強制比對**。
3. 信任狀態會顯示為「指紋已比對」或「首次使用時信任（未驗證）」。
4. 信任的單位是**發佈者金鑰**：同一把金鑰從不同的鏡像網址出現，不需要重新信任；同一個網址出示另一把金鑰，
   一律當成金鑰替換而被拒絕。
5. 之後每個套件仍各自走權限確認：預設只有「執行套件並連線到上列網站」，敏感權限逐項開關、預設關閉。

> 請在你的網站上公布**完整指紋**（`sha256:…`），而不是只公布分組的顯示指紋，因為比對只認完整值。

名稱含「官方」「official」「公式」的發佈者，App 會拒絕信任（見[第 8 節](#8-命名規則)）。

## 13. 發佈更新與 D4 自動沿用授權

發佈更新：修改程式 → 遞增 `versionCode`（與 `version`）→ `build` → `lint` → `test` → `sign` → 上傳。

使用者端的行為（ADR-0002 §6.3，決策 D4）：

- **更新沒有要求任何新東西** → App 自動沿用使用者之前的決定，並通知使用者「已更新，並沿用你先前授予的權限」。
  條件全部成立才算：
  - 新舊版本來自**同一個發佈者金鑰**；
  - 新版本要求的 host 權限、runtime 權限、`contentHostSuffixes` 都是舊版本要求過的子集；
  - 新版本的每個來源，舊版都有（沒有新來源），而且 `requestOrigins`、`credentialOrigins`、`contentOrigins`、
    `browserSessionOrigins` 都沒有新增（逐項 exact 比對）。
  - 沿用的是「舊授權縮到新版要求的範圍」：使用者拒絕過的仍然拒絕，不會多出任何東西。
- **只要有任何新增**（新權限、新後綴、新來源、新 origin）→ 使用者必須在**安裝前**審核這次更新，
  **舊版本在使用者接受之前繼續執行**。
- 換了發佈者金鑰、或套件沒有發佈者，永遠不會自動沿用。

實務建議：小修補不要順手加權限或 origin；需要新的 origin 時，在更新說明中講清楚理由。

## 14. 撤回某個版本

發現某個已發佈的版本有問題（含安全問題）時，用該版腳本的 SHA-256 撤回：

```bash
shinsou-plugin revoke <sha256> [<sha256>...] --key mykey
```

撤回清單會重新簽章；使用者的 App 取得後，會把對應的已安裝 artifact **卸載 runtime 並停用**，並提示更新。
接著發佈修正版（遞增 `versionCode`）。

沒有任何中央清單可以替你撤回；App 也不會因任何第三方的判斷遠端停用你的套件。唯一的遠端停用來源就是你簽署的
`revocations` 與 `key-revoked`。使用者仍可在本機自行封鎖套件、單一版本或整個發佈者。

## 15. 續簽撤回清單（必做）

撤回清單預設有效 **30 天**（`--days` 可改；`sign` 在剩不到一半時自動延長）。App 的規則：

- 新安裝與更新**一律要求**該發佈者有一份已驗證、未過期的撤回清單（可以是空清單）。
- 過期後，App 不再從這個發佈者安裝新套件或更新；**已安裝的套件繼續執行**，但會持續顯示警示（無法確認是否已被撤回）。

所以你必須定期續簽，即使沒有新版本：

```bash
shinsou-plugin refresh --key mykey
```

`init` 已附上 `.github/workflows/shinsou-refresh.yml`（每週一次），也可用 `shinsou-plugin ci-template` 寫入。
在 GitHub 設定 secrets：

- `SHINSOU_PLUGIN_KEY`：金鑰檔完整內容
- `SHINSOU_PLUGIN_PASSPHRASE`：密語（金鑰有加密時）

workflow 會提交更新後的 `public/` 與 `.shinsou/state.json`。

## 16. 金鑰輪替、自我撤銷與遺失金鑰

### 輪替（舊金鑰仍在手上）

```bash
shinsou-plugin keygen --name newkey
shinsou-plugin rotate --key mykey --to newkey
```

- 舊金鑰簽署 `publisher-key-rotation.json`，新金鑰另外簽署輪替聲明（雙簽），接著用新金鑰重簽所有文件。
- 使用者的 App 驗證成功後自動換成新金鑰的 pin，不需要重新信任；舊金鑰被標為已退休（只能驗證、不能再簽）。
- 發佈者 ID 不變（仍是第一把金鑰的指紋）。
- **請長期保留 `publisher-key-rotation.json`**：還沒更新過的使用者靠它換 pin。新金鑰的密語可用
  `SHINSOU_PLUGIN_NEW_PASSPHRASE`。

### 自我撤銷（私鑰外洩）

```bash
shinsou-plugin revoke-key --key mykey
```

由被撤銷的金鑰自己簽署 `publisher-key-revoked.json`。撤銷**目前**金鑰後，這個發佈者的所有套件在使用者端都會停用，
使用者必須重新信任新的金鑰；`sign` 也會拒絕繼續使用被撤銷的金鑰。撤銷已退休的舊金鑰只會讓它不再能驗證任何東西。

### 遺失私鑰

無法自動恢復，也沒有任何人能代為重設：

1. 用 `keygen` 產生新金鑰，建立新的發佈。
2. 在你的網站公布新的完整指紋，通知使用者。
3. 使用者必須明確同意改信任新金鑰；這個新金鑰會被視為**新的發佈者**，舊發佈者的已安裝套件不再執行，
   所有授權都要重新審核。

請從一開始就備份金鑰，避免走到這一步。

## 17. 從未簽章改為簽章發佈

如果你的儲存庫原本沒有簽章，之後改為簽章發佈：使用者原本加入的儲存庫在第一次看到簽章時，App 會顯示
「已改為由發佈者簽章：請確認發佈者以繼續更新」，使用者確認發佈者後，更新才會繼續。確認之後，未簽章時期的授權在
**第一個簽章版本沒有要求任何新東西**時會延續，不必重新授權。

## 已知落差與未驗證項目

以下是撰寫本指南時對照 App 與 CLI 程式碼所發現的差異，不是你的設定錯誤：

| 項目 | 狀況 |
|---|---|
| 名稱規則 | App 與 CLI 都拒絕「官方／official」並允許「非官方／Unofficial」；CLI 另外檢查更多變體（如「官網」、重音字母），所以 CLI 通過的名稱 App 一定接受（見第 8 節）。 |
| 資源限制 | ADR-0002 §7 的部分限制（請求速率、每次回應總量、crash-loop）是否已全部落實【未驗證】；腳本大小上限在 App 端的確切數值【未驗證】。 |
| 混合套件 | CLI 的 manifest 接受每個來源自己的 `contentKinds`（省略時沿用 package 的值），但 CLI 沒有混合範本，也未在 App 上實測過混合套件【未驗證】。 |
| 平台差異 | 沙箱的實作方式（Android isolated process、macOS／Windows helper、iOS WASM）見 ADR-0002；本指南不展開。 |
