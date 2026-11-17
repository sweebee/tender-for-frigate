package nl.wiebenieuwenhuis.tender.ui.live

import android.media.MediaCodecList
import androidx.compose.runtime.mutableStateSetOf
import nl.wiebenieuwenhuis.tender.api.StreamChoice
import nl.wiebenieuwenhuis.tender.app.Prefs

/**
 * Live streams this device cannot decode, such as a 2688×1520 HEVC main stream on a tablet whose
 * decoder stops at 1920×1088. The web view then fails MSE and WebRTC and ends up on snapshots;
 * instead the player switches to the camera's lighter stream. Learned when a stream starts (its
 * codec and size from the page) and kept per device, so the next time the lighter stream plays
 * straight away.
 */
object StreamSupport {
    private const val KEY = "unplayableStreams"
    private val unplayable = mutableStateSetOf<String>().apply {
        Prefs.string(KEY)?.split('\n')?.filter { it.isNotEmpty() }?.let { addAll(it) }
    }
    private val cache = HashMap<String, Boolean>()

    private fun key(serverId: String, stream: String) = "$serverId/$stream"

    fun isUnplayable(serverId: String, stream: String) = key(serverId, stream) in unplayable

    /** The streams worth trying, in the same order: those this device can play, or all when it can play none. */
    fun playable(serverId: String, streams: List<StreamChoice>): List<StreamChoice> =
        streams.filter { !isUnplayable(serverId, it.name) }.ifEmpty { streams }

    /**
     * Checks a stream that has started: go2rtc's MSE codec string and the video's size. Returns
     * true (and remembers it) when no decoder on this device takes that codec at that size.
     */
    fun check(serverId: String, stream: String, info: MseInfo): Boolean {
        val (codecs, width, height) = info
        if (width <= 0 || height <= 0) return false
        val mime = when {
            codecs.contains("hvc1") || codecs.contains("hev1") -> "video/hevc"
            codecs.contains("avc1") || codecs.contains("avc3") -> "video/avc"
            else -> return false
        }
        val supported = cache.getOrPut("$mime $width×$height") { canDecode(mime, width, height) }
        if (supported) return false
        if (unplayable.add(key(serverId, stream))) Prefs.setString(KEY, unplayable.joinToString("\n"))
        return true
    }

    private fun canDecode(mime: String, width: Int, height: Int): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                info.getCapabilitiesForType(mime).videoCapabilities?.let {
                    it.isSizeSupported(width, height) || it.isSizeSupported(height, width)
                } == true
        }
    }.getOrDefault(true)
}
