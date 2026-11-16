package nl.wiebenieuwenhuis.tender.ui.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.BuildConfig
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.ServerAddress
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ConnectionStatus
import nl.wiebenieuwenhuis.tender.app.Scope
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.ConnectRoute
import nl.wiebenieuwenhuis.tender.ui.LiveSettingsRoute
import nl.wiebenieuwenhuis.tender.ui.LiveTab
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.ServerSettingsRoute
import nl.wiebenieuwenhuis.tender.ui.components.LinkRow
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.StatusDot
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth
import nl.wiebenieuwenhuis.tender.ui.selectTab
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import nl.wiebenieuwenhuis.tender.ui.theme.serverTint

/** Settings: which servers the tabs show, your servers, and the app's Live settings. */
@Composable
fun ServersScreen(showsBack: Boolean = true) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    ScreenScaffold(
        stringResource(R.string.servers_title), showsBack = showsBack,
        actions = {
            IconButton(onClick = { nav.navigate(ConnectRoute()) }) { Icon(Icons.Filled.Add, stringResource(R.string.add_server)) }
        },
    ) { pad ->
        PullToRefreshBox(refreshing, onRefresh = {
            scope.launch { refreshing = true; AppModel.refreshAll(reloadConfig = true); refreshing = false }
        }, Modifier.padding(pad).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.readableWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.show))
                    val allActive = AppModel.scopeSelection == Scope.All
                    ScopeCard(allActive, onClick = { AppModel.selectScope(Scope.All); selectTab(nav, LiveTab) }) {
                        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.all_servers), style = MaterialTheme.typography.titleMedium)
                            val online = AppModel.onlineConnections
                            val cams = online.sumOf { it.cameras.size }
                            Text(pluralStringResource(R.plurals.n_cameras, cams, cams) + " " + pluralStringResource(R.plurals.from_n_online_servers, online.size, online.size),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (allActive) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.your_servers), Modifier.weight(1f))
                        if (AppModel.servers.size > 1) IconButton(onClick = { reordering = true }) {
                            Icon(Icons.Filled.SwapVert, stringResource(R.string.reorder_servers))
                        }
                    }
                    for (conn in AppModel.orderedConnections) ServerRow(conn)
                    SectionLabel(stringResource(R.string.settings), Modifier.padding(top = 10.dp))
                    SectionCard {
                        LinkRow(stringResource(R.string.live_view), { nav.navigate(LiveSettingsRoute) },
                            subtitle = stringResource(R.string.live_view_subtitle), icon = Icons.Outlined.GridView)
                    }
                    Text(stringResource(R.string.about_line), Modifier.fillMaxWidth().padding(top = 20.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) +
                        (if (BuildConfig.GIT_COMMIT.isNotEmpty()) " · ${BuildConfig.GIT_COMMIT}" else ""), Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
    if (reordering) ReorderServersDialog { reordering = false }
}

@Composable
private fun ScopeCard(active: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = if (active) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, content = content) }
}

@Composable
private fun ServerRow(conn: ServerConnection) {
    val nav = LocalNav.current
    val active = AppModel.scopeSelection == Scope.Server(conn.id)
    val status = Palette.status
    ScopeCard(active, onClick = { AppModel.selectScope(Scope.Server(conn.id)); selectTab(nav, LiveTab) }) {
        val tint = serverTint(conn.server.tintIndex)
        Box(Modifier.size(40.dp).background(tint, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(conn.server.initial, color = Color(0xFF0A0B0D), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conn.server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                StatusDot(when (conn.status) {
                    ConnectionStatus.Online -> status.online
                    ConnectionStatus.Connecting -> status.warning
                    is ConnectionStatus.Offline -> status.offline
                })
            }
            Text(conn.metaLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(ServerAddress.display(if (conn.usingRemote) conn.server.remoteUrl ?: conn.server.url else conn.server.url),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (active) Icon(Icons.Filled.Check, stringResource(R.string.active_server), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        // The server's settings: its connection (address, sign-in) and, for admins, Frigate's own settings.
        FilledTonalIconButton(onClick = {
            nav.navigate(ServerSettingsRoute(conn.id))
        }) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings_of, conn.server.name)) }
    }
}

/** Puts servers in the order every tab shows them in. */
@Composable
private fun ReorderServersDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reorder_servers)) },
        text = {
            LazyColumn {
                itemsIndexed(AppModel.servers, key = { _, s -> s.id }) { i, s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ServerBadge(s, 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { AppModel.moveServer(i, i - 1) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, null) }
                        IconButton(onClick = { AppModel.moveServer(i, i + 1) }, enabled = i < AppModel.servers.size - 1) { Icon(Icons.Filled.ArrowDownward, null) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
