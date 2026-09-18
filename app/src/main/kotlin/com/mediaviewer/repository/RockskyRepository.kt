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
 *  from, and its own note on field names being reconstructed from public
 *  docs rather than a verified live response. Every parse here is
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
            albumArtUrl = albumArt ?: track?.albumArt,
            playedAt = date ?: createdAt ?: ""
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
                resp.body()?.scrobbles?.mapNotNull { it.toModel() } ?: error("getScrobbles ${resp.code()}")
            }
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
