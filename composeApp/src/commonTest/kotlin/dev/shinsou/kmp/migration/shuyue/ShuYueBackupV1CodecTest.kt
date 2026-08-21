package dev.shinsou.kmp.migration.shuyue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShuYueBackupV1CodecTest {
    @Test
    fun strictDecoderAcceptsV1AndPreservesMultilineTextAndPreferenceKey() {
        val backup = ShuYueBackupV1Codec().decode(validJson())

        assertEquals(1, backup.version)
        assertEquals("book", backup.books.single().id)
        assertEquals("line one\nline two", backup.books.single().chapters.single().text)
        assertEquals("dark", backup.pluginPreferences["source\u0000theme"])
    }

    @Test
    fun unknownVersionFailsClosedBeforeDefaultingToAnEmptyBackup() {
        val exception = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec().decode("""{"version":2,"createdAt":1,"books":[]}""")
        }

        assertEquals(ShuYueBackupV1ErrorCode.UNSUPPORTED_VERSION, exception.code)
        assertEquals("version", exception.path)
        assertFalse(exception.toString().contains("2"))
    }

    @Test
    fun hostileVersionShapesAreMalformedAndNeverReachDtoDefaults() {
        listOf(
            """{"version":{},"createdAt":1}""",
            """{"version":[],"createdAt":1}""",
            """{"version":1,"createdAt":{}}""",
        ).forEach { input ->
            val exception = assertFailsWith<ShuYueBackupV1Exception> {
                ShuYueBackupV1Codec().decode(input)
            }
            assertEquals(ShuYueBackupV1ErrorCode.MALFORMED_JSON, exception.code)
        }
    }

    @Test
    fun strictJsonRejectsUnknownAndDuplicateMembersAtEveryObjectDepth() {
        val unknown = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec().decode("""{"version":1,"createdAt":1,"unknown":true}""")
        }
        assertEquals(ShuYueBackupV1ErrorCode.MALFORMED_JSON, unknown.code)

        val duplicate = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec().decode("""{"version":1,"ver\u0073ion":1,"createdAt":1}""")
        }
        assertEquals(ShuYueBackupV1ErrorCode.MALFORMED_JSON, duplicate.code)

        val nestedDuplicate = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec().decode(
                """{"version":1,"createdAt":1,"books":[{"id":"book","i\u0064":"other","title":"Book"}]}""",
            )
        }
        assertEquals(ShuYueBackupV1ErrorCode.MALFORMED_JSON, nestedDuplicate.code)

        val duplicateMapKey = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec().decode(
                """{"version":1,"createdAt":1,"pluginPreferences":{"source\u0000theme":"a","source\u0000th\u0065me":"b"}}""",
            )
        }
        assertEquals(ShuYueBackupV1ErrorCode.MALFORMED_JSON, duplicateMapKey.code)
    }

    @Test
    fun byteAndCharacterBoundsAreCheckedBeforeParsing() {
        val limits = ShuYueBackupV1Limits(maxRawBytes = 8, maxRawChars = 8)
        val exception = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec(limits).decode(validJson().encodeToByteArray())
        }

        assertEquals(ShuYueBackupV1ErrorCode.INPUT_TOO_LARGE, exception.code)
    }

    @Test
    fun jsonMemberCountsAreBoundedBeforeTreeParsing() {
        val perObject = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec(
                ShuYueBackupV1Limits(maxJsonObjectMembers = 3),
            ).decode("""{"version":1,"createdAt":1,"books":[],"progress":[]}""")
        }
        assertEquals(ShuYueBackupV1ErrorCode.INPUT_TOO_LARGE, perObject.code)

        val total = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec(
                ShuYueBackupV1Limits(maxJsonObjectMembers = 10, maxTotalJsonMembers = 3),
            ).decode("""{"version":1,"createdAt":1,"books":[{"id":"book"}]}""")
        }
        assertEquals(ShuYueBackupV1ErrorCode.INPUT_TOO_LARGE, total.code)
    }

    @Test
    fun jsonArrayElementsAndTotalValuesAreBoundedBeforeTreeParsing() {
        val array = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec(
                ShuYueBackupV1Limits(maxJsonArrayElements = 2),
            ).decode("""{"version":1,"createdAt":1,"books":[null,null,null]}""")
        }
        assertEquals(ShuYueBackupV1ErrorCode.INPUT_TOO_LARGE, array.code)

        val total = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec(
                ShuYueBackupV1Limits(maxTotalJsonValues = 4),
            ).decode("""{"version":1,"createdAt":1,"books":[[]]}""")
        }
        assertEquals(ShuYueBackupV1ErrorCode.INPUT_TOO_LARGE, total.code)
    }

    @Test
    fun genericAndSecuritySensitiveMetadataFieldsHaveExplicitBounds() {
        val genericReport = ShuYueBackupV1Validator.validate(
            ShuYueBackupV1(
                version = 1,
                createdAt = 1,
                books = listOf(
                    ShuYueV1Book(id = "book", title = "123456789", category = "Default"),
                ),
            ),
            ShuYueBackupV1Limits(maxFieldChars = 8, maxTitleChars = 100),
        )
        assertTrue(
            genericReport.issues.any {
                it.code == ShuYueMigrationIssueCode.FIELD_LENGTH_LIMIT_EXCEEDED &&
                    it.entityRef?.kind == "book"
            },
        )

        val manifest = ShuYueV1PluginManifest(
            id = "plug",
            name = "Plugin",
            version = "1",
            versionCode = 1,
            lang = "en",
            script = "",
        )
        val sensitiveReport = ShuYueBackupV1Validator.validate(
            ShuYueBackupV1(
                version = 1,
                createdAt = 1,
                installedPlugins = listOf(
                    ShuYueV1InstalledPlugin(
                        manifest = manifest,
                        installedAt = 1,
                        trustedSigningKeyFingerprint = "123456789",
                    ),
                ),
                selectedPluginRepositoryUrl = "https://x.test",
                pluginCredentials = listOf(
                    ShuYueV1PluginCredential("source-id", "123456789", "password", 1),
                ),
                pluginCookies = listOf(
                    ShuYueV1PluginCookie("source-id", "session-id", "value", "example.test"),
                ),
                pluginImageParsingPolicies = mapOf(
                    "source-id" to ShuYueV1PluginImageParsingPolicy.FOLLOW_DEFAULT,
                ),
            ),
            ShuYueBackupV1Limits(
                maxFieldChars = 100,
                maxIdentifierChars = 8,
                maxUrlChars = 8,
                maxRepositoryFieldChars = 8,
                maxCredentialFieldChars = 8,
                maxCookieFieldChars = 8,
            ),
        )
        val boundedEntityKinds = sensitiveReport.issues
            .filter { it.code == ShuYueMigrationIssueCode.FIELD_LENGTH_LIMIT_EXCEEDED }
            .mapNotNull { it.entityRef?.kind }
            .toSet()
        assertTrue(
            setOf(
                "installedPlugin",
                "selectedRepository",
                "credential",
                "cookie",
                "imagePolicy",
            ).all { it in boundedEntityKinds },
        )
    }

    @Test
    fun identifiersRejectLineTabAndCarriageReturnControls() {
        val plugin = ShuYueV1PluginManifest(
            id = "plugin",
            name = "Plugin",
            version = "1",
            versionCode = 1,
            lang = "en",
            script = "",
            sources = listOf(
                ShuYueV1PluginSourceDescriptor(
                    id = "source\rid",
                    name = "Source",
                    lang = "en",
                    baseUrl = "https://example.test",
                ),
            ),
        )
        val report = ShuYueBackupV1Validator.validate(
            ShuYueBackupV1(
                version = 1,
                createdAt = 1,
                books = listOf(
                    ShuYueV1Book(
                        id = "book\nid",
                        title = "Book",
                        chapters = listOf(
                            ShuYueV1Chapter(
                                id = "chapter\tid",
                                bookId = "book\nid",
                                title = "Chapter",
                                index = 0,
                                text = "body",
                                wordCount = 4,
                            ),
                        ),
                        category = "Default",
                    ),
                ),
                installedPlugins = listOf(ShuYueV1InstalledPlugin(plugin, installedAt = 1)),
            ),
        )
        val invalidKinds = report.issues
            .filter { it.code == ShuYueMigrationIssueCode.INVALID_IDENTIFIER }
            .mapNotNull { it.entityRef?.kind }
            .toSet()
        assertTrue(setOf("book", "chapter", "pluginSource").all { it in invalidKinds })
    }

    @Test
    fun remoteBookSourceIdRejectsControlsBeforePortableIdentityDerivation() {
        val backup = ShuYueBackupV1(
            version = 1,
            createdAt = 1,
            books = listOf(
                ShuYueV1Book(
                    id = "book",
                    title = "Book",
                    origin = ShuYueV1BookOrigin.REMOTE_PLUGIN,
                    sourceId = "source\nline",
                    chapters = emptyList(),
                    category = "Default",
                ),
            ),
        )

        val report = ShuYueBackupV1Validator.validate(backup)

        assertTrue(
            report.issues.any {
                it.code == ShuYueMigrationIssueCode.INVALID_IDENTIFIER &&
                    it.entityRef?.kind == "book"
            },
        )
        assertEquals(null, ShuYueBackupV1Stager.stage(backup))
    }

    @Test
    fun distinctEscapedLoneSurrogateIdsFailBeforePortableIdentityDerivation() {
        val exception = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec().decode(
                """
                {
                  "version": 1,
                  "createdAt": 1,
                  "books": [
                    {"id":"\uD800","title":"First","category":"Default"},
                    {"id":"\uD801","title":"Second","category":"Default"}
                  ]
                }
                """.trimIndent(),
            )
        }
        assertEquals(ShuYueBackupV1ErrorCode.VALIDATION_FAILED, exception.code)
        assertEquals(
            2,
            exception.report!!.issues.count { it.code == ShuYueMigrationIssueCode.INVALID_TEXT_ENCODING },
        )
    }

    @Test
    fun metadataMapKeysAndQuarantinedScriptsRequireWellFormedUtf16() {
        val malformed = "\uD800"
        val manifest = ShuYueV1PluginManifest(
            id = "plugin",
            name = "Plugin",
            version = "1",
            versionCode = 1,
            lang = "en",
            script = malformed,
        )
        val backup = ShuYueBackupV1(
            version = 1,
            createdAt = 1,
            books = listOf(ShuYueV1Book(id = "book", title = "title$malformed")),
            installedPlugins = listOf(ShuYueV1InstalledPlugin(manifest, installedAt = 1)),
            pluginPreferences = mapOf("source\u0000key$malformed" to "value"),
        )

        val report = ShuYueBackupV1Validator.validate(backup)
        val invalidKinds = report.issues
            .filter { it.code == ShuYueMigrationIssueCode.INVALID_TEXT_ENCODING }
            .mapNotNull { it.entityRef?.kind }
            .toSet()
        assertTrue(setOf("book", "installedPlugin", "preference").all { it in invalidKinds })
        assertEquals(null, ShuYueBackupV1Stager.stage(backup))
    }

    @Test
    fun danglingProgressAndInvalidOffsetAreReportedWithStableCodes() {
        val exception = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec().decode(
                """
                {
                  "version": 1,
                  "createdAt": 1,
                  "books": [{
                    "id": "book",
                    "title": "Book",
                    "origin": "LocalTxt",
                    "chapters": [{
                      "id": "chapter",
                      "bookId": "book",
                      "title": "Chapter",
                      "index": 0,
                      "text": "body",
                      "wordCount": 4
                    }]
                  }],
                  "progress": [
                    {"bookId":"book","chapterId":"missing","charOffset":0,"progress":0.1,"updatedAt":1},
                    {"bookId":"book","chapterId":"chapter","charOffset":99,"progress":0.1,"updatedAt":1}
                  ]
                }
                """.trimIndent(),
            )
        }

        assertEquals(ShuYueBackupV1ErrorCode.VALIDATION_FAILED, exception.code)
        val codes = exception.report!!.issues.map { it.code }.toSet()
        assertTrue(ShuYueMigrationIssueCode.MISSING_CHAPTER_REFERENCE in codes)
        assertTrue(ShuYueMigrationIssueCode.INVALID_CHAR_OFFSET in codes)
    }

    @Test
    fun byteDecoderRejectsInvalidUtf8() {
        val exception = assertFailsWith<ShuYueBackupV1Exception> {
            ShuYueBackupV1Codec().decode(byteArrayOf(0x7B, 0xC3.toByte(), 0x28))
        }
        assertEquals(ShuYueBackupV1ErrorCode.MALFORMED_JSON, exception.code)
    }

    private fun validJson(): String =
        """
        {
          "version": 1,
          "createdAt": 1,
          "books": [{
            "id": "book",
            "title": "Book",
            "origin": "LocalTxt",
            "chapters": [{
              "id": "chapter",
              "bookId": "book",
              "title": "Chapter",
              "index": 0,
              "text": "line one\nline two",
              "wordCount": 19
            }],
            "category": "Default"
          }],
          "progress": [{
            "bookId": "book",
            "chapterId": "chapter",
            "charOffset": 2,
            "progress": 0.2,
            "updatedAt": 1
          }],
          "pluginPreferences": {"source\u0000theme": "dark"}
        }
        """.trimIndent()
}
