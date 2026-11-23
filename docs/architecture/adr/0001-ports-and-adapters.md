# ADR 0001: Ports and adapters, enforced by the build

**Status:** Accepted (2026-11-21, in #733; proposed 2026-11-20)

## Context

Ephyra is developed by AI agents, should get an Emby adapter next to Jellyfin, and has two player
engines that are one idea. Today:

- Nine of ten modules declare the Jellyfin SDK; 85 production files mention it, 56 outside `data`;
  25 more reach it through the `JellyfinApi` wrapper
  ([1-inventory.md](../1-inventory.md), 1.1).
- `MediaRepository` has one implementation that mixes server calls with Room (1.2).
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

## Decisions by the user

All five were decided on 2026-11-21. Nothing in the plan waits for a decision any more.

- **D1. Ids: aliases now, the real choice when the Emby work starts.** The ports use
  `typealias ItemId = UUID` and its siblings. Whether ids become opaque
  (`value class ItemId(val value: String)`, 160 files and the Room columns) is decided once an
  Emby server has been looked at: its item ids are believed to be numeric strings, which nobody
  has checked. Opaque ids now would cost 160 files for no user; keeping `UUID` for good would
  mean a mapping table in an Emby adapter. #773 stays open until then.
- **D2. `MediaRepository` becomes `MediaRepository`, last, and only when the Emby work really
  starts.** With a second server the name says something false, which is what sets it apart from
  the internal names `CLAUDE.md` keeps. Renaming earlier would collide with phase 3, which
  rewrites the same files, and after the rename every upstream change to a file that names the
  repository needs a hand. Until Emby the old name costs nothing. One mechanical PR (#774).
- **D3. The device profile is the adapter's.** It stays Jellyfin's structure, built inside
  `server/jellyfin` from a `PlaybackTarget` and `DeviceCodecs`, which are server-neutral. No
  neutral profile model: with one server in sight it would be Jellyfin's model under other names,
  and `DeviceProfileBuilder` (794 lines, with tests) would be rewritten instead of moved. If
  Emby's profile turns out to be close, the assembly can be shared then, with two examples.
- **D4. Konsist `1.0.0-beta.1`.** It is the release built on the Kotlin version this project
  uses, and it is a beta. Test-only. Accepted.
- **D5. Phase 5 is tested by the agent on the OPPO, with a short check by the user.** For each of
  #764, #765 and #766 the agent runs `docs/mpv-checklist.md` on both backends on the OPPO over
  adb (everything that can be measured: start, resume, seek, tracks, queue, progress reports,
  the refresh-rate vote), and the user then looks and listens for a few minutes (smooth picture,
  audio in sync, HDR). The three PRs go one after the other, and no other session uses the OPPO
  during a run. Accepted gaps: there is no Dolby Vision device since the Xiaomi box went
  (2026-11-15), so Dolby Vision direct play is covered by unit tests of the profile choice only,
  and TV is tested on the emulator with software rendering, which says nothing about decoders,
  HDR, refresh rate or audio passthrough on TV hardware. Phase 5 moves code without changing what
  it does, so the phone run covers the shared logic; a TV device, when there is one again, runs
  the checklist after the fact.

## Consequences

- Adding Emby becomes a new module that implements five ports and passes the contract suites. It
  also needs the server type on the `servers` table, the choice on ids (D1), and server-scoped
  storage of downloads (#718: the tables are keyed by item id alone), since two servers of
  different types can easily use the same ids.
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
