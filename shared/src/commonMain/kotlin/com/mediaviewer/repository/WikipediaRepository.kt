package com.mediaviewer.repository

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.bodyString
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.network.NetworkClient
import com.mediaviewer.util.BlockedHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.mediaviewer.json.JSONObject
import com.mediaviewer.platform.urlEncode
import com.mediaviewer.platform.ConcurrentHashMap

/**
 * Title synopses and fallback covers from Wikipedia — free, keyless, run by
 * the nonprofit Wikimedia Foundation.
 *
 * Finding the right article:
 *  1. By ID, through Wikidata's public SPARQL endpoint: the record's
 *     `identifiers` (IMDb, TMDB movie/TV, MusicBrainz release) are matched
 *     against Wikidata's matching properties and the item's English
 *     Wikipedia sitelink is followed. These are just ID numbers stored in
 *     the Popfeed record — nothing is ever requested from those services.
 *  2. By title, trying the usual Wikipedia disambiguation forms for the
 *     title's type first ("Title (2010 film)", "Title (video game)",
 *     "Title (album)", "Title (novel)", …) and then the bare title. A
 *     title match only counts when the article's short description agrees
 *     with the type (and the year, for films/games/albums), so a movie never
 *     gets an unrelated page's picture.
 *
 * Text is CC BY-SA 4.0 (attributed on the title page). The lead image
 * (a poster, cover or title card) is used only as a fallback cover when the
 * Popfeed record has no image of its own. Results are cached in memory, and
 * cover lookups are kept on-device so each title is only looked up once.
 */
object WikipediaRepository {

    private const val WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"
    private const val WIKIPEDIA_SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/"
    /** Wikimedia's API policy asks every client to identify itself. */
    private const val USER_AGENT = BlockedHosts.WIKIMEDIA_USER_AGENT

    /** Kept for existing callers: the synopsis half of [WikiTitleInfo]. */
    data class WikipediaExtract(val extract: String, val pageTitle: String, val pageUrl: String)

    /** One resolved article: its synopsis, canonical title/URL (for the
     *  CC BY-SA attribution) and, when trustworthy, its lead image. */
    data class WikiTitleInfo(
        val extract: String?,
        val pageTitle: String,
        val pageUrl: String,
        val imageUrl: String? = null,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0
    ) {
        /** A landscape image (e.g. a TV title card) — better as a banner
         *  than cropped into a portrait poster. */
        val imageIsWide: Boolean get() = imageWidth > 0 && imageHeight > 0 && imageWidth > imageHeight * 1.15f
    }

    /** A cached fallback cover (see [cachedCover]). */
    data class WikiCover(val url: String, val width: Int, val height: Int, val pageUrl: String) {
        val isWide: Boolean get() = width > 0 && height > 0 && width > height * 1.15f
    }

    private object None
    private val memo = ConcurrentHashMap<String, Any>()
    // Gentle on Wikimedia: at most two lookups at a time, app-wide.
    private val gate = Semaphore(2)

    private const val PREFS = "wiki_title_covers"
    private const val POSITIVE_TTL_MS = 60L * 24 * 60 * 60 * 1000
    private const val NEGATIVE_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private var prefs: SharedPreferences? = null

    fun init(context: PlatformContext) {
        if (prefs == null) prefs = context.sharedPreferences(PREFS)
    }

    /** Dev Tools → Clear Cached Title Covers. */
    fun clearCoverCache() {
        memo.clear()
        prefs?.edit()?.clear()?.apply()
    }

    fun key(title: String, category: String?, identifiersJson: String?, releaseDate: String?): String {
        val ids = runCatching { identifiersJson?.let { JSONObject(it) } }.getOrNull()
        val idPart = listOf("imdbId", "tmdbId", "tmdbTvSeriesId", "mbReleaseId")
            .mapNotNull { k -> ids?.optString(k)?.takeIf { it.isNotBlank() }?.let { "$k=$it" } }
            .joinToString(",")
        return (category.orEmpty() + "|" + title.trim().lowercase() + "|" + releaseDate.orEmpty().take(4) + "|" + idPart)
    }

    /**
     * The on-device cached fallback cover for a title, if it's been looked
     * up before: [Result.success] with null = looked up, nothing usable;
     * null = not looked up yet (or the entry expired).
     */
    fun cachedCover(key: String): Result<WikiCover?>? {
        (memo[key])?.let { v -> return Result.success((v as? WikiTitleInfo)?.toCover()) }
        val raw = prefs?.getString(key, null) ?: return null
        val parts = raw.split('\t')
        val at = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val url = parts.getOrNull(1).orEmpty()
        val age = com.mediaviewer.platform.currentTimeMillis() - at
        if (url.isBlank()) return if (age < NEGATIVE_TTL_MS) Result.success(null) else null
        if (age > POSITIVE_TTL_MS) return null
        return Result.success(
            WikiCover(url, parts.getOrNull(2)?.toIntOrNull() ?: 0, parts.getOrNull(3)?.toIntOrNull() ?: 0, parts.getOrNull(4).orEmpty())
        )
    }

    private fun WikiTitleInfo.toCover(): WikiCover? = imageUrl?.let { WikiCover(it, imageWidth, imageHeight, pageUrl) }

    private fun saveCover(key: String, info: WikiTitleInfo?) {
        val c = info?.toCover()
        val v = if (c == null) "${com.mediaviewer.platform.currentTimeMillis()}\t" else
            "${com.mediaviewer.platform.currentTimeMillis()}\t${c.url}\t${c.width}\t${c.height}\t${c.pageUrl}"
        prefs?.edit()?.putString(key, v)?.apply()
        c?.url?.let { com.mediaviewer.util.TitleCovers.note(it) }
    }

    /** The cache key [lookup] uses for a title (IMDb id folded into the ids). */
    fun coverKey(title: String, category: String?, identifiersJson: String?, releaseDate: String?, imdbId: String? = null): String =
        key(title, category, mergedIds(identifiersJson, imdbId)?.toString(), releaseDate)

    /** Just the fallback cover for a title (cards use this). */
    suspend fun fetchCover(
        title: String, category: String?, identifiersJson: String?, releaseDate: String?,
        creator: String? = null, imdbId: String? = null
    ): WikiCover? {
        cachedCover(coverKey(title, category, identifiersJson, releaseDate, imdbId))?.let { return it.getOrNull() }
        return lookup(title, category, identifiersJson, releaseDate, creator, imdbId)?.toCover()
    }

    /** Synopsis + cover for a title page. */
    suspend fun lookup(
        title: String,
        category: String?,
        identifiersJson: String?,
        releaseDate: String?,
        creator: String? = null,
        imdbId: String? = null
    ): WikiTitleInfo? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val ids = mergedIds(identifiersJson, imdbId)
        val k = key(title, category, ids?.toString(), releaseDate)
        memo[k]?.let { return@withContext it as? WikiTitleInfo }
        gate.withPermit {
            memo[k]?.let { return@withPermit it as? WikiTitleInfo }
            val ctx = Ctx()
            val info = runCatching { resolve(title, category, ids, releaseDate, creator, ctx) }.getOrNull()
            // A failed request (offline, rate-limited, server error) isn't
            // remembered as "no cover" — it's simply tried again next time.
            if (info == null && ctx.failed) return@withPermit null
            memo[k] = info ?: None
            saveCover(k, info)
            info
        }
    }

    /** Older entry point: synopsis only, by title (+ IMDb id). */
    suspend fun fetchDescription(title: String, imdbId: String? = null): WikipediaExtract? =
        lookup(title, null, null, null, imdbId = imdbId)?.let { info ->
            info.extract?.let { WikipediaExtract(it, info.pageTitle, info.pageUrl) }
        }

    private fun mergedIds(identifiersJson: String?, imdbId: String?): JSONObject? {
        val o = runCatching { identifiersJson?.let { JSONObject(it) } }.getOrNull() ?: JSONObject()
        if (!imdbId.isNullOrBlank() && o.optString("imdbId").isBlank()) o.put("imdbId", imdbId)
        return if (o.length() == 0) null else o
    }

    /** Whether any request in one lookup failed (vs. simply not matching). */
    private class Ctx { var failed = false }

    private suspend fun resolve(title: String, category: String?, ids: JSONObject?, releaseDate: String?, creator: String?, ctx: Ctx): WikiTitleInfo? {
        val bucket = bucketOf(category)
        // 1. Exact match by ID through Wikidata.
        val byId = ids?.let { resolveEnwikiTitleByIds(it, bucket, ctx) }
        if (byId != null) {
            fetchSummary(byId, ctx)?.let { s -> return s.toInfo(withImage = true) }
        }
        // 2. By title, checked against the type (and year).
        val year = releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        for (candidate in candidateTitles(title.trim(), bucket, year, creator)) {
            val s = fetchSummary(candidate, ctx) ?: continue
            if (bucket == null) return s.toInfo(withImage = false)
            if (matches(s.description, bucket, year)) return s.toInfo(withImage = true)
        }
        return null
    }

    private enum class Bucket { MOVIE, TV, GAME, ALBUM, TRACK, BOOK }

    private fun bucketOf(category: String?): Bucket? {
        val c = category?.lowercase()?.trim().orEmpty()
        return when {
            c.isEmpty() -> null
            c == "movie" || c == "film" -> Bucket.MOVIE
            c.contains("tv") || c.contains("show") || c.contains("season") || c.contains("episode") -> Bucket.TV
            c.contains("game") -> Bucket.GAME
            c.contains("track") || c.contains("song") -> Bucket.TRACK
            c.contains("album") || c == "ep" || c.contains("music") -> Bucket.ALBUM
            c.contains("book") -> Bucket.BOOK
            else -> null
        }
    }

    private fun candidateTitles(t: String, bucket: Bucket?, year: String?, creator: String?): List<String> {
        val c = creator?.trim()?.takeIf { it.isNotBlank() && it.length < 60 }
        val list = when (bucket) {
            Bucket.MOVIE -> listOfNotNull(year?.let { "$t ($it film)" }, "$t (film)", t)
            Bucket.TV -> listOf("$t (TV series)", t)
            Bucket.GAME -> listOfNotNull("$t (video game)", year?.let { "$t ($it video game)" }, t)
            Bucket.ALBUM -> listOfNotNull(c?.let { "$t ($it album)" }, "$t (album)", t)
            Bucket.TRACK -> listOfNotNull(c?.let { "$t ($it song)" }, "$t (song)", t)
            Bucket.BOOK -> listOf("$t (novel)", "$t (book)", t)
            null -> listOf(t)
        }
        return list.distinct().take(3)
    }

    private val keywords = mapOf(
        Bucket.MOVIE to Regex("\\b(film|movie|documentary)\\b"),
        Bucket.TV to Regex("\\b(television|tv|series|sitcom|anime|miniseries|show|drama|soap opera)\\b"),
        Bucket.GAME to Regex("\\b(video game|game)\\b"),
        Bucket.ALBUM to Regex("\\b(album|ep|soundtrack|mixtape|compilation)\\b"),
        Bucket.TRACK to Regex("\\b(song|single|track)\\b"),
        Bucket.BOOK to Regex("\\b(novel|novella|book|memoir|manga|comic|comics|graphic novel|collection|poetry|autobiography|biography|anthology|series)\\b")
    )
    private val yearRegex = Regex("\\b(1[89]\\d\\d|20\\d\\d)\\b")

    private fun matches(description: String?, bucket: Bucket, year: String?): Boolean {
        val d = description?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        if (keywords[bucket]?.containsMatchIn(d) != true) return false
        if (year != null && bucket in setOf(Bucket.MOVIE, Bucket.GAME, Bucket.ALBUM)) {
            val y = yearRegex.find(d)?.value?.toIntOrNull()
            if (y != null && kotlin.math.abs(y - year.toInt()) > 1) return false
        }
        return true
    }

    /** Wikidata properties for the IDs Popfeed records carry. */
    private fun idProperties(ids: JSONObject, bucket: Bucket?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun v(k: String) = ids.optString(k).trim().takeIf { it.isNotBlank() && it != "null" }?.replace("\"", "")
        v("imdbId")?.let { out += "P345" to it }
        v("tmdbTvSeriesId")?.let { out += "P4983" to it }
        v("tmdbId")?.let { out += (if (bucket == Bucket.TV) "P4983" else "P4947") to it }
        v("mbReleaseId")?.let { out += "P5813" to it; out += "P436" to it }
        return out
    }

    private suspend fun resolveEnwikiTitleByIds(ids: JSONObject, bucket: Bucket?, ctx: Ctx): String? = runCatching {
        val props = idProperties(ids, bucket)
        if (props.isEmpty()) return@runCatching null
        val unions = props.joinToString(" UNION ") { (p, value) -> "{ ?item wdt:$p \"$value\" . }" }
        val query = """
            SELECT ?article WHERE {
              $unions
              ?article schema:about ?item ;
                       schema:isPartOf <https://en.wikipedia.org/> .
            } LIMIT 1
        """.trimIndent()
        val resp = PlainHttp.get(
            WIKIDATA_SPARQL,
            query = listOf("query" to query, "format" to "json"),
            headers = listOf("Accept" to "application/sparql-results+json", "User-Agent" to USER_AGENT)
        )
        run {
            if (!resp.isSuccessful) { ctx.failed = true; return@runCatching null }
            val bodyStr = resp.bodyString()
            val bindings = JSONObject(bodyStr).optJSONObject("results")?.optJSONArray("bindings")
                ?: return@runCatching null
            if (bindings.length() == 0) return@runCatching null
            val articleUrl = bindings.getJSONObject(0).optJSONObject("article")?.optString("value")
                ?: return@runCatching null
            // Already percent-encoded; decoded here so fetchSummary's own
            // encoding doesn't double-encode it.
            com.mediaviewer.platform.urlDecode(articleUrl.substringAfterLast("/wiki/")).takeIf { it.isNotBlank() }
        }
    }.onFailure { if (it is com.mediaviewer.platform.IOException) ctx.failed = true }.getOrNull()

    private class Summary(
        val extract: String?, val title: String, val pageUrl: String, val description: String?,
        val imageUrl: String?, val imageWidth: Int, val imageHeight: Int
    ) {
        fun toInfo(withImage: Boolean) = WikiTitleInfo(
            extract = extract, pageTitle = title, pageUrl = pageUrl,
            imageUrl = if (withImage) imageUrl else null,
            imageWidth = if (withImage) imageWidth else 0,
            imageHeight = if (withImage) imageHeight else 0
        )
    }

    /** Wikipedia's REST summary for one exact page title. Null when the
     *  page doesn't exist, is a disambiguation page, or the call fails. */
    private suspend fun fetchSummary(pageTitle: String, ctx: Ctx): Summary? = runCatching {
        val encoded = urlEncode(pageTitle.replace(' ', '_')).replace("+", "%20")
        val resp = PlainHttp.get(WIKIPEDIA_SUMMARY + encoded, headers = listOf("User-Agent" to USER_AGENT))
        run {
            if (!resp.isSuccessful) {
                if (resp.code != 404) ctx.failed = true
                return@runCatching null
            }
            val bodyStr = resp.bodyString()
            val json = JSONObject(bodyStr)
            if (json.optString("type") == "disambiguation") return@runCatching null
            val extract = json.optString("extract").takeIf { it.isNotBlank() }
            val canonicalTitle = json.optString("title").takeIf { it.isNotBlank() } ?: pageTitle
            val pageUrl = json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")
                ?.takeIf { it.isNotBlank() }
                ?: "https://en.wikipedia.org/wiki/${urlEncode(canonicalTitle.replace(' ', '_'))}"
            // The lead image: the original when it's modest in size (non-free
            // posters/covers always are) and not an SVG, else the thumbnail.
            val original = json.optJSONObject("originalimage")
            val thumb = json.optJSONObject("thumbnail")
            val origUrl = original?.optString("source")?.takeIf { it.isNotBlank() }
            val useOriginal = origUrl != null && !origUrl.lowercase().endsWith(".svg") && (original?.optInt("width") ?: 0) in 1..1000
            val img = if (useOriginal) original else thumb
            val imgUrl = img?.optString("source")?.takeIf { it.isNotBlank() && BlockedHosts.isAllowedCoverUrl(it) }
            Summary(
                extract = extract, title = canonicalTitle, pageUrl = pageUrl,
                description = json.optString("description").takeIf { it.isNotBlank() },
                imageUrl = imgUrl,
                imageWidth = if (imgUrl != null) (img?.optInt("width") ?: 0) else 0,
                imageHeight = if (imgUrl != null) (img?.optInt("height") ?: 0) else 0
            )
        }
    }.onFailure { if (it is com.mediaviewer.platform.IOException) ctx.failed = true }.getOrNull()
}
