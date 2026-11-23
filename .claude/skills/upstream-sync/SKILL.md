---
name: upstream-sync
description: >-
  Merge upstream Findroid (jarnedemeulemeester/findroid) into Ephyra (repo midasvo/ephyra) in a
  pull request, so Ephyra stays 0 commits behind: translations come in, every code change gets
  a verdict in docs/upstream-ledger.md. Use this whenever the user mentions syncing with
  upstream or Findroid, pulling or merging upstream/jarnedemeulemeester changes, taking
  Findroid's translations or a Findroid fix, or simply says "sync" in this repo. A sync never
  releases anything: releases are the `release` skill.
---

# Upstream Sync Playbook

Goal: keep Ephyra 0 commits behind Findroid, with every Findroid commit judged. "0 behind" means
each upstream commit is in `main`'s history and has a verdict in `docs/upstream-ledger.md`. It
does not mean Ephyra has upstream's code. The README links the ledger, so users can see where
Ephyra differs and why. A sync is a normal PR and never a release; what it brings reaches users
with the next release, under **From Findroid** in the changelog.

## When to sync

When upstream has translations, or about once a month. Don't chase the counter daily.

**Always a real merge, resolved by path class.**

- **Shared paths** take the three-way merge result: `**/res/values*/strings.xml`, the files
  Findroid's Weblate writes. Don't `git checkout upstream/main --` them: Ephyra has edits of its
  own in some (CE strings in the default-language files, the chapter-skip keys removed in
  `settings`, a Dutch typo fix in `core`).
- **Everything else** (Kotlin, Gradle, `gradle/libs.versions.toml`, workflows, `fastlane/`, the
  README) stays on Ephyra's side, conflicted or not. An upstream change there is never merged as
  text: it is read, gets a verdict, and if it is worth having it is built again in Ephyra's
  structure in a PR of its own.

Never `git merge -s ours`: it marks upstream's translations as merged without taking them, and
they never arrive again. Never cherry-pick instead of merging: the counter then never reaches 0.

Don't re-implement for the sake of it. Rebuilding an upstream refactoring in Ephyra's form is
busywork unless Ephyra gains from it: a fix users would notice, or a dependency Ephyra has to
follow anyway. The rest is **skipped**, with the reason in the ledger.

## Module layout

Ephyra's code is not where upstream's is (ports and adapters, #733; `CLAUDE.md` lists the
modules, `docs/architecture/` has the reasons). Packages stayed `dev.jdtech.jellyfin…`, but the
files of upstream's model, repository, API and download directories now live elsewhere or were
split. Use this table to say in the ledger where an upstream change would land:

| Upstream (Findroid) | Ephyra |
|---|---|
| `data/.../models/Findroid*.kt` | `domain/.../models/`, renamed (below); the mapping from SDK types is in `server/jellyfin/.../mappers/` |
| `data/.../models/Findroid*Dto.kt` | `data/.../models/*Entity.kt` (Room) |
| `data/.../repository/JellyfinRepository.kt` | `application/.../repository/MediaRepository.kt` |
| `data/.../repository/JellyfinRepositoryImpl.kt` | split: `data/.../repository/MediaRepositoryImpl.kt` (Room, stored fallback) over the ports, the SDK calls in `server/jellyfin` (`JellyfinCatalog`, `JellyfinPlayback`, `JellyfinUserData`, …) |
| `data/.../repository/JellyfinRepositoryOfflineImpl.kt` | gone (#281, no offline mode) |
| `data/.../api/JellyfinApi.kt` | `server/jellyfin/.../api/JellyfinApi.kt` |
| `data/.../database/` | `data/.../database/`, used only through the store ports in `application` (`ServerStore`, `DownloadStore`, `PendingUserDataStore`) |
| `core/.../di/` | split: `core/.../di/`, `data/.../data/di/` (Room, the stores, the repository), `downloads/.../di/`, `server/jellyfin/.../di/` |
| `core/.../utils/Downloader.kt`, `DownloaderImpl.kt`, `work/ImagesDownloaderWorker.kt` | `downloads` (with `DownloadQueue`); `DownloaderViewModel` stayed in `core` |
| `setup/.../data/SetupRepositoryImpl.kt` | same path, but its SDK calls are behind `ServerConnector` (`server/jellyfin/.../JellyfinServerConnector.kt`) |

Git follows a moved file only where the merge sees it; Kotlin is never merged as text here
anyway (above). A file Findroid **adds** in one of those directories would land in the old place:
re-implemented, it goes into the module the table names. A model field Findroid adds needs the
field on the renamed model in `domain`, its mapper line in `server/jellyfin`, and, if it is
stored, the `*Entity` column and a Room migration.

**Renamed.** `JellyfinRepository`/`JellyfinRepositoryImpl` are `MediaRepository`/
`MediaRepositoryImpl` (#774), and the models dropped `Findroid` (`FindroidItem` is `Item`,
`FindroidMovie` is `Movie`, `FindroidSource` is `ItemSource`, `FindroidCollection` is `Library`,
`FindroidSources` is `Playable`, `FindroidMediaStream` is `MediaStream`, the Room `Findroid*Dto` are
`*Entity`; the full map is in #774). Ids are value classes, not `UUID`: `ItemId`, `UserId`,
`ServerId`, `MediaSourceId` (`domain/.../models/Ids.kt`, #773, #902). A Findroid change that names
an old type or a `UUID` id is re-applied with the new name and type. `FindroidTheme` kept its name.

**Re-applied by hand most often.** `PlayerViewModel.kt` (split into `player/local/.../domain/` and
the engines), `JellyfinRepositoryImpl.kt` (now `MediaRepositoryImpl.kt` and the adapter),
`SetupRepositoryImpl.kt` and `DownloaderImpl.kt` differ so much from upstream that a change there
is always read and rebuilt, never compared line by line.

## Background

- **Fork:** `origin` = `midasvo/ephyra`, the app is **Ephyra** (formerly Findroid CE).
  **Upstream:** `jarnedemeulemeester/findroid`. Upstream auto-sync is **disabled**; syncing is
  always manual.
- Ephyra carries many commits of its own (downloads, the TV app, device-profile transcoding,
  branding). Where they meet upstream changes, Ephyra's behaviour wins unless the upstream
  change is a fix we want.

## Procedure

### 1. Fetch and assess

```bash
git config remote.upstream.tagOpt --no-tags              # once; Findroid's tags must not come in
git fetch origin
git fetch upstream --no-tags
git switch -c sync/findroid-$(date -u +%Y-%m-%d) origin/main
git rev-list --left-right --count upstream/main...HEAD   # "<behind>  <ahead>"
git log --oneline $(git merge-base HEAD upstream/main)..upstream/main   # what's new upstream
git diff --stat $(git merge-base HEAD upstream/main) upstream/main      # files upstream touches
```

> Use `merge-base..upstream/main`, **not** `HEAD..upstream/main`: the latter compares full trees
> and reports all of Ephyra's own commits as reverse changes, producing a huge misleading diff.

If "behind" is 0, there is nothing to sync: tell the user and stop.

### 2. Judge compatibility

Go through `git log --reverse $(git merge-base HEAD upstream/main)..upstream/main` one commit
at a time and read its diff. Translation commits need no verdict. Every other commit gets a row
in `docs/upstream-ledger.md`, in a new section for this sync at the top of **Commits**:

`| commit | date | kind | what it does | verdict | where |`

Kind: dependency, fix, feature, refactoring, CI/metadata. Verdict:

- **taken**: Ephyra has the change as upstream made it. Only for shared paths, a file that is
  identical on both sides, or a version Ephyra's catalog already has.
- **re-implement**: worth having, but it can't come in as it is. File an issue that names the
  upstream commit, says what it does, where that code lives in Ephyra and what must survive, and
  put the issue in the row. Don't build it in the sync PR.
- **covered**: Ephyra has the same thing in its own form; name the file, issue or PR.
- **skipped**: with the reason.

For a commit that touches the **moved files** (the table under Module layout), the "where"
column names the Ephyra module and file, not upstream's path, and a re-implement issue says which
port, store or mapper the change needs.

The PR that closes a **re-implement** issue changes the row to **re-implemented** and adds
itself, and has `Upstream: <sha>` in its commit message. That edit belongs to that PR, not to a
later sync.

A fix for a crash, for data loss or for a security problem is never **skipped** without asking
the user. Don't go on while a commit has no verdict.

### 3. Merge

```bash
git merge --no-commit --no-ff upstream/main
# Everything outside the shared paths goes back to main, conflicted or not:
{ git diff --name-only --cached HEAD; git diff --name-only --diff-filter=U; } | sort -u \
  | grep -vE '/res/values[^/]*/strings\.xml$' \
  | xargs -r git restore --source=HEAD --staged --worktree --
git diff --cached --stat HEAD   # must show only strings.xml files, or nothing
```

A file upstream added outside the shared paths shows up in that last diff: remove it again
(`git rm -f`). Resolve a conflict inside a `strings.xml` by hand, keeping Ephyra's edits. Then
add the ledger rows and commit the merge; the message lists every non-translation commit with
its verdict. With no translation changes the merge has an empty diff apart from the ledger, and
that is a complete sync.

- **CE translations collision:** if upstream adds a string or plural with the same key as one
  in `strings_ce.xml`, the build fails on duplicate resources. Delete the key from
  `strings_ce.xml` in all languages so upstream's Weblate translation takes over.
- **Upstream removes or renames a string Ephyra still uses:** the build fails on the missing
  resource. Add it as a `ce_` key in `strings_ce.xml`, with the translations from the files as
  they were before the merge, and use that key.
- **No upstream `strings.xml` moved** with the modules. The only new one is
  `downloads/src/main/res/values/strings.xml`, which holds six CE download strings (translations in
  its `strings_ce.xml`); upstream has no `downloads` module, so nothing merges into it.

### 4. Check

```bash
./gradlew ktfmtCheck testDebugUnitTest :app:phone:assembleLibreDebug :app:tv:assembleLibreDebug
python3 .claude/skills/translate-strings/scripts/check-translations.py
```

`testDebugUnitTest` includes the architecture rules (`:architecture:test`, R1–R16). A sync only
brings `strings.xml` files, so they should not fail; if one does, upstream code came in by
mistake. When a re-implement PR later brings upstream code that imports the SDK outside the
adapter, fix it by mapping the type in `server/jellyfin` and using Ephyra's own type, never with a
baseline line.

If something fails, fix forward in the same branch (KSP/Kotlin alignment, API changes). If it
can't be fixed easily, stop and report; the branch is the rollback, `main` is untouched.

### 5. Pull request

```bash
git push -u origin HEAD
gh pr create --repo midasvo/ephyra   --title "chore(upstream): sync with Findroid $(git rev-parse --short upstream/main)"   --body "<the translations that came in, and this sync's ledger rows as a table>"
```

Merge it with `--merge` once `gh pr checks` is fully green: a squash or rebase would drop the
upstream parent and the counter would not move (`--auto` merges right away because `main` has no
required checks). A sync with no translation changes gets `--body "[skip beta]"`. After the
merge, `git rev-list --left-right --count upstream/main...origin/main` must start with `0`.
Don't touch `CHANGELOG.md` here; the release skill writes the **From Findroid** part.

### 6. Report

Summarise for the user: the translations that came in, each ledger row of this sync, the issues
filed for **re-implement**, the checks, and the PR.

## Hard rules

- Never revert the app ID `nl.midasvo.findroid.ce` back to upstream's `dev.jdtech.jellyfin`.
- **Branding:** the app is Ephyra. Keep `app_name` (`Ephyra` / `Ephyra Debug` / `Ephyra Beta`), the
  launcher icons and TV banner from `images/branding/ephyra/`, the `ephyra-v…` APK names and the
  "Ephyra Device Profile" names on the CE side. Upstream's `welcome`, `welcome_text` and
  `permission_local_network_rationale` say "Findroid"; CE shows `ce_welcome`, `ce_welcome_text` and
  `ce_permission_local_network_rationale` instead, so take upstream's changes to those keys as they come.
- **Versions are ours.** Upstream's `APP_NAME` / `APP_CODE` in `Versions.kt` are unused: take
  whatever upstream has there, and never touch `EphyraVersion.kt` or tag anything in a sync.
- **No upstream tags.** Fetch upstream with `--no-tags` (the remote has `tagOpt = --no-tags`).
  Findroid's tags would clash with Ephyra's `vX.Y.Z` as soon as Findroid reaches 2.0.0.
- `.claude/worktrees/` are scratch copies — ignore them when grepping the tree.
- **The ledger is the record.** `docs/upstream-ledger.md` has a row for every upstream commit
  since 2026-11-15 that is not a translation. A commit without a row was not judged.
- A sync that brings an `org.jellyfin.sdk` import into a file outside the adapter is fixed by
  mapping the type or moving the code into `server/jellyfin`: the architecture rules have no
  exceptions for the SDK.
- **CE-owned defaults** — keep these on the CE value when a merge touches them; upstream still
  ships the other value, so every sync re-presents it:
  - `AppPreferences.playerMpvAo` = `audiotrack` (upstream: `aaudio`). Reverting it reintroduces
    the mpv seek/track-switch video freeze — issue #52, upstream #1246, mpv-android#1283 (open).
    `MPVPlayer`'s constructor and Builder defaults mirror it.
  - The **ktfmt formatter** = `0.62`: `ktfmt-formatter` in `gradle/libs.versions.toml`, forced
    onto the plugin's `ktfmt` configuration in the root `build.gradle.kts`. The ktfmt Gradle
    plugin itself is on upstream's `0.27.0` (the first that sees the sources under AGP 9's
    built-in Kotlin, #661), but its default formatter, 0.64, formats the whole tree differently.
    Upstream reformatted in `ba318d178` (2026-11-21). Moving Ephyra's formatter is a PR of its
    own with a tree-wide `ktfmtFormat`, never part of a sync. Plugin 0.28.0 needs ktfmt 0.65 and can't run 0.62. `ktfmtCheckCoverage` (under
    `ktfmtCheck`) fails if a plugin change leaves Kotlin files unchecked again.
  - **JDK 21** — `Versions.JAVA` = `JavaLanguageVersion.of(21)` in `buildSrc/.../Versions.kt` and
    `java-version: 21` in every `.github/workflows/*` (upstream: 17, since `1d5081d6`). Local dev
    machines only have JDK 21 and toolchain auto-provisioning isn't configured, so taking 17
    breaks the build with "Cannot find a Java installation … languageVersion=17".
