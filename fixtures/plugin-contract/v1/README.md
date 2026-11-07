# 套件發佈契約 v1 測試向量

這個資料夾是 Shinsou X App 與套件作者工具（`@shinsou/plugin` CLI）共用的**位元組級契約**。
檔案由 App 自己的程式碼產生（`composeApp/src/desktopTest/.../plugin/PluginContractVectorTest.kt`），
App 測試每次都會重新產生並與這裡的檔案逐字比對；CLI 也必須對同一份檔案逐 byte 相符。
任何一邊的差異都代表協定改變，不是可以隨手更新的快照。

重新產生（會覆寫檔案，請檢查 diff）：

```bash
./gradlew :composeApp:desktopTest -PwritePluginContractVectors=true --tests '*PluginContractVectorTest'
```

> `keys.json` 的金鑰只供測試，種子是公開標籤的 SHA-256，**絕對不能**用來簽署真正的儲存庫。

## 檔案

| 檔案 | 內容 |
|---|---|
| `keys.json` | 兩把測試發佈者金鑰：種子、公鑰、完整指紋、顯示用指紋。 |
| `canonical-json.json` | 正規化 JSON：可接受的輸入與預期輸出（含 SHA-256），以及必須拒絕的輸入。 |
| `envelopes.json` | 各文件類型以 `publisher-a` 簽好的 `shinsou-signed-envelope-v1`、簽章訊息、輪替聲明，以及必須驗證失敗的案例與預期錯誤。 |
| `sandbox-frames.json` | SandboxProtocol v1 各 frame 的 CBOR body 與含長度前綴的 envelope。 |

## 正規化 JSON（`canonicalSyncJson`）

- 物件鍵依 **UTF-16 code unit** 排序（與 JavaScript 預設 `sort()` 相同，不是 code point 順序）。
- 無空白；陣列保留順序。
- 數字只能是整數，且必須是正規寫法、介於 ±9007199254740991；小數、指數、`1.0`、`-0` 一律拒絕。
- 字串跳脫與 kotlinx.serialization 相同：`"` `\\` `\n` `\r` `\t` `\b` `\f` 用簡寫，其餘 U+0000–U+001F 用小寫 `\u00xx`；
  `/`、DEL（U+007F）、U+2028 與所有非 ASCII 字元原樣輸出（UTF-8）。

## 簽章 envelope（`shinsou-signed-envelope-v1`）

Envelope 只能有這 8 個欄位：`format`、`algorithm`（`Ed25519`）、`keyFingerprint`、`publicKey`、`payloadType`、
`sequence`（非負整數）、`payload`、`signature`。

簽章訊息：

```text
"dev.shinsou.repository.signature.v1\0" + canonicalJson({
  algorithm, documentId, format, keyFingerprint, payload, payloadType, repository, sequence
})
```

- `repository` 是正規化後的儲存庫基底 URL（去掉結尾 `/`，無 query／fragment）。**簽章綁定網址**：鏡像站要各自簽一份。
- `documentId`：`repo.json`、`index.json`、`index.min.json`、sidecar 的相對路徑（例如 `sidecars/example.novel.json`）、
  `publisher-key-rotation.json`、`publisher-revocations.json`、`publisher-key-revoked.json`。
- `payloadType`：`repository-meta`、`repository-index`、`repository-legacy-index`、`plugin-sidecar`、
  `publisher-key-rotation`、`publisher-revocations`、`publisher-key-revoked`。
- `publicKey`、`signature` 為無 padding 的 base64url；`keyFingerprint` 為 `sha256:` + 公鑰 SHA-256 的無 padding base64url。
- `repo.json` 的 `payload.meta.signingKeyFingerprint` 必須等於簽章金鑰的指紋。
- 每種文件各自有遞增的 `sequence`；App 會拒絕重播（較小的 sequence）與同 sequence 不同內容。

### 金鑰輪替

輪替文件由**舊金鑰**簽 envelope，payload 內的 `newKeySignature` 由**新金鑰**簽：

```text
"dev.shinsou.publisher.rotation.v1\0" + canonicalJson({
  effectiveSequence, newKeyFingerprint, newPublicKey, oldKeyFingerprint, publisherId
})
```

`publisherId` 是發佈者第一把金鑰的指紋，輪替後不變。

## SandboxProtocol v1

每個 envelope 是 4 byte big-endian 長度＋body；body 是一個 CBOR 陣列 `[frameType, 欄位…]`。
超過 1 MiB 的 body 以 `[0, more, bytes]` 的 CHUNK frame 分段，除最後一段外每段剛好
`chunkPayloadBytes` bytes。各 frame 的欄位順序見 `sandbox-frames.json` 的 `frame` 描述。
