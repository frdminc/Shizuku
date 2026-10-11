package rikka.shizuku.server;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

import af.shizuku.common.compat.Android17Compat;
import af.shizuku.common.util.TrustedSigners;
import rikka.shizuku.server.util.Logger;

/**
 * The manager ships as two flavours under two package names, and a user can have both installed.
 * The server runs for one of them ({@link ServerConstants#MANAGER_APPLICATION_ID}); the other
 * flavour counts as the manager only while it is signed like the manager: by the manager's own
 * key, or by a key in {@link TrustedSigners}.
 */
final class ManagerFlavours {

    private static final Logger LOGGER = new Logger("ManagerFlavours");

    /** Start-up lookups of both flavours before the server gives up. */
    static final int START_UP_ATTEMPTS = 5;
    static final long START_UP_RETRY_MS = 1_000;

    private ManagerFlavours() {
    }

    /**
     * Chooses, once at start-up, which flavour the server runs for, and sets
     * {@link ServerConstants#MANAGER_APPLICATION_ID} to it. Nothing changes it afterwards; a later
     * lookup that fails leaves the manager unchanged and is tried again later.
     *
     * <p>The manager is the installed flavour whose APK started this server: {@code classPath}, the
     * APK the starter ran (the manager's own sourceDir, passed with --apk, or else the base.apk
     * beside the starter or {@code pm path}), equals its sourceDir. When neither flavour matches,
     * the lookups are retried a few times for a package manager still coming up, then the server
     * exits.
     *
     * @return false when no installed flavour started the server; the server then exits
     */
    static boolean chooseAtStartUp(@Nullable String classPath) {
        if (classPath == null || classPath.isEmpty()) {
            LOGGER.w("no class path; cannot tell which manager started the server");
            return false;
        }
        String[] flavours = {ServerConstants.PLUS_APPLICATION_ID, ServerConstants.DROPIN_APPLICATION_ID};
        for (int attempt = 0; attempt < START_UP_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                SystemClock.sleep(START_UP_RETRY_MS);
            }
            for (String flavour : flavours) {
                ApplicationInfo ai = lookUp(flavour);
                if (ai != null && (classPath.equals(ai.sourceDir) || classPath.equals(ai.publicSourceDir))) {
                    ServerConstants.MANAGER_APPLICATION_ID = flavour;
                    return true;
                }
            }
            LOGGER.w("no installed manager has the APK %s that started the server (attempt %d of %d)",
                    classPath, attempt + 1, START_UP_ATTEMPTS);
        }
        return false;
    }

    @Nullable
    private static ApplicationInfo lookUp(String packageName) {
        try {
            return Android17Compat.getApplicationInfo(packageName, 0, 0);
        } catch (Throwable t) {
            LOGGER.w(t, "cannot look up %s", packageName);
            return null;
        }
    }

    /** The other flavour's package name. */
    static String otherApplicationId() {
        return ServerConstants.DROPIN_APPLICATION_ID.equals(ServerConstants.MANAGER_APPLICATION_ID)
                ? ServerConstants.PLUS_APPLICATION_ID : ServerConstants.DROPIN_APPLICATION_ID;
    }

    /** PackageManager flags that make {@link #signerDigests} answer. */
    static long signerFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    /** {@link Secondary#uid} when the other flavour's package or signers could not be read. */
    static final int UNKNOWN = -2;

    /** What resolving the other flavour's package found. */
    static final class Secondary {
        /** Its uid when it counts as the manager; -1 when absent or signed otherwise; {@link #UNKNOWN}. */
        final int uid;
        /** Its APK, when it counts as the manager. */
        @Nullable
        final String sourceDir;

        Secondary(int uid, @Nullable String sourceDir) {
            this.uid = uid;
            this.sourceDir = sourceDir;
        }
    }

    /** {@link #resolveSecondary()}'s uid. */
    static int secondaryManagerUid() {
        return resolveSecondary().uid;
    }

    /**
     * The other flavour's package in user 0: its uid when it is signed like the manager, -1 when
     * it is not installed or is signed by another key, {@link #UNKNOWN} when its package or the
     * signers to compare could not be read. A package read that answers nothing counts as not
     * installed: the package manager call used here cannot tell the two apart.
     */
    static Secondary resolveSecondary() {
        String other = otherApplicationId();
        PackageInfo pi;
        try {
            pi = Android17Compat.getPackageInfo(other, signerFlags(), 0);
        } catch (Throwable t) {
            LOGGER.w(t, "cannot read %s", other);
            return new Secondary(UNKNOWN, null);
        }
        if (pi == null || pi.applicationInfo == null) {
            return new Secondary(-1, null);
        }
        Boolean alike = signedLikeManager(pi);
        if (alike == null) {
            LOGGER.w("cannot compare the signers of %s with %s", other, ServerConstants.MANAGER_APPLICATION_ID);
            return new Secondary(UNKNOWN, null);
        }
        if (!alike) {
            LOGGER.w("%s is not signed like %s; it is not the manager", other, ServerConstants.MANAGER_APPLICATION_ID);
            return new Secondary(-1, null);
        }
        return new Secondary(pi.applicationInfo.uid, pi.applicationInfo.sourceDir);
    }

    /**
     * Whether {@code pi}, read with {@link #signerFlags()}, is signed by a trusted key or by
     * exactly the manager's signers; null when its signers or the manager's could not be read.
     */
    @Nullable
    static Boolean signedLikeManager(@Nullable PackageInfo pi) {
        if (pi == null) {
            return null;
        }
        try {
            if (TrustedSigners.isSignedByTrustedKey(pi)) {
                return true;
            }
            Set<String> theirs = signerDigests(pi);
            if (theirs == null) {
                return null;
            }
            PackageInfo manager = Android17Compat.getPackageInfo(ServerConstants.MANAGER_APPLICATION_ID, signerFlags(), 0);
            Set<String> ours = signerDigests(manager);
            if (ours == null) {
                return null;
            }
            return theirs.equals(ours);
        } catch (Throwable t) {
            LOGGER.w(t, "cannot compare the signers of %s with the manager's", pi.packageName);
            return null;
        }
    }

    /** SHA-256 digests of the APK's signers, or null when they cannot be read. */
    @Nullable
    static Set<String> signerDigests(@Nullable PackageInfo pi) {
        if (pi == null) {
            return null;
        }
        Signature[] signers;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signers = pi.signingInfo != null ? pi.signingInfo.getApkContentsSigners() : null;
        } else {
            signers = pi.signatures;
        }
        if (signers == null || signers.length == 0) {
            return null;
        }
        Set<String> digests = new LinkedHashSet<>();
        for (Signature signature : signers) {
            byte[] bytes = signature == null ? null : signature.toByteArray();
            String digest = bytes == null ? null : TrustedSigners.sha256Hex(bytes);
            if (digest == null) {
                return null;
            }
            digests.add(digest);
        }
        return digests;
    }
}
