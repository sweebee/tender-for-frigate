package nl.wiebenieuwenhuis.tender.ui

import android.net.Uri
import android.util.Log
import androidx.navigation.NavHostController
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.Scope
import nl.wiebenieuwenhuis.tender.app.ServerConfig
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Handles `frigate://` links, as the iOS app:
 *   frigate://add?url=http://host:8971&name=Home      add a server
 *   frigate://live | review | explore | chat | settings
 *   frigate://review?view=motion
 *   frigate://camera/<camera>[?server=<name>]
 *   frigate://playback/<camera>[?day=yyyy-MM-dd&review=<id>&view=timeline]
 *   frigate://event/<review id>[?server=<name>]
 */
object DeepLinks {
    suspend fun handle(uri: Uri, nav: NavHostController) {
        if (uri.scheme != "frigate") return
        val arg = uri.pathSegments.firstOrNull()
        val conn: ServerConnection? = uri.getQueryParameter("server")?.let { name ->
            AppModel.orderedConnections.firstOrNull { it.server.name.equals(name, ignoreCase = true) }
        } ?: (AppModel.scopeSelection as? Scope.Server)?.let { AppModel.connection(it.id) } ?: AppModel.orderedConnections.firstOrNull()

        when (uri.host) {
            "add" -> {
                val raw = uri.getQueryParameter("url") ?: return
                val base = ServerConfig.normalizedUrl(raw) ?: return
                if (AppModel.servers.any { it.url == base }) return
                AppModel.save(ServerConfig(name = uri.getQueryParameter("name") ?: Uri.parse(base).host ?: "Frigate", url = base), null)
            }
            "live" -> selectTab(nav, LiveTab)
            "review" -> {
                uri.getQueryParameter("view")?.let { AppModel.pendingLink = "review:$it" }
                selectTab(nav, ReviewTab)
            }
            "explore" -> selectTab(nav, ExploreTab)
            "settings" -> { selectTab(nav, MoreTab); nav.navigate(SettingsTab) }
            "chat" -> { selectTab(nav, MoreTab); nav.navigate(ChatTab) }
            "camera" -> {
                conn ?: return; arg ?: return
                selectTab(nav, LiveTab)
                nav.navigate(CameraRoute(conn.id, arg))
            }
            "playback" -> {
                conn ?: return; arg ?: return
                val day = uri.getQueryParameter("day")?.let { runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it) }.getOrNull() }
                val cal = Calendar.getInstance().apply {
                    if (day != null) time = day
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                selectTab(nav, ExploreTab)
                nav.navigate(PlaybackRoute(conn.id, arg, cal.timeInMillis, uri.getQueryParameter("review"), uri.getQueryParameter("view") == "timeline"))
            }
            "event" -> {
                conn ?: return; arg ?: return
                val client = conn.client ?: return
                val item = runCatching { client.review(arg) }.getOrElse { Log.w("Tender", "Deep link: could not load review $arg: $it"); return }
                ItemCache.put(item)
                selectTab(nav, ReviewTab)
                nav.navigate(EventRoute(conn.id, item.id))
            }
        }
    }
}
