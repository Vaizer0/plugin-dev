package dev.pluginstudio.gen.ai

import java.nio.file.Files
import java.nio.file.Path

/**
 * Lua API reference for AI prompts. Prefers the OFFICIAL guide shipped with
 * the user's NoveLA sources (lua-plugin-guide-en.md, ~68KB — truncated to keep
 * prompts bounded); falls back to the embedded quick reference.
 */
object LuaApiReference {
    @Volatile private var cached: String? = null

    fun text(): String {
        cached?.let { return it }
        val candidates = listOfNotNull(
            System.getenv("PLUGIN_GUIDE_FILE"),
            "../external-sources/lua-plugin-guide-en.md",
            "external-sources/lua-plugin-guide-en.md",
            System.getProperty("user.home") + "/external-sources/lua-plugin-guide-en.md"
        )
        for (p in candidates) {
            try {
                val f = Path.of(p)
                if (Files.exists(f)) {
                    val guide = Files.readString(f)
                    if (guide.length > 1000) {
                        val trimmed = if (guide.length > 18_000)
                            guide.take(11_000) + "\n\n[... middle sections omitted ...]\n\n" + guide.takeLast(6_000)
                        else guide
                        cached = "# OFFICIAL NOVELA LUA PLUGIN GUIDE (authoritative — follow it exactly)\n\n$trimmed" +
                            "\n\n== QUICK ENGINE REFERENCE (return shapes) ==\n" + TEXT
                        return cached!!
                    }
                }
            } catch (_: Exception) { }
        }
        cached = TEXT
        return cached!!
    }

    const val TEXT = """
== NOVELA LUA PLUGIN FORMAT ==
Top-level metadata variables:
  id = "source_id"            -- required, unique
  name = "Human Name"         -- required
  version = "1.0.0"
  baseUrl = "https://site"    -- required
  language = "en"
  icon = "https://.../icon.png"   -- optional

Required function signatures (return EXACTLY these shapes):
  function getCatalogList(index)                       -- index starts at 0
    return { items = { { title="", url="", cover="" }, ... }, hasNext = true/false }
  end
  function getCatalogSearch(index, query)
    return { items = {...}, hasNext = false }
  end
  function getBookTitle(bookUrl)      -> string or nil
  function getBookCoverImageUrl(bookUrl) -> string or nil
  function getBookDescription(bookUrl)   -> string or nil
  function getBookGenres(bookUrl)     -> { "Genre1", "Genre2" }
  function getChapterList(bookUrl)    -> { { title="", url="" }, ... }
  -- OR page-based alternative:
  function parsePage(bookUrl, page)   -- page starts at 0
    return { chapters = { {title="",url=""} }, totalPages = N }
  end
  function getChapterText(html, url)  -- receives raw HTML string of chapter page
    -> cleaned chapter text string
  Optional:
  function getFilterList() -> { { type="select", key="k", label="L", defaultValue="v",
      options={ {value="a",label="A"} } } }
  function getCatalogFiltered(index, filters) -- filters[k] is a string or array of strings
  function getChapterListHash(bookUrl) -> string

== ENGINE BUILTINS (the ONLY functions available; do not invent others) ==
Networking:
  http_get(url[, config]) -> { success=bool, body=str, statusCode=int }
      config table: { headers={...}, charset="UTF-8" }
  http_post(url, body, [config]) -> same shape
  http_get_batch({url1,url2}) -> table of results
HTML (jsoup-backed; elements expose .html .text .href .src and other attributes):
  html_parse(html) -> document element
  html_select(el_or_html, css_selector) -> array of elements
  html_select_first(el_or_html, selector) -> element or nil
  html_attr(el_or_html, selector, attrName) -> string ("")
  html_text(el_or_html) / el.html / el.text also work
  html_remove(html, sel1, sel2, ...) -> filtered html string
Strings/JSON:
  json_parse(str) -> table   |  json_stringify(tbl) -> str
  regex_match(s, pattern) / regex_replace(s, pattern, repl)   -- Java regex syntax
  string_normalize / string_clean / string_trim / string_split
  string_starts_with / string_ends_with
  unescape_unicode(s)
URLs:
  url_encode(s [,charset]) / url_encode_charset(s,cs) / url_resolve(base, rel)
Misc:
  base64_encode/decode, aes_decrypt, detect_pagination(html),
  get_cookies/set_cookies, get_preference/set_preference,
  os_time(), sleep(ms), log_info/log_error

== CONVENTIONS ==
- Always define helpers: absUrl(href) using url_resolve(baseUrl,...), and a cached
  fetchPage(url) wrapping http_get with one retry.
- Chapter/content extraction should strip ads: html_remove(html,"script","style", ...)
  then select the content node, then normalize whitespace.
- Pagination: catalog page number = index + 1 (append ?page= or /page/N/ as the site uses).
- Emit selectors as literal strings inside each function so tooling can display them.
- NEVER require authentication, cookies from the developer, or endpoints not present in evidence.
"""
}
