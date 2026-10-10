# Trusted signer allowlist — permanent Shizuku access for your own apps

Last updated: **2026-10-10**

## The problem this solves

`ShizukuConfigManager`'s constructor reconciles the persisted per-app
authorization file (`/data/user_de/0/com.android.shell/shizuku.json`) against
whatever's currently installed, **every time `shizuku_server` starts**:

```java
List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(entry.uid);
if (packages.isEmpty()) {
    // uid has gone away entirely -> drop its grant
}
if (packagesChanged) {
    // the package set for this uid differs from what was persisted -> drop its grant
}
```

Since r2851 the same pass also compares signers: each entry stores the SHA-256
digests of its packages' signing certificates (`signers`, written when the grant
is recorded; an older entry is given the current ones on its first reconcile),
and the entry is dropped when the uid's installed packages share none of them
(`GrantSigners.revoked`), and any Shizuku API runtime permission they hold is
revoked with it, so the same start does not import the grant again. An
unreadable or incomplete package or signer read keeps the entry but does not
honour it until a later lookup confirms it, and a rotated signing key still
matches through its lineage. Signers are recorded only on Android 9 (API 28)
and later, so this check does not protect grants on Android 7-8.1 (API 24-27).

In practice this means an app's Shizuku permission can silently disappear
with **no user-visible cause** — not just on uninstall/reinstall (which is
arguably correct), but from anything that makes
`getPackagesForUidNoThrow(uid)` report a different result than last time,
including transient reads during unrelated install/uninstall activity
elsewhere on the device. This was hit directly during stayturgid fleet work
on 2026-07-31: the production `org.stayturgid.agent`'s grant was found reset
(`"Authorized 0 applications"` in the manager app) twice in one session,
including once from nothing more than restarting `shizuku_server` locally —
no reboot, no reinstall, no obvious trigger. Recovering it required manually
reopening the app and re-approving the permission dialog each time.

For an app that's supposed to be an **always-on fleet automation agent**,
"silently loses privileged access for no visible reason, with no
notification" is a real reliability problem, not just an inconvenience.

## The fix: `TrustedSigners.SHA256`, enforced in `ShizukuConfigManager`

The allowlist of trusted **APK signing certificate SHA-256 fingerprints** is
`TrustedSigners.SHA256` in
`common/src/main/java/af/shizuku/common/util/TrustedSigners.java` — the one
copy, shared by server and manager (it used to be `TRUSTED_SIGNER_SHA256`
inside `ShizukuConfigManager`):

```java
public static final Set<String> SHA256 = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "35bbc3d1a93c2a726df14bcc066bdc791f7f55f21b57d9455da27439c5ff9b6a"
)));
```

`ShizukuConfigManager.find(uid)` — the method that actually gates every
permission check (see its call sites in `ShizukuService.getFlagsForUidInternal`)
— returns a synthetic entry with `FLAG_ALLOWED` set whenever the persisted
entry is not already an allow and `trustOf(uid)` is `TRUSTED`. That lookup
looks at every package currently installed under the UID and accepts a
package's certificate only if its `PackageInfo` has an `applicationInfo` whose
full UID equals the one being checked and which is installed for that user. It
has four results:

1. `TRUSTED`: some package under the UID passed that check and is signed by a
   listed certificate. Only this result grants anything.
2. `NOT_TRUSTED`: every package under the UID was read, belongs to it, and none
   is signed by a listed certificate.
3. `LOOKUP_FAILED`: no package list, a package that could not be read or no
   longer belongs to the UID, or an exception.
4. `UNCHECKED`: no lookup ran, because the global lookup budget was spent or
   the query accepts only a cached positive (see below).

**Anything but `TRUSTED` is never trusted.** Every decision treats
`LOOKUP_FAILED` and `UNCHECKED` exactly like `NOT_TRUSTED` (fail closed); they
are told apart only so the server can report which one happened. The existing
reconciliation/removal logic in the constructor is untouched, so this can't
affect Shizuku's behavior for any other app.

### Lookup cost: positive cache, coalescing, cooldown and a global budget

`find()` and the permission checks call the lookup synchronously, so
`TrustedSignerCache` bounds the work. No part of it can produce a false
positive:

1. **Positive cache, per UID, at most 16 entries.** A `TRUSTED` result from a
   lookup that read every package under the UID is remembered together with a
   fingerprint of that package set: each package's name, version code, first
   install time and last update time, taken from the same `PackageInfo` whose
   certificate was checked. Every hit re-reads the UID's packages (the name
   list plus one flag-less `getPackageInfo` each, no certificates and no
   hashing) and is believed only if the fingerprint is identical. Changing a
   signer needs an update or a reinstall, and either changes an install or
   update time; a reused UID holds different packages or different install
   times. Any mismatch, or a failed re-read, forgets the entry and runs a full
   lookup. A lookup that missed any package is still `TRUSTED` but is not
   cached. Only a verified `TRUSTED` lookup adds an entry, so traffic for
   other UIDs cannot push a trusted app out.
2. **One lookup per UID at a time.** A caller that arrives while a lookup for
   the same UID is running waits for it (up to five seconds, then
   `LOOKUP_FAILED`) instead of starting another. A shared negative is used as
   is. A shared `TRUSTED` was read before that caller arrived, so it is
   believed only if the UID's package set still matches it, exactly as for a
   cached positive; otherwise the caller looks once itself.
3. **Negative cooldown, per UID.** After a full lookup returns `NOT_TRUSTED` or
   `LOOKUP_FAILED`, that UID is answered with the same result, without another
   lookup, for one second measured from when the lookup **finished**. A fresh
   negative is never evicted early, so no amount of traffic for other UIDs
   shortens it; expired ones are purged on every insert. This is not a cached
   answer in the granting direction: it can only say "not trusted", so the
   worst it does is make a trusted app wait up to a second for its next real
   lookup.
4. **Global budget of 32 full lookups per second**, across all UIDs, for every
   lookup a client can trigger (permission checks and requests from its own
   UID). Once it is spent such a query answers `UNCHECKED` without a lookup.
   `UNCHECKED` is not recorded, so it starts no cooldown and the next query
   after the budget refills does a real lookup. A re-validated positive costs
   no budget, so a trusted app that has been verified once since the server
   started is never affected.

Not charged to the budget: transactions only the manager app may make
(`getFlagsForUid`, `updateFlagsForUid`, the permission-dialog reply, and the
manager's own `getApplications`). They are bounded by the operator's UI plus
items 2 and 3, and an `UNCHECKED` answer there would show a trusted app as
revocable, or let a revoke force-stop it. `getApplications` from any other
granted client sees trust only from a cached positive and never starts a
lookup, and `isHidden(uid)`, which any binder holder may call, no longer
touches `find()` at all.

### Manager and server agree from one decision

The manager does not evaluate the list itself. It reads an app's grant and its
locked state from one `getFlagsForUid` call with the permission mask plus the
`TrustedSigners.FLAG_ALWAYS_ALLOWED` capability bit. The server answers both
from a single lookup, so a row is never shown locked beside a grant computed
from a different answer. Only a server enforcing this rule sets the bit.

If the server cannot be asked (the binder stays dead through the same
retry-with-back-off `AuthorizationManager.granted()` uses, or the query
throws), the row's state is **unresolved**: its switch is off and disabled,
TalkBack reads "Permission state unavailable, tap to retry", a tap re-queries
instead of toggling, and long-press and swipe offer neither Grant nor Revoke.
An unresolved state is never shown as a grant that could be revoked. Only a
pre-v11 server, which has no `getFlagsForUid` and no such rule, is read
through the plain unlocked grant check.

## Why signing certificate, not package name or UID

Two things were considered and rejected before landing on this:

- **Package name allowlist** (`if (packageName.equals("org.stayturgid.agent"))`):
  defeated trivially — once the real app is uninstalled (which is exactly
  the scenario that motivated this fix), *any* app can be installed under
  that same package name and would inherit the trust. A signing-key check
  can't be forged without the actual private key.
- **UID allowlist**: UIDs are reassigned across installs/reinstalls, so a
  hardcoded UID would silently stop working (or worse, silently apply to
  whatever unrelated app got assigned that UID later) the first time the
  app was reinstalled.

## Why *not* "just trust whoever signed the currently-running Shizuku build"

This was a real alternative considered: instead of hardcoding a specific
fingerprint, dynamically read Shizuku's *own* signing certificate at runtime
and trust anything signed by the same key — "recompile with your own key,
no source changes needed." It's an appealing idea in principle, but **as
this fork is currently built, it would be a serious security hole**:
absent `signing.properties` (see `signing.gradle`), the build falls back to
the **shared, universally-known Android debug keystore**
(`CN=Android Debug`, password `"android"`, alias `"androiddebugkey"` — the
literal default every Android developer's machine uses). Verified directly
against the actual deployed release APK on 2026-07-31:

```
$ apksigner verify --print-certs shizuku-release.apk
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: 5ca8d5fcce4bad08206eb7e112b5c9d63b35b0a8874e3d25af48e8e332a9596a
```

"Trust whoever signed this build" would, today, mean "trust *any* debug-signed
APK from *any* developer anywhere" — the opposite of the intended guarantee.
This is the same gap tracked as `OPTIONS.md`'s **H1 — CI signing secrets**.
If H1 is ever fixed (a dedicated, non-debug release key configured for CI),
the self-referential design becomes safe and genuinely more convenient —
but until then, an explicit hardcoded fingerprint for a *known, independently
verified* signing key is the only safe option, and costs nothing extra to
set up in the meantime.

## Adapting this for your own app (person or AI agent)

You don't need to touch anything except the one constant, and you don't need
this fork's own signing set up correctly — only *your own app's* key needs
to be a real, dedicated, non-shared signing identity (i.e., not the debug
keystore).

1. **Get your app's real signing certificate SHA-256**, from the actual APK
   you install on-device — not from a keystore file, so you're verifying
   what's actually installed, not what you think you built:

   ```bash
   apksigner verify --print-certs your-app.apk
   # or, from an already-installed app, pull it first:
   adb shell pm path <your.application.id>
   adb pull <path-from-above> /tmp/app.apk
   apksigner verify --print-certs /tmp/app.apk
   ```

   Confirm the `DN` line shows *your own* identity, not `CN=Android Debug` —
   if it does, you're using the shared debug keystore and should set up a
   dedicated one before relying on this mechanism.

2. **Add the SHA-256 digest** (lowercase hex, no colons — the digest as
   printed by `apksigner` is already in this format) to
   `TrustedSigners.SHA256` in
   `common/src/main/java/af/shizuku/common/util/TrustedSigners.java`.
   Add a one-line comment naming the app/identity it belongs to, matching
   the existing entry.

3. **Rebuild and release** your Shizuku fork as usual. No other files need
   to change — `find()` already checks every package under a UID, so any
   number of your own apps sharing that same signing key are covered by one
   entry.

4. Any app signed with that key now gets Shizuku access automatically the
   first time it's installed and asks — no interactive "Allow" dialog, no
   risk of the grant silently disappearing later, and no way to switch it
   off from the manager (see below).

## What this does *not* protect against

This is a trust decision, not a sandbox. If your signing key is ever
compromised, anything signed with it gets automatic, silent Shizuku access
— the same as any code-signing trust model. Keep your keystore file and
passwords secret with the same care you'd give any other production signing
key. This mechanism only removes the *interactive, easily-and-silently-lost*
approval step for keys you already fully trust — it doesn't change what
"trusting a key" means.


## Revocation semantics on the ShizukuPlus base (2026-10-03)

**A trusted-signer app is always allowed; revoke and deny are ignored.** This reverses commit
`ca52b44d` ("make revoke stick for trusted-signer apps"), by owner decision on 2026-10-03: the point
of the allowlist is that the fleet agent can never lose access.

1. The server ignores a revoke (`updateFlagsForUid` / `update` with the permission bits cleared) and a
   Deny from the permission dialog for a trusted UID: nothing is stored, and the app is neither
   force-stopped nor has its user services torn down. A persisted deny or revoke from before this
   rule is overridden by `find()`.
2. A UID confirmed `TRUSTED` when it asks is never shown the permission dialog:
   `showPermissionConfirmation` allows it without UI. If the answer then is anything else
   (`NOT_TRUSTED`, `LOOKUP_FAILED`, or `UNCHECKED` because the budget was spent), the dialog is
   shown as for any app, and the manager still sends only what the user tapped; it never sends an
   Allow the user did not tap.
3. On a Deny the server looks up trust again, for whoever holds the UID at that moment:
   a. `TRUSTED`: the deny is not honoured; the requesting process is allowed.
   b. `NOT_TRUSTED`: the deny is honoured as for any app.
   c. `LOOKUP_FAILED` (or `UNCHECKED`, which a manager-sent reply cannot normally get): the deny
      is honoured (fail closed) and the server logs that it could not verify the signer.

   The manager sends Deny as the existing `dispatchPermissionConfirmationResult` transaction
   (`TrustedSigners.CONFIRMATION_TRANSACTION`, AIDL `= 104`), but without `FLAG_ONEWAY`, so the
   server can reply with the outcome that actually took effect (`TrustedSigners.CONFIRMATION_*`).
   No new transaction code and no change under `api/`. The manager shows "Always allowed by this
   build's policy" only when that reply says the deny was overridden; it never infers the outcome
   from a second query. A server without the rule runs the AIDL's oneway handler, writes no reply,
   and the manager shows nothing extra. Allow is still sent oneway, unchanged.
4. A deny honoured because of a failed lookup lasts only until a lookup succeeds. Shared
   `Service.checkSelfPermission()` is final and answers from the attached client record, which
   attach sets once, so `ShizukuService.onTransact` re-checks trust for the caller before that
   transaction runs and marks its unallowed records allowed if the UID is `TRUSTED`. A trusted
   client that attached while lookups failed, and only polls `checkSelfPermission()`, is allowed
   on its first poll after the lookup works (at most a second after the failed lookup finished,
   because of the cooldown, or once the global budget refills).
   Privileged calls and `requestPermission()` already re-check through `find()`.
5. The manager's app list shows the app with its switch on and disabled, plus a summary line, and
   its toggle, long-press, swipe, multi-select and toggle-all revoke paths skip it. It locks a row
   only when a server enforcing this rule says so, so against an older server, or while the
   server's signer lookup is failing, the row stays an ordinary switch (a revoke sent then is still
   ignored by the server if its own lookup says `TRUSTED`). When the manager cannot reach the
   server at all, the row is unresolved and offers no toggle (see above).

To cut a trusted-signer app off, do one of:

1. remove its digest from `TrustedSigners.SHA256`, rebuild, restart the server, and then revoke
   the app in the manager (its switch is ordinary again). The last step matters: an Allow the user
   ever tapped in the dialog was stored as a normal grant, and `find()` honours a stored grant
   without looking at the signer;
2. uninstall the app;
3. stop Shizuku.
