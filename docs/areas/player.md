# Player

Status: works on both engines, phone and TV. As of 2026-11-23 (`main` at `26e62f27a`). Open: the player side of trickplay previews ([#860](https://github.com/midasvo/ephyra/issues/860)).

## Start here

- The player is one `PlayerViewModel` in `player/local` (phone and TV share it). The engine is behind `PlayerEngine` in `engine/`: `engine/exo/ExoEngine.kt` and `engine/mpv/MpvEngine.kt`. Both give a media3 `Player`, so shared code never names an engine (rule R6).
- The queue is `PlaylistManager`, how an item is asked for is `ResolvePlayback`, quality and transcode-audio switches are `StreamSwitches`, trickplay is `TrickplayLoader` (phone) and `TrickplayRegionLoader` (TV).
- Reports to the server are `PlaybackReporter` and `PlaybackStartGate`: see [playback-reporting.md](playback-reporting.md). Resume position and played: [user-data.md](user-data.md). Tracks and subtitles: [subtitles-and-tracks.md](subtitles-and-tracks.md).
- Test fast: `./gradlew :player:local:testDebugUnitTest`. Player PRs also run [docs/mpv-checklist.md](../mpv-checklist.md) on both backends.
- **Open #860:** the player builds its trickplay loaders without the media source, and a failed sheet is asked for again at once: `getTrickplay` and `getTvTrickplay` in `PlayerViewModel.kt`, `TrickplayLoader.sheetLoad`, `TrickplayRegionLoader`. The adapter side is fixed (#878).
- `onMediaItemTransition` in `PlayerViewModel.kt` takes the item before it waits for the previous item's stop, and hands it with its `ItemTransition` to `PlaybackReporter.onTransitionHandled`, which drops it when a switch came meanwhile (#890).

## What it does

ExoPlayer is the default engine, mpv the other (`AppPreferences.playerBackend`); `EngineModule` binds both. `PlaylistManager` builds the queue of a movie or an episode series and `NeighbourLoader` adds the neighbours once the item is ready. `ResolvePlayback` asks the server how to play (direct, a transcode under the chosen cap, or a finished download). A version, quality or audio switch asks for the whole queue again and swaps it in (`StreamSwitches`, `rebuildQueue`; the latest switch wins, #726). `PlaybackService` keeps the media session; with background audio on, `PlaybackSessionStore` keeps the view model alive without the activity. `NetworkRecovery` waits for the network when a stream breaks (#450); `DisplayRefreshRateManager` votes for a refresh rate (#338).

## Where the code is

Paths are under `player/local/src/main/java/dev/jdtech/jellyfin/player/local/` unless they start with `app/`, `server/` or another module.

| Path | What |
|---|---|
| [presentation/PlayerViewModel.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerViewModel.kt) | The player: `initializePlayer`, `onMediaItemTransition`, `onPlaybackStateChanged`, `onPlayerError`, the switches, `getTrickplay` / `getTvTrickplay`, `releasePlayer`. Very large: search by name |
| [engine/PlayerEngine.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/engine/PlayerEngine.kt), [EngineDefaults.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/engine/EngineDefaults.kt) | `PlayerEngine`, `EnginePlayer` (`transitionsOnReplacedPlaylist`, `onItemReady`, `setZoomMode`), the shared defaults |
| [engine/exo/](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/engine/exo/) | `ExoEngine`, `MediaSourceFactory`, `AudioDecoderFallback`, subtitle sources. ExoPlayer keeps its default frame rate strategy |
| [engine/mpv/](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/engine/mpv/) | `MpvEngine`, `MPVPlayer` (media3 `BasePlayer` over libmpv), and one small file per mpv rule: `MpvCallQueue`, `MpvStallWatch`, `MpvStreamBreak`, `MpvErrorRecovery`, `MpvItemTransitions`, `SelectedVideoFrameRate` |
| [domain/PlaylistManager.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/PlaylistManager.kt) | The queue, `getNextPlayerItem`, `jumpTo`, `recreatePlayerItem`, and `toPlayerItem`, which reads the trickplay layout of the played source |
| [domain/ResolvePlayback.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/ResolvePlayback.kt) | `sources` and `pick`: the playback target, engine and cap (#766) |
| [domain/StreamSwitches.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/StreamSwitches.kt), [QueueRebuild.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/QueueRebuild.kt) | `selectBitrate`, `followBitrate`, `rebuildQueue`, `switchFailureText` |
| [domain/TrickplayLoader.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/TrickplayLoader.kt), [TrickplayRegionLoader.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/TrickplayRegionLoader.kt), [TrickplayTiles.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/TrickplayTiles.kt) | Sheet loads (`sheetLoad`, `tileAt`), the TV region loader, the tile arithmetic |
| [domain/](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/) | `NeighbourLoader`, `AutoPlayNext`, `EndOfItem`, `UpNextCountdown`, `SegmentTracker`, `SleepTimer`, `StillWatchingTracker`, `NetworkRecovery`, `DisplayRefreshRateManager`, `DebugSegments` |
| [PlaybackService.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/PlaybackService.kt), [PlaybackSessionStore.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/PlaybackSessionStore.kt) | The media session service; the owner of the view model for the phone player |
| [PlayerActivity.kt](../../app/phone/src/main/java/dev/jdtech/jellyfin/PlayerActivity.kt) | The phone player screen (Views); [PreviewScrubListener.kt](../../app/phone/src/main/java/dev/jdtech/jellyfin/utils/PreviewScrubListener.kt) shows the seek preview; dialogs in [presentation/player/](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/player/) |
| TV [ce/presentation/player/](../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/player/) | `TvPlayerScreen`, `TvPlayerKeyHandler`, `TvTrickplayThumbnail`, `TvSeekBar`, `TvUpNextCard`, `TvResumeOnReturn` ([ADR 0004](../tv-research/adr/0004-tv-player-ui.md)) |
| [http/.../MediaHttpClient.kt](../../http/src/main/kotlin/dev/jdtech/jellyfin/http/MediaHttpClient.kt) | The API's own OkHttp client, provided by `server/jellyfin`. ExoPlayer streams media over it (`MediaSourceFactory` derives its client with `newBuilder()`, so media shares the API's connection pool, #296); mpv uses it only for subtitle files, and libmpv fetches the media itself, with its own TLS. A module of its own so `domain` names no library (#964) |
| [JellyfinPlayback.kt](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/JellyfinPlayback.kt) | `trickplayTile` and `trickplayVersion` (the `mediaSourceId` of a tile request) |
| [PlaybackInfoMappers.kt](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/mappers/PlaybackInfoMappers.kt) | `toItemTrickplayInfo`, `preferredTrickplayInfo`: the width the player gets |
| [MediaRepository.kt](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/MediaRepository.kt) | `getTrickplayData(itemId, width, sheetIndex, mediaSourceId)` |
| [player/core/.../models/](../../player/core/src/main/java/dev/jdtech/jellyfin/player/core/domain/models/) | `PlayerItem`, `Trickplay`, `TrickplayInfo` |

## Rules

- **R6**: `engine/exo` and `engine/mpv` use neither each other, and shared code uses neither; only `engine.di` binds both. A feature of one engine goes behind `EnginePlayer`.
- **R1, R2, R3**: no Jellyfin SDK in the player. The adapter maps SDK types.
- **R10**: ids are `ItemId` and `MediaSourceId`; no `UUID`, no `String` id in a signature. #860 writes `mediaSourceId: String?`; use `MediaSourceId?`.
- **R12**: ticks only through `Ticks`. **R5**, **R19**: `domain` and `application` stay pure Kotlin, with no library but kotlinx; an OkHttp type goes through `http`, not a port.
- Player PRs: [docs/mpv-checklist.md](../mpv-checklist.md) on both backends. Do not fix mpv by touching the ExoPlayer path.
- `PlayerInvariantsTest` fails the build when ExoPlayer loses its default frame rate strategy, when mpv stops hinting the surface with the frame rate (#338), and for other mpv invariants (no calls on the main thread, a network timeout, replaced playlists reported).
- New strings: `translate-strings` skill, all 36 languages.

## How to test it

Unit tests, no device:

```bash
./gradlew :player:local:testDebugUnitTest
./gradlew :server:jellyfin:testDebugUnitTest --tests '*Trickplay*' --tests '*PlaybackSourceMappingTest'
./gradlew :architecture:test
```

Useful classes: `PlayerInvariantsTest`, `StreamSwitchesTest`, `ResolvePlaybackTest`, `PlaylistManagerQueueTest`, `PlaylistManagerNextUpTest`, `NeighbourLoaderTest`, `TrickplayTilesTest`, `NetworkRecoveryTest`, `PlaybackStartGateTest`, `MpvItemTransitionsTest`, `MpvStallWatchTest`. In the adapter: `TrickplayTileRequestTest`, `TrickplaySheetTimeoutTest`, `TrickplayWidthTest`.

On a device, on the test server ([docs/dev-environment.md](../dev-environment.md), `test-server` and `test-devices` skills):

- **Plain Movie**, **Short Movie** (ends at once), **Long Movie**, **Resume Show** (4 episodes, resume positions).
- **Versions Movie**: two versions (360p, 720p), for version and trickplay questions (#860).
- **Hevc Movie**, **Hdr Ten Movie** for decoders.
- Quality switch: a cap below the file's bitrate. Slow link: emulator at 3G (#860). A held request (#890): see [playback-reporting.md](playback-reporting.md#how-to-test-it).
- A real device for refresh rate, HDR and Dolby Vision. mpv needs software rendering on an emulator. Mute first.

[docs/mpv-checklist.md](../mpv-checklist.md) is the list: resume, from 0, seek, media session, refresh rate vote (`dumpsys SurfaceFlinger`), back out; plus playlist, autoplay, quality switch and slow link when the PR touches them.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :architecture:test
./gradlew :player:local:testDebugUnitTest :server:jellyfin:testDebugUnitTest
./gradlew :app:phone:compileLibreDebugKotlin :app:tv:compileLibreDebugKotlin
```

Add `:app:phone:compileProprietaryDebugKotlin` when Cast code or the `player/core` models change. Then the mpv checklist on both backends. When no review bot ran, a player PR gets an independent review (CLAUDE.md).

## Decisions and history

- [#126](https://github.com/midasvo/ephyra/issues/126), [#134](https://github.com/midasvo/ephyra/issues/134): the TV player on the shared view model (ADR 0004).
- [#267](https://github.com/midasvo/ephyra/issues/267), [#284](https://github.com/midasvo/ephyra/issues/284), [#305](https://github.com/midasvo/ephyra/issues/305): a faster start left mpv in `BUFFERING`; the reason the checklist exists.
- [#338](https://github.com/midasvo/ephyra/issues/338): the refresh rate vote, pinned by `PlayerInvariantsTest`. [#450](https://github.com/midasvo/ephyra/issues/450): wait for the network, then reload.
- [#657](https://github.com/midasvo/ephyra/issues/657): neighbours load when the item is ready. [#723](https://github.com/midasvo/ephyra/issues/723), [#726](https://github.com/midasvo/ephyra/issues/726): switches on mpv; the latest switch wins. [#837](https://github.com/midasvo/ephyra/issues/837): a default quality per network.
- [#764](https://github.com/midasvo/ephyra/issues/764), [#765](https://github.com/midasvo/ephyra/issues/765), [#766](https://github.com/midasvo/ephyra/issues/766): `PlayerEngine`, `PlaybackReporter` and `ResolvePlayback` out of `PlayerViewModel`. [#870](https://github.com/midasvo/ephyra/issues/870) / [#889](https://github.com/midasvo/ephyra/pull/889): mpv reports a replaced playlist itself.
- [#860](https://github.com/midasvo/ephyra/issues/860) / [#878](https://github.com/midasvo/ephyra/pull/878): the adapter sends the version, compares widths as numbers and gives sheets 30 s; the player part is open.

## Pitfalls

- **#860, the player part.** `getTrickplay` and `getTvTrickplay` (`PlayerViewModel.kt`) build the loaders with `itemId` only. `TrickplayLoader.sheetLoad` and `TrickplayRegionLoader` call `repository.getTrickplayData(itemId, width, sheetIndex)` with no `mediaSourceId`, so a second version shows the first version's images. The layout is read for the played source (`PlaylistManager.toPlayerItem`, `trickplayInfo?.get(mediaSource.id)`), the tiles are not. `sheetLoad` removes a failed load in `finally`, so the next scrub frame asks again at once; it catches `Exception` only, so a sheet too large to decode (an out-of-memory error) escapes it. `PreviewScrubListener` decodes too. The width as text is fixed (`preferredTrickplayInfo`).
- **A switch during a stop (#890).** `onMediaItemTransition` finds `item` in `items`, waits for the previous item's stop, then calls `onTransitionHandled(item, ..., transition)`; a switch that lands in that wait makes `item` stale, and the reporter drops it by the transition's number. Details: [playback-reporting.md](playback-reporting.md#pitfalls). Both backends.
- **A quality switch is a new playlist.** ExoPlayer reports a transition to the same item; mpv reports none (`transitionsOnReplacedPlaylist`). Code keyed on transitions must handle both (#870).
- **Find an item by id, not by index.** Indexes shift when neighbours load (#869).
- **mpv is not called on the main thread after init** (`MpvCallQueue`), and its state can drift from media3's: read the log, not the picture (checklist).
- **The refresh rate vote differs per engine:** ExoPlayer keeps its default strategy, mpv hints the surface itself. An mpv change that loses the hint passes every other test (#338).
- **A platform audio decoder that fails mid-stream** is dropped for ffmpeg (`AudioDecoderFallback`), per OS build.
- **Emulators:** host GPU rendering crashes mpv; on Android 17 images ExoPlayer cannot decode H.264 (`docs/dev-environment.md`).
