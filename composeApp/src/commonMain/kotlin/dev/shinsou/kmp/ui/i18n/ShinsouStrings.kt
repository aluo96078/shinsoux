package dev.shinsou.kmp.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale

/**
 * Small, dependency-free runtime string table for the common UI.
 * English is the fallback for every long-tail string while the navigation and
 * high-frequency actions are localized in all supported languages.
 */
@Immutable
data class ShinsouStrings(
    val library: String = "Library",
    val updates: String = "Updates",
    val history: String = "History",
    val browse: String = "Browse",
    val more: String = "More",
    val sources: String = "Sources",
    val extensions: String = "Extensions",
    val migration: String = "Migration",
    val downloads: String = "Downloads",
    val statistics: String = "Statistics",
    val settings: String = "Settings",
    val backup: String = "Backup & restore",
    val about: String = "About",
    val search: String = "Search",
    val searchLibrary: String = "Search your library",
    val refresh: String = "Refresh",
    val filter: String = "Filter",
    val sort: String = "Sort",
    val all: String = "All",
    val done: String = "Done",
    val cancel: String = "Cancel",
    val save: String = "Save",
    val close: String = "Close",
    val delete: String = "Delete",
    val remove: String = "Remove",
    val retry: String = "Retry",
    val share: String = "Share",
    val selectAll: String = "Select all",
    val selected: String = "selected",
    val markRead: String = "Mark read",
    val markUnread: String = "Mark unread",
    val moveToCategory: String = "Move to category",
    val continueReading: String = "Continue reading",
    val favorite: String = "Add to library",
    val unfavorite: String = "In library",
    val download: String = "Download",
    val install: String = "Install",
    val uninstall: String = "Uninstall",
    val enable: String = "Enable",
    val disable: String = "Disable",
    val chapters: String = "Chapters",
    val readerSettings: String = "Reader settings",
    val previousChapter: String = "Previous chapter",
    val nextChapter: String = "Next chapter",
    val page: String = "Page",
    val readerModeLeftToRight: String = "Paged · left to right",
    val readerModeRightToLeft: String = "Paged · right to left",
    val readerModeVertical: String = "Vertical paging",
    val readerModeWebtoon: String = "Webtoon",
    val readerModeContinuousVertical: String = "Continuous vertical",
    val pageTurnAnimation: String = "Page turn animation",
    val pageTurnAnimationDescription: String = "Animate transitions when changing pages",
    val clearFilters: String = "Clear filters",
    val noMatches: String = "No matches",
    val libraryEmpty: String = "Your library is empty",
    val noUpdates: String = "No recent updates",
    val noHistory: String = "No reading history",
    val createBackup: String = "Create backup",
    val restoreBackup: String = "Restore backup",
    /** Long-tail strings that are not part of the high-frequency typed fields. */
    val translations: Map<String, String> = emptyMap(),
)

val LocalShinsouStrings = staticCompositionLocalOf { EnglishStrings }

/** Resolve a long-tail string and replace optional `{0}`, `{1}`, ... tokens. */
fun ShinsouStrings.text(key: String, vararg values: Any?): String {
    // A few screens use the strongly typed, high-frequency fields while
    // others use the long-tail lookup.  Resolve both through the same entry
    // point so a localized core label never falls back to English just
    // because the caller used `text("Settings")` instead of `settings`.
    var result = translations[key] ?: coreTranslation(key) ?: key
    values.forEachIndexed { index, value -> result = result.replace("{$index}", value.toString()) }
    return result
}

private fun ShinsouStrings.coreTranslation(key: String): String? = when (key) {
    "Library" -> library
    "Updates" -> updates
    "History" -> history
    "Browse" -> browse
    "More" -> more
    "Sources" -> sources
    "Extensions" -> extensions
    "Migration" -> migration
    "Downloads" -> downloads
    "Statistics" -> statistics
    "Settings" -> settings
    "Backup & restore" -> backup
    "About" -> about
    "Search" -> search
    "Search your library" -> searchLibrary
    "Refresh" -> refresh
    "Filter" -> filter
    "Sort" -> sort
    "All" -> all
    "Done" -> done
    "Cancel" -> cancel
    "Save" -> save
    "Close" -> close
    "Delete" -> delete
    "Remove" -> remove
    "Retry" -> retry
    "Share" -> share
    "Select all" -> selectAll
    "selected" -> selected
    "Mark read" -> markRead
    "Mark unread" -> markUnread
    "Move to category" -> moveToCategory
    "Continue reading" -> continueReading
    "Add to library" -> favorite
    "In library" -> unfavorite
    "Download" -> download
    "Install" -> install
    "Uninstall" -> uninstall
    "Enable" -> enable
    "Disable" -> disable
    "Chapters" -> chapters
    "Reader settings" -> readerSettings
    "Previous chapter" -> previousChapter
    "Next chapter" -> nextChapter
    "Page" -> page
    "Pager ltr", "Left to right" -> readerModeLeftToRight
    "Pager rtl", "Right to left" -> readerModeRightToLeft
    "Pager vertical", "Vertical", "Vertical paging" -> readerModeVertical
    "Webtoon" -> readerModeWebtoon
    "Continuous vertical" -> readerModeContinuousVertical
    "Page turn animation" -> pageTurnAnimation
    "Animate transitions when changing pages" -> pageTurnAnimationDescription
    "Clear filters" -> clearFilters
    "No matches" -> noMatches
    "Your library is empty" -> libraryEmpty
    "No recent updates" -> noUpdates
    "No reading history" -> noHistory
    "Create backup" -> createBackup
    "Restore backup" -> restoreBackup
    else -> null
}

@Composable
fun ProvideShinsouStrings(
    languagePreference: String?,
    content: @Composable () -> Unit,
) {
    // Keep the complete BCP-47 tag.  Reading only `language` turns a
    // Traditional Chinese device into plain `zh`, which used to be treated
    // as Simplified Chinese by the matcher below.
    val systemLanguage = Locale.current.toLanguageTag()
    val language = languagePreference
        ?.takeUnless { it.equals("system", ignoreCase = true) }
        ?: systemLanguage
    CompositionLocalProvider(
        LocalShinsouStrings provides shinsouStringsFor(language),
        content = content,
    )
}

fun shinsouStringsFor(language: String): ShinsouStrings {
    val normalized = language.trim().lowercase().replace('_', '-')
    val parts = normalized.split('-').filter { it.isNotBlank() }
    val languageCode = parts.firstOrNull().orEmpty()
    val subtags = parts.drop(1)
    val script = subtags.firstOrNull { it.length == 4 }
    val region = subtags.firstOrNull { it.length == 2 || it.length == 3 }
    return when {
        languageCode == "zh" && (
            script.equals("hant", ignoreCase = true) ||
                region.equals("tw", ignoreCase = true) ||
                region.equals("hk", ignoreCase = true) ||
                region.equals("mo", ignoreCase = true)
            ) -> TraditionalChineseStrings
        languageCode == "zh" && (
            script.equals("hans", ignoreCase = true) ||
                region.equals("cn", ignoreCase = true) ||
                region.equals("sg", ignoreCase = true) ||
                region.equals("my", ignoreCase = true)
            ) -> SimplifiedChineseStrings
        // Bare `zh` has no script information.  Keep the app's existing
        // Traditional Chinese default instead of silently forcing Simplified.
        languageCode == "zh" -> TraditionalChineseStrings
        languageCode == "ja" -> JapaneseStrings
        languageCode == "ko" -> KoreanStrings
        languageCode == "fr" -> FrenchStrings
        languageCode == "de" -> GermanStrings
        languageCode == "es" -> SpanishStrings
        languageCode == "pt" -> PortugueseStrings
        else -> EnglishStrings
    }
}

private val EnglishStrings = ShinsouStrings()

/** Additional copy for settings, tracking, and platform screens. */
private val TraditionalAdditionalTranslations = mapOf(
    "Shinsou X" to "Shinsou X",
    "About Shinsou X" to "關於 Shinsou X",
    "Settings…" to "設定…",
    "Quit Shinsou X" to "結束 Shinsou X",
    "Quit Shinsou X?" to "確定要結束 Shinsou X 嗎？",
    "Go" to "前往",
    "Window" to "視窗",
    "Center window" to "置中視窗",
    "Minimize" to "最小化",
    "Export {0}" to "匯出 {0}",
    "Import Shinsou X data" to "匯入 Shinsou X 資料",
    "Choose local manga" to "選擇本機漫畫",
    "Require device authentication" to "需要裝置驗證",
    "Enable Shinsou X app lock" to "啟用 Shinsou X 應用程式鎖",
    "Lock after" to "鎖定時間",
    "Immediately" to "立即",
    "Hide content in system previews" to "在系統預覽中隱藏內容",
    "Start reading" to "開始閱讀",
    "Read again" to "再次閱讀",
    "Source order" to "來源順序",
    "Chapter number" to "章節編號",
    "Upload date" to "上傳日期",
    "Missing ch. {0}" to "缺少第 {0} 章",
    "Missing ch. {0}–{1}" to "缺少第 {0}–{1} 章",
    "{0} total" to "共 {0} 個",
    "{0} of {1}" to "{0}／{1}",
    "Select all visible chapters" to "全選可見章節",
    "Remove bookmarks" to "移除書籤",
    "Read" to "已讀",
    "Reading" to "閱讀中",
    "Completed" to "已完成",
    "On hold" to "擱置",
    "Dropped" to "已放棄",
    "Plan to read" to "計畫閱讀",
    "Rereading" to "重讀中",
    "Unknown" to "未知",
    "Mark duplicate chapter copies read" to "將重複章節副本標記為已讀",
    "Sort by {0}" to "排序：{0}",
    "Not bookmarked" to "未加書籤",
    "Not downloaded" to "未下載",
    "total" to "總計",
    "Remove selected titles?" to "要移除選取的作品嗎？",
    "Alphabetical" to "依字母排序",
    "Last read" to "最近閱讀",
    "Last update" to "最近更新",
    "Unread count" to "未讀數量",
    "Total chapters" to "章節總數",
    "Latest chapter" to "最新章節",
    "Chapter fetch date" to "章節擷取日期",
    "Date added" to "加入日期",
    "Tracker mean" to "追蹤器平均分數",
    "Random" to "隨機",
    "The operation could not be completed." to "無法完成操作。",
    "This chapter has no pages." to "此章節沒有頁面。",
    "Idle" to "閒置",
    "Creating" to "建立中",
    "Restoring" to "還原中",
    "Failed" to "失敗",
    "Completed" to "已完成",
    "Decrease {0}" to "減少 {0}",
    "Increase {0}" to "增加 {0}",
    "Move up" to "上移",
    "Move down" to "下移",
    "Selected" to "已選取",
    "Not selected" to "未選取",
    "Unable to load chapter pages." to "無法載入章節頁面。",
    "Unlock Shinsou X" to "解鎖 Shinsou X",
    "Chapter completed, but one or more trackers could not be updated: {0}" to "章節已完成，但無法更新一個或多個追蹤器：{0}",
    "unknown tracker error" to "未知的追蹤器錯誤",
    "Backup export was cancelled." to "備份匯出已取消。",
    "Device authentication is unavailable on this platform." to "此平台無法使用裝置驗證。",
    "Secure-screen protection is unavailable on this platform." to "此平台無法使用安全畫面保護。",
    "Set up a device passcode, PIN, password, or biometric authentication to use app lock." to "請設定裝置密碼、PIN、密碼或生物辨識驗證，才能使用應用程式鎖。",
    "App lock is unavailable on Desktop because macOS authentication is not implemented." to "桌面版無法使用應用程式鎖，因為尚未實作 macOS 驗證。",
    "Secure screen is unavailable on Desktop because macOS does not expose this protection here." to "桌面版無法使用安全畫面，因為 macOS 未在此提供這項保護。",
    "Checking iCloud Drive availability…" to "正在檢查 iCloud Drive 可用性…",
    "A single Shinsou X snapshot will be stored in the app's iCloud Drive container." to "單一 Shinsou X 快照會儲存在應用程式的 iCloud Drive 容器中。",
    "The remote snapshot service is unavailable." to "遠端快照服務無法使用。",
    "January" to "一月",
    "February" to "二月",
    "March" to "三月",
    "April" to "四月",
    "May" to "五月",
    "June" to "六月",
    "July" to "七月",
    "August" to "八月",
    "September" to "九月",
    "October" to "十月",
    "November" to "十一月",
    "December" to "十二月",
    "Today" to "今天",
    "Yesterday" to "昨天",
    "{0} days ago" to "{0} 天前",
    "Unknown time" to "未知時間",
    "Just now" to "剛剛",
    "{0}m ago" to "{0} 分鐘前",
    "{0}h ago" to "{0} 小時前",
    "{0}d ago" to "{0} 天前",
    "Add" to "新增",
    "{0} · {1} results" to "{0} · {1} 筆結果",
    "No matches from this source." to "此來源沒有符合項目。",
    "Unable to search {0}" to "無法搜尋 {0}",
    "Pinned" to "已釘選",
    "All sources" to "所有來源",
    "Unpin {0}" to "取消釘選 {0}",
    "Pin {0}" to "釘選 {0}",
    "Source settings" to "來源設定",
    "Imported {0} cookie(s)." to "已匯入 {0} 個 Cookie。",
    "unable to save browser cookies" to "無法儲存瀏覽器 Cookie",
    "Web challenge cancelled. No browser cookies were imported." to "Web 驗證已取消，沒有匯入瀏覽器 Cookie。",
    "Add category" to "新增分類",
    "All categories" to "所有分類",
    "Also delete when marked read" to "同時刪除標記為已讀的章節",
    "Any" to "任意",
    "Apply Manga Detail filters while moving between chapters" to "切換章節時套用漫畫詳情篩選條件",
    "Apply automatic deletion to chapters marked read outside the Reader" to "對在閱讀器外標記為已讀的章節套用自動刪除",
    "At least two chapter upload dates are needed to predict a schedule." to "至少需要兩個章節上傳日期才能預測排程。",
    "Auto-download new chapters" to "自動下載新章節",
    "Auto-sync after reading" to "閱讀後自動同步",
    "Available" to "可用",
    "Backups include your library, chapters, reading history, categories, tracking records, downloads queue, settings, and extension repositories. Android and iOS decide the exact background execution time; missed work is retried by the platform." to "備份包含書庫、章節、閱讀歷史、分類、追蹤記錄、下載佇列、設定與擴充套件儲存庫。Android 與 iOS 會決定實際的背景執行時間；錯過的工作會由平台重試。",
    "Beginning of {0}" to "{0} 開始",
    "Bind" to "綁定",
    "Bookmark" to "加入書籤",
    "Bookmarked" to "已加書籤",
    "Callback URL or access token" to "回呼 URL 或存取權杖",
    "Category updates" to "分類更新",
    "Chapter list" to "章節清單",
    "Chapter {0}" to "第 {0} 章",
    "Charging only updates" to "僅在充電時更新",
    "Check extension updates" to "檢查擴充套件更新",
    "Checking account…" to "正在檢查帳號…",
    "Checking availability" to "正在檢查可用性",
    "Checking availability…" to "正在檢查可用性…",
    "Choose one or more categories whenever a title is added" to "新增作品時選擇一個或多個分類",
    "Choose which categories participate in library updates" to "選擇要參與書庫更新的分類",
    "Clear completed downloads?" to "清除已完成的下載？",
    "Clear reading history?" to "清除閱讀歷史？",
    "Close reader" to "關閉閱讀器",
    "Cloud synchronization" to "雲端同步",
    "Cloudflare Worker proxy" to "Cloudflare Worker Proxy",
    "Complete the verification in the browser, then import its cookies." to "在瀏覽器完成驗證後匯入 Cookie。",
    "Completed" to "已完成",
    "Contact" to "聯絡",
    "Cookie file is larger than 1 MiB." to "Cookie 檔案大於 1 MiB。",
    "Create, rename, reorder, or remove library categories" to "建立、重新命名、排序或移除書庫分類",
    "Credentials and tracker tokens are stored by each platform's secure storage adapter, not in the portable snapshot." to "登入資料與追蹤器權杖由各平台的安全儲存介面保存，不會寫入可攜式快照。",
    "Custom User-Agent" to "自訂 User-Agent",
    "DNS over HTTPS" to "DNS over HTTPS",
    "Data stored on your device" to "儲存在裝置上的資料",
    "Decrease progress" to "減少進度",
    "Default category" to "預設分類",
    "Default reading mode" to "預設閱讀模式",
    "Delete after reading" to "閱讀後刪除",
    "Delete {0}" to "刪除 {0}",
    "Deselect all" to "取消全選",
    "Desktop does not include an embedded browser whose cookie store can be safely shared with sources. The page can open in your default browser, but those cookies stay in that browser and will not be imported. After verification, add the required cookies manually in Source settings." to "桌面版沒有可安全與來源共用 Cookie 儲存區的內嵌瀏覽器。頁面可以在預設瀏覽器開啟，但 Cookie 會留在該瀏覽器中且不會匯入。完成驗證後，請在來源設定中手動加入所需 Cookie。",
    "Disabled" to "已停用",
    "Do not save history or sync trackers" to "不儲存歷史或同步追蹤器",
    "Double-tap to zoom" to "雙擊縮放",
    "Download chapters from a title to read them offline." to "從作品下載章節即可離線閱讀。",
    "Download all chapters" to "下載全部章節",
    "Unable to open this title." to "無法開啟此作品。",
    "Download-only mode" to "僅下載模式",
    "Edit notes" to "編輯備註",
    "Enable snapshot sync" to "啟用快照同步",
    "Error: no usable cookies were found for this source. Complete the challenge and try again." to "錯誤：找不到此來源可用的 Cookie。請完成驗證後再試一次。",
    "Error: {0}" to "錯誤：{0}",
    "Estimated from the last library update" to "根據上次書庫更新估算",
    "Expected today" to "預計今天",
    "File access is used only for documents you choose to import or export. Notification, biometric lock and secure-screen features are optional. Credentials and tracker tokens use platform-protected storage." to "檔案存取只會用於匯入或匯出你選擇的文件。通知、生物辨識鎖與安全畫面功能都是選用功能；登入資料與追蹤器權杖會使用平台保護的儲存空間。",
    "Finished {0}" to "{0} 結束",
    "Hidden by default" to "預設隱藏",
    "Hide titles without offline chapters" to "隱藏沒有離線章節的作品",
    "Hide token" to "隱藏權杖",
    "Idle" to "閒置",
    "Import cookies.txt / JSON" to "匯入 cookies.txt／JSON",
    "Imported {0} cookie(s)." to "已匯入 {0} 個 Cookie。",
    "Importing…" to "匯入中…",
    "Incognito mode" to "無痕模式",
    "Increase progress" to "增加進度",
    "Install or enable another compatible source to migrate this title." to "請安裝或啟用其他相容來源以遷移此作品。",
    "Keep screen on" to "保持螢幕開啟",
    "Landscape columns" to "橫向欄數",
    "Last result" to "上次結果",
    "Last sync failed" to "上次同步失敗",
    "Last sync succeeded" to "上次同步成功",
    "Library layout" to "書庫版面",
    "Library records, reading history, settings, downloaded pages, installed extensions and automatic backups are stored in app-private storage. Portable backups intentionally exclude passwords, cookies and OAuth tokens." to "書庫記錄、閱讀歷史、設定、已下載頁面、已安裝擴充套件與自動備份會儲存在應用程式私有空間。可攜式備份刻意排除密碼、Cookie 與 OAuth 權杖。",
    "Manga notes" to "漫畫備註",
    "Merge one versioned Shinsou X backup file through iCloud Drive" to "透過 iCloud Drive 合併一份具版本的 Shinsou X 備份檔案",
    "Move {0} down" to "將 {0} 下移",
    "Move {0} up" to "將 {0} 上移",
    "Network and extensions" to "網路與擴充套件",
    "Newly added library titles are placed here" to "新加入書庫的作品會放在這裡",
    "Next month" to "下個月",
    "No custom categories" to "沒有自訂分類",
    "No title is expected to update on this date." to "預計這天沒有作品更新。",
    "No valid cookies for {0} were found." to "找不到 {0} 的有效 Cookie。",
    "Not configured" to "未設定",
    "Not signed in" to "尚未登入",
    "Notes" to "備註",
    "Open default browser" to "開啟預設瀏覽器",
    "Open original page" to "開啟原始頁面",
    "Open remote page" to "開啟遠端頁面",
    "Open {0} login" to "開啟 {0} 登入頁面",
    "Opened externally. No cookies were imported." to "已在外部開啟，沒有匯入 Cookie。",
    "Orientation" to "方向",
    "Page {0} failed to load." to "第 {0} 頁載入失敗。",
    "Parallel chapters" to "平行下載章節數",
    "Parallel pages" to "平行下載頁數",
    "Paste the redirected URL (the #access_token fragment is parsed) or the token itself. It is stored in platform secure storage." to "貼上重新導向的 URL（會解析 #access_token 片段）或直接貼上權杖。權杖會儲存在平台安全儲存空間。",
    "Permissions and security" to "權限與安全性",
    "Portrait columns" to "直向欄數",
    "Preparing…" to "準備中…",
    "Previous month" to "上個月",
    "Proxy API key" to "Proxy API 金鑰",
    "Proxy URL" to "Proxy URL",
    "Pulling, merging and uploading" to "擷取、合併與上傳中",
    "Questions about this policy can be sent to aluo96078@gmail.com." to "關於此政策的問題可以寄至 aluo96078@gmail.com。",
    "Read chapter" to "閱讀章節",
    "Read {0}" to "閱讀 {0}",
    "Reading cookies from the isolated browser session…" to "正在從隔離的瀏覽器工作階段讀取 Cookie…",
    "Refresh automatic backups" to "重新整理自動備份",
    "Refresh library metadata only on Wi-Fi" to "只在 Wi-Fi 下重新整理書庫中繼資料",
    "Refresh metadata automatically" to "自動重新整理中繼資料",
    "Refresh the library only while this device is charging" to "只在裝置充電時重新整理書庫",
    "Refresh tracking" to "重新整理追蹤",
    "Refresh your library to look for newly published chapters." to "重新整理書庫以尋找新發布的章節。",
    "Remove tracking link" to "移除追蹤連結",
    "Remove tracking link?" to "要移除追蹤連結嗎？",
    "Rename {0}" to "重新命名 {0}",
    "Restore {0}" to "還原 {0}",
    "Reverse sort" to "反向排序",
    "Revision {0} → {1} · {2} conflicts" to "修訂版 {0} → {1} · {2} 個衝突",
    "Runs only when sync is enabled; identical snapshots do not write again" to "只會在啟用同步時執行；相同的快照不會重複寫入",
    "Saved for platforms with a hostname-aware DNS resolver; direct IP rewriting cannot preserve TLS SNI safely" to "僅儲存給支援主機名稱感知 DNS 解析器的平台；直接改寫 IP 無法安全保留 TLS SNI",
    "Score (0–10)" to "評分（0–10）",
    "Selected categories only" to "僅選取的分類",
    "Shinsou X · version 1.0.0" to "Shinsou X · 版本 1.0.0",
    "Shinsou X 1.0" to "Shinsou X 1.0",
    "Shinsou X is a local-first manga library and reader. It does not operate an analytics or advertising service." to "Shinsou X 是以本機優先的漫畫書庫與閱讀器，不會執行分析或廣告服務。",
    "Show NSFW sources" to "顯示 NSFW 來源",
    "Show page number" to "顯示頁碼",
    "Show token" to "顯示權杖",
    "Showing the first {0} results. Refine the search to see more." to "顯示前 {0} 筆結果。請縮小搜尋範圍以查看更多。",
    "Signed in" to "已登入",
    "Skip alternate copies with the same chapter number" to "略過相同章節編號的替代版本",
    "Skip duplicate chapters" to "略過重複章節",
    "Skip filtered chapters" to "略過篩選出的章節",
    "Skip read chapters" to "略過已讀章節",
    "Source or request headers take priority when a plugin supplies its own User-Agent." to "外掛提供自己的 User-Agent 時，來源或要求標頭會優先使用。",
    "Split tall images" to "分割高圖片",
    "Started" to "已開始",
    "Sync status" to "同步狀態",
    "Sync when app enters foreground" to "應用程式進入前景時同步",
    "The existing {0} link is retained. Sign in to sync or edit it." to "現有的 {0} 連結會保留。登入後即可同步或編輯。",
    "The tracking operation could not be completed." to "無法完成追蹤操作。",
    "This removes all history entries but does not mark chapters unread." to "這會移除所有歷史記錄，但不會將章節標記為未讀。",
    "This removes the local {0} link. It does not delete the remote list entry." to "這會移除本機的 {0} 連結，但不會刪除遠端清單項目。",
    "This source does not provide a valid HTTP(S) URL." to "此來源沒有提供有效的 HTTP(S) URL。",
    "This tracker is not configured in this build." to "此追蹤器未在此版本中設定。",
    "This uses a coordinated single-file snapshot in iCloud Drive. It is not record-level CloudKit synchronization." to "這會在 iCloud Drive 使用協調的單檔快照，不是記錄層級的 CloudKit 同步。",
    "Titles you read will appear here." to "你閱讀過的作品會顯示在這裡。",
    "Tracker accounts are connected from Manga Detail. Authentication tokens remain in platform secure storage." to "追蹤器帳號可從漫畫詳情頁連結。驗證權杖會留在平台安全儲存空間。",
    "Try a different search." to "請嘗試其他搜尋字詞。",
    "Unable to open the default browser: {0}" to "無法開啟預設瀏覽器：{0}",
    "Unable to start the web challenge." to "無法啟動 Web 驗證。",
    "Unavailable" to "不可用",
    "Unread" to "未讀",
    "Update chapter progress" to "更新章節進度",
    "Use hardware volume keys to turn pages" to "使用硬體音量鍵翻頁",
    "Uses the source's exact User-Agent and imports only cookies valid for its domain and path." to "使用來源指定的 User-Agent，只匯入對其網域與路徑有效的 Cookie。",
    "Usually every {0} days" to "通常每 {0} 天",
    "Verification page loaded. Complete it, then choose Import cookies." to "驗證頁面已載入。完成驗證後選擇「匯入 Cookie」。",
    "Webtoon side padding · {0}%" to "條漫側邊留白 · {0}%",
    "When you browse or read, Shinsou X contacts the source and extension repository you selected. Those third parties receive normal request information such as your IP address and user agent and are governed by their own policies." to "瀏覽或閱讀時，Shinsou X 會連線至你選擇的來源與擴充套件儲存庫。這些第三方會收到 IP 位址與 User-Agent 等一般要求資訊，並受其自身政策規範。",
    "Wi-Fi only" to "僅 Wi-Fi",
    "Wi-Fi only updates" to "僅在 Wi-Fi 下更新",
    "iCloud Drive snapshot" to "iCloud Drive 快照",
    "iCloud Drive snapshot sync is unavailable on this platform." to "此平台無法使用 iCloud Drive 快照同步。",
    "iCloud Drive synchronization is opt-in and available only on iOS. If enabled, a Shinsou X backup snapshot is stored in your private iCloud container and handled under your Apple account settings." to "iCloud Drive 同步是選用功能，且僅在 iOS 上提供。啟用後，Shinsou X 備份快照會儲存在你的私有 iCloud 容器，並依 Apple 帳號設定處理。",
    "unable to import cookies" to "無法匯入 Cookie",
    "{0} active · {1} total" to "{0} 個進行中 · 共 {1} 個",
    "{0} new chapters" to "{0} 個新章節",
    "{0} selected" to "已選取 {0} 個",
    "{0} selected title(s) will be removed from your library." to "將從書庫移除 {0} 部作品。",
    "{0}% · {1}/{2} pages" to "{0}% · {1}/{2} 頁",
)

private val TraditionalLongTranslations = mapOf(
    "Cover: {0}" to "封面：{0}",
    "Shinsou X is locked" to "Shinsou X 已鎖定",
    "Authenticate to continue." to "請完成身分驗證後繼續。",
    "Unlock" to "解鎖",
    "Title not found" to "找不到作品",
    "This title may have been removed." to "此作品可能已被移除。",
    "Set categories" to "設定分類",
    "Library" to "書庫",
    "titles" to "部作品",
    "title(s)" to "部作品",
    "Try a different search or clear the active filters." to "請嘗試其他搜尋字詞，或清除目前的篩選條件。",
    "Browse sources and add a title to start reading." to "瀏覽來源並加入作品，即可開始閱讀。",
    "Refresh library" to "重新整理書庫",
    "Change layout" to "變更版面",
    "Continue {0}" to "繼續閱讀 {0}",
    "Clear search" to "清除搜尋",
    "Loading" to "載入中",
    "Cancel" to "取消",
    "Close" to "關閉",
    "Search" to "搜尋",
    "Save" to "儲存",
    "Delete" to "刪除",
    "Remove" to "移除",
    "Back" to "返回",
    "Language" to "語言",
    "System" to "系統預設",
    "Light" to "淺色",
    "Dark" to "深色",
    "General" to "一般",
    "Appearance" to "外觀",
    "Reader" to "閱讀器",
    "Sync" to "同步",
    "Security" to "安全性",
    "Advanced" to "進階",
    "Starting screen" to "啟動畫面",
    "English" to "English",
    "繁體中文" to "繁體中文",
    "简体中文" to "簡體中文",
    "Changes apply immediately" to "變更會立即套用",
    "Starting screen" to "啟動畫面",
    "Confirm before closing" to "關閉前確認",
    "Ask before closing the desktop application" to "關閉桌面應用程式前先詢問",
    "Date format" to "日期格式",
    "Theme" to "主題",
    "Compact grid" to "緊湊網格",
    "Comfortable grid" to "舒適網格",
    "List" to "清單",
    "Cover only grid" to "僅封面網格",
    "Pager ltr" to "翻頁（左至右）",
    "Pager rtl" to "翻頁（右至左）",
    "Pager vertical" to "垂直翻頁",
    "Continuous vertical" to "連續垂直",
    "Free" to "自由",
    "Portrait" to "直向",
    "Landscape" to "橫向",
    "Sensor portrait" to "感應直向",
    "Sensor landscape" to "感應橫向",
    "AMOLED black" to "AMOLED 黑色",
    "Use pure black surfaces in dark mode" to "在深色模式使用純黑色表面",
    "Relative timestamps" to "相對時間",
    "Show “2h ago” instead of a clock time" to "顯示「2 小時前」而非時鐘時間",
    "All" to "全部",
    "Check again" to "再次檢查",
    "Sync now" to "立即同步",
    "Enabled languages" to "啟用的語言",
    "App lock" to "應用程式鎖",
    "Secure screen" to "安全畫面",
    "Manage categories" to "管理分類",
    "New category" to "新增分類",
    "Rename category" to "重新命名分類",
    "Delete {0}?" to "刪除「{0}」？",
    "Titles in this category will be moved to the default category." to "此分類中的作品會移至預設分類。",
    "Category name" to "分類名稱",
    "Tracking" to "追蹤",
    "Log out" to "登出",
    "Search manga" to "搜尋漫畫",
    "Complete login" to "完成登入",
    "Status" to "狀態",
    "Progress" to "進度",
    "Update" to "更新",
    "Reading mode" to "閱讀模式",
    "Brightness" to "亮度",
    "Color filter" to "色彩濾鏡",
    "Open in browser" to "在瀏覽器開啟",
    "Return to chapter" to "返回章節",
    "Retry this page" to "重試此頁",
    "No chapters" to "沒有章節",
    "Show all" to "顯示全部",
    "Recent" to "最近",
    "Upcoming" to "即將到來",
    "Clear history" to "清除歷史",
    "No expected updates" to "沒有預期更新",
    "Download queue is empty" to "下載佇列是空的",
    "Back up now" to "立即備份",
    "Delete automatic backup?" to "刪除自動備份？",
    "Privacy policy" to "隱私權政策",
    "No cookies saved" to "沒有已儲存的 Cookie",
    "Add cookie" to "新增 Cookie",
    "Clear cookies" to "清除 Cookie",
    "Repositories" to "儲存庫",
    "Add repository" to "新增儲存庫",
    "No extensions" to "沒有擴充套件",
    "Migrate" to "遷移",
    "Load more" to "載入更多",
    "Reset" to "重設",
    "Apply" to "套用",
    "None" to "無",
    "Ascending" to "遞增",
    "Descending" to "遞減",
    "Default" to "預設",
    "Always Ask" to "每次詢問",
    "titles" to "部作品",
    "chapters" to "章節",
    "Unknown author" to "未知作者",
    "Unknown title" to "未知作品",
    "Unknown chapter" to "未知章節",
    "Downloaded" to "已下載",
    "Downloaded files remain available; only completed queue entries are removed." to "已下載的檔案仍可使用；只會移除已完成的佇列項目。",
    "Download failed" to "下載失敗",
    "Queued" to "排入佇列",
    "Paused" to "已暫停",
    "Resume" to "繼續",
    "Pause" to "暫停",
    "Clear completed" to "清除已完成",
    "{0} queued or active" to "{0} 個排隊或進行中的項目",
    "Library and reading insights" to "書庫與閱讀統計",
    "Appearance, reader, sources and privacy" to "外觀、閱讀器、來源與隱私",
    "No backup yet" to "尚無備份",
    "Version, licenses and project links" to "版本、授權與專案連結",
    "Do not save reading history or sync trackers" to "不儲存閱讀歷史，也不同步追蹤器",
    "Show only titles with offline chapters" to "只顯示有離線章節的作品",
    "Import local manga" to "匯入本機漫畫",
    "Images, CBZ, ZIP or EPUB · stored on this device" to "圖片、CBZ、ZIP 或 EPUB · 儲存於本機",
    "Incognito mode is active" to "無痕模式已啟用",
    "Download-only mode is active" to "僅下載模式已啟用",
    "A snapshot of your collection" to "書庫的概覽",
    "Titles" to "作品",
    "Reading progress" to "閱讀進度",
    "{0} of {1} chapters read · {2}%" to "已閱讀 {0}/{1} 章 · {2}%",
    "Categories" to "分類",
    "Backup status" to "備份狀態",
    "Automatic backups" to "自動備份",
    "Recoverable snapshots in private app storage" to "儲存在應用程式私有空間、可復原的快照",
    "Backup interval" to "備份間隔",
    "Every {0} hours" to "每 {0} 小時",
    "Stored backups" to "儲存的備份",
    "Keep {0}" to "保留 {0} 個",
    "Back up now" to "立即備份",
    "Saved automatic backups" to "已儲存的自動備份",
    "{0} stored on this device" to "本機已儲存 {0} 個",
    "No automatic backups yet. The first one is created when the platform scheduler runs, or you can create one now." to "尚無自動備份。平台排程器執行時會建立第一份，也可以現在手動建立。",
    "Damaged backup" to "備份損壞",
    "Restore automatic backup?" to "還原自動備份？",
    "Current library data will be replaced with the snapshot from {0}." to "目前書庫資料會被 {0} 的快照取代。",
    "Delete automatic backup?" to "刪除自動備份？",
    "{0} will be permanently removed from this device." to "{0} 將從此裝置永久移除。",
    "Portable Shinsou X JSON snapshot" to "可攜式 Shinsou X JSON 快照",
    "Privacy policy" to "隱私權政策",
    "Last updated August 19, 2026" to "最後更新：2026 年 8 月 19 日",
    "A private, extensible manga library and reader for Android, iOS, and desktop." to "適用於 Android、iOS 與桌面的私密可擴充漫畫書庫與閱讀器。",
    "GitHub · source code" to "GitHub · 原始碼",
    "Contact developer" to "聯絡開發者",
    "Open-source license" to "開放原始碼授權",
    "Search all sources" to "搜尋所有來源",
    "Global Search" to "全域搜尋",
    "{0} enabled sources" to "已啟用 {0} 個來源",
    "No enabled sources" to "沒有啟用的來源",
    "Enable at least one source before searching." to "搜尋前請至少啟用一個來源。",
    "Search across sources" to "跨來源搜尋",
    "Results are grouped by source so you can compare editions before opening one." to "結果會依來源分組，方便在開啟前比較不同版本。",
    "Try a different title or enable more source languages." to "請嘗試其他作品名稱，或啟用更多來源語言。",
    "No sources" to "沒有來源",
    "Install an extension or enable another language to browse manga." to "請安裝擴充套件或啟用其他語言以瀏覽漫畫。",
    "Credentials" to "登入資料",
    "Username" to "使用者名稱",
    "Password" to "密碼",
    "Login" to "登入",
    "Login required" to "需要登入",
    "Sign in to {0} to continue using this source." to "登入 {0} 後才能繼續使用此來源。",
    "Save credentials" to "儲存登入資料",
    "Login failed. Check your username and password." to "登入失敗，請檢查使用者名稱與密碼。",
    "Unable to save credentials" to "無法儲存登入資料",
    "Logout" to "登出",
    "Preferences" to "偏好設定",
    "Cookies" to "Cookie",
    "No cookies saved" to "沒有已儲存的 Cookie",
    "Add cookie" to "新增 Cookie",
    "Clear cookies" to "清除 Cookie",
    "Cookie name" to "Cookie 名稱",
    "Cookie value" to "Cookie 值",
    "Cookie domain" to "Cookie 網域",
    "Repositories" to "儲存庫",
    "Add an extension repository before installing sources." to "安裝來源前請先新增擴充套件儲存庫。",
    "No extensions" to "沒有擴充套件",
    "Refresh the selected repository or try a different language." to "請重新整理選取的儲存庫，或嘗試其他語言。",
    "Add repository" to "新增儲存庫",
    "Repository URL" to "儲存庫 URL",
    "Official" to "官方",
    "Update available" to "有可用更新",
    "Execution allowed" to "允許執行",
    "Execution blocked" to "已阻擋執行",
    "Nothing to migrate" to "沒有可遷移項目",
    "Migration suggestions appear after compatible sources are installed." to "安裝相容來源後會顯示遷移建議。",
    "Current source: {0}" to "目前來源：{0}",
    "Choose source" to "選擇來源",
    "Search title" to "搜尋作品",
    "Choose a target source, adjust the title if needed, then search." to "選擇目標來源，必要時調整作品名稱後搜尋。",
    "Migrate to this manga?" to "要遷移至這部漫畫嗎？",
    "Replace “{0}” with “{1}”? Read progress, categories and tracking links stay attached to this library entry." to "以「{1}」取代「{0}」？閱讀進度、分類與追蹤連結會保留在此書庫項目。",
    "Popular" to "熱門",
    "Latest" to "最新",
    "Source error" to "來源錯誤",
    "Try another query or refresh this source." to "請嘗試其他搜尋字詞或重新整理來源。",
    "Load more" to "載入更多",
    "Reset" to "重設",
    "Apply" to "套用",
    "Not set" to "未設定",
    "Ignore" to "忽略",
    "Include" to "包含",
    "Exclude" to "排除",
    "Web challenge / Cloudflare" to "Web 驗證／Cloudflare",
    "Import cookies" to "匯入 Cookie",
    "Retry this page" to "重試此頁",
    "Read next chapter" to "閱讀下一章",
    "Read previous chapter" to "閱讀上一章",
    "No next chapter" to "沒有下一章",
    "No previous chapter" to "沒有上一章",
    "Return to chapter" to "返回章節",
    "Newest first · {0} chapters" to "最新在前 · {0} 章",
    "Read" to "已讀",
    "Track" to "追蹤",
    "No description available." to "沒有可用的簡介。",
    "Add a private note for this title." to "為此作品新增私人備註。",
    "Scanlators" to "翻譯組",
    "{0} hidden" to "已隱藏 {0} 個",
    "Refresh the title or change the chapter filters." to "請重新整理作品或變更章節篩選條件。",
    "Duplicate chapter number" to "重複的章節編號",
    "Page {0}" to "第 {0} 頁",
    "Reading mode" to "閱讀模式",
    "Fullscreen" to "全螢幕",
    "Enable filter" to "啟用濾鏡",
    "Grayscale" to "灰階",
    "Invert colors" to "反轉色彩",
    "Volume keys" to "音量鍵",
    "Vertical" to "垂直",
    "Webtoon" to "條漫",
).plus(TraditionalAdditionalTranslations)

private val SimplifiedLongTranslations = mapOf(
    "Cover: {0}" to "封面：{0}",
    "Shinsou X is locked" to "Shinsou X 已锁定",
    "Authenticate to continue." to "请完成身份验证后继续。",
    "Unlock" to "解锁",
    "Title not found" to "找不到作品",
    "This title may have been removed." to "此作品可能已被移除。",
    "Set categories" to "设置分类",
    "Library" to "书库",
    "titles" to "部作品",
    "title(s)" to "部作品",
    "Try a different search or clear the active filters." to "请尝试其他搜索词，或清除当前筛选条件。",
    "Browse sources and add a title to start reading." to "浏览来源并添加作品，即可开始阅读。",
    "Refresh library" to "刷新书库",
    "Change layout" to "更改布局",
    "Continue {0}" to "继续阅读 {0}",
    "Clear search" to "清除搜索",
    "Loading" to "加载中",
    "Cancel" to "取消",
    "Close" to "关闭",
    "Search" to "搜索",
    "Save" to "保存",
    "Delete" to "删除",
    "Remove" to "移除",
    "Back" to "返回",
    "Language" to "语言",
    "System" to "系统默认",
    "English" to "English",
    "繁體中文" to "繁体中文",
    "简体中文" to "简体中文",
    "Changes apply immediately" to "更改会立即应用",
    "Starting screen" to "启动页面",
    "Confirm before closing" to "关闭前确认",
    "Ask before closing the desktop application" to "关闭桌面应用程序前先询问",
    "Date format" to "日期格式",
    "Theme" to "主题",
    "Compact grid" to "紧凑网格",
    "Comfortable grid" to "舒适网格",
    "List" to "列表",
    "Cover only grid" to "仅封面网格",
    "Pager ltr" to "翻页（从左到右）",
    "Pager rtl" to "翻页（从右到左）",
    "Pager vertical" to "垂直翻页",
    "Continuous vertical" to "连续垂直",
    "Free" to "自由",
    "Portrait" to "竖屏",
    "Landscape" to "横屏",
    "Sensor portrait" to "传感器竖屏",
    "Sensor landscape" to "传感器横屏",
    "AMOLED black" to "AMOLED 黑色",
    "Use pure black surfaces in dark mode" to "在深色模式使用纯黑色表面",
    "Relative timestamps" to "相对时间",
    "Show “2h ago” instead of a clock time" to "显示“2 小时前”而非时钟时间",
    "All" to "全部",
    "Check again" to "再次检查",
    "Sync now" to "立即同步",
    "Enabled languages" to "启用的语言",
    "App lock" to "应用锁",
    "Secure screen" to "安全屏幕",
    "Manage categories" to "管理分类",
    "New category" to "新建分类",
    "Rename category" to "重命名分类",
    "Delete {0}?" to "删除“{0}”？",
    "Titles in this category will be moved to the default category." to "此分类中的作品会移至默认分类。",
    "Category name" to "分类名称",
    "Tracking" to "追踪",
    "Log out" to "退出登录",
    "Search manga" to "搜索漫画",
    "Complete login" to "完成登录",
    "Status" to "状态",
    "Progress" to "进度",
    "Update" to "更新",
    "Reading mode" to "阅读模式",
    "Brightness" to "亮度",
    "Color filter" to "色彩滤镜",
    "Open in browser" to "在浏览器中打开",
    "Return to chapter" to "返回章节",
    "Retry this page" to "重试此页",
    "No chapters" to "没有章节",
    "Show all" to "显示全部",
    "Recent" to "最近",
    "Upcoming" to "即将到来",
    "Clear history" to "清除历史",
    "No expected updates" to "没有预期更新",
    "Download queue is empty" to "下载队列为空",
    "Back up now" to "立即备份",
    "Delete automatic backup?" to "删除自动备份？",
    "Privacy policy" to "隐私政策",
    "No cookies saved" to "没有已保存的 Cookie",
    "Add cookie" to "添加 Cookie",
    "Clear cookies" to "清除 Cookie",
    "Repositories" to "存储库",
    "Add repository" to "添加存储库",
    "No extensions" to "没有扩展",
    "Migrate" to "迁移",
    "Load more" to "加载更多",
    "Reset" to "重置",
    "Apply" to "应用",
    "None" to "无",
    "Ascending" to "升序",
    "Descending" to "降序",
    "Device authentication is unavailable on this platform." to "此平台无法使用设备验证。",
    "Secure-screen protection is unavailable on this platform." to "此平台无法使用安全屏幕保护。",
    "Set up a device passcode, PIN, password, or biometric authentication to use app lock." to "请设置设备密码、PIN、密码或生物识别验证，才能使用应用锁。",
    "App lock is unavailable on Desktop because macOS authentication is not implemented." to "桌面版无法使用应用锁，因为尚未实现 macOS 验证。",
    "Secure screen is unavailable on Desktop because macOS does not expose this protection here." to "桌面版无法使用安全屏幕，因为 macOS 未在此提供这项保护。",
    "Checking iCloud Drive availability…" to "正在检查 iCloud Drive 可用性…",
    "A single Shinsou X snapshot will be stored in the app's iCloud Drive container." to "单个 Shinsou X 快照会存储在应用的 iCloud Drive 容器中。",
    "The remote snapshot service is unavailable." to "远程快照服务不可用。",
).toMutableMap().apply {
    // Fill any newly added long-tail key from the Traditional table while
    // retaining the explicit Simplified wording above.
    TraditionalLongTranslations.forEach { (key, value) ->
        if (key !in this) this[key] = value.toSimplifiedChinese()
    }
}

private fun String.toSimplifiedChinese(): String {
    val source = this
    val converted = buildString(source.length) {
        // Keep the table compact while still covering every long-tail key.
        // The replacement set contains the characters used by the UI;
        // explicit Simplified entries above remain available for wording
        // differences.
        val replacements = mapOf(
        // Common compatibility characters which occur in the long-tail
        // Traditional copy.  Keep this table explicit and deterministic so
        // it works on every KMP target without pulling in a platform
        // conversion library.
        '並' to '并', '併' to '并', '裡' to '里', '裏' to '里', '綫' to '线',
        '臺' to '台', '爲' to '为', '衆' to '众', '啓' to '启', '佈' to '布',
        '妳' to '你', '們' to '们', '實' to '实', '門' to '门', '閒' to '闲',
        '點' to '点', '國' to '国', '會' to '会', '萬' to '万', '說' to '说',
        '過' to '过', '經' to '经', '廣' to '广', '東' to '东', '製' to '制',
        '價' to '价', '貳' to '贰', '參' to '参', '邊' to '边', '讓' to '让',
        '見' to '见', '貝' to '贝', '聲' to '声', '聽' to '听', '話' to '话',
        '順' to '顺',
        '記' to '记', '難' to '难', '雜' to '杂', '靈' to '灵', '歡' to '欢',
        '淨' to '净', '側' to '侧', '傳' to '传', '優' to '优',
        '剛' to '刚', '務' to '务', '區' to '区', '協' to '协', '問' to '问',
        '嗎' to '吗', '圍' to '围', '帳' to '帐', '導' to '导', '徑' to '径',
        '掛' to '挂', '換' to '换', '擊' to '击', '擱' to '搁', '擷' to '撷',
        '棄' to '弃', '欄' to '栏', '測' to '测', '準' to '准', '範' to '范',
        '籤' to '签', '級' to '级', '給' to '给', '綁' to '绑', '縮' to '缩',
        '缺' to '缺', '處' to '处', '衝' to '冲', '規' to '规', '視' to '视',
        '訂' to '订', '訊' to '讯', '評' to '评', '詞' to '词', '該' to '该',
        '詳' to '详', '識' to '识', '護' to '护', '貼' to '贴', '輯' to '辑',
        '載' to '载', '轉' to '转', '錄' to '录', '鑰' to '钥', '閒' to '闲',
        '書' to '书', '庫' to '库', '歷' to '历', '瀏' to '浏', '覽' to '览',
        '擴' to '扩', '套' to '套', '遷' to '迁', '統' to '统', '計' to '计',
        '設' to '设', '備' to '备', '與' to '与', '還' to '还', '關' to '关',
        '於' to '于', '尋' to '寻', '詢' to '询', '篩' to '筛', '選' to '选',
        '儲' to '储', '刪' to '删', '除' to '除', '移' to '移', '試' to '试',
        '項' to '项', '標' to '标', '讀' to '读', '類' to '类', '繼' to '继',
        '續' to '续', '閱' to '阅', '覽' to '览', '章' to '章', '節' to '节',
        '設' to '设', '預' to '预', '設' to '设', '啟' to '启', '動' to '动',
        '畫' to '画', '面' to '面', '應' to '应', '用' to '用', '程' to '程',
        '式' to '式', '詢' to '询', '確' to '确', '認' to '认', '時' to '时',
        '間' to '间', '純' to '纯', '黑' to '黑', '顯' to '显', '示' to '示',
        '為' to '为', '預' to '预', '設' to '设', '網' to '网', '域' to '域',
        '檢' to '检', '查' to '查', '啟' to '启', '用' to '用', '語' to '语',
        '變' to '变', '更' to '更', '即' to '即', '時' to '时', '無' to '无',
        '備' to '备', '份' to '份', '狀' to '状', '態' to '态', '與' to '与',
        '復' to '复', '原' to '原', '載' to '载', '入' to '入', '匯' to '汇',
        '產' to '产', '業' to '业', '網' to '网', '頁' to '页', '錯' to '错',
        '誤' to '误', '開' to '开', '閉' to '闭', '返' to '返', '回' to '回',
        '進' to '进', '行' to '行', '題' to '题', '組' to '组', '隱' to '隐',
        '藏' to '藏', '從' to '从', '這' to '这', '個' to '个', '條' to '条',
        '掃' to '扫', '描' to '描', '譯' to '译', '顏' to '颜', '色' to '色',
        '濾' to '滤', '鏡' to '镜', '亮' to '亮', '度' to '度', '灰' to '灰',
        '階' to '阶', '反' to '反', '轉' to '转', '音' to '音', '量' to '量',
        '鍵' to '键', '垂' to '垂', '直' to '直', '條' to '条', '漫' to '漫',
        '現' to '现', '場' to '场', '總' to '总', '數' to '数', '據' to '据',
        '標' to '标', '題' to '题', '專' to '专', '案' to '案', '聯' to '联',
        '絡' to '络', '隨' to '随', '機' to '机', '種' to '种', '庫' to '库',
        '擇' to '择', '選' to '选', '讀' to '读', '條' to '条', '與' to '与',
        '來' to '来', '請' to '请', '沒' to '没', '稱' to '称', '蹤' to '踪',
        '裝' to '装', '碼' to '码', '鎖' to '锁', '連' to '连', '資' to '资',
        '權' to '权', '結' to '结', '執' to '执', '驗' to '验', '證' to '证',
        '觀' to '观', '體' to '体', '簡' to '简', '橫' to '横', '僅' to '仅',
        '將' to '将', '遞' to '递', '暫' to '暂', '淺' to '浅', '緊' to '紧',
        '湊' to '凑', '單' to '单', '對' to '对', '鐘' to '钟', '減' to '减',
        '檔' to '档', '隊' to '队', '離' to '离', '線' to '线', '圖' to '图',
        '損' to '损', '壞' to '坏', '攜' to '携', '發' to '发', '較' to '较',
        '許' to '许', '擋' to '挡', '議' to '议', '調' to '调', '熱' to '热',
        '註' to '注', '複' to '复', '編' to '编', '號' to '号', '螢' to '萤',
        '後' to '后', '嘗' to '尝',
        )
        for (character in source) append(replacements[character] ?: character)
    }
    // A few terms are conventionally translated as words rather than as
    // independent characters (for example 程式 → 程序 and 佇列 → 队列).
    return converted
        // Character conversion cannot express terminology changes.  Apply
        // the few UI-wide terms after conversion so long sentences read as
        // native Simplified Chinese instead of a character-by-character mix.
        .replace("应用程式", "应用程序")
        .replace("扩充套件", "扩展")
        .replace("擴充套件", "扩展")
        .replace("程式", "程序")
        .replace("設定", "设置")
        .replace("设定", "设置")
        .replace("登入", "登录")
        .replace("帳號", "账号")
        .replace("網路", "网络")
        .replace("网路", "网络")
        .replace("網域", "域名")
        .replace("网域", "域名")
        .replace("頁面", "页面")
        .replace("裝置", "设备")
        .replace("装置", "设备")
        .replace("螢幕", "屏幕")
        .replace("萤幕", "屏幕")
        .replace("檔案", "文件")
        .replace("档案", "文件")
        .replace("資料", "资料")
        .replace("介面", "界面")
        .replace("外掛", "插件")
        .replace("外挂", "插件")
        .replace("權杖", "令牌")
        .replace("权杖", "令牌")
        .replace("匯入", "导入")
        .replace("汇入", "导入")
        .replace("匯出", "导出")
        .replace("汇出", "导出")
        .replace("帳號", "账号")
        .replace("帐号", "账号")
        .replace("佇列", "队列")
}

private val TraditionalChineseStrings = EnglishStrings.copy(
    library = "書庫", updates = "更新", history = "歷史", browse = "瀏覽", more = "更多",
    sources = "來源", extensions = "擴充套件", migration = "遷移", downloads = "下載",
    statistics = "統計", settings = "設定", backup = "備份與還原", about = "關於",
    search = "搜尋", searchLibrary = "搜尋書庫", refresh = "重新整理", filter = "篩選", sort = "排序",
    all = "全部", done = "完成", cancel = "取消", save = "儲存", close = "關閉", delete = "刪除",
    remove = "移除", retry = "重試", share = "分享", selectAll = "全選", selected = "項已選取",
    markRead = "標為已讀", markUnread = "標為未讀", moveToCategory = "移至分類",
    continueReading = "繼續閱讀", favorite = "加入書庫", unfavorite = "已在書庫", download = "下載",
    install = "安裝", uninstall = "解除安裝", enable = "啟用", disable = "停用", chapters = "章節",
    readerSettings = "閱讀器設定", previousChapter = "上一章", nextChapter = "下一章", page = "頁",
    readerModeLeftToRight = "翻頁（左至右）", readerModeRightToLeft = "翻頁（右至左）",
    readerModeVertical = "垂直翻頁", readerModeWebtoon = "條漫", readerModeContinuousVertical = "連續垂直",
    pageTurnAnimation = "翻頁動畫", pageTurnAnimationDescription = "切換頁面時顯示動畫",
    clearFilters = "清除篩選", noMatches = "沒有符合項目", libraryEmpty = "書庫是空的",
    noUpdates = "沒有最近更新", noHistory = "沒有閱讀歷史", createBackup = "建立備份", restoreBackup = "還原備份",
    translations = TraditionalLongTranslations,
)

private val SimplifiedChineseStrings = TraditionalChineseStrings.copy(
    library = "书库", updates = "更新", history = "历史", browse = "浏览", more = "更多",
    sources = "来源", extensions = "扩展", migration = "迁移", downloads = "下载", statistics = "统计",
    settings = "设置", backup = "备份与恢复", about = "关于", search = "搜索", searchLibrary = "搜索书库",
    refresh = "刷新", filter = "筛选", sort = "排序", all = "全部", done = "完成", cancel = "取消",
    save = "保存", close = "关闭", delete = "删除", remove = "移除", retry = "重试", share = "分享",
    selectAll = "全选", selected = "项已选择", markRead = "标为已读", markUnread = "标为未读",
    moveToCategory = "移至分类", continueReading = "继续阅读", favorite = "加入书库", unfavorite = "已在书库",
    download = "下载", install = "安装", uninstall = "卸载", enable = "启用", disable = "停用",
    chapters = "章节", readerSettings = "阅读器设置", previousChapter = "上一章", nextChapter = "下一章",
    page = "页", clearFilters = "清除筛选", noMatches = "没有匹配项", libraryEmpty = "书库为空",
    readerModeLeftToRight = "翻页（从左到右）", readerModeRightToLeft = "翻页（从右到左）",
    readerModeVertical = "垂直翻页", readerModeWebtoon = "条漫", readerModeContinuousVertical = "连续垂直",
    pageTurnAnimation = "翻页动画", pageTurnAnimationDescription = "切换页面时显示动画",
    noUpdates = "没有最近更新", noHistory = "没有阅读历史", createBackup = "创建备份", restoreBackup = "恢复备份",
    translations = SimplifiedLongTranslations,
)

private val JapaneseStrings = EnglishStrings.copy(
    library = "ライブラリ", updates = "更新", history = "履歴", browse = "ブラウズ", more = "その他",
    sources = "ソース", extensions = "拡張機能", migration = "移行", downloads = "ダウンロード",
    statistics = "統計", settings = "設定", backup = "バックアップと復元", about = "このアプリについて",
    search = "検索", searchLibrary = "ライブラリを検索", refresh = "更新", filter = "絞り込み", sort = "並べ替え",
    all = "すべて", done = "完了", cancel = "キャンセル", save = "保存", close = "閉じる", delete = "削除",
    remove = "削除", retry = "再試行", share = "共有", selectAll = "すべて選択", selected = "件を選択中",
    markRead = "既読にする", markUnread = "未読にする", moveToCategory = "カテゴリへ移動",
    continueReading = "続きを読む", favorite = "ライブラリに追加", unfavorite = "ライブラリ内", download = "ダウンロード",
    install = "インストール", uninstall = "アンインストール", enable = "有効", disable = "無効",
    chapters = "章", readerSettings = "リーダー設定", previousChapter = "前の章", nextChapter = "次の章",
    page = "ページ", clearFilters = "絞り込みを解除", noMatches = "一致する項目なし", libraryEmpty = "ライブラリは空です",
    readerModeLeftToRight = "ページ送り（左から右）", readerModeRightToLeft = "ページ送り（右から左）",
    readerModeVertical = "縦方向のページ送り", readerModeWebtoon = "ウェブトゥーン",
    readerModeContinuousVertical = "縦スクロール", pageTurnAnimation = "ページ切り替えアニメーション",
    pageTurnAnimationDescription = "ページを切り替えるときにアニメーションを表示",
    noUpdates = "最近の更新はありません", noHistory = "閲覧履歴はありません", createBackup = "バックアップを作成",
    restoreBackup = "バックアップを復元",
)

private val KoreanStrings = EnglishStrings.copy(
    library = "라이브러리", updates = "업데이트", history = "기록", browse = "탐색", more = "더보기",
    sources = "소스", extensions = "확장", migration = "마이그레이션", downloads = "다운로드",
    statistics = "통계", settings = "설정", backup = "백업 및 복원", about = "정보", search = "검색",
    searchLibrary = "라이브러리 검색", refresh = "새로 고침", filter = "필터", sort = "정렬", all = "전체",
    done = "완료", cancel = "취소", save = "저장", close = "닫기", delete = "삭제", remove = "제거",
    retry = "다시 시도", share = "공유", selectAll = "전체 선택", selected = "개 선택됨", markRead = "읽음으로 표시",
    markUnread = "읽지 않음으로 표시", moveToCategory = "카테고리로 이동", continueReading = "계속 읽기",
    favorite = "라이브러리에 추가", unfavorite = "라이브러리에 있음", download = "다운로드", install = "설치",
    uninstall = "제거", enable = "사용", disable = "사용 안 함", chapters = "챕터", readerSettings = "리더 설정",
    previousChapter = "이전 챕터", nextChapter = "다음 챕터", page = "페이지", clearFilters = "필터 지우기",
    readerModeLeftToRight = "페이지 넘김(왼쪽에서 오른쪽)", readerModeRightToLeft = "페이지 넘김(오른쪽에서 왼쪽)",
    readerModeVertical = "세로 페이지 넘김", readerModeWebtoon = "웹툰", readerModeContinuousVertical = "연속 세로 스크롤",
    pageTurnAnimation = "페이지 전환 애니메이션", pageTurnAnimationDescription = "페이지를 전환할 때 애니메이션 표시",
    noMatches = "검색 결과 없음", libraryEmpty = "라이브러리가 비어 있습니다", noUpdates = "최근 업데이트 없음",
    noHistory = "읽기 기록 없음", createBackup = "백업 만들기", restoreBackup = "백업 복원",
)

private val FrenchStrings = EnglishStrings.copy(
    library = "Bibliothèque", updates = "Mises à jour", history = "Historique", browse = "Parcourir", more = "Plus",
    sources = "Sources", extensions = "Extensions", migration = "Migration", downloads = "Téléchargements",
    statistics = "Statistiques", settings = "Réglages", backup = "Sauvegarde et restauration", about = "À propos",
    search = "Rechercher", searchLibrary = "Rechercher dans la bibliothèque", refresh = "Actualiser", filter = "Filtrer",
    sort = "Trier", all = "Tout", done = "Terminé", cancel = "Annuler", save = "Enregistrer", close = "Fermer",
    delete = "Supprimer", remove = "Retirer", retry = "Réessayer", share = "Partager", selectAll = "Tout sélectionner",
    selected = "sélectionné(s)", markRead = "Marquer comme lu", markUnread = "Marquer comme non lu",
    moveToCategory = "Déplacer vers une catégorie", continueReading = "Continuer la lecture", favorite = "Ajouter à la bibliothèque",
    unfavorite = "Dans la bibliothèque", download = "Télécharger", install = "Installer", uninstall = "Désinstaller",
    enable = "Activer", disable = "Désactiver", chapters = "Chapitres", readerSettings = "Réglages du lecteur",
    previousChapter = "Chapitre précédent", nextChapter = "Chapitre suivant", page = "Page", clearFilters = "Effacer les filtres",
    readerModeLeftToRight = "Pages · de gauche à droite", readerModeRightToLeft = "Pages · de droite à gauche",
    readerModeVertical = "Pagination verticale", readerModeWebtoon = "Webtoon",
    readerModeContinuousVertical = "Défilement vertical continu", pageTurnAnimation = "Animation de changement de page",
    pageTurnAnimationDescription = "Animer la transition entre les pages",
    noMatches = "Aucun résultat", libraryEmpty = "Votre bibliothèque est vide", noUpdates = "Aucune mise à jour récente",
    noHistory = "Aucun historique", createBackup = "Créer une sauvegarde", restoreBackup = "Restaurer une sauvegarde",
)

private val GermanStrings = EnglishStrings.copy(
    library = "Bibliothek", updates = "Updates", history = "Verlauf", browse = "Stöbern", more = "Mehr",
    sources = "Quellen", extensions = "Erweiterungen", migration = "Migration", downloads = "Downloads",
    statistics = "Statistik", settings = "Einstellungen", backup = "Sichern & Wiederherstellen", about = "Über",
    search = "Suchen", searchLibrary = "Bibliothek durchsuchen", refresh = "Aktualisieren", filter = "Filtern", sort = "Sortieren",
    all = "Alle", done = "Fertig", cancel = "Abbrechen", save = "Sichern", close = "Schließen", delete = "Löschen",
    remove = "Entfernen", retry = "Wiederholen", share = "Teilen", selectAll = "Alle auswählen", selected = "ausgewählt",
    markRead = "Als gelesen markieren", markUnread = "Als ungelesen markieren", moveToCategory = "In Kategorie verschieben",
    continueReading = "Weiterlesen", favorite = "Zur Bibliothek hinzufügen", unfavorite = "In Bibliothek", download = "Herunterladen",
    install = "Installieren", uninstall = "Deinstallieren", enable = "Aktivieren", disable = "Deaktivieren", chapters = "Kapitel",
    readerSettings = "Reader-Einstellungen", previousChapter = "Vorheriges Kapitel", nextChapter = "Nächstes Kapitel",
    page = "Seite", clearFilters = "Filter löschen", noMatches = "Keine Treffer", libraryEmpty = "Deine Bibliothek ist leer",
    readerModeLeftToRight = "Seiten · von links nach rechts", readerModeRightToLeft = "Seiten · von rechts nach links",
    readerModeVertical = "Vertikales Blättern", readerModeWebtoon = "Webtoon",
    readerModeContinuousVertical = "Fortlaufend vertikal", pageTurnAnimation = "Seitenwechsel animieren",
    pageTurnAnimationDescription = "Übergänge zwischen Seiten animieren",
    noUpdates = "Keine neuen Updates", noHistory = "Kein Leseverlauf", createBackup = "Backup erstellen",
    restoreBackup = "Backup wiederherstellen",
)

private val SpanishStrings = EnglishStrings.copy(
    library = "Biblioteca", updates = "Actualizaciones", history = "Historial", browse = "Explorar", more = "Más",
    sources = "Fuentes", extensions = "Extensiones", migration = "Migración", downloads = "Descargas",
    statistics = "Estadísticas", settings = "Ajustes", backup = "Copia y restauración", about = "Acerca de",
    search = "Buscar", searchLibrary = "Buscar en la biblioteca", refresh = "Actualizar", filter = "Filtrar", sort = "Ordenar",
    all = "Todo", done = "Listo", cancel = "Cancelar", save = "Guardar", close = "Cerrar", delete = "Eliminar",
    remove = "Quitar", retry = "Reintentar", share = "Compartir", selectAll = "Seleccionar todo", selected = "seleccionado(s)",
    markRead = "Marcar como leído", markUnread = "Marcar como no leído", moveToCategory = "Mover a categoría",
    continueReading = "Continuar leyendo", favorite = "Añadir a la biblioteca", unfavorite = "En la biblioteca", download = "Descargar",
    install = "Instalar", uninstall = "Desinstalar", enable = "Activar", disable = "Desactivar", chapters = "Capítulos",
    readerSettings = "Ajustes del lector", previousChapter = "Capítulo anterior", nextChapter = "Capítulo siguiente",
    page = "Página", clearFilters = "Borrar filtros", noMatches = "Sin resultados", libraryEmpty = "Tu biblioteca está vacía",
    readerModeLeftToRight = "Páginas · de izquierda a derecha", readerModeRightToLeft = "Páginas · de derecha a izquierda",
    readerModeVertical = "Paginación vertical", readerModeWebtoon = "Webtoon",
    readerModeContinuousVertical = "Desplazamiento vertical continuo", pageTurnAnimation = "Animación al pasar página",
    pageTurnAnimationDescription = "Animar la transición entre páginas",
    noUpdates = "No hay actualizaciones recientes", noHistory = "No hay historial de lectura", createBackup = "Crear copia",
    restoreBackup = "Restaurar copia",
)

private val PortugueseStrings = EnglishStrings.copy(
    library = "Biblioteca", updates = "Atualizações", history = "Histórico", browse = "Explorar", more = "Mais",
    sources = "Fontes", extensions = "Extensões", migration = "Migração", downloads = "Downloads",
    statistics = "Estatísticas", settings = "Configurações", backup = "Backup e restauração", about = "Sobre",
    search = "Pesquisar", searchLibrary = "Pesquisar na biblioteca", refresh = "Atualizar", filter = "Filtrar", sort = "Ordenar",
    all = "Tudo", done = "Concluído", cancel = "Cancelar", save = "Salvar", close = "Fechar", delete = "Excluir",
    remove = "Remover", retry = "Tentar novamente", share = "Compartilhar", selectAll = "Selecionar tudo",
    selected = "selecionado(s)", markRead = "Marcar como lido", markUnread = "Marcar como não lido",
    moveToCategory = "Mover para categoria", continueReading = "Continuar lendo", favorite = "Adicionar à biblioteca",
    unfavorite = "Na biblioteca", download = "Baixar", install = "Instalar", uninstall = "Desinstalar", enable = "Ativar",
    disable = "Desativar", chapters = "Capítulos", readerSettings = "Configurações do leitor", previousChapter = "Capítulo anterior",
    nextChapter = "Próximo capítulo", page = "Página", clearFilters = "Limpar filtros", noMatches = "Nenhum resultado",
    readerModeLeftToRight = "Páginas · da esquerda para a direita", readerModeRightToLeft = "Páginas · da direita para a esquerda",
    readerModeVertical = "Paginação vertical", readerModeWebtoon = "Webtoon",
    readerModeContinuousVertical = "Rolagem vertical contínua", pageTurnAnimation = "Animação ao virar página",
    pageTurnAnimationDescription = "Animar a transição entre páginas",
    libraryEmpty = "Sua biblioteca está vazia", noUpdates = "Nenhuma atualização recente", noHistory = "Nenhum histórico de leitura",
    createBackup = "Criar backup", restoreBackup = "Restaurar backup",
)
