package nl.wiebenieuwenhuis.tender.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.TenderApplication
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.ui.theme.ServerTints

/** Which servers the tabs show: all, or one. */
sealed class Scope {
    data object All : Scope()
    data class Server(val id: String) : Scope()
}

/** Live video in the overview tiles, as one choice (stored in `liveGrid` and `autoLive`). */
enum class TileLive { Always, OnActivity, Off }

/** Which stream live tiles play. */
enum class TileStream(val raw: String) { Automatic("automatic"), Main("main"), Sub("sub") }

/** The app's state, shared by every screen, as `AppModel` in the iOS app. */
object AppModel {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val servers = mutableStateListOf<ServerConfig>()
    val connections = mutableStateMapOf<String, ServerConnection>()
    var scopeSelection by mutableStateOf<Scope>(Scope.All)

    /** Review items marked reviewed in this session (Frigate may take a moment to reflect it). */
    val reviewedIds = mutableStateMapOf<String, Boolean>()

    /** Exports deleted in this session; lists leave them out. */
    val deletedExports = mutableStateMapOf<String, Boolean>()
    val busyExports = mutableStateMapOf<String, Boolean>()

    /** A request from a deep link or another screen, picked up by the tab it names. */
    var pendingLink by mutableStateOf<String?>(null)

    /** Set while the network is metered (mobile data or a hotspot). */
    var isExpensiveNetwork by mutableStateOf(false)
        private set

    // MARK: Settings (as UserDefaults in the iOS app)

    var liveGrid by mutableStateOf(Prefs.bool("liveGrid", false)); private set
    var liveGridOnCellular by mutableStateOf(Prefs.bool("liveGridOnCellular", false)); private set
    var autoLive by mutableStateOf(Prefs.bool("autoLive", true)); private set
    var playAlertPreviews by mutableStateOf(Prefs.bool("playAlertPreviews", true)); private set
    var showRecentAlerts by mutableStateOf(Prefs.bool("showRecentAlerts", true)); private set
    var largeTiles by mutableStateOf(Prefs.bool("liveLargeTiles", false)); private set
    /** Tiles show the camera's whole picture in its own shape; wide cameras take two columns and standing ones two rows, as in Frigate. */
    var tileWholePicture by mutableStateOf(Prefs.bool("tileWholePicture", false)); private set
    /** Object icons, the motion dot and switched-off icons on the tiles' picture. */
    var liveLabels by mutableStateOf(Prefs.bool("liveLabels", true)); private set
    /** The camera group Live shows (a name, or the "Now active" chip); null for all cameras. Kept across launches. */
    var liveGroup by mutableStateOf(Prefs.string("liveGroup")); private set
    var tileStream by mutableStateOf(TileStream.entries.firstOrNull { it.raw == Prefs.string("tileStream") } ?: TileStream.Automatic); private set

    fun updateLiveGridOnCellular(v: Boolean) { liveGridOnCellular = v; Prefs.setBool("liveGridOnCellular", v) }
    fun updatePlayAlertPreviews(v: Boolean) { playAlertPreviews = v; Prefs.setBool("playAlertPreviews", v) }
    fun updateShowRecentAlerts(v: Boolean) { showRecentAlerts = v; Prefs.setBool("showRecentAlerts", v) }
    fun updateLargeTiles(v: Boolean) { largeTiles = v; Prefs.setBool("liveLargeTiles", v) }
    fun updateTileWholePicture(v: Boolean) { tileWholePicture = v; Prefs.setBool("tileWholePicture", v) }
    fun updateLiveLabels(v: Boolean) { liveLabels = v; Prefs.setBool("liveLabels", v) }
    fun updateLiveGroup(v: String?) { liveGroup = v; Prefs.setString("liveGroup", v) }
    fun updateTileStream(v: TileStream) { tileStream = v; Prefs.setString("tileStream", v.raw) }

    var tileLive: TileLive
        get() = if (liveGrid) TileLive.Always else if (autoLive) TileLive.OnActivity else TileLive.Off
        set(v) {
            liveGrid = v == TileLive.Always; autoLive = v != TileLive.Off
            Prefs.setBool("liveGrid", liveGrid); Prefs.setBool("autoLive", autoLive)
        }

    /** Whether the overview should show live video right now. */
    val showsLiveGrid: Boolean get() = liveGrid && (liveGridOnCellular || !isExpensiveNetwork)

    fun init(context: Context) {
        servers.addAll(Prefs.loadServers())
        Prefs.string("scope")?.let { s -> if (servers.any { it.id == s }) scopeSelection = Scope.Server(s) }
        for (s in servers) connections[s.id] = ServerConnection(s, scope)
        watchNetwork(context)
    }

    private fun saveServers() = Prefs.saveServers(servers.toList())

    fun selectScope(s: Scope) {
        scopeSelection = s
        Prefs.setString("scope", (s as? Scope.Server)?.id)
    }

    val orderedConnections: List<ServerConnection> get() = servers.mapNotNull { connections[it.id] }
    val onlineConnections: List<ServerConnection> get() = orderedConnections.filter { it.isOnline }

    /** Connections in the current scope. */
    val scopedConnections: List<ServerConnection>
        get() = when (val s = scopeSelection) {
            Scope.All -> orderedConnections
            is Scope.Server -> listOfNotNull(connections[s.id])
        }

    val scopeTitle: String
        get() = when (val s = scopeSelection) {
            Scope.All -> L(R.string.all_servers)
            is Scope.Server -> connections[s.id]?.server?.name ?: L(R.string.server)
        }

    val unreviewedAlerts: Int get() = scopedConnections.sumOf { it.unreviewedAlerts }

    fun connection(id: String): ServerConnection? = connections[id]

    val classificationConnections: List<ServerConnection> get() = scopedConnections.filter { it.supportsClassification }
    val faceConnections: List<ServerConnection> get() = scopedConnections.filter { it.isOnline && it.config?.faceRecognition == true }
    val chatConnections: List<ServerConnection> get() = scopedConnections.filter { it.isOnline && it.config?.supportsChat == true }

    suspend fun connectAll() = coroutineScope { orderedConnections.forEach { c -> launch { c.connect() } } }

    /** `reloadConfig` for user-started refreshes (pull to refresh, returning to the app). */
    suspend fun refreshAll(reloadConfig: Boolean = false) = coroutineScope {
        orderedConnections.forEach { c ->
            launch { if (c.client == null || !c.isOnline) c.connect() else c.refresh(reloadConfig) }
        }
    }

    fun stopFeeds() = orderedConnections.forEach { it.stopActivityFeed() }

    // MARK: Editing servers

    fun save(server: ServerConfig, password: String?) {
        var s = server
        val i = servers.indexOfFirst { it.id == s.id }
        if (i >= 0) {
            servers[i] = s
        } else {
            val used = servers.map { it.tintIndex }.toSet()
            s = s.copy(tintIndex = ServerTints.indices.firstOrNull { it !in used } ?: servers.size)
            servers.add(s)
        }
        s.password = password
        connections[s.id]?.stopActivityFeed()
        val conn = ServerConnection(s, scope)
        connections[s.id] = conn
        if (servers.size == 1) selectScope(Scope.Server(s.id))
        saveServers()
        scope.launch { conn.connect() }
    }

    fun moveServer(from: Int, to: Int) {
        if (from !in servers.indices || to !in servers.indices) return
        val s = servers.removeAt(from)
        servers.add(to, s)
        saveServers()
    }

    fun delete(server: ServerConfig) {
        server.password = null
        connections[server.id]?.stopActivityFeed()
        servers.removeAll { it.id == server.id }
        connections.remove(server.id)
        if (scopeSelection == Scope.Server(server.id)) selectScope(Scope.All)
        saveServers()
    }

    // MARK: Network

    private var networkJob: Job? = null
    private var networkSignature: String? = null

    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val expensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                val sig = "$network ${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} ${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}"
                scope.launch {
                    isExpensiveNetwork = expensive
                    val old = networkSignature
                    networkSignature = sig
                    if (old != null && old != sig) networkChanged()
                }
            }
        })
    }

    /** The network changed: servers with a remote address pick an address again, offline servers retry. */
    private fun networkChanged() {
        networkJob?.cancel()
        networkJob = scope.launch {
            delay(1500)
            coroutineScope {
                orderedConnections.filter { !it.server.remoteUrl.isNullOrEmpty() || !it.isOnline }.forEach { c -> launch { c.connect() } }
            }
        }
    }

    val context: Context get() = TenderApplication.context
}
