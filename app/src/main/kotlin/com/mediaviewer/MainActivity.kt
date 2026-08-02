package com.mediaviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.mediaviewer.model.AppMode
import com.mediaviewer.ui.GlassBackdrop
import com.mediaviewer.ui.NeutralGlassTint
import com.mediaviewer.ui.DmInboxOverlay
import com.mediaviewer.ui.ListPickerDialog
import com.mediaviewer.ui.MainFeedScreen
import com.mediaviewer.ui.ProfileOverlay
import com.mediaviewer.ui.QuoteRepostDialog
import com.mediaviewer.ui.ReplyDialog
import com.mediaviewer.ui.SendDmDialog
import com.mediaviewer.ui.theme.MediaViewerTheme
import com.mediaviewer.viewmodel.MainViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
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
    val dmInboxOpen            by viewModel.dmInboxOpen.collectAsState()
    val dmThread               by viewModel.dmThread.collectAsState()
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
            onPostComment             = viewModel::postComment,
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
            onTapAuthor               = { item -> if (appMode == AppMode.BLUESKY) viewModel.openProfile(item.author) else viewModel.showAuthorFeed(item) },
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
            friendsFeedLoadingOverlay = friendsFeedLoadingOverlay,
            onCurrentBackdropChanged  = { backdrop, color -> currentBackdrop = backdrop; currentDominantColor = color },
            selfProfile               = selfProfile,
            hideTextOnlyPosts         = hideTextOnlyPosts,
            onToggleHideTextOnlyPosts = viewModel::setHideTextOnlyPosts,
            onOpenOwnProfile          = viewModel::openOwnProfile,
            onShowSaves               = viewModel::showSaves,
            onShowHistory             = viewModel::showHistory,
            onOpenDmInbox             = viewModel::openDmInbox,
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
                onSelectConvo   = viewModel::openDmThread,
                onCloseThread   = viewModel::closeDmThread,
                onSendReply     = viewModel::sendDmThreadReply,
                onClose         = viewModel::closeDmInbox
            )
        }

        val currentProfileOverlay = profileOverlay
        if (currentProfileOverlay != null) {
            ProfileOverlay(
                state             = currentProfileOverlay,
                liquidGlass       = liquidGlass,
                reducedAnimations = reducedAnimations,
                onClose           = viewModel::closeProfile,
                onSelectTab       = viewModel::selectProfileTab,
                onLoadMore        = viewModel::loadMoreProfileTab,
                onSetExpanded     = viewModel::setProfileExpanded,
                onToggleFollow    = viewModel::toggleProfileFollow,
                onTapItem         = viewModel::openPostFromProfileTab,
                onOpenBlog        = viewModel::openProfileBlog,
                onCloseBlog       = viewModel::closeProfileBlog,
                onOpenReview      = viewModel::openProfileReview,
                onCloseReview     = viewModel::closeProfileReview
            )
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

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            kotlinx.coroutines.delay(6000)
            viewModel.clearError()
        }
    }
}
