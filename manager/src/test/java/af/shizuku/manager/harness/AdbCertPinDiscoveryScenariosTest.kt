package af.shizuku.manager.harness

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbClient
import af.shizuku.manager.adb.AdbKey
import af.shizuku.manager.adb.AdbServerCertChangedException
import af.shizuku.manager.adb.AdbServerPin
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.adb.PreferenceAdbKeyStore
import af.shizuku.manager.adb.WirelessDebugging
import android.app.Application
import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdbCertPinDiscoveryScenariosTest {
    private lateinit var world: FakeWorld

    @Before fun setup() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        ShizukuSettings.initialize(world.app)
        ShizukuSettings.setPreferencesForTesting(world.prefs)
        world.prefs
            .edit()
            .putBoolean("tcp_mode", false)
            .commit()
    }

    @After fun cleanup() = world.close()

    private fun pin(server: FakeAdbd) = runBlocking { AdbStarter.startAdb(world.app, server.port) }

    /**
     * Replaces discovery with one that says nothing over mDNS and returns [server] from the scan.
     * The returned scan client has already completed TLS with commitFirstUse=false.
     */
    private fun setScanDiscovery(server: FakeAdbd) {
        WirelessDebugging.discovery =
            object : WirelessDebugging.PortDiscovery {
                override fun start(
                    context: Context,
                    onPort: (Int) -> Unit,
                ): () -> Unit = {}

                override fun scan(
                    context: Context,
                    onResult: (WirelessDebugging.ScanResult) -> Unit,
                ): (() -> Unit)? {
                    val key = AdbKey(PreferenceAdbKeyStore(world.prefs), "shizuku+")
                    val client =
                        AdbClient(
                            "127.0.0.1",
                            server.port,
                            key,
                            connectTimeoutMs = 2000,
                            commitFirstUse = false,
                        )
                    return try {
                        client.connect()
                        onResult(WirelessDebugging.ScanResult(server.port, client))
                        ({ })
                    } catch (e: Exception) {
                        client.close()
                        ({ })
                    }
                }
            }
    }

    @Test fun `changed key on saved port falls back to scan with pinned key`() {
        // Pin the real adbd first, then stop the server so the background start is not skipped.
        pin(world.wirelessAdbd)
        world.serverDown()
        af.shizuku.manager.utils.ShizukuStateMachine
            .update()

        // A different listener on the saved port.
        FakeAdbd(tls = true).use { otherListener ->
            world.prefs
                .edit()
                .putInt("last_adb_port", otherListener.port)
                .commit()

            // Silence mDNS so the worker must fall back to the scan, which finds the real adbd.
            world.silentDiscoveries.set(1)
            setScanDiscovery(world.wirelessAdbd)

            world.scenario {
                backgroundStart()
                awaitStartWork()
                assertEquals(androidx.work.WorkInfo.State.SUCCEEDED, lastWork)
                assertEquals(
                    AdbServerPin.of(world.wirelessAdbd.certificates.first()),
                    AdbServerPin.store().connectPin,
                )
            }
        }
    }

    @Test fun `queued mDNS candidate with A survives a preceding candidate with X`() {
        pin(world.wirelessAdbd)
        world.serverDown()
        af.shizuku.manager.utils.ShizukuStateMachine
            .update()
        android.provider.Settings.Global
            .putInt(world.app.contentResolver, "adb_wifi_enabled", 1)
        android.provider.Settings.Global
            .putInt(world.app.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0)
        FakeAdbd(tls = true, fixedCertificate = true).use { otherListener ->
            WirelessDebugging.discovery =
                object : WirelessDebugging.PortDiscovery {
                    override fun start(
                        context: Context,
                        onPort: (Int) -> Unit,
                    ): () -> Unit {
                        onPort(otherListener.port)
                        onPort(world.wirelessAdbd.port)
                        return {}
                    }
                }
            runBlocking {
                val session = WirelessDebugging.Session(world.app, {}, {}, { false })
                try {
                    val port = session.findPort()
                    assertEquals(world.wirelessAdbd.port, port)
                    AdbStarter.startAdb(world.app, port)
                } finally {
                    session.closeConnection()
                }
            }
            assertEquals(AdbServerPin.of(world.wirelessAdbd.certificates.first()), AdbServerPin.store().connectPin)
            assertEquals(0, otherListener.clientCertificates.get())
        }
    }

    @Test fun `mDNS mismatch proceeds to scan without toggling the last transport`() {
        pin(world.wirelessAdbd)
        android.provider.Settings.Global
            .putInt(world.app.contentResolver, "adb_wifi_enabled", 1)
        android.provider.Settings.Global
            .putInt(world.app.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0)
        FakeAdbd(tls = true, fixedCertificate = true).use { otherListener ->
            setScanDiscovery(world.wirelessAdbd)
            val scan = WirelessDebugging.discovery
            WirelessDebugging.discovery =
                object : WirelessDebugging.PortDiscovery {
                    override fun start(
                        context: Context,
                        onPort: (Int) -> Unit,
                    ): () -> Unit {
                        repeat(10) { onPort(otherListener.port) }
                        return {}
                    }

                    override fun scan(
                        context: Context,
                        onResult: (WirelessDebugging.ScanResult) -> Unit,
                    ) = scan.scan(context, onResult)
                }
            WirelessDebugging.discoveryMs = 500
            runBlocking {
                val session = WirelessDebugging.Session(world.app, {}, {}, { false })
                try {
                    val port = session.findPort()
                    assertEquals(world.wirelessAdbd.port, port)
                    AdbStarter.startAdb(world.app, port, connectedClient = session.scannedClient)
                } finally {
                    session.closeConnection()
                }
            }
            assertEquals(AdbServerPin.of(world.wirelessAdbd.certificates.first()), AdbServerPin.store().connectPin)
            assertEquals(0, otherListener.clientCertificates.get())
            assertEquals(1, otherListener.connections)
        }
    }

    @Test fun `scan client is compared with pin recorded after its handshake`() {
        val key = AdbKey(PreferenceAdbKeyStore(world.prefs), "shizuku+")
        val client = AdbClient("127.0.0.1", world.wirelessAdbd.port, key, commitFirstUse = false)
        client.connect()
        val pins = AdbServerPin.store()
        pins.commit("another-key", pins.snapshot())
        assertThrows(AdbServerCertChangedException::class.java) {
            runBlocking { AdbStarter.startAdb(world.app, world.wirelessAdbd.port, connectedClient = client) }
        }
        world.waitUntil({ "refused scan connection closes" }) { world.wirelessAdbd.activeConnections == 0 }
        assertEquals("another-key", pins.connectPin)
    }

    @Test fun `new boot with silent mDNS posts failure notice and closes scan client`() {
        world.prefs
            .edit()
            .remove("last_adb_port")
            .commit()
        WirelessDebugging.discoveryMs = 50
        setScanDiscovery(world.wirelessAdbd)
        world.scenario {
            backgroundStart()
            world.waitUntil({ "background work to be queued" }) {
                world.workManager
                    .getWorkInfosForUniqueWork(af.shizuku.manager.worker.AdbStartWorker.UNIQUE_WORK_NAME)
                    .get()
                    .isNotEmpty()
            }
            world.workManager.getWorkInfosForUniqueWork(af.shizuku.manager.worker.AdbStartWorker.UNIQUE_WORK_NAME).get().forEach {
                androidx.work.testing.WorkManagerTestInitHelper
                    .getTestDriver(world.app)!!
                    .setAllConstraintsMet(it.id)
            }
            awaitStartWork()
            assertEquals(androidx.work.WorkInfo.State.FAILED, lastWork)
            assertTrue(log().contains("no key recorded for this boot yet"))
        }
        assertNull(AdbServerPin.store().connectPin)
        world.waitUntil({ "scan connection closes" }) { world.wirelessAdbd.activeConnections == 0 }
        val nm = world.app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notice =
            org.robolectric.Shadows
                .shadowOf(nm)
                .getNotification(af.shizuku.manager.worker.AdbStartWorker.NOTIFICATION_ID)
        assertNotNull(notice)
        val intent =
            org.robolectric.Shadows
                .shadowOf(notice.contentIntent)
                .savedIntent
        assertEquals(af.shizuku.manager.MainActivity::class.java.name, intent.component!!.className)
    }

    @Test fun `successful mDNS start clears pending port`() {
        pin(world.wirelessAdbd)
        world.serverDown()
        af.shizuku.manager.utils.ShizukuStateMachine
            .update()
        world.prefs
            .edit()
            .remove("last_adb_port")
            .commit()
        AdbServerPin.pendingPort = 44444
        WirelessDebugging.discoveryMs = 500
        world.scenario {
            backgroundStart()
            world.waitUntil({ "background work to be queued" }) {
                world.workManager
                    .getWorkInfosForUniqueWork(af.shizuku.manager.worker.AdbStartWorker.UNIQUE_WORK_NAME)
                    .get()
                    .isNotEmpty()
            }
            world.workManager.getWorkInfosForUniqueWork(af.shizuku.manager.worker.AdbStartWorker.UNIQUE_WORK_NAME).get().forEach {
                androidx.work.testing.WorkManagerTestInitHelper
                    .getTestDriver(world.app)!!
                    .setAllConstraintsMet(it.id)
            }
            awaitStartWork()
            assertEquals(androidx.work.WorkInfo.State.SUCCEEDED, lastWork)
            assertEquals(-1, AdbServerPin.pendingPort)
        }
    }

    @Test fun `transient connect error does not produce changed key notice`() {
        pin(world.wirelessAdbd)
        android.provider.Settings.Global
            .putInt(world.app.contentResolver, "adb_wifi_enabled", 1)
        val closedPort = world.closedTcpPort
        val announcements = mutableListOf<(Int) -> Unit>()
        WirelessDebugging.discovery =
            object : WirelessDebugging.PortDiscovery {
                override fun start(
                    context: Context,
                    onPort: (Int) -> Unit,
                ): () -> Unit {
                    announcements.add(onPort)
                    return {}
                }
            }
        WirelessDebugging.discoveryMs = 200
        val e =
            assertThrows(Exception::class.java) {
                runBlocking {
                    val session = WirelessDebugging.Session(world.app, {}, {}, { false })
                    launch {
                        delay(50)
                        announcements.forEach { it(closedPort) }
                    }
                    session.findPort()
                }
            }
        assertTrue("expected a timeout, got ${e::class.java.name}: ${e.message}", e is java.util.concurrent.TimeoutException)
        assertFalse("changed-key notice must not appear for a transient error", e is AdbServerCertChangedException)
    }

    @Test fun `mDNS queue cap drops oldest candidates`() {
        pin(world.wirelessAdbd)
        world.serverDown()
        af.shizuku.manager.utils.ShizukuStateMachine
            .update()
        world.prefs
            .edit()
            .remove("last_adb_port")
            .commit()
        android.provider.Settings.Global
            .putInt(world.app.contentResolver, "adb_wifi_enabled", 1)
        // 20 stale no-listener ports followed by the real one: the cap drops the oldest, but the
        // real port (newest) is still found.
        val stalePorts = (1..20).map { world.closedTcpPort + it }.filter { it <= 65535 }
        val realPort = world.wirelessAdbd.port
        val announcements = mutableListOf<(Int) -> Unit>()
        WirelessDebugging.discovery =
            object : WirelessDebugging.PortDiscovery {
                override fun start(
                    context: Context,
                    onPort: (Int) -> Unit,
                ): () -> Unit {
                    announcements.add(onPort)
                    return {}
                }
            }
        WirelessDebugging.discoveryMs = 3_000
        runBlocking {
            val session = WirelessDebugging.Session(world.app, {}, {}, { false })
            val findPort = async { session.findPort() }
            delay(100)
            stalePorts.forEach { p -> announcements.forEach { it(p) } }
            announcements.forEach { it(realPort) }
            val port = findPort.await()
            assertEquals(realPort, port)
        }
    }

    @Test fun `scan found endpoint does not commit first pin`() {
        // No pin exists.
        assertNull(AdbServerPin.store().connectPin)

        val key = AdbKey(PreferenceAdbKeyStore(world.prefs), "shizuku+")
        val scanClient =
            AdbClient(
                "127.0.0.1",
                world.wirelessAdbd.port,
                key,
                connectTimeoutMs = 2000,
                commitFirstUse = false,
            )
        scanClient.use { it.connect() }
        assertNull("scan must not take the first-use pin", AdbServerPin.store().connectPin)

        // A start that has only the scan while no pin exists must not proceed unattended.
        assertThrows(af.shizuku.manager.adb.AdbServerPinMissingException::class.java) {
            runBlocking {
                AdbStarter.startAdb(world.app, world.wirelessAdbd.port, connectedClient = scanClient)
            }
        }
        assertNull(AdbServerPin.store().connectPin)
    }
}
