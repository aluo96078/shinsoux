# S0：沙箱內 C HTML 解析器評估（lexbor）

對應 ADR-0002 §5.2、§7、§12 S0 與 §14 開放問題 1。這個目錄是獨立的評估與原型，**沒有修改** `core/`、根目錄 `CMakeLists.txt`、`scripts/` 與任何 App 程式碼；lexbor 與 QuickJS 原始碼（`third_party/`）只讀取。

## 1. 結論

**建議採用 lexbor 作為 sandbox tier 的 DOM 解析器，並在 S1 直接上線，不再以 iOS 的純 JS bootstrap 當過渡實作。**

| 項目 | 結果 |
|---|---|
| 授權 | Apache-2.0，單一著作權人，無其他授權的內嵌程式碼；與本專案 MIT 相容，只需隨發佈附上 LICENSE／NOTICE（§2） |
| 大小 | 原生 arm64 約 0.8 MB（dead-strip 後、只匯出 `sbx_dom_*`）；wasm32 約 0.5 MB（`-Oz`，含部分 wasi-libc）（§3） |
| wasm32-wasi | 同一份原始碼編譯、連結都成功（未執行）；只多出 4 個 WASI import（`clock_time_get`、`fd_close`、`fd_seek`、`fd_write`），沙箱端提供空實作即可 |
| 官方套件語意 | 官方語料 151 個不重複 selector × 13 份頁面 = 1,963 次執行，結果數量與 jsoup 1.18.3 **全部一致（0 差異）**；現行 iOS JS bootstrap 有 4 次不一致（§5） |
| 其他語意 | `text`／`ownText`／`attr`／`absUrl`／`tagName`／`className`／`id`／父子兄弟關係在 13 份頁面、共 4,233 次元素比對中與 jsoup 逐項比對，僅剩 `<template>` 內容一類的差異；`html()`／`outerHtml()` 的空白與屬性拼寫不同，但官方套件只對 `<script>` 呼叫 `html()`，該情況 37/37 完全一致 |
| 效能 | 431 KB 真實頁面（dm5）解析 1.2 ms，JS bootstrap 100 ms（約 85 倍）；1 MB 合成頁面 5.4 ms 對 166 ms（約 30 倍）（§6）。以上都是**原生 QuickJS** 的比較，iOS 的 WASM 解譯會讓差距再拉大，但本次沒有量測 |
| 安全 | ASan＋UBSan 下 libFuzzer 600 秒、獨立變異 fuzzer 600 秒都沒有崩潰、記憶體錯誤或逾時；節點數、深度、輸入、selector 長度、handle 數、記憶體預算都在模組內強制（§7） |
| 主要風險 | (1) lexbor 有 3 個需要繞過或接受的行為（`:is()`／`:has()` 為 descendant 左側、quirks 模式的 class／id 大小寫、`:nth-child` 對極寬的兄弟列表是 O(n²)）；(2) lexbor 的呼叫不能被 QuickJS interrupt handler 中斷，只能靠輸入上限與 Host watchdog；(3) 單一維護者；(4) `html()`／`outerHtml()` 輸出不是 jsoup 的 pretty-print（§5.3） |

需要 ADR 決策的事項整理在 §9（建議修訂 §5.2 與開放問題 1）。

## 2. 授權

- lexbor v3.0.1（`third_party/lexbor`，`version` 檔為 `LEXBOR_VERSION=3.0.1`）：**Apache License 2.0**（`LICENSE`、`NOTICE`）。
- 實際編譯進來的 8 個模組（`core css dom encoding html ns selectors tag`）所有原始檔的版權標頭都只有 `Copyright (C) 2018–2026 Alexander Borisov`；沒有找到 BSD／MIT／公有領域等其他授權的內嵌程式碼。
- 與 App（MIT）相容。Apache-2.0 的義務：隨產品散佈 LICENSE 與 NOTICE（放進「開放原始碼授權」頁面即可）、保留版權聲明、修改過的檔案要標示。lexbor 本身**未修改**（allocator 與 token callback 都用公開 API 掛接）。專利授權條款對發佈者有利。
- 不構成 GitHub Release 側載發佈（D12）或 Developer ID 公證的障礙。
- 維護狀態：`CHANGELOG.md` 顯示 3.0.0（2026-03-31）、2.7.0（2026-03-12）、2.6.0（2025-11-10）持續發佈，並有安全回報流程（`SECURITY.md`，48 小時內回覆）。仍是單一主要維護者，需把「上游停止維護時由我們持有 fork」寫進風險。

## 3. 建置與大小

只編譯 lexbor 的 `core css dom encoding html ns selectors tag` 與 `ports/posix` 的 `memory.c`；不需要 `style`、`url`、`unicode`、`utils`、`engine`。`html/CMakeLists.txt` 直接用 glob 編譯（不依賴 lexbor 自己的 CMake，所以不需要改根目錄 CMake）。

機器：Apple M4 Pro（Mac16,8，12 核心、24 GiB），macOS 27.0.1，Apple clang；wasm 用 Homebrew LLVM 23.1.4 與 `wasi-libc`。

| 項目 | 大小 |
|---|---:|
| `liblexbor_min.a`（原生 arm64、`-O2`，8 個模組，未 strip 的靜態庫） | 2.9 MB |
| `libsandbox_dom.a`（本原型） | 50 KB |
| 原生動態庫，只匯出 `sbx_dom_*`、`-dead_strip`＋`strip -x` | **812 KB**（`__TEXT` 224 KB＋資料約 560 KB） |
| wasm32 靜態庫（`-Oz`） | 2.2 MB |
| wasm32 連結後 `sandbox_dom.wasm`（`-Oz`、`--gc-sections`、`--strip-all`，只匯出 27 個 `sbx_dom_*` 函式與 memory） | **499 KB**（code 192 KB、data 302 KB） |
| 對照：`build/native/libquickjs.a` | 1.3 MB |

各模組的 `-O2` 物件大小（text／data，單位 KB）：`encoding` 334／777、`html` 152／531、`css` 117／62、`core` 35／4、`dom` 28／2、`selectors` 8／0、`tag` 2／34。大小幾乎都在 `encoding`（多位元組編碼表）與 `html`（實體表、tokenizer 狀態表）；若 Host 一律傳入 UTF-8（JS 字串轉出來就是 UTF-8）理論上可以裁掉大部分編碼表，但這需要改 lexbor 的建置設定，本次沒有嘗試。

- 編譯時間：`liblexbor_min.a` 約 1.6 秒（8 執行緒）。
- wasm32：`scripts/build-wasm.sh` 以 `--target=wasm32-wasip1 --sysroot=…/wasi-sysroot` 編譯每個檔案，**全部編過、連結成功，沒有執行**。連結後的 import 只有 `wasi_snapshot_preview1.{clock_time_get, fd_close, fd_seek, fd_write}`（來自 libc 的 stdio／lexbor 的 perf 與 print 輔助）；沙箱的 WASM host 不提供 WASI 時，補空實作或在連結時 `--wrap` 掉即可。
- lexbor 的 allocator 只走 `lexbor_malloc` 家族，`lexbor_memory_setup()` 是公開 API；本原型用它替每個 context 設位元組預算（見 §4），不需要 patch lexbor。

## 4. 原型：`src/sandbox_dom.{h,c}`

以整數 handle 表示節點（`gen<<16 | slot+1`，0 代表 null；釋放後 generation 遞增，舊 handle 一律回 `SBX_ERR_BAD_HANDLE`，不會誤指到新節點）。字串結果放在 context 的 scratch buffer，下一次呼叫前有效。

提供的操作與 Rhino bridge 的 `dom*` 一一對應：

| Rhino bridge | `sbx_dom_*` | 備註 |
|---|---|---|
| `htmlParse`／`htmlParseFragment` | `parse(html, len, base_uri)` | `<base href>` 會覆寫 base（jsoup 行為） |
| `domSelect`／`domFirst` | `select`／`select_first` | 包含 root 自己（jsoup 行為）；`selectFirst` 在第一個結果就停止 |
| `domText`／`domOwnText` | `text`／`own_text` | 依 jsoup 1.18.3 的演算法逐字重寫（§5.2） |
| `domHtml`／`domOuterHtml` | `html`／`outer_html` | lexbor 序列化＋`html()` 去頭尾空白 |
| `domAttr`／`domHasAttr`／`domAbsUrl` | `attr`／`has_attr`／`abs_url` | `attr("abs:href")` 也支援；URL 解析模擬 `java.net.URL`＋jsoup 的 `StringUtil.resolve` |
| `domTagName`／`domClassName`／`domId` | `tag_name`／`class_name`／`id` | `className()` 會 trim；Document 的 tagName 是 `#root` |
| `domChildren`／`domParent`／`domNextSibling`／`domPrevSibling` | `children`／`parent`／`next_sibling`／`prev_sibling` | |
| `domRemove`／`domRelease`／`domReleaseAll` | `remove`／`release`／`release_all` | 文件在最後一個 handle 釋放時銷毀 |

### 4.1 限制（對應 ADR §7 與 `PluginExecutionLimits`）

| 限制 | 欄位 | 預設 | 強制方式 |
|---|---|---|---|
| 輸入大小 | `max_input_bytes`（`maxDomInputBytes`） | 4 MiB | 解析前檢查 |
| 節點數／文件 | `max_nodes`（`maxDomNodes`） | 65,536 | tokenizer 的 token callback 計數（近似上界：一個 token 通常產生一個節點）→ 超過就**中止解析**；解析完再做一次精確的樹走訪；格式化元素重建造成的額外節點由記憶體預算擋下 |
| 深度 | `max_depth`（`maxDomDepth`） | 256 | token callback 讀取 tree builder 的 open-element 堆疊長度 → 中止解析；解析完再檢查一次 |
| 存活 handle | `max_handles`（`maxDomHandles`） | 4,096（上限 65,535） | `select` 在配置前先檢查，不夠就整批回滾，不留半套 handle |
| selector 長度 | `max_selector_chars`（`maxSelectorChars`） | 4,096 | 改寫前檢查；另限制 `:not/:has/:is/:where` 巢狀 8 層、`:contains` 等標記 32 個 |
| DOM 記憶體 | `max_memory_bytes` | 64 MiB | 用 `lexbor_memory_setup()` 掛接 allocator，所有 lexbor 配置（含 selector 解析）都計入；超過回傳 NULL，解析器以錯誤狀態結束，不崩潰 |

錯誤碼是穩定的整數（`SBX_ERR_*`）；`SBX_IS_LIMIT_ERROR()` 為真時應轉成 `PluginResourceLimitException` 等級的失敗（整個 invocation 失敗，不回傳部分結果）。單元測試 `test/dom_test.c` 逐項驗證：節點上限（解析到一半被中止、不殘留文件）、深度、輸入、handle（含回滾）、selector 長度、記憶體預算（峰值不超過預算）、stale handle、被頁面偽造的標記屬性無效。

目前沒有「同時存活文件數」的限制；每個文件有約 1.2 MiB 的固定開銷（lexbor 的 hash 表與記憶體池），建議 S1 補一個上限（例如 16）。

### 4.2 Selector 改寫層

lexbor 的 selector 引擎符合 CSS 規格，jsoup 則有自己的方言。`sandbox_dom` 先把 jsoup selector 改寫成 lexbor selector，必要時用**暫時的標記屬性**（`sbx-m<N>`、`sbx-root`）表達 lexbor 做不到的述詞，查詢結束就移除；頁面自己帶的同名屬性會在查詢前被清掉，無法偽造。

| 語法 | 官方語料使用（151 個不重複 selector） | lexbor 原生 | 本原型的處理 | 結果 |
|---|---:|---|---|---|
| 型別、`*` | 118 | 是 | 直接傳遞 | ✔ |
| `.class` | 101 | 是，但「無 doctype」（quirks）時大小寫不敏感、standards 模式則敏感；jsoup 一律不敏感 | 改寫為 `[class~="x" i]` | ✔ |
| `#id` | 19 | 是，但 quirks 模式不分大小寫；jsoup 區分 | 改寫為 `[id="x"]` | ✔ |
| `[attr]` | 18 | 是 | 直接傳遞 | ✔ |
| `[a=v]`／`*=`／`^=`／`$=`／`\|=` | 7／19／3／0／0 | 是，但 jsoup 比較屬性值**不分大小寫**，且接受含 `/`、`.`、`%` 的未加引號值 | 一律加引號並加上 ` i` 旗標 | ✔ |
| `[a!=v]` | 0 | 否 | 改寫為 `:not([a="v" i])` | ✔ |
| `[a~=regex]`、`[^prefix]` | 0 | 否（語意不同） | 回傳 `SBX_ERR_UNSUPPORTED` | ✘ 不支援 |
| 後代、`>`、`+`、`~` | 73、7、3、0 | 是 | 直接傳遞 | ✔ |
| 群組 `,` | 41 | 是；jsoup 容許空群組（`,li`） | 略過空群組，結尾逗號仍是錯誤 | ✔ |
| 開頭組合子（`> div …`） | 1 | 否（解析錯誤） | 改寫為 `[sbx-root] > div …`，並在 select 的 root 上暫時加標記 | ✔ |
| `:not()`（含複雜 selector） | 4 | 是 | 內層遞迴改寫 | ✔ |
| `:has()` | 1 | 是 | 內層遞迴改寫 | ✔ |
| `:is()`／`:where()` | 0 | 是 | 內層遞迴改寫 | ✔ |
| `:contains(text)` | 3 | 否（只有只比對「直屬文字節點」的 `:lexbor-contains`） | 標記屬性：依 jsoup `text()` 演算法算出每個元素的文字，不分大小寫比對 | ✔ |
| `:containsOwn(text)` | 0 | 否 | 同上，用 `ownText()` | ✔ |
| `:eq(n)`／`:lt(n)`／`:gt(n)` | 0 | 否 | 標記屬性（O(n) 計算元素索引） | ✔ |
| `:empty` | 0 | 是，但含空白文字的元素被視為非空；jsoup 忽略空白文字與註解 | 標記屬性（jsoup 判定） | ✔ |
| `:first-child`、`:last-child`、`:only-child`、`:nth-child(an+b)`、`:nth-last-child`、`:nth-of-type`、`:first-of-type`… | 3、1、0、3、… | 是 | 直接傳遞 | ✔（注意 §7.2 的效能） |
| `:root` | 0 | 是 | 直接傳遞 | ✔ |
| `:matches(re)`、`:matchesOwn(re)`、`:matchText`、`:containsData`、`:containsWholeText`、`:containsWholeOwnText` | 0 | 否 | 回傳 `SBX_ERR_UNSUPPORTED` | ✘ 不支援 |
| 命名空間 `ns\|tag` | 0 | 部分 | 未處理 | ✘ 未驗證 |

另有一個 lexbor 3.0.1 的錯誤需要繞過：當 descendant 組合子的**左側**是只有 `:is()`／`:has()`／`:where()` 的複合 selector（例如 `:has(span) a`、`:is(p, div) a`）時，lexbor 只檢查最近的祖先，會漏結果；在複合 selector 前補一個 `*`（`*:has(span) a`）即可避免，改寫層會自動處理，`dom_test` 與 golden 都有覆蓋。

#### Element／Jsoup API 覆蓋

官方套件（`../shinsou_plugin/plugins/*.js`，20 個檔案）實際呼叫次數：`attr` 112、`selectFirst` 101、`text` 80、`select` 65、`forEach` 57、`isEmpty` 14、`map` 9、`html` 6（皆為 `script.html()`）、`size` 5、`absUrl` 4、`ownText` 3、`filter` 3、`remove`／`parent`／`last`／`hasAttr`／`get` 各 1；`Jsoup.parse` 36、`bridge.domReleaseAll` 37、`bridge.parseHtml` 1。

| 類別 | 方法 | 狀態 |
|---|---|---|
| 需要 C 實作（已完成） | `select`、`selectFirst`、`text`、`ownText`、`html`、`outerHtml`、`attr`（含 `abs:`）、`hasAttr`、`absUrl`、`tagName`、`className`、`id`、`children`、`parent`、`nextElementSibling`、`previousElementSibling`、`remove`、`release`／`releaseAll` | ✔ |
| 純 JS shim（現有 `RHINO_DOM_BOOTSTRAP`，不需改） | `Elements.get/first/last/size/isEmpty/forEach/map/filter/text/attr/hasAttr/html/select/eachAttr/eachText`、`getElementsByTag/Class/Id`、`toString`、`fetchAndParse` | ✔ 沿用 |
| 官方語料未使用、Rhino 與 iOS 目前也沒有 | 改寫 DOM（`append`、`attr(k, v)`、`text(s)`…）、`siblingElements`、`child(i)`、`textNodes`、`hasClass`、`closest`、`val`、`data` | 不實作（所有平台一致，沒有退步） |

## 5. 與 jsoup 的對齊測試（golden）

### 5.1 方法

- 預期值：`oracle/Oracle.java`＋Gradle 快取中的 **jsoup 1.18.3**（與 `gradle/libs.versions.toml` 一致，Rhino 端用的就是它）。
- 受測：`test/golden_run.c`（本原型），輸出同一種行格式；`scripts/compare_golden.py` 逐屬性比對，分成「完全相同」「僅空白不同」「不同」。
- 頁面：13 份，共 1.1 MB，`golden/fixtures/`。12 份是用 `curl` 抓的公開頁面（匿名 GET、不帶 Cookie、無登入；已檢查沒有 session／token／個人資料，頁面中只有表單欄位名稱）：E-Hentai 首頁與一本圖庫頁、拷貝漫畫首頁與漫畫頁、漫畫櫃（manhuagui）首頁與漫畫頁、紳士漫畫（wnacg）首頁與本子頁、嗶哩漫畫首頁與詳情頁、動漫之家（dm5）首頁與分類頁；另 1 份 `edge-cases.html` 是手寫的錯誤 HTML／實體／`<br>`／`<pre>`／`<base>`／`<svg>`／`<template>`／自訂元素／Unicode 不可見字元等。頁面內容屬於各網站，**建議不要提交進版本庫**，改用 `scripts/fetch-fixtures.sh` 重新取得（內容會隨時間改變，之後需重新產生 jsoup 預期值）。
- selector：官方語料 151 個（`golden/corpus-selectors.txt`）＋jsoup 專有／邊界情況 140 個（`golden/extra-selectors.txt`），每份頁面都跑，共 3,757 次 `select`。每次執行比對結果數量、`selectFirst` 的標籤，以及前 4 個與最後 2 個結果的 17 個屬性。
- `absUrl`：另有 35 組 (base, rel) 對照表（`golden/urls.txt`），與 jsoup **完全一致**。
- 官方語料中只有 73／151 個 selector 在這些頁面上實際命中元素（其餘是針對詳情頁或章節頁的）；其他的只能驗證「解析與語法行為一致」。

### 5.2 結果

| 屬性 | 比對次數 | 完全相同 | 僅空白不同 | 不同 |
|---|---:|---:|---:|---:|
| `tag`／`id`／`class`／`href`／`src`／`data-original`／`has_href`／`abshref`／`abssrc` | 各 4,233 | 全部 | 0 | 0 |
| `own`（`ownText()`） | 4,233 | 4,233 | 0 | 0 |
| `parent`／`next`／`prev` | 各 4,233 | 全部 | 0 | 0 |
| `nchildren` | 4,233 | 4,232 | 0 | 1（`<template>`） |
| `text`（`text()`） | 4,233 | 4,209 | 10¹ | 14（皆為 `<template>`） |
| `html()` | 2,404 | 1,748 | 619 | 37 |
| `outerHtml()` | 2,404 | 1,573 | 776 | 55 |

¹ 只出現在 20,000 字元截斷標記附近的比對，屬於測試工具的假差異。

整份文件層級（13 份）：`Document.text()`／`body.text()` 10 份完全相同、2 份僅因輸出截斷而無法逐字比對（前 150 字元相同）、1 份是 `edge-cases` 的 `<template>`（D4）；`Document.title()` 有 1 份不同（拷貝漫畫漫畫頁：jsoup 的 `title()` 只在 `<head>` 內找，回傳空字串；本測試以 `select_first("title")` 取得，是測試端的語意差異，不是解析差異）。

選擇器結果數量：官方語料 1,963 次執行 **0 次不同**；jsoup 專有的 1,794 次執行有 121 次不同，全部屬於下表的 D4（`<template>`）、D5（Document 自匹配、空群組）與 D6（不支援的語法）。

### 5.3 差異清單與評估

| # | 差異 | 原因 | 影響與是否可接受 |
|---|---|---|---|
| D1 | `html()`／`outerHtml()` 的縮排與換行 | jsoup 預設 pretty-print（對區塊元素縮排、重排空白）；lexbor 依 HTML 規格逐字序列化 | **可接受**。官方語料只對 `script` 呼叫 `html()`，該情況 37/37 完全一致（`html()` 已比照 jsoup 去頭尾空白）。第三方套件若用 regex 解析 `outerHtml()` 才會受影響；建議把行為定義為「HTML5 序列化」，不承諾與 jsoup 的空白相同 |
| D2 | 屬性拼寫：jsoup 輸出 `selected`、`async`、`data-x`（布林屬性與空值），lexbor 輸出 `selected="selected"`、`async=""`、`data-x=""`；屬性值內的 `>` 在 lexbor 會寫成 `&gt;` | 序列化規則不同 | 可接受（同 D1） |
| D3 | 空的 SVG 元素：jsoup 寫 `<circle r="1" />`，lexbor 寫 `<circle r="1"></circle>` | 同上 | 可接受 |
| D4 | `<template>` 的內容：lexbor 放在獨立的 content fragment，不在子節點裡，所以 `text()`、`select`、`children` 都看不到；jsoup 當一般子節點 | 規格行為 | **可接受**（官方語料與 fixture 都沒用到）；若需要，可在走訪時額外進入 `template` 的 content fragment |
| D5 | Document 本身會被 jsoup 當成可匹配的元素：`select("*")`、`:not(li)`、`:has(img)`、`,li` 多回傳 1 個（`#root`） | jsoup 的 `Document` 是 `Element` | **可接受**。沒有任何套件會期待 `#root` 出現在結果裡 |
| D6 | `:matches()`、`:matchesOwn()`、`:matchText`、`[a~=regex]`、`[^prefix]` | 未實作 | 官方語料 0 次使用；回傳穩定錯誤。若第三方需要，建議由 core 在 JS 端用 QuickJS 的 `RegExp` 先取候選再過濾，不要在 C 端內建 regex 引擎 |
| D7 | `li:nth-child(2n+1)` 等 an+b | 現行 iOS JS bootstrap **不支援**（只支援 `odd`／`even`／整數），lexbor 支援 | lexbor 較正確，不是缺點 |
| D8 | 非 UTF-8 輸入 | 本原型一律以 UTF-8 解析；不做 `<meta charset>` 嗅探重新解碼 | 可接受：JS 端傳進來的本來就是 UTF-8 字串 |
| D9 | `text()` 對 `<pre>`、`<textarea>`、`<title>`、不可見字元等 | 已按 jsoup 規則處理（`pre`／`title`／`textarea`／`plaintext` 保留空白；零寬空格、soft hyphen 移除；`&nbsp;` 視為空白） | 一致 |

`text()` 的主要規則（`test/dom_test.c` 的 `text rules` 與 golden 都有覆蓋）：區塊元素前後補空白、`<br>` 當空白、`script`／`style` 內容不算文字，且「區塊元素後面接已知標籤的元素或文字節點才補空白」（`<div>a</div><foo>b</foo>` 是 `ab`，`<div>a</div><span>b</span>` 是 `a b`）、`svg`／`math` 內部的 `title`／`text`／`g` 不算區塊元素（`jsoup` 的 Tag 解析依命名空間而異）。這些都是靠對照 jsoup 輸出逐一發現再修正的；表示**不能憑猜測重寫 jsoup 語意**，必須有 golden 測試守著。

### 5.4 對照：現行 iOS 純 JS bootstrap

同一組 fixture 與 selector 以 `test/parity_js.c`（QuickJS＋`bootstrap.js`，從 `IosJavaScriptBootstrap.kt` 抽出）跑出結果數量：

| | 官方語料（1,963 次） | jsoup 專有（1,794 次） |
|---|---:|---:|
| lexbor（本原型） | 0 次不同 | 121 次不同（皆為 §5.3 的 D4～D6） |
| 純 JS bootstrap | **4 次不同**（3 個 selector：`meta[name=description]`、`a.title`、`a.next, a:contains(下一頁), …`；原因未逐一追查） | **582 次不同**（65 個 selector，包含 `:nth-child(an+b)`、`~`、`!=`、`:containsOwn`、屬性值與 class 的大小寫、`ul > li`／`tr > td` 這類依賴樹結構的 selector） |

從原始碼可見純 JS bootstrap 的 tokenizer 只在遇到相符的結尾標籤時才彈出堆疊，沒有實作 HTML 的隱含結尾與錯誤巢狀修復（`<li>`、`<p>`、`<td>` 不閉合時會巢狀在一起），因此在真實頁面上樹結構會與 jsoup 不同，依賴樹結構的 selector 結果也隨之不同（我沒有逐一追查每個差異）。這支持「S1 不要把純 JS 當作可交付的過渡實作」。

## 6. 效能

`test/bench_dom.c`（lexbor）與 `test/bench_js.c`（`core/` 的 QuickJS 加上純 JS bootstrap，Release 建置）；機器如 §3，單執行緒，取多次中位數（50 KB 取 15 次或 7 次，1 MB 取 7 次或 3 次）。合成頁面是漫畫列表結構（每筆約 330 位元組、約 12 個節點）。JS 端的 handle 無上限，且給了足夠的 heap。

| 引擎 | 文件 | 大小 | 解析 ms | `a[href*='/manhua-']` | `ul.mh-list > li:nth-child(2n+1) a` | `li:has(img)` | `a:contains(Next)` | `span.tag` | 每個 `li` 取 `text()` | 每個 `a` 取 `attr`＋`absUrl` | 記憶體 MiB |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| lexbor | 合成 | 50 KB | **0.21** | 0.01 | 0.07 | 0.01 | 0.75 | 0.01 | 0.08 | 0.07 | 2.2 |
| QuickJS＋JS | 合成 | 50 KB | 8.12 | 1.49 | 3.41² | 4.50 | 1.52 | 1.25 | 1.59 | 1.29 | 0.9 |
| lexbor | 合成 | 1 MB | **5.43** | 0.35 | 107.25³ | 0.55 | 15.92 | 0.28 | 1.69 | 1.40 | 16.1 |
| QuickJS＋JS | 合成 | 1 MB | 165.63 | 72.17 | 954.38² | 994.30 | 30.99 | 67.98 | 42.78 | 68.86 | 13.1 |
| lexbor | dm5 首頁（真實） | 431 KB | **1.19** | 0.06 | 0.09 | 0.06 | 4.31 | 0.06 | 0.32 | 0.28 | 5.0 |
| QuickJS＋JS | dm5 首頁 | 431 KB | 100.28 | 5.43 | 3.55² | 7.39 | 5.74 | 3.94 | 5.26 | 5.71 | 4.1 |
| lexbor | manhuagui 首頁（真實） | 159 KB | **0.74** | 0.05 | 0.10 | 0.06 | 3.11 | 0.04 | 0.27 | 0.32 | 3.9 |
| QuickJS＋JS | manhuagui 首頁 | 159 KB | 28.84 | 5.55 | 2.78² | 10.04 | 6.54 | 3.87 | 5.38 | 7.17 | 2.7 |

² 純 JS bootstrap 不支援 `2n+1`，結果數量是錯的（合成 1 MB 文件回傳 2 筆，lexbor 與 jsoup 回傳 2,670 筆），所以這格的時間不能拿來比較。
³ lexbor 的 `:nth-child` 每個元素往前掃兄弟，對 5,300 個兄弟是 O(n²)；見 §7.2。

重點：
- **解析**：lexbor 比純 JS 快 30～85 倍；1 MB 頁面 5 ms vs 166 ms。
- **查詢與取值**：一般 selector 快約 15～250 倍（`li:has(img)` 在 1 MB 上差 1,800 倍）；`:contains` 是 lexbor 這邊最慢的路徑（要替每個元素算 jsoup 式 `text()`，1 MB 約 16 ms），只比純 JS 快 1.3～2 倍。
- **記憶體**：lexbor 每個文件有約 1.2 MiB 固定開銷；1 MB 頁面峰值 16 MiB，純 JS 的 heap 為 13 MiB，兩者同一量級，差別在 lexbor 的記憶體不是 JS heap，**必須另外編列預算**（§8）。
- 真實頁面最多 10,378 個節點（dm5 首頁 441 KB）、深度 ≤ 15；合成 1 MB 約 5 萬個節點。預設 `maxDomNodes = 65,536`、`maxDomDepth = 256` 夠用，但接近 1 MB 的頁面會碰到節點上限。
- **未量測**：iOS 的 WASM 解譯（WAMR）下的絕對時間。原生速度比為 30～85 倍的純 JS，在 WASM 解譯下只會更慢；lexbor 編成 wasm 後同樣會被解譯，但它是原生碼指令數少得多的路徑。建議由 WASM 原型 session 用 `build/html-wasm/sandbox_dom.wasm` 實測。

## 7. 安全

### 7.1 Fuzz

`test/fuzz_dom.c` 同時是 libFuzzer 目標與獨立變異 fuzzer：輸入格式為「selector 長度、selector、HTML」，用刻意縮小的限制（1 MiB 輸入、4,096 節點、深度 64、256 handle、256 字元 selector、32 MiB 記憶體）解析，再對結果呼叫所有存取函式（`text`／`html`／`outerHtml`／`attr`／`absUrl`／`children`／`parent`／兄弟／`remove`）。ASan＋UBSan，種子是 13 份 fixture（每份搭配 7 個 selector）。

| 測試（在最終版原始碼上、2026-10-26，Apple M4 Pro，兩者同時跑） | 結果 |
|---|---|
| libFuzzer＋ASan＋UBSan，600 秒（`scripts/run-fuzz.sh 600`，`-timeout=10`、`-rss_limit_mb=2048`） | **267,870 次執行**（約 445 次／秒；種子是大檔，所以速度不高）、涵蓋 14,535 個邊、新增 7,081 個語料；**0 個崩潰、0 個逾時、0 個 OOM**，峰值 RSS 461 MB |
| 獨立變異 fuzzer＋ASan＋UBSan，600 秒（`fuzz_dom 600`；變異含深層巢狀、插入／刪除／複製片段） | **5,949,409 次輸入，0 個崩潰** |

（同一份程式碼在較早的版本也各跑過 600 秒：215,955 次與 5,983,963 次，皆無崩潰；之後只改了 `:eq` 的索引計算、`html()`／`className()` 的 trim 與 URL 邊界情況，所以重跑了一次。）

另外：
- `dom_test` 與 golden 在 ASan＋UBSan 下全部通過；`leaks --atExit` 顯示函式庫本身 **0 個洩漏**（`golden_run` 的 3 個「洩漏」是測試工具自己的 buffer）。
- 已用手工構造的壞輸入驗證限制：節點炸彈、200 層巢狀、4 MB 輸入、`<b><i><u>x` 重複 20,000 次（格式化元素重建的放大）、`:not(:not(…))` 深層巢狀、深樹上的長 descendant 鏈等，皆以穩定錯誤結束，峰值記憶體不超過預算。

### 7.2 已知風險（需要 S1 納入設計）

1. **CPU 時間無法由 QuickJS 的 interrupt handler 中斷。** lexbor 的解析與 selector 匹配是一次 C 呼叫。實測最壞情況：深度 250、5,000 個葉節點的 `:contains` 約 0.09 秒；6 萬個兄弟的 `li:nth-child(2n+1)` 約 **16 秒**（O(n²)，lexbor 自己的實作）、`:nth-last-child`、`:nth-of-type(odd)` 同樣約 15 秒。此時只能靠 Host watchdog（ADR §7：30 秒 wall、deadline＋2 秒強制 kill／terminate）。緩解選項：(a) 沿用標記屬性做法，把 `:nth-child`／`:nth-last-child` 也改成 O(n)（`:eq` 已這樣做，從 15.7 秒降到 0.23 秒）；(b) 在 token callback 內檢查 deadline，讓**解析**可中止；(c) 維持現況，靠 watchdog。目前建議 (c)＋(a)。
2. **標記屬性每次查詢要走訪整份文件。** 使用 `:contains`／`:eq`／`:empty` 或開頭組合子的查詢是 O(整份文件)，不是 O(子樹)。對「對上千個列元素各呼叫一次 `row.select('td:contains(x)')`」的寫法會變成 O(N²)。官方語料只有 3 個 selector 這樣用；若量測顯示問題，可以把標記快取到文件變更為止（需要自己的序列化器過濾標記屬性）。
3. **每個文件約 1.2 MiB 固定開銷。** 需要「同時存活文件數」上限（§4.1）。
4. **lexbor 是 C 程式處理不受信任的輸入。** 這正是放進沙箱的理由；沙箱本身（Android isolated process／macOS helper／WASM 線性記憶體）要擋住解析器漏洞的後果。10 分鐘（約 27 萬次 libFuzzer 執行）的 fuzz 不能保證沒有漏洞，S1 應把 `fuzz_dom` 放進 CI 做常態 fuzz（lexbor 上游也有 libFuzzer 目標）。
5. **allocator hook 是行程全域的。** 一個行程可以有多個 context（依序呼叫安全），但多執行緒同時呼叫不同 context 不安全；ADR 的模型是「每個 package 一個行程或一個 WASM instance、一個執行緒」，符合。

## 8. 整合進 `shinsou-sandbox-core` 的建議方式

核心不用重寫，只需要把 `bridge.*` 的 DOM 原生函式從「iOS 純 JS 實作」換成「C 實作」，並沿用 Rhino 已經在用的薄 JS shim。

**(1) 限制欄位**（`ssc_limits` 新增）

```c
uint32_t dom_max_handles;        /* PluginExecutionLimits.maxDomHandles, 4096 */
uint32_t dom_max_input_bytes;    /* maxDomInputBytes, 4 MiB */
uint32_t dom_max_selector_chars; /* maxSelectorChars, 4096 */
uint32_t dom_max_documents;      /* 新增：同時存活文件數，建議 16 */
size_t   dom_memory_budget;      /* lexbor 的位元組預算；不在 JS heap 內，要從 128 MiB 實例上限中扣 */
/* 既有的 dom_max_nodes / dom_max_depth 直接沿用 */
```

**(2) 生命週期**

```c
/* ssc_new */   rt->dom = sbx_dom_new(&(sbx_dom_limits){ .max_nodes = l->dom_max_nodes, .max_depth = l->dom_max_depth, ... });
/* ssc_invoke 結尾（成功、失敗、取消、逾時都要）*/  sbx_dom_release_all(rt->dom);
/* ssc_free */  sbx_dom_free(rt->dom);
```

invocation 邊界一律 `release_all`，與現行 iOS bootstrap 的「在新的 invocation 邊界重設配額」一致；腳本呼叫的 `bridge.domReleaseAll()` 也直接對應 `sbx_dom_release_all`（文件會真的被釋放，所以不存在「重設後舊物件還活著卻不計配額」的問題；舊 handle 只會得到空字串／null，與 Rhino 的行為相同）。

**(3) 綁定**（QuickJS C 函式，名稱與回傳值與 Rhino 的 `bridge.dom*` 相同，所以 `RHINO_DOM_BOOTSTRAP` 那 ~25 行 JS 可以原封不動，取代 iOS 的 ~150 行 JS 解析器）

```c
static JSValue js_domSelect(JSContext *ctx, JSValueConst self, int argc, JSValueConst *argv) {
    ssc_runtime *rt = JS_GetContextOpaque(ctx);
    int32_t h; JS_ToInt32(ctx, &h, argv[0]);
    const char *css = JS_ToCString(ctx, argv[1]);
    int32_t *hs; size_t n;
    int rc = sbx_dom_select(rt->dom, h, css, &hs, &n);
    JS_FreeCString(ctx, css);
    if (rc) return dom_throw(rt, ctx, rc);                 /* 見下 */
    JSValue arr = JS_NewArray(ctx);
    for (size_t i = 0; i < n; i++) JS_SetPropertyUint32(ctx, arr, (uint32_t)i, JS_NewInt32(ctx, hs[i]));
    sbx_dom_free_array(hs);
    return arr;
}
/* 字串結果：JS_NewStringLen(ctx, sbx_dom_text(rt->dom, h, &len), len) */
```

錯誤對應：`SBX_IS_LIMIT_ERROR(rc)` → 記錄 `rt->limit = SSC_ERR_LIMIT_DOM`（新增的穩定錯誤碼）並丟出無法被腳本 `catch` 後忽略的內部錯誤（invocation 結束時以該碼失敗，不回部分結果）；`SBX_ERR_SELECTOR`／`SBX_ERR_UNSUPPORTED` → 丟出一般 JS `Error`（腳本可 `catch`，與 jsoup 的 `SelectorParseException` 相同）；`SBX_ERR_BAD_HANDLE` 讓 `dom*` 回空字串或 -1，維持 Rhino 行為。`domParse` 的配額查詢已經在核心本地處理，改成直接呼叫 `sbx_dom_parse`，限制由模組內部強制。

**(4) 建置**：核心 CMake 增加 `html/src/sandbox_dom.c` 與 lexbor 的 8 個模組（可以直接使用 `html/CMakeLists.txt` 的 glob 清單）。Android JNI、macOS／Windows helper、WASM 全部用同一份來源；WASM 版需要提供（或 wrap 掉）4 個 WASI import。

**(5) 預算與測試**：JS heap 上限＋`dom_memory_budget` 不得超過行程／instance 上限（128 MiB，iOS 以 WASM 線性記憶體 max 強制）。parity 測試的 golden 資料可以直接沿用本目錄的 `golden/`，並把「官方套件實際輸出」加入。

## 9. 對 ADR 的建議修訂

### 9.1 §5.2「DOM 解析放在哪裡」

1. 把「沙箱內 C 解析器」的條件改成已驗證的結論：「S0 評估 lexbor 3.0.1（Apache-2.0）可行：官方語料 1,963 次 selector 執行與 jsoup 1.18.3 結果數量完全一致；text／attr／absUrl 等逐項一致；詳見 `spikes/sandbox-s0/html/README.md`」。
2. 刪除或改寫「S1 階段可先以現有 iOS JS bootstrap 當暫時實作」：S0 顯示 JS bootstrap 在官方語料就有 4 次結果不同、在 jsoup 專有 selector 上 32% 不同（`an+b`、隱含結尾標籤…），且慢 30～85 倍（原生 QuickJS；iOS WASM 下更慢）。建議 **S1 直接上 lexbor**，JS bootstrap 只保留作為 parity 測試的對照組。
3. 明確定義語意範圍，取代「與 jsoup 一致」這種無界承諾：
   - **保證與 jsoup 1.18.3 一致**：`select`／`selectFirst` 的結果集與順序（§4.2 表中 ✔ 的語法）、`text`、`ownText`、`attr`（含 `abs:`）、`hasAttr`、`absUrl`、`tagName`、`className`、`id`、`children`、`parent`、兄弟、`remove`。
   - **不保證**：`html()`／`outerHtml()` 的空白與屬性拼寫（改為 HTML5 序列化；`script`／`style` 的 `html()` 保證一致）、`<template>` 內容、Document 本身出現在 `*` 等結果中、非 UTF-8 輸入。
   - **不支援（穩定錯誤）**：`:matches*`、`:matchText`、`:containsData`、`:containsWholeText`、`[a~=regex]`、`[^prefix]`。
4. 在 §5.2 的「沙箱內 C 解析器」列補上：DOM 記憶體由 `sandbox_dom` 的 allocator 預算強制，另需 `dom_max_documents`；lexbor 呼叫不可被 interrupt handler 中斷，依賴輸入上限與 Host watchdog；`:nth-child` 家族對極寬兄弟列表為 O(n²)。
5. §7 資源限制表：新增「DOM 記憶體」與「同時存活 DOM 文件數」兩列（初值 64 MiB、16），並註明 DOM 節點數的實測：真實頁面最多約 1 萬個節點，65,536 足夠但 1 MB 的頁面會接近上限。
6. §13 測試策略：把 `golden/` 的 fixture＋jsoup oracle 流程列為 parity 測試的一部分（`DomReleaseCompatibilityFixture` 只覆蓋少數情況），並新增 `fuzz_dom` 的常態 fuzz 與 ASan CI。

### 9.2 §14 開放問題 1（最終選擇與容忍範圍）

建議改寫成「已決定，附條件」：

> **C HTML 解析器採用 lexbor。**（S0 實測）授權 Apache-2.0；原生 0.8 MB／wasm32 0.5 MB；官方語料 1,963 次 selector 執行與 jsoup 1.18.3 零差異；ASan＋UBSan 的 libFuzzer 600 秒無崩潰。容忍範圍：見 §5.2 的「保證／不保證／不支援」三分法，由 `html/golden` 的 parity 測試守住，任何新增的差異必須先登記再放行。尚未決定、需 S1 處理：`dom_max_documents` 的數值、`:nth-child` 家族是否改成 O(n) 標記實作、是否在 token callback 加入 deadline 檢查、是否裁掉編碼表以縮小約 0.3 MB。
>
> 備案：若上游停止維護，持有 fork（程式碼量與授權都允許）；未實測其他候選（gumbo-parser 已無維護且沒有 selector、html5ever 需要 Rust 工具鏈與 wasm32 目標、litehtml 偏向排版），本次不評估。

### 9.3 其他發現（可能需要寫回 ADR）

- 純 JS bootstrap 就是**現行 iOS（JavaScriptCore）實際使用的 DOM 實作**（`IOS_JAVASCRIPTCORE_BOOTSTRAP`），所以它的語意缺陷（不支援 `an+b`、沒有隱含結尾標籤處理）今天就存在於 iOS；官方語料恰好沒踩到（4/1,963 次不同）。這是現行行為的附帶發現，不屬於本 ADR 的範圍。
- jsoup 的 `:matchText` 會**改寫 DOM**（把文字節點包成同名的偽元素），第三方套件若用到，會污染同一份文件；`sandbox_dom` 直接拒絕。

## 10. 重現

乾淨環境、所有輸出都在 `spikes/sandbox-s0/build/html*`：

```sh
spikes/sandbox-s0/html/scripts/reproduce.sh 600    # 參數是 fuzz 秒數，預設 30；約 1.5 分鐘＋fuzz 時間
```

需要 cmake、clang、python3；golden 需要 JDK 與 Gradle 快取中的 `jsoup-1.18.3.jar`（或設 `JSOUP_JAR`）；wasm 與 libFuzzer 需要 Homebrew LLVM（`/opt/homebrew/opt/llvm`）與 `wasi-libc`，缺少時會略過並提示。單獨執行：

| 目的 | 指令 |
|---|---|
| 建置＋單元測試 | `cmake -S html -B build/html && cmake --build build/html && build/html/dom_test` |
| golden 對照 jsoup | `html/scripts/run-golden.sh`（報告：`build/html-golden/report.txt`） |
| 純 JS bootstrap 對照 | `build/html/parity_js build/html/bootstrap.js html/golden html/golden/corpus-selectors.txt html/golden/extra-selectors.txt`，再用 `scripts/compare_golden.py … --counts-only` 或 `scripts/parity_summary.py` |
| 效能 | `build/html/bench_dom <html…>`、`build/html/bench_js build/html/bootstrap.js <html…>` |
| wasm32 編譯 | `html/scripts/build-wasm.sh` |
| libFuzzer | `html/scripts/run-fuzz.sh 600`；獨立 fuzzer：`cmake -DSBX_SANITIZE=ON` 後執行 `fuzz_dom 600` |

## 11. 目錄

```
html/
  CMakeLists.txt            獨立建置（不碰根目錄 CMake）
  src/sandbox_dom.{h,c}     原型
  test/dom_test.c           單元測試與限制測試
  test/golden_run.c         golden 輸出（對照 oracle/Oracle.java）
  test/bench_{common.h,dom.c,js.c}, parity_js.c   效能與對照
  test/fuzz_dom.c           libFuzzer 目標＋獨立變異 fuzzer
  oracle/Oracle.java        jsoup 1.18.3 預期值產生器
  golden/                   fixtures、selector 清單、URL 對照表
  scripts/                  reproduce / run-golden / run-fuzz / build-wasm / fetch-fixtures / compare_golden.py / parity_summary.py
```
