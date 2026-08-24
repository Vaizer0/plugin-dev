package dev.pluginstudio.gen

import dev.pluginstudio.engine.LuaEngine
import dev.pluginstudio.engine.PluginLoader
import dev.pluginstudio.engine.models.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Proves a generated plugin actually works: syntax-load it through the real
 * Lua engine, then live-run each function against the target site and record
 * a pass/fail matrix. This is what makes generation trustworthy.
 */
class PluginValidator(
    private val fetcher: HttpFetcher = HttpFetcher(
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    )
) {
    data class Report(val entries: List<ValidationEntry>, val pass: Boolean) {
        val catalogOk get() = entries.firstOrNull { it.function == "getCatalogList" }?.ok == true
        val searchOk get() = entries.firstOrNull { it.function == "getCatalogSearch" }?.ok == true
        val chaptersOk get() = (entries.firstOrNull { it.function == "getChapterList" } ?: entries.firstOrNull { it.function == "parsePage" })?.ok == true
        val textOk get() = entries.firstOrNull { it.function == "getChapterText" }?.ok == true
    }

    /** Load + run. Returns null if the script fails to even load (syntax error). */
    suspend fun validate(luaCode: String, testQuery: String, onStep: (String) -> Unit = {}): Pair<Throwable?, Report?> =
        withContext(Dispatchers.IO) {
            val tmpDir: Path = Files.createTempDirectory("pds-validate")
            try {
                Files.writeString(tmpDir.resolve("generated.lua"), luaCode)
                val engine = LuaEngine()
                val loader = PluginLoader(engine, tmpDir)
                val report = loader.loadAllPlugins()
                if (report.failed.isNotEmpty()) {
                    return@withContext RuntimeException(report.failed.first().message) to null
                }
                val adapter = report.loaded.firstOrNull()
                    ?: return@withContext RuntimeException("Plugin loaded but no adapter produced") to null

                val entries = mutableListOf<ValidationEntry>()
                var firstBookUrl: String? = null
                var firstChapterUrl: String? = null

                suspend fun step(name: String, block: suspend () -> Pair<Boolean, String>) {
                    if (!adapter.hasFunction(name)) {
                        entries.add(ValidationEntry(name, false, "function not generated", 0))
                        return
                    }
                    onStep("Validating $name …")
                    val t0 = System.currentTimeMillis()
                    val (ok, detail) = try {
                        withTimeout(12_000) { block() }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        false to "timed out after 12s"
                    } catch (e: Exception) { false to (e.message ?: e.toString()) }
                    synchronized(entries) {
                        entries.add(ValidationEntry(name, ok, detail, System.currentTimeMillis() - t0))
                    }
                    onStep("${if (ok) "✓" else "✗"} $name — $detail")
                }

                // 1. catalog (gate for everything else)
                step("getCatalogList") {
                    when (val r = adapter.getCatalogList(0)) {
                        is Response.Error -> false to r.message
                        is Response.Success<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = (r.data as dev.pluginstudio.engine.models.PagedList<dev.pluginstudio.engine.models.BookResult>).list
                            firstBookUrl = list.firstOrNull()?.url
                            (list.isNotEmpty()) to "${list.size} items"
                        }
                    }
                }
                if (!firstBookUrl.isNullOrBlank()) {
                    // Book-page functions share the plugin's internal page cache,
                    // so they are cheap once the first one warms it. Chapter-text
                    // and search hit different URLs → run them concurrently.
                    var metaHits = 0
                    step("getBookTitle") {
                        val r = adapter.getBookTitle(firstBookUrl!!)
                        val s = (r as? Response.Success<*>)?.data as? String
                        if (!s.isNullOrBlank()) metaHits++
                        (r !is Response.Error && !s.isNullOrBlank()) to (s?.take(60) ?: "null/empty")
                    }
                    step("getBookCoverImageUrl") {
                        val r = adapter.getBookCoverImageUrl(firstBookUrl!!)
                        val s = (r as? Response.Success<*>)?.data as? String
                        if (!s.isNullOrBlank()) metaHits++
                        (r !is Response.Error && !s.isNullOrBlank()) to (s?.take(60) ?: "null/empty")
                    }
                    step("getBookDescription") {
                        val r = adapter.getBookDescription(firstBookUrl!!)
                        val s = (r as? Response.Success<*>)?.data as? String
                        if ((s?.length ?: 0) > 50) metaHits++
                        (r !is Response.Error && (s?.length ?: 0) > 50) to "${s?.length ?: 0} chars"
                    }
                    step("getBookGenres") {
                        val r = adapter.getBookGenres(firstBookUrl!!)
                        @Suppress("UNCHECKED_CAST")
                        val l = ((r as? Response.Success<*>)?.data as? List<String>).orEmpty()
                        if (l.isNotEmpty()) metaHits++
                        (r !is Response.Error && l.isNotEmpty()) to "${l.size} genres"
                    }
                    entries.add(ValidationEntry("bookMetadata", metaHits >= 2, "$metaHits/4 fields", 0))

                    step("getChapterList") {
                        val r = adapter.getChapterList(firstBookUrl!!)
                        @Suppress("UNCHECKED_CAST")
                        val l = ((r as? Response.Success<*>)?.data as? List<dev.pluginstudio.engine.models.ChapterResult>).orEmpty()
                        firstChapterUrl = l.firstOrNull()?.url
                        (r !is Response.Error && l.isNotEmpty()) to "${l.size} chapters"
                    }

                    // chapter text and search hit different URLs → parallel
                    coroutineScope {
                        val textJob = async {
                            if (firstChapterUrl.isNullOrBlank() && !adapter.hasFunction("getChapterText")) return@async
                            step("getChapterText") {
                                val chUrl = firstChapterUrl ?: firstBookUrl!!
                                var http = fetcher.get(chUrl, timeoutSec = 15)
                                if (!http.ok) http = fetcher.get(chUrl, timeoutSec = 15)
                                if (!http.ok || http.blocked)
                                    return@step false to "HTTP ${http.status} fetching chapter (${http.body.take(60)}) url=$chUrl"
                                val text = adapter.getChapterTextRaw(http.body, chUrl)
                                val len = text?.trim()?.length ?: 0
                                (len > 200) to "$len chars extracted"
                            }
                        }
                        val searchJob = async {
                            if (!adapter.hasFunction("getCatalogSearch")) return@async
                            step("getCatalogSearch") {
                                when (val r = adapter.getCatalogSearch(0, testQuery)) {
                                    is Response.Error -> false to r.message
                                    is Response.Success<*> -> {
                                        @Suppress("UNCHECKED_CAST")
                                        val list = (r.data as dev.pluginstudio.engine.models.PagedList<dev.pluginstudio.engine.models.BookResult>).list
                                        (list.isNotEmpty()) to "${list.size} results for \"$testQuery\""
                                    }
                                }
                            }
                        }
                        textJob.await(); searchJob.await()
                    }
                }

                val coreOk = entries.firstOrNull { it.function == "getCatalogList" }?.ok == true &&
                    (entries.firstOrNull { it.function == "getChapterList" }?.ok == true ||
                        entries.firstOrNull { it.function == "parsePage" }?.ok == true) &&
                    entries.firstOrNull { it.function == "getChapterText" }?.ok == true

                null to Report(entries, coreOk)
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }
}
