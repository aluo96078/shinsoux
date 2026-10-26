# S0：iPhone 實機量測（WAMR fast interpreter）

ADR-0002 §16.1 要求以 iPhone 實機確認 D1 的結論。這個目錄是一個最小的 iOS 量測 App，把 `../wasm/` 的兩支量測程式
（只把 `main` 改名）與 WAMR fast interpreter 編成 iOS arm64 靜態函式庫後連結進 App，結果寫進 App 的 Documents。
和正式的 Shinsou X App 完全分開。

- 裝置：**iPhone 17 Pro Max（iPhone18,2）**、iOS 27.0.1；Xcode 27.1（iOS 27.1 SDK）。
- WAMR-2.4.5 fast interpreter，`THREAD_MGR=1`，無 JIT／AOT；Release `-O2`。
- 對照：同一套程式在 Mac（Apple M4 Pro）上的結果（`../wasm/README.md` §5、§9）。

## 結果

### lexbor 解析＋查詢組（D1 門檻用）

「解析＋查詢組」＝解析 ＋ `a[href*='/manhua-']` ＋ `li:has(img)` ＋ `a:contains(Next)` ＋ `span.tag` ＋ 每個 `li` 取
`text()` ＋ 每個 `a` 取 `attr`＋`absUrl`（與 `../wasm/README.md` §9 相同定義）。

| 頁面 | 解析（iPhone） | 解析＋查詢組（iPhone） | 解析＋查詢組（Mac） | iPhone ÷ Mac |
|---|---:|---:|---:|---:|
| 合成 50 KB | 3.65 ms | **17.7 ms** | 19.5 ms | 0.91 |
| 合成 200 KB | 14.29 ms | **69.6 ms** | 71.9 ms | 0.97 |
| manhuagui 首頁（真實，159 KB） | 12.52 ms | **70.5 ms** | 71.8 ms | 0.98 |
| dm5 首頁（真實，431 KB） | 20.03 ms | **107.1 ms** | 109.4 ms | 0.98 |
| 合成 1 MB | 72.43 ms | **353.6 ms** | 367.9 ms | 0.96 |

線性記憶體全程維持在初始 16 MiB 內。

### QuickJS 核心在 WASM 上

| 項目 | iPhone | Mac |
|---|---:|---:|
| instance 冷啟動（建立＋bootstrap）平均 | 17.3 ms | 18.8 ms |
| 同步 bridge 往返（原始／含 JS shim） | 1.48 µs／18.9 µs | 1.54 µs／21.8 µs |
| JS：fib(27) | 276 ms | 289 ms |
| JS：5M 次迴圈 | 1,511 ms | 1,463 ms |
| JS：10 萬字串 join／split | 292 ms | 296 ms |
| JS：10 萬筆 sort | 781 ms | 770 ms |
| JS：JSON 往返（2 萬物件） | 898 ms | 1,021 ms |
| 解析 50 KB HTML（純 JS DOM，對照用） | 561 ms | 600 ms |
| 解析 1 MB HTML（純 JS DOM，對照用） | 16,799 ms | 17,626 ms |
| 每 instance 增量 RSS（其中 JS heap／線性記憶體） | 17.3 MB（0.8／8.0） | 17.3 MB（0.8／8.0） |

原始輸出：`results/iphone17promax-dombench.txt`、`results/iphone17promax-bench.txt`。

## 結論

1. **D1 門檻在實機上成立**：200 KB 以內的典型頁面「解析＋查詢組」約 70 ms，相對「約 1 秒」有 14 倍以上餘裕；
   1 MB 的極端頁面也只要 0.35 秒。**不提供原生 QuickJS 效能模式、S4 不改採 wasm2c 的結論不變。**
2. iPhone 17 Pro Max 單核與 M4 Pro 相當，所以 Mac 上量的 WAMR 數字可以直接當作新機型的參考。
3. 純 JS DOM 在實機上同樣是 50 KB 要 0.56 秒、1 MB 要 17 秒，再次確認 S1 必須直接採用 lexbor。

## 限制

- 只量了**最新機型**。最舊的支援機型（App 最低 iOS 16）單核可能慢 3–4 倍，推估 200 KB 頁面約 0.2–0.3 秒，仍低於
  門檻，但**未實測**。S4 驗收仍應在最舊支援機型上量一次。
- 沒有量端到端（QuickJS 呼叫 `sbx_dom_*` 再組裝 `MangasPage`）與含 lexbor 的冷啟動，屬 S1 整合後的項目。

## iOS 整合時的必要設定（S0 實機發現）

- **WAMR 必須關閉硬體邊界檢查**：`WAMR_DISABLE_HW_BOUND_CHECK=1` 與 `WAMR_DISABLE_STACK_HW_BOUND_CHECK=1`。否則 iOS
  拒絕它保留的大段位址空間與 `mprotect` 的 stack 保護頁，`wasm_runtime_full_init` 以「Failed to init stack guard
  pages」失敗。改用軟體邊界檢查後，效能仍與 Mac（預設開啟硬體邊界檢查）相當，記憶體隔離不受影響。
- 執行 WASM 的執行緒要自行建立並給足 stack（本 App 用 16 MiB）；iOS 次要執行緒預設 stack 只有 512 KiB。
- 用 iOS 27 SDK 建置的 App 必須採用 UIScene 生命週期，否則啟動時就在
  `_UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption` 中止。正式 App 用 SwiftUI `App` 生命週期，
  不受影響；這裡只是量測 App 需要。

## 重現

前置：Xcode（同意授權）、已登入 Apple ID、iPhone 以 USB 連接並開啟開發者模式；先完成 `../wasm/run-all.sh` 與
`../wasm/dombench/build.sh`。

```bash
cd spikes/sandbox-s0
bash ios/build.sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcrun devicectl device install app --device <UDID> build/ios-dd/Build/Products/Release-iphoneos/S0Bench.app
xcrun devicectl device process launch --device <UDID> --terminate-existing dev.aluo.shinsoux.s0bench dombench   # 或 bench
xcrun devicectl device copy from --device <UDID> --domain-type appDataContainer \
  --domain-identifier dev.aluo.shinsoux.s0bench --source Documents/dombench.txt --destination dombench.txt
```

量測期間 App 必須保持在前景（App 已停用自動鎖定）；輸出最後一行是 `DONE rc=0`。
