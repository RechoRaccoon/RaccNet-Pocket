package com.mediaviewer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.ui.compat.rememberPlatformView
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class OnboardingStage { NONE, WELCOME, TUTORIAL, SUPPORT }

/**
 * The first-run popups, drawn over the whole app while it is blurred behind
 * them (AppRoot blurs its content by [blur], 0–1, and this dims it):
 *
 *  1. "Welcome to Stellar!!" — two feeds to add and two accounts to follow,
 *     each with a switch (all on), then Continue.
 *  2. "Stellar Tutorial" — the tutorial video (grows to full screen to
 *     play), then "Go beyond the Atmosphere", which fades the popup away
 *     as the Hub comes back into focus.
 *
 * Also hosts the "Support Stellar" popup of the tenth open.
 */
@Composable
fun OnboardingPopupHost(
    welcome: MainViewModel.WelcomeState?,
    tutorialOpen: Boolean,
    tutorialVideo: MainViewModel.TutorialVideo?,
    supportOpen: Boolean,
    openCount: Int,
    blur: State<Float>,
    tint: Color,
    liquidGlass: Boolean,
    onContinue: (Set<String>) -> Unit,
    onFinishTutorial: () -> Unit,
    onCloseSupport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stage = when {
        welcome != null -> OnboardingStage.WELCOME
        tutorialOpen -> OnboardingStage.TUTORIAL
        supportOpen -> OnboardingStage.SUPPORT
        else -> OnboardingStage.NONE
    }
    // Kept while the welcome popup fades out (its state is already gone).
    val lastWelcome = remember { arrayOfNulls<MainViewModel.WelcomeState>(1) }
    if (welcome != null) lastWelcome[0] = welcome
    // The switches: everything starts on.
    val switches = remember { mutableStateMapOf<String, Boolean>() }
    // The tutorial video's full-screen player (0 = in the popup, 1 = full screen).
    val expand = remember { Animatable(0f) }
    var playerOpen by remember { mutableStateOf(false) }
    var cardBounds by remember { mutableStateOf<Pair<Offset, IntSize>?>(null) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val view = rememberPlatformView()

    fun collapsePlayer() {
        scope.launch {
            expand.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
            playerOpen = false
        }
    }
    LaunchedEffect(stage) { if (stage != OnboardingStage.TUTORIAL) { playerOpen = false; expand.snapTo(0f) } }

    if (stage == OnboardingStage.NONE && blur.value <= 0.001f) return

    com.mediaviewer.ui.compat.BackHandler(enabled = stage != OnboardingStage.NONE) {
        when {
            playerOpen -> collapsePlayer()
            stage == OnboardingStage.SUPPORT -> onCloseSupport()
            else -> {} // Continue / the finish button are the ways on.
        }
    }

    Box(
        modifier.fillMaxSize()
            .onGloballyPositioned { hostOrigin = it.positionInRoot() }
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.5f * blur.value.coerceIn(0f, 1f))) }
            // Nothing behind the popups can be touched.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                (fadeIn(tween(320)) + scaleIn(tween(380, easing = FastOutSlowInEasing), initialScale = 0.92f))
                    .togetherWith(fadeOut(tween(260)) + scaleOut(tween(300, easing = FastOutSlowInEasing), targetScale = 0.96f))
            },
            contentAlignment = Alignment.Center,
            label = "onboardingStage"
        ) { shown ->
            when (shown) {
                OnboardingStage.NONE -> Spacer(Modifier.size(1.dp))
                OnboardingStage.WELCOME -> OnboardingPanel(tint) {
                    val state = welcome ?: lastWelcome[0]
                    if (state != null) WelcomeContent(
                        state = state, tint = tint,
                        isOn = { key -> switches[key] ?: true },
                        onToggle = { key, on -> switches[key] = on },
                        onContinue = {
                            onContinue((state.feeds + state.accounts).map { it.key }.filter { switches[it] ?: true }.toSet())
                        }
                    )
                }
                OnboardingStage.TUTORIAL -> OnboardingPanel(tint) {
                    TutorialContent(
                        video = tutorialVideo, tint = tint,
                        onCardBounds = { origin, size -> cardBounds = origin to size },
                        onPlay = {
                            playerOpen = true
                            scope.launch { expand.animateTo(1f, tween(460, easing = FastOutSlowInEasing)) }
                        },
                        onFinish = {
                            runCatching { view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.LONG_PRESS) }
                            onFinishTutorial()
                        }
                    )
                }
                OnboardingStage.SUPPORT -> OnboardingPanel(tint) {
                    SupportPopupContent(openCount = openCount, tint = tint, onClose = onCloseSupport)
                }
            }
        }

        // The tutorial video, grown out of its card to fill the screen.
        val bounds = cardBounds
        if (playerOpen && bounds != null) {
            TutorialPlayer(
                video = tutorialVideo, tint = tint, liquidGlass = liquidGlass,
                expand = expand,
                cardOrigin = bounds.first - hostOrigin, cardSize = bounds.second,
                onBack = { collapsePlayer() }
            )
        }
    }
}

/** The popups' shared panel: the app's dark, profile-colored popup surface. */
@Composable
private fun OnboardingPanel(tint: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val panel = lerp(Color(0xFF101014), tint, 0.18f)
    Box(
        Modifier.padding(horizontal = 18.dp)
            .widthIn(max = 420.dp).fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(panel, tint, 0.14f).copy(alpha = 0.94f), panel.copy(alpha = 0.94f))))
            .border(1.2.dp, Brush.linearGradient(listOf(tint.copy(alpha = 0.9f), Color.White.copy(alpha = 0.25f), tint.copy(alpha = 0.6f))), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) { content() }
}

@Composable
private fun WelcomeContent(
    state: MainViewModel.WelcomeState,
    tint: Color,
    isOn: (String) -> Boolean,
    onToggle: (String, Boolean) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Welcome to Stellar!!", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        WelcomeQuestion("Would you like to add these feeds?", tint)
        state.feeds.forEach { WelcomeRow(it, tint, isOn(it.key), enabled = !state.applying) { on -> onToggle(it.key, on) } }
        Spacer(Modifier.height(8.dp))
        WelcomeQuestion("Would you like to follow these accounts?", tint)
        state.accounts.forEach { WelcomeRow(it, tint, isOn(it.key), enabled = !state.applying) { on -> onToggle(it.key, on) } }
        Spacer(Modifier.height(10.dp))
        Text("Press \"Continue\" to apply", color = DimGray, fontSize = 11.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        OnboardingWideButton("Continue", tint, busy = state.applying, onClick = onContinue)
    }
}

@Composable
private fun WelcomeQuestion(text: String, tint: Color) {
    Text(
        text, color = lerp(tint, Color.White, 0.65f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 4.dp)
    )
}

@Composable
private fun WelcomeRow(
    entry: MainViewModel.WelcomeEntry, tint: Color, on: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, tint.copy(alpha = 0.35f), shape)
            .clickable(enabled = enabled) { tap(); onToggle(!on) }
            .padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverShape = if (entry.round) CircleShape else RoundedCornerShape(10.dp)
        Box(
            Modifier.size(38.dp).clip(coverShape)
                .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.2f), lerp(tint, Color.Black, 0.4f)))),
            contentAlignment = Alignment.Center
        ) {
            Text(entry.title.take(1), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (!entry.imageUrl.isNullOrBlank()) AsyncImage(
                model = entry.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(coverShape)
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    (if (entry.round) "" else "by ") + entry.by, color = DimGray, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
            }
            Text(
                entry.description, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp, lineHeight = 14.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        // Same compact switch as the Settings page's rows.
        Box(Modifier.size(width = 36.dp, height = 22.dp), contentAlignment = Alignment.Center) {
            Switch(
                checked = on, onCheckedChange = { tap(); onToggle(it) }, enabled = enabled,
                modifier = Modifier.scale(0.7f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = lerp(tint, Color.White, 0.25f),
                    uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)
                )
            )
        }
    }
}

/** The popups' closing button: as wide as the popup. */
@Composable
private fun OnboardingWideButton(label: String, tint: Color, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp))
            .background(Brush.horizontalGradient(listOf(lerp(tint, Color.White, 0.18f), tint, lerp(tint, Color.Black, 0.15f))))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(23.dp))
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun TutorialContent(
    video: MainViewModel.TutorialVideo?,
    tint: Color,
    onCardBounds: (Offset, IntSize) -> Unit,
    onPlay: () -> Unit,
    onFinish: () -> Unit
) {
    val tap = rememberHapticTap()
    Column(
        Modifier.fillMaxWidth().padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Stellar Tutorial", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        val shape = RoundedCornerShape(18.dp)
        Box(
            Modifier.fillMaxWidth()
                // The video's own shape, whole — nothing cropped.
                .aspectRatio((video?.aspectRatio ?: (16f / 9f)).coerceIn(0.5f, 2.4f))
                .onGloballyPositioned { onCardBounds(it.positionInRoot(), it.size) }
                .clip(shape)
                .border(1.dp, tint.copy(alpha = 0.6f), shape)
                .clickable { tap(); onPlay() },
            contentAlignment = Alignment.Center
        ) {
            TutorialPoster(video, tint)
            Box(
                Modifier.size(58.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.2f), tint)))
                    .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
        Spacer(Modifier.height(14.dp))
        OnboardingWideButton("Go beyond the Atmosphere", tint, onClick = onFinish)
    }
}

/** The video's first frame — or, until there is a tutorial video, a card
 *  that says so. */
@Composable
private fun TutorialPoster(video: MainViewModel.TutorialVideo?, tint: Color) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(lerp(tint, Color.Black, 0.35f), lerp(tint, Color.Black, 0.8f)))
        ),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (video != null && video.thumbUrl.isNotBlank()) AsyncImage(
            model = video.thumbUrl, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        ) else Text(
            "Placeholder", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

/**
 * The tutorial video at full screen. It starts exactly over its card in the
 * popup and eases out to fill the screen ([expand] 0 → 1), and eases back
 * the same way. Stellar's own video controls: tap the picture to show or
 * hide play/pause, ±10 seconds and the seek bar. The back button sits at the
 * top left, level with the camera notch row.
 */
@Composable
private fun TutorialPlayer(
    video: MainViewModel.TutorialVideo?,
    tint: Color,
    liquidGlass: Boolean,
    expand: Animatable<Float, *>,
    cardOrigin: Offset,
    cardSize: IntSize,
    onBack: () -> Unit
) {
    val context = com.mediaviewer.ui.compat.LocalContext.current
    val player = remember(video?.playlistUrl) { video?.let { FeedVideos.acquire(context, it.playlistUrl) } }
    DisposableEffect(player) {
        onDispose { if (player != null && video != null) { runCatching { player.pause() }; FeedVideos.recycle(video.playlistUrl, player) } }
    }
    var controls by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(if (video == null) 60_000L else 0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableStateOf(0L) }
    var firstFrame by remember { mutableStateOf(false) }
    // Leaving: pause first, so it isn't still playing while it shrinks.
    var leaving by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : FeedVideoListener {
            override fun onIsPlayingChanged(playing: Boolean) {}
            override fun onFirstFrame() { firstFrame = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Starts playing as it opens; the placeholder just runs a clock so the
    // controls can be tried out.
    LaunchedEffect(player) {
        if (player != null) player.play()
        playing = true
        while (true) {
            if (player != null) {
                playing = player.isPlaying
                if (player.duration > 0) durationMs = player.duration
                if (!seeking) positionMs = player.currentPosition.coerceAtLeast(0L)
            } else if (playing && !seeking) {
                positionMs = (positionMs + 200L).let { if (it >= durationMs) 0L else it }
            }
            delay(200L)
        }
    }
    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        positionMs = target
        player?.seekTo(target)
    }
    fun back() {
        if (leaving) return
        leaving = true
        player?.pause()
        playing = false
        onBack()
    }

    Box(Modifier.fillMaxSize()) {
        // Black fills in behind as it grows.
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = expand.value.coerceIn(0f, 1f))) })
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val t = expand.value.coerceIn(0f, 1f)
                    val w = (cardSize.width + (constraints.maxWidth - cardSize.width) * t).roundToInt().coerceAtLeast(1)
                    val h = (cardSize.height + (constraints.maxHeight - cardSize.height) * t).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place((cardOrigin.x * (1f - t)).roundToInt(), (cardOrigin.y * (1f - t)).roundToInt())
                    }
                }
                .graphicsLayer {
                    val r = 18.dp.toPx() * (1f - expand.value.coerceIn(0f, 1f))
                    shape = RoundedCornerShape(r)
                    clip = true
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
            contentAlignment = Alignment.Center
        ) {
            if (player != null && video != null) {
                Box(Modifier.aspectRatio(video.aspectRatio.coerceIn(0.3f, 4f)), contentAlignment = Alignment.Center) {
                    if (!firstFrame && video.thumbUrl.isNotBlank()) AsyncImage(
                        model = video.thumbUrl, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    FeedVideoView(player, Modifier.fillMaxSize())
                }
            } else {
                TutorialPoster(video, tint)
            }
        }

        // Controls appear once it has (nearly) reached full screen.
        val controlsShown = controls && !leaving && expand.value > 0.85f
        AnimatedVisibility(
            visible = controlsShown, enter = fadeIn(tween(160)), exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            VideoTransportButtons(
                liquidGlass = liquidGlass, dominantColor = tint, backdrop = null,
                isPlaying = playing,
                onPlayPause = {
                    if (player != null) { if (player.isPlaying) player.pause() else player.play() }
                    playing = !playing
                },
                onSkip = { delta -> seekTo(positionMs + delta) }
            )
        }
        AnimatedVisibility(
            visible = controlsShown, enter = fadeIn(tween(160)), exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navBarSpace)
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            VideoSeekBar(
                liquidGlass = liquidGlass, dominantColor = tint, backdrop = null,
                positionMs = if (seeking) seekPreviewMs else positionMs,
                durationMs = durationMs,
                onSeeking = { ms -> seeking = true; seekPreviewMs = ms },
                onSeekFinish = { seeking = false; seekTo(seekPreviewMs) }
            )
        }
        // Back: top left, in the row under the camera notch. Always there.
        RoundBackButton(
            liquidGlass = liquidGlass, tint = tint, backdrop = null, onClick = { back() },
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = 12.dp, top = rememberTopCutoutClearance() + 6.dp)
                .graphicsLayer { alpha = ((expand.value - 0.5f) * 2f).coerceIn(0f, 1f) },
            size = 44.dp
        )
    }
}
