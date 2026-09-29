package com.mediaviewer.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonArray as KxArray
import kotlinx.serialization.json.JsonElement as KxElement
import kotlinx.serialization.json.JsonNull as KxNull
import kotlinx.serialization.json.JsonObject as KxObject
import kotlinx.serialization.json.JsonPrimitive as KxPrimitive

/*
 * A small, multiplatform stand-in for Gson's JSON tree (com.google.gson.
 * JsonElement / JsonObject / JsonArray / JsonPrimitive / JsonNull /
 * JsonParser). Stellar's repository, backup, hub-layout and VRM code were
 * written against Gson's mutable tree API; keeping the same class and method
 * names here means that code moves to commonMain with only an import change
 * and keeps behaving the same. Parsing and printing go through
 * kotlinx.serialization.json underneath.
 *
 * Semantics follow Gson where the app relies on them:
 *  - a JSON `null` value is a JsonNull element (JsonObject.get returns it,
 *    not Kotlin null); a missing key returns Kotlin null
 *  - asString / asInt / ... on the wrong kind throw (callers wrap in
 *    runCatching / check isJsonXxx first, as they did with Gson)
 *  - asString on a number returns its text; asBoolean on the string "true"
 *    is true; asInt on "12" is 12
 *  - JsonObject keeps insertion order
 */

@kotlinx.serialization.Serializable(with = JsonElementSerializer::class)
abstract class JsonElement {
    open val isJsonObject: Boolean get() = this is JsonObject
    open val isJsonArray: Boolean get() = this is JsonArray
    open val isJsonPrimitive: Boolean get() = this is JsonPrimitive
    open val isJsonNull: Boolean get() = this is JsonNull

    open val asJsonObject: JsonObject
        get() = this as? JsonObject ?: throw IllegalStateException("Not a JSON Object: $this")
    open val asJsonArray: JsonArray
        get() = this as? JsonArray ?: throw IllegalStateException("Not a JSON Array: $this")
    open val asJsonPrimitive: JsonPrimitive
        get() = this as? JsonPrimitive ?: throw IllegalStateException("Not a JSON Primitive: $this")
    open val asJsonNull: JsonNull
        get() = this as? JsonNull ?: throw IllegalStateException("Not a JSON Null: $this")

    open val asString: String get() = throw UnsupportedOperationException(this::class.simpleName)
    open val asNumber: Number get() = throw UnsupportedOperationException(this::class.simpleName)
    open val asBoolean: Boolean get() = throw UnsupportedOperationException(this::class.simpleName)
    open val asInt: Int get() = throw UnsupportedOperationException(this::class.simpleName)
    open val asLong: Long get() = throw UnsupportedOperationException(this::class.simpleName)
    open val asDouble: Double get() = throw UnsupportedOperationException(this::class.simpleName)
    open val asFloat: Float get() = throw UnsupportedOperationException(this::class.simpleName)

    abstract fun deepCopy(): JsonElement

    /** Compact JSON text (same as Gson's JsonElement.toString()). */
    override fun toString(): String = toKx().toString()
}

class JsonNull private constructor() : JsonElement() {
    override fun deepCopy(): JsonElement = this
    override fun equals(other: Any?): Boolean = other is JsonNull
    override fun hashCode(): Int = 0
    companion object {
        val INSTANCE: JsonNull = JsonNull()
    }
}

class JsonPrimitive private constructor(
    private val stringValue: String?,
    private val numberValue: Number?,
    private val booleanValue: Boolean?,
    /** Raw number text from parsing (kept so asString returns what was in
     *  the JSON, like Gson's LazilyParsedNumber). */
    private val numberText: String?,
) : JsonElement() {
    constructor(value: String) : this(value, null, null, null)
    constructor(value: Number) : this(null, value, null, null)
    constructor(value: Boolean) : this(null, null, value, null)
    constructor(value: Char) : this(value.toString(), null, null, null)

    val isString: Boolean get() = stringValue != null
    val isNumber: Boolean get() = numberValue != null || numberText != null
    val isBoolean: Boolean get() = booleanValue != null

    override val asString: String
        get() = stringValue ?: numberText ?: numberValue?.let { numberToString(it) } ?: booleanValue.toString()

    override val asBoolean: Boolean
        get() = booleanValue ?: asString.toBoolean()

    override val asNumber: Number
        get() = numberValue ?: numberText?.let { parseNumber(it) } ?: parseNumber(asString)

    override val asDouble: Double
        get() = if (isNumber) asNumber.toDouble() else asString.trim().toDouble()
    override val asFloat: Float
        get() = if (isNumber) asNumber.toFloat() else asString.trim().toFloat()
    override val asLong: Long
        get() = if (isNumber) numberText?.trim()?.toLongOrNull() ?: asNumber.toLong() else asString.trim().toLong()
    override val asInt: Int
        get() = if (isNumber) numberText?.trim()?.toIntOrNull() ?: asNumber.toInt() else asString.trim().toInt()

    override fun deepCopy(): JsonElement = this

    override fun equals(other: Any?): Boolean {
        if (other !is JsonPrimitive) return false
        if (isNumber && other.isNumber) return asDouble == other.asDouble
        if (isBoolean && other.isBoolean) return asBoolean == other.asBoolean
        if (isString && other.isString) return asString == other.asString
        return false
    }

    override fun hashCode(): Int = when {
        isNumber -> asDouble.hashCode()
        else -> asString.hashCode()
    }

    internal fun toKxPrimitive(): KxPrimitive = when {
        stringValue != null -> KxPrimitive(stringValue)
        booleanValue != null -> KxPrimitive(booleanValue)
        numberText != null -> kotlinx.serialization.json.JsonUnquotedLiteral(numberText)
        else -> KxPrimitive(numberValue)
    }

    internal companion object {
        fun fromNumberText(text: String): JsonPrimitive = JsonPrimitive(null, null, null, text)

        fun parseNumber(text: String): Number {
            val t = text.trim()
            t.toIntOrNull()?.let { return it }
            t.toLongOrNull()?.let { return it }
            return t.toDouble()
        }

        /** Java's Number.toString() output (Kotlin's matches on every
         *  platform for Int/Long; Double/Float print like Java too). */
        fun numberToString(n: Number): String = n.toString()
    }
}

@kotlinx.serialization.Serializable(with = JsonObjectSerializer::class)
class JsonObject : JsonElement() {
    private val members = LinkedHashMap<String, JsonElement>()

    fun add(property: String, value: JsonElement?) {
        members[property] = value ?: JsonNull.INSTANCE
    }

    fun addProperty(property: String, value: String?) = add(property, value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    fun addProperty(property: String, value: Number?) = add(property, value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    fun addProperty(property: String, value: Boolean?) = add(property, value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    fun addProperty(property: String, value: Char?) = add(property, value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)

    fun remove(property: String): JsonElement? = members.remove(property)
    operator fun get(memberName: String): JsonElement? = members[memberName]
    fun has(memberName: String): Boolean = members.containsKey(memberName)
    fun entrySet(): MutableSet<MutableMap.MutableEntry<String, JsonElement>> = members.entries
    fun keySet(): MutableSet<String> = members.keys
    fun size(): Int = members.size
    fun isEmpty(): Boolean = members.isEmpty()
    fun asMap(): MutableMap<String, JsonElement> = members

    fun getAsJsonPrimitive(memberName: String): JsonPrimitive? = members[memberName] as? JsonPrimitive
    fun getAsJsonArray(memberName: String): JsonArray? = members[memberName] as? JsonArray
    fun getAsJsonObject(memberName: String): JsonObject? = members[memberName] as? JsonObject

    override fun deepCopy(): JsonObject {
        val copy = JsonObject()
        for ((k, v) in members) copy.add(k, v.deepCopy())
        return copy
    }

    override fun equals(other: Any?): Boolean = other is JsonObject && other.members == members
    override fun hashCode(): Int = members.hashCode()
}

@kotlinx.serialization.Serializable(with = JsonArraySerializer::class)
class JsonArray() : JsonElement(), Iterable<JsonElement> {
    private val elements = ArrayList<JsonElement>()

    constructor(capacity: Int) : this() { elements.ensureCapacity(capacity) }

    fun add(element: JsonElement?) { elements.add(element ?: JsonNull.INSTANCE) }
    fun add(string: String?) { elements.add(string?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE) }
    fun add(number: Number?) { elements.add(number?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE) }
    fun add(bool: Boolean?) { elements.add(bool?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE) }
    fun add(character: Char?) { elements.add(character?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE) }
    fun addAll(array: JsonArray) { elements.addAll(array.elements) }
    operator fun set(index: Int, element: JsonElement?): JsonElement = elements.set(index, element ?: JsonNull.INSTANCE)
    fun remove(element: JsonElement): Boolean = elements.remove(element)
    fun remove(index: Int): JsonElement = elements.removeAt(index)
    fun contains(element: JsonElement): Boolean = elements.contains(element)
    fun size(): Int = elements.size
    fun isEmpty(): Boolean = elements.isEmpty()
    operator fun get(i: Int): JsonElement = elements[i]
    override fun iterator(): MutableIterator<JsonElement> = elements.iterator()
    fun asList(): MutableList<JsonElement> = elements

    // Gson: a one-element array answers the scalar getters with that element.
    private fun single(): JsonElement =
        if (elements.size == 1) elements[0] else throw IllegalStateException("Array must have size 1, but has size ${elements.size}")
    override val asString: String get() = single().asString
    override val asNumber: Number get() = single().asNumber
    override val asBoolean: Boolean get() = single().asBoolean
    override val asInt: Int get() = single().asInt
    override val asLong: Long get() = single().asLong
    override val asDouble: Double get() = single().asDouble
    override val asFloat: Float get() = single().asFloat

    override fun deepCopy(): JsonArray {
        val copy = JsonArray()
        for (e in elements) copy.add(e.deepCopy())
        return copy
    }

    override fun equals(other: Any?): Boolean = other is JsonArray && other.elements == elements
    override fun hashCode(): Int = elements.hashCode()
}

/** Gson's JsonParser: `JsonParser.parseString(text)`. Throws on bad JSON
 *  (Gson throws JsonSyntaxException there too). */
object JsonParser {
    fun parseString(json: String): JsonElement = StellarJson.lenient.parseToJsonElement(json).toCompat()
}

class JsonSyntaxException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

// ── Conversions to / from kotlinx.serialization's immutable tree ──────────

fun JsonElement.toKx(): KxElement = when (this) {
    is JsonNull -> KxNull
    is JsonPrimitive -> toKxPrimitive()
    is JsonObject -> KxObject(entrySet().associate { (k, v) -> k to v.toKx() })
    is JsonArray -> KxArray(map { it.toKx() })
    else -> KxNull
}

fun KxElement.toCompat(): JsonElement = when (this) {
    is KxNull -> JsonNull.INSTANCE
    is KxPrimitive -> when {
        isString -> JsonPrimitive(content)
        booleanOrNull != null && (content == "true" || content == "false") -> JsonPrimitive(content == "true")
        else -> JsonPrimitive.fromNumberText(content)
    }
    is KxObject -> JsonObject().also { o -> for ((k, v) in this) o.add(k, v.toCompat()) }
    is KxArray -> JsonArray().also { a -> for (v in this) a.add(v.toCompat()) }
}

/** Lets models keep `JsonElement` (this package's) fields and still be
 *  @Serializable with kotlinx.serialization. */
object JsonElementSerializer : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor = KxElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: JsonElement) {
        val json = encoder as? JsonEncoder ?: error("JsonElement can only be written as JSON")
        json.encodeJsonElement(value.toKx())
    }

    override fun deserialize(decoder: Decoder): JsonElement {
        val json = decoder as? JsonDecoder ?: error("JsonElement can only be read from JSON")
        return json.decodeJsonElement().toCompat()
    }
}

object JsonObjectSerializer : KSerializer<JsonObject> {
    override val descriptor: SerialDescriptor = KxObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: JsonObject) = JsonElementSerializer.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): JsonObject =
        JsonElementSerializer.deserialize(decoder) as? JsonObject ?: throw JsonSyntaxException("Expected a JSON object")
}

object JsonArraySerializer : KSerializer<JsonArray> {
    override val descriptor: SerialDescriptor = KxArray.serializer().descriptor
    override fun serialize(encoder: Encoder, value: JsonArray) = JsonElementSerializer.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): JsonArray =
        JsonElementSerializer.deserialize(decoder) as? JsonArray ?: throw JsonSyntaxException("Expected a JSON array")
}
