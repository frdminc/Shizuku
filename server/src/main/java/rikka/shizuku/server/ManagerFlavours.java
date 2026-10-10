package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

import af.shizuku.common.compat.Android17Compat;
import af.shizuku.common.util.TrustedSigners;
import rikka.shizuku.server.util.Logger;

/**
 * The manager ships as two flavours under two package names, and a user can have both installed.
 * The server runs for one of them ({@link ServerConstants#MANAGER_APPLICATION_ID}); the other
 * flavour's package name says nothing about who built it, since any app can be installed under it
 * when the real one is absent. It counts as the manager only when it is signed by the manager's
 * own key, or by a key in {@link TrustedSigners}. Signers that cannot be read count as neither.
 */
final class ManagerFlavours {

    private static final Logger LOGGER = new Logger("ManagerFlavours");

    private ManagerFlavours() {
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

    /**
     * The uid of the other flavour's package in user 0 when it may act as the manager, or -1 when
     * it is not installed, is signed by another key, or its signers cannot be read.
     */
    static int secondaryManagerUid() {
        String other = otherApplicationId();
        PackageInfo pi;
        try {
            pi = Android17Compat.getPackageInfo(other, signerFlags(), 0);
        } catch (Throwable t) {
            LOGGER.w(t, "cannot read %s; not treating it as the manager", other);
            return -1;
        }
        if (pi == null || pi.applicationInfo == null) {
            return -1;
        }
        if (!signedLikeManager(pi)) {
            LOGGER.w("%s is installed but not signed like %s; not treating it as the manager",
                    other, ServerConstants.MANAGER_APPLICATION_ID);
            return -1;
        }
        return pi.applicationInfo.uid;
    }

    /**
     * True when {@code pi}, read with {@link #signerFlags()}, is signed by a trusted key or by
     * exactly the manager's signers. False on any read that fails.
     */
    static boolean signedLikeManager(@Nullable PackageInfo pi) {
        if (pi == null) {
            return false;
        }
        try {
            if (TrustedSigners.isSignedByTrustedKey(pi)) {
                return true;
            }
            Set<String> theirs = signerDigests(pi);
            if (theirs == null) {
                return false;
            }
            PackageInfo manager = Android17Compat.getPackageInfo(ServerConstants.MANAGER_APPLICATION_ID, signerFlags(), 0);
            Set<String> ours = signerDigests(manager);
            return theirs.equals(ours);
        } catch (Throwable t) {
            LOGGER.w(t, "cannot compare the signers of %s with the manager's", pi.packageName);
            return false;
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
