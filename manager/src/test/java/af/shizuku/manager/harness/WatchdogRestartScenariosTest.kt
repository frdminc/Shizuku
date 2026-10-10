package af.shizuku.manager.harness

import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ShizukuStateMachine.State
import af.shizuku.manager.worker.AdbStartWorker
import android.app.Application
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The watchdog and a restart it did not make. Running the starter from outside the app (a shell,
 * ops tooling, a second device session) kills the server and starts a new one; the manager sees
 * the old binder die as a crash. The watchdog used to start a second starter at once, which raced
 * the first one's new server and left two servers running. It now waits a grace period and starts
 * nothing when a server came back meanwhile; a crash with nothing coming back is still restarted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchdogRestartScenariosTest {
    private lateinit var world: FakeWorld
    private var watchdog: ServiceController<WatchdogService>? = null

    private val grace = WatchdogService.RESTART_GRACE_MS

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        // Plain saved-port mode is the path FakeAdbd models (see OnePromptScenariosTest).
        world.prefs
            .edit()
            .putBoolean("tcp_mode", false)
            .commit()
        // A server is running, the manager holds its binder, and the watchdog is on.
        world.serverUp()
        ShizukuStateMachine.set(State.RUNNING)
        watchdog = Robolectric.buildService(WatchdogService::class.java).create()
        world.settle()
    }

    @After
    fun tearDown() {
        watchdog?.destroy()
        world.close()
    }

    private fun scenario(block: Scenario.() -> Unit) = world.scenario(block)

    /** The server process dies; Shizuku's binder-dead listener tells the state machine. */
    private fun serverKilled() {
        world.serverDown()
        ShizukuStateMachine.setDead()
        world.settle()
    }

    /** A server started from outside the app sends its binder; Shizuku's binder-received listener runs. */
    private fun newServerBinderArrives() {
        world.serverUp()
        ShizukuStateMachine.set(State.RUNNING)
        world.settle()
    }

    /** Wall time passes for the watchdog (Robolectric's clock, which its main-thread delays use). */
    private fun elapse(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        world.settle()
    }

    /** Start requests queued for the start worker, run or not: each would run the starter. */
    private fun startsQueued(): Int =
        world.workManager
            .getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME)
            .get()
            .size

    @Test
    fun `outside-restart-within-grace-starts-nothing`() =
        scenario {
            serverKilled()
            assertEquals(State.CRASHED, ShizukuStateMachine.get())
            elapse(2_000)
            newServerBinderArrives()
            elapse(grace * 4)
            check {
                assertEquals("starts the watchdog queued", 0, startsQueued())
                expectOffers(0)
                assertEquals(State.RUNNING, ShizukuStateMachine.get())
            }
        }

    @Test
    fun `crash-is-not-restarted-before-the-grace-ends`() =
        scenario {
            serverKilled()
            elapse(grace - 1_000)
            check {
                assertEquals("starts queued inside the grace period", 0, startsQueued())
                expectOffers(0)
            }
        }

    @Test
    fun `crash-with-no-server-back-is-restarted-after-the-grace`() =
        scenario {
            serverKilled()
            elapse(grace + 1_000)
            check { assertEquals("starts the watchdog queued", 1, startsQueued()) }
            keyOffered()
            dialogAccepted()
            check {
                expectOffers(1)
                assertTrue("server up again", serverRunning)
            }
        }

    @Test
    fun `withheld-restart-stays-withheld-after-the-grace`() =
        scenario {
            markerSetAt(now)
            serverKilled()
            elapse(grace * 4)
            check {
                assertEquals("starts queued while the dialog is unanswered", 0, startsQueued())
                expectOffers(0)
            }
        }
}
