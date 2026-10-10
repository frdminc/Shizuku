package rikka.shizuku.server;

import androidx.annotation.Nullable;

import java.util.Collection;

/**
 * Decides whether a persisted grant still belongs to the app that earned it. A config entry is
 * keyed by UID and its package names; a package name alone says nothing about who signed the
 * package, so after an uninstall and a reboot a same-named app signed by someone else on the same
 * UID would inherit the grant. The entry therefore records the SHA-256 digests of the signing
 * certificates seen when it was granted, and server start-up revokes it when the installed
 * package set no longer shares one of them.
 *
 * <p>Only a positive mismatch revokes: a signer read that failed or was incomplete is "unknown"
 * and keeps the entry, the same rule {@link UidPackages} applies to a package list that could not
 * be read, but the grant is not honoured until a later read confirms it. An entry written before
 * digests were recorded has nothing to compare and is given the current ones (trust on first
 * reconcile). Digests are read only on API 28 and later.
 */
final class GrantSigners {

    private GrantSigners() {
    }

    /**
     * @param recorded the digests stored with the entry, or {@code null}/empty for an older entry
     * @param current  the digests of every signer of the UID's installed packages now (a rotated
     *                 signing lineage includes its earlier certificates), or {@code null} when
     *                 they could not be read completely
     * @return true only when both are known and share no certificate
     */
    static boolean revoked(@Nullable Collection<String> recorded, @Nullable Collection<String> current) {
        if (recorded == null || recorded.isEmpty() || current == null || current.isEmpty()) {
            return false;
        }
        for (String digest : current) {
            if (recorded.contains(digest)) {
                return false;
            }
        }
        return true;
    }
}
