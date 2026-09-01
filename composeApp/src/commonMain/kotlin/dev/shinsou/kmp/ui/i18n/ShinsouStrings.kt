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
    /** Account-owned collection exposed by a remote source (for example Wenku8 bookcase). */
    val myLibrary: String = "My library",
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
    "My library" -> myLibrary
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

private val EnglishStrings = ShinsouStrings(
    translations = mapOf(
        "Manga" to "Manga",
        "Novel" to "Novel",
        "Mixed" to "Mixed",
        "Unknown type" to "Unknown",
    ),
)

private val LibraryContentTypeTranslations = mapOf(
    "zh-TW" to mapOf("Manga" to "漫畫", "Novel" to "小說", "Mixed" to "混合", "Unknown type" to "未知"),
    "zh-CN" to mapOf("Manga" to "漫画", "Novel" to "小说", "Mixed" to "混合", "Unknown type" to "未知"),
    "ja" to mapOf("Manga" to "マンガ", "Novel" to "小説", "Mixed" to "混合", "Unknown type" to "不明"),
    "ko" to mapOf("Manga" to "만화", "Novel" to "소설", "Mixed" to "혼합", "Unknown type" to "알 수 없음"),
    "fr" to mapOf("Manga" to "Manga", "Novel" to "Roman", "Mixed" to "Mixte", "Unknown type" to "Type inconnu"),
    "de" to mapOf("Manga" to "Manga", "Novel" to "Roman", "Mixed" to "Gemischt", "Unknown type" to "Unbekannt"),
    "es" to mapOf("Manga" to "Manga", "Novel" to "Novela", "Mixed" to "Mixto", "Unknown type" to "Desconocido"),
    "pt" to mapOf("Manga" to "Mangá", "Novel" to "Romance", "Mixed" to "Misto", "Unknown type" to "Desconhecido"),
)

/** Additional copy for settings, tracking, and platform screens. */
private val TraditionalAdditionalTranslations = mapOf(
    "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to
        "下載內容、本機來源檔案、擴充套件安裝包、Cookie、密碼與 API 金鑰不會同步。",
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
    "Browser session imported successfully." to "已成功匯入瀏覽器工作階段。",
    "Browser session" to "瀏覽器工作階段",
    "The browser session could not be imported." to "無法匯入瀏覽器工作階段。",
    "unable to save browser cookies" to "無法儲存瀏覽器 Cookie",
    "Web challenge cancelled. No browser cookies were imported." to "Web 驗證已取消，沒有匯入瀏覽器 Cookie。",
    "Web challenge cancelled. No browser session was imported." to "Web 驗證已取消，沒有匯入瀏覽器工作階段。",
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
    "Default for sources set to Follow global. Per-source overrides remain authoritative." to
        "套件選擇「跟隨全域」時使用此設定；套件的強制啟用或強制關閉仍會優先套用。",
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
    "Unable to recover this legacy extension favorite. Remove it and add the title again from its source." to
        "無法還原這筆舊版擴充套件收藏。請移除後，再從原來源重新加入作品。",
    "The title opened, but its repaired extension identity could not be saved." to
        "作品已開啟，但無法儲存修復後的擴充套件識別資料。",
    "Download-only mode" to "僅下載模式",
    "Edit notes" to "編輯備註",
    "Enable snapshot sync" to "啟用快照同步",
    "Error: no usable cookies were found for this source. Complete the challenge and try again." to "錯誤：找不到此來源可用的 Cookie。請完成驗證後再試一次。",
    "Error: no usable browser session was found for this source. Complete website sign-in and try again." to "錯誤：找不到此來源可用的瀏覽器工作階段。請先完成網站登入後再試一次。",
    "Error: Cloudflare verification is not complete because {0} is missing. Keep this browser open, complete verification and website sign-in, then try again." to "錯誤：Cloudflare 驗證尚未完成，缺少 {0}。請保持此瀏覽器開啟，在這裡完成驗證與網站登入後再試一次。",
    "Error: website sign-in is not complete. Keep this browser open, finish signing in, then try again." to "錯誤：網站登入尚未完成。請保持此瀏覽器開啟，完成登入後再試一次。",
    "Complete verification and sign in to the website in this browser, then import its cookies." to "請在此瀏覽器完成驗證與網站登入，然後匯入 Cookie。",
    "Complete verification and sign in to the website in this browser, then import its browser session." to "請在此瀏覽器完成驗證與網站登入，然後匯入瀏覽器工作階段。",
    "Verification page loaded. Complete verification and website sign-in here, then choose Import cookies." to "驗證頁面已載入。請在這裡完成驗證與網站登入，然後選擇「匯入 Cookie」。",
    "Verification page loaded. Complete verification and website sign-in here, then choose Import browser session." to "驗證頁面已載入。請在這裡完成驗證與網站登入，然後選擇「匯入瀏覽器工作階段」。",
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
    "Import browser session" to "匯入瀏覽器工作階段",
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
    "Reading the isolated browser session…" to "正在讀取隔離的瀏覽器工作階段…",
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
    "Shinsou X · version 1.0.1-beta.7" to "Shinsou X · 版本 1.0.1-beta.7",
    "Shinsou X 1.0.1-beta.7" to "Shinsou X 1.0.1-beta.7",
    "Shinsou X is a local-first manga library and reader. It does not operate an analytics or advertising service." to "Shinsou X 是以本機優先的漫畫書庫與閱讀器，不會執行分析或廣告服務。",
    "Show NSFW sources" to "顯示 NSFW 來源",
    "Show page number" to "顯示頁碼",
    "Show token" to "顯示權杖",
    "Showing the first {0} results. Refine the search to see more." to "顯示前 {0} 筆結果。請縮小搜尋範圍以查看更多。",
    "Signed in" to "已登入",
    "Sign in in browser" to "在瀏覽器登入",
    "Skip alternate copies with the same chapter number" to "略過相同章節編號的替代版本",
    "Skip duplicate chapters" to "略過重複章節",
    "Skip filtered chapters" to "略過篩選出的章節",
    "Skip read chapters" to "略過已讀章節",
    "Leave blank to use this device's browser User-Agent for Worker proxy requests. An imported Cloudflare session remains bound to the User-Agent captured with its cookies." to "留空時，Worker Proxy 請求會自動使用此裝置的瀏覽器 User-Agent。已匯入的 Cloudflare 工作階段仍會使用與 Cookie 一同擷取的 User-Agent。",
    "Follow global" to "跟隨全域",
    "Force enable" to "強制啟用",
    "Force disable" to "強制關閉",
    "Follow global uses the Advanced setting. Force enable and Force disable override it for this source." to
        "跟隨全域會使用進階設定；強制啟用或強制關閉會覆寫此套件的全域設定。",
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
    "This source must sign in through its website. The app will import only the source-declared browser session data and will not call its direct password login API." to "此來源必須透過網站登入。應用程式只會匯入來源明確宣告的瀏覽器工作階段資料，不會呼叫其直接帳密登入 API。",
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
    "Uses a browser-compatible User-Agent and imports only cookies valid for the source domain and path." to "使用與瀏覽器相容的 User-Agent，只匯入對來源網域與路徑有效的 Cookie。",
    "Uses a browser-compatible User-Agent. If credentials are filled above, the isolated same-origin browser fills and submits the website login form automatically; only cookies valid for the source domain and path are imported." to "使用與瀏覽器相容的 User-Agent。若已在上方填入帳號密碼，隔離的同源瀏覽器會自動填寫並送出網站登入表單；只匯入對來源網域與路徑有效的 Cookie。",
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

/** Strings used by both the encrypted Cloudflare sync and legacy iCloud sync panels. */
private val TraditionalSyncTranslations = mapOf(
    "Cloudflare encrypted sync" to "Cloudflare 加密同步",
    "Not configured" to "未設定",
    "Deploying" to "部署中",
    "Linking" to "連結中",
    "Ready" to "就緒",
    "Device revoked" to "裝置已撤銷",
    "Error" to "錯誤",
    "Cursor {0}/{1} · {2} pending changes · {3} pending uploads" to
        "游標 {0}/{1} · {2} 項變更待處理 · {3} 項上傳待處理",
    "Encrypted event sync is unavailable in this runtime." to "此執行環境無法使用加密事件同步。",
    "Synchronized data needs repair" to "同步資料需要修復",
    "{0} records could not be projected. They remain in the encrypted replica and were not silently discarded." to
        "{0} 筆記錄無法投影。這些記錄仍保留在加密副本中，未被靜默丟棄。",
    "Retry validation" to "重試驗證",
    "Missing synchronized dependency" to "缺少同步相依項目",
    "Identity mapping collision" to "身分對應衝突",
    "Invalid synchronized record" to "無效的同步記錄",
    "Repair local identity mapping" to "修復本機身分對應",
    "Confirm repository signing key" to "確認儲存庫簽署金鑰",
    "This repository remains pinned to its previous key and cannot expand trust until you confirm the exact new fingerprint." to
        "此儲存庫仍固定使用先前的金鑰；確認完全相符的新指紋後才能擴大信任。",
    "Trusted: {0}" to "已信任：{0}",
    "None on this device" to "此裝置上沒有",
    "Proposed: {0}" to "提議：{0}",
    "Rejected on this device" to "已在此裝置拒絕",
    "Trust exact fingerprint" to "信任完全相符的指紋",
    "Reject" to "拒絕",
    "Setup, invite, pairing or emergency handoff link / code" to "設定、邀請、配對或緊急交接連結／代碼",
    "Connect" to "連線",
    "Paste" to "貼上",
    "Scan QR" to "掃描 QR",
    "Bootstrap secret" to "Bootstrap 秘密",
    "Create sync service" to "建立同步服務",
    "Open deployment page" to "開啟部署頁面",
    "The deployment page is required to create your private sync service. The bootstrap secret is kept on this device and can be reused if you reopen the page." to
        "建立私人同步服務需要開啟部署頁面。Bootstrap 秘密會保留在此裝置上，重新開啟頁面時可重複使用。",
    "Could not copy the bootstrap secret or open the deployment page." to
        "無法複製 Bootstrap 秘密或開啟部署頁面。",
    "Could not copy the bootstrap secret. Copying is required before deployment." to
        "無法複製 Bootstrap 秘密。部署前必須先完成複製。",
    "Could not open the deployment page. Tap Open deployment page to retry." to
        "無法開啟部署頁面，請點選「開啟部署頁面」重試。",
    "Lost every device?" to "所有裝置都遺失了？",
    "Import your Recovery Kit to verify the remote workspace, revoke old devices, rotate its keys and create a replacement kit." to
        "匯入 Recovery Kit 以驗證遠端工作區、撤銷舊裝置、輪替金鑰並建立替代 Kit。",
    "Import Recovery Kit" to "匯入 Recovery Kit",
    "Leave or clear pending workspace" to "離開或清除待處理工作區",
    "Add device" to "新增裝置",
    "Invite user" to "邀請使用者",
    "Refresh devices" to "重新整理裝置",
    "Approve new device" to "核准新裝置",
    "Allow" to "允許",
    "Deny" to "拒絕",
    "Instance usage and quota" to "執行個體用量與配額",
    "{0} users · {1} devices · {2} workspaces" to "{0} 位使用者 · {1} 部裝置 · {2} 個工作區",
    "Stored {0} · reserved {1}" to "已儲存 {0} · 已保留 {1}",
    "Users" to "使用者",
    "Workspaces / user" to "工作區／使用者",
    "Devices / user" to "裝置／使用者",
    "Workspace MiB" to "工作區 MiB",
    "Event KiB" to "事件 KiB",
    "Checkpoint MiB" to "檢查點 MiB",
    "Save quota" to "儲存配額",
    "Refresh usage" to "重新整理用量",
    "{0}: {1} events ({2}), {3} checkpoints ({4})" to "{0}：{1} 個事件（{2}）、{3} 個檢查點（{4}）",
    "This view contains usage metadata only. Encrypted library payloads are never exposed to instance administrators." to
        "此檢視僅包含用量中繼資料。加密的書庫內容永遠不會暴露給執行個體管理員。",
    " (this device)" to "（此裝置）",
    "Revoked" to "已撤銷",
    "Active" to "作用中",
    "Revoke" to "撤銷",
    "Export Recovery Kit" to "匯出 Recovery Kit",
    "Leave workspace" to "離開工作區",
    "Legacy iCloud snapshot" to "舊版 iCloud 快照",
    "Checking availability…" to "正在檢查可用性…",
    "Available" to "可用",
    "Enable snapshot sync" to "啟用快照同步",
    "Sync when app enters foreground" to "應用程式進入前景時同步",
    "Sync status" to "同步狀態",
    "Idle" to "閒置",
    "Checking availability" to "正在檢查可用性",
    "Pulling, merging and uploading" to "擷取、合併與上傳中",
    "Last sync succeeded" to "上次同步成功",
    "Last sync failed" to "上次同步失敗",
    "Last result" to "上次結果",
    "Revision {0} → {1} · {2} conflicts" to "修訂版 {0} → {1} · {2} 個衝突",
    "iCloud snapshot writing is disabled while Cloudflare event sync is configured." to
        "設定 Cloudflare 事件同步後，已停用 iCloud 快照寫入。",
    "Sync QR code" to "同步 QR 碼",
    "This is a one-time secret. Do not post it publicly." to "這是一次性秘密。請勿公開張貼。",
    "Copy" to "複製",
    "Revoke this device?" to "撤銷此裝置？",
    "The device will lose future access and the workspace key will rotate." to "此裝置將失去未來存取權，且工作區金鑰會輪替。",
    "Leave synced workspace?" to "離開已同步工作區？",
    "This device will stop receiving updates. Local data remains until you restore or reset it." to
        "此裝置將停止接收更新。除非還原或重設，否則本機資料會保留。",
    "Leave" to "離開",
    "Sync operation failed." to "同步操作失敗。",
    "Checking the iCloud ubiquity container…" to "正在檢查 iCloud ubiquity 容器…",
    "iCloud Drive is unavailable. Sign in to iCloud and enable iCloud Drive for Shinsou X." to
        "iCloud Drive 無法使用。請登入 iCloud 並為 Shinsou X 啟用 iCloud Drive。",
    "The Shinsou X iCloud Drive container is unavailable. Check the app's iCloud Documents entitlement." to
        "Shinsou X 的 iCloud Drive 容器無法使用。請檢查應用程式的 iCloud Documents 權限。",
    "The Shinsou X iCloud Drive container is unavailable. Check the iCloud Documents entitlement." to
        "Shinsou X 的 iCloud Drive 容器無法使用。請檢查 iCloud Documents 權限。",
    "Cloudflare Sync v2 is configured. Leave that workspace before using legacy snapshot sync." to
        "Cloudflare Sync v2 已設定。使用舊版快照同步前，請先離開該工作區。",
    "Uploaded the first Shinsou X snapshot to iCloud Drive." to "第一份 Shinsou X 快照已上傳至 iCloud Drive。",
    "Local data already matches iCloud Drive." to "本機資料已與 iCloud Drive 相符。",
    "Merged local and remote snapshots." to "已合併本機與遠端快照。",
    "Snapshot sync failed." to "快照同步失敗。",
).plus(
    // These entries are shared with other settings sections but are listed
    // here as well so every sync label remains covered if it is moved later.
    mapOf(
        "iCloud Drive snapshot" to "iCloud Drive 快照",
        "Unavailable" to "不可用",
        "Check again" to "再次檢查",
        "Sync now" to "立即同步",
        "Merge one versioned Shinsou X backup file through iCloud Drive" to
            "透過 iCloud Drive 合併一份具版本的 Shinsou X 備份檔案",
        "Runs only when sync is enabled; identical snapshots do not write again" to
            "只會在啟用同步時執行；相同的快照不會重複寫入",
        "This uses a coordinated single-file snapshot in iCloud Drive. It is not record-level CloudKit synchronization." to
            "這會在 iCloud Drive 使用協調的單檔快照，不是記錄層級的 CloudKit 同步。",
        "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to
            "下載內容、本機來源檔案、擴充套件安裝包、Cookie、密碼與 API 金鑰不會同步。",
    ),
)

private val SimplifiedSyncTranslations = TraditionalSyncTranslations.mapValues { (_, value) ->
    value.toSimplifiedChinese()
}

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
    "Scroll down" to "向下捲動",
    "Turn left" to "向左翻頁",
    "Turn right" to "向右翻頁",
    "Novel typography" to "小說排版",
    "Font size · {0}" to "字級 · {0}",
    "Line height · {0}%" to "行高 · {0}%",
    "Reading width · {0}" to "閱讀欄寬 · {0}",
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
    "Website order" to "網站順序",
    "Reverse website order" to "反向網站順序",
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
    "Login successful." to "登入成功。",
    "Login required" to "需要登入",
    "Sign in to {0} to continue using this source." to "登入 {0} 後才能繼續使用此來源。",
    "Save credentials" to "儲存登入資料",
    "Login failed. Check your username and password." to "登入失敗，請檢查使用者名稱與密碼。",
    "Unable to save credentials" to "無法儲存登入資料",
    "Unable to prepare the source login." to "無法準備來源登入，請重新整理來源後再試。",
    "Unable to read credentials from secure storage." to "無法從本機安全儲存讀取登入資料，請確認鑰匙圈已解鎖並允許 Shinsou X 存取。",
    "Unable to write credentials to secure storage." to "無法將登入資料寫入本機安全儲存，請確認鑰匙圈已解鎖並允許 Shinsou X 存取。",
    "The source login process failed before returning a response." to "來源登入流程在收到網站回應前發生錯誤。",
    "Unable to restore the previous credentials after login failed." to "登入失敗後無法還原原有登入資料，請重新啟動應用程式後再試。",
    "Login finished, but the source state could not be refreshed." to "登入流程已完成，但無法重新整理來源狀態。",
    "The login operation failed unexpectedly." to "登入作業發生未預期的錯誤。",
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
).plus(TraditionalAdditionalTranslations).plus(TraditionalSyncTranslations)

private val SimplifiedLongTranslations = mapOf(
    "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to
        "下载内容、本机来源文件、扩展安装包、Cookie、密码和 API 密钥不同步。",
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
    "Scroll down" to "向下滚动",
    "Turn left" to "向左翻页",
    "Turn right" to "向右翻页",
    "Novel typography" to "小说排版",
    "Font size · {0}" to "字号 · {0}",
    "Line height · {0}%" to "行高 · {0}%",
    "Reading width · {0}" to "阅读栏宽 · {0}",
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
    "Website order" to "网站顺序",
    "Reverse website order" to "反向网站顺序",
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

private val JapaneseSyncTranslations = mapOf(
    "Cloudflare encrypted sync" to "Cloudflare 暗号化同期",
    "Not configured" to "未設定", "Deploying" to "デプロイ中", "Linking" to "リンク中",
    "Ready" to "準備完了", "Device revoked" to "デバイスを失効", "Error" to "エラー",
    "Cursor {0}/{1} · {2} pending changes · {3} pending uploads" to
        "カーソル {0}/{1} · 保留中の変更 {2} 件 · 保留中のアップロード {3} 件",
    "Encrypted event sync is unavailable in this runtime." to "このランタイムでは暗号化イベント同期を利用できません。",
    "Synchronized data needs repair" to "同期データの修復が必要です",
    "{0} records could not be projected. They remain in the encrypted replica and were not silently discarded." to
        "{0} 件のレコードを反映できませんでした。暗号化レプリカに保持され、破棄されていません。",
    "Retry validation" to "検証を再試行", "Missing synchronized dependency" to "同期された依存関係がありません",
    "Identity mapping collision" to "ID マッピングが競合しています", "Invalid synchronized record" to "無効な同期レコード",
    "Repair local identity mapping" to "ローカル ID マッピングを修復", "Confirm repository signing key" to "リポジトリ署名鍵を確認",
    "This repository remains pinned to its previous key and cannot expand trust until you confirm the exact new fingerprint." to
        "このリポジトリは以前の鍵に固定されています。新しいフィンガープリントを正確に確認するまで信頼を拡張できません。",
    "Trusted: {0}" to "信頼済み：{0}", "None on this device" to "このデバイスにはありません",
    "Proposed: {0}" to "提案：{0}", "Rejected on this device" to "このデバイスで拒否済み",
    "Trust exact fingerprint" to "正確なフィンガープリントを信頼", "Reject" to "拒否",
    "Setup, invite, pairing or emergency handoff link / code" to "セットアップ、招待、ペアリング、緊急引き継ぎリンク／コード",
    "Connect" to "接続", "Paste" to "貼り付け", "Scan QR" to "QR をスキャン",
    "Bootstrap secret" to "ブートストラップシークレット", "Create sync service" to "同期サービスを作成", "Open deployment page" to "デプロイページを開く",
    "Lost every device?" to "すべてのデバイスを失いましたか？",
    "Import your Recovery Kit to verify the remote workspace, revoke old devices, rotate its keys and create a replacement kit." to
        "Recovery Kit をインポートしてリモートワークスペースを検証し、古いデバイスを失効、鍵をローテーションして交換キットを作成します。",
    "Import Recovery Kit" to "Recovery Kit をインポート", "Leave or clear pending workspace" to "保留中のワークスペースを離脱または消去",
    "Add device" to "デバイスを追加", "Invite user" to "ユーザーを招待", "Refresh devices" to "デバイスを更新",
    "Approve new device" to "新しいデバイスを承認", "Allow" to "許可", "Deny" to "拒否",
    "Instance usage and quota" to "インスタンス使用量とクォータ",
    "{0} users · {1} devices · {2} workspaces" to "ユーザー {0} · デバイス {1} · ワークスペース {2}",
    "Stored {0} · reserved {1}" to "保存済み {0} · 予約済み {1}", "Users" to "ユーザー",
    "Workspaces / user" to "ワークスペース／ユーザー", "Devices / user" to "デバイス／ユーザー",
    "Workspace MiB" to "ワークスペース MiB", "Event KiB" to "イベント KiB", "Checkpoint MiB" to "チェックポイント MiB",
    "Save quota" to "クォータを保存", "Refresh usage" to "使用量を更新",
    "{0}: {1} events ({2}), {3} checkpoints ({4})" to "{0}：イベント {1} 件（{2}）、チェックポイント {3} 件（{4}）",
    "This view contains usage metadata only. Encrypted library payloads are never exposed to instance administrators." to
        "この画面には使用量のメタデータのみが表示されます。暗号化されたライブラリの内容がインスタンス管理者に公開されることはありません。",
    " (this device)" to "（このデバイス）", "Revoked" to "失効済み", "Active" to "アクティブ", "Revoke" to "失効",
    "Export Recovery Kit" to "Recovery Kit をエクスポート", "Leave workspace" to "ワークスペースを離脱",
    "Legacy iCloud snapshot" to "従来の iCloud スナップショット", "iCloud Drive snapshot" to "iCloud Drive スナップショット",
    "Checking availability…" to "利用可能か確認中…", "Available" to "利用可能", "Unavailable" to "利用不可",
    "Check again" to "再確認", "Enable snapshot sync" to "スナップショット同期を有効化",
    "Merge one versioned Shinsou X backup file through iCloud Drive" to "iCloud Drive 経由でバージョン付き Shinsou X バックアップを 1 つ統合",
    "Sync when app enters foreground" to "アプリがフォアグラウンドになったら同期",
    "Runs only when sync is enabled; identical snapshots do not write again" to "同期が有効な場合のみ実行。同一のスナップショットは再書き込みしません",
    "Sync status" to "同期ステータス", "Idle" to "待機中", "Checking availability" to "利用可能か確認中",
    "Pulling, merging and uploading" to "取得、統合、アップロード中", "Last sync succeeded" to "前回の同期に成功",
    "Last sync failed" to "前回の同期に失敗", "Sync now" to "今すぐ同期", "Last result" to "前回の結果",
    "Revision {0} → {1} · {2} conflicts" to "リビジョン {0} → {1} · 競合 {2} 件",
    "iCloud snapshot writing is disabled while Cloudflare event sync is configured." to "Cloudflare イベント同期の設定中は iCloud スナップショットの書き込みが無効です。",
    "This uses a coordinated single-file snapshot in iCloud Drive. It is not record-level CloudKit synchronization." to "iCloud Drive の調整済み単一ファイルスナップショットを使用します。レコード単位の CloudKit 同期ではありません。",
    "Sync QR code" to "同期 QR コード", "This is a one-time secret. Do not post it publicly." to "これは一度だけ使える秘密です。公開しないでください。",
    "Share" to "共有", "Copy" to "コピー", "Revoke this device?" to "このデバイスを失効しますか？",
    "The device will lose future access and the workspace key will rotate." to "このデバイスは今後アクセスできなくなり、ワークスペース鍵がローテーションされます。",
    "Leave synced workspace?" to "同期済みワークスペースを離脱しますか？",
    "This device will stop receiving updates. Local data remains until you restore or reset it." to "このデバイスは更新を受信しなくなります。復元またはリセットするまでローカルデータは残ります。",
    "Leave" to "離脱", "Sync operation failed." to "同期操作に失敗しました。",
    "Checking the iCloud ubiquity container…" to "iCloud ユビキタスコンテナを確認中…",
    "iCloud Drive is unavailable. Sign in to iCloud and enable iCloud Drive for Shinsou X." to "iCloud Drive は利用できません。iCloud にサインインし、Shinsou X の iCloud Drive を有効にしてください。",
    "The Shinsou X iCloud Drive container is unavailable. Check the app's iCloud Documents entitlement." to "Shinsou X の iCloud Drive コンテナを利用できません。アプリの iCloud Documents 権限を確認してください。",
    "The Shinsou X iCloud Drive container is unavailable. Check the iCloud Documents entitlement." to "Shinsou X の iCloud Drive コンテナを利用できません。iCloud Documents 権限を確認してください。",
    "Cloudflare Sync v2 is configured. Leave that workspace before using legacy snapshot sync." to "Cloudflare Sync v2 が設定されています。従来のスナップショット同期を使う前にそのワークスペースを離脱してください。",
    "Uploaded the first Shinsou X snapshot to iCloud Drive." to "最初の Shinsou X スナップショットを iCloud Drive にアップロードしました。",
    "Local data already matches iCloud Drive." to "ローカルデータはすでに iCloud Drive と一致しています。",
    "Merged local and remote snapshots." to "ローカルとリモートのスナップショットを統合しました。",
    "Snapshot sync failed." to "スナップショット同期に失敗しました。",
    "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to "ダウンロード、本機のソースファイル、拡張パッケージ、Cookie、パスワード、API キーは同期されません。",
    "iCloud Drive snapshot sync is unavailable on this platform." to "このプラットフォームでは iCloud Drive スナップショット同期を利用できません。",
    "Checking iCloud Drive availability…" to "iCloud Drive の利用可能性を確認中…",
    "A single Shinsou X snapshot will be stored in the app's iCloud Drive container." to "アプリの iCloud Drive コンテナに Shinsou X のスナップショットを 1 つ保存します。",
    "The remote snapshot service is unavailable." to "リモートスナップショットサービスを利用できません。",
)

private val KoreanSyncTranslations = mapOf(
    "Cloudflare encrypted sync" to "Cloudflare 암호화 동기화", "Not configured" to "구성되지 않음", "Deploying" to "배포 중",
    "Linking" to "연결 중", "Ready" to "준비됨", "Device revoked" to "기기 해지됨", "Error" to "오류",
    "Cursor {0}/{1} · {2} pending changes · {3} pending uploads" to "커서 {0}/{1} · 대기 중 변경 {2}개 · 대기 중 업로드 {3}개",
    "Encrypted event sync is unavailable in this runtime." to "이 런타임에서는 암호화 이벤트 동기화를 사용할 수 없습니다.",
    "Synchronized data needs repair" to "동기화된 데이터에 복구가 필요합니다",
    "{0} records could not be projected. They remain in the encrypted replica and were not silently discarded." to "{0}개 레코드를 반영할 수 없습니다. 암호화된 복제본에 유지되며 조용히 삭제되지 않았습니다.",
    "Retry validation" to "검증 다시 시도", "Missing synchronized dependency" to "동기화 종속 항목 누락", "Identity mapping collision" to "ID 매핑 충돌",
    "Invalid synchronized record" to "잘못된 동기화 레코드", "Repair local identity mapping" to "로컬 ID 매핑 복구",
    "Confirm repository signing key" to "저장소 서명 키 확인",
    "This repository remains pinned to its previous key and cannot expand trust until you confirm the exact new fingerprint." to "이 저장소는 이전 키에 고정되어 있습니다. 새 지문을 정확히 확인하기 전에는 신뢰를 확장할 수 없습니다.",
    "Trusted: {0}" to "신뢰됨: {0}", "None on this device" to "이 기기에 없음", "Proposed: {0}" to "제안: {0}",
    "Rejected on this device" to "이 기기에서 거부됨", "Trust exact fingerprint" to "정확한 지문 신뢰", "Reject" to "거부",
    "Setup, invite, pairing or emergency handoff link / code" to "설정, 초대, 페어링 또는 긴급 인계 링크/코드",
    "Connect" to "연결", "Paste" to "붙여넣기", "Scan QR" to "QR 스캔", "Bootstrap secret" to "부트스트랩 비밀",
    "Create sync service" to "동기화 서비스 만들기", "Open deployment page" to "배포 페이지 열기", "Lost every device?" to "모든 기기를 잃으셨나요?",
    "Import your Recovery Kit to verify the remote workspace, revoke old devices, rotate its keys and create a replacement kit." to "Recovery Kit을 가져와 원격 작업 공간을 확인하고, 이전 기기를 해지하고, 키를 교체한 뒤 새 키트를 만드세요.",
    "Import Recovery Kit" to "Recovery Kit 가져오기", "Leave or clear pending workspace" to "대기 중인 작업 공간 나가기 또는 지우기",
    "Add device" to "기기 추가", "Invite user" to "사용자 초대", "Refresh devices" to "기기 새로 고침", "Approve new device" to "새 기기 승인",
    "Allow" to "허용", "Deny" to "거부", "Instance usage and quota" to "인스턴스 사용량 및 할당량",
    "{0} users · {1} devices · {2} workspaces" to "사용자 {0}명 · 기기 {1}개 · 작업 공간 {2}개", "Stored {0} · reserved {1}" to "저장됨 {0} · 예약됨 {1}",
    "Users" to "사용자", "Workspaces / user" to "사용자당 작업 공간", "Devices / user" to "사용자당 기기", "Workspace MiB" to "작업 공간 MiB", "Event KiB" to "이벤트 KiB", "Checkpoint MiB" to "체크포인트 MiB",
    "Save quota" to "할당량 저장", "Refresh usage" to "사용량 새로 고침", "{0}: {1} events ({2}), {3} checkpoints ({4})" to "{0}: 이벤트 {1}개({2}), 체크포인트 {3}개({4})",
    "This view contains usage metadata only. Encrypted library payloads are never exposed to instance administrators." to "이 화면에는 사용량 메타데이터만 표시됩니다. 암호화된 라이브러리 내용은 인스턴스 관리자에게 공개되지 않습니다.",
    " (this device)" to "(이 기기)", "Revoked" to "해지됨", "Active" to "활성", "Revoke" to "해지", "Export Recovery Kit" to "Recovery Kit 내보내기", "Leave workspace" to "작업 공간 나가기",
    "Legacy iCloud snapshot" to "레거시 iCloud 스냅샷", "iCloud Drive snapshot" to "iCloud Drive 스냅샷", "Checking availability…" to "사용 가능 여부 확인 중…", "Available" to "사용 가능", "Unavailable" to "사용할 수 없음", "Check again" to "다시 확인",
    "Enable snapshot sync" to "스냅샷 동기화 사용", "Merge one versioned Shinsou X backup file through iCloud Drive" to "iCloud Drive를 통해 버전이 지정된 Shinsou X 백업 파일 하나 병합",
    "Sync when app enters foreground" to "앱이 포그라운드로 전환될 때 동기화", "Runs only when sync is enabled; identical snapshots do not write again" to "동기화가 활성화된 경우에만 실행하며 동일한 스냅샷은 다시 쓰지 않습니다.",
    "Sync status" to "동기화 상태", "Idle" to "대기", "Checking availability" to "사용 가능 여부 확인 중", "Pulling, merging and uploading" to "가져오고 병합하고 업로드하는 중", "Last sync succeeded" to "마지막 동기화 성공", "Last sync failed" to "마지막 동기화 실패", "Sync now" to "지금 동기화", "Last result" to "마지막 결과",
    "Revision {0} → {1} · {2} conflicts" to "개정 {0} → {1} · 충돌 {2}개", "iCloud snapshot writing is disabled while Cloudflare event sync is configured." to "Cloudflare 이벤트 동기화가 구성된 동안 iCloud 스냅샷 쓰기가 비활성화됩니다.",
    "This uses a coordinated single-file snapshot in iCloud Drive. It is not record-level CloudKit synchronization." to "iCloud Drive의 조정된 단일 파일 스냅샷을 사용합니다. 레코드 수준 CloudKit 동기화가 아닙니다.", "Sync QR code" to "동기화 QR 코드", "This is a one-time secret. Do not post it publicly." to "일회성 비밀입니다. 공개하지 마세요.", "Share" to "공유", "Copy" to "복사",
    "Revoke this device?" to "이 기기를 해지하시겠습니까?", "The device will lose future access and the workspace key will rotate." to "이 기기는 이후 접근 권한을 잃고 작업 공간 키가 교체됩니다.", "Leave synced workspace?" to "동기화된 작업 공간을 나가시겠습니까?", "This device will stop receiving updates. Local data remains until you restore or reset it." to "이 기기는 업데이트 수신을 중지합니다. 복원하거나 재설정할 때까지 로컬 데이터는 유지됩니다.", "Leave" to "나가기", "Sync operation failed." to "동기화 작업에 실패했습니다.",
    "Checking the iCloud ubiquity container…" to "iCloud 유비쿼티 컨테이너 확인 중…", "iCloud Drive is unavailable. Sign in to iCloud and enable iCloud Drive for Shinsou X." to "iCloud Drive를 사용할 수 없습니다. iCloud에 로그인하고 Shinsou X의 iCloud Drive를 활성화하세요.",
    "The Shinsou X iCloud Drive container is unavailable. Check the app's iCloud Documents entitlement." to "Shinsou X iCloud Drive 컨테이너를 사용할 수 없습니다. 앱의 iCloud Documents 권한을 확인하세요.", "The Shinsou X iCloud Drive container is unavailable. Check the iCloud Documents entitlement." to "Shinsou X iCloud Drive 컨테이너를 사용할 수 없습니다. iCloud Documents 권한을 확인하세요.",
    "Cloudflare Sync v2 is configured. Leave that workspace before using legacy snapshot sync." to "Cloudflare Sync v2가 구성되어 있습니다. 레거시 스냅샷 동기화를 사용하기 전에 해당 작업 공간을 나가세요.", "Uploaded the first Shinsou X snapshot to iCloud Drive." to "첫 번째 Shinsou X 스냅샷을 iCloud Drive에 업로드했습니다.", "Local data already matches iCloud Drive." to "로컬 데이터가 이미 iCloud Drive와 일치합니다.", "Merged local and remote snapshots." to "로컬 및 원격 스냅샷을 병합했습니다.", "Snapshot sync failed." to "스냅샷 동기화에 실패했습니다.",
    "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to "다운로드, 로컬 소스 파일, 확장 패키지, 쿠키, 비밀번호 및 API 키는 동기화되지 않습니다.",
    "iCloud Drive snapshot sync is unavailable on this platform." to "이 플랫폼에서는 iCloud Drive 스냅샷 동기화를 사용할 수 없습니다.",
    "Checking iCloud Drive availability…" to "iCloud Drive 사용 가능 여부 확인 중…",
    "A single Shinsou X snapshot will be stored in the app's iCloud Drive container." to "앱의 iCloud Drive 컨테이너에 Shinsou X 스냅샷 하나가 저장됩니다.",
    "The remote snapshot service is unavailable." to "원격 스냅샷 서비스를 사용할 수 없습니다.",
)

private val FrenchSyncTranslations = mapOf(
    "Cloudflare encrypted sync" to "Synchronisation chiffrée Cloudflare", "Not configured" to "Non configuré", "Deploying" to "Déploiement en cours",
    "Linking" to "Liaison en cours", "Ready" to "Prêt", "Device revoked" to "Appareil révoqué", "Error" to "Erreur",
    "Cursor {0}/{1} · {2} pending changes · {3} pending uploads" to "Curseur {0}/{1} · {2} modifications en attente · {3} téléversements en attente",
    "Encrypted event sync is unavailable in this runtime." to "La synchronisation chiffrée des événements est indisponible dans cet environnement.",
    "Synchronized data needs repair" to "Les données synchronisées doivent être réparées",
    "{0} records could not be projected. They remain in the encrypted replica and were not silently discarded." to "{0} enregistrements n’ont pas pu être projetés. Ils restent dans la réplique chiffrée et n’ont pas été supprimés silencieusement.",
    "Retry validation" to "Réessayer la validation", "Missing synchronized dependency" to "Dépendance synchronisée manquante", "Identity mapping collision" to "Collision de mappage d’identité", "Invalid synchronized record" to "Enregistrement synchronisé non valide", "Repair local identity mapping" to "Réparer le mappage d’identité local",
    "Confirm repository signing key" to "Confirmer la clé de signature du dépôt", "This repository remains pinned to its previous key and cannot expand trust until you confirm the exact new fingerprint." to "Ce dépôt reste épinglé à son ancienne clé et ne peut élargir la confiance avant confirmation de la nouvelle empreinte exacte.",
    "Trusted: {0}" to "Approuvée : {0}", "None on this device" to "Aucune sur cet appareil", "Proposed: {0}" to "Proposée : {0}", "Rejected on this device" to "Refusée sur cet appareil", "Trust exact fingerprint" to "Faire confiance à l’empreinte exacte", "Reject" to "Refuser",
    "Setup, invite, pairing or emergency handoff link / code" to "Lien / code de configuration, d’invitation, d’association ou de transfert d’urgence",
    "Connect" to "Connecter", "Paste" to "Coller", "Scan QR" to "Scanner le QR", "Bootstrap secret" to "Secret d’amorçage", "Create sync service" to "Créer le service de synchronisation", "Open deployment page" to "Ouvrir la page de déploiement",
    "Lost every device?" to "Tous les appareils sont perdus ?", "Import your Recovery Kit to verify the remote workspace, revoke old devices, rotate its keys and create a replacement kit." to "Importez votre Recovery Kit pour vérifier l’espace de travail distant, révoquer les anciens appareils, renouveler ses clés et créer un kit de remplacement.", "Import Recovery Kit" to "Importer le Recovery Kit", "Leave or clear pending workspace" to "Quitter ou effacer l’espace de travail en attente",
    "Add device" to "Ajouter un appareil", "Invite user" to "Inviter un utilisateur", "Refresh devices" to "Actualiser les appareils", "Approve new device" to "Approuver le nouvel appareil", "Allow" to "Autoriser", "Deny" to "Refuser",
    "Instance usage and quota" to "Utilisation et quota de l’instance", "{0} users · {1} devices · {2} workspaces" to "{0} utilisateurs · {1} appareils · {2} espaces de travail", "Stored {0} · reserved {1}" to "Stocké : {0} · réservé : {1}",
    "Users" to "Utilisateurs", "Workspaces / user" to "Espaces de travail / utilisateur", "Devices / user" to "Appareils / utilisateur", "Workspace MiB" to "Espace de travail MiB", "Event KiB" to "Événement KiB", "Checkpoint MiB" to "Point de contrôle MiB", "Save quota" to "Enregistrer le quota", "Refresh usage" to "Actualiser l’utilisation",
    "{0}: {1} events ({2}), {3} checkpoints ({4})" to "{0} : {1} événements ({2}), {3} points de contrôle ({4})", "This view contains usage metadata only. Encrypted library payloads are never exposed to instance administrators." to "Cette vue contient uniquement des métadonnées d’utilisation. Les données chiffrées de la bibliothèque ne sont jamais exposées aux administrateurs de l’instance.",
    " (this device)" to " (cet appareil)", "Revoked" to "Révoqué", "Active" to "Actif", "Revoke" to "Révoquer", "Export Recovery Kit" to "Exporter le Recovery Kit", "Leave workspace" to "Quitter l’espace de travail",
    "Legacy iCloud snapshot" to "Ancien instantané iCloud", "iCloud Drive snapshot" to "Instantané iCloud Drive", "Checking availability…" to "Vérification de la disponibilité…", "Available" to "Disponible", "Unavailable" to "Indisponible", "Check again" to "Vérifier à nouveau",
    "Enable snapshot sync" to "Activer la synchronisation des instantanés", "Merge one versioned Shinsou X backup file through iCloud Drive" to "Fusionner un fichier de sauvegarde Shinsou X versionné via iCloud Drive", "Sync when app enters foreground" to "Synchroniser au premier plan de l’application", "Runs only when sync is enabled; identical snapshots do not write again" to "S’exécute uniquement lorsque la synchronisation est activée ; les instantanés identiques ne sont pas réécrits.",
    "Sync status" to "État de la synchronisation", "Idle" to "Inactif", "Checking availability" to "Vérification de la disponibilité", "Pulling, merging and uploading" to "Récupération, fusion et téléversement", "Last sync succeeded" to "Dernière synchronisation réussie", "Last sync failed" to "Dernière synchronisation échouée", "Sync now" to "Synchroniser maintenant", "Last result" to "Dernier résultat",
    "Revision {0} → {1} · {2} conflicts" to "Révision {0} → {1} · {2} conflits", "iCloud snapshot writing is disabled while Cloudflare event sync is configured." to "L’écriture des instantanés iCloud est désactivée lorsque la synchronisation des événements Cloudflare est configurée.", "This uses a coordinated single-file snapshot in iCloud Drive. It is not record-level CloudKit synchronization." to "Utilise un instantané coordonné dans un fichier unique sur iCloud Drive. Il ne s’agit pas d’une synchronisation CloudKit au niveau des enregistrements.",
    "Sync QR code" to "Code QR de synchronisation", "This is a one-time secret. Do not post it publicly." to "Il s’agit d’un secret à usage unique. Ne le publiez pas.", "Share" to "Partager", "Copy" to "Copier", "Revoke this device?" to "Révoquer cet appareil ?", "The device will lose future access and the workspace key will rotate." to "L’appareil perdra son accès futur et la clé de l’espace de travail sera renouvelée.", "Leave synced workspace?" to "Quitter l’espace de travail synchronisé ?", "This device will stop receiving updates. Local data remains until you restore or reset it." to "Cet appareil ne recevra plus de mises à jour. Les données locales restent présentes jusqu’à une restauration ou une réinitialisation.", "Leave" to "Quitter", "Sync operation failed." to "L’opération de synchronisation a échoué.",
    "Checking the iCloud ubiquity container…" to "Vérification du conteneur ubiquitaire iCloud…", "iCloud Drive is unavailable. Sign in to iCloud and enable iCloud Drive for Shinsou X." to "iCloud Drive est indisponible. Connectez-vous à iCloud et activez iCloud Drive pour Shinsou X.", "The Shinsou X iCloud Drive container is unavailable. Check the app's iCloud Documents entitlement." to "Le conteneur iCloud Drive de Shinsou X est indisponible. Vérifiez l’autorisation iCloud Documents de l’application.", "The Shinsou X iCloud Drive container is unavailable. Check the iCloud Documents entitlement." to "Le conteneur iCloud Drive de Shinsou X est indisponible. Vérifiez l’autorisation iCloud Documents.", "Cloudflare Sync v2 is configured. Leave that workspace before using legacy snapshot sync." to "Cloudflare Sync v2 est configuré. Quittez cet espace de travail avant d’utiliser la synchronisation d’instantanés héritée.", "Uploaded the first Shinsou X snapshot to iCloud Drive." to "Le premier instantané Shinsou X a été téléversé vers iCloud Drive.", "Local data already matches iCloud Drive." to "Les données locales correspondent déjà à iCloud Drive.", "Merged local and remote snapshots." to "Les instantanés local et distant ont été fusionnés.", "Snapshot sync failed." to "La synchronisation de l’instantané a échoué.",
    "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to "Les téléchargements, fichiers source locaux, paquets d’extensions, cookies, mots de passe et clés API ne sont pas synchronisés.",
    "iCloud Drive snapshot sync is unavailable on this platform." to "La synchronisation des instantanés iCloud Drive est indisponible sur cette plateforme.",
    "Checking iCloud Drive availability…" to "Vérification de la disponibilité d’iCloud Drive…",
    "A single Shinsou X snapshot will be stored in the app's iCloud Drive container." to "Un instantané Shinsou X sera stocké dans le conteneur iCloud Drive de l’application.",
    "The remote snapshot service is unavailable." to "Le service d’instantanés distant est indisponible.",
)

private val GermanSyncTranslations = mapOf(
    "Cloudflare encrypted sync" to "Cloudflare-verschlüsselte Synchronisierung", "Not configured" to "Nicht konfiguriert", "Deploying" to "Wird bereitgestellt", "Linking" to "Wird verknüpft", "Ready" to "Bereit", "Device revoked" to "Gerät widerrufen", "Error" to "Fehler",
    "Cursor {0}/{1} · {2} pending changes · {3} pending uploads" to "Cursor {0}/{1} · {2} ausstehende Änderungen · {3} ausstehende Uploads", "Encrypted event sync is unavailable in this runtime." to "Die verschlüsselte Ereignissynchronisierung ist in dieser Laufzeit nicht verfügbar.", "Synchronized data needs repair" to "Synchronisierte Daten müssen repariert werden", "{0} records could not be projected. They remain in the encrypted replica and were not silently discarded." to "{0} Datensätze konnten nicht projiziert werden. Sie bleiben im verschlüsselten Replikat und wurden nicht still verworfen.", "Retry validation" to "Validierung wiederholen", "Missing synchronized dependency" to "Fehlende synchronisierte Abhängigkeit", "Identity mapping collision" to "Konflikt bei der Identitätszuordnung", "Invalid synchronized record" to "Ungültiger synchronisierter Datensatz", "Repair local identity mapping" to "Lokale Identitätszuordnung reparieren",
    "Confirm repository signing key" to "Signaturschlüssel des Repositorys bestätigen", "This repository remains pinned to its previous key and cannot expand trust until you confirm the exact new fingerprint." to "Dieses Repository bleibt an den bisherigen Schlüssel gebunden und kann erst nach Bestätigung des exakten neuen Fingerabdrucks weiteres Vertrauen erhalten.", "Trusted: {0}" to "Vertraut: {0}", "None on this device" to "Auf diesem Gerät keiner", "Proposed: {0}" to "Vorgeschlagen: {0}", "Rejected on this device" to "Auf diesem Gerät abgelehnt", "Trust exact fingerprint" to "Exaktem Fingerabdruck vertrauen", "Reject" to "Ablehnen",
    "Setup, invite, pairing or emergency handoff link / code" to "Einrichtungs-, Einladungs-, Kopplungs- oder Notfallübergabe-Link / -Code", "Connect" to "Verbinden", "Paste" to "Einfügen", "Scan QR" to "QR scannen", "Bootstrap secret" to "Bootstrap-Geheimnis", "Create sync service" to "Synchronisierungsdienst erstellen", "Open deployment page" to "Bereitstellungsseite öffnen", "Lost every device?" to "Alle Geräte verloren?", "Import your Recovery Kit to verify the remote workspace, revoke old devices, rotate its keys and create a replacement kit." to "Importiere dein Recovery Kit, um den entfernten Arbeitsbereich zu prüfen, alte Geräte zu widerrufen, Schlüssel zu wechseln und ein Ersatz-Kit zu erstellen.", "Import Recovery Kit" to "Recovery Kit importieren", "Leave or clear pending workspace" to "Ausstehende Arbeitsbereich-Konfiguration verlassen oder löschen",
    "Add device" to "Gerät hinzufügen", "Invite user" to "Benutzer einladen", "Refresh devices" to "Geräte aktualisieren", "Approve new device" to "Neues Gerät genehmigen", "Allow" to "Erlauben", "Deny" to "Ablehnen", "Instance usage and quota" to "Instanznutzung und Kontingent", "{0} users · {1} devices · {2} workspaces" to "{0} Benutzer · {1} Geräte · {2} Arbeitsbereiche", "Stored {0} · reserved {1}" to "Gespeichert {0} · reserviert {1}", "Users" to "Benutzer", "Workspaces / user" to "Arbeitsbereiche / Benutzer", "Devices / user" to "Geräte / Benutzer", "Workspace MiB" to "Arbeitsbereich MiB", "Event KiB" to "Ereignis KiB", "Checkpoint MiB" to "Checkpoint MiB", "Save quota" to "Kontingent speichern", "Refresh usage" to "Nutzung aktualisieren", "{0}: {1} events ({2}), {3} checkpoints ({4})" to "{0}: {1} Ereignisse ({2}), {3} Checkpoints ({4})", "This view contains usage metadata only. Encrypted library payloads are never exposed to instance administrators." to "Diese Ansicht enthält nur Nutzungsmetadaten. Verschlüsselte Bibliotheksdaten werden Instanzadministratoren niemals offengelegt.",
    " (this device)" to " (dieses Gerät)", "Revoked" to "Widerrufen", "Active" to "Aktiv", "Revoke" to "Widerrufen", "Export Recovery Kit" to "Recovery Kit exportieren", "Leave workspace" to "Arbeitsbereich verlassen", "Legacy iCloud snapshot" to "Veralteter iCloud-Snapshot", "iCloud Drive snapshot" to "iCloud-Drive-Snapshot", "Checking availability…" to "Verfügbarkeit wird geprüft…", "Available" to "Verfügbar", "Unavailable" to "Nicht verfügbar", "Check again" to "Erneut prüfen",
    "Enable snapshot sync" to "Snapshot-Synchronisierung aktivieren", "Merge one versioned Shinsou X backup file through iCloud Drive" to "Eine versionierte Shinsou-X-Sicherungsdatei über iCloud Drive zusammenführen", "Sync when app enters foreground" to "Beim Wechsel der App in den Vordergrund synchronisieren", "Runs only when sync is enabled; identical snapshots do not write again" to "Wird nur bei aktivierter Synchronisierung ausgeführt; identische Snapshots werden nicht erneut geschrieben.", "Sync status" to "Synchronisierungsstatus", "Idle" to "Inaktiv", "Checking availability" to "Verfügbarkeit wird geprüft", "Pulling, merging and uploading" to "Abrufen, Zusammenführen und Hochladen", "Last sync succeeded" to "Letzte Synchronisierung erfolgreich", "Last sync failed" to "Letzte Synchronisierung fehlgeschlagen", "Sync now" to "Jetzt synchronisieren", "Last result" to "Letztes Ergebnis", "Revision {0} → {1} · {2} conflicts" to "Revision {0} → {1} · {2} Konflikte",
    "iCloud snapshot writing is disabled while Cloudflare event sync is configured." to "Das Schreiben von iCloud-Snapshots ist deaktiviert, solange die Cloudflare-Ereignissynchronisierung konfiguriert ist.", "This uses a coordinated single-file snapshot in iCloud Drive. It is not record-level CloudKit synchronization." to "Verwendet einen koordinierten Ein-Datei-Snapshot in iCloud Drive. Dies ist keine CloudKit-Synchronisierung auf Datensatzebene.", "Sync QR code" to "Synchronisierungs-QR-Code", "This is a one-time secret. Do not post it publicly." to "Dies ist ein einmaliges Geheimnis. Veröffentliche es nicht.", "Share" to "Teilen", "Copy" to "Kopieren", "Revoke this device?" to "Dieses Gerät widerrufen?", "The device will lose future access and the workspace key will rotate." to "Das Gerät verliert den zukünftigen Zugriff und der Arbeitsbereichsschlüssel wird gewechselt.", "Leave synced workspace?" to "Synchronisierten Arbeitsbereich verlassen?", "This device will stop receiving updates. Local data remains until you restore or reset it." to "Dieses Gerät empfängt keine Updates mehr. Lokale Daten bleiben erhalten, bis du sie wiederherstellst oder zurücksetzt.", "Leave" to "Verlassen", "Sync operation failed." to "Synchronisierung fehlgeschlagen.",
    "Checking the iCloud ubiquity container…" to "iCloud-Ubiquity-Container wird geprüft…", "iCloud Drive is unavailable. Sign in to iCloud and enable iCloud Drive for Shinsou X." to "iCloud Drive ist nicht verfügbar. Melde dich bei iCloud an und aktiviere iCloud Drive für Shinsou X.", "The Shinsou X iCloud Drive container is unavailable. Check the app's iCloud Documents entitlement." to "Der iCloud-Drive-Container von Shinsou X ist nicht verfügbar. Prüfe die iCloud-Documents-Berechtigung der App.", "The Shinsou X iCloud Drive container is unavailable. Check the iCloud Documents entitlement." to "Der iCloud-Drive-Container von Shinsou X ist nicht verfügbar. Prüfe die iCloud-Documents-Berechtigung.", "Cloudflare Sync v2 is configured. Leave that workspace before using legacy snapshot sync." to "Cloudflare Sync v2 ist konfiguriert. Verlasse diesen Arbeitsbereich, bevor du die alte Snapshot-Synchronisierung verwendest.", "Uploaded the first Shinsou X snapshot to iCloud Drive." to "Der erste Shinsou-X-Snapshot wurde zu iCloud Drive hochgeladen.", "Local data already matches iCloud Drive." to "Die lokalen Daten entsprechen bereits iCloud Drive.", "Merged local and remote snapshots." to "Lokaler und entfernter Snapshot wurden zusammengeführt.", "Snapshot sync failed." to "Snapshot-Synchronisierung fehlgeschlagen.", "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to "Downloads, lokale Quelldateien, Erweiterungspakete, Cookies, Passwörter und API-Schlüssel werden nicht synchronisiert.", "iCloud Drive snapshot sync is unavailable on this platform." to "Die iCloud-Drive-Snapshot-Synchronisierung ist auf dieser Plattform nicht verfügbar.",
)

private val GermanICloudSyncTranslations = mapOf(
    "Checking iCloud Drive availability…" to "Verfügbarkeit von iCloud Drive wird geprüft…",
    "A single Shinsou X snapshot will be stored in the app's iCloud Drive container." to "Ein einzelner Shinsou-X-Snapshot wird im iCloud-Drive-Container der App gespeichert.",
    "The remote snapshot service is unavailable." to "Der entfernte Snapshot-Dienst ist nicht verfügbar.",
)

private val SpanishSyncTranslations = mapOf(
    "Cloudflare encrypted sync" to "Sincronización cifrada de Cloudflare", "Not configured" to "No configurado", "Deploying" to "Desplegando", "Linking" to "Vinculando", "Ready" to "Listo", "Device revoked" to "Dispositivo revocado", "Error" to "Error",
    "Cursor {0}/{1} · {2} pending changes · {3} pending uploads" to "Cursor {0}/{1} · {2} cambios pendientes · {3} cargas pendientes", "Encrypted event sync is unavailable in this runtime." to "La sincronización cifrada de eventos no está disponible en este entorno.", "Synchronized data needs repair" to "Los datos sincronizados necesitan reparación", "{0} records could not be projected. They remain in the encrypted replica and were not silently discarded." to "No se pudieron proyectar {0} registros. Permanecen en la réplica cifrada y no se descartaron silenciosamente.", "Retry validation" to "Reintentar validación", "Missing synchronized dependency" to "Falta una dependencia sincronizada", "Identity mapping collision" to "Conflicto de asignación de identidad", "Invalid synchronized record" to "Registro sincronizado no válido", "Repair local identity mapping" to "Reparar la asignación de identidad local",
    "Confirm repository signing key" to "Confirmar la clave de firma del repositorio", "This repository remains pinned to its previous key and cannot expand trust until you confirm the exact new fingerprint." to "Este repositorio sigue fijado a su clave anterior y no puede ampliar la confianza hasta que confirmes la nueva huella exacta.", "Trusted: {0}" to "De confianza: {0}", "None on this device" to "Ninguna en este dispositivo", "Proposed: {0}" to "Propuesta: {0}", "Rejected on this device" to "Rechazada en este dispositivo", "Trust exact fingerprint" to "Confiar en la huella exacta", "Reject" to "Rechazar",
    "Setup, invite, pairing or emergency handoff link / code" to "Enlace / código de configuración, invitación, emparejamiento o transferencia de emergencia", "Connect" to "Conectar", "Paste" to "Pegar", "Scan QR" to "Escanear QR", "Bootstrap secret" to "Secreto de arranque", "Create sync service" to "Crear servicio de sincronización", "Open deployment page" to "Abrir página de despliegue", "Lost every device?" to "¿Has perdido todos los dispositivos?", "Import your Recovery Kit to verify the remote workspace, revoke old devices, rotate its keys and create a replacement kit." to "Importa tu Recovery Kit para verificar el espacio de trabajo remoto, revocar dispositivos antiguos, rotar sus claves y crear un kit de reemplazo.", "Import Recovery Kit" to "Importar Recovery Kit", "Leave or clear pending workspace" to "Salir o borrar el espacio de trabajo pendiente",
    "Add device" to "Añadir dispositivo", "Invite user" to "Invitar usuario", "Refresh devices" to "Actualizar dispositivos", "Approve new device" to "Aprobar nuevo dispositivo", "Allow" to "Permitir", "Deny" to "Denegar", "Instance usage and quota" to "Uso y cuota de la instancia", "{0} users · {1} devices · {2} workspaces" to "{0} usuarios · {1} dispositivos · {2} espacios de trabajo", "Stored {0} · reserved {1}" to "Almacenado {0} · reservado {1}", "Users" to "Usuarios", "Workspaces / user" to "Espacios de trabajo / usuario", "Devices / user" to "Dispositivos / usuario", "Workspace MiB" to "Espacio de trabajo MiB", "Event KiB" to "Evento KiB", "Checkpoint MiB" to "Punto de control MiB", "Save quota" to "Guardar cuota", "Refresh usage" to "Actualizar uso", "{0}: {1} events ({2}), {3} checkpoints ({4})" to "{0}: {1} eventos ({2}), {3} puntos de control ({4})", "This view contains usage metadata only. Encrypted library payloads are never exposed to instance administrators." to "Esta vista solo contiene metadatos de uso. Las cargas cifradas de la biblioteca nunca se exponen a los administradores de la instancia.",
    " (this device)" to " (este dispositivo)", "Revoked" to "Revocado", "Active" to "Activo", "Revoke" to "Revocar", "Export Recovery Kit" to "Exportar Recovery Kit", "Leave workspace" to "Salir del espacio de trabajo", "Legacy iCloud snapshot" to "Instantánea de iCloud heredada", "iCloud Drive snapshot" to "Instantánea de iCloud Drive", "Checking availability…" to "Comprobando disponibilidad…", "Available" to "Disponible", "Unavailable" to "No disponible", "Check again" to "Comprobar de nuevo",
    "Enable snapshot sync" to "Activar sincronización de instantáneas", "Merge one versioned Shinsou X backup file through iCloud Drive" to "Combinar un archivo de copia de seguridad versionado de Shinsou X mediante iCloud Drive", "Sync when app enters foreground" to "Sincronizar cuando la aplicación pase a primer plano", "Runs only when sync is enabled; identical snapshots do not write again" to "Solo se ejecuta con la sincronización activada; las instantáneas idénticas no se vuelven a escribir.", "Sync status" to "Estado de sincronización", "Idle" to "Inactivo", "Checking availability" to "Comprobando disponibilidad", "Pulling, merging and uploading" to "Obteniendo, combinando y cargando", "Last sync succeeded" to "Última sincronización correcta", "Last sync failed" to "Última sincronización fallida", "Sync now" to "Sincronizar ahora", "Last result" to "Último resultado", "Revision {0} → {1} · {2} conflicts" to "Revisión {0} → {1} · {2} conflictos",
    "iCloud snapshot writing is disabled while Cloudflare event sync is configured." to "La escritura de instantáneas de iCloud está desactivada mientras la sincronización de eventos de Cloudflare esté configurada.", "This uses a coordinated single-file snapshot in iCloud Drive. It is not record-level CloudKit synchronization." to "Usa una instantánea coordinada de un solo archivo en iCloud Drive. No es una sincronización de CloudKit a nivel de registro.", "Sync QR code" to "Código QR de sincronización", "This is a one-time secret. Do not post it publicly." to "Este es un secreto de un solo uso. No lo publiques.", "Share" to "Compartir", "Copy" to "Copiar", "Revoke this device?" to "¿Revocar este dispositivo?", "The device will lose future access and the workspace key will rotate." to "El dispositivo perderá el acceso futuro y la clave del espacio de trabajo rotará.", "Leave synced workspace?" to "¿Salir del espacio de trabajo sincronizado?", "This device will stop receiving updates. Local data remains until you restore or reset it." to "Este dispositivo dejará de recibir actualizaciones. Los datos locales permanecerán hasta que los restaures o restablezcas.", "Leave" to "Salir", "Sync operation failed." to "La operación de sincronización falló.",
    "Checking the iCloud ubiquity container…" to "Comprobando el contenedor de ubicuidad de iCloud…", "iCloud Drive is unavailable. Sign in to iCloud and enable iCloud Drive for Shinsou X." to "iCloud Drive no está disponible. Inicia sesión en iCloud y activa iCloud Drive para Shinsou X.", "The Shinsou X iCloud Drive container is unavailable. Check the app's iCloud Documents entitlement." to "El contenedor de iCloud Drive de Shinsou X no está disponible. Comprueba el permiso iCloud Documents de la aplicación.", "The Shinsou X iCloud Drive container is unavailable. Check the iCloud Documents entitlement." to "El contenedor de iCloud Drive de Shinsou X no está disponible. Comprueba el permiso iCloud Documents.", "Cloudflare Sync v2 is configured. Leave that workspace before using legacy snapshot sync." to "Cloudflare Sync v2 está configurado. Sal de ese espacio de trabajo antes de usar la sincronización de instantáneas heredada.", "Uploaded the first Shinsou X snapshot to iCloud Drive." to "Se ha cargado la primera instantánea de Shinsou X en iCloud Drive.", "Local data already matches iCloud Drive." to "Los datos locales ya coinciden con iCloud Drive.", "Merged local and remote snapshots." to "Se han combinado las instantáneas local y remota.", "Snapshot sync failed." to "La sincronización de instantáneas falló.", "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to "Las descargas, los archivos de origen locales, los paquetes de extensiones, las cookies, las contraseñas y las claves API no se sincronizan.", "iCloud Drive snapshot sync is unavailable on this platform." to "La sincronización de instantáneas de iCloud Drive no está disponible en esta plataforma.",
)

private val SpanishICloudSyncTranslations = mapOf(
    "Checking iCloud Drive availability…" to "Comprobando la disponibilidad de iCloud Drive…",
    "A single Shinsou X snapshot will be stored in the app's iCloud Drive container." to "Se almacenará una instantánea de Shinsou X en el contenedor de iCloud Drive de la aplicación.",
    "The remote snapshot service is unavailable." to "El servicio de instantáneas remoto no está disponible.",
)

private val PortugueseSyncTranslations = mapOf(
    "Cloudflare encrypted sync" to "Sincronização criptografada do Cloudflare", "Not configured" to "Não configurado", "Deploying" to "Implantando", "Linking" to "Vinculando", "Ready" to "Pronto", "Device revoked" to "Dispositivo revogado", "Error" to "Erro",
    "Cursor {0}/{1} · {2} pending changes · {3} pending uploads" to "Cursor {0}/{1} · {2} alterações pendentes · {3} uploads pendentes", "Encrypted event sync is unavailable in this runtime." to "A sincronização criptografada de eventos não está disponível neste ambiente.", "Synchronized data needs repair" to "Os dados sincronizados precisam de reparo", "{0} records could not be projected. They remain in the encrypted replica and were not silently discarded." to "Não foi possível projetar {0} registros. Eles permanecem na réplica criptografada e não foram descartados silenciosamente.", "Retry validation" to "Tentar validar novamente", "Missing synchronized dependency" to "Dependência sincronizada ausente", "Identity mapping collision" to "Conflito no mapeamento de identidade", "Invalid synchronized record" to "Registro sincronizado inválido", "Repair local identity mapping" to "Reparar mapeamento de identidade local",
    "Confirm repository signing key" to "Confirmar chave de assinatura do repositório", "This repository remains pinned to its previous key and cannot expand trust until you confirm the exact new fingerprint." to "Este repositório continua fixado à chave anterior e não pode ampliar a confiança até você confirmar a nova impressão digital exata.", "Trusted: {0}" to "Confiável: {0}", "None on this device" to "Nenhuma neste dispositivo", "Proposed: {0}" to "Proposta: {0}", "Rejected on this device" to "Rejeitada neste dispositivo", "Trust exact fingerprint" to "Confiar na impressão digital exata", "Reject" to "Rejeitar",
    "Setup, invite, pairing or emergency handoff link / code" to "Link / código de configuração, convite, emparelhamento ou transferência de emergência", "Connect" to "Conectar", "Paste" to "Colar", "Scan QR" to "Escanear QR", "Bootstrap secret" to "Segredo de inicialização", "Create sync service" to "Criar serviço de sincronização", "Open deployment page" to "Abrir página de implantação", "Lost every device?" to "Perdeu todos os dispositivos?", "Import your Recovery Kit to verify the remote workspace, revoke old devices, rotate its keys and create a replacement kit." to "Importe seu Recovery Kit para verificar o espaço de trabalho remoto, revogar dispositivos antigos, alternar as chaves e criar um kit substituto.", "Import Recovery Kit" to "Importar Recovery Kit", "Leave or clear pending workspace" to "Sair ou limpar o espaço de trabalho pendente",
    "Add device" to "Adicionar dispositivo", "Invite user" to "Convidar usuário", "Refresh devices" to "Atualizar dispositivos", "Approve new device" to "Aprovar novo dispositivo", "Allow" to "Permitir", "Deny" to "Negar", "Instance usage and quota" to "Uso e cota da instância", "{0} users · {1} devices · {2} workspaces" to "{0} usuários · {1} dispositivos · {2} espaços de trabalho", "Stored {0} · reserved {1}" to "Armazenado {0} · reservado {1}", "Users" to "Usuários", "Workspaces / user" to "Espaços de trabalho / usuário", "Devices / user" to "Dispositivos / usuário", "Workspace MiB" to "Espaço de trabalho MiB", "Event KiB" to "Evento KiB", "Checkpoint MiB" to "Ponto de verificação MiB", "Save quota" to "Salvar cota", "Refresh usage" to "Atualizar uso", "{0}: {1} events ({2}), {3} checkpoints ({4})" to "{0}: {1} eventos ({2}), {3} pontos de verificação ({4})", "This view contains usage metadata only. Encrypted library payloads are never exposed to instance administrators." to "Esta tela contém apenas metadados de uso. Os dados criptografados da biblioteca nunca são expostos aos administradores da instância.",
    " (this device)" to " (este dispositivo)", "Revoked" to "Revogado", "Active" to "Ativo", "Revoke" to "Revogar", "Export Recovery Kit" to "Exportar Recovery Kit", "Leave workspace" to "Sair do espaço de trabalho", "Legacy iCloud snapshot" to "Instantâneo legado do iCloud", "iCloud Drive snapshot" to "Instantâneo do iCloud Drive", "Checking availability…" to "Verificando disponibilidade…", "Available" to "Disponível", "Unavailable" to "Indisponível", "Check again" to "Verificar novamente",
    "Enable snapshot sync" to "Ativar sincronização de instantâneos", "Merge one versioned Shinsou X backup file through iCloud Drive" to "Mesclar um arquivo de backup versionado do Shinsou X pelo iCloud Drive", "Sync when app enters foreground" to "Sincronizar quando o app entrar em primeiro plano", "Runs only when sync is enabled; identical snapshots do not write again" to "Executa somente quando a sincronização está ativada; instantâneos idênticos não são gravados novamente.", "Sync status" to "Status da sincronização", "Idle" to "Ocioso", "Checking availability" to "Verificando disponibilidade", "Pulling, merging and uploading" to "Baixando, mesclando e enviando", "Last sync succeeded" to "Última sincronização bem-sucedida", "Last sync failed" to "Última sincronização falhou", "Sync now" to "Sincronizar agora", "Last result" to "Último resultado", "Revision {0} → {1} · {2} conflicts" to "Revisão {0} → {1} · {2} conflitos",
    "iCloud snapshot writing is disabled while Cloudflare event sync is configured." to "A gravação de instantâneos do iCloud está desativada enquanto a sincronização de eventos do Cloudflare estiver configurada.", "This uses a coordinated single-file snapshot in iCloud Drive. It is not record-level CloudKit synchronization." to "Usa um instantâneo coordenado de arquivo único no iCloud Drive. Não é uma sincronização do CloudKit em nível de registro.", "Sync QR code" to "Código QR de sincronização", "This is a one-time secret. Do not post it publicly." to "Este é um segredo de uso único. Não o publique.", "Share" to "Compartilhar", "Copy" to "Copiar", "Revoke this device?" to "Revogar este dispositivo?", "The device will lose future access and the workspace key will rotate." to "O dispositivo perderá o acesso futuro e a chave do espaço de trabalho será alternada.", "Leave synced workspace?" to "Sair do espaço de trabalho sincronizado?", "This device will stop receiving updates. Local data remains until you restore or reset it." to "Este dispositivo deixará de receber atualizações. Os dados locais permanecerão até você restaurá-los ou redefini-los.", "Leave" to "Sair", "Sync operation failed." to "Falha na operação de sincronização.",
    "Checking the iCloud ubiquity container…" to "Verificando o contêiner de ubiquidade do iCloud…", "iCloud Drive is unavailable. Sign in to iCloud and enable iCloud Drive for Shinsou X." to "O iCloud Drive está indisponível. Inicie sessão no iCloud e ative o iCloud Drive para o Shinsou X.", "The Shinsou X iCloud Drive container is unavailable. Check the app's iCloud Documents entitlement." to "O contêiner do iCloud Drive do Shinsou X está indisponível. Verifique a permissão iCloud Documents do app.", "The Shinsou X iCloud Drive container is unavailable. Check the iCloud Documents entitlement." to "O contêiner do iCloud Drive do Shinsou X está indisponível. Verifique a permissão iCloud Documents.", "Cloudflare Sync v2 is configured. Leave that workspace before using legacy snapshot sync." to "O Cloudflare Sync v2 está configurado. Saia desse espaço de trabalho antes de usar a sincronização de instantâneos legada.", "Uploaded the first Shinsou X snapshot to iCloud Drive." to "O primeiro instantâneo do Shinsou X foi enviado para o iCloud Drive.", "Local data already matches iCloud Drive." to "Os dados locais já correspondem ao iCloud Drive.", "Merged local and remote snapshots." to "Os instantâneos local e remoto foram mesclados.", "Snapshot sync failed." to "Falha na sincronização do instantâneo.", "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to "Downloads, arquivos de origem locais, pacotes de extensões, cookies, senhas e chaves de API não são sincronizados.", "iCloud Drive snapshot sync is unavailable on this platform." to "A sincronização de instantâneos do iCloud Drive não está disponível nesta plataforma.",
)

private val PortugueseICloudSyncTranslations = mapOf(
    "Checking iCloud Drive availability…" to "Verificando a disponibilidade do iCloud Drive…",
    "A single Shinsou X snapshot will be stored in the app's iCloud Drive container." to "Um instantâneo do Shinsou X será armazenado no contêiner do iCloud Drive do app.",
    "The remote snapshot service is unavailable." to "O serviço de instantâneos remoto está indisponível.",
)

private val TraditionalReaderTranslations = mapOf(
    "Import local content" to "匯入本機內容",
    "Images, CBZ, ZIP, TXT or EPUB · stored on this device" to "圖片、CBZ、ZIP、TXT 或 EPUB · 儲存於本機",
    "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync." to
        "除非您明確加入加密同步，TXT 與 EPUB 正文只會保留在此裝置上。",
    "Encrypted TXT/EPUB body sync" to "加密 TXT／EPUB 正文同步",
    "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB." to
        "只有在目前權限授權 SYNC_BLOB 時才會在背景上傳。",
    "Choose files" to "選擇檔案",
    "Encrypted chapter body sync" to "加密章節正文同步",
    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload." to
        "預設關閉。啟用後，只有目前 SYNC_BLOB 授權允許的正文會排入加密背景上傳。",
    "This content is no longer available under the current rights grant." to "目前的內容權限已不允許使用此內容。",
    "Full-text search is unavailable for this content." to "此內容目前無法使用全文搜尋。",
    "Search text" to "搜尋文字",
    "Speech stopped because it is unavailable or no longer permitted." to "語音朗讀無法使用或已不再獲得允許，因此已停止。",
    "Speech unavailable" to "語音朗讀無法使用",
    "Speaking…" to "正在朗讀…",
    "Speak from paragraph" to "從本段開始朗讀",
    "Stop" to "停止",
    "Search this book" to "搜尋此書",
    "Search is no longer permitted." to "目前的內容權限已不允許搜尋。",
    "Find" to "尋找",
    "Add note" to "新增註記",
    "Reader paragraph" to "閱讀器段落",
    "Copy is unavailable or not permitted." to "無法複製，或目前的內容權限不允許複製。",
    "Copy" to "複製",
    "{0} note" to "{0} 則註記",
    "{0} notes" to "{0} 則註記",
    "Add paragraph note" to "新增段落註記",
    "Note" to "註記",
    "The note could not be saved or is no longer permitted." to "無法儲存註記，或目前的內容權限已不允許註記。",
    "The image navigation graph is unavailable." to "圖片導覽資料無法使用。",
    "Page {0}" to "第 {0} 頁",
    "The image resource could not be opened." to "無法開啟圖片資源。",
    "The EPUB navigation graph is unavailable." to "EPUB 導覽資料無法使用。",
    "The EPUB resources could not be opened." to "無法開啟 EPUB 資源。",
    "Previous" to "上一節",
    "Next" to "下一節",
    "EPUB rendering is unavailable until this platform supplies its secure browser renderer." to
        "此平台尚未提供安全的瀏覽器渲染器，因此無法顯示 EPUB。",
    "This image representation has not been materialized into reader pages." to
        "此圖片內容尚未轉換為可供閱讀器顯示的頁面。",
    "Content saved for offline reading." to "內容已儲存，可離線閱讀。",
    "Extension content" to "擴充套件內容",
    "Unable to load chapters" to "無法載入章節",
    "No chapters" to "沒有章節",
    "This extension did not return any readable units." to "此擴充套件未傳回任何可閱讀單元。",
    "Choose a format when this chapter provides more than one." to "若此章節提供多種格式，請選擇一種。",
    "Save offline" to "儲存供離線閱讀",
    "Load more" to "載入更多",
    "Choose content format" to "選擇內容格式",
    "The operation could not be completed." to "無法完成操作。",
    "Format: {0}" to "格式：{0}",
)

private val SimplifiedReaderTranslations = mapOf(
    "Import local content" to "导入本机内容",
    "Images, CBZ, ZIP, TXT or EPUB · stored on this device" to "图片、CBZ、ZIP、TXT 或 EPUB · 存储在本机",
    "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync." to
        "除非你明确加入加密同步，TXT 与 EPUB 正文只会保留在此设备上。",
    "Encrypted TXT/EPUB body sync" to "加密 TXT／EPUB 正文同步",
    "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB." to
        "只有当前权限授予 SYNC_BLOB 时才会在后台上传。",
    "Choose files" to "选择文件",
    "Encrypted chapter body sync" to "加密章节正文同步",
    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload." to
        "默认关闭。启用后，只有当前 SYNC_BLOB 授权允许的正文才会排入加密后台上传。",
    "This content is no longer available under the current rights grant." to "当前的内容权限已不允许使用此内容。",
    "Full-text search is unavailable for this content." to "此内容目前无法使用全文搜索。",
    "Search text" to "搜索文字",
    "Speech stopped because it is unavailable or no longer permitted." to "语音朗读无法使用或已不再获得允许，因此已停止。",
    "Speech unavailable" to "语音朗读无法使用",
    "Speaking…" to "正在朗读…",
    "Speak from paragraph" to "从本段开始朗读",
    "Stop" to "停止",
    "Search this book" to "搜索此书",
    "Search is no longer permitted." to "当前的内容权限已不允许搜索。",
    "Find" to "查找",
    "Add note" to "添加笔记",
    "Reader paragraph" to "阅读器段落",
    "Copy is unavailable or not permitted." to "无法复制，或当前的内容权限不允许复制。",
    "Copy" to "复制",
    "{0} note" to "{0} 条笔记",
    "{0} notes" to "{0} 条笔记",
    "Add paragraph note" to "添加段落笔记",
    "Note" to "笔记",
    "The note could not be saved or is no longer permitted." to "无法保存笔记，或当前的内容权限已不允许添加笔记。",
    "The image navigation graph is unavailable." to "图片导航数据无法使用。",
    "Page {0}" to "第 {0} 页",
    "The image resource could not be opened." to "无法打开图片资源。",
    "The EPUB navigation graph is unavailable." to "EPUB 导航数据无法使用。",
    "The EPUB resources could not be opened." to "无法打开 EPUB 资源。",
    "Previous" to "上一节",
    "Next" to "下一节",
    "EPUB rendering is unavailable until this platform supplies its secure browser renderer." to
        "此平台尚未提供安全的浏览器渲染器，因此无法显示 EPUB。",
    "This image representation has not been materialized into reader pages." to
        "此图片内容尚未转换为可供阅读器显示的页面。",
    "Content saved for offline reading." to "内容已保存，可离线阅读。",
    "Extension content" to "扩展内容",
    "Unable to load chapters" to "无法加载章节",
    "No chapters" to "没有章节",
    "This extension did not return any readable units." to "此扩展未返回任何可阅读单元。",
    "Choose a format when this chapter provides more than one." to "如果此章节提供多种格式，请选择一种。",
    "Save offline" to "保存以供离线阅读",
    "Load more" to "加载更多",
    "Choose content format" to "选择内容格式",
    "The operation could not be completed." to "无法完成操作。",
    "Format: {0}" to "格式：{0}",
)

private val JapaneseReaderTranslations = mapOf(
    "Import local content" to "ローカルコンテンツを読み込む",
    "Images, CBZ, ZIP, TXT or EPUB · stored on this device" to "画像、CBZ、ZIP、TXT、EPUB · この端末に保存",
    "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync." to
        "暗号化同期へ明示的に追加しない限り、TXT と EPUB の本文はこの端末だけに保存されます。",
    "Encrypted TXT/EPUB body sync" to "TXT／EPUB 本文の暗号化同期",
    "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB." to
        "現在の権利で SYNC_BLOB が許可される場合に限り、バックグラウンドでアップロードします。",
    "Choose files" to "ファイルを選択",
    "Encrypted chapter body sync" to "章本文の暗号化同期",
    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload." to
        "既定ではオフです。有効にすると、現在の SYNC_BLOB 権限で許可された本文だけを暗号化バックグラウンドアップロードに追加します。",
    "This content is no longer available under the current rights grant." to "現在の権利では、このコンテンツを利用できません。",
    "Full-text search is unavailable for this content." to "このコンテンツでは全文検索を利用できません。",
    "Search text" to "本文を検索",
    "Speech stopped because it is unavailable or no longer permitted." to "音声読み上げを利用できないか、許可されていないため停止しました。",
    "Speech unavailable" to "音声読み上げを利用できません",
    "Speaking…" to "読み上げ中…",
    "Speak from paragraph" to "この段落から読み上げ",
    "Stop" to "停止",
    "Search this book" to "この本を検索",
    "Search is no longer permitted." to "現在の権利では検索できません。",
    "Find" to "検索",
    "Add note" to "メモを追加",
    "Reader paragraph" to "リーダーの段落",
    "Copy is unavailable or not permitted." to "コピーを利用できないか、許可されていません。",
    "Copy" to "コピー",
    "{0} note" to "メモ {0} 件",
    "{0} notes" to "メモ {0} 件",
    "Add paragraph note" to "段落メモを追加",
    "Note" to "メモ",
    "The note could not be saved or is no longer permitted." to "メモを保存できないか、現在の権利では許可されていません。",
    "The image navigation graph is unavailable." to "画像のナビゲーション情報を利用できません。",
    "Page {0}" to "{0} ページ",
    "The image resource could not be opened." to "画像リソースを開けませんでした。",
    "The EPUB navigation graph is unavailable." to "EPUB のナビゲーション情報を利用できません。",
    "The EPUB resources could not be opened." to "EPUB リソースを開けませんでした。",
    "Previous" to "前へ",
    "Next" to "次へ",
    "EPUB rendering is unavailable until this platform supplies its secure browser renderer." to
        "このプラットフォームに安全なブラウザレンダラーがないため、EPUB を表示できません。",
    "This image representation has not been materialized into reader pages." to
        "この画像コンテンツは、まだリーダーページとして生成されていません。",
    "Content saved for offline reading." to "コンテンツをオフライン閲覧用に保存しました。",
    "Extension content" to "拡張機能のコンテンツ",
    "Unable to load chapters" to "章を読み込めません",
    "No chapters" to "章がありません",
    "This extension did not return any readable units." to "この拡張機能から閲覧可能な単位が返されませんでした。",
    "Choose a format when this chapter provides more than one." to "この章に複数の形式がある場合は、形式を選択してください。",
    "Save offline" to "オフラインに保存",
    "Load more" to "さらに読み込む",
    "Choose content format" to "コンテンツ形式を選択",
    "The operation could not be completed." to "操作を完了できませんでした。",
    "Format: {0}" to "形式：{0}",
)

private val KoreanReaderTranslations = mapOf(
    "Import local content" to "로컬 콘텐츠 가져오기",
    "Images, CBZ, ZIP, TXT or EPUB · stored on this device" to "이미지, CBZ, ZIP, TXT 또는 EPUB · 이 기기에 저장",
    "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync." to
        "암호화 동기화에 명시적으로 추가하지 않으면 TXT와 EPUB 본문은 이 기기에만 보관됩니다.",
    "Encrypted TXT/EPUB body sync" to "TXT/EPUB 본문 암호화 동기화",
    "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB." to
        "현재 권한이 SYNC_BLOB을 허용할 때만 백그라운드에서 업로드합니다.",
    "Choose files" to "파일 선택",
    "Encrypted chapter body sync" to "장 본문 암호화 동기화",
    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload." to
        "기본값은 꺼짐입니다. 활성화하면 현재 SYNC_BLOB 권한이 허용한 본문만 암호화 백그라운드 업로드 대기열에 추가됩니다.",
    "This content is no longer available under the current rights grant." to "현재 콘텐츠 권한으로는 이 콘텐츠를 사용할 수 없습니다.",
    "Full-text search is unavailable for this content." to "이 콘텐츠에서는 전문 검색을 사용할 수 없습니다.",
    "Search text" to "본문 검색",
    "Speech stopped because it is unavailable or no longer permitted." to "음성 읽기를 사용할 수 없거나 더 이상 허용되지 않아 중지했습니다.",
    "Speech unavailable" to "음성 읽기 사용 불가",
    "Speaking…" to "읽는 중…",
    "Speak from paragraph" to "이 문단부터 읽기",
    "Stop" to "중지",
    "Search this book" to "이 책 검색",
    "Search is no longer permitted." to "현재 콘텐츠 권한으로는 검색할 수 없습니다.",
    "Find" to "찾기",
    "Add note" to "메모 추가",
    "Reader paragraph" to "리더 문단",
    "Copy is unavailable or not permitted." to "복사를 사용할 수 없거나 허용되지 않습니다.",
    "Copy" to "복사",
    "{0} note" to "메모 {0}개",
    "{0} notes" to "메모 {0}개",
    "Add paragraph note" to "문단 메모 추가",
    "Note" to "메모",
    "The note could not be saved or is no longer permitted." to "메모를 저장할 수 없거나 현재 권한으로 허용되지 않습니다.",
    "The image navigation graph is unavailable." to "이미지 탐색 정보를 사용할 수 없습니다.",
    "Page {0}" to "{0}페이지",
    "The image resource could not be opened." to "이미지 리소스를 열 수 없습니다.",
    "The EPUB navigation graph is unavailable." to "EPUB 탐색 정보를 사용할 수 없습니다.",
    "The EPUB resources could not be opened." to "EPUB 리소스를 열 수 없습니다.",
    "Previous" to "이전",
    "Next" to "다음",
    "EPUB rendering is unavailable until this platform supplies its secure browser renderer." to
        "이 플랫폼에 안전한 브라우저 렌더러가 없어 EPUB을 표시할 수 없습니다.",
    "This image representation has not been materialized into reader pages." to
        "이 이미지 콘텐츠는 아직 리더 페이지로 생성되지 않았습니다.",
    "Content saved for offline reading." to "오프라인 읽기용으로 콘텐츠를 저장했습니다.",
    "Extension content" to "확장 콘텐츠",
    "Unable to load chapters" to "챕터를 불러올 수 없음",
    "No chapters" to "챕터 없음",
    "This extension did not return any readable units." to "이 확장에서 읽을 수 있는 단위를 반환하지 않았습니다.",
    "Choose a format when this chapter provides more than one." to "이 챕터가 여러 형식을 제공하면 하나를 선택하세요.",
    "Save offline" to "오프라인 저장",
    "Load more" to "더 불러오기",
    "Choose content format" to "콘텐츠 형식 선택",
    "The operation could not be completed." to "작업을 완료할 수 없습니다.",
    "Format: {0}" to "형식: {0}",
)

private val FrenchReaderTranslations = mapOf(
    "Import local content" to "Importer du contenu local",
    "Images, CBZ, ZIP, TXT or EPUB · stored on this device" to "Images, CBZ, ZIP, TXT ou EPUB · stockés sur cet appareil",
    "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync." to
        "Les contenus TXT et EPUB restent sur cet appareil sauf ajout explicite à la synchronisation chiffrée.",
    "Encrypted TXT/EPUB body sync" to "Synchronisation chiffrée des contenus TXT/EPUB",
    "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB." to
        "L’envoi s’exécute uniquement en arrière-plan lorsque les droits actuels autorisent SYNC_BLOB.",
    "Choose files" to "Choisir des fichiers",
    "Encrypted chapter body sync" to "Synchronisation chiffrée du texte des chapitres",
    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload." to
        "Désactivée par défaut. Seuls les contenus autorisés par le droit SYNC_BLOB actuel sont placés dans la file d’envoi chiffré en arrière-plan.",
    "This content is no longer available under the current rights grant." to "Les droits actuels ne permettent plus d’accéder à ce contenu.",
    "Full-text search is unavailable for this content." to "La recherche plein texte n’est pas disponible pour ce contenu.",
    "Search text" to "Rechercher dans le texte",
    "Speech stopped because it is unavailable or no longer permitted." to "La lecture vocale s’est arrêtée, car elle est indisponible ou n’est plus autorisée.",
    "Speech unavailable" to "Lecture vocale indisponible",
    "Speaking…" to "Lecture en cours…",
    "Speak from paragraph" to "Lire à partir du paragraphe",
    "Stop" to "Arrêter",
    "Search this book" to "Rechercher dans ce livre",
    "Search is no longer permitted." to "La recherche n’est plus autorisée.",
    "Find" to "Rechercher",
    "Add note" to "Ajouter une note",
    "Reader paragraph" to "Paragraphe du lecteur",
    "Copy is unavailable or not permitted." to "La copie est indisponible ou n’est pas autorisée.",
    "Copy" to "Copier",
    "{0} note" to "{0} note",
    "{0} notes" to "{0} notes",
    "Add paragraph note" to "Ajouter une note au paragraphe",
    "Note" to "Note",
    "The note could not be saved or is no longer permitted." to "La note n’a pas pu être enregistrée ou n’est plus autorisée.",
    "The image navigation graph is unavailable." to "La navigation entre les images est indisponible.",
    "Page {0}" to "Page {0}",
    "The image resource could not be opened." to "La ressource image n’a pas pu être ouverte.",
    "The EPUB navigation graph is unavailable." to "La navigation EPUB est indisponible.",
    "The EPUB resources could not be opened." to "Les ressources EPUB n’ont pas pu être ouvertes.",
    "Previous" to "Précédent",
    "Next" to "Suivant",
    "EPUB rendering is unavailable until this platform supplies its secure browser renderer." to
        "L’affichage EPUB est indisponible tant que cette plateforme ne fournit pas de moteur de navigateur sécurisé.",
    "This image representation has not been materialized into reader pages." to
        "Ce contenu image n’a pas encore été matérialisé en pages de lecture.",
    "Content saved for offline reading." to "Contenu enregistré pour la lecture hors ligne.",
    "Extension content" to "Contenu de l’extension",
    "Unable to load chapters" to "Impossible de charger les chapitres",
    "No chapters" to "Aucun chapitre",
    "This extension did not return any readable units." to "Cette extension n’a renvoyé aucune unité lisible.",
    "Choose a format when this chapter provides more than one." to "Choisissez un format lorsque ce chapitre en propose plusieurs.",
    "Save offline" to "Enregistrer hors ligne",
    "Load more" to "Charger plus",
    "Choose content format" to "Choisir le format du contenu",
    "The operation could not be completed." to "L’opération n’a pas pu être effectuée.",
    "Format: {0}" to "Format : {0}",
)

private val GermanReaderTranslations = mapOf(
    "Import local content" to "Lokale Inhalte importieren",
    "Images, CBZ, ZIP, TXT or EPUB · stored on this device" to "Bilder, CBZ, ZIP, TXT oder EPUB · auf diesem Gerät gespeichert",
    "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync." to
        "TXT- und EPUB-Inhalte bleiben auf diesem Gerät, sofern sie nicht ausdrücklich zur verschlüsselten Synchronisierung hinzugefügt werden.",
    "Encrypted TXT/EPUB body sync" to "Verschlüsselte TXT-/EPUB-Inhaltssynchronisierung",
    "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB." to
        "Uploads laufen nur im Hintergrund und nur, wenn die aktuellen Rechte SYNC_BLOB erlauben.",
    "Choose files" to "Dateien auswählen",
    "Encrypted chapter body sync" to "Verschlüsselte Kapitelinhaltssynchronisierung",
    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload." to
        "Standardmäßig deaktiviert. Nur durch das aktuelle SYNC_BLOB-Recht erlaubte Inhalte werden für den verschlüsselten Hintergrund-Upload vorgemerkt.",
    "This content is no longer available under the current rights grant." to "Die aktuellen Rechte erlauben den Zugriff auf diesen Inhalt nicht mehr.",
    "Full-text search is unavailable for this content." to "Die Volltextsuche ist für diesen Inhalt nicht verfügbar.",
    "Search text" to "Text durchsuchen",
    "Speech stopped because it is unavailable or no longer permitted." to "Die Sprachausgabe wurde beendet, weil sie nicht verfügbar oder nicht mehr erlaubt ist.",
    "Speech unavailable" to "Sprachausgabe nicht verfügbar",
    "Speaking…" to "Wird vorgelesen…",
    "Speak from paragraph" to "Ab diesem Absatz vorlesen",
    "Stop" to "Stopp",
    "Search this book" to "Dieses Buch durchsuchen",
    "Search is no longer permitted." to "Die Suche ist nicht mehr erlaubt.",
    "Find" to "Suchen",
    "Add note" to "Notiz hinzufügen",
    "Reader paragraph" to "Reader-Absatz",
    "Copy is unavailable or not permitted." to "Kopieren ist nicht verfügbar oder nicht erlaubt.",
    "Copy" to "Kopieren",
    "{0} note" to "{0} Notiz",
    "{0} notes" to "{0} Notizen",
    "Add paragraph note" to "Absatznotiz hinzufügen",
    "Note" to "Notiz",
    "The note could not be saved or is no longer permitted." to "Die Notiz konnte nicht gespeichert werden oder ist nicht mehr erlaubt.",
    "The image navigation graph is unavailable." to "Die Bildnavigation ist nicht verfügbar.",
    "Page {0}" to "Seite {0}",
    "The image resource could not be opened." to "Die Bildressource konnte nicht geöffnet werden.",
    "The EPUB navigation graph is unavailable." to "Die EPUB-Navigation ist nicht verfügbar.",
    "The EPUB resources could not be opened." to "Die EPUB-Ressourcen konnten nicht geöffnet werden.",
    "Previous" to "Zurück",
    "Next" to "Weiter",
    "EPUB rendering is unavailable until this platform supplies its secure browser renderer." to
        "EPUB kann erst angezeigt werden, wenn diese Plattform eine sichere Browser-Engine bereitstellt.",
    "This image representation has not been materialized into reader pages." to
        "Dieser Bildinhalt wurde noch nicht als Reader-Seiten materialisiert.",
    "Content saved for offline reading." to "Inhalt zum Offline-Lesen gespeichert.",
    "Extension content" to "Erweiterungsinhalt",
    "Unable to load chapters" to "Kapitel konnten nicht geladen werden",
    "No chapters" to "Keine Kapitel",
    "This extension did not return any readable units." to "Diese Erweiterung hat keine lesbaren Einheiten zurückgegeben.",
    "Choose a format when this chapter provides more than one." to "Wähle ein Format aus, wenn dieses Kapitel mehrere anbietet.",
    "Save offline" to "Offline speichern",
    "Load more" to "Mehr laden",
    "Choose content format" to "Inhaltsformat auswählen",
    "The operation could not be completed." to "Der Vorgang konnte nicht abgeschlossen werden.",
    "Format: {0}" to "Inhaltsformat: {0}",
)

private val SpanishReaderTranslations = mapOf(
    "Import local content" to "Importar contenido local",
    "Images, CBZ, ZIP, TXT or EPUB · stored on this device" to "Imágenes, CBZ, ZIP, TXT o EPUB · guardados en este dispositivo",
    "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync." to
        "El contenido TXT y EPUB permanece en este dispositivo salvo que lo añadas expresamente a la sincronización cifrada.",
    "Encrypted TXT/EPUB body sync" to "Sincronización cifrada del contenido TXT/EPUB",
    "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB." to
        "Las cargas solo se ejecutan en segundo plano cuando los permisos actuales autorizan SYNC_BLOB.",
    "Choose files" to "Elegir archivos",
    "Encrypted chapter body sync" to "Sincronización cifrada del contenido de capítulos",
    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload." to
        "Desactivada de forma predeterminada. Solo el contenido autorizado por el permiso SYNC_BLOB actual se pone en cola para la carga cifrada en segundo plano.",
    "This content is no longer available under the current rights grant." to "Los permisos actuales ya no permiten acceder a este contenido.",
    "Full-text search is unavailable for this content." to "La búsqueda de texto completo no está disponible para este contenido.",
    "Search text" to "Buscar en el texto",
    "Speech stopped because it is unavailable or no longer permitted." to "La lectura por voz se detuvo porque no está disponible o ya no está permitida.",
    "Speech unavailable" to "Lectura por voz no disponible",
    "Speaking…" to "Leyendo…",
    "Speak from paragraph" to "Leer desde este párrafo",
    "Stop" to "Detener",
    "Search this book" to "Buscar en este libro",
    "Search is no longer permitted." to "La búsqueda ya no está permitida.",
    "Find" to "Buscar",
    "Add note" to "Añadir nota",
    "Reader paragraph" to "Párrafo del lector",
    "Copy is unavailable or not permitted." to "La copia no está disponible o no está permitida.",
    "Copy" to "Copiar",
    "{0} note" to "{0} nota",
    "{0} notes" to "{0} notas",
    "Add paragraph note" to "Añadir nota al párrafo",
    "Note" to "Nota",
    "The note could not be saved or is no longer permitted." to "No se pudo guardar la nota o ya no está permitida.",
    "The image navigation graph is unavailable." to "La navegación de imágenes no está disponible.",
    "Page {0}" to "Página {0}",
    "The image resource could not be opened." to "No se pudo abrir el recurso de imagen.",
    "The EPUB navigation graph is unavailable." to "La navegación EPUB no está disponible.",
    "The EPUB resources could not be opened." to "No se pudieron abrir los recursos EPUB.",
    "Previous" to "Anterior",
    "Next" to "Siguiente",
    "EPUB rendering is unavailable until this platform supplies its secure browser renderer." to
        "EPUB no puede mostrarse hasta que esta plataforma proporcione un navegador seguro.",
    "This image representation has not been materialized into reader pages." to
        "Este contenido de imágenes aún no se ha convertido en páginas del lector.",
    "Content saved for offline reading." to "Contenido guardado para leer sin conexión.",
    "Extension content" to "Contenido de la extensión",
    "Unable to load chapters" to "No se pudieron cargar los capítulos",
    "No chapters" to "No hay capítulos",
    "This extension did not return any readable units." to "Esta extensión no devolvió ninguna unidad legible.",
    "Choose a format when this chapter provides more than one." to "Elige un formato cuando este capítulo ofrezca más de uno.",
    "Save offline" to "Guardar sin conexión",
    "Load more" to "Cargar más",
    "Choose content format" to "Elegir formato de contenido",
    "The operation could not be completed." to "No se pudo completar la operación.",
    "Format: {0}" to "Formato: {0}",
)

private val PortugueseReaderTranslations = mapOf(
    "Import local content" to "Importar conteúdo local",
    "Images, CBZ, ZIP, TXT or EPUB · stored on this device" to "Imagens, CBZ, ZIP, TXT ou EPUB · armazenados neste dispositivo",
    "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync." to
        "O conteúdo TXT e EPUB permanece neste dispositivo, a menos que você o adicione explicitamente à sincronização criptografada.",
    "Encrypted TXT/EPUB body sync" to "Sincronização criptografada do conteúdo TXT/EPUB",
    "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB." to
        "Os uploads ocorrem apenas em segundo plano quando as permissões atuais autorizam SYNC_BLOB.",
    "Choose files" to "Escolher arquivos",
    "Encrypted chapter body sync" to "Sincronização criptografada do conteúdo dos capítulos",
    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload." to
        "Desativada por padrão. Somente o conteúdo permitido pela autorização SYNC_BLOB atual entra na fila de upload criptografado em segundo plano.",
    "This content is no longer available under the current rights grant." to "As permissões atuais não permitem mais acessar este conteúdo.",
    "Full-text search is unavailable for this content." to "A pesquisa de texto completo não está disponível para este conteúdo.",
    "Search text" to "Pesquisar no texto",
    "Speech stopped because it is unavailable or no longer permitted." to "A leitura em voz alta foi interrompida porque não está disponível ou não é mais permitida.",
    "Speech unavailable" to "Leitura em voz alta indisponível",
    "Speaking…" to "Lendo…",
    "Speak from paragraph" to "Ler a partir deste parágrafo",
    "Stop" to "Parar",
    "Search this book" to "Pesquisar neste livro",
    "Search is no longer permitted." to "A pesquisa não é mais permitida.",
    "Find" to "Pesquisar",
    "Add note" to "Adicionar nota",
    "Reader paragraph" to "Parágrafo do leitor",
    "Copy is unavailable or not permitted." to "A cópia não está disponível ou não é permitida.",
    "Copy" to "Copiar",
    "{0} note" to "{0} nota",
    "{0} notes" to "{0} notas",
    "Add paragraph note" to "Adicionar nota ao parágrafo",
    "Note" to "Nota",
    "The note could not be saved or is no longer permitted." to "Não foi possível salvar a nota ou ela não é mais permitida.",
    "The image navigation graph is unavailable." to "A navegação de imagens não está disponível.",
    "Page {0}" to "Página {0}",
    "The image resource could not be opened." to "Não foi possível abrir o recurso de imagem.",
    "The EPUB navigation graph is unavailable." to "A navegação EPUB não está disponível.",
    "The EPUB resources could not be opened." to "Não foi possível abrir os recursos EPUB.",
    "Previous" to "Anterior",
    "Next" to "Próximo",
    "EPUB rendering is unavailable until this platform supplies its secure browser renderer." to
        "O EPUB não pode ser exibido até que esta plataforma forneça um navegador seguro.",
    "This image representation has not been materialized into reader pages." to
        "Este conteúdo de imagens ainda não foi convertido em páginas do leitor.",
    "Content saved for offline reading." to "Conteúdo salvo para leitura offline.",
    "Extension content" to "Conteúdo da extensão",
    "Unable to load chapters" to "Não foi possível carregar os capítulos",
    "No chapters" to "Nenhum capítulo",
    "This extension did not return any readable units." to "Esta extensão não retornou nenhuma unidade legível.",
    "Choose a format when this chapter provides more than one." to "Escolha um formato quando este capítulo oferecer mais de um.",
    "Save offline" to "Salvar offline",
    "Load more" to "Carregar mais",
    "Choose content format" to "Escolher formato do conteúdo",
    "The operation could not be completed." to "Não foi possível concluir a operação.",
    "Format: {0}" to "Formato: {0}",
)

private val TraditionalPortabilityTranslations = mapOf(
    "Archive ready" to "封存檔已就緒",
    "Back" to "返回",
    "Binary export is unavailable until the content-backup service is connected." to
        "內容備份服務尚未連接，因此無法匯出二進位封存檔。",
    "Books" to "書籍",
    "Chapter bodies are not shown in this report" to "此報告不會顯示章節正文",
    "Choose .shinsou2 archive" to "選擇 .shinsou2 封存檔",
    "Choose another backup" to "選擇其他備份",
    "Choose backup" to "選擇備份",
    "Content backup v2" to "內容備份 v2",
    "Cookies ({0})" to "Cookie（{0}）",
    "Create binary archive" to "建立二進位封存檔",
    "Create portable archive" to "建立可攜式封存檔",
    "Credentials ({0})" to "登入憑證（{0}）",
    "Credentials, cookies, OAuth tokens, and device keys are always excluded." to
        "登入憑證、Cookie、OAuth 權杖與裝置金鑰一律排除。",
    "Done" to "完成",
    "Each body is included only when its rights grant permits export; omissions are recorded in the manifest." to
        "只有權限授權允許匯出的正文才會納入；省略項目會記錄在資訊清單中。",
    "Import from ShuYue" to "從 ShuYue 匯入",
    "Import protected secrets?" to "要匯入受保護的機密嗎？",
    "Import secrets" to "匯入機密",
    "Import selected content" to "匯入所選內容",
    "Imported {0} credentials and {1} cookies into protected storage." to
        "已將 {0} 組登入憑證與 {1} 個 Cookie 匯入受保護儲存空間。",
    "Include exportable content bodies" to "包含可匯出的內容正文",
    "Inspecting a bounded copy…" to "正在安全範圍內檢查副本…",
    "Leave workspace and restore this device" to "離開工作區並還原此裝置",
    "Moving from ShuYue?" to "要從 ShuYue 遷移嗎？",
    "No backup selected" to "尚未選擇備份",
    "Optional secrets" to "選用機密",
    "Portable metadata" to "可攜式中繼資料",
    "Preview truncated; only all-or-none selection is available" to "預覽已截斷，只能全選或全不選",
    "Protected platform storage is unavailable, so secret import is blocked." to
        "平台的受保護儲存空間無法使用，因此已封鎖機密匯入。",
    "Quarantined extension scripts" to "已隔離的擴充套件腳本",
    "Reading positions ({0})" to "閱讀位置（{0}）",
    "Restore and sync to all devices" to "還原並同步至所有裝置",
    "Restore content archive" to "還原內容封存檔",
    "Restore remains blocked until one shared content transaction and sync-outbox coordinator is connected." to
        "必須先連接共用內容交易與同步寄件匣協調器，才能進行還原。",
    "Restore this archive?" to "要還原此封存檔嗎？",
    "Restore verified archive" to "還原已驗證的封存檔",
    "Restored {0} publications, {1} annotations, and {2} content bodies." to
        "已還原 {0} 部出版品、{1} 則註記與 {2} 份內容正文。",
    "Review and import selected secrets" to "檢閱並匯入所選機密",
    "Review first; scripts stay quarantined and secrets stay excluded" to
        "請先檢閱；腳本會保持隔離，機密仍會排除",
    "Selected content and quarantined scripts were committed transactionally." to
        "所選內容與隔離腳本已透過交易一次提交。",
    "Selected scripts are stored for later review and are never executed by import" to
        "所選腳本只會保存供稍後檢閱，匯入過程絕不執行",
    "ShuYue backup v1" to "ShuYue 備份 v1",
    "The backup was rejected" to "備份已遭拒絕",
    "The complete container, declared paths, checksums, and portable state are validated before restore is enabled." to
        "啟用還原前，會驗證完整容器、宣告路徑、校驗和與可攜式狀態。",
    "The device must leave the workspace before its local state is replaced." to
        "此裝置必須先離開工作區，才能取代本機狀態。",
    "This exact import was already committed; nothing was duplicated." to
        "完全相同的匯入已提交，不會建立重複資料。",
    "This requires a Ready workspace and durable mutations, body uploads, and outbox records in the shared commit." to
        "這需要狀態為就緒的工作區，並在共用提交中寫入持久變更、正文上傳與寄件匣記錄。",
    "This separate action replaces the stored ShuYue migration secret batch. Values remain device-only and cannot be recovered from a portable backup." to
        "此獨立操作會取代已儲存的 ShuYue 遷移機密批次。數值只保留於此裝置，無法從可攜式備份復原。",
    "Use the dedicated staged importer for its validation report, book selection, script quarantine, and separate secret consent." to
        "請使用專用的分段匯入工具，以取得驗證報告、書籍選擇、腳本隔離與獨立機密同意流程。",
    "Validated contents" to "已驗證的內容",
    "Validated staging preview ready" to "已驗證的暫存預覽已就緒",
    "Validation report" to "驗證報告",
    "Values are never shown, backed up, synchronized, or imported automatically." to
        "數值絕不顯示、備份、同步或自動匯入。",
    "Verified portable state and bodies will be committed on this device." to
        "已驗證的可攜式狀態與正文將提交到此裝置。",
    "Verified restore preview" to "已驗證的還原預覽",
    "Version {0} · {1} bytes · digest {2}…" to "版本 {0} · {1} 位元組 · 摘要 {2}…",
    "Versioned manifest, checksums, and rights-filtered bodies" to
        "具版本的資訊清單、校驗和與依權限篩選的正文",
    "Where should this archive be restored?" to "要將此封存檔還原到哪裡？",
    "{0} books · {1} chapters · {2} reading positions" to
        "{0} 本書 · {1} 個章節 · {2} 個閱讀位置",
    "{0} categories · {1} characters of chapter text" to "{0} 個分類 · 章節正文共 {1} 個字元",
    "{0} chapters · {1}" to "{0} 個章節 · {1}",
    "{0} publications · {1} annotations · {2} content bodies" to
        "{0} 部出版品 · {1} 則註記 · {2} 份內容正文",
    "{0} · {1} attached manifests · {2} omitted" to "{0} · {1} 份附加資訊清單 · 省略 {2} 份",
    "Content backup is unavailable until the shared content storage is connected." to
        "共用內容儲存空間尚未連接，因此無法使用內容備份。",
    "ShuYue migration is unavailable until the shared content storage is connected." to
        "共用內容儲存空間尚未連接，因此無法使用 ShuYue 遷移。",
    "The checksummed content archive could not be created." to "無法建立含校驗和的內容封存檔。",
    "The selected content archive failed format or checksum validation." to
        "所選內容封存檔未通過格式或校驗和驗證。",
    "The selected content archive could not be inspected safely." to "無法安全檢查所選內容封存檔。",
    "The sync-aware content restore did not commit." to "同步感知內容還原未能提交。",
    "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive." to
        "同步工作區已離開，但此裝置的本機還原已回復原狀。請重試同一份已驗證的封存檔。",
    "The ShuYue backup could not be inspected safely." to "無法安全檢查 ShuYue 備份。",
    "This backup was already imported with a different selection." to "此備份已用不同選擇匯入。",
    "The transactional ShuYue import did not complete." to "ShuYue 交易式匯入未完成。",
    "No ShuYue secrets were replaced because protected storage failed." to
        "受保護儲存空間失敗，因此未取代任何 ShuYue 機密。",
    "The selected file is not an importable ShuYue v1 backup." to "所選檔案不是可匯入的 ShuYue v1 備份。",
    "The backup could not be inspected." to "無法檢查備份。",
    "Validation issue ({0})" to "驗證問題（{0}）",
    "The backup contains invalid or unsupported data. Review code {0}." to
        "備份包含無效或不支援的資料，請檢查代碼 {0}。",
    "Not requested" to "未要求",
    "Rights denied" to "權限拒絕",
    "Missing" to "缺少",
    "Corrupt" to "損毀",
    "Archive limit" to "封存檔限制",
)

private val SimplifiedPortabilityTranslations = TraditionalPortabilityTranslations.mapValues { (_, value) ->
    value.toSimplifiedChinese()
} + mapOf(
    "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive." to
        "已离开同步工作区，但设备本机还原已回滚。请使用同一份已验证的归档文件重试。",
)

private val JapanesePortabilityTranslations = mapOf(
    "Archive ready" to "アーカイブの準備ができました",
    "Back" to "戻る",
    "Binary export is unavailable until the content-backup service is connected." to
        "コンテンツバックアップサービスに接続するまで、バイナリアーカイブを書き出せません。",
    "Books" to "書籍",
    "Chapter bodies are not shown in this report" to "このレポートには章の本文を表示しません",
    "Choose .shinsou2 archive" to ".shinsou2 アーカイブを選択",
    "Choose another backup" to "別のバックアップを選択",
    "Choose backup" to "バックアップを選択",
    "Content backup v2" to "コンテンツバックアップ v2",
    "Cookies ({0})" to "Cookie（{0}）",
    "Create binary archive" to "バイナリアーカイブを作成",
    "Create portable archive" to "ポータブルアーカイブを作成",
    "Credentials ({0})" to "認証情報（{0}）",
    "Credentials, cookies, OAuth tokens, and device keys are always excluded." to
        "認証情報、Cookie、OAuth トークン、デバイスキーは常に除外されます。",
    "Done" to "完了",
    "Each body is included only when its rights grant permits export; omissions are recorded in the manifest." to
        "本文は権利が書き出しを許可する場合だけ含まれ、省略項目はマニフェストに記録されます。",
    "Import from ShuYue" to "ShuYue から読み込む",
    "Import protected secrets?" to "保護された機密情報を読み込みますか？",
    "Import secrets" to "機密情報を読み込む",
    "Import selected content" to "選択したコンテンツを読み込む",
    "Imported {0} credentials and {1} cookies into protected storage." to
        "{0} 件の認証情報と {1} 件の Cookie を保護ストレージへ読み込みました。",
    "Include exportable content bodies" to "書き出し可能な本文を含める",
    "Inspecting a bounded copy…" to "制限付きコピーを検査しています…",
    "Leave workspace and restore this device" to "ワークスペースを離れてこのデバイスを復元",
    "Moving from ShuYue?" to "ShuYue から移行しますか？",
    "No backup selected" to "バックアップが選択されていません",
    "Optional secrets" to "任意の機密情報",
    "Portable metadata" to "ポータブルメタデータ",
    "Preview truncated; only all-or-none selection is available" to
        "プレビューは省略されています。全選択または全解除のみ可能です",
    "Protected platform storage is unavailable, so secret import is blocked." to
        "プラットフォームの保護ストレージを利用できないため、機密情報の読み込みはブロックされています。",
    "Quarantined extension scripts" to "隔離された拡張スクリプト",
    "Reading positions ({0})" to "読書位置（{0}）",
    "Restore and sync to all devices" to "復元してすべてのデバイスへ同期",
    "Restore content archive" to "コンテンツアーカイブを復元",
    "Restore remains blocked until one shared content transaction and sync-outbox coordinator is connected." to
        "共有コンテンツトランザクションと同期送信キューのコーディネーターに接続するまで、復元はブロックされます。",
    "Restore this archive?" to "このアーカイブを復元しますか？",
    "Restore verified archive" to "検証済みアーカイブを復元",
    "Restored {0} publications, {1} annotations, and {2} content bodies." to
        "{0} 件の出版物、{1} 件の注釈、{2} 件の本文を復元しました。",
    "Review and import selected secrets" to "選択した機密情報を確認して読み込む",
    "Review first; scripts stay quarantined and secrets stay excluded" to
        "先に確認してください。スクリプトは隔離され、機密情報は除外されたままです",
    "Selected content and quarantined scripts were committed transactionally." to
        "選択したコンテンツと隔離スクリプトをトランザクションとしてコミットしました。",
    "Selected scripts are stored for later review and are never executed by import" to
        "選択したスクリプトは後の確認用に保存され、読み込み時には実行されません",
    "ShuYue backup v1" to "ShuYue バックアップ v1",
    "The backup was rejected" to "バックアップは拒否されました",
    "The complete container, declared paths, checksums, and portable state are validated before restore is enabled." to
        "復元を有効にする前に、コンテナ全体、宣言済みパス、チェックサム、ポータブル状態を検証します。",
    "The device must leave the workspace before its local state is replaced." to
        "ローカル状態を置き換える前に、このデバイスはワークスペースから離れる必要があります。",
    "This exact import was already committed; nothing was duplicated." to
        "同一の読み込みはすでにコミット済みです。重複は作成されませんでした。",
    "This requires a Ready workspace and durable mutations, body uploads, and outbox records in the shared commit." to
        "準備完了のワークスペースと、共有コミット内の永続的な変更、本文アップロード、送信キュー記録が必要です。",
    "This separate action replaces the stored ShuYue migration secret batch. Values remain device-only and cannot be recovered from a portable backup." to
        "この個別操作は保存済みの ShuYue 移行機密情報を置き換えます。値はこのデバイスだけに保持され、ポータブルバックアップからは復元できません。",
    "Use the dedicated staged importer for its validation report, book selection, script quarantine, and separate secret consent." to
        "検証レポート、書籍選択、スクリプト隔離、個別の機密情報同意には、専用の段階的インポーターを使用してください。",
    "Validated contents" to "検証済みコンテンツ",
    "Validated staging preview ready" to "検証済みのステージングプレビューが準備できました",
    "Validation report" to "検証レポート",
    "Values are never shown, backed up, synchronized, or imported automatically." to
        "値が表示、バックアップ、同期、自動読み込みされることはありません。",
    "Verified portable state and bodies will be committed on this device." to
        "検証済みのポータブル状態と本文をこのデバイスにコミットします。",
    "Verified restore preview" to "検証済み復元プレビュー",
    "Version {0} · {1} bytes · digest {2}…" to "バージョン {0} · {1} バイト · ダイジェスト {2}…",
    "Versioned manifest, checksums, and rights-filtered bodies" to
        "バージョン付きマニフェスト、チェックサム、権利で絞り込まれた本文",
    "Where should this archive be restored?" to "このアーカイブをどこへ復元しますか？",
    "{0} books · {1} chapters · {2} reading positions" to "{0} 冊 · {1} 章 · 読書位置 {2} 件",
    "{0} categories · {1} characters of chapter text" to "カテゴリ {0} 件 · 章本文 {1} 文字",
    "{0} chapters · {1}" to "{0} 章 · {1}",
    "{0} publications · {1} annotations · {2} content bodies" to
        "出版物 {0} 件 · 注釈 {1} 件 · 本文 {2} 件",
    "{0} · {1} attached manifests · {2} omitted" to "{0} · 添付マニフェスト {1} 件 · 省略 {2} 件",
    "Content backup is unavailable until the shared content storage is connected." to
        "共有コンテンツストレージに接続するまで、コンテンツバックアップを利用できません。",
    "ShuYue migration is unavailable until the shared content storage is connected." to
        "共有コンテンツストレージに接続するまで、ShuYue 移行を利用できません。",
    "The checksummed content archive could not be created." to "チェックサム付きコンテンツアーカイブを作成できませんでした。",
    "The selected content archive failed format or checksum validation." to
        "選択したコンテンツアーカイブは形式またはチェックサム検証に失敗しました。",
    "The selected content archive could not be inspected safely." to "選択したコンテンツアーカイブを安全に検査できませんでした。",
    "The sync-aware content restore did not commit." to "同期対応のコンテンツ復元をコミットできませんでした。",
    "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive." to
        "同期ワークスペースから離脱しましたが、デバイス内の復元はロールバックされました。同じ検証済みアーカイブで再試行してください。",
    "The ShuYue backup could not be inspected safely." to "ShuYue バックアップを安全に検査できませんでした。",
    "This backup was already imported with a different selection." to "このバックアップは別の選択内容ですでに読み込まれています。",
    "The transactional ShuYue import did not complete." to "ShuYue のトランザクション読み込みが完了しませんでした。",
    "No ShuYue secrets were replaced because protected storage failed." to
        "保護ストレージでエラーが発生したため、ShuYue の機密情報は置き換えられませんでした。",
    "The selected file is not an importable ShuYue v1 backup." to "選択したファイルは読み込み可能な ShuYue v1 バックアップではありません。",
    "The backup could not be inspected." to "バックアップを検査できませんでした。",
    "Validation issue ({0})" to "検証上の問題（{0}）",
    "The backup contains invalid or unsupported data. Review code {0}." to
        "バックアップに無効または未対応のデータがあります。コード {0} を確認してください。",
    "Not requested" to "要求なし",
    "Rights denied" to "権利により拒否",
    "Missing" to "不足",
    "Corrupt" to "破損",
    "Archive limit" to "アーカイブ上限",
)

private val KoreanPortabilityTranslations = mapOf(
    "Archive ready" to "아카이브 준비 완료",
    "Back" to "뒤로",
    "Binary export is unavailable until the content-backup service is connected." to
        "콘텐츠 백업 서비스가 연결될 때까지 바이너리 아카이브를 내보낼 수 없습니다.",
    "Books" to "도서",
    "Chapter bodies are not shown in this report" to "이 보고서에는 장 본문이 표시되지 않습니다",
    "Choose .shinsou2 archive" to ".shinsou2 아카이브 선택",
    "Choose another backup" to "다른 백업 선택",
    "Choose backup" to "백업 선택",
    "Content backup v2" to "콘텐츠 백업 v2",
    "Cookies ({0})" to "쿠키({0})",
    "Create binary archive" to "바이너리 아카이브 만들기",
    "Create portable archive" to "이동식 아카이브 만들기",
    "Credentials ({0})" to "자격 증명({0})",
    "Credentials, cookies, OAuth tokens, and device keys are always excluded." to
        "자격 증명, 쿠키, OAuth 토큰 및 기기 키는 항상 제외됩니다.",
    "Done" to "완료",
    "Each body is included only when its rights grant permits export; omissions are recorded in the manifest." to
        "현재 권한이 내보내기를 허용하는 본문만 포함되며, 누락 항목은 매니페스트에 기록됩니다.",
    "Import from ShuYue" to "ShuYue에서 가져오기",
    "Import protected secrets?" to "보호된 비밀 정보를 가져오시겠습니까?",
    "Import secrets" to "비밀 정보 가져오기",
    "Import selected content" to "선택한 콘텐츠 가져오기",
    "Imported {0} credentials and {1} cookies into protected storage." to
        "자격 증명 {0}개와 쿠키 {1}개를 보호된 저장소로 가져왔습니다.",
    "Include exportable content bodies" to "내보낼 수 있는 본문 포함",
    "Inspecting a bounded copy…" to "제한된 복사본 검사 중…",
    "Leave workspace and restore this device" to "작업 공간을 나가고 이 기기 복원",
    "Moving from ShuYue?" to "ShuYue에서 이전하시나요?",
    "No backup selected" to "선택한 백업 없음",
    "Optional secrets" to "선택적 비밀 정보",
    "Portable metadata" to "이동식 메타데이터",
    "Preview truncated; only all-or-none selection is available" to
        "미리보기가 잘려 전체 선택 또는 전체 해제만 가능합니다",
    "Protected platform storage is unavailable, so secret import is blocked." to
        "플랫폼의 보호된 저장소를 사용할 수 없어 비밀 정보 가져오기가 차단되었습니다.",
    "Quarantined extension scripts" to "격리된 확장 스크립트",
    "Reading positions ({0})" to "읽기 위치({0})",
    "Restore and sync to all devices" to "복원 후 모든 기기에 동기화",
    "Restore content archive" to "콘텐츠 아카이브 복원",
    "Restore remains blocked until one shared content transaction and sync-outbox coordinator is connected." to
        "공유 콘텐츠 트랜잭션 및 동기화 보낼 편지함 코디네이터가 연결될 때까지 복원이 차단됩니다.",
    "Restore this archive?" to "이 아카이브를 복원하시겠습니까?",
    "Restore verified archive" to "검증된 아카이브 복원",
    "Restored {0} publications, {1} annotations, and {2} content bodies." to
        "간행물 {0}개, 주석 {1}개, 본문 {2}개를 복원했습니다.",
    "Review and import selected secrets" to "선택한 비밀 정보 검토 및 가져오기",
    "Review first; scripts stay quarantined and secrets stay excluded" to
        "먼저 검토하세요. 스크립트는 격리되고 비밀 정보는 제외된 상태로 유지됩니다",
    "Selected content and quarantined scripts were committed transactionally." to
        "선택한 콘텐츠와 격리된 스크립트를 하나의 트랜잭션으로 커밋했습니다.",
    "Selected scripts are stored for later review and are never executed by import" to
        "선택한 스크립트는 나중에 검토하도록 저장되며 가져오기 중에는 실행되지 않습니다",
    "ShuYue backup v1" to "ShuYue 백업 v1",
    "The backup was rejected" to "백업이 거부되었습니다",
    "The complete container, declared paths, checksums, and portable state are validated before restore is enabled." to
        "복원을 활성화하기 전에 전체 컨테이너, 선언된 경로, 체크섬 및 이동식 상태를 검증합니다.",
    "The device must leave the workspace before its local state is replaced." to
        "로컬 상태를 교체하기 전에 이 기기가 작업 공간에서 나가야 합니다.",
    "This exact import was already committed; nothing was duplicated." to
        "동일한 가져오기가 이미 커밋되어 중복 항목을 만들지 않았습니다.",
    "This requires a Ready workspace and durable mutations, body uploads, and outbox records in the shared commit." to
        "준비된 작업 공간과 공유 커밋의 영구 변경, 본문 업로드 및 보낼 편지함 기록이 필요합니다.",
    "This separate action replaces the stored ShuYue migration secret batch. Values remain device-only and cannot be recovered from a portable backup." to
        "이 별도 작업은 저장된 ShuYue 이전 비밀 정보 묶음을 교체합니다. 값은 이 기기에만 남으며 이동식 백업에서 복구할 수 없습니다.",
    "Use the dedicated staged importer for its validation report, book selection, script quarantine, and separate secret consent." to
        "검증 보고서, 도서 선택, 스크립트 격리 및 별도 비밀 정보 동의를 위해 전용 단계별 가져오기 도구를 사용하세요.",
    "Validated contents" to "검증된 콘텐츠",
    "Validated staging preview ready" to "검증된 준비 미리보기 완료",
    "Validation report" to "검증 보고서",
    "Values are never shown, backed up, synchronized, or imported automatically." to
        "값은 표시, 백업, 동기화 또는 자동 가져오기되지 않습니다.",
    "Verified portable state and bodies will be committed on this device." to
        "검증된 이동식 상태와 본문을 이 기기에 커밋합니다.",
    "Verified restore preview" to "검증된 복원 미리보기",
    "Version {0} · {1} bytes · digest {2}…" to "버전 {0} · {1}바이트 · 다이제스트 {2}…",
    "Versioned manifest, checksums, and rights-filtered bodies" to
        "버전이 지정된 매니페스트, 체크섬 및 권한으로 필터링된 본문",
    "Where should this archive be restored?" to "이 아카이브를 어디에 복원하시겠습니까?",
    "{0} books · {1} chapters · {2} reading positions" to "도서 {0}권 · 장 {1}개 · 읽기 위치 {2}개",
    "{0} categories · {1} characters of chapter text" to "카테고리 {0}개 · 장 본문 {1}자",
    "{0} chapters · {1}" to "장 {0}개 · {1}",
    "{0} publications · {1} annotations · {2} content bodies" to
        "간행물 {0}개 · 주석 {1}개 · 본문 {2}개",
    "{0} · {1} attached manifests · {2} omitted" to "{0} · 첨부 매니페스트 {1}개 · 누락 {2}개",
    "Content backup is unavailable until the shared content storage is connected." to
        "공유 콘텐츠 저장소가 연결될 때까지 콘텐츠 백업을 사용할 수 없습니다.",
    "ShuYue migration is unavailable until the shared content storage is connected." to
        "공유 콘텐츠 저장소가 연결될 때까지 ShuYue 이전을 사용할 수 없습니다.",
    "The checksummed content archive could not be created." to "체크섬이 포함된 콘텐츠 아카이브를 만들 수 없습니다.",
    "The selected content archive failed format or checksum validation." to
        "선택한 콘텐츠 아카이브가 형식 또는 체크섬 검증에 실패했습니다.",
    "The selected content archive could not be inspected safely." to "선택한 콘텐츠 아카이브를 안전하게 검사할 수 없습니다.",
    "The sync-aware content restore did not commit." to "동기화 인식 콘텐츠 복원이 커밋되지 않았습니다.",
    "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive." to
        "동기화 작업 공간에서는 나갔지만 기기 내 복원은 롤백되었습니다. 동일한 검증된 아카이브로 다시 시도하세요.",
    "The ShuYue backup could not be inspected safely." to "ShuYue 백업을 안전하게 검사할 수 없습니다.",
    "This backup was already imported with a different selection." to "이 백업은 다른 선택 항목으로 이미 가져왔습니다.",
    "The transactional ShuYue import did not complete." to "ShuYue 트랜잭션 가져오기가 완료되지 않았습니다.",
    "No ShuYue secrets were replaced because protected storage failed." to
        "보호된 저장소 오류로 ShuYue 비밀 정보를 교체하지 않았습니다.",
    "The selected file is not an importable ShuYue v1 backup." to "선택한 파일은 가져올 수 있는 ShuYue v1 백업이 아닙니다.",
    "The backup could not be inspected." to "백업을 검사할 수 없습니다.",
    "Validation issue ({0})" to "검증 문제({0})",
    "The backup contains invalid or unsupported data. Review code {0}." to
        "백업에 잘못되었거나 지원되지 않는 데이터가 있습니다. 코드 {0}을 확인하세요.",
    "Not requested" to "요청하지 않음",
    "Rights denied" to "권한 거부",
    "Missing" to "누락",
    "Corrupt" to "손상",
    "Archive limit" to "아카이브 한도",
)

private val FrenchPortabilityTranslations = mapOf(
    "Archive ready" to "Archive prête",
    "Back" to "Retour",
    "Binary export is unavailable until the content-backup service is connected." to
        "L’exportation de l’archive binaire est indisponible tant que le service de sauvegarde du contenu n’est pas connecté.",
    "Books" to "Livres",
    "Chapter bodies are not shown in this report" to "Le texte des chapitres n’apparaît pas dans ce rapport",
    "Choose .shinsou2 archive" to "Choisir une archive .shinsou2",
    "Choose another backup" to "Choisir une autre sauvegarde",
    "Choose backup" to "Choisir une sauvegarde",
    "Content backup v2" to "Sauvegarde du contenu v2",
    "Cookies ({0})" to "Cookies : {0}",
    "Create binary archive" to "Créer l’archive binaire",
    "Create portable archive" to "Créer une archive portable",
    "Credentials ({0})" to "Identifiants ({0})",
    "Credentials, cookies, OAuth tokens, and device keys are always excluded." to
        "Les identifiants, cookies, jetons OAuth et clés d’appareil sont toujours exclus.",
    "Done" to "Terminé",
    "Each body is included only when its rights grant permits export; omissions are recorded in the manifest." to
        "Chaque contenu n’est inclus que si ses droits autorisent l’exportation ; les omissions sont consignées dans le manifeste.",
    "Import from ShuYue" to "Importer depuis ShuYue",
    "Import protected secrets?" to "Importer les secrets protégés ?",
    "Import secrets" to "Importer les secrets",
    "Import selected content" to "Importer le contenu sélectionné",
    "Imported {0} credentials and {1} cookies into protected storage." to
        "{0} identifiants et {1} cookies ont été importés dans le stockage protégé.",
    "Include exportable content bodies" to "Inclure les contenus exportables",
    "Inspecting a bounded copy…" to "Inspection d’une copie limitée…",
    "Leave workspace and restore this device" to "Quitter l’espace de travail et restaurer cet appareil",
    "Moving from ShuYue?" to "Vous venez de ShuYue ?",
    "No backup selected" to "Aucune sauvegarde sélectionnée",
    "Optional secrets" to "Secrets facultatifs",
    "Portable metadata" to "Métadonnées portables",
    "Preview truncated; only all-or-none selection is available" to
        "Aperçu tronqué : seule la sélection complète ou vide est disponible",
    "Protected platform storage is unavailable, so secret import is blocked." to
        "Le stockage protégé de la plateforme est indisponible ; l’importation des secrets est donc bloquée.",
    "Quarantined extension scripts" to "Scripts d’extension en quarantaine",
    "Reading positions ({0})" to "Positions de lecture ({0})",
    "Restore and sync to all devices" to "Restaurer et synchroniser sur tous les appareils",
    "Restore content archive" to "Restaurer une archive de contenu",
    "Restore remains blocked until one shared content transaction and sync-outbox coordinator is connected." to
        "La restauration reste bloquée tant qu’un coordinateur commun des transactions de contenu et de la boîte d’envoi de synchronisation n’est pas connecté.",
    "Restore this archive?" to "Restaurer cette archive ?",
    "Restore verified archive" to "Restaurer l’archive vérifiée",
    "Restored {0} publications, {1} annotations, and {2} content bodies." to
        "{0} publications, {1} annotations et {2} contenus ont été restaurés.",
    "Review and import selected secrets" to "Vérifier et importer les secrets sélectionnés",
    "Review first; scripts stay quarantined and secrets stay excluded" to
        "Vérifiez d’abord : les scripts restent en quarantaine et les secrets sont exclus",
    "Selected content and quarantined scripts were committed transactionally." to
        "Le contenu sélectionné et les scripts en quarantaine ont été validés dans une même transaction.",
    "Selected scripts are stored for later review and are never executed by import" to
        "Les scripts sélectionnés sont conservés pour vérification ultérieure et ne sont jamais exécutés pendant l’importation",
    "ShuYue backup v1" to "Sauvegarde ShuYue v1",
    "The backup was rejected" to "La sauvegarde a été rejetée",
    "The complete container, declared paths, checksums, and portable state are validated before restore is enabled." to
        "Le conteneur complet, les chemins déclarés, les sommes de contrôle et l’état portable sont validés avant d’autoriser la restauration.",
    "The device must leave the workspace before its local state is replaced." to
        "L’appareil doit quitter l’espace de travail avant le remplacement de son état local.",
    "This exact import was already committed; nothing was duplicated." to
        "Cette importation exacte avait déjà été validée ; aucun doublon n’a été créé.",
    "This requires a Ready workspace and durable mutations, body uploads, and outbox records in the shared commit." to
        "Cette action exige un espace de travail prêt ainsi que des mutations durables, des envois de contenu et des entrées de boîte d’envoi dans la validation commune.",
    "This separate action replaces the stored ShuYue migration secret batch. Values remain device-only and cannot be recovered from a portable backup." to
        "Cette action distincte remplace le lot de secrets de migration ShuYue enregistré. Les valeurs restent sur l’appareil et ne peuvent pas être récupérées depuis une sauvegarde portable.",
    "Use the dedicated staged importer for its validation report, book selection, script quarantine, and separate secret consent." to
        "Utilisez l’outil d’importation par étapes pour obtenir le rapport de validation, choisir les livres, mettre les scripts en quarantaine et donner un consentement distinct aux secrets.",
    "Validated contents" to "Contenu validé",
    "Validated staging preview ready" to "Aperçu intermédiaire validé prêt",
    "Validation report" to "Rapport de validation",
    "Values are never shown, backed up, synchronized, or imported automatically." to
        "Les valeurs ne sont jamais affichées, sauvegardées, synchronisées ni importées automatiquement.",
    "Verified portable state and bodies will be committed on this device." to
        "L’état portable et les contenus vérifiés seront validés sur cet appareil.",
    "Verified restore preview" to "Aperçu de restauration vérifié",
    "Version {0} · {1} bytes · digest {2}…" to "Version {0} · {1} octets · empreinte {2}…",
    "Versioned manifest, checksums, and rights-filtered bodies" to
        "Manifeste versionné, sommes de contrôle et contenus filtrés selon les droits",
    "Where should this archive be restored?" to "Où restaurer cette archive ?",
    "{0} books · {1} chapters · {2} reading positions" to "{0} livres · {1} chapitres · {2} positions de lecture",
    "{0} categories · {1} characters of chapter text" to "{0} catégories · {1} caractères de texte de chapitre",
    "{0} chapters · {1}" to "{0} chapitres · {1}",
    "{0} publications · {1} annotations · {2} content bodies" to
        "{0} publications · {1} annotations · {2} contenus",
    "{0} · {1} attached manifests · {2} omitted" to "{0} · {1} manifestes joints · {2} omis",
    "Content backup is unavailable until the shared content storage is connected." to
        "La sauvegarde du contenu est indisponible tant que le stockage partagé du contenu n’est pas connecté.",
    "ShuYue migration is unavailable until the shared content storage is connected." to
        "La migration ShuYue est indisponible tant que le stockage partagé du contenu n’est pas connecté.",
    "The checksummed content archive could not be created." to "L’archive de contenu avec somme de contrôle n’a pas pu être créée.",
    "The selected content archive failed format or checksum validation." to
        "L’archive de contenu sélectionnée a échoué à la validation du format ou de la somme de contrôle.",
    "The selected content archive could not be inspected safely." to "L’archive de contenu sélectionnée n’a pas pu être inspectée en toute sécurité.",
    "The sync-aware content restore did not commit." to "La restauration du contenu avec synchronisation n’a pas été validée.",
    "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive." to
        "L’espace de synchronisation a été quitté, mais la restauration locale a été annulée. Réessayez avec la même archive vérifiée.",
    "The ShuYue backup could not be inspected safely." to "La sauvegarde ShuYue n’a pas pu être inspectée en toute sécurité.",
    "This backup was already imported with a different selection." to "Cette sauvegarde a déjà été importée avec une autre sélection.",
    "The transactional ShuYue import did not complete." to "L’importation transactionnelle ShuYue ne s’est pas terminée.",
    "No ShuYue secrets were replaced because protected storage failed." to
        "Aucun secret ShuYue n’a été remplacé, car le stockage protégé a échoué.",
    "The selected file is not an importable ShuYue v1 backup." to "Le fichier sélectionné n’est pas une sauvegarde ShuYue v1 importable.",
    "The backup could not be inspected." to "La sauvegarde n’a pas pu être inspectée.",
    "Validation issue ({0})" to "Problème de validation ({0})",
    "The backup contains invalid or unsupported data. Review code {0}." to
        "La sauvegarde contient des données invalides ou non prises en charge. Consultez le code {0}.",
    "Not requested" to "Non demandé",
    "Rights denied" to "Refusé par les droits",
    "Missing" to "Manquant",
    "Corrupt" to "Corrompu",
    "Archive limit" to "Limite de l’archive",
)

private val GermanPortabilityTranslations = mapOf(
    "Archive ready" to "Archiv bereit",
    "Back" to "Zurück",
    "Binary export is unavailable until the content-backup service is connected." to
        "Der Export des Binärarchivs ist erst verfügbar, wenn der Inhalts-Backupdienst verbunden ist.",
    "Books" to "Bücher",
    "Chapter bodies are not shown in this report" to "Kapitelinhalte werden in diesem Bericht nicht angezeigt",
    "Choose .shinsou2 archive" to ".shinsou2-Archiv auswählen",
    "Choose another backup" to "Anderes Backup auswählen",
    "Choose backup" to "Backup auswählen",
    "Content backup v2" to "Inhalts-Backup v2",
    "Cookies ({0})" to "Cookies: {0}",
    "Create binary archive" to "Binärarchiv erstellen",
    "Create portable archive" to "Portables Archiv erstellen",
    "Credentials ({0})" to "Anmeldedaten ({0})",
    "Credentials, cookies, OAuth tokens, and device keys are always excluded." to
        "Anmeldedaten, Cookies, OAuth-Token und Geräteschlüssel werden immer ausgeschlossen.",
    "Done" to "Fertig",
    "Each body is included only when its rights grant permits export; omissions are recorded in the manifest." to
        "Ein Inhalt wird nur aufgenommen, wenn seine Rechte den Export erlauben; Auslassungen werden im Manifest protokolliert.",
    "Import from ShuYue" to "Aus ShuYue importieren",
    "Import protected secrets?" to "Geschützte Geheimnisse importieren?",
    "Import secrets" to "Geheimnisse importieren",
    "Import selected content" to "Ausgewählte Inhalte importieren",
    "Imported {0} credentials and {1} cookies into protected storage." to
        "{0} Anmeldedaten und {1} Cookies wurden in den geschützten Speicher importiert.",
    "Include exportable content bodies" to "Exportierbare Inhalte einbeziehen",
    "Inspecting a bounded copy…" to "Begrenzte Kopie wird geprüft…",
    "Leave workspace and restore this device" to "Arbeitsbereich verlassen und dieses Gerät wiederherstellen",
    "Moving from ShuYue?" to "Wechsel von ShuYue?",
    "No backup selected" to "Kein Backup ausgewählt",
    "Optional secrets" to "Optionale Geheimnisse",
    "Portable metadata" to "Portable Metadaten",
    "Preview truncated; only all-or-none selection is available" to
        "Vorschau gekürzt; nur vollständige Auswahl oder Abwahl ist möglich",
    "Protected platform storage is unavailable, so secret import is blocked." to
        "Der geschützte Plattformspeicher ist nicht verfügbar; der Import von Geheimnissen ist daher gesperrt.",
    "Quarantined extension scripts" to "Unter Quarantäne gestellte Erweiterungsskripte",
    "Reading positions ({0})" to "Lesepositionen ({0})",
    "Restore and sync to all devices" to "Wiederherstellen und mit allen Geräten synchronisieren",
    "Restore content archive" to "Inhaltsarchiv wiederherstellen",
    "Restore remains blocked until one shared content transaction and sync-outbox coordinator is connected." to
        "Die Wiederherstellung bleibt gesperrt, bis ein gemeinsamer Koordinator für Inhaltstransaktionen und den Synchronisierungs-Postausgang verbunden ist.",
    "Restore this archive?" to "Dieses Archiv wiederherstellen?",
    "Restore verified archive" to "Geprüftes Archiv wiederherstellen",
    "Restored {0} publications, {1} annotations, and {2} content bodies." to
        "{0} Publikationen, {1} Anmerkungen und {2} Inhalte wurden wiederhergestellt.",
    "Review and import selected secrets" to "Ausgewählte Geheimnisse prüfen und importieren",
    "Review first; scripts stay quarantined and secrets stay excluded" to
        "Zuerst prüfen; Skripte bleiben in Quarantäne und Geheimnisse ausgeschlossen",
    "Selected content and quarantined scripts were committed transactionally." to
        "Ausgewählte Inhalte und quarantänisierte Skripte wurden gemeinsam in einer Transaktion übernommen.",
    "Selected scripts are stored for later review and are never executed by import" to
        "Ausgewählte Skripte werden zur späteren Prüfung gespeichert und beim Import nie ausgeführt",
    "ShuYue backup v1" to "ShuYue-Backup v1",
    "The backup was rejected" to "Das Backup wurde abgelehnt",
    "The complete container, declared paths, checksums, and portable state are validated before restore is enabled." to
        "Vor Freigabe der Wiederherstellung werden der vollständige Container, deklarierte Pfade, Prüfsummen und der portable Zustand validiert.",
    "The device must leave the workspace before its local state is replaced." to
        "Das Gerät muss den Arbeitsbereich verlassen, bevor sein lokaler Zustand ersetzt wird.",
    "This exact import was already committed; nothing was duplicated." to
        "Dieser identische Import wurde bereits übernommen; es wurden keine Duplikate erzeugt.",
    "This requires a Ready workspace and durable mutations, body uploads, and outbox records in the shared commit." to
        "Dafür sind ein bereiter Arbeitsbereich sowie dauerhafte Änderungen, Inhalts-Uploads und Postausgangseinträge im gemeinsamen Commit erforderlich.",
    "This separate action replaces the stored ShuYue migration secret batch. Values remain device-only and cannot be recovered from a portable backup." to
        "Diese separate Aktion ersetzt den gespeicherten Stapel mit ShuYue-Migrationsgeheimnissen. Die Werte bleiben auf diesem Gerät und können nicht aus einem portablen Backup wiederhergestellt werden.",
    "Use the dedicated staged importer for its validation report, book selection, script quarantine, and separate secret consent." to
        "Verwende den speziellen stufenweisen Import für Validierungsbericht, Buchauswahl, Skriptquarantäne und separate Zustimmung zu Geheimnissen.",
    "Validated contents" to "Validierte Inhalte",
    "Validated staging preview ready" to "Validierte Vorbereitungsansicht ist bereit",
    "Validation report" to "Validierungsbericht",
    "Values are never shown, backed up, synchronized, or imported automatically." to
        "Werte werden niemals angezeigt, gesichert, synchronisiert oder automatisch importiert.",
    "Verified portable state and bodies will be committed on this device." to
        "Der geprüfte portable Zustand und die Inhalte werden auf diesem Gerät übernommen.",
    "Verified restore preview" to "Geprüfte Wiederherstellungsvorschau",
    "Version {0} · {1} bytes · digest {2}…" to "Version {0} · {1} Byte · Prüfsumme {2}…",
    "Versioned manifest, checksums, and rights-filtered bodies" to
        "Versioniertes Manifest, Prüfsummen und nach Rechten gefilterte Inhalte",
    "Where should this archive be restored?" to "Wo soll dieses Archiv wiederhergestellt werden?",
    "{0} books · {1} chapters · {2} reading positions" to "{0} Bücher · {1} Kapitel · {2} Lesepositionen",
    "{0} categories · {1} characters of chapter text" to "{0} Kategorien · {1} Zeichen Kapiteltext",
    "{0} chapters · {1}" to "{0} Kapitel · {1}",
    "{0} publications · {1} annotations · {2} content bodies" to
        "{0} Publikationen · {1} Anmerkungen · {2} Inhalte",
    "{0} · {1} attached manifests · {2} omitted" to "{0} · {1} angehängte Manifeste · {2} ausgelassen",
    "Content backup is unavailable until the shared content storage is connected." to
        "Das Inhalts-Backup ist erst verfügbar, wenn der gemeinsame Inhaltsspeicher verbunden ist.",
    "ShuYue migration is unavailable until the shared content storage is connected." to
        "Die ShuYue-Migration ist erst verfügbar, wenn der gemeinsame Inhaltsspeicher verbunden ist.",
    "The checksummed content archive could not be created." to "Das Inhaltsarchiv mit Prüfsumme konnte nicht erstellt werden.",
    "The selected content archive failed format or checksum validation." to
        "Das ausgewählte Inhaltsarchiv hat die Format- oder Prüfsummenprüfung nicht bestanden.",
    "The selected content archive could not be inspected safely." to "Das ausgewählte Inhaltsarchiv konnte nicht sicher geprüft werden.",
    "The sync-aware content restore did not commit." to "Die synchronisierungsfähige Inhaltswiederherstellung wurde nicht übernommen.",
    "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive." to
        "Der Synchronisierungsbereich wurde verlassen, die lokale Wiederherstellung jedoch zurückgesetzt. Versuchen Sie es mit demselben geprüften Archiv erneut.",
    "The ShuYue backup could not be inspected safely." to "Das ShuYue-Backup konnte nicht sicher geprüft werden.",
    "This backup was already imported with a different selection." to "Dieses Backup wurde bereits mit einer anderen Auswahl importiert.",
    "The transactional ShuYue import did not complete." to "Der transaktionale ShuYue-Import wurde nicht abgeschlossen.",
    "No ShuYue secrets were replaced because protected storage failed." to
        "Wegen eines Fehlers im geschützten Speicher wurden keine ShuYue-Geheimnisse ersetzt.",
    "The selected file is not an importable ShuYue v1 backup." to "Die ausgewählte Datei ist kein importierbares ShuYue-v1-Backup.",
    "The backup could not be inspected." to "Das Backup konnte nicht geprüft werden.",
    "Validation issue ({0})" to "Validierungsproblem ({0})",
    "The backup contains invalid or unsupported data. Review code {0}." to
        "Das Backup enthält ungültige oder nicht unterstützte Daten. Prüfe Code {0}.",
    "Not requested" to "Nicht angefordert",
    "Rights denied" to "Durch Rechte verweigert",
    "Missing" to "Fehlt",
    "Corrupt" to "Beschädigt",
    "Archive limit" to "Archivgrenze",
)

private val SpanishPortabilityTranslations = mapOf(
    "Archive ready" to "Archivo listo",
    "Back" to "Atrás",
    "Binary export is unavailable until the content-backup service is connected." to
        "La exportación del archivo binario no estará disponible hasta conectar el servicio de copia de contenido.",
    "Books" to "Libros",
    "Chapter bodies are not shown in this report" to "El texto de los capítulos no se muestra en este informe",
    "Choose .shinsou2 archive" to "Elegir archivo .shinsou2",
    "Choose another backup" to "Elegir otra copia",
    "Choose backup" to "Elegir copia",
    "Content backup v2" to "Copia de contenido v2",
    "Cookies ({0})" to "Cookies: {0}",
    "Create binary archive" to "Crear archivo binario",
    "Create portable archive" to "Crear archivo portátil",
    "Credentials ({0})" to "Credenciales ({0})",
    "Credentials, cookies, OAuth tokens, and device keys are always excluded." to
        "Las credenciales, cookies, tokens OAuth y claves del dispositivo siempre se excluyen.",
    "Done" to "Listo",
    "Each body is included only when its rights grant permits export; omissions are recorded in the manifest." to
        "Cada contenido solo se incluye si sus permisos autorizan la exportación; las omisiones se registran en el manifiesto.",
    "Import from ShuYue" to "Importar desde ShuYue",
    "Import protected secrets?" to "¿Importar secretos protegidos?",
    "Import secrets" to "Importar secretos",
    "Import selected content" to "Importar el contenido seleccionado",
    "Imported {0} credentials and {1} cookies into protected storage." to
        "Se importaron {0} credenciales y {1} cookies en el almacenamiento protegido.",
    "Include exportable content bodies" to "Incluir contenido exportable",
    "Inspecting a bounded copy…" to "Inspeccionando una copia limitada…",
    "Leave workspace and restore this device" to "Salir del espacio de trabajo y restaurar este dispositivo",
    "Moving from ShuYue?" to "¿Vienes de ShuYue?",
    "No backup selected" to "No hay ninguna copia seleccionada",
    "Optional secrets" to "Secretos opcionales",
    "Portable metadata" to "Metadatos portátiles",
    "Preview truncated; only all-or-none selection is available" to
        "Vista previa truncada; solo se puede seleccionar todo o nada",
    "Protected platform storage is unavailable, so secret import is blocked." to
        "El almacenamiento protegido de la plataforma no está disponible, por lo que se bloqueó la importación de secretos.",
    "Quarantined extension scripts" to "Scripts de extensión en cuarentena",
    "Reading positions ({0})" to "Posiciones de lectura ({0})",
    "Restore and sync to all devices" to "Restaurar y sincronizar en todos los dispositivos",
    "Restore content archive" to "Restaurar archivo de contenido",
    "Restore remains blocked until one shared content transaction and sync-outbox coordinator is connected." to
        "La restauración seguirá bloqueada hasta conectar un coordinador compartido de transacciones de contenido y bandeja de salida de sincronización.",
    "Restore this archive?" to "¿Restaurar este archivo?",
    "Restore verified archive" to "Restaurar archivo verificado",
    "Restored {0} publications, {1} annotations, and {2} content bodies." to
        "Se restauraron {0} publicaciones, {1} anotaciones y {2} contenidos.",
    "Review and import selected secrets" to "Revisar e importar los secretos seleccionados",
    "Review first; scripts stay quarantined and secrets stay excluded" to
        "Revisa primero; los scripts siguen en cuarentena y los secretos permanecen excluidos",
    "Selected content and quarantined scripts were committed transactionally." to
        "El contenido seleccionado y los scripts en cuarentena se confirmaron en una misma transacción.",
    "Selected scripts are stored for later review and are never executed by import" to
        "Los scripts seleccionados se guardan para revisarlos después y nunca se ejecutan durante la importación",
    "ShuYue backup v1" to "Copia de ShuYue v1",
    "The backup was rejected" to "La copia fue rechazada",
    "The complete container, declared paths, checksums, and portable state are validated before restore is enabled." to
        "Antes de activar la restauración se validan el contenedor completo, las rutas declaradas, las sumas de comprobación y el estado portátil.",
    "The device must leave the workspace before its local state is replaced." to
        "El dispositivo debe salir del espacio de trabajo antes de reemplazar su estado local.",
    "This exact import was already committed; nothing was duplicated." to
        "Esta importación exacta ya estaba confirmada; no se duplicó nada.",
    "This requires a Ready workspace and durable mutations, body uploads, and outbox records in the shared commit." to
        "Esto requiere un espacio de trabajo listo y cambios duraderos, cargas de contenido y registros de salida dentro de la confirmación compartida.",
    "This separate action replaces the stored ShuYue migration secret batch. Values remain device-only and cannot be recovered from a portable backup." to
        "Esta acción independiente reemplaza el lote guardado de secretos de migración de ShuYue. Los valores permanecen solo en el dispositivo y no pueden recuperarse desde una copia portátil.",
    "Use the dedicated staged importer for its validation report, book selection, script quarantine, and separate secret consent." to
        "Usa el importador por etapas para obtener el informe de validación, seleccionar libros, poner scripts en cuarentena y autorizar los secretos por separado.",
    "Validated contents" to "Contenido validado",
    "Validated staging preview ready" to "Vista previa intermedia validada lista",
    "Validation report" to "Informe de validación",
    "Values are never shown, backed up, synchronized, or imported automatically." to
        "Los valores nunca se muestran, respaldan, sincronizan ni importan automáticamente.",
    "Verified portable state and bodies will be committed on this device." to
        "El estado portátil y el contenido verificados se confirmarán en este dispositivo.",
    "Verified restore preview" to "Vista previa de restauración verificada",
    "Version {0} · {1} bytes · digest {2}…" to "Versión {0} · {1} bytes · resumen {2}…",
    "Versioned manifest, checksums, and rights-filtered bodies" to
        "Manifiesto versionado, sumas de comprobación y contenido filtrado por permisos",
    "Where should this archive be restored?" to "¿Dónde se debe restaurar este archivo?",
    "{0} books · {1} chapters · {2} reading positions" to "{0} libros · {1} capítulos · {2} posiciones de lectura",
    "{0} categories · {1} characters of chapter text" to "{0} categorías · {1} caracteres de texto de capítulos",
    "{0} chapters · {1}" to "{0} capítulos · {1}",
    "{0} publications · {1} annotations · {2} content bodies" to
        "{0} publicaciones · {1} anotaciones · {2} contenidos",
    "{0} · {1} attached manifests · {2} omitted" to "{0} · {1} manifiestos adjuntos · {2} omitidos",
    "Content backup is unavailable until the shared content storage is connected." to
        "La copia de contenido no estará disponible hasta conectar el almacenamiento de contenido compartido.",
    "ShuYue migration is unavailable until the shared content storage is connected." to
        "La migración de ShuYue no estará disponible hasta conectar el almacenamiento de contenido compartido.",
    "The checksummed content archive could not be created." to "No se pudo crear el archivo de contenido con suma de comprobación.",
    "The selected content archive failed format or checksum validation." to
        "El archivo de contenido seleccionado no superó la validación de formato o suma de comprobación.",
    "The selected content archive could not be inspected safely." to "No se pudo inspeccionar de forma segura el archivo de contenido seleccionado.",
    "The sync-aware content restore did not commit." to "La restauración de contenido con sincronización no se confirmó.",
    "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive." to
        "Se abandonó el espacio de sincronización, pero se revirtió la restauración local. Vuelve a intentarlo con el mismo archivo verificado.",
    "The ShuYue backup could not be inspected safely." to "No se pudo inspeccionar de forma segura la copia de ShuYue.",
    "This backup was already imported with a different selection." to "Esta copia ya se importó con una selección diferente.",
    "The transactional ShuYue import did not complete." to "La importación transaccional de ShuYue no se completó.",
    "No ShuYue secrets were replaced because protected storage failed." to
        "No se reemplazó ningún secreto de ShuYue porque falló el almacenamiento protegido.",
    "The selected file is not an importable ShuYue v1 backup." to "El archivo seleccionado no es una copia importable de ShuYue v1.",
    "The backup could not be inspected." to "No se pudo inspeccionar la copia.",
    "Validation issue ({0})" to "Problema de validación ({0})",
    "The backup contains invalid or unsupported data. Review code {0}." to
        "La copia contiene datos no válidos o incompatibles. Revisa el código {0}.",
    "Not requested" to "No solicitado",
    "Rights denied" to "Denegado por permisos",
    "Missing" to "Faltante",
    "Corrupt" to "Dañado",
    "Archive limit" to "Límite del archivo",
)

private val PortuguesePortabilityTranslations = mapOf(
    "Archive ready" to "Arquivo pronto",
    "Back" to "Voltar",
    "Binary export is unavailable until the content-backup service is connected." to
        "A exportação do arquivo binário ficará indisponível até que o serviço de backup de conteúdo seja conectado.",
    "Books" to "Livros",
    "Chapter bodies are not shown in this report" to "O texto dos capítulos não aparece neste relatório",
    "Choose .shinsou2 archive" to "Escolher arquivo .shinsou2",
    "Choose another backup" to "Escolher outro backup",
    "Choose backup" to "Escolher backup",
    "Content backup v2" to "Backup de conteúdo v2",
    "Cookies ({0})" to "Cookies: {0}",
    "Create binary archive" to "Criar arquivo binário",
    "Create portable archive" to "Criar arquivo portátil",
    "Credentials ({0})" to "Credenciais ({0})",
    "Credentials, cookies, OAuth tokens, and device keys are always excluded." to
        "Credenciais, cookies, tokens OAuth e chaves do dispositivo são sempre excluídos.",
    "Done" to "Concluído",
    "Each body is included only when its rights grant permits export; omissions are recorded in the manifest." to
        "Cada conteúdo só é incluído quando suas permissões autorizam a exportação; as omissões são registradas no manifesto.",
    "Import from ShuYue" to "Importar do ShuYue",
    "Import protected secrets?" to "Importar segredos protegidos?",
    "Import secrets" to "Importar segredos",
    "Import selected content" to "Importar conteúdo selecionado",
    "Imported {0} credentials and {1} cookies into protected storage." to
        "Foram importadas {0} credenciais e {1} cookies para o armazenamento protegido.",
    "Include exportable content bodies" to "Incluir conteúdo exportável",
    "Inspecting a bounded copy…" to "Inspecionando uma cópia limitada…",
    "Leave workspace and restore this device" to "Sair do espaço de trabalho e restaurar este dispositivo",
    "Moving from ShuYue?" to "Migrando do ShuYue?",
    "No backup selected" to "Nenhum backup selecionado",
    "Optional secrets" to "Segredos opcionais",
    "Portable metadata" to "Metadados portáteis",
    "Preview truncated; only all-or-none selection is available" to
        "Prévia truncada; somente a seleção total ou vazia está disponível",
    "Protected platform storage is unavailable, so secret import is blocked." to
        "O armazenamento protegido da plataforma está indisponível; por isso, a importação de segredos foi bloqueada.",
    "Quarantined extension scripts" to "Scripts de extensão em quarentena",
    "Reading positions ({0})" to "Posições de leitura ({0})",
    "Restore and sync to all devices" to "Restaurar e sincronizar em todos os dispositivos",
    "Restore content archive" to "Restaurar arquivo de conteúdo",
    "Restore remains blocked until one shared content transaction and sync-outbox coordinator is connected." to
        "A restauração continuará bloqueada até que um coordenador compartilhado de transações de conteúdo e caixa de saída de sincronização seja conectado.",
    "Restore this archive?" to "Restaurar este arquivo?",
    "Restore verified archive" to "Restaurar arquivo verificado",
    "Restored {0} publications, {1} annotations, and {2} content bodies." to
        "Foram restauradas {0} publicações, {1} anotações e {2} conteúdos.",
    "Review and import selected secrets" to "Revisar e importar os segredos selecionados",
    "Review first; scripts stay quarantined and secrets stay excluded" to
        "Revise primeiro; os scripts permanecem em quarentena e os segredos continuam excluídos",
    "Selected content and quarantined scripts were committed transactionally." to
        "O conteúdo selecionado e os scripts em quarentena foram confirmados em uma única transação.",
    "Selected scripts are stored for later review and are never executed by import" to
        "Os scripts selecionados são armazenados para revisão posterior e nunca são executados durante a importação",
    "ShuYue backup v1" to "Backup do ShuYue v1",
    "The backup was rejected" to "O backup foi rejeitado",
    "The complete container, declared paths, checksums, and portable state are validated before restore is enabled." to
        "O contêiner completo, os caminhos declarados, as somas de verificação e o estado portátil são validados antes de ativar a restauração.",
    "The device must leave the workspace before its local state is replaced." to
        "O dispositivo precisa sair do espaço de trabalho antes que seu estado local seja substituído.",
    "This exact import was already committed; nothing was duplicated." to
        "Esta importação exata já havia sido confirmada; nenhum item foi duplicado.",
    "This requires a Ready workspace and durable mutations, body uploads, and outbox records in the shared commit." to
        "Isso exige um espaço de trabalho pronto e alterações duráveis, uploads de conteúdo e registros da caixa de saída na confirmação compartilhada.",
    "This separate action replaces the stored ShuYue migration secret batch. Values remain device-only and cannot be recovered from a portable backup." to
        "Esta ação separada substitui o lote salvo de segredos da migração do ShuYue. Os valores permanecem somente no dispositivo e não podem ser recuperados de um backup portátil.",
    "Use the dedicated staged importer for its validation report, book selection, script quarantine, and separate secret consent." to
        "Use o importador em etapas para obter o relatório de validação, selecionar livros, colocar scripts em quarentena e autorizar segredos separadamente.",
    "Validated contents" to "Conteúdo validado",
    "Validated staging preview ready" to "Prévia intermediária validada pronta",
    "Validation report" to "Relatório de validação",
    "Values are never shown, backed up, synchronized, or imported automatically." to
        "Os valores nunca são exibidos, incluídos em backup, sincronizados ou importados automaticamente.",
    "Verified portable state and bodies will be committed on this device." to
        "O estado portátil e o conteúdo verificados serão confirmados neste dispositivo.",
    "Verified restore preview" to "Prévia de restauração verificada",
    "Version {0} · {1} bytes · digest {2}…" to "Versão {0} · {1} bytes · resumo {2}…",
    "Versioned manifest, checksums, and rights-filtered bodies" to
        "Manifesto versionado, somas de verificação e conteúdo filtrado por permissões",
    "Where should this archive be restored?" to "Onde este arquivo deve ser restaurado?",
    "{0} books · {1} chapters · {2} reading positions" to "{0} livros · {1} capítulos · {2} posições de leitura",
    "{0} categories · {1} characters of chapter text" to "{0} categorias · {1} caracteres de texto de capítulos",
    "{0} chapters · {1}" to "{0} capítulos · {1}",
    "{0} publications · {1} annotations · {2} content bodies" to
        "{0} publicações · {1} anotações · {2} conteúdos",
    "{0} · {1} attached manifests · {2} omitted" to "{0} · {1} manifestos anexados · {2} omitidos",
    "Content backup is unavailable until the shared content storage is connected." to
        "O backup de conteúdo ficará indisponível até que o armazenamento compartilhado de conteúdo seja conectado.",
    "ShuYue migration is unavailable until the shared content storage is connected." to
        "A migração do ShuYue ficará indisponível até que o armazenamento compartilhado de conteúdo seja conectado.",
    "The checksummed content archive could not be created." to "Não foi possível criar o arquivo de conteúdo com soma de verificação.",
    "The selected content archive failed format or checksum validation." to
        "O arquivo de conteúdo selecionado falhou na validação de formato ou soma de verificação.",
    "The selected content archive could not be inspected safely." to "Não foi possível inspecionar com segurança o arquivo de conteúdo selecionado.",
    "The sync-aware content restore did not commit." to "A restauração de conteúdo com sincronização não foi confirmada.",
    "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive." to
        "O espaço de sincronização foi abandonado, mas a restauração local foi revertida. Tente novamente com o mesmo arquivo verificado.",
    "The ShuYue backup could not be inspected safely." to "Não foi possível inspecionar com segurança o backup do ShuYue.",
    "This backup was already imported with a different selection." to "Este backup já foi importado com outra seleção.",
    "The transactional ShuYue import did not complete." to "A importação transacional do ShuYue não foi concluída.",
    "No ShuYue secrets were replaced because protected storage failed." to
        "Nenhum segredo do ShuYue foi substituído porque o armazenamento protegido falhou.",
    "The selected file is not an importable ShuYue v1 backup." to "O arquivo selecionado não é um backup importável do ShuYue v1.",
    "The backup could not be inspected." to "Não foi possível inspecionar o backup.",
    "Validation issue ({0})" to "Problema de validação ({0})",
    "The backup contains invalid or unsupported data. Review code {0}." to
        "O backup contém dados inválidos ou não compatíveis. Revise o código {0}.",
    "Not requested" to "Não solicitado",
    "Rights denied" to "Negado pelas permissões",
    "Missing" to "Ausente",
    "Corrupt" to "Corrompido",
    "Archive limit" to "Limite do arquivo",
)

private val TraditionalReviewedExtensionTranslations = mapOf(
    "Approve reviewed extension" to "核准已審核的擴充套件",
    "SHA-256: {0}" to "SHA-256：{0}",
    "Required permissions" to "必要權限",
    "The script remains blocked until you approve this exact version and digest." to
        "在你核准這個確切版本與摘要前，腳本會持續被阻擋。",
    "Exact reviewed permissions granted" to "已授予精確審核的權限",
    "Downloaded ShuYue artifact is not an exact reviewed version" to
        "下載的 ShuYue 成品不是完全相符的已審核版本",
    "Stored ShuYue quarantine could not be decoded safely." to
        "已儲存的 ShuYue 隔離資料已損壞或不相容，無法安全讀取。",
    "Execution blocked" to "已阻擋執行",
    "Execute reviewed script" to "執行已審核腳本",
    "Network access" to "網路存取",
    "Cookie storage" to "Cookie 儲存",
    "Credential access" to "登入憑證存取",
    "Show login prompt" to "顯示登入提示",
    "Modify favorites" to "修改收藏",
    "Open browser challenge" to "開啟瀏覽器驗證",
)

private val SimplifiedReviewedExtensionTranslations =
    TraditionalReviewedExtensionTranslations.mapValues { (_, value) -> value.toSimplifiedChinese() } + mapOf(
        "Approve reviewed extension" to "批准已审核的扩展",
        "Exact reviewed permissions granted" to "已授予精确审核的权限",
    )

private val JapaneseReviewedExtensionTranslations = mapOf(
    "Approve reviewed extension" to "レビュー済み拡張機能を承認",
    "SHA-256: {0}" to "SHA-256：{0}",
    "Required permissions" to "必要な権限",
    "The script remains blocked until you approve this exact version and digest." to
        "この正確なバージョンとダイジェストを承認するまで、スクリプトはブロックされたままです。",
    "Exact reviewed permissions granted" to "レビュー済みの権限を付与済み",
    "Downloaded ShuYue artifact is not an exact reviewed version" to
        "ダウンロードした ShuYue 成果物は、レビュー済みの正確なバージョンではありません",
    "Stored ShuYue quarantine could not be decoded safely." to
        "保存済みの ShuYue 隔離データが破損しているか互換性がないため、安全に読み込めません。",
    "Execution blocked" to "実行をブロック中",
    "Execute reviewed script" to "レビュー済みスクリプトを実行",
    "Network access" to "ネットワークアクセス",
    "Cookie storage" to "Cookie ストレージ",
    "Credential access" to "認証情報へのアクセス",
    "Show login prompt" to "ログイン画面を表示",
    "Modify favorites" to "お気に入りを変更",
    "Open browser challenge" to "ブラウザー認証を開く",
)

private val KoreanReviewedExtensionTranslations = mapOf(
    "Approve reviewed extension" to "검토된 확장 승인",
    "SHA-256: {0}" to "SHA-256: {0}",
    "Required permissions" to "필수 권한",
    "The script remains blocked until you approve this exact version and digest." to
        "이 정확한 버전과 다이제스트를 승인할 때까지 스크립트 실행이 차단됩니다.",
    "Exact reviewed permissions granted" to "검토된 정확한 권한 부여됨",
    "Downloaded ShuYue artifact is not an exact reviewed version" to
        "다운로드한 ShuYue 결과물이 검토된 정확한 버전이 아닙니다",
    "Stored ShuYue quarantine could not be decoded safely." to
        "저장된 ShuYue 격리 데이터가 손상되었거나 호환되지 않아 안전하게 읽을 수 없습니다.",
    "Execution blocked" to "실행 차단됨",
    "Execute reviewed script" to "검토된 스크립트 실행",
    "Network access" to "네트워크 접근",
    "Cookie storage" to "쿠키 저장소",
    "Credential access" to "자격 증명 접근",
    "Show login prompt" to "로그인 화면 표시",
    "Modify favorites" to "즐겨찾기 변경",
    "Open browser challenge" to "브라우저 인증 열기",
)

private val FrenchReviewedExtensionTranslations = mapOf(
    "Approve reviewed extension" to "Approuver l’extension vérifiée",
    "SHA-256: {0}" to "SHA-256 : {0}",
    "Required permissions" to "Autorisations requises",
    "The script remains blocked until you approve this exact version and digest." to
        "Le script reste bloqué tant que vous n’approuvez pas cette version et cette empreinte exactes.",
    "Exact reviewed permissions granted" to "Autorisations vérifiées accordées",
    "Downloaded ShuYue artifact is not an exact reviewed version" to
        "L’artefact ShuYue téléchargé ne correspond pas exactement à une version vérifiée",
    "Stored ShuYue quarantine could not be decoded safely." to
        "Les données de quarantaine ShuYue sont endommagées ou incompatibles et ne peuvent pas être lues en toute sécurité.",
    "Execution blocked" to "Exécution bloquée",
    "Execute reviewed script" to "Exécuter le script vérifié",
    "Network access" to "Accès réseau",
    "Cookie storage" to "Stockage des cookies",
    "Credential access" to "Accès aux identifiants",
    "Show login prompt" to "Afficher la connexion",
    "Modify favorites" to "Modifier les favoris",
    "Open browser challenge" to "Ouvrir la vérification du navigateur",
)

private val GermanReviewedExtensionTranslations = mapOf(
    "Approve reviewed extension" to "Geprüfte Erweiterung genehmigen",
    "SHA-256: {0}" to "SHA-256: {0}",
    "Required permissions" to "Erforderliche Berechtigungen",
    "The script remains blocked until you approve this exact version and digest." to
        "Das Skript bleibt gesperrt, bis du genau diese Version und Prüfsumme genehmigst.",
    "Exact reviewed permissions granted" to "Geprüfte Berechtigungen erteilt",
    "Downloaded ShuYue artifact is not an exact reviewed version" to
        "Das geladene ShuYue-Artefakt entspricht keiner exakt geprüften Version",
    "Stored ShuYue quarantine could not be decoded safely." to
        "Die gespeicherten ShuYue-Quarantänedaten sind beschädigt oder inkompatibel und können nicht sicher gelesen werden.",
    "Execution blocked" to "Ausführung blockiert",
    "Execute reviewed script" to "Geprüftes Skript ausführen",
    "Network access" to "Netzwerkzugriff",
    "Cookie storage" to "Cookie-Speicher",
    "Credential access" to "Zugriff auf Anmeldedaten",
    "Show login prompt" to "Anmeldedialog anzeigen",
    "Modify favorites" to "Favoriten ändern",
    "Open browser challenge" to "Browser-Verifizierung öffnen",
)

private val SpanishReviewedExtensionTranslations = mapOf(
    "Approve reviewed extension" to "Aprobar extensión revisada",
    "SHA-256: {0}" to "SHA-256: {0}",
    "Required permissions" to "Permisos requeridos",
    "The script remains blocked until you approve this exact version and digest." to
        "El script seguirá bloqueado hasta que apruebes esta versión y este resumen exactos.",
    "Exact reviewed permissions granted" to "Permisos revisados concedidos",
    "Downloaded ShuYue artifact is not an exact reviewed version" to
        "El artefacto de ShuYue descargado no coincide con una versión revisada exacta",
    "Stored ShuYue quarantine could not be decoded safely." to
        "Los datos de cuarentena de ShuYue están dañados o son incompatibles y no se pueden leer de forma segura.",
    "Execution blocked" to "Ejecución bloqueada",
    "Execute reviewed script" to "Ejecutar script revisado",
    "Network access" to "Acceso a la red",
    "Cookie storage" to "Almacenamiento de cookies",
    "Credential access" to "Acceso a credenciales",
    "Show login prompt" to "Mostrar inicio de sesión",
    "Modify favorites" to "Modificar favoritos",
    "Open browser challenge" to "Abrir verificación del navegador",
)

private val PortugueseReviewedExtensionTranslations = mapOf(
    "Approve reviewed extension" to "Aprovar extensão revisada",
    "SHA-256: {0}" to "SHA-256: {0}",
    "Required permissions" to "Permissões necessárias",
    "The script remains blocked until you approve this exact version and digest." to
        "O script continuará bloqueado até você aprovar esta versão e este resumo exatos.",
    "Exact reviewed permissions granted" to "Permissões revisadas concedidas",
    "Downloaded ShuYue artifact is not an exact reviewed version" to
        "O artefato ShuYue baixado não corresponde a uma versão revisada exata",
    "Stored ShuYue quarantine could not be decoded safely." to
        "Os dados de quarentena do ShuYue estão corrompidos ou incompatíveis e não podem ser lidos com segurança.",
    "Execution blocked" to "Execução bloqueada",
    "Execute reviewed script" to "Executar script revisado",
    "Network access" to "Acesso à rede",
    "Cookie storage" to "Armazenamento de cookies",
    "Credential access" to "Acesso às credenciais",
    "Show login prompt" to "Mostrar tela de login",
    "Modify favorites" to "Modificar favoritos",
    "Open browser challenge" to "Abrir verificação do navegador",
)

private val TraditionalChineseStrings = EnglishStrings.copy(
    library = "書庫", updates = "更新", history = "歷史", browse = "瀏覽", more = "更多",
    sources = "來源", extensions = "擴充套件", migration = "遷移", downloads = "下載",
    statistics = "統計", settings = "設定", backup = "備份與還原", about = "關於",
    search = "搜尋", searchLibrary = "搜尋書庫", refresh = "重新整理", filter = "篩選", sort = "排序",
    all = "全部", done = "完成", cancel = "取消", save = "儲存", close = "關閉", delete = "刪除",
    remove = "移除", retry = "重試", share = "分享", selectAll = "全選", selected = "項已選取",
    markRead = "標為已讀", markUnread = "標為未讀", moveToCategory = "移至分類",
    continueReading = "繼續閱讀", favorite = "加入書庫", unfavorite = "已在書庫", myLibrary = "我的收藏庫", download = "下載",
    install = "安裝", uninstall = "解除安裝", enable = "啟用", disable = "停用", chapters = "章節",
    readerSettings = "閱讀器設定", previousChapter = "上一章", nextChapter = "下一章", page = "頁",
    readerModeLeftToRight = "翻頁（左至右）", readerModeRightToLeft = "翻頁（右至左）",
    readerModeVertical = "垂直翻頁", readerModeWebtoon = "條漫", readerModeContinuousVertical = "連續垂直",
    pageTurnAnimation = "翻頁動畫", pageTurnAnimationDescription = "切換頁面時顯示動畫",
    clearFilters = "清除篩選", noMatches = "沒有符合項目", libraryEmpty = "書庫是空的",
    noUpdates = "沒有最近更新", noHistory = "沒有閱讀歷史", createBackup = "建立備份", restoreBackup = "還原備份",
    translations = TraditionalLongTranslations + LibraryContentTypeTranslations.getValue("zh-TW") + TraditionalReaderTranslations + TraditionalPortabilityTranslations +
        TraditionalReviewedExtensionTranslations,
)

private val SimplifiedChineseStrings = TraditionalChineseStrings.copy(
    library = "书库", updates = "更新", history = "历史", browse = "浏览", more = "更多",
    sources = "来源", extensions = "扩展", migration = "迁移", downloads = "下载", statistics = "统计",
    settings = "设置", backup = "备份与恢复", about = "关于", search = "搜索", searchLibrary = "搜索书库",
    refresh = "刷新", filter = "筛选", sort = "排序", all = "全部", done = "完成", cancel = "取消",
    save = "保存", close = "关闭", delete = "删除", remove = "移除", retry = "重试", share = "分享",
    selectAll = "全选", selected = "项已选择", markRead = "标为已读", markUnread = "标为未读",
    moveToCategory = "移至分类", continueReading = "继续阅读", favorite = "加入书库", unfavorite = "已在书库", myLibrary = "我的收藏库",
    download = "下载", install = "安装", uninstall = "卸载", enable = "启用", disable = "停用",
    chapters = "章节", readerSettings = "阅读器设置", previousChapter = "上一章", nextChapter = "下一章",
    page = "页", clearFilters = "清除筛选", noMatches = "没有匹配项", libraryEmpty = "书库为空",
    readerModeLeftToRight = "翻页（从左到右）", readerModeRightToLeft = "翻页（从右到左）",
    readerModeVertical = "垂直翻页", readerModeWebtoon = "条漫", readerModeContinuousVertical = "连续垂直",
    pageTurnAnimation = "翻页动画", pageTurnAnimationDescription = "切换页面时显示动画",
    noUpdates = "没有最近更新", noHistory = "没有阅读历史", createBackup = "创建备份", restoreBackup = "恢复备份",
    // Explicit Simplified wording wins over the character-converted Traditional fallback.
    translations = SimplifiedSyncTranslations + SimplifiedLongTranslations + LibraryContentTypeTranslations.getValue("zh-CN") + SimplifiedReaderTranslations +
        SimplifiedPortabilityTranslations + SimplifiedReviewedExtensionTranslations,
)

private val JapaneseStrings = EnglishStrings.copy(
    library = "ライブラリ", updates = "更新", history = "履歴", browse = "ブラウズ", more = "その他",
    sources = "ソース", extensions = "拡張機能", migration = "移行", downloads = "ダウンロード",
    statistics = "統計", settings = "設定", backup = "バックアップと復元", about = "このアプリについて",
    search = "検索", searchLibrary = "ライブラリを検索", refresh = "更新", filter = "絞り込み", sort = "並べ替え",
    all = "すべて", done = "完了", cancel = "キャンセル", save = "保存", close = "閉じる", delete = "削除",
    remove = "削除", retry = "再試行", share = "共有", selectAll = "すべて選択", selected = "件を選択中",
    markRead = "既読にする", markUnread = "未読にする", moveToCategory = "カテゴリへ移動",
    continueReading = "続きを読む", favorite = "ライブラリに追加", unfavorite = "ライブラリ内", myLibrary = "マイライブラリ", download = "ダウンロード",
    install = "インストール", uninstall = "アンインストール", enable = "有効", disable = "無効",
    chapters = "章", readerSettings = "リーダー設定", previousChapter = "前の章", nextChapter = "次の章",
    page = "ページ", clearFilters = "絞り込みを解除", noMatches = "一致する項目なし", libraryEmpty = "ライブラリは空です",
    readerModeLeftToRight = "ページ送り（左から右）", readerModeRightToLeft = "ページ送り（右から左）",
    readerModeVertical = "縦方向のページ送り", readerModeWebtoon = "ウェブトゥーン",
    readerModeContinuousVertical = "縦スクロール", pageTurnAnimation = "ページ切り替えアニメーション",
    pageTurnAnimationDescription = "ページを切り替えるときにアニメーションを表示",
    noUpdates = "最近の更新はありません", noHistory = "閲覧履歴はありません", createBackup = "バックアップを作成",
    restoreBackup = "バックアップを復元",
    translations = JapaneseSyncTranslations + LibraryContentTypeTranslations.getValue("ja") + JapaneseReaderTranslations + JapanesePortabilityTranslations +
        JapaneseReviewedExtensionTranslations,
)

private val KoreanStrings = EnglishStrings.copy(
    library = "라이브러리", updates = "업데이트", history = "기록", browse = "탐색", more = "더보기",
    sources = "소스", extensions = "확장", migration = "마이그레이션", downloads = "다운로드",
    statistics = "통계", settings = "설정", backup = "백업 및 복원", about = "정보", search = "검색",
    searchLibrary = "라이브러리 검색", refresh = "새로 고침", filter = "필터", sort = "정렬", all = "전체",
    done = "완료", cancel = "취소", save = "저장", close = "닫기", delete = "삭제", remove = "제거",
    retry = "다시 시도", share = "공유", selectAll = "전체 선택", selected = "개 선택됨", markRead = "읽음으로 표시",
    markUnread = "읽지 않음으로 표시", moveToCategory = "카테고리로 이동", continueReading = "계속 읽기",
    favorite = "라이브러리에 추가", unfavorite = "라이브러리에 있음", myLibrary = "내 라이브러리", download = "다운로드", install = "설치",
    uninstall = "제거", enable = "사용", disable = "사용 안 함", chapters = "챕터", readerSettings = "리더 설정",
    previousChapter = "이전 챕터", nextChapter = "다음 챕터", page = "페이지", clearFilters = "필터 지우기",
    readerModeLeftToRight = "페이지 넘김(왼쪽에서 오른쪽)", readerModeRightToLeft = "페이지 넘김(오른쪽에서 왼쪽)",
    readerModeVertical = "세로 페이지 넘김", readerModeWebtoon = "웹툰", readerModeContinuousVertical = "연속 세로 스크롤",
    pageTurnAnimation = "페이지 전환 애니메이션", pageTurnAnimationDescription = "페이지를 전환할 때 애니메이션 표시",
    noMatches = "검색 결과 없음", libraryEmpty = "라이브러리가 비어 있습니다", noUpdates = "최근 업데이트 없음",
    noHistory = "읽기 기록 없음", createBackup = "백업 만들기", restoreBackup = "백업 복원",
    translations = KoreanSyncTranslations + LibraryContentTypeTranslations.getValue("ko") + KoreanReaderTranslations + KoreanPortabilityTranslations +
        KoreanReviewedExtensionTranslations,
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
    unfavorite = "Dans la bibliothèque", myLibrary = "Ma bibliothèque", download = "Télécharger", install = "Installer", uninstall = "Désinstaller",
    enable = "Activer", disable = "Désactiver", chapters = "Chapitres", readerSettings = "Réglages du lecteur",
    previousChapter = "Chapitre précédent", nextChapter = "Chapitre suivant", page = "Page", clearFilters = "Effacer les filtres",
    readerModeLeftToRight = "Pages · de gauche à droite", readerModeRightToLeft = "Pages · de droite à gauche",
    readerModeVertical = "Pagination verticale", readerModeWebtoon = "Webtoon",
    readerModeContinuousVertical = "Défilement vertical continu", pageTurnAnimation = "Animation de changement de page",
    pageTurnAnimationDescription = "Animer la transition entre les pages",
    noMatches = "Aucun résultat", libraryEmpty = "Votre bibliothèque est vide", noUpdates = "Aucune mise à jour récente",
    noHistory = "Aucun historique", createBackup = "Créer une sauvegarde", restoreBackup = "Restaurer une sauvegarde",
    translations = FrenchSyncTranslations + LibraryContentTypeTranslations.getValue("fr") + FrenchReaderTranslations + FrenchPortabilityTranslations +
        FrenchReviewedExtensionTranslations,
)

private val GermanStrings = EnglishStrings.copy(
    library = "Bibliothek", updates = "Updates", history = "Verlauf", browse = "Stöbern", more = "Mehr",
    sources = "Quellen", extensions = "Erweiterungen", migration = "Migration", downloads = "Downloads",
    statistics = "Statistik", settings = "Einstellungen", backup = "Sichern & Wiederherstellen", about = "Über",
    search = "Suchen", searchLibrary = "Bibliothek durchsuchen", refresh = "Aktualisieren", filter = "Filtern", sort = "Sortieren",
    all = "Alle", done = "Fertig", cancel = "Abbrechen", save = "Sichern", close = "Schließen", delete = "Löschen",
    remove = "Entfernen", retry = "Wiederholen", share = "Teilen", selectAll = "Alle auswählen", selected = "ausgewählt",
    markRead = "Als gelesen markieren", markUnread = "Als ungelesen markieren", moveToCategory = "In Kategorie verschieben",
    continueReading = "Weiterlesen", favorite = "Zur Bibliothek hinzufügen", unfavorite = "In Bibliothek", myLibrary = "Meine Bibliothek", download = "Herunterladen",
    install = "Installieren", uninstall = "Deinstallieren", enable = "Aktivieren", disable = "Deaktivieren", chapters = "Kapitel",
    readerSettings = "Reader-Einstellungen", previousChapter = "Vorheriges Kapitel", nextChapter = "Nächstes Kapitel",
    page = "Seite", clearFilters = "Filter löschen", noMatches = "Keine Treffer", libraryEmpty = "Deine Bibliothek ist leer",
    readerModeLeftToRight = "Seiten · von links nach rechts", readerModeRightToLeft = "Seiten · von rechts nach links",
    readerModeVertical = "Vertikales Blättern", readerModeWebtoon = "Webtoon",
    readerModeContinuousVertical = "Fortlaufend vertikal", pageTurnAnimation = "Seitenwechsel animieren",
    pageTurnAnimationDescription = "Übergänge zwischen Seiten animieren",
    noUpdates = "Keine neuen Updates", noHistory = "Kein Leseverlauf", createBackup = "Backup erstellen",
    restoreBackup = "Backup wiederherstellen",
    translations = GermanSyncTranslations + GermanICloudSyncTranslations + LibraryContentTypeTranslations.getValue("de") + GermanReaderTranslations +
        GermanPortabilityTranslations + GermanReviewedExtensionTranslations,
)

private val SpanishStrings = EnglishStrings.copy(
    library = "Biblioteca", updates = "Actualizaciones", history = "Historial", browse = "Explorar", more = "Más",
    sources = "Fuentes", extensions = "Extensiones", migration = "Migración", downloads = "Descargas",
    statistics = "Estadísticas", settings = "Ajustes", backup = "Copia y restauración", about = "Acerca de",
    search = "Buscar", searchLibrary = "Buscar en la biblioteca", refresh = "Actualizar", filter = "Filtrar", sort = "Ordenar",
    all = "Todo", done = "Listo", cancel = "Cancelar", save = "Guardar", close = "Cerrar", delete = "Eliminar",
    remove = "Quitar", retry = "Reintentar", share = "Compartir", selectAll = "Seleccionar todo", selected = "seleccionado(s)",
    markRead = "Marcar como leído", markUnread = "Marcar como no leído", moveToCategory = "Mover a categoría",
    continueReading = "Continuar leyendo", favorite = "Añadir a la biblioteca", unfavorite = "En la biblioteca", myLibrary = "Mi biblioteca", download = "Descargar",
    install = "Instalar", uninstall = "Desinstalar", enable = "Activar", disable = "Desactivar", chapters = "Capítulos",
    readerSettings = "Ajustes del lector", previousChapter = "Capítulo anterior", nextChapter = "Capítulo siguiente",
    page = "Página", clearFilters = "Borrar filtros", noMatches = "Sin resultados", libraryEmpty = "Tu biblioteca está vacía",
    readerModeLeftToRight = "Páginas · de izquierda a derecha", readerModeRightToLeft = "Páginas · de derecha a izquierda",
    readerModeVertical = "Paginación vertical", readerModeWebtoon = "Webtoon",
    readerModeContinuousVertical = "Desplazamiento vertical continuo", pageTurnAnimation = "Animación al pasar página",
    pageTurnAnimationDescription = "Animar la transición entre páginas",
    noUpdates = "No hay actualizaciones recientes", noHistory = "No hay historial de lectura", createBackup = "Crear copia",
    restoreBackup = "Restaurar copia",
    translations = SpanishSyncTranslations + SpanishICloudSyncTranslations + LibraryContentTypeTranslations.getValue("es") + SpanishReaderTranslations +
        SpanishPortabilityTranslations + SpanishReviewedExtensionTranslations,
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
    unfavorite = "Na biblioteca", myLibrary = "Minha biblioteca", download = "Baixar", install = "Instalar", uninstall = "Desinstalar", enable = "Ativar",
    disable = "Desativar", chapters = "Capítulos", readerSettings = "Configurações do leitor", previousChapter = "Capítulo anterior",
    nextChapter = "Próximo capítulo", page = "Página", clearFilters = "Limpar filtros", noMatches = "Nenhum resultado",
    readerModeLeftToRight = "Páginas · da esquerda para a direita", readerModeRightToLeft = "Páginas · da direita para a esquerda",
    readerModeVertical = "Paginação vertical", readerModeWebtoon = "Webtoon",
    readerModeContinuousVertical = "Rolagem vertical contínua", pageTurnAnimation = "Animação ao virar página",
    pageTurnAnimationDescription = "Animar a transição entre páginas",
    libraryEmpty = "Sua biblioteca está vazia", noUpdates = "Nenhuma atualização recente", noHistory = "Nenhum histórico de leitura",
    createBackup = "Criar backup", restoreBackup = "Restaurar backup",
    translations = PortugueseSyncTranslations + PortugueseICloudSyncTranslations + LibraryContentTypeTranslations.getValue("pt") + PortugueseReaderTranslations +
        PortuguesePortabilityTranslations + PortugueseReviewedExtensionTranslations,
)
