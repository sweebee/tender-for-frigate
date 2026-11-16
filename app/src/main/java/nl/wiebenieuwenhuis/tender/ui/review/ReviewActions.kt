package nl.wiebenieuwenhuis.tender.ui.review

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.TenderApplication
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.DestructiveMenuItem
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.TextPromptDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A review item of one server, as listed in Review. */
data class ReviewEntry(val serverId: String, val item: ReviewItem) {
    val key: String get() = "$serverId/${item.id}"
}

/** Review state shared by the list and the item screen for this session. */
object ReviewSession {
    /** Review items deleted here; lists leave them out at once (Frigate may lag a moment). */
    val deletedIds = mutableStateMapOf<String, Boolean>()
}

/** Reviewed on the server, or marked reviewed in this session. */
val ReviewItem.isReviewed: Boolean get() = hasBeenReviewed || AppModel.reviewedIds[id] == true

/** "Person · Voortuin, 13 Nov 15:06": a name for an export made from the app. */
fun exportName(camera: String, displayName: String?, start: Double, label: String?): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), if (Format.uses12Hour) "dMMMhmm" else "dMMMHHmm")
    val date = SimpleDateFormat(pattern, Locale.getDefault()).format(Date((start * 1000).toLong()))
    val cam = displayName ?: Format.prettyName(camera)
    return if (label != null) L(R.string.review_export_name_label, Format.labelName(label), cam, date)
    else L(R.string.review_export_name_plain, cam, date)
}

/** What a clip action works on: a span of a camera's recordings on one server. */
data class ClipRequest(val serverId: String, val camera: String, val start: Double, val end: Double, val name: String)

/** Progress of saving or sharing a clip, shown as a pill at the top of the screen. */
sealed class ClipState {
    data object Idle : ClipState()
    data class Working(val text: String) : ClipState()
    data class Done(val text: String) : ClipState()
    data class Failed(val text: String) : ClipState()
}

/**
 * Long-press actions on a review item, and the clip actions of the item screen: save as a file,
 * keep as an export in Frigate, share and delete. A screen owns one and wraps its content in
 * [ReviewActionsHost], which shows the progress pill, the file picker and the dialogs.
 */
class ReviewActions(private val scope: CoroutineScope) {
    var state by mutableStateOf<ClipState>(ClipState.Idle)
    /** Of a download in progress, 0…1 when its size is known. */
    var fraction by mutableStateOf<Float?>(null)
    var pendingDelete by mutableStateOf<ReviewEntry?>(null)
    var pendingFrigateSave by mutableStateOf<ClipRequest?>(null)
    var error by mutableStateOf<String?>(null)
    /** Review ids being deleted, for a spinner on their rows. */
    val deleting = mutableStateMapOf<String, Boolean>()
    /** Called after Frigate deleted an item. */
    var onDeleted: ((ReviewEntry) -> Unit)? = null

    internal var context: Context? = null
    internal var launchFilePicker: ((String) -> Unit)? = null
    private var pendingFile: ClipRequest? = null

    val isWorking: Boolean get() = state is ClipState.Working

    /** The item with 3 seconds around it, as the iOS app's long-press actions. */
    fun request(entry: ReviewEntry): ClipRequest? {
        val r = entry.item
        val conn = AppModel.connection(entry.serverId) ?: return null
        val now = nowSeconds()
        return ClipRequest(entry.serverId, r.camera, r.startTime - 3, minOf((r.endTime ?: now) + 3, now),
            exportName(r.camera, conn.camera(r.camera)?.displayName, r.startTime, r.labels.firstOrNull()))
    }

    private fun client(serverId: String): FrigateClient? = AppModel.connection(serverId)?.client

    private fun fileName(req: ClipRequest): String {
        val f = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "${req.camera}_${f.format(Date((req.start * 1000).toLong()))}.mp4"
    }

    /** Downloads the span as MP4 into the app's cache (shared/), with the progress in the pill. */
    private suspend fun download(req: ClipRequest): File {
        val client = client(req.serverId) ?: throw IllegalStateException(L(R.string.not_reachable))
        val dest = File(TenderApplication.context.cacheDir, "shared/${fileName(req)}")
        fraction = null
        var last = 0L
        try {
            client.download(client.clipUrl(req.camera, req.start, req.end), dest) { got, total ->
                // At most every 0.5 MB: each report redraws the pill.
                if (total > 0 && (got - last >= 500_000 || got >= total)) {
                    last = got
                    fraction = (got.toFloat() / total).coerceIn(0f, 1f)
                }
            }
        } finally {
            fraction = null
        }
        return dest
    }

    private suspend fun finish(s: ClipState) {
        state = s
        delay(2500)
        if (state == s) state = ClipState.Idle
    }

    fun share(req: ClipRequest, onBusy: ((Boolean) -> Unit)? = null) {
        if (isWorking) return
        val ctx = context ?: return
        scope.launch {
            onBusy?.invoke(true)
            state = ClipState.Working(L(R.string.review_preparing_clip))
            try {
                val file = download(req)
                state = ClipState.Idle
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, null))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                finish(ClipState.Failed(e.message ?: e.toString()))
            } finally {
                onBusy?.invoke(false)
            }
        }
    }

    /** Asks where to save the file, then downloads it there. */
    fun saveToDevice(req: ClipRequest) {
        if (isWorking) return
        val launch = launchFilePicker ?: return
        pendingFile = req
        launch(fileName(req))
    }

    internal fun fileChosen(uri: Uri?) {
        val req = pendingFile ?: return
        pendingFile = null
        if (uri == null) return
        scope.launch {
            state = ClipState.Working(L(R.string.review_saving_clip, Format.duration(req.end - req.start)))
            try {
                val file = download(req)
                withContext(Dispatchers.IO) {
                    TenderApplication.context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    file.delete()
                }
                finish(ClipState.Done(L(R.string.review_saved_file)))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                finish(ClipState.Failed(e.message ?: e.toString()))
            }
        }
    }

    /** A dialog first, for the export's name. */
    fun saveToFrigate(req: ClipRequest) { if (!isWorking) pendingFrigateSave = req }

    private fun startFrigateExport(req: ClipRequest, name: String) {
        val client = client(req.serverId) ?: return
        scope.launch {
            state = ClipState.Working(L(R.string.review_saving_to_frigate))
            try {
                client.startExport(req.camera, req.start, req.end, name)
                finish(ClipState.Done(L(R.string.review_saved_to_frigate)))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                finish(ClipState.Failed(e.message ?: e.toString()))
            }
        }
    }

    /** Deletes after the confirmation; the item stays (with a spinner) until Frigate confirms. */
    private fun delete(entry: ReviewEntry) {
        val conn = AppModel.connection(entry.serverId) ?: return
        val client = conn.client ?: return
        val id = entry.item.id
        if (deleting[id] == true) return
        scope.launch {
            deleting[id] = true
            state = ClipState.Working(L(R.string.review_deleting))
            try {
                client.deleteReviews(listOf(id))
                ReviewSession.deletedIds[id] = true
                state = ClipState.Idle
                onDeleted?.invoke(entry)
                AppModel.scope.launch { conn.refresh() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state = ClipState.Idle
                error = e.message ?: e.toString()
            } finally {
                deleting.remove(id)
            }
        }
    }

    internal fun confirmDelete() { pendingDelete?.let { delete(it) }; pendingDelete = null }
    internal fun confirmFrigateSave(name: String) { pendingFrigateSave?.let { startFrigateExport(it, name) }; pendingFrigateSave = null }
}

/** "Person at 15:06:12": what a delete confirmation names. */
fun deleteMessage(item: ReviewItem): String =
    L(R.string.review_delete_message, item.labels.firstOrNull()?.let { Format.labelName(it) } ?: L(R.string.review_event),
        Format.time(item.startTime, seconds = true))

/** The long-press menu's clip actions for one review item (inside a DropdownMenu). */
@Composable
fun ReviewItemMenuItems(entry: ReviewEntry, actions: ReviewActions, onDismiss: () -> Unit) {
    val req = actions.request(entry) ?: return
    val isAdmin = AppModel.connection(entry.serverId)?.isAdmin == true
    DropdownMenuItem(
        text = { Text(stringResource(R.string.review_save_to_device)) },
        leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) },
        onClick = { onDismiss(); actions.saveToDevice(req) },
        enabled = !actions.isWorking,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.review_save_to_frigate)) },
        leadingIcon = { Icon(Icons.Outlined.Save, null) },
        onClick = { onDismiss(); actions.saveToFrigate(req) },
        enabled = !actions.isWorking,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.review_share)) },
        leadingIcon = { Icon(Icons.Outlined.Share, null) },
        onClick = { onDismiss(); actions.share(req) },
        enabled = !actions.isWorking,
    )
    // Frigate lets only admins delete review items.
    if (isAdmin) {
        HorizontalDivider()
        DestructiveMenuItem(stringResource(R.string.review_delete), Icons.Outlined.DeleteOutline) { onDismiss(); actions.pendingDelete = entry }
    }
}

/** Progress pill, file picker, save-to-Frigate prompt and delete confirmation for [ReviewActions]. */
@Composable
fun ReviewActionsHost(actions: ReviewActions, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { actions.fileChosen(it) }
    DisposableEffect(actions, ctx, picker) {
        actions.context = ctx
        actions.launchFilePicker = { picker.launch(it) }
        onDispose { if (actions.context === ctx) { actions.context = null; actions.launchFilePicker = null } }
    }
    Box(modifier) {
        content()
        ClipPill(actions, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
    }
    actions.pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.review_delete_title),
            message = deleteMessage(entry.item),
            confirmText = stringResource(R.string.review_delete),
            onConfirm = { actions.confirmDelete() },
            onDismiss = { if (actions.pendingDelete === entry) actions.pendingDelete = null },
        )
    }
    actions.pendingFrigateSave?.let { req ->
        TextPromptDialog(
            title = stringResource(R.string.review_save_to_frigate),
            initial = req.name,
            confirmText = stringResource(R.string.save),
            label = stringResource(R.string.review_export_name),
            onConfirm = { actions.confirmFrigateSave(it) },
            onDismiss = { if (actions.pendingFrigateSave === req) actions.pendingFrigateSave = null },
        )
    }
    actions.error?.let { MessageDialog(stringResource(R.string.review_something_went_wrong), it) { actions.error = null } }
}

/** The pill at the top while a clip is saved, shared or deleted, and briefly after. */
@Composable
private fun ClipPill(actions: ReviewActions, modifier: Modifier = Modifier) {
    val s = actions.state
    if (s == ClipState.Idle) return
    Surface(modifier.widthIn(max = 420.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s) {
                is ClipState.Working -> {
                    val f = actions.fraction
                    if (f != null) CircularProgressIndicator(progress = { f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.inverseOnSurface)
                    else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
                    Text(s.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
                is ClipState.Done -> {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                    Text(s.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
                is ClipState.Failed -> {
                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp))
                    Text(s.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
                ClipState.Idle -> {}
            }
        }
    }
}
