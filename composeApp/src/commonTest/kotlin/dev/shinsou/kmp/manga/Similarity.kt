package dev.shinsou.kmp.manga

/** 1 − edit distance / longer length: how alike two OCR readings are. */
internal fun similarity(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    val d = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = d[0]; d[0] = i
        for (j in 1..b.length) {
            val cur = d[j]
            d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
    }
    return 1.0 - d[b.length].toDouble() / maxOf(a.length, b.length)
}
