package dev.pluginstudio.engine

data class LuaSetting(
    val type: String,        // "text", "number", "boolean", "select", "multiselect"
    val key: String,
    val label: String,
    val defaultValue: String? = null,
    val options: List<SettingOption> = emptyList()
)

data class SettingOption(
    val value: String,
    val label: String
)
