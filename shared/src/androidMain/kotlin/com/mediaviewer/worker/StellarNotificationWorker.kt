package com.mediaviewer.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.PreferencesManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Supporter Settings → Notifications (Android): every 15 minutes (the
 * shortest WorkManager allows, and battery-friendly), checks for unread DMs
 * and new Inbox activity and posts ordinary device notifications for
 * anything new. Entirely local — no push server. Both checks go straight to
 * Bluesky's chat service / AppView with service-auth tokens (see
 * BlueskyRepository.viaService), so they cost the PDS at most one token
 * request an hour and can't get the account rate-limited.
 */
class StellarNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            check(app)
            Result.success()
        } catch (_: Exception) {
            // Offline, signed out, …: just try again next time.
            Result.success()
        }
    }

    private suspend fun check(app: Context) {
        val local = app.getSharedPreferences(LocalData.PREFS, Context.MODE_PRIVATE)
        val wantDms = local.getBoolean(LocalData.KEY_NOTIFY_DMS, false)
        val wantInbox = local.getBoolean(LocalData.KEY_NOTIFY_INBOX, false)
        if (!wantDms && !wantInbox) return
        // While Stellar is open its own badges already show all of this.
        if (StellarNotificationScheduler.appInForeground) return

        val p = PreferencesManager(app)
        var token = p.bskyAccessJwt.first().orEmpty()
        var did = p.bskyDid.first().orEmpty()
        if (token.isBlank() || did.isBlank()) return
        // Supporters only.
        val supporters = app.getSharedPreferences("stellar_supporters", Context.MODE_PRIVATE)
            .getString("dids", null)?.split(',')?.toSet() ?: emptySet()
        if (did !in supporters) return

        val repo = BlueskyRepository().apply { updateServiceUrl(p.bskyServiceUrl.first()) }
        val state = app.getSharedPreferences("stellar_notification_state", Context.MODE_PRIVATE)

        suspend fun refresh(): Boolean {
            val refreshJwt = p.bskyRefreshJwt.first().orEmpty()
            if (refreshJwt.isBlank()) return false
            val r = repo.refreshToken(refreshJwt).getOrNull() ?: return false
            p.saveBskySession(r.accessJwt, r.refreshJwt, r.did, r.handle)
            token = r.accessJwt
            did = r.did
            return true
        }
        fun authError(t: Throwable?): Boolean {
            val m = t?.message ?: return false
            return m.contains("401") || m.contains("400") || m.contains("ExpiredToken", true) || m.contains("InvalidToken", true)
        }

        if (wantDms) {
            var convos = repo.listConvos(token, did)
            if (convos.isFailure && authError(convos.exceptionOrNull()) && refresh()) convos = repo.listConvos(token, did)
            val list = convos.getOrNull().orEmpty()
            val editor = state.edit()
            val firstRun = !state.contains("dm_seeded")
            for (c in list) {
                if (c.unreadCount <= 0) continue
                val key = "dm_" + c.convoId
                if (state.getString(key, null) == c.lastActivityAt) continue
                editor.putString(key, c.lastActivityAt)
                // The very first check only records what's already there.
                if (firstRun) continue
                val name = c.member.displayName.ifBlank { c.member.handle }.ifBlank { "New message" }
                val text = c.lastMessageText.ifBlank {
                    if (c.unreadCount == 1) "Sent you a message" else "${c.unreadCount} new messages"
                }
                post(app, StellarNotificationScheduler.CHANNEL_DMS, "Messages", c.convoId.hashCode(), name, text)
            }
            editor.putBoolean("dm_seeded", true).apply()
        }

        if (wantInbox) {
            var resp = repo.listNotifications(token, did, limit = 25)
            if (resp.isFailure && authError(resp.exceptionOrNull()) && refresh()) resp = repo.listNotifications(token, did, limit = 25)
            val all = resp.getOrNull()?.notifications.orEmpty()
            val last = state.getString("inbox_last", null)
            val newest = all.maxOfOrNull { it.indexedAt }
            if (last != null) {
                val fresh = all.filter { !it.isRead && it.indexedAt > last }
                if (fresh.isNotEmpty()) {
                    val first = fresh.first()
                    val who = first.author.displayName?.ifBlank { null } ?: first.author.handle
                    val what = when (first.reason) {
                        "like", "like-via-repost" -> "liked your post"
                        "repost", "repost-via-repost" -> "reposted your post"
                        "follow" -> "followed you"
                        "mention" -> "mentioned you"
                        "reply" -> "replied to you"
                        "quote" -> "quoted your post"
                        else -> "sent you a notification"
                    }
                    val title = if (fresh.size == 1) "Stellar" else "${fresh.size} new notifications"
                    val text = if (fresh.size == 1) "$who $what" else "$who $what, and ${fresh.size - 1} more"
                    post(app, StellarNotificationScheduler.CHANNEL_INBOX, "Inbox", 73410, title, text)
                }
            }
            if (newest != null && newest != last) state.edit().putString("inbox_last", newest).apply()
        }
    }

    private fun post(app: Context, channelId: String, channelName: String, id: Int, title: String, text: String) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pending = launch?.let {
            PendingIntent.getActivity(app, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(app, channelId)
            .setSmallIcon(com.mediaviewer.R.drawable.stellar_logo_vector)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try { nm.notify(id, n) } catch (_: Exception) {}
    }
}

object StellarNotificationScheduler {
    private const val UNIQUE_WORK_NAME = "stellar_notifications"
    const val CHANNEL_DMS = "stellar_dms"
    const val CHANNEL_INBOX = "stellar_inbox"

    /** Set by the app as it comes to the front / goes to the background. */
    @Volatile var appInForeground: Boolean = false

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<StellarNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
