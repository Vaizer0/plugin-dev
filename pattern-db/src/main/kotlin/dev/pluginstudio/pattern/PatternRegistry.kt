package dev.pluginstudio.pattern

data class ClusterDef(
    val id: String,
    val name: String,
    val description: String,
    val probes: List<CmsProbe>,
    val parents: List<String> = emptyList(),
    val confidenceThreshold: Double = 0.6,
    val samplePlugins: List<String> = emptyList(),
    val paginationType: String = "",
    val catalogType: String = "",   // "html", "api", "ajax"
    val chapterListType: String = ""
)

data class CmsProbe(
    val type: String,       // "html_contains", "script_contains", "meta_charset", "no_cms_markers"
    val pattern: String,
    val weight: Double = 1.0
)

data class PluginPatternSummary(
    val pluginId: String,
    val pluginName: String,
    val matchedCluster: String?,
    val confidence: Double,
    val probeResults: List<ProbeResult>,
    val functions: List<String>,
    val hasApiPatterns: Boolean,
    val hasWordPressPatterns: Boolean,
    val hasCustomEncoding: Boolean,
    val charset: String?
)

data class ProbeResult(
    val probe: CmsProbe,
    val matched: Boolean,
    val context: String = ""
)

data class ClusterMatch(
    val cluster: ClusterDef,
    val confidence: Double,
    val matchedProbes: Int,
    val totalProbes: Int
)

object PatternRegistry {
    val clusters = listOf(
        ClusterDef(
            id = "novelbin_clone",
            name = "NovelBin Clone",
            description = "Pirate novel CMS with .col-truyen-main structure",
            probes = listOf(
                CmsProbe("html_contains", ".col-truyen-main", 0.8),
                CmsProbe("html_contains", "list-chapter", 0.6),
                CmsProbe("html_contains", "#chapter-content", 0.6)
            ),
            confidenceThreshold = 0.6,
            samplePlugins = listOf("NovelBin", "NovelFull", "AllNovel", "FreeWebNovel", "ReadNovelFull", "NovelBuddy", "NovelArrow"),
            paginationType = "items_count",
            catalogType = "html",
            chapterListType = "html"
        ),
        ClusterDef(
            id = "madara_theme",
            name = "Madara (WordPress Theme)",
            description = "WordPress + Madara theme for web novels",
            probes = listOf(
                CmsProbe("html_contains", "wp-content/themes/madara", 0.5),
                CmsProbe("html_contains", ".c-tabs-item__content", 0.7),
                CmsProbe("html_contains", ".reading-content", 0.6),
                CmsProbe("html_contains", ".post-title h3", 0.5),
                CmsProbe("html_contains", ".page-item-detail", 0.6)
            ),
            parents = listOf("wordpress"),
            confidenceThreshold = 0.5,
            samplePlugins = listOf("NovelFire", "NovelHall", "NovelNice", "WuxiaWorldSite"),
            paginationType = "items_count",
            catalogType = "html",
            chapterListType = "ajax"
        ),
        ClusterDef(
            id = "wordpress",
            name = "WordPress (Generic)",
            description = "Generic WordPress site with AJAX chapter loading",
            probes = listOf(
                CmsProbe("html_contains", "wp-content/themes", 0.5),
                CmsProbe("html_contains", "wp-admin/admin-ajax.php", 0.8),
                CmsProbe("html_contains", "wpApiSettings", 0.7),
                CmsProbe("script_contains", "ajaxurl", 0.6)
            ),
            confidenceThreshold = 0.5,
            samplePlugins = listOf("Jaomix", "IFreedom", "BookHamster"),
            paginationType = "ajax_pages",
            catalogType = "html",
            chapterListType = "ajax"
        ),
        ClusterDef(
            id = "scribblehub",
            name = "ScribbleHub Style",
            description = "ScribbleHub and similar WordPress-based fiction sites",
            probes = listOf(
                CmsProbe("html_contains", ".search_main_box", 0.8),
                CmsProbe("html_contains", ".fic_title", 0.6),
                CmsProbe("html_contains", ".wi_fic_desc", 0.6),
                CmsProbe("html_contains", "#chp_raw", 0.7),
                CmsProbe("script_contains", "wi_getreleases_pagination", 0.8)
            ),
            parents = listOf("wordpress"),
            confidenceThreshold = 0.5,
            samplePlugins = listOf("ScribbleHub", "RoyalRoad"),
            paginationType = "next_link",
            catalogType = "html",
            chapterListType = "ajax"
        ),
        ClusterDef(
            id = "custom_api",
            name = "API-First",
            description = "Custom API with JSON responses, no HTML parsing for data",
            probes = listOf(
                CmsProbe("script_contains", "/api/", 0.5),
                CmsProbe("script_contains", "fetch(", 0.4),
                CmsProbe("html_contains", "__NEXT_DATA__", 0.5),
                CmsProbe("html_contains", "__NUXT__", 0.5),
                CmsProbe("no_cms_markers", "", 0.3)
            ),
            confidenceThreshold = 0.4,
            samplePlugins = listOf("RanobeLib", "RanobeHub"),
            paginationType = "api_meta",
            catalogType = "api",
            chapterListType = "api"
        ),
        ClusterDef(
            id = "chinese_site",
            name = "Chinese Site",
            description = "Chinese novel sites with GBK charset and custom encoding",
            probes = listOf(
                CmsProbe("meta_charset", "GBK", 0.8),
                CmsProbe("meta_charset", "gb2312", 0.8),
                CmsProbe("html_contains", ".pic_txt_list", 0.5),
                CmsProbe("script_contains", "base64", 0.3),
                CmsProbe("script_contains", "encodeURI", 0.3)
            ),
            confidenceThreshold = 0.5,
            samplePlugins = listOf("Quanben5", "Piaotia", "Novel543", "Shuba69", "Ttkan", "Twkan"),
            paginationType = "page_param",
            catalogType = "html",
            chapterListType = "html"
        )
    )

    fun findByPluginId(pluginId: String): ClusterDef? =
        clusters.firstOrNull { pluginId in it.samplePlugins }
}
