# 已審核套件的本機開發入口

此入口僅供測試 App 已內建審核的 Shinsou 套件，不是任意腳本或區域網路儲存庫的信任開關。

## 啟用與使用

正式建置預設關閉。Mac 開發建置需明確傳入 Gradle 屬性
`-PshinsouReviewedLocalRepository=true`；打包器將
`-Dshinsou.reviewedLocalRepository=true` 寫入該 App 的 JVM 啟動設定。
不修改全域 Java、系統代理、Codex 或個人信任資料。

在插件目錄啟動只監聽回環位址的 server：

```sh
python3 -m http.server 18081 --bind 127.0.0.1 --directory /Users/aluoexpiry/project/shinsou_plugin
```

在 App 新增儲存庫 `http://127.0.0.1:18081/index.json`（同一 base URL 亦可）。
選擇禁漫天堂 1.0.9／code 10，檢查並核准套件權限。此操作不會發布線上版本。
`127.0.0.1` 指目前裝置；這個 Mac server 不能透過同一網址提供 iPhone 使用。

Android Debug 與目前的 iPhone Debug 開發入口使用精確位址
`http://192.168.50.193:18081`。Android 依 App 的 `FLAG_DEBUGGABLE` 啟用，
Release 不啟用。請在 App 手動新增此位址；啟用入口不會自動新增儲存庫。
手機使用的 server 需監聽該內網介面：

```sh
python3 -m http.server 18081 --bind 192.168.50.193 --directory /Users/aluoexpiry/project/shinsou_plugin
```

其他 IP 或 port 不適用；主機位址改變時需更新開發入口並重新建置。

2026-09-08 的包子漫畫／MyComic 原始碼修復另見
[修復紀錄](BAOZI_MYCOMIC_REPAIR_2026-09-08.md)。新版套件必須搭配包含新版
精確審核表的 App；更新本機 server 檔案不會更新已部署的 App，也不會自動替換已安裝套件。

## 安全邊界與影響

- Mac 僅啟用指定 `http://127.0.0.1:18081`，手機 Debug 僅啟用上述精確內網位址；其他 port、localhost 別名、IPv6、其他 LAN 位址、路徑跳脫與重新導向不適用。
- 通用套件目錄只提供 App 內建審核表完整匹配的 Shinsou 套件。小說套件由獨立的 ShuYue 審核讀取器載入，沿用程序啟動時指定的同一個精確內網／loopback origin 與專用傳輸器；不開放其他區域網路位址。
- 使用者自行新增或舊版保存的整合儲存庫會在刷新時同步載入小說分區，無須刪除重加。App 不會自動加入預設儲存庫。小說仍須符合既有審核 profile，下載時驗證腳本及 sidecar，安裝執行仍使用原有權限確認流程。
- 安裝仍核對 index／sidecar、完整 manifest、來源與腳本 SHA-256；本機內容變更不會自動獲得權限。新修正版需重新審核並更新 App。
- 安裝 metadata 保留本機來源，不偽裝成官方 GitHub URL。使用者權限確認、停用、撤銷與驗證憑證生命周期仍有效。
- 本機下載使用獨立連線，不帶 Cookie、Authorization 或系統代理，不進行 DNS 解析；有逾時、回應大小與標頭上限。每個檔案請求明確關閉連線，避免 Python HTTP/1.0 server 的舊連線重用問題；不開啟自動重試。
- 套件安裝後不需要持續開著 server；但 App 必須仍明確啟用開發入口，才可恢復本機套件的執行權限。重新部署未啟用此屬性的正常建置即可關閉；不會刪除套件或個人資料。
- 封面並行數、一般來源網路規則及閱讀管線不變。新增成本在目錄精確比對、安裝／恢復時的內容驗證，以及本機檔案各自建立的短連線；沒有固定加速或零成本保證。

Python 簡易 server 會提供整個指定目錄（包含可能的隱藏檔），僅適合受控開發環境；手機測試限上述指定內網介面，請勿對外開放。App 的允許路徑不等於 server 的存取控制。

## 驗證

離線回歸測試使用本機 fixture 與記憶體 transport，不連線第三方網站。
已啟用入口的打包 App 支援 `--verify-reviewed-local-repository`：只讀取指定 server 的儲存庫文件與禁漫天堂 artifact，且在開啟任何個人儲存前執行；不安裝套件、不呼叫來源網站。

此入口解決本機更新被 URL／來源信任規則拒絕的問題；不代表已完成人工 Cloudflare 驗證或確認真實站點的目錄／閱讀可用。
