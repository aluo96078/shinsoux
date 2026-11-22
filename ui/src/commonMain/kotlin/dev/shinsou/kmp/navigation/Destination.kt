package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.ui.screens.MoreDestination

/**
 * R4-3 (docs/architecture/0006): what a page of the app shell shows, by identity only. It is what
 * the phone layout animates between and what [AppNavigator.pageChanges] counts, so it holds
 * nothing a page reports about itself (its nested Back) and no request token.
 */
internal sealed interface Destination {
    /** Nothing pushed: the main section itself. */
    data object Section : Destination

    /** A beta-era extension favorite whose source identity is being searched for. */
    data class Recovering(val mangaId: Long) : Destination

    /** An extension publication opened from the local library. */
    data class LocalExtension(val item: BrowseManga) : Destination

    /** A source title that is still being resolved into a library row. */
    data class Pending(val item: BrowseManga) : Destination

    /** A title's detail page. */
    data class Manga(val mangaId: Long) : Destination

    /** A page of the More section. */
    data class More(val destination: MoreDestination) : Destination
}

/**
 * 0007 C2: the layer a [PageEntry] sits on. Pages stack in this order on a phone, lowest first:
 * a page covers every page of a lower layer. A layer holds at most one page.
 */
internal enum class PageLayer { MORE, DETAIL, PENDING, LOCAL_EXTENSION, RECOVERING }

/** One page pushed above the section, with what only that page instance owns. */
internal sealed interface PageEntry {
    val layer: PageLayer

    /** What the page shows. */
    val destination: Destination

    /** A page of the More section; [nestedBackAvailable] is reported by that page. */
    data class More(val page: MoreDestination, val nestedBackAvailable: Boolean = false) : PageEntry {
        override val layer get() = PageLayer.MORE
        override val destination get() = Destination.More(page)
    }

    /** A title's detail page; [nestedBackAvailable] is reported by that page (an open sheet). */
    data class Detail(val mangaId: Long, val nestedBackAvailable: Boolean = false) : PageEntry {
        override val layer get() = PageLayer.DETAIL
        override val destination get() = Destination.Manga(mangaId)
    }

    /**
     * A source title shown at once while it is resolved ([AppNavigator.openBrowseManga]).
     * [request] is minted anew for each one, so a resolution for an earlier one is dropped.
     */
    data class Pending(val item: BrowseManga, val request: Long) : PageEntry {
        override val layer get() = PageLayer.PENDING
        override val destination get() = Destination.Pending(item)
    }

    /** An extension publication opened from the local library. */
    data class LocalExtension(val item: BrowseManga) : PageEntry {
        override val layer get() = PageLayer.LOCAL_EXTENSION
        override val destination get() = Destination.LocalExtension(item)
    }

    /**
     * A beta-era favorite being repaired. [request] is minted anew for each recovery, so one
     * cancelled (by Back, another tab, a newer open) cannot navigate later.
     */
    data class Recovering(val mangaId: Long, val request: Long) : PageEntry {
        override val layer get() = PageLayer.RECOVERING
        override val destination get() = Destination.Recovering(mangaId)
    }
}

/**
 * 0007 C2: the pages pushed above the main section, ordered by [PageLayer] (lowest first). A
 * transition is a [push] (which replaces the page of the same layer), a [pop] of one layer, or
 * [clear]; every "is X shown" question is read off the stack instead of a field of its own.
 *
 * On a phone only [top] shows. A tablet or desktop shows several layers side by side (a detail
 * page beside the section, a library publication beside it), which is why lower layers are kept
 * under a higher one instead of being closed by it.
 */
internal class PageStack private constructor(private val entries: List<PageEntry>) {
    constructor() : this(emptyList())

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The page a phone shows: the highest layer's. */
    val top: PageEntry? get() = entries.lastOrNull()

    val more: PageEntry.More? get() = entry(PageLayer.MORE) as PageEntry.More?
    val detail: PageEntry.Detail? get() = entry(PageLayer.DETAIL) as PageEntry.Detail?
    val pending: PageEntry.Pending? get() = entry(PageLayer.PENDING) as PageEntry.Pending?
    val localExtension: PageEntry.LocalExtension? get() = entry(PageLayer.LOCAL_EXTENSION) as PageEntry.LocalExtension?
    val recovering: PageEntry.Recovering? get() = entry(PageLayer.RECOVERING) as PageEntry.Recovering?

    fun entry(layer: PageLayer): PageEntry? = entries.firstOrNull { it.layer == layer }

    /** [entry] on its layer, replacing the page that layer had. */
    fun push(entry: PageEntry): PageStack =
        PageStack((entries.filterNot { it.layer == entry.layer } + entry).sortedBy { it.layer })

    /** Without the pages of [layers]. */
    fun pop(vararg layers: PageLayer): PageStack =
        if (entries.none { it.layer in layers }) this else PageStack(entries.filterNot { it.layer in layers })

    /** Nothing pushed. */
    fun clear(): PageStack = if (entries.isEmpty()) this else PageStack()

    /** [current] (a page of this stack) replaced by [transform] of it; unchanged when it is null. */
    fun <T : PageEntry> update(current: T?, transform: (T) -> T): PageStack = current?.let { push(transform(it)) } ?: this

    override fun equals(other: Any?): Boolean = other is PageStack && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "PageStack$entries"

    companion object {
        fun of(vararg entries: PageEntry): PageStack = entries.fold(PageStack()) { stack, entry -> stack.push(entry) }
    }
}

/** 0007 C2: the pages whose own nested Back the shell's Back is handed to. */
internal enum class BackTarget { DETAIL, MORE, BROWSE, LOCAL_EXTENSION_READER }

/**
 * 0007 C2: Back presses handed to the page that owns a nested Back, counted per [BackTarget].
 * A count only ever grows (opening a page never resets it): a page compares the count with the
 * one it saw when it appeared, so a change always is a Back press and never a reset.
 */
internal class BackRequests private constructor(private val counts: Map<BackTarget, Long>) {
    constructor() : this(emptyMap())

    operator fun get(target: BackTarget): Long = counts[target] ?: 0L

    /** One more Back handed to [target]. */
    fun after(target: BackTarget): BackRequests = BackRequests(counts + (target to get(target) + 1))

    override fun equals(other: Any?): Boolean = other is BackRequests && other.counts == counts

    override fun hashCode(): Int = counts.hashCode()

    override fun toString(): String = "BackRequests$counts"
}

/**
 * Everything the shell needs to know to draw its pages and route Back: the [section], the
 * [stack] of pages above it, and the few facts that belong to the section or the window rather
 * than to a page.
 */
internal data class NavigationState(
    val section: MainSection,
    val stack: PageStack = PageStack(),
    /** Browse's own nested pages (a catalogue, a publication) can go back; reported by Browse. */
    val browseBackAvailable: Boolean = false,
    /** A legacy detail opened from a Browse catalogue, closed again with that catalogue. */
    val browseOwnedLegacyMangaId: Long? = null,
    /** An extension reader (Browse or local library) covers the whole window. */
    val browseReaderOpen: Boolean = false,
    val backRequests: BackRequests = BackRequests(),
    /** The last request token minted for a [PageEntry.Pending] or [PageEntry.Recovering]; only grows. */
    val requestSerial: Long = 0L,
) {
    /** The page a phone shows above the section ([Destination.Section] when none is pushed). */
    val destination: Destination get() = stack.top?.destination ?: Destination.Section

    val selectedMangaId: Long? get() = stack.detail?.mangaId
    val moreDestination: MoreDestination? get() = stack.more?.page
    val pendingBrowseManga: BrowseManga? get() = stack.pending?.item
    val localLibraryPublication: BrowseManga? get() = stack.localExtension?.item
    val recoveringLocalLibraryMangaId: Long? get() = stack.recovering?.mangaId
    val detailNestedBackAvailable: Boolean get() = stack.detail?.nestedBackAvailable == true
    val moreNestedBackAvailable: Boolean get() = stack.more?.nestedBackAvailable == true
}
