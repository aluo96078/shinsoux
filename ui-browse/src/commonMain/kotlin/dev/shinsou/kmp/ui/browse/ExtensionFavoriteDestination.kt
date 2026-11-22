package dev.shinsou.kmp.ui.browse

// 0008 §7-5: from the shell's LocalLibraryExtensionRouting.kt; the publication page owns where a favorite goes.
/** Selects the explicit favorite owner advertised by an extension source. */
public enum class ExtensionFavoriteDestination {
    LOCAL_LIBRARY,
    SOURCE_ACCOUNT,
}

public fun extensionFavoriteDestination(
    sourceSupportsFavorites: Boolean?,
): ExtensionFavoriteDestination = if (sourceSupportsFavorites == true) {
    ExtensionFavoriteDestination.SOURCE_ACCOUNT
} else {
    ExtensionFavoriteDestination.LOCAL_LIBRARY
}

/** Executes exactly one favorite mutation boundary. */
public suspend fun mutateExtensionFavorite(
    destination: ExtensionFavoriteDestination,
    localMutation: suspend () -> Unit,
    sourceMutation: suspend () -> Unit,
) {
    when (destination) {
        ExtensionFavoriteDestination.LOCAL_LIBRARY -> localMutation()
        ExtensionFavoriteDestination.SOURCE_ACCOUNT -> sourceMutation()
    }
}
