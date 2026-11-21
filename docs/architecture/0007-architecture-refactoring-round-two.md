# 0007 — 架構重構第二輪

- 狀態：實施中（進度見第 5 節）
- 日期：2026-11-22
- 範圍：只處理架構、模組邊界與程式碼品質；不改變功能行為（資料搬移的階段例外，見 C3）
- 依據：2026-11-21 Gemini 4 Neon 的唯讀架構評估，2026-11-22 逐項對照程式碼複查
- 前一輪：[0006](0006-code-quality-refactoring-plan.md)
- 驗收：每個階段跑 0006 的完整驗證（三平台編譯、桌面與 Android 測試、`detektProduct`、Kover 門檻、`check_baselines_shrink.sh`），通過後在本機 commit；全部完成後交給 Gemini 4 Neon 與 Grok 4.7 複查

## 1. 複查結果

評估裡的具體事實都對照過程式碼，以下成立：

| 項目 | 證據 |
|---|---|
| 4 個沒用到的依賴 | `domain` → Ktor、`data-repository` → `:core`、`feature-translation` → `:data-repository`、`composeApp` → jsoup，對應目錄內 0 次引用 |
| 記憶體版交易庫在產品碼 | `InMemorySharedContentTransactionStore`（`data-content/commonMain`）只被三個模組的測試使用 |
| `:domain` 夾帶特定來源演算法 | `JmImageDescrambler` 寫死來源 id，只有 `:feature-plugin` 的 `SourceContracts` 使用 |
| `:domain` 為一個鎖依賴整個 `:core` | 只有 `ContentRights.kt` 用到 `SynchronousLock` |
| `:core` 以 `api` 暴露 Coil、Ktor | `core/build.gradle.kts` 第 17～20 行 |
| SQL 沒有語句快取 | `ContentSqlAccess` 的 `executeQuery`／`execute` 一律傳 `identifier = null` |
| 介面大小測試可被繞過 | `ServiceInterfaceSizeTest` 只數介面本體的成員，不算繼承 |
| 組裝根過大 | `ShinsouComposition.kt` 1,249 行 |
| 未啟用 `explicitApi()` | 全 repo 無設定 |
| 原生建置 | `NativeLibrariesPlugin` 寫死 `-j 8`，fetch／CMake 的 `Exec` task 沒有輸入輸出 |

## 2. A｜低風險整理

- A1 移除 4 個沒用到的依賴。
- A2 記憶體版交易庫移到測試專用模組，產品產物不再包含它。
- A3 `JmImageDescrambler` 移到 `:feature-plugin`，`:domain` 只留通用的圖片轉換契約。
- A4 介面大小測試連繼承的成員一起算；超過上限的介面再拆。
- A5 `ContentSqlAccess` 為固定 SQL 帶穩定的語句識別，啟用 SQLDelight driver 的 prepared statement 快取。
- A6 原生建置的平行數依處理器數；fetch 與 CMake task 宣告輸入輸出，可判斷 `UP-TO-DATE`。

## 3. B｜中等重構

- B1 `:core` 拆成 `:core`（併發、日誌、加密、檔案）與 `:core-network`（Ktor、Coil、AVIF），只需要基礎能力的模組不再編譯 Coil／Ktor；`KeyValueStore` 正名為通用名稱。
- B2 `:feature-plugin`、`:feature-library`、`:data-content` 各自提供組裝入口，`ShinsouComposition` 只串接子系統；跨模組不再需要的類別收回 `internal`。
- B3 `:core`、`:domain`、`:feature-translation` 啟用 `explicitApi()`。
- B4 `ShinsouAppServices` 不再直接帶具體實作類別，改以窄介面注入。

## 4. C｜大型重構

- C1 `:ui` 拆成設計系統、外殼與各功能的 UI 模組。
- C2 導航由旗標與計數器改為明確的目的地堆疊。
- C3 `AppSnapshot` 中頻繁變動的集合（閱讀歷史、章節進度、下載佇列）搬進 SQLite，消除雙重真相來源；需要一次性資料遷移與回退路徑。
- C4 固定 schema 的資料表改由 SQLDelight `.sq` 在編譯期產生存取碼。
- C5 拆開 `MangaTranslation`；消化 detekt baseline 的高複雜度方法，`LongMethod` 門檻逐步下修。
- C6 Swift 層補測試與 lint；detekt 對 Android／iOS source set 啟用型別解析。

## 5. 實施進度

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| A1 | 完成 | |
| A2 | 完成（有偏離） | 搬到獨立測試模組需要公開 `:data-content` 約 30 個 internal 成員，反而擴大 API；改為 `internal`，其他模組的測試只經 `DataContentTestAccess.inMemoryTransactionStore` 取得。它仍編進產品產物（Android 由 R8 移除） |
| A3 | 完成 | 圖片轉換契約留在 `:domain`（`ReaderImageTransform.kt`）；繪製邊界的測試留在 `:feature-reader` |
| A4 | 完成 | 只有 `ShinsouAppServices`、`BrowseCallbacks` 可超過上限；仍直接引用它們的 15 個檔案列在測試內，只減不增（B4 收斂） |
| A5 | 完成（範圍有限） | `ContentSqlAccess` 與 `SqlDriverPortableAliasResolver` 的語句帶識別碼（每個行程最多 512 段 SQL）；DDL／PRAGMA 不快取；其餘約 60 處多行 SQL 未改 |
| A6 | 完成 | `-j` 依處理器數；fetch task 以 `third_party/.fetched` 為輸出。CMake configure／build task 仍每次執行（cmake 本身是增量的） |
| B1 | 完成 | `:core-network` 承接 `network` 套件（Ktor、Coil、AVIF）；`:domain`、`:data-content`、`:data-repository`、`:feature-translation` 不再編譯 Ktor／Coil。`PluginKeyValueStore` 改名 `KeyValueStore`（平台實作類別名稱未改） |
| B2 | 完成（範圍有限） | 組裝入口：`:feature-plugin` 的 `PluginSubsystem`（`create` 建外掛圖，`openBrowse` 在 sync 之後建 browse 外觀）、`:data-content` 的 `ContentBackgroundWork`（outbox 丟棄、blob 回收、衍生資料重整）、`:feature-library` 的 `TrackingSubsystem`。代理金鑰、帳號套件清單與同步來源描述移入 `PluginSubsystem`，備份附件候選移為 `BackupV2AttachmentCandidate.allOf`。`:feature-plugin` 頂層公開宣告 382 → 224；`:app` 的 `commonTest` 仍直接組裝外掛內部（`RepositoryPluginCoordinatorTest`、`RepositoryPluginReaderParityTest`），它們用到的類別與其簽名牽涉的型別仍為 public |
| B3 | 完成 | `:core`、`:core-network`、`:domain`、`:feature-translation` 啟用 `explicitApi()`：267 個宣告標明 `public`（其他模組有用到），13 個改為 `internal` |
| B4 | 完成 | `ShinsouAppServices` 的成員改為窄介面：`AppUpdateSource`（`:feature-library`）、`MangaTranslationService`（`:feature-translation`，`AcceleratorState` 移入此介面）、`TrackingService`（`:feature-library`）、`SyncClient`＝`SyncSharing`＋`SyncReviews`＋帳號／作品雜湊（`:feature-sync`）；具體類別實作它們，平台 `*AppServices` 仍以具體型別（協變）覆寫。`PortableContentBackupV2UiController` 本來就是 `:ui` 介面，未改。`UMBRELLA_USERS` 15 → 9：`LibraryContinueReading`、`ReaderHost`、`TypedLocalContent`、`PluginReviewCoordinator`、`TitleNavigationController` 改收子介面（同一物件需要多個子介面時用型別參數的 `where` 交集，例如 `PluginReviewCoordinator<B> where B : SourceCatalog, B : ExtensionManagement, B : ExtensionTrust`，避免 detekt `LongParameterList`），`ExtensionContentConsumerV2` 只是註解提到。留下的是外殼（`ShinsouApp`、`ShinsouAppState`、`ShinsouAppPanes`——後者把 `browse` 整個交給 `BrowseScreen`）、`BrowseScreen`、平台實作與外掛轉接器 |
| C5 | 完成（範圍有限） | `MangaTranslation` 只剩外觀（975→325 行），實作 `MangaTranslationService`：資源與加速器下載歸 `MangaResourceManager`，共用管線的持有／釋放（`holding` 在 `NonCancellable` 裡放回、`whileIdle`、`release`）歸 `MangaPipelines`，語言探測歸 `MangaLanguageProbes`；一章的流程是 `MangaChapterRun`（整章與分段兩種流程），讀頁是 `ChapterReading`，繪製與交頁是 `ChapterDrawing`，已畫章節的重用是 `DrawnChapters`，階段之間改傳具名型別（`PageToDetect`、`DetectedPage`、`PageToDraw`），每頁的槽位收在 `ChapterPages`；`pipeline.chapters` 鎖、交頁順序、快取鍵都不變。章節流程經 `ChapterStages` 介面（產品用 `PipelineStages` 包 `MangaPipeline`），`MangaChapterRunTest` 以假階段測兩種流程。`LongMethod` 門檻 150 → 100，新超過的 52 個方法全部拆開，沒有加進 baseline；baseline（三種 source set 合計）`CyclomaticComplexMethod` 216 → 174、`ComplexCondition` 88 → 73、`NestedBlockDepth` 13 → 10、`ReturnFromFinally` 1 → 0；`LongMethod` 仍 37（都超過 150 行，未處理）。補了 `ExtensionRepositoryUrlValidationTest`、`ShuYueValidationPredicatesTest`、`MangaRefactorCharacterizationTest`（版面與 eraser 的逐位元特性測試） |
