package rikka.shizuku.server

import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One server at a time. Two starters run close together (the watchdog's and one from outside the
 * app) each started a server, and both then wrote the same config file. A server now takes a
 * process-lifetime guard before it touches anything; the kernel releases it when the process dies,
 * SIGKILL included. These tests drive the waiting logic over fake locks and a fake clock.
 */
class SingleInstanceGuardTest {
    /** Record locks as the kernel keeps them: a held path answers another process with null until released. */
    private class Locks : SingleInstanceGuard.Lock {
        val held = mutableSetOf<String>()

        override fun tryLock(path: String): Closeable? {
            if (!held.add(path)) return null
            return Closeable { held.remove(path) }
        }
    }

    /** A clock that moves only when the guard sleeps, running anything due by then. */
    private class Clock : SingleInstanceGuard.Clock {
        var now = 0L
        val due = mutableListOf<Pair<Long, () -> Unit>>()

        override fun uptimeMillis(): Long = now

        override fun sleep(ms: Long) {
            now += ms
            due.filter { it.first <= now }.forEach {
                due.remove(it)
                it.second()
            }
        }
    }

    private val locks = Locks()
    private val clock = Clock()
    private val path = "/data/user_de/0/com.android.shell/shizuku_plus_server.lock"

    private fun acquire(waitMs: Long = 5_000): Closeable? = SingleInstanceGuard.acquire(locks, clock, path, waitMs, 100)

    @Test
    fun `the first server takes the guard at once`() {
        assertNotNull(acquire())
        assertEquals(0L, clock.now)
    }

    @Test
    fun `a second server cannot take it while the first holds it, and gives up after the wait`() {
        val first = acquire()
        assertNotNull(first)

        assertNull(acquire(waitMs = 5_000))
        assertTrue(clock.now >= 5_000, "gave up at ${clock.now} ms")
        assertTrue(clock.now < 5_200, "kept trying until ${clock.now} ms")
        assertTrue(path in locks.held, "the first server still holds it")
    }

    @Test
    fun `once the first releases it a second server can take it`() {
        val first = acquire()!!
        assertNull(acquire(waitMs = 0))

        first.close()

        assertNotNull(acquire(waitMs = 0))
    }

    @Test
    fun `a server the starter has just killed lets the new one in when it dies`() {
        // The starter SIGKILLs the old server and starts the new one at once; the kernel frees the
        // old one's socket when it has finished dying, a moment later.
        val old = acquire()!!
        clock.due += 300L to { old.close() }

        val new = acquire(waitMs = 5_000)

        assertNotNull(new)
        assertTrue(clock.now in 300L..400L, "took it at ${clock.now} ms")
    }

    @Test
    fun `the second start of a restart pair waits out an old server that is slow to die`() {
        // Two starts close together: the second starter kills the first one's new server, which
        // was still waiting, and the old server it killed is stuck in uninterruptible I/O for some
        // seconds before the kernel can free its lock. The second new server must still take the
        // lock when the old one finally dies, not give up and leave the device with no server.
        val old = acquire()!!
        clock.due += 8_000L to { old.close() }

        val second = SingleInstanceGuard.acquire(locks, clock, path, SingleInstanceGuard.WAIT_MS, SingleInstanceGuard.RETRY_MS)

        assertNotNull(second)
        assertTrue(clock.now in 8_000L..8_100L, "took it at ${clock.now} ms")
    }

    @Test
    fun `the lock file is used only when it is a regular file with one link owned by root or shell`() {
        assertTrue(SingleInstanceGuard.isPlainFile(true, 1, 0))
        assertTrue(SingleInstanceGuard.isPlainFile(true, 1, 2000))
        kotlin.test.assertFalse(SingleInstanceGuard.isPlainFile(false, 1, 0), "a symbolic link, directory or other node")
        kotlin.test.assertFalse(SingleInstanceGuard.isPlainFile(true, 2, 0), "a second link to the file")
        kotlin.test.assertFalse(SingleInstanceGuard.isPlainFile(true, 1, 10_123), "owned by an app")
    }

    @Test
    fun `a lock that fails for another reason is not retried as contention`() {
        val broken =
            object : SingleInstanceGuard.Lock {
                var tries = 0

                override fun tryLock(path: String): Closeable? {
                    tries++
                    throw IOException("Permission denied")
                }
            }

        val guard = SingleInstanceGuard.acquire(broken, clock, path, 5_000, 100)

        // The server must not refuse to run because the guard itself cannot work here.
        assertNotNull(guard)
        assertEquals(1, broken.tries)
    }
}
