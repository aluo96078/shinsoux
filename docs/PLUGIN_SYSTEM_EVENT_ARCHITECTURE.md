# 插件系統事件接口架構

- 狀態：Partially implemented（production V1 gateway 與四個 handler 已接通）
- 日期：2026-08-22；實作狀態更新：2026-08-29
- 適用範圍：Shinsou X KMP、Shinsou JavaScript plugins、reviewed ShuYue plugins
- 本文件範圍：事件契約、安全邊界、宿主策略、目前 production 接線與尚未完成項目

## 目前 production 狀態

Production composition 已建立 `PluginSystemEventGateway`，將它注入 `PluginManager`／runtime，並由
host code 註冊下列四個 V1 handler：

- `auth.login.request`：送入 exact-source login coordinator；
- `source.refresh.request`：只 invalidates exact live source，不呼叫 repository／global refresh；
- `auth.logout.request`：由 Host 顯示確認並清理 exact session owner；
- `diagnostic.message.report`：只寫入 bounded diagnostic log，不直接投影成使用者訊息。

Exact-artifact event grant 會持久化並在 runtime 啟動時 hydrate；grant identity 是
`packageId + version + versionCode + SHA-256 + SourceKey`。JVM Rhino 與 iOS JavaScriptCore 都接入同一
wire／gateway 契約，既有 `requestLogin` 保留 compatibility path。

目前仍未完成或刻意保留：

- `REPORT_USER_MESSAGE`：沒有 production safe presenter，admission 會明確拒絕；
- `REQUEST_BROWSER_CHALLENGE`：目前只有 permission enum／設計保留，沒有 system-event handler；App
  本身的來源 Web challenge UI 不等於 Plugin 已能透過此事件呼叫它；
- pull-based result mailbox 與 browser-challenge 的 opaque session／credential handle；
- 本文件後段列出的其餘 runtime 加固與完整外部／平台 smoke gate。

因此下文同時包含「已落地的 V1 契約」與「保留設計」。看到 future／候選／落地階段時，不應解讀
成目前可由 Plugin 使用的 production capability。

## 決策摘要

插件只能向宿主提交一個受限、版本化的「事件請求」，不能直接操作 UI 或核心服務。宿主在
runtime 邊界補上不可偽造的插件身份，依序執行 schema 驗證、精確 artifact 權限檢查、限流、
去重、生命週期檢查與 UI／domain policy，最後才決定是否執行。

首版定義四種訊息：

- `auth.login.request`：請求宿主啟動登入流程；
- `source.refresh.request`：請求刷新自身來源或宿主簽發的目前 context；
- `auth.logout.request`：請求宿主登出自身來源；
- `diagnostic.message.report`：向宿主回報受限的 info／warning／error 訊息。

所有請求都必須立即返回 admission receipt。`accepted` 只代表成功入列，不代表登入、刷新或
登出已完成。事件處理不能等待 UI，也不能在原本的插件呼叫堆疊中重新呼叫插件。

既有 `bridge.requestLogin(reason)` 保留為 compatibility path：具 negotiated system-event binding 的
runtime 會映射到新登入事件；沒有新 declaration／binding 的 legacy package 仍走既有 bounded login
requester。舊插件不因新接口而自動取得刷新、登出或顯示錯誤的權限。

## 背景與原始問題

導入 system-event gateway 前，登入請求的主流程是：

```text
plugin bridge.requestLogin(reason)
  -> PluginLoginRequester
  -> PluginLoginRequestCoordinator
  -> BrowseCallbacks.loginRequests
  -> ShinsouApp
  -> host-owned SourceLoginDialog
  -> BrowseCallbacks.saveSourceCredentials(...)
  -> exact source login
```

其中「只入列並立即返回」是正確的。JavaScript invocation 與 `login()` 使用同一 runtime worker；
若 `requestLogin()` 等待使用者操作，登入 Dialog 後續再呼叫該 runtime 的 `login()` 時會互鎖。

現況不適合直接擴充成通用事件系統：

- `PluginLoginRequestCoordinator` 反向依賴 UI 的 `SourceLoginRequest`；
- 身份與去重只使用插件可碰到的 `Long sourceId`，缺少 package、完整 `SourceKey`、artifact digest
  與 runtime generation；
- 插件提供 `sourceName` 與未設上限的 reason，可能偽裝宿主或持續彈窗；
- 無事件 ID、TTL、queue bound、限流、撤銷後重驗與精確 dismiss；
- `BrowseCallbacks.refresh()` 是整個套件／儲存庫刷新，不是來源局部刷新；
- `BrowseCallbacks` 已承擔大量 UI/domain 操作，不應再成為插件事件總線；
- Rhino 與 JavaScriptCore 各自手寫 bridge，新增方法容易產生平台差異。

因此新系統不擴寫 `PluginLoginRequestCoordinator`，而是建立獨立、host-authoritative 的事件邊界。

## 威脅模型

接口必須假設插件可能有 bug、已遭供應鏈置換，或刻意嘗試影響宿主：

| 威脅 | 主要控制 |
|---|---|
| 偽造另一來源／核心身份 | Runtime 注入 exact bound scope；wire 禁止 identity/target |
| 彈窗、刷新或 Snackbar flood | Exact-source 去重、token bucket、TTL、有界 queue 與三條 lane |
| 以文字冒充 Shinsou、誘導輸入秘密 | Host-owned title/source label；純文字、無連結/action、長度限制 |
| 跨來源登出、清 cookie 或刷新 | Target 永遠為 self；handler 只取得 narrow exact-source port |
| 更新後重播舊請求或權限 | Grant 綁 exact digest；執行前重驗 runtime generation |
| UI/plugin 重入死鎖 | Ingress 立即 receipt；原 invocation 返回後才執行 handler |
| JSON parser／queue 資源耗盡 | Decode 前 byte/depth/duplicate-key limits；bounded queue/backpressure |
| 從 receipt/result 探測核心狀態 | 穩定、最小化 disposition/error code；不回傳內部 identity/state |
| 透過 error、log 或 UI DTO 外洩秘密 | Typed allowlist payload；禁止 raw exception/secret；secret absence tests |
| Generic event 變成 confused deputy | Host 編譯期 handler registry；每個事件獨立 permission 與 target policy |

## 不可破壞的安全規則

1. **插件只表達 intent，不具有執行權。** 名稱雖為 `request`／`command`，宿主永遠可以拒絕、
   合併、延後、降級成記錄或忽略。
2. **身份只能由 runtime 注入。** Wire payload 不接受 package ID、source ID、顯示名稱、artifact
   digest、使用者、裝置或其他來源 identity。
3. **目標預設永遠是 self。** 插件不能傳入核心 row ID、任意 `SourceKey`、publication ID、同步
   workspace、repository URL 或其他來源。
4. **UI 完全由宿主管理。** 插件不能取得 Compose／SwiftUI、Activity／ViewController、navigator、
   Snackbar、Dialog callback 或 UI coroutine scope。
5. **秘密永不進入事件。** Request、receipt、result、log、StateFlow 與安全 UI DTO 都不得包含
   username/password、cookie value、token、header、secret reference 的可解析內容或 stack trace。
6. **非阻塞且不可重入。** Bridge ingress 只做有界 CPU 工作與 `trySend`；所有 domain/UI 行為在
   原插件 invocation 返回後執行。
7. **權限綁定精確 artifact。** 敏感授權以 package/version/versionCode/digest 為鍵；更新不得
   繼承舊 digest 的授權。
8. **執行前重新授權。** 入列後若套件被更新、卸載、停用、撤銷權限或 runtime generation 改變，
   pending request 必須失效。
9. **未知行為 fail closed。** 未協商的 protocol version、required capability、事件名稱或 payload
   version 不得執行。
10. **插件不能控制優先級。** 插件不能要求 foreground、urgent、global、modal 或 system
    notification；實際優先級只由宿主根據前景與使用者操作 context 決定。

## 信任邊界與分層

```text
Untrusted plugin JS
  |
  | one JSON request / immediate bounded receipt
  v
Platform transport (Rhino / JavaScriptCore)
  |
  | runtime injects BoundPluginScope; plugin cannot serialize it
  v
PluginSystemEventGateway (commonMain)
  +-- strict codec and size/depth validation
  +-- exact-artifact permission and capability policy
  +-- live runtime generation check
  +-- idempotency, throttling, TTL, queue/backpressure
  v
Typed dispatcher
  +-- modal lane ----------> Authentication / Logout broker
  +-- refresh lane --------> SourceRefreshScheduler
  +-- transient lane ------> Diagnostic presenter / bounded log
  v
HostSystemIntent / host domain ports
  v
Shinsou-owned UI, credential vault, source facade and cache
```

### 分層責任

| 元件 | 可以做 | 不可以做 |
|---|---|---|
| Plugin JS | 提交協商過的 event name 與有界 payload | 傳入權威 identity、UI 元件、secret 或任意核心 target |
| Platform transport | 傳遞 bytes、取得綁定 runtime scope | 解釋 UI 行為、直接呼叫 repository、保存插件提供的權威身份 |
| Event gateway | 驗證、授權、去重、限流、入列與返回 receipt | 顯示 UI、等待使用者、在 JS stack 內執行 logout/refresh |
| Typed handler | 把一個已授權事件映射成受限 host port | 取得整個 `ShinsouAppServices`、navigator 或通用 repository |
| UI presenter | 產生固定、已清理的 `HostSystemIntent` | 接收 raw JSON、raw exception、cookie、password 或插件 callback |
| Host executor | 依 exact `SourceKey` 執行登入／登出／刷新 | 接受插件指定的其他 source、全局 refresh、sync 或 app restart |

`plugin/events` core 不依賴 Compose 或 `ui.*`。UI 只看宿主建立的 DTO，不能看 wire envelope。

## Wire protocol v1

系統事件協議與 repository 格式、`shinsou-unified-v1`、extension content contract v2 分開版本化。
新增事件接口不提高 `ExtensionPackageV2.CURRENT_CONTRACT_VERSION`。

### 請求 envelope

```json
{
  "protocol": "dev.shinsou.system",
  "version": 1,
  "kind": "command",
  "name": "auth.login.request",
  "id": "login-expired-01",
  "idempotencyKey": "session-expired",
  "payloadVersion": 1,
  "contextRef": null,
  "payload": {
    "reasonCode": "AUTH_REQUIRED",
    "fallbackMessage": "需要登入才能繼續"
  }
}
```

欄位規則：

- `protocol` 固定為 `dev.shinsou.system`；
- `version` 是協議 major version，必須先協商；
- `kind` 在 plugin -> host 方向只接受 `command` 或 `event`；
- `name` 是 namespaced string，由宿主 handler registry 決定是否支援；
- `id` 是插件本地 correlation ID，不是權威 identity；
- `idempotencyKey` 可省略，只能影響同一 host-bound scope 內的去重；
- `payloadVersion` 獨立演進單一訊息的 schema；
- `contextRef` 只能引用宿主先前簽發、短效、綁定同一 runtime 的 opaque handle；
- `payload` 在 gateway 內按 `name + payloadVersion` 嚴格解碼成 typed DTO。

V1 不允許未知 top-level 或 payload 欄位。擴充必須透過新 event name、新 payload version 或協商後
的新 protocol version，避免拼字錯誤或看似無害的未知欄位被舊宿主誤執行。未知 event name 返回
`unsupported`，不會使 runtime 崩潰。

解析前先檢查 UTF-8 byte size、JSON nesting、重複 key 與集合上限，防止 parser 資源耗盡。

### Host-bound scope

Wire request 解碼後，runtime 必須附加一個插件不可建立、不可序列化的 scope：

```text
BoundPluginScope(
  artifactIdentity = packageId + version + versionCode + sha256,
  sourceKey = exact opaque SourceKey,
  runtimeInstanceId,
  runtimeGeneration,
  invocationContext?,
  receivedAtMonotonic
)
```

顯示名稱、圖示與可信 base origin 由宿主使用 `sourceKey` 查已驗證 descriptor，不能取自 payload。
同一 legacy `Long sourceId` 出現在不同 package 時仍是兩個不同 scope。

### 立即 receipt

```json
{
  "protocol": "dev.shinsou.system",
  "version": 1,
  "messageId": "login-expired-01",
  "disposition": "accepted",
  "operationRef": "opaque-host-operation-ref",
  "retryAfterMillis": null
}
```

`disposition` 固定為：

- `accepted`：已入列，尚未完成；
- `deduplicated`：等價請求已在 pending／running；
- `denied`：權限或目前 host policy 不允許；
- `unsupported`：版本、事件或 optional capability 不支援；
- `throttled`：超過頻率限制，可附有模糊化的 retry 時間；
- `invalid`：格式或 payload 不合法；
- `busy`：有界 queue 已滿；
- `runtime_closed`：runtime 已關閉或 generation 已失效。

Receipt 不包含 queue 長度、其他來源狀態、登入狀態、內部 exception 或具意義的核心 identifier。

### 非同步結果

Host 處理狀態為：

```text
Received
  -> Validated
  -> Accepted
  -> Queued
  -> Presented | Executing | Suppressed
  -> Succeeded | Cancelled | Failed | Expired
```

V1 所有 handler 都必須能在沒有 result consumer 的情況下正確運作。若未來協商
`result.delivery.pull.v1`，host 可把有界結果放進每 runtime mailbox，由插件在下一次正常 invocation
時使用 `drainResults(max)` 拉取。結果只包含 correlation ID、`succeeded/cancelled/failed/expired`
及穩定錯誤碼。

禁止在原 request stack 內等待結果，也禁止 UI thread 直接 push callback 進正在執行的 runtime。

## V1 事件定義

### `auth.login.request`

種類：`command`

```text
LoginRequestV1(
  reasonCode: SafeCode?,
  fallbackMessage: PlainText?
)
```

規則：

- target 隱含為 bound `sourceKey`；
- 同時需要來源的 `ExtensionCapability.LOGIN` 與 host-event `REQUEST_LOGIN_UI`；
- 宿主決定使用帳密 Dialog、已審核的 Web challenge 或其他登入方法；插件不能指定 View/WebView；
- 若方法需要 Web challenge，仍必須另有 `REQUEST_BROWSER_CHALLENGE` grant；login permission 不能
  隱含提升為 browser permission；
- title、來源名、圖示、欄位、按鈕與錯誤樣式完全由宿主產生；
- 一個來源最多一筆 pending login；跨來源採公平 FIFO；
- 僅 foreground、unlocked 且具可驗證 user-interaction context 時顯示 modal；背景請求只能標記
  「需要登入」或返回 `denied`，不能在下次開 App 時突然彈出未預期的插件視窗；
- 使用者提交後，host 將 opaque secret references 交給 v2 source login；事件本身永遠看不到 secret。

### `source.refresh.request`

種類：`command`

```text
SourceRefreshRequestV1(
  scope: SELF | ACTIVE_CONTEXT = SELF,
  reasonCode: SafeCode? = null
)
```

規則：

- `SELF` 只失效／重載 exact source 的可見 catalogue/cache；
- `ACTIVE_CONTEXT` 必須附有效、同 runtime、同 source、短效 `contextRef`，由宿主解析目前 publication/unit；
- 插件不能傳 publication ID、unit ID、repository、query、sync workspace 或 URL 當 target；
- 請求進 per-source coalescing scheduler；最多一個 running 加一個 dirty rerun；
- 默認為低優先背景工作。若宿主知道這是目前可見且由使用者觸發的畫面，可由宿主提升體驗優先級；
- 絕對不能映射到現有 `BrowseCallbacks.refresh()`，也不能觸發 repository scan、全局搜尋、全庫更新、
  同步、app restart 或另一來源刷新；
- 若日後需要 token refresh，新增獨立 `auth.session.refresh.request`，不可擴張此事件語義。

### `auth.logout.request`

種類：`command`

```text
LogoutRequestV1(
  reasonCode: SafeCode?,
  fallbackMessage: PlainText?
)
```

規則：

- 同時需要來源 `LOGIN` 與 host-event `REQUEST_LOGOUT`；
- 插件提供的 reason 只是 hint，不能聲稱使用者已登出；
- 宿主依 invocation context 決定是否要求確認；插件不能略過確認；
- dispatcher 在原 invocation 返回後，對 exact source facade 執行有 timeout 的 `logout()`；
- 遠端 logout 完成或失敗後，只能依 host policy 清除該 artifact/source namespace 的本地 session、
  credential reference 與 cookies，不能影響其他來源；
- pending login 與 logout 的衝突由 host state machine 解決，不能用 `sourceId` 一次 dismiss 全部事件。

### `diagnostic.message.report`

種類：`event`

```text
DiagnosticMessageV1(
  code: SafeCode,
  operation: SafeCode?,
  severity: INFO | WARNING | ERROR,
  retryable: Boolean? = null,
  fallbackMessage: PlainText
)
```

規則：

- 這是問題回報，不是直接呼叫 Snackbar；宿主可顯示、聚合、只記錄或忽略；
- 提交結構化診斷至少需要 `REPORT_DIAGNOSTIC`；要成為使用者可見訊息還必須有
  `REPORT_USER_MESSAGE`，否則只進 bounded plugin diagnostic log；
- UI 固定標示「來源 {host descriptor name} 回報」，避免偽裝 Shinsou 系統錯誤；
- 只允許短純文字；禁止 HTML、Markdown、URL action、自訂 button、顏色、icon、modal 類型與導航；
- 插件不能要求 `fatal/system/urgent`；宿主可降低 severity；
- 已知 `code` 可由宿主 i18n 映射，未知 code 顯示已清理的 fallback；插件不能使用宿主保留 namespace；
- 按 `(bound source, code, operation)` 聚合並顯示重複次數，避免 toast flood；
- raw fallback 不進 portable state、sync、一般 telemetry 或永久錯誤歷史；宿主可遮蔽已知 secret pattern，
  bounded diagnostic log 也必須按 runtime 關閉／TTL 清理；
- 正常函式失敗仍使用 typed return/exception；此事件不能把失敗的操作偽裝成成功。

### 後續候選事件

- `auth.web_challenge.request`；
- `auth.session.invalidated`；
- `source.settings.open.request`；
- `source.cache.invalidated`。

每個候選事件必須有獨立 payload schema、handler、host permission 與測試，不能透過 generic
`execute`、`navigate`、`openUrl`、`refresh(any)` 或任意 action registry 取得權限。

## 能力協商與授權

### 宣告與協商

新套件可宣告：

```json
{
  "minVersion": 1,
  "maxVersion": 1,
  "required": ["command.auth.login.request"],
  "optional": [
    "command.source.refresh.request",
    "event.diagnostic.message.report"
  ]
}
```

Host 只回傳 negotiated version、granted capability IDs 與公開 hard limits。不得回傳 OS/device、
app account、登入狀態、其他插件、資料庫、sync 或內部 feature flag。

未知 required capability 使該 runtime 的事件接口不可啟用；未知 optional capability 保持 denied。

### 權限與功能 capability 分離

`ExtensionCapability` 描述 host -> plugin 可呼叫的來源功能。`PluginHostPermission` 描述
plugin -> host 可提出的請求。兩者不能混為同一 enum 或互相自動授權。

```text
effective permission =
  package/profile declaration
  ∩ host supported capability
  ∩ exact-artifact reviewed/admission grant
  ∩ user grant for the exact digest
  ∩ current platform/lifecycle policy
  ∩ event-specific source capability prerequisite
```

首版 host permissions：

| Permission | 事件 | 額外前置條件 |
|---|---|---|
| `REQUEST_LOGIN_UI` | `auth.login.request` | source `LOGIN`、可互動 lifecycle |
| `REQUEST_SOURCE_REFRESH` | `source.refresh.request` | live exact source |
| `REQUEST_LOGOUT` | `auth.logout.request` | source `LOGIN`、host confirmation policy |
| `REPORT_DIAGNOSTIC` | `diagnostic.message.report` | 只允許 bounded structured diagnostic |
| `REPORT_USER_MESSAGE` | 保留：diagnostic 的 UI projection | production admission 目前拒絕；需 safe presenter，且仍可 suppress/aggregate |
| `REQUEST_BROWSER_CHALLENGE` | 保留：future web challenge | 目前無 production handler；未來仍需 exact reviewed grant、平台隔離能力 |

Grant key 至少包含：

```text
(packageId, version, versionCode, sha256, optional SourceKey)
```

同 package 更新到新 digest 後，舊 queue、context refs、result mailbox 與 grants 全部失效。

## Dispatcher、queue 與背壓

### Handler registry

Handler 只能由宿主程式碼註冊，插件不能安裝 handler 或把任意 action name 對應到 core service。

```kotlin
internal interface PluginSystemEventHandler<P : Any> {
    val name: String
    val payloadVersion: Int
    val requiredPermission: PluginHostPermission
    fun decodeAndValidate(payload: JsonElement): P
    suspend fun handle(context: AuthorizedPluginEventContext, payload: P): PluginEventOutcome
}
```

Registry key 是 `(protocolVersion, kind, name, payloadVersion)`。新增事件只需新增 typed payload、
permission、handler 與測試，不必擴張 bridge 或把 `JsonObject` 傳進 UI/domain。

### Bridge ingress

```kotlin
internal fun interface ScopedPluginSystemEventSink {
    /** CPU-only, bounded, non-suspending; never waits for UI or plugin work. */
    fun submit(scope: BoundPluginScope, utf8Envelope: ByteArray): PluginEventReceipt
}
```

Rhino／JavaScriptCore 只實作同一個 native transport，例如：

```javascript
bridge.requestHostEvent(json)
bridge.getHostEventCapabilities()
bridge.drainHostEventResults(max) // optional negotiated pull channel
```

Typed JS helpers由共用 bootstrap/compatibility shim 建立：

```javascript
bridge.system.requestLogin(reason)
bridge.system.requestRefresh(scope)
bridge.system.requestLogout(reason)
bridge.system.reportMessage(message)
```

既有 `bridge.requestLogin(reason)` 轉呼叫第一個 helper；平台 native bridge 不再為每個事件新增一套
Rhino/JSC 特例。

### 三條處理 lane

| Lane | 訊息 | 排程策略 | 過載策略 |
|---|---|---|---|
| Modal | login、logout、future challenge | exact event ID、per-source serial、跨來源公平 FIFO、TTL | 拒絕／合併同源等價請求 |
| Refresh | source refresh | per-source actor、conflate、最多 running + dirty rerun | 合併，不擴大 scope |
| Transient | diagnostic message | code/operation 聚合、token bucket | 先丟低優先 diagnostic，不阻塞 command |

Dispatcher 使用 `SupervisorJob` 隔離不同來源的失敗。單一來源內保持 serial，不同來源可以有限並行。
UI consumer 或某一 handler 失敗不能取消整個 dispatcher。

### 初始 hard limits

以下為 host policy 初值，不是插件可提高的 wire 權利：

- envelope：8 KiB UTF-8；
- JSON nesting：4；禁止重複 key；
- request ID／idempotency key／code：64 個安全 ASCII 字元；
- event name：96 個安全 ASCII 字元；
- reason：256 UTF-8 bytes；diagnostic fallback：512 UTF-8 bytes；
- map：16 entries；list：16 entries；
- per-source pending：32；global pending：128；
- per-runtime general token bucket：burst 5、20/minute；
- login：同來源最多一筆 pending，並有獨立 cooldown；
- logout：同來源最多一筆 pending；
- refresh：按 `(SourceKey, scope, contextRef)` 合併；
- diagnostic：按 `(SourceKey, code, operation)` 在時間窗內聚合。

即使插件不停變換 request ID 或 idempotency key，source/type token bucket 仍必須生效。

Runtime close、卸載、來源停用、artifact replacement 或權限撤銷時，必須清除該 scope 的 pending
request、context refs 與 result mailbox。

## UI 與秘密資料隔離

UI presenter 只發布宿主建立的安全 DTO，例如：

```text
HostLoginIntent(eventId, sourceKey, trustedDisplayName, safeReasonCode, safeFallback)
HostLogoutIntent(eventId, sourceKey, trustedDisplayName, requiresConfirmation)
HostDiagnosticIntent(eventId, trustedDisplayName, severity, localizedText, occurrenceCount)
```

DTO 不攜帶 runtime、callback、raw JSON、raw exception、credential、cookie、UA 或 repository object。
Dismiss／complete 必須使用 exact event ID；不能再用 `sourceId` 刪掉該來源所有不同事件。

Host UI policy：

- App locked 時不顯示插件 modal；
- background runtime 不得強制前景、不發 system notification；
- source disabled/uninstalled/replaced 時立即 expire UI intent；
- title、button、icon、色彩、可點擊 action、導航與顯示位置全由宿主決定；
- 插件文字移除控制字元，當純文字顯示，不把 URL 轉成可點擊連結；
- 登入畫面不把已保存 password 回填到一般 UI state；
- 錯誤訊息明確帶可信 source label，不得看起來像 Shinsou 核心警告。

## Shinsou／ShuYue 相容策略

### Legacy Shinsou

- 保留現有 `bridge.requestLogin(reason): Boolean`；
- compatibility mapping：`accepted`／`deduplicated` -> `true`，其他 disposition -> `false`；
- 只有 trusted artifact、`supportsLogin` 與既有登入條件成立時給予此 legacy mapping；
- Repository 未提供 `sha256` 時，由宿主對實際安裝的 script bytes 計算 artifact digest；事件 grant
  仍綁定該 digest，不能退回只用 package ID／version；
- 舊 manifest 沒有 event permission 欄位，因此不自動授予 refresh/logout/message；
- 官方套件可在確認舊 client 解碼行為後，使用新 sidecar/manifest declaration 漸進 opt in；
- legacy `Long sourceId` 只作 runtime adapter 的 lossless projection，gateway identity 使用完整 scope。

### Reviewed ShuYue

- `LOGIN_PROMPT` 映射為 `REQUEST_LOGIN_UI`；
- `BROWSER_CHALLENGE` 未來只映射 `REQUEST_BROWSER_CHALLENGE`；
- `CREDENTIAL_ACCESS`／`COOKIE_STORAGE` 不是 UI event permission，不能推導 refresh/logout/message；
- 既有 `console.log` 不自動映射為使用者可見訊息；`REPORT_USER_MESSAGE` 必須獨立核准；
- refresh/logout/message 必須出現在 exact digest reviewed profile 並重新取得核准；
- ShuYue index 目前採嚴格解碼，首階段不向舊 index 任意加入未知欄位，先由 digest-pinned profile
  宣告；
- 新 digest 不繼承舊 profile/grant，queued event 也不能跨 runtime generation。

### Repository 與 content contract

- 舊 Shinsou array、舊 ShuYue array 與 `shinsou-unified-v1` 保持不變；
- repository URL 或 `index.json` 字樣不是 runtime protocol 判斷依據；
- system event protocol 不改來源的 `manga/novel/both` content type；
- 不提高 extension content contract v2；兩套協議只在 host admission 層以 exact source identity 相交。

## 已知相鄰安全缺口

新事件邊界本身不能證明整個既有插件 runtime 已完全隔離。下列問題必須列入後續安全工作，並在
向一般第三方套件開放高權限事件前完成：

1. Legacy v1 bridge 仍可直接讀寫 raw username/password/cookies，storage 主要以 `Long sourceId`
   namespace。應遷移到 `packageId + SourceKey` scope 與 opaque secret references。
2. `BrowseSnapshot`／來源設定 UI 仍可能攜完整 password 與 cookie value。應改為 `hasCredential`、
   masked username、cookie metadata/count，secret editor 直接寫入 vault。
3. Web challenge DTO 目前把 UA/cookies 穿過 UI；應改為 opaque `ChallengeSessionId`，由平台服務在
   內部 seed/capture cookies。
4. Android Web challenge 使用全域 `CookieManager`，需要真正的 per-source 隔離，不能讓一個插件
   清除或讀取其他 WebView session。
5. Rhino runtime 已安裝 deny-all `ClassShutter`，讓 JavaScript 無法看見 host classes；仍需維持
   sandbox escape／host-class regression tests，且不能只憑此一層就宣稱整個 JVM runtime 絕對安全。
6. `PluginBrowseAdapter` 的單一 `operationMutex` 讓登入、登出、repository refresh 與設定互相阻塞。
   新 dispatcher 不得持有該 mutex 等待 UI/plugin；domain handler 應拆成 exact-source serial actors。
7. Raw `Throwable.message` 目前在部分 UI 路徑直接顯示。插件事件必須走安全 code/fallback renderer，
   不能重用這條 raw error path。

## 落地階段與目前進度

下列 E0～E6 是原始拆解，不是每一階段都已完成。Production 已落地 V1 wire／codec、gateway、
exact-artifact admission、四個 host handler、runtime lifecycle invalidation、Rhino／JavaScriptCore bridge
與主要 deterministic tests；`REPORT_USER_MESSAGE` presenter、browser-challenge event、result mailbox 與
部分 runtime／外部 smoke 加固仍未完成。原始階段條目保留作追蹤用途，請以上方「目前 production
狀態」與實際程式碼為準；條目中的 imperative wording 不代表該項目前一定尚未開始。

### E0：契約與測試骨架

- 新增 commonMain wire DTO、strict codec、limits、bound scope、receipt 與 handler registry；
- 建立 parser fuzz、spoofing、oversize、unknown version/type 與 Rhino/JSC parity tests；
- 不改 UI 行為。

### E1：Gateway 與登入相容層

- 新增有界 dispatcher 與 exact-artifact permission seam；
- 讓既有 `PluginLoginRequester` adapter 轉入 `auth.login.request`；
- 登入 queue 改用 exact scope + event ID + TTL，UI 暫時保持相同外觀；
- 驗證 `requestLogin()` 仍立即返回且不死鎖。

### E2：刷新與錯誤回報

- 新增 per-source refresh scheduler，與 repository/global refresh 明確分離；
- 新增 safe diagnostic presenter、聚合與限流；
- 完成 locked/background lifecycle policy。

### E3：登出與授權生命週期

- 新增 exact-source logout handler、確認策略、timeout 與本地 scope cleanup；
- queued request 執行前重驗 grant/runtime generation；
- runtime close/update/uninstall/revoke integration tests。

### E4：跨規格 permission admission

- Reviewed ShuYue profile 映射既有 `LOGIN_PROMPT`；
- 新 Shinsou artifact declaration/sidecar 與安裝核准 UI；
- 套件更新顯示新增 event permissions，禁止靜默繼承。

### E5：秘密與 runtime 加固

- opaque credential/challenge handles、safe browse UI projection、per-source WebView cookies；
- Rhino host-class denylist 與 escape tests；
- 官方 Shinsou/ShuYue 套件改用 native system event helper。

### E6：可選結果通道與 legacy 收斂

- 加入 bounded pull-based result mailbox；
- 在安全遷移完成後逐步 deprecated 直接 credential/cookie bridge；
- 不移除仍在支援範圍內的 legacy `requestLogin` shim。

## 測試矩陣

### Contract 與平台一致性

- 同一 envelope 在 Rhino／JavaScriptCore 產生相同 receipt；
- old Shinsou array、strict ShuYue index、unified-v1 與 native system-v1 共存；
- unknown protocol/name/payloadVersion、unknown required/optional capability；
- invalid UTF-8、deep JSON、duplicate key、oversize、control chars 與 parser fuzz。

### 身份、權限與生命週期

- 插件偽造 package/source/displayName/digest 無效；
- 兩 package 使用相同 legacy Long ID 仍完全隔離；
- 未宣告、未授權、缺 source capability、digest 更新、grant revoke 均拒絕；
- 入列後 update/uninstall/disable/runtime close 使事件 expired；
- 過期或跨 runtime 的 `contextRef` 拒絕。

### 行為與併發

- login immediate receipt 與後續 login 不重入/不死鎖；
- event-ID-specific dismiss、跨來源公平 FIFO、同來源去重與 TTL；
- refresh 只觸及 exact source、正確 conflate/dirty rerun，且不呼叫 repository refresh；
- logout 只清 exact source scope，failure/timeout 不影響 dispatcher；
- source A 永遠不能影響 source B；
- queue overflow、token bucket、變換 message ID 無法繞過限流。

### UI 與秘密

- locked/background 不彈未授權 modal；
- HTML/Markdown/URL/control chars/oversize/phishing-like message 被拒絕或安全純文字化；
- receipt/result/log/Flow/UI DTO 不含 password、cookie、token、header、stack 或 raw secret reference；
- UI consumer exception 不會取消其他來源事件；
- accessibility/i18n 使用 host-owned title/action/source label。

## 完成條件（尚未全部達成）

系統事件接口只有在下列條件同時成立時才算完整完成，而不是僅新增幾個 bridge 方法。目前四個 V1
handler 已進 production，但 user-message projection、browser-challenge event 與部分加固仍使整體狀態
維持 partially implemented：

- 四個 V1 訊息走同一 wire transport 與 common codec；
- runtime 注入 exact scope，插件無法指定或偽造 identity/target；
- event permission 與 `ExtensionCapability` 分離且綁定 exact artifact digest；
- ingress 有界、同步只回 receipt、所有 UI/domain 工作非同步且不可重入；
- login/logout/message 由 host UI policy 控制，refresh 僅 exact source；
- revoke/update/uninstall/runtime close 能取消 stale queue；
- legacy Shinsou/ShuYue 相容測試通過，舊套件沒有新增隱含權限；
- Rhino 與 JavaScriptCore contract parity 測試通過；
- 安全、限流、生命週期與秘密不外洩測試通過。
