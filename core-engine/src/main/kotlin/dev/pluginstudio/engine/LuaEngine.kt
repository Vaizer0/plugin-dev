package dev.pluginstudio.engine

import com.google.gson.Gson
import dev.pluginstudio.engine.models.Response
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.luaj.vm2.*
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JsePlatform
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlinx.coroutines.*

data class HttpLogEntry(
    val method: String,
    val url: String,
    val statusCode: Int,
    val durationMs: Long,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String = "",
    val contentType: String = "",
    val success: Boolean = true
)

data class HttpResult(
    val success: Boolean,
    val body: String,
    val statusCode: Int
)

class LuaEngine(
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    var onHttpLog: (HttpLogEntry) -> Unit = {}
) {
    private val gson = Gson()
    private val preferences = Preferences.userNodeForPackage(LuaEngine::class.java)

    suspend fun loadScript(luaCode: String): LuaValue = withContext(Dispatchers.IO) {
        val globals = JsePlatform.standardGlobals()
        registerApi(globals)
        globals.load(luaCode).call()
        globals
    }

    fun loadScriptBlocking(luaCode: String): LuaValue {
        val globals = JsePlatform.standardGlobals()
        registerApi(globals)
        globals.load(luaCode).call()
        return globals
    }

    suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap(), charset: String = "UTF-8"): HttpResult = withContext(Dispatchers.IO) {
        val allHeaders = defaultHeaders(url) + headers
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(url).headers(allHeaders.toHeaders()).get().build()
            httpClient.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val body = String(bytes, Charset.forName(charset))
                val respHeaders = response.headers.associate { it.first to it.second }
                val ct = response.header("Content-Type") ?: ""
                onHttpLog(HttpLogEntry(
                    method = "GET", url = url, statusCode = response.code,
                    durationMs = duration, success = response.isSuccessful,
                    requestHeaders = allHeaders, responseHeaders = respHeaders,
                    contentType = ct, responseBody = body.take(5000)
                ))
                HttpResult(response.isSuccessful, body, response.code)
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            onHttpLog(HttpLogEntry("GET", url, 0, duration, success = false))
            HttpResult(false, "", 0)
        }
    }

    private fun registerApi(g: Globals) {
        g.set("http_get", HttpGetFunction() as LuaValue)
        g.set("http_post", HttpPostFunction() as LuaValue)
        g.set("get_cookies", GetCookiesFunction() as LuaValue)
        g.set("set_cookies", SetCookiesFunction() as LuaValue)
        g.set("get_preference", GetPreferenceFunction() as LuaValue)
        g.set("set_preference", SetPreferenceFunction() as LuaValue)
        g.set("aes_decrypt", AesDecryptFunction() as LuaValue)
        g.set("base64_decode", Base64DecodeFunction() as LuaValue)
        g.set("base64_encode", Base64EncodeFunction() as LuaValue)
        g.set("html_parse", HtmlParseFunction() as LuaValue)
        g.set("html_select", HtmlSelectFunction() as LuaValue)
        g.set("html_select_first", HtmlSelectFirstFunction() as LuaValue)
        g.set("html_attr", HtmlAttrFunction() as LuaValue)
        g.set("html_text", HtmlTextFunction() as LuaValue)
        g.set("html_remove", HtmlRemoveFunction() as LuaValue)
        g.set("http_get_batch", HttpGetBatchFunction() as LuaValue)
        g.set("url_encode", UrlEncodeFunction() as LuaValue)
        g.set("url_encode_charset", UrlEncodeCharsetFunction() as LuaValue)
        g.set("url_resolve", UrlResolveFunction() as LuaValue)
        g.set("regex_match", RegexMatchFunction() as LuaValue)
        g.set("regex_replace", RegexReplaceFunction() as LuaValue)
        g.set("string_normalize", StringNormalizeFunction() as LuaValue)
        g.set("string_split", StringSplitFunction() as LuaValue)
        g.set("string_trim", StringTrimFunction() as LuaValue)
        g.set("string_starts_with", StringStartsWithFunction() as LuaValue)
        g.set("string_ends_with", StringEndsWithFunction() as LuaValue)
        g.set("string_clean", StringCleanFunction() as LuaValue)
        g.set("unescape_unicode", UnescapeUnicodeFunction() as LuaValue)
        g.set("json_parse", JsonParseFunction() as LuaValue)
        g.set("json_stringify", JsonStringifyFunction() as LuaValue)
        g.set("detect_pagination", DetectPaginationFunction() as LuaValue)
        g.set("sleep", SleepFunction() as LuaValue)
        g.set("log_info", LogInfoFunction() as LuaValue)
        g.set("log_error", LogErrorFunction() as LuaValue)
        g.set("os_time", OsTimeFunction() as LuaValue)
    }

    private fun defaultHeaders(url: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to acceptLanguage(url),
        "Referer" to refererFromUrl(url)
    )

    private fun acceptLanguage(url: String): String {
        val domain = try { URI(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        return if (domain.endsWith(".jp") || domain.contains("syosetu") || domain.contains("narou")) {
            "ja,en-US;q=0.9"
        } else {
            Locale.getDefault().toLanguageTag()
        }
    }

    private fun refererFromUrl(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}/"
    } catch (_: Exception) { url }

    // ── HTTP ──

    private inner class HttpGetFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = runBlocking {
            val url = a1.checkjstring()
            val config = if (a2.istable()) a2.checktable() else LuaTable()
            val pluginHeaders = convertHeaders(config.get("headers").opttable(LuaTable()))
            val charset = config.get("charset").optjstring("UTF-8")
            val result = httpGet(url, pluginHeaders, charset)
            responseTable(result.success, result.body, result.statusCode)
        }
    }

    private inner class HttpPostFunction : ThreeArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue, a3: LuaValue): LuaValue = runBlocking {
            val url = a1.checkjstring()
            val bodyStr = a2.checkjstring()
            val config = if (a3.istable()) a3.checktable() else LuaTable()
            val pluginHeaders = convertHeaders(config.get("headers").opttable(LuaTable()))
            val charset = config.get("charset").optjstring("UTF-8")
            val headers = defaultHeaders(url) + pluginHeaders
            val startTime = System.currentTimeMillis()
            try {
                val mediaType = (headers["Content-Type"] ?: detectContentType(bodyStr)).toMediaType()
                val body = bodyStr.toRequestBody(mediaType)
                val request = Request.Builder().url(url).headers(headers.toHeaders()).post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    val duration = System.currentTimeMillis() - startTime
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    val s = String(bytes, Charset.forName(charset))
                    val respHeaders = response.headers.associate { it.first to it.second }
                    val ct = response.header("Content-Type") ?: ""
                    onHttpLog(HttpLogEntry("POST", url, response.code, duration,
                        success = response.isSuccessful, requestHeaders = headers,
                        responseHeaders = respHeaders, contentType = ct,
                        responseBody = s.take(5000)))
                    responseTable(response.isSuccessful, s, response.code)
                }
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                onHttpLog(HttpLogEntry("POST", url, 0, duration, success = false))
                errorTable(e)
            }
        }
    }

    private fun responseTable(success: Boolean, body: String, code: Int) = LuaTable().also { t ->
        t.set("success", LuaValue.valueOf(success))
        t.set("body", LuaValue.valueOf(body))
        t.set("code", LuaValue.valueOf(code))
    }

    private fun errorTable(e: Exception) = LuaTable().also { t ->
        t.set("success", LuaValue.FALSE)
        t.set("body", LuaValue.valueOf(e.message ?: "Unknown error"))
        t.set("code", LuaValue.valueOf(-1))
    }

    private fun detectContentType(body: String): String =
        if (body.trimStart().firstOrNull() in listOf('{', '[')) "application/json"
        else "application/x-www-form-urlencoded"

    private fun convertHeaders(table: LuaTable): Map<String, String> {
        val map = mutableMapOf<String, String>()
        table.keys().forEach { map[it.tojstring()] = table.get(it).tojstring() }
        return map
    }

    // http_get_batch
    private inner class HttpGetBatchFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val urlTable = arg.checktable()
            val urls = (1..urlTable.length()).map { urlTable.get(it).checkjstring() }
            val results = runBlocking {
                urls.map { url ->
                    async(Dispatchers.IO) {
                        try {
                            val startTime = System.currentTimeMillis()
                            val request = Request.Builder().url(url)
                                .headers(defaultHeaders(url).toHeaders()).get().build()
                            httpClient.newCall(request).execute().use { response ->
                                val body = response.body?.string() ?: ""
                                val duration = System.currentTimeMillis() - startTime
                                val respHeaders = response.headers.associate { it.first to it.second }
                                val ct = response.header("Content-Type") ?: ""
                                onHttpLog(HttpLogEntry("GET", url, response.code, duration,
                                    success = response.isSuccessful,
                                    responseHeaders = respHeaders, contentType = ct,
                                    responseBody = body.take(5000)))
                                Triple(response.isSuccessful, body, response.code)
                            }
                        } catch (_: Exception) {
                            Triple(false, "", 0)
                        }
                    }
                }.awaitAll()
            }
            return LuaTable().also { out ->
                results.forEachIndexed { i, (success, body, code) ->
                    out.set(i + 1, responseTable(success, body, code))
                }
            }
        }
    }

    // ── Preferences ──

    private inner class GetPreferenceFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue =
            LuaValue.valueOf(preferences.get(arg.checkjstring(), "") ?: "")
    }

    private inner class SetPreferenceFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue {
            preferences.put(a1.checkjstring(), a2.tojstring())
            return LuaValue.NIL
        }
    }

    // ── Cookies ──

    private inner class GetCookiesFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val url = arg.checkjstring()
            return LuaTable()
        }
    }

    private inner class SetCookiesFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = LuaValue.NIL
    }

    // ── Crypto ──

    private inner class AesDecryptFunction : ThreeArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue, a3: LuaValue): LuaValue = try {
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(a2.checkjstring().toByteArray(), "AES"),
                javax.crypto.spec.IvParameterSpec(a3.checkjstring().toByteArray())
            )
            LuaValue.valueOf(String(
                cipher.doFinal(Base64.getDecoder().decode(a1.checkjstring())),
                Charsets.UTF_8
            ))
        } catch (_: Exception) { LuaValue.NIL }
    }

    private inner class Base64DecodeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(String(
                Base64.getDecoder().decode(arg.checkjstring()),
                Charsets.UTF_8
            ))
        } catch (_: Exception) { LuaValue.NIL }
    }

    private inner class Base64EncodeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(Base64.getEncoder().encodeToString(
                arg.checkjstring().toByteArray(Charsets.UTF_8)
            ))
        } catch (_: Exception) { LuaValue.NIL }
    }

    // ── HTML ──

    private inner class HtmlParseFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            val doc = Jsoup.parse(arg.checkjstring())
            LuaTable().also { t ->
                t.set("text", LuaValue.valueOf(doc.text()))
                t.set("html", LuaValue.valueOf(doc.html()))
                t.set("title", LuaValue.valueOf(doc.title()))
                t.set("body", elementToTable(doc.body()))
            }
        } catch (_: Exception) { LuaValue.NIL }
    }

    private inner class HtmlSelectFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            val html = htmlFromValue(a1)
            val elems = Jsoup.parse(html).select(a2.checkjstring())
            LuaTable().also { t ->
                elems.forEachIndexed { i, el -> t.set(i + 1, elementToTable(el)) }
            }
        } catch (_: Exception) { LuaTable() }
    }

    private inner class HtmlSelectFirstFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            val html = htmlFromValue(a1)
            val el = Jsoup.parse(html).selectFirst(a2.checkjstring())
            if (el != null) elementToTable(el) else LuaValue.NIL
        } catch (_: Exception) { LuaValue.NIL }
    }

    private inner class HtmlAttrFunction : ThreeArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue, a3: LuaValue): LuaValue = try {
            val html = htmlFromValue(a1)
            val el = Jsoup.parse(html).selectFirst(a2.checkjstring())
            if (el != null) LuaValue.valueOf(el.attr(a3.checkjstring())) else LuaValue.valueOf("")
        } catch (_: Exception) { LuaValue.valueOf("") }
    }

    private inner class HtmlTextFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            val html = arg.tojstring()
            val doc = Jsoup.parseBodyFragment(html)
            LuaValue.valueOf(TextExtractor.get(doc.body()))
        } catch (_: Exception) { LuaValue.NIL }
    }

    private inner class HtmlRemoveFunction : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            return try {
                val html = htmlFromValue(args.arg(1))
                val doc = Jsoup.parse(html)
                for (i in 2..args.narg()) {
                    val selector = args.arg(i).optjstring(null) ?: continue
                    if (selector.isNotBlank()) doc.select(selector).remove()
                }
                LuaValue.valueOf(doc.body().html())
            } catch (_: Exception) { args.arg(1) }
        }
    }

    // ── String utils ──

    private inner class StringCleanFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val s = arg.optjstring("") ?: return LuaValue.valueOf("")
            val normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
            val collapsed = normalized.replace(Regex("""\s+"""), " ").trim()
            return LuaValue.valueOf(collapsed)
        }
    }

    private fun htmlFromValue(v: LuaValue): String =
        if (v.istable()) v.checktable().get("html").optjstring("") else v.checkjstring()

    private fun elementToTable(el: Element): LuaTable = LuaTable().also { t ->
        t.set("text", LuaValue.valueOf(el.text()))
        t.set("html", LuaValue.valueOf(el.html()))
        t.set("href", LuaValue.valueOf(el.attr("abs:href").ifEmpty { el.attr("href") }))
        t.set("src", LuaValue.valueOf(el.attr("abs:src").ifEmpty { el.attr("src") }))
        t.set("title", LuaValue.valueOf(el.attr("title")))
        t.set("class", LuaValue.valueOf(el.attr("class")))
        t.set("id", LuaValue.valueOf(el.attr("id")))
        t.set("get_text", object : ZeroArgFunction() { override fun call() = LuaValue.valueOf(el.text()) })
        t.set("get_html", object : ZeroArgFunction() { override fun call() = LuaValue.valueOf(el.html()) })
        t.set("attr", object : OneArgFunction() {
            override fun call(a: LuaValue) = try {
                LuaValue.valueOf(el.attr(a.checkjstring()))
            } catch (_: Exception) { LuaValue.valueOf("") }
        })
        t.set("remove", object : ZeroArgFunction() {
            override fun call(): LuaValue { el.remove(); return LuaValue.NIL }
        })
        t.set("select", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue = try {
                val sub = el.select(a.checkjstring())
                LuaTable().also { t2 -> sub.forEachIndexed { i, e -> t2.set(i + 1, elementToTable(e)) } }
            } catch (_: Exception) { LuaTable() }
        })
    }

    // ── URL ──

    private inner class UrlEncodeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(URLEncoder.encode(arg.checkjstring(), "UTF-8"))
        } catch (_: Exception) { LuaValue.NIL }
    }

    private inner class UrlEncodeCharsetFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            LuaValue.valueOf(URLEncoder.encode(a1.checkjstring(), a2.optjstring("UTF-8")))
        } catch (_: Exception) { LuaValue.NIL }
    }

    private inner class UrlResolveFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            LuaValue.valueOf(URI(a1.checkjstring()).resolve(a2.checkjstring()).toString())
        } catch (_: Exception) { a2 }
    }

    // ── Regex ──

    private inner class RegexReplaceFunction : ThreeArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue, a3: LuaValue): LuaValue = try {
            LuaValue.valueOf(a1.checkjstring().replace(Regex(a2.checkjstring()), a3.checkjstring()))
        } catch (_: Exception) { a1 }
    }

    private inner class RegexMatchFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            LuaTable().also { t ->
                Regex(a2.checkjstring()).findAll(a1.checkjstring())
                    .forEachIndexed { i, m -> t.set(i + 1, LuaValue.valueOf(m.value)) }
            }
        } catch (_: Exception) { LuaTable() }
    }

    private inner class StringNormalizeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(java.text.Normalizer.normalize(arg.checkjstring(), java.text.Normalizer.Form.NFKC))
        } catch (_: Exception) { arg }
    }

    private inner class StringSplitFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            val parts = a1.checkjstring().split(a2.checkjstring())
            LuaTable().also { t -> parts.forEachIndexed { i, s -> t.set(i + 1, LuaValue.valueOf(s)) } }
        } catch (_: Exception) { LuaTable() }
    }

    private inner class StringTrimFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(arg.checkjstring().trim())
        } catch (_: Exception) { arg }
    }

    private inner class StringStartsWithFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            LuaValue.valueOf(a1.checkjstring().startsWith(a2.checkjstring()))
        } catch (_: Exception) { LuaValue.FALSE }
    }

    private inner class StringEndsWithFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            LuaValue.valueOf(a1.checkjstring().endsWith(a2.checkjstring()))
        } catch (_: Exception) { LuaValue.FALSE }
    }

    private inner class UnescapeUnicodeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(
                Regex("\\\\u([0-9a-fA-F]{4})").replace(arg.checkjstring()) { m ->
                    m.groupValues[1].toInt(16).toChar().toString()
                }
            )
        } catch (_: Exception) { arg }
    }

    // ── JSON ──

    private inner class JsonParseFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            convertToLua(gson.fromJson(arg.checkjstring(), Any::class.java))
        } catch (_: Exception) { LuaValue.NIL }
    }

    private inner class JsonStringifyFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(gson.toJson(convertFromLua(arg)))
        } catch (_: Exception) { LuaValue.NIL }
    }

    // ── Misc ──

    private inner class DetectPaginationFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            val html = htmlFromValue(a1)
            val next = Jsoup.parse(html).select("a[href]:contains(next), a[href]:contains(›), a[href]:contains(»)")
            LuaTable().also { t ->
                t.set("hasNext", LuaValue.valueOf(next.isNotEmpty()))
                val nextUrl = next.firstOrNull()?.attr("abs:href")
                t.set("next_url", if (!nextUrl.isNullOrBlank()) LuaValue.valueOf(nextUrl) else LuaValue.NIL)
            }
        } catch (_: Exception) { LuaValue.NIL }
    }

    private inner class SleepFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val ms = arg.optlong(500)
            runBlocking { delay(ms) }
            return LuaValue.NIL
        }
    }

    private inner class LogInfoFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            println("[Lua INFO] ${arg.optjstring("")}")
            return LuaValue.NIL
        }
    }

    private inner class LogErrorFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            System.err.println("[Lua ERROR] ${arg.optjstring("")}")
            return LuaValue.NIL
        }
    }

    private inner class OsTimeFunction : ZeroArgFunction() {
        override fun call(): LuaValue = LuaValue.valueOf(System.currentTimeMillis().toDouble())
    }

    // ── Java ↔ Lua conversion ──

    fun convertToLua(obj: Any?): LuaValue = when (obj) {
        null -> LuaValue.NIL
        is String -> LuaValue.valueOf(obj)
        is Number -> LuaValue.valueOf(obj.toDouble())
        is Boolean -> LuaValue.valueOf(obj)
        is Map<*, *> -> LuaTable().also { t ->
            obj.forEach { (k, v) -> t.set(LuaValue.valueOf(k.toString()), convertToLua(v)) }
        }
        is List<*> -> LuaTable().also { t ->
            obj.forEachIndexed { i, v -> t.set(i + 1, convertToLua(v)) }
        }
        else -> LuaValue.valueOf(obj.toString())
    }

    fun convertFromLua(v: LuaValue): Any? = when {
        v.isnil() -> null
        v.isboolean() -> v.toboolean()
        v.isnumber() -> v.todouble()
        v.isstring() -> v.tojstring()
        v.istable() -> {
            val t = v.checktable(); val keys = t.keys()
            if (keys.all { it.isnumber() && it.toint() > 0 })
                (1..t.length()).map { convertFromLua(t.get(it)) }
            else
                keys.associate { it.tojstring() to convertFromLua(t.get(it)) }
        }
        else -> v.tojstring()
    }
}
