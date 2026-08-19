package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.ui.BrowseFilter
import dev.shinsou.kmp.ui.BrowseSortSelection
import dev.shinsou.kmp.ui.BrowseTriState

internal fun Filter.toBrowseFilter(): BrowseFilter = when (this) {
    is Filter.Header -> BrowseFilter.Header(name)
    Filter.Separator -> BrowseFilter.Separator
    is Filter.Select -> BrowseFilter.Select(name, values, state)
    is Filter.Text -> BrowseFilter.Text(name, state)
    is Filter.CheckBox -> BrowseFilter.CheckBox(name, state)
    is Filter.TriState -> BrowseFilter.TriState(
        name = name,
        state = when (state) {
            TriStateValue.IGNORE -> BrowseTriState.Ignore
            TriStateValue.INCLUDE -> BrowseTriState.Include
            TriStateValue.EXCLUDE -> BrowseTriState.Exclude
        },
    )
    is Filter.Group -> BrowseFilter.Group(name, filters.map { it.toBrowseFilter() })
    is Filter.Sort -> BrowseFilter.Sort(
        name = name,
        values = values,
        selection = selection?.let { BrowseSortSelection(it.index, it.ascending) },
    )
}

internal fun BrowseFilter.toPluginFilter(): Filter = when (this) {
    is BrowseFilter.Header -> Filter.Header(name)
    BrowseFilter.Separator -> Filter.Separator
    is BrowseFilter.Select -> Filter.Select(name, values, state)
    is BrowseFilter.Text -> Filter.Text(name, state)
    is BrowseFilter.CheckBox -> Filter.CheckBox(name, state)
    is BrowseFilter.TriState -> Filter.TriState(
        name = name,
        state = when (state) {
            BrowseTriState.Ignore -> TriStateValue.IGNORE
            BrowseTriState.Include -> TriStateValue.INCLUDE
            BrowseTriState.Exclude -> TriStateValue.EXCLUDE
        },
    )
    is BrowseFilter.Group -> Filter.Group(name, filters.map { it.toPluginFilter() })
    is BrowseFilter.Sort -> Filter.Sort(
        name = name,
        values = values,
        selection = selection?.let { SortSelection(it.index, it.ascending) },
    )
}
