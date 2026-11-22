package dev.shinsou.kmp.ui.browse

// 0008 §7-5: from ShellSavedState; the publication page saves units by it and the shell restores them.
/**
 * A stable 64-bit identity of an extension unit id (FNV-1a over its characters), saved in place of
 * the id itself.
 */
public fun extensionUnitIdentity(remoteUnitId: String): Long {
    var hash = FNV_OFFSET_BASIS
    for (char in remoteUnitId) {
        hash = (hash xor char.code.toLong()) * FNV_PRIME
    }
    return hash
}

private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
