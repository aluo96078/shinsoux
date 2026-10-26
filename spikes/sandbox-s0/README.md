# ADR-0002 S0：沙箱執行環境技術驗證總報告

對應 [ADR-0002](../../docs/architecture/0002-sandboxed-third-party-plugin-runtime.md) §12 的 S0。這個目錄是 spike，
不是 production 程式碼；App 本身沒有任何行為改變。第三方原始碼由 `scripts/fetch-deps.sh` 取得固定版本
（quickjs-ng v0.17.0、WAMR-2.4.5、lexbor v3.0.1），放在不納入版控的 `third_party/`。

量測機器：Apple M4 Pro（Mac16,8）、24 GiB、macOS 27.0.1。

## 結論摘要

| S0 項目 | 結果 | 細節 |
|---|---|---|
| (1) QuickJS 嵌入：共用核心 | ✅ 完成。每個 runtime 有記憶體／stack／時間／bridge 次數／結果大小上限；同步 bridge 與非同步 `bridge.net.fetch`（D10）皆可；惡意語料與非同步語意測試在 Release 與 ASan＋UBSan 下全數通過 | [core/](core/) |
| (1) Android JNI | ✅ 可編譯。arm64 `.so` 1.05 MiB，只依賴 libc／libm／libdl，無 socket／檔案／exec 符號；Gradle 產出含 `isolatedProcess` 的 AAR／APK；JNI 邏輯在 macOS 上測試通過 | [android/](android/) |
| (1) macOS helper | ✅ 完成。pipe 協定、Cancel、生命週期、無孤兒程序 | [macos/](macos/) |
| (1) iOS WASM | ✅ 完成（含 iPhone 17 Pro Max 實機，[ios/](ios/)）。`.wasm` 只有 2 個 import（`host_bridge`、`host_clock_ms`），所有情境在 WAMR fast／classic／wasm2c 皆與原生一致；lexbor 在 WAMR fast 上解析＋查詢典型頁面約 70 ms | [wasm/](wasm/) |
| (2) C HTML 解析器 | ✅ 建議採用 lexbor。官方語料 1,963 次 selector 執行與 jsoup **零差異**；解析比純 JS 快 30–85 倍；fuzz 1,200 秒無崩潰 | [html/](html/) |
| (3) Android isolated process 大 payload | ✅ 實機（Galaxy S24 Ultra）：pipe 與 SharedMemory 差距 < 10%，維持 pipe；1 MiB 結果 32 ms；修正超限結果會讓 Host 永遠等待的 bug | [android/](android/) |
| (4) macOS 沙箱 | ✅ `sandbox_init` deny-all 全部隔離項目通過；App Sandbox entitlement 較弱（可 fork／exec、可讀 `/etc`），只當備案 | [macos/](macos/) |
| (4) Windows AppContainer＋Job | ⛔ 未做：需 push 到遠端才能用 GitHub Actions Windows runner | — |
| (5) 官方套件量測 | ✅ 5 個官方套件、真實回應離線重播，與現行 Rhino 比較 | [bench/](bench/) |
| (6) 非同步 host 回呼 | ✅ 原生、helper pipe、JNI、WASM 四種路徑都驗證可行 | 各目錄 |

## 關鍵數字

### JS 引擎與 HTML 解析（原生，in-process）

| | 結果 |
|---|---|
| QuickJS vs Rhino，不碰 DOM 的步驟（JSON API、解壓縮、字串處理） | QuickJS 耗時約為 Rhino 的 **0.1–0.5 倍**（Rhino 已 JIT 暖機） |
| QuickJS＋純 JS DOM vs Rhino＋jsoup，解析 HTML 的步驟 | 純 JS DOM 慢 **3.5–25 倍**（dm5 1.2 MB 頁面單一步驟 260–370 ms） |
| lexbor vs 純 JS DOM，解析 | lexbor 快 **30–85 倍**（dm5 首頁 431 KB：1.2 ms vs 100 ms） |
| lexbor 語意 | 官方語料與 jsoup 1.18.3 結果數量 0 差異；純 JS DOM（也就是**現行 iOS 實作**）有 4 次不同 |

結論：引擎不是瓶頸，HTML 解析才是；S1 應直接採用 lexbor，不要以純 JS DOM 過渡。

### 程序隔離成本

| | macOS helper（Seatbelt） | Android isolated process |
|---|---|---|
| 冷啟動 | 8.0 ms（含沙箱與 bootstrap） | 105–217 ms（bind＋載入＋第一次 invoke；第二個起 zygote 已存在） |
| 單次 invoke／同步 bridge 往返 | 約 0.02 ms | 64 KiB 結果 2.4 ms、1 MiB 32 ms（binder＋pipe） |
| 常駐記憶體 | 約 6 MiB／helper | 約 18.7 MB PSS／沙箱程序（ART） |

### iOS WASM（相對原生 QuickJS 的耗時倍數）

| | WAMR fast | WAMR classic | wasm2c（預編成 C，非 JIT） |
|---|---|---|---|
| JS 運算 | 18–40× | 130–740×（不可用） | 2–3.3× |
| 解析 50 KB HTML（純 JS DOM） | 49×（0.6 秒） | 193× | 2.0× |
| 冷啟動 | 21×（18.8 ms） | 62× | 1.9× |
| 每 instance RSS | 17 MB | 34 MB | 1.4 MB |

## D1 的結果（iOS 效能模式）：不提供

補量 lexbor 編進 `.wasm` 後在 WAMR fast 上的成本（[wasm/README.md](wasm/README.md) §9）：

| 頁面 | 原生 | WAMR fast | wasm2c |
|---|---:|---:|---:|
| manhuagui 首頁（真實，158 KB）解析＋查詢組 | 4.3 ms | **71.8 ms** | 7.5 ms |
| dm5 首頁（真實，431 KB） | 6.1 ms | **109.4 ms** | 10.0 ms |
| 合成 1 MB | 22.5 ms | **367.9 ms** | 36.3 ms |

1. 典型頁面約 70 ms，遠低於 ADR §16.1 的「約 1 秒」門檻，**依 D1 規則定案：不提供原生 QuickJS 效能模式**。
2. **S4 不改採 wasm2c**：WAMR fast 能在 lexbor 不可中斷的 C 迴圈中途 terminate（實測 506 ms 停下），wasm2c 只能等它跑完；
   效能不是瓶頸時保留可中止性。wasm2c 只在 iPhone 實機量測不足時當備案。
3. **iPhone 實機已確認**（[ios/README.md](ios/README.md)）：iPhone 17 Pro Max 上 200 KB 頁面解析＋查詢組 69.6 ms、
   dm5 首頁 107.1 ms、1 MB 353.6 ms，與 M4 Pro 幾乎相同，門檻有 14 倍以上餘裕。
4. 仍待 S4：最舊支援機型的實測、端到端（QuickJS 呼叫 `sbx_dom_*` 與結果組裝）、含 lexbor 初始化的冷啟動。
5. iOS 整合必要設定：WAMR 必須關閉硬體邊界檢查（`WAMR_DISABLE_HW_BOUND_CHECK`、`WAMR_DISABLE_STACK_HW_BOUND_CHECK`），
   否則在 iOS 上初始化失敗。

## 對核心（`core/`）已做的修正

四個原型回報的核心問題中，屬於 bug 或不破壞 API 的已在本次修正並補測試：

- `ssc_deliver_async` 的 payload 不必再以 NUL 結尾（三個原型都踩到；先前會變成 `BAD_RESPONSE`）。
- `bridge.log`／`console.*` 改為本地交給 `ssc_host.log`，不再走 bridge、不佔 bridge 次數。
- （Codex review，高嚴重度）結果轉成 UTF-8 時若記憶體不足，舊程式碼會把字面常數 `"null"` 當成 QuickJS 字串釋放。
  已改為直接回報 `LIMIT_MEMORY`；以「接近 heap 上限的非 ASCII 結果」掃描 481 種大小的測試，在 ASan 下對舊程式碼重現
  `global-buffer-overflow`，修正後全數通過。
- （Codex review，高嚴重度）序列化結果時腳本的 `toJSON` 可排入 Promise job，舊程式碼會讓它在下一次 invocation 執行。
  已改為：序列化前就關閉所有 Host 介面（`toJSON` 無法呼叫 bridge 或 `bridge.net.fetch`）；init／invoke 結束時帶著取消
  旗標排空剩餘 job、再取消所有 pending 請求；無法排空的無限 job 串接會讓 runtime 標記為不可用（`SSC_ERR_INTERNAL`，
  Host 必須重建）。新增 `toJSON` 排入 job、`toJSON` 直接呼叫 Host、hook 留下的 microtask、兩種無限 job 串接的測試。
- （Codex 複審，高嚴重度）Host 若在 `async_cancel` 回呼中同步呼叫 `ssc_deliver_async`，舊程式碼仍會 settle 已取消的
  請求並啟動佇列中的下一個。已改為取消時先讓所有 slot 失效再通知 Host，且 invocation 關閉後 `ssc_deliver_async` 與
  佇列推進一律拒絕。新增重入交付與「確實觸發不可用並安全釋放」的測試（ASan 下通過）。
- （Codex 第 3、4 輪複審，高嚴重度）同一類問題的其他入口：settle 時腳本的 `then` getter、`Error.prototype` 的
  setter、參數的 `toString` 都能在核心狀態未更新完時執行腳本並重入 Host；`async_start` 期間同步交付會讓計數下溢或
  超過並行上限。已改為一致的原則：**任何會執行腳本或 Host 回呼的動作之前，狀態必須先更新完**——交付時先移除 slot 與
  更新計數再建立任何 JS 值；啟動前先佔用 in-flight 名額並取走請求內容；核心建立的錯誤物件只用自有資料屬性；
  `__shinsouBridge`／`__shinsouAsync` 只接受字串；錯誤分類只讀自有資料屬性，bridge 配額改以旗標判斷（腳本無法偽造）。
  第 5 輪 Codex 逐一掃描所有呼叫點，確認沒有剩餘的重入破壞。
- （Codex 第 5 輪，中等）`source` 或 hook 本身是 getter 且在其中拋錯、逾時或耗盡配額時，舊程式碼會誤報為
  `NO_SUCH_HOOK`。已改為每次讀取後立即檢查例外並回報正確狀態；新增 getter 拋錯（`SCRIPT_ERROR`）、逾時
  （`LIMIT_TIME`）、配額耗盡（`LIMIT_BRIDGE`）與 getter 回傳 hook（`OK`）的測試。
- 備註：quickjs-ng 的 `JS_NewError` 會呼叫 `Error.prepareStackTrace`；目前每個呼叫點都沒有半完成的狀態，S1 若新增
  呼叫點必須維持同樣的順序原則。

留待 S1 設計（會改 API，三個原型已各自繞過）：

- Cancel 的競爭：`ssc_invoke` 開始時會清掉先到的 cancel；完成後才到的 cancel 不會反映。S1 應改成以 invocation
  generation／callId 為單位的取消 token。
- `ssc_invoke` 改收 `(ptr, len)`，避免 JNI 多一次複製；批次 `ssc_deliver_async`。
- WASM 下無法跨執行緒 `ssc_cancel`：改由 host 讓 `host_clock_ms` 回傳極大值，或提供線性記憶體中的取消旗標。
- `ssc_memory_used` 只含 QuickJS heap，不含 DOM 與 WASM 線性記憶體；§7 的程序預算要分開計算。

## 需要使用者介入、尚未完成的項目

| 項目 | 需要什麼 |
|---|---|
| Windows AppContainer＋Job Object 原型 | 同意 push 到遠端分支，以 GitHub Actions `windows-2022` runner 驗證 |

## 重現

```bash
cd spikes/sandbox-s0
./scripts/fetch-deps.sh
cmake -S . -B build/native -DCMAKE_BUILD_TYPE=Release && cmake --build build/native -j8
./build/native/core_test build/native/bootstrap.js
bash macos/run-all.sh
bash wasm/run-all.sh          # --bench 另跑量測
bash android/scripts/test-host-jni.sh
bash html/scripts/reproduce.sh
```

官方套件量測步驟見 [bench/README.md](bench/README.md)。
