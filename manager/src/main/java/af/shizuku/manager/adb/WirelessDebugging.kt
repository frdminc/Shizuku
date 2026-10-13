package af.shizuku.manager.adb

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.NotifAttemptActivity
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.receiver.WifiRestoreReceiver
import af.shizuku.manager.utils.HeadlessLogger
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.WirelessDebuggingWatchWorker
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Wireless debugging cannot come up without the user: there is no Wi-Fi, or the Wi-Fi network is
 * not trusted for it. Retrying would only repeat the same wait (or the system's "Allow wireless
 * debugging on this network?" prompt), so the start stops. Wi-Fi connecting resumes a start that
 * had none ([WirelessDebugging.armResume]); a refused network gets one automatic prompt per boot,
 * and wireless debugging coming on continues the start ([WirelessDebugging.watch]). A start by hand
 * always tries again.
 */
class WirelessDebuggingBlockedException(
    val reason: WirelessDebugging.Blocked,
    message: String,
) : Exception(message)

/**
 * A TCP-mode restore waiting for Wi-Fi found the TCP port it was reopening listening again (adbd
 * restarted, or `adb tcpip` from a computer): the start goes through it instead, with no Wi-Fi.
 */
class TcpPortOpenAgainException : Exception("the tcp port is open again")

/**
 * Brings wireless debugging up for a background start and finds its TLS port, the order a user
 * would follow by hand: Wi-Fi first, then adb_wifi_enabled=1, then mDNS.
 *
 * The system writes adb_wifi_enabled back to 0 when Wi-Fi is not connected (at boot it is usually
 * not up yet) or WifiManager does not know its BSSID yet, when Wi-Fi goes away or changes network,
 * and when the network is not trusted for wireless debugging, in which case it shows "Allow
 * wireless debugging on this network?" and only "Always allow" trusts it (AOSP
 * AdbDebuggingManager). A paired or "Always allow" key in adb_keys authorises the TLS connection
 * after a reboot, so nothing needs pairing again.
 *
 * At most one automatic prompt per boot: the moment a write of 1 comes back 0 on the same Wi-Fi
 * the prompt is recorded (durably, with the boot count), and from then on only a start by hand
 * made after it, HEADLESS_START with force, the user turning wireless debugging on, or the next
 * boot write 1 again. Without location permission no Wi-Fi network has a stable identity (each
 * connection gets a new handle, and handles restart with every boot), so the record is per boot.
 *
 * One UI shows no prompt while the keyguard is locked: an untrusted network's write is refused in
 * silence (s24: "startConfirmationForNetwork: isLockScreenMode", no WifiDebuggingActivity). Trust is
 * per access point (BSSID), so on a mesh the same network can be allowed on one node and not the
 * next. Such a refusal is no prompt: the next unlock writes once more, which the user sees, and
 * that is this boot's prompt; until then a quiet retry every [QUIET_RETRY_MIN] minutes finds an
 * allowed node. AOSP queues its dialog for the unlock, so elsewhere a locked refusal stays the
 * prompt and the unlock only checks whether it was allowed.
 */
object WirelessDebugging {
    private const val LOG = "WirelessDebugging"
    private const val SETTING = "adb_wifi_enabled"

    // The restore's own notice. Not the start notification (ShizukuReceiverStarter renders that
    // from WorkInfo); this one says what only the user can do.
    const val NOTICE_ID = 1453
    private const val CHANNEL_ID = "AdbStartWorker"

    // This boot's prompt: the boot count, and when (wall clock, AdbAuthWait.clockMs) it was raised.
    private const val KEY_PROMPTED_BOOT = "wadb_restore_prompted_boot"
    private const val KEY_PROMPTED_AT = "wadb_restore_prompted_at"

    // A write of 1 made this boot whose outcome no run saw (the run died): counts as the prompt.
    private const val KEY_WRITE_PENDING_BOOT = "wadb_restore_write_pending_boot"

    // Whether that write was made with the keyguard locked (One UI then shows no prompt).
    private const val KEY_WRITE_PENDING_LOCKED = "wadb_restore_write_pending_locked"

    // A restore stopped for want of Wi-Fi this boot (resumed when Wi-Fi connects).
    private const val KEY_NO_WIFI_BOOT = "wadb_restore_no_wifi_boot"

    // Set (committed) before a start that found wireless debugging off turns it on, cleared when it
    // is put back: a run that dies in between leaves it, and the next one then knows it was off.
    private const val KEY_TURNED_ON = "wadb_restore_turned_on"

    // When WifiRestoreReceiver resumed a restore on Wi-Fi that had just connected (elapsedMs).
    private const val KEY_WIFI_SEEN_AT = "wadb_restore_wifi_seen_at"
    private const val KEY_WIFI_SEEN_BOOT = "wadb_restore_wifi_seen_boot"

    // A write refused while locked with no prompt shown (One UI) this boot: the next unlock may
    // write once more, and that write is this boot's prompt.
    private const val KEY_SILENT_BOOT = "wadb_restore_silent_refusal_boot"

    // The write made unlocked after a silent refusal is out and not yet judged (committed with
    // its pending marker): no quiet retry or locked stand-down meanwhile. Its outcome is judged by
    // its own lock state; a write lost with Wi-Fi forgets it.
    private const val KEY_UNLOCK_WRITE_BOOT = "wadb_restore_unlock_write_boot"

    // A restore stopped for an untrusted network this boot and is watching (see [watch]).
    private const val KEY_WATCH_BOOT = "wadb_restore_watch_boot"

    // The watch's unique works: adb_wifi_enabled changing, and the quiet retry while locked.
    const val WATCH_WORK = "wadb_restore_watch"
    const val QUIET_WORK = "wadb_restore_quiet_retry"
    const val QUIET_RETRY_MIN = 20L

    // After a no-Wi-Fi stop in TCP mode, the TCP port is probed again a few times: adbd may listen
    // again without Wi-Fi (it was restarting, or `adb tcpip` from a computer). Armed once per stop
    // (this boot's record), ended by a success, a start that has Wi-Fi, or the next boot.
    const val PORT_RECHECK_WORK = "tcp_port_recheck"
    private const val KEY_PORT_RECHECK_BOOT = "tcp_port_recheck_boot"

    // Seconds before each recheck: about 0.5, 1.5, 3.5, 7.5 and 13.5 minutes after the stop. After
    // the last, Wi-Fi connecting, the watchdog, the boot retry and a start by hand remain.
    private val PORT_RECHECK_DELAYS_S = longArrayOf(30, 60, 120, 240, 360)
    val PORT_RECHECKS: Int get() = PORT_RECHECK_DELAYS_S.size

    // Earlier builds' keys, forgotten at boot.
    private val OLD_KEYS = listOf("wadb_restore_blocked", "wadb_restore_blocked_boot", "wadb_restore_blocked_network")

    // Rounds of "Wi-Fi, turn it on, discover", each started again when Wi-Fi changes under it.
    private const val MAX_ROUNDS = 3

    // mDNS can announce many ports; verify serially, so keep only the newest candidates.
    private const val MAX_PENDING_PORTS = 16

    enum class Blocked { NO_WIFI, UNTRUSTED_NETWORK }

    /** Starts mDNS discovery of the wireless debugging TLS port; the returned function stops it. */
    fun interface PortDiscovery {
        fun start(
            context: Context,
            onPort: (Int) -> Unit,
        ): () -> Unit

        /** Fake discoveries opt in explicitly: existing scenarios must never scan real ports. */
        fun scan(
            context: Context,
            onResult: (ScanResult) -> Unit,
        ): (() -> Unit)? = null
    }

    data class ScanResult(
        val port: Int,
        val client: AdbClient?,
    )

    private fun realDiscovery() =
        object : PortDiscovery {
            override fun start(
                context: Context,
                onPort: (Int) -> Unit,
            ) = startMdns(context, onPort)

            override fun scan(
                context: Context,
                onResult: (ScanResult) -> Unit,
            ) =
                if (available()) LoopbackAdbScan.start(context, onResult) else null
        }

    @Volatile
    internal var scanBudgetMs = 12_000L

    // Test seams (FakeWorld). Real defaults.
    @Volatile
    internal var discovery: PortDiscovery = realDiscovery()

    private fun startMdns(
        context: Context,
        onPort: (Int) -> Unit,
    ): () -> Unit {
        // No wireless debugging before Android 11: nothing to find.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return {}
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { p -> if (p > 0) onPort(p) }
        mdns.start()
        return { mdns.stop() }
    }

    /** How long a start waits for Wi-Fi to connect. */
    @Volatile
    internal var wifiWaitMs = 120_000L

    /** How long a start waits for the user, an unlock and the answer together. */
    @Volatile
    internal var userWaitMs = 300_000L

    // Everything one start may wait before it connects. WorkManager stops a background job at 10
    // minutes, and the connection after this may wait AdbAuthWait.TIMEOUT_MS (5 minutes) for
    // adbd's dialog, so a run that is not a foreground worker keeps to 10 - 5 - 1 minutes; a
    // stopped run is re-run, and would wait again. A foreground run has no such limit.
    @Volatile
    internal var backgroundBudgetMs = 240_000L

    @Volatile
    internal var foregroundBudgetMs = 480_000L

    /** How long mDNS may take to find the port once wireless debugging is on. */
    @Volatile
    internal var discoveryMs = 15_000L

    /**
     * How old a Wi-Fi network must be before wireless debugging is turned on. Turned on before
     * WifiManager knows the BSSID, the system turns it off again with no prompt, which would look
     * like a refusal; after this, a 0 counts as one.
     */
    @Volatile
    internal var wifiSettleMs = 5_000L

    @Volatile
    internal var pollMs = 250L

    /** How often a TCP-mode restore waiting for Wi-Fi probes the TCP port again. */
    @Volatile
    internal var portRecheckMs = 3_000L

    @Volatile
    internal var toggleGapMs = 500L

    @Volatile
    internal var elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }

    /** Time since boot: no Wi-Fi network is older. */
    @Volatile
    internal var uptimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    internal fun resetForTesting() {
        unlockWatch.set(null)
        discovery = realDiscovery()
        scanBudgetMs = 12_000L
        wifiWaitMs = 120_000L
        userWaitMs = 300_000L
        backgroundBudgetMs = 240_000L
        foregroundBudgetMs = 480_000L
        discoveryMs = 15_000L
        wifiSettleMs = 5_000L
        pollMs = 250L
        portRecheckMs = 3_000L
        toggleGapMs = 500L
        elapsedMs = { SystemClock.elapsedRealtime() }
        uptimeMs = { SystemClock.elapsedRealtime() }
    }

    /** Wireless debugging can restore the TCP port here: Android 11+ with TLS adb. */
    fun available(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching {
                af.shizuku.manager.utils.EnvironmentUtils
                    .isTlsSupported()
            }.getOrDefault(false)

    fun setting(context: Context): Int = runCatching { Settings.Global.getInt(context.contentResolver, SETTING, 0) }.getOrDefault(-1)

    private fun put(
        context: Context,
        value: Int,
    ) {
        AdbServerPin.putSetting(context, SETTING, value)
    }

    private fun bootCount(context: Context): Int = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)

    private fun prefs() = ShizukuSettings.getPreferences()

    private fun thisBoot(
        context: Context,
        key: String,
    ): Boolean = runCatching { prefs().getInt(key, -1) == bootCount(context) }.getOrDefault(false)

    fun locked(context: Context): Boolean = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    // The One UI that refuses an untrusted network's write in silence while locked, as
    // ro.build.version.oneui. Evidence: s24 SM-S921U1, One UI 8.5 (80500), Android 16, build
    // BP4A.251205.006.S921U1UES6DZH3, logcat 2026-10-06 03:01:57: "AdbDebuggingManager:
    // startConfirmationForNetwork: isLockScreenMode", no WifiDebuggingActivity, adb_wifi_enabled
    // back to 0 within ~90 ms. Older or unknown One UI is unproven and takes AOSP's path.
    private const val ONE_UI_SILENT_WHEN_LOCKED = 80500

    /** ro.build.version.oneui (80500 for One UI 8.5); 0 if absent or unreadable. */
    fun oneUiVersion(): Int =
        runCatching {
            android.os.SystemProperties
                .get("ro.build.version.oneui", "")
                .toIntOrNull()
        }.getOrNull() ?: 0

    /**
     * One UI refuses an untrusted network's write in silence while the keyguard is locked (no
     * WifiDebuggingActivity, so nothing to answer after the unlock either). AOSP shows its dialog
     * once the keyguard goes.
     */
    fun silentWhenLocked(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) && oneUiVersion() >= ONE_UI_SILENT_WHEN_LOCKED

    // Wi-Fi.

    /**
     * A connected Wi-Fi network, whether or not it has internet or is metered (null: none). Not a
     * VPN that runs over Wi-Fi.
     */
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            fun isWifi(n: Network?): Boolean {
                val caps = n?.let { cm.getNetworkCapabilities(it) }
                return caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            // The active network first: a phone can report more than one Wi-Fi network (a
            // secondary or local-only one), and allNetworks' order is not stable, so "the first
            // Wi-Fi network" could alternate and read as a network change on every check.
            cm.activeNetwork?.takeIf { isWifi(it) }
                ?:
                @Suppress("DEPRECATION")
                cm.allNetworks.firstOrNull { isWifi(it) }
        }.getOrNull()
    }

    /** True while [network] is still a connected Wi-Fi network, however other networks are listed. */
    private fun isConnectedWifi(
        context: Context,
        network: Network,
    ): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .getOrDefault(false)
    }

    // Any Wi-Fi. Not NOT_METERED or VALIDATED (WorkManager's UNMETERED/CONNECTED constraints imply
    // them), nor INTERNET: wireless debugging works on a metered Wi-Fi and on one with no internet.
    // The builder's defaults keep NOT_VPN, TRUSTED and NOT_RESTRICTED.
    internal fun wifiRequest(): NetworkRequest =
        NetworkRequest
            .Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

    /**
     * Waits up to [timeoutMs] for Wi-Fi with a network callback; null if none connected. While it
     * waits, [check] runs every [checkEveryMs] (not at the end) and may end the wait by throwing.
     */
    suspend fun awaitWifi(
        context: Context,
        timeoutMs: Long,
        checkEveryMs: Long = 0L,
        check: (suspend () -> Unit)? = null,
    ): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val available = CompletableDeferred<Network>()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    available.complete(network)
                }
            }
        cm.registerNetworkCallback(wifiRequest(), callback)
        try {
            // Registered first, so a network that connects in between is not missed.
            wifiNetwork(context)?.let { return it }
            if (check == null || checkEveryMs <= 0L) return withTimeoutOrNull(timeoutMs.coerceAtLeast(0L)) { available.await() }
            val deadline = elapsedMs() + timeoutMs
            while (true) {
                val left = deadline - elapsedMs()
                if (left <= 0L) return null
                withTimeoutOrNull(minOf(left, checkEveryMs)) { available.await() }?.let { return it }
                if (elapsedMs() < deadline) check()
            }
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    // This boot's prompt and the no-Wi-Fi stop, durable so that the watchdog, the boot retry,
    // HEADLESS_START and WorkManager's re-runs stand down at once instead of waiting or prompting.

    /** When this boot's automatic network prompt was raised (AdbAuthWait.clockMs), or null. */
    fun promptedAt(context: Context): Long? =
        if (thisBoot(context, KEY_PROMPTED_BOOT)) runCatching { prefs().getLong(KEY_PROMPTED_AT, 0L) }.getOrNull() else null

    private fun markPrompted(context: Context) {
        runCatching {
            prefs()
                .edit()
                .putInt(KEY_PROMPTED_BOOT, bootCount(context))
                .putLong(KEY_PROMPTED_AT, AdbAuthWait.clockMs())
                .remove(KEY_WRITE_PENDING_BOOT)
                .remove(KEY_WRITE_PENDING_LOCKED)
                .commit()
        }
    }

    /** A start by hand made after the prompt, or HEADLESS_START with force: it may ask again. */
    fun clearPrompted(context: Context) {
        runCatching {
            prefs()
                .edit()
                .remove(KEY_PROMPTED_BOOT)
                .remove(KEY_PROMPTED_AT)
                .remove(KEY_WRITE_PENDING_BOOT)
                .remove(KEY_WRITE_PENDING_LOCKED)
                .remove(KEY_SILENT_BOOT)
                .remove(KEY_UNLOCK_WRITE_BOOT)
                .commit()
        }
        cancelNotice(context)
    }

    /**
     * This boot's writes were refused in silence while locked, and no write has been made unlocked
     * since (that one is the prompt).
     */
    fun silentRefusalPending(context: Context): Boolean =
        thisBoot(context, KEY_SILENT_BOOT) && promptedAt(context) == null && !thisBoot(context, KEY_UNLOCK_WRITE_BOOT)

    /** A quiet retry's start may write now: One UI, still locked, after a silent refusal. */
    fun quietAllowed(context: Context): Boolean = silentWhenLocked() && locked(context) && silentRefusalPending(context)

    private fun markSilentRefusal(context: Context) {
        runCatching {
            prefs()
                .edit()
                .putInt(KEY_SILENT_BOOT, bootCount(context))
                .remove(KEY_WRITE_PENDING_BOOT)
                .remove(KEY_WRITE_PENDING_LOCKED)
                .commit()
        }
    }

    private fun setWritePending(
        context: Context,
        pending: Boolean,
        locked: Boolean = false,
        unlockWrite: Boolean = false,
    ) {
        runCatching {
            val edit = prefs().edit()
            if (pending) {
                edit.putInt(KEY_WRITE_PENDING_BOOT, bootCount(context)).putBoolean(KEY_WRITE_PENDING_LOCKED, locked)
                if (unlockWrite) edit.putInt(KEY_UNLOCK_WRITE_BOOT, bootCount(context))
            } else {
                edit.remove(KEY_WRITE_PENDING_BOOT).remove(KEY_WRITE_PENDING_LOCKED)
            }
            edit.commit()
        }
    }

    private fun noWifiThisBoot(context: Context): Boolean = thisBoot(context, KEY_NO_WIFI_BOOT)

    private fun markNoWifi(context: Context) {
        runCatching {
            prefs()
                .edit()
                .putInt(KEY_NO_WIFI_BOOT, bootCount(context))
                .apply()
        }
    }

    /**
     * A start succeeded, or one may go ahead: forgets the no-Wi-Fi stop, its Wi-Fi trigger and the
     * restore's notice ([keepNotice]: not the notice). This boot's prompt stays (see [clearPrompted]).
     */
    fun clearNoWifi(
        context: Context,
        keepNotice: Boolean = false,
    ) {
        if (runCatching { prefs().contains(KEY_NO_WIFI_BOOT) }.getOrDefault(false)) {
            runCatching {
                prefs()
                    .edit()
                    .remove(KEY_NO_WIFI_BOOT)
                    .apply()
            }
            disarmResume(context)
        }
        endPortRecheck(context)
        if (!keepNotice) cancelNotice(context)
    }

    // The TCP port's rechecks after a no-Wi-Fi stop (see PORT_RECHECK_WORK).

    /**
     * A TCP-mode start stopped for want of Wi-Fi: probe [port] again a few times, in case adbd
     * listens on it again without Wi-Fi. Once per stop: a stand-down while there is still no Wi-Fi
     * (the watchdog, the boot retry) arms nothing new.
     */
    fun armPortRecheck(
        context: Context,
        port: Int,
    ) {
        if (port !in 1..65535 || thisBoot(context, KEY_PORT_RECHECK_BOOT)) return
        runCatching { prefs().edit().putInt(KEY_PORT_RECHECK_BOOT, bootCount(context)).commit() }
        enqueuePortRecheck(context, port, 0, ExistingWorkPolicy.REPLACE)
        HeadlessLogger.i(
            LOG,
            "probing tcp port $port again $PORT_RECHECKS times over the next ${PORT_RECHECK_DELAYS_S.sum() / 60} min, " +
                "in case adbd listens on it again without Wi-Fi",
        )
    }

    private fun enqueuePortRecheck(
        context: Context,
        port: Int,
        done: Int,
        policy: ExistingWorkPolicy,
    ) {
        val request =
            OneTimeWorkRequestBuilder<WirelessDebuggingWatchWorker>()
                .setInitialDelay(PORT_RECHECK_DELAYS_S[done], TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        WirelessDebuggingWatchWorker.KEY_KIND to WirelessDebuggingWatchWorker.KIND_PORT,
                        WirelessDebuggingWatchWorker.KEY_PORT to port,
                        WirelessDebuggingWatchWorker.KEY_CHECK to done + 1,
                    ),
                ).build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(PORT_RECHECK_WORK, policy, request) }
            .onFailure { HeadlessLogger.w(LOG, "cannot queue the tcp port recheck: ${HeadlessLogger.brief(it)}") }
    }

    /** A success, a start that goes ahead with Wi-Fi, or a new boot: no more rechecks. */
    private fun endPortRecheck(context: Context) {
        if (!runCatching { prefs().contains(KEY_PORT_RECHECK_BOOT) }.getOrDefault(false)) return
        runCatching { prefs().edit().remove(KEY_PORT_RECHECK_BOOT).commit() }
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(PORT_RECHECK_WORK) }
    }

    /**
     * Recheck [check] of [port]: if it listens, an ordinary background start (every one-prompt and
     * single-start guard applies) goes through it; the next recheck is queued either way, so a start
     * that fails is tried again, and a success cancels it.
     */
    internal suspend fun onPortRecheck(
        context: Context,
        port: Int,
        check: Int,
    ) {
        // Ended (a success, a start with Wi-Fi) or left over from an earlier boot.
        if (!thisBoot(context, KEY_PORT_RECHECK_BOOT)) return
        ShizukuStateMachine.update()
        if (ShizukuStateMachine.isRunning() || !ShizukuSettings.getTcpMode() || ShizukuSettings.getTcpPort() != port) {
            runCatching { prefs().edit().remove(KEY_PORT_RECHECK_BOOT).commit() }
            return
        }
        val open = withContext(Dispatchers.IO) { AdbPortProber.isPortOpen(port, 600) }
        if (check < PORT_RECHECKS) enqueuePortRecheck(context, port, check, ExistingWorkPolicy.APPEND_OR_REPLACE)
        when {
            open -> {
                HeadlessLogger.i(LOG, "tcp port $port is open again; starting through it (recheck $check of $PORT_RECHECKS)")
                ShizukuReceiverStarter.start(context)
            }
            check < PORT_RECHECKS -> HeadlessLogger.i(LOG, "tcp port $port still closed (recheck $check of $PORT_RECHECKS)")
            else ->
                HeadlessLogger.i(
                    LOG,
                    "tcp port $port still closed after $PORT_RECHECKS checks; waiting for Wi-Fi to connect, the watchdog, or a start by hand",
                )
        }
    }

    /** BOOT_COMPLETED: everything here is per boot. */
    fun onBoot(context: Context) {
        runCatching {
            val edit = prefs().edit()
            (
                OLD_KEYS +
                    listOf(
                        KEY_PROMPTED_BOOT,
                        KEY_PROMPTED_AT,
                        KEY_WRITE_PENDING_BOOT,
                        KEY_NO_WIFI_BOOT,
                        KEY_WIFI_SEEN_AT,
                        KEY_WIFI_SEEN_BOOT,
                        KEY_SILENT_BOOT,
                        KEY_UNLOCK_WRITE_BOOT,
                        KEY_WRITE_PENDING_LOCKED,
                        KEY_WATCH_BOOT,
                        KEY_PORT_RECHECK_BOOT,
                    )
            ).forEach { edit.remove(it) }
            edit.apply()
        }
        disarmResume(context)
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(PORT_RECHECK_WORK) }
        // Every watch also checks the boot count, so one that outlives this changes nothing.
        cancelWatch(context)
        cancelNotice(context)
    }

    /** What stops a start that has not been allowed to ask (see [standDownReason]); null: nothing. */
    fun blocked(context: Context): Blocked? =
        when {
            promptedAt(context) != null && setting(context) != 1 -> Blocked.UNTRUSTED_NETWORK
            silentRefusalPending(context) && setting(context) != 1 -> Blocked.UNTRUSTED_NETWORK
            noWifiThisBoot(context) -> Blocked.NO_WIFI
            else -> null
        }

    /**
     * Why a start should not try wireless debugging now, or null if it should. Unattended starts
     * ([explicit] false) stand down after this boot's prompt while wireless debugging is still off,
     * and while there is still no Wi-Fi after a no-Wi-Fi stop. A start by hand may ask again,
     * unless it is a WorkManager re-run of a request made before the prompt ([requestedAt]).
     * After a silent refusal (One UI, locked) unattended starts stand down while still locked; the
     * unlock, or any start made unlocked, may write once more. Only the [quiet] retry writes locked.
     */
    fun standDownReason(
        context: Context,
        explicit: Boolean,
        requestedAt: Long,
        quiet: Boolean = false,
    ): Pair<Blocked, String>? {
        val prompted = promptedAt(context)
        if (prompted != null && setting(context) != 1) {
            if (!explicit) {
                return Blocked.UNTRUSTED_NETWORK to
                    "this Wi-Fi network is still not trusted for wireless debugging (one automatic prompt per boot); " +
                    "waiting for a start by hand, wireless debugging turned on, or the next boot"
            }
            if (requestedAt <= prompted) {
                return Blocked.UNTRUSTED_NETWORK to
                    "this start by hand was requested before this boot's network prompt and has asked already; waiting for a new start by hand"
            }
        }
        if (!explicit && !quiet && silentRefusalPending(context) && setting(context) != 1 && locked(context)) {
            return Blocked.UNTRUSTED_NETWORK to
                "wireless debugging was refused while locked, when this phone shows no prompt; " +
                "waiting for an unlock (one more try, which the system shows), the quiet retry, or wireless debugging turned on"
        }
        if (!explicit && noWifiThisBoot(context) && wifiNetwork(context) == null) {
            return Blocked.NO_WIFI to "still no Wi-Fi; waiting for Wi-Fi to connect before turning wireless debugging on"
        }
        return null
    }

    // The watch: a start that stopped for an untrusted network continues by itself when wireless
    // debugging comes on (the user's "Allow", the Settings switch, a shell), and after a silent
    // refusal when the phone is unlocked. Per boot; a success ends it.

    /** A start stopped for an untrusted network (or stood down for one): watch for what ends it. */
    fun watch(context: Context) {
        val started = !thisBoot(context, KEY_WATCH_BOOT)
        if (started) runCatching { prefs().edit().putInt(KEY_WATCH_BOOT, bootCount(context)).commit() }
        enqueueWatch(context, ExistingWorkPolicy.KEEP)
        val silent = silentRefusalPending(context)
        val locked = locked(context)
        if (silent || (locked && promptedAt(context) != null)) watchUnlock(context)
        val quiet = silent && locked && silentWhenLocked()
        if (quiet) enqueueQuiet(context, ExistingWorkPolicy.KEEP)
        if (started) {
            HeadlessLogger.i(
                LOG,
                "continuing by itself when wireless debugging is turned on" +
                    (
                        if (silent) {
                            "; one more try after the next unlock"
                        } else if (locked) {
                            "; checking again after the next unlock (no new write)"
                        } else {
                            ""
                        }
                    ) +
                    (if (quiet) "; meanwhile a quiet retry every $QUIET_RETRY_MIN min while locked" else ""),
            )
        }
    }

    /** A start succeeded (or the server is up): nothing left to watch for. */
    fun succeeded(context: Context) {
        unwatchUnlock(context)
        val watching = runCatching { prefs().contains(KEY_WATCH_BOOT) || prefs().contains(KEY_SILENT_BOOT) }.getOrDefault(false)
        if (!watching) return
        runCatching {
            prefs()
                .edit()
                .remove(KEY_WATCH_BOOT)
                .remove(KEY_SILENT_BOOT)
                .remove(KEY_UNLOCK_WRITE_BOOT)
                .commit()
        }
        cancelWatch(context)
    }

    private fun watchRequest(): androidx.work.OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<WirelessDebuggingWatchWorker>()
            // JobScheduler runs it once after adb_wifi_enabled changes (ContentObserverController,
            // as AOSP's own ProvisionObserver does for Settings.Global.DEVICE_PROVISIONED).
            .setConstraints(Constraints.Builder().addContentUriTrigger(Settings.Global.getUriFor(SETTING), false).build())
            .setInputData(workDataOf(WirelessDebuggingWatchWorker.KEY_KIND to WirelessDebuggingWatchWorker.KIND_WATCH))
            .build()

    private fun enqueueWatch(
        context: Context,
        policy: ExistingWorkPolicy,
    ) {
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WATCH_WORK, policy, watchRequest()) }
            .onFailure { HeadlessLogger.w(LOG, "cannot watch wireless debugging: ${HeadlessLogger.brief(it)}") }
    }

    private fun enqueueQuiet(
        context: Context,
        policy: ExistingWorkPolicy,
    ) {
        val request =
            OneTimeWorkRequestBuilder<WirelessDebuggingWatchWorker>()
                .setInitialDelay(QUIET_RETRY_MIN, TimeUnit.MINUTES)
                .setInputData(workDataOf(WirelessDebuggingWatchWorker.KEY_KIND to WirelessDebuggingWatchWorker.KIND_QUIET))
                .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(QUIET_WORK, policy, request) }
            .onFailure { HeadlessLogger.w(LOG, "cannot queue the quiet retry: ${HeadlessLogger.brief(it)}") }
    }

    private fun cancelQuiet(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(QUIET_WORK) }
    }

    private fun cancelWatch(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WATCH_WORK) }
        cancelQuiet(context)
    }

    private fun watching(context: Context): Boolean = thisBoot(context, KEY_WATCH_BOOT)

    // True (and the watch ended) if the server is up.
    private fun runningNow(context: Context): Boolean {
        ShizukuStateMachine.update()
        if (!ShizukuStateMachine.isRunning()) return false
        succeeded(context)
        return true
    }

    /** adb_wifi_enabled changed. Content-trigger work runs once, so it queues the next watch. */
    internal fun onWatchFired(context: Context) {
        if (!watching(context) || runningNow(context)) return
        // Queued first, behind this run, so a start that succeeds at once cancels it too.
        enqueueWatch(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        if (setting(context) == 1) {
            HeadlessLogger.i(LOG, "wireless debugging was turned on; continuing the ADB restore")
            ShizukuReceiverStarter.start(context)
        }
    }

    /**
     * A start is about to use wireless debugging itself: its own writes are no news. It watches
     * again if it stops for an untrusted network ([watch]); the quiet retry keeps its turn.
     */
    fun pauseWatch(context: Context) {
        if (watching(context)) runCatching { WorkManager.getInstance(context).cancelUniqueWork(WATCH_WORK) }
    }

    /**
     * One UI only, after a silent refusal: while still locked, write again in case the phone has
     * moved to an access point that is allowed (on one that is not, the write is refused in
     * silence again). Unlocked, the unlock's one visible write is due instead.
     */
    internal fun onQuietRetry(context: Context) {
        if (!watching(context) || !silentRefusalPending(context) || !silentWhenLocked() || runningNow(context)) return
        if (!locked(context)) {
            HeadlessLogger.i(LOG, "quiet retry: unlocked since the silent refusal; trying once more, which the system shows")
            ShizukuReceiverStarter.start(context)
            return
        }
        HeadlessLogger.i(LOG, "quiet retry while locked: turning wireless debugging on again (refused in silence again unless this access point is allowed)")
        enqueueQuiet(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        ShizukuReceiverStarter.start(context, quiet = true)
    }

    // ACTION_USER_PRESENT cannot be declared in the manifest, so the unlock is watched only while
    // this process lives; any start made unlocked later does the same.
    private val unlockWatch = AtomicReference<BroadcastReceiver?>()

    private fun watchUnlock(context: Context) {
        val app = context.applicationContext ?: context
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action != Intent.ACTION_USER_PRESENT) return
                    unwatchUnlock(app)
                    onUnlock(app)
                }
            }
        if (!unlockWatch.compareAndSet(null, receiver)) return
        runCatching { ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { unlockWatch.compareAndSet(receiver, null) }
    }

    private fun unwatchUnlock(context: Context) {
        val receiver = unlockWatch.getAndSet(null) ?: return
        runCatching { (context.applicationContext ?: context).unregisterReceiver(receiver) }
    }

    private fun onUnlock(context: Context) {
        HeadlessLogger.init(context)
        if (!watching(context)) return
        if (silentRefusalPending(context)) {
            // The quiet retry stays until this start makes its write (Session.turnOn): if the
            // start is turned away, the retry coming due unlocked makes it instead.
            HeadlessLogger.i(LOG, "unlocked after a silent refusal; trying once more: the system shows its prompt now, and it is this boot's one")
            ShizukuReceiverStarter.start(context)
        } else if (setting(context) == 1) {
            HeadlessLogger.i(LOG, "unlocked, and wireless debugging is on; continuing the ADB restore")
            ShizukuReceiverStarter.start(context)
        } else {
            HeadlessLogger.i(LOG, "unlocked; wireless debugging is still off: the system's prompt is the user's to answer, nothing is written again")
        }
    }

    /**
     * A TCP-mode start succeeded without this start touching wireless debugging: if an earlier run
     * turned it on and died before turning it off, turn it off now.
     */
    fun afterTcpStart(
        context: Context,
        note: (String) -> Unit,
    ) {
        if (!runCatching { prefs().getBoolean(KEY_TURNED_ON, false) }.getOrDefault(false)) return
        if (setting(context) == 1) {
            put(context, 0)
            note("an earlier start turned wireless debugging on and did not turn it off; turned it off (adb_wifi_enabled=${setting(context)})")
        }
        runCatching { prefs().edit().remove(KEY_TURNED_ON).commit() }
    }

    // The resume trigger: a network callback with a PendingIntent outlives this process, so Wi-Fi
    // connecting restarts a restore that had none even if nothing else is running.

    private fun resumeIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WifiRestoreReceiver::class.java),
            // The system adds the network to the intent it sends, so it must be mutable; it is
            // explicit, so nothing else can receive it.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )

    /** Only for a no-Wi-Fi stop with no prompt this boot (a prompted boot never resumes by itself). */
    fun armResume(context: Context) {
        if (!noWifiThisBoot(context) || promptedAt(context) != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.registerNetworkCallback(wifiRequest(), resumeIntent(context)) }
            .onSuccess { HeadlessLogger.i(LOG, "will resume when Wi-Fi connects") }
            .onFailure { HeadlessLogger.w(LOG, "cannot watch for Wi-Fi: ${HeadlessLogger.brief(it)}") }
    }

    private fun disarmResume(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(resumeIntent(context)) }
    }

    /**
     * A Wi-Fi event reached [WifiRestoreReceiver]. True if the restore should start again: it
     * stopped for want of Wi-Fi, nothing has prompted this boot, and there is Wi-Fi now.
     */
    fun onWifiEvent(context: Context): Boolean {
        if (!noWifiThisBoot(context) || promptedAt(context) != null) {
            disarmResume(context)
            return false
        }
        if (wifiNetwork(context) == null) return false
        // This Wi-Fi has only just connected: the restore lets it settle before turning anything on.
        runCatching {
            prefs()
                .edit()
                .putLong(KEY_WIFI_SEEN_AT, elapsedMs())
                .putInt(KEY_WIFI_SEEN_BOOT, bootCount(context))
                .apply()
        }
        HeadlessLogger.i(LOG, "Wi-Fi connected; resuming the ADB restore")
        disarmResume(context)
        return true
    }

    // Notices.

    private fun postNotice(
        context: Context,
        title: Int,
        text: Int,
        contentIntent: Intent,
        action: Pair<Int, Intent>? = null,
    ) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.wadb_notification_title), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            // "Attempt now" is the start notification's explicit start (one-prompt rules included).
            val attempt =
                PendingIntent.getActivity(
                    context,
                    NOTICE_ID,
                    Intent(context, NotifAttemptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    flags,
                )
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(context.getString(title))
                    .setContentText(context.getString(text))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
                    .setContentIntent(PendingIntent.getActivity(context, NOTICE_ID, contentIntent, flags))
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
            action?.let { (label, intent) ->
                builder.addAction(0, context.getString(label), PendingIntent.getActivity(context, NOTICE_ID + 1, intent, flags))
            }
            builder.addAction(0, context.getString(R.string.wadb_notification_attempt_now), attempt)
            nm.notify(NOTICE_ID, builder.build())
        }.onFailure { HeadlessLogger.w(LOG, "could not post the notice: ${HeadlessLogger.brief(it)}") }
    }

    fun notifyNoWifi(context: Context) =
        postNotice(
            context,
            R.string.wadb_restore_no_wifi_title,
            R.string.wadb_restore_no_wifi_text,
            SettingsPage.InternetPanel.buildIntent(context),
        )

    /** After a silent refusal while locked (One UI): the unlock tries once more. */
    fun notifyLocked(context: Context) {
        val wireless = SettingsPage.Developer.WirelessDebugging.buildIntent(context)
        postNotice(
            context,
            R.string.wadb_restore_locked_title,
            R.string.wadb_restore_locked_text,
            wireless,
            R.string.wadb_restore_open_wireless_debugging to wireless,
        )
    }

    fun notifyUntrusted(context: Context) {
        val wireless = SettingsPage.Developer.WirelessDebugging.buildIntent(context)
        postNotice(
            context,
            R.string.wadb_restore_untrusted_title,
            R.string.wadb_restore_untrusted_text,
            wireless,
            R.string.wadb_restore_open_wireless_debugging to wireless,
        )
    }

    fun cancelNotice(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID) }
    }

    /**
     * One background start's use of wireless debugging. [foreground] tries to make the worker a
     * foreground one while it waits for Wi-Fi or the user, and says whether it is. With
     * [restoresState], [restore] puts adb_wifi_enabled back as this start found it (TCP mode);
     * without, wireless debugging is the transport and stays on. [tcpPortOpen], for a restore of the
     * TCP port, probes that port while the start waits for Wi-Fi: once it listens again, [findPort]
     * throws [TcpPortOpenAgainException] and the start goes through it instead.
     */
    class Session(
        private val context: Context,
        private val note: (String) -> Unit,
        private val warn: (String) -> Unit,
        private val foreground: suspend () -> Boolean,
        private val restoresState: Boolean = false,
        // The quiet retry: writes while locked, and stops at a silent refusal without waiting.
        private val quiet: Boolean = false,
        private val tcpPortOpen: (suspend () -> Boolean)? = null,
    ) {
        private var requiredPin: String? = null
        private var changedCertSeen = false

        fun requireMatchingPin(pin: String?) {
            requiredPin = pin
            changedCertSeen = true
        }

        var scannedClient: AdbClient? = null
            private set

        fun closeConnection() {
            scannedClient?.abort()
            scannedClient?.close()
            scannedClient = null
        }

        // adb_wifi_enabled before this start (or a run of it that died) first touched it.
        private var initial: Int? = null

        private val startedAt = elapsedMs()
        private var isForeground = false

        // Set when this start first waits for the user: an unlock and the answer share the wait.
        private var userDeadline: Long? = null

        // Whether this start's last write was made locked: the system decides then whether anyone
        // sees its prompt, so a refusal is judged by this, not by the lock state when the 0 is seen.
        private var lastWriteLocked = false

        // When this start saw Wi-Fi appear (it waited for it, or it differs from the last round's).
        private var wifiSeenAt: Long? = null

        // The Wi-Fi network the last round used.
        private var lastNetwork: Network? = null

        private sealed interface Round {
            data class Found(
                val port: Int,
            ) : Round

            object NetworkChanged : Round
        }

        private fun sessionDeadline(): Long = startedAt + if (isForeground) foregroundBudgetMs else backgroundBudgetMs

        private fun remaining(): Long = sessionDeadline() - elapsedMs()

        private suspend fun goForeground() {
            if (!isForeground && foreground()) isForeground = true
        }

        private fun userRemaining(): Long {
            val deadline = userDeadline ?: (elapsedMs() + userWaitMs).also { userDeadline = it }
            return minOf(deadline, sessionDeadline()) - elapsedMs()
        }

        /**
         * Opens a short TLS probe to [port] to check the pinned key. Does not commit a first-use
         * pin, so it is safe to use while still searching for the real adbd. Transport errors are
         * rethrown so the caller can retry the same port later instead of treating it as a refusal.
         */
        private suspend fun verifyPort(port: Int): Boolean {
            val prefs = ShizukuSettings.getPreferences() ?: return false
            val key = runCatching { AdbKey(PreferenceAdbKeyStore(prefs), "shizuku+") }.getOrNull() ?: return false
            return try {
                AdbClient("127.0.0.1", port, key, connectTimeoutMs = 2000, commitFirstUse = false, expectedPin = requiredPin).use {
                    it.connect()
                    it.seenPin != null && it.seenPin == (requiredPin ?: AdbServerPin.store().connectPin)
                }
            } catch (e: AdbServerCertChangedException) {
                false
            } catch (e: java.net.ConnectException) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                throw e
            } catch (e: java.net.SocketException) {
                throw e
            } catch (e: javax.net.ssl.SSLException) {
                // Handshake failures from a closed or reset socket are transient; a real mismatch
                // is already raised as AdbServerCertChangedException.
                throw e
            } catch (_: Exception) {
                false
            }
        }

        /** How long ago Wi-Fi appeared, as far as anything here can tell; boot is the upper bound. */
        private fun wifiAgeMs(): Long {
            val now = elapsedMs()
            val seenByReceiver =
                if (thisBoot(context, KEY_WIFI_SEEN_BOOT)) {
                    runCatching { prefs().getLong(KEY_WIFI_SEEN_AT, Long.MIN_VALUE) }.getOrDefault(Long.MIN_VALUE)
                } else {
                    Long.MIN_VALUE
                }
            return listOfNotNull(
                uptimeMs(),
                wifiSeenAt?.let { now - it },
                seenByReceiver.takeIf { it != Long.MIN_VALUE && it <= now }?.let { now - it },
            ).min()
        }

        private fun untrustedStop(why: String): WirelessDebuggingBlockedException = WirelessDebuggingBlockedException(Blocked.UNTRUSTED_NETWORK, why)

        /** The wireless debugging TLS port, or a [WirelessDebuggingBlockedException] / [TimeoutException]. */
        suspend fun findPort(): Int {
            // A run that wrote 1 this boot and died before it saw the outcome may have raised the
            // prompt: count it as raised.
            if (thisBoot(context, KEY_WRITE_PENDING_BOOT)) {
                val wroteLocked = runCatching { prefs().getBoolean(KEY_WRITE_PENDING_LOCKED, false) }.getOrDefault(false)
                if (setting(context) != 1 && silentWhenLocked() && wroteLocked) {
                    // Written locked on One UI: nobody was asked.
                    warn(
                        "an earlier start turned wireless debugging on while locked and stopped before it saw the outcome; " +
                            "this phone shows no prompt while locked, so that was not this boot's network prompt",
                    )
                    markSilentRefusal(context)
                } else if (setting(context) != 1) {
                    warn("an earlier start turned wireless debugging on and stopped before it saw the outcome; counting that as this boot's network prompt")
                    markPrompted(context)
                    throw untrustedStop("an earlier start may have raised this boot's network prompt")
                } else {
                    setWritePending(context, false)
                }
            }
            var network = requireWifi()
            repeat(MAX_ROUNDS) {
                when (val round = round(network)) {
                    is Round.Found -> return round.port
                    Round.NetworkChanged -> {
                        if (promptedAt(context) != null && setting(context) != 1) {
                            warn("Wi-Fi changed after this boot's network prompt; not turning wireless debugging on again")
                            throw untrustedStop("Wi-Fi changed after this boot's network prompt")
                        }
                        network = requireWifi()
                    }
                }
            }
            warn("Wi-Fi kept changing while wireless debugging was turned on ($MAX_ROUNDS rounds); waiting for Wi-Fi to connect again")
            markNoWifi(context)
            throw WirelessDebuggingBlockedException(Blocked.NO_WIFI, "Wi-Fi kept changing while wireless debugging was turned on")
        }

        /** If this start (or a run of it that died) turned wireless debugging on, turns it off again. */
        fun restore() {
            closeConnection()
            // Still 1 at the end of the run: the system accepted the write, which raised no
            // prompt, however the run ended (an mDNS timeout, the budget, Cancel, a stop).
            if (setting(context) == 1) setWritePending(context, false)
            if (!restoresState || initial == null) return
            // Read before the write below: after writing 0 the value always reads 0, which made a
            // successful restore look like a prompt still waiting for an answer and kept the marker,
            // so a later start turned off wireless debugging the user had switched on.
            val onAtEnd = setting(context) == 1
            if (initial == 0 && onAtEnd) {
                put(context, 0)
                note("wireless debugging was off before this start; turned it off again (adb_wifi_enabled=${setting(context)})")
            }
            // This boot's prompt may still be on screen: a late "Allow" turns wireless debugging
            // on, and whichever start comes next must know that a restore turned it on.
            val promptMayStillBeAnswered = initial == 0 && promptedAt(context) != null && !onAtEnd
            if (!promptMayStillBeAnswered) runCatching { prefs().edit().remove(KEY_TURNED_ON).commit() }
        }

        private suspend fun requireWifi(): Network {
            val network =
                wifiNetwork(context) ?: run {
                    // Nothing to post or wait for on the quiet retry's account: the next one looks again.
                    if (quiet) throw untrustedStop("no Wi-Fi for the quiet retry")
                    val waitMs = minOf(wifiWaitMs, remaining())
                    note(
                        "no Wi-Fi; waiting up to ${waitMs / 1000} s for Wi-Fi (metered or without internet is fine)" +
                            if (tcpPortOpen != null) ", probing the tcp port every ${portRecheckMs / 1000} s meanwhile" else "",
                    )
                    goForeground()
                    val connected = awaitWifi(context, waitMs, portRecheckMs) { stopIfTcpPortOpen() }
                    if (connected == null) {
                        // Once more before giving up: the wait's last probe may be seconds old.
                        stopIfTcpPortOpen()
                        warn("no Wi-Fi after ${waitMs / 1000} s; wireless debugging cannot come up without it")
                        markNoWifi(context)
                        notifyNoWifi(context)
                        throw WirelessDebuggingBlockedException(Blocked.NO_WIFI, "no Wi-Fi to turn wireless debugging on")
                    }
                    wifiSeenAt = elapsedMs()
                    note("Wi-Fi connected (network $connected)")
                    cancelNotice(context)
                    connected
                }
            if (lastNetwork != null && network != lastNetwork) {
                // Another network than the last round's: its BSSID may not be known yet either.
                wifiSeenAt = elapsedMs()
                note("Wi-Fi is now network $network")
            }
            lastNetwork = network
            val age = wifiAgeMs()
            if (age < wifiSettleMs) {
                note("Wi-Fi appeared $age ms ago; settling ${wifiSettleMs - age} ms before turning wireless debugging on")
                delay(wifiSettleMs - age)
            }
            return network
        }

        // Before the first round nothing has been written (no prompt, no turned-on or write-pending
        // record); after a later round's write, [restore] puts back what this start changed.
        private suspend fun stopIfTcpPortOpen() {
            if (tcpPortOpen?.invoke() == true) throw TcpPortOpenAgainException()
        }

        private fun turnOn() {
            val lockedNow = locked()
            lastWriteLocked = lockedNow
            // After a silent refusal, a write made unlocked is the one the user sees.
            val unlockWrite = !lockedNow && silentRefusalPending(context)
            // Committed before the write, in one edit: a run that dies right after it leaves the
            // record, and a dead unlocked write counts as the prompt.
            setWritePending(context, true, lockedNow, unlockWrite)
            if (unlockWrite) cancelQuiet(context)
            put(context, 1)
        }

        private suspend fun round(network: Network): Round {
            val before = setting(context)
            if (initial == null) {
                val leftOn = restoresState && runCatching { prefs().getBoolean(KEY_TURNED_ON, false) }.getOrDefault(false)
                if (leftOn && before == 1) note("an earlier start turned wireless debugging on and did not turn it off; it counts as off")
                initial = if (leftOn) 0 else before
            }
            var wroteThisRound = false
            if (before != 1) {
                if (promptedAt(context) != null) throw untrustedStop("this boot's network prompt has been raised already")
                // Wi-Fi may have moved while this round settled: the system would check the new
                // network, which has not settled.
                if (!isConnectedWifi(context, network)) {
                    note("Wi-Fi changed before wireless debugging was turned on; not writing")
                    return Round.NetworkChanged
                }
                wroteThisRound = true
                if (restoresState && initial == 0) runCatching { prefs().edit().putBoolean(KEY_TURNED_ON, true).commit() }
                turnOn()
                note("turning wireless debugging on: adb_wifi_enabled $before -> ${setting(context)}")
            } else {
                note("wireless debugging is already on; discovering its port")
            }
            // Already on but announcing nothing: turn it off and on once for a fresh announcement.
            var mayToggle = before == 1
            val found = ConcurrentLinkedQueue<Pair<Int, Boolean>>()
            val checkedPorts = mutableSetOf<Int>()
            val winnerLock = Any()
            var accepting = true
            var scanResult: ScanResult? = null
            var scanStarted = false
            var stopScan: () -> Unit = {}
            val stop =
                discovery.start(context) { p ->
                    synchronized(winnerLock) {
                        if (accepting && p in 1..65535) {
                            if (found.size >= MAX_PENDING_PORTS) found.poll()
                            found.add(p to false)
                        }
                    }
                }
            try {
                var deadline = elapsedMs() + discoveryMs
                while (true) {
                    val (port, fromScan) =
                        synchronized(winnerLock) {
                            val candidate =
                                if (elapsedMs() < deadline && elapsedMs() < sessionDeadline()) found.poll() else null
                            if (candidate?.second == true) {
                                scannedClient = scanResult?.client
                                scanResult = null
                            }
                            candidate ?: (0 to false)
                        }
                    if (port > 0) {
                        if (!fromScan && port in checkedPorts) {
                            delay(pollMs)
                            continue
                        }
                        // Keep every candidate, and after a refusal require the original pin.
                        val currentPin = requiredPin ?: AdbServerPin.store().connectPin
                        val refused =
                            try {
                                (!fromScan && (currentPin != null || changedCertSeen) && !verifyPort(port)) ||
                                    (fromScan && changedCertSeen && (currentPin == null || scannedClient?.seenPin != currentPin))
                            } catch (e: java.net.ConnectException) {
                                note("port $port is not accepting yet; keeping it for retry")
                                continue
                            } catch (e: java.net.SocketTimeoutException) {
                                note("port $port timed out; keeping it for retry")
                                continue
                            } catch (e: java.net.SocketException) {
                                note("port $port reset; keeping it for retry")
                                continue
                            } catch (e: javax.net.ssl.SSLException) {
                                note("port $port handshake failed; keeping it for retry")
                                continue
                            }
                        if (refused) {
                            if (fromScan) closeConnection()
                            if (!fromScan) checkedPorts.add(port)
                            if (currentPin == null) {
                                note("no key recorded for this boot; continuing discovery")
                                throw AdbServerPinMissingException(null)
                            }
                            note("port $port did not match the recorded adbd key; continuing discovery")
                            requiredPin = currentPin
                            changedCertSeen = true
                            mayToggle = false
                            continue
                        }
                        setWritePending(context, false)
                        val path = if (fromScan) "scan" else "mDNS"
                        note("$path found port $port")
                        af.shizuku.manager.utils.ManagerActivityLog
                            .log(context, "Wireless ADB $path found port $port")
                        return Round.Found(port)
                    }
                    if (setting(context) == 0) {
                        val wifi = wifiNetwork(context)
                        if (!isConnectedWifi(context, network)) {
                            if (wroteThisRound && wifi != null && promptedAt(context) == null && silentWhenLocked() && lastWriteLocked) {
                                // Written locked on One UI: the other network was checked, but
                                // nobody was asked.
                                markSilentRefusal(context)
                                warn("adb_wifi_enabled went to 0 after a write made locked, with Wi-Fi now on network $wifi; this phone shows no prompt while locked, so not this boot's network prompt")
                            } else if (wroteThisRound && wifi != null) {
                                // This round's write met another network, which the system checked
                                // and may have asked about: count it as this boot's prompt.
                                markPrompted(context)
                                notifyUntrusted(context)
                                warn("adb_wifi_enabled went to 0 after the write with Wi-Fi now on network $wifi; counting that as this boot's network prompt")
                            } else {
                                // Nothing judged the write: it is no prompt, nor the unlock's write.
                                setWritePending(context, false)
                                runCatching { prefs().edit().remove(KEY_UNLOCK_WRITE_BOOT).commit() }
                                warn("adb_wifi_enabled went to 0: Wi-Fi ${if (wifi == null) "disconnected" else "changed to network $wifi"}")
                            }
                            return Round.NetworkChanged
                        }
                        // The same, settled Wi-Fi: the system refused. Written locked on One UI it
                        // asked nobody (even if the screen is unlocked by now): not this boot's
                        // prompt, and the unlock writes once more. Written unlocked, it asked.
                        if (wroteThisRound &&
                            promptedAt(context) == null &&
                            silentWhenLocked() &&
                            lastWriteLocked
                        ) {
                            markSilentRefusal(context)
                            warn(
                                "adb_wifi_enabled went back to 0 while locked: this network (or access point) is not allowed for wireless debugging, " +
                                    "and this phone shows no prompt while locked; not this boot's network prompt",
                            )
                            if (quiet) throw untrustedStop("refused in silence while locked (quiet retry)")
                            awaitSilentUnlock()
                            if (!isConnectedWifi(context, network)) {
                                note("the Wi-Fi network changed while waiting for an unlock")
                                return Round.NetworkChanged
                            }
                            turnOn()
                            note("unlocked; turning wireless debugging on once more, which the system shows: adb_wifi_enabled=${setting(context)}")
                            deadline = elapsedMs() + discoveryMs
                            delay(pollMs)
                            continue
                        }
                        // Otherwise the system asked (or will once unlocked). Recorded before
                        // anything else, so no run asks again this boot.
                        if (promptedAt(context) == null) {
                            markPrompted(context)
                            warn("adb_wifi_enabled went back to 0 with Wi-Fi connected: this network is not trusted for wireless debugging; this boot's one network prompt")
                        }
                        if (locked()) awaitUnlock()
                        untrusted(network)
                        deadline = elapsedMs() + discoveryMs
                    } else if ((changedCertSeen && !scanStarted && found.isEmpty()) || elapsedMs() >= minOf(deadline, sessionDeadline())) {
                        if (!mayToggle || changedCertSeen || elapsedMs() >= sessionDeadline()) {
                            if (!scanStarted && remaining() > 0) {
                                scanStarted = true
                                deadline = minOf(elapsedMs() + scanBudgetMs, sessionDeadline())
                                val scanStop =
                                    discovery.scan(context) { result ->
                                        synchronized(winnerLock) {
                                            if (accepting && result.port in 1..65535 && scanResult == null) {
                                                scanResult = result
                                                found.add(result.port to true)
                                            } else {
                                                result.client?.abort()
                                                result.client?.close()
                                            }
                                        }
                                    }
                                if (scanStop != null) {
                                    stopScan = scanStop
                                    continue
                                }
                            }
                            // On all this time: the write was accepted, no prompt.
                            if (setting(context) == 1) setWritePending(context, false)
                            if (changedCertSeen) {
                                val currentPin = requiredPin ?: AdbServerPin.store().connectPin
                                if (currentPin == null) {
                                    warn("no key recorded for this boot")
                                    throw AdbServerPinMissingException(null)
                                }
                                warn("every discovered adbd endpoint presented a changed TLS key")
                                throw AdbServerCertChangedException(null, null)
                            }
                            warn("mDNS discovery timed out after ${discoveryMs / 1000} s (adb_wifi_enabled=${setting(context)})")
                            throw TimeoutException("Timed out during mDNS port discovery")
                        }
                        mayToggle = false
                        note("mDNS found nothing in ${discoveryMs / 1000} s although wireless debugging is on; turning it off and on once")
                        put(context, 0)
                        delay(toggleGapMs)
                        wroteThisRound = true
                        turnOn()
                        note("wireless debugging toggled: adb_wifi_enabled=${setting(context)}")
                        deadline = elapsedMs() + discoveryMs
                    }
                    delay(pollMs)
                }
            } finally {
                synchronized(winnerLock) {
                    accepting = false
                    scanResult?.client?.close()
                    scanResult = null
                }
                stopScan()
                stop()
                if (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == false || scannedClient == null) closeConnection()
            }
        }

        // Off while locked: the system's prompt waits for the user, who cannot answer it locked.
        // Nothing is written again (that would queue a second prompt).
        private suspend fun awaitUnlock() {
            val waitMs = userRemaining()
            note("wireless debugging went off while locked; waiting up to ${waitMs / 1000} s for an unlock")
            notifyUntrusted(context)
            goForeground()
            if (!awaitUnlock(waitMs)) {
                warn(
                    "not unlocked within ${waitMs / 1000} s; the system's prompt waits for the unlock, and no second one is raised this boot; " +
                        "continuing when wireless debugging is turned on, or on a start by hand",
                )
                throw untrustedStop("locked while the system asked to allow wireless debugging on this network")
            }
            note("unlocked; waiting for the system's prompt to be answered")
        }

        // Refused in silence while locked (One UI): no prompt exists. The unlock writes once more.
        private suspend fun awaitSilentUnlock() {
            val waitMs = userRemaining()
            note("waiting up to ${waitMs / 1000} s for an unlock, then turning wireless debugging on once more")
            notifyLocked(context)
            goForeground()
            if (!awaitUnlock(waitMs)) {
                warn("not unlocked within ${waitMs / 1000} s; trying once more after the next unlock")
                throw untrustedStop("refused in silence while locked")
            }
        }

        // Waits for the user to allow wireless debugging on this network (the prompt was raised by
        // the write). Returns once it is on, or the network changed (the round's next check sees
        // that).
        private suspend fun untrusted(network: Network) {
            notifyUntrusted(context)
            goForeground()
            val waitMs = userRemaining()
            note("asked the user to allow wireless debugging on this network; waiting up to ${waitMs / 1000} s")
            val deadline = elapsedMs() + waitMs
            while (elapsedMs() < deadline) {
                if (setting(context) == 1) {
                    note("wireless debugging is on again: the network was allowed")
                    cancelNotice(context)
                    return
                }
                if (!isConnectedWifi(context, network)) {
                    note("the Wi-Fi network changed while waiting for the user")
                    return
                }
                delay(pollMs)
            }
            warn(
                "the network was not allowed within ${waitMs / 1000} s; no second prompt this boot: " +
                    "continuing when wireless debugging is turned on, or on a start by hand",
            )
            throw untrustedStop("this Wi-Fi network is not trusted for wireless debugging")
        }

        private fun locked(): Boolean = WirelessDebugging.locked(context)

        private suspend fun awaitUnlock(waitMs: Long): Boolean {
            val unlocked = CompletableDeferred<Unit>()
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (intent.action == Intent.ACTION_USER_PRESENT) unlocked.complete(Unit)
                    }
                }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                if (!locked()) return true
                return withTimeoutOrNull(waitMs.coerceAtLeast(0L)) { unlocked.await() } != null
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}
