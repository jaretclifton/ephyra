# Ports and adapters

Research into moving Ephyra to a hexagonal (ports and adapters) architecture. Tracked in
[issue #733](https://github.com/midasvo/ephyra/issues/733).

**Status:** research and plan, 2026-11-20. Nothing here is built yet. All counts, paths and line
numbers were taken on `main` at `3c7c205c9`; line numbers drift fast, so the task issues name
functions and give the search command instead.

| Document | Contents |
|---|---|
| [1-inventory.md](1-inventory.md) | What the code does today: every place the Jellyfin SDK crosses a module boundary, what `JellyfinRepository` exposes, how playback, setup, downloads and Cast are tied to Jellyfin, which features need a capability flag |
| [2-enforcement-and-tests.md](2-enforcement-and-tests.md) | How the build enforces the boundaries (Gradle and Konsist, with the Konsist/ArchUnit comparison), the contract test suites, and the test gaps that make a phase risky |
| [3-target.md](3-target.md) | The target module layout and the port interfaces as Kotlin signatures |
| [4-migration.md](4-migration.md) | The phases, what each one costs an upstream sync, and the options that were rejected |
| [adr/0001-ports-and-adapters.md](adr/0001-ports-and-adapters.md) | The decision |

## Why

1. All development here is done by AI agents. Boundaries the build enforces let an agent work on
   one adapter with little context, get fast feedback from pure Kotlin tests, and make it
   impossible to leak a type across a layer.
2. Emby support is wanted later, as a second server adapter next to Jellyfin. The Jellyfin Kotlin
   SDK does not work against Emby, so nothing outside the Jellyfin adapter may depend on SDK
   types.
3. ExoPlayer and mpv are already two implementations of one idea. That boundary should be
   explicit.

What this is not: tactical DDD. Ephyra is a client with a thin domain. There are no aggregates, no
domain events and no use case class per API call. A ViewModel may call a port directly. Use cases
exist only where there is real logic: resolving what to play, reporting progress, the play queue
and the download queue.

## The short version

- The SDK is declared as a dependency by **9 of 10** library and app modules, and imported by
  **85** production files, **56** of them outside `data`. Another **24** files outside `data` reach
  the SDK through the `JellyfinApi` wrapper without importing an SDK type.
- Three enums make up most of the leak outside `data`: `BaseItemKind` (23 files),
  `MediaStreamType` (10) and `PersonKind` (4). They are cheap to replace.
- The expensive leaks are few and specific: `setup` calls the SDK itself, `SyncWorker` and
  `ServerSessionKeeper` in `core` call it, Cast asks for playback info itself, and
  `PlayerViewModel` (2,836 lines, no unit test of its own) builds both engines and does the
  reporting.
- There is no offline repository any more (#281). `JellyfinRepositoryImpl` is one class that mixes
  server calls with Room. The first real cut is inside that class: a server-only part that an Emby
  adapter can replace, and a Room part that stays shared.
- Both engines already implement media3's `Player`. That interface stays the engine port; what is
  missing is a factory boundary and separate packages.
- Enforcement lands first: an allowlist of modules that may declare the SDK (9 today, 1 at the
  end) and frozen baselines of the files that import it (56 and 24 today, 0 at the end). Every
  cleanup PR shortens a list.
