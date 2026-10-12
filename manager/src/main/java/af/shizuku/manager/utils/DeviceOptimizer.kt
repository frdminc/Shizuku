package af.shizuku.manager.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

object DeviceOptimizer {
    /**
     * Tests only: run after this app is added to the Doze whitelist, so a scenario can simulate
     * the whitelist command's effect (the app becoming exempt from battery optimization).
     */
    @androidx.annotation.VisibleForTesting
    var onAppWhitelistedForTesting: ((String) -> Unit)? = null

    /**
     * Tests only: return the standard output for a shell command (null = the command failed or
     * produced nothing), so a scenario can fake the appops / standby-bucket / autostart readings
     * and the fix commands without a real server or root shell.
     */
    @androidx.annotation.VisibleForTesting
    var runCommandOutputForTesting: ((String) -> String?)? = null

    private fun runCommand(cmd: String): Boolean {
        if (Shizuku.pingBinder()) {
            try {
                val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                if (p != null) {
                    val code = p.waitFor()
                    p.destroy()
                    return code == 0
                }
            } catch (e: Exception) {
                Timber.tag("DeviceOptimizer").w(e, "Command failed via Shizuku: $cmd")
            }
        }
        if (EnvironmentUtils.isRooted()) {
            try {
                return com.topjohnwu.superuser.Shell
                    .cmd(cmd)
                    .exec()
                    .isSuccess
            } catch (e: Exception) {
                Timber.tag("DeviceOptimizer").w(e, "Command failed via Root: $cmd")
            }
        }
        return false
    }

    /** How long a read through [runCommandOutput] may take before it is abandoned. */
    private const val COMMAND_OUTPUT_TIMEOUT_SECONDS = 10L

    /**
     * [runCommand] but returning the command's standard output, so callers can read settings and
     * appops from the shell. Returns null when the command could not run, exited non-zero or took
     * longer than [COMMAND_OUTPUT_TIMEOUT_SECONDS]. Standard error is discarded, so a command that
     * writes a lot there cannot fill its pipe and stall. Root is used only when the server is not
     * reachable: a failed read through the server never falls through to a root prompt.
     */
    internal fun runCommandOutput(cmd: String): String? {
        runCommandOutputForTesting?.let { return it(cmd) }
        if (Shizuku.pingBinder()) {
            var p: rikka.shizuku.ShizukuRemoteProcess? = null
            try {
                val proc = Shizuku.newProcess(arrayOf("sh", "-c", "$cmd 2>/dev/null"), null, null) ?: return null
                p = proc
                val output =
                    java.util.concurrent.atomic
                        .AtomicReference<String?>(null)
                // Read on its own thread so the wait below can time out; destroy() in finally
                // closes the stream and ends the reader.
                val reader =
                    Thread {
                        try {
                            output.set(proc.inputStream.bufferedReader().use { it.readText() })
                        } catch (_: Exception) {
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                if (!proc.waitForTimeout(COMMAND_OUTPUT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                    Timber.tag("DeviceOptimizer").w("Command timed out via Shizuku: $cmd")
                    return null
                }
                reader.join(
                    java.util.concurrent.TimeUnit.SECONDS
                        .toMillis(COMMAND_OUTPUT_TIMEOUT_SECONDS),
                )
                return if (proc.exitValue() == 0) output.get() else null
            } catch (e: Exception) {
                Timber.tag("DeviceOptimizer").w(e, "Command failed via Shizuku: $cmd")
                return null
            } finally {
                try {
                    p?.destroy()
                } catch (_: Exception) {
                }
            }
        }
        if (EnvironmentUtils.isRooted()) {
            try {
                val result =
                    com.topjohnwu.superuser.Shell
                        .cmd(cmd)
                        .exec()
                if (result.isSuccess) return result.out.joinToString("\n")
            } catch (e: Exception) {
                Timber.tag("DeviceOptimizer").w(e, "Command failed via Root: $cmd")
            }
        }
        return null
    }

    suspend fun applyFixes(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (!Shizuku.pingBinder() && !EnvironmentUtils.isRooted()) {
                return@withContext false
            }

            val pkgName = context.packageName

            // 1. Whitelist Shizuku from Doze / Battery Optimization
            runCommand("dumpsys deviceidle whitelist +$pkgName")
            onAppWhitelistedForTesting?.invoke(pkgName)

            // 2. Whitelist known terminal and companion packages if installed
            val clientPackages =
                listOf(
                    "com.termux",
                    "com.termux.api",
                    "in.hridayan.ashell",
                    "com.rubex.nfile",
                )
            val pm = context.packageManager
            for (client in clientPackages) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(client, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(client, 0)
                    }
                    runCommand("dumpsys deviceidle whitelist +$client")
                } catch (_: PackageManager.NameNotFoundException) {
                    // Ignore uninstalled packages
                }
            }

            // 3. Grant WRITE_SECURE_SETTINGS to self if not granted
            if (!SettingsHelper.hasWriteSecureSettings(context)) {
                runCommand("pm grant $pkgName android.permission.WRITE_SECURE_SETTINGS")
            }

            // 4. Set AppOps for background resilience
            runCommand("appops set $pkgName SYSTEM_ALERT_WINDOW allow")
            runCommand("appops set $pkgName GET_USAGE_STATS allow")
            runCommand("appops set $pkgName SCHEDULE_EXACT_ALARM allow")

            // 5. Remove the copy of the starter earlier versions kept at /data/local/tmp/shizuku,
            // or inside a shizuku directory there. Nothing runs it, and the starter finds the
            // manager's APK from beside itself in the app's native library directory. The
            // directory goes only when it is shell's and holds nothing but that copy.
            runCommand("[ -f /data/local/tmp/shizuku ] && rm -f /data/local/tmp/shizuku")
            runCommand(
                "d=/data/local/tmp/shizuku; " +
                    "if [ -d \"\$d\" ] && [ ! -L \"\$d\" ] && [ \"\$(stat -c %u \"\$d\")\" = 2000 ] " +
                    "&& [ \"\$(ls -A \"\$d\")\" = libshizuku.so ]; then rm -f \"\$d/libshizuku.so\" && rmdir \"\$d\"; fi",
            )

            true
        }
}
