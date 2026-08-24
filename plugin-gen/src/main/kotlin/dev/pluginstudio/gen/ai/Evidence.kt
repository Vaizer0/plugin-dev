package dev.pluginstudio.gen.ai

import com.google.gson.Gson
import dev.pluginstudio.gen.ApiProbe
import dev.pluginstudio.gen.PageSnapshot
import dev.pluginstudio.gen.SiteCrawler
import org.jsoup.Jsoup

/**
 * Builds the compact, structured evidence package handed to AI models.
 * Everything is capped so prompts stay small; raw HTML is never included —
 * only distilled structural facts collected by the deterministic analyzer.
 */
object EvidenceBuilder {

    private const val MAX_STR = 160
    private fun s(v: String?, max: Int = MAX_STR) = (v ?: "").take(max)
    private fun gson() = Gson()

    fun build(corpus: SiteCrawler.CrawlResult, apis: List<ApiProbe>, searchQuery: String): Map<String, Any?> {
        val pages = corpus.pages.map { p ->
            val doc = Jsoup.parse(p.html, p.url)
            val title = doc.selectFirst("title")?.text()?.take(120)
            buildMap<String, Any?> {
                put("role", p.kind)
                put("url", s(p.url))
                put("viaBrowser", p.viaBrowser)
                title?.let { put("title", it) }
                p.dom?.cms?.let { cms ->
                    put("cms", mapOf(
                        "name" to cms.name,
                        "variant" to cms.themeVariant,
                        "confidence" to cms.confidence,
                        "cluster" to cms.clusterId
                    ))
                }
                val containers = (p.dom?.containers ?: emptyList()).take(4).map { c ->
                    mapOf(
                        "selector" to s(c.selector, 80),
                        "count" to c.count,
                        "purpose" to c.purpose,
                        "fields" to c.possibleFields.take(5).map { f ->
                            mapOf("role" to f.role, "sel" to s(f.selector, 60), "sample" to s(f.sampleValue, 40))
                        }
                    )
                }
                if (containers.isNotEmpty()) put("repeatingContainers", containers)
                if ((p.dom?.forms ?: emptyList()).isNotEmpty())
                    put("forms", p.dom!!.forms.take(3).map { f ->
                        mapOf("action" to s(f.action, 100), "method" to f.method, "inputs" to f.inputs.take(5))
                    })
                p.dom?.pagination?.let { pg -> put("pagination", mapOf("type" to pg.type, "selector" to s(pg.selector, 60), "sampleUrl" to s(pg.sampleUrl, 120))) }
                val jsonTypes = (p.dom?.embeddedJson ?: emptyList()).map { it.type }
                if (jsonTypes.isNotEmpty()) put("embeddedJson", jsonTypes)

                // selector candidates grouped by purpose (top 2 each)
                val selCands = p.js?.selectorResult?.candidates.orEmpty()
                    .mapValues { (_, v) -> v.take(2).map { c -> mapOf("sel" to s(c.selector, 70), "conf" to c.confidence, "n" to c.matchCount) } }
                    .filterValues { it.isNotEmpty() }
                if (selCands.isNotEmpty()) put("selectorCandidates", selCands)

                val eps = (p.js?.endpointSuggestions ?: emptyList()).take(8).map { e ->
                    mapOf("url" to s(e.url, 110), "category" to e.category.name, "conf" to e.confidence, "src" to e.source)
                }
                if (eps.isNotEmpty()) put("jsEndpoints", eps)
            }
        }

        val apiList = apis.map { a ->
            buildMap<String, Any?> {
                put("url", s(a.url, 140)); put("method", a.method); put("status", a.status)
                put("isJson", a.isJson); put("items", a.sampleCount)
                a.titleField?.let { put("titleField", it) }
                a.urlOrSlugField?.let { put("idField", it) }
                a.coverField?.let { put("coverField", it) }
                put("source", a.source)
            }
        }

        return mapOf(
            "baseUrl" to corpus.baseUrl,
            "searchQueryTested" to searchQuery,
            "pages" to pages,
            "verifiedApis" to apiList,
            "notes" to listOf(
                "All URLs/fields above were observed live during analysis.",
                "chapter role page = real chapter content page; book role = novel details page."
            )
        )
    }

    fun toJson(evidence: Map<String, Any?>): String =
        gson().toJson(evidence)
}
