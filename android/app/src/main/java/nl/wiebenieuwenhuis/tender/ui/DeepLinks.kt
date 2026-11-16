package nl.wiebenieuwenhuis.tender.ui

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    /** A section at its start: a link does not land on what the section showed last. */
    private fun openSection(nav: NavHostController, route: Any) = selectTab(nav, route, alreadySelected = true)

    /** frigate://add?url=…&name=…: adds the server (it is tested on connecting). */
    fun addServer(uri: Uri) {
        val raw = uri.getQueryParameter("url") ?: return
        val base = ServerConfig.normalizedUrl(raw) ?: return
        if (AppModel.servers.any { it.url == base }) return
        AppModel.save(ServerConfig(name = uri.getQueryParameter("name") ?: Uri.parse(base).host ?: "Frigate", url = base), null)
    }

    suspend fun handle(uri: Uri, nav: NavHostController) {
        if (uri.scheme != "frigate") return
        val arg = uri.pathSegments.firstOrNull()
        val conn: ServerConnection? = uri.getQueryParameter("server")?.let { name ->
            AppModel.orderedConnections.firstOrNull { it.server.name.equals(name, ignoreCase = true) }
        } ?: (AppModel.scopeSelection as? Scope.Server)?.let { AppModel.connection(it.id) } ?: AppModel.orderedConnections.firstOrNull()

        when (uri.host) {
            "add" -> addServer(uri)
            "live" -> openSection(nav, LiveTab)
            "review" -> {
                uri.getQueryParameter("view")?.let { AppModel.pendingLink = "review:$it" }
                openSection(nav, ReviewTab)
            }
            "explore" -> openSection(nav, ExploreTab)
            "settings" -> { openSection(nav, MoreTab); nav.navigate(SettingsTab) }
            "chat" -> { openSection(nav, MoreTab); nav.navigate(ChatTab) }
            "camera" -> {
                conn ?: return; arg ?: return
                openSection(nav, LiveTab)
                nav.navigate(CameraRoute(conn.id, arg))
            }
            "playback" -> {
                conn ?: return; arg ?: return
                val day = uri.getQueryParameter("day")?.let { runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it) }.getOrNull() }
                val cal = Calendar.getInstance().apply {
                    if (day != null) time = day
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                openSection(nav, ExploreTab)
                nav.navigate(PlaybackRoute(conn.id, arg, cal.timeInMillis, uri.getQueryParameter("review"), uri.getQueryParameter("view") == "timeline"))
            }
            "event" -> {
                conn ?: return; arg ?: return
                // Opened at launch the server is still connecting: wait for it a little.
                val client = withTimeoutOrNull(15_000) { snapshotFlow { conn.client }.filterNotNull().first() } ?: return
                val item = runCatching { client.review(arg) }.getOrElse { Log.w("Tender", "Deep link: could not load review $arg: $it"); return }
                ItemCache.put(item)
                openSection(nav, ReviewTab)
                nav.navigate(EventRoute(conn.id, item.id))
            }
        }
    }
}
