package af.shizuku.manager.worker

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbAuthPendingException
import af.shizuku.manager.adb.AdbAuthTimeoutException
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.adb.AdbPortProber
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.adb.StartNotificationState
import af.shizuku.manager.adb.TcpPortOpenAgainException
import af.shizuku.manager.adb.WirelessDebugging
import af.shizuku.manager.adb.WirelessDebuggingBlockedException
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.settings.BugReportDialogActivity
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HeadlessLogger
import af.shizuku.manager.utils.ManagerActivityLog
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.util.concurrent.TimeoutException

class AdbStartWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private var discoverySession: WirelessDebugging.Session? = null
    // Counted as start work for its whole run, so the tile never settles a start this worker is
    // still making (mDNS discovery, waiting for an unlock, the binder wait).
    override suspend fun doWork(): Result = AdbAuthWait.starts.track {
        try { attemptStart() } finally { discoverySession?.closeConnection() }
    }

    // The start path's decisions also go to HeadlessLogger: a release build logs nothing through
    // Timber, and HEADLESS_LOG is then the only way to see why a start keeps failing.
    private fun note(message: String) = HeadlessLogger.i(LOG, message)

    private fun warn(message: String) = HeadlessLogger.w(LOG, message)

    // Queued as a quiet retry, and it still applies (One UI, locked, after a silent refusal).
    private fun quietRun(): Boolean = inputData.getBoolean(KEY_QUIET, false) && WirelessDebugging.quietAllowed(applicationContext)

    private fun wifiSetting(cr: android.content.ContentResolver) = runCatching { Settings.Global.getInt(cr, "adb_wifi_enabled", 0) }.getOrDefault(-1)

    private suspend fun attemptStart(): Result {
        HeadlessLogger.init(applicationContext)
        try {
            // Decide before publishing progress, so a start that stands down never shows itself
            // over the start that holds the dialog.
            if (AdbAuthWait.isWaiting()) {
                throw AdbAuthPendingException("another start is waiting for the adbd authorisation dialog to be answered")
            }
            // One dialog per boot or explicit start, enforced where every background connection
            // begins, so it also covers WorkManager's own re-runs and requests admitted long ago.
            // A request nobody made by hand (the watchdog, Home auto-reconnect, a Tasker trigger)
            // stops at any unanswered marker. An explicit one may pass a marker that was already
            // there when it was made (the user asked in spite of it) and keeps that right across
            // retries, but stops at a newer one: its own dialog, or a later one, went unanswered.
            // Read once: 0 means no marker.
            val unansweredAt = AdbAuthWait.unansweredStamp()
            if (unansweredAt != 0L &&
                (!inputData.getBoolean(KEY_EXPLICIT, false) || unansweredAt > inputData.getLong(KEY_REQUESTED_AT, 0L))
            ) {
                throw AdbAuthPendingException("the adbd authorisation dialog went unanswered; waiting for an explicit start")
            }
            timber.log.Timber.tag("AdbStartWorker").i(
                "doWork: runAttempt=%d, isAdbEnabled=%s, tcpMode=%s",
                runAttemptCount,
                EnvironmentUtils.isAdbEnabled(),
                ShizukuSettings.getTcpMode(),
            )
            note(
                "run attempt=$runAttemptCount explicit=${inputData.getBoolean(KEY_EXPLICIT, false)} " +
                    "adb_enabled=${EnvironmentUtils.isAdbEnabled()} tcp_mode=${ShizukuSettings.getTcpMode()} " +
                    "tcp_port=${ShizukuSettings.getTcpPort()} sys_tcp_port=${EnvironmentUtils.getAdbTcpPort()} " +
                    "last_port=${ShizukuSettings.getLastPort()} adb_wifi_enabled=${wifiSetting(applicationContext.contentResolver)}",
            )
            // Progress travels with this request's WorkInfo, which the shared notification is
            // rendered from; the refresh makes sure this process (perhaps started just for this run)
            // is rendering it. A quiet retry that still applies publishes nothing, so the
            // notification ignores it (QUIET_TAG with no step): its notice is the unlock notice.
            if (!quietRun()) {
                setProgress(workDataOf(KEY_STEP to StartNotificationState.Step.STARTING.name))
                ShizukuReceiverStarter.refreshNotification(applicationContext)
            }

            val cr = applicationContext.contentResolver

            // Give the system time to finish initializing after reboot before enabling ADB, longer
            // on Samsung One UI (#545). Its other part, a Samsung reset of adb_wifi_enabled, was
            // never confirmed: the system turns wireless debugging off without Wi-Fi or on an
            // untrusted network, which WirelessDebugging handles (no rewrite loop).
            if (runAttemptCount == 0) {
                if (!EnvironmentUtils.isAdbEnabled()) {
                    val baseDelay =
                        if (
                            Build.MANUFACTURER.equals("samsung", ignoreCase = true)
                        ) {
                            3000L
                        } else {
                            1500L
                        }
                    note("adb off after boot; settling ${baseDelay}ms before enabling it")
                    delay(baseDelay)
                }
            }

            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

            // TCP mode: when the port is listening, connect to it directly (no Wireless Debugging or
            // Wi-Fi required). When it is not (a reboot or `adb usb` closed it), open it again
            // through wireless debugging in this same attempt. Dialling the closed port (eight
            // connects, ~12 s) and a WorkManager backoff before that cost ~40 s on a Samsung S24.
            if (ShizukuSettings.getTcpMode()) {
                val desiredPort = ShizukuSettings.getTcpPort()
                if (desiredPort in 1..65535) {
                    val open = tcpPortOpen(desiredPort)
                    note("tcp fast path: port $desiredPort ${if (open) "open" else "closed"}")
                    if (open) return startThroughTcp(desiredPort)
                    // adbd still says it listens there (service.adb.tcp.port): closed only for a
                    // moment while adbd restarts (t2e, 2026-10-06: the end of a fleet deploy), which
                    // is no reason to wait for Wi-Fi and turn wireless debugging on. The property
                    // itself, not the old-TV fallback that assumes the configured port.
                    if (af.shizuku.common.util.EnvironmentUtils
                            .getAdbTcpPort() == desiredPort
                    ) {
                        note(
                            "tcp port $desiredPort closed although adbd reports it as its tcp port; " +
                                "probing again for up to ${TRANSIENT_PROBES * TRANSIENT_PROBE_GAP_MS / 1000} s (adbd may be restarting)",
                        )
                        repeat(TRANSIENT_PROBES) {
                            delay(TRANSIENT_PROBE_GAP_MS)
                            if (tcpPortOpen(desiredPort)) {
                                note("tcp port $desiredPort is open again; starting through it")
                                return startThroughTcp(desiredPort)
                            }
                        }
                        note("tcp port $desiredPort still closed after ${TRANSIENT_PROBES * TRANSIENT_PROBE_GAP_MS / 1000} s")
                    }
                    return restoreTcpPort(desiredPort)
                } else {
                    warn("tcp mode on but tcp port $desiredPort is invalid; skipping the fast path")
                }
            }

            val tcpPort = EnvironmentUtils.getAdbTcpPort()
            if (tcpPort > 0 && !ShizukuSettings.getTcpMode()) {
                note("tcp mode off but adbd listens on $tcpPort; switching adbd back to usb")
                if (!AdbStarter.stopTcp(applicationContext, tcpPort)) {
                    // Connecting again for the start would raise the dialog a second time.
                    throw AdbAuthTimeoutException("adbd authorisation was not accepted while leaving TCP mode")
                }
            }

            val savedPort = ShizukuSettings.getLastPort()
            val isWifiOk = !EnvironmentUtils.isWifiRequired() || ShizukuSettings.isForceStartWadbEnabled()

            // force_start_wadb fast-path: probe the default ADB TCP port when no port is already
            // known from the system. Handles devices (e.g. Vivo/FunTouchOS) that block mDNS
            // multicast — if adbd is already listening via persist.adb.tcp.port from a prior
            // session, we connect directly without going through mDNS discovery.
            if (ShizukuSettings.isForceStartWadbEnabled() && tcpPort <= 0 && savedPort <= 0) {
                val probePort = ShizukuSettings.getTcpPort().takeIf { it in 1..65535 } ?: 5555
                val open = AdbPortProber.isPortOpen(probePort, 400)
                note("force_start_wadb probe: port $probePort ${if (open) "open" else "closed"}")
                if (open) {
                    AdbStarter.startAdb(applicationContext, probePort)
                    Starter.waitForBinder()
                    ManagerActivityLog.log(
                        applicationContext,
                        "Service started via force_start_wadb TCP probe on port $probePort",
                    )
                    WirelessDebugging.succeeded(applicationContext)
                    note("SUCCESS via force_start_wadb probe on port $probePort (attempt=$runAttemptCount)")
                    return Result.success()
                }
            }

            val port =
                when {
                    tcpPort > 0 && isWifiOk -> tcpPort.also { note("using adbd's tcp port $it") }
                    savedPort > 0 && isWifiOk && runAttemptCount == 0 -> savedPort.also { note("using the saved port $it (first attempt)") }
                    else -> {
                        note("discovering the wireless debugging port by mDNS (sys_tcp_port=$tcpPort saved_port=$savedPort wifi_ok=$isWifiOk)")
                        // Wireless debugging is the transport here, so it stays on afterwards.
                        wirelessSession().findPort()
                    }
                }

            timber.log.Timber
                .tag("AdbStartWorker")
                .i("doWork: resolved port %d, starting ADB client", port)
            note("starting adb on port $port")
            AdbStarter.startAdb(applicationContext, port, connectedClient = discoverySession?.scannedClient)
            Starter.waitForBinder()
            ManagerActivityLog.log(applicationContext, "Service started via background ADB worker on port $port")
            WirelessDebugging.clearNoWifi(applicationContext)
            WirelessDebugging.succeeded(applicationContext)
            note("SUCCESS on port $port (attempt=$runAttemptCount)")
            timber.log.Timber
                .tag("AdbStartWorker")
                .i("doWork: Shizuku service successfully started and binder ready on port %d", port)

            return Result.success()
        } catch (e: CancellationException) {
            // Whether WorkManager runs this request again (a stop by the system) or not (Cancel,
            // REPLACE) is its decision, and the notification follows it from WorkInfo.
            val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason else -1
            timber.log.Timber
                .tag("AdbStartWorker")
                .w("doWork: job cancelled (stopReason=%d)", reason)
            warn("cancelled (stop_reason=$reason, attempt=$runAttemptCount)")
            throw e
        } catch (e: AdbAuthPendingException) {
            // Another start holds the one authorisation dialog. It owns the state machine and the
            // unanswered marker; stand down without touching either (and without retrying, which
            // would just stand down again).
            timber.log.Timber
                .tag("AdbStartWorker")
                .i("doWork: stood down: %s", e.message)
            note("FAILURE stood down (attempt=$runAttemptCount): ${e.message}")
            return Result.failure()
        } catch (e: WirelessDebuggingBlockedException) {
            // Only Wi-Fi, another network or the user can change this; a WorkManager retry would
            // wait (or raise the system's network prompt) again. The notice says what to do; Wi-Fi
            // connecting resumes a start that had none, and wireless debugging coming on (or, after
            // a silent refusal, an unlock) one stopped for an untrusted network.
            when (e.reason) {
                WirelessDebugging.Blocked.NO_WIFI -> {
                    warn("FAILURE (attempt=$runAttemptCount): ${e.message}; not retrying until Wi-Fi connects or a start by hand")
                    // Not after this boot's prompt: that one never resumes by itself.
                    WirelessDebugging.armResume(applicationContext)
                    // adbd may listen on the TCP port again without Wi-Fi (it was restarting, or
                    // `adb tcpip` from a computer): probed a few more times (once per stop).
                    if (ShizukuSettings.getTcpMode()) WirelessDebugging.armPortRecheck(applicationContext, ShizukuSettings.getTcpPort())
                }
                WirelessDebugging.Blocked.UNTRUSTED_NETWORK -> {
                    warn("FAILURE (attempt=$runAttemptCount): ${e.message}; no WorkManager retry")
                    WirelessDebugging.watch(applicationContext)
                }
            }
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            }
            return Result.failure()
        } catch (e: AdbAuthTimeoutException) {
            // Retrying (WorkManager backoff) would open a new connection and raise a new dialog.
            // Stop here; the notification's "Attempt now" or the next explicit start tries again.
            timber.log.Timber
                .tag("AdbStartWorker")
                .w(e, "doWork: authorisation dialog not answered, not retrying")
            warn("FAILURE authorisation dialog not accepted, not retrying (attempt=$runAttemptCount): ${HeadlessLogger.brief(e)}")
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            }
            // The marker was recorded when the key was offered (AdbClient) and stays: a server
            // that happens to be running did not come from this offer, and its key is still
            // not authorised.
            return Result.failure()
        } catch (e: Exception) {
            timber.log.Timber
                .tag("AdbStartWorker")
                .e(e, "doWork: failed on runAttempt %d: %s", runAttemptCount, e.message)
            val ignored =
                listOf(
                    EOFException::class,
                    SecurityException::class,
                    TimeoutException::class,
                    java.net.ConnectException::class,
                    java.net.SocketException::class,
                    java.net.SocketTimeoutException::class,
                )
            // Only show error notification if it's not a common transient error,
            // or if we've already tried several times and it's still failing.
            if (ignored.none { it.isInstance(e) } || runAttemptCount >= 5) {
                if (e !is SecurityException && e !is TimeoutException) {
                    showErrorNotification(applicationContext, e)
                }
            }

            // Reset STARTING → STOPPED so update() can re-detect the real state.
            // Without this, update() perpetually preserves STARTING (binder never
            // arrived) and every subsequent button click shows "already starting".
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            }
            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                // A server is up despite the exception (e.g. the connection dropped after the
                // starter command ran). That does not show the key was accepted, so the
                // unanswered marker is left to AdbClient, which clears it on acceptance.
                WirelessDebugging.succeeded(applicationContext)
                warn("SUCCESS (attempt=$runAttemptCount): the server is running despite ${HeadlessLogger.brief(e)}")
                return Result.success()
            } else {
                warn(
                    "RETRY (attempt=$runAttemptCount): ${HeadlessLogger.brief(e)} " +
                        "adb_wifi_enabled=${wifiSetting(applicationContext.contentResolver)}",
                )
                // After repeated mDNS timeouts, suggest TCP Mode — the device may be
                // blocking multicast (common on Vivo/FunTouchOS and some corporate Wi-Fi).
                if (e is TimeoutException && runAttemptCount >= 2) {
                    showMdnsBlockedSuggestion(applicationContext)
                }
                return Result.retry()
            }
        }
    }

    /**
     * TCP mode with its port closed. adbd may still listen on another TCP port (`adb tcpip`), which
     * AdbStarter moves; otherwise wireless debugging is turned on, its TLS port found by mDNS, and
     * AdbStarter sends `tcpip:<port>` there and connects to the reopened port. Wireless debugging
     * is then put back as this start found it: the TCP port stays open until the next reboot.
     */
    private suspend fun restoreTcpPort(desiredPort: Int): Result {
        val sysPort = EnvironmentUtils.getAdbTcpPort()
        if (sysPort in 1..65535 && sysPort != desiredPort && AdbPortProber.isPortOpen(sysPort, 600)) {
            note("adbd listens on tcp port $sysPort; moving it to $desiredPort")
            return startOn(sysPort).also { WirelessDebugging.afterTcpStart(applicationContext, ::note) }
        }
        if (!WirelessDebugging.available()) {
            // Before Android 11, or without TLS adb, only USB or root can reopen it; something
            // else (ops tooling, `adb tcpip`) may, and the next run's probe would find it.
            warn("tcp port $desiredPort closed and this device has no wireless debugging to reopen it; RETRY later")
            return Result.retry()
        }
        note("tcp port $desiredPort closed; restoring it through wireless debugging in this attempt")
        // While it waits for Wi-Fi the port is probed again: adbd may listen there again by itself.
        val session = wirelessSession(restoresState = true, tcpPortOpen = { tcpPortOpen(desiredPort) })
        // A start that stood down for another (one holding adbd's dialog, or an interactive start)
        // leaves wireless debugging to it, as AdbStarter does.
        var stoodDown = false
        try {
            val port = session.findPort()
            note("starting adb on wireless debugging port $port to reopen tcp port $desiredPort")
            return startOn(port)
        } catch (e: AdbAuthPendingException) {
            stoodDown = true
            throw e
        } catch (_: TcpPortOpenAgainException) {
            // Falls through to start through the port once the session has put back what it
            // changed (nothing, if it was still waiting for its first Wi-Fi).
        } finally {
            if (!stoodDown) session.restore()
        }
        note("tcp port $desiredPort is open again; starting through it")
        return startThroughTcp(desiredPort)
    }

    /** The TCP-mode fast path: adbd listens on [port], so connect to it directly, no Wi-Fi needed. */
    private suspend fun startThroughTcp(port: Int): Result {
        AdbStarter.startAdb(applicationContext, port)
        Starter.waitForBinder()
        ManagerActivityLog.log(applicationContext, "Service started via direct TCP port $port (no Wi-Fi required)")
        WirelessDebugging.clearNoWifi(applicationContext)
        WirelessDebugging.succeeded(applicationContext)
        WirelessDebugging.afterTcpStart(applicationContext, ::note)
        note("SUCCESS via tcp fast path on port $port (attempt=$runAttemptCount)")
        return Result.success()
    }

    // A loopback connect, off the worker's dispatcher threads.
    private suspend fun tcpPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) { AdbPortProber.isPortOpen(port, 600) }

    private suspend fun startOn(port: Int): Result {
        AdbStarter.startAdb(applicationContext, port, connectedClient = discoverySession?.scannedClient)
        Starter.waitForBinder()
        ManagerActivityLog.log(applicationContext, "Service started via background ADB worker on port $port")
        // This boot's network prompt stays: a later restore the same boot must not ask again.
        WirelessDebugging.clearNoWifi(applicationContext)
        WirelessDebugging.succeeded(applicationContext)
        note("SUCCESS on port $port (attempt=$runAttemptCount)")
        return Result.success()
    }

    /**
     * This run's use of wireless debugging. A start nobody made by hand stands down after this
     * boot's network prompt (while wireless debugging is still off) and while there is still no
     * Wi-Fi after a no-Wi-Fi stop, so the watchdog, the boot retry and WorkManager's re-runs
     * neither wait nor prompt again. A start by hand made after the prompt may ask once more; a
     * re-run of one made before it may not (its requested-at, as the unanswered marker does).
     * The quiet retry (One UI, locked, after a silent refusal) may write while locked.
     */
    private fun wirelessSession(
        restoresState: Boolean = false,
        tcpPortOpen: (suspend () -> Boolean)? = null,
    ): WirelessDebugging.Session {
        val explicit = inputData.getBoolean(KEY_EXPLICIT, false)
        // Quiet only while that still holds: a quiet request run later (WorkManager's retry, or
        // after an unlock) is an ordinary start, which may wait for Wi-Fi and ask.
        val queuedQuiet = inputData.getBoolean(KEY_QUIET, false)
        val quiet = quietRun()
        if (queuedQuiet && !quiet) note("queued as a quiet retry, but no longer locked after a silent refusal: an ordinary start")
        WirelessDebugging.standDownReason(applicationContext, explicit, inputData.getLong(KEY_REQUESTED_AT, 0L), quiet)?.let { (reason, why) ->
            throw WirelessDebuggingBlockedException(reason, why)
        }
        if (quiet) note("the quiet retry: turning wireless debugging on while locked, in case this access point is allowed")
        WirelessDebugging.pauseWatch(applicationContext)
        if (explicit && WirelessDebugging.promptedAt(applicationContext) != null) {
            note("a start by hand made after this boot's network prompt: it may ask once more")
            WirelessDebugging.clearPrompted(applicationContext)
        }
        // The unlock notice stays while an unlock is what helps.
        WirelessDebugging.clearNoWifi(applicationContext, keepNotice = quiet || WirelessDebugging.silentRefusalPending(applicationContext))
        return WirelessDebugging.Session(applicationContext, ::note, ::warn, ::promoteToForeground, restoresState, quiet, tcpPortOpen)
            .also { discoverySession = it }
    }

    private var promoted = false
    private var promotedOk = false

    /**
     * Waiting for Wi-Fi, an unlock or the user can outlast what a background job is allowed, so the
     * run becomes a foreground worker for it. Only once the foreground notification is really up
     * does this run's identical progress step aside; if promotion fails, the progress stays the
     * one visible sign of the start.
     */
    private suspend fun promoteToForeground(): Boolean {
        if (promoted) return promotedOk
        promoted = true
        val notification = ShizukuReceiverStarter.buildForegroundNotification(applicationContext)
        // On Android 14+ (API 34), ForegroundInfo must declare a foreground service type (one the
        // manifest lists for SystemForegroundService) or the OS throws
        // InvalidForegroundServiceTypeException. specialUse, not shortService: the OS stops a
        // shortService after about 3 minutes, and this run may wait for Wi-Fi, an unlock or the
        // user and then up to 5 minutes for the adbd dialog.
        val foregroundInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ForegroundInfo(
                    ShizukuReceiverStarter.FOREGROUND_NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                ForegroundInfo(ShizukuReceiverStarter.FOREGROUND_NOTIFICATION_ID, notification)
            }
        try {
            setForeground(foregroundInfo)
            setProgress(workDataOf(KEY_STEP to StartNotificationState.Step.FOREGROUND.name))
            promotedOk = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            timber.log.Timber
                .tag("AdbStartWorker")
                .w(e, "doWork: foreground promotion failed")
            warn("foreground promotion failed: ${HeadlessLogger.brief(e)}")
        }
        return promotedOk
    }

    private fun Throwable.toUserMessage(context: Context): String =
        when {
            this is java.net.ConnectException || this is java.net.SocketTimeoutException ->
                context.getString(R.string.wadb_error_cannot_connect)
            this is java.util.concurrent.TimeoutException ->
                context.getString(R.string.wadb_error_discovery_timeout)
            this is javax.net.ssl.SSLException ->
                context.getString(R.string.wadb_error_ssl_mismatch)
            this is SecurityException ->
                context.getString(R.string.wadb_error_not_authorized)
            else -> context.getString(R.string.wadb_error_generic_short)
        }

    private fun showMdnsBlockedSuggestion(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.wadb_notification_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val openAppIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pi =
            PendingIntent.getActivity(
                context,
                20,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.wadb_mdns_blocked_title))
                .setContentText(context.getString(R.string.wadb_mdns_blocked_text))
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(context.getString(R.string.wadb_mdns_blocked_text)),
                ).setContentIntent(pi)
                .addAction(0, context.getString(R.string.wadb_mdns_switch_tcp_action), pi)
                .setAutoCancel(true)
                .build()
        nm.notify(NOTIFICATION_ID_MDNS_BLOCKED, notification)
    }

    private fun showErrorNotification(
        context: Context,
        e: Exception,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.wadb_notification_title),
                    NotificationManager.IMPORTANCE_LOW,
                )
            nm.createNotificationChannel(channel)
        }

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        val shortMsg = e.toUserMessage(context)
        val devDetail = e.message?.take(120)
        val bigText = if (devDetail != null) "$shortMsg\n\n$devDetail" else shortMsg

        val intent =
            Intent(context, BugReportDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            nb
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.wadb_error_title))
                .setContentText(shortMsg)
                .setContentIntent(pendingIntent)
                .setSilent(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        /**
         * Asks for a background start. Returns at once: whether to enqueue is decided off the main
         * thread from WorkManager's state (nothing happens while a worker is already running), and
         * the notification then shows whatever WorkManager holds.
         */
        fun enqueue(
            context: Context,
            explicit: Boolean = false,
            quiet: Boolean = false,
        ) {
            // An early out only: enqueueStart decides again, as one step with the enqueue.
            if (AdbAuthWait.isWaiting()) {
                timber.log.Timber
                    .tag("AdbStartWorker")
                    .i("enqueue skipped: waiting for the adbd authorisation dialog")
                return
            }
            // WorkManager uses credential-encrypted storage which is unavailable during direct boot.
            // Skip enqueueing until the user has unlocked their device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(android.os.UserManager::class.java)
                if (um != null && !um.isUserUnlocked) return
            }

            val waitsForWifi = EnvironmentUtils.isWifiRequired() && !ShizukuSettings.isForceStartWadbEnabled()
            val cb = Constraints.Builder()
            if (waitsForWifi) {
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            }
            val request =
                OneTimeWorkRequestBuilder<AdbStartWorker>()
                    .setConstraints(cb.build())
                    .setInputData(workDataOf(KEY_EXPLICIT to explicit, KEY_REQUESTED_AT to AdbAuthWait.clockMs(), KEY_QUIET to quiet))
                    .apply { if (quiet) addTag(QUIET_TAG) }
                    .build()
            ShizukuReceiverStarter.enqueueStart(context, request, explicit)
        }

        /** Cancels the start work, queued or running, and dismisses its "not answered" notice. */
        fun cancel(
            context: Context,
            then: (() -> Unit)? = null,
        ) = ShizukuReceiverStarter.cancelStarts(context, then)

        const val UNIQUE_WORK_NAME = "adb_start_worker"

        /** On a quiet retry's request: the start notification shows it only once it publishes a step. */
        const val QUIET_TAG = "adb_start_quiet"

        // HeadlessLogger component.
        private const val LOG = "StartWorker"

        // TCP mode, the port closed although adbd reports it: probed this many more times, this
        // far apart, before the restore through wireless debugging.
        private const val TRANSIENT_PROBES = 5
        private const val TRANSIENT_PROBE_GAP_MS = 1_000L

        // The progress key a running worker publishes its StartNotificationState.Step under.
        internal const val KEY_STEP = "step"

        // The request came from the user's own hand (or a path that deliberately allows a new dialog).
        private const val KEY_EXPLICIT = "explicit"
        private const val KEY_REQUESTED_AT = "requested_at"

        // WirelessDebugging's quiet retry while locked (One UI): no wait, no notice.
        private const val KEY_QUIET = "quiet"
        const val CHANNEL_ID = "AdbStartWorker"
        const val NOTIFICATION_ID = 1448
        private const val NOTIFICATION_ID_MDNS_BLOCKED = 1449
    }
}
