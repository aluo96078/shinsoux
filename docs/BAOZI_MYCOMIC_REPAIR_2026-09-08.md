# 包子漫畫與 MyComic 修復（2026-09-08）

## 範圍與基準

使用者確認兩個來源無法使用並要求實作修復。範圍為 App 審核目錄、這兩個 Shinsou 套件及離線回歸；不自動更新已安裝套件、核准權限、部署或發布。

- App HEAD：`8830f5b447e268ce9e1338275ab80dd234ddba5a`，保留既有未提交修改。
- 套件庫 HEAD：`eab68a0c241e8dd8afda4443108ca0dacb4e664d`，保留既有未提交修改。
- 本輪開始時 Mac 安裝版本：包子漫畫 1.0.6、MyComic 1.0.0；腳本雜湊符合安裝紀錄。
- 測試不使用個人帳密、Cookie 或書庫。網站回應採合成 fixture／mock；離線測試不能證明目前 CDN 可用或人工 Cloudflare 驗證成功。

## 已確認的程式問題

1. 包子漫畫腳本明確使用 `https://www.twmanga.com` 的章節閱讀路徑，但其精確審核網路政策沒有允許此 origin。正常跨站 redirect 因而會被拒絕；先前僅直接餵入 HTML 的 parser 測試沒有覆蓋這條正式網路路徑。
2. 包子漫畫的列表／詳情／章節遇到錯誤會回傳空資料；閱讀流程可能依序嘗試多個受阻路徑後仍回傳空頁，掩蓋真正的拒絕、挑戰或網路失敗。
3. MyComic 本機 1.0.1 在 structured HTTP 200 流程先回傳 HTML，漏掉部分挑戰頁；旧版沒有可用的精確審核瀏覽器挑戰入口。

歷史包子 CDN 連線遭拒是另外一個尚待實測的問題，不能因修復 App 允許清單就宣稱 CDN 已恢復。歷史 MyComic 同時有明確封鎖頁與挑戰觀察；封鎖不等於一定能經由驗證解除。

## 實作與驗收

### 套件產物

| 套件 | 本機修正版 | bytes | SHA-256 |
|---|---|---:|---|
| 包子漫畫 | 1.0.7／code 8 | 23,300 | `79010b5571ca4079be744df68bb984df339b691c92a6bee0c61b3a2ba6372290` |
| MyComic | 1.0.2／code 3 | 23,990 | `d79fbcd04e3398f57d73af021691fa368ae3d7702dc3ef62d00cc67144fd7eba` |

兩者仍為 `contract: shinsou`／`legacy-shinsou-adapter-v2`、`IMAGE_SEQUENCE`，sourceId 不變。腳本、index、sidecar 的版本與 digest／size 已同步；未修改保留的舊格式 migration artifacts，也沒有發布。

### 功能與安全邊界

- 包子漫畫新 artifact 增加 `https://www.twmanga.com` 的精確 request／content origin，用於現有公共閱讀路徑；credentialOrigins 不變，新增站點不接收 Cookie、Authorization 或來源敏感標頭。不授予瀏覽器、Cookie 儲存或登入能力。舊 1.0.6 不自動取得新增權限範圍。
- 包子列表、詳情、章節及閱讀改為 structured／legacy 單次請求分類；封鎖／403／挑戰／空白或異常回應不再假裝成空目錄。閱讀遇到終止性拒絕立即停止，普通失敗保留有限相容路徑；所有路徑無有效頁面時顯示錯誤。合法搜尋零結果保留。
- 包子圖片 `w640` 改寫只適用精確的 `https://s1.baozicdn.com/scomic/`，不誤改其他 `.com` 圖片 URL。已知絕對 CDN URL 與 data-src 優先順序保留。直接閱讀 URL 的相對圖片可用該請求的基底解析；bridge 未提供最終 redirect URL，所以不宣稱已解決所有跨站相對網址情境。
- MyComic 新 artifact 才要求 `COOKIE_STORAGE`／`BROWSER_CHALLENGE`；不新增登入、帳密或 localStorage 能力。使用者明確核准後，驗證頁為 `https://mycomic.com/comics`，頂層導覽只允許 `https://mycomic.com`，Cloudflare origin 僅供驗證頁子資源，不能成為插件 HTTP／憑證目的地。
- MyComic 匯入需有合法、未過期、適用該路徑的 `cf_clearance`；驗證 Cookie 與實際 User-Agent 保持同一隔離來源。舊版、未核准、停用、偽造／過期憑證繼續拒絕。
- 明確封鎖頁優先於挑戰判斷；HTTP 200 的普通 JSD／Turnstile 資源不當成驗證牆。不偽造通過驗證、不繞過網站存取控制。

### 效能與可用性

正常請求增加少量 HTML 標記檢查，不增加單次頁面請求數。包子在拒絕／挑戰時停止嘗試後續鏡像，減少無效等待；普通網路失敗仍可能走原本的有限備援路徑。封面並行上限、圖片解碼與全域網路節流不變。未做 benchmark，不能承諾固定加速或零成本。

新版套件必須搭配含本次精確審核表的 App。更新套件庫檔案不會更新 `/Applications` 的 App 或已安裝套件；此輪未部署，也未改個人授權。MyComic 真實挑戰是否能完成、包子 CDN 是否可用仍待限定網站實測；Android 保持既有 external-browser-only 安全政策。

### 測試

工具：Node v24.6.0、Gradle 9.4.1、Temurin JDK 25.0.2；Gradle 使用 `--offline` 與既有快取，所有 live-probe／wire-test 旗標停用。未更新漏洞資料庫、未連線第三方網站。

Node `v2-migration-smoke`（20 套件）、`source-regressions`、擴充 `http-failure-regressions`、新增 `baozi-reader-regressions` 已通過，兩個 repo 的 `git diff --check` 通過。

穩定版本 Desktop 74 項、Android 21 項 focused 回歸全部通過，0 failure／error／skip。Desktop 含正式 manager 安裝／核准與 Rhino+Jsoup：包子公共 redirect→閱讀頁→圖片 transport（圖片為合成 bytes，不算真實解碼）、未知 redirect 不送出、受阻停止備援；MyComic 先遇到挑戰→匯入合成 clearance→重試完全相同的查詢，再解析列表／詳情／章節／閱讀 URL。可攜權限測試另外驗證新增 reader origin 不繼承合成 Cookie／Authorization、Cloudflare 子資源不變成 script origin，以及精確版本／digest／permission 綁定。

測試開發過程曾有新測試的 Kotlin 語法／參數錯誤與一個錯誤 Cookie 預期，均修正後重新執行；最終結果以 `verified-final-test.log` 及對應 JUnit XML 為準。首個 Android task 名稱不適用，已改用現有 `testDebugUnitTest`。沒有修改正式 Cookie 保護來迎合測試。

iOS Simulator（Kotlin/Native 2.4.0）同組權限／Cookie 回歸 21 項通過，0 failure／error／skip。這是共用授權／網路政策的 Native 測試，不是兩個網站的 JavaScriptCore 真人閱讀 E2E。

證據目錄：`/private/tmp/shinsou-baozi-mycomic-20260908.9zi4RB/`。Desktop／Android 最終日誌為 `verified-final-test.log`；iOS 模擬器為 `ios-test.log`。JUnit XML 位於 `composeApp/build/test-results/` 下的對應平台目錄。
