# ADR-0001：統一出版品、內容與安全遷移基礎

- 狀態：Accepted
- 日期：2026-08-21
- 適用專案：Shinsou X KMP、Shinsou extension repository、ShuYue migration
- 決策範圍：M0 contracts/foundation；後續 reader、search、TTS、annotation、DRM 與 blob sync 必須遵守

## 背景

Shinsou X 現有領域模型、extension contract 與 Reader 以漫畫為中心：`Manga`、`Chapter`、
`SManga`、`SChapter` 與 `Page` 都假設章節的主要內容是圖片序列。ShuYue 則以 `Book`、
內嵌全文的 `Chapter.text` 與字元 offset 為中心，並有另一套字串 source ID 與小說 plugin
contract。

兩邊都已有值得保留的能力，但任何一邊的現有模型都不能直接成為合併後的權威模型：

- 把小說正文加進 `Manga`／`Chapter` 會讓 image/text/EPUB 三種生命週期混在同一 record，
  並使大型正文進入 `AppSnapshot`、backup 與 sync checkpoint。
- 把漫畫降成 ShuYue 的 `ReaderContentBlock.Image` 會丟失 page、spread、viewer mode、下載
  manifest 與 image transform 等既有語義。
- Shinsou X 的 source ID 是 `Long`；ShuYue 已發布的 source ID 是
  `zh.wenku8`、`zh.wenku8.api` 等 opaque string。把字串 hash 成 `Long` 會引入碰撞，且無法
  建立可稽核的跨裝置 identity。
- Shinsou X extension manifest 可列多個 sources，但目前 executable runtime 只使用第一個
  source。ShuYue 與 Shinsou 的 v1 script 也使用互不相容的 content methods。
- 現有 Cloudflare v2 sync 有可重用的 E2EE event/checkpoint/control plane，但明確排除 Local、
  download 與 EPUB bytes；R2 目前只有 checkpoint plane。
- ShuYue backup v1 把書籍正文、plugin script、帳密與 cookies 放在同一份未加密 JSON。
  遷移必須保留可合法保留的資料，但不能自動執行 script 或把明文 secret 帶進新的 portable
  state。

本 ADR 建立後續功能共同依賴的 identity、內容、locator、rights、storage 與遷移邊界。

## 決策摘要

1. 新權威領域採用 `Publication -> Acquisition -> PublicationUnit -> ContentManifest`。
2. Publication 的本機／同步 ID 與來源 identity 分離；來源使用 opaque、版本化的 string key。
3. 內容使用可擴充的 tagged representations；媒體種類屬於 representation，不是全域
   `isNovel`／`isManga` flag。
4. 正文與 EPUB resources 存入 immutable app-private blob store；metadata、關聯、rights、
   locators 與 blob refs 存入同一 shared SQLite transaction boundary。
5. 閱讀進度、搜尋結果、TTS 游標與註記共用同一 `ReadingLocator`／`ReadingRange` contract。
6. 所有內容操作由 host-side rights grant 控制；plugin 只能提供 hints，不能自行授權。
7. Extension v2 的 package runtime 與 source facade 分離，必須真正支援一 package 多 source，
   以及同 source／同 unit 多種 content representations。
8. Cloudflare event/checkpoint plane 只同步 metadata 與 blob refs；正文另走可續傳、分塊、E2EE
   的 R2 body plane。M0 不改現行 sync protocol。
9. ShuYue backup/plugin migration 一律先 staging、validation、report、quarantine，再由使用者
   明確接受敏感項目；不得在 decode/import 階段執行內嵌 JavaScript。
10. 現有 `Manga`／`Chapter`、v1 extensions、AppSnapshot schema v1 與 sync schema v1 以
    compatibility projection／adapter 漸進遷移，不做一次性破壞式改寫。

## Identity contract

### Local authority 與 remote identity 分離

`PublicationId`、`AcquisitionId`、`PublicationUnitId`、`ContentManifestId` 與 `AnnotationId`
是 app 配置、可攜且可同步的 UUID。它們不是本機資料庫 row ID，也不由 title、list index、
Kotlin `hashCode()` 或 URL hash 產生。

來源 identity 使用下列概念 contract：

```text
SourceKey(
  contractVersion,
  packageId,
  sourceId,          // opaque string
  legacyLongId?      // only for lossless v1 projection
)

RemoteEntityKey(
  keyVersion,
  sourceKey,
  entityKind,
  canonicalId
)
```

必要 invariants：

- `packageId` 與 `sourceId` 必須保留發布者提供的穩定值，不能用顯示名稱替代。
- `legacyLongId` 只供 v1 adapter／migration round-trip，不能重新成為新模型權威。
- extension v2 提供 canonical ID 時優先使用；否則使用明確版本的 conservative URL
  normalization。規則改變必須提高 `keyVersion` 並產生可稽核 remap。
- repository mirror URL 不屬於 content identity。Package trust identity 由 package ID、簽章／
  TOFU evidence 與 source ID 共同判定。
- 兩個來源的同名作品不得依 title／author 猜測為同一 Publication。只有強識別碼或使用者
  明確 link/merge 才能讓多個 Acquisitions 掛在同一 Publication。
- migration 必須保存 legacy ID -> 新 UUID alias，重跑同一輸入時得到同一結果。

## 統一領域模型

### Publication

`Publication` 是書庫中的作品／使用者管理單位，保存與呈現媒體無關的 metadata、書庫狀態與
portable UUID。它不直接保存來源 URL、正文、圖片頁面或 DRM key。

### Acquisition

`Acquisition` 表示 Publication 的一個合法取得版本，例如：

- 本地 TXT import；
- 本地完整 EPUB package；
- 某 extension source 的漫畫版本；
- 某 extension source 的小說版本；
- 同作品的另一來源／語言／版本。

Acquisition 持有 origin/source binding、remote identity、rights grant reference、內容 revision
與可用性。預設一個舊 `Manga` 或 ShuYue `Book` 各自映射成一個 Publication 加一個
Acquisition；不因名稱相似自動合併。

### PublicationUnit

`PublicationUnit` 是章、話、卷內 spine item 或其他可導航內容單位。Identity 與排序分開：

- Unit ID 不得等於 list index；
- `sourceOrder`／`ordinal` 只決定顯示及導覽順序；
- remote canonical ID 或 legacy URL 放在 acquisition binding；
- 同 unit 可有零到多個 immutable `ContentManifest` revisions。

### ContentManifest

`ContentManifest` 描述一個 immutable content revision 及其 resources。它只含 metadata 與
`BlobRef`，不 inline 大型 bytes。至少支援：

```text
ImageSequence(
  ordered stable resource IDs,
  page/spread/layout metadata,
  optional image transforms
)

PlainTextDocument(
  canonical UTF-8 resource,
  source encoding metadata,
  semantic block map
)

EpubPackage(
  original package blob,
  OCF/OPF metadata,
  manifest/spine/nav resource graph,
  rendition metadata,
  encryption descriptors
)

EpubSpineDocument(
  package ID,
  href,
  media type,
  spine position,
  layout/properties
)
```

媒體 profile 是 representations 的 materialized facet。不得以 Publication 上的一個
`isNovel`／`isManga` boolean 阻止同 source 或同 unit 同時提供文字、圖片或 EPUB
representation。

## Extension v2 content payload

Extension v2 的 content result 是 tagged union，不再假設只有 `String` 或 `Page[]`：

```text
InlineTextPayload       // bounded, explicit charset/media type/base URI
HostFetchResource       // host network stack downloads to BlobStore
ChunkedTextPayload      // cursor-based bounded chunks
ImageSequencePayload    // ordered resource requests and transforms
EpubPackagePayload      // package/resource acquisition plan
```

共同規則：

- 所有 payload 都有 contract/schema version、media type、size policy 與 stable representation ID。
- Inline payload 必須有硬上限；超限內容改走 host-managed fetch/chunk stream。
- Plugin 不能回傳可任意讀取的本機 path，也不能自行寫 app-private storage。
- Host network policy、cookie、credential、redirect、body limit 與 cancellation 仍是唯一 transport
  authority。
- v1 Shinsou manga script 與 legacy ShuYue novel script 必須透過隔離 adapter 轉為 v2 payload；
  新 runtime 不得再使用 `manifest.sources.firstOrNull()` 作 executable source contract。

## Storage 與 blob contract

### Shared SQLite authority

Publication metadata、acquisition、units、manifests、blob refs、reading progress、annotations、
rights、migration aliases 與 sync draft/outbox 必須能共享同一 SQLite transaction boundary。
不得再建立一份與 sync outbox 無法原子協調的第二套 JSON authority。

現行 `AppSnapshot` 在遷移期是 compatibility/materialized projection 與 portable DTO，不是大型
正文容器。正文、EPUB archive、stylesheets、fonts、images、plugin package bytes 與全文索引
不得新增為 `AppSnapshot` inline fields。

### Immutable ContentBlobStore

概念 contract：

```text
BlobRef(
  blobId,              // opaque/versioned
  digestAlgorithm,
  plaintextDigest,     // local/encrypted metadata; never an arbitrary path
  byteSize,
  mediaType
)
```

必要 lifecycle：

1. 將來源 bytes 寫入 staging file，過程中計算 digest 並套用 size/resource limits。
2. fsync／close 後核對 declared size 與 digest。
3. 以 immutable、opaque 名稱發布 blob。
4. 在 SQLite transaction 中建立 manifest/ref 與 migration ledger。
5. Crash recovery 只能看見完整 committed blob，或可安全 GC 的 orphan；不得留下指向部分檔案
   的 committed ref。

Filesystem path、security-scoped URL、content-provider URI 與 platform handle 都是 device-local
adapter state，不進 BlobRef、backup 或 sync event。

全文索引、EPUB DOM/block map cache、縮圖與 TTS segmentation 是可由 content revision 重建的
derived data。它們不是 backup/sync authority。

## Reading locator contract

Reader progress、Continue Reading、search hit、TTS segment 與 annotation 必須共用同一版本化
locator：

```text
ReadingLocator(
  publicationId,
  acquisitionId,
  unitId,
  contentRevision,
  resourceIdOrHref,
  locations,
  textQuoteFallback?
)
```

`locations` 是 tagged union：

- Image：stable page resource ID、page index（僅 materialized hint）與 0..1 intra-page fraction；
- Plain text：resource/block ID、明確定義的 UTF-16 offset、progression；
- EPUB：resource href、EPUB CFI、progression，必要時保留 DOM/text quote fallback。

`ReadingRange` 由 start/end locator 組成，並可帶 W3C-style TextQuote selector
（exact/prefix/suffix）供內容 revision 更新後 re-anchor。

Offset unit 必須固定並進入 schema；byte offset、Unicode code point 與 UTF-16 code unit 不得
混用。ShuYue v1 `charOffset` 是 migration input，不是新 locator 的唯一 anchor。

## Rights 與合法 DRM 邊界

Rights 是 host 強制執行的資料契約，不是 UI 文案：

```text
RightsGrant(
  grantId,
  provenance,
  protectionScheme,
  validFrom/validUntil,
  allowedOperations,
  constraints,
  providerEvidenceRef?
)
```

最小 operation 集合：

```text
DISPLAY
OFFLINE_STORE
SYNC_BLOB
EXPORT
COPY
PRINT
TTS
SEARCH_INDEX
ANNOTATE
```

規則：

- User-imported、unprotected content 可依明確 local-import policy 建立 grant。
- Plugin 只能回 rights hints 或取得 license 所需 metadata；不能自行把 operation 從 deny 提升成
  allow。
- DRM/provider adapter 必須可 inspect、acquire/open、renew/expire、return/revoke，且 private
  license material 進 strict platform secret store。
- 不支援的 `encryption.xml`／protection scheme 必須 fail closed 並顯示可診斷原因；不得 strip、
  繞過或把 app lock/secure screen 稱為 DRM。
- Device-bound DRM key、帳密、cookie 與 OAuth token 不進 portable backup、event、checkpoint
  或 body blob。目的裝置應合法重新授權。
- Blob sync、export、search index、TTS 與 annotation quote storage 都必須查詢當下 grant；grant
  過期／撤銷後清理不再允許的 derived data。

本 ADR 定義合法 provider framework，不授權任何 DRM 規避。實際 provider 上線前另需完成
license、distribution、certification 與真實帳號／內容 smoke gate。

## EPUB 與完整 CSS 的架構含義

「EPUB 支援」不能以抽圖片或 strip HTML/CSS 代替。新 importer 必須：

- 以 XML parser 讀 OCF container、OPF、manifest、spine、nav/NCX 與 encryption metadata；
- 保存原始 package/resource graph、relative URLs、stylesheets、fonts、images 與 rendition
  properties；
- 對 archive path、entry count、expanded size、compression ratio、external network 與 scripted
  content 套用安全政策；
- 透過 browser-grade document renderer 呈現 publisher CSS，再以明確 user-style layer 套用使用者
  設定；
- 讓 renderer 與 semantic extractor 都產生同一 resource/locator identity。

平台 renderer 的具體 library 需由 spike 鎖定，但 acceptance 不變：Android 使用 WebView 級
engine、iOS 使用 WKWebView 級 engine，Desktop 必須採可封裝於 macOS／Windows 的 browser-grade
engine。純 Compose fixed-char pagination 不能宣稱完整 EPUB CSS。

ShuYue backup 只保存已 strip 的 chapter text，沒有原始 XHTML、CSS、font 或 image resources。
Migration 必須標示 `legacyFlattened`；只有重新匯入原 EPUB 才能恢復完整 CSS。不得虛構已不存在
的資源或宣稱 lossless EPUB migration。

## Sync metadata plane 與 body plane

現行 event/checkpoint/control plane 繼續作為 metadata authority；其 E2EE、HLC、tombstone、
checkpoint recovery、key rotation 與 tenant isolation 不得退回 latest-snapshot sync。

後續 body plane 使用獨立 R2 namespace 與 API：

```text
workspaces/{workspaceId}/blobs/v1/{blobId}/{chunkIndex}-{ciphertextHash}.bin
```

必要 invariants：

- body 先在 client 分塊、壓縮（適用時）並 AEAD 加密；server 不得取得 plaintext digest、正文、
  resource name 或 blob DEK。
- 上傳 session 具 byte reservation、hard cap、idempotent chunk receipt、resume 與 exact manifest
  commit。
- 每個 blob 使用獨立 DEK；workspace epoch rotation 只 re-wrap live blob DEKs，不重寫所有大型
  ciphertext。舊 epoch 淘汰前必須有 current-epoch envelope 與 stable checkpoint evidence。
- Remote metadata 不得宣告 blob available，除非 exact blob manifest 已 commit。
- Checkpoint 保存 blob refs/envelopes/tombstones，不包含 body bytes。
- GC 必須等待 reference tombstone、retained checkpoint/recovery boundary、ack 與 safety window；
  不可只依單一 client 宣告立即刪除。
- `SYNC_BLOB` rights deny 時不得上傳；新裝置改走合法 reacquisition／DRM reauthorization。

M0 不建立上述 endpoint、migration 或新 wire mutation。當 body plane 開始實作時，
`CROSS_PLATFORM_SYNC_ARCHITECTURE.md` 中「R2 只保存 checkpoint」與「Local/EPUB bytes 預設
不同步」必須以版本化 scope amendment 更新，並提高 schema/min-reader/min-writer gate。

## ShuYue backup/plugin migration safety

Migration pipeline 固定為：

```text
bounded read
-> strict versioned decode
-> structural validation
-> staging records
-> user-visible validation/import report
-> explicit selection/secret consent
-> transactional publication/blob import
-> plugin quarantine/review
-> migration ledger commit
```

必要規則：

- ShuYue backup v1 decoder 必須檢查 version、duplicate IDs、referential integrity、offset/size/count
  bounds 與 enum；不支援版本 fail closed。
- `ignoreUnknownKeys` 可用於 forward-tolerant inspection，但不能把 malformed/corrupt payload 靜默
  變成空書庫或預設 state。
- Chapter text 以原字串內容產生 immutable UTF-8 text blob；legacy UTF-16 char offset 加上 quote
  context 轉為 locator。
- `Book.category` 轉成 portable category UUID；`Default` 映射系統 default，其他名稱不作跨書籍
  模糊合併。
- Backup 內 `pluginInstallations.script` 一律 quarantine。Decode、validation、preview 或 metadata
  import 絕不 evaluate JavaScript。
- Plugin ID/source ID 先映射到 extension v2 compatibility record。只有經 digest、manifest、runtime
  compatibility、permissions 與使用者 trust 確認後才可 install/execute。
- Plaintext credentials/cookies 不自動匯入。Report 顯示種類與筆數但不顯示值；使用者另行明確
  同意時才寫 strict secret store，且不可重新放入 portable backup/sync。
- `installedPlugins` 與 `pluginInstallations` 不一致時不得猜測；以 validation issue 呈現。
- 同一 backup digest 重跑時依 migration ledger 保持 idempotent，不重複建立 Publications、blobs、
  categories 或 plugin quarantine entries。
- Sync active 時，正式 import 必須轉成 v2 mutations/blob uploads；不能繞過既有 clocks/outbox
  direct replace。

## Phased compatibility

### M0：Contracts 與 golden fixtures

範圍內：

- 本 ADR；
- additive identity/publication/content/blob/locator/rights DTO 與 store interfaces；
- extension v2 DTO/contracts；
- legacy Manga/Chapter mapping contract；
- ShuYue backup v1 strict staging DTO／validation report；
- golden fixtures 與 contract tests。

範圍外：

- UI 切換；
- EPUB browser renderer；
- TTS／FTS／annotation services；
- R2 body endpoints；
- sync protocol/schema bump；
- 刪除／重新命名現有 `Manga`、`Chapter` 或 v1 plugin runtime。

### M1：Shared storage 與 legacy projection

- 建立 shared SQLite domain tables 與 ContentBlobStore state machine；
- 將現有 Manga/Chapter 無損投影到新模型；
- AppSnapshot 保持可 decode，UI 仍可走 legacy projection；
- crash/recovery 與 deterministic identity gate 通過。

### M2：TXT／EPUB acquisition 與 unified reader

- TXT encoding、chapter/block map、stable locators；
- 完整 EPUB package/resource/CSS renderer；
- image reader 以 ImageSequence adapter 接入同一 navigation contract。

### M3：Extension v2 與 plugin migration

- 一 package 多 source runtime；
- 同 source 多 content representations；
- 現有 Shinsou manga plugins 經 v1 adapter 保持可用；
- ShuYue Wenku8/Biquge plugins 經審核轉為 v2，ID 與既有書庫 migration 保持穩定。

### M4：Search、TTS、annotation 與 rights enforcement

- local derived FTS、CJK/Latin tokenizer、snippet locator；
- platform TTS engine 與 segment locator；
- paragraph/range annotation、quote fallback、re-anchor/tombstone；
- 所有 operation 走 host rights gate。

### M5：Backup v2 與 ShuYue transactional import

- versioned archive manifest/checksum 與 rights-filtered optional blobs；
- ShuYue staging report、transactional import、plugin quarantine、explicit secret flow；
- sync-aware restore 不繞過 replica/outbox。

### M6：Sync schema v2 與 encrypted body plane

- publication/unit/annotation/blob-ref mutations；
- R2 resumable encrypted chunks、quota、re-wrap、GC；
- checkpoint/recovery/key-rotation 相容；
- Android/iOS/macOS/Windows cross-device content smoke matrix。

## M0 acceptance matrix

| Gate | 必須通過的條件 |
|---|---|
| Identity | 相同 fixture 在所有 target 得到相同 stable key；opaque ShuYue string ID 原樣保留；legacy Long 可 round-trip；跨來源同名不自動合併 |
| Domain | Manga/Chapter -> 新模型 -> legacy projection 不丟 favorite/read/bookmark/progress/category/notes/flags；一 Publication 可多 Acquisition；一 Unit 可多 representation |
| Payload | v2 tagged union 可區分 text/image/EPUB；inline 有 hard cap；大型 body 只能進 host blob/stream；AppSnapshot/event/checkpoint 不含 body bytes |
| Storage | Metadata/ref 與 migration ledger 可在 shared SQLite transaction 提交；staging crash 不產生 partial committed blob 或 dangling ref；FTS 明示 derived |
| Locator | Image、plain text、EPUB locator schema 明確；UTF-16 offset 單位固定；ShuYue charOffset 可帶 quote fallback 遷移 |
| Rights | DISPLAY/OFFLINE/SYNC/EXPORT/COPY/PRINT/TTS/SEARCH_INDEX/ANNOTATE 可獨立表達；protected operation default deny；plugin hint 不能提升 grant |
| Extension compatibility | Runtime contract 不依賴 sources[0]；source ID 是 string；v1 adapter 邊界明確；現行 v1 types 尚未刪除 |
| Backup safety | v1 strict version dispatch；malformed/oversize/dangling refs 產 issue；decode/preview 不執行 script；plaintext secrets 只進 quarantined consent report |
| Wire compatibility | M0 不改 Cloudflare protocol/schema/min-writer，不發布新 mutation，不破壞 v1 AppSnapshot/backup decoder |

## 不接受的縮小範圍

以下替代方案不符合本決策：

- 在 `Manga` 加 `isNovel` 與 `text` 就宣稱完成統一模型；
- 只取 EPUB 圖片，或 strip HTML/styles 後宣稱完整 EPUB/CSS；
- 以 title/author metadata search 或 remote source search 代替本機正文全文搜尋；
- 以 raw char offset/page index 代替 paragraph/range annotation anchor；
- 以 accessibility screen reader、Android-only/iOS-only prototype 代替四平台 TTS；
- 以 app lock、secure screen、一般 at-rest encryption 代替 rights/DRM framework；
- 以同步 URL/metadata並讓另一裝置重新下載，代替 rights-allowed 的正文 blob sync；
- 把正文塞進 event、checkpoint、AppSnapshot 或巨大 backup JSON，代替 body plane；
- 複製／直接執行 ShuYue backup 內 script，代替 extension v2 migration、permissions 與 trust review；
- 自動攜帶 ShuYue 明文帳密/cookies 以追求表面上的「完整 restore」；
- 用 latest-wins 全量 snapshot sync 取代現有 E2EE event/checkpoint recovery correctness；
- 因 ShuYue backup 已丟失 EPUB CSS/assets，而虛構 lossless migration。正確行為是保留 flattened
  text、標示限制並要求原檔 re-import。

## 後果

正面結果：

- 漫畫、小說、TXT、完整 EPUB 與未來媒體可共享書庫、identity、reader navigation、backup 與
  sync semantics；
- 搜尋、TTS、註記與進度不再各自發明位置格式；
- 大型內容與 metadata/sync checkpoint 解耦；
- ShuYue migration 可稽核、可重跑，且不降低 Shinsou X 既有安全邊界。

成本與風險：

- 需要 shared SQLite/domain-store 重構與長期 compatibility projection；
- Desktop browser-grade EPUB renderer 會增加 binary、signing、installer 與 sandbox 複雜度；
- DRM provider 受外部授權、certification 與平台能力限制；
- Sync body plane 需新增 R2 quota、chunk lifecycle、key envelope retention 與 GC 狀態機；
- 舊 ShuYue EPUB backup 本質上無法恢復已丟失的 CSS/resources。

這些成本是完整目標的一部分，不能以縮小資料模型、只支援部分平台或把 bytes 塞回 snapshot
規避。
