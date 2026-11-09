# ADR-0003（草案）：獨立開放式同步服務

狀態：規劃中，尚未實作
日期：2026-11-09（2026-11-10 修訂：補上現況、資料模型、API、App 接入方式與里程碑驗收條件）

## 1. 現況（v1.0.1-beta.18 之後）

- 舊同步已在 `f07cd0a` 移除：iCloud 快照同步、v2 同步引擎、Cloudflare `syncWorker`、裝置配對、復原套件、blob 同步、同步設定頁。
- App 內**仍留下約 4,000 行 v2 模型**（`composeApp/.../sync/v2`、`sync/crypto`、`sync/trust`）：
  - 內容寫入路徑（`ContentTransactions`、`LocalTypedContentController`、備份還原等 14 個檔案）仍在每次提交時寫入 `SyncDraft` 到 outbox。
  - `ContentOutboxDiscarder` 在背景把它們丟掉，因為提交日誌的指紋包含 outbox，所以暫時不能直接停寫。
  - v2 的事件格式是為端對端加密設計的（文件切塊、`documentSha256`、blob DEK 信封），**不適合直接沿用到新服務**。
- `HlcTimestamp`、`SyncEntityKey`、`SyncEntityKey.normalizeNamespace` 等身分與時鐘規則仍可重用。
- 進度鍵 `ContentProgressKeyV2` 用的是本機內容 UUID（publication / acquisition / unit）。新服務改用作品鍵，App 端需要一張「作品鍵 ↔ 本機 UUID」對照表（見第 10 節）。
- 新服務的儲存庫：`~/project/shinsou_sync_service`（模組 `github.com/aluo96078/shinsou_sync_service`，執行檔 `shinsou-server`）。P0 的規格以該儲存庫的 `docs/spec/` 與 `api/` 為準，本文件只保留摘要。

## 2. 目標

1. 獨立的**後端**與**網頁前台**，開源，任何人都能自行架設。
2. 使用者可**自由選擇**要連到誰架設的同步伺服器。
3. 可**註冊帳號**，一個帳號可同步到**多台裝置**。
4. 同步**有來源的漫畫、小說**（書庫、章節狀態、閱讀進度、分類等）。
5. 在同一個伺服器內，可**分享作品**給其他使用者。
6. **書籍評論**（評分、文字、檢舉與管理）。

非目標（第一版）：同步章節內文或圖片、跨伺服器聯邦、端對端加密、一台裝置同時登入多個帳號。

## 3. 已定案的決策

| 項目 | 決定 | 理由 |
|---|---|---|
| 隱私模型 | 伺服器可讀；傳輸走 TLS，磁碟可加密 | 分享、評論、搜尋都需要伺服器理解資料。使用者選擇自己信任的伺服器 |
| 後端 | Go + PostgreSQL | 單一執行檔、記憶體佔用低，適合 VPS、NAS |
| 同步範圍 | 只同步中繼資料 | 章節內文在各裝置從來源重新下載；伺服器輕量，也沒有版權內容 |
| 同步語意 | **狀態式**（state-based）欄位級 LWW，不是事件重播 | 拉取等冪、不需要快照與檢查點、伺服器不必理解事件歷史 |
| 用戶端佇列 | **髒標記**（dirty set），推送時讀取本機當下狀態 | 佇列大小上限 = 實體數量，從結構上杜絕 1.8 GB 佇列 |
| 跨伺服器 | 第一版不做聯邦 | 分享與評論限定在同一個伺服器內 |
| 舊系統 | 已移除，不提供遷移 | 見第 11 節 |
| 開源授權 | 整個儲存庫（伺服器、網頁前台、API 規格與測試向量）採用 MIT | 完全開源、降低架設與二次開發門檻；接受他人以封閉方式修改或託管的可能 |

## 4. 系統組成

```
┌──────────────┐   HTTPS (REST + WebSocket)   ┌─────────────────────────┐
│ Shinsou X App │ ───────────────────────────▶ │ shinsou-server (Go)      │
│ (Android/iOS/ │                              │  ├─ /api/v1  JSON API    │
│  Desktop)     │ ◀── 變更通知 (WS/SSE) ─────── │  ├─ /       網頁前台 (SPA)│
└──────────────┘                              │  └─ 背景工作 (清理/寄信)  │
        ▲                                     └──────────┬──────────────┘
        │ 瀏覽器                                          │
┌──────────────┐                              ┌──────────▼──────────────┐
│ 網頁前台      │                              │ PostgreSQL              │
└──────────────┘                              │ 檔案儲存：本機目錄 / S3  │
                                              └─────────────────────────┘
```

### 4.1 技術選型

| 層 | 選擇 | 備註 |
|---|---|---|
| HTTP | Go 標準庫 `net/http`（1.22+ 路由） | 少依賴；中介層自寫（記錄、限流、驗證） |
| 資料庫存取 | `pgx` + `sqlc` | SQL 為主，型別安全 |
| 遷移 | `goose`，遷移檔以 `embed` 內嵌 | 啟動時自動執行，可用 `--no-migrate` 關閉 |
| API 規格 | OpenAPI 3.1（`api/openapi.yaml`）+ `oapi-codegen`（只產生型別與介面） | 單一事實來源 |
| 密碼 | `argon2id`（m=64 MiB, t=3, p=1，可設定） | |
| 即時通知 | WebSocket（`coder/websocket`），SSE 退路 | 只推「有新變更」 |
| 網頁前台 | Vite + Svelte + TypeScript，`openapi-typescript` 產生型別 | 打包後由 Go `embed` 提供 |
| 測試 | `testcontainers-go` 跑真的 PostgreSQL | |
| 發佈 | GoReleaser：多平台執行檔 + 多架構 Docker 映像 | |

### 4.2 儲存庫結構

```
shinsou_sync_service/
├─ api/
│  ├─ openapi.yaml           # 協定單一事實來源
│  └─ vectors/               # 跨語言測試向量（作品鍵正規化、LWW 合併）
├─ cmd/shinsou-server/       # main：serve / migrate / admin 子命令
├─ internal/
│  ├─ config/                # 環境變數與設定檔
│  ├─ httpapi/               # handler、中介層、錯誤格式
│  ├─ auth/                  # 帳號、密碼、權杖、裝置
│  ├─ sync/                  # push / changes / 合併 / 墓碑清理
│  ├─ works/                 # 作品身分與顯示資料
│  ├─ social/                # 分享、封鎖、評論、檢舉
│  ├─ admin/                 # 實例設定、使用者管理、管理紀錄
│  ├─ notify/                # WebSocket hub（單機記憶體；多實例時改用 PG LISTEN/NOTIFY）
│  ├─ store/                 # sqlc 產生碼與交易輔助
│  └─ mail/                  # SMTP（可選）
├─ migrations/
├─ web/                      # 網頁前台
├─ deploy/                   # docker-compose.yml、Caddyfile 範例、systemd unit
└─ docs/                     # 架設指南、API 說明、安全模型
```

### 4.3 設定（環境變數為主）

`SHINSOU_DATABASE_URL`、`SHINSOU_PUBLIC_URL`、`SHINSOU_LISTEN`、`SHINSOU_REGISTRATION=open|invite|closed`、`SHINSOU_SMTP_*`、`SHINSOU_STORAGE=local:/data|s3://…`、`SHINSOU_TRUSTED_PROXIES`。
JWT 簽章金鑰首次啟動時產生並存進資料庫，不要求架設者手動設定。

## 5. 伺服器探索與選擇

- **`GET /.well-known/shinsou`** 回傳實例資訊：名稱、說明、版本、API 版本範圍、註冊政策、是否需要 email 驗證、條款與隱私權政策網址、管理員聯絡方式、功能開關（評論、分享）。
- **App 的連線流程**：輸入伺服器網址 → 顯示實例資訊 → 登入或註冊。第一版每台裝置同時只連一個帳號。
- **版本相容**：App 送 `Shinsou-Api-Version`；伺服器不支援時回 `426`，App 顯示「請更新 App 或伺服器」。
- **安全要求**：正式環境必須 HTTPS（`localhost`、區網 IP 除外並顯示警告）。

## 6. 帳號與裝置

- **註冊**：使用者名稱（3–32 字元，`[a-z0-9_]`，不分大小寫唯一）+ 密碼（argon2id）+ 選填 email。
  - email 驗證與重設密碼，需要 SMTP 才會啟用。
  - 第一位使用者的建立方式：伺服器首次啟動時在日誌印出一次性設定碼，用它註冊的帳號成為管理員（避免公開實例被搶先註冊）。
- **工作階段**：
  - 存取權杖：JWT（EdDSA），15 分鐘。
  - 更新權杖：不透明隨機字串，資料庫只存雜湊；每台裝置一串，每次使用即輪替，舊權杖被重用時作廢整串並登出該裝置。
  - 網頁端：HttpOnly + SameSite=Lax cookie，加上 CSRF 權杖。
- **裝置**：每次登入建立裝置紀錄（名稱、平台、App 版本、最後上線時間），可在 App 或網頁上逐一登出。
- **帳號管理**：改密碼後其他裝置全部登出；匯出資料（JSON）；刪除帳號（書庫與進度立即刪除，評論可選刪除或匿名保留）。
- **後期**：OIDC 登入、TOTP。

## 7. 作品身分（核心設計）

跨裝置、跨使用者能指向「同一部作品」，是同步、分享和評論共同的基礎。

- **作品鍵**：`(keyVersion, packageId, sourceId, remoteId)`，對應 App 的 `RemoteEntityKey`（`SourceKey.contractVersion` 不納入）。
  - **完全不正規化**：與 App 的 `canonicalRemoteId`（恆等函式）一致，只驗證 UTF-8、非空白、無控制字元與長度上限。
  - 正規字串以 UTF-8 位元組長度前綴串接（`w1|9:zh.wenku89:zh.wenku84:1973`），`workHash` 為其 SHA-256 十六進位。
  - 規格：`shinsou_sync_service/docs/spec/work-key.md`；Go、Kotlin 共用 `api/vectors/work-key.json`。
- **章節鍵**：`(work, canonicalUnitId)`，章節 ID 只在其作品範圍內有效。
- **作品顯示資料**：標題、作者、封面網址、類型（漫畫或小說）、來源顯示名稱、語言。
  - 由用戶端提交，伺服器保留最新一次的值並記錄提交者。
  - 伺服器**不主動抓取**外部網址（避免 SSRF）；網頁前台顯示外部封面時走 `referrerpolicy=no-referrer`，或只顯示使用者上傳的縮圖。
- **本機匯入的書**（TXT、EPUB、圖片）：以用戶端產生的 UUID 存成私人條目；可同步書庫條目和進度，不能分享、評論，內文不上傳。
- **後期**：作品群組，把不同來源的同一部作品連起來，讓評論共用。

## 8. 同步模型

### 8.1 記錄

伺服器把每位使用者的同步資料存成**泛用記錄**，伺服器只負責合併，不需要懂每種記錄的語意：

| kind | key | 主要欄位 |
|---|---|---|
| `library` | 作品鍵 或 私人 UUID | inLibrary、addedAt、categoryIds、pinned、archived、updatePolicy、customTitle |
| `unit` | 作品鍵 + 章節 ID | read、bookmarked、lastPage |
| `progress` | 作品鍵 | locator（沿用 `ReadingLocator` 的可攜部分）、unitId、updatedAt |
| `history` | 作品鍵 | lastReadAt、lastUnitId |
| `category` | 類別 UUID | name、order、flags |
| `repository` | 正規化網址 | url、trustedPublisherFingerprints |
| `setting` | 設定名稱 | value（只限白名單內的可攜設定） |
| `annotation` | 註記 UUID | 第二階段才做 |

```sql
create table sync_records (
  user_id     uuid   not null references users(id) on delete cascade,
  kind        text   not null,
  key         text   not null,
  work_id     bigint references works(id),     -- library/unit/progress/history 才有
  fields      jsonb  not null,                 -- { 欄位: 值 }
  clocks      jsonb  not null,                 -- { 欄位: "millis:counter:deviceId" }
  deleted     boolean not null default false,
  deleted_hlc text,
  seq         bigint not null,                 -- 使用者層級遞增序號
  primary key (user_id, kind, key)
);
create index on sync_records (user_id, seq);
```

### 8.2 推送 `POST /api/v1/sync/push`

```json
{ "batchId": "uuid", "records": [
  { "kind": "progress", "key": "…", "work": { "sourceKey": "…", "remoteId": "…", "display": {…} },
    "fields": { "locator": {…} }, "clocks": { "locator": "1731200000000:0:dev-a" },
    "deleted": false } ] }
```

- 逐欄位比較 HLC，較新者勝；時鐘相等時保留伺服器的值。
- `deleted` 是獨立的 LWW 暫存器；另有 `cleared`（所有刪除中最大的時鐘），時鐘不晚於它的欄位一律清除，所以刪除後再加入不會讓舊欄位復活。完整規則見 `docs/spec/sync-semantics.md`。
- 有任何欄位改變才配發新 `seq`：在同一個交易內 `update users set sync_seq = sync_seq + 1 … returning`。
- 回應：每筆記錄的合併後結果與 `seq`，用戶端據此更新本機時鐘與已確認狀態。
- `batchId` 在 24 小時內重送會直接回傳上次結果（等冪）。
- **時鐘防護**：HLC 毫秒超過伺服器時間 +5 分鐘的欄位會被拒絕，避免一台時鐘錯誤的裝置永遠蓋掉別人。
- 上限：每批 500 筆或 1 MiB；單一使用者記錄數、每分鐘請求數有配額。

### 8.3 拉取 `GET /api/v1/sync/changes?since=<seq>&limit=500`

- 回傳 `seq > since` 的**完整記錄狀態**（不是差異），依 `seq` 排序，附 `nextSince` 與 `hasMore`。
- 新裝置從 `since=0` 分頁拉完即可得到完整狀態，不需要快照或檢查點。
- 墓碑保留 90 天；`since` 早於最舊已清除墓碑的位置時回 `410`，用戶端改做完整重拉並以本機髒資料再推一次。

### 8.4 即時通知

`GET /api/v1/sync/stream`（WebSocket，失敗退回 SSE）。伺服器只推 `{ "seq": 123 }`，裝置收到後自行拉取。手機在背景時不連線，回到前景時先拉一次。

### 8.5 一致性保證（要寫成測試）

- 同一組記錄不論推送順序、重送次數、分批方式，最後合併結果都相同（交換律、結合律、等冪）。
- 三台模擬裝置做隨機操作與隨機斷線後，最後都收斂到相同狀態（屬性測試，Go 端與 Kotlin 端各一套，共用 `api/vectors/lww.json`）。

## 9. 分享與評論

### 分享
- 同伺服器內以使用者名稱搜尋並分享作品，可附訊息。
- 收件者在收件匣「加入書庫」或忽略；未安裝對應來源的套件時，App 顯示套件名稱與儲存庫網址引導安裝。
- 可封鎖他人，或設定「只接受追蹤對象的分享」。
- **後期**：公開或私人書單，可分享連結。

### 評論
- 每位使用者對每部作品最多一則評論：評分（1–5，可選）、文字（Markdown 子集，伺服器端轉義後再渲染）、是否有雷。
- 作品頁：平均評分、評論數，排序（最新、最有幫助）。
- 互動：按讚（有幫助）、一層回覆、檢舉。
- 管理：管理員與版主可隱藏、刪除評論、停權使用者，動作全部寫入管理紀錄；新帳號有發文頻率限制。
- **後期**：章節留言、評論通知。

## 10. App 端改造

### 10.1 接入點：用髒標記取代 v2 outbox

1. **第一步（相容）**：新增 `sync/v3/DirtyMarker`，把現有 outbox 中的 `SyncDraft` 轉成「哪些實體被改過」，寫進新的 `sync_dirty(kind, key)` 表，再照舊確認 draft。`ContentOutboxDiscarder` 變成這個轉換器。
2. **第二步（清理）**：各寫入路徑直接標記髒實體，不再產生 v2 draft；移除 `ContentPublicationSyncDraftFactory` 等 v2 產生器、`sync/crypto`、blob 相關模型。提交日誌的指紋改為涵蓋 `sync_dirty`。
3. **推送時**才讀本機目前狀態組成 v3 記錄。佇列大小上限就是實體數量。

### 10.2 本機新增資料表

- `sync_dirty(kind, key, dirtied_at)`：待推送的實體。
- `sync_field_clock(kind, key, field, hlc)`：每個欄位最後一次本機或遠端寫入的 HLC，合併時使用。
- `sync_work_identity(work_key, publication_id, acquisition_id)`：作品鍵 ↔ 本機內容 UUID 對照；拉到本機沒有的作品時，以伺服器提供的顯示資料建立「待補全」書庫條目，等來源可用時再補章節。
- `sync_state(server_url, account_id, device_id, last_seq)`。

### 10.3 模組

- `ServerDirectory`：`/.well-known` 讀取與伺服器記憶。
- `AuthClient`：登入、註冊、權杖輪替；權杖放平台安全儲存（Android Keystore、iOS Keychain、桌面 OS 金鑰圈）。
- `SyncClient`：推送（前景每 5 秒合併一次髒標記、背景排程）、增量拉取、WebSocket。
- `SyncMerger`：沿用 `HlcTimestamp` 與 LWW 規則，套用遠端記錄到內容資料庫。
- Ktor 用戶端**手寫**（KMP 的 OpenAPI 產生器品質不穩），以 `api/vectors/` 下的 JSON 範例做契約測試，伺服器那邊用同一份範例驗證。

### 10.4 介面

- 設定 → 同步：伺服器、帳號、裝置清單、上次同步時間、手動同步。
- 作品詳情頁：評論區、分享按鈕。
- 新的收件匣頁。

## 11. 不提供舊同步遷移

- 舊同步已在 beta.18 移除，書庫與進度只保留在各裝置本機。
- 使用者登入新伺服器後，本機資料作為第一次推送的內容：登入時把所有實體標記為髒，第一台裝置上傳，其餘裝置拉取並依 LWW 合併。
- 為避免第二台裝置的舊資料蓋掉第一台，首次登入時本機欄位的 HLC 取「該欄位最後修改時間」，沒有修改時間的欄位以 0 計，讓伺服器上的值優先。

## 12. 分階段計畫與驗收條件

| 階段 | 內容 | 驗收條件 |
|---|---|---|
| P0 規格（2026-11-10 完成初稿） | OpenAPI 初稿；作品鍵與 LWW 規格及測試向量；記錄欄位規格；資料表遷移初稿 | Go 參考實作通過全部向量與隨機收斂測試；Kotlin `WorkKey`、`SyncClock` 通過作品鍵與 HLC 向量；遷移在 PostgreSQL 16 上可執行 |
| P1 伺服器核心 | 帳號、裝置、權杖、`/.well-known`、push / changes / stream、墓碑清理、限流、Docker Compose | 整合測試涵蓋 8.5 節的收斂性；`docker compose up` 後 curl 可走完註冊 → 推送 → 拉取 |
| P2a App 接入（書庫與進度） | `DirtyMarker` 相容層、伺服器選擇、登入、`library`/`unit`/`progress`/`history`/`category` 同步 | Android、iOS、桌面三台實機互相同步；斷網改動後恢復可收斂；佇列在 1 萬筆變更後仍只有實體數量那麼大 |
| P2b App 清理 | 移除 v2 draft 產生器與 `sync/crypto`；`repository`、`setting` 同步 | v2 相關程式碼刪除，`./gradlew check` 通過 |
| P3 網頁前台 | 註冊登入、裝置管理、書庫與進度檢視、匯出與刪除、管理員後台 | 不用 App 也能完成帳號全生命週期 |
| P4 分享 | 使用者搜尋、分享、收件匣、封鎖（App 與網頁） | |
| P5 評論 | 作品頁、評論、按讚、回覆、檢舉、管理工具 | |
| P6 收尾 | 架設文件；Docker、Fly.io、Railway 範本；備份與升級指南 | 一位沒看過程式碼的人照文件 15 分鐘內架好 |

順序：P0 → P1 → P2a；P3 可與 P2a 平行；P2b 在 P2a 實機穩定後做；P4、P5 需要 P1，且建議在 P2a 之後，以免作品身分規則還在變動。

## 13. 決策紀錄（2026-11-10）

| 項目 | 決定 |
|---|---|
| 測試伺服器 | Mac 本機，使用 podman 的 PostgreSQL；手機以區網 IP 連線 |
| App 預設伺服器 | 不內建，使用者自行輸入網址 |
| 網頁前台框架 | **未決**，P3 開始前決定 |
| Commit | 每階段測試通過後在兩個儲存庫各自 commit，不 push |
| email 驗證 | 由架設者決定（disabled / optional / required）；開放註冊且有 SMTP 時預設 required |
| 分享 | 做追蹤功能；可封鎖、可設「只接受追蹤對象的分享」、可完全關閉接收 |
| 訪客瀏覽 | 由架設者決定是否開放未登入者唯讀瀏覽作品頁與評論，預設關閉 |
| 評論 | 照計畫全做：評分、文字、有雷、按讚、一層回覆、檢舉、版主管理；不提供匿名發文 |
| iOS 驗證 | 使用 iOS 模擬器 |
| 可攜設定 | P2b 前列出建議清單由使用者勾選 |
| 同步時機 | 前景即時（推送＋WebSocket 通知），另加 Android WorkManager / iOS BGTask 背景定期同步 |
| 部署範本 | Docker Compose＋Caddy |
| 沿用第 3 版建議 | 不做 SQLite 模式；一台裝置一個帳號；角色分使用者、版主、管理員；第一版不做聯邦但保留 `@user@host`；作品顯示資料採最新提交者勝並記錄提交者 |
