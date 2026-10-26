# S0：iOS WASM 原型（QuickJS-in-WASM，WAMR 直譯器）

ADR-0002 §4.5／§12 S0／§14 D1 的 iOS 原型，在 macOS（arm64）上用 WAMR 2.4.5 驗證。
只放在 `spikes/sandbox-s0/wasm/`，沒有修改 `core/`、根 `CMakeLists.txt`、`scripts/` 或 App 程式碼，沒有任何 git 操作。

## 結論摘要

| 問題 | 結果 |
|---|---|
| 核心能編成 `wasm32-wasi` 且不連結 WASI 嗎？ | 可以。最終 `.wasm` **只有 2 個 import**：`env.host_bridge`、`env.host_clock_ms`，沒有任何 `wasi_snapshot_preview1`。不需要 sjlj、不需要關閉 quickjs-ng 功能 |
| 惡意語料在 WASM 下行為一致嗎？ | 一致。無窮迴圈、吞掉 interrupt、catastrophic regex、深遞迴、記憶體炸彈、bridge 次數、過大結果、bridge flood 都得到與原生相同的穩定錯誤碼（fast／classic／wasm2c 皆通過） |
| 非同步 host 回呼可行嗎（§5.3）？ | 可行。Host 收到 `ASYNC_START` 立即返回，結果由其他執行緒延遲 30 ms 後排入，沙箱執行緒在 `ASYNC_WAIT` frame 內收下並進入 job loop；並行上限 6、佇列、取消、遲到結果丟棄全部與原生一致 |
| terminate／watchdog 可行嗎？ | 可行（WAMR 需 `WAMR_BUILD_THREAD_MGR=1`）。另一執行緒 `wasm_runtime_terminate` 在約 2 ms 內中止連「引擎內部、不會呼叫 import」的長迴圈；host import 內阻塞（等網路）也能在 watchdog 切片內中止。該 instance 丟棄後可重建，同程序另一 instance 不受影響 |
| WASM 直譯器效能 | **fast interpreter 約為原生 QuickJS 的 18–50 倍慢**；classic interpreter 另有嚴重問題（見下），**不可用**。詳見量測表 |
| D1 建議 | 見 §9（最終）。**WAMR fast 搭配 lexbor 已足夠**：≤ 200 KB 的真實頁面「解析＋一組查詢」約 70–110 ms，遠低於 1 秒門檻；**不提供原生 QuickJS 效能模式、S4 不需改採 wasm2c**（wasm2c 保留為日後備案）。§7 是補量 lexbor 之前的初版建議，以 §9 為準 |

## 1. 檔案與建置

```
wasm/
  guest.c            WASM 側：把 core 的 host callbacks 轉成 host_bridge／host_clock_ms import；匯出 sw_*
  wasm_frames.h      host_bridge frame 種類（S0 用原始 frame，正式版換成 ADR §4.2 的 CBOR）
  build-guest.sh     clang(wasm32-wasip1) + wasm-ld → build/wasm/shinsou_sandbox.wasm
  check-imports.sh   列出 import／export／memory；出現預期外 import 即失敗
  wamr_host.[ch]     WAMR embedder（instance、limits、watchdog、terminate）
  frame_host.[ch]    與 runtime 無關的 frame 分派（WAMR 與 wasm2c 共用）
  mock.[ch]          mock Host（同步 bridge 回覆＋會「晚一點」由其他執行緒完成的模擬網路）
  backend.h / backend_{native,wamr,w2c}.c   同一份測試／量測跑在三種後端
  scenarios.c        情境測試（對應 core_test.c ＋ WASM 專屬項目）
  bench.c            量測；standalone.c 為無 host import 的參考程式（原生／wasmtime）
  run-all.sh run-bench.sh build-standalone.sh   重現腳本
  results/           本次量測的原始輸出
```

乾淨重現（需 Homebrew `llvm lld wasi-libc wasi-runtimes wabt`；wasmtime 只用於參考）：

```bash
spikes/sandbox-s0/wasm/run-all.sh --clean    # 建 guest、WAMR fast／classic、原生、wasm2c，並跑全部情境（classic 約 3 分鐘）
spikes/sandbox-s0/wasm/run-bench.sh 3        # 量測（機器需閒置；classic 要數分鐘到十幾分鐘）
spikes/sandbox-s0/wasm/build-standalone.sh   # wasmtime 參考用 standalone
```

build 目錄：`build/wasm`（guest）、`build/wasm-fast`、`build/wasm-classic`、`build/wasm-native`、`build/wasm-w2c`、`build/wasm-standalone`。
bootstrap 由既有的 `python3 -I scripts/extract-bootstrap.py` 在各 build 目錄產生（唯讀使用）。

### 編譯設定

- guest：`clang --target=wasm32-wasip1 -O2 -mexec-model=reactor`，quickjs-ng 四個檔案＋`shinsou_sandbox_core.c`＋`guest.c`＋`libclang_rt.builtins`；
  `-z stack-size=2 MiB`（shadow stack，須大於 JS 限制 1 MiB）、`--initial-memory=8 MiB`、`--max-memory=128 MiB`（ADR §7 的 128 MiB 硬上限）、`--gc-sections --strip-all`。
- wasi-libc 只會經 `__imported_wasi_snapshot_preview1_*`（`clock_time_get`、`fd_close/fdstat_get/seek/write`）碰 host。`guest.c` 自己定義這 5 個符號：時間改走 `host_clock_ms`，`fd_*` 一律回 `EBADF`，因此 import 清單裡沒有 WASI。
- WAMR（`third_party/wamr`，從原始碼建到 `build/`）：`INTERP=1`，`FAST_INTERP=1|0`，`AOT=0 JIT=0 FAST_JIT=0`，`LIBC_WASI=0 LIBC_BUILTIN=0`，`THREAD_MGR=1`，`BULK_MEMORY=1 REF_TYPES=1`，`SIMD=0`。
- 不需要 `-mllvm -wasm-enable-sjlj`：quickjs-ng 用回傳值傳遞例外，沒有 setjmp。

### 產物大小與 import／export

- `shinsou_sandbox.wasm`：**1,095,506 bytes**（gzip 376,334）。大部分是 Unicode 表與 quickjs 本體。
- Import（2）：`env.host_clock_ms (i32) -> i64`、`env.host_bridge (i32,i32,i32,i32) -> i32`。
- Export（12）：`memory`、`_initialize`、`sw_alloc`、`sw_free`、`sw_new`、`sw_init`、`sw_invoke`、`sw_result_ptr`、`sw_result_len`、`sw_memory_used`、`sw_bridge_calls`、`sw_free_runtime`。
- Memory：initial 128 pages（8 MiB）、max 2048 pages（128 MiB）。
- App 內大小（arm64 text+const）：WAMR fast ≈ 207 KB、classic ≈ 175 KB、原生 QuickJS ≈ 865 KB；wasm2c 產物 ≈ 2.6 MB（見 §6）。

## 2. host_bridge 協定（S0 版）

`host_bridge(frame_ptr, frame_len, out_ptr, out_cap) -> len`，frame 第一個位元組是種類：

| frame | 內容 → 回覆 |
|---|---|
| `BRIDGE_CALL` | `[u32 method_len][method][args_json]` → `[ok][json]`（同步，Host 在沙箱執行緒上處理） |
| `ASYNC_START` | `[u32 op][request_json]` → `[ok]`，**立即返回** |
| `ASYNC_CANCEL` | `[u32 op]` |
| `ASYNC_WAIT` | `[u64 deadline_ms]` → `N × [u32 op][u8 ok][u32 len][payload]`（阻塞到有結果或 deadline） |
| `LOG` | `[bytes]` |
| `FETCH_REPLY` | 回覆大於 `out_cap` 時，Host 暫存並回傳所需長度，guest 擴大緩衝後以此 frame 取回 |

`host_clock_ms(0|1)`：0 = 牆鐘（`Date.now`）、1 = 單調時鐘（interrupt handler／deadline）。
單一 frame 沒有 1 MiB 上限檢查（正式版 CBOR frame 才有）；1 MB HTML 經 `BRIDGE_CALL` 回覆走 `FETCH_REPLY` 路徑，已驗證。

## 3. 情境結果

`results/run-all.log` 為完整輸出。下表為 WAMR fast（預設 limits：heap 16 MiB、JS stack 1 MiB、invoke 300 ms）：

| 情境 | 結果 |
|---|---|
| 解析 HTML（bootstrap 的 Jsoup + `select`） | OK，與原生輸出逐字相同 |
| 暴露的全域 | `std/os/require/process/__shinsouAsync/fetch/XMLHttpRequest/WebAssembly` 皆 `undefined` |
| 無窮迴圈／吞 interrupt／catastrophic regex | `LIMIT_TIME`（301 ms） |
| 深遞迴 | `LIMIT_STACK`（1 ms），instance 可繼續使用 |
| 記憶體炸彈（JS heap 16 MiB） | `LIMIT_MEMORY`；需 invoke 上限 ≥ 約 1 s（直譯器執行 `'x'.repeat` 的 C 迴圈很慢，原生 29 ms／fast 約 510 ms） |
| OOM 被 catch 後空轉 | `LIMIT_TIME` |
| bridge flood／過大結果／缺少 hook | `LIMIT_BRIDGE`／`LIMIT_RESULT`／`NO_SUCH_HOOK` |
| `bridge.net.fetch` ×10（上限 6） | OK，最大同時 6；佇列滿 → 恰好 1 個 `ASYNC_QUEUE_FULL` |
| 非同步＋延遲 30 ms | 10 個請求分 2 波，79 ms 完成（結果由其他執行緒晚到） |
| 同步與非同步混用、遲到結果 | OK；invocation 結束時 pending op 以 `ASYNC_CANCEL` 取消，之後不會洩漏到下一次 |
| 永不 settle 的 Promise | `PROMISE_STALLED` |
| 另一執行緒 terminate（50 ms） | 回 `TRAPPED`，instance 已死；重建後正常 |
| watchdog 200 ms，JS deadline 60 s | 208 ms `TRAPPED` |
| watchdog，guest 阻塞在 `ASYNC_WAIT`（網路 5 s） | 217 ms `TRAPPED`（embedder 把 wait 切成 25 ms 片段並檢查 dead 旗標） |
| terminate 命中「引擎內部」長迴圈（`Array.sort` 40 萬筆，不呼叫 import） | 約 2 ms 內中止（THREAD_MGR=1） |
| terminate 期間同程序的鄰居 instance（另一執行緒） | 100 次呼叫 0 失敗 |
| 線性記憶體上限 24 MiB（JS heap 上限 256 MiB） | `LIMIT_MEMORY`，`memory.grow` 失敗被 malloc→QuickJS OOM 路徑吞下，**instance 仍存活可用**，線性記憶體停在 24 MiB |
| 建立／銷毀 ×50 | 50 次全部正常 |

classic 與 wasm2c 全數通過（classic 用 `SW_TIME_SCALE=20`、`SW_WASM_STACK=32 MiB`，見 §4、§6）。

## 4. WAMR 的實際行為（含踩到的坑）

- **terminate**：`wasm_runtime_terminate(inst)` 只是設 exception（"terminated by user"），`wasm_runtime_call_wasm` 回傳 false。
  - `THREAD_MGR=1`：interpreter 在迴圈／呼叫處檢查 suspend flag，引擎內部的長迴圈約 2 ms 內中止。
  - `THREAD_MGR=0`：只在函式邊界發現，同一個 40 萬筆 sort 要等跑完（81 ms 才回，而 terminate 在 24 ms 發出）。**S4 必須開 THREAD_MGR**。
  - terminate 後 instance 不可再用（本原型以 `dead` 旗標拒絕後續呼叫）；`deinstantiate` 後重建 50 次正常。
- **blocking import 無法被 terminate 打斷**：terminate 只在 import 返回後生效，所以 embedder 的 `ASYNC_WAIT` 必須切片等待並檢查 `dead`（已做，否則等 5 s 網路會卡住 watchdog）。Host 自己的同步 `BRIDGE_CALL`（例如卡住的 `runBlocking`）同理，Host 必須自行設逾時。
- **線性記憶體上限**：`wasm_runtime_instantiation_args_set_max_memory_pages` 只能「調低」模組宣告的 max；調高會印警告並被忽略。上限低於 initial 會失敗。超限時 `memory.grow` 回 -1，wasi-libc `malloc` 回 NULL，QuickJS 走既有 OOM 路徑，不會 trap。
- **interpreter stack**：WAMR 的 operand/frame stack（`wasm_stack_bytes`）與 guest 的 shadow stack 是兩回事。JS stack 限制 1 MiB 時，fast 需要 ≥ 約 5 MiB，**4 MiB 會在遞迴時 trap（instance 死亡而非 `LIMIT_STACK`）**；classic 需要 ≥ 約 32 MiB（16 MiB 會 trap）。原型用 16 MiB（fast）／32 MiB（classic）。這塊記憶體是 host malloc，只有碰到的頁才計入 RSS。
- **classic interpreter 在 QuickJS 上病態地慢**：預設設定下 `fib(27)` 158 秒（fast 為 0.29 秒，約 550 倍）。`sample` 顯示時間全花在 `wasm_loader_find_block_addr`：classic 每次進入 block 都要掃描 bytecode 找對應的 `end`，靠 64 項的 block cache 緩解，而 `JS_CallInternal` 是超大函式，cache 持續失效。把 `BLOCK_ADDR_CACHE_SIZE` 調成 4096（`-DWAMR_BLOCK_CACHE=4096`）後 `fib(27)` 降到 4.7 秒，但仍是 fast 的約 16 倍。結論：**只考慮 fast interpreter**。
- 執行緒：非主執行緒要呼叫 `wasm_runtime_init_thread_env()`；一個 instance 一次只能由一條執行緒驅動。
- fast interpreter 載入時會把 bytecode 預處理，module 載入一次約 15–20 ms（含在 `cold_first_ms`）。

## 5. 量測

機器：Apple M4 Pro（12 核：8 效能＋4 效率）、24 GiB、macOS 27.0.1（26A434）；Apple clang 21（原生）、Homebrew clang 23.1.4（wasm）；quickjs-ng v0.17.0、WAMR 2.4.5、wasmtime 50.0.0。全部 `-O2`，閒置機器上取最佳值（fast 取 3 次，classic 取 1 次）。
負載使用既有的純 JS DOM bootstrap（`IosJavaScriptBootstrap.kt` 抽出），HTML 為合成的 `<div class="item"><a href>Title N</a><p>…</p></div>`（50 KB = 514 項、1 MB = 10,296 項），`select('.item a')` 後取文字。
每個 instance：heap 上限 128 MiB（量測用）、interpreter stack 16 MiB（classic 32 MiB）。

| 項目 | 原生 QuickJS | WAMR fast（倍數） | WAMR classic*（倍數） | wasm2c**（倍數） | wasmtime JIT***（倍數） |
|---|---|---|---|---|---|
| instance 冷啟動（建立＋bootstrap） | 0.89 ms | 18.8 ms（21×） | 54.8 ms（62×） | 1.67 ms（1.9×） | 1.58 ms |
| 同步 bridge 往返（原始 `__shinsouBridge`） | 0.04 µs | 1.54 µs（39×） | 9.84 µs（246×） | 0.13 µs（3.2×） | 0.08 µs（1.6×） |
| 同步 bridge 往返（含 JS shim `bridge.getPreference`） | 0.37 µs | 21.8 µs（59×） | 83.9 µs（227×） | 0.91 µs（2.5×） | — |
| JS：fib(27) | 9.1 ms | 289 ms（32×） | 4,746 ms（522×） | 30 ms（3.3×） | 18.6 ms（1.7×） |
| JS：5M 次迴圈運算 | 54 ms | 1,463 ms（27×） | 40,085 ms（743×） | 112 ms（2.1×） | 73 ms（1.1×） |
| JS：10 萬字串 join／split | 10.9 ms | 296 ms（27×） | 1,479 ms（135×） | 22.9 ms（2.1×） | 19.5 ms（1.7×） |
| JS：10 萬筆 sort | 41.8 ms | 770 ms（18×） | 7,008 ms（168×） | 88 ms（2.1×） | 63.9 ms（1.5×） |
| JS：JSON 往返（2 萬物件） | 25.8 ms | 1,021 ms（40×） | 3,433 ms（133×） | 57 ms（2.2×） | 44.9 ms（1.8×） |
| 解析 50 KB HTML＋select 文字 | 12.2 ms | 600 ms（49×） | 2,353 ms（193×） | 24 ms（2.0×） | 20.3 ms（1.6×） |
| 解析 1 MB HTML＋select 文字 | 474 ms | 17,626 ms（37×） | 59,658 ms（126×） | 827 ms（1.7×） | 697 ms（1.5×） |
| 每 instance 增量 RSS（初始化＋解析 50 KB 後，4 個平均） | 1.5 MB | 17.3 MB | 33.5 MB | 1.4 MB | — |
| 其中 JS heap | 1.03 MB | 0.80 MB | 0.80 MB | 0.80 MB | — |
| 其中線性記憶體 | — | 8 MB（初始值，已清零） | 8 MB | 8 MB（惰性） | — |

\* classic 用 `BLOCK_ADDR_CACHE_SIZE=4096`；預設 64 時 fib(27) 158 s、冷啟動 463 ms（`results/bench-wamr-classic-default-cache-partial.txt`，量到 fib 就中止）。
\*\* wasm2c 是 §6 的額外參考，**不是**題目要求的「直譯器」。
\*\*\* wasmtime 的倍數是對「同一支 standalone 程式的原生版」算的（`results/bench-standalone-*.txt`），僅用來顯示「若可 JIT，WASM 本身只慢 1.1–1.8 倍」。

解讀：

- fast interpreter 是「直譯器上跑直譯器」：CPU 密集的 JS 約慢 18–40 倍，與原生差距主要來自 interpreter 本身，不是隔離機制。
- 以現行純 JS DOM bootstrap，**50 KB 頁面解析約 0.6 秒、1 MB 約 18 秒**（M4 Pro；iPhone 單核較慢，未實機量測）。
- 同步 bridge 本身只貢獻 1.5 µs；21.8 µs 幾乎全是 JS shim（`JSON.stringify`／`JSON.parse`）在直譯器上的成本，不是跨界成本。
- ADR §5.2 預定把 DOM 換成編進 WASM 的 C 解析器，成本會從「JS 直譯器上跑 JS」改成「WASM 直譯器上跑 C」，預期比上表的 HTML 兩列好很多，但 **這台原型沒有量到**（`html/` 另有 session 在做解析器）。把原生 C 解析器的時間乘以 15–30 倍即可粗估。

## 6. 額外參考：wasm2c（預編成 C，非 JIT）

題目要求只比直譯器，但量測顯示直譯器與 JIT 差距大到值得多一個選項：`wasm2c`（wabt）把**同一個** `shinsou_sandbox.wasm` 轉成 C，在 build 階段編譯並靜態連結進 App。沒有執行期產生程式碼，所以 iOS 不禁止；因為被轉換的只有我們自己出貨的固定模組（第三方只提供 JS 當資料），所以不涉及「載入第三方原生碼」。

- 建置：`cmake -S wasm -B build/wasm-w2c -DSW_BACKEND=w2c`（`SW_WASM` 環境變數指向 `.wasm`）；使用 bounds-check 模式（`WASM_RT_USE_MMAP=0`、`WASM_RT_MEMCHECK_BOUNDS_CHECK=1`），不依賴 8 GiB 保留位址與 SIGSEGV handler（iOS 上較穩當；guard-page 模式會更快但未試）。
- 效能：約為原生的 **1.7–3.5 倍**（見表），冷啟動 1.7 ms，每 instance RSS 約 1.4 MB（線性記憶體惰性配置）。
- 隔離：線性記憶體仍由 bounds check 保護；情境測試全數通過。
- 代價：
  - **無法搶佔**：沒有 interpreter 可以停。terminate 是協作式——旗標在下一次 `host_clock_ms`（QuickJS interrupt handler 會呼叫）時 trap。引擎內部、不呼叫 import 的長迴圈只能等它跑完（`Array.sort` 40 萬筆實測是跑完才回）。若攻擊者利用引擎漏洞取得 guest 內任意執行，可以直接略過時鐘呼叫而無限迴圈；記憶體仍隔離，但 CPU 型 DoS 只剩 OS 層級手段（背景執行緒無法安全 kill）。
  - 線性記憶體上限是連結期 max（128 MiB），不是每 instance 可調。
  - 產物約 2.6 MB text、17 MB 生成的 C 原始碼（編譯時間長）；wasm2c 產物的 call depth 用計數器（本原型設 100000）＋大 stack 執行緒（本原型 64 MiB）防護，行為需 fuzz。
  - 需把生成的 C 納入建置與供應鏈審查。

## 7. 對 D1 與 ADR 的建議

**D1（是否提供原生 QuickJS 效能模式）**

1. **直譯器 WASM 的效能**：WAMR fast 約慢 18–50 倍（絕對值：50 KB 頁 0.6 s、冷啟動 19 ms、每次同步 bridge 的 JS 端成本約 22 µs）。對「單次點開一個列表／章節頁」可忍受；對「大型頁面（數百 KB 以上）、批次請求、搜尋多頁」會明顯卡頓。在量到 C 解析器的 WASM 成本前，無法斷言「日常可用」。
2. **不建議做「原生 QuickJS 效能模式」**：它要拿掉記憶體隔離，ADR §3.4 已指出引擎漏洞會直接危及 App；而且還要維護多一條路徑。若效能是唯一理由，wasm2c 以約 2–3.5 倍原生速度保住隔離，是更好的交換。
3. 建議的決策順序：
   - S4 基線：WAMR **fast** interpreter，`THREAD_MGR=1`。
   - 在 S0 補兩個量測：(a) `html/` 的 C 解析器放進 WASM 後，50 KB／200 KB／1 MB 頁面的解析時間；(b) 實機 iPhone（建議最舊的支援機型）上的同一套數字。
   - 若 (a)(b) 顯示典型頁面（≤200 KB）解析＋hook 超過約 1 秒，S4 改採 wasm2c（並單獨設計 CPU 型 DoS 的對策，例如 iOS 沙箱執行緒的 CPU 時間監控＋崩潰隔離），而不是做原生效能模式。
   - 若量測顯示 fast interpreter 已足夠，則 ADR 原本的「不提供效能模式」分支成立。

**對 ADR 的修訂建議**

- §4.5：「Host import 只有一個」改為「兩個」：`host_bridge` ＋ `host_clock_ms(clock)`（interrupt handler 需要單調時間，`Date.now` 需要牆鐘；沒有時鐘則 deadline 無法實作）。並註明 wasi-libc 的 `fd_*`／`clock_time_get` 要在 guest 內以 stub 取代，且以 `check-imports.sh` 類的檢查防止 WASI import 滲入。
- §4.5／§4.2：`host_bridge` 需要「回覆大於輸出緩衝」的處理（本原型的 `FETCH_REPLY`），以及 `ASYNC_WAIT` 這種**阻塞式** frame——iOS 上沙箱執行緒在等網路時整條執行緒阻塞在 import 內，不能被 terminate 打斷，Host 必須切片等待並自行逾時（§7 watchdog 的 `deadline + 2 秒` 在此成立的前提）。
- §5.3 iOS 段落可保留原句，補一句：「`AsyncBridgeCall` 返回後，結果在沙箱執行緒的 `ASYNC_WAIT` frame 內交付，而不是在任意時刻重入」。
- §7：新增 WAMR 專屬項目——interpreter stack（fast ≥ 5 MiB、建議 8–16 MiB；JS stack 限制 1 MiB 時）、必須 `THREAD_MGR=1`、classic interpreter 不可用、線性記憶體上限只能調低。
- §12 S4：驗收增加「WASM 效能門檻」的具體數字（建議以 §5 的 50 KB／200 KB 解析時間與冷啟動為準），並加入「wasm2c 靜態編譯」作為效能不足時的替代方案。
- §14 開放問題 2（WASM runtime 選擇）：WAMR fast interpreter 功能面可行；wasm3 未評估（見下）。

**對核心 `core/` 的觀察（沒有修改，請另外決定）**

1. `ssc_deliver_async` 的 payload 必須在 `payload[len]` 有 NUL（`JS_ParseJSON` 的要求）；標頭未說明。本原型在 guest 端複製一份補 NUL。建議核心內部複製，或在標頭註明。
2. 核心的 `ssc_cancel` 只能同執行緒旗標；WASM 下不可跨執行緒呼叫，只能 terminate（instance 必死）。若要「不殺 instance 的協作式取消」，可讓 `host_clock_ms` 在 cancel 後回傳極大值，讓 interrupt handler 以 `LIMIT_TIME` 結束；或讓核心提供可由 host 直接寫入線性記憶體的取消旗標位址。
3. `ssc_memory_used` 回 QuickJS 的 `malloc_size`，不含 guest allocator／DOM 外的線性記憶體；§7「instance 128 MiB」要看 `memory.size`，兩者要分開。

## 8. 未解決問題

- **沒有實機 iPhone 數字**；也沒有在 iOS 上實際載入（macOS 與 iOS 的 WAMR 平台層相同，但 W^X、記憶體上限、背景執行緒 QoS 未驗證）。
- 沒有 C 解析器（lexbor）進 WASM 的量測（屬 `html/` 工作）。
- wasm3 與其他直譯器未評估（原始碼不在 `third_party/`）；WAMR fast 之外的選項是否更快未知。
- 每 instance RSS：fast 17 MB 中約 8 MB 是 WAMR 對初始線性記憶體清零，其餘是 interpreter stack 與 bootstrap；可試縮小初始 8 MiB（代價是更多次 `memory.grow`，而 WAMR shrunk-memory 模式每次 grow 都會 realloc 複製，需量）。同時存活 3 個 instance（iOS 上限）約 50 MB，在預算內但未做壓力測試。
- terminate 對 WAMR 內部狀態的長期影響（重複 terminate 數千次是否洩漏）只測了 50 次重建；未跑 ASan／長時間 fuzz。
- wasm2c 路徑只驗證了功能與效能，沒有做 call-depth／native stack 耗盡的對抗測試，也沒有 guard-page 模式量測。
- 時間來源讓套件能讀牆鐘（`Date.now`）；若要降低時序側信道需要降精度，S0 未處理。
- `bridge.net.fetch` 的 Host 端實際網路（URLSession）、Kotlin/Native 與 C 的 interop、CBOR frame 未在此原型範圍。

## 9. lexbor 在 WASM 上的解析與查詢（ADR §16.1 的 D1 門檻）

`html/` 的 lexbor 原型（`html/src/sandbox_dom.c`，未修改）加上本目錄的 `dombench/` 量測驅動，編成 `wasm32-wasip1` 後分別放在 WAMR fast interpreter（`THREAD_MGR=1`，interpreter stack 16 MiB）與 wasm2c（bounds-check 模式）上執行，並與同一份原始碼的原生版（`-O2`）相除。
同一個 `db.c` 同時當原生程式與 wasm 模組：`.wasm` 564 KB（`-O2`，含 lexbor 八個模組＋sandbox_dom；`html/` 的 `-Oz` 是 0.5 MB），**只有 1 個 import（`env.host_clock_ns`）**，WASI 的五個符號在模組內以 stub 取代。

重現：`wasm/run-all.sh`（先建 `build/wasm-fast`）→ `wasm/dombench/build.sh` → 執行 `build/wasm-dombench/dombench_{native,wamr,w2c}`（用法與原始輸出見 `results/dombench-*.txt`；wamr 為 `dombench_wamr dombench.wasm <html…>`）。
機器同 §5（M4 Pro）。每份文件取中位數（≤ 512 KB 取 9 或 5 次，1 MB 原生 5 次、WASM 2 次）。原生數字與 `html/README.md` §6 一致（誤差內）。

\* 「解析＋查詢組」＝解析 ＋ `a[href*='/manhua-']` ＋ `li:has(img)` ＋ `a:contains(Next)` ＋ `span.tag` ＋ 每個 `li` 取 `text()` ＋ 每個 `a` 取 `attr`＋`absUrl`，單位 ms；括號為相對原生的倍數。
（`ul.mh-list > li:nth-child(2n+1) a` 沒有計入，見後面的最壞情況。）

| 文件 | 大小 | 引擎 | 解析 | `a[href*]` | `li:has(img)` | `a:contains(Next)` | 每個 `li` `text()` | **解析＋查詢組** |
|---|---|---|---:|---:|---:|---:|---:|---:|
| 合成 50 KB | 49 KB | 原生 | 0.26 | 0.02 | 0.01 | 0.95 | 0.10 | 1.4 |
| | | WAMR fast | 4.39 (17×) | 0.25 (13×) | 0.20 (14×) | 12.10 (13×) | 1.31 (13×) | **19.5 (14×)** |
| | | wasm2c | 0.43 (2×) | 0.02 (1×) | 0.02 (1×) | 1.06 (1×) | 0.12 (1×) | 1.8 (1×) |
| 合成 200 KB | 199 KB | 原生 | 0.79 | 0.05 | 0.04 | 2.95 | 0.31 | 4.4 |
| | | WAMR fast | 15.02 (19×) | 0.83 (17×) | 0.64 (14×) | 45.39 (15×) | 5.31 (17×) | **71.9 (16×)** |
| | | wasm2c | 1.76 (2×) | 0.08 (2×) | 0.07 (2×) | 4.28 (1×) | 0.49 (2×) | 7.1 (2×) |
| manhuagui 首頁（真實） | 158 KB | 原生 | 0.70 | 0.04 | 0.04 | 2.92 | 0.25 | 4.3 |
| | | WAMR fast | 13.18 (19×) | 0.77 (17×) | 0.73 (17×) | 47.71 (16×) | 4.33 (17×) | **71.8 (17×)** |
| | | wasm2c | 1.54 (2×) | 0.08 (2×) | 0.08 (2×) | 4.90 (2×) | 0.45 (2×) | 7.5 (2×) |
| dm5 首頁（真實） | 431 KB | 原生 | 1.13 | 0.05 | 0.06 | 4.24 | 0.32 | 6.1 |
| | | WAMR fast | 21.19 (19×) | 0.78 (15×) | 0.70 (12×) | 76.98 (18×) | 5.73 (18×) | **109.4 (18×)** |
| | | wasm2c | 2.23 (2×) | 0.08 (2×) | 0.08 (1×) | 6.69 (2×) | 0.51 (2×) | 10.0 (2×) |
| 合成 1 MB | 1023 KB | 原生 | 4.51 | 0.27 | 0.51 | 14.25 | 1.48 | 22.5 |
| | | WAMR fast | 77.24 (17×) | 4.04 (15×) | 3.25 (6×) | 234.08 (16×) | 25.71 (17×) | **367.9 (16×)** |
| | | wasm2c | 9.22 (2×) | 0.43 (2×) | 0.36 (1×) | 21.79 (2×) | 2.46 (2×) | 36.3 (2×) |

其他觀察：

- WAMR fast 對 lexbor 的倍數是 **12–19 倍**，比 QuickJS 的 18–50 倍低：lexbor 是緊湊的 C 迴圈，直譯器的分派成本相對少。
- 對照 §5：同樣 50 KB 頁面，純 JS bootstrap 在 WAMR fast 要 600 ms，lexbor 為 **19.5 ms（約 31 倍快）**；1 MB 為 17.6 s 對 0.37 s（約 48 倍）。把純 JS bootstrap 放在 dm5 首頁（原生約 100 ms）在 WAMR 上約 2–3 秒，lexbor 為 0.11 秒。
- 這些數字不含 QuickJS 端「從 JS 呼叫 `sbx_dom_*`、建立 JS 字串／物件」的成本（`MangasPage` 組裝等）；那部分是 §5 的直譯器成本，通常遠小於解析，但 S1 整合後需再量一次端到端。
- 線性記憶體：lexbor 模組全程 16 MiB 初始記憶體內（dm5 首頁解析峰值 2.8 MiB，原生計為 5.0 MiB；1 MB 頁面峰值 8.5 MiB，皆低於 §7 的 128 MiB 預算）。
- **最壞情況（不可被 JS interrupt handler 中斷的 lexbor 呼叫）**：`html/README.md` §7.2 的 6 萬個兄弟 `li:nth-child(2n+1)`：

| 引擎 | 結果 |
|---|---|
| 原生 | 完成，13.9 s |
| wasm2c | 完成，3.3 s（此例反而較快，lexbor 在 32 位元指標下的 O(n²) 掃描快取表現較好，不代表一般情況）；**但中途完全無法中止** |
| WAMR fast（`THREAD_MGR=1`） | watchdog 在 500 ms 呼叫 `wasm_runtime_terminate`，**506 ms 就在 lexbor 的 C 迴圈中途中止**（`terminated by user`），instance 丟棄；若不中止預估需數分鐘 |

  這正是 ADR §7「deadline＋2 秒強制 terminate」要解的情況，也是 wasm2c 的最大缺點：解析器漏洞或 O(n²) 輸入若進入不查時鐘的 C 迴圈，wasm2c 只能等它跑完（或靠 §7.2 的建議 (a) 把 `:nth-child` 改成 O(n)、(b) 在 token callback 內檢查 deadline）。

### 門檻判斷

ADR §16.1 門檻：典型頁面（≤ 200 KB）在 WAMR fast 上「解析＋一組查詢」約 1 秒內。

- 實測：199 KB 合成頁 72 ms、158 KB 真實頁（manhuagui）72 ms、431 KB 真實頁（dm5，已超過 200 KB）109 ms。**達標，餘裕約 10 倍以上**；即使 iPhone 單核慢 2–3 倍、再加上 QuickJS 端的組裝成本，仍遠低於 1 秒。
- 1 MB 的極端頁面約 0.37 秒，也在可接受範圍。
- 冷啟動（§5：QuickJS＋bootstrap 19 ms）在 DOM 改由 lexbor 之後只剩 QuickJS 初始化與精簡 shim，應更低，但需 S1 整合後確認。

### 對 D1 的最終建議

1. **WAMR fast interpreter 足夠**，iOS 預設且唯一的 sandbox 路徑就是 QuickJS-in-WASM（WAMR fast，`THREAD_MGR=1`）＋ lexbor 編進同一個 `.wasm`。
2. **不提供原生 QuickJS 效能模式**：ADR §4.5 的「若 WASM 已可接受，則不提供」分支成立，少一條需要維護與測試、且拿掉隔離的路徑。
3. **S4 不需改採 wasm2c**。wasm2c 快 7–9 倍，但無法搶佔（對 lexbor 這種不可中斷的 C 迴圈與引擎漏洞後的 CPU 型 DoS 沒有辦法），而 WAMR fast 的搶佔已實測有效；效能不是瓶頸時，保留可中止性比較重要。wasm2c 僅作為日後若 iPhone 實機量測顯示不足時的備案（§6）。
4. 此結論的前提與仍未驗證處：(a) 數字在 M4 Pro，iPhone 實機尚未量；(b) 端到端（QuickJS 呼叫 `sbx_dom_*`、結果序列化）尚未量；(c) 冷啟動含 lexbor 初始化（每文件約 1.2 MiB 固定開銷）未量。建議 S4 的驗收門檻訂成「最舊支援機型上，200 KB 頁面解析＋一組查詢 ≤ 1 秒」，若 (a) 顯示超標，再評估 wasm2c 並補上 CPU 型 DoS 對策。
5. 配合 `html/README.md` §7.2：把 lexbor 的 `:nth-child`／`:nth-last-child` 改成 O(n)，並保留 Host watchdog；在 WAMR 上 terminate 已證實能打斷 lexbor。
