package dev.pluginstudio.engine

import dev.pluginstudio.engine.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.jsoup.nodes.Document

fun createLuaSourceAdapter(
    luaScript: LuaValue,
    luaEngine: LuaEngine,
    iconUrlFromYaml: String? = null,
    fileName: String?,
    sourceCode: String? = null
): LuaSourceAdapter {
    val selectors = sourceCode?.let { SelectorExtractor.extract(it) } ?: emptyMap()
    val hasSettings = !luaScript.get("getSettingsSchema").isnil()
    val hasFilters = !luaScript.get("getFilterList").isnil()
    val schema = if (hasSettings) parseLuaSettingsSchema(luaScript) else null
    return when {
        schema != null && hasFilters ->
            LuaSourceAdapterFull(luaScript, luaEngine, iconUrlFromYaml, fileName, schema, selectors)
        schema != null ->
            LuaSourceAdapterConfigurable(luaScript, luaEngine, iconUrlFromYaml, fileName, schema, selectors)
        hasFilters ->
            LuaSourceAdapterFilterable(luaScript, luaEngine, iconUrlFromYaml, fileName, selectors)
        else ->
            LuaSourceAdapter(luaScript, luaEngine, iconUrlFromYaml, fileName, selectors)
    }
}

open class LuaSourceAdapter(
    protected val luaScript: LuaValue,
    protected val luaEngine: LuaEngine,
    protected val iconUrlFromYaml: String? = null,
    protected val fileName: String?,
    val selectors: Map<String, List<String>> = emptyMap()
) : SourceInterface.Catalog {

    private val metadata: SourceMetadata = extractMetadata()

    override val id: String = metadata.id
    override val name: String = metadata.name.ifEmpty { "Unknown" }
    override val baseUrl: String = metadata.url.ifEmpty {
        try { luaScript.get("baseUrl").optjstring("") } catch (_: Exception) { "" }
    }
    override val catalogUrl: String = baseUrl
    override val charset: String = metadata.charset ?: "UTF-8"

    override val language: LanguageCode? = when (metadata.language.lowercase().trim()) {
        "mtl", "multi" -> LanguageCode.MTL
        else -> LanguageCode.fromIso639_1(metadata.language)
    }

    override val iconUrl: String? = iconUrlFromYaml
        ?: metadata.icon.takeIf { it.isNotEmpty() }?.let { icon ->
            if (icon.startsWith("http")) icon
            else "${baseUrl.trimEnd('/')}/$icon"
        }

    fun getMetadata(): SourceMetadata = metadata

    fun hasFunction(name: String): Boolean = !luaScript.get(name).isnil()

    private fun extractMetadata(): SourceMetadata {
        fun s(key: String, def: String = "") = try {
            luaScript.get(key).optjstring(def)
        } catch (_: Exception) { def }
        return SourceMetadata(
            id = s("id", fileName?.let { "lua_$it" } ?: "lua_unknown"),
            name = s("name", "Unknown Source"),
            version = s("version", "1.0.0"),
            description = s("description"),
            url = s("baseUrl"),
            icon = s("icon"),
            language = s("language", "en"),
            charset = s("charset").takeIf { it.isNotBlank() }
        )
    }

    // ── SourceInterface.Catalog ──

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getCatalogList").call(LuaValue.valueOf(index))
                convertLuaResultToPagedList(result)
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> =
        withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getCatalogSearch").call(
                    LuaValue.valueOf(index), LuaValue.valueOf(input)
                )
                convertLuaResultToPagedList(result)
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getBookTitle(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            try {
                Response.Success(
                    luaScript.get("getBookTitle").call(LuaValue.valueOf(bookUrl)).optjstring(null)
                )
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown error", e)
            }
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            try {
                Response.Success(
                    luaScript.get("getBookCoverImageUrl").call(LuaValue.valueOf(bookUrl)).optjstring(null)
                )
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown error", e)
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            try {
                Response.Success(
                    luaScript.get("getBookDescription").call(LuaValue.valueOf(bookUrl)).optjstring(null)
                )
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown error", e)
            }
        }

    override suspend fun getBookGenres(bookUrl: String): Response<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val fn = luaScript.get("getBookGenres")
                if (fn.isnil()) return@withContext Response.Success(emptyList())
                val result = fn.call(LuaValue.valueOf(bookUrl))
                if (!result.istable()) return@withContext Response.Success(emptyList())
                val table = result.checktable()
                val genres = mutableListOf<String>()
                for (i in 1..table.length()) {
                    val v = table.get(LuaValue.valueOf(i)).optjstring(null)
                    if (!v.isNullOrBlank()) genres.add(v)
                }
                Response.Success(genres)
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown error", e)
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getChapterList").call(LuaValue.valueOf(bookUrl))
                val chapters = mutableListOf<ChapterResult>()
                if (result.istable()) {
                    val table = result.checktable()
                    for (i in 1..table.length()) {
                        val ch = table.get(LuaValue.valueOf(i))
                        if (ch.istable()) chapters.add(convertLuaTableToChapterResult(ch.checktable()))
                    }
                }
                Response.Success(chapters)
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun parsePage(
        bookUrl: String, page: Int
    ): Response<SourceInterface.Catalog.PagedChapterResult>? =
        withContext(Dispatchers.IO) {
            val fn = luaScript.get("parsePage")
            if (fn.isnil()) return@withContext null
            try {
                val result = fn.call(LuaValue.valueOf(bookUrl), LuaValue.valueOf(page))
                if (!result.istable()) return@withContext Response.Error("parsePage returned non-table", Exception())
                val table = result.checktable()
                val chaptersTable = table.get("chapters").opttable(null)
                val chapters = mutableListOf<ChapterResult>()
                if (chaptersTable != null) {
                    for (i in 1..chaptersTable.length()) {
                        val ch = chaptersTable.get(LuaValue.valueOf(i))
                        if (ch.istable()) chapters.add(convertLuaTableToChapterResult(ch.checktable()))
                    }
                }
                val totalPages = table.get("totalPages").optint(1)
                Response.Success(SourceInterface.Catalog.PagedChapterResult(chapters, totalPages))
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getChapterText(doc: Document): String? {
        val html = doc.outerHtml()
        val url = doc.location()
        return luaScript.get("getChapterText").call(
            LuaValue.valueOf(html), LuaValue.valueOf(url)
        ).optjstring(null)
    }

    suspend fun getChapterTextRaw(html: String, url: String): String? {
        return luaScript.get("getChapterText").call(
            LuaValue.valueOf(html), LuaValue.valueOf(url)
        ).optjstring(null)
    }

    override suspend fun getChapterListHash(bookUrl: String): Response<String?> =
        try {
            val fn = luaScript.get("getChapterListHash")
            if (fn.isnil()) Response.Success(null)
            else Response.Success(fn.call(LuaValue.valueOf(bookUrl)).optjstring(null))
        } catch (e: Exception) {
            Response.Error(e.message ?: "Unknown error", e)
        }

    protected fun convertLuaResultToPagedList(luaResult: LuaValue): Response<PagedList<BookResult>> {
        if (!luaResult.istable()) return Response.Success(PagedList(listOf(), 0, true))
        return try {
            val table = luaResult.checktable()
            val items = mutableListOf<BookResult>()
            val itemsTable = table.get("items").opttable(null)
            if (itemsTable != null) {
                for (i in 1..itemsTable.length()) {
                    val item = itemsTable.get(LuaValue.valueOf(i))
                    if (item.istable()) items.add(convertLuaTableToBookResult(item.checktable()))
                }
            }
            val hasNext = table.get("hasNext").optboolean(false)
            Response.Success(PagedList(items, 0, !hasNext))
        } catch (e: Exception) {
            Response.Error(e.message ?: "Conversion error", e)
        }
    }

    private fun convertLuaTableToBookResult(table: LuaTable) = BookResult(
        title = table.get("title").optjstring(""),
        url = table.get("url").optjstring(""),
        coverImageUrl = table.get("cover").optjstring("")
    )

    private fun convertLuaTableToChapterResult(table: LuaTable) = ChapterResult(
        title = table.get("title").optjstring(""),
        url = table.get("url").optjstring(""),
        volume = table.get("volume").optjstring(null)
    )
}

open class LuaSourceAdapterConfigurable(
    luaScript: LuaValue,
    luaEngine: LuaEngine,
    iconUrlFromYaml: String? = null,
    fileName: String?,
    private val schema: List<LuaSetting>,
    selectors: Map<String, List<String>> = emptyMap()
) : LuaSourceAdapter(luaScript, luaEngine, iconUrlFromYaml, fileName, selectors) {
    fun getSettingsSchema(): List<LuaSetting> = schema
}

class LuaSourceAdapterFilterable(
    luaScript: LuaValue,
    luaEngine: LuaEngine,
    iconUrlFromYaml: String? = null,
    fileName: String?,
    selectors: Map<String, List<String>> = emptyMap()
) : LuaSourceAdapter(luaScript, luaEngine, iconUrlFromYaml, fileName, selectors),
    SourceInterface.FilterableCatalog {

    override suspend fun getFilterList(): Response<List<LuaFilter>> =
        withContext(Dispatchers.IO) {
            try {
                val fn = luaScript.get("getFilterList")
                if (fn.isnil()) return@withContext Response.Success(emptyList())
                val result = fn.call()
                Response.Success(parseLuaFilterList(result))
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getCatalogFiltered(
        index: Int, filters: ActiveFilters
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.IO) {
        try {
            val luaFilters = filters.toLuaTable()
            val result = luaScript.get("getCatalogFiltered").call(
                LuaValue.valueOf(index), luaFilters
            )
            convertLuaResultToPagedList(result)
        } catch (e: Exception) {
            Response.Error(e.message ?: "Unknown Lua error", e)
        }
    }
}

class LuaSourceAdapterFull(
    luaScript: LuaValue,
    luaEngine: LuaEngine,
    iconUrlFromYaml: String? = null,
    fileName: String?,
    schema: List<LuaSetting>,
    selectors: Map<String, List<String>> = emptyMap()
) : LuaSourceAdapterConfigurable(luaScript, luaEngine, iconUrlFromYaml, fileName, schema, selectors),
    SourceInterface.FilterableCatalog {

    override suspend fun getFilterList(): Response<List<LuaFilter>> =
        withContext(Dispatchers.IO) {
            try {
                val fn = luaScript.get("getFilterList")
                if (fn.isnil()) return@withContext Response.Success(emptyList())
                val result = fn.call()
                Response.Success(parseLuaFilterList(result))
            } catch (e: Exception) {
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getCatalogFiltered(
        index: Int, filters: ActiveFilters
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.IO) {
        try {
            val luaFilters = filters.toLuaTable()
            val result = luaScript.get("getCatalogFiltered").call(
                LuaValue.valueOf(index), luaFilters
            )
            convertLuaResultToPagedList(result)
        } catch (e: Exception) {
            Response.Error(e.message ?: "Unknown Lua error", e)
        }
    }
}

private fun ActiveFilters.toLuaTable(): LuaValue {
    val table = LuaTable()
    for ((key, values) in filters) {
        if (values.size == 1) {
            table.set(key, LuaValue.valueOf(values[0]))
        } else {
            val arr = LuaTable()
            values.forEachIndexed { i, v -> arr.set(i + 1, LuaValue.valueOf(v)) }
            table.set(key, arr)
        }
    }
    return table
}

private fun parseLuaFilterList(luaResult: LuaValue): List<LuaFilter> {
    if (!luaResult.istable()) return emptyList()
    val table = luaResult.checktable()
    val filters = mutableListOf<LuaFilter>()
    for (i in 1..table.length()) {
        val entry = table.get(LuaValue.valueOf(i))
        if (!entry.istable()) continue
        val t = entry.checktable()
        filters.add(
            LuaFilter(
                type = t.get("type").optjstring("select"),
                key = t.get("key").optjstring(""),
                label = t.get("label").optjstring(""),
                defaultValue = t.get("defaultValue").optjstring(null),
                multiselect = t.get("multiselect").optboolean(true),
                options = parseFilterOptions(t.get("options"))
            )
        )
    }
    return filters
}

private fun parseFilterOptions(luaValue: LuaValue): List<FilterOption> {
    if (!luaValue.istable()) return emptyList()
    val table = luaValue.checktable()
    val options = mutableListOf<FilterOption>()
    for (i in 1..table.length()) {
        val entry = table.get(LuaValue.valueOf(i))
        if (!entry.istable()) continue
        val t = entry.checktable()
        options.add(
            FilterOption(
                value = t.get("value").optjstring(""),
                label = t.get("label").optjstring("")
            )
        )
    }
    return options
}

private fun parseSettingOptions(luaValue: LuaValue): List<SettingOption> {
    if (!luaValue.istable()) return emptyList()
    val table = luaValue.checktable()
    val options = mutableListOf<SettingOption>()
    for (i in 1..table.length()) {
        val entry = table.get(LuaValue.valueOf(i))
        if (!entry.istable()) continue
        val t = entry.checktable()
        options.add(
            SettingOption(
                value = t.get("value").optjstring(""),
                label = t.get("label").optjstring("")
            )
        )
    }
    return options
}

private fun parseLuaSettingsSchema(luaScript: LuaValue): List<LuaSetting> {
    val fn = luaScript.get("getSettingsSchema")
    if (fn.isnil()) return emptyList()
    val result = fn.call()
    if (!result.istable()) return emptyList()
    val table = result.checktable()
    val settings = mutableListOf<LuaSetting>()
    for (i in 1..table.length()) {
        val entry = table.get(LuaValue.valueOf(i))
        if (!entry.istable()) continue
        val t = entry.checktable()
        settings.add(
            LuaSetting(
                key = t.get("key").optjstring(""),
                label = t.get("label").optjstring(""),
                type = t.get("type").optjstring("text"),
                defaultValue = t.get("defaultValue").optjstring(""),
                options = parseSettingOptions(t.get("options"))
            )
        )
    }
    return settings
}
