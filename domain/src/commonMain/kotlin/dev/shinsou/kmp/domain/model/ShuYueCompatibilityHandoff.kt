package dev.shinsou.kmp.domain.model

/*
 * The marker the ShuYue importer and the standalone source migrations share: a ShuYue
 * compatibility row that one of them handed to a native/standalone source records it among the
 * snapshot's projection markers, and the other then leaves that row alone.
 */

/** Prevents a compatibility row already handed to a native/standalone source from being rebuilt. */
public fun shuyueCompatibilityProjectionHandoffMarker(publicationKey: PublicationKey): String =
    "$SHUYUE_COMPATIBILITY_PROJECTION_HANDOFF_PREFIX${publicationKey.value}"

private const val SHUYUE_COMPATIBILITY_PROJECTION_HANDOFF_PREFIX: String =
    "migration.shuyue.compatibility-handoff."
