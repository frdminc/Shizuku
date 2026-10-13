package af.shizuku.manager.harness

import af.shizuku.manager.R
import af.shizuku.manager.adb.WirelessDebugging
import af.shizuku.manager.utils.HeadlessLogger
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * TCP mode after something closed adbd's TCP port (a reboot, `adb usb`): the app opens it again
 * itself through wireless debugging, in the attempt that found it closed. Wireless debugging needs
 * Wi-Fi, and a network trusted for it; FakeWorld runs both as the system does. The manager's key
 * is already authorised (paired, or "Always allow"), so no scenario here raises adbd's dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TcpRestoreScenariosTest {
    private lateinit var world: FakeWorld
    private val tcpPort get() = world.closedTcpPort

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        // TCP mode (the default) on a closed port; the last port used is that port, as on a device.
        world.prefs
            .edit()
            .putBoolean("tcp_mode", true)
            .putString("tcp_port", tcpPort.toString())
            .putInt("last_adb_port", tcpPort)
            .commit()
        val path = checkNotNull(HeadlessLogger.getLogPath()) { "HeadlessLogger has no log file" }
        File(path).delete()
        File("$path.1").delete()
    }

    @After
    fun tearDown() = world.close()

    private fun scenario(block: Scenario.() -> Unit) = world.scenario(block)

    private fun Scenario.assertLogHas(vararg lines: String) {
        val text = log()
        lines.forEach { assertTrue("headless log has \"$it\":\n$text", it in text) }
    }

    private fun Scenario.assertLogLacks(vararg lines: String) {
        val text = log()
        lines.forEach { assertFalse("headless log has no \"$it\":\n$text", it in text) }
    }

    private fun Scenario.assertRestored() {
        assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
        assertTrue("server up", serverRunning)
        assertTrue("tcp port open again", tcpPortOpen)
        assertEquals("no adbd dialog", 0, allOffers)
        assertNull("no restore notice left", restoreNoticeTitle)
    }

    /** WorkManager ran start [n] (a start queued by a broadcast or another worker is asynchronous). */
    private fun Scenario.awaitStarts(n: Int) = world.waitUntil({ "start $n to run (ran $startsRun)" }) { startsRun >= n }

    @Test
    fun `tcp-port-closed-restores-in-the-same-attempt`() =
        scenario {
            boot()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("one run, no retry", 1, startsRun)
                assertEquals("wireless debugging back off, as the start found it", 0, adbWifiEnabled)
                assertLogHas(
                    "StartWorker: tcp fast path: port $tcpPort closed",
                    "restoring it through wireless debugging",
                    "StartWorker: turning wireless debugging on: adb_wifi_enabled 0 -> 1",
                    "StartWorker: mDNS found port ${world.wirelessAdbd.port}",
                    "AdbStarter: connected on ${world.wirelessAdbd.port}; switching adbd to tcp port $tcpPort (tcpip)",
                    "AdbStarter: starter ran on port $tcpPort",
                    "StartWorker: wireless debugging was off before this start; turned it off again",
                    "StartWorker: SUCCESS",
                )
                // The closed port is probed once, never dialled by the start's connect retries.
                assertLogLacks("connect 1/8 to port $tcpPort", "RETRY")
            }
        }

    // Something other than adbd listens on the TCP-mode port (adbd does not report it in
    // service.adb.tcp.port) and answers with the legacy token challenge. The fast path must not
    // dial it: no token is signed and no key offered, and the start goes on to the restore.
    @Test
    fun `tcp-port-held-by-a-listener-adbd-does-not-report-is-not-dialled`() =
        scenario {
            af.shizuku.manager.adb.AdbStarter.isLegacyAuthPort = { it == world.adbd.port || it == world.tcpAdbd?.port }
            val notUsed = "tcp port $tcpPort is open but adbd does not report it as its tcp port; not using it"
            FakeAdbd(port = tcpPort, authorized = true).use { other ->
                boot()
                world.waitUntil({ "the fast path to pass the listener by" }) { notUsed in log() }
                check {
                    assertEquals("no token signed", 0, other.signatures)
                    assertEquals("no key offered", 0, other.offers)
                }
            }
            // The listener held the port the restore needs; once it is gone the restore finishes.
            awaitStartWork()
        }

    // HEADLESS_STATUS reported "ADB: USB:on" right after this restore although adbd was listening
    // on the TCP port again: it named a Wi-Fi port only while wireless debugging was on, and the
    // restore turns wireless debugging back off. The port that is listening is what counts.
    @Test
    fun `tcp-port-restored-shows-in-headless-status`() =
        scenario {
            boot()
            awaitStartWork()
            val status = headlessStatus()
            check {
                assertRestored()
                assertEquals("wireless debugging back off, as the start found it", 0, adbWifiEnabled)
                assertTrue("status names the listening TCP port: ${status.data}", "ADB: USB:on WiFi:$tcpPort" in status.data.orEmpty())
                assertEquals("adb_tcp_listening_port extra", tcpPort, status.extras?.getInt("adb_tcp_listening_port"))
            }
        }

    @Test
    fun `tcp-port-closed-shows-no-port-in-headless-status`() =
        scenario {
            val status = headlessStatus()
            check {
                assertFalse("tcp port closed", tcpPortOpen)
                assertTrue("status names no Wi-Fi port: ${status.data}", "ADB: USB:on," in status.data.orEmpty())
                assertEquals("adb_tcp_listening_port extra", -1, status.extras?.getInt("adb_tcp_listening_port"))
            }
        }

    @Test
    fun `tcp-port-closed-waits-for-wifi-without-internet`() =
        scenario {
            wifiDrops()
            boot()
            startWaitsForWifi()
            // No VALIDATED and no NOT_METERED: a Wi-Fi with no internet, metered, is enough.
            wifiConnects()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("one run, no retry", 1, startsRun)
                assertLogHas(
                    "StartWorker: no Wi-Fi; waiting up to",
                    "StartWorker: Wi-Fi connected",
                    "StartWorker: SUCCESS",
                )
            }
        }

    @Test
    fun `tcp-port-closed-stale-wireless-debugging-toggled-once`() =
        scenario {
            world.staleWirelessDebuggingOn()
            boot()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("one run, no retry", 1, startsRun)
                assertEquals("left on, as the start found it", 1, adbWifiEnabled)
                assertLogHas(
                    "StartWorker: wireless debugging is already on; discovering its port",
                    "StartWorker: mDNS found nothing in",
                    "turning it off and on once",
                    "StartWorker: mDNS found port ${world.wirelessAdbd.port}",
                )
            }
        }

    @Test
    fun `untrusted-network-asks-once-and-stops-retrying`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals("the system asked once; no rewrite loop", 1, trustPrompts)
                assertEquals(0, adbWifiEnabled)
                assertEquals(string(R.string.wadb_restore_untrusted_title), restoreNoticeTitle)
                assertEquals(string(R.string.wadb_restore_untrusted_text), restoreNoticeText)
                assertLogHas(
                    "StartWorker: adb_wifi_enabled went back to 0 with Wi-Fi connected: this network is not trusted for wireless debugging",
                    "no second prompt this boot",
                    "StartWorker: FAILURE",
                )
                assertLogLacks("RETRY")
            }

            // The watchdog or the boot retry: stands down without prompting again.
            backgroundStart()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(2, startsRun)
                assertEquals("no second prompt", 1, trustPrompts)
                assertLogHas("this Wi-Fi network is still not trusted for wireless debugging")
            }

            // The user starts by hand: that tries again, prompting once more.
            advanceTime(1_000)
            tileTap()
            awaitStartWork()
            check {
                assertEquals(3, startsRun)
                assertEquals(2, trustPrompts)
            }
        }

    @Test
    fun `untrusted-network-allowed-while-waiting`() =
        scenario {
            wifiConnects(trusted = false)
            boot()
            startAsksToAllowNetwork()
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, startsRun)
                assertEquals(1, trustPrompts)
                assertLogHas("StartWorker: wireless debugging is on again: the network was allowed")
            }
        }

    // A successful restore after "Allow" must not keep the "turned on by restore" marker: a later
    // start would then turn off wireless debugging that the user switched on.
    @Test
    fun `allowed-restore-then-user-turns-wireless-debugging-on-stays-on`() =
        scenario {
            wifiConnects(trusted = false)
            boot()
            startAsksToAllowNetwork()
            userAllowsThisNetwork()
            awaitStartWork()
            check { assertRestored() }
            android.provider.Settings.Global
                .putInt(world.app.contentResolver, "adb_wifi_enabled", 1)
            backgroundStart()
            awaitStartWork()
            check { assertEquals("the user's wireless debugging is left on", 1, adbWifiEnabled) }
        }

    // Without location permission no network has a stable identity (a reconnect gets a new handle),
    // so a refused network allows one automatic prompt per boot; after that only a start by hand,
    // the user turning wireless debugging on, or the next boot tries again.
    @Test
    fun `untrusted-network-then-another-network-needs-a-start-by-hand`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            boot()
            awaitStartWork()
            assertEquals(WorkInfo.State.FAILED, lastWork)

            wifiConnects(trusted = true)
            awaitStartWork()
            assertEquals("no automatic restart", 1, startsRun)
            backgroundStart()
            awaitStartWork()
            assertEquals(WorkInfo.State.FAILED, lastWork)

            advanceTime(1_000)
            tileTap()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(3, startsRun)
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `untrusted-network-reconnects-prompt-once-per-boot`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            boot()
            awaitStartWork()
            assertEquals(WorkInfo.State.FAILED, lastWork)

            // Out of range and back, or Wi-Fi toggled: the same refused network, a new handle.
            wifiConnects(trusted = false)
            awaitStartWork()
            wifiConnects(trusted = false)
            awaitStartWork()
            check {
                assertEquals("no automatic restart", 1, startsRun)
                assertEquals("one prompt this boot", 1, trustPrompts)
            }

            // The next boot may ask once more.
            reboot()
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(2, startsRun)
                assertEquals(2, trustPrompts)
            }
        }

    @Test
    fun `locked-untrusted-network-prompts-once-across-reruns`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            check {
                assertEquals("no retry", WorkInfo.State.FAILED, lastWork)
                assertEquals(1, trustPrompts)
                assertLogLacks("RETRY")
            }
            // WorkManager re-running the request, the boot retry and the watchdog: none prompts.
            workerRerun()
            backgroundStart()
            awaitStartWork()
            check {
                assertEquals(1, trustPrompts)
                assertEquals(0, adbWifiEnabled)
            }
        }

    @Test
    fun `locked-untrusted-network-unlock-then-allow-prompts-once`() =
        scenario {
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitLog("went off while locked")
            // The system's prompt from the first write shows now; nothing writes 1 again.
            unlockScreen()
            startAsksToAllowNetwork()
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `headless-start-tcp-mode-untrusted-network-prompts-once`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            headlessStart()
            awaitStartWork()
            assertEquals(1, trustPrompts)
            // An unattended repair loop sends it again: told why, nothing prompts.
            val again = headlessStart()
            awaitStartWork()
            check {
                // Queued, not refused in the receiver (no main-thread port probe there); the worker
                // probes the port, stands down for the untrusted network, and nothing prompts.
                assertEquals(0, again.code)
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `headless-start-tcp-mode-turns-wireless-debugging-off-again`() =
        scenario {
            headlessStart()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("off again, as the start found it", 0, adbWifiEnabled)
            }
        }

    // A write before WifiManager knows the new network's BSSID is undone without a prompt, and
    // would look like a refusal: the first write waits until Wi-Fi has settled.
    @Test
    fun `wifi-just-connected-waits-for-the-bssid-before-turning-on`() =
        scenario {
            WirelessDebugging.wifiSettleMs = 600
            world.bssidLagMs = 300
            wifiDrops()
            boot()
            startWaitsForWifi()
            wifiConnects()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(0, trustPrompts)
            }
        }

    @Test
    fun `untrusted-new-wifi-prompts-once`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiDrops()
            boot()
            startWaitsForWifi()
            wifiConnects(trusted = false)
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `locked-untrusted-new-wifi-prompts-once`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiDrops()
            lockScreen()
            boot()
            startWaitsForWifi()
            wifiConnects(trusted = false)
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `untrusted-network-wifi-reconnects-during-the-wait-prompts-once`() =
        scenario {
            WirelessDebugging.userWaitMs = 3_000
            wifiConnects(trusted = false)
            boot()
            startAsksToAllowNetwork()
            // A new connection (new handle) to the same refused network.
            wifiConnects(trusted = false)
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(1, startsRun)
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `untrusted-network-wifi-gone-during-the-wait-prompts-once`() =
        scenario {
            WirelessDebugging.userWaitMs = 3_000
            WirelessDebugging.wifiWaitMs = 1_000
            wifiConnects(trusted = false)
            boot()
            startAsksToAllowNetwork()
            wifiDrops()
            awaitStartWork()
            assertEquals(WorkInfo.State.FAILED, lastWork)
            // Back on the same refused network: nothing resumes, nothing prompts.
            wifiConnects(trusted = false)
            awaitStartWork()
            check {
                assertEquals(1, startsRun)
                assertEquals(1, trustPrompts)
                assertEquals(string(R.string.wadb_restore_untrusted_title), restoreNoticeTitle)
            }
        }

    @Test
    fun `untrusted-network-rerun-during-the-wait-stands-down`() =
        scenario {
            WirelessDebugging.userWaitMs = 3_000
            wifiConnects(trusted = false)
            boot()
            startAsksToAllowNetwork()
            // The system stopped the run (or the process died) and WorkManager runs it again.
            assertEquals(ListenableWorker.Result.failure(), workerRerun())
            assertEquals(1, trustPrompts)
            awaitStartWork()
            assertEquals(1, trustPrompts)
        }

    @Test
    fun `explicit-request-rerun-older-than-the-prompt-stands-down`() =
        scenario {
            WirelessDebugging.userWaitMs = 3_000
            wifiConnects(trusted = false)
            tileTap()
            startAsksToAllowNetwork()
            // WorkManager re-running the same request made by hand: it asked already.
            assertEquals(ListenableWorker.Result.failure(), workerRerun())
            awaitStartWork()
            assertEquals(1, trustPrompts)
            // A new start by hand may ask once more.
            advanceTime(1_000)
            tileTap()
            awaitStartWork()
            check {
                assertEquals(2, trustPrompts)
            }
        }

    @Test
    fun `untrusted-network-prompt-outlives-a-start-that-needed-no-wireless-debugging`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            boot()
            awaitStartWork()
            assertEquals(1, trustPrompts)
            // Something else opens the TCP port (adb tcpip 5555 from a computer); a start uses it.
            world.openTcpPort(tcpPort)
            backgroundStart()
            awaitStartWork()
            assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
            // The port is lost again the same boot: the watchdog does not prompt again.
            world.closeTcpPort()
            backgroundStart()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `headless-start-wireless-mode-untrusted-network-prompts-once`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            // Wireless debugging is the transport; no Wi-Fi constraint, no saved port to dial.
            world.prefs
                .edit()
                .putBoolean("tcp_mode", false)
                .putBoolean("force_start_wadb", true)
                .putInt("last_adb_port", 0)
                .commit()
            wifiConnects(trusted = false)
            headlessStart()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `tcp-port-open-turns-off-wireless-debugging-a-dead-run-left-on`() =
        scenario {
            world.prefs
                .edit()
                .putBoolean("wadb_restore_turned_on", true)
                .commit()
            world.wirelessDebuggingOn()
            world.openTcpPort(tcpPort)
            boot()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(0, adbWifiEnabled)
            }
        }

    // A write the system accepted is no prompt, however the run ends.
    @Test
    fun `accepted-write-then-mdns-timeout-is-no-prompt`() =
        scenario {
            world.silentDiscoveries.set(1)
            boot()
            world.awaitRetry()
            world.cancelStartWork()
            assertEquals("wireless debugging put back off", 0, adbWifiEnabled)
            // WorkManager's retry of the same request.
            assertEquals(ListenableWorker.Result.success(), workerRerun())
            check {
                assertTrue("tcp port open again", tcpPortOpen)
                assertEquals(0, trustPrompts)
                assertLogLacks("counting that as this boot's network prompt")
            }
            // Nothing recorded: a later start still restores, and HEADLESS_START is not refused.
            world.closeTcpPort()
            assertEquals(0, headlessStart().code)
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(0, trustPrompts)
            }
        }

    @Test
    fun `wifi-switches-to-an-untrusted-network-during-the-settle-prompts-once`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            WirelessDebugging.wifiSettleMs = 800
            wifiDrops()
            boot()
            startWaitsForWifi()
            wifiConnects()
            awaitLog("settling")
            wifiConnects(trusted = false)
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `wifi-switches-during-the-settle-waits-for-the-new-bssid`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            WirelessDebugging.wifiSettleMs = 800
            world.bssidLagMs = 300
            wifiDrops()
            boot()
            startWaitsForWifi()
            wifiConnects()
            awaitLog("settling")
            wifiConnects()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(0, trustPrompts)
            }
        }

    @Test
    fun `wifi-switches-to-an-untrusted-network-at-the-write-prompts-once`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.switchOnNextWrite = false
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `late-allow-after-the-wait-is-still-turned-off-again`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            boot()
            awaitStartWork()
            assertEquals(WorkInfo.State.FAILED, lastWork)
            // The user answers the prompt after the wait: the system turns wireless debugging on.
            userAllowsThisNetwork()
            backgroundStart()
            awaitStartWork()
            check {
                assertEquals("off again: this restore turned it on", 0, adbWifiEnabled)
                assertRestored()
            }
        }

    @Test
    fun `wireless-debugging-left-on-by-a-dead-run-is-turned-off`() =
        scenario {
            // A run turned wireless debugging on and died before turning it off again.
            world.prefs
                .edit()
                .putBoolean("wadb_restore_turned_on", true)
                .commit()
            world.wirelessDebuggingOn()
            boot()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(0, adbWifiEnabled)
            }
        }

    @Test
    fun `no-wifi-notifies-and-resumes-when-wifi-connects`() =
        scenario {
            WirelessDebugging.wifiWaitMs = 1_500
            wifiDrops()
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(string(R.string.wadb_restore_no_wifi_title), restoreNoticeTitle)
                assertEquals(string(R.string.wadb_restore_no_wifi_text), restoreNoticeText)
                assertLogHas("StartWorker: no Wi-Fi after", "StartWorker: FAILURE")
                assertLogLacks("RETRY")
            }

            // A start nobody asked for while there is still no Wi-Fi does not wait again.
            backgroundStart()
            awaitStartWork()
            check {
                assertEquals(2, startsRun)
                assertLogHas("still no Wi-Fi; waiting for Wi-Fi to connect")
                assertEquals("one wait only", 1, Regex("no Wi-Fi; waiting up to").findAll(log()).count())
            }

            wifiConnects()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(3, startsRun)
                assertLogHas("WirelessDebugging: Wi-Fi connected; resuming the ADB restore")
            }
        }

    @Test
    fun `tcp-port-open-uses-it-directly`() =
        scenario {
            world.openTcpPort(tcpPort)
            boot()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("wireless debugging untouched", 0, adbWifiEnabled)
                assertLogHas("StartWorker: tcp fast path: port $tcpPort open", "StartWorker: SUCCESS via tcp fast path")
                assertLogLacks("turning wireless debugging on", "mDNS")
            }
        }

    // s24, 2026-10-06 03:01: locked, on a mesh node whose BSSID was never allowed. One UI refused
    // the write with no prompt (no WifiDebuggingActivity), so nobody could answer it, yet it was
    // counted as this boot's prompt and nothing tried again for three hours.
    @Test
    fun `samsung-locked-untrusted-refusal-is-silent-and-the-unlock-asks-once`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals("One UI showed nothing", 0, trustPrompts)
                assertEquals(1, silentRefusals)
                assertLogHas("this phone shows no prompt while locked; not this boot's network prompt")
                assertTrue("watching for wireless debugging to come on", watchingWirelessDebugging)
                assertTrue("a quiet retry is queued", quietRetryQueued)
            }

            // The unlock: one more write, which the system shows now. It is this boot's prompt.
            // Real seconds, read at each wait: the user needs longer than the first wait had.
            WirelessDebugging.userWaitMs = 10_000
            unlockScreen()
            startAsksToAllowNetwork()
            assertFalse("no quiet retry once unlocked", quietRetryQueued)
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(2, startsRun)
                assertEquals("one visible prompt", 1, trustPrompts)
                assertEquals(1, silentRefusals)
                assertFalse(watchingWirelessDebugging)
                assertFalse(quietRetryQueued)
            }
        }

    @Test
    fun `samsung-locked-untrusted-unlock-during-the-wait-asks-once`() =
        scenario {
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitLog("this phone shows no prompt while locked")
            unlockScreen()
            startAsksToAllowNetwork()
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("one run", 1, startsRun)
                assertEquals(1, trustPrompts)
                assertEquals(1, silentRefusals)
            }
        }

    // After the visible prompt the one-prompt rule holds again: no write on later unlocks, no
    // quiet retries.
    @Test
    fun `samsung-unlock-prompt-unanswered-is-this-boots-one`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            unlockScreen()
            awaitStarts(2)
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(1, trustPrompts)
                assertFalse(quietRetryQueued)
            }
            val writes = turnOns
            lockScreen()
            backgroundStart()
            awaitStartWork()
            unlockScreen()
            awaitStartWork()
            check {
                assertEquals("no write after this boot's prompt", writes, turnOns)
                assertEquals(1, trustPrompts)
                assertEquals(1, silentRefusals)
                assertFalse(quietRetryQueued)
            }
        }

    // A mesh: the phone roams back to an access point that is allowed while still locked.
    @Test
    fun `samsung-locked-quiet-retry-heals-on-an-allowed-access-point`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            // Still on the same access point: refused silently again, and queued again.
            quietRetryDue()
            awaitStarts(2)
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(0, trustPrompts)
                assertEquals(2, silentRefusals)
                assertTrue(quietRetryQueued)
            }
            roamsTo(trusted = true)
            quietRetryDue()
            awaitStarts(3)
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(0, trustPrompts)
                assertEquals("off again, as the start found it", 0, adbWifiEnabled)
                assertFalse("no quiet retry after a success", quietRetryQueued)
                assertFalse(watchingWirelessDebugging)
            }
        }

    @Test
    fun `samsung-quiet-retry-stops-at-reboot`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            assertTrue(quietRetryQueued)
            // The last boot's quiet retry comes due before BOOT_COMPLETED: it writes nothing.
            reboot()
            val writes = turnOns
            quietRetryDue()
            check {
                assertEquals(writes, turnOns)
                assertFalse(quietRetryQueued)
            }
            roamsTo(trusted = true)
            boot()
            awaitStartWork()
            check {
                assertRestored()
                assertFalse(quietRetryQueued)
                assertFalse(watchingWirelessDebugging)
            }
        }

    // AOSP queues its dialog for the unlock, so the locked refusal stays this boot's prompt: the
    // unlock writes nothing, and the user's "Allow" turning wireless debugging on continues.
    @Test
    fun `locked-refusal-unlock-writes-nothing-and-a-later-allow-continues`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            assertEquals(1, trustPrompts)
            unlockScreen()
            awaitStartWork()
            check {
                assertEquals("no write on unlock", 1, turnOns)
                assertEquals(1, startsRun)
                assertFalse(quietRetryQueued)
            }
            userAllowsThisNetwork()
            awaitStarts(2)
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, trustPrompts)
            }
        }

    // s24, 2026-10-06 06:20: wireless debugging was turned on by hand after the stop (still locked,
    // on an allowed access point) and nothing noticed until a later HEADLESS_START.
    @Test
    fun `wireless-debugging-turned-on-after-a-stop-continues`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            assertEquals(WorkInfo.State.FAILED, lastWork)
            roamsTo(trusted = true)
            wirelessDebuggingTurnedOn()
            awaitStarts(2)
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(0, trustPrompts)
                assertFalse(quietRetryQueued)
                assertFalse(watchingWirelessDebugging)
                assertLogHas("wireless debugging was turned on; continuing the ADB restore")
            }
        }

    // The write is made unlocked (the system shows its prompt) and the screen locks before the
    // run sees the 0: still this boot's prompt, and the next unlock writes nothing.
    @Test
    fun `samsung-unlock-write-refused-after-locking-again-is-the-prompt`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            WirelessDebugging.userWaitMs = 3_000
            world.holdRefusals = true
            unlockScreen()
            world.awaitHeldRefusal()
            lockScreen()
            world.releaseRefusal()
            awaitLog("this boot's one network prompt")
            unlockScreen()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals("one visible prompt", 1, trustPrompts)
                assertEquals(1, silentRefusals)
                assertFalse(quietRetryQueued)
            }
        }

    // The inverse: written locked (refused in silence), unlocked before the run sees the 0. No
    // prompt was shown, so it is not this boot's: the run writes once more, now visibly.
    @Test
    fun `samsung-locked-write-seen-after-the-unlock-is-not-the-prompt`() =
        scenario {
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            world.holdRefusals = true
            boot()
            world.awaitHeldRefusal()
            unlockScreen()
            world.releaseRefusal()
            world.waitUntil({ "the system's prompt (raised $trustPrompts)" }) { trustPrompts == 1 }
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, trustPrompts)
                assertEquals(1, silentRefusals)
            }
        }

    // A run (the quiet retry's, say) wrote while locked on One UI and died before it saw the 0:
    // that write showed nothing, so it is no prompt.
    @Test
    fun `samsung-locked-write-of-a-dead-run-is-not-the-prompt`() =
        scenario {
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            world.prefs
                .edit()
                .putInt("wadb_restore_write_pending_boot", 1)
                .putBoolean("wadb_restore_write_pending_locked", true)
                .commit()
            backgroundStart()
            world.waitUntil({ "a silent refusal (saw $silentRefusals)" }) { silentRefusals == 1 }
            unlockScreen()
            world.waitUntil({ "the system's prompt (raised $trustPrompts)" }) { trustPrompts == 1 }
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, trustPrompts)
            }
        }

    @Test
    fun `samsung-quiet-retry-keeps-the-unlock-notice`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            assertEquals(string(R.string.wadb_restore_locked_title), restoreNoticeTitle)
            quietRetryDue()
            awaitStarts(2)
            awaitStartWork()
            check {
                assertEquals(2, silentRefusals)
                assertEquals("the unlock is still what helps", string(R.string.wadb_restore_locked_title), restoreNoticeTitle)
            }
        }

    // The unlock's start was turned away (a start still marked STARTING): the quiet retry stays
    // queued, and coming due unlocked it makes the unlock's one write.
    @Test
    fun `samsung-unlock-start-turned-away-keeps-the-quiet-retry`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            unlockScreen()
            awaitLog("start skipped: state=STARTING")
            assertTrue("the quiet retry is still queued", quietRetryQueued)
            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            WirelessDebugging.userWaitMs = 10_000
            quietRetryDue()
            world.waitUntil({ "the system's prompt (raised $trustPrompts)" }) { trustPrompts == 1 }
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, trustPrompts)
            }
        }

    // A quiet retry's request that runs unlocked (WorkManager's retry, or late) is an ordinary
    // start: no Wi-Fi is a no-Wi-Fi stop, resumed when Wi-Fi connects.
    @Test
    fun `quiet-request-run-unlocked-is-an-ordinary-start`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            WirelessDebugging.wifiWaitMs = 1_000
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            quietRetryDue()
            awaitStarts(2)
            awaitStartWork()
            val quietRequest = world.startInputs.last()
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            unlockScreen()
            awaitLog("start skipped: state=STARTING")
            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            wifiDrops()
            world.workerRerun(quietRequest)
            check {
                assertEquals(string(R.string.wadb_restore_no_wifi_title), restoreNoticeTitle)
                assertLogHas("StartWorker: no Wi-Fi after")
            }
            WirelessDebugging.userWaitMs = 10_000
            wifiConnects(trusted = false)
            world.waitUntil({ "the system's prompt (raised $trustPrompts)" }) { trustPrompts == 1 }
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, trustPrompts)
                assertEquals(2, silentRefusals)
            }
        }

    // The silent refusal is evidenced on One UI 8.5 only: an older (or unknown) One UI takes the
    // stock path, where the locked refusal is this boot's prompt and the unlock writes nothing.
    @Test
    fun `samsung-older-one-ui-locked-refusal-is-the-prompt`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung(oneUi = 80000)
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertLogHas("this boot's one network prompt")
                assertFalse(quietRetryQueued)
            }
            unlockScreen()
            awaitStartWork()
            check {
                assertEquals("no write on unlock", 1, turnOns)
                assertEquals(1, startsRun)
            }
        }

    // Overnight the quiet retry runs three times an hour: it posts no start notification at all,
    // and the unlock notice stays.
    @Test
    fun `samsung-quiet-retry-posts-no-start-notification`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            world.holdRefusals = true
            quietRetryDue()
            world.awaitHeldRefusal()
            world.settle()
            check {
                assertNull("no start notification while the quiet retry runs", startNotification)
                assertEquals(string(R.string.wadb_restore_locked_title), restoreNoticeTitle)
            }
            world.releaseRefusal()
            awaitStarts(2)
            awaitStartWork()
            check {
                assertNull("none after it either", startNotification)
                assertEquals(2, silentRefusals)
                assertEquals(string(R.string.wadb_restore_locked_title), restoreNoticeTitle)
            }
        }

    // The unlock's write never reached the system's judgement (Wi-Fi dropped first): no dialog, so
    // the next locked refusal is still silent and the next unlock still asks once.
    @Test
    fun `samsung-unlock-write-lost-with-wifi-is-not-the-prompt`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            WirelessDebugging.wifiWaitMs = 1_000
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            world.wifiDropsOnNextWrite = true
            unlockScreen()
            awaitStarts(2)
            awaitStartWork()
            assertEquals("no dialog", 0, trustPrompts)
            // Wi-Fi comes back while locked: the resumed start is refused in silence, or stands down.
            lockScreen()
            wifiConnects(trusted = false)
            awaitStarts(3)
            awaitStartWork()
            WirelessDebugging.userWaitMs = 10_000
            unlockScreen()
            world.waitUntil({ "the system's prompt (raised $trustPrompts)" }) { trustPrompts == 1 }
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, trustPrompts)
            }
        }

    // A quiet write made locked meets a Wi-Fi reconnect (a new network handle): the system judged
    // it locked, so nobody was asked, and the unlock still asks once.
    @Test
    fun `samsung-quiet-write-meeting-a-wifi-change-is-not-the-prompt`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            world.samsung()
            wifiConnects(trusted = false)
            lockScreen()
            boot()
            awaitStartWork()
            world.switchOnNextWrite = false
            quietRetryDue()
            awaitStarts(2)
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(0, trustPrompts)
            }
            WirelessDebugging.userWaitMs = 10_000
            unlockScreen()
            world.waitUntil({ "the system's prompt (raised $trustPrompts)" }) { trustPrompts == 1 }
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, trustPrompts)
            }
        }
}
