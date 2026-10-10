package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.ServiceManager;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.function.IntPredicate;

import af.shizuku.common.compat.Android17Compat;
import af.shizuku.common.util.TrustedSigners;
import af.shizuku.common.util.UserHandleCompat;

/** A held permission counts only when a trusted package defines it. */
final class PermissionDefiners {

    private PermissionDefiners() {
    }

    static boolean isTrustedDefiner(@Nullable String packageName, boolean lookupComplete,
                                    boolean manager, boolean trustedSigner) {
        return lookupComplete && packageName != null && !packageName.isEmpty()
                && (manager || (ServerConstants.DROPIN_APPLICATION_ID.equals(packageName) && trustedSigner));
    }

    static boolean isTrusted(String permission, IntPredicate isManagerAppId) {
        long identity = Binder.clearCallingIdentity();
        try {
            PermissionInfo permissionInfo = getPermissionInfo(permission);
            if (permissionInfo == null || permissionInfo.packageName == null) {
                return false;
            }
            String packageName = permissionInfo.packageName;
            // Permission definitions are global; resolve their owner in the manager's user (0).
            PackageInfo pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES, 0);
            if (pi == null || pi.applicationInfo == null || !packageName.equals(pi.packageName)
                    || UserHandleCompat.getUserId(pi.applicationInfo.uid) != 0
                    || !ShizukuConfigManager.isInstalledUnderUid(pi, pi.applicationInfo.uid)) {
                return false;
            }
            boolean manager = isManagerAppId.test(UserHandleCompat.getAppId(pi.applicationInfo.uid));
            boolean trustedSigner = !manager && ServerConstants.DROPIN_APPLICATION_ID.equals(packageName)
                    && TrustedSigners.isSignedByTrustedKey(pi);
            return isTrustedDefiner(packageName, true, manager, trustedSigner);
        } catch (Throwable ignored) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Nullable
    static PermissionInfo getPermissionInfo(String permission) throws Exception {
        // The API moved from IPackageManager to IPermissionManager. Fall back only when the
        // service/signature is absent; a failed lookup or a missing definition never grants access.
        String[][] services = {
                {"permissionmgr", "android.permission.IPermissionManager"},
                {"package", "android.content.pm.IPackageManager"}
        };
        for (String[] service : services) {
            IBinder binder = ServiceManager.getService(service[0]);
            if (binder == null) continue;
            Class<?> api;
            Class<?> stub;
            try {
                api = Class.forName(service[1]);
                stub = Class.forName(service[1] + "$Stub");
            } catch (ClassNotFoundException ignored) {
                continue;
            }
            Object pm = stub.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
            if (pm == null) continue;
            Method method;
            try {
                method = api.getMethod("getPermissionInfo", String.class, String.class, int.class);
            } catch (NoSuchMethodException ignored) {
                try {
                    method = api.getMethod("getPermissionInfo", String.class, int.class);
                } catch (NoSuchMethodException absent) {
                    continue;
                }
                return (PermissionInfo) method.invoke(pm, permission, 0);
            }
            return (PermissionInfo) method.invoke(pm, permission, "com.android.shell", 0);
        }
        return null;
    }
}
