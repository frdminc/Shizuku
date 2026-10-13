package af.shizuku.manager.harness

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.*
import android.app.Application
import android.provider.Settings
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdbCertPinScenariosTest {
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

    private fun start(
        server: FakeAdbd,
        log: ((String) -> Unit)? = null,
    ) =
        runBlocking {
            AdbStarter.startAdb(world.app, server.port, log)
        }

    @Test fun `pinned boot refuses changed certificate without background retry`() {
        FakeAdbd(tls = true, fixedCertificate = true).use { first -> start(first) }
        FakeAdbd(tls = true).use { changed ->
            assertThrows(AdbServerCertChangedException::class.java) { start(changed) }
            assertEquals(1, changed.connections)
            assertEquals(0, changed.clientCertificates.get())
        }
    }

    @Test fun `worker refuses changed certificate without WorkManager retry`() {
        val pins = AdbServerPin.store()
        pins.commit("different-key", pins.snapshot())
        world.prefs
            .edit()
            .putInt("last_adb_port", world.wirelessAdbd.port)
            .commit()
        // The saved port is the real adbd but with a pinned key that does not match. Discovery
        // must also find only changed keys, so let mDNS return the same real adbd and verify it.
        world.silentDiscoveries.set(0)
        af.shizuku.manager.adb.WirelessDebugging.discoveryMs = 500L
        af.shizuku.manager.adb.WirelessDebugging.backgroundBudgetMs = 2000L
        world.scenario {
            backgroundStart()
            awaitStartWork()
            assertEquals(androidx.work.WorkInfo.State.FAILED, lastWork)
            assertTrue(log().contains("no WorkManager retry"))
            // One failed connection on the saved port plus one failed verification during discovery.
            assertEquals(2, world.wirelessAdbd.connections)
        }
    }

    @Test fun `new boot count pins on first background start`() {
        FakeAdbd(tls = true).use { first -> start(first) }
        Settings.Global.putInt(world.app.contentResolver, Settings.Global.BOOT_COUNT, 2)
        FakeAdbd(tls = true, fixedCertificate = true).use { next ->
            start(next)
            start(next)
            assertEquals(2, next.connections)
        }
    }

    @Test fun `interactive start does not pin changed certificate without confirmation`() {
        FakeAdbd(tls = true).use { first -> start(first) }
        val output = StringBuilder()
        FakeAdbd(tls = true, fixedCertificate = true).use { changed ->
            assertThrows(AdbServerCertChangedException::class.java) { start(changed) { output.append(it) } }
            assertFalse(output.toString().contains("pinned changed adbd TLS certificate"))
            assertEquals(0, changed.clientCertificates.get())
        }
    }

    @Test fun `allowed pin change pins changed certificate and logs it`() {
        FakeAdbd(tls = true).use { first -> start(first) }
        val output = StringBuilder()
        FakeAdbd(tls = true, fixedCertificate = true).use { changed ->
            val refusal = assertThrows(AdbServerCertChangedException::class.java) { start(changed) }
            runBlocking {
                AdbStarter.startAdb(world.app, changed.port, { output.append(it) }, userGesture = true, confirmedPin = refusal.seenPin)
            }
            assertTrue(output.toString().contains("pinned changed adbd TLS certificate"))
            start(changed)
        }
    }

    @Test fun `own tcpip restart permits TLS reconnect in same boot`() {
        FakeAdbd(tls = true, fixedCertificate = true).use { next ->
            world.prefs
                .edit()
                .putBoolean("tcp_mode", true)
                .putString("tcp_port", next.port.toString())
                .commit()
            FakeAdbd(tls = true, fixedCertificate = true).use { first ->
                start(first)
                assertEquals(1, first.connections)
                assertEquals(1, next.connections)
                start(next)
            }
        }
    }
}
