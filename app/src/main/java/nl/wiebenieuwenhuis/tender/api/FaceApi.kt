package nl.wiebenieuwenhuis.tender.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

// Frigate's face library (face recognition, 0.16+), called as Frigate's own Face Library page
// calls it. Recent recognition attempts live in the folder "train"; each name is a folder of
// face images. Attempt files are named like classification attempts:
// <event id>-<timestamp>-<name>-<score>.webp (see ClassificationAttempt).

/** Every name with its images, and the recent attempts under "train". */
suspend fun FrigateClient.faces(): Map<String, List<String>> =
    json("api/faces").obj()?.mapValues { (_, v) -> v.strings() ?: emptyList() } ?: emptyMap()

fun FrigateClient.faceImageUrl(name: String, file: String): String = urlString("clips/faces/$name/$file")

/** Adds a recent attempt to a name's images. */
suspend fun FrigateClient.trainFace(file: String, name: String) {
    post("api/faces/train/$name/classify", mapOf("training_file" to file))
}

/** Runs recognition again on a recent attempt; returns the name and score it got. */
suspend fun FrigateClient.reprocessFace(file: String): Pair<String, Double>? {
    val data = post("api/faces/reprocess", mapOf("training_file" to file))
    val obj = runCatching { FrigateJson.parseToJsonElement(data.decodeToString()) }.getOrNull()
    val name = obj["face_name"].str() ?: return null
    return name to (obj["score"].dbl() ?: 0.0)
}

/** Deletes images of a name, or recent attempts with `name` "train". Deleting every image of a name removes the name. */
suspend fun FrigateClient.deleteFaces(name: String, files: List<String>) {
    post("api/faces/$name/delete", mapOf("ids" to files))
}

suspend fun FrigateClient.renameFace(old: String, new: String) {
    send("PUT", "api/faces/$old/rename", mapOf("new_name" to new))
}

/** Moves one image of a name to another name. */
suspend fun FrigateClient.reclassifyFace(file: String, from: String, to: String) {
    post("api/faces/$from/reclassify", mapOf("id" to file, "new_name" to to))
}

/** A new, empty name (Frigate turns spaces into underscores). */
suspend fun FrigateClient.createFace(name: String) {
    post("api/faces/$name/create", emptyMap<String, Any>())
}

/** Uploads a picture of a person for a name; Frigate finds the face in it. */
suspend fun FrigateClient.registerFace(name: String, jpeg: ByteArray) {
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", "face.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
        .build()
    val data = sendRaw("POST", "api/faces/$name/register", body, timeoutSeconds = 60)
    // Frigate answers 200 with success false when it found no face in the picture.
    val obj = runCatching { FrigateJson.parseToJsonElement(data.decodeToString()) }.getOrNull()
    if (obj["success"].bool() == false) throw FrigateException.Http(400, obj["message"].str())
}
