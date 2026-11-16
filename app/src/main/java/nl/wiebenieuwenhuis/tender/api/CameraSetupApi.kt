package nl.wiebenieuwenhuis.tender.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import nl.wiebenieuwenhuis.tender.R
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt

// Adding a camera as Frigate's own wizard does (0.17+): find its streams over ONVIF or from a
// brand's address, test each with ffprobe, then write the camera and its go2rtc streams.
// As CameraSetupAPI.swift and StreamAddress.swift in the iOS app.

/** What ffprobe found in a stream. */
data class StreamProbe(
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val fps: Double? = null,
) {
    val resolution: String? get() = if (width != null && height != null) "$width×$height" else null
    val isHEVC: Boolean get() = videoCodec in setOf("hevc", "h265")
    val pixels: Int get() = (width ?: 0) * (height ?: 0)

    /** "2560×1440 · h265 · 20 fps · aac" */
    val summary: String
        get() = listOfNotNull(resolution, videoCodec?.let { if (it == "hevc") "h265" else it },
            fps?.let { "${it.roundToInt()} fps" }, audioCodec).joinToString(" · ")
}

/** What ONVIF told about the camera. */
data class OnvifDevice(
    val manufacturer: String,
    val model: String,
    val firmware: String,
    val profiles: Int,
    val ptz: Boolean,
    val panTilt: Boolean,
    /** Stream addresses the camera reported (credentials filled in by Frigate). */
    val streams: List<String>,
)

enum class CameraBrand {
    Hikvision, Dahua, Reolink, Ubiquiti, Axis, TpLink, Foscam, Other;

    val title: String
        get() = when (this) {
            Hikvision -> "Hikvision"
            Dahua -> "Dahua / Amcrest"
            Reolink -> "Reolink"
            Ubiquiti -> "Ubiquiti"
            Axis -> "Axis"
            TpLink -> "TP-Link / Tapo"
            Foscam -> "Foscam"
            Other -> L(R.string.setup_other_address)
        }

    /** The main stream's address, as Frigate's wizard builds it. Reolink: RTSP and HTTP-FLV. */
    fun urls(host: String, user: String, password: String): List<String> {
        val templates = when (this) {
            Hikvision -> listOf("rtsp://{u}:{p}@{h}:554/Streaming/Channels/101")
            Dahua -> listOf("rtsp://{u}:{p}@{h}:554/cam/realmonitor?channel=1&subtype=0")
            Ubiquiti -> listOf("rtsp://{u}:{p}@{h}:554/live/ch0")
            Axis -> listOf("rtsp://{u}:{p}@{h}:554/axis-media/media.amp")
            TpLink -> listOf("rtsp://{u}:{p}@{h}:554/stream1")
            Foscam -> listOf("rtsp://{u}:{p}@{h}:88/videoMain")
            Reolink -> listOf("http://{h}/flv?port=1935&app=bcs&stream=channel0_main.bcs&user={u}&password={p}",
                "rtsp://{u}:{p}@{h}:554/Preview_01_main")
            Other -> emptyList()
        }
        val u = StreamAddress.encode(user, StreamAddress.USERINFO_ALLOWED)
        val p = StreamAddress.encode(password, StreamAddress.USERINFO_ALLOWED)
        return templates.map { it.replace("{u}", u).replace("{p}", p).replace("{h}", host) }
    }

    /** The sub stream next to the main one, where the brand has a known address for it. */
    fun subUrl(main: String): String? = when (this) {
        Hikvision -> main.replace("/Channels/101", "/Channels/102")
        Dahua -> main.replace("subtype=0", "subtype=1")
        Reolink -> if (main.contains("channel0_main")) main.replace("channel0_main", "channel0_sub")
            else main.replace("Preview_01_main", "Preview_01_sub")
        TpLink -> main.replace("/stream1", "/stream2")
        else -> null
    }
}

/** ffprobe's failure in words people can act on; its own text stays in `detail`. */
class StreamProbeException(val detail: String) : IOException(describe(detail)) {
    companion object {
        private fun describe(detail: String): String {
            val d = detail.lowercase()
            return when {
                "401" in d || "unauthorized" in d -> L(R.string.setup_probe_login_refused)
                "404" in d || "not found" in d -> L(R.string.setup_probe_no_stream)
                listOf("refused", "timed out", "timeout", "no route", "connection to").any { it in d } -> L(R.string.setup_probe_no_answer)
                else -> L(R.string.setup_probe_failed)
            }
        }
    }
}

suspend fun FrigateClient.probeOnvif(host: String, port: Int, user: String, password: String): OnvifDevice {
    val obj = json("api/onvif/probe", mapOf(
        "host" to host, "port" to port.toString(), "username" to user, "password" to password,
        "test" to "false", "auth_type" to "basic",
    ), timeoutSeconds = 35)
    if (obj["success"].bool() == false) throw FrigateException.Http(400, obj["message"].str())
    val reported = obj["rtsp_candidates"].arr()?.filter { it["source"].str() == "GetStreamUri" }?.mapNotNull { it["uri"].str() } ?: emptyList()
    fun s(k: String) = obj[k].str()?.takeIf { it != "Unknown" } ?: ""
    return OnvifDevice(
        manufacturer = s("manufacturer"), model = s("model"), firmware = s("firmware_version"),
        profiles = obj["profiles_count"].int() ?: 0,
        ptz = obj["ptz_supported"].bool() ?: false, panTilt = obj["pan_tilt_supported"].bool() ?: false,
        streams = reported.distinct(),
    )
}

/** Reolink: "http-flv" up to 5 MP, "rtsp" above, null when it cannot tell. */
suspend fun FrigateClient.reolinkProtocol(host: String, user: String, password: String): String? = runCatching {
    json("api/reolink/detect", mapOf("host" to host, "username" to user, "password" to password), timeoutSeconds = 15)["protocol"].str()
}.getOrNull()

/** Opens the stream on the server with ffprobe. Throws with ffprobe's (credential-free) error. */
suspend fun FrigateClient.probeStream(streamUrl: String): StreamProbe {
    val first = json("api/ffprobe", mapOf("paths" to streamUrl, "detailed" to "true"), timeoutSeconds = 20).arr()?.firstOrNull()
        ?: throw FrigateException.NotFrigate()
    if ((first["return_code"].int() ?: 0) != 0) {
        val err = first["stderr"]
        val lines = err.strings() ?: listOf(err.str() ?: "")
        throw StreamProbeException(lines.filter { it.isNotEmpty() }.joinToString(" "))
    }
    var probe = StreamProbe()
    for (s in first["stdout"]["streams"].arr() ?: JsonArray(emptyList())) {
        val codec = s["codec_name"].str()?.lowercase() ?: ""
        if (probe.videoCodec == null && codec in setOf("h264", "hevc", "h265", "mjpeg")) {
            var fps: Double? = null
            s["avg_frame_rate"].str()?.let { rate ->
                val parts = rate.split("/").mapNotNull { it.toDoubleOrNull() }
                if (parts.size == 2 && parts[1] > 0) fps = parts[0] / parts[1]
            }
            probe = probe.copy(videoCodec = codec, width = s["width"].int(), height = s["height"].int(), fps = fps)
        } else if (probe.audioCodec == null && codec in setOf("aac", "mp3", "pcm_mulaw", "pcm_alaw", "opus")) {
            probe = probe.copy(audioCodec = codec)
        }
    }
    if (probe.width == null) throw FrigateException.Http(400, L(R.string.setup_probe_no_video))
    return probe
}

fun FrigateClient.streamSnapshotUrl(streamUrl: String): String = urlString("api/ffprobe/snapshot", mapOf("url" to streamUrl))

/** Adds a stream to the running go2rtc (it reads its config only at start). */
suspend fun FrigateClient.addGo2rtcStream(name: String, source: String) {
    val req = Request.Builder().url(url("api/go2rtc/streams/$name", mapOf("src" to source)))
        .header("X-CSRF-TOKEN", "1").put("".toRequestBody(null)).build()
    perform(req)
}

/** The config with the real stream addresses (Frigate 0.17+, admins): `cameras.*.ffmpeg.inputs` and `go2rtc.streams`. */
suspend fun FrigateClient.rawPaths(): JsonObject = json("api/config/raw_paths").obj() ?: JsonObject(emptyMap())

/**
 * A camera's stream address ("rtsp://user:password@192.168.1.50:554/stream", or Reolink's
 * "http://…/flv?…&user=…&password=…"): its host and login read and replaced as text, so the
 * rest stays as written, {FRIGATE_*} variables included.
 */
object StreamAddress {
    /** Shown instead of the password. */
    const val MASK = "••••"

    private data class Parts(val scheme: String, val userinfo: String?, val host: String, val port: String?, val rest: String)

    private val pattern = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://(?:([^/\s]*)@)?(\[[^\]\s]+\]|[^:/?#@\s]+)(?::(\d+))?(.*)$""")
    private val queryUser = Regex("""(?i)([?&](?:user|username)=)([^&#\s]*)""")
    private val queryPassword = Regex("""(?i)([?&](?:password|pass|pwd)=)([^&#\s]*)""")

    private fun parts(url: String): Parts? {
        val m = pattern.matchEntire(url) ?: return null
        val g = m.groups
        return Parts(g[1]!!.value, g[2]?.value, g[3]!!.value, g[4]?.value, g[5]?.value ?: "")
    }

    private fun join(p: Parts) = p.scheme + "://" + (p.userinfo?.let { "$it@" } ?: "") + p.host + (p.port?.let { ":$it" } ?: "") + p.rest

    fun host(url: String): String? = parts(url)?.host

    /** A go2rtc restream Frigate reads (rtsp://127.0.0.1:8554/<name>): its name. */
    fun restreamName(path: String): String? {
        val p = parts(path) ?: return null
        if (p.host !in setOf("127.0.0.1", "localhost") || p.port != "8554") return null
        val name = p.rest.drop(1).takeWhile { it != '?' && it != '#' && it != '/' }
        return name.ifEmpty { null }
    }

    fun isLocal(url: String): Boolean = host(url) in setOf("127.0.0.1", "localhost")

    fun user(url: String): String? {
        val info = parts(url)?.userinfo
        if (!info.isNullOrEmpty()) return decode(info.substringBefore(':'))
        return queryUser.find(url)?.groupValues?.get(2)?.let { decode(it) }
    }

    /** The password as written in the address (percent-encoded, or a {FRIGATE_*} variable). */
    fun password(url: String): String? {
        val info = parts(url)?.userinfo
        if (info != null && info.contains(':')) return info.substringAfter(':')
        return queryPassword.find(url)?.groupValues?.get(2)
    }

    /** Where the password sits in an address (after "user:", and in "password=" query values). */
    fun passwordRanges(url: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        pattern.matchEntire(url)?.groups?.get(2)?.let { info ->
            val colon = info.value.indexOf(':')
            if (colon >= 0 && colon + 1 < info.value.length) out.add((info.range.first + colon + 1)..info.range.last)
        }
        for (m in queryPassword.findAll(url)) m.groups[2]?.range?.takeIf { !it.isEmpty() }?.let { out.add(it) }
        return out
    }

    fun masked(url: String): String {
        var s = url
        val p = parts(s)
        if (p?.userinfo != null) {
            val info = p.userinfo
            val colon = info.indexOf(':')
            if (colon >= 0 && colon + 1 < info.length) s = join(p.copy(userinfo = info.substring(0, colon + 1) + MASK))
        }
        return queryPassword.replace(s) { it.groupValues[1] + MASK }
    }

    /** The edited text of a masked address with the original password back in place of the mask. */
    fun unmasked(text: String, original: String): String {
        if (!text.contains(MASK)) return text
        val pw = password(original) ?: return text
        return text.replace(MASK, pw)
    }

    fun replacingHost(url: String, host: String): String = parts(url)?.let { join(it.copy(host = host)) } ?: url

    /** New login where the address has one; null keeps that part as it is. */
    fun replacingLogin(url: String, user: String?, password: String?): String {
        if (user == null && password == null) return url
        var s = url
        parts(s)?.let { p ->
            val info = p.userinfo ?: return@let
            val colon = info.indexOf(':')
            val oldUser = if (colon >= 0) info.substring(0, colon) else info
            val oldPassword = if (colon >= 0) info.substring(colon + 1) else null
            val u = user?.let { encode(it, USERINFO_ALLOWED) } ?: oldUser
            val pw = password?.let { encode(it, USERINFO_ALLOWED) } ?: oldPassword
            s = join(p.copy(userinfo = u + (pw?.let { ":$it" } ?: "")))
        }
        // No login in the address yet: it goes in front of the host.
        val p = parts(s)
        if (p != null && p.userinfo == null && queryUser.find(s) == null && queryPassword.find(s) == null && !user.isNullOrEmpty()) {
            return join(p.copy(userinfo = encode(user, USERINFO_ALLOWED) + (password?.let { ":" + encode(it, USERINFO_ALLOWED) } ?: "")))
        }
        if (user != null && queryUser.find(s) != null) s = queryUser.replace(s) { it.groupValues[1] + encode(user, UNRESERVED) }
        if (password != null && queryPassword.find(s) != null) s = queryPassword.replace(s) { it.groupValues[1] + encode(password, UNRESERVED) }
        return s
    }

    /** Frigate can open it with ffprobe: a camera address without a {FRIGATE_*} variable. */
    fun canProbe(url: String): Boolean {
        val scheme = parts(url)?.scheme?.lowercase() ?: return false
        return scheme in setOf("rtsp", "rtsps", "rtmp", "http", "https") && !url.contains("{")
    }

    // MARK: Percent encoding, as Foundation's URL character sets

    const val UNRESERVED = "-._~"
    /** Letters, digits and what a URL allows in its user and password ("@:/?#[]" excluded). */
    const val USERINFO_ALLOWED = "-._~!$&'()*+,;="

    fun encode(s: String, allowed: String): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || allowed.indexOf(c) >= 0) out.append(c)
            else out.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return out.toString()
    }

    fun decode(s: String): String {
        if (!s.contains('%')) return s
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { bytes.write(v); i += 3; continue }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
