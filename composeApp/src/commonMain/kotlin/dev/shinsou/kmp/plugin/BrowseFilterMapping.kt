package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.ui.BrowseFilter
import dev.shinsou.kmp.ui.BrowseSortSelection
import dev.shinsou.kmp.ui.BrowseTriState
import dev.shinsou.kmp.plugin.v2.BrowseFilterV2
import dev.shinsou.kmp.plugin.v2.BrowseSortSelectionV2
import dev.shinsou.kmp.plugin.v2.BrowseTriStateV2

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

internal fun Filter.toBrowseFilterV2(): BrowseFilterV2 = when (this) {
    is Filter.Header -> BrowseFilterV2.Header(name)
    Filter.Separator -> BrowseFilterV2.Separator
    is Filter.Select -> BrowseFilterV2.Select(name, values, state)
    is Filter.Text -> BrowseFilterV2.Text(name, state)
    is Filter.CheckBox -> BrowseFilterV2.CheckBox(name, state)
    is Filter.TriState -> BrowseFilterV2.TriState(
        name = name,
        state = when (state) {
            TriStateValue.IGNORE -> BrowseTriStateV2.IGNORE
            TriStateValue.INCLUDE -> BrowseTriStateV2.INCLUDE
            TriStateValue.EXCLUDE -> BrowseTriStateV2.EXCLUDE
        },
    )
    is Filter.Group -> BrowseFilterV2.Group(name, filters.map { it.toBrowseFilterV2() })
    is Filter.Sort -> BrowseFilterV2.Sort(
        name = name,
        values = values,
        selection = selection?.let { BrowseSortSelectionV2(it.index, it.ascending) },
    )
}

internal fun BrowseFilterV2.toPluginFilter(): Filter = when (this) {
    is BrowseFilterV2.Header -> Filter.Header(name)
    BrowseFilterV2.Separator -> Filter.Separator
    is BrowseFilterV2.Select -> Filter.Select(name, values, state)
    is BrowseFilterV2.Text -> Filter.Text(name, state)
    is BrowseFilterV2.CheckBox -> Filter.CheckBox(name, state)
    is BrowseFilterV2.TriState -> Filter.TriState(
        name = name,
        state = when (state) {
            BrowseTriStateV2.IGNORE -> TriStateValue.IGNORE
            BrowseTriStateV2.INCLUDE -> TriStateValue.INCLUDE
            BrowseTriStateV2.EXCLUDE -> TriStateValue.EXCLUDE
        },
    )
    is BrowseFilterV2.Group -> Filter.Group(name, filters.map { it.toPluginFilter() })
    is BrowseFilterV2.Sort -> Filter.Sort(
        name = name,
        values = values,
        selection = selection?.let { SortSelection(it.index, it.ascending) },
    )
}

internal fun BrowseFilterV2.toBrowseFilter(): BrowseFilter = when (this) {
    is BrowseFilterV2.Header -> BrowseFilter.Header(name)
    BrowseFilterV2.Separator -> BrowseFilter.Separator
    is BrowseFilterV2.Select -> BrowseFilter.Select(name, values, state)
    is BrowseFilterV2.Text -> BrowseFilter.Text(name, state)
    is BrowseFilterV2.CheckBox -> BrowseFilter.CheckBox(name, state)
    is BrowseFilterV2.TriState -> BrowseFilter.TriState(
        name = name,
        state = when (state) {
            BrowseTriStateV2.IGNORE -> BrowseTriState.Ignore
            BrowseTriStateV2.INCLUDE -> BrowseTriState.Include
            BrowseTriStateV2.EXCLUDE -> BrowseTriState.Exclude
        },
    )
    is BrowseFilterV2.Group -> BrowseFilter.Group(name, filters.map { it.toBrowseFilter() })
    is BrowseFilterV2.Sort -> BrowseFilter.Sort(
        name = name,
        values = values,
        selection = selection?.let { BrowseSortSelection(it.index, it.ascending) },
    )
}

internal fun BrowseFilter.toBrowseFilterV2(): BrowseFilterV2 = when (this) {
    is BrowseFilter.Header -> BrowseFilterV2.Header(name)
    BrowseFilter.Separator -> BrowseFilterV2.Separator
    is BrowseFilter.Select -> BrowseFilterV2.Select(name, values, state)
    is BrowseFilter.Text -> BrowseFilterV2.Text(name, state)
    is BrowseFilter.CheckBox -> BrowseFilterV2.CheckBox(name, state)
    is BrowseFilter.TriState -> BrowseFilterV2.TriState(
        name = name,
        state = when (state) {
            BrowseTriState.Ignore -> BrowseTriStateV2.IGNORE
            BrowseTriState.Include -> BrowseTriStateV2.INCLUDE
            BrowseTriState.Exclude -> BrowseTriStateV2.EXCLUDE
        },
    )
    is BrowseFilter.Group -> BrowseFilterV2.Group(name, filters.map { it.toBrowseFilterV2() })
    is BrowseFilter.Sort -> BrowseFilterV2.Sort(
        name = name,
        values = values,
        selection = selection?.let { BrowseSortSelectionV2(it.index, it.ascending) },
    )
}
