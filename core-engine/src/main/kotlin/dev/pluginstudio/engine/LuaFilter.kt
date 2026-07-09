package dev.pluginstudio.engine

data class LuaFilter(
    val type: String,        // "select", "checkbox", "tristate", "switch", "text", "sort"
    val key: String,
    val label: String,
    val defaultValue: String? = null,
    val options: List<FilterOption> = emptyList(),
    val multiselect: Boolean = true
)

data class FilterOption(
    val value: String,
    val label: String
)

data class ActiveFilters(
    val filters: Map<String, List<String>> = emptyMap()
) {
    val isEmpty: Boolean get() = filters.isEmpty()
}
