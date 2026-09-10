package com.mediaviewer.repository

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bug fix: downloading a Bluesky video by saving the bytes at its HLS
 * `playlist.m3u8` URL under a `.mp4` filename produced a "video" that was
 * actually just a small text manifest — hence it showing up in the gallery
 * as 0 seconds long / corrupted.
 *
 * The real, original video file a person uploaded lives as a content-addressed
 * blob on *their own* PDS (Personal Data Server), referenced by the video
 * embed's `cid`. This resolves the correct PDS for a DID (most accounts are
 * NOT hosted on bsky.social itself) and builds the direct
 * `com.atproto.sync.getBlob` URL, which returns the real playable video.
 *
 * PORT: moved from com.mediaviewer.worker to com.mediaviewer.repository.
 * The blocking OkHttp call is now a suspending Ktor GET and org.json is
 * kotlinx.serialization, so this can run on any target (not just Android).
 * Pure URL-building logic (did:plc / did:web handling, getBlob URL shape)
 * is unchanged.
 */
object BlueskyBlobResolver {

    private val http by lazy { HttpClient() }

    // PORT: was a blocking fun; suspend now (Ktor has no blocking I/O).
    suspend fun resolveBlobUrl(did: String, cid: String): String {
        val pds = resolvePds(did)
        return "$pds/xrpc/com.atproto.sync.getBlob?did=$did&cid=$cid"
    }

    private suspend fun resolvePds(did: String): String {
        val docUrl = when {
            did.startsWith("did:plc:") -> "https://plc.directory/$did"
            did.startsWith("did:web:") -> {
                // did:web:example.com  ->  https://example.com/.well-known/did.json
                // (a %3A-encoded port, if any, is preserved as part of the host)
                val host = did.removePrefix("did:web:").substringBefore(':').replace("%3A", ":")
                "https://$host/.well-known/did.json"
            }
            else -> error("Unsupported DID method: $did")
        }
        val resp = http.get(docUrl)
        if (!resp.status.isSuccess()) error("DID resolution failed: HTTP ${resp.status.value}")
        val body = resp.bodyAsText()
        val services = repoJson.parseToJsonElement(body).jsonObject["service"] as? JsonArray
            ?: error("No service entries in DID document")
        for (svc in services) {
            val obj = svc.jsonObject
            if (obj["id"]?.jsonPrimitive?.content == "#atproto_pds") {
                return obj.getValue("serviceEndpoint").jsonPrimitive.content.trimEnd('/')
            }
        }
        error("No PDS service found in DID document")
    }
}
