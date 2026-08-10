package com.mediaviewer.worker

import com.mediaviewer.network.NetworkClient
import okhttp3.Request
import org.json.JSONObject

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
 */
object BlueskyBlobResolver {

    fun resolveBlobUrl(did: String, cid: String): String {
        val pds = resolvePds(did)
        return "$pds/xrpc/com.atproto.sync.getBlob?did=$did&cid=$cid"
    }

    private fun resolvePds(did: String): String {
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
        val response = NetworkClient.downloadClient.newCall(Request.Builder().url(docUrl).build()).execute()
        if (!response.isSuccessful) error("DID resolution failed: HTTP ${response.code}")
        val body = response.body?.string() ?: error("Empty DID document")
        val services = JSONObject(body).optJSONArray("service") ?: error("No service entries in DID document")
        for (i in 0 until services.length()) {
            val svc = services.getJSONObject(i)
            if (svc.optString("id") == "#atproto_pds") return svc.getString("serviceEndpoint").trimEnd('/')
        }
        error("No PDS service found in DID document")
    }
}
