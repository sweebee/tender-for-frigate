package nl.wiebenieuwenhuis.tender.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraBrand
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.OnvifDevice
import nl.wiebenieuwenhuis.tender.api.StreamAddress
import nl.wiebenieuwenhuis.tender.api.StreamProbe
import nl.wiebenieuwenhuis.tender.api.addGo2rtcStream
import nl.wiebenieuwenhuis.tender.api.probeOnvif
import nl.wiebenieuwenhuis.tender.api.probeStream
import nl.wiebenieuwenhuis.tender.api.reolinkProtocol
import nl.wiebenieuwenhuis.tender.api.streamSnapshotUrl
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.CameraWizardRoute
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.PasswordField
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.util.UUID

// Adding a camera in Frigate's four steps: connection, what was found, streams and roles, check
// and add. The camera starts at once; Frigate writes it to the config and starts it without a restart.

private enum class WizardStep { Connect, Found, Roles, Review }

private val allRoles = listOf("detect", "record", "audio")

private data class WizardStream(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val probe: StreamProbe? = null,
    val error: String? = null,
    val testing: Boolean = false,
    val roles: Set<String> = emptySet(),
    val restream: Boolean = true,
    val use: Boolean = true,
)

private data class Issue(val error: Boolean, val text: String)

/** Frigate's key for a name: spaces to _, lower case; other characters give "cam_<hash>". */
internal fun cameraKey(title: String): String {
    val t = title.trim()
    val k = t.replace(Regex("\\s+"), "_").lowercase()
    if (k.isNotEmpty() && Regex("^[a-zA-Z0-9_-]+$").matches(k) && !k.all { it.isDigit() }) return k
    // FNV-1a 32-bit over the UTF-8 text, as Frigate's web wizard.
    var hash = 0x811C9DC5u
    for (b in t.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (b.toUInt() and 0xFFu)
        hash *= 0x01000193u
    }
    var hex = "%08x".format(hash.toLong())
    if (hex.first().isDigit()) hex = "a" + hex.drop(1)
    return "cam_$hex"
}

@Composable
fun CameraWizardScreen(route: CameraWizardRoute) {
    val conn = AppModel.connection(route.serverId)
    val client = conn?.client
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(WizardStep.Connect) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useOnvif by remember { mutableStateOf(true) }
    var onvifPort by remember { mutableStateOf("80") }
    var brand by remember { mutableStateOf(CameraBrand.Hikvision) }
    var customUrl by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf<OnvifDevice?>(null) }
    val streams = remember { mutableStateListOf<WizardStream>() }
    var extraUrl by remember { mutableStateOf("") }
    var appleCompatibility by remember { mutableStateOf(true) }
    var usePTZ by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val chosen = streams.filter { it.use && it.probe != null }
    val key = cameraKey(name)

    fun update(id: String, f: (WizardStream) -> WizardStream) {
        val i = streams.indexOfFirst { it.id == id }
        if (i >= 0) streams[i] = f(streams[i])
    }

    suspend fun test(id: String) {
        val c = client ?: return
        val url = streams.firstOrNull { it.id == id }?.url ?: return
        update(id) { it.copy(testing = true, error = null) }
        val r = runCatching { c.probeStream(url) }
        update(id) { s ->
            r.fold({ s.copy(probe = it, use = true, testing = false) }, { s.copy(probe = null, use = false, error = it.message, testing = false) })
        }
        // Two working streams at most ticked by default: the largest and the smallest.
        val working = streams.filter { it.probe != null }
        if (working.size > 2) {
            val sorted = working.sortedByDescending { it.probe!!.pixels }
            val keep = setOf(sorted.first().id, sorted.last().id)
            for (s in working) update(s.id) { it.copy(use = it.id in keep) }
        }
    }

    fun search() {
        val n = name.trim()
        if (n.isEmpty() || n.length > 64) { errorMessage = L(R.string.setup_wizard_name_missing); return }
        if (conn?.config?.cameras?.containsKey(cameraKey(n)) == true) { errorMessage = L(R.string.setup_wizard_name_taken); return }
        val h = host.trim()
        val c = client ?: return
        searching = true
        device = null
        scope.launch {
            try {
                val urls: List<String>
                if (useOnvif) {
                    if (h.isEmpty()) { errorMessage = L(R.string.setup_wizard_address_missing); return@launch }
                    val d = try {
                        c.probeOnvif(h, onvifPort.toIntOrNull() ?: 80, user, password)
                    } catch (e: Exception) {
                        errorMessage = L(R.string.setup_wizard_no_onvif, e.message ?: ""); return@launch
                    }
                    device = d
                    urls = d.streams
                    if (urls.isEmpty()) { errorMessage = L(R.string.setup_wizard_no_streams); return@launch }
                } else if (brand == CameraBrand.Other) {
                    val u = customUrl.trim()
                    if (!(u.startsWith("rtsp://") || u.startsWith("rtsps://") || u.startsWith("http"))) {
                        errorMessage = L(R.string.setup_wizard_rtsp_missing); return@launch
                    }
                    urls = listOf(u)
                } else {
                    if (h.isEmpty()) { errorMessage = L(R.string.setup_wizard_address_missing); return@launch }
                    var candidates = brand.urls(h, user, password)
                    if (brand == CameraBrand.Reolink) {
                        // As Frigate: HTTP-FLV up to 5 MP, RTSP above.
                        when (c.reolinkProtocol(h, user, password)) {
                            "rtsp" -> candidates = candidates.reversed()
                            null -> candidates = listOf(candidates[1])
                        }
                    }
                    var found: String? = null
                    for (cand in candidates) if (runCatching { c.probeStream(cand) }.isSuccess) { found = cand; break }
                    val main = found ?: candidates.firstOrNull() ?: ""
                    val list = mutableListOf(main)
                    brand.subUrl(main)?.let { if (it != main) list.add(it) }
                    urls = list
                }
                streams.clear()
                streams.addAll(urls.map { WizardStream(url = it) })
                step = WizardStep.Found
                coroutineScope { streams.map { it.id }.map { id -> async { test(id) } }.awaitAll() }
            } finally {
                searching = false
            }
        }
    }

    fun addExtra() {
        val u = extraUrl.trim()
        if (u.isEmpty()) return
        val s = WizardStream(url = u)
        streams.add(s)
        extraUrl = ""
        scope.launch { test(s.id) }
    }

    /** The largest stream records, the smallest detects; one stream does both. */
    fun assignRoles() {
        val ids = chosen.sortedByDescending { it.probe?.pixels ?: 0 }.map { it.id }
        for (i in streams.indices) streams[i] = streams[i].copy(roles = emptySet())
        val big = ids.firstOrNull() ?: return
        val small = ids.last()
        update(big) { it.copy(roles = it.roles + "record") }
        update(small) { it.copy(roles = it.roles + "detect") }
    }

    /** As Frigate does: the short side at most 720, even sizes, never larger than the stream. */
    val detectSize: Pair<Int, Int>? = chosen.firstOrNull { "detect" in it.roles }?.probe?.let { p ->
        val w = p.width ?: return@let null
        val h = p.height ?: return@let null
        if (w <= 0 || h <= 0) return@let null
        val short = minOf(w, h)
        val scale = minOf(720, short).toDouble() / short
        fun even(v: Double) = v.toInt() / 2 * 2
        even(w * scale) to even(h * scale)
    }

    /** As Frigate's wizard warns: detection too large or small, recording without (usable) audio. */
    val issues: List<Issue> = buildList {
        chosen.firstOrNull { "detect" in it.roles }?.probe?.let { d ->
            val w = d.width; val h = d.height
            if (w != null && h != null) {
                if (minOf(w, h) > 1080) add(Issue(false, L(R.string.setup_issue_detect_large)))
                if (maxOf(w, h) < 640) add(Issue(true, L(R.string.setup_issue_detect_small)))
            }
        }
        chosen.firstOrNull { "record" in it.roles }?.probe?.let { r ->
            if (r.audioCodec == null) add(Issue(false, L(R.string.setup_issue_no_sound)))
            else if (r.audioCodec != "aac") add(Issue(false, L(R.string.setup_issue_not_aac)))
        }
        chosen.firstOrNull { "audio" in it.roles }?.let { if (it.probe?.audioCodec == null) add(Issue(true, L(R.string.setup_issue_audio_none))) }
        if (conn?.config?.cameras?.containsKey(key) == true) add(Issue(true, L(R.string.setup_wizard_name_taken)))
    }

    fun save() {
        val c = conn ?: return
        val cl = client ?: return
        issues.firstOrNull { it.error }?.let { errorMessage = it.text; return }
        saving = true
        val cam = key
        val used = chosen.filter { it.roles.isNotEmpty() || it.restream }
        val single = used.size == 1
        val inputs = mutableListOf<Map<String, Any?>>()
        val go2rtc = LinkedHashMap<String, Any?>()
        val live = LinkedHashMap<String, String>()
        for ((i, s) in used.withIndex()) {
            val streamName = if (single) cam else "${cam}_${i + 1}"
            go2rtc[streamName] = listOf(if (s.url.startsWith("http")) "ffmpeg:${s.url}" else s.url)
            live[L(R.string.setup_stream_n, i + 1)] = streamName
            if (s.roles.isEmpty()) continue
            val roles = allRoles.filter { it in s.roles }
            inputs.add(if (s.restream) mapOf("path" to "rtsp://127.0.0.1:8554/$streamName", "input_args" to "preset-rtsp-restream", "roles" to roles)
                else mapOf("path" to s.url, "roles" to roles))
        }
        val ffmpeg = linkedMapOf<String, Any?>("inputs" to inputs)
        if (appleCompatibility && chosen.any { "record" in it.roles && it.probe?.isHEVC == true }) ffmpeg["apple_compatibility"] = true
        val camera = linkedMapOf<String, Any?>("enabled" to true, "ffmpeg" to ffmpeg, "live" to mapOf("streams" to live))
        val title = name.trim()
        if (title != cam) camera["friendly_name"] = title
        detectSize?.let { camera["detect"] = mapOf("width" to it.first, "height" to it.second) }
        if (usePTZ && device?.ptz == true) {
            val onvif = linkedMapOf<String, Any?>("host" to host.trim(), "port" to (onvifPort.toIntOrNull() ?: 80))
            if (user.isNotEmpty()) onvif["user"] = user
            if (password.isNotEmpty()) onvif["password"] = password
            camera["onvif"] = onvif
        }
        scope.launch {
            try {
                // As Frigate's wizard: the camera (started by the "add" topic), then its go2rtc streams.
                cl.setConfig(mapOf("cameras" to mapOf(cam to camera)), requiresRestart = true, updateTopic = "config/cameras/$cam/add")
                runCatching { cl.setConfig(mapOf("go2rtc" to mapOf("streams" to go2rtc)), requiresRestart = false, updateTopic = null) }
                for ((n, src) in go2rtc) {
                    val first = (src as? List<*>)?.firstOrNull() as? String ?: continue
                    runCatching { cl.addGo2rtcStream(n, first) }
                }
                c.refresh(reloadConfig = true)
                nav.popBackStack()
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                saving = false
            }
        }
    }

    val stepNumber = step.ordinal + 1
    BackHandler(enabled = step != WizardStep.Connect && !saving && !searching) {
        step = WizardStep.entries[step.ordinal - 1]
    }

    ScreenScaffold(title = stringResource(R.string.setup_add_camera), subtitle = stringResource(R.string.setup_step_of_4, stepNumber),
        onBack = { if (!saving) nav.popBackStack() }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.readableWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (step) {
                    WizardStep.Connect -> {
                        FormCard {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.setup_name)) },
                                    placeholder = { Text(stringResource(R.string.setup_wizard_name_example)) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.setup_address)) },
                                    placeholder = { Text("192.168.1.50") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
                                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.setup_username)) },
                                    placeholder = { Text("admin") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii))
                                PasswordField(password, { password = it }, stringResource(R.string.setup_password))
                            }
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            SegmentedButton(selected = useOnvif, onClick = { useOnvif = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                                Text(stringResource(R.string.setup_wizard_onvif), maxLines = 1, softWrap = false)
                            }
                            SegmentedButton(selected = !useOnvif, onClick = { useOnvif = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                                Text(stringResource(R.string.setup_wizard_brand_or_address), maxLines = 1, softWrap = false)
                            }
                        }
                        if (useOnvif) {
                            FormCard {
                                OutlinedTextField(onvifPort, { v -> onvifPort = v.filter { it.isDigit() }.take(5) },
                                    label = { Text(stringResource(R.string.setup_onvif_port)) }, placeholder = { Text("80") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            }
                            Note(stringResource(R.string.setup_onvif_note))
                        } else {
                            FormCard {
                                MenuRow(stringResource(R.string.setup_brand), brand.title, CameraBrand.entries.map { it.title }, brand.ordinal) {
                                    brand = CameraBrand.entries[it]
                                }
                                if (brand == CameraBrand.Other) {
                                    RowDivider()
                                    OutlinedTextField(customUrl, { customUrl = it }, placeholder = { Text("rtsp://user:password@192.168.1.50:554/stream") },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                                        visualTransformation = UrlPasswordTransformation,
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
                                }
                            }
                        }
                        WideButton(stringResource(R.string.setup_find_camera), onClick = { search() }, busy = searching)
                        if (searching) Note(stringResource(R.string.setup_searching_note))
                    }

                    WizardStep.Found -> {
                        device?.let { d ->
                            FormCard {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(listOf(d.manufacturer, d.model).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { stringResource(R.string.setup_camera) },
                                        fontWeight = FontWeight.SemiBold)
                                    Text(listOfNotNull(
                                        d.firmware.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.setup_firmware, it) },
                                        stringResource(R.string.setup_profiles, d.profiles),
                                        if (d.ptz) "PTZ" else null,
                                    ).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        streams.firstOrNull { it.probe != null }?.let { first -> if (client != null) StreamSnapshot(client.streamSnapshotUrl(first.url)) }
                        SectionLabel(stringResource(R.string.setup_streams), Modifier.padding(top = 6.dp))
                        FormCard {
                            for ((i, s) in streams.withIndex()) {
                                if (i > 0) RowDivider()
                                FoundStreamRow(s,
                                    onToggle = { update(s.id) { it.copy(use = !it.use) } },
                                    onTest = { scope.launch { test(s.id) } },
                                    onRemove = { streams.removeAll { it.id == s.id } })
                            }
                        }
                        Note(stringResource(R.string.setup_tick_streams_note))
                        FormCard {
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(extraUrl, { extraUrl = it }, placeholder = { Text(stringResource(R.string.setup_another_stream)) },
                                    singleLine = true, modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                                    visualTransformation = UrlPasswordTransformation,
                                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
                                IconButton(onClick = { addExtra() }, enabled = extraUrl.isNotBlank()) {
                                    Icon(Icons.Filled.AddCircle, stringResource(R.string.add), tint = if (extraUrl.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        ButtonPair(
                            secondary = stringResource(R.string.setup_back), onSecondary = { step = WizardStep.Connect },
                            primary = stringResource(R.string.setup_next),
                            onPrimary = { if (chosen.isEmpty()) errorMessage = L(R.string.setup_tick_one) else { assignRoles(); step = WizardStep.Roles } },
                        )
                    }

                    WizardStep.Roles -> {
                        for (s in chosen) {
                            FormCard {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(s.probe?.summary ?: "", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(StreamAddress.masked(s.url), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for (role in allRoles) {
                                            val on = role in s.roles
                                            ToggleChip(roleTitle(role), on, {
                                                if (on) update(s.id) { it.copy(roles = it.roles - role) }
                                                else {
                                                    // A role sits on one stream only.
                                                    for (j in streams.indices) streams[j] = streams[j].copy(roles = streams[j].roles - role)
                                                    update(s.id) { it.copy(roles = it.roles + role) }
                                                }
                                            })
                                        }
                                    }
                                }
                                SwitchRow(stringResource(R.string.setup_through_go2rtc), s.restream, { v -> update(s.id) { it.copy(restream = v) } })
                            }
                        }
                        Note(stringResource(R.string.setup_roles_wizard_note))
                        if (chosen.any { "record" in it.roles && it.probe?.isHEVC == true }) {
                            FormCard { SwitchRow(stringResource(R.string.setup_apple_compat), appleCompatibility, { appleCompatibility = it }) }
                            Note(stringResource(R.string.setup_apple_compat_note))
                        }
                        if (device?.ptz == true) {
                            FormCard { SwitchRow(stringResource(R.string.setup_ptz), usePTZ, { usePTZ = it }) }
                        }
                        ButtonPair(
                            secondary = stringResource(R.string.setup_back), onSecondary = { step = WizardStep.Found },
                            primary = stringResource(R.string.setup_next),
                            onPrimary = {
                                if (chosen.none { "detect" in it.roles }) errorMessage = L(R.string.setup_one_needs_detect) else step = WizardStep.Review
                            },
                        )
                    }

                    WizardStep.Review -> {
                        chosen.firstOrNull { "detect" in it.roles }?.let { d -> if (client != null) StreamSnapshot(client.streamSnapshotUrl(d.url)) }
                        FormCard {
                            val title = name.trim()
                            ValueRow(stringResource(R.string.setup_name), title + if (key != title.lowercase()) "  ($key)" else "")
                            detectSize?.let { RowDivider(); ValueRow(stringResource(R.string.setup_role_detect), "${it.first}×${it.second}") }
                            RowDivider()
                            ValueRow(stringResource(R.string.setup_role_record),
                                chosen.firstOrNull { "record" in it.roles }?.probe?.resolution ?: stringResource(R.string.setup_off))
                        }
                        if (issues.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.setup_check), Modifier.padding(top = 6.dp))
                            FormCard {
                                for ((i, issue) in issues.withIndex()) {
                                    if (i > 0) RowDivider()
                                    val color = if (issue.error) MaterialTheme.colorScheme.error else Palette.status.warning
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                                        Icon(if (issue.error) Icons.Filled.Error else Icons.Filled.Warning, null, Modifier.size(18.dp), tint = color)
                                        Spacer(Modifier.width(8.dp))
                                        Text(issue.text, fontSize = 13.sp, color = color)
                                    }
                                }
                            }
                        }
                        Note(stringResource(R.string.setup_wizard_add_note))
                        ButtonPair(
                            secondary = stringResource(R.string.setup_back), onSecondary = { step = WizardStep.Roles }, secondaryEnabled = !saving,
                            primary = stringResource(R.string.setup_add_camera), onPrimary = { save() }, primaryBusy = saving,
                        )
                    }
                }
            }
        }
    }

    errorMessage?.let { MessageDialog(stringResource(R.string.setup_not_ready), it) { errorMessage = null } }
}

@Composable
private fun StreamSnapshot(url: String) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        RemoteImage(url, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun FoundStreamRow(s: WizardStream, onToggle: () -> Unit, onTest: () -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggle, enabled = s.probe != null) {
            Icon(if (s.use && s.probe != null) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (s.probe == null) scheme.onSurfaceVariant else scheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(StreamAddress.masked(s.url), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            when {
                s.testing -> Text(stringResource(R.string.setup_testing), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                s.probe != null -> Text(s.probe.summary, fontSize = 12.sp, color = Palette.status.online)
                s.error != null -> Text(s.error, fontSize = 12.sp, color = scheme.error, maxLines = 2)
            }
        }
        if (s.testing) SmallSpinner(Modifier.padding(horizontal = 12.dp))
        else if (s.probe == null) {
            TextButton(onClick = onTest) { Text(stringResource(R.string.setup_test), maxLines = 1) }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.remove), tint = scheme.onSurfaceVariant) }
        }
    }
}

/** A choice from a short list: a row with an up/down chevron that opens a menu. */
@Composable
internal fun MenuRow(title: String, value: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Icon(Icons.Filled.UnfoldMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            for ((i, o) in options.withIndex()) {
                DropdownMenuItem(
                    text = { Text(o) },
                    trailingIcon = if (i == selected) ({ Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = { open = false; onSelect(i) },
                )
            }
        }
    }
}
