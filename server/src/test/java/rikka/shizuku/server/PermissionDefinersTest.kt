package rikka.shizuku.server

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionDefinersTest {
    @Test
    fun `manager definer is trusted after a complete lookup`() {
        assertTrue(PermissionDefiners.isTrustedDefiner("af.shizuku.plus.api", true, true, false))
    }

    @Test
    fun `compat definer requires a trusted signer unless it is a manager`() {
        assertTrue(PermissionDefiners.isTrustedDefiner(ServerConstants.DROPIN_APPLICATION_ID, true, false, true))
        assertFalse(PermissionDefiners.isTrustedDefiner(ServerConstants.DROPIN_APPLICATION_ID, true, false, false))
    }

    @Test
    fun `on the Drop-In flavour the Plus package is the one that needs a trusted signer`() {
        ServerConstants.MANAGER_APPLICATION_ID = ServerConstants.DROPIN_APPLICATION_ID
        try {
            assertTrue(PermissionDefiners.isTrustedDefiner(ServerConstants.PLUS_APPLICATION_ID, true, false, true))
            assertFalse(PermissionDefiners.isTrustedDefiner(ServerConstants.PLUS_APPLICATION_ID, true, false, false))
            assertFalse(PermissionDefiners.isTrustedDefiner(ServerConstants.DROPIN_APPLICATION_ID, true, false, true))
        } finally {
            ServerConstants.MANAGER_APPLICATION_ID = ServerConstants.PLUS_APPLICATION_ID
        }
    }

    @Test
    fun `unrelated definer is not trusted even with an allowlisted signer`() {
        assertFalse(PermissionDefiners.isTrustedDefiner("com.example.definer", true, false, false))
        assertFalse(PermissionDefiners.isTrustedDefiner("com.example.definer", true, false, true))
    }

    @Test
    fun `missing definer is not trusted`() {
        assertFalse(PermissionDefiners.isTrustedDefiner(null, true, true, true))
        assertFalse(PermissionDefiners.isTrustedDefiner("", true, true, true))
    }

    @Test
    fun `incomplete lookup never trusts a definer`() {
        assertFalse(PermissionDefiners.isTrustedDefiner("af.shizuku.plus.api", false, true, true))
    }
}
