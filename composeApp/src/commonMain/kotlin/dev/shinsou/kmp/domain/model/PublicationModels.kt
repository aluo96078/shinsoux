package dev.shinsou.kmp.domain.model

import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.rights.RightsGrantRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned, opaque source identity.  The string source id is never converted to a numeric hash.
 * [legacyLongId] is an optional compatibility field and may be signed because old stores used
 * signed fallback Long values.
 */
@Serializable
public data class SourceKey(
    val contractVersion: Int = CURRENT_CONTRACT_VERSION,
    val packageId: String,
    val sourceId: String,
    val legacyLongId: Long? = null,
) {
    init {
        require(contractVersion > 0) { "Source contract version must be positive" }
        requirePrintable(packageId, "Source package id", allowNewlines = false)
        requirePrintable(sourceId, "Source id", allowNewlines = false)
    }

    /** The source id exactly as supplied by the extension; no hash or numeric projection. */
    public val opaqueId: String get() = sourceId

    /** Length-delimited identity useful for diagnostics and non-cryptographic map keys. */
    public val canonicalId: String
        get() = "$contractVersion:${packageId.length}:$packageId:${sourceId.length}:$sourceId"

    /**
     * [legacyLongId] is compatibility metadata, not source authority. A source decoded without
     * that optional projection must still resolve the same runtime entry and remote parent keys.
     */
    override fun equals(other: Any?): Boolean =
        other is SourceKey && contractVersion == other.contractVersion &&
            packageId == other.packageId && sourceId == other.sourceId

    override fun hashCode(): Int =
        31 * (31 * contractVersion + packageId.hashCode()) + sourceId.hashCode()

    public fun validate(): Unit = validateSourceKey(this)

    public companion object {
        public const val CURRENT_CONTRACT_VERSION: Int = 2

        public fun fromLegacy(
            packageId: String,
            legacyLongId: Long,
            contractVersion: Int = CURRENT_CONTRACT_VERSION,
        ): SourceKey = SourceKey(contractVersion, packageId, legacyLongId.toString(), legacyLongId)
    }
}

/** A portable UUID independent of any source or remote id. */
@Serializable
public data class PublicationKey(
    val value: String,
) {
    init {
        requirePortableUuid(value, "Publication key")
    }

    public fun validate(): Unit = requirePortableUuid(value, "Publication key")

    public companion object {
        public fun isPortableUuid(value: String): Boolean = PORTABLE_UUID.matches(value)
        private val PORTABLE_UUID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
    }
}

/** A publication-scoped portable UUID; unscoped/legacy values are intentionally impossible. */
@Serializable
public data class UnitKey(
    val publicationKey: PublicationKey,
    val value: String,
) {
    init {
        publicationKey.validate()
        requirePortableUuid(value, "Unit key")
    }

    public val unitId: String get() = value

    public fun validate(): Unit {
        publicationKey.validate()
        requirePortableUuid(value, "Unit key")
    }
}

/** Versioned remote identity kept separate from the portable publication identity. */
@Serializable
public data class RemoteEntityKey(
    val keyVersion: Int,
    val sourceKey: SourceKey,
    val entityKind: RemoteEntityKind,
    val rawId: String,
    val canonicalId: String,
    /**
     * Required for a UNIT identity.  A chapter id is only source-local in many catalogs, so the
     * parent publication context is part of the identity rather than an undocumented caller-side
     * concatenation.  It is null for PUBLICATION keys.
     */
    val parentPublication: RemoteEntityKey? = null,
) {
    init {
        require(keyVersion > 0) { "Remote entity key version must be positive" }
        sourceKey.validate()
        requirePrintable(rawId, "Remote entity raw id", allowNewlines = false)
        requirePrintable(canonicalId, "Remote entity canonical id", allowNewlines = false)
        validateParentContext()
    }

    public fun validate(): Unit {
        require(keyVersion > 0) { "Remote entity key version must be positive" }
        sourceKey.validate()
        requirePrintable(rawId, "Remote entity raw id", allowNewlines = false)
        requirePrintable(canonicalId, "Remote entity canonical id", allowNewlines = false)
        validateParentContext()
    }

    private fun validateParentContext(): Unit {
        require(canonicalId == canonicalRemoteId(rawId)) {
            "Remote entity canonical id does not match its raw id for key version $keyVersion"
        }
        when (entityKind) {
            RemoteEntityKind.PUBLICATION -> require(parentPublication == null) {
                "A publication remote key must not have a parent publication"
            }
            RemoteEntityKind.UNIT -> {
                val parent = requireNotNull(parentPublication) {
                    "A unit remote key requires its parent publication context"
                }
                parent.validate()
                require(parent.entityKind == RemoteEntityKind.PUBLICATION) {
                    "A unit parent remote key must identify a publication"
                }
                require(parent.sourceKey == sourceKey) {
                    "A unit and parent publication must use the same source key"
                }
                require(parent.keyVersion == keyVersion) {
                    "A unit and parent publication must use the same remote-key version"
                }
            }
        }
    }
}

@Serializable
public enum class RemoteEntityKind {
    PUBLICATION,
    UNIT,
}

/** A source-specific binding for a remote publication or unit. */
@Serializable
public data class SourceBinding(
    val remoteEntityKey: RemoteEntityKey,
    val canonicalUrl: String? = null,
) {
    init {
        remoteEntityKey.validate()
        canonicalUrl?.let { requireSafeUri(it, "Canonical URL") }
    }

    public val sourceKey: SourceKey get() = remoteEntityKey.sourceKey
    public val remoteId: String get() = remoteEntityKey.rawId
    public val entityKind: RemoteEntityKind get() = remoteEntityKey.entityKind
    public val id: String
        get() = buildString {
            append("key-v")
            append(remoteEntityKey.keyVersion)
            append('/')
            append(sourceKey.canonicalId)
            append('/')
            append(entityKind.name.lowercase())
            append('/')
            remoteEntityKey.parentPublication?.let {
                append("parent=")
                append("v")
                append(it.keyVersion)
                append(':')
                append(it.canonicalId.length)
                append(':')
                append(it.canonicalId)
                append('/')
            }
            append(remoteEntityKey.canonicalId.length)
            append(':')
            append(remoteEntityKey.canonicalId)
        }

    public constructor(
        sourceKey: SourceKey,
        remoteId: String,
        canonicalUrl: String? = null,
        entityKind: RemoteEntityKind = RemoteEntityKind.PUBLICATION,
        keyVersion: Int = 1,
        parentPublication: RemoteEntityKey? = null,
    ) : this(
        RemoteEntityKey(
            keyVersion,
            sourceKey,
            entityKind,
            remoteId,
            canonicalRemoteId(remoteId),
            parentPublication,
        ),
        canonicalUrl,
    )

    public fun validate(): Unit {
        remoteEntityKey.validate()
        canonicalUrl?.let { requireSafeUri(it, "Canonical URL") }
    }
}

/** Origin of an acquisition, including source-backed and local imports. */
@Serializable
public sealed interface AcquisitionOrigin {
    @Serializable
    @SerialName("extension_source")
    public data class ExtensionSource(
        val sourceBinding: SourceBinding,
    ) : AcquisitionOrigin {
        init { sourceBinding.validate() }
    }

    @Serializable
    @SerialName("local_text")
    public data object LocalText : AcquisitionOrigin

    @Serializable
    @SerialName("local_epub")
    public data object LocalEpub : AcquisitionOrigin

    @Serializable
    @SerialName("local_package")
    public data class LocalPackage(
        val kind: LocalPackageKind,
    ) : AcquisitionOrigin
}

@Serializable
public enum class LocalPackageKind {
    IMAGES,
    ZIP,
    CBZ,
}

@Serializable
public enum class AcquisitionAvailability {
    AVAILABLE,
    PARTIAL,
    UNAVAILABLE,
}

/**
 * One independently addressable acquisition.  The source binding is optional because local TXT
 * and EPUB imports are first-class origins, not fake extension sources.
 */
@Serializable
public data class Acquisition(
    val id: String,
    val origin: AcquisitionOrigin,
    val units: List<PublicationUnit> = emptyList(),
    val contentRevision: Long = 0,
    val availability: AcquisitionAvailability = AcquisitionAvailability.AVAILABLE,
    val rightsGrantRef: RightsGrantRef? = null,
    val acquiredAtEpochMillis: Long? = null,
    /** Body-free compatibility metadata for an additive legacy Manga projection. */
    val legacyCompatibilityFacet: LegacyMangaCompatibilityFacetV1? = null,
) {
    init {
        requirePortableUuid(id, "Acquisition id")
        when (origin) {
            is AcquisitionOrigin.ExtensionSource -> {
                origin.sourceBinding.validate()
                require(origin.sourceBinding.entityKind == RemoteEntityKind.PUBLICATION) {
                    "Acquisition extension binding must identify a publication"
                }
            }
            AcquisitionOrigin.LocalText,
            AcquisitionOrigin.LocalEpub,
            is AcquisitionOrigin.LocalPackage,
            -> Unit
        }
        require(contentRevision >= 0) { "Content revision must be non-negative" }
        require(acquiredAtEpochMillis == null || acquiredAtEpochMillis >= 0) {
            "Acquisition timestamp must be non-negative"
        }
        rightsGrantRef?.validate()
        legacyCompatibilityFacet?.validate()
        require(units.map(PublicationUnit::key).distinct().size == units.size) {
            "An acquisition cannot contain duplicate unit keys"
        }
        validateAcquisitionUnitBindings(origin, units)
    }

    /** Compatibility alias for code that names the edge binding. */
    public val sourceBinding: SourceBinding?
        get() = (origin as? AcquisitionOrigin.ExtensionSource)?.sourceBinding

    public val legacyMangaFacet: LegacyMangaCompatibilityFacetV1?
        get() = legacyCompatibilityFacet

    public val legacyFacet: LegacyMangaCompatibilityFacetV1?
        get() = legacyCompatibilityFacet

    public fun validate(): Unit {
        requirePortableUuid(id, "Acquisition id")
        when (origin) {
            is AcquisitionOrigin.ExtensionSource -> {
                origin.sourceBinding.validate()
                require(origin.sourceBinding.entityKind == RemoteEntityKind.PUBLICATION) {
                    "Acquisition extension binding must identify a publication"
                }
            }
            AcquisitionOrigin.LocalText,
            AcquisitionOrigin.LocalEpub,
            is AcquisitionOrigin.LocalPackage,
            -> Unit
        }
        require(contentRevision >= 0) { "Content revision must be non-negative" }
        require(acquiredAtEpochMillis == null || acquiredAtEpochMillis >= 0) {
            "Acquisition timestamp must be non-negative"
        }
        rightsGrantRef?.validate()
        legacyCompatibilityFacet?.validate()
        require(units.map(PublicationUnit::key).distinct().size == units.size) {
            "An acquisition cannot contain duplicate unit keys"
        }
        validateAcquisitionUnitBindings(origin, units)
    }
}

/** A unit owns zero or more immutable manifest revisions; identity never depends on ordinal. */
@Serializable
public data class PublicationUnit(
    val key: UnitKey,
    val title: String,
    val manifestRevisions: List<ContentManifest> = emptyList(),
    val sourceBinding: SourceBinding? = null,
    val ordinal: Int? = null,
    val publishedAtEpochMillis: Long? = null,
    /** Body-free compatibility metadata for an additive legacy Chapter projection. */
    val legacyCompatibilityFacet: LegacyChapterCompatibilityFacetV1? = null,
) {
    init {
        key.validate()
        requirePrintable(title, "Publication unit title", allowNewlines = false, allowBlank = true)
        sourceBinding?.let {
            it.validate()
            require(it.entityKind == RemoteEntityKind.UNIT) {
                "Publication unit binding must identify a unit"
            }
        }
        legacyCompatibilityFacet?.validate()
        require(ordinal == null || ordinal >= 0) { "Publication unit ordinal must be non-negative" }
        require(publishedAtEpochMillis == null || publishedAtEpochMillis >= 0) {
            "Publication unit timestamp must be non-negative"
        }
        manifestRevisions.forEach(ContentManifest::validate)
        require(manifestRevisions.map { it.manifestId }.distinct().size == manifestRevisions.size) {
            "Publication unit manifest revisions must have unique ids"
        }
        require(manifestRevisions.map { it.contentRevision }.distinct().size == manifestRevisions.size) {
            "Publication unit manifest revisions must have unique content revisions"
        }
    }

    public val publicationKey: PublicationKey get() = key.publicationKey
    public val latestManifest: ContentManifest? get() = manifestRevisions.maxByOrNull { it.contentRevision }
    public val legacyChapterFacet: LegacyChapterCompatibilityFacetV1?
        get() = legacyCompatibilityFacet

    public val legacyFacet: LegacyChapterCompatibilityFacetV1?
        get() = legacyCompatibilityFacet

    public fun validate(): Unit {
        key.validate()
        requirePrintable(title, "Publication unit title", allowNewlines = false, allowBlank = true)
        sourceBinding?.let {
            it.validate()
            require(it.entityKind == RemoteEntityKind.UNIT) {
                "Publication unit binding must identify a unit"
            }
        }
        legacyCompatibilityFacet?.validate()
        require(ordinal == null || ordinal >= 0) { "Publication unit ordinal must be non-negative" }
        require(publishedAtEpochMillis == null || publishedAtEpochMillis >= 0) {
            "Publication unit timestamp must be non-negative"
        }
        manifestRevisions.forEach(ContentManifest::validate)
        require(manifestRevisions.map { it.manifestId }.distinct().size == manifestRevisions.size) {
            "Publication unit manifest revisions must have unique ids"
        }
        require(manifestRevisions.map { it.contentRevision }.distinct().size == manifestRevisions.size) {
            "Publication unit manifest revisions must have unique content revisions"
        }
    }
}

@Serializable
public enum class WorkLinkType {
    RELATED,
    ALTERNATE_EDITION,
    SEQUEL,
    PREQUEL,
    SIDE_STORY,
    ADAPTATION,
}

@Serializable
public data class WorkLink(
    val target: PublicationKey,
    val relation: WorkLinkType = WorkLinkType.RELATED,
    val label: String? = null,
) {
    init {
        target.validate()
        label?.let { requirePrintable(it, "Work-link label", allowNewlines = false) }
    }

    public val publicationKey: PublicationKey get() = target
    public val kind: WorkLinkType get() = relation

    public fun validate(): Unit {
        target.validate()
        label?.let { requirePrintable(it, "Work-link label", allowNewlines = false) }
    }
}

/** The authoritative work record. Manga/Chapter remain compatibility projections for now. */
@Serializable
public data class Publication(
    val key: PublicationKey,
    val title: String,
    val acquisitions: List<Acquisition> = emptyList(),
    val workLinks: List<WorkLink> = emptyList(),
    val description: String? = null,
    val authors: List<String> = emptyList(),
) {
    init {
        key.validate()
        requirePrintable(title, "Publication title", allowNewlines = false, allowBlank = true)
        description?.let { requirePrintable(it, "Publication description", allowNewlines = true, allowBlank = true) }
        authors.forEach { requirePrintable(it, "Publication author", allowNewlines = false) }
        require(acquisitions.map(Acquisition::id).distinct().size == acquisitions.size) {
            "Publication acquisitions must have unique ids"
        }
        require(workLinks.none { it.target == key }) { "A publication cannot link to itself" }
        acquisitions.forEach(Acquisition::validate)
        workLinks.forEach(WorkLink::validate)
        val units = acquisitions.flatMap { it.units }
        require(units.all { it.key.publicationKey == key }) {
            "Every publication unit must be scoped to its publication"
        }
        require(units.map(PublicationUnit::key).distinct().size == units.size) {
            "Publication unit identity must be unique across acquisitions"
        }
    }

    public val units: List<PublicationUnit> get() = acquisitions.flatMap { it.units }

    public fun validate(): Unit {
        key.validate()
        requirePrintable(title, "Publication title", allowNewlines = false, allowBlank = true)
        description?.let { requirePrintable(it, "Publication description", allowNewlines = true, allowBlank = true) }
        authors.forEach { requirePrintable(it, "Publication author", allowNewlines = false) }
        require(acquisitions.map(Acquisition::id).distinct().size == acquisitions.size) {
            "Publication acquisitions must have unique ids"
        }
        require(workLinks.none { it.target == key }) { "A publication cannot link to itself" }
        acquisitions.forEach(Acquisition::validate)
        workLinks.forEach(WorkLink::validate)
        val units = acquisitions.flatMap { it.units }
        require(units.all { it.key.publicationKey == key }) {
            "Every publication unit must be scoped to its publication"
        }
        require(units.map(PublicationUnit::key).distinct().size == units.size) {
            "Publication unit identity must be unique across acquisitions"
        }
    }
}

private fun validateSourceKey(value: SourceKey) {
    require(value.contractVersion > 0) { "Source contract version must be positive" }
    requirePrintable(value.packageId, "Source package id", allowNewlines = false)
    requirePrintable(value.sourceId, "Source id", allowNewlines = false)
}

private fun validateAcquisitionUnitBindings(
    origin: AcquisitionOrigin,
    units: List<PublicationUnit>,
) {
    if (origin !is AcquisitionOrigin.ExtensionSource) return
    val publicationRemoteKey = origin.sourceBinding.remoteEntityKey
    units.forEach { unit ->
        val unitRemoteKey = requireNotNull(unit.sourceBinding) {
            "Every extension-backed unit requires a source binding"
        }.remoteEntityKey
        require(unitRemoteKey.sourceKey == publicationRemoteKey.sourceKey) {
            "An extension-backed unit must use the acquisition source key"
        }
        require(unitRemoteKey.parentPublication == publicationRemoteKey) {
            "An extension-backed unit must use the acquisition publication as its exact remote parent"
        }
    }
}

private fun requirePortableUuid(value: String, label: String) {
    require(PublicationKey.isPortableUuid(value)) { "$label must be a portable UUID" }
}

private fun requirePrintable(
    value: String,
    label: String,
    allowNewlines: Boolean,
    allowBlank: Boolean = false,
) {
    require(allowBlank || value.isNotBlank()) { "$label must not be blank" }
    require(value.none { it.isISOControl() && (!allowNewlines || it != '\n' && it != '\r' && it != '\t') }) {
        "$label contains an unsafe control character"
    }
}

private fun requireSafeUri(value: String, label: String) {
    requirePrintable(value, label, allowNewlines = false)
    require(value.length <= 4096) { "$label is too long" }
    require(value.matches(URI_PATTERN)) { "$label is not a supported absolute URI" }
}

private fun canonicalRemoteId(value: String): String = value

private val URI_PATTERN = Regex("(?i)https?://[^\\s\\u0000-\\u001f\\u007f]+")
