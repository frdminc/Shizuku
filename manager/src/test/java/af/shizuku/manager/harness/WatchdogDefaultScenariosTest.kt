package af.shizuku.manager.harness

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.SettingsBackupManager
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ShizukuStateMachine.State
import af.shizuku.manager.worker.AdbStartWorker
import android.app.Application
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The Watchdog's default. A new install gets the Watchdog on, and it turns on at the first time a
 * server runs, not at the first launch: before then there is nothing to watch, and the watchdog
 * must not start a server, raise adbd's dialog or post its notification on its own. An install
 * updated from a build whose default was off keeps off when the user never touched the switch,
 * and a value the user set, or one a backup restored, is kept either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchdogDefaultScenariosTest {
    private lateinit var world: FakeWorld
    private var watchdog: ServiceController<WatchdogService>? = null
    private val app: Application get() = world.app
    private val grace = WatchdogService.RESTART_GRACE_MS

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        // Plain saved-port mode is the path FakeAdbd models (see OnePromptScenariosTest). A new
        // install has no launch mode yet: the first start records it (serverStarted()).
        world.prefs
            .edit()
            .putBoolean("tcp_mode", false)
            .remove("mode")
            .commit()
        drainWatchdogStarts()
    }

    @After
    fun tearDown() {
        watchdog?.destroy()
        world.close()
    }

    private fun scenario(block: Scenario.() -> Unit) = world.scenario(block)

    /** The app starts for the first time after this install or update (ShizukuApplication.onCreate). */
    private fun appStarts(upgraded: Boolean) {
        ShizukuSettings.decideWatchdogDefault(upgraded)
        world.settle()
    }

    /** The user starts a server over ADB; Shizuku's binder-received listener runs. */
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

    private fun elapse(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        world.settle()
    }

    /** WatchdogService start requests made so far (the system would create the service for each). */
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

    private val storedWatchdog: Boolean? get() =
        if (world.prefs.contains(ShizukuSettings.Keys.KEY_WATCHDOG)) world.prefs.getBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, false) else null

    // (a) and (d): a new install.

    @Test
    fun `new-install-turns-the-watchdog-on-at-the-first-server-start`() =
        scenario {
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertTrue("watchdog setting after the first server start", ShizukuSettings.getWatchdog())
                assertEquals("WatchdogService starts", 1, drainWatchdogStarts())
            }
        }

    @Test
    fun `new-install-with-no-server-yet-starts-nothing`() =
        scenario {
            appStarts(upgraded = false)
            // update() at a cold start, a binder death notice for a server that never ran, and a
            // long wait: nothing of the watchdog's may run before a server has.
            assertEquals(State.STOPPED, ShizukuStateMachine.update())
            ShizukuStateMachine.setDead()
            elapse(grace * 4)
            check {
                assertFalse("watchdog setting before any server ran", ShizukuSettings.getWatchdog())
                assertEquals("WatchdogService starts", 0, drainWatchdogStarts())
                assertEquals("starts queued", 0, startsQueued())
                expectOffers(0)
                assertFalse("server up", serverRunning)
            }
        }

    // (e): a new install whose server, once started, crashes.

    @Test
    fun `new-install-server-started-then-crashed-is-restarted-once-after-the-grace`() =
        scenario {
            appStarts(upgraded = false)
            serverStarted()
            assertEquals("WatchdogService starts", 1, drainWatchdogStarts())
            // The system creates the service the start asked for.
            watchdog = Robolectric.buildService(WatchdogService::class.java).create()
            world.settle()
            serverKilled()
            elapse(grace - 1_000)
            check { assertEquals("starts queued inside the grace", 0, startsQueued()) }
            elapse(2_000)
            check { assertEquals("starts the watchdog queued", 1, startsQueued()) }
            keyOffered()
            dialogAccepted()
            check {
                expectOffers(1)
                assertTrue("server up again", serverRunning)
            }
        }

    @Test
    fun `new-install-switched-off-before-the-first-start-stays-off`() =
        scenario {
            appStarts(upgraded = false)
            ShizukuSettings.setWatchdog(app, false)
            drainWatchdogStarts()
            serverStarted()
            check {
                assertFalse("watchdog setting", ShizukuSettings.getWatchdog())
                assertEquals("WatchdogService starts", 0, drainWatchdogStarts())
            }
        }

    @Test
    fun `new-install-turns-on-once-only`() =
        scenario {
            appStarts(upgraded = false)
            serverStarted()
            drainWatchdogStarts()
            // Turned off by the user later; the next server start and app start leave it off.
            ShizukuSettings.setWatchdog(app, false)
            serverKilled()
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertFalse("watchdog setting", ShizukuSettings.getWatchdog())
                assertEquals("WatchdogService starts", 0, drainWatchdogStarts())
            }
        }

    // (b) and (c): an install updated from a build whose default was off.

    @Test
    fun `upgrade-with-no-stored-setting-stays-off`() =
        scenario {
            appStarts(upgraded = true)
            serverStarted()
            check {
                assertEquals("stored watchdog setting", false, storedWatchdog)
                assertFalse("watchdog setting", ShizukuSettings.getWatchdog())
                assertEquals("WatchdogService starts", 0, drainWatchdogStarts())
            }
        }

    @Test
    fun `upgrade-with-stored-on-stays-on`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
                .commit()
            appStarts(upgraded = true)
            serverStarted()
            check {
                assertEquals("stored watchdog setting", true, storedWatchdog)
                assertTrue("watchdog setting", ShizukuSettings.getWatchdog())
            }
        }

    @Test
    fun `upgrade-with-stored-off-stays-off`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, false)
                .commit()
            appStarts(upgraded = true)
            serverStarted()
            check {
                assertEquals("stored watchdog setting", false, storedWatchdog)
                assertEquals("WatchdogService starts", 0, drainWatchdogStarts())
            }
        }

    /**
     * Not an update by the package's install times, but the settings hold what an earlier build
     * wrote: data kept across an uninstall, or restored from a backup before the first launch.
     */
    @Test
    fun `earlier-builds-settings-with-no-stored-setting-stay-off`() =
        scenario {
            world.prefs
                .edit()
                .putInt("mode", ShizukuSettings.LaunchMethod.ADB)
                .commit()
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertEquals("stored watchdog setting", false, storedWatchdog)
                assertEquals("WatchdogService starts", 0, drainWatchdogStarts())
            }
        }

    @Test
    fun `restored-setting-on-a-new-install-is-kept`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, false)
                .commit()
            appStarts(upgraded = false)
            serverStarted()
            check {
                assertEquals("stored watchdog setting", false, storedWatchdog)
                assertEquals("WatchdogService starts", 0, drainWatchdogStarts())
            }
        }

    /** A backup made by a build whose default was off, imported before the first server start. */
    @Test
    fun `backup-without-the-setting-imported-on-a-new-install-keeps-it-off`() =
        scenario {
            appStarts(upgraded = false)
            assertTrue(SettingsBackupManager.importAndCommit(app, """{"_version":1,"mode":1}"""))
            serverStarted()
            check {
                assertEquals("stored watchdog setting", false, storedWatchdog)
                assertEquals("WatchdogService starts", 0, drainWatchdogStarts())
            }
        }

    @Test
    fun `backup-with-the-setting-on-imported-on-a-new-install-keeps-it-on`() =
        scenario {
            appStarts(upgraded = false)
            assertTrue(SettingsBackupManager.importAndCommit(app, """{"_version":1,"watchdog":true}"""))
            serverStarted()
            check { assertEquals("stored watchdog setting", true, storedWatchdog) }
        }

    /**
     * The settings switch shows the service's state; persisted, it wrote that state back as the
     * setting, so opening Settings before the first server start stored an off that cancelled the
     * new-install default. It must not persist, and must have no default that disagrees with
     * getWatchdog()'s.
     */
    @Test
    fun `settings-switch-neither-persists-nor-has-a-default`() {
        val android = "http://schemas.android.com/apk/res/android"
        val parser = app.resources.getXml(af.shizuku.manager.R.xml.settings_behavior)
        var found = false
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            if (parser.getAttributeValue(android, "key") != ShizukuSettings.Keys.KEY_WATCHDOG) continue
            found = true
            assertFalse("android:persistent", parser.getAttributeBooleanValue(android, "persistent", true))
            assertEquals("android:defaultValue", null, parser.getAttributeValue(android, "defaultValue"))
        }
        assertTrue("watchdog switch in settings_behavior.xml", found)
    }

    // The install-time signal itself.

    @Test
    fun `package-install-times-tell-a-new-install-from-an-update`() {
        val info = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
        info.firstInstallTime = 1_000L
        info.lastUpdateTime = 1_000L
        assertFalse("same install and update time", ShizukuSettings.isUpgradedInstall(app))
        info.lastUpdateTime = 2_000L
        assertTrue("updated after the install", ShizukuSettings.isUpgradedInstall(app))
    }
}
