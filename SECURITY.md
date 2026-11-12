# 安全政策｜Security Policy

## 回報漏洞｜Reporting a Vulnerability

**請勿在公開的 GitHub Issue、Discussion 或 Discord 頻道揭露漏洞。**
**Do not disclose vulnerabilities in public GitHub issues, discussions, or Discord channels.**

請寄信至 / Email: **aszxc96078@gmail.com**，主旨以 `[Security] shinsoux` 開頭。

信中請盡量提供 / Please include:

- 受影響的平台（Android／iOS／macOS／Windows）與 App 版本 / Affected platform and app version
- 漏洞類型與影響範圍 / Type and impact of the issue
- 重現步驟或 PoC（若涉及外掛，請附上最小化的測試套件或儲存庫） / Reproduction steps or proof of concept (for plugin issues, a minimal test package or repository)
- 你是否已在其他地方揭露 / Whether it has been disclosed elsewhere

寄出前請移除 Cookie、Token、密碼、同步伺服器登入權杖、發佈者私鑰、電子郵件與裝置識別碼等私人資料。
Remove cookies, tokens, passwords, sync login tokens, publisher private keys, email addresses, and device identifiers before sending.

## 處理流程｜Process

1. 收到後會在 7 天內回覆確認。/ We aim to acknowledge reports within 7 days.
2. 確認問題後會評估嚴重程度並開發修正；修正發佈前請勿公開細節。/ Please keep details private until a fix is released.
3. 修正發佈後，若你同意，會在 Release notes 中致謝。/ With your permission, you will be credited in the release notes.

本專案由個人維護，沒有漏洞獎金計畫。/ This project is maintained by an individual and has no bug bounty program.

## 支援版本｜Supported Versions

只有最新發佈的版本（目前為 `v1.0.1-beta` 系列的最新版）會收到安全修正。
Only the latest released version receives security fixes.

## 範圍｜Scope

適用於 / In scope:

- 外掛沙箱逃逸、權限繞過（`requestOrigins`、`contentHostSuffixes`、敏感 runtime permission）
  / Plugin sandbox escapes and permission bypasses
- 簽章驗證、發佈者信任與撤回機制的繞過 / Bypasses of signature verification, publisher trust, or revocation
- Cookie、帳密、Token 等敏感資料外洩到套件、其他來源或第三方 / Leaks of credentials or cookies to plugins, other sources, or third parties
- 備份、同步或本機儲存造成的敏感資料外洩 / Sensitive data exposure through backups, sync, or local storage

不在範圍內 / Out of scope:

- 第三方內容來源網站本身的問題 / Issues in third-party content websites
- 已明確由使用者開啟的「開發者模式」所允許的行為 / Behavior explicitly allowed by Developer mode
- 自架同步伺服器的漏洞請回報至 [shinsou_sync_service](https://github.com/aluo96078/shinsou_sync_service/blob/main/SECURITY.md)；官方外掛儲存庫請回報至 [shinsou_plugin](https://github.com/aluo96078/shinsou_plugin/blob/master/SECURITY.md)（同一個信箱即可）
  / Report sync server and official plugin repository issues under their own repositories (same email address)

## 帳號安全提醒｜Account Safety

官方人員不會透過私訊索取密碼、Cookie、Token、同步伺服器登入權杖或發佈者私鑰。
Staff will never ask for passwords, cookies, tokens, sync login tokens, or publisher private keys through direct messages.
