package dev.pluginstudio.engine

import dev.pluginstudio.engine.models.BookResult
import dev.pluginstudio.engine.models.ChapterResult
import dev.pluginstudio.engine.models.LanguageCode
import dev.pluginstudio.engine.models.PagedList
import dev.pluginstudio.engine.models.Response
import org.jsoup.nodes.Document

sealed interface SourceInterface {
    val id: String
    val name: String get() = ""
    val baseUrl: String
    val isLocalSource: Boolean get() = false
    val requiresLogin: Boolean get() = false
    val charset: String get() = "UTF-8"

    suspend fun transformChapterUrl(url: String): String = url
    suspend fun getChapterText(doc: Document): String? = null

    interface Catalog : SourceInterface {
        val catalogUrl: String
        val language: LanguageCode?
        val iconUrl: String? get() = null

        suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> = Response.Success(null)
        suspend fun getBookDescription(bookUrl: String): Response<String?> = Response.Success(null)
        suspend fun getBookTitle(bookUrl: String): Response<String?> = Response.Success(null)
        suspend fun getBookGenres(bookUrl: String): Response<List<String>> = Response.Success(emptyList())
        suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>>
        suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>>
        suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>>
        suspend fun getChapterListHash(bookUrl: String): Response<String?> = Response.Success(null)

        data class PagedChapterResult(
            val chapters: List<ChapterResult>,
            val totalPages: Int
        )

        suspend fun parsePage(bookUrl: String, page: Int): Response<PagedChapterResult>? = null
    }

    interface FilterableCatalog : Catalog {
        suspend fun getFilterList(): Response<List<LuaFilter>>
        suspend fun getCatalogFiltered(
            index: Int,
            filters: ActiveFilters
        ): Response<PagedList<BookResult>>
    }
}
