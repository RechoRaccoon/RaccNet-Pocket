package com.mediaviewer.network

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Item 16: Rocksky (rocksky.app) integration — an AT Protocol music-
 *  scrobbling service. This talks to their public XRPC surface at
 *  https://api.rocksky.app, per their docs (https://docs.rocksky.app) and
 *  lexicon namespace (app.rocksky.*, github.com/tsirysndr/rocksky). Two
 *  endpoints are used:
 *   - app.rocksky.actor.getActorScrobbles — a DID's *own* scrobble history
 *     (queried with a `did` param — `actor` is rejected with InvalidRequest),
 *     used for the profile's "Music History" tab. Important: the sibling
 *     app.rocksky.scrobble.getScrobbles returns the GLOBAL recent-scrobble
 *     feed whenever the DID has no Rocksky data — it never goes empty —
 *     so it must never back a per-profile tab. The actor endpoint returns
 *     {"scrobbles":[]} for unknown DIDs instead, which is what lets the tab
 *     hide itself on non-Rocksky accounts.
 *   - app.rocksky.player.getCurrentlyPlaying — a DID's live now-playing
 *     state, used for the "Listening to ..." bio line. Rocksky also exposes
 *     a Spotify-specific app.rocksky.spotify.getCurrentlyPlaying; this app
 *     tries the general player endpoint first and falls back to the Spotify
 *     one (see RockskyRepository.getNowPlaying) since not every scrobbler
 *     integration necessarily answers the general one.
 *  The actor-endpoint scrobble field names (title, artist, album, albumArt,
 *  uri, ...) were verified against a live API response (2026-09-18); the
 *  now-playing fields are reconstructed from public docs/lexicon, so
 *  RockskyRepository parses them defensively (every field
 *  optional/defaulted) and a mismatch degrades to "no data" instead of
 *  crashing. */
interface RockskyApi {

    @GET("xrpc/app.rocksky.actor.getActorScrobbles")
    suspend fun getScrobbles(
        @Query("did") did: String,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0
    ): Response<RockskyScrobblesResponse>

    @GET("xrpc/app.rocksky.player.getCurrentlyPlaying")
    suspend fun getCurrentlyPlaying(@Query("actor") actor: String): Response<RockskyNowPlayingDto>

    @GET("xrpc/app.rocksky.spotify.getCurrentlyPlaying")
    suspend fun getSpotifyCurrentlyPlaying(@Query("actor") actor: String): Response<RockskyNowPlayingDto>
}

data class RockskyScrobblesResponse(
    val scrobbles: List<RockskyScrobbleDto>? = null,
    val cursor: String? = null
)

data class RockskyScrobbleDto(
    val uri: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    // The actor endpoint's real cover-art field (verified live); the
    // global-feed variant used `cover` instead — kept as a fallback.
    val albumArt: String? = null,
    val cover: String? = null,
    val did: String? = null,
    val handle: String? = null,
    // Some responses may nest cover art under a `track` object instead of
    // flat fields — checked as a fallback when parsing (see
    // RockskyRepository.toModel()).
    val track: RockskyTrackDto? = null,
    val date: String? = null,
    val createdAt: String? = null
)

data class RockskyTrackDto(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArt: String? = null,
    val duration: Long? = null
)

data class RockskyNowPlayingDto(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArt: String? = null,
    val track: RockskyTrackDto? = null,
    val isPlaying: Boolean? = null,
    val playing: Boolean? = null
)
