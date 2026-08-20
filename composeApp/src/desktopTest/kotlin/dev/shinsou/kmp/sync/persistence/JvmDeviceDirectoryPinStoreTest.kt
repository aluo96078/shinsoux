package dev.shinsou.kmp.sync.persistence

import dev.shinsou.kmp.sync.trust.PinnedDeviceDirectory
import dev.shinsou.kmp.sync.trust.PinnedDeviceIdentity
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmDeviceDirectoryPinStoreTest {
    @Test
    fun atomicFileAdapterSurvivesStoreRecreationAndRejectsCorruption() = runTest {
        val directory = Files.createTempDirectory("shinsou-directory-pin-test")
        val path = directory.resolve("device-directory-pins.json")
        try {
            val expected = pin()
            val first = createJvmFileDeviceDirectoryPinStore(path)
            first.compareAndSet(WORKSPACE, null, expected)
            assertEquals(expected, createJvmFileDeviceDirectoryPinStore(path).load(WORKSPACE))

            Files.writeString(path, "{malformed")
            assertFailsWith<SyncMetadataCorruptException> {
                createJvmFileDeviceDirectoryPinStore(path).load(WORKSPACE)
            }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun pin() = PinnedDeviceDirectory(
        workspaceId = WORKSPACE,
        version = 1,
        hash = "A".repeat(43),
        allDeviceCount = 1,
        devices = listOf(
            PinnedDeviceIdentity(
                deviceId = DEVICE,
                userId = USER,
                displayName = "Desktop",
                platform = "macos",
                signingPublicKey = "A".repeat(43),
                wrappingPublicKey = "Q".repeat(43),
                status = "active",
                authEpoch = 1,
                createdAt = 1,
                attestationSha256 = "g".repeat(43),
            ),
        ),
    )

    private companion object {
        const val WORKSPACE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val USER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val DEVICE = "11111111-1111-4111-8111-111111111111"
    }
}
