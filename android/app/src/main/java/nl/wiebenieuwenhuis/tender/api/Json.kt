package nl.wiebenieuwenhuis.tender.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Lenient JSON for everything Frigate sends: unknown keys are skipped, odd types are tolerated. */
val FrigateJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Small helpers to read Frigate's JSON leniently, the way the iOS app decodes it with `try?`. */
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.let { if (it is JsonNull) null else it.doubleOrNull }
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { if (it is JsonNull) null else it.intOrNull ?: it.doubleOrNull?.toInt() }
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { if (it is JsonNull) null else it.booleanOrNull }
fun JsonElement?.strings(): List<String>? = (this as? JsonArray)?.mapNotNull { it.str() }

/** Converts plain Kotlin values (maps, lists, strings, numbers, booleans, null) to JSON. */
fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonOf(v) })
    is Iterable<*> -> JsonArray(value.map { jsonOf(it) })
    is Array<*> -> JsonArray(value.map { jsonOf(it) })
    else -> JsonPrimitive(value.toString())
}

/** JSON to plain Kotlin values: maps, lists, strings, doubles/longs, booleans and null. */
fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> LinkedHashMap<String, Any?>().also { m -> forEach { (k, v) -> m[k] = v.toPlain() } }
    is JsonArray -> map { it.toPlain() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        content.contains('.') || content.contains('e') || content.contains('E') -> doubleOrNull
        else -> content.toLongOrNull() ?: doubleOrNull
    }
}
