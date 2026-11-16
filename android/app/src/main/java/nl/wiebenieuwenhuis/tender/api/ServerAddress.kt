package nl.wiebenieuwenhuis.tender.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Turns whatever someone types as a server address into the URLs worth trying, best guess
 * first. "192.168.1.20", "frigate.local:5000", "HTTP//nvr.example.com/review" and a link
 * pasted from the browser all work.
 */
object ServerAddress {
    /** Frigate's ports: 8971 with login, 5000 without. */
    val frigatePorts = listOf(8971, 5000)

    data class Parsed(val scheme: String?, val host: String, val port: Int?, val path: String)

    fun candidates(raw: String): List<HttpUrl> {
        val parsed = parse(raw) ?: return emptyList()
        val local = isLocal(parsed.host)
        val schemes: List<String> = when {
            parsed.scheme != null -> listOf(parsed.scheme)
            parsed.port == 443 -> listOf("https")
            parsed.port == 80 -> listOf("http")
            // At home Frigate usually serves plain http; a name on the internet usually sits behind https.
            local -> listOf("http", "https")
            else -> listOf("https", "http")
        }
        val list = mutableListOf<Pair<String, Int?>>()
        if (parsed.port != null) {
            schemes.forEach { list.add(it to parsed.port) }
        } else if (local && parsed.scheme == null) {
            for (s in schemes) for (p in frigatePorts) if (!(s == "https" && p == 5000)) list.add(s to p)
            for (s in schemes) list.add(s to null)
        } else {
            for (s in schemes) list.add(s to null)
            for (s in schemes) for (p in frigatePorts) if (!(s == "https" && p == 5000)) list.add(s to p)
        }
        val seen = mutableSetOf<String>()
        return list.mapNotNull { (scheme, port) ->
            val b = HttpUrl.Builder().scheme(scheme).host(parsed.host.trim('[', ']'))
            if (port != null) b.port(port)
            parsed.path.split("/").filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
            val url = runCatching { b.build() }.getOrNull() ?: return@mapNotNull null
            if (seen.add(url.toString())) url else null
        }
    }

    /**
     * The first of `candidates` that answers like Frigate: with its version, or asking for a
     * login. All are tried at once; a better guess still wins over a faster one, but once one
     * has answered the others get only `graceMs` more.
     */
    suspend fun firstReachable(candidates: List<HttpUrl>, timeoutMs: Long = 4000, graceMs: Long = 1000): HttpUrl? {
        if (candidates.size <= 1) return candidates.firstOrNull()
        return coroutineScope {
            val results = arrayOfNulls<Boolean>(candidates.size)
            val done = CompletableDeferred<HttpUrl?>()
            var graceStarted = false
            val lock = Any()
            fun check() {
                synchronized(lock) {
                    for ((j, r) in results.withIndex()) {
                        if (r == null) return
                        if (r) { done.complete(candidates[j]); return }
                    }
                    done.complete(null)
                }
            }
            val jobs = candidates.mapIndexed { i, url ->
                launch {
                    val ok = withTimeoutOrNull(timeoutMs) { answers(url) } ?: false
                    synchronized(lock) { results[i] = ok }
                    check()
                    if (ok) synchronized(lock) {
                        if (!graceStarted) {
                            graceStarted = true
                            launch {
                                delay(graceMs)
                                synchronized(lock) { done.complete(results.indexOfFirst { it == true }.takeIf { it >= 0 }?.let { candidates[it] }) }
                            }
                        }
                    }
                }
            }
            val r = done.await()
            jobs.forEach { it.cancel() }
            coroutineContext.cancelChildren()
            r
        }
    }

    private suspend fun answers(base: HttpUrl): Boolean = runCatching {
        val url = base.newBuilder().addPathSegment("api").addPathSegment("version").build()
        Http.withTimeout(4).newCall(Request.Builder().url(url).build()).await().use { res ->
            if (res.code == 401) return@use true
            if (res.code != 200) return@use false
            res.body.string().trim().firstOrNull()?.isDigit() == true
        }
    }.getOrDefault(false)

    /** True when the address names the full URL, so there is nothing to guess. */
    fun isComplete(raw: String): Boolean = parse(raw)?.let { it.scheme != null && it.port != null } ?: false

    /** The pages of Frigate's web app; a pasted link to one of them means the server root. */
    private val appPages = setOf("api", "review", "explore", "export", "exports", "settings", "system", "config", "logs",
        "faces", "classification", "chat", "live", "events", "recording", "login", "#")

    fun parse(raw: String): Parsed? {
        var s = raw.trim().replace(" ", "")
        if (s.isEmpty()) return null
        var scheme: String? = null
        Regex("^[hH]*[tT]+[pP]+[sS]*:?/{1,3}").find(s)?.let { m ->
            scheme = if (m.value.lowercase().contains("s")) "https" else "http"
            s = s.substring(m.range.last + 1)
        } ?: Regex("^https?:", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            scheme = if (m.value.lowercase().startsWith("https")) "https" else "http"
            s = s.substring(m.range.last + 1)
        }
        // Login details in the address belong in their own fields.
        val at = s.indexOf('@')
        if (at >= 0 && !s.substring(0, at).contains('/')) s = s.substring(at + 1)

        val hostEnd = s.indexOfFirst { it == '/' || it == '#' || it == '?' }.let { if (it < 0) s.length else it }
        var hostPort = s.substring(0, hostEnd)
        var rest = s.substring(hostEnd)
        // "192,168,1,20" from a keyboard with a comma.
        if (hostPort.all { it.isDigit() || it == ',' || it == ':' || it == '.' }) hostPort = hostPort.replace(',', '.')

        var host = hostPort
        var port: Int? = null
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(0, close + 1)
            val after = hostPort.substring(close + 1)
            if (after.startsWith(":")) port = after.drop(1).toIntOrNull()
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon >= 0) {
                host = hostPort.substring(0, colon)
                val p = hostPort.substring(colon + 1)
                if (p.isNotEmpty()) {
                    val n = p.toIntOrNull() ?: return null
                    if (n !in 1..65535) return null
                    port = n
                }
            }
        }
        host = host.lowercase().trim('.')
        if (host.isEmpty() || !Regex("^(\\[[0-9a-f:.]+]|[a-z0-9.-]+)$").matches(host)) return null

        val cut = rest.indexOfFirst { it == '#' || it == '?' }
        if (cut >= 0) rest = rest.substring(0, cut)
        var parts = rest.split("/").filter { it.isNotEmpty() }
        val i = parts.indexOfFirst { it.lowercase() in appPages }
        if (i >= 0) parts = parts.take(i)
        val path = if (parts.isEmpty()) "" else "/" + parts.joinToString("/")
        return Parsed(scheme, host, port, path)
    }

    /** An address on the home network or a private VPN, where Frigate's own ports are likely. */
    fun isLocal(host: String): Boolean {
        if (host.startsWith("[")) return true
        if (host.split(".").size == 4 && host.all { it.isDigit() || it == '.' }) return true
        if (!host.contains('.')) return true
        return listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain", ".ts.net").any { host.endsWith(it) }
    }

    /** Display form of the URL without scheme. */
    fun display(raw: String): String = raw.replace("https://", "").replace("http://", "").trim('/')

    /** Hides the password in an address such as rtsp://user:pass@host, for display and logs. */
    fun maskPassword(text: String): String = Regex("(://[^:/@\\s]+:)([^@\\s]+)(@)").replace(text) { "${it.groupValues[1]}••••${it.groupValues[3]}" }
}
