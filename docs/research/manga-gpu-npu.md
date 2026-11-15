# 漫畫辨識的 GPU／NPU 加速研究

日期：2026-11-16。背景：[ADR-0005](../architecture/0005-manga-translation.md) §8 的 CPU 調校完成後，評估能否再把模型放上 GPU／NPU（神經網路引擎）。

## 結論

1. **透過 ONNX Runtime 的加速器這條路，對現有模型全部無效**，已在三個平台實測（見下表）。原因是模型結構，而不是設定：
   - OCR 的時間主要在逐字解碼的 decoder（一批 8 句中 encoder 只占約 32 ms），每一步運算很小，在 CPU 與加速器間來回的成本大於加速。
   - 偵測器在 CoreML MLProgram 下無法編譯（`max_pool2d`）；OCR encoder 無法建立模型。
   - LaMa 用了 FFT（快速傅立葉轉換），CoreML／NNAPI 不支援，被切成碎片在 CPU 與加速器間來回，比 CPU 慢。
2. **值得加速的只有偵測器。** CPU 調校後，手機上偵測成了辨識階段的瓶頸（iPhone 每頁 125 ms、SM-S9280 約 180～230 ms，而 3 個 OCR 工作者平均每頁不到 110 ms）。
3. **最有希望的方案：把偵測器直接轉成原生 Core ML（Apple 平台）**，不經 ONNX Runtime。Android 的 NPU（高通 QNN）可行性較低、需要實驗。
4. 回填（擦字、排版、繪字、JPEG 編碼）在手機上約占整章一半時間，屬 CPU 程式碼，GPU／NPU 幫不上；若要再加速，應另外最佳化這段程式。

## 實測：ONNX Runtime 加速器

| 平台 | 加速器 | 偵測 | OCR | LaMa |
|---|---|---|---|---|
| Mac M4 Pro | CoreML（預設格式） | 速度相同（多半退回 CPU），載入 8 秒 | 慢一倍，文字有差異 | — |
| Mac M4 Pro | CoreML MLProgram（全部／GPU／ANE） | 無法編譯 | encoder 無法建立 | int8：2.5／2.2／0.87 秒；fp32：1.06／1.0／0.64 秒（CPU 0.61 秒）；首次編譯 20～32 秒 |
| Mac M4 Pro | XNNPACK | 略慢 | 略慢 | — |
| SM-S9280 | NNAPI | 快 1.17 倍（未達 1.3 倍門檻） | 變慢，44 句中 10 句不同 | 慢一倍 |
| SM-S9280 | XNNPACK | 較慢 | 較慢 | 較慢 |
| iPhone 17 Pro Max | CoreML（預設格式） | 速度相同，載入 6.7 秒 | 變慢，文字有差異 | App 被系統結束 |

fp32 原始模型的結果相同，所以問題不在 int8 量化。

## 方案比較

| 方案 | 平台 | 對象 | 預期效益 | 主要風險／成本 |
|---|---|---|---|---|
| **原生 Core ML（coremltools，fp16，固定 640）** | iOS、macOS | 偵測器 | ANE／GPU 上預期每頁幾十 ms（iPhone 現為 125 ms） | 小型 v4-s 只有 int8 公開版，需把權重反量化回 fp32 再轉換，或改用較大的公開 fp32 版；grid_sample（可變形注意力）在 ANE 上可能退回 GPU；需新增 Core ML 執行路徑（Swift／Obj-C 互通）；模型包增加約 20 MB |
| 高通 QNN（HTP NPU） | Android（Snapdragon） | 偵測器 | 若完整跑在 NPU，預期數倍 | 需靜態量化（QDQ）模型與校正資料；grid_sample 在 HTP 的支援與量化後準確度未知；只適用 Snapdragon；QNN 執行庫使 APK 增加數十 MB |
| LiteRT（TFLite）GPU 委派 | Android | 偵測器 | GPU 上數倍 | 需 ONNX→TFLite 轉換；grid_sample 非內建運算，可能需自訂運算；第二套推論引擎 |
| DirectML | Windows | 全部 | 視顯示卡而定 | ONNX Runtime 的 Java 套件沒有 DirectML，需自行建置 |
| 原生 Core ML 的 LaMa | iOS、macOS | LaMa | 每區塊可能從 0.6～0.9 秒降到 0.1～0.2 秒 | FFT 需 iOS 18／macOS 15 以上的 Core ML 才支援，舊系統要保留 CPU 路徑 |

## 建議

1. 先做 **Apple 平台的原生 Core ML 偵測器原型**：把 v4-s 的 int8 權重反量化成 fp32 → PyTorch 載入 → coremltools 轉 fp16 mlpackage，在 iPhone 與 Mac 上量速度與偵測框一致性（IoU）。快 1.3 倍以上且框一致才往下做產品整合（新的模型包需經同意再上傳）。
2. Android 先維持 CPU；若要試 NPU，用獨立的探測 App 驗證 QNN 能否完整跑偵測器，再決定。
3. 回填階段的 CPU 最佳化可能比 GPU／NPU 更划算，可另外評估。

## 原型：原生 Core ML 偵測器（2026-11-16）

- 模型：作者公開的 fp32 權重只有 RT-DETRv2 **R50 大型版**（`model.safetensors`），現用的 v4-s 是 HGNetV2 架構且只有 int8 版，還原成本高，因此原型改用 R50。以 transformers 載入（`anchor_image_size=[640,640]` 讓錨點預先算好），coremltools 9 轉成 fp16 MLProgram（固定 640 輸入，輸出 sigmoid 分數與正規化框），82 MB。轉換需 Python 3.13、torch 2.7、numpy < 2.2。腳本在 `/tmp/coreml-proto/scripts`（`convert_r50.py`、`compare.py`、`compare_models.py`）。
- 與 PyTorch fp32 原始模型一致性（7 頁、集合配對）：GPU 與「全部單元」87 個框全部對上、類別相同、平均 IoU 0.996／0.995；ANE 雖全部對上但平均 IoU 只有 0.855 且多出 3 個框（fp16 精度不足）。
- 速度（每頁，不含前處理）：

| 裝置 | 現用 v4-s int8（CPU） | R50 Core ML GPU | R50 全部單元 | R50 ANE | R50 只用 CPU |
|---|---|---|---|---|---|
| Mac M4 Pro | 54 ms | 31.7 ms | 27.5 ms | 31.6 ms | 55 ms |
| iPhone 17 Pro Max | 125 ms | **29 ms**（載入 0.27 秒） | 27 ms（載入 4 秒） | 27 ms（載入 3.9 秒） | 72 ms |

  iPhone 上首次編譯 mlpackage 只要 0.12 秒。建議用 **GPU**：與原始模型一致、載入快，偵測快 4.3 倍。
- 與 v4-s 的偵測差異（14 頁，同門檻）：對話框 68／70、對話框內文字 40／42（框的切法略有不同）；**畫在圖上的文字 7／20**。R50 補上了 v4-s 漏掉的圖上文字（例如合成頁的「いつもの場所で」）與被截掉的句尾字（「静かな夜だった」的「た」），也會框到頁碼與部分狀聲詞。

### 產品化（2026-11-16，iOS）
- `vision-coreml-v1` 模型包（`tools/manga-models/convert_coreml_detector.py`），Apple 裝置首次下載辨識模型時一併下載；已裝辨識模型者在面板的「偵測加速」選用下載。
- `PageDetector` 介面：ONNX（`MangaDetector`，id `v4s`）與 iOS 的 `IosCoreMlDetector`（id `coreml-r50`，`MLComputeUnitsCPUAndGPU`）。輸出依步長讀取（GPU 會把每列補齊，例如 3 個分數占 32 個位置），fp32／fp16 皆可。載入時以灰色測試圖試跑一次，輸出全為 0（模擬器的 GPU）就拒用、改用 CPU 偵測器。
- 辨識結果與回填頁的快取依偵測器分開（預設偵測器沿用原本的鍵）。
- **Release 版實測（iPhone 17 Pro Max，7 頁）**：新調校＋CPU 偵測 1920 ms（辨識 1305 ms、45 個文字區塊）→ 新調校＋GPU 偵測 **1555 ms**（辨識 944 ms、49 個文字區塊），整章快 1.23 倍且多找到文字。Debug 版因 Kotlin/Native 未最佳化，逐像素處理拖慢，偵測的差異被淹沒（3.7～3.9 秒）；先前 iPhone 上的數字都是 Debug 版。
- macOS 桌機（JVM）尚未接 Core ML；Android、Windows 維持 v4-s。

## Android 高通 NPU（QNN HTP）實測（2026-11-16）

裝置 SM-S9280（Snapdragon 8 Gen 3，SM8650，HTP v75）；獨立探測 App（`/tmp/mangaocr/android` 的 `QnnBenchActivity`，Release 版，`com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0`）。模型為 R50 偵測器自行匯出的 fp32 ONNX（固定 640、無後處理，`/tmp/qnn-proto/scripts/export_and_quantize.py`），7 頁，與 CPU fp32 結果比對。

| 跑法 | 每頁 | 建立 session | 精準度（對 CPU fp32） |
|---|---|---|---|
| CPU（ONNX Runtime，4 條執行緒） | 892～977 ms | 0.3～0.5 秒 | 基準（93 個框） |
| **HTP，fp32 模型（NPU 以 fp16 執行）** | **52～55 ms** | 首次 31 秒；**context 快取後 0.27 秒** | **93／93，類別全同** |
| HTP，只量化骨幹（A16W8） | 58 ms | 35 秒 | 92／93 |
| QNN GPU 後端 | 934 ms | 0.3 秒 | 93／93（大多退回 CPU，無加速） |

- **全模型靜態量化（QDQ）會讓 RT-DETR 崩潰**：A8W8 在 197 個框中一個都沒對上，A16W8 什麼都偵測不到（分數、框回歸與可變形注意力的取樣座標對量化敏感）。只量化骨幹可保持精度（193／197），但在 HTP 上沒有更快，因此**不需要量化**，直接用 fp32 模型。
- **context 快取**（`ep.context_enable`）：首次在裝置上編譯約 31 秒，產生約 93 MB 的快取檔（綁定 HTP 架構與 QNN 版本），之後載入只要 0.27 秒。
- 與現況比較：手機上目前的 v4-s int8 在 CPU 每頁約 180～230 ms；R50 在 NPU 每頁約 54 ms，**快 3.5～4.4 倍**，且是召回較好的大模型。耗電依文獻，NPU 約為 CPU 的 1/4～1/6。

### 產品化的成本與風險
- **APK 大小**：QNN 執行庫未壓縮約 190 MB（裝置上編譯用的 `libQnnHtpPrepare.so` 84 MB、各代 HTP 驅動 9～17 MB、GPU／DSP 後端不需要），探測 App 的 APK 為 85 MB。可只保留 v73／v75／v79／v81（8 Gen 2 以後）與 HtpPrepare，或改為預先在電腦上為各代 HTP 編好 context 檔、依裝置下載（可省掉 84 MB 的編譯器，但模型包要分代、並綁定 QNN 版本）。
- **ONNX Runtime 版本**：微軟的 QNN 套件最新為 1.29，正式 App 用 1.32；需降版，或改用高通的外掛版（`com.qualcomm.qti:onnxruntime-android-qnn` 2.x，搭配 onnxruntime-android 1.30）。
- **只適用 Snapdragon**：聯發科、Exynos、Tensor 等手機仍用 CPU。
- **首次準備**：每台裝置第一次要編譯約 30 秒（可在下載模型後的「準備中」進行）。
- 模型：Android 也改用 R50，可與 iOS 共用同一個偵測器結構（輸出格式相同，後處理沿用 `MangaDetector.fromQueries`）。

### 精簡打包評估（2026-11-16）

QNN 執行庫在 APK 中的大小（壓縮後，即下載量）：編譯器 `libQnnHtpPrepare.so` 34 MB；各代 HTP 驅動（Skel）每代 3～4 MB；GPU／DSP 後端約 4 MB（不需要）；合計 62.9 MB。實機驗證（探測 App，SM8650）：

- **不含編譯器也能執行預編快取**：排除編譯器、GPU／DSP 與 v68／v69 後，APK 從 85 MB 降到 40.5 MB（QNN 庫壓縮後 18.1 MB），載入快取 0.27 秒、每頁 54 ms、93／93 全對。
- **QNN 庫可以事後放進 App 資料夾再載入**（targetSdk 36）：APK 完全不含 QNN 庫（22.4 MB），把 `libQnnSystem`、`libQnnHtp`、`libQnnHtpV75Stub`／`Skel`、`libQnnHtpPrepare` 放到 `filesDir`，依序 `System.load`，`backend_path` 用絕對路徑並把 `ADSP_LIBRARY_PATH` 指向該資料夾（DSP 讀得到驅動）。載入快取（0.26 秒、50 ms／頁）與裝置上編譯（31 秒、54 ms／頁）都成功，93／93 全對。
- **授權**：QNN 執行庫的 Maven 套件標示為 Qualcomm 授權（[Maven POM](https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/2.51.0/qnn-runtime-2.51.0.pom)），一般解讀為只能以目的碼形式「隨 App 一起」散布、不得單獨散布，且高通可終止授權（[DroidRunner #82](https://github.com/m96-chan/DroidRunner/issues/82)、[HexAnima](https://github.com/ayanoby/hexanima)）。Shinsou X 是 MIT 授權，打包進 APK 沒有 GPL 衝突；但把 QNN 庫**放到自己的 Release 供下載**屬單獨散布，有風險。替代做法是讓 App 在執行時**直接從 Maven Central 下載官方的 `qnn-runtime` AAR**（63 MB）並取出需要的檔案，散布者仍是高通。

| 方案 | APK 增量（壓縮） | 使用者額外下載 | 首次準備 | 適用範圍 | 授權 | 維護 |
|---|---|---|---|---|---|---|
| A：打包進 APK，保留編譯器、只留 v73 以後 | 約 +45 MB（全部裝置都要下載） | R50 模型（fp32 172 MB；fp16 約 86 MB） | 編譯 31 秒 | Snapdragon 8 Gen 2 以後 | 安全 | 低 |
| B：打包進 APK，不含編譯器，下載預編快取 | 約 +13 MB | 該代快取約 93 MB | 0.3 秒 | 只限有預編的晶片世代 | 安全 | 高：每代、每個 QNN 版本都要在 Linux／Windows 上用 QAIRT 離線產生 |
| C：APK 不含，從自己的 Release 下載 QNN 庫 | 0 | QNN 庫約 40 MB＋模型 | 31 秒 | 全部 Snapdragon | **有風險**（單獨散布） | 中 |
| **C′：APK 不含，執行時從 Maven Central 下載官方 AAR** | 0 | AAR 63 MB（取出約 106 MB）＋模型 | 31 秒 | 全部 Snapdragon | 較安全（官方管道） | 中：釘選 AAR 版本與 SHA-256 |

共同前提：正式 App 的 ONNX Runtime 要換成含 QNN EP 的版本（微軟 `onnxruntime-android-qnn` 目前最新 1.29，正式 App 為 1.32，需降版並重跑全部測試；或改用高通的外掛版 `com.qualcomm.qti:onnxruntime-android-qnn` 2.x）。非 Snapdragon 的手機不下載任何東西，維持 CPU。

**建議**：C′。APK 不變、非 Snapdragon 手機零成本、授權風險最低、所有 Snapdragon 世代通用；模型可改用 fp16 ONNX 減半下載量（待驗證）。若想先求簡單，A 是最穩的起點，但每位 Android 使用者的 APK 都會多約 45 MB。

## 耗電、速率、精準度與方案價值（2026-11-16）

### 公開的能效實測（非本 App 場景）
- **Apple ANE vs GPU**：M1 上 ANE 的能源效率是 GPU 的 2～14 倍（大型矩陣運算 ANE 4.4 W、GPU 32.5 W；ResNet 類卷積差距最大達 14.5 倍），閒置時 ANE 幾乎不耗電（[arXiv 2606.22283](https://arxiv.org/html/2606.22283v1)）。Core ML 影像分類在 M5 Max 上：ANE 每張 38 mJ、8.9 W，GPU 每張 46 mJ、44 W；扣掉驅動的 CPU 開銷後 ANE 約省一半（[coreml-compute-placement](https://github.com/erwinzhang7/coreml-compute-placement)）。iPhone 上 `.cpuAndNeuralEngine` 被 Xcode 標為低耗電、`.cpuAndGPU` 為高耗電（[dev.to TTS](https://dev.to/software_mvp-factory/wiring-ios-core-ml-to-a-quantized-on-device-speech-synthesis-model-for-real-time-tts-6fa)、[3nsofts](https://3nsofts.com/insights/on-device-ai-performance-benchmarks)）；持續負載下 iPhone 17 Pro 的 ANE 保有 67% 速度、GPU 只剩 38%（降頻）（[dev.to](https://dev.to/john-rocky/iphone-on-device-llm-the-gpu-wins-the-sprint-the-neural-engine-wins-the-marathon-42lo)）。iOS 沒有各元件的耗電 API，iPhone 上的嚴謹數據很少。
- **手機 NPU vs CPU vs GPU**：Pixel 7 Pro 上 BERT 每次推論 NPU 0.3 J、CPU 1.7 J、GPU 9.0 J，GPU 反而最耗電；運算不被 NPU 支援時要切成數十段在 CPU／NPU 間來回，可能比 GPU 還慢（[arXiv 2308.14363](https://arxiv.org/pdf/2308.14363)）。Snapdragon X Elite 上 Hexagon NPU 每次查詢 315 J、CPU 1251 J、Adreno GPU 2051 J（[arXiv 2606.11257](https://arxiv.org/abs/2606.11257)）。Galaxy S24 Ultra 持續推論約 15% 降頻（[arXiv 2603.23640](https://arxiv.org/pdf/2603.23640)）。
- **CPU 執行緒數**：最佳執行緒數依裝置而異，超過可用核心會明顯變差（[Smart at what cost?, IMC 2021](https://arxiv.org/abs/2109.13963)）；多數運算加執行緒是次線性加速，加太多會多耗電（[arXiv 2210.02620](https://arxiv.org/pdf/2210.02620)）；功率略增但大幅縮短時間時，每幀能耗反而下降（race to idle，[arXiv 2503.21109](https://arxiv.org/html/2503.21109v1)）。

### 方案價值

本 App 的情境：偵測每頁只要 0.03～0.2 秒，一章的辨識多在數秒內，與閱讀時亮螢幕的耗電相比很小；耗電差異主要影響「預先翻譯大量章節」。

| 方案 | 平台 | 速率（實測） | 精準度（實測） | 耗電（文獻推估） | 成本 | 價值 |
|---|---|---|---|---|---|---|
| **CPU 調校**（已完成） | 全部 | 整章快 1.6～2.6 倍 | 與原本完全相同 | 功率略增、時間大減；OCR 多工作者接近線性加速，每頁能耗應持平或略降 | 已完成 | **高** |
| **Core ML GPU 偵測器**（R50） | iOS（macOS 需橋接） | iPhone 偵測快 4.3 倍（125→29 ms） | 與 fp32 原始模型一致（IoU 0.996），圖上文字召回更好 | GPU 功率高但只跑 0.03 秒／頁；每次推論能耗約為 ANE 的 1.2～2 倍；持續負載易降頻 | 模型包 +82 MB、新執行路徑 | **高（iPhone）** |
| Core ML ANE 偵測器 | iOS | 與 GPU 相同（27 ms），首次載入約 4 秒 | 較差（IoU 0.855、多出框），fp16 精度不足 | **最省電**：約為 GPU 的 1/2～1/14，持續負載不易降頻 | 同上，加上需處理精度（混合精度或校正） | 中（精度問題解決後最好） |
| **高通 NPU（QNN HTP）** | Android（Snapdragon） | 實測 R50 每頁 54 ms（現況 CPU 180～230 ms，快 3.5～4.4 倍） | fp32 交給 NPU 與 CPU 完全一致（93／93）；全量化會崩潰，不需量化 | 文獻：比 CPU 省 4～6 倍 | APK 大幅增加（QNN 庫約 190 MB 未壓縮）、ORT 版本、只限 Snapdragon、首次編譯 30 秒 | **中高**（效益確定，成本在 APK 大小） |
| Android GPU（LiteRT／QNN GPU） | Android | 未實測 | fp16，大致可保持 | 文獻：手機 GPU 跑 transformer 常比 CPU 更耗電 | 第二套推論引擎、轉換 | 低 |
| ONNX Runtime 加速器（CoreML／NNAPI／XNNPACK 原樣） | 全部 | 實測無加速或更慢 | NNAPI／CoreML 的 OCR 文字有差異 | 切碎來回反而多耗電 | — | **無** |
| LaMa 上 GPU／NPU | 全部 | 實測更慢（FFT 不支援） | — | — | 需新版系統或改模型 | 無（目前） |

### 建議
1. **iOS 產品化 Core ML GPU 偵測器**：速度與召回的提升最確定，精度與原始模型一致；耗電因每頁只跑 0.03 秒，影響很小。
2. **ANE 作為省電選項再研究**：若能以混合精度（敏感層保留 fp32）解決框不準的問題，大量預先翻譯時可省最多電。
3. Android 先維持 CPU 調校；若要追求省電與速度，再以獨立原型評估 QNN。
4. 若要確認本 App 的實際耗電，可在 Mac 用 `macmon`／`powermetrics`、iPhone 用 Instruments 的 Power Profiler、Android 拔線改用無線 adb 讀電池電流來實測。

## 量測工具

- `MangaAcceleratorResearchTest`（桌機）：各模型單獨在 CPU 與 CoreML MLProgram（全部／GPU／ANE）上的速度與輸出差異，需 `SHINSOU_MANGA_BENCH=1`、`SHINSOU_MANGA_PACKS`、`SHINSOU_MANGA_FP32`。
- `MangaBenchmarkDesktopTest`、`MangaBenchmarkDeviceTest`、iOS 的 `IosMangaBenchmark.kt`：見 `tools/manga-models/README.md`。

## 參考資料

- ONNX Runtime CoreML EP：https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html
- ONNX Runtime Java API（`addCoreML` 的選項）：https://onnxruntime.ai/docs/api/java/index-all.html
- CoreML 實務（MLProgram 與運算單位）：https://egordmitriev.dev/blog/2026-05-18-optimizing-samurai-part-3
- QNN EP 文件：https://github.com/onnxruntime/onnxruntime-qnn/blob/main/docs/execution_providers/QNN-ExecutionProvider.md
- QNN HTP fp16 準確度問題：https://github.com/onnxruntime/onnxruntime-qnn/issues/974
- 偵測器設定（RT-DETRv2）：https://huggingface.co/ogkalu/comic-text-and-bubble-detector

## 產品化：翻譯設定中的 CPU／GPU／NPU（2026-11-16）

- 「設定 → 譯文 → 漫畫文字偵測」列出 CPU、GPU、NPU，可多選；多選時依權重（CPU 1、GPU／NPU 4）固定分配頁面（`assignPages`），同一組選擇每次分配相同，快取可命中。說明只用「較快／較省電／較慢／通用」等字眼，不標示測得的速率。
- Android NPU：只在 Snapdragon 8 Gen 2 以後（HTP v73+，`QnnSoc`）提供。下載 `vision-qnn-v1`（fp16 R50）與 Maven Central 的 `qnn-runtime-2.42.0.aar`（驗 SHA-256，只保留該世代 5 個 .so、設為唯讀，刪除 AAR）。APK 不含任何 `libQnn*`。
  - Manifest 需宣告 `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`，否則 Stub 載入失敗（`libcdsprpc.so not found`）。
  - 首次準備（編譯 context）在 SM-S9280 約 32 秒，快取存在 `files/manga/qnn-context`（約 93 MB），移除 NPU 時一併刪除。
- SM-S9280 整章 7 頁（假翻譯）：CPU 3.4–3.6 s（辨識 2.1–2.2 s）；NPU 3.0 s（辨識 1.65 s）；CPU+NPU 3.1–3.2 s（v4s:1 qnn-r50:6）。整章瓶頸在 OCR 與回填，NPU 主要價值是省電與找到更多框外文字；CPU+NPU 並未比單用 NPU 快。

## macOS（Apple silicon）可行性（2026-11-16，M4 Pro）

桌機是 JVM，無法直接呼叫 Core ML；改用 ONNX Runtime 1.32 內建的 CoreML EP（Java `addCoreML`，不需原生橋接），直接跑 Android NPU 用的 `vision-qnn-v1`（R50 fp16 ONNX）：

| 執行方式 | 每頁 | 與 CPU fp32 的框一致（7 頁 99 框，IoU>0.9） |
|---|---|---|
| 現用 v4-s int8（CPU） | 54 ms | — |
| R50 CPU | 146 ms | 基準 |
| R50 CoreML EP GPU（`CPUAndGPU`） | 36–41 ms | 97/99，平均 IoU 0.975 |
| R50 CoreML EP ANE（`CPUAndNeuralEngine`） | 110 ms | 88/99，平均 IoU 0.90，多 2 框 |

- 1006 個節點中 994 個由 CoreML 執行（8 個分區）。
- 首次載入約 7 秒；設 `ModelCacheDirectory` 後第二次 0.1 秒，但快取約 251 MB。
- 結論：Mac 上 GPU 可行（比現用 CPU 偵測器快約 1.4 倍、與完整模型一致）；ANE 經 EP 反而比 CPU 慢且較不準，不提供。Intel Mac 只有 CPU。
