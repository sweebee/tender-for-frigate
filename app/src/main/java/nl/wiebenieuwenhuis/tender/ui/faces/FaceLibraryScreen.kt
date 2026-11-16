package nl.wiebenieuwenhuis.tender.ui.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.ClassificationAttempt
import nl.wiebenieuwenhuis.tender.api.ClassificationNames
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.createFace
import nl.wiebenieuwenhuis.tender.api.deleteFaces
import nl.wiebenieuwenhuis.tender.api.faceImageUrl
import nl.wiebenieuwenhuis.tender.api.faces
import nl.wiebenieuwenhuis.tender.api.reclassifyFace
import nl.wiebenieuwenhuis.tender.api.registerFace
import nl.wiebenieuwenhuis.tender.api.renameFace
import nl.wiebenieuwenhuis.tender.api.reprocessFace
import nl.wiebenieuwenhuis.tender.api.trainFace
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.MoreTab
import nl.wiebenieuwenhuis.tender.ui.classification.AdaptiveGrid
import nl.wiebenieuwenhuis.tender.ui.classification.CountChip
import nl.wiebenieuwenhuis.tender.ui.classification.EmptyText
import nl.wiebenieuwenhuis.tender.ui.classification.ImageTile
import nl.wiebenieuwenhuis.tender.ui.classification.LibrarySheet
import nl.wiebenieuwenhuis.tender.ui.classification.NoticeLine
import nl.wiebenieuwenhuis.tender.ui.classification.SelectionBar
import nl.wiebenieuwenhuis.tender.ui.classification.ServerPickerChip
import nl.wiebenieuwenhuis.tender.ui.classification.SheetActionButton
import nl.wiebenieuwenhuis.tender.ui.classification.percent
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.DestructiveMenuItem
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.ReadableWidth
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.TextPromptDialog
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.io.ByteArrayOutputStream

/** The face library of each server, kept while the app runs so the screen shows at once when it comes back. */
private object FaceCache {
    val faces = mutableStateMapOf<String, Map<String, List<String>>>()
    var serverId: String? = null
}

private enum class FaceTab { Recent, Faces }

/** What the face sheet shows: the tries of one tracked object, or one image of a name. */
private data class FaceSheetTarget(val serverId: String, val files: List<String>, val name: String?)

/** Colours of face scores, as Frigate's face library shows them. */
@Composable
private fun faceScoreColor(score: Double, recognition: Double, unknown: Double): Color = when {
    score >= recognition -> Palette.status.online
    score >= unknown -> Palette.status.warning
    else -> Palette.status.destructive
}

@Composable
private fun displayName(n: String): String = if (n == "unknown") stringResource(R.string.faces_unknown) else n

/**
 * Frigate's Face Library: recent recognitions to train as a person, and each person's face
 * images. One server at a time; with several, a chip picks which.
 */
@Composable
fun FaceLibraryScreen(showsBack: Boolean = true) {
    val nav = LocalNav.current
    // Opened from More on a phone: a pushed screen, with a back arrow.
    val back = showsBack || nav.previousBackStackEntry?.destination?.hasRoute(MoreTab::class) == true
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val connections = AppModel.faceConnections
    var serverId by rememberSaveable { mutableStateOf(FaceCache.serverId) }
    val conn: ServerConnection? = connections.firstOrNull { it.id == serverId } ?: connections.firstOrNull()
    val client = conn?.client
    val isAdmin = conn?.isAdmin == true
    val faces: Map<String, List<String>> = conn?.let { FaceCache.faces[it.id] } ?: emptyMap()
    var loaded by remember { mutableStateOf(conn?.let { FaceCache.faces.containsKey(it.id) } ?: false) }
    var refreshing by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(FaceTab.Recent) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    var recentFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var working by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<FaceSheetTarget?>(null) }
    var busyName by remember { mutableStateOf<String?>(null) }
    var addingFace by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deletingName by remember { mutableStateOf<String?>(null) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    var uploadMenu by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val names = faces.keys.filter { it != "train" }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    val groups = ClassificationAttempt.grouped(
        (faces["train"] ?: emptyList()).mapNotNull { ClassificationAttempt.from(it) }.sortedByDescending { it.time })
    val shownGroups = recentFilter?.let { f -> groups.filter { it.first().label == f } } ?: groups
    val recognition = conn?.config?.faceRecognitionThreshold ?: 0.9
    val unknownScore = conn?.config?.faceUnknownScore ?: 0.8

    suspend fun load() {
        val c = conn ?: run { loaded = true; return }
        val cl = c.client ?: run { loaded = true; return }
        runCatching { cl.faces() }.getOrNull()?.let { FaceCache.faces[c.id] = it }
        val fresh = FaceCache.faces[c.id] ?: emptyMap()
        val freshNames = fresh.keys.filter { it != "train" }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (tab == FaceTab.Faces && (person == null || fresh[person!!] == null)) person = freshNames.firstOrNull()
        val freshGroups = ClassificationAttempt.grouped((fresh["train"] ?: emptyList()).mapNotNull { ClassificationAttempt.from(it) })
        recentFilter?.let { rf -> if (freshGroups.none { it.first().label == rf }) recentFilter = null }
        loaded = true
    }

    LaunchedEffect(conn?.id) {
        FaceCache.serverId = conn?.id
        if (conn != null && !FaceCache.faces.containsKey(conn.id)) loaded = false
        load()
    }

    fun show(new: FaceTab) {
        if (new == tab) return
        tab = new
        selection = emptySet()
        if (new == FaceTab.Faces && (person == null || faces[person!!] == null)) person = names.firstOrNull()
    }

    /** After a name was made or renamed: show it (Frigate stores spaces as underscores). */
    fun showName(name: String) {
        val stored = name.replace(" ", "_")
        val now = conn?.let { FaceCache.faces[it.id]?.keys } ?: emptySet()
        person = now.firstOrNull { it == stored } ?: now.filter { it != "train" }.minWithOrNull(String.CASE_INSENSITIVE_ORDER)
    }

    fun createFace(raw: String) {
        val name = raw.trim()
        val cl = client ?: return
        if (!ClassificationNames.isValid(name)) { errorMessage = L(R.string.classification_name_rule); return }
        if (names.any { it.equals(name, ignoreCase = true) }) { errorMessage = L(R.string.faces_name_taken); return }
        scope.launch {
            busyName = "+new"
            try {
                cl.createFace(name)
                load()
                tab = FaceTab.Faces
                selection = emptySet()
                showName(name)
            } catch (e: Exception) { errorMessage = e.message }
            busyName = null
        }
    }

    fun rename(old: String, raw: String) {
        val name = raw.trim()
        val cl = client ?: return
        if (name.isEmpty() || name == old) return
        if (!ClassificationNames.isValid(name)) { errorMessage = L(R.string.classification_name_rule); return }
        if (faces[name] != null) { errorMessage = L(R.string.faces_name_taken); return }
        scope.launch {
            busyName = old
            try {
                cl.renameFace(old, name)
                load()
                showName(name)
            } catch (e: Exception) { errorMessage = e.message }
            busyName = null
        }
    }

    fun deleteName(n: String) {
        val c = conn ?: return
        val cl = client ?: return
        scope.launch {
            busyName = n
            try {
                cl.deleteFaces(n, faces[n] ?: emptyList())
                FaceCache.faces[c.id] = faces - n
                if (person == n) person = names.firstOrNull { it != n }
                load()
            } catch (e: Exception) { errorMessage = e.message }
            busyName = null
        }
    }

    fun deleteSelection() {
        val cl = client ?: return
        if (selection.isEmpty()) return
        scope.launch {
            working = true
            try {
                cl.deleteFaces(if (tab == FaceTab.Faces) (person ?: "train") else "train", selection.toList())
                selection = emptySet()
                selecting = false
                load()
            } catch (e: Exception) { errorMessage = e.message }
            working = false
        }
    }

    /** Sends a picture for the name on screen, as JPEG (Frigate cannot read every format a phone keeps). */
    fun upload(uri: Uri?) {
        val cl = client ?: return
        val p = person ?: return
        uri ?: return
        scope.launch {
            uploading = true
            val jpeg = withContext(Dispatchers.IO) { jpegData(context, uri) }
            if (jpeg == null) {
                errorMessage = L(R.string.faces_not_a_picture)
            } else {
                try {
                    cl.registerFace(p, jpeg)
                    notice = L(R.string.faces_uploaded)
                    load()
                } catch (e: Exception) { errorMessage = e.message }
            }
            uploading = false
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { upload(it) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { upload(it) }

    fun selectAll() {
        selection = if (tab == FaceTab.Faces) person?.let { (faces[it] ?: emptyList()).toSet() } ?: emptySet()
        else shownGroups.flatMap { g -> g.map { it.file } }.toSet()
    }

    val canSelect = selecting || (if (tab == FaceTab.Recent) groups.isNotEmpty() else !(person?.let { faces[it] }).isNullOrEmpty())

    ScreenScaffold(
        stringResource(R.string.face_library),
        showsBack = back,
        actions = {
            if (isAdmin && loaded) {
                if (canSelect) TextButton(onClick = { selecting = !selecting; selection = emptySet() }) {
                    Text(stringResource(if (selecting) R.string.done else R.string.faces_select), maxLines = 1, softWrap = false)
                }
                IconButton(onClick = { addingFace = true }, enabled = busyName != "+new") {
                    if (busyName == "+new") SmallSpinner() else Icon(Icons.Outlined.Add, stringResource(R.string.faces_add_face))
                }
            }
        },
        bottomBar = {
            if (selecting) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SelectionBar(
                    countText = pluralStringResource(R.plurals.faces_n_faces, selection.size, selection.size),
                    hasSelection = selection.isNotEmpty(), working = working, deleting = true,
                    onSelectAll = { selectAll() }, onDelete = { confirmDeleteSelection = true },
                )
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.fillMaxSize().widthIn(max = ReadableWidth),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (connections.size > 1) full("server") {
                        Row {
                            ServerPickerChip(connections, conn) {
                                serverId = it.id; FaceCache.serverId = it.id; selection = emptySet(); selecting = false; notice = null
                            }
                        }
                    }
                    when {
                        !loaded -> full("loading") { LoadingBox() }
                        conn == null -> full("off") { EmptyState(Icons.Outlined.Face, stringResource(R.string.faces_off)) }
                        else -> {
                            full("tabs") {
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    FaceTab.entries.forEachIndexed { i, t ->
                                        SegmentedButton(
                                            selected = tab == t, onClick = { show(t) },
                                            shape = SegmentedButtonDefaults.itemShape(i, FaceTab.entries.size),
                                        ) {
                                            Text(stringResource(if (t == FaceTab.Recent) R.string.faces_recent else R.string.faces_faces),
                                                maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                            if (tab == FaceTab.Faces || groups.isNotEmpty()) full("chips") {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (tab == FaceTab.Recent) {
                                        item {
                                            CountChip(stringResource(R.string.faces_all), groups.size, recentFilter == null,
                                                { recentFilter = null; selection = emptySet() })
                                        }
                                        // The names the attempts got: the library's names first, then others ("unknown").
                                        val seen = groups.map { it.first().label }
                                        val recentNames = names.filter { it in seen } + seen.distinct().filter { it !in names }
                                        items(recentNames) { n ->
                                            CountChip(displayName(n), groups.count { it.first().label == n }, recentFilter == n,
                                                { recentFilter = n; selection = emptySet() })
                                        }
                                    } else {
                                        items(names) { n ->
                                            CountChip(
                                                n, faces[n]?.size ?: 0, person == n, { person = n; selection = emptySet() },
                                                busy = busyName == n,
                                                menu = if (isAdmin) { close ->
                                                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) },
                                                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                                        onClick = { close(); renaming = n })
                                                    DestructiveMenuItem(stringResource(R.string.delete), Icons.Outlined.Delete) { close(); deletingName = n }
                                                } else null,
                                            )
                                        }
                                    }
                                }
                            }
                            notice?.let { n -> full("notice") { NoticeLine(n) } }
                            if (tab == FaceTab.Faces) {
                                val p = person
                                if (p == null) {
                                    full("empty") { EmptyText(stringResource(R.string.faces_add_first)) }
                                } else {
                                    val files = (faces[p] ?: emptyList()).sorted()
                                    if (files.isEmpty()) full("empty") { EmptyText(stringResource(R.string.faces_no_faces)) }
                                    items(files, key = { "f/$p/$it" }) { file ->
                                        val picked = file in selection
                                        ImageTile(client?.faceImageUrl(p, file), selecting, picked, onClick = {
                                            if (selecting) selection = if (picked) selection - file else selection + file
                                            else opened = FaceSheetTarget(conn.id, listOf(file), p)
                                        })
                                    }
                                    if (isAdmin) full("upload") {
                                        Box(Modifier.padding(top = 6.dp)) {
                                            OutlinedButton(
                                                onClick = { uploadMenu = true }, enabled = !uploading,
                                                modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp),
                                            ) {
                                                if (uploading) SmallSpinner() else Icon(Icons.Outlined.Upload, null)
                                                Spacer(Modifier.width(8.dp))
                                                Text(stringResource(R.string.faces_upload_image), maxLines = 1, fontWeight = FontWeight.SemiBold)
                                            }
                                            DropdownMenu(expanded = uploadMenu, onDismissRequest = { uploadMenu = false }) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.faces_photo_library)) },
                                                    leadingIcon = { Icon(Icons.Outlined.Photo, null) },
                                                    onClick = {
                                                        uploadMenu = false
                                                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                    })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.faces_files)) },
                                                    leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                                                    onClick = { uploadMenu = false; pickFile.launch(arrayOf("image/*")) })
                                            }
                                        }
                                    }
                                }
                            } else if (groups.isEmpty()) {
                                full("empty") {
                                    EmptyText(stringResource(if (names.isEmpty()) R.string.faces_add_first else R.string.faces_no_attempts))
                                }
                            } else if (shownGroups.isEmpty()) {
                                full("empty") { EmptyText(stringResource(R.string.faces_no_recent_result)) }
                            } else {
                                items(shownGroups, key = { "r/" + it.first().file }) { group ->
                                    val best = group.first()
                                    val files = group.map { it.file }
                                    val picked = files.all { it in selection }
                                    ImageTile(
                                        client?.faceImageUrl("train", best.file), selecting, picked,
                                        onClick = {
                                            if (selecting) selection = if (picked) selection - files.toSet() else selection + files
                                            else opened = FaceSheetTarget(conn.id, files, null)
                                        },
                                        caption = displayName(best.label), score = best.score,
                                        scoreColor = faceScoreColor(best.score, recognition, unknownScore), count = group.size,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    opened?.let { target ->
        FaceSheet(
            target = target, names = names, isAdmin = isAdmin, recognition = recognition, unknownScore = unknownScore,
            onDismiss = { opened = null },
            onDone = { message ->
                opened = null
                if (message != null) notice = message
                scope.launch { load() }
            },
        )
    }
    if (addingFace) TextPromptDialog(
        title = stringResource(R.string.faces_add_face), initial = "", confirmText = stringResource(R.string.add),
        label = stringResource(R.string.faces_name), onConfirm = { createFace(it) }, onDismiss = { addingFace = false },
    )
    renaming?.let { old ->
        TextPromptDialog(
            title = stringResource(R.string.faces_rename_face), initial = old, confirmText = stringResource(R.string.rename),
            label = stringResource(R.string.faces_name), onConfirm = { rename(old, it) }, onDismiss = { renaming = null },
        )
    }
    deletingName?.let { n ->
        ConfirmDialog(
            title = stringResource(R.string.faces_delete_name, n), message = stringResource(R.string.faces_delete_name_message),
            confirmText = stringResource(R.string.delete), onConfirm = { deleteName(n) }, onDismiss = { deletingName = null },
        )
    }
    if (confirmDeleteSelection) ConfirmDialog(
        title = pluralStringResource(R.plurals.faces_delete_n_faces_q, selection.size, selection.size),
        message = stringResource(R.string.faces_cannot_undo), confirmText = stringResource(R.string.delete),
        onConfirm = { deleteSelection() }, onDismiss = { confirmDeleteSelection = false },
    )
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

/** A grid item spanning the whole width (headers, chips, messages). */
private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** Reads a picked picture and turns it into a JPEG of at most 2048 pixels; null when it is no picture. */
private fun jpegData(context: Context, uri: Uri): ByteArray? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 2048) {
            val scale = 2048.0 / longest
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }
    }
    ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
    }
}.getOrNull()

// MARK: - Train or move faces

/**
 * Recent attempts of one object: train them as a person, reprocess or delete them. One image
 * of a person: move it to another person or delete it.
 */
@Composable
private fun FaceSheet(
    target: FaceSheetTarget,
    names: List<String>,
    isAdmin: Boolean,
    recognition: Double,
    unknownScore: Double,
    onDismiss: () -> Unit,
    /** Closes the sheet, with a message to show on the library when there is one. */
    onDone: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val client = AppModel.connection(target.serverId)?.client
    var picked by remember(target) { mutableStateOf(target.files.toSet()) }
    var working by remember { mutableStateOf<String?>(null) }
    var addingFace by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val attempts = target.files.mapNotNull { ClassificationAttempt.from(it) }
    val targets = names.filter { it != target.name }

    fun run(key: String, action: suspend () -> Unit) {
        scope.launch {
            working = key
            try { action() } catch (e: Exception) { errorMessage = e.message }
            working = null
        }
    }

    suspend fun assign(name: String) {
        val cl = client ?: return
        for (file in target.files.filter { it in picked }) {
            if (target.name != null) cl.reclassifyFace(file, target.name, name) else cl.trainFace(file, name)
        }
        // Frigate moves a trained try into the name's images; the tries not ticked stay, as on its page.
        onDone(L(if (target.name == null) R.string.faces_trained else R.string.faces_reclassified))
    }

    LibrarySheet(title = target.name ?: stringResource(R.string.faces_recent), onDismiss = onDismiss) {
        if (target.files.size == 1) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 280.dp)) {
                    FaceImage(client, target, target.files.first(), recognition, unknownScore, selecting = false, picked = false) {}
                }
            }
        } else {
            Text(stringResource(R.string.faces_tries_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AdaptiveGrid(target.files, minWidth = 90.dp) { file ->
                val on = file in picked
                FaceImage(client, target, file, recognition, unknownScore, selecting = true, picked = on) {
                    picked = if (on) picked - file else picked + file
                }
            }
        }
        if (target.name == null) attempts.maxByOrNull { it.score }?.let { best ->
            Text(
                if (best.label == "unknown") stringResource(R.string.faces_not_recognized)
                else stringResource(R.string.faces_frigate_thought, best.label, percent(best.score)),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isAdmin) {
            SectionLabel(stringResource(if (target.name == null) R.string.faces_train_as else R.string.faces_reclassify_as))
            AdaptiveGrid(targets + "\u0000new", minWidth = 130.dp, spacing = 8.dp) { n ->
                if (n == "\u0000new") {
                    SheetActionButton(stringResource(R.string.faces_new_face), busy = working == n, enabled = working == null,
                        onClick = { addingFace = true }, icon = Icons.Outlined.Add, tint = MaterialTheme.colorScheme.primary)
                } else {
                    SheetActionButton(n, busy = working == n, enabled = working == null && picked.isNotEmpty(), onClick = { run(n) { assign(n) } })
                }
            }
            if (target.name == null) {
                SheetActionButton(stringResource(R.string.faces_reprocess), busy = working == "\u0000reprocess",
                    enabled = working == null && picked.isNotEmpty(), icon = Icons.Outlined.Refresh,
                    onClick = {
                        run("\u0000reprocess") {
                            val cl = client ?: return@run
                            for (file in target.files.filter { it in picked }) cl.reprocessFace(file)
                            onDone(null)
                        }
                    })
            }
            SheetActionButton(
                if (picked.size > 1) pluralStringResource(R.plurals.faces_delete_n_faces, picked.size, picked.size)
                else stringResource(R.string.faces_delete_face),
                busy = working == "\u0000delete", enabled = working == null && picked.isNotEmpty(), icon = Icons.Outlined.Delete,
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    run("\u0000delete") {
                        val cl = client ?: return@run
                        cl.deleteFaces(target.name ?: "train", target.files.filter { it in picked })
                        onDone(null)
                    }
                })
        }
    }

    if (addingFace) TextPromptDialog(
        title = stringResource(R.string.faces_new_face), initial = "", confirmText = stringResource(R.string.add),
        label = stringResource(R.string.faces_name),
        onDismiss = { addingFace = false },
        onConfirm = { raw ->
            val name = raw.trim()
            when {
                !ClassificationNames.isValid(name) -> errorMessage = L(R.string.classification_name_rule)
                names.any { it.equals(name, ignoreCase = true) } -> errorMessage = L(R.string.faces_name_taken)
                else -> run("\u0000new") {
                    val cl = client ?: return@run
                    cl.createFace(name)
                    assign(name.replace(" ", "_"))
                }
            }
        },
    )
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

@Composable
private fun FaceImage(
    client: FrigateClient?,
    target: FaceSheetTarget,
    file: String,
    recognition: Double,
    unknownScore: Double,
    selecting: Boolean,
    picked: Boolean,
    onClick: () -> Unit,
) {
    val a = if (target.name == null) ClassificationAttempt.from(file) else null
    ImageTile(
        client?.faceImageUrl(target.name ?: "train", file), selecting = selecting, picked = picked, onClick = onClick,
        caption = a?.let { displayName(it.label) }, score = a?.score,
        scoreColor = a?.let { faceScoreColor(it.score, recognition, unknownScore) } ?: Color.White,
    )
}

fun NavGraphBuilder.facesDestinations() {}
