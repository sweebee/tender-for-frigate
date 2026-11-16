package nl.wiebenieuwenhuis.tender.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.StreamAddress
import nl.wiebenieuwenhuis.tender.api.StreamProbe
import nl.wiebenieuwenhuis.tender.api.arr
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.probeStream
import nl.wiebenieuwenhuis.tender.api.rawPaths
import nl.wiebenieuwenhuis.tender.api.str
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.components.PasswordField
import nl.wiebenieuwenhuis.tender.ui.settings.RowDivider
import nl.wiebenieuwenhuis.tender.ui.settings.SettingsRow
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

// Changing a camera's IP address (or its login) in one place. Frigate keeps the address in the
// camera's go2rtc streams and, for cameras without go2rtc, in its ffmpeg inputs; its own UI makes
// you edit each of them. Here the app finds every one on the camera's host and changes them together.

/** Where a camera's address is written, read from the config with the real (unmasked) addresses. */
class CameraAddressPlan internal constructor(
    val camera: String,
    /** The camera's host: the one most of its addresses use. */
    val host: String,
    val user: String?,
    /** The addresses carry a login (so a new username or password can go in). */
    val hasLogin: Boolean,
    /** Every address on `host`, titled by its go2rtc stream or "Stream n". */
    internal val targets: List<Pair<String, String>>,
    /** Other cameras that read the same go2rtc streams. */
    val sharedWith: List<String>,
    /** An input on this host gets its password from a {FRIGATE_*} variable; Frigate only shows it filled in. */
    val usesVariable: Boolean,
    private val streams: Map<String, List<String>>,
    private val inputs: List<Map<String, Any?>>,
) {
    private fun change(url: String, host: String, user: String?, password: String?): String {
        if (StreamAddress.host(url) != this.host) return url
        return StreamAddress.replacingLogin(StreamAddress.replacingHost(url, host), user, password)
    }

    /** The addresses as they become, in the order of `targets`. */
    internal fun newAddresses(host: String, user: String?, password: String?): List<Pair<String, String>> =
        targets.map { it.first to change(it.second, host, user, password) }

    /**
     * The config change for /api/config/set: the go2rtc streams that change, and the camera's
     * inputs (Frigate takes the list as a whole) when one of them has the address itself.
     */
    internal fun configChange(host: String, user: String?, password: String?): Map<String, Any?> {
        val data = LinkedHashMap<String, Any?>()
        val changed = LinkedHashMap<String, Any?>()
        for ((name, sources) in streams) {
            val new = sources.map { change(it, host, user, password) }
            if (new != sources) changed[name] = new
        }
        if (changed.isNotEmpty()) data["go2rtc"] = mapOf("streams" to changed)
        var inputsChanged = false
        val newInputs = inputs.map { input ->
            val path = input["path"] as? String
            if (path != null && StreamAddress.restreamName(path) == null) {
                val new = change(path, host, user, password)
                if (new != path) { inputsChanged = true; return@map input + ("path" to new) }
            }
            input
        }
        if (inputsChanged) data["cameras"] = mapOf(camera to mapOf("ffmpeg" to mapOf("inputs" to newInputs)))
        return data
    }

    companion object {
        /** null when the camera has no address of its own (or Frigate is older than 0.17). */
        suspend fun load(client: FrigateClient, camera: String): CameraAddressPlan? = coroutineScope {
            val rawReq = async { client.rawPaths() }
            val cfgReq = async { client.rawConfig() }
            val yamlReq = async { runCatching { client.rawConfigText() }.getOrDefault("") }
            val raw = rawReq.await()
            val config = cfgReq.await()
            val yaml = yamlReq.await()

            val cameras = config["cameras"].obj() ?: return@coroutineScope null
            val cam = cameras[camera]
            val rawInputs = raw["cameras"][camera]["ffmpeg"]["inputs"].arr() ?: emptyList()
            val shownInputs = cam["ffmpeg"]["inputs"].arr() ?: emptyList()

            // The inputs as written back: the real address and roles, plus the input's own ffmpeg arguments.
            val inputs = rawInputs.mapIndexed { i, r ->
                val input = LinkedHashMap<String, Any?>()
                if (i < shownInputs.size) input.putAll(inputExtras(shownInputs[i]))
                input["path"] = r["path"].str()
                input["roles"] = r["roles"]
                input
            }

            fun streamNames(cam: JsonElement?, inputs: List<JsonElement?>): List<String> {
                val names = mutableListOf<String>()
                for (input in inputs) {
                    val name = input["path"].str()?.let { StreamAddress.restreamName(it) } ?: continue
                    if (name !in names) names.add(name)
                }
                for (name in cam["live"]["streams"].obj()?.values?.mapNotNull { it.str() }?.sorted() ?: emptyList()) {
                    if (name !in names) names.add(name)
                }
                return names
            }
            val names = streamNames(cam, rawInputs)
            val defined = raw["go2rtc"]["streams"].obj()
            val streams = LinkedHashMap<String, List<String>>()
            for (name in names) {
                // Only plain addresses; a stream with a map source is left alone.
                val v = defined?.get(name) ?: continue
                v.str()?.let { streams[name] = listOf(it) } ?: v.arr()?.let { a ->
                    val list = a.mapNotNull { it.str() }
                    if (list.size == a.size) streams[name] = list
                }
            }

            val all = mutableListOf<Pair<String, String>>()
            for (name in names) for (s in streams[name] ?: emptyList()) {
                if (StreamAddress.host(s) != null && !StreamAddress.isLocal(s)) all.add(name to s)
            }
            for ((i, input) in rawInputs.withIndex()) {
                val path = input["path"].str() ?: continue
                if (StreamAddress.restreamName(path) != null || StreamAddress.host(path) == null || StreamAddress.isLocal(path)) continue
                all.add((if (rawInputs.size > 1) L(R.string.setup_stream_n, i + 1) else L(R.string.setup_camera)) to path)
            }
            val counts = all.groupingBy { StreamAddress.host(it.second) ?: "" }.eachCount()
            val host = counts.entries.sortedWith(compareBy<Map.Entry<String, Int>>({ -it.value }, { it.key })).firstOrNull()?.key
                ?: return@coroutineScope null
            val targets = all.filter { StreamAddress.host(it.second) == host }

            val mine = streams.keys
            val shared = mutableListOf<String>()
            for ((name, other) in cameras) {
                if (name == camera) continue
                if (streamNames(other, other["ffmpeg"]["inputs"].arr() ?: emptyList()).any { it in mine }) {
                    shared.add(other["friendly_name"].str() ?: Format.prettyName(name))
                }
            }

            // Frigate fills {FRIGATE_*} variables in camera inputs (not in go2rtc streams); such an
            // address is not in the file as Frigate shows it, and writing it back would put the password in.
            val usesVariable = targets.any { (title, url) -> title !in names && StreamAddress.password(url) != null && !yaml.contains(url) }

            CameraAddressPlan(
                camera = camera, host = host,
                user = targets.firstNotNullOfOrNull { StreamAddress.user(it.second) },
                hasLogin = targets.any { StreamAddress.user(it.second) != null || StreamAddress.password(it.second) != null },
                targets = targets, sharedWith = shared.sorted(), usesVariable = usesVariable,
                streams = streams, inputs = inputs,
            )
        }
    }
}

/**
 * The camera's address as a settings row (admins, Frigate 0.17+), which opens [CameraAddressSheet].
 * Shows nothing while loading or when the camera has no address of its own. For the camera's
 * Configuration card: draws the divider after itself when it shows (the next row follows it).
 */
@Composable
fun CameraAddressRow(serverId: String, camera: String, modifier: Modifier = Modifier, onShown: (Boolean) -> Unit = {}) {
    val conn = AppModel.connection(serverId)
    var plan by remember { mutableStateOf<CameraAddressPlan?>(null) }
    var editing by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(serverId, camera, reload, conn?.client) {
        val client = conn?.client ?: return@LaunchedEffect
        if (conn.isAdmin && Format.versionAtLeast(conn.version, 0, 17)) {
            plan = runCatching { CameraAddressPlan.load(client, camera) }.getOrNull()
        }
        onShown(plan != null)
    }
    val p = plan ?: return
    Column(modifier.fillMaxWidth()) {
        SettingsRow(Icons.Outlined.Lan, stringResource(R.string.setup_camera_address), p.host) { editing = true }
        RowDivider()
    }
    if (editing) CameraAddressSheet(serverId, p, onDismiss = { editing = false }, onSaved = { reload++ })
}

private enum class AddressPhase { Editing, Testing, Saving, Restarting }

/**
 * A new address and login for the camera: tested with ffprobe on the server, then saved and
 * Frigate restarted (go2rtc reads its streams only when it starts).
 */
@Composable
fun CameraAddressSheet(serverId: String, plan: CameraAddressPlan, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val conn = AppModel.connection(serverId)
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(plan.host) }
    var user by remember { mutableStateOf(plan.user ?: "") }
    var password by remember { mutableStateOf("") }
    val results = remember { mutableStateMapOf<Int, Result<StreamProbe>>() }
    val testing = remember { mutableStateMapOf<Int, Boolean>() }
    var phase by remember { mutableStateOf(AddressPhase.Editing) }
    var failed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val newHost = host.trim()
    val newUser = user.trim().takeIf { it != (plan.user ?: "") && it.isNotEmpty() }
    val newPassword = password.ifEmpty { null }
    // A first login needs a username; a password alone has nowhere to go.
    val loginComplete = plan.hasLogin || newPassword == null || newUser != null
    val hasChange = newHost != plan.host || newUser != null || newPassword != null
    val busy = phase != AddressPhase.Editing
    val canSave = hasChange && newHost.isNotEmpty() && !plan.usesVariable && loginComplete

    fun edited() { failed = false; results.clear() }

    fun save(force: Boolean) {
        val c = conn ?: return
        val client = c.client ?: return
        errorMessage = null
        scope.launch {
            if (!force) {
                phase = AddressPhase.Testing
                results.clear()
                val addresses = plan.newAddresses(newHost, newUser, newPassword)
                val toTest = addresses.indices.filter { StreamAddress.canProbe(addresses[it].second) }
                toTest.forEach { testing[it] = true }
                coroutineScope {
                    toTest.map { i ->
                        async {
                            results[i] = runCatching { client.probeStream(addresses[i].second) }
                            testing.remove(i)
                        }
                    }.awaitAll()
                }
                if (results.values.any { it.isFailure }) {
                    failed = true
                    phase = AddressPhase.Editing
                    return@launch
                }
            }
            phase = AddressPhase.Saving
            try {
                client.setConfig(plan.configChange(newHost, newUser, newPassword), requiresRestart = true, updateTopic = null)
                phase = AddressPhase.Restarting
                restartAndWait(c, client)
                onSaved()
                onDismiss()
            } catch (e: Exception) {
                errorMessage = e.message
                phase = AddressPhase.Editing
            }
        }
    }

    FormSheet(title = stringResource(R.string.setup_camera_address), onDismiss = onDismiss, dismissEnabled = !busy) {
        FormCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(host, { host = it; edited() }, label = { Text(stringResource(R.string.setup_ip_or_name)) },
                    placeholder = { Text("192.168.1.50") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
                OutlinedTextField(user, { user = it; edited() }, label = { Text(stringResource(R.string.setup_username)) },
                    placeholder = if (plan.hasLogin) null else ({ Text(stringResource(R.string.setup_none)) }),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii))
                PasswordField(password, { password = it; edited() }, stringResource(R.string.setup_password))
                if (password.isEmpty()) {
                    Text(stringResource(if (plan.hasLogin) R.string.setup_unchanged else R.string.setup_none),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Note(stringResource(R.string.setup_address_changes_in, joinNames(plan.targets.map { it.first }.distinct())) +
            if (plan.hasLogin) " " + stringResource(R.string.setup_leave_password_empty) else "")
        if (plan.sharedWith.isNotEmpty()) Note(stringResource(R.string.setup_address_shared, joinNames(plan.sharedWith)), color = Palette.status.warning)
        if (plan.usesVariable) Note(stringResource(R.string.setup_address_variable), color = Palette.status.warning)
        if (results.isNotEmpty() || testing.isNotEmpty()) {
            val addresses = plan.newAddresses(newHost, newUser, newPassword)
            FormCard(Modifier.padding(top = 6.dp)) {
                for ((i, target) in addresses.withIndex()) {
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            val r = results[i]
                            when {
                                testing[i] == true -> SmallSpinner()
                                r?.isSuccess == true -> Icon(Icons.Filled.CheckCircle, null, tint = Palette.status.online)
                                r?.isFailure == true -> Icon(Icons.Filled.Cancel, null, tint = MaterialTheme.colorScheme.error)
                                else -> Icon(Icons.Filled.RemoveCircleOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(target.first, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(StreamAddress.masked(target.second), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            val r = results[i]
                            r?.onSuccess { Text(it.summary, fontSize = 12.sp, color = Palette.status.online) }
                            r?.onFailure { Text(it.message ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                            if (r == null && testing[i] != true && !StreamAddress.canProbe(target.second)) {
                                Text(stringResource(R.string.setup_not_tested), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        errorMessage?.let { Note(it, color = MaterialTheme.colorScheme.error) }
        WideButton(
            text = stringResource(when (phase) {
                AddressPhase.Editing -> if (failed) R.string.setup_save_anyway else R.string.setup_test_and_save
                AddressPhase.Testing -> R.string.setup_testing
                AddressPhase.Saving -> R.string.setup_saving
                AddressPhase.Restarting -> R.string.setup_restarting
            }),
            onClick = { save(force = failed) }, busy = busy, enabled = canSave,
        )
        Note(stringResource(if (failed) R.string.setup_address_failed_note else R.string.setup_address_restart_note))
    }
}
