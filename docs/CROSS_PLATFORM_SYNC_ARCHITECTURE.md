# 跨平台同步架構規劃

- 狀態：Proposed
- 適用專案：Shinsou X KMP
- 最後更新：2026-08-21
- 目標平台：Android、iOS、macOS、Windows

## 1. 決策摘要

跨平台同步採用「本機優先、端對端加密、事件同步」架構。預設後端為一套可由使用者自行部署的 Cloudflare 服務：

```text
Android / iOS / macOS / Windows
                │
         HTTPS + WebSocket
                │
       Cloudflare Worker
        ├── D1
        │   ├── 使用者、裝置、邀請與權限
        │   └── workspace head、加密 event tail 與 checkpoint index
        ├── WorkspaceHub Durable Object
        │   ├── WebSocket 即時推送
        │   ├── 每個 workspace 的寫入序列化
        │   └── 限流與短期連線狀態
        └── R2
            ├── 客戶端產生的加密 checkpoint／備份
            └── 經 rights gate 允許的加密 content manifests／chunks
```

核心決策如下：

1. D1 control plane 加上 R2 stable checkpoint 與其後的 D1 event tail，共同構成可恢復的遠端權威狀態；Durable Object 不保存任何唯一資料。
2. R2 的 control-plane namespace 保存 checkpoint／備份；M6 另設隔離的 encrypted content body
   namespace。正文絕不塞入 event、checkpoint 或每次翻頁覆寫的 snapshot；event GC 後仍不可缺少
   stable checkpoint。
3. 一般使用者不需要 Cloudflare 帳號、Email、密碼或 OAuth。
4. 裝置配對只呈現「掃描 QR、核對短碼、按下允許」。
5. 一套 Cloudflare deployment 可服務多位互相隔離的使用者。
6. 所有領域資料在客戶端加密；伺服器只接觸密文、公鑰、token hash 與必要流量 metadata。
7. 現有 iCloud 單檔 snapshot sync 保留為舊版能力／備份來源，但不能與新事件同步引擎同時作為主同步寫入端。

## 2. 產品目標與驗收指標

| 目標 | 設計結果 | 驗收方式 |
|---|---|---|
| 裝置認證簡單 | QR 掃描、6 位確認碼、一次允許 | 新裝置可在不輸入帳密的情況下完成配對 |
| Cloudflare 配置懶人化 | Deploy to Cloudflare 範本、自動建立 bindings／migrations | 除 Cloudflare 登入外，最多只需貼上一個 bootstrap secret 並完成一次 claim |
| 跨裝置閱讀接力 | 小型 ReadingProgress event、foreground catch-up、WebSocket push | 正常網路且兩端前景時，通常在 1～2 秒內出現新進度 |
| 單一部署支援多使用者 | user、workspace、membership、device 全面 tenant scoping | 跨使用者讀寫測試必須全部被拒絕，管理員也不能解密內容 |
| 離線可用 | 本機先寫入 durable outbox，恢復連線後重送 | 網路中斷或程序被終止不會遺失已本機提交的修改 |
| 免費私人部署 | 使用 Cloudflare 免費額度與保守 quota | 家庭／小群組使用可維持低請求與儲存量；不以公開 SaaS 規模為目標 |

「1～2 秒」是正常網路與兩臺 App 都在前景時的產品目標，不是背景作業系統保證。iOS／Android 完全 suspend、離線或 Cloudflare 故障時，正確行為是保留本機資料並在下一次可連線時補送。

## 3. 範圍

### 3.1 第一版同步內容

- 書庫項目與分類
- 章節 read／bookmark 狀態
- 閱讀歷史與精確閱讀位置
- explicit allowlist 中非敏感且具可攜性的應用設定；不直接同步整份 `AppSettings`
- extension repository 清單
- 刪除 tombstone
- M6 schema v2 的 Publication／Acquisition／Unit／Manifest、annotation 與 blob reference metadata
- 使用者選擇且當下 `RightsGrant` 明確允許 `SYNC_BLOB` 的 immutable content bodies；body 走獨立
  R2 plane，不進 event／checkpoint

### 3.2 預設不同步

- download queue 與一般下載快取
- Local source 的原始 ZIP／CBZ／EPUB 容器不會自動同步；只有已進 host `ContentBlobStore`、具完整
  manifest graph 且通過 rights gate 的 immutable bodies 可明確加入 M6 body sync
- extension package 與執行環境
- 來源登入 cookie、OAuth token、密碼、API key
- 裝置專屬設定，例如路徑、視窗狀態與平台權限

未獲 `SYNC_BLOB` 授權或未選擇同步的內容，在另一臺裝置只會看到 metadata，仍需重新匯入或由
來源重新取得。來源 credentials 日後可提供獨立、明確 opt-in 的秘密同步，但不納入第一版。

### 3.3 非目標

- 不將私人自架範本設計成公開註冊 SaaS。
- 不保證作業系統已完全 suspend 時仍可背景即時同步。
- 不讓 Cloudflare 管理員讀取使用者的書庫或閱讀紀錄明文。
- 不使用 APNs／FCM 作為資料正確性的必要條件。
- 第一版不提供多人共同編輯同一個 vault；資料模型保留 workspace／membership，以便未來擴充。

## 4. 租戶與角色模型

```text
Cloudflare deployment（instance）
├── Instance admin
│   ├── 邀請／停權使用者
│   ├── 查看用量與設定 quota
│   └── 無法取得 workspace 明文金鑰
├── User A
│   └── Private workspace A
│       ├── Android device
│       └── macOS device
└── User B
    └── Private workspace B
        ├── iOS device
        └── Windows device
```

- `instance`：一套 Cloudflare 部署。
- `user`：Shinsou 使用者，不等於 Cloudflare 帳號。
- `workspace`：資料、加密金鑰、事件序列、quota 與 Durable Object 的隔離邊界。
- `device`：一個可獨立撤銷的安裝實例；不能使用固定的 `android`／`desktop` 字串作為身份。
- 第一版每位 user 自動建立一個 private workspace。
- Worker 必須從已驗證的 device credential 推導 user／workspace，不可相信 request body 傳入的 user ID。
- R2 object key 必須由 Worker 用已驗證 UUID 組成，不接受客戶端提供任意 path。

## 5. 使用者流程

### 5.1 建立 Cloudflare 同步服務

只有 instance admin 需要 Cloudflare 帳號。

1. Shinsou 設定頁選擇「建立同步服務」。
2. App 在本機產生高熵 bootstrap secret，複製到剪貼簿後開啟 Deploy to Cloudflare 頁面。
3. 部署範本自動建立 D1、private R2、Durable Object binding/class migration，並執行 numbered D1 migrations。
4. 使用者只需貼上 bootstrap secret，部署流程將它保存為 encrypted Worker secret；預設使用 `workers.dev`，自訂網域放在進階設定。
5. 部署完成後開啟 `/setup`，頁面提供「在 Shinsou 開啟」deep link 與 QR。
6. App 使用同一 bootstrap secret claim 第一臺管理裝置。
7. Worker 將 instance 設為 initialized、切換成 invite-only，之後永久拒絕 bootstrap claim。

若 Cloudflare 瀏覽器部署流程因權限、R2 啟用或帳戶政策無法完成，提供一條 CLI 指令作可靠 fallback。CLI 自動產生 secret、建立資源、執行 migration 並印出 setup QR。

### 5.2 新增一位使用者

1. Instance admin 選擇「邀請使用者」。
2. 系統建立單次使用、預設 24 小時過期的邀請 QR／連結。
3. 新使用者第一臺裝置掃描邀請。
4. 裝置在本機建立 device keys、自己的 workspace key 與 Recovery Kit。
5. Server 建立 user、private workspace、device 與 public-key envelope。

邀請 secret 至少 192-bit，建議 256-bit；server 只保存 hash。預設不提供公開註冊。

### 5.3 同一使用者新增裝置

使用者介面只有以下步驟：

1. A 裝置選擇「設定 → 同步 → 裝置 → 新增裝置」。
2. A 顯示 5 分鐘有效、單次使用的 QR。
3. B 掃描 QR，兩臺顯示由完整 pairing transcript 推導的相同 6 位確認碼。
4. A 顯示 B 的名稱與平台；使用者按「允許」。
5. B 自動取得 workspace key envelope 與自己的 device credential，開始 catch-up。

QR 不包含 workspace 明文金鑰或長期 token。內部流程為：

1. B 產生 Ed25519 signing key、X25519 wrapping key 與隨機 device API token。
2. B 提交 public keys 與 token hash。
3. A 確認短碼後，以 B 的 X25519 public key 包裝 workspace key。
4. A 對 pairing session、B public keys、token hash、key envelope hash 與 expiry 簽章。
5. Server 驗證 A 仍是有效裝置及簽章正確後才啟用 B。

新裝置還必須能走完所有 retained recovery paths。配對時 A 應包裝「recovery base checkpoint 到目前 head」以及 retained stable fallback 所需的完整 key epoch 集合；若其中任何 key 已不存在，先以目前 epoch 建立並驗證一份覆蓋當前 head 的 checkpoint，建立新的可自足 recovery base，再完成 B 的啟用。不能讓裝置顯示配對成功後才發現最新 checkpoint 損壞時無法 fallback。

### 5.4 撤銷遺失裝置

1. 使用者從任一有效裝置撤銷目標 device。
2. D1 在同一個 control-plane transaction 將 device 設為 revoked、遞增 `authEpoch`，並啟動 workspace key epoch rotation。
3. D1 commit 後 Worker 通知 WorkspaceHub invalidation；Hub 立即中斷舊 WebSocket。通知失敗時，由短效 workspace capability 的 TTL 提供失效上限。
4. 剩餘裝置建立新的 workspace key epoch。
5. Server 拒絕舊 epoch 的任何新 event／checkpoint；新 key 只包裝給仍有效的裝置與 recovery public key。
6. 建立涵蓋完整狀態的新 checkpoint，等待驗證後逐步淘汰舊 event tail。

撤銷只能保護未來資料，無法讓遺失裝置忘記已經下載或解密過的內容。

### 5.5 所有裝置遺失

每位使用者在建立 workspace 時取得可列印的 Recovery QR／恢復碼。Recovery secret 不上傳 server；server 只保存 recovery public keys 與 workspace key envelope。

恢復時，新裝置以 recovery signing key 回應 server challenge，解開 workspace key envelope，撤銷舊裝置，輪替 key epoch，並產生新的 Recovery Kit。若 Recovery Kit 也遺失，管理員只能重設為空 workspace，不能解密或恢復原資料。

Recovery bootstrap 也必須保留歷史 sender trust，不能因裝置已撤銷就從 device directory
移除其簽章公鑰。建立 workspace 時，Recovery signing key 以獨立 domain co-sign 初始
device/workspace/public-key binding；每次 Recovery 則由舊 root 簽 recovery claim，並由新 root
反向 co-sign predecessor root 與新 device identity。Client 只可從本機匯入或已 pin 的 root 出發，
沿這條雙向、不可變的 lineage 驗證完整 directory。這讓新安裝仍可驗證 revoked device 產生的
歷史 event/checkpoint，同時不會把 server 提供但未經 Recovery 私鑰簽署的自簽 device key 當成可信。

### 5.6 Key epoch rotation 協定

Rotation 對使用者不可見，但不能由兩臺裝置各自產生「同一 epoch 的不同 key」。流程固定為：

1. 有效裝置以 D1 CAS 取得 workspace 唯一的短效 rotation lease；lease 綁定 `rotationId/fromEpoch/toEpoch/proposerDeviceId/authEpoch`。
2. Proposer 產生全新隨機 256-bit epoch key 與 key commitment。
3. Proposer 為當下所有有效 recipient devices 與 recovery public key 產生 envelope，建立 deterministic CBOR manifest；manifest 綁定 rotation ID、前後 epoch、key commitment、recipient 清單、每個 envelope hash 與 expiry，再以 device key 簽章。
4. Recipient 解開 envelope 後驗證 commitment，可提交 ack；同一 manifest 中所有 envelopes 必須對應同一個 epoch key。
5. Commit 使用 D1 transaction 再次確認 lease、proposer、有效 recipient set、device/membership auth epochs 與目前 active epoch 都未改變，然後保存 manifest/envelopes 並 CAS 切換 `active_key_epoch`。
6. Commit 後通知 WorkspaceHub invalidation；舊 epoch 只能作歷史解密，不能再 append 新 event/checkpoint。

若 recipient set 或權限在 rotation 中改變，整個 manifest 作廢並重新取得 lease，不能局部修改。這套 single-proposer lease + signed manifest 同時用於撤銷、Recovery 與需要重新封裝 key 的配對流程。

## 6. Cloudflare 元件責任

### 6.1 Worker

- `/setup` 與 deployment initialization
- device token 驗證、簽章驗證與 tenant authorization
- invite、pairing、device、recovery API
- workspace event append／catch-up API
- WebSocket upgrade 與 Durable Object routing
- R2 checkpoint 串流與 object metadata 驗證
- request body 限制、edge rate limit、CORS 與安全 headers
- `/v1/capabilities` 協定與版本協商

### 6.2 D1

D1 是 control plane、workspace sequence/head 以及 active encrypted event tail 的權威儲存：

- instance、schema migration 狀態
- users、devices、memberships
- token hash、invites、pairing sessions
- workspace key envelopes
- encrypted events 與 idempotency constraints
- checkpoint pointer、ack、lease metadata
- quota profiles、權威 byte reservation／usage 與每日彙總

可恢復的完整 workspace 狀態定義為「目前 stable R2 checkpoint + `throughWorkspaceSeq` 之後的 D1 events」。任何 event 都必須先成功 commit D1，才可由 Durable Object broadcast。若 Durable Object 故障，client 仍可透過 REST 從 D1 catch-up；寫入保留在 client outbox，不能繞過驗證／限流直接寫 D1。

### 6.3 WorkspaceHub Durable Object

每個 workspace 對應一個 hibernatable Durable Object，負責：

- WebSocket fan-out
- event append 的序列化
- workspace／device token bucket
- membership epoch 的短期 cache
- checkpoint lease 協調
- 連線 cursor／ack 聚合
- D1 revoke commit 後的 capability invalidation 與 socket disconnect

它不應是 events、membership、token、checkpoint pointer 或 quota totals 的唯一保存位置。

### 6.4 R2

R2 保存 immutable、已壓縮並經 AEAD 加密的 checkpoint：

```text
workspaces/{workspaceId}/checkpoints/v1/{throughWorkspaceSeq}/{checkpointId}-{sha256}.bin
```

- Checkpoint 必須由持有 workspace key 的 client 產生，內容是完整 `SyncState`，而不是只有現行 `AppSnapshot`。
- `SyncState` 必須包含所有 field HLC registers、tombstones、stable content keys、reader reset epochs、key epoch、`throughWorkspaceSeq`、schema version 與前一 stable checkpoint hash。
- Client 先取得固定在當下 `headSeq` 的 lease，再由上一個 stable checkpoint 加上 `until=throughWorkspaceSeq` 的完整分頁 event tail 建立 candidate；建立期間的新 events 留在下一段 tail。
- Server 可檢查大小、hash、簽章與 sequence，但不能驗證明文語意。
- 最新 checkpoint 先標記為 candidate。
- 驗證裝置不能只做「可以解密」檢查；它必須獨立載入前一 stable checkpoint、完整重播固定 watermark 前的 event tail，將 deterministic canonical SyncState bytes 與 candidate 明文逐 byte 比較，再簽署綁定 `checkpointId + ciphertext hash + throughWorkspaceSeq + keyEpoch + validationVersion` 的 ack。
- 另一臺裝置完成上述 replay ack 後才可標記 stable。單裝置 workspace 必須重新下載 R2 object，使用獨立 reducer instance self-verify，並保留 grace period。
- Lease、upload、commit、ack 與 promotion 全部綁定 exact `checkpointId + ciphertext hash`；同一 `throughWorkspaceSeq` 可存在 rotation 或 retry 產生的多個 objects，但最多一個可成為該輪 stable。
- 永遠保留最新 3 份 stable checkpoint，`/bootstrap` 回傳 retained stable 清單而不只最新一份。
- 最新 stable checkpoint 是正常 bootstrap base；第二新的 stable checkpoint 是 recovery base。D1 必須保留 recovery base 之後直到目前 head 的完整 event delta，因此最新 checkpoint 損壞時仍可由 recovery base 重建完整狀態。
- 只有至少存在兩份已驗證的 stable checkpoint，且超過 safety window 後，才能 GC `workspaceSeq <= recoveryBase.throughWorkspaceSeq` 的 events；只有一份 stable checkpoint 時不得依賴它刪除全部歷史。
- Recovery base 向前移動、stable pointer／retained set 更新與 event GC boundary 必須由同一個可恢復狀態機控制；刪除失敗可重試，不能先失去 recovery delta。
- Candidate 與 stable pointer 必須分開；stable pointer 更新及對應 D1 metadata 變更必須是同一 transaction。

### 6.5 M6 encrypted content body plane

Content body plane 與 checkpoint control plane 共用 workspace capability、quota authority 與 key epoch，
但使用不同的 D1 tables、R2 object prefixes 與 lifecycle。Worker 只接受已驗證 UUID，並由已驗證的
`instanceId/workspaceId/blobId/manifestId/chunkIndex` 組成 object key；client 永遠不能提交任意 R2
path。

```text
workspaces/{workspaceId}/content/v2/blobs/{blobId}/manifests/{manifestId}.bin
workspaces/{workspaceId}/content/v2/blobs/{blobId}/manifests/{manifestId}/chunks/{chunkIndex}.bin
```

- Event/checkpoint 只保存 `BlobRef`、remote manifest reference、每個 retained key epoch 的 DEK
  envelope、presence/tombstone 與 HLC；正文、私有 manifest、plaintext digest 與 DEK 不進 metadata
  plane。
- 每個 blob 使用隨機 256-bit DEK。Chunk 與 private manifest 分別使用 ChaCha20-Poly1305、獨立
  domain-separated nonce/AAD；clear manifest reference 只暴露路由、大小、chunk 數、ciphertext hash
  與 commit receipt identity。
- Upload session 先以 D1 CAS reserve exact bytes，再分塊 idempotent upload；commit 必須驗證每個
  chunk index/size/hash、private manifest hash 與 reservation。R2 成功但 D1 commit 失敗不得形成
  remotely available blob。
- Client 的 durable transfer journal 保存 exact upload intent、DEK envelope 與 commit receipt。順序
  固定為「Worker manifest commit → durable receipt → durable `BlobReferenceCommitV2` draft → content
  job ack → journal cleanup」；每個 crash boundary 都以 deterministic operation ID replay。
- Key rotation 不重新加密大型 body；client 以新 workspace epoch key re-wrap 同一 DEK，envelope
  必須鏈結 previous envelope hash。至少保留 recovery base 到 active epoch 所需 envelopes。
- Metadata presence 先產生 tombstone。只有 stable checkpoint 已包含該 tombstone，且有效 validator
  以 Ed25519 簽署 exact checkpoint/hash/through-seq ack 後，Worker 才接受 GC。GC 同時刪除 chunks、
  private manifest、envelopes與 quota usage，並以 receipt 保持冪等。
- Tombstone identity 綁定 immutable removal boundary
  `(workspaceId, blobId, manifestId, referenceThroughWorkspaceSeq)`；多臺裝置可先產生不同 provisional
  UUID，但 Worker 必須回傳同一 canonical handle，client 將 winner durable save 後才可簽 ack。
- 較新的 `BlobReferencePresenceSetV2(true)` 會中止本機 tombstone intent。若 tombstone 已在 Worker
  建立，client 必須先驗證 retained stable checkpoint 的 plaintext 確實含 live reference，再簽署
  exact checkpoint identity/hash/through-seq revival assertion。Worker 看不到 E2EE presence，只驗證
  active-device signature、retained checkpoint 與嚴格較新的 sequence。
- Revival 與 GC claim 在 D1 原子競爭：`WAITING -> CANCELLED` 會恢復 manifest 為 committed 並保留
  signed audit；GC 必須在刪 R2 前原子完成 `WAITING -> DELETING` 的最後重查。已進 `DELETING`／
  `DELETED` 時 revival 回傳 typed `reupload_required`，不可假裝原 body 已恢復。Cancelled removal
  history 不阻擋同一 blob 之後以較新 boundary 再次 remove/revive。
- Upload/download/rewrap/tombstone/GC 都重新檢查 capability tenant/auth/key epoch；`SYNC_BLOB`、
  `OFFLINE_STORE` 與其他 host rights operation 仍各自 fail closed，plugin hint 不能提升 grant。
- Body worker 永遠不在 cold-start、foreground catch-up、reader navigation 或 progress flush critical
  path 執行。ReadingProgress 與 metadata outbox 優先；body scheduler 每個低優先級 slice 最多處理
  一個 blob，失敗只保留 durable job 等待重試。

## 7. 最小資料模型

欄位為概念設計；正式 migration 仍需加入 foreign key、status constraint、時間格式與必要 index。

```sql
installation(
  instance_id PRIMARY KEY,
  schema_version,
  initialized_at,
  signup_mode
)

users(
  user_id PRIMARY KEY,
  display_name,
  instance_role,
  status,
  quota_profile_id,
  created_at
)

devices(
  device_id PRIMARY KEY,
  user_id,
  display_name,
  platform,
  signing_public_key,
  wrapping_public_key,
  token_hash,
  status,
  auth_epoch,
  created_at,
  last_seen_at,
  revoked_at
)

workspaces(
  workspace_id PRIMARY KEY,
  owner_user_id,
  status,
  active_key_epoch,
  pending_rotation_id,
  head_seq,
  stable_checkpoint_id,
  candidate_checkpoint_id,
  committed_bytes,
  reserved_bytes,
  created_at
)

memberships(
  workspace_id,
  user_id,
  role,
  status,
  auth_epoch,
  PRIMARY KEY(workspace_id, user_id)
)

workspace_device_keys(
  workspace_id,
  key_epoch,
  device_id,
  rotation_id,
  key_commitment,
  wrapped_key,
  wrapped_by_device_id,
  signature,
  PRIMARY KEY(workspace_id, rotation_id, device_id)
)

recovery_credentials(
  user_id PRIMARY KEY,
  signing_public_key,
  wrapping_public_key,
  rotated_at
)

workspace_recovery_keys(
  workspace_id,
  key_epoch,
  rotation_id,
  key_commitment,
  wrapped_key,
  PRIMARY KEY(workspace_id, rotation_id)
)

key_rotations(
  rotation_id PRIMARY KEY,
  workspace_id,
  from_epoch,
  to_epoch,
  proposer_device_id,
  key_commitment,
  manifest_cbor,
  manifest_signature,
  status,
  lease_expires_at,
  created_at
)

events(
  workspace_id,
  workspace_seq,
  event_id,
  device_id,
  device_seq,
  key_epoch,
  authenticated_header_cbor,
  ciphertext,
  ciphertext_sha256,
  signature,
  byte_size,
  created_at,
  PRIMARY KEY(workspace_id, workspace_seq),
  UNIQUE(workspace_id, event_id),
  UNIQUE(workspace_id, device_id, device_seq)
)

device_stream_state(
  workspace_id,
  device_id,
  committed_device_seq,
  updated_at,
  PRIMARY KEY(workspace_id, device_id)
)

event_receipts(
  workspace_id,
  device_id,
  device_seq,
  workspace_seq,
  ciphertext_sha256,
  created_at,
  PRIMARY KEY(workspace_id, device_id, device_seq)
)

checkpoints(
  workspace_id,
  through_seq,
  checkpoint_id,
  key_epoch,
  authenticated_header_cbor,
  envelope_version,
  schema_version,
  cipher_suite,
  nonce,
  previous_stable_sha256,
  r2_key,
  ciphertext_sha256,
  byte_size,
  uploader_device_id,
  device_signature,
  status,
  created_at,
  PRIMARY KEY(workspace_id, checkpoint_id)
)
```

另需 `invites`、`pairing_sessions`、`checkpoint_acks`、`checkpoint_leases`、`key_rotation_acks`、`quota_profiles`、`workspace_usage_reservations`、`usage_daily` 與 `schema_migrations`。Checkpoint lease、commit、ack、stable/candidate pointer 都以 `(workspaceId, checkpointId, ciphertextSha256)` 綁定 exact object；`through_seq` 只作排序／查詢，不能作 identity，因為同一 head 可能因 rotation、損壞或 retry 產生多份 checkpoint。

`workspace_seq` 是每個 workspace 從 1 開始的連續序號，不是 deployment-wide `AUTOINCREMENT`。Event commit 必須在單一 D1 statement／transaction 內 conditional assert device active、membership active、capability 的 device/membership auth epochs 與資料庫相符、event key epoch 等於 workspace active epoch；通過後才讀取 `workspaces.head_seq + 1`、插入 event，並更新 workspace head、device high-watermark、receipt 與 committed bytes。任一條件或寫入失敗時全部 rollback。可用 single-statement conditional insert 加 trigger 或經 integration test 證明具相同原子性的 D1 transaction。如此 revoke/rotation 不會在 Worker 預檢與 commit 之間產生 TOCTOU，其他 tenant 的寫入也不會在 client cursor 中製造假 gap。

`authenticated_header_cbor` 保存 client 送來且用作 AEAD AAD／簽章輸入的原始 canonical bytes；D1 的 workspace/device/epoch 等索引欄位必須逐一與解析後 header 交叉驗證。Catch-up 回傳原始 header bytes，不能從資料庫欄位重新序列化，否則跨版本時可能無法驗簽。Checkpoint 的 `checkpoint_id` 是 client 產生的隨機 UUID，明確寫入 checkpoint header，不由 ciphertext hash 反推。

每個 device 必須依序提交 sealed `device_seq`。`device_stream_state.committed_device_seq` 與 compact `event_receipts` hash rows 在 event payload GC 後仍保留；對任何 `deviceSeq <= high-watermark` 的重送，server 仍能比較 ciphertext hash 並回原 receipt 或 `409 replay_or_corruption`，絕不能當成新 event 插入。第一版不淘汰 receipt hash；未來若要封存，必須先有可驗證的 receipt segment/proof，不能退化成不可驗證的成功回覆。

必要 index 至少包含：

```sql
events(workspace_id, workspace_seq)
events(workspace_id, created_at)
memberships(user_id, status)
workspace_device_keys(device_id, workspace_id, key_epoch, rotation_id)
```

## 8. 同步事件協定

### 8.1 可見且經驗證的 header

Server 需要看見路由、版本、解密參數、驗證及限流所需欄位。以下 JSON 只供閱讀；簽章、AEAD AAD 與 hash 的規範 bytes 一律使用 RFC 8949 deterministic CBOR，避免 Kotlin／TypeScript JSON 序列化差異：

```json
{
  "envelopeVersion": 1,
  "protocolVersion": 2,
  "schemaVersion": 2,
  "cipherSuite": "CHACHA20_POLY1305",
  "nonce": "base64url",
  "instanceId": "uuid",
  "workspaceId": "uuid",
  "eventId": "uuid",
  "deviceId": "uuid",
  "deviceSeq": 42,
  "keyEpoch": 3,
  "ciphertextSha256": "base64url",
  "signature": "base64url"
}
```

- `workspaceSeq` 由 D1 commit 時配置，不屬於 client AEAD AAD 或 device signature，避免先產生 ciphertext 還是先取得 server sequence 的循環依賴。
- AEAD AAD 是上述 immutable client header 去除 `ciphertextSha256` 與 `signature` 後的 canonical bytes。
- Device signature 覆蓋相同 canonical header 加上 ciphertext SHA-256。
- REST／WebSocket envelope 另外攜帶 server 配置的 `workspaceSeq` 與 receipt metadata。
- Event key 由 workspace epoch event key 再依 `deviceId` 派生；同一 device/key epoch 的 nonce 必須唯一。
- Event 一旦 sealed，nonce、event ID、device sequence、header 與 ciphertext 都不可修改。

### 8.2 加密 payload

```json
{
  "opId": "uuid",
  "hlc": {
    "millis": 0,
    "counter": 0,
    "deviceId": "uuid"
  },
  "mutations": []
}
```

`protocolVersion`／`schemaVersion` 已位於 clear-but-authenticated header，因此 client 在解密前即可選擇正確 codec，不會形成必須先解密才能知道 AAD 的循環。

事件必須可重試、可重播、可交換順序，並得到相同結果。第一版 mutation 類型建議包括：

- `LibraryEntryPatch`
- `ChapterStatePatch`
- `ReadingProgressSet`，原子包含精確 position、history touch 與 completed/read materialized state
- `CategoryPatch`
- `CategoryMembershipSet`
- `ExtensionRepositoryPatch`
- `ExtensionRepositoryPresenceSet`
- `PortableSettingPatch`
- `EntityPresenceSet`，其中刪除使用 LWW tombstone
- `PublicationPatchV2`／`AcquisitionPatchV2`／`PublicationUnitPatchV2`
- `ContentManifestPatchV2`／chunked `ContentAnnotationPatchV2`
- `PublicationCategoryMembershipSetV2`／`ContentReadingProgressSetV2`
- `BlobReferenceCommitV2`／`BlobDekEnvelopeRewrappedV2`／`BlobReferencePresenceSetV2`

Protocol/schema v1 只作既有 metadata envelope 的 read compatibility；active v1 writer 不可靜默丟棄
v2 content mutations。Shared content transaction 必須明確 reject 或 durable defer，直到 negotiated writer
升級至 v2。

### 8.3 Idempotency

- 每臺裝置維護單調遞增的 `deviceSeq`。
- Server 只接受下一個連續 `deviceSeq`；client 必須依序送出 sealed events。
- `eventId` 與 `(workspaceId, deviceId, deviceSeq)` 皆設 unique constraint。
- 相同 `deviceSeq` 與相同 ciphertext hash 的重試回傳既有 receipt。
- 相同 `deviceSeq` 但不同 hash 回傳 `409 replay_or_corruption`。
- Event payload GC 後仍以永久 device high-watermark 與 compact receipt hash row 驗證舊 sequence，並回傳原始 `workspaceSeq`／hash。
- Reader coalescing 只能更新尚未 seal 的 durable draft；一旦配置 `eventId/deviceSeq` 或開始傳送就必須 immutable，之後的位置建立新 draft。
- Sender 應在取得目前 capability/key epoch 後才 seal，避免離線期間產生大量舊 epoch ciphertext。Server 對 `deviceSeq <= committed high-watermark` 必須先走 dedup/receipt，再檢查 epoch；只有尚未 commit 的下一個 sequence 才可回 `stale_key_epoch`。
- 收到明確的 `stale_key_epoch` 代表該 envelope 未 commit。Client 保留舊 sealed bytes 作稽核，使用相同 logical `opId` 與 server 期望的下一個 `deviceSeq` 建立全新的 current-epoch envelope；不得原地修改或在網路結果未知時自行重封。
- Client 收到重複事件時必須安全忽略。

### 8.4 衝突規則

- 各欄位採 Hybrid Logical Clock（HLC）的 LWW register，不使用整份 snapshot revision 判斷新舊。
- 刪除以 `present=false + HLC` tombstone 表達；不得直接移除後讓舊 replica 復活資料。
- collection membership 以每個 membership 的 LWW boolean 表達。
- `ReadingProgressSet.position` 比較 `(resetEpoch, HLC)`；同一 epoch 由最新 HLC 勝出，不能用最大頁碼合併，否則回頭重讀會被舊的較大頁碼覆蓋。
- 只有明確的「重設閱讀進度」會遞增 `resetEpoch`；「標記未讀」只更新獨立的 read LWW register，沿用現有保留位置的語意。
- 讀到章末時，以同一個 `ReadingProgressSet` 原子更新 position、history 與 read register。`completed` 只能是其 materialized 結果，不能成為另一個獨立權威欄位。
- `Chapter.lastPageRead`、History 與 Continue Reading 都是同一份 `ReadingProgressSet` 的 materialized view，不可各自競爭或分開發布。
- HLC tie-breaker 使用 device ID，確保 deterministic merge。

### 8.5 跨裝置實體識別

所有可同步 entity 都使用版本化 `SyncEntityKey`，不只 ReaderPosition：

```text
SyncEntityKey(version, entityType, namespace, canonicalValue)
```

- Manga／Chapter：優先使用未來新增的 extension canonical-ID contract；fallback 使用 versioned source identity + normalized URL。
- Category：建立時產生可攜 UUID，名稱不是 identity。
- History／ReadingProgress：引用 Manga／Chapter 的 SyncEntityKey。
- Membership／bookmark／tombstone：引用相同 key，不引用本機 Long ID。
- 本機持久化 `SyncEntityKey ↔ local ID` mapping；發現 collision 時停止該 entity 同步並要求修復，不可靜默合併。
- URL normalization 或 plugin canonical-ID 規則變更時必須提高 key version，並以明確 remap event 遷移。

Replica reducer 必須容許暫時 orphan 與 tombstone，因為遠端 events 可能亂序到達；只有 materializer 在依賴齊全後才產生符合 `AppSnapshot.validate()` 的 domain view。

### 8.6 Materializer ownership 與 cascade

`AppSnapshot` 同時包含跨裝置與裝置本機資料。Materializer 必須依下表合成，不能用 remote replica 整份覆蓋：

| AppSnapshot 區域 | 權威來源 | 規則 |
|---|---|---|
| `mangas`、`chapters` | SyncReplicaStore | 依 SyncEntityKey/HLC materialize；本機 Long ID 只由 identity map 配置 |
| `categories`、`mangaCategories` | SyncReplicaStore | Category 使用可攜 UUID；default category 永遠存在且不可 tombstone |
| `histories`、ReadingProgress view | SyncReplicaStore | 只在 Manga/Chapter parent 可 materialize 時輸出 |
| `extensionRepositories` | SyncReplicaStore | v1 identity 為 versioned canonical HTTPS base URL；fingerprint 改變需本機再次確認，不自動擴張 trust |
| `settings` allowlist | SyncReplicaStore | 只覆蓋下列 `portable-settings-v1` 欄位，其餘沿用本機值 |
| `backupState`、`updates` | 目前裝置 | 永不從 remote replica 覆蓋 |
| `downloadQueue` 與 page/manifests | 目前裝置 | 永不產生 sync event；保留本機 parent shell 以通過關聯驗證 |
| `tracks`、`trackerAccounts` | 目前裝置 | 第一版不同步；token 仍在 strict/platform secret store |
| extension packages、plugin KV/cookies | 目前裝置 | 不屬於 AppSnapshot sync projection |

`portable-settings-v1` 明確 allowlist：

- `general`：`languagePreference`、`dateFormat`、`defaultStartingScreen`
- `appearance`：`theme`、`amoledDark`、`tintColor`、`timestampFormat`、`relativeTimestamps`
- `library`：`sort`、`filter`、`categoryUpdateBehaviour`、`globalUpdateRestrictions`、`autoRefreshMetadata`
- `reader`：`readingMode`、`doubleTapToZoom`、`animatePageTransitions`、`showPageNumber`、三個 `skip*Chapters`、`colorFilter`、`splitTallImages`、`webtoonSidePadding`
- `tracking`：`autoSyncAfterRead`、`updateProgressAfterRead`
- `browse`：`checkExtensionUpdates`、`showNsfwSources`、`enabledLanguages`

明確排除 `downloads.*`、`sync.*`、`security.*`、`advanced.*`、`backupState`，以及 `confirmBeforeClosing`、library display/columns/defaultCategory/download-only、reader orientation/keep-screen/fullscreen/volume keys、`pinnedSourceIds`。Allowlist 版本改變時使用新的 setting schema/min-writer gate；舊 client 不得用整個 settings object 覆寫未知欄位。

Dependency/cascade 規則：

1. 遠端 child 先到時保存在 replica orphan table，不輸出到 AppSnapshot；parent 到達後以 deterministic key order 重新 materialize。
2. 「移出書庫」是 `Manga.favorite=false`，不是 Manga tombstone。
3. 真正刪除 Manga 的 mutation batch 必須同時為其 synced Chapters、ReadingProgress/History 與 category memberships 寫 tombstone；刪除 Chapter 同時 tombstone 對應 ReadingProgress/History；刪除 Category 同時將 memberships 設為 false。Reducer 不可依到達順序自行猜測 cascade。
4. 若 tombstoned parent 仍被本機 `downloadQueue`、`updates` 或 `tracks` 引用，LocalSyncStore 建立不回傳遠端的 local parent shell，保留必要 Manga/Chapter record，但不顯示在書庫、history 或分類。最後一個本機 dependency 消失後才移除 shell。
5. 較新的合法 resurrection 依各 entity/tombstone HLC 決定；parent 復活不會自動復活已明確 tombstone 的 children。
6. Materializer 每次 commit 後必須通過 `AppSnapshot.validate()`；若無法滿足，保留 replica/orphan 並報錯，不得刪除 device-local collections 來強行通過。

## 9. 閱讀接力

### 9.1 ReadingProgress 與 ReaderPosition

```text
ReadingProgress
├── contentKey（Manga／Chapter SyncEntityKey）
├── position
│   ├── readingMode
│   ├── pageIndex
│   ├── normalizedOffsetFraction
│   └── resetEpoch
├── readState
├── historyTouchedAt
├── hlc
├── deviceId
└── sessionId
```

- 分頁閱讀器的 `normalizedOffsetFraction` 固定為 0。
- Webtoon 保存第一個可見 page index，以及該 page 內 0～1 的正規化 offset；不能同步 raw pixel offset。
- `contentKey` 必須跨裝置穩定並遵守 8.5 的版本化 identity 規則；不能只使用本機自增資料庫 ID。

### 9.2 A 裝置發布

1. Reader page／scroll callback 建立 ReadingProgress mutation。
2. 每次 settled change 以同一個 LocalSyncStore transaction 更新 replica state、HLC 與尚未 sealed 的 durable draft。
3. Remote reporter 合併同一 reader session 尚未 sealed 的 draft，通常最多每 1～2 秒 seal 並上傳一次。
4. page change、關閉 Reader、切換章節或 App 進背景時觸發 flush。
5. Background flush 順序固定為：

```text
await reader mutation
→ commit replica state + durable draft transaction
→ materialize and flush repository view
→ seal latest draft as immutable outbox event
→ attempt remote outbox flush with bounded timeout
```

若 remote flush 被 OS 中止，本機 outbox 仍保留，下一次 foreground 重試。

### 9.3 Server commit 與通知

1. Worker 預先驗證短效 capability、workspace membership、大小與簽章；這只是快速拒絕，不是最後 authorization boundary。
2. WorkspaceHub 套用 rate limit。
3. D1 append transaction 再次 conditional assert device/membership auth epochs、active key epoch 與 capability binding，才 insert event 並取得 workspace sequence。
4. 只有 D1 commit 成功後，WorkspaceHub 才 broadcast 密文事件。
5. A 收到 `{workspaceSeq, headSeq}` receipt 後才移除 outbox item。

### 9.4 B 裝置接續

- App cold start／foreground 時立即以本機 cursor 呼叫 catch-up。
- Continue Reading 使用 history 中真正最後閱讀的 chapter，不再選「第一個未讀章節」。
- 若 WebSocket 已連線，直接套用 broadcast event；若 sequence 有 gap，再用 REST 補齊。
- B 尚未進 Reader 時，Continue Reading 更新為最新位置。
- B 已在同一章閱讀時不得強制跳頁；顯示「另一臺裝置有較新的位置」並讓使用者選擇套用。
- Incognito 模式完全不建立任何 reading-activity event，包括 position、history touch 與自動 read completion。

## 10. API 草案

```http
GET    /v1/capabilities

POST   /v1/setup/claim
POST   /v1/admin/invites
POST   /v1/invites/redeem

POST   /v1/auth/challenge
POST   /v1/auth/token

POST   /v1/pairings
POST   /v1/pairings/{id}/candidate
POST   /v1/pairings/{id}/approve
GET    /v1/pairings/{id}

GET    /v1/devices
POST   /v1/devices/{id}/revoke

POST   /v1/workspaces/{id}/key-rotations
POST   /v1/workspaces/{id}/key-rotations/{rotationId}/commit
POST   /v1/workspaces/{id}/key-rotations/{rotationId}/ack

POST   /v1/workspaces/{id}/events
GET    /v1/workspaces/{id}/events?after={workspaceSeq}&until={workspaceSeq}&limit={limit}
GET    /v1/workspaces/{id}/stream
GET    /v1/workspaces/{id}/bootstrap

POST   /v1/workspaces/{id}/checkpoint-leases
PUT    /v1/workspaces/{id}/checkpoints/{checkpointId}
POST   /v1/workspaces/{id}/checkpoints/{checkpointId}/commit
POST   /v1/workspaces/{id}/checkpoints/{checkpointId}/ack
GET    /v1/workspaces/{id}/checkpoints/{checkpointId}

POST   /v2/workspaces/{id}/blob-upload-sessions
GET    /v2/workspaces/{id}/blob-upload-sessions/{sessionId}
PUT    /v2/workspaces/{id}/blob-upload-sessions/{sessionId}/chunks/{chunkIndex}
POST   /v2/workspaces/{id}/blob-upload-sessions/{sessionId}/commit
GET    /v2/workspaces/{id}/blobs/{blobId}/manifest
GET    /v2/workspaces/{id}/blobs/{blobId}/chunks/{chunkIndex}
POST   /v2/workspaces/{id}/blobs/{blobId}/envelopes
POST   /v2/workspaces/{id}/blobs/{blobId}/tombstone
POST   /v2/workspaces/{id}/blobs/{blobId}/tombstone/acks
POST   /v2/workspaces/{id}/blobs/{blobId}/tombstone/revival
POST   /v2/workspaces/{id}/blob-gc
```

Catch-up 開始時 client 固定本輪 `until=headSeq` watermark，逐頁取得：

```json
{
  "fromExclusive": 120,
  "untilInclusive": 180,
  "nextCursor": 160,
  "hasMore": true,
  "headSeq": 185,
  "stableCheckpointSeq": 100,
  "events": []
}
```

Client 只有在整頁 events 解密、驗簽、寫入 replica store 並 materialize 成功後，才原子前移 `nextCursor`。完成固定 watermark 後若 `headSeq` 已增加，再開始下一輪；WebSocket 發現 workspace sequence gap 時也走相同 REST 流程。

WebSocket 訊息最小集合：

```text
hello(headSeq, stableCheckpointSeq)
event(workspaceSeq, encryptedEnvelope)
checkpointAvailable(throughSeq)
resyncRequired(stableCheckpointSeq, headSeq)
reauthRequired
```

`/bootstrap` 回傳目前 head、retained stable checkpoint headers／hashes／下載位置及所需 key epochs。Client 優先使用最新一份，驗證失敗時可退回 recovery base。若 client cursor 已被 GC，catch-up API 回傳 `410 checkpoint_required` 與 retained stable 清單；client 下載、驗證並原子安裝 checkpoint 後，從其 `throughWorkspaceSeq` 繼續。

## 11. 客戶端架構

### 11.1 新增共用元件

建議在 `composeApp/src/commonMain/.../sync/v2/` 建立：

```text
SyncEngine
├── SyncSessionStore（非秘密 session metadata）
├── SyncSecretStore（strict／fail-closed）
├── LocalSyncStore（SQLite/WAL）
│   ├── SyncReplicaStore + CRDT metadata
│   ├── SyncEntityKey ↔ local ID mapping
│   ├── durable drafts + sealed outbox
│   └── cursors + retained key epochs
├── SyncEventCodec
├── SyncCrypto
├── CloudflareSyncApi
├── RealtimeWorkspaceClient
├── SyncReducer
├── SnapshotMaterializer
└── ReaderProgressReporter
```

- `SyncSessionStore`：保存 endpoint、instance/workspace/device ID 與 non-secret capability metadata；不得放入 `AppSnapshot`。
- `SyncSecretStore`：以 typed key 保存 device credential、private keys、workspace epoch keyring；必須區分 missing／unavailable／corrupt 並 fail closed。
- `LocalSyncStore`：建議採 SQLDelight/SQLite WAL。一次 transaction 內提交 domain mutation、HLC、identity mapping、draft/outbox 與 cursor；成功 receipt 後才刪除 sealed outbox。
- `SyncReplicaStore`：保存 domain values 以及 field clocks、tombstones、reset epochs、orphan records，並作為 synced fields 的本機權威。
- `SyncReducer`：純函式、可重播、可測試，先套用至允許 orphan 的 replica store，不能直接把任意順序 event 寫入要求完整關聯的 `AppSnapshot`。
- `SnapshotMaterializer`：依 dependency/cascade 規則將 replica state 投影成通過 validation 的 `AppSnapshot`；中途 crash 可由 replica journal idempotent 重建。
- `ReaderProgressReporter`：conflated/debounced position 上報與 lifecycle flush。
- `SyncCrypto`：共用介面；平台實作負責安全 key generation／storage，不允許明文檔案 fallback；保留從 recovery base 到 head、retained stable fallbacks 及本機 pending envelopes 所需的 epoch keys。只有 recovery base 已前移且沒有本機 outbox 引用時才能淘汰舊 key。

現有 Android Keystore、iOS/macOS Keychain 與 Windows DPAPI primitives 可重用，但不可直接重用會依 key 名猜測敏感性或在 capability 不足時回退明文的 permissive plugin adapter。Sync secrets 必須走獨立 typed API，不靠 `token/password/secret` substring 判斷。

這個 SQLite write-ahead 邊界是離線正確性的必要條件：若程序在 replica commit 後、`AppSnapshot` materialize 前終止，啟動時重播即可補上 view；若同一 mutation 被重播，field HLC 與 op ID 使它保持冪等。不可用兩個各自 atomic 的 JSON 檔案宣稱 state/outbox 是同一 transaction。

Seal 也有獨立的原子邊界：LocalSyncStore 必須在同一 transaction 內 CAS `nextDeviceSeq`、配置 event ID／nonce、產生 canonical header/ciphertext/signature、插入 sealed outbox row、消耗對應 draft，最後才遞增 next sequence。若 transaction 前 crash，sequence 未消耗；commit 後 crash，完整 sealed row 必然存在。不得先遞增 counter 再非同步寫 event。若平台 crypto API 無法在 transaction 內執行，改用持久化 `SEALING` intent，且後續 sequence 不得越過它，啟動時必須先完成同一 intent。

### 11.2 Checkpoint 安裝與本機 rebase

Cursor 被 GC 的裝置可能同時持有未取得 receipt 的本機修改。安裝 checkpoint 不得直接覆蓋 LocalSyncStore：

1. 將 checkpoint 下載到 temporary store，驗證 canonical header、ciphertext hash、device signature、key commitment 與 checkpoint chain。
2. 在 temporary replica 獨立套用 checkpoint，再分頁重播至固定 remote head；完成前不改目前 cursor／UI state。
3. 取得所有本機 pending logical operations、durable drafts 與 sealed outbox。網路結果不明的 sealed event 先查 receipt/high-watermark，不能假設成功或失敗。
4. 在單一 LocalSyncStore transaction 安裝 remote replica base，以原本 `opId/HLC` 重新套用仍 pending 的 logical operations，保留 drafts/outbox、keyring 與 device-local state，最後才更新 cursor。
5. 同一 state-based op 已存在於 checkpoint 時，重播必須冪等；sealed outbox 在收到可驗證 receipt 前仍保留。只有明確 `stale_key_epoch` 才依 8.3 規則重新 seal。
6. Transaction commit 後再 materialize `AppSnapshot`；失敗時繼續使用舊 replica，不可留下半套 checkpoint。

### 11.3 現有程式調整

- [`SnapshotSyncController.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/sync/SnapshotSyncController.kt)：保留 legacy snapshot sync；Cloudflare v2 不繼承單檔 `readSnapshot/writeSnapshot` contract。
- [`SnapshotBackupService.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/backup/SnapshotBackupService.kt)：Cloudflare active 時 restore/import 不得直接 `replaceSnapshot`；必須走下述 bulk mutation policy。
- [`Chapter.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/domain/model/Chapter.kt)：`lastPageRead/read` 改為 ReadingProgress materialized view，不再是跨裝置權威。
- [`AppSnapshot.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/data/AppSnapshot.kt)：維持有效 domain projection；CRDT metadata、orphan 與 outbox 不塞入現有 portable snapshot。
- [`AppStateModels.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/domain/model/AppStateModels.kt)：為 portable settings 建立 explicit allowlist，排除路徑、sync endpoint/session、security capability、proxy secret 與裝置偏好。
- [`ReaderScreen.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/ui/screens/ReaderScreen.kt)：paged 回報 logical index；Webtoon 回報及恢復 normalized page offset。
- [`ShinsouRepository.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/data/ShinsouRepository.kt)：由 LocalSyncStore transaction 驅動 synced mutation，再 materialize position/history；提供可 await 的 view flush。
- [`RepositoryPluginCoordinator.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/RepositoryPluginCoordinator.kt)：來源 refresh 時保留新的 progress metadata。
- [`SnapshotConflictResolver.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/sync/SnapshotConflictResolver.kt)：維持 legacy merge；v2 tombstone／field HLC 完全由 SyncReducer 與 SyncReplicaStore 處理。若日後要替 iCloud 補 tombstone，必須另做 AppSnapshot／BackupEnvelope schema migration，不能只改 resolver。
- [`ShinsouApp.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/ui/ShinsouApp.kt)：接入 Reader reporter、close/background flush、foreground catch-up 與精確 Continue Reading。
- [`AppServices.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/ui/AppServices.kt)：提供 QR／一次性連結分享、掃描與 manual-code fallback 的平台能力。
- [`SettingsScreen.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/ui/screens/SettingsScreen.kt)：改為 provider-neutral 的 `NOT_CONFIGURED / DEPLOYING / LINKING / READY / REVOKED / ERROR` 狀態。
- [`DeepLinkParser.kt`](../composeApp/src/commonMain/kotlin/dev/shinsou/kmp/navigation/DeepLinkParser.kt)：加入 setup／invite／pair route，保留並安全解析 query。
- Android、iOS、Desktop composition root：首次啟動產生真正隨機 installation/device ID，注入 strict SyncSecretStore 與 Cloudflare client。
- Android／iOS：加入 QR scanner/renderer、camera permission 與用途說明；使用者拒絕相機時仍可貼上一次性連結／短碼。
- macOS／Windows：加入 QR renderer、貼上／短碼 fallback 與 installer OS protocol handler；沒有 camera scanner 也不得阻塞配對。

Cloudflare active 時，backup restore、import、clear/reset 與任何 bulk replacement 只能採以下其中一條明確路徑：

1. 「還原並同步到所有裝置」：先對 SyncReplicaStore 計算 selection-aware diff，將新增／更新／刪除轉成 HLC mutations/tombstones，在 LocalSyncStore transaction 寫入 replica + drafts，再分批上傳；device-local collections 依 ownership table 在本機處理。
2. 「只在此裝置還原」：先離開／解除目前 workspace，再使用既有 local `replaceSnapshot`；UI 必須警告此裝置不再接收該 workspace 更新。

直接 `replaceSnapshot` 只允許 sync disabled、首次建立 v2 replica，或由 SnapshotMaterializer 內部更新 projection。Full reset 也必須讓使用者選擇「跨裝置 tombstone reset」或「離開 workspace 後本機 reset」，不可繞過 clocks/outbox。大型 restore 若超過 batch/quota，保持 durable drafts 並顯示進度，不得先宣稱遠端已完成。

離開 workspace 時，content transaction store 必須在刪除 session／workspace keys 前，原子移除該
`instanceId + workspaceId` 所擁有的 publication replica cursors、`replica:` replay journal 與尚未
消費的 blob-removal intents。Publication、Acquisition、Unit、manifest refs、rights 與本機 body
仍保留；GC intent 是舊 authority 的工作憑證，不得帶入下一個 workspace。清理失敗時 departure
中止並保留憑證以便重試；成功後同一 publication 才能由新 authority 以空 cursor 重新
materialize，而不會永久落入 cross-authority CAS conflict。

### 11.4 新增依賴決策

實作前先以 spike 鎖定並記錄版本：

- Ktor WebSocket client。
- SQLDelight 或另一個具 Android／iOS／JVM driver 的 SQLite transaction layer。
- RFC 8949 deterministic CBOR codec；不可假設一般 JSON/CBOR encoder 自動 canonical。
- 經審查的 Ed25519、X25519/HPKE、HKDF、AEAD KMP library 或平台 adapter，並以跨平台 test vector 驗證。
- QR renderer/scanner；無論選哪個 library，都必須保留貼上連結／手動短碼 fallback。

相依性加入 [`composeApp/build.gradle.kts`](../composeApp/build.gradle.kts) 後，Android、iOS Simulator、macOS、Windows/JVM 都必須有 compile/test 路徑。

### 11.5 建議伺服器目錄

```text
syncWorker/
├── src/
│   ├── worker.ts
│   ├── auth/
│   ├── api/
│   ├── crypto/
│   ├── durable-objects/WorkspaceHub.ts
│   └── storage/
├── migrations/
├── test/
├── wrangler.jsonc
├── package.json
└── README.md
```

App 與 Worker 以明確的 `protocolVersion`、`minReaderVersion`、`minWriterVersion` 協商，不共享未版本化的內部 model。

## 12. 安全設計

### 12.1 金鑰與 token

- Data encryption：protocol v1/v2 固定使用 ChaCha20-Poly1305；v2 另為 event、checkpoint、body
  chunk、private body manifest 與 DEK envelope 使用不同 domain/AAD。Worker 與 App 都必須拒絕其他
  `cipherSuite`，不能把未實作的算法名稱送進 ChaCha primitive。未來增加 AES-256-GCM 時必須
  升級並協商 protocol contract，且所有平台先具備真正的 AES-GCM 實作與跨語言 vectors。
- Device authorization：Ed25519。
- Device key wrapping：X25519 + HKDF + AEAD，優先採標準 HPKE。
- 每個 workspace 的每個 key epoch 都使用全新、彼此獨立的隨機 256-bit epoch key；不得把可推導未來 epochs 的長期 root key 交給任何裝置。
- Event key 與 checkpoint key 只由當前 epoch key 透過 HKDF 分離；撤銷／rotation 時將新隨機 epoch key 包裝給有效 devices 與 recovery public key。任何舊 epoch key 或已撤銷裝置持有的材料都不能推導未來 key。
- device／invite／pairing token 至少 192-bit，建議 256-bit。
- Server 只保存長期 device credential 的 hash；App 靜默以短效 challenge + device signature 換取 access token，再換取最長約 5 分鐘的 workspace capability。這不增加任何使用者操作。
- 一般 event API 使用短效 workspace capability；pair、revoke、rotate、recovery 等控制操作另外要求 device signature。
- Capability 綁定 `deviceId/workspaceId/authEpoch/keyEpoch/expiry`。每次 append 驗證 capability epoch；舊 key epoch 的新 event／checkpoint 一律拒絕。
- 撤銷順序固定為 D1 commit → WorkspaceHub invalidation/disconnect。若即時 invalidation 失敗，capability TTL 是最長殘留時間。
- WebSocket credential 放在 `Authorization` header，不放 URL query，避免進入 access log。

### 12.2 AEAD associated data

Event AAD 的精確欄位已在 8.1 定義，使用 client 已知的 `deviceSeq`，不包含 server commit 後才配置的 `workspaceSeq`。Checkpoint 使用獨立 clear header，至少包含：

```text
envelopeVersion
protocolVersion
schemaVersion
cipherSuite
nonce
instanceId
workspaceId
checkpoint ID
deviceId
throughWorkspaceSeq
keyEpoch
previousStableCheckpointHash
```

Checkpoint AEAD AAD 與 device signature 同樣使用 deterministic CBOR header；signature 另覆蓋 ciphertext hash，避免 server 或中間人替換 public key、tenant、epoch、sequence 或 payload。

### 12.3 明確安全邊界

- E2EE 隱藏內容，但不隱藏 workspace/device ID、請求時間、大小與頻率。
- 惡意或損壞的 server 仍能刪除、回滾密文或拒絕服務；client 需檢查 sequence、hash、簽章與 checkpoint chain。
- Recovery Kit 被竊等同 workspace 被接管；可在後續提供額外 passphrase 模式。
- Recovery Kit 只能恢復金鑰，不能在 D1/R2 被刪除後憑空恢復 ciphertext；instance admin 仍需備份 Cloudflare 資料。
- 所有 log 禁止記錄 bearer token、invite／pairing secret、QR payload、解密內容及完整 ciphertext。

## 13. Quota 與濫用防護

私人部署的建議預設值：

```text
users / deployment                  25
workspaces / user                    1（MVP）
devices / user                      10
concurrent WebSockets / device       2
concurrent WebSockets / workspace   20
event body                          32 KiB
batch body                         256 KiB
events / device / minute            60，burst 20
events / workspace / minute        300
checkpoint body                     32 MiB
content blob plaintext             128 MiB
content blob chunk                   8 MiB（ciphertext 另加 AEAD tag）
content private manifest           512 KiB
content chunks / blob                 1024
workspace stored ciphertext        250 MiB
retained checkpoints                 3
```

- 預設 invite-only。
- Auth／invite／pairing 依 IP 做 edge rate limit。
- Realtime events 依 device／workspace 在 Durable Object 做 token bucket；Lite 以 Worker edge gate 加 D1 原子 quota counter 執行等價限制。
- 讀取 request body 前先檢查 `Content-Length`，串流過程仍設 hard cap。
- `workspaces.committed_bytes/reserved_bytes` 與有效 reservation 是 quota 權威，`usage_daily` 只供觀測。Event insert／GC 在同一 D1 transaction 加減 committed bytes。
- Checkpoint upload 前先以 D1 CAS 建立有期限的 byte reservation；R2 commit 後按實際大小結算，失敗／過期則釋放，避免並行上傳繞過 quota。
- Content body upload 使用同一 `workspaces.committed_bytes/reserved_bytes` authority；session reserve
  綁 exact chunk plan、private-manifest hash 與初始 DEK envelope，不能用平行 sessions 超賣 quota。
- 429 時 client 只更新同一 ReadingProgress 尚未 sealed 的最新 draft，不累積每一個中間翻頁事件。
- 超過儲存 quota 回傳 507；本機修改與 outbox 不得因此刪除。
- Cloudflare 免費方案額度可能調整，部署頁必須連到官方 pricing 並顯示實際資源用量，而不是硬編碼「永久免費」。

## 14. 失敗與恢復策略

| 情境 | 行為 |
|---|---|
| 無網路 | 本機照常使用；事件留在 outbox |
| Worker／DO 暫時失敗 | 指數退避；DO 不可用時只允許 D1 REST catch-up，待送寫入保留在 outbox，不能繞過授權直接寫 D1 |
| WebSocket 斷線 | 帶 cursor 重連；發現 gap 後 REST 補齊 |
| 重複上傳 | 依 event ID／deviceSeq 回既有 receipt |
| App 被強制終止 | 已持久化 outbox 在下次啟動重送 |
| Cursor 已被 GC | 下載 stable checkpoint，再拉取其後 events |
| Candidate checkpoint 損壞 | 不升級為 stable；保留上一份 checkpoint 與 event tail |
| Outbox 使用舊 key epoch | 先查 receipt/high-watermark；只有 server 明確回未 commit 的 `stale_key_epoch` 時，才以目前 epoch 重新 seal 同一 logical op |
| 裝置撤銷 | D1 auth epoch 立即更新、Hub 收到 invalidation 後斷線，短效 capability TTL 提供失效上限，接著輪替 key epoch |
| D1/R2 資料遺失 | 由 instance 備份恢復；Recovery Kit 本身不能恢復 ciphertext |
| 新 client 寫入舊 client 不理解的 schema | 舊 client 停止發布，避免忽略新欄位後覆寫資料 |

## 15. 部署 Profiles

提供相同 schema 與 protocol 的兩個 profile：

### Realtime（預設）

```text
Worker + D1 + WorkspaceHub Durable Object + R2
```

適用於本計畫要求的秒級閱讀接力。

### Lite（降級模式）

```text
Worker + D1 + R2
```

不維持 WebSocket。App 開啟／foreground 時仍會立即 catch-up；兩臺同時使用則採 adaptive polling。Lite 的 event append 仍執行 D1 auth/key epoch check、per-workspace sequence allocation 與 idempotency；checkpoint lease 以 D1 unique active lease／CAS 實作，rate limit 以 edge gate + D1 counter 實作。Lite 與 Realtime 不搬移資料，只透過 `/v1/capabilities` 切換 client 行為。

Cloudflare 免費額度適合私人、家庭與小群組，不應用來承諾公開 SaaS 的容量或 SLA。參考：

- [Cloudflare Deploy Buttons](https://developers.cloudflare.com/workers/platform/deploy-buttons/)
- [Cloudflare Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
- [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/)

## 16. 現有 iCloud Sync 遷移

1. 升級後不自動把既有 iCloud 使用者切到 Cloudflare。
2. 使用者選擇 Cloudflare 並完成配對後，client 以目前本機 snapshot 建立第一份 v2 加密 checkpoint。
3. 首次 upload 成功且重新下載驗證後，才將 Cloudflare 設為 active sync provider。
4. 同一安裝一次只能有一個 active sync provider，避免 iCloud snapshot merge 與 Cloudflare events 互相回寫形成循環。
5. 啟用 Cloudflare 後停用 iCloud 自動 merge；跨系統搬移使用現有 portable backup export/import。若要新增 iCloud 手動匯出入口，視為另一項待實作功能，不能把現況描述為已支援。
6. Provider 遷移本身不自動加入 downloaded cache、Local source container bytes、extension packages 或
   secrets；之後只有通過 rights gate 且由使用者選擇的 immutable content bodies 才可另行排入 M6
   encrypted body sync，UI 必須事前說明。

## 17. 實作階段

### Phase 1：基礎身份與部署

- Worker／D1／R2／DO skeleton
- numbered migrations 與 `/v1/capabilities`
- bootstrap claim、invite-only、多 user／workspace 隔離
- 依賴 spike、`SyncSessionStore`、strict `SyncSecretStore`、隨機 device ID
- 第一臺裝置與新增使用者流程

完成條件：單一 deployment 能建立至少兩位完全隔離的使用者；重啟 App 後 session 仍可用，server 無明文領域資料。

### Phase 2：裝置配對與事件同步

- QR／deep link pairing、短碼確認、裝置撤銷
- SQLite/WAL `LocalSyncStore`、transactional draft/outbox/cursor
- `SyncEntityKey` mapping、HLC replica reducer、orphan、tombstone、key epoch
- deterministic CBOR envelope、per-workspace sequence 與 GC-safe idempotency
- 現有 snapshot 到完整初始 SyncState/checkpoint 的轉換

完成條件：離線修改、crash reconciliation、重試、重播與 out-of-order events 都得到 deterministic state；materialized AppSnapshot 永遠通過 validation；任一裝置可單獨撤銷。

### Phase 3：精確閱讀接力

- `ReadingProgress`、ReaderPosition 與 stable content key
- paged／RTL／vertical／Webtoon round-trip
- Reader close／background bounded flush
- foreground catch-up 與精確 Continue Reading
- Incognito 排除

完成條件：正常網路下 A 翻頁後 B 通常能在 1～2 秒收到；Webtoon 可回到同一頁內近似位置；往回閱讀與 reset 不會被舊的較大頁碼覆蓋。

### Phase 4：Realtime 與 checkpoint

- hibernatable WorkspaceHub WebSocket
- D1-commit-before-broadcast
- client checkpoint lease／upload／validate／ack
- stable/candidate pointer、event receipt GC、quota reservation、Lite fallback

完成條件：DO 重啟或停用不造成資料遺失；舊 cursor 能以 stable checkpoint 恢復；壞 checkpoint 不會淘汰最後可用資料。

### Phase 5：恢復與強化

- Recovery Kit
- revoke 後 key rotation
- D1/R2 export／restore 工具
- admin quota／usage UI
- 協定與 schema compatibility gate
- 端到端安全與壓力測試

### Phase 6：統一內容與 encrypted body plane

- Publication／Acquisition／Unit／Manifest／annotation／unified progress schema v2 mutations
- shared SQLite content transaction、durable metadata outbox 與 exact blob-job journal
- R2 resumable encrypted chunk upload/download、DEK re-wrap、tombstone ack 與 GC
- Backup v2 與 [ShuYue](https://github.com/aluo96078/shuyue) transactional import 只經同一 content transaction／v2 outbox 進入同步
- body scheduler 每 slice 一個 blob，且永遠排在 reader progress 與 metadata 後

完成條件：body 不進 event/checkpoint；upload 每個 crash boundary 可重播；舊 epoch envelope 可由
retained recovery path 解密；沒有 stable-checkpoint ack 就不能 GC；冷啟動與 reader path 不等待 body。

## 18. 必要測試與驗收

### 共用 client tests

- HLC deterministic ordering 與 tie-break
- field-level LWW、tombstone、membership merge
- ReadingProgress 往前、往回、mark unread、reset、chapter completion 衝突
- paged／RTL／vertical／Webtoon position encode／restore
- LocalSyncStore transaction、orphan materialization、crash reconciliation
- draft seal 的 nextDeviceSeq CAS/crash recovery/不可變性、outbox coalescing、retry 與 receipt ordering
- checkpoint install 時保留並 rebase pending drafts／sealed outbox／device-local fields
- SyncEntityKey normalization、collision、version remap 與 local-ID mapping
- Materializer ownership/cascade、local parent shell 與 `AppSnapshot.validate()` 一致性
- deterministic CBOR／AEAD／signature 的 Android、iOS、JVM 共用 test vectors
- Event/checkpoint canonical header 經 D1/R2 round-trip 後 bytes 完全相同且仍可驗簽
- 新 epoch key 的隨機性與不可由任一舊 epoch material 推導
- strict SyncSecretStore 的 missing／unavailable／corrupt fail-closed 行為
- background flush 必須等待 reader mutation 與 local persistence
- Incognito 不產生任何同步事件
- snapshot → 完整 v2 SyncState/checkpoint migration
- Cloudflare active 時 backup restore/import/reset 產生 mutations/tombstones，不走 direct replace
- content transaction 的 publication/manifest/blob job/outbox 必須同 commit，restart 後 exact replay
- blob upload commit、LocalSyncStore draft、content job ack、journal cleanup 四個 crash boundary 均可恢復
- tampered chunk/private manifest 在 publish 至本機 ContentBlobStore 前失敗
- key rotation re-wrap 保留相同 DEK、鏈結 previous envelope hash，重試不得產生不同 durable intent
- body scheduler 預設一次只處理一個 job，且 reader background flush 先完成

### Worker tests

- bootstrap 只能成功一次
- invite／pairing secret 過期、重播及 hash 驗證
- token 只能存取其 user／workspace
- 管理員沒有 membership 時不能讀取 workspace ciphertext
- event signature、連續 deviceSeq、per-workspace sequence、GC 後 idempotency 與 body limit
- 其他 tenant 寫入不得在本 workspace cursor 製造 gap
- Worker precheck 後插入 revoke/rotation，D1 conditional append 必須 rollback（TOCTOU test）
- revoke 後 API 與既有 WebSocket 都在定義的 invalidation/TTL 邊界內失效
- D1 commit 失敗時不得 broadcast
- R2 path traversal 不可能由 client input 形成
- checkpoint 完整 reducer metadata、candidate／ack／stable／GC 狀態機
- 同一 throughSeq 的 retry/rotation checkpoints 以不同 checkpointId 共存，所有 lease/ack/pointer 綁 exact hash
- Checkpoint validator 漏 replay 任一 event 時不得產生可 promotion 的 ack
- 最新 stable checkpoint 損壞時，以 recovery base + 保留 event delta 重建至 head
- 兩臺 proposer 競爭 rotation 時只允許一個 lease/manifest commit；recipient 驗證相同 key commitment
- 新裝置 keyring 必須能解密 retained recovery base 到 head 的每個 epoch
- checkpoint byte reservation、過期釋放與並行 quota 測試
- blob session reserve/status/chunk/commit exact-plan、跨 tenant、過期 capability、平行 quota 與 R2 rollback
- blob download hash/size、re-wrap CAS、跨裝置 canonical tombstone、stable-checkpoint ack/revival signature、
  全 active-device ack、revival/GC claim race、multi-cycle removal 與 GC receipt
- blob R2 key traversal 不可能由 client input 形成；D1 commit 前不得回報 remotely available
- Lite／Realtime protocol compatibility

### 跨裝置 smoke matrix

```text
Android ↔ iOS
Android ↔ macOS
Android ↔ Windows
iOS ↔ macOS
iOS ↔ Windows
macOS ↔ Windows
```

每組至少驗證：首次配對、前景即時接力、A background 後 B 接續、離線補送、Webtoon offset、裝置撤銷與 Recovery Kit。

## 19. 上線 Gate

以下條件全部完成前，不應把 Cloudflare Sync 標為 stable：

- 一鍵部署與 CLI fallback 均以全新 Cloudflare 帳戶實測。
- 多租戶 authorization 有自動化負向測試。
- Server log／D1／R2 掃描確認沒有 Vault Key、明文內容或原始 token。
- Android Keystore、iOS Keychain、macOS Keychain、Windows DPAPI 均完成重啟與失敗模式 smoke。
- A→B 接力延遲、離線恢復與大 event tail 有實測數據。
- D1 migration 可從前一正式版升級，並有 export／restore 演練。
- 至少一份 checkpoint 損壞時仍能從上一份 checkpoint 加 event tail 恢復。
- UI 清楚區分一般 download cache、未獲 rights 的 Local bodies、已明確加入 encrypted body sync 的
  content，以及永不同步的 credentials／extension executable packages。

## 20. 最終建議

第一版即採用 Realtime profile，但按 Phase 1～4 漸進交付；不要先發布以 R2 單檔覆寫為核心的多使用者版本，之後再重做事件同步。Worker + D1 + Durable Object + R2 的分工可以同時滿足簡單配對、懶人部署、秒級閱讀接力與單一部署多使用者，並保留 Lite 降級、備份復原及未來 shared workspace 的擴充路徑。
