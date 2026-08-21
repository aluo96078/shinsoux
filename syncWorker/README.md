# Shinsou X Cloudflare Sync Worker

這個目錄實作 `docs/CROSS_PLATFORM_SYNC_ARCHITECTURE.md` 的伺服器端。它是本機優先、端對端加密事件同步與內容 body plane；Worker 永遠不持有 workspace 明文金鑰，也不解析 event、checkpoint、content manifest 或 blob chunk 的明文內容。

## 元件與權威邊界

- **Worker**：驗證 bootstrap、device/access token、短效 workspace capability、Ed25519 signature、租戶與 body 上限，並提供 REST API。
- **D1**：使用者、裝置、membership/auth epoch、workspace head、event tail、永久 receipt hash、checkpoint pointer/lease/ack、blob upload/manifest/envelope/tombstone/GC、rotation/recovery 與 quota 的權威來源。
- **WorkspaceHub Durable Object**：每個 workspace 一個 hibernatable WebSocket fan-out、短期 token bucket 與 append serialization。它沒有唯一資料；DO 故障時 REST catch-up 仍可恢復。
- **CHECKPOINTS R2**：只保存 client 產生、壓縮並 AEAD 加密的 immutable `SyncState` checkpoint。
- **BLOBS R2**：與 checkpoint 完全隔離，保存 client 端加密的 private content manifest 與 resumable chunks。兩個 bucket 的 object key 都只由 Worker 依已驗證 UUID、chunk index 與 hash 組成，client 不能提交任意 R2 path。

Event commit 使用一條 conditional `INSERT … SELECT`，其 trigger 在同一 SQLite transaction 配置 per-workspace sequence、更新 head、device high-watermark、永久 receipt 與 committed bytes。這讓 revoke/rotation 發生在 Worker 預檢之後時，寫入仍會 rollback。DO 只有在 D1 commit 成功後才 broadcast。

## 目錄

```text
syncWorker/
├── migrations/               # 0001 control plane ～ 0005 encrypted body plane
├── src/
│   ├── api/                    # identity、events、checkpoint、blobs、rotation/recovery
│   ├── auth/                   # access/capability/control signature
│   ├── crypto/                 # RFC 8949 deterministic CBOR、hash、Ed25519
│   ├── durable-objects/        # WorkspaceHub
│   ├── storage/                # D1 statements/helpers
│   └── worker.ts               # 完整 router
├── test/                       # Node + 真實 SQLite migration/trigger tests
├── package.json
└── wrangler.jsonc
```

## 本機測試

測試不需安裝 npm package；Node 22.18 以上即可。Node 內建 SQLite 會真正套用 numbered migration，再測試 conditional event append、租戶隔離、TOCTOU revoke/rotation、GC-safe receipt、invite replay、checkpoint reservation/promotion、encrypted blob reserve/chunk/commit/re-wrap/tombstone/GC、rotation recipient CAS 與 recovery claim。

```bash
cd syncWorker
npm test
npm run smoke
npm run ops:test
npm run typecheck
```

本機啟動 Cloudflare bindings 需要 Wrangler：

```bash
cd syncWorker
npx wrangler@4 d1 migrations apply shinsou-sync --local
npx wrangler@4 dev --local
```

建立不會提交的 `syncWorker/.dev.vars`，至少提供：

```dotenv
BOOTSTRAP_SECRET=<32-byte-or-longer-base64url-secret>
TOKEN_PEPPER=<independent-high-entropy-secret>
RATE_LIMIT_SALT=<independent-high-entropy-secret>
```

`INSTANCE_ID` 必須改成此 deployment 專屬的 UUID。三個秘密必須彼此獨立；不要把它們放進 QR query、Git、log 或 crash report。
`PROTOCOL_VERSION/MIN_READER_VERSION/MIN_WRITER_VERSION` 與
`SCHEMA_VERSION/MIN_SCHEMA_READER_VERSION/MIN_SCHEMA_WRITER_VERSION` 是兩組獨立的相容性範圍；最低版本不得高於同組 current version。缺欄、非正整數或非法範圍一律 fail closed。

## Cloudflare 部署

### 瀏覽器範本

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/aluo96078/shinsoux/tree/master/syncWorker)

`wrangler.jsonc` 不固定任何 D1 ID，讓新版 Cloudflare dashboard／Wrangler 自動 provision 專屬 D1、private `CHECKPOINTS` R2 與另一個 private `BLOBS` R2 binding。瀏覽器流程仍必須確認三個值以 **encrypted secrets** 保存、`INSTANCE_ID` 已換成隨機 UUID，並套用 `migrations/`。兩個 binding 不可指向同一 bucket；不同帳戶政策可能禁止自動啟用 R2，遇到這種情況應使用下方 CLI fallback，不要把 secret 改放明文 `vars`。

此按鈕與全新帳戶的實際 Cloudflare 授權畫面仍屬上線 gate；本機測試不能代替真實帳戶演練。

### 一條指令的 CLI fallback

CLI 預設只顯示計畫，必須明確加入 `--apply` 才會登入 Cloudflare、建立／重用 D1 與兩個隔離的 R2 bucket、執行 migration、部署 Worker 並以 stdin 安裝三個 encrypted secrets：

```bash
cd syncWorker
npm run ops -- deploy
npm run ops -- deploy --apply
```

它具有以下安全行為：

- D1、CHECKPOINTS R2 與 BLOBS R2 先依名稱查詢，重跑不會任意建立第二份資源。
- 生成的 config 一律同時包含 `CHECKPOINTS` 與 `BLOBS`，而且拒絕兩個 binding 共用同一 bucket；否則 `/v1/capabilities` 會把 encrypted body plane 標成停用。
- `INSTANCE_ID` 與三個 secret 在重跑時保持不變；換掉 `TOKEN_PEPPER` 會讓既有 credential 失效，因此工具絕不在每次執行重新生成。
- secret 不進命令列、stdout、Wrangler config 或 Git。只允許寫到 Git checkout 外；預設位置為 `~/.config/shinsou/cloudflare/<worker>/`，目錄權限 `0700`、檔案 `0600`，既有檔案權限過寬、內容過短或三個值重複時會拒絕部署。
- 結束時只印出不含 secret 的 `/setup` URL 與 terminal QR；bootstrap secret 的受保護檔案路徑會另行顯示，由 App 本機讀取／貼上。
- 若帳戶已經有資源，可傳 `--database-id <uuid>`；自訂名稱使用 `--worker`、`--database`、`--checkpoints-bucket`、`--blobs-bucket`。舊 `--bucket` 僅保留為 `--checkpoints-bucket` 的相容 alias。

成功後生成的 `wrangler.generated.jsonc` 位於上述外部 state 目錄。後續 export／restore 應用 `--config` 指向該檔；它不含 secret。

手動流程仍可使用 `wrangler secret put`（不要使用明文 `vars`）：

   ```bash
   npx wrangler@4 secret put BOOTSTRAP_SECRET
   npx wrangler@4 secret put TOKEN_PEPPER
   npx wrangler@4 secret put RATE_LIMIT_SALT
   ```

再套用 numbered D1 migration並部署 Worker：

   ```bash
   npx wrangler@4 d1 migrations apply shinsou-sync --remote
   npx wrangler@4 deploy
   ```

5. 開啟 `/setup`，由 App 呼叫 `/v1/setup/claim`。第一次成功 claim 會原子建立 admin user、private workspace、第一臺 device 與 recovery public-key envelope；之後相同或任何其他 bootstrap claim 永久回覆 conflict。

建議部署完成後保留 Cloudflare secret 但以 D1 `installation` 為不可逆 claim gate。若刪除 D1，Recovery Kit 無法憑空恢復已遺失的 ciphertext；D1、CHECKPOINTS R2 與 BLOBS R2 必須視為同一份可恢復資料集。

## D1／雙 R2 匯出與還原

備份必須同時包含 D1 SQL、CHECKPOINTS 與 BLOBS 中的每個 ciphertext，以及各自的 `httpMetadata/customMetadata`。checkpoint 與 body commit 驗證都依賴 exact metadata；用一般 S3 copy 或單獨的 `wrangler r2 object get/put` 搬檔可能遺失這個安全資訊，不能視為有效備份。

先執行 dry-run，再在 App／部署沒有寫入的維護時段匯出到 **Git checkout 外** 的新目錄：

```bash
npm run ops -- export \
  --config ~/.config/shinsou/cloudflare/shinsou-sync/wrangler.generated.jsonc \
  --output /absolute/private/path/shinsou-sync-2026-08-20

npm run ops -- export --apply \
  --config ~/.config/shinsou/cloudflare/shinsou-sync/wrangler.generated.jsonc \
  --output /absolute/private/path/shinsou-sync-2026-08-20

npm run ops -- verify-backup \
  --input /absolute/private/path/shinsou-sync-2026-08-20
```

匯出先取得 D1 snapshot，再列出／下載兩個 R2；這個順序允許 R2 有無害的額外 immutable object，卻不應讓 D1 指向尚未上傳的 object。仍應暫停 client 寫入、checkpoint GC 與 body upload/GC，避免匯出期間 object lifecycle 改變。v2 `manifest.json` 分別保存 `r2.checkpoints` 與 `r2.blobs` inventory；本機檔案也隔離在 `objects/checkpoints/` 與 `objects/blobs/`，所以兩個 bucket 即使有相同 key 也不會互相覆蓋。工具仍可驗證／還原既有的 v1 checkpoint-only backup，讀取時會正規化為空的 BLOBS inventory；還原這類舊 D1 snapshot 後必須先套用尚未執行的 numbered migrations，才可啟動目前 Worker。所有新匯出固定使用 v2。備份目錄固定為 `0700`、資料檔為 `0600`，任一 SHA-256、大小、metadata 或路徑不符就拒絕還原。

export／restore 會暫時部署隨機命名、同時綁定目標 CHECKPOINTS 與 BLOBS 的操作 Worker。bridge URL 強制帶 `checkpoints` 或 `blobs` plane，沒有模糊的預設 bucket；它只接受執行程序記憶體內的 256-bit bearer，`finally` 一律嘗試刪除，token、ciphertext 與 metadata 都不寫 log。若程序遭強制終止，終端會留下隨機 Worker 名稱，必須到 Cloudflare 刪除後再繼續。

還原只接受全新且空白的 D1、CHECKPOINTS 與 BLOBS，並要求把三個目標名稱逐字確認：

```bash
npm run ops -- restore \
  --input /absolute/private/path/shinsou-sync-2026-08-20 \
  --config /absolute/path/to/target-wrangler.generated.jsonc \
  --database shinsou-sync-restored \
  --checkpoints-bucket shinsou-sync-checkpoints-restored \
  --blobs-bucket shinsou-sync-blobs-restored

npm run ops -- restore --apply \
  --input /absolute/private/path/shinsou-sync-2026-08-20 \
  --config /absolute/path/to/target-wrangler.generated.jsonc \
  --database shinsou-sync-restored \
  --checkpoints-bucket shinsou-sync-checkpoints-restored \
  --blobs-bucket shinsou-sync-blobs-restored \
  --confirm-empty-target shinsou-sync-restored \
  --confirm-empty-checkpoints shinsou-sync-checkpoints-restored \
  --confirm-empty-blobs shinsou-sync-blobs-restored
```

工具會先離線驗證完整 backup，再遠端確認 D1 與兩個 R2 都為空；寫入順序固定為 CHECKPOINTS/BLOBS → 重新下載逐一比對 ciphertext、大小與 metadata → D1，避免產生指向不存在或 metadata 已失真的 checkpoint/blob row。還原失敗後不要覆蓋或混用半成品，應建立另一組空白目標重試。完成後仍要以 App 重新 bootstrap、下載 stable checkpoint、replay event tail、materialize 至少一個 encrypted body，並比對 workspace head，才算演練成功。

### Emergency workspace reset

Emergency reset 預設也是 dry-run。正式執行只允許 private single-owner workspace，要求逐字 confirmation，並在 D1 原子撤銷 credential 後分別清除、重新列出及確認 CHECKPOINTS/BLOBS 的 exact `workspaces/{workspaceId}/` prefix。只有兩個 bucket 都為空，工具才提交一筆 purge receipt 並產生受 `0600` 保護的一次性 App handoff：

```bash
npm run ops -- emergency-reset \
  --workspace 00000000-0000-4000-8000-000000000007

npm run ops -- emergency-reset --apply \
  --workspace 00000000-0000-4000-8000-000000000007 \
  --endpoint https://your-worker.workers.dev \
  --confirm "RESET EMPTY WORKSPACE 00000000-0000-4000-8000-000000000007" \
  --config ~/.config/shinsou/cloudflare/shinsou-sync/wrangler.generated.jsonc
```

若不用 generated config，必須以 `--checkpoints-bucket` 與 `--blobs-bucket` 明確指定兩個 bucket。任一 plane 清除失敗時不會產生 purge receipt；以完全相同參數重跑即可繼續。

## API

### Public/bootstrap

| Method | Path | 說明 |
|---|---|---|
| `GET` | `/v1/capabilities` | 獨立 protocol/schema reader/writer 範圍、Realtime/Lite 與 body limits |
| `GET` | `/setup` | 不含 secret、內嵌可掃描 setup deep-link QR 的 landing page |
| `POST` | `/v1/setup/claim` | 單次 bootstrap claim |
| `POST` | `/v1/invites/redeem` | 單次 invite secret 建立 user/workspace/device |
| `POST` | `/v1/auth/challenge` | 建立兩分鐘 device challenge |
| `POST` | `/v1/auth/token` | 256-bit device token commitment + Ed25519 challenge response 換 access token |
| `POST` | `/v1/recovery/challenge` | 取得 Recovery Kit challenge 與加密 recovery envelopes |
| `POST` | `/v1/recovery/claim` | recovery signature 建立新 device、撤銷舊 device 並要求 rotation |

### Access token + control signature

| Method | Path | 說明 |
|---|---|---|
| `POST` | `/v1/auth/capability` | access token 換最長 5 分鐘 workspace capability |
| `POST` | `/v1/admin/invites` | admin 建立預設 24 小時、256-bit 單次 invite |
| `POST` | `/v1/pairings` | 建立 5 分鐘 pairing session |
| `POST` | `/v1/pairings/{id}/approve` | sponsor 綁定 transcript、candidate keys/token hash 與完整 retained keyring |
| `GET` | `/v1/devices` | 僅列出目前 user 的 devices、兩種 public key 與 enrollment attestation |
| `POST` | `/v1/devices/{id}/revoke` | 同一 user 的另一臺有效 device 才能撤銷；立即增加 auth epoch |

控制操作另外要求：

```text
X-Shinsou-Timestamp: <13-digit Unix milliseconds>
X-Shinsou-Nonce: <at least 128-bit base64url>
X-Shinsou-Signature: Ed25519(
  "shinsou:control-request:v1\0" ||
  METHOD || "\n" || PATH || "\n" || TIMESTAMP || "\n" || NONCE || "\n" ||
  base64url(SHA-256(raw request body)) || "\n" || DEVICE_ID
)
```

nonce 在 D1 單次消耗，timestamp 容許正負五分鐘。Access token 被竊但沒有 device private signing key時，不能執行 invite、pairing approval 或 revoke。

### Pairing candidate

| Method | Path | 說明 |
|---|---|---|
| `POST` | `/v1/pairings/{id}/candidate` | pairing secret 提交 B 的 public keys 與公開 token commitment |
| `GET` | `/v1/pairings/{id}` | A 用 access token、B 用 `X-Shinsou-Pairing-Secret` 讀狀態/短碼 |

六位短碼由完整 session/candidate transcript 的 SHA-256 推導。QR 不包含 workspace key或長期 device token。啟用前，server 會要求 envelope epoch 集合完整涵蓋 active epoch、retained stable checkpoints 與仍保留的 event tail。配對成功後，新裝置也可從 authenticated `/bootstrap` 取得自己的完整 key envelopes。

### Workspace capability

| Method | Path | 說明 |
|---|---|---|
| `POST` / `GET` | `/v1/workspaces/{id}/events` | append 或固定 `until` watermark 的分頁 catch-up |
| `GET` | `/v1/workspaces/{id}/stream` | Realtime profile WebSocket；credential 只放 Authorization header |
| `GET` | `/v1/workspaces/{id}/bootstrap` | head、GC boundary、retained checkpoints、required epochs/key envelopes 與完整 attested device directory |
| `POST` | `/v1/workspaces/{id}/checkpoint-leases` | exact checkpoint ID/hash、head CAS 與 byte reservation |
| `PUT` | `/v1/workspaces/{id}/checkpoints/{checkpointId}` | hard-cap R2 upload；header/hash/signature 走 headers |
| `POST` | `/v1/workspaces/{id}/checkpoints/{checkpointId}/commit` | R2 metadata 驗證後建立 candidate |
| `POST` | `/v1/workspaces/{id}/checkpoints/{checkpointId}/ack` | 獨立 replay evidence + signature；合格才 promotion |
| `GET` | `/v1/workspaces/{id}/checkpoints/{checkpointId}` | authenticated ciphertext download |
| `POST` | `/v2/workspaces/{id}/blob-upload-sessions` | 依 exact manifest/chunk plan 在 D1 保留 body quota |
| `GET` | `/v2/workspaces/{id}/blob-upload-sessions/{sessionId}` | 取得 immutable upload plan、已收 chunks 與 session 狀態 |
| `PUT` | `/v2/workspaces/{id}/blob-upload-sessions/{sessionId}/chunks/{chunkIndex}` | 將已驗證大小/hash 的 encrypted chunk 冪等寫入 BLOBS R2 |
| `POST` | `/v2/workspaces/{id}/blob-upload-sessions/{sessionId}/commit` | 驗證完整 R2 plan 後原子建立 manifest、envelope 與 durable receipt |
| `GET` | `/v2/workspaces/{id}/blobs/{blobId}/manifest` | 取得 encrypted private manifest、clear refs 與 retained DEK envelope chain |
| `GET` | `/v2/workspaces/{id}/blobs/{blobId}/chunks/{chunkIndex}` | 下載具 exact size/hash headers 的 encrypted chunk |
| `POST` | `/v2/workspaces/{id}/blobs/{blobId}/envelopes` | 以 stable checkpoint evidence CAS re-wrap 同一 DEK 至新 key epoch |
| `POST` | `/v2/workspaces/{id}/blobs/{blobId}/tombstone` | 建立綁定 manifest 與 event watermark 的 body tombstone |
| `POST` | `/v2/workspaces/{id}/blobs/{blobId}/tombstone/acks` | validator 簽署 retained stable checkpoint evidence |
| `POST` | `/v2/workspaces/{id}/blobs/{blobId}/tombstone/revival` | 以較新的 signed stable-checkpoint assertion 取消尚未進入 DELETING 的 tombstone |
| `POST` | `/v2/workspaces/{id}/blob-gc` | safety window、recovery boundary 與所有 active-device ack 完整後執行冪等 GC |
| `POST` | `/v1/workspaces/{id}/key-rotations` | single proposer CAS lease + recipient snapshot |
| `POST` | `/v1/workspaces/{id}/key-rotations/{rotationId}/commit` | deterministic CBOR manifest、完整 envelopes 與 recovery envelope |
| `POST` | `/v1/workspaces/{id}/key-rotations/{rotationId}/ack` | recipient commitment ack |

所有 workspace endpoint 都從 bearer capability 推導 workspace/device/user；request body 的 user ID 不參與授權。Instance admin 若沒有 membership，也不能讀取 ciphertext。

## Sender public key directory 與釘選

只從 server 取得一把未經證明的 Ed25519 key，無法防止惡意 server 替換 event sender。Worker 因此保存不可變的 enrollment attestation，而不是把 server 自己的簽章當成 E2EE 信任根：

- `initial`：第一臺 device 以自己的 Ed25519 key 簽署 initial claim；bootstrap/invite secret 與本機已知 key 建立首次信任。
- `pairing`：已釘選 sponsor device 對完整 pairing transcript、B public keys、公開 token commitment 與所有 envelope hash 簽署。
- `recovery`：Recovery Kit signing key 對新 device keys、公開 token/challenge commitments、新 recovery keys 與 workspace envelope hashes 簽署。

`devices.signing_public_key/wrapping_public_key/token_hash` 以及 `device_key_attestations` 在 D1 有 immutable trigger，不能就地替換或刪除。Pairing、revoke、recovery 會遞增 workspace 的 `device_directory_epoch`。

`/bootstrap` 回傳：

```json
{
  "deviceDirectory": {
    "version": 3,
    "hash": "base64url-sha256-of-full-canonical-directory",
    "allDeviceCount": 2,
    "devices": [
      {
        "deviceId": "uuid",
        "signingPublicKey": "base64url",
        "wrappingPublicKey": "base64url",
        "attestation": {
          "type": "pairing",
          "attestorDeviceId": "already-pinned-uuid",
          "attestorPublicKey": "base64url",
          "signatureDomain": "pairing-approval",
          "manifestJson": "exact canonical JSON bytes as UTF-8",
          "signature": "base64url"
        }
      }
    ]
  }
}
```

Catch-up 每頁回 `deviceDirectoryVersion/deviceDirectoryHash/senderDevices`；`senderDevices` 只有本頁 event sender，但 hash 永遠針對完整目錄。Client 的安全順序固定為：

1. 釘選最高 `(directory version, hash)` 與每個 `deviceId → signing/wrapping keys`。
2. 新 sender 若為 `pairing`，其 attestor 必須已釘選；若為 `recovery`，attestor 必須符合本機 Recovery Kit／先前釘選 recovery key；驗證 `shinsou:{signatureDomain}:v1\0 || UTF8(manifestJson)`。
3. 驗證 manifest 中的 device ID、兩把 key、token commitment 與 response 欄位完全相同，才將新 key 加入目錄。
4. 同一 version 出現不同 hash、既有 device ID 換 key、version rollback、缺少或無效 attestation，一律 fail closed。未知 sender event 不得先套用；先重新 bootstrap 目錄。

Directory hash可偵測已釘選 client看到的 rollback／分岔；server 仍可拒絕服務或對從未見過彼此的 clients 做 split view。因此 onboarding attestation 與 QR 短碼才是 key authenticity 的信任來源，server directory hash 本身不是。

Device token 是隨機 256-bit base64url；D1 只保存 `base64url(SHA-256(raw token bytes))`。這個公開 commitment 讓 sponsor/client 能重建並驗證 attestation manifest，又不需要知道 server-side pepper；access token、capability、invite 與 pairing secret 仍使用各自 domain-separated peppered hash。

## Event envelope

`POST /events` 使用 JSON transport：

```json
{
  "headerCbor": "base64url deterministic-CBOR AAD header",
  "ciphertext": "base64url encrypted event",
  "ciphertextSha256": "base64url SHA-256",
  "signature": "base64url Ed25519 signature"
}
```

`headerCbor` 是 deterministic CBOR map，精確包含 `envelopeVersion/protocolVersion/schemaVersion/cipherSuite/nonce/instanceId/workspaceId/eventId/deviceId/deviceSeq/keyEpoch`，不含 server 配置的 `workspaceSeq`。Device signature input 為：

Protocol v1 的 `cipherSuite` 唯一合法值為 `CHACHA20_POLY1305`。未經版本協商加入的算法會由
Worker 與 App 同時 fail closed，避免 header 宣告與實際 AEAD primitive 不一致。

```text
"shinsou:event-envelope:v1\0" || rawHeaderCbor || rawCiphertextSha256
```

Server 保存並在 catch-up 原樣回傳 `headerCbor`，不從 D1 欄位重建。每臺 device 只能提交下一個連續 `deviceSeq`；已提交 sequence 的相同 hash 回原 receipt，不同 hash 回 `replay_or_corruption`。Event payload 被 GC 後，`device_stream_state` 與 `event_receipts` 仍永久保留。

## Checkpoint 狀態機

1. Client 先取得 head，建立包含完整 reducer metadata 的 `SyncState` candidate，再以 exact `checkpointId + ciphertextSha256 + throughWorkspaceSeq` CAS lease。
2. Lease trigger 原子保留 byte quota。上傳時 Worker 驗證 deterministic checkpoint header、ciphertext SHA-256、device signature、key epoch、previous stable chain，並自行建立 R2 key。
3. `commit` 只在 R2 object metadata 與 lease 完全一致時建立 D1 candidate；candidate 與 stable pointer 分離。
4. Validator 必須從 previous stable 完整 replay 到固定 watermark，提交精確 replay range/count 與簽章。Server 會比對 D1 authoritative event count；漏掉任何 retained event 的 ack 不能 promotion。
5. 多裝置 workspace 不接受 uploader 自己的 ack。單裝置 workspace 必須等待 grace period，Worker 重新下載 R2 並再次比對 hash。
6. Promotion 以 exact ID/hash 更新 stable pointer。至少兩份 stable 且經 safety window 後，event GC 才能移至第二新（recovery base）的 sequence；永久 receipts 不刪除。永遠保留最新三份 stable R2 objects。

Checkpoint header signature input 為：

```text
"shinsou:checkpoint-envelope:v1\0" || rawHeaderCbor || rawCiphertextSha256
```

Checkpoint header 是 exact deterministic-CBOR map，並且必須包含
`compression = "LZ4_BLOCK_V1"` 與 `uncompressedSize`。後者是解壓後 canonical
`SyncState` 的 byte size，合法範圍為 1 到 32 MiB；缺少欄位、額外欄位、未知壓縮
演算法、非整數或超出範圍都會在接受 R2 object 前遭拒絕。

Server 無法看到 plaintext，因此「canonical SyncState bytes 逐 byte 相同」最終仍由具 workspace key 的 validator 負責；server 只能驗證完整 replay 範圍、event 數量、identity/hash 與 attestation signature。

## Encrypted content body lifecycle

Content body plane 與 checkpoint 共用 workspace capability、quota authority 與 active key epoch，但使用獨立的 D1 lifecycle tables 與 `BLOBS` bucket。Worker 只由已驗證的 workspace/blob/manifest UUID 與 chunk index 組成 object key；API 不接受 client 指定 R2 path。正文、private manifest、plaintext digest 與 DEK 不進 event/checkpoint metadata，後者只保存 `BlobRef`、remote manifest reference、DEK envelope reference、presence/tombstone 與 HLC。

1. Client 為每個 blob 產生隨機 256-bit DEK，以各自 domain-separated nonce/AAD 加密 private manifest 與 chunks，再提交 exact byte/hash/chunk plan 建立 upload session。D1 trigger 以 CAS 原子保留 quota。
2. Chunk PUT 逐一驗證 index、ciphertext size/hash 與 session identity，並冪等寫入 `BLOBS`。Commit 重新核對全部 object metadata、private manifest、reservation 與 initial DEK envelope；只有 D1 manifest/receipt commit 成功後，client 才能把 blob 標為 remotely available。
   Expired/cancelled session 的 quota 會持續計入，直到任一後續 workspace upload 觸發有 durable
   cursor、post-expiry quiescence 與固定 window 上限的 orphan sweep，且所有 planned R2 keys 都經
   delete + HEAD 確認消失。
3. Client 必須 durable 保存 transfer intent 與 receipt，依「Worker commit receipt → durable `BlobReferenceCommitV2` draft → content job ack → journal cleanup」順序完成。任一 crash boundary 都用相同 deterministic operation ID 重放，不能因重試產生另一份 body identity。
4. Key rotation 不重加密大型 body。Client 以新 workspace epoch key re-wrap 同一 DEK，並把新 envelope 鏈結至 previous envelope hash；Worker 只在 stable checkpoint evidence 與 epoch CAS 都成立時接受。尚在 safety window 的 tombstoned body 也必須完成相同 re-wrap，才能進入下一輪 rotation。
5. 刪除先在 metadata plane 建立 presence tombstone。Worker 只在 retained stable checkpoint 已涵蓋 tombstone、safety window 與 recovery boundary 已通過，而且所有 active device 都提交有效 Ed25519 ack 後執行 GC。Rotation 後同一 device 可用較高 retained checkpoint epoch（或同 epoch 更高 watermark）刷新 ack；GC claim 會在 D1 transaction 內重驗 capability、device/user/membership、role、auth/key epoch 與 expiry。GC 刪除 private manifest、chunks、DEK envelopes 與 quota usage，永久 receipt 讓中斷後重跑保持冪等。
6. 同一 `(workspace, blob, manifest, removal through-seq)` 的多裝置 request 收斂為同一 canonical
   tombstone handle。較新的 live-reference checkpoint 可用 active-device signature 將 `WAITING`
   tombstone 原子改為 `CANCELLED`；若 GC claim 已先進入 `DELETING`，API 明確回傳
   `reupload_required`。同一 blob id 日後已 reincarnate 時，舊 revival 仍只依 immutable
   tombstone/GC receipt generation facts 回覆，不會 join 或阻塞新 manifest。Cancellation assertion
   與歷史 tombstone 都保留供稽核，較新的 removal boundary 可開始下一個 lifecycle。

Body transfer 不應進入 cold start、foreground catch-up、reader navigation 或 progress flush 的 critical path。ReadingProgress 與 metadata outbox 優先；App 的低優先級 body scheduler 每個 slice 最多處理一個 blob，失敗時保留 durable job 等待後續重試。

## Rotation 與撤銷

- Revoke 的 D1 trigger 同時把 target device 設為 revoked、增加 `auth_epoch`、撤銷 access/capability 並將相關 workspace 設為 `rotation_required`。
- D1 commit 後 Worker 通知 WorkspaceHub；Hub 對 target（rotation/recovery 時為全部）送 `reauthRequired` 並斷線。通知失敗時，最長五分鐘 capability TTL 是上限。
- Rotation lease trigger snapshot 當下所有有效 recipient 的 device/auth epoch/wrapping key。Commit trigger 再次比較 proposer、membership、完整 recipient set、每個 commitment/epoch envelope 與 recovery envelope，任何 device/membership 變化都讓整批 transaction rollback。
- Commit 成功後才 CAS `active_key_epoch`。舊 epoch 仍可供歷史解密，但 conditional event/checkpoint insert 不再接受它。

## Realtime 與 Lite

`REALTIME_ENABLED=true` 時，event append 經相同 workspace 的 DO serialization、D1 commit，再 fan-out：

```text
hello → event → checkpointAvailable → resyncRequired/reauthRequired
```

目前 client-to-server mutation 一律走 authenticated REST append；WebSocket 接收 `ping` 與 cursor `ack`。發現 sequence gap 時，client 必須使用 REST `after/until/limit` catch-up。

`REALTIME_ENABLED=false` 時，`/stream` 不提供，append 直接走相同 D1 conditional statement與 D1 rate/quota gate。`/v1/capabilities` 回傳 `profile: "lite"`，client foreground 立即 catch-up 並採 adaptive polling；資料 schema 不需搬移。

## 安全注意事項與上線前 Gate

- CORS 預設不允許任何 browser origin；native App 無 `Origin` 可正常使用。原生部署範本刻意省略 `ALLOWED_ORIGINS` 空值，避免 Cloudflare Deploy 表單要求填寫沒有用途的欄位。若需要 Web client，才在 Cloudflare 中新增明確的 `ALLOWED_ORIGINS`（逗號分隔），永不使用 `*`。
- Log 只記 method/path 與固定錯誤碼，不記 bearer、invite/pairing/bootstrap secret、QR payload、ciphertext 或 decrypted content。
- Body 在讀取前檢查 `Content-Length`，串流過程仍套用 hard cap。Checkpoint 目前因 WebCrypto SHA-256 沒有標準 incremental API，最多會在 Worker memory 緩衝 32 MiB；部署前需用目標 Cloudflare plan 做壓力測試。
- 本機測試使用 Cloudflare D1 同源的 SQLite 語意，但仍必須在正式 Cloudflare preview/remote D1 驗證 migration、trigger、CHECKPOINTS/BLOBS R2 conditional put、DO hibernation/restart 與 WebSocket invalidation。
- E2EE 不隱藏 workspace/device ID、時間、大小與頻率，也不能防止惡意 server 刪除/回滾/拒絕服務。Client 必須驗證 sequence、signature、hash、checkpoint chain 與 retained fallback。
- Recovery Kit 被竊等同 workspace 被接管；Recovery Kit 只恢復 key，無法恢復已從 D1、CHECKPOINTS 或 BLOBS 刪除的 ciphertext。
- 正式標示 stable 前，仍需完成全新 Cloudflare 帳戶的一鍵部署演練、D1/雙 R2 export/restore、實際 quota/負載、跨裝置 matrix 與 server storage/log secret scan。
