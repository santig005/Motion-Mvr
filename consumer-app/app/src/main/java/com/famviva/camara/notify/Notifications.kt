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

    /** Intent extra MainActivity reads to deep-link to a screen when a notification is tapped. */
    const val EXTRA_DEST = "dest"
    /** Deep-link value: open the live view. */
    const val DEST_LIVE = "live"

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
     * notification (photo + thumbnail); tapping it jumps straight to the live view so the user can
     * check what's happening now.
     */
    fun notifyNewClips(context: Context, count: Int, latestTime: String? = null, thumb: Bitmap? = null) {
        if (!canPost(context)) return
        ensureChannel(context)
        val text = context.resources.getQuantityString(R.plurals.notif_new_clips, count, count)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, DEST_LIVE))
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
