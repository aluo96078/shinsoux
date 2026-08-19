package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.ui.BrowseFilter
import dev.shinsou.kmp.ui.BrowseSortSelection
import dev.shinsou.kmp.ui.BrowseTriState
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseFilterMappingTest {
    @Test
    fun roundTripsEveryFilterTypeAndNestedState() {
        val pluginFilters: FilterList = listOf(
            Filter.Header("Search"),
            Filter.Separator,
            Filter.Select("Genre", listOf("All", "Action"), 1),
            Filter.Text("Author", "Ada"),
            Filter.CheckBox("Completed", true),
            Filter.TriState("Adult", TriStateValue.EXCLUDE),
            Filter.Group(
                "Nested",
                listOf(
                    Filter.CheckBox("Official", false),
                    Filter.TriState("Licensed", TriStateValue.INCLUDE),
                ),
            ),
            Filter.Sort("Order", listOf("Date", "Title"), SortSelection(1, ascending = false)),
        )

        val uiFilters = pluginFilters.map { it.toBrowseFilter() }

        assertEquals(pluginFilters, uiFilters.map { it.toPluginFilter() })
        assertEquals(BrowseFilter.Header("Search"), uiFilters[0])
        assertEquals(BrowseFilter.Separator, uiFilters[1])
        assertEquals(BrowseTriState.Exclude, (uiFilters[5] as BrowseFilter.TriState).state)
        assertEquals(
            BrowseSortSelection(1, ascending = false),
            (uiFilters[7] as BrowseFilter.Sort).selection,
        )
    }

    @Test
    fun preservesNullableSortAndAllTriStateValues() {
        assertEquals(
            Filter.Sort("Order", listOf("Date"), null),
            BrowseFilter.Sort("Order", listOf("Date"), null).toPluginFilter(),
        )
        BrowseTriState.entries.forEach { state ->
            val ui = BrowseFilter.TriState("State", state)
            assertEquals(ui, ui.toPluginFilter().toBrowseFilter())
        }
    }
}
