package dev.shinsou.kmp.domain.model

/** [cfi] (a point CFI ending in a text offset) moved to [offsetUtf16] within the same text node. */
public fun cfiWithTextOffset(cfi: String, offsetUtf16: Int): String {
    require(offsetUtf16 >= 0) { "EPUB CFI text offset must be non-negative" }
    val colon = cfi.lastIndexOf(':')
    require(colon >= 0 && cfi.endsWith(')')) { "EPUB CFI has no terminal text offset" }
    cfi.substring(colon + 1, cfi.length - 1).toIntOrNull()
        ?: throw IllegalArgumentException("EPUB CFI terminal text offset is invalid")
    return cfi.substring(0, colon + 1) + offsetUtf16 + ')'
}
