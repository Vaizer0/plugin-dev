package dev.pluginstudio.gen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Browser-like HTTP fetcher with Cloudflare-challenge detection. */
class HttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    data class Response(val status: Int, val contentType: String, val body: String, val finalUrl: String = "") {
        val ok: Boolean get() = status in 200..299
        val blocked: Boolean get() =
            status == 403 || status == 503 ||
                body.contains("Just a moment", ignoreCase = true) ||
                body.contains("challenges.cloudflare.com", ignoreCase = true) ||
                body.contains("Attention Required", ignoreCase = true)
    }

    suspend fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): Response =
        request("GET", url, null, extraHeaders)

    suspend fun post(url: String, body: String?, contentType: String = "application/x-www-form-urlencoded"): Response =
        request("POST", url, body?.toByteArray(), mapOf("Content-Type" to contentType))

    private suspend fun request(method: String, url: String, body: ByteArray?, headers: Map<String, String>): Response =
        withContext(Dispatchers.IO) {
            try {
                val b = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                if (method == "POST" && body != null) {
                    val ct = headers["Content-Type"] ?: "application/x-www-form-urlencoded"
                    b.post(okhttp3.RequestBody.create(ct.toMediaTypeOrNull(), body))
                }
                headers.forEach { (k, v) -> if (!k.equals("Content-Type", true)) b.header(k, v) }
                client.newCall(b.build()).execute().use { resp ->
                    Response(resp.code, resp.header("Content-Type") ?: "", resp.body?.string() ?: "", resp.request.url.toString())
                }
            } catch (e: Exception) {
                Response(0, "", e.message ?: e.toString())
            }
        }

    /** Resolve possibly-relative href against a base URL (no network). */
    fun resolve(base: String, href: String): String {
        val h = href.trim().trim('"', '\'')
        if (h.isEmpty()) return ""
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (h.startsWith("//")) return "https:$h"
        return try {
            java.net.URI(base).resolve(h).toString()
        } catch (_: Exception) {
            val scheme = base.substringBefore("://")
            val hostPart = base.substringAfter("://").substringBefore("/")
            if (h.startsWith("/")) "$scheme://$hostPart$h" else "$scheme://$hostPart/$h"
        }
    }
}
