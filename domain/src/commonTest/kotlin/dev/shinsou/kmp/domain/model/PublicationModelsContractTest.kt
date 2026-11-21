package dev.shinsou.kmp.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PublicationModelsContractTest {
    @Test
    fun sourceKeyKeepsOpaqueStringAndSignedLegacyId() {
        val key = SourceKey(
            contractVersion = 2,
            packageId = "example.package",
            sourceId = "source:α/42",
            legacyLongId = -42,
        )

        assertEquals("source:α/42", key.opaqueId)
        assertEquals(-42, key.legacyLongId)
        assertEquals(key, SourceKey(2, "example.package", "source:α/42", -42))
        val withoutLegacyProjection = SourceKey(2, "example.package", "source:α/42")
        assertEquals(key, withoutLegacyProjection)
        assertEquals(key.hashCode(), withoutLegacyProjection.hashCode())
        assertEquals(
            SourceBinding(key, "book").id,
            SourceBinding(withoutLegacyProjection, "book").id,
        )
    }

    @Test
    fun publicationAndUnitKeysAreIndependentPortableUuids() {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        val unit = UnitKey(publication, "22222222-2222-4222-8222-222222222222")
        assertEquals(publication, unit.publicationKey)
        assertFailsWith<IllegalArgumentException> { PublicationKey("remote-123") }
        assertFailsWith<IllegalArgumentException> {
            UnitKey(PublicationKey("11111111-1111-4111-8111-111111111111"), "remote-123")
        }
    }

    @Test
    fun localAcquisitionDoesNotRequireSourceBinding() {
        val publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111")
        val acquisition = Acquisition(
            id = "33333333-3333-4333-8333-333333333333",
            origin = AcquisitionOrigin.LocalPackage(LocalPackageKind.CBZ),
        )
        val publication = Publication(publicationKey, "Imported", acquisitions = listOf(acquisition))
        publication.validate()
    }

    @Test
    fun unitRemoteIdentityRequiresSameSourceParentPublicationContext() {
        val source = SourceKey(2, "package", "source")
        val parent = RemoteEntityKey(1, source, RemoteEntityKind.PUBLICATION, "book-1", "book-1")
        val unit = RemoteEntityKey(
            1,
            source,
            RemoteEntityKind.UNIT,
            "chapter-1",
            "chapter-1",
            parentPublication = parent,
        )
        assertEquals(parent, unit.parentPublication)

        assertFailsWith<IllegalArgumentException> {
            RemoteEntityKey(1, source, RemoteEntityKind.UNIT, "chapter-1", "chapter-1")
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteEntityKey(
                1,
                SourceKey(2, "package", "other-source"),
                RemoteEntityKind.UNIT,
                "chapter-1",
                "chapter-1",
                parentPublication = parent,
            )
        }
    }

    @Test
    fun remoteIdentityRejectsCallerSuppliedCanonicalContradictionAndVersionsBindingId() {
        val source = SourceKey(2, "package", "source")
        assertFailsWith<IllegalArgumentException> {
            RemoteEntityKey(1, source, RemoteEntityKind.PUBLICATION, "raw", "different")
        }

        val v1 = SourceBinding(source, "book", keyVersion = 1)
        val v2 = SourceBinding(source, "book", keyVersion = 2)
        assertNotEquals(v1.id, v2.id)
    }

    @Test
    fun extensionAcquisitionRequiresUnitsFromItsExactSourceAndRemoteParent() {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        val source = SourceKey(2, "package", "source")
        val otherSource = SourceKey(2, "package", "other")
        val parent = RemoteEntityKey(1, source, RemoteEntityKind.PUBLICATION, "book", "book")
        val otherParent = RemoteEntityKey(1, source, RemoteEntityKind.PUBLICATION, "other-book", "other-book")
        val unitKey = UnitKey(publication, "22222222-2222-4222-8222-222222222222")

        fun acquisition(unitBinding: SourceBinding?) = Acquisition(
            id = "33333333-3333-4333-8333-333333333333",
            origin = AcquisitionOrigin.ExtensionSource(SourceBinding(parent)),
            units = listOf(PublicationUnit(unitKey, "Chapter", sourceBinding = unitBinding)),
        )

        assertFailsWith<IllegalArgumentException> { acquisition(null) }
        assertFailsWith<IllegalArgumentException> {
            acquisition(
                SourceBinding(
                    RemoteEntityKey(
                        1,
                        otherSource,
                        RemoteEntityKind.UNIT,
                        "chapter",
                        "chapter",
                        RemoteEntityKey(1, otherSource, RemoteEntityKind.PUBLICATION, "book", "book"),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acquisition(
                SourceBinding(
                    RemoteEntityKey(1, source, RemoteEntityKind.UNIT, "chapter", "chapter", otherParent),
                ),
            )
        }

        acquisition(
            SourceBinding(RemoteEntityKey(1, source, RemoteEntityKind.UNIT, "chapter", "chapter", parent)),
        ).validate()
    }
}
