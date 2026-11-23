# Ports and adapters

The move of Ephyra to a hexagonal (ports and adapters) architecture, with DDD where there are
rules. Tracked in [issue #733](https://github.com/midasvo/ephyra/issues/733).

**Status: built.** Researched and planned on 2026-11-20; ADR 0001 (ports and adapters) built from
2026-11-21 to 2026-11-22 in 37 task issues (#735 to #771); ADR 0002 (further towards hexagonal,
DDD where there are rules) built on 2026-11-22 and 2026-11-23 (#773, #774, #896 to #902). This
README and `CLAUDE.md` describe the code as it is after both (`main` at `e4dee3f9b`, 2026-11-23).
Files 1 to 4 describe the app as it was on 2026-11-20, file 5 as it was on 2026-11-22: they are
the record of why, and where they differ from the code, the code is right.

| Document | Contents |
|---|---|
| [1-inventory.md](1-inventory.md) | 2026-11-20: every place the Jellyfin SDK crossed a module boundary, what the repository exposed, how playback, setup, downloads and Cast were tied to Jellyfin |
| [2-enforcement-and-tests.md](2-enforcement-and-tests.md) | How the build enforces the boundaries (Gradle and Konsist, with the Konsist/ArchUnit comparison), the contract test suites, and the test gaps that made a phase risky |
| [3-target.md](3-target.md) | The target of ADR 0001: module layout and port signatures. Historical: ADR 0002 changed it (its status section says where) |
| [4-migration.md](4-migration.md) | The phases of ADR 0001, what each one cost an upstream sync, and the options that were rejected |
| [adr/0001-ports-and-adapters.md](adr/0001-ports-and-adapters.md) | The decision on ports and adapters, D1 to D5 |
| [5-hexagonal-ddd.md](5-hexagonal-ddd.md) | 2026-11-22: what was not hexagonal yet, where DDD pays, the tasks T1 to T10 |
| [adr/0002-hexagonal-ddd.md](adr/0002-hexagonal-ddd.md) | The decision on that, with the owner's answers (it replaces D1 and D2) |

## Why

1. All development here is done by AI agents. Boundaries the build enforces let an agent work on
   one adapter with little context, get fast feedback from pure Kotlin tests, and make it
   impossible to leak a type across a layer.
2. Emby support is wanted later, as a second server adapter next to Jellyfin. The Jellyfin Kotlin
   SDK does not work against Emby, so nothing outside the Jellyfin adapter may depend on SDK
   types.
3. ExoPlayer and mpv are already two implementations of one idea. That boundary should be
   explicit.

## What was built

```
app/phone, app/tv   (composition root, screens)
   │
   ├─► modes/film, setup, settings, player/*, core, downloads ─► application ─► domain
   │                                                                  ▲
   ├─► data  (Room: the stores and MediaRepositoryImpl) ──────────────┘
   └─► server/jellyfin  (the server ports, the only module with the SDK) ───► domain
```

- `domain` (pure Kotlin): the models without the `Findroid` prefix (`Item`, `Movie`, `ItemSource`,
  `Library`, …), the ids as value classes (`ItemId`, `UserId`, `ServerId`, `MediaSourceId`), the
  server ports (`MediaServer` with `ServerSession`, `CatalogPort`, `UserDataPort`, `PlaybackPort`;
  `ServerConnector`, `UserDataSyncPort`), `ActiveServer`, `ServerCapabilities`, and the two domain
  models with rules: `PlayState` (marks, stops, the sync's merge) and `DownloadState`. `Ticks` for
  the unit conversions.
- `application` (pure Kotlin): `MediaRepository`, the event flows, and the store ports
  `ServerStore`, `DownloadStore`, `PendingUserDataStore`, keyed by `AccountKey(serverId, userId)`.
- `data`: Room, the three `Room*Store` and `MediaRepositoryImpl`, which composes the server in use
  with Room (stored fallback, local sources, write then report).
- `server/jellyfin`: the SDK, `JellyfinApi`, the mappers, the port implementations, the device
  profiles. `CurrentMediaServer` in `core` picks the adapter by the active server's type.
- `downloads`: the download engine and queue, out of `core`.
- `player/local`: ExoPlayer and mpv behind `PlayerEngine`; playback logic in
  `player/local/.../domain/` (`PlaybackReporter`, `ResolvePlayback`, `PlaylistManager`, …).
- `testing`: `FakeMediaServer`, `FakeServerConnector` and the contract suites the Jellyfin adapter
  passes and an Emby adapter must pass.

The decisions: D3 (the device profile is the adapter's) and D4 (Konsist) stand, D5 was how phase
5 was tested; D1 (ids) and D2 (the repository's name) were replaced by ADR 0002, which made the
ids opaque and renamed the repository and the models at once.

## The rules

`./gradlew :architecture:test` (part of `testDebugUnitTest`) runs them over the source tree. When
one fails, fix the code. `architecture/baseline/` has two allowlists, not debt: `uuid-imports.txt`
(files that may name `java.util.UUID`, for ids the app makes itself) and `string-ids.txt` (files
that may type a server or source id as `String`: the navigation routes, the Cast custom data). A
line is added only with a reason of the same kind, given in the PR.

| Rule | Says |
|---|---|
| R1, R2 | No production file outside `server/jellyfin` mentions the SDK or imports `JellyfinApi` |
| R3 | Only `server/jellyfin` declares the SDK dependency |
| R4 | No production file outside `data` imports `ServerDatabaseDao` |
| R5 | `domain` and `application` import nothing from Android, the SDK, Room (or `data`) |
| R6 | The engine packages of `player/local` use neither each other nor are used by shared code |
| R7 | Only the apps depend on `server/jellyfin` |
| R8 | `testing` is only a test dependency |
| R9 | Only the apps depend on `data` |
| R10 | No `java.util.UUID` outside the adapter, and no server or source id typed as `String` (the two allowlists) |
| R11 | No production type is named `Findroid*`, and none `Jellyfin*` outside the adapter |
| R12 | Only `Ticks` declares tick constants and converts ticks by a literal |
| R13 | Only `DownloadState` (`DownloadFiles`) names the partial download suffix |
| R14 | The play state rules are declared in `domain` alone; `data` and the sync compare no dates |
| R15 | `downloads` depends on no adapter, not on `core` and not on an app, and nothing else names its engine's implementation |
| R16 | The server in use is written as one `ActiveServer` value, in one place |

## Not done

- **Renaming the packages** (`dev.jdtech.jellyfin` → something of Ephyra's own): a possible
  follow-up, not a task (ADR 0002, Q2). It would lose queued WorkManager work and saved
  navigation state on update unless handled.
- **Emby itself.** The notes on #733 of 2026-11-22 for the first Emby task, as they stand now:
  - `ServerSession`, `MediaHttpClient` and `DeviceReportSource` are still bound once, to Jellyfin
    (`JellyfinServerModule`); `MediaServer`, `ServerConnector` and the sync ports are maps keyed by
    `ServerType`. The split switch is fixed (#899, R16).
  - Downloads of a server whose type this build does not know could not be listed or played in
    the emulator check of #835; `UnsupportedMediaServer` says they open from Room. Not checked
    again since: decide when Emby starts whether they should stay playable.
- **Preferences in the adapters.** File 5 (5.1, point 3) wanted `data` and the adapter to stop
  reading `AppPreferences`. They still do: `MediaRepositoryImpl` reads the current server id and
  the Next Up limit from it, `JellyfinServerModule` the timeouts, the download engine its
  settings. No task was filed for it.
- **Issues filed on the way**, real bugs outside the refactoring: #907 (an unplayed mark during a
  profile switch may clear the other user's last-played date), #909 (playback reports do not name
  the chosen version), #912 (a download cut off by an unreachable server does not resume when it
  returns).
