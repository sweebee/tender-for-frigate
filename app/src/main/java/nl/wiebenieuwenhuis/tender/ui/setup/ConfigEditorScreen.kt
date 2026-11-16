package nl.wiebenieuwenhuis.tender.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.FrigateException
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.ConfigEditorRoute
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

// The server's config file as text, as Frigate's Configuration Editor shows it: YAML with line
// numbers and colours, search, and save with or without a restart. Frigate checks the text before
// it writes it; its message names the line, which the editor then marks.

private enum class SaveKind { Only, Restart }

@Composable
fun ConfigEditorScreen(route: ConfigEditorRoute) {
    val conn = AppModel.connection(route.serverId)
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf("") }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf<SaveKind?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var problemLine by remember { mutableStateOf<Int?>(null) }
    var problemFirst by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    var compactMenu by remember { mutableStateOf(false) }
    val editor = remember { EditorController() }
    val typing = WindowInsets.isImeVisible
    val changed = loaded && value.text != original

    LaunchedEffect(route.serverId) {
        val client = conn?.client ?: return@LaunchedEffect
        try {
            val t = client.rawConfigText()
            original = t
            value = TextFieldValue(t)
            loaded = true
        } catch (e: FrigateException.Unauthorized) {
            loadError = L(R.string.setup_config_admin_only)
        } catch (e: Exception) {
            loadError = e.message
        }
    }

    val matches = remember(value.text, query) { if (query.isEmpty()) emptyList() else findAll(value.text, query) }
    val current = if (matches.isEmpty()) -1 else matchIndex.coerceIn(0, matches.size - 1)

    fun showMatch(i: Int) {
        if (matches.isEmpty()) return
        matchIndex = Math.floorMod(i, matches.size)
        val start = matches[matchIndex]
        value = value.copy(selection = TextRange(start, start + query.length))
        scope.launch { editor.scrollToOffset(start) }
    }

    fun undo() {
        value = TextFieldValue(original)
        problem = null; problemLine = null
    }

    fun save(kind: SaveKind) {
        val c = conn ?: return
        val client = c.client ?: return
        val text = value.text
        saving = kind
        scope.launch {
            try {
                client.saveRawConfig(text, restart = kind == SaveKind.Restart)
                original = text
                problem = null; problemLine = null
                notice = L(if (kind == SaveKind.Restart) R.string.setup_config_saved_restarting else R.string.setup_config_saved_restart_later)
                if (kind == SaveKind.Restart) {
                    // Back once Frigate answers again.
                    AppModel.scope.launch { delay(20_000); c.connect() }
                }
            } catch (e: FrigateException.Http) {
                val message = (e.detail ?: L(R.string.setup_config_not_saved))
                    .replace("Your configuration is invalid.\nSee the official documentation at docs.frigate.video.\n\n", "")
                    .trim()
                val (resolved, first) = resolveLines(message, text)
                problem = resolved
                problemFirst = first
                if (first != null) {
                    problemLine = first
                    editor.goToLine(first, { value }, { value = it })
                }
            } catch (e: Exception) {
                problem = e.message
            } finally {
                saving = null
            }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.setup_configuration),
        actions = {
            if (loaded) {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.setup_search))
                }
            }
            if (typing && (changed || problem != null)) {
                // Saving while the keyboard is up: one button, its choices in a menu.
                Box {
                    Button(onClick = { compactMenu = true }, enabled = changed && saving == null, modifier = Modifier.padding(end = 8.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.save), maxLines = 1, color = if (saving != null) Color.Transparent else androidx.compose.material3.LocalContentColor.current)
                            if (saving != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    DropdownMenu(expanded = compactMenu, onDismissRequest = { compactMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.setup_save_and_restart)) },
                            leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = { compactMenu = false; save(SaveKind.Restart) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.setup_save_only)) },
                            leadingIcon = { Icon(Icons.Filled.Save, null) }, onClick = { compactMenu = false; save(SaveKind.Only) })
                        HorizontalDivider()
                        nl.wiebenieuwenhuis.tender.ui.components.DestructiveMenuItem(stringResource(R.string.setup_undo),
                            Icons.AutoMirrored.Filled.Undo) { compactMenu = false; undo() }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            when {
                loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(loadError ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                !loaded -> LoadingBox(Modifier.weight(1f))
                else -> {
                    if (searching) {
                        SearchBar(query, { query = it; matchIndex = 0 }, count = matches.size, current = current,
                            onNext = { showMatch(current + 1) }, onPrevious = { showMatch(current - 1) },
                            onClose = { searching = false; query = "" }, onSubmit = { showMatch(current) })
                    }
                    YamlEditor(
                        value = value,
                        onValueChange = { new ->
                            val v = autoIndent(value, new)
                            if (v.text != value.text) {
                                // Editing after a refusal: the marked line may no longer be the one Frigate meant.
                                problemLine = null
                                notice = null
                            }
                            value = v
                        },
                        errorLine = problemLine,
                        matches = matches, matchLength = query.length, current = current,
                        controller = editor,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                }
            }
            notice?.let {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = Palette.status.online)
                    Spacer(Modifier.width(6.dp))
                    Text(it, color = Palette.status.online, style = MaterialTheme.typography.labelLarge)
                }
            }
            val p = problem
            if (typing && p != null) {
                // One line of Frigate's refusal while typing; the whole message once the keyboard goes.
                Row(
                    Modifier.fillMaxWidth().clickable { problemFirst?.let { editor.goToLine(it, { value }, { v -> value = v }) } }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(p.lineSequence().first(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (!typing && (changed || p != null)) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    SaveBar(
                        problem = p, problemFirst = problemFirst, changed = changed, saving = saving,
                        onGoTo = { line -> problemLine = line; editor.goToLine(line, { value }, { value = it }) },
                        onUndo = { undo() }, onSave = { save(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveBar(
    problem: String?, problemFirst: Int?, changed: Boolean, saving: SaveKind?,
    onGoTo: (Int) -> Unit, onUndo: () -> Unit, onSave: (SaveKind) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainer,
        modifier = Modifier.readableWidth().border(1.dp, if (problem == null) scheme.outlineVariant else scheme.error.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (problem != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = scheme.error)
                    Spacer(Modifier.width(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.setup_config_refused), style = MaterialTheme.typography.titleSmall)
                        SelectionContainer {
                            Text(problem, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 6,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (problemFirst != null) {
                            Text(stringResource(R.string.setup_go_to_line, problemFirst), color = scheme.primary,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.clickable { onGoTo(problemFirst) })
                        }
                    }
                }
            } else {
                Text(stringResource(R.string.setup_changed), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.setup_config_changed_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUndo, enabled = changed && saving == null, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_undo), maxLines = 1)
                }
                OutlinedProgressButton(stringResource(R.string.setup_save_only), busy = saving == SaveKind.Only,
                    onClick = { onSave(SaveKind.Only) }, enabled = changed && saving == null, modifier = Modifier.weight(1f))
            }
            nl.wiebenieuwenhuis.tender.ui.components.ProgressButton(stringResource(R.string.setup_save_and_restart), busy = saving == SaveKind.Restart,
                onClick = { onSave(SaveKind.Restart) }, enabled = changed && saving == null, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SearchBar(
    query: String, onQuery: (String) -> Unit, count: Int, current: Int,
    onNext: () -> Unit, onPrevious: () -> Unit, onClose: () -> Unit, onSubmit: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(10.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.setup_search), color = scheme.onSurfaceVariant)
            BasicTextField(
                query, onQuery, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = TextStyle(color = scheme.onSurface, fontSize = 15.sp), cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
            )
        }
        if (query.isNotEmpty()) {
            Text(if (count == 0) "0" else "${current + 1}/$count", style = MaterialTheme.typography.labelMedium.merge(nl.wiebenieuwenhuis.tender.ui.components.MonoDigits),
                color = scheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onPrevious, enabled = count > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.setup_previous)) }
        IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.setup_next)) }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
    }
}

// MARK: - The text view

/** Lets the screen drive the editor: scroll to a place, jump to a line. */
internal class EditorController {
    var layout: TextLayoutResult? = null
    var scroll: ScrollState? = null
    var viewport = 0
    var topPadding = 0f
    var scope: kotlinx.coroutines.CoroutineScope? = null

    suspend fun scrollToOffset(offset: Int) {
        val l = layout ?: return
        val s = scroll ?: return
        val line = l.getLineForOffset(offset.coerceIn(0, l.layoutInput.text.length))
        val y = l.getLineTop(line) + topPadding
        if (y < s.value || y > s.value + viewport - 80) s.animateScrollTo((y - viewport / 3f).toInt().coerceAtLeast(0))
    }

    fun goToLine(line: Int, get: () -> TextFieldValue, set: (TextFieldValue) -> Unit) {
        val v = get()
        val start = lineStart(v.text, line)
        set(v.copy(selection = TextRange(start)))
        scope?.launch { scrollToOffset(start) }
    }
}

/** Offset of the start of a line (counted from 1). */
private fun lineStart(text: String, line: Int): Int {
    var index = 0
    var current = 1
    while (current < line && index < text.length) {
        val nl = text.indexOf('\n', index)
        if (nl < 0) { index = text.length; break }
        index = nl + 1
        current++
    }
    return index
}

private fun findAll(text: String, query: String): List<Int> {
    val out = mutableListOf<Int>()
    var i = text.indexOf(query, 0, ignoreCase = true)
    while (i >= 0 && out.size < 5000) {
        out.add(i)
        i = text.indexOf(query, i + query.length.coerceAtLeast(1), ignoreCase = true)
    }
    return out
}

/** A new line keeps the indentation of the one above, as in any code editor; after "key:" two more. */
private fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val sel = new.selection
    if (!sel.collapsed || new.text.length != old.text.length + 1 || sel.start < 1 || new.text[sel.start - 1] != '\n') return new
    if (new.text.removeRange(sel.start - 1, sel.start) != old.text) return new
    val before = new.text.substring(0, sel.start - 1)
    val line = before.substring(before.lastIndexOf('\n') + 1)
    var indent = line.takeWhile { it == ' ' }
    if (line.trim().endsWith(":")) indent += "  "
    if (indent.isEmpty()) return new
    val text = new.text.substring(0, sel.start) + indent + new.text.substring(sel.start)
    return TextFieldValue(text, TextRange(sel.start + indent.length))
}

private val gutterWidth = 44.dp

@Composable
private fun YamlEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    errorLine: Int?,
    matches: List<Int>,
    matchLength: Int,
    current: Int,
    controller: EditorController,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val status = Palette.status
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableIntStateOf(0) }
    controller.scroll = scroll
    controller.scope = rememberCoroutineScope()
    controller.viewport = viewport
    controller.topPadding = with(density) { 10.dp.toPx() }
    val colors = YamlColors(key = scheme.primary, value = status.warning, string = status.online,
        comment = scheme.onSurfaceVariant.copy(alpha = 0.7f), error = scheme.error.copy(alpha = 0.25f),
        match = status.warning.copy(alpha = 0.3f), currentMatch = status.warning.copy(alpha = 0.7f))
    val transformation = remember(errorLine, matches, matchLength, current, colors) {
        YamlTransformation(colors, errorLine, matches, matchLength, current)
    }
    val lineStarts = remember(value.text) {
        buildList { add(0); value.text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    }
    val numberStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant.copy(alpha = 0.6f))
    val gutterColor = scheme.surfaceContainerHighest
    Box(
        modifier.fillMaxWidth()
            .background(scheme.surfaceContainer, RoundedCornerShape(10.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .onSizeChanged { viewport = it.height }
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = with(density) { viewport.toDp() })
                    .drawBehind {
                        // The gutter with line numbers, drawn only for the lines in view.
                        val gutter = gutterWidth.toPx()
                        drawRect(gutterColor, size = androidx.compose.ui.geometry.Size(gutter, size.height))
                        val l = layout ?: return@drawBehind
                        val top = controller.topPadding
                        val from = scroll.value - 40
                        val to = scroll.value + viewport + 40
                        val textLength = l.layoutInput.text.length
                        for ((i, start) in lineStarts.withIndex()) {
                            val visualLine = l.getLineForOffset(start.coerceAtMost(textLength))
                            val y = l.getLineTop(visualLine) + top
                            if (y < from) continue
                            if (y > to) break
                            val label = measurer.measure((i + 1).toString(), numberStyle)
                            val h = l.getLineBottom(visualLine) - l.getLineTop(visualLine)
                            drawText(label, topLeft = Offset(gutter - 6.dp.toPx() - label.size.width, y + (h - label.size.height) / 2))
                        }
                    }
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().padding(start = gutterWidth + 8.dp, end = 10.dp, top = 10.dp, bottom = 40.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = scheme.onSurface, lineHeight = 18.sp),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it; controller.layout = it },
                )
            }
        }
    }
}

private data class YamlColors(
    val key: Color, val value: Color, val string: Color, val comment: Color, val error: Color, val match: Color, val currentMatch: Color,
)

private val numberRe = Regex(""":[ \t]*(-?\d+(\.\d+)?)[ \t]*$""", RegexOption.MULTILINE)
private val boolRe = Regex("""(?:^|:\s)[ \t]*(true|false|null)[ \t]*$""", RegexOption.MULTILINE)
private val keyRe = Regex("""^[ \t]*-?[ \t]*([A-Za-z0-9_\-. ]+?):(?=\s|$)""", RegexOption.MULTILINE)
private val quoteRe = Regex("""(["'])(?:(?!\1).)*\1""")
private val commentRe = Regex("""(^|\s)#.*$""", RegexOption.MULTILINE)

/** Colours: keys, comments, quoted text, numbers and true/false; the line Frigate refused; search matches. */
private class YamlTransformation(
    val colors: YamlColors, val errorLine: Int?, val matches: List<Int>, val matchLength: Int, val current: Int,
) : VisualTransformation {
    private var lastText: String? = null
    private var lastResult: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw == lastText) lastResult?.let { return it }
        val styled = buildAnnotatedString {
            append(raw)
            fun paint(re: Regex, color: Color, group: Int = 0) {
                for (m in re.findAll(raw)) {
                    val r = m.groups[group]?.range ?: continue
                    addStyle(SpanStyle(color = color), r.first, r.last + 1)
                }
            }
            paint(numberRe, colors.value, 1)
            paint(boolRe, colors.value, 1)
            paint(keyRe, colors.key, 1)
            paint(quoteRe, colors.string)
            paint(commentRe, colors.comment)
            if (errorLine != null) {
                val start = lineStart(raw, errorLine)
                if (start < raw.length) {
                    val end = raw.indexOf('\n', start).let { if (it < 0) raw.length else it }
                    addStyle(SpanStyle(background = colors.error), start, end)
                }
            }
            if (matchLength > 0) {
                for ((i, m) in matches.withIndex()) {
                    if (m + matchLength > raw.length) break
                    addStyle(SpanStyle(background = if (i == current) colors.currentMatch else colors.match), m, m + matchLength)
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity).also { lastText = raw; lastResult = it }
    }
}

/**
 * Frigate's "Line 43: cameras -> carport -> detect -> heigth - …" names the line of the block the
 * wrong key sits in (from 0). Point each at the key itself, counted from 1 as the editor shows them.
 */
internal fun resolveLines(message: String, text: String): Pair<String, Int?> {
    val lines = text.split("\n")
    var first: Int? = null
    val rowRe = Regex("""^Line (\d+): (.+?) - """)
    val rows = message.split("\n").map { row ->
        val m = rowRe.find(row) ?: return@map row
        val start = m.groupValues[1].toIntOrNull() ?: return@map row
        val key = m.groupValues[2].split(" -> ").last()
        var line = start + 1
        if (key.isNotEmpty() && key.toIntOrNull() == null) {
            val re = Regex("^\\s*-?\\s*" + Regex.escape(key) + "\\s*:")
            val found = (start until minOf(lines.size, start + 400)).firstOrNull { re.containsMatchIn(lines[it]) }
            if (found != null) line = found + 1
        }
        if (first == null) first = line
        row.replaceFirst("Line $start:", L(R.string.setup_line, line))
    }
    return rows.joinToString("\n") to first
}
