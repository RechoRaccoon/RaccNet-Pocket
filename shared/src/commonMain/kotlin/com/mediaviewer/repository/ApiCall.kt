package com.mediaviewer.repository

import com.mediaviewer.model.BskyBlob
import io.ktor.client.plugins.ResponseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random

/**
 * Shared plumbing for the Ktor ports of the repositories in this package.
 *
 * Cross-worker contract assumptions (see also each file's notes):
 *  - The sibling network/ port keeps the same interface + factory names
 *    (NetworkClient.buildBlueskyApi/buildBlueskyVideoApi/buildE621Api/
 *    buildStreamplaceApi) and the same endpoint method names, but each
 *    endpoint now returns its decoded body directly and THROWS on HTTP
 *    errors (Ktor expectSuccess = true, i.e. [ResponseException]) instead
 *    of returning a Retrofit Response wrapper.
 *  - The sibling model/ port moves every com.google.gson.JsonElement field
 *    to kotlinx.serialization.json.JsonElement, and record-shaped request
 *    fields (BskyCreateRecordRequest.record, BskySendMessageInput.facets/
 *    embed) accept the Map<String, JsonElement> shapes built here (a
 *    Map<String, JsonElement> is also a valid Map<String, Any>, so this
 *    compiles against either spelling).
 */

/** Lenient JSON for hand-parsing loosely-typed AT Protocol payloads (DM
 *  message embeds, DID documents, Wikidata/Wikipedia responses) — the
 *  kotlinx equivalent of the `Gson()` instances the legacy code used for
 *  the same job (unknown fields ignored, no strict null checking). */
internal val repoJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Runs one Ktor API call, converting HTTP failures into the same
 * "<label> <code>: <description>" errors the Retrofit port produced via
 * `error("... ${resp.code()} ...")`, so call-site failure behavior and
 * messages are preserved. Non-HTTP failures (network down, bad JSON)
 * propagate untouched, exactly as before.
 */
internal suspend inline fun <T> apiCall(label: String, crossinline block: suspend () -> T): T =
    try {
        block()
    } catch (e: ResponseException) {
        val status = e.response.status
        error("$label ${status.value}: ${status.description}")
    }

/** Null-safe "is this a JSON object" downcast (kotlinx has no getAsJsonObject). */
internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

/** Null-safe string extraction — mirrors Gson's optString (missing *or*
 *  explicit-null both yield null rather than throwing). */
internal fun JsonElement?.asStr(): String? =
    (this as? JsonPrimitive)?.takeIf { !it.isNull }?.content

/**
 * Converts the legacy Gson-era `Map<String, Any>` record shapes this package
 * builds (post records, facets, embeds, like/comment records) into kotlinx
 * JsonElements for the Ktor API models. Gson used to do this reflectively;
 * kotlinx.serialization cannot serialize `Any`, hence the explicit walk.
 * [BskyBlob] values are encoded with their real serializer so blob embeds
 * keep their exact `{"$type":"blob","ref":{"$link":...},...}` wire shape.
 */
internal fun Any?.toJsonElement(): JsonElement = when (val v = this) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is BskyBlob -> repoJson.encodeToJsonElement(BskyBlob.serializer(), v)
    // `when (val v = this)` keeps the smart-cast subject in a plain local so
    // the builder lambdas below can't accidentally resolve forEach/add/put
    // against the wrong implicit receiver.
    is Map<*, *> -> buildJsonObject {
        v.forEach { (k, item) -> put(k.toString(), item.toJsonElement()) }
    }
    is Iterable<*> -> buildJsonArray { v.forEach { add(it.toJsonElement()) } }
    is Array<*> -> buildJsonArray { v.forEach { add(it.toJsonElement()) } }
    else -> JsonPrimitive(v.toString())
}

internal fun Map<String, Any?>.toJsonElementMap(): Map<String, JsonElement> =
    mapValues { (_, v) -> v.toJsonElement() }

/** java.util.UUID.randomUUID() replacement — only used as an opaque
 *  client-generated id (saved-feed preference items), so any unique-enough
 *  random string will do. */
internal fun randomId(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(36)
    repeat(32) { sb.append(hex[Random.nextInt(16)]) }
    sb.insert(20, '-')
    sb.insert(16, '-')
    sb.insert(12, '-')
    sb.insert(8, '-')
    return sb.toString()
}
