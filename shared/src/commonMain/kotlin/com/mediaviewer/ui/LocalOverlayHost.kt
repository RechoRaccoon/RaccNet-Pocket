package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel

/**
 * Draws the supporter features' popups, the Launchpad apps and the floating
 * web pages over the rest of the app, and connects [LocalOverlays]' actions
 * to the ViewModel. Called once from AppRoot, inside its root Box (each
 * piece sets its own zIndex among AppRoot's other layers).
 */
@Composable
fun LocalOverlayHost(
    viewModel: MainViewModel,
    liquidGlass: Boolean,
    /** The signed-in account's own profile color. */
    tint: Color,
    /** The color of the post on screen (popups about that post wear it). */
    postTint: Color,
    savedFeedUris: Set<String>,
    /** The app page behind the popups, recorded live: their glass blurs it. */
    backdrop: GlassBackdrop?,
    hubShowing: Boolean
) {
    val folderId by viewModel.bookmarkFolderId.collectAsState()
    val dmHidden by viewModel.dmHiddenBehindFeed.collectAsState()
    val myLists by viewModel.userLists.collectAsState()
    val selectedFeed by viewModel.selectedFeedUri.collectAsState()

    SideEffect {
        LocalOverlays.onEditCurrentPost = viewModel::editCurrentPost
        LocalOverlays.pollTally = { uri -> viewModel.pollTally(uri) }
        LocalOverlays.pollVote = viewModel::votePoll
        LocalOverlays.scanDmStreak = viewModel::scanDmStreak
        LocalOverlays.resolveFeedCard = { actor, kind, rkey -> viewModel.sharedFeedCard(actor, kind, rkey) }
        LocalOverlays.openSharedFeed = viewModel::openFeedFromDm
        LocalOverlays.addSharedFeed = viewModel::addSharedFeed
        LocalOverlays.onShowBookmarkFolder = viewModel::showBookmarkFolder
        LocalOverlays.savedFeedUris = savedFeedUris
        LocalOverlays.bookmarkFolderId = folderId
    }

    // The timer keeps running (and rings) wherever you are in the app.
    TimerWatcher()

    // A feed dropped on the Hub's DMs button → the "Share with" popup.
    val shareFeed = LocalOverlays.shareFeed
    LaunchedEffect(shareFeed) {
        if (shareFeed != null) {
            viewModel.openShareFeed(shareFeed)
            LocalOverlays.shareFeed = null
        }
    }

    // A feed opened from a DM sits in front of the DMs: Back returns to them.
    // (Going to the Hub instead leaves the DMs for good.)
    if (dmHidden) BackHandler { viewModel.closeProfileFeed() }
    LaunchedEffect(hubShowing, dmHidden) { if (hubShowing && dmHidden) viewModel.dropHiddenDm() }

    // ── Launchpad apps (full pages over the Hub) ──
    LocalOverlays.launchApp?.let { app ->
        Box(Modifier.fillMaxSize().zIndex(10.55f)) {
            val close = { LocalOverlays.launchApp = null }
            when (app) {
                LaunchApp.CALENDAR -> CalendarPage(tint, liquidGlass, close)
                LaunchApp.NOTES -> NotesPage(tint, liquidGlass, close)
                LaunchApp.CALCULATOR -> CalculatorPage(tint, liquidGlass, close)
                LaunchApp.TIMER -> TimerPage(tint, liquidGlass, close)
            }
        }
    }

    // ── Popups ──
    FadingPopupHost(LocalOverlays.editHistoryFor, Modifier.zIndex(10.6f)) { item ->
        EditHistoryPopup(item, liquidGlass, postTint, backdrop, onClose = { LocalOverlays.editHistoryFor = null })
    }
    FadingPopupHost(LocalOverlays.bookmarkFolderFor, Modifier.zIndex(10.6f)) { item ->
        BookmarkFolderPopup(item, liquidGlass, postTint, backdrop, onClose = { LocalOverlays.bookmarkFolderFor = null })
    }
    FadingPopupHost(LocalOverlays.profileNoteFor, Modifier.zIndex(10.6f)) { author ->
        ProfileNotePopup(author, liquidGlass, tint, backdrop, onClose = { LocalOverlays.profileNoteFor = null })
    }
    FadingPopupHost(LocalOverlays.feedBuilder, Modifier.zIndex(10.6f)) { feed ->
        FeedBuilderPopup(
            initial = feed, myLists = myLists, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            onLoadLists = viewModel::loadListsForBuilder,
            onResolveList = viewModel::resolveListForBuilder,
            onResolveAccount = viewModel::resolveAccountForBuilder,
            // A feed that's open while it's edited reloads with its new contents.
            onSaved = { saved -> if (selectedFeed == saved.uri) viewModel.selectFeed(saved.uri) },
            onDeleted = { gone -> if (selectedFeed == gone.uri) viewModel.selectFeed(null) },
            onClose = { LocalOverlays.feedBuilder = null }
        )
    }

    // Press and hold a chat in the DM list (supporters): pin / unpin.
    LocalOverlays.pinDmFor?.let { convo ->
        val isPinned = LocalData.isDmPinned(convo.convoId)
        ConfirmPopup(
            title = if (isPinned) "Unpin this chat?" else "Pin this chat?",
            message = if (isPinned) "\"${convo.member.displayName}\" goes back to the regular list."
                else "\"${convo.member.displayName}\" stays at the top of your DMs, under Pinned.",
            confirmLabel = if (isPinned) "Unpin" else "Pin",
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            onConfirm = { LocalData.setDmPinned(convo.convoId, !isPinned); LocalOverlays.pinDmFor = null },
            onDismiss = { LocalOverlays.pinDmFor = null },
            preview = convo.member.avatarUrl,
            destructive = false,
            modifier = Modifier.zIndex(10.6f)
        )
    }

    // ── "Time's up" (when the Timer page itself isn't open) ──
    if (TimerEngine.ringing && LocalOverlays.launchApp != LaunchApp.TIMER) {
        val tap = rememberHapticTap()
        val shape = RoundedCornerShape(22.dp)
        Box(Modifier.fillMaxSize().zIndex(11.6f).padding(top = rememberTopCutoutClearance() + 8.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.clip(shape).background(lerp(Color(0xFF14101A), tint, 0.3f))
                    .border(1.dp, Color(0xFFFF4FA1).copy(alpha = 0.9f), shape)
                    .clickable { tap(); TimerEngine.reset() }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⏰", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text("Time's up — tap to stop", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // ── Floating web pages: over everything, on every page ──
    BrowserPopoutLayer(tint, Modifier.zIndex(11.5f))
}
