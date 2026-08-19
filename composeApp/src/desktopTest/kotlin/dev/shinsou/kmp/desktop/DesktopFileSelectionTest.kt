package dev.shinsou.kmp.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopFileSelectionTest {
    @Test
    fun pickerResultsAreValidatedAfterNativeSelection() {
        val files = listOf(
            Path.of("/selected/volume.cbz"),
            Path.of("/selected/notes.txt"),
            Path.of("/selected/ARCHIVE.PDF"),
            Path.of("/selected/no-extension"),
        )

        val accepted = filterAcceptedFileSelections(files, setOf(".cbz", "pdf"))

        assertEquals(listOf(files[0], files[2]), accepted)
    }

    @Test
    fun invalidSingleSelectionIsRejectedInsteadOfBeingRead() {
        val accepted = filterAcceptedFileSelections(
            files = listOf(Path.of("/selected/not-a-backup.exe")),
            acceptedExtensions = setOf("json"),
        )

        assertTrue(accepted.isEmpty())
    }

    @Test
    fun unrestrictedPickerPreservesAllSelections() {
        val files = listOf(Path.of("/selected/one"), Path.of("/selected/two.anything"))

        assertEquals(files, filterAcceptedFileSelections(files, emptySet()))
    }

    @Test
    fun desktopSecurityExplanationsDoNotClaimTheHostIsMacOs() {
        val services = DesktopAppServices(
            closeApplication = {},
            platform = DesktopPlatform.WINDOWS,
        )

        assertEquals(
            "Device authentication is unavailable on this platform.",
            services.securityCapabilities.appLock.unavailableReason,
        )
        assertEquals(
            "Secure-screen protection is unavailable on this platform.",
            services.securityCapabilities.secureScreen.unavailableReason,
        )
    }
}
