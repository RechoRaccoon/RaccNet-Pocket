package com.mediaviewer.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "media_viewer_prefs")

object PrefKeys {
    val BSKY_ACCESS_JWT      = stringPreferencesKey("bsky_access_jwt")
    val BSKY_REFRESH_JWT     = stringPreferencesKey("bsky_refresh_jwt")
    val BSKY_DID             = stringPreferencesKey("bsky_did")
    val BSKY_HANDLE          = stringPreferencesKey("bsky_handle")
    val BSKY_SERVICE_URL     = stringPreferencesKey("bsky_service_url")
    val E621_USERNAME        = stringPreferencesKey("e621_username")
    val E621_API_KEY         = stringPreferencesKey("e621_api_key")
    val DOWNLOAD_ON_LIKE     = booleanPreferencesKey("download_on_like")
    val LAST_MODE            = stringPreferencesKey("last_mode")
    val REDUCED_ANIMATIONS   = booleanPreferencesKey("reduced_animations")
    val LAST_FEED_URI        = stringPreferencesKey("last_feed_uri")
    val LAST_E621_TAGS       = stringPreferencesKey("last_e621_tags")
    val LAST_PICKER_TAB      = stringPreferencesKey("last_picker_tab")
    val E621_FOLLOWED_ARTISTS = stringSetPreferencesKey("e621_followed_artists")
    val COMBINE_LISTS_PACKS   = booleanPreferencesKey("combine_lists_packs")
    val AUTO_ADD_TO_ON_FOLLOW = booleanPreferencesKey("auto_add_to_on_follow")
    val LIQUID_GLASS          = booleanPreferencesKey("liquid_glass")
    // Item 26: 0f (fully flat/transparent, no blur) .. 1f (current full look).
    val LIQUID_GLASS_INTENSITY = floatPreferencesKey("liquid_glass_intensity")
    // Bug fix: separate dial for rim/outline strength, split out from the
    // background blur/tint dial above so the rim can be turned down (or up)
    // independently of the background effect.
    val GLASS_RIM_INTENSITY    = floatPreferencesKey("glass_rim_intensity")
    // Item 7: whether the rim gradient's brighter second stop is on at all —
    // see glassRimVibrantSecondary's own doc comment.
    val GLASS_RIM_VIBRANT_SECONDARY = booleanPreferencesKey("glass_rim_vibrant_secondary")
    val HIDE_TEXT_ONLY_POSTS  = booleanPreferencesKey("hide_text_only_posts")
    val HISTORY_JSON          = stringPreferencesKey("history_json")
    // Hub Reviews/Blogs cache — persisted so a cold restart can show the
    // subscribed-accounts Reviews/Blogs sections instantly from disk while
    // a fresh fetch runs in the background, instead of a blank section
    // until that fetch completes.
    val HUB_REVIEWS_CACHE_JSON = stringPreferencesKey("hub_reviews_cache_json")
    val HUB_BLOGS_CACHE_JSON   = stringPreferencesKey("hub_blogs_cache_json")
    val HUB_CACHE_HYDRATED_AT  = longPreferencesKey("hub_cache_hydrated_at")
    // Item (this session): Mutuals row cache — same "instant from disk on
    // cold start, then a fresh fetch replaces it" shape as the Reviews/Blogs
    // cache just above, so the Mutuals row doesn't sit blank until
    // dmConversations' own network fetch resolves.
    val HUB_MUTUALS_CACHE_JSON = stringPreferencesKey("hub_mutuals_cache_json")
    // Phase 4 — on-device translation
    val TRANSLATE_ENABLED     = booleanPreferencesKey("translate_enabled")
    val TRANSLATE_TARGET_LANG = stringPreferencesKey("translate_target_lang")
    // Phase 4 — custom app-wide font pack
    val CUSTOM_FONT_PATH      = stringPreferencesKey("custom_font_path")
    val CUSTOM_FONT_NAME      = stringPreferencesKey("custom_font_name")
    // Item (this session): local-only "Subscribe" lists on profiles' Reviews/
    // Blogs tabs — the Hub's Reviews/Blogs sections now pull only from
    // whichever accounts are in these sets (subscribing is per-section: an
    // account can be subscribed for Reviews, Blogs, both, or neither), a
    // direct-PDS-per-account model instead of the removed Jetstream/firehose
    // "everyone you follow" pipeline. Never synced anywhere.
    val SUBSCRIBED_REVIEW_DIDS = stringSetPreferencesKey("subscribed_review_dids")
    val SUBSCRIBED_BLOG_DIDS   = stringSetPreferencesKey("subscribed_blog_dids")
    // AI Tagging feature: realtime tag-on-like toggle. The "has an initial
    // tagging pass ever completed" state isn't stored separately — it's
    // derived at runtime from TagDatabase.scannedCount() > 0, so it can
    // never drift out of sync with the actual dataset on disk.
    val TAG_POST_WHEN_LIKED    = booleanPreferencesKey("tag_post_when_liked")
    // Item 6: parallel tagging slider (1-10 posts at once).
    val TAG_CONCURRENCY        = intPreferencesKey("tag_concurrency")

    // ── Live Link widget ─────────────────────────────────────────────────
    // Saved channel URLs (Settings input) — either/both may be set. The
    // widget/Hub row's toggle buttons only appear for whichever of these is
    // non-blank.
    val LIVE_TWITCH_URL       = stringPreferencesKey("live_twitch_url")
    val LIVE_YOUTUBE_URL      = stringPreferencesKey("live_youtube_url")
    // Which platform's badge is currently ON ("TWITCH"/"YOUTUBE"), or absent
    // if neither. Read by the widget (a separate RemoteViews surface with no
    // ViewModel) and the periodic check worker, so it's the single source of
    // truth for "is a Live Link currently active" — not just UI state.
    val LIVE_ACTIVE_PLATFORM  = stringPreferencesKey("live_active_platform")
    // Epoch-millis this account's app.bsky.actor.status record is set to
    // expire at — used to show "time remaining" and as the periodic worker's
    // own fallback (if a check is somehow missed, the badge still expires on
    // Bluesky's side on its own at this same moment).
    val LIVE_EXPIRES_AT_MS    = longPreferencesKey("live_expires_at_ms")
    // Cached copy of the logged-in account's own avatar URL — kept in sync
    // whenever loadSelfProfileSuspend() resolves (see MainViewModel), purely
    // so the widget (a separate process/RemoteViews surface that can't run
    // Compose/Coil the normal way) has something to sample a bubble tint
    // from without needing its own network+auth round trip just to color
    // itself.
    val SELF_AVATAR_URL_CACHE = stringPreferencesKey("self_avatar_url_cache")

    // Item (this session): profile row layout toggle. The icon-only layout
    // (left half = content-type filters, right half =
    // Posts/Reposts/Likes/Blogs/Reviews/Backlog/Vods source) was tried as a
    // default but reverted per feedback — the classic two-row, text-label
    // layout (ProfileTabsRow/ProfileSubFilterRow) is the default again and
    // the settings toggle for this was removed, so this key is no longer
    // read anywhere; it's kept only so any previously-persisted value
    // doesn't dangle as an orphaned DataStore entry. The icon layout
    // composables (ProfileIconTabRow etc.) are still present in
    // ProfileOverlay.kt, just unreachable, in case this is revisited later.
    val CLASSIC_PROFILE_TAB_ROW = booleanPreferencesKey("classic_profile_tab_row")

    // Feature request #4: an on-disk cache of each visited profile's tab
    // strip (which tabs it has, plus a trimmed snapshot of each tab's
    // content) so reopening a profile can show its tabs and a first batch
    // of results *instantly*, the same way the Hub's Reviews/Blogs sections
    // do off HUB_REVIEWS_CACHE_JSON/HUB_BLOGS_CACHE_JSON above — see
    // MainViewModel's profileTabCache doc comment for the read/refresh/
      // reconcile flow. A single JSON object keyed by author did, capped to
    // a bounded number of most-recently-used profiles (see
    // MainViewModel.PROFILE_TAB_CACHE_MAX_ENTRIES) so this can't grow
    // unbounded from someone who visits hundreds of different profiles.
    val PROFILE_TAB_CACHE_JSON = stringPreferencesKey("profile_tab_cache_json")

    // Feature request #7: experimental 3-wide variant of the Pinterest-style
    // masonry used by the Posts tab's All/Images filters (default is 2
    // columns).
    val PINTEREST_THREE_COLUMNS = booleanPreferencesKey("pinterest_three_columns")

    // Feature request #8: "I hate fun" — blurs Bluesky-labeled sexual/adult
    // content behind a tap-to-reveal cover instead of filtering it out.
    val HATE_FUN_BLUR_NSFW = booleanPreferencesKey("hate_fun_blur_nsfw")

    // Fix (per feedback): square-grid tiles render as flat squares (no
    // rounded corners, no outline) by default; this opts back into the old
    // rounded + outlined tiles.
    val SQUARE_GRID_ROUNDED = booleanPreferencesKey("square_grid_rounded")

    // ── Blogs/Reviews auto-subscribe (feature: automatic subscriptions) ───
    // The actual review/blog *content* cache already exists —
    // HUB_REVIEWS_CACHE_JSON/HUB_BLOGS_CACHE_JSON above, written by
    // MainViewModel.loadFriendsReviewsIfNeeded — so nothing new is needed
    // there. What's new is just tracking the one-time follower scan.
    val FOLLOWER_SCAN_COMPLETED    = booleanPreferencesKey("follower_scan_completed")
    val FOLLOWER_SCAN_LAST_RUN_MS  = longPreferencesKey("follower_scan_last_run_ms")
    // Resumability: if the app is closed mid-scan, this is the last
    // follows-list pagination cursor successfully processed so a re-launch
    // of the scan (manual retry from Settings) doesn't have to start over
    // from scratch and re-spend PDS calls on accounts it already checked.
    val FOLLOWER_SCAN_CURSOR       = stringPreferencesKey("follower_scan_cursor")
}


class PreferencesManager(private val context: Context) {

    val bskyAccessJwt: Flow<String?>  = context.dataStore.data.map { it[PrefKeys.BSKY_ACCESS_JWT] }
    val bskyRefreshJwt: Flow<String?> = context.dataStore.data.map { it[PrefKeys.BSKY_REFRESH_JWT] }
    val bskyDid: Flow<String?>        = context.dataStore.data.map { it[PrefKeys.BSKY_DID] }
    val bskyHandle: Flow<String?>     = context.dataStore.data.map { it[PrefKeys.BSKY_HANDLE] }
    val bskyServiceUrl: Flow<String>  = context.dataStore.data.map { it[PrefKeys.BSKY_SERVICE_URL] ?: "https://bsky.social/" }
    val e621Username: Flow<String?>   = context.dataStore.data.map { it[PrefKeys.E621_USERNAME] }
    val e621ApiKey: Flow<String?>     = context.dataStore.data.map { it[PrefKeys.E621_API_KEY] }
    val downloadOnLike: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.DOWNLOAD_ON_LIKE] ?: false }
    val lastMode: Flow<String>        = context.dataStore.data.map { it[PrefKeys.LAST_MODE] ?: "BLUESKY" }
    val reducedAnimations: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.REDUCED_ANIMATIONS] ?: false }
    val lastFeedUri: Flow<String?>    = context.dataStore.data.map { it[PrefKeys.LAST_FEED_URI] }
    val lastE621Tags: Flow<String?>   = context.dataStore.data.map { it[PrefKeys.LAST_E621_TAGS] }
    val lastPickerTab: Flow<String>   = context.dataStore.data.map { it[PrefKeys.LAST_PICKER_TAB] ?: "LISTS" }
    val e621FollowedArtists: Flow<Set<String>> = context.dataStore.data.map { it[PrefKeys.E621_FOLLOWED_ARTISTS] ?: emptySet() }
    val combineListsAndPacks: Flow<Boolean>    = context.dataStore.data.map { it[PrefKeys.COMBINE_LISTS_PACKS] ?: false }
    // Defaulted OFF — the "Add To" popup no longer opens automatically after
    // following someone unless the user opts in from Settings.
    val autoAddToOnFollow: Flow<Boolean>       = context.dataStore.data.map { it[PrefKeys.AUTO_ADD_TO_ON_FOLLOW] ?: false }
    val liquidGlass: Flow<Boolean>             = context.dataStore.data.map { it[PrefKeys.LIQUID_GLASS] ?: true }
    // Item 26: how strong the blur/magnify effect is while Glass Theme is on.
    val liquidGlassIntensity: Flow<Float>      = context.dataStore.data.map { it[PrefKeys.LIQUID_GLASS_INTENSITY] ?: 1f }
    // Bug fix: independent rim/outline strength dial, split out from the
    // background dial above.
    val glassRimIntensity: Flow<Float>         = context.dataStore.data.map { it[PrefKeys.GLASS_RIM_INTENSITY] ?: 1f }
    // Item 7: defaulted ON — every glass rim gradient blends its tint into a
    // brighter/more-saturated version of that same tint (rather than the
    // old neutral grey/white) as its middle stop. Turning this off collapses
    // the rim to a single flat reflected color with no gradient at all.
    val glassRimVibrantSecondary: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.GLASS_RIM_VIBRANT_SECONDARY] ?: true }
    // Settings Update: universally hides text-only posts (no image/video) from every feed.
    val hideTextOnlyPosts: Flow<Boolean>       = context.dataStore.data.map { it[PrefKeys.HIDE_TEXT_ONLY_POSTS] ?: false }
    // Settings Update: raw JSON array of HistoryEntry, newest first, capped at write time.
    val historyJson: Flow<String>              = context.dataStore.data.map { it[PrefKeys.HISTORY_JSON] ?: "[]" }
    val hubReviewsCacheJson: Flow<String>       = context.dataStore.data.map { it[PrefKeys.HUB_REVIEWS_CACHE_JSON] ?: "[]" }
    val hubBlogsCacheJson: Flow<String>         = context.dataStore.data.map { it[PrefKeys.HUB_BLOGS_CACHE_JSON] ?: "[]" }
    val hubMutualsCacheJson: Flow<String>       = context.dataStore.data.map { it[PrefKeys.HUB_MUTUALS_CACHE_JSON] ?: "[]" }
    val hubCacheHydratedAt: Flow<Long>          = context.dataStore.data.map { it[PrefKeys.HUB_CACHE_HYDRATED_AT] ?: 0L }
    val subscribedReviewDids: Flow<Set<String>> = context.dataStore.data.map { it[PrefKeys.SUBSCRIBED_REVIEW_DIDS] ?: emptySet() }
    val subscribedBlogDids: Flow<Set<String>>   = context.dataStore.data.map { it[PrefKeys.SUBSCRIBED_BLOG_DIDS] ?: emptySet() }
    // Defaults false: the new single-row icon layout is the default profile
    // tab row; this opts back into the classic two-row text-label layout.
    val classicProfileTabRow: Flow<Boolean>     = context.dataStore.data.map { it[PrefKeys.CLASSIC_PROFILE_TAB_ROW] ?: false }
    val profileTabCacheJson: Flow<String>       = context.dataStore.data.map { it[PrefKeys.PROFILE_TAB_CACHE_JSON] ?: "{}" }
    val pinterestThreeColumns: Flow<Boolean>    = context.dataStore.data.map { it[PrefKeys.PINTEREST_THREE_COLUMNS] ?: false }
    val hateFunBlurNsfw: Flow<Boolean>          = context.dataStore.data.map { it[PrefKeys.HATE_FUN_BLUR_NSFW] ?: false }
    // Fix (per feedback): default false — flat square tiles with no outline;
    // true restores the old rounded + outlined tiles.
    val squareGridRounded: Flow<Boolean>        = context.dataStore.data.map { it[PrefKeys.SQUARE_GRID_ROUNDED] ?: false }
    val followerScanCompleted: Flow<Boolean>     = context.dataStore.data.map { it[PrefKeys.FOLLOWER_SCAN_COMPLETED] ?: false }
    val followerScanLastRunMs: Flow<Long>        = context.dataStore.data.map { it[PrefKeys.FOLLOWER_SCAN_LAST_RUN_MS] ?: 0L }
    val followerScanCursor: Flow<String?>        = context.dataStore.data.map { it[PrefKeys.FOLLOWER_SCAN_CURSOR] }
    // Phase 4: on-device translation toggle + preferred target language (BCP-47 tag).
    // Defaults to the device's own language so a fresh install "just works" without
    // the user having to hunt for the setting first.
    val translateEnabled: Flow<Boolean>        = context.dataStore.data.map { it[PrefKeys.TRANSLATE_ENABLED] ?: false }
    val translateTargetLang: Flow<String>      = context.dataStore.data.map {
        it[PrefKeys.TRANSLATE_TARGET_LANG] ?: java.util.Locale.getDefault().language.ifBlank { "en" }
    }
    // Phase 4: custom font pack — absolute path to the copied-in font file on
    // internal storage, plus its original display name for the Settings row.
    val customFontPath: Flow<String?>          = context.dataStore.data.map { it[PrefKeys.CUSTOM_FONT_PATH] }
    val customFontName: Flow<String?>          = context.dataStore.data.map { it[PrefKeys.CUSTOM_FONT_NAME] }
    val tagPostWhenLiked: Flow<Boolean>        = context.dataStore.data.map { it[PrefKeys.TAG_POST_WHEN_LIKED] ?: false }
    val tagConcurrency: Flow<Int>              = context.dataStore.data.map { (it[PrefKeys.TAG_CONCURRENCY] ?: 3).coerceIn(1, 10) }

    // ── Live Link widget ─────────────────────────────────────────────────
    val liveTwitchUrl: Flow<String?>   = context.dataStore.data.map { it[PrefKeys.LIVE_TWITCH_URL] }
    val liveYoutubeUrl: Flow<String?>  = context.dataStore.data.map { it[PrefKeys.LIVE_YOUTUBE_URL] }
    val selfAvatarUrlCache: Flow<String?> = context.dataStore.data.map { it[PrefKeys.SELF_AVATAR_URL_CACHE] }
    // Bundled into one LiveLinkState Flow (rather than three separate ones)
    // so the Hub row and widget-preview code observe one atomic snapshot —
    // never a torn read where activePlatform updated but expiresAt hasn't
    // caught up yet (or vice versa).
    val liveLinkState: Flow<com.mediaviewer.model.LiveLinkState> = context.dataStore.data.map { prefs ->
        com.mediaviewer.model.LiveLinkState(
            twitchUrl = prefs[PrefKeys.LIVE_TWITCH_URL],
            youtubeUrl = prefs[PrefKeys.LIVE_YOUTUBE_URL],
            activePlatform = when (prefs[PrefKeys.LIVE_ACTIVE_PLATFORM]) {
                "TWITCH" -> com.mediaviewer.model.LiveNowPlatform.TWITCH
                "YOUTUBE" -> com.mediaviewer.model.LiveNowPlatform.YOUTUBE
                else -> null
            },
            expiresAtEpochMs = prefs[PrefKeys.LIVE_EXPIRES_AT_MS] ?: 0L
        )
    }

    suspend fun setLiveTwitchUrl(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(PrefKeys.LIVE_TWITCH_URL) else prefs[PrefKeys.LIVE_TWITCH_URL] = url.trim()
        }
    }

    suspend fun setLiveYoutubeUrl(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(PrefKeys.LIVE_YOUTUBE_URL) else prefs[PrefKeys.LIVE_YOUTUBE_URL] = url.trim()
        }
    }

    /** Marks a Live Link as active — called right after the Bluesky status
     *  record write succeeds. One write covers both fields for the same
     *  reason setHubCache does: never a torn read of "active platform" vs.
     *  "expires at" for two different toggles. */
    suspend fun setLiveActive(platform: com.mediaviewer.model.LiveNowPlatform, expiresAtEpochMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.LIVE_ACTIVE_PLATFORM] = platform.name
            prefs[PrefKeys.LIVE_EXPIRES_AT_MS] = expiresAtEpochMs
        }
    }

    /** Clears the active Live Link — called once the badge is confirmed torn
     *  down on Bluesky's side (manual "End Link" tap, or the periodic worker
     *  finding the stream itself has ended). */
    suspend fun clearLiveActive() {
        context.dataStore.edit { prefs ->
            prefs.remove(PrefKeys.LIVE_ACTIVE_PLATFORM)
            prefs.remove(PrefKeys.LIVE_EXPIRES_AT_MS)
        }
    }

    suspend fun setSelfAvatarUrlCache(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(PrefKeys.SELF_AVATAR_URL_CACHE) else prefs[PrefKeys.SELF_AVATAR_URL_CACHE] = url
        }
    }

    suspend fun setTagPostWhenLiked(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.TAG_POST_WHEN_LIKED] = enabled }
    }

    suspend fun setTagConcurrency(value: Int) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.TAG_CONCURRENCY] = value.coerceIn(1, 10) }
    }

    suspend fun setTranslateEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.TRANSLATE_ENABLED] = enabled }
    }

    suspend fun setTranslateTargetLang(languageTag: String) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.TRANSLATE_TARGET_LANG] = languageTag }
    }

    suspend fun setCustomFontPath(path: String?) {
        context.dataStore.edit { prefs ->
            if (path == null) prefs.remove(PrefKeys.CUSTOM_FONT_PATH) else prefs[PrefKeys.CUSTOM_FONT_PATH] = path
        }
    }

    suspend fun setCustomFontName(name: String?) {
        context.dataStore.edit { prefs ->
            if (name == null) prefs.remove(PrefKeys.CUSTOM_FONT_NAME) else prefs[PrefKeys.CUSTOM_FONT_NAME] = name
        }
    }

    /** Toggles one account's Reviews-tab "Subscribe" state — added to (or
     *  removed from) the set the Hub's Reviews section reads from. */
    suspend fun toggleSubscribedReviewDid(did: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PrefKeys.SUBSCRIBED_REVIEW_DIDS] ?: emptySet()
            prefs[PrefKeys.SUBSCRIBED_REVIEW_DIDS] = if (did in current) current - did else current + did
        }
    }

    /** Blogs-tab equivalent of [toggleSubscribedReviewDid] — a separate list,
     *  since subscribing to someone's Reviews doesn't imply their Blogs. */
    suspend fun toggleSubscribedBlogDid(did: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PrefKeys.SUBSCRIBED_BLOG_DIDS] ?: emptySet()
            prefs[PrefKeys.SUBSCRIBED_BLOG_DIDS] = if (did in current) current - did else current + did
        }
    }

    // ── Automatic subscriptions (feature: auto-subscribe on profile open /
    // follower scan) ─────────────────────────────────────────────────────
    // Additive-only, unlike the manual toggle above: opening a profile (or
    // scanning followers) that turns out to have reviews/blogs should only
    // ever *add* that account, never remove one the user (or a past scan)
    // already added — removal stays a manual action if it's ever exposed.
    suspend fun addSubscribedReviewDidIfMissing(did: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PrefKeys.SUBSCRIBED_REVIEW_DIDS] ?: emptySet()
            if (did !in current) prefs[PrefKeys.SUBSCRIBED_REVIEW_DIDS] = current + did
        }
    }

    suspend fun addSubscribedBlogDidIfMissing(did: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PrefKeys.SUBSCRIBED_BLOG_DIDS] ?: emptySet()
            if (did !in current) prefs[PrefKeys.SUBSCRIBED_BLOG_DIDS] = current + did
        }
    }

    /** Bulk variant used by the follower scan — one DataStore write for a
     *  whole batch of newly-discovered accounts instead of one write per
     *  account, since the scan can turn up thousands of candidates. */
    suspend fun addSubscribedReviewDids(dids: Collection<String>) {
        if (dids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[PrefKeys.SUBSCRIBED_REVIEW_DIDS] ?: emptySet()
            prefs[PrefKeys.SUBSCRIBED_REVIEW_DIDS] = current + dids
        }
    }

    suspend fun addSubscribedBlogDids(dids: Collection<String>) {
        if (dids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[PrefKeys.SUBSCRIBED_BLOG_DIDS] ?: emptySet()
            prefs[PrefKeys.SUBSCRIBED_BLOG_DIDS] = current + dids
        }
    }

    suspend fun setHideTextOnlyPosts(enabled: Boolean) {        context.dataStore.edit { prefs -> prefs[PrefKeys.HIDE_TEXT_ONLY_POSTS] = enabled }
    }

    suspend fun setHistoryJson(json: String) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.HISTORY_JSON] = json }
    }

    /** See HUB_REVIEWS_CACHE_JSON's comment — one write covers both lists
     *  plus the hydration timestamp so a caller never ends up with a
     *  timestamp that's newer than the data it's supposed to describe. */
    suspend fun setHubCache(reviewsJson: String, blogsJson: String, hydratedAt: Long) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.HUB_REVIEWS_CACHE_JSON] = reviewsJson
            prefs[PrefKeys.HUB_BLOGS_CACHE_JSON] = blogsJson
            prefs[PrefKeys.HUB_CACHE_HYDRATED_AT] = hydratedAt
        }
    }

    /** Mutuals-row equivalent of [setHubCache]. Kept as its own write since
     *  dmConversations loads on a different, earlier trigger than Reviews/
     *  Blogs (see loadDmConversationsBlocking) and shouldn't need to wait
     *  on — or block — that unrelated fetch. */
    suspend fun setHubMutualsCache(mutualsJson: String) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.HUB_MUTUALS_CACHE_JSON] = mutualsJson }
    }

    suspend fun setLiquidGlass(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.LIQUID_GLASS] = enabled }
    }

    suspend fun setLiquidGlassIntensity(intensity: Float) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.LIQUID_GLASS_INTENSITY] = intensity.coerceIn(0f, 1f) }
    }

    suspend fun setGlassRimIntensity(intensity: Float) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.GLASS_RIM_INTENSITY] = intensity.coerceIn(0f, 1f) }
    }
    suspend fun setGlassRimVibrantSecondary(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.GLASS_RIM_VIBRANT_SECONDARY] = enabled }
    }

    suspend fun setCombineListsAndPacks(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.COMBINE_LISTS_PACKS] = enabled }
    }

    suspend fun setAutoAddToOnFollow(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.AUTO_ADD_TO_ON_FOLLOW] = enabled }
    }

    suspend fun setLastPickerTab(tab: String) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.LAST_PICKER_TAB] = tab }
    }

    suspend fun followE621Artist(artist: String) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.E621_FOLLOWED_ARTISTS] = (prefs[PrefKeys.E621_FOLLOWED_ARTISTS] ?: emptySet()) + artist
        }
    }

    suspend fun unfollowE621Artist(artist: String) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.E621_FOLLOWED_ARTISTS] = (prefs[PrefKeys.E621_FOLLOWED_ARTISTS] ?: emptySet()) - artist
        }
    }

    suspend fun setLastFeedUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(PrefKeys.LAST_FEED_URI) else prefs[PrefKeys.LAST_FEED_URI] = uri
        }
    }

    suspend fun setLastE621Tags(tags: String) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.LAST_E621_TAGS] = tags }
    }

    suspend fun saveBskySession(accessJwt: String, refreshJwt: String, did: String, handle: String) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.BSKY_ACCESS_JWT]  = accessJwt
            prefs[PrefKeys.BSKY_REFRESH_JWT] = refreshJwt
            prefs[PrefKeys.BSKY_DID]         = did
            prefs[PrefKeys.BSKY_HANDLE]      = handle
        }
    }

    suspend fun clearBskySession() {
        context.dataStore.edit { prefs ->
            prefs.remove(PrefKeys.BSKY_ACCESS_JWT)
            prefs.remove(PrefKeys.BSKY_REFRESH_JWT)
            prefs.remove(PrefKeys.BSKY_DID)
            prefs.remove(PrefKeys.BSKY_HANDLE)
        }
    }

    suspend fun saveE621Credentials(username: String, apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.E621_USERNAME] = username
            prefs[PrefKeys.E621_API_KEY]  = apiKey
        }
    }

    suspend fun clearE621Credentials() {
        context.dataStore.edit { prefs ->
            prefs.remove(PrefKeys.E621_USERNAME)
            prefs.remove(PrefKeys.E621_API_KEY)
        }
    }

    suspend fun setDownloadOnLike(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.DOWNLOAD_ON_LIKE] = enabled }
    }

    suspend fun setLastMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.LAST_MODE] = mode }
    }

    suspend fun setReducedAnimations(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.REDUCED_ANIMATIONS] = enabled }
    }

    suspend fun setClassicProfileTabRow(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.CLASSIC_PROFILE_TAB_ROW] = enabled }
    }

    // Fix (per feedback): "Rounded grid tiles" setting.
    suspend fun setSquareGridRounded(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.SQUARE_GRID_ROUNDED] = enabled }
    }

    suspend fun setProfileTabCacheJson(json: String) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.PROFILE_TAB_CACHE_JSON] = json }
    }

    suspend fun setPinterestThreeColumns(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.PINTEREST_THREE_COLUMNS] = enabled }
    }

    suspend fun setHateFunBlurNsfw(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.HATE_FUN_BLUR_NSFW] = enabled }
    }

    suspend fun setFollowerScanCompleted(completed: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.FOLLOWER_SCAN_COMPLETED] = completed }
    }

    suspend fun setFollowerScanLastRunMs(ms: Long) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.FOLLOWER_SCAN_LAST_RUN_MS] = ms }
    }

    suspend fun setFollowerScanCursor(cursor: String?) {
        context.dataStore.edit { prefs ->
            if (cursor == null) prefs.remove(PrefKeys.FOLLOWER_SCAN_CURSOR) else prefs[PrefKeys.FOLLOWER_SCAN_CURSOR] = cursor
        }
    }
}
