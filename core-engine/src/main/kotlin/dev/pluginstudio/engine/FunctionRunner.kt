package dev.pluginstudio.engine

import dev.pluginstudio.engine.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis

enum class ParamType { INT, STRING }

data class ParamDef(
    val name: String,
    val type: ParamType,
    val required: Boolean = true,
    val defaultValue: String? = null,
    val hint: String? = null
)

data class FunctionDef(
    val name: String,
    val params: List<ParamDef>,
    val returnType: String
)

data class FunctionResult(
    val functionName: String,
    val success: Boolean,
    val data: String?,
    val error: String?,
    val durationMs: Long
)

val ALL_FUNCTIONS = listOf(
    FunctionDef("getCatalogList", listOf(
        ParamDef("index", ParamType.INT, defaultValue = "0", hint = "Page number")
    ), "PagedList<BookResult>"),
    FunctionDef("getCatalogSearch", listOf(
        ParamDef("index", ParamType.INT, defaultValue = "0", hint = "Page number"),
        ParamDef("input", ParamType.STRING, hint = "Search query")
    ), "PagedList<BookResult>"),
    FunctionDef("getBookTitle", listOf(
        ParamDef("bookUrl", ParamType.STRING, hint = "Book page URL")
    ), "String?"),
    FunctionDef("getBookCoverImageUrl", listOf(
        ParamDef("bookUrl", ParamType.STRING, hint = "Book page URL")
    ), "String?"),
    FunctionDef("getBookDescription", listOf(
        ParamDef("bookUrl", ParamType.STRING, hint = "Book page URL")
    ), "String?"),
    FunctionDef("getBookGenres", listOf(
        ParamDef("bookUrl", ParamType.STRING, hint = "Book page URL")
    ), "List<String>"),
    FunctionDef("getChapterList", listOf(
        ParamDef("bookUrl", ParamType.STRING, hint = "Book page URL")
    ), "List<ChapterResult>"),
    FunctionDef("getChapterText", listOf(
        ParamDef("html", ParamType.STRING, hint = "Raw HTML"),
        ParamDef("url", ParamType.STRING, hint = "Chapter URL")
    ), "String?"),
    FunctionDef("parsePage", listOf(
        ParamDef("bookUrl", ParamType.STRING, hint = "Book page URL"),
        ParamDef("page", ParamType.INT, defaultValue = "1", hint = "Page number")
    ), "PagedChapterResult?"),
    FunctionDef("getChapterListHash", listOf(
        ParamDef("bookUrl", ParamType.STRING, hint = "Book page URL")
    ), "String?")
)

val FILTER_FUNCTIONS = listOf(
    FunctionDef("getFilterList", emptyList(), "List<LuaFilter>"),
    FunctionDef("getCatalogFiltered", listOf(
        ParamDef("index", ParamType.INT, defaultValue = "0", hint = "Page number"),
        ParamDef("filtersJson", ParamType.STRING, hint = "JSON string of filters")
    ), "PagedList<BookResult>")
)

class FunctionRunner(
    private val adapter: LuaSourceAdapter,
    private val onHttpLog: (HttpLogEntry) -> Unit = {}
) {
    fun availableFunctions(): List<FunctionDef> {
        val base = ALL_FUNCTIONS.filter { adapter.hasFunction(it.name) }
        val filter = if (adapter is SourceInterface.FilterableCatalog) {
            FILTER_FUNCTIONS.filter { adapter.hasFunction(it.name) }
        } else emptyList()
        return base + filter
    }

    suspend fun run(name: String, args: Map<String, String>): FunctionResult {
        val startMs = System.currentTimeMillis()
        return try {
            withTimeout(30_000) {
                val result = invokeFunction(name, args)
                FunctionResult(
                    functionName = name,
                    success = true,
                    data = result,
                    error = null,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
        } catch (e: Exception) {
            FunctionResult(
                functionName = name,
                success = false,
                data = null,
                error = e.message ?: e.toString(),
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private suspend fun invokeFunction(name: String, args: Map<String, String>): String {
        return withContext(Dispatchers.IO) {
            val result: Any? = when (name) {
                "getCatalogList" -> adapter.getCatalogList(
                    args["index"]?.toIntOrNull() ?: 0
                )
                "getCatalogSearch" -> adapter.getCatalogSearch(
                    args["index"]?.toIntOrNull() ?: 0,
                    args["input"] ?: ""
                )
                "getBookTitle" -> adapter.getBookTitle(
                    args["bookUrl"] ?: ""
                )
                "getBookCoverImageUrl" -> adapter.getBookCoverImageUrl(
                    args["bookUrl"] ?: ""
                )
                "getBookDescription" -> adapter.getBookDescription(
                    args["bookUrl"] ?: ""
                )
                "getBookGenres" -> adapter.getBookGenres(
                    args["bookUrl"] ?: ""
                )
                "getChapterList" -> adapter.getChapterList(
                    args["bookUrl"] ?: ""
                )
                "getChapterText" -> adapter.getChapterTextRaw(
                    args["html"] ?: "",
                    args["url"] ?: ""
                )
                "parsePage" -> adapter.parsePage(
                    args["bookUrl"] ?: "",
                    args["page"]?.toIntOrNull() ?: 1
                )
                "getChapterListHash" -> adapter.getChapterListHash(
                    args["bookUrl"] ?: ""
                )
                else -> "Unknown function: $name"
            }
            formatResult(result)
        }
    }

    private fun formatResult(result: Any?): String {
        return when (result) {
            null -> "null"
            is Response.Error -> "Error: ${result.message}"
            is Response.Success<*> -> formatData(result.data)
            else -> result.toString()
        }
    }

    private fun formatData(data: Any?): String {
        return when (data) {
            null -> "null"
            is String -> data
            is Number -> data.toString()
            is Boolean -> data.toString()
            is List<*> -> {
                if (data.isEmpty()) "[]"
                else buildString {
                    data.forEachIndexed { i, item ->
                        appendLine("[$i] ${formatData(item)}")
                    }
                }
            }
            is PagedList<*> -> {
                buildString {
                    appendLine("Page: items=${data.list.size}, isLastPage=${data.isLastPage}")
                    data.list.forEachIndexed { i, item ->
                        appendLine("[$i] ${formatData(item)}")
                    }
                }
            }
            is SourceInterface.Catalog.PagedChapterResult -> {
                buildString {
                    appendLine("Total pages: ${data.totalPages}")
                    data.chapters.forEachIndexed { i, it ->
                        appendLine("[$i] ${it.title} -> ${it.url}")
                    }
                }
            }
            is BookResult -> "BookResult(title=${data.title}, url=${data.url}, cover=${data.coverImageUrl})"
            is ChapterResult -> "ChapterResult(title=${data.title}, url=${data.url}, volume=${data.volume})"
            else -> data.toString()
        }
    }
}
