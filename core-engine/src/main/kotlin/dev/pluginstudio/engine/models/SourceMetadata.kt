package dev.pluginstudio.engine.models

data class SourceMetadata(
    val id: String = "",
    val name: String = "Unknown",
    val version: String = "1.0.0",
    val description: String = "",
    val url: String = "",
    val icon: String = "",
    val language: String = "en",
    val charset: String? = null
)
