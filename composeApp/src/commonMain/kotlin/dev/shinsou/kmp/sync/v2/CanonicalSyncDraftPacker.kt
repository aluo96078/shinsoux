package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec

/**
 * Deterministically packs metadata mutations by their real RFC 8949 event encoding size.
 *
 * The Worker accepts at most 32 KiB of AEAD ciphertext. Keeping canonical plaintext at or below
 * 28 KiB leaves a fixed margin for the authentication tag and future envelope-compatible codec
 * overhead. [PACKING_HLC] deliberately uses the largest numeric encodings and a production-sized
 * UUID, so re-clocking a stored draft cannot make its canonical event larger.
 */
public object CanonicalSyncDraftPacker {
    public const val MAX_EVENT_PLAINTEXT_BYTES: Int = 28 * 1024
    public const val MAX_EVENT_CIPHERTEXT_BYTES: Int = 32 * 1024
    public const val AEAD_TAG_BYTES: Int = 16

    public fun pack(
        mutations: List<SyncMutation>,
        createdAtMillis: Long,
        draftId: (index: Int) -> String,
        maxPlaintextBytes: Int = MAX_EVENT_PLAINTEXT_BYTES,
    ): List<SyncDraft> {
        require(mutations.isNotEmpty()) { "Cannot pack an empty sync mutation list" }
        require(createdAtMillis >= 0) { "Sync draft time cannot be negative" }
        require(maxPlaintextBytes in 1..MAX_EVENT_PLAINTEXT_BYTES) {
            "Sync event plaintext budget exceeds the protocol-safe limit"
        }

        val drafts = ArrayList<SyncDraft>()
        var pending = ArrayList<SyncMutation>()
        mutations.forEach { mutation ->
            val index = drafts.size
            val candidate = pending + mutation
            if (encodedSize(draftId(index), candidate) <= maxPlaintextBytes) {
                pending = ArrayList(candidate)
            } else {
                if (pending.isEmpty()) {
                    throw IllegalArgumentException("One sync mutation exceeds the canonical event budget")
                }
                drafts += createDraft(draftId(index), pending, createdAtMillis, maxPlaintextBytes)
                pending = arrayListOf(mutation)
                require(encodedSize(draftId(drafts.size), pending) <= maxPlaintextBytes) {
                    "One sync mutation exceeds the canonical event budget"
                }
            }
        }
        if (pending.isNotEmpty()) {
            drafts += createDraft(draftId(drafts.size), pending, createdAtMillis, maxPlaintextBytes)
        }
        require(drafts.map(SyncDraft::draftId).distinct().size == drafts.size) {
            "Packed sync draft ids collide"
        }
        return drafts
    }

    public fun encodedSize(event: SyncEvent): Int = CODEC.encodeEvent(event).size

    public fun encodedSize(opId: String, mutations: List<SyncMutation>): Int = encodedSize(
        SyncEvent(opId = opId, hlc = PACKING_HLC, mutations = mutations),
    )

    public fun isMutationPackable(
        opId: String,
        mutation: SyncMutation,
        maxPlaintextBytes: Int = MAX_EVENT_PLAINTEXT_BYTES,
    ): Boolean = encodedSize(opId, listOf(mutation)) <= maxPlaintextBytes

    private fun createDraft(
        id: String,
        mutations: List<SyncMutation>,
        createdAtMillis: Long,
        maxPlaintextBytes: Int,
    ): SyncDraft {
        val event = SyncEvent(opId = id, hlc = PACKING_HLC, mutations = mutations.toList())
        check(encodedSize(event) <= maxPlaintextBytes) { "Packed sync event exceeds its canonical budget" }
        return SyncDraft(draftId = id, event = event, createdAtMillis = createdAtMillis)
    }

    private val CODEC = DeterministicSyncEventCodec()
    private val PACKING_HLC = HlcTimestamp(
        millis = Long.MAX_VALUE,
        counter = Int.MAX_VALUE,
        deviceId = "ffffffff-ffff-4fff-bfff-ffffffffffff",
    )
}
