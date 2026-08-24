package dev.pluginstudio.gen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withPermit

/**
 * Live-validates candidate API endpoints discovered by JS scanning or captured
 * from the user's browser, and classifies the ones a plugin can actually use.
 */
class ApiProber(private val fetcher: HttpFetcher = HttpFetcher()) {

    private val assetHints = listOf("/assets/", "/images/", "/img/", "/css/", "/js/", "/fonts/",
        "/static/", "/dist/", ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".woff", ".ttf", ".ico")

    /** Probe endpoints concurrently and return only usable JSON list APIs (best first). */
    suspend fun probe(
        base: String,
        candidates: List<Pair<String, String>>,   // url to method ("GET"/"POST")
        searchQuery: String,
        onStep: (String) -> Unit = {}
    ): List<ApiProbe> = withContext(Dispatchers.IO) {
        val host = hostOf(base)
        val seen = mutableSetOf<String>()
        val toProbe = candidates.mapNotNull { (rawUrl, method) ->
            val abs = fetcher.resolve(base, rawUrl.trim().trim('"', '\'', '`'))
            if (abs.isBlank() || !abs.startsWith("http")) return@mapNotNull null
            if (assetHints.any { abs.lowercase().contains(it) }) return@mapNotNull null
            val isSameSite = host.isBlank() || abs.contains(host)
            val wasAbsolute = rawUrl.startsWith("http")
            if (!isSameSite && !wasAbsolute) return@mapNotNull null
            val key = "$method $abs"
            if (!seen.add(key)) return@mapNotNull null
            abs to method
        }.take(8)

        onStep("Testing ${toProbe.size} endpoint(s) in parallel …")

        coroutineScope {
            val sem = Semaphore(6)
            val results = java.util.Collections.synchronizedList(mutableListOf<ApiProbe>())
            val jobs = toProbe.map { (abs, method) ->
                async {
                    sem.withPermit {
                        try {
                            val resp = if (method == "POST")
                                fetcher.post(abs, "action=${extractAjaxAction(abs)}", timeoutSec = 7)
                            else fetcher.get(abs, timeoutSec = 7)
                            if (!resp.ok || resp.blocked || resp.status == 0) return@withPermit
                            val body = resp.body.trim()
                            val isJson = resp.contentType.contains("json", true) ||
                                body.startsWith("{") || body.startsWith("[")
                            if (!isJson) { note(onStep, abs, resp.status); return@withPermit }
                            val shape = analyzeJsonShape(body)
                            if (shape.sampleCount <= 0 || shape.titleField == null) { note(onStep, abs, resp.status); return@withPermit }
                            val probe = ApiProbe(abs, method, resp.status, resp.contentType, true,
                                shape.listField, shape.titleField, shape.urlOrSlugField, shape.coverField,
                                shape.sampleCount, source = "probe")
                            var added = false
                            synchronized(results) {
                                if (results.size < 4) { results.add(probe); added = true }
                            }
                            if (added) onStep("✓ working API: $abs (${shape.sampleCount} items)")
                        } catch (_: Exception) { }
                    }
                }
            }
            // hard deadline for the whole probe phase
            withTimeoutOrNull(15_000) { jobs.joinAll() }
            results.sortedByDescending { it.sampleCount }
        }
    }

    /** Turn a validated probe into a concrete search strategy, verifying one real query. */
    suspend fun buildSearchApi(probe: ApiProbe, base: String, query: String): SearchStrategy.Api? =
        withContext(Dispatchers.IO) {
            val template = makeTemplate(probe.url, query)
            if (template == null) null else {
                // verify the template with a different query still returns items
                val testUrl = template.replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
                val r = if (probe.method == "POST") fetcher.post(testUrl, "") else fetcher.get(testUrl)
                if (!r.ok || !(r.contentType.contains("json", true) || r.body.trim().startsWith("{") || r.body.trim().startsWith("["))) {
                    null
                } else {
                    SearchStrategy.Api(
                        method = probe.method,
                        urlTemplate = template,
                        listField = probe.listField,
                        titleField = probe.titleField ?: "title",
                        urlField = probe.urlOrSlugField?.takeIf { it in setOf("url", "link", "href") },
                        slugField = probe.urlOrSlugField?.takeIf { it == "slug" || it.endsWith("_slug") || it == "id" },
                        coverField = probe.coverField,
                        bookUrlPrefix = guessBookPrefix(base, probe.url)
                    )
                }
            }
        }

    private fun makeTemplate(url: String, usedQuery: String): String? {
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        val qParams = listOf("q", "s", "search", "query", "searchkey", "keyword", "name")
        for (p in qParams) {
            if (decoded.contains("$p=", ignoreCase = true)) {
                val re = Regex("""([?&])$p=[^&]*""", RegexOption.IGNORE_CASE)
                return decoded.replace(re) { m -> "${m.groupValues[1]}$p={query}" }
            }
        }
        // path-style /search/<term>
        if (usedQuery.isNotBlank() && decoded.contains(usedQuery)) {
            return decoded.replace(usedQuery, "{query}")
        }
        return if (decoded.contains("{query}")) decoded else null
    }

    private fun guessBookPrefix(base: String, apiUrl: String): String {
        // common patterns: /api/search → books at /novel/{slug}
        return if (Regex("/novel", RegexOption.IGNORE_CASE).containsMatchIn(apiUrl)) "$base/novel/"
        else if (Regex("/book", RegexOption.IGNORE_CASE).containsMatchIn(apiUrl)) "$base/book/"
        else if (Regex("/manga", RegexOption.IGNORE_CASE).containsMatchIn(apiUrl)) "$base/manga/"
        else "$base/novel/"
    }

    data class JsonShape(val sampleCount: Int, val listField: String?, val titleField: String?, val urlOrSlugField: String?, val coverField: String?)

    fun analyzeJsonShape(body: String): JsonShape {
        return try {
            @Suppress("UNCHECKED_CAST")
            val root: Any = gson().fromJson(body, Any::class.java) ?: return JsonShape(0, null, null, null, null)
            val arr: List<Map<String, Any>>? = when (root) {
                is List<*> -> root.filterIsInstance<Map<String, Any>>().takeIf { it.isNotEmpty() }
                is Map<*, *> -> findArray(root)
                else -> null
            }
            val items = arr ?: return JsonShape(0, null, null, null, null)
            val keys = items.first().keys
            val title = keys.firstOrNull { it.equals("title", true) || it.equals("name", true) || it.equals("novel_name", true) || it.equals("book_name", true) || it.equals("story_name", true) }
            val link = keys.firstOrNull { it.equals("url", true) || it.equals("link", true) || it.equals("href", true) }
            val slug = keys.firstOrNull { it.equals("slug", true) || it.equals("novel_slug", true) || it.equals("id", true) || it.equals("book_id", true) || it.equals("novel_id", true) }
            val cover = keys.firstOrNull { it.contains("cover", true) || it.contains("image", true) || it.contains("thumb", true) || it.equals("poster", true) }
            if (title == null) JsonShape(items.size, null, null, null, cover)
            else JsonShape(items.size, null, title, link ?: slug, cover)
        } catch (_: Exception) {
            JsonShape(0, null, null, null, null)
        }
    }

    /** Find the first array-of-objects inside an arbitrary JSON object (depth ≤ 2). */
    private fun findArray(root: Map<*, *>): List<Map<String, Any>>? {
        for ((k, v) in root) {
            when {
                v is List<*> && v.isNotEmpty() && v.all { it is Map<*, *> } ->
                    @Suppress("UNCHECKED_CAST")
                    return (v as List<Map<String, Any>>).takeIf { it.isNotEmpty() }
                v is Map<*, *> -> findArray(v)?.let { return it }
            }
        }
        return null
    }

    private fun extractAjaxAction(url: String): String =
        Regex("action=([^&]+)").find(url)?.groupValues?.get(1) ?: ""

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host?.lowercase() ?: ""
    } catch (_: Exception) { "" }

    private fun gson() = com.google.gson.Gson()

    private fun seenProbe(onStep: (String) -> Unit, abs: String, method: String) =
        onStep("Probing $method $abs …")

    private fun note(onStep: (String) -> Unit, abs: String, status: Int) {
        if (status != 0) onStep("  ✗ $abs (HTTP $status — not a usable JSON list)")
    }

    /** Collect endpoint candidates from analysis reports of all snapshots. */
    fun candidatesFromPages(pages: List<PageSnapshot>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        pages.forEach { p ->
            p.js?.endpointSuggestions?.forEach { ep ->
                out.add(if (ep.source == "WORDPRESS_AJAX_COMBINED" || ep.source == "WP_AJAX_URL") ep.url to "POST" else ep.url to "GET")
            }
        }
        return out.distinctBy { it.first.lowercase() }
    }

    /** Browser capture gives us REAL runtime requests — highest priority evidence. */
    suspend fun probeCaptured(base: String, records: List<NetRecord>, query: String): List<ApiProbe> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<ApiProbe>()
            for (r in records.sortedByDescending { it.bodySample.length }.take(20)) {
                if (results.size >= 4) break
                val ct = r.contentType
                val body = r.bodySample.trim()
                val looksJson = ct.contains("json", true) || body.startsWith("{") || body.startsWith("[")
                if (!looksJson || body.length < 10) continue
                val shape = analyzeJsonShape(body)
                if (shape.titleField == null || shape.sampleCount <= 0) continue
                results.add(ApiProbe(r.url, r.method, r.status, ct, true, null,
                    shape.titleField, shape.urlOrSlugField, shape.coverField, shape.sampleCount, "browser"))
            }
            results
        }
}
