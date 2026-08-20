package dev.shinsou.kmp.sync.provisioning

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvisioningCapabilitiesCompatibilityTest {
    @Test
    fun provisioningChecksBothIndependentVersionRanges() {
        val capabilities = ProvisioningCapabilities(
            instanceId = "00000000-0000-4000-8000-000000000001",
            protocolVersion = 3,
            minReaderVersion = 1,
            minWriterVersion = 2,
            schemaVersion = 7,
            minSchemaReaderVersion = 4,
            minSchemaWriterVersion = 5,
            realtime = true,
        )

        assertTrue(capabilities.isCompatibleWith(3, 2, 7, 5))
        assertFalse(capabilities.isCompatibleWith(2, 2, 7, 5))
        assertFalse(capabilities.isCompatibleWith(3, 2, 6, 5))
        assertFalse(capabilities.isCompatibleWith(3, 4, 7, 5))
        assertFalse(capabilities.isCompatibleWith(3, 2, 7, 8))
    }
}
