package com.famviva.camara.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.famviva.camara.MainActivity
import com.famviva.camara.R

object Notifications {
    private const val CHANNEL_ID = "events"
    private const val NOTIF_ID = 1
    private const val NOTIF_ID_HEALTH = 2
    private const val NOTIF_ID_DIGEST = 3

    /** Intent extra MainActivity reads to deep-link to a screen when a notification is tapped. */
    const val EXTRA_DEST = "dest"
    /** Deep-link value: open the live view. */
    const val DEST_LIVE = "live"
    /** Deep-link route to a clip's player (the actual triggering evidence). */
    fun clipRoute(clipId: String) = "player/$clipId"

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notif_channel_desc) }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun openAppIntent(context: Context, dest: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (dest != null) intent.putExtra(EXTRA_DEST, dest)
        // Distinct request code per destination so the "open live" and plain-open PendingIntents
        // don't collide (FLAG_UPDATE_CURRENT would otherwise overwrite the extras of one another).
        return PendingIntent.getActivity(
            context, dest?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * New-clip alert. When we could fetch the newest clip's preview it becomes a BigPicture
     * notification (photo + thumbnail). Tapping it opens the triggering clip's player ([deepLinkRoute],
     * e.g. "player/{id}") — the actual evidence — falling back to the live view when no clip route is
     * given.
     */
    fun notifyNewClips(
        context: Context,
        count: Int,
        latestTime: String? = null,
        thumb: Bitmap? = null,
        deepLinkRoute: String = DEST_LIVE,
    ) {
        if (!canPost(context)) return
        ensureChannel(context)
        val text = context.resources.getQuantityString(R.plurals.notif_new_clips, count, count)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, deepLinkRoute))
        latestTime?.let { builder.setSubText(it) }
        if (thumb != null) {
            builder.setLargeIcon(thumb)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumb)
                        .bigLargeIcon(null as Bitmap?)   // hide the thumbnail once expanded
                        .setSummaryText(text),
                )
        }
        NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build())
    }

    /**
     * Once-a-day recap: event count + peak hour + the strongest clip's time, with that clip's
     * thumbnail as a BigPicture when available. Tapping it opens the day's clip list ([deepLinkRoute]
     * is a nav route like "clips/YYYYMMDD").
     */
    fun notifyDailyDigest(
        context: Context,
        count: Int,
        peakHour: Int?,
        strongestTime: String?,
        thumb: Bitmap?,
        deepLinkRoute: String,
    ) {
        if (!canPost(context) || count <= 0) return
        ensureChannel(context)
        val events = context.resources.getQuantityString(R.plurals.events, count, count)
        val peak = peakHour?.let {
            context.getString(
                R.string.notif_digest_peak,
                context.getString(R.string.analytics_hour_range, it, (it + 1) % 24),
            )
        }
        val text = listOfNotNull(events, peak).joinToString(" · ")
        val big = listOfNotNull(
            text,
            strongestTime?.let { context.getString(R.string.notif_digest_strongest, it) },
        ).joinToString("\n")
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(context.getString(R.string.notif_digest_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, deepLinkRoute))
        if (thumb != null) {
            builder.setLargeIcon(thumb)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumb)
                        .bigLargeIcon(null as Bitmap?)
                        .setSummaryText(big),
                )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(big))
        }
        NotificationManagerCompat.from(context).notify(NOTIF_ID_DIGEST, builder.build())
    }

    /** NVR health warning (recording down / not reporting / low battery). */
    fun notifyHealthIssues(context: Context, issues: List<String>) {
        if (!canPost(context) || issues.isEmpty()) return
        ensureChannel(context)
        val big = issues.joinToString("\n")
        val text = if (issues.size == 1) {
            issues[0]
        } else {
            context.resources.getQuantityString(R.plurals.notif_alerts_more, issues.size, issues.size)
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notif_health_title, context.getString(R.string.app_name)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_HEALTH, notif)
    }
}
