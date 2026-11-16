package nl.wiebenieuwenhuis.tender.ui.classification

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.components.RemoteImage
import nl.wiebenieuwenhuis.tender.ui.components.ServerBadge
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import kotlin.math.roundToInt

// Pieces shared by the Face Library and the Classification screens, which work the same way:
// recent images to put in a class (or person), and the images of each class.

/** "93%". */
fun percent(score: Double): String = "${(score * 100).roundToInt()}%"

/** A filter chip with a count, as Frigate's library pages; long-press opens `menu` when given. */
@Composable
fun CountChip(
    title: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    busy: Boolean = false,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        Surface(
            shape = CircleShape,
            color = if (selected) scheme.primary else scheme.surfaceContainerHigh,
            contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
            border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
            modifier = Modifier.clip(CircleShape).combinedClickable(
                enabled = !busy,
                onClick = onClick,
                onLongClick = if (menu != null) ({ menuOpen = true }) else null,
            ),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp,
                        color = if (selected) scheme.onPrimary else scheme.onSurface)
                    Spacer(Modifier.width(5.dp))
                }
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(5.dp))
                Text(count.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    color = if (selected) scheme.onPrimary.copy(alpha = 0.7f) else scheme.onSurfaceVariant)
            }
        }
        if (menu != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
        }
    }
}

/** A square picture tile: caption with score at the bottom, a count or tick at the top right. */
@Composable
fun ImageTile(
    url: String?,
    selecting: Boolean,
    picked: Boolean,
    onClick: () -> Unit,
    caption: String? = null,
    score: Double? = null,
    scoreColor: Color = Color.White,
    count: Int = 1,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
            .background(scheme.surfaceContainerHigh)
            .border(if (picked) 2.dp else 1.dp, if (picked) scheme.primary else scheme.outlineVariant, shape)
            .combinedClickable(onClick = onClick),
    ) {
        RemoteImage(url, Modifier.matchParentSize())
        if (caption != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(caption, Modifier.weight(1f), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (score != null && score > 0) {
                    Text(percent(score), color = scoreColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        if (selecting) {
            Tick(picked, Modifier.align(Alignment.TopEnd).padding(5.dp))
        } else if (count > 1) {
            Text(count.toString(), Modifier.align(Alignment.TopEnd).padding(5.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The round tick on a picture that can be selected. */
@Composable
fun Tick(on: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(modifier.size(size).shadow(2.dp, CircleShape).background(if (on) Color.White else Color.Black.copy(alpha = 0.25f), CircleShape)
        .border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Icon(Icons.Filled.CheckCircle, null, Modifier.size(size), tint = MaterialTheme.colorScheme.primary)
    }
}

/** A grid inside a scrolling column: as many columns of at least `minWidth` as fit. */
@Composable
fun <T> AdaptiveGrid(items: List<T>, minWidth: Dp, spacing: Dp = 6.dp, content: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minWidth + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (row in items.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (item in row) Box(Modifier.weight(1f)) { content(item) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A button in a sheet's list of classes or names; shows a spinner while its change waits for Frigate. */
@Composable
fun SheetActionButton(
    title: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled && !busy, modifier = modifier.fillMaxWidth().heightIn(min = 40.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (busy) SmallSpinner(size = 16.dp) else if (icon != null) Icon(icon, null, Modifier.size(18.dp))
        if (busy || icon != null) Spacer(Modifier.width(6.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    }
}

/** The bar at the bottom while selecting: count, Select all, an optional "Put in" menu and Delete. */
@Composable
fun SelectionBar(
    countText: String,
    hasSelection: Boolean,
    working: Boolean,
    deleting: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    moveTargets: List<Pair<String, String>> = emptyList(),
    onMove: (String) -> Unit = {},
) {
    var moveOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp).widthIn(max = 720.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(countText, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.classification_select_all), maxLines = 1, softWrap = false) }
            if (moveTargets.isNotEmpty()) {
                Box {
                    Button(onClick = { moveOpen = true }, enabled = hasSelection && !working, shape = RoundedCornerShape(10.dp)) {
                        if (working && !deleting) { SmallSpinner(size = 16.dp); Spacer(Modifier.width(6.dp)) }
                        Text(stringResource(R.string.classification_put_in), maxLines = 1, softWrap = false)
                    }
                    DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
                        for ((key, title) in moveTargets) DropdownMenuItem(text = { Text(title) }, onClick = { moveOpen = false; onMove(key) })
                    }
                }
            }
            Button(
                onClick = onDelete, enabled = hasSelection && !working, shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                if (working && deleting) SmallSpinner(size = 16.dp) else Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
            }
        }
    }
}

/** Picks one of several servers; shows which server the screen is about. */
@Composable
fun ServerPickerChip(connections: List<ServerConnection>, current: ServerConnection?, onPick: (ServerConnection) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.clip(CircleShape).combinedClickable(onClick = { open = true }),
        ) {
            Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) ServerBadge(current.server, size = 20.dp) else Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(current?.server?.name ?: "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in connections) DropdownMenuItem(
                text = { Text(c.server.name) },
                leadingIcon = { ServerBadge(c.server, size = 20.dp) },
                trailingIcon = { if (c.id == current?.id) Icon(Icons.Outlined.Check, null) },
                onClick = { open = false; onPick(c) },
            )
        }
    }
}

/** A short success message under the chips (as Frigate's toasts). */
@Composable
fun NoticeLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = Palette.status.online)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, color = Palette.status.online)
    }
}

/** Grey centred text for an empty grid. */
@Composable
fun EmptyText(text: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * A bottom sheet with a title, a Close (or Cancel) on the left and an optional confirming
 * action on the right; its content scrolls.
 */
@Composable
fun LibrarySheet(
    title: String,
    onDismiss: () -> Unit,
    closeText: String = stringResource(R.string.close),
    confirm: (@Composable () -> Unit)? = null,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { dismissible || it != androidx.compose.material3.SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (dismissible) onDismiss() }, sheetState = state) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text(closeText, maxLines = 1) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.CenterEnd) { confirm?.invoke() }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** − value + for a number, as the iOS app's NumberStepper. */
@Composable
fun NumberStepper(value: Int, unit: String, step: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onChange(maxOf(min, value - step)) }, enabled = value > min, modifier = Modifier.size(40.dp, 36.dp)) {
            Icon(Icons.Outlined.Remove, null, tint = if (value > min) scheme.primary else scheme.outline)
        }
        Text(if (unit.isEmpty()) "$value" else "$value $unit", Modifier.widthIn(min = 64.dp), fontWeight = FontWeight.SemiBold,
            style = nl.wiebenieuwenhuis.tender.ui.components.MonoDigits, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(minOf(max, value + step)) }, enabled = value < max, modifier = Modifier.size(40.dp, 36.dp)) {
            Icon(Icons.Outlined.Add, null, tint = if (value < max) scheme.primary else scheme.outline)
        }
    }
}

/** "1 image" / "3 images". */
@Composable
fun imagesText(n: Int): String = pluralStringResource(R.plurals.classification_n_images, n, n)
