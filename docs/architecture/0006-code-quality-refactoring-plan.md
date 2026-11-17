# 0006 — 程式碼品質與架構重構計畫

- 狀態：實施中（進度見第 14 節）
- 日期：2026-11-17
- 範圍：只處理程式碼品質、架構與可用性；不改變任何功能行為
- 依據：2026-11-17 對工作樹現況的唯讀審查（當時有 54 個未提交的變更檔）

## 1. 摘要

專案的單點程式碼衛生良好：沒有 `GlobalScope`、沒有 TODO/FIXME、取消例外大多正確重拋、外掛沙箱與 EPUB 解析有明確的資源上限，測試檔 286 個。問題集中在結構層：

- 整個應用是單一 Gradle 模組，commonMain 約 11.6 萬行，套件邊界沒有編譯期約束。
- 套件之間循環依賴，`plugin` 與 `ui` 互相匯入，`domain` 反向依賴上層。
- UI 沒有狀態持有層，商業邏輯寫在數千行的 Composable 裡。
- 資料庫全靠手寫 SQL，schema 遷移機制從未被呼叫。
- 沒有任何靜態分析，pull request 的測試門檻不完整。

本計畫分五個階段，由低成本高回報的修正開始，最後才做模組拆分。每個階段都能獨立合併並交付價值，不需要一次做完。

## 2. 目標與非目標

**目標**

1. 消除已知會造成崩潰或錯誤狀態的缺陷。
2. 讓規範由工具強制，而不是靠人工審查維持。
3. 讓資料庫 schema 有可用、可測的升級路徑。
4. 解開循環依賴，使模組拆分可行，並縮短增量編譯時間。
5. 讓 UI 的商業邏輯可以脫離 Compose 做單元測試。

**非目標**

- 不新增、移除或調整任何功能。
- 不更換技術選型（Compose Multiplatform、Ktor、SQLite、沙箱架構維持不變）。
- 不重寫已經運作良好且有測試保護的部分（沙箱協定、EPUB 解析、同步合併邏輯）。

## 3. 現況基線

以下數字取自 `composeApp/src` 的五個 main source set（commonMain、androidMain、iosMain、desktopMain、jvmCommonMain），作為各階段完成後的比較基準。

| 指標 | 現況 |
|---|---|
| Gradle 模組數 | 1 |
| commonMain 行數 | 116,605 |
| 超過 2000 行的檔案 | 8 |
| `catch (` | 621 |
| `catch (x: Exception/Throwable)` | 295 |
| `runCatching` | 439 |
| `!!` | 175 |
| `runBlocking`（非測試） | 5 處實際呼叫 |
| `CoroutineScope(` | 56 |
| `println(` | 6 |
| `expect` 宣告 | 17 |
| `SqlSchema.migrate()` 呼叫點 | 0 |
| 靜態分析工具 | 無 |

**套件依賴矩陣的異常項**（commonMain 的 import 次數）

| 來源套件 | 不應存在的依賴 |
|---|---|
| `plugin` | `ui`（42） |
| `domain` | `reader`（1）、`content`（1）、`rights`（1） |
| `local` | `ui`（7） |
| `backup` | `ui`（1） |
| `content` | `plugin`（2） |
| `reader` | `sync`（2）、`plugin`（3） |

## 4. 目標架構

### 4.1 分層與依賴方向

依賴只能由上往下，同層之間不互相依賴。

```
app（組裝、平台入口）
  └─ ui（Compose 畫面、狀態持有層）
       └─ feature：plugin / sync / translation / manga / reader / backup / download / tracking / acquisition / search
            └─ data：content（SQL store）、repository（快照）
                 └─ domain（模型、契約介面）
                      └─ core：concurrent / crypto / files / network / logging
```

規則：

- `domain` 不依賴任何上層套件，也不依賴 Compose。
- feature 之間需要互通時，透過 `domain` 內定義的契約介面，由 `app` 負責注入。
- `ui` 之外的套件不得匯入 `dev.shinsou.kmp.ui.*`。

### 4.2 目標模組

| 模組 | 內容 | 來源 |
|---|---|---|
| `:core` | `concurrent`、`crypto`、`files`、`network`、新增的 `logging` | 現有同名套件 |
| `:domain` | `domain/model`、`rights`、跨 feature 的契約型別 | `domain`、`rights`，以及由 `ui/AppServices.kt` 下沉的型別 |
| `:data-content` | `content`、`annotation`、`search` | 現有同名套件 |
| `:data-repository` | `data`、`migration` | 現有同名套件 |
| `:feature-plugin` | `plugin` 全部子套件、jvmCommonMain 的 runtime | `plugin` |
| `:feature-sync` | `sync/v3` | `sync` |
| `:feature-translation` | `translation`、`manga` | 現有同名套件 |
| `:feature-reader` | `reader`、`acquisition`、`local`、`tts` | 現有同名套件 |
| `:feature-library` | `backup`、`download`、`tracking`、`statistics`、`update` | 現有同名套件 |
| `:ui` | `ui`、`navigation` | 現有同名套件 |
| `:composeApp` | `app`、平台入口、打包設定 | 保留 |

模組數量可以視實作成本合併，但 `:domain`、`:data-content`、`:feature-plugin`、`:ui` 四個邊界必須獨立，因為目前的循環依賴都發生在它們之間。

## 5. 階段一：可用性缺陷修正

目的：修掉已確認會造成崩潰或錯誤狀態的問題。每項都是小範圍修改，可各自獨立提交。

### R1-1 `SyncApi` 對可能為 null 的回應強制解包

- **證據**：`sync/v3/SyncApi.kt:244` 在 204 或空回應時回傳 `null`；同檔案有 23 處以 `)!!` 解包（已核對）。
- **影響**：伺服器任一端點回空內容時丟出 `NullPointerException`，繞過 `SyncV3Service` 以 `SyncApiException` 分流的退避與 410 重新同步處理。
- **做法**：把 `call()` 拆成兩個函式。有回應型別的版本回傳非 null 的 `R`，空回應時拋 `SyncApiException(status, "empty_response", …)`；無回應型別的版本回傳 `Unit`。移除全部 23 處 `!!`。
- **驗收**：`SyncApi.kt` 內 `!!` 為 0；新增測試以 mock engine 回 204，斷言拋出 `SyncApiException` 且同步循環判定為可重試或永久失敗之一，而非未分類例外。

### R1-2 暫時限流被當成配額用盡，波及所有作品

- **證據**：`translation/CloudTranslationProviders.kt:141` 把 429/456/529 一律對應到 `QUOTA_EXCEEDED`，不讀 `Retry-After`；`translation/TranslationPrefetchQueue.kt:281-287` 把 `QUOTA_EXCEEDED` 列入 `STOPS_EVERY_WORK`（已核對）。
- **做法**：新增 `TranslationFailure.RATE_LIMITED`。429 與 529 先依 `Retry-After`（無則指數退避）重試一到兩次，仍失敗才回報 `RATE_LIMITED`，且只停止該作品。只有供應商明確回報配額用盡（如 DeepL 的 456）才維持 `QUOTA_EXCEEDED`。
- **驗收**：新增測試：第一次回 429、第二次回 200 時翻譯成功；連續 429 時只有該作品的佇列項目轉為失敗。

### R1-3 `runCatching` 包住 suspend 呼叫吞掉取消

- **證據**：`ui/screens/NovelTranslationPanel.kt:146`（已核對）、`ui/screens/MangaTranslationPanel.kt:317`、`ui/screens/TrackingSheet.kt:170`、`ui/ShinsouApp.kt:931`。全專案 `runCatching` 共 439 處，其餘尚未逐一檢查。
- **做法**：在 `concurrent` 新增 `suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T>`，捕捉後若為 `CancellationException` 立即重拋。先替換上述四處，再全面盤點其餘 `runCatching`，凡是區塊內有 suspend 呼叫的一律替換。
- **驗收**：階段二導入的 lint 規則對「`runCatching` 內含 suspend 呼叫」報錯且全專案零違規。

### R1-4 並行路徑上的 `!!`

- **證據**：`manga/MangaTranslation.kt:571`、`:598`、`:645`、`:648`、`:672`，`manga/MangaPipeline.kt:179`。這些值來自跨協程共享的陣列，正確性依賴「每個索引一定先被填值」的隱性約定。
- **做法**：短期改為 `checkNotNull(x) { "page $i 尚未載入" }`，讓崩潰報告可定位。長期隨 R4-4 把這些陣列換成帶狀態的值類別。
- **驗收**：`manga/` 內 `!!` 為 0。

### R1-5 iOS 啟動時多建一個未設逾時的 `HttpClient`

- **證據**：`iosMain/.../MainViewController.kt:202`，在容器的 `init` 內無條件建立，只供效能測試使用。
- **做法**：把建構移到 `startMangaBenchmarkIfAsked` 內確認環境變數存在之後，或直接重用第 135 行的共用 client。
- **驗收**：一般啟動路徑只建立一個 `HttpClient`。

### R1-6 桌面端 JNA 呼叫未防護

- **證據**：`desktopMain/.../DesktopMangaPlatform.kt:93-101`，調整執行緒優先權的原生呼叫沒有例外處理，放在 `by lazy` 內。
- **影響**：符號不存在時整個漫畫翻譯 dispatcher 初始化失敗，而這只是次要最佳化。
- **做法**：包住原生呼叫，失敗時記錄並退回不調整優先權。
- **驗收**：以測試注入失敗的原生呼叫，dispatcher 仍可建立。

## 6. 階段二：工程護欄

目的：讓後續重構有工具把關，避免階段一修掉的問題再出現。這個階段不改產品程式碼的行為。

### R2-1 導入 detekt

- **做法**：加入 detekt 與 `.editorconfig`。第一次執行時產生 baseline 檔，既有違規全部列入 baseline，只對新程式碼強制。啟用的規則至少包含：
  - `SwallowedException`、`TooGenericExceptionCaught`
  - `UnsafeCallOnNullableType`（`!!`）
  - `LongMethod`（門檻先設 150 行）、`LargeClass`、`LongParameterList`
  - `ForbiddenMethodCall`：`kotlin.runCatching`（引導改用 R1-3 的函式）、`kotlinx.coroutines.runBlocking`、`println`
  - `InjectDispatcher`
- **驗收**：`./gradlew detekt` 在 CI 執行且通過；baseline 檔納入版控，之後只能縮小不能擴大。

### R2-2 依賴方向檢查

- **做法**：在模組拆分完成前，先用一個測試（或 detekt 的 `ForbiddenImport`）列出禁止的 import，例如 `plugin`、`local`、`backup`、`content` 不得匯入 `dev.shinsou.kmp.ui.*`，`domain` 不得匯入 `reader`、`content`。既有違規列入允許清單，隨階段四逐步清空。
- **驗收**：新增的違規 import 會讓 CI 失敗。

### R2-3 補齊 pull request 的測試門檻

- **現況**：對一般變更會跑的只有 `windows.yml` 的 `desktopTest`。`sandbox-ios.yml` 的 `iosSimulatorArm64Test`、`sandbox-android.yml` 的裝置測試只在特定路徑或手動觸發；`release.yml` 的建置任務前面沒有測試。
- **做法**：
  1. 新增一個對所有 pull request 觸發的工作流程：`detekt`、`desktopTest`（Linux，最快）、Android 單元測試。
  2. `iosSimulatorArm64Test` 改為對 `composeApp/src/commonMain/**` 與 `iosMain/**` 的變更觸發。
  3. `release.yml` 增加一個測試任務，四個平台建置任務都 `needs` 它。
- **驗收**：任何修改 commonMain 的 pull request 都會跑 lint 與至少兩個平台的測試；測試失敗時無法產出發佈檔。

### R2-4 統一日誌

- **現況**：沒有 logger；185 處 `catch (_: …)` 丟棄錯誤內容；零星的 `println` 與 `System.err.println`。
- **做法**：在 `:core` 新增最小的 `Logger` 介面（`debug`/`warn`/`error`，帶 tag 與可選的 throwable），各平台提供實作，預設在 release 只輸出 warn 以上。明訂不得記錄 token、API key、cookie 與使用者內容。靜默吞錯的位置改為 `logger.warn`，優先處理 `TranslationPrefetchQueue.kt:173` 與 `MangaTranslation.kt:559`。
- **驗收**：`println(` 在 main source set 為 0（效能測試工具除外）；翻譯與漫畫管線的失敗路徑都留下可查的紀錄。

### R2-5 測試覆蓋率量測

- **做法**：導入 Kover，只產生報告，先不設門檻。取得基線後，再對 `content`、`sync`、`translation` 設定不得下降的門檻。
- **驗收**：CI 產出覆蓋率報告。

### R2-6 倉庫衛生

- 把 `work/` 自版控移除，並連同 `spikes/`、`.claude/worktrees/` 寫進 `.gitignore`（後兩者目前只在本機的 `.git/info/exclude`）。
- 把 `docs/` 根目錄的一次性報告（`BAOZI_MYCOMIC_REPAIR_*`、`SOURCE_REPAIR_REPORT_*`）移到 `docs/reports/`。
- GitHub Actions 的引用釘到 commit SHA，至少涵蓋會接觸簽章金鑰的 `release.yml`。
- 四份相同的 22 萬位元組圖示檔改為建置時由單一來源複製。

## 7. 階段三：資料層

目的：讓 schema 可以安全演進，並消除手寫 SQL 的脆弱寫法。

### R3-1 建立可用的 schema 遷移路徑

- **證據**：全專案沒有任何 `.migrate(` 呼叫點（已核對）。`search/SqlDriverLocalFullTextIndex.kt:79` 在版本不符時 `require` 失敗。實際的欄位變更靠散落的 `PRAGMA table_info` 加 `ALTER TABLE`（`content/SqlDriverContentBlobStore.kt:1285`、`translation/TranslationMemoryStore.kt:44-51`）。
- **做法**：
  1. 在 `:data-content` 新增 `SchemaMigrator`：讀取已儲存的版本；等於目標版本則不動；較舊則在單一交易內依序執行已註冊的遷移步驟並更新版本；較新則拋出可辨識的 `SchemaTooNewException`，由上層顯示「請更新應用程式」而非崩潰。
  2. 把 `ContentTransactionSchema`、`ContentBlobPersistenceSchema`、`LocalFullTextIndexSchema`、`ContentAnnotationSchema`、`SyncV3Schema`、`TranslationMemoryStore` 的 schema 都接到這個機制，現有的條件式 `ALTER TABLE` 改寫成具編號的遷移步驟。
  3. 全文索引屬於可重建的衍生資料：版本不符時改為清空並重建，不要拋例外。
- **驗收**：每個 schema 都有測試，從每一個歷史版本的資料庫檔升級到最新版並驗證資料完整；另有測試確認「資料庫比程式新」時回報 `SchemaTooNewException`。

### R3-2 字串插值 SQL 改為綁定參數

- **證據**：`content/SqlDriverContentTransactionStore.kt` 約 30 處，例如 `:1148-1151` 把 `sqlLiteral()` 的結果拼進 `LIKE '…'`（已核對）。`sqlLiteral` 只轉義單引號，`LIKE` 的 `%` 與 `_` 沒有轉義，鍵值含底線時會多匹配。
- **做法**：`queryRows` 增加參數綁定的多載；所有呼叫點改用 `?`。前綴比對改為 `LIKE ? ESCAPE '\'`，並在共用函式內轉義 `%`、`_`、`\`。最後刪除 `sqlLiteral`。
- **驗收**：`sqlLiteral` 不存在；新增測試：兩個 publication key 只差在底線位置（如 `a_b` 與 `axb`）時，附件查詢不會互相取到對方的資料。

### R3-3 結果列的位置索引解析

- **證據**：`content/` 下 88 處 `row[n]`，所有欄位以字串讀回再轉型。專案只用 SQLDelight 的 runtime，沒有套用其 plugin，也沒有 `.sq` 檔。
- **方案比較**：

  | 方案 | 優點 | 代價 |
  |---|---|---|
  | A. 導入 SQLDelight plugin 與 `.sq` 檔 | 編譯期檢查欄位與型別 | 要把所有查詢搬到 `.sq`，改動面最大；動態組合的查詢需要另外處理 |
  | B. 自建具名欄位讀取層 | 改動小，可逐檔進行 | 仍沒有編譯期的 SQL 檢查 |

- **建議**：先做 B。每張表定義欄位常數與一個 `RowReader`，以欄位名稱取值並用正確的型別讀取（`getLong` 而非字串轉型）。新增的 store 若查詢是靜態的，直接採用 A。
- **驗收**：`content/` 內裸的 `row[n]` 為 0。

### R3-4 連線設定與執行緒

- **PRAGMA**：`journal_mode`、`synchronous`、`foreign_keys` 目前只在 `SqlDriverContentTransactionStore` 的建構子設定，靠所有 store 共用同一個 driver 才生效。移到 `ContentFoundationRuntime`（driver 的唯一持有者），並加測試斷言 `foreign_keys` 為開啟。
- **Dispatcher**：資料庫 store 內完全沒有 dispatcher 切換，`ContentFoundationRuntime.transaction` 是 suspend 但內部同步阻塞。在入口統一 `withContext` 到注入的 IO dispatcher。此項是否已在主執行緒發生過尚未驗證，實作前先在 debug 建置加上主執行緒斷言確認。
- **驗收**：debug 建置在主執行緒執行 SQL 時會失敗；現有測試全數通過。

### R3-5 備份匯出改為串流

- **證據**：`backup/PortableContentBackupV2.kt:418`、`:468` 與 `backup/AutoBackupService.kt:156` 都是整份 `encodeToString().encodeToByteArray()`。
- **做法**：改用 kotlinx.serialization 的 okio 串流編解碼，直接寫入 `Sink`、由 `Source` 讀取。
- **驗收**：以合成的大型書庫測試匯出，記憶體峰值不隨書庫大小線性成長。

### R3-6 釐清兩套持久化的權責

- **現況**：`data/ShinsouRepository` 把整份 `AppSnapshot` 序列化成 JSON；`content/` 是細粒度 SQL 交易；兩者靠投影標記橋接。
- **做法**：這一項先只寫文件。在 ADR-0001 補上一張表，列出每類資料目前由哪一邊負責、過渡期結束後 `AppSnapshot` 會移除哪些欄位。是否把快照的內容遷入 SQL 屬於另一個決策，不在本計畫範圍。

## 8. 階段四：解耦與拆分

目的：解開循環依賴並拆解巨型檔案，為模組化做準備。這是工作量最大的階段，每一項都應獨立成數個小的提交，且不與功能變更混在同一個提交裡。

### R4-1 把共用型別從 `ui` 下沉

- **證據**：`plugin` 匯入 `ui` 的型別包含 `ContentCallbacks`、`ReaderPage`、`ReaderChapter`、`TypedReaderContentSession`、`SourceLoginRequest`、`SourceLoginResult`、`SourceCookie`、`SourceCredential`、`SourceSecrets`、`SourcePreference`、`SourceWebChallengeRequest`、`MigrationCandidate`、`ImportedDocument`、`BrowseManga`、`BrowseFilter`，以及 `ui.portability.PortableContentBackupV*`。它們都定義在 `ui/AppServices.kt` 或 `ui/portability/`。
- **做法**：
  1. 純資料型別（`Browse*`、`Reader*`、`Source*`、`MigrationCandidate`、`ImportedDocument`）移到 `domain/model` 下的新檔案，依主題分檔（`BrowseModels.kt`、`SourceAuthModels.kt`、`ReaderSessionModels.kt`）。
  2. `ui/portability` 的備份轉接邏輯移到 `backup`。
  3. `domain` 對 `reader.ReadingLocator`、`content.ContentManifest`、`rights.RightsGrantRef` 的依賴：把這三個型別移入 `domain`，原套件改為引用。
  4. 每移完一批，就從 R2-2 的允許清單刪掉對應項目。
- **驗收**：`ui` 以外的套件沒有任何 `import dev.shinsou.kmp.ui.`；`domain` 不匯入任何其他業務套件。

### R4-2 拆解巨型介面

- **證據**：`ui/AppServices.kt:355` 的 `ShinsouAppServices` 有 43 個成員；`:968` 的 `BrowseCallbacks` 有 92 個成員。三個平台各自實作整份介面。
- **做法**：
  - `ShinsouAppServices` 依職責拆為 `ClipboardHost`、`DocumentHost`（匯入匯出）、`DeepLinkHost`、`LifecycleHost`、`SecurityHost`（生物辨識、螢幕保護）、`InputHost`（音量鍵）、`ExternalNavigationHost`。原介面暫時保留為這些介面的組合，待呼叫端都改為依賴小介面後移除。
  - `BrowseCallbacks` 拆為 `SourceCatalog`（搜尋、瀏覽、詳情）、`SourceAuth`（登入、cookie、網頁驗證）、`SourceSettings`、`ExtensionManagement`（安裝、信任、授權）、`ContentAcquisition`。
- **驗收**：沒有超過 15 個成員的服務介面；各畫面只接收它實際使用的介面。

### R4-3 UI 狀態持有層與導航

- **證據**：`ui/ShinsouApp.kt:306-2300` 的 `ShinsouAppContent` 約 2000 行、98 個狀態宣告；`ui/screens/BrowseScreen.kt:332` 的 `BrowseScreen` 有 27 個參數、函式體約 940 行；`navigation/` 只有一個 45 行的深連結解析器。
- **做法**：
  1. **導航**：定義 `sealed interface Destination` 與 `AppNavigator`。後者是不依賴 Compose 的類別，持有 `StateFlow<NavigationState>`，以純函式處理事件（開啟作品、返回、深連結、切換分頁）。`ShinsouApp.kt:1732-2168` 那一串以 `!!` 判斷的 `when` 改為對 `Destination` 的窮舉。
  2. **狀態持有者**：由 `ShinsouAppContent` 抽出三個不依賴 Compose 的類別，各自持有 `StateFlow` 並接受注入的 scope：
     - `PluginReviewCoordinator`：來源審核閘門、儲存庫信任、事件授權審查。
     - `ReaderSessionController`：閱讀器 session 的開啟、關閉與進度回寫。
     - `LibrarySelectionState`：選取的作品、待處理的瀏覽項目、遷移流程。
  3. **畫面層**：`BrowseScreen`、`ReaderScreen`、`ExtensionV2PublicationPane` 各自對應一個狀態持有者；Composable 只接收 `state` 與一個事件回呼介面。相關的回呼打包成資料類，把參數壓到十個以內。
  4. 同一批狀態持有者順手解決 process death：需要還原的狀態改用 `rememberSaveable` 或寫入持久層。
- **順序**：先導航，再 `ReaderSessionController`（邊界最清楚），最後 `BrowseScreen`。
- **驗收**：`ShinsouAppContent` 少於 300 行；導航規則與三個狀態持有者都有不依賴 Compose 的單元測試；`ui/` 內 `!!` 為 0。

### R4-4 拆解巨型類別與函式

| 對象 | 現況 | 拆分方式 |
|---|---|---|
| `plugin/PluginManager.kt` | 3006 行、170 個函式 | 拆出 `PluginInstaller`（安裝、更新、移除）、`PluginRepositoryRefresher`（刷新與准入）、`PluginTrustCoordinator`、`PluginWebChallengeCoordinator`、`PluginRuntimeLoader`；legacy 身分對應集中到 `LegacyPluginCompat`。`PluginManager` 只保留門面與鎖序 |
| `plugin/PluginUiAdapter.kt` | 2736 行、176 個函式 | 依 R4-2 拆出的五個介面各一個實作類別 |
| `content/SqlDriverContentTransactionStore.kt` | 2691 行；`commitLocked` 約 278 行、`createTables` 約 230 行 | schema 定義獨立成檔；每張表的讀寫各自成為內部類別；`commitLocked` 只保留步驟順序與統一的錯誤處理 |
| `ui/screens/BrowseScreen.kt` | 6265 行 | 依畫面分檔：來源清單、搜尋結果、擴充套件管理、來源設定對話框、`ExtensionV2PublicationPane` |
| `manga/MangaTranslation.kt` | 907 行；`drawChapter` 與 `drawProgressively` 混用四種並行原語 | 拆為 `MangaResourceManager`（模型與加速器）與 `MangaChapterTranslator`；排程改為明確的階段物件（載入、偵測、辨識、翻譯、繪製），以有界 `Channel` 串接，共享陣列改為帶狀態的值類別 |
| `acquisition/EpubAcquisitionService.kt` | 1484 行；`parsePackage` 約 200 行 | CSS 相依性解析移到獨立檔案；`parsePackage` 拆為 mimetype、OPF、manifest、spine 四個步驟 |
| `ui/i18n/ShinsouStrings.kt` | 3934 行 | 見 R4-6 |

驗收：沒有超過 1500 行的產品程式碼檔案（產生的檔案除外）；detekt 的 `LongMethod` 門檻由 150 行降到 80 行且 baseline 不新增。

每個拆分都遵守同一個做法：先確認該檔案有測試保護，只搬移不改邏輯，搬移與後續的邏輯調整分成不同的提交。

### R4-5 外掛執行期的橋接與網路

- **`runBlocking` 橋接**：`jvmCommonMain/.../RhinoScriptPluginRuntime.kt:430` 在單執行緒 dispatcher 上以 `runBlocking` 回呼 suspend 程式碼，結構上可能自我死鎖，目前靠 30 秒逾時兜底；`iosMain/.../JavaScriptCoreScriptPluginRuntime.kt:535` 是相同寫法。`docs/PLUGIN_SYSTEM.md:28` 說這兩個 runtime 只用於測試與對照。
  - 若確實只用於測試：把兩個檔案移到測試 source set（或獨立的測試支援模組），讓產品建置不可能引用到。
  - 若仍有產品路徑使用：加上「回呼中不得再排程回同一 dispatcher」的斷言與對應測試。
  - 實作前需先確認哪一種情況成立，這一點尚未驗證。
- **逾時集中**：新增 `PluginNetworkLimits`（連線、整體請求、UI 等待三個層級），取代散落在 `PlatformPluginNetwork.jvm.kt:37`、`PlatformPluginNetwork.ios.kt:91`、`PluginManager.kt:1618`、`PluginUiAdapter.kt:211` 的數值。
- **失敗退避**：在 `PluginNetwork` 加入以來源為單位的失敗退避。近期連續失敗的來源在短時間內直接快速失敗，避免每次瀏覽都等滿逾時。
- **防護邏輯共用**：回應大小上限、宣告長度一致性、解壓上限等與平台無關的檢查移到 commonMain，平台只提供 socket 與 DNS。iOS 目前是否具備等價的防護尚未驗證，搬移時一併補上跨平台的契約測試。

### R4-6 i18n 改用具型別的鍵

- **證據**：`ui/i18n/ShinsouStrings.kt:84` 的 `text(key: String, …)` 以整句英文原文查表。改一個英文字，所有語言的翻譯就靜默退回英文。
- **做法**：
  1. 以腳本掃描所有 `text("…")` 呼叫，為每個字串產生穩定的識別碼，改為 `enum class StringKey` 或 Compose Resources 的字串資源。專案已經使用 `compose.components.resources`，改用其字串資源可同時解決分檔與缺漏檢查，建議優先評估。
  2. 每種語言獨立成檔。
  3. 新增測試：每種語言的鍵集合與基準語言一致，缺漏時列出清單。
- **驗收**：不存在以原文字串為鍵的查表；缺漏翻譯會讓測試失敗。

### R4-7 同步服務的鎖與測試缺口

- `sync/v3/SyncV3Service.kt:944` 的 `cycleMutex` 包住整個循環，背景同步逾時上限 8 分鐘（`:1826`），手動同步會一起等。改為手動同步以 `tryLock` 加短逾時取鎖，取不到時回報「同步進行中」。
- 426（版本不相容）在登入後的循環只顯示泛用錯誤，於 `userMessage()` 補上專屬分支。
- 補三個測試：同時觸發兩次同步時 push 與 pull 各只發生一次；push 成功但回應遺失後重送，最終狀態與只送一次相同；快照寫入成功但 `setApplied` 未提交時重新執行 `applyChunk`，結果等價。

### R4-8 自建 scope 的生命週期

- `translation/TranslationPrefetchQueue.kt:97` 與 `plugin/v2/ExtensionDownloadQueueV2.kt:70` 自建 scope 但沒有關閉的方法。改為由建構子注入 scope（預設值維持現狀），並提供 `close()`，由 `ShinsouComposition.close` 呼叫。
- 全專案 56 處 `CoroutineScope(` 逐一確認有對應的取消路徑，沒有的比照處理。

## 9. 階段五：模組化與建置

前提：階段四的 R4-1 完成，R2-2 的允許清單已清空。

### R5-1 拆分 Gradle 模組

- **順序**（由底層往上，每一步都能獨立合併）：
  1. `:core`
  2. `:domain`
  3. `:data-content`、`:data-repository`
  4. `:feature-sync`、`:feature-translation`（對外依賴最少）
  5. `:feature-reader`、`:feature-library`
  6. `:feature-plugin`（最大，且帶有 jvmCommonMain、cinterop 與原生建置）
  7. `:ui`
- **共用設定**：新增 `build-logic` 內的 convention plugin，統一 target 清單、手動的 source set 階層（`jvmCommonMain`、`iosMain`）、JVM 版本與 detekt 設定，避免每個模組重複。
- **可見性**：每個模組預設 `internal`，只公開契約型別。Kotlin 的 `explicitApi()` 可在 `:domain` 與 `:core` 先啟用。
- **驗收**：依賴圖無循環；修改單一 feature 模組時，其他 feature 模組不需重新編譯。

### R5-2 精簡建置腳本

- **現況**：`composeApp/build.gradle.kts` 有 1164 行。
- **做法**：
  - `GeneratePublicSuffixData`、`GenerateBuildInfo` 兩個 task 類別移到 `build-logic`。
  - 原生函式庫的建置（沙箱輔助程式、翻譯核心、iOS 的 WASM host、iOS vision）各自成為一個 convention plugin 或獨立的腳本檔，並以具型別的 task 取代 `Exec` 加 shell 字串。
  - `fetchSandboxCoreDeps`、`fetchTranslationCoreDeps` 宣告輸入（釘版清單與 patch）與輸出（`third_party` 目錄），使其可以判定為最新而略過。
  - 以 `tasks.named(…)` 與具型別的 provider 取代 `tasks.configureEach { if (name.startsWith(…)) }` 的字串比對。
  - JavaFX 與 bytedeco 的座標移入 `gradle/libs.versions.toml`；`ndkVersion` 移入版本目錄；`-j 8` 改為依可用處理器數量。
  - 簽章相關的設定集中到一個檔案。
- **驗收**：`composeApp/build.gradle.kts` 少於 300 行；未變更原生程式碼時，第二次建置不重跑取得相依套件與原生編譯。

### R5-3 Android 發佈建置

- **現況**：release 建置型別沒有啟用壓縮混淆，實際發佈的是 debug 變體（`release.yml:151`，建置腳本的註解也如此說明）。
- **做法**：這牽涉發佈策略，需要先做決策。若改為發佈 release 變體，需補齊 R8 規則（JNA、ONNX Runtime、OpenCV、kotlinx.serialization、AIDL）並在裝置測試驗證。本計畫只把它列為待決事項。

## 10. 執行順序與規模

規模為粗估，用來排序而不是承諾工期：S 是一到兩天，M 是三到五天，L 是一到兩週，XL 超過兩週。

| 階段 | 項目 | 規模 | 前置 |
|---|---|---|---|
| 一 | R1-1 至 R1-6 | 各 S | 無 |
| 二 | R2-1 detekt | M | 無 |
| 二 | R2-2 依賴檢查 | S | 無 |
| 二 | R2-3 CI 門檻 | M | R2-1 |
| 二 | R2-4 日誌 | M | 無 |
| 二 | R2-5、R2-6 | 各 S | 無 |
| 三 | R3-1 遷移機制 | L | 無 |
| 三 | R3-2 綁定參數 | M | 無 |
| 三 | R3-3 具名欄位 | L | R3-2 |
| 三 | R3-4、R3-5 | 各 M | 無 |
| 四 | R4-1 型別下沉 | L | R2-2 |
| 四 | R4-2 拆介面 | L | R4-1 |
| 四 | R4-3 狀態持有層 | XL | R4-2 |
| 四 | R4-4 拆巨型檔案 | XL（可分批） | R2-1 |
| 四 | R4-5 至 R4-8 | 各 M 至 L | 無 |
| 五 | R5-1 模組化 | XL | R4-1 |
| 五 | R5-2 建置腳本 | L | 可與 R5-1 並行 |

階段一與階段二可以立刻開始且彼此不衝突。階段三與階段四互不相依，可以並行。

## 11. 風險與對策

| 風險 | 對策 |
|---|---|
| 大規模搬移造成行為回歸 | 搬移與邏輯修改分開提交；搬移的提交只允許移動與改 import；每一步都跑完整測試 |
| 與進行中的功能開發衝突（目前工作樹就有 54 個未提交的檔案） | 重構以小的提交進行；動到熱點檔案（`BrowseScreen.kt`、`ShinsouApp.kt`）前先讓進行中的功能合併 |
| schema 遷移寫錯導致使用者資料損毀 | 遷移在單一交易內執行；保留每個歷史版本的資料庫檔作為測試夾具；首次遷移前自動備份資料庫檔 |
| 模組化後 iOS 建置變慢或連結失敗 | 先拆沒有 cinterop 的模組；`:feature-plugin` 與 `:feature-translation` 的 cinterop 留到最後 |
| detekt 的 baseline 變成永久豁免 | CI 檢查 baseline 的條目數只減不增；R4-4 每拆完一個檔案就重新產生 baseline |

## 12. 完成後的驗證指標

| 指標 | 現況 | 目標 |
|---|---|---|
| Gradle 模組數 | 1 | 8 以上 |
| 超過 1500 行的產品程式碼檔案 | 17 | 0 |
| 最長的函式 | 約 2000 行 | 80 行以內（baseline 除外） |
| `ui` 以外對 `ui` 的 import | 50 以上 | 0 |
| `SqlSchema.migrate()` 可達且有測試 | 否 | 每個 schema 都有 |
| 字串插值 SQL | 約 30 處 | 0 |
| 包住 suspend 的 `runCatching` | 至少 4 處 | 0（由 lint 強制） |
| `!!`（main source set） | 175 | 30 以下，且都不在並行路徑上 |
| pull request 必跑的檢查 | Windows 的 `desktopTest` | lint、desktop、Android 單元測試、iOS 模擬器測試 |
| 靜默吞錯且無紀錄的 `catch` | 185 | 0 |

## 13. 尚未驗證的事項

以下主張來自審查中的推論，實作對應項目前需要先確認：

1. 資料庫操作是否曾在主執行緒執行（R3-4）。
2. Rhino 與 JavaScriptCore 的 in-process runtime 是否完全不在產品路徑上（R4-5）。
3. iOS 的外掛網路層是否具備與 JVM 等價的回應大小與解壓防護（R4-5）。
4. `runCatching` 其餘四百多處中有多少包住 suspend 呼叫（R1-3）。
5. `sync/v3/SyncDiff.kt` 以參照比較判斷變更，是否依賴 `ShinsouRepository` 每次提交都產生新物件的保證。
6. 各平台入口是否都會在結束時呼叫 `ShinsouComposition.close()`。
7. `manga/MangaTranslation.kt:644-645` 以控制字元 `\u0001`、`\u0002` 作為分隔符組成快取鍵，翻譯結果若含這些字元是否會造成鍵衝突。

審查中曾提出但經核對不成立的項目，記錄於此避免重複調查：

- `SynchronousLock` 的重入問題：JVM 實作是 `ReentrantLock`，iOS 是 `NSRecursiveLock`，都可重入。
- 下載佇列系統暫停標記的寫入順序：`BackgroundDownloadCoordinator.kt:187-199` 是先落盤再暫停，順序正確。
- EPUB 整檔讀入記憶體：產品路徑使用 `BoundedEpubArchiveExtractor`，逐項讀取並驗證後釋放。

## 14. 實施進度（2026-11-17）

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| R1-1～R1-6 | 完成 | |
| R2-1～R2-6 | 完成（有偏離） | detekt baseline 在 `config/detekt/`，R4-4 搬移後已重新產生。「只減不增」由 `scripts/ci/check_baselines_shrink.sh` 在 PR 與推送到 master 時檢查（force push 後找不到前一個 commit 時只警告）；detekt baseline 與依賴 allowlist 比條目數（搬移會改條目名稱），i18n 清單比條目內容。R2-4：`println` 還剩兩處刻意寫到 stdout 的輸出（桌面 `--verify-runtime` 的結果、iOS `MANGABENCH` 基準輸出）。R2-6：圖示只有 Android 改為建置時複製，iOS 仍是提交的副本，由 `AppIconCopiesTest` 把關。R2-3：`checks.yml` 的 Android 單元測試在 macOS runner 跑（libsodium host library 在 Linux 載不進來）；`release.yml` 只在推 tag 時跑 checks，重建既有 tag 時跳過（舊 tag 的原始碼沒有這些檢查） |
| R3-1～R3-3、R3-6 | 完成 | 全文索引遇到較新格式時依 R3-1 第 3 點清空重建（`rebuildWhenNewer`），不回報 `SchemaTooNewException`；代價是降版後要重建一次索引，索引是衍生資料，不會遺失使用者資料。第 11 節「首次遷移前自動備份資料庫檔」已補上：開啟 store 前若有表群將升級，先把資料庫檔（含未併入的 WAL）複製為 `<檔名>.before-upgrade`（是開啟 store 前的狀態；Android/iOS 驅動開檔時已先跑過 `PlatformContentDatabaseSchema` 的舊建表步驟），Android 的雲端備份與裝置轉移規則已排除這些副本，每次升級覆寫為最近一次；複製失敗只記錄、不阻擋啟動，也不留下半成品；`<副本>.of` 記錄副本對應的各表群格式，以及複製後寫進資料庫 `shinsou_schema_versions` 的一個隨機標記（`before-upgrade-copy` 列）；同一個資料庫仍是那些格式時不重複複製（Android 背景 worker 只開一個 store 的情況），換成另一個資料庫（例如手動還原同格式的舊檔）則標記不符，會重新複製。複製目前在啟動執行緒同步進行，資料庫很大時會拖慢升級後的第一次啟動，移到背景與 R3-4 的 dispatcher 切換一起做。可重建元件遇到任何不同格式（較新或較舊）都清空重建 |
| R3-4 | 部分 | 連線設定已集中；桌面端的 `foreign_keys = ON` 實際不生效（`DesktopSqliteDriver` 每條語句開新連線，連線層級設定不保留；WAL 記在檔案裡，`synchronous` 也是連線層級，但 sqlite-jdbc 的預設值本來就是 FULL），刪除路徑都明確刪子表、不靠 cascade，所以沒有資料遺失；啟用前要先對既有資料庫跑 `PRAGMA foreign_key_check`，另案處理；把 DB 呼叫移出主執行緒的 dispatcher 切換，待第 13 節第 1 項的主執行緒探測結果再做 |
| R3-5 | 部分 | 匯出已串流；寫入端改為檔案 sink 串流列為後續 |
| R4-1 | 完成（有偏離） | 共用型別放在 `contract`、`features`、`buildinfo`，而不是計畫中的 `domain/model` |
| R4-2 | 部分 | `ShinsouAppServices` 拆成 9 個 host 介面、`BrowseCallbacks` 拆成 9 個子介面，每個至多 15 個成員（`ServiceInterfaceSizeTest` 把關）；畫面改為只接收所需的小介面尚未做 |
| R4-3 | 未做 | XL，需要改動邏輯並補測試，另案進行 |
| R4-4 | 部分 | 只做不改程式碼的檔案搬移；仍超過 1500 行：`ShinsouApp.kt`（`ShinsouAppContent`，依賴 R4-3）、`PluginManager.kt`、`PluginUiAdapter.kt`、`SqlDriverContentTransactionStore.kt`、`SyncV3Service.kt`，需要真正的類別拆分 |
| R4-5 | 完成 | iOS 外掛請求逾時由 30 秒改為 45 秒，與 JVM 一致 |
| R4-6 | 部分 | 語系檔已依語言拆檔；`I18nCoverageRatchetTest` 以 `config/i18n/` 的兩份清單（缺 zh-TW 10 項、未使用 208 項）作只減不增的護欄，只涵蓋以字面值呼叫 `text("…")` 的鍵（以變數傳入的約 77 處不受保護，清單中也混有少數非 i18n 的 `text(` 呼叫）；具型別的鍵尚未做 |
| R4-7 | 完成 | 只有「立即同步」按鈕用 `tryLock` 式等待；`syncNow` 仍會等鎖 |
| R4-8 | 完成 | Android 的 `AndroidChallengeState` 保留為行程範圍 |
| R5-1 | 未做 | 已建立 `build-logic/` 作為之後 convention plugin 的基礎 |
| R5-2 | 部分 | 三個 task 類別移到 `build-logic`（`shinsou.codegen`）；原生建置 plugin、fetch task 的輸入輸出、JavaFX/bytedeco 座標移入版本目錄、降到 300 行以下尚未做 |
| R5-3 | 待決 | 發佈策略決策，見 R5-3 |

搬移以外的行為變更（快照提交 `8680f35` 與其後），審閱時不應視為純搬移：

- `ContentFeatureRuntime.kt` 關閉時多了 `translation?.close()`，修正翻譯資源未釋放。
- `ExtensionNovelReaderShell.kt`、`ExtensionV2PublicationPane.kt` 的翻譯開關是 `8680f35` 帶入的新功能，不是從他處搬來的程式碼。
- `MangaTranslation` 的語言與文字系統猜測快取改由 `Mutex` 保護：面板（UI scope）與預取（`Dispatchers.Default`）會同時呼叫。
- 雲端翻譯的 `Retry-After` 除了秒數，也接受 HTTP 日期格式（RFC 9110 §10.2.3）。
- 外掛退避（R4-5）計「連不到主機」與「主機回的不是格式正確的 HTTP 回應」（`PluginMalformedResponseException`：狀態列、標頭、分段或長度不符）；格式正確但被傳輸層以 `require` 拒絕的回應（過大、不支援的編碼）與不送出的請求不計；退避中的請求在排隊等速率許可前就拒絕；狀態表滿時先淘汰未在封鎖中的項目。
- `ExtensionDownloadQueueV2.close()` 會等背景工作結束（最多 2 秒），工作不再把關閉當成暫停而空轉。
- 小說自動翻譯：自己啟動且讀者尚未改動時，來源語言跟著每一章重新判斷；文字太少（插圖章只有標記與網址）的章節不判斷語言；已在翻譯中的作品只有遇到文字夠多（60 個字以上）的章節才換來源語言，標題頁之類的短章不會改動它。
- 漫畫：譯圖只在仍是畫面上那一章時寫入；「不是日文」的猜測只有讀到足量文字才記住整部作品。
- 雲端翻譯：429 等待中被取消時歸還已計的用量；「額度用盡」在回應前 8000 字內比對。
- `DependencyDirectionTest` 也檢查對根套件（進入點）符號的引用；`IosSilentAudioKeepAlive` 因此移到 `download` 套件。
