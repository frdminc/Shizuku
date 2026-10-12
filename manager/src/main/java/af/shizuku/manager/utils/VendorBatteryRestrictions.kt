package af.shizuku.manager.utils

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * #40: vendors add their own battery restrictions on top of Android's Doze exemption, and those can
 * stop the Watchdog even when the #36 exemption holds. At every start while the Watchdog is on (unlike
 * the one-shot #36 check: vendors re-apply these), undo what is readable and fixable from the server's
 * shell when Device Hardening is on, and otherwise tell the user once what still restricts the app.
 */
object VendorBatteryRestrictions {
    private const val CHANNEL_ID = "battery_optimization"
    const val NOTIFICATION_ID = 1455

    // Restriction keys for the persisted "last reported" record (the set of restrictions the user was
    // last notified about; a change in the set posts again).
    private const val KEY_BACKGROUND_ACTIVITY = "background_activity"
    private const val KEY_STANDBY_BUCKET = "standby_bucket"
    private const val KEY_SAMSUNG_DEEP_SLEEP = "samsung_deep_sleep"
    private const val KEY_XIAOMI_AUTOSTART = "xiaomi_autostart"
    private const val KEY_XIAOMI_NO_RESTRICT = "xiaomi_no_restrict"

    // Activity-log actions a scenario asserts on. "Undid" lines name what a fix removed; "reported"
    // lines name what is still restricted (a fix that did not hold, Device Hardening off, or a
    // report-only vendor signal).
    const val LOG_ACTION_UNDID_BACKGROUND_ACTIVITY =
        "Watchdog: re-enabled background activity that a battery restriction had blocked"
    const val LOG_ACTION_UNDID_STANDBY_BUCKET =
        "Watchdog: moved the app out of the battery standby bucket that held back background work"
    const val LOG_ACTION_UNDID_SAMSUNG_DEEP_SLEEP =
        "Watchdog: re-enabled the app that Samsung put in Deep sleeping apps"
    const val LOG_ACTION_UNDID_XIAOMI_AUTOSTART =
        "Watchdog: re-enabled Xiaomi autostart for the app"
    const val LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY =
        "Watchdog: background activity is restricted and may stop the Watchdog"
    const val LOG_ACTION_REPORTED_STANDBY_BUCKET =
        "Watchdog: the battery standby bucket restricts the app and may stop the Watchdog"
    const val LOG_ACTION_REPORTED_SAMSUNG_DEEP_SLEEP =
        "Watchdog: Samsung Deep sleeping apps restricts the app and may stop the Watchdog"
    const val LOG_ACTION_REPORTED_XIAOMI_AUTOSTART =
        "Watchdog: Xiaomi autostart is off and may stop the Watchdog"
    const val LOG_ACTION_REPORTED_XIAOMI_NO_RESTRICT =
        "Watchdog: the app is missing from the Xiaomi battery saver whitelist"

    /** Tests only: force the package's enabled setting to this value. Null defers to PackageManager. */
    @androidx.annotation.VisibleForTesting
    var applicationEnabledSettingForTesting: ((String) -> Int)? = null

    /** Tests only: force [resolves] to this value. Null defers to PackageManager.resolveActivity. */
    @androidx.annotation.VisibleForTesting
    var resolveActivityForTesting: ((Context, Intent) -> Boolean)? = null

    // The restriction set this process last logged. The persisted record changes only when a
    // notification is posted, so with notifications blocked this keeps an unchanged restriction
    // from being logged again at every start.
    @Volatile private var lastLogged: Set<String>? = null

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        lastLogged = null
    }

    private class Finding(
        val key: String,
        val nameRes: Int,
        val undone: Boolean,
        val vendorScreens: List<Intent> = emptyList(),
    )

    /**
     * Checks every vendor restriction while the Watchdog is on, undoing what Device Hardening allows
     * and reporting (once, per restriction set) what remains. The shell work runs on the caller's IO
     * thread; the report (the tap-target resolution and the notification) touches system services,
     * which the harness serves reliably only from the main looper.
     */
    suspend fun check(context: Context) {
        if (!ShizukuSettings.getWatchdog()) return
        val pkg = context.packageName
        val hardening = ShizukuSettings.isDeviceHardeningEnabled()

        val findings = mutableListOf<Finding>()
        checkBackgroundActivity(pkg, hardening)?.let { findings += it }
        checkStandbyBucket(pkg, hardening)?.let { findings += it }
        if (isXiaomi()) {
            checkXiaomiAutostart(pkg, hardening)?.let { findings += it }
            checkXiaomiNoRestrict(context, pkg)?.let { findings += it }
        }
        if (isSamsung()) {
            checkSamsungDeepSleep(context, pkg, hardening)?.let { findings += it }
        }

        // The report touches system services (PackageManager for the tap target, NotificationManager),
        // which the harness serves reliably only from the main looper; the shell work above stays on
        // the caller's IO thread.
        withContext(Dispatchers.Main) {
            try {
                report(context, findings)
            } catch (e: Exception) {
                Timber.tag("VendorBatteryRestrictions").w(e, "Failed to report vendor battery restrictions")
            }
        }
    }

    // --- Universal checks ---

    private fun checkBackgroundActivity(
        pkg: String,
        hardening: Boolean,
    ): Finding? {
        val read = DeviceOptimizer.runCommandOutput("cmd appops get $pkg RUN_ANY_IN_BACKGROUND") ?: return null
        if (!read.contains("RUN_ANY_IN_BACKGROUND: ignore")) return null
        val fixed = hardening && DeviceOptimizer.runCommandOutput("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow") != null
        val stillRestricted =
            if (fixed) {
                DeviceOptimizer
                    .runCommandOutput("cmd appops get $pkg RUN_ANY_IN_BACKGROUND")
                    ?.contains("RUN_ANY_IN_BACKGROUND: ignore") != false
            } else {
                true
            }
        return Finding(KEY_BACKGROUND_ACTIVITY, R.string.vendor_battery_restriction_background_activity, undone = fixed && !stillRestricted)
    }

    private fun checkStandbyBucket(
        pkg: String,
        hardening: Boolean,
    ): Finding? {
        val read = DeviceOptimizer.runCommandOutput("am get-standby-bucket $pkg") ?: return null
        if (read.trim() != "45") return null
        val fixed = hardening && DeviceOptimizer.runCommandOutput("am set-standby-bucket $pkg active") != null
        val stillRestricted =
            if (fixed) {
                DeviceOptimizer.runCommandOutput("am get-standby-bucket $pkg")?.trim() == "45"
            } else {
                true
            }
        return Finding(KEY_STANDBY_BUCKET, R.string.vendor_battery_restriction_standby_bucket, undone = fixed && !stillRestricted)
    }

    // --- Samsung ---

    private fun checkSamsungDeepSleep(
        context: Context,
        pkg: String,
        hardening: Boolean,
    ): Finding? {
        if (applicationEnabledSetting(context, pkg) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) return null
        val fixed = hardening && DeviceOptimizer.runCommandOutput("pm enable $pkg") != null
        val stillDisabled =
            if (fixed) {
                applicationEnabledSetting(context, pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            } else {
                true
            }
        return Finding(
            KEY_SAMSUNG_DEEP_SLEEP,
            R.string.vendor_battery_restriction_samsung_deep_sleep,
            undone = fixed && !stillDisabled,
            samsungNeverSleepingScreens(),
        )
    }

    // --- Xiaomi ---

    private fun checkXiaomiAutostart(
        pkg: String,
        hardening: Boolean,
    ): Finding? {
        val read = DeviceOptimizer.runCommandOutput("cmd appops get $pkg 10008") ?: return null
        // Only an explicit mode is a restriction: "No operations." (no recorded mode) or an unknown
        // op on another build is not evidence either way.
        if (!AUTOSTART_DENIED.containsMatchIn(read)) return null
        val fixed = hardening && DeviceOptimizer.runCommandOutput("cmd appops set $pkg 10008 allow") != null
        val stillRestricted =
            if (fixed) {
                DeviceOptimizer.runCommandOutput("cmd appops get $pkg 10008")?.let { AUTOSTART_DENIED.containsMatchIn(it) } != false
            } else {
                true
            }
        return Finding(
            KEY_XIAOMI_AUTOSTART,
            R.string.vendor_battery_restriction_xiaomi_autostart,
            undone = fixed && !stillRestricted,
            xiaomiAutostartScreens(),
        )
    }

    private fun checkXiaomiNoRestrict(
        context: Context,
        pkg: String,
    ): Finding? {
        val read = DeviceOptimizer.runCommandOutput("settings get system MILLET_NO_RESTRICT_APP")?.trim() ?: return null
        // `settings get` prints "null" for a setting that does not exist (older MIUI): no signal.
        if (read.isEmpty() || read == "null") return null
        val listed = read.split(',').any { it.trim() == pkg }
        if (listed) return null
        // Report only: the issue says never to write this setting.
        return Finding(
            KEY_XIAOMI_NO_RESTRICT,
            R.string.vendor_battery_restriction_xiaomi_no_restrict,
            undone = false,
            xiaomiNoRestrictScreens(context),
        )
    }

    // --- Report ---

    private fun report(
        context: Context,
        findings: List<Finding>,
    ) {
        val reported = findings.filterNot { it.undone }
        val reportSet = reported.map { it.key }.toSet()
        val lastReported = lastReported()
        for (finding in findings) {
            // An undo is an action and is always logged; a restriction that stays is logged when
            // what is restricted changes, not again at every start.
            if (!finding.undone && (reportSet == lastReported || reportSet == lastLogged)) continue
            val action =
                if (finding.undone) {
                    when (finding.key) {
                        KEY_BACKGROUND_ACTIVITY -> LOG_ACTION_UNDID_BACKGROUND_ACTIVITY
                        KEY_STANDBY_BUCKET -> LOG_ACTION_UNDID_STANDBY_BUCKET
                        KEY_SAMSUNG_DEEP_SLEEP -> LOG_ACTION_UNDID_SAMSUNG_DEEP_SLEEP
                        KEY_XIAOMI_AUTOSTART -> LOG_ACTION_UNDID_XIAOMI_AUTOSTART
                        else -> continue
                    }
                } else {
                    when (finding.key) {
                        KEY_BACKGROUND_ACTIVITY -> LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY
                        KEY_STANDBY_BUCKET -> LOG_ACTION_REPORTED_STANDBY_BUCKET
                        KEY_SAMSUNG_DEEP_SLEEP -> LOG_ACTION_REPORTED_SAMSUNG_DEEP_SLEEP
                        KEY_XIAOMI_AUTOSTART -> LOG_ACTION_REPORTED_XIAOMI_AUTOSTART
                        KEY_XIAOMI_NO_RESTRICT -> LOG_ACTION_REPORTED_XIAOMI_NO_RESTRICT
                        else -> continue
                    }
                }
            ManagerActivityLog.log(context, action)
        }
        lastLogged = reportSet

        if (reportSet.isEmpty()) {
            // Nothing to report now; forget what was reported so a later re-appearance posts again.
            if (lastReported.isNotEmpty()) persistReported(emptySet())
            return
        }
        if (reportSet == lastReported) return // Already told the user; nothing changed.

        if (EnvironmentUtils.isTelevision() || !canPostNotification(context)) {
            // Log only; leave the record, so a later start that can post does.
            return
        }
        postNotification(context, reported)
        persistReported(reportSet)
    }

    private fun postNotification(
        context: Context,
        reported: List<Finding>,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.battery_exemption_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        val contentIntent = contentIntent(context, reported)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val names = reported.joinToString(", ") { context.getString(it.nameRes) }
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.vendor_battery_notification_title))
                .setContentText(context.getString(R.string.vendor_battery_notification_text, names))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    /** The notification's tap target: the first finding's screen that resolves, else the app's own page. */
    private fun contentIntent(
        context: Context,
        reported: List<Finding>,
    ): Intent {
        for (finding in reported) {
            for (screen in finding.vendorScreens) {
                if (resolves(context, screen)) return screen
            }
        }
        return appDetailsIntent(context)
    }

    private fun resolves(
        context: Context,
        intent: Intent,
    ): Boolean =
        resolveActivityForTesting?.invoke(context, intent)
            ?: context.packageManager.resolveActivity(intent, 0)?.activityInfo.let { info ->
                // resolveActivity also finds activities other apps may not start; a tap on those
                // would do nothing, so they fall back to the app's settings page.
                info != null &&
                    info.exported &&
                    (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
            }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:" + context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun applicationEnabledSetting(
        context: Context,
        pkg: String,
    ): Int =
        applicationEnabledSettingForTesting?.invoke(pkg)
            ?: context.packageManager.getApplicationEnabledSetting(pkg)

    private fun canPostNotification(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    // --- Persisted "last reported" record ---

    private fun lastReported(): Set<String> {
        val prefs = ShizukuSettings.getPreferences() ?: return emptySet()
        val value = prefs.getString(ShizukuSettings.Keys.KEY_WATCHDOG_VENDOR_BATTERY_REPORTED, null) ?: return emptySet()
        return value.split(',').filter { it.isNotEmpty() }.toSet()
    }

    private fun persistReported(keys: Set<String>) {
        ShizukuSettings
            .getPreferences()
            ?.edit()
            ?.putString(ShizukuSettings.Keys.KEY_WATCHDOG_VENDOR_BATTERY_REPORTED, keys.sorted().joinToString(","))
            ?.apply()
    }

    // --- Vendor detection and screens ---

    private fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    private fun isXiaomi(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return manufacturer in XIAOMI_NAMES || brand in XIAOMI_NAMES
    }

    private val XIAOMI_NAMES = setOf("xiaomi", "redmi", "poco")

    private val AUTOSTART_DENIED = Regex(":\\s*(ignore|deny|errored)\\b")

    private fun samsungNeverSleepingScreens(): List<Intent> =
        listOf(
            Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").apply {
                setPackage("com.samsung.android.lool")
                putExtra("activity_type", 2)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent().apply {
                setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )

    private fun xiaomiAutostartScreens(): List<Intent> =
        listOf(
            Intent().apply {
                setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )

    private fun xiaomiNoRestrictScreens(context: Context): List<Intent> =
        listOf(
            Intent().apply {
                setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.getString(R.string.app_name))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
}
