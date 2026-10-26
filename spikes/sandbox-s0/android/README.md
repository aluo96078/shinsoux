# ADR-0002 S0：Android 原型

範圍：在沒有裝置的 Mac 上能完成的部分。JNI 層、AIDL／Kotlin 服務骨架、Gradle 編譯、macOS 上的 JNI 測試皆已完成；
**所有實機項目尚未驗證**（見 §6）。本目錄之外沒有任何改動。

## 1. 目錄

| 路徑 | 內容 |
|---|---|
| `jni/sandbox_jni.c` | 核心 API 的 JNI 包裝（host callbacks → `NativeHost`） |
| `CMakeLists.txt` | 建 `libshinsou_sandbox_core.so`（QuickJS + core + JNI）；`-DSSC_HOST_JNI=ON` 為 macOS JVM 測試版 |
| `src/main/java/.../NativeCore.java`、`NativeHost.java`、`FrameCodec.java` | JNI 對外類別與 frame 編解碼（純 Java，可在 JVM 測） |
| `src/main/aidl/.../ISandbox.aidl`、`IHostBridge.aidl`、`Payload.aidl` | 兩個方向的 IPC |
| `src/main/kotlin/.../SandboxService.kt` | 沙箱端服務（isolated process） |
| `src/main/kotlin/.../SandboxClient.kt`、`SandboxManager.kt` | Host 端：`bindIsolatedService`、`linkToDeath`、閒置回收、LRU |
| `src/main/kotlin/.../Payload.kt`、`PayloadCodec.kt` | 大 payload：pipe 與 SharedMemory 兩種實作 |
| `harness/` | 實機量測用的最小 app（logcat tag `SSC-S0`） |
| `test/HostJniTest.java` | macOS JNI 測試 |
| `scripts/` | `build-android.sh`、`test-host-jni.sh`、`device-verify.sh` |

## 2. 建置方式

前置：先有 repo 層的 S0 build（產生 `build/native/bootstrap.js`）：
`cmake -S spikes/sandbox-s0 -B spikes/sandbox-s0/build/native && cmake --build spikes/sandbox-s0/build/native`

```bash
spikes/sandbox-s0/android/scripts/build-android.sh [ndk版本]   # NDK CMake，輸出 build/android-{arm64-v8a,x86_64}/
spikes/sandbox-s0/android/scripts/test-host-jni.sh              # macOS JNI 測試（build/android-host）
cd spikes/sandbox-s0/android && gradle assembleDebug            # AAR + harness APK
```

`build-android.sh` 每次先刪除 build 目錄，兩個 ABI 皆為 Release、`ANDROID_PLATFORM=android-29`。
Gradle 輸出在 `android/build`、`android/harness/build`（已 `.gitignore`）。

## 3. .so 大小與符號依賴（NDK 27.0.12077973，Release）

| ABI | strip 後 | 未 strip |
|---|---|---|
| arm64-v8a | 1,100,864 B（1.05 MiB） | 6,850,672 B |
| x86_64 | 1,177,792 B（1.12 MiB） | 7,281,168 B |

- `NEEDED`：`libm.so`、`libdl.so`、`libc.so`（`libdl` 是 NDK 預設連結，沒有實際使用的符號）。沒有 liblog、libandroid、libz、libc++。
- 未定義符號全數來自 libc／libm：`malloc` 系列、`mem*`／`str*`、數學函式、`pthread_mutex/cond*`（含 `condattr_setclock`）、
  `clock_gettime`／`gettimeofday`／`localtime_r`（`Date`）、`vsnprintf`／`printf`、`abort`、`__stack_chk_fail`、`__cxa_atexit`。
- **沒有** `socket`／`connect`／`open`／`fopen`／`read`／`write`／`getenv`／`dlopen`／`fork`／`exec*`／`system` 等符號。
- 僅 arm64 多一個 `__system_property_get`、`getauxval`，來自編譯器 runtime 的 outline-atomics 偵測，不是核心程式碼呼叫。
- `printf` 的來源尚未追到（可能是 QuickJS 的 debug 輸出路徑），S1 正式建置前建議以 `-DNDEBUG`／移除確認。
- 匯出符號只有 7 個 `Java_dev_shinsou_sandbox_NativeCore_*`（`-fvisibility=hidden` + `--exclude-libs,ALL`）。
- 以 16 KiB page 對齊連結（`max-page-size=16384`）。
- 內嵌在 AAR／APK 的 .so 經 AGP strip，arm64 1,242,224 B、x86_64 1,348,064 B（略大於我們自己 strip 的結果）。

## 4. 骨架架構

```text
Host（App 程序）                                   isolated process（每 package 一個，uid u0iNNNN）
SandboxManager ─ LRU(4)、閒置 60s、crash-loop 計數
  └ SandboxClient(packageKey)
      bindIsolatedService(intent, BIND_AUTO_CREATE,   ───►  SandboxService（isolatedProcess、exported=false、useAppZygote）
        instanceName = packageKey, executor, conn)            └ ISandbox.Stub
      linkToDeath ─► fail(): 完成所有 pending 為 CRASHED         ├ binder 執行緒：invoke/cancel/asyncResult 只入列
      IHostBridge.Stub（每 package 一個 = 身分 capability） ◄── ├ 單一 ssc-runtime 執行緒擁有 QuickJS（符合核心的 threading 契約）
        bridgeCall（同步）、asyncStart/Cancel、onResult、onLog     └ BinderHost：同步 bridge = 阻塞 binder；asyncWait = 等 inbox，再在同一執行緒 deliverAsync
```

重點：

- `ssc_cancel` 在 binder 執行緒呼叫（核心保證 thread-safe）；其餘都在 runtime 執行緒。
- 每次 `invoke` 開始時清空 async inbox，避免上一個 invocation 的遲到結果進入下一個（ADR §5.3）。
- Host 端 `asyncStart` 的完成 callback 會檢查 op 是否仍有效（`ops` 表），已取消或沙箱已死則丟棄。
- `fail()` 同時處理 `linkToDeath`、`onServiceDisconnected`、`onBindingDied`、主動 `stop()`；死亡的 client 不會自動重建，
  由 `SandboxManager.onDead` 移出 LRU 並累計 crash 次數（`crashLoopLimit` 之後 `acquire` 拒絕）。
- Manifest 的 `preload_name` 指向 `SandboxZygotePreload`，在 app zygote 內預先 `System.loadLibrary`。
- 協定本身（CBOR）仍屬 S1。這裡只有 `FrameCodec`（u32 長度 + type/flags/id，1 MiB 切 chunk，總量上限、錯誤即 fail closed）作為串流行為的替身。

## 5. 驗證結果

### 5.1 macOS：JNI 與 frame（`scripts/test-host-jni.sh`）— 通過（ALL PASSED）

同一份 `sandbox_jni.c` 用 JDK 21 的 `jni.h` 編成 dylib，由 `HostJniTest` 驅動，涵蓋：

- 初始化、JS → host 同步 bridge 的參數／回傳往返、`Jsoup.parse` 結果；
- hook 不存在 → `NO_SUCH_HOOK`，無限迴圈 → `LIMIT_TIME`（300 ms 逾時），另一執行緒 `cancel()` 後 runtime 可重用；
- `bridge.net.fetch`：Java 的 `asyncWait` 內透過 JNI 重入 `deliverAsync`（同一執行緒），`Promise.all` 結果正確；
- invocation 結束時 pending op 觸發 `asyncCancel`，遲到結果被丟棄（回傳 -1）；
- host callback 丟例外 → 轉成 JS 的 `null`，不會讓 JVM／核心崩潰；
- `FrameCodec`：3 MiB+ 的 chunk 往返、空 frame、乾淨 EOF、總量超限、長度欄位非法、截斷皆正確失敗。

限制：這只驗證 JNI 邏輯，**不代表 ART／bionic 上的行為**（例如 JNI 區域參考表、執行緒 attach、bionic 的 pthread 時鐘）。

### 5.2 編譯

| 項目 | 結果 |
|---|---|
| NDK CMake，arm64-v8a + x86_64，API 29 | 成功 |
| Gradle `assembleDebug`（AGP 8.11.1、Kotlin 2.4.0、compileSdk 36、minSdk 29、AIDL、CMake 3.22.1、NDK 27）| **成功**，產出 AAR 與 harness APK |
| APK manifest（`aapt2 dump xmltree`）| `isolatedProcess=true`、`exported=false`、`useAppZygote=true`、`preload_name` 皆已合併進去，minSdk 29 |

注意：第一次以 `--offline` 解析失敗，因為本機快取沒有 Gradle plugin marker（`com.android.library` 等）；改為連網後，
只多下載了很小的 marker，AGP／Kotlin／androidx 其餘都來自本機快取。版本與 repo 的 `libs.versions.toml` 一致，沒有改動該檔。
未跑 lint、未跑任何在 Android 上執行的測試。

### 5.3 pipe 與 SharedMemory 比較（只有程式碼層面；延遲數字待實機）

| | `ParcelFileDescriptor.createPipe()` | `SharedMemory` |
|---|---|---|
| 實作量 | 約 25 行 + 重用 `FrameCodec`；**必須另開寫入執行緒**（pipe 緩衝 64 KiB，寫入端不能阻塞 binder 執行緒） | 約 20 行；同步、無額外執行緒 |
| 資源生命週期 | 兩端 fd 必須各自關閉；讀端提早關 → 寫端 `IOException`（已處理）；中途取消需要關 fd 才能解除阻塞 | 一個 region，用完 `close()`；沒有半途狀態 |
| 不可信對端 | 長度由 `FrameCodec` 逐 frame 強制上限，天然 bounded；對端可以慢速餵資料（需讀取逾時） | 對端可在我們讀取時改內容 → 必須先 `setProtect(PROT_READ)` 並**複製出來再解析**；需先檢查宣稱長度 ≤ `region.size` |
| 複製次數 | 2 次（寫入 + 讀出）加 kernel 緩衝 | 2 次（寫入 region、讀出到 heap）；零拷貝需要核心支援直接讀 region，S0 未做 |
| 與串流／分塊的相容 | 自然對應 ADR §4.2 的 chunk frame | 一次性整塊；超大值需自行分段 |
| 限制 | 無特殊 | API 27+（minSdk 29 沒問題）；每個 region 是一個 ashmem fd，量大時耗 fd |

初步傾向：**預設 pipe**（串流語意與 ADR 的 chunk frame 一致、天然有大小上限、不用處理被對端改寫），除非實機量測顯示 SharedMemory 在
1–4 MiB 上有明顯優勢（例如 >2× 延遲差）。inline 門檻 256 KiB（binder 緩衝區 1 MB 是整個程序共用，非單筆）。

## 6. 實機驗證計畫

前置：API 29+ 裝置，開啟 USB 偵錯；先完成 §2 的 native build。一鍵版：

```bash
spikes/sandbox-s0/android/scripts/device-verify.sh PIPE
spikes/sandbox-s0/android/scripts/device-verify.sh SHARED_MEMORY
```

逐項確認（harness 已自動執行多數；括號為預期）：

1. **隔離探測**：logcat tag `SSC-S0-PROBE`。`socket(AF_INET/AF_INET6)`、`open(app data dir)`、`open(apk)`（可能成功——見未解決問題 1）、
   `open(/sdcard)`、`open(/proc/net/tcp)`、`execve` 都應是 `errno=` 而不是 `OK(unexpected)`。
2. **不同 pid／uid**：harness 印出 `pkg.alpha`、`pkg.beta` 的 pid／uid 且互不相同，uid 為 isolated range（`u0i*`）；
   另可 `adb shell ps -A -o USER,PID,NAME | grep dev.shinsou.sandbox.harness`。
3. **kill 沙箱不影響 Host**：harness 會 `crash()` alpha：預期 alpha 之後回 `CRASHED/DISCONNECTED`、beta 與 Host 仍正常。
   手動版：`adb shell kill <sandbox pid>`，再重新觸發 invoke。`linkToDeath` 應在 <100 ms 內讓 pending 失敗。
4. **pipe vs SharedMemory 延遲**：兩次 `device-verify.sh`（PIPE／SHARED_MEMORY），比較 harness 輸出的 `result 64K/512K/1M/4M` 與
   `bridge reply ~1 MiB`（各 15 次取中位數）。CPU 可用 `adb shell top -H -p <pid>` 觀察。
5. **冷啟動**：`pkg.* cold start` 行（bind + zygote fork + load .so + 初始化 + 第一次 invoke）。分別量
   有／無 `useAppZygote`（移除 manifest 屬性重建）、第一個 vs 第二個 package。目標值待定（S0 決定）。
6. **RSS／PSS**：`adb shell dumpsys meminfo dev.shinsou.sandbox.harness`（腳本已印），看每個 `:sandbox` 程序的 PSS，
   以及 4 個 package 同時存活時的總和（可修改 harness 的 package 清單），用來調整 LRU 上限 4。
7. **逾時與限制**：`loop` hook 應回 status 4（`LIMIT_TIME`）。
8. **閒置回收**：等 60 秒以上，`ps` 應看不到 sandbox 程序（目前只有 sweeper，harness 未覆蓋，需手動確認）。
9. **Application 在 isolated process 內初始化**：見未解決問題 2，需用真 App 的 `Application` 類別驗證。

## 7. 未解決問題與對 ADR 的建議

1. **isolated process 仍可讀 APK 自己**：isolated uid 讀不到 App 私有資料目錄，但 APK 檔本身（`/data/app/...`）因系統需要載入 dex，
   通常可讀。這不洩漏使用者資料，ADR §13 的「讀 App 檔案必須失敗」應限縮為「App 資料目錄（`dataDir`、`shared_prefs`、`databases`）與外部儲存」。
2. **Application 類別會在 isolated process 也被實例化**（含 `Application.onCreate`）。正式 App（Koin／DB／Compose 初始化）必須在
   `Application.onCreate` 偵測 `Process.isIsolated()`（API 28+）並直接 return，否則會在沙箱內初始化 DB 或做網路。這應寫進 ADR §4.3 與 S2 完成標準；
   另外 isolated process 載入的是完整 APK 的 classloader，需要用 R8 與只載入必要類別來降低冷啟動／RSS。
3. `useAppZygote="true"` 搭配 `bindIsolatedService` 時，zygote 本身是一個常駐程序（額外記憶體），且 ZygotePreload 內不能做複雜工作；
   ADR 把它寫成「可搭配」，建議改為「由 S0 量測後決定」。
4. 核心目前以 `ssc_invoke` 的 `args_json` 需 NUL 結尾；JNI 層為此多複製一次參數（`malloc` + `memcpy`）。若要零複製，核心可改收 `(ptr,len)`，但這屬於 core 的變更，本 session 未改。
5. 同步 bridge 用阻塞 binder（沙箱 runtime 執行緒 → Host binder 執行緒）。Host 的 binder 執行緒池預設 16 條，若 4 個沙箱同時大量同步呼叫可能耗盡；
   S2 要量測並考慮限制 per-package 並行同步呼叫數。
6. 尚未實作：Host 端對 `CRASHED` 的重建與重試政策、`Cancel` 與 pending 同步 bridge 的互動、`SandboxManager` 的單元測試（需 Robolectric／裝置）。
7. 建議 ADR 修訂：§13 隔離探測項目依第 1 點調整；§4.3 加上 `Process.isIsolated()` guard；§12 S2 完成標準加入「冷啟動／PSS 數字」與「Application 不在沙箱內初始化」。

## 8. 對核心（`core/`）的建議（未修改）

- `ssc_invoke` 接受長度參數而非 NUL 結尾字串（見上）。
- 需要時提供 `ssc_deliver_async` 的 batch 版本，Android 一次 `asyncWait` 常會收到多個結果，目前每個結果一次 JNI 往返。

## 9. 實機驗證結果（2026-10-26）

裝置：**Samsung Galaxy S24 Ultra（SM-S9280）**、Android 16（API 36）、arm64-v8a。`scripts/device-verify.sh PIPE` 與
`SHARED_MEMORY` 各跑一次，原始輸出在 `results/`。

### 9.1 隔離探測

| 項目 | 結果 |
|---|---|
| 每個 package 一個程序 | ✅ alpha、beta 各自獨立程序，uid 為隔離範圍（`u0_i*`，與 Host 的 `u0_a70` 不同），共用 app zygote |
| `socket(AF_INET／AF_INET6)` | ✅ `EPERM` |
| 讀 App 資料目錄 | ✅ `ENOENT`（隔離 uid 看不到） |
| 讀 `/sdcard`、`/proc/net/tcp` | ✅ `EACCES` |
| 讀自己的 APK | ⚠️ 可讀（預期：系統載入 dex 需要，ADR §13 已限縮） |
| **產生子程序**（`sh`、`toybox`） | ❌ **隔離程序原本可以 fork／exec 系統程式** → ✅ 加上 seccomp 後被擋（`IOException`） |
| 建立執行緒（seccomp 之後） | ✅ 正常 |

**發現與修正**：Android 的 isolated process 雖然沒有網路與檔案權限，但仍可產生子程序（子程序繼承同一個隔離
uid 與 SELinux 網域，不算逃出沙箱，但擴大了能接觸的系統程式與核心攻擊面，也能繞過每程序的資源限制）。已在
`jni/sandbox_jni.c` 新增 `nativeLockdown()`，在載入不可信腳本前安裝 seccomp 過濾器（`PR_SET_NO_NEW_PRIVS`＋
`SECCOMP_FILTER_FLAG_TSYNC`）：禁止 `execve`／`execveat`、`fork`／`vfork`、沒有 `CLONE_THREAD` 的 `clone`，
`clone3` 回 `ENOSYS` 讓 libc 改走 `clone(2)`。過濾器疊加在平台的 App 過濾器之上。原本的探測直接在沙箱程序內
`execv`，成功時會把整個程序換掉而看起來像崩潰，已改為以 `ProcessBuilder` 在子程序嘗試。

### 9.2 傳輸與限制

| 項目 | pipe | SharedMemory |
|---|---:|---:|
| 冷啟動 alpha（bind＋載入 .so＋初始化＋第一次 invoke） | 216.6 ms | 187.5 ms |
| 冷啟動 beta（zygote 已存在） | 105.1 ms | 107.8 ms |
| 結果 64 KiB | 2.41 ms | 2.31 ms |
| 結果 512 KiB | 16.46 ms | 16.20 ms |
| 結果 1 MiB | 32.02 ms | 31.90 ms |
| 結果 4 MiB（上限內） | 132.42 ms | 123.67 ms |
| Host → 沙箱 bridge 回覆約 1 MiB | 19.09 ms | 15.90 ms |

- 兩者差距遠小於「兩倍」，**維持預設 pipe**（§5.3 的理由不變）。
- 超過 4 MiB 的結果：兩種方案都在 118 ms 內回 `LIMIT_RESULT`。**修正前會讓 Host 的呼叫永遠等不到結果**：
  `SandboxClient.onResult` 先從 `pending` 取出 future 再解碼，解碼超限丟出例外後 future 永遠不會完成。已改為解碼
  失敗時以 `LIMIT_RESULT`／`BAD_PAYLOAD` 完成，`bridgeCall`／`asyncStart` 的解碼也不再讓例外逃出 binder 執行緒。
  harness 的核心結果上限也改為與傳輸上限一致的 4 MiB。
- 非同步 fetch 正常；無窮迴圈 10 秒（harness 的 invoke 上限）後回 `LIMIT_TIME`。
- 強制殺掉 alpha：alpha 回 `DISCONNECTED`，beta 與 Host 繼續正常。
- PSS：一般沙箱程序約 18.7 MB；剛處理過 1–4 MiB 結果的程序約 95 MB（ART heap 尚未回收），Host 約 133 MB。

### 9.3 對 ADR 的影響

- §4.3：isolated process 不足以阻止產生子程序，**必須在載入腳本前安裝 seccomp 鎖定**（列入 S2 完成標準）。
- §13：隔離探測增列「產生子程序必須失敗、建立執行緒必須成功」；探測不得在沙箱程序內直接 exec。
- §4.2／S1：Host 端對沙箱送來的任何 payload，解碼失敗都必須讓該次呼叫以穩定錯誤完成，不得讓等待者懸置。

