package com.mediaviewer.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

enum class AppMode { BLUESKY, E621 }
enum class ScreenState { FEED, COMMENTS, SETTINGS, GRID }

data class DownloadProgress(val count: Int, val isRunning: Boolean)

data class MediaGroupItem(
    val mediaUrl: String,
    val thumbUrl: String,
    val altText: String = ""
)

data class MediaItem(
    val id: String,
    val mediaUrl: String,
    val thumbUrl: String,
    val isVideo: Boolean,
    val videoPlaylistUrl: String? = null,
    // Bug fix: the blob CID of the ORIGINAL video file (as uploaded), used with
    // author.did to fetch the real playable video via com.atproto.sync.getBlob.
    // videoPlaylistUrl above is only an HLS streaming manifest — not itself a
    // downloadable file — which is why "download video" used to save a
    // corrupted/0-second file.
    val videoBlobCid: String? = null,
    val postUri: String = "",
    val postCid: String = "",
    val author: AuthorInfo,
    val likeUri: String? = null,
    val repostUri: String? = null,
    val bookmarkUri: String? = null,
    val isLiked: Boolean = false,
    val isReposted: Boolean = false,
    val isQuoteReposted: Boolean = false,
    val isBlocked: Boolean = false,
    val blockUri: String? = null,
    val isDownloaded: Boolean = false,
    val isGifDownloaded: Boolean = false,
    val isBookmarked: Boolean = false,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val repostCount: Int = 0,
    val altText: String = "",
    val e621PostId: Int? = null,
    val e621Score: Int = 0,
    val e621UserVote: Int = 0,
    val tags: String = "",
    // ── "From Friends" — set only for posts sourced from a DM someone sent us ──
    val sentByAuthor: AuthorInfo? = null,
    val sentByMessage: String = "",
    val sentByConvoId: String? = null,
    // Distinguishes what the "sentBy" header above actually means: a real DM
    // share (false, the original meaning — header reads "Sent by ...") vs a
    // quote repost, where sentByAuthor/sentByMessage repurpose the same
    // header mechanic to attribute the quoting commentary while the card
    // itself shows the original (quoted) post's content — see parseFeedItem's
    // "record" embed branch. Header reads "<name> reposted: ..." instead, and
    // has no Reply action (there's no DM to reply to).
    val sentByIsRepost: Boolean = false,
    // Item 3: all images in this post (populated only for multi-image posts —
    // mediaUrl/thumbUrl above still point at the first image for any code that
    // doesn't know about the group, e.g. previews and single-download fallback).
    val mediaGroup: List<MediaGroupItem> = emptyList(),
    // Big Update #2/#3: the post's own text body. Shown inside the expandable
    // author pill for media posts, or as the sole content of a text-only post.
    val text: String = ""
) {
    /** True when this post has no image/video to show — feed renders it as a
     *  standalone liquid-glass text card instead of a media tile. */
    val isTextOnly: Boolean get() = mediaUrl.isBlank() && thumbUrl.isBlank() && !isVideo
}

data class AuthorInfo(
    val did: String,
    val handle: String,
    val displayName: String,
    val avatarUrl: String?,
    val followingUri: String? = null,
    val isFollowing: Boolean = false
)

data class CommentItem(
    val id: String,
    val uri: String = "",
    val cid: String = "",
    val authorHandle: String,
    val authorDisplayName: String,
    val authorAvatarUrl: String?,
    val body: String,
    val createdAt: String,
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val likeUri: String? = null,
    val e621UserVote: Int = 0,
    val replyCount: Int = 0,
    // Full reply-thread support: Bluesky's getPostThread already returns nested
    // replies up to a fixed depth in one call, so the whole chain the user can
    // navigate into is available locally — no extra network round-trip per level.
    val replies: List<CommentItem> = emptyList()
)

// ── Bluesky ──────────────────────────────────────────────────────────────────

data class BskyCreateSessionRequest(val identifier: String, val password: String)

data class BskySession(
    val accessJwt: String,
    val refreshJwt: String,
    val handle: String,
    val did: String,
    val email: String? = null
)

data class BskyRefreshResponse(
    val accessJwt: String,
    val refreshJwt: String,
    val did: String,
    val handle: String
)

data class BskyTimelineResponse(val feed: List<BskyFeedItem>, val cursor: String? = null)

data class BskyFeedItem(
    val post: BskyPost,
    val reply: BskyReply? = null,
    val reason: BskyReason? = null
)

data class BskyPost(
    val uri: String,
    val cid: String,
    val author: BskyProfile,
    val record: BskyRecord,
    val embed: BskyEmbed? = null,
    val likeCount: Int? = 0,
    val repostCount: Int? = 0,
    val replyCount: Int? = 0,
    val viewer: BskyPostViewer? = null
)

data class BskyPostViewer(
    val like: String? = null,
    val repost: String? = null,
    val threadMuted: Boolean? = null
)

data class BskyProfile(
    val did: String,
    val handle: String,
    val displayName: String? = null,
    val avatar: String? = null,
    val viewer: BskyActorViewer? = null
)

data class BskyActorViewer(
    val following: String? = null,
    val followedBy: String? = null,
    val muted: Boolean? = null
)

data class BskyRecord(
    @SerializedName("\$type") val type: String = "",
    val text: String? = null,
    val createdAt: String? = null,
    val reply: BskyReplyRef? = null
)

data class BskyReplyRef(val root: BskyRef, val parent: BskyRef)
data class BskyRef(val uri: String, val cid: String)
data class BskyReply(val root: BskyPost? = null, val parent: BskyPost? = null)

data class BskyReason(
    @SerializedName("\$type") val type: String = "",
    val by: BskyProfile? = null
)

data class BskyEmbed(
    @SerializedName("\$type") val type: String = "",
    val images: List<BskyImageView>? = null,
    val playlist: String? = null,
    val thumbnail: String? = null,
    val aspectRatio: BskyAspectRatio? = null,
    val cid: String? = null,
    val external: BskyExternalView? = null,
    val media: BskyEmbed? = null,
    val record: BskyEmbedRecord? = null
)

data class BskyImageView(
    val thumb: String,
    val fullsize: String,
    val alt: String? = null,
    val aspectRatio: BskyAspectRatio? = null
)

data class BskyAspectRatio(val width: Int, val height: Int)
data class BskyExternalView(val uri: String, val title: String? = null, val thumb: String? = null)
data class BskyEmbedRecord(
    @SerializedName("\$type") val type: String = "",
    val uri: String? = null,
    val cid: String? = null,
    val author: BskyProfile? = null,
    val value: BskyRecord? = null,
    val embeds: List<BskyEmbed>? = null,
    // For app.bsky.embed.recordWithMedia#view, the actual quoted-post view is
    // nested one level deeper (embed.record.record) instead of directly on
    // embed.record the way a plain app.bsky.embed.record#view is — callers
    // can just do `embed.record?.record ?: embed.record` to reach the real
    // viewRecord either way.
    val record: BskyEmbedRecord? = null
)

data class BskyActorLikesResponse(val feed: List<BskyFeedItem>, val cursor: String? = null)

data class BskyCreateRecordRequest(
    val repo: String,
    val collection: String,
    val record: Map<String, Any>
)

data class BskyCreateRecordResponse(val uri: String, val cid: String)

data class BskyDeleteRecordRequest(
    val repo: String,
    val collection: String,
    val rkey: String
)

data class BskyThreadResponse(val thread: BskyThreadView)

data class BskyThreadView(
    @SerializedName("\$type") val type: String = "",
    val post: BskyPost? = null,
    val parent: BskyThreadView? = null,
    val replies: List<BskyThreadView>? = null,
    val notFound: Boolean? = null,
    val blocked: Boolean? = null
)

data class BskyPreferencesResponse(val preferences: List<JsonElement>)

data class BskyPreference(
    @SerializedName("\$type") val type: String = "",
    val pinned: List<String>? = null,
    val saved: List<String>? = null,
    val items: List<BskySavedFeedItem>? = null
)

data class BskySavedFeedItem(
    val type: String = "",
    val value: String = "",
    val pinned: Boolean = false,
    val id: String = ""
)

data class BskyFeedGeneratorView(
    val uri: String,
    val cid: String,
    val did: String,
    val displayName: String,
    val description: String? = null,
    val avatar: String? = null
)

data class BskyGetFeedGeneratorsResponse(val feeds: List<BskyFeedGeneratorView>)

data class BskyFeedInfo(
    val uri: String,
    val displayName: String,
    val avatarUrl: String? = null
)

// ── e621 ─────────────────────────────────────────────────────────────────────

data class E621PostsResponse(val posts: List<E621Post>)

data class E621Post(
    val id: Int,
    val file: E621File,
    val preview: E621Preview,
    val sample: E621Sample? = null,
    val score: E621Score,
    val tags: E621Tags,
    val fav_count: Int,
    val is_favorited: Boolean,
    val description: String,
    val created_at: String,
    val updated_at: String,
    val rating: String,
    val comment_count: Int
)

data class E621File(val width: Int, val height: Int, val ext: String, val url: String? = null, val md5: String)
data class E621Preview(val width: Int, val height: Int, val url: String? = null)
data class E621Sample(val has: Boolean, val width: Int, val height: Int, val url: String? = null)
data class E621Score(val up: Int, val down: Int, val total: Int)
data class E621Tags(
    val general: List<String>,
    val species: List<String>,
    val character: List<String>,
    val artist: List<String>,
    val meta: List<String>
)

data class E621Comment(
    val id: Int,
    val post_id: Int,
    val creator_id: Int?,
    val creator_name: String,
    val body: String,
    val created_at: String,
    val score: Int,
    val is_hidden: Boolean
)

// ── Author feed / discovery ───────────────────────────────────────────────────

data class BskyActorFeedsResponse(val feeds: List<BskyFeedGeneratorView>, val cursor: String? = null)

// ── Bluesky Lists ─────────────────────────────────────────────────────────────

data class BskyGetListsResponse(
    val lists: List<BskyList>,
    val cursor: String? = null
)

data class BskyList(
    val uri: String,
    val cid: String,
    val name: String,
    val purpose: String = "",
    val description: String? = null,
    val avatar: String? = null,
    val itemCount: Int? = null
)

// ── Bluesky Starter Packs ─────────────────────────────────────────────────────

data class BskyGetStarterPacksResponse(
    val starterPacks: List<BskyStarterPackView>,
    val cursor: String? = null
)

data class BskyStarterPackView(
    val uri: String,
    val cid: String,
    val record: BskyStarterPackRecord? = null,
    val creator: BskyProfile? = null,
    val listItemCount: Int? = null,
    val joinedAllTimeCount: Int? = null
)

data class BskyStarterPackRecord(
    @com.google.gson.annotations.SerializedName("\$type") val type: String = "",
    val name: String = "",
    val description: String? = null,
    val list: String = "",   // AT-URI of the underlying list — use this to add members
    val createdAt: String = ""
)

// ── Batch post hydration (for "From Friends") ────────────────────────────────

data class BskyGetPostsResponse(val posts: List<BskyPost>)

data class BskyProfileBasic(
    val did: String,
    val handle: String,
    val displayName: String? = null,
    val avatar: String? = null
)

data class BskyGetFollowsResponse(val follows: List<BskyProfileBasic>, val cursor: String? = null)
data class BskyGetFollowersResponse(val followers: List<BskyProfileBasic>, val cursor: String? = null)
data class BskyGetBlocksResponse(val blocks: List<BskyProfileBasic>, val cursor: String? = null)

// ── Profile Overhaul ──────────────────────────────────────────────────────────

/** Full profileViewDetailed shape from app.bsky.actor.getProfile — adds the
 *  banner image, bio, and the three headline counts that the basic
 *  [BskyProfile] embedded-in-post view doesn't carry. */
data class BskyProfileDetailed(
    val did: String,
    val handle: String,
    val displayName: String? = null,
    val description: String? = null,
    val avatar: String? = null,
    val banner: String? = null,
    val followersCount: Int? = 0,
    val followsCount: Int? = 0,
    val postsCount: Int? = 0,
    val viewer: BskyActorViewer? = null
)

/** UI-ready profile detail used by the Profile Overlay. */
data class ProfileData(
    val author: AuthorInfo,
    val bannerUrl: String? = null,
    val description: String = "",
    val followersCount: Int = 0,
    val followsCount: Int = 0,
    val postsCount: Int = 0
)

// Generic com.atproto.repo.listRecords envelope — used for any collection
// (likes, reposts-by-record, Leaflet documents, Popfeed reviews) where we
// only need the raw record value rather than a typed AppView response.
data class BskyRecordEnvelope(
    val uri: String,
    val cid: String? = null,
    val value: com.google.gson.JsonElement? = null
)
data class BskyListRecordsResponse(
    val records: List<BskyRecordEnvelope> = emptyList(),
    val cursor: String? = null
)

/** A single Leaflet (pub.leaflet.* / site.standard.*) long-form document,
 *  reduced down to what the Blogs tab needs. [bodyText] is a best-effort
 *  flattened plain-text rendering of the block content — Leaflet's block
 *  schema (images, embeds, tables, canvases) isn't fully modeled here, so
 *  rich blocks are skipped and only text-bearing blocks are concatenated. */
data class LeafletBlog(
    val uri: String,
    val title: String,
    val bodyText: String,
    val createdAt: String
)

/** A single Popfeed (social.popfeed.review, formerly app.popsky.review)
 *  review, reduced down to what the Reviews tab needs. Popfeed's exact field
 *  names aren't publicly documented, so parsing is defensive across a few
 *  known aliases (see BlueskyRepository.getPopfeedReviews). */
data class PopfeedReview(
    val uri: String,
    val mediaTitle: String,
    val mediaImageUrl: String? = null,
    // Popfeed stores a separate landscape/backdrop image distinct from the
    // portrait poster (mediaImageUrl) — used for the wide banner in the
    // review detail popup instead of cropping the portrait poster into a
    // landscape shape. Null if the record doesn't carry one, in which case
    // callers fall back to mediaImageUrl.
    val mediaBackdropUrl: String? = null,
    val ratingOutOf5: Float = 0f,
    val reviewText: String = "",
    val createdAt: String = ""
)

/** A single Popfeed backlog/watchlist entry (movie, TV show, or game the
 *  account has logged to watch/play eventually) — reduced down to what the
 *  profile's Backlog tab needs. The collection these come from
 *  (social.popfeed.feed.listItem) and the title field are confirmed against
 *  a real third-party Popfeed integration; the image field and the exact
 *  backlog/watchlist category values for movies/TV/games are not, and are
 *  matched defensively — see BlueskyRepository.getPopfeedBacklog. */
data class PopfeedBacklogItem(
    val uri: String,
    val title: String,
    val imageUrl: String? = null,
    val createdAt: String = ""
)

// ── Settings Update ───────────────────────────────────────────────────────────

/** app.bsky.bookmark.getBookmarks response shape. */
data class BskyBookmarkEntry(val item: BskyPost? = null)
data class BskyGetBookmarksResponse(val bookmarks: List<BskyBookmarkEntry> = emptyList(), val cursor: String? = null)

/** A lightweight, locally-persisted record of a post the user has scrolled
 *  onto, powering the local-only "History" feed. Deliberately smaller than a
 *  full [MediaItem] since it's stored as JSON in DataStore rather than a DB —
 *  [showHistory]-style reconstruction rebuilds a full MediaItem from this at
 *  read time (with fresh like/repost state refetched lazily by the normal
 *  post-interaction calls, keyed by the preserved uri/cid). */
data class HistoryEntry(
    val uri: String,
    val cid: String,
    val mediaUrl: String,
    val thumbUrl: String,
    val isVideo: Boolean,
    val text: String,
    val authorDid: String,
    val authorHandle: String,
    val authorDisplayName: String,
    val authorAvatarUrl: String?,
    val viewedAt: Long
)

// ── Chat / DMs (chat.bsky.convo.*, proxied via did:web:api.bsky.chat) ───────

data class BskyConvoMember(
    val did: String,
    val handle: String,
    val displayName: String? = null,
    val avatar: String? = null
)

data class BskyMessageSender(val did: String)

data class BskyMessageView(
    val id: String = "",
    val text: String = "",
    val facets: List<JsonElement>? = null,
    val embed: JsonElement? = null,
    val sender: BskyMessageSender? = null,
    val sentAt: String = ""
)

data class BskyConvoView(
    val id: String,
    val rev: String? = null,
    val members: List<BskyConvoMember> = emptyList(),
    val lastMessage: BskyMessageView? = null,
    val unreadCount: Int = 0
)

data class BskyListConvosResponse(val convos: List<BskyConvoView>, val cursor: String? = null)

data class BskyGetConvoForMembersResponse(val convo: BskyConvoView)

data class BskyGetMessagesResponse(val messages: List<BskyMessageView>, val cursor: String? = null)

data class BskySendMessageInput(
    val text: String,
    val facets: List<Map<String, Any>>? = null,
    val embed: Map<String, Any>? = null
)

data class BskySendMessageRequest(val convoId: String, val message: BskySendMessageInput)

/** A friendly, UI-ready DM conversation — one per person we can message. */
data class DmConversation(
    val convoId: String,
    val member: AuthorInfo,
    val lastSentByUsAt: String,   // ISO timestamp of the most recent message WE sent (empty if none yet)
    val lastActivityAt: String    // fallback sort key — most recent activity of any kind
)

/** A DM message that carries a shared post — powers the "From Friends" feed. */
data class SharedPostMessage(
    val postUri: String,
    val postCid: String,
    val senderDid: String,
    val senderHandle: String,
    val senderDisplayName: String,
    val senderAvatarUrl: String?,
    val messageText: String,
    val sentAt: String
)
