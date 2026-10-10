package rikka.shizuku.server

import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.compat.InstalledPackagesCompat
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.RemoteException
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.server.ktx.workerHandler
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server start-up checks each persisted grant against the packages and signers now installed
 * under its UID. A read that cannot answer must never leave a grant trusted for the rest of the
 * server's life, and a grant revoked for a new signer must not come back through the start-up
 * import of held API permissions.
 */
class GrantReconcileTest {

    private val stored = ShizukuConfig()
    private var installed: List<PackageInfo> = listOf(app())
    private var now = 0L

    @BeforeEach
    fun setup() {
        mockkStatic(
            ShizukuConfigManager::class, InstalledPackagesCompat::class, UserManagerApis::class,
            PackageManagerApis::class, Android17Compat::class, PermissionDefiners::class, SystemClock::class,
        )
        mockkStatic("rikka.shizuku.server.ktx.HandlerKt")
        every { workerHandler } returns mockk<Handler>(relaxed = true)
        every { ShizukuConfigManager.load() } returns stored
        every { UserManagerApis.getUserIdsNoThrow() } returns listOf(0)
        every { InstalledPackagesCompat.getInstalledPackagesNoThrow(any(), 0) } answers { installed }
        every { PackageManagerApis.getPackagesForUid(APP_UID) } returns arrayOf(APP_PACKAGE)
        every { PackageManagerApis.getPackagesForUidNoThrow(APP_UID) } returns listOf(APP_PACKAGE)
        every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns listOf(OURS)
        every { Android17Compat.checkPermission(any<String>(), any<Int>()) } returns PackageManager.PERMISSION_DENIED
        every { Android17Compat.getPackageInfo(any(), any(), any()) } returns null
        every { Android17Compat.getPackageInfo(APP_PACKAGE, any(), 0) } answers {
            installed.firstOrNull { it.packageName == APP_PACKAGE }
        }
        every { Android17Compat.revokeRuntimePermission(any(), any(), any()) } returns Unit
        every { PermissionDefiners.isTrusted(any(), any()) } returns true
        every { SystemClock.uptimeMillis() } answers { now }
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `a positive signer mismatch removes the grant`() {
        grant()
        every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns listOf(THEIRS)
        val manager = start()
        assertTrue(stored.packages.isEmpty())
        assertNull(manager.find(APP_UID))
    }

    @Test
    fun `the recorded signer keeps the grant`() {
        grant()
        val manager = start()
        assertTrue(allowed(manager))
        assertEquals(listOf(APP_UID), manager.allowedUids)
    }

    @Test
    fun `an entry without recorded signers adopts the live ones`() {
        grant(signers = null)
        val manager = start()
        assertEquals(listOf(OURS), stored.packages.single().signers)
        assertTrue(allowed(manager))
    }

    @Test
    fun `an entry without package names still has its signer checked`() {
        grant(packages = emptyList())
        every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns listOf(THEIRS)
        val manager = start()
        assertTrue(stored.packages.isEmpty())
        assertNull(manager.find(APP_UID))
    }

    @Test
    fun `an empty installed-package read leaves grants unconfirmed rather than trusted`() {
        grant()
        installed = emptyList()
        every { PackageManagerApis.getPackagesForUid(APP_UID) } throws RemoteException()
        val manager = start()
        assertEquals(1, stored.packages.size, "the grant is kept, not wiped")
        assertFalse(allowed(manager))
        assertTrue(manager.allowedUids.isEmpty(), "start-up migration must not refresh its runtime permission")

        every { PackageManagerApis.getPackagesForUid(APP_UID) } returns arrayOf(APP_PACKAGE)
        now += RECHECK_AFTER
        assertTrue(allowed(manager), "confirmed once the packages can be read")
        assertEquals(listOf(APP_UID), manager.allowedUids)
    }

    @Test
    fun `an unreadable package list leaves the grant unconfirmed rather than trusted`() {
        grant()
        installed = listOf(other())
        every { PackageManagerApis.getPackagesForUid(APP_UID) } throws RemoteException()
        val manager = start()
        assertEquals(1, stored.packages.size)
        assertFalse(allowed(manager))

        every { PackageManagerApis.getPackagesForUid(APP_UID) } returns arrayOf(APP_PACKAGE)
        assertFalse(allowed(manager), "rechecks are rate-limited")
        now += RECHECK_AFTER
        assertTrue(allowed(manager))
    }

    @Test
    fun `unreadable signers leave the grant unconfirmed rather than trusted`() {
        grant()
        every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns null
        val manager = start()
        assertEquals(1, stored.packages.size)
        assertFalse(allowed(manager))

        every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns listOf(OURS)
        now += RECHECK_AFTER
        assertTrue(allowed(manager))
    }

    @Test
    fun `a later check that finds another signer removes the grant and its runtime permission`() {
        grant()
        installed = listOf(app(ServerConstants.PERMISSION_ORIGINAL))
        every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns null
        val manager = start()
        assertFalse(allowed(manager))

        every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns listOf(THEIRS)
        every { Android17Compat.checkPermission(ServerConstants.PERMISSION_ORIGINAL, APP_UID) } returns PackageManager.PERMISSION_GRANTED
        now += RECHECK_AFTER
        assertNull(manager.find(APP_UID))
        assertTrue(stored.packages.isEmpty())
        verify { Android17Compat.revokeRuntimePermission(APP_PACKAGE, ServerConstants.PERMISSION_ORIGINAL, 0) }
    }

    @Test
    fun `a grant by the operator confirms an unconfirmed entry`() {
        grant()
        every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns null
        val manager = start()
        assertFalse(allowed(manager))
        manager.update(APP_UID, listOf(APP_PACKAGE), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        assertTrue(allowed(manager))
    }

    @Test
    fun `a uid removed for a new signer is not imported again by the same start`() {
        for (permission in permissions) {
            grant()
            installed = listOf(app(permission))
            every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns listOf(THEIRS)
            every { Android17Compat.checkPermission(permission, APP_UID) } returns PackageManager.PERMISSION_GRANTED
            val manager = start()
            assertTrue(stored.packages.isEmpty(), permission)
            assertNull(manager.find(APP_UID), permission)
        }
    }

    @Test
    fun `a uid removed for a new signer has its held API permission revoked`() {
        for (permission in permissions) {
            grant()
            installed = listOf(app(permission))
            every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns listOf(THEIRS)
            every { Android17Compat.checkPermission(any<String>(), any<Int>()) } returns PackageManager.PERMISSION_DENIED
            every { Android17Compat.checkPermission(permission, APP_UID) } returns PackageManager.PERMISSION_GRANTED
            start()
            verify(exactly = 1) { Android17Compat.revokeRuntimePermission(APP_PACKAGE, permission, 0) }
        }
    }

    @Test
    fun `a failed revoke does not stop start-up or bring the grant back`() {
        grant()
        installed = listOf(app(ServerConstants.PERMISSION_ORIGINAL))
        every { ShizukuConfigManager.currentSigners(APP_UID, any()) } returns listOf(THEIRS)
        every { Android17Compat.checkPermission(ServerConstants.PERMISSION_ORIGINAL, APP_UID) } returns PackageManager.PERMISSION_GRANTED
        every { Android17Compat.revokeRuntimePermission(any(), any(), any()) } throws SecurityException("denied")
        val manager = start()
        assertTrue(stored.packages.isEmpty())
        assertNull(manager.find(APP_UID))
    }

    @Test
    fun `a holder that was never granted is still imported`() {
        installed = listOf(app(ServerConstants.PERMISSION_ORIGINAL))
        every { Android17Compat.checkPermission(ServerConstants.PERMISSION_ORIGINAL, APP_UID) } returns PackageManager.PERMISSION_GRANTED
        val manager = start()
        assertTrue(allowed(manager))
        assertEquals(listOf(OURS), stored.packages.single().signers)
        verify(exactly = 0) { Android17Compat.revokeRuntimePermission(any(), any(), any()) }
    }

    private fun grant(packages: List<String> = listOf(APP_PACKAGE), signers: List<String>? = listOf(OURS)) {
        stored.packages = arrayListOf(
            ShizukuConfig.PackageEntry(APP_UID, ConfigManager.FLAG_ALLOWED).apply {
                this.packages.addAll(packages)
                this.signers = signers
            }
        )
    }

    private fun start() = ShizukuConfigManager { false }

    private fun allowed(manager: ShizukuConfigManager) = manager.find(APP_UID)?.isAllowed == true

    private fun app(vararg requested: String) = PackageInfo().apply {
        packageName = APP_PACKAGE
        applicationInfo = ApplicationInfo().apply {
            uid = APP_UID
            flags = ApplicationInfo.FLAG_INSTALLED
        }
        requestedPermissions = if (requested.isEmpty()) null else arrayOf(*requested)
    }

    private fun other() = PackageInfo().apply {
        packageName = "com.example.other"
        applicationInfo = ApplicationInfo().apply { uid = 10_999 }
    }

    private companion object {
        val permissions = arrayOf(ServerConstants.PERMISSION, ServerConstants.PERMISSION_LEGACY, ServerConstants.PERMISSION_ORIGINAL)
        val OURS = "aa".repeat(32)
        val THEIRS = "bb".repeat(32)
        const val APP_UID = 10_123
        const val APP_PACKAGE = "com.example.client"
        const val RECHECK_AFTER = 1_000L
    }
}
