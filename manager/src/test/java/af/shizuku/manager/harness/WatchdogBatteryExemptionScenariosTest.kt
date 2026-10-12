package af.shizuku.manager.harness

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ActivityLogSettingsImpl
import af.shizuku.manager.utils.BatteryOptimizationGuard
import af.shizuku.manager.utils.DeviceOptimizer
import af.shizuku.manager.utils.SettingsBackupManager
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ShizukuStateMachine.State
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * #36: a new install's Watchdog default turns the Watchdog on, and the app tells the user if
 * Android's battery optimization still restricts it.
 *
 * Switching the Watchdog on by hand already asks (BaseSettingsFragment.maybeToggleBatterySensitiveSetting),
 * and the Home screen shows the battery-optimisation snackbar when the Watchdog or start-on-boot is on,
 * but neither covers a new install whose first server start happens in the background (boot, headless,
 * the tile) with Device Hardening off or its whitelist command failing. When the default turns the
 * Watchdog on, the app checks the exemption after Device Hardening's pass and, if it is still not exempt,
 * writes this to the activity log and posts one notification whose tap opens the exemption request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchdogBatteryExemptionScenariosTest {
    private lateinit var world: FakeWorld
    private val app: Application get() = world.app

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        ActivityLogManager.initialize(app, ActivityLogSettingsImpl())
        DeviceOptimizer.onAppWhitelistedForTesting = null
        SettingsHelper.isIgnoringBatteryOptimizationsForTesting = null
        world.prefs
            .edit()
            .putBoolean("tcp_mode", false)
            .remove("mode")
            .commit()
        drainWatchdogStarts()
    }

    @After
    fun tearDown() {
        DeviceOptimizer.onAppWhitelistedForTesting = null
        SettingsHelper.isIgnoringBatteryOptimizationsForTesting = null
        world.close()
    }

    private fun scenario(block: Scenario.() -> Unit) = world.scenario(block)

    private fun appStarts(upgraded: Boolean) {
        ShizukuSettings.decideWatchdogDefault(upgraded)
        world.settle()
    }

    private fun serverStarted() {
        val latch = CountDownLatch(1)
        ShizukuStateMachine.postRunningLatchForTesting = latch
        ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
        world.serverUp()
        ShizukuStateMachine.set(State.RUNNING)
        world.settle()
        world.waitUntil({ "the post-RUNNING work finished" }) { latch.count == 0L }
        world.settle()
    }

    private fun serverKilled() {
        world.serverDown()
        ShizukuStateMachine.setDead()
        world.settle()
    }

    private fun setExempt(exempt: Boolean) {
        SettingsHelper.isIgnoringBatteryOptimizationsForTesting = exempt
    }

    private fun setDeviceHardening(enabled: Boolean) {
        world.prefs
            .edit()
            .putBoolean(ShizukuSettings.Keys.KEY_DEVICE_HARDENING_ENABLED, enabled)
            .commit()
    }

    private fun drainWatchdogStarts(): Int {
        var n = 0
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: break
            if (intent.component?.className == WatchdogService::class.java.name) n++
        }
        return n
    }

    private fun notificationManager(): NotificationManager =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun getActiveNotification(id: Int): Notification? =
        notificationManager().activeNotifications.firstOrNull { it.id == id }?.notification

    private fun gapLines(): Int =
        ActivityLogManager.logs.value.count { it.action == BatteryOptimizationGuard.GAP_LOG_ACTION }

    // --- #36: the new-install Watchdog default reports the battery exemption ---

    @Test
    fun `new-install-first-start-no-exemption-hardening-off-records-gap-and-posts-notification`() =
        scenario {
            setExempt(false)
            setDeviceHardening(false)
            appStarts(upgraded = false)
            val before = gapLines()
            serverStarted()
            world.waitUntil({ "the gap to be recorded in the activity log" }) { gapLines() == before + 1 }
            check {
                assertTrue("watchdog on", ShizukuSettings.getWatchdog())
                val notification = getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID)
                assertNotNull("battery-exemption notification posted", notification)

                // The tap opens the AOSP exemption request for this package, immutable.
                val contentIntent = notification!!.contentIntent
                assertTrue("content intent immutable", shadowOf(contentIntent).isImmutable)
                val saved = shadowOf(contentIntent).savedIntent
                assertEquals(
                    "AOSP exemption action",
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    saved.action,
                )
                assertEquals("package data", Uri.parse("package:" + app.packageName), saved.data)
            }
        }

    @Test
    fun `new-install-first-start-exemption-granted-posts-nothing`() =
        scenario {
            setExempt(true)
            setDeviceHardening(false)
            appStarts(upgraded = false)
            val before = gapLines()
            serverStarted()
            check {
                assertTrue("watchdog on", ShizukuSettings.getWatchdog())
                assertEquals("no gap line", before, gapLines())
                assertNull(
                    "no battery-exemption notification",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
            }
        }

    @Test
    fun `new-install-first-start-hardening-grants-exemption-posts-nothing`() =
        scenario {
            setExempt(false)
            setDeviceHardening(true)
            // The whitelist command is what grants the exemption: simulate its effect so the check
            // (which must run after the pass) observes the grant and posts nothing. If the check ran
            // before the pass, it would still see "not exempt" and post — this test fails then.
            DeviceOptimizer.onAppWhitelistedForTesting = { setExempt(true) }
            appStarts(upgraded = false)
            val before = gapLines()
            serverStarted()
            check {
                assertTrue("watchdog on", ShizukuSettings.getWatchdog())
                assertEquals("no gap line", before, gapLines())
                assertNull(
                    "no battery-exemption notification",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
            }
        }

    @Test
    fun `later-start-with-default-settled-does-not-post-again`() =
        scenario {
            setExempt(false)
            setDeviceHardening(false)
            appStarts(upgraded = false)
            val before = gapLines()
            serverStarted()
            world.waitUntil({ "the gap to be recorded in the activity log" }) { gapLines() == before + 1 }
            assertNotNull(
                "notification posted on the first start",
                getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
            )

            notificationManager().cancel(BatteryOptimizationGuard.NOTIFICATION_ID)
            serverKilled()
            serverStarted()
            check {
                assertEquals("still one gap line after a later start", before + 1, gapLines())
                assertNull(
                    "no second notification",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
            }
        }

    @Test
    fun `update-keeping-users-watchdog-setting-posts-nothing`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            setExempt(false)
            setDeviceHardening(false)
            appStarts(upgraded = true)
            val before = gapLines()
            serverStarted()
            check {
                assertTrue("watchdog setting kept", ShizukuSettings.getWatchdog())
                assertEquals("no gap line", before, gapLines())
                assertNull(
                    "no battery-exemption notification",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
            }
        }

    @Test
    fun `user-turns-the-watchdog-on-by-hand-before-the-first-start-posts-nothing`() =
        scenario {
            // A new install whose user toggles the Watchdog on in Settings before any server has run:
            // the choice is stored, so the new-install default path never fires and nothing is posted.
            appStarts(upgraded = false)
            ShizukuSettings.setWatchdog(app, true)
            drainWatchdogStarts()
            setExempt(false)
            setDeviceHardening(false)
            val before = gapLines()
            serverStarted()
            check {
                assertTrue("watchdog on", ShizukuSettings.getWatchdog())
                assertEquals("no gap line", before, gapLines())
                assertNull(
                    "no battery-exemption notification",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
            }
        }

    @Test
    fun `process-death-before-the-check-runs-the-next-start-runs-it-once`() =
        scenario {
            setExempt(false)
            setDeviceHardening(false)
            appStarts(upgraded = false)
            val before = gapLines()

            // The default turns the Watchdog on and marks the exemption check pending, but the
            // process dies before the post-RUNNING coroutine runs it: settle the default directly
            // and persist, without letting the check run.
            ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
            world.serverUp()
            assertTrue("default turned the Watchdog on", ShizukuSettings.applyWatchdogDefaultOnFirstRun(app))
            assertTrue("exemption check pending", ShizukuSettings.isWatchdogExemptionPending())
            world.prefs.flush()
            world.processDeath()
            assertTrue("pending survives the death", ShizukuSettings.isWatchdogExemptionPending())

            // The next start runs the check once, then clears the flag.
            serverStarted()
            world.waitUntil({ "the gap to be recorded in the activity log" }) { gapLines() == before + 1 }
            check {
                assertNotNull(
                    "notification posted",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
                assertFalse("pending flag cleared", ShizukuSettings.isWatchdogExemptionPending())
            }
        }

    @Test
    fun `samsung-device-care-battery-last-fallback-is-the-generic-action-intent`() {
        val current = SettingsPage.Samsung.DeviceCareBattery.currentComponentIntent(app)
        assertEquals(
            "current path is the new One UI component",
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            current.component,
        )

        // The last fallback (super.launch) sends the generic action, not a component, so a Samsung
        // device with neither the new nor the old path still opens whatever handles the action.
        val fallback = SettingsPage.Samsung.DeviceCareBattery.buildIntent(app)
        assertEquals("com.samsung.android.sm.ACTION_BATTERY", fallback.action)
        assertNull("fallback has no explicit component", fallback.component)
    }

    @Test
    fun `watchdog-turned-off-after-the-default-posts-nothing`() =
        scenario {
            setExempt(false)
            setDeviceHardening(false)
            appStarts(upgraded = false)
            val before = gapLines()

            // The default turns the Watchdog on and marks the check pending, but the process dies
            // and the user turns the Watchdog off before the next start.
            ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
            world.serverUp()
            assertTrue("default turned the Watchdog on", ShizukuSettings.applyWatchdogDefaultOnFirstRun(app))
            assertTrue("exemption check pending", ShizukuSettings.isWatchdogExemptionPending())
            world.prefs.flush()
            world.processDeath()
            ShizukuSettings.setWatchdog(app, false)
            drainWatchdogStarts()

            serverStarted()
            check {
                assertFalse("watchdog off", ShizukuSettings.getWatchdog())
                assertEquals("no gap line", before, gapLines())
                assertNull(
                    "no battery-exemption notification",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
                assertFalse("pending flag cleared", ShizukuSettings.isWatchdogExemptionPending())
            }
        }

    @Test
    fun `notifications-disabled-logs-the-gap-and-retries-on-a-later-start`() =
        scenario {
            setExempt(false)
            setDeviceHardening(false)
            appStarts(upgraded = false)
            shadowOf(notificationManager()).setNotificationsEnabled(false)
            val before = gapLines()

            // First start: the gap is logged but the notification can't be posted, so the flag stays.
            serverStarted()
            world.waitUntil({ "the gap to be recorded in the activity log" }) { gapLines() == before + 1 }
            check {
                assertNull(
                    "no notification while disabled",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
                assertTrue("pending flag still set", ShizukuSettings.isWatchdogExemptionPending())
            }

            // A second start while still disabled (a Watchdog restart) does not log the gap again.
            serverKilled()
            serverStarted()
            check { assertTrue("pending flag still set", ShizukuSettings.isWatchdogExemptionPending()) }

            // Notifications enabled; the next start posts and clears the flag.
            shadowOf(notificationManager()).setNotificationsEnabled(true)
            serverKilled()
            serverStarted()
            world.waitUntil({ "the notification to be posted" }) {
                getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID) != null && gapLines() >= before + 2
            }
            check {
                assertEquals("the gap is logged once while blocked and once when posted", before + 2, gapLines())
                assertNotNull(
                    "notification posted",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
                assertFalse("pending flag cleared", ShizukuSettings.isWatchdogExemptionPending())
            }
        }

    @Test
    fun `backup-export-excludes-the-pending-exemption-flag`() =
        scenario {
            ShizukuSettings
                .getPreferences()
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG_EXEMPTION_PENDING, true)
                .commit()
            val backup = SettingsBackupManager.export(app)

            world.prefs
                .edit()
                .clear()
                .commit()
            assertFalse("fresh install has no flag", ShizukuSettings.isWatchdogExemptionPending())
            assertTrue("import succeeds", SettingsBackupManager.importAndCommit(app, backup))
            assertFalse("the flag is excluded from the backup", ShizukuSettings.isWatchdogExemptionPending())

            // Nothing posts on the fresh install's first start.
            setExempt(false)
            setDeviceHardening(false)
            appStarts(upgraded = false)
            val before = gapLines()
            serverStarted()
            check {
                assertEquals("nothing posts", before, gapLines())
                assertNull(
                    "no battery-exemption notification",
                    getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID),
                )
            }
        }
}
