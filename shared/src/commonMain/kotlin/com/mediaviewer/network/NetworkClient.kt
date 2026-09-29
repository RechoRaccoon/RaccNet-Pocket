package com.mediaviewer.network

/** Factories for Stellar's API clients (formerly Retrofit builders). Each
 *  call creates a new client, as before — see AndroidHttpClients for the
 *  Android OkHttp configuration behind each [HttpProfile]. */
object NetworkClient {

    fun buildBlueskyApi(baseUrl: String = "https://bsky.social/"): BlueskyApi =
        BlueskyApi(baseUrl, HttpProfile.BLUESKY)

    /** A client for calling a Bluesky service (the AppView, the chat
     *  service) DIRECTLY with a service-auth token, instead of through the
     *  user's PDS. The PDS-routing "atproto-proxy" header some endpoints
     *  carry is dropped — it only means something to a PDS. */
    fun buildDirectServiceApi(baseUrl: String): BlueskyApi =
        BlueskyApi(baseUrl, HttpProfile.DIRECT_SERVICE)

    fun buildE621Api(): E621Api = E621Api("https://e621.net/", HttpProfile.E621)

    fun buildStreamplaceApi(baseUrl: String = "https://stream.place/"): StreamplaceApi =
        StreamplaceApi(baseUrl, HttpProfile.STREAMPLACE)

    /** Item 16: Rocksky music-scrobbling integration — see RockskyApi's own
     *  doc comment for the endpoints this backs. */
    fun buildRockskyApi(): RockskyApi = RockskyApi("https://api.rocksky.app/", HttpProfile.ROCKSKY)

    // Compose Post (upload flow): video upload/processing lives on its own
    // service, separate from the user's PDS — see BlueskyRepository.
    // uploadVideoBlob. Longer timeouts (HttpProfile.VIDEO).
    fun buildBlueskyVideoApi(): BlueskyVideoApi = BlueskyVideoApi("https://video.bsky.app/", HttpProfile.VIDEO)
}
