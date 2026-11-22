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
- C6 Swift 層補測試與 lint；detekt 對 Android source set 啟用型別解析（iOS 無型別化 task）。

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
| C6 | 完成（iOS 型別解析除外） | detekt：`detektProduct` 加入 `detektDebugAndroid`（Android debug 變體、含型別解析，連同 common／jvmCommon 來源），baseline 為各模組 `config/detekt/<module>/baseline-debug.xml`，共 1069 筆（原本不可見的既有債，`check_baselines_shrink.sh` 以新 kind `debug` 自動納入、之後只減不增；既有 kind 數量不變）。iOS 未啟用型別解析：detekt 只為 JVM／Android 編譯產生型別化 task，Kotlin/Native target 只有 `detekt<SourceSet>SourceSet`（無型別），沿用 `detektIosMainSourceSet`。`checks.yml` 的 lint job（Linux）補裝 Android SDK。Swift：`iosApp/project.yml` 新增 `ShinsouTests`（XCTest），把可純測的邏輯抽到 `iosApp/Shinsou/Logic/`（`VolumeKeyLogic`、`SilentWav`、`JSDomLib`），測試 target 直接編入這些檔案、不載入 app，因此不需要 ShinsouKit 與 Gradle；11 個測試（音量鍵方向／門檻／安全範圍／重設判定、靜音 WAV 檔頭與大小、`JSDomLib` 在 JavaScriptCore 求值與 `Jsoup.parse`）。`ShinsouApp.swift` 的上述邏輯改呼叫這些函式，行為不變；其餘依賴 UIKit／ShinsouKit／Translation 的程式碼（`ReaderVolumeKeyMonitor`、`AppleTranslationBridge`）仍未測。macOS helper `main.swift` 是頂層腳本且被 Kotlin 測試以原始碼讀取，未拆分，只在 CI 做 `swiftc -typecheck`。SwiftLint：根目錄 `.swiftlint.yml`（預設規則，關閉 `line_length`、`file_length`、`type_body_length`、`function_body_length`、`cyclomatic_complexity`、`trailing_comma`、`statement_position`，`identifier_name` 例外 `to`／`up`／`id`／`x`／`y`），涵蓋 iosApp、桌面 helper 與 `scripts/*.swift`，`--strict` 0 違規；`checks.yml` 新增 `swift` job（macOS，以 SHA-256 釘住的 SwiftLint 0.65.1 portable 版、typecheck、XCTest） |
| C2 | 完成 | `NavigationState` 改為 section＋依 layer 排序的 `PageStack`（sealed `PageEntry`：More、Detail、Pending、LocalExtension、Recovering，每層最多一頁，寬版可多層並存），轉移改成 push／pop／replace，舊查詢由 stack 推導；Back 計數改為每個 `BackTarget` 單調遞增、開頁不再歸零，消除「重設被誤認為 Back」；Back 的處理順序與儲存格式不變；新增 `PageStackTest`（12 個）。手機返回手勢的位移重設、寬版多層並存需在裝置上確認 |
| C3 | 完成（未上機驗證） | C3-0 量測（桌面 M4 Pro；500 部、15 萬章、2 萬筆 history、5 千筆下載）：JSON 47.8 MiB，整份寫入 179 ms（`validate` 14、編碼 107、寫檔 50），啟動解碼 230 ms；翻頁的差異計算約 1–3 ms，SQL 差異寫入 0.6 ms（`SHINSOU_BENCHMARK=1` 才跑 `SnapshotPersistenceBenchmark`）。C3-1：`SnapshotStorage`、`HotStateStore`、`HotDelta` 在 `:domain` 的 `persistence` 套件（D5：`:data-content` 與 `:data-repository` 同層，不能互相依賴）；repository 改用 `SnapshotStorage`，三平台仍用 `WholeJsonSnapshotStorage`，行為不變。C3-2：`legacy_hot_state` v1 表群（`.sq`、`INSERT OR REPLACE`）與 `SqlHotStateStore`（加入宿主交易、主執行緒把關），已註冊但尚未啟用。與 6.3.2 不同：history 的 `chapter_id` 不加 UNIQUE（`validate()` 不保證一章一筆，`INSERT OR REPLACE` 會靜靜刪列）；下載佇列加 `ordinal`（清單順序不等於 `position`）；差異由 worker 對「上次寫成功的快照」計算，不需依主鍵合併 | C3-3：載入時 SQL 熱資料覆蓋 JSON；worker 拆兩路，熱資料只寫 SQL 差異（桌面大書庫每次翻頁落盤約 9 ms，原本 205 ms），冷變更、進背景、關閉與每 10 分鐘才整份寫 JSON；一次性遷移（`.before-hot-state` 副本 → 單一交易寫熱資料與 token → 標記並寫 JSON），各步驟當機重跑皆有測試；複製副本失敗就不遷移。C3-4：JSON `revision == paired_json_revision` 且 token 相符才以 SQL 為準（比 6.3 的 `≤` 嚴格），否則以 JSON 重建；整份寫入為「meta 記 pending → 寫檔 → 熱資料與配對同一交易」（取代 6.3.3 的熱先冷後，消除 6.3.8 的空窗），檔案在 pending 版時以 JSON 為準；標記只在檔案、不進記憶體快照；孤兒列載入時刪除並重新編號；緊急關閉為狀態檔旁的 `shinsou-state.whole-json`（桌面另可 `-Dshinsou.wholeJsonSnapshot=true`）。C3-5：v2 還原時熱資料加入宿主交易，失敗一起回滾。已知邊角：JSON 損毀被隔離時，只存在 SQL 的最新進度救不回 |
| C4 | 完成 | `content_annotations`、`sync_v3`、`translation_memory` 的固定查詢改由 SQLDelight（2.1.0、`sqlite-3-18` 方言、同步、convention `shinsou.sqldelight`）產生，DDL 與版本仍歸 `SchemaMigrator`（表群的 create 步驟呼叫產生的 `Schema.create`，不交給 driver、不碰 `user_version`）；動態 SQL（`notIn`、`hopsFrom`、分頁）仍手寫。A5 的註冊表改從 2_000_000_000 起編號，`:data-content:checkSqlStatementIdentifiers` 擋產生的識別碼與註冊表或彼此撞號；`GeneratedSchemaMatchesMigratorTest` 證明從每個出貨格式升級後的表與產生的 DDL 相同。產生的 database／queries 為 public（SQLDelight 不支援 internal），`:feature-translation` 的會出現在 iOS framework 標頭 |

## 6. C3／C4 設計

2026-11-22 依程式碼寫成，未實作、未跑 Gradle。C4 先做，C3 的新表直接以 `.sq` 撰寫。

### 6.1 現況（程式碼事實）

**快照（`:data-repository`）**

- 全 app 狀態是一個不可變的 `AppSnapshot`（`AppSnapshot.kt:30-53`），`schemaVersion` 固定為 1（`:23`）。`ShinsouRepository` 以 `MutableStateFlow` 持有（`ShinsouRepository.kt:58,69`）；每次修改都在 mutex 內 `copy` 出新快照，先呼叫 `mutationObserver.beforeCommit(previous, next)`，再發布、排入落盤（`mutate`，`:1039-1049`）。
- 落盤：`SnapshotPersistenceWorker` 以 CONFLATED channel 只留最新一份，安靜 350 ms、最長 2 s 合併一次（`:1117-1218`），每次落盤都**對整份快照跑 `validate()` 再整份 JSON 編碼**（`:1184`）。`validate()`（`AppSnapshot.kt:54-150`）對所有集合建 HashSet／HashMap 並逐筆檢查參照，成本與整個書庫成正比。
- 檔案：三平台都是 `shinsou-state.json`，暫存檔加原子取代。Android `filesDir`（`AndroidSnapshotPersistence.kt:17,61-84,101`），iOS `IosApplicationPaths`（`IosPersistence.kt:72-95`），桌面 `DesktopAppDirectories.dataRoot`（`DesktopAppServices.kt:367,394`）。解碼失敗就把檔案隔離、**以空狀態啟動**（`AndroidSnapshotPersistence.kt:51-58`、`IosPersistence.kt:82-90`），`validate()` 遇到不支援的 `schemaVersion` 也算解碼失敗（`AppSnapshot.kt:55`）。
- 高頻寫入：
  - 下載每完成一頁就 `setDownloadState`（`DownloadManager.kt:381-388`），每次都複製 `downloadQueue`、喚醒落盤。`DOWNLOADED` 的列不會刪，只設 `visibleInQueue = false`（`ShinsouRepository.kt:730-741`），佇列隨下載過的章節只增不減。
  - 閱讀翻頁走 `markChapterProgress`（`ReaderProgressCoordinators.kt:91,235` → `ShinsouRepository.kt:545-611`），同時複製 `chapters` 與 `histories` 兩個 list。
  - 熱欄位：`Chapter.read`／`bookmark`／`lastPageRead`（`Chapter.kt:6-21`）、整筆 `History`（`ActivityModels.kt:6-15`）、整筆 `DownloadQueueItem`（`AppStateModels.kt:256-275`）。`chapters` 通常是最大的集合（每部作品數十到上千章），因此閱讀時每 ≤2 s 就要重新驗證、編碼整個書庫。
- 誰在看快照：
  - 同步：`SyncDirtyTracker` 的觀察者在 `beforeCommit` 裡跑 `SyncDiff.diff(previous, next)`，把 dirty 記錄寫進 `sync3_dirty`（`SyncDirtyTracker.kt:162-176`）。`SyncDiff` 以 list 參照相等加對齊掃描比對 `chapters`、`histories` 等（`SyncDiff.kt:94-108,192-237,295-333`），**不比對 `downloadQueue`**。拉取時以 `SyncProjection` 產生整份新快照，再 `replaceSnapshotFromSyncIfRevision`（`SyncPuller.kt:192`）。
  - 備份：`SnapshotBackupService` 序列化整份快照（`SnapshotBackupService.kt:15-25`）；還原時 `downloadQueue`、`updates` 一律沿用本機的（`:97-98`）。v2 還原以 `commitSnapshotMutationAtomically` 包住 SQLite 交易，快照在 SQL commit 之後才發布（`PortableContentBackupV2RestoreCoordinator.kt:136-150`）。
  - ShuYue：`ShuYueCompatibilityProjectionCoordinator` 在快照層補建 chapter／history，再以 `materializeContentAuthorityProjectionIfRevision` 加標記寫回（`ShuYueCompatibilityProjectionCoordinator.kt:94,221-295`）。
  - UI：`ShinsouApp` 收集整份快照（`ShinsouApp.kt:92`），任何一頁下載進度都會讓它重組。
- 快照落盤與 SQL 之間沒有共同交易：dirty 標記立即寫進 SQLite，快照 JSON 最多晚 2 s 才寫。

**內容資料庫（`:data-content`）**

- 單一 SQLite 檔（Android `shinsou-sync-v2.db`，`AndroidContentInfrastructure.kt:40-48`；iOS `NativeSqliteDriver`，`IosContentInfrastructure.kt:91-99`；桌面自製的 `DesktopSqliteDriver`，每執行緒一條連線、`BEGIN IMMEDIATE`）。driver 的 `SqlSchema` 是凍結在 `user_version` 2 的 `PlatformContentDatabaseSchema`（`PlatformContentInfrastructure.kt:70-134`），真正的表由各表群自己建立。
- `SchemaMigrator`（`SchemaMigrator.kt:28-58,127-161`）：每個 `SchemaComponent` 是一個表群，各自在 `shinsou_schema_versions` 記錄版本，依序跑編號步驟；比程式新就丟 `SchemaTooNewException`；可重建的表群清空重建。表群：`content`（v5，`ContentTransactionSchema.kt:14-26`）、blob、`content_annotations`（v1，`SqlDriverContentAnnotationStore.kt:421-430`）、全文索引、`SyncV3Schema`、`translation_memory`（v4），在 `ContentSchemaComponents.kt:17-18` 串起來。開庫前若有表群要升級，先把資料庫檔複製成 `.before-upgrade`（`ContentDatabaseBackup.kt:24-40`）；**新增一個表群不算升級，不會觸發複製**。
- SQL 全部手寫：`ContentSqlAccess` 以表名、欄名為參數組 SQL（`ContentSqlAccess.kt:20-90`）；annotation、TM、sync3 各自直接呼叫 `driver.execute(null, ...)`。A5 的語句快取以 `SqlStatementIdentifiers` 依首次出現的順序編號 0..511（`SqlStatementIdentifiers.kt:19-41`）。
- 兩個世界的橋：`LegacyPublicationProjection`（快照 → publication，唯讀，`LegacyPublicationProjection.kt`）、`LegacyPublicationStartupMigration.migrate(snapshot)`（`composeApp/.../LegacyPublicationStartupMigration.kt:62`）、ShuYue 投影、`contentAuthorityProjectionMarkers`（與被投影的資料同在一份快照，`AppSnapshot.kt:45-50`）、`SnapshotReplacementGuard`（`ShinsouRepository.kt:1101-1107`）。
- SQLDelight：只用 2.1.0 的 `runtime`、`android-driver`、`native-driver`、`sqlite-driver`（`libs.versions.toml:21,68-71`），沒有套用 plugin，也沒有 `.sq` 檔；Kotlin 2.4.0、AGP 8.11.1，目標平台為 androidTarget、jvm("desktop")、iosArm64、iosSimulatorArm64（`KmpPlugin.kt:86-93`）。

**附帶發現（與 C3／C4 無關，但需確認）**：既有 SQL 使用 UPSERT（`ON CONFLICT ... DO UPDATE/DO NOTHING`，`ContentLedgerTables.kt:84,98,116`、`ContentOutboxTable.kt:91`、`ContentReplicaTables.kt:61`）。SQLite 3.24 才支援這個語法，Android 用系統 SQLite（`AndroidSqliteDriver` 未指定 factory），而 `minSdk` 29（Android 10）內建的是 3.22。**需要在 API 29 模擬器上確認**這些語句是否會失敗。C4 的方言設定會在編譯期擋下 `.sq` 裡的這類語法，但擋不到手寫 SQL。

### 6.2 C4：SQLDelight 程式碼產生

**原則：查詢由產生的程式碼負責，DDL 與版本仍由 `SchemaMigrator` 負責。**

- 每個表群一個 SQLDelight database（同一模組可宣告多個，各自 `srcDirs` 與 `packageName`），`.sq` 只放該表群的 `CREATE TABLE IF NOT EXISTS`／`CREATE INDEX IF NOT EXISTS` 與具名查詢。表群的 `SchemaComponent.create` 改呼叫產生的 `XxxDatabase.Schema.create(driver)`；升級步驟維持手寫（`steps`），不用 `.sqm`，`verifyMigrations = false`。必須沿用 `IF NOT EXISTS`，因為遷移器對無版本記錄的舊庫會在跑完步驟後再呼叫一次 `create`（`SchemaMigrator.kt:148`）。
- 產生的 `Schema` **絕不交給 driver 的建構子**：driver 仍用 `PlatformContentDatabaseSchema`，否則 SQLDelight 會依自己的 `version` 改寫 `user_version`，跟遷移器打架。產生的 `Schema.version` 一律忽略。
- 設定：`generateAsync = false`（driver 是同步的，切換 dispatcher 由 `ContentFoundationRuntime.transaction` 負責，`ContentFoundationRuntime.kt:304`）；方言用 `sqlite-3-18-dialect`（不得高於 Android 10 的 3.22，因此沒有 UPSERT，改用 `INSERT OR REPLACE`）。iOS（系統 SQLite 3.36 以上）與桌面（sqlite-jdbc 內附）都比這個新。
- 外掛放在 build-logic，做成 `shinsou.sqldelight` convention，只套用到有 `.sq` 的模組。產生的程式碼排除在 detekt、Kover、baseline 之外。`:feature-translation` 開了 `explicitApi()`，要確認產生的程式碼能在 explicit API 模式下編譯。
- **語句快取識別碼不能撞號**：產生的查詢帶編譯期常數的 `identifier`（雜湊值），跟 A5 註冊表的 0..511 共用同一個 driver 的快取，撞號就會執行錯的語句。做法：註冊表改從一個固定的大偏移量起編號，另在 build-logic 加 `checkSqlStatementIdentifiers` task，掃描產生的原始碼，任何識別碼落在註冊表範圍內就讓建置失敗。產生的查詢本身就有快取，不必再經註冊表。
- 交易：產生的 `Transacter` 以同一個 driver 的 `currentTransaction()` 參與巢狀交易，可以留在 `foundation.transaction {}` 裡，與手寫 SQL 同屬一個 SQLite 交易。需要寫一個測試證明 rollback 會一起回滾。
- **驗證**：不用 `verifySqlDelightMigration`（它需要 `.sqm` 與 `.db` 快照，跟手寫步驟並存只會多一份可能不一致的真相）。改加 `desktopTest` 的 `GeneratedSchemaMatchesMigratorTest`：對每個轉換過的表群，A 庫從每一個歷史版本的夾具（R3-1 已有）經 `SchemaMigrator` 升到最新，B 庫是空庫跑產生的 `Schema.create`，逐表比對 `pragma_table_info`、`pragma_index_list`／`index_info` 與 `WITHOUT ROWID`，兩邊必須相同。

**先導與範圍**

| 階段 | 內容 | 估計 |
| --- | --- | --- |
| C4-0 | Spike：在 `:data-content` 套用 plugin（2.1.0 搭 Kotlin 2.4.0），四個 target 能編譯、iOS framework 能連結；識別碼檢查 task | 0.5–1 天 |
| C4-1 | 先導 `content_annotations`：v1、單表加一個索引、約 6 個固定查詢，store 介面不變（`SqlDriverContentAnnotationStore.kt`）；跑上面的比對測試與既有 annotation 測試 | 1–2 天 |
| C4-2 | （可選）`translation_memory`（v4，已有編號步驟）、`sync3_*` 的固定查詢 | 各 1–2 天 |
| — | C3 的新表直接用 `.sq` | 算在 C3 |

**不值得做的部分**：`SqlDriverContentTransactionStore` 與 `ContentSqlAccess` 這類以表名、欄名為參數的通用存取，全文索引（動態 `IN` 清單、可重建），以及 `PlatformContentDatabaseSchema`（凍結的舊 DDL）。改寫這些只會把動態 SQL 硬塞進 `.sq`，A5 的語句快取已經拿到大部分好處。C4 到 C4-1／C4-2 就停。

### 6.3 C3：熱集合移出快照 JSON

**取捨：不從 `AppSnapshot` 型別移除欄位，只拆持久化。**

把 `chapters` 的進度欄位與 `histories` 從模型拆掉，需要改 132 處 `.chapters`、18 個讀進度欄位的檔案，以及 `SyncDiff`、`SyncProjection`、備份、ShuYue、統計。0001 的方向本來就是舊模型的 chapters 最後改由 SQL 的 `content_units` 投影產生，現在拆一次、之後再拆一次並不划算。因此：

- 記憶體裡的 `AppSnapshot` 仍是完整的讀取模型。UI、`SyncDiff`、`SyncProjection`、備份匯出、ShuYue 投影、`validate()` 的語意全部不變。
- 落盤時把快照分成兩份：
  - 冷資料：其餘欄位，以及 chapters 去掉三個熱欄位後的部分，照舊寫 `shinsou-state.json`，**只有冷資料變了才整份寫**。
  - 熱資料：三類熱資料以逐列差異寫進 SQLite，SQLite 是權威。
- 這樣去掉了熱路徑上「每 ≤2 s 驗證並編碼整個書庫」的成本；檔案格式、`schemaVersion`、備份格式都不變。記憶體裡每次修改仍要做 O(章節數) 的參照複製，這部分不處理（見 6.3.8）。

#### 6.3.1 要搬的集合

| 集合 | 搬 | 理由 |
| --- | --- | --- |
| `downloadQueue` | 是 | 每頁寫一次；`DOWNLOADED` 列只增不減；不同步，還原時也不採用備份裡的版本（`SnapshotBackupService.kt:98`） |
| `histories` | 是 | 每次翻頁都寫 |
| `Chapter.read`／`bookmark`／`lastPageRead` | 是（只搬這三欄） | 每次翻頁都寫；只要它們還在 JSON 裡，閱讀時就仍須整份寫入 |
| `updates`、`mangas`、`categories`、其他 | 否 | 只在書庫更新、使用者編輯時批次變動 |

#### 6.3.2 資料表

放在**內容資料庫**（共用 driver），不另開資料庫：`sync3_dirty` 也在這裡，熱資料列與 dirty 標記可以放進同一個交易；0001 也不允許另一套無法與 sync outbox 原子協調的權威。

新增表群 `legacy_hot_state` v1，以 `.sq` 撰寫：

- `legacy_chapter_progress(chapter_id INTEGER PRIMARY KEY, read INTEGER NOT NULL, bookmark INTEGER NOT NULL, last_page_read INTEGER NOT NULL)`：只存與預設值不同的章節。
- `legacy_history(history_id INTEGER PRIMARY KEY, chapter_id INTEGER NOT NULL UNIQUE, ordinal INTEGER NOT NULL, last_read INTEGER NOT NULL, time_read INTEGER NOT NULL, last_locator_json TEXT, last_page_count INTEGER)`
- `legacy_download_queue(id TEXT PRIMARY KEY, manga_id, chapter_id, state TEXT, progress REAL, downloaded_pages, total_pages, position, queued_at, updated_at, destination_path, error_message, visible INTEGER)`（`WITHOUT ROWID`）
- `legacy_hot_state_meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)`：`paired_json_revision`、`token`。

這些表不加外鍵，因為 manga／chapter 在 JSON 裡。載入時忽略找不到對應 chapter 的列；chapter 被刪除時，差異計算會產生刪除。`ordinal` 讓 histories 的 list 順序能無損還原（`SyncDiff` 的對齊快速路徑與備份輸出都依賴順序）。

#### 6.3.3 Repository API

- 公開 API 不變：`snapshot`、`currentSnapshot`、各 mutation、`flushPersistence`、`closePersistence`。
- 建構子的 `persist: (String) -> Unit` 換成 `SnapshotStorage` 埠：
  - `writeCold(json, revision)`
  - `writeHot(delta)`
  - `loadHot(): HotState`
  - `pairedRevision`
- 埠的介面放在 `:data-repository`，只用 `:domain` 型別；SQL 實作放在 `:data-content`；JSON 實作留在各平台；測試用記憶體實作。`:data-repository` 仍不依賴 SQL。若 `moduleDependenciesPointDown` 不允許 `:data-content` 看到這個介面，就把介面移到 `:domain`，請決定（D5）。
- `mutate` 在 mutex 內計算 `HotDelta`（previous／next 的三類熱資料，依參照相等走快速路徑，作法同 `SyncDiff.diffList`），並判斷冷資料是否改變。冷資料沒變就不排 JSON 寫入。
- 寫入端：沿用 latest-wins 的合併 worker（350 ms／2 s）。差異依主鍵合併（同鍵只留最新），在 IO dispatcher 上一次交易寫完，`ContentSqlThreadGuard` 照樣把關。當機最多遺失 2 s 的進度，與現在相同。下載進度在重啟時本來就會依完成清單重算（`DownloadManager.kt:75-110`）。
- `flushPersistence`、`closePersistence` 要先寫熱資料、再寫冷資料，兩者都完成才算完成。生命週期 flush（進背景、關閉）一律整份寫 JSON，含熱欄位（用途見 6.3.6）。
- 整份 JSON 寫入時才 `validate()`。熱資料寫入只做逐列檢查（repository 現有的 `validateDownload`、進度範圍檢查）。

#### 6.3.4 同步、備份、ShuYue、驗證

- **同步**：`SyncDiff` 仍比對記憶體裡的 previous／next 快照，產生 dirty 記錄的方式完全不變。`markAll`、`SyncProjection`、`SyncPuller` 都操作完整快照，不需要修改。
- **備份匯出**：從記憶體裡的完整快照匯出，格式不變，舊版 app 與 ShuYue 相容性不受影響。
- **還原**：`replaceSnapshot*` 產生的是整批差異。`commitSnapshotMutationAtomically` 可以讓熱資料列加入宿主的 SQLite 交易，比現在更原子（列為可選的 C3-5）。
- **ShuYue 投影**：仍在快照層運作，只是新建的 history 會以差異寫入 SQL。熱資料表依主鍵 `INSERT OR REPLACE`，具冪等性，所以投影在 JSON 標記落盤前當機、重跑時，不會產生重複。
- **`validate()`**：載入時先套上熱資料再驗證一次，與現在相同；每次落盤的全量驗證只剩冷資料寫入時才跑。

#### 6.3.5 一次性遷移

首次以新版本啟動時，SQL 沒有 `token`：

1. 把 `shinsou-state.json` 複製成 `shinsou-state.json.before-hot-state`。只複製一次；Android 的雲端備份規則要排除它，與 `.before-upgrade` 相同。
2. 在一個 SQL 交易裡：寫入快照中所有熱資料，並寫入 meta 的 `paired_json_revision = snapshot.revision` 與隨機 `token`。
3. 在 `contentAuthorityProjectionMarkers` 加上 `hot-state-sql:v1:<token>`，整份寫 JSON。

每一步都可重跑：第 2 步完成、第 3 步前當機，下次啟動時 meta 與 JSON 版本相同，資料也一致。**不得調高 `APP_SNAPSHOT_SCHEMA_VERSION`**：舊版 `validate()` 會判定解碼失敗，隔離檔案並以**空書庫**啟動（`AppSnapshot.kt:55`、`AndroidSnapshotPersistence.kt:51-58`），之後的寫入會蓋掉資料。新標記放在舊版已認得的欄位裡，舊版會原樣保留。

#### 6.3.6 載入規則與回退

- **一般載入**：JSON 的 `revision` ≤ `paired_json_revision` 時，SQL 的熱資料覆蓋 JSON 的熱欄位。每次整份寫 JSON 前，先把 `paired_json_revision` 寫成即將寫入的版本；JSON 寫入失敗時，磁碟上的版本號比較小，仍判定 SQL 為準。
- **降版**：舊版 app 讀到的 JSON 含有最近一次整份寫入（冷資料變更或生命週期 flush）時的熱欄位，只會遺失那之後的進度與下載狀態，不會變成空書庫。舊版不認得 `legacy_hot_state` 表群，`tooNew` 只檢查它認得的表群（`SchemaMigrator.kt:157-161`），所以會直接略過這些表。
- **降版後再升版**：舊版寫 JSON 時會遞增 `revision`，使 JSON 的 `revision` > `paired_json_revision`。這時以 JSON 為準，用它重建熱資料表，並記錄一筆日誌。
- **JSON 有標記，但 SQL 沒有 `token` 或 `token` 不符**（資料庫遺失，或還原了另一個檔案）：以 JSON 為準重新寫入，等同重做一次遷移。
- **緊急關閉**：保留一個旗標，讓 worker 回到「每次都整份寫 JSON」。載入規則不變，關閉後也能安全讀回。

#### 6.3.7 階段

每一步都可以單獨驗證，各自 commit。

| 階段 | 內容 | 測試 | 估計 |
| --- | --- | --- | --- |
| C3-0 | 量測：合成大書庫（例如 500 部、15 萬章、2 萬筆 history、5 千筆下載），在桌面與一台中階 Android 上量 `validate`、編碼、寫檔的時間與 JSON 大小 | 基準測試（不進 CI 門檻） | 0.5–1 天 |
| C3-1 | `SnapshotStorage` 埠、`HotDelta` 計算、冷資料變更判斷；先用一個「全部寫 JSON」的實作接上，行為不變 | 差異的性質測試：隨機修改序列套用差異後，熱資料必須等於 next 的熱資料；冷資料變更判斷的正反例 | 1.5–2 天 |
| C3-2 | `legacy_hot_state` 表群（`.sq`）、SQL 實作、`ContentSchemaComponents` 註冊 | 往返無損（含 `ordinal`）、冪等性、rollback、產生的 schema 與遷移器比對 | 1–2 天 |
| C3-3 | 切換：載入時套上熱資料、worker 拆成兩路、flush 語意、一次性遷移與 `.before-hot-state` 副本 | 遷移各步驟之間當機重跑；`encode(load(persist(s))) == s`；閱讀與下載期間不寫 JSON | 2–3 天 |
| C3-4 | 降版與再升版規則、token 不符、孤兒列 | 以舊版 JSON 夾具模擬「舊版寫入後再升版」；資料庫遺失；chapter 被刪後的列 | 1–2 天 |
| C3-5 | （可選）`commitSnapshotMutationAtomically` 讓熱資料加入宿主交易 | v2 還原中途失敗時，熱資料表必須一起回滾 | 1 天 |

合計約 7–11 天，不含 C4。

#### 6.3.8 風險與不做的部分

- **兩個儲存之間不是同一個交易**：熱資料在 SQL，冷資料在 JSON。刪除作品時，SQL 會先刪掉列，JSON 晚一點才寫。中間當機的話，載入後作品還在但進度不見，只有那一部作品受影響。可在 `deleteManga`／`deleteChapter` 之後立即 flush 冷資料，把這段時間縮到最短。
- **記憶體成本沒有降低**：每次修改仍 O(章節數) 複製 list 參照，`ShinsouApp` 仍因下載進度而重組。若 C3-0 顯示這部分也有問題，另立一項，把 `downloadQueue` 改成獨立的 `StateFlow`；這需要改 UI 與 `DownloadManager` 約 30 處讀取點。
- **C3-0 是門檻**：若在合理的大書庫上，整份寫入在中階 Android 只要數十毫秒、JSON 在數 MB 以內，**C3 不值得做**。改為只調整落盤節奏即可，例如閱讀中把最長延遲 2 s 放寬，並在翻章、進背景時 flush。
- **雙重真相**：JSON 裡的熱欄位只是「最近一次整份寫入」的投影，SQL 才是權威，由 6.3.6 的版本規則決定以誰為準。這比現在多了一條規則，換到的是「降版不會變成空書庫」。

#### 6.3.9 待確認的決策

**使用者決定（2026-11-22）**：D1 支援降版（只保留最近一次整份寫入時的進度）；D2 不以量測決定，C3 全做；D4 做 C4-0、C4-1 與 C4-2（`translation_memory`、`sync3_*`）；D6 先在 API 29 模擬器驗證，屬實就改寫 UPSERT 並加測試擋住新的 UPSERT。D3、D5 依建議。

- **D1 降版支援**：建議「可降版，但只保留最近一次整份寫入時的進度」（6.3.6）。另一個選項是不支援降版，JSON 只寫冷資料，舊版會看到全部未讀、下載佇列是空的。
- **D2 C3 是否實作，取決於 C3-0**：門檻建議為中階 Android 上整份寫入超過 100 ms，或 JSON 超過 10 MB。
- **D3 是否在 C3 一起把 `downloadQueue` 改成獨立 flow**：建議先不要，見 6.3.8。
- **D4 C4 範圍**：建議只做 C4-0、C4-1，C4-2 可選；動態 SQL 不轉換。
- **D5 `SnapshotStorage` 介面放 `:data-repository` 還是 `:domain`**：依 `moduleDependenciesPointDown` 是否允許決定。
- **D6 Android 10 的 UPSERT 問題**：獨立處理，建議盡快在 API 29 模擬器上驗證（6.1 附帶發現）。
