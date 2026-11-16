package nl.wiebenieuwenhuis.tender.ui.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateException
import nl.wiebenieuwenhuis.tender.api.Http
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.ServerAddress
import nl.wiebenieuwenhuis.tender.api.await
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConfig
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.ConnectRoute
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.PasswordField
import nl.wiebenieuwenhuis.tender.ui.components.ProgressButton
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.StatusDot
import nl.wiebenieuwenhuis.tender.ui.components.SwitchRow
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface

/** Adding a server (onboarding or from Settings) or editing how the app connects to one. */
@Composable
fun ConnectScreen(route: ConnectRoute, onboarding: Boolean = false) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val existing = route.serverId?.let { id -> AppModel.servers.firstOrNull { it.id == id } }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var url by rememberSaveable { mutableStateOf(existing?.url ?: "") }
    var username by rememberSaveable { mutableStateOf(existing?.username ?: "") }
    var password by rememberSaveable { mutableStateOf(existing?.password ?: "") }
    var useRemote by rememberSaveable { mutableStateOf(!existing?.remoteUrl.isNullOrEmpty()) }
    var remoteUrl by rememberSaveable { mutableStateOf(existing?.remoteUrl ?: "") }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scanner = remember { NetworkScanner() }
    LaunchedEffect(Unit) { if (existing == null) scanner.scan() }

    val title = when {
        onboarding -> stringResource(R.string.connect_to_frigate)
        existing == null -> stringResource(R.string.add_server)
        else -> stringResource(R.string.edit_server)
    }
    val intro = when {
        onboarding -> stringResource(R.string.connect_intro_onboarding)
        existing != null -> stringResource(R.string.connect_intro_edit)
        else -> stringResource(R.string.connect_intro_add)
    }
    val canSave = ServerConfig.normalizedUrl(url) != null

    fun testAndSave() = scope.launch {
        error = null
        val guesses = ServerAddress.candidates(url)
        if (guesses.isEmpty()) { error = FrigateException.InvalidUrl().message; return@launch }
        val wantsRemote = useRemote && remoteUrl.isNotBlank()
        val remoteGuesses = if (wantsRemote) ServerAddress.candidates(remoteUrl) else emptyList()
        if (wantsRemote && remoteGuesses.isEmpty()) { error = FrigateException.InvalidUrl().message; return@launch }
        testing = true
        try {
            // Without http(s) or a port, try the usual ones (all at once) and keep the first that answers.
            val (found, remoteFound) = coroutineScope {
                val a = async { ServerAddress.firstReachable(guesses) }
                val b = async { if (remoteGuesses.isEmpty()) null else ServerAddress.firstReachable(remoteGuesses) }
                a.await() to b.await()
            }
            val base = found ?: guesses.first()
            val remoteBase = remoteFound ?: remoteGuesses.firstOrNull()
            if (found == null && remoteFound == null) {
                val typed = ServerAddress.parse(url)
                val host = typed?.let { p -> p.host + (p.port?.let { ":$it" } ?: "") } ?: url
                error = if (typed?.port == null) L(R.string.no_server_answered_port, host) else L(R.string.no_server_answered, host)
                return@launch
            }
            val baseStr = base.toString().trimEnd('/')
            var server = (existing ?: ServerConfig(name = "", url = "")).copy(
                name = name.trim().ifEmpty { base.host },
                url = baseStr,
                username = username.ifEmpty { null },
                remoteUrl = remoteBase?.toString()?.trimEnd('/'),
            )
            var lastError: Throwable? = null
            var reached = false
            for (u in listOfNotNull(found, remoteFound)) {
                try {
                    ServerConnection.authenticate(FrigateClient(u), server, password)
                    reached = true; break
                } catch (e: Exception) { lastError = e }
            }
            if (!reached) { error = lastError?.message ?: L(R.string.could_not_reach); return@launch }
            url = baseStr
            remoteBase?.let { remoteUrl = it.toString().trimEnd('/') }
            AppModel.save(server, password.ifEmpty { null })
            if (!onboarding) nav.popBackStack()
        } finally {
            testing = false
        }
    }

    ScreenScaffold(title, showsBack = !onboarding) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.readableWidth(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (existing == null) Discovered(scanner, url) { picked ->
                    url = picked
                    if (name.isEmpty()) name = "Frigate"
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, placeholder = { Text(stringResource(R.string.name_placeholder)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.server_url)) }, placeholder = { Text("192.168.1.20") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next))
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, placeholder = { Text(stringResource(R.string.optional)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
                PasswordField(password, { password = it }, stringResource(R.string.password))
                SectionCard {
                    SwitchRow(stringResource(R.string.remote_address), useRemote, { useRemote = it }, subtitle = stringResource(R.string.remote_address_subtitle))
                }
                AnimatedVisibility(useRemote) {
                    OutlinedTextField(remoteUrl, { remoteUrl = it }, label = { Text(stringResource(R.string.remote_url)) }, placeholder = { Text("nvr.example.com") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                }
                error?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ProgressButton(
                    if (testing) stringResource(R.string.testing) else stringResource(R.string.test_and_save), testing, { testAndSave() },
                    Modifier.fillMaxWidth().height(52.dp), enabled = canSave,
                )
                Text(stringResource(R.string.credentials_stored), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (existing != null) TextButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.remove_server), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete && existing != null) ConfirmDialog(
        title = stringResource(R.string.remove_named, existing.name),
        message = stringResource(R.string.remove_server_message),
        confirmText = stringResource(R.string.remove),
        onConfirm = {
            AppModel.delete(existing)
            // The server's settings page below closes itself once its server is gone.
            if (AppModel.servers.isNotEmpty()) nav.popBackStack()
        },
        onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun Discovered(scanner: NetworkScanner, url: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.found_on_network), Modifier.weight(1f))
            if (scanner.scanning) SmallSpinner(size = 14.dp)
            else {
                val scope = rememberCoroutineScope()
                TextButton(onClick = { scope.launch { scanner.scan() } }) { Text(stringResource(R.string.scan_again)) }
            }
        }
        val known = AppModel.servers.mapNotNull { runCatching { ServerConfig.normalizedUrl(it.url)?.toHttpUrl()?.host }.getOrNull() }.toSet()
        val found = scanner.found.filter { it.url.host !in known }
        if (found.isEmpty()) Text(
            stringResource(if (scanner.scanning) R.string.looking_for_servers else R.string.no_new_servers),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (s in found) {
            val selected = ServerConfig.normalizedUrl(url) == s.url.toString().trimEnd('/')
            Card(
                onClick = { onPick(s.url.toString().trimEnd('/')) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Palette.status.online, size = 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.url.host, style = MaterialTheme.typography.titleSmall)
                        Text("${ServerAddress.display(s.url.toString())} · ${Format.shortVersion(s.version)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Finds Frigate servers on the local /24 network by probing /api/version. */
class NetworkScanner {
    data class Found(val url: HttpUrl, val version: String)

    val found = mutableStateListOf<Found>()
    var scanning by mutableStateOf(false)
        private set

    suspend fun scan() {
        if (scanning) return
        scanning = true
        try {
            val ports = listOf(8971, 5000)
            val hosts = mutableListOf("frigate.local", "frigate")
            for (prefix in localPrefixes()) for (i in 1..254) hosts.add("$prefix.$i")
            val client = Http.withTimeout(2)
            val gate = Semaphore(64)
            val results = Channel<Found>(Channel.UNLIMITED)
            coroutineScope {
                for (h in hosts) for (p in ports) launch(Dispatchers.IO) {
                    gate.withPermit {
                        val url = runCatching { "http://$h:$p".toHttpUrl() }.getOrNull() ?: return@withPermit
                        val v = withTimeoutOrNull(2000) { probe(client, url) } ?: return@withPermit
                        withContext(Dispatchers.Main) { add(Found(url, v)) }
                    }
                }
            }
            results.close()
        } finally {
            scanning = false
        }
    }

    /** Prefer port 8971 when a host answers on both. */
    private fun add(f: Found) {
        val i = found.indexOfFirst { it.url.host == f.url.host }
        if (i >= 0) { if (found[i].url.port != 8971) found[i] = f }
        else { found.add(f); found.sortBy { it.url.toString() } }
    }

    private suspend fun probe(client: okhttp3.OkHttpClient, url: HttpUrl): String? = runCatching {
        client.newCall(Request.Builder().url(url.newBuilder().addPathSegments("api/version").build()).build()).await().use { res ->
            if (res.code != 200) return@use null
            val t = res.body.string().trim()
            if (t.firstOrNull()?.isDigit() == true && t.length < 40 && t.contains('.')) t else null
        }
    }.getOrNull()

    /** First three octets of each private IPv4 interface address. */
    private fun localPrefixes(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { ip ->
                val p = ip.split(".")
                p.size == 4 && (ip.startsWith("192.168.") || ip.startsWith("10.") || (p[0] == "172" && (p[1].toIntOrNull() ?: 0) in 16..31))
            }
            .map { it.split(".").take(3).joinToString(".") }.distinct()
    }.getOrDefault(emptyList())
}
