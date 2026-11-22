package dev.shinsou.kmp.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShinsouStringsTest {
    private fun key(english: String): StringKey = StringKey.entries.single { it.english == english }

    /** 0008: the permission labels themselves are :ui-browse's (`ui.browse.PluginPermissionLabelsTest`). */
    @Test
    fun pluginReviewProgressIsLocalizedForEverySupportedLocale() {
        val locales = listOf("en-US", "zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR")
        locales.forEach { locale ->
            val strings = shinsouStringsFor(locale)
            listOf(
                "Installing…", "Updating…", "Removing…",
                "Applying permissions…", "Updating trust…",
                "This extension changed. Review its permissions again.",
            ).map(::key).forEach { key ->
                if (locale == "en-US") {
                    assertEquals(key.english, strings.text(key))
                } else {
                    assertFalse(strings.text(key) == key.english, "$locale exposed raw '$key'")
                }
            }
        }
    }

    @Test
    fun libraryContentTypeBadgesAreLocalized() {
        val keys = listOf(StringKey.MANGA, StringKey.NOVEL, StringKey.MIXED, StringKey.UNKNOWN_TYPE)
        listOf("en-US", "zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            keys.forEach { key ->
                if (locale != "en-US") assertTrue(key in shinsouStringsFor(locale).table, "$locale did not localize '$key'")
            }
        }
        assertEquals("漫畫", shinsouStringsFor("zh-TW").text(StringKey.MANGA))
        assertEquals("小說", shinsouStringsFor("zh-TW").text(StringKey.NOVEL))
    }

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

        assertEquals("沒有章節", traditional.text(StringKey.NO_CHAPTERS))
        assertEquals("没有章节", simplified.text(StringKey.NO_CHAPTERS))
        assertEquals("已閱讀 3/12 章 · 25%", traditional.text(StringKey.X_OF_X_CHAPTERS_READ_X, 3, 12, 25))
        assertEquals("已阅读 3/12 章 · 25%", simplified.text(StringKey.X_OF_X_CHAPTERS_READ_X, 3, 12, 25))
    }

    @Test
    fun legacyExtensionFavoriteRecoveryFailureIsActionableInChinese() {
        val key = key("Unable to recover this legacy extension favorite. Remove it and add the title again from its source.")
        val persistenceKey = key("The title opened, but its repaired extension identity could not be saved.")

        assertEquals(
            "無法還原這筆舊版擴充套件收藏。請移除後，再從原來源重新加入作品。",
            shinsouStringsFor("zh-TW").text(key),
        )
        assertFalse(shinsouStringsFor("zh-CN").text(key) == key.english)
        assertFalse(shinsouStringsFor("zh-TW").text(persistenceKey) == persistenceKey.english)
        assertFalse(shinsouStringsFor("zh-CN").text(persistenceKey) == persistenceKey.english)
    }

    @Test
    fun coreLabelsResolveThroughTextForEverySupportedEastAsianLocale() {
        assertEquals("設定", shinsouStringsFor("ja-JP").text(StringKey.SETTINGS))
        assertEquals("설정", shinsouStringsFor("ko-KR").text(StringKey.SETTINGS))
        assertEquals("设置", shinsouStringsFor("zh-CN").text(StringKey.SETTINGS))
        assertEquals("設定", shinsouStringsFor("zh-TW").text(StringKey.SETTINGS))
    }

    @Test
    fun remoteSourceCollectionLabelIsLocalized() {
        assertEquals("我的收藏庫", shinsouStringsFor("zh-TW").text(StringKey.MY_LIBRARY))
        assertEquals("我的收藏库", shinsouStringsFor("zh-CN").text(StringKey.MY_LIBRARY))
        assertEquals("マイライブラリ", shinsouStringsFor("ja-JP").text(StringKey.MY_LIBRARY))
        assertEquals("My library", shinsouStringsFor("en-US").text(StringKey.MY_LIBRARY))
    }

    @Test
    fun platformSecurityMessagesFollowTheSelectedChineseLocale() {
        val key = key("Set up a device passcode, PIN, password, or biometric authentication to use app lock.")
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
    fun syncSettingsPanelDoesNotFallBackToEnglish() {
        val keys = listOf(
            "Connect",
            "Unavailable",
        ).map(::key)
        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key) == key.english, "$locale still falls back for '$key'")
            }
        }
    }

    @Test
    fun encryptedBodySyncConsentDoesNotFallBackInSupportedLocales() {
        val keys = listOf(
            "Import local content",
            "Images, CBZ, ZIP, TXT or EPUB · stored on this device",
            "Encrypted chapter body sync",
            "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload.",
        ).map(::key)
        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key) == key.english, "$locale still falls back for '$key'")
            }
        }
    }

    @Test
    fun extensionContentBrowseDoesNotFallBackInSupportedLocales() {
        val keys = listOf(
            "Content saved for offline reading.",
            "Unable to load chapters",
            "No chapters",
            "This extension did not return any readable units.",
            "Load more",
            "Choose content format",
            "The operation could not be completed.",
            "Format: {0}",
        ).map(::key)

        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key) == key.english, "$locale still falls back for extension content '$key'")
            }
            assertFalse(strings.text(StringKey.FORMAT_X, "EPUB").contains("{0}"))
        }
    }

    @Test
    fun reviewedExtensionApprovalDoesNotExposeEnumNamesOrFallBackInSupportedLocales() {
        val keys = listOf(
            "Execution blocked",
            "Run extension script",
            "Network access",
            "Cookie storage",
            "Credential access",
            "Show login prompt",
            "Modify favorites",
            "Open browser challenge",
        ).map(::key)

        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key, "digest") == key.english, "$locale still falls back for reviewed extension '$key'")
            }
        }

        val traditional = shinsouStringsFor("zh-TW")
        assertEquals("執行擴充套件腳本", traditional.text(StringKey.RUN_EXTENSION_SCRIPT))
        assertEquals("網路存取", traditional.text(StringKey.NETWORK_ACCESS))
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
            "Moving from ShuYue?",
            "No backup selected",
            "Optional secrets",
            "Portable metadata",
            "Preview truncated; only all-or-none selection is available",
            "Protected platform storage is unavailable, so secret import is blocked.",
            "Quarantined extension scripts",
            "Reading positions ({0})",
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
            "This exact import was already committed; nothing was duplicated.",
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
        ).map(::key)

        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertFalse(strings.text(key) == key.english, "$locale still falls back for portability '$key'")
            }
        }
    }

    @Test
    fun simplifiedLongTailDoesNotLeakCommonTraditionalTerms() {
        val simplified = shinsouStringsFor("zh-CN")

        assertEquals("启用 Shinsou X 应用程序锁", simplified.text(StringKey.ENABLE_SHINSOU_X_APP_LOCK))
        assertEquals("网络与扩展", simplified.text(StringKey.NETWORK_AND_EXTENSIONS))
        assertEquals("来源设置", simplified.text(StringKey.SOURCE_SETTINGS))
        assertEquals("选择本机漫画", simplified.text(StringKey.CHOOSE_LOCAL_MANGA))
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
            assertEquals(copy.leftToRight, strings.text(StringKey.LEFT_TO_RIGHT), locale)
            assertEquals(copy.rightToLeft, strings.text(StringKey.RIGHT_TO_LEFT), locale)
            assertEquals(copy.vertical, strings.text(StringKey.VERTICAL_PAGING), locale)
            assertEquals(copy.webtoon, strings.text(StringKey.WEBTOON), locale)
            assertEquals(copy.continuousVertical, strings.text(StringKey.CONTINUOUS_VERTICAL), locale)
            assertEquals(copy.pageTurnAnimation, strings.text(StringKey.PAGE_TURN_ANIMATION), locale)
            assertEquals(copy.pageTurnAnimationDescription, strings.text(StringKey.ANIMATE_TRANSITIONS_WHEN_CHANGING_PAGES), locale)
            assertEquals(copy.leftToRight, strings.text(StringKey.PAGER_LTR), "$locale legacy LTR key")
            assertEquals(copy.rightToLeft, strings.text(StringKey.PAGER_RTL), "$locale legacy RTL key")
            assertFalse(strings.text(StringKey.LEFT_TO_RIGHT).contains("LTR", ignoreCase = true), locale)
            assertFalse(strings.text(StringKey.RIGHT_TO_LEFT).contains("RTL", ignoreCase = true), locale)
        }
    }

    @Test
    fun unifiedReaderActionsAndFailuresAreLocalizedInEverySupportedLanguage() {
        val keys = listOf(
            "This content is no longer available under the current rights grant.",
            "Full-text search is unavailable for this content.",
            "Search text",
            "Speech stopped because it is unavailable or no longer permitted.",
            "Speak from paragraph",
            "Stop",
            "Search this book",
            "Find",
            "Add note",
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
        ).map(::key)

        listOf("zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR").forEach { locale ->
            val strings = shinsouStringsFor(locale)
            keys.forEach { key ->
                assertTrue(key in strings.table, "$locale has no explicit unified Reader translation for '$key'")
            }
        }

        assertEquals("第 7 頁", shinsouStringsFor("zh-TW").text(StringKey.PAGE_X, 7))
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
