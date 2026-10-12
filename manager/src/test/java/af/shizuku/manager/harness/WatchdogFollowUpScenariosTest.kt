package af.shizuku.manager.harness

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.service.ShizukuLiveService
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.settings.BehaviorSettingsFragment
import af.shizuku.manager.utils.ActivityLogSettingsImpl
import af.shizuku.manager.utils.LiveActivityNotificationManager
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ShizukuStateMachine.State
import af.shizuku.manager.worker.AdbStartWorker
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.preference.TwoStatePreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Scenario tests for Watchdog follow-ups:
 * #32: Watchdog switch state, not-running summary, and dynamic refresh on service start/stop.
 * #33: Single persistent notification (1001 carrying live text) when Watchdog & live activity are both on.
 * #34: Switching Watchdog off leaves no Watchdog or live persistent notifications in the shade.
 * #35: Crash heads-up notification (1002) suppressed on first successful restart; logged to activity log.
 * Review fix: a startForegroundService() start of ShizukuLiveService while the Watchdog runs still
 * calls startForeground() before stopping (a start that stops without it crashes on API 26+).
 * Follow-ups: a second crash within the recovery window posts one heads-up and no stale recovery
 * line; the live service's onCreate collector never re-posts 9999 under the Watchdog (the server
 * RUNNING); live activity with the Watchdog off still posts 9999 as before; setWatchdog(false)
 * stops a running ShizukuLiveService.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchdogFollowUpScenariosTest {
    private lateinit var world: FakeWorld
    private var watchdog: ServiceController<WatchdogService>? = null
    private var liveService: ServiceController<ShizukuLiveService>? = null
    private var activityController: ActivityController<FragmentActivity>? = null
    private val app: Application get() = world.app
    private val grace = WatchdogService.RESTART_GRACE_MS

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        ActivityLogManager.initialize(app, ActivityLogSettingsImpl())
        world.prefs
            .edit()
            .putBoolean("tcp_mode", false)
            .remove("mode")
            .commit()
        drainWatchdogStarts()
    }

    @After
    fun tearDown() {
        activityController?.destroy()
        liveService?.destroy()
        watchdog?.destroy()
        world.close()
    }

    private fun scenario(block: Scenario.() -> Unit) = world.scenario(block)

    private fun serverStarted() {
        ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
        world.serverUp()
        ShizukuStateMachine.set(State.RUNNING)
        world.settle()
    }

    private fun serverKilled() {
        world.serverDown()
        ShizukuStateMachine.setDead()
        world.settle()
    }

    private fun newServerBinderArrives() {
        world.serverUp()
        ShizukuStateMachine.set(State.RUNNING)
        world.settle()
    }

    private fun elapse(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        world.settle()
    }

    private fun drainWatchdogStarts(): Int {
        var n = 0
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: break
            if (intent.component?.className == WatchdogService::class.java.name) n++
        }
        return n
    }

    private fun startsQueued(): Int =
        world.workManager
            .getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME)
            .get()
            .size

    private fun notificationManager(): NotificationManager =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun getActiveNotification(id: Int): Notification? =
        notificationManager().activeNotifications.firstOrNull { it.id == id }?.notification

    private fun createBehaviorFragment(): Pair<FragmentActivity, BehaviorSettingsFragment> {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.Theme)
        controller.create().start().resume()
        activityController = controller
        val fragment = BehaviorSettingsFragment()
        controller
            .get()
            .supportFragmentManager
            .beginTransaction()
            .add(fragment, "behavior")
            .commitNow()
        return controller.get() to fragment
    }

    // --- Issue #32: Switch shows off while setting is on; summary reflects running state ---

    @Test
    fun `setting-on-with-service-down-shows-switch-on-and-not-running-summary`() =
        scenario {
            // Setting is stored as ON, but the service is not running
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            assertFalse("service not running", WatchdogService.isRunning())

            val (_, fragment) = createBehaviorFragment()
            val pref = fragment.findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_WATCHDOG)
            assertNotNull("watchdog preference exists", pref)
            assertTrue("switch shows ON when setting is ON", pref!!.isChecked)
            assertEquals(
                "summary says 'On, not running right now'",
                app.getString(R.string.settings_watchdog_not_running),
                pref.summary.toString(),
            )
        }

    @Test
    fun `pending-default-with-service-down-shows-switch-on-and-not-running-summary`() =
        scenario {
            // New install with default pending (not yet stored in prefs, no server ran)
            world.prefs
                .edit()
                .putString(ShizukuSettings.Keys.KEY_WATCHDOG_DEFAULT, "pending")
                .commit()
            assertTrue("pending default", ShizukuSettings.isWatchdogDefaultPending())
            assertFalse("service not running", WatchdogService.isRunning())

            val (_, fragment) = createBehaviorFragment()
            val pref = fragment.findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_WATCHDOG)
            assertNotNull(pref)
            assertTrue("switch shows ON when default pending", pref!!.isChecked)
            assertEquals(
                "summary says 'On, not running right now'",
                app.getString(R.string.settings_watchdog_not_running),
                pref.summary.toString(),
            )
        }

    @Test
    fun `setting-on-with-service-running-shows-switch-on-and-standard-summary`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            assertTrue("service running", WatchdogService.isRunning())

            val (_, fragment) = createBehaviorFragment()
            val pref = fragment.findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_WATCHDOG)
            assertNotNull(pref)
            assertTrue("switch shows ON", pref!!.isChecked)
            assertEquals(
                "summary shows standard description when service is running",
                app.getString(R.string.settings_watchdog_summary),
                pref.summary.toString(),
            )
        }

    @Test
    fun `setting-off-shows-switch-off-and-standard-summary`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, false)
                .commit()
            assertFalse("service not running", WatchdogService.isRunning())

            val (_, fragment) = createBehaviorFragment()
            val pref = fragment.findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_WATCHDOG)
            assertNotNull(pref)
            assertFalse("switch shows OFF", pref!!.isChecked)
            assertEquals(
                "summary shows standard description when off",
                app.getString(R.string.settings_watchdog_summary),
                pref.summary.toString(),
            )
        }

    @Test
    fun `service-lifecycle-dynamically-refreshes-switch-summary`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            assertFalse("service initially not running", WatchdogService.isRunning())

            val (_, fragment) = createBehaviorFragment()
            val pref = fragment.findPreference<TwoStatePreference>(ShizukuSettings.Keys.KEY_WATCHDOG)!!
            assertEquals(
                app.getString(R.string.settings_watchdog_not_running),
                pref.summary.toString(),
            )

            // Service starts while screen is active
            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            assertTrue("service running", WatchdogService.isRunning())
            world.settle()
            assertEquals(
                "summary automatically refreshed to standard on service start",
                app.getString(R.string.settings_watchdog_summary),
                pref.summary.toString(),
            )

            // Service stops while screen is active
            watchdog?.destroy()
            watchdog = null
            assertFalse("service stopped", WatchdogService.isRunning())
            world.settle()
            assertEquals(
                "summary automatically refreshed to not-running on service stop",
                app.getString(R.string.settings_watchdog_not_running),
                pref.summary.toString(),
            )
        }

    // --- Issue #33: One persistent notification when Watchdog and live activity are both on ---

    @Test
    fun `watchdog-and-live-activity-both-on-shows-only-watchdog-notification-carrying-live-text`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .putBoolean(ShizukuSettings.Keys.KEY_LIVE_ACTIVITY_ENABLED, true)
                .commit()

            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            val notif1001 = getActiveNotification(1001)
            assertNotNull("watchdog FGS notification 1001 posted", notif1001)

            // Live status event is reported
            LiveActivityNotificationManager.show(app, "Settings: opened")
            world.settle()

            // Notification 1001 should now carry the live text, and 9999 must NOT be posted
            val updated1001 = getActiveNotification(1001)
            assertNotNull("watchdog notification 1001 remains", updated1001)
            val text1001 = updated1001!!.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            assertEquals("watchdog notification 1001 carries live text", "Settings: opened", text1001)

            val notif9999 = getActiveNotification(9999)
            assertNull("live notification 9999 must NOT be posted while watchdog is on", notif9999)
        }

    @Test
    fun `watchdog-off-with-live-activity-on-still-posts-live-notification-9999`() =
        scenario {
            serverStarted()
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, false)
                .putBoolean(ShizukuSettings.Keys.KEY_LIVE_ACTIVITY_ENABLED, true)
                .commit()

            // The live service runs as before this change (only the Watchdog-on case routes to
            // 1001): with the Watchdog off it posts 9999 on RUNNING and keeps it foreground.
            liveService =
                Robolectric
                    .buildService(ShizukuLiveService::class.java)
                    .create()
                    .startCommand(0, 0)
            world.settle()
            assertNotNull("live notification 9999 posted with the Watchdog off", getActiveNotification(9999))

            // The live-status entry the activity log routes through (LiveActivityNotificationManager
            // .show) still goes to 9999 itself, not to the Watchdog's 1001 as when both are on.
            LiveActivityNotificationManager.show(app, "Settings: opened")
            world.settle()
            val notif9999 = getActiveNotification(9999)
            assertNotNull("live notification 9999 still posted", notif9999)
            assertEquals(
                "live notification 9999 carries the live text",
                "Settings: opened",
                notif9999!!.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            )
            assertNull("no watchdog notification 1001 posted with the Watchdog off", getActiveNotification(1001))
        }

    // --- Issue #34: Turning Watchdog off leaves no Watchdog or live persistent notifications ---

    @Test
    fun `switching-watchdog-off-clears-watchdog-and-live-notifications-and-stops-services`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .putBoolean(ShizukuSettings.Keys.KEY_LIVE_ACTIVITY_ENABLED, true)
                .commit()

            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            LiveActivityNotificationManager.show(app, "System Bridge Active")
            world.settle()

            // Turning off watchdog through ShizukuSettings
            ShizukuSettings.setWatchdog(app, false)
            world.settle()

            assertNull("watchdog notification 1001 is dismissed", getActiveNotification(1001))
            assertNull("live notification 9999 is dismissed", getActiveNotification(9999))
        }

    @Test
    fun `switching-watchdog-off-stops-the-running-live-service`() =
        scenario {
            serverStarted()
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, false)
                .putBoolean(ShizukuSettings.Keys.KEY_LIVE_ACTIVITY_ENABLED, true)
                .commit()

            // The live service is running (the Watchdog is off): 9999 posted, not stopped.
            liveService =
                Robolectric
                    .buildService(ShizukuLiveService::class.java)
                    .create()
                    .startCommand(0, 0)
            world.settle()
            assertNotNull("live notification 9999 posted while the live service runs", getActiveNotification(9999))
            assertFalse("live service not stopped by itself", shadowOf(liveService!!.get()).isStoppedBySelf)

            // The Watchdog is turned on next (its service starts; its onCreate asks the system to
            // stop the legacy live service, which has not landed yet — the window where both run).
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            world.settle()

            // Swallow the Watchdog's own stop request of the live service so the assertion below
            // is about setWatchdog(false)'s stop, not that earlier one.
            var swallowedStops = 0
            while (shadowOf(app).nextStoppedService != null) swallowedStops++
            assertTrue(
                "the Watchdog's onCreate asked to stop the legacy live service",
                swallowedStops >= 1,
            )

            ShizukuSettings.setWatchdog(app, false)
            world.settle()

            // setWatchdog(false) stops the running live service (before the Watchdog's own).
            val stopped = shadowOf(app).nextStoppedService
            assertNotNull("a service was stopped by setWatchdog(false)", stopped)
            assertEquals(
                "setWatchdog(false) stops the running live service",
                ShizukuLiveService::class.java.name,
                stopped!!.component?.className,
            )
            assertNull("watchdog notification 1001 is dismissed", getActiveNotification(1001))
            assertNull("live notification 9999 is dismissed", getActiveNotification(9999))
        }

    // --- Review fix: a startForegroundService() start of the live service while the Watchdog runs ---

    @Test
    fun `live-service-start-with-watchdog-on-enters-foreground-before-stopping`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .putBoolean(ShizukuSettings.Keys.KEY_LIVE_ACTIVITY_ENABLED, true)
                .commit()

            watchdog = Robolectric.buildService(WatchdogService::class.java).create()

            // The system restarts the sticky live service (e.g. after the app's process died)
            // with startForegroundService; the Watchdog owns the notification now, so the live
            // service must stop — but a startForegroundService() start that stops without
            // startForeground crashes the app on API 26+.
            liveService =
                Robolectric
                    .buildService(ShizukuLiveService::class.java)
                    .create()
                    .startCommand(0, 0)
            val service = liveService!!.get()
            world.settle()

            assertTrue(
                "live service called startForeground before stopping",
                shadowOf(service).lastForegroundNotificationId ==
                    LiveActivityNotificationManager.NOTIFICATION_ID,
            )
            assertTrue("live service removed its foreground notification", shadowOf(service).isForegroundStopped)
            assertTrue("live service stopped itself", shadowOf(service).isStoppedBySelf)
            assertNull("no live notification 9999 while the watchdog runs", getActiveNotification(9999))
        }

    @Test
    fun `live-service-start-with-watchdog-on-and-server-running-never-reposts-9999`() =
        scenario {
            // The state machine is RUNNING when the live service starts (a sticky restart of it
            // after process death, say) with the Watchdog on: onStartCommand stops the service
            // and removes 9999, and the onCreate collector — which the looper runs afterwards —
            // must not post 9999 again while the Watchdog owns the notification.
            serverStarted()
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .putBoolean(ShizukuSettings.Keys.KEY_LIVE_ACTIVITY_ENABLED, true)
                .commit()

            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            liveService =
                Robolectric
                    .buildService(ShizukuLiveService::class.java)
                    .create()
                    .startCommand(0, 0)
            world.settle()

            val service = liveService!!.get()
            assertNull("no live notification 9999 after the looper idles", getActiveNotification(9999))
            assertTrue("live service removed its foreground notification", shadowOf(service).isForegroundStopped)
            assertTrue("live service stopped itself", shadowOf(service).isStoppedBySelf)
            assertEquals(
                "live service reached startForeground before stopping",
                LiveActivityNotificationManager.NOTIFICATION_ID,
                shadowOf(service).lastForegroundNotificationId,
            )
        }

    // --- Issue #35: Crash heads-up notification suppressed on successful first restart ---

    @Test
    fun `first-restart-with-server-recovering-suppresses-crash-heads-up-and-logs-recovery`() =
        scenario {
            serverStarted()
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            world.settle()

            // First crash
            serverKilled()
            assertEquals(State.CRASHED, ShizukuStateMachine.get())

            // Wait past the grace period for watchdog to trigger restart #1
            elapse(grace + 1_000)
            assertEquals("starts queued", 1, startsQueued())

            // Server comes back within the bounded recovery window
            newServerBinderArrives()
            elapse(grace)

            // Crash heads-up (1002) should NOT be posted
            assertNull("crash heads-up 1002 suppressed on first successful restart", getActiveNotification(1002))

            // Recovery is logged to ActivityLogManager
            val logs = ActivityLogManager.logs.value
            assertTrue(
                "activity log contains recovery line",
                logs.any { it.action.contains("Watchdog: the server came back after restart #1") },
            )
        }

    @Test
    fun `first-restart-with-server-not-recovering-posts-crash-heads-up`() =
        scenario {
            serverStarted()
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            world.settle()

            // First crash
            serverKilled()
            assertEquals(State.CRASHED, ShizukuStateMachine.get())

            // Wait past grace period for restart #1
            elapse(grace + 1_000)
            assertEquals("starts queued", 1, startsQueued())

            // Server does NOT come back; bounded recovery window expires
            elapse(WatchdogService.RECOVERY_WAIT_MS + 1_000)

            // Crash heads-up (1002) MUST be posted
            assertNotNull("crash heads-up 1002 posted when server does not come back", getActiveNotification(1002))
        }

    @Test
    fun `repeated-restart-posts-crash-heads-up-immediately`() =
        scenario {
            serverStarted()
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            world.settle()

            // First crash and restart (which doesn't recover within the recovery window)
            serverKilled()
            elapse(grace + 1_000) // triggers restart #1
            val restart1WallMs = System.currentTimeMillis()
            elapse(WatchdogService.RECOVERY_WAIT_MS + 1_000) // recovery times out, posts 1002
            notificationManager().cancel(1002) // clear for next test assertion
            world.settle()

            // The dialog restart #1 raised is answered (Allow), so its unanswered marker no
            // longer withholds later restarts, and the server comes back up before the second crash.
            keyOffered()
            dialogAccepted()
            newServerBinderArrives()
            world.settle()

            // Second crash (consecutiveCrashes was > 0)
            val cooldown = WatchdogService.backoffMs(1)
            serverKilled()
            // The watchdog spaces restarts by a wall-clock cooldown (System.currentTimeMillis,
            // which Robolectric does not virtualise along with the looper clock), so real time
            // must pass it before the crash block's grace delay is elapsed below.
            world.waitUntil({ "the restart cooldown (${cooldown}ms) to pass" }) {
                System.currentTimeMillis() - restart1WallMs > cooldown
            }
            elapse(grace + 1_000)

            // On repeated restart, crash heads-up (1002) is posted immediately upon restart
            assertNotNull("crash heads-up 1002 posted for repeated restart", getActiveNotification(1002))
        }

    @Test
    fun `second-crash-within-recovery-window-posts-one-heads-up-and-no-stale-recovery-line`() =
        scenario {
            serverStarted()
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            world.settle()

            // Crash #1 → restart #1 (a first restart): the 60 s recovery job is armed
            // instead of the heads-up.
            serverKilled()
            elapse(grace + 1_000)
            assertEquals("starts queued", 1, startsQueued())
            val restart1WallMs = System.currentTimeMillis()

            // Restart #1 raises adbd's authorisation dialog (the marker set with the offer).
            // The real narrow case has it answered — the marker cleared with it — while the
            // RUNNING its short-lived server brought is missed by the recovery job; here the
            // answer's marker clearing is driven directly, so the RUNNING stays missed.
            keyOffered()
            AdbAuthWait.clearUnanswered()

            // The restarted server dies again before the machine ever reported RUNNING: a
            // second crash, with restart #1's recovery job still pending. The binder-received
            // listener's RUNNING → binder-dead listener's CRASHED is what the watchdog sees.
            world.serverDown()
            ShizukuStateMachine.set(State.CRASHED)
            world.settle()

            // The Watchdog spaces restarts by a wall-clock cooldown (System.currentTimeMillis,
            // which Robolectric does not virtualise along with the looper clock), so real time
            // must pass it before the crash block's grace delay is elapsed below.
            val cooldown = WatchdogService.backoffMs(1)
            world.waitUntil({ "the restart cooldown (${cooldown}ms) to pass" }) {
                System.currentTimeMillis() - restart1WallMs > cooldown
            }
            elapse(grace + 1_000)

            // The second crash posts the heads-up immediately (it is not a first restart).
            assertNotNull("crash heads-up 1002 posted for the second crash", getActiveNotification(1002))

            // Recovery: a later restart brings the server back.
            newServerBinderArrives()
            world.settle()

            // The stale restart-#1 recovery job must not claim it: left armed, it logs
            // "the server came back after restart #1" here — for restart #2's server. The
            // record list is newest-first, and Robolectric reuses the app singletons across
            // this class's tests (an earlier test's recovery line stays in it), so the check
            // is on the records newer than the second crash's own restart line.
            val logs = ActivityLogManager.logs.value
            val crash2Line = logs.indexOfFirst { it.action == "Watchdog: restarting after crash #2" }
            assertTrue("the second crash's restart line is logged", crash2Line >= 0)
            assertFalse(
                "no stale 'came back after restart #1' line after the second crash's restart",
                logs.take(crash2Line).any { it.action.contains("Watchdog: the server came back after restart #1") },
            )
        }

    @Test
    fun `withheld-restart-does-not-post-crash-heads-up`() =
        scenario {
            serverStarted()
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            world.settle()

            // Mark ADB auth as unanswered
            markerSetAt(now)
            assertTrue("ADB auth wait unanswered", AdbAuthWait.isUnanswered())

            serverKilled()
            elapse(grace * 4)

            assertEquals("no starts queued while auth unanswered", 0, startsQueued())
            assertNull("crash heads-up 1002 not posted for withheld restart", getActiveNotification(1002))
        }
}
