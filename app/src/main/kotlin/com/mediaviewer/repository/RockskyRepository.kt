package com.mediaviewer.repository

import com.mediaviewer.model.RockskyTrack
import com.mediaviewer.network.NetworkClient
import com.mediaviewer.network.RockskyNowPlayingDto
import com.mediaviewer.network.RockskyScrobbleDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Item 16: Rocksky (rocksky.app) music-scrobbling integration — powers the
 *  profile's "Music History" tab and the "Listening to ..." bio line. See
 *  RockskyApi's own doc comment for the endpoints/lexicon this is built
 *  from, and its own note on field names: the scrobble fields were verified
 *  against a live actor-endpoint response (2026-09-18), while the
 *  now-playing fields are reconstructed from public docs. Every parse here is
 *  best-effort: a track missing a title/artist is dropped rather than shown
 *  blank, and any network/parse failure degrades to an empty/null result
 *  (this is a nice-to-have overlay on someone's profile, never something
 *  worth surfacing an error for). */
class RockskyRepository {

    private val api = NetworkClient.buildRockskyApi()

    private fun RockskyScrobbleDto.toModel(): RockskyTrack? {
        val title = title ?: track?.title ?: return null
        val artist = artist ?: track?.artist ?: return null
        return RockskyTrack(
            title = title,
            artist = artist,
            album = album ?: track?.album ?: "",
            albumArtUrl = albumArt ?: cover ?: track?.albumArt,
            playedAt = date ?: createdAt ?: "",
            uri = uri ?: ""
        )
    }

    private fun RockskyNowPlayingDto.toModel(): RockskyTrack? {
        val playing = isPlaying ?: playing ?: true
        if (!playing) return null
        val title = title ?: track?.title ?: return null
        val artist = artist ?: track?.artist ?: return null
        return RockskyTrack(
            title = title,
            artist = artist,
            album = album ?: track?.album ?: "",
            albumArtUrl = albumArt ?: track?.albumArt
        )
    }

    /** [did]'s scrobble history, most recent first — the Music History tab. */
    suspend fun getScrobbles(did: String, limit: Int = 30, offset: Int = 0): Result<List<RockskyTrack>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.getScrobbles(did, limit, offset)
                val dtos = resp.body()?.scrobbles ?: error("getScrobbles ${resp.code()}")
                dtos
                    // Belt-and-braces: only ever surface the profile owner's
                    // own scrobbles. The actor endpoint returns
                    // {"scrobbles":[]} for DIDs with no Rocksky data, but if
                    // a response ever leaked entries from the global feed,
                    // their record URIs wouldn't live under this DID —
                    // drop any such stragglers so the tab can't show
                    // someone else's tracks.
                    .filter { dto ->
                        val uri = dto.uri ?: return@filter true
                        uri.startsWith("at://$did/")
                    }
                    .mapNotNull { it.toModel() }
            }
        }

    /** What [inferNowPlaying] found: whether the account has any Music
     *  History at all, and the inferred "Listening to" track (null = none). */
    data class InferredNowPlaying(val hasHistory: Boolean, val track: RockskyTrack?)

    /**
     * "Listening to" workaround: Rocksky's live now-playing endpoints rarely
     * answer, so the latest scrobble stands in for it. If that song started
     * less than [windowMs] (5 minutes) ago, it's shown as what the person is
     * listening to until 5 minutes after it started — i.e. whatever is left
     * of the 5 minutes once the time since it started is taken off. A newer
     * song showing up in the history replaces it (the caller re-checks).
     */
    suspend fun inferNowPlaying(did: String, windowMs: Long = 5 * 60_000L): InferredNowPlaying = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.getScrobbles(did, 1, 0)
            val dto = resp.body()?.scrobbles?.firstOrNull()
                ?.takeIf { d -> d.uri?.startsWith("at://$did/") != false }
                ?: return@runCatching InferredNowPlaying(false, null)
            val startMs = parseTimeMs(dto.date ?: dto.createdAt) ?: return@runCatching InferredNowPlaying(true, null)
            val now = System.currentTimeMillis()
            val endsAt = startMs + windowMs
            // A little tolerance for a scrobble clock slightly ahead of ours.
            if (now < startMs - 60_000L || now >= endsAt) return@runCatching InferredNowPlaying(true, null)
            InferredNowPlaying(true, dto.toModel()?.copy(endsAtMs = endsAt))
        }.getOrDefault(InferredNowPlaying(false, null))
    }

    private fun parseTimeMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { n -> return if (n < 10_000_000_000L) n * 1000 else n }
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /** [did]'s live now-playing track, or null if nothing is currently
     *  playing (or the account has no scrobbler connected at all) — the
     *  "Listening to ..." bio line. Tries the general player endpoint
     *  first, then falls back to the Spotify-specific one, since not every
     *  scrobbler integration necessarily answers the general one. */
    suspend fun getNowPlaying(did: String): RockskyTrack? = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.getCurrentlyPlaying(did)
            if (resp.isSuccessful) resp.body()?.toModel() else null
        }.getOrNull()
            ?: runCatching {
                val resp = api.getSpotifyCurrentlyPlaying(did)
                if (resp.isSuccessful) resp.body()?.toModel() else null
            }.getOrNull()
    }
}
