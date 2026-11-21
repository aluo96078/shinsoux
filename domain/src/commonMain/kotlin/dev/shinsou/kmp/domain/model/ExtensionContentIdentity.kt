package dev.shinsou.kmp.domain.model

/*
 * The typed-content identities an exact extension source's publications and units materialize
 * into. They are shared by the plugin consumer, library migration, sync and the browse screens,
 * so they live with the identity types they derive.
 */

/**
 * Returns the stable typed-content publication identity for an extension publication.
 *
 * The browse UI uses this before a unit has been materialized so a locally favourited novel can
 * share the exact compatibility URL that the reader/history bridge creates later. Keep this
 * derivation in one place; changing it would split one remote publication into two local rows.
 */
fun extensionAcquisitionId(
    sourceKey: SourceKey,
    remotePublicationId: String,
): String {
    sourceKey.validate()
    require(remotePublicationId.isNotBlank()) { "Remote publication id cannot be blank" }
    return Rfc9562UuidV5.derive(
        EXTENSION_CONTENT_NAMESPACE,
        framedIdentity("acquisition", sourceKey.canonicalId, remotePublicationId),
    )
}

/** The typed unit an exact source unit materializes into; shared with library migration. */
fun extensionUnitKey(
    sourceKey: SourceKey,
    remotePublicationId: String,
    remoteUnitId: String,
): UnitKey {
    require(remoteUnitId.isNotBlank()) { "Remote unit id cannot be blank" }
    return UnitKey(
        extensionPublicationKey(sourceKey, remotePublicationId),
        Rfc9562UuidV5.derive(
            EXTENSION_CONTENT_NAMESPACE,
            framedIdentity("unit", sourceKey.canonicalId, remotePublicationId, remoteUnitId),
        ),
    )
}

fun extensionPublicationKey(
    sourceKey: SourceKey,
    remotePublicationId: String,
): PublicationKey {
    sourceKey.validate()
    require(remotePublicationId.isNotBlank()) { "Remote publication id cannot be blank" }
    return PublicationKey(
        Rfc9562UuidV5.derive(
            EXTENSION_CONTENT_NAMESPACE,
            framedIdentity("publication", sourceKey.canonicalId, remotePublicationId),
        ),
    )
}

fun framedIdentity(vararg fields: String): String = buildString {
    append("shinsou-extension-content-v2")
    fields.forEach { field ->
        append('|')
        append(field.encodeToByteArray().size)
        append(':')
        append(field)
    }
}

val EXTENSION_CONTENT_NAMESPACE = MigrationNamespaceId("bcdcd2d9-9908-5e51-a976-f3b8c19af82a")
