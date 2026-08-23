package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ExtensionPublicationIdentityTest {
    @Test
    fun publicationIdentityIsStableAndSourceScoped() {
        val source = SourceKey(
            packageId = "zh.wenku8",
            sourceId = "zh.wenku8",
        )
        val same = extensionPublicationKey(source, "12345")
        assertEquals(same, extensionPublicationKey(source, "12345"))
        assertNotEquals(same, extensionPublicationKey(source, "12346"))
        assertNotEquals(
            same,
            extensionPublicationKey(source.copy(packageId = "zh.wenku8.api"), "12345"),
        )
    }
}
