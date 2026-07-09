package dev.pluginstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import dev.pluginstudio.engine.HttpLogEntry

@Composable
fun NetworkPanel() {
    val appState = LocalAppState.current
    val logs = appState.httpLogs
    var selected by remember { mutableStateOf<HttpLogEntry?>(null) }
    var showFullBody by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(0.45f).fillMaxHeight()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("HTTP Requests (${logs.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { appState.httpLogs.clear(); selected = null }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(logs) { entry ->
                    val isSel = entry == selected
                    Surface(onClick = { selected = entry },
                        color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (entry.success) "✓" else "✗", color = if (entry.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(16.dp))
                                Text(entry.method, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace, modifier = Modifier.width(36.dp))
                                Text("[${entry.statusCode}]", style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace, color = when {
                                        entry.statusCode in 200..299 -> Color(0xFF4CAF50)
                                        entry.statusCode in 300..399 -> Color(0xFFFFC107)
                                        else -> MaterialTheme.colorScheme.error
                                    }, modifier = Modifier.width(44.dp))
                                Text("${entry.durationMs}ms", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
                                Text(entry.contentType.take(25), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(entry.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                            Text("${entry.responseBody.length}b", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (selected != null) {
            val entry = selected!!
            VerticalDivider()
            Column(modifier = Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                SelectionContainer {
                    Column {
                        Text("Request", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("${entry.method} ${entry.url}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (entry.requestHeaders.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Headers:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            entry.requestHeaders.forEach { (k, v) -> Text("  $k: $v", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Response", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Status: ${entry.statusCode}  ${entry.durationMs}ms  ${entry.responseBody.length}b  Type: ${entry.contentType}",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (entry.responseHeaders.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Headers:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            entry.responseHeaders.forEach { (k, v) -> Text("  $k: $v", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Body:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { showFullBody = !showFullBody }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text(if (showFullBody) "Collapse" else "Show All ${entry.responseBody.length}b", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(4.dp))

                        val body = entry.responseBody
                        if (body.isNotBlank()) {
                            if (showFullBody || body.length <= 5000) {
                                Text(body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            } else {
                                Text(body.take(5000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text("... (${body.length - 5000} more bytes. Click 'Show All')", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Text("(empty)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
