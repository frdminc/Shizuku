package af.shizuku.manager.utils

import af.shizuku.manager.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * #36: when a new install's Watchdog default turns the Watchdog on (not when the user turns it on
 * by hand — that path already asks), and the app is still not exempt from Android's battery
 * optimization after Device Hardening's pass, record the gap in the activity log and post one
 * notification whose tap opens the exemption request.
 */
object BatteryOptimizationGuard {
    private val gapLoggedWhileBlocked =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() = gapLoggedWhileBlocked.set(false)

    private const val CHANNEL_ID = "battery_optimization"
    const val NOTIFICATION_ID = 1454

    /** The activity-log action written when the gap is found (and the one scenarios assert on). */
    const val GAP_LOG_ACTION =
        "Watchdog: battery optimization still restricts the app — exempt it to keep the Watchdog running"

    /**
     * Checks the exemption and reports the gap. Returns true when the matter is settled (a TV, an
     * already-exempt app, or a posted notification) so the caller clears its pending flag, and false
     * when the check should be retried on a later start (notifications are disabled, so nothing
     * could be shown this time).
     */
    fun ensureExemption(context: Context): Boolean {
        if (EnvironmentUtils.isTelevision()) return true
        if (SettingsHelper.isIgnoringBatteryOptimizations(context)) return true

        if (!canPostNotification(context)) {
            // Retried at every start while blocked (a Watchdog restart included): log the gap once.
            if (gapLoggedWhileBlocked.compareAndSet(false, true)) ManagerActivityLog.log(context, GAP_LOG_ACTION)
            return false
        }
        ManagerActivityLog.log(context, GAP_LOG_ACTION)
        postNotification(context)
        return true
    }

    private fun canPostNotification(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // A channel the user later blocked drops notify() silently; treat that as "not yet".
            val channel = nm.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun postNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.battery_exemption_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        val contentIntent =
            PendingIntent.getActivity(
                context,
                0,
                SettingsHelper.batteryExemptionIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.battery_exemption_notification_title))
                .setContentText(context.getString(R.string.battery_exemption_notification_text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
