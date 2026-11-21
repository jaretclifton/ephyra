# 4. Migration

Eight phases. Every task is one pull request that leaves the app working and can be merged on its
own. The task issues are listed per phase in
[issue #733](https://github.com/midasvo/ephyra/issues/733); this file explains the order.

## 4.1 Principles

1. **Enforcement before cleanup.** The baselines of phase 0 are on `main` before the first type is
   replaced. From then on each PR deletes lines from a baseline, and nothing can be added to one
   unnoticed.
2. **Pin, then move.** Before code that talks to the server is moved, a test pins what it sends
   and what it makes of the answer (see [2-enforcement-and-tests.md](2-enforcement-and-tests.md),
   2.3).
3. **Behaviour does not change.** No task in phases 0 to 6 changes what a user sees. A task that
   finds a bug files it and leaves it.
4. **Types before modules.** Replacing a type is a change the compiler checks at every call site.
   Moving files between modules comes after, when there is nothing left to untangle, and moved
   files keep their package.
5. **Cheap leaks first.** Three enums are two thirds of the files in baseline R1. Replacing them
   early makes the baseline show what is actually hard.

## 4.2 Phases

### Phase 0: guardrails

Goal: the rules exist, with their baselines, and CI runs them. Nothing in the app changes.

- #735 The `architecture` module with Konsist, rules R1 to R4 and their baselines.
- #736 The unused SDK dependency of `settings` goes: the first line off the module allowlist.
- #737 The `domain` module, empty, with rule R5.
- #738 Tests that pin the playback info request, the source mapping and the playback reports at
  the HTTP boundary.
- #739 Golden tests for the mappers.

### Phase 1: our own types instead of the SDK's enums

Goal: the SDK's enums and exception types are gone outside `data`. R1 goes from 56 to about 15.

- #740 `ItemKind` for `BaseItemKind`.
- #741 `MediaStreamType`, `VideoRangeType`, `SubtitleDeliveryMethod`, under the same simple names.
- #742 `PersonKind`.
- #743 `ItemFilter` and `EpisodeField` for `ItemFilter` and `ItemFields`.
- #744 `java.time.LocalDateTime` for the SDK's `DateTime` alias.
- #745 `ServerException` for the SDK's exception types.

Each of these is independent of the others. They touch different lines of some of the same files
(`JellyfinRepository.kt` most of all), so two running at once will need a rebase, not a redesign.

### Phase 2: a repository interface with no SDK type, and no wrapper in the UI

Goal: `JellyfinRepository` imports nothing from the SDK; composables and the player stop using
`JellyfinApi`. `app/phone`, `app/tv`, `modes/film` and `player/local` lose their SDK dependency.
R3 goes from 8 to 4.

- #746 Libraries as `FindroidCollection` instead of `BaseItemDto`.
- #747 `ServerInfo` and `autoPlayNextEpisode()` instead of `PublicSystemInfo` and
  `UserConfiguration`.
- #748 `ServerSession` (read side) for the base URL and the user id.
- #749 URL building behind the repository (chapter images, the TV season backdrop).
- #750 The player's track preferences and HTTP client through interfaces.
- #751 Removing the dependency line from the four modules.

### Phase 3: the Jellyfin adapter

Goal: everything that uses the SDK is in the adapter's packages, and at the end in the adapter's
module. `data` has no SDK dependency.

- #752 Device profile: the probing stays, the Jellyfin assembly becomes the adapter's; requests
  name a `PlaybackTarget`.
- #753 Mappers take a base URL instead of the repository and leave the model files.
- #754 Image URLs as `String`.
- #755 The models move to `domain`.
- #756 The repository is split: catalog reads behind `CatalogPort`.
- #757 The repository is split: playback and user data behind `PlaybackPort` and `UserDataPort`.
- #758 The `server/jellyfin` module. **Last of all**: it needs every user of `JellyfinApi`
  outside the adapter gone, so it waits for phases 4 and 6.

This is the phase with the most moved lines and the least new logic. The tests of #738 and #739
are what make it safe. Until #758, adapter code lives in `data`, in the package
`dev.jdtech.jellyfin.server.jellyfin`; the module move is then a move of two packages with no
import changes.

### Phase 4: setup, session and sync

Goal: `setup` and `core` have no SDK dependency. In R2 only the Hilt providers in `core` that
construct the adapter remain, until #758 moves them.

- #759 `SetupRepository` with our own types.
- #760 `ServerAddressesViewModel` through its repository.
- #761 `ServerConnector`: the SDK code of `SetupRepositoryImpl` moves to the adapter.
- #762 `SyncWorker` over a port.
- #763 `ServerSessionKeeper`, reachability and the session wiring over `ServerSession`.

Phase 4 needs phases 1 and 2, not phase 3. It can run beside phase 3.

### Phase 5: playback

Goal: the engine boundary is explicit and the reporting and resolving logic have their own tests.

- #764 `PlayerEngine`: engine construction leaves `PlayerViewModel`; `engine/exo` and
  `engine/mpv`; rule R6.
- #765 `PlaybackReporter` out of `PlayerViewModel`.
- #766 `ResolvePlayback`: the target is decided in the player, not read from a preference in
  `data`.

Every PR here runs `docs/mpv-checklist.md` on both backends. This is the phase where a mistake
reaches users as a playback bug, and where the test net is thinnest.

### Phase 6: Cast

Goal: `player/cast` has no SDK dependency.

- #767 Playback info through `PlaybackPort` with a `CastReceiver` target; tracks from our own
  models; the Chromecast profiles in the adapter.

Downloads need no phase of their own: after phases 1 and 4 they have no SDK use left, and the
queue and the transfer engine are already separate from Jellyfin.

### Phase 7: ready for a second server

Goal: adding Emby is adding a module.

- #768 The lock: the allowlist is one module, the baselines are deleted, the exceptions for them
  are removed from the rules.
- #769 `ServerCapabilities` and the UI that follows it.
- #770 A server type on the `servers` table (existing rows become `jellyfin` in the migration) and
  a `MediaServer` chosen by it.
- #771 The `testing` module with `FakeMediaServer` and the contract suites; the Jellyfin adapter
  passes them.
- #772 `CLAUDE.md`, the upstream-sync skill and this folder brought up to date.
- Only when the Emby work starts: #773 opaque ids (D1), #774 renaming the repository (D2).

## 4.3 Order

```
Phase 0 ─► Phase 1 ─► Phase 2 ─┬─► Phase 3 (#752 to #757) ─┬─► Phase 6 ─┬─► #758 ─► Phase 7
                               ├─► Phase 4 ────────────────┼────────────┘
                               └─► Phase 5 (#764, then #765, then #766 after #757)

Exception: #769 and #771 of phase 7 do not wait for #758 (see below).
```

- Phase 5 is not on the path to #758 or to phase 7. It can run at any time after phase 2 and is
  the one phase that needs a device.
- #769 of phase 7 only needs the ports (#757, #761) and can start before #758; #771 follows it,
  #770 follows it and #758.

Inside a phase most tasks can run in parallel; each issue says which.

## 4.4 What each phase costs an upstream sync

Where upstream stands: Findroid has 344 Kotlin files; Ephyra has changed 223 of them and deleted
7. Since the last merge (2026-11-03) upstream has 11 commits, 7 of them not translations, and
since May 2026 one commit that touched a Kotlin file. Syncs bring translations. The skill already
says to cherry-pick instead of merging when a merge would mostly be conflicts in code Ephyra has
rewritten.

| Phase | Cost | Why |
|---|---|---|
| 0 | None | New files only. `settings.gradle.kts` gets three `include` lines |
| 1 | Low | Import lines and type names in about 50 files, 30 of which upstream also has. A conflict needs upstream to edit the same lines |
| 2 | Low | `JellyfinRepository.kt` and the home ViewModel, both already far from upstream |
| 3 | Medium | About 35 files move from `data` to `domain` or `server/jellyfin`. Their packages do not change, so git follows them as renames and an upstream edit still lands in the moved file. A model field that upstream adds needs its mapper line added in the adapter by hand. A file upstream *adds* to a moved package arrives in the old directory and has to be put in the right module |
| 4 | Low | `setup` moves one file's content. Upstream's `SetupRepositoryImpl` changes rarely |
| 5 | High, but not new | The player is already on the skill's list of code to cherry-pick around. After this phase an upstream change to `PlayerViewModel` cannot be merged at all, only re-applied |
| 6, 7 | None | Cast, capabilities and the test modules do not exist upstream |

**Translations are not affected.** Weblate's files are `<module>/src/main/res/values-*/strings.xml`
in `core`, `data`, `modes/film`, `player/local`, `setup` and `settings`. No task moves a resource
file. `domain` has no resources, and `server/jellyfin` returns our own types and enums; the
strings for them stay where they are (for example the server issues that `setup` turns into
text).

**The upstream-sync skill has to change**, in two steps:

1. With phase 0: nothing in the text. Its check step already runs `testDebugUnitTest`, which
   includes the architecture rules from then on. Add one hard rule: a sync that brings an SDK
   import into a file outside the adapter is fixed by mapping the type, never by adding the file
   to a baseline.
2. With phase 7: add the moved packages to step 2 ("Judge compatibility") as a class of their
   own (where a new upstream file in `data/models`, `data/repository` or `core/di` belongs now),
   add the module layout to the background section, and put `PlayerViewModel` and
   `JellyfinRepositoryImpl` on the cherry-pick list by name.

## 4.5 Options that were rejected

| Option | Why not |
|---|---|
| Tactical DDD: aggregates, domain events, a use case per call | The domain is thin. Nearly every operation is "ask the server, show the answer". A use case class per call would be 50 classes that forward one line each |
| Keep the SDK's types as the app's model and have an Emby adapter produce `BaseItemDto` | It keeps every file tied to an SDK version and to Jellyfin's shape, and the SDK's client still cannot talk to Emby. It solves none of the three reasons |
| ArchUnit | See the comparison in 2.1. Its advantage is covered by Gradle; it needs every variant compiled and cannot see two flavors in one run |
| Conventions only (packages, `internal`, review) | An agent with little context breaks a convention it has not read. Reason 1 is boundaries the build enforces |
| ViewModels call the server ports and Room directly, no repository | The Room behaviour (stored fallback, local sources, write-then-report) would be written in every ViewModel or once per server type |
| One port per server instead of five small ones | A 50-function interface is what there is now. Smaller ports mean an agent reads one of them, and a fake implements one of them |
| Wrap media3's `Player` in an interface of our own | Both engines implement `Player` already, `MediaSession` requires it, and the UI uses its listener. A wrapper would be a second copy of a large interface |
| A server-neutral device profile model now | `DeviceProfileBuilder` is 794 lines with tests, in the most fragile part of the app, and there is one consumer. The port takes a `PlaybackTarget` and the adapter builds its own profile. Worth revisiting when Emby gives a second profile to compare (decision D3) |
| Opaque ids now | 160 files, for a need that only Emby has, without having looked at an Emby server. The ports use aliases so the change stays possible (decision D1) |
| Rename `Findroid*` and the packages while moving | A large diff with no effect on any of the three reasons, and it would make every moved file a conflict in a sync |
| Put Room behind ports too (22 files use the DAO directly) | Real, and frozen by rule R4 so it does not grow, but it serves none of the three reasons. A separate decision after this migration |
| Kotlin Multiplatform for `domain` | No second platform is planned. A plain Kotlin module gives the fast tests and the purity |
| Move the files into the new modules first, clean up after | The modules would have to declare the SDK and each other to compile, so no rule could be enforced until the end |
