package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.domain.model.PortableCategoryId
import dev.shinsou.kmp.plugin.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShuYuePreviewTest {
    @Test
    fun publicInspectorReturnsOnlySafePreviewAndQuarantinesEveryScriptOccurrence() {
        val result = ShuYueBackupV1Inspector.inspect(
            """
            {
              "version": 1,
              "createdAt": 1,
              "books": [{
                "id": "book",
                "title": "Book",
                "origin": "RemotePlugin",
                "sourceId": "source",
                "chapters": [{
                  "id": "chapter",
                  "bookId": "book",
                  "title": "Chapter",
                  "index": 0,
                  "text": "line one\nline two",
                  "wordCount": 19
                }],
                "category": "Fiction"
              }],
              "progress": [{"bookId":"book","chapterId":"chapter","charOffset":2,"progress":0.2,"updatedAt":1}],
              "installedPlugins": [{"manifest": {
                "id":"plugin","name":"Plugin","version":"1","versionCode":1,"lang":"en",
                "script":"installed-script","sources": [{"id":"source","name":"Source","lang":"en","baseUrl":"https://example.test"}]
              },"installedAt":1}],
              "pluginInstallations": [{"plugin": {
                "id":"plugin","name":"Plugin","version":"1","versionCode":1,"lang":"en",
                "script":"installed-script","sources": [{"id":"source","name":"Source","lang":"en","baseUrl":"https://example.test"}]
              },"script":"persisted-script"}],
              "pluginCredentials": [{"sourceId":"source","username":"user","password":"TOP-SECRET","updatedAt":1}],
              "pluginCookies": [{"sourceId":"source","name":"session","value":"COOKIE-SECRET","domain":"example.test","path":"/"}],
              "pluginPreferences": {"source\u0000theme":"dark"}
            }
            """.trimIndent(),
        )

        assertTrue(result.accepted)
        assertTrue(result.canStage)
        assertEquals(1L, result.preview.counts.books)
        assertEquals(1L, result.preview.secrets.credentialCount)
        assertEquals(1L, result.preview.secrets.cookieCount)
        assertFalse(result.preview.secrets.automaticImportAllowed)
        assertEquals(3, result.preview.quarantinedPlugins.size)
        assertTrue(result.preview.quarantinedPlugins.all { it.requiresExplicitTrust })
        assertEquals(
            setOf(
                "installedPlugins.manifest.script",
                "pluginInstallations.plugin.script",
                "pluginInstallations.script",
            ),
            result.preview.quarantinedPlugins.map { it.origin }.toSet(),
        )
        val rendered = result.toString()
        assertFalse("TOP-SECRET" in rendered)
        assertFalse("COOKIE-SECRET" in rendered)
        assertFalse("installed-script" in rendered)
        assertFalse("persisted-script" in rendered)
    }

    @Test
    fun maliciousCookieIsRejectedWithoutEchoingValueOrPath() {
        val result = ShuYueBackupV1Inspector.inspect(
            """
            {
              "version":1,
              "createdAt":1,
              "pluginCookies":[{"sourceId":"source","name":"session","value":"COOKIE-SECRET","domain":"https://evil.example","path":"//evil"}]
            }
            """.trimIndent(),
        )

        assertFalse(result.accepted)
        assertEquals(ShuYueBackupV1ErrorCode.VALIDATION_FAILED, result.errorCode)
        assertTrue(result.preview.issues.any { it.code == ShuYueMigrationIssueCode.INVALID_COOKIE })
        assertFalse(result.toString().contains("COOKIE-SECRET"))
        assertFalse(result.toString().contains("https://evil.example"))
    }

    @Test
    fun sameInputProducesDeterministicPreviewOrdering() {
        val input = """{"version":1,"createdAt":1,"books":[]}"""
        val first = ShuYueBackupV1Inspector.inspect(input).preview
        val second = ShuYueBackupV1Inspector.inspect(input).preview

        assertEquals(first, second)
    }

    @Test
    fun stringInspectorAppliesCharacterBoundBeforeUtf8Conversion() {
        val result = ShuYueBackupV1Inspector.inspect(
            "x".repeat(9),
            ShuYueBackupV1Limits(maxRawBytes = 64, maxRawChars = 8),
        )
        assertFalse(result.accepted)
        assertEquals(ShuYueBackupV1ErrorCode.INPUT_TOO_LARGE, result.errorCode)
    }

    @Test
    fun stagedMigrationDerivesLedgerFromExactSourceBytesAndVersionedResult() {
        val encoded = """{"version":1,"createdAt":1,"books":[]}""".encodeToByteArray()
        val first = assertNotNull(ShuYueBackupV1Stager.stageWithLedger(encoded))
        val replay = assertNotNull(ShuYueBackupV1Stager.stageWithLedger(encoded.copyOf()))
        assertEquals(first.ledgerMutation, replay.ledgerMutation)
        assertEquals(Sha256.hex(encoded), first.ledgerMutation.sourceDigestSha256)
        assertEquals(
            "migration:${first.ledgerMutation.migrationKey}",
            first.ledgerMutation.commitId,
        )

        val semanticallyEqualDifferentBytes = encoded + byteArrayOf(' '.code.toByte())
        val changed = assertNotNull(
            ShuYueBackupV1Stager.stageWithLedger(semanticallyEqualDifferentBytes),
        )
        assertNotEquals(first.ledgerMutation.sourceDigestSha256, changed.ledgerMutation.sourceDigestSha256)
        assertNotEquals(
            first.ledgerMutation.resultFingerprintSha256,
            changed.ledgerMutation.resultFingerprintSha256,
        )
    }

    @Test
    fun stagedPreferenceMapsUseStableLexicographicOrderOnAllTargets() {
        val backup = ShuYueBackupV1Codec().decode(
            """
            {
              "version": 1,
              "createdAt": 1,
              "pluginPreferences": {
                "z-source\u0000theme": "dark",
                "a-source\u0000theme": "light"
              },
              "pluginImageParsingPolicies": {
                "z-source": "Allow",
                "a-source": "Deny"
              }
            }
            """.trimIndent(),
        )

        val staged = requireNotNull(ShuYueBackupV1Stager.stage(backup))
        assertEquals(
            listOf("a-source\u0000theme", "z-source\u0000theme"),
            staged.preferences.keys.toList(),
        )
        assertEquals(listOf("a-source", "z-source"), staged.imageParsingPolicies.keys.toList())
    }

    @Test
    fun stagingMaterializesUtf16LocatorAndQuoteReanchorsAfterContentMovesForward() {
        val original = "前言 😀 target text 結尾"
        val targetOffset = original.indexOf("target")
        val backup = ShuYueBackupV1Codec().decode(
            """
            {
              "version": 1,
              "createdAt": 1,
              "books": [{
                "id": "book",
                "title": "Book",
                "chapters": [{
                  "id": "chapter",
                  "bookId": "book",
                  "title": "Chapter",
                  "index": 0,
                  "text": "$original",
                  "wordCount": ${original.length}
                }]
              }],
              "progress": [{
                "bookId": "book",
                "chapterId": "chapter",
                "charOffset": $targetOffset,
                "progress": 0.4,
                "updatedAt": 1
              }]
            }
            """.trimIndent(),
        )

        val staged = requireNotNull(ShuYueBackupV1Stager.stage(backup))
        assertEquals(1, staged.progress.size)
        assertEquals(1, staged.readingLocators.size)
        val stagedProgress = staged.progress.single()
        val locator = stagedProgress.locator
        assertEquals(targetOffset, locator.offset)
        assertEquals(ShuYueReadingLocatorMapper.DEFAULT_TEXT_BLOCK_ID, locator.blockId)
        assertEquals(original.indexOf("target"), locator.resolveOffset(original))
        val quote = assertNotNull(locator.quote)
        assertTrue(quote.exact.length <= 256)
        assertTrue(quote.prefix.length <= 64)
        assertTrue(quote.suffix.length <= 64)

        val moved = "新增前置內容 | $original"
        assertEquals(moved.indexOf("target"), locator.resolveOffset(moved))
        assertEquals(stagedProgress.raw, staged.rawProgress.single())
    }

    @Test
    fun quotePrefixStaysWithinItsBoundWhenTheWindowWouldSplitAnEmoji() {
        val text = "😀" + "a".repeat(63) + "target"
        val targetOffset = text.indexOf("target")
        val backup = ShuYueBackupV1(
            version = 1,
            createdAt = 1,
            books = listOf(
                ShuYueV1Book(
                    id = "book",
                    title = "Book",
                    chapters = listOf(
                        ShuYueV1Chapter(
                            id = "chapter",
                            bookId = "book",
                            title = "Chapter",
                            index = 0,
                            text = text,
                            wordCount = text.length,
                        ),
                    ),
                ),
            ),
            progress = listOf(
                ShuYueV1ReaderProgress(
                    bookId = "book",
                    chapterId = "chapter",
                    charOffset = targetOffset,
                    progress = 0.5f,
                    updatedAt = 1,
                ),
            ),
        )

        val locator = requireNotNull(ShuYueBackupV1Stager.stage(backup)).readingLocators.single()
        val quote = assertNotNull(locator.quote)
        assertEquals(targetOffset, locator.offset)
        assertEquals("a".repeat(63), quote.prefix)
        assertTrue(quote.prefix.length <= 64)
    }

    @Test
    fun stagingQuoteMovesToScalarBoundaryWhenLegacyOffsetSplitsEmoji() {
        val text = "前😀後"
        val emojiStart = text.indexOf("😀")
        val backup = ShuYueBackupV1Codec().decode(
            """
            {
              "version": 1,
              "createdAt": 1,
              "books": [{
                "id": "book",
                "title": "Book",
                "chapters": [{
                  "id": "chapter",
                  "bookId": "book",
                  "title": "Chapter",
                  "index": 0,
                  "text": "$text",
                  "wordCount": ${text.length}
                }]
              }],
              "progress": [{
                "bookId": "book",
                "chapterId": "chapter",
                "charOffset": ${emojiStart + 1},
                "progress": 0.4,
                "updatedAt": 1
              }]
            }
            """.trimIndent(),
        )

        val locator = requireNotNull(ShuYueBackupV1Stager.stage(backup)).readingLocators.single()
        assertEquals(emojiStart + 1, locator.offset)
        assertEquals(emojiStart, locator.resolveOffset(text))
        assertEquals("😀", locator.quote!!.exact.substring(0, 2))
    }

    @Test
    fun stagingAtEndOfChapterPreservesExactEofOffset() {
        val text = "chapter end"
        val backup = ShuYueBackupV1Codec().decode(
            """
            {
              "version": 1,
              "createdAt": 1,
              "books": [{
                "id": "book",
                "title": "Book",
                "chapters": [{
                  "id": "chapter",
                  "bookId": "book",
                  "title": "Chapter",
                  "index": 0,
                  "text": "$text",
                  "wordCount": ${text.length}
                }]
              }],
              "progress": [{
                "bookId": "book",
                "chapterId": "chapter",
                "charOffset": ${text.length},
                "progress": 1.0,
                "updatedAt": 1
              }]
            }
            """.trimIndent(),
        )

        val locator = requireNotNull(ShuYueBackupV1Stager.stage(backup)).readingLocators.single()
        assertEquals(null, locator.quote)
        assertEquals(text.length, locator.resolveOffset(text))
    }

    @Test
    fun stagingDropsQuoteBeyondGlobalOccurrenceCapAndKeepsExecutableOffset() {
        val text = "a".repeat(1_000_400)
        val targetOffset = 1_000_200
        val backup = ShuYueBackupV1(
            version = 1,
            createdAt = 1,
            books = listOf(
                ShuYueV1Book(
                    id = "book",
                    title = "Book",
                    chapters = listOf(
                        ShuYueV1Chapter(
                            id = "chapter",
                            bookId = "book",
                            title = "Chapter",
                            index = 0,
                            text = text,
                            wordCount = text.length,
                        ),
                    ),
                ),
            ),
            progress = listOf(
                ShuYueV1ReaderProgress(
                    bookId = "book",
                    chapterId = "chapter",
                    charOffset = targetOffset,
                    progress = 0.9f,
                    updatedAt = 1,
                ),
            ),
        )

        val locator = requireNotNull(ShuYueBackupV1Stager.stage(backup)).readingLocators.single()
        assertEquals(null, locator.quote)
        assertEquals(targetOffset, locator.resolveOffset(text))
    }

    @Test
    fun quoteCapFallbackNormalizesSplitSurrogateWhileRetainingRawProgress() {
        val text = "😀".repeat(1_000_400)
        val emojiStart = 2 * 1_000_200
        val splitOffset = emojiStart + 1
        val backup = ShuYueBackupV1(
            version = 1,
            createdAt = 1,
            books = listOf(
                ShuYueV1Book(
                    id = "book",
                    title = "Book",
                    chapters = listOf(
                        ShuYueV1Chapter(
                            id = "chapter",
                            bookId = "book",
                            title = "Chapter",
                            index = 0,
                            text = text,
                            wordCount = text.length,
                        ),
                    ),
                ),
            ),
            progress = listOf(
                ShuYueV1ReaderProgress(
                    bookId = "book",
                    chapterId = "chapter",
                    charOffset = splitOffset,
                    progress = 0.9f,
                    updatedAt = 1,
                ),
            ),
        )

        val staged = requireNotNull(ShuYueBackupV1Stager.stage(backup))
        val locator = staged.readingLocators.single()
        assertEquals(splitOffset, staged.rawProgress.single().charOffset)
        assertEquals(null, locator.quote)
        assertEquals(emojiStart, locator.offset)
        assertEquals(emojiStart, locator.resolveOffset(text))
    }

    @Test
    fun stagedLedgerUsesOneBoundedSnapshotForDecodeAndDigest() {
        val original = """{"version":1,"createdAt":1,"books":[]}""".encodeToByteArray()
        val expectedBytes = original.copyOf()
        val expected = assertNotNull(ShuYueBackupV1Stager.stageWithLedger(expectedBytes))

        val actual = assertNotNull(
            ShuYueBackupV1Stager.stageWithLedgerAfterSnapshotForTest(original) {
                original.fill(0)
            },
        )

        assertEquals(Sha256.hex(expectedBytes), actual.ledgerMutation.sourceDigestSha256)
        assertEquals(expected.ledgerMutation, actual.ledgerMutation)
        assertEquals(expected.session, actual.session)
    }

    @Test
    fun stagingBindsDefaultAndNamedCategoriesToStablePortableIdsPerBook() {
        val backup = ShuYueBackupV1(
            version = 1,
            createdAt = 1,
            books = listOf(
                ShuYueV1Book(id = "default-book", title = "Default", category = "Default"),
                ShuYueV1Book(id = "named-book", title = "Named", category = "Fiction"),
            ),
        )

        val first = requireNotNull(ShuYueBackupV1Stager.stage(backup))
        val replay = requireNotNull(ShuYueBackupV1Stager.stage(backup.copy()))
        assertEquals(first.categories, replay.categories)

        val categoriesByName = first.categories.associateBy { it.name }
        assertEquals(PortableCategoryId.DEFAULT, categoriesByName.getValue("Default").id)
        assertNotEquals(PortableCategoryId.DEFAULT, categoriesByName.getValue("Fiction").id)
        first.books.forEach { book ->
            assertEquals(categoriesByName.getValue(book.category).id, book.categoryId)
        }
    }

    @Test
    fun stagedResultFingerprintBindsEveryImportedResultPlane() {
        val session = requireNotNull(ShuYueBackupV1Stager.stage(fingerprintFixture()))
        val sourceDigest = "01".repeat(32)
        val baseline = ShuYueBackupV1Stager.fingerprintStagedResult(sourceDigest, session)

        fun assertChanged(label: String, changed: ShuYueStagingSession) {
            assertNotEquals(
                baseline,
                ShuYueBackupV1Stager.fingerprintStagedResult(sourceDigest, changed),
                label,
            )
        }

        assertChanged(
            "book metadata",
            session.copy(books = listOf(session.books.single().copy(title = "Changed book"))),
        )
        assertChanged(
            "per-book category mapping",
            session.copy(
                books = listOf(
                    session.books.single().copy(
                        categoryId = ShuYueReadingLocatorMapper.portableCategoryId("Other"),
                    ),
                ),
            ),
        )
        assertChanged(
            "chapter text digest",
            session.copy(
                chapters = listOf(session.chapters.single().copy(text = "alpha Target omega")),
            ),
        )
        assertChanged(
            "category mapping",
            session.copy(
                categories = listOf(
                    session.categories.single().copy(
                        id = ShuYueReadingLocatorMapper.portableCategoryId("Other"),
                    ),
                ),
            ),
        )

        val progress = session.progress.single()
        assertChanged(
            "raw progress",
            session.copy(
                progress = listOf(progress.copy(raw = progress.raw.copy(updatedAt = 43))),
            ),
        )
        assertChanged(
            "locator scope",
            session.copy(
                progress = listOf(
                    progress.copy(
                        locator = progress.locator.copy(
                            scope = progress.locator.scope.copy(contentRevision = 1),
                        ),
                    ),
                ),
            ),
        )
        assertChanged(
            "locator quote",
            session.copy(
                progress = listOf(
                    progress.copy(
                        locator = progress.locator.copy(
                            quote = requireNotNull(progress.locator.quote).copy(occurrence = 1),
                        ),
                    ),
                ),
            ),
        )
        assertChanged(
            "reader settings",
            session.copy(
                readerSettings = session.readerSettings.copy(lineHeightPercent = 177),
            ),
        )
        assertChanged(
            "preference",
            session.copy(preferences = session.preferences + ("a-source\u0000theme" to "sepia")),
        )
        assertChanged(
            "image policy",
            session.copy(
                imageParsingPolicies = session.imageParsingPolicies +
                    ("a-source" to ShuYueV1PluginImageParsingPolicy.DENY),
            ),
        )
        assertChanged(
            "quarantined script digest",
            session.copy(
                pluginInstallations = session.pluginInstallations.mapIndexed { index, plugin ->
                    if (index == 0) plugin.copy(script = "Runtime-script") else plugin
                },
            ),
        )
        assertNotEquals(
            baseline,
            ShuYueBackupV1Stager.fingerprintStagedResult("02".repeat(32), session),
            "source digest",
        )

        val reorderedMaps = session.copy(
            preferences = session.preferences.entries.reversed().associateTo(linkedMapOf()) { it.toPair() },
            imageParsingPolicies = session.imageParsingPolicies.entries
                .reversed()
                .associateTo(linkedMapOf()) { it.toPair() },
        )
        assertEquals(
            baseline,
            ShuYueBackupV1Stager.fingerprintStagedResult(sourceDigest, reorderedMaps),
        )
    }

    @Test
    fun stagedResultFingerprintHasStableV2GoldenValue() {
        val session = requireNotNull(ShuYueBackupV1Stager.stage(fingerprintFixture()))
        assertEquals(
            "5c42d2b73a9f43a805a1e376c8aa550d313bacc25f2cc835b9fec2f958b3834f",
            ShuYueBackupV1Stager.fingerprintStagedResult("01".repeat(32), session),
        )
    }

    private fun fingerprintFixture(): ShuYueBackupV1 {
        val source = ShuYueV1PluginSourceDescriptor(
            id = "a-source",
            name = "Source",
            lang = "en",
            baseUrl = "https://example.test",
            supportsLatest = true,
        )
        val manifest = ShuYueV1PluginManifest(
            id = "plugin",
            name = "Plugin",
            version = "1.2.3",
            versionCode = 12,
            lang = "en",
            script = "runtime-script",
            minRuntimeVersion = "1",
            sources = listOf(source),
        )
        val text = "alpha target omega"
        return ShuYueBackupV1(
            version = 1,
            createdAt = 42,
            books = listOf(
                ShuYueV1Book(
                    id = "book",
                    title = "Book",
                    author = "Author",
                    description = "Description",
                    origin = ShuYueV1BookOrigin.REMOTE_PLUGIN,
                    sourceId = source.id,
                    originalUri = "https://example.test/book",
                    chapters = listOf(
                        ShuYueV1Chapter(
                            id = "chapter",
                            bookId = "book",
                            title = "Chapter",
                            index = 0,
                            href = "chapter-1",
                            text = text,
                            wordCount = text.length,
                        ),
                    ),
                    addedAt = 40,
                    updatedAt = 41,
                    category = "Fiction",
                ),
            ),
            progress = listOf(
                ShuYueV1ReaderProgress(
                    bookId = "book",
                    chapterId = "chapter",
                    charOffset = text.indexOf("target"),
                    progress = 0.25f,
                    updatedAt = 42,
                ),
            ),
            readerSettings = ShuYueV1ReaderSettings(
                language = ShuYueV1AppLanguage.TRADITIONAL_CHINESE,
                fontSizeSp = 20f,
                lineHeightPercent = 175,
                pageChars = 900,
                theme = ShuYueV1ReaderTheme.PAPER,
                accentColor = ShuYueV1AccentColor.TEAL,
                volumeKeysEnabled = false,
                volumeUpAction = ShuYueV1PageTurnAction.NEXT,
                volumeDownAction = ShuYueV1PageTurnAction.PREVIOUS,
                keepScreenOn = true,
                syncOnLaunch = true,
                secureScreen = true,
                showNsfwSources = true,
                showPluginErrors = true,
            ),
            installedPlugins = listOf(
                ShuYueV1InstalledPlugin(
                    manifest = manifest,
                    installedAt = 42,
                    enabled = false,
                ),
            ),
            pluginInstallations = listOf(
                ShuYueV1PersistedPluginInstall(
                    plugin = manifest,
                    script = "persisted-script",
                ),
            ),
            pluginPreferences = linkedMapOf(
                "z-source\u0000theme" to "dark",
                "a-source\u0000theme" to "light",
            ),
            pluginImageParsingPolicies = linkedMapOf(
                "z-source" to ShuYueV1PluginImageParsingPolicy.ALLOW,
                "a-source" to ShuYueV1PluginImageParsingPolicy.FOLLOW_DEFAULT,
            ),
        )
    }
}
