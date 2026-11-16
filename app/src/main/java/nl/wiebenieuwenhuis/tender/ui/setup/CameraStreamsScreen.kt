package nl.wiebenieuwenhuis.tender.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateException
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.StreamAddress
import nl.wiebenieuwenhuis.tender.api.StreamProbe
import nl.wiebenieuwenhuis.tender.api.arr
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.probeStream
import nl.wiebenieuwenhuis.tender.api.rawPaths
import nl.wiebenieuwenhuis.tender.api.str
import nl.wiebenieuwenhuis.tender.api.strings
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.CameraStreamsRoute
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.ErrorBanner
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.util.UUID

// A camera's streams in one list: the go2rtc streams it reads (and those it only shows in live
// view) and the inputs that open the camera directly. A stream can be renamed (its go2rtc
// stream, the camera's restream address and its live view entries change along), get another
// address, other roles, be removed or added. Changes are saved together and Frigate restarts,
// since go2rtc reads its streams only when it starts.

/** One stream as the Streams page shows it. */
internal data class CameraStream(
    val id: String = UUID.randomUUID().toString(),
    /** The go2rtc stream it goes through; null for an input that opens the camera itself. */
    val name: String?,
    /** go2rtc's sources for it, or the input's own address. */
    val sources: List<String>,
    /** Detect, record, audio. Empty for a go2rtc stream Frigate does not read (live view only). */
    val roles: List<String> = emptyList(),
    /** Its place among the camera's inputs as loaded, for the input's own ffmpeg arguments. */
    val inputIndex: Int? = null,
    /** What follows the name in the camera's restream address ("?video=copy"). */
    val restreamSuffix: String = "",
    /** Live view entries that show it ("Main"). */
    val liveLabels: List<String> = emptyList(),
    /** Other cameras that read the same go2rtc stream. */
    val sharedWith: List<String> = emptyList(),
    /** Its address cannot change here: a map source in go2rtc, or a password from a {FRIGATE_*} variable. */
    val locked: Boolean = false,
) {
    /** Frigate reads it as one of the camera's inputs. */
    val isInput: Boolean get() = name == null || roles.isNotEmpty()
}

internal fun roleTitle(role: String): String = when (role) {
    "detect" -> L(R.string.setup_role_detect)
    "record" -> L(R.string.setup_role_record)
    "audio" -> L(R.string.setup_role_audio)
    else -> role
}

internal fun roleOrder(role: String): Int = listOf("detect", "record", "audio").indexOf(role).let { if (it < 0) 9 else it }

/** The input keys Frigate keeps per input besides its path and roles. */
private val inputArgKeys = listOf("global_args", "hwaccel_args", "input_args")

/** The input's own ffmpeg arguments as Frigate shows them, to write back with the input. */
internal fun inputExtras(shown: JsonElement?): Map<String, JsonElement> {
    val out = mutableMapOf<String, JsonElement>()
    for (key in inputArgKeys) {
        val v = shown[key] ?: continue
        if (v.str()?.isNotEmpty() == true || v.arr()?.isNotEmpty() == true) out[key] = v
    }
    return out
}

private class LoadedStreams(
    val streams: List<CameraStream>, val extras: Map<Int, Map<String, JsonElement>>, val defined: Set<String>,
    val prefix: String?, val liveOrder: List<String>,
)

private fun parseStreams(raw: JsonObject, config: JsonObject, yaml: String, camera: String): LoadedStreams {
    val cameras = config["cameras"].obj() ?: JsonObject(emptyMap())
    val cam = cameras[camera]
    val rawInputs = raw["cameras"][camera]["ffmpeg"]["inputs"].arr() ?: emptyList()
    val shownInputs = cam["ffmpeg"]["inputs"].arr() ?: emptyList()
    val definedStreams = raw["go2rtc"]["streams"].obj() ?: JsonObject(emptyMap())
    val live: Map<String, String> = cam["live"]["streams"].obj()?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap() ?: emptyMap()

    // Who else reads each go2rtc stream.
    val users = mutableMapOf<String, MutableList<String>>()
    for ((name, other) in cameras) {
        if (name == camera) continue
        val title = other["friendly_name"].str() ?: Format.prettyName(name)
        val names = mutableSetOf<String>()
        for (input in other["ffmpeg"]["inputs"].arr() ?: emptyList()) {
            input["path"].str()?.let { StreamAddress.restreamName(it) }?.let { names.add(it) }
        }
        other["live"]["streams"].obj()?.values?.mapNotNull { it.str() }?.let { names.addAll(it) }
        for (n in names) users.getOrPut(n) { mutableListOf() }.add(title)
    }

    fun sources(v: JsonElement?): List<String>? = v.str()?.let { listOf(it) } ?: v.arr()?.let { a ->
        val list = a.mapNotNull { it.str() }
        if (list.size == a.size) list else null
    }
    fun liveLabels(name: String) = live.filter { it.value == name }.keys.sorted()

    val streams = mutableListOf<CameraStream>()
    val extras = mutableMapOf<Int, Map<String, JsonElement>>()
    for ((i, input) in rawInputs.withIndex()) {
        val path = input["path"].str() ?: ""
        val roles = (input["roles"].strings() ?: emptyList()).sortedBy { roleOrder(it) }
        if (i < shownInputs.size) extras[i] = inputExtras(shownInputs[i])
        val name = StreamAddress.restreamName(path)
        if (name != null && definedStreams.containsKey(name)) {
            val src = sources(definedStreams[name])
            val marker = ":8554/$name"
            val suffix = path.indexOf(marker).let { if (it < 0) "" else path.substring(it + marker.length) }
            streams.add(CameraStream(name = name, sources = src ?: emptyList(), roles = roles, inputIndex = i, restreamSuffix = suffix,
                liveLabels = liveLabels(name), sharedWith = users[name] ?: emptyList(), locked = src == null))
        } else {
            // Frigate fills {FRIGATE_*} variables in inputs; such an address is not in the file as shown.
            val fromVariable = StreamAddress.password(path) != null && !yaml.contains(path)
            streams.add(CameraStream(name = null, sources = listOf(path), roles = roles, inputIndex = i, locked = fromVariable))
        }
    }
    for (name in live.values.toSet().sorted()) {
        if (!definedStreams.containsKey(name) || streams.any { it.name == name }) continue
        val src = sources(definedStreams[name])
        streams.add(CameraStream(name = name, sources = src ?: emptyList(), liveLabels = liveLabels(name),
            sharedWith = users[name] ?: emptyList(), locked = src == null))
    }

    // A new stream starts at the camera's own address.
    val camUrl = streams.flatMap { it.sources }.firstOrNull { StreamAddress.host(it) != null && !StreamAddress.isLocal(it) && !it.contains("{") }
    val prefix = camUrl?.let { url ->
        val scheme = url.indexOf("://")
        if (scheme < 0) null else {
            val rest = url.substring(scheme + 3)
            val end = rest.indexOf('/').let { if (it < 0) rest.length else it }
            url.substring(0, scheme + 3 + end) + "/"
        }
    }
    return LoadedStreams(streams, extras, definedStreams.keys, prefix, live.keys.toList())
}

/** Restarts Frigate and waits until it answers again. */
internal suspend fun restartAndWait(conn: ServerConnection, client: FrigateClient) {
    client.restart()
    delay(5000)
    repeat(40) {
        if (runCatching { client.version(3) }.isSuccess) return@repeat
        delay(3000)
    }
    conn.refresh(reloadConfig = true)
}

private enum class Phase { Editing, Saving, Restarting }

@Composable
fun CameraStreamsScreen(route: CameraStreamsRoute) {
    val camera = route.camera
    if (camera == null) { ServerStreamsScreen(route.serverId); return }
    val conn = AppModel.connection(route.serverId)
    val scope = rememberCoroutineScope()
    val saved = remember { mutableStateListOf<CameraStream>() }
    val streams = remember { mutableStateListOf<CameraStream>() }
    var extras by remember { mutableStateOf<Map<Int, Map<String, JsonElement>>>(emptyMap()) }
    var defined by remember { mutableStateOf<Set<String>>(emptySet()) }
    var prefix by remember { mutableStateOf<String?>(null) }
    val probes = remember { mutableStateMapOf<String, Result<StreamProbe>>() }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var phase by remember { mutableStateOf(Phase.Editing) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var savedLiveOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var liveDefault by remember { mutableStateOf<String?>(null) }
    var savedLiveDefault by remember { mutableStateOf<String?>(null) }
    val busy = phase != Phase.Editing

    suspend fun load() {
        val client = conn?.client ?: return
        loading = true
        try {
            val loaded = coroutineScope {
                val raw = async { client.rawPaths() }
                val cfg = async { client.rawConfig() }
                val yaml = async { runCatching { client.rawConfigText() }.getOrDefault("") }
                parseStreams(raw.await(), cfg.await(), yaml.await(), camera)
            }
            saved.clear(); saved.addAll(loaded.streams)
            streams.clear(); streams.addAll(loaded.streams)
            savedLiveOrder = loaded.liveOrder
            savedLiveDefault = savedLiveOrder.firstOrNull()?.let { first -> loaded.streams.firstOrNull { first in it.liveLabels }?.id }
            liveDefault = savedLiveDefault
            extras = loaded.extras
            defined = loaded.defined
            prefix = loaded.prefix
            loadError = null
            probes.clear()
            for (s in loaded.streams) {
                val url = s.name?.let { "rtsp://127.0.0.1:8554/$it" } ?: s.sources.firstOrNull() ?: ""
                if (!StreamAddress.canProbe(url)) continue
                scope.launch { probes[s.id] = runCatching { client.probeStream(url) } }
            }
        } catch (e: FrigateException.Http) {
            loadError = if (e.code == 404) L(R.string.setup_streams_needs_017) else e.message
        } catch (e: Exception) {
            loadError = e.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(route) { if (saved.isEmpty()) load() }

    fun title(s: CameraStream, number: Int) = s.name ?: L(R.string.setup_stream_n, number)

    fun inputs(list: List<CameraStream>): List<Map<String, Any?>> = list.filter { it.isInput }.map { s ->
        val input = LinkedHashMap<String, Any?>()
        val base: Map<String, Any?> = s.inputIndex?.let { extras[it] }
            ?: if (s.name != null) mapOf("input_args" to "preset-rtsp-restream") else emptyMap()
        input.putAll(base)
        input["path"] = s.name?.let { "rtsp://127.0.0.1:8554/$it${s.restreamSuffix}" } ?: s.sources.firstOrNull() ?: ""
        input["roles"] = s.roles
        input
    }
    fun inputKey(list: List<CameraStream>) = inputs(list).map { "${it["path"]} ${(it["roles"] as? List<*>)?.joinToString(",")}" }

    /** `live.streams` as it should be: label and go2rtc stream, the default first, otherwise in the loaded order. */
    fun liveEntries(list: List<CameraStream>, def: String?): List<Pair<String, String>> {
        val entries = mutableListOf<Triple<Pair<String, String>, Int, Int>>()
        for ((i, s) in list.withIndex()) {
            val name = s.name ?: continue
            for (label in s.liveLabels) {
                val rank = if (s.id == def) -1 else savedLiveOrder.indexOf(label).let { if (it < 0) 1000 + i else it }
                entries.add(Triple(label to name, rank, entries.size))
            }
        }
        return entries.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
    }

    fun firstInLive(): String? = liveEntries(streams, null).firstOrNull()?.let { (_, name) -> streams.firstOrNull { it.name == name }?.id }

    val problem: String? = run {
        val readers = streams.filter { it.isInput }
        val labels = streams.flatMap { it.liveLabels }
        when {
            readers.isEmpty() -> L(R.string.setup_streams_need_role)
            readers.count { "detect" in it.roles } != 1 -> L(R.string.setup_one_needs_detect)
            streams.any { it.name == null && it.roles.isEmpty() } -> L(R.string.setup_no_go2rtc_needs_role)
            labels.any { it.isBlank() } || labels.toSet().size != labels.size -> L(R.string.setup_live_names_unique)
            inputKey(streams) != inputKey(saved) && streams.any { it.name == null && it.locked } -> L(R.string.setup_stream_password_variable)
            else -> null
        }
    }

    val changeLines: List<String> = run {
        val out = mutableListOf<String>()
        for ((i, old) in saved.withIndex()) {
            val s = streams.firstOrNull { it.id == old.id }
            if (s == null) { out.add(L(R.string.setup_change_removed, title(old, i + 1))); continue }
            if (old.name != null && s.name != null && old.name != s.name) out.add(L(R.string.setup_change_renamed, old.name, s.name))
            if (old.sources != s.sources) out.add(L(R.string.setup_change_address, title(s, i + 1)))
            if (old.roles != s.roles) out.add(L(R.string.setup_change_roles, title(s, i + 1)))
        }
        for ((i, s) in streams.withIndex()) if (saved.none { it.id == s.id }) out.add(L(R.string.setup_change_added, title(s, i + 1)))
        for ((i, s) in streams.withIndex()) {
            val old = saved.firstOrNull { it.id == s.id }
            if (old != null && old.liveLabels != s.liveLabels) out.add(L(R.string.setup_change_live, title(s, i + 1)))
        }
        if (liveDefault != savedLiveDefault) {
            val i = streams.indexOfFirst { it.id == liveDefault }
            if (i >= 0) out.add(L(R.string.setup_change_default, title(streams[i], i + 1)))
        }
        out
    }

    /** The change for /api/config/set: go2rtc streams, the camera's inputs (as a whole), its live view entries. */
    fun configChange(): Map<String, Any?> {
        val go2rtc = LinkedHashMap<String, Any?>()
        val live = LinkedHashMap<String, Any?>()
        for (old in saved) {
            val name = old.name ?: continue
            val now = streams.firstOrNull { it.id == old.id }
            val newName = now?.name
            if (now != null && newName != null) {
                if (newName != name) {
                    if (old.sharedWith.isEmpty()) go2rtc[name] = ""
                    go2rtc[newName] = now.sources
                    for (label in old.liveLabels) live[label] = newName
                } else if (now.sources != old.sources && !old.locked) {
                    go2rtc[name] = now.sources
                }
            } else if (now == null) {
                // Removed: its go2rtc stream goes too, unless another camera reads it.
                if (old.sharedWith.isEmpty()) go2rtc[name] = ""
                for (label in old.liveLabels) live[label] = ""
            }
        }
        for (s in streams) {
            if (saved.any { it.id == s.id }) continue
            val name = s.name ?: continue
            go2rtc[name] = s.sources
        }
        val data = LinkedHashMap<String, Any?>()
        if (go2rtc.isNotEmpty()) data["go2rtc"] = mapOf("streams" to go2rtc)
        val cam = LinkedHashMap<String, Any?>()
        if (inputKey(streams) != inputKey(saved)) cam["ffmpeg"] = mapOf("inputs" to inputs(streams))
        if (live.isNotEmpty()) cam["live"] = mapOf("streams" to live)
        if (cam.isNotEmpty()) data["cameras"] = mapOf(camera to cam)
        return data
    }

    /** `live.streams` after the first save, which follows renames and removals in place. */
    fun liveAfterFirstSave(): List<Pair<String, String>> = savedLiveOrder.mapNotNull { label ->
        val old = saved.firstOrNull { label in it.liveLabels } ?: return@mapNotNull null
        val name = streams.firstOrNull { it.id == old.id }?.name ?: return@mapNotNull null
        label to name
    }

    fun save() {
        val c = conn ?: return
        val client = c.client ?: return
        errorMessage = null
        notice = null
        phase = Phase.Saving
        scope.launch {
            try {
                val change = configChange()
                if (change.isNotEmpty()) client.setConfig(change, requiresRestart = true, updateTopic = null)
                // live.streams is a map whose order counts, and /api/config/set keeps the old order:
                // clear it and add the entries one by one.
                val wanted = liveEntries(streams, liveDefault)
                val current = if (change.isEmpty()) liveEntries(saved, savedLiveDefault) else liveAfterFirstSave()
                if (wanted != current) {
                    if (current.isNotEmpty()) {
                        client.setConfig(mapOf("cameras" to mapOf(camera to mapOf("live" to mapOf("streams" to "")))), true, null)
                    }
                    for ((label, name) in wanted) {
                        client.setConfig(mapOf("cameras" to mapOf(camera to mapOf("live" to mapOf("streams" to mapOf(label to name))))), true, null)
                    }
                }
                phase = Phase.Restarting
                restartAndWait(c, client)
                load()
                notice = L(R.string.setup_saved_restarted)
            } catch (e: Exception) {
                errorMessage = e.message
            }
            phase = Phase.Editing
        }
    }

    fun apply(updated: CameraStream, isDefault: Boolean) {
        // A role sits on one stream only.
        for (i in streams.indices) {
            if (streams[i].id != updated.id) streams[i] = streams[i].copy(roles = streams[i].roles.filter { it !in updated.roles })
        }
        val i = streams.indexOfFirst { it.id == updated.id }
        if (i >= 0) streams[i] = updated else streams.add(updated)
        when {
            isDefault -> liveDefault = updated.id
            liveDefault == updated.id && updated.liveLabels.isEmpty() -> { liveDefault = null; liveDefault = firstInLive() }
            liveDefault == null -> liveDefault = firstInLive()
        }
    }

    ScreenScaffold(title = stringResource(R.string.setup_streams), subtitle = conn?.camera(camera)?.displayName ?: Format.prettyName(camera)) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.readableWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    loading && saved.isEmpty() -> LoadingBox()
                    loadError != null -> ErrorBanner(loadError ?: "")
                    else -> {
                        notice?.let {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = Palette.status.online)
                                Spacer(Modifier.width(6.dp))
                                Text(it, color = Palette.status.online, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        FormCard {
                            for ((i, s) in streams.withIndex()) {
                                if (i > 0) RowDivider()
                                StreamRow(s, title(s, i + 1), saved.firstOrNull { it.id == s.id }, probes[s.id], liveDefault, enabled = !busy) {
                                    editing = s.id to false
                                }
                            }
                            if (streams.isNotEmpty()) RowDivider()
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !busy) { editing = UUID.randomUUID().toString() to true }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.setup_add_stream), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Note(stringResource(R.string.setup_streams_note))
                        if (streams.toList() != saved.toList() || liveDefault != savedLiveDefault || busy) {
                            ChangesCard(
                                busy = busy, phase = phase, lines = changeLines, problem = problem, errorMessage = errorMessage,
                                onUndo = { streams.clear(); streams.addAll(saved); liveDefault = savedLiveDefault; errorMessage = null },
                                onSave = { save() },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { (id, _) ->
        val existing = streams.firstOrNull { it.id == id }
        val template = existing ?: CameraStream(id = id, name = "", sources = listOf(prefix ?: "rtsp://"))
        // Names other streams already have: on the server, and new or renamed ones here.
        val taken = (defined + streams.mapNotNull { it.name }) - setOfNotNull(saved.firstOrNull { it.id == id }?.name) - setOfNotNull(existing?.name)
        val labels = streams.filter { it.id != id }.flatMap { it.liveLabels }.toSet()
        StreamEditSheet(
            stream = template, isNew = existing == null, takenNames = taken, takenLabels = labels,
            isDefault = liveDefault == id, client = conn?.client,
            onDone = { updated, isDefault -> apply(updated, isDefault); editing = null },
            onDelete = {
                streams.removeAll { it.id == id }
                if (liveDefault == id) liveDefault = firstInLive()
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun StreamRow(s: CameraStream, title: String, original: CameraStream?, probe: Result<StreamProbe>?, liveDefault: String?,
                      enabled: Boolean, onClick: () -> Unit) {
    val changed = original != s
    val scheme = MaterialTheme.colorScheme
    val chips = buildList {
        addAll(s.roles.map { roleTitle(it) to true })
        if (s.name != null) add("go2rtc" to false)
        addAll(s.liveLabels.map { (if (s.id == liveDefault) L(R.string.setup_live_label_default, it) else L(R.string.setup_live_label, it)) to false })
        if (!s.isInput && s.liveLabels.isEmpty()) add(L(R.string.setup_not_used) to false)
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                if (original == null) Text(" " + stringResource(R.string.setup_tag_new), fontSize = 12.sp, color = Palette.status.warning)
                else if (changed) Text(" " + stringResource(R.string.setup_tag_changed), fontSize = 12.sp, color = Palette.status.warning)
            }
            Text(StreamAddress.masked(s.sources.firstOrNull() ?: ""), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (!changed) probe?.let { r ->
                r.onSuccess { Text(it.summary, fontSize = 12.sp, color = Palette.status.online) }
                r.onFailure { Text(it.message ?: "", fontSize = 12.sp, color = scheme.error, maxLines = 2) }
            }
            FlowChips(chips, Modifier.padding(top = 3.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun ChangesCard(busy: Boolean, phase: Phase, lines: List<String>, problem: String?, errorMessage: String?,
                        onUndo: () -> Unit, onSave: () -> Unit) {
    FormCard(Modifier.padding(top = 10.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (busy) R.string.setup_saving else R.string.setup_not_saved_yet), style = MaterialTheme.typography.titleSmall)
            for (line in lines) Text("• $line", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp), tint = Palette.status.warning)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.setup_go2rtc_restart_note), fontSize = 12.sp, color = Palette.status.warning)
            }
            problem?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            errorMessage?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            ButtonPair(
                secondary = stringResource(R.string.setup_undo), onSecondary = onUndo, secondaryEnabled = !busy,
                primary = stringResource(when (phase) {
                    Phase.Restarting -> R.string.setup_restarting
                    Phase.Saving -> R.string.setup_saving
                    Phase.Editing -> R.string.setup_save_and_restart
                }),
                onPrimary = onSave, primaryBusy = busy, primaryEnabled = problem == null,
            )
        }
    }
}

/** Editing one stream, or adding one: its name, address (password hidden), a test, its roles. */
@Composable
private fun StreamEditSheet(
    stream: CameraStream,
    isNew: Boolean,
    takenNames: Set<String>,
    takenLabels: Set<String>,
    isDefault: Boolean,
    client: FrigateClient?,
    onDone: (CameraStream, Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(stream.name ?: "") }
    // Passwords stay hidden while editing; the original comes back where the mask is left as it is.
    val texts = remember { mutableStateListOf(*stream.sources.map { StreamAddress.masked(it) }.toTypedArray()) }
    var roles by remember { mutableStateOf(stream.roles.toSet()) }
    var viaGo2rtc by remember { mutableStateOf(stream.name != null) }
    var testing by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<Result<StreamProbe>?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var showInLive by remember { mutableStateOf(stream.liveLabels.isNotEmpty()) }
    var liveLabel by remember { mutableStateOf(stream.liveLabels.firstOrNull() ?: "") }
    var makeDefault by remember { mutableStateOf(isDefault) }

    val trimmedName = name.trim()
    val hasName = if (isNew) viaGo2rtc else stream.name != null
    val canRename = isNew || (stream.sharedWith.isEmpty() && !stream.locked)
    val addresses = texts.mapIndexed { i, text ->
        val t = text.trim()
        if (i < stream.sources.size) StreamAddress.unmasked(t, stream.sources[i]) else t
    }.filter { it.isNotEmpty() }
    val nameProblem: String? = when {
        !hasName -> null
        trimmedName.isEmpty() -> L(R.string.setup_name_missing)
        !Regex("[A-Za-z0-9_\\-]+").matches(trimmedName) -> L(R.string.setup_name_characters)
        trimmedName in takenNames -> L(R.string.setup_name_taken)
        else -> null
    }
    val trimmedLabel = liveLabel.trim()
    val labelProblem: String? = when {
        !hasName || !showInLive -> null
        trimmedLabel.isEmpty() -> L(R.string.setup_live_name_missing)
        trimmedLabel in takenLabels -> L(R.string.setup_live_name_taken)
        else -> null
    }
    val canFinish = nameProblem == null && labelProblem == null && addresses.isNotEmpty() && (hasName || roles.isNotEmpty()) &&
        addresses.all { StreamAddress.host(it) != null || it.startsWith("ffmpeg:") || stream.locked }
    val canTest = addresses.firstOrNull()?.let { StreamAddress.canProbe(it) } == true

    fun finish() {
        val s = stream.copy(
            name = if (hasName) trimmedName else null,
            sources = if (stream.locked) stream.sources else addresses,
            roles = roles.sortedBy { roleOrder(it) },
            // Other live view names of the stream (rare) stay as they are.
            liveLabels = if (hasName && showInLive) listOf(trimmedLabel) + stream.liveLabels.drop(1) else emptyList(),
        )
        onDone(s, hasName && showInLive && makeDefault)
    }

    FormSheet(
        title = stringResource(if (isNew) R.string.setup_add_stream else R.string.setup_edit_stream),
        onDismiss = onDismiss,
        confirmText = stringResource(if (isNew) R.string.add else R.string.done),
        confirmEnabled = canFinish,
        onConfirm = { finish() },
    ) {
        FormCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasName) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.setup_name)) },
                        placeholder = { Text("driveway_sub") }, singleLine = true, enabled = canRename, modifier = Modifier.fillMaxWidth(),
                        isError = nameProblem != null && (trimmedName.isNotEmpty() || !isNew),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii))
                }
                for (i in texts.indices) {
                    val label = if (texts.size > 1) stringResource(R.string.setup_address_n, i + 1) else stringResource(R.string.setup_address)
                    if (stream.locked) {
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(texts[i], fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        OutlinedTextField(texts[i], { texts[i] = it; test = null }, label = { Text(label) }, placeholder = { Text("rtsp://…") },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp),
                            visualTransformation = UrlPasswordTransformation,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
                    }
                }
            }
            if (isNew) {
                RowDivider()
                SwitchRow(stringResource(R.string.setup_through_go2rtc), viaGo2rtc, { viaGo2rtc = it })
            }
        }
        when {
            nameProblem != null && (trimmedName.isNotEmpty() || !isNew) -> Note(nameProblem, color = MaterialTheme.colorScheme.error)
            stream.sharedWith.isNotEmpty() -> Note(stringResource(R.string.setup_stream_shared, joinNames(stream.sharedWith)))
            stream.locked -> Note(stringResource(R.string.setup_stream_locked))
            !isNew && stream.name != null && stream.liveLabels.isNotEmpty() ->
                Note(stringResource(R.string.setup_rename_note, stream.name, joinNames(stream.liveLabels.map { "“$it”" })))
            isNew && stream.sources.firstOrNull()?.let { it != "rtsp://" } == true -> Note(stringResource(R.string.setup_prefix_note))
        }

        TestCard(testing, test, enabled = canTest && !testing) {
            val c = client ?: return@TestCard
            val url = addresses.firstOrNull() ?: return@TestCard
            testing = true
            scope.launch {
                test = runCatching { c.probeStream(url) }
                testing = false
            }
        }

        SectionLabel(stringResource(R.string.setup_roles), Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (role in listOf("detect", "record", "audio")) {
                val on = role in roles
                ToggleChip(roleTitle(role), on, { roles = if (on) roles - role else roles + role })
            }
        }
        Note(stringResource(if (hasName) R.string.setup_roles_note_go2rtc else R.string.setup_roles_note_direct))

        if (hasName) {
            SectionLabel(stringResource(R.string.setup_live_view), Modifier.padding(top = 10.dp))
            FormCard {
                SwitchRow(stringResource(R.string.setup_show_in_live), showInLive, { showInLive = it })
                if (showInLive) {
                    RowDivider()
                    OutlinedTextField(liveLabel, { liveLabel = it }, label = { Text(stringResource(R.string.setup_name_in_choice)) },
                        placeholder = { Text(trimmedName.ifEmpty { "Main" }) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp))
                    RowDivider()
                    // Only switched on here: another stream becomes the default by switching it on there.
                    SwitchRow(stringResource(R.string.setup_default), makeDefault, { makeDefault = it }, enabled = !isDefault)
                }
            }
            if (labelProblem != null && !(isNew && liveLabel.isEmpty())) Note(labelProblem, color = MaterialTheme.colorScheme.error)
            else Note(stringResource(if (isDefault) R.string.setup_live_note_default else R.string.setup_live_note))
        }

        if (!isNew) DestructiveWideButton(stringResource(R.string.setup_remove_stream), onClick = { confirmingDelete = true })
    }

    if (confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.setup_remove_stream_title),
            message = stringResource(if (stream.sharedWith.isEmpty()) R.string.setup_remove_stream_message else R.string.setup_remove_stream_message_shared),
            confirmText = stringResource(R.string.setup_remove_stream),
            onConfirm = onDelete,
            onDismiss = { confirmingDelete = false },
        )
    }
}

/** Tests a stream on the server with ffprobe: what it found, or why it failed. */
@Composable
internal fun TestCard(testing: Boolean, result: Result<StreamProbe>?, enabled: Boolean, onTest: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FormCard(Modifier.padding(top = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onTest).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                when {
                    testing -> SmallSpinner()
                    result?.isSuccess == true -> Icon(Icons.Filled.CheckCircle, null, tint = Palette.status.online)
                    result?.isFailure == true -> Icon(Icons.Filled.Cancel, null, tint = scheme.error)
                    else -> Icon(Icons.Filled.PlayCircleOutline, null, tint = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(R.string.setup_test_stream), style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled || testing || result != null) scheme.onSurface else scheme.onSurface.copy(alpha = 0.5f))
                result?.onSuccess { Text(it.summary, fontSize = 12.sp, color = Palette.status.online) }
                result?.onFailure { Text(it.message ?: "", fontSize = 12.sp, color = scheme.error) }
            }
        }
    }
}

/** Without a camera: the server's cameras to choose from, and its go2rtc streams to read. */
@Composable
private fun ServerStreamsScreen(serverId: String) {
    val conn = AppModel.connection(serverId)
    val nav = LocalNav.current
    var go2rtc by remember { mutableStateOf<List<Pair<String, List<String>>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serverId) {
        val client = conn?.client ?: return@LaunchedEffect
        try {
            go2rtc = client.rawPaths()["go2rtc"]["streams"].obj()?.map { (k, v) ->
                k to (v.str()?.let { listOf(it) } ?: v.arr()?.mapNotNull { it.str() } ?: emptyList())
            } ?: emptyList()
        } catch (e: FrigateException.Http) {
            error = if (e.code == 404) L(R.string.setup_streams_needs_017) else e.message
        } catch (e: Exception) {
            error = e.message
        }
    }
    ScreenScaffold(title = stringResource(R.string.setup_streams), subtitle = conn?.server?.name) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.readableWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                error?.let { ErrorBanner(it) }
                val cams = conn?.config?.cameras?.values?.sortedWith(compareBy({ it.uiOrder ?: 0 }, { it.name })) ?: emptyList()
                if (cams.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.setup_cameras))
                    FormCard {
                        for ((i, cam) in cams.withIndex()) {
                            if (i > 0) RowDivider()
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.navigate(CameraStreamsRoute(serverId, cam.name)) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Videocam, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(cam.displayName, Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val list = go2rtc
                if (list == null && error == null) LoadingBox()
                if (!list.isNullOrEmpty()) {
                    SectionLabel("go2rtc", Modifier.padding(top = 10.dp))
                    FormCard {
                        for ((i, entry) in list.withIndex()) {
                            if (i > 0) RowDivider()
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(entry.first, fontWeight = FontWeight.SemiBold)
                                for (src in entry.second) {
                                    Text(StreamAddress.masked(src), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                }
                            }
                        }
                    }
                    Note(stringResource(R.string.setup_go2rtc_server_note))
                }
            }
        }
    }
}
