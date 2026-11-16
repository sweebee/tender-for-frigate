package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.CameraConfig
import nl.wiebenieuwenhuis.tender.api.ExportItem
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateExportCase
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import java.util.Calendar
import java.util.TimeZone

/**
 * A clip to keep as an export in Frigate, waiting for its name and case. `cameras`: the server's
 * cameras; when given, the span can be changed and, on Frigate 0.18 and later, several cameras
 * exported at once, as in Frigate's Export dialog.
 */
data class SaveToFrigateRequest(
    val client: FrigateClient,
    val camera: String,
    val start: Double,
    val end: Double,
    val name: String,
    val cameras: List<CameraConfig> = emptyList(),
)

private const val NEW_CASE = "\u0001new"
private const val MAX_ITEMS = 50

private class CamActivity(var objects: Int = 0, val spans: MutableList<Pair<Double, Double>> = mutableListOf())

/** Asks for the export's name and, on servers with cases, which case to file it in. From playback also the span and cameras. */
@Composable
fun SaveToFrigateSheet(request: SaveToFrigateRequest, exporter: ClipExporter, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val client = request.client
    var name by remember { mutableStateOf(request.name) }
    var cases by remember { mutableStateOf<List<FrigateExportCase>>(emptyList()) }
    var loadingCases by remember { mutableStateOf(true) }
    /** null: no case; NEW_CASE: a new case named `newCase`. */
    var choice by remember { mutableStateOf<String?>(null) }
    var newCase by remember { mutableStateOf("") }
    var start by remember { mutableDoubleStateOf(request.start) }
    var end by remember { mutableDoubleStateOf(request.end) }
    var multi by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf(request.camera to true) }
    /** Picked by hand: no longer follows what was active in the span. */
    var touched by remember { mutableStateOf(false) }
    var activity by remember { mutableStateOf<Map<String, CamActivity>>(emptyMap()) }
    var loadingActivity by remember { mutableStateOf(false) }

    val adjustable = request.cameras.isNotEmpty()
    val offersMulti = adjustable && client.servesCases && request.cameras.size > 1
    val spanValid = end - start >= 1
    val chosen = if (multi) request.cameras.filter { selected[it.name] == true } else emptyList()
    val canSave = spanValid && (choice != NEW_CASE || newCase.isNotBlank()) &&
        (if (multi) chosen.isNotEmpty() && chosen.size <= MAX_ITEMS else name.isNotBlank())

    LaunchedEffect(Unit) {
        if (!client.servesCases) return@LaunchedEffect
        cases = client.exportCases()
        loadingCases = false
    }
    LaunchedEffect(multi, start, end) {
        if (!multi || !spanValid) return@LaunchedEffect
        delay(300)
        loadingActivity = true
        try {
            val items = runCatching { client.reviews(after = start - 3600, before = end, limit = 1000) }.getOrNull() ?: return@LaunchedEffect
            val out = HashMap<String, CamActivity>()
            for (cam in request.cameras) out[cam.name] = CamActivity()
            for (r in items) {
                val rs = r.startTime
                val re = r.endTime ?: nowSeconds()
                if (re <= start || rs >= end) continue
                val a = out.getOrPut(r.camera) { CamActivity() }
                a.objects += r.detections.size.coerceAtLeast(1)
                a.spans.add(rs to re)
            }
            activity = out
            if (!touched) {
                selected.clear()
                out.filter { it.value.objects > 0 }.keys.forEach { selected[it] = true }
                selected[request.camera] = true
            }
        } finally {
            loadingActivity = false
        }
    }
    LaunchedEffect(multi) {
        // A base name in place of the one camera's name, and back.
        if (multi && name == request.name) name = ""
        if (!multi && name.isEmpty()) name = request.name
    }

    fun save() {
        val trimmed = name.trim()
        val c = choice
        val caseName = newCase.trim()
        val s = if (adjustable) start else request.start
        val e = if (adjustable) minOf(end, nowSeconds()) else request.end
        val items = if (multi) chosen.map { cam ->
            ExportItem(cam.name, s, e, if (trimmed.isEmpty()) ClipExporter.exportName(cam.name, cam.displayName, s) else "$trimmed · ${cam.displayName}")
        } else null
        onDismiss()
        AppModel.scope.launch {
            if (items != null) {
                exporter.saveToFrigate(client, items, if (c == NEW_CASE) null else c, if (c == NEW_CASE) caseName else null)
                return@launch
            }
            var caseId = c
            if (c == NEW_CASE) {
                caseId = try {
                    client.createExportCase(caseName).id
                } catch (ex: Exception) {
                    exporter.state = ClipExporter.State.Failed(ex.message ?: L(R.string.playback_something_wrong))
                    exporter.settle()
                    return@launch
                }
            }
            exporter.saveToFrigate(client, request.camera, s, e, trimmed, caseId)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), maxLines = 1) }
                Text(
                    stringResource(if (adjustable) R.string.playback_export_recordings else R.string.playback_save_to_frigate),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(onClick = ::save, enabled = canSave) {
                    Text(if (multi) stringResource(R.string.playback_export_n, chosen.size) else stringResource(R.string.save), maxLines = 1)
                }
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (offersMulti) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        SegmentedButton(!multi, { multi = false }, SegmentedButtonDefaults.itemShape(0, 2)) {
                            Text(stringResource(R.string.playback_this_camera), maxLines = 1)
                        }
                        SegmentedButton(multi, { multi = true }, SegmentedButtonDefaults.itemShape(1, 2)) {
                            Text(stringResource(R.string.playback_several_cameras), maxLines = 1)
                        }
                    }
                }
                if (adjustable) {
                    Spacer(Modifier.height(4.dp))
                    SectionLabel(stringResource(R.string.playback_time_span))
                    Group {
                        DateTimeRow(stringResource(R.string.playback_from), start, max = nowSeconds()) { start = it }
                        DateTimeRow(stringResource(R.string.playback_to), end, min = start, max = nowSeconds()) { end = it }
                    }
                    Footer(if (spanValid) Format.shortDuration(end - start) else stringResource(R.string.playback_end_after_start))
                }
                if (multi) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.playback_cameras), Modifier.weight(1f))
                        if (loadingActivity) { SmallSpinner(size = 12.dp); Spacer(Modifier.width(6.dp)) }
                        Text(stringResource(R.string.playback_n_selected, selected.count { it.value }, request.cameras.size),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Group {
                        for (cam in request.cameras) {
                            CameraRow(cam, selected[cam.name] == true, activity[cam.name], start, end) {
                                touched = true
                                if (selected[cam.name] == true) selected.remove(cam.name) else selected[cam.name] = true
                            }
                        }
                    }
                    Footer(stringResource(R.string.playback_cameras_footer))
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(if (multi) R.string.playback_optional_base_name else R.string.playback_name)) },
                )
                if (multi) Footer(stringResource(R.string.playback_base_name_footer))
                if (client.servesCases) {
                    Spacer(Modifier.height(4.dp))
                    SectionLabel(stringResource(R.string.playback_case))
                    Group {
                        if (loadingCases && cases.isEmpty()) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { SmallSpinner() }
                        CaseOption(Icons.Outlined.Inbox, stringResource(R.string.playback_no_case), choice == null) { choice = null }
                        for (c in cases) CaseOption(Icons.Outlined.Folder, c.name, choice == c.id) { choice = c.id }
                        CaseOption(Icons.Outlined.CreateNewFolder, stringResource(R.string.playback_new_case), choice == NEW_CASE) { choice = NEW_CASE }
                    }
                    if (choice == NEW_CASE) {
                        OutlinedTextField(newCase, { newCase = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.playback_case_name)) })
                    }
                    Footer(stringResource(R.string.playback_case_footer))
                }
            }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(vertical = 4.dp)) {
        content()
    }
}

@Composable
private fun Footer(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CaseOption(icon: ImageVector, title: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (on) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CameraRow(cam: CameraConfig, on: Boolean, a: CamActivity?, start: Double, end: Double, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(cam.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a != null) Text(pluralStringResource(R.plurals.playback_tracked_objects, a.objects, a.objects), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        // When something was going on in the span, as in Frigate's dialog.
        BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))) {
            val w = maxWidth
            for ((s, e) in a?.spans ?: emptyList()) {
                val len = (end - start).coerceAtLeast(1.0)
                val x0 = ((s - start) / len).coerceIn(0.0, 1.0)
                val x1 = ((e - start) / len).coerceIn(0.0, 1.0)
                Box(Modifier.offset(x = w * x0.toFloat()).width(maxOf(5.dp, w * (x1 - x0).toFloat())).height(5.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
            }
        }
    }
}

/** A date and time in a row; a tap asks for the date, then the time. */
@Composable
private fun DateTimeRow(label: String, value: Double, min: Double? = null, max: Double, onChange: (Double) -> Unit) {
    var pickingDate by remember { mutableStateOf(false) }
    var pickedDay by remember { mutableStateOf<Calendar?>(null) }
    Row(
        Modifier.fillMaxWidth().clickable { pickingDate = true }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Text(Format.dateTime(value.toDate()), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
    if (pickingDate) {
        // The date picker works in UTC midnights.
        val local = Calendar.getInstance().apply { time = value.toDate() }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }
        val maxUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            val l = Calendar.getInstance().apply { time = max.toDate() }
            clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        val minUtc = min?.let { m ->
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                val l = Calendar.getInstance().apply { time = m.toDate() }
                clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = utc.timeInMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= maxUtc && (minUtc == null || utcTimeMillis >= minUtc)
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val sel = state.selectedDateMillis
                    pickingDate = false
                    if (sel != null) {
                        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                        pickedDay = Calendar.getInstance().apply {
                            time = value.toDate()
                            set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                        }
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
    pickedDay?.let { day ->
        val time = rememberTimePickerState(day.get(Calendar.HOUR_OF_DAY), day.get(Calendar.MINUTE), is24Hour = !Format.uses12Hour)
        AlertDialog(
            onDismissRequest = { pickedDay = null },
            confirmButton = {
                TextButton(onClick = {
                    day.set(Calendar.HOUR_OF_DAY, time.hour); day.set(Calendar.MINUTE, time.minute); day.set(Calendar.SECOND, 0)
                    var t = day.timeInMillis / 1000.0
                    t = t.coerceAtMost(max)
                    if (min != null) t = t.coerceAtLeast(min)
                    pickedDay = null
                    onChange(t)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickedDay = null }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(time) },
        )
    }
}
