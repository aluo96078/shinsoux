# ADR-0002：開放第三方套件生態的隔離執行環境與最小權限模型

- 狀態：Accepted（規劃階段，尚未實作；第 14 節 D1–D12 已由使用者決策，開放問題 1–6 仍未決）
- 日期：2026-10-25；決策回寫：2026-10-25
- 程式碼基準：`v1.0.1-beta.8`（commit `f5fff7f`）；本機工作區較舊，下文引用的行為以 beta.8 為準
- 適用專案：[Shinsou X KMP](https://github.com/aluo96078/shinsoux)、[Shinsou extension repository](https://github.com/aluo96078/shinsou_plugin)
- 相關文件：[插件系統總覽](../PLUGIN_SYSTEM.md) · [插件系統事件接口架構](../PLUGIN_SYSTEM_EVENT_ARCHITECTURE.md) ·
  [統一插件儲存庫契約](../UNIFIED_PLUGIN_REPOSITORY.md) · [ADR-0001](0001-unified-publication-content-foundation.md)
- 決策範圍：第三方 JavaScript 套件的執行引擎、隔離邊界、bridge／IPC、權限、發佈者信任、資源限制、
  威脅模型、與既有 reviewed 官方套件的共存與遷移

## 1. 背景

### 1.1 目前狀態（beta.8）

- 套件是 JavaScript。Android／Desktop 以 Rhino（`RhinoScriptPluginRuntime.kt`）、iOS 以
  JavaScriptCore（`JavaScriptCoreScriptPluginRuntime.kt` + `IosJavaScriptBootstrap.kt`）在 **App 自身程序內**執行。
- 兩個引擎都沒有可依賴的 per-runtime heap 上限與強制中止隔離（`PLUGIN_SYSTEM.md`「JavaScript runtime 與跨平台限制」）。
  Rhino 只能靠 instruction observer 計數，JSC 的公開 API 沒有記憶體上限。
- 因此 production 只執行 `OfficialShinsouReviewedCatalog`／reviewed ShuYue catalog 精確命中的 artifact：
  `PluginManager` 只有在 catalog 命中時才發出 `InProcessScriptProvenance`，而 engine 在配置 runtime 前以
  `requireExactMatch()` 比對 package／version／versionCode／SHA-256／`SourceKey`／最終 evaluated bytes。
  一般 repository JavaScript 即使已安裝、簽章、使用者核准，也保持 inert。
- 已有可沿用的安全基礎：
  - `PluginNetworkPolicy`：exact HTTPS origin 的 `requestOrigins`、`credentialOrigins`、`contentOrigins`、
    `browserSessionOrigins`；DNS pinning、私有位址封鎖、header sanitizer、有界 response、redirect 逐 hop 檢查、
    與 API request plane 分離的 Host content plane。
  - `RepositoryTrust.kt`：`shinsou-signed-envelope-v1`（Ed25519、domain separation、canonical JSON）、
    `RepositoryTrustRootStore`（首次 pin 不可變，需明確核准才可替換）、`RepositorySecurityStateStore`
    （sequence replay／equivocation、artifact downgrade watermark）。
  - `events/`：四層能力（source capability／runtime permission／Host permission／negotiated system event）、
    exact-artifact durable admission record（`PENDING`／`GRANTED`／`REVOKED`）、identity 由 runtime 注入。
  - `PluginExecutionLimits`：Host 擁有、套件不可提高的 CPU、bridge、DOM、結果大小限制。
  - `BoundPluginStorage` + `PermissionFilteredPluginStorage`：依 `SourceKey` 分隔並依權限過濾的儲存。

### 1.2 問題

1. 每修正一個套件都要發佈 App，作者成為唯一瓶頸。
2. 沒有人能自行發佈可執行套件，生態封閉。
3. 曾評估的「遠端簽章審核清單」只把清單搬到線上，仍是「作者審核才可執行」，不符合開放目標，已否決。

### 1.3 目標

- 任何開發者都能開發、簽署、發佈套件；使用者自行決定加入哪些 repository、信任哪些開發者、安裝哪些套件。
- 惡意或有 bug 的第三方套件 **不能**：讀寫 App 核心資料、讀取其他套件的資料或 secrets、對宣告以外的
  網路位址發出請求、讓 App 當機或長時間卡住、冒充官方或冒充 Host UI。
- 不降低既有 reviewed 官方套件的相容性。

### 1.4 非目標

- 不保證第三方套件「行為正確」或「內容合法」；只保證影響範圍被限制在其被授權的範圍內。
- 不讓第三方套件取得 reviewed tier 的放寬能力（in-process provenance、large-text profile、`Connection`
  header 移除、未 pinned 的 browser session 等）。
- 不支援 Mihon／Tachiyomi APK 執行（維持 metadata-only）。

## 2. 決策摘要

1. **兩級信任（Execution tier）**：
   - `REVIEWED_IN_PROCESS`：維持現狀。官方內建 key 簽署 + reviewed catalog exact match → Rhino／JSC
     in-process，可用 reviewed profile。`InProcessScriptProvenance` 的發放條件不變。
   - `SANDBOXED`：所有其他可執行套件。只在隔離環境執行，預設最小權限，權限上限（tier ceiling）由 Host 固定。
   - 沒有第三種「受信任的第三方 in-process」路徑。
2. **引擎**：第三方套件統一使用 **QuickJS（建議 quickjs-ng 分支）**，每個 package 一個獨立 `JSRuntime`，
   以 `JS_SetMemoryLimit`、`JS_SetMaxStackSize`、`JS_SetInterruptHandler` 施加限制。引擎與 HTML 解析器、
   bridge shim 一起編成單一 C 程式庫 **`shinsou-sandbox-core`**，四平台共用同一份原始碼與同一個 bridge ABI。
3. **程序／記憶體隔離（第二道牆，與引擎限制疊加）**：
   - Android：`android:isolatedProcess="true"` bound service，以 `bindIsolatedService` 讓每個 package
     有自己的 isolated process。為此 **App 的 Android minSdk 由 API 26 提高到 API 29（Android 10）**（D2），
     不提供多個套件共用程序的降級模式；Android 8～9 使用者之後無法再更新 App。
   - macOS／Windows：Host 啟動原生 helper 子程序 `shinsou-sandbox`，以 OS sandbox（macOS App Sandbox
     無網路／檔案 entitlement；Windows AppContainer + Job Object）限制。
   - iOS：不能開子程序。預設 **QuickJS 編譯成 WebAssembly，以 WASM 直譯器在 App 程序內執行**，用 WASM 的
     線性記憶體邊界取代程序邊界。原生 QuickJS 作為使用者可選、明確標示隔離較弱的效能模式，是否提供依 S0
     效能結果決定（D1）。單靠原生 QuickJS 的限制只能防 DoS，不能防記憶體安全漏洞逃逸（見 §3.4）。
4. **沙箱內沒有任何 I/O 能力**：沒有網路、檔案、時鐘以外的系統呼叫可用。所有 HTTP、儲存、Cookie、
   登入、system event 都變成跨邊界 bridge 請求，由 Host 的 `SandboxBridgeDispatcher`（commonMain）重用既有
   `PluginNetworkPolicy`、`BoundPluginStorage`、event gateway 執行。套件身分由「哪一條 channel」決定，
   不從 payload 讀取。
5. **DOM 解析放在沙箱內**（原生 C 解析器編進 `shinsou-sandbox-core`），Host 不為第三方套件解析不可信 HTML，
   也避免每個 `domText()` 都跨 IPC。
6. **權限**：沿用四層模型，新增 tier ceiling 與一個 runtime permission（`COOKIE_READ`）。預設授予低風險能力；
   帳密、可讀 Cookie、browser challenge、登入 UI、修改遠端帳號、動態 CDN 等敏感能力需使用者逐項核准。
7. **發佈者信任**：信任單位由「repository URL」改為「發佈者 Ed25519 金鑰」，URL 只是 mirror。
   提供兩種由使用者選擇的信任模式（D3）：(a) TOFU＋顯示指紋＋可選比對（預設暫定）、(b) 一律要求指紋比對。
   新增 key rotation、self-revocation 與 index 到期時間；到期只警示、不停用（D8）。**不設中央 denylist**，
   改由每位使用者自行維護本機封鎖名單，可封鎖套件或發佈者金鑰（D5）。
8. **相容性與 API**：沙箱內提供與現行相同的同步 `bridge.*` API，現有官方腳本可不修改在沙箱 tier 執行；
   同時在第一階段（S1）就提供非同步 `bridge.net.fetch`（D10）。官方套件也搬進 sandbox tier，由作者的發佈者
   金鑰簽署，更新免發 App；reviewed catalog 只保留真正需要 reviewed profile 的例外（D9）。
9. **發佈管道**：App 只透過 GitHub Release 自行發佈，不上架 App Store、Google Play 或桌面商店（D12），
   因此設計不受商店審核政策限制，但仍受各平台簽章、公證與側載限制（§11）。

## 3. JS 引擎選型比較

### 3.1 候選方案

| 方案 | 說明 |
|---|---|
| A. 維持現狀 | 只執行 reviewed catalog；第三方 inert。 |
| B. Rhino + 程序隔離 | Android isolated process 內跑 Rhino；Desktop 子 JVM 跑 Rhino；iOS 無解（無 JVM）。 |
| C. QuickJS 原生 + 程序隔離（iOS 原生 in-process） | 每 package 一個 `JSRuntime`；Android／Desktop 加程序隔離；iOS 只靠 QuickJS 限制。 |
| D. QuickJS 原生 + 程序隔離（iOS 改 QuickJS-in-WASM） | 同 C，但 iOS 以 WASM 直譯器包住 QuickJS。**建議方案。** |
| E. 全平台 QuickJS-in-WASM | 四平台都跑 WASM；Desktop 可用 JIT 型 WASM runtime（wasmtime），Android／iOS 用直譯器。 |
| F. iOS 用 WKWebView 隔離 | JS 在 WebKit 的獨立 WebContent 程序執行（有 JIT），bridge 用 `WKScriptMessageHandlerWithReply`。 |
| G. 其他引擎（Hermes、Boa 等） | 列入比較但不深入。 |

### 3.2 比較

| 面向 | B. Rhino + 程序 | C. QuickJS + 程序／iOS 原生 | **D. 建議** | E. 全 WASM | F. WKWebView（iOS） |
|---|---|---|---|---|---|
| 記憶體上限 | 程序級（ART heap／`-Xmx`），無 per-runtime 精確上限 | `JS_SetMemoryLimit` 精確 per-runtime | 同 C；iOS 另有 WASM linear memory 上限 | WASM memory max pages | 只能靠 jetsam 殺 WebContent，無精確上限 |
| CPU 中止 | instruction observer；程序可 kill | interrupt handler；程序可 kill | 同 C；iOS 由 WASM runtime 從另一執行緒 terminate | WASM fuel／epoch | 只能銷毀 WebView，非同步 |
| 引擎漏洞逃逸 | Android／Desktop 被程序 sandbox 擋住；iOS 無方案 | **iOS 直接取得 App 程序權限（Keychain、Cookie、全部資料）** | 被程序 sandbox 或 WASM 邊界擋住 | 被 WASM 邊界擋住，Android／Desktop 再加程序 | WebKit sandbox 很強 |
| 網路 egress 控制 | 程序無網路權限 → 完整 | 同左；iOS 靠「不提供 API」 | 完整（沙箱內沒有 socket API） | 完整 | **不完整**：WebSocket／WebRTC 等通道無法全部用 content rule 封住（Android WebView 文件已觀察到同類問題） |
| 同步 bridge 相容 | 是 | 是 | 是 | 是 | **否**：message handler 是非同步，現有同步腳本要改寫 |
| 效能（相對現況） | 與現在 Rhino 相近；Desktop 子 JVM 啟動慢、RSS 大 | QuickJS 直譯與 Rhino 直譯／JSC 無 JIT 相近（需 S0 實測） | iOS 慢數倍（WASM 直譯 × JS 直譯，需實測） | Android 同樣慢數倍 | 最快（JIT） |
| 四平台可行性 | iOS 不可行 → 仍需第二個引擎 | 可行 | 可行 | 可行 | 只 iOS |
| 引擎一致性 | Rhino／JSC 兩套 | 一套 | 一套（同一份 C，兩種編譯目標） | 一套、一種二進位 | 第三套 |
| 維護成本 | 中（但 iOS 缺口無法補） | 中：C 依賴、JNI／cinterop、helper 簽章 | 中高：多一條 wasm32 編譯與 WASM runtime | 中：只一種產物，但 Android 效能代價 | 高：第三套 bridge 與行為差異 |

### 3.3 選擇 D 的理由

- 只有 QuickJS 同時提供精確的 per-runtime 記憶體上限、可靠的 interrupt、與可嵌入四平台的 C 原始碼。
- 同一份 `shinsou-sandbox-core` 編成原生（Android／Desktop）與 wasm32（iOS），bridge ABI、DOM 行為、
  限制計數方式相同，消除現在 Rhino／JSC 的 parity 問題。
- 程序隔離已能在 Android／Desktop 擋住引擎漏洞，再加 WASM 只是多付效能；iOS 沒有程序可用，WASM 是唯一
  能把「引擎漏洞」與「App 程序」分開的手段。
- E 方案在 Android 上要付不必要的直譯代價；保留為 D 的後備（例如 Android 某些廠商 ROM 的 isolated process 不穩定時）。
- F 方案的 egress 控制不完整且破壞同步 API，不適合作為安全邊界；可作為未來「效能模式」研究，不在本 ADR 範圍。

### 3.4 iOS：單靠 QuickJS 限制是否足夠？

| 威脅 | 原生 QuickJS in-process | QuickJS-in-WASM in-process |
|---|---|---|
| 無窮迴圈、CPU 濫用 | 足夠（interrupt handler） | 足夠（WASM runtime terminate + interrupt handler） |
| 記憶體炸彈 | 足夠（`JS_SetMemoryLimit`），但 C 層 allocator 失敗路徑仍需測試 | 足夠，且有 linear memory 硬上限 |
| 讀其他套件資料 | 靠「不同 `JSRuntime` 不共享物件」的邏輯隔離 | 每個套件一個 WASM instance，記憶體不共享 |
| **引擎記憶體安全漏洞**（UAF、型別混淆、越界） | **不足**：攻擊者取得 App 程序的任意讀寫，可讀 Keychain 項目、所有 Cookie、書庫、其他套件 secrets，可對任意網址發請求 | 漏洞只能破壞該 instance 的線性記憶體；要逃逸還需 WASM runtime 本身的漏洞 |

QuickJS 歷年持續被 fuzzing 找到記憶體安全問題，第三方套件會刻意構造觸發輸入，因此「原生 QuickJS
in-process」只適合作為使用者明確選擇的效能後備，不當預設。已決策（D1）：預設 QuickJS-in-WASM，接受 iOS 上的
效能代價；原生 QuickJS 效能模式是否提供，依 S0 量測結果決定。

WASM runtime 候選（S0 spike 驗證）：WAMR（classic／fast interpreter）、wasm3（維護狀態需確認）。
iOS App 不能使用 JIT，因此只能選直譯器；要確認支援從另一執行緒中止執行、限制 linear memory 與 stack、
以及 host import 的同步呼叫。

### 3.5 Binding 取得方式

- 不直接依賴第三方 KMP QuickJS 包裝程式庫當安全邊界（其 API 通常把 Kotlin 物件暴露給 JS，與「沙箱內只有
  bridge」相反）。可參考其建置方式，但自建薄 C shim：`sandbox_core_init / eval / invoke / on_bridge_call`。
- 版本固定、vendored 原始碼、可重現建置，C 依賴另列 SBOM（§9.5）。

## 4. 各平台隔離架構

### 4.1 共同結構

```text
Host（App 程序，受信任）
  PluginManager ──► ExecutionTierResolver ──┬─► REVIEWED_IN_PROCESS：Rhino／JSC（現狀）
                                            └─► SANDBOXED：SandboxRuntimeFactory
                                                    │
                                     SandboxTransport（平台實作）
                                                    │  SandboxProtocol v1 frames
                                                    ▼
不可信邊界 ═══════════════════════════════════════════════════════════
  沙箱（isolated process／helper 子程序／WASM instance）
    shinsou-sandbox-core：
      QuickJS JSRuntime（每 package 一個）
      HTML parser + CSS selector（沙箱內 DOM）
      bridge shim：同步 bridge.* → 送出 BridgeCall frame，阻塞等待 BridgeResult
      無網路、無檔案、無環境變數；時間只提供單調時鐘
```

- `SandboxRuntimeFactory` 實作既有 `ScriptPluginRuntimeFactory` 介面，所以 `PluginManager`、V2 adapter、
  Browse／Reader／Download 呼叫端不需要知道 tier。
- `SandboxRuntimeFactory` 收到非 null 的 `inProcessScriptProvenance` 一律拒絕；reviewed profile
  （large-text 等）不能在 sandbox tier 啟用。
- 沙箱只回傳資料，不回傳可呼叫物件；Host 對每個回傳值重做 bounded decode（沿用現有
  `pluginJsonArrayCountsAtMost`、result limits）。

### 4.2 SandboxProtocol v1（commonMain）

- Frame：長度前綴（u32）+ CBOR（kotlinx.serialization），單一 frame 上限 1 MiB；較大的資料以
  `chunk` frame 串流，總量仍受 §7 限制。
- Frame 種類：

| 方向 | Frame | 用途 |
|---|---|---|
| Host → 沙箱 | `Init{script, limits, negotiatedApi}` | 載入腳本；limits 由 Host 給，沙箱不可修改 |
| Host → 沙箱 | `Invoke{callId, method, args}` | 呼叫 `getPopularManga` 等 hook |
| Host → 沙箱 | `Cancel{callId}` | 取消；沙箱的 interrupt handler 會看到 |
| 沙箱 → Host | `BridgeCall{callId, seq, op, args}` | 套件呼叫同步 bridge；沙箱執行緒阻塞等待 |
| Host → 沙箱 | `BridgeResult{seq, ok|error}` | 同步 bridge 回應；錯誤只用穩定錯誤碼 |
| 沙箱 → Host | `AsyncBridgeCall{callId, opId, op, args}` | 非同步 bridge（`bridge.net.fetch`）；送出後 JS 繼續執行 |
| Host → 沙箱 | `AsyncBridgeResult{opId, ok|error}` | Host 完成請求後送回；沙箱在 job loop 中 resolve／reject 對應 Promise |
| Host → 沙箱 | `AsyncCancel{opId}` | Host 因取消、逾時或撤銷而中止某個 pending op |
| 沙箱 → Host | `Result{callId, value}`／`Failure{callId, code}` | hook 結果 |
| 沙箱 → Host | `Log{bytes}`／`LimitExceeded{kind}` | 有界記錄與限制事件 |

- 一個 `JSRuntime` 一次只有一個 invocation（與現在 runtime worker 序列化一致），避免 bridge 重入。
  invocation 內可以有多個 pending 非同步 op；hook 回傳 Promise 時，invocation 直到該 Promise settle 才結束，
  結束時仍 pending 的 op 由 Host 取消並丟棄結果（§5.3）。
- 協定版本在 `Init` 協商；未知版本 fail closed。
- Codec 是 fuzz 目標（§13）。
- iOS 的 WASM 不經過 OS IPC，但 host import 收發同樣的 frame bytes，讓三種 transport 共用同一個
  dispatcher 與測試。

### 4.3 Android

- `SandboxService` 宣告 `android:isolatedProcess="true"`、`android:exported="false"`，可搭配
  `android:useAppZygote="true"`（API 29+）降低啟動成本。isolated process 以獨立 uid 執行，沒有 App 權限、
  沒有網路、不能讀 App 資料目錄。
- `Context.bindIsolatedService(intent, flags, instanceName = packageKey, executor, conn)`（API 29+），
  每個 package 一個 isolated process。
- 已決策（D2）：App 的 Android minSdk 由 26 提高到 29（beta.8 定義在 `gradle/libs.versions.toml` 的
  `android-minSdk`），所有裝置都使用每套件一個 isolated process 的完整隔離，不提供共用程序的降級路徑。
  代價是 Android 8～9（API 26–28）使用者之後無法再更新 App，只能停留在最後一個支援 API 26 的版本；
  這需要在 release notes 預告（§12 S2）。
- IPC：AIDL `ISandbox`（Host → 沙箱）與 `IHostBridge`（沙箱 → Host）。Host 在 bind 時傳入每個 package
  專屬的 `IHostBridge` binder，該 binder 物件本身就是身分 capability；沙箱無法取得其他 package 的 binder。
- Binder 單次交易約 1 MB 上限，現有 bridge 結果上限是 4 MiB：大 payload 改用
  `ParcelFileDescriptor.createPipe()` 串流 frame；minSdk 29 後也可直接使用 `SharedMemory`（API 27+），
  由 S0 比較兩者的延遲與實作複雜度後擇一。
- Host 端用 `linkToDeath` 偵測沙箱崩潰；崩潰只讓該 package 的 runtime 失效，並計入 crash-loop 計數（§7）。
- 沙箱內是 ART + JNI 載入 `libshinsou_sandbox_core.so`；ART heap 另有程序級上限，作為 QuickJS 限制之外的第二層。

### 4.4 Desktop（macOS／Windows）

- Host 以 `ProcessBuilder` 啟動隨 App 簽章發佈的原生 `shinsou-sandbox` 執行檔，一個 package 一個子程序，
  IPC 只用繼承的匿名 stdin／stdout pipe（不開 socket、不用具名 pipe），stderr 丟棄或限量收集。
- 不使用子 JVM：每個 JVM 約數十至上百 MB RSS、啟動慢；原生 helper 只有數 MB。
- macOS：
  - helper 單獨簽章，entitlements 只有 `com.apple.security.app-sandbox`，**不含**
    `network.client`、使用者檔案存取等 entitlement；Hardened Runtime，不允許 JIT。
  - 記憶體：QuickJS 上限為主；Host 另以 `proc_pid_rusage` 監控 RSS 超限即 kill（macOS 不可靠支援 `RLIMIT_AS`）。
  - 需在 S0 驗證：主 App 未啟用 App Sandbox 時，helper 能否獨立套用 sandbox；若不能，改用 `sandbox_init`
    deny-all profile（API 已 deprecated 但仍可用，需評估長期風險）。
- Windows：
  - 以 AppContainer（不給任何 capability，尤其是 `internetClient`）＋ Low integrity 啟動。
  - 指派 Job Object：`JOB_OBJECT_LIMIT_PROCESS_MEMORY`、CPU rate control、`KILL_ON_JOB_CLOSE`、
    `ACTIVE_PROCESS_LIMIT = 1`（不能再開子程序）、UI restrictions。
  - Host 結束時 job 一起結束，不留孤兒程序。
- 打包：`shinsou-sandbox` 要為 macOS arm64／x64、Windows x64 交叉編譯並簽章／公證，納入 `docs/BUILDING.md`、
  `docs/RELEASING.md` 的流程。

### 4.5 iOS

- `shinsou-sandbox-core` 編成 `wasm32-wasi`（只連結 Host 提供的 import，不連結 WASI 檔案／socket API），
  以 WASM 直譯器在專屬背景執行緒執行，每個 package 一個 instance、各自的 linear memory 上限。
- Host import 只有一個：`host_bridge(frame_ptr, frame_len, out_ptr, out_cap) -> len`；Host 在該執行緒上同步處理
  （與現在 JSC 的 `runBlocking` bridge 模式相同），網路仍在 Host 的 coroutine／URLSession 上執行。
- 中止：Host watchdog 在 deadline 後呼叫 runtime 的 terminate；該 instance 丟棄並重建。
- 現有 iOS JS DOM bootstrap 不再用於 sandbox tier；DOM 改由編進 WASM 的 C 解析器提供，與 Android／Desktop 一致。
- 效能模式（D1）：預設一律 WASM。若 S0 量測顯示 WASM 效能不足以日常使用，才提供使用者可選的
  「原生 QuickJS（較快、隔離較弱）」模式：預設關閉、可逐套件或全域開啟、UI 明確說明引擎漏洞會直接危及 App。
  若 S0 顯示 WASM 已可接受，則不提供此模式，減少一條需要維護與測試的路徑。

### 4.6 程序／instance 生命週期

- 惰性啟動：第一次呼叫該 package 時才啟動。
- 閒置回收：無呼叫 60 秒後關閉（Android unbind、Desktop 關 stdin 後 kill、iOS 釋放 instance）。
- 同時存活上限（LRU 淘汰）：Android 4、Desktop 8、iOS 3（初值，S0 依記憶體實測調整）。
- 背景執行：只允許 Host 排程的工作（例如書庫更新），且使用較低的 CPU／請求預算；套件不能自行排程背景工作。

## 5. Bridge API 跨邊界對應

### 5.1 對應表

沙箱內保留與現行相同的同步 `bridge.*` 名稱與參數（來源：`RhinoScriptPluginRuntime.kt` 的 binding 與
`IosJavaScriptBootstrap.kt`），讓既有腳本可直接執行。

| 現行 API | 跨邊界方式 | Host 端行為（sandbox tier） | 所需權限 |
|---|---|---|---|
| `httpGet`／`httpGetWithHeaders`／`httpGetResponse`／`httpPost`／`httpPostResponse` | `BridgeCall{op: net.request}` | 經 `PluginNetworkPolicy.authorize`（exact `requestOrigins`、DNS pinning、私有位址封鎖、forbidden header、redirect 逐 hop）；Cookie、UA、proxy 由 Host 附加；回傳 headers 一律移除 `Set-Cookie`、`Authorization` 類 | `NETWORK` |
| `httpPostBatch` | `net.batch` | 沿用現有 128 items／32 concurrent／4 MiB 上限 | `NETWORK` |
| `bridge.net.fetch`（新增，非同步，S1） | `AsyncBridgeCall{op: net.request}` | 與同步 HTTP 走同一個 `PluginNetworkPolicy` 與 Host 網路層，只差在不阻塞沙箱；並行數與總量受 §7 限制 | `NETWORK` |
| `browserSessionRequest` | `net.browserSession` | 只允許 `browserSessionOrigins`，維持 DNS pinning；不提供 developer unpinned 例外 | `BROWSER_CHALLENGE`（敏感） |
| `htmlParse`／`htmlParseFragment`／`dom*`／`parseHtml` | **不跨邊界**，沙箱內 C 解析器 | Host 不參與；節點數、深度、handle 數照 `PluginExecutionLimits` 在沙箱內計數 | 無 |
| `log`、`console.*` | `Log` frame | 有界 ring buffer；只進 diagnostic，不顯示成 Host UI | 無 |
| `getPreference`／`setPreference` | `storage.pref.*` | `BoundPluginStorage`（`SourceKey` 範圍）＋配額 | 預設 |
| `getCredentialUsername`／`hasCredential` | `cred.*` | 只回傳是否存在與 username | `CREDENTIAL_ACCESS`（敏感） |
| `getCredentialPassword`／`setCredential`／`clearCredential` | `cred.*` | 只在 Host 啟動的 login invocation 期間可用（§6.4） | `CREDENTIAL_ACCESS`（敏感） |
| `getCookie`／`getCookies` | `cookie.read` | 只限 `credentialOrigins` 的 cookie | `COOKIE_READ`（新增，敏感） |
| `setCookie`／`deleteCookie`／`clearCookies` | `cookie.write` | domain 必須落在 `credentialOrigins`；沿用 cookie parser 與 suffix 防護 | `COOKIE_STORAGE` |
| `requestLogin` | `event.submit` | 走既有 legacy login compatibility → `PluginSystemEventGateway` | `REQUEST_LOGIN_UI` |
| `requestHostEvent`／`bridge.system.*` | `event.submit` | 同一個 gateway；`BoundPluginScope` 由 Host 依 channel 注入，不從 frame 讀取 | 依事件的 Host permission |
| `getHostEventContext`／`getHostEventCapabilities` | `event.context`／`event.caps` | 與現行相同 | 無 |

- Host 端的 Cookie jar 會自動對 `credentialOrigins` 的請求附加 Cookie、保存 `Set-Cookie`；預設情況下
  套件**用得到** Session，但**讀不到** Cookie 值，因此無法把 Session 外洩到其他 origin。
- 每個 `BridgeCall` 都計入 `maxBridgeCallsPerInvocation`，並重新檢查 runtime generation：套件被撤銷或更新後，
  舊 channel 上的呼叫一律拒絕。
- 錯誤只回穩定錯誤碼（沿用 `safeBridgeError` 的精神），不回 Host exception message 或 stack trace。

### 5.2 DOM 解析放在哪裡

| 選項 | 優點 | 缺點 |
|---|---|---|
| Host 解析（jsoup／Ksoup）、handle 跨邊界 | jsoup 語意與現在 Rhino 一致；原生速度 | 每次 `domText`／`domAttr` 都是一次 IPC。4,000 章、每章 5 次呼叫 = 2 萬次 Binder 往返，粗估 0.2–1 秒（需實測）；Host 解析器直接面對套件刻意構造的輸入；DOM 記憶體算在 Host，不受套件記憶體上限約束 |
| 沙箱內 JS 解析器（現有 iOS bootstrap） | 已存在、無需新 C 依賴 | 在直譯 QuickJS（iOS 還要再乘 WASM 直譯）中解析 MB 級 HTML 很慢 |
| **沙箱內 C 解析器**（建議，例如 lexbor：HTML5 tokenizer + CSS selector，需 S0 確認授權與 API 適配） | 不跨邊界；解析器漏洞被關在沙箱；記憶體算在套件配額；四平台同一份實作 | 新 C 依賴；要用 golden fixture 對齊現行 jsoup 語意 |

決策：sandbox tier 採沙箱內 C 解析器。S1 階段可先以現有 iOS JS bootstrap 當暫時實作，讓 bridge 與 IPC 先上線，
再換成 C 解析器；兩者都必須通過既有 `DomReleaseCompatibilityFixture` 與官方套件語料的 parity 測試。

### 5.3 非同步 API（`bridge.net.fetch`，S1 一起提供）

已決策（D10）：sandbox tier 第一階段就同時提供同步相容層與非同步網路 API。

```javascript
const res = await bridge.net.fetch(url, { method: 'GET', headers: {}, body: null });
// res: { status, headers, text() / json() 由沙箱內解碼 }
const pages = await Promise.all(urls.map(u => bridge.net.fetch(u)));
```

- 只提供網路這一個非同步 op；儲存、Cookie、帳密、system event 仍是同步 API，避免一次擴大太多面。
- 語意：
  - hook 可以是 `async function`；Host 把回傳的 Promise 視為 invocation 結果，settle 後才回 `Result`。
  - QuickJS 的 job queue 由 `shinsou-sandbox-core` 的 event loop 驅動（`JS_ExecutePendingJob`），沒有 timer、
    沒有 `setTimeout`；沙箱只在等待 `AsyncBridgeResult` 時讓出執行緒。
  - 同步 `bridge.http*` 與 `bridge.net.fetch` 可混用；同步呼叫期間 pending 的非同步結果先排隊，不會重入。
  - invocation 結束、取消、逾時、套件撤銷或 runtime generation 改變時，Host 送 `AsyncCancel` 取消所有 pending op，
    其結果一律丟棄，不能洩漏到下一個 invocation。
  - 未 settle 的 Promise 不會讓 invocation 永遠掛著：invocation wall time（§7）照常計算。
- 限制：每 package 同時 pending 的 fetch 最多 6 個（超過的在沙箱內排隊，佇列上限 64，滿了直接 reject）；
  每 invocation 請求數與回應總量與同步 API 共用同一額度（§7）。
- 平台差異：Android／Desktop 透過 IPC 非同步 frame；iOS 的 WASM 以 host import 送出請求後立即返回，
  結果由 Host 在同一個沙箱執行緒排入，再進入 job loop。三種 transport 共用同一份 dispatcher 測試。
- 之後的 API 擴充（例如非同步儲存）另立版本協商，不在 S1 範圍。

## 6. 權限模型

### 6.1 沿用四層，加上 tier ceiling

```text
effective permission（sandbox tier）=
  package declaration（index ∩ sidecar ∩ script metadata，三方必須一致）
  ∩ Host supported capability
  ∩ SANDBOXED tier ceiling（Host 常數，套件與使用者都不能提高）
  ∩ 使用者對 exact artifact + 發佈者金鑰 的授權
  ∩ 平台／生命週期 policy
  ∩ 事件的 source capability 前置條件
```

- 現有公式（`PLUGIN_SYSTEM_EVENT_ARCHITECTURE.md`「權限與功能 capability 分離」）只多一個 tier ceiling 項。
- 本 ADR 要求 sandbox tier 把 `contentKinds`、`capabilities`、`runtimePermissions` 的三方一致性從「官方發佈檢查」
  提升為 Host admission gate（目前 generic Host 尚未全部強制，見 `PLUGIN_SYSTEM.md` 已知限制）。

### 6.2 權限分級

| 層級 | 項目 | 預設 | 說明 |
|---|---|---|---|
| Source capability | `CATALOGUE`、`LATEST`、`BROWSE`、`METADATA`、`UNITS`、`CONTENT`、`SEARCH`、`PREFERENCES` | 依宣告授予 | Host → 套件的呼叫，不涉及敏感資料 |
| Source capability | `LOGIN`、`FAVORITE` | 隨對應敏感權限 | 只在相關 runtime permission 核准後啟用 |
| Runtime permission | `EXECUTE_SCRIPT`（僅限沙箱）、`NETWORK`（僅限宣告的 exact `requestOrigins`）、Host-managed Session Cookie（僅限 `credentialOrigins`，套件讀不到值） | 安裝時一次確認 | 安裝畫面列出所有 origin |
| Runtime permission（敏感，逐項） | `CREDENTIAL_ACCESS`、`COOKIE_READ`（新增）、`COOKIE_STORAGE`（主動寫入）、`BROWSER_CHALLENGE`、`LOGIN_PROMPT`、`FAVORITE_MUTATION` | 預設關閉 | 每項各自開關，可隨時撤銷 |
| Host permission | `REPORT_DIAGNOSTIC`、`REQUEST_SOURCE_REFRESH` | 依宣告授予 | 低風險、已有 rate limit |
| Host permission（敏感，逐項） | `REQUEST_LOGIN_UI`、`REQUEST_LOGOUT`、`REQUEST_BROWSER_CHALLENGE` | 預設關閉 | 會讓 Host 顯示 UI |
| 網路範圍（敏感） | `contentHostSuffixes`（動態 CDN 後綴） | 預設關閉 | 已決策（D11）：第三方可用，列為敏感權限、逐項核准；只用於 content plane，不能用於 API request 或 credential；後綴至少要到註冊網域層級（例如 `cdn.example.com`、`example.com` 可以，`com`、`co.jp`、`github.io` 這類公開後綴不行）。現有 cookie 用的 suffix 清單不是完整 Public Suffix List，因此需要引入並定期更新完整 PSL 來判斷（§12 S6） |
| **Tier ceiling：永不授予第三方** | `InProcessScriptProvenance`、large-text profile、`Connection` header 移除、私有網路／LAN／loopback、developer unpinned browser session、`REPORT_USER_MESSAGE`、非 HTTPS origin、任意 header | — | 需要這些能力的套件只能走 reviewed tier |

### 6.3 授權記錄

- 沿用單一 durable V2 admission record（`PENDING`／`GRANTED`／`REVOKED`），artifact identity 擴充為：

```text
(tier, publisherKeyFingerprint, packageId, version, versionCode, sha256, [SourceKey], grantedPermissions, grantedOrigins)
```

- 同一 packageId 由不同發佈者金鑰發佈，是不同的 identity，授權不互通。
- 更新的授權延續（已決策 D4）：同一發佈者金鑰、權限與 origin 集合（含 `contentHostSuffixes`）是舊授權的子集
  → Host 自動建立新 artifact 的 `GRANTED` record 並通知使用者；任何新增權限或 origin → 新 record 為 `PENDING`，
  舊版本保留執行直到使用者決定。發佈者金鑰或套件被使用者本機封鎖時不自動延續（§8.4）。

### 6.4 帳密處理：一次性 login 沙箱

給第三方套件明文密碼是最大的外洩風險，因為套件可以把密碼編進任何允許 origin 的請求。緩解方式：

1. `getCredentialPassword` 只在 Host 啟動的 `login()` invocation 中可用。
2. login 在一個**一次性**的 `JSRuntime`（Android／Desktop 為獨立程序）中執行，網路只允許 `credentialOrigins`。
3. login 結束後銷毀該 runtime；它的輸出只有 Host 保存的 Session Cookie／token（存在 Host，套件之後只能「使用」，
   若無 `COOKIE_READ` 則讀不到）。
4. 一般 runtime 永遠拿不到密碼。

殘餘風險：如果 `credentialOrigins` 本身就是攻擊者控制的網站，密碼仍會送到那裡，因此授權 UI 必須明確顯示
「帳密會送往：<origin 清單>」。已決策（D7）：第三方可以使用 `CREDENTIAL_ACCESS`，但只限上述一次性 login 沙箱，
且必須逐項核准。

### 6.5 權限 UI

- **安裝確認頁**（Host 自繪，套件無法控制版面或文字樣式）：
  - 發佈者名稱（自稱，標示「未驗證」）＋金鑰指紋＋信任狀態（TOFU／指紋已比對／官方）。
  - 「會連線到」：列出 `requestOrigins`、`contentOrigins`，超過 5 個時摺疊但顯示總數。
  - 預設權限一行摘要；敏感權限逐項開關，預設關閉，每項附一句白話風險說明。
  - 執行環境標示「隔離執行」。
- **更新確認頁**：只顯示差異（新增的 origin／權限以醒目標示）。
- **來源設定 → 權限**：可隨時撤銷單項權限；撤銷後 runtime 以新 generation 重載。
- **封鎖名單**（設定 → 套件安全）：使用者自行維護，可封鎖單一套件（packageId + 發佈者）、單一 artifact
  （SHA-256）或整個發佈者金鑰；套件與發佈者頁面都有「封鎖」入口（§8.4）。
- **過期警示**：repository index／revocations 過期時，在套件清單與來源頁顯示持續警示（「已 N 天無法確認此套件是否被
  開發者撤回」），但不停用套件（§8.4）。
- **信任模式**：設定中選擇全域預設模式，新增 repository 時可對單一發佈者覆寫（§8.2）。
- **官方標章**只在發佈者金鑰等於 App 內建官方金鑰時出現；第三方套件名稱中含「官方」「Shinsou」等保留字時，
  Host 拒絕或加註警示。

## 7. 資源限制

所有數值都是 Host policy 初值，套件不能提高；S0／S1 以官方語料量測後再調整。沿用 `PluginExecutionLimits`
的欄位，sandbox tier 用一組獨立的 `SandboxExecutionLimits` 預設值。

| 資源 | 機制 | 初值（sandbox tier） |
|---|---|---|
| JS heap | `JS_SetMemoryLimit` | 64 MiB／package（低記憶體裝置 32 MiB） |
| JS stack | `JS_SetMaxStackSize` | 1 MiB |
| 程序／instance 記憶體 | Android ART heap、Windows Job Object、macOS RSS 監控、iOS WASM linear memory max | 128 MiB（含 DOM、解析器、緩衝） |
| 初始化時間 | interrupt handler 檢查 deadline | 10 秒（沿用） |
| invocation 時間 | interrupt handler + Host watchdog | 30 秒 wall；watchdog 在 deadline + 2 秒後強制 kill／terminate |
| CPU 時間 | 程序 CPU time（Android／Desktop）；iOS 以執行緒 CPU time | 15 秒／invocation |
| Regex | QuickJS regex 引擎的逾時檢查（需 S0 確認 quickjs-ng 的 `lre` 會呼叫 interrupt） | 同 invocation 預算 |
| Bridge 呼叫數 | 沙箱與 Host 雙重計數 | 4,096／invocation（沿用） |
| 網路請求數 | Host token bucket | 每 package 最多 6 個同時進行（同步與 `bridge.net.fetch` 合計）；10 req/s，burst 20；每 invocation 最多 256 個 |
| 非同步 pending op | 沙箱內佇列 + Host 計數 | 同時 in flight 6、沙箱內等待佇列 64，超過即 reject；invocation 結束時全部取消 |
| 單一回應 | 現有 bounded streaming | API plane 4 MiB；content plane 維持 Host-only 上限 |
| 每 invocation 回應總量 | Host 累計 | 32 MiB |
| Batch | 沿用 | 128 items、32 concurrent、每項 128 KiB、整批 4 MiB |
| 結果大小 | 沿用 generic `PluginExecutionLimits` | 4 MiB result、1 MiB 單字串；不提供 large-text profile |
| 偏好儲存 | `BoundPluginStorage` 配額 | 256 keys、單值 64 KiB、總計 1 MiB |
| Cookie | 沿用 cookie parser 上限 | 每 package 500 筆、1 MiB |
| 腳本大小 | package store | 2 MiB（reviewed 維持 8 MiB） |
| Log | 沿用 | 256 entries、256 KiB |
| 同時存活沙箱 | LRU | Android 4、Desktop 8、iOS 3 |
| Crash-loop | Host 計數 | 10 分鐘內 3 次崩潰或超限 → 自動停用並在 UI 提示 |

超限一律以 `PluginResourceLimitException` 類型的穩定錯誤失敗，不回傳部分結果。

## 8. 開發者發佈流程

### 8.1 流程

```text
開發者
  shinsou-plugin keygen          產生 Ed25519 金鑰（私鑰只留在開發者端）
  shinsou-plugin build           計算 SHA-256／byte size，產生 sidecar，檢查三方宣告一致
  shinsou-plugin lint            靜態檢查：用到的 bridge op 是否都有宣告權限；origin 是否 exact HTTPS
  shinsou-plugin test            用錄製的 HTTP fixture 在本機 QuickJS 沙箱跑 hook
  shinsou-plugin sign            對 repo-meta／index／sidecar／revocations 產生 signed envelope，sequence 遞增
  發佈到任何靜態主機（GitHub Pages、raw、CDN）

使用者
  新增 repository URL（或點開發者提供的 shinsou://add-repository?url=…&fp=… 連結）
  → Host 取得 repo.json envelope，顯示發佈者名稱（未驗證）與指紋
  → 依使用者選擇的信任模式：TOFU 直接確認，或必須輸入／比對指紋（§8.2）
  → 使用者選擇「信任此開發者金鑰」
  → 瀏覽、安裝套件；每個套件仍各自走權限確認
```

- CLI 以官方 plugin repository 現有的 `test/v2-migration-smoke.js` 與 `shinsou-extension-creator` skill 為基礎擴充。
- 開發者模式：App 設定中可開啟「套件開發者模式」，允許從 loopback／指定 LAN 位址載入**未簽章**套件，
  但只能在 sandbox tier 執行、明顯標示「開發中」、不能取得敏感權限以外的任何放寬。它取代現在只服務
  reviewed 套件的 `ReviewedLocalRepositoryPolicy` 開發入口（後者保留給官方套件）。

### 8.2 信任發佈者金鑰

已決策（D3）：兩種模式都提供，由使用者選擇。

| 模式 | 流程 | 使用方式 |
|---|---|---|
| (a) TOFU＋可選比對（預設暫定） | 首次加入 repository 時 pin 金鑰並顯示指紋；若 deep link 帶 `fp` 或使用者手動輸入指紋，則比對，不符即拒絕；比對成功時信任狀態顯示「指紋已比對」，否則顯示「首次使用信任」 | 一般使用者 |
| (b) 一律要求指紋比對 | 沒有提供指紋或指紋不符就不能信任該發佈者；只能透過帶 `fp` 的 deep link 或手動輸入完整指紋 | 重視安全、願意從開發者網站取得指紋的使用者 |

- 模式在「設定 → 套件安全」設定全域預設；新增 repository 時可對該發佈者改用較嚴格的 (b)（不能在全域 (b) 時降為 (a)）。
- 從 (a) 切換到 (b) 時，已用 TOFU 信任、尚未比對指紋的發佈者標為「待比對」：已安裝套件繼續執行，但在完成比對前
  不安裝新套件、不自動沿用更新授權（D4）。
- 兩種模式共用同一個 `RepositoryTrustRootStore`；差別只在 pin 之前是否強制比對，以及信任記錄中的 `verification`
  欄位（`TOFU`／`FINGERPRINT_MATCHED`／`OFFICIAL_BUILT_IN`）。

- 指紋：SHA-256(public key) 取前 128 bit，以 4 個字元一組顯示；比對用完整值。
- 信任單位改為發佈者金鑰：同一把金鑰可服務多個 URL（mirror），不同 URL 不會重新 TOFU；換 URL 不需重新信任。
  反過來，同一 URL 換了金鑰 → 沿用 `KeySubstitution` 拒絕，只能走明確替換流程。

### 8.3 Key rotation

- 新增 signed document type `publisher-key-rotation`：舊金鑰簽署 `{newPublicKey, effectiveSequence}`，新金鑰再對同一內容
  簽署（雙簽）。Host 驗證成功後自動更新 pin，並保留舊金鑰為 `retired`（只能驗證、不能再簽新文件）。
- 遺失私鑰：無法自動恢復。開發者只能發佈新金鑰，使用者經 `replaceAfterExplicitApproval` 明確替換；替換後該發佈者所有
  套件的授權轉為 `PENDING`。

### 8.4 撤銷與更新

| 機制 | 文件 | Host 行為 |
|---|---|---|
| 版本撤回（yank） | 發佈者簽署 `revocations`，列出 artifact SHA-256 | 對應已安裝 artifact 立即卸載 runtime、轉為 inert，提示使用者更新 |
| 金鑰自我撤銷 | 該金鑰簽署 `key-revoked`（任何持有私鑰者都能撤銷，攻擊者撤銷無利可圖） | 該發佈者全部套件 inert，需重新信任新金鑰 |
| 使用者本機封鎖名單（D5） | 不簽章、不下載、只存在本機；使用者自行加入套件（packageId + 發佈者）、artifact SHA-256 或發佈者金鑰 | 命中即卸載 runtime 並 inert；封鎖發佈者時，該金鑰簽署的所有 repository 不再提供新安裝；可由使用者解除封鎖 |
| 防凍結攻擊 | index 與 revocations 加 `expiresAt`（例如 30 天） | 過期後不從該 repository 安裝新套件或新版本；**已安裝套件繼續執行並持續顯示警示，不論過期多久都不停用**（D8） |
| 防降級 | 沿用 `requireArtifactNotDowngraded` | 版本號或 digest 回退即拒絕 |

已決策（D5）：**不設中央 denylist**。Shinsou 不簽署、不發佈、不維護任何遠端封鎖清單，也不提供檢舉或下架流程；
App 不會因為作者或任何第三方的判斷而遠端停用使用者安裝的套件。唯一的遠端停用來源是發佈者自己簽署的
`revocations`／`key-revoked`。

- 本機封鎖名單是裝置本機設定，存在 Host 的 KV store，不進 portable backup／sync（與 execution grant 同類，
  避免另一裝置的資料自動改變本機執行狀態）；是否提供手動匯出／匯入列為後續 UI 細節。
- 封鎖判定在 `ExecutionTierResolver`、安裝、更新、啟動 rehydrate 時都要檢查，優先於任何授權記錄。

### 8.5 沿用 `RepositoryTrust.kt` 的部分

| 元件 | 處理 |
|---|---|
| `shinsou-signed-envelope-v1` 格式、`SIGNATURE_DOMAIN`、canonical JSON、libsodium Ed25519 | 原樣沿用 |
| `RepositorySignedDocumentType` | 擴充 `PUBLISHER_KEY_ROTATION`、`REVOCATIONS`、`KEY_REVOKED`（不新增中央 denylist 類型） |
| `RepositoryTrustRootStore`（首次 pin 不可變、明確替換） | 沿用語意；key 由 normalized base URL 改為 publisher ID，另存 URL → publisher 對應 |
| `RepositorySecurityStateStore`（replay／equivocation／downgrade watermark） | 沿用；watermark key 改為 `(publisherKey, type, documentId)`，讓 mirror 不能重播舊文件 |
| `RepositoryTrustDecision.UnsignedDeveloperCompatibility` | 只在開發者模式使用 |
| `isCanonicalOfficialUnsignedRepository` | 官方 repository 完成簽章後移除 |
| 新增 | `expiresAt` 驗證（過期只產生警示狀態，不影響已安裝套件執行）、rotation 雙簽驗證、revocation 套用、指紋顯示格式、信任模式與 `verification` 欄位、本機封鎖名單 store（不屬於簽章文件） |

## 9. 威脅模型

### 9.1 攻擊者假設

- 攻擊者可以發佈任意套件、控制其宣告的 origin、刻意構造 JS 與 HTML 觸發引擎漏洞、引誘使用者安裝。
- 攻擊者可能取得某開發者的 GitHub 帳號或靜態主機（但不一定有私鑰），或取得私鑰本身。
- Host 程式碼、OS 與 App 簽章視為可信。

### 9.2 威脅、緩解與殘餘風險

| 威脅 | 緩解 | 殘餘風險 |
|---|---|---|
| **外洩 App／其他套件資料** | 沙箱沒有檔案與網路；資料只經 bridge 取得，且只限該 `SourceKey` 的儲存；Cookie 值預設不可讀；密碼只在一次性 login 沙箱 | 套件看得到的資料（搜尋關鍵字、瀏覽作品、自己的偏好）可送到它宣告的 origin；這是使用該來源的固有代價，只能靠 UI 揭露 origin |
| **經 content plane 外洩** | 套件回傳的圖片 URL 必須命中 exact `contentOrigins`；只允許 GET／HEAD，不帶 Cookie | 可把資料編進宣告內 content origin 的 query string；同上，靠 origin 揭露 |
| **DoS（CPU／記憶體／遞迴／regex）** | §7 的 QuickJS 限制 + 程序／WASM 硬上限 + Host watchdog + crash-loop 停用 | 在限制內反覆消耗電量；以背景預算與 crash-loop 降低 |
| **網路濫用（掃內網、打第三方）** | 沿用 exact origin、DNS pinning、私有位址封鎖、rate limit | 可對自己宣告的公開 origin 發出限額內的請求 |
| **引擎漏洞逃逸** | Android isolated process；Desktop OS sandbox；iOS WASM 邊界；QuickJS 與解析器持續 fuzz、固定版本 | 要真正危害 App 需「引擎漏洞 + OS sandbox 或 WASM runtime 漏洞」串連；minSdk 29 後 Android 所有裝置都是每套件一程序，沒有共用程序的弱化情況；iOS 使用者若自行開啟原生 QuickJS 效能模式（D1，視 S0 是否提供），該套件的引擎漏洞 = App 被控制 |
| **IPC／frame 攻擊** | 長度上限、strict CBOR、schema 驗證、fuzz；身分來自 channel 而非 payload | Host codec 漏洞；以 fuzz 與 bounded decode 降低 |
| **Host 解析套件輸出** | bounded JSON／結果驗證；URL 只接受 https；文字當純文字 | Host 圖片解碼器（AVIF 等）會處理攻擊者可控的圖片 bytes，這與現在處理遠端網站圖片相同，但攻擊者可精準選擇；列為後續加固項目 |
| **偽造官方套件** | 官方標章只綁 App 內建金鑰；reviewed catalog 的 packageId 為保留命名空間；名稱保留字檢查；授權 identity 含發佈者金鑰 | 使用者仍可能被說服安裝名稱相似的第三方套件；UI 必須讓「發佈者」比「名稱」更醒目 |
| **偽造 Host UI／誘導輸入密碼** | 沿用 event 架構：Host 自繪 UI、套件文字當純文字、可信 source label；登入表單由 Host 顯示 | browser challenge 會顯示網站自己的頁面，網站本身可能是釣魚頁；只能靠 origin 顯示與敏感權限 |
| **供應鏈：repository 主機被入侵** | 所有文件需發佈者簽章；主機被入侵但私鑰沒外洩 → 無法發佈新內容；replay／equivocation／expiry 防重播舊文件 | 攻擊者可讓 repository 無法使用（DoS） |
| **供應鏈：開發者私鑰外洩** | 權限子集才自動更新（D4）；新增權限需使用者確認；發佈者 `key-revoked`；使用者本機封鎖名單；更新差異 UI | 攻擊者可在原有權限內推送惡意更新（例如把已授權的帳密送往原有 credential origin 的別的路徑），且因 D4 會自動沿用授權；沒有中央 denylist，只有原發佈者撤銷或使用者自己封鎖才能停用；這是開放生態固有風險 |
| **發現惡意套件後無法遠端保護**（D5） | 發佈者自己的 `revocations`／`key-revoked`；使用者本機封鎖名單；權限最小化讓惡意套件能做的事有限 | 若發佈者本身就是攻擊者，或私鑰已被奪走而原發佈者不撤銷，**Shinsou 沒有任何手段從遠端停用已安裝的惡意套件**；每位使用者必須自行得知消息並手動封鎖。風險只能靠社群公告與 UI 中易找的封鎖入口降低 |
| **被撤銷的套件長期殘留**（D5 + D8） | 過期警示持續顯示；過期後不從該 repository 安裝新套件或新版本 | 裝置若長期無法取得新的 `revocations`（離線、repository 被移除、攻擊者讓 repository 無法存取或持續回應舊的未過期文件直到過期），已安裝的套件會一直執行，**即使發佈者早已撤回該版本**。因為沒有中央 denylist 也不會因過期停用，被撤銷的套件可能無限期留在未更新的裝置上，使用者只能從警示自行判斷是否封鎖或卸載 |
| **供應鏈：套件打包的 npm 依賴** | 套件以單一 bundle 發佈並以 digest 綁定；CLI lint 提醒 | 依賴本身的惡意程式碼仍在沙箱與權限範圍內執行 |
| **供應鏈：Shinsou 自身的 C 依賴**（QuickJS、解析器、WASM runtime） | vendored 固定版本、SBOM、可重現建置、CI fuzz、上游安全公告追蹤 | 0-day；以多層隔離降低影響 |
| **跨套件干擾** | 每 package 獨立 runtime／程序／instance、獨立儲存與 Cookie jar、獨立 channel；Android minSdk 29 保證每套件一程序 | iOS 的多個 WASM instance 仍在同一個 App 程序內，需要 WASM runtime 漏洞才能互相影響 |
| **非同步 API 濫用**（D10） | pending op 上限、與同步 API 共用請求額度、invocation 結束即取消、generation 檢查 | 套件可在額度內並行請求，對其宣告的 origin 造成比同步 API 更集中的流量 |
| **時間差探測** | 只提供單調時鐘、不提供高精度計時器與 SharedArrayBuffer | 可忽略 |

## 10. 相容性與遷移路徑

### 10.1 Tier 判定（`ExecutionTierResolver`）

```text
if 發佈者金鑰 == App 內建官方金鑰 && OfficialShinsouReviewedCatalog（或 reviewed ShuYue catalog）exact match:
    REVIEWED_IN_PROCESS（現有 Rhino／JSC 路徑，InProcessScriptProvenance 照舊）
else if 簽章有效 && 發佈者已信任 && 不在使用者本機封鎖名單 && 未被發佈者撤銷 && 使用者已授權:
    SANDBOXED
else if 開發者模式 && 來源為允許的 loopback／LAN:
    SANDBOXED（開發中標示，未簽章）
else:
    inert（現狀）
```

- 判定只用 Host 本機狀態，不讀 repository 自述的 tier。本機封鎖名單在所有分支之前檢查，連官方套件也可被使用者封鎖。
- 官方套件不在 catalog 中（例如作者推了新版但 App 還沒更新）→ 落到 SANDBOXED，用 sandbox tier 的權限上限執行。
  這讓官方套件更新也不再需要 App 發版，除非它需要 reviewed profile。
- 已決策（D9）：官方套件也搬進 sandbox tier，由作者的發佈者金鑰簽署（該金鑰同時內建於 App，作為「官方」標章的依據）；
  reviewed catalog 只保留真正需要 reviewed profile 的例外，並在 S8 逐一列出與縮減。

### 10.2 既有套件

| 類型 | 遷移後 |
|---|---|
| 官方 reviewed 套件（catalog 內） | 不變，仍為 `REVIEWED_IN_PROCESS` |
| 官方套件的新版本（catalog 外） | `SANDBOXED`；不需要 reviewed profile 的套件可正常運作 |
| 需要 reviewed profile 的官方套件（ShuYue large-text、`Connection` 移除、動態 CDN suffix） | 留在 reviewed tier；長期評估把這些能力做成 sandbox tier 可用的有限版本 |
| 現有 generic Shinsou 套件（今天 inert） | 若重新以發佈者金鑰簽署並經使用者授權 → `SANDBOXED`；未簽章 → 維持 inert |
| Mihon／Tachiyomi metadata | 不變（metadata-only） |

### 10.3 資料延續

- `SourceKey` 不因 tier 改變而改變，偏好、Cookie、書庫 binding、閱讀進度沿用（儲存都在 Host，不需搬資料）。
- tier 改變會建立新的 admission record（identity 含 tier），需要重新授權；Host 應在 UI 說明原因。
- packageId 衝突（已決策 D6）：`SourceKey` 升 `contractVersion = 3`，加入 `publisherId`（發佈者金鑰指紋），
  不同發佈者的同名 packageId 是不同來源。必須遵守 ADR-0001 的 identity 規則：
  - `SourceKey` 仍是 opaque、版本化、可跨裝置穩定的 key，不從 URL、顯示名稱或 hash 推導；`publisherId` 來自
    Host 驗證過的金鑰，不讀 repository 自述欄位。
  - 官方套件以內建官方金鑰對應既有 `contractVersion = 2` 的 key（v2 → v3 無損映射），既有書庫 binding、閱讀進度、
    下載與偏好不需要使用者動作。
  - 書庫 schema 遷移：shared SQLite 的來源 binding、`RemoteEntityKey`、下載 manifest、AppSnapshot／backup 與 sync
    payload 都要能保存 v3 key；舊版 App 讀到 v3 key 時必須保留原值而不是丟棄（沿用 ADR-0001 的 compatibility
    projection 原則）。此遷移排入 §12 S6，並需在 ADR-0001 的 identity contract 補上 v3 定義。
  - 金鑰 rotation（§8.3）不改變 `publisherId`：`publisherId` 綁定發佈者的第一把金鑰，rotation 鏈上的後續金鑰沿用
    同一個 `publisherId`；金鑰遺失後以新金鑰重新信任，視為新的發佈者，需要使用者手動遷移書庫 binding（後續 UI 工作）。

### 10.4 腳本相容性

- 沙箱內 `bridge.*` 同步 API 與 Rhino／JSC 相同；Rhino 的 Java interop 不提供（現有官方腳本應未使用，S1 以語料確認）。
- 每個官方套件在 S1 都以「錄製的 HTTP fixture」分別跑 Rhino（或 JSC）與 sandbox，比對所有 hook 輸出，作為相容 gate。

## 11. 平台政策與發佈管道

已決策（D12）：App 只透過 GitHub Release 自行發佈，不上架 App Store、Google Play 或任何桌面商店。因此：

- **不受商店審核政策限制**：App Store Review Guideline 2.5.2／4.7、Google Play 的 Device and Network Abuse 等關於
  下載可執行程式碼的規定不適用，開放第三方套件不需要配合商店的檢舉、下架或內容審核要求。
- **仍受平台本身的限制**（與商店無關、必須處理）：
  - Android：使用者需允許安裝未知來源 App；App 更新由 App 自己或使用者從 GitHub Release 取得。minSdk 提高到 29
    （D2）後，Android 8～9 使用者會停在最後一個支援 API 26 的 Release。
  - iOS：不經 App Store 時只能以側載（開發者憑證、AltStore 類工具等）安裝，簽章會定期過期；側載 App 一般同樣不能使用
    JIT，因此 iOS 仍採 WASM 直譯（D1），不因發佈管道而改變。
  - macOS：在 App Store 外發佈仍需 Developer ID 簽章與公證，才能通過 Gatekeeper；`shinsou-sandbox` helper 要用同一個
    Team ID 簽章並一起公證。
  - Windows：未簽章或新簽章的執行檔會觸發 SmartScreen 警告；helper 與主程式應使用同一張程式碼簽章憑證。
- **App 本身的供應鏈**：使用者從 GitHub Release 下載 App，Release 資產的完整性只靠平台簽章與 GitHub 帳號安全；
  建議 Release 一併提供 SHA-256 與簽章說明（屬發佈流程，不在本 ADR 範圍）。
- 第三方來源的內容合法性不由技術邊界保證；D5 已決定不設中央 denylist 或檢舉流程，因此 App 不承擔遠端下架角色，
  只提供使用者本機封鎖。

## 12. 分階段實作計畫

| 階段 | 範圍 | 驗收標準 |
|---|---|---|
| **S0：Spike 與量測** | (1) quickjs-ng 嵌入 Android JNI、macOS／Windows helper、iOS WASM（WAMR 等）；(2) C HTML 解析器候選評估；(3) Android `bindIsolatedService` + pipe／`SharedMemory` 大 payload；(4) macOS helper sandbox、Windows AppContainer + Job；(5) 以 3–5 個官方套件量測冷啟動、單次 hook 延遲、記憶體；(6) 驗證 QuickJS job loop 與 WASM 直譯器能支援非同步 host 回呼 | 每平台有可執行原型；量測報告含與現況比較；**依 iOS WASM 量測結果決定是否提供原生 QuickJS 效能模式（D1）**；任何無法達成的隔離要求寫回本 ADR |
| **S1：sandbox-core、協定與非同步 API** | `shinsou-sandbox-core`（QuickJS + 沙箱內 DOM + bridge shim + job loop）；commonMain 的 `SandboxProtocol v1` codec（含 `AsyncBridgeCall`／`AsyncBridgeResult`／`AsyncCancel`）、`SandboxBridgeDispatcher`、`SandboxRuntimeFactory`；同步相容層與 **`bridge.net.fetch` 非同步 API（D10）**；`async` hook 支援；JVM 測試用的 in-process transport（僅測試） | 官方語料以同步 API 的 parity 全通過；另以至少 2 個官方套件改寫為 `bridge.net.fetch` 版本，輸出與同步版一致；codec fuzz 24 小時無 crash；惡意語料（§13，含非同步項目）全數以穩定錯誤失敗；invocation 結束、取消、逾時、撤銷後沒有殘留 pending op 或跨 invocation 結果；`SandboxRuntimeFactory` 拒絕 provenance |
| **S2：Android isolated process 與 minSdk 29** | (1) **把 composeApp 的 Android minSdk 由 26 提高到 29**（beta.8 為 `gradle/libs.versions.toml` 的 `android-minSdk`），清除只為 API 26–28 存在的相容分支；(2) 在前一個 Release 的 release notes 預告：Android 8～9 使用者之後無法再更新 App，最後支援版本為何；(3) `SandboxService`、`bindIsolatedService`、AIDL、pipe／`SharedMemory` 串流、非同步 frame、生命週期與 LRU、crash-loop | minSdk 29 的建置與 Android 單元／儀器測試全數通過；release notes 已發佈預告；惡意語料在實機通過；沙箱內 `socket()`／讀 App 檔案皆失敗；每個 package 確認在不同 isolated process（不同 pid／uid）；kill 沙箱不影響 Host |
| **S3：Desktop helper** | macOS 先、Windows 後；簽章與公證流程 | 同 S2；helper 無法連網、讀使用者檔案、開子程序；Host 結束不留孤兒程序 |
| **S4：iOS WASM** | wasm32 編譯、WASM runtime 整合、非同步 host 回呼、terminate watchdog；若 S0 決定提供，另做原生 QuickJS 效能模式與其警示 UI | 同 S2 的功能面；WASM 效能達 S0 設定的門檻；效能模式（若提供）預設關閉且不能被套件或 repository 開啟 |
| **S5：發佈者信任** | publisher-keyed trust store、兩種信任模式（D3）與 `verification` 欄位、rotation、revocations、expiry（只警示，D8）、指紋 UI 與 deep link、**使用者本機封鎖名單（D5）** | `RepositoryTrustTest` 擴充：rotation 雙簽、mirror 重播、過期只警示不停用、撤銷、金鑰替換；模式 (b) 下無指紋必定拒絕；(a)→(b) 切換後 TOFU 發佈者轉「待比對」；封鎖套件／artifact／發佈者後立即 inert 且重啟後仍有效 |
| **S6：權限、授權與 identity** | tier ceiling、`COOKIE_READ`、一次性 login 沙箱（D7）、admission record 擴充、更新授權延續（D4）、`contentHostSuffixes` 敏感權限與完整 PSL（D11）、**`SourceKey` v3 + `publisherId` 與書庫 schema 遷移（D6，需同步修訂 ADR-0001）**、權限 UI | 權限矩陣測試：每個 bridge op × 每個權限有無；撤銷即時生效；公開後綴被拒；v2 → v3 遷移後既有官方來源的書庫 binding、進度、下載、偏好完整保留，舊版 App 讀到 v3 key 不丟資料；UI 文案經審閱 |
| **S7：開發者工具與 Beta** | `shinsou-plugin` CLI（含非同步 API 範本與 lint）、開發者模式、作者文件；以 feature flag 在 GitHub Release beta 開放 | 外部開發者能從零完成發佈；beta 期間收集崩潰與限制觸發統計 |
| **S8：官方套件遷移（D9）** | 官方套件以作者的發佈者金鑰簽署並改走 sandbox tier；reviewed catalog 只留真正需要 reviewed profile 的例外 | 官方套件更新不再需要 App 發版（例外清單除外）；例外清單逐項列出所需的 reviewed 能力 |

S1 工程量評估（粗估，S0 後重估）：只做同步相容層約 6–8 人週（sandbox-core、codec、dispatcher、parity）。加入
`bridge.net.fetch` 後需要多做 job loop、非同步 frame 與取消語意、三種 transport 的非同步路徑、`async` hook 結果處理，
以及對應的惡意語料與 parity 測試，約再增加 3–4 人週，S1 合計約 9–12 人週。非同步 frame 也會讓 S2–S4 各平台
transport 的工作量各增加約 0.5–1 人週。

S1 前不改 production 行為；S2–S4 可獨立並行；S5、S6 是開放給一般使用者前的必要條件。

## 13. 測試策略

- **惡意套件語料**（每平台都跑）：無窮迴圈、深遞迴、巨大字串／陣列、記憶體緩慢洩漏、catastrophic backtracking regex、
  `Proxy`／getter 炸彈、竄改 `bridge` 原型、偽造身分欄位、對私有 IP／loopback／非 HTTPS 發請求、header injection、
  嘗試讀 Cookie／密碼、超大結果、bridge 呼叫洪水、事件洪水、刻意崩潰。
- **非同步 API**：大量 `bridge.net.fetch` 洪水（超過 pending 上限必須 reject）、永不 settle 的 Promise、hook 回傳後
  仍 pending 的 op、取消或撤銷途中送回的結果、同步與非同步呼叫交錯、在 rejection handler 中再發請求、跨 invocation
  取用舊結果。
- **隔離探測**：在沙箱內以 C 測試程式（僅測試建置）嘗試開檔、開 socket、`fork`／`CreateProcess`、讀環境變數，全數必須失敗。
- **Fuzz**：SandboxProtocol codec（Host 端）、QuickJS + 解析器（沙箱端，CI 夜間 libFuzzer）、bounded JSON decode。
- **Parity**：官方套件 × 錄製 HTTP fixture，比對 reviewed 與 sandbox 輸出；`DomReleaseCompatibilityFixture` 擴充到 C 解析器。
- **信任與生命週期**：兩種信任模式、TOFU、指紋不符、模式切換、金鑰替換、rotation、revocation、本機封鎖名單
  （套件／artifact／發佈者，含重啟後持續）、expiry（過期任意天數後仍執行並顯示警示）、downgrade、mirror 重播、
  更新權限擴大／縮小、撤銷中途的 pending bridge call。
- **Identity 遷移**：`SourceKey` v2 → v3 的書庫、下載、備份與同步 round-trip；同名 packageId 不同發佈者不互相覆蓋。
- **跨套件隔離**：兩個套件同時執行，互相嘗試讀對方偏好、Cookie、DOM handle。
- **效能與耐久**：冷啟動、hook 延遲、記憶體高點；連續 12 小時瀏覽／書庫更新 soak，觀察 RSS 與程序數。
- **平台矩陣**：Android（API 29、最新）、iOS 實機（側載安裝）、macOS arm64／x64（Developer ID 簽章＋公證後）、Windows x64（簽章後）。

## 14. 已決策的取捨與仍開放的問題

D1–D12 已由使用者於 2026-10-25 決策。「原建議」保留規劃時的建議以供追溯；★ 表示決策與原建議不同。

| # | 問題 | 選項 | 原建議 | 決策 | 理由與影響 |
|---|---|---|---|---|---|
| D1 | iOS 第三方套件的執行方式 | (a) QuickJS-in-WASM；(b) 原生 QuickJS in-process；(c) (a) 為預設、(b) 為使用者可選的效能模式；(d) iOS 先不開放第三方 | (c) | **(c)** | 預設保有記憶體安全隔離；效能模式是否真的提供，依 S0 iOS 量測結果決定（§4.5、§12 S0／S4） |
| D2 ★ | Android API 26–28 | (a) 共用一個 isolated process；(b) 這些版本停用第三方套件；**(c) minSdk 提高到 29** | (a) | **(c)** | 所有裝置都用每套件一程序的完整隔離，移除共用程序設計；代價是 Android 8～9 使用者之後無法更新 App（§4.3、§11、§12 S2） |
| D3 ★ | 發佈者金鑰信任方式 | (a) TOFU＋顯示指紋＋可選比對；(b) 一律要求指紋比對 | (a) | **兩者都提供，由使用者選擇；預設暫定 (a)** | 兼顧低門檻與高安全需求；需要模式設定、單一發佈者覆寫與模式切換處理（§8.2、§6.5、§12 S5） |
| D4 | 更新時授權延續 | (a) 同金鑰且權限／origin 為子集時自動延續；(b) 每次 digest 變更都重新核准 | (a) | **(a)** | 第三方更新頻繁，避免授權疲勞；私鑰外洩時的殘餘風險見 §9.2（§6.3） |
| D5 ★ | 封鎖名單 | (a) Shinsou 金鑰簽署的中央 denylist；(b) 完全交給使用者；**(c) 每位使用者自行維護的本機封鎖名單** | (a) | **(c)** | 不設中央 denylist，Shinsou 不承擔遠端下架與檢舉；使用者可封鎖套件、artifact 或發佈者金鑰；發現惡意套件時無法遠端保護已安裝的使用者（§8.4、§9.2） |
| D6 | packageId 命名衝突 | (a) `SourceKey` v3 加 `publisherId`；(b) 不改 `SourceKey`，Host 拒絕衝突 | (a) | **(a)** | 避免 packageId 先搶先贏；需書庫 schema 遷移並修訂 ADR-0001 identity contract（§10.3、§12 S6） |
| D7 | 第三方能否取得帳密 | (a) 可以，僅限一次性 login 沙箱＋逐項核准；(b) 一律不能 | (a) | **(a)** | 保留帳密登入型來源的可行性，密碼不進入一般 runtime（§6.4） |
| D8 ★ | index／revocations 過期後 | (a) 繼續執行並警示；(b) 停止執行；原建議為 (a) 但 90 天後改 (b) | (a)＋90 天後停用 | **(a)，不論過期多久都不停用** | 避免離線或 repository 消失時套件突然不能用；與 D5 合併後，被撤銷的套件可能長期留在未更新的裝置上（§8.4、§9.2） |
| D9 | 官方套件是否移往 sandbox tier | (a) 是，reviewed catalog 只留例外；(b) 維持全部 reviewed | (a) | **(a)** | 官方套件由作者的發佈者金鑰簽署，更新免發 App（§10.1、§12 S8） |
| D10 ★ | 第一版是否提供非同步 `bridge.net.fetch` | (a) S1 一起做；(b) 先只做同步相容層 | (b) | **(a)** | 套件從一開始就能並行請求；S1 工程量由約 6–8 人週增至約 9–12 人週，並增加取消語意與測試面（§5.3、§12 S1） |
| D11 | 第三方能否使用動態 CDN 後綴 | (a) 可，作為敏感權限；(b) 只能 exact origin | (a) | **(a)** | 後綴至少到註冊網域層級，需引入完整 Public Suffix List（§6.2、§12 S6） |
| D12 ★ | 發佈管道 | App Store／Google Play／桌面商店上架，或自行發佈 | 需使用者提供資訊 | **只透過 GitHub Release 自行發佈** | 不受商店審核政策限制；仍需處理側載、Developer ID 公證、Windows 程式碼簽章（§11） |

仍未決的開放問題：

1. C HTML 解析器的最終選擇（lexbor 或其他）與 jsoup 語意差異的容忍範圍。
2. WASM runtime 的最終選擇與其長期維護狀態。
3. macOS 主 App 是否已啟用 App Sandbox；這影響 helper 的 sandbox 套用方式。
4. 低記憶體 Android 裝置上同時存活沙箱數的下限。
5. 是否需要套件「目錄網站」或搜尋服務（本 ADR 只處理使用者手動新增 repository）。
6. 套件圖示等 repository 提供的圖片是否也要經 sandbox 或限制格式，以降低 Host 圖片解碼器的攻擊面。

## 15. 後果

正面結果：

- 任何人都能開發、簽署、發佈套件；使用者自行選擇信任與安裝，作者不再是唯一瓶頸。
- 第三方套件的影響範圍被限制在：它宣告並經使用者核准的 origin、它自己的儲存、它被核准的敏感權限。
- 四平台只剩一個第三方執行引擎，消除 Rhino／JSC 行為差異。
- 官方套件也能在 sandbox tier 更新，大幅減少 App 發版次數。

成本與風險：

- 新增 C 依賴（QuickJS、HTML 解析器、WASM runtime）及其 fuzz、更新、SBOM 維護。
- Desktop 要額外編譯、簽章、公證 helper；Android 要維護 isolated service 與 Binder／pipe 協定。
- iOS 的 WASM 直譯會讓第三方套件明顯變慢（幅度待 S0 量測）。
- 每個沙箱程序佔記憶體；需要 LRU 與閒置回收，冷啟動會增加首次呼叫延遲。
- 開放生態後，「使用者被說服授權惡意套件」是無法完全以技術消除的風險，必須靠 UI 揭露、發佈者撤銷與使用者本機封鎖名單降低。
- 不設中央 denylist（D5）且過期不停用（D8）：發現惡意套件時 Shinsou 無法遠端保護已安裝的使用者，被撤銷的套件可能
  無限期留在未更新的裝置上。
- minSdk 提高到 29（D2）：Android 8～9 使用者之後無法再更新 App。
- 非同步 API 在 S1 一起做（D10）：S1 工程量約增加 50%，並多一組取消與並行語意需要長期維護。
- 兩種信任模式（D3）、`SourceKey` v3 書庫遷移（D6）、完整 PSL（D11）都是新增的實作與測試面。
- 只透過 GitHub Release 發佈（D12）：不受商店審核限制，但 iOS 只能側載、macOS／Windows 需自行處理簽章與公證。

## 不接受的縮小範圍

- 讓第三方套件在 Rhino 或 JavaScriptCore in-process 執行，只加 instruction 計數或事後記憶體輪詢。
- 以 repository 簽章、使用者核准或 digest 相符代替隔離執行。
- 在 iOS 以原生 QuickJS in-process 當預設，卻宣稱與 Android／Desktop 同等隔離。
- 讓套件透過 payload 自報身分、tier 或資源限制。
- 為了方便把 Host 的 Cookie、密碼或其他套件的資料以「唯讀」方式暴露給第三方套件。
- 以線上簽章的審核清單代替開放發佈（已否決）。
- 為了保留 Android API 26–28 支援，讓多個第三方套件共用同一個 isolated process（D2 已否決）。
