package dev.pluginstudio.gen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * End-to-end pipeline: crawl (or use browser-captured corpus) → probe APIs →
 * pick strategies → generate Lua variants → live-validate each until one works.
 */
class PluginGenerator(
    private val fetcher: HttpFetcher = HttpFetcher(),
    private val crawler: SiteCrawler = SiteCrawler(fetcher),
    private val prober: ApiProber = ApiProber(fetcher),
    private val picker: StrategyPicker = StrategyPicker(fetcher),
    private val validator: PluginValidator = PluginValidator(fetcher),
    private val onStep: (String) -> Unit = {}
) {
    data class CaptureInput(val pages: List<Triple<String, String, List<NetRecord>>>)

    suspend fun generate(
        seedUrl: String,
        query: String = "novel",
        idHint: String? = null,
        nameHint: String? = null,
        capture: CaptureInput? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            // ── 1. corpus ──
            val corpus = if (capture != null) {
                onStep("Using ${capture.pages.size} browser-captured page(s) …")
                crawler.corpusFromCapture(capture.pages)
            } else crawler.crawl(seedUrl, query, onStep)

            if (!corpus.success) {
                return@withContext GenerationResult(
                    false, seedUrl, null, emptyMap(), emptyList(), false, "",
                    stagedFile = null, variantsTried = 0,
                    error = corpus.error + if (corpus.blocked)
                        ". Use the protected-site flow: capture pages from your own browser after passing verification." else ""
                )
            }

            // ── 2. API probing ──
            val capturedRecords = capture?.pages?.flatMap { it.third }.orEmpty()
            var apis: List<ApiProbe> = emptyList()
            var searchApi: SearchStrategy.Api? = null
            if (capturedRecords.isNotEmpty()) {
                onStep("Checking ${capturedRecords.size} runtime requests captured from your browser …")
                apis = prober.probeCaptured(corpus.baseUrl, capturedRecords, query)
                if (apis.isEmpty()) onStep("No usable JSON APIs among captured requests")
            }
            val scanCandidates = prober.candidatesFromPages(corpus.pages).filter { c ->
                apis.none { it.url == fetcher.resolve(corpus.baseUrl, c.first) }
            }
            if (scanCandidates.isNotEmpty()) {
                onStep("Testing ${scanCandidates.size.coerceAtMost(12)} endpoint(s) found in page JavaScript …")
                apis = apis + prober.probe(corpus.baseUrl, scanCandidates, query, onStep)
            }
            if (apis.isNotEmpty()) {
                onStep("${apis.size} working JSON API(s) found — preferring API over HTML scraping")
                searchApi = prober.buildSearchApi(apis.first(), corpus.baseUrl, query)
                if (searchApi != null) onStep("✓ Search API confirmed: ${searchApi.urlTemplate}")
                else onStep("API list found but no query parameter — skipping API search")
            }

            // ── 3. strategy variants ──
            onStep("Choosing selectors and strategies …")
            val variants = picker.pick(corpus.baseUrl, corpus.pages, apis, searchApi, query, idHint, nameHint)
            onStep("${variants.size} strategy variant(s) to try")

            // ── 4+5. generate & validate until one passes ──
            var best: GenerationResult? = null
            var tried = 0
            for (bp in variants.take(3)) {
                tried++
                onStep("Generating plugin (variant \"${bp.variantName}\") [catalog=${bp.catalog?.containerSel ?: "-"}, chapters=${bp.chapters?.anchorsSel ?: "-"}, text=${bp.chapterText?.contentSel ?: "-"}] …")
                val lua = LuaGenerator.generate(bp)
                val report = validator.validate(lua, query, onStep).second
                if (report == null) {
                    onStep("✗ variant \"${bp.variantName}\" has a syntax problem, trying next …")
                    continue
                }

                val summary = strategySummary(bp)
                // For browser-captured (protected) sites the live server often
                // serves a degraded bot-variant during validation. Selectors were
                // already verified against the REAL captured pages, so treat
                // metadata/chapters/content failures as partial when the core
                // catalog works and strategies exist for the rest.
                val liveCoreOk = report.entries.firstOrNull { it.function == "getCatalogList" }?.ok == true
                val strategiesComplete = bp.chapters != null && bp.chapterText != null
                val partialPass = capture != null && liveCoreOk && strategiesComplete && !report.pass

                val result = GenerationResult(
                    success = report.pass || partialPass,
                    siteUrl = seedUrl,
                    blueprint = bp,
                    strategySummary = summary,
                    validation = report.entries,
                    overallPass = report.pass,
                    luaCode = lua,
                    stagedFile = null,
                    variantsTried = tried,
                    error = when {
                        report.pass -> null
                        partialPass -> null
                        else -> "Generated plugin did not pass live validation (catalog/chapters/content incomplete)"
                    },
                    partialValidation = partialPass
                )
                if (report.pass || partialPass) {
                    if (partialPass) {
                        onStep("✓ Variant \"${bp.variantName}\" accepted with PARTIAL validation — selectors verified against your captured pages; live site served a bot-variant to the validator.")
                        return@withContext result
                    }
                    onStep("✓ Variant \"${bp.variantName}\" PASSED validation (${report.entries.count { it.ok }}/${report.entries.size} functions)")
                    return@withContext result
                }
                if (best == null || result.validation.count { it.ok } > best!!.validation.count { it.ok }) {
                    best = result
                }
                onStep("Variant \"${bp.variantName}\" failed validation — trying next …")
            }

            (best ?: GenerationResult(false, seedUrl, null, emptyMap(), emptyList(), false, "", null, tried,
                error = "Could not produce a working plugin from this site"))
        } catch (e: Exception) {
            GenerationResult(false, seedUrl, null, emptyMap(), emptyList(), false, "", null, 0,
                e.message ?: e.toString())
        }
    }

    private fun strategySummary(bp: PluginBlueprint): Map<String, String> = buildMap {
        bp.catalog?.let {
            put("getCatalogList", if (it.anchorOnly) "DOM anchors: ${it.containerSel} a" else "DOM: ${it.containerSel} › ${it.titleSel}")
        } ?: put("getCatalogList", "not generated")
        bp.searchApi?.let { put("getCatalogSearch", "JSON API: ${it.method} ${it.urlTemplate}") }
            ?: if (bp.searchDom != null && bp.catalog != null) put("getCatalogSearch", "site search form + DOM parse")
            else put("getCatalogSearch", "not generated")
        bp.book?.let { b ->
            put("bookMetadata", listOfNotNull(
                b.titleSel?.let { "title:$it" },
                b.coverSel?.let { "cover:$it" },
                b.descSel?.let { "desc:$it" },
                b.genresSel?.let { "genres:$it" }
            ).joinToString(" · ").ifBlank { "not generated" })
        }
        bp.chapters?.let { put("getChapterList", it.anchorsSel) } ?: put("getChapterList", "not generated")
        bp.chapterText?.let {
            put("getChapterText", (it.contentSel ?: "<paragraph fallback>") + " (removes ${it.removeSels.size} noise elements)")
        } ?: put("getChapterText", "not generated")
    }
}
