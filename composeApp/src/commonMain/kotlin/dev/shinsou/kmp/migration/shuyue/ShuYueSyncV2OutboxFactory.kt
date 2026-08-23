package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.domain.model.PortableCategoryId
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.sync.v2.CategoryPatch
import dev.shinsou.kmp.sync.v2.ContentPublicationSyncDraftFactory
import dev.shinsou.kmp.sync.v2.ContentReadingProgressSetV2
import dev.shinsou.kmp.sync.v2.ContentSyncFields
import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.PortableSettingPatch
import dev.shinsou.kmp.sync.v2.PublicationCategoryMembershipSetV2
import dev.shinsou.kmp.sync.v2.PublicationPatchV2
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.sync.v2.SyncEntityKey
import dev.shinsou.kmp.sync.v2.SyncEvent
import dev.shinsou.kmp.sync.v2.SyncFields
import dev.shinsou.kmp.sync.v2.SyncMutation
import dev.shinsou.kmp.sync.v2.SyncValue

/**
 * Production schema-v2 ShuYue writer.
 *
 * Draft clocks are deliberately inert placeholders. [ContentSyncOutboxDrainBridge] replaces
 * every clock inside the LocalSyncStore transaction, after observing the durable remote clock.
 * Operation ids remain deterministic so a crash between LocalSyncStore commit and content-outbox
 * acknowledgement is an idempotent replay rather than a duplicate import.
 */
public object ShuYueSyncV2OutboxFactory : ShuYueImportOutboxFactory<SyncDraft> {
    override fun build(plan: ShuYuePortableImportPlan): ShuYueImportOutboxBundle<SyncDraft> {
        val drafts = ArrayList<SyncDraft>()
        val represented = linkedSetOf<ShuYueImportSyncDomain>()

        val categoryMutations = plan.categories
            .sortedBy(ShuYueImportedCategory::categoryId)
            .mapIndexed { index, category ->
                CategoryPatch(
                    key = categoryKey(category.categoryId),
                    fields = mapOf(
                        SyncFields.Category.NAME to SyncValue.StringValue(category.name),
                        SyncFields.Category.SORT to SyncValue.LongValue(index.toLong()),
                        SyncFields.Category.FLAGS to SyncValue.LongValue(0),
                    ),
                )
            }
        if (categoryMutations.isNotEmpty() || plan.categoryMemberships.isNotEmpty()) {
            represented += ShuYueImportSyncDomain.CATEGORIES
            drafts += chunkedDrafts(plan, "categories", categoryMutations)
            drafts += chunkedDrafts(
                plan,
                "category-memberships",
                plan.categoryMemberships.sortedWith(
                    compareBy(ShuYueImportedCategoryMembership::publicationId)
                        .thenBy(ShuYueImportedCategoryMembership::categoryId),
                ).map { membership ->
                    PublicationCategoryMembershipSetV2(
                        publicationKey = SyncEntityKey.publication(membership.publicationId),
                        categoryKey = categoryKey(membership.categoryId),
                        present = true,
                    )
                },
            )
        }

        plan.publications.sortedBy { it.key.value }.forEach { publication ->
            val grants = plan.rightsGrants.filter { grant ->
                grant.scope.publicationId == publication.key
            }
            drafts += ContentPublicationSyncDraftFactory.build(
                publication = publication,
                rightsGrants = grants,
                operationNamespace = "shuyue:${plan.sourceDigestSha256}:${publication.key.value}",
                createdAtMillis = 0,
            ).drafts
            if (publication.key.value in plan.legacyFlattenedPublicationIds) {
                drafts += chunkedDrafts(
                    plan,
                    "legacy-flattened:${publication.key.value}",
                    listOf(
                        PublicationPatchV2(
                            key = SyncEntityKey.publication(publication.key.value),
                            fields = mapOf(
                                ContentSyncFields.Publication.LEGACY_FLATTENED to SyncValue.BooleanValue(true),
                            ),
                        ),
                    ),
                )
            }
        }
        if (plan.publications.isNotEmpty()) represented += ShuYueImportSyncDomain.PUBLICATIONS
        if (plan.blobReferences.isNotEmpty()) represented += ShuYueImportSyncDomain.CONTENT_REFS

        val progressMutations = plan.readingProgress
            .sortedBy { it.locator.scope.unitId.value }
            .map { progress ->
                ContentReadingProgressSetV2(
                    locator = progress.locator,
                    historyTouchedAtEpochMillis = progress.updatedAtEpochMillis,
                )
            }
        if (progressMutations.isNotEmpty()) {
            represented += ShuYueImportSyncDomain.READING_PROGRESS
            drafts += chunkedDrafts(plan, "reading-progress", progressMutations)
        }

        plan.readerSettings?.let { settings ->
            represented += ShuYueImportSyncDomain.READER_SETTINGS
            drafts += chunkedDrafts(
                plan,
                "portable-reader-settings",
                listOf(PortableSettingPatch(portableSettings(settings))),
            )
        }

        val jobs = blobSyncJobs(plan)
        if (jobs.isNotEmpty()) represented += ShuYueImportSyncDomain.CONTENT_BLOBS
        return ShuYueImportOutboxBundle(
            drafts = drafts,
            representedDomains = represented,
            blobSyncJobs = jobs,
        )
    }


    private fun blobSyncJobs(plan: ShuYuePortableImportPlan): List<ContentBlobSyncJobMutation> {
        data class Candidate(
            val blob: BlobRef,
            val owner: ContentManifestOwner,
            val manifestId: String,
            val contentRevision: Long,
            val grantId: dev.shinsou.kmp.rights.RightsGrantRef,
        )

        val required = plan.syncableBlobReferences.associateBy(BlobRef::blobId)
        if (required.isEmpty()) return emptyList()
        val candidates = ArrayList<Candidate>()
        plan.publications.forEach { publication ->
            publication.acquisitions.forEach { acquisition ->
                val grant = acquisition.rightsGrantRef ?: return@forEach
                acquisition.units.forEach { unit ->
                    unit.manifestRevisions.forEach { manifest ->
                        manifest.referencedBlobs.forEach { blob ->
                            if (required[blob.blobId] == blob) {
                                candidates += Candidate(
                                    blob = blob,
                                    owner = ContentManifestOwner(publication.key, acquisition.id, unit.key),
                                    manifestId = manifest.manifestId,
                                    contentRevision = manifest.contentRevision,
                                    grantId = grant,
                                )
                            }
                        }
                    }
                }
            }
        }
        val byBlob = candidates.groupBy { it.blob.blobId }
        require(byBlob.keys == required.keys) { "A syncable ShuYue blob has no exact manifest owner" }
        return required.keys.sorted().map { blobId ->
            val candidate = requireNotNull(byBlob[blobId]).minBy {
                "${it.owner.scopeKey}/${it.manifestId}/${it.contentRevision}"
            }
            ContentBlobSyncJobMutation(
                jobId = stableId(plan, "blob-upload:$blobId"),
                blob = candidate.blob,
                owner = candidate.owner,
                manifestId = candidate.manifestId,
                contentRevision = candidate.contentRevision,
                grantReference = candidate.grantId,
            )
        }
    }

    private fun portableSettings(settings: ShuYueImportedReaderSettings): Map<String, SyncValue> = mapOf(
        "general.languagePreference" to when (settings.language) {
            "SYSTEM" -> SyncValue.NullValue
            "TRADITIONAL_CHINESE" -> SyncValue.StringValue("zh-Hant")
            "SIMPLIFIED_CHINESE" -> SyncValue.StringValue("zh-Hans")
            "JAPANESE" -> SyncValue.StringValue("ja")
            else -> throw IllegalArgumentException("Unsupported staged ShuYue language")
        },
        "appearance.theme" to SyncValue.StringValue(
            when (settings.theme) {
                "SYSTEM" -> "SYSTEM"
                "LIGHT", "PAPER" -> "LIGHT"
                "DARK", "OLED" -> "DARK"
                else -> throw IllegalArgumentException("Unsupported staged ShuYue theme")
            },
        ),
        "appearance.amoledDark" to SyncValue.BooleanValue(settings.theme == "OLED"),
        "appearance.tintColor" to SyncValue.StringValue(settings.accentColor.lowercase()),
        "reader.novelFontSizeSp" to
            SyncValue.DoubleValue(settings.fontSizeSp.coerceIn(12f, 36f).toDouble()),
        "reader.novelLineHeightMultiplier" to SyncValue.DoubleValue(
            (settings.lineHeightPercent / 100.0).coerceIn(1.15, 2.4),
        ),
        "browse.showNsfwSources" to SyncValue.BooleanValue(settings.showNsfwSources),
    )

    private fun categoryKey(categoryId: String): SyncEntityKey =
        if (categoryId == PortableCategoryId.DEFAULT.value) {
            SyncEntityKey.defaultCategory()
        } else {
            SyncEntityKey.category(categoryId)
        }

    private fun chunkedDrafts(
        plan: ShuYuePortableImportPlan,
        label: String,
        mutations: List<SyncMutation>,
    ): List<SyncDraft> = mutations.chunked(MAX_MUTATIONS_PER_DRAFT).mapIndexed { index, chunk ->
        val opId = stableId(plan, "$label:$index")
        SyncDraft(
            draftId = opId,
            event = SyncEvent(opId = opId, hlc = PENDING_HLC, mutations = chunk),
            createdAtMillis = 0,
        )
    }

    private fun stableId(plan: ShuYuePortableImportPlan, label: String): String =
        "shuyue-v2:${Sha256.hex("${plan.sourceDigestSha256.length}:${plan.sourceDigestSha256}|$label".encodeToByteArray())}"

    private val PENDING_HLC: HlcTimestamp = HlcTimestamp(0, 0, "shuyue-import-pending")
    private const val MAX_MUTATIONS_PER_DRAFT: Int = 128
}
