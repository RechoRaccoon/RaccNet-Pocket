package com.mediaviewer

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.mediaviewer.model.AppMode
import com.mediaviewer.ui.GlassBackdrop
import com.mediaviewer.ui.LocalGlassIntensity
import com.mediaviewer.ui.LocalGlassRimIntensity
import com.mediaviewer.ui.NeutralGlassTint
import com.mediaviewer.ui.DmInboxOverlay
import com.mediaviewer.ui.ListPickerDialog
import com.mediaviewer.ui.MainFeedScreen
import com.mediaviewer.ui.ProfileOverlay
import com.mediaviewer.ui.QuoteRepostDialog
import com.mediaviewer.ui.ReplyDialog
import com.mediaviewer.ui.SearchOverlay
import com.mediaviewer.ui.SendDmDialog
import com.mediaviewer.ui.theme.MediaViewerTheme
import com.mediaviewer.viewmodel.MainViewModel
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// No Android Studio/adb in this workflow (APKs are built by GitHub Actions
// and sideloaded straight onto the phone), so there's normally no way to see
// a crash's stack trace at all. This is a minimal self-contained crash
// catcher: any uncaught exception gets written to a plain file in internal
// storage, and the *next* time the app is opened, that file's contents are
// shown as plain copyable text instead of the normal UI — so a crash can be
// diagnosed just by reopening the app and copying what's on screen.
private const val CRASH_LOG_FILENAME = "last_crash.txt"

private fun installCrashHandler(context: Context) {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            File(context.filesDir, CRASH_LOG_FILENAME).writeText(sw.toString())
        }
        // Still hand off to whatever Android's own default handler is (shows
        // the normal "app has stopped" dialog and actually closes the
        // process) — this only adds a side-effect, it doesn't swallow the
        // crash.
        previousHandler?.uncaughtException(thread, throwable)
    }
}

private fun readCrashLog(context: Context): String? {
    val file = File(context.filesDir, CRASH_LOG_FILENAME)
    return if (file.exists()) runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } else null
}

private fun clearCrashLog(context: Context) {
    runCatching { File(context.filesDir, CRASH_LOG_FILENAME).delete() }
}

@Composable
private fun CrashLogScreen(log: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)
    ) {
        Text("RaccNetLite crashed last time it ran", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Copy this and send it back for a fix.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.background(Color(0xFF2A7D46)).clickable { clipboard.setText(AnnotatedString(log)) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text("Copy", color = Color.White, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.background(Color.White.copy(alpha = 0.15f)).clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text("Dismiss", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(log, color = Color(0xFF8BE28B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler(applicationContext)
        enableEdgeToEdge()
        setContent {
            var crashLog by remember { mutableStateOf(readCrashLog(applicationContext)) }
            if (crashLog != null) {
                CrashLogScreen(log = crashLog!!, onDismiss = { clearCrashLog(applicationContext); crashLog = null })
                return@setContent
            }
            // Phase 4 — custom font pack: rebuilt only when the stored path
            // actually changes, not on every recomposition. Falls back to null
            // (MediaViewerTheme's own default Typography) if the file somehow
            // isn't there anymore (e.g. cleared app storage out from under it).
            val customFontPath by viewModel.customFontPath.collectAsState()
            val customFontFamily = remember(customFontPath) {
                customFontPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) FontFamily(Font(file)) else null
                }
            }
            MediaViewerTheme(customFontFamily = customFontFamily) { AppRoot(viewModel) }
        }
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val mediaItems         by viewModel.mediaItems.collectAsState()
    val currentIndex       by viewModel.currentIndex.collectAsState()
    val currentItem        by viewModel.currentItem.collectAsState()
    val screenState        by viewModel.screenState.collectAsState()
    val appMode            by viewModel.appMode.collectAsState()
    val navDirection       by viewModel.navDirection.collectAsState()
    val reducedAnimations  by viewModel.reducedAnimations.collectAsState()
    val liquidGlass        by viewModel.liquidGlass.collectAsState()
    val liquidGlassIntensity by viewModel.liquidGlassIntensity.collectAsState()
    val glassRimIntensity  by viewModel.glassRimIntensity.collectAsState()
    val availableFeeds     by viewModel.availableFeeds.collectAsState()
    val selectedFeed       by viewModel.selectedFeedUri.collectAsState()
    val authorFeedState    by viewModel.authorFeedState.collectAsState()
    val comments           by viewModel.comments.collectAsState()
    val commentsLoad       by viewModel.commentsLoading.collectAsState()
    val downloadOnLike     by viewModel.downloadOnLike.collectAsState()
    val downloadProgress   by viewModel.downloadProgress.collectAsState()
    val e621Tags           by viewModel.e621SearchTags.collectAsState()
    val isLoading          by viewModel.isLoading.collectAsState()
    val bskyLoggedIn       by viewModel.bskyLoggedIn.collectAsState()
    val e621LoggedIn       by viewModel.e621LoggedIn.collectAsState()
    val errorMessage       by viewModel.errorMessage.collectAsState()
    val listPickerDid      by viewModel.listPickerTargetDid.collectAsState()
    val userLists          by viewModel.userLists.collectAsState()
    val userStarterPacks   by viewModel.userStarterPacks.collectAsState()
    val userListsLoading   by viewModel.userListsLoading.collectAsState()
    val lastPickerTab      by viewModel.lastPickerTab.collectAsState()
    val combineListsPacks  by viewModel.combineListsAndPacks.collectAsState()
    val autoAddToOnFollow  by viewModel.autoAddToOnFollow.collectAsState()
    val dmConversations       by viewModel.dmConversations.collectAsState()
    val dmConversationsLoading by viewModel.dmConversationsLoading.collectAsState()
    val sendPopupTarget       by viewModel.sendPopupTarget.collectAsState()
    val sendPopupSelected     by viewModel.sendPopupSelected.collectAsState()
    val sendPopupSending      by viewModel.sendPopupSending.collectAsState()
    val quoteRepostTarget     by viewModel.quoteRepostTarget.collectAsState()
    val quoteRepostSubmitting by viewModel.quoteRepostSubmitting.collectAsState()
    val replyToConvo          by viewModel.replyToConvo.collectAsState()
    val sentByExpanded        by viewModel.sentByExpanded.collectAsState()
    val friendsFeedLoadingOverlay by viewModel.friendsFeedLoadingOverlay.collectAsState()
    val profileOverlay         by viewModel.profileOverlay.collectAsState()
    val selfProfile            by viewModel.selfProfile.collectAsState()
    val hideTextOnlyPosts      by viewModel.hideTextOnlyPosts.collectAsState()
    val bskyDid                by viewModel.bskyDid.collectAsState()
    val dmInboxOpen            by viewModel.dmInboxOpen.collectAsState()
    val dmThread               by viewModel.dmThread.collectAsState()
    val searchOpen             by viewModel.searchOpen.collectAsState()
    val searchState            by viewModel.searchState.collectAsState()
    // Phase 4
    val translationEnabled     by viewModel.translationEnabled.collectAsState()
    val translationTargetLang  by viewModel.translationTargetLang.collectAsState()
    val customFontName         by viewModel.customFontName.collectAsState()

    // Big Update #10: the currently-on-screen post's live backdrop + dominant
    // color, reported up from inside the pager (see PostContent's onBackdropChanged)
    // so overlays that live above the whole pager — Share, Add To — can show the
    // same real-time reflection the in-post glass panels do, instead of a plain
    // static tint.
    var currentBackdrop by remember { mutableStateOf<GlassBackdrop?>(null) }
    var currentDominantColor by remember { mutableStateOf(NeutralGlassTint) }

    // Item 26: makes the glass-intensity dial reach every LiquidGlassSurface/
    // glassPanel below without threading a Float through every composable's
    // parameter list.
    CompositionLocalProvider(
        LocalGlassIntensity provides liquidGlassIntensity,
        LocalGlassRimIntensity provides glassRimIntensity
    ) {
    Box(Modifier.fillMaxSize()) {
        MainFeedScreen(
            mediaItems                = mediaItems,
            currentIndex              = currentIndex,
            currentItem               = currentItem,
            screenState               = screenState,
            appMode                   = appMode,
            navDirection              = navDirection,
            reducedAnimations         = reducedAnimations,
            liquidGlass               = liquidGlass,
            onToggleLiquidGlass       = viewModel::setLiquidGlass,
            liquidGlassIntensity      = liquidGlassIntensity,
            onSetLiquidGlassIntensity = viewModel::setLiquidGlassIntensity,
            glassRimIntensity         = glassRimIntensity,
            onSetGlassRimIntensity    = viewModel::setGlassRimIntensity,
            availableFeeds            = availableFeeds,
            selectedFeedUri           = selectedFeed,
            authorFeedState           = authorFeedState,
            comments                  = comments,
            commentsLoading           = commentsLoad,
            downloadOnLike            = downloadOnLike,
            downloadProgress          = downloadProgress,
            e621SearchTags            = e621Tags,
            isLoading                 = isLoading,
            bskyLoggedIn              = bskyLoggedIn,
            e621LoggedIn              = e621LoggedIn,
            bskyHandle                = viewModel.bskyHandle,
            e621Username              = viewModel.e621Username,
            errorMessage              = errorMessage,
            onNavigateNext            = viewModel::navigateNext,
            onNavigatePrev            = viewModel::navigatePrev,
            onNavigateTo              = viewModel::navigateTo,
            onSetScreen               = viewModel::setScreen,
            onToggleLike              = viewModel::toggleLike,
            onToggleRepost            = viewModel::toggleRepost,
            onToggleBookmark          = viewModel::toggleBookmark,
            onToggleFollow            = viewModel::toggleFollow,
            onE621Vote                = viewModel::e621Vote,
            onPostComment             = { text, replyTo -> viewModel.postComment(text, replyTo) },
            onLikeComment             = viewModel::likeComment,
            onVoteComment             = viewModel::voteComment,
            // All feed-chip selections route through selectFeedFromAnyContext so that
            // selecting the previous feed while in an author overlay restores scroll position
            onSelectFeed              = viewModel::selectFeedFromAnyContext,
            onToggleDownloadOnLike    = viewModel::setDownloadOnLike,
            onDownloadAllLiked        = viewModel::downloadAllLiked,
            onCancelDownload          = viewModel::cancelDownloadAll,
            onShowLikes               = viewModel::showBskyLikes,
            onShowFriends             = viewModel::showFriendsFeed,
            onShowE621Following       = viewModel::searchFollowingE621,
            onToggleReducedAnimations = viewModel::setReducedAnimations,
            combineListsAndPacks      = combineListsPacks,
            onToggleCombineListsPacks = viewModel::setCombineListsAndPacks,
            autoAddToOnFollow         = autoAddToOnFollow,
            onToggleAutoAddToOnFollow = viewModel::setAutoAddToOnFollow,
            onLoginBluesky            = viewModel::loginBluesky,
            onLogoutBluesky           = viewModel::logoutBluesky,
            onSaveE621Credentials     = viewModel::saveE621Credentials,
            onLogoutE621              = viewModel::logoutE621,
            onSearchE621              = { tags -> viewModel.setE621SearchTags(tags); viewModel.searchE621() },
            onShowE621Favorites       = viewModel::showE621Favorites,
            onSwipeToMode             = viewModel::setMode,
            onLoadMore                = viewModel::loadMore,
            onDownloadCurrent         = viewModel::downloadCurrentItem,
            onRefresh                 = { viewModel.loadFeed(reset = true) },
            // Profile Overhaul: tapping an account now opens the full Profile
            // Overlay instead of swapping the pager to their feed directly.
            // e621 has no notion of an account profile, so tapping an artist
            // there keeps the old behavior of searching that artist's tag.
            // If the post being viewed is text-only, open straight into that
            // profile's Text Posts tab instead of the default Media tab.
            onTapAuthor               = { item ->
                if (appMode == AppMode.BLUESKY) {
                    val tab = if (item.isTextOnly) MainViewModel.ProfileTab.TEXT_POSTS else MainViewModel.ProfileTab.MEDIA
                    viewModel.openProfile(item.author, initialTab = tab)
                } else viewModel.showAuthorFeed(item)
            },
            onPinchIn                 = viewModel::pinchInFromPost,
            // Item 1: pause whatever's playing behind a visible (non-hidden)
            // profile overlay — see the doc comment on this param in
            // MainFeedScreen for why the grid case doesn't need this too.
            externallyPaused           = profileOverlay?.hidden == false,
            onTagClick                = { tag -> viewModel.searchSingleTag(tag) },
            onTagAdd                  = { tag -> viewModel.addTagToSearch(tag, exclude = false) },
            onTagExclude              = { tag -> viewModel.addTagToSearch(tag, exclude = true) },
            onSendPost                = viewModel::openSendPopup,
            onQuoteRepost             = viewModel::openQuoteRepost,
            onBlockAccount            = viewModel::toggleBlockCurrentAuthor,
            onDownloadGif             = viewModel::downloadCurrentItemAsGif,
            sentByExpanded            = sentByExpanded,
            onToggleSentByExpanded    = viewModel::toggleSentByExpanded,
            onOpenReplyToSender       = viewModel::openReplyToSender,
            // Item 27: tapping the sender's avatar in the "Sent by" header
            // (From Friends feed) opens their profile.
            onTapSentByAuthor         = { author -> viewModel.openProfile(author) },
            friendsFeedLoadingOverlay = friendsFeedLoadingOverlay,
            onCurrentBackdropChanged  = { backdrop, color -> currentBackdrop = backdrop; currentDominantColor = color },
            selfProfile               = selfProfile,
            hideTextOnlyPosts         = hideTextOnlyPosts,
            onToggleHideTextOnlyPosts = viewModel::setHideTextOnlyPosts,
            onOpenOwnProfile          = viewModel::openOwnProfile,
            onShowSaves               = viewModel::showSaves,
            onShowHistory             = viewModel::showHistory,
            onOpenDmInbox             = viewModel::openDmInbox,
            onOpenSearch              = viewModel::openSearch,
            translationEnabled        = translationEnabled,
            translationTargetLang     = translationTargetLang,
            onToggleTranslation       = viewModel::setTranslationEnabled,
            onSelectTranslationLanguage = viewModel::setTranslationTargetLang,
            customFontName            = customFontName,
            onPickFontFile            = viewModel::setCustomFontFromUri,
            onResetFont               = viewModel::resetCustomFont
        )

        if (dmInboxOpen) {
            DmInboxOverlay(
                conversations   = dmConversations,
                loading         = dmConversationsLoading,
                thread          = dmThread,
                liquidGlass     = liquidGlass,
                selfAvatarUrl   = selfProfile?.author?.avatarUrl,
                onSelectConvo   = viewModel::openDmThread,
                onCloseThread   = viewModel::closeDmThread,
                onSendReply     = viewModel::sendDmThreadReply,
                onClose         = viewModel::closeDmInbox,
                onTapAuthor     = { author -> viewModel.closeDmInbox(); viewModel.openProfile(author) }
            )
        }

        if (searchOpen) {
            SearchOverlay(
                state           = searchState,
                liquidGlass     = liquidGlass,
                onQueryChange   = viewModel::runSearch,
                onSelectFilter  = viewModel::setSearchFilter,
                onOpenPost      = viewModel::openPostFromSearch,
                onOpenAccount   = { author -> viewModel.closeSearch(); viewModel.openProfile(author) },
                onClose         = viewModel::closeSearch
            )
        }

        val currentProfileOverlay = profileOverlay
        if (currentProfileOverlay != null) {
            // Pinch navigation: a "hidden" profile (tapped a post from inside
            // it — see openPostFromProfileTab) stays fully composed at zero
            // size instead of being removed, so its LazyListState (scroll
            // position), loaded tabs, etc. survive untouched. Zero size means
            // it can't be seen or hit-test any touches, so the pager
            // underneath is fully interactive again — pinching back in
            // (pinchInFromPost) just flips this back to full size.
            Box(if (currentProfileOverlay.hidden) Modifier.size(0.dp) else Modifier.fillMaxSize()) {
                ProfileOverlay(
                    state             = currentProfileOverlay,
                    liquidGlass       = liquidGlass,
                    reducedAnimations = reducedAnimations,
                    selfDid           = bskyDid,
                    onClose           = viewModel::closeProfile,
                    onSelectTab       = viewModel::selectProfileTab,
                    onLoadMore        = viewModel::loadMoreProfileTab,
                    onToggleFollow    = viewModel::toggleProfileFollow,
                    onTapItem         = viewModel::openPostFromProfileTab,
                    onOpenBlog        = viewModel::openProfileBlog,
                    onCloseBlog       = viewModel::closeProfileBlog,
                    onOpenReview      = viewModel::openProfileReview,
                    onCloseReview     = viewModel::closeProfileReview,
                    onPinchOut        = viewModel::pinchOutFromProfile
                )
            }
        }

        val currentSendTarget = sendPopupTarget
        if (currentSendTarget != null) {
            SendDmDialog(
                target          = currentSendTarget,
                conversations   = dmConversations,
                loading         = dmConversationsLoading,
                selected        = sendPopupSelected,
                sending         = sendPopupSending,
                liquidGlass     = liquidGlass,
                dominantColor   = currentDominantColor,
                backdrop        = currentBackdrop,
                onToggleSelect  = viewModel::toggleSendRecipient,
                onSend          = viewModel::sendToSelectedRecipients,
                onDismiss       = viewModel::dismissSendPopup
            )
        }

        val currentQuoteTarget = quoteRepostTarget
        if (currentQuoteTarget != null) {
            QuoteRepostDialog(
                target      = currentQuoteTarget,
                submitting  = quoteRepostSubmitting,
                liquidGlass   = liquidGlass,
                dominantColor = currentDominantColor,
                backdrop      = currentBackdrop,
                onSubmit    = viewModel::submitQuoteRepost,
                onDismiss   = viewModel::dismissQuoteRepost
            )
        }

        val currentReplyConvo = replyToConvo
        if (currentReplyConvo != null) {
            ReplyDialog(
                convo     = currentReplyConvo,
                onSend    = viewModel::sendReply,
                onDismiss = viewModel::dismissReplyPopup
            )
        }

        if (listPickerDid != null) {
            ListPickerDialog(
                lists         = userLists,
                starterPacks  = userStarterPacks,
                listsLoading  = userListsLoading,
                initialTab    = lastPickerTab,
                combineMode   = combineListsPacks,
                liquidGlass   = liquidGlass,
                dominantColor = currentDominantColor,
                backdrop      = currentBackdrop,
                onTabChange   = { tab -> viewModel.setPickerTab(tab) },
                onSelectList  = { listUri, additionalUri -> viewModel.addAccountToList(listUri, additionalUri) },
                onDismiss     = { viewModel.dismissListPicker() }
            )
        }
    }
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            kotlinx.coroutines.delay(6000)
            viewModel.clearError()
        }
    }
}
