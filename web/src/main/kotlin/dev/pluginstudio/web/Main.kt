package dev.pluginstudio.web

import com.google.gson.Gson
import dev.pluginstudio.analyzer.DomAnalyzer
import dev.pluginstudio.analyzer.SiteAnalyzer
import dev.pluginstudio.engine.ALL_FUNCTIONS
import dev.pluginstudio.engine.ActiveFilters
import dev.pluginstudio.engine.FILTER_FUNCTIONS
import dev.pluginstudio.engine.FunctionRunner
import dev.pluginstudio.engine.HttpLogEntry
import dev.pluginstudio.engine.LuaEngine
import dev.pluginstudio.engine.PluginLoadReport
import dev.pluginstudio.engine.PluginLoader
import dev.pluginstudio.engine.SourceInterface
import dev.pluginstudio.engine.models.Response
import dev.pluginstudio.gen.NetRecord
import dev.pluginstudio.pattern.PatternRegistry
import dev.pluginstudio.pattern.PluginPatternAnalyzer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class AnalyzeWebResponse(
    val url: String,
    val success: Boolean,
    val error: String? = null,
    val dom: Any? = null,
    val js: Any? = null,
    val durationMs: Long = 0,
    val viaArchive: Boolean = false,
    val archiveUrl: String? = null
)

data class LogEntry(val ok: Boolean, val message: String)

data class BookDto(val idx: Int, val title: String, val url: String, val coverImageUrl: String)

data class CatalogWebResponse(
    val ok: Boolean,
    val log: List<LogEntry>,
    val books: List<BookDto>,
    val selectors: List<String> = emptyList()
)

data class BookWebResponse(
    val ok: Boolean,
    val bookUrl: String,
    val log: List<LogEntry>,
    val title: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val chapters: List<Any> = emptyList(),
    val totalPages: Int? = null
)

data class ChapterWebResponse(
    val ok: Boolean,
    val log: List<LogEntry>,
    val text: String? = null
)

class WebStudio(
    private val gson: Gson = Gson(),
    sourcesDirOverride: Path? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /** Repo root (parent of the web module) — used for staging generated plugins. */
    private val repoRoot: Path = Path.of(System.getProperty("user.dir"))
    lateinit var generator: GeneratorService
    val aiSettings = AiSettingsStore(Path.of(System.getProperty("user.dir")).resolve(".pds-ai.json"))

    @Volatile
    var sourcesDir: Path = sourcesDirOverride
        ?: System.getenv("PLUGIN_SOURCES_DIR")?.let { Path.of(it) }
        ?: defaultPluginsDir()
        private set

    private val luaEngine = LuaEngine()
    private val siteAnalyzer = SiteAnalyzer()
    private val domAnalyzer = DomAnalyzer()
    private val patternAnalyzer = PluginPatternAnalyzer()

    @Volatile
    private var pluginLoader: PluginLoader = PluginLoader(luaEngine, sourcesDir)

    @Volatile
    private var cachedReport: PluginLoadReport? = null

    val httpLogs = CopyOnWriteArrayList<HttpLogEntry>()

    init {
        luaEngine.onHttpLog = { entry ->
            httpLogs.add(0, entry)
            while (httpLogs.size > 500) httpLogs.removeAt(httpLogs.size - 1)
            Unit
        }
        generator = GeneratorService(
            repoRoot = repoRoot,
            aiSettings = aiSettings,
            onPluginsChanged = { cachedReport = null }
        )
    }

    fun plugins(): PluginLoadReport = cachedReport ?: pluginLoader.loadAllPlugins().also { cachedReport = it }

    /** Mirrors desktop defaultPluginsDir(): prefer ../external-sources, ./external-sources, ../sources, ./sources */
    private fun defaultPluginsDir(): Path {
        val cwd = Path.of("").toAbsolutePath()
        val candidates = listOf(
            cwd.parent?.resolve("external-sources"),
            cwd.resolve("external-sources"),
            cwd.parent?.resolve("sources"),
            cwd.resolve("sources")
        )
        for (c in candidates) if (c != null && Files.isDirectory(c)) return c
        return (cwd.parent?.resolve("external-sources") ?: cwd.resolve("external-sources"))
    }

    fun setPluginDir(path: String): PluginLoadReport {
        sourcesDir = Path.of(path)
        pluginLoader = PluginLoader(luaEngine, sourcesDir)
        return pluginLoader.loadAllPlugins().also { cachedReport = it }
    }

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1"
    )

    private data class FetchResult(val ok: Boolean, val status: Int, val headers: Map<String, String>, val body: String)

    private suspend fun fetchDirect(url: String): FetchResult = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url)
            browserHeaders.forEach { (k, v) -> builder.header(k, v) }
            httpClient.newCall(builder.build()).execute().use { resp ->
                FetchResult(resp.isSuccessful, resp.code, resp.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" }, resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            FetchResult(false, 0, emptyMap(), e.message ?: e.toString())
        }
    }

    private fun looksLikeChallenge(r: FetchResult): Boolean =
        r.status == 403 || r.status == 503 ||
            r.headers.keys.any { it.equals("cf-mitigated", ignoreCase = true) } ||
            r.body.contains("Just a moment", ignoreCase = true) ||
            r.body.contains("challenges.cloudflare.com", ignoreCase = true) ||
            r.body.contains("Attention Required", ignoreCase = true)

    private suspend fun fetchUrl(url: String, timeoutSec: Long = 30L): String? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url)
            browserHeaders.forEach { (k, v) -> builder.header(k, v) }
            val client = httpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Latest Wayback Machine snapshot for a URL, or null. Rejects redirect-stub/parked captures. */
    private suspend fun waybackSnapshot(pageUrl: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(pageUrl, "UTF-8")
            val api = "https://archive.org/wayback/available?url=$encoded"
            @Suppress("UNCHECKED_CAST")
            val obj = gson.fromJson(fetchUrl(api, timeoutSec = 20) ?: return@withContext null, Map::class.java) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val closest = ((obj["archived_snapshots"] as? Map<String, Any>)?.get("closest") as? Map<String, Any>) ?: return@withContext null
            val snapUrl = (closest["url"] as? String)?.takeIf { it.isNotBlank() } ?: return@withContext null
            val httpsSnap = snapUrl.replace("http://web.archive.org", "https://web.archive.org")
            val html = fetchUrl(httpsSnap, timeoutSec = 45) ?: return@withContext null
            val isStub = (html.length < 15000 && html.contains("Redirecting...", ignoreCase = true)) ||
                html.contains("parklogic", ignoreCase = true)
            if (isStub) null else httpsSnap to html
        } catch (_: Exception) {
            null
        }
    }

    @Volatile
    private var lastSentAnalysis: AnalyzeWebResponse? = null

    fun analyzeHtmlDirect(pageUrl: String, html: String): AnalyzeWebResponse {
        val start = System.currentTimeMillis()
        return try {
            val dom = domAnalyzer.analyze(pageUrl, html)
            val js = siteAnalyzer.analyzeHtml(pageUrl, html, dom)
            AnalyzeWebResponse(pageUrl, true, dom = dom, js = js, durationMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            AnalyzeWebResponse(pageUrl, false, e.message ?: e.toString(), durationMs = System.currentTimeMillis() - start)
        }.also { lastSentAnalysis = it }
    }

    private suspend fun analyzeUrl(url: String): AnalyzeWebResponse {
        val start = System.currentTimeMillis()
        val direct = fetchDirect(url)
        var html: String? = null
        var viaArchive = false
        var archiveUrl: String? = null

        if (direct.ok && !looksLikeChallenge(direct)) {
            html = direct.body
        }
        if (html.isNullOrBlank()) {
            val wb = waybackSnapshot(url)
            if (wb != null && wb.second.isNotBlank()) {
                html = wb.second; viaArchive = true; archiveUrl = wb.first
            }
        }
        html = html?.takeIf { it.isNotBlank() }
            ?: return AnalyzeWebResponse(
                url, false,
                error = buildString {
                    append("Failed to download page")
                    if (direct.status in setOf(403, 503)) append(" — blocked by bot protection (HTTP ${direct.status}, likely Cloudflare challenge)")
                    append("; Wayback Machine fallback found no snapshot")
                },
                durationMs = System.currentTimeMillis() - start
            )
        return try {
            val dom = domAnalyzer.analyze(url, html)
            val js = siteAnalyzer.analyzeHtml(if (viaArchive) archiveUrl!! else url, html, dom)
            AnalyzeWebResponse(url, true, dom = dom, js = js, durationMs = System.currentTimeMillis() - start, viaArchive = viaArchive, archiveUrl = archiveUrl)
        } catch (e: Exception) {
            AnalyzeWebResponse(url, false, e.message ?: e.toString(), durationMs = System.currentTimeMillis() - start)
        }
    }

    private fun findAdapter(pluginId: String) = plugins().loaded.firstOrNull { it.id == pluginId }

    // ── Pipeline (mirrors desktop PipelinePanel) ──

    suspend fun pipelineFilters(pluginId: String): Any {
        val adapter = findAdapter(pluginId)
            ?: return mapOf("ok" to false, "error" to "Plugin not found: $pluginId")
        if (adapter !is SourceInterface.FilterableCatalog) return mapOf("ok" to true, "filters" to emptyList<Any>())
        return try {
            when (val resp = adapter.getFilterList()) {
                is dev.pluginstudio.engine.models.Response.Success<*> ->
                    mapOf("ok" to true, "filters" to (resp.data ?: emptyList<Any>()))
                is dev.pluginstudio.engine.models.Response.Error ->
                    mapOf("ok" to false, "error" to resp.message)
            }
        } catch (e: Exception) {
            mapOf("ok" to false, "error" to (e.message ?: e.toString()))
        }
    }

    suspend fun pipelineCatalog(pluginId: String, mode: String, query: String, filters: Map<String, List<String>>): Any {
        val adapter = findAdapter(pluginId)
            ?: return CatalogWebResponse(false, listOf(LogEntry(false, "plugin not found: $pluginId")), emptyList())
        val funcKey = when (mode) {
            "catalog" -> "getCatalogList"; "search" -> "getCatalogSearch"; "filtered" -> "getCatalogFiltered"
            else -> mode
        }
        val start = System.currentTimeMillis()
        return try {
            val resp = when (mode) {
                "catalog" -> adapter.getCatalogList(0)
                "search" -> adapter.getCatalogSearch(0, query)
                "filtered" -> (adapter as? SourceInterface.FilterableCatalog)
                    ?.getCatalogFiltered(0, ActiveFilters(filters))
                    ?: return CatalogWebResponse(false, listOf(LogEntry(false, "not a filterable catalog")), emptyList())
                else -> return CatalogWebResponse(false, listOf(LogEntry(false, "unknown mode: $mode")), emptyList())
            }
            val dur = System.currentTimeMillis() - start
            when (resp) {
                is dev.pluginstudio.engine.models.Response.Success<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = (resp.data as dev.pluginstudio.engine.models.PagedList<dev.pluginstudio.engine.models.BookResult>).list
                    val books = list.mapIndexed { i, br -> BookDto(i, br.title, br.url, br.coverImageUrl) }
                    CatalogWebResponse(true, listOf(LogEntry(true, "✓ $funcKey ($dur ms) — ${books.size} books")), books)
                }
                is dev.pluginstudio.engine.models.Response.Error ->
                    CatalogWebResponse(false, listOf(LogEntry(false, "✗ $funcKey ($dur ms) — ${resp.message}")), emptyList())
            }
        } catch (e: Exception) {
            CatalogWebResponse(false, listOf(LogEntry(false, "✗ $funcKey — ${e.message ?: e.toString()}")), emptyList())
        }
    }

    suspend fun pipelineBook(pluginId: String, bookUrl: String): Any {
        val adapter = findAdapter(pluginId)
            ?: return BookWebResponse(false, bookUrl, listOf(LogEntry(false, "plugin not found: $pluginId")))
        val logs = mutableListOf<LogEntry>()
        var title: String? = null; var cover: String? = null; var description: String? = null
        var genres: List<String> = emptyList(); var chapters: List<Any> = emptyList(); var totalPages: Int? = null

        suspend fun runFunc(name: String, block: suspend () -> Any?) {
            val start = System.currentTimeMillis()
            try {
                val resp = block() ?: run {
                    logs.add(LogEntry(false, "✗ $name — returned null")); return
                }
                val dur = System.currentTimeMillis() - start
                when (resp) {
                    is dev.pluginstudio.engine.models.Response.Error ->
                        logs.add(LogEntry(false, "✗ $name ($dur ms) — ${resp.message}"))
                    is dev.pluginstudio.engine.models.Response.Success<*> -> {
                        val data = resp.data
                        val msg = when (data) {
                            is String -> "\"${data.take(100)}\""
                            is List<*> -> "${data.size} items"
                            is SourceInterface.Catalog.PagedChapterResult -> "${data.chapters.size} chapters"
                            else -> data?.toString()?.take(100) ?: "null"
                        }
                        logs.add(LogEntry(true, "✓ $name ($dur ms) — $msg"))
                        when (name) {
                            "getBookTitle" -> title = data as? String
                            "getBookCoverImageUrl" -> cover = data as? String
                            "getBookDescription" -> description = data as? String
                            "getBookGenres" -> @Suppress("UNCHECKED_CAST") genres = (data as? List<String>) ?: emptyList()
                            "getChapterList" -> @Suppress("UNCHECKED_CAST") chapters = (data as? List<Any>) ?: emptyList()
                            "parsePage" -> {
                                val paged = data as? SourceInterface.Catalog.PagedChapterResult
                                chapters = paged?.chapters ?: emptyList()
                                totalPages = paged?.totalPages
                            }
                        }
                    }
                    else -> logs.add(LogEntry(true, "✓ $name ($dur ms) — ${resp.toString().take(100)}"))
                }
            } catch (e: Exception) {
                logs.add(LogEntry(false, "✗ $name — ${e.message ?: e.toString()}"))
            }
        }

        for (fn in listOf("getBookTitle", "getBookCoverImageUrl", "getBookDescription", "getBookGenres").filter { adapter.hasFunction(it) }) {
            runFunc(fn) {
                when (fn) {
                    "getBookTitle" -> adapter.getBookTitle(bookUrl)
                    "getBookCoverImageUrl" -> adapter.getBookCoverImageUrl(bookUrl)
                    "getBookDescription" -> adapter.getBookDescription(bookUrl)
                    "getBookGenres" -> adapter.getBookGenres(bookUrl)
                    else -> null
                }
            }
        }
        for (fn in listOf("getChapterList", "parsePage").filter { adapter.hasFunction(it) }) {
            runFunc(fn) {
                when (fn) {
                    "getChapterList" -> adapter.getChapterList(bookUrl)
                    "parsePage" -> adapter.parsePage(bookUrl, 0)
                    else -> null
                }
            }
        }
        val ok = logs.none { !it.ok }
        return BookWebResponse(ok, bookUrl, logs, title, cover, description, genres, chapters, totalPages)
    }

    suspend fun pipelineChapter(pluginId: String, chUrl: String): Any {
        val adapter = findAdapter(pluginId)
            ?: return ChapterWebResponse(false, listOf(LogEntry(false, "plugin not found: $pluginId")))
        val logs = mutableListOf<LogEntry>()
        return try {
            val result = luaEngine.httpGet(chUrl)
            if (!result.success) {
                logs.add(LogEntry(false, "✗ HTTP GET chapter (status=${result.statusCode})"))
                return ChapterWebResponse(false, logs)
            }
            logs.add(LogEntry(true, "✓ HTTP GET chapter (${result.body.length} bytes, status=${result.statusCode})"))
            val tStart = System.currentTimeMillis()
            val text = adapter.getChapterTextRaw(result.body, chUrl)
            logs.add(LogEntry(true, "✓ getChapterText (${System.currentTimeMillis() - tStart} ms) — ${text?.length ?: 0} chars"))
            ChapterWebResponse(text != null, logs, text)
        } catch (e: Exception) {
            logs.add(LogEntry(false, "✗ HTTP GET chapter — ${e.message ?: e.toString()}"))
            ChapterWebResponse(false, logs)
        }
    }

    fun module(): Application.() -> Unit = {
        routing {
            get("/") {
                val html = javaClass.classLoader.getResourceAsStream("static/index.html")?.readBytes()
                if (html != null) call.respondText(html.toString(Charsets.UTF_8), ContentType.Text.Html)
                else call.respondText("index.html missing", ContentType.Text.Plain, HttpStatusCode.NotFound)
            }
            get("/index.html") { call.respondRedirect("/") }

            get("/api/health") {
                call.respondJson(mapOf("status" to "ok", "app" to "plugin-dev-studio-web", "version" to "1.0.0"))
            }

            get("/api/functions") { call.respondJson(ALL_FUNCTIONS + FILTER_FUNCTIONS) }

            get("/api/plugins") {
                val report = plugins()
                call.respondJson(
                    mapOf(
                        "sourcesDir" to sourcesDir.toAbsolutePath().toString(),
                        "plugins" to report.loaded.map { a ->
                            val meta = a.getMetadata()
                            val available = ALL_FUNCTIONS.filter { a.hasFunction(it.name) }.map { it.name } +
                                (if (a is SourceInterface.FilterableCatalog)
                                    FILTER_FUNCTIONS.filter { a.hasFunction(it.name) }.map { it.name }
                                else emptyList())
                            mapOf(
                                "id" to a.id,
                                "name" to a.name,
                                "baseUrl" to a.baseUrl,
                                "version" to meta.version,
                                "description" to meta.description,
                                "charset" to meta.charset,
                                "language" to (a.language?.iso639_1 ?: ""),
                                "isFilterable" to (a is SourceInterface.FilterableCatalog),
                                "availableFunctions" to available,
                                "selectors" to a.selectors
                            )
                        },
                        "failed" to report.failed.map { mapOf("file" to it.file, "message" to it.message) }
                    )
                )
            }

            post("/api/plugins/dir") {
                val body = parseBody(call.receiveText())
                val path = body["path"]?.toString()?.trim().orEmpty()
                if (path.isBlank()) {
                    call.respondJson(mapOf("error" to "Missing 'path'"), HttpStatusCode.BadRequest)
                    return@post
                }
                if (!Files.isDirectory(Path.of(path))) {
                    call.respondJson(mapOf("error" to "Folder not found: $path"), HttpStatusCode.BadRequest)
                    return@post
                }
                val report = setPluginDir(path)
                call.respondJson(
                    mapOf(
                        "loaded" to report.loaded.size,
                        "failed" to report.failed.size,
                        "sourcesDir" to sourcesDir.toAbsolutePath().toString()
                    )
                )
            }

            post("/api/plugins/reload") {
                val report = setPluginDir(sourcesDir.toString())
                call.respondJson(
                    mapOf(
                        "loaded" to report.loaded.size,
                        "failed" to report.failed.size,
                        "sourcesDir" to sourcesDir.toAbsolutePath().toString()
                    )
                )
            }

            get("/api/patterns") {
                val report = plugins()
                call.respondJson(report.loaded.associate { a -> a.id to patternAnalyzer.analyze(a) })
            }

            get("/api/patterns/{pluginId}") {
                val id = call.parameters["pluginId"] ?: ""
                val adapter = findAdapter(id)
                if (adapter == null) {
                    call.respondJson(mapOf("error" to "Plugin not found: $id"), HttpStatusCode.NotFound)
                    return@get
                }
                call.respondJson(
                    mapOf(
                        "summary" to patternAnalyzer.analyze(adapter),
                        "matches" to patternAnalyzer.matchCluster(adapter, null),
                        "allClusters" to PatternRegistry.clusters
                    )
                )
            }

            post("/api/analyze") {
                val body = parseBody(call.receiveText())
                val url = body["url"]?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    call.respondJson(mapOf("error" to "Missing 'url'"), HttpStatusCode.BadRequest)
                } else {
                    call.respondJson(analyzeUrl(url))
                }
            }

            options("/api/analyze/html") {
                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.response.headers.append("Access-Control-Allow-Methods", "POST, OPTIONS")
                call.response.headers.append("Access-Control-Allow-Headers", "Content-Type")
                call.respondText("", ContentType.Text.Plain, HttpStatusCode.NoContent)
            }

            // Receives page HTML sent from the user's own browser (bookmarklet / paste),
            // for sites that block server-side fetching (e.g. Cloudflare challenge).
            post("/api/analyze/html") {
                call.response.headers.append("Access-Control-Allow-Origin", "*")
                val body = parseBody(call.receiveText())
                val html = body["html"]?.toString().orEmpty()
                val url = body["url"]?.toString()?.trim().orEmpty().ifBlank { "https://sent-from-browser" }
                if (html.isBlank()) {
                    call.respondJson(mapOf("error" to "Missing 'html'"), HttpStatusCode.BadRequest)
                } else {
                    call.respondJson(analyzeHtmlDirect(url, html))
                }
            }

            get("/api/analyze/last") {
                val last = lastSentAnalysis
                if (last == null) call.respondJson(mapOf("none" to true))
                else call.respondJson(last)
            }

            post("/api/run") {
                val body = parseBody(call.receiveText())
                val pluginId = body["pluginId"]?.toString()?.trim().orEmpty()
                val function = body["function"]?.toString()?.trim().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val args = (body["args"] as? Map<String, Any>)?.mapValues { it.value.toString() } ?: emptyMap()
                val adapter = findAdapter(pluginId)
                if (adapter == null || function.isBlank()) {
                    call.respondJson(mapOf("error" to "Plugin or function missing"), HttpStatusCode.BadRequest)
                } else {
                    call.respondJson(FunctionRunner(adapter).run(function, args))
                }
            }

            get("/api/pipeline/filters") {
                val pluginId = call.request.queryParameters["pluginId"] ?: ""
                call.respondJson(pipelineFilters(pluginId))
            }

            post("/api/pipeline/catalog") {
                val body = parseBody(call.receiveText())
                val pluginId = body["pluginId"]?.toString()?.trim().orEmpty()
                val mode = body["mode"]?.toString()?.trim().orEmpty().ifBlank { "catalog" }
                val query = body["query"]?.toString() ?: ""
                @Suppress("UNCHECKED_CAST")
                val filters = (body["filters"] as? Map<String, Any>)?.mapValues { entry ->
                    when (val v = entry.value) {
                        is List<*> -> v.map { it.toString() }
                        else -> listOf(v.toString())
                    }
                } ?: emptyMap()
                call.respondJson(pipelineCatalog(pluginId, mode, query, filters))
            }

            post("/api/pipeline/book") {
                val body = parseBody(call.receiveText())
                val pluginId = body["pluginId"]?.toString()?.trim().orEmpty()
                val bookUrl = body["bookUrl"]?.toString()?.trim().orEmpty()
                call.respondJson(pipelineBook(pluginId, bookUrl))
            }

            post("/api/pipeline/chapter") {
                val body = parseBody(call.receiveText())
                val pluginId = body["pluginId"]?.toString()?.trim().orEmpty()
                val url = body["url"]?.toString()?.trim().orEmpty()
                call.respondJson(pipelineChapter(pluginId, url))
            }

            get("/api/network/logs") { call.respondJson(httpLogs.toList()) }

            post("/api/network/clear") {
                httpLogs.clear()
                call.respondJson(mapOf("cleared" to true))
            }

            // ── Analyze & Generate Plugin ──

            post("/api/generator/start") {
                val body = parseBody(call.receiveText())
                val url = body["url"]?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    call.respondJson(mapOf("error" to "Missing 'url'"), HttpStatusCode.BadRequest)
                    return@post
                }
                val query = body["query"]?.toString()?.trim()?.ifBlank { null } ?: "novel"
                val idHint = body["id"]?.toString()?.trim()?.ifBlank { null }
                val nameHint = body["name"]?.toString()?.trim()?.ifBlank { null }
                val useCapture = body["useCapture"]?.toString() in listOf("true", "1")
                val providerId = body["providerId"]?.toString()?.trim()?.ifBlank { null }
                val model = body["model"]?.toString()?.trim()?.ifBlank { null }
                val job = generator.startJob(StartParams(
                    url = url, query = query, idHint = idHint, nameHint = nameHint,
                    useCapture = useCapture, providerId = providerId, model = model
                ))
                call.respondJson(mapOf("jobId" to job.id))
            }

            get("/api/generator/status/{id}") {
                val job = generator.jobs[call.parameters["id"] ?: ""]
                if (job == null) {
                    call.respondJson(mapOf("error" to "unknown job"), HttpStatusCode.NotFound)
                    return@get
                }
                call.respondJson(mapOf(
                    "phase" to job.phase,
                    "steps" to synchronized(job.steps) { job.steps.toList() },
                    "result" to job.result,
                    "error" to job.error,
                    "elapsedMs" to job.elapsedMs(),
                    "aiModel" to job.aiModel,
                    "attempts" to synchronized(job.attempts) { job.attempts.toList() }
                ))
            }

            // ── interactive AI repair chat ──

            post("/api/generator/chat") {
                val body = parseBody(call.receiveText())
                val jobId = body["jobId"]?.toString()?.trim().orEmpty()
                val message = body["message"]?.toString()?.trim().orEmpty()
                if (jobId.isBlank() || message.isBlank()) {
                    call.respondJson(mapOf("error" to "Missing 'jobId' or 'message'"), HttpStatusCode.BadRequest)
                    return@post
                }
                val model = body["model"]?.toString()?.trim()?.ifBlank { null }
                call.respondJson(generator.chat(jobId, message, model))
            }

            post("/api/generator/analyze-page") {
                val body = parseBody(call.receiveText())
                val jobId = body["jobId"]?.toString()?.trim().orEmpty()
                val url = body["url"]?.toString()?.trim().orEmpty()
                if (jobId.isBlank() || url.isBlank()) {
                    call.respondJson(mapOf("error" to "Missing 'jobId' or 'url'"), HttpStatusCode.BadRequest)
                    return@post
                }
                call.respondJson(generator.analyzeExtraPage(jobId, url))
            }

            // ── AI providers ──

            get("/api/ai/providers") {
                call.respondJson(mapOf(
                    "default" to aiSettings.defaultProviderId,
                    "providers" to aiSettings.list().map { it.publicView() }
                ))
            }

            post("/api/ai/providers") {
                val body = parseBody(call.receiveText())
                val id = body["id"]?.toString()?.trim().orEmpty()
                    .ifBlank { "custom_" + System.currentTimeMillis() }
                val name = body["name"]?.toString()?.trim()?.ifBlank { null } ?: id
                val baseUrl = body["baseUrl"]?.toString()?.trim().orEmpty().trimEnd('/')
                if (!baseUrl.startsWith("http")) {
                    call.respondJson(mapOf("error" to "Invalid baseUrl"), HttpStatusCode.BadRequest)
                    return@post
                }
                val mode = if (body["mode"]?.toString() == "responses") "responses" else "chat"
                @Suppress("UNCHECKED_CAST")
                val extraHeaders: Map<String, String> =
                    (body["headers"] as? Map<String, Any>)?.entries?.associate { it.key to it.value.toString() }
                        ?: emptyMap()
                val saved = aiSettings.upsert(dev.pluginstudio.gen.ai.ProviderConfig(
                    id = id, name = name, baseUrl = baseUrl,
                    apiKey = body["apiKey"]?.toString() ?: "",
                    mode = mode,
                    defaultModel = body["defaultModel"]?.toString()?.trim() ?: "",
                    maxTokens = (body["maxTokens"] as? Number)?.toInt() ?: 8192,
                    headers = extraHeaders
                ))
                call.respondJson(mapOf("ok" to true, "provider" to saved.publicView()))
            }

            delete("/api/ai/providers/{id}") {
                val removed = aiSettings.delete(call.parameters["id"] ?: "")
                call.respondJson(mapOf("ok" to removed))
            }

            get("/api/ai/models") {
                val providerId = call.request.queryParameters["provider"] ?: aiSettings.defaultProviderId
                val provider = aiSettings.get(providerId)
                if (provider == null) {
                    call.respondJson(mapOf("error" to "unknown provider"), HttpStatusCode.NotFound)
                    return@get
                }
                val (models, err) = dev.pluginstudio.gen.ai.AiClient().listModels(provider)
                if (err != null) call.respondJson(mapOf("models" to models, "warning" to err))
                else call.respondJson(mapOf("models" to models))
            }

            post("/api/ai/test") {
                val body = parseBody(call.receiveText())
                val providerId = body["providerId"]?.toString()?.trim()
                val model = body["model"]?.toString()?.trim()?.ifBlank { null }
                val provider = aiSettings.get(providerId ?: "") ?: aiSettings.default()
                if (provider == null) {
                    call.respondJson(mapOf("ok" to false, "error" to "no provider configured"))
                    return@post
                }
                val m = model ?: dev.pluginstudio.gen.ai.AiClient().listModels(provider).first.firstOrNull() ?: ""
                if (m.isBlank()) { call.respondJson(mapOf("ok" to false, "error" to "no model")); return@post }
                val r = withTimeoutOrNull(60_000) {
                    dev.pluginstudio.gen.ai.AiClient().complete(
                        provider, m,
                        "You are a test endpoint. Reply with exactly: OK",
                        "Reply with exactly: OK", maxTokens = 16
                    )
                }
                when (r) {
                    is dev.pluginstudio.gen.ai.AiClient.Result.Ok ->
                        call.respondJson(mapOf("ok" to true, "reply" to r.text.take(40), "model" to m))
                    is dev.pluginstudio.gen.ai.AiClient.Result.Fail ->
                        call.respondJson(mapOf("ok" to false, "error" to r.error.take(200), "model" to m))
                    null -> call.respondJson(mapOf("ok" to false, "error" to "test timed out"))
                }
            }

            post("/api/generator/stage") {
                val body = parseBody(call.receiveText())
                val jobId = body["jobId"]?.toString()?.trim().orEmpty()
                val path = generator.stage(jobId)
                if (path != null) call.respondJson(mapOf("ok" to true, "stagedFile" to path))
                else call.respondJson(mapOf("ok" to false, "error" to "nothing to stage"), HttpStatusCode.BadRequest)
            }

            post("/api/generator/save") {
                val body = parseBody(call.receiveText())
                val jobId = body["jobId"]?.toString()?.trim().orEmpty()
                call.respondJson(generator.saveToLibrary(jobId, sourcesDir.toAbsolutePath()))
            }

            // ── Built-in deterministic demo site (acceptance testing target) ──

            get("/demo") { call.respondRedirect("/demo/catalog", permanent = false) }
            get("/demo/catalog") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val q = call.request.queryParameters["q"]
                call.respondText(demoCatalogHtml(page, q), ContentType.Text.Html)
            }
            get("/demo/search") {
                val q = call.request.queryParameters["q"].orEmpty()
                call.respondText(demoCatalogHtml(1, q), ContentType.Text.Html)
            }
            get("/demo/book/{id}") {
                call.respondText(demoBookHtml(call.parameters["id"]?.toIntOrNull() ?: 1), ContentType.Text.Html)
            }
            get("/demo/book/{id}/chapter/{n}") {
                call.respondText(demoChapterHtml(call.parameters["id"]?.toIntOrNull() ?: 1, call.parameters["n"]?.toIntOrNull() ?: 1), ContentType.Text.Html)
            }
            get("/demo/covers/{id}.png") {
                val id = call.parameters["id"] ?: "1"
                val svg = """<svg xmlns='http://www.w3.org/2000/svg' width='120' height='160'><rect width='120' height='160' fill='#4a4458'/><text x='60' y='85' font-size='40' fill='#eaddff' text-anchor='middle'>B$id</text></svg>"""
                call.respondText(svg, ContentType.Image.SVG)
            }

            // ── Browser capture (protected sites) ──

            options("/api/capture/page") {
                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.response.headers.append("Access-Control-Allow-Methods", "POST, OPTIONS")
                call.response.headers.append("Access-Control-Allow-Headers", "Content-Type")
                call.respondText("", ContentType.Text.Plain, HttpStatusCode.NoContent)
            }

            post("/api/capture/page") {
                call.response.headers.append("Access-Control-Allow-Origin", "*")
                val body = parseBody(call.receiveText())
                val url = body["url"]?.toString()?.trim().orEmpty()
                val html = body["html"]?.toString().orEmpty()
                if (url.isBlank() || html.isBlank()) {
                    call.respondJson(mapOf("success" to false, "error" to "Missing 'url' or 'html'"), HttpStatusCode.BadRequest)
                    return@post
                }
                @Suppress("UNCHECKED_CAST")
                val reqs = (body["requests"] as? List<Any>)?.mapNotNull { r ->
                    (r as? Map<String, Any>)?.let { m ->
                        NetRecord(
                            method = m["method"]?.toString() ?: "GET",
                            url = m["url"]?.toString() ?: "",
                            status = (m["status"] as? Number)?.toInt() ?: 0,
                            contentType = m["contentType"]?.toString() ?: "",
                            bodySample = ""
                        )
                    }
                }.orEmpty()
                val count = generator.capturePage(url, html, reqs)
                call.respondJson(mapOf("success" to true, "pagesReceived" to count))
            }

            get("/api/capture/list") { call.respondJson(generator.listCaptured()) }

            post("/api/capture/clear") {
                generator.clearCaptured()
                call.respondJson(mapOf("cleared" to true))
            }
        }
    }

    private fun parseBody(text: String): Map<String, Any> {
        if (text.isBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(text, Map::class.java) as Map<String, Any>
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
        payload: Any?,
        status: HttpStatusCode = HttpStatusCode.OK
    ) {
        respondText(gson.toJson(payload), ContentType.Application.Json, status)
    }
}

// ── Demo site content (deterministic, for end-to-end testing) ──

private val DEMO_TITLES = listOf(
    "Demo Novel One", "Demo Novel Two", "Demo Novel Three",
    "Demo Novel Four", "Demo Novel Five", "Demo Martial Tale"
)

private fun demoEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

private fun demoCard(i: Int): String {
    val title = DEMO_TITLES[i - 1]
    return """
    <div class="book-card">
        <a class="book-title" href="/demo/book/$i">$title</a>
        <img class="cover" src="/demo/covers/$i.png" alt="cover">
        <p class="snippet">Book number $i of the demo collection used by Plugin Dev Studio acceptance tests.</p>
    </div>""".trimIndent()
}

private fun demoCatalogHtml(page: Int, q: String?): String {
    val ids = (1..6).filter { q.isNullOrBlank() || DEMO_TITLES[it - 1].contains(q, ignoreCase = true) || "demo".contains(q, ignoreCase = true) }
    val cards = if (ids.isEmpty())
        "<p>No results found.</p>"
    else ids.joinToString("\n") { demoCard(it) }
    return """
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Demo Novel Site${if (q != null) " — search: $q" else ""}</title></head>
<body>
<header><h1>Demo Novel Site</h1>
<form action="/demo/search" method="get"><input type="text" name="q" placeholder="search novels"><button type="submit">Go</button></form>
</header>
<main class="catalog">
$cards
</main>
<nav class="pagination">
<a href="/demo/catalog?page=${(page - 1).coerceAtLeast(1)}">Prev</a>
<span>Page $page</span>
<a href="/demo/catalog?page=${page + 1}">Next</a>
</nav>
</body></html>"""
}

private fun demoBookHtml(id: Int): String {
    val title = DEMO_TITLES.getOrElse(id - 1) { "Demo Novel" }
    val chapters = (1..8).joinToString("\n") { n ->
        """<li><a href="/demo/book/$id/chapter/$n">Chapter $n: The Demo Continues</a></li>"""
    }
    return """
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<title>$title — Demo Novel Site</title>
<meta property="og:image" content="/demo/covers/$id.png">
<meta property="og:description" content="$title is a deterministic demo novel used to validate generated plugins end to end.">
</head>
<body>
<article class="book-page">
<h1 class="fiction-title">$title</h1>
<img class="novel-cover" src="/demo/covers/$id.png" alt="cover">
<div class="summary"><p class="description">$title is a deterministic demo novel written for the Plugin Dev Studio acceptance test. It contains enough text to satisfy description extraction, and its genres and chapter list are stable across runs.</p></div>
<ul class="genres">
<li><a href="/genre/fantasy">Fantasy</a></li>
<li><a href="/genre/adventure">Adventure</a></li>
<li><a href="/genre/demo">Demo</a></li>
</ul>
<h2>Chapters</h2>
<ul class="chapter-list">
$chapters
</ul>
</article>
</body></html>"""
}

private fun demoChapterHtml(id: Int, n: Int): String {
    val title = DEMO_TITLES.getOrElse(id - 1) { "Demo Novel" }
    val paras = (1..7).joinToString("\n") { p ->
        "<p>This is paragraph $p of chapter $n in $title. The demo generator fills each paragraph with enough prose so that content extraction validation can require a meaningful length before declaring success. Every run produces identical bytes on purpose.</p>"
    }
    return """
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Chapter $n — $title</title></head>
<body>
<article>
<h1>Chapter $n: The Demo Continues</h1>
<div id="chapter-content">
$paras
<p>Previous chapter: <a href="/demo/book/$id/chapter/${(n - 1).coerceAtLeast(1)}">here</a>.</p>
</div>
</article>
</body></html>"""
}

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull()
        ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val studio = WebStudio()
    println("Plugin Dev Studio web UI starting at http://localhost:$port/")
    println("Lua sources dir: ${studio.sourcesDir.toAbsolutePath()}")
    embeddedServer(CIO, port = port, host = host) { studio.module()(this) }.start(wait = true)
}
