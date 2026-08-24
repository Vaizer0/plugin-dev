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
    val source: String,          // "AI:<model>"
    val ok: Boolean,
    val summary: String,
    val luaBytes: Int
)

class GenJob(val id: String, val url: String, val query: String) {
    @Volatile var phase: String = "queued"   // crawl|probe|evidence|ai-generate|ai-validate|done|error
    val steps: MutableList<Step> = java.util.Collections.synchronizedList(mutableListOf<Step>())
    @Volatile var result: GenerationResult? = null
    @Volatile var error: String? = null
    private val startNanos = System.nanoTime()
    val startedAt = System.currentTimeMillis()
    val attempts = java.util.Collections.synchronizedList(mutableListOf<AttemptInfo>())
    @Volatile var aiModel: String = ""

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
 * Pipeline: Plugin-Dev deterministic analysis (multi-page crawl + live API
 * probing) → structured evidence → AI Lua generation (grounded in the official
 * plugin guide) → real-engine live validation → AI repair loop (max 3).
 */
class GeneratorService(
    private val repoRoot: Path,
    private val aiSettings: AiSettingsStore,
    private val onPluginsChanged: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val jobs = ConcurrentHashMap<String, GenJob>()
    private var jobCounter = 0

    // ── browser capture session (protected sites) ──

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
        // ── 0. provider/model (harness-style: config decides, no magic) ──
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
                when {
                    msg.startsWith("Probing") || msg.startsWith("Testing") -> job.phase = "probe"
                    msg.startsWith("Inferred") || msg.startsWith("Book page") ||
                        msg.startsWith("Chapter") || msg.startsWith("Search results") -> job.phase = "probe"
                }
                job.step(msg)
            }
        } ?: run { finishError(job, "Analysis exceeded its 60s budget — site too slow; try the capture flow"); return }

        if (!corpus.success) {
            finishError(job, (corpus.error ?: "analysis failed") +
                if (corpus.blocked) " — use the protected-site capture flow below." else "")
            return
        }

        // ── 2. analysis: parallel live API probing ──
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
            } ?: (AiPluginGenerator.Result2(false, "AI call timed out after 180s") to "")
            val genOk = outcome.first.ok
            val lua = outcome.second
            if (!genOk) {
                job.attempts.add(AttemptInfo(attemptNo, "AI:$model", false, outcome.first.message, lua.length))
                job.step("✗ ${outcome.first.message.take(160)}")
                // No silent rotation — errors surface so the user can fix config.
                continue
            }

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

        finishWithBest(job, best)
    }

    private fun finishError(job: GenJob, msg: String) {
        job.error = msg
        job.phase = "error"
        job.step("✗ $msg")
    }

    private fun finishWithBest(job: GenJob, best: GenerationResult?) {
        if (best != null) {
            job.result = best
            job.phase = "done"   // partial success surfaces via overallPass=false in UI
            job.error = null
            job.step("Stopped after repair loop — best attempt kept (see validation matrix).")
        } else {
            finishError(job, "AI could not produce a usable plugin from the available evidence.")
        }
    }

    private suspend fun listModels(provider: dev.pluginstudio.gen.ai.ProviderConfig): List<String> =
        AiClient().listModels(provider).first

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

        // Derive id/name from the Lua metadata itself (AI sets them properly).
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
