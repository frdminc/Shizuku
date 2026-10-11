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

    /**
     * How long a deny is reused when the definer's package reads as nothing in user 0, twice: a
     * definer installed only in another user reads that way every time.
     */
    static final long MISSING_DEFINER_TTL_MS = 1_000;

    private static final Map<String, Answer> answers = new ConcurrentHashMap<>();

    private static final class Answer {
        final boolean trusted;
        final long at;
        final long ttl;

        Answer(boolean trusted, long at, long ttl) {
            this.trusted = trusted;
            this.at = at;
            this.ttl = ttl;
        }
    }

    /** What a lookup found. */
    private enum Outcome {
        TRUSTED,
        NOT_TRUSTED,
        /** The definer's package read as nothing in user 0, twice. */
        DEFINER_MISSING,
        /** The lookup threw, or signers could not be read: not remembered. */
        FAILED
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
        if (cached != null && now >= cached.at && now - cached.at < cached.ttl) {
            return cached.trusted;
        }
        switch (lookUp(permission, isManagerAppId)) {
            case TRUSTED:
                answers.put(permission, new Answer(true, now, ANSWER_TTL_MS));
                return true;
            case NOT_TRUSTED:
                answers.put(permission, new Answer(false, now, ANSWER_TTL_MS));
                return false;
            case DEFINER_MISSING:
                answers.put(permission, new Answer(false, now, MISSING_DEFINER_TTL_MS));
                return false;
            default:
                // A lookup that failed is not remembered, so the next check asks again.
                return false;
        }
    }

    @VisibleForTesting
    static void forgetAnswers() {
        answers.clear();
    }

    /** Whether a trusted package defines {@code permission}. */
    private static Outcome lookUp(String permission, IntPredicate isManagerAppId) {
        long identity = Binder.clearCallingIdentity();
        try {
            PermissionInfo permissionInfo = getPermissionInfo(permission);
            if (permissionInfo == null || permissionInfo.packageName == null) {
                return Outcome.NOT_TRUSTED;
            }
            String packageName = permissionInfo.packageName;
            // Permission definitions are global; resolve their owner in the manager's user (0).
            PackageInfo pi = Android17Compat.getPackageInfo(packageName, ManagerFlavours.signerFlags(), 0);
            if (pi == null) {
                // The permission has a definer, so a read that answers nothing either failed or
                // the definer is installed only in another user: read once more, then deny for a
                // short while.
                pi = Android17Compat.getPackageInfo(packageName, ManagerFlavours.signerFlags(), 0);
                if (pi == null) {
                    return Outcome.DEFINER_MISSING;
                }
            }
            if (pi.applicationInfo == null || !packageName.equals(pi.packageName)
                    || UserHandleCompat.getUserId(pi.applicationInfo.uid) != 0
                    || (pi.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                return Outcome.NOT_TRUSTED;
            }
            // Only the manager's own package name counts as the manager here; the other flavour's
            // package, even when it is the secondary manager, must be signed like it.
            boolean manager = ServerConstants.MANAGER_APPLICATION_ID.equals(packageName)
                    && isManagerAppId.test(UserHandleCompat.getAppId(pi.applicationInfo.uid));
            boolean trustedSigner = false;
            if (!manager && ManagerFlavours.otherApplicationId().equals(packageName)) {
                Boolean alike = ManagerFlavours.signedLikeManager(pi);
                if (alike == null) {
                    return Outcome.FAILED;
                }
                trustedSigner = alike;
            }
            return isTrustedDefiner(packageName, true, manager, trustedSigner) ? Outcome.TRUSTED : Outcome.NOT_TRUSTED;
        } catch (Throwable ignored) {
            return Outcome.FAILED;
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
