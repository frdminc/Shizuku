package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.service.ShizukuLiveService
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.DeviceOptimizer
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.LiveActivityNotificationManager
import af.shizuku.manager.utils.ShizukuStateMachine
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class BehaviorSettingsFragment :
    BaseSettingsFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private const val TAG = "BehaviorSettingsFragment"
    }

    override fun getTitle(): CharSequence? = getString(R.string.settings_main_nav_startup_behavior_title)

    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var watchdogPreference: TwoStatePreference
    private lateinit var deviceHardeningPreference: TwoStatePreference
    private lateinit var tcpModePreference: TwoStatePreference
    private lateinit var tcpPortPreference: EditTextPreference
    private lateinit var tcpRandomizePreference: Preference
    private lateinit var tcpAutoClosePreference: TwoStatePreference
    private lateinit var networkCategory: CollapsiblePreferenceCategory
    private lateinit var startupCategory: CollapsiblePreferenceCategory

    // True when tcp_mode is logically available on this device (TLS-capable or TV).
    // Used to gate syncTcpPortVisibility() so root-mode hides are not undone.
    private var tcpModeAvailable = false

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isRunning()) {
            tcpModePreference.icon = maybeGetRestartIcon(KEY_TCP_MODE)
            tcpPortPreference.icon = maybeGetRestartIcon(KEY_TCP_PORT)
        }
    }

    private val watchdogRunningListener: (Boolean) -> Unit = {
        syncWatchdogPreference()
    }

    override fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
        val context = requireContext()

        startOnBootPreference = requireNotNull(findPreference(KEY_START_ON_BOOT))
        watchdogPreference = requireNotNull(findPreference(KEY_WATCHDOG))
        deviceHardeningPreference = requireNotNull(findPreference(KEY_DEVICE_HARDENING_ENABLED))
        tcpModePreference = requireNotNull(findPreference(KEY_TCP_MODE))
        tcpPortPreference = requireNotNull(findPreference(KEY_TCP_PORT))
        tcpRandomizePreference = requireNotNull(findPreference("tcp_port_randomize"))
        tcpAutoClosePreference = requireNotNull(findPreference(KEY_TCP_AUTO_CLOSE_PORT))
        networkCategory = requireNotNull(findPreference("category_network_activity"))
        startupCategory = requireNotNull(findPreference("category_startup"))

        startOnBootPreference.apply {
            isChecked = ShizukuSettings.getStartOnBoot(context)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    val doToggle = {
                        maybeToggleSecureSetting(newValue) { secureResult ->
                            if (secureResult) {
                                maybeToggleBatterySensitiveSetting(newValue) { batteryResult ->
                                    if (batteryResult) {
                                        ShizukuSettings.setStartOnBoot(context, newValue)
                                        isChecked = ShizukuSettings.getStartOnBoot(context)
                                    }
                                }
                            }
                        }
                    }
                    // https://r.android.com/2128832
                    if (newValue &&
                        !EnvironmentUtils.isTelevision() &&
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    ) {
                        showDialog(
                            MaterialAlertDialogBuilder(context)
                                .setTitle(android.R.string.dialog_alert_title)
                                .setMessage(R.string.settings_start_on_boot_bug)
                                .setPositiveButton(android.R.string.ok) { _, _ -> doToggle() }
                                .setNegativeButton(android.R.string.cancel) { _, _ -> isChecked = !newValue },
                        )
                    } else {
                        doToggle()
                    }
                }
                false
            }
        }

        watchdogPreference.apply {
            syncWatchdogPreference()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleBatterySensitiveSetting(newValue) { result ->
                        if (result) {
                            ShizukuSettings.setWatchdog(context, newValue)
                            syncWatchdogPreference()
                        }
                    }
                }
                false
            }
        }

        deviceHardeningPreference.apply {
            isChecked = ShizukuSettings.isDeviceHardeningEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    ShizukuSettings.setDeviceHardeningEnabled(newValue)
                    isChecked = newValue
                    if (newValue) {
                        lifecycleScope.launch {
                            val success = DeviceOptimizer.applyFixes(context)
                            if (success) {
                                Toast.makeText(context, R.string.device_hardening_applied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                true
            }
        }

        tcpModePreference.apply {
            if (EnvironmentUtils.isTlsSupported()) {
                tcpModeAvailable = true
                summary = context.getString(R.string.settings_tcp_mode_summary)
                icon = maybeGetRestartIcon(KEY_TCP_MODE)
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue is Boolean) {
                        val applyChange: () -> Unit = {
                            ShizukuSettings.setTcpMode(newValue)
                            isChecked = newValue
                            isEnabled = true
                            summary = context.getString(R.string.settings_tcp_mode_summary)
                            icon = maybeGetRestartIcon(KEY_TCP_MODE)
                            syncTcpPortVisibility()
                        }
                        if (!newValue && !ShizukuStateMachine.isRunning() && needsRestart(KEY_TCP_MODE, newValue)) {
                            promptStopTcp(tcpModePreference) { applyChange() }
                        } else {
                            maybePromptRestart(KEY_TCP_MODE, newValue) { applyChange() }
                        }
                    }
                    false
                }
            } else if (EnvironmentUtils.isTelevision()) {
                tcpModeAvailable = true
                isEnabled = false
                isChecked = true
            } else {
                // Non-TLS device: hide tcp_mode, tcp_port, randomize, and auto-close through the
                // category so expand/collapse cycles don't accidentally restore them.
                networkCategory.setChildAvailable(KEY_TCP_MODE, false)
                networkCategory.setChildAvailable(KEY_TCP_PORT, false)
                networkCategory.setChildAvailable("tcp_port_randomize", false)
                networkCategory.setChildAvailable(KEY_TCP_AUTO_CLOSE_PORT, false)
            }
        }

        tcpPortPreference.apply {
            syncTcpPortVisibility()
            icon = maybeGetRestartIcon(KEY_TCP_PORT)
            setOnBindEditTextListener { editText ->
                editText.hint = context.getString(R.string.settings_tcp_port_hint)
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text.length)
            }
            summaryProvider =
                Preference.SummaryProvider<EditTextPreference> { pref ->
                    val text = pref.text
                    if (text.isNullOrEmpty()) context.getString(R.string.settings_tcp_port_default) else text
                }
            setOnPreferenceChangeListener { _, newValue ->
                val port = (newValue as? String)?.toIntOrNull()
                if (port == null || port in 1..65535) {
                    val applyChange: () -> Unit = {
                        ShizukuSettings.setTcpPort(port)
                        text = port?.toString()
                        icon = maybeGetRestartIcon(KEY_TCP_PORT)
                    }
                    maybePromptRestart(KEY_TCP_PORT, port ?: 5555) { applyChange() }
                } else {
                    SnackbarHelper.show(context, requireView(), context.getString(R.string.snackbar_invalid_port))
                }
                false
            }
        }

        tcpRandomizePreference.setOnPreferenceClickListener {
            val randomPort = (49152..65535).random()
            val applyChange: () -> Unit = {
                ShizukuSettings.setTcpPort(randomPort)
                tcpPortPreference.text = randomPort.toString()
                SnackbarHelper.show(
                    requireContext(),
                    requireView(),
                    requireContext().getString(R.string.snackbar_tcp_port_randomized, randomPort),
                )
            }
            maybePromptRestart(KEY_TCP_PORT, randomPort) { applyChange() }
            true
        }

        findPreference<TwoStatePreference>(KEY_AUTO_DISABLE_USB_DEBUGGING)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleSecureSetting(newValue) { result ->
                        if (result) {
                            isChecked = newValue
                        }
                    }
                }
                false
            }
        }

        findPreference<TwoStatePreference>(KEY_LIVE_ACTIVITY_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
            val enable = newValue as Boolean
            // Live activity is shown only inside the Watchdog's notification (1001). The switch
            // no longer starts ShizukuLiveService: with the Watchdog on it only decides whether
            // 1001 carries the live text. Turning it off stops any legacy live service still
            // running and clears the live text from the Watchdog's notification.
            if (!enable) {
                val ctx = requireContext()
                ctx.stopService(Intent(ctx, ShizukuLiveService::class.java))
                LiveActivityNotificationManager.dismiss(ctx)
            }
            true
        }

        syncModeVisibility()
    }

    /**
     * Show/hide preferences that are only relevant to the current connection mode (#433).
     *
     * ADB-only: tcp_mode, tcp_port, auto_reconnect_mdns -- meaningless in root mode since root
     *   starts the server directly without a TCP/wireless ADB connection.
     *
     * When in root mode the "Network" and "Startup & Recovery" category headers gain an
     * explanatory summary so users understand why those options are absent.
     * Mode is refreshed on every onResume() so it updates if the user switches modes.
     */
    private fun syncModeVisibility() {
        val isRootMode =
            EnvironmentUtils.isRooted() ||
                ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
        val isAdbMode = !isRootMode

        // Route through setChildAvailable so the category's expand/collapse cycle
        // does not restore preferences that should stay hidden in root mode.
        if (isRootMode) {
            networkCategory.setChildAvailable(KEY_TCP_MODE, false)
            networkCategory.setChildAvailable(KEY_TCP_PORT, false)
            networkCategory.setChildAvailable("tcp_port_randomize", false)
            networkCategory.setChildAvailable(KEY_TCP_AUTO_CLOSE_PORT, false)
        } else if (tcpModeAvailable) {
            networkCategory.setChildAvailable(KEY_TCP_MODE, true)
            syncTcpPortVisibility()
        }
        startupCategory.setChildAvailable(KEY_AUTO_RECONNECT_MDNS, isAdbMode)

        // Category summaries as mode indicators
        networkCategory.summary = if (isRootMode) getString(R.string.settings_mode_indicator_root) else null
        startupCategory.summary = if (isRootMode) getString(R.string.settings_mode_indicator_root) else null
    }

    private fun syncTcpPortVisibility() {
        val isRootMode =
            EnvironmentUtils.isRooted() ||
                ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
        if (tcpModeAvailable && !isRootMode) {
            val tcpOn = tcpModePreference.isChecked
            networkCategory.setChildAvailable(KEY_TCP_PORT, tcpOn)
            networkCategory.setChildAvailable("tcp_port_randomize", tcpOn)
            networkCategory.setChildAvailable(KEY_TCP_AUTO_CLOSE_PORT, tcpOn)
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        ShizukuStateMachine.addListener(stateListener)
        WatchdogService.addRunningListener(watchdogRunningListener)
        syncModeVisibility()
        syncWatchdogPreference()
    }

    override fun onPause() {
        WatchdogService.removeRunningListener(watchdogRunningListener)
        ShizukuStateMachine.removeListener(stateListener)
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        if (key == KEY_WATCHDOG) syncWatchdogPreference()
    }

    private fun syncWatchdogPreference() {
        val isOn = watchdogShownOn()
        watchdogPreference.isChecked = isOn
        if (isOn && !WatchdogService.isRunning()) {
            watchdogPreference.setSummary(R.string.settings_watchdog_not_running)
        } else {
            watchdogPreference.setSummary(R.string.settings_watchdog_summary)
        }
    }

    // A new install's Watchdog is on by default from its first server start; until then the
    // switch shows that default, and turning it off stores the choice.
    private fun watchdogShownOn(): Boolean =
        ShizukuSettings.getWatchdog() || ShizukuSettings.isWatchdogDefaultPending()
}
