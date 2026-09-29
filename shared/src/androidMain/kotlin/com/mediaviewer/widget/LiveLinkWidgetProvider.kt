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

        /** Bitmap sizing: this is capped to a small fixed pixel budget
         *  regardless of the widget's actual on-screen size — see the
         *  comment on the RemoteViews.setImageViewBitmap call below for why
         *  that's load-bearing and not just an optimization. */
        private const val BUBBLE_BITMAP_MAX_PX = 200

        private suspend fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            try {
                val prefs = PreferencesManager(context.applicationContext)
                val state = prefs.liveLinkState.first()
                val avatarUrl = prefs.selfAvatarUrlCache.first()
                val tint = avatarUrl?.let { fetchDominantColor(context, it) }
                val tintArgb = tint?.let {
                    Color.argb(235, (it.red * 255).roundToInt(), (it.green * 255).roundToInt(), (it.blue * 255).roundToInt())
                } ?: Color.argb(235, 42, 42, 46) // matches fetchDominantColor's own neutral fallback

                val options = mgr.getAppWidgetOptions(widgetId)
                val views = RemoteViews(context.packageName, R.layout.widget_live_link)

                // Bug fix: this used to size the bitmap to the widget's
                // actual pixel dimensions (reading MIN_WIDTH/MIN_HEIGHT from
                // AppWidgetManager and converting dp->px at the device's real
                // density). At this widget's declared max resize (300dp), on
                // a high-density phone that's 900-1000+ px square in
                // ARGB_8888 — several MB — and RemoteViews.setImageViewBitmap
                // ships the bitmap through a Binder IPC call to the launcher
                // process, which enforces a ~1MB transaction buffer. Past
                // that, AppWidgetManager.updateAppWidget throws
                // TransactionTooLargeException — a real crash, not a
                // rendering glitch — and it happens on EVERY update once the
                // widget's big enough, including the one triggered by
                // tapping a button, which is exactly what looked like "the
                // buttons don't work, they give an error." Fixed by keeping
                // the bitmap itself tiny and fixed-size (well under any
                // Binder limit) and letting the ImageView's fitXY scaling do
                // the enlarging — free, GPU-side, and a rounded-rect/solid-
                // fill shape loses nothing visually by being upscaled.
                val aspect = run {
                    val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110).coerceAtLeast(1)
                    val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).coerceAtLeast(1)
                    minW.toFloat() / minH.toFloat()
                }
                val (bitmapW, bitmapH) = if (aspect >= 1f) {
                    BUBBLE_BITMAP_MAX_PX to (BUBBLE_BITMAP_MAX_PX / aspect).roundToInt().coerceAtLeast(1)
                } else {
                    (BUBBLE_BITMAP_MAX_PX * aspect).roundToInt().coerceAtLeast(1) to BUBBLE_BITMAP_MAX_PX
                }
                views.setImageViewBitmap(R.id.widget_bubble_bg, buildBubbleBitmap(bitmapW, bitmapH, tintArgb))

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
            } catch (e: Exception) {
                // Defensive: a widget update can run in a freshly spun-up
                // process with no Activity ever having started (e.g. the
                // app was fully killed and the launcher/WorkManager invoked
                // this directly) — MainActivity's installCrashHandler never
                // ran there, so an uncaught exception here would surface as
                // a bare system "app has stopped" with no useful context.
                // Logging and swallowing it means a button tap fails
                // silently (the widget just doesn't visibly update) rather
                // than crashing — worse UX for that one tap, but that's
                // clearly preferable to a crash, and the Log.e leaves a
                // trace to actually debug from.
                android.util.Log.e("LiveLinkWidget", "updateWidget failed", e)
            }
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
         *  this app's in-app LiquidGlassSurface rim treatment. Radius is a
         *  more moderate 22% of the short side (was 30% — too extreme once
         *  combined with the fixed small canvas this now always draws at;
         *  at this size 30% started reading as a distorted blob rather than
         *  a rounded rect). */
        private fun buildBubbleBitmap(widthPx: Int, heightPx: Int, argbColor: Int): Bitmap {
            val w = widthPx.coerceAtLeast(1)
            val h = heightPx.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val radius = min(w, h) * 0.22f
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argbColor }
            val strokeWidth = (min(w, h) * 0.025f).coerceAtLeast(1f)
            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(90, 255, 255, 255)
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
            }
            val inset = strokeWidth / 2f
            val rect = RectF(inset, inset, w - inset, h - inset)
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
            } catch (e: Exception) {
                // Defensive, same reasoning as updateWidget's catch: this
                // can run in a process with no crash handler installed, so
                // an uncaught exception here is a hard system crash on tap,
                // not a graceful in-app error message.
                android.util.Log.e("LiveLinkWidget", "Live Link action '$action' failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
