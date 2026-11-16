package nl.wiebenieuwenhuis.tender.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.Lq
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import nl.wiebenieuwenhuis.tender.ui.components.ReadableWidth
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - JSON

/** The value at a dotted path ("alerts.retain.days"). */
fun JsonElement?.at(path: String): JsonElement? = path.split('.').fold(this) { node, key -> node[key] }

/** Sets a value at a dotted path, creating the levels in between. */
@Suppress("UNCHECKED_CAST")
fun MutableMap<String, Any?>.setNested(path: String, value: Any?) {
    val parts = path.split('.')
    var node: MutableMap<String, Any?> = this
    for (p in parts.dropLast(1)) {
        node = (node[p] as? MutableMap<String, Any?>) ?: mutableMapOf<String, Any?>().also { node[p] = it }
    }
    node[parts.last()] = value
}

// MARK: - Formatting

object SettingsFormat {
    /** "1 day", "30 days", "1.5 days". */
    fun days(d: Double): String =
        if (d == d.roundToInt().toDouble()) Lq(R.plurals.settings_n_days, d.roundToInt(), d.roundToInt())
        else L(R.string.settings_days_decimal, String.format(Locale.getDefault(), "%.1f", d))

    /** Frigate reports disk sizes in MB; shown as binary sizes ("1.2 TB"). */
    fun size(mb: Double): String {
        var v = mb * 1_048_576.0
        val units = listOf("bytes", "KB", "MB", "GB", "TB", "PB")
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i <= 1) "${v.roundToInt()} ${units[i]}" else String.format(Locale.getDefault(), "%.1f %s", v, units[i])
    }

    /** "3 d 4 h", "9 h 12 min" or "12 min". */
    fun uptime(t: Double): String {
        val s = t.toLong()
        return when {
            s >= 86400 -> L(R.string.settings_uptime_days, s / 86400, (s % 86400) / 3600)
            s >= 3600 -> L(R.string.settings_uptime_hours, s / 3600, (s % 3600) / 60)
            else -> L(R.string.settings_uptime_minutes, s / 60)
        }
    }

    /** Compares "0.18.2" with "0.17.1" by their numbers. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split("-").first().split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split("-").first().split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** A profile's key from its name, as Frigate's own page makes it ("Thuis & weg" → "thuis_weg"). */
    fun key(title: String): String {
        val folded = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
        return folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("_")
    }

    /** go2rtc logs camera addresses with their password ("rtsp://admin:secret@…", "&password=…"). */
    fun hidingPasswords(line: String): String =
        nl.wiebenieuwenhuis.tender.api.ServerAddress.maskPassword(line)
            .replace(Regex("([?&](?:password|pass|pwd)=)[^&\\s\"]+", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]}••••" }
}

// MARK: - Server actions

/** Restarts Frigate and waits until it answers again, then reloads its config. */
suspend fun restartAndWait(conn: ServerConnection) {
    val client = conn.client ?: return
    client.restart()
    delay(5000)
    for (i in 0 until 40) {
        if (runCatching { client.version(3) }.isSuccess) break
        delay(3000)
    }
    conn.refresh(reloadConfig = true)
}

// MARK: - Layout

/**
 * A settings page: scrolls, keeps the readable width on a tablet, and refreshes when pulled
 * down if `onRefresh` is given.
 */
@Composable
fun SettingsPage(
    inner: PaddingValues,
    onRefresh: (suspend () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body = @Composable {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ReadableWidth).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
    if (onRefresh == null) {
        Box(Modifier.fillMaxSize().padding(inner)) { body() }
    } else {
        var refreshing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; try { onRefresh() } finally { refreshing = false } } },
            modifier = Modifier.fillMaxSize().padding(inner),
        ) { body() }
    }
}

/** A small explanation under a card. */
@Composable
fun Footnote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 4.dp))
}

/** The divider between rows in a card. */
@Composable
fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

/** An icon on a soft square, as in the rows of the iOS app. */
@Composable
fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(18.dp), tint = tint) }
}

/** A row in a settings card: an icon, a title, a value on the right and a chevron. */
@Composable
fun SettingsRow(
    icon: ImageVector?,
    title: String,
    value: String = "",
    tint: Color = MaterialTheme.colorScheme.primary,
    chevron: Boolean = true,
    pending: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled && !pending, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) RowIcon(icon, tint)
        Text(title, Modifier.weight(1f), color = titleColor, style = MaterialTheme.typography.bodyLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (pending) SmallSpinner()
        if (value.isNotEmpty()) Text(value, Modifier.widthIn(max = 200.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chevron) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An on/off setting: the label on the left, the switch on the right; a spinner while it waits. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    pending: Boolean = false,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    val active = enabled && !pending
    Row(
        Modifier.fillMaxWidth().clickable(enabled = active) { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pending) SmallSpinner()
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = active,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.status.online),
        )
    }
}

/** A choice from a short list: a row whose value opens a menu, the chosen item with a check. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    pending: Boolean = false,
    enabled: Boolean = true,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var open by remember { mutableStateOf(false) }
    val active = enabled && !pending
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = active) { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) RowIcon(icon)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pending) SmallSpinner()
            Text(options.firstOrNull { it.first == selected }?.second ?: "", style = MaterialTheme.typography.bodyMedium,
                color = valueColor, fontWeight = FontWeight.Medium, maxLines = 1)
            Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(18.dp), tint = valueColor)
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((value, label) in options) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { if (value == selected) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.width(24.dp)) },
                        onClick = { open = false; if (value != selected) onSelect(value) },
                    )
                }
            }
        }
    }
}

/** A row with a title and a value, to read. */
@Composable
fun InfoRow(title: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(value, Modifier.widthIn(max = 220.dp), style = if (mono) MaterialTheme.typography.bodyMedium.merge(nl.wiebenieuwenhuis.tender.ui.components.MonoDigits)
            else MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** Dims content that cannot be used now. */
fun Modifier.dimmed(on: Boolean): Modifier = if (on) this.alpha(0.6f) else this
