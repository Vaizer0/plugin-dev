package dev.pluginstudio.analyzer

enum class EndpointCategory {
    CATALOG, SEARCH, BOOK_DETAIL, CHAPTER_LIST, CHAPTER_TEXT, GENRES, AUTH, OTHER
}

enum class FindingType {
    FETCH, AXIOS, XHR, JQUERY_AJAX, JQUERY_SHORTHAND, XHR_OPEN,
    WP_AJAX_URL, WP_AJAX_ACTION, WP_API_SETTINGS,
    API_BASE_VARIABLE, BASE_URL_VARIABLE, API_KEY_VARIABLE, ENDPOINT_VARIABLE,
    NEXT_DATA, NUXT_DATA, JSON_LD,
    CUSTOM_ENCODE, CUSTOM_DECODE, CUSTOM_BASE64, STRING_MANIP,
    GRAPHQL_ENDPOINT, GRAPHQL_QUERY,
    API_PATH, WORDPRESS_AJAX_COMBINED
}

data class JsFinding(
    val type: FindingType,
    val value: String,
    val line: Int = 0,
    val context: String = ""
)

data class JsFileResult(
    val url: String,
    val findings: List<JsFinding>,
    val apiPaths: List<String> = emptyList(),
    val fileSizeBytes: Int = 0
)

data class SiteAnalysisReport(
    val pageUrl: String,
    val jsFiles: List<JsFileResult>,
    val endpointSuggestions: List<EndpointSuggestion>,
    val cssSelectors: List<CssSelectorEntry>,
    val selectorResult: SelectorResult? = null,
    val summary: AnalysisSummary
)

data class EndpointSuggestion(
    val url: String,
    val category: EndpointCategory,
    val source: String,
    val confidence: String  // "high", "medium", "low"
)

data class CssSelectorEntry(
    val selector: String,
    val type: String,
    val count: Int,
    val sampleText: String = ""
)

data class AnalysisSummary(
    val totalJsFiles: Int,
    val totalFindings: Int,
    val totalEndpoints: Int,
    val totalCssSelectors: Int,
    val warningMessages: List<String> = emptyList()
)

// ── Regex patterns ──

private val FETCH_PATTERN = Regex(
    """fetch\s*\(\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val AXIOS_PATTERN = Regex(
    """axios\.(?:get|post|put|delete|patch)\s*\(\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val XHR_PATTERN = Regex(
    """(?:new\s+)?XMLHttpRequest""",
    RegexOption.IGNORE_CASE
)

private val JQUERY_AJAX_PATTERN = Regex(
    """\.ajax\s*\(\s*\{[^}]*?url\s*[:=]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val JQUERY_SHORTHAND = Regex(
    """\$\s*\.\s*(?:get|post)\s*\(\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val XHR_OPEN = Regex(
    """\.open\s*\(\s*['"`](?:GET|POST)['"`]\s*,\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val WP_AJAX_URL = Regex(
    """(?:ajaxurl|ajax_url)\s*[:=]\s*['"`]([^'"`]*)admin-ajax\.php[^'"`]*['"`]""",
    RegexOption.IGNORE_CASE
)

private val WP_AJAX_ACTION = Regex(
    """['"`]action['"`]\s*[:=]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val WP_API_SETTINGS = Regex(
    """wpApiSettings\s*=\s*\{[^}]*?"root"\s*:\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val API_BASE_VARIABLE = Regex(
    """(?:var|let|const|window\.)\s*(\w*(?:api|Api|API)\w*)\s*[=:]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val BASE_URL_VARIABLE = Regex(
    """(?:var|let|const|window\.)\s*(\w*(?:base|Base)[uU]rl\w*)\s*[=:]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val API_KEY_VARIABLE = Regex(
    """(?:var|let|const|window\.)\s*(\w*(?:apiKey|siteId|token|secret)\w*)\s*[=:]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val ENDPOINT_VARIABLE = Regex(
    """(?:var|let|const|window\.)\s*(\w*(?:endpoint|Endpoint)\w*)\s*[=:]\s*['"`]([^'"`]+)['"`]""",
    RegexOption.IGNORE_CASE
)

private val NEXT_DATA = Regex(
    """__NEXT_DATA__\s*=\s*(\{.+?\});""",
    RegexOption.DOT_MATCHES_ALL
)

private val NUXT_DATA = Regex(
    """__NUXT__\s*=\s*(\{.+?\});""",
    RegexOption.DOT_MATCHES_ALL
)

private val JSON_LD = Regex(
    """application/ld\+json""",
    RegexOption.IGNORE_CASE
)

private val CUSTOM_ENCODE = Regex(
    """function\s+\w*[Ee]ncode\w*\s*\([^)]*\)""",
    RegexOption.IGNORE_CASE
)

private val CUSTOM_DECODE = Regex(
    """function\s+\w*[Dd]ecode\w*\s*\([^)]*\)""",
    RegexOption.IGNORE_CASE
)

private val CUSTOM_BASE64 = Regex(
    """(?:base64|Base64)\s*=\s*['"`]([A-Za-z0-9+/]{20,})['"`]""",
    RegexOption.IGNORE_CASE
)

private val STRING_MANIP = Regex(
    """.*\.(?:charCodeAt|fromCharCode|substring|split|join).*""",
    RegexOption.IGNORE_CASE
)

private val GRAPHQL_ENDPOINT = Regex(
    """['"`](/graphql|/api/graphql|/v1/graphql)['"`]""",
    RegexOption.IGNORE_CASE
)

private val GRAPHQL_QUERY = Regex(
    """(?:query|mutation)\s+\w+\s*\{""",
    RegexOption.IGNORE_CASE
)

// ── String literal extraction ──

private val SQ_STR = Regex("""'([^'\\]*(?:\\.[^'\\]*)*)'""")
private val DQ_STR = Regex("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"")
private val BT_STR = Regex("""`([^`\\]*(?:\\.[^`\\]*)*)`""")

private val ASSET_PATHS = listOf(
    "/assets/", "/images/", "/img/", "/css/", "/js/", "/fonts/",
    "/static/", "/dist/", "/build/", "/uploads/", "/media/", "/icons/",
    ".svg", ".ico", ".png", ".jpg", ".jpeg", ".gif", ".webp",
    ".woff", ".woff2", ".ttf", ".eot", ".css", ".js"
)

private fun isApiPath(s: String): Boolean {
    val lower = s.lowercase()
    if (lower.length < 5) return false
    // skip absolute URLs to other domains
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        return false
    }
    // skip asset paths
    if (ASSET_PATHS.any { lower.contains(it) }) return false
    // API-like indicators
    return lower.contains("/api") ||
           lower.contains("admin-ajax") ||
           lower.contains("wp-json") ||
           lower.contains("graphql") ||
           lower.contains("/ajax") ||
           lower.endsWith(".php") ||
           lower.endsWith(".json") ||
           (lower.startsWith("/") && (
               lower.contains("/novel") ||
               lower.contains("/manga") ||
               lower.contains("/book") ||
               lower.contains("/chapter") ||
               lower.contains("/search") ||
               lower.contains("/browse") ||
               lower.contains("/listing") ||
               lower.contains("/catalog") ||
               lower.contains("/category") ||
               lower.contains("/genre") ||
               lower.contains("/tag") ||
               lower.contains("/comment")
           ))
}

private fun extractAllStringLiterals(code: String): List<String> {
    val results = mutableListOf<String>()
    fun scan(pat: Regex) { for (m in pat.findAll(code)) { m.groupValues[1].let { if (it.length in 5..200) results.add(it) } } }
    scan(SQ_STR); scan(DQ_STR); scan(BT_STR)
    return results
}

class JsScanner {
    fun scan(jsCode: String, sourceUrl: String): JsFileResult {
        val findings = mutableListOf<JsFinding>()

        findPattern(jsCode, FETCH_PATTERN, FindingType.FETCH) { findings.add(it) }
        findPattern(jsCode, AXIOS_PATTERN, FindingType.AXIOS) { findings.add(it) }
        findPattern(jsCode, XHR_PATTERN, FindingType.XHR) { findings.add(it) }
        findPattern(jsCode, JQUERY_AJAX_PATTERN, FindingType.JQUERY_AJAX) { findings.add(it) }
        findPattern(jsCode, JQUERY_SHORTHAND, FindingType.JQUERY_SHORTHAND) { findings.add(it) }
        findPattern(jsCode, XHR_OPEN, FindingType.XHR_OPEN) { findings.add(it) }
        findPattern(jsCode, WP_AJAX_URL, FindingType.WP_AJAX_URL) { findings.add(it) }
        findPattern(jsCode, WP_AJAX_ACTION, FindingType.WP_AJAX_ACTION) { findings.add(it) }
        findPattern(jsCode, WP_API_SETTINGS, FindingType.WP_API_SETTINGS) { findings.add(it) }
        findPattern(jsCode, API_BASE_VARIABLE, FindingType.API_BASE_VARIABLE) { findings.add(it) }
        findPattern(jsCode, BASE_URL_VARIABLE, FindingType.BASE_URL_VARIABLE) { findings.add(it) }
        findPattern(jsCode, API_KEY_VARIABLE, FindingType.API_KEY_VARIABLE) { findings.add(it) }
        findPattern(jsCode, ENDPOINT_VARIABLE, FindingType.ENDPOINT_VARIABLE) { findings.add(it) }
        findPattern(jsCode, NEXT_DATA, FindingType.NEXT_DATA) { findings.add(it) }
        findPattern(jsCode, NUXT_DATA, FindingType.NUXT_DATA) { findings.add(it) }
        findPattern(jsCode, JSON_LD, FindingType.JSON_LD) { findings.add(it) }
        findPattern(jsCode, CUSTOM_ENCODE, FindingType.CUSTOM_ENCODE) { findings.add(it) }
        findPattern(jsCode, CUSTOM_DECODE, FindingType.CUSTOM_DECODE) { findings.add(it) }
        findPattern(jsCode, CUSTOM_BASE64, FindingType.CUSTOM_BASE64) { findings.add(it) }
        findPattern(jsCode, STRING_MANIP, FindingType.STRING_MANIP) { findings.add(it) }
        findPattern(jsCode, GRAPHQL_ENDPOINT, FindingType.GRAPHQL_ENDPOINT) { findings.add(it) }
        findPattern(jsCode, GRAPHQL_QUERY, FindingType.GRAPHQL_QUERY) { findings.add(it) }

        // extract API paths from all string literals
        val apiPaths = extractAllStringLiterals(jsCode).filter { isApiPath(it) }.distinct()
        apiPaths.forEach { path ->
            findings.add(JsFinding(
                type = FindingType.API_PATH,
                value = path.take(200),
                context = "string literal"
            ))
        }

        // WordPress: combine admin-ajax URLs with action names
        val ajaxUrls = findings.filter { it.type == FindingType.WP_AJAX_URL }.map { it.value.trim().trim('"', '\'', '`') }.distinct()
        val actions = findings.filter { it.type == FindingType.WP_AJAX_ACTION }.map { it.value.trim().trim('"', '\'', '`') }.distinct()
        if (ajaxUrls.isNotEmpty() && actions.isNotEmpty()) {
            for (ajaxUrl in ajaxUrls) {
                for (action in actions) {
                    val combined = if (ajaxUrl.contains("?")) "$ajaxUrl&action=$action" else "$ajaxUrl?action=$action"
                    findings.add(JsFinding(
                        type = FindingType.WORDPRESS_AJAX_COMBINED,
                        value = combined,
                        context = "admin-ajax + action"
                    ))
                }
            }
        } else if (ajaxUrls.isNotEmpty()) {
            // just the admin-ajax URL even without known actions
            for (ajaxUrl in ajaxUrls) {
                findings.add(JsFinding(
                    type = FindingType.WORDPRESS_AJAX_COMBINED,
                    value = ajaxUrl,
                    context = "admin-ajax endpoint"
                ))
            }
        }

        return JsFileResult(
            url = sourceUrl,
            findings = findings,
            apiPaths = apiPaths,
            fileSizeBytes = jsCode.length
        )
    }

    private fun findPattern(code: String, regex: Regex, type: FindingType, onMatch: (JsFinding) -> Unit) {
        for (match in regex.findAll(code)) {
            val value = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value.take(120)
            val line = code.take(match.range.first).count { it == '\n' } + 1
            val contextStart = maxOf(0, match.range.first - 30)
            val contextEnd = minOf(code.length, match.range.last + 1 + 30)
            val context = code.substring(contextStart, contextEnd).replace('\n', ' ').trim().take(120)
            onMatch(JsFinding(type = type, value = value.take(200), line = line, context = context))
        }
    }
}

fun classifyEndpoint(url: String): EndpointCategory {
    val path = url.lowercase()
    return when {
        path.contains("chapter") || path.contains("release") -> EndpointCategory.CHAPTER_LIST
        path.contains("search") || path.contains("query") || path.contains("find") -> EndpointCategory.SEARCH
        path.contains("catalog") || path.contains("browse") || path.contains("listing") -> EndpointCategory.CATALOG
        (path.contains("novel") || path.contains("book")) &&
            (path.contains("detail") || path.contains("info") || path.contains("get")) -> EndpointCategory.BOOK_DETAIL
        path.contains("genre") || path.contains("tag") || path.contains("category") -> EndpointCategory.GENRES
        path.contains("login") || path.contains("auth") || path.contains("token") -> EndpointCategory.AUTH
        path.contains("reader") || path.contains("content") || path.contains("text") ||
            path.contains("view") -> EndpointCategory.CHAPTER_TEXT
        path.contains("manga") && !path.contains("search") -> EndpointCategory.BOOK_DETAIL
        else -> EndpointCategory.OTHER
    }
}

fun suggestEndpoints(findings: List<JsFinding>): List<EndpointSuggestion> {
    val suggestions = mutableListOf<EndpointSuggestion>()
    val seen = mutableSetOf<String>()

    for (f in findings) {
        val url = when (f.type) {
            FindingType.FETCH, FindingType.AXIOS, FindingType.JQUERY_AJAX,
            FindingType.JQUERY_SHORTHAND, FindingType.XHR_OPEN -> f.value.trim()
            FindingType.GRAPHQL_ENDPOINT -> f.value
            FindingType.API_PATH -> {
                val raw = f.value.trim()
                if (raw.length < 5) null else raw
            }
            FindingType.WORDPRESS_AJAX_COMBINED -> f.value.trim()
            FindingType.WP_AJAX_URL -> f.value.trim().trim('"', '\'', '`')
            FindingType.WP_API_SETTINGS -> {
                val raw = f.value.trim().trim('"', '\'', '`')
                if (raw.isNotBlank()) raw else null
            }
            FindingType.API_BASE_VARIABLE -> {
                val parts = f.value.split("=", limit = 2).map { it.trim().trim('"', '\'', '`') }
                if (parts.size == 2 && looksLikeUrl(parts[1])) parts[1] else null
            }
            FindingType.BASE_URL_VARIABLE -> {
                val parts = f.value.split("=", limit = 2).map { it.trim().trim('"', '\'', '`') }
                if (parts.size == 2 && looksLikeUrl(parts[1])) parts[1] else null
            }
            else -> null
        } ?: continue

        val normalized = url.trim().trim('"', '\'', '`')
        if (normalized.isBlank()) continue
        if (normalized in seen) continue
        seen.add(normalized)

        val confidence = when (f.type) {
            FindingType.WORDPRESS_AJAX_COMBINED -> "high"
            FindingType.FETCH, FindingType.AXIOS -> "high"
            FindingType.WP_AJAX_URL, FindingType.WP_API_SETTINGS -> "high"
            FindingType.API_BASE_VARIABLE, FindingType.BASE_URL_VARIABLE -> "medium"
            FindingType.JQUERY_AJAX, FindingType.JQUERY_SHORTHAND, FindingType.XHR_OPEN -> "medium"
            FindingType.GRAPHQL_ENDPOINT -> "high"
            FindingType.API_PATH -> "medium"
            else -> "low"
        }
        suggestions.add(EndpointSuggestion(normalized, classifyEndpoint(normalized), f.type.name, confidence))
    }

    return suggestions
}

private fun looksLikeUrl(s: String): Boolean {
    if (s.isBlank()) return false
    return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("/") || s.contains(".")
}
