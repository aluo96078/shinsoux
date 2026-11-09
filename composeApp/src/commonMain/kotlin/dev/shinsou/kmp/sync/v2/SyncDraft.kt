package dev.shinsou.kmp.sync.v2

import kotlinx.serialization.Serializable

/**
 * One locally committed, not yet delivered sync event. The content transaction store keeps these
 * in its outbox; nothing drains them while no sync service is configured.
 */
@Serializable
data class SyncDraft(
    val draftId: String,
    val event: SyncEvent,
    val coalescingKey: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
) {
    init {
        require(draftId.isNotBlank()) { "Draft id cannot be blank" }
        require(coalescingKey == null || coalescingKey.isNotBlank()) { "Coalescing key cannot be blank" }
        require(createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis) { "Invalid draft timestamps" }
    }
}

/** Small immutable byte container for codec boundaries. */
class BinaryData(bytes: Collection<Byte>) {
    private val value: List<Byte> = bytes.toList()

    val size: Int get() = value.size

    fun copyBytes(): ByteArray = value.toByteArray()

    override fun equals(other: Any?): Boolean = other is BinaryData && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "BinaryData(${value.size} bytes)"

    companion object {
        fun copyOf(bytes: ByteArray): BinaryData = BinaryData(bytes.asList())
        val Empty: BinaryData = BinaryData(emptyList())
    }
}
