package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.ui.theme.OffBlack
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.rememberHapticTap
import kotlin.math.roundToInt

/**
 * One web page: where it is, what it can do, and the buttons' actions. The
 * platform's own web view (Android's WebView, iOS's WKWebView — both
 * already part of the system, so they add nothing to the app's size) is
 * created the first time the page is shown and kept in [native], so the
 * page survives being hidden and shown again without reloading.
 */
class BrowserState(initialUrl: String) {
    /** The address the page is on (kept current by the web view). */
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var loading by mutableStateOf(false)
    /** A popped-out window smaller than a phone page: how much the whole
     *  page is shrunk to fit it (1 = full size). */
    var zoom by mutableStateOf(1f)

    /** The address to open when the web view is first created. */
    var pendingUrl: String = initialUrl
    /** The platform web view, once created. */
    var native: Any? = null
    /** Set by the platform view: runs "load:<url>", "back", "forward", "reload". */
    var command: ((String) -> Unit)? = null
    /** Set by the platform view: tears the web view down completely. */
    var destroy: (() -> Unit)? = null

    fun load(address: String) {
        pendingUrl = address
        url = address
        command?.invoke("load:$address")
    }
    fun back() { command?.invoke("back") }
    fun forward() { command?.invoke("forward") }
    fun reload() { command?.invoke("reload") }

    /** Stops the page and frees the web view — nothing is left running. */
    fun dispose() {
        runCatching { destroy?.invoke() }
        destroy = null
        command = null
        native = null
    }

    /** A short name for the page (its title, else its host). */
    val label: String get() = title.ifBlank { runCatching { com.mediaviewer.platform.uriHost(url) }.getOrNull().orEmpty() }.ifBlank { url }
}

/** The page in Search → Web Browser. Kept while the app runs, so leaving
 *  Search and coming back finds it where it was. */
object BrowserHome {
    private var instance: BrowserState? = null
    val state: BrowserState
        get() = instance ?: BrowserState(LocalData.searchEngine.home).also { instance = it }

    /** Leaving Search: the page is stopped and its web view destroyed. */
    fun close() {
        instance?.dispose()
        instance = null
    }
}

/** The live web page for [state]. */
@Composable
expect fun PlatformBrowserView(state: BrowserState, modifier: Modifier = Modifier)

@Composable
private fun WindowButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    Box(
        Modifier.size(30.dp).clip(CircleShape).clickable(enabled = enabled) { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(16.dp))
    }
}

/**
 * Every popped-out web page, floating over whatever page of the app is
 * showing (the same idea as VRM mode's browser windows): a slim tinted title
 * bar — drag it to move the window; back, forward, reload and close — above
 * the live page, with a resize grip in the bottom-right corner. Each window
 * only redraws itself. Closing one destroys its web view outright.
 */
@Composable
fun BrowserPopoutLayer(tint: Color, modifier: Modifier = Modifier) {
    val popouts = LocalOverlays.popouts
    if (popouts.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }
        val minW = with(density) { 180.dp.toPx() }
        val minH = with(density) { 150.dp.toPx() }
        popouts.forEach { p ->
            key(p.id) {
                val shape = RoundedCornerShape(16.dp)
                val wPx = (p.w * areaW).coerceIn(minW, areaW)
                val hPx = (p.h * areaH).coerceIn(minH, areaH)
                Column(
                    Modifier
                        .zIndex(p.z)
                        .offset { IntOffset((p.x * areaW).roundToInt(), (p.y * areaH).roundToInt()) }
                        .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                        .shadow(14.dp, shape)
                        .clip(shape)
                        .background(OffBlack)
                        .border(1.dp, lerp(tint, Color.White, 0.3f).copy(alpha = 0.7f), shape)
                ) {
                    // Title bar: drag to move.
                    Row(
                        Modifier.fillMaxWidth().height(34.dp)
                            .background(lerp(OffBlack, tint, 0.38f))
                            .pointerInput(p.id, areaW, areaH) {
                                detectDragGestures(
                                    onDragStart = {
                                        // Touched: comes to the front.
                                        LocalOverlays.bringToFront(p)
                                    }
                                ) { change, drag ->
                                    change.consume()
                                    val maxX = ((areaW - 60f) / areaW).coerceAtLeast(0f)
                                    val maxY = ((areaH - 40f) / areaH).coerceAtLeast(0f)
                                    p.x = (p.x + drag.x / areaW).coerceIn(-(p.w - 0.15f).coerceAtLeast(0f), maxX)
                                    p.y = (p.y + drag.y / areaH).coerceIn(0f, maxY)
                                }
                            }
                            .padding(start = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WindowButton(Icons.AutoMirrored.Filled.ArrowBack, "Previous page", p.state.canGoBack) { p.state.back() }
                        WindowButton(Icons.AutoMirrored.Filled.ArrowForward, "Next page", p.state.canGoForward) { p.state.forward() }
                        WindowButton(Icons.Default.Refresh, "Refresh") { p.state.reload() }
                        Text(
                            p.state.label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                        )
                        WindowButton(Icons.Default.Close, "Close") { LocalOverlays.closePopout(p) }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        // The page is always laid out a full phone-page wide and
                        // the WHOLE page is scaled to the window, so making the
                        // window smaller shrinks the page instead of cutting it off.
                        val pageW = with(density) { 400.dp.toPx() }
                        val scale = (wPx / pageW).coerceIn(0.2f, 1f)
                        if (com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.ANDROID) {
                            Box(
                                Modifier.fillMaxSize().layout { measurable, constraints ->
                                    val w = (constraints.maxWidth / scale).roundToInt()
                                    val h = (constraints.maxHeight / scale).roundToInt()
                                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                                    layout(constraints.maxWidth, constraints.maxHeight) {
                                        placeable.placeWithLayer(0, 0) {
                                            scaleX = scale; scaleY = scale
                                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                        }
                                    }
                                }
                            ) { PlatformBrowserView(p.state, Modifier.fillMaxSize()) }
                        } else {
                            // iOS: the web view zooms its own page out.
                            androidx.compose.runtime.SideEffect { p.state.zoom = scale }
                            PlatformBrowserView(p.state, Modifier.fillMaxSize())
                        }
                        // Resize grip.
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(30.dp)
                                .pointerInput(p.id, areaW, areaH) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        p.w = (p.w + drag.x / areaW).coerceIn(minW / areaW, 1f)
                                        p.h = (p.h + drag.y / areaH).coerceIn(minH / areaH, 1f)
                                    }
                                },
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            Box(
                                Modifier.padding(4.dp).size(14.dp).clip(RoundedCornerShape(topStart = 12.dp, bottomEnd = 10.dp))
                                    .background(lerp(tint, Color.White, 0.35f).copy(alpha = 0.85f))
                            )
                        }
                    }
                }
            }
        }
    }
}


/** Search → Web Browser's interaction bar: Previous Page, Next Page,
 *  Refresh and Popout, in the same pill as the other tabs' bar. */
@Composable
fun BrowserInteractionBar(
    state: BrowserState, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    onPopout: () -> Unit, modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(26.dp)
    val pillHeight = if (liquidGlass) 44.dp else 36.dp
    @Composable
    fun Button(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled) { tap(); onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(20.dp))
        }
    }
    @Composable
    fun BarContent() {
        Row(
            Modifier.height(pillHeight).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
        ) {
            Button(Icons.AutoMirrored.Filled.ArrowBack, "Previous page", state.canGoBack) { state.back() }
            Button(Icons.AutoMirrored.Filled.ArrowForward, "Next page", state.canGoForward) { state.forward() }
            Button(Icons.Default.Refresh, "Refresh", true) { state.reload() }
            Button(androidx.compose.material.icons.Icons.AutoMirrored.Filled.OpenInNew, "Popout", true) { onPopout() }
        }
    }
    Box(
        modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navBarSpace)
            .height(if (liquidGlass) 60.dp else 52.dp).fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        if (liquidGlass) {
            LiquidGlassSurface(modifier = Modifier.height(pillHeight), shape = shape, tint = tint, backdrop = backdrop) { BarContent() }
        } else {
            Box(Modifier.height(pillHeight).clip(shape).background(Color.Black.copy(alpha = 0.7f))) { BarContent() }
        }
    }
}
