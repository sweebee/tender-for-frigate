package nl.wiebenieuwenhuis.tender.ui.setup

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.WebInterfaceRoute
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold

/**
 * Frigate's own web interface inside the app. The login cookie lives in the system's web cookie
 * store (see `WebCookieJar`), so a server with a password opens already signed in.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebInterfaceScreen(route: WebInterfaceRoute) {
    val conn = AppModel.connection(route.serverId)
    val client = conn?.client
    val context = LocalContext.current
    val url = remember(client, route.path) { client?.urlString(route.path) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val background = MaterialTheme.colorScheme.surface.toArgb()

    BackHandler(enabled = canGoBack) { web?.goBack() }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }

    ScreenScaffold(
        title = conn?.server?.name ?: stringResource(R.string.setup_web_interface),
        actions = {
            IconButton(onClick = { web?.reload() }, enabled = web != null) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.setup_reload))
            }
            IconButton(onClick = {
                val target = web?.url ?: url ?: return@IconButton
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
            }, enabled = url != null) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.setup_open_in_browser))
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (url == null) {
                EmptyState(Icons.Filled.Refresh, stringResource(R.string.not_reachable))
                return@Box
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(background)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { canGoBack = view.canGoBack() }
                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { canGoBack = view.canGoBack() }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                        }
                        loadUrl(url)
                        web = this
                    }
                },
            )
            if (progress in 0.01f..0.99f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}
