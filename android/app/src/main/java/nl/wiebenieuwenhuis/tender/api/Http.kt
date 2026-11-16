package nl.wiebenieuwenhuis.tender.api

import android.webkit.CookieManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cookies live in the system's web cookie store, so Frigate's login cookie is the same for the
 * app's own requests, its image loader and the web views that play live video.
 */
object WebCookieJar : CookieJar {
    private val manager: CookieManager by lazy { CookieManager.getInstance().apply { setAcceptCookie(true) } }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) manager.setCookie(url.toString(), c.toString())
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = runCatching { manager.getCookie(url.toString()) }.getOrNull() ?: return emptyList()
        return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

object Http {
    /** One connection pool for the whole app. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebCookieJar)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun withTimeout(seconds: Long): OkHttpClient = client.newBuilder()
        .connectTimeout(minOf(seconds, 8), TimeUnit.SECONDS)
        .readTimeout(seconds, TimeUnit.SECONDS)
        .callTimeout(seconds + 2, TimeUnit.SECONDS)
        .build()
}

/** Runs a call on OkHttp's threads, cancelled with the coroutine. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
