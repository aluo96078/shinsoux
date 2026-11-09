package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.plugin.Sha256

/**
 * Work identity shared with the sync server (shinsou_sync_service `docs/spec/work-key.md`).
 *
 * Components are used exactly as given: nothing is trimmed, case-folded, Unicode-normalised or
 * URL-rewritten. Any change to [canonical] must bump [keyVersion], otherwise one work splits
 * into two on every server.
 */
public data class WorkKey(
    val keyVersion: Int,
    val packageId: String,
    val sourceId: String,
    val remoteId: String,
) {
    init {
        require(keyVersion == CURRENT_KEY_VERSION) { "Unsupported work key version $keyVersion" }
        requireComponent("packageId", packageId, MAX_PACKAGE_ID_BYTES)
        requireComponent("sourceId", sourceId, MAX_SOURCE_ID_BYTES)
        requireComponent("remoteId", remoteId, MAX_REMOTE_ID_BYTES)
    }

    /** `w1|` followed by UTF-8-byte-length-prefixed components. */
    public val canonical: String
        get() = "w1|" + lp(packageId) + lp(sourceId) + lp(remoteId)

    /** Lowercase hex SHA-256 of [canonical]. */
    public val hash: String
        get() = Sha256.hex(canonical.encodeToByteArray())

    public fun unitCanonical(unitRemoteId: String): String {
        requireComponent("unitRemoteId", unitRemoteId, MAX_REMOTE_ID_BYTES)
        return "u1|" + lp(packageId) + lp(sourceId) + lp(remoteId) + lp(unitRemoteId)
    }

    public fun unitHash(unitRemoteId: String): String = Sha256.hex(unitCanonical(unitRemoteId).encodeToByteArray())

    public companion object {
        public const val CURRENT_KEY_VERSION: Int = 1
        public const val MAX_PACKAGE_ID_BYTES: Int = 128
        public const val MAX_SOURCE_ID_BYTES: Int = 128
        public const val MAX_REMOTE_ID_BYTES: Int = 1024

        private fun lp(value: String): String = "${value.encodeToByteArray().size}:$value"

        private fun requireComponent(name: String, value: String, maxBytes: Int) {
            require(!hasLoneSurrogate(value)) { "Work key $name is not valid Unicode" }
            require(!value.all { isUnicodeWhiteSpace(it) }) { "Work key $name is blank" }
            require(value.none { isControl(it) }) { "Work key $name contains a control character" }
            require(value.encodeToByteArray().size <= maxBytes) { "Work key $name exceeds $maxBytes bytes" }
        }

        private fun hasLoneSurrogate(value: String): Boolean {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c.isHighSurrogate()) {
                    if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return true
                    i += 2
                    continue
                }
                if (c.isLowSurrogate()) return true
                i++
            }
            return false
        }

        /** Unicode `Cc`: U+0000–U+001F and U+007F–U+009F. */
        private fun isControl(c: Char): Boolean = c.code <= 0x1F || c.code in 0x7F..0x9F

        /**
         * The Unicode `White_Space` property, spelled out so Kotlin matches Go's `unicode.IsSpace`
         * instead of depending on platform `isWhitespace` definitions.
         */
        private fun isUnicodeWhiteSpace(c: Char): Boolean = when (c.code) {
            in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            else -> false
        }
    }
}
