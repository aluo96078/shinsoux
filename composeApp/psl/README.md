# Public Suffix List

`public_suffix_list.dat` 是 [Public Suffix List](https://publicsuffix.org/)（MPL 2.0）的副本，建置時由
`generatePublicSuffixData` 轉成 Kotlin 資料（國際化規則轉為 punycode），App 執行時不會下載。

用途：

- Cookie 不得設定在公開後綴上（`com`、`co.uk`、`github.io` 等）；
- 套件宣告的 `contentHostSuffixes` 必須是可註冊網域或其子網域，不能是公開後綴（ADR-0002 §6.2、D11）。

目前版本：publicsuffix/list `main` @ `94ec5df7273926560d91e77c002000ba1a53fec4`（2026-11-08 取得），
SHA-256 `762b6cbdd2dacc597b398fcd7860e2184b684125a58aa177dbdf54916a1f3430`。

更新：

```bash
scripts/update-public-suffix-list.sh
```

更新後請檢查 diff，並執行 `./gradlew :composeApp:desktopTest --tests '*SandboxTierCeilingTest'`。
若某個已授權的 `contentHostSuffixes` 在新版清單中變成公開後綴，該套件會在下次安裝或更新時被拒絕。
