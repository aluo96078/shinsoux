package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey

/**
 * Returns an exact legacy-library match and rejects title-only or otherwise ambiguous recovery.
 *
 * Search results are only discovery hints. The deterministic publication UUID is the authority,
 * so a same-named title can never rebind an old favorite to a different remote publication.
 */
internal fun exactExtensionLibraryRecoveryMatchV2(
    publicationKey: PublicationKey,
    sourceKey: SourceKey,
    publications: Iterable<RemotePublicationV2>,
): ExtensionLibraryBindingV2? = publications
    .asSequence()
    .filter { publication ->
        extensionPublicationKey(sourceKey, publication.remoteId) == publicationKey
    }
    .map { publication ->
        ExtensionLibraryBindingV2(
            publicationKey = publicationKey,
            sourceKey = sourceKey,
            remotePublicationId = publication.remoteId,
        )
    }
    .singleOrNull()
