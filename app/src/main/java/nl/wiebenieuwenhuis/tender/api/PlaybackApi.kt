package nl.wiebenieuwenhuis.tender.api

/** One of Frigate's hourly preview files (about 320×180, a frame every few seconds). */
data class PreviewFile(val src: String, val start: Double, val end: Double)

/** Preview files of a camera that overlap `start..end`. */
suspend fun FrigateClient.previews(camera: String, start: Double, end: Double): List<PreviewFile> =
    list("api/preview/$camera/start/${start.toLong()}/end/${end.toLong()}") { e ->
        val src = e["src"].str() ?: return@list null
        PreviewFile(src, e["start"].dbl() ?: return@list null, e["end"].dbl() ?: return@list null)
    }.sortedBy { it.start }

/**
 * The loose preview frames Frigate keeps while an hour runs (its file is written only after the
 * hour), e.g. `preview_front-1712345678.901234.webp`, as (time, name), oldest first.
 */
suspend fun FrigateClient.previewFrames(camera: String, start: Double, end: Double): List<Pair<Double, String>> =
    list("api/preview/$camera/start/${start.toLong()}/end/${kotlin.math.ceil(end).toLong()}/frames") { it.str() }
        .mapNotNull { name ->
            // The time follows the last "-" (camera names may contain one), before ".webp".
            val dash = name.lastIndexOf('-')
            val dot = name.lastIndexOf('.')
            if (dash < 0 || dot <= dash) return@mapNotNull null
            name.substring(dash + 1, dot).toDoubleOrNull()?.let { it to name }
        }
        .sortedBy { it.first }

fun FrigateClient.previewFrameUrl(name: String): String = urlString("api/preview/$name/thumbnail.webp")
fun FrigateClient.previewFileUrl(src: String): String = urlString(src)
