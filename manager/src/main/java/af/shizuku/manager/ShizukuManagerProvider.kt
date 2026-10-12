package af.shizuku.manager

import af.shizuku.api.BinderContainer
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ManagerActivityLog
import af.shizuku.manager.utils.ShizukuStateMachine
import android.os.Binder
import android.os.Bundle
import androidx.core.os.bundleOf
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider

class ShizukuManagerProvider : ShizukuProvider() {
    companion object {
        private const val EXTRA_BINDER = "af.shizuku.plus.api.intent.extra.BINDER"
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    }

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (extras == null) return null

        if (method == METHOD_SEND_BINDER) {
            // Read the kernel-provided caller identity before any binder handoff or IPC.
            val uid = Binder.getCallingUid()
            // The starter runs the server as root, system or shell (starter.cpp), so those three are accepted.
            if (uid != 0 && uid != 1000 && uid != 2000) {
                context?.let { ManagerActivityLog.log(it, "Service connection ignored: caller uid $uid is not root, system or shell") }
                return null
            }
        }

        return if (method == METHOD_SEND_USER_SERVICE) {
            try {
                extras.classLoader = BinderContainer::class.java.classLoader

                val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
                val binder = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null

                if (!ShizukuStateMachine.isRunning()) {
                    if (ShizukuStateMachine.update() != ShizukuStateMachine.State.RUNNING) {
                        // Cold-start grace period: wait up to 1500ms for binder delivery from server daemon
                        val deadline = System.currentTimeMillis() + 1500L
                        while (System.currentTimeMillis() < deadline) {
                            try {
                                Thread.sleep(100)
                            } catch (_: InterruptedException) {
                                break
                            }
                            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                                break
                            }
                        }
                    }
                }

                if (!ShizukuStateMachine.isRunning()) {
                    LOGGER.w("sendUserService called when not running")
                    return null
                }

                try {
                    Shizuku.attachUserService(binder, bundleOf(USER_SERVICE_ARG_TOKEN to token))
                    val serviceBinder = Shizuku.getBinder() ?: return null
                    val reply = Bundle()
                    reply.putParcelable(EXTRA_BINDER, BinderContainer(serviceBinder))
                    reply
                } catch (e: Throwable) {
                    LOGGER.e(e, "attachUserService $token")
                    null
                }
            } catch (e: Throwable) {
                LOGGER.e(e, "sendUserService")
                null
            }
        } else {
            super.call(method, arg, extras)
        }
    }
}
