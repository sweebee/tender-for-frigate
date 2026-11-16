package nl.wiebenieuwenhuis.tender.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.ChatTab
import nl.wiebenieuwenhuis.tender.ui.ClassificationTab
import nl.wiebenieuwenhuis.tender.ui.ExportsRoute
import nl.wiebenieuwenhuis.tender.ui.FacesTab
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.SettingsTab
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth

/** The phone's fourth tab: what does not get a tab of its own. Tablets show these in the rail. */
@Composable
fun MoreScreen() {
    val nav = LocalNav.current
    var exportCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        exportCount = coroutineScope {
            AppModel.scopedConnections.filter { it.isOnline }.mapNotNull { it.client }
                .map { c -> async { runCatching { c.exports() }.getOrNull() } }.awaitAll()
                .filterNotNull().flatten().count { AppModel.deletedExports[it.id] != true }
        }
    }
    ScreenScaffold(stringResource(R.string.tab_more), showsBack = false) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).readableWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Icons.Outlined.MovieFilter, stringResource(R.string.exports),
                exportCount?.let { pluralStringResource(R.plurals.n_clips, it, it) } ?: stringResource(R.string.exports_subtitle)) {
                nav.navigate(ExportsRoute())
            }
            if (AppModel.classificationConnections.isNotEmpty())
                Row(Icons.Outlined.Category, stringResource(R.string.classification), stringResource(R.string.classification_subtitle)) { nav.navigate(ClassificationTab) }
            if (AppModel.faceConnections.isNotEmpty())
                Row(Icons.Outlined.Face, stringResource(R.string.face_library), stringResource(R.string.face_library_subtitle)) { nav.navigate(FacesTab) }
            if (AppModel.chatConnections.isNotEmpty())
                Row(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.chat), stringResource(R.string.chat_subtitle)) { nav.navigate(ChatTab) }
            Row(Icons.Outlined.Settings, stringResource(R.string.settings), stringResource(R.string.settings_subtitle)) { nav.navigate(SettingsTab) }
        }
    }
}

@Composable
private fun Row(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    SectionCard {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle, maxLines = 1) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}
