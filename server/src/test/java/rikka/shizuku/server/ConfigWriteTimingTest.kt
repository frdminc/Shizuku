package rikka.shizuku.server

import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.compat.InstalledPackagesCompat
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.SystemClock
import android.system.Os
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.server.ktx.workerHandler
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A grant, revoke or flag change the operator makes must reach the config file promptly: the
 * server can be stopped (the app's Stop) or killed and started again (the starter, the watchdog)
 * at any moment, and a change still waiting to be written is then lost, so a revoked app gets its
 * access back. The start-up reconcile and import, which change many entries at once, still write
 * once after a delay. The worker handler here is a fake with its own clock: [advance] runs what
 * is due, and every config write is recorded instead of reaching the disk.
 */
class ConfigWriteTimingTest {

    @TempDir
    lateinit var dir: Path

    private val stored = ShizukuConfig()
    private var installed: List<PackageInfo> = emptyList()
    private val writes = mutableListOf<ShizukuConfig>()
    private var duringWrite: (() -> Unit)? = null
    /** How many of the next writes fail, as a full disk would fail them. */
    private var failWrites = 0
    /** The clock at every write attempt, failed or not. */
    private val attempts = mutableListOf<Long>()

    private var now = 0L
    private val queue = mutableListOf<Posted>()

    private class Posted(val runnable: Runnable, val at: Long)

    @BeforeEach
    fun setup() {
        mockkStatic(
            ShizukuConfigManager::class, InstalledPackagesCompat::class, UserManagerApis::class,
            PackageManagerApis::class, Android17Compat::class, PermissionDefiners::class, SystemClock::class,
        )
        val handler = mockk<Handler>()
        every { handler.post(any()) } answers { queue.add(Posted(firstArg(), now)) }
        every { handler.postDelayed(any<Runnable>(), any<Long>()) } answers {
            queue.add(Posted(firstArg(), now + secondArg<Long>()))
        }
        every { handler.removeCallbacks(any<Runnable>()) } answers {
            val runnable = firstArg<Runnable>()
            queue.removeAll { it.runnable === runnable }
            Unit
        }
        mockkStatic("rikka.shizuku.server.ktx.HandlerKt")
        every { workerHandler } returns handler

        every { ShizukuConfigManager.load() } returns stored
        every { ShizukuConfigManager.writeJson(any()) } answers {
            attempts.add(now)
            if (failWrites > 0) {
                failWrites--
                duringWrite?.let {
                    duringWrite = null
                    it()
                }
                throw IOException("No space left on device")
            }
            writes.add(Gson().fromJson(firstArg<String>(), ShizukuConfig::class.java))
            duringWrite?.let {
                duringWrite = null
                it()
            }
        }
        every { UserManagerApis.getUserIdsNoThrow() } returns listOf(0)
        every { InstalledPackagesCompat.getInstalledPackagesNoThrow(any(), 0) } answers { installed }
        every { PackageManagerApis.getPackagesForUid(any()) } answers { arrayOf(packageOf(firstArg())) }
        every { PackageManagerApis.getPackagesForUidNoThrow(any()) } answers { listOf(packageOf(firstArg())) }
        every { ShizukuConfigManager.currentSigners(any(), any()) } returns listOf(OURS)
        every { ShizukuConfigManager.signersReadable() } returns true
        every { Android17Compat.checkPermission(any<String>(), any<Int>()) } returns PackageManager.PERMISSION_DENIED
        every { Android17Compat.getPackageInfo(any(), any(), any()) } returns null
        every { PermissionDefiners.isTrusted(any(), any()) } returns true
        every { SystemClock.uptimeMillis() } answers { now }
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // --- operator changes ----------------------------------------------------------------------

    @Test
    fun `a grant made after start-up is written without waiting for the batch delay`() {
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)

        assertEquals(1, writes.size, "the grant was not written at once")
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.last(), APP_UID))
    }

    @Test
    fun `a revoke made after start-up is written without waiting for the batch delay`() {
        storeGrant(APP_UID)
        val manager = start()
        advance(BATCH_DELAY)
        writes.clear()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)

        assertEquals(1, writes.size, "the revoke was not written at once")
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.last(), APP_UID))
    }

    @Test
    fun `a removal made after start-up is written without waiting for the batch delay`() {
        storeGrant(APP_UID)
        val manager = start()
        advance(BATCH_DELAY)
        writes.clear()

        manager.remove(APP_UID)
        advance(0)

        assertEquals(1, writes.size, "the removal was not written at once")
        assertEquals(null, flagsOf(writes.last(), APP_UID))
    }

    @Test
    fun `changes made together are written once`() {
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)
        advance(BATCH_DELAY)

        assertEquals(1, writes.size)
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), APP_UID))
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), OTHER_UID))
    }

    @Test
    fun `a change made while a write runs is written by a write of its own`() {
        val manager = start()
        duringWrite = {
            manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        }

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)

        assertEquals(2, writes.size, "the change made during the first write was left for later")
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.last(), OTHER_UID))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.last(), APP_UID))
    }

    // --- start-up ------------------------------------------------------------------------------

    @Test
    fun `the start-up import is written once, after the batch delay`() {
        importHolders(10_201, 10_202, 10_203)

        start()
        advance(BATCH_DELAY - 1)
        assertTrue(writes.isEmpty(), "the start-up import was written before the batch delay")
        advance(1)

        assertEquals(1, writes.size)
        assertEquals(3, writes.single().packages.size)
        advance(BATCH_DELAY)
        assertEquals(1, writes.size)
    }

    @Test
    fun `a grant while the start-up write waits brings it forward, and one write holds both`() {
        importHolders(10_201, 10_202)
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)

        assertEquals(1, writes.size, "the grant waited behind the start-up write")
        val written = writes.single()
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(written, APP_UID))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(written, 10_201))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(written, 10_202))
        advance(BATCH_DELAY)
        assertEquals(1, writes.size, "the start-up write ran again with nothing new")
    }

    // --- flush ---------------------------------------------------------------------------------

    @Test
    fun `a flush writes a waiting change on the calling thread and the worker does not write it again`() {
        importHolders(10_201)
        val manager = start()
        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)

        manager.flushPendingWrite()

        assertEquals(1, writes.size, "the flush did not write the waiting change")
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), APP_UID))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), 10_201))
        advance(BATCH_DELAY)
        assertEquals(1, writes.size)
    }

    @Test
    fun `a flush with nothing waiting writes nothing and does not start the worker`() {
        val manager = start()

        manager.flushPendingWrite()
        advance(BATCH_DELAY)

        assertTrue(writes.isEmpty())
        verify(exactly = 0) { workerHandler }
    }

    @Test
    fun `the worker's write and an exit flush on another thread reach the disk in order`() {
        // The real config file logic over a real directory; the worker's write of change A is
        // held after the manager copied the config and before the file is written, which is
        // where a flush that did not wait for it would overtake it with a newer copy.
        val file = SafeConfigFile(dir.resolve("shizuku.json").toFile(), SafeConfigFileTest.NioOps(), 4242)
        val onDisk = Collections.synchronizedList(mutableListOf<ShizukuConfig>())
        val workerWriting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        every { ShizukuConfigManager.writeJson(any()) } answers {
            if (first.getAndSet(false)) {
                workerWriting.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            file.write(firstArg<String>().toByteArray())
            onDisk.add(Gson().fromJson(String(file.read()!!), ShizukuConfig::class.java))
        }
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        val write = queue.single().runnable
        queue.clear()
        val worker = thread(name = "Worker") { write.run() }
        assertTrue(workerWriting.await(5, TimeUnit.SECONDS), "the worker did not start writing")

        manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        val flushed = CountDownLatch(1)
        val exiting = thread(name = "exit") {
            manager.flushPendingWrite()
            flushed.countDown()
        }
        assertFalse(flushed.await(200, TimeUnit.MILLISECONDS), "the flush did not wait for the write in progress")
        release.countDown()
        worker.join(5_000)
        exiting.join(5_000)

        assertEquals(2, onDisk.size, "a write was lost or written twice")
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(onDisk[0], APP_UID))
        assertEquals(null, flagsOf(onDisk[0], OTHER_UID), "the worker's write held a change made after its copy")
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(onDisk[1], APP_UID))
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(onDisk[1], OTHER_UID), "the flush lost the change made during the write")
        val last = Gson().fromJson(String(file.read()!!), ShizukuConfig::class.java)
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(last, OTHER_UID), "an older copy reached the disk last")
        assertTrue(queue.isEmpty(), "the worker still has a write waiting after the flush")
    }

    // --- failed writes -------------------------------------------------------------------------

    @Test
    fun `a write that fails is tried again and the change reaches the disk`() {
        // The real config file logic over a real directory, with the temp file failing to be
        // created on the first attempt, as a full disk fails it.
        val ops = SafeConfigFileTest.FaultyOps(SafeConfigFileTest.NioOps()).apply { failCreate = true }
        val file = SafeConfigFile(dir.resolve("shizuku.json").toFile(), ops, 4242)
        every { ShizukuConfigManager.writeJson(any()) } answers {
            attempts.add(now)
            file.write(firstArg<String>().toByteArray())
        }
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)
        assertEquals(null, file.read(), "the failed write left something at the config's name")
        ops.failCreate = false
        advance(BATCH_DELAY)

        assertEquals(2, attempts.size, "the failed write was not tried again, or was tried more than once")
        val onDisk = Gson().fromJson(String(file.read()!!), ShizukuConfig::class.java)
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(onDisk, APP_UID))
        assertEquals(listOf("shizuku.json"), dir.toFile().list()!!.sorted(), "a temp file was left behind")
        assertTrue(queue.isEmpty(), "a write is still waiting after the change was saved")
    }

    @Test
    fun `an exit flush after a failed write writes the change`() {
        val manager = start()
        failWrites = 1

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)
        assertTrue(writes.isEmpty())
        manager.flushPendingWrite()

        assertEquals(1, writes.size, "the exit flush found nothing waiting after the failed write")
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), APP_UID))
        advance(BATCH_DELAY)
        assertEquals(1, writes.size, "the retry wrote again after the flush had saved the change")
    }

    @Test
    fun `a change made while a write fails is written at once and the earlier change with it`() {
        val manager = start()
        failWrites = 1
        duringWrite = {
            manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        }

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)

        assertEquals(listOf(0L, 0L), attempts, "the change made during the failed write waited for the retry")
        assertEquals(1, writes.size)
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), APP_UID))
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), OTHER_UID))
        advance(BATCH_DELAY)
        assertEquals(1, writes.size, "a write ran again with nothing new")
    }

    @Test
    fun `changes made while writes keep failing are all written once a write succeeds`() {
        val manager = start()
        failWrites = 3

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)
        manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)
        manager.update(THIRD_UID, listOf(packageOf(THIRD_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(BATCH_DELAY * 3)

        assertEquals(4, attempts.size)
        assertEquals(1, writes.size)
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), APP_UID))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), OTHER_UID))
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), THIRD_UID))
        assertTrue(queue.isEmpty(), "a write is still waiting after every change was saved")
    }

    @Test
    fun `writes that keep failing are tried again at a bounded rate`() {
        val manager = start()
        failWrites = Int.MAX_VALUE

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(MINUTE)

        val gaps = attempts.zipWithNext { a, b -> b - a }
        assertTrue(attempts.size >= 4, "the failed write was tried only ${attempts.size} times in a minute")
        assertTrue(attempts.size <= 12, "the failed write was tried ${attempts.size} times in a minute: $attempts")
        assertTrue(gaps.all { it >= 1_000 }, "attempts closer than a second apart: $attempts")
        assertTrue(gaps.all { it <= BATCH_DELAY }, "attempts more than the batch delay apart: $attempts")
        assertEquals(1, queue.size, "not exactly one retry waiting")
    }

    @Test
    fun `a write that succeeds ends the failure streak`() {
        val manager = start()
        failWrites = 4

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(MINUTE)
        assertEquals(1, writes.size)
        attempts.clear()
        failWrites = 1

        manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        val first = now
        advance(MINUTE)

        assertEquals(2, attempts.size)
        assertEquals(first, attempts[0])
        assertEquals(FIRST_RETRY, attempts[1] - attempts[0], "the retry kept the earlier streak's longer wait")
        assertEquals(2, writes.size)
    }

    // --- exit ----------------------------------------------------------------------------------

    @Test
    fun `the app's Stop writes a waiting change before the server exits`() {
        val order = mutableListOf<String>()
        val manager = mockk<ShizukuConfigManager>()
        every { manager.flushPendingWrite() } answers {
            order.add("flush")
            Unit
        }
        val service = serviceExitingInto(order)
        setField(ShizukuService::class.java, service, "configManager", manager)

        service.exit()

        assertEquals(listOf("flush", "exit 0"), order)
    }

    @Test
    fun `an exit still ends the server when the config cannot be written`() {
        val order = mutableListOf<String>()
        val manager = mockk<ShizukuConfigManager>()
        every { manager.flushPendingWrite() } throws IllegalStateException("cannot write")
        val service = serviceExitingInto(order)
        setField(ShizukuService::class.java, service, "configManager", manager)

        service.exit()

        assertEquals(listOf("exit 0"), order)
    }

    @Test
    fun `an exit does not wait longer than its bound for a config write that does not finish`() {
        // The worker's write hangs while it holds the config file, as a stalled sync would.
        val stuck = CountDownLatch(1)
        val hung = CountDownLatch(1)
        val written = CountDownLatch(2)
        every { ShizukuConfigManager.writeJson(any()) } answers {
            hung.countDown()
            stuck.await(10, TimeUnit.SECONDS)
            written.countDown()
            Unit
        }
        val manager = start()
        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        val write = queue.single().runnable
        queue.clear()
        val worker = thread(name = "Worker") { write.run() }
        try {
            assertTrue(hung.await(5, TimeUnit.SECONDS))
            manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
            val order = Collections.synchronizedList(mutableListOf<String>())
            val service = serviceExitingInto(order)
            every { ShizukuService.exitFlushTimeoutMillis() } returns EXIT_BOUND
            setField(ShizukuService::class.java, service, "configManager", manager)

            val started = System.nanoTime()
            service.exit()
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertEquals(listOf("exit 0"), order)
            assertTrue(waited >= EXIT_BOUND, "the exit did not wait for the write at all ($waited ms)")
            assertTrue(waited < 5_000, "the exit waited $waited ms for a write that does not finish")
        } finally {
            // The flush left behind writes once the worker's write ends; let it, before teardown.
            stuck.countDown()
            worker.join(5_000)
            written.await(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `the uncaught-exception handler writes a waiting change and exits with status 1`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val manager = mockk<ShizukuConfigManager>()
        every { manager.flushPendingWrite() } answers {
            order.add("flush")
            Unit
        }
        val service = serviceExitingInto(order)
        setField(ShizukuService::class.java, service, "configManager", manager)

        service.onUncaughtException(Thread.currentThread(), IllegalStateException("crash"))

        assertEquals(listOf("flush", "exit 1"), order)
    }

    @Test
    fun `the uncaught-exception handler exits within its bound when the config write hangs`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val stuck = CountDownLatch(1)
        val manager = mockk<ShizukuConfigManager>()
        every { manager.flushPendingWrite() } answers {
            stuck.await(10, TimeUnit.SECONDS)
            Unit
        }
        val service = serviceExitingInto(order)
        every { ShizukuService.exitFlushTimeoutMillis() } returns EXIT_BOUND
        setField(ShizukuService::class.java, service, "configManager", manager)

        try {
            service.onUncaughtException(Thread.currentThread(), IllegalStateException("crash"))
            assertEquals(listOf("exit 1"), order)
        } finally {
            stuck.countDown()
        }
    }

    @Test
    fun `an exit before the config manager is stored does not write`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val service = serviceExitingInto(order)

        service.onUncaughtException(Thread.currentThread(), IllegalStateException("crash"))

        assertEquals(listOf("exit 1"), order)
    }

    /** A ShizukuService whose exits are recorded in [order] instead of ending the JVM. */
    private fun serviceExitingInto(order: MutableList<String>): ShizukuService {
        // enforceManagerPermission passes a call from the server's own pid.
        mockkStatic(Binder::class, Os::class)
        every { Binder.getCallingUid() } returns 0
        every { Binder.getCallingPid() } returns SERVER_PID
        every { Os.getpid() } returns SERVER_PID
        mockkStatic(ShizukuService::class)
        every { ShizukuService.exitProcess(any()) } answers {
            order.add("exit ${firstArg<Int>()}")
            Unit
        }
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        // The constructor starts the real server; exit() needs only the config manager.
        return unsafe.allocateInstance(ShizukuService::class.java) as ShizukuService
    }

    private fun setField(owner: Class<*>, target: Any, name: String, value: Any) {
        val field = owner.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun start() = ShizukuConfigManager { false }

    /** Runs every posted runnable due within [ms] from now, in order, and moves the clock on. */
    private fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = queue.filter { it.at <= until }.minByOrNull { it.at } ?: break
            queue.remove(next)
            now = maxOf(now, next.at)
            next.runnable.run()
        }
        now = until
    }

    private fun storeGrant(uid: Int) {
        stored.packages.add(
            ShizukuConfig.PackageEntry(uid, ConfigManager.FLAG_ALLOWED).apply {
                packages.add(packageOf(uid))
                signers = listOf(OURS)
            }
        )
        installed = installed + holder(uid, null)
    }

    /** Installed packages that hold the API permission, which the start-up import grants. */
    private fun importHolders(vararg uids: Int) {
        installed = installed + uids.map { holder(it, ServerConstants.PERMISSION) }
        for (uid in uids) {
            every { Android17Compat.checkPermission(ServerConstants.PERMISSION, uid) } returns PackageManager.PERMISSION_GRANTED
        }
    }

    private fun holder(uid: Int, permission: String?) = PackageInfo().apply {
        packageName = packageOf(uid)
        applicationInfo = ApplicationInfo().apply {
            this.uid = uid
            flags = ApplicationInfo.FLAG_INSTALLED
        }
        requestedPermissions = permission?.let { arrayOf(it) }
    }

    private fun packageOf(uid: Int) = "com.example.app$uid"

    private fun flagsOf(config: ShizukuConfig, uid: Int): Int? =
        config.packages.firstOrNull { it.uid == uid }?.flags?.and(ConfigManager.MASK_PERMISSION)

    private companion object {
        val OURS = "aa".repeat(32)
        const val APP_UID = 10_123
        const val OTHER_UID = 10_124
        const val THIRD_UID = 10_125
        const val MINUTE = 60_000L
        const val FIRST_RETRY = 1_000L
        const val BATCH_DELAY = 10_000L
        const val SERVER_PID = 4242
        const val EXIT_BOUND = 150L
    }
}
