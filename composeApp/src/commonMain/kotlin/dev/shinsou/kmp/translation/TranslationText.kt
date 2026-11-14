package dev.shinsou.kmp.translation

import dev.shinsou.kmp.plugin.Sha256

/** Text handling shared by every provider: segmentation, identity and target-language clean-up. */
public object TranslationText {
    /** A chapter is translated line by line: novel sources deliver one paragraph per line. */
    public fun segments(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /**
     * Identity of a segment in the translation memory. Whitespace at the ends does not change a
     * translation, so it does not change the identity either.
     */
    public fun hash(segment: String): String = Sha256.hex(segment.trim().encodeToByteArray())

    /**
     * Machine translation into Chinese or Japanese tends to keep ASCII punctuation. Converts the
     * marks that follow a CJK character to their full-width forms; Latin text inside the
     * translation ("Chapter 3, part 2") is left alone.
     */
    public fun localizePunctuation(text: String, language: String): String {
        val base = language.substringBefore('-').lowercase()
        if (base != "zh" && base != "ja") return text
        val out = StringBuilder(text.length)
        var afterFullWidth = false
        for (char in text) {
            // A full-width mark carries its own spacing: the ASCII space after it goes too.
            if (afterFullWidth && char == ' ') continue
            afterFullWidth = false
            val replacement = FULL_WIDTH[char]
            val previous = out.lastOrNull { !it.isWhitespace() }
            if (replacement != null && previous != null && isCjk(previous)) {
                // The space machine translation leaves before the mark goes with it.
                while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
                out.append(replacement)
                afterFullWidth = true
            } else {
                out.append(char)
            }
        }
        return out.toString()
    }

    internal fun isCjk(char: Char): Boolean = char in '぀'..'ヿ' || char in '㐀'..'䶿' ||
        char in '一'..'鿿' || char in '豈'..'﫿' || char in '가'..'힯' ||
        char in "，。？！：；、」』）"

    private val FULL_WIDTH = mapOf(',' to '，', '?' to '？', '!' to '！', ':' to '：', ';' to '；')
}
