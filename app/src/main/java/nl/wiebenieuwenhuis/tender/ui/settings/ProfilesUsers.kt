package nl.wiebenieuwenhuis.tender.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateProfile
import nl.wiebenieuwenhuis.tender.api.FrigateUser
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.bool
import nl.wiebenieuwenhuis.tender.api.get
import nl.wiebenieuwenhuis.tender.api.obj
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.LocalNav
import nl.wiebenieuwenhuis.tender.ui.components.ConfirmDialog
import nl.wiebenieuwenhuis.tender.ui.components.DestructiveMenuItem
import nl.wiebenieuwenhuis.tender.ui.components.LoadingBox
import nl.wiebenieuwenhuis.tender.ui.components.MessageDialog
import nl.wiebenieuwenhuis.tender.ui.components.PasswordField
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SmallSpinner
import nl.wiebenieuwenhuis.tender.ui.components.TextPromptDialog
import nl.wiebenieuwenhuis.tender.ui.theme.Palette

// MARK: - Profiles

/** A top-bar icon button that shows a spinner while its change waits for the server. */
@Composable
fun BusyIconButton(icon: ImageVector, description: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled && !busy) {
        if (busy) SmallSpinner() else Icon(icon, description)
    }
}

/**
 * Frigate 0.18's profiles: switch the whole server to "Home", "Away", … in one tap, and make or
 * change them (what each one does per camera).
 */
@Composable
fun ProfilesScreen(route: ProfilesRoute) {
    val nav = LocalNav.current
    val conn = AppModel.connection(route.serverId)
    var profiles by remember { mutableStateOf<List<FrigateProfile>>(emptyList()) }
    var active by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FrigateProfile?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    /** The profile being created (""), renamed or deleted on the server. */
    var workingOn by remember { mutableStateOf<String?>(null) }
    val working = workingOn != null
    val isAdmin = conn?.isAdmin ?: true
    val scope = rememberCoroutineScope()

    suspend fun load() {
        val client = conn?.client ?: return
        runCatching { client.profiles() }.getOrNull()?.let { (list, act) ->
            profiles = list; active = act
            // Live's switcher reads the connection's list.
            conn.profiles = list; conn.activeProfile = act
        }
        loaded = true
    }
    LaunchedEffect(Unit) { load() }

    /** Frigate reloads its profiles a moment after the config changed: ask until its list shows the change. */
    suspend fun settle(done: (List<FrigateProfile>) -> Boolean) {
        for (i in 0 until 20) {
            val p = runCatching { conn?.client?.profiles() }.getOrNull()
            if (p != null && done(p.first)) break
            delay(500)
        }
        load()
    }

    fun activate(name: String?) {
        val c = conn ?: return
        if (name == active) return
        switching = name ?: ""
        val before = active
        active = name
        c.command("profile/set", name ?: "none")
        scope.launch {
            // Frigate applies the profile to every camera, which takes a few seconds; ask until it reports it.
            for (i in 0 until 10) {
                delay(1000)
                if (runCatching { c.client?.profiles() }.getOrNull()?.second == name) break
            }
            load()
            if (active != name) { active = before; errorMessage = L(R.string.settings_profile_not_switched) }
            c.refresh(reloadConfig = true)
            switching = null
        }
    }

    fun create(title: String) {
        val client = conn?.client ?: return
        val id = SettingsFormat.key(title)
        if (id.isEmpty()) return
        if (profiles.any { it.name == id }) { errorMessage = L(R.string.settings_profile_exists); return }
        workingOn = ""
        scope.launch {
            try {
                client.setConfig(mapOf("profiles" to mapOf(id to mapOf("friendly_name" to title))), false, null)
                settle { list -> list.any { it.name == id } }
            } catch (e: Exception) { errorMessage = e.message } finally { workingOn = null }
        }
    }

    fun rename(id: String, title: String) {
        val client = conn?.client ?: return
        if (title.isEmpty()) return
        workingOn = id
        scope.launch {
            try {
                client.setConfig(mapOf("profiles" to mapOf(id to mapOf("friendly_name" to title))), false, null)
                settle { list -> list.any { it.name == id && it.title == title } }
            } catch (e: Exception) { errorMessage = e.message } finally { workingOn = null }
        }
    }

    fun delete(id: String) {
        val c = conn ?: return
        val client = c.client ?: return
        workingOn = id
        scope.launch {
            try {
                // An active profile is switched off first, as Frigate's own page does, and only
                // deleted once Frigate reports no profile.
                if (active == id || c.activeProfile == id) {
                    client.setProfile(null)
                    for (i in 0 until 20) {
                        if (runCatching { client.profiles() }.getOrNull()?.let { it.second == null } == true) break
                        delay(500)
                    }
                }
                val data = mutableMapOf<String, Any?>("profiles" to mapOf(id to ""))
                runCatching { client.rawConfig() }.getOrNull()?.get("cameras").obj()?.let { cams ->
                    val camData = cams.filter { (_, v) -> v["profiles"][id] != null }.keys.associateWith { mapOf("profiles" to mapOf(id to "")) }
                    if (camData.isNotEmpty()) data["cameras"] = camData
                }
                client.setConfig(data, false, null)
                settle { list -> list.none { it.name == id } }
                if (profiles.any { it.name == id }) errorMessage = L(R.string.settings_profile_still_listed)
            } catch (e: Exception) { errorMessage = e.message } finally { workingOn = null }
        }
    }

    ScreenScaffold(
        stringResource(R.string.settings_profiles),
        actions = {
            if (isAdmin) BusyIconButton(Icons.Outlined.Add, stringResource(R.string.settings_new_profile), workingOn == "", !working) { creating = true }
        },
    ) { inner ->
        SettingsPage(inner, onRefresh = { load() }) {
            Text(stringResource(R.string.settings_profiles_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!loaded) {
                LoadingBox()
            } else {
                SectionCard(Modifier.dimmed(working)) {
                    ProfileRow(null, stringResource(R.string.settings_no_profile), stringResource(R.string.settings_no_profile_subtitle),
                        active == null, switching == "", switching == null && !working, false, false, { activate(null) }, {}, {}, {})
                    for (p in profiles) {
                        RowDivider()
                        ProfileRow(p.name, p.title, null, active == p.name, switching == p.name, switching == null && !working,
                            isAdmin, workingOn == p.name,
                            onActivate = { activate(p.name) },
                            onRename = { renaming = p },
                            onDelete = { deleting = p.name },
                            onEdit = { nav.navigate(ProfileEditRoute(route.serverId, p.name, p.title, active == p.name)) })
                    }
                }
                if (profiles.isEmpty() && isAdmin) Footnote(stringResource(R.string.settings_profiles_empty_hint))
            }
        }
    }

    if (creating) TextPromptDialog(
        title = stringResource(R.string.settings_new_profile), initial = "", confirmText = stringResource(R.string.settings_create),
        label = stringResource(R.string.settings_profile_name_placeholder),
        onConfirm = { create(it) }, onDismiss = { creating = false },
    )
    renaming?.let { p ->
        TextPromptDialog(
            title = stringResource(R.string.settings_rename_profile), initial = p.title, confirmText = stringResource(R.string.rename),
            label = stringResource(R.string.settings_name),
            onConfirm = { rename(p.name, it) }, onDismiss = { renaming = null },
        )
    }
    deleting?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_profile_question),
            message = stringResource(R.string.settings_delete_profile_message),
            confirmText = stringResource(R.string.delete),
            onConfirm = { delete(id) }, onDismiss = { deleting = null },
        )
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.settings_something_wrong), it) { errorMessage = null } }
}

@Composable
private fun ProfileRow(
    name: String?, title: String, subtitle: String?, isActive: Boolean, switching: Boolean, canSwitch: Boolean,
    editable: Boolean, working: Boolean,
    onActivate: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(enabled = canSwitch) { onActivate() }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(if (isActive) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (switching) SmallSpinner()
        }
        if (name != null && editable) {
            if (working) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { SmallSpinner() }
            } else {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.settings_options_for, title)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onRename() })
                        HorizontalDivider()
                        DestructiveMenuItem(stringResource(R.string.delete), Icons.Outlined.Delete) { menu = false; onDelete() }
                    }
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.settings_edit_named, title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What a profile changes, per camera: each switch follows the camera's own setting, or is on or off while it is active. */
@Composable
fun ProfileEditScreen(route: ProfileEditRoute) {
    val conn = AppModel.connection(route.serverId)
    /** Camera → its overrides under `profiles.<profile>`. */
    var overrides by remember { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cameras = orderedCameras(route.serverId)

    suspend fun load() {
        val cams = runCatching { conn?.client?.rawConfig() }.getOrNull()?.get("cameras").obj()
        if (cams != null) overrides = cams.mapValues { (_, v) -> v["profiles"][route.profile].obj() ?: JsonObject(emptyMap()) }
        loaded = true
    }
    LaunchedEffect(Unit) { load() }

    fun set(camera: String, path: String, value: Boolean?) {
        val c = conn ?: return
        val client = c.client ?: return
        saving = camera + path
        val node = mutableMapOf<String, Any?>()
        // "" removes the override, as Frigate's own page sends it.
        node.setNested(path, value ?: "")
        scope.launch {
            try {
                client.setConfig(mapOf("cameras" to mapOf(camera to mapOf("profiles" to mapOf(route.profile to node)))), false, null)
                // An active profile takes the change on its next activation.
                if (route.active) c.command("profile/set", route.profile)
                load()
            } catch (e: Exception) { errorMessage = e.message } finally { saving = null }
        }
    }

    val switches: List<Triple<String, Int, ImageVector>> = listOf(
        Triple("enabled", R.string.settings_camera, Icons.Outlined.Videocam),
        Triple("detect.enabled", R.string.settings_object_detection, Icons.Outlined.CenterFocusStrong),
        Triple("record.enabled", R.string.settings_record, Icons.Outlined.FiberManualRecord),
        Triple("snapshots.enabled", R.string.settings_snapshots, Icons.Outlined.Photo),
        Triple("review.alerts.enabled", R.string.settings_alerts, Icons.Outlined.Campaign),
        Triple("notifications.enabled", R.string.settings_push_notifications, Icons.Outlined.Notifications),
    )

    ScreenScaffold(route.title) { inner ->
        SettingsPage(inner) {
            Text(stringResource(R.string.settings_profile_edit_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!loaded) {
                LoadingBox()
            } else {
                for (cam in cameras) {
                    SectionLabel(cam.displayName, Modifier.padding(top = 10.dp))
                    SectionCard {
                        switches.forEachIndexed { i, (path, title, icon) ->
                            if (i > 0) RowDivider()
                            val value = overrides[cam.name].at(path).bool()
                            val color = when (value) {
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                                true -> Palette.status.online
                                false -> Palette.status.warning
                            }
                            ChoiceRow(
                                stringResource(title),
                                listOf<Pair<Boolean?, String>>(null to stringResource(R.string.settings_default_as_configured),
                                    true to stringResource(R.string.settings_on), false to stringResource(R.string.settings_off)),
                                value, { set(cam.name, path, it) }, icon = icon,
                                pending = saving == cam.name + path, enabled = saving == null, valueColor = color,
                            )
                        }
                    }
                }
            }
        }
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.not_saved), it) { errorMessage = null } }
}

// MARK: - Users

/** The server's user accounts: add, change role or password, remove. Admin only. */
@Composable
fun UsersScreen(route: UsersRoute) {
    val conn = AppModel.connection(route.serverId)
    var users by remember { mutableStateOf<List<FrigateUser>>(emptyList()) }
    var roles by remember { mutableStateOf(listOf("admin", "viewer")) }
    var loaded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var passwordFor by remember { mutableStateOf<FrigateUser?>(null) }
    var deleting by remember { mutableStateOf<FrigateUser?>(null) }
    /** The user being changed on the server ("" for a new one). */
    var workingOn by remember { mutableStateOf<String?>(null) }
    val working = workingOn != null
    val scope = rememberCoroutineScope()

    suspend fun load() {
        val client = conn?.client ?: return
        try {
            users = client.users()
            runCatching { client.rawConfig() }.getOrNull()?.get("auth")?.get("roles").obj()?.keys?.let { custom ->
                roles = listOf("admin", "viewer") + custom.filter { it != "admin" && it != "viewer" }.sorted()
            }
        } catch (e: Exception) { errorMessage = e.message }
        loaded = true
    }
    LaunchedEffect(Unit) { load() }

    fun run(user: String, work: suspend (FrigateClient) -> Unit) {
        val client = conn?.client ?: return
        workingOn = user
        scope.launch {
            try { work(client); load() } catch (e: Exception) { errorMessage = e.message } finally { workingOn = null }
        }
    }

    ScreenScaffold(
        stringResource(R.string.settings_users),
        actions = { BusyIconButton(Icons.Outlined.Add, stringResource(R.string.settings_add_user), workingOn == "", !working) { adding = true } },
    ) { inner ->
        SettingsPage(inner, onRefresh = { load() }) {
            if (!loaded) {
                LoadingBox()
            } else {
                SectionCard {
                    users.forEachIndexed { i, user ->
                        if (i > 0) RowDivider()
                        UserRow(user, roles, workingOn == user.username, !working,
                            onRole = { role -> run(user.username) { it.setRole(user.username, role) } },
                            onPassword = { passwordFor = user },
                            onRemove = { deleting = user })
                    }
                }
                Footnote(stringResource(R.string.settings_users_footer))
            }
        }
    }

    if (adding) AddUserDialog(roles, onDismiss = { adding = false }) { name, password, role ->
        adding = false
        run("") { it.createUser(name, password, role) }
    }
    passwordFor?.let { user ->
        PasswordDialog(user.username, onDismiss = { passwordFor = null }) { password ->
            passwordFor = null
            run(user.username) { it.setPassword(user.username, password) }
        }
    }
    deleting?.let { user ->
        ConfirmDialog(
            title = stringResource(R.string.settings_remove_user_question),
            message = stringResource(R.string.settings_user_cannot_sign_in, user.username),
            confirmText = stringResource(R.string.remove),
            onConfirm = { run(user.username) { it.deleteUser(user.username) } },
            onDismiss = { deleting = null },
        )
    }
    errorMessage?.let { MessageDialog(stringResource(R.string.settings_something_wrong), it) { errorMessage = null } }
}

private fun roleName(role: String): String = when (role) {
    "admin" -> L(R.string.settings_role_admin)
    "viewer" -> L(R.string.settings_role_viewer)
    else -> Format.prettyName(role)
}

@Composable
private fun UserRow(
    user: FrigateUser, roles: List<String>, working: Boolean, enabled: Boolean,
    onRole: (String) -> Unit, onPassword: () -> Unit, onRemove: () -> Unit,
) {
    val builtIn = user.username == "admin"
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(32.dp).background(
                if (user.role == "admin") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(user.username.take(1).uppercase(), color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(user.username, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(roleName(user.role ?: "viewer"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (working) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { SmallSpinner() }
        } else {
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.settings_options_for, user.username))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!builtIn) {
                        Text(stringResource(R.string.settings_role), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (role in roles) {
                            DropdownMenuItem(
                                text = { Text(roleName(role)) },
                                leadingIcon = { if (user.role == role) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.width(24.dp)) },
                                onClick = { menu = false; if (user.role != role) onRole(role) },
                            )
                        }
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_change_password)) },
                        leadingIcon = { Icon(Icons.Outlined.Key, null) }, onClick = { menu = false; onPassword() })
                    if (!builtIn) {
                        HorizontalDivider()
                        DestructiveMenuItem(stringResource(R.string.settings_remove_user), Icons.Outlined.Delete) { menu = false; onRemove() }
                    }
                }
            }
        }
    }
}

/** A new user: name, password and role, in one form. */
@Composable
private fun AddUserDialog(roles: List<String>, onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("viewer") }
    val ready = name.isNotBlank() && password.length >= 12
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_add_user)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_username)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                PasswordField(password, { password = it }, stringResource(R.string.settings_password_min))
                SectionCard {
                    ChoiceRow(stringResource(R.string.settings_role), roles.map { it to roleName(it) }, role, { role = it })
                }
                Text(stringResource(if (role == "admin") R.string.settings_admin_explained else R.string.settings_viewer_explained),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name.trim(), password, role) }, enabled = ready) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A new password for a user, at least 12 characters. */
@Composable
private fun PasswordDialog(username: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_new_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_password_for, username), style = MaterialTheme.typography.bodyMedium)
                PasswordField(password, { password = it }, stringResource(R.string.settings_password))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(password) }, enabled = password.length >= 12) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
