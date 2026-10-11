package rikka.shizuku.server

import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.compat.InstalledPackagesCompat
import af.shizuku.common.util.TrustedSigners
import af.shizuku.common.util.OsUtils as CommonOsUtils
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PermissionInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.UserInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
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
import rikka.shizuku.server.ktx.workerHandler
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionDefinerConsentTest {

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
        every { PermissionDefiners.getPermissionInfo(any()) } answers {
            PermissionInfo().apply { packageName = definerPackage }
        }
        every { Android17Compat.getPackageInfo(MANAGER_PACKAGE, any(), 0) } returns
            PackageInfo().apply {
                packageName = MANAGER_PACKAGE
                applicationInfo = ApplicationInfo().apply {
                    uid = MANAGER_UID
                    flags = ApplicationInfo.FLAG_INSTALLED
                }
                signatures = signedBy(OURS)
            }
        every { Android17Compat.getApplicationInfo(MANAGER_PACKAGE, any(), 0) } returns
            ApplicationInfo().apply { uid = MANAGER_UID }
        every { Android17Compat.getPackageInfo(OTHER_PACKAGE, any(), 0) } returns
            PackageInfo().apply {
                packageName = OTHER_PACKAGE
                applicationInfo = ApplicationInfo().apply {
                    uid = 10_999
                    flags = ApplicationInfo.FLAG_INSTALLED
                }
            }
        every { Android17Compat.checkPermission(any<String>(), any<Int>()) } returns PackageManager.PERMISSION_DENIED

        configManager = mockk(relaxed = true)
        every { configManager.find(any()) } returns null
        every { configManager.trustOf(any()) } returns ShizukuConfigManager.Trust.NOT_TRUSTED
        every { configManager.trustOf(any(), any()) } returns ShizukuConfigManager.Trust.NOT_TRUSTED
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
        ServerConstants.MANAGER_APPLICATION_ID = MANAGER_PACKAGE
    }

    private var definerPackage = OTHER_PACKAGE

    @Test
    fun `a held permission another package defines still asks for consent`() {
        for (permission in permissions) {
            every { ActivityManagerApis.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
            every { ActivityManagerApis.checkPermission(permission, any(), APP_UID) } returns PackageManager.PERMISSION_GRANTED
            for (serverUid in serverUids) {
                val service = newService(serverUid)
                val app = attach(service)
                val record = service.clientManager.findClient(APP_UID, APP_PID)!!
                assertFalse(record.allowed, "$permission server uid $serverUid")
                startedActivities.clear()
                service.requestPermission(REQUEST_CODE)
                assertEquals(1, startedActivities.size)
                assertFalse(record.allowed)
                verify(exactly = 0) { app.dispatchRequestPermissionResult(any(), any()) }
            }
        }
    }

    @Test
    fun `held permission defined by the manager allows attach`() {
        definerPackage = MANAGER_PACKAGE
        for (permission in permissions) {
            every { ActivityManagerApis.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
            every { ActivityManagerApis.checkPermission(permission, any(), APP_UID) } returns PackageManager.PERMISSION_GRANTED
            for (serverUid in serverUids) {
                val service = newService(serverUid)
                attach(service)
                assertTrue(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed)
                startedActivities.clear()
                service.requestPermission(REQUEST_CODE)
                assertTrue(startedActivities.isEmpty())
            }
        }
    }

    @Test
    fun `a failed definer lookup leaves held permissions awaiting consent`() {
        every { PermissionDefiners.getPermissionInfo(any()) } throws IllegalStateException("lookup failed")
        every { ActivityManagerApis.checkPermission(ServerConstants.PERMISSION_LEGACY, any(), APP_UID) } returns PackageManager.PERMISSION_GRANTED
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            attach(service)
            assertFalse(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed)
            startedActivities.clear()
            service.requestPermission(REQUEST_CODE)
            assertEquals(1, startedActivities.size)
        }
    }

    @Test
    fun `uid permission fallback checks the definer too`() {
        every { Android17Compat.getPackageInfo(APP_PACKAGE, any(), any()) } returns
            PackageInfo().apply { requestedPermissions = heldPermissionNames }
        every { Android17Compat.checkPermission(ServerConstants.PERMISSION_LEGACY, APP_UID) } returns PackageManager.PERMISSION_GRANTED
        for (serverUid in serverUids) {
            for (owner in listOf(OTHER_PACKAGE, MANAGER_PACKAGE)) {
                definerPackage = owner
                val service = newService(serverUid)
                attach(service)
                assertEquals(owner == MANAGER_PACKAGE, service.clientManager.findClient(APP_UID, APP_PID)!!.allowed)
            }
        }
    }

    @Test
    fun `migration grants only permissions defined by a trusted package`() {
        every { configManager.getAllowedUids() } returns listOf(APP_UID)
        every { Android17Compat.getPackageInfo(APP_PACKAGE, any(), any()) } returns
            PackageInfo().apply { requestedPermissions = arrayOf(ServerConstants.PERMISSION_LEGACY) }
        val migrate = ShizukuService::class.java.getDeclaredMethod("migratePermissionGrants")
        migrate.isAccessible = true
        for (serverUid in serverUids) {
            migrate.invoke(newService(serverUid))
        }
        verify(exactly = 0) { Android17Compat.grantRuntimePermission(any(), any(), any()) }
        definerPackage = MANAGER_PACKAGE
        for (serverUid in serverUids) {
            migrate.invoke(newService(serverUid))
        }
        verify(exactly = 2) { Android17Compat.grantRuntimePermission(APP_PACKAGE, ServerConstants.PERMISSION_LEGACY, 0) }
    }

    @Test
    fun `start-up does not import holders of a permission another package defines`() {
        for (permission in permissions) {
            for (serverUid in serverUids) {
                val imported = startupImport(serverUid, permission)
                assertTrue(imported.packages.isEmpty(), "$permission server uid $serverUid")
            }
        }
    }

    @Test
    fun `startup still imports holders of a manager defined permission`() {
        definerPackage = MANAGER_PACKAGE
        for (permission in permissions) {
            for (serverUid in serverUids) {
                val imported = startupImport(serverUid, permission)
                val entry = imported.packages.single()
                assertEquals(APP_UID, entry.uid)
                assertTrue(entry.isAllowed, "$permission server uid $serverUid")
                assertEquals(listOf(APP_PACKAGE), entry.packages)
            }
        }
    }

    @Test
    fun `startup does not import held permissions when definer lookup fails`() {
        every { PermissionDefiners.getPermissionInfo(any()) } throws IllegalStateException("lookup failed")
        for (permission in permissions) {
            for (serverUid in serverUids) {
                val imported = startupImport(serverUid, permission)
                assertTrue(imported.packages.isEmpty(), "$permission server uid $serverUid")
            }
        }
    }

    @Test
    fun `the other flavour's package signed by another key does not become the manager`() {
        installOtherFlavour(THEIRS)
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } returns 60_000L
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            assertFalse(service.checkCallerManagerPermission("exit", OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID), "server uid $serverUid")
        }
    }

    @Test
    fun `the other flavour's package signed like the manager or by an allowlisted key is the manager`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } returns 60_000L
        installOtherFlavour(OURS)
        assertTrue(newService(SHELL_UID).checkCallerManagerPermission("exit", OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID))

        installOtherFlavour(THEIRS)
        mockkStatic(TrustedSigners::class)
        every { TrustedSigners.isSignedByTrustedKey(any()) } answers {
            firstArg<PackageInfo?>()?.packageName == OTHER_FLAVOUR_PACKAGE
        }
        assertTrue(newService(SHELL_UID).checkCallerManagerPermission("exit", OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID))
    }

    @Test
    fun `the other flavour's package signed by another key gets no manager permissions on attach`() {
        installOtherFlavour(THEIRS)
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } returns 60_000L
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            attach(service, OTHER_FLAVOUR_PACKAGE, OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID)
            // The manager attaching is not enough either: the other package is granted nothing.
            attach(service, MANAGER_PACKAGE, MANAGER_UID, MANAGER_PID)
        }
        verify(exactly = 0) { Android17Compat.grantRuntimePermission(OTHER_FLAVOUR_PACKAGE, any(), any()) }
        verify { Android17Compat.grantRuntimePermission(MANAGER_PACKAGE, android.Manifest.permission.WRITE_SECURE_SETTINGS, any()) }
    }

    @Test
    fun `a refresh that cannot find the manager does not make the other flavour's package the manager`() {
        installOtherFlavour(THEIRS)
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } returns 60_000L
        val failures = listOf<() -> ApplicationInfo?>({ null }, { throw IllegalStateException("package manager not answering") })
        for (failure in failures) {
            every { Android17Compat.getApplicationInfo(MANAGER_PACKAGE, any(), any()) } answers { failure() }
            val service = newService(SHELL_UID)
            assertFalse(service.checkCallerManagerPermission("exit", OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID))
            assertEquals(MANAGER_PACKAGE, ServerConstants.MANAGER_APPLICATION_ID, "the flavour chosen at start-up stays")
            attach(service, OTHER_FLAVOUR_PACKAGE, OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID)
            attach(service, MANAGER_PACKAGE, MANAGER_UID, MANAGER_PID)
            assertTrue(service.checkCallerManagerPermission("exit", MANAGER_UID, MANAGER_PID), "the manager's uid stays")
        }
        verify(exactly = 0) { Android17Compat.grantRuntimePermission(OTHER_FLAVOUR_PACKAGE, any(), any()) }
    }

    @Test
    fun `when the Drop-In APK started the server a package under the Plus name is not the manager`() {
        // Drop-In started the server and is signed by the manager's key; a package installed
        // under the Plus name is signed by another key.
        every { Android17Compat.getApplicationInfo(OTHER_FLAVOUR_PACKAGE, any(), any()) } returns
            ApplicationInfo().apply { uid = OTHER_FLAVOUR_UID; sourceDir = DROPIN_APK }
        every { Android17Compat.getPackageInfo(OTHER_FLAVOUR_PACKAGE, any(), 0) } returns
            PackageInfo().apply {
                packageName = OTHER_FLAVOUR_PACKAGE
                applicationInfo = ApplicationInfo().apply {
                    uid = OTHER_FLAVOUR_UID
                    flags = ApplicationInfo.FLAG_INSTALLED
                }
                signatures = signedBy(OURS)
            }
        every { Android17Compat.getApplicationInfo(MANAGER_PACKAGE, any(), any()) } returns
            ApplicationInfo().apply { uid = MANAGER_UID; sourceDir = "/data/app/other/base.apk" }
        every { Android17Compat.getPackageInfo(MANAGER_PACKAGE, any(), 0) } returns
            PackageInfo().apply {
                packageName = MANAGER_PACKAGE
                applicationInfo = ApplicationInfo().apply {
                    uid = MANAGER_UID
                    flags = ApplicationInfo.FLAG_INSTALLED
                }
                signatures = signedBy(THEIRS)
            }
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } returns 60_000L

        assertTrue(ManagerFlavours.chooseAtStartUp(DROPIN_APK))
        assertEquals(OTHER_FLAVOUR_PACKAGE, ServerConstants.MANAGER_APPLICATION_ID)
        for (serverUid in serverUids) {
            val service = newService(serverUid)
            // As the constructor resolves them after the choice.
            setField(ShizukuService::class.java, service, "managerAppId", OTHER_FLAVOUR_UID)
            setField(ShizukuService::class.java, service, "secondaryManagerAppId", ManagerFlavours.secondaryManagerUid())
            assertFalse(service.checkCallerManagerPermission("exit", MANAGER_UID, MANAGER_PID), "server uid $serverUid")
            attach(service, MANAGER_PACKAGE, MANAGER_UID, MANAGER_PID)
            attach(service, OTHER_FLAVOUR_PACKAGE, OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID)
            assertTrue(service.checkCallerManagerPermission("exit", OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID))
        }
        verify(exactly = 0) { Android17Compat.grantRuntimePermission(MANAGER_PACKAGE, any(), any()) }
        verify { Android17Compat.grantRuntimePermission(OTHER_FLAVOUR_PACKAGE, android.Manifest.permission.WRITE_SECURE_SETTINGS, any()) }
    }

    @Test
    fun `the other flavour's package replaced by one signed by another key is granted nothing and is not the manager`() {
        // Resolved as the manager's own build at start-up, then replaced under the same name and
        // app id by a package signed by another key while the server runs.
        installOtherFlavour(THEIRS)
        for (serverUid in serverUids) {
            val service = newService(serverUid, secondaryManagerUid = OTHER_FLAVOUR_UID)
            attach(service, MANAGER_PACKAGE, MANAGER_UID, MANAGER_PID)
            assertFalse(service.checkCallerManagerPermission("exit", OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID), "server uid $serverUid")
        }
        verify(exactly = 0) { Android17Compat.grantRuntimePermission(OTHER_FLAVOUR_PACKAGE, any(), any()) }
        verify { Android17Compat.grantRuntimePermission(MANAGER_PACKAGE, android.Manifest.permission.WRITE_SECURE_SETTINGS, any()) }
    }

    @Test
    fun `a refresh that cannot read the other flavour's signers keeps it as the manager`() {
        installOtherFlavour(OURS)
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } returns 60_000L
        val service = newService(SHELL_UID, secondaryManagerUid = OTHER_FLAVOUR_UID)
        every { Android17Compat.getPackageInfo(OTHER_FLAVOUR_PACKAGE, any(), 0) } throws IllegalStateException("package manager not answering")
        // A failed manager check from another uid runs the refresh.
        assertFalse(service.checkCallerManagerPermission("exit", APP_UID, APP_PID))
        assertTrue(service.checkCallerManagerPermission("exit", OTHER_FLAVOUR_UID, OTHER_FLAVOUR_PID))
    }

    @Test
    fun `a definer package read that failed is asked again on the next call`() {
        every { ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, any(), APP_UID) } returns PackageManager.PERMISSION_GRANTED
        callingUid = APP_UID
        callingPid = APP_PID
        // The definer's own package, and the manager's package read to compare signers with.
        for (failing in listOf(MANAGER_PACKAGE, OTHER_FLAVOUR_PACKAGE)) {
            installOtherFlavour(OURS)
            definerPackage = if (failing == MANAGER_PACKAGE) OTHER_FLAVOUR_PACKAGE else failing
            val readable = Android17Compat.getPackageInfo(failing, 0L, 0)
            if (failing == MANAGER_PACKAGE) {
                every { Android17Compat.getPackageInfo(failing, any(), 0) } returns null
            } else {
                every { Android17Compat.getPackageInfo(failing, any(), 0) } throws IllegalStateException("package manager not answering")
            }
            val service = newService(SHELL_UID)
            assertFalse(service.checkCallerPermission("newProcess", APP_UID, APP_PID, null), failing)
            every { Android17Compat.getPackageInfo(failing, any(), 0) } returns readable
            assertTrue(service.checkCallerPermission("newProcess", APP_UID, APP_PID, null), failing)
        }
    }

    @Test
    fun `a definer package missing from user 0 is denied for a second before it is read again`() {
        // Permission definitions are global, but the definer is read in user 0, where this one
        // is not installed.
        every { ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, any(), APP_UID) } returns PackageManager.PERMISSION_GRANTED
        every { Android17Compat.getPackageInfo(OTHER_PACKAGE, any(), 0) } returns null
        mockkStatic(SystemClock::class)
        var now = 100_000L
        every { SystemClock.uptimeMillis() } answers { now }
        callingUid = APP_UID
        callingPid = APP_PID
        val service = newService(SHELL_UID)
        repeat(5) { assertFalse(service.checkCallerPermission("newProcess", APP_UID, APP_PID, null)) }
        verify(exactly = 1) { PermissionDefiners.getPermissionInfo(ServerConstants.PERMISSION) }

        now += 1_000L
        assertFalse(service.checkCallerPermission("newProcess", APP_UID, APP_PID, null))
        verify(exactly = 2) { PermissionDefiners.getPermissionInfo(ServerConstants.PERMISSION) }
    }

    @Test
    fun `the other flavour is granted manager permissions only under the uid its check found`() {
        installOtherFlavour(OURS)
        for (installedUid in listOf(OTHER_FLAVOUR_UID + 1, OTHER_FLAVOUR_UID)) {
            // The package under the other flavour's name as the grant reads it, after the check.
            every { Android17Compat.getApplicationInfo(OTHER_FLAVOUR_PACKAGE, any(), 0) } returns
                ApplicationInfo().apply { uid = installedUid }
            val service = newService(SHELL_UID, secondaryManagerUid = OTHER_FLAVOUR_UID)
            attach(service, MANAGER_PACKAGE, MANAGER_UID, MANAGER_PID)
            if (installedUid != OTHER_FLAVOUR_UID) {
                verify(exactly = 0) { Android17Compat.grantRuntimePermission(OTHER_FLAVOUR_PACKAGE, any(), any()) }
            }
        }
        verify { Android17Compat.grantRuntimePermission(OTHER_FLAVOUR_PACKAGE, android.Manifest.permission.WRITE_SECURE_SETTINGS, any()) }
    }

    @Test
    fun `a permission the other flavour's package defines counts only when the manager's key signs it`() {
        every { Android17Compat.getPackageInfo(APP_PACKAGE, any(), any()) } returns
            PackageInfo().apply { requestedPermissions = heldPermissionNames }
        definerPackage = OTHER_FLAVOUR_PACKAGE
        for (cert in listOf(THEIRS, OURS)) {
            installOtherFlavour(cert)
            val expected = cert == OURS
            for (permission in permissions) {
                for (serverUid in serverUids) {
                    // The caller permission check at attach.
                    every { ActivityManagerApis.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
                    every { ActivityManagerApis.checkPermission(permission, any(), APP_UID) } returns PackageManager.PERMISSION_GRANTED
                    every { Android17Compat.checkPermission(any<String>(), any<Int>()) } returns PackageManager.PERMISSION_DENIED
                    var service = newService(serverUid, secondaryManagerUid = OTHER_FLAVOUR_UID)
                    attach(service)
                    assertEquals(expected, service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "attach $cert $permission $serverUid")

                    // The held-permission fallback of the stored-grant lookup.
                    every { ActivityManagerApis.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
                    every { Android17Compat.checkPermission(permission, APP_UID) } returns PackageManager.PERMISSION_GRANTED
                    service = newService(serverUid, secondaryManagerUid = OTHER_FLAVOUR_UID)
                    attach(service)
                    assertEquals(expected, service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "fallback $cert $permission $serverUid")

                    // The start-up import.
                    val imported = startupImport(serverUid, permission)
                    assertEquals(expected, imported.packages.isNotEmpty(), "import $cert $permission $serverUid")
                }
            }
        }
    }

    @Test
    fun `the definer of a held permission is looked up once within a few seconds`() {
        definerPackage = MANAGER_PACKAGE
        every { ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, any(), APP_UID) } returns PackageManager.PERMISSION_GRANTED
        mockkStatic(SystemClock::class)
        var now = 100_000L
        every { SystemClock.uptimeMillis() } answers { now }
        callingUid = APP_UID
        callingPid = APP_PID
        val service = newService(SHELL_UID)
        repeat(5) { assertTrue(service.checkCallerPermission("newProcess", APP_UID, APP_PID, null)) }
        verify(exactly = 1) { PermissionDefiners.getPermissionInfo(ServerConstants.PERMISSION) }

        now += 60_000L
        assertTrue(service.checkCallerPermission("newProcess", APP_UID, APP_PID, null))
        verify(exactly = 2) { PermissionDefiners.getPermissionInfo(ServerConstants.PERMISSION) }
    }

    @Test
    fun `a definer lookup that failed is asked again on the next call`() {
        every { ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, any(), APP_UID) } returns PackageManager.PERMISSION_GRANTED
        every { PermissionDefiners.getPermissionInfo(any()) } throws IllegalStateException("lookup failed")
        callingUid = APP_UID
        callingPid = APP_PID
        val service = newService(SHELL_UID)
        assertFalse(service.checkCallerPermission("newProcess", APP_UID, APP_PID, null))
        every { PermissionDefiners.getPermissionInfo(any()) } answers {
            PermissionInfo().apply { packageName = MANAGER_PACKAGE }
        }
        assertTrue(service.checkCallerPermission("newProcess", APP_UID, APP_PID, null))
    }

    @Test
    fun `a withheld stored grant leaves the caller check to the runtime permission the OS holds`() {
        // A grant start-up could not check is returned without its permission flags. It does not
        // count, and neither does the held-permission fallback behind it; the OS runtime
        // permission is checked on its own, and the OS clears it when the package is uninstalled.
        definerPackage = MANAGER_PACKAGE
        every { configManager.find(APP_UID) } returns
            ShizukuConfig.PackageEntry(APP_UID, 0).apply { packages.add(APP_PACKAGE) }
        every { Android17Compat.getPackageInfo(APP_PACKAGE, any(), any()) } returns
            PackageInfo().apply { requestedPermissions = heldPermissionNames }
        every { Android17Compat.checkPermission(ServerConstants.PERMISSION, APP_UID) } returns PackageManager.PERMISSION_GRANTED
        for (serverUid in serverUids) {
            var service = newService(serverUid)
            attach(service)
            assertFalse(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "withheld, no OS grant: $serverUid")

            every { ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, any(), APP_UID) } returns PackageManager.PERMISSION_GRANTED
            service = newService(serverUid)
            attach(service)
            assertTrue(service.clientManager.findClient(APP_UID, APP_PID)!!.allowed, "withheld, OS grant: $serverUid")
            every { ActivityManagerApis.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        }
    }

    private fun installOtherFlavour(cert: Byte) {
        every { Android17Compat.getApplicationInfo(OTHER_FLAVOUR_PACKAGE, any(), 0) } returns
            ApplicationInfo().apply { uid = OTHER_FLAVOUR_UID }
        every { Android17Compat.getPackageInfo(OTHER_FLAVOUR_PACKAGE, any(), 0) } returns
            PackageInfo().apply {
                packageName = OTHER_FLAVOUR_PACKAGE
                applicationInfo = ApplicationInfo().apply {
                    uid = OTHER_FLAVOUR_UID
                    flags = ApplicationInfo.FLAG_INSTALLED
                }
                signatures = signedBy(cert)
            }
        every { PackageManagerApis.getPackagesForUidNoThrow(OTHER_FLAVOUR_UID) } returns listOf(OTHER_FLAVOUR_PACKAGE)
    }

    private fun signedBy(cert: Byte): Array<Signature> {
        val signature = mockk<Signature>()
        every { signature.toByteArray() } returns byteArrayOf(cert)
        return arrayOf(signature)
    }

    private fun startupImport(serverUid: Int, permission: String): ShizukuConfig {
        val imported = ShizukuConfig()
        mockkStatic(ShizukuConfigManager::class, InstalledPackagesCompat::class)
        every { ShizukuConfigManager.load() } returns imported
        every { UserManagerApis.getUserIdsNoThrow() } returns listOf(0)
        every { InstalledPackagesCompat.getInstalledPackagesNoThrow(any(), 0) } returns listOf(
            PackageInfo().apply {
                packageName = APP_PACKAGE
                applicationInfo = ApplicationInfo().apply { uid = APP_UID }
                requestedPermissions = arrayOf(permission)
            }
        )
        every { Android17Compat.checkPermission(permission, APP_UID) } returns PackageManager.PERMISSION_GRANTED
        every { Android17Compat.getApplicationInfo(MANAGER_PACKAGE, any(), 0) } returns
            ApplicationInfo().apply { uid = MANAGER_UID }
        mockkStatic("rikka.shizuku.server.ktx.HandlerKt")
        every { workerHandler } returns mockk<Handler>(relaxed = true)
        val service = newService(serverUid)
        // Service calls this factory from super(), before the manager fields are initialized.
        setField(ShizukuService::class.java, service, "managerAppId", 0)
        setField(ShizukuService::class.java, service, "secondaryManagerAppId", 0)
        service.onCreateConfigManager()
        return imported
    }

    private fun newService(serverUid: Int, secondaryManagerUid: Int = -1): ShizukuService {
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
        setField(ShizukuService::class.java, service, "secondaryManagerAppId", secondaryManagerUid)
        return service
    }

    private fun attach(
        service: ShizukuService,
        packageName: String = APP_PACKAGE,
        uid: Int = APP_UID,
        pid: Int = APP_PID,
    ): IShizukuApplication {
        val app = mockk<IShizukuApplication>(relaxed = true)
        val args = mockk<Bundle>()
        every { args.getString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME) } returns packageName
        every { args.getInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1) } returns 13
        callingUid = uid
        callingPid = pid
        service.attachApplication(app, args)
        return app
    }

    private fun setField(owner: Class<*>, target: Any, name: String, value: Any) {
        val field = owner.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private companion object {
        val permissions = arrayOf(ServerConstants.PERMISSION, ServerConstants.PERMISSION_LEGACY, ServerConstants.PERMISSION_ORIGINAL)
        val heldPermissionNames = permissions
        const val MANAGER_PACKAGE = "af.shizuku.plus.api"
        const val OTHER_PACKAGE = "com.example.definer"
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val SERVER_PID = 555
        const val APP_UID = 10_123
        const val APP_PID = 4_321
        const val APP_PACKAGE = "com.example.client"
        const val MANAGER_UID = 10_050
        const val MANAGER_PID = 7_777
        const val REQUEST_CODE = 42
        const val OTHER_FLAVOUR_PACKAGE = ServerConstants.DROPIN_APPLICATION_ID
        const val OTHER_FLAVOUR_UID = 10_060
        const val OTHER_FLAVOUR_PID = 8_888
        const val OURS: Byte = 1
        const val THEIRS: Byte = 2
        const val DROPIN_APK = "/data/app/dropin/base.apk"
    }
}
