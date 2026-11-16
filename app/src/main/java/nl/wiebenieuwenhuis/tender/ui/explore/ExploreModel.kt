package nl.wiebenieuwenhuis.tender.ui.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.api.EventSort
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.FrigateEvent
import nl.wiebenieuwenhuis.tender.api.SearchSource
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.Prefs
import nl.wiebenieuwenhuis.tender.app.ServerConnection
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.roundToInt

/** A tracked object on one of the servers. */
data class ExploreEntry(val serverId: String, val event: FrigateEvent) {
    val key: String get() = "$serverId/${event.id}"
}

/** Filters of the Explore tab, like Frigate's Explore page. */
data class ExploreFilter(
    val cameras: Set<String> = emptySet(),
    val labels: Set<String> = emptySet(),
    val zones: Set<String> = emptySet(),
    val subLabels: Set<String> = emptySet(),
    /** Local midnight of the day shown, in epoch milliseconds. */
    val day: Long? = null,
    val sort: EventSort = EventSort.Newest,
    /** Part of a recognized license plate. */
    val plate: String = "",
    /** Semantic search: text to look for, and where (the pictures, the descriptions or both). */
    val query: String = "",
    val sources: Set<SearchSource> = SearchSource.entries.toSet(),
    /** Semantic search for objects that look like this one (Frigate's "Find similar"). */
    val similar: ExploreEntry? = null,
) {
    val isActive: Boolean get() = this != ExploreFilter()
    /** A semantic search: results come best match first, from the servers that have it on. */
    val isSearch: Boolean get() = query.isNotEmpty() || similar != null
    /** Rows per label until a label is picked or the order changes; then one grid. */
    val showsGrid: Boolean get() = labels.isNotEmpty() || sort != EventSort.Newest || plate.isNotEmpty() || isSearch
    /** Only the label rows' shortcut endpoint can be used. */
    val isPlain: Boolean get() = plate.isEmpty() && cameras.isEmpty() && zones.isEmpty() && subLabels.isEmpty() && day == null

    /** The day as (after, before) in epoch seconds. */
    val dayRange: Pair<Double?, Double?>
        get() {
            val d = day ?: return null to null
            val cal = Calendar.getInstance().apply { timeInMillis = d; add(Calendar.DAY_OF_YEAR, 1) }
            return d / 1000.0 to cal.timeInMillis / 1000.0
        }
}

/** How well a search result matched, as Frigate shows it: a percentage, by picture or by description. */
data class SearchMatch(val byDescription: Boolean, val percent: Int)

fun searchMatch(e: FrigateEvent, similarity: Boolean): SearchMatch? {
    val d = e.searchDistance ?: return null
    // Frigate: similarity distances are cosine distances; text distances are normalised z-scores.
    val confidence = if (similarity) 1 - d else 1 / (1 + exp(d))
    return SearchMatch(e.searchSource == "description", (confidence.coerceIn(0.0, 1.0) * 100).roundToInt())
}

/** Matching license plates loosely: "hk008" finds "HK-008-X". */
object PlateSearch {
    /** Letters and digits only, upper case. */
    fun normalized(text: String): String = text.uppercase().filter { it.isLetterOrDigit() }

    /**
     * Regex for Frigate's recognized_license_plate filter: case-insensitive, contains the
     * characters in order with optional dashes or spaces between them.
     */
    fun pattern(text: String): String = "(?i).*" + normalized(text).map { it.toString() }.joinToString("[- ]?") + ".*"
}

/** Explore's "one grid of everything" default, kept between launches (Frigate's "Unfiltered grid"). */
object ExplorePrefs {
    var gridByDefault by mutableStateOf(Prefs.bool("exploreGrid", false))
        private set

    fun toggleGrid() {
        gridByDefault = !gridByDefault
        Prefs.setBool("exploreGrid", gridByDefault)
    }
}

data class LabelRow(val label: String, val count: Int?, val entries: List<ExploreEntry>)

/** Explore's state, kept while objects are opened from it and while switching tabs. */
class ExploreViewModel : ViewModel() {
    var filter by mutableStateOf(ExploreFilter())
    var searchText by mutableStateOf("")
    var rows by mutableStateOf<List<LabelRow>>(emptyList()); private set
    var grid by mutableStateOf<List<ExploreEntry>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    /** Pull to refresh: only that shows the pull indicator. */
    var refreshing by mutableStateOf(false); private set
    var canLoadMore by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    var knownLabels by mutableStateOf<List<String>>(emptyList()); private set
    var knownSubLabels by mutableStateOf<List<String>>(emptyList()); private set
    var knownPlates by mutableStateOf<List<String>>(emptyList()); private set

    private var started = false
    private var loadJob: Job? = null
    private var loadedKey: String? = null
    private var loadedAt = 0L
    private val pageSize = 50

    /** Starts with "Find similar" for this object, once. */
    fun start(similar: ExploreEntry?) {
        if (started) return
        started = true
        if (similar != null) filter = ExploreFilter(similar = similar)
    }

    val connections: List<ServerConnection> get() = AppModel.scopedConnections.filter { it.isOnline }

    val showsGrid: Boolean get() = ExplorePrefs.gridByDefault || filter.showsGrid

    fun filterKey(): String = "${filter.copy(similar = null)}|${filter.similar?.key}|${ExplorePrefs.gridByDefault}"

    fun loadKey(): String =
        "${filter.copy(similar = null)}|${filter.similar?.key}|${ExplorePrefs.gridByDefault}|${connections.map { it.id }}"

    /** Loads for the current filter; coming back to the same filter within a minute keeps what is there. */
    fun load(key: String, force: Boolean = false) {
        if (!force && key == loadedKey && System.currentTimeMillis() - loadedAt < 60_000) return
        loadedKey = key
        loadJob?.cancel()
        loadJob = viewModelScope.launch { reload() }
    }

    fun refresh() {
        refreshing = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try { reload() } finally { refreshing = false }
        }
    }

    private suspend fun reload() {
        loading = true
        loadError = null
        try {
            val f = filter
            if (f.isSearch) {
                grid = semanticResults(f)
                // Frigate's search returns its best matches at once; there are no further pages.
                canLoadMore = false
            } else if (showsGrid) {
                val (after, before) = f.dayRange
                val result = search(f, f.labels.takeIf { it.isNotEmpty() }?.toList(), before, after, pageSize)
                grid = result
                // Frigate pages by time; sorting by score shows the best matches only.
                canLoadMore = f.sort != EventSort.Score && result.size >= pageSize
            } else {
                rows = loadRows(f)
            }
            loadedAt = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    fun loadOptions() {
        viewModelScope.launch {
            val labels = mutableSetOf<String>(); val subs = mutableSetOf<String>(); val plates = mutableSetOf<String>()
            coroutineScope {
                connections.mapNotNull { it.client }.map { c ->
                    async {
                        Triple(runCatching { c.trackedLabels() }.getOrDefault(emptyList()),
                            runCatching { c.subLabels() }.getOrDefault(emptyList()),
                            runCatching { c.recognizedPlates() }.getOrDefault(emptyList()))
                    }
                }.awaitAll()
            }.forEach { (l, s, p) -> labels += l; subs += s; plates += p }
            knownLabels = labels.toList()
            knownSubLabels = subs.toList()
            knownPlates = plates.sorted()
        }
    }

    private data class Target(val conn: ServerConnection, val client: FrigateClient, val cameras: List<String>?)

    /** Servers and the cameras to ask each for; a server the camera filter excludes is left out. */
    private fun targets(f: ExploreFilter): List<Target> = connections.mapNotNull { conn ->
        val client = conn.client ?: return@mapNotNull null
        if (f.cameras.isEmpty()) return@mapNotNull Target(conn, client, null)
        val cams = conn.cameras.map { it.name }.filter { it in f.cameras }
        if (cams.isEmpty()) null else Target(conn, client, cams)
    }

    private suspend fun search(f: ExploreFilter, labels: List<String>?, before: Double?, after: Double?, limit: Int): List<ExploreEntry> {
        val zones = f.zones.takeIf { it.isNotEmpty() }?.toList()
        val subs = f.subLabels.takeIf { it.isNotEmpty() }?.toList()
        val plate = f.plate.takeIf { it.isNotEmpty() }?.let { PlateSearch.pattern(it) }
        val all = coroutineScope {
            targets(f).map { t ->
                async {
                    t.client.searchEvents(t.cameras, labels, zones, subs, plate, after, before, f.sort, limit)
                        .map { ExploreEntry(t.conn.id, it) }
                }
            }.awaitAll().flatten()
        }
        return sorted(all.distinctBy { it.key }, f.sort)
    }

    /** Semantic search on the servers that have it on (similar objects: on the object's own server), best match first. */
    private suspend fun semanticResults(f: ExploreFilter): List<ExploreEntry> {
        val (after, before) = f.dayRange
        val labels = f.labels.takeIf { it.isNotEmpty() }?.toList()
        val zones = f.zones.takeIf { it.isNotEmpty() }?.toList()
        val subs = f.subLabels.takeIf { it.isNotEmpty() }?.toList()
        val plate = f.plate.takeIf { it.isNotEmpty() }?.let { PlateSearch.pattern(it) }
        val servers = targets(f).filter { t ->
            f.similar?.let { t.conn.id == it.serverId } ?: (t.conn.config?.semanticSearch == true)
        }
        val all = coroutineScope {
            servers.map { t ->
                async {
                    t.client.semanticSearch(
                        query = if (f.similar == null) f.query else null, similarTo = f.similar?.event?.id,
                        sources = f.sources, cameras = t.cameras, labels = labels, zones = zones, subLabels = subs,
                        plate = plate, after = after, before = before,
                    ).map { ExploreEntry(t.conn.id, it) }
                }
            }.awaitAll().flatten()
        }
        val similarity = f.similar != null
        return all.distinctBy { it.key }.sortedByDescending { searchMatch(it.event, similarity)?.percent ?: 0 }
    }

    private fun sorted(list: List<ExploreEntry>, sort: EventSort): List<ExploreEntry> = when (sort) {
        EventSort.Newest -> list.sortedByDescending { it.event.startTime }
        EventSort.Oldest -> list.sortedBy { it.event.startTime }
        EventSort.Score -> list.sortedByDescending { it.event.bestScore ?: 0.0 }
    }

    private suspend fun loadRows(f: ExploreFilter): List<LabelRow> {
        val entries = mutableMapOf<String, MutableList<ExploreEntry>>()
        val counts = mutableMapOf<String, Int>()
        var withCounts = false
        if (f.isPlain) {
            withCounts = true
            // One request per server: the latest objects of each label plus the totals.
            val parts = coroutineScope {
                targets(f).map { t -> async { t to t.client.exploreEvents(limit = 10) } }.awaitAll()
            }
            for ((t, items) in parts) {
                for ((event, _) in items) entries.getOrPut(event.label) { mutableListOf() }.add(ExploreEntry(t.conn.id, event))
                // event_count repeats on every item of a label; add it once per server.
                items.groupBy { it.first.label }.forEach { (label, list) ->
                    counts[label] = (counts[label] ?: 0) + (list.first().second ?: 0)
                }
            }
        } else {
            val (after, before) = f.dayRange
            // Ask the servers which labels exist; the filter options may not have loaded yet.
            val found = knownLabels.toMutableSet()
            for (t in targets(f)) found += runCatching { t.client.trackedLabels() }.getOrDefault(emptyList())
            val parts = coroutineScope {
                found.map { label -> async { label to search(f, listOf(label), before, after, 10) } }.awaitAll()
            }
            for ((label, list) in parts) if (list.isNotEmpty()) entries[label] = list.toMutableList()
        }
        return entries.map { (label, list) ->
            LabelRow(label, if (withCounts) counts[label] ?: 0 else null, list.sortedByDescending { it.event.startTime }.take(10))
        }.sortedWith { a, b ->
            val ca = a.count; val cb = b.count
            if (ca != null && cb != null && ca != cb) cb.compareTo(ca)
            else (b.entries.firstOrNull()?.event?.startTime ?: 0.0).compareTo(a.entries.firstOrNull()?.event?.startTime ?: 0.0)
        }
    }

    fun loadMore() {
        if (loading || !canLoadMore) return
        val last = grid.lastOrNull()?.event?.startTime ?: return
        val f = filter
        loadJob = viewModelScope.launch {
            loading = true
            try {
                val (after, before) = f.dayRange
                val labels = f.labels.takeIf { it.isNotEmpty() }?.toList()
                val more = runCatching {
                    if (f.sort == EventSort.Oldest) search(f, labels, before, last, pageSize)
                    else search(f, labels, last, after, pageSize)
                }.getOrNull()
                if (more != null && f == filter) {
                    val known = grid.map { it.key }.toSet()
                    val fresh = more.filter { it.key !in known }
                    grid = grid + fresh
                    canLoadMore = fresh.isNotEmpty()
                }
            } finally {
                loading = false
            }
        }
    }

    // MARK: Options for the filters

    fun labelOptions(): List<String> =
        (knownLabels + rows.map { it.label } + filter.labels).distinct().sortedBy { Format.labelName(it).lowercase() }

    fun subLabelOptions(): List<String> = (knownSubLabels + filter.subLabels).distinct().sorted()

    fun zoneOptions(): List<String> {
        val zones = mutableSetOf<String>()
        for (conn in connections) zones += conn.zones(filter.cameras)
        zones += filter.zones
        return zones.sorted()
    }
}
