package af.shizuku.manager.harness

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ActivityLogSettingsImpl
import af.shizuku.manager.utils.DeviceOptimizer
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ShizukuStateMachine.State
import af.shizuku.manager.utils.VendorBatteryRestrictions
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowBuild
import java.util.concurrent.CountDownLatch

/**
 * #40: at every start while the Watchdog is on, undo the vendor battery restrictions that can stop
 * it (Samsung's Deep sleeping apps, Xiaomi's autostart, and the universal background-activity and
 * standby-bucket restrictions), and tell the user once what cannot be undone.
 *
 * The shell reads and fixes are faked through [DeviceOptimizer.runCommandOutputForTesting]; the
 * Samsung enabled state and the tap-target resolution are faked through [VendorBatteryRestrictions]
 * seams. Each scenario runs the real post-RUNNING block in [ShizukuStateMachine], so a finding that
 * a scenario cannot make reach the guard is a real ordering bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VendorBatteryRestrictionScenariosTest {
    private lateinit var world: FakeWorld
    private val app: Application get() = world.app

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        ActivityLogManager.initialize(app, ActivityLogSettingsImpl())
        ActivityLogManager.clear()
        DeviceOptimizer.onAppWhitelistedForTesting = null
        DeviceOptimizer.runCommandOutputForTesting = null
        SettingsHelper.isIgnoringBatteryOptimizationsForTesting = null
        VendorBatteryRestrictions.applicationEnabledSettingForTesting = null
        VendorBatteryRestrictions.resolveActivityForTesting = null
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
        DeviceOptimizer.runCommandOutputForTesting = null
        SettingsHelper.isIgnoringBatteryOptimizationsForTesting = null
        VendorBatteryRestrictions.applicationEnabledSettingForTesting = null
        VendorBatteryRestrictions.resolveActivityForTesting = null
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

    private fun logLines(action: String): Int =
        ActivityLogManager.logs.value.count { it.action == action }

    private fun contentIntentOf(id: Int): android.content.Intent {
        val notification = checkNotNull(getActiveNotification(id)) { "no notification $id" }
        return shadowOf(checkNotNull(notification.contentIntent)).savedIntent
    }

    // --- 1. background activity, fix holds: undo log, no notification ---

    @Test
    fun `background-activity-ignore-fix-holds-logs-undid-and-posts-nothing`() =
        scenario {
            setExempt(true)
            setDeviceHardening(true)
            var appops = "ignore"
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.contains("appops set") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> {
                        appops = "allow"
                        "ok"
                    }
                    cmd.contains("appops get") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "RUN_ANY_IN_BACKGROUND: $appops"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertTrue("watchdog on", ShizukuSettings.getWatchdog())
                assertEquals(
                    "the undo is recorded once",
                    1,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_BACKGROUND_ACTIVITY),
                )
                assertEquals("nothing reported", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY))
                assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
            }
        }

    // --- 2. standby bucket 45, fix holds ---

    @Test
    fun `standby-bucket-45-fix-holds-logs-undid-and-posts-nothing`() =
        scenario {
            setExempt(true)
            setDeviceHardening(true)
            var bucket = "45"
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.contains("set-standby-bucket") -> {
                        bucket = "5"
                        "ok"
                    }
                    cmd.contains("get-standby-bucket") -> bucket
                    cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                    else -> null
                }
            }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertTrue("watchdog on", ShizukuSettings.getWatchdog())
                assertEquals(
                    "the undo is recorded once",
                    1,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_STANDBY_BUCKET),
                )
                assertEquals("nothing reported", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_STANDBY_BUCKET))
                assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
            }
        }

    // --- 3. a fix that does not hold: notification opens the app's own settings page ---

    @Test
    fun `background-activity-fix-does-not-hold-posts-notification-with-app-details`() =
        scenario {
            setExempt(true)
            setDeviceHardening(true)
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.contains("appops set") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "ok"
                    cmd.contains("appops get") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "RUN_ANY_IN_BACKGROUND: ignore"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertEquals(
                    "the still-restricted finding is recorded",
                    1,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY),
                )
                val saved = contentIntentOf(VendorBatteryRestrictions.NOTIFICATION_ID)
                assertEquals("app-details action", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, saved.action)
                assertEquals("package data", Uri.parse("package:" + app.packageName), saved.data)
            }
        }

    // --- 4. Device Hardening off: report only, nothing changed ---

    @Test
    fun `device-hardening-off-logs-and-posts-without-changing-anything`() =
        scenario {
            setExempt(true)
            setDeviceHardening(false)
            val calls = mutableListOf<String>()
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                calls += cmd
                when {
                    cmd.contains("appops get") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "RUN_ANY_IN_BACKGROUND: ignore"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertEquals(
                    "the restriction is logged",
                    1,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY),
                )
                assertNotNull("notification posted", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
                assertTrue(
                    "no fix command ran",
                    calls.none { it.contains("appops set") || it.contains("set-standby-bucket") || it.startsWith("pm enable") },
                )
            }
        }

    // --- 5. Watchdog off: nothing read, changed, logged or posted ---

    @Test
    fun `watchdog-off-nothing-read-changed-logged-or-posted`() =
        scenario {
            setExempt(true)
            setDeviceHardening(false)
            val calls = mutableListOf<String>()
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                calls += cmd
                null
            }
            appStarts(upgraded = true)
            serverStarted()
            check {
                assertTrue("watchdog off", !ShizukuSettings.getWatchdog())
                assertTrue("nothing read", calls.isEmpty())
                assertEquals("no undo logged", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_BACKGROUND_ACTIVITY))
                assertEquals("no report logged", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY))
                assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
            }
        }

    // --- 6. nothing restricted: nothing logged or posted ---

    @Test
    fun `nothing-restricted-nothing-logged-or-posted`() =
        scenario {
            setExempt(true)
            setDeviceHardening(true)
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertTrue("watchdog on", ShizukuSettings.getWatchdog())
                assertEquals("no undo logged", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_BACKGROUND_ACTIVITY))
                assertEquals("no report logged", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY))
                assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
            }
        }

    // --- 7. Samsung, enabled state 4: pm enable runs, holds ---

    @Test
    fun `samsung-enabled-state-4-pm-enable-runs-and-holds`() =
        scenario {
            world.samsung()
            setExempt(true)
            setDeviceHardening(true)
            var enabledState = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            val calls = mutableListOf<String>()
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                calls += cmd
                when {
                    cmd.startsWith("pm enable") -> {
                        enabledState = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        "ok"
                    }
                    cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            VendorBatteryRestrictions.applicationEnabledSettingForTesting = { enabledState }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertTrue("pm enable ran", calls.any { it.startsWith("pm enable") })
                assertEquals(
                    "the undo is recorded once",
                    1,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_SAMSUNG_DEEP_SLEEP),
                )
                assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
            }
        }

    // --- 7b. Samsung, fix does not hold: notification carries the "Never sleeping apps" screen ---

    @Test
    fun `samsung-enabled-state-4-fix-does-not-hold-notification-carries-never-sleeping-screen`() =
        scenario {
            world.samsung()
            setExempt(true)
            setDeviceHardening(true)
            val enabledState = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.startsWith("pm enable") -> "ok" // re-read still shows 4
                    cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            VendorBatteryRestrictions.applicationEnabledSettingForTesting = { enabledState }
            VendorBatteryRestrictions.resolveActivityForTesting = { _, intent -> intent.`package` == "com.samsung.android.lool" }
            appStarts(upgraded = false)
            serverStarted()
            check {
                val saved = contentIntentOf(VendorBatteryRestrictions.NOTIFICATION_ID)
                assertEquals(
                    "Samsung checkable-list action",
                    "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY",
                    saved.action,
                )
                assertEquals("Samsung lool package", "com.samsung.android.lool", saved.`package`)
                assertEquals("Never sleeping apps list", 2, saved.getIntExtra("activity_type", -1))
            }
        }

    // --- 8. Xiaomi, op 10008 not allowed: fix runs; MILLET missing is reported, never written ---

    @Test
    fun `xiaomi-op-10008-not-allowed-fix-runs-millet-missing-reported-never-written`() =
        scenario {
            ShadowBuild.setManufacturer("xiaomi")
            setExempt(true)
            setDeviceHardening(true)
            var autostart = "ignore"
            val calls = mutableListOf<String>()
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                calls += cmd
                when {
                    cmd.contains("10008") && cmd.contains("appops set") -> {
                        autostart = "allow"
                        "ok"
                    }
                    cmd.contains("10008") && cmd.contains("appops get") -> "10008: $autostart"
                    cmd.contains("MILLET_NO_RESTRICT_APP") -> "com.some.other,com.another"
                    cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            VendorBatteryRestrictions.resolveActivityForTesting = { _, intent -> intent.component?.packageName == "com.miui.powerkeeper" }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertTrue("autostart fix ran", calls.any { it.contains("10008") && it.contains("appops set") })
                assertEquals(
                    "autostart undone once",
                    1,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_XIAOMI_AUTOSTART),
                )
                assertEquals(
                    "millet reported once",
                    1,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_XIAOMI_NO_RESTRICT),
                )
                assertTrue(
                    "MILLET is only read, never written",
                    calls.filter { it.contains("MILLET_NO_RESTRICT_APP") }.all { it.contains("settings get") },
                )
                val saved = contentIntentOf(VendorBatteryRestrictions.NOTIFICATION_ID)
                assertEquals(
                    "Xiaomi battery saver screen",
                    ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
                    saved.component,
                )
                assertEquals("package_name extra", app.packageName, saved.getStringExtra("package_name"))
            }
        }

    // --- 9. a vendor screen that does not resolve: falls back to the app's settings page ---

    @Test
    fun `vendor-screen-that-does-not-resolve-falls-back-to-app-details`() =
        scenario {
            world.samsung()
            setExempt(true)
            setDeviceHardening(false)
            val enabledState = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            VendorBatteryRestrictions.applicationEnabledSettingForTesting = { enabledState }
            VendorBatteryRestrictions.resolveActivityForTesting = { _, _ -> false }
            appStarts(upgraded = false)
            serverStarted()
            check {
                val saved = contentIntentOf(VendorBatteryRestrictions.NOTIFICATION_ID)
                assertEquals("app-details action", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, saved.action)
                assertEquals("package data", Uri.parse("package:" + app.packageName), saved.data)
            }
        }

    // --- 10. the same restriction unchanged at the next start: no second notification ---

    @Test
    fun `same-restriction-unchanged-next-start-posts-no-second-notification`() =
        scenario {
            setExempt(true)
            setDeviceHardening(false)
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.contains("appops get") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "RUN_ANY_IN_BACKGROUND: ignore"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertNotNull(
                    "first start posts",
                    getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID),
                )
            }

            notificationManager().cancel(VendorBatteryRestrictions.NOTIFICATION_ID)
            serverKilled()
            serverStarted()
            check {
                assertNull(
                    "no second notification for an unchanged restriction",
                    getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID),
                )
                assertEquals(
                    "an unchanged restriction is logged once, not at every start",
                    1,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY),
                )
            }
        }

    // --- 11. Xiaomi without the battery-saver setting at all: nothing to report ---

    @Test
    fun `xiaomi-without-the-millet-setting-reports-nothing`() =
        scenario {
            ShadowBuild.setManufacturer("xiaomi")
            setExempt(true)
            setDeviceHardening(true)
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.contains("10008") && cmd.contains("appops get") -> "10008: allow"
                    // `settings get` prints "null" for a setting that does not exist (older MIUI).
                    cmd.contains("MILLET_NO_RESTRICT_APP") -> "null\n"
                    cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertEquals(
                    "a missing setting is not a missing exemption",
                    0,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_XIAOMI_NO_RESTRICT),
                )
                assertNull("nothing posted", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
            }
        }

    // --- 12. Xiaomi autostart with no recorded mode ("No operations."): not a restriction ---

    @Test
    fun `xiaomi-autostart-with-no-recorded-mode-is-not-reported`() =
        scenario {
            ShadowBuild.setManufacturer("xiaomi")
            setExempt(true)
            setDeviceHardening(false)
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.contains("10008") && cmd.contains("appops get") -> "No operations.\n"
                    cmd.contains("MILLET_NO_RESTRICT_APP") -> "null"
                    cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertEquals(
                    "only an explicit ignore or deny is a restriction",
                    0,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_XIAOMI_AUTOSTART),
                )
                assertNull("nothing posted", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
            }
        }

    // --- 13. notifications blocked: the restriction is logged once, not at every start ---

    @Test
    fun `notifications-blocked-logs-an-unchanged-restriction-once`() =
        scenario {
            setExempt(true)
            setDeviceHardening(false)
            shadowOf(notificationManager()).setNotificationsEnabled(false)
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                when {
                    cmd.contains("appops get") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "RUN_ANY_IN_BACKGROUND: ignore"
                    cmd.contains("get-standby-bucket") -> "5"
                    else -> null
                }
            }
            appStarts(upgraded = false)
            serverStarted()
            serverKilled()
            serverStarted()
            check {
                assertEquals(
                    "logged once across two starts",
                    1,
                    logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY),
                )
                assertNull("nothing posted while blocked", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
            }
            shadowOf(notificationManager()).setNotificationsEnabled(true)
            serverKilled()
            serverStarted()
            check {
                assertNotNull(
                    "posted once notifications are allowed again",
                    getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID),
                )
            }
        }

    // --- 14. every read and fix names the app's own Android user, not the shell's default ---

    @Test
    fun `every-command-names-the-apps-own-user`() =
        scenario {
            world.samsung()
            setExempt(true)
            setDeviceHardening(true)
            val calls = mutableListOf<String>()
            DeviceOptimizer.runCommandOutputForTesting = { cmd ->
                calls += cmd
                when {
                    cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: ignore"
                    cmd.contains("get-standby-bucket") -> "45"
                    else -> "ok"
                }
            }
            VendorBatteryRestrictions.applicationEnabledSettingForTesting = {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            }
            appStarts(upgraded = false)
            serverStarted()
            check {
                val userCommands = calls.filter { it.startsWith("cmd appops") || it.startsWith("am ") || it.startsWith("pm ") }
                assertTrue("the pass ran appops, am and pm commands", userCommands.size >= 5)
                assertTrue(
                    "each names --user 0: $userCommands",
                    userCommands.all { it.contains("--user 0 ") },
                )
            }
        }
}
