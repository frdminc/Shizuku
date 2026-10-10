package rikka.shizuku.server;

import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import kotlin.collections.ArraysKt;
import af.shizuku.common.compat.Android17Compat;
import af.shizuku.common.compat.InstalledPackagesCompat;
import af.shizuku.common.util.TrustedSigners;
import af.shizuku.common.util.UserHandleCompat;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.UserManagerApis;
import rikka.shizuku.server.ktx.HandlerKt;

public class ShizukuConfigManager extends ConfigManager {

    private static final Gson GSON_IN = new GsonBuilder()
            .create();
    private static final Gson GSON_OUT = new GsonBuilder()
            .setVersion(ShizukuConfig.LATEST_VERSION)
            .create();

    private static final long WRITE_DELAY = 10 * 1000;

    private static final File FILE = getConfigFile();
    private static final AtomicFile ATOMIC_FILE = new AtomicFile(FILE);

    private static File getConfigFile() {
        File shellFile = new File("/data/user_de/0/com.android.shell/shizuku.json");
        if (shellFile.exists()) {
            return shellFile;
        }
        try {
            File parent = shellFile.getParentFile();
            if (parent != null && parent.exists() && parent.canWrite()) {
                return shellFile;
            }
        } catch (Throwable ignored) {}
        return new File("/data/local/tmp/shizuku.json");
    }

    public static ShizukuConfig load() {
        FileInputStream stream;
        try {
            stream = ATOMIC_FILE.openRead();
        } catch (FileNotFoundException e) {
            LOGGER.i("no existing config file " + ATOMIC_FILE.getBaseFile() + "; starting empty");
            return new ShizukuConfig();
        }

        ShizukuConfig config = null;
        try {
            config = GSON_IN.fromJson(new InputStreamReader(stream), ShizukuConfig.class);
        } catch (Throwable tr) {
            LOGGER.e(tr, "load config");
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                LOGGER.w("failed to close: " + e);
            }
        }
        if (config != null) return config;
        return new ShizukuConfig();
    }

    public static void write(ShizukuConfig config) {
        synchronized (ATOMIC_FILE) {
            FileOutputStream stream;
            try {
                stream = ATOMIC_FILE.startWrite();
            } catch (IOException e) {
                LOGGER.e("failed to write state: " + e);
                return;
            }

            try {
                String json = GSON_OUT.toJson(config);
                stream.write(json.getBytes());

                ATOMIC_FILE.finishWrite(stream);

                // Closing #419: the config file was previously world-readable/writable.
                // Root bypasses DAC checks entirely, so it doesn't need explicit bits here;
                // only shell needs explicit access, granted via group ownership. No "other"
                // access is needed, so we restrict to owner+group read/write only.
                File file = ATOMIC_FILE.getBaseFile();
                if (file.exists()) {
                    try {
                        Os.chmod(file.getAbsolutePath(), 0660);
                        Os.chown(file.getAbsolutePath(), -1, android.os.Process.SHELL_UID);
                    } catch (ErrnoException e) {
                        LOGGER.w("failed to set permissions on " + file.getAbsolutePath() + ": " + e);
                    }
                }
                LOGGER.v("config saved to " + file.getAbsolutePath());
            } catch (Throwable tr) {
                LOGGER.e(tr, "can't save %s, restoring backup.", ATOMIC_FILE.getBaseFile());
                ATOMIC_FILE.failWrite(stream);
            }
        }
    }

    private final Runnable mWriteRunner = new Runnable() {

        @Override
        public void run() {
            write(config);
        }
    };

    private final ShizukuConfig config;

    public ShizukuConfigManager(IntPredicate isManagerAppId) {
        this.config = load();

        boolean changed = false;

        if (config.packages == null) {
            config.packages = new ArrayList<>();
            changed = true;
        }

        Map<Integer, List<String>> packagesByUid = new HashMap<>();
        List<PackageInfo> allPackages = new ArrayList<>();
        // Users whose installed list was read. A real user always has packages, so an empty list is
        // a read that failed (the no-throw call answers a failure with an empty list).
        Set<Integer> readUsers = new HashSet<>();

        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            List<PackageInfo> installed = InstalledPackagesCompat.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS, userId);
            if (!installed.isEmpty()) readUsers.add(userId);
            for (PackageInfo pi : installed) {
                if (pi == null || pi.applicationInfo == null) continue;
                allPackages.add(pi);

                List<String> list = packagesByUid.get(pi.applicationInfo.uid);
                if (list == null) {
                    list = new ArrayList<>();
                    packagesByUid.put(pi.applicationInfo.uid, list);
                }
                list.add(pi.packageName);
            }
        }

        if (packagesByUid.isEmpty()) {
            LOGGER.w("packagesByUid is empty, skipping config pruning to avoid wiping authorizations");
            return;
        }

        for (ShizukuConfig.PackageEntry entry : new ArrayList<>(config.packages)) {
            if (entry.packages == null) {
                entry.packages = new ArrayList<>();
            }

            // Removing the entry revokes the UID's grant, so it is removed as gone only on a positive
            // answer: its user's installed list was read and does not name it, and the throwing
            // packages-for-UID lookup answered without throwing and named nothing. A read that failed
            // (early in boot, a package manager not answering) is "unknown" and keeps the entry; the
            // no-throw lookup used here before answered a failure with an empty list, which was read
            // as "uninstalled" and deleted grants.
            final int uid = entry.uid;
            List<String> namedByUserList = readUsers.contains(UserHandleCompat.getUserId(uid))
                    ? packagesByUid.getOrDefault(uid, Collections.emptyList())
                    : null;
            List<String> packages = UidPackages.of(namedByUserList, () -> PackageManagerApis.getPackagesForUid(uid));
            if (packages == null) {
                LOGGER.w("cannot tell whether uid %d still has packages; keeping its config", uid);
                continue;
            }
            if (packages.isEmpty()) {
                LOGGER.i("remove config for uid %d since it has gone", uid);
                config.packages.remove(entry);
                changed = true;
                continue;
            }
            packagesByUid.put(uid, packages);

            if (entry.packages.isEmpty()) {
                // Entries created via the plain toggle path (updateFlagsForUid) used to be
                // written with no package names at all - that's missing data, not evidence this
                // uid's packages changed. Treating it as "changed" pruned a still-valid
                // authorization on every server restart, and separately made getApplications()
                // exclude the package from the authorized list on the very next refresh (its
                // membership check on this same empty list always fails). Backfill from the
                // live package list instead.
                LOGGER.i("backfilling empty packages list for uid %d from current package manager state", entry.uid);
                entry.packages.addAll(packages);
                changed = true;
                continue;
            }

            boolean packagesChanged = true;

            for (String packageName : entry.packages) {
                if (packages.contains(packageName)) {
                    packagesChanged = false;
                    break;
                }
            }

            final int rawSize = entry.packages.size();
            Set<String> s = new LinkedHashSet<>(entry.packages);
            entry.packages.clear();
            entry.packages.addAll(s);
            final int shrunkSize = entry.packages.size();
            if (shrunkSize < rawSize) {
                LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize);
            }

            if (packagesChanged) {
                LOGGER.i("remove config for uid %d since the packages for it changed", entry.uid);
                config.packages.remove(entry);
                changed = true;
                continue;
            }

            List<String> signers = currentSigners(uid, packages);
            if (GrantSigners.revoked(entry.signers, signers)) {
                LOGGER.w("remove config for uid %d since its packages are no longer signed by the key it was granted to", uid);
                config.packages.remove(entry);
                changed = true;
            } else if (signers != null && !signers.isEmpty() && !signers.equals(entry.signers)) {
                entry.signers = signers;
                changed = true;
            }
        }

        for (PackageInfo pi : allPackages) {
            if (pi.requestedPermissions == null) {
                continue;
            }

                String activePerm = null;
                if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) activePerm = PERMISSION;
                else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_LEGACY)) activePerm = ServerConstants.PERMISSION_LEGACY;
                else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_ORIGINAL)) activePerm = ServerConstants.PERMISSION_ORIGINAL;

                if (activePerm == null) continue;

                int uid = pi.applicationInfo.uid;
                boolean allowed;
                try {
                    allowed = Android17Compat.checkPermission(activePerm, uid) == PackageManager.PERMISSION_GRANTED
                            && PermissionDefiners.isTrusted(activePerm, isManagerAppId);
                } catch (Throwable e) {
                    LOGGER.w("checkPermission");
                    continue;
                }

                if (allowed) {
                    List<String> packages = new ArrayList<>();
                    packages.add(pi.packageName);
                    updateLocked(uid, packages, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED,
                            currentSigners(uid, packages));
                    changed = true;
                }
        }

        if (changed) {
            scheduleWriteLocked();
        }
    }

    private void scheduleWriteLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (HandlerKt.getWorkerHandler().hasCallbacks(mWriteRunner)) {
                return;
            }
        } else {
            HandlerKt.getWorkerHandler().removeCallbacks(mWriteRunner);
        }
        HandlerKt.getWorkerHandler().postDelayed(mWriteRunner, WRITE_DELAY);
    }

    private ShizukuConfig.PackageEntry findLocked(int uid) {
        for (ShizukuConfig.PackageEntry entry : config.packages) {
            if (uid == entry.uid) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    public ShizukuConfig.PackageEntry find(int uid) {
        return find(uid, TrustQuery.BUDGETED);
    }

    @Nullable
    ShizukuConfig.PackageEntry find(int uid, TrustQuery query) {
        ShizukuConfig.PackageEntry entry;
        synchronized (this) {
            entry = findLocked(uid);
        }
        if (entry != null && (entry.flags & ConfigManager.FLAG_ALLOWED) != 0) {
            return entry;
        }
        return effectiveEntry(uid, entry, isTrustedSignerUid(uid, query));
    }

    /** find() with the trust decision supplied, so a caller reporting it answers from one lookup. */
    @Nullable
    ShizukuConfig.PackageEntry find(int uid, boolean trusted) {
        ShizukuConfig.PackageEntry entry;
        synchronized (this) {
            entry = findLocked(uid);
        }
        return effectiveEntry(uid, entry, trusted);
    }

    // A trusted signer is always allowed: a stored revoke or deny (including one written before
    // this rule, or by a client other than the manager's list) does not take it away. Taking the
    // certificate out of TrustedSigners ends this, but not an allow stored earlier by the dialog:
    // that one has to be revoked as for any other app.
    @Nullable
    static ShizukuConfig.PackageEntry effectiveEntry(int uid, @Nullable ShizukuConfig.PackageEntry stored, boolean trusted) {
        if (stored != null && (stored.flags & ConfigManager.FLAG_ALLOWED) != 0) {
            return stored;
        }
        if (trusted) {
            return new ShizukuConfig.PackageEntry(uid, ConfigManager.FLAG_ALLOWED);
        }
        return stored;
    }

    /** True if a write with this mask/values would leave the uid without FLAG_ALLOWED. */
    static boolean clearsAllowed(int mask, int values) {
        return (mask & ConfigManager.MASK_PERMISSION) != 0 && (values & ConfigManager.FLAG_ALLOWED) == 0;
    }

    /**
     * Result of a signer lookup. Only TRUSTED grants anything; every other value is "not
     * trusted" to every decision, and they are told apart only so a caller can report which.
     */
    enum Trust {
        TRUSTED,
        /** Every package under the uid was read, installed under it, and none is trusted. */
        NOT_TRUSTED,
        /** No package list, an unreadable or inconsistent package, or an exception. */
        LOOKUP_FAILED,
        /**
         * No full lookup ran: the global lookup budget was spent, or the query accepts only a
         * cached positive. Never recorded, so the next query that may look does.
         */
        UNCHECKED
    }

    /** Who is asking, which decides whether the question may cost a full signer lookup. */
    enum TrustQuery {
        /**
         * Anything a client can trigger, such as a permission check or request from its own uid:
         * charged to {@link TrustedSignerCache}'s global budget, UNCHECKED once that is spent.
         */
        BUDGETED,
        /**
         * A transaction only the manager app may make. Not charged: it is bounded by the
         * operator's UI and the per-uid coalescing and cooldown, and an UNCHECKED here would show
         * a trusted app as revocable or let a revoke force-stop it.
         */
        MANAGER,
        /** Only a cached, re-validated positive counts; never starts a full lookup. */
        CACHED_ONLY
    }

    /** What a dialog Deny for a uid with this trust actually does, as a TrustedSigners.CONFIRMATION_* value. */
    static int denyOutcome(Trust trust) {
        switch (trust) {
            case TRUSTED:
                return TrustedSigners.CONFIRMATION_DENY_OVERRIDDEN;
            case NOT_TRUSTED:
                return TrustedSigners.CONFIRMATION_DENIED;
            default:
                return TrustedSigners.CONFIRMATION_DENIED_UNVERIFIED;
        }
    }

    private final TrustedSignerCache trustCache = new TrustedSignerCache();

    boolean isTrustedSignerUid(int uid) {
        return isTrustedSignerUid(uid, TrustQuery.BUDGETED);
    }

    boolean isTrustedSignerUid(int uid, TrustQuery query) {
        return trustOf(uid, query) == Trust.TRUSTED;
    }

    Trust trustOf(int uid) {
        return trustOf(uid, TrustQuery.BUDGETED);
    }

    /**
     * Whether any package currently installed under {@code uid} is signed by a certificate in
     * {@link TrustedSigners#SHA256}. Fails closed: any error is LOOKUP_FAILED, never TRUSTED and
     * never a crashed server.
     *
     * Find and the permission checks call this synchronously, so {@link TrustedSignerCache}
     * bounds the work: a positive is re-validated against the uid's current package set on every
     * hit (a package-name list and one flag-less getPackageInfo per package, no certificates or
     * hashing), concurrent callers for one uid share one lookup, a negative suppresses further
     * full lookups for that uid for a second after it completed, and {@code query} decides
     * whether a full lookup is charged to the global budget or not run at all.
     */
    Trust trustOf(int uid, TrustQuery query) {
        if (TrustedSigners.SHA256.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Trust.NOT_TRUSTED;
        }
        int userId = UserHandleCompat.getUserId(uid);
        TrustedSignerCache.PositiveCheck positive =
                () -> trustCache.hasPositive(uid) && trustCache.confirmPositive(uid, currentFingerprint(uid, userId));
        if (query == TrustQuery.CACHED_ONLY) {
            return positive.confirmed() ? Trust.TRUSTED : Trust.UNCHECKED;
        }
        return trustCache.resolve(uid, query == TrustQuery.BUDGETED, SystemClock::uptimeMillis, positive, () -> {
            try {
                return lookUpTrust(uid, userId);
            } catch (Throwable t) {
                LOGGER.w(t, "trusted signer lookup failed for uid " + uid);
                return Trust.LOOKUP_FAILED;
            }
        });
    }

    private Trust lookUpTrust(int uid, int userId) {
        List<String> names = PackageManagerApis.getPackagesForUidNoThrow(uid);
        if (names.isEmpty()) {
            return Trust.LOOKUP_FAILED;
        }
        List<PackageInfo> verified = new ArrayList<>();
        boolean complete = true;
        boolean trusted = false;
        for (String packageName : names) {
            PackageInfo pi = Android17Compat.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES, userId);
            // The name came from one PackageManager read and the certificate from another; a
            // remove-and-reinstall in between can hand back a same-named package that now
            // belongs to a different uid, whose signer says nothing about this one.
            if (!isInstalledUnderUid(pi, uid)) {
                complete = false;
                continue;
            }
            verified.add(pi);
            if (TrustedSigners.isSignedByTrustedKey(pi)) {
                trusted = true;
            }
        }
        if (trusted) {
            // Only a complete read describes the uid's package set well enough to re-validate.
            String fingerprint = complete ? packageSetFingerprint(uid, verified) : null;
            if (fingerprint != null) {
                trustCache.putPositive(uid, fingerprint);
            }
            return Trust.TRUSTED;
        }
        return complete ? Trust.NOT_TRUSTED : Trust.LOOKUP_FAILED;
    }

    @Nullable
    private static String currentFingerprint(int uid, int userId) {
        try {
            List<PackageInfo> infos = new ArrayList<>();
            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                infos.add(Android17Compat.getPackageInfo(packageName, 0, userId));
            }
            return packageSetFingerprint(uid, infos);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Identifies the exact set of package installs under {@code uid}, or null if the set is empty
     * or any member is missing or not installed under that uid. Re-signing needs an update or a
     * reinstall, either of which changes an install/update time, so an equal fingerprint means
     * the same APKs, and so the same signers, as when it was taken.
     */
    @Nullable
    static String packageSetFingerprint(int uid, List<PackageInfo> infos) {
        if (infos.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(infos.size());
        for (PackageInfo pi : infos) {
            if (!isInstalledUnderUid(pi, uid)) {
                return null;
            }
            long version = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
            parts.add(pi.packageName + '/' + version + '/' + pi.firstInstallTime + '/' + pi.lastUpdateTime);
        }
        Collections.sort(parts);
        StringBuilder sb = new StringBuilder().append(uid);
        for (String part : parts) {
            sb.append('|').append(part);
        }
        return sb.toString();
    }

    static boolean isInstalledUnderUid(@Nullable PackageInfo pi, int uid) {
        return pi != null
                && pi.applicationInfo != null
                && pi.applicationInfo.uid == uid
                && (pi.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
    }

    public List<Integer> getAllowedUids() {
        synchronized (this) {
            List<Integer> result = new ArrayList<>();
            for (ShizukuConfig.PackageEntry entry : config.packages) {
                if ((entry.flags & ConfigManager.FLAG_ALLOWED) != 0) {
                    result.add(entry.uid);
                }
            }
            return result;
        }
    }

    /**
     * SHA-256 digests (lowercase hex) of every signer of {@code names}, the packages installed
     * under {@code uid}: the APK's signers plus, for a single-signer APK, the earlier certificates
     * of its signing lineage, so a rotated key still matches the digest recorded before it.
     * Null when they cannot be read completely (below API 28, a package missing or not installed
     * under that uid, any error): unknown, never "no signer".
     */
    @Nullable
    private static List<String> currentSigners(int uid, @Nullable List<String> names) {
        if (names == null || names.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
        try {
            int userId = UserHandleCompat.getUserId(uid);
            Set<String> digests = new LinkedHashSet<>();
            for (String packageName : names) {
                PackageInfo pi = Android17Compat.getPackageInfo(
                        packageName, PackageManager.GET_SIGNING_CERTIFICATES, userId);
                if (!isInstalledUnderUid(pi, uid) || pi.signingInfo == null) {
                    return null;
                }
                List<Signature> signatures = new ArrayList<>();
                Collections.addAll(signatures, pi.signingInfo.getApkContentsSigners());
                if (!pi.signingInfo.hasMultipleSigners() && pi.signingInfo.hasPastSigningCertificates()) {
                    Collections.addAll(signatures, pi.signingInfo.getSigningCertificateHistory());
                }
                for (Signature signature : signatures) {
                    String digest = signature == null ? null : TrustedSigners.sha256Hex(signature.toByteArray());
                    if (digest == null) {
                        return null;
                    }
                    digests.add(digest);
                }
            }
            return digests.isEmpty() ? null : new ArrayList<>(digests);
        } catch (Throwable t) {
            LOGGER.w(t, "signer lookup failed for uid %d", uid);
            return null;
        }
    }

    private void updateLocked(int uid, List<String> packages, int mask, int values) {
        updateLocked(uid, packages, mask, values, null);
    }

    private void updateLocked(int uid, List<String> packages, int mask, int values, @Nullable List<String> signers) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            entry = new ShizukuConfig.PackageEntry(uid, mask & values);
            config.packages.add(entry);
        } else {
            int newValue = (entry.flags & ~mask) | (mask & values);
            if (newValue == entry.flags) {
                return;
            }
            entry.flags = newValue;
        }
        if (packages != null) {
            for (String packageName : packages) {
                if (entry.packages.contains(packageName)) {
                    continue;
                }
                entry.packages.add(packageName);
            }
        }
        if (signers != null && !signers.isEmpty()) {
            entry.signers = signers;
        }
        scheduleWriteLocked();
    }

    public void update(int uid, List<String> packages, int mask, int values) {
        // Computed per query, never stored: a persisted copy would outlive the signer that earned
        // it and would be reported by an older server that does not enforce the rule.
        if ((mask & TrustedSigners.FLAG_ALWAYS_ALLOWED) != 0) {
            mask &= ~TrustedSigners.FLAG_ALWAYS_ALLOWED;
            if (mask == 0) {
                return;
            }
        }
        // Revoke and deny writes come only from the manager (updateFlagsForUid, the dialog).
        if (clearsAllowed(mask, values) && isTrustedSignerUid(uid, TrustQuery.MANAGER)) {
            LOGGER.i("uid %d is signed by a trusted key; not storing a revoke for it", uid);
            mask &= ~ConfigManager.MASK_PERMISSION;
            if (mask == 0) {
                return;
            }
        }
        // Read outside the lock (package manager IPC); only a grant records them.
        List<String> signers = (mask & values & ConfigManager.FLAG_ALLOWED) != 0
                ? currentSigners(uid, packages != null && !packages.isEmpty()
                        ? packages : PackageManagerApis.getPackagesForUidNoThrow(uid))
                : null;
        synchronized (this) {
            updateLocked(uid, packages, mask, values, signers);
        }
    }

    private void removeLocked(int uid) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            return;
        }
        config.packages.remove(entry);
        scheduleWriteLocked();
    }

    public void remove(int uid) {
        synchronized (this) {
            removeLocked(uid);
        }
    }
}
