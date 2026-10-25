# Unified Shinsou／ShuYue repository contract

- 導覽：[插件系統總覽](PLUGIN_SYSTEM.md) · [插件系統事件接口架構](PLUGIN_SYSTEM_EVENT_ARCHITECTURE.md)
- 上游歷史專案：[Shinsou](https://github.com/aluo96078/shinsou) · [ShuYue](https://github.com/aluo96078/shuyue)
- 官方 V2 repository：<https://github.com/aluo96078/shinsou_plugin>

Shinsou X 依 fetched JSON shape 偵測 repository 格式，不依 URL 檔名、GitHub hostname、`index.json`
字樣或本機／LAN 位址猜測 protocol。這讓歷史 Shinsou、Mihon metadata、unified-v1 與 V2 repository
可以使用相同的新增入口，同時維持不同的 execution admission。

Production executable repository 必須以 pinned Ed25519 `shinsou-signed-envelope-v1` 發布。Envelope 的 key 必須命中 Host 事先配置的 trust root，並檢查 sequence replay／equivocation、payload SHA-256 與 byte size；unsigned 僅是明示的 local/developer compatibility 例外。這裡不宣稱官方簽章已部署：正式發布前仍須配置 out-of-band public key／fingerprint、簽署 repository/index/sidecar、持久化 watermark，並完成 rotation、撤銷與跨平台驗證。

## 相容矩陣

| 格式 | 載入入口 | 投影結果 | 執行狀態 |
|---|---|---|---|
| Shinsou JSON array | `index.json` | `PluginIndexEntry` | 可解析／安裝，但 generic JavaScript 在 production 保持 inert；legacy adapter 只供明示的 internal test／developer compatibility。 |
| Mihon／Tachiyomi JSON array | `index.min.json` fallback | `LegacyExtensionIndexEntry` | Metadata-only stub；APK 不會在 Shinsou X 執行。 |
| `shinsou-unified-v1` object | `index.json` | `shinsou + legacy` 與 `shuyue` | 各項仍走自己的 admission；envelope 本身不授權 script。 |
| `shinsou-extension-v2` object | `index.json` | `contract: shinsou` 與 `contract: shuyue` | 驗證 V2 metadata；generic Shinsou production inert，ShuYue 只允許 reviewed catalog exact match。 |

`ExtensionRepositoryClient.fetchIndex()` 會先嘗試 `index.json`。只有該讀取／decode 失敗時才 fallback 到
`index.min.json`；因此錯誤的 HTML、登入頁或 Cloudflare challenge 也可能令第一階段 decode 失敗，
應保留兩次錯誤資訊而不是把 fallback 當成格式證明。

## 歷史 Shinsou array

傳統 `index.json` 是 `PluginIndexEntry` array。Host 仍接受既有欄位，並可讀取新版可選欄位：

- `sha256`、`byteSize`、`sidecarUrl`；
- `type` 或 `contentType`；
- `contract`；
- `systemEvents`、`requestedHostPermissions`；
- `installable`、`referenceOnly`、`legacyCompatibilityOnly`。

缺少發布端 SHA-256 的歷史項目只在使用者明確安裝／更新時，以收到的 bytes 建立 legacy
trust-on-install 記錄。App 重啟仍要求既有 execution trust；digest 相符本身不會復原已撤銷授權。

## Mihon／Tachiyomi metadata

`index.min.json` 可被解析成 `LegacyExtensionIndexEntry`，包含 package、version、language 與 source
metadata。Host 會建立不可執行的 stub source，不會下載、安裝或執行 APK。這是資料顯示與遷移相容，
不是 Mihon Android extension ABI 相容。

## `shinsou-unified-v1`

可選 envelope 讓一個 URL 同時公開歷史 Shinsou 與 ShuYue metadata：

```json
{
  "format": "shinsou-unified-v1",
  "shinsou": [/* PluginIndexEntry */],
  "legacy": [/* PluginIndexEntry */],
  "shuyue": [/* ShuYueRepositoryEntry */]
}
```

`shinsou` 與 `legacy` 會合併投影成 Shinsou entries；`shuyue` 保持獨立。Envelope 只負責格式聚合，
不會把 ShuYue script 當作 generic Shinsou script，也不會繞過 reviewed catalog。

歷史 `type`（或 alias `contentType`）可為 `manga`、`novel`、`both`，可放在 package 或 source。
來源值優先；沒有可用值時，legacy Host 為相容性解析成 `both`。這個 fallback 不是 V2 發布者省略
內容宣告的理由。

## `shinsou-extension-v2`

V2 根物件至少包含：

```json
{
  "format": "shinsou-extension-v2",
  "contractVersion": 2,
  "contentContract": "extension-content-v2",
  "contentContractVersion": 2,
  "packages": []
}
```

同一 `packages` array 可以有：

- `contract: "shinsou"`、`runtime: "legacy-shinsou-adapter-v2"`；
- `contract: "shuyue"`、`runtime: "reviewed-shuyue-adapter-v2"`。

### Shinsou projection

Installable Shinsou package 會投影為 `PluginIndexEntry`，但 production 不執行其 generic JavaScript。
Internal test／developer compatibility 的 generic adapter 仍以 legacy `Long` 執行 scope 對接，所以每個
installable `sourceId` 必須可無損轉成 `Long`。參考、不可安裝或僅遷移項目會在 coercion 前略過，
避免 opaque fixture 令整個混合索引失敗。

### ShuYue projection

ShuYue package 會投影為 `ShuYueRepositoryEntry`，保留 opaque string source ID。Repository entry
本身仍是 inert metadata；production coordinator 只顯示內建 reviewed catalog 中 package、version、
versionCode、SHA-256、source metadata、內容及 event declaration 全部符合的項目。下載後必須進
quarantine、明確 approval，才會建立 runtime。

### 不可安裝狀態

下列任一狀態都不能作一般新安裝：

- `installable: false`；
- `referenceOnly: true`；
- `legacyCompatibilityOnly: true`。

官方 `example.dual` 展示同 package 內一個 `PLAIN_TEXT` 小說來源與一個 `IMAGE_SEQUENCE` 漫畫來源，
但明確標為 `referenceOnly: true`、`installable: false`。V2 schema 能描述 mixed package；目前 generic
adapter 與 reviewed ShuYue coordinator 尚未保證此組合的完整 production runtime，因此官方可安裝
漫畫與小說來源採分包發布。

## Sidecar cross-check

V2 sidecar 是另一份 Host-facing contract 描述，不是擁有 repository-declared 獨立 digest 的第二個
executable。Reviewed ShuYue loader 仍會計算實際下載 sidecar bytes 的 SHA-256／size，並記入本機
quarantine metadata；這是下載記錄，不是索引內的發布者 digest binding。
安裝前，Host 會交叉核對：

- format 與 contract version；
- package ID、version、version code；
- script URL、script SHA-256 與 byte size；
- content contract 與 type；官方 repository smoke test另會比對 content kinds；
- 每個 exact `SourceKey`、source ID 與 `browserSessionOrigins`；
- requested Host permission 與 system-event declaration；reviewed ShuYue admission 另比對 capabilities。

上述 Host admission 欄位不一致會使該 entry fail closed。官方發布 smoke test 還會完整比對
index／script metadata／sidecar 的 content kinds、capabilities 與 runtime permissions；generic Shinsou
Host 目前尚未把這三組欄位全部保留為 runtime enforcement。Repository 的 permission declaration 只是
review input，不能自行建立 execution 或 system-event grant。

Repository／plugin loader 的 index、script、metadata、package/source、redirect、response 與 log 都有
resource limits，超限即拒絕或 fail closed。Package store 的 Host bounds 是：script 8 MiB、metadata
512 KiB、package 256 個、每 package 256 sources、package-store index 128 KiB、package-directory
discovered files 1,024 個；repository fetch index／sidecar response 則分別為 2 MiB／512 KiB。寫入與
readback 會驗證 content-addressed SHA-256、strict UTF-8、metadata／package ID 與
atomic commit；key-value V2 record 損壞時不會 fallback 到 V1 split keys。Host content plane 另只按 exact
`contentOrigins` 取得 GET/HEAD bytes，移除 Cookie、Authorization、proxy key 等秘密，再交給 Coil／內容
consumer；`browserSessionOrigins` 只供具備 exact artifact `BROWSER_CHALLENGE` permission 的 browser flow。

File package ownership is fail closed: once a package directory has an authoritative marker, a missing or
corrupt `package.json`／content-addressed script is an installed-but-inert package, never a reason to fall
back to legacy KV metadata or script keys. Legacy KV migration is attempted only when no V2 file-store
ownership exists; a failed legacy cleanup cannot make an older executable discoverable again.

Response header bounds 同樣適用於每個 hop：128 fields、name 256 bytes、value 16 KiB、aggregate 64 KiB、
`Set-Cookie` 64 個、每個 cookie 32 attributes；`Location` 必須唯一且不超過 4 KiB。超限或含非法字元
直接 fail closed。Response body 以 bounded streaming 讀取，`Content-Length` 必須唯一、合法且不超過
該 request cap；`Content-Encoding` 只允許 identity／gzip／deflate，decoded bytes 逐 chunk 限制，並
對 HTTP framing／壓縮後大小矛盾 fail closed。

Plugin batch POST 的精確 Host 邊界為最多 128 items、32 concurrent requests、每 item 128 KiB decoded
response、整批 4 MiB aggregate response；transport read-ahead 加上已接受結果的 decoded peak 約 8 MiB。
超過任一額度會整批失敗，不交付部分結果；大文件不得透過 batch 取得。

Runtime identity 由 exact artifact 與 exact `SourceKey` 推導為 stable logical ID，reload 時以新的
generation 使舊 handles／events 失效。只有 exact reviewed ShuYue compatibility path 會移除 legacy
`Connection` header；generic repository plugin 無法設定或繞過 Host-owned hop-by-hop／framing headers。

Exact reviewed ShuYue runtime 才能使用 large-text profile：相對 generic defaults，invocation instructions
由 100M 提高至 500M，default result string cap 由 1 MiB 提高至 8 MiB，result envelope cap 由 4 MiB
提高至 17 MiB。caller 明示的更嚴值保留；只有 default 值會提升，非 default 的 raised value 仍 capped
於 reviewed maximum，generic runtime 完全不變。4–8 MiB 多頁 `PLAIN_TEXT` 因此可讀，>8 MiB 直接
fail closed。正文以 64 KiB chunk 的 single-use、可取消 stream 傳遞，pending+active aggregate reservation
最多 8 MiB，超過即拒絕。最壞情況包含 JS String、JSON 與 Kotlin copies 及較高 CPU budget；影響只在
exact reviewed runtime，generic steady-state 記憶體與效能不變。

安全驗證會增加 DNS、hash、signature 與 admission 延遲；content bytes 可能增加 host-fetch/cache 步驟，SourceKey 變更可能觸發 cache migration。JVM transport 以 DNS socket pinning 並透過 OkHttp shared pool 維持連線重用；iOS production generic network/repository transport 無法安全 pinning 時 fail closed。這些行為與 synchronous Rhino／JavaScriptCore、Mihon metadata-only 及 mixed-package 限制共同構成相容性邊界。

## 本地測試

從官方 plugin repository 根目錄啟動靜態 server：

```sh
python3 -m http.server 18081 --directory /Users/aluoexpiry/project/shinsou_plugin
```

在 App 加入以下基底 URL：

```text
http://127.0.0.1:18081
```

若是實體裝置，將 `127.0.0.1` 換成 Mac 的 LAN 位址。正式官方 URL 為：

```text
https://raw.githubusercontent.com/aluo96078/shinsou_plugin/refs/heads/master
```

舊 `merged-shuyue/` 只保留 unified-v1／遷移 fixture；根 `index.json` 才是目前 Extension v2 source of
truth。發布前從 plugin repository 執行：

```sh
node test/v2-migration-smoke.js
git diff --check
```

Smoke test 會驗證索引、script digest、sidecar binding、權限與 migration fixture，不會登入或請求
正式第三方來源。Package schema 與作者清單見官方
[`shinsou-extension-creator`](https://github.com/aluo96078/shinsou_plugin/blob/master/skills/shinsou-extension-creator/SKILL.md)。
