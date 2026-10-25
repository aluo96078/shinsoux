# 來源相容性修復與驗證（2026-09-05）

本報告是每個來源一筆公開目錄／書籍／首章的抽樣，不代表整個站點或所有登入狀態都正常。測試使用記憶體儲存，不讀取個人帳密。站方拒絕或網路不可達仍列為未解決。

## 受限本機開發儲存庫與 Mac 部署（2026-09-07 01:01）

- 使用者核准的範圍：解決 `http://127.0.0.1:18081` 被 App 拒絕，僅允許已審核精確套件，並部署 Mac。未發布、commit／push 或部署 iPhone；未自動更改使用者已安裝的禁漫天堂或核准其權限。
- App 基準仍為 `8830f5b447e268ce9e1338275ab80dd234ddba5a` 加現有 dirty 工作區；保留所有先前修正。新增不可由 index 自行開啟的啟動政策，正常建置預設停用；本機版以 `-PshinsouReviewedLocalRepository=true` 明確啟用。
- 實際安裝包已讀取原 Python server，僅列出 14 個完整匹配 App 審核表的 Shinsou 套件，JM 為 1.0.9／code 10、24,107 bytes、SHA-256 `1cee1cf231775b8b622b855dc3d9e3269e52550201c88408c895537fec6880a8`。其餘套件不進入受限本機目錄；正式來源原有相容性不變。
- URL、port、路徑、index 版本、完整 manifest／source、sidecar、script digest／size 分層核對；固定 local provenance 在啟動政策關閉時不建立 runtime 或內容能力。既有 user grant、停用、撤銷與 browser capability 仍有效；configured author pin 保留優先，無簽章不能退回 local compatibility。
- 專用 OkHttp 4.12.0 transport 僅直連固定 IPv4 loopback，不帶 Cookie／Authorization／代理，不做網域查詢或重新導向；設逾時、bounded headers/body，取消會取消請求。安裝包實測確認 Python HTTP/1.0 舊連線重用造成第二個請求 EOF；加入 `Connection: close`，未開啟自動重試，再測同一 server 的四個檔案全部成功。
- 最終穩定版驗證：Desktop 1,421 項、Android 1,114 項、iOS Simulator 插件 397 項，均 0 failure／error／skip。Desktop 含 5 項實際 HTTP 回環測試；原 server 於測試期間暫停、測試後恢復，未使用其他 port。新增回歸涵蓋偽造 row 安裝前拒絕、sidecar／script 竄改、安裝前後權限、fresh store 冷啟動開關、撤銷、pin precedence、舊格式與混合格式拒絕及 cancellation。Opt-in public source probes 均停用；一般測試中早退的 live probe 不算實際網站覆蓋。
- 工具：Gradle 9.4.1、Temurin JDK 25.0.2、Kotlin/Native 2.4.0，使用既有離線快取，無資安掃描器／漏洞資料庫更新。測試網路僅 `127.0.0.1:18081` GET repository 文件，來源網站採離線 fixture/mock。
- 已部署 `/Applications/Shinsou X.app`，沿用原 Apple Development 簽章與 designated requirement，部署前後 deep/strict signature、`--verify-desktop-runtime`、`--verify-reviewed-local-repository` 全部通過，已重新開啟。舊版保留 `/Applications/Shinsou X.pre-reviewed-local-20260907-0100.app`，未刪除書庫或個人資料。此為開發簽章，不是 notarized 公開發行版。
- 功能／效能與操作方式見 [受限本機開發入口](REVIEWED_LOCAL_REPOSITORY.md)。封面並行與閱讀管線不變；新增精確比對、hash 驗證與本機短連線成本，未做性能 benchmark。使用者仍需從本機目錄更新禁漫天堂並核准權限；人工 Cloudflare 驗證及真實站點目錄／閱讀仍未驗證。
- 證據 log 位於 `/private/tmp/shinsou-local-repo-final-package-20260907.log`、`/private/tmp/shinsou-local-repo-mobile-20260907.log`、`/private/tmp/shinsou-local-repo-probe-final-20260907.log`；JUnit 詳細結果位於 `composeApp/build/test-results/`。
- 部署 App 內主程式 JAR `composeApp-desktop-88030258d97d733f776542db1b970.jar` 的 SHA-256 為 `e0844da18e410de1e337f3d9ea83a638578724a000e2945cd8c11c9f93397c4c`；此為 JAR 指紋，不是整個 App bundle 的 hash。

## 禁漫天堂續修（2026-09-06，JM-20260906）

本節僅使用原始碼、已保存 artifact 與離線 mock；下方較早的公開抽樣不代表本輪重新測試。使用者回報使用「線上最新版」，截圖顯示「沒有符合項目」。截圖不能證明確切安裝版本或回應內容，本輪沒有重新連線核對線上索引。

- 基準：App HEAD `8830f5b447e268ce9e1338275ab80dd234ddba5a`、插件庫 HEAD `eab68a0c241e8dd8afda4443108ca0dacb4e664d`，兩者皆有先前未提交修正，予以保留。已保存的線上 1.0.7／code 8 腳本為 21,381 bytes、SHA-256 `f45a1485ac720d9a1963c7cecde874926c3e727f209c5534e04b49b544ebfb27`。
- 後續唯讀核對本機 `Library/Application Support/Shinsou/Content/plugins/packages/zh.jinmantiantang/package.json` 的版本、digest、權限欄位：實際已安裝為 1.0.7／code 8，同一 `f45a1485…` digest，僅 `EXECUTE_SCRIPT`、`NETWORK`。未讀共享設定、個人帳密或 Cookie。這確認本機仍使用舊 artifact，不代表重新確認遠端索引。
- 已確認：舊腳本不區分驗證頁與目錄；本機未發布的 1.0.8 又漏判 structured HTTP 200 驗證頁。用合成 `_cf_chl_opt` 回應重現其被當成正常 HTML 放行，這能解釋空列表的一條路徑，但不能據此斷定使用者當次回應一定是 Cloudflare。
- 套件 1.0.9／code 10 統一 structured／legacy 單次請求的錯誤分類，空白內容與無效 status 不再靜默變成空目錄；有效回應先判 BLOCKED，再判明確 challenge。一般頁面只有 Cloudflare JSD／Turnstile 資源時不視為驗證牆。正常非空 HTML 的零筆搜尋結果保留，不猜測未知網站 DOM。
- 1.0.9 腳本、index、sidecar 與 App 精確 catalog 已同步，保留舊 artifact 的原權限；沒有替 1.0.7 增加 Cookie／瀏覽器能力。新 artifact 為 24,107 bytes、SHA-256 `1cee1cf231775b8b622b855dc3d9e3269e52550201c88408c895537fec6880a8`。
- 按 Shinsou Extension 技能維持 `shinsou`／`legacy-shinsou-adapter-v2`、sourceId `1817081`、內容種類與權限一致；沒有改成 ShuYue、加入帳號登入或增加 CDN／導航網域。
- App 僅在完整匹配的 CHALLENGE 標記、精確 live artifact 通過 manager availability 檢查時顯示驗證按鈕；首次空目錄錯誤與已有內容的分頁錯誤都有入口。只有使用者點擊後才核發一次性驗證憑證；匯入成功後重試相同來源、查詢、模式、filters、頁碼及 append 狀態，不自動循環驗證。
- Availability 不讀 Cookie／帳密、不核發或撤銷既有憑證，並拒絕停用、撤銷、無瀏覽器權限／origin 或不存活來源。補上停用時 issue／import 拒絕及 legacy 重新啟用清除停用標記。關閉、切換來源、取消或建構失敗會撤銷未交付憑證；成功匯入仍使用原始精確網站範圍。
- 已執行 Node v24.6.0：`v2-migration-smoke`（20 套件）、`source-regressions`、`http-failure-regressions` 與插件庫 `git diff --check` 全部通過。App 51 項 focused 整合通過，使用真實 Rhino／Jsoup 與完全 mock transport；涵蓋正常 catalogue＋JSD、封面網址、頁碼、詳情、章節、閱讀 URL 解擾 metadata、合法空搜尋、200／403／503 challenge、BLOCKED 優先、空白回應與單次請求，以及合成驗證 Cookie／UA 真正帶入後續 browse。
- 最終穩定版本複驗：Desktop 1,405 項、Android 1,106 項、iOS Simulator 指定插件／本地化／Cookie／解擾 413 項，JUnit 報告均 0 failure／error／skip。所有 live-source 環境旗標未啟用；早退的 opt-in live 測試不代表網站端驗證。工具為 Gradle 9.4.1、JDK 25.0.2、Kotlin/Native 2.4.0，`--offline` 使用本機快取。初次 wrapper 缺快取曾嘗試下載並 DNS 失敗，未完成下載；改用已安裝 Gradle 後，本機 socket 權限與 Android SDK 變數衝突亦已排除。最終日誌：`/private/tmp/shinsou-jm-20260906-final.log`。
- 功能／效能：增加 HTML 固定標記與少量正則判斷，不增加每次來源請求數、不改封面並行上限或圖片解擾。僅遇到驗證錯誤才檢查按鈕可用性；使用者驗證成功後多一次明確的重試。正常搜尋空結果保留；有問題的空白回應現在顯示錯誤。沒有性能基準，不能宣稱零成本或固定加速。瀏覽器建立仍有既有 WebView 成本，Android 不放寬外部瀏覽器隔離限制。
- 尚未發布、部署或替換使用者安裝的插件；沒有 commit／push。真人 Cloudflare 驗證、站方是否接受匯入後 session、最新站點 DOM、真實封面與閱讀图片仍未完成本輪端到端驗證，不能宣稱禁漫天堂已全面恢復。

## 結果

| 來源 | 本次結果 | 限制／後續 |
| --- | --- | --- |
| 輕小說文庫 | 封面下載解碼、詳情、章節、首章內容通過 | 公開抽樣 |
| 筆趣閣 | 封面下載解碼、詳情、章節、首章內容通過 | 公開抽樣 |
| 嗶哩輕小說 | 封面下載解碼、詳情、章節、首章內容通過 | 公開抽樣 |
| 紳士漫畫 | 封面、詳情、24 頁章節列表及首圖通過 | 公開抽樣 |
| 漫畫人 | 封面、53 章列表、18 頁章節及首圖通過 | 公開抽樣 |
| 拷貝漫畫 | 封面、116 章列表、7 頁章節及首圖通過 | CDN 主機可能輪替 |
| E-Hentai | 封面、81 頁章節、延遲解析及首圖通過 | 原回報書籍未重測；不能確定原錯誤根因 |
| 漫畫櫃 | 修復後 327 章、198 頁章節及首圖通過；封面通過 | 需發布／更新插件 1.1.7 |
| 動漫屋 | 修復後 971 章、15 頁章節及首圖通過；封面通過 | 需發布／更新插件 1.4.1；CDN 主機可能輪替 |
| 包子漫畫 | 封面、777 章列表、64 頁章節解析通過；舊輸出 bzcdn 與目前 mapping 的 baozicdn 均解析至 206.168.190.107 | 該位址拒絕連線，閱讀圖片仍未恢復 |
| 禁漫天堂 | 1.0.9／code 10 修正 HTTP 200 驗證漏判並加入目錄驗證後重試，詳見續修節 | 尚未發布；本機仍安裝 1.0.7，未完成人工 Cloudflare 驗證，不能宣稱目錄或閱讀已恢復 |
| MyComic | 1.0.1／code 2 能區分 HTTP 403 拒絕／驗證頁並顯示中文錯誤；最後抽樣為驗證頁 | 尚未發布；未授予內嵌驗證入口，僅改善診斷，目錄內容未恢復 |
| 嗶咔漫畫 | 修正舊式 UI 列的 manager 精確映射與隔離儲存；審核 ShuYue 儲存判定改為目前仍存活且精確核准的 artifact | 未使用真實帳號驗證完整登入／閱讀 |
| 嗶哩漫畫 | 已以受限 WKWebView＋固定 IP 代理恢復公開圖片，Desktop 跨 2 章 4 圖通過，iOS 模擬器取圖與解碼通過 | macOS 14+／iOS 17+；仍為公開抽樣，有圖片大小與格式限制，詳見下節 |

## 修正與影響

- 封面改為最多 6 個同時載入，耗時查找移至背景 dispatcher；保留每站請求啟動節流。可重疊網路等待，增加有上限的同時連線與暫存圖片記憶體，不承諾固定倍數加速。
- 圖片走精確来源／精確已審核套件的內容網路權限，補入實測 CDN；未增加一般網域萬用字元、私有網路存取或傳送 Cookie／Authorization 的權限。
- 小說來源使用實際已載入 artifact 的 scope，避免錯綁至舊 profile。停用或撤銷審核授權後不再核發新的圖片 scope。
- 保留安全內容標頭、去除來源 metadata 的帳密標頭；支援拷貝漫畫圖片回應的 binary/octet-stream，仍拒絕 HTML／SVG。
- 嗶哩漫畫 sidecar 的單一媒體類型可以由 package 繼承；明示不一致及混合類型不明仍拒絕。
- 嗶咔驗證補上舊式來源轉接器 lookup，登入資料寫入對應來源的隔離儲存。權限仍限定精確官方腳本與審核網站。
- 禁漫天堂的瀏覽器能力只由精確官方 1.0.8／code 9 artifact 核發；頂層導覽限 `https://18comic.vip`，Cloudflare 僅列為 subresource origin，manifest 不授予 `browserSessionOrigins` 或一般瀏覽器 transport。匯入必須具有未過期、path 可涵蓋挑戰網址且屬於精確主機的 `cf_clearance`。
- JavaScriptCore 僅辨識 primitive string 中固定、完整匹配的來源錯誤標記；任意 object exception 在 JavaScript 邊界先改為固定字串，避免引擎在例外離開時隱式執行 `toString()`。相關記錄已遮蔽；iOS 插件使用 literal marker，避免跨引擎包裝差異。包裝仍保留 legacy contract 的 `var source`／`var sources` 匯出。
- MyComic 的 HTTP 403 現在提供明確後續操作，但沒有虛構或快取內容作為目錄恢復。
- 動漫屋解析 escaped quotes，保留 cid/key 與章節 Referer；漫畫櫃以既有 LZString 解碼章節 HTML，不執行網站 JavaScript。依 Extension v2 skill 同步 script、index、sidecar、版本與雜湊。
- 桌面安裝包增加 URLStreamHandlerProvider／EPUB 協定檢查。本次開發環境與安裝後檢查均通過；原 E-Hentai 錯誤未能重現，不能宣稱確定修復了特定根因。
- 功能與效能：新增工作主要是最多 512 字元的固定標記比對及額外精確 ownership lookup，未增加網路請求或更改封面並行上限。沒有效能基準數據，不宣稱零成本；過期／路徑不符的驗證 Cookie 會要求重新驗證，偽裝同名來源不再能使用已審核來源的儲存。

### 嗶哩漫畫瀏覽器圖片路徑（2026-09-06）

- 已驗證 Desktop 實際 transport：精確測試圖片 `https://i.motiezw.com/1/1615/133085/2911882.avif` 經公開 DNS、固定 CONNECT proxy（TLS SNI／HTTP Host 保留原主機，failover=false）及無帳密瀏覽器請求後，輸出 PNG，HTTP 200、2,410,057 bytes、1350×1920；以實際 Skia 解碼確認尺寸。
- 同一 production manager pipeline 已完成 2 個章節、4 張圖片的實際抓取驗證。此為公開抽樣，不代表所有圖片或站點狀態均已恢復。
- Reviewed browser image transport 僅 macOS 14+ 與 iOS 17+；Android、Windows、Linux 保留原生 transport。瀏覽器圖片最多同時 2 個請求，與封面最多 6 個並行上限分開。
- 每次瀏覽器請求使用短生命週期的隔離 WebView／helper，會增加啟動 CPU／記憶體；PNG 正規化通常比 AVIF 佔用更大，也可能增加下載與快取成本，並非降低畫質。輸入與輸出各受 4 MiB 上限，總像素最多 8,388,608、單邊最多 8192；較大的圖片可能被安全策略拒絕。AVIF 解碼前結構化檢查 ISO-BMFF `ispe`，解碼後再次核對尺寸；多個不同尺寸的 AVIF item 會被保守拒絕。原生解碼器仍有內部配置成本，不能把像素上限當作程序記憶體硬限制。
- 內容權限仍限精確 artifact、精確圖片 URL／路徑及公開 DNS 位址；不傳送 Cookie、Authorization 或其他 credentials。
- iOS Simulator 實測已通過原生 BiliManga HTML 請求、兩次隔離瀏覽器圖片請求與 UIImage 解碼（1350×1920），以及有效但不匹配的公開 IP 失敗測試；Desktop 同樣驗證錯誤 IP 不會退回直連。未使用個人帳號或 Cookie。
- 修正 WebKit 同一連線的 407 認證重試（最多一次）、CONNECT Host 可省略預設 443、桌面 stdin 串流讀取與指令大小限制、WebKit 規則語法，以及 iOS 原生 Network 常數橋接造成的 TLS 誤啟用與送出崩潰。一般插件權限沒有增加，TLS 信任仍由 WebKit 驗證。
- 目前 macOS 使用已核准 DNS 清單中的第一個位址；iOS 的後續位址重試只涵蓋部分失敗類型。第一個位址不可達時仍可能載入失敗，不會自行擴大目的地或改為直連。

### 瀏覽器圖片修正版部署（2026-09-06 02:40）

- Desktop 全量 1,398 項、Android 全量 1,099 項測試通過，無失敗／略過。公開取圖測試另行以顯式環境旗標執行，並非僅以預設跳過的測試函式算作實測通過。
- iOS Simulator 最終 382 項插件測試通過，包含實際 JavaScriptCore 執行 AVIF 結構解析器的合法／8 組惡意資料測試、原生網站請求、兩次 PNG 真實取圖解碼與禁止錯誤 IP 直連回退。
- macOS `/Applications/Shinsou X.app` 已更新、正常開啟，簽章與部署前後 `--verify-desktop-runtime` 通過；舊版保留於 `/Applications/Shinsou X.pre-browser-image-fix-20260906-0240.app`。
- iPhone 裝置 Debug 建置／簽章成功，02:38 已安裝至已配對 iPhone 17 Pro Max。自動啟動被 iOS 以 Locked 拒絕；需解鎖後手動開啟，尚未在實體 iPhone 人工驗證閱讀畫面。
- 本輪沒有修改或發布插件產物，也沒有 commit／push。禁漫天堂人工驗證、MyComic、包子漫畫 CDN 與嗶咔真實登入等先前未解決項目仍保留，不因本次 BiliManga 修復而宣稱全部來源恢復。

## 本輪驗證與部署（2026-09-06 續）

- Desktop：1,388 項、Android：1,091 項測試通過，無失敗或略過。
- 插件庫：20 個套件 v2-migration-smoke、source-regressions、http-failure-regressions 與 diff check 通過。
- macOS 已更新 `/Applications/Shinsou X.app`，簽章及部署後 runtime probe 通過，已開啟。修復前版本保留於 `/Applications/Shinsou X.pre-unresolved-fix-20260906-0000.app`，最終文字調整前版本另存 `/Applications/Shinsou X.pre-wording-fix-20260906-0015.app`，未刪除個人資料。
- iOS Simulator：393 項插件、錯誤本地化及驗證 Cookie 測試通過，無失敗或略過；涵蓋所有四種固定錯誤、未知／HTML／過大錯誤遮罩及任意例外物件不執行 `toString()`。
- iPhone 17 Pro Max：本輪 Debug build／簽章成功；安全修正版曾成功安裝及啟動，最終文字調整版本也已安裝（00:14），但此次重啟被裝置鎖定拒絕（00:15），需解鎖後手動開啟。仍未進行真人登入或 Cloudflare 驗證。
- 禁漫天堂尚未完成人工 Cloudflare 驗證，MyComic 與嗶哩漫畫也尚無內容恢復證據。
- 最新 literal-marker 插件經正式 Rhino runtime 公開抽樣，禁漫天堂與 MyComic 的驗證頁均產生本地化提示，沒有退化成空目錄；MyComic 沒有已審核 Web 驗證入口，提示已改為「若來源設定提供」以避免引導到不存在的功能。
- 禁漫天堂 1.0.8／code 9、MyComic 1.0.1／code 2 插件仍未發布；本機 App 部署不會自行替換已安裝插件。沒有 commit 或 push。

## 歷史驗證與部署（非本輪結果）

- 先前 Desktop：1,380 項測試通過。
- 先前 Android：1,085 項單元測試通過。
- 先前 iOS Simulator：248 項插件相關測試通過；iOS 裝置編譯及簽章成功。
- 插件庫：v2-migration-smoke、source-regressions、git diff --check 通過。
- macOS：部署至 /Applications/Shinsou X.app，簽章與安裝後 runtime probe 通過，已啟動。
- 舊 macOS App：/Applications/Shinsou X.pre-source-fix-20260905-2254.app，可恢復；未刪除個人資料。
- 已配對 iPhone 17 Pro Max：安裝 dev.aluo.shinsoux 成功；啟動遭 iOS 拒絕，原因為裝置鎖定，需解鎖後手動開啟。
- 沒有 commit 或 push。動漫屋 1.4.1／漫畫櫃 1.1.7 的插件庫修正尚未發布到線上；App 更新不等於已安裝的插件自動更新。

## 最終插件產物

| 插件 | 版本／code | bytes | SHA-256 |
| --- | --- | ---: | --- |
| zh.dm5 | 1.4.1／6 | 22580 | 88618e4c1e6df4a7955c40e5c24a879c6d04baf945d5a89fea25f2b27ca4e133 |
| zh.manhuagui | 1.1.7／9 | 29153 | 75eb03ef359f5ba8aea594f5ec888c24ba045e7b012edd82949fb1a50c270d39 |
| zh.jinmantiantang（未發布） | 1.0.9／10 | 24107 | 1cee1cf231775b8b622b855dc3d9e3269e52550201c88408c895537fec6880a8 |
| zh.mycomic（未發布） | 1.0.1／2 | 22895 | eee353857e13f2c07e7c72d9cd9be6eb6c32a55b1de927ec0985acd57795aa90 |
