package com.mediaviewer.util

/**
 * Central switchboard for in-progress features that are fully wired up in
 * code but not yet ready to be user-facing. Flip a flag to `true` once the
 * feature is finished — nothing else needs to change.
 *
 * LIVE_LINK_ENABLED gates the "Live Link" (Twitch/YouTube "I'm live" status)
 * feature and its home-screen widget: the Settings input fields + "Create
 * Widget" button, and the mirrored toggle row at the bottom of the AT
 * Protocol Hub page. All of the underlying plumbing (LiveLinkManager,
 * LiveLinkCheckWorker, LiveLinkWidgetProvider, PreferencesManager entries)
 * stays intact and untouched — this only hides the surfaces a user could
 * reach it from while it's unfinished.
 */
object FeatureFlags {
    const val LIVE_LINK_ENABLED: Boolean = false
}
