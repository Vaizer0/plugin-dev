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

You are given:
(1) STRUCTURED EVIDENCE — collected live by a deterministic analyzer from the target site,
(2) THE OFFICIAL NOVELA LUA PLUGIN GUIDE — the authoritative spec for structure,
    metadata, function signatures, engine builtins and conventions.

STRICT RULES:
1. The EVIDENCE is your only source of truth for URLs, selectors, JSON fields,
   endpoints and parameters. NEVER invent anything not present there.
2. Prefer VERIFIED JSON APIs (verifiedApis in the evidence) over HTML scraping
   whenever they cover a capability.
3. Follow the GUIDE for format and API usage — otherwise you have FREE HAND in
   designing helpers, control flow and parsing strategy, as long as the plugin
   loads through the real engine and works.
   HARD CONTRACT — exact global function NAMES/arity; include one only if the
   capability is supported by evidence:
     getCatalogList(index) · getCatalogSearch(index, query)
     getBookTitle(bookUrl) · getBookCoverImageUrl(bookUrl)
     getBookDescription(bookUrl) · getBookGenres(bookUrl)
     getChapterList(bookUrl) OR parsePage(bookUrl, page)
     getChapterText(html, url)
   Every helper you call must be defined in the same file.
4b. METADATA CONTRACT — ALWAYS emit these top-level variables (no local):
     id       = "lowercase_with_underscores"
     name     = "Human Name"
     version  = "1.0.0"
     baseUrl  = "<evidence.baseUrl>"
     language = "<ISO code>"
   plus icon per rule 5.
4. Cover every capability the evidence supports: metadata, catalog, search,
   details, genres, chapter list (or parsePage), chapter content, pagination, images.
5. ICON: always set the top-level `icon` variable — use evidence.siteIconObserved
   if present, otherwise evidence.iconSuggestion verbatim (standard Novela favicon
   template on the base domain).
6. Keep code compact; short section comments only.
7. Output EXACTLY ONE complete Lua source in a single ```lua code block — nothing
   before or after.

""" + LuaApiReference.text()

        private val HELPER_SHIMS = mapOf(
            "absUrl" to """
                |if not absUrl then
                |  function absUrl(href)
                |    if not href or href == "" then return "" end
                |    if string_starts_with(href, "http") then return href end
                |    if string_starts_with(href, "//") then return "https:" .. href end
                |    return url_resolve(baseUrl, href)
                |  end
                |end""".trimMargin()
        )

        /** Inject commonly-implied helper definitions when the model referenced but forgot them. */
        fun withHelperShims(lua: String): String {
            var out = lua
            for ((name, shim) in HELPER_SHIMS) {
                val defined = Regex("(local\\s+)?function\\s+$name\\b").containsMatchIn(out)
                val used = Regex("$name\\s*\\(").containsMatchIn(out)
                if (used && !defined) out = shim + "\n\n" + out
            }
            return out
        }

        /**
         * Guarantees the five required top-level metadata variables exist.
         * Missing ones are injected at the very top; existing ones untouched.
         */
        fun ensureMetadata(
            lua: String,
            baseUrl: String,
            fallbackId: String,
            fallbackName: String,
            language: String = "en",
            iconSuggestion: String
        ): String {
            val defs = mutableListOf<String>()
            fun missing(key: String) =
                !Regex("(?m)^\\s*(?:local\\s+)?$key\\s*=\\s*\"[^\"]*\"").containsMatchIn(lua)
            fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
            if (missing("id")) defs.add("id       = \"${esc(fallbackId)}\"")
            if (missing("name")) defs.add("name     = \"${esc(fallbackName)}\"")
            if (missing("version")) defs.add("version  = \"1.0.0\"")
            if (missing("baseUrl")) defs.add("baseUrl  = \"${esc(baseUrl)}\"")
            if (missing("language")) defs.add("language = \"${esc(language)}\"")
            if (iconSuggestion.isNotBlank() &&
                !lua.contains("gstatic.com/faviconV2") &&
                !Regex("(?m)^\\s*icon\\s*=").containsMatchIn(lua))
                defs.add("icon     = \"${esc(iconSuggestion)}\"")
            return if (defs.isEmpty()) lua else (defs.joinToString("\n") + "\n\n" + lua)
        }

        fun extractLua(reply: String): String? {
            val fenced = Regex("```(?:lua)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
                .findAll(reply).lastOrNull()?.groupValues?.get(1)?.trim()
            if (!fenced.isNullOrBlank() && fenced.contains("function")) return fenced
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
