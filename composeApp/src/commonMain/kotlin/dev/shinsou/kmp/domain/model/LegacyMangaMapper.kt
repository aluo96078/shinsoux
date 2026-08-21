package dev.shinsou.kmp.domain.model

import kotlinx.serialization.Serializable

@Serializable
public data class PortableCategoryId(val value: String) {
    init { require(PublicationKey.isPortableUuid(value)) { "Portable category id must be a UUID" } }

    public companion object {
        /** Stable system identity for the legacy category whose numeric id is zero. */
        public val DEFAULT: PortableCategoryId =
            PortableCategoryId("00000000-0000-5000-8000-000000000001")
    }
}

@Serializable
public data class PortableLegacyCategory(
    val id: PortableCategoryId,
    val legacyRecord: LegacyCategoryRecordV1,
)

@Serializable
public data class PortableLegacyCategoryLink(
    val publicationKey: PublicationKey,
    val categoryId: PortableCategoryId,
    val legacyRecord: LegacyMangaCategoryLinkV1,
) {
    init { require(legacyRecord.mangaId != Long.MIN_VALUE) { "Invalid legacy category link" } }
}

@Serializable
public data class PortableAliasBinding(
    val namespace: MigrationNamespaceId,
    val alias: LegacyAliasKey,
    val portableUuid: String,
) {
    init {
        namespace.validate()
        alias.validate()
        require(PublicationKey.isPortableUuid(portableUuid)) { "Alias binding must contain a portable UUID" }
    }
}

public data class PortableAliasRequest(
    val namespace: MigrationNamespaceId,
    val alias: LegacyAliasKey,
    val derivedUuid: String,
) {
    init {
        namespace.validate()
        alias.validate()
        require(PublicationKey.isPortableUuid(derivedUuid)) { "Derived alias id must be a portable UUID" }
    }
}

public sealed class PortableAliasException(message: String) : IllegalArgumentException(message) {
    public class ChangedBinding(public val alias: LegacyAliasKey) :
        PortableAliasException("A legacy alias was already bound to another UUID")

    public class UuidCollision(public val portableUuid: String) :
        PortableAliasException("A portable UUID was already bound to another legacy alias")

    public class DuplicateRequest(public val alias: LegacyAliasKey) :
        PortableAliasException("A legacy alias appears more than once in one transaction")

    public class MissingBinding(public val alias: LegacyAliasKey) :
        PortableAliasException("A required legacy alias binding is missing")
}

/** Shared-metadata transaction participant used by migrations and compatibility projections. */
public interface PortableAliasResolver {
    public fun resolveOrBind(
        namespace: MigrationNamespaceId,
        alias: LegacyAliasKey,
        derivedUuid: String,
    ): PortableAliasBinding = resolveOrBindAll(
        listOf(PortableAliasRequest(namespace, alias, derivedUuid)),
    ).single()

    /** All bindings are installed or none are; implementations must not expose partial mutation. */
    public fun resolveOrBindAll(requests: List<PortableAliasRequest>): List<PortableAliasBinding>

    public fun resolve(namespace: MigrationNamespaceId, alias: LegacyAliasKey): PortableAliasBinding?
}

/** Deterministic executable oracle for the future shared-SQLite alias ledger. */
public class InMemoryPortableAliasLedger : PortableAliasResolver {
    private val byAlias = LinkedHashMap<ScopedAlias, String>()
    private val byUuid = LinkedHashMap<String, ScopedAlias>()

    override fun resolveOrBindAll(requests: List<PortableAliasRequest>): List<PortableAliasBinding> {
        val seen = HashSet<ScopedAlias>(requests.size)
        requests.forEach { request ->
            val scoped = ScopedAlias(request.namespace, request.alias)
            if (!seen.add(scoped)) throw PortableAliasException.DuplicateRequest(request.alias)
        }

        // Copy-on-write models a single shared SQLite transaction and makes validation rollback explicit.
        val nextByAlias = LinkedHashMap(byAlias)
        val nextByUuid = LinkedHashMap(byUuid)
        requests.forEach { request ->
            val scoped = ScopedAlias(request.namespace, request.alias)
            val existingUuid = nextByAlias[scoped]
            if (existingUuid != null && existingUuid != request.derivedUuid) {
                throw PortableAliasException.ChangedBinding(request.alias)
            }
            val existingAlias = nextByUuid[request.derivedUuid]
            if (existingAlias != null && existingAlias != scoped) {
                throw PortableAliasException.UuidCollision(request.derivedUuid)
            }
            nextByAlias[scoped] = request.derivedUuid
            nextByUuid[request.derivedUuid] = scoped
        }
        byAlias.clear()
        byAlias.putAll(nextByAlias)
        byUuid.clear()
        byUuid.putAll(nextByUuid)
        return requests.map { PortableAliasBinding(it.namespace, it.alias, it.derivedUuid) }
    }

    override fun resolve(namespace: MigrationNamespaceId, alias: LegacyAliasKey): PortableAliasBinding? =
        byAlias[ScopedAlias(namespace, alias)]?.let { PortableAliasBinding(namespace, alias, it) }

    public fun snapshot(): List<PortableAliasBinding> = byAlias.map { (scope, uuid) ->
        PortableAliasBinding(scope.namespace, scope.alias, uuid)
    }

    private data class ScopedAlias(
        val namespace: MigrationNamespaceId,
        val alias: LegacyAliasKey,
    )
}

@Serializable
public data class LegacyPublicationBundle(
    val namespace: MigrationNamespaceId,
    val publication: Publication,
    val categories: List<PortableLegacyCategory>,
    val categoryLinks: List<PortableLegacyCategoryLink>,
    val aliases: List<PortableAliasBinding>,
) {
    init {
        namespace.validate()
        publication.validate()
        require(aliases.all { it.namespace == namespace }) { "Bundle aliases use another namespace" }
        require(aliases.map { it.alias }.distinct().size == aliases.size) { "Bundle aliases must be unique" }
        require(aliases.map { it.portableUuid }.distinct().size == aliases.size) {
            "Bundle portable UUIDs must be unique"
        }
        require(categories.map { it.id }.distinct().size == categories.size) {
            "Portable categories must have unique ids"
        }
        require(categoryLinks.all { it.publicationKey == publication.key }) {
            "Portable category link belongs to another publication"
        }
    }
}

public object LegacyMangaMapper {
    private const val LEGACY_PACKAGE_ID: String = "legacy.manga.v1"

    public fun toPublication(
        input: LegacyMangaAggregateV1,
        aliases: PortableAliasResolver,
        namespace: MigrationNamespaceId = MigrationNamespaceId.LEGACY_MANGA_V1,
    ): LegacyPublicationBundle {
        input.validate()
        namespace.validate()

        val mangaAlias = LegacyAliasKey.Manga(input.record.id, input.record.source)
        val acquisitionAlias = LegacyAliasKey.Acquisition(input.record.id, input.record.source)
        val chapterAliases = input.chapters.map {
            LegacyAliasKey.Chapter(input.record.id, it.id, input.record.source)
        }
        val categoryAliases = input.categories.map { LegacyAliasKey.Category(it.id) }
        val requests = buildList {
            add(aliasRequest(namespace, mangaAlias))
            add(aliasRequest(namespace, acquisitionAlias))
            chapterAliases.forEach { add(aliasRequest(namespace, it)) }
            input.categories.zip(categoryAliases).forEach { (record, alias) ->
                add(
                    PortableAliasRequest(
                        namespace,
                        alias,
                        if (record.id == Category.Default.id) PortableCategoryId.DEFAULT.value
                        else deriveAliasUuid(namespace, alias),
                    ),
                )
            }
        }
        val bindings = aliases.resolveOrBindAll(requests)
        val bound = bindings.associate { it.alias to it.portableUuid }
        val publicationKey = PublicationKey(requireNotNull(bound[mangaAlias]))
        val acquisitionId = requireNotNull(bound[acquisitionAlias])
        val sourceKey = SourceKey.fromLegacy(LEGACY_PACKAGE_ID, input.record.source)
        val publicationRemoteKey = RemoteEntityKey(
            keyVersion = 1,
            sourceKey = sourceKey,
            entityKind = RemoteEntityKind.PUBLICATION,
            rawId = input.record.id.toString(),
            canonicalId = input.record.id.toString(),
        )
        val units = input.chapters.zip(chapterAliases).map { (chapter, alias) ->
            val unitRemoteKey = RemoteEntityKey(
                keyVersion = 1,
                sourceKey = sourceKey,
                entityKind = RemoteEntityKind.UNIT,
                rawId = chapter.id.toString(),
                canonicalId = chapter.id.toString(),
                parentPublication = publicationRemoteKey,
            )
            PublicationUnit(
                key = UnitKey(publicationKey, requireNotNull(bound[alias])),
                title = chapter.name,
                sourceBinding = SourceBinding(unitRemoteKey),
                ordinal = chapter.legacyListOrdinal,
                publishedAtEpochMillis = chapter.dateUpload.takeIf { it >= 0L },
                legacyCompatibilityFacet = LegacyChapterCompatibilityFacetV1(
                    namespace = namespace,
                    record = chapter,
                    parentSource = input.record.source,
                    alias = alias,
                ),
            )
        }
        val acquisition = Acquisition(
            id = acquisitionId,
            origin = AcquisitionOrigin.ExtensionSource(SourceBinding(publicationRemoteKey)),
            units = units,
            acquiredAtEpochMillis = input.record.dateAdded.takeIf { it >= 0L },
            legacyCompatibilityFacet = LegacyMangaCompatibilityFacetV1(
                namespace = namespace,
                record = input.record,
                alias = mangaAlias,
                categories = input.categories,
                links = input.links,
            ),
        )
        val publication = Publication(
            key = publicationKey,
            title = input.record.title,
            acquisitions = listOf(acquisition),
            description = input.record.description,
            authors = listOfNotNull(input.record.author).filter(String::isNotBlank),
        )
        val portableCategories = input.categories.zip(categoryAliases).map { (record, alias) ->
            PortableLegacyCategory(PortableCategoryId(requireNotNull(bound[alias])), record)
        }
        val categoryIdsByLegacy = portableCategories.associate { it.legacyRecord.id to it.id }
        val portableLinks = input.links.map { link ->
            PortableLegacyCategoryLink(
                publicationKey = publicationKey,
                categoryId = requireNotNull(categoryIdsByLegacy[link.categoryId]) {
                    "Legacy category link has no portable category"
                },
                legacyRecord = link,
            )
        }
        return LegacyPublicationBundle(
            namespace = namespace,
            publication = publication,
            categories = portableCategories,
            categoryLinks = portableLinks,
            aliases = bindings,
        )
    }

    public fun toLegacy(bundle: LegacyPublicationBundle): LegacyMangaAggregateV1 {
        val publication = bundle.publication
        val acquisition = publication.acquisitions.singleOrNull {
            it.legacyCompatibilityFacet?.namespace == bundle.namespace
        } ?: throw IllegalArgumentException(
            "A legacy projection requires exactly one acquisition facet for its migration namespace",
        )
        val mangaFacet = requireNotNull(acquisition.legacyCompatibilityFacet) {
            "Legacy Manga compatibility facet is missing"
        }
        require(mangaFacet.namespace == bundle.namespace) { "Legacy Manga namespace mismatch" }
        require(mangaFacet.sourcePackageId == LEGACY_PACKAGE_ID) {
            "Legacy Manga source package changed"
        }
        require(publication.title == mangaFacet.record.title) {
            "Legacy Manga title projection is stale"
        }
        require(publication.description == mangaFacet.record.description) {
            "Legacy Manga description projection is stale"
        }
        require(
            publication.authors == listOfNotNull(mangaFacet.record.author).filter(String::isNotBlank),
        ) { "Legacy Manga author projection is stale" }
        require(publication.workLinks.isEmpty()) {
            "Legacy Manga projection cannot discard work links"
        }

        val mangaAlias = LegacyAliasKey.Manga(mangaFacet.record.id, mangaFacet.record.source)
        val acquisitionAlias = LegacyAliasKey.Acquisition(mangaFacet.record.id, mangaFacet.record.source)
        require(binding(bundle, mangaAlias) == publication.key.value) { "Publication alias does not match" }
        require(binding(bundle, acquisitionAlias) == acquisition.id) { "Acquisition alias does not match" }

        require(acquisition.contentRevision == 0L) {
            "Legacy Manga projection cannot discard acquisition content revision"
        }
        require(acquisition.availability == AcquisitionAvailability.AVAILABLE) {
            "Legacy Manga projection cannot discard acquisition availability"
        }
        require(acquisition.rightsGrantRef == null) {
            "Legacy Manga projection cannot discard acquisition rights"
        }
        require(acquisition.acquiredAtEpochMillis == mangaFacet.record.dateAdded.takeIf { it >= 0L }) {
            "Legacy Manga acquisition timestamp projection is stale"
        }

        val sourceBinding = acquisition.sourceBinding
            ?: throw IllegalArgumentException("Legacy acquisition source binding is missing")
        val expectedSourceKey = SourceKey.fromLegacy(LEGACY_PACKAGE_ID, mangaFacet.record.source)
        val expectedPublicationRemoteKey = RemoteEntityKey(
            keyVersion = 1,
            sourceKey = expectedSourceKey,
            entityKind = RemoteEntityKind.PUBLICATION,
            rawId = mangaFacet.record.id.toString(),
            canonicalId = mangaFacet.record.id.toString(),
        )
        require(sourceBinding == SourceBinding(expectedPublicationRemoteKey)) {
            "Legacy Manga source binding changed"
        }

        val chapters = acquisition.units.map { unit ->
            val facet = unit.legacyCompatibilityFacet
                ?: throw IllegalArgumentException("Legacy Chapter compatibility facet is missing")
            require(facet.namespace == bundle.namespace) { "Legacy Chapter namespace mismatch" }
            require(facet.record.mangaId == mangaFacet.record.id) { "Legacy Chapter parent changed" }
            require(binding(bundle, facet.alias) == unit.key.value) { "Legacy Chapter alias does not match" }
            require(unit.key.publicationKey == publication.key) { "Legacy Chapter publication scope changed" }
            require(unit.manifestRevisions.isEmpty()) {
                "Legacy Manga projection cannot discard content manifests"
            }
            require(unit.ordinal == facet.record.legacyListOrdinal) {
                "Legacy Chapter ordinal projection is stale"
            }
            require(unit.publishedAtEpochMillis == facet.record.dateUpload.takeIf { it >= 0L }) {
                "Legacy Chapter publication timestamp projection is stale"
            }
            val unitSourceBinding = unit.sourceBinding
                ?: throw IllegalArgumentException("Legacy Chapter source binding is missing")
            val expectedUnitRemoteKey = RemoteEntityKey(
                keyVersion = 1,
                sourceKey = expectedSourceKey,
                entityKind = RemoteEntityKind.UNIT,
                rawId = facet.record.id.toString(),
                canonicalId = facet.record.id.toString(),
                parentPublication = expectedPublicationRemoteKey,
            )
            require(unitSourceBinding == SourceBinding(expectedUnitRemoteKey)) {
                "Legacy Chapter source binding changed"
            }
            require(unit.title == facet.record.name) { "Legacy Chapter title projection is stale" }
            facet.record
        }

        val categories = bundle.categories
        require(categories.map(PortableLegacyCategory::legacyRecord) ==
            mangaFacet.categories) {
            "Legacy category compatibility data changed"
        }
        categories.forEach { category ->
            val alias = LegacyAliasKey.Category(category.legacyRecord.id)
            require(binding(bundle, alias) == category.id.value) { "Legacy category alias does not match" }
        }
        val links = bundle.categoryLinks
        require(links.map(PortableLegacyCategoryLink::legacyRecord) ==
            mangaFacet.links) {
            "Legacy category-link compatibility data changed"
        }
        val portableByLegacyId = categories.associate { it.legacyRecord.id to it.id }
        links.forEach { link ->
            require(link.publicationKey == publication.key) { "Legacy category link publication changed" }
            require(portableByLegacyId[link.legacyRecord.categoryId] == link.categoryId) {
                "Legacy category link target changed"
            }
        }

        val expectedAliases = buildSet {
            add(mangaAlias)
            add(acquisitionAlias)
            chapters.forEach { add(LegacyAliasKey.Chapter(it.mangaId, it.id, mangaFacet.record.source)) }
            categories.forEach { add(LegacyAliasKey.Category(it.legacyRecord.id)) }
        }
        require(bundle.aliases.map { it.alias }.toSet() == expectedAliases) {
            "Legacy bundle contains missing or unexpected aliases"
        }
        return LegacyMangaAggregateV1(
            record = mangaFacet.record,
            chapters = chapters,
            categories = categories.map(PortableLegacyCategory::legacyRecord),
            links = links.map(PortableLegacyCategoryLink::legacyRecord),
        )
    }

    private fun aliasRequest(namespace: MigrationNamespaceId, alias: LegacyAliasKey): PortableAliasRequest =
        PortableAliasRequest(namespace, alias, deriveAliasUuid(namespace, alias))

    private fun deriveAliasUuid(namespace: MigrationNamespaceId, alias: LegacyAliasKey): String =
        Rfc9562UuidV5.derive(namespace, alias.canonicalBytes())

    private fun binding(bundle: LegacyPublicationBundle, alias: LegacyAliasKey): String =
        bundle.aliases.singleOrNull { it.alias == alias }?.portableUuid
            ?: throw PortableAliasException.MissingBinding(alias)
}
