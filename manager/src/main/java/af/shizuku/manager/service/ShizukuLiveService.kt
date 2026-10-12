package af.shizuku.manager.service

import af.shizuku.manager.utils.LiveActivityNotificationManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import timber.log.Timber

class ShizukuLiveService : Service() {
    companion object {
        private const val TAG = "ShizukuLiveService"
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // A legacy entry point: live activity is shown only inside the Watchdog's notification
        // (1001), so this service posts nothing and stops as soon as it starts. startForeground()
        // must still be called within 5s of startForegroundService() — a start that stops without
        // it crashes the app on API 26+ (ForegroundServiceDidNotStartInTimeException).
        val notif = LiveActivityNotificationManager.buildNotification(this, "Monitoring...")
        if (!startForegroundSafely(notif)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        removeForegroundNotification()
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // Returns false when the platform refuses the FGS notification (CannotPostForegroundService-
    // NotificationException, ForegroundServiceStartNotAllowedException, etc.) so callers can
    // stopSelf() gracefully rather than crashing — SHIZUKUPLUS-5P.
    private fun startForegroundSafely(notif: Notification): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    LiveActivityNotificationManager.NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(LiveActivityNotificationManager.NOTIFICATION_ID, notif)
            }
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "startForeground refused; stopping service")
            false
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Cancel only notification 9999; never touch the Watchdog's notification (1001) or its
        // live text.
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(LiveActivityNotificationManager.NOTIFICATION_ID)
        super.onDestroy()
    }
}
