package af.shizuku.manager.service

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.LiveActivityNotificationManager
import af.shizuku.manager.utils.ManagerActivityLog
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.StockShizukuCompat
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class WatchdogService : Service() {
    private var lastRestartMs = 0L
    private var consecutiveCrashes = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    private var pendingRecoveryJob: Job? = null
    private var stabilityJob: Job? = null

    // Only true when the user explicitly taps "Turn off" from the notification — keeps
    // onDestroy() from clearing the watchdog setting when the service self-stops due to a
    // transient foreground-start rejection (background restrictions, FGS time limit, etc.).
    private var intentionalStop = false

    // Returns false on failure so callers can bail out via stopSelf() instead of crashing
    // (RemoteServiceException$CannotPostForegroundServiceNotificationException, background-start
    // restrictions, etc. — same pattern as AutomationService.ensureForeground(), SHIZUKUPLUS-5P).
    private fun startForegroundSafely(): Boolean =
        try {
            val notif = buildNotification(this, currentLiveStatus)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_WATCHDOG,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(
                    NOTIFICATION_ID_WATCHDOG,
                    notif,
                )
            }
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "startForeground refused; stopping watchdog service")
            false
        }

    override fun onCreate() {
        super.onCreate()
        setRunning(true)

        // Stop legacy ShizukuLiveService and dismiss notification 9999 if active (#33, #34)
        try {
            stopService(Intent(this, ShizukuLiveService::class.java))
            LiveActivityNotificationManager.dismiss(this)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to stop legacy live service on watchdog start")
        }

        // Create notification channel before startForeground() to avoid
        // InvalidForegroundServiceTypeException. Wrapped in try-catch so that
        // a null/restricted NotificationManager never prevents startForeground()
        // from being called (which would cause ForegroundServiceDidNotStartInTimeException).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.createNotificationChannel(
                    NotificationChannel(WATCHDOG_CHANNEL_ID, "Watchdog", NotificationManager.IMPORTANCE_LOW),
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to create watchdog notification channel")
            }
        }

        // Must be called within 5s of startForegroundService(); do it before any
        // coroutine work so that a heavy initialisation path can never delay it.
        if (!startForegroundSafely()) {
            setRunning(false)
            stopSelf()
            return
        }

        job =
            scope.launch {
                ShizukuStateMachine.asFlow().collectLatest { state ->
                    if (state == ShizukuStateMachine.State.CRASHED) {
                        // The server is no longer RUNNING, so the stability window ends here:
                        // left armed, it would fire while the server is dead and reset
                        // consecutiveCrashes, making the next crash look like a first restart (#35).
                        stabilityJob?.cancel()
                        // A recovery job armed by an earlier first restart is stale too: the
                        // server just died again, so what that job waits for is a later
                        // restart's business now. Left armed, its timeout would post a
                        // duplicate heads-up for this same outage, or a later restart's
                        // server would be logged as "came back after restart #1" (#35).
                        pendingRecoveryJob?.cancel()
                        // A restart run from outside the app (a shell, ops tooling) kills the
                        // server and starts a new one, and its binder dies here like a crash's.
                        // Restarting at once started a second starter alongside that one, and the
                        // two left two servers running. Wait, then restart only if no server came
                        // back: any later state (the new server's binder arriving, a start or stop
                        // from elsewhere) cancels this block, and update() asks the binder itself.
                        Timber.tag(TAG).d("Watchdog: server gone; waiting ${RESTART_GRACE_MS}ms for it to come back before restarting")
                        delay(RESTART_GRACE_MS)
                        if (ShizukuStateMachine.update() != ShizukuStateMachine.State.CRASHED) {
                            ManagerActivityLog.log(applicationContext, "Watchdog: the server came back on its own; no restart")
                            return@collectLatest
                        }
                        val now = System.currentTimeMillis()
                        val cooldown = backoffMs(consecutiveCrashes)
                        if (now - lastRestartMs > cooldown) {
                            val wasFirstRestart = (consecutiveCrashes == 0)
                            consecutiveCrashes++
                            lastRestartMs = now
                            // An unanswered authorisation dialog stops unattended ADB starts, so
                            // start() below will do nothing: do not announce a restart that is
                            // not going to happen. The "not answered" notice, with "Attempt
                            // now", is what the user sees instead.
                            val withheld =
                                ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB &&
                                    af.shizuku.manager.adb.AdbAuthWait
                                        .isUnanswered()
                            if (withheld) {
                                ManagerActivityLog.log(
                                    applicationContext,
                                    "Watchdog: crash #$consecutiveCrashes, restart withheld until an explicit start (authorisation dialog unanswered)",
                                )
                            } else {
                                if (!wasFirstRestart) {
                                    showCrashNotification()
                                }
                                ManagerActivityLog.log(applicationContext, "Watchdog: restarting after crash #$consecutiveCrashes")
                                if (wasFirstRestart) {
                                    pendingRecoveryJob?.cancel()
                                    pendingRecoveryJob =
                                        scope.launch {
                                            val cameBack =
                                                withTimeoutOrNull(RECOVERY_WAIT_MS) {
                                                    if (ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING) {
                                                        true
                                                    } else {
                                                        ShizukuStateMachine.asFlow().first { it == ShizukuStateMachine.State.RUNNING }
                                                        true
                                                    }
                                                } ?: false
                                            if (cameBack) {
                                                ManagerActivityLog.log(applicationContext, "Watchdog: the server came back after restart #1")
                                            } else {
                                                showCrashNotification()
                                            }
                                        }
                                }
                            }
                            ShizukuReceiverStarter.start(applicationContext)
                            Timber.tag(TAG).d("Watchdog: restart #$consecutiveCrashes (cooldown was ${cooldown}ms)")
                        } else {
                            Timber.tag(TAG).d("Watchdog: restart suppressed (cooldown active, ${now - lastRestartMs}ms / ${cooldown}ms)")
                        }
                    } else if (state == ShizukuStateMachine.State.RUNNING) {
                        // Not pendingRecoveryJob: RUNNING is exactly what the recovery check
                        // waits for, and both run on the main dispatcher, so cancelling here
                        // would kill that check before it logs the recovery (#35).
                        stabilityJob?.cancel()
                        stabilityJob =
                            scope.launch {
                                delay(STABILITY_WINDOW_MS)
                                consecutiveCrashes = 0
                            }
                    }
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!startForegroundSafely()) {
            setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_SERVICE) {
            intentionalStop = true
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stabilityJob?.cancel()
        pendingRecoveryJob?.cancel()
        job?.cancel()
        scope.cancel()
        setRunning(false)
        currentLiveStatus = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID_WATCHDOG)
        // Only clear the user's preference when the service was deliberately stopped via the
        // notification action. Transient stops (failed foreground-start, background-start
        // restrictions) must not wipe the setting — the watchdog should re-arm on next boot.
        if (intentionalStop) {
            ShizukuSettings.setWatchdog(applicationContext, false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification = buildNotification(this, currentLiveStatus)

    private fun showCrashNotification() {
        val channelId = CRASH_CHANNEL_ID
        val channelName = "Crash Reports"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            nm.createNotificationChannel(channel)
        }

        val learnMoreIntent =
            Intent(Intent.ACTION_VIEW).apply {
                setData(Uri.parse("https://github.com/thejaustin/ShizukuPlus/wiki#shizuku-keeps-stopping-randomly"))
            }
        val learnMorePendingIntent = PendingIntent.getActivity(this, 10, learnMoreIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val disableIntent = SettingsPage.Notifications.NotificationChannel.buildIntent(applicationContext)
        val disablePendingIntent = PendingIntent.getActivity(this, 11, disableIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Manual crash report via BugReportDialogActivity or similar
        val reportIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/frdminc/ShizukuTendCF/issues/new"))
        val reportPendingIntent = PendingIntent.getActivity(this, 12, reportIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder =
            NotificationCompat
                .Builder(this, channelId)
                .setContentTitle(getString(R.string.watchdog_shizuku_crashed_title))
                .setContentText(getString(R.string.watchdog_shizuku_crashed_text))
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentIntent(learnMorePendingIntent)
                .setAutoCancel(true)
                .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_report_manually), reportPendingIntent)
                .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_turn_off_alerts), disablePendingIntent)

        if (consecutiveCrashes >= 3 &&
            ShizukuSettings.isCompanionFallbackEnabled() &&
            StockShizukuCompat.isInstalled(applicationContext)
        ) {
            val companionIntent =
                applicationContext.packageManager
                    .getLaunchIntentForPackage(StockShizukuCompat.PACKAGE)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (companionIntent != null) {
                val companionPendingIntent =
                    PendingIntent.getActivity(
                        this,
                        3,
                        companionIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                builder.addAction(0, getString(R.string.watchdog_open_stock_shizuku), companionPendingIntent)
            }
        }

        val notification = builder.build()

        nm.notify(NOTIFICATION_ID_CRASH, notification)
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val NOTIFICATION_ID_WATCHDOG = 1001
        private const val NOTIFICATION_ID_CRASH = 1002
        private const val ACTION_STOP_SERVICE = "action.stop_service"
        const val WATCHDOG_CHANNEL_ID = "shizuku_watchdog"
        const val CRASH_CHANNEL_ID = "crash_reports"

        /** How long a crash waits for a server restarted from outside the app to come back. */
        const val RESTART_GRACE_MS = 10_000L

        /** How long a first restart waits for RUNNING before the crash heads-up is posted: a successful restart took ~11 s on an API 34 emulator, a loaded device's first start 32 s. A restart slower than this, or one that raises a fresh authorisation dialog answered later, still gets the heads-up — by design for now. */
        const val RECOVERY_WAIT_MS = 60_000L

        /** How long the server must remain RUNNING before consecutive crash count resets. */
        const val STABILITY_WINDOW_MS = 60_000L

        private const val BASE_COOLDOWN_MS = 5_000L
        private const val MAX_COOLDOWN_MS = 300_000L // 5 min cap
        private val isRunning = AtomicBoolean(false)
        private val runningListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
        @Volatile private var currentLiveStatus: String? = null

        fun backoffMs(crashes: Int): Long =
            minOf(BASE_COOLDOWN_MS * (1L shl crashes.coerceAtMost(10)), MAX_COOLDOWN_MS)

        @JvmStatic
        fun addRunningListener(listener: (Boolean) -> Unit) {
            runningListeners.add(listener)
        }

        @JvmStatic
        fun removeRunningListener(listener: (Boolean) -> Unit) {
            runningListeners.remove(listener)
        }

        private fun setRunning(running: Boolean) {
            if (isRunning.getAndSet(running) != running) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    runningListeners.forEach { it(running) }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        runningListeners.forEach { it(running) }
                    }
                }
            }
        }

        @JvmStatic
        fun updateLiveStatus(context: Context, status: String?) {
            currentLiveStatus = status
            if (isRunning()) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID_WATCHDOG, buildNotification(context, status))
            }
        }

        @JvmStatic
        fun buildNotification(
            context: Context,
            status: String? = currentLiveStatus,
        ): Notification {
            val launchIntent =
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                }
            val launchPendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val stopIntent =
                Intent(context, WatchdogService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
            val stopPendingIntent =
                PendingIntent.getService(
                    context,
                    1,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val builder =
                NotificationCompat
                    .Builder(context, WATCHDOG_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.watchdog_running))
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentIntent(launchPendingIntent)
                    .addAction(
                        R.drawable.ic_close_24,
                        context.getString(R.string.watchdog_turn_off),
                        stopPendingIntent,
                    ).setOngoing(true)

            if (!status.isNullOrEmpty()) {
                builder.setContentText(status)
            }

            return builder.build()
        }

        @JvmStatic
        fun start(context: Context) {
            try {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.tag("ShizukuApplication").w(e, "Failed to start WatchdogService: ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_WATCHDOG)
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }
}
