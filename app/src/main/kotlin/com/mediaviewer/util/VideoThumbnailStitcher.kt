package com.mediaviewer.util

import android.content.Context
import android.net.Uri

/**
 * Splices a custom thumbnail image into a video's first frame before
 * upload. Bluesky has no separate "thumbnail" field on app.bsky.embed.
 * video — the app always shows frame 0 of the video itself as the
 * thumbnail — so the only way a custom thumbnail shows up is to make
 * frame 0 *be* that image. RaccNet Legacy does this server-side with
 * ffmpeg (see `_process_video` in raccnet_server.py).
 *
 * CURRENTLY A NO-OP — this was implemented with Media3 Transformer
 * (EditedMediaItemSequence + experimentalSetForceAudioTrack, matching
 * Legacy's concat-a-still-frame trick), but that API only landed in
 * media3-transformer 1.8.0, and that version's own AAR metadata requires
 * compileSdk 35+ — which in turn needs Android Gradle Plugin 8.5+ and a
 * newer Gradle wrapper than this project currently uses (AGP 8.2.0 /
 * compileSdk 34 / Gradle 8.4). That's a real migration (AGP + Gradle +
 * compileSdk all move together, and it's worth testing deliberately rather
 * than as a side effect of one feature), so rather than force it through
 * silently this just passes the video through untouched for now — video
 * posts work fine, they just get Bluesky's own auto-generated (frame 0)
 * thumbnail instead of a custom one.
 *
 * To finish this: bump compileSdk to 35 (or 36) + AGP to a matching
 * version (check https://developer.android.com/studio/releases/gradle-plugin
 * for the AGP/Gradle-wrapper pairing) + media3-exoplayer/-ui to a matching
 * 1.8.0+ release, add media3-transformer/-effect/-muxer at that same
 * version, then restore the EditedMediaItemSequence-based implementation
 * (previous version of this file, still in this chat's history). Build and
 * test that whole migration on its own before layering this feature back
 * on top of it.
 */
object VideoThumbnailStitcher {
    /** Returns [thumbnailUri] spliced into [videoUri] as its first frame —
     *  currently just returns [videoUri] unchanged; see class doc above. */
    suspend fun stitch(context: Context, videoUri: Uri, thumbnailUri: Uri?): Uri = videoUri
}
