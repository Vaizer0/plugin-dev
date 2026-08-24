package dev.pluginstudio.gen.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AiAttempt(
    val n: Int,
    val source: String,          // "template" | "AI:<model>"
    val ok: Boolean,
    val summary: String,
    val luaBytes: Int
)

/**
 * AI generation with self-repair: builds prompts from the evidence package,
 * extracts Lua, hands it to the caller for real validation, and feeds failures
 * back for a limited number of repair rounds.
 */
class AiPluginGenerator(private val client: AiClient = AiClient()) {

    companion object {
        fun systemPrompt(): String = """You are an expert generator of NoveLA Lua source plugins for novel websites.

The user gives you STRUCTURED EVIDENCE collected by a deterministic analyzer from a real site (pages, roles, containers, selector candidates, verified API endpoints, forms, pagination). The evidence is the ONLY source of truth.

STRICT RULES:
1. NEVER invent URLs, endpoints, selectors, JSON fields, parameters, headers or cookies. Use only what appears in the evidence.
2. Prefer VERIFIED JSON APIs (listed under verifiedApis) over HTML scraping whenever they cover a capability.
3. Use only the engine builtins documented below — do not invent functions.
4. Generate metadata + getCatalogList + getCatalogSearch (when search evidence exists) + book details + chapter list (or parsePage) + getChapterText, covering every capability the evidence supports.
5. ICON: always set the top-level `icon` variable. Use evidence.siteIconObserved if present; otherwise use evidence.iconSuggestion verbatim (standard Novela favicon template on the base domain — never include a path).
6. Keep code COMPACT: short section comments only — less output = faster & fewer token overruns. and normalize whitespace.
7. Output EXACTLY ONE complete Lua source in a single ```lua code block, no explanations before/after.

""" + LuaApiReference.text()

        fun extractLua(reply: String): String? {
            val fenced = Regex("```(?:lua)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
                .findAll(reply).lastOrNull()?.groupValues?.get(1)?.trim()
            if (!fenced.isNullOrBlank() && fenced.contains("function")) return fenced
            // unfenced fallback: take from first assignment/function to end
            val start = reply.lines().indexOfFirst {
                it.trimStart().startsWith("id ") || it.trimStart().startsWith("local") ||
                    it.trimStart().startsWith("function")
            }
            if (start >= 0) return reply.lines().drop(start).joinToString("\n").trim()
            return null
        }
    }

    data class GenerationCall(
        val evidenceJson: String,
        val previousLua: String? = null,
        val previousFailures: List<String> = emptyList()
    )

    suspend fun generate(provider: ProviderConfig, model: String, call: GenerationCall): Pair<Result2, String> =
        withContext(Dispatchers.IO) {
            val user = buildString {
                appendLine("SITE EVIDENCE (JSON):")
                appendLine(call.evidenceJson)
                if (call.previousLua != null) {
                    appendLine()
                    appendLine("PREVIOUS ATTEMPT FAILED LIVE VALIDATION. Failures:")
                    call.previousFailures.forEach { appendLine(" - $it") }
                    appendLine("Fix these problems. Previous Lua:")
                    appendLine("```lua")
                    appendLine(call.previousLua)
                    appendLine("```")
                    appendLine("Return the FULL corrected Lua source.")
                } else {
                    appendLine()
                    appendLine("Generate the complete NoveLA Lua plugin now. Return only the ```lua block.")
                }
            }
            when (val r = client.complete(provider, model, systemPrompt(), user)) {
                is AiClient.Result.Ok -> {
                    val lua = extractLua(r.text)?.let { code ->
                        // light deterministic cleanup
                        code.replace("\uFEFF", "").trimIndent().trim() + "\n"
                    }
                        ?: return@withContext Result2(false, "AI replied without usable Lua (${r.text.length} chars)") to r.text
                    Result2(true, "ok") to lua
                }
                is AiClient.Result.Fail ->
                    Result2(false, "AI error: ${r.error.take(300)}") to ""
            }
        }

    data class Result2(val ok: Boolean, val message: String)
}
