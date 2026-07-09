package dev.pluginstudio.pattern

import dev.pluginstudio.engine.LuaSourceAdapter
import dev.pluginstudio.engine.SourceInterface

class PluginPatternAnalyzer {

    fun analyze(adapter: LuaSourceAdapter): PluginPatternSummary {
        val functions = extractFunctions(adapter)
        val hasApi = functions.any { findApiHints(adapter) }
        val hasWp = findWordPressHints(adapter)
        val hasCustomEncode = findCustomEncodingHints(adapter)

        return PluginPatternSummary(
            pluginId = adapter.id,
            pluginName = adapter.name,
            matchedCluster = null,
            confidence = 0.0,
            probeResults = emptyList(),
            functions = functions,
            hasApiPatterns = hasApi,
            hasWordPressPatterns = hasWp,
            hasCustomEncoding = hasCustomEncode,
            charset = adapter.charset.takeIf { it.uppercase() != "UTF-8" }
        )
    }

    private fun extractFunctions(adapter: LuaSourceAdapter): List<String> {
        val functions = mutableListOf<String>()
        val knownFunctions = listOf(
            "getCatalogList", "getCatalogSearch", "getBookTitle",
            "getBookCoverImageUrl", "getBookDescription", "getBookGenres",
            "getChapterList", "getChapterText", "parsePage",
            "getChapterListHash", "getFilterList", "getCatalogFiltered",
            "getSettingsSchema", "transformCatalogCover"
        )
        for (fn in knownFunctions) {
            if (adapter.hasFunction(fn)) functions.add(fn)
        }
        return functions
    }

    private fun findApiHints(adapter: LuaSourceAdapter): Boolean {
        return adapter.hasFunction("getChapterListHash") ||
                adapter.hasFunction("parsePage")
    }

    private fun findWordPressHints(adapter: LuaSourceAdapter): Boolean {
        val hasAjaxChapter = adapter.hasFunction("parsePage")
        val hasFilters = adapter is SourceInterface.FilterableCatalog
        return hasAjaxChapter && hasFilters
    }

    private fun findCustomEncodingHints(adapter: LuaSourceAdapter): Boolean {
        val charset = adapter.charset.uppercase()
        return charset == "GBK" || charset == "GB2312" || charset == "WINDOWS-1251"
    }

    fun matchCluster(adapter: LuaSourceAdapter, htmlSample: String? = null): List<ClusterMatch> {
        val analysis = analyze(adapter)
        return PatternRegistry.clusters.mapNotNull { cluster ->
            val results = cluster.probes.map { probe ->
                val matched = evaluateProbe(probe, adapter, htmlSample)
                ProbeResult(probe, matched)
            }
            val matchedProbes = results.count { it.matched }
            val totalProbes = results.size
            val rawConfidence = results.sumOf { r ->
                if (r.matched) r.probe.weight else 0.0
            }
            val parentPenalty = cluster.parents.size * 0.1
            val confidence = (rawConfidence / totalProbes.coerceAtLeast(1)) - parentPenalty

            if (confidence >= cluster.confidenceThreshold) {
                ClusterMatch(cluster, confidence, matchedProbes, totalProbes)
            } else null
        }.sortedByDescending { it.confidence }
    }

    private fun evaluateProbe(probe: CmsProbe, adapter: LuaSourceAdapter, html: String?): Boolean {
        return when (probe.type) {
            "html_contains" -> html?.contains(probe.pattern, ignoreCase = true) ?: false
            "script_contains" -> html?.contains(probe.pattern, ignoreCase = true) ?: false
            "meta_charset" -> adapter.charset.equals(probe.pattern, ignoreCase = true)
            "no_cms_markers" -> {
                if (html == null) return false
                val cmsPatterns = listOf("wp-content", "wordpress", "joomla", "drupal")
                cmsPatterns.none { html.contains(it, ignoreCase = true) }
            }
            else -> false
        }
    }
}
