package dev.pluginstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import dev.pluginstudio.analyzer.AnalyzeRequest
import dev.pluginstudio.analyzer.DomAnalyzer
import dev.pluginstudio.analyzer.DomAnalysisReport
import dev.pluginstudio.analyzer.SelectorCandidate
import dev.pluginstudio.analyzer.SiteAnalyzer
import dev.pluginstudio.analyzer.SiteAnalysisReport
import dev.pluginstudio.engine.*
import dev.pluginstudio.engine.models.*
import dev.pluginstudio.pattern.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.net.URI
import java.nio.file.Paths
import java.util.*
import javax.swing.JFileChooser

enum class NavSection(val label: String) {
    LIBRARY("Library"), TOOLS("Tools"), ANALYSIS("Analysis"), NETWORK("Network"), CREATE("Create")
}

enum class NavItem(val label: String, val section: NavSection) {
    PLUGINS("Plugins", NavSection.LIBRARY),
    QUICK_TEST("Quick Test", NavSection.TOOLS),
    RUN("Run", NavSection.TOOLS),
    ANALYZE("Analyze", NavSection.ANALYSIS),
    PATTERNS("Patterns", NavSection.ANALYSIS),
    NETWORK_LOGS("Network Logs", NavSection.NETWORK),
    NEW_PLUGIN("New Plugin", NavSection.CREATE)
}

fun main() = application {
    startKoin { modules(appModule) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Plugin Dev Studio",
        state = rememberWindowState(width = 1300.dp, height = 850.dp)
    ) {
        MenuBar {
            Menu("File") {
                Item("Exit", onClick = ::exitApplication)
            }
        }
        MaterialTheme(colorScheme = darkColorScheme()) {
            App()
        }
    }
}

val appModule = module {
    single { LuaEngine() }
    single { SiteAnalyzer() }
    single { PluginPatternAnalyzer() }
}

private val dirPrefs = java.util.prefs.Preferences.userNodeForPackage(AppState::class.java)

private fun loadSavedPluginDir(): String {
    val saved = dirPrefs.get("pluginDir", null)
    if (saved != null) return saved
    return defaultPluginsDir()
}

private fun savePluginDir(path: String) {
    dirPrefs.put("pluginDir", path)
}

class AppState {
    var pluginDir: String by mutableStateOf(loadSavedPluginDir())
    var plugins: List<LuaSourceAdapter> by mutableStateOf(emptyList())
    var selectedPlugin by mutableStateOf<LuaSourceAdapter?>(null)
    var error by mutableStateOf<String?>(null)
    var showAbout by mutableStateOf(false)
    var statusMessage by mutableStateOf("")
    var loadErrors by mutableStateOf<List<PluginLoadError>>(emptyList())
    var currentNav by mutableStateOf(NavItem.PLUGINS)
    val httpLogs = mutableStateListOf<HttpLogEntry>()

    private val luaEngine = GlobalContext.get().get<LuaEngine>()
    private val analyzer = GlobalContext.get().get<PluginPatternAnalyzer>()

    fun navTo(item: NavItem) { currentNav = item }

    fun loadPlugins() {
        val path = Paths.get(pluginDir)
        if (!path.toFile().exists()) {
            statusMessage = "Folder not found: $pluginDir"
            plugins = emptyList(); selectedPlugin = null; loadErrors = emptyList()
            return
        }
        try {
            val loader = PluginLoader(luaEngine, path)
            val report = loader.loadAllPlugins()
            plugins = report.loaded
            loadErrors = report.failed
            selectedPlugin = plugins.firstOrNull()
            val failInfo = if (report.failed.isNotEmpty()) " (${report.failed.size} failed)" else ""
            statusMessage = "Loaded ${plugins.size} plugins from $pluginDir$failInfo"
            error = null
        } catch (e: Exception) {
            error = e.message
            statusMessage = "Error loading plugins"
        }
    }

    fun choosePluginDir() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Select plugins folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            pluginDir = chooser.selectedFile.absolutePath
            savePluginDir(pluginDir)
            loadPlugins()
        }
    }
}

val LocalAppState = staticCompositionLocalOf { AppState() }

private fun defaultPluginsDir(): String {
    val cwd = Paths.get("").toAbsolutePath()
    val candidates = listOf(
        cwd.parent.resolve("external-sources"),
        cwd.resolve("external-sources"),
        cwd.parent.resolve("sources"),
        cwd.resolve("sources")
    )
    for (p in candidates) {
        if (p.toFile().exists()) return p.toString()
    }
    return cwd.parent.resolve("external-sources").toString()
}

@Composable
fun App() {
    val appState = remember { AppState() }
    val luaEngine = remember { GlobalContext.get().get<LuaEngine>() }

    // wire up http logging
    LaunchedEffect(Unit) {
        luaEngine.onHttpLog = { appState.httpLogs.add(0, it) }
    }

    CompositionLocalProvider(LocalAppState provides appState) {
        LaunchedEffect(appState.pluginDir) { appState.loadPlugins() }

        Column(modifier = Modifier.fillMaxSize()) {
            Toolbar(appState)

            Row(modifier = Modifier.weight(1f)) {
                NavigationSidebar(appState, modifier = Modifier.width(200.dp).fillMaxHeight())
                VerticalDivider(modifier = Modifier.fillMaxHeight())

                when (val nav = appState.currentNav) {
                    NavItem.PLUGINS -> {
                        if (appState.plugins.isEmpty() && appState.error == null) {
                            WelcomeScreen(modifier = Modifier.weight(1f))
                        } else {
                            Row(modifier = Modifier.weight(1f)) {
                                PluginListPanel(
                                    plugins = appState.plugins,
                                    selectedPlugin = appState.selectedPlugin,
                                    onSelect = { appState.selectedPlugin = it; appState.navTo(NavItem.PLUGINS) },
                                    modifier = Modifier.width(320.dp).fillMaxHeight()
                                )
                                VerticalDivider(modifier = Modifier.fillMaxHeight())
                                PluginDetailPanel(
                                    plugin = appState.selectedPlugin,
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        }
                    }
                    NavItem.NEW_PLUGIN -> NewPluginPanel(modifier = Modifier.weight(1f).fillMaxHeight())
                    NavItem.ANALYZE -> AnalyzePage(appState.selectedPlugin, modifier = Modifier.weight(1f))
                    NavItem.PATTERNS -> PatternsPage(appState.selectedPlugin, modifier = Modifier.weight(1f))
                    NavItem.QUICK_TEST -> QuickTestPage(appState.selectedPlugin, modifier = Modifier.weight(1f))
                    NavItem.RUN -> RunPage(appState.selectedPlugin, modifier = Modifier.weight(1f))
                    NavItem.NETWORK_LOGS -> NetworkLogsPage(modifier = Modifier.weight(1f))
                }
            }
            StatusBar(appState)
        }

        if (appState.showAbout) {
            AlertDialog(
                onDismissRequest = { appState.showAbout = false },
                title = { Text("Plugin Dev Studio") },
                text = { Text("Version 1.0.0\n\nDesktop GUI for testing, debugging and generating Lua plugins for Novela.\n\nBuilt with Compose Desktop + Kotlin + Koin.") },
                confirmButton = { TextButton(onClick = { appState.showAbout = false }) { Text("OK") } }
            )
        }

        appState.error?.let {
            AlertDialog(
                onDismissRequest = { appState.error = null },
                title = { Text("Error") },
                text = { Text(it) },
                confirmButton = { TextButton(onClick = { appState.error = null }) { Text("OK") } }
            )
        }
    }
}

@Composable
fun Toolbar(appState: AppState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().height(44.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Plugin Dev Studio", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = 16.dp))
            OutlinedButton(
                onClick = { appState.choosePluginDir() },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) { Text("Open Folder", style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { appState.showAbout = true },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) { Text("About", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
fun NavigationSidebar(appState: AppState, modifier: Modifier = Modifier) {
    Surface(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Text("Navigation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, top = 4.dp))

            NavSection.entries.forEach { section ->
                val items = NavItem.entries.filter { it.section == section }
                Text(section.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
                items.forEach { item ->
                    val selected = appState.currentNav == item
                    Surface(
                        onClick = { appState.navTo(item) },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    ) {
                        Text(item.label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
fun AnalyzePage(plugin: LuaSourceAdapter?, modifier: Modifier = Modifier) {
    if (plugin != null) PluginAnalyzePanel(plugin) else Box(modifier = modifier, contentAlignment = Alignment.Center) { Text("Select a plugin first", style = MaterialTheme.typography.bodyLarge) }
}

@Composable
fun PatternsPage(plugin: LuaSourceAdapter?, modifier: Modifier = Modifier) {
    if (plugin != null) PluginPatternsPanel(plugin) else Box(modifier = modifier, contentAlignment = Alignment.Center) { Text("Select a plugin first", style = MaterialTheme.typography.bodyLarge) }
}

@Composable
fun QuickTestPage(plugin: LuaSourceAdapter?, modifier: Modifier = Modifier) {
    if (plugin != null) PluginQuickTestPanel(plugin) else Box(modifier = modifier, contentAlignment = Alignment.Center) { Text("Select a plugin first", style = MaterialTheme.typography.bodyLarge) }
}

@Composable
fun RunPage(plugin: LuaSourceAdapter?, modifier: Modifier = Modifier) {
    if (plugin != null) PluginRunPanel(plugin) else Box(modifier = modifier, contentAlignment = Alignment.Center) { Text("Select a plugin first", style = MaterialTheme.typography.bodyLarge) }
}

@Composable
fun NetworkLogsPage(modifier: Modifier = Modifier) {
    NetworkPanel()
}

@Composable
fun NewPluginPanel(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Create New Plugin", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(16.dp))
        Text("Enter the website URL to analyze and generate a plugin.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        var siteUrl by remember { mutableStateOf("") }
        var pluginName by remember { mutableStateOf("") }
        var pluginId by remember { mutableStateOf("") }
        val appState = LocalAppState.current

        OutlinedTextField(value = siteUrl, onValueChange = {
            siteUrl = it
            if (pluginName.isBlank() && it.isNotBlank()) {
                val host = it.removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore(".")
                pluginName = host.replaceFirstChar { c -> c.uppercase() }
                pluginId = host.lowercase()
            }
        }, label = { Text("Website URL") }, placeholder = { Text("https://example.com") }, modifier = Modifier.fillMaxWidth().widthIn(max = 500.dp), singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = pluginName, onValueChange = { pluginName = it }, label = { Text("Plugin Name") }, modifier = Modifier.fillMaxWidth().widthIn(max = 500.dp), singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = pluginId, onValueChange = { pluginId = it }, label = { Text("Plugin ID") }, modifier = Modifier.fillMaxWidth().widthIn(max = 500.dp), singleLine = true)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                appState.statusMessage = "Analysis not yet implemented — coming in Phase 3"
            },
            enabled = siteUrl.isNotBlank() && pluginName.isNotBlank() && pluginId.isNotBlank(),
            modifier = Modifier.widthIn(max = 500.dp).fillMaxWidth()
        ) { Text("Analyze & Generate Plugin") }
    }
}

@Composable
fun WelcomeScreen(modifier: Modifier = Modifier) {
    val appState = LocalAppState.current
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Plugin Dev Studio", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text("No plugins loaded", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Default path: ${appState.pluginDir}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { appState.choosePluginDir() }) { Text("Open Plugins Folder") }
        }
    }
}

@Composable
fun StatusBar(appState: AppState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().height(28.dp)) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(appState.statusMessage.ifEmpty { "Ready" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${appState.plugins.size} plugins | ${appState.selectedPlugin?.name ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PluginListPanel(plugins: List<LuaSourceAdapter>, selectedPlugin: LuaSourceAdapter?, onSelect: (LuaSourceAdapter) -> Unit, modifier: Modifier = Modifier) {
    val appState = LocalAppState.current
    Surface(modifier = modifier) {
        Column {
            Text("Plugins (${plugins.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            if (appState.loadErrors.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${appState.loadErrors.size} failed to load", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (expanded) {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        appState.loadErrors.forEach { err ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(err.file, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    Text(err.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            if (plugins.isEmpty()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("No plugins loaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn { items(plugins) { plugin -> PluginListItem(plugin, isSelected = plugin == selectedPlugin, onClick = { onSelect(plugin) }) } }
            }
        }
    }
}

@Composable
fun PluginListItem(plugin: LuaSourceAdapter, isSelected: Boolean, onClick: () -> Unit) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Surface(onClick = onClick, color = bg, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(plugin.name, style = MaterialTheme.typography.bodyLarge)
            Text(plugin.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plugin.language?.iso639_1?.uppercase() ?: "??", style = MaterialTheme.typography.labelSmall)
                Text("v${plugin.getMetadata().version}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val TABS = listOf("Info", "Quick Test", "Run", "Network", "Analyze", "Patterns")

@Composable
fun PluginDetailPanel(plugin: LuaSourceAdapter?, modifier: Modifier = Modifier) {
    Surface(modifier = modifier) {
        if (plugin == null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("Select a plugin from the list", style = MaterialTheme.typography.bodyLarge) }
        } else {
            var selectedTab by remember(plugin) { mutableStateOf(TABS[0]) }
            Column {
                TabRow(selectedTabIndex = TABS.indexOf(selectedTab)) {
                    TABS.forEach { tab -> Tab(selected = selectedTab == tab, onClick = { selectedTab = tab }, text = { Text(tab) }) }
                }
                when (selectedTab) {
                    "Info" -> PluginInfoPanel(plugin)
                    "Quick Test" -> PluginQuickTestPanel(plugin)
                    "Run" -> PluginRunPanel(plugin)
                    "Network" -> NetworkPanel()
                    "Analyze" -> PluginAnalyzePanel(plugin)
                    "Patterns" -> PluginPatternsPanel(plugin)
                }
            }
        }
    }
}

@Composable
fun PluginInfoPanel(plugin: LuaSourceAdapter) {
    Column(modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(plugin.name, style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(8.dp))
        Text("ID: ${plugin.id}"); Text("Base URL: ${plugin.baseUrl}"); Text("Language: ${plugin.language}")
        val meta = plugin.getMetadata(); Text("Version: ${meta.version}")
        if (meta.description.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text("Description: ${meta.description}") }
        Spacer(Modifier.height(16.dp))
        Text("Functions:", style = MaterialTheme.typography.titleMedium)
        listOf("getCatalogList","getCatalogSearch","getBookTitle","getBookCoverImageUrl","getBookDescription","getBookGenres","getChapterList","getChapterText","parsePage").forEach { fn ->
            Text("${if (plugin.hasFunction(fn)) "✓" else "✗"} $fn", color = if (plugin.hasFunction(fn)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun PluginQuickTestPanel(plugin: LuaSourceAdapter) {
    PipelineContent(plugin)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginRunPanel(plugin: LuaSourceAdapter) {
    val runner = remember(plugin) { FunctionRunner(plugin) }
    val functions = remember(plugin) { runner.availableFunctions() }
    val selectors = remember(plugin) { plugin.selectors }
    val scope = rememberCoroutineScope()
    var selectedFunc by remember { mutableStateOf<FunctionDef?>(null) }
    var argValues by remember { mutableStateOf(mapOf<String, String>()) }
    var result by remember { mutableStateOf<FunctionResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Run Function", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(12.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(value = selectedFunc?.name ?: "", onValueChange = {}, readOnly = true, label = { Text("Function") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                functions.forEach { fn -> DropdownMenuItem(text = { Text(fn.name) }, onClick = { selectedFunc = fn; argValues = fn.params.associate { it.name to (it.defaultValue ?: "") }; result = null; expanded = false }) }
            }
        }
        selectedFunc?.let { func ->
            Spacer(Modifier.height(12.dp)); Text("${func.name} : ${func.returnType}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp))
            selectors[func.name]?.let { sels ->
                Text("Selector: ${sels.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
            }
            func.params.forEach { param ->
                OutlinedTextField(value = argValues[param.name] ?: "", onValueChange = { argValues = argValues + (param.name to it) }, label = { Text(param.name) }, placeholder = param.hint?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), singleLine = true)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { running = true; result = null; scope.launch { result = runner.run(func.name, argValues); running = false } }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                if (running) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Run")
            }
        }
        result?.let { res ->
            Spacer(Modifier.height(16.dp)); Text("Result (${res.durationMs}ms):", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(4.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().weight(1f)) {
                SelectionContainer {
                    Text(text = if (res.success) res.data ?: "null" else "Error: ${res.error}", modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginAnalyzePanel(plugin: LuaSourceAdapter) {
    val analyzer = remember { GlobalContext.get().get<SiteAnalyzer>() }
    val domAnalyzer = remember { DomAnalyzer() }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(plugin.baseUrl.ifBlank { "" }) }
    var siteReport by remember { mutableStateOf<SiteAnalysisReport?>(null) }
    var domReport by remember { mutableStateOf<DomAnalysisReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        SelectionContainer {
            Column {
                Text("Site Analyzer", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, placeholder = { Text("https://example.com") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    running = true; siteReport = null; domReport = null; error = null
                    scope.launch {
                        try {
                            val html = downloadPage(url)
                            if (html != null) {
                                val domR = domAnalyzer.analyze(url, html)
                                domReport = domR
                                siteReport = analyzer.analyzeHtml(url, html, domR)
                            } else {
                                siteReport = analyzer.analyze(AnalyzeRequest(url = url))
                            }
                        } catch (e: Exception) { error = e.message }
                        running = false
                    }
                }, enabled = !running && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    if (running) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Analyze")
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text("Error: $it", color = MaterialTheme.colorScheme.error) }

                val selectorResult = siteReport?.selectorResult

                siteReport?.let { r ->
                    Spacer(Modifier.height(12.dp)); Text("Summary", style = MaterialTheme.typography.titleSmall)
                    Text("JS: ${r.summary.totalJsFiles} · Findings: ${r.summary.totalFindings} · Endpoints: ${r.summary.totalEndpoints} · Selectors: ${r.summary.totalCssSelectors}")
                    r.summary.warningMessages.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }

                domReport?.let { d ->
                    Spacer(Modifier.height(16.dp))
                    Text("DOM Analysis", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp))

                    d.cms?.let { cms ->
                        val clusterId = cms.clusterId
                        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("CMS: ${cms.name}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    if (clusterId != null) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                                            Text(clusterId, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                        }
                                    }
                                }
                                cms.themeVariant?.let { Text("Theme: $it", style = MaterialTheme.typography.bodySmall) }
                                Text("Confidence: ${"%.0f%%".format(cms.confidence * 100)}", style = MaterialTheme.typography.bodySmall)
                                cms.evidence.forEach { Text("  • $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (clusterId != null) {
                                    Spacer(Modifier.height(4.dp)); Divider()
                                    Text("Approach: ${cms.catalogType ?: "?"}/${cms.chapterListType ?: "?"} · Pagination: ${cms.paginationType ?: "?"}", style = MaterialTheme.typography.labelSmall)
                                    if (cms.samplePlugins.isNotEmpty()) {
                                        Text("Similar plugins: ${cms.samplePlugins.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (d.containers.isNotEmpty()) {
                        Text("Containers (${d.containers.size})", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(4.dp))
                        d.containers.forEach { c ->
                            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(c.selector, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                        Text("x${c.count} ${c.purpose}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    c.possibleFields.forEach { f ->
                                        Text("  ${f.role}: ${f.selector}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("    \"${f.sampleValue.take(60)}\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (d.forms.isNotEmpty()) {
                        Text("Forms (${d.forms.size})", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(4.dp))
                        d.forms.forEach { f ->
                            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.padding(8.dp)) {
                                    Text("${f.method} ${f.action}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    Text("  inputs: ${f.inputs.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    d.pagination?.let { p ->
                        Text("Pagination", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(4.dp))
                        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${p.type}: ${p.selector}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text("sample: ${p.sampleUrl}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (d.embeddedJson.isNotEmpty()) {
                        Text("Embedded JSON (${d.embeddedJson.size})", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(4.dp))
                        d.embeddedJson.forEach { json ->
                            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(json.type, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Text(json.content.take(200), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                selectorResult?.let { sel ->
                    if (sel.candidates.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Suggested Selectors", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp))
                        val purposeLabels = mapOf(
                            "catalog_container" to "Catalog container",
                            "book_title" to "Book title",
                            "book_cover" to "Book cover",
                            "book_description" to "Book description",
                            "chapter_list" to "Chapter list",
                            "chapter_content" to "Chapter content",
                            "pagination" to "Pagination"
                        )
                        val sortedPurposes: List<Map.Entry<String, List<SelectorCandidate>>> = sel.candidates.entries.sortedBy { (k, _) ->
                            purposeLabels.keys.indexOf(k).let { if (it < 0) 99 else it }
                        }
                        for ((purpose, candidates) in sortedPurposes) {
                            Column(Modifier.padding(start = 0.dp)) {
                                Text(purposeLabels[purpose] ?: purpose, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 2.dp))
                                for (c in candidates) {
                                    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                        Column(Modifier.padding(8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(c.selector, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    val confColor = when (c.confidence) { "high" -> MaterialTheme.colorScheme.primary; "medium" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.error }
                                                    Surface(color = confColor, shape = MaterialTheme.shapes.extraSmall) {
                                                        Text(c.confidence, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                                    }
                                                    Text("x${c.matchCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text("\"${c.sampleText.take(80)}\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                siteReport?.let { r ->
                    if (r.endpointSuggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp)); Text("API Endpoints", style = MaterialTheme.typography.titleSmall)
                        val ajaxEndpoints = r.endpointSuggestions.filter { it.source == "WORDPRESS_AJAX_COMBINED" || it.source == "WP_AJAX_URL" }
                        val apiPaths = r.endpointSuggestions.filter { it.source == "API_PATH" }
                        val fetchEndpoints = r.endpointSuggestions.filter { it.source in setOf("FETCH", "AXIOS", "JQUERY_AJAX", "XHR_OPEN") }
                        val graphqlEndpoints = r.endpointSuggestions.filter { it.source == "GRAPHQL_ENDPOINT" }
                        val wpApiSettings = r.endpointSuggestions.filter { it.source == "WP_API_SETTINGS" }
                        val otherEndpoints = r.endpointSuggestions.filter {
                            it.source !in setOf("WORDPRESS_AJAX_COMBINED", "WP_AJAX_URL", "API_PATH", "FETCH", "AXIOS",
                                "JQUERY_AJAX", "XHR_OPEN", "GRAPHQL_ENDPOINT", "WP_API_SETTINGS") &&
                            it.confidence != "low"
                        }

                        if (ajaxEndpoints.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp)); Text("WordPress AJAX", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ajaxEndpoints.forEach { ep ->
                                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(ep.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(ep.category.name, style = MaterialTheme.typography.labelSmall)
                                            Text(ep.confidence, style = MaterialTheme.typography.labelSmall, color = when (ep.confidence) { "high" -> MaterialTheme.colorScheme.primary; "medium" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.error })
                                        }
                                    }
                                }
                            }
                        }
                        if (apiPaths.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp)); Text("API Paths", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            apiPaths.forEach { ep ->
                                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(ep.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(ep.category.name, style = MaterialTheme.typography.labelSmall)
                                            Text(ep.confidence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                        }
                                    }
                                }
                            }
                        }
                        if (fetchEndpoints.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp)); Text("Fetch / XHR", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            fetchEndpoints.forEach { ep ->
                                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(ep.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(ep.category.name, style = MaterialTheme.typography.labelSmall)
                                            Text(ep.confidence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        if (graphqlEndpoints.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp)); Text("GraphQL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            graphqlEndpoints.forEach { ep ->
                                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(ep.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(ep.category.name, style = MaterialTheme.typography.labelSmall)
                                            Text(ep.confidence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        if (wpApiSettings.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp)); Text("WP REST API", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            wpApiSettings.forEach { ep ->
                                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(ep.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(ep.category.name, style = MaterialTheme.typography.labelSmall)
                                            Text(ep.confidence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        if (otherEndpoints.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp)); Text("Other", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            otherEndpoints.forEach { ep ->
                                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(ep.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(ep.category.name, style = MaterialTheme.typography.labelSmall)
                                            Text(ep.confidence, style = MaterialTheme.typography.labelSmall, color = when (ep.confidence) { "high" -> MaterialTheme.colorScheme.primary; "medium" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.error })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (r.jsFiles.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp)); Text("JS Files", style = MaterialTheme.typography.titleSmall)
                        r.jsFiles.forEach { js ->
                            if (js.findings.isNotEmpty()) {
                                val interesting = js.findings.filter {
                                    it.type.name in setOf("FETCH", "AXIOS", "WP_AJAX_URL", "WP_AJAX_ACTION",
                                        "WP_API_SETTINGS", "API_BASE_VARIABLE", "GRAPHQL_ENDPOINT",
                                        "NEXT_DATA", "NUXT_DATA", "JSON_LD", "API_PATH",
                                        "WORDPRESS_AJAX_COMBINED")
                                }
                                if (interesting.isEmpty()) return@forEach
                                val fileName = js.url.substringAfterLast("/").take(40)
                                Text(fileName, style = MaterialTheme.typography.labelMedium)
                                interesting.take(3).forEach { f ->
                                    val label = f.type.name.take(16)
                                    val hint = f.value.take(80)
                                    Text("  $label: $hint", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun downloadPage(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val client = okhttp3.OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true).build()
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { it.body?.string() }
    } catch (_: Exception) { null }
}

@Composable
fun PluginPatternsPanel(plugin: LuaSourceAdapter) {
    val analyzer = remember { GlobalContext.get().get<PluginPatternAnalyzer>() }
    var summary by remember(plugin) { mutableStateOf<PluginPatternSummary?>(null) }
    var matches by remember(plugin) { mutableStateOf<List<ClusterMatch>?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(plugin) {
        summary = analyzer.analyze(plugin)
        matches = analyzer.matchCluster(plugin, null)
        loading = false
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Pattern Analysis", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(12.dp))
        if (loading) { CircularProgressIndicator(); return@Column }
        summary?.let { s ->
            Text("Plugin: ${s.pluginName} (${s.pluginId})", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(8.dp))
            Text("Functions: ${s.functions.size} / ${s.functions.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Characteristics:", style = MaterialTheme.typography.bodyMedium)
            Text("  API: ${if (s.hasApiPatterns) "✓" else "✗"} / WordPress: ${if (s.hasWordPressPatterns) "✓" else "✗"} / Custom encoding: ${if (s.hasCustomEncoding) "✓" else "✗"}")
            if (s.charset != null) Text("  Charset: ${s.charset}")
            Spacer(Modifier.height(12.dp)); Text("Matched Clusters:", style = MaterialTheme.typography.titleSmall)
            val matchList = matches ?: emptyList()
            if (matchList.isEmpty()) {
                Text("No clusters matched", color = MaterialTheme.colorScheme.error)
            } else {
                matchList.forEach { match ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(match.cluster.name, style = MaterialTheme.typography.bodyMedium)
                                Text("%.0f%%".format(match.confidence * 100), style = MaterialTheme.typography.bodyMedium, color = confidenceColor(match.confidence))
                            }
                            Text(match.cluster.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Probes: ${match.matchedProbes}/${match.totalProbes}", style = MaterialTheme.typography.labelSmall)
                            if (match.cluster.samplePlugins.isNotEmpty()) Text("Examples: ${match.cluster.samplePlugins.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp)); Text("All Clusters", style = MaterialTheme.typography.titleSmall)
            PatternRegistry.clusters.forEach { cluster ->
                val isMatched = matchList.any { it.cluster.id == cluster.id }
                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = if (isMatched) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.padding(8.dp)) { Text("${if (isMatched) "▶ " else ""}${cluster.name}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun confidenceColor(confidence: Double) = when {
    confidence >= 0.7 -> Color(0xFF4CAF50)
    confidence >= 0.5 -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}
