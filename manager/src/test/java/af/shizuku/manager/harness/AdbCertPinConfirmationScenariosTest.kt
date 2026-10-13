package af.shizuku.manager.harness

import af.shizuku.core.ui.ThemeDelegateManager
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbServerPin
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.home.StartWirelessAdbViewHolder
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.ThemeDelegateImpl
import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdbCertPinConfirmationScenariosTest {
    private lateinit var world: FakeWorld
    private var controller: ActivityController<*>? = null

    @Before fun setup() {
        ThemeDelegateManager.setDelegate(ThemeDelegateImpl())
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        ShizukuSettings.initialize(world.app)
        ShizukuSettings.setPreferencesForTesting(world.prefs)
        world.prefs
            .edit()
            .putBoolean("tcp_mode", false)
            .commit()
    }

    @After fun cleanup() {
        controller?.destroy()
        world.close()
    }

    private fun pinFirst(server: FakeAdbd) = runBlocking { AdbStarter.startAdb(world.app, server.port) }

    /**
     * Launches StarterActivity the way the exported MainActivity route does: no user-gesture extra,
     * so a changed key must be refused without confirmation.
     */
    private fun startViaExportedIntent(port: Int) {
        val intent =
            Intent(world.app, StarterActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(StarterActivity.EXTRA_PORT, port)
                putExtra(HomeActivity.EXTRA_START_SERVICE_VIA_WADB, true)
            }
        controller =
            Robolectric
                .buildActivity(StarterActivity::class.java, intent)
                .create()
                .start()
                .resume()
                .visible()
                .windowFocusChanged(true)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    /**
     * Launches StarterActivity as a direct tap in the app's own UI would: with EXTRA_USER_GESTURE.
     */
    private fun startViaUserGesture(port: Int) {
        val intent =
            Intent(world.app, StarterActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(StarterActivity.EXTRA_PORT, port)
                putExtra(StarterActivity.EXTRA_USER_GESTURE, true)
            }
        controller =
            Robolectric
                .buildActivity(StarterActivity::class.java, intent)
                .create()
                .start()
                .resume()
                .visible()
                .windowFocusChanged(true)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    private fun latestDialog(): AlertDialog? =
        ShadowDialog.getLatestDialog() as? AlertDialog
            ?: ShadowAlertDialog.getLatestAlertDialog() as? AlertDialog

    private fun awaitDialog(timeoutMs: Long = 10_000): AlertDialog {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            latestDialog()?.let { return it }
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            Thread.sleep(50)
        }
        throw AssertionError("no dialog appeared within ${timeoutMs}ms")
    }

    @Test fun `exported intent start with changed key refuses and keeps old pin`() {
        FakeAdbd(tls = true).use { first ->
            pinFirst(first)
            FakeAdbd(tls = true, fixedCertificate = true).use { changed ->
                startViaExportedIntent(changed.port)
                Thread.sleep(500)
                ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
                // The exported route may show the generic failure dialog, but never the
                // changed-key confirmation dialog.
                latestDialog()?.let {
                    val label =
                        it
                            .getButton(AlertDialog.BUTTON_POSITIVE)
                            ?.text
                            ?.toString()
                            .orEmpty()
                    assertFalse("exported route showed changed-key dialog", "Use new key" in label)
                }
                assertEquals(AdbServerPin.of(first.certificates.first()), AdbServerPin.store().connectPin)
                assertEquals(0, changed.clientCertificates.get())
            }
        }
    }

    @Test fun `hand made start does not re-pin changed key until confirmation accepted`() {
        FakeAdbd(tls = true).use { first ->
            pinFirst(first)
            val commands =
                java.util.concurrent.atomic
                    .AtomicInteger()
            FakeAdbd(tls = true, fixedCertificate = true, onShell = { commands.incrementAndGet() }).use { changed ->
                startViaUserGesture(changed.port)
                val dialog = awaitDialog()
                assertNotNull("user gesture must show changed-key dialog", dialog)
                assertEquals(AdbServerPin.of(first.certificates.first()), AdbServerPin.store().connectPin)
                assertEquals(0, changed.clientCertificates.get())

                // Tap "Use new key".
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                val expected = AdbServerPin.of(changed.certificates.first())
                world.waitUntil({ "the new pin to be stored" }) { AdbServerPin.store().connectPin == expected }
                assertEquals(expected, AdbServerPin.store().connectPin)
                assertTrue(changed.clientCertificates.get() > 0)
                world.waitUntil({ "confirmed start command to run" }) { commands.get() > 0 }
            }
        }
    }

    @Test fun `confirmation for key B refuses key C on retry and keeps A`() {
        FakeAdbd(tls = true).use { first ->
            pinFirst(first)
            val changed = FakeAdbd(tls = true, fixedCertificate = true)
            val port = changed.port
            try {
                startViaUserGesture(port)
                val dialog = awaitDialog()
                val shown = AdbServerPin.of(changed.certificates.first())
                assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.contains(shown.take(16).uppercase()))
                changed.close()
                FakeAdbd(port = port, tls = true, fixedCertificate = true).use { third ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    world.waitUntil({ "retry to refuse key C" }) { third.connections > 0 && third.activeConnections == 0 }
                    assertEquals(AdbServerPin.of(first.certificates.first()), AdbServerPin.store().connectPin)
                    assertEquals(0, third.clientCertificates.get())
                }
            } finally {
                changed.close()
            }
        }
    }

    @Test fun `first key from scan hint needs confirmation before recording`() {
        FakeAdbd(tls = true, fixedCertificate = true).use { server ->
            AdbServerPin.pendingPort = server.port
            startViaUserGesture(server.port)
            val dialog = awaitDialog()
            val shown = AdbServerPin.of(server.certificates.first())
            val message = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text
            assertTrue(message.contains("No wireless debugging key is recorded"))
            assertTrue(message.contains(shown.take(16).uppercase()))
            assertNull(AdbServerPin.store().connectPin)
            assertEquals(0, server.clientCertificates.get())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            world.waitUntil({ "confirmed first start to finish" }) { AdbServerPin.store().connectPin == shown && AdbServerPin.pendingPort == -1 }
        }
    }

    @Test fun `live ports outrank pending hint`() {
        assertEquals(5555 to 5555, StartWirelessAdbViewHolder.resolveStartPort(5555, -1, -1, 44444, -1))
        assertEquals(5555 to 5555, StartWirelessAdbViewHolder.resolveStartPort(-1, 5555, -1, 44444, -1))
        assertEquals(5555 to 5555, StartWirelessAdbViewHolder.resolveStartPort(-1, -1, 5555, 44444, -1))
        assertEquals(44444 to 44444, StartWirelessAdbViewHolder.resolveStartPort(-1, -1, -1, 44444, -1))
        assertEquals(-1 to 33333, StartWirelessAdbViewHolder.resolveStartPort(-1, -1, -1, -1, 33333))
    }

    @Test fun `cancel on changed key confirmation keeps old pin`() {
        FakeAdbd(tls = true).use { first ->
            pinFirst(first)
            FakeAdbd(tls = true, fixedCertificate = true).use { changed ->
                startViaUserGesture(changed.port)
                val dialog = awaitDialog()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
                assertEquals(AdbServerPin.of(first.certificates.first()), AdbServerPin.store().connectPin)
                assertEquals(0, changed.clientCertificates.get())
            }
        }
    }
}
