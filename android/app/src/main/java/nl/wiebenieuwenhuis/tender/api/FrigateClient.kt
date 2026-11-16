package nl.wiebenieuwenhuis.tender.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.wiebenieuwenhuis.tender.R
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor

sealed class FrigateException(message: String) : IOException(message) {
    class InvalidUrl : FrigateException(L(R.string.error_invalid_url))
    class Http(val code: Int, val detail: String?) :
        FrigateException(L(R.string.error_http, code, detail?.let { ": $it" } ?: ""))
    class Unauthorized : FrigateException(L(R.string.error_unauthorized))
    class NotFrigate : FrigateException(L(R.string.error_not_frigate))
}

private val JSON_TYPE = "application/json".toMediaType()

enum class EventSort(val raw: String) { Newest("date_desc"), Oldest("date_asc"), Score("score_desc") }

/** Where a semantic search looks: the objects' pictures, their descriptions, or both. */
enum class SearchSource(val raw: String) { Thumbnail("thumbnail"), Description("description") }

data class ExportItem(val camera: String, val start: Double, val end: Double, val name: String)

/** Thin client for the Frigate HTTP API of one server, as `FrigateClient.swift`. */
class FrigateClient(val baseUrl: HttpUrl) {
    /** The server's version ("0.18.2-cfa08c5"), once connected. */
    @Volatile var version: String? = null
    val http: OkHttpClient get() = Http.client

    /** Builds a URL relative to the server root, e.g. `api/review`. */
    fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val b = baseUrl.newBuilder()
        val base = baseUrl.encodedPath.trimEnd('/')
        b.encodedPath("$base/")
        path.removePrefix("/").split("?").first().split("/").filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        for ((k, v) in query.entries.sortedBy { it.key }) if (v != null) b.addQueryParameter(k, v)
        return b.build()
    }

    fun urlString(path: String, query: Map<String, String?> = emptyMap()) = url(path, query).toString()

    // MARK: Raw requests

    suspend fun data(path: String, query: Map<String, String?> = emptyMap(), timeoutSeconds: Long? = null): ByteArray {
        val req = Request.Builder().url(url(path, query)).header("Cache-Control", "no-cache").build()
        return perform(req, timeoutSeconds)
    }

    suspend fun json(path: String, query: Map<String, String?> = emptyMap(), timeoutSeconds: Long? = null): JsonElement {
        val bytes = data(path, query, timeoutSeconds)
        return withContext(Dispatchers.Default) { FrigateJson.parseToJsonElement(bytes.decodeToString()) }
    }

    suspend fun <T> list(path: String, query: Map<String, String?> = emptyMap(), map: (JsonElement) -> T?): List<T> =
        (json(path, query) as? JsonArray)?.mapNotNull(map) ?: emptyList()

    suspend fun post(path: String, body: Any?, query: Map<String, String?> = emptyMap(), timeoutSeconds: Long? = null): ByteArray =
        send("POST", path, body ?: emptyMap<String, Any>(), query, timeoutSeconds)

    /** PUT, PATCH, DELETE or POST with an optional JSON body. */
    suspend fun send(method: String, path: String, body: Any?, query: Map<String, String?> = emptyMap(), timeoutSeconds: Long? = null): ByteArray {
        val rb: RequestBody? = when {
            body != null -> jsonOf(body).toString().toRequestBody(JSON_TYPE)
            method == "POST" || method == "PUT" || method == "PATCH" -> "".toRequestBody(null)
            else -> null
        }
        val req = Request.Builder().url(url(path, query))
            // Frigate rejects cookie-authenticated writes without this header.
            .header("X-CSRF-TOKEN", "1")
            .method(method, rb)
            .build()
        return perform(req, timeoutSeconds)
    }

    suspend fun sendRaw(method: String, path: String, body: RequestBody?, query: Map<String, String?> = emptyMap(), timeoutSeconds: Long? = null): ByteArray {
        val req = Request.Builder().url(url(path, query)).header("X-CSRF-TOKEN", "1").method(method, body).build()
        return perform(req, timeoutSeconds)
    }

    suspend fun perform(req: Request, timeoutSeconds: Long? = null): ByteArray {
        val client = timeoutSeconds?.let { Http.withTimeout(it) } ?: http
        client.newCall(req).await().use { res ->
            val bytes = withContext(Dispatchers.IO) { res.body.bytes() }
            if (res.code == 401 || res.code == 403) throw FrigateException.Unauthorized()
            if (!res.isSuccessful) {
                val obj = runCatching { FrigateJson.parseToJsonElement(bytes.decodeToString()) }.getOrNull()
                throw FrigateException.Http(res.code, obj["detail"].str() ?: obj["message"].str())
            }
            return bytes
        }
    }

    private fun successOrThrow(bytes: ByteArray) {
        val obj = runCatching { FrigateJson.parseToJsonElement(bytes.decodeToString()) }.getOrNull()
        if (obj["success"].bool() == false) throw FrigateException.Http(400, obj["message"].str())
    }

    // MARK: Auth and info

    suspend fun version(timeoutSeconds: Long = 6): String {
        val s = data("api/version", timeoutSeconds = timeoutSeconds).decodeToString().trim()
        if (s.isEmpty() || !s.first().isDigit() || s.length >= 40) throw FrigateException.NotFrigate()
        return s
    }

    suspend fun login(user: String, password: String) {
        try {
            post("api/login", mapOf("user" to user, "password" to password))
        } catch (e: FrigateException.Http) {
            if (e.code == 400 || e.code == 401) throw FrigateException.Unauthorized()
            throw e
        }
    }

    suspend fun config(): FrigateConfig = FrigateConfig(json("api/config") as? JsonObject ?: throw FrigateException.NotFrigate())
    suspend fun rawConfig(): JsonObject = json("api/config") as? JsonObject ?: throw FrigateException.NotFrigate()
    suspend fun stats(): FrigateStats = FrigateStats(json("api/stats") as? JsonObject ?: JsonObject(emptyMap()))

    // MARK: Review

    suspend fun reviews(
        cameras: List<String>? = null, labels: List<String>? = null, zones: List<String>? = null, severity: Severity? = null,
        reviewed: Boolean? = null, after: Double? = null, before: Double? = null, limit: Int = 100,
    ): List<ReviewItem> {
        val q = mutableMapOf<String, String?>("limit" to limit.toString())
        if (!cameras.isNullOrEmpty()) q["cameras"] = cameras.joinToString(",")
        if (!labels.isNullOrEmpty()) q["labels"] = labels.joinToString(",")
        if (!zones.isNullOrEmpty()) q["zones"] = zones.joinToString(",")
        severity?.let { q["severity"] = it.raw }
        reviewed?.let { q["reviewed"] = if (it) "1" else "0" }
        after?.let { q["after"] = it.toString() }
        before?.let { q["before"] = it.toString() }
        return list("api/review", q, ReviewItem::from)
    }

    suspend fun reviewSummary(): DayCounts? = DayCounts.from(json("api/review/summary")["last24Hours"])

    /** Days ("yyyy-MM-dd") that have recordings, in the user's time zone. */
    suspend fun recordingDays(cameras: List<String>? = null): Set<String> {
        val q = mutableMapOf<String, String?>("timezone" to TimeZone.getDefault().id)
        if (!cameras.isNullOrEmpty()) q["cameras"] = cameras.joinToString(",")
        return json("api/recordings/summary", q).obj()?.filter { it.value.bool() == true }?.keys ?: emptySet()
    }

    /** Alert and detection counts per day ("yyyy-MM-dd"). */
    suspend fun reviewDays(cameras: List<String>? = null): Map<String, DayCounts> {
        val q = mutableMapOf<String, String?>("timezone" to TimeZone.getDefault().id)
        if (!cameras.isNullOrEmpty()) q["cameras"] = cameras.joinToString(",")
        return json("api/review/summary", q).obj()?.filterKeys { it != "last24Hours" }
            ?.mapNotNull { (k, v) -> DayCounts.from(v)?.let { k to it } }?.toMap() ?: emptyMap()
    }

    suspend fun markViewed(ids: List<String>) {
        if (ids.isEmpty()) return
        post("api/reviews/viewed", mapOf("ids" to ids, "reviewed" to true))
    }

    suspend fun deleteReviews(ids: List<String>) { post("api/reviews/delete", mapOf("ids" to ids)) }

    suspend fun motionActivity(cameras: List<String>, after: Double, before: Double, scale: Int): List<MotionActivity> =
        list("api/review/activity/motion", mapOf(
            "cameras" to cameras.joinToString(","), "after" to after.toLong().toString(),
            "before" to before.toLong().toString(), "scale" to scale.toString(),
        ), MotionActivity::from)

    /** Motion per camera. Frigate merges cameras when several are requested at once. */
    suspend fun motionActivityByCamera(cameras: List<String>, after: Double, before: Double, scale: Int): Map<String, List<MotionActivity>> =
        coroutineScope {
            cameras.map { cam -> async { cam to (runCatching { motionActivity(listOf(cam), after, before, scale) }.getOrNull() ?: emptyList()) } }
                .awaitAll().toMap()
        }

    // MARK: Exports

    suspend fun exports(): List<FrigateExport> = list("api/exports", map = FrigateExport::from).sortedByDescending { it.date }

    fun exportFileUrl(export: FrigateExport): String = urlString(FrigateExport.servedPath(export.videoPath))
    fun exportThumbnailUrl(export: FrigateExport): String? =
        export.thumbPath.takeIf { it.isNotEmpty() }?.let { urlString(FrigateExport.servedPath(it)) }

    /** Asks Frigate to export a span of recordings; it makes the file in the background. */
    suspend fun startExport(camera: String, start: Double, end: Double, name: String, caseId: String? = null) {
        val body = mutableMapOf<String, Any?>("playback" to "realtime", "source" to "recordings", "name" to name)
        if (caseId != null) body["export_case_id"] = caseId
        post("api/export/$camera/start/${floor(start).toLong()}/end/${ceil(end).toLong()}", body)
    }

    /** Exports from several cameras at once, all in one case (Frigate 0.18 and later). */
    suspend fun startExports(items: List<ExportItem>, caseId: String?, newCaseName: String?): Int {
        val body = mutableMapOf<String, Any?>("items" to items.map {
            mapOf("camera" to it.camera, "start_time" to floor(it.start), "end_time" to ceil(it.end), "friendly_name" to it.name)
        })
        if (caseId != null) body["export_case_id"] = caseId else if (newCaseName != null) body["new_case_name"] = newCaseName
        val data = post("api/exports/batch", body)
        val queued = runCatching { FrigateJson.parseToJsonElement(data.decodeToString())["export_ids"].arr()?.size }.getOrNull() ?: items.size
        if (queued == 0) throw FrigateException.Http(400, L(R.string.no_recordings_in_span))
        return queued
    }

    suspend fun exportCases(): List<FrigateExportCase> = runCatching { list("api/cases", map = FrigateExportCase::from) }.getOrDefault(emptyList())
    suspend fun createExportCase(name: String): FrigateExportCase =
        FrigateExportCase.from(FrigateJson.parseToJsonElement(post("api/cases", mapOf("name" to name)).decodeToString()))
            ?: throw FrigateException.NotFrigate()
    suspend fun renameExportCase(id: String, name: String) { send("PATCH", "api/cases/$id", mapOf("name" to name)) }
    suspend fun deleteExportCase(id: String, deletingExports: Boolean = false) {
        send("DELETE", "api/cases/$id", null, mapOf("delete_exports" to if (deletingExports) "true" else null))
    }
    suspend fun moveExports(ids: List<String>, caseId: String?) {
        post("api/exports/reassign", mapOf("ids" to ids, "export_case_id" to caseId))
    }
    suspend fun renameExport(id: String, name: String) {
        try {
            send("PATCH", "api/export/$id/rename", mapOf("name" to name))
        } catch (e: FrigateException.Http) {
            if (e.code != 404 && e.code != 405) throw e
            send("PATCH", "api/export/$id/$name", emptyMap<String, Any>())
        }
    }
    suspend fun deleteExport(id: String) {
        try {
            post("api/exports/delete", mapOf("ids" to listOf(id)))
        } catch (e: FrigateException.Http) {
            if (e.code != 404 && e.code != 405) throw e
            send("DELETE", "api/export/$id", null)
        }
    }

    /** Starts a manual ("on demand") recording, as Frigate's live view does. Returns its event id. */
    suspend fun startManualRecording(camera: String): String {
        val data = post("api/events/$camera/on_demand/create", mapOf("include_recording" to true, "duration" to null))
        val obj = FrigateJson.parseToJsonElement(data.decodeToString())
        if (obj["success"].bool() != true) throw FrigateException.Http(500, obj["message"].str())
        return obj["event_id"].str() ?: throw FrigateException.Http(500, null)
    }

    suspend fun endEvent(id: String) {
        send("PUT", "api/events/$id/end", mapOf("end_time" to ceil(nowSeconds()).toLong()))
    }

    // MARK: Server settings

    /** The signed-in user's role; nil when Frigate runs without authentication. */
    suspend fun role(): String? = runCatching { json("api/profile")["role"].str() }.getOrNull()

    /** Changes Frigate's config as its own settings pages do (PUT /api/config/set). Admin only. */
    suspend fun setConfig(data: Map<String, Any?>, requiresRestart: Boolean, updateTopic: String?) {
        val body = mutableMapOf<String, Any?>("requires_restart" to if (requiresRestart) 1 else 0, "config_data" to data)
        if (updateTopic != null) body["update_topic"] = updateTopic
        successOrThrow(send("PUT", "api/config/set", body))
    }

    /** The config file as it is on disk (YAML). Admin only. */
    suspend fun rawConfigText(): String {
        val text = data("api/config/raw").decodeToString()
        return runCatching { (FrigateJson.parseToJsonElement(text) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull() ?: text
    }

    /** Writes the config file after Frigate has checked it; with `restart` Frigate restarts. */
    suspend fun saveRawConfig(text: String, restart: Boolean) {
        val bytes = sendRaw("POST", "api/config/save", text.toRequestBody("text/plain".toMediaType()),
            mapOf("save_option" to if (restart) "restart" else "saveonly"), timeoutSeconds = 60)
        successOrThrow(bytes)
    }

    /** Frigate 0.18's profiles and the active one. */
    suspend fun profiles(): Pair<List<FrigateProfile>, String?> {
        val obj = json("api/profiles")
        val list = obj["profiles"].arr()?.mapNotNull { p ->
            val name = p["name"].str() ?: return@mapNotNull null
            FrigateProfile(name, p["friendly_name"].str()?.takeIf { it.isNotEmpty() } ?: Format.prettyName(name))
        } ?: emptyList()
        return list to obj["active_profile"].str()
    }

    suspend fun setProfile(name: String?) { send("PUT", "api/camera/*/set/profile", mapOf("value" to (name ?: "none"))) }

    suspend fun users(): List<FrigateUser> = list("api/users") { e -> e["username"].str()?.let { FrigateUser(it, e["role"].str()) } }
    suspend fun createUser(username: String, password: String, role: String) {
        post("api/users", mapOf("username" to username, "password" to password, "role" to role))
    }
    suspend fun deleteUser(username: String) { send("DELETE", "api/users/$username", null) }
    suspend fun setPassword(username: String, password: String) { send("PUT", "api/users/$username/password", mapOf("password" to password)) }
    suspend fun setRole(username: String, role: String) { send("PUT", "api/users/$username/role", mapOf("role" to role)) }
    suspend fun restart() { post("api/restart", emptyMap<String, Any>()) }

    // MARK: Events

    /** Fetches events one by one: Frigate ignores an id filter on /api/events. */
    suspend fun events(ids: List<String>): List<FrigateEvent> = coroutineScope {
        ids.take(20).map { id -> async { runCatching { event(id) }.getOrNull() } }.awaitAll().filterNotNull().sortedBy { it.startTime }
    }

    /** The latest tracked objects per label, with each label's total count (`event_count`). */
    suspend fun exploreEvents(limit: Int = 10): List<Pair<FrigateEvent, Int?>> =
        list("api/events/explore", mapOf("limit" to limit.toString())) { e -> FrigateEvent.from(e)?.let { it to e["event_count"].int() } }

    /** Tracked objects matching the filters, as Frigate's Explore search does. */
    suspend fun searchEvents(
        cameras: List<String>? = null, labels: List<String>? = null, zones: List<String>? = null, subLabels: List<String>? = null,
        plate: String? = null, after: Double? = null, before: Double? = null, sort: EventSort = EventSort.Newest, limit: Int = 50,
    ): List<FrigateEvent> {
        val q = mutableMapOf<String, String?>("limit" to limit.toString(), "sort" to sort.raw, "include_thumbnails" to "0")
        if (!cameras.isNullOrEmpty()) q["cameras"] = cameras.joinToString(",")
        if (!labels.isNullOrEmpty()) q["labels"] = labels.joinToString(",")
        if (!zones.isNullOrEmpty()) q["zones"] = zones.joinToString(",")
        if (!subLabels.isNullOrEmpty()) q["sub_labels"] = subLabels.joinToString(",")
        plate?.let { q["recognized_license_plate"] = it }
        after?.let { q["after"] = it.toString() }
        before?.let { q["before"] = it.toString() }
        return list("api/events", q, FrigateEvent::from)
    }

    /** Frigate's semantic search (/api/events/search): by text, or for objects like `similarTo`. */
    suspend fun semanticSearch(
        query: String? = null, similarTo: String? = null, sources: Set<SearchSource> = SearchSource.entries.toSet(),
        cameras: List<String>? = null, labels: List<String>? = null, zones: List<String>? = null, subLabels: List<String>? = null,
        plate: String? = null, after: Double? = null, before: Double? = null, limit: Int = 100,
    ): List<FrigateEvent> {
        val q = mutableMapOf<String, String?>("limit" to limit.toString(), "include_thumbnails" to "0", "timezone" to TimeZone.getDefault().id)
        if (similarTo != null) {
            q["event_id"] = similarTo; q["search_type"] = "similarity"
        } else {
            q["query"] = query
            q["search_type"] = SearchSource.entries.filter { it in sources }.joinToString(",") { it.raw }
        }
        if (!cameras.isNullOrEmpty()) q["cameras"] = cameras.joinToString(",")
        if (!labels.isNullOrEmpty()) q["labels"] = labels.joinToString(",")
        if (!zones.isNullOrEmpty()) q["zones"] = zones.joinToString(",")
        if (!subLabels.isNullOrEmpty()) q["sub_labels"] = subLabels.joinToString(",")
        plate?.let { q["recognized_license_plate"] = it }
        after?.let { q["after"] = it.toString() }
        before?.let { q["before"] = it.toString() }
        return list("api/events/search", q, FrigateEvent::from)
    }

    suspend fun setDescription(eventId: String, text: String) { post("api/events/$eventId/description", mapOf("description" to text)) }
    suspend fun regenerateDescription(eventId: String, fromSnapshot: Boolean) {
        send("PUT", "api/events/$eventId/description/regenerate", null, mapOf("source" to if (fromSnapshot) "snapshot" else "thumbnails"))
    }
    suspend fun deleteEvent(id: String) { send("DELETE", "api/events/$id", null) }
    suspend fun event(id: String): FrigateEvent = FrigateEvent.from(json("api/events/$id")) ?: throw FrigateException.NotFrigate()
    suspend fun trackedLabels(): List<String> = list("api/labels") { it.str() }
    suspend fun subLabels(): List<String> = list("api/sub_labels") { it.str() }
    suspend fun recognizedPlates(): List<String> = list("api/recognized_license_plates") { it.str() }

    /** The review item an object belongs to, for playing its video. */
    suspend fun reviewForEvent(id: String): ReviewItem =
        withReviewedStatus(ReviewItem.from(json("api/review/event/$id")) ?: throw FrigateException.NotFrigate())
    suspend fun review(id: String): ReviewItem =
        withReviewedStatus(ReviewItem.from(json("api/review/$id")) ?: throw FrigateException.NotFrigate())

    /** Single-item endpoints have no reviewed flag; read it from the list around the item's start. */
    private suspend fun withReviewedStatus(item: ReviewItem): ReviewItem {
        val listed = runCatching {
            list("api/review", mapOf("cameras" to item.camera, "limit" to "20",
                "after" to (item.startTime - 1).toString(), "before" to (item.startTime + 1).toString()), ReviewItem::from)
        }.getOrNull()
        return listed?.firstOrNull { it.id == item.id } ?: item
    }

    suspend fun activeEvents(): List<FrigateEvent> = list("api/events", mapOf("in_progress" to "1", "limit" to "100"), FrigateEvent::from)
    suspend fun timeline(sourceId: String): List<TimelineEntry> = list("api/timeline", mapOf("source_id" to sourceId), TimelineEntry::from)
    suspend fun recordings(camera: String, after: Double, before: Double): List<RecordingSegment> =
        list("api/$camera/recordings", mapOf("after" to after.toLong().toString(), "before" to before.toLong().toString()), RecordingSegment::from)

    // MARK: Media URLs

    fun latestImageUrl(camera: String, height: Int? = null): String = urlString("api/$camera/latest.jpg", mapOf("height" to height?.toString()))
    fun mjpegUrl(camera: String, height: Int = 720): String = urlString("api/$camera", mapOf("height" to height.toString()))
    fun thumbnailUrl(event: FrigateEvent): String = urlString("api/events/${event.id}/thumbnail.webp")
    /** Snapshot of an object with its bounding box drawn in. */
    fun snapshotUrl(event: FrigateEvent, bbox: Boolean = true): String =
        urlString("api/events/${event.id}/snapshot.jpg", if (bbox) mapOf("bbox" to "1") else emptyMap())
    fun thumbnailUrl(review: ReviewItem): String? = review.thumbnailPath?.let { urlString(it) }
    /** A recording frame at a moment. */
    fun recordingSnapshotUrl(camera: String, time: Double, height: Int? = null): String =
        urlString("api/$camera/recordings/${time.toLong()}/snapshot.jpg", mapOf("height" to height?.toString()))
    /** Frigate's review preview: an animated picture of a review item. */
    fun reviewPreviewUrl(review: ReviewItem): String = urlString("api/review/${review.id}/preview", mapOf("format" to "mp4"))

    /** HLS playlist for a recording range. */
    fun vodUrl(camera: String, start: Double, end: Double): String =
        urlString("vod/$camera/start/${floor(start).toLong()}/end/${ceil(end).toLong()}/index.m3u8")

    /** A short clip (an event, an object) to play; Frigate 0.17+ cuts `/vod/clip` at each segment, which starts faster. */
    fun clipVodUrl(camera: String, start: Double, end: Double): String =
        if (Format.versionAtLeast(version, 0, 17)) urlString("vod/clip/$camera/start/${floor(start).toLong()}/end/${ceil(end).toLong()}/index.m3u8")
        else vodUrl(camera, start, end)

    /** Cases for exports came with Frigate 0.18. */
    val servesCases: Boolean get() = Format.versionAtLeast(version, 0, 18)

    fun clipUrl(camera: String, start: Double, end: Double): String =
        urlString("api/$camera/start/${floor(start).toLong()}/end/${ceil(end).toLong()}/clip.mp4")

    /** Names of the server's go2rtc streams; empty when Frigate runs without go2rtc restreams. */
    suspend fun go2rtcStreams(): Set<String> = json("api/go2rtc/streams").obj()?.keys ?: emptySet()

    /** Whether a go2rtc stream can receive audio (a camera speaker): a producer offers "audio, sendonly". */
    suspend fun supportsTwoWayAudio(stream: String): Boolean = runCatching {
        json("api/go2rtc/streams/$stream")["producers"].arr()?.any { p ->
            p["medias"].strings()?.any { it.contains("audio, sendonly") } == true
        } == true
    }.getOrDefault(false)

    /** Websocket version of a server path, e.g. `ws` for Frigate's event feed. */
    fun socketUrl(path: String, query: Map<String, String?> = emptyMap()): String =
        url(path, query).toString().replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")

    /** Websocket URL of go2rtc's endpoint (mse or webrtc) proxied by Frigate. */
    fun liveSocketUrl(stream: String, mode: String): String = socketUrl("live/$mode/api/ws", mapOf("src" to stream))

    /** Downloads a file into `dest`, reporting bytes received and expected (0 when unknown). */
    suspend fun download(url: String, dest: File, progress: ((Long, Long) -> Unit)? = null) {
        val req = Request.Builder().url(url).build()
        Http.withTimeout(120).newCall(req).await().use { res ->
            if (!res.isSuccessful) throw FrigateException.Http(res.code, null)
            withContext(Dispatchers.IO) {
                val total = res.body.contentLength().coerceAtLeast(0)
                dest.parentFile?.mkdirs()
                res.body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            progress?.invoke(got, total)
                        }
                    }
                }
            }
        }
    }

    /** Streams a chat answer from Frigate's GenAI chat (/api/chat/completion, newline-delimited JSON). */
    fun chat(messages: List<Any?>): Flow<JsonElement> = flow {
        val body = jsonOf(mapOf("messages" to messages, "stream" to true)).toString().toRequestBody(JSON_TYPE)
        val req = Request.Builder().url(url("api/chat/completion")).header("X-CSRF-TOKEN", "1").post(body).build()
        Http.withTimeout(180).newCall(req).await().use { res ->
            if (!res.isSuccessful) {
                val text = res.body.string()
                val obj = runCatching { FrigateJson.parseToJsonElement(text) }.getOrNull()
                if (res.code == 401) throw FrigateException.Unauthorized()
                throw FrigateException.Http(res.code, obj["error"].str() ?: obj["message"].str() ?: obj["detail"].str())
            }
            val source = res.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                val e = runCatching { FrigateJson.parseToJsonElement(line) }.getOrNull() ?: continue
                emit(e)
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        fun of(url: String): FrigateClient? = runCatching { FrigateClient(url.toHttpUrl()) }.getOrNull()
    }
}

/** True for a JSON null or missing value. */
fun JsonElement?.isNull(): Boolean = this == null || this is JsonNull
