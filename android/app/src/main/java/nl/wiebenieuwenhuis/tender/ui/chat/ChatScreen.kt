package nl.wiebenieuwenhuis.tender.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.ReadableWidth
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner

/** Frigate's GenAI chat: questions about the cameras, answered by the server's chat provider. */
@Composable
fun ChatScreen(showsBack: Boolean = true) {
    val connections = AppModel.chatConnections
    // The server picked in the chat, else the first server that has chat.
    val conn = connections.firstOrNull { it.id == ChatSessions.serverId } ?: connections.firstOrNull()
    val session = conn?.let { ChatSessions.session(it.id) }
    var input by rememberSaveable { mutableStateOf("") }

    fun send(text: String) {
        val client = conn?.client ?: return
        session?.send(text, client)
        input = ""
    }

    ScreenScaffold(
        stringResource(R.string.chat), showsBack = showsBack,
        actions = {
            if (session != null && !session.isEmpty) {
                IconButton(onClick = { session.reset() }) { Icon(Icons.Outlined.EditNote, stringResource(R.string.chat_new)) }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = ReadableWidth).fillMaxSize()) {
                if (conn == null || session == null) {
                    Spacer(Modifier.weight(1f))
                    EmptyState(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.chat_unavailable), stringResource(R.string.chat_unavailable_message))
                    Spacer(Modifier.weight(1f))
                    return@Column
                }
                ServerLine(conn, connections)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (session.isEmpty) Intro { send(it) } else Conversation(session)
                }
                InputBar(input, { input = it }, busy = session.busy, onSend = { send(input) }, onStop = { session.stop() })
            }
        }
    }
}

/** Frigate's chat answers for one server; with several, pick or show which one. */
@Composable
private fun ServerLine(conn: ServerConnection, connections: List<ServerConnection>) {
    if (connections.size > 1) {
        var open by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
            Row(
                Modifier.clickable { open = true }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.chat_ask), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ServerBadge(conn.server, size = 18.dp)
                Text(conn.server.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(open, { open = false }) {
                for (c in connections) DropdownMenuItem(
                    text = { Text(c.server.name, maxLines = 1) },
                    leadingIcon = { if (c.id == conn.id) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                    trailingIcon = { ServerBadge(c.server, size = 18.dp) },
                    onClick = { open = false; ChatSessions.serverId = c.id },
                )
            }
        }
    } else if (AppModel.orderedConnections.size > 1) {
        Text(
            stringResource(R.string.chat_asking, conn.server.name), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun Intro(onAsk: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.size(48.dp))
        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.chat_intro_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(stringResource(R.string.chat_intro_message), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        for (q in listOf(R.string.chat_suggest_events, R.string.chat_suggest_status, R.string.chat_suggest_away)) {
            val text = stringResource(q)
            Surface(
                onClick = { onAsk(text) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Conversation(session: ChatSession) {
    val lines = session.lines
    val state = rememberLazyListState()
    val itemCount = lines.size + (if (session.busy) 1 else 0) + (if (session.error != null) 1 else 0)
    LaunchedEffect(itemCount, session.streaming.length, session.busy) {
        if (itemCount > 0) state.animateScrollToItem(itemCount - 1, scrollOffset = Int.MAX_VALUE / 2)
    }
    LazyColumn(
        Modifier.fillMaxSize(), state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(lines, key = { it.id }) { ChatLineView(it) }
        if (session.busy) {
            item(key = "busy") {
                if (session.streaming.isEmpty()) {
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallSpinner()
                        Text(stringResource(R.string.chat_thinking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    ChatLineView(ChatLine(-1, ChatLine.Kind.Assistant, session.streaming))
                }
            }
        }
        session.error?.let { error ->
            item(key = "error") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ChatLineView(line: ChatLine) {
    when (line.kind) {
        ChatLine.Kind.User -> Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f).widthIn(min = 48.dp))
            SelectionContainer {
                Text(
                    line.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        ChatLine.Kind.Assistant -> Row(Modifier.fillMaxWidth()) {
            SelectionContainer(Modifier.weight(1f, fill = false)) {
                Text(
                    chatMarkdown(line.text, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.width(32.dp))
        }
        ChatLine.Kind.Tools -> Row(
            Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Outlined.Build, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.chat_used, line.text), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InputBar(text: String, onText: (String) -> Unit, busy: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            text, onText, modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(20.dp),
            placeholder = { Text(stringResource(R.string.chat_placeholder), maxLines = 1) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!busy) onSend() }),
        )
        FilledIconButton(
            onClick = { if (busy) onStop() else onSend() },
            enabled = busy || text.isNotBlank(),
            modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
        ) {
            if (busy) Icon(Icons.Filled.Stop, stringResource(R.string.chat_stop))
            else Icon(Icons.Filled.ArrowUpward, stringResource(R.string.chat_send))
        }
    }
}
