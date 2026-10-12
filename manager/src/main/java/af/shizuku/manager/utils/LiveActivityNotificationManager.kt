package af.shizuku.manager.utils

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.service.WatchdogService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat

object LiveActivityNotificationManager {
    private const val CHANNEL_ID = "live_activity_channel"
    const val NOTIFICATION_ID = 9999
    private const val WARNING_CHANNEL_ID = "activity_log_warning"
    const val WARNING_NOTIFICATION_ID = 9998

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.live_activity_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.live_activity_channel_description)
                    setShowBadge(false)
                }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        context: Context,
        status: String,
    ): android.app.Notification {
        ensureChannel(context)
        val pendingIntent =
            PendingIntentCompat.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                0,
                false,
            )
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_server_ok_24)
            .setContentTitle(context.getString(R.string.live_activity_notification_title))
            .setContentText(status)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun show(
        context: Context,
        status: String,
    ) {
        // Live activity is shown only inside the Watchdog's notification (1001). With the Watchdog
        // off there is no live-activity notification at all, so post nothing here (a standalone
        // 9999 posted in a narrow race — the Watchdog turned off mid-log — would never be cleared).
        if (ShizukuSettings.getWatchdog() || WatchdogService.isRunning()) {
            WatchdogService.updateLiveStatus(context, status)
        }
    }

    fun dismiss(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(NOTIFICATION_ID)
        if (WatchdogService.isRunning()) {
            WatchdogService.updateLiveStatus(context, null)
        }
    }

    /**
     * A one-shot, auto-cancelling warning (the activity log's database was corrupted and reset).
     * Independent of the Watchdog and of the live-status path: shown whatever the Watchdog setting,
     * on its own id so it never collides with 1001 or 9999.
     */
    fun showWarning(
        context: Context,
        title: String,
        message: String,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    WARNING_CHANNEL_ID,
                    "System warnings",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val pendingIntent =
            PendingIntentCompat.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java),
                0,
                false,
            )
        manager.notify(
            WARNING_NOTIFICATION_ID,
            NotificationCompat
                .Builder(context, WARNING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build(),
        )
    }
}
