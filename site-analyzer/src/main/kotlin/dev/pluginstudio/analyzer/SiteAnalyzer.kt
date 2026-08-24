package dev.pluginstudio.analyzer

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.TimeUnit

data class AnalyzeRequest(
    val url: String,
    val maxJsFiles: Int = 20
)

class SiteAnalyzer(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    /** How many same-origin external scripts to download & scan per page. */
    var maxExternalScripts: Int = 20
) {
    private val jsScanner = JsScanner()
    private val selectorAnalyzer = SelectorAnalyzer()

    fun analyze(request: AnalyzeRequest): SiteAnalysisReport {
        val html = downloadPage(request.url) ?: return errorReport(request.url, "Failed to download page")
        return analyzeHtml(request.url, html)
    }

    fun analyzeHtml(pageUrl: String, html: String, domReport: DomAnalysisReport? = null): SiteAnalysisReport {
        val doc = Jsoup.parse(html, pageUrl)
        val pageHost = extractHost(pageUrl)

        // inline scripts first — free, no downloads
        val jsResults = mutableListOf<JsFileResult>()
        val inlineScript = doc.select("script:not([src])").eachText().joinToString("\n")
        if (inlineScript.isNotBlank()) {
            jsResults.add(jsScanner.scan(inlineScript, "$pageUrl (inline)"))
        }

        // external scripts — only same-origin, capped, each with a whole-call deadline
        val scriptSrcUrls = doc.select("script[src]").eachAttr("src")
            .filter { it.isNotBlank() }
            .map { resolveUrl(pageUrl, it) }
            .filter { isSameHost(it, pageHost) }
            .take(maxExternalScripts)

        for (url in scriptSrcUrls) {
            val jsCode = downloadPage(url) ?: continue
            jsResults.add(jsScanner.scan(jsCode, url))
        }

        val allFindings = jsResults.flatMap { it.findings }
        val endpointSuggestions = suggestEndpoints(allFindings)

        val selResult = selectorAnalyzer.analyze(html, pageUrl, domReport)

        val summary = AnalysisSummary(
            totalJsFiles = jsResults.size,
            totalFindings = allFindings.size,
            totalEndpoints = endpointSuggestions.size,
            totalCssSelectors = selResult.candidates.values.sumOf { it.size },
            warningMessages = emptyList()
        )

        return SiteAnalysisReport(
            pageUrl = pageUrl,
            jsFiles = jsResults,
            endpointSuggestions = endpointSuggestions,
            cssSelectors = emptyList(),
            selectorResult = selResult,
            summary = summary
        )
    }

    private fun extractHost(url: String): String = try {
        URI(url).host?.lowercase() ?: ""
    } catch (_: Exception) { "" }

    private fun isSameHost(url: String, pageHost: String): Boolean {
        if (pageHost.isBlank()) return true
        return try {
            val host = URI(url).host?.lowercase() ?: return true
            host == pageHost || host.endsWith(".$pageHost")
        } catch (_: Exception) { true }
    }

    private fun downloadPage(url: String): String? {
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            // Whole-call deadline: drip-feed/tarpit responses must never stall analysis.
            val call = httpClient.newCall(request)
            call.timeout().deadline(10, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveUrl(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        if (href.startsWith("//")) return "https:$href"
        val baseUrl = base.substringBeforeLast("/")
        return if (href.startsWith("/")) {
            val scheme = base.substringBefore("://") + "://"
            val host = base.substringAfter("://").substringBefore("/")
            "$scheme$host$href"
        } else "$baseUrl/$href"
    }

    private fun errorReport(url: String, error: String): SiteAnalysisReport {
        return SiteAnalysisReport(
            pageUrl = url,
            jsFiles = emptyList(),
            endpointSuggestions = emptyList(),
            cssSelectors = emptyList(),
            summary = AnalysisSummary(
                totalJsFiles = 0,
                totalFindings = 0,
                totalEndpoints = 0,
                totalCssSelectors = 0,
                warningMessages = listOf(error)
            )
        )
    }
}
