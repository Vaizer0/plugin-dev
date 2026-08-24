package dev.pluginstudio.gen

import dev.pluginstudio.analyzer.DomAnalyzer
import dev.pluginstudio.analyzer.SiteAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Builds a multi-page corpus (home / search / book / chapter) using normal
 * HTTP access. Protected sites that return a Cloudflare challenge are reported
 * so the caller can fall back to the browser-assisted capture flow.
 */
class SiteCrawler(
    private val fetcher: HttpFetcher = HttpFetcher(),
    private val domAnalyzer: DomAnalyzer = DomAnalyzer(),
    private val siteAnalyzer: SiteAnalyzer = SiteAnalyzer(maxExternalScripts = 2)
) {
    data class CrawlResult(
        val success: Boolean,
        val blocked: Boolean = false,
        val error: String? = null,
        val baseUrl: String = "",
        val pages: MutableList<PageSnapshot> = mutableListOf()
    )

    suspend fun crawl(
        seedUrl: String,
        searchQuery: String,
        onStep: (String) -> Unit = {}
    ): CrawlResult = withContext(Dispatchers.IO) {
        val base = baseUrlOf(seedUrl)
        if (base.isBlank()) return@withContext CrawlResult(false, error = "Invalid URL")

        // ── 1. home ──
        onStep("Fetching home page …")
        val homeResp = fetcher.get(seedUrl)
        if (!homeResp.ok) {
            val why = if (homeResp.blocked) "blocked by bot protection (Cloudflare challenge)" else "HTTP ${homeResp.status}"
            return@withContext CrawlResult(false, blocked = homeResp.blocked, error = "Home page $why", baseUrl = base)
        }
        // Normalize to the final post-redirect URL (e.g. non-www → www)
        // so the generated plugin never depends on redirect behaviour.
        val homeUrl = homeResp.finalUrl.ifBlank { seedUrl }
        val effectiveBase = baseUrlOf(homeUrl).ifBlank { base }
        val homeDoc = Jsoup.parse(homeResp.body, homeUrl)
        val pages = mutableListOf(snapshot(homeUrl, homeResp.body, "home", homeDoc))
        onStep("Home page OK (${homeResp.body.length} bytes) → $effectiveBase")

        // ── 2+3+4. book / chapter / search — fetched concurrently ──
        onStep("Looking for a book/novel link …")
        var bookLinks = findBookLinks(homeDoc, base)
        if (bookLinks.isEmpty()) {
            // Keyword heuristics found nothing → derive book URLs from the
            // prefixes of chapter links themselves (works for any URL scheme).
            bookLinks = inferBookLinksFromChapters(homeDoc, base)
            if (bookLinks.isNotEmpty()) onStep("Inferred ${bookLinks.size} book link(s) from chapter URL patterns …")
        }

        return@withContext coroutineScope {
            val searchUrl = discoverSearchUrl(homeDoc, base, searchQuery)
            val deadline = System.currentTimeMillis() + 45_000   // hard budget for the whole crawl

            val bookJob = async {
                val snaps = bookLinks.take(3).map { link -> async {
                    if (System.currentTimeMillis() > deadline) return@async null
                    val r = fetcher.get(link, timeoutSec = 15)
                    if (!r.ok || r.blocked) null
                    else snapshot(link, r.body, "book", Jsoup.parse(r.body, link)) to hasChapterLinks(Jsoup.parse(r.body, link))
                } }.awaitAll().filterNotNull()
                val best = snaps.firstOrNull { it.second }?.first ?: snaps.firstOrNull()?.first
                pages.addAll(snaps.map { it.first })
                best
            }
            val chapterJob = async {
                val bs = bookJob.await()
                if (bs == null || System.currentTimeMillis() > deadline) return@async null
                val doc = Jsoup.parse(bs.html, bs.url)
                var chLink = firstChapterLink(doc, bs.url)
                // JS-driven chapter lists often live on a /chapters sub-page.
                if (chLink == null && !bs.url.endsWith("/chapters")) {
                    val sub = bs.url.trimEnd('/') + "/chapters"
                    val sr = fetcher.get(sub, timeoutSec = 12)
                    if (sr.ok && !sr.blocked && hasChapterLinks(Jsoup.parse(sr.body, sub))) {
                        chLink = firstChapterLink(Jsoup.parse(sr.body, sub), sub)
                        if (chLink == null) chLink = sub
                        pages.add(snapshot(sub, sr.body, "book", Jsoup.parse(sr.body, sub)))
                    }
                }
                if (chLink == null) return@async null
                val r = fetcher.get(chLink, timeoutSec = 20)
                if (!r.ok || r.blocked) { onStep("Chapter fetch failed (${r.status})"); return@async null }
                val snap = snapshot(chLink, r.body, "chapter", Jsoup.parse(r.body, chLink))
                pages.add(snap); snap
            }
            val searchJob = async {
                if (searchUrl == null) { onStep("No obvious search form/path found — will still probe JSON APIs"); return@async null }
                if (System.currentTimeMillis() > deadline) return@async null
                val r = fetcher.get(searchUrl, timeoutSec = 15)
                if (!r.ok || r.blocked) { onStep("Search fetch failed (${r.status})"); return@async null }
                val doc2 = Jsoup.parse(r.body, searchUrl)
                if (countBookishLinks(doc2) >= 2 || looksLikeResults(doc2, searchQuery)) {
                    val snap = snapshot(searchUrl, r.body, "search", doc2)
                    pages.add(snap); snap
                } else { onStep("Search URL returned no results — will still probe JSON APIs"); null }
            }

            val bookSnap = bookJob.await()
            onStep(if (bookSnap != null) "Book page captured: ${bookSnap.url}" else "No book page found from home links")
            val chapterSnap = chapterJob.await()
            onStep(if (chapterSnap != null) "Chapter page captured: ${chapterSnap.url}" else "No chapter content captured")
            val searchSnap = searchJob.await()
            onStep(if (searchSnap != null) "Search results captured: ${searchSnap.url}" else "")

            CrawlResult(true, baseUrl = effectiveBase, pages = pages)
        }
    }

    /** Build corpus purely from browser-captured snapshots (protected sites). */
    fun corpusFromCapture(pages: List<Triple<String, String, List<NetRecord>>>): CrawlResult {
        if (pages.isEmpty()) return CrawlResult(false, error = "No browser-captured pages available")
        val base = baseUrlOf(pages.first().first)
        val snaps = pages.map { (url, html, _) ->
            snapshot(url, html, classify(url), Jsoup.parse(html, url), viaBrowser = true)
        }
        return CrawlResult(true, baseUrl = base, pages = snaps.toMutableList())
    }

    private fun snapshot(url: String, html: String, kind: String, doc: Document, viaBrowser: Boolean = false): PageSnapshot {
        val dom = domAnalyzer.analyze(url, html)
        val js = try { siteAnalyzer.analyzeHtml(url, html, dom) } catch (_: Exception) { null }
        return PageSnapshot(url, html, kind, viaBrowser, dom, js)
    }

    fun classify(url: String): String {
        val path = try { java.net.URI(url).path ?: "" } catch (_: Exception) { url }
        return when {
            Regex("chapter|/chap|/ch-?\\d|глава|/read-", RegexOption.IGNORE_CASE).containsMatchIn(path) -> "chapter"
            Regex("search|query|[?&]s=", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "search"
            Regex("/(fiction|novel|book|manga|series|story|comic|work)s?/", RegexOption.IGNORE_CASE).containsMatchIn(path) -> "book"
            path.isEmpty() || path == "/" -> "home"
            else -> "other"
        }
    }

    private fun baseUrlOf(url: String): String = try {
        val u = java.net.URI(url.trim())
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.scheme ?: "https"}://${u.host ?: ""}$port"
    } catch (_: Exception) { ""

    }

    private fun hasChapterLinks(doc: Document): Boolean =
        doc.select("a[href]").any { isChapterHref(it.attr("href")) }

    fun isChapterHref(href: String): Boolean =
        Regex("chapter|/ch-?\\d|/chap|глава|/read-", RegexOption.IGNORE_CASE).containsMatchIn(href)

    private fun countBookishLinks(doc: Document): Int =
        doc.select("a[href]").count { isBookHref(it.attr("href")) && it.text().length > 5 }

    private fun looksLikeResults(doc: Document, query: String): Boolean =
        doc.body().text().contains(query, ignoreCase = true)

    fun isBookHref(href: String): Boolean =
        Regex("/(novel|book|manga|series|story|comic|work|fiction)s?/", RegexOption.IGNORE_CASE).containsMatchIn(href) ||
            Regex("/n/\\d", RegexOption.IGNORE_CASE).containsMatchIn(href)

    private val utilityPath = Regex(
        "/(notification|account|user|profile|login|register|signin|signup|auth|forum|blog|wiki|support|help|" +
            "privacy|terms|settings|message|billing|payment|premium|donate|about|contact|career|job|api/|" +
            "search|tag|genre|category|rank|popular|latest|home|index|read|library|author|upload)",
        RegexOption.IGNORE_CASE
    )

    private fun findBookLinks(doc: Document, base: String): List<String> {
        val scored = doc.select("a[href]").mapNotNull { a ->
            val raw = a.attr("href")
            if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("mailto") || raw.startsWith("javascript")) return@mapNotNull null
            val href = a.attr("abs:href").ifBlank { fetchResolve(base, raw) }
            if (href.isBlank() || samePage(href, base)) return@mapNotNull null
            if (utilityPath.containsMatchIn(href)) return@mapNotNull null
            var score = 0
            if (isBookHref(href)) score += 4
            if (href.removePrefix("https://").removePrefix("http://").count { it == '/' } >= 2) score += 1
            val text = a.text().trim()
            if (text.length in 10..120) score += 2 else if (text.length > 120) score -= 1
            if (a.closest(".c-tabs-item__content, .col-truyen-main, .recommendation-card, .list-truyen, .fiction-list-item") != null) score += 2
            if (a.selectFirst("img") != null) score += 1
            if (score < 5) return@mapNotNull null
            href to score
        }.sortedByDescending { it.second }
        return scored.map { it.first }.distinct().take(6)
    }

    private fun samePage(a: String, b: String) =
        a.substringBefore("#") == b.substringBefore("#") || a == "$b/" || b == "$a/"

    /**
     * Book URLs are the prefixes of chapter URLs. E.g. given
     * "/libread/slug-123/chapter-0174" the book is "/libread/slug-123".
     * Fully data-driven — no URL scheme assumptions.
     */
    fun inferBookLinksFromChapters(doc: Document, base: String): List<String> {
        val freq = HashMap<String, Int>()
        for (a in doc.select("a[href]")) {
            val h = a.attr("href")
            if (!isChapterHref(h)) continue
            val abs = a.attr("abs:href").ifBlank { fetchResolve(base, h) }
            if (abs.isBlank()) continue
            val bookUrl = abs.replace(Regex("/chapter.*$", RegexOption.IGNORE_CASE), "").trimEnd('/')
            if (bookUrl == abs || bookUrl.length <= base.length + 1) continue
            freq[bookUrl] = (freq[bookUrl] ?: 0) + 1
        }
        return freq.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { u -> doc.select("a[href]").any { (it.attr("abs:href").ifBlank { fetchResolve(base, it.attr("href")) }) == u } }
            .take(6)
    }

    private fun fetchResolve(base: String, href: String): String =
        if (href.isBlank()) "" else fetcher.resolve(base, href)

    fun firstChapterLink(doc: Document, pageUrl: String): String? {
        val a = doc.select("a[href]").firstOrNull { isChapterHref(it.attr("href")) } ?: return null
        val abs = a.attr("abs:href")
        return if (abs.isNotBlank()) abs else fetchResolve(pageUrl, a.attr("href"))
    }

    /** Try the page's search form first, then common paths. Returns absolute search URL or null. */
    fun discoverSearchUrl(homeDoc: Document, base: String, query: String): String? {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        for (form in homeDoc.select("form")) {
            val action = form.attr("abs:action").ifBlank { base }
            val textInputs = form.select("input[type=text], input:not([type]), input[type=search]")
            val input = textInputs.firstOrNull() ?: continue
            val name = input.attr("name")
            if (name.isBlank()) continue
            val method = form.attr("method").lowercase()
            if (method == "post") continue
            val sep = if (action.contains("?")) "&" else "?"
            return "$action${sep}$name=$q"
        }
        val candidates = listOf(
            "$base/?s=$q", "$base/search?q=$q", "$base/?searchkey=$q",
            "$base/search/$q", "$base/tim-kiem?q=$q"
        )
        return candidates.firstOrNull()
    }
}
