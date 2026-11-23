package nl.wiebenieuwenhuis.tender.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

// MARK: - Config

data class CameraGroupConfig(val cameras: List<String>, val icon: String?, val order: Int?)

data class Toggle(val enabled: Boolean?, val enabledInConfig: Boolean?) {
    companion object {
        fun from(e: JsonElement?): Toggle? = e.obj()?.let { Toggle(it["enabled"].bool(), it["enabled_in_config"].bool()) }
    }
}

data class StreamChoice(val title: String, val name: String)

data class CameraConfig(
    val name: String,
    val friendlyName: String?,
    /** On right now; Frigate 0.16+ can switch a camera off and on while it runs. */
    val enabled: Boolean?,
    /** On in the config file (Frigate 0.16+): only then can it be switched on at runtime. */
    val enabledInConfig: Boolean?,
    val detectEnabled: Boolean?,
    val detectWidth: Int?,
    val detectHeight: Int?,
    /** Milliseconds between detection timestamps and the recording (detect.annotation_offset). */
    val annotationOffsetMs: Double?,
    val record: Toggle?,
    val audio: Toggle?,
    /** live.streams in Frigate's order: the first is the stream live view opens with. */
    val liveStreams: List<Pair<String, String>>,
    val uiOrder: Int?,
    val uiDashboard: Boolean?,
    val zones: List<String>,
    val onvifHost: String?,
    val ffmpegInputs: List<Pair<String?, List<String>>>,
    val lpr: Toggle?,
    val notifications: Toggle?,
    val type: String?,
    val genaiObjects: Toggle?,
    val raw: JsonObject,
) {
    val canSwitchOff: Boolean get() = enabledInConfig == true
    val displayName: String get() = friendlyName?.takeIf { it.isNotBlank() } ?: Format.prettyName(name)
    val aspectRatio: Float
        get() {
            val w = detectWidth ?: 0; val h = detectHeight ?: 0
            return if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f
        }
    val hasOnvif: Boolean get() = !onvifHost.isNullOrEmpty()
    val annotationOffset: Double get() = (annotationOffsetMs ?: 0.0) / 1000.0
    val describesObjects: Boolean get() = genaiObjects?.enabled == true

    /**
     * Streams for live view: Frigate's `live.streams`, or else the go2rtc restreams the camera
     * itself reads (rtsp://127.0.0.1:8554/<name>). `available` is the server's go2rtc stream
     * list when known; streams missing from it are left out. `inFrigateOrder`: the camera screen
     * opens with the first, as Frigate does. Without it the main stream comes first.
     */
    fun streams(available: Set<String>?, inFrigateOrder: Boolean = true): List<StreamChoice> {
        var list: List<StreamChoice> = if (liveStreams.isNotEmpty()) {
            liveStreams.map { StreamChoice(it.first, it.second) }
        } else {
            val names = mutableListOf(name)
            for ((path, _) in ffmpegInputs) {
                val uri = runCatching { URI(path ?: continue) }.getOrNull() ?: continue
                if (uri.host !in setOf("127.0.0.1", "localhost") || uri.port != 8554) continue
                val stream = (uri.path ?: "").removePrefix("/")
                if (stream.isNotEmpty() && stream !in names) names.add(stream)
            }
            names.map { StreamChoice(if (it == name) "Main" else it, it) }
        }
        if (available != null) list = list.filter { it.name in available }
        if (inFrigateOrder && liveStreams.isNotEmpty()) return list // already in the config's order
        return list.sortedWith { a, b ->
            when {
                a.name == name && b.name != name -> -1
                b.name == name && a.name != name -> 1
                else -> a.title.compareTo(b.title)
            }
        }
    }

    companion object {
        fun from(name: String, o: JsonObject): CameraConfig {
            val detect = o["detect"].obj()
            val streams = o["live"]["streams"].obj()?.entries?.mapNotNull { (k, v) -> v.str()?.let { k to it } } ?: emptyList()
            val inputs = o["ffmpeg"]["inputs"].arr()?.map { it["path"].str() to (it["roles"].strings() ?: emptyList()) } ?: emptyList()
            return CameraConfig(
                name = o["name"].str() ?: name,
                friendlyName = o["friendly_name"].str(),
                enabled = o["enabled"].bool(),
                enabledInConfig = o["enabled_in_config"].bool(),
                detectEnabled = detect["enabled"].bool(),
                detectWidth = detect["width"].int(),
                detectHeight = detect["height"].int(),
                annotationOffsetMs = detect["annotation_offset"].dbl(),
                record = Toggle.from(o["record"]),
                audio = Toggle.from(o["audio"]),
                liveStreams = streams,
                uiOrder = o["ui"]["order"].int(),
                uiDashboard = o["ui"]["dashboard"].bool(),
                zones = o["zones"].obj()?.keys?.toList() ?: emptyList(),
                onvifHost = o["onvif"]["host"].str(),
                ffmpegInputs = inputs,
                lpr = Toggle.from(o["lpr"]),
                notifications = Toggle.from(o["notifications"]),
                type = o["type"].str(),
                genaiObjects = Toggle.from(o["objects"]["genai"]),
                raw = o,
            )
        }
    }
}

class FrigateConfig(val raw: JsonObject) {
    val cameras: Map<String, CameraConfig> =
        raw["cameras"].obj()?.mapNotNull { (k, v) -> v.obj()?.let { k to CameraConfig.from(k, it) } }?.toMap() ?: emptyMap()
    val cameraGroups: Map<String, CameraGroupConfig> =
        raw["camera_groups"].obj()?.mapNotNull { (k, v) ->
            val o = v.obj() ?: return@mapNotNull null
            k to CameraGroupConfig(o["cameras"].strings() ?: emptyList(), o["icon"].str(), o["order"].int())
        }?.toMap() ?: emptyMap()

    /** A GenAI provider has the "chat" role, so /api/chat/completion works (Frigate 0.18+). */
    val supportsChat: Boolean = raw["genai"].obj()?.values?.any { p -> p["roles"].strings()?.contains("chat") == true } ?: false
    /** License plate recognition is on for the whole server. */
    val platesOnServer: Boolean = raw["lpr"]["enabled"].bool() == true
    /** License plate recognition is on, for the server or a camera. */
    val recognizesPlates: Boolean = cameras.values.any { it.enabled != false && (it.lpr?.enabled ?: platesOnServer) }
    /** Frigate's ui.time_format: "browser", "12hour" or "24hour". */
    val timeFormat: String? = raw["ui"]["time_format"].str()
    val semanticSearch: Boolean = raw["semantic_search"]["enabled"].bool() == true
    val faceRecognition: Boolean = raw["face_recognition"]["enabled"].bool() == true
    /** Seconds Birdseye keeps a camera after its activity ends; "Now active" keeps cameras as long. */
    val inactivityThreshold: Double = raw["birdseye"]["inactivity_threshold"].dbl() ?: 30.0
    val faceRecognitionThreshold: Double = raw["face_recognition"]["recognition_threshold"].dbl() ?: 0.9
    val faceUnknownScore: Double = raw["face_recognition"]["unknown_score"].dbl() ?: 0.8

    /** Cameras shown on the dashboard, in UI order. A camera switched off at runtime stays. */
    val orderedCameras: List<CameraConfig> = cameras.values
        .filter { (it.enabledInConfig ?: it.enabled) != false && it.uiDashboard != false }
        .sortedWith(compareBy({ it.uiOrder ?: 0 }, { it.name }))

    val orderedGroups: List<Pair<String, CameraGroupConfig>> = cameraGroups.entries
        .map { it.key to it.value }
        .sortedWith(compareBy({ it.second.order ?: 0 }, { it.first }))
}

// MARK: - Stats

data class CameraStats(
    val cameraFps: Double?, val detectionFps: Double?, val processFps: Double?, val skippedFps: Double?,
    val skippedPct: Double?, val detectionEnabled: Boolean?,
)

data class StorageStats(val total: Double?, val used: Double?)

/** One camera's recordings on disk, from /api/recordings/storage. Sizes in MB. */
data class CameraStorage(val camera: String, val usage: Double, val bandwidth: Double)

class FrigateStats(val raw: JsonObject) {
    val cameras: Map<String, CameraStats> = raw["cameras"].obj()?.mapValues { (_, v) ->
        CameraStats(v["camera_fps"].dbl(), v["detection_fps"].dbl(), v["process_fps"].dbl(), v["skipped_fps"].dbl(),
            v["skipped_pct"].dbl(), v["detection_enabled"].bool())
    } ?: emptyMap()
    val version: String? = raw["service"]["version"].str()
    val uptime: Double? = raw["service"]["uptime"].dbl()
    val latestVersion: String? = raw["service"]["latest_version"].str()
    val retentionUnmet: Boolean = raw["service"]["retention_unmet"].bool() == true
    val storage: Map<String, StorageStats> = raw["service"]["storage"].obj()?.mapValues { (_, v) ->
        StorageStats(v["total"].dbl(), v["used"].dbl())
    } ?: emptyMap()
    /** Detector name → inference speed in ms. */
    val detectors: Map<String, Double?> = raw["detectors"].obj()?.mapValues { (_, v) -> v["inference_speed"].dbl() } ?: emptyMap()
    /** GPU name → load ("12.5%") and memory. */
    val gpus: Map<String, Pair<String?, String?>> = raw["gpu_usages"].obj()?.mapValues { (_, v) -> v["gpu"].str() to v["mem"].str() } ?: emptyMap()
    val cpuPercent: Double? = raw["cpu_usages"]["frigate.full_system"]["cpu"].let { it.str()?.toDoubleOrNull() ?: it.dbl() }
    val recordingStorage: StorageStats? get() = storage["/media/frigate/recordings"]
}

// MARK: - Review

enum class Severity(val raw: String) {
    Alert("alert"), Detection("detection");
    companion object { fun of(s: String?) = entries.firstOrNull { it.raw == s } ?: Detection }
}

data class GenAISummary(
    val title: String?, val scene: String?, val confidence: Double?,
    /** 0 normal, 1 needs review, 2 security concern. */
    val threatLevel: Int?, val otherConcerns: List<String>?,
) {
    val trimmedTitle: String? get() = title?.trim()?.takeIf { it.isNotEmpty() }
    companion object {
        fun from(e: JsonElement?): GenAISummary? = e.obj()?.let {
            GenAISummary(it["title"].str(), it["scene"].str(), it["confidence"].dbl(),
                it["potential_threat_level"].int(), it["other_concerns"].strings())
        }
    }
}

data class ReviewItem(
    val id: String,
    val camera: String,
    val startTime: Double,
    val endTime: Double?,
    val severity: Severity,
    val thumbPath: String?,
    val detections: List<String>,
    val objects: List<String>,
    val subLabels: List<String>,
    val zones: List<String>,
    val audio: List<String>,
    val thumbTime: Double?,
    val metadata: GenAISummary?,
    val hasBeenReviewed: Boolean,
) {
    val duration: Double get() = maxOf(0.0, (endTime ?: (System.currentTimeMillis() / 1000.0)) - startTime)
    val inProgress: Boolean get() = endTime == null

    /** Unique labels: objects first, then audio, with "-verified" suffixes removed. */
    val labels: List<String>
        get() = (objects + audio).map { it.replace("-verified", "") }.distinct()

    /** Path for the thumbnail, relative to the server root. */
    val thumbnailPath: String? get() = thumbPath?.takeIf { it.isNotEmpty() }?.replace("/media/frigate", "")

    companion object {
        fun from(e: JsonElement): ReviewItem? {
            val o = e.obj() ?: return null
            val d = o["data"]
            return ReviewItem(
                id = o["id"].str() ?: return null,
                camera = o["camera"].str() ?: return null,
                startTime = o["start_time"].dbl() ?: return null,
                endTime = o["end_time"].dbl(),
                severity = Severity.of(o["severity"].str()),
                thumbPath = o["thumb_path"].str(),
                detections = d["detections"].strings() ?: emptyList(),
                objects = d["objects"].strings() ?: emptyList(),
                subLabels = d["sub_labels"].strings() ?: emptyList(),
                zones = d["zones"].strings() ?: emptyList(),
                audio = d["audio"].strings() ?: emptyList(),
                thumbTime = d["thumb_time"].dbl(),
                metadata = GenAISummary.from(d["metadata"]),
                hasBeenReviewed = o["has_been_reviewed"].bool() ?: false,
            )
        }
    }
}

data class DayCounts(val reviewedAlert: Int, val reviewedDetection: Int, val totalAlert: Int, val totalDetection: Int) {
    val unreviewedAlerts: Int get() = maxOf(0, totalAlert - reviewedAlert)
    val unreviewedDetections: Int get() = maxOf(0, totalDetection - reviewedDetection)
    companion object {
        fun from(e: JsonElement?): DayCounts? = e.obj()?.let {
            DayCounts(it["reviewed_alert"].int() ?: 0, it["reviewed_detection"].int() ?: 0,
                it["total_alert"].int() ?: 0, it["total_detection"].int() ?: 0)
        }
    }
}

data class MotionActivity(val startTime: Double, val motion: Double?, val camera: String?) {
    companion object {
        fun from(e: JsonElement): MotionActivity? = e.obj()?.let { o ->
            MotionActivity(o["start_time"].dbl() ?: return null, o["motion"].dbl(), o["camera"].str())
        }
    }
}

// MARK: - Events and timeline

/** `sub_label` is either a string or `[name, score]`. */
fun subLabelName(e: JsonElement?): String? = when (e) {
    is JsonPrimitive -> e.str()
    is JsonArray -> e.firstOrNull().str()
    else -> null
}

data class FrigateEvent(
    val id: String,
    val camera: String,
    val label: String,
    val subLabel: String?,
    val zones: List<String>,
    val startTime: Double,
    val endTime: Double?,
    val hasClip: Boolean?,
    val hasSnapshot: Boolean?,
    val topScore: Double?,
    val score: Double?,
    val dataTopScore: Double?,
    val box: List<Double>?,
    val recognizedLicensePlate: String?,
    val description: String?,
    /** From a semantic search: how close it came, and whether by its picture or its description. */
    val searchDistance: Double?,
    val searchSource: String?,
    val raw: JsonObject,
) {
    val bestScore: Double? get() = dataTopScore ?: topScore ?: score
    val recognizedPlate: String? get() = recognizedLicensePlate?.takeIf { it.isNotEmpty() }

    companion object {
        fun from(e: JsonElement): FrigateEvent? {
            val o = e.obj() ?: return null
            val d = o["data"]
            return FrigateEvent(
                id = o["id"].str() ?: return null,
                camera = o["camera"].str() ?: return null,
                label = o["label"].str() ?: "object",
                subLabel = subLabelName(o["sub_label"]),
                zones = o["zones"].strings() ?: emptyList(),
                startTime = o["start_time"].dbl() ?: 0.0,
                endTime = o["end_time"].dbl(),
                hasClip = o["has_clip"].bool(),
                hasSnapshot = o["has_snapshot"].bool(),
                topScore = o["top_score"].dbl(),
                score = d["score"].dbl(),
                dataTopScore = d["top_score"].dbl(),
                box = d["box"].arr()?.mapNotNull { it.dbl() },
                recognizedLicensePlate = d["recognized_license_plate"].str(),
                description = d["description"].str(),
                searchDistance = o["search_distance"].dbl(),
                searchSource = o["search_source"].str(),
                raw = o,
            )
        }
    }
}

data class TimelineEntry(
    val timestamp: Double,
    val camera: String,
    val sourceId: String?,
    val classType: String,
    val label: String?,
    val subLabel: String?,
    val zones: List<String>,
    val attribute: String?,
    val box: List<Double>?,
    val raw: JsonObject,
) {
    companion object {
        fun from(e: JsonElement): TimelineEntry? {
            val o = e.obj() ?: return null
            val d = o["data"]
            return TimelineEntry(
                o["timestamp"].dbl() ?: return null, o["camera"].str() ?: "", o["source_id"].str(),
                o["class_type"].str() ?: "", d["label"].str(), subLabelName(d["sub_label"]),
                d["zones"].strings() ?: emptyList(), d["attribute"].str(), d["box"].arr()?.mapNotNull { it.dbl() }, o,
            )
        }
    }
}

data class RecordingSegment(val startTime: Double, val endTime: Double, val duration: Double?, val motion: Double?, val objects: Double?) {
    companion object {
        fun from(e: JsonElement): RecordingSegment? = e.obj()?.let { o ->
            RecordingSegment(o["start_time"].dbl() ?: return null, o["end_time"].dbl() ?: return null,
                o["duration"].dbl(), o["motion"].dbl(), o["objects"].dbl())
        }
    }
}

// MARK: - Exports

/** A case in Frigate's Exports (0.18 and later): a named folder that exports are filed in. */
data class FrigateExportCase(val id: String, val name: String, val description: String?, val createdAt: Double) {
    companion object {
        fun from(e: JsonElement): FrigateExportCase? = e.obj()?.let { o ->
            FrigateExportCase(o["id"].str() ?: return null, o["name"].str() ?: "", o["description"].str(), o["created_at"].dbl() ?: 0.0)
        }
    }
}

/** A clip Frigate exported and keeps on the server (its Exports page). */
data class FrigateExport(
    val id: String, val camera: String, val name: String,
    /** Start of the exported recording (unix seconds). */
    val date: Double,
    val videoPath: String, val thumbPath: String, val inProgress: Boolean,
    /** The case it is filed under (Frigate 0.18 and later). */
    val caseId: String?,
) {
    companion object {
        fun from(e: JsonElement): FrigateExport? = e.obj()?.let { o ->
            FrigateExport(o["id"].str() ?: return null, o["camera"].str() ?: return null, o["name"].str() ?: "",
                o["date"].dbl() ?: 0.0, o["video_path"].str() ?: "", o["thumb_path"].str() ?: "",
                o["in_progress"].bool() ?: false, o["export_case"].str())
        }

        /** Paths on the server ("/media/frigate/exports/x.mp4") as served over HTTP ("exports/x.mp4"). */
        fun servedPath(path: String): String = path.replace("/media/frigate/", "").removePrefix("/")
    }
}

/** One of Frigate 0.18's profiles ("Home", "Away"). */
data class FrigateProfile(val name: String, val title: String)

data class FrigateUser(val username: String, val role: String?)
