package nl.wiebenieuwenhuis.tender.ui.exports

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.ExportPlayerRoute
import nl.wiebenieuwenhuis.tender.ui.ItemCache
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.TextPromptDialog
import nl.wiebenieuwenhuis.tender.ui.explore.zoomable
import nl.wiebenieuwenhuis.tender.ui.playback.PlaybackOverlayControls
import nl.wiebenieuwenhuis.tender.ui.playback.PlayerTexture
import nl.wiebenieuwenhuis.tender.ui.playback.VideoSpinner
import nl.wiebenieuwenhuis.tender.ui.playback.rememberRecordingPlayer

/** One export: watch it, share or save the file, rename or delete it. */
@Composable
fun ExportPlayerScreen(route: ExportPlayerRoute) {
    var export by remember { mutableStateOf(ItemCache.exports[route.exportId]) }
    var failed by remember { mutableStateOf<String?>(null) }
    val client = AppModel.connection(route.serverId)?.client
    LaunchedEffect(route, client) {
        if (export != null || client == null) return@LaunchedEffect
        runCatching { client.exports().firstOrNull { it.id == route.exportId } }
            .onSuccess { e -> if (e != null) export = ItemCache.put(e) else failed = L(R.string.exports_not_found) }
            .onFailure { failed = it.message ?: it.toString() }
    }
    val e = export
    if (e == null) {
        ScreenScaffold("") { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (failed != null) EmptyState(Icons.Outlined.Share, stringResource(R.string.exports_could_not_load), failed)
                else LoadingBox(Modifier.padding(top = 80.dp))
            }
        }
    } else {
        ExportPlayer(ExportEntry(route.serverId, e))
    }
}

/** A download running for sharing or saving, shown in a pill at the top. */
private data class Progress(val text: String, val fraction: Float?)

private enum class FileJob { Share, Save }

@Composable
private fun ExportPlayer(entry: ExportEntry) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn = AppModel.connection(entry.serverId)
    val client = conn?.client
    val camera = conn?.camera(entry.export.camera)
    val player = rememberRecordingPlayer()
    var name by remember { mutableStateOf(entry.export.name) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<FileJob?>(null) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val busy = AppModel.busyExports[entry.export.id] == true

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(client) {
        if (client != null) player.load(client.exportFileUrl(entry.export))
    }
    // Controls hide a moment after the last touch while playing; a paused video keeps them.
    LaunchedEffect(overlayToken, player.wantsToPlay, scrubbing) {
        if (!player.wantsToPlay) { overlayVisible = true; return@LaunchedEffect }
        delay(4000)
        if (!scrubbing) overlayVisible = false
    }

    fun shareFile() {
        if (job != null) return
        job = FileJob.Share
        scope.launch {
            progress = Progress(L(R.string.exports_preparing), null)
            try {
                val file = ExportActions.download(entry) { f -> progress = Progress(L(R.string.exports_preparing), f.takeIf { it >= 0 }) }
                progress = null
                FileShare.share(context, file, "video/mp4")
            } catch (e: Exception) {
                progress = null
                errorMessage = e.message ?: e.toString()
            } finally {
                job = null
            }
        }
    }

    fun saveToDevice() {
        if (job != null) return
        job = FileJob.Save
        scope.launch {
            progress = Progress(L(R.string.exports_downloading), null)
            try {
                val file = ExportActions.download(entry) { f -> progress = Progress(L(R.string.exports_downloading), f.takeIf { it >= 0 }) }
                FileShare.saveVideo(context, file, "${name.ifEmpty { entry.export.id }}.mp4")
                progress = Progress(L(R.string.exports_saved), 1f)
                delay(2500)
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
            } finally {
                progress = null
                job = null
            }
        }
    }

    // No title: the info under the video already names the export.
    ScreenScaffold(
        "",
        actions = {
            IconButton(onClick = { shareFile() }, enabled = client != null) {
                if (job == FileJob.Share) SmallSpinner() else Icon(Icons.Outlined.Share, stringResource(R.string.exports_share))
            }
        },
    ) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val wide = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.then(if (wide) Modifier.widthIn(max = 900.dp) else Modifier).fillMaxWidth()) {
                    // The video, pinch to zoom, with the playback controls along its bottom.
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = if (wide) 16.dp else 0.dp)
                            .clip(RoundedCornerShape(if (wide) 12.dp else 0.dp))
                            .aspectRatio(camera?.aspectRatio ?: (16f / 9f)).background(Color.Black),
                    ) {
                        PlayerTexture(
                            player.exo, player.videoAspect,
                            Modifier.fillMaxSize().zoomable(onTap = { overlayVisible = !overlayVisible; overlayToken++ }),
                        )
                        val err = player.error
                        if (err != null) {
                            Text(err, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Center).padding(16.dp))
                        } else if (player.isBuffering || player.duration == 0.0) {
                            Box(Modifier.align(Alignment.Center)) { VideoSpinner(large = wide) }
                        }
                        androidx.compose.animation.AnimatedVisibility(overlayVisible && player.duration > 0, Modifier.align(Alignment.BottomCenter),
                            enter = fadeIn(), exit = fadeOut()) {
                            PlaybackOverlayControls(player, large = wide, onUse = { overlayToken++ })
                        }
                    }
                    // Scrubber and times.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp)) {
                        val total = player.duration.coerceAtLeast(0.1)
                        Slider(
                            value = if (scrubbing) scrubValue else (player.currentTime / total).toFloat().coerceIn(0f, 1f),
                            onValueChange = {
                                scrubbing = true; scrubValue = it; overlayToken++
                                player.seek(it * total)
                            },
                            onValueChangeFinished = { player.seek(scrubValue * total, precise = true); scrubbing = false },
                            enabled = player.duration > 0,
                        )
                        Row(Modifier.fillMaxWidth()) {
                            val shown = if (scrubbing) scrubValue * total else player.currentTime
                            Text(Format.duration(shown), style = MaterialTheme.typography.bodySmall.merge(MonoDigits),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(Format.duration(player.duration), style = MaterialTheme.typography.bodySmall.merge(MonoDigits),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Name, camera and date.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name.ifEmpty { stringResource(R.string.exports_export) }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (AppModel.orderedConnections.size > 1 && conn != null) { ServerBadge(conn.server, size = 18.dp); Spacer(Modifier.width(6.dp)) }
                            val date = entry.export.date.toDate()
                            Text("${camera?.displayName ?: Format.prettyName(entry.export.camera)} · ${Format.dayTitle(date)}, ${Format.time(date)}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Actions: buttons side by side are equally wide and tall.
                    Column(Modifier.fillMaxWidth().padding(16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (FileShare.canSaveVideo) {
                                ActionButton(stringResource(R.string.exports_save_to_device), Modifier.weight(1f), enabled = job == null && client != null,
                                    busy = job == FileJob.Save, icon = { Icon(Icons.Outlined.Download, null) }) { saveToDevice() }
                            }
                            ActionButton(stringResource(R.string.rename), Modifier.weight(1f), enabled = !busy, icon = { Icon(Icons.Outlined.Edit, null) }) { renaming = true }
                        }
                        OutlinedButton(
                            onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            if (busy) { SmallSpinner(size = 16.dp); Spacer(Modifier.width(8.dp)) }
                            Text(stringResource(R.string.exports_delete_export), maxLines = 1)
                        }
                    }
                }
            }
            // Download progress, at the top while it runs.
            progress?.let { p ->
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp), shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.fraction != 1f) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
                        Text(p.text + (p.fraction?.takeIf { it < 1f }?.let { " ${(it * 100).toInt()}%" } ?: ""),
                            color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge.merge(MonoDigits), maxLines = 1)
                    }
                }
            }
        }
    }

    if (renaming) {
        TextPromptDialog(
            title = stringResource(R.string.exports_rename_export), initial = name, confirmText = stringResource(R.string.rename),
            label = stringResource(R.string.exports_name),
            onConfirm = { newName ->
                AppModel.scope.launch {
                    try { ExportActions.rename(entry, newName); name = newName; ItemCache.put(entry.export.copy(name = newName)) }
                    catch (e: Exception) { errorMessage = e.message ?: e.toString() }
                }
            },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.exports_delete_title), message = stringResource(R.string.exports_delete_message),
            confirmText = stringResource(R.string.delete),
            onConfirm = {
                scope.launch {
                    try { ExportActions.delete(entry); player.stop(); nav.popBackStack() }
                    catch (e: Exception) { errorMessage = e.message ?: e.toString() }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.exports_something_wrong), it) { errorMessage = null } }
}

@Composable
private fun ActionButton(
    text: String, modifier: Modifier, enabled: Boolean, busy: Boolean = false,
    icon: @Composable () -> Unit, onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
        if (busy) SmallSpinner(size = 16.dp) else icon()
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1, softWrap = false)
    }
}
