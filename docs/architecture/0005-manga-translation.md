# ADR-0005（草案）：裝置端漫畫翻譯

狀態：草案，實作中（見 §8）。取代 ADR-0004 §1「漫畫不做去字重繪，只做覆蓋式顯示」與 §2「階段 4 不承諾」兩點。
日期：2026-11-15

## 1. 目標與範圍

在閱讀器內翻譯漫畫頁面：找出文字、辨識、翻譯，再把譯文以接近原作的樣式畫回原位置。

- 三個平台（Android、iOS、桌機）都在裝置上完成辨識與回填，不把頁面圖片外送。
- 翻譯沿用 ADR-0004 的翻譯服務：離線引擎、系統翻譯或自備金鑰的供應商，由使用者在翻譯設定選擇。
- 辨識結果與回填後的頁面都留存，同一頁不重複計算。

不做的事：

- 不把頁面圖片上傳到任何伺服器（雲端翻譯只會收到辨識出的文字，與小說翻譯相同）。
- 狀聲詞（畫成美術字的擬聲詞）不翻譯、不重繪，保留原作。
- 第一版不處理畫在圖上的文字（保留原文）；修補需要 LaMa，放第二階段，做成選用下載。

## 2. 已定案的決定

| 項目 | 決定 |
| --- | --- |
| 平台 | Android、iOS、桌機一起 |
| 推論引擎 | ONNX Runtime（Android：`onnxruntime-android`；桌機：`onnxruntime` Java；iOS：`onnxruntime-c` xcframework 的 C API，經 cinterop） |
| 影像處理 | OpenCV（Android：`org.opencv:opencv`；桌機：Java 綁定；iOS：自建只含 core／imgproc／photo 的 xcframework，經 cinterop 呼叫其 Objective-C API） |
| 文字偵測 | `ogkalu/comic-text-and-bubble-detector` 的 `detector-v4-s_int8`（RT-DETR-v2，11 MB，輸入 640×640） |
| 日文 OCR | manga-ocr-2025（`jzhang533/manga-ocr-base-2025`），帶 KV cache 的匯出版；encoder 以 per-channel 動態 int8、decoder 以動態 int8 量化 |
| OCR 分批 | 依偵測順序每 8 框一批（固定分批），保證同一頁每次結果相同 |
| 偵測門檻 | 對話框內文字 0.45、框外文字 0.35，跨類別重疊超過一半時只留分數高者 |
| 翻譯單位 | 整章劇本，一框一行，沿用 `ChapterTranslationRequest` 一行一段的對應 |
| 回填 | 擦除整個對話框內部（固定範圍填滿＋補洞＋閉運算），以局部二次曲面修補底色；排版以原字字級為上限，支援直書、橫書、縱中橫與禁則 |
| 字型 | Noto Sans TC 可變字型（OFL），隨模型包下載 |
| 框外文字修補（第二階段） | `ogkalu/lama-manga-onnx-dynamic`，只量化殘差區塊的動態 int8（64 MB），選用下載 |

## 3. 可行性驗證結果

驗證用的檔案在本機的 `/tmp/mangaocr`、`/tmp/iosspike`（不在版本庫內）。

### 3.1 授權

| 元件 | 授權 |
| --- | --- |
| ONNX Runtime | MIT |
| OpenCV | Apache-2.0 |
| comic-text-and-bubble-detector | Apache-2.0 |
| manga-ocr（kha-white）、manga-ocr-base-2025、KV cache 匯出版 | Apache-2.0 |
| AnimeMangaInpainting（LaMa 漫畫微調）／其 ONNX 匯出 | MIT／Apache-2.0 |
| Noto Sans TC | OFL-1.1 |

### 3.2 效能（Galaxy S24 Ultra，Android 17，release 建置，7 頁 43 個文字框）

| 階段 | 時間 |
| --- | --- |
| 偵測＋OCR（同時進行，各 3 執行緒） | 約 2.2 秒 |
| 擦字＋排版＋畫字 | 約 0.85 秒 |
| 每段框外文字的 LaMa 修補（int8） | 約 0.4～0.9 秒 |

演進：最初 15.6 秒 → KV cache 8.2 秒 → 分批 5.2 秒 → int8 3.6 秒 → 同時進行 2.9 秒 → release 建置 2.2 秒。

### 3.3 iOS

在 iOS 27.1 模擬器（Apple Silicon）上：

- ONNX Runtime 1.30.0 的 C API 可經 cinterop 呼叫；偵測 12 框、OCR 6 框的結果與桌機逐字相同。
- 官方 OpenCV iOS framework 沒有 arm64 模擬器版本，需自行以 `platforms/apple/build_xcframework.py` 編譯（`IPHONEOS_DEPLOYMENT_TARGET=15.0`，只保留 core／imgproc／photo），xcframework 共 40 MB。其 Objective-C API 可經 cinterop 呼叫，連通元件、填滿、距離轉換、膨脹、Telea、k-means、輪廓、縮放的結果與 Python cv2 一致。

### 3.4 品質

- OCR 量化後與 fp32 約 3% 字差，多為「い／し」、符號、拉長音等小差異。
- 偵測器會刻意略過短片假名與狀聲詞風格的文字：合成在畫面上的 8 段旁白中抓到 5 段。
- 動態 int8 的結果會隨同批內容改變，固定分批後可完全重現；靜態量化不受分批影響，但字差升到 5.4%，未採用。

## 4. 架構

所有流程邏輯放在 `commonMain` 的 `dev.shinsou.kmp.manga`，平台只提供三個介面：

- `OnnxRuntime`／`OnnxSession`：載入模型、執行推論。KV cache 等中間張量留在原生記憶體，不在每一步複製回 Kotlin。
- `VisionOps`：流程用到的 OpenCV 功能。
- `PageCodec`：頁面圖片解碼與 JPEG 編碼。

Android 與桌機的 ONNX Runtime、OpenCV 都是同一套 Java API，實作放在 `jvmCommonMain`；iOS 實作放在 `iosMain`。畫字以 Compose 的 `CanvasDrawScope` 與 `TextMeasurer` 在 `ImageBitmap` 上完成，三平台共用。

流程（每章）：

1. 逐頁偵測，找到的文字框依序送 OCR（偵測與 OCR 同時進行）。
2. OCR 結果依圖片內容雜湊存入 `manga_ocr_page`（與翻譯記憶同一個資料庫，不進備份）。
3. 依閱讀順序組成整章劇本，交給 `TranslationService`。
4. 每頁擦字、排版、畫字，存成 JPEG 快取；閱讀器在 `ResolvedReaderPageImage` 改顯示回填後的頁面。

## 5. 模型包

| 包 | 內容 | 大小 |
| --- | --- | --- |
| `vision-v1` | 偵測器、OCR encoder／decoder、詞表、字型 | 約 60 MB |
| `inpaint-v1`（第二階段） | LaMa int8 | 64 MB |

模型不隨 App 發佈，使用時下載並以 SHA-256 核對（沿用 `OfflineModelStore` 的流程）。量化與打包腳本放在 `tools/manga-models/`，可重現。

## 6. 分期

1. `commonMain` 核心與純邏輯測試。
2. Android、桌機實作。
3. 儲存（OCR 快取、頁面快取、模型下載）。
4. 閱讀器 UI 與預先翻譯。
5. iOS 實作。
6. 第二階段：LaMa。

## 7. 風險與未驗證事項

- iOS 實機的效能與記憶體尚未量測（模擬器在 Mac 上執行，不具代表性）。
- Android 的 ONNX Runtime 與 OpenCV 每個 ABI 約 60 MB（x86_64 超過 100 MB），只打包 arm64-v8a；其他 ABI（含模擬器）不提供漫畫翻譯。加入後 APK 約增加 30 MB。
- 桌機的 OpenCV 以 bytedeco 的分平台 jar 打包（只含建置主機的平台），ONNX Runtime 的 jar 內含各平台原生檔，之後可再剔除。
- 狀聲詞與短旁白的偵測取捨：目前選擇不翻譯狀聲詞。

## 8. 實作現況

尚未合併。已完成：

- `commonMain`（`dev.shinsou.kmp.manga`）：偵測、OCR、整章劇本、擦字、排版、畫字、模型下載、OCR 快取、頁面快取與整章流程（`MangaTranslation`）。
- Android、桌機：`jvmCommonMain` 的 ONNX Runtime 與 OpenCV 實作，桌機上以真實模型跑過整頁與整章測試。
- iOS：`ios-vision/scripts/build-ios-vision.sh` 與 Gradle 任務 `buildIosVision`，`mangaOnnx`、`mangaOpenCv` 兩個 cinterop，以及 `IosMangaPlatform`。

- 閱讀器：頁面閱讀器的工具列提供翻譯（`MangaTranslationPanel`），開啟後背景翻譯整章並逐頁替換顯示；各平台在 `ShinsouComposition` 傳入 `mangaPlatform`，原生函式庫在第一次使用時才載入。

- 預先翻譯：作品頁選取章節後可加入 App 共用的翻譯佇列（`TranslationPrefetchJob.run`），完成的章節在閱讀器開啟時直接取用快取。

- 統一閱讀器：圖片章節（`ImageSequence`）的工具列翻譯按鈕開啟同一個面板，頁面經 `imageRenderPages` 載入後翻譯，回填圖以 `LocalTranslatedImagePage` 取代原圖；僅限可保留在裝置上的內容（`offlineStore`），且有閱讀器轉換（`readerTransform`）的頁面不翻譯。

- 統一閱讀器的預先翻譯：擴充來源的章節加入翻譯佇列後，抓下來若是可保留的圖片章節，`prefetchChapter` 回傳帶 `pages` 的 `PrefetchChapter`，佇列改走漫畫流程（日文 → 佇列選的目標語言），作品之後開啟即為翻譯；`MangaTranslation` 以 `LocalMangaTranslation` 提供給作品頁。

- 已翻譯標記：整章劇本經 `ReaderTranslation` 以同一組 `workKey`／`chapterKey` 翻譯並存入翻譯記憶，因此擴充來源作品頁的「已翻譯」標記與佇列的「已翻譯就略過」直接涵蓋漫畫章節；頁面閱讀器的章節列表（`MangaDetailScreen`）也以 `translatedChapterKeys` 標記。畫好的頁面若被頁面快取淘汰，開啟時會以快取的 OCR 結果與譯文重畫。

- 第二階段 LaMa：`inpaint-v1` 為選用下載（面板「畫在圖上的文字」一節，可下載或移除）。裝了之後，偵測為框外文字（`FREE_TEXT`）且背景不平的區塊，由 `MangaEraser` 以 k-means 找出字與描邊的遮罩、`MangaInpainter` 以 LaMa 重繪底圖（框外加 64 px 參考範圍、反射補到 8 的倍數），譯文以原文的字色與描邊色畫回。用 LaMa 畫的頁面在頁面快取中另有鍵（`MangaRenderCache.key(…, inpaint)`），裝上或移除後已讀過的頁面會重畫（OCR 結果沿用快取）。換模型時，進行中的章節繼續用原本的管線，最後一個使用者結束後才關閉。桌機 7 頁合成測試頁（框外文字 7 處）全部重繪；iOS 模擬器上一頁（2 處）約 3.3 秒。

- LaMa 實機（SM-S9280，發佈用的 debug 版，`MangaLamaDeviceTest`）：7 頁合成測試頁中框外文字 6 處全部重繪，每處約多 0.73 秒，第一次載入模型約 1.5 秒。LaMa 的輸入大小每次不同，ONNX Runtime 的記憶體池會為每種大小各留一份而一路增長（原生堆積峰值 908 MB），因此 LaMa 的 session 關閉記憶體池與記憶體規劃（`OnnxRuntime.open(…, variableShapes = true)`），峰值降到 620 MB、慢約 7%。實機上也修正了 Android 的描邊字：未指定 `drawStyle` 的填色會沿用上一次的描邊，畫成白色色塊，現在明確指定 `Fill`。

- 模型包已發佈於 GitHub Release `manga-models-v1`（不設為 Latest，不影響 App 版本發佈）：`vision-v1` 與 `inpaint-v1` 的附件與 App 內釘死的大小、SHA-256 一致，另附 Noto 字型的 OFL 授權全文與 `manifest.json`；`MangaModelReleaseTest`（`SHINSOU_MANGA_RELEASE=1`）以 App 的下載流程實際下載並核對通過。

- 實機安裝（SM-S9280，覆蓋安裝本機 debug 版、匯入 7 頁 CBZ 以統一閱讀器開啟）：模型包從 Release 下載約 5 秒；離線翻譯（ja→en→zh-Hant，105 MB）下載後，7 頁的辨識、翻譯、回填約 7 秒；切換語言時已畫好的頁面直接取自快取。實測發現並修正：面板在離線翻譯模型未下載時只顯示英文錯誤，改為與小說面板相同，顯示大小與「下載模型」；離線引擎對狀聲／呻吟句會輸出無止境的重複，排版縮到極小字，現在連續重複超過三次的字或短句會截成三次，仍比原文長三倍以上的譯文不畫（保留原文）；排不進區塊時還原原圖而非留下空白對話框。`LAYOUT_VERSION` 升為 3。

- iPhone 實機（iPhone 17 Pro Max，Xcode Debug 版覆蓋安裝）：模型包下載約 5 秒；34 頁章節（來源頁面邊讀邊下載）辨識數分鐘、回填 34 頁約 10 秒。實測發現並修正：iOS 的 Skia 沒有 JPEG 解碼器（模擬器上連原始 JPEG 也解不開），閱讀器顯示回填頁時「載入失敗」——快取仍存 JPEG，交給閱讀器前由 `PageCodec.forDisplay` 轉成 iOS 可顯示的 PNG（`IosDrawnPageDisplayTest`）；使用系統翻譯（Apple 翻譯）且語言未下載時面板只有英文錯誤，改為與小說面板相同的「下載語言」。

- 依裝置調校（`MangaDeviceProfile`／`MangaTuning`）：各平台回報效能核心、節能核心、記憶體與省電／過熱（Android 依 cpufreq 分大小核，Apple 用 `sysctl hw.perflevel*`）。實測 OCR 逐字解碼，單一辨識器加執行緒幾乎無效（M4 Pro 上 4 條最好、8 條反而慢），改為多個辨識器（各 2 條執行緒）同時處理不同頁，結果與單一辨識器完全相同；偵測固定 2 條；同一組工作者也並行回填（每個有自己的排版與繪字物件）。工作者數 = 全部核心 ÷ 2，上限 3，記憶體 <4 GB 為 1、<8 GB 為 2；省電或過熱時核心以三分之一計。頁面最多同時下載並解碼 2～6 頁，依頁序交給偵測。7 頁整章（舊設定 → 新調校）：Mac M4 Pro 1555 → 930 ms（每頁 300 ms 網路延遲時 3268 → 1257 ms）；SM-S9280 5553 → 3196 ms（7185 → 3622 ms）；iPhone 17 Pro Max 5659 → 3558 ms。
- 硬體加速（`Accelerator`：CoreML、NNAPI、XNNPACK，建立失敗退回 CPU）三平台實測都不採用：Mac 的 CoreML 讓 OCR 慢一倍、偵測不變但載入 8 秒；Android 的 NNAPI 偵測只快 1.17 倍、OCR 變慢且文字不一致、LaMa 慢一倍，XNNPACK 全部較慢；iPhone 的 CoreML 讓 OCR 變慢且文字不一致，LaMa 在 CoreML 上會讓 App 被系統結束。fp32 版本同樣沒有好處，因此不另做模型包。量測工具：`MangaBenchmarkDesktopTest`（`SHINSOU_MANGA_BENCH=1`）、`MangaBenchmarkDeviceTest`（Android，`-e shinsouMangaBench true`）、iOS 以 `SHINSOU_MANGA_BENCH_DIR` 啟動 App（`IosMangaBenchmark.kt`，結果印在 console）。開發者模式的「顯示載入耗時」會在面板顯示調校結果與上一章各階段耗時。

- iOS 的 GPU 偵測（`vision-coreml-v1`，RT-DETRv2 R50 轉 Core ML fp16）：`PageDetector` 介面下的 `IosCoreMlDetector`，GPU 不可用時退回 CPU 的 v4-s；快取依偵測器區分。Release 版 iPhone 17 Pro Max 整章 1920 → 1555 ms，並多找到畫在圖上的文字。研究與量測見 `docs/research/manga-gpu-npu.md`。

尚未完成：離線引擎會把部分狀聲詞原樣（日文）輸出，這些區塊會畫回日文；偵測框沒框到的字（例如句尾一字）會留在圖上；辨識管線（偵測與 OCR）在第一頁後原生記憶體會再長約 140 MB，之後持平。
