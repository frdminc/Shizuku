package af.shizuku.manager.harness

import af.shizuku.api.BinderContainer
import af.shizuku.manager.ShizukuManagerProvider
import af.shizuku.manager.adb.LoopbackAdbScan
import af.shizuku.manager.adb.WirelessDebugging
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.ActivityLogSettingsImpl
import android.app.Application
import android.os.Binder
import android.os.Bundle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MdnsFallbackConnectionTest {
    private lateinit var world: FakeWorld
    private val stops = mutableListOf<() -> Unit>()

    @Before fun setup() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        // These short budgets measure socket deadlines, not first-use JVM crypto loading.
        af.shizuku.manager.adb
            .AdbKey(
                af.shizuku.manager.adb
                    .PreferenceAdbKeyStore(world.prefs),
                "test",
            ).sslContext
    }

    @After fun cleanup() {
        stops.forEach { it() }
        await { scanThreads().isEmpty() }
        ShadowBinder.reset()
        if (::world.isInitialized) world.close()
    }

    private fun scanThreads() =
        Thread.getAllStackTraces().keys.filter {
            it.isAlive && it.name in setOf("adb-port-scan", "adb-scan-deadline")
        }

    private fun await(
        timeoutMs: Long = 5_000,
        predicate: () -> Boolean,
    ) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!predicate() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("condition did not complete within ${timeoutMs}ms", predicate())
    }

    @Test fun `real scan hands over TLS client and accepts fresh certificate on next connection`() {
        val commands = LinkedBlockingQueue<String>()
        FakeAdbd(tls = true, onShell = { commands.add(it) }).use { server ->
            repeat(2) {
                val results = LinkedBlockingQueue<WirelessDebugging.ScanResult>()
                stops += LoopbackAdbScan.start(world.app, { results.add(it) }, server.port..server.port, budgetMs = 8_000)
                val result = results.poll(6, TimeUnit.SECONDS)
                assertNotNull("TLS listener was not found", result)
                assertEquals(server.port, result!!.port)
                result.client!!.use { client ->
                    await { scanThreads().isEmpty() }
                    // Retained client survives the old per-attempt deadline.
                    Thread.sleep(1_600)
                    client.command("shell:echo ready") { }
                    assertEquals("shell:echo ready", commands.poll(1, TimeUnit.SECONDS))
                }
                await { server.activeConnections == 0 }
                assertTrue(results.isEmpty())
            }
            assertEquals(2, server.connections)
            assertEquals(listOf(31, 31), server.greetingSizes)
            assertEquals(2, server.certificates.size)
            assertFalse(server.certificates[0].encoded.contentEquals(server.certificates[1].encoded))
            assertEquals(0, server.offers)
            assertEquals(0, server.signatures)
        }
    }

    @Test fun `legacy AUTH is closed without key offer or signature`() {
        FakeAdbd().use { server ->
            val results = LinkedBlockingQueue<WirelessDebugging.ScanResult>()
            stops += LoopbackAdbScan.start(world.app, { results.add(it) }, server.port..server.port, budgetMs = 1_000)
            await { server.connections == 1 && server.activeConnections == 0 }
            await { scanThreads().isEmpty() }
            assertTrue(results.isEmpty())
            assertEquals(0, server.offers)
            assertEquals(0, server.signatures)
        }
    }

    @Test fun `silent listener is closed at per attempt limit before total budget`() {
        FakeAdbd(silentHello = true).use { server ->
            val start = System.nanoTime()
            val results = LinkedBlockingQueue<WirelessDebugging.ScanResult>()
            stops += LoopbackAdbScan.start(world.app, { results.add(it) }, server.port..server.port, attemptMs = 150, budgetMs = 2_000)
            await(1_500) { server.connections == 1 && server.activeConnections == 0 }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
            await(2_500) { scanThreads().isEmpty() }
            assertTrue(results.isEmpty())
        }
    }

    @Test fun `total budget closes pending sockets and stops all workers`() {
        FakeAdbd(silentHello = true).use { server ->
            val results = LinkedBlockingQueue<WirelessDebugging.ScanResult>()
            stops += LoopbackAdbScan.start(world.app, { results.add(it) }, server.port..server.port, attemptMs = 5_000, budgetMs = 800)
            await { server.connections == 1 }
            await(1_500) { server.activeConnections == 0 && scanThreads().isEmpty() }
            assertTrue(results.isEmpty())
        }
    }

    @Test fun `win closes another pending listener and stops workers before caller closes winner`() {
        var pair: Pair<FakeAdbd, FakeAdbd>? = null
        for (attempt in 1..20) {
            val tls = FakeAdbd(tls = true)
            val silent = runCatching { FakeAdbd(port = tls.port + 1, silentHello = true) }.getOrNull()
            if (silent != null) {
                pair = tls to silent
                break
            }
            tls.close()
        }
        val (tls, silent) = checkNotNull(pair) { "could not reserve two adjacent loopback ports" }
        tls.use { server ->
            silent.use { loser ->
                server.onHello = { await { loser.connections == 1 } }
                val results = LinkedBlockingQueue<WirelessDebugging.ScanResult>()
                stops += LoopbackAdbScan.start(world.app, { results.add(it) }, server.port..loser.port, budgetMs = 8_000)
                val result = results.poll(6, TimeUnit.SECONDS)
                assertNotNull(result)
                result!!.client!!.use {
                    await { loser.activeConnections == 0 && scanThreads().isEmpty() }
                    assertEquals(1, server.activeConnections)
                }
                await { server.activeConnections == 0 }
                assertTrue(results.isEmpty())
            }
        }
    }

    @Test fun `candidate whose deadline fired cannot be handed over`() {
        FakeAdbd(tls = true).use { server ->
            val reached = AtomicBoolean()
            val results = LinkedBlockingQueue<WirelessDebugging.ScanResult>()
            stops +=
                LoopbackAdbScan.start(
                    world.app,
                    { results.add(it) },
                    server.port..server.port,
                    attemptMs = 1_500,
                    budgetMs = 4_000,
                    beforeHandoff = {
                        reached.set(true)
                        Thread.sleep(1_700)
                    },
                )
            await { reached.get() }
            await { scanThreads().isEmpty() }
            results.forEach { it.client?.close() }
            assertTrue("expired connection was handed over", results.isEmpty())
            await { server.activeConnections == 0 }
        }
    }

    @Test
    @Config(sdk = [29])
    fun `Android 10 does not start real scan`() {
        WirelessDebugging.resetForTesting()
        val stop = WirelessDebugging.discovery.scan(RuntimeEnvironment.getApplication()) { fail("unexpected result") }
        stop?.invoke()
        assertNull(stop)
    }

    @Test fun `binder delivery rejects app uid before using supplied binder and logs once`() {
        ActivityLogManager.initialize(world.app, ActivityLogSettingsImpl())
        val provider = Robolectric.buildContentProvider(ShizukuManagerProvider::class.java).create().get()
        var touched = false
        val supplied =
            object : Binder() {
                override fun pingBinder(): Boolean {
                    touched = true
                    return true
                }
            }
        ShadowBinder.setCallingUid(10_123)
        val before = ActivityLogManager.logs.value.size
        assertNull(provider.call("sendBinder", null, Bundle().apply { putBinder("binder", supplied) }))
        assertNull(rikka.shizuku.Shizuku.getBinder())
        assertFalse("unaccepted binder must never be queried", touched)
        assertEquals(before + 1, ActivityLogManager.logs.value.size)
        assertTrue(
            ActivityLogManager.logs.value
                .last()
                .action
                .contains("10123"),
        )
    }

    @Test fun `binder delivery preserves shell, system and root handoff`() {
        val provider = Robolectric.buildContentProvider(ShizukuManagerProvider::class.java).create().get()
        for (uid in listOf(0, 1000, 2000)) {
            world.serverDown()
            ShadowBinder.setCallingUid(uid)
            val supplied = Binder()
            assertNotNull(provider.call("sendBinder", null, Bundle().apply { putBinder("binder", supplied) }))
            assertSame(supplied, rikka.shizuku.Shizuku.getBinder())
        }
    }

    @Test fun `user service delivery rejects app uid before using supplied binder and logs once`() {
        ActivityLogManager.initialize(world.app, ActivityLogSettingsImpl())
        val provider = Robolectric.buildContentProvider(ShizukuManagerProvider::class.java).create().get()
        var touched = false
        val supplied =
            object : Binder() {
                override fun pingBinder(): Boolean {
                    touched = true
                    return true
                }
            }
        world.serverUp()
        ShadowBinder.setCallingUid(10_123)
        val before = ActivityLogManager.logs.value.size
        val extras =
            Bundle().apply {
                putParcelable("af.shizuku.plus.api.intent.extra.BINDER", BinderContainer(supplied))
                putString(rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, "test-token")
            }
        assertNull(provider.call("sendUserService", null, extras))
        assertFalse("unaccepted binder must never be queried", touched)
        assertEquals(before + 1, ActivityLogManager.logs.value.size)
        assertTrue(
            ActivityLogManager.logs.value
                .last()
                .action
                .contains("10123"),
        )
    }

    @Test fun `user service delivery preserves shell, system and root handoff`() {
        val provider = Robolectric.buildContentProvider(ShizukuManagerProvider::class.java).create().get()
        for (uid in listOf(0, 1000, 2000)) {
            world.serverUp()
            ShadowBinder.setCallingUid(uid)
            val supplied = Binder()
            val extras =
                Bundle().apply {
                    putParcelable("af.shizuku.plus.api.intent.extra.BINDER", BinderContainer(supplied))
                    putString(rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, "test-token")
                }
            val reply = provider.call("sendUserService", null, extras)
            assertNotNull("expected reply for uid $uid", reply)
            reply!!.classLoader = BinderContainer::class.java.classLoader
            assertSame(
                rikka.shizuku.Shizuku.getBinder(),
                reply.getParcelable<BinderContainer>("af.shizuku.plus.api.intent.extra.BINDER")?.binder,
            )
        }
    }
}
