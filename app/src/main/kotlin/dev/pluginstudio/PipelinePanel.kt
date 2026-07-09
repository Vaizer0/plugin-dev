package dev.pluginstudio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pluginstudio.engine.*
import dev.pluginstudio.engine.models.*
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.net.URI
import java.util.*

private fun refUrl(url: String): String = try { val u = URI(url); "${u.scheme}://${u.host}/" } catch (_: Exception) { url }

data class BookBrief(val title: String, val url: String, val coverUrl: String, val idx: Int)

enum class PipelineView { CATALOG, BOOK, CHAPTER }

@Composable
fun PipelineContent(plugin: LuaSourceAdapter) {
    val scope = rememberCoroutineScope()
    val luaEngine = remember { GlobalContext.get().get<LuaEngine>() }
    val selectors = remember(plugin) { plugin.selectors }
    val clrPrimary = MaterialTheme.colorScheme.primary
    val clrError = MaterialTheme.colorScheme.error
    val clrSurface = MaterialTheme.colorScheme.surfaceVariant
    val clrOnSurface = MaterialTheme.colorScheme.onSurface
    val clrOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // nav
    var view by remember { mutableStateOf(PipelineView.CATALOG) }
    var bookUrl by remember { mutableStateOf("") }
    var bookName by remember { mutableStateOf("") }
    var chUrl by remember { mutableStateOf("") }
    var chTitle by remember { mutableStateOf("") }
    var prevView by remember { mutableStateOf<PipelineView?>(null) }
    var navToken by remember { mutableStateOf(0) }

    // http logs
    val appState = LocalAppState.current
    var showHttpLogs by remember { mutableStateOf(false) }

    // catalog
    val catalogModes = buildMap {
        if (plugin.hasFunction("getCatalogList")) put("catalog", "Catalog")
        if (plugin.hasFunction("getCatalogSearch")) put("search", "Search")
        if (plugin is SourceInterface.FilterableCatalog) put("filtered", "Filtered")
    }
    var catalogMode by remember { mutableStateOf(catalogModes.keys.firstOrNull() ?: "catalog") }
    var searchQuery by remember { mutableStateOf("") }
    var books by remember { mutableStateOf<List<BookBrief>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var catalogLog by remember { mutableStateOf<List<Pair<String, Color>>>(emptyList()) }

    // filters
    var filterList by remember { mutableStateOf<List<LuaFilter>?>(null) }
    var filterValues by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    // book
    var bTitle by remember { mutableStateOf<String?>(null) }
    var bCover by remember { mutableStateOf<String?>(null) }
    var bDesc by remember { mutableStateOf<String?>(null) }
    var bGenres by remember { mutableStateOf<List<String>?>(null) }
    var bChapters by remember { mutableStateOf<List<ChapterResult>?>(null) }
    var bLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var bLoading by remember { mutableStateOf(false) }

    // chapter
    var chapterText by remember { mutableStateOf<String?>(null) }
    var chLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var chLoading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Surface(color = clrSurface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pipeline", style = MaterialTheme.typography.labelMedium, color = clrPrimary)
                if (view == PipelineView.BOOK || view == PipelineView.CHAPTER) {
                    Text(" › ", style = MaterialTheme.typography.labelMedium)
                    Text(bookName.take(50), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (view == PipelineView.CHAPTER) {
                    Text(" › ", style = MaterialTheme.typography.labelMedium)
                    Text(chTitle.take(40), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(4.dp))
                if (view != PipelineView.CATALOG) {
                    TextButton(onClick = { prevView?.let { view = it }; prevView = null }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("← Back", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showHttpLogs = !showHttpLogs }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(if (showHttpLogs) "Hide HTTP" else "HTTP (${appState.httpLogs.size})", style = MaterialTheme.typography.labelSmall, color = clrPrimary)
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        if (showHttpLogs) {
            Surface(color = Color(0xFF1A1A2E), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(200.dp)) {
                SelectionContainer {
                    LazyColumn(modifier = Modifier.padding(4.dp)) {
                        items(appState.httpLogs.take(100)) { entry ->
                            val c = if (entry.success) Color(0xFF4CAF50) else clrError
                            Column {
                                Text("${entry.method} ${entry.url} [${entry.statusCode}] ${entry.durationMs}ms body=${entry.responseBody.length}b ct=${entry.contentType.take(30)}",
                                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!entry.success || entry.responseBody.isNotBlank()) {
                                    val preview = entry.responseBody.take(500).replace("\n", "\\n")
                                    Text("  body: $preview", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Color(0xFF888888), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        when (view) {
            PipelineView.CATALOG -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    catalogModes.forEach { (key, label) ->
                        FilterChip(selected = catalogMode == key, onClick = {
                            catalogMode = key
                            if (key == "filtered" && filterList == null && plugin is SourceInterface.FilterableCatalog) {
                                scope.launch {
                                    val resp = plugin.getFilterList()
                                    if (resp is Response.Success) {
                                        filterList = resp.data
                                        filterValues = resp.data.associate { it.key to listOfNotNull(it.defaultValue) }
                                    }
                                }
                            }
                        }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                        Spacer(Modifier.width(4.dp))
                    }
                    if (catalogMode == "search") {
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("query") }, singleLine = true, modifier = Modifier.weight(1f).height(48.dp), textStyle = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(4.dp))
                    }
                    Button(onClick = {
                        loading = true; books = null; catalogLog = emptyList()
                        scope.launch {
                            val start = System.currentTimeMillis()
                            try {
                                val resp = when (catalogMode) {
                                    "catalog" -> plugin.getCatalogList(0)
                                    "search" -> plugin.getCatalogSearch(0, searchQuery)
                                    "filtered" -> (plugin as? SourceInterface.FilterableCatalog)?.getCatalogFiltered(0, ActiveFilters(filterValues))
                                    else -> null
                                }
                                val dur = System.currentTimeMillis() - start
                                when (resp) {
                                    is Response.Success -> {
                                        val list = resp.data.list.mapIndexed { i, br -> BookBrief(br.title, br.url, br.coverImageUrl, i) }
                                        books = list
                                        catalogLog = listOf("✓ ${when (catalogMode) { "catalog" -> "getCatalogList"; "search" -> "getCatalogSearch"; "filtered" -> "getCatalogFiltered"; else -> "?" }} ($dur ms) — ${list.size} books" to clrPrimary)
                                    }
                                    is Response.Error -> catalogLog = listOf("✗ ${when (catalogMode) { "catalog" -> "getCatalogList"; "search" -> "getCatalogSearch"; "filtered" -> "getCatalogFiltered"; else -> "?" }} ($dur ms) — ${resp.message}" to clrError)
                                    null -> catalogLog = listOf("✗ no function for mode: $catalogMode" to clrError)
                                }
                            } catch (e: Exception) {
                                catalogLog = listOf("✗ ${when (catalogMode) { "catalog" -> "getCatalogList"; "search" -> "getCatalogSearch"; "filtered" -> "getCatalogFiltered"; else -> "?" }} — ${e.message}" to clrError)
                            }
                            loading = false
                        }
                    }, enabled = !loading && (catalogMode != "search" || searchQuery.isNotBlank()), modifier = Modifier.height(40.dp)) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Load", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (catalogMode == "filtered" && filterList != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(color = clrSurface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Filters:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                            filterList!!.forEach { filter ->
                                key(filter.key) {
                                    Spacer(Modifier.height(4.dp))
                                    val currentVal = filterValues[filter.key]?.firstOrNull() ?: filter.defaultValue ?: ""
                                    val displayLabel = filter.options.find { it.value == currentVal }?.label ?: currentVal
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${filter.label}: ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(100.dp))
                                        Box {
                                            var expanded by remember { mutableStateOf(false) }
                                            Surface(onClick = { expanded = true }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.height(34.dp)) {
                                                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(displayLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                    Text(" ▼", style = MaterialTheme.typography.labelSmall, color = clrPrimary)
                                                }
                                            }
                                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                                filter.options.forEach { opt ->
                                                    DropdownMenuItem(text = { Text(opt.label, style = MaterialTheme.typography.bodySmall) },
                                                        onClick = {
                                                            filterValues = filterValues + (filter.key to listOf(opt.value))
                                                            expanded = false
                                                        })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                val curBooks = books
                if (curBooks != null) {
                    SelectionContainer {
                        Column(modifier = Modifier.fillMaxSize()) {
                            catalogLog.forEach { (msg, color) -> Text(msg, style = MaterialTheme.typography.labelSmall, color = color) }

                            val funcKey = when (catalogMode) {
                                "catalog" -> "getCatalogList"
                                "search" -> "getCatalogSearch"
                                "filtered" -> "getCatalogFiltered"
                                else -> null
                            }
                            val catSel = funcKey?.let { selectors[it] }
                            if (!catSel.isNullOrEmpty()) {
                                Text("Selectors: ${catSel.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = clrPrimary)
                            }

                            Spacer(Modifier.height(4.dp))
                            Text("Results (${curBooks.size}):", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(curBooks) { b ->
                                    Card(onClick = {
                                        bookUrl = b.url; bookName = b.title
                                        bTitle = null; bCover = null; bDesc = null; bGenres = null; bChapters = null; bLog = emptyList()
                                        prevView = PipelineView.CATALOG; view = PipelineView.BOOK; navToken++
                                    }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Column(Modifier.padding(10.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("${b.idx + 1}.", style = MaterialTheme.typography.labelSmall, color = clrOnSurfaceVariant, modifier = Modifier.width(24.dp))
                                                Text(if (b.title.isNotBlank()) b.title else "(empty title)",
                                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                                    color = if (b.title.isNotBlank()) clrOnSurface else clrError,
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(b.url, style = MaterialTheme.typography.labelSmall, color = clrOnSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Spacer(Modifier.height(1.dp))
                                            Text(if (b.coverUrl.isNotBlank()) "Cover: ${b.coverUrl}" else "Cover: MISSING",
                                                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                                                color = if (b.coverUrl.isNotBlank()) clrPrimary else clrError,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (!loading) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("Click Load to fetch catalog", style = MaterialTheme.typography.bodySmall, color = clrOnSurfaceVariant)
                    }
                }
            }

            PipelineView.BOOK -> {
                LaunchedEffect(bookUrl, navToken) {
                    if (bTitle != null && navToken == 0) return@LaunchedEffect
                    bLoading = true; bLog = emptyList()
                    bTitle = null; bCover = null; bDesc = null; bGenres = null; bChapters = null

                    val bookFuncs = listOf("getBookTitle", "getBookCoverImageUrl", "getBookDescription", "getBookGenres")
                    val chapterFuncs = listOf("getChapterList", "parsePage")

                    suspend fun runFunc(name: String, block: suspend () -> Any?) {
                        val start = System.currentTimeMillis()
                        try {
                            val resp = block()
                            val dur = System.currentTimeMillis() - start
                            when (resp) {
                                is Response.Error -> bLog = bLog + "✗ $name ($dur ms) — ${resp.message}"
                                is Response.Success<*> -> {
                                    val data = resp.data
                                    val logMsg = when (data) {
                                        is String -> "\"${data.take(100)}\""
                                        is List<*> -> "${data.size} items"
                                        is SourceInterface.Catalog.PagedChapterResult -> "${data.chapters.size} chapters"
                                        else -> data?.toString()?.take(100) ?: "null"
                                    }
                                    bLog = bLog + "✓ $name ($dur ms) — $logMsg"
                                    when (name) {
                                        "getBookTitle" -> bTitle = data as? String
                                        "getBookCoverImageUrl" -> bCover = data as? String
                                        "getBookDescription" -> bDesc = data as? String
                                        "getBookGenres" -> bGenres = data as? List<String>
                                        "getChapterList" -> bChapters = data as? List<ChapterResult>
                                        "parsePage" -> bChapters = (data as? SourceInterface.Catalog.PagedChapterResult)?.chapters
                                    }
                                }
                                null -> bLog = bLog + "✗ $name — returned null"
                                else -> bLog = bLog + "✓ $name ($dur ms) — ${resp.toString().take(100)}"
                            }
                        } catch (e: Exception) {
                            bLog = bLog + "✗ $name — ${e.message}"
                        }
                    }

                    for (fn in bookFuncs.filter { plugin.hasFunction(it) }) {
                        runFunc(fn) {
                            when (fn) {
                                "getBookTitle" -> plugin.getBookTitle(bookUrl)
                                "getBookCoverImageUrl" -> plugin.getBookCoverImageUrl(bookUrl)
                                "getBookDescription" -> plugin.getBookDescription(bookUrl)
                                "getBookGenres" -> plugin.getBookGenres(bookUrl)
                                else -> null
                            }
                        }
                    }
                    for (fn in chapterFuncs.filter { plugin.hasFunction(it) }) {
                        runFunc(fn) {
                            when (fn) {
                                "getChapterList" -> plugin.getChapterList(bookUrl)
                                "parsePage" -> plugin.parsePage(bookUrl, 0)
                                else -> null
                            }
                        }
                    }
                    bLoading = false
                }
                if (bLoading) { Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { CircularProgressIndicator() } }
                else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SelectionContainer {
                            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                Text(bTitle ?: "(no title)", style = MaterialTheme.typography.titleLarge)
                                Text(bookUrl, style = MaterialTheme.typography.labelSmall, color = clrOnSurfaceVariant)

                                bLog.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = if (it.startsWith("✓")) clrPrimary else clrError) }

                                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                                if (bCover != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Cover URL:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                                    Text(bCover!!, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    selectors["getBookCoverImageUrl"]?.let { sels ->
                                        Text(" ↳ $sels", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = clrPrimary)
                                    }
                                }
                                if (!bDesc.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Description (${bDesc!!.length} chars):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                                    Text(bDesc!!.take(500), style = MaterialTheme.typography.bodySmall)
                                    selectors["getBookDescription"]?.let { sels ->
                                        Text(" ↳ $sels", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = clrPrimary)
                                    }
                                    if (bDesc!!.length > 500) Text("... (truncated)", style = MaterialTheme.typography.labelSmall, color = clrOnSurfaceVariant)
                                }
                                if (!bGenres.isNullOrEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Genres:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                                    Text(bGenres!!.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                                    selectors["getBookGenres"]?.let { sels ->
                                        Text(" ↳ $sels", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = clrPrimary)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                Text("Chapters (${bChapters?.size ?: 0}):", style = MaterialTheme.typography.titleSmall)
                                val chSel = selectors["getChapterList"] ?: selectors["parsePage"]
                                chSel?.let { sels ->
                                    Text(" ↳ $sels", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = clrPrimary)
                                }
                                bChapters?.forEachIndexed { idx, ch ->
                                    Card(onClick = {
                                        chUrl = ch.url; chTitle = ch.title
                                        chapterText = null; chLog = emptyList()
                                        prevView = PipelineView.BOOK; view = PipelineView.CHAPTER
                                    }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("${idx + 1}.", style = MaterialTheme.typography.labelSmall, color = clrOnSurfaceVariant, modifier = Modifier.width(30.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(ch.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(ch.url, style = MaterialTheme.typography.labelSmall, color = clrOnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            PipelineView.CHAPTER -> {
                LaunchedEffect(chUrl) {
                    if (chapterText != null) return@LaunchedEffect
                    chLoading = true; chLog = emptyList()
                    try {
                        val result = luaEngine.httpGet(chUrl)
                        if (result.success) {
                            chLog = chLog + "✓ HTTP GET chapter (${result.body.length} bytes, status=${result.statusCode})"
                            val tStart = System.currentTimeMillis()
                            val text = plugin.getChapterTextRaw(result.body, chUrl)
                            chLog = chLog + "✓ getChapterText (${System.currentTimeMillis() - tStart} ms) — ${text?.length ?: 0} chars"
                            chapterText = text
                        } else {
                            chLog = chLog + "✗ HTTP GET chapter (status=${result.statusCode})"
                        }
                    } catch (e: Exception) {
                        chLog = chLog + "✗ HTTP GET chapter — ${e.message ?: e.toString()}"
                    }
                    chLoading = false
                }
                if (chLoading) { Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { CircularProgressIndicator() } }
                else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(chTitle, style = MaterialTheme.typography.titleSmall)
                        Text(chUrl, style = MaterialTheme.typography.labelSmall, color = clrOnSurfaceVariant)
                        chLog.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = if (it.startsWith("✓")) clrPrimary else clrError) }
                        selectors["getChapterText"]?.let { sels ->
                            Text("Selectors: $sels", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = clrPrimary)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        if (chapterText != null) {
                            SelectionContainer {
                                Surface(color = clrSurface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxSize()) {
                                    Text(chapterText!!, modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
