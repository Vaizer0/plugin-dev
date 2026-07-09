package dev.pluginstudio.engine

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

data class LanguageIndex(
    val language: String = "",
    val name: String = "",
    val sources: List<SourceEntry> = emptyList()
)

data class SourceEntry(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val url: String = "",
    val icon: String = "",
    val language: String = ""
)

data class PluginLoadReport(
    val loaded: List<LuaSourceAdapter>,
    val failed: List<PluginLoadError>
)

data class PluginLoadError(
    val file: String,
    val message: String
)

class PluginLoader(
    private val luaEngine: LuaEngine,
    private val sourcesDir: Path
) {
    fun loadAllPlugins(): PluginLoadReport {
        val loaded = mutableListOf<LuaSourceAdapter>()
        val failed = mutableListOf<PluginLoadError>()

        val rootIndex = loadYamlMap(sourcesDir.resolve("index.yaml"))
        val languages = rootIndex?.get("languages") as? Map<*, *>

        if (languages != null) {
            for (langCode in languages.keys) {
                val (l, f) = loadLanguagePlugins(langCode.toString())
                loaded.addAll(l); failed.addAll(f)
            }
        } else {
            val (l, f) = loadDirectoryPlugins(sourcesDir)
            loaded.addAll(l); failed.addAll(f)
        }

        return PluginLoadReport(loaded, failed)
    }

    private fun loadLanguagePlugins(langCode: String): PluginLoadReport {
        val langDir = sourcesDir.resolve(langCode)
        if (!Files.isDirectory(langDir)) return PluginLoadReport(emptyList(), emptyList())

        val yamlSources = parseLanguageIndex(langDir.resolve("index.yaml"))
            ?.sources?.associateBy { it.id } ?: emptyMap()

        return loadLuaFilesFromDir(langDir, yamlSources)
    }

    private fun loadDirectoryPlugins(dir: Path): PluginLoadReport {
        if (!Files.isDirectory(dir)) return PluginLoadReport(emptyList(), emptyList())
        return loadLuaFilesFromDir(dir, emptyMap())
    }

    private fun loadLuaFilesFromDir(
        dir: Path,
        yamlSources: Map<String, SourceEntry>
    ): PluginLoadReport {
        val loaded = mutableListOf<LuaSourceAdapter>()
        val failed = mutableListOf<PluginLoadError>()

        val luaFiles = try {
            Files.list(dir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".lua") }
                    .filter { path ->
                        path.parent?.fileName?.toString() != ".history"
                    }
                    .collect(Collectors.toList())
            }
        } catch (e: Exception) {
            return PluginLoadReport(emptyList(), listOf(PluginLoadError(dir.toString(), e.message ?: "IO error")))
        }

        for (file in luaFiles) {
            try {
                val rawCode = Files.readString(file)
                val luaCode = rawCode.trimStart('\uFEFF', '\u00a0', ' ', '\t', '\r', '\n')
                val luaScript = luaEngine.loadScriptBlocking(luaCode)
                val fileName = file.fileName.toString()
                val id = luaScript.get("id").optjstring(fileName.removeSuffix(".lua"))
                val yamlEntry = yamlSources[id]
                val iconUrl = yamlEntry?.icon?.takeIf { it.isNotBlank() }
                loaded.add(createLuaSourceAdapter(luaScript, luaEngine, iconUrl, fileName, rawCode))
            } catch (e: Exception) {
                failed.add(PluginLoadError(file.fileName.toString(), e.message ?: e.toString()))
            }
        }

        return PluginLoadReport(loaded, failed)
    }

    private fun loadYamlMap(path: Path): Map<String, Any>? {
        return try {
            if (!Files.exists(path)) return null
            val content = Files.readString(path)
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Map<String, Any>>(content)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLanguageIndex(path: Path): LanguageIndex? {
        val map = loadYamlMap(path) ?: return null
        return LanguageIndex(
            language = (map["language"] as? String) ?: "",
            name = (map["name"] as? String) ?: "",
            sources = parseSourceList(map["sources"])
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSourceList(raw: Any?): List<SourceEntry> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { entry ->
            if (entry !is Map<*, *>) return@mapNotNull null
            SourceEntry(
                id = (entry["id"] as? String) ?: "",
                name = (entry["name"] as? String) ?: "",
                version = (entry["version"] as? String) ?: "",
                url = (entry["url"] as? String) ?: "",
                icon = (entry["icon"] as? String) ?: "",
                language = (entry["language"] as? String) ?: ""
            )
        }
    }
}
