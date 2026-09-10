package com.mediaviewer.repository

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fully open, key-free movie/TV/book/etc. description lookup.
 *
 * Popfeed's own review/listItem records carry title, genres, release date,
 * and a main credit (confirmed directly against Popfeed's public lexicon —
 * see BlueskyRepository.getPopfeedBacklog's doc comment), but genuinely no
 * synopsis field at all. TMDB was ruled out entirely (no API key, no
 * commercial license), so this is the one piece of "movie info" that has to
 * come from somewhere else — two free, no-key Wikimedia endpoints, chained:
 *
 *  1. Wikidata's public SPARQL endpoint. If an IMDb ID is available
 *     (Popfeed's `identifiers.imdbId`), this finds the exact matching
 *     Wikidata item (property P345 = IMDb ID) and follows its English
 *     Wikipedia sitelink — sidesteps ambiguous-title lookups entirely (e.g.
 *     a movie sharing its title with an unrelated page).
 *  2. Wikipedia's REST summary endpoint, called with that exact sitelink
 *     title when step 1 succeeds, or the raw Popfeed title as a best-effort
 *     fallback when it doesn't (no IMDb id on the record, no Wikidata match,
 *     or a network failure). Returns a clean plain-text extract — see the
 *     "Attribution" section below for why displaying it isn't a no-strings
 *     freebie.
 *
 * Both requests are plain unauthenticated GETs — no signup, no key, free at
 * any scale, run by the nonprofit Wikimedia Foundation.
 *
 * Returns null on any failure (no match, disambiguation page, network
 * error) rather than throwing — callers show no description bubble at all
 * in that case, the same graceful-degradation pattern already used for the
 * poster/backdrop image fields elsewhere in this app.
 *
 * ── Attribution ──────────────────────────────────────────────────────────
 * Wikipedia's text is dual-licensed CC BY-SA 4.0 / GFDL — *not* public
 * domain. Reusing an extract of it (which is exactly what [fetchDescription]
 * does — this isn't just "linking to" Wikipedia, it's displaying its actual
 * copyrighted text inside this app) means the license's own attribution
 * clause actually applies here, not just as a courtesy: in practice, per
 * Wikipedia's own reuse guidance (https://en.wikipedia.org/wiki/Wikipedia:Reusing_Wikipedia_content),
 * that means crediting "Wikipedia"/"Wikipedia contributors" with a link
 * back to the source article (satisfies attributing the actual authors,
 * without needing to list them all individually), and indicating the
 * license with a link to its full text. [WikipediaExtract.pageUrl] below
 * carries the former; TitleDetailOverlay's description bubble links the
 * latter to https://creativecommons.org/licenses/by-sa/4.0/ directly.
 *
 * PORT: the blocking OkHttp calls (NetworkClient.downloadClient) are now
 * suspending Ktor GETs; org.json is kotlinx.serialization; java.net.URLEncoder
 * is Ktor's encodeURLPathPart / URLBuilder query parameters.
 */
object WikipediaRepository {

    private const val WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"
    private const val WIKIPEDIA_SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/"

    private val http by lazy { HttpClient() }

    /** [extract]: the plain-text synopsis. [pageTitle]: the article's
     *  canonical title (may differ in casing/disambiguation from the raw
     *  Popfeed title that was searched for). [pageUrl]: the article's own
     *  canonical URL — required for attribution (see this object's class
     *  doc comment) and used as the "open full article" link. */
    data class WikipediaExtract(val extract: String, val pageTitle: String, val pageUrl: String)

    // PORT: Dispatchers.Default doesn't exist in commonMain — Dispatchers.Default
    // (Ktor never blocks a thread here anyway).
    suspend fun fetchDescription(title: String, imdbId: String? = null): WikipediaExtract? = withContext(Dispatchers.Default) {
        val sitelinkTitle = imdbId?.takeIf { it.isNotBlank() }?.let { resolveEnwikiTitleByImdbId(it) }
        sitelinkTitle?.let { fetchSummaryExtract(it) } ?: fetchSummaryExtract(title)
    }

    /** Looks up the Wikidata item whose IMDb-ID property (P345) matches
     *  [imdbId], and returns the title of its English Wikipedia sitelink, if
     *  any. Null on no match or any failure — [fetchDescription] falls back
     *  to a plain title-based Wikipedia lookup in that case. */
    // PORT: was a blocking (non-suspend) fun doing synchronous OkHttp I/O;
    // suspend now.
    private suspend fun resolveEnwikiTitleByImdbId(imdbId: String): String? = runCatching {
        // IMDb ids (e.g. "tt1375666") don't contain quotes in practice, but
        // stripping them defensively keeps a malformed value from breaking
        // out of the SPARQL string literal below.
        val sanitized = imdbId.replace("\"", "")
        val query = """
            SELECT ?article WHERE {
              ?item wdt:P345 "$sanitized" .
              ?article schema:about ?item ;
                       schema:isPartOf <https://en.wikipedia.org/> .
            } LIMIT 1
        """.trimIndent()
        // PORT: okhttp3.HttpUrl.Builder -> Ktor URLBuilder (same query-param
        // encoding).
        val url = URLBuilder(WIKIDATA_SPARQL).apply {
            parameters.append("query", query)
            parameters.append("format", "json")
        }.build()
        val resp = http.get(url) {
            header("Accept", "application/sparql-results+json")
        }
        if (!resp.status.isSuccess()) return@runCatching null
        val bodyStr = resp.bodyAsText()
        val bindings = repoJson.parseToJsonElement(bodyStr).jsonObject["results"]
            ?.jsonObject?.get("bindings") as? JsonArray
            ?: return@runCatching null
        if (bindings.isEmpty()) return@runCatching null
        val articleUrl = (bindings[0] as? JsonObject)?.get("article")
            ?.jsonObject?.get("value")?.jsonPrimitive?.content
            ?: return@runCatching null
        // articleUrl looks like https://en.wikipedia.org/wiki/Some_Title —
        // already percent-encoded the same way the REST summary endpoint
        // expects its path segment, so a straight substring is enough.
        articleUrl.substringAfterLast("/wiki/").takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Calls Wikipedia's REST summary endpoint for one exact page title and
     *  returns its plain-text extract plus the article's own canonical
     *  title/URL (needed for attribution — see this object's class doc
     *  comment). Null if the page doesn't exist, is a disambiguation page
     *  with nothing usable, or the request fails. */
    // PORT: was a blocking (non-suspend) fun doing synchronous OkHttp I/O;
    // suspend now.
    private suspend fun fetchSummaryExtract(pageTitle: String): WikipediaExtract? = runCatching {
        // A raw Popfeed title can contain spaces/punctuation and isn't
        // pre-encoded (unlike the sitelink path above), so it needs
        // MediaWiki-style encoding: spaces to underscores, then
        // percent-encoding everything else. Re-encoding an
        // already-underscore/percent-encoded sitelink title here is
        // harmless — '_' passes through unchanged either way.
        // PORT: java.net.URLEncoder -> Ktor encodeURLPathPart.
        val encoded = encodeURLPathPart(pageTitle.replace(' ', '_'))
        val resp = http.get(WIKIPEDIA_SUMMARY + encoded)
        if (!resp.status.isSuccess()) return@runCatching null
        val bodyStr = resp.bodyAsText()
        val json = repoJson.parseToJsonElement(bodyStr).jsonObject
        // A disambiguation page has no real synopsis to show — treat it
        // the same as "no match" rather than surfacing its generic blurb.
        if (json["type"].asStr() == "disambiguation") return@runCatching null
        val extract = json["extract"].asStr()?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val canonicalTitle = json["title"].asStr()?.takeIf { it.isNotBlank() } ?: pageTitle
        // content_urls.desktop.page is the article's real, canonical
        // URL straight from Wikipedia's own response — used as-is
        // rather than re-deriving one from [pageTitle], which can
        // differ from the canonical title in casing/disambiguation.
        val pageUrl = (json["content_urls"] as? JsonObject)
            ?.get("desktop")?.jsonObject
            ?.get("page")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: "https://en.wikipedia.org/wiki/${encodeURLPathPart(canonicalTitle.replace(' ', '_'))}"
        WikipediaExtract(extract = extract, pageTitle = canonicalTitle, pageUrl = pageUrl)
    }.getOrNull()
}
