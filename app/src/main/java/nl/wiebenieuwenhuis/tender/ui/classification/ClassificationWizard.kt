package nl.wiebenieuwenhuis.tender.ui.classification

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.ClassificationModel
import nl.wiebenieuwenhuis.tender.api.ClassificationNames
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.attemptImageUrl
import nl.wiebenieuwenhuis.tender.api.categorize
import nl.wiebenieuwenhuis.tender.api.createCategory
import nl.wiebenieuwenhuis.tender.api.deleteAttempts
import nl.wiebenieuwenhuis.tender.api.deleteClassificationFiles
import nl.wiebenieuwenhuis.tender.api.generateObjectExamples
import nl.wiebenieuwenhuis.tender.api.generateStateExamples
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.rawClassificationTrainFiles
import nl.wiebenieuwenhuis.tender.api.setClassificationModel
import nl.wiebenieuwenhuis.tender.api.trainClassification
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.ReadableWidth
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.isWideLayout
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import kotlin.math.roundToInt

/** A square area on a camera picture: its centre (0…1) and its side as a share of the picture's short side. */
data class CropArea(val x: Double = 0.5, val y: Double = 0.5, val size: Double = 0.3) {
    /** [x1, y1, x2, y2] in 0…1 of the picture, square in pixels, as Frigate keeps it. */
    fun normalized(aspect: Double): List<Double> {
        val w = if (aspect >= 1) size / aspect else size
        val h = if (aspect >= 1) size else size * aspect
        val x1 = (x - w / 2).coerceAtLeast(0.0).coerceAtMost(1 - w)
        val y1 = (y - h / 2).coerceAtLeast(0.0).coerceAtMost(1 - h)
        return listOf(x1, y1, x1 + w, y1 + h).map { (it * 1000).roundToInt() / 1000.0 }
    }
}

private enum class WizardStep { Define, Area, Examples, Done }

/**
 * A new classification model in Frigate's three steps: name and classes; for a state model the
 * area on one or more cameras; then examples Frigate picks from past events, sorted per class.
 * The model is written and trained only at the end.
 *
 * Shown over the models list, as the iOS app's sheet: a full-height bottom sheet on phones, a
 * large centred dialog on wide windows. `onClose(made)`: the wizard is done (`made`: a model was
 * written) or cancelled; examples made for a cancelled model are removed first.
 */
@Composable
fun ClassificationWizard(serverId: String, startAsState: Boolean, onClose: (made: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val conn = AppModel.connection(serverId)
    val client = conn?.client
    var step by rememberSaveable { mutableStateOf(WizardStep.Define) }
    var name by rememberSaveable { mutableStateOf("") }
    var isState by rememberSaveable { mutableStateOf(startAsState) }
    var label by rememberSaveable { mutableStateOf("") }
    var asAttribute by rememberSaveable { mutableStateOf(false) }
    val classes = remember { mutableStateListOf("", "") }
    var labels by remember { mutableStateOf<List<String>>(emptyList()) }
    var existing by remember { mutableStateOf<Set<String>>(emptySet()) }
    val cameras = remember { mutableStateMapOf<String, CropArea>() }
    var examples by remember { mutableStateOf<List<String>>(emptyList()) }
    var generating by remember { mutableStateOf(false) }
    var generated by remember { mutableStateOf(false) }
    var classIndex by remember { mutableStateOf(0) }
    /** Example file → class. */
    val chosen = remember { mutableStateMapOf<String, String>() }
    var finishing by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var trained by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val cleanName = name.trim()
    val cleanClasses = classes.map { it.trim() }.filter { it.isNotEmpty() }
    val stateCameras = (conn?.config?.cameras?.values?.toList() ?: emptyList())
        .filter { it.enabledInConfig != false }
        .sortedWith(compareBy({ it.uiOrder ?: 0 }, { it.name }))
    fun aspect(camera: String): Double = (conn?.config?.cameras?.get(camera)?.aspectRatio ?: (16f / 9f)).toDouble()

    LaunchedEffect(Unit) {
        val cfg = runCatching { client?.rawConfig() }.getOrNull() ?: return@LaunchedEffect
        labels = ClassificationNames.trackedLabels(cfg)
        if (label.isEmpty()) label = if ("person" in labels) "person" else labels.firstOrNull() ?: ""
        existing = ClassificationModel.all(cfg, serverId).map { it.name }.toSet() +
            (cfg["classification"]["custom"].obj()?.keys ?: emptySet())
    }

    /** Closing needs no work first: nothing to clean up and nothing being written. */
    val closesAtOnce = step == WizardStep.Done || (!finishing && !cancelling && !generated && !generating)

    /** Cancel, Back, a swipe down: done when done; otherwise cancel, removing the examples made. */
    fun close() {
        if (step == WizardStep.Done) { onClose(true); return }
        if (finishing || cancelling) return
        // Examples were made under the model's name: remove them; the config was not written yet.
        if ((generated || generating) && client != null) {
            cancelling = true
            scope.launch {
                runCatching { client.deleteClassificationFiles(cleanName) }
                cancelling = false
                onClose(false)
            }
        } else onClose(false)
    }

    fun defineProblem(): String? {
        val n = cleanName
        if (n.isEmpty() || n.length > 64 || !ClassificationNames.isValid(n.take(50)) || n.all { it.isDigit() }) return L(R.string.classification_wizard_name_rule)
        if (existing.any { it.equals(n, ignoreCase = true) }) return L(R.string.classification_wizard_name_taken)
        if (!isState && label.isEmpty()) return L(R.string.classification_wizard_pick_object)
        val cls = cleanClasses
        if (cls.size < (if (isState) 2 else 1)) return L(if (isState) R.string.classification_wizard_two_states else R.string.classification_wizard_one_class)
        if (cls.map { it.lowercase() }.toSet().size != cls.size) return L(R.string.classification_wizard_unique_classes)
        if (cls.any { it.lowercase() == "none" }) return L(R.string.classification_wizard_none_reserved)
        if (!cls.all { ClassificationNames.isValid(it) }) return L(R.string.classification_name_rule)
        return null
    }

    fun next() {
        defineProblem()?.let { errorMessage = it; return }
        if (isState) {
            if (cameras.isEmpty()) stateCameras.firstOrNull()?.let { cameras[it.name] = CropArea() }
            step = WizardStep.Area
        } else step = WizardStep.Examples
    }

    suspend fun generate() {
        val cl = client ?: return
        generating = true
        try {
            if (isState) cl.generateStateExamples(cleanName, cameras.mapValues { (cam, area) -> area.normalized(aspect(cam)) })
            else cl.generateObjectExamples(cleanName, label)
            examples = cl.rawClassificationTrainFiles(cleanName).sorted()
            generated = true
        } catch (e: Exception) {
            errorMessage = e.message
            step = if (isState) WizardStep.Area else WizardStep.Define
        }
        generating = false
    }

    LaunchedEffect(step) { if (step == WizardStep.Examples && !generated && !generating) generate() }

    fun finish() {
        val cl = client ?: return
        scope.launch {
            finishing = true
            val n = cleanName
            val node = mutableMapOf<String, Any?>("enabled" to true, "name" to n, "threshold" to 0.8)
            if (isState) {
                node["state_config"] = mapOf(
                    "cameras" to cameras.mapValues { (cam, area) -> mapOf("crop" to area.normalized(aspect(cam))) },
                    "motion" to true,
                )
            } else {
                node["object_config"] = mapOf("objects" to listOf(label), "classification_type" to if (asAttribute) "attribute" else "sub_label")
            }
            try {
                cl.setClassificationModel(n, node)
                ClassificationStore.deleted.remove("$serverId/$n")
                val assigned = chosen.toMutableMap()
                if (!isState) for (file in examples) if (assigned[file] == null) assigned[file] = "none"
                for ((file, cls) in assigned) cl.categorize(n, file, cls)
                if (isState) {
                    val rest = examples.filter { assigned[it] == null }
                    if (rest.isNotEmpty()) runCatching { cl.deleteAttempts(n, rest) }
                }
                val withImages = assigned.values.toSet()
                for (cls in cleanClasses) if (cls !in withImages) runCatching { cl.createCategory(n, cls) }
                // As Frigate's wizard: a state model needs examples of every state, an object model of at least one class.
                val canTrain = if (isState) cleanClasses.all { it in withImages } else (withImages - "none").isNotEmpty()
                if (canTrain) {
                    cl.trainClassification(n)
                    conn.modelStates[n] = "training"
                    trained = true
                }
                generated = false
                step = WizardStep.Done
            } catch (e: Exception) { errorMessage = e.message }
            finishing = false
        }
    }

    WizardFrame(closesAtOnce = closesAtOnce, onCloseRequest = { close() }) {
        // Cancel on the left, as every sheet; the Done step closes with its own button.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 88.dp), contentAlignment = Alignment.CenterStart) {
                if (step != WizardStep.Done) TextButton(onClick = { close() }, enabled = !finishing && !cancelling) {
                    if (cancelling) SmallSpinner() else Text(stringResource(R.string.cancel), maxLines = 1)
                }
            }
            Text(stringResource(R.string.classification_new_model), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.width(88.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (step == WizardStep.Done) {
                DoneStep(cleanName, trained) { onClose(true) }
                return@Box
            }
            Column(
                Modifier.widthIn(max = ReadableWidth).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val steps = if (isState) 3 else 2
                val number = when (step) { WizardStep.Define -> 1; WizardStep.Area -> 2; else -> steps }
                Text(stringResource(R.string.classification_wizard_step, number, steps), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (step) {
                    WizardStep.Define -> {
                        OutlinedTextField(
                            name, { name = it }, label = { Text(stringResource(R.string.faces_name)) }, singleLine = true,
                            placeholder = { Text(stringResource(if (isState) R.string.classification_wizard_gate else R.string.classification_wizard_delivery_service)) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            listOf(false, true).forEachIndexed { i, s ->
                                SegmentedButton(selected = isState == s, onClick = { isState = s }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                                    Text(stringResource(if (s) R.string.classification_state else R.string.classification_object), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        Hint(stringResource(if (isState) R.string.classification_wizard_state_explain else R.string.classification_wizard_object_explain))
                        if (!isState) {
                            SectionLabel(stringResource(R.string.classification_object), Modifier.padding(top = 8.dp))
                            SectionCard {
                                LabelPickerRow(label, labels) { label = it }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                SubLabelOrAttribute(asAttribute) { asAttribute = it }
                            }
                            Hint(stringResource(R.string.classification_sub_label_hint))
                        }
                        SectionLabel(stringResource(if (isState) R.string.classification_states else R.string.classification_classes), Modifier.padding(top = 8.dp))
                        SectionCard {
                            classes.forEachIndexed { i, value ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.TextField(
                                        value, { classes[i] = it }, singleLine = true, modifier = Modifier.weight(1f),
                                        placeholder = {
                                            Text(if (isState) stringResource(if (i == 0) R.string.classification_wizard_open else R.string.classification_wizard_closed)
                                            else if (i == 0) "DHL" else "PostNL")
                                        },
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
                                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                        ),
                                    )
                                    if (classes.size > (if (isState) 2 else 1)) IconButton(onClick = { classes.removeAt(i) }) {
                                        Icon(Icons.Outlined.RemoveCircle, stringResource(R.string.remove), tint = Palette.status.destructive)
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            TextButton(onClick = { classes.add("") }, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (isState) R.string.classification_wizard_add_state else R.string.classification_wizard_add_class))
                            }
                        }
                        Hint(stringResource(if (isState) R.string.classification_wizard_states_hint else R.string.classification_wizard_classes_hint))
                        PrimaryButton(stringResource(R.string.classification_wizard_next), Modifier.padding(top = 14.dp)) { next() }
                    }
                    WizardStep.Area -> {
                        Text(stringResource(R.string.classification_wizard_area_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SectionCard {
                            stateCameras.forEachIndexed { i, cam ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                SwitchRow(cam.displayName, cameras.containsKey(cam.name)) { on ->
                                    if (on) cameras[cam.name] = CropArea() else cameras.remove(cam.name)
                                }
                            }
                        }
                        for (cam in stateCameras.filter { cameras.containsKey(it.name) }) {
                            SectionLabel(cam.displayName, Modifier.padding(top = 8.dp))
                            CropEditor(client?.latestImageUrl(cam.name, 500), aspect(cam.name), cameras[cam.name] ?: CropArea()) { cameras[cam.name] = it }
                        }
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton(stringResource(R.string.back), Modifier.weight(1f)) { step = WizardStep.Define }
                            PrimaryButton(stringResource(R.string.classification_wizard_next), Modifier.weight(1f)) {
                                if (cameras.isEmpty()) errorMessage = L(R.string.classification_wizard_pick_camera) else step = WizardStep.Examples
                            }
                        }
                    }
                    WizardStep.Examples -> {
                        val cls = cleanClasses.getOrNull(classIndex)
                        if (generating || !generated) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 200.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.classification_wizard_generating), fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        } else if (examples.isEmpty()) {
                            Text(stringResource(if (isState) R.string.classification_wizard_no_recordings else R.string.classification_wizard_no_events),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PrimaryButton(stringResource(R.string.classification_wizard_without_examples), busy = finishing) { finish() }
                        } else if (cls != null) {
                            Text(stringResource(R.string.classification_wizard_tap_every, cls), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.classification_wizard_class_n_of, classIndex + 1, cleanClasses.size) +
                                if (isState) "" else " · " + stringResource(R.string.classification_wizard_rest_none),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AdaptiveGrid(examples.filter { chosen[it] == null || chosen[it] == cls }, minWidth = 96.dp) { file ->
                                val on = chosen[file] == cls
                                ImageTile(client?.attemptImageUrl(cleanName, file), selecting = true, picked = on,
                                    onClick = { if (on) chosen.remove(file) else chosen[file] = cls })
                            }
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SecondaryButton(stringResource(R.string.back), Modifier.weight(1f), enabled = !finishing) {
                                    if (classIndex > 0) classIndex -= 1 else step = if (isState) WizardStep.Area else WizardStep.Define
                                }
                                val last = classIndex + 1 >= cleanClasses.size
                                PrimaryButton(stringResource(if (last) R.string.classification_wizard_make_model else R.string.classification_wizard_next_class),
                                    Modifier.weight(1f), busy = finishing) {
                                    if (!last) classIndex += 1 else finish()
                                }
                            }
                        }
                    }
                    WizardStep.Done -> {}
                }
            }
        }
    }

    errorMessage?.let {
        MessageDialog(stringResource(if (step == WizardStep.Define || step == WizardStep.Area) R.string.classification_wizard_not_ready else R.string.not_saved), it) {
            errorMessage = null
        }
    }
}

@Composable
private fun DoneStep(name: String, trained: Boolean, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(48.dp), tint = Palette.status.online)
        Text(stringResource(R.string.classification_wizard_made, name), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(stringResource(if (trained) R.string.classification_wizard_training_now else R.string.classification_wizard_put_images),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        PrimaryButton(stringResource(R.string.done), Modifier.widthIn(max = 240.dp)) { onDone() }
    }
}

@Composable
private fun PrimaryButton(title: String, modifier: Modifier = Modifier.fillMaxWidth(), busy: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp)) {
        if (busy) SmallSpinner() else Text(title, maxLines = 1, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp)) {
        Text(title, maxLines = 1, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The wizard's window: a full-height bottom sheet on phones, a large centred dialog on wide
 * windows. A swipe down or Back asks `onCloseRequest`; while closing needs work first (removing
 * examples, writing the model) the sheet stays up until the wizard closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WizardFrame(closesAtOnce: Boolean, onCloseRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val atOnce by rememberUpdatedState(closesAtOnce)
    val request by rememberUpdatedState(onCloseRequest)
    if (isWideLayout()) {
        Dialog(onDismissRequest = { request() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            Surface(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(0.92f).fillMaxHeight(0.9f),
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.fillMaxSize().padding(top = 8.dp)) { content() }
            }
        }
    } else {
        // One lambda for the sheet's life: a new one would make a new sheet state.
        val confirm = remember {
            { v: SheetValue ->
                if (v != SheetValue.Hidden || atOnce) true
                else { request(); false }
            }
        }
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
        ModalBottomSheet(onDismissRequest = { request() }, sheetState = state) {
            Column(Modifier.fillMaxWidth().fillMaxHeight()) { content() }
        }
    }
}

/** The camera picture with a square to drag, and a slider for its size. */
@Composable
fun CropEditor(url: String?, aspect: Double, area: CropArea, onChange: (CropArea) -> Unit) {
    val current by rememberUpdatedState(area)
    val change by rememberUpdatedState(onChange)
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect.toFloat()).clip(RoundedCornerShape(10.dp))) {
            RemoteImage(url, Modifier.matchParentSize())
            Canvas(
                Modifier.matchParentSize().pointerInput(aspect) {
                    var start = current
                    var total = Offset.Zero
                    detectDragGestures(onDragStart = { start = current; total = Offset.Zero }) { pc, drag ->
                        pc.consume()
                        total += drag
                        val moved = start.copy(x = start.x + total.x / size.width, y = start.y + total.y / size.height)
                        val n = moved.normalized(aspect)
                        change(moved.copy(x = (n[0] + n[2]) / 2, y = (n[1] + n[3]) / 2))
                    }
                },
            ) {
                val box = area.normalized(aspect)
                val l = (box[0] * size.width).toFloat(); val t = (box[1] * size.height).toFloat()
                val r = (box[2] * size.width).toFloat(); val b = (box[3] * size.height).toFloat()
                val dim = Color.Black.copy(alpha = 0.45f)
                drawRect(dim, Offset.Zero, Size(size.width, t))
                drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                drawRect(dim, Offset(0f, t), Size(l, b - t))
                drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
                drawRoundRect(accent, Offset(l, t), Size(r - l, b - t),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CropFree, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = area.size.toFloat(), onValueChange = { onChange(area.copy(size = it.toDouble())) }, valueRange = 0.1f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Icon(Icons.Outlined.CropFree, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
