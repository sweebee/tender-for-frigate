package nl.wiebenieuwenhuis.tender.api

import kotlinx.serialization.json.JsonElement
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date

// Frigate's custom classification (0.17+): models that put a tracked object into a class (an
// object model, e.g. which delivery service) or tell a fixed area's state (a state model, e.g.
// the gate open or closed). Called as Frigate's own Classification page calls them.

/** A model as it stands in the config (classification.custom.<name>). */
data class ClassificationModel(
    val serverId: String,
    val name: String,
    val enabled: Boolean,
    val kind: Kind,
    val threshold: Double,
    val saveAttempts: Int?,
) {
    sealed class Kind {
        /** The object label it looks at, and whether the class becomes a sub label or an attribute. */
        data class Object(val label: String, val asAttribute: Boolean) : Kind()
        /** The cameras with their square area (0…1: x1, y1, x2, y2); runs on motion and/or every `interval` seconds. */
        data class State(val cameras: Map<String, List<Double>>, val motion: Boolean, val interval: Int?) : Kind()
    }

    val id: String get() = "$serverId/$name"
    val isState: Boolean get() = kind is Kind.State

    companion object {
        fun all(config: JsonElement?, serverId: String): List<ClassificationModel> {
            val custom = config["classification"]["custom"].obj() ?: return emptyList()
            return custom.mapNotNull { (key, raw) ->
                val m = raw.obj() ?: return@mapNotNull null
                val name = m["name"].str()?.takeIf { it.isNotEmpty() } ?: key
                val kind: Kind = m["object_config"].obj()?.let { o ->
                    Kind.Object(o["objects"].strings()?.firstOrNull() ?: "", o["classification_type"].str() == "attribute")
                } ?: m["state_config"].obj()?.let { s ->
                    val cams = s["cameras"].obj()?.mapNotNull { (cam, c) ->
                        c["crop"].arr()?.mapNotNull { it.dbl() }?.let { cam to it }
                    }?.toMap() ?: emptyMap()
                    Kind.State(cams, s["motion"].bool() ?: false, s["interval"].int())
                } ?: return@mapNotNull null
                ClassificationModel(serverId, name, m["enabled"].bool() ?: true, kind,
                    m["threshold"].dbl() ?: 0.8, m["save_attempts"].int())
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }
}

/** A model's labelled images, per class, and whether it needs training. */
data class ClassificationDataset(
    val categories: Map<String, List<String>> = emptyMap(),
    val hasTrained: Boolean = false,
    val lastTrained: Date? = null,
    /** Images added or moved since the last training. */
    val newImages: Int = 0,
    val totalImages: Int = 0,
    /** Images in the last training; Frigate sets it to 0 (and the date to now) when images are moved or renamed. */
    val lastTrainedImages: Int = 0,
    /** Something changed since the last training, so training again makes sense. */
    val changed: Boolean = false,
) {
    /** The classes in Frigate's order: by name, "none" last. */
    val classNames: List<String>
        get() = categories.keys.sortedWith { a, b ->
            when {
                a == "none" && b != "none" -> 1
                b == "none" && a != "none" -> -1
                else -> String.CASE_INSENSITIVE_ORDER.compare(a, b)
            }
        }
}

/**
 * An image Frigate saved while classifying, waiting to be put in a class:
 * "<event id>-<timestamp>-<label>-<score>.webp" (state models use "none-none" as event id).
 */
data class ClassificationAttempt(val file: String, val eventId: String, val time: Double, val label: String, val score: Double) {
    val id: String get() = file

    companion object {
        fun from(file: String): ClassificationAttempt? {
            val parts = file.replace(".webp", "").split("-")
            if (parts.size < 5) return null
            val ts = parts[2].toDoubleOrNull() ?: return null
            val score = parts[4].toDoubleOrNull() ?: return null
            if (parts[3].isEmpty()) return null
            return ClassificationAttempt(file, parts[0] + "-" + parts[1], ts, parts[3], score)
        }

        /** Attempts per tracked object, the best try first; newest object first. */
        fun grouped(attempts: List<ClassificationAttempt>): List<List<ClassificationAttempt>> {
            val byEvent = LinkedHashMap<String, MutableList<ClassificationAttempt>>()
            for (a in attempts) byEvent.getOrPut(a.eventId) { mutableListOf() }.add(a)
            return byEvent.values.map { g -> g.sortedByDescending { it.score } }
        }
    }
}

object ClassificationNames {
    /** As Frigate's page allows for classes: letters, numbers, spaces, ' _ -, at most 50. */
    fun isValid(name: String): Boolean =
        name.isNotEmpty() && name.length <= 50 && name.all { it.isLetterOrDigit() || it in " '_-" }

    /** What Frigate tracks (attributes such as faces and delivery logos left out), for object models. */
    fun trackedLabels(config: JsonElement?): List<String> {
        val attributes = setOf("face", "license_plate", "amazon", "an_post", "canada_post", "dhl", "dpd", "fedex", "gls",
            "nzpost", "postnl", "postnord", "purolator", "royal_mail", "ups", "usps")
        val all = (config["objects"]["track"].strings() ?: emptyList()).toMutableSet()
        config["cameras"].obj()?.values?.forEach { cam ->
            if (cam["enabled"].bool() != false) all.addAll(cam["objects"]["track"].strings() ?: emptyList())
        }
        return (all - attributes).sortedBy { Format.labelName(it) }
    }
}

/** "2026-11-15T13:31:39.156140", with or without a zone; without one it is the server's (and so likely the phone's) local time. */
internal fun parseFrigateDate(s: String): Date? {
    runCatching { return Date.from(OffsetDateTime.parse(s).toInstant()) }
    runCatching { return Date.from(java.time.Instant.parse(s)) }
    runCatching { return Date.from(LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant()) }
    return null
}

suspend fun FrigateClient.classificationDataset(model: String): ClassificationDataset {
    val obj = json("api/classification/$model/dataset").obj() ?: return ClassificationDataset()
    val cats = obj["categories"].obj()?.mapNotNull { (k, v) -> v.strings()?.let { k to it } }?.toMap() ?: emptyMap()
    val meta = obj["training_metadata"].obj() ?: return ClassificationDataset(categories = cats)
    return ClassificationDataset(
        categories = cats,
        hasTrained = meta["has_trained"].bool() ?: false,
        lastTrained = meta["last_training_date"].str()?.let { parseFrigateDate(it) },
        newImages = meta["new_images_count"].int() ?: 0,
        totalImages = meta["current_image_count"].int() ?: 0,
        lastTrainedImages = meta["last_training_image_count"].int() ?: 0,
        changed = meta["dataset_changed"].bool() ?: false,
    )
}

/** Recent attempts, newest first; the wizard's examples (example_000.jpg) are left out. */
suspend fun FrigateClient.classificationAttempts(model: String): List<ClassificationAttempt> =
    rawClassificationTrainFiles(model).mapNotNull { ClassificationAttempt.from(it) }.sortedByDescending { it.time }

suspend fun FrigateClient.rawClassificationTrainFiles(model: String): List<String> =
    json("api/classification/$model/train").strings() ?: emptyList()

/** Puts a recent image into a class (made when it does not exist yet). */
suspend fun FrigateClient.categorize(model: String, file: String, category: String) {
    post("api/classification/$model/dataset/categorize", mapOf("training_file" to file, "category" to category))
}

/** Moves a labelled image to another class (Frigate 0.18). */
suspend fun FrigateClient.reclassify(model: String, file: String, from: String, to: String) {
    post("api/classification/$model/dataset/$from/reclassify", mapOf("id" to file, "new_category" to to))
}

/** Deleting every image of a class deletes the class. */
suspend fun FrigateClient.deleteDatasetImages(model: String, category: String, files: List<String>) {
    post("api/classification/$model/dataset/$category/delete", mapOf("ids" to files))
}

suspend fun FrigateClient.deleteAttempts(model: String, files: List<String>) {
    post("api/classification/$model/train/delete", mapOf("ids" to files))
}

suspend fun FrigateClient.createCategory(model: String, category: String) {
    post("api/classification/$model/dataset/$category/create", emptyMap<String, Any>())
}

suspend fun FrigateClient.renameCategory(model: String, old: String, new: String) {
    send("PUT", "api/classification/$model/dataset/$old/rename", mapOf("new_category" to new))
}

/** Starts training; Frigate reports progress as the model's state on its websocket. */
suspend fun FrigateClient.trainClassification(model: String) {
    post("api/classification/$model/train", emptyMap<String, Any>())
}

/** Removes the model's images and trained files (the wizard's examples too). */
suspend fun FrigateClient.deleteClassificationFiles(model: String) {
    send("DELETE", "api/classification/$model", null)
}

/** Removes the model's images and trained files, and then its config. */
suspend fun FrigateClient.deleteClassificationModel(model: String) {
    deleteClassificationFiles(model)
    setConfig(mapOf("classification" to mapOf("custom" to mapOf(model to ""))), requiresRestart = false,
        updateTopic = "config/classification/custom/$model")
}

/** Writes (part of) a model's config node, as Frigate's page does. */
suspend fun FrigateClient.setClassificationModel(model: String, node: Map<String, Any?>) {
    setConfig(mapOf("classification" to mapOf("custom" to mapOf(model to node))), requiresRestart = false,
        updateTopic = "config/classification/custom/$model")
}

/**
 * Fills the model's recent images with 24 varied examples from past events (object models)
 * or recordings (state models). Takes a while; replaces what was there.
 */
suspend fun FrigateClient.generateObjectExamples(model: String, label: String) {
    post("api/classification/generate_examples/object", mapOf("model_name" to model, "label" to label), timeoutSeconds = 300)
}

suspend fun FrigateClient.generateStateExamples(model: String, cameras: Map<String, List<Double>>) {
    post("api/classification/generate_examples/state", mapOf("model_name" to model, "cameras" to cameras), timeoutSeconds = 300)
}

fun FrigateClient.datasetImageUrl(model: String, category: String, file: String): String =
    urlString("clips/$model/dataset/$category/$file")

fun FrigateClient.attemptImageUrl(model: String, file: String): String = urlString("clips/$model/train/$file")
