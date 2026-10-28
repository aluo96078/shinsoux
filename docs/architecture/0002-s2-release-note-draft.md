# ADR-0002 S2：Android 最低版本預告（草稿，尚未發佈）

ADR-0002 §12 S2 要求在 minSdk 提高到 29 的版本之前，先於前一個 Release 的 release notes 預告。以下是建議加在
下一個仍支援 Android 8～9 的 beta（或直接補在 v1.0.1-beta.8）說明中的段落，格式比照既有的 `... note:` 行。

發佈前需要決定：

1. 要補在哪個 Release：直接編輯 v1.0.1-beta.8，或另出一個仍是 minSdk 26 的 beta 專門預告。
2. 「最後支援的版本」寫哪一個版本號。

```text
Android note: this is the last release line that installs on Android 8 and 9 (API 26–28). The next release requires Android 10 (API 29) or newer, so that each third-party extension can run in its own isolated process. Devices on Android 8 or 9 keep working with this version but will not receive further updates.
```

中文版（如果之後的 release notes 有中文段落）：

```text
Android 說明：這是最後一個可安裝在 Android 8、9（API 26–28）的版本。下一版起需要 Android 10（API 29）以上，讓每個第三方套件都在自己的隔離程序中執行。Android 8、9 的裝置可繼續使用本版，但不會再收到更新。
```
