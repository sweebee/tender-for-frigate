package nl.wiebenieuwenhuis.tender.ui.playback

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.api.Severity

/** Client-side filter for review items by type, label and zone. */
@Immutable
data class PlaybackFilter(
    val severity: Severity? = null,
    val labels: Set<String> = emptySet(),
    val zones: Set<String> = emptySet(),
) {
    val isActive: Boolean get() = severity != null || labels.isNotEmpty() || zones.isNotEmpty()

    fun matches(item: ReviewItem): Boolean {
        if (severity != null && item.severity != severity) return false
        if (labels.isNotEmpty() && labels.none { it in item.labels }) return false
        if (zones.isNotEmpty() && zones.none { it in item.zones }) return false
        return true
    }
}

/** Chips for filtering review items: type, labels and zones. `zones`: the camera's zones, offered even when nothing happened there yet. */
@Composable
fun PlaybackFilterBar(
    filter: PlaybackFilter,
    onChange: (PlaybackFilter) -> Unit,
    reviews: List<ReviewItem>,
    zones: List<String>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val labelOptions = (reviews.flatMap { it.labels } + filter.labels).distinct().sorted()
    val zoneOptions = (reviews.flatMap { it.zones } + zones + filter.zones).distinct().sorted()
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuChip(
            title = when (filter.severity) {
                null -> stringResource(R.string.playback_filter_type)
                Severity.Alert -> stringResource(R.string.playback_alerts)
                Severity.Detection -> stringResource(R.string.playback_detections)
            },
            active = filter.severity != null,
            enabled = true,
        ) { close ->
            Option(stringResource(R.string.playback_all_types), filter.severity == null) { onChange(filter.copy(severity = null)); close() }
            Option(stringResource(R.string.playback_alerts), filter.severity == Severity.Alert) { onChange(filter.copy(severity = Severity.Alert)); close() }
            Option(stringResource(R.string.playback_detections), filter.severity == Severity.Detection) {
                onChange(filter.copy(severity = Severity.Detection)); close()
            }
        }
        MenuChip(
            title = if (filter.labels.isEmpty()) stringResource(R.string.playback_filter_labels)
            else filter.labels.map(Format::labelName).sorted().joinToString(", "),
            active = filter.labels.isNotEmpty(),
            enabled = labelOptions.isNotEmpty(),
        ) { close ->
            Option(stringResource(R.string.playback_all_labels), filter.labels.isEmpty()) { onChange(filter.copy(labels = emptySet())); close() }
            for (l in labelOptions) Option(Format.labelName(l), l in filter.labels) { onChange(filter.copy(labels = filter.labels.toggle(l))) }
        }
        MenuChip(
            title = if (filter.zones.isEmpty()) stringResource(R.string.playback_filter_zones)
            else filter.zones.map(Format::prettyName).sorted().joinToString(", "),
            active = filter.zones.isNotEmpty(),
            enabled = zoneOptions.isNotEmpty(),
        ) { close ->
            Option(stringResource(R.string.playback_all_zones), filter.zones.isEmpty()) { onChange(filter.copy(zones = emptySet())); close() }
            for (z in zoneOptions) Option(Format.prettyName(z), z in filter.zones) { onChange(filter.copy(zones = filter.zones.toggle(z))) }
        }
        if (filter.isActive) {
            IconButton(
                onClick = { onChange(PlaybackFilter()) },
                modifier = Modifier.size(34.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            ) { Icon(Icons.Filled.Close, stringResource(R.string.playback_clear_filters), Modifier.size(16.dp)) }
        }
    }
}

private fun Set<String>.toggle(v: String) = if (v in this) this - v else this + v

@Composable
private fun MenuChip(title: String, active: Boolean, enabled: Boolean, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active, onClick = { open = true }, enabled = enabled,
            label = { Text(title, maxLines = 1, softWrap = false) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
private fun Option(title: String, on: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, maxLines = 1) },
        leadingIcon = { if (on) Icon(Icons.Filled.Check, null) else Box(Modifier.size(24.dp)) },
        onClick = onClick,
    )
}
