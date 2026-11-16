package nl.wiebenieuwenhuis.tender.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.ui.components.ProgressButton
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.isWideLayout
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth

/*
 * Building blocks shared by the setup screens (config editor, streams, camera wizard, zones).
 */

/**
 * A sheet with a form: Cancel (×) on the left, the title, and the button that finishes it on the
 * right (filled, with a spinner while it waits for Frigate). Full screen on a phone; on a wide
 * window a centred card about 640 dp wide, as tall as its content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FormSheet(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    confirmEnabled: Boolean = true,
    confirmBusy: Boolean = false,
    onConfirm: () -> Unit = {},
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val wide = isWideLayout()
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(enabled = !dismissEnabled) {}
        val topBar = @Composable {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = dismissEnabled) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (confirmText != null) {
                        ProgressButton(confirmText, busy = confirmBusy, onClick = onConfirm, enabled = confirmEnabled,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                },
                // In a card the bar does not sit under the status bar.
                windowInsets = if (wide) WindowInsets(0) else TopAppBarDefaults.windowInsets,
                colors = if (wide) TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    else TopAppBarDefaults.topAppBarColors(),
            )
        }
        if (wide) {
            BoxWithConstraints(
                Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    Modifier.widthIn(max = WideSheetWidth).fillMaxWidth().heightIn(max = maxHeight),
                    shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp, shadowElevation = 6.dp,
                ) {
                    Column {
                        topBar()
                        Column(
                            // As tall as the content; scrolls once the window is not tall enough.
                            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            content = content,
                        )
                    }
                }
            }
        } else {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Scaffold(topBar = topBar) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier.readableWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

/** The width of a form sheet on a wide window. */
private val WideSheetWidth = 640.dp

/** A small explanation under a card. */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = color)
}

/** An on/off setting: the label on the left, the switch on the right. */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, busy: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && !busy) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (busy) nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner(Modifier.padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled && !busy)
    }
}

/** A row with a title and a value on the right, to read. */
@Composable
internal fun ValueRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** A card holding rows, divided by lines. */
@Composable
internal fun FormCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = SectionCard(modifier, content)

/** A choice that can be on or off (a role, an object): filled when on. */
@Composable
internal fun ToggleChip(title: String, on: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Text(
        title,
        Modifier
            .background(if (on) scheme.primary else scheme.surfaceContainerHighest, CircleShape)
            .border(1.dp, if (on) Color.Transparent else scheme.outlineVariant, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        color = if (on) scheme.onPrimary else scheme.onSurface,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
    )
}

/** Small role and info chips that wrap onto a new line when they do not fit (`FlowChips`). */
@Composable
internal fun FlowChips(chips: List<Pair<String, Boolean>>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for ((text, accent) in chips) {
            Text(
                text,
                Modifier
                    .background(if (accent) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHighest, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                color = if (accent) scheme.primary else scheme.onSurfaceVariant,
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            )
        }
    }
}

/** Two buttons side by side, equally wide: a secondary one (Back, Undo) and the main one. */
@Composable
internal fun ButtonPair(
    secondary: String, onSecondary: () -> Unit, primary: String, onPrimary: () -> Unit,
    primaryBusy: Boolean = false, primaryEnabled: Boolean = true, secondaryEnabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onSecondary, enabled = secondaryEnabled, modifier = Modifier.weight(1f)) { Text(secondary, maxLines = 1) }
        ProgressButton(primary, busy = primaryBusy, onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(1f))
    }
}

/** A wide button that removes something: red text on a faint red card. */
@Composable
internal fun DestructiveWideButton(text: String, onClick: () -> Unit, busy: Boolean = false, enabled: Boolean = true) {
    val red = MaterialTheme.colorScheme.error
    Box(
        Modifier.fillMaxWidth().padding(top = 18.dp)
            .background(red.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, red.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner()
            Text(text, color = red, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A filled button across the width (the step's main action). */
@Composable
internal fun WideButton(text: String, onClick: () -> Unit, busy: Boolean = false, enabled: Boolean = true) {
    ProgressButton(text, busy = busy, onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

/** Joins names as a sentence list: "A, B and C". */
internal fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " " + nl.wiebenieuwenhuis.tender.api.L(R.string.setup_and) + " " + names.last()
}


/** An outlined button whose action waits for Frigate: a spinner in place of its text meanwhile. */
@Composable
internal fun OutlinedProgressButton(text: String, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled && !busy, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, maxLines = 1, color = if (busy) Color.Transparent else androidx.compose.material3.LocalContentColor.current)
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * Hides the password in a stream address while it is typed: each of its characters shows as a
 * dot, so the text keeps its length and the caret stays where it is.
 */
internal object UrlPasswordTransformation : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val ranges = nl.wiebenieuwenhuis.tender.api.StreamAddress.passwordRanges(raw)
        if (ranges.isEmpty()) return androidx.compose.ui.text.input.TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
        val chars = raw.toCharArray()
        for (r in ranges) for (i in r) if (i in chars.indices) chars[i] = '•'
        return androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(String(chars)),
            androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}
