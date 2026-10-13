package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.HeadlessLogger
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedTrustManager

/** The connect key lasts for one adbd process; its certificate is renewed on each handshake. */
object AdbServerPin {
    fun of(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }

    fun store() = PreferenceAdbServerPinStore(ShizukuSettings.getPreferences()) { ShizukuSettings.getAdbPinBootCount() }

    private const val PENDING_PORT_KEY = "adb_server_pin_pending_port"
    private const val PENDING_BOOT_KEY = "adb_server_pin_pending_boot"

    var pendingPort: Int
        get() = ShizukuSettings.getPreferences().getInt(PENDING_PORT_KEY, -1)
        set(value) {
            ShizukuSettings
                .getPreferences()
                .edit()
                .putInt(PENDING_PORT_KEY, value)
                .putString(PENDING_BOOT_KEY, ShizukuSettings.getAdbPinBootCount())
                .commit()
        }

    /** The stored hint only if it belongs to this boot; a hint from another boot is ignored. */
    fun pendingPortForCurrentBoot(): Int {
        val prefs = ShizukuSettings.getPreferences()
        val port = prefs.getInt(PENDING_PORT_KEY, -1)
        if (port <= 0) return -1
        val boot = ShizukuSettings.getAdbPinBootCount()
        val recordedBoot = prefs.getString(PENDING_BOOT_KEY, null)
        return if (boot != null && recordedBoot != null && boot == recordedBoot) port else -1
    }

    fun clearPendingPort() {
        ShizukuSettings
            .getPreferences()
            .edit()
            .remove(PENDING_PORT_KEY)
            .remove(PENDING_BOOT_KEY)
            .commit()
    }

    /** Keep unchanged settings and listener-only Wi-Fi toggles from discarding a working pin. */
    fun putSetting(
        context: Context,
        setting: String,
        value: Int,
    ): Boolean {
        val cr = context.contentResolver
        val before = Settings.Global.getInt(cr, setting, -1)
        val usb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, -1)
        if (before != value && (setting == Settings.Global.ADB_ENABLED || usb != 1)) {
            store().clear()
        }
        return Settings.Global.putInt(cr, setting, value)
    }
}

open class AdbServerPinException(
    message: String,
    val seenPin: String? = null,
    cause: Throwable? = null,
) : SSLException(message, cause)

class AdbServerCertChangedException(
    cause: Throwable? = null,
    seenPin: String? = null,
) : AdbServerPinException("adbd TLS certificate changed; confirm the key from the app", seenPin, cause)

class AdbServerPinMissingException(
    seenPin: String? = null,
) : AdbServerPinException("no key recorded for this boot yet; confirm the key from the app", seenPin)

/** Null boot counts retain an existing pin, rather than silently replacing it. */
class PreferenceAdbServerPinStore(
    private val preference: SharedPreferences,
    private val bootId: () -> String? = { null },
) {
    data class Snapshot(
        val pin: String?,
        val boot: String?,
        val generation: Long,
    )

    fun snapshot(hideWhenBootUnknown: Boolean = false): Snapshot =
        synchronized(lock) {
            val boot = bootId()
            val recordedBoot = preference.getString(CONNECT_BOOT_KEY, null)
            val pin = preference.getString(CONNECT_KEY, null)
            val valid =
                when {
                    boot == null -> !hideWhenBootUnknown && pin != null
                    recordedBoot == null -> false
                    boot != recordedBoot -> false
                    else -> true
                }
            Snapshot(
                if (valid) pin else null,
                boot,
                preference.getLong(GENERATION_KEY, 0),
            )
        }

    val connectPin: String? get() = snapshot(hideWhenBootUnknown = true).pin

    /** A parallel connection or a restart must not let an older handshake overwrite the pin. */
    fun commit(
        pin: String,
        observed: Snapshot,
    ) =
        synchronized(lock) {
            val now = snapshot()
            if (now.boot != observed.boot ||
                now.generation != observed.generation ||
                (now.pin != observed.pin && now.pin != pin)
            ) {
                throw AdbServerCertChangedException()
            }
            val edit = preference.edit().putString(CONNECT_KEY, pin)
            if (now.boot == null) edit.remove(CONNECT_BOOT_KEY) else edit.putString(CONNECT_BOOT_KEY, now.boot)
            check(edit.commit()) { "could not store adbd TLS certificate pin" }
        }

    fun clear() =
        synchronized(lock) {
            check(
                preference
                    .edit()
                    .remove(CONNECT_KEY)
                    .remove(CONNECT_BOOT_KEY)
                    .putLong(GENERATION_KEY, preference.getLong(GENERATION_KEY, 0) + 1)
                    .commit(),
            ) {
                "could not clear adbd TLS certificate pin"
            }
        }

    companion object {
        const val CONNECT_KEY = "adb_server_pin_connect"
        const val CONNECT_BOOT_KEY = "adb_server_pin_connect_boot"
        const val GENERATION_KEY = "adb_server_pin_generation"
        private val lock = Any()
    }
}

/** Checks the SPKI inside TLS, before the client certificate and CertificateVerify are sent. */
class PinnedAdbServerTrustManager(
    private val pins: PreferenceAdbServerPinStore,
    private val expectedPin: String? = null,
    private val log: ((String) -> Unit)? = null,
    private val requireRecordedPin: Boolean = false,
) : X509ExtendedTrustManager() {
    private val observed = pins.snapshot()
    private var seen: String? = null
    var mismatchSeen = false
        private set

    /** The SHA-256 pin seen during the handshake, if any. */
    val seenPin: String? get() = seen

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("adbd presented no certificate")
        val pin = AdbServerPin.of(leaf)
        seen = pin
        if ((expectedPin != null && pin != expectedPin) ||
            (observed.pin != null && observed.pin != pin && pin != expectedPin)
        ) {
            mismatchSeen = true
            HeadlessLogger.w("AdbServerPin", "refused changed adbd TLS certificate")
            throw CertificateException("adbd TLS certificate changed")
        }
        if (observed.pin == null && expectedPin == null && requireRecordedPin) {
            throw AdbServerPinMissingException(pin)
        }
    }

    /** Only A_CNXN received over this TLS connection completes first use or an interactive change. */
    fun commitFirstUse() {
        val pin = seen ?: return
        pins.commit(pin, observed)
        if (observed.pin != pin) {
            val message = if (observed.pin == null) "pinned adbd TLS certificate on first connect" else "pinned changed adbd TLS certificate on interactive start"
            HeadlessLogger.i("AdbServerPin", message)
            log?.invoke("$message\n")
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = check(chain)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = check(chain)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = check(chain)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ): Unit = throw CertificateException("client certificates are not accepted")

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ): Unit = throw CertificateException("client certificates are not accepted")

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ): Unit = throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Pairing uses SPAKE2 and a separate system_server certificate, not the connect-port pin. */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
object PairingAdbServerTrustManager : X509ExtendedTrustManager() {
    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {}

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {}

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {}

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {}

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {}

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
