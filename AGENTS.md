# Agent Guidelines (Claude / Gemini / Cursor / Aider / et al.)

This file is the canonical entry point for AI coding agents. It points to
the per-agent guides that already exist in this repo and lists the rules
that apply to every agent regardless of vendor.

## Per-agent guides

`CLAUDE.md` and `GEMINI.md` are symlinks to this file, so every agent reads
the same rules. Jules has its own setup notes in [`JULES.md`](JULES.md).

## Rules that apply to every agent

1. **Crash rules were never published.** Earlier versions of this file pointed
   at "Critical Crash Rules" in a `CLAUDE.md` that was gitignored and never
   committed, here or upstream. Before changing startup, binder or service
   lifecycle code, read the git history of the file you touch for crash fixes.
2. **The build target is `:manager:assembleRelease`** for verification.
   Debug builds skip Sentry symbol upload and are fine for fast iteration.
3. **Do not edit `key.jks`, `signing.properties`, or files matching
   `secrets*`** — they are signing material.
4. **CI is GitHub Actions**, single workflow at `.github/workflows/app.yml`.
   Inspect it before assuming how a build works.
5. **Use `scripts/dev/*`** for common commands instead of re-deriving from
   `build.gradle` each session.
6. **Changes to the one-prompt marker or start ordering add a scenario first**
   (`AdbAuthWait`, `AdbClient`'s key offer, `AdbStartWorker`'s guards, the boot,
   headless, tile and "Attempt now" paths). Write the failing scenario in
   `manager/src/test/java/af/shizuku/manager/harness/` (catalogue: site-private
   `memory/handoffs/ShizukuTendCF/reports-2026-10-05/a2-scenario-catalogue-zcode.md`),
   then the fix. Ten review rounds on 2026-10-04 found ordering bugs the tests
   could have.
7. **The app handles a problem itself even when `~/ops` also does.** If stayturgid's
   Termux repair pass, fleet-watch or a deploy role works around something (for example
   restoring ADB after a reboot), the app should still deal with it where it can: upstream
   ShizukuPlus users have none of that tooling, and for the fleet the redundancy is the point.
   Don't call a case handled because the ops tooling covers it.

## Project quick-ref

- **Release-signed build without CI:** `scripts/dev/build-release-local.sh [dropin|shizukuplus]`
  (signing secrets from the 1Password Service vault into a temp dir; installs over the fleet's app)
- **Unit tests:** the manager has flavours, so name one: `:manager:testShizukuplusDebugUnitTest --tests '<class>'`
  (bare `:manager:testDebugUnitTest` is ambiguous). JDK 21 is
  `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`; `/usr/libexec/java_home -v 21`
  returns JDK 27 on the build Mac, which fails. A fresh worktree needs `git submodule update --init api`.
- **Entry activity:** `MainActivity` (NOT `HomeActivity` — that's abstract)
- **Settings keys:** `manager/src/main/java/af/shizuku/manager/ShizukuSettings.java` inner class `Keys`
- **Preference XML:** `manager/src/main/res/xml/settings_*.xml`
- **Theme:** `Theme.Material3Expressive.*` — use M3 components, not AppCompat
- **App widgets:** `RemoteViews` only allows framework views — do NOT use
  `MaterialButton` / `MaterialSwitch` etc. inside `widget_*.xml`
## CFEngine reference book — query it, don't guess (2026-10-03)

`Learning CFEngine` (Diego Zamboni, 2nd ed.; covers CFEngine 3.12) is in the
local book knowledge base as slug `learning-cfengine` — 69 chapters, 210 code
blocks. **Before writing or reviewing CFEngine policy** — promise semantics,
`edit_line`, bundle/body syntax, class expressions, normal ordering, testing —
query it rather than relying on recall:

- `~/ops/site-private/bin/book-kb query '<regex>' learning-cfengine` — exact
  match, ~0.5–5k tokens. **This is what proves a term is or is not in the book.**
- `~/ops/site-private/bin/book-kb toc learning-cfengine` — ~2.5k-token chapter
  index; then read one chapter (~1.2k median). Add `--deep` for `file:line`
  anchors to every sub-heading.
- basic-memory `search_notes(project="books", query=…)` — semantic recall when
  you don't know the author's wording. Ranked by similarity, so a hit is **not**
  a match; confirm with the exact query above.

Never read `~/kb/raw/learning-cfengine.md`: that is the entire book, ~115k
tokens. The index exists so you don't have to. Full detail, including how to add
a book: `site-private/AGENTS.md` ("Book knowledge base").
