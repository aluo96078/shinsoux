package dev.shinsou.kmp.plugin.network

/**
 * The Public Suffix List (ICANN and private sections), bundled at build time from
 * `composeApp/psl/public_suffix_list.dat` (see its README for the pinned version and updates).
 *
 * Host names are compared as lowercase ASCII: internationalized rules were converted to punycode
 * when the data was generated. A host that is not plain ASCII is treated as a public suffix
 * (fail closed), as is any single label: the list's implicit `*` rule.
 */
internal object PublicSuffixList {
    private class Rules(val normal: Set<String>, val wildcards: Set<String>, val exceptions: Set<String>)

    private val rules: Rules by lazy {
        val normal = HashSet<String>(16_384)
        val wildcards = HashSet<String>(256)
        val exceptions = HashSet<String>(64)
        PUBLIC_SUFFIX_RULE_CHUNKS.joinToString("").split('\n').forEach { rule ->
            when {
                rule.isEmpty() -> Unit
                rule.startsWith("!") -> exceptions += rule.substring(1)
                rule.startsWith("*.") -> wildcards += rule.substring(2)
                else -> normal += rule
            }
        }
        Rules(normal, wildcards, exceptions)
    }

    /** The public suffix of [host] (e.g. `co.uk` for `www.example.co.uk`), or null for an unusable host. */
    fun publicSuffixOf(host: String): String? {
        val labels = normalizedLabels(host) ?: return null
        val rules = rules
        for (start in labels.indices) {
            val candidate = labels.subList(start, labels.size).joinToString(".")
            if (candidate in rules.exceptions) return labels.subList(start + 1, labels.size).joinToString(".")
            if (candidate in rules.normal) return candidate
            if (start + 1 < labels.size && labels.subList(start + 1, labels.size).joinToString(".") in rules.wildcards) {
                return candidate
            }
        }
        return labels.last()
    }

    /** True when [domain] is itself a public suffix, so a cookie or a host grant must not cover it. */
    fun isPublicSuffix(domain: String): Boolean {
        val normalized = normalizedLabels(domain)?.joinToString(".") ?: return true
        return publicSuffixOf(normalized) == normalized
    }

    /** The registrable domain of [host] (public suffix plus one label), or null when [host] is a public suffix. */
    fun registrableDomain(host: String): String? {
        val labels = normalizedLabels(host) ?: return null
        val suffix = publicSuffixOf(host) ?: return null
        val suffixLabels = suffix.count { it == '.' } + 1
        if (labels.size <= suffixLabels) return null
        return labels.subList(labels.size - suffixLabels - 1, labels.size).joinToString(".")
    }

    private fun normalizedLabels(host: String): List<String>? {
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty() || normalized.length > 253) return null
        if (normalized.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.') }) return null
        val labels = normalized.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        return labels
    }
}
