package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Socket work never runs on the caller. Every attempt, including reads/writes/TLS, has a deadline. */
internal object LoopbackAdbScan {
    private const val CONCURRENCY = 32
    private const val CONNECT_MS = 200
    private const val ATTEMPT_MS = 1500L

    // Known non-ADB services. Never skip arbitrary open ports merely because they are registered.
    private val excluded = setOf(5037, 5353, 5555, 1717)

    internal fun parseRange(text: String?): IntRange {
        val parts = text?.trim()?.split(Regex("\\s+"))?.map { it.toIntOrNull() ?: -1 }
        return if (parts?.size == 2 && parts[0] in 1024..65535 && parts[1] in parts[0]..65535) {
            parts[0]..parts[1]
        } else {
            32768..60999
        }
    }

    fun start(
        context: Context,
        onResult: (WirelessDebugging.ScanResult) -> Unit,
        portRange: IntRange? = null,
        attemptMs: Long = ATTEMPT_MS,
        budgetMs: Long = WirelessDebugging.scanBudgetMs,
        beforeHandoff: () -> Unit = {},
    ): () -> Unit {
        val lock = Any()
        var stopped = false
        val pending = mutableSetOf<AdbClient>()
        val workers = Executors.newFixedThreadPool(CONCURRENCY) { r -> Thread(r, "adb-port-scan").apply { isDaemon = true } }
        val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "adb-scan-deadline").apply { isDaemon = true } }
        timer.removeOnCancelPolicy = true

        fun stop() {
            synchronized(lock) {
                if (stopped) return
                stopped = true
                pending.forEach { it.abort() }
                pending.clear()
            }
            workers.shutdownNow()
            timer.shutdownNow()
        }
        timer.schedule({ stop() }, budgetMs, TimeUnit.MILLISECONDS)
        workers.execute {
            try {
                val range = portRange ?: parseRange(runCatching { File("/proc/sys/net/ipv4/ip_local_port_range").readText() }.getOrNull())
                val next = AtomicInteger(range.first)
                val prefs =
                    ShizukuSettings.getPreferences() ?: run {
                        stop()
                        return@execute
                    }
                val key = AdbKey(PreferenceAdbKeyStore(prefs), "shizuku+")
                // Initialize the lazy TLS context once, before concurrent socket work.
                key.sslContext
                repeat(CONCURRENCY) {
                    if (workers.isShutdown) return@repeat
                    workers.execute scanWorker@{
                        while (!Thread.currentThread().isInterrupted) {
                            val port = next.getAndIncrement()
                            if (port > range.last) break
                            if (port in excluded) continue
                            val client = AdbClient("127.0.0.1", port, key, tlsOnly = true, connectTimeoutMs = CONNECT_MS)
                            synchronized(lock) {
                                if (stopped) return@scanWorker
                                pending += client
                            }
                            var handedOff = false
                            var expired = false
                            var deadline: java.util.concurrent.ScheduledFuture<*>? = null
                            try {
                                deadline =
                                    timer.schedule({
                                        synchronized(lock) {
                                            if (!handedOff) {
                                                expired = true
                                                client.abort()
                                            }
                                        }
                                    }, attemptMs, TimeUnit.MILLISECONDS)
                                client.connect()
                                beforeHandoff()
                                synchronized(lock) {
                                    // Cancel before publishing. If the timer already ran, this
                                    // connection is no longer usable. The lock also covers a
                                    // timer that began running just before cancel(false).
                                    val cancelled = deadline.cancel(false)
                                    if (cancelled && !expired && !stopped) {
                                        pending -= client
                                        handedOff = true
                                        onResult(WirelessDebugging.ScanResult(port, client))
                                        stop()
                                    }
                                }
                            } catch (_: Exception) {
                                // Refused, non-ADB, failed TLS, deadline, or cancellation: try the next port.
                            } finally {
                                deadline?.cancel(false)
                                synchronized(lock) { pending -= client }
                                if (!handedOff) client.close()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                stop()
            }
        }
        return { stop() }
    }
}
