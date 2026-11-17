# 漫畫翻譯模型包

依 [ADR-0005](../../docs/architecture/0005-manga-translation.md) §5 從公開來源產生模型包：

```bash
python3 -m venv .venv && .venv/bin/pip install onnx "onnxruntime==1.32.*" numpy
.venv/bin/python -I tools/manga-models/build_packs.py /tmp/manga-packs --with-inpaint
```

輸出 `vision-v1/`（偵測器、OCR encoder／decoder、詞表、字型）、`inpaint-v1/`（LaMa int8），以及列出每個檔案大小與
SHA-256 的 `manifest.json`。App 內的模型目錄以這些雜湊釘住版本；換模型或換量化設定時，要一併更新目錄與 manifest。

## 中文、英文辨識與簡體字型（`--with-ocr`）

PaddleOCR PP-OCRv5 mobile 由 paddle2onnx（opset 14）轉成 ONNX，另需 `pip install "paddlepaddle==3.3.*" "paddle2onnx==2.1.*" pyyaml`：

```bash
.venv/bin/python -I tools/manga-models/build_packs.py /tmp/manga-packs --with-ocr   # 連同 vision-v1
.venv/bin/python -I tools/manga-models/build_packs.py /tmp/manga-packs --ocr-only   # 只產生以下四包
```

| 包 | 檔案 | 來源 |
|---|---|---|
| `ocr-lines-v1` | `lines.onnx`（4.8 MB） | PP-OCRv5_mobile_det（區塊內的行偵測，中英共用） |
| `ocr-zh-v1` | `rec.onnx`（16.5 MB）、`chars.txt` | PP-OCRv5_mobile_rec（簡繁中） |
| `ocr-en-v1` | `rec.onnx`（7.9 MB）、`chars.txt` | en_PP-OCRv5_mobile_rec |
| `font-sc-v1` | `NotoSansSC-VF.ttf`（17.8 MB） | Google Fonts（譯成簡體時使用） |

`chars.txt` 取自模型 `inference.yml` 的 `PostProcess.character_dict`，一行一字、無結尾換行；空白類別與空格由 App 補上。

## 發佈

App 從 GitHub Release `manga-models-v1`（`MangaModelCatalog.BASE_URL`）下載，附件檔名為 `<包名>-<檔名>`，例如
`vision-v1-detector.onnx`、`inpaint-v1-lama.onnx`。`MangaModelCatalog` 內的大小與 SHA-256 必須與 `manifest.json` 一致。

## 在 Android 實機上測 LaMa

`MangaLamaDeviceTest` 把含框外文字的頁面分別以有、無 `inpaint-v1` 畫一次，並記錄時間與原生記憶體。測試使用獨立的
application id（`.sandboxtest`），不會動到手機上已安裝的 Shinsou：

```bash
./gradlew -PshinsouSandboxDeviceTest=true :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
adb install -r -t composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb install -r -t composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
adb shell mkdir -p /data/local/tmp/manga-device/pages
adb push <packs>/vision-v1 <packs>/inpaint-v1 /data/local/tmp/manga-device/
adb push <pages>/*.jpg /data/local/tmp/manga-device/pages/
adb shell chmod -R a+rX /data/local/tmp/manga-device
adb shell run-as dev.aluo.shinsoux.sandboxtest sh -c "'mkdir -p files && cp -r /data/local/tmp/manga-device files/'"
adb shell am instrument -w -e shinsouMangaDevice true -e class dev.shinsou.kmp.manga.MangaLamaDeviceTest dev.aluo.shinsoux.sandboxtest.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s MangaLamaDevice:I
```

用 LaMa 畫好的頁面在 App 的 `files/manga-device/out`，可用 `adb exec-out run-as dev.aluo.shinsoux.sandboxtest cat files/manga-device/out/<頁面>-lama.jpg > out.jpg` 取出。

## 量測辨識速度

- 桌機：`SHINSOU_MANGA_BENCH=1 SHINSOU_MANGA_PACKS=<packs> SHINSOU_MANGA_ART=<頁面資料夾> ./gradlew :composeApp:desktopTest --rerun --tests 'dev.shinsou.kmp.manga.MangaBenchmarkDesktopTest'`。`SHINSOU_MANGA_FP32` 指向原始 fp32 模型時一併比較；`SHINSOU_MANGA_BENCH_SPLITS=2x3,4x2`（偵測執行緒 x OCR 工作者）與 `SHINSOU_MANGA_BENCH_DELAYS=0,300`（每頁下載延遲 ms）可調整比較組合。
- Android：照上一節推送檔案（另把頁面放在 `files/manga-device/pages`，fp32 模型放在 `files/manga-device/fp32`），以 `-e shinsouMangaBench true -e class dev.shinsou.kmp.manga.MangaBenchmarkDeviceTest` 執行，結果在 `adb logcat -s MangaBench`；`-e splits 2x3 -e delays 0` 調整組合。
- iOS 實機：把頁面複製到 App 的 `Documents/bench`，再以環境變數啟動並讀 console：

```bash
xcrun devicectl device process launch --device <UDID> --terminate-existing --console --environment-variables '{"SHINSOU_MANGA_BENCH_DIR":"bench"}' dev.aluo.shinsoux
```

  `SHINSOU_MANGA_BENCH_SPLITS=2x3x2`（偵測 x 工作者 x 每個工作者的執行緒）加入比較組合，`SHINSOU_MANGA_BENCH_ACCEL=1` 另比較 CoreML（LaMa 在 CoreML 上可能讓 App 被系統結束）。

## Core ML 偵測器（Apple 裝置的 GPU）

`convert_coreml_detector.py` 把 RT-DETRv2 R50 轉成 Core ML（fp16、固定 640 輸入），輸出到 `<OUT>/vision-coreml-v1/detector.mlpackage`。需要 Python 3.13 與 torch 2.7（見腳本開頭）。mlpackage 是資料夾，`build_packs.py` 的 manifest 會以子路徑記錄檔名；Release 附件只用檔名本身，例如 `vision-coreml-v1-weight.bin`。`Manifest.json` 內含隨機識別碼，重新轉換的結果等價但位元組不同，所以 App 內釘死的是實際上傳的檔案。

