package com.mediaviewer.util

import android.content.Context
import com.mediaviewer.model.LiveNowPlatform
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.widget.LiveLinkWidgetProvider
import com.mediaviewer.worker.LiveLinkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Single source of truth for the Live Link feature (Settings toggle,
 *  the resizable home-screen widget, and the Hub's bottom row all funnel
 *  through here) — so a tap in any one of those three surfaces behaves
 *  identically and can never leave the other two showing a stale state.
 *  Deliberately a plain object with an explicit Context param (not tied to
 *  a ViewModel/AndroidViewModel) since the widget's AppWidgetProvider and
 *  the WorkManager CoroutineWorker both need to call this from places that
 *  have no ViewModel at all. */
object LiveLinkManager {

    /** Real Bluesky client max — see BlueskyRepository.setLiveNowStatus's
     *  doc comment. This is "the maximum length it can be until ended" from
     *  the feature request: every go-live call (including the periodic
     *  worker's re-bump while the stream's confirmed still up) sets the
     *  status to expire this far out, rather than asking the person to pick
     *  a duration up front. */
    const val MAX_DURATION_MINUTES = 240

    /** How often the periodic worker re-checks whether the stream's still
     *  actually up. 15 minutes is WorkManager's own minimum periodic
     *  interval — see LiveLinkCheckWorker. */
    const val CHECK_INTERVAL_MINUTES = 15L

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) = PreferencesManager(context.applicationContext)

    /** Turns a Live Link ON: writes the Bluesky status record, persists the
     *  active/expiry state, schedules the periodic checker, and nudges the
     *  widget to redraw. `channelUrl` is whatever was saved in Settings for
     *  that platform. */
    suspend fun goLive(context: Context, platform: LiveNowPlatform, channelUrl: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val title = if (platform == LiveNowPlatform.TWITCH) "Live on Twitch" else "Live on YouTube"
            withFreshToken(context) { repo, token, did ->
                repo.setLiveNowStatus(token, did, channelUrl, title, MAX_DURATION_MINUTES)
            }.map {
                val expiresAt = System.currentTimeMillis() + MAX_DURATION_MINUTES * 60_000L
                prefs(context).setLiveActive(platform, expiresAt)
                LiveLinkScheduler.schedule(context)
                LiveLinkWidgetProvider.requestUpdateAll(context)
                Unit
            }
        }

    /** Turns the active Live Link back OFF — manual "End ... Link" tap, or
     *  the periodic worker having confirmed the stream itself ended.
     *  Clearing local state always happens even if the network call fails
     *  (e.g. offline, or the record was already gone) — a stuck "still
     *  live" badge in this app's own UI is worse than a stale Bluesky
     *  record that will itself expire on its own regardless. */
    suspend fun endLive(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val result = withFreshToken(context) { repo, token, did -> repo.clearLiveNowStatus(token, did) }
        prefs(context).clearLiveActive()
        LiveLinkScheduler.cancel(context)
        LiveLinkWidgetProvider.requestUpdateAll(context)
        result
    }

    /** Called by the periodic worker only. If the saved channel is
     *  confirmed no longer live, ends it; otherwise re-bumps the Bluesky
     *  status back out to the full [MAX_DURATION_MINUTES] window so "live
     *  for up to 4 hours, checked periodically" doesn't quietly run down
     *  and expire mid-stream. Every liveness check is best-effort — a
     *  failed/timed-out/inconclusive check never ends the badge on its own
     *  (only a *confirmed offline* result does), since a flaky check is far
     *  more likely than the stream actually having ended in the 15 minutes
     *  since the last one. */
    suspend fun checkAndMaybeRenew(context: Context) = withContext(Dispatchers.IO) {
        val state = prefs(context).liveLinkState.first()
        val platform = state.activePlatform ?: return@withContext
        val url = if (platform == LiveNowPlatform.TWITCH) state.twitchUrl else state.youtubeUrl
        if (url.isNullOrBlank()) { endLive(context); return@withContext }

        val liveness = when (platform) {
            LiveNowPlatform.TWITCH -> checkTwitchLive(url)
            LiveNowPlatform.YOUTUBE -> checkYouTubeLive(url)
            else -> null
        }
        if (liveness == false) {
            endLive(context)
        } else {
            // Still live (or the check was inconclusive) — bump the expiry
            // back out to the max window and keep going.
            goLive(context, platform, url)
        }
    }

    /** Twitch has no unauthenticated "is this channel live" endpoint of its
     *  own (the real Helix API needs an app client id/secret this app
     *  doesn't have anywhere to safely hold) — this uses decapi.me's public,
     *  no-auth uptime helper instead, the same "small unauthenticated
     *  helper, tolerate it disappearing" spirit as this file's other
     *  best-effort integrations. Returns null (inconclusive) rather than
     *  false on any network/parsing failure, since decapi.me being down
     *  isn't evidence the stream ended.
     */
    private fun checkTwitchLive(channelUrl: String): Boolean? {
        val login = channelUrl.trimEnd('/').substringAfterLast('/').substringBefore('?').lowercase()
        if (login.isBlank()) return null
        return runCatching {
            val req = Request.Builder().url("https://decapi.me/twitch/uptime/$login").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                when {
                    body.contains("offline", ignoreCase = true) -> false
                    body.contains("not found", ignoreCase = true) -> null
                    body.isBlank() -> null
                    else -> true
                }
            }
        }.getOrNull()
    }

    /** Unofficial/best-effort: YouTube has no public no-auth "is this
     *  channel live" endpoint either, so this fetches the channel's own
     *  `/live` redirect page and looks for the `"isLiveNow":true` marker
     *  YouTube's own page data embeds for an active broadcast. This is HTML-
     *  scraping a page whose structure YouTube doesn't guarantee — if this
     *  stops matching after a YouTube redesign, the periodic check just goes
     *  back to always-inconclusive (never wrongly ends a live badge; worst
     *  case it just relies on the 4-hour hard expiry instead of ending
     *  early). Returns null (inconclusive) on any failure. */
    private fun checkYouTubeLive(channelUrl: String): Boolean? {
        val handle = channelUrl.trimEnd('/')
        val liveUrl = when {
            handle.contains("/channel/") -> handle.substringBefore("?") + "/live"
            handle.contains("youtube.com/@") -> handle.substringBefore("?") + "/live"
            else -> return null // not a recognizable channel URL shape
        }
        return runCatching {
            val req = Request.Builder().url(liveUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MediaViewer/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string().orEmpty()
                when {
                    html.contains("\"isLiveNow\":true") -> true
                    html.contains("\"isLive\":true") -> true
                    html.isBlank() -> null
                    else -> false
                }
            }
        }.getOrNull()
    }

    /** Shared "do this authenticated Bluesky call, refreshing the token
     *  once on a 401 and persisting the refreshed session" logic — the
     *  standalone-Context equivalent of MainViewModel's own
     *  refreshBskyTokenIfPossible/isAuthError pair, since the widget/worker
     *  have no ViewModel to borrow that from directly. */
    private suspend fun <T> withFreshToken(
        context: Context,
        block: suspend (repo: BlueskyRepository, token: String, did: String) -> Result<T>
    ): Result<T> {
        val p = prefs(context)
        val serviceUrl = p.bskyServiceUrl.first()
        val repo = BlueskyRepository().apply { updateServiceUrl(serviceUrl) }
        var token = p.bskyAccessJwt.first().orEmpty()
        val did = p.bskyDid.first().orEmpty()
        if (token.isBlank() || did.isBlank()) return Result.failure(IllegalStateException("Not logged in to Bluesky"))

        val first = block(repo, token, did)
        val message = first.exceptionOrNull()?.message
        val isAuthError = message != null && (message.contains("401") || message.contains("400") ||
            message.contains("ExpiredToken", true) || message.contains("InvalidToken", true))
        if (first.isSuccess || !isAuthError) return first

        val refreshJwt = p.bskyRefreshJwt.first().orEmpty()
        if (refreshJwt.isBlank()) return first
        val refreshed = repo.refreshToken(refreshJwt).getOrNull() ?: return first
        token = refreshed.accessJwt
        p.saveBskySession(refreshed.accessJwt, refreshed.refreshJwt, refreshed.did, refreshed.handle)
        return block(repo, token, refreshed.did)
    }
}
