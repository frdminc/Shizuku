package af.shizuku.manager.adb
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HeadlessLogger
import af.shizuku.manager.utils.ManagerActivityLog
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

object AdbStarter {
    private const val TAG = "AdbStarter"

    // HeadlessLogger component: what a release build can still report about a start.
    private const val LOG = "AdbStarter"

    /**
     * Whether adbd may be asked to use the legacy token challenge on [port] (see AdbClient's
     * allowLegacyAuth): only on the TCP port adbd itself reports listening on
     * (service.adb.tcp.port, then persist.adb.tcp.port), on every Android version, and not when
     * service.adb.listen_addrs puts adbd on other addresses (adbd then ignores the port
     * properties). A device whose properties cannot be read gets no legacy connection. Old TVs
     * without TLS fall back to the configured TCP port, as EnvironmentUtils does.
     */
    internal val realIsLegacyAuthPort: (Int) -> Boolean = { port ->
        val listen =
            af.shizuku.common.util.EnvironmentUtils
                .getAdbListenAddrs()
        port in 1..65535 &&
            port == EnvironmentUtils.getAdbTcpPort() &&
            (listen.isBlank() || listen.split(',').any { it.trim().endsWith(":$port") })
    }

    @VisibleForTesting
    internal var isLegacyAuthPort: (Int) -> Boolean = realIsLegacyAuthPort

    private fun Context.getActivity(): Activity? {
        var context = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    /** Returns true for transient connection errors that are not bugs and should not go to Sentry. */
    private fun Throwable.isExpectedAdbError(includeIllegalState: Boolean = false) =
        this is EOFException ||
            this is SocketException ||
            this is SocketTimeoutException ||
            this is ConnectException ||
            this is SSLException ||
            this is AdbKeyException ||
            this is AdbAuthPendingException ||
            this is AdbTlsRequiredException ||
            (includeIllegalState && this is IllegalStateException)

    /**
     * @param attempt the background start this call belongs to (see [AdbAuthWait.scheduleAttempt]);
     * its authorisation prompt is posted as that attempt's. Ignored when [log] is given: an
     * interactive start reports through its log and posts nothing.
     */
    suspend fun startAdb(
        context: Context,
        port: Int,
        log: ((String) -> Unit)? = null,
        attempt: Long = AdbAuthWait.NO_ATTEMPT,
        connectedClient: AdbClient? = null,
        /**
         * True only when this start was made by the user's own hand in the app's UI and may ask
         * the user to confirm a changed adbd key. Exported intents, tiles, boot and headless paths
         * must leave this false.
         */
        userGesture: Boolean = false,
        /** The exact SPKI approved by the user's confirmation. */
        confirmedPin: String? = null,
    ) {
        HeadlessLogger.init(context)
        if (port !in 1..65535) {
            Timber.tag(TAG).w("startAdb called with invalid port $port — skipping")
            HeadlessLogger.w(LOG, "invalid port $port; not starting")
            return
        }

        // Another start is holding adbd's authorisation dialog open; a second connection would
        // raise a second dialog. Throw rather than return: a silent return looked like success,
        // and the caller's next step (a 20 s waitForBinder) then timed out while the pending
        // start was still legitimately waiting its 300 s.
        if (AdbAuthWait.isWaiting()) {
            Timber.tag(TAG).i("startAdb stood down: waiting for the adbd authorisation dialog")
            HeadlessLogger.i(LOG, "stood down on port $port: another start holds the adbd authorisation dialog")
            log?.invoke(context.getString(R.string.wadb_notification_awaiting_auth) + "\n")
            throw AdbAuthPendingException("another start is waiting for the adbd authorisation dialog to be answered")
        }

        // Set when this call stood down because another start owns the authorisation wait; the
        // finally block must then leave wireless debugging alone — it is the owner's transport.
        var stoodDown = false
        var pinRefused = false

        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        // Counted from here to the end of the starter command: the authorisation wait alone ends
        // before the server is deployed, and an already-authorised key never waits at all.
        AdbAuthWait.starts.begin()
        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            Timber.tag(TAG).i("startAdb: initiating connection on port %d", port)
            log?.invoke("Starting with wireless adb...\n")

            withContext(Dispatchers.IO) {
                val key =
                    runCatching { AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+") }
                        .getOrElse {
                            if (it is CancellationException) {
                                throw it
                            } else {
                                throw AdbKeyException(it)
                            }
                        }

                // The system dialog shows the offered key's fingerprint; repeating it here lets
                // the user tell this prompt from one raised by something else at the same moment.
                // The background worker owns (and later clears) the ongoing notification; an
                // interactive start reports through its log instead and must not leave one behind.
                val fingerprint = runCatching { context.getString(R.string.wadb_auth_fingerprint, key.fingerprint()) }.getOrNull()
                val onPending = {
                    HeadlessLogger.i(LOG, "key not yet authorised; adbd is asking (${fingerprint ?: "fingerprint unavailable"})")
                    if (log != null) {
                        log.invoke(listOfNotNull(context.getString(R.string.wadb_notification_awaiting_auth), fingerprint).joinToString(". ") + "\n")
                    } else {
                        ShizukuReceiverStarter.postAuthPrompt(context, attempt, fingerprint)
                    }
                }

                var activePort = port
                var retained = connectedClient
                var retryPin = confirmedPin.takeIf { userGesture }

                fun nextClient(): AdbClient {
                    val connected = retained
                    retained = null
                    if (connected != null) {
                        val pin = AdbServerPin.store().connectPin
                        if (pin == null || connected.seenPin != pin) {
                            val seen = connected.seenPin
                            connected.close()
                            if (pin == null) {
                                AdbServerPin.pendingPort = activePort
                                throw AdbServerPinMissingException(seen)
                            }
                            throw AdbServerCertChangedException(seenPin = seen)
                        }
                        return connected
                    }
                    val expected = retryPin
                    retryPin = null
                    return activePort.let { p ->
                        AdbClient(
                            "127.0.0.1",
                            p,
                            key,
                            onPending,
                            allowLegacyAuth = { isLegacyAuthPort(p) },
                            pinChangeLog = log,
                            expectedPin = expected,
                            requireRecordedPin = activePort == AdbServerPin.pendingPort,
                        )
                    }
                }

                suspend fun connectUnlessRetained(client: AdbClient) {
                    if (client !== connectedClient) connectWithRetry(client, activePort)
                }
                val tcpMode = ShizukuSettings.getTcpMode()
                val tcpPort = ShizukuSettings.getTcpPort()
                HeadlessLogger.i(LOG, "start on port $port (tcp_mode=$tcpMode tcp_port=$tcpPort interactive=${log != null} user_gesture=$userGesture confirmed_key=${confirmedPin != null})")
                if (tcpMode && activePort != tcpPort) {
                    if (tcpPort !in 1..65535) {
                        Timber.tag(TAG).w("TCP mode enabled but stored TCP port is invalid ($tcpPort) — skipping TCP redirect")
                        HeadlessLogger.w(LOG, "tcp mode on but tcp port $tcpPort is invalid; not switching")
                    } else {
                        Timber.tag(TAG).d("Switching ADB from port %d to TCP port %d", activePort, tcpPort)
                        log?.invoke("Connecting on port $activePort...")

                        nextClient().use { client ->
                            connectUnlessRetained(client)

                            log?.invoke("Successfully connected on port $activePort...")
                            log?.invoke("\nRestarting in TCP mode port: $tcpPort")

                            activePort = tcpPort
                            HeadlessLogger.i(LOG, "connected on $port; switching adbd to tcp port $tcpPort (tcpip)")
                            runCatching {
                                client.command("tcpip:$activePort")
                            }.onFailure { if (it !is EOFException && it !is SocketException) throw it } // Expected when ADB restarts in TCP mode
                        }
                    }
                }

                Timber.tag(TAG).i("Connecting to ADB daemon at 127.0.0.1:%d", activePort)
                log?.invoke("Connecting on port $activePort...")

                nextClient().use { client ->
                    connectUnlessRetained(client)
                    Timber.tag(TAG).i("Connected to ADB at 127.0.0.1:%d; deploying starter command", activePort)
                    HeadlessLogger.i(LOG, "connected on $activePort; running the starter")
                    log?.invoke("Successfully connected on port $activePort...\n")
                    client.runCommand("shell:${Starter.internalCommand}")
                    runCatching {
                        client.runCommand("shell:cmd appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
                        client.runCommand("shell:pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    }.onFailure { Timber.tag(TAG).w(it, "Failed to auto-elevate privileges on ADB start") }
                    // A port found by the loopback scan is not saved: the next first attempt dials the
                    // saved port with an ordinary client, and only mDNS or a TCP port should seed it.
                    if (client !== connectedClient) ShizukuSettings.setLastPort(activePort)
                    AdbServerPin.clearPendingPort()
                    AdbAuthWait.clearUnanswered()
                    ManagerActivityLog.log(context, "Service started via ADB on port $activePort")
                    ShizukuStateMachine.update()
                    Timber.tag(TAG).i("Shizuku service started successfully via ADB on port %d", activePort)
                    HeadlessLogger.i(LOG, "starter ran on port $activePort")
                }
            }
        } catch (e: Exception) {
            if (e is AdbAuthPendingException) stoodDown = true
            if (e is AdbServerPinException) pinRefused = true
            if (e !is AdbServerPinMissingException) AdbServerPin.clearPendingPort()
            if (e is AdbTlsRequiredException) {
                HeadlessLogger.w(LOG, "port $port answered without TLS; adbd reports tcp port ${EnvironmentUtils.getAdbTcpPort()}")
            }
            Timber.tag(TAG).e(e, "startAdb failed on port %d: %s", port, e.message)
            HeadlessLogger.w(LOG, "failed on port $port: ${HeadlessLogger.brief(e)}")
            if (e is SSLException && (e.message?.contains("protocol version") == true || e is javax.net.ssl.SSLProtocolException)) {
                withContext(Dispatchers.Main) {
                    val activity = context.getActivity()
                    if (activity != null && !activity.isFinishing) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.adb_error_ssl_title)
                            .setMessage(R.string.adb_error_ssl_message)
                            .setPositiveButton(R.string.adb_error_ssl_button_reset) { _, _ ->
                                SettingsPage.Developer.Options.launch(activity)
                            }.setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        // Fallback for non-activity context
                        val themedContext = ContextThemeWrapper(context, R.style.AppTheme)
                        Toast.makeText(themedContext, R.string.adb_error_ssl_message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (e !is CancellationException && !e.isExpectedAdbError()) {
                Sentry.captureException(e)
            }
            throw e
        } finally {
            AdbAuthWait.starts.end()
            if (!stoodDown && !pinRefused && ShizukuSettings.getAutoDisableUsbDebugging() && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                AdbServerPin.putSetting(context, "adb_wifi_enabled", 0)
                HeadlessLogger.i(LOG, "auto-disable: turned wireless debugging off")
            }
        }
    }

    /** @return false only if adbd's authorisation dialog was raised and not accepted. */
    suspend fun stopTcp(
        context: Context,
        port: Int,
    ): Boolean {
        if (port !in 1..65535 || AdbAuthWait.isWaiting()) return true
        val result =
            runCatching {
                val cr = context.contentResolver
                if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                    AdbServerPin.putSetting(context, Settings.Global.ADB_ENABLED, 1)
                    Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                }

                if (!EnvironmentUtils.isAdbEnabled()) throw IllegalStateException("ADB is not enabled")

                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+")
                withContext(Dispatchers.IO) {
                    AdbClient("127.0.0.1", port, key, allowLegacyAuth = { isLegacyAuthPort(port) }).use { client ->
                        connectWithRetry(client, port)
                        client.command("usb:")
                    }
                }
            }
        result.onFailure {
            if (it is CancellationException) throw it
            HeadlessLogger.w(LOG, "leaving tcp mode on port $port failed: ${HeadlessLogger.brief(it)}")
            if (it !is CancellationException && !it.isExpectedAdbError(includeIllegalState = true)) {
                Sentry.captureException(it)
            }
            if (EnvironmentUtils.getAdbTcpPort() > 0) {
                ShizukuStateMachine.update()
                withContext(Dispatchers.Main) {
                    val errorMsg =
                        when (it) {
                            is AdbKeyException -> context.getString(R.string.adb_error_key_store)
                            else -> it.message
                        }
                    Toast
                        .makeText(context, context.getString(R.string.adb_error_stop_tcp) + ". ${errorMsg?.take(80)}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
        return result.exceptionOrNull() !is AdbAuthTimeoutException
    }

    private suspend fun connectWithRetry(
        client: AdbClient,
        port: Int,
    ) = coroutineScope {
        // connect() blocks in a socket read that coroutine cancellation cannot interrupt, and it can
        // now wait minutes for the authorisation dialog. Close the socket when this scope is
        // cancelled so a cancelled start does not leave a connection (and the AdbAuthWait gate) held.
        val finished = AtomicBoolean(false)
        val watcher =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) client.close()
                }
            }
        try {
            var delayTime = 500L
            val maxAttempts = 8
            for (attempt in 1..maxAttempts) {
                try {
                    if (attempt > 1) {
                        delay(delayTime)
                        delayTime = (delayTime * 1.5).toLong().coerceAtMost(3000L) // Exponential backoff up to 3s
                    }
                    Timber.tag(TAG).d("Connecting to ADB attempt %d/%d (port=%d)", attempt, maxAttempts, port)
                    client.connect()
                    Timber.tag(TAG).d("Connected successfully on attempt %d", attempt)
                    break
                } catch (e: Exception) {
                    // A cancelled start closes the socket (see the watcher above), which surfaces
                    // here as an I/O failure; report it as the cancellation it is.
                    ensureActive()
                    Timber.tag(TAG).w(e, "Connection attempt %d/%d failed: %s", attempt, maxAttempts, e.message)
                    HeadlessLogger.w(LOG, "connect $attempt/$maxAttempts to port $port failed: ${HeadlessLogger.brief(e)}")
                    if (
                        attempt == maxAttempts ||
                        e is CancellationException ||
                        // Reconnecting would raise another "Allow USB debugging?" dialog.
                        e is AdbAuthTimeoutException ||
                        // A CAS loser must stand down, not reconnect: a retry could claim the
                        // slot the instant its owner releases it and offer a second key, or —
                        // once the owner's key is accepted — start a second server in parallel.
                        e is AdbAuthPendingException ||
                        // The peer would not use TLS on a port that requires it; it will not change.
                        e is AdbTlsRequiredException ||
                        e is AdbServerPinException
                    ) {
                        throw e
                    }
                }
            }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }
}
