package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtensionLibraryBindingV2Test {
    @Test
    fun reversibleLibraryBindingPreservesOpaqueUnicodeIdentity() {
        val sourceKey = SourceKey(
            contractVersion = 2,
            packageId = "zh.bilimanga",
            sourceId = "linovelib/繁體",
        )
        val remoteId = "/novel/1234.html?卷=第一卷"

        val encoded = encodeExtensionLibraryPublicationUrl(sourceKey, remoteId)
        val decoded = decodeExtensionLibraryPublicationUrl(encoded)

        assertEquals(sourceKey, decoded?.sourceKey)
        assertEquals(remoteId, decoded?.remotePublicationId)
        assertEquals(extensionPublicationKey(sourceKey, remoteId), decoded?.publicationKey)
    }

    @Test
    fun malformedOrTamperedLibraryBindingIsRejected() {
        val sourceKey = SourceKey(packageId = "zh.bilimanga", sourceId = "linovelib")
        val encoded = encodeExtensionLibraryPublicationUrl(sourceKey, "book:42")

        assertNull(decodeExtensionLibraryPublicationUrl("https://example.invalid/$encoded"))
        assertNull(decodeExtensionLibraryPublicationUrl(encoded.replace("/2/", "/0/")))
        assertNull(decodeExtensionLibraryPublicationUrl(encoded.dropLast(1) + "G"))
        assertNull(
            decodeExtensionLibraryPublicationUrl(
                encoded.replace(extensionPublicationKey(sourceKey, "book:42").value, ZERO_UUID),
            ),
        )
    }

    private companion object {
        const val ZERO_UUID: String = "00000000-0000-8000-8000-000000000000"
    }
}
