package af.shizuku.manager.harness

import af.shizuku.manager.adb.WirelessDebugging
import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MdnsFallbackScenariosTest {
    private lateinit var world: FakeWorld
    private val notes = mutableListOf<String>()
    private var scans = 0
    private var stopped = false
    private var mdns: ((Int) -> Unit)? = null
    private var scan: ((WirelessDebugging.ScanResult) -> Unit)? = null
    private var immediateMdns = false
    private var immediateScan = false

    @Before fun setup() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        world.connectWifi(true)
        world.wirelessDebuggingOn()
        WirelessDebugging.discoveryMs = 30
        WirelessDebugging.scanBudgetMs = 100
        WirelessDebugging.discovery =
            object : WirelessDebugging.PortDiscovery {
                override fun start(
                    context: android.content.Context,
                    onPort: (Int) -> Unit,
                ): () -> Unit {
                    mdns = onPort
                    if (immediateMdns) onPort(40001)
                    return {}
                }

                override fun scan(
                    context: android.content.Context,
                    onResult: (WirelessDebugging.ScanResult) -> Unit,
                ): () -> Unit {
                    scans++
                    scan = onResult
                    if (immediateScan) onResult(WirelessDebugging.ScanResult(40002, null))
                    return { stopped = true }
                }
            }
    }

    @After fun cleanup() = world.close()

    private fun session() = WirelessDebugging.Session(world.app, { notes += it }, { notes += it }, { false })

    @Test fun `mdns works without scanning`() =
        runBlocking {
            immediateMdns = true
            assertEquals(40001, session().findPort())
            assertEquals(0, scans)
        }

    @Test fun `silent mdns falls back and logs scan`() =
        runBlocking {
            world.prefs
                .edit()
                .putBoolean("tcp_mode", false)
                .commit()
            af.shizuku.manager.ShizukuSettings
                .setLastPort(31999)
            val key =
                af.shizuku.manager.adb
                    .AdbKey(
                        af.shizuku.manager.adb
                            .PreferenceAdbKeyStore(world.prefs),
                        "test",
                    )
            val connected =
                af.shizuku.manager.adb
                    .AdbClient("127.0.0.1", world.wirelessAdbd.port, key)
            connected.connect()
            val session = session()
            val finding = async { session.findPort() }
            while (scan == null) delay(5)
            scan!!(WirelessDebugging.ScanResult(world.wirelessAdbd.port, connected))
            val port = finding.await()
            assertEquals(world.wirelessAdbd.port, port)
            af.shizuku.manager.adb.AdbStarter
                .startAdb(world.app, port, connectedClient = session.scannedClient)
            assertTrue(rikka.shizuku.Shizuku.pingBinder())
            assertEquals("retained connection, no second connect", 1, world.wirelessAdbd.connections)
            assertEquals(
                "a port found by the scan is not saved as the last port",
                31999,
                af.shizuku.manager.ShizukuSettings
                    .getLastPort(),
            )
            assertTrue(notes.any { "scan found port $port" in it })
            assertTrue(stopped)
            session.restore()
        }

    @Test fun `empty scan retains timeout`() =
        runBlocking {
            try {
                session().findPort()
                fail("expected timeout")
            } catch (_: TimeoutException) {
            }
            assertEquals(1, scans)
            assertTrue(stopped)
        }

    @Test fun `late mdns wins once and stops scan`() =
        runBlocking {
            val finding = async { session().findPort() }
            while (scan == null) delay(5)
            mdns!!(40001)
            scan!!(WirelessDebugging.ScanResult(40002, null))
            assertEquals(40001, finding.await())
            assertTrue(stopped)
        }

    @Test fun `cancel stops discovery scan`() =
        runBlocking {
            val finding = async { session().findPort() }
            while (scan == null) delay(5)
            finding.cancel()
            finding.join()
            assertTrue(stopped)
        }

    @Test fun `scan rejects legacy auth without offering key`() {
        val key =
            af.shizuku.manager.adb
                .AdbKey(
                    af.shizuku.manager.adb
                        .PreferenceAdbKeyStore(world.prefs),
                    "test",
                )
        af.shizuku.manager.adb.AdbClient("127.0.0.1", world.adbd.port, key, tlsOnly = true).use {
            try {
                it.connect()
                fail("legacy AUTH accepted")
            } catch (_: IllegalStateException) {
            }
        }
        assertEquals(0, world.adbd.offers)
    }

    @Test fun `kernel range parser validates or uses kernel default`() {
        assertEquals(
            35000..65000,
            af.shizuku.manager.adb.LoopbackAdbScan
                .parseRange("35000\t65000\n"),
        )
        listOf(null, "", "65536 70000", "50000 30000", "1 65535", "30000 garbage 50000").forEach {
            assertEquals(
                32768..60999,
                af.shizuku.manager.adb.LoopbackAdbScan
                    .parseRange(it),
            )
        }
    }
}
