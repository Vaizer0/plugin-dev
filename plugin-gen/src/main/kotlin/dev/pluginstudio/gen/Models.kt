package dev.pluginstudio.gen

/** One crawled (or browser-captured) page plus its analysis results. */
data class PageSnapshot(
    val url: String,
    val html: String,
    val kind: String = "home",          // home | search | book | chapter | other
    val viaBrowser: Boolean = false,
    val dom: dev.pluginstudio.analyzer.DomAnalysisReport? = null,
    val js: dev.pluginstudio.analyzer.SiteAnalysisReport? = null
)

/** A network request observed by the prober or captured from the user's browser. */
data class NetRecord(
    val method: String,
    val url: String,
    val status: Int = 0,
    val contentType: String = "",
    val bodySample: String = ""
)

/** Result of probing a single candidate API endpoint. */
data class ApiProbe(
    val url: String,
    val method: String,
    val status: Int,
    val contentType: String,
    val isJson: Boolean,
    val listField: String?,             // JSON field holding the array, if any
    val titleField: String?,
    val urlOrSlugField: String?,
    val coverField: String?,
    val sampleCount: Int,
    val source: String                  // "js-scan" | "browser" | "form"
)

/** Strategies chosen for each plugin function. */
data class CatalogStrategy(
    val containerSel: String,
    val titleSel: String,
    val linkSel: String,
    val coverSel: String,
    val anchorOnly: Boolean = false,
    val pageParamStyle: String = "query_page_1",   // query_page_1 | path_page_1
    val basePath: String = ""                      // e.g. "" (root) or "/demo/catalog"
)

sealed class SearchStrategy {
    data class Api(
        val method: String,
        val urlTemplate: String,        // contains {query}
        val listField: String?,
        val titleField: String,
        val urlField: String?,
        val slugField: String?,
        val coverField: String?,
        val bookUrlPrefix: String?
    ) : SearchStrategy()

    data class Dom(
        val formAction: String,
        val inputName: String,
        val containerSel: String? = null,
        val titleSel: String? = null,
        val linkOnly: Boolean = true,
        val coverSel: String? = null
    ) : SearchStrategy()
}

data class BookStrategy(
    val titleSel: String?,
    val coverSel: String?,
    val descSel: String?,
    val genresSel: String?
)

data class ChaptersStrategy(
    val anchorsSel: String,             // selector matching <a> elements of chapters
    val altSelectors: List<String> = emptyList(),  // tried in order if the primary yields nothing
    val useParsePage: Boolean = false,
    val parsePageUrlTemplate: String? = null   // contains {page} starting at 0/1 decided in lua
)

data class ChapterTextStrategy(
    val contentSel: String?,
    val removeSels: List<String>
)

data class PluginBlueprint(
    val id: String,
    val name: String,
    val baseUrl: String,
    val language: String,
    val icon: String,
    val variantName: String,
    val catalog: CatalogStrategy?,
    val searchApi: SearchStrategy.Api?,
    val searchDom: SearchStrategy.Dom?,
    val book: BookStrategy?,
    val chapters: ChaptersStrategy?,
    val chapterText: ChapterTextStrategy?,
    /** A verified sample novel-details URL discovered during analysis. */
    val bookProbeUrl: String? = null,
    val notes: List<String> = emptyList()
)

data class ValidationEntry(
    val function: String,
    val ok: Boolean,
    val detail: String,
    val durationMs: Long
)

data class GenerationResult(
    val success: Boolean,
    val siteUrl: String,
    val blueprint: PluginBlueprint?,
    val strategySummary: Map<String, String>,
    val validation: List<ValidationEntry>,
    val overallPass: Boolean,
    val luaCode: String,
    var stagedFile: String? = null,
    val variantsTried: Int = 0,
    val error: String? = null,
    val partialValidation: Boolean = false
)
