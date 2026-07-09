package dev.pluginstudio.analyzer

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

data class DomAnalysisReport(
    val pageUrl: String,
    val cms: CmsResult? = null,
    val containers: List<RepeatingContainer> = emptyList(),
    val forms: List<FormInfo> = emptyList(),
    val pagination: PaginationInfo? = null,
    val embeddedJson: List<EmbeddedJson> = emptyList()
)

data class CmsResult(
    val name: String,
    val themeVariant: String? = null,
    val confidence: Double,
    val evidence: List<String>,
    val clusterId: String? = null,
    val paginationType: String? = null,
    val catalogType: String? = null,
    val chapterListType: String? = null,
    val samplePlugins: List<String> = emptyList()
)

data class RepeatingContainer(
    val selector: String,
    val count: Int,
    val possibleFields: List<ContainerField>,
    val sampleHtml: String = "",
    val purpose: String = ""  // "catalog_item", "chapter_item", "search_result"
)

data class ContainerField(
    val role: String,        // "title", "url", "cover", "description", "chapter_link", "chapter_date"
    val tag: String,
    val selector: String,
    val sampleValue: String = ""
)

data class FormInfo(
    val selector: String,
    val method: String,
    val action: String,
    val inputs: List<String>
)

data class PaginationInfo(
    val selector: String,
    val type: String,  // "next_link", "page_numbers"
    val sampleUrl: String = ""
)

data class EmbeddedJson(
    val type: String,  // "json_ld", "next_data", "nuxt"
    val content: String = ""
)

class DomAnalyzer {

    fun analyze(pageUrl: String, html: String): DomAnalysisReport {
        val doc = Jsoup.parse(html, pageUrl)
        val cms = detectCms(doc)
        val containers = findRepeatingContainers(doc)
        val forms = findForms(doc)
        val pagination = findPagination(doc)
        val json = findEmbeddedJson(doc)
        return DomAnalysisReport(
            pageUrl = pageUrl, cms = cms, containers = containers,
            forms = forms, pagination = pagination, embeddedJson = json
        )
    }

    // ── CMS Detection ──

    private fun detectCms(doc: Document): CmsResult? {
        val checks = listOf(
            ::checkWordPress,
            ::checkMadara,
            ::checkNovelBin,
            ::checkScribbleHub,
            ::checkRoyalRoad,
            ::checkNextJs,
            ::checkChineseSite,
            ::checkCustomApi
        )
        val hardcoded = checks.mapNotNull { it(doc) }.maxByOrNull { it.confidence }
        // try probe-based enrichment if a cluster is known for the detected CMS
        return if (hardcoded != null) enrichWithCluster(hardcoded, doc) else null
    }

    private fun checkWordPress(doc: Document): CmsResult? {
        val evidence = mutableListOf<String>()
        if (doc.select("link[href*=wp-content]").isNotEmpty()) evidence.add("wp-content assets")
        if (doc.select("meta[name=generator][content*=WordPress]").isNotEmpty()) evidence.add("meta generator")
        if (doc.html().contains("wp-admin/admin-ajax.php")) evidence.add("admin-ajax.php reference")
        if (doc.select("link[href*=wp-includes]").isNotEmpty()) evidence.add("wp-includes assets")
        if (doc.select(".wp-manga-chapter").isNotEmpty()) evidence.add("Madara list-chapter class")
        if (evidence.isEmpty()) return null
        val confidence = when {
            "Madara" in evidence.joinToString() -> 0.95
            evidence.size >= 2 -> 0.9
            else -> 0.7
        }
        val isMadara = doc.select("link[href*=madara]").isNotEmpty() ||
            doc.select("link[href*=manga]").isNotEmpty() ||
            doc.select(".c-tabs-item__content").isNotEmpty()
        return CmsResult("WordPress", if (isMadara) "Madara" else null, confidence, evidence)
    }

    private fun checkMadara(doc: Document): CmsResult? {
        val ev = mutableListOf<String>()
        if (doc.select(".c-tabs-item__content").size > 2) ev.add(".c-tabs-item__content container")
        if (doc.select(".post-title").isNotEmpty()) ev.add(".post-title")
        if (doc.select(".reading-content").isNotEmpty()) ev.add(".reading-content")
        if (doc.select(".c-blog-post").isNotEmpty()) ev.add(".c-blog-post")
        if (doc.select(".page-listing-item").isNotEmpty()) ev.add(".page-listing-item")
        if (ev.isEmpty()) return null
        val confidence = if (ev.size >= 3) 0.95 else if (ev.size >= 2) 0.85 else 0.6
        return CmsResult("Madara", "WordPress+Madara", confidence, ev)
    }

    private fun checkNovelBin(doc: Document): CmsResult? {
        val ev = mutableListOf<String>()
        if (doc.select(".col-truyen-main .row").size > 2) ev.add(".col-truyen-main .row container")
        if (doc.select(".col-xs-7").isNotEmpty()) ev.add(".col-xs-7 item")
        if (doc.select(".list-chapter").isNotEmpty()) ev.add(".list-chapter")
        if (doc.select("#chapter-content").isNotEmpty()) ev.add("#chapter-content")
        if (doc.select("nav.navbar").first()?.id() == "navbar") ev.add("navbar#navbar")
        if (ev.isEmpty()) return null
        return CmsResult("NovelBin", null, if (ev.size >= 2) 0.9 else 0.6, ev)
    }

    private fun checkScribbleHub(doc: Document): CmsResult? {
        val ev = mutableListOf<String>()
        if (doc.select(".search_main_box").isNotEmpty()) ev.add(".search_main_box")
        if (doc.select(".fic_title").isNotEmpty()) ev.add(".fic_title")
        if (doc.select(".wi_fic_desc").isNotEmpty()) ev.add(".wi_fic_desc")
        if (doc.select(".fic_image").isNotEmpty()) ev.add(".fic_image")
        if (doc.select("#chp_raw").isNotEmpty()) ev.add("#chp_raw")
        if (ev.isEmpty()) return null
        return CmsResult("ScribbleHub", null, if (ev.size >= 3) 0.95 else 0.7, ev)
    }

    private fun checkRoyalRoad(doc: Document): CmsResult? {
        val ev = mutableListOf<String>()
        if (doc.select(".fiction-list-item").size > 2) ev.add(".fiction-list-item container")
        if (doc.select(".fiction-title").isNotEmpty()) ev.add(".fiction-title")
        if (doc.select(".chapter-content").isNotEmpty()) ev.add(".chapter-content")
        if (doc.select(".search-container").isNotEmpty()) ev.add(".search-container")
        if (doc.select(".fic-header").isNotEmpty()) ev.add(".fic-header")
        if (ev.isEmpty()) return null
        return CmsResult("RoyalRoad", null, if (ev.size >= 3) 0.95 else 0.7, ev)
    }

    private fun checkNextJs(doc: Document): CmsResult? {
        val nextData = doc.select("script:containsData(__NEXT_DATA__)")
        if (nextData.isNotEmpty()) {
            return CmsResult("Next.js", "SSR", 0.9, listOf("__NEXT_DATA__ found"))
        }
        val nuxt = doc.select("script:containsData(__NUXT__)")
        if (nuxt.isNotEmpty()) {
            return CmsResult("Nuxt.js", "SSR", 0.85, listOf("__NUXT__ found"))
        }
        return null
    }

    private fun checkChineseSite(doc: Document): CmsResult? {
        val ev = mutableListOf<String>()
        val html = doc.html()
        if (doc.select("meta[charset]").firstOrNull()?.attr("charset")?.uppercase() in listOf("GBK", "GB2312")) ev.add("meta charset GBK/GB2312")
        else if (doc.select("meta[charset=GBK], meta[charset=gb2312]").isNotEmpty()) ev.add("meta charset GBK/gb2312")
        else if (html.contains("charset=gbk", ignoreCase = true) || html.contains("charset=gb2312", ignoreCase = true)) ev.add("charset GBK/gb2312 in HTML")
        if (doc.select(".pic_txt_list").isNotEmpty()) ev.add(".pic_txt_list container")
        if (doc.select("div#bookList, div.list-item").size > 3) ev.add("bookList/list-item containers")
        if (html.contains("novel543", ignoreCase = true) || html.contains("shuba69", ignoreCase = true)) ev.add("known Chinese CMS host")
        if (ev.isEmpty()) return null
        return CmsResult("Chinese Novel Site", null, if (ev.size >= 2) 0.85 else 0.5, ev)
    }

    private fun checkCustomApi(doc: Document): CmsResult? {
        // No CMS markers found; check if it looks API-driven (spa, minimal html)
        val totalElements = doc.allElements.size
        val textContent = doc.text().length
        if (totalElements < 50 && textContent < 500) {
            return CmsResult("Unknown (minimal HTML)", "API-driven", 0.5,
                listOf("Very few elements ($totalElements) and text ($textContent chars) — possibly SPA/API"))
        }
        return null
    }

    private fun enrichWithCluster(cms: CmsResult, doc: Document): CmsResult {
        val html = doc.html()
        val matched = probeClusters.firstNotNullOfOrNull { (id, probes) ->
            val score = probes.sumOf { p ->
                val found = when (p.first) {
                    "html_contains" -> html.contains(p.second, ignoreCase = true)
                    "meta_charset" -> doc.select("meta[charset]").firstOrNull()?.attr("charset")?.equals(p.second, ignoreCase = true) ?: false
                    else -> false
                }
                if (found) p.third else 0.0
            }
            if (score >= probeThresholds.getOrDefault(id, 0.5)) id else null
        }
        val clusterMeta = clusterRegistry.getOrDefault(matched ?: "", emptyList()).firstOrNull()
        return if (clusterMeta != null) cms.copy(
            clusterId = clusterMeta.id,
            paginationType = clusterMeta.paginationType,
            catalogType = clusterMeta.catalogType,
            chapterListType = clusterMeta.chapterListType,
            samplePlugins = clusterMeta.samplePlugins
        ) else cms
    }

    companion object {
        private val probeClusters = mapOf(
            "novelbin_clone" to listOf(
                Triple("html_contains", ".col-truyen-main", 0.8),
                Triple("html_contains", "list-chapter", 0.6),
                Triple("html_contains", "#chapter-content", 0.6)
            ),
            "madara_theme" to listOf(
                Triple("html_contains", "wp-content/themes/madara", 0.5),
                Triple("html_contains", ".c-tabs-item__content", 0.7),
                Triple("html_contains", ".reading-content", 0.6),
                Triple("html_contains", ".post-title h3", 0.5),
                Triple("html_contains", ".page-item-detail", 0.6)
            ),
            "wordpress" to listOf(
                Triple("html_contains", "wp-content/themes", 0.5),
                Triple("html_contains", "wp-admin/admin-ajax.php", 0.8),
                Triple("html_contains", "wpApiSettings", 0.7)
            ),
            "scribblehub" to listOf(
                Triple("html_contains", ".search_main_box", 0.8),
                Triple("html_contains", ".fic_title", 0.6),
                Triple("html_contains", ".wi_fic_desc", 0.6),
                Triple("html_contains", "#chp_raw", 0.7)
            ),
            "custom_api" to listOf(
                Triple("html_contains", "__NEXT_DATA__", 0.5),
                Triple("html_contains", "__NUXT__", 0.5)
            ),
            "chinese_site" to listOf(
                Triple("meta_charset", "GBK", 0.8),
                Triple("meta_charset", "gb2312", 0.8),
                Triple("html_contains", ".pic_txt_list", 0.5)
            )
        )

        private val probeThresholds = mapOf(
            "novelbin_clone" to 0.5, "madara_theme" to 0.5, "wordpress" to 0.5,
            "scribblehub" to 0.5, "custom_api" to 0.4, "chinese_site" to 0.5
        )

        private val clusterRegistry = mapOf(
            "novelbin_clone" to listOf(ClusterMeta("novelbin_clone", "items_count", "html", "html", listOf("NovelBin", "NovelFull", "AllNovel", "FreeWebNovel", "ReadNovelFull", "NovelBuddy", "NovelArrow"))),
            "madara_theme" to listOf(ClusterMeta("madara_theme", "items_count", "html", "ajax", listOf("NovelFire", "NovelHall", "NovelNice", "WuxiaWorldSite"))),
            "wordpress" to listOf(ClusterMeta("wordpress", "ajax_pages", "html", "ajax", listOf("Jaomix", "IFreedom", "BookHamster"))),
            "scribblehub" to listOf(ClusterMeta("scribblehub", "next_link", "html", "ajax", listOf("ScribbleHub", "RoyalRoad"))),
            "custom_api" to listOf(ClusterMeta("custom_api", "api_meta", "api", "api", listOf("RanobeLib", "RanobeHub"))),
            "chinese_site" to listOf(ClusterMeta("chinese_site", "page_param", "html", "html", listOf("Quanben5", "Piaotia", "Novel543", "Shuba69", "Ttkan", "Twkan")))
        )
    }

    private data class ClusterMeta(
        val id: String,
        val paginationType: String,
        val catalogType: String,
        val chapterListType: String,
        val samplePlugins: List<String>
    )

    // ── Repeating Container Detection ──

    private fun findRepeatingContainers(doc: Document): List<RepeatingContainer> {
        val candidates = mutableMapOf<String, MutableList<Element>>()

        // Collect all direct children of common container parents
        val containers = doc.select("div, ul, ol, table, section, main")
        val directChildren = mutableMapOf<Element, MutableMap<String, MutableList<Element>>>()
        for (parent in containers) {
            val children = parent.children()
            if (children.size < 3) continue
            val bySelector = mutableMapOf<String, MutableList<Element>>()
            for (child in children) {
                val sel = candidateSelector(child)
                bySelector.getOrPut(sel) { mutableListOf() }.add(child)
            }
            for ((sel, matches) in bySelector) {
                if (matches.size >= 3) {
                    candidates.getOrPut(sel) { mutableListOf() }.addAll(matches)
                }
            }
        }

        // Rank: prefer containers with more repeated items and specific selectors
        val ranked = candidates.map { (sel, els) ->
            val unique = els.distinct()
            val fields = analyzeContainerElements(sel, unique.first())
            RepeatingContainer(
                selector = sel, count = unique.size,
                possibleFields = fields,
                sampleHtml = unique.firstOrNull()?.html()?.take(200) ?: "",
                purpose = inferPurpose(sel, fields)
            )
        }.sortedByDescending { it.count }

        // Filter: prefer narrower/more specific selectors when counts are similar
        val filtered = ranked.filter { rc ->
            val selTag = rc.selector.substringBefore(".")
            !ranked.any { other ->
                other != rc &&
                other.selector.startsWith("$selTag.") &&
                other.selector.length < rc.selector.length &&
                other.count >= rc.count * 0.8 &&
                other.count <= rc.count * 1.2
            }
        }

        return filtered.take(10)
    }

    private fun candidateSelector(el: Element): String {
        val tag = el.tagName()
        val classes = el.classNames()
        return if (classes.isNotEmpty()) {
            classes.joinToString(".") { "$tag.$it" }
        } else {
            val parent = el.parent()
            if (parent != null) {
                val idx = parent.children().filter { it.tagName() == tag }.indexOf(el) + 1
                "$tag:nth-of-type($idx)"
            } else tag
        }
    }

    private fun analyzeContainerElements(containerSelector: String, sample: Element): List<ContainerField> {
        val fields = mutableListOf<ContainerField>()

        // Title candidate: first heading or link with text
        val heading = sample.select("h1, h2, h3, h4, h5, h6").firstOrNull()
        if (heading != null) {
            val text = heading.text().take(60)
            fields.add(ContainerField("title", heading.tagName(), buildSelector(heading), text))
        }

        // Link candidate: anchor with significant text
        val links = sample.select("a[href]")
        val titleLink = links.maxByOrNull { it.text().length }
        if (titleLink != null && titleLink.text().length > 5) {
            val existing = fields.indexOfFirst { it.role == "title" }
            val sel = buildSelector(titleLink)
            if (existing >= 0) {
                // prefer anchor tag for title (it's clickable)
                val cur = fields[existing]
                if (titleLink.text().length > cur.sampleValue.length) {
                    fields[existing] = ContainerField("title", "a", sel, titleLink.text().take(60))
                }
            } else {
                fields.add(ContainerField("title", "a", sel, titleLink.text().take(60)))
            }
        }

        // Cover image
        val img = sample.select("img[src]").firstOrNull()
        if (img != null) {
            val src = img.attr("src")
            fields.add(ContainerField("cover", "img", buildSelector(img), src.take(80)))
        }

        // Description / secondary text
        val descCandidates = sample.select("p, div.desc, div.summary, div.excerpt, .description, .summary__content")
        val desc = descCandidates.maxByOrNull { it.text().length }
        if (desc != null && desc.text().length > 20) {
            fields.add(ContainerField("description", desc.tagName(), buildSelector(desc), desc.text().take(80)))
        }

        // Chapter links (multiple links inside one item)
        if (links.size >= 2) {
            val nonTitleLinks = links.filter { it.attr("abs:href") != titleLink?.attr("abs:href") }
            val chLink = nonTitleLinks.firstOrNull { it.text().contains(Regex("ch|chapter|гл|глава", RegexOption.IGNORE_CASE)) }
            if (chLink != null) {
                fields.add(ContainerField("chapter_link", "a", buildSelector(chLink), chLink.text().take(40)))
            }
        }

        return fields
    }

    private fun buildSelector(el: Element): String {
        val tag = el.tagName()
        val id = el.id()
        if (id.isNotBlank()) return "#$id"
        val classes = el.classNames()
        if (classes.isNotEmpty()) return "$tag.${classes.joinToString(".")}"
        val parent = el.parent()
        return if (parent != null) {
            val idx = parent.children().indexOf(el) + 1
            "$tag:nth-child($idx)"
        } else tag
    }

    private fun inferPurpose(selector: String, fields: List<ContainerField>): String {
        val roles = fields.map { it.role }
        val hasTitle = "title" in roles
        val hasCover = "cover" in roles
        val hasChapter = "chapter_link" in roles
        return when {
            hasTitle && hasCover && hasChapter -> "catalog_item"
            hasTitle && hasChapter -> "chapter_item"
            hasTitle && hasCover -> "catalog_item"
            hasTitle -> "search_result"
            else -> "unknown"
        }
    }

    // ── Forms ──

    private fun findForms(doc: Document): List<FormInfo> {
        return doc.select("form").mapNotNull { form ->
            val action = form.attr("action")
            val method = form.attr("method").ifBlank { "get" }
            val inputs = form.select("input[type!=hidden], input:not([type]), select, textarea").map { it.attr("name") }.filter { it.isNotBlank() }
            if (inputs.isEmpty() && action.isBlank()) return@mapNotNull null
            FormInfo(buildSelector(form), method.uppercase(), action.ifBlank { "(self)" }, inputs)
        }
    }

    // ── Pagination ──

    private fun findPagination(doc: Document): PaginationInfo? {
        val patterns = listOf(
            "ul.pagination a" to "page_numbers",
            ".pagination a" to "page_numbers",
            "a[rel=next]" to "next_link",
            "a.next" to "next_link",
            ".next a" to "next_link",
            "a:contains(Next)" to "next_link",
            "a:contains(›)" to "next_link",
            "a:contains(»)" to "next_link",
            "a:contains(›)" to "next_link",
            ".page-numbers a" to "page_numbers",
            ".pagenav a" to "page_numbers",
            "ul.pager a" to "page_numbers",
            ".page-nav a" to "page_numbers",
            "a.page-numbers" to "page_numbers",
        )
        for ((sel, type) in patterns) {
            try {
                val els = doc.select(sel)
                if (els.isNotEmpty()) {
                    val sample = els.first()?.attr("abs:href") ?: ""
                    return PaginationInfo(sel, type, sample)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // ── Embedded JSON ──

    private fun findEmbeddedJson(doc: Document): List<EmbeddedJson> {
        val results = mutableListOf<EmbeddedJson>()
        val ld = doc.select("script[type=application/ld+json]")
        if (ld.isNotEmpty()) {
            results.add(EmbeddedJson("json_ld", ld.first()?.data()?.take(500) ?: ""))
        }
        for (script in doc.select("script:not([src])")) {
            val html = script.html()
            if (html.contains("__NEXT_DATA__")) {
                val json = html.substringAfter("__NEXT_DATA__").substringAfter("=").substringBefore(";").trim()
                results.add(EmbeddedJson("next_data", json.take(500)))
            }
            if (html.contains("__NUXT__")) {
                val json = html.substringAfter("__NUXT__").substringAfter("=").substringBefore(";").trim()
                results.add(EmbeddedJson("nuxt", json.take(500)))
            }
        }
        return results
    }
}
