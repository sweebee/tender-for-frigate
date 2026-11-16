package nl.wiebenieuwenhuis.tender.ui.classification

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.ClassificationAttempt
import nl.wiebenieuwenhuis.tender.api.ClassificationDataset
import nl.wiebenieuwenhuis.tender.api.ClassificationModel
import nl.wiebenieuwenhuis.tender.api.ClassificationNames
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateException
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.Lq
import nl.wiebenieuwenhuis.tender.api.attemptImageUrl
import nl.wiebenieuwenhuis.tender.api.categorize
import nl.wiebenieuwenhuis.tender.api.classificationAttempts
import nl.wiebenieuwenhuis.tender.api.classificationDataset
import nl.wiebenieuwenhuis.tender.api.datasetImageUrl
import nl.wiebenieuwenhuis.tender.api.deleteAttempts
import nl.wiebenieuwenhuis.tender.api.deleteClassificationModel
import nl.wiebenieuwenhuis.tender.api.deleteDatasetImages
import nl.wiebenieuwenhuis.tender.api.reclassify
import nl.wiebenieuwenhuis.tender.api.renameCategory
import nl.wiebenieuwenhuis.tender.api.setClassificationModel
import nl.wiebenieuwenhuis.tender.api.trainClassification
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.ClassificationModelRoute
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.MoreTab
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.DestructiveMenuItem
import nl.wiebenieuwenhuis.tender.ui.components.EmptyState
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.ReadableWidth
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.TextPromptDialog
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

// Frigate's Classification page: custom models that put tracked objects into classes, or tell
// the state of a fixed area. Per model the images Frigate saved recently can be put into a
// class, after which the model is trained again. Calls as Frigate's own page does.

/** Creating a model: the wizard, for one server. */
@Serializable data class ClassificationWizardRoute(val serverId: String, val state: Boolean)

/** Models per server, kept while the app runs so the screens show at once when they come back. */
internal object ClassificationStore {
    val models = mutableStateMapOf<String, List<ClassificationModel>>()
    /** Model id → picture of one of its classes. */
    val covers = mutableStateMapOf<String, String>()
    /** Models deleted in this session; lists leave them out until Frigate's config agrees. */
    val deleted = mutableStateMapOf<String, Boolean>()
    var showStates by mutableStateOf(false)

    fun model(serverId: String, name: String): ClassificationModel? =
        models[serverId]?.firstOrNull { it.name == name }
            ?: AppModel.connection(serverId)?.config?.raw?.let { ClassificationModel.all(it, serverId) }?.firstOrNull { it.name == name }
}

fun NavGraphBuilder.classificationDestinations() {
    composable<ClassificationWizardRoute> { ClassificationWizard(it.toRoute()) }
}

/** A grid item spanning the whole width (headers, chips, messages). */
internal fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
internal fun className(c: String): String = if (c == "none") stringResource(R.string.classification_none) else c

/** The cameras of a state model, by their names. */
private fun cameraNames(cameras: Map<String, List<Double>>, conn: ServerConnection?): String =
    cameras.keys.sorted().joinToString(", ") { conn?.config?.cameras?.get(it)?.displayName ?: Format.prettyName(it) }

private fun modelSubtitle(m: ClassificationModel, conn: ServerConnection?): String = when (val k = m.kind) {
    is ClassificationModel.Kind.Object ->
        Format.labelName(k.label) + " · " + L(if (k.asAttribute) R.string.classification_attribute_lc else R.string.classification_sub_label_lc)
    is ClassificationModel.Kind.State -> cameraNames(k.cameras, conn)
}

// MARK: - Models

/** The models of every server, as cards; Objects and States apart. */
@Composable
fun ClassificationScreen(showsBack: Boolean = true) {
    val nav = LocalNav.current
    val back = showsBack || nav.previousBackStackEntry?.destination?.hasRoute(MoreTab::class) == true
    val scope = rememberCoroutineScope()
    val connections = AppModel.classificationConnections
    val admins = connections.filter { it.isAdmin }
    val showStates = ClassificationStore.showStates
    var loaded by remember { mutableStateOf(connections.all { ClassificationStore.models.containsKey(it.id) }) }
    var refreshing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ClassificationModel?>(null) }
    var deleting by remember { mutableStateOf<ClassificationModel?>(null) }
    val removing = remember { mutableStateMapOf<String, Boolean>() }
    var addMenu by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        coroutineScope {
            connections.map { conn ->
                async {
                    val client = conn.client ?: return@async
                    conn.requestModelStates()
                    val cfg = runCatching { client.rawConfig() }.getOrNull() ?: return@async
                    val list = ClassificationModel.all(cfg, conn.id)
                    ClassificationStore.models[conn.id] = list
                    // Gone from the config now: no need to hide it any more.
                    ClassificationStore.deleted.keys.filter { id -> id.startsWith("${conn.id}/") && list.none { it.id == id } }
                        .forEach { ClassificationStore.deleted.remove(it) }
                    for (m in list) {
                        val set = runCatching { client.classificationDataset(m.name) }.getOrNull() ?: continue
                        val cls = set.classNames.firstOrNull { it != "none" && it != "unknown" && !set.categories[it].isNullOrEmpty() }
                        val file = cls?.let { set.categories[it]?.minOrNull() }
                        if (cls != null && file != null) ClassificationStore.covers[m.id] = client.datasetImageUrl(m.name, cls, file)
                        else ClassificationStore.covers.remove(m.id)
                    }
                }
            }.awaitAll()
        }
        loaded = true
    }

    LaunchedEffect(connections.map { it.id }) { load() }

    fun create(conn: ServerConnection) { nav.navigate(ClassificationWizardRoute(conn.id, showStates)) }

    fun delete(m: ClassificationModel) {
        val client = AppModel.connection(m.serverId)?.client ?: return
        scope.launch {
            removing[m.id] = true
            try {
                client.deleteClassificationModel(m.name)
                ClassificationStore.deleted[m.id] = true
                ClassificationStore.models[m.serverId] = ClassificationStore.models[m.serverId].orEmpty().filter { it.name != m.name }
                load()
            } catch (e: Exception) { errorMessage = e.message }
            removing.remove(m.id)
        }
    }

    fun modelsOf(conn: ServerConnection): List<ClassificationModel> =
        ClassificationStore.models[conn.id].orEmpty().filter { it.isState == showStates && ClassificationStore.deleted[it.id] != true }
    val anyModels = connections.any { modelsOf(it).isNotEmpty() }

    ScreenScaffold(
        stringResource(R.string.classification),
        showsBack = back,
        actions = {
            if (admins.size == 1) {
                IconButton(onClick = { create(admins.first()) }) { Icon(Icons.Outlined.Add, stringResource(R.string.classification_new_model)) }
            } else if (admins.size > 1) {
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.classification_new_model)) }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        for (c in admins) DropdownMenuItem(
                            text = { Text(c.server.name) }, leadingIcon = { ServerBadge(c.server, size = 20.dp) },
                            onClick = { addMenu = false; create(c) },
                        )
                    }
                }
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
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize().widthIn(max = ReadableWidth),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    full("intro") {
                        Text(stringResource(R.string.classification_intro), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    full("kind") {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(false, true).forEachIndexed { i, state ->
                                SegmentedButton(
                                    selected = showStates == state, onClick = { ClassificationStore.showStates = state },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                                ) {
                                    Text(stringResource(if (state) R.string.classification_states else R.string.classification_objects),
                                        maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                    when {
                        !loaded -> full("loading") { LoadingBox() }
                        connections.isEmpty() -> full("none") {
                            EmptyState(Icons.Outlined.Category, stringResource(R.string.classification_needs_017))
                        }
                        else -> {
                            if (!anyModels && connections.size > 1) full("explain") {
                                Text(stringResource(if (showStates) R.string.classification_state_explain else R.string.classification_object_explain),
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            for (conn in connections) {
                                val list = modelsOf(conn)
                                if (connections.size > 1) full("server/${conn.id}") {
                                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        ServerBadge(conn.server, size = 18.dp)
                                        Spacer(Modifier.width(6.dp))
                                        SectionLabel(conn.server.name)
                                    }
                                }
                                if (list.isEmpty() && connections.size == 1) {
                                    full("empty/${conn.id}") { EmptyExplanation(showStates, conn.isAdmin) { create(conn) } }
                                } else if (list.isEmpty()) {
                                    full("empty/${conn.id}") { EmptyRow(showStates, conn.isAdmin) { create(conn) } }
                                } else {
                                    items(list, key = { it.id }) { m ->
                                        ModelCard(
                                            m, conn, removing = removing[m.id] == true,
                                            onOpen = { nav.navigate(ClassificationModelRoute(conn.id, m.name)) },
                                            onEdit = { editing = m }, onDelete = { deleting = m },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { m -> ClassificationEditSheet(m, onDismiss = { editing = null }, onDone = { scope.launch { load() } }) }
    deleting?.let { m ->
        ConfirmDialog(
            title = stringResource(R.string.classification_delete_q, m.name), message = stringResource(R.string.classification_delete_model_message),
            confirmText = stringResource(R.string.delete), onConfirm = { delete(m) }, onDismiss = { deleting = null },
        )
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

@Composable
private fun EmptyRow(showStates: Boolean, isAdmin: Boolean, onCreate: () -> Unit) {
    SectionCard {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (showStates) R.string.classification_no_state_models else R.string.classification_no_object_models),
                Modifier.weight(1f).padding(vertical = 6.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isAdmin) TextButton(onClick = onCreate) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.classification_make_a_model), maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun EmptyExplanation(showStates: Boolean, isAdmin: Boolean, onCreate: () -> Unit) {
    SectionCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (showStates) Icons.Outlined.CenterFocusWeak else Icons.Outlined.Category, null, Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (showStates) R.string.classification_no_state_models else R.string.classification_no_object_models),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (showStates) R.string.classification_state_explain else R.string.classification_object_explain),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (isAdmin) Button(onClick = onCreate, shape = RoundedCornerShape(10.dp)) {
                Text(stringResource(R.string.classification_make_a_model), maxLines = 1, softWrap = false)
            }
        }
    }
}

private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun ModelCard(
    m: ClassificationModel,
    conn: ServerConnection,
    removing: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val cover = ClassificationStore.covers[m.id]
    val ctx = LocalContext.current
    Box {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .combinedClickable(enabled = !removing, onClick = onOpen, onLongClick = if (conn.isAdmin) ({ menu = true }) else null),
        ) {
            if (cover != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(cover).crossfade(true).build(), contentDescription = null,
                    modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
                    colorFilter = if (m.enabled) null else Grayscale,
                )
            } else {
                Icon(if (m.isState) Icons.Outlined.CenterFocusWeak else Icons.Outlined.Category, null,
                    Modifier.align(Alignment.Center).size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(m.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(modelSubtitle(m, conn), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val badgeMod = Modifier.align(Alignment.TopEnd).padding(8.dp)
            when {
                removing -> Box(badgeMod.background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(6.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                }
                !m.enabled -> Pill(stringResource(R.string.classification_off), Palette.status.warning, Palette.status.onLabel, badgeMod)
                conn.modelStates[m.name] == "training" ->
                    Pill(stringResource(R.string.classification_training), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, badgeMod, Icons.Outlined.Settings)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { menu = false; onEdit() })
            DestructiveMenuItem(stringResource(R.string.delete), Icons.Outlined.Delete) { menu = false; onDelete() }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, onColor: Color, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(modifier.background(color, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(12.dp), tint = onColor); Spacer(Modifier.width(3.dp)) }
        Text(text, color = onColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

// MARK: - One model

private enum class ModelTab { Recent, Training }

/** What the assign sheet shows: the images of one object, or one labelled image. */
private data class AssignTarget(val files: List<String>, val category: String?)

private const val UNSURE = "\u0000unsure"

/** A model's recent images and classes; put images in a class and train the model. */
@Composable
fun ClassificationModelScreen(route: ClassificationModelRoute) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val conn = AppModel.connection(route.serverId)
    val client = conn?.client
    var current by remember { mutableStateOf(ClassificationStore.model(route.serverId, route.model)) }
    var dataset by remember { mutableStateOf(ClassificationDataset()) }
    var attempts by remember { mutableStateOf<List<ClassificationAttempt>>(emptyList()) }
    var tab by rememberSaveable { mutableStateOf(ModelTab.Recent) }
    var library by rememberSaveable { mutableStateOf<String?>(null) }
    var recentFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf<AssignTarget?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var working by remember { mutableStateOf(false) }
    var busyClass by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf(false) }
    var trainingAsked by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deletingClass by remember { mutableStateOf<String?>(null) }
    var deletingModel by remember { mutableStateOf(false) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val m = current
    val isAdmin = conn?.isAdmin == true
    val state = conn?.modelStates?.get(route.model)
    val training = state == "training"
    val reclassifies = Format.versionAtLeast(conn?.version, 0, 18)
    val threshold = m?.threshold ?: 0.8

    /** Recent images per object (object models keep up to 16 tries of one object), else one by one. */
    val groups = if (m?.isState != false) attempts.map { listOf(it) } else ClassificationAttempt.grouped(attempts)
    fun isUnsure(a: ClassificationAttempt) = a.score < threshold
    fun matches(group: List<ClassificationAttempt>, filter: String?): Boolean {
        val best = group.firstOrNull() ?: return true
        return when (filter) { null -> true; UNSURE -> isUnsure(best); else -> best.label == filter }
    }
    val shownGroups = groups.filter { matches(it, recentFilter) }

    suspend fun load() {
        val cl = client ?: return
        if (current == null) {
            runCatching { cl.rawConfig() }.getOrNull()?.let { cfg ->
                current = ClassificationModel.all(cfg, route.serverId).firstOrNull { it.name == route.model }
            }
        }
        coroutineScope {
            val set = async { runCatching { cl.classificationDataset(route.model) }.getOrNull() }
            val recent = async { runCatching { cl.classificationAttempts(route.model) }.getOrNull() }
            dataset = set.await() ?: ClassificationDataset()
            attempts = recent.await() ?: emptyList()
        }
        if (tab == ModelTab.Training && (library == null || dataset.categories[library!!] == null)) library = dataset.classNames.firstOrNull()
        loaded = true
    }

    suspend fun reloadModel() {
        val cl = client ?: return
        runCatching { cl.rawConfig() }.getOrNull()?.let { cfg ->
            val all = ClassificationModel.all(cfg, route.serverId)
            ClassificationStore.models[route.serverId] = all
            all.firstOrNull { it.name == route.model }?.let { current = it }
        }
        load()
    }

    LaunchedEffect(Unit) {
        conn?.requestModelStates()
        load()
    }

    // Training's end comes over the websocket as the model's state.
    var lastState by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        val old = lastState
        lastState = state
        if (old == state || !(old == "training" || trainingAsked)) return@LaunchedEffect
        if (state == "complete" || state == "downloaded") {
            trainingAsked = false
            notice = L(R.string.classification_model_trained)
            // Frigate writes the training's details just after it reports the model done.
            repeat(6) {
                val before = dataset.lastTrained
                load()
                if (dataset.hasTrained && dataset.lastTrained != before && !dataset.changed) return@LaunchedEffect
                delay(1000)
            }
        } else if (state == "failed") {
            trainingAsked = false
            errorMessage = L(R.string.classification_training_failed_message)
        }
    }

    fun show(new: ModelTab) {
        if (new == tab) return
        tab = new
        selection = emptySet()
        library = if (new == ModelTab.Training) dataset.classNames.firstOrNull() else null
    }

    val canTrain = !training && !trainingAsked && (dataset.changed || state == "failed") && dataset.categories.size >= 2

    fun train() {
        val cl = client ?: return
        scope.launch {
            working = true
            trainingAsked = true
            notice = null
            try {
                cl.trainClassification(route.model)
                conn.modelStates[route.model] = "training"
            } catch (e: Exception) {
                trainingAsked = false
                errorMessage = e.message
            }
            working = false
        }
    }

    /** The classes the selection can go to: every class but the one it is in. Moving labelled images needs Frigate 0.18. */
    val moveTargets: List<String> = if (!isAdmin || !(library == null || reclassifies)) emptyList() else dataset.classNames.filter { it != library }

    fun moveSelection(category: String) {
        val cl = client ?: return
        if (selection.isEmpty()) return
        scope.launch {
            working = true
            moving = true
            var failed: Exception? = null
            for (file in selection.sorted()) {
                try {
                    val from = library
                    if (from != null) cl.reclassify(route.model, file, from, category) else cl.categorize(route.model, file, category)
                } catch (e: FrigateException.Http) {
                    // Already moved or pruned by Frigate.
                    if (e.code != 404) failed = e
                } catch (e: Exception) { failed = e }
            }
            failed?.let { errorMessage = it.message }
            val count = selection.size
            selection = emptySet()
            selecting = false
            val shown = if (category == "none") L(R.string.classification_none) else category
            notice = Lq(R.plurals.classification_n_put_in, count, count, shown)
            load()
            working = false
            moving = false
        }
    }

    fun selectAll() {
        selection = library?.let { (dataset.categories[it] ?: emptyList()).toSet() } ?: shownGroups.flatMap { g -> g.map { it.file } }.toSet()
    }

    fun deleteSelection() {
        val cl = client ?: return
        if (selection.isEmpty()) return
        scope.launch {
            working = true
            try {
                val cls = library
                if (cls != null) cl.deleteDatasetImages(route.model, cls, selection.toList()) else cl.deleteAttempts(route.model, selection.toList())
                selection = emptySet()
                selecting = false
                load()
            } catch (e: Exception) { errorMessage = e.message }
            working = false
        }
    }

    fun rename(old: String, raw: String) {
        val name = raw.trim()
        val cl = client ?: return
        if (name.isEmpty() || name == old) return
        if (!ClassificationNames.isValid(name)) { errorMessage = L(R.string.classification_name_rule); return }
        if (dataset.categories[name] != null) { errorMessage = L(R.string.classification_class_taken); return }
        scope.launch {
            busyClass = old
            try {
                cl.renameCategory(route.model, old, name)
                if (library == old) library = name
                load()
            } catch (e: Exception) { errorMessage = e.message }
            busyClass = null
        }
    }

    fun deleteClass(c: String) {
        val cl = client ?: return
        scope.launch {
            busyClass = c
            try {
                cl.deleteDatasetImages(route.model, c, dataset.categories[c] ?: emptyList())
                if (library == c) library = dataset.classNames.firstOrNull { it != c }
                load()
            } catch (e: Exception) { errorMessage = e.message }
            busyClass = null
        }
    }

    fun deleteModel() {
        val cl = client ?: return
        scope.launch {
            working = true
            try {
                cl.deleteClassificationModel(route.model)
                ClassificationStore.deleted["${route.serverId}/${route.model}"] = true
                nav.popBackStack()
            } catch (e: Exception) { errorMessage = e.message }
            working = false
        }
    }

    val kindLine = when (val k = m?.kind) {
        is ClassificationModel.Kind.Object -> stringResource(R.string.classification_object) + " · " + Format.labelName(k.label) + " · " +
            stringResource(if (k.asAttribute) R.string.classification_attribute_lc else R.string.classification_sub_label_lc)
        is ClassificationModel.Kind.State -> stringResource(R.string.classification_state) + " · " + cameraNames(k.cameras, conn)
        null -> null
    }

    ScreenScaffold(
        route.model,
        subtitle = kindLine?.let { line ->
            if (AppModel.orderedConnections.size > 1) (conn?.server?.name?.let { "$it · " } ?: "") + line else line
        },
        actions = {
            if (isAdmin && loaded) {
                TextButton(onClick = { selecting = !selecting; selection = emptySet() }) {
                    Text(stringResource(if (selecting) R.string.done else R.string.faces_select), maxLines = 1, softWrap = false)
                }
                if (working && !selecting) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { SmallSpinner() }
                } else Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            enabled = m != null, onClick = { menu = false; editing = true })
                        DestructiveMenuItem(stringResource(R.string.classification_delete_model), Icons.Outlined.Delete) { menu = false; deletingModel = true }
                    }
                }
            }
        },
        bottomBar = {
            if (selecting) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SelectionBar(
                    countText = imagesText(selection.size), hasSelection = selection.isNotEmpty(), working = working, deleting = !moving,
                    onSelectAll = { selectAll() }, onDelete = { confirmDeleteSelection = true },
                    moveTargets = moveTargets.map { it to (if (it == "none") L(R.string.classification_none) else it) },
                    onMove = { moveSelection(it) },
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
                    if (!loaded) {
                        full("loading") { LoadingBox() }
                        return@LazyVerticalGrid
                    }
                    full("train") {
                        TrainCard(
                            training = training, state = state, dataset = dataset, isAdmin = isAdmin, canTrain = canTrain,
                            busy = training || (working && trainingAsked), onTrain = { train() },
                        )
                    }
                    full("tabs") {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            ModelTab.entries.forEachIndexed { i, t ->
                                SegmentedButton(selected = tab == t, onClick = { show(t) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                                    Text(stringResource(if (t == ModelTab.Recent) R.string.classification_recent else R.string.classification_training_images),
                                        maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                    full("chips") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (tab == ModelTab.Recent) {
                                item { CountChip(stringResource(R.string.faces_all), groups.size, recentFilter == null, { recentFilter = null; selection = emptySet() }) }
                                // The classes the recent images got: the model's classes first, then any other result.
                                val seen = groups.mapNotNull { it.firstOrNull()?.label }
                                val labels = (dataset.classNames.filter { it in seen } + seen.distinct().filter { it !in dataset.classNames }).toMutableList()
                                recentFilter?.let { f -> if (f != UNSURE && f !in labels) labels.add(f) }
                                items(labels) { c ->
                                    CountChip(if (c == "unknown") stringResource(R.string.faces_unknown) else className(c),
                                        groups.count { matches(it, c) }, recentFilter == c, { recentFilter = c; selection = emptySet() })
                                }
                                val unsure = groups.count { matches(it, UNSURE) }
                                if (unsure > 0 || recentFilter == UNSURE) item {
                                    CountChip(stringResource(R.string.classification_unsure), unsure, recentFilter == UNSURE,
                                        { recentFilter = UNSURE; selection = emptySet() })
                                }
                            } else {
                                items(dataset.classNames) { c ->
                                    CountChip(
                                        className(c), dataset.categories[c]?.size ?: 0, library == c, { library = c; selection = emptySet() },
                                        busy = busyClass == c,
                                        menu = if (isAdmin && c != "none") { close ->
                                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) },
                                                leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { close(); renaming = c })
                                            if (dataset.categories.size > 2) {
                                                DestructiveMenuItem(stringResource(R.string.delete), Icons.Outlined.Delete) { close(); deletingClass = c }
                                            }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                    notice?.let { n -> full("notice") { NoticeLine(n) } }
                    val cls = library
                    when {
                        tab == ModelTab.Training && cls == null -> full("empty") { EmptyText(stringResource(R.string.classification_no_classes)) }
                        cls != null -> {
                            val files = (dataset.categories[cls] ?: emptyList()).sorted()
                            if (files.isEmpty()) full("empty") { EmptyText(stringResource(R.string.classification_empty_class)) }
                            items(files, key = { "d/$cls/$it" }) { file ->
                                val picked = file in selection
                                ImageTile(client?.datasetImageUrl(route.model, cls, file), selecting, picked, onClick = {
                                    if (selecting) selection = if (picked) selection - file else selection + file
                                    else assigning = AssignTarget(listOf(file), cls)
                                })
                            }
                        }
                        groups.isEmpty() -> full("empty") {
                            EmptyText(stringResource(if (m?.isState == true) R.string.classification_recent_empty_state else R.string.classification_recent_empty_object))
                        }
                        shownGroups.isEmpty() -> full("empty") { EmptyText(stringResource(R.string.faces_no_recent_result)) }
                        else -> items(shownGroups, key = { "r/" + it.first().file }) { group ->
                            val best = group.first()
                            val files = group.map { it.file }
                            val picked = files.all { it in selection }
                            ImageTile(
                                client?.attemptImageUrl(route.model, best.file), selecting, picked,
                                onClick = {
                                    if (selecting) selection = if (picked) selection - files.toSet() else selection + files
                                    else assigning = AssignTarget(files, null)
                                },
                                caption = if (best.label == "unknown") "?" else className(best.label), score = best.score,
                                scoreColor = if (best.score >= threshold) Palette.status.online else Palette.status.destructive,
                                count = group.size,
                            )
                        }
                    }
                }
            }
        }
    }

    assigning?.let { target ->
        ClassificationAssignSheet(
            model = route.model, serverId = route.serverId, target = target, classes = dataset.classNames, reclassifies = reclassifies,
            isAdmin = isAdmin, onDismiss = { assigning = null },
            onDone = { assigning = null; scope.launch { load() } },
        )
    }
    if (editing && m != null) ClassificationEditSheet(m, onDismiss = { editing = false }, onDone = { scope.launch { reloadModel() } })
    renaming?.let { old ->
        TextPromptDialog(
            title = stringResource(R.string.classification_rename_class), initial = old, confirmText = stringResource(R.string.rename),
            label = stringResource(R.string.faces_name), onConfirm = { rename(old, it) }, onDismiss = { renaming = null },
        )
    }
    deletingClass?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.classification_delete_q, className(c)), message = stringResource(R.string.classification_delete_class_message),
            confirmText = stringResource(R.string.delete), onConfirm = { deleteClass(c) }, onDismiss = { deletingClass = null },
        )
    }
    if (confirmDeleteSelection) ConfirmDialog(
        title = pluralStringResource(R.plurals.classification_delete_n_images_q, selection.size, selection.size),
        message = stringResource(if (library == null) R.string.classification_delete_recent_message else R.string.classification_delete_from_class_message),
        confirmText = stringResource(R.string.delete), onConfirm = { deleteSelection() }, onDismiss = { confirmDeleteSelection = false },
    )
    if (deletingModel) ConfirmDialog(
        title = stringResource(R.string.classification_delete_q, route.model), message = stringResource(R.string.classification_delete_model_message),
        confirmText = stringResource(R.string.delete), onConfirm = { deleteModel() }, onDismiss = { deletingModel = false },
    )
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

@Composable
private fun TrainCard(
    training: Boolean,
    state: String?,
    dataset: ClassificationDataset,
    isAdmin: Boolean,
    canTrain: Boolean,
    busy: Boolean,
    onTrain: () -> Unit,
) {
    val title = when {
        training -> stringResource(R.string.classification_training)
        state == "failed" -> stringResource(R.string.classification_training_failed)
        !dataset.hasTrained -> stringResource(R.string.classification_not_trained)
        // After a move Frigate's record says "trained now, 0 images": its date says nothing then.
        dataset.lastTrainedImages == 0 -> stringResource(R.string.classification_train_again)
        dataset.lastTrained != null -> stringResource(R.string.classification_trained_ago, Format.relative(dataset.lastTrained).lowercase())
        else -> stringResource(R.string.classification_trained)
    }
    val detail = when {
        training -> stringResource(R.string.classification_training_detail)
        dataset.categories.size < 2 -> stringResource(R.string.classification_two_classes_first)
        // Moving or renaming makes Frigate count every image as new; then say the classes changed.
        dataset.newImages > 0 && (dataset.newImages < dataset.totalImages || !dataset.hasTrained) ->
            pluralStringResource(R.plurals.classification_n_new_images, dataset.newImages, dataset.newImages)
        dataset.changed -> stringResource(R.string.classification_classes_changed)
        else -> stringResource(R.string.classification_up_to_date)
    }
    SectionCard {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isAdmin) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onTrain, enabled = canTrain, shape = RoundedCornerShape(10.dp)) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(if (training) R.string.classification_training_ellipsis else R.string.classification_train_model),
                        maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// MARK: - Put images in a class

@Composable
private fun ClassificationAssignSheet(
    model: String,
    serverId: String,
    target: AssignTarget,
    classes: List<String>,
    reclassifies: Boolean,
    isAdmin: Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val client = AppModel.connection(serverId)?.client
    var picked by remember(target) { mutableStateOf(target.files.toSet()) }
    var working by remember { mutableStateOf<String?>(null) }
    var addingClass by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val attempts = target.files.mapNotNull { ClassificationAttempt.from(it) }
    val targets = classes.filter { it != target.category }

    fun url(file: String): String? =
        client?.let { c -> target.category?.let { c.datasetImageUrl(model, it, file) } ?: c.attemptImageUrl(model, file) }

    suspend fun assign(category: String) {
        val cl = client ?: return
        for (file in target.files.filter { it in picked }) {
            try {
                val from = target.category
                if (from != null) cl.reclassify(model, file, from, category) else cl.categorize(model, file, category)
            } catch (e: FrigateException.Http) {
                // Already moved (a second tap, or Frigate pruned it): nothing left to do for this one.
                if (e.code != 404) throw e
            }
        }
        // The tries not ticked are of no use once the object is classified.
        if (target.category == null) {
            val rest = target.files.filter { it !in picked }
            if (rest.isNotEmpty()) runCatching { cl.deleteAttempts(model, rest) }
        }
        onDone()
    }

    fun run(key: String, action: suspend () -> Unit) {
        scope.launch {
            working = key
            try { action() } catch (e: Exception) { errorMessage = e.message }
            working = null
        }
    }

    LibrarySheet(
        title = stringResource(if (target.category == null) R.string.classification_put_in_a_class else R.string.classification_image),
        onDismiss = onDismiss,
    ) {
        if (target.files.size == 1) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 280.dp)) { ImageTile(url(target.files.first()), selecting = false, picked = false, onClick = {}) }
            }
        } else {
            Text(stringResource(R.string.classification_tries_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AdaptiveGrid(target.files, minWidth = 90.dp) { file ->
                val on = file in picked
                ImageTile(url(file), selecting = true, picked = on, onClick = { picked = if (on) picked - file else picked + file })
            }
        }
        val best = attempts.maxByOrNull { it.score }
        if (best != null && target.category == null) {
            Text(
                if (best.label == "unknown" || best.score == 0.0) stringResource(R.string.classification_could_not_tell)
                else stringResource(R.string.faces_frigate_thought, className(best.label), percent(best.score)),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (target.category != null) {
            Text(stringResource(R.string.classification_in_class, className(target.category)), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isAdmin) {
            if (target.category == null || reclassifies) {
                SectionLabel(stringResource(if (target.category == null) R.string.classification_belongs_to else R.string.classification_move_to))
                AdaptiveGrid(targets + "\u0000new", minWidth = 130.dp, spacing = 8.dp) { c ->
                    if (c == "\u0000new") {
                        SheetActionButton(stringResource(R.string.classification_new_class), busy = working == c, enabled = working == null,
                            onClick = { addingClass = true }, icon = Icons.Outlined.Add, tint = MaterialTheme.colorScheme.primary)
                    } else {
                        SheetActionButton(className(c), busy = working == c, enabled = working == null && picked.isNotEmpty(),
                            onClick = { run(c) { assign(c) } })
                    }
                }
            }
            SheetActionButton(
                if (target.files.size > 1 && picked.size > 1) pluralStringResource(R.plurals.classification_delete_n_images, picked.size, picked.size)
                else stringResource(R.string.classification_delete_image),
                busy = working == "\u0000delete", enabled = working == null && picked.isNotEmpty(), icon = Icons.Outlined.Delete,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp),
                onClick = {
                    run("\u0000delete") {
                        val cl = client ?: return@run
                        val files = target.files.filter { it in picked }
                        val cls = target.category
                        if (cls != null) cl.deleteDatasetImages(model, cls, files) else cl.deleteAttempts(model, files)
                        onDone()
                    }
                },
            )
        }
    }

    if (addingClass) TextPromptDialog(
        title = stringResource(R.string.classification_new_class), initial = "", confirmText = stringResource(R.string.add),
        label = stringResource(R.string.faces_name), onDismiss = { addingClass = false },
        onConfirm = { raw ->
            val name = raw.trim()
            when {
                !ClassificationNames.isValid(name) -> errorMessage = L(R.string.classification_name_rule)
                classes.any { it.equals(name, ignoreCase = true) } || name.lowercase() == "none" -> errorMessage = L(R.string.classification_class_taken)
                else -> run("\u0000new") { assign(name) }
            }
        },
    )
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

// MARK: - Edit a model

@Composable
internal fun ClassificationEditSheet(initial: ClassificationModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val conn = AppModel.connection(initial.serverId)
    val newer = Format.versionAtLeast(conn?.version, 0, 18)
    val kind = initial.kind
    var enabled by remember { mutableStateOf(initial.enabled) }
    var label by remember { mutableStateOf((kind as? ClassificationModel.Kind.Object)?.label ?: "") }
    var asAttribute by remember { mutableStateOf((kind as? ClassificationModel.Kind.Object)?.asAttribute ?: false) }
    var motion by remember { mutableStateOf((kind as? ClassificationModel.Kind.State)?.motion ?: false) }
    var interval by remember { mutableStateOf((kind as? ClassificationModel.Kind.State)?.interval ?: 0) }
    var saveAttempts by remember { mutableStateOf(initial.saveAttempts ?: if (initial.isState) 100 else 200) }
    var labels by remember { mutableStateOf(listOfNotNull(label.takeIf { it.isNotEmpty() })) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val cfg = runCatching { conn?.client?.rawConfig() }.getOrNull() ?: return@LaunchedEffect
        val list = ClassificationNames.trackedLabels(cfg).toMutableList()
        if (label.isNotEmpty() && label !in list) list.add(label)
        labels = list
    }

    fun save() {
        val client = conn?.client ?: return
        val node = mutableMapOf<String, Any?>()
        when (kind) {
            is ClassificationModel.Kind.Object -> {
                node["name"] = initial.name
                node["threshold"] = initial.threshold
                node["object_config"] = mapOf("objects" to listOf(label), "classification_type" to if (asAttribute) "attribute" else "sub_label")
            }
            is ClassificationModel.Kind.State ->
                if (newer) node["state_config"] = mapOf("motion" to motion, "interval" to if (interval > 0) interval else null)
        }
        if (newer) { node["enabled"] = enabled; node["save_attempts"] = saveAttempts }
        scope.launch {
            saving = true
            try {
                client.setClassificationModel(initial.name, node)
                onDone()
                onDismiss()
            } catch (e: Exception) { errorMessage = e.message }
            saving = false
        }
    }

    LibrarySheet(
        title = initial.name, onDismiss = onDismiss, closeText = stringResource(R.string.cancel), dismissible = !saving,
        confirm = {
            Button(onClick = { save() }, enabled = !saving, shape = RoundedCornerShape(10.dp),
                contentPadding = ButtonDefaults.ContentPadding) {
                if (saving) SmallSpinner(size = 16.dp) else Text(stringResource(R.string.save), maxLines = 1)
            }
        },
    ) {
        if (newer) SectionCard { SwitchRow(stringResource(R.string.classification_model_on), enabled) { enabled = it } }
        when (kind) {
            is ClassificationModel.Kind.Object -> {
                SectionLabel(stringResource(R.string.classification_object), Modifier.padding(top = 8.dp))
                SectionCard {
                    LabelPickerRow(label, labels) { label = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SubLabelOrAttribute(asAttribute) { asAttribute = it }
                }
                Hint(stringResource(R.string.classification_sub_label_hint))
            }
            is ClassificationModel.Kind.State -> if (newer) {
                SectionLabel(stringResource(R.string.classification_when), Modifier.padding(top = 8.dp))
                SectionCard {
                    SwitchRow(stringResource(R.string.classification_on_motion), motion) { motion = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.classification_every), Modifier.weight(1f), fontSize = 15.sp)
                        NumberStepper(interval, "s", 10, 0, 3600) { interval = it }
                    }
                }
                Hint(stringResource(R.string.classification_only_on_motion))
            }
        }
        if (newer) {
            SectionLabel(stringResource(R.string.classification_recent_images), Modifier.padding(top = 8.dp))
            SectionCard {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.classification_keep), Modifier.weight(1f), fontSize = 15.sp)
                    NumberStepper(saveAttempts, "", 10, 0, 1000) { saveAttempts = it }
                }
            }
            Hint(stringResource(R.string.classification_keep_hint))
        }
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

@Composable
internal fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An on/off setting: label on the left, switch on the right. */
@Composable
internal fun SwitchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onChange(!on) }).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** "Object" with the chosen label; tapping opens the list of tracked labels. */
@Composable
internal fun LabelPickerRow(label: String, labels: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { open = true }).padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.classification_object), Modifier.weight(1f), fontSize = 15.sp)
            Text(if (label.isEmpty()) "" else Format.labelName(label), fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            for (l in labels) DropdownMenuItem(
                text = { Text(Format.labelName(l)) },
                trailingIcon = { if (l == label) Icon(Icons.Outlined.Check, null) },
                onClick = { open = false; onPick(l) },
            )
        }
    }
}

/** Sub label or attribute, as a segmented choice. */
@Composable
internal fun SubLabelOrAttribute(asAttribute: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
        listOf(false, true).forEachIndexed { i, attr ->
            SegmentedButton(selected = asAttribute == attr, onClick = { onChange(attr) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                Text(stringResource(if (attr) R.string.classification_attribute else R.string.classification_sub_label), maxLines = 1, softWrap = false)
            }
        }
    }
}
