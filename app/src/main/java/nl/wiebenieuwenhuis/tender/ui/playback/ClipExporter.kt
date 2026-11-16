package nl.wiebenieuwenhuis.tender.ui.playback

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.TenderApplication
import nl.wiebenieuwenhuis.tender.api.ExportItem
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.FrigateClient
import nl.wiebenieuwenhuis.tender.api.L
import nl.wiebenieuwenhuis.tender.ui.components.MonoDigits
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Downloads a recording range from Frigate and keeps it on the device or in Frigate's exports. */
@Stable
class ClipExporter {
    sealed class State {
        data object Idle : State()
        data class Working(val text: String) : State()
        data class Done(val text: String) : State()
        data class Failed(val text: String) : State()
    }

    var state by mutableStateOf<State>(State.Idle)
    /** Of the download in progress: bytes received and expected (0 when unknown). */
    var received by mutableLongStateOf(0L); private set
    var expected by mutableLongStateOf(0L); private set
    val fraction: Float? get() = if (expected > 0) minOf(1f, received.toFloat() / expected) else null
    val isWorking: Boolean get() = state is State.Working

    /** Fetches `start...end` of `camera` as MP4 into the shared cache folder. */
    suspend fun download(client: FrigateClient, camera: String, start: Double, end: Double): File {
        val name = "${camera}_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date((start * 1000).toLong()))}.mp4"
        val dest = File(File(TenderApplication.context.cacheDir, "shared"), name)
        received = 0; expected = 0
        try {
            client.download(client.clipUrl(camera, start, end), dest) { got, total ->
                // Forward only, and at most every 0.5 MB: each report redraws the pill.
                if (got >= received + 500_000 || (total > 0 && got >= total)) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { received = got; expected = total }
                }
            }
        } finally {
            received = 0; expected = 0
        }
        return dest
    }

    /** Saves a clip to the device's gallery (Movies/Tender). */
    suspend fun saveToGallery(client: FrigateClient, camera: String, start: Double, end: Double) {
        state = State.Working(L(R.string.playback_saving_clip, Format.duration(end - start)))
        state = try {
            val file = download(client, camera, start, end)
            State.Done(withContext(Dispatchers.IO) { keepInGallery(TenderApplication.context, file) })
        } catch (e: Exception) {
            State.Failed(e.message ?: L(R.string.playback_something_wrong))
        }
        settle()
    }

    /** Downloads a clip and opens Android's share sheet for it. */
    suspend fun share(context: Context, client: FrigateClient, camera: String, start: Double, end: Double) {
        state = State.Working(L(R.string.playback_preparing_video))
        try {
            val file = download(client, camera, start, end)
            state = State.Idle
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            state = State.Failed(e.message ?: L(R.string.playback_something_wrong))
            settle()
        }
    }

    /** Has Frigate export the span on the server, where it stays (Frigate's Exports). */
    suspend fun saveToFrigate(client: FrigateClient, camera: String, start: Double, end: Double, name: String, caseId: String? = null) {
        state = State.Working(L(R.string.playback_saving_to_frigate))
        state = try {
            client.startExport(camera, start, end, name, caseId)
            State.Done(L(R.string.playback_saved_to_frigate))
        } catch (e: Exception) {
            State.Failed(e.message ?: L(R.string.playback_something_wrong))
        }
        settle()
    }

    /** Several cameras' exports at once, in one case (Frigate 0.18 and later). */
    suspend fun saveToFrigate(client: FrigateClient, items: List<ExportItem>, caseId: String?, newCaseName: String?) {
        state = State.Working(L(R.string.playback_saving_to_frigate))
        state = try {
            val queued = client.startExports(items, caseId, newCaseName)
            State.Done(if (queued == items.size) L(R.string.playback_saved_to_frigate) else L(R.string.playback_some_saved, queued, items.size))
        } catch (e: Exception) {
            State.Failed(e.message ?: L(R.string.playback_something_wrong))
        }
        settle()
    }

    /** Shows a result for a moment, then clears it (unless new work started meanwhile). */
    suspend fun settle() {
        delay(2500)
        if (state !is State.Working) state = State.Idle
    }

    companion object {
        /** "Voortuin, 13 Nov 15:06": a name for an export made from the app. */
        fun exportName(camera: String, displayName: String?, start: Double, label: String? = null): String {
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), if (Format.uses12Hour) "dMMMhmma" else "dMMMHHmm")
            val date = SimpleDateFormat(pattern, Locale.getDefault()).format(Date((start * 1000).toLong()))
            val cam = displayName ?: Format.prettyName(camera)
            return if (label != null) "${Format.labelName(label)} · $cam, $date" else "$cam, $date"
        }

        /**
         * Copies a downloaded clip into the gallery and deletes the download. From Android 10 on
         * MediaStore needs no permission. On Android 9 writing to the shared Movies folder needs
         * WRITE_EXTERNAL_STORAGE, which the app does not ask for: there the clip goes to the
         * app's own Movies folder, which the media scanner then indexes.
         */
        fun keepInGallery(context: Context, file: File): String {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Tender")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                        ?: throw java.io.IOException(L(R.string.playback_not_saved_gallery))
                    try {
                        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: throw java.io.IOException(L(R.string.playback_not_saved_gallery))
                        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                } else {
                    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Tender").apply { mkdirs() }
                    val dest = File(dir, file.name)
                    file.copyTo(dest, overwrite = true)
                    MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
                }
                return L(R.string.playback_saved_to_gallery)
            } finally {
                file.delete()
            }
        }
    }
}

/** Small status pill shown while exporting and after it finishes; with a download's progress. */
@Composable
fun ExportToast(exporter: ClipExporter, modifier: Modifier = Modifier) {
    val state = exporter.state
    AnimatedVisibility(
        state !is ClipExporter.State.Idle, modifier,
        enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
    ) {
        // Keeps showing the last text while it slides away.
        val text = when (state) {
            is ClipExporter.State.Working -> state.text
            is ClipExporter.State.Done -> state.text
            is ClipExporter.State.Failed -> state.text
            ClipExporter.State.Idle -> ""
        }
        val working = state is ClipExporter.State.Working
        Column(
            Modifier
                .padding(top = 8.dp)
                .widthIn(max = 360.dp)
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is ClipExporter.State.Done -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = Color.White)
                    is ClipExporter.State.Failed -> Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = Color.White)
                    else -> CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                }
                Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                if (working && exporter.received > 0) {
                    Spacer(Modifier.width(4.dp))
                    // A fixed width, so the growing number does not push the text around.
                    Text(amount(exporter), Modifier.width(64.dp), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
                        style = MonoDigits, textAlign = TextAlign.End, maxLines = 1)
                }
            }
            // A bar while downloading (Frigate first builds the clip, so it starts after a moment).
            if (working && exporter.received > 0) {
                val f = exporter.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, Modifier.width(220.dp), color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.15f))
                else LinearProgressIndicator(Modifier.width(220.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.White.copy(alpha = 0.15f))
            }
        }
    }
}

private fun amount(e: ClipExporter): String {
    e.fraction?.let { return "${(it * 100).roundToInt()}%" }
    return String.format(Locale.getDefault(), "%.1f MB", e.received / 1_000_000.0)
}
