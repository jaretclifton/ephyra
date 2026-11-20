# ADR 0001: Ports and adapters, enforced by the build

**Status:** Proposed (2026-11-20, in #733)

## Context

Ephyra is developed by AI agents, should get an Emby adapter next to Jellyfin, and has two player
engines that are one idea. Today:

- Nine of ten modules declare the Jellyfin SDK; 85 production files mention it, 56 outside `data`;
  24 more reach it through the `JellyfinApi` wrapper
  ([1-inventory.md](../1-inventory.md), 1.1).
- `JellyfinRepository` has one implementation that mixes server calls with Room (1.2).
- `setup`, the sync worker, the session keeper and Cast call the SDK themselves (1.1, 1.4).
- Both engines implement media3's `Player`, but `PlayerViewModel` builds them, checks for
  `MPVPlayer` in three places and has no unit test of its own (1.3).

## Decision

1. **Ports and adapters, without tactical DDD.** The app's own models and the ports live in a
   pure Kotlin `domain` module. A ViewModel calls the repository or a port directly. Use cases
   exist for four things only: resolving what to play, reporting playback, the play queue and
   the download queue.
2. **One adapter module per server type.** `server/jellyfin` is the only module that declares the
   SDK. It implements five small ports (`CatalogPort`, `UserDataPort`, `PlaybackPort`,
   `ServerSession`, `ServerConnector`) behind one `MediaServer`. It does not know Room.
3. **The repository stays, as the composition of a server with Room.** The stored fallback, the
   local sources and write-then-report are server-neutral and are written once.
4. **media3's `Player` is the engine's control surface.** The port around it is a
   `PlayerEngine` factory and an `EnginePlayer` with the few calls `Player` lacks. The engines
   get their own packages.
5. **The server decides direct play or transcode; the client says who plays.** The port takes a
   `PlaybackTarget` (local player with an engine, download, Cast receiver). Each adapter turns it
   into what its server needs. The device profile stays Jellyfin's, inside the adapter.
6. **Gradle is the boundary; Konsist is the ratchet.** A module that does not declare the SDK
   cannot use it. Konsist rules with exact baselines hold the line while a module still has the
   dependency, and cover what Gradle cannot say (2.1).
7. **Enforcement lands before cleanup**, and every cleanup PR shortens a baseline.
8. **Moved files keep their package**, so a move changes no import and an upstream change still
   merges.
9. **Capabilities are data.** A `ServerCapabilities` value per server says what it can do; the UI
   hides what the server lacks. No version checks spread through the UI.

The signatures are in [3-target.md](../3-target.md), the phases in
[4-migration.md](../4-migration.md).

## Open decisions

These are the user's to make. Only D4 blocks the start.

- **D1. Ids.** Keep `UUID` (an Emby adapter would have to map its ids onto UUIDs), or make ids
  opaque (`value class ItemId(val value: String)`, 160 files). Proposed: aliases now, decide when
  the Emby work starts and its ids have been looked at on a real server. Blocks only the last
  task of phase 7.
- **D2. The name `JellyfinRepository`.** `CLAUDE.md` says the internal names stay on purpose. With
  a second server this one becomes false. Proposed: rename to `MediaRepository` in one mechanical
  PR at the end of phase 7. Blocks nothing.
- **D3. The device profile.** Keep it Jellyfin's, built inside the adapter from a
  `PlaybackTarget` (proposed), or write a server-neutral profile model now. Blocks one task in
  phase 3.
- **D4. Konsist `1.0.0-beta.1`.** It is the release built on the Kotlin version this project
  uses, and it is a beta. Test-only. Proposed: accept. Blocks the first task.
- **D5. Device tests for phase 5.** Every playback PR needs `docs/mpv-checklist.md` on both
  backends, and mpv does not run on an emulator with a host GPU. Who runs it, on which phone?
  Blocks phase 5.

## Consequences

- Adding Emby becomes a new module that implements five ports and passes the contract suites. It
  also needs the server type on the `servers` table, a decision on ids (D1), and server-scoped
  lookups of stored rows, since two servers of different types can use the same ids.
- An agent that works in `server/jellyfin` needs `domain` and nothing else. An agent that works
  in a ViewModel cannot see the SDK.
- `domain` and the use cases are tested without Android. The adapter is tested against recorded
  answers.
- Three modules are added (`domain`, `server/jellyfin`, `architecture`) and later `testing`.
  `CLAUDE.md` and the upstream-sync skill have to describe the layout.
- Upstream merges get harder for about 35 moved files and impossible for `PlayerViewModel`
  ([4-migration.md](../4-migration.md), 4.4). Translations are not affected.
- The work is about 40 pull requests, none of which a user notices. The risk is concentrated in
  phase 5.
