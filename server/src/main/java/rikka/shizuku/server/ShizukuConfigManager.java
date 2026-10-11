package rikka.shizuku.server;

import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.ByteArrayInputStream;
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
import java.util.function.Supplier;

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

    /**
     * How long a batched write waits, so the start-up reconcile and import, which can change many
     * entries at once, are written together. A change the operator makes is written at once
     * instead ({@link #scheduleWriteLocked(boolean)}).
     */
    private static final long WRITE_DELAY = 10 * 1000;

    /** No write is waiting. */
    private static final int WRITE_NONE = 0;
    /** A write is posted to run after {@link #WRITE_DELAY}. */
    private static final int WRITE_DELAYED = 1;
    /** A write is posted to run next on the worker. */
    private static final int WRITE_PROMPT = 2;

    private static final File FILE = getConfigFile();
    private static final SafeConfigFile CONFIG_FILE = new SafeConfigFile(FILE);

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
        byte[] bytes;
        try {
            bytes = CONFIG_FILE.read();
        } catch (Throwable tr) {
            LOGGER.e(tr, "load config");
            return new ShizukuConfig();
        }
        if (bytes == null) {
            LOGGER.i("no existing config file " + CONFIG_FILE.getPath() + "; starting empty");
            return new ShizukuConfig();
        }

        ShizukuConfig config = null;
        try {
            config = GSON_IN.fromJson(new InputStreamReader(new ByteArrayInputStream(bytes)), ShizukuConfig.class);
        } catch (Throwable tr) {
            LOGGER.e(tr, "load config");
        }
        if (config != null) return config;
        return new ShizukuConfig();
    }

    /** Writes {@code json} as the config. Callers hold the CONFIG_FILE lock; a seam for tests. */
    static void writeJson(String json) {
        // The config is written to a fresh file given mode 0660 and group shell through its
        // descriptor, then renamed into place, never through a symbolic link (SafeConfigFile).
        // Closing #419: the config file was previously world-readable/writable. Root bypasses
        // DAC checks entirely, so it doesn't need explicit bits; only shell needs access,
        // granted via group ownership.
        try {
            CONFIG_FILE.write(json.getBytes());
            LOGGER.v("config saved to " + CONFIG_FILE.getPath());
        } catch (Throwable tr) {
            LOGGER.e(tr, "can't save %s; the previous config is kept.", CONFIG_FILE.getPath());
        }
    }

    /**
     * Writes a change still waiting for the worker now, on the calling thread; nothing when none
     * is. For an exit, which would otherwise lose it.
     */
    void flushPendingWrite() {
        writePending();
    }

    /**
     * Writes the config if a change is waiting. The config is copied out under this object's lock,
     * which every change holds, and written under CONFIG_FILE's, held across both so that two
     * writes (the worker's and an exit's flush) cannot reach the disk in the other order. Nothing
     * takes CONFIG_FILE while holding this object's lock.
     */
    private void writePending() {
        synchronized (CONFIG_FILE) {
            String json;
            synchronized (this) {
                // Checked first: a flush with nothing waiting does not start the worker thread.
                if (pendingWrite == WRITE_NONE) {
                    return;
                }
                HandlerKt.getWorkerHandler().removeCallbacks(mWriteRunner);
                // Cleared before the copy: a change after it posts a write of its own.
                pendingWrite = WRITE_NONE;
                try {
                    json = GSON_OUT.toJson(config);
                } catch (Throwable tr) {
                    LOGGER.e(tr, "can't save %s; the previous config is kept.", CONFIG_FILE.getPath());
                    return;
                }
            }
            writeJson(json);
        }
    }

    private final Runnable mWriteRunner = new Runnable() {

        @Override
        public void run() {
            writePending();
        }
    };

    private final ShizukuConfig config;

    /** WRITE_NONE, WRITE_DELAYED or WRITE_PROMPT: the write posted to the worker, if any. */
    private int pendingWrite = WRITE_NONE;

    /**
     * Uids whose stored grant start-up could not check against their packages and signers (a
     * read that did not answer). The grant is kept but not honoured until find() confirms it.
     */
    private final Set<Integer> unconfirmedUids = new HashSet<>();
    private final Map<Integer, Long> lastRecheck = new HashMap<>();
    private static final long RECHECK_INTERVAL = 1000;

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
            // Not one installed list was read, so nothing can be checked: keep every grant, but
            // honour none until find() has checked it against what is installed now.
            LOGGER.w("packagesByUid is empty, skipping config pruning to avoid wiping authorizations");
            for (ShizukuConfig.PackageEntry entry : config.packages) {
                unconfirmedUids.add(entry.uid);
            }
            return;
        }

        // A UID whose grant was removed for a new signer: the import of held API permissions below
        // must not grant it again.
        Set<Integer> signerChanged = new HashSet<>();

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
            if (packages != null && !packages.isEmpty()) {
                packagesByUid.put(uid, packages);
            }

            Check result = check(entry, packages, () -> currentSigners(uid, packages));
            if (result == Check.UNKNOWN) {
                unconfirmedUids.add(uid);
            } else if (result == Check.UPDATED) {
                changed = true;
            } else if (result != Check.KEEP) {
                config.packages.remove(entry);
                changed = true;
                if (result == Check.SIGNER_CHANGED) {
                    signerChanged.add(uid);
                    revokeApiPermissions(uid, packages);
                }
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
                if (signerChanged.contains(uid)) continue;

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
                    if (updateLocked(uid, packages, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED,
                            currentSigners(uid, packages))) {
                        changed = true;
                    }
                }
        }

        if (changed) {
            // Start-up: one write for the whole reconcile and import. Under the lock, as the
            // worker reads pendingWrite under it.
            synchronized (this) {
                scheduleWriteLocked(false);
            }
        }
    }

    /**
     * Posts a write of the config to the worker: at once when {@code prompt} (a change the operator
     * made, which a stop or restart soon after must not lose), otherwise after {@link #WRITE_DELAY}
     * (start-up's bulk changes, and fill-ins that are worked out again at the next start). A
     * prompt write brings a delayed one forward; either one already posted covers a delayed
     * request. Each write saves the whole config, so one write covers every change before it.
     */
    private void scheduleWriteLocked(boolean prompt) {
        if (pendingWrite == WRITE_PROMPT || (pendingWrite == WRITE_DELAYED && !prompt)) {
            return;
        }
        Handler handler = HandlerKt.getWorkerHandler();
        if (prompt) {
            handler.removeCallbacks(mWriteRunner);
            handler.post(mWriteRunner);
            pendingWrite = WRITE_PROMPT;
        } else {
            handler.postDelayed(mWriteRunner, WRITE_DELAY);
            pendingWrite = WRITE_DELAYED;
        }
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
        ShizukuConfig.PackageEntry entry = storedEntry(uid);
        if (entry != null && (entry.flags & ConfigManager.FLAG_ALLOWED) != 0) {
            return entry;
        }
        return effectiveEntry(uid, entry, isTrustedSignerUid(uid, query));
    }

    /** find() with the trust decision supplied, so a caller reporting it answers from one lookup. */
    @Nullable
    ShizukuConfig.PackageEntry find(int uid, boolean trusted) {
        return effectiveEntry(uid, storedEntry(uid), trusted);
    }

    /**
     * The stored entry for {@code uid}. A grant start-up could not check is kept but withheld
     * (returned without its permission flags, so neither it nor the held-permission fallback of
     * getFlagsForUid counts) until a check here, at most one per {@link #RECHECK_INTERVAL} per
     * uid, confirms it; a check that finds other packages or another signer removes it.
     *
     * <p>Withholding covers the stored grant only. The caller permission check reads the runtime
     * permission from the OS on its own and still counts it: the OS clears that grant when the
     * package is uninstalled, and a package signed by another key cannot be installed over one
     * that keeps it.
     */
    @Nullable
    private ShizukuConfig.PackageEntry storedEntry(int uid) {
        synchronized (this) {
            ShizukuConfig.PackageEntry entry = findLocked(uid);
            if (entry == null || !entry.isAllowed() || !unconfirmedUids.contains(uid)) {
                return entry;
            }
            long now = SystemClock.uptimeMillis();
            Long last = lastRecheck.get(uid);
            if (last != null && now - last < RECHECK_INTERVAL) {
                return withheld(entry);
            }
            lastRecheck.put(uid, now);
        }
        // Package manager IPC, outside the lock.
        List<String> packages = UidPackages.of(null, () -> PackageManagerApis.getPackagesForUid(uid));
        List<String> signers = packages == null || packages.isEmpty() ? null : currentSigners(uid, packages);
        Check result;
        synchronized (this) {
            ShizukuConfig.PackageEntry entry = findLocked(uid);
            if (entry == null || !unconfirmedUids.contains(uid)) {
                // The operator decided for this uid while the check ran.
                return entry;
            }
            result = check(entry, packages, () -> signers);
            if (result == Check.UNKNOWN) {
                return withheld(entry);
            }
            if (result == Check.KEEP || result == Check.UPDATED) {
                unconfirmedUids.remove(uid);
                lastRecheck.remove(uid);
                if (result == Check.UPDATED) {
                    // Package names or signers filled in; the next start works them out again.
                    scheduleWriteLocked(false);
                }
                return entry;
            }
            removeLocked(uid);
        }
        if (result == Check.SIGNER_CHANGED) {
            revokeUnlessAllowedAgain(uid, packages);
        }
        return null;
    }

    /**
     * {@link #revokeApiPermissions} for a grant removed after start-up. The operator can allow
     * the uid again between the removal and the revoke, and the dialog grants the runtime
     * permission right after storing that grant; revoking then would take it back. The held
     * permissions are read first, outside the lock, then the revoke runs under the lock only
     * while the uid still has no allowed entry, so a grant either lands first and stops it or
     * waits for it to finish.
     */
    private void revokeUnlessAllowedAgain(int uid, List<String> packages) {
        List<String[]> held = heldApiPermissions(uid, packages);
        synchronized (this) {
            ShizukuConfig.PackageEntry entry = findLocked(uid);
            if (entry != null && entry.isAllowed()) {
                LOGGER.i("uid %d was allowed again; not revoking its API permissions", uid);
                return;
            }
            revoke(uid, held);
        }
    }

    private static ShizukuConfig.PackageEntry withheld(ShizukuConfig.PackageEntry entry) {
        ShizukuConfig.PackageEntry copy = new ShizukuConfig.PackageEntry(entry.uid, entry.flags & ~ConfigManager.MASK_PERMISSION);
        copy.packages.addAll(entry.packages);
        return copy;
    }

    /** What checking a stored grant against what is installed under its uid now decided. */
    enum Check {
        /** Same packages and a recorded signer, or nothing recorded to compare. */
        KEEP,
        /** Kept, and its package names or signers were filled in from the live ones. */
        UPDATED,
        GONE,
        PACKAGES_CHANGED,
        SIGNER_CHANGED,
        /** The packages, or the signers of a grant that recorded some, could not be read. */
        UNKNOWN
    }

    /**
     * Checks {@code entry} against {@code packages}, the uid's installed packages now (null when
     * they could not be read), and the signers {@code signersOf} reads, asked only once the
     * packages still match. Fills in package names and signers an older entry lacks; never
     * removes it, which is the caller's job.
     */
    private static Check check(ShizukuConfig.PackageEntry entry, @Nullable List<String> packages,
                               Supplier<List<String>> signersOf) {
        final int uid = entry.uid;
        if (packages == null) {
            LOGGER.w("cannot tell whether uid %d still has packages; keeping its config, unconfirmed", uid);
            return Check.UNKNOWN;
        }
        if (packages.isEmpty()) {
            LOGGER.i("remove config for uid %d since it has gone", uid);
            return Check.GONE;
        }

        if (!entry.packages.isEmpty()) {
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
                LOGGER.i("remove config for uid %d since the packages for it changed", uid);
                return Check.PACKAGES_CHANGED;
            }
        }

        List<String> signers = signersOf.get();
        if (GrantSigners.revoked(entry.signers, signers)) {
            LOGGER.w("remove config for uid %d since its packages are no longer signed by the key it was granted to", uid);
            return Check.SIGNER_CHANGED;
        }
        boolean recorded = entry.signers != null && !entry.signers.isEmpty();
        boolean known = signers != null && !signers.isEmpty();
        // Below Android 9 the signers are never read, so recorded ones (an entry carried over from
        // a newer release of Android) have nothing to be compared with: kept, as for an entry that
        // recorded none.
        if (recorded && !known && signersReadable()) {
            LOGGER.w("cannot read the signers of uid %d; keeping its config, unconfirmed", uid);
            return Check.UNKNOWN;
        }

        boolean updated = false;
        if (entry.packages.isEmpty()) {
            // Entries created via the plain toggle path (updateFlagsForUid) used to be
            // written with no package names at all - that's missing data, not evidence this
            // uid's packages changed. Treating it as "changed" pruned a still-valid
            // authorization on every server restart, and separately made getApplications()
            // exclude the package from the authorized list on the very next refresh (its
            // membership check on this same empty list always fails). Backfill from the
            // live package list instead; the signer check above still applies.
            LOGGER.i("backfilling empty packages list for uid %d from current package manager state", uid);
            entry.packages.addAll(packages);
            updated = true;
        }
        if (known && !signers.equals(entry.signers)) {
            entry.signers = signers;
            updated = true;
        }
        return updated ? Check.UPDATED : Check.KEEP;
    }

    private static final String[] API_PERMISSIONS = {
            PERMISSION, ServerConstants.PERMISSION_LEGACY, ServerConstants.PERMISSION_ORIGINAL
    };

    /**
     * Revokes the Shizuku API runtime permissions that {@code packages}, installed under
     * {@code uid}, hold. A held one is a grant of its own (the caller permission check, and the
     * start-up import of holders), so a grant removed for a new signer would otherwise come
     * straight back. Failures are logged, never thrown.
     */
    private static void revokeApiPermissions(int uid, List<String> packages) {
        revoke(uid, heldApiPermissions(uid, packages));
    }

    /** The {package, permission} pairs of the Shizuku API permissions {@code packages} hold. */
    private static List<String[]> heldApiPermissions(int uid, List<String> packages) {
        List<String[]> held = new ArrayList<>();
        int userId = UserHandleCompat.getUserId(uid);
        for (String packageName : packages) {
            PackageInfo pi;
            try {
                pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId);
            } catch (Throwable t) {
                LOGGER.w(t, "cannot read the permissions of %s to revoke them", packageName);
                continue;
            }
            if (pi == null || pi.requestedPermissions == null) {
                continue;
            }
            for (String permission : API_PERMISSIONS) {
                if (!ArraysKt.contains(pi.requestedPermissions, permission)) {
                    continue;
                }
                try {
                    if (Android17Compat.checkPermission(permission, uid) == PackageManager.PERMISSION_GRANTED) {
                        held.add(new String[]{packageName, permission});
                    }
                } catch (Throwable t) {
                    LOGGER.w(t, "cannot tell whether %s holds %s", packageName, permission);
                }
            }
        }
        return held;
    }

    private static void revoke(int uid, List<String[]> held) {
        int userId = UserHandleCompat.getUserId(uid);
        for (String[] pair : held) {
            String packageName = pair[0];
            String permission = pair[1];
            try {
                Android17Compat.revokeRuntimePermission(packageName, permission, userId);
                LOGGER.i("revoked %s from %s (uid %d) with its grant", permission, packageName, uid);
            } catch (Throwable t) {
                LOGGER.w(t, "revoking %s from %s failed", permission, packageName);
            }
        }
    }

    /** Signers are read on API 28 and later only; a seam, since tests run with SDK_INT 0. */
    static boolean signersReadable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
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

    /** Uids with a confirmed grant; one start-up could not check is left out until find() confirms it. */
    public List<Integer> getAllowedUids() {
        synchronized (this) {
            List<Integer> result = new ArrayList<>();
            for (ShizukuConfig.PackageEntry entry : config.packages) {
                if ((entry.flags & ConfigManager.FLAG_ALLOWED) != 0 && !unconfirmedUids.contains(entry.uid)) {
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
    static List<String> currentSigners(int uid, @Nullable List<String> names) {
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

    /** Applies the change; true when the config changed. The caller schedules the write. */
    private boolean updateLocked(int uid, List<String> packages, int mask, int values, @Nullable List<String> signers) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            entry = new ShizukuConfig.PackageEntry(uid, mask & values);
            config.packages.add(entry);
        } else {
            int newValue = (entry.flags & ~mask) | (mask & values);
            if (newValue == entry.flags) {
                return false;
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
        return true;
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
            boolean changed = updateLocked(uid, packages, mask, values, signers);
            if ((mask & ConfigManager.MASK_PERMISSION) != 0) {
                // The operator just decided for this uid, which settles a grant left unconfirmed at
                // start-up; and a grant answers for the signers it was made with, even when the
                // entry was already allowed.
                unconfirmedUids.remove(uid);
                lastRecheck.remove(uid);
                ShizukuConfig.PackageEntry entry = findLocked(uid);
                if (entry != null && signers != null && !signers.isEmpty() && !signers.equals(entry.signers)) {
                    entry.signers = signers;
                    changed = true;
                }
            }
            if (changed) {
                // The operator's change: written at once, on the worker.
                scheduleWriteLocked(true);
            }
        }
    }

    private void removeLocked(int uid) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            return;
        }
        config.packages.remove(entry);
        unconfirmedUids.remove(uid);
        lastRecheck.remove(uid);
        // A removal ends a grant, so it is written at once whoever asked for it.
        scheduleWriteLocked(true);
    }

    public void remove(int uid) {
        synchronized (this) {
            removeLocked(uid);
        }
    }
}
