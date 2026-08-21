package dev.shinsou.kmp.migration.shuyue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop-only golden tests intentionally load the files from commonTest/resources.  Keeping the
 * loader on the JVM classpath makes a missing or stale fixture fail the test instead of silently
 * falling back to an inline copy.
 */
class ShuYueBackupV1FixtureResourceTest {
    @Test
    fun validFixtureIsAcceptedWithContentScriptAndSecretCounts() {
        val result = inspectFixture("valid-v1.json")

        assertTrue(result.accepted)
        assertTrue(result.canStage)
        assertEquals(3L, result.preview.counts.books)
        assertEquals(3L, result.preview.counts.chapters)
        assertEquals(31L, result.preview.counts.totalChapterChars)
        assertEquals(1L, result.preview.counts.progress)
        assertEquals(1L, result.preview.counts.installedPlugins)
        assertEquals(1L, result.preview.counts.pluginInstallations)
        assertEquals(1L, result.preview.counts.credentials)
        assertEquals(1L, result.preview.counts.cookies)
        assertEquals(1L, result.preview.counts.repositories)
        assertEquals(1L, result.preview.counts.repositoryEntries)
        assertEquals(3, result.preview.quarantinedPlugins.size)
        assertEquals(56L, result.preview.counts.totalPluginScriptBytes)
        assertEquals(40L, result.preview.counts.uniquePluginScriptBytes)
        assertEquals(1L, result.preview.secrets.credentialCount)
        assertEquals(1L, result.preview.secrets.cookieCount)
        assertFalse(result.preview.secrets.automaticImportAllowed)
        assertEquals(
            setOf("txt-1", "epub-1", "remote-1"),
            result.preview.bookSummaries.map { it.id }.toSet(),
        )
        assertEquals(
            setOf(
                "installedPlugins.manifest.script",
                "pluginInstallations.plugin.script",
                "pluginInstallations.script",
            ),
            result.preview.quarantinedPlugins.map { it.origin }.toSet(),
        )
    }

    @Test
    fun unsupportedVersionFixtureFailsClosed() {
        val result = inspectFixture("unsupported-version.json")

        assertFalse(result.accepted)
        assertFalse(result.canStage)
        assertEquals(ShuYueBackupV1ErrorCode.UNSUPPORTED_VERSION, result.errorCode)
        assertEquals("version", result.errorPath)
        assertFalse(result.toString().contains("2"))
    }

    @Test
    fun danglingProgressFixtureReportsStableMissingReferenceIssue() {
        val result = inspectFixture("dangling-progress.json")

        assertFalse(result.accepted)
        assertFalse(result.canStage)
        assertEquals(ShuYueBackupV1ErrorCode.VALIDATION_FAILED, result.errorCode)
        assertTrue(
            result.preview.issues.any {
                it.code == ShuYueMigrationIssueCode.MISSING_CHAPTER_REFERENCE
            },
        )
    }

    @Test
    fun maliciousCookieFixtureIsRejectedWithoutLeakingCookieValue() {
        val result = inspectFixture("malicious-cookie.json")

        assertFalse(result.accepted)
        assertFalse(result.canStage)
        assertEquals(ShuYueBackupV1ErrorCode.VALIDATION_FAILED, result.errorCode)
        assertTrue(result.preview.issues.any { it.code == ShuYueMigrationIssueCode.INVALID_COOKIE })
        val rendered = result.toString()
        assertFalse(rendered.contains("do-not-print-this-cookie"))
        assertFalse(rendered.contains("https://evil.example"))
        assertFalse(rendered.contains("//evil.example"))
    }

    private fun inspectFixture(fileName: String): ShuYueMigrationInspectionResult {
        val resourcePath = "shuyue-migration/$fileName"
        val contents = requireNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) {
            "Classpath resource '$resourcePath' was not found"
        }.bufferedReader().use { it.readText() }
        assertTrue(contents.isNotBlank(), "Classpath resource '$resourcePath' is empty")
        return ShuYueBackupV1Inspector.inspect(contents)
    }
}
