# shinsou-sandbox-core

第三方套件沙箱的原生核心（ADR-0002 §4、§17）：在沙箱內以 QuickJS 執行套件腳本，所有對外能力都只透過
SandboxProtocol v1 的 frame 向 Host 要求。Host 端的對應實作在
`composeApp/src/commonMain/kotlin/dev/shinsou/kmp/plugin/sandbox/`。

## 組成

| 檔案 | 內容 |
|---|---|
| `src/ssc_frames.{c,h}` | 嚴格 CBOR 子集與 frame codec，必須和 Kotlin 的 `SandboxProtocol.kt` 逐位元組一致（同一組 golden vector 與惡意輸入） |
| `src/ssc_core.{c,h}` | QuickJS runtime：記憶體／stack／時間／bridge 次數／結果大小上限，同步 bridge、`bridge.net.fetch`、以 generation 為單位的取消、`__shinsou*` 保留進入點 |
| `src/ssc_driver.{c,h}` | 沙箱端的 frame 驅動：讀取執行緒解析 frame 並立即套用 `Cancel`，主執行緒處理 Init／Invoke 與 Host 回呼 |
| `src/ssc_main.c` | `shinsou-sandbox`：以 stdin／stdout 溝通的桌面 helper（OS 沙箱由 S3 的 launcher 套上） |
| `src/sbx_dom.{c,h}` | lexbor 之上的 jsoup 相容 DOM（S0 評估過的轉接層，加上 handle 重用、記憶體計帳與故障注入） |
| `src/ssc_dom_js.{c,h}` | 把 DOM 綁進 QuickJS，並以 `SSC_DOM_SHIM` 取代 bootstrap 的純 JS 解析器 |
| `scripts/embed-bootstrap.py` | 把相容 bootstrap（iOS 引擎的 bootstrap 原文）嵌成 C 字串 |
| `fuzz/` | libFuzzer target：`frames_fuzz`（codec 與 envelope 串流）、`chunks_fuzz`（大型 chunk 重組）、`dom_fuzz`（DOM），以及 seed／字典 |
| `patches/` | 套用在釘選相依上的修補（`fetch-deps.sh` 自動套用，每個檔案說明原因） |

QuickJS 編譯時不含 `quickjs-libc`：沙箱內沒有檔案、程序、socket 或環境變數。

## 建置與測試

```bash
cd sandbox-core
./scripts/fetch-deps.sh                      # quickjs-ng v0.17.0、lexbor v3.0.1（不納入版控）＋ patches/
cmake -S . -B build/release -DCMAKE_BUILD_TYPE=Release && cmake --build build/release -j8
(cd build/release && ./frames_test && ./core_test bootstrap.js && ./driver_test && ./dom_js_test bootstrap.js && ./dom_fault_test)
cmake -S . -B build/asan -DCMAKE_BUILD_TYPE=Debug -DSSC_SANITIZE=ON && cmake --build build/asan -j8
```

jsoup parity（需要 S0 的 golden 語料與 oracle 輸出）：

```bash
G=../spikes/sandbox-s0/html
./build/release/golden_js build/release/bootstrap.js $G/golden $G/golden/corpus-selectors.txt $G/golden/extra-selectors.txt > /tmp/golden-js.txt
python3 -I $G/scripts/compare_golden.py ../spikes/sandbox-s0/build/html-golden/expected.txt /tmp/golden-js.txt
```

Kotlin↔C 端到端與官方套件 parity：建好 `build/release/shinsou-sandbox` 後執行
`./gradlew :composeApp:desktopTest --tests 'dev.shinsou.kmp.plugin.sandbox.*'`（找不到 binary 或官方套件 repo 時
相關測試會略過並印出原因；也可用 `SHINSOU_SANDBOX_BIN` 指定路徑）。加上
`SHINSOU_SANDBOX_BENCH=../spikes/sandbox-s0/build/bench/fixtures` 會另外比對 S0 的錄製資料（只在本機）。

Fuzz（需要含 libFuzzer 的 clang，例如 Homebrew LLVM；CI 見 `.github/workflows/sandbox-fuzz.yml`）：

```bash
cmake -S . -B build/fuzz -DCMAKE_C_COMPILER=/opt/homebrew/opt/llvm/bin/clang -DCMAKE_BUILD_TYPE=RelWithDebInfo -DSSC_FUZZ=ON
cmake --build build/fuzz -j8 --target frames_fuzz chunks_fuzz dom_fuzz frames_seeds
cd build/fuzz && mkdir -p seeds corpus/frames && ./frames_seeds seeds
./frames_fuzz corpus/frames seeds -max_len=4096 -max_total_time=600
./dom_fuzz corpus/dom ../../fuzz/dom-seeds -dict=../../fuzz/dom.dict -max_len=16384 -max_total_time=600
```

## 規則（違反任何一條都是 bug）

- 任何會執行腳本程式碼或 Host 回呼的呼叫之前，核心狀態必須已一致（ADR §16.2）。
- 腳本文字不離開沙箱：失敗只回穩定代碼，唯一例外是 Host 擁有的 source-failure 標記編號（ADR §17.1），而且只在
  丟出的值恰好是標記名稱時設定，不執行 getter 或 Proxy trap。
- 跑核心的執行緒 stack 必須大於給 QuickJS 的 stack 上限：driver 拒絕超過 4 MiB 的上限，`shinsou-sandbox`
  在 16 MiB stack 的執行緒上執行 driver。
- 沙箱外配置的記憶體都要有上限：排隊中的 `net.fetch` 請求（單筆 4 MiB、合計 8 MiB）、driver 收到但尚未處理的
  frame（1,024 個／32 MiB）、同步 bridge 等待期間暫存的非同步結果（256 個／16 MiB）。
- 同步 bridge 等待以 invocation 期限為界，相符的 `Cancel` 會立即中止等待；中止時核心丟出腳本無法 catch 的錯誤
  （`CANCELLED`／`LIMIT_TIME`）。bridge seq 在整個 session 單調遞增，已取消 invocation 的晚到回覆一律丟棄。
- DOM：模組自己的配置嚴格計入預算，每個失敗路徑都由 `dom_fault_test` 驗證；lexbor 的配置採軟上限（預算＋16 MiB），
  系統層級的配置失敗會讓沙箱程序 `abort()`（Host 視為崩潰）。DOM 物件不跨 invocation。
- driver 狀態在 heap 上並以參考計數管理；runtime 指標在讀取執行緒的鎖下發布與撤回，`Cancel` 不會碰到已釋放的
  runtime。
