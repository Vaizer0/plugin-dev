package dev.pluginstudio.gen

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Merges DOM analysis, selector candidates, validated API probes and CMS
 * cluster knowledge into ranked [PluginBlueprint] variants (best first).
 */
class StrategyPicker(private val fetcher: HttpFetcher = HttpFetcher()) {

    fun pick(
        baseUrl: String,
        pages: List<PageSnapshot>,
        apis: List<ApiProbe>,
        searchApi: SearchStrategy.Api?,
        searchQuery: String,
        idHint: String?,
        nameHint: String?
    ): List<PluginBlueprint> {
        val home = pages.firstOrNull { it.kind == "home" } ?: pages.firstOrNull()
        val book = pages.firstOrNull { it.kind == "book" }
        val chapter = pages.firstOrNull { it.kind == "chapter" }

        val doc = home?.let { Jsoup.parse(it.html, it.url) }
        val cms = home?.dom?.cms
        val cluster = cms?.clusterId

        val icon = home?.html?.let { html ->
            Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.let { fetcher.resolve(home.url, it) }
        } ?: ""

        val lang = detectLanguage(home?.html, baseUrl)

        val name = (nameHint?.takeIf { it.isNotBlank() }
            ?: hostOf(baseUrl).substringBefore(".").replaceFirstChar { it.uppercase() })

        val id = slugify(idHint?.takeIf { it.isNotBlank() } ?: name)

        val catalog = pickCatalog(doc, home, cluster)
        val chapters = pickChapters(book ?: home, cluster)
        val text = pickChapterText(chapter, cluster)
        val bookStrat = pickBook(book)
        val searchPage = pages.firstOrNull { it.kind == "search" }
        val searchDom = searchDomForm(doc, baseUrl, searchPage)

        val baseBlueprint = PluginBlueprint(
            id = id,
            name = name,
            baseUrl = baseUrl,
            language = lang,
            icon = icon,
            variantName = "primary",
            catalog = catalog,
            searchApi = searchApi,
            searchDom = searchDom,
            book = bookStrat,
            chapters = chapters,
            chapterText = text,
            notes = buildList {
                if (searchApi != null) add("Search uses validated JSON API: ${searchApi.urlTemplate}")
                if (cluster != null) add("Detected CMS cluster: $cluster (${cms?.name})")
                add("${pages.size} page(s) analyzed")
            }
        )

        val variants = mutableListOf(baseBlueprint)

        // Variant B: anchor-only catalog + cluster chapter selectors
        if (catalog != null && !catalog.anchorOnly) {
            variants.add(baseBlueprint.copy(
                variantName = "anchor-catalog",
                catalog = catalog.copy(anchorOnly = true),
                chapters = chapters ?: clusterChapters(cluster, book)
            ))
        }
        // Variant C: cluster-template fallbacks for everything DOM
        variants.add(baseBlueprint.copy(
            variantName = "cluster-template",
            catalog = clusterCatalog(cluster, doc) ?: catalog,
            chapters = clusterChapters(cluster, book) ?: chapters,
            chapterText = clusterText(cluster) ?: text
        ))

        return variants.distinctBy { v ->
            listOf(v.variantName, v.catalog.toString(), v.chapters.toString(), v.chapterText.toString())
        }
    }

    // ── Catalog ──

    private fun pickCatalog(doc: Document?, homePage: PageSnapshot?, cluster: String?): CatalogStrategy? {
        val containers = homePage?.dom?.containers.orEmpty()
        if (containers.isEmpty()) return null
        val best = containers.firstOrNull { c ->
            (c.purpose == "catalog_item" || c.purpose == "search_result") &&
                c.possibleFields.any { it.role == "title" }
        } ?: containers.firstOrNull { it.possibleFields.any { f -> f.role == "title" } && it.count >= 4 }

        if (best != null && doc != null) {
            val titleField = best.possibleFields.first { it.role == "title" }
            var containerSel = simplifyContainer(best.selector, doc)
            val titleSel = titleField.selector
            val coverSel = best.possibleFields.firstOrNull { it.role == "cover" }?.selector
            val linkSel = "a"
            // verify the pair actually yields items with links+text on the real page
            if (!verifyCatalog(doc, containerSel, titleSel, linkSel)) {
                containerSel = relaxContainer(containerSel, doc) ?: containerSel
            }
            return CatalogStrategy(
                containerSel = containerSel,
                titleSel = titleSel,
                linkSel = linkSel,
                coverSel = coverSel ?: "img",
                anchorOnly = !verifyTitles(doc, containerSel, titleSel),
                basePath = catalogBasePath(homePage?.url)
            )
        }
        return clusterCatalog(cluster, doc)
    }

    /** Strip :nth-child noise and overly specific chains. */
    private fun simplifyContainer(sel: String, doc: Document): String {
        val noNth = sel.substringBefore(":nth-child").substringBefore(":nth-of-type")
        return if (noNth.isNotBlank() && doc.select(noNth).size >= 3) noNth else sel
    }

    private fun relaxContainer(sel: String, doc: Document): String? {
        val tagAndFirstClass = sel.split(".").take(2).joinToString(".")
        return if (tagAndFirstClass != sel && doc.select(tagAndFirstClass).size >= 3) tagAndFirstClass else null
    }

    private fun verifyCatalog(doc: Document, containerSel: String, titleSel: String, linkSel: String): Boolean =
        runCatching {
            doc.select(containerSel).count { card ->
                card.select(linkSel).isNotEmpty() &&
                    (card.selectFirst(titleSel)?.text()?.isNotBlank() == true || card.select("a").firstOrNull()?.text()?.isNotBlank() == true)
            } >= 2
        }.getOrDefault(false)

    private fun verifyTitles(doc: Document, containerSel: String, titleSel: String): Boolean =
        runCatching {
            doc.select("$containerSel $titleSel").count { it.text().isNotBlank() } >= 2 ||
                doc.select(containerSel).mapNotNull { it.selectFirst("a") }.count { it.text().length > 5 } >= 2
        }.getOrDefault(false)

    private fun clusterCatalog(cluster: String?, doc: Document?): CatalogStrategy? {
        if (doc == null) return null
        val presets = mapOf(
            "madara_theme" to listOf(
                Triple(".c-tabs-item__content", ".post-title h3 a, .h5 a", ".tab-thumb img, img"),
                Triple(".page-item-detail", ".post-title h3 a, h5 a", "img")
            ),
            "novelbin_clone" to listOf(
                Triple(".col-truyen-main .row", "h3.truncate a, h3 a", "img")
            ),
            "scribblehub" to listOf(
                Triple(".search_main_box", ".fic_title", ".fic_image img")
            ),
            "wordpress" to listOf(
                Triple(".library-item, article", "h2 a, h3 a, .entry-title a", "img")
            )
        )
        return presets[cluster]?.firstNotNullOfOrNull { (c, t, cov) ->
            if (runCatching { doc.select(c).size >= 3 }.getOrDefault(false))
                CatalogStrategy(c, t, "a", cov, anchorOnly = true, pageParamStyle = defaultPageStyle(cluster))
            else null
        }
    }

    /** Path (with any non-pagination query) of the catalog listing page. */
    private fun catalogBasePath(homeUrl: String?): String {
        homeUrl ?: return ""
        val u = runCatching { java.net.URI(homeUrl) }.getOrNull() ?: return ""
        var path = (u.rawPath ?: "") + if (u.rawQuery != null) "?${u.rawQuery}" else ""
        path = Regex("[?&]page=\\d+").replace(path, "")
        if (path.endsWith("/") && path.length > 1) path = path.trimEnd('/')
        return path
    }

    private fun defaultPageStyle(cluster: String?) =
        if (cluster == "novelbin_clone") "path_page_1" else "query_page_1"

    // ── Chapters ──

    fun pickChapters(page: PageSnapshot?, cluster: String?): ChaptersStrategy? {
        if (page == null) return null
        val doc = Jsoup.parse(page.html, page.url)
        findChapterAnchorsSelector(doc)?.let { sel ->
            return ChaptersStrategy(anchorsSel = sel)
        }
        return clusterChapters(cluster, page)
    }

    /** Find the selector whose matches are mostly chapter-like anchors (>3). */
    private fun findChapterAnchorsSelector(doc: Document): String? {
        val tally = mutableMapOf<String, Int>()
        val anchors = doc.select("a[href]").filter {
            val h = it.attr("href")
            (h.startsWith("http") || h.startsWith("/") || h.contains("://")) &&
                Regex("chapter|/chap|/ch/|/ch-|глава|/read-", RegexOption.IGNORE_CASE).containsMatchIn(h)
        }
        if (anchors.size < 3) return null
        for (a in anchors.take(60)) {
            var el: Element? = a
            repeat(3) {
                el = el?.parent() ?: return@repeat
                val p = el ?: return@repeat
                if (p.tagName() in listOf("ul", "div", "ol", "tbody", "table")) {
                    val sel = candidateOf(p) + " a"
                    val matches = runCatching {
                        doc.select(sel).count { x ->
                            val h = x.attr("href")
                            h.startsWith("/") && !h.startsWith("//") ||
                                h.startsWith("http") && isChapterishAnchor(x)
                        }
                    }.getOrDefault(0)
                    if (matches >= 3) tally[sel] = (tally[sel] ?: 0) + matches
                }
            }
        }
        return tally.maxByOrNull { it.value }?.key
    }

    private fun isChapterishAnchor(a: Element): Boolean =
        isChapterHref(a.attr("href")) || Regex("^\\s*(chapter|гл\\.?|chap)\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(a.text())

    private fun isChapterHref(href: String): Boolean =
        Regex("chapter|/ch-?\\d|/chap|глава|/read-", RegexOption.IGNORE_CASE).containsMatchIn(href)

    private fun candidateOf(el: Element): String {
        val id = el.id()
        if (id.isNotBlank()) return "#$id"
        val classes = el.classNames()
        if (classes.isNotEmpty()) return el.tagName() + "." + classes.joinToString(".")
        return el.tagName()
    }

    private fun clusterChapters(cluster: String?, page: PageSnapshot?): ChaptersStrategy? {
        if (page == null) return null
        val doc = Jsoup.parse(page.html, page.url)
        val presets = mapOf(
            "madara_theme" to listOf(".wp-manga-chapter a", ".listing-chapters_wrap a", ".reading-content a[href*='chapter']"),
            "novelbin_clone" to listOf(".list-chapter a", ".chapter-list a"),
            "scribblehub" to listOf("#chp_raw a", ".chp_raw a"),
            "wordpress" to listOf(".chap a", "a[href*='chapter']")
        )[cluster] ?: emptyList()
        return presets.firstOrNull { runCatching { doc.select(it).size >= 3 }.getOrDefault(false) }
            ?.let { ChaptersStrategy(anchorsSel = it) }
    }

    // ── Chapter content ──

    private fun pickChapterText(chapter: PageSnapshot?, cluster: String?): ChapterTextStrategy? {
        if (chapter == null) return null
        val doc = Jsoup.parse(chapter.html, chapter.url)
        val removals = listOf("script", "style", "nav", "footer", "ins", ".adsbygoogle", ".ad", ".ads",
            "#ad", ".sharedaddy", ".code-block", ".chapter-nav", "#comments", ".post-footer")

        // SelectorAnalyzer's suggestion first
        chapter.js?.selectorResult?.candidates?.get("chapter_content")?.firstOrNull()?.let { c ->
            if (textContentLen(doc, c.selector) > 200) {
                return ChapterTextStrategy(c.selector, removals)
            }
        }
        val common = listOf(
            "#chapter-content", ".reading-content", "#chp_raw", ".chapter-content", ".text-content",
            ".entry-content", ".content-area", "article.post", "#content", ".chr-content", ".chapter_body"
        )
        common.firstOrNull { textContentLen(doc, it) > 200 }?.let { return ChapterTextStrategy(it, removals) }

        // densest <p> block wins as last resort
        val dense = doc.select("div, section, article").maxByOrNull { el ->
            val paras = el.select(":scope > p")
            if (paras.isEmpty()) 0 else el.ownText().length + paras.sumOf { it.text().length }
        }
        if (dense != null && dense.text().length > 400) {
            return ChapterTextStrategy(candidateOf(dense), removals)
        }
        return clusterText(cluster) ?: ChapterTextStrategy(null, removals)
    }

    private fun textContentLen(doc: Document, sel: String): Int =
        runCatching { doc.selectFirst(sel)?.text()?.length ?: 0 }.getOrDefault(0)

    private fun clusterText(cluster: String?): ChapterTextStrategy? = when (cluster) {
        "madara_theme" -> ChapterTextStrategy(".reading-content .read-container, .reading-content", MADARA_REMOVES)
        "novelbin_clone" -> ChapterTextStrategy("#chapter-content, .chapter-content", NOVELBIN_REMOVES)
        else -> null
    }

    // ── Book metadata ──

    private val junkImgSrc = Regex(
        "amazon|analytics|doubleclick|pixel|facebook|twitter|/batch/|1x1|logo|avatar|icon|emoji",
        RegexOption.IGNORE_CASE
    )

    private val junkTitle = Regex(
        "^(skip|follow|sign|menu|search|home|genre|genres|update|latest|read now|bookmark|share|report|next|prev)",
        RegexOption.IGNORE_CASE
    )

    private fun pickBook(book: PageSnapshot?): BookStrategy? {
        if (book == null) return BookStrategy(null, null, null, null)
        val doc = Jsoup.parse(book.html, book.url)
        val cands = book.js?.selectorResult?.candidates.orEmpty()

        fun usable(sel: String?, minText: Int): String? {
            sel ?: return null
            val t = runCatching { doc.selectFirst(sel)?.text() ?: "" }.getOrDefault("")
            return if (t.length >= minText && !junkTitle.containsMatchIn(t.trim())) sel else null
        }

        val title = usable(cands["book_title"]?.firstOrNull()?.selector, 4)
            ?: firstNonEmpty(doc, listOf("h1.fiction-title", ".fiction-title h1", "h1.novel-title",
                ".novel-title h1", ".post-title h1", "h1[itemprop=name]", "h1"))
                ?.takeIf { sel ->
                    val t = runCatching { doc.selectFirst(sel)?.text()?.trim() ?: "" }.getOrDefault("")
                    t.length in 4..200 && !junkTitle.containsMatchIn(t)
                }
        val cover = cands["book_cover"]?.firstOrNull()?.let { c ->
            val src = runCatching {
                val el = doc.selectFirst(c.selector)
                el?.attr("src") ?: el?.attr("data-src") ?: ""
            }.getOrDefault("")
            if (src.isNotBlank() && !junkImgSrc.containsMatchIn(src)) c.selector else null
        } ?: firstGoodImg(doc)

        // description: common classes, selector candidates (≥120 chars), then og meta
        val desc = firstNonEmpty(doc, listOf(".summary__content", ".description", "#description", ".novel-desc",
            "[itemprop=description]", ".story-notes", ".hidden-content"))
            ?: cands["book_description"]?.firstOrNull { c ->
                runCatching { (doc.selectFirst(c.selector)?.text()?.length ?: 0) >= 120 }.getOrDefault(false)
            }?.selector
            ?: "meta[property='og:description']"   // html_attr(content) fallback emitted by generator

        val genres = guessGenres(doc)

        return BookStrategy(title, cover, desc, genres)
    }

    private fun guessGenres(doc: Document): String? {
        val candidates = listOf(
            ".genres_content a", ".genres a", "a[href*='/genre/']", "a[href*='/genres/']",
            ".genre-tag", ".tags a", "a[href*='/tag/']", "a[href*='genre']", ".story-categories a",
            ".categories a", "[itemprop=genre] a"
        )
        return candidates.firstOrNull { sel ->
            runCatching {
                val els = doc.select(sel)
                els.size in 1..30 && els.all { it.text().length <= 40 } && els.first()!!.text().isNotBlank()
            }.getOrDefault(false)
        }
    }

    private fun firstNonEmpty(doc: Document, sels: List<String>): String? =
        sels.firstOrNull { runCatching { doc.selectFirst(it)?.text()?.isNotBlank() == true }.getOrDefault(false) }

    private fun firstNonEmptyAttr(doc: Document, sels: List<String>): String? =
        sels.firstOrNull { sel ->
            runCatching {
                val el = doc.selectFirst(sel)
                el != null && (el.attr("src").isNotEmpty() || el.attr("data-src").isNotEmpty())
            }.getOrDefault(false)
        }

    /** Best-effort cover image: first non-junk <img> with a real src. */
    private fun firstGoodImg(doc: Document): String? =
        doc.select("img").firstOrNull { img ->
            val src = img.attr("src") + img.attr("data-src")
            src.startsWith("http") && !junkImgSrc.containsMatchIn(src) &&
                (img.attr("width").isEmpty() || img.attr("width").toIntOrNull() ?: 999 >= 80)
        }?.let { candidateOf(it) }

    private fun searchDomForm(homeDoc: Document?, base: String, searchPage: PageSnapshot?): SearchStrategy.Dom? {
        var action: String? = null; var inputName: String? = null
        if (homeDoc != null) {
            for (form in homeDoc.select("form")) {
                val input = form.selectFirst("input[type=text], input:not([type]), input[type=search]") ?: continue
                val name = input.attr("name")
                if (name.isBlank()) continue
                action = fetcher.resolve(base, form.attr("action").ifBlank { "/" })
                inputName = name
                break
            }
        }
        // derive result-parsing selectors from the actual search results page
        if (searchPage != null && searchPage.dom != null) {
            val sDoc = Jsoup.parse(searchPage.html, searchPage.url)
            pickCatalog(sDoc, searchPage, null)?.let { cat ->
                return SearchStrategy.Dom(
                    formAction = action ?: searchPage.url,
                    inputName = inputName ?: guessSearchParam(searchPage.url) ?: "q",
                    containerSel = cat.containerSel,
                    titleSel = cat.titleSel,
                    linkOnly = cat.anchorOnly,
                    coverSel = cat.coverSel
                )
            }
            return SearchStrategy.Dom(action ?: searchPage.url, inputName ?: "q", "a", "a", true, null)
        }
        return if (action != null && inputName != null) SearchStrategy.Dom(action, inputName) else null
    }

    private fun guessSearchParam(url: String): String? =
        Regex("[?&](\\w+)=").find(url)?.groupValues?.get(1)

    // ── utils ──

    private fun detectLanguage(html: String?, url: String): String {
        html?.let {
            Regex("""<html[^>]+lang=["']([a-zA-Z-]{2,8})["']""").find(it)?.groupValues?.get(1)?.let { l ->
                return l.substringBefore('-').lowercase()
            }
        }
        val tld = url.substringAfterLast('.', "").substringBefore('/').lowercase()
        return if (tld.length == 2 && tld != "com") tld else "en"
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: ""
    } catch (_: Exception) { ""

    }

    fun slugify(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(32).ifBlank { "generated_source" }

    companion object {
        private val MADARA_REMOVES = listOf("script", "style", ".adsbygoogle", "ins", ".ad", ".ads", ".sharedaddy", "nav", "footer")
        private val NOVELBIN_REMOVES = listOf("script", "style", ".adsbygoogle", "ins", ".ad", ".ads", "#google_ads", ".chaos")
    }
}
