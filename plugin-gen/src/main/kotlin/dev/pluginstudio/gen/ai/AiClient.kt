package dev.pluginstudio.gen.ai

import com.google.gson.Gson
import dev.pluginstudio.gen.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/** One configured OpenAI-compatible provider. apiKey never leaves the server. */
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,          // e.g. https://opencode.ai/zen/v1
    val apiKey: String = "",      // may be empty for public endpoints
    val mode: String = "chat",    // "chat" | "responses"
    val defaultModel: String = "",// user-chosen model for this provider
    val maxTokens: Int = 8192,
    val headers: Map<String, String> = emptyMap()
) {
    /** Redacted view safe to send to the browser. */
    fun publicView(): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "baseUrl" to baseUrl,
        "mode" to mode,
        "hasKey" to apiKey.isNotBlank(),
        "defaultModel" to defaultModel,
        "maxTokens" to maxTokens,
        "keyHint" to if (apiKey.length > 4) "…" + apiKey.takeLast(4) else "",
        "headers" to headers.keys
    )
}

/**
 * Minimal generic OpenAI-compatible client supporting both Chat Completions
 * and Responses API modes. No provider-specific logic.
 */
class AiClient(private val fetcher: HttpFetcher = HttpFetcher()) {

    sealed class Result {
        data class Ok(val text: String, val model: String) : Result()
        data class Fail(val error: String, val status: Int) : Result()
    }

    suspend fun listModels(provider: ProviderConfig): Pair<List<String>, String?> = withContext(Dispatchers.IO) {
        val resp = fetcher.get(
            provider.baseUrl.trimEnd('/') + "/models",
            extraHeaders = authHeaders(provider),
            timeoutSec = 15
        )
        if (!resp.ok) return@withContext emptyList<String>() to ("HTTP ${resp.status}: ${resp.body.take(120)}")
        try {
            @Suppress("UNCHECKED_CAST")
            val root = Gson().fromJson(resp.body, Map::class.java) as Map<String, Any>
            val data = (root["data"] as? List<Map<*, *>>)?.mapNotNull { (it["id"] as? String) }
            data.orEmpty() to null
        } catch (e: Exception) {
            emptyList<String>() to (e.message ?: "parse error")
        }
    }

    suspend fun complete(
        provider: ProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 12_000,
        temperature: Double = 0.2
    ): Result = completeTurns(
        provider, model, systemPrompt,
        listOf("user" to userPrompt), maxTokens, temperature
    )

    /** Multi-turn completion (interactive repair chat). */
    suspend fun completeTurns(
        provider: ProviderConfig,
        model: String,
        systemPrompt: String,
        turns: List<Pair<String, String>>,
        maxTokens: Int = 12_000,
        temperature: Double = 0.2
    ): Result = completeWithKeys(provider, apiKeys(provider), model, systemPrompt, turns, maxTokens, temperature)

    /** NoveLA-style multi-key round-robin: 401 tries remaining keys, 429 rotates too. */
    private suspend fun completeWithKeys(
        provider: ProviderConfig,
        keys: List<String>,
        model: String,
        systemPrompt: String,
        turns: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double
    ): Result {
        var last: Result? = null
        for ((i, _) in keys.withIndex()) {
            val key = keys[(keyCursor(provider.id) + i) % keys.size]
            val r = completeOnce(provider, key, model, systemPrompt, turns, maxTokens, temperature)
            when (r) {
                is Result.Ok -> { advanceCursor(provider.id); return r }
                is Result.Fail -> {
                    last = r
                    if (r.status == 401 && i < keys.size - 1) continue   // bad key → next
                    if (r.status == 429 && i < keys.size - 1) continue   // rate limit → next
                    return r
                }
            }
        }
        return last ?: Result.Fail("no API keys configured", 0)
    }

    private val cursors = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun keyCursor(id: String): Int =
        cursors.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicInteger(0) }.getAndIncrement()

    private fun advanceCursor(id: String) { cursors[id]?.incrementAndGet() }

    private fun apiKeys(p: ProviderConfig): List<String> =
        p.apiKey.split("\n", ";", ",").map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf("") }

    private suspend fun completeOnce(
        provider: ProviderConfig,
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double
    ): Result {
        return withContext(Dispatchers.IO) {
        val url: String = when (provider.mode) {
            "responses" -> provider.baseUrl.trimEnd('/') + "/responses"
            else -> provider.baseUrl.trimEnd('/') + "/chat/completions"
        }
        val messages = listOf(mapOf("role" to "system", "content" to systemPrompt)) +
            turns.map { mapOf("role" to it.first, "content" to it.second) }
        val payload: Map<String, Any> = when (provider.mode) {
            "responses" -> mapOf(
                "model" to model,
                "input" to messages,
                "max_output_tokens" to maxTokens,
                "temperature" to temperature
            )
            else -> mapOf(
                "model" to model,
                "messages" to messages,
                "max_tokens" to maxTokens,
                "temperature" to temperature
            )
        }
        val body = Gson().toJson(payload)
        if (apiKey.isBlank() && provider.headers.isEmpty()) {
            val resp = fetcher.post(url, body, "application/json", timeoutSec = 150)
            if (!resp.ok) return@withContext Result.Fail("HTTP ${resp.status}: ${resp.body.take(200)}", resp.status)
            return@withContext (extractText(resp.body, provider.mode)?.let { Result.Ok(it, model) }
                ?: Result.Fail("Could not parse AI response: ${resp.body.take(200)}", resp.status))
        }
        val h = buildMap {
            if (apiKey.isNotBlank()) {
                put("Authorization", "Bearer $apiKey")
                put("x-api-key", apiKey)   // Anthropic-style providers
            }
            provider.headers.forEach { (k, v) -> put(k, v) }
            put("Content-Type", "application/json")
        }
            val resp = rawPost(url, body, h, 150)
            if (!resp.ok) return@withContext Result.Fail(
                "HTTP ${resp.status}: ${resp.body.take(200)}", resp.status
            )
            extractText(resp.body, provider.mode)?.let { return@withContext Result.Ok(it, model) }
            Result.Fail("Could not parse AI response: ${resp.body.take(200)}", resp.status)
        }
    }

    private suspend fun rawPost(url: String, body: String, headers: Map<String, String>, timeoutSec: Int): HttpFetcher.Response =
        withContext(Dispatchers.IO) {
            try {
                val b = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", HttpFetcher.UA)
                    .header("Accept", "application/json")
                headers.forEach { (k, v) -> b.header(k, v) }
                b.post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
                val call = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(b.build())
                call.timeout().deadline(timeoutSec + 5L, java.util.concurrent.TimeUnit.SECONDS)
                call.execute().use { resp ->
                    HttpFetcher.Response(resp.code, resp.header("Content-Type") ?: "", resp.body?.string() ?: "")
                }
            } catch (e: Exception) {
                HttpFetcher.Response(0, "", e.message ?: e.toString())
            }
        }

    private fun authHeaders(p: ProviderConfig): Map<String, String> =
        buildMap {
            if (p.apiKey.isNotBlank()) {
                put("Authorization", "Bearer ${p.apiKey}")
                put("x-api-key", p.apiKey)   // Anthropic-style providers
            }
            p.headers.forEach { (k, v) -> put(k, v) }
        }

    private fun extractText(body: String, mode: String): String? = try {
        @Suppress("UNCHECKED_CAST")
        val root = Gson().fromJson(body, Map::class.java) as Map<String, Any>
        if (mode == "responses") {
            val direct = root["output_text"] as? String
            if (!direct.isNullOrBlank()) direct
            else {
                val out = root["output"] as? List<*> ?: return null
                val sb = StringBuilder()
                for (item in out) {
                    val m = item as? Map<*, *> ?: continue
                    val content = m["content"] as? List<*> ?: continue
                    for (c in content) {
                        val cm = c as? Map<*, *> ?: continue
                        (cm["text"] as? String)?.let { sb.append(it) }
                    }
                }
                sb.toString().ifBlank { null }
            }
        } else {
            val choices = root["choices"] as? List<*> ?: return null
            val first = choices.firstOrNull() as? Map<*, *> ?: return null
            val msg = first["message"] as? Map<*, *> ?: return null
            val content = msg["content"] as? String
            if (content.isNullOrBlank()) {
                throw IllegalStateException(
                    "model exhausted its token budget during reasoning (finish_reason=" +
                        (first["finish_reason"]?.toString() ?: "?") + ")"
                )
            }
            content
        }
    } catch (_: Exception) { null }
}
