package dev.shinsou.kmp.migration.shuyue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShuYuePreparedImportTest {
    @Test
    fun preparationKeepsBodiesScriptsAndSecretsOpaque() {
        val result = ShuYueImportPreparer.prepare(fixture("valid-v1.json"))
        val prepared = assertNotNull(result.preparedImport)

        assertTrue(result.accepted)
        assertEquals(3L, prepared.preview.counts.books)
        assertEquals(setOf("example-source"), prepared.availableCredentialSourceIds())
        assertEquals(setOf("example-source"), prepared.availableCookieSourceIds())
        val rendered = "$result $prepared"
        assertFalse(rendered.contains("fixture-password-do-not-import"))
        assertFalse(rendered.contains("fixture-cookie-do-not-import"))
        assertFalse(rendered.contains("installed-script"))
        assertFalse(rendered.contains("hello\nworld"))
    }

    @Test
    fun selectionIsDeepSnapshottedAndBoundToLedgerFingerprint() {
        val bytes = fixture("valid-v1.json")
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(bytes).preparedImport)
        val mutableBooks = mutableSetOf("remote-1")
        val mutablePlugins = mutableSetOf(prepared.preview.quarantinedPlugins.first().sha256)
        val selection = ShuYueImportSelection(
            selectedBookIds = mutableBooks,
            includeProgress = true,
            includeReaderSettings = false,
            includeContentBodySync = true,
            quarantinedPluginDigests = mutablePlugins,
        )
        mutableBooks += "txt-1"
        mutablePlugins.clear()

        val resolved = prepared.resolveSelection(selection)
        assertEquals(listOf("remote-1"), resolved.books.map { it.id })
        assertEquals(1, resolved.chapters.size)
        assertEquals(1, resolved.progress.size)
        // A digest selects the immutable script payload, not just the first place it appeared.
        // Preserve every occurrence so provenance remains auditable during quarantine review.
        assertEquals(2, resolved.pluginInstallations.size)
        assertEquals(
            setOf(
                "installedPlugins.manifest.script",
                "pluginInstallations.plugin.script",
            ),
            resolved.pluginInstallations.mapTo(linkedSetOf()) { it.origin },
        )
        assertFalse(resolved.includeReaderSettings)
        assertTrue(resolved.includeContentBodySync)
        assertNotEquals(prepared.resultFingerprintSha256, resolved.ledgerMutation.resultFingerprintSha256)

        val replay = assertNotNull(ShuYueImportPreparer.prepare(bytes).preparedImport)
            .resolveSelection(
                ShuYueImportSelection(
                    selectedBookIds = setOf("remote-1"),
                    includeProgress = true,
                    includeReaderSettings = false,
                    includeContentBodySync = true,
                    quarantinedPluginDigests = setOf(
                        prepared.preview.quarantinedPlugins.first().sha256,
                    ),
                ),
            )
        assertEquals(
            resolved.ledgerMutation.resultFingerprintSha256,
            replay.ledgerMutation.resultFingerprintSha256,
        )
        val metadataOnly = prepared.resolveSelection(
            ShuYueImportSelection(
                selectedBookIds = setOf("remote-1"),
                includeProgress = true,
                includeReaderSettings = false,
                includeContentBodySync = false,
                quarantinedPluginDigests = setOf(prepared.preview.quarantinedPlugins.first().sha256),
            ),
        )
        assertNotEquals(
            resolved.ledgerMutation.resultFingerprintSha256,
            metadataOnly.ledgerMutation.resultFingerprintSha256,
        )
    }

    @Test
    fun explicitSecretConsentIsOnePreparedSnapshotCapability() {
        val bytes = fixture("valid-v1.json")
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(bytes).preparedImport)
        val credentialSelection = mutableSetOf("example-source")
        val cookieSelection = mutableSetOf("example-source")
        val consent = prepared.confirmSecretImport(
            credentialSourceIds = credentialSelection,
            cookieSourceIds = cookieSelection,
            confirmedAtEpochMillis = 10,
        )
        credentialSelection.clear()
        cookieSelection.clear()

        val batch = prepared.secretWriteBatch(consent)
        assertEquals(1, batch.credentials.size)
        assertEquals(1, batch.cookies.size)
        assertEquals("fixture-user", batch.credentials.single().username)
        assertEquals("fixture-password-do-not-import", batch.credentials.single().password)
        assertEquals("fixture-cookie-do-not-import", batch.cookies.single().value)
        assertFalse(batch.toString().contains("fixture-password-do-not-import"))
        assertFalse(consent.toString().contains("example-source"))

        val secondPreparation = assertNotNull(ShuYueImportPreparer.prepare(bytes).preparedImport)
        assertFailsWith<IllegalArgumentException> { secondPreparation.secretWriteBatch(consent) }
    }

    @Test
    fun rejectedInspectionNeverCreatesPreparedCapability() {
        val result = ShuYueImportPreparer.prepare(fixture("malicious-cookie.json"))

        assertFalse(result.accepted)
        assertNull(result.preparedImport)
        assertFalse(result.toString().contains("do-not-print-this-cookie"))
    }

    private fun fixture(fileName: String): ByteArray {
        val resourcePath = "shuyue-migration/$fileName"
        return requireNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) {
            "Classpath resource '$resourcePath' was not found"
        }.use { it.readBytes() }
    }
}
