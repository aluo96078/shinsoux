package dev.shinsou.kmp.domain.model

/** A deterministic image operation that must survive online and downloaded reader paths. */
public sealed interface ReaderImageTransform {
    public data class ReverseVerticalSegments(val segmentCount: Int) : ReaderImageTransform {
        init {
            require(segmentCount > 1) { "A segmented image needs at least two segments" }
        }
    }

    public fun encodeSidecar(): ByteArray = when (this) {
        is ReverseVerticalSegments -> "$SIDECAR_PREFIX$segmentCount".encodeToByteArray()
    }

    public companion object {
        private const val SIDECAR_PREFIX = "reverse-vertical-segments:"

        public fun decodeSidecar(value: ByteArray?): ReaderImageTransform? {
            val encoded = value?.decodeToString()?.trim()
            return encoded?.takeIf { it.startsWith(SIDECAR_PREFIX) }
                ?.removePrefix(SIDECAR_PREFIX)
                ?.toIntOrNull()
                ?.takeIf { it > 1 }
                ?.let(::ReverseVerticalSegments)
        }
    }
}
