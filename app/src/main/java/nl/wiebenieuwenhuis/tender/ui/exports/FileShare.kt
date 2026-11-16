package nl.wiebenieuwenhuis.tender.ui.exports

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.wiebenieuwenhuis.tender.TenderApplication
import java.io.File
import java.io.IOException

/** Files from a Frigate server to hand to other apps (share) or keep on the device. */
object FileShare {
    /** A name without characters that file systems refuse. */
    fun safeName(base: String): String = base.replace(Regex("[/\\\\:?%*|\"<>]"), "-").trim().ifEmpty { "file" }

    /** A file in the cache folder the FileProvider serves (`shared/`). */
    fun cacheFile(name: String): File = File(TenderApplication.context.cacheDir, "shared/${safeName(name)}")

    /** Opens the system's share sheet for a file. */
    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Saving into the gallery without a storage permission needs Android 10 or later. */
    val canSaveVideo: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Copies a video into the gallery (Movies/Tender). */
    suspend fun saveVideo(context: Context, file: File, displayName: String) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("Not supported")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, safeName(displayName))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Tender")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("Could not write the file")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
