package dev.shinsou.kmp.plugin.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// R4-4 (docs/architecture/0006): moved out of ExtensionV2Contracts.kt without changing it.

@Serializable
public data class BrowseOptionsV2(
    val values: Map<String, String> = emptyMap(),
    /** Structured source filters projected from the legacy FilterList model. */
    val filters: List<BrowseFilterV2> = emptyList(),
) {
    init { validate() }

    public fun validate(): Unit {
        require(values.size <= MAX_PARAMETERS)
        values.forEach { (key, value) ->
            requireSafeIdentifier(key, "Browse option key")
            require(value.length <= MAX_PARAMETER_VALUE_LENGTH && value.none(Char::isISOControl))
        }
        validateBrowseFilters(filters)
    }
}

@Serializable
public data class BrowseOptionsSchemaV2(
    val keys: List<String> = emptyList(),
    /** Editable source filters, in the same order and shape as the legacy FilterList. */
    val filters: List<BrowseFilterV2> = emptyList(),
) {
    init { validate() }

    public fun validate(): Unit {
        require(keys.size <= MAX_PARAMETERS && keys.distinct().size == keys.size)
        keys.forEach { requireSafeIdentifier(it, "Browse option key") }
        validateBrowseFilters(filters)
    }
}

/**
 * Serializable v2 projection of the legacy source FilterList.
 *
 * Keeping this model in the contract (instead of leaking the host UI's BrowseFilter type) lets
 * native and legacy-backed v2 sources expose the same Select/Text/CheckBox/TriState/Group/Sort
 * controls.  The host converts it to its platform-neutral UI model before rendering.
 */
@Serializable
public sealed interface BrowseFilterV2 {
    public val name: String

    @Serializable
    @SerialName("header")
    public data class Header(override val name: String) : BrowseFilterV2

    @Serializable
    @SerialName("separator")
    public data object Separator : BrowseFilterV2 {
        override val name: String = ""
    }

    @Serializable
    @SerialName("select")
    public data class Select(
        override val name: String,
        val values: List<String>,
        val state: Int,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("text")
    public data class Text(
        override val name: String,
        val state: String,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("checkBox")
    public data class CheckBox(
        override val name: String,
        val state: Boolean,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("triState")
    public data class TriState(
        override val name: String,
        val state: BrowseTriStateV2,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("group")
    public data class Group(
        override val name: String,
        val filters: List<BrowseFilterV2>,
    ) : BrowseFilterV2

    @Serializable
    @SerialName("sort")
    public data class Sort(
        override val name: String,
        val values: List<String>,
        val selection: BrowseSortSelectionV2?,
    ) : BrowseFilterV2
}

@Serializable
public enum class BrowseTriStateV2 {
    IGNORE,
    INCLUDE,
    EXCLUDE,
}

@Serializable
public data class BrowseSortSelectionV2(
    val index: Int,
    val ascending: Boolean,
)
