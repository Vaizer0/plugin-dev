package dev.pluginstudio.engine

object SelectorExtractor {

    private val TARGET_FUNCTIONS = setOf(
        "getCatalogList", "getCatalogSearch", "getCatalogFiltered", "getBookTitle",
        "getBookCoverImageUrl", "getBookDescription", "getBookGenres",
        "getChapterList", "getChapterText", "getChapterListHash",
        "parsePage", "parseCatalogItems", "parseChaptersFromArchive",
        "fetchAjaxPage"
    )

    private val FUNC_DEF = Regex("""(?:local\s+)?function\s+(get\w+|parse\w+|fetch\w+)\s*\(""")

    // html_select(html, "sel"), html_select_first(html, "sel"), html_attr(html, "sel", "attr"),
    // html_remove(html, "sel1", "sel2", ...) — captures the 2nd arg (first selector)
    private val HTML_SEL = Regex("""(html_select|html_select_first|html_attr|html_remove)\s*\(\s*[^,]+,\s*"((?:[^"\\]|\\.)*)"""")

    // Single-quoted variant
    private val HTML_SEL_SQ = Regex("""(html_select|html_select_first|html_attr|html_remove)\s*\(\s*[^,]+,\s*'((?:[^'\\]|\\.)*)'""")

    // Matches a complete double-quoted string: "contents"
    private val DQ_STRING = Regex("""((?:[^"\\]|\\.)*)""")

    // Variable assignments: local var = "selector"
    private val VAR_ASSIGN = Regex("""local\s+\w+\s*=\s*"((?:[^"\\]|\\.)*)"""")

    fun extract(source: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        val lines = source.lines()

        val funcDefs = mutableListOf<Pair<String, Int>>()
        for ((i, line) in lines.withIndex()) {
            FUNC_DEF.findAll(line).forEach { m ->
                val name = m.groupValues[1]
                if (name in TARGET_FUNCTIONS) funcDefs.add(name to i)
            }
        }

        fun closestFunc(line: Int): String? {
            var best: String? = null; var bestLine = -1
            for ((name, l) in funcDefs) {
                if (l <= line && l > bestLine) { best = name; bestLine = l }
            }
            return best
        }

        for ((i, line) in lines.withIndex()) {
            val ctx = closestFunc(i) ?: continue

            // html_select / select_first / attr / remove — 2nd arg is a selector
            for (m in HTML_SEL.findAll(line)) {
                val sel = m.groupValues[2].trim()
                if (sel.isNotBlank()) result.getOrPut(ctx) { mutableSetOf() }.add(sel)
            }
            for (m in HTML_SEL_SQ.findAll(line)) {
                val sel = m.groupValues[2].trim()
                if (sel.isNotBlank()) result.getOrPut(ctx) { mutableSetOf() }.add(sel)
            }

            // html_remove: all double-quoted strings on the line after the first ARE selectors
            if (line.contains("html_remove")) {
                val dq = DQ_STRING.findAll(line).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
                for (sel in dq.drop(1)) {
                    if (sel.isNotBlank() && sel.length > 1) {
                        result.getOrPut(ctx) { mutableSetOf() }.add(sel)
                    }
                }
            }

            // Variable assignment: local var = "css selector"
            for (m in VAR_ASSIGN.findAll(line)) {
                val sel = m.groupValues[1].trim()
                if (sel.length >= 3 && looksLikeCssSelector(sel)) {
                    result.getOrPut(ctx) { mutableSetOf() }.add(sel)
                }
            }
        }

        return result.mapValues { it.value.toList() }
    }

    private fun looksLikeCssSelector(s: String): Boolean {
        if (s.length < 2) return false
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("//") || s.startsWith("/")) return false
        val cssIndicators = setOf('.', '#', ':', '[', '>', ' ')
        if (cssIndicators.any { s.contains(it) }) return true
        val htmlTags = setOf("div", "span", "ul", "li", "ol", "a", "img", "h1", "h2", "h3", "h4", "h5", "h6", "p", "table", "tr", "td", "th", "section", "article", "header", "footer", "nav", "main", "aside", "form", "input", "select", "option", "button", "label", "meta", "link", "script", "style", "template", "body", "head", "html")
        val first = s.split(Regex("[\\s.#\\[:]+")).firstOrNull()?.lowercase()
        if (first in htmlTags) return true
        if (s.contains("[") && s.contains("]")) return true
        return false
    }
}
