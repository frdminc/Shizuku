package rikka.shizuku.server

import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.util.OsUtils as CommonOsUtils
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.os.Binder
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import moe.shizuku.server.IShizukuApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.util.OsUtils
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Root mode asks exactly like ADB mode. ShizukuPlus e3b2f7f2 made attachApplication() mark every
 * client allowed whenever the server ran as uid 0, so in root mode any app that attached got
 * Shizuku access with no dialog. Every scenario here runs the real attachApplication() /
 * requestPermission() / dispatchPermissionConfirmationResult() once with the server as root (uid 0)
 * and once as shell (uid 2000, ADB mode), and the two must come out the same.
 */
class RootModeConsentTest {

    private val serverUids = listOf(ROOT_UID, SHELL_UID)

    private lateinit var configManager: ShizukuConfigManager
    private var callingUid = APP_UID
    private var callingPid = APP_PID
    private val startedActivities = mutableListOf<Intent>()

    @BeforeEach
    fun setup() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } answers { callingUid }
        every { Binder.getCallingPid() } answers { callingPid }

        // Base Service reads rikka.shizuku.server.util.OsUtils, ShizukuService the common copy.
        mockkStatic(OsUtils::class, CommonOsUtils::class)
        every { OsUtils.getPid() } returns SERVER_PID
        every { OsUtils.getSELinuxContext() } returns null
        every { CommonOsUtils.getPid() } returns SERVER_PID
        every { CommonOsUtils.getSELinuxContext() } returns null

        mockkStatic(PackageManagerApis::class)
        every { PackageManagerApis.getPackagesForUidNoThrow(APP_UID) } returns listOf(APP_PACKAGE)
        every { PackageManagerApis.getPackagesForUidNoThrow(neq(APP_UID)) } returns emptyList()

        mockkStatic(ActivityManagerApis::class)
        every { ActivityManagerApis.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        every { ActivityManagerApis.startActivityNoThrow(any(), any(), any()) } answers {
            startedActivities.add(firstArg())
            0
        }
        every { ActivityManagerApis.forceStopPackageNoThrow(any(), any()) } returns Unit

        mockkStatic(Android17Compat::class)
        every { Android17Compat.getApplicationInfo(any(), any(), any()) } returns null
        every { Android17Compat.getPackageInfo(any(), any(), any()) } returns PackageInfo()
        every { Android17Compat.grantRuntimePermission(any(), any(), any()) } returns Unit
        every { Android17Compat.revokeRuntimePermission(any(), any(), any()) } returns Unit

        mockkStatic(UserManagerApis::class)
        every { UserManagerApis.getUserInfo(any()) } returns mockk<UserInfo>(relaxed = true)

        // showPermissionConfirmation() builds the dialog intent with chained setters, which the stub
        // android.jar answers with null.
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any(), any<Int>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any(), any<android.os.Parcelable>()) } answers { self as Intent }

        mockkStatic(PermissionDefiners::class)
        every { PermissionDefiners.getPermissionInfo(any()) } returns android.content.pm.PermissionInfo().apply {
            packageName = ServerConstants.PLUS_APPLICATION_ID
        }
        every { Android17Compat.getPackageInfo(ServerConstants.PLUS_APPLICATION_ID, any(), 0) } returns
            PackageInfo().apply {
                packageName = ServerConstants.PLUS_APPLICATION_ID
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    uid = MANAGER_UID
                    flags = android.content.pm.ApplicationInfo.FLAG_INSTALLED
                }
            }

        configManager = mockk(relaxed = true)
        every { configManager.find(any()) } returns null
        every { configManager.trustOf(any()) } returns ShizukuConfigManager.Trust.NOT_TRUSTED
        every { configManager.trustOf(any(), any()) } returns ShizukuConfigManager.Trust.NOT_TRUSTED
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `an app with no grant is not allowed on attach and its request opens the dialog`() {
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            val app = attach(service)
            val record = service.clientManager.findClient(APP_UID, APP_PID)!!

            // record.allowed is what attach sends back as BIND_APPLICATION_PERMISSION_GRANTED.
            assertFalse(record.allowed, "server uid $serverUid: attach allowed an app with no grant")
            assertFalse(service.checkSelfPermission(), "server uid $serverUid: checkSelfPermission")
            assertFalse(
                service.checkCallerPermission("newProcess", APP_UID, APP_PID, record),
                "server uid $serverUid: checkCallerPermission",
            )

            startedActivities.clear()
            service.requestPermission(REQUEST_CODE)

            assertEquals(1, startedActivities.size, "server uid $serverUid: the request did not open the dialog")
            assertFalse(record.allowed, "server uid $serverUid: the request allowed before the user answered")
            verify(exactly = 0) { app.dispatchRequestPermissionResult(any(), any()) }
        }
    }

    @Test
    fun `allow in the dialog grants and persists the grant`() {
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            val app = attach(service)
            service.requestPermission(REQUEST_CODE)

            answerDialog(service, allowed = true)

            assertTrue(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "server uid $serverUid")
            verify { app.dispatchRequestPermissionResult(REQUEST_CODE, any()) }
            verify {
                configManager.update(APP_UID, listOf(APP_PACKAGE), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
            }
        }
    }

    @Test
    fun `deny in the dialog leaves the app without access and persists the deny`() {
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            val app = attach(service)
            service.requestPermission(REQUEST_CODE)

            answerDialog(service, allowed = false)

            val record = service.clientManager.findClient(APP_UID, APP_PID)!!
            assertFalse(record.allowed, "server uid $serverUid")
            verify { app.dispatchRequestPermissionResult(REQUEST_CODE, any()) }
            verify {
                configManager.update(APP_UID, listOf(APP_PACKAGE), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
            }
            assertFalse(service.checkCallerPermission("newProcess", APP_UID, APP_PID, record), "server uid $serverUid")
        }
    }

    @Test
    fun `an existing grant is allowed on attach without a prompt`() {
        every { configManager.find(APP_UID) } returns ShizukuConfig.PackageEntry(APP_UID, ConfigManager.FLAG_ALLOWED)
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            val app = attach(service)

            assertTrue(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "server uid $serverUid")
            assertTrue(service.checkSelfPermission(), "server uid $serverUid")

            startedActivities.clear()
            service.requestPermission(REQUEST_CODE)
            assertTrue(startedActivities.isEmpty(), "server uid $serverUid: an existing grant was asked again")
            verify { app.dispatchRequestPermissionResult(REQUEST_CODE, any()) }
        }
    }

    @Test
    fun `an existing grant held only as the runtime permission is allowed on attach`() {
        every { ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, any(), APP_UID) } returns
            PackageManager.PERMISSION_GRANTED
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            attach(service)
            assertTrue(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "server uid $serverUid")
        }
    }

    @Test
    fun `a stored deny answers the request without a dialog`() {
        every { configManager.find(APP_UID) } returns ShizukuConfig.PackageEntry(APP_UID, ConfigManager.FLAG_DENIED)
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            val app = attach(service)

            startedActivities.clear()
            service.requestPermission(REQUEST_CODE)

            assertFalse(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "server uid $serverUid")
            assertTrue(startedActivities.isEmpty(), "server uid $serverUid")
            verify { app.dispatchRequestPermissionResult(REQUEST_CODE, any()) }
        }
    }

    @Test
    fun `a trusted signer is allowed on attach the same in both modes`() {
        // ShizukuConfigManager.find() hands back a synthetic FLAG_ALLOWED entry for a trusted signer.
        every { configManager.find(APP_UID) } returns ShizukuConfig.PackageEntry(APP_UID, ConfigManager.FLAG_ALLOWED)
        every { configManager.trustOf(APP_UID) } returns ShizukuConfigManager.Trust.TRUSTED
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            attach(service)
            assertTrue(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "server uid $serverUid")
        }
    }

    @Test
    fun `a trusted signer whose attach-time lookup failed is allowed on request without a dialog in both modes`() {
        every { configManager.trustOf(APP_UID) } returns ShizukuConfigManager.Trust.TRUSTED
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            val app = attach(service)
            assertFalse(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "server uid $serverUid")

            startedActivities.clear()
            service.requestPermission(REQUEST_CODE)

            assertTrue(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "server uid $serverUid")
            assertTrue(startedActivities.isEmpty(), "server uid $serverUid")
            verify { app.dispatchRequestPermissionResult(REQUEST_CODE, any()) }
        }
    }

    @Test
    fun `a caller running as the server's own uid needs no grant in either mode`() {
        // rish run as the server's identity: adb shell rish in ADB mode, su -c rish in root mode.
        // Any other caller, rish inside Termux included, is an ordinary app and takes the dialog.
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            callingUid = serverUid
            callingPid = 999
            assertTrue(service.checkSelfPermission(), "server uid $serverUid")
            callingUid = APP_UID
            callingPid = APP_PID
        }
    }

    private fun newService(serverUid: Int): ShizukuService {
        PermissionDefiners.forgetAnswers()
        every { OsUtils.getUid() } returns serverUid
        every { CommonOsUtils.getUid() } returns serverUid
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        // The constructor starts the real server; the paths under test only need the managers.
        val service = unsafe.allocateInstance(ShizukuService::class.java) as ShizukuService
        val clientManager = ShizukuClientManager(configManager)
        setField(ShizukuService::class.java, service, "clientManager", clientManager)
        setField(ShizukuService::class.java, service, "configManager", configManager)
        setField(Service::class.java, service, "clientManager", clientManager)
        setField(Service::class.java, service, "configManager", configManager)
        setField(ShizukuService::class.java, service, "managerAppId", MANAGER_UID)
        setField(ShizukuService::class.java, service, "secondaryManagerAppId", -1)
        return service
    }

    private fun attach(service: ShizukuService): IShizukuApplication {
        val app = mockk<IShizukuApplication>(relaxed = true)
        val args = mockk<Bundle>()
        every { args.getString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME) } returns APP_PACKAGE
        every { args.getInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1) } returns 13
        callingUid = APP_UID
        callingPid = APP_PID
        service.attachApplication(app, args)
        return app
    }

    private fun answerDialog(service: ShizukuService, allowed: Boolean) {
        val data = mockk<Bundle>()
        every { data.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED) } returns allowed
        every { data.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME) } returns false
        callingUid = MANAGER_UID
        callingPid = MANAGER_PID
        try {
            service.dispatchPermissionConfirmationResult(APP_UID, APP_PID, REQUEST_CODE, data)
        } finally {
            callingUid = APP_UID
            callingPid = APP_PID
        }
    }

    private fun setField(owner: Class<*>, target: Any, name: String, value: Any) {
        val field = owner.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val SERVER_PID = 555
        const val APP_UID = 10_123
        const val APP_PID = 4_321
        const val APP_PACKAGE = "com.example.client"
        const val MANAGER_UID = 10_050
        const val MANAGER_PID = 7_777
        const val REQUEST_CODE = 42
    }
}
