package com.mediaviewer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.content.FileProvider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.ScreenState
import com.mediaviewer.ui.GlassBackdrop
import com.mediaviewer.ui.LocalGlassIntensity
import com.mediaviewer.ui.LocalGlassRimIntensity
import com.mediaviewer.ui.LocalGlassRimVibrantSecondary
import com.mediaviewer.ui.NeutralGlassTint
import com.mediaviewer.ui.rememberDominantColor
import com.mediaviewer.ui.DmInboxOverlay
import com.mediaviewer.ui.ListPickerDialog
import com.mediaviewer.ui.LiveNowPlayerOverlay
import com.mediaviewer.ui.MainFeedScreen
import com.mediaviewer.ui.CameraNotchButton
import com.mediaviewer.ui.VrmModeScreen
import com.mediaviewer.ui.PixelMatrixOverlay
import com.mediaviewer.ui.PixelPhase
import com.mediaviewer.ui.ProfileOverlay
import com.mediaviewer.ui.fetchDominantColor
import com.mediaviewer.ui.rememberLoadingTransition
import com.mediaviewer.ui.ShatterOverlay
import com.mediaviewer.ui.blockClicksBehind
import com.mediaviewer.ui.recordLastTap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.mediaviewer.ui.QuoteRepostDialog
import com.mediaviewer.ui.ReplyDialog
import com.mediaviewer.ui.SearchOverlay
import com.mediaviewer.ui.SendDmDialog
import com.mediaviewer.ui.SettingsExtras
import com.mediaviewer.ui.TaggingOverlay
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
    val javaLog = if (file.exists()) runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } else null
    // Native (Filament) aborts and low-memory kills skip the Java handler;
    // the breadcrumb says what was running instead.
    return javaLog ?: com.mediaviewer.util.CrashBreadcrumbs.report()
}

private fun clearCrashLog(context: Context) {
    runCatching { File(context.filesDir, CRASH_LOG_FILENAME).delete() }
    com.mediaviewer.util.CrashBreadcrumbs.dismissReport()
}

class MainActivity : ComponentActivity() {
    // Screens (VRM mode) can claim hardware keys — see HardwareKeys.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        com.mediaviewer.util.HardwareKeys.dispatch(event) || super.dispatchKeyEvent(event)

    // MainViewModel is shared with iOS; on Android it runs through
    // AndroidAppPlatform (haptics, toasts, downloads, tagging, widgets…).
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel { bsky, e621 -> com.mediaviewer.app.AndroidAppPlatform(application, bsky, e621) }
            }
        }
    }

    /** A bsky.app/profile/<actor> link Stellar was opened with, waiting to
     *  be opened once the app (and your login) is ready. */
    private val pendingProfileLink = mutableStateOf<String?>(null)

    private fun handleLinkIntent(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (intent?.action != android.content.Intent.ACTION_VIEW) return
        val host = data.host?.lowercase() ?: return
        if (host != "bsky.app" && host != "www.bsky.app") return
        val segments = data.pathSegments ?: return
        if (segments.size >= 2 && segments[0] == "profile" && segments[1].isNotBlank()) {
            // bsky.app/profile/<actor>/post/<rkey> → "<actor>|<rkey>" (the
            // profile opens, then the post on top of it).
            val rkey = if (segments.size >= 4 && segments[2] == "post") segments[3] else null
            pendingProfileLink.value = if (rkey.isNullOrBlank()) segments[1] else segments[1] + "|" + rkey
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLinkIntent(intent)
    }

    /** Opens the app already in your profile colors: the first frame is
     *  held back (the window's own background shows meanwhile) until "your
     *  color" is known — instantly on most launches, since it's remembered
     *  from the last run. Nothing to wait for when you're signed out, and
     *  never longer than [PROFILE_COLOR_WAIT_MS] (e.g. offline on a first
     *  launch). */
    private fun holdFirstFrameForProfileColors() {
        if (com.mediaviewer.ui.SelfProfileColors.ready) return
        val started = android.os.SystemClock.uptimeMillis()
        // Signed out → no profile color to wait for.
        lifecycleScope.launch {
            val did = runCatching { com.mediaviewer.util.PreferencesManager(applicationContext).bskyDid.first() }.getOrNull()
            if (did.isNullOrBlank()) com.mediaviewer.ui.SelfProfileColors.ready = true
        }
        val content = findViewById<android.view.View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val go = com.mediaviewer.ui.SelfProfileColors.ready ||
                    android.os.SystemClock.uptimeMillis() - started > com.mediaviewer.ui.PROFILE_COLOR_WAIT_MS
                if (go) content.viewTreeObserver.removeOnPreDrawListener(this)
                return go
            }
        })
    }
    override fun onCreate(savedInstanceState: Bundle?) {        super.onCreate(savedInstanceState)
        com.mediaviewer.ui.compat.AndroidToastHost.appContext = applicationContext
        installCrashHandler(applicationContext)
        com.mediaviewer.util.CrashBreadcrumbs.init(applicationContext)
        com.mediaviewer.util.UiToggles.init(applicationContext)
        com.mediaviewer.util.FontStore.init(applicationContext)
        com.mediaviewer.util.ListRecency.init(applicationContext)
        com.mediaviewer.util.HubLayout.init(applicationContext)
        com.mediaviewer.util.TitleCovers.init(applicationContext)
        com.mediaviewer.repository.WikipediaRepository.init(applicationContext)
        com.mediaviewer.ui.ProfileColorStore.init(applicationContext)
        com.mediaviewer.ui.SelfProfileColors.init(applicationContext)
        // Audio visualizer: start noting music apps' audio sessions right
        // away, so the bars can attach to one even if the music started
        // before the feed was opened.
        if (com.mediaviewer.util.UiToggles.audioVisualizer) {
            com.mediaviewer.util.AudioVisualizerEngine.watchPlayerSessions(applicationContext)
        }
        com.mediaviewer.util.ImageLoading.install(applicationContext)
        // 30-day image cache age limit: checked now, and daily in the
        // background for when the app isn't opened (see ImageLoading).
        // Off the main thread: clearing a large disk cache is file I/O.
        Thread({ runCatching { com.mediaviewer.util.ImageLoading.wipeIfDue(applicationContext) } }, "image-cache-expiry").start()
        runCatching { com.mediaviewer.worker.ImageCacheExpiryWorker.schedule(applicationContext) }
        enableEdgeToEdge()
        hideSystemStatusBar()
        // Bug fix: lets the background/media draw all the way up under the
        // camera cutout instead of the system reserving a blank strip there
        // — the cutout area should show background color/art only, with
        // real interactive UI padded clear of it instead (see
        // rememberTopCutoutClearance in GlassTheme.kt for that half of the
        // fix). ALWAYS is available from API 28; SHORT_EDGES is the closest
        // equivalent on 27, and there's no cutout API at all below that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        holdFirstFrameForProfileColors()
        handleLinkIntent(intent)
        // Reduced Animations: every Compose animation in the window reads
        // its speed from AppMotion (0 = instant) — see util/AppMotion.kt.
        com.mediaviewer.util.AppMotion.init(applicationContext)
        installReducedMotionRecomposer()
        setContent {
            var crashLog by remember { mutableStateOf(readCrashLog(applicationContext)) }
            if (crashLog != null) {
                com.mediaviewer.ui.CrashLogScreen(log = crashLog!!, onDismiss = { clearCrashLog(applicationContext); crashLog = null })
                return@setContent
            }
            // App Font: Audiowide by default, the Original system font, or
            // any imported font (Settings → UI Customization → App Font).
            // A font picked with the old single-font setting is adopted into
            // the list once.
            val legacyFontPath by viewModel.customFontPath.collectAsState()
            val legacyFontName by viewModel.customFontName.collectAsState()
            LaunchedEffect(legacyFontPath) {
                if (legacyFontPath != null) com.mediaviewer.util.FontStore.adoptLegacy(legacyFontPath, legacyFontName)
            }
            val selectedFont = com.mediaviewer.util.FontStore.selected
            val customFontFamily = com.mediaviewer.util.FontStore.familyFor(selectedFont)
            MediaViewerTheme(customFontFamily = customFontFamily) {
                com.mediaviewer.ui.AppRoot(viewModel, pendingProfileLink = pendingProfileLink.value, onProfileLinkHandled = { pendingProfileLink.value = null })
            }
        }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun installReducedMotionRecomposer() {
        runCatching {
            androidx.compose.ui.platform.WindowRecomposerPolicy.setFactory { rootView ->
                rootView.createLifecycleAwareWindowRecomposer(com.mediaviewer.util.AppMotion)
            }
        }
    }

    // Item (this session): the Jetstream/firehose connection this used to
    // nudge on every resume is gone (Reviews/Blogs are now a direct,
    // per-visit/per-refresh PDS fetch off the Subscribe lists — see
    // MainViewModel.loadFriendsReviewsIfNeeded — nothing persistent to
    // reconnect), so there's nothing left for onResume to do here.

    override fun onStart() {
        super.onStart()
        viewModel.setAppForeground(true)
    }

    override fun onStop() {
        super.onStop()
        // DM/Inbox polling pauses while the app is off screen.
        viewModel.setAppForeground(false)
    }

    override fun onResume() {
        super.onResume()
        // Re-assert immersive mode: the system can bring the status/nav bars
        // back after things like a permission dialog, keyboard, or app switch.
        hideSystemStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemStatusBar()
    }

    /**
     * Fully hides the phone status bar (clock, battery, wifi/signal icons, etc).
     * Uses immersive-sticky behavior so a swipe from the edge only shows the
     * bar temporarily and it re-hides itself; the nav bar is left alone.
     */
    private fun hideSystemStatusBar() {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    }
}
