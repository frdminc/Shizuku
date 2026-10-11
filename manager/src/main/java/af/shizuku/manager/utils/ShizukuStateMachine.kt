package af.shizuku.manager.utils

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object ShizukuStateMachine {
    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    // Seeded from the last persisted settled state (see the persistence hook in transition()),
    // not a hardcoded STOPPED - a freshly cold-started process (e.g. WatchdogAlarmReceiver reviving
    // the app after an OEM freezer killed it, #417) otherwise has no way to tell "the server was
    // RUNNING when this process died" apart from "the user deliberately stopped it": both would
    // read as the in-memory default, and update() below would silently report STOPPED for an actual
    // crash, so the watchdog's CRASHED-triggered restart would never fire for exactly the case it
    // exists to handle.
    private var state = AtomicReference<State>(loadPersistedSettledState())
    private val listeners = CopyOnWriteArrayList<Registration>()

    /**
     * A state listener. Transitions happen on whatever thread makes them (AdbStarter, the start
     * worker, binder callbacks), so a listener that touches views is called on the main thread
     * ([onMainThread]); only thread-safe ones (asFlow's trySend) are called inline.
     */
    private class Registration(
        val listener: (State) -> Unit,
        val onMainThread: Boolean,
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Main-thread deliveries posted but not yet run. While any are queued, a transition made on the
    // main thread queues behind them instead of running inline, so listeners see states in order.
    private val queuedMainDeliveries = AtomicInteger(0)
    private val startingTimestamp =
        java.util.concurrent.atomic
            .AtomicLong(0L)
    private const val STARTING_TIMEOUT_MS = 90_000L

    /** A start of ours that has not yet produced a new server; [binder] is the one present when it began. */
    private class PendingStart(
        val binder: IBinder?,
    )

    private val pendingStart = AtomicReference<PendingStart?>(null)

    /** Tests only: what a new process would start from (the persisted settled state). */
    internal fun resetForTesting() {
        state.set(loadPersistedSettledState())
        startingTimestamp.set(0L)
        pendingStart.set(null)
    }

    private fun loadPersistedSettledState(): State =
        try {
            when (ShizukuSettings.getLastSettledState()) {
                State.RUNNING.name -> State.RUNNING
                State.CRASHED.name -> State.CRASHED
                else -> State.STOPPED
            }
        } catch (_: Exception) {
            State.STOPPED
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener {
                Sentry.addBreadcrumb(
                    Breadcrumb("Binder received - service is now RUNNING").apply {
                        category = "shizuku.service"
                    },
                )
                // A live binder does not say this manager's ADB key was accepted (the server may
                // have been started as root, from a computer, or before the key was revoked), so
                // the unanswered marker stays: it is cleared where adbd accepts the key
                // (AdbClient) and by explicit starts. Its notice is hidden while a server runs;
                // the start notification follows this state machine's flow, so it comes back
                // when the server stops.
                set(State.RUNNING)
                recordIfNewServer()
            },
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener {
                Sentry.addBreadcrumb(
                    Breadcrumb("Binder dead - service connection lost").apply {
                        category = "shizuku.service"
                        level = io.sentry.SentryLevel.WARNING
                    },
                )
                setDead()
            },
        )
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        // Capture the value our own CAS produced: a separate state.get() after getAndUpdate can
        // observe a concurrent transition's later write, making oldState == newState and silently
        // skipping listener/broadcast side effects for a transition that did occur.
        var computed: State? = null
        val oldState = state.getAndUpdate { current -> transform(current).also { computed = it } }
        val newState = computed ?: error("getAndUpdate lambda must always execute synchronously")
        if (oldState != newState) {
            Timber.tag("ShizukuStateMachine").d(newState.toString())

            // Which app build started the running server is recorded when a start succeeds, not
            // when it begins. All deliberate start paths (AdbStarter, ShizukuReceiverStarter, tile,
            // StarterActivity) funnel through STARTING, which notes the server binder present then;
            // the build is recorded once RUNNING is reached with a different binder, that is, a new
            // server. Recording at STARTING marked a server left over from a pre-update build as
            // current when the restart failed and update() settled back onto it, so
            // isServerVersionSkewed() never fired in exactly the case it exists for.
            if (newState == State.STARTING) {
                startingTimestamp.set(System.currentTimeMillis())
                pendingStart.set(PendingStart(currentBinder()))
            }
            if (newState == State.RUNNING) {
                recordIfNewServer()
                applyWatchdogDefault()
            }

            // After the bookkeeping above: a RUNNING listener (HomeActivity's version-skew check)
            // must see the build this start recorded, or it offers a "restart" that stops the new
            // server. Each listener is isolated: one that throws must not abort the side effects
            // below (the persisted state, the STATE_CHANGED broadcast) or the caller's start.
            deliver(newState)

            if (newState == State.RUNNING || newState == State.STOPPED || newState == State.CRASHED) {
                try {
                    val context = ShizukuApplication.appContext
                    af.shizuku.manager.automation.AutomationEngine.dispatchEvent(
                        af.shizuku.manager.automation
                            .ShizukuStateEvent(newState == State.RUNNING),
                        context,
                    )
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to dispatch automation event")
                }

                if (newState == State.RUNNING) {
                    try {
                        val context = ShizukuApplication.appContext
                        CoroutineScope(Dispatchers.IO).launch {
                            if (ShizukuSettings.isDeviceHardeningEnabled()) {
                                DeviceOptimizer.applyFixes(context)
                            }
                            SettingsHelper.autoGrantPrivileges(context)
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to apply optimizations and privileges on RUNNING")
                    }
                }

                if ((newState == State.STOPPED || newState == State.CRASHED) &&
                    ShizukuSettings.isAutoCloseTcpPortEnabled()
                ) {
                    try {
                        val context = ShizukuApplication.appContext
                        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                            // Close the ADB TCP listening port by disabling wireless debugging.
                            // This runs asynchronously so it doesn't block the state transition.
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    Settings.Global.putInt(
                                        context.contentResolver,
                                        "adb_wifi_enabled",
                                        0,
                                    )
                                    Timber.tag("ShizukuStateMachine").i("auto-closed TCP port (disabled wireless debugging)")
                                } catch (e: Exception) {
                                    Timber.tag("ShizukuStateMachine").w(e, "Failed to auto-close TCP port")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to initiate TCP auto-close on $newState")
                    }
                }

                // Persist so a future cold-started process (see loadPersistedSettledState() above)
                // can tell a crash from a deliberate stop instead of defaulting to "assume stopped".
                try {
                    ShizukuSettings.setLastSettledState(newState.name)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to persist settled state")
                }
            }

            // Broadcast state change for widgets and other receivers
            try {
                val context = ShizukuApplication.appContext
                val intent =
                    android.content.Intent("af.shizuku.manager.action.STATE_CHANGED").apply {
                        setPackage(context.packageName)
                    }
                context.sendBroadcast(intent)
            } catch (_: UninitializedPropertyAccessException) {
                Timber.tag("ShizukuStateMachine").w("Skipping broadcast: appContext not initialized yet")
            }
        }
    }

    /**
     * A new install's Watchdog default turns on with its first server, so that until a server has
     * run the watchdog starts nothing and shows nothing (see ShizukuSettings.decideWatchdogDefault).
     */
    private fun applyWatchdogDefault() {
        try {
            val context = ShizukuApplication.appContext
            if (ShizukuSettings.applyWatchdogDefaultOnFirstRun(context)) {
                ManagerActivityLog.log(context, "Watchdog: turned on with the first server start (the default for a new install)")
            }
        } catch (e: Exception) {
            Timber.tag("ShizukuStateMachine").w(e, "Failed to apply the new-install Watchdog default")
        }
    }

    fun set(newState: State) = transition { newState }

    fun setDead() =
        transition {
            when (it) {
                State.RUNNING -> State.CRASHED
                State.STOPPING -> {
                    try {
                        val context = ShizukuApplication.appContext
                        val permissionGranted = context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                        val shouldDisableUsbDebugging = permissionGranted && ShizukuSettings.getAutoDisableUsbDebugging()
                        if (shouldDisableUsbDebugging) {
                            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                        }
                    } catch (_: UninitializedPropertyAccessException) {
                        Timber.tag("ShizukuStateMachine").w("Skipping USB debugging disable: appContext not initialized yet")
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to disable USB debugging")
                    }
                    State.STOPPED
                }
                else -> it
            }
        }

    fun update(): State {
        val span = Sentry.getSpan()?.startChild("ipc.shizuku", "pingBinder")
        val isAlive =
            try {
                Shizuku.pingBinder()
            } catch (_: Exception) {
                false
            } finally {
                span?.finish()
            }

        val currentState = get()
        val state =
            when {
                isAlive -> State.RUNNING
                currentState == State.STARTING -> {
                    // Break out of STARTING after 90 s so a failed start (server process died,
                    // ADB connection refused, etc.) never leaves the UI permanently locked.
                    val elapsed = System.currentTimeMillis() - startingTimestamp.get()
                    // A start waiting on adbd's authorisation dialog is still in progress; calling
                    // it STOPPED would invite a second start, and with it a second dialog.
                    if (elapsed > STARTING_TIMEOUT_MS &&
                        !af.shizuku.manager.adb.AdbAuthWait
                            .isWaiting()
                    ) {
                        State.STOPPED
                    } else {
                        State.STARTING
                    }
                }
                currentState == State.STOPPING -> State.STOPPING
                currentState == State.CRASHED -> State.CRASHED
                // Was RUNNING (or, thanks to loadPersistedSettledState(), a freshly cold-started
                // process that persisted RUNNING before it died) and the binder isn't answering: that's
                // a crash, not a stop. Previously fell into the `else -> STOPPED` branch below, which
                // WatchdogService's flow collector (only listens for CRASHED) silently ignores - the
                // watchdog's external re-arm (#417) never restarted anything because every unexpected
                // death got misreported as an intentional stop.
                currentState == State.RUNNING -> State.CRASHED
                else -> State.STOPPED
            }
        set(state)
        return state
    }

    fun isRunning(): Boolean = get() == State.RUNNING

    /**
     * True when the running privileged server was started by an app build older than the one now
     * installed — i.e. the app was updated but the server (a separate long-lived process) is still
     * running the old code. The binder wire protocol can differ across versions, so this can
     * silently break connections for third-party apps until the service is restarted. Returns false
     * when the starting build is unknown (0, e.g. server predates this tracking) or already current.
     */
    fun isServerVersionSkewed(): Boolean {
        if (!isRunning()) return false
        val startedBuild = ShizukuSettings.getServerStartedBuild()
        return startedBuild in 1 until BuildConfig.VERSION_CODE
    }

    private fun currentBinder(): IBinder? = runCatching { Shizuku.getBinder() }.getOrNull()

    /**
     * Records this build as the running server's starter when a start of ours is pending and the
     * server now answering is a new one (its binder differs from the one present when the start
     * began). A restart that failed over a live older server leaves the binder unchanged, so the
     * older build stays recorded and the skew stays visible. The pending start is kept until a new
     * server arrives, so a start that timed out to STOPPED is still recorded when it comes up late.
     */
    private fun recordIfNewServer() {
        val start = pendingStart.get() ?: return
        val binder = currentBinder() ?: return
        if (binder === start.binder) return
        if (!pendingStart.compareAndSet(start, null)) return
        try {
            ShizukuSettings.setServerStartedBuild(BuildConfig.VERSION_CODE)
        } catch (e: Exception) {
            Timber.tag("ShizukuStateMachine").w(e, "Failed to record server start build")
        }
    }

    fun isDead(): Boolean = (get() == State.STOPPED || get() == State.CRASHED)

    /**
     * Calls [listener] with the current state and on every transition, always on the main thread,
     * so it may touch views. Use [asFlow] to follow the state from another thread.
     */
    fun addListener(listener: (State) -> Unit) = addListener(listener, onMainThread = true)

    private fun addListener(
        listener: (State) -> Unit,
        onMainThread: Boolean,
    ) {
        listeners.add(Registration(listener, onMainThread))
        if (onMainThread) {
            runOnMainThread { if (listeners.any { it.listener === listener }) notify(listener, state.get()) }
        } else {
            notify(listener, state.get())
        }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.removeAll { it.listener === listener }
    }

    // The flow's listener only hands the state to a channel, which is thread-safe, so it is called
    // inline: the watchdog and start-notification collectors do not wait on the main thread.
    fun asFlow(): Flow<State> =
        callbackFlow {
            val listener: (State) -> Unit = { trySend(it).isSuccess }
            addListener(listener, onMainThread = false)
            awaitClose { removeListener(listener) }
        }

    private fun deliver(newState: State) {
        listeners.forEach { if (!it.onMainThread) notify(it.listener, newState) }
        if (listeners.any { it.onMainThread }) {
            // Read the list when the delivery runs: a listener removed meanwhile (a destroyed
            // activity's) is not called, one added meanwhile already had the current state.
            runOnMainThread { listeners.forEach { if (it.onMainThread) notify(it.listener, newState) } }
        }
    }

    private fun notify(
        listener: (State) -> Unit,
        newState: State,
    ) {
        try {
            listener(newState)
        } catch (e: Exception) {
            Timber.tag("ShizukuStateMachine").w(e, "State listener failed on $newState")
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        val main = runCatching { Looper.getMainLooper() }.getOrNull()
        if (main == null || (Looper.myLooper() == main && queuedMainDeliveries.get() == 0)) {
            block()
            return
        }
        queuedMainDeliveries.incrementAndGet()
        val posted =
            mainHandler.post {
                try {
                    block()
                } finally {
                    queuedMainDeliveries.decrementAndGet()
                }
            }
        if (!posted) queuedMainDeliveries.decrementAndGet()
    }
}
