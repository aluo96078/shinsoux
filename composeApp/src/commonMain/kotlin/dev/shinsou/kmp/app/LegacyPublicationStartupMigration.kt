package dev.shinsou.kmp.app

import dev.shinsou.kmp.content.ContentAliasMutation
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitResult
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentMigrationLedgerMutation
import dev.shinsou.kmp.content.ContentMigrationLookupStatus
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.asContentAliasMutation
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.InMemoryPortableAliasLedger
import dev.shinsou.kmp.domain.model.LegacyMangaCompatibilityFacetV1
import dev.shinsou.kmp.domain.model.LegacyPublicationBundle
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.local.isTypedLocalCompatibilityUrl
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.v2.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.sync.v2.SyncDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public enum class LegacyPublicationMigrationStatus {
    UP_TO_DATE,
    MIGRATED,
    REPAIRED_AFTER_PORTABLE_REPLACEMENT,
}

public data class LegacyPublicationMigrationResult(
    val status: LegacyPublicationMigrationStatus,
    val publicationCount: Int,
    val aliasCount: Int,
    val sourceDigestSha256: String? = null,
    val resultFingerprintSha256: String? = null,
    val commit: ContentCommitResult? = null,
)

/**
 * Durable, versioned M1 migration from the body-free AppSnapshot compatibility graph.
 *
 * Projection uses a fresh deterministic resolver so merely inspecting a snapshot performs no SQL
 * write. The derived alias bindings, typed Publications and migration ledger then enter one shared
 * transaction. A crash before its commit therefore leaves no partial alias/publication authority.
 *
 * Existing typed rows are immutable. Later legacy snapshots may append newly discovered Chapters,
 * but ordinary legacy metadata/progress remains owned by AppSnapshot until the UI switches to the
 * typed projection. Local typed compatibility rows are excluded because their authoritative graph
 * already exists and projecting them again would create a second Publication.
 */
public class LegacyPublicationStartupMigration(
    private val foundation: ContentFoundationRuntime,
) {
    public fun migrate(snapshot: AppSnapshot): LegacyPublicationMigrationResult {
        val source = snapshot.legacyProjectionSource()
        val bundles = LegacyPublicationProjection(InMemoryPortableAliasLedger()).project(
            source.toProjectionSnapshot(),
        )
        // `PublicationStore.find` is a strong restart-safe read and hydrates the complete SQL
        // transaction state. Snapshot it once so a large legacy library does not become O(n²).
        val durablePublications = foundation.publications.all().associateBy(Publication::key)
        val candidates = bundles.mapNotNull { bundle ->
            candidateGraph(bundle, durablePublications[bundle.publication.key])
        }
            .sortedBy { it.key.value }
        if (candidates.isEmpty()) {
            return LegacyPublicationMigrationResult(
                status = LegacyPublicationMigrationStatus.UP_TO_DATE,
                publicationCount = 0,
                aliasCount = 0,
            )
        }

        val candidateKeys = candidates.mapTo(hashSetOf()) { it.key }
        val aliases = bundles
            .filter { it.publication.key in candidateKeys }
            .flatMap(LegacyPublicationBundle::aliases)
            .map { it.asContentAliasMutation() }
            .distinctExactAliases()
            .sortedBy(ContentAliasMutation::alias)
        val sourceDigest = Sha256.hex(
            MIGRATION_JSON.encodeToString(source).encodeToByteArray(),
        )
        val resultFingerprint = Sha256.hex(
            MIGRATION_JSON.encodeToString(
                LegacyProjectionResultV1(
                    publications = candidates,
                    aliases = aliases,
                ),
            ).encodeToByteArray(),
        )
        val ledger = ContentMigrationLedgerMutation(
            namespace = MIGRATION_NAMESPACE,
            sourceDigestSha256 = sourceDigest,
            resultFingerprintSha256 = resultFingerprint,
        )
        val lookup = foundation.transactions.lookupMigrationLedger(
            ledger.namespace,
            ledger.sourceDigestSha256,
            ledger.resultFingerprintSha256,
        )
        check(lookup.status != ContentMigrationLookupStatus.CONFLICT) {
            "The legacy AppSnapshot migration source is already bound to a different typed graph"
        }

        val repairingPortableReplacement = lookup.status == ContentMigrationLookupStatus.REPLAY
        val commitId = if (repairingPortableReplacement) {
            // Backup v2 intentionally retains the device-local migration ledger while replacing
            // the portable publication graph. Repository revision changes at that restore CAS, so
            // a deterministic repair id cannot be mistaken for the pre-restore migration commit.
            "legacy-snapshot-repair-v$MIGRATION_VERSION:${snapshot.revision}:$resultFingerprint"
        } else {
            ledger.commitId
        }
        val commit = foundation.transactions.commit(
            ContentCommitBatch<SyncDraft>(
                commitId = commitId,
                aliases = aliases,
                migrations = if (repairingPortableReplacement) emptyList() else listOf(ledger),
                publications = candidates.map(::ContentPublicationMutation),
            ),
        )
        return LegacyPublicationMigrationResult(
            status = if (repairingPortableReplacement) {
                LegacyPublicationMigrationStatus.REPAIRED_AFTER_PORTABLE_REPLACEMENT
            } else {
                LegacyPublicationMigrationStatus.MIGRATED
            },
            publicationCount = candidates.size,
            aliasCount = aliases.size,
            sourceDigestSha256 = sourceDigest,
            resultFingerprintSha256 = resultFingerprint,
            commit = commit,
        )
    }

    private fun candidateGraph(
        bundle: LegacyPublicationBundle,
        durable: Publication?,
    ): Publication? {
        val expected = bundle.publication
        if (durable == null) return expected
        val expectedAcquisition = expected.acquisitions.singleOrNull()
            ?: error("A legacy publication migration must contain exactly one acquisition")
        val durableAcquisition = durable.acquisitions.singleOrNull {
            it.id == expectedAcquisition.id
        } ?: error("A legacy Publication identity collided with a non-legacy acquisition graph")
        requireSameLegacyIdentity(durableAcquisition, expectedAcquisition)

        val durableUnits = durableAcquisition.units.associateBy(PublicationUnit::key)
        expectedAcquisition.units.forEach { expectedUnit ->
            durableUnits[expectedUnit.key]?.let { durableUnit ->
                val durableFacet = requireNotNull(durableUnit.legacyCompatibilityFacet) {
                    "A legacy Unit identity collided with a non-legacy unit"
                }
                val expectedFacet = requireNotNull(expectedUnit.legacyCompatibilityFacet)
                check(durableFacet.namespace == expectedFacet.namespace &&
                    durableFacet.record.id == expectedFacet.record.id &&
                    durableFacet.record.mangaId == expectedFacet.record.mangaId &&
                    durableFacet.parentSource == expectedFacet.parentSource) {
                    "A legacy Unit identity was rebound to different source data"
                }
            }
        }
        val missingUnits = expectedAcquisition.units.filter { it.key !in durableUnits }
        if (missingUnits.isEmpty()) return null

        val extendedAcquisition = durableAcquisition.copy(
            units = durableAcquisition.units + missingUnits,
            contentRevision = durableAcquisition.contentRevision + 1,
        )
        return durable.copy(
            acquisitions = durable.acquisitions.map { acquisition ->
                if (acquisition.id == extendedAcquisition.id) extendedAcquisition else acquisition
            },
        )
    }

    private fun requireSameLegacyIdentity(durable: Acquisition, expected: Acquisition) {
        val durableFacet = requireNotNull(durable.legacyCompatibilityFacet) {
            "A legacy Publication identity collided with a non-legacy publication"
        }
        val expectedFacet = requireNotNull(expected.legacyCompatibilityFacet)
        check(durableFacet.sameIdentityAs(expectedFacet)) {
            "A legacy Publication identity was rebound to different source data"
        }
    }

    private companion object {
        const val MIGRATION_VERSION: Int = 1
        const val MIGRATION_NAMESPACE: String = "legacy-app-snapshot-v1"
        val MIGRATION_JSON: Json = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

private fun LegacyMangaCompatibilityFacetV1.sameIdentityAs(
    other: LegacyMangaCompatibilityFacetV1,
): Boolean = namespace == other.namespace &&
    record.id == other.record.id &&
    record.source == other.record.source

private fun List<ContentAliasMutation>.distinctExactAliases(): List<ContentAliasMutation> {
    val unique = linkedMapOf<String, ContentAliasMutation>()
    forEach { mutation ->
        unique[mutation.alias]?.let { existing ->
            require(existing == mutation) { "One legacy alias resolved to conflicting portable identities" }
        }
        unique[mutation.alias] = mutation
    }
    return unique.values.toList()
}

private fun AppSnapshot.legacyProjectionSource(): LegacyProjectionSourceV1 {
    val typedMangaIds = mangas
        .filter {
            isTypedLocalCompatibilityUrl(it.url) ||
                decodeExtensionLibraryPublicationUrl(it.url) != null
        }
        .mapTo(hashSetOf(), Manga::id)
        .apply {
            chapters
                .filter { isTypedLocalCompatibilityUrl(it.url) }
                .mapTo(this, Chapter::mangaId)
        }
    return LegacyProjectionSourceV1(
        schemaVersion = LegacyProjectionSourceV1.CURRENT_SCHEMA_VERSION,
        mangas = mangas.filterNot { it.id in typedMangaIds },
        chapters = chapters.filterNot { it.mangaId in typedMangaIds },
        categories = categories,
        mangaCategories = mangaCategories.filterNot { it.mangaId in typedMangaIds },
    )
}

@Serializable
private data class LegacyProjectionSourceV1(
    val schemaVersion: Int,
    val mangas: List<Manga>,
    val chapters: List<Chapter>,
    val categories: List<Category>,
    val mangaCategories: List<MangaCategory>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION)
    }

    fun toProjectionSnapshot(): AppSnapshot = AppSnapshot(
        mangas = mangas,
        chapters = chapters,
        categories = categories,
        mangaCategories = mangaCategories,
    ).validate()

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
private data class LegacyProjectionResultV1(
    val publications: List<Publication>,
    val aliases: List<ContentAliasMutation>,
)
