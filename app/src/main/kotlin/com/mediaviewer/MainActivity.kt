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
import com.mediaviewer.ui.ListPickerDialog
import com.mediaviewer.ui.MainFeedScreen
import com.mediaviewer.ui.QuoteRepostDialog
import com.mediaviewer.ui.ReplyDialog
import com.mediaviewer.ui.SendDmDialog
import com.mediaviewer.ui.theme.MediaViewerTheme
import com.mediaviewer.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MediaViewerTheme { AppRoot(viewModel) } }
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
            onTapAuthor               = { item -> viewModel.showAuthorFeed(item) },
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
            friendsFeedLoadingOverlay = friendsFeedLoadingOverlay
        )

        if (sendPopupTarget != null) {
            SendDmDialog(
                target          = sendPopupTarget,
                conversations   = dmConversations,
                loading         = dmConversationsLoading,
                selected        = sendPopupSelected,
                sending         = sendPopupSending,
                liquidGlass     = liquidGlass,
                onToggleSelect  = viewModel::toggleSendRecipient,
                onSend          = viewModel::sendToSelectedRecipients,
                onDismiss       = viewModel::dismissSendPopup
            )
        }

        if (quoteRepostTarget != null) {
            QuoteRepostDialog(
                target      = quoteRepostTarget,
                submitting  = quoteRepostSubmitting,
                onSubmit    = viewModel::submitQuoteRepost,
                onDismiss   = viewModel::dismissQuoteRepost
            )
        }

        if (replyToConvo != null) {
            ReplyDialog(
                convo     = replyToConvo,
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
