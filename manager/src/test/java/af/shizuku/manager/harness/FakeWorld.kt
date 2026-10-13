package af.shizuku.manager.harness

import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.adb.WirelessDebugging
import af.shizuku.manager.receiver.BootRetryWorker
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.HeadlessLogger
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.AdbStartWorker
import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Binder
import android.os.Looper
import android.provider.Settings
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.WorkManagerTestInitHelper.getTestDriver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemProperties
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything outside the manager process that the one-prompt rule depends on: the wall clock,
 * durable storage, adbd, the server's binder and WorkManager. The manager's own code runs for real
 * against it; [processDeath] drops exactly what a dead process loses.
 */
class FakeWorld(
    val app: Application,
) : Closeable {
    private val clock = AtomicLong(START_MS)
    val now: Long get() = clock.get()

    val prefs = FakePrefs()
    val adbd = FakeAdbd(onShell = { serverUp() })

    // Wireless debugging and Wi-Fi as the system runs them (AOSP AdbDebuggingManager): adbd's TLS
    // port is announced by mDNS only while adb_wifi_enabled is 1 on a connected, trusted Wi-Fi; the
    // system writes adb_wifi_enabled back to 0 without Wi-Fi, and on an untrusted network after
    // raising "Allow wireless debugging on this network?" ([trustPrompts]). The manager's key is
    // authorised there already (paired, or "Always allow" in adb_keys), and "tcpip:<port>" makes
    // adbd listen on that port ([tcpAdbd]). It speaks TLS, as adbd does on that port.
    val wirelessAdbd = FakeAdbd(onShell = { serverUp() }, authorized = true, onTcpip = { openTcpPort(it) }, tls = true, fixedKey = true)

    /** adbd's classic TCP port once something has opened it; null while closed, as after a reboot. */
    @Volatile
    var tcpAdbd: FakeAdbd? = null
        private set

    /** A loopback port nothing listens on: the TCP-mode port a reboot (or adb usb) closed. */
    val closedTcpPort: Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    init {
        // adbd's classic TCP ports, where it answers with the legacy token challenge: [adbd] (the
        // saved port, where the key-offer dialog is raised), [tcpAdbd] and the closed TCP-mode port.
        // Production asks the system for adbd's TCP port; these fakes listen on random ports.
        AdbStarter.isLegacyAuthPort = { it == adbd.port || it == tcpAdbd?.port || it == closedTcpPort }
    }

    class Wifi(
        val network: Network,
        val trusted: Boolean,
    )

    @Volatile
    var wifi: Wifi? = null
        private set

    /** Wireless debugging is on but announces nothing until it is next turned off. */
    @Volatile
    var staleWirelessDebugging = false

    private val trustPromptCount = AtomicInteger()
    private val silentRefusalCount = AtomicInteger()
    private val turnOnCount = AtomicInteger()
    private val originalManufacturer: String = android.os.Build.MANUFACTURER

    /**
     * The phone is a Samsung (One UI): an untrusted network's write made while the keyguard is
     * locked is refused with no prompt at all (AdbDebuggingManager logs
     * "startConfirmationForNetwork: isLockScreenMode" and starts no WifiDebuggingActivity), where
     * AOSP queues its dialog for the unlock.
     */
    fun samsung(oneUi: Int = ONE_UI_8_5) {
        ShadowBuild.setManufacturer("samsung")
        ShadowSystemProperties.override("ro.build.version.oneui", oneUi.toString())
    }

    private val isSamsung: Boolean get() =
        android.os.Build.MANUFACTURER
            .equals("samsung", ignoreCase = true)

    /** mDNS discoveries still to hear nothing. */
    val silentDiscoveries = AtomicInteger()

    /** Wi-Fi switches to a network of this trust at the next write of adb_wifi_enabled=1. */
    @Volatile
    var switchOnNextWrite: Boolean? = null

    /** Wi-Fi disconnects at the next write of adb_wifi_enabled=1, before the system has judged it. */
    @Volatile
    var wifiDropsOnNextWrite = false

    /**
     * For this long after Wi-Fi connects WifiManager has no BSSID for it, and the system undoes a
     * write of adb_wifi_enabled=1 without a prompt.
     */
    @Volatile
    var bssidLagMs = 0L

    @Volatile
    private var wifiConnectedAtNs = 0L
    val trustPrompts: Int get() = trustPromptCount.get()

    /** Writes of 1 refused with no prompt (One UI, locked, a network not trusted). */
    val silentRefusals: Int get() = silentRefusalCount.get()

    /** Every time adb_wifi_enabled became 1, whoever wrote it. */
    val turnOns: Int get() = turnOnCount.get()
    private val discoveries = CopyOnWriteArrayList<(Int) -> Unit>()
    private var nextNetId = 100
    private var wifiCaps: NetworkCapabilities? = null

    /** Input of every AdbStartWorker WorkManager ran, oldest first: what a rerun repeats. */
    val startInputs = CopyOnWriteArrayList<Data>()

    private val workExecutor = Executors.newCachedThreadPool()
    val workManager: WorkManager get() = WorkManager.getInstance(app)

    fun install() {
        if (Security.getProvider(ANDROID_KEYSTORE) == null) Security.addProvider(UnusableAndroidKeyStore())
        ShizukuApplication::class.java
            .getDeclaredField("appContext")
            .apply { isAccessible = true }
            .set(null, app)
        Starter.initialize(app)
        ShizukuSettings.initialize(app)
        ShizukuSettings.setPreferencesForTesting(prefs)
        AdbAuthWait.clockMs = { clock.get() }
        AdbAuthWait.elapsedMs = { clock.get() }
        AdbAuthWait.timeoutMs = AUTH_TIMEOUT_MS
        timers.clear()
        AdbAuthWait.schedule = { delayMs, task ->
            val timer = VirtualTimer(clock.get() + delayMs, task)
            timers += timer
            val cancel: () -> Unit = {
                timer.cancelled = true
                timers.remove(timer)
            }
            cancel
        }
        serverDown()
        // Robolectric reuses one sandbox (and so every app singleton) across the tests of a class.
        ShizukuReceiverStarter.resetForTesting()
        AdbAuthWait.resetForTesting()
        ShizukuStateMachine.resetForTesting()
        // The log lives in this test's files dir; a previous test's dir is gone.
        HeadlessLogger.resetForTesting()
        HeadlessLogger.init(app)

        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        // A trusted Wi-Fi is connected and wireless debugging is off.
        shadowOf(cm).clearAllNetworks()
        addWifi(trusted = true)
        Settings.Global.putInt(app.contentResolver, ADB_WIFI, 0)
        app.contentResolver.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI), false, adbWifiObserver)
        WirelessDebugging.discovery =
            WirelessDebugging.PortDiscovery { _, onPort ->
                // An mDNS discovery that hears nothing (multicast lost).
                if (silentDiscoveries.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    val none: () -> Unit = {}
                    return@PortDiscovery none
                }
                discoveries += onPort
                announce()
                val stop: () -> Unit = { discoveries -= onPort }
                stop
            }
        WirelessDebugging.wifiWaitMs = 10_000
        WirelessDebugging.userWaitMs = 10_000
        WirelessDebugging.discoveryMs = 3_000
        WirelessDebugging.pollMs = 20
        WirelessDebugging.toggleGapMs = 20
        WirelessDebugging.wifiSettleMs = 20
        // An hour since boot: no Wi-Fi here is "just connected" unless a scenario says so.
        WirelessDebugging.uptimeMs = { 3_600_000 }
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 1)
        // Robolectric's SystemClock stands still unless the main looper is idled.
        WirelessDebugging.elapsedMs = { System.nanoTime() / 1_000_000 }
        // Skips the worker's post-boot settling delay.
        Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1)
        // A saved port that answers the starter's probe routes the worker straight to adbd, with
        // no Wi-Fi constraint and no mDNS.
        prefs
            .edit()
            .putInt("mode", ShizukuSettings.LaunchMethod.ADB)
            .putInt("last_adb_port", adbd.port)
            .commit()

        val config =
            Configuration
                .Builder()
                .setExecutor(workExecutor)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? {
                            if (workerClassName == AdbStartWorker::class.java.name) startInputs += workerParameters.inputData
                            return null
                        }
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config, WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS)
    }

    private class VirtualTimer(
        val dueAt: Long,
        val task: () -> Unit,
    ) {
        @Volatile
        var cancelled = false
    }

    private val timers = CopyOnWriteArrayList<VirtualTimer>()

    /** Tasks the manager has scheduled (AdbAuthWait.schedule) that have neither run nor been cancelled. */
    val pendingTimers: Int get() = timers.count { !it.cancelled }

    /** Moves the wall and monotonic clocks on, running every scheduled task that falls due. */
    fun advanceTime(ms: Long) {
        val now = clock.addAndGet(ms)
        val due = timers.filter { it.dueAt <= now }.sortedBy { it.dueAt }
        timers.removeAll(due.toSet())
        due.filterNot { it.cancelled }.forEach { it.task() }
    }

    private val cm: ConnectivityManager get() = app.getSystemService(ConnectivityManager::class.java)

    val adbWifiEnabled: Int get() = Settings.Global.getInt(app.contentResolver, ADB_WIFI, 0)

    private fun setAdbWifi(value: Int) = Settings.Global.putInt(app.contentResolver, ADB_WIFI, value)

    private val observerDepth = AtomicInteger()

    private val adbWifiObserver =
        object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                observerDepth.incrementAndGet()
                try {
                    systemReactsToAdbWifi()
                } finally {
                    // The system's own write back to 0 notifies again inside this one: JobScheduler
                    // sees the setting once it has settled.
                    if (observerDepth.decrementAndGet() == 0) fireContentTriggers()
                }
            }
        }

    // JobScheduler's ContentObserverController: a job with a content-URI trigger on
    // adb_wifi_enabled runs once after the setting changes. Only the restore's watch has one.
    private fun fireContentTriggers() {
        runCatching {
            workManager
                .getWorkInfosForUniqueWork(WATCH_WORK)
                .get()
                .filter { it.state == WorkInfo.State.ENQUEUED }
                .forEach { getTestDriver(app)?.setAllConstraintsMet(it.id) }
        }
    }

    private fun systemReactsToAdbWifi() {
        if (adbWifiEnabled != 1) {
            staleWirelessDebugging = false
            return
        }
        turnOnCount.incrementAndGet()
        // Wi-Fi moves to another network just as the manager writes: the system checks that one.
        switchOnNextWrite?.let { trusted ->
            switchOnNextWrite = null
            wifi?.let { removeNetwork(it.network) }
            addWifi(trusted)
        }
        if (wifiDropsOnNextWrite) {
            wifiDropsOnNextWrite = false
            wifi?.let { gone ->
                wifi = null
                removeNetwork(gone.network)
                shadowOf(cm).networkCallbacks.toList().forEach { it.onLost(gone.network) }
            }
        }
        val w = wifi
        when {
            // WifiManager has no BSSID for the new network yet: off again, with no prompt.
            w != null && System.nanoTime() - wifiConnectedAtNs < bssidLagMs * 1_000_000 -> setAdbWifi(0)
            w == null -> setAdbWifi(0)
            // One UI, locked: refused, and no prompt to answer later.
            !w.trusted && isSamsung && keyguard.isKeyguardLocked -> {
                silentRefusalCount.incrementAndGet()
                refuse()
            }
            !w.trusted -> {
                trustPromptCount.incrementAndGet()
                refuse()
            }
            else -> announce()
        }
    }

    /**
     * The system still decides at the write (prompt or not, by the lock state then) but writes 0
     * back only at [releaseRefusal]: the lock state can change before the manager sees the 0.
     */
    @Volatile
    var holdRefusals = false

    @Volatile
    private var refusalHeld = false

    private fun refuse() {
        if (holdRefusals) refusalHeld = true else setAdbWifi(0)
    }

    /** A refusal is being held (see [holdRefusals]). */
    fun awaitHeldRefusal() = waitUntil({ "a write of adb_wifi_enabled=1 to be refused" }) { refusalHeld }

    fun releaseRefusal() {
        check(refusalHeld) { "no refusal held" }
        holdRefusals = false
        refusalHeld = false
        setAdbWifi(0)
    }

    private fun announce() {
        if (adbWifiEnabled == 1 && wifi?.trusted == true && !staleWirelessDebugging) discoveries.forEach { it(wirelessAdbd.port) }
    }

    // Wi-Fi with no internet and metered (neither VALIDATED nor NOT_METERED): enough for wireless debugging.
    private fun addWifi(trusted: Boolean): Network {
        val network = ShadowNetwork.newInstance(nextNetId++)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        // What every ordinary network has; deliberately not INTERNET, VALIDATED or NOT_METERED.
        listOf(
            NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
            NetworkCapabilities.NET_CAPABILITY_TRUSTED,
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
            NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED,
            NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
            NET_CAPABILITY_NOT_VCN_MANAGED,
        ).forEach { shadowOf(caps).addCapability(it) }
        wifiCaps = caps
        wifiConnectedAtNs = System.nanoTime()
        @Suppress("DEPRECATION")
        shadowOf(cm).addNetwork(
            network,
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED),
        )
        shadowOf(cm).setNetworkCapabilities(network, caps)
        wifi = Wifi(network, trusted)
        return network
    }

    // The shadow keeps a removed network's capabilities, so getNetworkCapabilities would still call
    // it Wi-Fi; ConnectivityManager returns null for a network that is gone.
    private fun removeNetwork(network: Network) {
        shadowOf(cm).removeNetwork(network)
        shadowOf(cm).setNetworkCapabilities(network, null)
    }

    /** Wi-Fi connects (replacing any other): network callbacks and their PendingIntents fire. */
    fun connectWifi(trusted: Boolean) {
        dropWifi()
        val network = addWifi(trusted)
        // The shadow keeps no request with a callback; every callback here is the manager's Wi-Fi
        // request, so deliver only what that request would match (as ConnectivityService does).
        if (!WirelessDebugging.wifiRequest().canBeSatisfiedBy(wifiCaps)) {
            settle()
            return
        }
        shadowOf(cm).networkCallbacks.toList().forEach { it.onAvailable(network) }
        // The system sends a PendingIntent callback to its explicit receiver.
        shadowOf(cm).networkCallbackPendingIntents.toList().forEach { pi ->
            val intent = Intent(shadowOf(pi).savedIntent).putExtra(ConnectivityManager.EXTRA_NETWORK, network)
            val receiver = Class.forName(checkNotNull(intent.component).className).getDeclaredConstructor().newInstance() as BroadcastReceiver
            receiver.onReceive(app, intent)
        }
        settle()
    }

    /** Wi-Fi disconnects; the system turns wireless debugging off with it. */
    fun dropWifi() {
        val w = wifi ?: return
        wifi = null
        removeNetwork(w.network)
        if (adbWifiEnabled == 1) setAdbWifi(0)
        shadowOf(cm).networkCallbacks.toList().forEach { it.onLost(w.network) }
    }

    /** The user answers "Always allow on this network": it is trusted and wireless debugging comes on. */
    fun allowWirelessDebuggingOnThisNetwork() {
        val w = checkNotNull(wifi) { "no Wi-Fi to allow" }
        wifi = Wifi(w.network, trusted = true)
        setAdbWifi(1)
    }

    fun wirelessDebuggingOn() = setAdbWifi(1)

    /**
     * The phone moves to another access point of the same Wi-Fi (a mesh node): the same network,
     * so no callback, but wireless debugging trust is per BSSID and may differ.
     */
    fun roamTo(trusted: Boolean) {
        val w = checkNotNull(wifi) { "no Wi-Fi to roam on" }
        wifi = Wifi(w.network, trusted)
    }

    private fun pendingWork(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get().filterNot { it.state.isFinished }

    /** The restore is watching adb_wifi_enabled (its content-trigger work is queued). */
    val watchingWirelessDebugging: Boolean get() = pendingWork(WATCH_WORK).isNotEmpty()

    /** A quiet retry is queued. */
    val quietRetryQueued: Boolean get() = pendingWork(QUIET_WORK).isNotEmpty()

    /** The quiet retry's delay has passed: it runs, and so does any start it queues. */
    fun quietRetryDue() = delayedWorkDue(QUIET_WORK, "quiet retry")

    /** A recheck of the TCP port after a no-Wi-Fi stop is queued. */
    val portRecheckQueued: Boolean get() = pendingWork(PORT_RECHECK_WORK).isNotEmpty()

    /** The next recheck of the TCP port comes due: it runs, and so does any start it queues. */
    fun portRecheckDue() = delayedWorkDue(PORT_RECHECK_WORK, "TCP port recheck")

    /**
     * adbd reports [port] as its TCP port (service.adb.tcp.port), as it does while `adb tcpip` is in
     * effect, whether or not anything listens there right now.
     */
    fun adbdReportsTcpPort(port: Int) = ShadowSystemProperties.override("service.adb.tcp.port", port.toString())

    private fun delayedWorkDue(
        name: String,
        what: String,
    ) {
        settle()
        var work: WorkInfo? = null
        // A run queued behind the last one is BLOCKED until that one has finished.
        waitUntil({ "a $what to be queued" }) {
            work = workManager.getWorkInfosForUniqueWork(name).get().firstOrNull { it.state == WorkInfo.State.ENQUEUED }
            work != null
        }
        val id = checkNotNull(work).id
        checkNotNull(getTestDriver(app)).setInitialDelayMet(id)
        waitUntil({ "the $what to run" }) {
            workManager
                .getWorkInfoById(id)
                .get()
                ?.state
                ?.isFinished != false
        }
        settle()
    }

    /** Wireless debugging is on but its mDNS announcement has gone stale. */
    fun staleWirelessDebuggingOn() {
        setAdbWifi(1)
        staleWirelessDebugging = true
    }

    /** adbd listens on [port] (tcpip, or something outside the manager did it). */

    /** Something closes adbd's TCP port again (adb usb). */
    fun closeTcpPort() {
        tcpAdbd?.close()
        tcpAdbd = null
        serverDown()
        // The server went with it; the manager notices (its binder death does the same).
        ShizukuStateMachine.update()
    }

    fun openTcpPort(port: Int) {
        tcpAdbd?.close()
        tcpAdbd = FakeAdbd(onShell = { serverUp() }, port = port, authorized = true)
    }

    private val keyguard get() = app.getSystemService(android.app.KeyguardManager::class.java)

    fun lockScreen() = shadowOf(keyguard).setKeyguardLocked(true)

    fun unlockScreen() {
        shadowOf(keyguard).setKeyguardLocked(false)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        settle()
    }

    /** The device restarts: a new boot count, wireless debugging off and adbd's TCP port closed. */
    fun reboot() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0) + 1)
        setAdbWifi(0)
        tcpAdbd?.close()
        tcpAdbd = null
        serverDown()
    }

    fun awaitLog(line: String) =
        waitUntil({ "the start log to say \"$line\"" }) {
            HeadlessLogger
                .getLogPath()
                ?.let { java.io.File(it) }
                ?.takeIf { it.exists() }
                ?.readText()
                ?.contains(line) == true
        }

    fun awaitWifiWait() = waitUntil({ "a start to wait for Wi-Fi" }) { shadowOf(cm).networkCallbacks.isNotEmpty() }

    fun serverUp() = setShizukuBinder(Binder())

    fun serverDown() = setShizukuBinder(null)

    private fun setShizukuBinder(binder: Binder?) =
        Shizuku::class.java
            .getDeclaredField("binder")
            .apply { isAccessible = true }
            .set(null, binder)

    // The test thread is Robolectric's main thread, which WorkManager hands workers to, so every
    // wait runs the main looper instead of blocking it.
    private fun pump() = shadowOf(Looper.getMainLooper()).idle()

    fun waitUntil(
        what: () -> String,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out after ${timeoutMs}ms waiting for ${what()}" }
            pump()
            Thread.sleep(10)
        }
    }

    /** Every render and enqueue decision queued so far has run. */
    fun settle() {
        pump()
        waitUntil({ "the start notification thread to go idle" }) { ShizukuReceiverStarter.awaitIdleForTesting(50) }
    }

    fun awaitOffer() = adbd.awaitOffer(pump = ::pump)

    /** Waits for the unique start work to finish; null if none was ever enqueued. */
    fun awaitStartWork(): WorkInfo.State? {
        settle()
        var last: List<WorkInfo> = emptyList()
        waitUntil({ "the start work to finish (last seen ${last.map { it.state }})" }) {
            last = workManager.getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME).get()
            last.all { it.state.isFinished }
        }
        return last.lastOrNull()?.state
    }

    /** The start work ran and asked WorkManager to retry it (now ENQUEUED with a backoff). */
    fun awaitRetry() {
        settle()
        waitUntil({ "the start work to ask for a retry" }) {
            workManager.getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME).get().any {
                it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0
            }
        }
    }

    /** Cancels the start work (its backoff would outlast the scenario). */
    fun cancelStartWork() {
        workManager.cancelUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME).result.get()
        settle()
    }

    /** One BootRetryWorker run, with its verify delay in virtual time. */
    fun bootRetryTick(): ListenableWorker.Result {
        var result: ListenableWorker.Result? = null
        runTest { result = TestListenableWorkerBuilder.from(app, BootRetryWorker::class.java).build().doWork() }
        settle()
        return checkNotNull(result)
    }

    /** WorkManager runs the given start request again, as after the system stopped it. */
    fun workerRerun(input: Data = startInputs.last()): ListenableWorker.Result {
        val worker =
            TestListenableWorkerBuilder
                .from(app, AdbStartWorker::class.java)
                .setInputData(input)
                .setRunAttemptCount(1)
                .build()
        return runBlocking { worker.doWork() }.also { settle() }
    }

    /**
     * The manager process dies: its connections close, in-memory state is lost and unflushed
     * apply()s never reach disk. WorkManager's database and the server (another process) survive.
     */
    fun processDeath() {
        adbd.dropAll()
        awaitStartWork()
        prefs.killProcess()
        AdbAuthWait.resetForTesting()
        ShizukuStateMachine.resetForTesting()
        ShizukuReceiverStarter.resetForTesting()
    }

    override fun close() {
        adbd.close()
        wirelessAdbd.close()
        tcpAdbd?.close()
        runCatching { awaitStartWork() }
        runCatching { settle() }
        workExecutor.shutdownNow()
        app.contentResolver.unregisterContentObserver(adbWifiObserver)
        ShadowBuild.setManufacturer(originalManufacturer)
        ShadowSystemProperties.reset()
        WirelessDebugging.resetForTesting()
        AdbAuthWait.clockMs = System::currentTimeMillis
        AdbAuthWait.elapsedMs = { android.os.SystemClock.elapsedRealtime() }
        AdbAuthWait.timeoutMs = AdbAuthWait.TIMEOUT_MS
        AdbAuthWait.schedule = AdbAuthWait.realSchedule
        AdbStarter.isLegacyAuthPort = AdbStarter.realIsLegacyAuthPort
        timers.clear()
        serverDown()
    }

    private companion object {
        const val START_MS = 1_800_000_000_000L

        // Real seconds: a denied or unanswered dialog ends only at AdbClient's deadline.
        const val AUTH_TIMEOUT_MS = 3_000
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ADB_WIFI = "adb_wifi_enabled"

        // ro.build.version.oneui on the s24 whose silent locked refusal is the evidence.
        const val ONE_UI_8_5 = 80500

        // The restore's unique works (WirelessDebugging): its watch on adb_wifi_enabled, and the quiet retry.
        const val WATCH_WORK = "wadb_restore_watch"
        const val QUIET_WORK = "wadb_restore_quiet_retry"

        // The bounded recheck of the TCP port after a no-Wi-Fi stop.
        const val PORT_RECHECK_WORK = "tcp_port_recheck"

        // A system API constant (NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED) that requests carry by default.
        const val NET_CAPABILITY_NOT_VCN_MANAGED = 28
    }
}

/**
 * Robolectric has no AndroidKeyStore. AdbKey looks it up outside its try, so without a provider the
 * key cannot be built at all; one whose load fails takes AdbKey's real BouncyCastle fallback.
 */
@Suppress("DEPRECATION")
internal class UnusableAndroidKeyStore : Provider("AndroidKeyStore", 1.0, "unusable AndroidKeyStore for tests") {
    init {
        put("KeyStore.AndroidKeyStore", UnusableKeyStoreSpi::class.java.name)
    }
}

internal class UnusableKeyStoreSpi : KeyStoreSpi() {
    override fun engineLoad(
        stream: InputStream?,
        password: CharArray?,
    ): Unit = throw java.io.IOException("AndroidKeyStore is unavailable in tests")

    override fun engineGetKey(
        alias: String?,
        password: CharArray?,
    ): Key? = null

    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

    override fun engineGetCertificate(alias: String?): Certificate? = null

    override fun engineGetCreationDate(alias: String?): Date? = null

    override fun engineSetKeyEntry(
        alias: String?,
        key: Key?,
        password: CharArray?,
        chain: Array<out Certificate>?,
    ) = Unit

    override fun engineSetKeyEntry(
        alias: String?,
        key: ByteArray?,
        chain: Array<out Certificate>?,
    ) = Unit

    override fun engineSetCertificateEntry(
        alias: String?,
        cert: Certificate?,
    ) = Unit

    override fun engineDeleteEntry(alias: String?) = Unit

    override fun engineAliases(): Enumeration<String> = Collections.emptyEnumeration()

    override fun engineContainsAlias(alias: String?): Boolean = false

    override fun engineSize(): Int = 0

    override fun engineIsKeyEntry(alias: String?): Boolean = false

    override fun engineIsCertificateEntry(alias: String?): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate?): String? = null

    override fun engineStore(
        stream: OutputStream?,
        password: CharArray?,
    ) = Unit
}
