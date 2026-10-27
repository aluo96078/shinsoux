# ADR-0002：開放第三方套件生態的隔離執行環境與最小權限模型

- 狀態：Accepted（規劃階段，尚未實作；第 14 節 D1–D12 已由使用者決策；S0 技術驗證已完成可在 Mac 上進行的部分，
  結果與據此的修訂見 §16，開放問題 1–3 已由 S0 回答）
- 日期：2026-10-25；決策回寫：2026-10-25；S0 結果回寫：2026-10-26
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
- （S2 修訂，§18.1）IPC 改為 AIDL 只負責啟動，協定 frame 走一組 pipe；下列兩項為原規劃。
- IPC：AIDL `ISandbox`（Host → 沙箱）與 `IHostBridge`（沙箱 → Host）。Host 在 bind 時傳入每個 package
  專屬的 `IHostBridge` binder，該 binder 物件本身就是身分 capability；沙箱無法取得其他 package 的 binder。
- Binder 單次交易約 1 MB 上限，現有 bridge 結果上限是 4 MiB：大 payload 改用
  `ParcelFileDescriptor.createPipe()` 串流 frame；minSdk 29 後也可直接使用 `SharedMemory`（API 27+），
  由 S0 比較兩者的延遲與實作複雜度後擇一。
- Host 端用 `linkToDeath` 偵測沙箱崩潰；崩潰只讓該 package 的 runtime 失效，並計入 crash-loop 計數（§7）。
- 沙箱內是 ART + JNI 載入 `libshinsou_sandbox_core.so`；ART heap 另有程序級上限，作為 QuickJS 限制之外的第二層。
- （S0）App 的 `Application` 類別也會在 isolated process 內被建立並執行 `onCreate`：正式 App 必須在
  `Application.onCreate` 以 `Process.isIsolated()` 判斷並直接返回，不得在沙箱內初始化資料庫、Koin、網路或 Compose。
- （S0 實機，Galaxy S24 Ultra／Android 16）isolated process 沒有網路與檔案權限，但**仍可 fork／exec 系統程式**。
  沙箱程序必須在載入腳本前安裝 seccomp 過濾器（`PR_SET_NO_NEW_PRIVS`＋`SECCOMP_FILTER_FLAG_TSYNC`），禁止
  `execve`／`execveat`、`fork`／`vfork` 與沒有 `CLONE_THREAD` 的 `clone`（`clone3` 回 `ENOSYS`），只允許建立執行緒；
  S0 已實作並在實機驗證有效（`spikes/sandbox-s0/android/` §9）。安裝失敗（含 TSYNC 回傳無法同步的執行緒）時
  必須 fail closed：不建立 runtime、不執行腳本，回報穩定錯誤。
- （S0）Host 讀取沙箱送來的 fd 型 payload 必須有整體期限，且不得在 binder 執行緒上阻塞；等待中的呼叫在解碼結束前
  仍要留在 pending 表，讓 stop／崩潰能完成它。pipe 讀取端可能與沙箱共用，搶讀可讓 `poll` 後的 `read` 阻塞：讀取前
  設 `O_NONBLOCK`，並以解碼看門狗（期限＋1 秒）終止該沙箱作為最後防線。收到的 fd 不論解碼成功與否都必須關閉，
  payload 只能使用一種傳輸方式。
- （S0）非同步 op 的啟動與取消必須在同一把鎖下判定：取消先到則永不啟動；已啟動則一定送出取消。沙箱結束時取消
  它所有已啟動的 op，且 op／call 的登記與「標記結束」共用同一把鎖，結束後不得再登記。
- （S0）結束流程（看門狗、binder death、stop）只在鎖內更新狀態；future 的完成與 handler 通知派送到其他執行緒，各自
  獨立，不得在共用排程執行緒或 binder 執行緒上執行呼叫端的後續回呼。
- （S0）client 生命週期（啟動 → 綁定 → 結束）在同一把鎖下判定：結束後不綁定、不接受遲到的連線；結束與綁定同時
  發生時恰好解除一次；SandboxManager 的帳務（移除項目、崩潰計數）在對外通知失敗之前完成。
- （S0 實機）OEM 會凍結螢幕關閉或在背景的程序（Samsung Freecess），期間所有呼叫都會停住；S2 的逾時、看門狗與
  崩潰計數不得把凍結期間算成沙箱的錯。
- （S0）`useAppZygote` 會多一個常駐的 zygote 程序，是否採用改由 S2 實機量測冷啟動與 PSS 後決定。S2 已決定採用（§18.3）。
- （S0）大 payload 預設採 `ParcelFileDescriptor.createPipe()` 串流 frame：與 §4.2 的 chunk frame 語意一致、天然有上限、
  不必處理對端改寫共享記憶體；除非實機量測顯示 `SharedMemory` 在 1–4 MiB 有兩倍以上的延遲優勢。

### 4.4 Desktop（macOS／Windows）

- Host 以 `ProcessBuilder` 啟動隨 App 簽章發佈的原生 `shinsou-sandbox` 執行檔，一個 package 一個子程序，
  IPC 只用繼承的匿名 stdin／stdout pipe（不開 socket、不用具名 pipe），stderr 丟棄或限量收集。
- 不使用子 JVM：每個 JVM 約數十至上百 MB RSS、啟動慢；原生 helper 只有數 MB。
- macOS：
  - （S0 修訂）主方案為 helper 啟動後、讀第一個 frame 前自行呼叫 `sandbox_init` 套用 deny-all profile（拒絕網路、
    檔案、`process*`、`mach*`、`ipc*`、`signal`）；S0 驗證全部隔離項目通過，且主 App 未啟用 App Sandbox 時也能套用。
    啟動序列固定為：清空 environ → 關閉多餘 fd → `RLIMIT_CORE=0` → 忽略 SIGPIPE → 套用沙箱 → 讀 frame；EOF 或協定
    違規立刻結束。
  - App Sandbox entitlement（只有 `com.apple.security.app-sandbox`、不含 `network.client`）降為備案：S0 顯示它
    **不能阻止 `fork`／`exec`**、可讀 `/etc` 等系統路徑、無法再疊加 `sandbox_init`，且需要內嵌含 `CFBundleIdentifier`
    的 `Info.plist`、會在 `~/Library/Containers/` 留下 container。
  - helper 仍以 Hardened Runtime 簽章，不允許 JIT（QuickJS 本身沒有 JIT）。
  - 記憶體：QuickJS 上限為主；Host 另以 `proc_pid_rusage` 監控 RSS 超限即 kill（macOS 不可靠支援 `RLIMIT_AS`）。
  - `sandbox_init` 已 deprecated：每個 macOS 大版本都要在 CI 跑隔離探測（S3 起為 `sandbox-core/test/isolation_probe.c`，§19.1），
    若 Apple 移除該 API 再改走 App Sandbox entitlement 並另補「禁止子程序」的機制。
- Windows：
  - 以 **Low Privilege AppContainer（LPAC）** 啟動：不給任何 capability（尤其是 `internetClient`），並以
    `PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT` 退出 `ALL APPLICATION PACKAGES` 群組。S0 實測一般
    AppContainer 仍可讀部分 HKCU（例如 Explorer 的最近文件紀錄），LPAC 才全部拒絕。
  - （S3 修訂）JVM 的 `ProcessBuilder` 無法建立 AppContainer 程序，所以 `shinsou-sandbox.exe` 先當 launcher：以 LPAC＋Job
    重新啟動自己，只把自己的 stdin／stdout 交給被隔離的那一份，然後等待它結束；launcher 本身不讀任何輸入。被隔離的那一份
    不相信命令列參數，讀 frame 前先檢查自己的 token（AppContainer、沒有 capability、不在 `ALL APPLICATION PACKAGES` 內）
    與 Job 限制，不符就結束（§19.4）。
  - **每個 package 一個 AppContainer**（`Shinsou.Sandbox.<SHA-256(package id) 前 16 bytes>`）：同一個 AppContainer 的程序
    可以互相開啟，共用一個就等於讓套件讀其他套件的記憶體。
  - helper 是單一靜態執行檔（不帶 DLL），只授予 `ALL RESTRICTED APPLICATION PACKAGES` 對它所在的私有目錄 read＋execute；
    子程序環境只帶 `SystemRoot`／`LOCALAPPDATA`／`TEMP` 等路徑變數（缺少時 `CreateProcess` 失敗），不帶 Host 其他環境變數。
  - 只繼承 pipe（`PROC_THREAD_ATTRIBUTE_HANDLE_LIST`），並以 `PROC_THREAD_ATTRIBUTE_JOB_LIST` 在建立時就加入
    Job（不留「已建立但不在 Job 內」的空窗）；建立後任何設定失敗都先終止子程序。
  - 指派 Job Object：`JOB_OBJECT_LIMIT_PROCESS_MEMORY`、CPU rate control（hard cap，S0 實測有效）、`KILL_ON_JOB_CLOSE`、
    `ACTIVE_PROCESS_LIMIT = 1`（不能再開子程序）、UI restrictions。（S3）另加 process mitigation（禁止 win32k 系統呼叫、
    動態程式碼、遠端與低完整性映像、非系統字型、舊式擴充點；strict handle checks 等）。child process policy 會讓 LPAC
    子程序在載入時就以 `STATUS_DLL_INIT_FAILED` 結束，所以不用；禁止子程序由 Job 的 `ACTIVE_PROCESS_LIMIT` 負責。
  - Host 結束時 job 一起結束，不留孤兒程序。
- 打包：`shinsou-sandbox` 要為 macOS arm64／x64、Windows x64 交叉編譯並簽章／公證，納入 `docs/BUILDING.md`、
  `docs/RELEASING.md` 的流程。

### 4.5 iOS

- `shinsou-sandbox-core` 編成 `wasm32-wasi`（只連結 Host 提供的 import，不連結 WASI 檔案／socket API），
  以 WASM 直譯器在專屬背景執行緒執行，每個 package 一個 instance、各自的 linear memory 上限。
- Host import 只有兩個（S0 已驗證，最終 `.wasm` 沒有任何 WASI import）：`host_bridge(frame_ptr, frame_len, out_ptr, out_cap) -> len`
  與 `host_clock_ms(clock) -> i64`（interrupt handler 需要單調時鐘）。Host 在該執行緒上同步處理（與現在 JSC 的
  `runBlocking` bridge 模式相同），網路仍在 Host 的 coroutine／URLSession 上執行。回覆大於輸出緩衝時需要分段取回；
  等待非同步結果的 `ASYNC_WAIT` 是阻塞式 frame，terminate 只在 import 返回後生效，因此 Host 必須切片等待並自行逾時。
- WASM runtime：WAMR **fast interpreter**，且必須以 `WAMR_BUILD_THREAD_MGR=1` 建置（否則長迴圈要到函式邊界才能被
  terminate），並在 iOS 上以 `WAMR_DISABLE_HW_BOUND_CHECK=1`、`WAMR_DISABLE_STACK_HW_BOUND_CHECK=1` 建置（iOS 拒絕硬體
  邊界檢查的大段位址保留與 stack 保護頁，否則初始化失敗；改用軟體邊界檢查，S0 實機量測效能不受影響）；執行 WASM 的
  執行緒需自行建立並給足 stack（iOS 次要執行緒預設只有 512 KiB）；classic interpreter 在 QuickJS 上慢到不可用。interpreter stack 至少 5 MiB（JS stack 上限 1 MiB 時），
  建議 8–16 MiB；線性記憶體上限只能調低、不能調高模組宣告的 max。
- 效能不足時的替代方案：以 wasm2c 把同一個 `.wasm` 預先轉成 C、靜態連結進 App（非 JIT，iOS 允許，約原生 2–3.5 倍，
  記憶體隔離保留），代價是無法搶佔中止，需另設計 CPU 型 DoS 對策（§16）。
- 中止：Host watchdog 在 deadline 後呼叫 runtime 的 terminate；該 instance 丟棄並重建。
- 現有 iOS JS DOM bootstrap 不再用於 sandbox tier；DOM 改由編進 WASM 的 C 解析器提供，與 Android／Desktop 一致。
- 效能模式（D1）：預設一律 WASM。若 S0 量測顯示 WASM 效能不足以日常使用，才提供使用者可選的
  「原生 QuickJS（較快、隔離較弱）」模式：預設關閉、可逐套件或全域開啟、UI 明確說明引擎漏洞會直接危及 App。
  若 S0 顯示 WASM 已可接受，則不提供此模式，減少一條需要維護與測試的路徑。
  **S0 結果：WASM 已可接受，不提供此模式**（lexbor 在 WAMR fast 上解析＋查詢 ≤ 200 KB 頁面約 70 ms，§16.1）。

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

決策：sandbox tier 採沙箱內 C 解析器，**選定 lexbor 3.0.1（Apache-2.0），S1 直接上線，不以 iOS JS bootstrap 過渡**（S0 修訂）。
S0 結果：官方語料 151 個 selector × 13 份頁面共 1,963 次執行，與 jsoup 1.18.3 結果數量零差異；解析比純 JS bootstrap 快
30–85 倍；純 JS bootstrap 在官方語料就有 4 次結果不同，且沒有 HTML 隱含結尾標籤處理（詳見 `spikes/sandbox-s0/html/`）。
JS bootstrap 只保留作為 parity 測試的對照組。

語意範圍（取代「與 jsoup 一致」的無界承諾）：

- **保證與 jsoup 1.18.3 一致**：`select`／`selectFirst` 的結果集與順序、`text`、`ownText`、`attr`（含 `abs:`）、`hasAttr`、
  `absUrl`、`tagName`、`className`、`id`、`children`、`parent`、兄弟、`remove`。
- **不保證**：`html()`／`outerHtml()` 的空白與屬性拼寫（改為 HTML5 序列化；`script`／`style` 的 `html()` 保證一致）、
  `<template>` 內容、Document 本身出現在 `*` 等結果中、非 UTF-8 輸入。
- **不支援（穩定錯誤）**：`:matches*`、`:matchText`、`:containsData`、`:containsWholeText`、`[a~=regex]`、`[^prefix]`。

已知限制：lexbor 的解析與 selector 匹配是一次 C 呼叫，**不能被 QuickJS interrupt handler 中斷**，依賴輸入上限與
Host watchdog；`:nth-child` 家族對極寬的兄弟列表是 O(n²)（S1 改成 O(n) 標記實作）；DOM 記憶體不在 JS heap 內，需另編預算（§7）。

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
| DOM 記憶體（S0 新增） | `sandbox_dom` allocator 預算（lexbor `lexbor_memory_setup`） | 64 MiB／package；每份文件約 1.2 MiB 固定開銷 |
| 同時存活 DOM 文件（S0 新增） | 沙箱內計數 | 16 |
| DOM 節點／深度 | 沙箱內強制 | 65,536 節點、256 層（S0 實測真實頁面最多約 1 萬個節點、深度 ≤ 15） |
| WASM interpreter stack（S0 新增，iOS） | WAMR `wasm_stack_bytes` | 8–16 MiB（至少 5 MiB；只有碰到的頁面計入 RSS） |
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
- **隔離探測**：在沙箱內以 C 測試程式（僅測試建置）嘗試開檔、連線、`fork`／`CreateProcess`、讀環境變數，全數必須失敗。
  S0 修訂：`socket()` 建立在 macOS 兩種沙箱下都無法禁止，以 `connect`／`bind`／`sendto` 失敗為準；Android isolated
  process 為了載入 dex 通常可讀自己的 APK，因此「讀 App 檔案必須失敗」限縮為 App 資料目錄（`dataDir`、`shared_prefs`、
  `databases`）與外部儲存。Android 另需驗證「產生子程序失敗、建立執行緒成功」（seccomp 鎖定），探測不得在沙箱程序內
  直接 exec。macOS 探測沿用 `spikes/sandbox-s0/macos/` 的 `isolation-probe`，列入 S3 的 CI。
- **DOM parity**：`spikes/sandbox-s0/html/golden/` 的 fixture＋jsoup oracle 流程列入 parity 測試，任何新增差異必須先登記；
  `fuzz_dom` 列入常態 fuzz 與 ASan CI。
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
| D1 | iOS 第三方套件的執行方式 | (a) QuickJS-in-WASM；(b) 原生 QuickJS in-process；(c) (a) 為預設、(b) 為使用者可選的效能模式；(d) iOS 先不開放第三方 | (c) | **(c)**；依 S0 量測結果**不提供效能模式**，iOS 只有 WASM 一條路徑 | 預設保有記憶體安全隔離；S0 量到 lexbor 在 WAMR fast 上解析＋查詢 ≤ 200 KB 頁面約 70–110 ms，遠低於 1 秒門檻，因此原生效能模式不提供（§4.5、§16.1） |
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

1. ~~C HTML 解析器的最終選擇與 jsoup 語意差異的容忍範圍。~~ **S0 已回答**：採用 lexbor 3.0.1，容忍範圍見 §5.2 的
   「保證／不保證／不支援」三分法。仍待 S1：`:nth-child` 家族改 O(n)、是否在 token callback 加 deadline 檢查、是否裁掉
   編碼表（約 0.3 MB）。上游停止維護時由本專案持有 fork。
2. ~~WASM runtime 的最終選擇。~~ **S0 已回答**：WAMR fast interpreter（`THREAD_MGR=1`）功能可行；效能不足時改採
   wasm2c（§4.5、§16）。wasm3 未評估。
3. ~~macOS 主 App 是否已啟用 App Sandbox。~~ **S0 已回答**：不影響；helper 以 `sandbox_init` 自行套用沙箱（§4.4）。
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
- iOS 的 WASM 直譯會讓第三方套件變慢：S0 量到 WAMR fast 上 JS 運算約為原生的 18–50 倍、lexbor 解析約 12–19 倍；
  典型頁面解析＋查詢約 70 ms，可接受（§16.1）。
- 每個沙箱程序佔記憶體；需要 LRU 與閒置回收，冷啟動會增加首次呼叫延遲。
- 開放生態後，「使用者被說服授權惡意套件」是無法完全以技術消除的風險，必須靠 UI 揭露、發佈者撤銷與使用者本機封鎖名單降低。
- 不設中央 denylist（D5）且過期不停用（D8）：發現惡意套件時 Shinsou 無法遠端保護已安裝的使用者，被撤銷的套件可能
  無限期留在未更新的裝置上。
- minSdk 提高到 29（D2）：Android 8～9 使用者之後無法再更新 App。
- 非同步 API 在 S1 一起做（D10）：S1 工程量約增加 50%，並多一組取消與並行語意需要長期維護。
- 兩種信任模式（D3）、`SourceKey` v3 書庫遷移（D6）、完整 PSL（D11）都是新增的實作與測試面。
- 只透過 GitHub Release 發佈（D12）：不受商店審核限制，但 iOS 只能側載、macOS／Windows 需自行處理簽章與公證。

## 16. S0 驗證結果（2026-10-26）

原型與完整數據在 [`spikes/sandbox-s0/`](../../spikes/sandbox-s0/README.md)（quickjs-ng v0.17.0、WAMR-2.4.5、lexbor v3.0.1；
量測機器 Apple M4 Pro、24 GiB、macOS 27.0.1）。各平台原型依 §12 S0 的驗收標準完成如下：

| S0 項目 | 結果 |
|---|---|
| 共用核心 `shinsou-sandbox-core` | 完成。每個 runtime 有記憶體／stack／時間／bridge 次數／結果大小上限；逾時與 bridge 配額耗盡是腳本無法 catch 的錯誤；quickjs-ng 的 regex 引擎會呼叫 interrupt，災難性回溯可被中止（回答 §7 Regex 列的疑問）；同步 bridge 與 `bridge.net.fetch`（D10）皆可。惡意語料與非同步語意測試在 Release 與 ASan＋UBSan 下全數通過 |
| Android | `.so`（arm64 1.05 MiB）只依賴 libc／libm／libdl；Gradle 產出含 `isolatedProcess`、minSdk 29 的 AAR／APK。**實機（Galaxy S24 Ultra／Android 16）**：每 package 獨立隔離程序、無網路與檔案權限；發現可產生子程序，以 seccomp 鎖定補上；冷啟動 105–217 ms；pipe 傳 1 MiB 結果 32 ms；超限結果修正後 118 ms 內回 `LIMIT_RESULT`；kill 一個沙箱不影響其他。Codex review 後：seccomp 安裝失敗時拒絕載入；惡意沙箱送半個 frame 不關 pipe 時，該呼叫在 5 秒讀取期限回 `BAD_PAYLOAD`，期間其他呼叫不受影響 |
| macOS helper | `sandbox_init` deny-all 全部隔離項目通過；冷啟動 8.0 ms、invoke／同步 bridge 往返約 0.02 ms、常駐約 6 MiB；helper 或 Host 被 kill 都不留孤兒程序 |
| Windows helper | GitHub Actions `windows-2022`：一般 AppContainer 仍可讀 HKCU，**改為要求 LPAC**（§4.4）；LPAC＋Job 下網路、使用者檔案、`C:\Users` 列舉、子程序、開啟 Host 程序、HKCU、超量記憶體全部被拒，執行緒可用；核心測試在其中全數通過；冷啟動 18.7 ms（無沙箱 18.0 ms）；Host 被 kill 時 Job 一併結束子程序；子程序在建立時即加入 Job。helper 原型（pipe）在 LPAC＋Job 內：invoke 往返 0.023–0.038 ms、同步 bridge 24–49 µs、1 MiB 結果 7.5–10.7 ms、working set 約 4–4.5 MiB；Job CPU hard cap 生效。Server 2022 與 2025 結果一致 |
| iOS WASM | `.wasm` 只有 2 個 import；所有情境在 WAMR fast／classic／wasm2c 與原生一致；跨執行緒 terminate 約 2 ms 生效 |
| HTML 解析器 | 採用 lexbor（§5.2） |
| 官方套件量測 | 不碰 DOM 的步驟 QuickJS 耗時約為 Rhino（已 JIT 暖機）的 0.1–0.5 倍；以純 JS DOM 解析 HTML 則慢 3.5–25 倍，證明瓶頸在 DOM 而非引擎 |
| 非同步 host 回呼 | 原生、helper pipe、JNI、WASM 四種路徑都可行 |

iOS WASM 相對原生 QuickJS 的耗時倍數：

| | WAMR fast | WAMR classic | wasm2c |
|---|---|---|---|
| JS 運算 | 18–40× | 130–740× | 2–3.3× |
| 解析 50 KB HTML（純 JS DOM） | 49×（0.6 秒） | 193× | 2.0× |
| instance 冷啟動 | 21×（18.8 ms） | 62× | 1.9× |
| 每 instance RSS | 17 MB | 34 MB | 1.4 MB |

### 16.1 D1 的結果：不提供原生效能模式

D1 規定「是否提供原生 QuickJS 效能模式，依 S0 量測決定」。S0 補量 lexbor 編進 `.wasm` 後在 WAMR fast 上的成本
（`spikes/sandbox-s0/wasm/README.md` §9），結果如下（「解析＋查詢組」＝解析加 6 種代表性查詢，M4 Pro）：

| 頁面 | 原生 | WAMR fast | wasm2c |
|---|---:|---:|---:|
| manhuagui 首頁（真實，158 KB） | 4.3 ms | **71.8 ms**（17×） | 7.5 ms |
| 合成 200 KB | 4.4 ms | **71.9 ms**（16×） | 7.1 ms |
| dm5 首頁（真實，431 KB） | 6.1 ms | **109.4 ms**（18×） | 10.0 ms |
| 合成 1 MB | 22.5 ms | **367.9 ms**（16×） | 36.3 ms |

- 典型頁面（≤ 200 KB）約 70 ms，相對「約 1 秒」的門檻有 10 倍以上餘裕；同樣 50 KB 頁面，純 JS DOM 在 WAMR fast 要
  600 ms，lexbor 只要 19.5 ms。
- 因此依 D1 的規則定案：**不提供原生 QuickJS 效能模式**；iOS 唯一的 sandbox 路徑是 QuickJS 與 lexbor 編進同一個
  `.wasm`、以 WAMR fast（`THREAD_MGR=1`）執行。
- **S4 不改採 wasm2c**：wasm2c 快 7–9 倍，但無法搶佔。S0 的最壞情況測試中，WAMR fast 的 watchdog 在 500 ms 時
  terminate，506 ms 就在 lexbor 不可中斷的 C 迴圈中途停下；wasm2c 只能等迴圈跑完。效能不是瓶頸時，保留可中止性較重要。
  wasm2c 只在 iPhone 實機量測不足時當備案。
- **iPhone 實機確認**（2026-10-26，`spikes/sandbox-s0/ios/`）：iPhone 17 Pro Max（iOS 27.0.1）上 200 KB 頁面解析＋查詢組
  69.6 ms、manhuagui 首頁 70.5 ms、dm5 首頁 107.1 ms、1 MB 353.6 ms，與 M4 Pro 相差在 10% 內，門檻有 14 倍以上餘裕。
- 仍需在 S4 確認的前提：只量了最新機型；最舊支援機型（iOS 16）單核可能慢 3–4 倍，推估 200 KB 頁面約 0.2–0.3 秒，
  仍低於門檻但未實測。端到端（QuickJS 呼叫 `sbx_dom_*` 與結果組裝）與含 lexbor 初始化的冷啟動也未量。S4 驗收門檻
  訂為「最舊支援機型上，200 KB 頁面解析＋一組查詢 ≤ 1 秒」。

### 16.2 核心 API 的 S1 修訂項目

- 取消改為以 invocation generation／callId 為單位的 token；目前 `ssc_invoke` 開始時會清掉先到的取消、完成後才到的取消
  也不會反映（三個原型都在外層補救）。
- WASM 下無法跨執行緒呼叫 `ssc_cancel`：改由 Host 讓 `host_clock_ms` 回傳極大值，或提供線性記憶體中的取消旗標。
- `ssc_invoke` 改收 `(ptr, len)`、提供批次 `ssc_deliver_async`。
- 記憶體統計分開：QuickJS heap、DOM 預算、WASM 線性記憶體三者分別計算（§7）。
- 已在 S0 修正：`ssc_deliver_async` 的 payload 不需以 NUL 結尾；`bridge.log` 改為本地 Log，不佔 bridge 次數；
  結果轉換在記憶體不足時的非法釋放；序列化期間 `toJSON` 排入的 job 跨 invocation 執行（改為序列化前關閉 Host 介面、
  結束時排空 job，排不完則 runtime 標記為不可用並由 Host 重建）；取消時 Host 回呼重入交付結果的問題（改為先讓 slot
  失效再通知 Host，invocation 關閉後拒絕交付與啟動排隊請求）；`source`／hook getter 拋錯或超限時被誤報為
  `NO_SUCH_HOOK`。
- S1 要求：invocation 結束時「排空 job → 取消 pending 請求」的順序、序列化不得存取 Host、runtime 不可用時的重建，
  都要列入 §13 的非同步與惡意語料測試。
- S1 規範（S0 經五輪對抗式 review 收斂）：核心在呼叫任何可能執行腳本程式碼（`then`／getter／setter／`toString`／
  `Error.prepareStackTrace`）或 Host 回呼的 API 之前，自身狀態必須已經一致——slot 先移除再 settle、配額先保留再啟動、
  核心建立的物件只用自有資料屬性、跨邊界參數只接受字串、錯誤分類不依賴腳本可控的訊息。§13 的惡意語料需包含
  `Object.prototype.then` getter、`Error.prototype` setter、覆寫 `JSON.stringify`、Host 回呼中同步重入交付等案例。

### 16.3 尚未完成、需要使用者協助的 S0 項目

無。延後到 S3／S4 的驗證：Windows 11 桌面版一般使用者帳號上重跑隔離探測（S0 用的是 Server 2022／2025 runner）、
Windows helper 的非同步 bridge；iOS 最舊支援機型實測；Android 的 OEM 程序凍結對逾時的影響（§4.3）。

## 17. S1 進度與實作決策

S1 依相依順序切成可各自驗證的增量；每個增量完成時更新本節。

| 增量 | 狀態 | 位置 |
|---|---|---|
| (a) SandboxProtocol v1 codec | ✅ 完成：10 項單元測試；mutation fuzz 20 分鐘 3.64 億筆輸入無失敗；24 小時 fuzz 171.6 億筆無失敗（17.6）；Codex 複審後修正「大量 1 位元組 chunk 造成 Host 記憶體放大」，非最後一塊必須是滿額大小 | `composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/sandbox/SandboxProtocol.kt`、`SandboxCbor.kt` |
| (b) SandboxBridgeDispatcher（同步 bridge＋`net.fetch`） | ✅ 完成：11 項單元測試；Codex 複審後加入解析前的 JSON 形狀預檢（總節點、單一陣列／物件、深度，`pluginJsonShapeWithin`），最終判定 approve；完整 desktop 套件 1,485 項通過 | `…/sandbox/SandboxBridgeDispatcher.kt` |
| (c1) 正式 `shinsou-sandbox-core`：C 端 codec、核心（§16.2 API 修訂）、frame driver、桌面 helper 入口 | ✅ 完成：C 測試（codec golden vector／惡意輸入／50 萬筆 fuzz、核心語料＋generation／保留進入點／請求預算、driver 端到端）在 Release 與 ASan＋UBSan 下通過；**Kotlin Host 驅動真正的 C 沙箱**的互通測試通過（真實腳本跑沙箱內 DOM、同步 bridge、並行 `net.fetch`、login 限縮；跨程序取消 3 ms、時間上限 3 秒）；Codex 五輪複審後判定 approve | `sandbox-core/` |
| (c2) lexbor DOM 綁定進 JS（取代純 JS DOM bootstrap） | ✅ 完成：以 JS API 跑 S0 的 golden 語料（151＋邊界 selector × 13 份真實頁面），輸出與 S0 直接呼叫 C 的結果**逐位元組相同**（86,712 行），即官方語料與 jsoup 結果數量 0 差異；DOM 專屬測試 20 項；故障注入讓模組的 272 個配置點逐一失敗皆無崩潰；Release 與 ASan＋UBSan 全過；Codex 五輪複審後判定 approve | `sandbox-core/src/ssc_dom_js.c`、`sbx_dom.c` |
| (d) `SandboxRuntime`／`SandboxRuntimeFactory`（`ScriptPluginRuntime` over transport，拒絕 provenance） | ✅ 完成：以「說協定的假沙箱」驅動 19 項測試（握手、版本不符、bridge 往返、非同步 fetch、各種協定違規、逾時、取消、login 限縮、結果上限、日誌、frame 洪水）；Codex 三輪複審後判定 approve | `…/sandbox/SandboxSession.kt`、`SandboxScriptPluginRuntime.kt`、`SandboxScriptJson.kt` |
| (e) JVM 測試用 transport、官方語料 parity、2 個官方套件改寫為 `net.fetch`、24 小時 fuzz | ✅ 完成（24 小時 fuzz 無 crash，見 17.6）：14 個官方來源的中繼資料／filter／偏好設定／stub 呼叫與 Rhino 0 差異；5 個 S0 錄製套件的完整 hook 鏈逐項相同；komiic、mangacopy 的 `net.fetch` 改寫版在錄製資料與 7 個合成情境（含失敗與提前結束）上與同步原版結果、請求序列皆相同。parity 抓到 **source-failure 標記遺失**，FAILURE frame 因此加入 marker 欄位（17.1）；Linux CI 首次建置抓到 libm 連結順序，LeakSanitizer 抓到 quickjs-ng 的 OOM 洩漏（以修補檔修正，17.6）；Codex 多輪複審 | `…/desktopTest/…/sandbox/SandboxOfficialParityTest.kt`、`desktopTest/resources/sandbox-netfetch/`、`sandbox-core/fuzz/`、`.github/workflows/sandbox-fuzz.yml` |

### 17.1 協定格式（修訂 §4.2）

- **不使用 kotlinx.serialization CBOR**，改為手寫的嚴格 CBOR 子集：只有無號整數、byte／text string、陣列與
  `false`／`true`／`null`；只接受定長與最短編碼；不支援負數、map、tag、浮點。理由：沙箱端在 macOS／Windows helper
  與 iOS WASM 是 C，必須逐位元組實作同一格式；子集小到兩邊能完全對齊，而且「解碼成功即可重新編碼回相同位元組」
  成為可檢驗的性質（fuzz 以此為判準）；不新增相依、不依賴反射。
- Frame body 是 `[frameType, 欄位…]` 固定位置的陣列。型別碼：`0` CHUNK；Host→沙箱 `1` Init、`2` Invoke、`3` Cancel、
  `4` BridgeResult、`5` AsyncBridgeResult、`6` AsyncCancel；沙箱→Host `16` Ready、`17` BridgeCall、`18` AsyncBridgeCall、
  `19` Result、`20` Failure、`21` Log、`22` LimitExceeded。每一端只接受對端可送的型別。
- 新增 `Ready{callId, protocolVersion}` 作為 `Init` 的回應：Host 收到 v1 以外的版本即 fail closed。
- 超過 1 MiB 的 frame 以 `[0, more, bytes]` 串流，重組上限由接收端設定；不得把可放進單一 envelope 的 frame 拆塊，
  也不得在拆塊途中插入其他 frame。
- Hook 參數、結果、bridge 參數與回覆仍是 JSON 文字（核心本來就用 JSON），由 Host 既有的有界 JSON 解碼重新驗證。
- 錯誤碼：1–11 沿用核心的 `ssc_status`；32 起為 Host／transport 產生（`UNSUPPORTED_PROTOCOL`、`PERMISSION_DENIED`、
  `UNKNOWN_OPERATION`、`HOST_FAILURE`、`NETWORK_FAILED`、`REVOKED`）。
- `Failure` 是 `[20, callId, code, marker]`（增量 e 加入）：`marker` 為 0 或 Host 擁有的 source-failure 標記編號
  （1 `HTTP_CHALLENGE`、2 `HTTP_BLOCKED`、3 `HTTP_FORBIDDEN`、4 `HTTP_UNAVAILABLE`、5 `QUOTA_EXCEEDED`），只能搭配
  `SCRIPT_ERROR`，其他組合兩端都拒絕。官方套件以 `throw new Error("SHINSOU_SOURCE_…")` 讓 App 顯示固定指引與復原
  動作（`SourceFailureMarker.kt`）；沙箱只在丟出的值**恰好**是標記名稱時設定編號（字串，或 Error 自身的資料屬性
  `message`；不執行 getter、不檢查 Proxy），Host 再還原成同一個標記訊息。除此之外腳本文字仍不離開沙箱。
- 單元測試含固定位元組的 golden vector，C 端 codec（增量 c）必須通過同一組向量。

### 17.2 Bridge op 名稱與 sandbox tier 政策（修訂 §5.1）

- op 名稱沿用現行 `bridge.*` 方法名（`httpGet`、`setCookie`…），參數是相同的 JSON 陣列，既有腳本與 bootstrap 不需改；
  §5.1 表中的 `net.request` 等是分類名稱，不是 wire 上的字串。唯一新增的 op 是非同步的 `net.fetch`。
- DOM（`dom*`、`parseHtml`）與 `log` 在沙箱內處理，送到 Host 一律 `UNKNOWN_OPERATION`。
- Cookie 值的讀取（`getCookie`／`getCookies`）需要 §5.1 的 COOKIE_READ 授權；S1 先以 dispatcher 的
  `SandboxBridgePolicy.allowCookieRead`（預設關閉）表示，並只允許 `credentialOrigins`。把它接上 §6 的權限紀錄與 UI
  （新增 `PluginRuntimePermission` 值）屬於權限模型的工作，與 §6.5 一起做。
- Cookie 寫入的 domain 必須是 credential host 本身或其上層網域，且不得是 public suffix（與回應 Cookie 解析共用
  `isPluginCookiePublicSuffix`）。
- 密碼（`getCredentialPassword`／`setCredential`／`clearCredential`）只在 Host 啟動的 login invocation 可用（§6.4）。
- `browserSessionRequest` 一律使用 pinned transport，不提供 developer unpinned 例外。
- `net.fetch` 只接受 GET／POST，回應 headers 移除 `Set-Cookie`、`Authorization`、`WWW-Authenticate` 等可觀察或重放憑證的欄位。
- 每次呼叫先檢查 invocation 是否已結束與 runtime generation，再計入 bridge 次數；Host 工作完成後再檢查一次，
  撤銷或取消期間完成的結果一律丟棄（`REVOKED`）。

### 17.3 Host 端 session 規則（增量 d）

- 保留的 bootstrap 進入點，以 `Invoke` 呼叫（核心必須支援，增量 c）：`__shinsouSelectSource(id)`、
  `__shinsouMetadata()`、`__shinsouPreferences()`、`__shinsouHasFunction(name)`。Init 的腳本由 Host 組成：
  來源選擇的全域變數前置段＋套件腳本，與 JavaScriptCore 引擎相同。
- `Init` 之後沙箱先回 `Ready`，腳本載入後再以同一 callId 回 `Result`／`Failure`；載入期間可以呼叫 bridge。
- 一個 runtime 一次只有一個 invocation；同步 bridge 在讀取迴圈內依序處理，seq 必須遞增；`net.fetch` 以呼叫端
  協程的子工作執行，並行數超過 `maxAsyncInflight` 即為違規（沙箱必須自行排隊）。
- 違規、EOF、逾時（沙箱自己的上限＋2 秒）一律終止沙箱並使 session 不可用；Host 不在同一個 session 上重試。
- 呼叫端取消：送 `Cancel` 並在 1 秒內等待該 call 的 `Result`／`Failure`，期間到達的 bridge 呼叫一律回 `CANCELLED`；
  整段（含 `Cancel` 的寫入）有期限，逾時即終止沙箱。
- invocation 結束：先標記關閉，再以寫入鎖為柵欄，取消並等待所有非同步子工作，然後送 `AsyncCancel`；非同步結果
  只在持有寫入鎖且確認尚未關閉時送出，因此不會出現在 `AsyncCancel` 或下一個 `Invoke` 之後；整段有期限。
- Frame 預算：每個 invocation 的 bridge／async frame 不得超過 `maxBridgeCalls`＋16、Log frame 不得超過 65,536，
  超過即為違規；讀取迴圈每個 frame 都檢查取消、每 64 個 frame 讓出一次，持續湧入的輸入也無法讓逾時失效。
- 結果先以 `pluginJsonShapeWithin` 檢查形狀、再解析、再檢查字串長度；限制類失敗轉成 `PluginResourceLimitException`，
  其餘只帶穩定錯誤碼（`SandboxInvocationException`）。`close()` 讓 generation 遞增並終止沙箱。

### 17.4 原生核心的實作規則（增量 c1）

- 核心 API 修訂（§16.2）：`ssc_init`／`ssc_invoke` 帶 invocation generation（driver 用 protocol 的 callId），
  `ssc_cancel(rt, gen)` 只影響該 generation——先到的取消仍會生效，晚到的不會誤傷下一個 invocation；
  `ssc_invoke` 收 `(ptr, len)`；`__shinsou*` 是全域進入點，其餘名稱只查 `source`。
- 非同步失敗以穩定錯誤碼名稱 reject（`NETWORK_FAILED`、`PERMISSION_DENIED`…）；`bridge.net.fetch` 的請求是與
  其他 bridge op 相同的 JSON 參數陣列 `[url, {method, headers, body}]`。
- bridge 被拒時，腳本看到的是 `{"error": CODE}`（限制類另加 `__shinsouResourceLimit:true`），與 reviewed 引擎相同，
  既有 bootstrap 的 `nativeCall` 不需修改。
- Host 端 session 的讀寫都不在 invocation 內進行：專用的讀取 pump 與寫入協程分別負責 transport，invocation
  只從有界 channel 取資料（`tryReceive`＋喚醒 channel，取消時不會遺失資料）、以不會阻塞的方式排入完整的 frame
  （位元組預算 32 MiB，超過即判定沙箱停止讀取並終止）。真實 pipe 的阻塞讀寫因此不會讓取消或逾時失效。
- 非同步 op 的 in-flight 名額在寫入鎖下、結果排入之前釋放，沙箱一收到結果就啟動下一個排隊中的 op 不會被誤判違規。
- 其餘 C 端規則（stack、沙箱外記憶體預算、bridge 等待、runtime 生命週期）見 `sandbox-core/README.md`。

### 17.5 沙箱內 DOM 的規則（增量 c2）

- bootstrap 的純 JS 解析器仍留在 bootstrap 內但已無法觸及：`SSC_DOM_SHIM` 在 bootstrap 之後重新綁定 `Jsoup.parse` 與
  `bridge.parseHtml`；`fetchAndParse` 透過全域的 `Jsoup` 自動改用原生 DOM。Element／Elements 的方法名稱與回傳形狀
  與 reviewed 引擎一一對應；語意以 jsoup 1.18.3 為準（§5.2 的保證範圍）。
- 同一個節點永遠是同一個 JS 物件（handle 對節點重用、wrapper 依 handle 快取），`===` 比較與 `Elements.select` 去重
  與 JSC 相同；handle 用量也因此只會更少。
- DOM 物件不跨 invocation：每個 invocation 結束時釋放全部文件與 handle，保留下來的節點在下一次使用時拋出
  `STALE_DOM_NODE`。wrapper 的 handle 與 epoch 存在閉包私有的 WeakMap，不暴露建構子，所需的內建方法在任何套件
  腳本執行之前就以 bind 鎖定，腳本無法偽造或竄改。
- 原生函式只接受數字與字串；`String()` 轉換在 JS wrapper 中、於腳本自己的環境進行（jsoup 同樣會呼叫 `toString`）。
- DOM 上限（節點、深度、handle、輸入、記憶體）屬於記憶體預算（§7），違反時以無法 catch 的 `LIMIT_MEMORY` 結束；
  selector 語法錯誤（`SELECTOR_SYNTAX`）與不支援的 selector（`UNSUPPORTED_SELECTOR`）是可以 catch 的一般錯誤。
- 記憶體：模組自己的配置嚴格計入預算（故障注入驗證所有失敗路徑）；lexbor 的配置採軟上限，超過預算仍可完成，
  最多到預算再加 16 MiB，之後該操作以 `LIMIT_MEMORY` 失敗，因為 lexbor 不能承受任意一次配置失敗。`base` URI
  上限 8 KiB。**殘餘風險（已接受）**：系統真的配不出記憶體時，lexbor 內的配置會讓沙箱程序以 `abort()` 結束，
  Host 當作崩潰處理（§4.6）；lexbor 本身不修改。
- 已移除節點之後的 selector 標記改為走訪該元素實際所在的樹（含已分離的子樹）。

### 17.6 Parity 與 fuzz（增量 e）

- **JVM 測試用 transport**：不另做 in-process 的 C 綁定。協定行為由 commonTest 的「說協定的假沙箱」覆蓋，真實
  行為由 desktopTest 以子程序 pipe 驅動 `shinsou-sandbox`（`SandboxProcessTransport`），兩者都不進產品程式碼。
- **官方 parity**（`SandboxOfficialParityTest`）：比較 Rhino（同步原版）、沙箱（同一腳本）與沙箱跑 `net.fetch`
  改寫版。hook 鏈為 popular → details → chapters → 最後一章的 pages；結果逐項相同、請求序列相同，只有四個 hook
  都成功才算完成。失敗只比較類別（腳本錯誤／各種上限）與 source-failure 標記，因為兩個引擎的錯誤措辭不同；
  `Date.now()` 產生的時間在 10 分鐘內視為相同。S0 錄製資料含真實網站內容，只在本機執行；CI 使用不含網站內容的
  合成情境（`sandbox-netfetch/fixtures/`），而且任何「SKIPPED」都會讓 CI 失敗。
- **`net.fetch` 改寫版**（`desktopTest/resources/sandbox-netfetch/`，不是發佈的產物）：hook 改為 `async`，請求改走
  `bridge.net.fetch`，錯誤與狀態語意與同步 API 相同（任何狀態都回傳 body，失敗等同 `{error}`）。mangacopy 的章節
  群組維持依序抓取：原版在第一個群組失敗時立即結束，並行會多送請求並改變失敗行為。
- **Fuzz**（`sandbox-core/fuzz/`，libFuzzer＋ASan＋UBSan＋LeakSanitizer）：`frames_fuzz`（單一 body 與任意切割的
  envelope 串流，判準為「接受即可重新編碼回相同位元組」與 reader 失敗後一律 BROKEN）、`chunks_fuzz`（把精簡輸入
  展開成滿額 chunk 的大 frame 再變異；未變異時必須恰好重組出同一個 frame）、`dom_fuzz`（lexbor 解析、selector、
  讀取與移除節點）；Kotlin codec 以 `SandboxProtocolLongFuzzTest` 執行。`.github/workflows/sandbox-fuzz.yml` 先在
  Linux 跑 C 測試（Release、ASan＋UBSan＋LSan）與 Kotlin↔C 互通／parity，再以平行 shard 執行 fuzz：GitHub 的
  job 上限 6 小時，所以「24 小時」指每個 codec target 合計約 24 CPU 小時（5 × 290 分鐘），chunks_fuzz 另有 2 個 shard。
- **driver 的 `reader_status`**（S2 Codex 複審）：讀取執行緒與 driver 執行緒共用的這個欄位改為全程在鎖內讀寫。原本只有在
  driver 先結束、讀取執行緒仍在寫入時才有競爭，而那時這個值不會被使用，但仍是 C 的 data race。`driver_test` 以
  ThreadSanitizer 執行無警告（TSan 先抓到的是測試 harness 自己重用堆疊結構的競爭，已改為每個沙箱獨立的 fd 欄位）。
- **24 小時驗收結果**（2026-10-27，GitHub Actions run 40446938665，`ubuntu-24.04`）：每個 shard 跑滿 17,401 秒（290 分鐘），
  全部 17 個 job 通過，沒有 crash、sanitizer 報告或洩漏。

  | target | shard × 時間 | 輸入總數 |
  |---|---|---:|
  | `frames_fuzz`（C codec＋envelope 串流） | 5 × 17,401 s（24 h 10 min） | 6,576,943,903 |
  | Kotlin codec（`SandboxProtocolLongFuzzTest`） | 5 × 17,400 s（24 h 10 min） | 17,155,100,000 |
  | `dom_fuzz`（lexbor DOM） | 5 × 17,401 s（24 h 10 min） | 115,816,027 |
  | `chunks_fuzz`（大型 chunk 重組） | 2 × 17,401 s（9 h 40 min） | 1,147,916 |

  第一次正式執行（run 40446140260）在 fuzz 之前的 Linux 測試就停下：`core_test` 只用 clang 的 `__has_feature` 偵測 ASan，
  GCC 要看 `__SANITIZE_ADDRESS__`，ASan 下的時間放寬因此沒有生效。修正偵測後重跑的就是上表這次。
- **相依修補**：`sandbox-core/patches/` 內的修補由 `fetch-deps.sh` 套用在釘選版本上，每個修補檔說明原因。目前只有
  quickjs-ng v0.17.0 的 `JS_ToCStringLen2` 在 OOM 時洩漏字串參考（腳本可在接近 heap 上限時反覆觸發，讓洩漏量
  持續佔用自己的 heap 預算）。

## 18. S2 進度與實作決策（Android isolated process）

| 項目 | 狀態 | 位置 |
|---|---|---|
| (1) minSdk 26 → 29 | ✅ `android-minSdk = "29"`；程式碼沒有只為 API 26–28 存在的分支（僅有的兩個 `SDK_INT` 判斷是 API 30／33）。README、`docs/BUILDING.md` 已更新。minSdk 29 會讓 AGP 以不壓縮方式打包 dex 與 `.so`（APK 由約 40 MB 變成 106 MB），因為 APK 從 GitHub Release 下載、不經商店切分，改回壓縮打包（37 MB） | `gradle/libs.versions.toml`、`composeApp/build.gradle.kts` |
| (2) release notes 預告 Android 8～9 停止更新 | ⏳ 需要使用者決定發佈時間與措辭（對外發佈） | — |
| (3) `SandboxService`、`bindIsolatedService`、pipe 串流、非同步 frame | ✅ 實機（Galaxy S24 Ultra／Android 16）8 項裝置測試全過 | `androidMain/…/plugin/sandbox/`、`androidMain/aidl/`、`sandbox-core/src/ssc_android_jni.c` |
| (3) 生命週期、LRU、crash-loop | ✅ commonMain 的 `SandboxLifecycle`（三平台共用）；commonTest 8 項＋實機 1 項 | `commonMain/…/sandbox/SandboxLifecycle.kt` |
| 惡意語料、隔離探測、kill 不影響 Host | ✅ 實機 | `androidInstrumentedTest/…/AndroidSandboxDeviceTest.kt` |
| API 29／最新版模擬器、Linux NDK 建置 | ✅ x86_64 模擬器 API 29 與 API 35 各 8 項裝置測試全過（冷啟動 62／311 ms，取消 3／8 ms）；Linux 以 NDK 建出三個 ABI。Android 單元測試只在 macOS 跑：Linux runner 載不到單元測試用的 libsodium 主機函式庫，90 項加密測試在開始前就失敗，與沙箱無關 | `.github/workflows/sandbox-android.yml` |

### 18.1 IPC：協定 frame 直接走 pipe（修訂 §4.3）

§4.3 原本規劃 AIDL `ISandbox`＋`IHostBridge` 兩個方向的 binder 介面，大 payload 另走 pipe。S1 完成後改為：
**AIDL 只負責啟動**（`ISandboxProcess.start(fromHost, toHost)`），之後 SandboxProtocol v1 的 frame 全部走這兩條
pipe，沙箱端由 sandbox-core 的 driver 處理，和桌面 helper 的 stdin／stdout 完全相同。理由：

- 三個平台共用同一個 codec、同一個 `SandboxSession`，以及已做 24 小時 fuzz 的程式碼路徑；不需要第二套 binder 協定。
- 沒有沙箱 → Host 的 binder 回呼，S0 記錄的幾類問題因此不存在：在 binder 執行緒上解碼不可信 payload、
  fd 型 payload 的搶讀與 `O_NONBLOCK` 被清除、oneway 回呼卡住同一 binder 物件。沙箱只持有自己那一端
  （Host 讀取端與寫入端不在沙箱手上），所以無法搶讀 Host 要讀的資料，也改不了 Host 端的檔案狀態旗標。
- 身分 capability 由「每個 package 一組 pipe」提供：沙箱拿不到其他 package 的 pipe。
- Host 端讀寫以 200 ms 為單位 `poll`，`close()` 不必關閉其他執行緒正在使用的 fd；寫入在 `POLLOUT` 後每次最多
  `PIPE_BUF`（4,096）位元組，不會阻塞。逾時、看門狗與取消沿用 `SandboxSession`（S1 §17.3）。

每次 `open` 都以新的 instance name 綁定（`s<序號>_<packageId>`；系統只接受英數、`_`、`.`），所以重啟後不會沿用
已死或已關閉的程序。`start()` 依序檢查 `Process.isIsolated()`（manifest 設錯時不得在 App uid 執行腳本）、
只能呼叫一次、載入 `.so`、安裝 seccomp；任何一步失敗都關閉 pipe 並回負值，不載入腳本。沒有建置 sandbox-core
的 ABI 會因找不到 `.so` 而同樣 fail closed。

### 18.2 生命週期與 crash-loop（§4.6、§7）

- `SandboxLifecycle` 放在 commonMain，Android 用 LRU 4、閒置 60 秒；Desktop（8）與 iOS（3）之後直接沿用。
- runtime 背後的沙箱可替換：崩潰、Host 強制終止或閒置回收後，下一次呼叫會啟動新沙箱並重新載入腳本與選擇來源
  （腳本內的狀態和 App 重啟時一樣不保留）；只有 `close()` 是終點。回收只在沒有呼叫進行時發生（`tryLock`），
  所以所有沙箱都在呼叫中時可以暫時超過上限。
- 計入 crash-loop 的事件：沙箱崩潰／協定違規／Host 終止（`SandboxBrokenException`）、沙箱自己的上限
  （記憶體、stack、bridge 次數、結果大小）、時間上限。10 分鐘內 3 次即停用該 package，直到 App 重啟；
  停用時也會停止仍在執行的沙箱。重啟時腳本載入失敗也計入（否則每次呼叫都會再開一個程序）；hook 內的腳本錯誤、
  `NO_SUCH_HOOK` 等不計入。
- 生命週期帳務（`close()`、呼叫結束、strike）在 `NonCancellable` 內執行：呼叫端在等待生命週期鎖時被取消，也不會留下已死的
  沙箱占用存活名額（Codex 複審；commonTest 兩項，拿掉修正時失敗）。
- 時間上限只在 Host 能為時鐘背書時計入：Android 為「螢幕亮著且 App 在前景（含前景服務）」。OEM 凍結程序
  （§4.3）期間的逾時不算套件的錯。停用的使用者提示屬於 S6／S7 的 UI 工作。

### 18.3 程序啟動：採用 `useAppZygote`

| 實機（S24 Ultra） | 沒有 app zygote | 有 app zygote |
|---|---:|---:|
| 啟動一個沙箱程序（bind＋lockdown） | 465–482 ms | 第一個 644 ms（含 zygote 啟動），之後 125–152 ms |
| 第一次可用（含 bootstrap、腳本、metadata） | 493 ms | 127 ms |
| 每個沙箱程序 PSS | 約 132 MB | 約 43 MB，另有 zygote 常駐約 36 MB |
| 4 個同時存活 | 約 528 MB | 約 208 MB |

`useAppZygote` 在啟動時間與記憶體上都明顯較好，**採用**。zygote 的 preload（`SandboxZygotePreload`）只載入
`libshinsou_sandbox.so`；seccomp 在每個沙箱程序 fork 之後才安裝。app zygote 以 App 的 uid 執行，但只執行 preload
這段 App 自己的程式碼，不執行任何套件腳本；沙箱程序 fork 後換成隔離 uid（實機 `u0_i0`、`u0_i1`…）。
isolated process 會建立預設的 `Application`，App 沒有自訂 `Application` 類別，實機 logcat 也確認沙箱程序內沒有
WorkManager、資料庫或其他 App 初始化；之後若新增自訂 `Application`，必須在 `onCreate` 以 `Process.isIsolated()` 直接返回。

### 18.4 建置

- `composeApp` 以 `externalNativeBuild` 建置 `../sandbox-core`（NDK 27.0.12077973、CMake 3.22.1），ABI 為
  arm64-v8a、armeabi-v7a、x86_64（`abiFilters` 只限制 sandbox-core，相依函式庫仍保留自己的 ABI）。seccomp 過濾器
  支援 aarch64、arm、x86_64，其他 ABI 編譯時就會失敗。
- 所有 variant 都以 Release 最佳化編譯原生函式庫：GitHub Release 發佈的是 debug variant，而 `-O0` 的 QuickJS 慢很多。
- seccomp 過濾器（S2 Codex 複審後）另外只允許 `AF_UNIX` 的 `socket`／`socketpair`（記錄 log 與 socketpair 需要），其他
  位址族群（inet、netlink、packet、bluetooth、vsock…）一律回 `EAFNOSUPPORT`；`socketcall` 整個拒絕。網路原本已由隔離 uid
  與 SELinux 擋下，這一層讓網路隔離不依賴平台政策。實機發現：bionic 的 `socket()` 會先詢問 netd client，直接回 `EPERM` 而
  不進核心，所以先前的探測其實只測到 libc。探測改用原始 syscall，並要求 `socket(AF_INET)` 回 `EAFNOSUPPORT`：這個錯誤碼
  核心與 SELinux 都不會給（SELinux 回 `EACCES`），可以證明是過濾器回應的。實測暫時拿掉這條規則時探測會失敗。
- `nativeRun` 的 pipe 狀態與 `ssc_io` 是靜態的（每個程序只允許一次）：driver 因協定違規或 Host 失敗結束時，可能留下仍卡在
  `read()` 的讀取執行緒，它會一直使用 io 表與輸入端 fd，直到程序結束（`nativeRun` 返回後立即結束）。因此輸入端不在
  `nativeRun` 關閉（避免 fd 編號被重用到那個 `read` 底下），輸出端照常關閉讓 Host 立即看到 end of stream（S2 Codex 第三輪）。
  裝置測試 `aProtocolViolationEndsTheSandboxWithoutANativeCrash` 走這條路徑（Init 前送 Invoke），確認程序結束且沒有原生崩潰；
  舊程式的問題是未定義行為、不一定當場崩潰，所以這個測試是回歸防護，不是重現。
- driver 執行緒的 16 MiB stack（Android `nativeRun` 與桌面 `shinsou-sandbox`）：`pthread_attr_init`／`setstacksize`／
  `pthread_create` 任一失敗都不進 driver、回 `SSC_DRIVER_NOMEM`，在讀取任何輸入之前結束；QuickJS 不會以執行緒實際沒有的
  stack 上限執行（S2 Codex 第四輪）。
- `.so` 只匯出 3 個 JNI 進入點（`nativeLockdown`、`nativeRun`、`nativeProbe`）；`socket`／`fork`／`execve`／`open`
  只出現在隔離自我檢查 `nativeProbe`。CI 檢查匯出符號，並拒絕 `connect`、`dlopen`、`getenv`、`system` 等匯入。
- 第三方原始碼由 Gradle 的 `fetchSandboxCoreDeps` 在 CMake 前呼叫 `fetch-deps.sh` 取得；release workflow 安裝
  NDK／CMake，並確認 APK 內三個 ABI 都有 `libshinsou_sandbox.so`。
- 裝置測試必須加 `-PshinsouSandboxDeviceTest=true`，以 `dev.aluo.shinsoux.sandboxtest` 安裝，因為 connected 測試
  結束時會解除安裝 App；沒有加這個屬性時 Gradle 拒絕執行，避免覆蓋或刪除使用者裝置上真正的安裝與書庫。

### 18.5 實機結果（Galaxy S24 Ultra／Android 16）

| 項目 | 結果 |
|---|---|
| 每個 package 一個程序 | ✅ 不同 pid、不同隔離 uid（`u0_i*`），與 Host（`u0_a*`）不同 |
| 隔離探測（沙箱內 C 程式碼，socket 以原始 syscall） | ✅ `socket(AF_INET／AF_INET6／AF_NETLINK／AF_PACKET／AF_BLUETOOTH／AF_VSOCK)`（且 `AF_INET` 由 seccomp 回應）、開啟 `filesDir` 內檔案、`shared_prefs`、`databases`、`dataDir`、`/sdcard`、`/proc/net/tcp`、`fork`、`execve` 全數失敗；exec 以不存在的路徑探測，以 `EPERM`（seccomp）與 `ENOENT` 區分，不會真的換掉程序 |
| 端到端 | ✅ 沙箱內 DOM、同步 bridge、並行 `net.fetch`、login 限縮、source-failure 標記 |
| 惡意語料 | ✅ 無窮迴圈、深遞迴、陣列囤積、字串倍增、catastrophic backtracking、Proxy／getter 炸彈、竄改 bridge 與保留進入點、私有 IP／loopback／非 HTTPS、header injection、超大結果、bridge 洪水、200 個 `net.fetch` 洪水（超出 in-flight＋佇列的全部被 reject）、永不 settle 的 Promise、微任務洪水：都以穩定錯誤（上限、`SCRIPT_ERROR`、`PROMISE_STALLED`）在 3 秒內結束，之後同一 runtime 立即可用；竄改不影響 Host 端的 metadata |
| 時間上限／取消 | 3,004–3,009 ms 回 `LIMIT_TIME`；取消 9–12 ms |
| kill 沙箱（`am crash <pid>`） | ✅ 該 runtime 下一次呼叫回 `SandboxBrokenException`，其他套件與 Host 不受影響，再下一次呼叫自動啟動新沙箱 |
| LRU／閒置 | ✅ 上限 2 時第三個啟動會回收最久未用的程序；閒置 3 秒（測試值）後程序消失，下次呼叫重新啟動 |
| `close()` | ✅ 3 秒內程序結束 |
| hook 往返 | 約 3.9–4.3 ms（中位數）；桌面同一路徑 171 µs。計時顯示沙箱端往返只有 0.2–0.4 ms，其餘是 Host 端協程切換在 debuggable 的 ART 上的成本，相對於網路請求可忽略，S7 以 release variant 再量 |
| Android 單元測試 | ✅ 1,196 項（minSdk 29） |

## 19. S3 進度與實作決策（Desktop helper）

| 項目 | 狀態 | 位置 |
|---|---|---|
| macOS：helper 自行以 `sandbox_init` 鎖定 | ✅ 本機與 GitHub Actions macOS 14.8／15.7 的隔離探測全過 | `sandbox-core/src/ssc_os_sandbox.c`、`ssc_main.c` |
| macOS：桌面 transport、生命週期（上限 8）、helper 定位 | ✅ 桌面 sandbox 測試 64 項 | `desktopMain/…/plugin/sandbox/DesktopSandboxTransport.kt` |
| macOS：helper 打包與簽章 | ✅ hardened runtime、無 entitlement；App 通過 `codesign --verify --deep --strict` | `composeApp/build.gradle.kts`（`buildMacOsSandboxHelper`） |
| Host 結束不留孤兒程序 | ✅ helper 在 stdin end of stream 後 11–88 ms 內結束（即使正在跑無窮迴圈） | `sandbox-core/src/ssc_driver.c` |
| macOS 公證 | ⏳ 需要 Developer ID 憑證（公開 DMG 目前仍為 ad-hoc 簽章、未公證）；helper 已以 hardened runtime 簽章，設定憑證後不需另外處理 | — |
| macOS：Host 端 RSS 監控、CPU 時間上限（§7） | ⏳ 尚未實作；目前靠 QuickJS 的記憶體與 wall-time 上限 | — |
| Windows：LPAC＋Job object | ✅ GitHub Actions Windows Server 2022／2025：隔離探測 15 項全擋、桌面 sandbox 測試全過（§19.4） | `sandbox-core/src/ssc_os_windows.c`、`DesktopSandboxTransport.kt` |
| Windows 11 一般使用者帳號實機重跑 | ⏳ 需要使用者協助（§16.3） | — |

### 19.1 macOS helper 的鎖定

- 依 §4.4 的啟動序列，`main` 讀第一個 frame 前呼叫 `ssc_os_lockdown()`：清空 environ、`tzset()` 預先載入系統時區、
  `RLIMIT_CORE=0`、關閉 stderr 之後的所有 fd、套用 deny-all profile（`network*`、`system-socket`、`file*`、`process*`、
  `mach*`、`ipc*`、`signal`；只允許 `sysctl-read`）。任何一步失敗回 `SSC_DRIVER_LOCKDOWN`（5）並結束，不讀任何輸入。
- **時區**：鎖定後 libc 讀不到時區檔，腳本的本地時間會靜默變成 UTC，與 Host 的 Rhino 不一致。parity 測試在實作時就抓到
  mangacopy 的章節日期不同；`tzset()` 移到鎖定前後一致。互通測試比對沙箱內外的 `getTimezoneOffset()`，拿掉
  `tzset()` 時得到 `[0,0]`（Host 為 `[-480,-480]`），所以這項測試同時證明 helper 確實無法讀檔。
- **隔離探測**（`sandbox-core/test/isolation_probe.c`＋`run_isolation_probe.py`，ctest 項目）：與 helper 用同一個
  `ssc_os_lockdown()`，在本機 TCP listener、可讀的使用者檔案、可寫目錄、環境變數內的祕密、繼承的 fd 3 都備妥的
  情況下嘗試 14 項操作。不鎖定時 14 項全部成功（證明每個探測都抓得到洩漏），鎖定後全部失敗：TCP connect、UDP
  sendto、bind、連 `/var/run/syslog`、讀使用者檔案、讀 `/etc/hosts`、建立檔案、列 `/Users`、`fork`、`posix_spawn`、
  `fork`＋`execv`、讀環境變數、`kill(parent, 0)`、使用繼承的 fd；執行緒與匿名 `mmap` 仍可用。`sandbox_init` 已
  deprecated，所以 `.github/workflows/sandbox-desktop.yml` 每次都在 macOS 14 與 15 runner 上重跑。
- **不留孤兒**：Host 死亡時 helper 的 stdin 收到 end of stream，但原本 driver 只在當前 invocation 結束後才離開，
  正在跑無窮迴圈的 hook 要等到時間上限（實測 29.8 秒）。讀取執行緒在 end of stream 或協定違規時，現在會取消正在執行
  的 invocation（`driver_test` 0–2 ms；實際 helper 11–88 ms）。Android 的 driver 同樣受益。

### 19.2 Host 端

- `DesktopSandboxTransport`（由測試程式移入 desktopMain）：每個 package 一個 helper 程序，環境變數清空、stderr 丟棄，
  `close()` 以 `destroyForcibly` 結束程序，同時解除阻塞中的讀寫。生命週期沿用 §18.2 的 `SandboxLifecycle`，上限 8。
- `SandboxHelperLocator`：jpackage 安裝的 resources 沒有執行權限，所以每次執行把 helper 複製到只有使用者能進入的
  暫存目錄（目錄 0700、檔案 0500）。**打包後的 App 只使用自己 bundle 內的 helper**：`SHINSOU_SANDBOX_BIN` 與工作目錄
  下的建置輸出只在開發執行（沒有 resources 目錄）時生效，否則能控制啟動環境的人可以換上沒有鎖定的執行檔
  （Codex 複審，嚴重）。找不到 helper 或不是 macOS／Windows 時一律拋出例外，不會退回未隔離的執行方式。
- `desktopTest` 把 `sandbox-core/build/release/shinsou-sandbox` 列為輸入：實作時一度因 helper 不是 task 輸入，Gradle
  沿用了舊的測試結果，時區問題晚了一步才被發現。

### 19.3 打包與簽章

- `buildMacOsSandboxHelper` 以 CMake 依建置主機架構編譯（deployment target 12.0，與 WKWebView helper 相同），
  複製到 App resources 前以 hardened runtime 簽章：有設定簽章身分時用該身分（Developer ID 可直接公證），否則 ad-hoc。
  helper 不帶任何 entitlement。CI 檢查 hardened runtime 旗標、沒有 entitlement、App 整體 `codesign --verify --deep
  --strict`；release workflow 另確認 DMG 內的 App 有通過驗證的 helper。
- 實測 hardened runtime 不影響 `sandbox_init`。

### 19.4 Windows helper：LPAC＋Job object

- **自己當 launcher**：JVM 的 `ProcessBuilder` 不能建立 AppContainer 程序，所以 `shinsou-sandbox.exe` 第一次執行時只做
  launcher：
  - 建立（或取得）`Shinsou.Sandbox.<key>` AppContainer profile。key 由 Host 以 `--package-key` 傳入，是
    SHA-256(package id) 的前 16 bytes。
  - 授予 `ALL RESTRICTED APPLICATION PACKAGES` 對 helper 所在私有目錄的 read＋execute。
  - 建立 Job：記憶體 384 MiB（核心自己的預算之上的保險）、`ACTIVE_PROCESS = 1`、`KILL_ON_JOB_CLOSE`、
    `DIE_ON_UNHANDLED_EXCEPTION`、UI restrictions、CPU hard cap 一顆處理器。
  - 以 LPAC＋Job（`PROC_THREAD_ATTRIBUTE_JOB_LIST`，建立時就在 Job 內）＋process mitigation 重新啟動自己，只繼承
    stdin／stdout，工作目錄設為 helper 自己的目錄，然後等待它結束並回傳結束碼。
  - launcher 不讀任何輸入。`userenv` 以 `LoadLibrary` 載入，所以被隔離的那一份只匯入 KERNEL32 與 ADVAPI32
    （CI 檢查匯入表）。
- **被隔離的那一份不相信參數**：讀 frame 前檢查以下各項，任何一項不符就以 `SSC_DRIVER_LOCKDOWN` 結束。
  - token 是 AppContainer，沒有任何 capability。
  - token 帶 `WIN://NOALLAPPPKG` 屬性，代表是 LPAC。
  - 在 Job 內，且 Job 限制齊全。

  CI 實測：拿掉 LPAC opt-out 時回報「not a Low Privilege AppContainer」，拿掉 Job 時回報「Job limits missing」。原本想以
  `AccessCheck` 驗證不在 `ALL APPLICATION PACKAGES` 內，但在 LPAC token 上連對照組（授予自己的 AppContainer SID）也被拒，
  所以改用 token 屬性。
- **helper 目錄的授權對象**：read＋execute 授予 `ALL RESTRICTED APPLICATION PACKAGES`，而不是各 package 的 AppContainer SID
  （Codex 複審提出，決定維持）。
  - 理由：所有 package 共用同一份 helper，就算逐一授予各 package 的 SID，每個沙箱仍讀得到同一個目錄，只是讓 ACL 隨套件數增長。
  - 目錄內只有公開的 helper 執行檔，沒有任何 package 的資料。
  - 代價：本機其他 LPAC 程序也讀得到這個目錄。
  - 因此 launcher 在授權前確認目錄只有 helper 本身，否則以「helper directory holds other files」拒絕啟動。這項
    檢查由隔離探測（helper 旁放一個檔案）與 `onWindowsTheHelperRefusesADirectoryHoldingOtherFiles` 驗證；開發與測試
    時也一律從只放 helper 的私有副本執行。
- **每個 package 一個 AppContainer**：同一個 AppContainer 的程序可以互相開啟。隔離探測另外啟動一個不同 package 的
  沙箱，被測的沙箱 `OpenProcess` 它被拒（5）。
- **S3 實作時發現**：
  - child process policy 讓子程序以 `STATUS_DLL_INIT_FAILED`（0xC0000142）結束，所以不用（§4.4）。
  - 子程序繼承 launcher 的工作目錄，所以改為 helper 自己的目錄。
  - repo 的 `* text=auto` 讓 quickjs patch 在 Windows checkout 時變成 CRLF，已在 `.gitattributes` 固定為 LF。
  - `memmem` 不在 Windows C runtime，改為可攜的實作。
  - Gradle 在 Windows 上找到的 `bash` 可能是 WSL 的啟動器，改用 Git for Windows 的 bash。
- **隔離探測**（Windows Server 2022 與 2025 結果一致）：不隔離時 15 項全部成功。隔離後全部被擋：

  | 探測項目 | 結果 |
  |---|---|
  | TCP connect、UDP sendto、bind | 10107：LPAC 下 Winsock 無法初始化 |
  | 讀使用者檔案；在使用者目錄、helper 目錄、`C:\Windows\Temp` 建檔；列 `C:\Users`；讀 HKCU Explorer 機碼 | 5 |
  | `CreateProcess(cmd.exe)` | 1816：Job 的程序數上限 |
  | 讀 Host 的環境變數 | 拿不到 |
  | `OpenProcess`（Host、另一個 package 的沙箱） | 5 |
  | 使用繼承的 handle | strict handle checks 以 `STATUS_INVALID_HANDLE` 結束該程序 |
  | commit 1 GiB 記憶體 | 1455 |

  執行緒與一般記憶體配置仍可用。
- **Host 端**：在 Windows 上，`DesktopSandboxTransport` 只把路徑類環境變數交給 launcher，launcher 再過濾一次。
  `close()` 結束 launcher，Job 隨之關閉，被隔離的那一份跟著結束。新測試 `closingAHelperLeavesNoProcessBehind`
  確認 launcher 與那一份都不存在。
- **CI 實測**：
  - Host 的 stdin 關閉後 10–11 ms 內 helper 結束，即使正在跑無窮迴圈。
  - 取消需 1–2 ms。
  - hook 往返中位數 435–689 µs（launcher 不轉送資料，pipe 直接交給被隔離的那一份）。
  - 桌面 sandbox 測試全部通過，沒有任何 SKIPPED。
- **打包**：
  - `buildWindowsSandboxHelper` 以 MSYS2 UCRT64 的 MinGW-w64 靜態連結編譯（sandbox-core 需要 pthreads 與 POSIX I/O）。
  - helper 放進 App resources 的 `windows/`；Windows CI 與 release workflow 都檢查 App image 內有 `shinsou-sandbox.exe`。
  - Authenticode 簽章與 SmartScreen 仍隨 §11 的整體 Windows 簽章處理。
- **接進 App 後的補強**（Codex 複審，第 20.3 節的接入階段）：
  - 暫存副本所在目錄改為只有擁有者能存取的 ACL。
  - launcher 以「只允許共用讀取」的方式鎖住自己的執行檔：從檢查目錄開始，直到被隔離的那一份建立完成，期間沒有人能
    改寫、改名或刪除它。
  - `CreateProcess` 前再檢查一次目錄只有 helper。
  - 子程序加上「DLL 優先從 System32 載入」的 mitigation。
  - launcher 的所有失敗路徑都經過同一個清理出口；建立後若失敗，會先確認子程序已經結束才返回。
  - `windows.yml` 與 release 不再只檢查 helper 是否存在，而是以私有副本實際啟動打包好的 helper（空輸入），必須以 0
    結束，代表 LPAC＋Job 建立並通過自我驗證。
- **仍待**：Windows 11 一般使用者帳號上的實機重跑（runner 都是管理員帳號）。

## 20. S5 進度與實作決策（發佈者信任）

| 項目 | 狀態 | 位置 |
|---|---|---|
| publisher-keyed trust store、URL → 發佈者對應、鏡像 | ✅ | `commonMain/…/plugin/PublisherTrust.kt`（`PublisherTrustStore`） |
| 兩種信任模式（D3）與 `verification` 欄位 | ✅ | 同上 |
| key rotation（雙簽）、金鑰自我撤銷、遺失後明確替換 | ✅ | 同上 |
| artifact 撤回與 `expiresAt`（過期只警示，D8） | ✅ | `PublisherRevocationStore` |
| 本機封鎖名單（D5） | ✅ | `LocalPluginBlocklist` |
| `ExecutionTierResolver`（§10.1） | ✅ 判斷邏輯與測試；尚未接到 `PluginManager` 的執行路徑 | `ExecutionTierResolver` |
| 指紋顯示格式、`shinsou://add-repository` 解析 | ✅ 解析與格式；UI 與 deep link 註冊尚未做 | `PublisherFingerprint`、`AddRepositoryLink` |
| 驗收測試（§12 S5） | ✅ `PublisherTrustTest` 13 項；`RepositoryTrustTest` 21 項不變 | `commonTest/…/plugin/PublisherTrustTest.kt` |

### 20.1 信任記錄

- **發佈者 ID**：發佈者以第一把金鑰的指紋（`sha256:` 加 base64url）作為 `publisherId`，rotation 後不變。
  遺失金鑰後經 `replaceAfterExplicitApproval` 換上的新金鑰視為新的發佈者：
  - 舊記錄標為 `replacedBy`，狀態 `KEY_REPLACED`。
  - 舊發佈者的已安裝套件轉為 inert，需要重新授權（§8.3）。
- **URL 對應**：一個 URL 對應一個發佈者；同一把金鑰從另一個 URL 出現時直接沿用，不重新 TOFU。
  - 同一個 URL 出示另一把金鑰，一律 `KeySubstitution`。
  - 已退休的金鑰從鏡像出現，也算替換。
- **狀態**由記錄與當下的全域模式推導，不另外遷移：
  - `TRUSTED`。
  - `PENDING_FINGERPRINT_MATCH`：TOFU 信任，且全域或該發佈者適用模式 (b)。
  - `KEY_REVOKED`。
  - `KEY_REPLACED`。

  所以從 (a) 切到 (b) 時，TOFU 發佈者立即顯示為「待比對」：已安裝套件繼續執行並帶警示，新安裝與更新被擋，
  比對完整指紋後恢復。
- **指紋**：
  - 比對一律用完整值，接受 `sha256:` 形式或 64 位十六進位（忽略空白、冒號、連字號與大小寫）。
  - UI 只顯示前 128 bit，4 個字元一組，這個顯示值不能拿來比對。
- 所有狀態都存在 plugin key-value store。它屬於裝置本機，不進可攜備份或同步（§8.4）。

### 20.2 簽章文件

- 沿用 `shinsou-signed-envelope-v1`，新增三種 payload type：
  - `publisher-key-rotation`：payload 為 `publisherId`、`newPublicKey`、`newKeyFingerprint`、`effectiveSequence`、
    `newKeySignature`。
  - `publisher-revocations`：payload 為 `publisherId`、`revokedArtifacts`（SHA-256 hex）、`expiresAt`（Unix 秒）。
  - `publisher-key-revoked`：payload 為 `publisherId`、`revokedKeyFingerprint`。

  欄位必須剛好是這些，多或少都拒絕。
- **Rotation 雙簽**：
  - envelope 由目前的金鑰簽署。
  - 新金鑰另外簽署網域分隔的聲明 `dev.shinsou.publisher.rotation.v1\0` 加 canonical JSON，內容為 `publisherId`、
    舊／新指紋、新公鑰與 `effectiveSequence`，表示同意接手。
  - `effectiveSequence` 必須大於上次。
  - 新金鑰不能已經屬於任何發佈者。
- **金鑰自我撤銷**由被撤銷的那把金鑰簽署：
  - 撤銷目前金鑰：發佈者 `KEY_REVOKED`，全部 inert，需要重新信任新金鑰。
  - 撤銷已退休金鑰：只記錄，不影響發佈者。
- **Watermark 改以發佈者為單位**：`RepositoryTrustDecision.RequirePinnedSignature` 新增 `stateScope`。發佈者信任的
  repository 用 `publisher:<id>` 作為重播、equivocation 與 artifact 降版的 watermark key，所以一個鏡像不能重播另一個鏡像
  已經取代的文件。
  - 簽章訊息仍綁定取得文件的 URL（沿用 v1 格式）。
  - 由於簽章綁定 URL，發佈者要對每個鏡像各簽一次，payload 相同。
  - URL pin 的 repository 不變，仍以 URL 為 scope。
- **撤回清單**：新的 `publisher-revocations` 整份取代舊的；sequence 只能前進，所以不能重播舊清單來取消撤回。
- **到期（D8）**：過了 `expiresAt` 之後，該發佈者不能再安裝或更新任何套件；已安裝的套件不論過期多久都繼續執行，
  只帶 `PUBLISHER_DOCUMENTS_EXPIRED` 警示。
- **新鮮度必須證明**（Codex 複審）：新安裝與更新一律要求該發佈者已有驗證過、未過期的 `publisher-revocations`（可以是
  空清單）。否則 repository 只要永遠不提供這份文件，凍結攻擊的到期防護就永遠不會啟動。所以每個發佈者都必須發佈並定期
  重簽撤回清單；已安裝套件不受影響（D8）。

### 20.3 Tier 判斷與接入

- `ExecutionTierResolver.resolve` 依 §10.1 的順序判斷，只用本機狀態：
  1. 封鎖名單最先檢查，連官方套件也能封鎖。
  2. 官方：catalog 完全符合，且發佈者是內建官方金鑰（官方 repository 仍未簽章期間，暫時也接受來自標準官方 URL 的
     catalog 套件），回傳 `ReviewedInProcess`。
  3. 未簽章：只有開發者模式加本機來源會得到開發中的 `Sandboxed`，其餘 inert。
  4. 發佈者未信任、金鑰被替換或撤銷、artifact 被撤回、使用者未授權：inert，並帶原因。
  5. 其餘回傳 `Sandboxed`，可能附帶「待比對」或「已過期」警示。

  另外有 `requireInstallable` 作為新安裝與更新的關卡：比 `resolve` 更嚴格，待比對或已過期的發佈者都不能安裝新東西。
- `ShinsouComposition` 已組裝 `PublisherTrustStore`、`PublisherRevocationStore`、`LocalPluginBlocklist`、
  `ExecutionTierResolver`，repository client 改用 `PublisherRepositoryTrustPolicy`：
  - 對應到發佈者的 URL 要求該發佈者目前的金鑰。
  - 被撤銷、被替換或被封鎖的發佈者在送出任何請求前就被拒絕。
  - 其他 URL 沿用原本的 URL pin 與官方未簽章例外。

  因為目前沒有任何流程會寫入發佈者記錄，正式行為不變。
- **接進 App（S5 之後，接入階段）**：
  - `PluginManager` 安裝時，用 client 正規化後的 repository URL 查詢發佈者，記錄在
    `InstalledPluginMetadata.publisherId`，並先通過 `requireInstallable`。
  - 每次建立 runtime 前（安裝、授權、啟動 rehydrate、重新啟用）都經過 `requireExecutionTier`。結果為 inert 時丟出
    `PluginExecutionInertException`，這是可逆的：使用者的授權保留，解除封鎖或信任恢復後就能再執行。只有 UNSIGNED 會
    與舊的「未審查」同樣處理，也就是撤銷授權。
  - 用不在發佈者對應中的 URL 寫法安裝時，`publisherId` 為 null，只會被判為 UNSIGNED，永遠不會執行。
  - `TieredScriptPluginRuntimeFactory`：
    - 帶有 in-process provenance 的（官方 catalog、ShuYue）交給 Rhino／JSC。
    - 其餘交給沙箱。
    - 平台沒有沙箱時一律拒絕，絕不交給 in-process 引擎。
  - 桌面版 `Main.kt` 用 `desktopSandboxRuntimeFactory`，在 macOS 與 Windows 提供 OS 隔離的 helper。
  - **新增 repository**：`inspectRepositoryKey` 走嚴格的 transport，先取得 `repo.json`，確認它能以自己出示的金鑰驗證，
    並顯示名稱與指紋。使用者確認後，以畫面上顯示的同一把金鑰呼叫 `trustRepository`，不再從網路重讀，然後才加入
    repository。
  - **「套件安全」**對話框：模式切換、發佈者清單（比對指紋、封鎖）、本機封鎖名單；已安裝的套件列可以直接封鎖。
    任何變更都在 `NonCancellable` 中重新評估所有套件，即使操作在寫入後被取消，也會立即生效。
  - **重新整理**時先同步各發佈者的 rotation、撤回清單與金鑰撤銷；只要儲存的狀態有變，就在 `finally` 裡重新評估。
  - Codex 共複審 6 輪，最後一輪 approve。修正的問題依序為：
    - 取消時的重新評估。
    - 本機封鎖的取消。
    - 必須有撤回清單才能安裝。
    - URL 正規化。
    - 沒有沙箱時 fail closed。
- **尚未完成**：
  - `shinsou://` deep link 在各平台的註冊。
  - ShuYue reviewed 路徑尚未經過封鎖名單。
  - S6 的權限上限與 `COOKIE_READ`：`policyFor` 目前全部回傳預設值。

## 21. S4 進度與實作決策（iOS WASM 沙箱）

| 項目 | 狀態 | 位置 |
|---|---|---|
| sandbox-core 編成 wasm32 guest，只匯入 4 個 Host 函式 | ✅ 建置時檢查 import | `sandbox-core/wasm/` |
| driver 單執行緒模式（WASM 沒有執行緒） | ✅ 原生與 WAMR 兩種方式都跑整套 driver 測試 | `sandbox-core/src/ssc_driver.c`（`SSC_DRIVER_SINGLE_THREAD`） |
| WAMR Host（iOS 靜態庫，每個 package 一個 instance＋執行緒） | ✅ macOS 上以 ctest 驗證，模擬器上以 Kotlin 驗證 | `sandbox-core/ios/`、`IosSandboxTransport.kt` |
| App 接入（JSC＋WASM 沙箱分派） | ✅ | `MainViewController.kt` |
| 實機測試 | ⏳ 交由使用者接手（S4 目標依使用者安排） | — |

### 21.1 設計

- **同一套協定**：iOS 不另做一套 bridge，而是把桌面版與 Android 用的 SandboxProtocol v1 driver 編進 WASM。Host 端沿用
  `SandboxSession`／`SandboxLifecycle`／`SandboxRuntimeFactory`，只多一個 `IosSandboxTransport`。這樣與 S0 原型的
  `host_bridge` frame 不同。
- **Guest 的 import 只有四個**：
  - `host_read(buf, len, timeout_ms)`：帶逾時的讀取。
  - `host_write(buf, len)`。
  - `host_now_ms(clock)`。
  - `host_timezone_offset(time_ms)`。

  wasi-libc 會碰到的 WASI 函式都由 guest 自己以 stub 定義；`check-imports.sh` 在每次建置時確認 import 剛好是這四個。
- **單執行緒 driver**：
  - 沒有 reader 執行緒，等待 frame 時由 driver 自己以 `read_wait` 拉取。
  - hook 執行中，QuickJS 的 interrupt handler 每 20 ms 透過 `cb_now` 非阻塞地讀一次輸入，所以 Cancel 與 Host 消失仍會
    立即生效（WAMR 上 17 ms／16 ms）。
  - 這是編譯期選項；桌面版與 Android 的原生 driver 不受影響，但兩種模式都跑同一套 `driver_test`。
- **時區**：wasi-libc 沒有時區。`patches/quickjs-wasi-timezone.patch` 在 `__wasi__` 時改由 Host 以裝置的 `localtime_r`
  回答，本地日期與 JavaScriptCore 一致（模擬器測試比對 `NSTimeZone`）。
- **WAMR**：
  - 2.4.5，由 `fetch-deps.sh` 釘選。
  - 只啟用 fast interpreter；開啟 THREAD_MGR，讓執行中的 instance 能被 terminate。
  - 不含 WASI 與 libc builtins，並關閉硬體邊界檢查（iOS 拒絕；改用軟體檢查）。
  - Host import 的 buffer 參數用 `*~` 簽名，由 WAMR 驗證位址範圍。
  - linear memory 上限 160 MiB（模組宣告，instance 只能調低）。
  - interpreter stack 8 MiB，執行緒 stack 8 MiB。
- **Host 端的界線**：
  - guest 尚未取走的輸入在 App 記憶體裡，不受 linear memory 限制，所以設 48 MiB 上限。超過時 session 直接失敗：
    輸入關閉、待處理資料丟棄、guest 結束（Codex 複審）。
  - 輸出上限 32 MiB。
  - 關閉時先送 EOF，1 秒內未結束就 `wasm_runtime_terminate`。
- **Kotlin 生命週期**：`IosSandboxTransport` 用單一 `AtomicInt` 記錄使用中的呼叫數與 CLOSED 旗標，close 自己也算一個
  使用者；只有「已關閉且沒有人在用」時，才由 CAS 轉成 FREED 並釋放 native session。Codex 兩輪複審抓到兩個釋放時序的
  問題，修正後 approve。
- **EOF 之後不再開始新工作**（所有平台）：WASM 壓力測試發現，EOF 前已排進佇列的 Invoke 會在 EOF 之後才開始，而 EOF
  只取消了當時正在執行的呼叫，所以它會一直跑到自己的時間上限。現在 driver 在串流結束後不再開始任何 Init／Invoke。
- **建置**：
  - Gradle `buildIosSandboxGuest` 執行 `wasm/build-guest.sh`。
  - `buildIosSandboxHost{Iphoneos,Iphonesimulator}` 以 CMake 搭配 iOS SDK 建 `libshinsou_wasm_host.a`，guest 以 C 陣列
    嵌入。
  - cinterop `sandboxWasm` 連結這個靜態庫。
  - 新增 `iosTest` source set。
  - CI：`.github/workflows/sandbox-ios.yml`。

### 21.2 結果（macOS／模擬器）

- WAMR 上的整套 driver 測試全過：Cancel 17 ms、Host 消失後 16 ms 內結束、flood 與協定違規都被擋下。
- 輸入上限在約 47 MiB 時拒絕，guest 在 15–17 ms 內結束（連續 5 次）。
- 模擬器（iPhone 17 Pro、iOS 27）：
  - 冷啟動 47–49 ms，DOM 解析與 bridge 正常。
  - 時間上限 2000 ms；時區與裝置一致。
  - close 與 send／receive 競爭 40 輪，沒有 crash。
- guest 大小 1.69 MB。

## 不接受的縮小範圍

- 讓第三方套件在 Rhino 或 JavaScriptCore in-process 執行，只加 instruction 計數或事後記憶體輪詢。
- 以 repository 簽章、使用者核准或 digest 相符代替隔離執行。
- 在 iOS 以原生 QuickJS in-process 當預設，卻宣稱與 Android／Desktop 同等隔離。
- 讓套件透過 payload 自報身分、tier 或資源限制。
- 為了方便把 Host 的 Cookie、密碼或其他套件的資料以「唯讀」方式暴露給第三方套件。
- 以線上簽章的審核清單代替開放發佈（已否決）。
- 為了保留 Android API 26–28 支援，讓多個第三方套件共用同一個 isolated process（D2 已否決）。
