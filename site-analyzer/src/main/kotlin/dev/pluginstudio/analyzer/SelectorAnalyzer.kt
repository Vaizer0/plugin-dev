package dev.pluginstudio.analyzer

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/** A single selector candidate with preview */
data class SelectorCandidate(
    val selector: String,
    val purpose: String,       // "catalog_container", "book_title", "book_cover", "book_description", "chapter_list", "chapter_content", "pagination", "search_form"
    val sampleText: String,
    val matchCount: Int,
    val confidence: String     // "high", "medium", "low"
)

data class SelectorResult(
    val candidates: Map<String, List<SelectorCandidate>>  // purpose → candidates
)

class SelectorAnalyzer {

    fun analyze(html: String, pageUrl: String, domReport: DomAnalysisReport? = null): SelectorResult {
        val doc = Jsoup.parse(html, pageUrl)
        val cms = domReport?.cms
        val containers = domReport?.containers ?: emptyList()

        val result = mutableMapOf<String, MutableList<SelectorCandidate>>()

        // Step 1: CMS-specific patterns
        cms?.let { addCmsPatterns(doc, it, result) }

        // Step 2: DomAnalyzer structural candidates
        addDomStructuralCandidates(doc, containers, result)

        // Step 3: Heuristic / fallback candidates (always added)
        addHeuristicCandidates(doc, result)

        // Step 4: Pagination from domReport
        domReport?.pagination?.let { addPaginationCandidate(doc, it, result) }

        return SelectorResult(result.mapValues { it.value.distinctBy { c -> c.selector }.take(3) })
    }

    // ── Step 1: CMS-specific ──

    private val cmsPatterns = mapOf(
        "WordPress" to listOf(
            Pattern("book_title", listOf("h3.title", "h1", "h1.entry-title", ".post-title"), "high"),
            Pattern("book_cover", listOf("meta[property='og:image']", ".summary_image img", ".cover img"), "high"),
            Pattern("book_description", listOf(".desc-text", ".entry-content p", ".summary__content", "#desc-tab"), "medium"),
            Pattern("chapter_list", listOf("a[href]", "#chapter-list a", ".wp-manga-chapter a", ".chapter-item a"), "medium"),
            Pattern("chapter_content", listOf("#chr-content", "#chapter-content", ".reading-content", ".entry-content"), "high"),
            Pattern("catalog_container", listOf(".page-item-detail", ".c-tabs-item__content", ".row .item"), "medium")
        ),
        "Madara" to listOf(
            Pattern("catalog_container", listOf(".c-tabs-item__content", ".page-listing-item .row", ".c-blog-post"), "high"),
            Pattern("book_title", listOf(".post-title h3", ".post-title", "h1"), "high"),
            Pattern("book_cover", listOf(".summary_image img", "meta[property='og:image']"), "high"),
            Pattern("book_description", listOf(".summary__content", ".manga-excerpt"), "medium"),
            Pattern("chapter_list", listOf(".wp-manga-chapter a", "a[href]", ".listing-chapters a"), "medium"),
            Pattern("chapter_content", listOf(".reading-content", ".text-left"), "high")
        ),
        "NovelBin" to listOf(
            Pattern("catalog_container", listOf(".col-novel-main .row", ".col-xs-7"), "high"),
            Pattern("book_title", listOf("h3.title", "h1"), "high"),
            Pattern("book_cover", listOf("meta[property='og:image']", "img[data-src]"), "high"),
            Pattern("book_description", listOf("div.desc-text", "#description"), "medium"),
            Pattern("chapter_list", listOf("a[href]", "template[data-chapter-item-template] a"), "high"),
            Pattern("chapter_content", listOf("#chr-content", "#chapter-content"), "high"),
            Pattern("pagination", listOf("ul.pagination a", ".pagination a"), "medium")
        ),
        "ScribbleHub" to listOf(
            Pattern("catalog_container", listOf(".search_main_box", ".fic_row"), "high"),
            Pattern("book_title", listOf(".fic_title", "div.fic_title"), "high"),
            Pattern("book_cover", listOf(".fic_image img", ".novel-cover img"), "high"),
            Pattern("book_description", listOf(".wi_fic_desc", ".fic_desc"), "medium"),
            Pattern("chapter_list", listOf(".toc_w a[href]", ".chapter-row a"), "high"),
            Pattern("chapter_content", listOf("#chp_raw", ".chapter-content"), "high")
        ),
        "RoyalRoad" to listOf(
            Pattern("catalog_container", listOf(".fiction-list-item", ".search-container"), "high"),
            Pattern("book_title", listOf(".fiction-title", "h1"), "high"),
            Pattern("book_cover", listOf(".fic-header img", "img[src]"), "medium"),
            Pattern("book_description", listOf(".description", ".fic-description"), "medium"),
            Pattern("chapter_list", listOf(".chapter-list a", "a[href]"), "medium"),
            Pattern("chapter_content", listOf(".chapter-content", "#chp_raw"), "high")
        ),
        "Next.js" to listOf(
            Pattern("catalog_container", listOf("a[href*='/novel/']", "a[href*='/manga/']"), "low"),
            Pattern("book_title", listOf("h1", "meta[property='og:title']"), "low"),
            Pattern("chapter_content", listOf("main", ".content", "article"), "low")
        ),
        "Nuxt.js" to listOf(
            Pattern("catalog_container", listOf("a[href*='/novel/']", "a[href*='/manga/']"), "low"),
            Pattern("book_title", listOf("h1", "meta[property='og:title']"), "low"),
            Pattern("chapter_content", listOf("main", ".content", "article"), "low")
        ),
        "Chinese Novel Site" to listOf(
            Pattern("catalog_container", listOf(".pic_txt_list", "div#bookList", ".list-item"), "high"),
            Pattern("book_title", listOf("span.name", "h1"), "high"),
            Pattern("book_cover", listOf(".box .pic img", ".pic img"), "high"),
            Pattern("book_description", listOf(".box .description", ".intro", "#description"), "medium"),
            Pattern("chapter_list", listOf("ul.list li a", "a[href]", "#list a"), "high"),
            Pattern("chapter_content", listOf("#content", "#article-content", ".entry-content"), "high")
        )
    )

    private data class Pattern(val purpose: String, val selectors: List<String>, val confidence: String)

    private fun addCmsPatterns(doc: Document, cms: CmsResult, result: MutableMap<String, MutableList<SelectorCandidate>>) {
        // Check CMS name and also clusterId
        val key = cms.clusterId ?: cms.name
        val patterns = cmsPatterns.entries.firstOrNull { (name, _) ->
            key.contains(name, ignoreCase = true) || name.contains(key, ignoreCase = true)
        }?.value ?: return

        for (pat in patterns) {
            for (sel in pat.selectors) {
                try {
                    val els = doc.select(sel)
                    if (els.isEmpty()) continue
                    val sample = els.first()?.text()?.take(80) ?: els.first()?.attr("src")?.take(80) ?: ""
                    result.getOrPut(pat.purpose) { mutableListOf() }.add(
                        SelectorCandidate(sel, pat.purpose, sample, els.size, pat.confidence)
                    )
                } catch (_: Exception) {}
            }
        }
    }

    // ── Step 2: DomAnalyzer structural candidates ──

    private fun addDomStructuralCandidates(doc: Document, containers: List<RepeatingContainer>, result: MutableMap<String, MutableList<SelectorCandidate>>) {
        // Catalog containers
        val catalogContainers = containers.filter { it.purpose in setOf("catalog_item", "search_result") }
        for (c in catalogContainers.take(3)) {
            result.getOrPut("catalog_container") { mutableListOf() }.add(
                SelectorCandidate(c.selector, "catalog_container", c.sampleHtml.take(80), c.count, "medium")
            )
            // Extract individual field selectors from the first container as book selectors
            for (f in c.possibleFields) {
                val purpose = when (f.role) {
                    "title" -> "book_title"
                    "cover" -> "book_cover"
                    "description" -> "book_description"
                    "chapter_link" -> "chapter_list"
                    else -> continue
                }
                val sample = if (purpose == "book_cover") f.sampleValue else f.sampleValue
                result.getOrPut(purpose) { mutableListOf() }.add(
                    SelectorCandidate(f.selector, purpose, sample, c.count, "medium")
                )
            }
        }
    }

    // ── Step 3: Heuristic/fallback ──

    private fun addHeuristicCandidates(doc: Document, result: MutableMap<String, MutableList<SelectorCandidate>>) {
        val checks = listOf(
            Heuristic("book_title") { doc.select("h1").takeIf { it.isNotEmpty() }?.let { listOf("h1" to it) } },
            Heuristic("book_title") { doc.select("h2").takeIf { it.isNotEmpty() }?.let { listOf("h2" to it) } },
            Heuristic("book_title") { doc.select("h3.title, h1.entry-title, .post-title, .novel-title").takeIf { it.isNotEmpty() }?.let { listOf(buildSimpleSelector(it.first()) to it) } },
            Heuristic("book_cover") { doc.select("meta[property='og:image']").takeIf { it.isNotEmpty() }?.let { listOf("meta[property='og:image']" to it) } },
            Heuristic("book_cover") { doc.select("img.cover, .cover img, .novel-cover img, .book img[src]").takeIf { it.isNotEmpty() }?.let { listOf(buildSimpleSelector(it.first()) to it) } },
            Heuristic("book_cover") { doc.select("img[src]").takeIf { it.size in 1..5 }?.let { listOf(buildSimpleSelector(it.first()) to it) } },
            Heuristic("book_description") { doc.select("#description, .description, .desc-text, .summary").takeIf { it.isNotEmpty() }?.let { listOf(buildSimpleSelector(it.first()) to it) } },
            Heuristic("chapter_content") { doc.select("#chr-content, #chapter-content, .chapter-content, .entry-content, #chp_raw, #content, .reading-content, #article-content").takeIf { it.isNotEmpty() }?.map { buildSimpleSelector(it) to doc.select(buildSimpleSelector(it)) } },
            Heuristic("catalog_container") { doc.select(".col-novel-main .row, .search_main_box, .fiction-list-item, .novel-list > .novel-item, .pic_txt_list, div.block-home > div.one, .page-item-detail, .c-tabs-item__content").takeIf { it.isNotEmpty() }?.map { buildSimpleSelector(it) to doc.select(buildSimpleSelector(it)) } },
            Heuristic("chapter_list") { doc.select("a[href*='/chapter-'], a[href*='/ch-'], a[href*='/capitulo-'], .chapter-list a, .toc_w a[href], ul.list li a, #morelist a").takeIf { it.isNotEmpty() }?.map { buildSimpleSelector(it) to doc.select(buildSimpleSelector(it)) } },
            Heuristic("chapter_list") { doc.select("a[href]").takeIf { s -> s.size in 5..50 && s.any { it.text().contains(Regex("ch|chapter|гл|глава", RegexOption.IGNORE_CASE)) } }?.let { listOf("a[href]" to it) } },
            Heuristic("pagination") { doc.select("a[rel=next], .next a, a.next, .pagination a, ul.pagination a, .page-numbers a, .page-nav a").takeIf { it.isNotEmpty() }?.map { buildSimpleSelector(it) to doc.select(buildSimpleSelector(it)) } },
        )

        for (h in checks) {
            val matches = h.block() ?: continue
            for ((sel, els) in matches) {
                if (sel.isBlank()) continue
                val exists = result[h.purpose]?.any { it.selector == sel } == true
                if (exists) continue
                val sample = els.first()?.text()?.take(80) ?: els.first()?.attr("src")?.take(80) ?: els.first()?.outerHtml()?.take(80) ?: ""
                result.getOrPut(h.purpose) { mutableListOf() }.add(
                    SelectorCandidate(sel, h.purpose, sample, els.size, "low")
                )
            }
        }
    }

    // ── Step 4: Pagination from DomAnalyzer ──

    private fun addPaginationCandidate(doc: Document, pagination: PaginationInfo, result: MutableMap<String, MutableList<SelectorCandidate>>) {
        val exists = result["pagination"]?.any { it.selector == pagination.selector } == true
        if (!exists) {
            result.getOrPut("pagination") { mutableListOf() }.add(
                SelectorCandidate(pagination.selector, "pagination", pagination.sampleUrl.take(80), 1, "high")
            )
        }
    }

    private fun buildSimpleSelector(el: Element): String {
        val tag = el.tagName()
        val id = el.id()
        if (id.isNotBlank()) return "#$id"
        val classes = el.classNames()
        if (classes.isNotEmpty()) {
            val ordered = classes.sorted().joinToString(".") { it }
            return "$tag.$ordered"
        }
        val parent = el.parent()
        if (parent != null) {
            val idx = parent.children().indexOf(el) + 1
            return "$tag:nth-child($idx)"
        }
        return tag
    }

    private data class Heuristic(val purpose: String, val block: () -> List<Pair<String, Elements>>?)
}
