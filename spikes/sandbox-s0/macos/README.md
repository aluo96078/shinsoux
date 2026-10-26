# ADR-0002 S0：macOS helper 原型（`shinsou-sandbox`）

`shinsou-sandbox` 是 ADR-0002 §4.4 的桌面原生 helper：以 `shinsou-sandbox-core`（QuickJS）在子程序內執行第三方套件，
Host 只透過繼承的匿名 stdin／stdout pipe 溝通。本目錄只有 macOS 原型；核心、根 CMake、scripts 皆未修改。

## 建置與執行

需求：macOS、cmake ≥ 3.20、clang、python3、`codesign`（ad-hoc 簽章即可）。

```sh
spikes/sandbox-s0/macos/run-all.sh      # 乾淨 build + 全部測試 + 量測（約 1 分鐘）
```

或手動：

```sh
cmake -S spikes/sandbox-s0/macos -B spikes/sandbox-s0/build/macos -DCMAKE_BUILD_TYPE=Release
cmake --build spikes/sandbox-s0/build/macos
python3 -I spikes/sandbox-s0/macos/tests/run_probe.py   spikes/sandbox-s0/build/macos   # 隔離探測
python3 -I spikes/sandbox-s0/macos/tests/test_helper.py spikes/sandbox-s0/build/macos   # host 情境測試
python3 -I spikes/sandbox-s0/macos/tests/bench.py       spikes/sandbox-s0/build/macos   # 量測
```

產物（`build/macos/`）：

| 檔案 | 說明 |
|---|---|
| `shinsou-sandbox` | helper，預設啟動後套用 Seatbelt deny-all（`--sandbox=none` 可關閉） |
| `shinsou-sandbox-appsandbox` | 同一份程式，ad-hoc 簽章並帶 `com.apple.security.app-sandbox`（無 `network.client`），搭配 `--sandbox=none` |
| `isolation-probe`、`isolation-probe-appsandbox` | 隔離探測程式（同樣的啟動強化 + 沙箱，再嘗試禁止的操作） |
| `bootstrap.js` | 由 `scripts/extract-bootstrap.py` 產生（CMake 自動執行） |

原始碼：`src/main.c`（協定與主迴圈）、`src/mini_json.c`（有界 JSON 掃描器，不建 DOM）、`src/sandbox_apply.c`（`sandbox_init`）、
`src/probe.c`、`entitlements/`。測試：`tests/host.py`（Python host）、`test_helper.py`、`run_probe.py`、`bench.py`。

## 協定（S0 簡化版）

Frame = `u32 big-endian 長度` + UTF-8 JSON 物件，單一 frame ≤ 1 MiB（長度 0、超限、JSON 不合法 → helper 以 exit 1 結束，fail closed）。
所有 frame 以 `"t"` 區分；ID 皆為無號整數。

| 方向 | Frame | 欄位 |
|---|---|---|
| H→S | `Init` | `v:1`、`bootstrap`、`script`、`limits{heapLimitBytes, stackLimitBytes, initTimeoutMs, invokeTimeoutMs, maxBridgeCalls, maxAsyncInflight, maxAsyncQueue, maxResultBytes, domMaxNodes, domMaxDepth}`；版本不是 1 → `Failure{code:BAD_PROTOCOL_VERSION}` |
| S→H | `InitOk{v}`／`Failure{callId:0,code}` | ADR 未列 `InitOk`，S0 新增（需要明確的 Init 回應） |
| H→S | `Invoke` | `callId, method, args`（JSON 陣列） |
| H→S | `Cancel` | `callId`；由獨立讀取執行緒處理，立即 `ssc_cancel` |
| S→H | `BridgeCall` | `callId, seq, op, args` → helper 阻塞等 `BridgeResult` |
| H→S | `BridgeResult` | `seq, ok, value`（`ok:false` 時 JS 端得到 `null`，同舊 bridge 契約） |
| S→H | `AsyncBridgeCall` | `callId, opId, op:"net.fetch", args`（core 的 request JSON） |
| H→S | `AsyncBridgeResult` | `opId, ok, value`；helper 在 `async_wait` 內取出並呼叫 `ssc_deliver_async` |
| H→S／S→H | `AsyncCancel` | `opId`。H→S：host 放棄該 op，helper 以 `ok=false` reject；S→H：invocation 結束／取消時通知 host 丟棄（core 的 `async_cancel` callback） |
| S→H | `Result{callId,value}`／`Failure{callId,code}` | `code` 為 `ssc_status_name`（`LIMIT_TIME`、`CANCELLED` …），不含引擎訊息 |
| S→H | `Log{callId,message}` | `bridge.log`／`console.*` 的 args JSON，helper 本地處理（不往返），單筆 ≤ 1 KiB、每 invocation ≤ 256 筆／256 KiB |

行為細節：

- 執行緒：主執行緒擁有 runtime、是唯一的 stdout 寫入者；讀取執行緒是唯一的 stdin 讀取者，只做三件事：`Cancel` → `ssc_cancel`、其餘 frame 入佇列（上限 8 MiB，超過即 exit 1）、EOF → `_exit(0)`（即使主執行緒正卡在無窮迴圈）。
- 同步 bridge 等待、`async_wait` 都會被 Cancel／invocation deadline 喚醒；host 永不回覆 `BridgeResult` 時，helper 在 `invokeTimeoutMs` 後放棄（回傳 `null`，interrupt handler 隨後以 `LIMIT_TIME` 結束）。
- Cancel 與完成競爭：若 Cancel 在 `ssc_invoke` 回傳 `OK` 前抵達，仍回報 `CANCELLED`。Cancel 早於 Invoke 抵達（idle 時）的 callId 會被記住（環形 16 筆）。
- invocation 結束後，佇列中殘留的 `BridgeResult`／`AsyncBridgeResult` 一律丟棄，避免跨 invocation 洩漏。
- 結果受單 frame 1 MiB 限制：helper 把 `maxResultBytes` 夾到 1 MiB − 4 KiB；S0 未實作 `chunk` frame。
- 啟動強化（在讀第一個 frame 前）：清空 `environ`、`RLIMIT_CORE=0`、關閉 fd 3–255、忽略 SIGPIPE、套用沙箱。stderr 只輸出最多 32 行、每行 ≤ 256 bytes 的固定診斷訊息，不含腳本內容。

## 兩種沙箱方式與結論

### A. `sandbox_init`（Seatbelt，自訂 deny-all profile）

helper 自行呼叫 `sandbox_init(profile, 0, &err)`：`deny default` + 明確拒絕 `network*`、`file*`、`process*`、`mach*`、`ipc*`、`signal`，只允許 `sysctl-read`。
stdin／stdout／stderr 是啟動前就開好的 fd，Seatbelt 在 open 時才檢查，所以照常讀寫；執行緒、匿名 `mmap` 不受影響。

- **主 App 未啟用 App Sandbox 時 helper 能否獨立套用沙箱：能。** helper 由未沙箱化的 parent 啟動（本測試的 Python host 即是），不需特殊簽章、不需 entitlement、不需 bundle。
- 限制：API 已 deprecated（標頭有警告，程式內以 `extern` 宣告）；套用後不可撤銷；profile 屬 SBPL 私有語法，未來 macOS 可能變動，需每個 macOS 大版本跑本探測測試。
- 成本：冷啟動約 +4 ms、RSS 約 +3 MiB（見量測）。

### B. App Sandbox entitlement（ad-hoc 簽章 + `com.apple.security.app-sandbox`，無 `network.client`）

- 要點：**必須有內嵌 `Info.plist` 且含 `CFBundleIdentifier`**（本專案以 `-sectcreate __TEXT __info_plist` 嵌入）。沒有時，`libsecinit` 在載入階段 `EXC_BREAKPOINT`／SIGTRAP，程序還沒到 `main` 就死了。
- **主 App 未啟用 App Sandbox 時 helper 也能獨立套用**：沙箱由 helper 自己的簽章 entitlement 在 exec 時啟動，不看 parent。
- 副作用：首次執行會在 `~/Library/Containers/<CFBundleIdentifier>/` 建立 container 目錄（測試後留下 `dev.shinsou.*` 三個 container，受 `containermanagerd` 保護，`rm` 會被拒絕，需由使用者在 Finder 或重新開機後清理）。每個 package 若共用同一 helper 就共用同一 container。
- **隔離強度明顯較弱**（見下表）：允許讀系統路徑（如 `/etc/hosts`）、**允許 `fork`／`posix_spawn`／`execv`**（子程序繼承沙箱，但仍是額外的程序，且 ADR 要求 helper 不能開子程序）。無法再疊加 `sandbox_init`（回傳 `EPERM`），所以 B 不能補上 A 的規則。
- 優點：Apple 支援的路徑，公證／Mac App Store 友善；`sandbox_init` 被移除時的備案。

### 結論

1. **S3 採方式 A（`sandbox_init` deny-all）為主**：隔離項目全數通過（見下表），且不依賴簽章或 container。
2. 方式 B 只當備案；若要用，需額外以其他機制補「禁止 fork／exec」（目前找不到；可用 `RLIMIT_NPROC` 但對 root 外的同 uid 並不可靠）。
3. ADR §4.4「helper 單獨簽章、entitlements 只有 app-sandbox」不足以滿足「不開子程序」要求，建議改寫（見最後一節）。
4. 兩者都不限制 CPU／記憶體；記憶體另靠 QuickJS 限制與 Host 端 RSS 監控。

## 隔離驗證結果

`tests/run_probe.py`：Host 開一個本機 TCP listener（connect 若沒被擋就會成功），設定 `SHINSOU_SECRET_TOKEN`，執行探測程式。
「Control」是不套沙箱（證明探測有效）。

| 操作（沙箱生效後、在 helper 啟動序列內嘗試） | Control | A. Seatbelt | B. App Sandbox |
|---|---|---|---|
| `socket(AF_INET)` 建立 | 成功 | 成功（僅資訊；未連線的 socket 無害） | 成功（同左） |
| `connect(127.0.0.1)` TCP | 成功 | **失敗** EPERM | **失敗** EPERM |
| UDP `sendto` | 成功 | **失敗** | **失敗** |
| `bind` | 成功 | **失敗** | **失敗** |
| `connect(AF_UNIX /var/run/syslog)` | 成功 | **失敗** | **失敗** |
| `open()` 使用者家目錄檔案 | 成功 | **失敗** | **失敗** |
| `open(/etc/hosts)` | 成功 | **失敗** | **成功（弱點）** |
| 在 `/tmp` `open(O_CREAT\|O_WRONLY)` | 成功 | **失敗** | **失敗** |
| `opendir(/Users)` | 成功 | **失敗** | **失敗** |
| `fork()` | 成功 | **失敗** | **成功（弱點）** |
| `posix_spawn(/usr/bin/true)` | 成功 | **失敗** | **成功（弱點）** |
| `vfork + execv` | 成功 | **失敗** | **成功（弱點）** |
| `getenv(SHINSOU_SECRET_TOKEN)` | 失敗（啟動時已清空 environ） | **失敗** | **失敗** |
| `kill(parent, 0)` | 成功 | **失敗** | **失敗** |
| 執行緒、匿名 `mmap`（必須可用） | OK | OK | OK |

`tests/test_helper.py` 的生命週期項目（Seatbelt、無沙箱、App Sandbox 三種模式皆通過）：

| 項目 | 結果 |
|---|---|
| helper 被 `SIGKILL` | Host 讀到 EOF，Host 照常運作，可再啟動新 helper |
| Host 關閉 stdin（閒置） | helper 於 ~2 ms 內 exit 0 |
| Host 關閉 stdin（helper 正在無窮迴圈） | 讀取執行緒 `_exit`，~2 ms 內結束 |
| Host 被 `SIGKILL`（helper 正在無窮迴圈） | helper 隨 pipe 關閉結束，**無孤兒程序** |
| 超大長度／長度 0／非法 JSON frame | helper exit 1 |
| 未知協定版本 | `Failure BAD_PROTOCOL_VERSION`（fail closed） |

## Host 情境測試（`tests/test_helper.py`）

與 `core/test/core_test.c` 同類情境，改走真實 pipe 協定；**每個限制情境之後都立刻對同一個 helper 再跑一次 `ok`，皆成功**，
即 helper 在每種限制後可繼續服務，不需重啟。

| 情境 | 結果 |
|---|---|
| 同步 bridge + DOM（Jsoup 相容層） | Result，內容正確；Unicode／控制字元 args 往返無損 |
| 禁用全域（`std`、`os`、`require`、`process`、`fetch`、`XMLHttpRequest`） | 全為 `undefined` |
| 無窮迴圈／吞掉 interrupt 的 try-catch／ReDoS／OOM 後空轉 | `LIMIT_TIME`（400 ms 限制，~401 ms 結束） |
| 無限遞迴 | `LIMIT_STACK` |
| 記憶體炸彈 | `LIMIT_MEMORY`（~30 ms） |
| bridge 洪水 | `LIMIT_BRIDGE` |
| 2 MiB 結果 | `LIMIT_RESULT` |
| 不存在的 hook | `NO_SUCH_HOOK` |
| 10 個並行 `bridge.net.fetch`（host 每個延遲 100 ms） | Result；在途峰值 6（符合上限）；~220 ms 完成（序列需 ≥ 1 s） |
| 71 個 fetch 超過佇列 | 恰 1 個 `ASYNC_QUEUE_FULL` |
| invocation 結束仍 pending 的 op | host 收到 `AsyncCancel`；晚到結果被丟棄，下一次 invocation 正常 |
| 同步與非同步交錯 | 正確 |
| 永不 settle 的 Promise | `PROMISE_STALLED` |
| Cancel：忙迴圈 | `CANCELLED`，~100 ms（Cancel 送出後立即） |
| Cancel：等待 fetch | `CANCELLED`，並對 pending op 送 `AsyncCancel` |
| Cancel：阻塞在同步 bridge（host 不回覆） | `CANCELLED` |
| 無關的過期 `Cancel` | 不影響後續 invocation |
| Host 不回覆 `BridgeCall` | 於 invoke deadline（400 ms）後放棄 |

## 量測

機器：Mac16,8（Apple M4 Pro）、24 GiB、macOS 27.0.1、Apple clang 21.0.0、Release（`-O3` 預設 CMake Release）。
Host 為 Python 3 `subprocess`（含 Python 端開銷，helper 本身更快）。

| 項目 | A. Seatbelt（建議） | B. App Sandbox | 無沙箱 |
|---|---|---|---|
| 冷啟動：spawn → Init（含沙箱與 bootstrap 24.5 KB）→ 第一個 Result，中位數（20 次） | **8.0 ms**（7.2–9.6） | 6.4 ms | 4.1 ms |
| 空 invoke 往返（2000 次）平均／p99 | **0.021 ms**／0.040 | 0.029／0.072 | 0.029／0.070 |
| 同步 bridge 呼叫往返（1000 次 × 10 輪）平均 | **0.022 ms** | 0.022 | 0.022 |
| RSS：剛啟動、尚未 Init | 4.8 MiB | 1.8 MiB | 1.7 MiB |
| RSS：載入 bootstrap 並暖機後 | **6.1 MiB** | 3.3 MiB | 3.2 MiB |
| RSS：2 萬次 bridge 呼叫後 | 6.2 MiB | 3.5 MiB | 3.4 MiB |
| 記憶體炸彈（heap 上限 64 MiB）後 RSS | 74.0 MiB | 72.0 MiB | 71.9 MiB |

（Seatbelt 與 App Sandbox 的 RSS 差約 3 MiB 來自 `libsandbox` 編譯 profile；數字依 `ps -o rss`，單次測量。）
完整輸出見 `run-all.sh`。與現況比較（Rhino／JSC）需另外量，S0 此處只量 helper。

結論：冷啟動 ~8 ms、單次往返 ~20 µs，完全不是瓶頸；ADR 的「閒置 60 秒回收、最多 8 個」可放寬，常駐 8 個 helper 約 50 MiB。
64 MiB heap 上限觸發後 RSS 約 74 MiB，低於 ADR 的 128 MiB 程序預算。

## 未解決問題

1. **Seatbelt profile 長期風險**：`sandbox_init` deprecated，需每個 macOS 大版本重跑探測；建議把 `run_probe.py` 放進 S3 CI（macOS runner）。
2. `socket()` 建立在兩種沙箱下都不被禁止（只是無法連線）；`deny system-socket` 無效。若要連建立都擋，需 seccomp 類機制，macOS 沒有。
3. 未在已公證、Hardened Runtime 的 Developer ID 簽章下驗證（S0 只用 ad-hoc）；Hardened Runtime 不允許 JIT，QuickJS 本身無 JIT，預期無影響。
4. Host 端 RSS 監控（`proc_pid_rusage`）與 watchdog kill 只示範用 `ps`，未實作 Kotlin／JVM 端。
5. CPU 時間上限（ADR §7，15 秒）未做：helper 只有 wall-clock 的 interrupt；可由 Host 讀 `proc_pid_rusage` 的 CPU time，或 helper 設 `RLIMIT_CPU`（SIGXCPU 會殺掉整個 helper）。
6. 協定未實作 `chunk` frame，也未用 CBOR；JSON 僅為 S0 簡化，frame 邊界與 dispatcher 行為與 ADR 一致。
7. 同一 helper 多 package 共用？本原型是一個 helper 一個 runtime（`Init` 只能一次），符合 ADR「一個 package 一個子程序」。

## 對 ADR 的建議修訂

- §4.4 macOS：**主方案改為 `sandbox_init` deny-all profile**（helper 啟動後、讀第一個 frame 前自行套用），App Sandbox entitlement 降為備案，並註明：僅 entitlement 不能阻止 `fork`／`exec`、可讀 `/etc` 等系統路徑，且無法再疊加 `sandbox_init`；App Sandbox 變體需內嵌含 `CFBundleIdentifier` 的 `Info.plist`，否則 SIGTRAP，且會在 `~/Library/Containers/` 留下 container。
- §4.4：「主 App 未啟用 App Sandbox 時 helper 能否獨立套用」→ 已驗證，兩種方式都可以。
- §4.2：補 `InitOk`（或定義 `Init` 的回應）；明確 `AsyncCancel` 為雙向；說明 `Log` 由沙箱本地處理（`bridge.log` 不往返）。
- §4.4：helper 啟動序列寫成規範：清空 environ、關閉多餘 fd、`RLIMIT_CORE=0`、忽略 SIGPIPE、套用沙箱、再讀 frame；EOF／協定違規立刻結束。
- §4.6：S0 量測顯示常駐成本很低（~6 MiB／helper），Desktop LRU 上限 8 可上調或改以記憶體預算決定。
- §13 隔離探測：沿用本目錄 `isolation-probe` 作為 S3 的 CI 測試；增列「socket() 建立不可禁止，以 connect／bind／sendto 失敗為準」。

## 對核心（`core/`）的修改需求（未自行修改）

1. `ssc_deliver_async()`：內部 `JS_ParseJSON(payload, len)` 要求 `payload[len] == 0`，但 API 帶 `len`，容易誤用。helper 目前自行複製並補 NUL；建議 core 內複製，或在標頭註明。（不處理時 payload 在 frame 內非 NUL 結尾會得到 `BAD_RESPONSE`——本原型實測遇到。）
2. Cancel 競爭：`ssc_invoke` 開頭（`begin()`）會清除 `cancel_requested`，在 `ssc_invoke` 開始之前或剛開始時抵達的 `ssc_cancel` 會被吃掉；helper 以 hook 內重新檢查補救，但純 JS 迴圈會漏接到 deadline。建議提供 `ssc_cancel_token`／invocation generation，或讓 `begin()` 不覆寫 cancel。
3. 完成後才抵達的 Cancel：`ssc_invoke` 回傳 `SSC_OK` 時不檢查旗標，helper 在外層補成 `CANCELLED`；建議由 core 統一。
4. `ssc_host.log` 目前沒有任何地方呼叫（`bridge.log` 走 `bridge_call("log")`）；helper 在 `bridge_call` 攔截 `log`。建議核心移除 callback 或讓 `bridge.log` 走它。
5. `async_cancel` callback 方向是「通知 host」，ADR 的 `AsyncCancel` 寫成 Host→沙箱；core 沒有「host 要求 reject 某 op」的 API，helper 以 `ssc_deliver_async(ok=0)` 代替（JS 端得到 `NETWORK_FAILED`）。若需要區分取消原因，可加 `ssc_reject_async(code)`。
