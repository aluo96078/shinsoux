package dev.shinsou.kmp.content

import kotlinx.serialization.Serializable

/**
 * Small, body-free content authority which must survive a portable backup round trip.
 *
 * These rows deliberately remain separate from publications and immutable bodies: metadata
 * carries migration-owned categories/progress/settings, aliases preserve stable legacy identity,
 * and ledgers keep an inspected import idempotent after moving to another installation.
 */
@Serializable
public data class ContentPortableAuxiliaryState(
    val metadata: List<ContentMetadataMutation> = emptyList(),
    val aliases: List<ContentAliasMutation> = emptyList(),
    val migrations: List<ContentMigrationLedgerMutation> = emptyList(),
) {
    init { validate() }

    public fun validate(): ContentPortableAuxiliaryState {
        require(metadata.map(ContentMetadataMutation::key).distinct().size == metadata.size) {
            "Portable content metadata keys must be unique"
        }
        require(aliases.map(ContentAliasMutation::alias).distinct().size == aliases.size) {
            "Portable content aliases must be unique"
        }
        require(
            migrations.map(ContentMigrationLedgerMutation::migrationKey).distinct().size ==
                migrations.size,
        ) { "Portable content migration ledgers must be unique" }
        return this
    }
}
