package dev.shinsou.kmp.domain.model

import kotlinx.serialization.Serializable

/** A host-owned opaque reference persisted by Acquisition. */
@Serializable
public data class RightsGrantRef(
    val value: String,
) {
    init { requireUuid(value, "Rights grant reference") }

    public fun validate(): Unit = requireUuid(value, "Rights grant reference")
}

private fun requireUuid(value: String, label: String) {
    require(UUID_PATTERN.matches(value) && value != NIL_UUID) {
        "$label must be a lowercase non-NIL UUID"
    }
}

private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
