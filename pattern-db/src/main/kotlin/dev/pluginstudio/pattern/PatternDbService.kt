package dev.pluginstudio.pattern

import dev.pluginstudio.engine.LuaSourceAdapter
import dev.pluginstudio.engine.PluginLoader
import dev.pluginstudio.analyzer.AnalyzeRequest
import dev.pluginstudio.analyzer.SiteAnalyzer

data class PatternDbResult(
    val plugins: List<PluginPatternSummary>,
    val matches: Map<String, List<ClusterMatch>>,
    val clusterSummary: List<ClusterSummary>
)

data class ClusterSummary(
    val clusterId: String,
    val clusterName: String,
    val matchedPluginCount: Int,
    val matchConfidence: Double
)

class PatternDbService(
    private val pluginLoader: PluginLoader,
    private val siteAnalyzer: SiteAnalyzer? = null
) {
    private val analyzer = PluginPatternAnalyzer()

    fun analyzeAllPlugins(): List<PluginPatternSummary> {
        val adapters = pluginLoader.loadAllPlugins().loaded
        return adapters.map { analyzer.analyze(it) }
    }

    fun analyzeWithMatching(htmlSample: String? = null): PatternDbResult {
        val adapters = pluginLoader.loadAllPlugins().loaded
        val summaries = mutableListOf<PluginPatternSummary>()
        val allMatches = mutableMapOf<String, List<ClusterMatch>>()

        for (adapter in adapters) {
            val summary = analyzer.analyze(adapter)
            val matches = if (htmlSample != null) {
                analyzer.matchCluster(adapter, htmlSample)
            } else {
                analyzer.matchCluster(adapter, null)
            }
            allMatches[adapter.id] = matches

            val best = matches.maxByOrNull { it.confidence }
            summaries.add(summary.copy(
                matchedCluster = best?.cluster?.id,
                confidence = best?.confidence ?: 0.0,
                probeResults = emptyList()
            ))
        }

        val clusterSummary = PatternRegistry.clusters.map { cluster ->
            val matched = summaries.filter { it.matchedCluster == cluster.id }
            val avgConfidence = if (matched.isEmpty()) 0.0
            else matched.sumOf { it.confidence } / matched.size
            ClusterSummary(
                clusterId = cluster.id,
                clusterName = cluster.name,
                matchedPluginCount = matched.size,
                matchConfidence = avgConfidence
            )
        }

        return PatternDbResult(
            plugins = summaries,
            matches = allMatches,
            clusterSummary = clusterSummary
        )
    }

    fun analyzeUrl(url: String): PatternDbResult {
        val report = siteAnalyzer?.analyze(AnalyzeRequest(url = url))
        val html = report?.let { null } // We don't keep full HTML here
        val result = analyzeWithMatching(null)

        val siteFindings = buildString {
            if (report != null) {
                appendLine("URL: $url")
                appendLine("JS Files: ${report.summary.totalJsFiles}")
                appendLine("Endpoints: ${report.summary.totalEndpoints}")
                report.endpointSuggestions.forEach {
                    appendLine("  ${it.category.name}: ${it.url}")
                }
            }
        }

        return result
    }
}
