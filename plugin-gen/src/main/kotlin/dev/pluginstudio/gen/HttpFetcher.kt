package dev.pluginstudio.gen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Browser-like HTTP fetcher with Cloudflare-challenge detection + curl fallback. */
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

    // Derived clients sharing the connection pool, one per read-timeout.
    private val shortClients = java.util.concurrent.ConcurrentHashMap<Int, OkHttpClient>()

    private fun clientFor(readTimeoutSec: Int): OkHttpClient =
        if (readTimeoutSec == 25) client
        else shortClients.computeIfAbsent(readTimeoutSec) {
            client.newBuilder().readTimeout(it.toLong(), TimeUnit.SECONDS).build()
        }

    data class Response(val status: Int, val contentType: String, val body: String, val finalUrl: String = "") {
        val ok: Boolean get() = status in 200..299
        val blocked: Boolean get() =
            status == 403 || status == 503 ||
                body.contains("Just a moment", ignoreCase = true) ||
                body.contains("challenges.cloudflare.com", ignoreCase = true) ||
                body.contains("Attention Required", ignoreCase = true)
    }

    /**
     * GET strategy: system curl FIRST (fast on every tested CDN, incl. ones that
     * tarpit JVM TLS), OkHttp as fallback when curl is unavailable or fails.
     */
    suspend fun get(url: String, extraHeaders: Map<String, String> = emptyMap(), timeoutSec: Int = 25): Response {
        if (curlAvailable()) {
            val viaCurl = curlGet(url, timeoutSec)
            if (viaCurl != null && viaCurl.ok && !viaCurl.blocked) return viaCurl
        }
        val direct = request("GET", url, null, extraHeaders, timeoutSec)
        if (!direct.ok && !direct.blocked && direct.status != 404 && curlAvailable()) {
            val retry = curlGet(url, timeoutSec)
            if (retry != null && retry.ok) return retry
        }
        return direct
    }

    suspend fun post(
        url: String,
        body: String?,
        contentType: String = "application/x-www-form-urlencoded",
        timeoutSec: Int = 25,
        extraHeaders: Map<String, String> = emptyMap()
    ): Response = request("POST", url, body?.toByteArray(),
        mapOf("Content-Type" to contentType) + extraHeaders, timeoutSec)

    @Volatile private var curlChecked: Boolean? = null
    private fun curlAvailable(): Boolean {
        curlChecked?.let { return it }
        curlChecked = try {
            ProcessBuilder("curl", "--version").start().waitFor() == 0
        } catch (_: Exception) { false }
        return curlChecked!!
    }

    /** Normal GET via the system curl binary (follows redirects, decompresses). */
    private fun curlGet(url: String, timeoutSec: Int): Response? = try {
        val body = java.nio.file.Files.createTempFile("pds_curl", ".body")
        try {
            val pb = ProcessBuilder(
                "curl", "-sS", "-L", "--compressed",
                "-m", "${timeoutSec + 5}",
                "-A", UA,
                "-H", "Accept-Language: en-US,en;q=0.9",
                "-o", body.toString(),
                "-w", "%{http_code}\t%{content_type}",
                url
            ).apply { redirectErrorStream(true) }
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) null
            else {
                val parts = out.trim().split("\t")
                val status = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val ct = parts.getOrNull(1)?.trim() ?: ""
                if (status == 0) null
                else Response(status, ct, java.nio.file.Files.readString(body), url)
            }
        } finally {
            java.nio.file.Files.deleteIfExists(body)
        }
    } catch (_: Exception) { null }

    private suspend fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
        timeoutSec: Int
    ): Response = withContext(Dispatchers.IO) {
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
            // Deadline bounds the WHOLE call (connect + headers + body read).
            // A socket readTimeout alone never fires against drip-feed servers,
            // and coroutine cancellation cannot interrupt blocking execute().
            val call = clientFor(timeoutSec).newCall(b.build())
            call.timeout().deadline(timeoutSec + 2L, TimeUnit.SECONDS)
            call.execute().use { resp ->
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
