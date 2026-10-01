package com.mediaviewer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * One floating browser window in VRM mode (chat, alerts, any stream
 * widget). Position/size are fractions of the VRM screen so they survive
 * rotation. [inCapture] = also drawn into photos, recordings and streams;
 * off by default, so normally only the person sees them.
 */
data class BrowserOverlaySpec(
    val id: String,
    val url: String,
    val x: Float = 0.08f,
    val y: Float = 0.18f,
    val w: Float = 0.6f,
    val h: Float = 0.3f,
    val inCapture: Boolean = false
)

object BrowserOverlayStore {
    private const val KEY = "browser_overlays"
    private const val KEY_ENABLED = "browser_overlays_enabled"

    fun enabled(store: com.mediaviewer.util.VrmSettingsStore) = store.bool(KEY_ENABLED, false)
    fun setEnabled(store: com.mediaviewer.util.VrmSettingsStore, value: Boolean) = store.put(KEY_ENABLED, value)

    fun load(store: com.mediaviewer.util.VrmSettingsStore): List<BrowserOverlaySpec> = runCatching {
        val arr = JSONArray(store.string(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            BrowserOverlaySpec(
                id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                url = o.optString("url"),
                x = o.optDouble("x", 0.08).toFloat(),
                y = o.optDouble("y", 0.18).toFloat(),
                w = o.optDouble("w", 0.6).toFloat(),
                h = o.optDouble("h", 0.3).toFloat(),
                inCapture = o.optBoolean("inCapture", false)
            )
        }
    }.getOrDefault(emptyList())

    fun save(store: com.mediaviewer.util.VrmSettingsStore, list: List<BrowserOverlaySpec>) {
        val arr = JSONArray()
        for (s in list) arr.put(JSONObject().apply {
            put("id", s.id); put("url", s.url)
            put("x", s.x.toDouble()); put("y", s.y.toDouble()); put("w", s.w.toDouble()); put("h", s.h.toDouble())
            put("inCapture", s.inCapture)
        })
        // Written straight to disk: a later crash or the app being swiped
        // away can't lose where the windows were.
        store.putNow(KEY, arr.toString())
    }

    /** "twitch.tv/popout/x/chat" → "https://twitch.tv/popout/x/chat". */
    fun normalizeUrl(input: String): String {
        val t = input.trim()
        if (t.isBlank()) return t
        return if (t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("about:")) t else "https://$t"
    }
}

/** Live WebViews and where their page area sits on screen (root px), for
 *  capture snapshots. Main thread only. */
class BrowserOverlayRegistry {
    val webViews = HashMap<String, WebView>()
    val bounds = HashMap<String, Rect>()

    // ── Hardware snapshots ──
    // A page is drawn the same way it is drawn on screen — by the GPU, into
    // an offscreen surface — and read back from there. (Drawing a WebView
    // into a plain bitmap uses its software renderer, which leaves complex
    // pages blank: the capture went white after navigating.)
    private class Grabber(val reader: android.media.ImageReader) {
        val pool = arrayOfNulls<Bitmap>(3)
        var next = 0
        /** When the frame now waiting in [reader] was drawn (uptime ms). */
        var drawnAt = 0L
    }
    private val grabbers = HashMap<String, Grabber>()
    private val lastGood = HashMap<String, Bitmap>()
    private var hardwareBroken = false

    private fun hardwareSnapshot(id: String, view: WebView): Bitmap? {
        if (hardwareBroken) return null
        val w = view.width
        val h = view.height
        return try {
            val existing = grabbers[id]
            val g: Grabber = if (existing != null && existing.reader.width == w && existing.reader.height == h) existing else {
                existing?.reader?.close()
                lastGood.remove(id)
                Grabber(android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)).also { grabbers[id] = it }
            }
            // Read back the frame drawn on the previous call (drawing is
            // finished by the render thread a moment after it's posted)…
            var out: Bitmap? = null
            val now = android.os.SystemClock.uptimeMillis()
            // An old waiting frame (snapshots weren't running) is thrown away.
            val waitingIsFresh = now - g.drawnAt <= 500L
            g.drawnAt = now
            if (!waitingIsFresh) { g.reader.acquireLatestImage()?.close(); lastGood.remove(id) }
            else g.reader.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val buffer = plane.buffer
                val slot = g.next
                g.next = (slot + 1) % g.pool.size
                val bmp = g.pool[slot]?.takeIf { !it.isRecycled && it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { g.pool[slot] = it }
                if (rowStride == w * 4) {
                    buffer.rewind()
                    bmp.copyPixelsFromBuffer(buffer)
                } else {
                    // Padded rows: copy through a stride-wide bitmap.
                    val wide = Bitmap.createBitmap(rowStride / 4, h, Bitmap.Config.ARGB_8888)
                    buffer.rewind()
                    wide.copyPixelsFromBuffer(buffer)
                    android.graphics.Canvas(bmp).apply {
                        drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                        drawBitmap(wide, 0f, 0f, null)
                    }
                    wide.recycle()
                }
                out = bmp
            }
            // …and draw the next one.
            val surface = g.reader.surface
            val canvas = surface.lockHardwareCanvas()
            try {
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                view.draw(canvas)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            out?.let { lastGood[id] = it }
            out
        } catch (e: Throwable) {
            android.util.Log.e("VrmOverlays", "Hardware overlay snapshot failed; using software snapshots", e)
            hardwareBroken = true
            lastGood.clear()
            null
        }
    }

    /** Frees the snapshot surfaces (leaving the page). */
    fun release() {
        grabbers.values.forEach { g -> runCatching { g.reader.close() } }
        grabbers.clear()
        lastGood.clear()
    }

    /** Snapshots every overlay in [ids] that has a laid-out WebView. */
    fun snapshot(ids: Collection<String>, rootW: Int, rootH: Int): List<CaptureOverlay> {
        if (rootW <= 0 || rootH <= 0) return emptyList()
        val out = ArrayList<CaptureOverlay>()
        for (id in ids) {
            val view = webViews[id] ?: continue
            val r = bounds[id] ?: continue
            if (view.width <= 0 || view.height <= 0) continue
            val bmp = hardwareSnapshot(id, view) ?: lastGood[id] ?: runCatching {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            }.getOrNull() ?: continue
            out.add(CaptureOverlay(bmp, r.left / rootW, r.top / rootH, r.right / rootW, r.bottom / rootH))
        }
        return out
    }
}

/**
 * All of VRM mode's (and the Camera page's) browser windows, floating over
 * the page. Each one: a slim tinted title bar (drag anywhere on it to move;
 * address — tap to edit —, reload, close) above a live web page, with a
 * resize grip in the bottom-right corner.
 *
 * Performance: every window is its own lightweight system window (a
 * [Popup]), not part of the VRM screen's own drawing. A chat or alert
 * widget animates constantly; inside the VRM window each of its frames
 * forced the whole screen — the avatar's render, the blurred glass buttons,
 * everything — to be recomposited, at the panel's full 90/120 Hz. As
 * separate windows, a page only ever redraws itself.
 *
 * The page itself is laid out at a fixed [PAGE_WIDTH_DP]-wide viewport and
 * scaled to the window, so the WHOLE page is always visible: making the
 * window smaller shrinks the page instead of cutting it off, and changing
 * its shape just makes the page taller/shorter.
 *
 * [hidden] parks the windows off-screen (pages keep their state, paused)
 * while one of the page's own popups is open, since system windows would
 * otherwise sit on top of it.
 */
@Composable
fun VrmBrowserOverlays(
    overlays: List<BrowserOverlaySpec>,
    tint: Color,
    registry: BrowserOverlayRegistry,
    onChange: (BrowserOverlaySpec) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    /** False while recording/streaming: parked pages keep running. */
    pauseWhenHidden: Boolean = true
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rootW = with(density) { maxWidth.toPx() }
        val rootH = with(density) { maxHeight.toPx() }
        for (spec in overlays) {
            androidx.compose.runtime.key(spec.id) {
                BrowserOverlayWindow(spec, tint, rootW, rootH, registry, onChange, onRemove, hidden, pauseWhenHidden)
            }
        }
    }
}

/** The CSS width every overlay page is laid out at (then scaled to fit). */
private const val PAGE_WIDTH_DP = 360f
private val BAR_HEIGHT = 30.dp
private val GRIP_SIZE = 28.dp

/** Tells the window when the page was touched (so it can check whether a
 *  text field got focus and the window should take the keyboard). */
private class OverlayPageContainer(context: android.content.Context, val onPageTouched: () -> Unit) :
    android.widget.FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) onPageTouched()
        return false
    }
}

private const val FOCUSED_EDITABLE_JS =
    "(function(){var e=document.activeElement;if(!e)return false;var t=(e.tagName||'').toUpperCase();" +
        "return e.isContentEditable||t==='INPUT'||t==='TEXTAREA'||t==='IFRAME';})()"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserOverlayWindow(
    spec: BrowserOverlaySpec,
    tint: Color,
    rootW: Float,
    rootH: Float,
    registry: BrowserOverlayRegistry,
    onChange: (BrowserOverlaySpec) -> Unit,
    onRemove: (String) -> Unit,
    hidden: Boolean,
    pauseWhenHidden: Boolean = true
) {
    val density = LocalDensity.current
    val tap = com.mediaviewer.util.rememberHapticTap()
    // Moving/resizing edits a local copy (smooth), committed when the finger lifts.
    var live by remember(spec.id) { mutableStateOf(spec) }
    androidx.compose.runtime.LaunchedEffect(spec) { live = spec }
    val current by rememberUpdatedState(live)
    val latestOnChange by rememberUpdatedState(onChange)
    val minW = with(density) { 140.dp.toPx() } / rootW.coerceAtLeast(1f)
    val minH = with(density) { 100.dp.toPx() } / rootH.coerceAtLeast(1f)
    var editing by remember { mutableStateOf(false) }
    // The page has a text field focused: the window takes the keyboard
    // until you tap outside it (or press back).
    var typing by remember { mutableStateOf(false) }
    var urlField by remember(spec.url) { mutableStateOf(spec.url) }
    val barColor = androidx.compose.ui.graphics.lerp(Color(0xFF101014), tint, 0.35f).copy(alpha = 0.94f)
    val shape = RoundedCornerShape(12.dp)
    val barPx = with(density) { BAR_HEIGHT.toPx() }
    val gripPx = with(density) { GRIP_SIZE.toPx() }

    val winX = (live.x * rootW).roundToInt()
    val winY = (live.y * rootH).roundToInt()
    val winW = (live.w * rootW).roundToInt().coerceAtLeast(1)
    val winH = (live.h * rootH).roundToInt().coerceAtLeast(1)
    // Page geometry: the committed size decides the page's layout height
    // (so it doesn't re-layout on every resize frame); the live width
    // decides its scale (so it grows/shrinks smoothly while resizing).
    val virtualW = (PAGE_WIDTH_DP * density.density).roundToInt()
    val committedScale = ((spec.w * rootW) / virtualW).coerceAtLeast(0.05f)
    val virtualH = (((spec.h * rootH) - barPx) / committedScale).roundToInt().coerceAtLeast(1)
    val liveScale = (winW / virtualW.toFloat()).coerceAtLeast(0.05f)

    // Where the page sits on the VRM screen (for capture snapshots).
    androidx.compose.runtime.SideEffect {
        registry.bounds[spec.id] = Rect(winX.toFloat(), winY + barPx, (winX + winW).toFloat(), (winY + winH).toFloat())
    }

    val focusable = editing || typing
    Popup(
        alignment = Alignment.TopStart,
        offset = if (hidden) IntOffset(-100_000, -100_000) else IntOffset(winX, winY),
        onDismissRequest = { editing = false; typing = false },
        properties = PopupProperties(
            focusable = focusable,
            dismissOnBackPress = true,
            dismissOnClickOutside = focusable,
            clippingEnabled = false
        )
    ) {
        Box(
            Modifier
                .size(with(density) { winW.toDp() }, with(density) { winH.toDp() })
                .clip(shape)
                .border(if (typing) 1.5.dp else 1.dp, tint.copy(alpha = if (typing) 1f else 0.7f), shape)
                // Move (press anywhere on the title bar and drag) and resize
                // (the bottom-right grip) — handled here, on the window's
                // root, in screen coordinates: the window itself moves under
                // the finger while dragging, so positions relative to it
                // would chase their own tail. Buttons on the bar still get
                // plain taps; a drag only takes over past the touch slop.
                // Resize: the bottom-right grip. Resizing keeps the window's
                // top-left fixed, so the window's own coordinates stay
                // stable under the finger. (Moving is handled by the title
                // bar below, in real screen coordinates.)
                .pointerInput(spec.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val inGrip = down.position.x >= size.width - gripPx && down.position.y >= size.height - gripPx
                        if (!inGrip) return@awaitEachGesture
                        var last = down.position
                        var travelled = 0f
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val now = ch.position
                            val d = now - last
                            last = now
                            if (!dragging) {
                                travelled += d.getDistance()
                                if (travelled < viewConfiguration.touchSlop) continue
                                dragging = true
                                editing = false
                            }
                            ch.consume()
                            val c = current
                            live = c.copy(
                                w = (c.w + d.x / rootW).coerceIn(minW, 1f),
                                h = (c.h + d.y / rootH).coerceIn(minH, 1f)
                            )
                        }
                        if (dragging) latestOnChange(current)
                    }
                }
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(BAR_HEIGHT).background(barColor)) {
                // Move: press anywhere on the title bar (except its buttons)
                // and drag. This used to be measured in the window's own
                // coordinates — but the window moves under the finger, and
                // its new position lands a frame or two after each touch,
                // so every move was measured against a stale position and
                // the window jittered/flew about. A plain Android view
                // behind the bar reads the finger's true position on the
                // SCREEN (rawX/rawY), and the window is placed from where
                // the drag started + how far the finger has gone — no
                // build-up of errors. A tap (no drag) edits the address.
                val slopPx = with(density) { 8.dp.toPx() }
                val latestRootW by rememberUpdatedState(rootW)
                val latestRootH by rememberUpdatedState(rootH)
                AndroidView(
                    modifier = Modifier.matchParentSize(),
                    factory = { ctx ->
                        android.view.View(ctx).apply {
                            var startX = 0f; var startY = 0f
                            var startSpec = current
                            var dragging = false
                            setOnTouchListener { v, ev ->
                                when (ev.actionMasked) {
                                    android.view.MotionEvent.ACTION_DOWN -> {
                                        startX = ev.rawX; startY = ev.rawY
                                        startSpec = current; dragging = false
                                    }
                                    android.view.MotionEvent.ACTION_MOVE -> {
                                        val dx = ev.rawX - startX; val dy = ev.rawY - startY
                                        if (!dragging && kotlin.math.hypot(dx, dy) >= slopPx) {
                                            dragging = true; editing = false
                                            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                        if (dragging) {
                                            val w = latestRootW.coerceAtLeast(1f); val h = latestRootH.coerceAtLeast(1f)
                                            live = startSpec.copy(
                                                x = (startSpec.x + dx / w).coerceIn(-startSpec.w + 0.1f, 0.9f),
                                                y = (startSpec.y + dy / h).coerceIn(0f, 0.95f)
                                            )
                                        }
                                    }
                                    android.view.MotionEvent.ACTION_UP -> {
                                        if (dragging) latestOnChange(current)
                                        else { urlField = spec.url; editing = true }
                                        dragging = false
                                    }
                                    android.view.MotionEvent.ACTION_CANCEL -> {
                                        if (dragging) latestOnChange(current)
                                        dragging = false
                                    }
                                }
                                true
                            }
                        }
                    }
                )
                Row(
                    Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.DragIndicator, contentDescription = "Move",
                        tint = Color.White.copy(0.8f), modifier = Modifier.padding(horizontal = 4.dp).size(16.dp)
                    )
                    if (editing) {
                        BasicTextField(
                            value = urlField, onValueChange = { urlField = it }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrect = false),
                            keyboardActions = KeyboardActions(onGo = {
                                editing = false
                                val u = BrowserOverlayStore.normalizeUrl(urlField)
                                if (u.isNotBlank()) latestOnChange(current.copy(url = u))
                            }),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(0.1f)).padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    } else {
                        // Not clickable itself: taps (and drags) fall through
                        // to the bar's drag view behind it — a tap edits.
                        Text(
                            spec.url.removePrefix("https://").removePrefix("http://").ifBlank { "Tap to enter a URL" },
                            color = Color.White.copy(0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                    }
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).clickable { tap(); registry.webViews[spec.id]?.reload() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White.copy(0.8f), modifier = Modifier.size(14.dp)) }
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).clickable { tap(); onRemove(spec.id) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Close, contentDescription = "Remove overlay", tint = Color.White.copy(0.8f), modifier = Modifier.size(14.dp)) }
                }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val web = WebView(ctx).apply {
                                // Transparent pages (chat/alert widgets made for
                                // OBS) show the avatar through them.
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                // Scaled by its parent below, never by the page.
                                settings.setSupportZoom(false)
                                webViewClient = WebViewClient()
                                webChromeClient = WebChromeClient()
                                pivotX = 0f
                                pivotY = 0f
                                registry.webViews[spec.id] = this
                                if (spec.url.isNotBlank()) loadUrl(spec.url)
                                tag = spec.url
                            }
                            OverlayPageContainer(ctx) {
                                // A tap in the page: if it landed in a text
                                // field, let this window take the keyboard.
                                web.postDelayed({
                                    runCatching {
                                        web.evaluateJavascript(FOCUSED_EDITABLE_JS) { result ->
                                            if (result == "true") typing = true
                                        }
                                    }
                                }, 120)
                            }.apply {
                                clipChildren = true
                                addView(web, android.widget.FrameLayout.LayoutParams(virtualW, virtualH))
                            }
                        },
                        update = { container ->
                            val web = container.getChildAt(0) as? WebView ?: return@AndroidView
                            val lp = web.layoutParams
                            if (lp.width != virtualW || lp.height != virtualH) {
                                lp.width = virtualW; lp.height = virtualH
                                web.layoutParams = lp
                            }
                            if (web.scaleX != liveScale) { web.scaleX = liveScale; web.scaleY = liveScale }
                            if (web.tag != spec.url && spec.url.isNotBlank()) {
                                web.tag = spec.url
                                web.loadUrl(spec.url)
                            }
                        }
                    )
                }
            }
            // Resize grip (the gesture itself is handled by the root above).
            Box(
                Modifier.align(Alignment.BottomEnd).size(GRIP_SIZE)
                    .clip(RoundedCornerShape(topStart = 10.dp))
                    .background(barColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.OpenInFull, contentDescription = "Resize", tint = Color.White.copy(0.8f),
                    modifier = Modifier.size(13.dp).rotate(90f))
            }
        }
        // Typing: once the window can take focus, bring the keyboard up for
        // the field that was tapped.
        androidx.compose.runtime.LaunchedEffect(typing) {
            if (!typing) return@LaunchedEffect
            kotlinx.coroutines.delay(150)
            val web = registry.webViews[spec.id] ?: return@LaunchedEffect
            runCatching {
                web.requestFocus()
                val imm = web.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    // Parked off-screen: pause the page (timers, animations, media).
    androidx.compose.runtime.LaunchedEffect(hidden, pauseWhenHidden) {
        val web = registry.webViews[spec.id] ?: return@LaunchedEffect
        runCatching { if (hidden && pauseWhenHidden) web.onPause() else web.onResume() }
    }
    DisposableEffect(spec.id) {
        onDispose {
            registry.webViews.remove(spec.id)?.let { runCatching { it.stopLoading(); it.destroy() } }
            registry.bounds.remove(spec.id)
        }
    }
}

/** Compact editor for the overlay list, shown inside VRM Settings. */
@Composable
internal fun VrmBrowserOverlaySettings(
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    overlays: List<BrowserOverlaySpec>,
    tint: Color,
    onAdd: (String) -> Unit,
    onUpdate: (BrowserOverlaySpec) -> Unit,
    onRemove: (String) -> Unit,
    toggleRow: @Composable (label: String, checked: Boolean, hint: String?, onToggle: (Boolean) -> Unit) -> Unit
) {
    val tap = com.mediaviewer.util.rememberHapticTap()
    toggleRow("Browser overlays", enabled,
        "Floating web pages (chat, alerts…) over your avatar. Only you see them unless one is set to show in captures.") { onToggleEnabled(it) }
    if (!enabled) return
    for (o in overlays) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                o.url.removePrefix("https://").removePrefix("http://").ifBlank { "(no URL)" },
                color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            val on = o.inCapture
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (on) tint else Color.White.copy(0.1f))
                    .clickable { tap(); onUpdate(o.copy(inCapture = !on)) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text(if (on) "In captures" else "Hidden in captures", color = Color.White, fontSize = 11.sp, maxLines = 1) }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(26.dp).clip(CircleShape).clickable { tap(); onRemove(o.id) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White.copy(0.6f), modifier = Modifier.size(15.dp)) }
        }
    }
    var newUrl by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = newUrl, onValueChange = { newUrl = it }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(tint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrect = false),
            keyboardActions = KeyboardActions(onDone = { if (newUrl.isNotBlank()) { onAdd(newUrl); newUrl = "" } }),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(0.08f))
                .border(1.dp, tint.copy(0.35f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (newUrl.isEmpty()) Text("Add a page: URL", color = Color.White.copy(0.35f), fontSize = 13.sp, maxLines = 1)
                    inner()
                }
            }
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(if (newUrl.isNotBlank()) tint else Color.White.copy(0.1f))
                .clickable(enabled = newUrl.isNotBlank()) { tap(); onAdd(newUrl); newUrl = "" }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text("Add", color = Color.White, fontSize = 13.sp) }
    }
}
