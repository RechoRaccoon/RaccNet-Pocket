package com.mediaviewer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import com.mediaviewer.R
import com.mediaviewer.model.LiveNowPlatform
import com.mediaviewer.ui.fetchDominantColor
import com.mediaviewer.util.LiveLinkManager
import com.mediaviewer.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** Resizable home-screen widget for the Live Link feature — see the feature
 *  request: "a resizable android widget that's a rounded bubble using the
 *  design language as the app, and its colors should reflect the user's
 *  profile". RemoteViews (the framework a widget's views actually run
 *  under, in the launcher's own process) can't run this app's normal
 *  Compose/LiquidGlassSurface UI at all, so instead of a static drawable
 *  resource, the "bubble" background is a bitmap drawn fresh (rounded rect,
 *  tinted to the account's own avatar color via the same
 *  [fetchDominantColor] the in-app Hub uses) every time the widget updates
 *  or gets resized — see [buildBubbleBitmap]. The two brand-colored action
 *  buttons (Twitch purple / YouTube red, matching the same colors already
 *  used for the Hub's Live Now cards) stay static drawables since those
 *  colors are fixed regardless of whose profile this is. */
class LiveLinkWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TWITCH = "com.mediaviewer.widget.ACTION_TWITCH"
        const val ACTION_YOUTUBE = "com.mediaviewer.widget.ACTION_YOUTUBE"
        const val ACTION_END = "com.mediaviewer.widget.ACTION_END"

        // Not tied to any Activity/ViewModel lifecycle — the widget can be
        // clicked, and the periodic worker can finish, with the app's UI
        // fully closed, so this needs its own long-lived scope.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Called by LiveLinkManager after every toggle (from the widget
         *  itself, the Hub row, or the periodic worker) so every placed
         *  instance redraws immediately instead of waiting for the next
         *  natural onUpdate. */
        fun requestUpdateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(context, LiveLinkWidgetProvider::class.java))
            if (ids.isEmpty()) return
            scope.launch { ids.forEach { updateWidget(context, mgr, it) } }
        }

        private suspend fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val prefs = PreferencesManager(context.applicationContext)
            val state = prefs.liveLinkState.first()
            val avatarUrl = prefs.selfAvatarUrlCache.first()
            val tint = avatarUrl?.let { fetchDominantColor(context, it) }
            val tintArgb = tint?.let {
                Color.argb(235, (it.red * 255).roundToInt(), (it.green * 255).roundToInt(), (it.blue * 255).roundToInt())
            } ?: Color.argb(235, 42, 42, 46) // matches fetchDominantColor's own neutral fallback

            val options = mgr.getAppWidgetOptions(widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_live_link)

            val density = context.resources.displayMetrics.density
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val widthPx = (minWidthDp * density).roundToInt().coerceAtLeast(1)
            val heightPx = (minHeightDp * density).roundToInt().coerceAtLeast(1)
            views.setImageViewBitmap(R.id.widget_bubble_bg, buildBubbleBitmap(widthPx, heightPx, tintArgb))

            val hasTwitch = !state.twitchUrl.isNullOrBlank()
            val hasYoutube = !state.youtubeUrl.isNullOrBlank()

            if (state.isLive) {
                views.setViewVisibility(R.id.btn_twitch, android.view.View.GONE)
                views.setViewVisibility(R.id.btn_youtube, android.view.View.GONE)
                views.setViewVisibility(R.id.btn_end, android.view.View.VISIBLE)
                val label = if (state.activePlatform == LiveNowPlatform.TWITCH) "End Twitch Link" else "End YouTube Link"
                views.setTextViewText(R.id.btn_end, label)
                views.setOnClickPendingIntent(R.id.btn_end, actionPendingIntent(context, widgetId, ACTION_END))
            } else {
                views.setViewVisibility(R.id.btn_end, android.view.View.GONE)
                views.setViewVisibility(R.id.btn_twitch, if (hasTwitch) android.view.View.VISIBLE else android.view.View.GONE)
                views.setViewVisibility(R.id.btn_youtube, if (hasYoutube) android.view.View.VISIBLE else android.view.View.GONE)
                if (hasTwitch) views.setOnClickPendingIntent(R.id.btn_twitch, actionPendingIntent(context, widgetId, ACTION_TWITCH))
                if (hasYoutube) views.setOnClickPendingIntent(R.id.btn_youtube, actionPendingIntent(context, widgetId, ACTION_YOUTUBE))
            }

            mgr.updateAppWidget(widgetId, views)
        }

        private fun actionPendingIntent(context: Context, widgetId: Int, action: String): android.app.PendingIntent {
            val intent = Intent(context, LiveLinkWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // Distinguishes PendingIntents per (widget, action) — without
                // this, Android would collapse them into one shared
                // PendingIntent and every widget instance's every button
                // would fire whichever action was registered last.
                data = android.net.Uri.parse("liveLinkWidget://$widgetId/$action")
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            return android.app.PendingIntent.getBroadcast(context, widgetId, intent, flags)
        }

        /** Draws the "rounded bubble" bg — a rounded rect (not a full
         *  stadium/pill; a fixed-feeling large corner radius reads as
         *  "bubble" across both small square and larger resized shapes
         *  without looking like a plain capsule button at bigger sizes)
         *  filled with the account's own profile color, plus a subtle
         *  lighter rim stroke — the closest a RemoteViews bitmap can get to
         *  this app's in-app LiquidGlassSurface rim treatment. */
        private fun buildBubbleBitmap(widthPx: Int, heightPx: Int, argbColor: Int): Bitmap {
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val radius = min(widthPx, heightPx) * 0.30f
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argbColor }
            val strokeWidth = min(widthPx, heightPx) * 0.02f
            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(90, 255, 255, 255)
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
            }
            val inset = strokeWidth / 2f
            val rect = RectF(inset, inset, widthPx - inset, heightPx - inset)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.drawRoundRect(rect, radius, radius, rimPaint)
            return bmp
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        scope.launch { appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) } }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        // Fired on resize — the bubble bitmap must be redrawn at the new
        // pixel size or it'll look stretched (fitXY) instead of resized.
        scope.launch { updateWidget(context, appWidgetManager, appWidgetId) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_TWITCH && action != ACTION_YOUTUBE && action != ACTION_END) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_TWITCH -> {
                        val url = PreferencesManager(context.applicationContext).liveTwitchUrl.first()
                        if (!url.isNullOrBlank()) LiveLinkManager.goLive(context, LiveNowPlatform.TWITCH, url)
                    }
                    ACTION_YOUTUBE -> {
                        val url = PreferencesManager(context.applicationContext).liveYoutubeUrl.first()
                        if (!url.isNullOrBlank()) LiveLinkManager.goLive(context, LiveNowPlatform.YOUTUBE, url)
                    }
                    ACTION_END -> LiveLinkManager.endLive(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
