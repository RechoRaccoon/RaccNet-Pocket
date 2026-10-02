package com.mediaviewer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** The system WebView (already on every Android phone — nothing bundled). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun PlatformBrowserView(state: BrowserState, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val existing = state.native as? WebView
            val web = existing ?: WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptCookie(true)
                fun sync(view: WebView) {
                    state.canGoBack = view.canGoBack()
                    state.canGoForward = view.canGoForward()
                    view.url?.let { state.url = it }
                    view.title?.let { state.title = it }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Only web pages load here; app links (intent:, market:, tel:…) are left alone.
                        val scheme = request.url.scheme?.lowercase()
                        return scheme != "http" && scheme != "https"
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        state.loading = true
                        sync(view)
                    }
                    override fun onPageFinished(view: WebView, url: String?) {
                        state.loading = false
                        sync(view)
                    }
                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        sync(view)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) {
                        state.title = title.orEmpty()
                    }
                }
                loadUrl(state.pendingUrl)
            }
            // Shown somewhere else before (Search was closed and reopened).
            (web.parent as? ViewGroup)?.removeView(web)
            web.onResume()
            state.native = web
            state.command = { cmd ->
                when {
                    cmd.startsWith("load:") -> web.loadUrl(cmd.removePrefix("load:"))
                    cmd == "back" -> if (web.canGoBack()) web.goBack()
                    cmd == "forward" -> if (web.canGoForward()) web.goForward()
                    cmd == "reload" -> web.reload()
                }
            }
            state.destroy = {
                web.stopLoading()
                (web.parent as? ViewGroup)?.removeView(web)
                web.loadUrl("about:blank")
                web.destroy()
            }
            web
        },
        // Off screen: timers and media in the page are paused.
        onRelease = { web -> if (state.native === web) web.onPause() }
    )
}
