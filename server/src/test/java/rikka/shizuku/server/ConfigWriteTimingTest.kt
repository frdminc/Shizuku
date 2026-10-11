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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.server.ktx.workerHandler
import kotlin.test.assertEquals
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

    private val stored = ShizukuConfig()
    private var installed: List<PackageInfo> = emptyList()
    private val writes = mutableListOf<ShizukuConfig>()
    private var duringWrite: (() -> Unit)? = null

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
        every { handler.hasCallbacks(any()) } answers {
            val runnable = firstArg<Runnable>()
            queue.any { it.runnable === runnable }
        }
        mockkStatic("rikka.shizuku.server.ktx.HandlerKt")
        every { workerHandler } returns handler

        every { ShizukuConfigManager.load() } returns stored
        every { ShizukuConfigManager.writeJson(any()) } answers {
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
    fun `a flush with nothing waiting writes nothing`() {
        val manager = start()

        manager.flushPendingWrite()
        advance(BATCH_DELAY)

        assertTrue(writes.isEmpty())
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
        const val BATCH_DELAY = 10_000L
        const val SERVER_PID = 4242
    }
}
