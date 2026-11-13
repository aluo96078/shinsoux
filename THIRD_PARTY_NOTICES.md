# 第三方元件｜Third-Party Notices

Shinsou X 本身以 [MIT License](LICENSE) 發佈。發佈的安裝包（APK／DMG／MSI／EXE）另外包含下列第三方
元件，各自依其授權條款散布。版本以 [`gradle/libs.versions.toml`](gradle/libs.versions.toml) 與
[`sandbox-core/scripts/fetch-deps.sh`](sandbox-core/scripts/fetch-deps.sh) 為準；本表為人工維護，
升級相依套件時請一併更新。

Shinsou X itself is released under the MIT License. The published installers also contain the
third-party components below, each distributed under its own license.

## 擴充套件沙箱｜Plugin sandbox (`sandbox-core`)

| 元件 | 版本 | 授權 | 來源 |
| --- | --- | --- | --- |
| QuickJS-NG | v0.17.0（另套用 `sandbox-core/patches/`） | MIT | https://github.com/quickjs-ng/quickjs |
| Lexbor | v3.0.1 | Apache-2.0 | https://github.com/lexbor/lexbor |
| WebAssembly Micro Runtime (WAMR)（僅 iOS） | 2.4.5 | Apache-2.0 WITH LLVM-exception | https://github.com/bytecodealliance/wasm-micro-runtime |

## 應用程式相依套件｜Application libraries

| 元件 | 授權 | 來源 |
| --- | --- | --- |
| Kotlin、kotlinx.coroutines、kotlinx.serialization、kotlinx-datetime | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Compose Multiplatform、AndroidX（Activity、Biometric、WebKit、WorkManager） | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform |
| Ktor | Apache-2.0 | https://github.com/ktorio/ktor |
| OkHttp、Okio | Apache-2.0 | https://github.com/square/okhttp |
| Coil | Apache-2.0 | https://github.com/coil-kt/coil |
| SQLDelight | Apache-2.0 | https://github.com/sqldelight/sqldelight |
| SQLite JDBC（含 SQLite，public domain）（Desktop） | Apache-2.0 | https://github.com/xerial/sqlite-jdbc |
| multiplatform-crypto-libsodium-bindings | Apache-2.0 | https://github.com/ionspin/kotlin-multiplatform-libsodium |
| libsodium | ISC | https://github.com/jedisct1/libsodium |
| jsoup（Android／Desktop） | MIT | https://github.com/jhy/jsoup |
| Mozilla Rhino（Android／Desktop；原始碼可於右列網址取得） | MPL-2.0 | https://github.com/mozilla/rhino |
| Java Native Access (JNA)（Desktop） | Apache-2.0 或 LGPL-2.1-or-later | https://github.com/java-native-access/jna |
| OpenJFX（Desktop；含 WebKit 等元件，原始碼可於右列網址取得） | GPL-2.0 WITH Classpath-exception-2.0 | https://github.com/openjdk/jfx |
| OpenJDK runtime（Desktop 安裝包內建的精簡 JRE） | GPL-2.0 WITH Classpath-exception-2.0 | https://github.com/adoptium/temurin-build |
| libavif Android 綁定與其內含的 AV1 解碼器（Android） | BSD-2-Clause（libavif）及各解碼器的授權 | https://github.com/AOMediaCodec/libavif |

## 資料｜Data

| 項目 | 授權 | 來源 |
| --- | --- | --- |
| Public Suffix List（`composeApp/psl/public_suffix_list.dat`，建置時編入） | MPL-2.0 | https://publicsuffix.org/ |

各授權全文請見上列來源儲存庫。
The full license texts are available in the repositories linked above.
