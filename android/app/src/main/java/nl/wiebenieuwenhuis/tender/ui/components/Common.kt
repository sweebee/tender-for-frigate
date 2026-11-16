package nl.wiebenieuwenhuis.tender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.app.ServerConfig
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import nl.wiebenieuwenhuis.tender.ui.theme.serverTint

/** Widest a page of text and settings gets on a tablet, centred (`readableWidth` in the iOS app). */
val ReadableWidth = 720.dp

fun Modifier.readableWidth(max: Dp = ReadableWidth) = this.fillMaxWidth().wrapCenter(max)
private fun Modifier.wrapCenter(max: Dp) = this.then(Modifier.widthIn(max = max))

/**
 * A pushed screen: a top bar with the back arrow and a title, actions on the right.
 * Every pushed screen uses this, so the back button looks and sits the same everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showsBack: Boolean = true,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val nav = LocalNav.current
    Scaffold(
        modifier = if (scrollBehavior != null) modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (showsBack) IconButton(onClick = { onBack?.invoke() ?: nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        content = content,
    )
}

/** A section heading in capitals, as Frigate's settings. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A round badge with a server's initial, in its tint. Shown wherever a server is named. */
@Composable
fun ServerBadge(server: ServerConfig, size: Dp = 22.dp) {
    val tint = serverTint(server.tintIndex)
    Box(
        Modifier.size(size).background(tint.copy(alpha = 0.2f), CircleShape).border(1.dp, tint.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(server.initial, color = tint, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A filter or group chip; `busy` shows a spinner in it while a change waits for the server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Chip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected, onClick = onClick, modifier = modifier, enabled = enabled && !busy,
        label = { Text(title, maxLines = 1, softWrap = false) },
        leadingIcon = when {
            busy -> ({ CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) })
            icon != null -> icon
            else -> null
        },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

/** A label in its colour, as Frigate's object tags. */
@Composable
fun LabelTag(label: String, modifier: Modifier = Modifier, fontSize: Int = 11) {
    Text(
        Format.labelName(label), modifier = modifier
            .background(Palette.labelColor(label), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = Palette.status.onLabel, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
    )
}

/** Nothing to show: an icon, a title and an optional message, centred. */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

/** A centred spinner for a screen that is loading. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** A problem with the screen's content, shown at its top only while something is wrong. */
@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
        }
    }
}

/** A card as used for rows and groups of settings. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(content = content) }
}

/**
 * A button whose action waits for the server: it shows a spinner in place of its text and is
 * disabled until the server answers (UX rule: the spinner sits in the control that started it).
 */
@Composable
fun ProgressButton(
    text: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    filled: Boolean = true,
) {
    val colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
    else ButtonDefaults.buttonColors()
    if (filled) {
        Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier, colors = colors) { ProgressContent(text, busy) }
    } else {
        TextButton(onClick = onClick, enabled = enabled && !busy, modifier = modifier) { ProgressContent(text, busy) }
    }
}

@Composable
private fun ProgressContent(text: String, busy: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Text(text, maxLines = 1, color = if (busy) Color.Transparent else LocalContentColor.current)
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
    }
}

/** A small spinner to put inside a row, chip or tile while its change waits for the server. */
@Composable
fun SmallSpinner(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    CircularProgressIndicator(modifier.size(size), strokeWidth = 2.dp)
}

/**
 * Asks before deleting or removing: always a Cancel button next to the red action.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A message with only OK, e.g. "Not saved" with Frigate's reason. */
@Composable
fun MessageDialog(title: String, message: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = message?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/** A destructive item in a menu: red icon and red text. */
@Composable
fun DestructiveMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    DropdownMenuItem(
        text = { Text(text, color = red) },
        leadingIcon = { Icon(icon, null, tint = red) },
        onClick = onClick,
    )
}

/** Asks for one line of text (a name), with Cancel and a confirming button. */
@Composable
fun TextPromptDialog(
    title: String,
    initial: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String? = null,
    message: String? = null,
    validate: (String) -> String? = { null },
) {
    var text by remember { mutableStateOf(initial) }
    val problem = validate(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(text, { text = it }, singleLine = true, label = label?.let { { Text(it) } },
                    isError = problem != null, supportingText = problem?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(text.trim()) }, enabled = text.isNotBlank() && problem == null) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A password field that can show its password. */
@Composable
fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onValueChange, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth(),
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (shown) R.string.hide_password else R.string.show_password))
            }
        },
    )
}

/** Monospaced digits for numbers that keep changing, so the text does not jump. */
val MonoDigits = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

/** A small rounded label on a picture (time, duration), white on translucent black. */
@Composable
fun OverlayChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Color.White) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(12.dp), tint = tint); Spacer(Modifier.width(3.dp)) }
        Text(text, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = MonoDigits)
    }
}

@Composable
fun FullScreenBox(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
