package dev.pluginstudio.web

import com.google.gson.Gson
import dev.pluginstudio.gen.ApiProber
import dev.pluginstudio.gen.GenerationResult
import dev.pluginstudio.gen.NetRecord
import dev.pluginstudio.gen.PluginValidator
import dev.pluginstudio.gen.SiteCrawler
import dev.pluginstudio.gen.ai.AiClient
import dev.pluginstudio.gen.ai.AiPluginGenerator
import dev.pluginstudio.gen.ai.EvidenceBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class AttemptInfo(
    val n: Int,
    val source: String,
    val ok: Boolean,
    val summary: String,
    val luaBytes: Int
)

class GenJob(val id: String, val url: String, val query: String) {
    @Volatile var phase: String = "queued"
    val steps: MutableList<Step> = java.util.Collections.synchronizedList(mutableListOf<Step>())
    @Volatile var result: GenerationResult? = null
    @Volatile var error: String? = null
    private val startNanos = System.nanoTime()
    val startedAt = System.currentTimeMillis()
    val attempts = java.util.Collections.synchronizedList(mutableListOf<AttemptInfo>())
    @Volatile var aiModel: String = ""

    // Transient AI-session state for the interactive repair chat
    @Volatile var corpusRef: SiteCrawler.CrawlResult? = null
    @Volatile var evidenceJson: String = ""
    @Volatile var providerIdUsed: String = ""
    val chatHistory = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    fun elapsedMs(): Long = (System.nanoTime() - startNanos) / 1_000_000

    data class Step(val ts: Long, val msg: String)

    fun step(msg: String) { steps.add(Step(System.currentTimeMillis(), msg)) }
}

data class StartParams(
    val url: String,
    val query: String,
    val idHint: String?,
    val nameHint: String?,
    val useCapture: Boolean,
    val providerId: String?,
    val model: String?
)

/**
 * Pipeline: deterministic analysis (multi-page crawl + live API probing) →
 * structured evidence → AI Lua generation → real-engine validation → AI repair
 * loop (≤3) → interactive user chat for further fixes.
 */
class GeneratorService(
    private val repoRoot: Path,
    private val aiSettings: AiSettingsStore,
    private val onPluginsChanged: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val jobs = ConcurrentHashMap<String, GenJob>()
    private var jobCounter = 0

    // ── browser capture session ──

    data class CapturedPage(val url: String, val html: String, val requests: List<NetRecord>, val receivedAt: Long)

    private val capturedPages = java.util.Collections.synchronizedList(mutableListOf<CapturedPage>())

    fun capturePage(url: String, html: String, requests: List<NetRecord>): Int {
        synchronized(capturedPages) {
            capturedPages.removeAll { it.url == url }
            capturedPages.add(CapturedPage(url, html, requests, System.currentTimeMillis()))
        }
        return capturedPages.size
    }

    fun listCaptured(): List<Map<String, Any>> = synchronized(capturedPages) {
        capturedPages.map {
            mapOf("url" to it.url, "bytes" to it.html.length,
                "requests" to it.requests.size, "receivedAt" to it.receivedAt)
        }
    }

    fun clearCaptured() { synchronized(capturedPages) { capturedPages.clear() } }

    @Synchronized
    fun startJob(p: StartParams): GenJob {
        jobCounter++
        val job = GenJob("gen-$jobCounter-${System.currentTimeMillis()}", p.url, p.query)
        jobs[job.id] = job
        scope.launch {
            try { runPipeline(job, p) }
            catch (e: kotlinx.coroutines.CancellationException) { job.phase = "error"; job.error = "cancelled" }
            catch (e: Exception) { job.error = e.message ?: e.toString(); job.phase = "error" }
        }
        return job
    }

    private suspend fun runPipeline(job: GenJob, p: StartParams) {
        // ── 0. provider/model ──
        val provider = aiSettings.get(p.providerId ?: "") ?: aiSettings.default()
        if (provider == null) {
            finishError(job, "No AI provider configured — open ⚙ AI Settings and add one.")
            return
        }
        val model = p.model?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel.takeIf { it.isNotBlank() }
            ?: run {
                finishError(job, "No model set for ${provider.name} — open ⚙ AI Settings and set its model.")
                return
            }
        job.aiModel = "${provider.id}/$model"

        // ── 1. analysis: multi-page corpus ──
        job.phase = "crawl"
        job.step("Analyzing ${p.url} …")
        val crawler = SiteCrawler()
        val captureInput = if (p.useCapture && capturedPages.isNotEmpty()) {
            synchronized(capturedPages) { capturedPages.map { Triple(it.url, it.html, it.requests) } }
        } else null

        val corpus = withTimeoutOrNull(60_000) {
            if (captureInput != null) {
                job.step("Using ${captureInput.size} browser-captured page(s)")
                crawler.corpusFromCapture(captureInput)
            } else crawler.crawl(p.url, p.query) { msg ->
                if (msg.startsWith("Probing") || msg.startsWith("Testing")) job.phase = "probe"
                job.step(msg)
            }
        } ?: run { finishError(job, "Analysis exceeded its 60s budget — site too slow; try the capture flow"); return }

        if (!corpus.success) {
            finishError(job, (corpus.error ?: "analysis failed") +
                if (corpus.blocked) " — use the protected-site capture flow below." else "")
            return
        }

        // ── 2. parallel live API probing ──
        job.phase = "probe"
        val prober = ApiProber()
        val candidates = prober.candidatesFromPages(corpus.pages)
        val apis = if (candidates.isEmpty()) emptyList()
        else withTimeoutOrNull(20_000) {
            prober.probe(corpus.baseUrl, candidates, p.query) { job.step(it) }
        } ?: emptyList()
        job.step(if (apis.isEmpty()) "No verified JSON APIs — evidence will guide HTML scraping"
                 else "${apis.size} verified JSON API(s)")

        // ── 3. structured evidence package ──
        job.phase = "evidence"
        val evidenceJson = EvidenceBuilder.toJson(EvidenceBuilder.build(corpus, apis, p.query))
        job.step("Evidence package ready (${evidenceJson.length} bytes, ${corpus.pages.size} pages)")

        // keep session state for the interactive chat window
        job.corpusRef = corpus
        job.evidenceJson = evidenceJson
        job.providerIdUsed = provider.id

        // ── 4. AI generate → validate → repair (≤3) ──
        val aiGen = AiPluginGenerator(AiClient())
        val validator = PluginValidator()
        val probeUrl = corpus.pages.firstOrNull { it.kind == "book" }?.url ?: corpus.baseUrl
        var prevLua: String? = null
        var prevFailures: List<String> = emptyList()
        var attemptNo = 0
        var best: GenerationResult? = null

        while (attemptNo < 3) {
            attemptNo++
            job.phase = "ai-generate"
            job.step("AI attempt $attemptNo/3 via $model …")
            val outcome = withTimeoutOrNull(180_000) {
                aiGen.generate(provider, model, AiPluginGenerator.GenerationCall(evidenceJson, prevLua, prevFailures))
            }
            if (outcome == null) {
                job.attempts.add(AttemptInfo(attemptNo, "AI:$model", false, "AI call timed out after 180s", 0))
                job.step("✗ AI call timed out after 180s")
                continue
            }
            if (!outcome.first.ok) {
                job.attempts.add(AttemptInfo(attemptNo, "AI:$model", false, outcome.first.message, 0))
                job.step("✗ ${outcome.first.message.take(160)}")
                continue
            }
            val lua = outcome.second

            job.phase = "ai-validate"
            job.step("Validating AI Lua against the live site (${lua.length} bytes) …")
            val report = withTimeoutOrNull(90_000) {
                validator.validate(lua, p.query, { job.step(it) }, probeUrl)
            }?.second
            if (report == null) {
                job.attempts.add(AttemptInfo(attemptNo, "AI:$model", false, "validation timed out / load error", lua.length))
                job.step("✗ validation failed — requesting repair")
                prevLua = lua
                prevFailures = listOf("Lua failed to load through the real engine or validation timed out")
                continue
            }

            val fails = report.entries.filter { !it.ok }.map { "${it.function}: ${it.detail}" }
            val passCount = report.entries.count { it.ok }
            job.attempts.add(AttemptInfo(attemptNo, "AI:$model", report.pass,
                "$passCount/${report.entries.size} functions pass", lua.length))

            val res = GenerationResult(
                success = report.pass,
                siteUrl = p.url,
                blueprint = null,
                strategySummary = mapOf("source" to "AI:$model"),
                validation = report.entries,
                overallPass = report.pass,
                luaCode = lua,
                variantsTried = attemptNo
            )
            if (report.pass) {
                job.result = res; job.phase = "done"
                job.step("✓ AI plugin PASSED live validation (attempt $attemptNo)")
                return
            }
            if (best == null || res.validation.count { it.ok } > best.validation.count { it.ok }) best = res
            prevLua = lua
            prevFailures = fails
            job.step("✗ attempt $attemptNo incomplete — sending failures back for repair")
        }

        if (best != null) {
            job.result = best
            job.phase = "done"
            job.error = null
            job.step("Stopped after repair loop — best attempt kept (see validation matrix / chat to fix further).")
        } else {
            finishError(job, "AI could not produce a usable plugin from the available evidence.")
        }
    }

    private fun finishError(job: GenJob, msg: String) {
        job.error = msg
        job.phase = "error"
        job.step("✗ $msg")
    }

    // ── interactive AI repair chat ──

    data class ChatReply(
        val reply: String,
        val rebuilt: Boolean,
        val pass: Boolean?,
        val validation: List<Map<String, Any?>>?
    )

    /**
     * User sends feedback/questions about the current plugin. Model sees
     * evidence + current Lua + validation results + conversation history.
     * If the reply contains Lua it is re-validated live and becomes the new plugin.
     */
    suspend fun chat(jobId: String, userMessage: String, requestedModel: String?): ChatReply {
        val job = jobs[jobId] ?: return ChatReply("Unknown job.", false, null, null)
        val provider = aiSettings.get(job.providerIdUsed.ifBlank { aiSettings.defaultProviderId })
            ?: aiSettings.default()
            ?: return ChatReply("No AI provider configured.", false, null, null)
        val model = (requestedModel?.takeIf { it.isNotBlank() } ?: provider.defaultModel)
            .ifBlank { job.aiModel.substringAfter('/').takeIf { it.isNotBlank() } }
            ?: return ChatReply("No model configured for this session.", false, null, null)

        val result = job.result
        val fails = (result?.validation ?: emptyList()).filter { !it.ok }.map { "${it.function}: ${it.detail}" }

        val turns = mutableListOf<Pair<String, String>>()
        val context = buildString {
            appendLine("SITE EVIDENCE (JSON):")
            appendLine(job.evidenceJson)
            if (!result?.luaCode.isNullOrBlank()) {
                appendLine()
                appendLine("CURRENT GENERATED LUA:")
                appendLine("```lua")
                appendLine(result!!.luaCode)
                appendLine("```")
                appendLine("CURRENT LIVE VALIDATION:")
                if (fails.isEmpty()) appendLine("All supported functions passed.")
                else fails.forEach { appendLine(" - $it") }
            }
        }
        turns.add("user" to context)
        synchronized(job.chatHistory) { job.chatHistory.forEach { turns.add(it) } }
        turns.add("user" to userMessage)

        job.phase = "ai-generate"
        job.step("💬 chat: ${userMessage.take(80)}")
        val sys = AiPluginGenerator.systemPrompt() + """

You are now in INTERACTIVE REPAIR CHAT mode for a plugin you generated.
The human gives feedback ("chapter list uses the wrong container", "search must
POST", "also parse genres") or asks questions.

When fixing: re-derive selectors/URLs from EVIDENCE only. chapterLinkGroups in
the evidence lists REAL chapter URLs of this novel — the correct chapter-list
selector must produce exactly those links. Reply with a short explanation AND
the FULL corrected Lua in one ```lua block.
When only asked a question, answer briefly WITHOUT a lua block.
Never invent endpoints/selectors not present in evidence.""".trimIndent()

        val messages = listOf(mapOf("role" to "system", "content" to sys)) +
            turns.map { mapOf("role" to it.first, "content" to it.second) }

        job.phase = "ai-validate"
        val outcome = withTimeoutOrNull(180_000) {
            chatOnce(provider, model, messages, maxOf(provider.maxTokens, 12_000))
        } ?: AiClient.Result.Fail("chat timed out after 180s", 0)

        val reply: ChatReply = when (outcome) {
            is AiClient.Result.Fail -> {
                synchronized(job.chatHistory) {
                    job.chatHistory.add("user" to userMessage)
                    job.chatHistory.add("assistant" to "Error: ${outcome.error.take(300)}")
                }
                ChatReply("AI error: ${outcome.error.take(250)}", false, null, null)
            }
            is AiClient.Result.Ok -> {
                val replyText = outcome.text
                val lua = AiPluginGenerator.extractLua(replyText)
                var rebuilt = false
                var pass: Boolean? = null
                var validation: List<Map<String, Any?>>? = null

                if (lua != null && lua.contains("function")) {
                    job.step("💬 chat produced corrected Lua (${lua.length} bytes) — re-validating live …")
                    val probeUrl = job.corpusRef?.pages?.firstOrNull { it.kind == "book" }?.url
                        ?: job.corpusRef?.baseUrl ?: job.url
                    val report = withTimeoutOrNull(90_000) {
                        PluginValidator().validate(lua, job.query, { job.step(it) }, probeUrl)
                    }?.second
                    if (report != null) {
                        rebuilt = true
                        pass = report.pass
                        validation = report.entries.map {
                            mapOf("function" to it.function, "ok" to it.ok,
                                "detail" to it.detail, "durationMs" to it.durationMs)
                        }
                        job.result = GenerationResult(
                            success = report.pass,
                            siteUrl = job.url,
                            blueprint = null,
                            strategySummary = mapOf("source" to "AI:$model 💬 chat fix"),
                            validation = report.entries,
                            overallPass = report.pass,
                            luaCode = lua,
                            variantsTried = attemptCount(job) + 1
                        )
                        job.attempts.add(AttemptInfo(
                            attemptCount(job) + 1, "AI:$model 💬", report.pass,
                            "${report.entries.count { it.ok }}/${report.entries.size} functions pass", lua.length
                        ))
                        job.phase = "done"
                        job.error = null   // pass/fail is visible in the validation matrix
                    }
                }
                val cleanReply = replyText.replace(Regex("```(?:lua)?[\\s\\S]*?```", RegexOption.IGNORE_CASE), "").trim()
                synchronized(job.chatHistory) {
                    job.chatHistory.add("user" to userMessage)
                    job.chatHistory.add("assistant" to replyText)
                    while (job.chatHistory.size > 24) job.chatHistory.removeAt(0)
                }
                ChatReply(cleanReply.ifBlank { "(no text)" }, rebuilt, pass, validation)
            }
        }
        return reply
    }

    private suspend fun chatOnce(
        provider: dev.pluginstudio.gen.ai.ProviderConfig,
        model: String,
        messages: List<Map<String, String>>,
        maxTokens: Int
    ): AiClient.Result {
        var last: AiClient.Result? = null
        val keys = provider.apiKey.split("\n", ";", ",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("") }
        val cursor = java.util.concurrent.atomic.AtomicInteger(0)
        for ((i, _) in keys.withIndex()) {
            val key = keys[(cursor.getAndIncrement() + i) % keys.size]
            val url = when (provider.mode) {
                "responses" -> provider.baseUrl.trimEnd('/') + "/responses"
                else -> provider.baseUrl.trimEnd('/') + "/chat/completions"
            }
            val payload: Map<String, Any> = if (provider.mode == "responses")
                mapOf("model" to model, "input" to messages, "max_output_tokens" to maxTokens)
            else mapOf("model" to model, "messages" to messages, "max_tokens" to maxTokens)

            val resp = dev.pluginstudio.gen.HttpFetcher().post(
                url, Gson().toJson(payload), "application/json", timeoutSec = 170,
                extraHeaders = buildMap {
                    if (key.isNotBlank()) { put("Authorization", "Bearer $key"); put("x-api-key", key) }
                    provider.headers.forEach { (k, v) -> put(k, v) }
                }
            )
            if (!resp.ok) {
                val f = AiClient.Result.Fail("HTTP ${resp.status}: ${resp.body.take(200)}", resp.status)
                last = f
                if ((resp.status == 401 || resp.status == 429) && i < keys.size - 1) continue
                return f
            }
            val text = extractChatText(resp.body, provider.mode)
            if (text != null) { advanceChatCursor(provider.id); return AiClient.Result.Ok(text, model) }
            last = AiClient.Result.Fail("Could not parse AI response: ${resp.body.take(200)}", resp.status)
        }
        return last ?: AiClient.Result.Fail("no API keys", 0)
    }

    private val chatCursors = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun advanceChatCursor(id: String) { chatCursors[id]?.incrementAndGet() }

    @Suppress("UNCHECKED_CAST")
    private fun extractChatText(body: String, mode: String): String? = try {
        val root = Gson().fromJson(body, Map::class.java) as Map<String, Any>
        if (mode == "responses") {
            (root["output_text"] as? String)?.takeIf { it.isNotBlank() } ?: run {
                val sb = StringBuilder()
                (root["output"] as? List<*>)?.forEach { item ->
                    (item as? Map<*, *>)?.get("content")?.let { content ->
                        (content as? List<*>)?.forEach { c ->
                            ((c as? Map<*, *>)?.get("text") as? String)?.let { sb.append(it) }
                        }
                    }
                }
                sb.toString().ifBlank { null }
            }
        } else {
            (((root["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>)
                ?.get("message") as? Map<*, *>)?.get("content") as? String
        }
    } catch (_: Exception) { null }

    private fun attemptCount(job: GenJob): Int = synchronized(job.attempts) { job.attempts.size }

    /** Analyze an extra page mid-session and fold it into the stored evidence. */
    suspend fun analyzeExtraPage(jobId: String, url: String): Map<String, Any?> {
        val job = jobs[jobId] ?: return mapOf("ok" to false, "error" to "unknown job")
        val corpus = job.corpusRef ?: return mapOf("ok" to false, "error" to "job has no analysis session — run Analyze & Generate first")
        return try {
            val resp = withTimeoutOrNull(35_000) {
                dev.pluginstudio.gen.HttpFetcher().get(url, timeoutSec = 25)
            } ?: return mapOf("ok" to false, "error" to "fetch timed out")
            if (!resp.ok || resp.blocked) return mapOf("ok" to false, "error" to "HTTP ${resp.status} / blocked")
            val kind = SiteCrawler().classify(url)
            val snap = dev.pluginstudio.gen.PageSnapshot(
                url, resp.body, kind,
                dom = dev.pluginstudio.analyzer.DomAnalyzer().analyze(url, resp.body),
                js = try {
                    dev.pluginstudio.analyzer.SiteAnalyzer(maxExternalScripts = 2).analyzeHtml(url, resp.body)
                } catch (_: Exception) { null }
            )
            synchronized(corpus.pages) {
                corpus.pages.removeAll { it.url == url }
                corpus.pages.add(snap)
            }
            val fresh = EvidenceBuilder.toJson(EvidenceBuilder.build(corpus, emptyList(), job.query))
            job.evidenceJson = fresh
            mapOf("ok" to true, "kind" to kind, "bytes" to resp.body.length, "evidenceBytes" to fresh.length)
        } catch (e: Exception) {
            mapOf("ok" to false, "error" to (e.message ?: e.toString()))
        }
    }

    // ── export ──

    /** Stage generated lua under <repoRoot>/generated/ai/<jobId>.lua */
    fun stage(jobId: String): String? {
        val result = jobs[jobId]?.result ?: return null
        if (result.luaCode.isBlank()) return null
        val dir = repoRoot.resolve("generated").resolve("ai")
        Files.createDirectories(dir)
        val file = dir.resolve("$jobId.lua")
        Files.writeString(file, result.luaCode)
        result.stagedFile = file.toAbsolutePath().toString()
        return result.stagedFile
    }

    /** Copy latest generated lua into the live NoveLA sources folder. */
    fun saveToLibrary(jobId: String, libraryDir: Path): Map<String, Any> {
        val result = jobs[jobId]?.result
            ?: return mapOf("ok" to false, "error" to "job has no result")
        if (result.luaCode.isBlank()) return mapOf("ok" to false, "error" to "empty lua code")

        fun meta(key: String): String =
            Regex("""^\s*$key\s*=\s*"([^"]*)"""", RegexOption.MULTILINE)
                .find(result.luaCode)?.groupValues?.get(1).orEmpty()

        val id = meta("id").ifBlank { "ai_$jobId" }.replace(Regex("[^a-zA-Z0-9_]"), "_").take(48)
        val name = meta("name").ifBlank { id }
        val lang = meta("language").ifBlank { "en" }

        val langDir = libraryDir.resolve(lang)
        Files.createDirectories(langDir)
        val target = langDir.resolve("$id.lua")
        Files.writeString(target, result.luaCode)

        val indexFile = langDir.resolve("index.yaml")
        val entry = buildString {
            appendLine()
            appendLine("  - id: \"$id\"")
            appendLine("    name: \"$name\"")
            appendLine("    version: \"1.0.0\"")
            appendLine("    url: \"file://${target.toAbsolutePath()}\"")
            appendLine("    language: \"$lang\"")
        }.trimEnd('\n') + "\n"
        if (Files.exists(indexFile)) {
            Files.writeString(indexFile, Files.readString(indexFile).trimEnd('\n') + "\n" + entry)
        } else {
            Files.writeString(indexFile, buildString {
                appendLine("language: \"$lang\"")
                appendLine("name: \"${lang.uppercase()}\"")
                appendLine("sources:")
                append(entry.trimEnd('\n').removePrefix("\n"))
                appendLine()
            })
        }
        onPluginsChanged()
        return mapOf(
            "ok" to true, "pluginId" to id, "language" to lang,
            "file" to target.toAbsolutePath().toString(),
            "indexUpdated" to indexFile.toAbsolutePath().toString()
        )
    }
}
