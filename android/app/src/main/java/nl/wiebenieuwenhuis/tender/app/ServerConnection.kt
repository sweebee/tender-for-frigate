package nl.wiebenieuwenhuis.tender.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.DayCounts
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateConfig
import nl.wiebenieuwenhuis.tender.api.FrigateException
import nl.wiebenieuwenhuis.tender.api.FrigateJson
import nl.wiebenieuwenhuis.tender.api.FrigateProfile
import nl.wiebenieuwenhuis.tender.api.FrigateStats
import nl.wiebenieuwenhuis.tender.api.Http
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.ServerAddress
import nl.wiebenieuwenhuis.tender.api.Severity
import nl.wiebenieuwenhuis.tender.api.StreamChoice
import nl.wiebenieuwenhuis.tender.api.bool
import nl.wiebenieuwenhuis.tender.api.dbl
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.jsonOf
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.str
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt

/** An object Frigate tracks right now, from its camera_activity message or its events feed. */
data class LiveObject(
    val id: String, val label: String, val subLabel: String?, val score: Double?,
    val ratio: Double?, val area: Double?, val stationary: Boolean,
) {
    companion object {
        fun from(d: JsonElement?): LiveObject? {
            val o = d.obj() ?: return null
            val label = o["label"].str() ?: return null
            val sub = o["sub_label"].let { it.str() ?: (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull().str() }
            return LiveObject(
                o["id"].str() ?: UUID.randomUUID().toString(),
                // A sub label confirmed by a model shows as "<label>-verified".
                label.replace("-verified", ""), sub, o["score"].dbl(), o["ratio"].dbl(), o["area"].dbl(),
                o["stationary"].bool() ?: false,
            )
        }
    }
}

/** What a camera sees right now: motion, and labels of objects that are moving. */
data class CameraActivity(
    val motion: Boolean = false,
    val tracking: List<String> = emptyList(),
    /** Every object Frigate tracks on the camera now, parked ones too (its debug object list). */
    val objects: List<LiveObject> = emptyList(),
)

sealed class ConnectionStatus {
    data object Connecting : ConnectionStatus()
    data object Online : ConnectionStatus()
    data class Offline(val message: String) : ConnectionStatus()
}

data class ServerProblem(val isError: Boolean, val text: String)

/** Live state of one configured server, as `ServerConnection` in the iOS app. */
class ServerConnection(server: ServerConfig, private val scope: CoroutineScope) {
    var server by mutableStateOf(server)
    val id: String get() = server.id
    var status by mutableStateOf<ConnectionStatus>(ConnectionStatus.Connecting)
    var client by mutableStateOf<FrigateClient?>(null)
    var usingRemote by mutableStateOf(false)
    var version by mutableStateOf<String?>(null)
    var config by mutableStateOf<FrigateConfig?>(null)
        private set
    /** The signed-in user's role; null when Frigate runs without authentication (everyone is admin). */
    var role by mutableStateOf<String?>(null)
    /** go2rtc stream names; null until loaded. */
    var go2rtcStreams by mutableStateOf<Set<String>?>(null)
    var stats by mutableStateOf<FrigateStats?>(null)
    var summary by mutableStateOf<DayCounts?>(null)
    private var polledObjects by mutableStateOf<Map<String, List<String>>>(emptyMap())
    /** The websocket has sent its first snapshot of tracked objects and is still open. */
    var feedLive by mutableStateOf(false)
        private set
    /** Start of the most recent review item, per camera. */
    var lastActivity by mutableStateOf<Map<String, Double>>(emptyMap())
    private var configLoaded = 0L
    /** Live motion and tracking per camera, pushed by Frigate over its websocket. */
    val activity = mutableStateMapOf<String, CameraActivity>()
    /** Cameras, detection and recording switched at runtime, as Frigate reports over its websocket. */
    val cameraEnabled = mutableStateMapOf<String, Boolean>()
    val cameraDetect = mutableStateMapOf<String, Boolean>()
    val cameraRecord = mutableStateMapOf<String, Boolean>()
    /** Switches sent to Frigate and not yet confirmed by its websocket ("<camera>/detect", …). */
    val pendingSwitches = mutableStateMapOf<String, Boolean>()
    /** Classification models' state from Frigate's websocket: "training", "complete", "failed", … */
    val modelStates = mutableStateMapOf<String, String>()
    var profiles by mutableStateOf<List<FrigateProfile>>(emptyList())
    var activeProfile by mutableStateOf<String?>(null)
    /** Frigate's notifications for a camera are paused until this moment (epoch seconds). */
    val notificationsPausedUntil = mutableStateMapOf<String, Double>()
    private var socket: WebSocket? = null
    private var lowered: Triple<Int, Int, Long>? = null

    val isOnline: Boolean get() = status == ConnectionStatus.Online
    val isAdmin: Boolean get() = role == null || role == "admin"
    val cameras: List<CameraConfig> get() = config?.orderedCameras ?: emptyList()

    fun camera(name: String): CameraConfig? = config?.cameras?.get(name)

    /** Labels of objects currently being tracked, per camera. */
    val activeObjects: Map<String, List<String>>
        get() = if (!feedLive) polledObjects else activity.mapValues { (_, a) -> a.objects.map { it.label }.distinct() }

    /** Supports custom classification (Frigate 0.17 and later). */
    val supportsClassification: Boolean get() = isOnline && Format.versionAtLeast(version, 0, 17)
    private val servesProfiles: Boolean get() = Format.versionAtLeast(version, 0, 18)

    fun zones(only: Set<String> = emptySet()): List<String> =
        cameras.filter { only.isEmpty() || it.name in only }.flatMap { it.zones }.distinct().sortedBy { Format.prettyName(it).lowercase() }

    var lastSeen: Date?
        get() = Prefs.long("lastSeen.${server.id}")?.let { Date(it) }
        set(value) { if (value != null) Prefs.setLong("lastSeen.${server.id}", value.time) }

    fun isCameraOnline(name: String): Boolean {
        if (!isOnline) return false
        val fps = stats?.cameras?.get(name)?.cameraFps ?: return true
        return fps > 0
    }

    fun isCameraEnabled(cam: CameraConfig): Boolean = cameraEnabled[cam.name] ?: cam.enabled ?: true
    fun isDetectEnabled(cam: CameraConfig): Boolean =
        cameraDetect[cam.name] ?: stats?.cameras?.get(cam.name)?.detectionEnabled ?: cam.detectEnabled ?: true
    fun isRecordEnabled(cam: CameraConfig): Boolean = cameraRecord[cam.name] ?: cam.record?.enabled ?: false
    /** Frigate switches recording on only when it is on in the config. */
    fun canSwitchRecording(cam: CameraConfig): Boolean = cam.record?.enabledInConfig ?: cam.record?.enabled ?: false
    fun isRecordSwitchedOff(cam: CameraConfig): Boolean = canSwitchRecording(cam) && !isRecordEnabled(cam)
    fun hasNotifications(cam: CameraConfig): Boolean = cam.notifications?.enabled == true
    fun notificationsPaused(cam: CameraConfig): Double? = notificationsPausedUntil[cam.name]?.takeIf { it > nowSeconds() }

    fun setCameraEnabled(cam: CameraConfig, on: Boolean) { if (sendSwitch(cam, "enabled", on)) cameraEnabled[cam.name] = on }
    fun setDetectEnabled(cam: CameraConfig, on: Boolean) { if (sendSwitch(cam, "detect", on)) cameraDetect[cam.name] = on }
    fun setRecordEnabled(cam: CameraConfig, on: Boolean) { if (sendSwitch(cam, "recordings", on)) cameraRecord[cam.name] = on }
    fun isSwitchPending(cam: CameraConfig, topic: String): Boolean = pendingSwitches.containsKey("${cam.name}/$topic")

    /** Pauses Frigate's notifications for a camera for some minutes (0: resume), as its web interface does. */
    fun pauseNotifications(cam: CameraConfig, minutes: Int) {
        if (!command("${cam.name}/notifications/suspend", minutes.toString())) return
        if (minutes > 0) notificationsPausedUntil[cam.name] = nowSeconds() + minutes * 60 else notificationsPausedUntil.remove(cam.name)
    }

    /** Asks Frigate for every model's state; the answer comes as "model_state". */
    fun requestModelStates() { command("modelState", "") }

    val activeProfileTitle: String?
        get() = activeProfile?.let { name -> profiles.firstOrNull { it.name == name }?.title ?: Format.prettyName(name) }

    /** Activates a profile (or none), shown at once; Frigate's websocket confirms it. */
    suspend fun setProfile(name: String?) {
        val c = client ?: return
        val before = activeProfile
        activeProfile = name
        try { c.setProfile(name) } catch (e: Exception) { activeProfile = before; throw e }
    }

    /** Sends `<camera>/<topic>/set` ON or OFF over Frigate's websocket. */
    private fun sendSwitch(cam: CameraConfig, topic: String, on: Boolean): Boolean {
        if (!command("${cam.name}/$topic/set", if (on) "ON" else "OFF")) return false
        val key = "${cam.name}/$topic"
        pendingSwitches[key] = true
        // Frigate answers within a second; stop waiting after a while so the switch is usable again.
        scope.launch { delay(6000); pendingSwitches.remove(key) }
        return true
    }

    /** Sends a command over Frigate's websocket, as its web interface does. False without a connection. */
    fun command(topic: String, payload: String): Boolean {
        startActivityFeed()
        val ws = socket ?: return false
        return ws.send(jsonOf(mapOf("topic" to topic, "payload" to payload, "retain" to false)).toString())
    }

    /** What is wrong with the server, as Frigate's status bar lists it: errors first; empty when all is well. */
    val problems: List<ServerProblem>
        get() {
            val stats = stats ?: return emptyList()
            val config = config ?: return emptyList()
            if (!isOnline) return emptyList()
            if ((stats.uptime ?: 1000.0) < 120) return emptyList()
            val out = mutableListOf<ServerProblem>()
            if (stats.retentionUnmet) out.add(ServerProblem(true, L(R.string.problem_disk_small)))
            for ((name, speed) in stats.detectors.entries.sortedBy { it.key }) {
                speed ?: continue
                if (speed > 100) out.add(ServerProblem(true, L(R.string.problem_detector_very_slow, name, speed.roundToInt())))
                else if (speed > 50) out.add(ServerProblem(false, L(R.string.problem_detector_slow, name, speed.roundToInt())))
            }
            for (cam in config.orderedCameras) {
                if (!isCameraEnabled(cam)) continue
                val s = stats.cameras[cam.name] ?: continue
                if (s.cameraFps == 0.0) out.add(ServerProblem(true, L(R.string.problem_camera_offline, cam.displayName)))
                else if ((s.skippedPct ?: 0.0) >= 5) out.add(ServerProblem(false, L(R.string.problem_camera_skips, cam.displayName, s.skippedPct!!.roundToInt())))
            }
            return out.sortedBy { if (it.isError) 0 else 1 }
        }

    /** "6 cameras · v0.18 · Local" or "Offline · last seen 2 days ago". */
    val metaLine: String
        get() = when (status) {
            ConnectionStatus.Connecting -> L(R.string.connecting)
            is ConnectionStatus.Offline -> lastSeen?.let { L(R.string.offline_last_seen, Format.relative(it).lowercase()) } ?: L(R.string.offline)
            ConnectionStatus.Online -> buildList {
                add(nl.wiebenieuwenhuis.tender.api.Lq(R.plurals.n_cameras, cameras.size, cameras.size))
                version?.let { add(Format.shortVersion(it)) }
                add(if (usingRemote) L(R.string.remote) else L(R.string.local))
            }.joinToString(" · ")
        }

    val unreviewedAlerts: Int
        get() {
            val server = summary?.unreviewedAlerts ?: 0
            val l = lowered ?: return server
            return if (l.third > System.currentTimeMillis()) minOf(server, l.first) else server
        }
    val unreviewedDetections: Int
        get() {
            val server = summary?.unreviewedDetections ?: 0
            val l = lowered ?: return server
            return if (l.third > System.currentTimeMillis()) minOf(server, l.second) else server
        }

    /** Lowers the unreviewed counts at once by these items (of the last 24 hours, not yet reviewed). */
    fun noteReviewed(items: List<ReviewItem>) {
        val cutoff = nowSeconds() - 86400
        val fresh = items.filter { !it.hasBeenReviewed && it.startTime >= cutoff }
        if (fresh.isEmpty()) return
        val alerts = fresh.count { it.severity == Severity.Alert }
        lowered = Triple(maxOf(0, unreviewedAlerts - alerts), maxOf(0, unreviewedDetections - (fresh.size - alerts)), System.currentTimeMillis() + 15000)
        summary = summary?.copy() // redraw
    }

    fun liveStreams(camera: CameraConfig, inFrigateOrder: Boolean = true): List<StreamChoice> =
        camera.streams(go2rtcStreams, inFrigateOrder)

    // MARK: Connecting

    private class Attempt(val client: FrigateClient, val remote: Boolean, val version: String, val config: FrigateConfig)

    /**
     * Connects through the home address or the remote one. Both are tried at once: the home
     * address wins when it answers, and the remote one is taken as soon as the home address
     * fails, or has not answered within a moment after the remote one did.
     */
    suspend fun connect() {
        if (client == null) status = ConnectionStatus.Connecting
        val candidates = buildList {
            ServerConfig.normalizedUrl(server.url)?.let { add(it to false) }
            server.remoteUrl?.takeIf { it.isNotBlank() }?.let { ServerConfig.normalizedUrl(it) }?.let { add(it to true) }
        }
        if (candidates.isEmpty()) { status = ConnectionStatus.Offline(FrigateException.InvalidUrl().message ?: ""); return }
        val srv = server
        val password = srv.password
        var lastError: Throwable = FrigateException.NotFrigate()
        val picked: Attempt? = coroutineScope {
            val jobs = candidates.map { (url, remote) ->
                async(Dispatchers.IO) { runCatching { attempt(url, remote, srv, password) } }
            }
            val homeJob = candidates.indexOfFirst { !it.second }.takeIf { it >= 0 }?.let { jobs[it] }
            val remoteJob = candidates.indexOfFirst { it.second }.takeIf { it >= 0 }?.let { jobs[it] }
            var result: Attempt? = null
            if (homeJob != null && remoteJob == null) {
                homeJob.await().onSuccess { result = it }.onFailure { lastError = it }
            } else if (homeJob == null && remoteJob != null) {
                remoteJob.await().onSuccess { result = it }.onFailure { lastError = it }
            } else if (homeJob != null && remoteJob != null) {
                // Whichever finishes first.
                val first = select<Pair<Boolean, Result<Attempt>>> {
                    homeJob.onAwait { false to it }
                    remoteJob.onAwait { true to it }
                }
                if (!first.first) {
                    first.second.onSuccess { result = it }.onFailure { lastError = it }
                    if (result == null) remoteJob.await().onSuccess { result = it }.onFailure { lastError = it }
                } else {
                    val remote = first.second.getOrNull()
                    first.second.exceptionOrNull()?.let { lastError = it }
                    if (remote == null) {
                        homeJob.await().onSuccess { result = it }.onFailure { lastError = it }
                    } else {
                        // Give the home address a moment more; it wins when it answers.
                        val home = kotlinx.coroutines.withTimeoutOrNull(1500) { homeJob.await() }
                        result = home?.getOrNull() ?: remote
                    }
                }
            }
            jobs.forEach { it.cancel() }
            result
        }
        val a = picked ?: run { status = ConnectionStatus.Offline(lastError.message ?: L(R.string.not_reachable)); return }
        if (client != null && client?.baseUrl != a.client.baseUrl) stopActivityFeed()
        client = a.client
        usingRemote = a.remote
        version = a.version
        applyConfig(a.config)
        configLoaded = System.currentTimeMillis()
        go2rtcStreams = runCatching { a.client.go2rtcStreams() }.getOrNull()
        role = a.client.role()
        status = ConnectionStatus.Online
        lastSeen = Date()
        refresh()
    }

    fun applyConfig(c: FrigateConfig?) {
        config = c
        Format.serverTimeFormats[server.id] = c?.timeFormat
        Format.refreshClock()
    }

    private suspend fun attempt(url: String, remote: Boolean, server: ServerConfig, password: String?): Attempt {
        val c = FrigateClient.of(url) ?: throw FrigateException.InvalidUrl()
        val v = authenticate(c, server, password)
        c.version = v
        return Attempt(c, remote, v, c.config())
    }

    /** Re-reads the config, e.g. after a settings change. */
    suspend fun reloadConfig() {
        val c = client ?: return
        runCatching { c.config() }.getOrNull()?.let { applyConfig(it); configLoaded = System.currentTimeMillis() }
        runCatching { c.go2rtcStreams() }.getOrNull()?.let { go2rtcStreams = it }
    }

    /** Refreshes the frequently changing state; `reloadConfig` also fetches the config again. */
    suspend fun refresh(reloadConfig: Boolean = false) {
        val c = client ?: return
        if (servesProfiles) runCatching { c.profiles() }.getOrNull()?.let { (list, active) -> profiles = list; activeProfile = active }
        if (reloadConfig || System.currentTimeMillis() - configLoaded > 300_000) reloadConfig()
        val pollObjects = !feedLive
        val (s, sum, active, recent) = coroutineScope {
            val a = async { runCatching { c.stats() }.getOrNull() }
            val b = async { runCatching { c.reviewSummary() }.getOrNull() }
            val d = async { if (pollObjects) runCatching { c.activeEvents() }.getOrNull() else null }
            val e = async { runCatching { c.reviews(after = nowSeconds() - 86400, limit = 200) }.getOrNull() }
            listOf(a.await(), b.await(), d.await(), e.await())
        }
        coroutineContextEnsureActive()
        if (s == null && sum == null) {
            if (runCatching { c.version(4) }.isFailure) { status = ConnectionStatus.Offline(L(R.string.not_reachable)); return }
        }
        status = ConnectionStatus.Online
        lastSeen = Date()
        startActivityFeed()
        (s as? FrigateStats)?.let { stats = it }
        (sum as? DayCounts)?.let { summary = it }
        @Suppress("UNCHECKED_CAST")
        (active as? List<nl.wiebenieuwenhuis.tender.api.FrigateEvent>)?.let { list ->
            polledObjects = list.groupBy { it.camera }.mapValues { (_, v) -> v.map { it.label }.distinct() }
        }
        @Suppress("UNCHECKED_CAST")
        (recent as? List<ReviewItem>)?.let { list ->
            lastActivity = list.groupBy { it.camera }.mapValues { (_, v) -> v.maxOf { it.startTime } }
        }
    }

    private suspend fun coroutineContextEnsureActive() = kotlin.coroutines.coroutineContext.ensureActive()

    // MARK: Activity feed

    /** Opens Frigate's websocket for motion and tracked objects, unless it is already open. */
    fun startActivityFeed() {
        val c = client ?: return
        if (socket != null) return
        val req = Request.Builder().url(c.socketUrl("ws")).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"topic":"onConnect","message":""}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { FrigateJson.parseToJsonElement(text) }.getOrNull() ?: return
                scope.launch { if (socket === webSocket) handleActivity(msg) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended(webSocket)
            private fun ended(ws: WebSocket) {
                scope.launch {
                    if (socket === ws) { socket = null; activity.clear(); feedLive = false }
                }
            }
        }
        socket = Http.client.newWebSocket(req, listener)
    }

    fun stopActivityFeed() {
        socket?.close(1001, null)
        socket = null
        activity.clear()
        feedLive = false
    }

    /**
     * Frigate sends the tracked objects once on connecting (camera_activity) and then each
     * change as an "events" message: new, update or end. Kept up to date like Frigate's red outline.
     */
    private fun applyTrackedObjectEvent(event: JsonElement) {
        val type = event["type"].str() ?: return
        val after = event["after"].obj() ?: return
        val cam = after["camera"].str() ?: return
        val id = after["id"].str() ?: return
        val act = activity[cam] ?: CameraActivity()
        val objects = act.objects.toMutableList()
        val index = objects.indexOfFirst { it.id == id }
        if (type == "end") {
            if (index >= 0) objects.removeAt(index)
        } else if (after["false_positive"].bool() == true) {
            return
        } else {
            var obj = LiveObject.from(after) ?: return
            if (index >= 0) {
                // camera_activity names an object by its attribute ("dhl" for a car); keep that name.
                obj = obj.copy(label = objects[index].label)
                objects[index] = obj
            } else if (!obj.stationary) {
                // As the web UI: a new object counts once it moves.
                objects.add(obj)
            }
        }
        val next = act.copy(objects = objects, tracking = objects.filter { !it.stationary }.map { it.label })
        if (next != act) activity[cam] = next
    }

    private fun handleActivity(msg: JsonElement) {
        val topic = msg["topic"].str() ?: return
        val payload = msg["payload"]
        fun payloadJson(): JsonElement? = payload.str()?.let { runCatching { FrigateJson.parseToJsonElement(it) }.getOrNull() }
        when {
            topic == "camera_activity" -> {
                val cams = payloadJson().obj() ?: return
                val next = mutableMapOf<String, CameraActivity>()
                for ((cam, info) in cams) {
                    // The first message after connecting carries each camera's switches.
                    info["config"].obj()?.let { cfg ->
                        cfg["enabled"].bool()?.let { cameraEnabled[cam] = it }
                        cfg["detect"].bool()?.let { cameraDetect[cam] = it }
                        cfg["record"].bool()?.let { cameraRecord[cam] = it }
                        cfg["notifications_suspended"].dbl()?.let { until ->
                            if (until > 0) notificationsPausedUntil[cam] = until else notificationsPausedUntil.remove(cam)
                        }
                    }
                    val objects = (info["objects"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { LiveObject.from(it) } ?: emptyList()
                    next[cam] = CameraActivity(info["motion"].bool() ?: false, objects.filter { !it.stationary }.map { it.label }, objects)
                }
                activity.clear(); activity.putAll(next)
                feedLive = true
            }
            topic == "events" -> payloadJson()?.let { applyTrackedObjectEvent(it) }
            topic == "model_state" -> payloadJson().obj()?.forEach { (k, v) -> v.str()?.let { modelStates[k] = it } }
            topic == "profile/state" -> { val p = payload.str(); activeProfile = if (p.isNullOrEmpty() || p == "none") null else p }
            topic.endsWith("/notifications/suspended") -> {
                val cam = topic.removeSuffix("/notifications/suspended")
                val until = payload.str()?.toDoubleOrNull() ?: 0.0
                if (until > 0) notificationsPausedUntil[cam] = until else notificationsPausedUntil.remove(cam)
            }
            else -> {
                val parts = topic.split("/")
                val state = payload.str() ?: return
                if (parts.size == 3 && parts[2] == "state" && parts[1] in setOf("enabled", "detect", "recordings")) {
                    val cam = parts[0]
                    pendingSwitches.remove("$cam/${parts[1]}")
                    when (parts[1]) {
                        "enabled" -> cameraEnabled[cam] = state == "ON"
                        "detect" -> cameraDetect[cam] = state == "ON"
                        else -> cameraRecord[cam] = state == "ON"
                    }
                } else if (parts.size == 2 && parts[1] == "motion") {
                    val cam = parts[0]
                    activity[cam] = (activity[cam] ?: CameraActivity()).copy(motion = state == "ON")
                }
            }
        }
    }

    companion object {
        /** Checks the server responds, logging in first when credentials are set. Returns the version. */
        suspend fun authenticate(client: FrigateClient, server: ServerConfig, password: String?): String {
            val user = server.username
            if (!user.isNullOrEmpty()) {
                // Probe first: an unauthenticated port answers without a login.
                runCatching { return client.version(5) }
                client.login(user, password ?: "")
            }
            return client.version()
        }
    }
}
