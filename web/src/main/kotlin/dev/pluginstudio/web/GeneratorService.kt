package dev.pluginstudio.web

import dev.pluginstudio.gen.GenerationResult
import dev.pluginstudio.gen.NetRecord
import dev.pluginstudio.gen.PluginGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** One Analyze & Generate run with live progress steps. */
class GenJob(val id: String, val url: String, val query: String) {
    @Volatile var phase: String = "queued"          // queued|crawl|probe|generate|validate|done|error
    val steps: MutableList<Step> = java.util.Collections.synchronizedList(mutableListOf<Step>())
    @Volatile var result: GenerationResult? = null
    @Volatile var error: String? = null
    private val startNanos = System.nanoTime()
    val startedAt = System.currentTimeMillis()

    fun elapsedMs(): Long = (System.nanoTime() - startNanos) / 1_000_000

    data class Step(val ts: Long, val msg: String)

    fun step(msg: String) {
        steps.add(Step(System.currentTimeMillis(), msg))
    }
}

/**
 * Runs the full Analyze & Generate pipeline as background jobs and stores
 * browser-captured pages for protected sites.
 */
class GeneratorService(
    private val repoRoot: Path,
    private val onPluginsChanged: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val jobs = ConcurrentHashMap<String, GenJob>()
    private var jobCounter = 0

    // ── browser capture session (protected sites) ──
    data class CapturedPage(
        val url: String,
        val html: String,
        val requests: List<NetRecord>,
        val receivedAt: Long
    )

    private val capturedPages = java.util.Collections.synchronizedList(mutableListOf<CapturedPage>())

    fun capturePage(url: String, html: String, requests: List<NetRecord>): Int {
        // replace previous capture of the same URL so re-visits stay fresh
        synchronized(capturedPages) {
            capturedPages.removeAll { it.url == url }
            capturedPages.add(CapturedPage(url, html, requests, System.currentTimeMillis()))
        }
        return capturedPages.size
    }

    fun listCaptured(): List<Map<String, Any>> = synchronized(capturedPages) {
        capturedPages.map { mapOf(
            "url" to it.url,
            "bytes" to it.html.length,
            "requests" to it.requests.size,
            "receivedAt" to it.receivedAt
        ) }
    }

    fun clearCaptured() { synchronized(capturedPages) { capturedPages.clear() } }

    // ── generation ──

    @Synchronized
    fun startJob(url: String, query: String, idHint: String?, nameHint: String?, useCapture: Boolean): GenJob {
        jobCounter++
        val job = GenJob("gen-$jobCounter-${System.currentTimeMillis()}", url, query)
        jobs[job.id] = job
        scope.launch {
            try {
                job.phase = "crawl"
                val generator = PluginGenerator(onStep = { msg ->
                    when {
                        msg.startsWith("Probing") || msg.startsWith("Testing") || msg.startsWith("Checking") -> job.phase = "probe"
                        msg.startsWith("Generating") -> job.phase = "generate"
                        msg.startsWith("Validating") || msg.startsWith("✓ Variant") -> job.phase = "validate"
                    }
                    job.step(msg)
                })
                val captureInput = if (useCapture && capturedPages.isNotEmpty()) {
                    PluginGenerator.CaptureInput(synchronized(capturedPages) {
                        capturedPages.map { Triple(it.url, it.html, it.requests) }
                    })
                } else null
                if (captureInput != null) job.step("Using ${captureInput.pages.size} captured page(s) instead of crawling")
                job.phase = "generate"
                // Hard cap so a hostile/slow site can never hang a job indefinitely.
                val result = withTimeoutOrNull(150_000) {
                    generator.generate(url, query, idHint, nameHint, captureInput)
                } ?: GenerationResult(false, url, null, emptyMap(), emptyList(), false, "",
                    null, 0, "Generation exceeded the 2.5-minute budget — use the protected-site (capture) flow for this site")
                job.result = result
                job.phase = if (result.success) "done" else "error"
                if (!result.success) job.error = result.error
            } catch (e: Exception) {
                job.error = e.message ?: e.toString()
                job.phase = "error"
            }
        }
        return job
    }

    /** Stage the generated lua under <repo>/generated/<lang>/<id>.lua. */
    fun stage(jobId: String): String? {
        val result = jobs[jobId]?.result ?: return null
        val bp = result.blueprint ?: return null
        val dir = repoRoot.resolve("generated").resolve(bp.language.ifBlank { "en" })
        Files.createDirectories(dir)
        val file = dir.resolve("${bp.id}.lua")
        Files.writeString(file, result.luaCode)
        result.stagedFile = file.toAbsolutePath().toString()
        return result.stagedFile
    }

    /** Copy staged plugin into the live NoveLA sources folder and update index.yaml. */
    fun saveToLibrary(jobId: String, libraryDir: Path): Map<String, Any> {
        val result = jobs[jobId]?.result
            ?: return mapOf("ok" to false, "error" to "job has no result")
        val bp = result.blueprint
            ?: return mapOf("ok" to false, "error" to "no blueprint")
        if (result.luaCode.isBlank()) return mapOf("ok" to false, "error" to "empty lua code")

        val langDir = libraryDir.resolve(bp.language.ifBlank { "en" })
        Files.createDirectories(langDir)
        val target = langDir.resolve("${bp.id}.lua")
        Files.writeString(target, result.luaCode)

        // update language index.yaml (append entry at end of sources list)
        val indexFile = langDir.resolve("index.yaml")
        val entry = buildString {
            appendLine()
            appendLine("  - id: \"${bp.id}\"")
            appendLine("    name: \"${bp.name}\"")
            appendLine("    version: \"1.0.0\"")
            appendLine("    url: \"file://${target.toAbsolutePath()}\"")
            if (bp.icon.isNotBlank()) appendLine("    icon: \"${bp.icon}\"")
            appendLine("    language: \"${bp.language.ifBlank { "en" }}\"")
        }.trimEnd('\n') + "\n"
        if (Files.exists(indexFile)) {
            val text = Files.readString(indexFile)
            Files.writeString(indexFile, text.trimEnd('\n') + "\n" + entry)
        } else {
            Files.writeString(indexFile, buildString {
                appendLine("language: \"${bp.language.ifBlank { "en" }}\"")
                appendLine("name: \"${bp.language.ifBlank { "en" }.uppercase()}\"")
                appendLine("sources:")
                append(entry.trimEnd('\n').replaceFirst("\n", "") )
                appendLine()
            })
        }
        onPluginsChanged()

        return mapOf(
            "ok" to true,
            "pluginId" to bp.id,
            "language" to bp.language,
            "file" to target.toAbsolutePath().toString(),
            "indexUpdated" to indexFile.toAbsolutePath().toString()
        )
    }
}
