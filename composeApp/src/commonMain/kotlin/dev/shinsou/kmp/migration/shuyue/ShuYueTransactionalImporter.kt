package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.content.AuxiliaryBlobAttachment
import dev.shinsou.kmp.content.AuxiliaryBlobPurpose
import dev.shinsou.kmp.content.BlobPublishReceipt
import dev.shinsou.kmp.content.ContentAliasMutation
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitResult
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentMetadataMutation
import dev.shinsou.kmp.content.ContentMigrationLookupStatus
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.ContentQuarantineMutation
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.UnrepresentableDraftPolicy
import dev.shinsou.kmp.content.access.ContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.content.access.PendingContentBodyStoreRequest
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.RemoteEntityKey
import dev.shinsou.kmp.domain.model.RemoteEntityKind
import dev.shinsou.kmp.domain.model.SourceBinding
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedPluginCatalogV2
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public enum class ShuYueImportSyncDomain {
    PUBLICATIONS,
    CONTENT_REFS,
    CONTENT_BLOBS,
    CATEGORIES,
    READING_PROGRESS,
    READER_SETTINGS,
}

/** Outbox drafts and an explicit proof of what the current v2 writer represented. */
public data class ShuYueImportOutboxBundle<D : Any>(
    val drafts: List<D>,
    val representedDomains: Set<ShuYueImportSyncDomain>,
    val blobSyncJobs: List<ContentBlobSyncJobMutation> = emptyList(),
)

public fun interface ShuYueImportOutboxFactory<D : Any> {
    public fun build(plan: ShuYuePortableImportPlan): ShuYueImportOutboxBundle<D>
}

@Serializable
public data class ShuYueImportedCategory(
    val categoryId: String,
    val name: String,
)

@Serializable
public data class ShuYueImportedCategoryMembership(
    val publicationId: String,
    val categoryId: String,
) {
    init {
        require(dev.shinsou.kmp.domain.model.PublicationKey.isPortableUuid(publicationId))
        require(dev.shinsou.kmp.domain.model.PublicationKey.isPortableUuid(categoryId))
    }
}

@Serializable
public data class ShuYueImportedReadingProgress(
    val locator: ReadingLocator.Text,
    val updatedAtEpochMillis: Long,
)

@Serializable
public data class ShuYueImportedReaderSettings(
    val language: String,
    val fontSizeSp: Float,
    val lineHeightPercent: Int,
    val pageChars: Int,
    val theme: String,
    val accentColor: String,
    val volumeKeysEnabled: Boolean,
    val volumeUpAction: String,
    val volumeDownAction: String,
    val keepScreenOn: Boolean,
    val syncOnLaunch: Boolean,
    val appLockEnabled: Boolean,
    val secureScreen: Boolean,
    val incognitoMode: Boolean,
    val showNsfwSources: Boolean,
    val imageParsingEnabled: Boolean,
    val showPluginErrors: Boolean,
)

/** Safe, body-free plan supplied to the active sync-v2 outbox writer. */
@Serializable
public data class ShuYuePortableImportPlan(
    val sourceDigestSha256: String,
    val publications: List<Publication>,
    val categories: List<ShuYueImportedCategory>,
    val categoryMemberships: List<ShuYueImportedCategoryMembership> = emptyList(),
    val readingProgress: List<ShuYueImportedReadingProgress>,
    val readerSettings: ShuYueImportedReaderSettings?,
    val blobReferences: List<dev.shinsou.kmp.content.BlobRef>,
    val syncableBlobReferences: List<dev.shinsou.kmp.content.BlobRef>,
    /** Body-free host grants required to materialize the publication on another device. */
    val rightsGrants: List<RightsGrant>,
    val legacyFlattenedPublicationIds: Set<String>,
) {
    init {
        require(SHA256_HEX.matches(sourceDigestSha256)) { "ShuYue import source digest is invalid" }
        require(publications.map { it.key }.distinct().size == publications.size)
        publications.forEach(Publication::validate)
        require(categories.map(ShuYueImportedCategory::categoryId).distinct().size == categories.size)
        require(categoryMemberships.distinct().size == categoryMemberships.size)
        require(categoryMemberships.all { membership ->
            publications.any { it.key.value == membership.publicationId } &&
                categories.any { it.categoryId == membership.categoryId }
        })
        require(readingProgress.map { it.locator.scope }.distinct().size == readingProgress.size)
        require(blobReferences.map { it.blobId }.distinct().size == blobReferences.size)
        require(syncableBlobReferences.all { it in blobReferences })
        require(rightsGrants.map(RightsGrant::grantId).distinct().size == rightsGrants.size)
        rightsGrants.forEach(RightsGrant::validate)
    }
}

public data class ShuYueTransactionalImportResult(
    val commit: ContentCommitResult,
    val publicationCount: Int,
    val unitCount: Int,
    val contentBlobCount: Int,
    val quarantineCount: Int,
    val categoryCount: Int,
    val progressCount: Int,
) {
    public val replayed: Boolean get() = commit.replayed
}

public class ShuYueMigrationConflictException : IllegalStateException(
    "This ShuYue backup digest was already imported with a different selection or mapping",
)

/**
 * The production import boundary. All body receipts, complete manifests, publications, grants,
 * quarantine rows, migration aliases/ledger and active-sync drafts enter one shared transaction.
 */
public class ShuYueTransactionalImporter<D : Any>(
    private val blobStore: ContentBlobStore,
    private val transactionStore: SharedContentTransactionStore<D>,
    private val syncActive: () -> Boolean,
    private val outboxFactory: ShuYueImportOutboxFactory<D>,
    private val offlineStoreAuthorizer: ContentBodyOfflineStoreAuthorizer,
) {
    private val stagedReceiptsByCommit = mutableMapOf<String, MutableMap<String, BlobPublishReceipt>>()

    public fun import(
        prepared: ShuYuePreparedImport,
        selection: ShuYueImportSelection = ShuYueImportSelection(),
    ): ShuYueTransactionalImportResult {
        val resolved = prepared.resolveSelection(selection)
        val ledger = resolved.ledgerMutation
        val lookup = transactionStore.lookupMigrationLedger(
            ledger.namespace,
            ledger.sourceDigestSha256,
            ledger.resultFingerprintSha256,
        )
        when (lookup.status) {
            ContentMigrationLookupStatus.CONFLICT -> throw ShuYueMigrationConflictException()
            ContentMigrationLookupStatus.REPLAY -> {
                stagedReceiptsByCommit.remove(ledger.commitId)
                val replay = transactionStore.commit(
                    ContentCommitBatch(
                        commitId = ledger.commitId,
                        migrations = listOf(ledger),
                    ),
                )
                return ShuYueTransactionalImportResult(
                    commit = replay,
                    publicationCount = resolved.books.size,
                    unitCount = resolved.chapters.size,
                    contentBlobCount = resolved.chapters.size,
                    quarantineCount = resolved.pluginInstallations.size,
                    categoryCount = selectedCategories(prepared, resolved).size,
                    progressCount = resolved.progress.size,
                )
            }
            ContentMigrationLookupStatus.MISSING -> Unit
        }

        val chaptersByBook = resolved.chapters.groupBy(ShuYueStagedChapter::bookId)
        val publicationPlans = resolved.books.map { book ->
            buildPublicationPlan(book, chaptersByBook[book.id].orEmpty())
        }
        // Authorize the complete selection before the first body is published. A denial in a later
        // book therefore cannot leave earlier content bodies staged as an avoidable orphan.
        publicationPlans.flatMap(ShuYuePublicationImportPlan::bodies)
            .forEach { body -> offlineStoreAuthorizer.requireAllowed(body.authorization) }

        val stagedReceipts = stagedReceiptsByCommit.getOrPut(ledger.commitId) { mutableMapOf() }
        val receipts = ArrayList<BlobPublishReceipt>()
        val attachments = ArrayList<ManifestAttachment>()
        val rights = ArrayList<ContentRightsGrantMutation>()
        val publications = publicationPlans.map { plan ->
            buildPublication(plan, receipts, attachments, rights, stagedReceipts)
        }
        val quarantines = resolved.pluginInstallations.map { staged ->
            val quarantineId = quarantineId(ledger.sourceDigestSha256, staged)
            val cacheKey = "quarantine:$quarantineId"
            val receipt = stagedReceipts[cacheKey] ?: blobStore.put(
                staged.script.encodeToByteArray(),
                ContentQuarantineMutation.QUARANTINED_SCRIPT_MEDIA_TYPE,
            ).also { stagedReceipts[cacheKey] = it }
            receipts += receipt
            val mutation = ContentQuarantineMutation(
                quarantineId = quarantineId,
                packageId = staged.id,
                version = staged.version,
                versionCode = staged.versionCode,
                sourceIds = staged.sourceIds,
                origin = staged.origin,
                ordinal = staged.ordinal,
                scriptBlob = receipt.reference,
                enabledHint = staged.enabled,
                installedAtEpochMillis = staged.installedAt,
            )
            mutation to AuxiliaryBlobAttachment(
                ownerId = mutation.auxiliaryOwnerId,
                purpose = AuxiliaryBlobPurpose.PLUGIN_QUARANTINE,
                blobs = listOf(receipt.reference),
            )
        }
        val categories = selectedCategories(prepared, resolved)
        val progress = resolved.progress.map { staged ->
            ShuYueImportedReadingProgress(
                locator = staged.locator.copy(
                    scope = staged.locator.scope.copy(contentRevision = IMPORTED_CONTENT_REVISION),
                ),
                updatedAtEpochMillis = staged.updatedAt,
            )
        }
        val settings = prepared.staged.session.readerSettings
            .takeIf { resolved.includeReaderSettings }
            ?.toPortableSettings()
        // Quarantined scripts are intentionally not portable sync references. Only immutable
        // publication resources enter the metadata/body planes.
        val allBlobs = attachments.flatMap { it.blobs }.distinctBy { it.blobId }
        val syncableBlobs = if (resolved.includeContentBodySync) {
            val syncablePublicationIds = rights
                .filter { ContentOperation.SYNC_BLOB in it.grant.allowedOperations }
                .mapTo(hashSetOf()) { it.grant.scope.publicationId }
            attachments.filter { it.owner.publicationKey in syncablePublicationIds }
                .flatMap { it.blobs }
                .distinctBy { it.blobId }
        } else {
            emptyList()
        }
        val plan = ShuYuePortableImportPlan(
            sourceDigestSha256 = ledger.sourceDigestSha256,
            publications = publications,
            categories = categories,
            categoryMemberships = resolved.books.map { book ->
                ShuYueImportedCategoryMembership(
                    publicationId = ShuYueReadingLocatorMapper.publicationId(book).value,
                    categoryId = ShuYueReadingLocatorMapper.portableCategoryId(book.category).value,
                )
            }.distinct(),
            readingProgress = progress,
            readerSettings = settings,
            blobReferences = allBlobs,
            syncableBlobReferences = syncableBlobs,
            rightsGrants = rights.map(ContentRightsGrantMutation::grant),
            legacyFlattenedPublicationIds = resolved.books
                .filter { it.origin == ORIGIN_LOCAL_EPUB }
                .mapTo(linkedSetOf()) { ShuYueReadingLocatorMapper.publicationId(it).value },
        )
        val outbox = outboxFactory.build(plan)
        if (syncActive()) requireSyncCoverage(plan, outbox)

        val metadata = buildMetadata(plan)
        val aliases = buildAliases(resolved)
        val batch = ContentCommitBatch(
            commitId = ledger.commitId,
            receipts = receipts,
            attachments = attachments,
            metadata = metadata,
            aliases = aliases,
            outbox = outbox.drafts,
            migrations = listOf(ledger),
            publications = publications.map(::ContentPublicationMutation),
            auxiliaryAttachments = quarantines.map { it.second },
            quarantines = quarantines.map { it.first },
            rightsGrants = rights,
            blobSyncJobs = outbox.blobSyncJobs,
            unrepresentableDraftPolicy = UnrepresentableDraftPolicy.REJECT,
        )
        val commit = transactionStore.commit(batch)
        stagedReceiptsByCommit.remove(ledger.commitId)
        return ShuYueTransactionalImportResult(
            commit = commit,
            publicationCount = publications.size,
            unitCount = attachments.size,
            contentBlobCount = attachments.sumOf { it.blobs.size },
            quarantineCount = quarantines.size,
            categoryCount = categories.size,
            progressCount = progress.size,
        )
    }

    public suspend fun importSecrets(
        prepared: ShuYuePreparedImport,
        consent: ShuYueSecretImportConsent,
        secretStore: ShuYueMigrationSecretStore,
    ): ShuYueSecretImportResult {
        require(secretStore.protectedAtRest) { "ShuYue secrets require a protected-at-rest store" }
        val batch = prepared.secretWriteBatch(consent)
        secretStore.replaceAtomically(batch)
        return ShuYueSecretImportResult(batch.credentials.size, batch.cookies.size)
    }

    private fun buildPublication(
        plan: ShuYuePublicationImportPlan,
        receipts: MutableList<BlobPublishReceipt>,
        attachments: MutableList<ManifestAttachment>,
        rights: MutableList<ContentRightsGrantMutation>,
        stagedReceipts: MutableMap<String, BlobPublishReceipt>,
    ): Publication {
        val book = plan.book
        val publicationKey = plan.publicationKey
        val acquisitionId = plan.acquisitionId
        val publicationBinding = book.remotePublicationBinding()
        val grant = plan.grant
        rights += ContentRightsGrantMutation(grant)
        val units = plan.bodies.map { body ->
            val chapter = body.chapter
            val unitKey = body.unitKey
            val receipt = stagedReceipts[body.cacheKey] ?: offlineStoreAuthorizer.execute(body.authorization) {
                blobStore.put(body.bytes, "text/plain")
            }.also { stagedReceipts[body.cacheKey] = it }
            receipts += receipt
            val resource = ResourceRef(
                id = ShuYueReadingLocatorMapper.resourceId(book, chapter.id),
                blob = receipt.reference,
            )
            val manifest = ContentManifest(
                manifestId = body.manifestId,
                schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
                contentRevision = IMPORTED_CONTENT_REVISION,
                representations = listOf(
                    ContentRepresentation.PlainText(
                        representationId = ShuYueReadingLocatorMapper.representationId(book, chapter.id),
                        resource = resource,
                        canonicalUtf16Length = chapter.text.length,
                        sourceEncoding = "UTF-8",
                        blocks = listOf(
                            TextBlock(
                                blockId = ShuYueReadingLocatorMapper.DEFAULT_TEXT_BLOCK_ID,
                                startUtf16 = 0,
                                endUtf16 = chapter.text.length,
                            ),
                        ),
                    ),
                ),
                declaredSizeBytes = receipt.reference.byteSize,
            )
            attachments += ManifestAttachment(
                ContentManifestOwner(publicationKey, acquisitionId, unitKey),
                manifest,
            )
            PublicationUnit(
                key = unitKey,
                title = chapter.title,
                manifestRevisions = listOf(manifest),
                sourceBinding = publicationBinding?.let { binding ->
                    SourceBinding(
                        sourceKey = binding.sourceKey,
                        remoteId = chapter.id,
                        canonicalUrl = chapter.href?.takeIf(::isHttpUrl),
                        entityKind = RemoteEntityKind.UNIT,
                        keyVersion = binding.remoteEntityKey.keyVersion,
                        parentPublication = binding.remoteEntityKey,
                    )
                },
                ordinal = chapter.index,
            )
        }
        return Publication(
            key = publicationKey,
            title = book.title,
            acquisitions = listOf(
                Acquisition(
                    id = acquisitionId,
                    origin = when (book.origin) {
                        ORIGIN_LOCAL_TXT -> AcquisitionOrigin.LocalText
                        ORIGIN_LOCAL_EPUB -> AcquisitionOrigin.LocalEpub
                        ORIGIN_REMOTE_PLUGIN -> AcquisitionOrigin.ExtensionSource(
                            requireNotNull(publicationBinding),
                        )
                        else -> error("Unsupported staged ShuYue origin")
                    },
                    units = units,
                    contentRevision = IMPORTED_CONTENT_REVISION,
                    availability = if (book.origin == ORIGIN_LOCAL_EPUB) {
                        AcquisitionAvailability.PARTIAL
                    } else {
                        AcquisitionAvailability.AVAILABLE
                    },
                    rightsGrantRef = grant.grantId,
                    acquiredAtEpochMillis = book.addedAt,
                ),
            ),
            description = book.description,
            authors = listOfNotNull(book.author?.takeIf(String::isNotBlank)),
        )
    }

    private fun buildPublicationPlan(
        book: ShuYueStagedBook,
        chapters: List<ShuYueStagedChapter>,
    ): ShuYuePublicationImportPlan {
        val publicationKey = ShuYueReadingLocatorMapper.publicationId(book)
        val acquisitionId = ShuYueReadingLocatorMapper.acquisitionId(book)
        val grant = importedRightsGrant(book, publicationKey, acquisitionId)
        val bodies = chapters.sortedWith(compareBy(ShuYueStagedChapter::index, ShuYueStagedChapter::id))
            .map { chapter ->
                val unitKey = ShuYueReadingLocatorMapper.unitId(book, chapter.id)
                val manifestId = ShuYueReadingLocatorMapper.manifestId(book, chapter.id)
                val bytes = chapter.text.encodeToByteArray()
                ShuYueContentBodyImportPlan(
                    chapter = chapter,
                    unitKey = unitKey,
                    manifestId = manifestId,
                    bytes = bytes,
                    cacheKey = "body:${publicationKey.value}:${unitKey.value}:$manifestId:${Sha256.hex(bytes)}",
                    authorization = PendingContentBodyStoreRequest(
                        grant = grant,
                        scope = RightsScope(
                            publicationId = publicationKey,
                            acquisitionId = acquisitionId,
                            unitId = unitKey,
                            manifestId = manifestId,
                            contentRevision = IMPORTED_CONTENT_REVISION,
                        ),
                        byteCount = bytes.size.toLong(),
                    ),
                )
            }
        return ShuYuePublicationImportPlan(book, publicationKey, acquisitionId, grant, bodies)
    }
}

private data class ShuYuePublicationImportPlan(
    val book: ShuYueStagedBook,
    val publicationKey: PublicationKey,
    val acquisitionId: String,
    val grant: RightsGrant,
    val bodies: List<ShuYueContentBodyImportPlan>,
)

private data class ShuYueContentBodyImportPlan(
    val chapter: ShuYueStagedChapter,
    val unitKey: UnitKey,
    val manifestId: String,
    val bytes: ByteArray,
    val cacheKey: String,
    val authorization: PendingContentBodyStoreRequest,
)

private fun importedRightsGrant(
    book: ShuYueStagedBook,
    publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    acquisitionId: String,
): RightsGrant {
    val userOwned = book.origin == ORIGIN_LOCAL_TXT || book.origin == ORIGIN_LOCAL_EPUB
    val allowed = if (userOwned) ContentOperation.entries.toSet() else REMOTE_CONSERVATIVE_OPERATIONS
    return RightsGrant(
        schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
        grantId = RightsGrantRef(ShuYueReadingLocatorMapper.rightsGrantId(book)),
        scope = RightsScope(publicationKey, acquisitionId),
        provenance = RightsProvenance.HostPolicy(
            if (userOwned) "shuyue-user-import" else "shuyue-remote-conservative",
        ),
        protectionScheme = ProtectionScheme.None,
        validFromEpochMillis = 0,
        validUntilEpochMillis = null,
        allowedOperations = allowed,
    )
}

private fun ShuYueStagedBook.remotePublicationBinding(): SourceBinding? {
    if (origin != ORIGIN_REMOTE_PLUGIN) return null
    val exactSourceId = requireNotNull(sourceId) { "Remote ShuYue book is missing its source id" }
    return SourceBinding(
        sourceKey = ShuYueReviewedPluginCatalogV2.sourceKeyForLegacySourceId(exactSourceId)
            ?: SourceKey(
                contractVersion = SourceKey.CURRENT_CONTRACT_VERSION,
                packageId = SHUYUE_COMPAT_PACKAGE_ID,
                sourceId = exactSourceId,
            ),
        remoteId = id,
        canonicalUrl = originalUri?.takeIf(::isHttpUrl),
        entityKind = RemoteEntityKind.PUBLICATION,
        keyVersion = 1,
        parentPublication = null,
    )
}

private fun selectedCategories(
    prepared: ShuYuePreparedImport,
    resolved: ResolvedShuYueImportSelection,
): List<ShuYueImportedCategory> {
    val selectedNames = resolved.books.mapTo(hashSetOf()) { it.category }
    return prepared.staged.session.categories
        .filter { it.name in selectedNames }
        .sortedBy { it.id.value }
        .map { ShuYueImportedCategory(it.id.value, it.name) }
}

private fun ShuYueV1ReaderSettings.toPortableSettings(): ShuYueImportedReaderSettings =
    ShuYueImportedReaderSettings(
        language = language.name,
        fontSizeSp = fontSizeSp,
        lineHeightPercent = lineHeightPercent,
        pageChars = pageChars,
        theme = theme.name,
        accentColor = accentColor.name,
        volumeKeysEnabled = volumeKeysEnabled,
        volumeUpAction = volumeUpAction.name,
        volumeDownAction = volumeDownAction.name,
        keepScreenOn = keepScreenOn,
        syncOnLaunch = syncOnLaunch,
        appLockEnabled = appLockEnabled,
        secureScreen = secureScreen,
        incognitoMode = incognitoMode,
        showNsfwSources = showNsfwSources,
        imageParsingEnabled = imageParsingEnabled,
        showPluginErrors = showPluginErrors,
    )

private fun <D : Any> requireSyncCoverage(
    plan: ShuYuePortableImportPlan,
    bundle: ShuYueImportOutboxBundle<D>,
) {
    val required = buildSet {
        if (plan.publications.isNotEmpty()) add(ShuYueImportSyncDomain.PUBLICATIONS)
        if (plan.blobReferences.isNotEmpty()) add(ShuYueImportSyncDomain.CONTENT_REFS)
        if (plan.syncableBlobReferences.isNotEmpty()) add(ShuYueImportSyncDomain.CONTENT_BLOBS)
        if (plan.categories.isNotEmpty() || plan.categoryMemberships.isNotEmpty()) {
            add(ShuYueImportSyncDomain.CATEGORIES)
        }
        if (plan.readingProgress.isNotEmpty()) add(ShuYueImportSyncDomain.READING_PROGRESS)
        if (plan.readerSettings != null) add(ShuYueImportSyncDomain.READER_SETTINGS)
    }
    val jobBlobs = bundle.blobSyncJobs.map(ContentBlobSyncJobMutation::blob)
    require(
        required.all { it in bundle.representedDomains } &&
            (required.isEmpty() || bundle.drafts.isNotEmpty()) &&
            jobBlobs.toSet() == plan.syncableBlobReferences.toSet() &&
            jobBlobs.size == jobBlobs.distinctBy { it.blobId }.size &&
            bundle.blobSyncJobs.map(ContentBlobSyncJobMutation::jobId).distinct().size ==
            bundle.blobSyncJobs.size,
    ) {
        "Active sync v2 cannot atomically represent every selected ShuYue import domain"
    }
}

private fun buildMetadata(plan: ShuYuePortableImportPlan): List<ContentMetadataMutation> = buildList {
    plan.categories.forEach { category ->
        add(
            ContentMetadataMutation(
                key = "migration.shuyue.category.${category.categoryId}",
                value = IMPORT_JSON.encodeToString(category),
            ),
        )
    }
    plan.categoryMemberships.forEach { membership ->
        add(
            ContentMetadataMutation(
                key = shuyueCategoryMembershipMetadataKey(membership),
                value = IMPORT_JSON.encodeToString(membership),
            ),
        )
    }
    plan.readingProgress.forEach { progress ->
        add(
            ContentMetadataMutation(
                key = "migration.shuyue.progress.${progress.locator.scope.unitId.value}",
                value = IMPORT_JSON.encodeToString(progress),
            ),
        )
    }
    plan.readerSettings?.let { settings ->
        add(
            ContentMetadataMutation(
                key = "migration.shuyue.reader-settings.${plan.sourceDigestSha256}",
                value = IMPORT_JSON.encodeToString(settings),
            ),
        )
    }
    plan.legacyFlattenedPublicationIds.forEach { publicationId ->
        add(ContentMetadataMutation("migration.shuyue.legacy-flattened.$publicationId", "true"))
    }
}

internal fun shuyueCategoryMembershipMetadataKey(
    membership: ShuYueImportedCategoryMembership,
): String = "$SHUYUE_CATEGORY_MEMBERSHIP_METADATA_PREFIX${membership.publicationId}.${membership.categoryId}"

private fun buildAliases(resolved: ResolvedShuYueImportSelection): List<ContentAliasMutation> = buildList {
    resolved.books.forEach { book ->
        val publicationId = ShuYueReadingLocatorMapper.publicationId(book)
        add(ContentAliasMutation("shuyue-v1-book:${hexIdentity(book.id)}", publicationId.value))
    }
    val books = resolved.books.associateBy(ShuYueStagedBook::id)
    resolved.chapters.forEach { chapter ->
        val book = requireNotNull(books[chapter.bookId])
        add(
            ContentAliasMutation(
                "shuyue-v1-chapter:${hexIdentity(book.id)}:${hexIdentity(chapter.id)}",
                ShuYueReadingLocatorMapper.unitId(book, chapter.id).value,
            ),
        )
    }
}

private fun quarantineId(
    sourceDigest: String,
    staged: ShuYueStagedPluginInstallationDescription,
): String = Sha256.hex(
    listOf(
        sourceDigest,
        staged.id,
        staged.version,
        staged.versionCode.toString(),
        staged.origin,
        staged.ordinal.toString(),
        staged.sha256,
    )
        .joinToString("|") { "${it.length}:$it" }
        .encodeToByteArray(),
)

private fun hexIdentity(value: String): String = value.encodeToByteArray().joinToString("") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}

private fun isHttpUrl(value: String): Boolean = value.startsWith("https://", true) ||
    value.startsWith("http://", true)

private val IMPORT_JSON = Json { encodeDefaults = true; explicitNulls = true }
private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val REMOTE_CONSERVATIVE_OPERATIONS = setOf(
    ContentOperation.DISPLAY,
    ContentOperation.OFFLINE_STORE,
    ContentOperation.TTS,
    ContentOperation.SEARCH_INDEX,
    ContentOperation.ANNOTATE,
)
private const val IMPORTED_CONTENT_REVISION = 1L
private const val SHUYUE_COMPAT_PACKAGE_ID = "shuyue.compat.v1"
internal const val SHUYUE_CATEGORY_MEMBERSHIP_METADATA_PREFIX: String =
    "migration.shuyue.category-membership."
private const val ORIGIN_LOCAL_TXT = "LocalTxt"
private const val ORIGIN_LOCAL_EPUB = "LocalEpub"
private const val ORIGIN_REMOTE_PLUGIN = "RemotePlugin"
