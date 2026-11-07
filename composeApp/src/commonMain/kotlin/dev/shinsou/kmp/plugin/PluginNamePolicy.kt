package dev.shinsou.kmp.plugin

/**
 * There are no official plugins: every package is a third party's (ADR-0002). A package, source
 * or repository name that claims otherwise is refused at install and before trusting a publisher,
 * the same rule the `@shinsou/plugin` CLI's `lint` applies.
 *
 * Compared case-insensitively, with full-width forms folded, every separator (and zero-width
 * character) removed and look-alike digits read as letters, so `Shinsou_Official`, `ＯＦＦＩＣＩＡＬ`,
 * `0fficial` and `官 方` all match.
 */
public object PluginNamePolicy {
    private val MISLEADING = listOf("official", "offical", "官方", "公式")

    /** Explicit disclaimers are fine; anything left after removing them still counts. */
    private val DISCLAIMERS = listOf("unofficial", "unoffical", "nonofficial", "非官方", "非公式")

    public fun isMisleading(name: String): Boolean {
        val folded = DISCLAIMERS.fold(fold(name)) { text, disclaimer -> text.replace(disclaimer, " ") }
        return MISLEADING.any { it in folded }
    }

    /** Throws for the first misleading name among [names]. */
    public fun requireNotMisleading(vararg names: String?) {
        names.filterNotNull().firstOrNull(::isMisleading)?.let { throw MisleadingPluginNameException(it) }
    }

    private fun fold(name: String): String = buildString {
        for (char in name) {
            val c = if (char in '！'..'～') (char.code - 0xFEE0).toChar() else char
            // Look-alike digits (`0fficial`, `offic1al`) count as the letters they imitate.
            if (c.isLetterOrDigit()) append(when (c) { '0' -> 'o'; '1' -> 'i'; else -> c.lowercaseChar() })
        }
    }
}

public class MisleadingPluginNameException(public val name: String) :
    IllegalArgumentException("'$name' claims to be official; plugins cannot use that name")
