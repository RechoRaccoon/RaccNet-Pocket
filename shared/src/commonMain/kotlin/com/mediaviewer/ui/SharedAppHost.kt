package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mediaviewer.platform.AppEvents
import com.mediaviewer.platform.AppPlatform
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import com.mediaviewer.ui.theme.MediaViewerTheme
import com.mediaviewer.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * Stellar's app shell for platforms without an Activity (iOS): starts the
 * shared stores, owns the [MainViewModel], shows app messages (there's no
 * system Toast) and rebuilds everything in place when the app asks to
 * restart (account switch, backup import). Android keeps MainActivity.
 */
object SharedAppStartup {
    private var started = false

    /** Reads every saved setting the first frames need. Call once. */
    fun init(context: PlatformContext) {
        if (started) return
        started = true
        com.mediaviewer.util.UiToggles.init(context)
        com.mediaviewer.util.FontStore.init(context)
        com.mediaviewer.util.ListRecency.init(context)
        com.mediaviewer.util.HubLayout.init(context)
        com.mediaviewer.util.TitleCovers.init(context)
        com.mediaviewer.repository.WikipediaRepository.init(context)
        ProfileColorStore.init(context)
        SelfProfileColors.init(context)
        com.mediaviewer.util.AdultContentPolicy.init(context)
    }
}

@Composable
fun SharedAppHost(
    context: PlatformContext,
    createPlatform: (BlueskyRepository, E621Repository) -> AppPlatform,
    crashLog: String? = null,
    onCrashLogDismissed: () -> Unit = {},
) {
    remember { SharedAppStartup.init(context); true }
    var pendingCrash by remember { mutableStateOf(crashLog) }
    val selectedFont = com.mediaviewer.util.FontStore.selected
    val fontFamily = com.mediaviewer.util.FontStore.familyFor(selectedFont)
    MediaViewerTheme(customFontFamily = fontFamily) {
        val crash = pendingCrash
        if (crash != null) {
            CrashLogScreen(log = crash, onDismiss = { onCrashLogDismissed(); pendingCrash = null })
            return@MediaViewerTheme
        }
        // A restart request rebuilds the ViewModel (and every screen) from
        // the saved state, like Android's process restart.
        var generation by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { AppEvents.restart.collect { generation++ } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            key(generation) {
                val store = remember { ViewModelStore() }
                DisposableEffect(store) { onDispose { store.clear() } }
                val viewModel = remember(store) {
                    ViewModelProvider.create(
                        store,
                        viewModelFactory { initializer { MainViewModel(createPlatform) } }
                    )[MainViewModel::class]
                }
                // DM/Inbox polling pauses while the app is in the background.
                LifecycleStartEffect(viewModel) {
                    viewModel.setAppForeground(true)
                    onStopOrDispose { viewModel.setAppForeground(false) }
                }
                AppRoot(viewModel)
            }
            AppMessageToast(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The Android-Toast-style pill for [AppEvents.messages]. */
@Composable
private fun AppMessageToast(modifier: Modifier = Modifier) {
    var message by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        AppEvents.messages.collect { m ->
            message = m
            visible = true
        }
    }
    LaunchedEffect(message, visible) {
        if (visible) {
            delay(if ((message?.length ?: 0) > 60) 3500L else 2000L)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible && message != null,
        enter = fadeIn(), exit = fadeOut(),
        modifier = modifier.windowInsetsPadding(WindowInsets.navBarSpace).padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
    ) {
        Text(
            message.orEmpty(),
            color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .background(Color(0xE6303034), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
