# S0 官方套件量測：QuickJS 沙箱核心 vs 現行 Rhino

ADR-0002 §12 S0 第 5 項。以真實官方套件、離線重播的真實 HTTP 回應，比較 `shinsou-sandbox-core`
（原生 QuickJS，in-process、無 IPC）與現行 Rhino runtime 的冷啟動與各 hook 耗時。

## 方法

- 套件：`zh.dm5`、`zh.komiic`、`zh.manhuagui`、`zh.mangacopy`、`zh.wnacg`（`shinsou_plugin` 目前版本）。
- 流程（兩邊相同）：`getPopularManga(1)` → 第一部作品 `getMangaDetails` → `getChapterList` → 最後一章 `getPageList`。
- 資料：`record.py` 先實際抓取一次公開頁面（不送 Cookie／帳密），存成 fixture；之後兩邊都離線重播同一份回應。
  兩邊取得的作品數／章節數／頁數完全一致（例如 dm5：159 部、983 章），確認是同一份資料。
- QuickJS：`plugin_bench.c`，Release（`-O2`），每次 run 都是新的 runtime；DOM 用現行 iOS 的純 JS bootstrap
  （ADR §5.2 的 S1 暫時實作），5 次取中位數。
- Rhino：`composeApp/src/desktopTest/.../SandboxS0RhinoBaselineTest.kt`（需設定環境變數才會執行），
  `RhinoScriptPluginRuntimeFactory.unsafeForTests()`，DOM 是真正的 jsoup；先跑 1 次 JVM 暖機不計，再取 5 次中位數。
  因此 Rhino 數字是**已 JIT 暖機**的最佳情況，App 冷啟動時會更慢。
- 機器：Apple M4 Pro、24 GiB、macOS 27.0.1。

## 結果（中位數，ms）

| 套件 | 步驟 | QuickJS | Rhino | QuickJS ÷ Rhino |
|---|---|---:|---:|---:|
| zh.dm5 | init | 1.55 | 4.74 | 0.33× |
| | popular（HTML 1.2 MB） | 260.32 | 24.40 | **10.7×** |
| | details | 371.98 | 14.61 | **25.5×** |
| | chapters | 288.57 | 24.20 | **11.9×** |
| | pages | 7.82 | 3.97 | 2.0× |
| zh.komiic（JSON API） | init | 1.18 | 2.65 | 0.45× |
| | popular | 0.25 | 1.09 | 0.23× |
| | details | 0.04 | 0.59 | 0.07× |
| | chapters | 0.21 | 1.90 | 0.11× |
| | pages | 0.10 | 0.98 | 0.10× |
| zh.mangacopy | init | 2.08 | 4.30 | 0.48× |
| | popular | 0.43 | 2.35 | 0.18× |
| | details | 0.09 | 0.76 | 0.12× |
| | chapters | 3.22 | 30.53 | 0.11× |
| | pages | 0.16 | 1.33 | 0.12× |
| zh.manhuagui | init | 1.77 | 2.92 | 0.61× |
| | popular | 17.15 | 4.56 | 3.8× |
| | details | 10.23 | 2.96 | 3.5× |
| | chapters（含 LZString 解壓） | 23.76 | 99.69 | 0.24× |
| | pages | 0.51 | 4.51 | 0.11× |
| zh.wnacg | init | 1.30 | 1.83 | 0.71× |
| | popular | 7.78 | 1.82 | 4.3× |
| | details | 9.33 | 1.10 | 8.5× |
| | pages | 0.52 | 1.94 | 0.27× |

QuickJS 單一 runtime 的 heap 峰值：dm5 11.0 MiB、manhuagui 1.6 MiB、wnacg 0.6 MiB、mangacopy 0.3 MiB、komiic 0.2 MiB，
都遠低於 ADR §7 的 64 MiB 上限。

## 結論

1. **純 JS 運算 QuickJS 比 Rhino 快**：不碰 DOM 的步驟（JSON API、LZString 解壓、字串處理）QuickJS 約是 Rhino 的
   0.1–0.5 倍耗時，而且 Rhino 數字已經是 JIT 暖機後的結果。冷啟動也較快。
2. **瓶頸是 HTML 解析**：凡是解析 HTML 的步驟，用純 JS DOM bootstrap 會比 jsoup 慢 3.5–25 倍；dm5 的 1.2 MB 頁面
   單一步驟要 260–370 ms。這直接支持 ADR §5.2「沙箱內 C 解析器」的決策，純 JS bootstrap 只適合當暫時實作。
   C 解析器（lexbor）的實際數字見 `../html/README.md`。
3. 以上是原生 QuickJS 的數字；iOS 的 WASM 直譯還要再乘上一個倍數，見 `../wasm/README.md`。程序隔離的 IPC
   成本見 `../macos/README.md` 與 `../android/README.md`。

## 重現

```bash
cd spikes/sandbox-s0
cmake -S . -B build/native -DCMAKE_BUILD_TYPE=Release && cmake --build build/native -j8
clang -O2 -Icore/src bench/plugin_bench.c build/native/libshinsou_sandbox_core.a build/native/libquickjs.a -lm -o build/bench/plugin_bench
# 錄製（需要網路，只需一次）
python3 -I bench/record.py build/bench/plugin_bench build/native/bootstrap.js ../../../shinsou_plugin/plugins/zh.dm5.js build/bench/fixtures/zh.dm5
# 產生 Rhino 用的請求索引
BENCH_TRACE=1 build/bench/plugin_bench build/native/bootstrap.js ../../../shinsou_plugin/plugins/zh.dm5.js build/bench/fixtures/zh.dm5 1 \
  | python3 -I bench/make-index.py build/bench/fixtures/zh.dm5
# QuickJS 量測
build/bench/plugin_bench build/native/bootstrap.js ../../../shinsou_plugin/plugins/zh.dm5.js build/bench/fixtures/zh.dm5 5
# Rhino 量測（在 repo 根目錄）
SHINSOU_SANDBOX_BENCH=$PWD/spikes/sandbox-s0/build/bench/fixtures SHINSOU_SANDBOX_BENCH_PLUGINS=../shinsou_plugin/plugins \
  ./gradlew :composeApp:desktopTest --tests 'dev.shinsou.kmp.plugin.SandboxS0RhinoBaselineTest' -i
```

fixture 不納入版控（`build/` 已在 `.gitignore`）。

## 限制

- fixture 鍵是「方法＋參數 JSON」的雜湊；參數含時間戳的請求每次都不同（mangacopy 錄製時因此多抓了幾份），
  不影響量測，但重播前需先錄製。
- 只量 in-process 核心；沒有包含 helper／isolated process 的 IPC 往返。
- Rhino 數字來自 JUnit 內的 JVM，與 App 實際的 JIT 狀態不同。
