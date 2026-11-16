package nl.wiebenieuwenhuis.tender.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.FrigateEvent
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.ItemCache
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.ProgressButton
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner

/**
 * A tracked object's description (written by GenAI or by hand) and Frigate's "Find similar", as
 * in Frigate's tracked object details. Shown only where it applies: the description when there is
 * one or GenAI describes the camera's objects, Find similar with semantic search on. Editing and
 * regenerating are for admins.
 */
@Composable
fun ObjectDescriptionSection(serverId: String, event: FrigateEvent) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val conn = AppModel.connection(serverId)
    val camera = conn?.camera(event.camera)
    val isAdmin = conn?.isAdmin ?: false
    val canSearch = conn?.config?.semanticSearch == true
    val describes = camera?.describesObjects == true
    var text by remember(event.id) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var regenerating by remember { mutableStateOf(false) }
    /** GenAI was asked for a new description; it arrives a little later. */
    var requested by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var regenerateMenu by remember { mutableStateOf(false) }
    val description = text ?: event.description ?: ""
    val showsDescription = description.isNotEmpty() || describes || canSearch

    // A new description: ask Frigate now and then until it is there (GenAI takes a while).
    LaunchedEffect(requested) {
        val client = conn?.client
        if (!requested || client == null) return@LaunchedEffect
        val before = description
        for (i in 0 until 20) {
            delay(3000)
            val fresh = runCatching { client.event(event.id) }.getOrNull()?.description
            if (fresh != null && fresh != before) { text = fresh; break }
        }
        requested = false
    }

    fun regenerate(fromSnapshot: Boolean) {
        val client = conn?.client ?: return
        if (regenerating) return
        regenerating = true
        scope.launch {
            try {
                client.regenerateDescription(event.id, fromSnapshot)
                requested = true
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
            } finally {
                regenerating = false
            }
        }
    }

    fun save() {
        val client = conn?.client ?: return
        if (saving) return
        saving = true
        scope.launch {
            try {
                val new = draft.trim()
                client.setDescription(event.id, new)
                text = new
                editing = false
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canSearch) {
            OutlinedButton(
                onClick = {
                    ItemCache.put(event)
                    nav.navigate(ExploreSearchRoute(serverId, event.id))
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Outlined.FilterNone, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.explore_find_similar), maxLines = 1)
            }
        }
        if (showsDescription) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.explore_description), Modifier.weight(1f))
                if (isAdmin) {
                    IconButton(onClick = { draft = description; editing = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.edit), Modifier.size(18.dp))
                    }
                    if (describes && event.endTime != null) {
                        Box {
                            IconButton(onClick = { regenerateMenu = true }, enabled = !regenerating && !requested, modifier = Modifier.size(36.dp)) {
                                if (regenerating) SmallSpinner(size = 16.dp)
                                else Icon(Icons.Outlined.AutoAwesome, stringResource(R.string.explore_regenerate), Modifier.size(18.dp))
                            }
                            DropdownMenu(regenerateMenu, { regenerateMenu = false }) {
                                if (event.hasSnapshot == true) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.explore_regenerate_snapshot)) },
                                    onClick = { regenerateMenu = false; regenerate(true) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.explore_regenerate_thumbnails)) },
                                    onClick = { regenerateMenu = false; regenerate(false) },
                                )
                            }
                        }
                    }
                }
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
                    when {
                        description.isEmpty() && describes && event.endTime == null ->
                            Text(stringResource(R.string.explore_description_after_end), style = MaterialTheme.typography.bodyMedium, color = secondary)
                        requested -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallSpinner(size = 16.dp)
                            Text(stringResource(R.string.explore_waiting_description), style = MaterialTheme.typography.bodyMedium, color = secondary)
                        }
                        description.isEmpty() -> Text(stringResource(R.string.explore_none), style = MaterialTheme.typography.bodyMedium, color = secondary)
                        else -> SelectionContainer { Text(description, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { if (!saving) editing = false },
            title = { Text(stringResource(R.string.explore_description)) },
            text = {
                OutlinedTextField(
                    draft, { draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
                    placeholder = { Text(stringResource(R.string.explore_description_placeholder)) },
                )
            },
            confirmButton = { ProgressButton(stringResource(R.string.save), busy = saving, onClick = { save() }, filled = false) },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
        )
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.explore_something_wrong), it) { errorMessage = null } }
}
