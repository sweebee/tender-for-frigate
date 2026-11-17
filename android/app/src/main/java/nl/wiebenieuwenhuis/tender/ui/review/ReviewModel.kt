package nl.wiebenieuwenhuis.tender.ui.review

import nl.wiebenieuwenhuis.tender.app.Prefs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.api.Severity
import nl.wiebenieuwenhuis.tender.api.nowSeconds
import nl.wiebenieuwenhuis.tender.api.toDate
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import java.util.Calendar
import kotlin.coroutines.cancellation.CancellationException

/** The period Review shows: like Frigate, the last 24 hours by default, or one day (local midnight, epoch ms). */
sealed class ReviewRange {
    data object Last24Hours : ReviewRange()
    data class Day(val start: Long) : ReviewRange()

    /** After and before, in epoch seconds. */
    val bounds: Pair<Double, Double>
        get() {
            val now = nowSeconds()
            return when (this) {
                Last24Hours -> (now - 86400) to now
                is Day -> {
                    val end = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
                    start / 1000.0 to minOf(end / 1000.0, now)
                }
            }
        }

    val title: String get() = when (this) {
        Last24Hours -> L(R.string.review_last_24_hours)
        is Day -> Format.dayTitle((start / 1000.0).toDate())
    }
}

enum class ReviewKind { Alert, Detection, Motion }

/** Local midnight of the day a moment (epoch seconds) falls on, in epoch milliseconds. */
fun startOfDay(epochSeconds: Double): Long = Calendar.getInstance().apply {
    timeInMillis = (epochSeconds * 1000).toLong()
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** State of the Review tab; kept while an item or its recordings are open on top of it. */
class ReviewModel : ViewModel() {
    var kind by mutableStateOf(ReviewKind.Alert)
    var cameraFilter by mutableStateOf(emptySet<String>())
    var labelFilter by mutableStateOf(emptySet<String>())
    var zoneFilter by mutableStateOf(emptySet<String>())
    private var unreviewedOnlyState = mutableStateOf(Prefs.bool("reviewUnreviewedOnly", true))
    /** Remembered between launches, as on iOS. */
    var unreviewedOnly: Boolean
        get() = unreviewedOnlyState.value
        set(v) { unreviewedOnlyState.value = v; Prefs.setBool("reviewUnreviewedOnly", v) }
    var range by mutableStateOf<ReviewRange>(ReviewRange.Last24Hours)

    var entries by mutableStateOf<List<ReviewEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var canLoadMore by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
    /** "Mark all reviewed" is going through the pages on the server. */
    var markingAll by mutableStateOf(false)
        private set
    /** Entries being marked reviewed, by key. */
    val marking = mutableStateMapOf<String, Boolean>()

    var selecting by mutableStateOf(false)
        private set
    val selected = mutableStateMapOf<String, Boolean>()
    var bulkMarking by mutableStateOf(false)
        private set
    var bulkDeleting by mutableStateOf(false)
        private set

    val actions = ReviewActions(viewModelScope)

    private var loadedKey: String? = null
    private var loads = 0
    private val pageSize = 60

    val connections: List<ServerConnection> get() = AppModel.scopedConnections.filter { it.isOnline }

    /** What the list shows; a change empties it until the new list is in. */
    private val contentKey: String
        get() = listOf(kind, cameraFilter.sorted(), labelFilter.sorted(), zoneFilter.sorted(), unreviewedOnly, range).joinToString("|")

    val filterKey: String
        get() = contentKey + "|" + AppModel.scopedConnections.joinToString(",") { "${it.id}${it.isOnline}" }

    private var loadedContentKey: String? = null

    /** What the list shows: deleted items left out, reviewed ones too when only unreviewed are shown. */
    val shown: List<ReviewEntry>
        get() = entries.filter { ReviewSession.deletedIds[it.item.id] != true && !(unreviewedOnly && it.item.isReviewed) }

    // MARK: Loading

    private suspend fun fetch(before: Double?): List<ReviewEntry> {
        if (kind == ReviewKind.Motion) return emptyList()
        val severity = if (kind == ReviewKind.Alert) Severity.Alert else Severity.Detection
        val cams = cameraFilter.takeIf { it.isNotEmpty() }
        val labels = labelFilter.takeIf { it.isNotEmpty() }?.toList()
        val zones = zoneFilter.takeIf { it.isNotEmpty() }?.toList()
        val reviewed = if (unreviewedOnly) false else null
        val (after, end) = range.bounds
        return coroutineScope {
            connections.mapNotNull { conn ->
                val client = conn.client ?: return@mapNotNull null
                val known = conn.cameras.map { it.name }.toSet()
                val serverCams = cams?.filter { it in known }
                if (serverCams != null && serverCams.isEmpty()) return@mapNotNull null
                async {
                    client.reviews(serverCams, labels, zones, severity, reviewed, after, before ?: end, pageSize)
                        .map { ReviewEntry(conn.id, it) }
                }
            }.awaitAll().flatten().sortedByDescending { it.item.startTime }
        }
    }

    /** Loads the list; with the same filters as last time the old list stays until the new one is in. */
    suspend fun reload(silent: Boolean = false) {
        val key = filterKey
        val quiet = silent || (key == loadedKey && entries.isNotEmpty())
        if (key != loadedKey) selected.clear()
        if (contentKey != loadedContentKey) {
            // Other items asked for (another kind, filter or day): not the old list under the new choice.
            entries = emptyList()
            canLoadMore = false
        }
        val content = contentKey
        // Only the latest load writes the list: a slow one must not overwrite newer filters' items.
        val generation = ++loads
        if (!quiet) loading = true
        loadError = null
        // The counts on the segments and the tab badge come from each server's summary;
        // refresh it too, so items reviewed elsewhere (e.g. in Frigate's web UI) drop out.
        AppModel.scope.launch { AppModel.refreshAll() }
        try {
            var result = fetch(null)
            if (generation != loads) return
            // Frigate's list can lag a moment behind items just marked reviewed.
            result = result.map { if (AppModel.reviewedIds[it.item.id] == true) it.copy(item = it.item.copy(hasBeenReviewed = true)) else it }
            if (unreviewedOnly) result = result.filter { !it.item.hasBeenReviewed }
            entries = result
            loadedKey = key
            loadedContentKey = content
            canLoadMore = result.size >= pageSize
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation == loads) loadError = e.message ?: e.toString()
        } finally {
            if (generation == loads) loading = false
        }
    }

    fun loadMore() {
        val oldest = entries.lastOrNull()?.item?.startTime ?: return
        if (loadingMore) return
        viewModelScope.launch {
            loadingMore = true
            try {
                val more = fetch(oldest)
                val known = entries.map { it.key }.toSet()
                val fresh = more.filter { it.key !in known }
                entries = entries + fresh
                canLoadMore = fresh.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: e.toString()
            } finally {
                loadingMore = false
            }
        }
    }

    // MARK: Marking reviewed

    private suspend fun mark(list: List<ReviewEntry>) {
        for ((serverId, items) in list.filter { !it.item.isReviewed }.groupBy { it.serverId }) {
            val conn = AppModel.connection(serverId) ?: continue
            val client = conn.client ?: continue
            try {
                client.markViewed(items.map { it.item.id })
                conn.noteReviewed(items.map { it.item })
                items.forEach { AppModel.reviewedIds[it.item.id] = true }
                val keys = items.map { it.key }.toSet()
                entries = entries.map { if (it.key in keys) it.copy(item = it.item.copy(hasBeenReviewed = true)) else it }
                AppModel.scope.launch { conn.refresh() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: e.toString()
            }
        }
    }

    fun markReviewed(list: List<ReviewEntry>) {
        val todo = list.filter { !it.item.isReviewed && marking[it.key] != true }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            todo.forEach { marking[it.key] = true }
            try { mark(todo) } finally { todo.forEach { marking.remove(it.key) } }
        }
    }

    /**
     * Marks every unreviewed item that matches the current filters, not only the loaded page,
     * and refreshes the counts (again a moment later: Frigate's summary can lag behind).
     */
    fun markAllReviewed() {
        if (kind == ReviewKind.Motion || markingAll) return
        viewModelScope.launch {
            markingAll = true
            try {
                val severity = if (kind == ReviewKind.Alert) Severity.Alert else Severity.Detection
                val cams = cameraFilter.takeIf { it.isNotEmpty() }
                val labels = labelFilter.takeIf { it.isNotEmpty() }?.toList()
                val zones = zoneFilter.takeIf { it.isNotEmpty() }?.toList()
                for (conn in connections) {
                    val client = conn.client ?: continue
                    val known = conn.cameras.map { it.name }.toSet()
                    val serverCams = cams?.filter { it in known }
                    if (serverCams != null && serverCams.isEmpty()) continue
                    try {
                        // Pages of unreviewed items until none are left (bounded, to be safe).
                        for (page in 0 until 20) {
                            val items = client.reviews(serverCams, labels, zones, severity, reviewed = false, limit = 500)
                            val ids = items.map { it.id }.filter { AppModel.reviewedIds[it] != true }
                            if (ids.isEmpty()) break
                            ids.chunked(100).forEach { client.markViewed(it) }
                            conn.noteReviewed(items.filter { it.id in ids })
                            ids.forEach { AppModel.reviewedIds[it] = true }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        loadError = e.message ?: e.toString()
                    }
                }
                entries = entries.map { it.copy(item = it.item.copy(hasBeenReviewed = true)) }
                AppModel.refreshAll()
                delay(2000)
                AppModel.refreshAll()
            } finally {
                markingAll = false
            }
        }
    }

    // MARK: Selection

    fun startSelecting(first: ReviewEntry? = null) {
        selecting = true
        selected.clear()
        first?.let { selected[it.key] = true }
    }

    fun endSelecting() { selecting = false; selected.clear() }

    fun toggle(entry: ReviewEntry) {
        if (selected[entry.key] == true) selected.remove(entry.key) else selected[entry.key] = true
    }

    fun selectAll() { shown.forEach { selected[it.key] = true } }

    val selectedEntries: List<ReviewEntry> get() = shown.filter { selected[it.key] == true }

    /** Only admins may delete; with several servers, an admin on each of the selected items' servers. */
    val canDeleteSelection: Boolean
        get() = selectedEntries.isNotEmpty() && selectedEntries.map { it.serverId }.distinct().all { AppModel.connection(it)?.isAdmin == true }

    fun markSelectedReviewed() {
        val list = selectedEntries
        if (list.isEmpty() || bulkMarking) return
        viewModelScope.launch {
            bulkMarking = true
            try { mark(list) } finally { bulkMarking = false }
            endSelecting()
        }
    }

    fun deleteSelected() {
        val list = selectedEntries
        if (list.isEmpty() || bulkDeleting) return
        viewModelScope.launch {
            bulkDeleting = true
            try {
                for ((serverId, items) in list.groupBy { it.serverId }) {
                    val conn = AppModel.connection(serverId) ?: continue
                    val client = conn.client ?: continue
                    try {
                        client.deleteReviews(items.map { it.item.id })
                        items.forEach { ReviewSession.deletedIds[it.item.id] = true }
                        AppModel.scope.launch { conn.refresh() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        actions.error = e.message ?: e.toString()
                    }
                }
            } finally {
                bulkDeleting = false
            }
            endSelecting()
        }
    }
}
