package com.mediaviewer.util

import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getBooleanFlow
import com.russhwolf.settings.coroutines.getFloatFlow
import com.russhwolf.settings.coroutines.getIntFlow
import com.russhwolf.settings.coroutines.getLongFlow
import com.russhwolf.settings.coroutines.getStringFlow
import com.russhwolf.settings.coroutines.getStringSetFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default BCP-47 language tag used when no translation target language has
 * been stored yet. The legacy Android build defaulted this to the device's
 * own locale language (java.util.Locale.getDefault().language); in
 * commonMain there is no expect-free locale API, so this defaults to "en"
 * for now — the platform worker may wire a real per-platform locale into
 * this helper later.
 */
fun defaultLanguageTag(): String = "en"

/** Preference key names — kept identical to the legacy DataStore build so
 *  existing Android installs keep their stored values after migration. */
object PrefKeys {
    const val BSKY_ACCESS_JWT       = "bsky_access_jwt"
    const val BSKY_REFRESH_JWT      = "bsky_refresh_jwt"
    const val BSKY_DID              = "bsky_did"
    const val BSKY_HANDLE           = "bsky_handle"
    const val BSKY_SERVICE_URL      = "bsky_service_url"
    const val E621_USERNAME         = "e621_username"
    const val E621_API_KEY          = "e621_api_key"
    const val DOWNLOAD_ON_LIKE      = "download_on_like"
    const val LAST_MODE             = "last_mode"
    const val REDUCED_ANIMATIONS    = "reduced_animations"
    const val LAST_FEED_URI         = "last_feed_uri"
    const val LAST_E621_TAGS        = "last_e621_tags"
    const val LAST_PICKER_TAB       = "last_picker_tab"
    const val E621_FOLLOWED_ARTISTS = "e621_followed_artists"
    const val COMBINE_LISTS_PACKS   = "combine_lists_packs"
    const val AUTO_ADD_TO_ON_FOLLOW = "auto_add_to_on_follow"
    const val LIQUID_GLASS          = "liquid_glass"
    // Item 26: 0f (fully flat/transparent, no blur) .. 1f (current full look).
    const val LIQUID_GLASS_INTENSITY = "liquid_glass_intensity"
    // Bug fix: separate dial for rim/outline strength, split out from the
    // background blur/tint dial above so the rim can be turned down (or up)
    // independently of the background effect.
    const val GLASS_RIM_INTENSITY    = "glass_rim_intensity"
    const val HIDE_TEXT_ONLY_POSTS  = "hide_text_only_posts"
    const val HISTORY_JSON          = "history_json"
    // Hub Reviews/Blogs cache — persisted so a cold restart can show the
    // subscribed-accounts Reviews/Blogs sections instantly from disk while
    // a fresh fetch runs in the background, instead of a blank section
    // until that fetch completes.
    const val HUB_REVIEWS_CACHE_JSON = "hub_reviews_cache_json"
    const val HUB_BLOGS_CACHE_JSON   = "hub_blogs_cache_json"
    const val HUB_CACHE_HYDRATED_AT  = "hub_cache_hydrated_at"
    // Item (this session): Mutuals row cache — same "instant from disk on
    // cold start, then a fresh fetch replaces it" shape as the Reviews/Blogs
    // cache just above, so the Mutuals row doesn't sit blank until
    // dmConversations' own network fetch resolves.
    const val HUB_MUTUALS_CACHE_JSON = "hub_mutuals_cache_json"
    // Phase 4 — on-device translation
    const val TRANSLATE_ENABLED     = "translate_enabled"
    const val TRANSLATE_TARGET_LANG = "translate_target_lang"
    // Phase 4 — custom app-wide font pack
    const val CUSTOM_FONT_PATH      = "custom_font_path"
    const val CUSTOM_FONT_NAME      = "custom_font_name"
    // Item (this session): local-only "Subscribe" lists on profiles' Reviews/
    // Blogs tabs — the Hub's Reviews/Blogs sections now pull only from
    // whichever accounts are in these sets (subscribing is per-section: an
    // account can be subscribed for Reviews, Blogs, both, or neither), a
    // direct-PDS-per-account model instead of the removed Jetstream/firehose
    // "everyone you follow" pipeline. Never synced anywhere.
    const val SUBSCRIBED_REVIEW_DIDS = "subscribed_review_dids"
    const val SUBSCRIBED_BLOG_DIDS   = "subscribed_blog_dids"
    // AI Tagging feature: realtime tag-on-like toggle. The "has an initial
    // tagging pass ever completed" state isn't stored separately — it's
    // derived at runtime from TagDatabase.scannedCount() > 0, so it can
    // never drift out of sync with the actual dataset on disk.
    const val TAG_POST_WHEN_LIKED    = "tag_post_when_liked"
    // Item 6: parallel tagging slider (1-10 posts at once).
    const val TAG_CONCURRENCY        = "tag_concurrency"
}

/**
 * Shared replacement for the legacy DataStore-backed PreferencesManager.
 * Backed by multiplatform-settings [ObservableSettings] (SharedPreferences
 * on Android, in-memory on web for now) with reactive flows from the
 * multiplatform-settings-coroutines artifact. Public API (names + types) is
 * kept identical to the legacy class — the viewmodel worker depends on it.
 */
class PreferencesManager(private val settings: ObservableSettings) {

    // Read-modify-write helpers (toggleSubscribed*, follow/unfollow) can't
    // use DataStore's atomic edit{} here, so they serialize on this mutex.
    private val editMutex = Mutex()

    val bskyAccessJwt: Flow<String?>  = settings.getStringFlow(PrefKeys.BSKY_ACCESS_JWT, null)
    val bskyRefreshJwt: Flow<String?> = settings.getStringFlow(PrefKeys.BSKY_REFRESH_JWT, null)
    val bskyDid: Flow<String?>        = settings.getStringFlow(PrefKeys.BSKY_DID, null)
    val bskyHandle: Flow<String?>     = settings.getStringFlow(PrefKeys.BSKY_HANDLE, null)
    val bskyServiceUrl: Flow<String>  = settings.getStringFlow(PrefKeys.BSKY_SERVICE_URL, "https://bsky.social/")
    val e621Username: Flow<String?>   = settings.getStringFlow(PrefKeys.E621_USERNAME, null)
    val e621ApiKey: Flow<String?>     = settings.getStringFlow(PrefKeys.E621_API_KEY, null)
    val downloadOnLike: Flow<Boolean> = settings.getBooleanFlow(PrefKeys.DOWNLOAD_ON_LIKE, false)
    val lastMode: Flow<String>        = settings.getStringFlow(PrefKeys.LAST_MODE, "BLUESKY")
    val reducedAnimations: Flow<Boolean> = settings.getBooleanFlow(PrefKeys.REDUCED_ANIMATIONS, false)
    val lastFeedUri: Flow<String?>    = settings.getStringFlow(PrefKeys.LAST_FEED_URI, null)
    val lastE621Tags: Flow<String?>   = settings.getStringFlow(PrefKeys.LAST_E621_TAGS, null)
    val lastPickerTab: Flow<String>   = settings.getStringFlow(PrefKeys.LAST_PICKER_TAB, "LISTS")
    val e621FollowedArtists: Flow<Set<String>> = settings.getStringSetFlow(PrefKeys.E621_FOLLOWED_ARTISTS, emptySet())
    val combineListsAndPacks: Flow<Boolean>    = settings.getBooleanFlow(PrefKeys.COMBINE_LISTS_PACKS, false)
    // Defaulted OFF — the "Add To" popup no longer opens automatically after
    // following someone unless the user opts in from Settings.
    val autoAddToOnFollow: Flow<Boolean>       = settings.getBooleanFlow(PrefKeys.AUTO_ADD_TO_ON_FOLLOW, false)
    val liquidGlass: Flow<Boolean>             = settings.getBooleanFlow(PrefKeys.LIQUID_GLASS, true)
    // Item 26: how strong the blur/magnify effect is while Glass Theme is on.
    val liquidGlassIntensity: Flow<Float>      = settings.getFloatFlow(PrefKeys.LIQUID_GLASS_INTENSITY, 1f)
    // Bug fix: independent rim/outline strength dial, split out from the
    // background dial above.
    val glassRimIntensity: Flow<Float>         = settings.getFloatFlow(PrefKeys.GLASS_RIM_INTENSITY, 1f)
    // Settings Update: universally hides text-only posts (no image/video) from every feed.
    val hideTextOnlyPosts: Flow<Boolean>       = settings.getBooleanFlow(PrefKeys.HIDE_TEXT_ONLY_POSTS, false)
    // Settings Update: raw JSON array of HistoryEntry, newest first, capped at write time.
    val historyJson: Flow<String>              = settings.getStringFlow(PrefKeys.HISTORY_JSON, "[]")
    val hubReviewsCacheJson: Flow<String>       = settings.getStringFlow(PrefKeys.HUB_REVIEWS_CACHE_JSON, "[]")
    val hubBlogsCacheJson: Flow<String>         = settings.getStringFlow(PrefKeys.HUB_BLOGS_CACHE_JSON, "[]")
    val hubMutualsCacheJson: Flow<String>       = settings.getStringFlow(PrefKeys.HUB_MUTUALS_CACHE_JSON, "[]")
    val hubCacheHydratedAt: Flow<Long>          = settings.getLongFlow(PrefKeys.HUB_CACHE_HYDRATED_AT, 0L)
    val subscribedReviewDids: Flow<Set<String>> = settings.getStringSetFlow(PrefKeys.SUBSCRIBED_REVIEW_DIDS, emptySet())
    val subscribedBlogDids: Flow<Set<String>>   = settings.getStringSetFlow(PrefKeys.SUBSCRIBED_BLOG_DIDS, emptySet())
    // Phase 4: on-device translation toggle + preferred target language (BCP-47 tag).
    // Defaults to the device's own language so a fresh install "just works" without
    // the user having to hunt for the setting first.
    val translateEnabled: Flow<Boolean>        = settings.getBooleanFlow(PrefKeys.TRANSLATE_ENABLED, false)
    val translateTargetLang: Flow<String>      = settings.getStringFlow(PrefKeys.TRANSLATE_TARGET_LANG, defaultLanguageTag())
    // Phase 4: custom font pack — absolute path to the copied-in font file on
    // internal storage, plus its original display name for the Settings row.
    val customFontPath: Flow<String?>          = settings.getStringFlow(PrefKeys.CUSTOM_FONT_PATH, null)
    val customFontName: Flow<String?>          = settings.getStringFlow(PrefKeys.CUSTOM_FONT_NAME, null)
    val tagPostWhenLiked: Flow<Boolean>        = settings.getBooleanFlow(PrefKeys.TAG_POST_WHEN_LIKED, false)
    val tagConcurrency: Flow<Int>              = settings.getIntFlow(PrefKeys.TAG_CONCURRENCY, 3).map { it.coerceIn(1, 10) }

    suspend fun setTagPostWhenLiked(enabled: Boolean) {
        settings.putBoolean(PrefKeys.TAG_POST_WHEN_LIKED, enabled)
    }

    suspend fun setTagConcurrency(value: Int) {
        settings.putInt(PrefKeys.TAG_CONCURRENCY, value.coerceIn(1, 10))
    }

    suspend fun setTranslateEnabled(enabled: Boolean) {
        settings.putBoolean(PrefKeys.TRANSLATE_ENABLED, enabled)
    }

    suspend fun setTranslateTargetLang(languageTag: String) {
        settings.putString(PrefKeys.TRANSLATE_TARGET_LANG, languageTag)
    }

    suspend fun setCustomFontPath(path: String?) {
        if (path == null) settings.remove(PrefKeys.CUSTOM_FONT_PATH) else settings.putString(PrefKeys.CUSTOM_FONT_PATH, path)
    }

    suspend fun setCustomFontName(name: String?) {
        if (name == null) settings.remove(PrefKeys.CUSTOM_FONT_NAME) else settings.putString(PrefKeys.CUSTOM_FONT_NAME, name)
    }

    /** Toggles one account's Reviews-tab "Subscribe" state — added to (or
     *  removed from) the set the Hub's Reviews section reads from. */
    suspend fun toggleSubscribedReviewDid(did: String) {
        editMutex.withLock {
            val current = settings.getStringSetOrNull(PrefKeys.SUBSCRIBED_REVIEW_DIDS) ?: emptySet()
            settings.putStringSet(PrefKeys.SUBSCRIBED_REVIEW_DIDS, if (did in current) current - did else current + did)
        }
    }

    /** Blogs-tab equivalent of [toggleSubscribedReviewDid] — a separate list,
     *  since subscribing to someone's Reviews doesn't imply their Blogs. */
    suspend fun toggleSubscribedBlogDid(did: String) {
        editMutex.withLock {
            val current = settings.getStringSetOrNull(PrefKeys.SUBSCRIBED_BLOG_DIDS) ?: emptySet()
            settings.putStringSet(PrefKeys.SUBSCRIBED_BLOG_DIDS, if (did in current) current - did else current + did)
        }
    }

    suspend fun setHideTextOnlyPosts(enabled: Boolean) {
        settings.putBoolean(PrefKeys.HIDE_TEXT_ONLY_POSTS, enabled)
    }

    suspend fun setHistoryJson(json: String) {
        settings.putString(PrefKeys.HISTORY_JSON, json)
    }

    /** See HUB_REVIEWS_CACHE_JSON's comment — one write covers both lists
     *  plus the hydration timestamp so a caller never ends up with a
     *  timestamp that's newer than the data it's supposed to describe. */
    suspend fun setHubCache(reviewsJson: String, blogsJson: String, hydratedAt: Long) {
        editMutex.withLock {
            settings.putString(PrefKeys.HUB_REVIEWS_CACHE_JSON, reviewsJson)
            settings.putString(PrefKeys.HUB_BLOGS_CACHE_JSON, blogsJson)
            settings.putLong(PrefKeys.HUB_CACHE_HYDRATED_AT, hydratedAt)
        }
    }

    /** Mutuals-row equivalent of [setHubCache]. Kept as its own write since
     *  dmConversations loads on a different, earlier trigger than Reviews/
     *  Blogs (see loadDmConversationsBlocking) and shouldn't need to wait
     *  on — or block — that unrelated fetch. */
    suspend fun setHubMutualsCache(mutualsJson: String) {
        settings.putString(PrefKeys.HUB_MUTUALS_CACHE_JSON, mutualsJson)
    }

    suspend fun setLiquidGlass(enabled: Boolean) {
        settings.putBoolean(PrefKeys.LIQUID_GLASS, enabled)
    }

    suspend fun setLiquidGlassIntensity(intensity: Float) {
        settings.putFloat(PrefKeys.LIQUID_GLASS_INTENSITY, intensity.coerceIn(0f, 1f))
    }

    suspend fun setGlassRimIntensity(intensity: Float) {
        settings.putFloat(PrefKeys.GLASS_RIM_INTENSITY, intensity.coerceIn(0f, 1f))
    }

    suspend fun setCombineListsAndPacks(enabled: Boolean) {
        settings.putBoolean(PrefKeys.COMBINE_LISTS_PACKS, enabled)
    }

    suspend fun setAutoAddToOnFollow(enabled: Boolean) {
        settings.putBoolean(PrefKeys.AUTO_ADD_TO_ON_FOLLOW, enabled)
    }

    suspend fun setLastPickerTab(tab: String) {
        settings.putString(PrefKeys.LAST_PICKER_TAB, tab)
    }

    suspend fun followE621Artist(artist: String) {
        editMutex.withLock {
            settings.putStringSet(
                PrefKeys.E621_FOLLOWED_ARTISTS,
                (settings.getStringSetOrNull(PrefKeys.E621_FOLLOWED_ARTISTS) ?: emptySet()) + artist
            )
        }
    }

    suspend fun unfollowE621Artist(artist: String) {
        editMutex.withLock {
            settings.putStringSet(
                PrefKeys.E621_FOLLOWED_ARTISTS,
                (settings.getStringSetOrNull(PrefKeys.E621_FOLLOWED_ARTISTS) ?: emptySet()) - artist
            )
        }
    }

    suspend fun setLastFeedUri(uri: String?) {
        if (uri == null) settings.remove(PrefKeys.LAST_FEED_URI) else settings.putString(PrefKeys.LAST_FEED_URI, uri)
    }

    suspend fun setLastE621Tags(tags: String) {
        settings.putString(PrefKeys.LAST_E621_TAGS, tags)
    }

    suspend fun saveBskySession(accessJwt: String, refreshJwt: String, did: String, handle: String) {
        editMutex.withLock {
            settings.putString(PrefKeys.BSKY_ACCESS_JWT, accessJwt)
            settings.putString(PrefKeys.BSKY_REFRESH_JWT, refreshJwt)
            settings.putString(PrefKeys.BSKY_DID, did)
            settings.putString(PrefKeys.BSKY_HANDLE, handle)
        }
    }

    suspend fun clearBskySession() {
        editMutex.withLock {
            settings.remove(PrefKeys.BSKY_ACCESS_JWT)
            settings.remove(PrefKeys.BSKY_REFRESH_JWT)
            settings.remove(PrefKeys.BSKY_DID)
            settings.remove(PrefKeys.BSKY_HANDLE)
        }
    }

    suspend fun saveE621Credentials(username: String, apiKey: String) {
        editMutex.withLock {
            settings.putString(PrefKeys.E621_USERNAME, username)
            settings.putString(PrefKeys.E621_API_KEY, apiKey)
        }
    }

    suspend fun clearE621Credentials() {
        editMutex.withLock {
            settings.remove(PrefKeys.E621_USERNAME)
            settings.remove(PrefKeys.E621_API_KEY)
        }
    }

    suspend fun setDownloadOnLike(enabled: Boolean) {
        settings.putBoolean(PrefKeys.DOWNLOAD_ON_LIKE, enabled)
    }

    suspend fun setLastMode(mode: String) {
        settings.putString(PrefKeys.LAST_MODE, mode)
    }

    suspend fun setReducedAnimations(enabled: Boolean) {
        settings.putBoolean(PrefKeys.REDUCED_ANIMATIONS, enabled)
    }
}
