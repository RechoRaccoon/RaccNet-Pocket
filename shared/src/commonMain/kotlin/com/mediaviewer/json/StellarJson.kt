package com.mediaviewer.json

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.serializerOrNull
import kotlinx.serialization.json.JsonArray as KxArray
import kotlinx.serialization.json.JsonElement as KxElement
import kotlinx.serialization.json.JsonNull as KxNull
import kotlinx.serialization.json.JsonObject as KxObject
import kotlinx.serialization.json.JsonPrimitive as KxPrimitive

/**
 * The app-wide kotlinx.serialization setup, configured to read and write
 * JSON the way Gson did for these models:
 *  - unknown keys are ignored (Gson never failed on them)
 *  - a JSON null or a missing key for a field that has a default gets the
 *    default instead of failing (coerceInputValues)
 *  - null fields are left out when writing (Gson's default)
 *  - fields equal to their default ARE written (Gson writes every field —
 *    requests like `$type: "app.bsky.feed.post"` depend on that)
 */
object StellarJson {
    val default: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        allowSpecialFloatingPointValues = true
    }

    /** Same, used for parsing free-form trees (JsonParser.parseString). */
    val lenient: Json get() = default

    inline fun <reified T> decode(text: String): T = default.decodeFromString(text)
    inline fun <reified T> encode(value: T): String = default.encodeToString(value)
}

/**
 * Converts the loosely-typed values Stellar builds ATProto records from
 * (Map<String, Any>, List<Any>, strings, numbers, booleans, JSON trees and
 * @Serializable model objects like BskyBlob) into a JSON tree — what Gson
 * did reflectively when those maps were request bodies.
 */
@OptIn(InternalSerializationApi::class)
fun anyToKxJson(value: Any?): KxElement = when (value) {
    null -> KxNull
    is KxElement -> value
    is JsonElement -> value.toKx()
    is String -> KxPrimitive(value)
    is Boolean -> KxPrimitive(value)
    is Number -> KxPrimitive(value)
    is Char -> KxPrimitive(value.toString())
    is Enum<*> -> KxPrimitive(value.name)
    is Map<*, *> -> KxObject(buildMap { for ((k, v) in value) if (v != null) put(k.toString(), anyToKxJson(v)) })
    is Iterable<*> -> KxArray(value.map { anyToKxJson(it) })
    is Array<*> -> KxArray(value.map { anyToKxJson(it) })
    is IntArray -> KxArray(value.map { KxPrimitive(it) })
    is LongArray -> KxArray(value.map { KxPrimitive(it) })
    is FloatArray -> KxArray(value.map { KxPrimitive(it) })
    is DoubleArray -> KxArray(value.map { KxPrimitive(it) })
    is BooleanArray -> KxArray(value.map { KxPrimitive(it) })
    else -> {
        @Suppress("UNCHECKED_CAST")
        val s = value::class.serializerOrNull() as KSerializer<Any>?
            ?: throw IllegalArgumentException("Can't write ${value::class.simpleName} as JSON (not @Serializable)")
        StellarJson.default.encodeToJsonElement(s, value)
    }
}

/** Reading side of [AnyValueSerializer]: plain Kotlin values (String /
 *  Double-or-Long / Boolean / Map / List), roughly what Gson produced for
 *  `Any` fields. */
fun kxJsonToAny(el: KxElement): Any? = when (el) {
    is KxNull -> null
    is KxPrimitive -> when {
        el.isString -> el.content
        el.content == "true" -> true
        el.content == "false" -> false
        else -> el.content.toLongOrNull() ?: el.content.toDoubleOrNull() ?: el.content
    }
    is KxObject -> LinkedHashMap<String, Any?>().also { m -> for ((k, v) in el) m[k] = kxJsonToAny(v) }
    is KxArray -> el.map { kxJsonToAny(it) }
}

/** For `Map<String, Any>` record fields in request models. */
object AnyMapSerializer : KSerializer<Map<String, Any>> {
    override val descriptor: SerialDescriptor = KxObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Map<String, Any>) {
        (encoder as JsonEncoder).encodeJsonElement(anyToKxJson(value))
    }
    @Suppress("UNCHECKED_CAST")
    override fun deserialize(decoder: Decoder): Map<String, Any> =
        (kxJsonToAny((decoder as JsonDecoder).decodeJsonElement()) as? Map<String, Any>) ?: emptyMap()
}

/** For `List<Map<String, Any>>` fields (e.g. DM facets). */
object AnyMapListSerializer : KSerializer<List<Map<String, Any>>> {
    override val descriptor: SerialDescriptor = KxArray.serializer().descriptor
    override fun serialize(encoder: Encoder, value: List<Map<String, Any>>) {
        (encoder as JsonEncoder).encodeJsonElement(anyToKxJson(value))
    }
    @Suppress("UNCHECKED_CAST")
    override fun deserialize(decoder: Decoder): List<Map<String, Any>> =
        ((kxJsonToAny((decoder as JsonDecoder).decodeJsonElement()) as? List<*>)?.filterIsInstance<Map<String, Any>>()) ?: emptyList()
}

/** For a single `Any` value. */
object AnyValueSerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = KxElement.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Any) {
        (encoder as JsonEncoder).encodeJsonElement(anyToKxJson(value))
    }
    override fun deserialize(decoder: Decoder): Any =
        kxJsonToAny((decoder as JsonDecoder).decodeJsonElement()) ?: ""
}

/** Gson().toJson(any) for the loosely typed request maps. */
fun anyToJsonString(value: Any?): String = anyToKxJson(value).toString()
