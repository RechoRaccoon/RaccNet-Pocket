package com.mediaviewer.model

/** Why a post/account is being reported — Bluesky's own report categories
 *  (com.atproto.moderation.defs), worded like the official app. */
enum class ReportReason(val title: String, val description: String, val lexicon: String) {
    SPAM("Spam", "Excessive mentions or replies", "com.atproto.moderation.defs#reasonSpam"),
    SEXUAL("Unwanted Sexual Content", "Nudity or adult content not labeled as such", "com.atproto.moderation.defs#reasonSexual"),
    RUDE("Anti-Social Behavior", "Harassment, trolling, or intolerance", "com.atproto.moderation.defs#reasonRude"),
    MISLEADING("Misleading", "Impersonation, misinformation, or false claims", "com.atproto.moderation.defs#reasonMisleading"),
    VIOLATION("Illegal and Urgent", "Glaring violations of law or terms of service", "com.atproto.moderation.defs#reasonViolation"),
    OTHER("Other", "An issue not included in these options", "com.atproto.moderation.defs#reasonOther");
}

/** What the Report popup is reporting. */
data class ReportTarget(
    val author: AuthorInfo,
    /** Null = the account itself; set = that one post. */
    val postUri: String? = null,
    val postCid: String? = null,
    val postThumbUrl: String = "",
    val postText: String = "",
    /** Opened from a profile page (its colors), not the feed. */
    val fromProfile: Boolean = false,
) {
    val isPost: Boolean get() = postUri != null
}
