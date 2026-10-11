package rikka.shizuku.server;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

import af.shizuku.common.compat.Android17Compat;
import af.shizuku.common.util.UserHandleCompat;

/**
 * A held permission counts only when a trusted package defines it: the manager, or the other
 * manager flavour's package when it is signed like the manager or by a trusted key (see
 * {@link ManagerFlavours}).
 */
final class PermissionDefiners {

    /**
     * How long an answer is reused. Each lookup costs the package manager two calls, and a client
     * holding one of the permissions decides how often a check runs; a definer that changes is
     * seen within this long.
     */
    static final long ANSWER_TTL_MS = 3_000;

    private static final Map<String, Answer> answers = new ConcurrentHashMap<>();

    private static final class Answer {
        final boolean trusted;
        final long at;

        Answer(boolean trusted, long at) {
            this.trusted = trusted;
            this.at = at;
        }
    }

    private PermissionDefiners() {
    }

    /**
     * @param manager       the definer is the manager package itself
     * @param trustedSigner the definer is signed like the manager or by a trusted key; it counts
     *                      only for the other manager flavour's package
     */
    static boolean isTrustedDefiner(@Nullable String packageName, boolean lookupComplete,
                                    boolean manager, boolean trustedSigner) {
        return lookupComplete && packageName != null && !packageName.isEmpty()
                && (manager || (ManagerFlavours.otherApplicationId().equals(packageName) && trustedSigner));
    }

    static boolean isTrusted(String permission, IntPredicate isManagerAppId) {
        long now = SystemClock.uptimeMillis();
        Answer cached = answers.get(permission);
        if (cached != null && now >= cached.at && now - cached.at < ANSWER_TTL_MS) {
            return cached.trusted;
        }
        Boolean trusted = lookUp(permission, isManagerAppId);
        if (trusted == null) {
            // A lookup that failed is not remembered, so the next check asks again.
            return false;
        }
        answers.put(permission, new Answer(trusted, now));
        return trusted;
    }

    @VisibleForTesting
    static void forgetAnswers() {
        answers.clear();
    }

    /** Whether a trusted package defines {@code permission}; null when the lookup failed. */
    @Nullable
    private static Boolean lookUp(String permission, IntPredicate isManagerAppId) {
        long identity = Binder.clearCallingIdentity();
        try {
            PermissionInfo permissionInfo = getPermissionInfo(permission);
            if (permissionInfo == null || permissionInfo.packageName == null) {
                return false;
            }
            String packageName = permissionInfo.packageName;
            // Permission definitions are global; resolve their owner in the manager's user (0).
            PackageInfo pi = Android17Compat.getPackageInfo(packageName, ManagerFlavours.signerFlags(), 0);
            if (pi == null) {
                // The permission has a definer, so a package read that answers nothing failed.
                return null;
            }
            if (pi.applicationInfo == null || !packageName.equals(pi.packageName)
                    || UserHandleCompat.getUserId(pi.applicationInfo.uid) != 0
                    || (pi.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                return false;
            }
            // Only the manager's own package name counts as the manager here; the other flavour's
            // package, even when it is the secondary manager, must be signed like it.
            boolean manager = ServerConstants.MANAGER_APPLICATION_ID.equals(packageName)
                    && isManagerAppId.test(UserHandleCompat.getAppId(pi.applicationInfo.uid));
            boolean trustedSigner = false;
            if (!manager && ManagerFlavours.otherApplicationId().equals(packageName)) {
                Boolean alike = ManagerFlavours.signedLikeManager(pi);
                if (alike == null) {
                    return null;
                }
                trustedSigner = alike;
            }
            return isTrustedDefiner(packageName, true, manager, trustedSigner);
        } catch (Throwable ignored) {
            return null;
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
