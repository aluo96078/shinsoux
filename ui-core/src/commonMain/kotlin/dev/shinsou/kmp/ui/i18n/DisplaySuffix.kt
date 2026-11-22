package dev.shinsou.kmp.ui.i18n

/** `hath.network:1024-65535` reads as the host family with its port range spelled out. */
public fun String.displaySuffix(strings: ShinsouStrings): String {
    val base = removeSuffix(":1024-65535")
    return if (base == this) this else strings.text(StringKey.X_ANY_PORT_FROM_1024, base)
}
