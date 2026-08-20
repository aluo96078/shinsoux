package dev.shinsou.kmp.sync.v2

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SyncCapabilitiesCompatibilityTest {
    @Test
    fun protocolAndSchemaRangesAreNegotiatedIndependently() {
        capabilities().requireCompatible(
            protocolReaderVersion = 3,
            protocolWriterVersion = 2,
            schemaReaderVersion = 7,
            schemaWriterVersion = 5,
        )

        assertFailsWith<IllegalArgumentException> {
            capabilities().requireCompatible(2, 2, 7, 5)
        }
        assertFailsWith<IllegalArgumentException> {
            capabilities().requireCompatible(3, 2, 6, 5)
        }
    }

    @Test
    fun futureWritersAndInvalidServerRangesFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            capabilities().requireCompatible(3, 4, 7, 5)
        }
        assertFailsWith<IllegalArgumentException> {
            capabilities().requireCompatible(3, 2, 7, 8)
        }
        assertFailsWith<IllegalArgumentException> {
            capabilities(minSchemaWriterVersion = 8).requireCompatible(3, 2, 7, 5)
        }
        assertFailsWith<IllegalArgumentException> {
            capabilities(minReaderVersion = 4).requireCompatible(3, 2, 7, 5)
        }
    }

    private fun capabilities(
        minReaderVersion: Int = 1,
        minSchemaWriterVersion: Int = 5,
    ) = SyncCapabilities(
        protocolVersion = 3,
        minReaderVersion = minReaderVersion,
        minWriterVersion = 2,
        schemaVersion = 7,
        minSchemaReaderVersion = 4,
        minSchemaWriterVersion = minSchemaWriterVersion,
        realtimeAvailable = true,
        maxEventBytes = 32 * 1024,
        maxBatchBytes = 256 * 1024,
        maxCheckpointBytes = 32 * 1024 * 1024,
    )
}
