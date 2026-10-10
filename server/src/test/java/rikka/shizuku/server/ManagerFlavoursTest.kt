package rikka.shizuku.server

import af.shizuku.common.compat.Android17Compat
import android.content.pm.ApplicationInfo
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server chooses the manager flavour once, at start-up. A package manager that does not
 * answer must never make another package name the manager.
 */
class ManagerFlavoursTest {

    @BeforeEach
    fun setup() {
        mockkStatic(Android17Compat::class)
        every { Android17Compat.getApplicationInfo(any(), any(), any()) } returns null
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
        ServerConstants.MANAGER_APPLICATION_ID = ServerConstants.PLUS_APPLICATION_ID
    }

    @Test
    fun `start-up chooses Plus when its package is found`() {
        installed(ServerConstants.PLUS_APPLICATION_ID, PLUS_APK)
        installed(ServerConstants.DROPIN_APPLICATION_ID, DROPIN_APK)
        assertTrue(ManagerFlavours.chooseAtStartUp(DROPIN_APK))
        assertEquals(ServerConstants.PLUS_APPLICATION_ID, ServerConstants.MANAGER_APPLICATION_ID)
    }

    @Test
    fun `start-up chooses Drop-In when Plus is absent and the Drop-In APK started the server`() {
        installed(ServerConstants.DROPIN_APPLICATION_ID, DROPIN_APK)
        assertTrue(ManagerFlavours.chooseAtStartUp(DROPIN_APK))
        assertEquals(ServerConstants.DROPIN_APPLICATION_ID, ServerConstants.MANAGER_APPLICATION_ID)
    }

    @Test
    fun `start-up does not choose a Drop-In package that did not start the server`() {
        // Started from the Plus APK, whose lookup does not answer: the Drop-In-named package is
        // some other app.
        installed(ServerConstants.DROPIN_APPLICATION_ID, "/data/app/other/base.apk")
        assertFalse(ManagerFlavours.chooseAtStartUp(PLUS_APK))
        assertEquals(ServerConstants.PLUS_APPLICATION_ID, ServerConstants.MANAGER_APPLICATION_ID)
    }

    @Test
    fun `start-up retries a Plus lookup that did not answer`() {
        installed(ServerConstants.DROPIN_APPLICATION_ID, "/data/app/other/base.apk")
        var asked = 0
        every { Android17Compat.getApplicationInfo(ServerConstants.PLUS_APPLICATION_ID, any(), 0) } answers {
            asked++
            if (asked < 3) throw IllegalStateException("package manager not answering")
            ApplicationInfo().apply { sourceDir = PLUS_APK }
        }
        assertTrue(ManagerFlavours.chooseAtStartUp(PLUS_APK))
        assertEquals(ServerConstants.PLUS_APPLICATION_ID, ServerConstants.MANAGER_APPLICATION_ID)
        assertEquals(3, asked)
    }

    @Test
    fun `start-up gives up when no manager is found`() {
        assertFalse(ManagerFlavours.chooseAtStartUp(PLUS_APK))
    }

    private fun installed(packageName: String, apk: String) {
        every { Android17Compat.getApplicationInfo(packageName, any(), 0) } returns
            ApplicationInfo().apply {
                uid = 10_050
                sourceDir = apk
                publicSourceDir = apk
            }
    }

    private companion object {
        const val PLUS_APK = "/data/app/plus/base.apk"
        const val DROPIN_APK = "/data/app/dropin/base.apk"
    }
}
