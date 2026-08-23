package dev.shinsou.kmp.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShinsouStringsTest {
    @Test
    fun localeTagsKeepTraditionalAndSimplifiedChineseSeparate() {
        assertEquals("書庫", shinsouStringsFor("zh-TW").library)
        assertEquals("書庫", shinsouStringsFor("zh-Hant").library)
        assertEquals("書庫", shinsouStringsFor("zh-HK").library)
        assertEquals("书库", shinsouStringsFor("zh-CN").library)
        assertEquals("书库", shinsouStringsFor("zh-Hans").library)
        assertEquals("书库", shinsouStringsFor("zh-SG").library)
    }

    @Test
    fun bareChineseTagUsesTheStableTraditionalFallback() {
        assertEquals("書庫", shinsouStringsFor("zh").library)
        assertEquals("書庫", shinsouStringsFor("ZH").library)
    }

    @Test
    fun nonChineseLocalesHaveAStableEnglishFallback() {
        assertEquals("Library", shinsouStringsFor("en-US").library)
        assertEquals("Library", shinsouStringsFor("xx-YY").library)
    }

    @Test
    fun longTailStringsAreLocalizedAndInterpolated() {
        val traditional = shinsouStringsFor("zh-TW")
        val simplified = shinsouStringsFor("zh-CN")

        assertEquals("沒有章節", traditional.text("No chapters"))
        assertEquals("没有章节", simplified.text("No chapters"))
        assertEquals("已閱讀 3/12 章 · 25%", traditional.text("{0} of {1} chapters read · {2}%", 3, 12, 25))
        assertEquals("已阅读 3/12 章 · 25%", simplified.text("{0} of {1} chapters read · {2}%", 3, 12, 25))
    }

    @Test
    fun coreLabelsResolveThroughTextForEverySupportedEastAsianLocale() {
        assertEquals("設定", shinsouStringsFor("ja-JP").text("Settings"))
        assertEquals("설정", shinsouStringsFor("ko-KR").text("Settings"))
        assertEquals("设置", shinsouStringsFor("zh-CN").text("Settings"))
        assertEquals("設定", shinsouStringsFor("zh-TW").text("Settings"))
    }

    @Test
    fun remoteSourceCollectionLabelIsLocalized() {
        assertEquals("我的收藏庫", shinsouStringsFor("zh-TW").text("My library"))
        assertEquals("我的收藏库", shinsouStringsFor("zh-CN").text("My library"))
        assertEquals("マイライブラリ", shinsouStringsFor("ja-JP").text("My library"))
        assertEquals("My library", shinsouStringsFor("en-US").text("My library"))
    }

    @Test
    fun platformSecurityMessagesFollowTheSelectedChineseLocale() {
        val key = "Set up a device passcode, PIN, password, or biometric authentication to use app lock."
        assertEquals(
            "請設定裝置密碼、PIN、密碼或生物辨識驗證，才能使用應用程式鎖。",
            shinsouStringsFor("zh-TW").text(key),
        )
        assertEquals(
            "请设置设备密码、PIN、密码或生物识别验证，才能使用应用锁。",
            shinsouStringsFor("zh-CN").text(key),
        )
    }

    @Test
    fun syncScopeDisclosureIsLocalizedBeforeWorkspaceSetup() {
        val key = "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized."
        assertEquals(
            "下載內容、本機來源檔案、擴充套件安裝包、Cookie、密碼與 API 金鑰不會同步。",
            shinsouStringsFor("zh-TW").text(key),
        )
        assertEquals(
            "下载内容、本机来源文件、扩展安装包、Cookie、密码和 API 密钥不同步。",
            shinsouStringsFor("zh-CN").text(key),
        )
    }

    @Test
    fun syncSettingsPanelDoesNotFallBackToEnglish() {
        val keys = listOf(
            "Cloudflare encrypted sync",
            "Deploying",
            "Setup, invite, pairing or emergency handoff link / code",
            "Connect",
            "Paste",
            "Scan QR",
            "Create sync service",
            "Open deployment page",
            "Lost every device?",
            "Import Recovery Kit",
            "Leave or clear pending workspace",
            "Legacy iCloud snapshot",
            "iCloud Drive snapshot",
            "Unavailable",
            "The Shinsou X iCloud Drive container is unavailable. Check the app's iCloud Documents entitlement.",
        )
        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key) == key, "$locale still falls back for '$key'")
            }
        }

        val traditional = shinsouStringsFor("zh-TW")
        assertEquals("Cloudflare 加密同步", traditional.text("Cloudflare encrypted sync"))
        assertEquals("部署中", traditional.text("Deploying"))
        assertEquals("游標 1/2 · 3 項變更待處理 · 4 項上傳待處理", traditional.text("Cursor {0}/{1} · {2} pending changes · {3} pending uploads", 1, 2, 3, 4))
    }

    @Test
    fun encryptedBodySyncConsentDoesNotFallBackInSupportedLocales() {
        val keys = listOf(
            "Import local content",
            "Images, CBZ, ZIP, TXT or EPUB · stored on this device",
            "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync.",
            "Encrypted TXT/EPUB body sync",
            "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB.",
            "Choose files",
            "Encrypted chapter body sync",
            "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload.",
        )
        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key) == key, "$locale still falls back for '$key'")
            }
        }
    }

    @Test
    fun extensionContentBrowseDoesNotFallBackInSupportedLocales() {
        val keys = listOf(
            "Content saved for offline reading.",
            "Extension content",
            "Unable to load chapters",
            "No chapters",
            "This extension did not return any readable units.",
            "Choose a format when this chapter provides more than one.",
            "Save offline",
            "Load more",
            "Choose content format",
            "The operation could not be completed.",
            "Format: {0}",
        )

        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key) == key, "$locale still falls back for extension content '$key'")
            }
            assertFalse(strings.text("Format: {0}", "EPUB").contains("{0}"))
        }
    }

    @Test
    fun reviewedExtensionApprovalDoesNotExposeEnumNamesOrFallBackInSupportedLocales() {
        val keys = listOf(
            "Approve reviewed extension",
            "SHA-256: {0}",
            "Required permissions",
            "The script remains blocked until you approve this exact version and digest.",
            "Exact reviewed permissions granted",
            "Downloaded ShuYue artifact is not an exact reviewed version",
            "Execution blocked",
            "Execute reviewed script",
            "Network access",
            "Cookie storage",
            "Credential access",
            "Show login prompt",
            "Modify favorites",
            "Open browser challenge",
        )

        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key, "digest") == key, "$locale still falls back for reviewed extension '$key'")
            }
        }

        val traditional = shinsouStringsFor("zh-TW")
        assertEquals("必要權限", traditional.text("Required permissions"))
        assertEquals("執行已審核腳本", traditional.text("Execute reviewed script"))
        assertEquals("網路存取", traditional.text("Network access"))
    }

    @Test
    fun contentBackupAndShuYueMigrationDoNotFallBackInSupportedLocales() {
        val keys = listOf(
            "Archive ready",
            "Back",
            "Binary export is unavailable until the content-backup service is connected.",
            "Books",
            "Chapter bodies are not shown in this report",
            "Choose .shinsou2 archive",
            "Choose another backup",
            "Choose backup",
            "Content backup v2",
            "Cookies ({0})",
            "Create binary archive",
            "Create portable archive",
            "Credentials ({0})",
            "Credentials, cookies, OAuth tokens, and device keys are always excluded.",
            "Done",
            "Each body is included only when its rights grant permits export; omissions are recorded in the manifest.",
            "Import from ShuYue",
            "Import protected secrets?",
            "Import secrets",
            "Import selected content",
            "Imported {0} credentials and {1} cookies into protected storage.",
            "Include exportable content bodies",
            "Inspecting a bounded copy…",
            "Leave workspace and restore this device",
            "Moving from ShuYue?",
            "No backup selected",
            "Optional secrets",
            "Portable metadata",
            "Preview truncated; only all-or-none selection is available",
            "Protected platform storage is unavailable, so secret import is blocked.",
            "Quarantined extension scripts",
            "Reading positions ({0})",
            "Restore and sync to all devices",
            "Restore content archive",
            "Restore remains blocked until one shared content transaction and sync-outbox coordinator is connected.",
            "Restore this archive?",
            "Restore verified archive",
            "Restored {0} publications, {1} annotations, and {2} content bodies.",
            "Review and import selected secrets",
            "Review first; scripts stay quarantined and secrets stay excluded",
            "Selected content and quarantined scripts were committed transactionally.",
            "Selected scripts are stored for later review and are never executed by import",
            "ShuYue backup v1",
            "The backup was rejected",
            "The complete container, declared paths, checksums, and portable state are validated before restore is enabled.",
            "The device must leave the workspace before its local state is replaced.",
            "This exact import was already committed; nothing was duplicated.",
            "This requires a Ready workspace and durable mutations, body uploads, and outbox records in the shared commit.",
            "This separate action replaces the stored ShuYue migration secret batch. Values remain device-only and cannot be recovered from a portable backup.",
            "Use the dedicated staged importer for its validation report, book selection, script quarantine, and separate secret consent.",
            "Validated contents",
            "Validated staging preview ready",
            "Validation report",
            "Values are never shown, backed up, synchronized, or imported automatically.",
            "Verified portable state and bodies will be committed on this device.",
            "Verified restore preview",
            "Version {0} · {1} bytes · digest {2}…",
            "Versioned manifest, checksums, and rights-filtered bodies",
            "Where should this archive be restored?",
            "{0} books · {1} chapters · {2} reading positions",
            "{0} categories · {1} characters of chapter text",
            "{0} chapters · {1}",
            "{0} publications · {1} annotations · {2} content bodies",
            "{0} · {1} attached manifests · {2} omitted",
            "Content backup is unavailable until the shared content storage is connected.",
            "ShuYue migration is unavailable until the shared content storage is connected.",
            "The checksummed content archive could not be created.",
            "The selected content archive failed format or checksum validation.",
            "The selected content archive could not be inspected safely.",
            "The sync-aware content restore did not commit.",
            "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive.",
            "The ShuYue backup could not be inspected safely.",
            "This backup was already imported with a different selection.",
            "The transactional ShuYue import did not complete.",
            "No ShuYue secrets were replaced because protected storage failed.",
            "The selected file is not an importable ShuYue v1 backup.",
            "The backup could not be inspected.",
            "Validation issue ({0})",
            "The backup contains invalid or unsupported data. Review code {0}.",
            "Not requested",
            "Rights denied",
            "Missing",
            "Corrupt",
            "Archive limit",
        )

        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key) == key, "$locale still falls back for portability '$key'")
            }
        }
    }

    @Test
    fun recoverableContentRestoreShowsSameArchiveRetryInstructionInChinese() {
        val key = "The sync workspace was left, but the device-local restore rolled back. " +
            "Retry the same verified archive."

        assertEquals(
            "同步工作區已離開，但此裝置的本機還原已回復原狀。請重試同一份已驗證的封存檔。",
            shinsouStringsFor("zh-TW").text(key),
        )
        assertEquals(
            "已离开同步工作区，但设备本机还原已回滚。请使用同一份已验证的归档文件重试。",
            shinsouStringsFor("zh-CN").text(key),
        )
    }

    @Test
    fun simplifiedLongTailDoesNotLeakCommonTraditionalTerms() {
        val simplified = shinsouStringsFor("zh-CN")

        assertEquals("启用 Shinsou X 应用程序锁", simplified.text("Enable Shinsou X app lock"))
        assertEquals("网络与扩展", simplified.text("Network and extensions"))
        assertEquals("来源设置", simplified.text("Source settings"))
        assertEquals("选择本机漫画", simplified.text("Choose local manga"))
    }

    @Test
    fun readerDirectionsAndPageAnimationAreCompleteInEverySupportedLanguage() {
        val expected = mapOf(
            "en-US" to ReaderCopy(
                "Paged · left to right", "Paged · right to left", "Vertical paging", "Webtoon",
                "Continuous vertical", "Page turn animation", "Animate transitions when changing pages",
            ),
            "zh-TW" to ReaderCopy(
                "翻頁（左至右）", "翻頁（右至左）", "垂直翻頁", "條漫",
                "連續垂直", "翻頁動畫", "切換頁面時顯示動畫",
            ),
            "zh-CN" to ReaderCopy(
                "翻页（从左到右）", "翻页（从右到左）", "垂直翻页", "条漫",
                "连续垂直", "翻页动画", "切换页面时显示动画",
            ),
            "ja-JP" to ReaderCopy(
                "ページ送り（左から右）", "ページ送り（右から左）", "縦方向のページ送り", "ウェブトゥーン",
                "縦スクロール", "ページ切り替えアニメーション", "ページを切り替えるときにアニメーションを表示",
            ),
            "ko-KR" to ReaderCopy(
                "페이지 넘김(왼쪽에서 오른쪽)", "페이지 넘김(오른쪽에서 왼쪽)", "세로 페이지 넘김", "웹툰",
                "연속 세로 스크롤", "페이지 전환 애니메이션", "페이지를 전환할 때 애니메이션 표시",
            ),
            "fr-FR" to ReaderCopy(
                "Pages · de gauche à droite", "Pages · de droite à gauche", "Pagination verticale", "Webtoon",
                "Défilement vertical continu", "Animation de changement de page", "Animer la transition entre les pages",
            ),
            "de-DE" to ReaderCopy(
                "Seiten · von links nach rechts", "Seiten · von rechts nach links", "Vertikales Blättern", "Webtoon",
                "Fortlaufend vertikal", "Seitenwechsel animieren", "Übergänge zwischen Seiten animieren",
            ),
            "es-ES" to ReaderCopy(
                "Páginas · de izquierda a derecha", "Páginas · de derecha a izquierda", "Paginación vertical", "Webtoon",
                "Desplazamiento vertical continuo", "Animación al pasar página", "Animar la transición entre páginas",
            ),
            "pt-BR" to ReaderCopy(
                "Páginas · da esquerda para a direita", "Páginas · da direita para a esquerda", "Paginação vertical", "Webtoon",
                "Rolagem vertical contínua", "Animação ao virar página", "Animar a transição entre páginas",
            ),
        )

        expected.forEach { (locale, copy) ->
            val strings = shinsouStringsFor(locale)
            assertEquals(copy.leftToRight, strings.text("Left to right"), locale)
            assertEquals(copy.rightToLeft, strings.text("Right to left"), locale)
            assertEquals(copy.vertical, strings.text("Vertical paging"), locale)
            assertEquals(copy.webtoon, strings.text("Webtoon"), locale)
            assertEquals(copy.continuousVertical, strings.text("Continuous vertical"), locale)
            assertEquals(copy.pageTurnAnimation, strings.text("Page turn animation"), locale)
            assertEquals(copy.pageTurnAnimationDescription, strings.text("Animate transitions when changing pages"), locale)
            assertEquals(copy.leftToRight, strings.text("Pager ltr"), "$locale legacy LTR key")
            assertEquals(copy.rightToLeft, strings.text("Pager rtl"), "$locale legacy RTL key")
            assertFalse(strings.text("Left to right").contains("LTR", ignoreCase = true), locale)
            assertFalse(strings.text("Right to left").contains("RTL", ignoreCase = true), locale)
        }
    }

    @Test
    fun unifiedReaderActionsAndFailuresAreLocalizedInEverySupportedLanguage() {
        val keys = listOf(
            "This content is no longer available under the current rights grant.",
            "Full-text search is unavailable for this content.",
            "Search text",
            "Speech stopped because it is unavailable or no longer permitted.",
            "Speech unavailable",
            "Speaking…",
            "Speak from paragraph",
            "Stop",
            "Search this book",
            "Search is no longer permitted.",
            "Find",
            "Add note",
            "Reader paragraph",
            "Copy is unavailable or not permitted.",
            "Copy",
            "Add paragraph note",
            "Note",
            "The note could not be saved or is no longer permitted.",
            "The image navigation graph is unavailable.",
            "Page {0}",
            "The image resource could not be opened.",
            "The EPUB navigation graph is unavailable.",
            "The EPUB resources could not be opened.",
            "Previous",
            "Next",
            "EPUB rendering is unavailable until this platform supplies its secure browser renderer.",
            "This image representation has not been materialized into reader pages.",
            "{0} note",
            "{0} notes",
        )

        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertTrue(key in strings.translations, "$locale has no explicit unified Reader translation for '$key'")
            }
        }

        assertEquals("第 7 頁", shinsouStringsFor("zh-TW").text("Page {0}", 7))
        assertEquals("1 則註記", shinsouStringsFor("zh-TW").text("{0} note", 1))
        assertEquals("2 条笔记", shinsouStringsFor("zh-CN").text("{0} notes", 2))
    }

    private data class ReaderCopy(
        val leftToRight: String,
        val rightToLeft: String,
        val vertical: String,
        val webtoon: String,
        val continuousVertical: String,
        val pageTurnAnimation: String,
        val pageTurnAnimationDescription: String,
    )
}
