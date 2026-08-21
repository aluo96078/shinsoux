@file:OptIn(ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Immutable production package runtime with an exact implementation for every descriptor. */
public class ImmutableExtensionPackageRuntimeV2(
    descriptor: ExtensionPackageV2,
    implementations: Iterable<ExtensionSourceV2>,
) : ExtensionPackageRuntimeV2 {
    override val descriptor: ExtensionPackageV2 = descriptor.immutableSnapshot()
    private val sourcesByKey: Map<SourceKey, ExtensionSourceV2>

    init {
        this.descriptor.validate()
        val values = implementations.toList()
        require(values.size == this.descriptor.sources.size) {
            "Extension runtime must implement every declared source exactly once"
        }
        require(values.map { it.descriptor.sourceKey }.distinct().size == values.size) {
            "Extension runtime contains duplicate source implementations"
        }
        val declaredByKey = this.descriptor.sources.associateBy(SourceDescriptorV2::sourceKey)
        values.forEach { implementation ->
            val declared = declaredByKey[implementation.descriptor.sourceKey]
            require(declared == implementation.descriptor) {
                "Extension source implementation does not exactly match its package descriptor"
            }
        }
        sourcesByKey = values.associateBy { it.descriptor.sourceKey }
        require(sourcesByKey.keys == declaredByKey.keys) {
            "Extension runtime source set differs from its package descriptor"
        }
    }

    override fun source(sourceKey: SourceKey): ExtensionSourceV2? = sourcesByKey[sourceKey]
}

/** Optional lifecycle implemented by executable package runtimes that own engines or workers. */
public interface CloseableExtensionPackageRuntimeV2 : ExtensionPackageRuntimeV2 {
    public suspend fun close()
}

/**
 * Process-local production registry. Package replacement is explicit, source lookup uses complete
 * [SourceKey] equality, and a malformed package never partially enters the live registry.
 */
public class ExtensionRuntimeRegistryV2 {
    private data class LoadedPackage(
        val runtime: ExtensionPackageRuntimeV2,
        val facade: ExtensionHostFacadeV2,
    )

    private val mutex = Mutex()
    private val packages = linkedMapOf<String, LoadedPackage>()

    public suspend fun install(
        runtime: ExtensionPackageRuntimeV2,
        replace: Boolean = false,
    ): ExtensionHostFacadeV2 {
        val facade = validateRuntime(runtime)
        var obsolete: ExtensionPackageRuntimeV2? = null
        mutex.withLock {
            val packageId = runtime.descriptor.packageId
            val existing = packages[packageId]
            require(existing == null || replace) { "Extension package '$packageId' is already loaded" }
            packages[packageId] = LoadedPackage(runtime, facade)
            obsolete = existing?.runtime?.takeUnless { it === runtime }
        }
        closeSafely(obsolete)
        return facade
    }

    public suspend fun uninstall(packageId: String): Boolean {
        requireRegistryId(packageId, "Extension package id")
        val removed = mutex.withLock { packages.remove(packageId) } ?: return false
        closeSafely(removed.runtime)
        return true
    }

    public suspend fun packageFacade(packageId: String): ExtensionHostFacadeV2? {
        requireRegistryId(packageId, "Extension package id")
        return mutex.withLock { packages[packageId]?.facade }
    }

    public suspend fun source(sourceKey: SourceKey): HostExtensionSourceV2? {
        sourceKey.validate()
        return mutex.withLock { packages[sourceKey.packageId]?.facade }?.source(sourceKey)
    }

    public suspend fun descriptors(): List<ExtensionPackageV2> = mutex.withLock {
        packages.values.map { it.runtime.descriptor }
    }

    public suspend fun close() {
        val removed = mutex.withLock {
            packages.values.map(LoadedPackage::runtime).also { packages.clear() }
        }
        removed.forEach { closeSafely(it) }
    }

    private fun validateRuntime(runtime: ExtensionPackageRuntimeV2): ExtensionHostFacadeV2 {
        runtime.descriptor.validate()
        val facade = ExtensionHostFacadeV2(runtime)
        runtime.descriptor.sources.forEach { descriptor ->
            requireNotNull(facade.source(descriptor.sourceKey)) {
                "Extension runtime is missing declared source ${descriptor.sourceKey.canonicalId}"
            }
        }
        return facade
    }

    private suspend fun closeSafely(runtime: ExtensionPackageRuntimeV2?) {
        if (runtime is CloseableExtensionPackageRuntimeV2) runCatching { runtime.close() }
    }
}

/** Exact request passed to independent representation providers for one source/unit scope. */
public data class UnitContentRequestV2(
    val sourceKey: SourceKey,
    val remotePublicationId: String,
    val remoteUnitId: String,
) {
    init {
        sourceKey.validate()
        requireRuntimeId(remotePublicationId, "Remote publication id")
        requireRuntimeId(remoteUnitId, "Remote unit id")
    }
}

/**
 * One reviewed provider of a representation. Returning null means that representation is not
 * available for this unit; it does not suppress sibling text/image/EPUB forms.
 */
public interface UnitContentRepresentationProviderV2 {
    public val representationId: String
    public suspend fun load(request: UnitContentRequestV2): UnitContentPayload?
}

/** Builds one v2 result from multiple independently acquired representations of the same unit. */
public class MultiRepresentationContentResolverV2(
    providers: Iterable<UnitContentRepresentationProviderV2>,
) {
    private val providers: List<UnitContentRepresentationProviderV2> = providers.toList()

    init {
        require(this.providers.isNotEmpty() && this.providers.size <= MAX_PRODUCTION_REPRESENTATIONS) {
            "Content resolver needs a bounded provider list"
        }
        this.providers.forEach { requireRuntimeId(it.representationId, "Representation provider id") }
        require(this.providers.map(UnitContentRepresentationProviderV2::representationId).distinct().size ==
            this.providers.size) { "Content resolver contains duplicate representation providers" }
    }

    public suspend fun resolve(request: UnitContentRequestV2): UnitContentResultV2 {
        val representations = providers.mapNotNull { provider ->
            provider.load(request)?.also { payload ->
                require(payload.representationId == provider.representationId) {
                    "Representation provider changed its stable id"
                }
                require(payload.sourceKey == request.sourceKey && payload.remoteUnitId == request.remoteUnitId) {
                    "Representation provider escaped its requested source/unit scope"
                }
            }
        }
        require(representations.isNotEmpty()) { "No content representation is available for this unit" }
        return UnitContentResultV2(
            schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
            sourceKey = request.sourceKey,
            remotePublicationId = request.remotePublicationId,
            remoteUnitId = request.remoteUnitId,
            representations = representations,
        )
    }
}

/** Adds production multi-representation content resolution to any v2 source implementation. */
public class MultiRepresentationExtensionSourceV2(
    private val delegate: ExtensionSourceV2,
    private val contentResolver: MultiRepresentationContentResolverV2,
) : ExtensionSourceV2 by delegate {
    override val descriptor: SourceDescriptorV2 = delegate.descriptor

    override suspend fun content(
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2 = contentResolver.resolve(
        UnitContentRequestV2(descriptor.sourceKey, remotePublicationId, remoteUnitId),
    )
}

private fun requireRegistryId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 512 && value.none { it.isWhitespace() || it.isISOControl() }) {
        "$label must be bounded and printable"
    }
}

private fun requireRuntimeId(value: String, label: String) = requireRegistryId(value, label)

private fun ExtensionPackageV2.immutableSnapshot(): ExtensionPackageV2 = copy(
    sources = sources.map { source ->
        source.copy(
            supportedContentKinds = source.supportedContentKinds.toSet(),
            capabilities = source.capabilities.toSet(),
        )
    },
    capabilities = capabilities.toSet(),
    supportedContentKinds = supportedContentKinds.toSet(),
)

private const val MAX_PRODUCTION_REPRESENTATIONS: Int = 32
