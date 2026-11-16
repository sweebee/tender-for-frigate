package nl.wiebenieuwenhuis.tender.ui.exports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nl.wiebenieuwenhuis.tender.api.FrigateException
import nl.wiebenieuwenhuis.tender.api.FrigateExport
import nl.wiebenieuwenhuis.tender.api.FrigateExportCase
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import java.io.File

/** An export on one of the servers. */
data class ExportEntry(val serverId: String, val export: FrigateExport) {
    val key: String get() = "$serverId/${export.id}"
}

/** A case on one of the servers: a folder of exports in Frigate's Exports (0.18 and later). */
data class ExportCaseEntry(val serverId: String, val exportCase: FrigateExportCase) {
    val key: String get() = "$serverId/${exportCase.id}"
}

/**
 * Changes made in this session that the server may not show yet, shared by every exports screen
 * so what changed shows at once and stays (as the iOS app's AppModel). Deleted and busy exports
 * are `AppModel.deletedExports` / `AppModel.busyExports`, keyed by export id.
 */
object ExportsState {
    val addedCases = mutableStateListOf<ExportCaseEntry>()
    /** Case keys. */
    val deletedCases = mutableStateMapOf<String, Boolean>()
    /** Case key → new name. */
    val renamedCases = mutableStateMapOf<String, String>()
    /** Export key → the case it was moved to ("" for none). */
    val movedExports = mutableStateMapOf<String, String>()
    /** Cases seen, by key, so a case's screen has its name at once. */
    val knownCases = mutableMapOf<String, ExportCaseEntry>()

    /** The export's case, as just moved here or in another exports screen. */
    fun caseId(of: ExportEntry): String? {
        movedExports[of.key]?.let { return it.ifEmpty { null } }
        return of.export.caseId
    }
}

/** Loads exports and cases of the servers in view, newest first. */
object ExportsLoader {
    suspend fun exports(connections: List<ServerConnection>): List<ExportEntry> = coroutineScope {
        connections.mapNotNull { conn -> conn.client?.let { conn.id to it } }
            .map { (id, c) -> async { runCatching { c.exports() }.getOrDefault(emptyList()).map { ExportEntry(id, it) } } }
            .awaitAll().flatten()
            .filter { AppModel.deletedExports[it.export.id] != true }
            .sortedByDescending { it.export.date }
    }

    suspend fun cases(connections: List<ServerConnection>): List<ExportCaseEntry> = coroutineScope {
        connections.mapNotNull { conn -> conn.client?.takeIf { it.servesCases }?.let { conn.id to it } }
            .map { (id, c) -> async { c.exportCases().map { ExportCaseEntry(id, it) } } }
            .awaitAll().flatten()
            .sortedByDescending { it.exportCase.createdAt }
    }
}

/** Actions shared by the grid and the player; each shows a spinner on the export while Frigate works. */
object ExportActions {
    suspend fun rename(entry: ExportEntry, name: String) {
        val client = AppModel.connection(entry.serverId)?.client ?: throw FrigateException.InvalidUrl()
        AppModel.busyExports[entry.export.id] = true
        try { client.renameExport(entry.export.id, name) } finally { AppModel.busyExports.remove(entry.export.id) }
    }

    suspend fun delete(entry: ExportEntry) {
        val client = AppModel.connection(entry.serverId)?.client ?: throw FrigateException.InvalidUrl()
        AppModel.busyExports[entry.export.id] = true
        try {
            client.deleteExport(entry.export.id)
            AppModel.deletedExports[entry.export.id] = true
        } finally {
            AppModel.busyExports.remove(entry.export.id)
        }
    }

    /** The export as a local file in the shared cache folder; `progress` gets 0…1 (or -1 while the size is unknown). */
    suspend fun download(entry: ExportEntry, progress: (Float) -> Unit): File {
        val client = AppModel.connection(entry.serverId)?.client ?: throw FrigateException.InvalidUrl()
        val base = entry.export.name.ifEmpty { entry.export.id }
        val file = FileShare.cacheFile("$base.mp4")
        client.download(client.exportFileUrl(entry.export), file) { got, total ->
            progress(if (total > 0) got.toFloat() / total else -1f)
        }
        return file
    }
}

/** One exports screen's state, kept while an export is played from it. */
class ExportsViewModel : ViewModel() {
    var entries by mutableStateOf<List<ExportEntry>>(emptyList()); private set
    /** The cases as last loaded; `cases` adds what was just changed here. */
    var loadedCases by mutableStateOf<List<ExportCaseEntry>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false)
    /** Case changes still on their way to the server. */
    var caseWork by mutableIntStateOf(0)
    var errorMessage by mutableStateOf<String?>(null)
    private var loadCount = 0

    val connections: List<ServerConnection> get() = AppModel.scopedConnections.filter { it.isOnline }
    /** Servers that have cases (Frigate 0.18 and later). */
    val caseServers: List<ServerConnection> get() = connections.filter { it.client?.servesCases == true }

    val cases: List<ExportCaseEntry>
        get() {
            val loaded = loadedCases.map { it.key }.toSet()
            return (ExportsState.addedCases.filter { it.key !in loaded } + loadedCases)
                .filter { ExportsState.deletedCases[it.key] != true }
                .map { c -> ExportsState.renamedCases[c.key]?.let { c.copy(exportCase = c.exportCase.copy(name = it)) } ?: c }
                .sortedByDescending { it.exportCase.createdAt }
        }

    fun exportsIn(c: ExportCaseEntry): List<ExportEntry> = entries.filter {
        it.serverId == c.serverId && ExportsState.caseId(it) == c.exportCase.id && AppModel.deletedExports[it.export.id] != true
    }

    suspend fun load() {
        val mine = ++loadCount
        val conns = connections
        val (e, c) = coroutineScope {
            val a = async { ExportsLoader.exports(conns) }
            val b = async { ExportsLoader.cases(conns) }
            a.await() to b.await()
        }
        if (mine != loadCount) return
        // Moves the server now shows are done.
        for (x in e) if (ExportsState.movedExports[x.key] == (x.export.caseId ?: "")) ExportsState.movedExports.remove(x.key)
        // Case changes the server now shows are done.
        val ids = c.map { it.key }.toSet()
        ExportsState.addedCases.removeAll { it.key in ids }
        ExportsState.deletedCases.keys.filter { it !in ids }.forEach { ExportsState.deletedCases.remove(it) }
        for (x in c) if (ExportsState.renamedCases[x.key] == x.exportCase.name) ExportsState.renamedCases.remove(x.key)
        for (x in c) ExportsState.knownCases[x.key] = x
        entries = e
        loadedCases = c
        loading = false
    }
}
