package dev.pluginstudio.web

import com.google.gson.Gson
import dev.pluginstudio.gen.ai.AiClient
import dev.pluginstudio.gen.ai.ProviderConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Persists AI provider configs at <repoRoot>/.pds-ai.json (gitignored, chmod 600). */
class AiSettingsStore(private val file: Path) {
    private val gson = Gson()
    private val providers = ConcurrentHashMap<String, ProviderConfig>()
    @Volatile var defaultProviderId: String = "zen"

    init {
        load()
        if (providers.isEmpty()) {
            val zen = ProviderConfig(
                id = "zen", name = "OpenCode Zen",
                baseUrl = "https://opencode.ai/zen/v1",
                apiKey = "", mode = "chat",
                defaultModel = System.getenv("ZEN_DEFAULT_MODEL") ?: ""
            )
            providers[zen.id] = zen
            save()
        }
    }

    fun list(): List<ProviderConfig> = providers.values.sortedBy { it.id }
    fun get(id: String): ProviderConfig? = providers[id]
    fun default(): ProviderConfig? = providers[defaultProviderId] ?: providers.values.firstOrNull()

    fun upsert(p: ProviderConfig): ProviderConfig {
        // preserve existing key when caller sends empty (masked round-trip)
        val merged = if (p.apiKey.isBlank()) {
            val old = providers[p.id]
            p.copy(apiKey = old?.apiKey ?: "")
        } else p
        providers[merged.id] = merged
        save()
        return merged
    }

    fun delete(id: String): Boolean {
        val removed = providers.remove(id) != null
        if (removed) save()
        return removed
    }

    @Synchronized
    private fun load() {
        try {
            if (!Files.exists(file)) return
            @Suppress("UNCHECKED_CAST")
            val root = gson.fromJson(Files.readString(file), Map::class.java) as Map<String, Any>
            defaultProviderId = root["default"]?.toString() ?: "zen"
            ((root["providers"] as? List<Map<String, Any>>) ?: emptyList()).forEach { m ->
                val p = ProviderConfig(
                    id = m["id"]?.toString() ?: return@forEach,
                    name = m["name"]?.toString() ?: m["id"].toString(),
                    baseUrl = m["baseUrl"]?.toString() ?: return@forEach,
                    apiKey = m["apiKey"]?.toString() ?: "",
                    mode = m["mode"]?.toString() ?: "chat",
                    defaultModel = m["defaultModel"]?.toString() ?: "",
                    headers = (m["headers"] as? Map<String, String>) ?: emptyMap()
                )
                providers[p.id] = p
            }
        } catch (_: Exception) { }
    }

    @Synchronized
    private fun save() {
        try {
            val json = mapOf(
                "default" to defaultProviderId,
                "providers" to providers.values.map { p ->
                    mapOf(
                        "id" to p.id, "name" to p.name, "baseUrl" to p.baseUrl,
                        "apiKey" to p.apiKey, "mode" to p.mode,
                        "defaultModel" to p.defaultModel, "headers" to p.headers
                    )
                }
            )
            Files.writeString(file, Gson().toJson(json))
            try { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) {}
        } catch (_: Exception) { }
    }
}
