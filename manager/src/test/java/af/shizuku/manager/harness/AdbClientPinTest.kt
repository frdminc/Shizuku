package af.shizuku.manager.harness

import af.shizuku.manager.adb.*
import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdbClientPinTest {
    private lateinit var world: FakeWorld
    private lateinit var key: AdbKey

    @Before fun setup() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        key = AdbKey(PreferenceAdbKeyStore(world.prefs), "test")
    }

    @After fun cleanup() = world.close()

    private fun connect(server: FakeAdbd) = AdbClient("127.0.0.1", server.port, key).use { it.connect() }

    @Test fun fixedCertificateMatchesOnSecondConnection() {
        FakeAdbd(tls = true, fixedCertificate = true).use { server ->
            connect(server)
            connect(server)
            assertEquals(2, server.clientCertificates.get())
        }
    }

    @Test fun renewedCertificateWithSameSpkiMatches() {
        FakeAdbd(tls = true, fixedKey = true).use { server ->
            connect(server)
            connect(server)
            assertFalse(server.certificates[0].encoded.contentEquals(server.certificates[1].encoded))
            assertEquals(AdbServerPin.of(server.certificates[0]), AdbServerPin.of(server.certificates[1]))
        }
    }

    @Test fun changedPinGetsNoClientCertificate() {
        FakeAdbd(tls = true).use { server ->
            connect(server)
            assertThrows(AdbServerCertChangedException::class.java) { connect(server) }
            assertEquals(1, server.clientCertificates.get())
        }
    }

    @Test fun tlsWithoutCnxnDoesNotPin() {
        FakeAdbd(tls = true, omitTlsCnxn = true).use { server ->
            assertThrows(java.io.IOException::class.java) { connect(server) }
            assertNull(AdbServerPin.store().connectPin)
        }
    }

    @Test fun usbCommandClearsPinForNextTlsConnection() {
        FakeAdbd(tls = true).use { server ->
            AdbClient("127.0.0.1", server.port, key).use {
                it.connect()
                assertNotNull(AdbServerPin.store().connectPin)
                it.command("usb:")
                assertNull(AdbServerPin.store().connectPin)
            }
            connect(server)
        }
    }
}
