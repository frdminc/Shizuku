package af.shizuku.manager.harness

import af.shizuku.manager.adb.*
import af.shizuku.manager.utils.SettingsBackupManager
import android.app.Application
import android.provider.Settings
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdbServerPinTest {
    private lateinit var world: FakeWorld
    private lateinit var pins: PreferenceAdbServerPinStore
    private var boot: String? = "1"

    @Before fun setup() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        pins = PreferenceAdbServerPinStore(world.prefs) { boot }
    }

    @After fun cleanup() = world.close()

    private fun trust(
        cert: X509Certificate = first,
        expectedPin: String? = null,
    ) =
        PinnedAdbServerTrustManager(pins, expectedPin).also { it.checkServerTrusted(arrayOf(cert), "RSA") }

    @Test fun pinIsSha256OfSubjectPublicKeyInfo() {
        assertEquals(64, AdbServerPin.of(first).length)
        assertEquals(AdbServerPin.of(first), AdbServerPin.of(renewed))
        assertFalse(first.encoded.contentEquals(renewed.encoded))
        assertNotEquals(AdbServerPin.of(first), AdbServerPin.of(other))
    }

    @Test fun pinIsStoredOnlyAfterCnxn() {
        val tm = trust()
        assertNull(pins.connectPin)
        tm.commitFirstUse()
        assertEquals(AdbServerPin.of(first), pins.connectPin)
        trust(renewed).commitFirstUse()
        assertThrows(CertificateException::class.java) { trust(other) }
    }

    @Test fun emptyChainIsRefused() {
        assertThrows(CertificateException::class.java) {
            PinnedAdbServerTrustManager(pins).checkServerTrusted(emptyArray(), "RSA")
        }
    }

    @Test fun unknownBootHidesPinButStillEnforcesRecordedKey() {
        trust().commitFirstUse()
        boot = null
        assertNull(pins.connectPin)
        assertThrows(CertificateException::class.java) { trust(other) }
        boot = "2"
        assertNull(pins.connectPin)
        trust(other).commitFirstUse()
        assertThrows(CertificateException::class.java) { trust() }
    }

    @Test fun interactiveChangeIsNotStoredUntilCnxn() {
        trust().commitFirstUse()
        val tm = trust(other, AdbServerPin.of(other))
        assertEquals(AdbServerPin.of(first), pins.connectPin)
        tm.commitFirstUse()
        assertEquals(AdbServerPin.of(other), pins.connectPin)
    }

    @Test fun confirmedPinStillRequiredWhenStoredPinWasCleared() {
        trust().commitFirstUse()
        pins.clear()
        assertThrows(CertificateException::class.java) { trust(first, AdbServerPin.of(other)) }
        assertNull(pins.connectPin)
        trust(other, AdbServerPin.of(other)).commitFirstUse()
        assertEquals(AdbServerPin.of(other), pins.connectPin)
    }

    @Test fun refusalRetainsPresentedFingerprint() {
        trust().commitFirstUse()
        val tm = PinnedAdbServerTrustManager(pins)
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(other), "RSA") }
        assertEquals(AdbServerPin.of(other), tm.seenPin)
    }

    @Test fun concurrentDifferentFirstUseCannotOverwriteWinner() {
        val a = trust()
        val b = trust(other)
        a.commitFirstUse()
        assertThrows(AdbServerCertChangedException::class.java) { b.commitFirstUse() }
        assertEquals(AdbServerPin.of(first), pins.connectPin)
    }

    @Test fun restartInvalidatesInFlightHandshake() {
        val tm = trust()
        pins.clear()
        assertThrows(AdbServerCertChangedException::class.java) { tm.commitFirstUse() }
        assertNull(pins.connectPin)
    }

    @Test fun changedBootInvalidatesInFlightHandshake() {
        val tm = trust()
        boot = "2"
        assertThrows(AdbServerCertChangedException::class.java) { tm.commitFirstUse() }
    }

    @Test fun pendingPortIsIgnoredFromAnotherBoot() {
        AdbServerPin.pendingPort = 34567
        assertEquals(34567, AdbServerPin.pendingPort)
        assertEquals(34567, AdbServerPin.pendingPortForCurrentBoot())
        Settings.Global.putInt(world.app.contentResolver, Settings.Global.BOOT_COUNT, 2)
        assertEquals(34567, AdbServerPin.pendingPort)
        assertEquals(-1, AdbServerPin.pendingPortForCurrentBoot())
        AdbServerPin.clearPendingPort()
        assertEquals(-1, AdbServerPin.pendingPort)
    }

    @Test fun pinAndKeyAreExcludedFromBackupAndImport() {
        trust().commitFirstUse()
        AdbServerPin.pendingPort = 34567
        world.prefs
            .edit()
            .putString("adbkey", "local-key")
            .commit()
        val json = SettingsBackupManager.export(world.app)
        assertFalse(json.contains("adb_server_pin"))
        assertFalse(json.contains("adbkey"))
        assertTrue(
            SettingsBackupManager.importAndCommit(
                world.app,
                """{"_version":1,"adbkey":"other","adb_server_pin_connect":"other","adb_server_pin_connect_boot":"99","adb_server_pin_generation":99}""",
            ),
        )
        assertEquals(AdbServerPin.of(first), pins.connectPin)
        assertEquals("local-key", world.prefs.getString("adbkey", null))
    }

    @Test fun pairingUsesIndependentCertificate() {
        trust().commitFirstUse()
        PairingAdbServerTrustManager.checkServerTrusted(arrayOf(other), "RSA")
        assertEquals(AdbServerPin.of(first), pins.connectPin)
    }

    @Test fun wirelessToggleWithUsbEnabledKeepsPin() {
        trust().commitFirstUse()
        Settings.Global.putInt(world.app.contentResolver, Settings.Global.ADB_ENABLED, 1)
        AdbServerPin.putSetting(world.app, "adb_wifi_enabled", 1)
        AdbServerPin.putSetting(world.app, "adb_wifi_enabled", 0)
        assertEquals(AdbServerPin.of(first), pins.connectPin)
    }

    @Test fun enablingUsbClearsPinButUnchangedUsbDoesNot() {
        Settings.Global.putInt(world.app.contentResolver, Settings.Global.ADB_ENABLED, 0)
        trust().commitFirstUse()
        AdbServerPin.putSetting(world.app, Settings.Global.ADB_ENABLED, 1)
        assertNull(pins.connectPin)
        trust().commitFirstUse()
        AdbServerPin.putSetting(world.app, Settings.Global.ADB_ENABLED, 1)
        assertEquals(AdbServerPin.of(first), pins.connectPin)
    }

    @Test fun disablingLastTransportClearsPin() {
        Settings.Global.putInt(world.app.contentResolver, Settings.Global.ADB_ENABLED, 0)
        Settings.Global.putInt(world.app.contentResolver, "adb_wifi_enabled", 1)
        trust().commitFirstUse()
        AdbServerPin.putSetting(world.app, "adb_wifi_enabled", 0)
        assertNull(pins.connectPin)
    }

    companion object {
        private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }

        private fun cert(
            serial: Long,
            sameKey: Boolean,
        ): X509Certificate {
            val key = if (sameKey) pair else KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=adbd")
            val encoded =
                JcaX509v3CertificateBuilder(name, java.math.BigInteger.valueOf(serial), Date(0), Date(4_000_000_000_000L), name, key.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").build(key.private))
                    .encoded
            return java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(encoded.inputStream()) as X509Certificate
        }

        private val first by lazy { cert(1, true) }
        private val renewed by lazy { cert(2, true) }
        private val other by lazy { cert(3, false) }
    }
}
