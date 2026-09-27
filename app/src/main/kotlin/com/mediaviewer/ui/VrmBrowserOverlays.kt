package com.mediaviewer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
        store.put(KEY, arr.toString())
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

    /** Snapshots every overlay in [ids] that has a laid-out WebView. */
    fun snapshot(ids: Collection<String>, rootW: Int, rootH: Int): List<CaptureOverlay> {
        if (rootW <= 0 || rootH <= 0) return emptyList()
        val out = ArrayList<CaptureOverlay>()
        for (id in ids) {
            val view = webViews[id] ?: continue
            val r = bounds[id] ?: continue
            if (view.width <= 0 || view.height <= 0) continue
            val bmp = runCatching {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            }.getOrNull() ?: continue
            out.add(CaptureOverlay(bmp, r.left / rootW, r.top / rootH, r.right / rootW, r.bottom / rootH))
        }
        return out
    }
}

/**
 * All of VRM mode's browser overlays, drawn over the avatar. Each one:
 * a slim tinted bar (drag handle, address — tap to edit —, reload, close)
 * above a live WebView, with a resize grip in the bottom-right corner.
 */
@Composable
fun VrmBrowserOverlays(
    overlays: List<BrowserOverlaySpec>,
    tint: Color,
    registry: BrowserOverlayRegistry,
    onChange: (BrowserOverlaySpec) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rootW = with(density) { maxWidth.toPx() }
        val rootH = with(density) { maxHeight.toPx() }
        for (spec in overlays) {
            androidx.compose.runtime.key(spec.id) {
                BrowserOverlayWindow(spec, tint, rootW, rootH, registry, onChange, onRemove)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserOverlayWindow(
    spec: BrowserOverlaySpec,
    tint: Color,
    rootW: Float,
    rootH: Float,
    registry: BrowserOverlayRegistry,
    onChange: (BrowserOverlaySpec) -> Unit,
    onRemove: (String) -> Unit
) {
    val density = LocalDensity.current
    val tap = com.mediaviewer.util.rememberHapticTap()
    // Dragging edits a local copy (smooth), committed when the finger lifts.
    var live by remember(spec.id) { mutableStateOf(spec) }
    androidx.compose.runtime.LaunchedEffect(spec) { live = spec }
    val current by rememberUpdatedState(live)
    val minW = with(density) { 140.dp.toPx() } / rootW.coerceAtLeast(1f)
    val minH = with(density) { 100.dp.toPx() } / rootH.coerceAtLeast(1f)
    var editing by remember { mutableStateOf(false) }
    var urlField by remember(spec.url) { mutableStateOf(spec.url) }
    val barColor = androidx.compose.ui.graphics.lerp(Color(0xFF101014), tint, 0.35f).copy(alpha = 0.92f)
    val shape = RoundedCornerShape(12.dp)

    Box(
        Modifier
            .offset { IntOffset((live.x * rootW).roundToInt(), (live.y * rootH).roundToInt()) }
            .size(with(density) { (live.w * rootW).toDp() }, with(density) { (live.h * rootH).toDp() })
            .clip(shape)
            .border(1.dp, tint.copy(alpha = 0.7f), shape)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(28.dp).background(barColor).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(24.dp).pointerInput(spec.id) {
                        detectDragGestures(
                            onDragEnd = { onChange(current) },
                            onDragCancel = { onChange(current) }
                        ) { change, drag ->
                            change.consume()
                            val c = current
                            live = c.copy(
                                x = (c.x + drag.x / rootW).coerceIn(-c.w + 0.1f, 0.9f),
                                y = (c.y + drag.y / rootH).coerceIn(0f, 0.95f)
                            )
                        }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.DragIndicator, contentDescription = "Move", tint = Color.White.copy(0.8f), modifier = Modifier.size(16.dp))
                }
                if (editing) {
                    BasicTextField(
                        value = urlField, onValueChange = { urlField = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrect = false),
                        keyboardActions = KeyboardActions(onGo = {
                            editing = false
                            val u = BrowserOverlayStore.normalizeUrl(urlField)
                            if (u.isNotBlank()) onChange(current.copy(url = u))
                        }),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(0.1f)).padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                } else {
                    Text(
                        spec.url.removePrefix("https://").removePrefix("http://").ifBlank { "Tap to enter a URL" },
                        color = Color.White.copy(0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { urlField = spec.url; editing = true }.padding(horizontal = 4.dp)
                    )
                }
                Box(
                    Modifier.size(24.dp).clip(CircleShape).clickable { tap(); registry.webViews[spec.id]?.reload() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White.copy(0.8f), modifier = Modifier.size(14.dp)) }
                Box(
                    Modifier.size(24.dp).clip(CircleShape).clickable { tap(); onRemove(spec.id) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, contentDescription = "Remove overlay", tint = Color.White.copy(0.8f), modifier = Modifier.size(14.dp)) }
            }
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .onGloballyPositioned { registry.bounds[spec.id] = it.boundsInRoot() }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // Transparent pages (chat/alert widgets made for
                            // OBS) show the avatar through them.
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            registry.webViews[spec.id] = this
                            if (spec.url.isNotBlank()) loadUrl(spec.url)
                            tag = spec.url
                        }
                    },
                    update = { view ->
                        if (view.tag != spec.url && spec.url.isNotBlank()) {
                            view.tag = spec.url
                            view.loadUrl(spec.url)
                        }
                    }
                )
            }
        }
        // Resize grip.
        Box(
            Modifier.align(Alignment.BottomEnd).size(26.dp)
                .clip(RoundedCornerShape(topStart = 10.dp))
                .background(barColor)
                .pointerInput(spec.id) {
                    detectDragGestures(
                        onDragEnd = { onChange(current) },
                        onDragCancel = { onChange(current) }
                    ) { change, drag ->
                        change.consume()
                        val c = current
                        live = c.copy(
                            w = (c.w + drag.x / rootW).coerceIn(minW, 1f),
                            h = (c.h + drag.y / rootH).coerceIn(minH, 1f)
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.OpenInFull, contentDescription = "Resize", tint = Color.White.copy(0.8f),
                modifier = Modifier.size(13.dp).rotate(90f))
        }
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
