package dev.shinsou.kmp.navigation

/**
 * 0008: where a wide window (tablet, desktop) draws the pushed pages. It reads only the
 * [PageLayer] order, like the phone does (the phone shows [PageStack.top]) and like Back does
 * ([backTransition]), so what is in front is what Back dismisses first.
 *
 * The main area shows the section, or the More page when one is open ([PageStack.more]). Over it
 * come [cover] (an opaque page that replaces what the main area shows) and [side] (the detail
 * column). [front] is the one drawn in front of the other. When [side] fills the window (not
 * [sideBeside]) and a [cover] is set too, the cover is drawn over the whole window.
 */
internal class WideArrangement(
    /** A page drawn over the main area (a title resolving, a recovery, or the detail when it does not fit beside). */
    val cover: PageEntry?,
    /** The page in the detail column: a library publication or a title's detail. */
    val side: PageEntry?,
    /** The column sits beside the main area; otherwise it takes the whole window. */
    val sideBeside: Boolean,
) {
    /** The page drawn in front: the highest layer of [cover] and [side]; null when only the main area shows. */
    val front: PageEntry? get() = listOfNotNull(cover, side).maxByOrNull { it.layer }
}

/**
 * The arrangement of [state]'s pages in a window that fits [splitDetail] a column beside the
 * main area. An extension reader covers the whole window, so it never sits beside.
 */
internal fun wideArrangement(state: NavigationState, splitDetail: Boolean): WideArrangement {
    val stack = state.stack
    return if (splitDetail && !state.browseReaderOpen) {
        // Room for both: a title resolving or a recovery covers the main area, the detail column
        // keeps the library publication, else the detail (a higher layer wins the one column).
        WideArrangement(
            cover = stack.recovering ?: stack.pending,
            side = stack.localExtension ?: stack.detail,
            sideBeside = true,
        )
    } else {
        // One page fills the window: the front one, as on a phone. An open extension reader stays
        // mounted beneath a page that covers it (a recovery a deep link starts), so Back reveals
        // the same chapter instead of a fresh chapter list with its reader session lost.
        val front = stack.top
        WideArrangement(
            cover = front?.takeIf { it.layer != PageLayer.MORE && it.layer != PageLayer.LOCAL_EXTENSION },
            side = if (state.browseReaderOpen) stack.localExtension else front?.takeIf { it.layer == PageLayer.LOCAL_EXTENSION },
            sideBeside = false,
        )
    }
}
