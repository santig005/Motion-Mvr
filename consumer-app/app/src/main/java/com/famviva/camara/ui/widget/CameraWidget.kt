package com.famviva.camara.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.famviva.camara.MainActivity
import com.famviva.camara.R
import com.famviva.camara.data.WidgetSummaryStore
import com.famviva.camara.data.agoLabel
import com.famviva.camara.notify.Notifications

/**
 * Home-screen widget: camera health at a glance (recording state + heartbeat freshness, battery,
 * today's clip count and the newest clip's time). It renders only the snapshot NewClipsWorker
 * persisted in [WidgetSummaryStore] — no network from the widget — and shows that snapshot's age
 * honestly ("actualizado hace X min"), so stale data reads as stale.
 *
 * Tap the body -> app (Clips tab). Tap "En vivo" -> the live view (deep link).
 */
class CameraWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = WidgetSummaryStore(context).read()
        provideContent { WidgetContent(context, summary) }
    }
}

class CameraWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CameraWidget()
}

// Fixed dark-card palette: reads well over any launcher wallpaper (light or dark) without pulling in
// the Glance material3 dependency just for dynamic theming.
private val bg = ColorProvider(Color(0xFF1B1E23))
private val cardText = ColorProvider(Color(0xFFECEDEE))
private val subText = ColorProvider(Color(0xFF9BA0A6))
private val okColor = ColorProvider(Color(0xFF6FD98A))
private val warnColor = ColorProvider(Color(0xFFF2B8B5))
private val accent = ColorProvider(Color(0xFFAEC6FF))

@Composable
private fun WidgetContent(context: Context, s: WidgetSummaryStore.Summary) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(bg).padding(12.dp)
            .clickable(actionStartActivity(openApp(context, dest = null))),
        verticalAlignment = Alignment.Top,
    ) {
        // Title + recording status dot line.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.app_name),
                style = TextStyle(color = cardText, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (s.hasData) {
                val now = System.currentTimeMillis() / 1000
                val stale = s.heartbeatUpdated > 0 && now - s.heartbeatUpdated > 10800  // 3 h
                val (label, tint) = when {
                    stale -> context.getString(R.string.widget_not_reporting) to warnColor
                    s.recordingOk -> recordingLabel(context, s.recMode) to okColor
                    else -> context.getString(R.string.widget_recording_off) to warnColor
                }
                Text(label, style = TextStyle(color = tint, fontWeight = FontWeight.Medium, fontSize = 13.sp))
            }
        }

        Spacer(GlanceModifier.height(8.dp))

        if (!s.hasData) {
            Text(context.getString(R.string.widget_no_data), style = TextStyle(color = subText, fontSize = 13.sp))
            return@Column
        }

        // Battery + today's clip count.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (s.battery >= 0) {
                val batteryLabel = context.getString(R.string.widget_battery, s.battery) +
                    if (s.charging) " ⚡" else ""
                Text(batteryLabel, style = TextStyle(color = cardText, fontWeight = FontWeight.Medium, fontSize = 14.sp))
                Spacer(GlanceModifier.width(12.dp))
            }
            Text(
                context.getString(R.string.widget_today_events, s.todayCount),
                style = TextStyle(color = cardText, fontSize = 14.sp),
            )
        }

        Spacer(GlanceModifier.height(4.dp))

        // Newest clip time.
        s.newestClipTime?.let {
            Text(
                context.getString(R.string.widget_last_clip, it),
                style = TextStyle(color = subText, fontSize = 13.sp),
            )
        }

        Spacer(GlanceModifier.defaultWeight())

        // Footer: honest freshness of the snapshot + a Live shortcut.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(
                    R.string.widget_updated,
                    agoLabel(context, s.updatedEpoch, System.currentTimeMillis() / 1000),
                ),
                style = TextStyle(color = subText, fontSize = 11.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                context.getString(R.string.widget_live),
                style = TextStyle(color = accent, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                modifier = GlanceModifier
                    .cornerRadius(8.dp)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clickable(actionStartActivity(openApp(context, dest = Notifications.DEST_LIVE))),
            )
        }
    }
}

private fun recordingLabel(context: Context, recMode: String?): String =
    if (recMode == "SUB") context.getString(R.string.widget_recording_sub)
    else context.getString(R.string.widget_recording_on)

/** Intent into the app, optionally deep-linking to a destination (e.g. the live view). */
private fun openApp(context: Context, dest: String?): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .apply { if (dest != null) putExtra(Notifications.EXTRA_DEST, dest) }
