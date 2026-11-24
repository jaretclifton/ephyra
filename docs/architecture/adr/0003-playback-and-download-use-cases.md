# ADR 0003: Use cases for playback and downloads

**Status:** Proposed (2026-11-25, in #970). Refines ADR 0001, decision 1, and ADR 0002, decision 3.
Nothing is built yet; the PRs are in "The order of the PRs" below.

## Context

`application` is almost only interfaces. The rules of playback and downloads sit in four large
classes, where they are hard to test and easy to break:

| Class | Lines on `main` (`41de995ad`) | Unit tests of its own |
|---|---|---|
| `PlayerViewModel` (`player/local/.../presentation/`) | 2583 | none of its logic: `PlayerErrorTest` covers the error events, `PlayerInvariantsTest` reads its source with regular expressions |
| `CastPlayerControllerImpl` (`player/cast/src/proprietary`) | 1097 | none; only its pure helpers are tested |
| `DownloaderImpl` (`downloads/.../utils/`) | 1529 | Robolectric, with a real engine over OkHttp fakes |
| `DownloadQueue` (`downloads/.../core/presentation/downloader/`) | 1004 | Robolectric, `runBlocking` and `Thread.sleep` loops: no clock or dispatcher can be injected |

Most regressions fixed between 2.8.0 and 2.9.0 were in this code. What each one broke, and where
that rule lives now:

| Issue | The rule that was broken | Where it lives now | Pure test |
|---|---|---|---|
| #601 | No start before the first ready state; no progress or stop before a start | `PlaybackStartGate`, inside `PlaybackReporter` | `PlaybackStartGateTest` |
| #674 | A pause or resume is reported at once; a buffering stall is not | `ReportedPause`; the trigger is `onPlayWhenReadyChanged` in the view model | `ReportedPauseTest`; the trigger only by regex |
| #868 | The start carries the player's position when it is sent | `PlaybackReporter.sendPlaybackStart` | `PlaybackReporterTest` |
| #869 | The item being left reports its last position, never the next file's 0 | `MpvItemLeftPosition` (the mpv engine) | `MpvItemLeftPositionTest` |
| #870 | Every new stream of the playing item gets one start, with or without a transition | `PlaybackReporter.onStreamReplaced`; the call is in `swapInQueue` in the view model | `PlaybackReporterTest` |
| #890 | The previous item's stop reaches the server before the next start; only the newest transition sends one | `PlaybackReporter` (`ItemTransition`, `stopsInFlight`) | `PlaybackReporterTest` |
| #909 | Every report names the version that plays | `PlaySessions` (`domain`) | `PlaySessionsTest` |
| #726 | Only the newest switch is applied; a failed switch undoes only its own change | `StreamSwitches` | `StreamSwitchesTest` |
| #848 | A download is finished only when its finalize succeeded | split over `DownloadQueue` (the outcome) and four places in `DownloaderImpl` | Robolectric only |
| #850 | A resume sends the stored validator as `If-Range` | `ResumeValidators` and the engine | `ResumeValidatorsTest` |
| #912 | A download that failed for want of an answer goes on when the server is back | `DownloadQueue` (`retryWhenServerReturns`, `afterFailure`) | Robolectric only |

Two things follow. First, the playback rules that broke were moved into small classes as they were
fixed (#765, #766 started that), and those classes are tested; what is left in the view model is
the wiring that decides *when* each rule runs, and several flows whose order lives only there (the
end of an item, the track choice). Second, the download rules were fixed in place: the life of one
download is spread over an in-memory `EntryState`, the engine's `DownloadStatus` numbers, a second
`DownloadStatus` enum for the screens, the file suffix and two Room tables, and none of it is
tested without Robolectric.

The playback rules also exist a second time in Cast. `CastPlaybackReporter` is its own decider for
start, progress and stop; Cast reloads at a position for an audio switch on its own; it reuses
`PlaylistManager` and `SegmentTracker` from `player/local`. Only #909 was fixed on both sides.

The player's `domain` package is not the domain layer: it is a package of `player/local`, an
Android module, and `PlaylistManager`, `PlaybackReporter`, `PlaybackStopReport` and `EndOfItem`
import media3 constants. A rule there cannot be used by `application` and is not covered by R5 or
R19.

## Decision

1. **A use case is a flow with rules or state of its own, nowhere else.** ADR 0001 (decision 1)
   named four: resolving what to play, reporting playback, the play queue and the download queue.
   ADR 0002 (decision 3) kept "no use case per call". This ADR keeps both and names the flows that
   qualify (the inventory below). A view model still calls `MediaRepository` or a port directly
   for a plain read or write (a chapter image, the segments of an item, a favourite mark). No class
   is written only to pass a call through.
2. **Two shapes.** A *rule* is a pure function or value in `domain`: no coroutines, no clock, time
   as a parameter (as `PlayState` already is). A *service* is a class in `application` over ports,
   with the state of one flow, an injected scope, dispatcher and clock, tested with `runTest`.
   Details in "How a use case is shaped".
3. **The view model keeps the screen; the engine keeps the engine.** A view model holds the state
   the screen shows, maps the user's actions to services, and maps media3's events and track
   groups to our own types. An engine (ExoPlayer, mpv, the Cast receiver, the download engine)
   does I/O and reports what happened. Neither decides what a rule decides.
4. **The life of one download is a state machine in `domain`**, with the engine as an adapter that
   only does I/O. `DownloadState` stays what Room can tell (no schema change in this series); the
   machine refines its `DOWNLOADING` value.
5. **One rule set for both players.** The local player and Cast call the same services and rules.
   Where Cast behaves differently today, the move keeps the difference as an explicit input, and a
   change of behaviour is a separate `fix` PR with its own issue.
6. **No change of behaviour in a move.** Each PR moves one flow, keeps the existing tests green
   and adds pure tests. A bug found on the way is filed, not fixed in the move.
7. **New code goes in packages by layer and area now** (`dev.jdtech.jellyfin.domain.playback`,
   `dev.jdtech.jellyfin.domain.downloads`, `dev.jdtech.jellyfin.application.playback`,
   `dev.jdtech.jellyfin.application.downloads`), so #971 does not move it again (Q2).

## Inventory and decision per flow

Line numbers are those of `main` at `41de995ad`. "VM" is `PlayerViewModel.kt`.

### Playback

**P1. Resolving what plays.**
- *Where:* `ResolvePlayback` (109 lines: `sources`, `pick`, the cap per network of #837, the
  preferred audio language); `PlaylistManager.toPlayerItem` and `withChosenAudio` (497 to 633),
  which may ask the server a second time for the chosen audio; VM `initializePlayer` (708 to 773)
  and `toMediaItem` (779 to 828). Cast: `CastPlayerControllerImpl.playItem` (754 to 827) resolves
  each item through the local `PlaylistManager`, with the local target and cap, and then asks
  again through `MediaServer.playback` for `PlaybackTarget.CastReceiver` (`receiverSource`, 640 to
  660), keeping only the source id and the start position of the first answer.
- *Depends on:* `MediaRepository`, `AppPreferences`, `AppVariant`, `NetworkConnectivity`;
  `PlayerItem` (Parcelable, `Uri`).
- *Needed by:* the local player (phone, TV), Cast, the downloads' quality choice (the cap).
- *Tests:* `ResolvePlaybackTest`, `PlaylistManagerAudioChoiceTest`,
  `PlaylistManagerUnfinishedDownloadTest`. *Bugs:* #766, #837, #860 (the played version).
- **Decision: a use case in `application`, ResolvePlayback**, one operation: an item id, a wanted
  source, a playback target, a cap and an audio stream in; a resolved stream out (the source, the
  URL, transcoded or not, the play session, the cap asked for, the audio streams), in our own
  types. The settings come through the player settings port of #969. Building a `PlayerItem` and
  a `MediaItem` from the result stays in `player/local`. Cast keeps its two requests in the move;
  asking once is a fix of its own.

**P2. The queue and the next episode.**
- *Where:* `PlaylistManager` (672 lines: `getInitialItem` 105 to 272, `loadQueue` 273,
  `getPreviousPlayerItem` / `getNextPlayerItem` 294 to 379, the next season 380, `jumpTo` 440,
  shuffle); `NeighbourLoader`; VM `playQueueItem`, `playEpisode`, `jumpToQueued` (835 to 877) and
  the deferred neighbour work (1175 to 1216). Cast: `manageQueue` (833 to 863) over the same
  `PlaylistManager`, plus `CastPlaylistOrder`, because a `PlaylistManager` hands out each
  neighbour once.
- *Depends on:* `MediaRepository`, P1, `Timber`, media3's `C` and `androidx.core.net`.
- *Needed by:* the local player, Cast, the TV player's queue row.
- *Tests:* `PlaylistManagerQueueTest`, `PlaylistManagerNextUpTest`, `PlaylistManagerNextSeasonTest`,
  `PlaylistManagerShuffleTest`, `NeighbourLoaderTest`, `CastPlaylistOrderTest`. *Bugs:* #657 (the
  neighbours of an item left early), an item found by index instead of id (the pitfall in the
  player guide).
- **Decision: a service in `application`, the play queue**, with the list of items, the current
  one, previous and next, the next season and shuffle, over the catalog role and P1. It hands out
  items, not `PlayerItem`s; the mapping stays in `player/local`. Inserting into the player's
  playlist stays in the view model. The start position rule (the saved state, the item's resume
  position, from the beginning: VM 755 to 760) moves with it.

**P3. The reports to the server.**
- *Where:* `PlaybackReporter` (420 lines: `onItemTransition` 160, `onTransitionHandled` 182,
  `onStreamReplaced` 204, `sendPlaybackStart` 237, `reportStopped` 286, `reportProgress` 362),
  `PlaybackStartGate`, `ReportedPause`, `playbackStopReport`; in the VM `releasePlayer` (901 to
  935), `refreshPlayingSnapshot` and its outro variant (946 to 967), `reportProgress` (998 to
  1004), the transition (1086 to 1172), the ready state (1696 to 1700), `swapInQueue` (2107) and
  the background loop (2490 to 2498); the phone's 5 s loop in `PlayerActivity`, TV's
  `TvProgressReports`. Cast: `CastPlaybackReporter` (190 lines, pure, with a clock) and its
  sender in `CastPlayerControllerImpl` (462 to 544).
- *Depends on:* `MediaRepository` (`postPlaybackStart`, `postPlaybackProgress`,
  `postPlaybackStop`, `setPlaySession`), `PlaybackEvents`, an application scope (a stop outlives
  the player), the IO dispatcher, media3's `C`, `PlayerItem`, `Timber`.
- *Needed by:* the local player (phone, TV), Cast.
- *Tests:* `PlaybackReporterTest`, `PlaybackStartGateTest`, `PlaybackStopReportTest`,
  `ReportedPauseTest`, `CastPlaybackReporterTest`; the call sites only through
  `PlayerInvariantsTest`. *Bugs:* #601, #674, #868, #869, #870, #890, #909: seven of the
  eleven above.
- **Decision: a service in `application`, the playback reports** (the reporter as it is, with a
  stream value of our own instead of `PlayerItem`, a missing duration as null instead of
  `C.TIME_UNSET`, logging through a port). The stop's position and percentage
  (`playbackStopReport`) and the start gate become rules in `domain`. Hilt provides it unscoped in
  `player/local/.../di/`, as today. What stays in the view model: telling the service what
  happened, and reading the player's position for it (`ReportedPlayer`). Cast's decider then uses
  the same stop rule and gate; its differences (no position on the start, no `PlaybackEvents`, a
  percentage clamped to 0..100) stay as inputs until their own fix.

**P4. Switching quality, audio and version (the reload at a position).**
- *Where:* `StreamSwitches` (204 lines: `selectBitrate`, `followBitrate`, `playingAt`,
  `selectAudio`), `QueueRebuild`; VM `switchTranscodeAudio` (2064 to 2084), `swapInQueue` (2092 to
  2114), `selectMaxBitrate` and the network quality (2135 to 2172), the host (2178 to 2234). Cast:
  `setAudioTrack` (1007 to 1074) reloads the current item at its position with its own
  cancellation. A version is chosen only at the start, on both players.
- *Depends on:* a scope, `PlayerItem`, `UiText` for the failure text, P1, P2, the stored cap.
- *Needed by:* the local player; Cast for audio.
- *Tests:* `StreamSwitchesTest`, `QueueRebuildTest`, `MpvPlaylistLoadTest`. *Bugs:* #723, #726,
  #580, #837, #870 and #890 (both triggered by a switch).
- **Decision: a service in `application`, the stream switches**, over P1 and P2: the newest switch
  wins, a failure undoes its own change and comes back as a result with the `ServerException`; the
  view model turns it into a text. The reload itself (`setMediaItems` at the position, `prepare`,
  `playWhenReady`) stays in the view model, because it is the engine's. Cast's audio switch joins
  it after P3's Cast step.

**P5. Segments and skipping.**
- *Where:* `SegmentTracker` (167 lines, already pure), `segmentTrackerFrom` (reads
  `AppPreferences`); VM `updateCurrentSegment` (1013 to 1078), `getSegments` (2236 to 2249),
  `skipSegment` and `skipTo` (2323 to 2345), `skipButtonText` (2347 to 2359). Cast:
  `CastSegmentSkipper` wraps the same tracker with guards of its own for receiver positions.
- *Depends on:* the settings (#969), the item's duration and whether a next item exists.
- *Needed by:* the local player, Cast.
- *Tests:* `SegmentTrackerTest`, `CastSegmentSkipperTest`, `CastViewModelSegmentTest`.
  *Bugs:* #271, #298, #616, #681, #851.
- **Decision: already a rule; it moves to `domain` as it is.** The decision about what an
  automatic outro skip does (the sleep timer, the up next card, autoplay off: VM 1042 to 1076)
  joins P7. The skip label stays in the view models (it is a string resource).

**P6. Track choice and remembered preferences.**
- *Where:* `SeriesTrackPreference.kt` (`matchSeriesTrack`, `seriesTrackChoice`,
  `audioStreamIndex`, `serverAudioTracks`), `TrackPicks.kt` (`SessionTrackPicks`); in
  `PlaylistManager` `withChosenAudio`, `preferredAudio`, `savedAudioChoice` (596 to 662); VM
  `rememberTrackChoice` (1804 to 1850), `onTracksChanged` (1852 to 1913),
  `seriesTrackPreferences` (1967 to 1995), `trackChoices` and `switchToTrack` (2001 to 2057). Cast
  starts with the server's default tracks and does not apply the remembered choice.
- *Depends on:* `MediaRepository` (`saveSeriesTrackPreference`, `getSeriesTrackPreferences`,
  `getUserId`), the preferred audio language, media3's track type numbers (`TrackPick.trackType`
  is a media3 `C.TRACK_TYPE_*` value).
- *Needed by:* the local player; Cast (not today).
- *Tests:* `SeriesTrackPreferenceTest`, `SessionTrackPicksTest`, `PlaylistManagerAudioChoiceTest`;
  the order of apply, reset and read in `onTracksChanged` has no test. *Bugs:* #296, #580, #596,
  #597.
- **Decision: a service in `application`, the track preferences**: given an item (series or not)
  and the candidate tracks per kind, which one to select; given a pick, what to remember and
  where. Our own kind of track (audio, subtitle) instead of media3's numbers. Matching media3's
  track groups to candidates, and building `TrackSelectionOverride`s, stays in the view model.

**P7. The end of an item: autoplay, up next, still watching, the sleep timer.**
- *Where:* the pieces are pure and tested: `AutoPlayNext`, `EndOfItem` (imports media3's
  `Player`), `UpNextCountdown`, `StillWatchingTracker`, `SleepTimer`. Their order is only in the
  VM: `onPlayWhenReadyChanged` (1225 to 1282: the sleep timer, then a cancelled up next card, then
  autoplay off, then still watching), `advanceOrAskStillWatching` (1288 to 1307), `updateUpNext`
  (1314 to 1362), `autoAdvanceToNextItem` and `endItemWithoutAdvancing` (1392 to 1422), the prompt
  and the timer (1424 to 1535), the outro auto-skip branch (1042 to 1076) and
  `onPositionDiscontinuity` (2502 to 2523).
- *Depends on:* the clock (`System.currentTimeMillis`, `SystemClock.elapsedRealtime`), the settings,
  the server's autoplay setting.
- *Needed by:* the local player (phone, TV). Cast ignores autoplay today.
- *Tests:* each piece has one (`AutoPlayNextTest`, `EndOfItemTest`, `UpNextCountdownTest`,
  `StillWatchingTrackerTest`, `SleepTimerTest`); the order has none. *Bugs:* #272, #298, #477,
  #616, #677.
- **Decision: a rule in `domain`, the end of an item**: the facts (a next item, autoplay, the sleep
  timer, the up next card, the still watching counters, now) in, one decision out (stay paused for
  the timer, end without advancing, ask, advance). The view model carries the decision out. This is
  the largest untested rule in the view model, and it is cheap to test.

**P8. Resume position rules.**
- *Where:* `PlayState`, `PlayStop` and `ResumeThresholds` in `domain` (#637), applied in
  `MediaRepositoryImpl.postPlaybackStop`; `PlayingSnapshot`'s unknown duration (#461); the start
  position (VM 755 to 760, `PlaylistManager` 253 to 255); the engine's last position on mpv (#869).
- **Decision: already done** for what is stored (ADR 0002, R14). The start position moves with P2.
  The engine's position stays in the engine.

**Not use cases, and why.** The network wait during playback (`NetworkRecovery` is already a
tested rule; the `ConnectivityManager` callback in VM 1583 to 1671 goes behind a port in #969 or
#998). Trickplay, chapters, the subtitle sync sheet's cues and the display refresh rate: screen or
engine work, without a rule another flow needs. The media session and `PlaybackService`: Android.

### Downloads

**D1. The life of one download.**
- *Where:* `DownloadQueue.EntryState` (86 to 96: Pending, Downloading, Paused, Completed,
  Failed) and the flags on `Entry` (98 to 145: `retryCount`, `retryAt`, `waitingForNetwork`,
  `retryWhenServerReturns`); the transitions in the pump (551 to 820), `afterFailure` (989 to
  1002), retry (448 to 497), remove (399 to 445), restore (281 to 342); the engine's
  `DownloadStatus` numbers on an in-memory task; a second `DownloadStatus` enum for the screens
  (`DownloadProgress.kt`); `DownloadState` from the row's type and path; the finalize in
  `DownloaderImpl` (1182 to 1444).
- *Persisted today:* a `pending_downloads` row (queued); a `LOCAL` source with the partial suffix
  and a `downloadId` (started, not finished); the final path and `downloadedAt` (finished). Room
  (version 21) cannot tell running, paused, failed and finalizing apart.
- *Depends on:* `System.currentTimeMillis`, `System.nanoTime`, `delay`, a hard-coded
  `Dispatchers.IO` scope (`DownloadQueue` 158), `Context` (the pump service, notifications).
- *Tests:* `DownloadQueue*Test` (cancel, finalize, HTTP error, network, retry transition, server
  return), `DownloadFinalizeFailureTest`, `SubtitleFinalizeTest`, all on Robolectric.
  *Bugs:* #383, #426, #530, #560, #573, #848, #912.
- **Decision: a state machine in `domain`** (below), with the queue as the only caller and the
  engine and `DownloaderImpl` doing what its effects say.

**D2. The queue.**
- *Where:* `DownloadQueue`: order (`sort`, 517 to 529), slots (`maxConcurrentDownloads`, a paused
  download keeps its slot, 785 to 790), the network (the queue checks only online, 783 and 858; the
  engine checks metered and roaming, `shouldPauseTransfer`), retry and backoff (three retries, 30 s,
  2 min, 10 min, `afterFailure`), the server's return (#912), the restore after process death,
  deduplication by item id (#718), the pump's poll interval (`DownloadSpeed`).
- **Decision: a service in `application`, the download queue**, over the store role of #967, the
  `Downloader` port, `DownloadSettings`, `NetworkConnectivity` and `ServerAvailability`, with its
  scope, dispatcher and clock injected. The choice of what starts next is a rule in `domain`. The
  pump service and the failure notification go behind two small ports implemented in `downloads`.
  The metered and roaming rule becomes one function in `domain` that the engine calls, so there is
  one network rule; who applies it does not change.

**D3. The engine.** `MediaDownloadEngine`: OkHttp, Range and `If-Range` (#850), pausing on a
network the settings forbid. **Decision: stays an adapter**, doing I/O only; it reports running,
paused, progress, succeeded and failed (with the HTTP status), which become the machine's events.

**D4. `DownloaderImpl`.** Pure rules inside an I/O class: the orphan sweep (76 to 147), the paths
(1481 to 1510), which subtitles are downloaded (683 to 685, again 830 to 843), progress as a
percentage (written twice, 594 to 652). **Decision: the rules go to `domain`; the class stays the
adapter** that writes rows and files. The order of the finalize (rename, write the row, undo the
rename when the write fails, the subtitles of #573) becomes the effects of the machine's
finalizing stage; the file operations stay here.

**D5. Smart downloads.** `SmartDownloadPlanner` is pure and tested (`SmartDownloadPlannerTest`).
**Already done**; it moves to `domain` with the package pass.

### The download state machine

States (`DownloadStage`, a sealed type in `domain`):

- **Queued** (`retryCount`, `retryAt`, `waitingForNetwork`, `smart`): waits for a slot.
- **Running** (`downloadId`, progress): the engine transfers.
- **Paused** (`downloadId`): the engine stopped for the network; keeps its slot.
- **Finalizing** (`downloadId`): the engine finished; rename and rows in progress.
- **Finished**: the final file and row exist.
- **Failed** (`reason`: refused with a status, no answer, finalize failed, local such as storage):
  waits for the user, or for the server when the reason is no answer.

Events: `Enqueue`, `Start` (a slot is free, `now`), `StartFailed` (online or not, the reason),
`EngineRunning`, `EnginePaused`, `EngineProgress`, `EngineSucceeded`, `EngineFailed` (HTTP
status, permanent), `Finalized`, `FinalizeFailed`, `NetworkChanged`, `ServerConfirmed`, `Retry`,
`Cancel`, `Delete`, `Restored` (process start, from what Room has).

| From | Event | To | Effects (done by the queue, the engine or `DownloaderImpl`) |
|---|---|---|---|
| (none) | Enqueue | Queued | save the pending row (deduplicated by item id) |
| Queued | Start, `retryAt` passed, online | Running | delete the pending row, start the engine |
| Queued | Start, offline | Queued (`waitingForNetwork`) | save the pending row again |
| Queued | NetworkChanged | Queued (flag follows) | none |
| Queued | StartFailed, online | Failed (local or no answer) | delete the rows and the engine task |
| Running | EnginePaused | Paused | none |
| Paused | EngineRunning | Running | none |
| Running, Paused | EngineProgress | same | publish progress |
| Running, Paused | EngineSucceeded | Finalizing | finalize |
| Finalizing | Finalized | Finished | publish a data change |
| Finalizing | FinalizeFailed | Failed (finalize failed) | keep the partial file and row |
| Running, Paused | EngineFailed, retries left, not permanent | Queued (`retryCount + 1`, `retryAt = now + backoff`) | save the pending row |
| Running, Paused | EngineFailed, permanent or no retries left | Failed (refused, or no answer) | notify |
| Failed | Retry | Queued (counters reset) | save the pending row |
| Failed (no answer) | ServerConfirmed | Queued (counters reset) | save the pending row |
| any but Finished | Cancel | (removed) | cancel the engine, delete the partial files and rows |
| Finished | Delete | (removed) | delete files and rows, cascade to season and show |
| (process start) | Restored from a pending row | Queued | none |
| (process start) | Restored from a partial row | Queued (resume by Range) | none |
| (process start) | Restored from a partial row whose final file exists | Finalizing | finalize (the #848 repair) |

Every other pair is not legal. The machine returns the state unchanged and no effects, and the
queue logs it: an engine report that races a cancel must not crash. Today's pump already guards the
race of #912 that way (`DownloadQueue` 760 to 768).

**`DownloadState` and Room.** `DownloadState` (`NONE`, `DOWNLOADING`, `FINISHED`) stays what it is:
the stored view of a source, read by `isDownloaded()`, `ResolvePlayback` and the item buttons, and
the only place that knows the suffix (R13). The machine refines `DOWNLOADING` into Queued, Running,
Paused, Finalizing and Failed; each stage maps back to the `DownloadState` its rows have, and
`Restored` maps forward as `restoreAll` does today. **No schema change in this series**: what Room
stores carries every state that survives a restart today, and Failed, Paused and the retry
counters are lost at process death today as well. Keeping Failed across a restart would change
what the user sees after one (today such a download comes back as Queued and starts again), so it
is its own decision (Q3), with a schema version 22.

## How a use case is shaped

- **A rule** is a top-level function, or a value with methods, in `domain`. It reads no clock and
  launches nothing: `now` is a parameter. It is tested with plain JUnit Jupiter tables.
- **A stateless use case** is a class named by what it does (`ResolvePlayback`), with one public
  operation, usually `suspend`. It takes the ports it uses, and nothing else, in its constructor.
- **A service with state** (the play queue, the reports, the switches, the download queue) is a
  small class whose public operations are the events of its flow (`onItemTransition`, `retry`).
  It exposes its state as a `StateFlow` of its own type, and a one-off outcome as a return value or
  a `Flow` of events. The view model turns that into the screen's state and its `Channel` of
  events.
- **Scope, dispatcher, clock.** A service takes `CoroutineScope` only when its work must outlive
  the caller (a stop report outlives the player: today `@ApplicationScope` in
  `PlaybackReporterModule`); otherwise it suspends and the caller's scope runs it. It takes a
  `CoroutineDispatcher` for blocking work and a `clock: () -> Long` for time, as
  `ExternalRatingsService` does. Defaults appear only in the constructor (as `ProfileLock` and PR
  987 do: the production constructor passes `Dispatchers.IO`), so a test passes a
  `StandardTestDispatcher`, `backgroundScope` and `testScheduler.currentTime` and runs under
  `runTest` with `advanceUntilIdle()`, with no real dispatcher left running.
- **Errors.** A port throws `ServerException` for an I/O failure. A service catches it only where
  its rule says what happens then (a report is logged and dropped; a switch is undone and returns
  a failure that carries the exception); otherwise it lets it through. `CancellationException` is
  always rethrown. Texts for the user (`LoadErrorText`, `switchFailureText`) are made outside
  `application`, from the exception.
- **Logging.** `application` has no logger (R19). A service that logs takes a log port, as the
  stored fallback takes `FallbackLog`, implemented over Timber in `core` (Q5).
- **Hilt.** `application` declares no Hilt annotation and no `@Inject` (R19: no library but
  kotlinx). A feature module provides each service with `@Provides` in its `di/` package, as
  `ExternalRatingsModule` in `core` provides `ExternalRatingsService`: the player's services in
  `player/local/.../di/` (unscoped, one per player, as `PlaybackReporter` is now), the download
  queue in `downloads/.../di/` as a `@Singleton`. Cast takes the same bindings, since
  `player/cast` depends on `player/local`.
- **Constraints.** R5 and R19: a service in `application` imports only Kotlin, kotlinx, the JDK and
  our own code, so no media3, no `Uri`, no `Parcelable`, no `PlayerItem`: it gets values of our own
  (ids, positions in milliseconds, a missing duration as null). R6: shared player code imports no
  engine package; the services cannot, because they are not in `player/local` at all. The Cast
  flavours: a service is compiled once in `application` and used from `player/cast/src/proprietary`
  and `src/main`; `src/libre` needs nothing new. Nothing in a service names a Cast type: the
  receiver is `PlaybackTarget.CastReceiver` of `domain`.

A sketch of the reports service, to show the shape (not compiled):

```kotlin
// application, package dev.jdtech.jellyfin.application.playback (sketch)
class PlaybackReports(
    private val reports: PlaybackReportsRole,        // the role of MediaRepository after #967
    private val events: PlaybackEvents,
    private val reportScope: CoroutineScope,         // outlives the player
    private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) {
    private val _playing = MutableStateFlow<PlayingSnapshot?>(null)
    val playing: StateFlow<PlayingSnapshot?> = _playing.asStateFlow()

    /** The item changed; returns the transition to hand back once the item is known. */
    fun onItemTransition(newId: ItemId?): ItemTransition { /* stop the old one, gate the new */ }

    suspend fun onTransitionHandled(stream: QueuedStream, ready: Boolean, player: ReportedPlayer,
                                    transition: ItemTransition) {
        if (transition.isStale()) return log.debug("a newer stream owes the start")
        stopsInFlight.joinAll()                      // #890: the stop goes first
        if (!startGate.allows(stream, ready)) return // #601: a domain rule
        try {
            withContext(io) { reports.start(stream.itemId, player.positionTicks(), stream.transcoded) }
        } catch (e: ServerException) { log.warn(e, "playback start") }
    }
}
```

A sketch of the download machine's transition function (not compiled):

```kotlin
// domain, package dev.jdtech.jellyfin.domain.downloads (sketch)
data class Transition(val stage: DownloadStage?, val effects: List<DownloadEffect>)

fun DownloadStage?.on(event: DownloadEvent, now: Long): Transition = when {
    this == null && event is Enqueue -> Transition(Queued(), listOf(SavePending))
    this is Queued && event is Start && event.online && (retryAt ?: 0) <= now ->
        Transition(Running(event.downloadId), listOf(RemovePending, StartEngine))
    this is Queued && event is Start -> Transition(copy(waitingForNetwork = true), listOf(SavePending))
    (this is Running || this is Paused) && event is EngineSucceeded ->
        Transition(Finalizing(downloadId()), listOf(Finalize))
    (this is Running || this is Paused) && event is EngineFailed ->
        if (event.permanent || retryCount() >= MAX_AUTO_RETRIES)
            Transition(Failed(event.reason()), listOf(NotifyFailure))
        else Transition(Queued(retryCount() + 1, retryAt = now + backoff(retryCount())), listOf(SavePending))
    this is Failed && (event is Retry || (event is ServerConfirmed && reason == NoAnswer)) ->
        Transition(Queued(), listOf(SavePending))
    this != null && this !is Finished && event is Cancel -> Transition(null, listOf(CancelEngine, DeletePartial))
    else -> Transition(this, emptyList())            // not legal: unchanged, logged by the caller
}
```

## The order of the PRs

One flow per PR, smallest and best tested first. "Player" marks a PR that changes code the running
player executes: it runs `docs/mpv-checklist.md` on both backends, over http and https, and waits
for the owner's check on a real device before it merges (a pure move may be an exception, Q4). A
PR that touches stored download state runs the upgrade check: the previous build with downloads in
every state (queued, running, cut off, failed, finished, with external subtitles), the new build
installed over it, and the database and the queue compared before and after.

| # | PR | Moves | Must not change | Pure tests it adds | Device check | Needs |
|---|---|---|---|---|---|---|
| 1 | Download machine in `domain` | nothing: new types and the transition function, no caller | anything | one test per row of the table, and that every other pair is unchanged | none | nothing |
| 2 | The queue drives the machine | `EntryState`, `afterFailure` and the restore mapping into the machine; the queue applies effects | what the queue shows and does; `DownloadQueue*Test` unchanged | the queue's decisions with a fake `Downloader` | upgrade check; the guide's cut-off and airplane checks | 1 |
| 3 | The queue's time and scope injected | clock, dispatcher and scope as constructor parameters; tests from `Thread.sleep` to `runTest` | behaviour | backoff and server return with virtual time | none | 2 |
| 4 | The queue to `application` | the service, with ports for the pump service and the notice; the choice of what starts next as a `domain` rule | behaviour; R15's names | the queue without Robolectric | upgrade check | 3; the store role of #967 if it has landed (not waited for) |
| 5 | `DownloaderImpl`'s rules to `domain` | the sweep, the paths, the subtitle choice, the progress, the network rule; the finalize as effects | the files kept and deleted | the sweep and finalize tables | upgrade check, finalize with external subtitles | 2 |
| 6 | Pure player rules to `domain` | `SegmentTracker`, `AutoPlayNext`, `UpNextCountdown`, `StillWatchingTracker`, `SleepTimer`, the track matching, the stop report and the start gate (without media3's constants), as moves | behaviour; their tests move with them | none new | Player (a move, Q4) | #969's player settings port for `segmentTrackerFrom`, or that stays behind |
| 7 | The end of an item as a rule | nothing: a new `domain` rule with no caller | anything | a table of today's order in `onPlayWhenReadyChanged`, `updateUpNext` and the outro skip | none | 6 |
| 8 | The view model uses the end of an item | VM 1042 to 1076 and 1225 to 1535 carry out the rule's decision | the order of the end of an item | the view model's part through 7 | Player, with the checklist's playlist and autoplay lines | 7 |
| 9 | The reports to `application` | `PlaybackReporter` with a stream value of our own and a log port | every report and its order; `PlayerInvariantsTest` | the existing tests, under `runTest` | Player | 6 |
| 10 | Cast uses the shared report rules | `CastPlaybackReporter` uses the stop rule and the gate; differences as inputs | every Cast report | Cast's decider over the shared rules | Cast on a real receiver (the debug stand-in shows states, not reports) | 9 |
| 11 | `ResolvePlayback` to `application` | the use case, with a resolved stream of our own | every request to the server | without `AppPreferences` | Player, with a quality switch over https | #969's player settings port |
| 12 | Track preferences to `application` | `PlaylistManager`'s audio choice and the view model's remembering and applying | which tracks play | the order of apply, reset and read | Player, with a series' saved choice and a movie | 6, 11 |
| 13 | The play queue to `application` | `PlaylistManager`'s queue and the start position | the queue on both players | the queue without the mapping | Player and Cast | 11 |
| 14 | The stream switches to `application` | `StreamSwitches` over 11 and 13 | the newest switch wins; the reload | the switches over fakes | Player, with the checklist's switch and slow link lines | 11, 13 |

Rules R21 to R24 (below) come in the PR that makes them true: R21 and R22 with 2 (R21's numbers
lowered by each later PR), R24 with 3, R23 with 9.

**Order around the other issues.** The downloads come first: they need nothing from #966, #967 or
#969, and their tests are the slowest today. #969's player settings port is the one dependency
that blocks the player (PRs 6, 11, 12): do it before 11. #967 is not waited for: a service takes
`MediaRepository` until the role it needs exists, and names only what it uses. #966 does not block
anything here; the playback reports build their requests through the repository, so #966 moves
that part with the rest of `MediaRepositoryImpl`. #971 comes last, as planned, and finds the new
code already in its packages.

**Done for #970:**
- `PlayerViewModel` at most 1800 lines (from 2583), what is left being media3 wiring, the screen's
  state, the subtitle sync sheet and trickplay; `DownloadQueue` gone from `downloads`, or only its
  Android shell (at most 150 lines); `DownloaderImpl` at most 1100 lines (from 1529). The limits
  are R21's numbers.
- Each of the ten flows above that becomes a rule or a service (P1 to P7, D1, D2, D4) has its
  tests in `domain` or `application`, with no Robolectric. Of the 11 rules in the table under
  "Context", all but #869 (the mpv engine's own) and #850 (the engine's `If-Range`) are then in
  `domain` or `application`.
- The download machine in `domain` with every row of its table tested.
- No playback rule exists twice: Cast uses the stop rule, the gate and the segment rule of
  `domain`; R23 holds.
- Every player PR ran the mpv checklist on both backends over http and https.

## New architecture rules

R20 is taken by #965 (the device adapter). What a reviewer cannot check ("a view model holds no
rule") is replaced by what a test can:

- **R21: size limits that only go down.** A list of the largest files (`PlayerViewModel`,
  `CastPlayerControllerImpl`, `CastViewModel`, `DownloaderImpl`, `DownloadQueue`) with a line
  limit each, in a file of its own under `architecture/` (a list of limits, not a baseline). The test fails when a file grows past its limit, and when it is more than
  100 lines under it, so a PR that shrinks a file also lowers its number.
- **R22: the download stages are declared in `domain` alone.** No production file outside
  `domain` declares a type of download states (no `EntryState`, no `DownloadStatus`), so the four
  vocabularies do not come back.
- **R23: one sender of playback reports.** Only the reports service calls `postPlaybackStart`,
  `postPlaybackProgress` and `postPlaybackStop` (or the report role after #967), as R16 holds the
  server switch to one place. A second reporter in Cast or TV fails the build.
- **R24: no hidden time in the pure modules.** `domain` names no clock and no `Dispatchers`;
  `application` names them only as a constructor parameter's default. `delay` is fine: virtual time
  covers it.
- After PR 13, **R25: the player's `domain` package imports no media3**, which makes "the player's
  rules are not engine code" checkable for what is left there (the mappers).

## Risks, and what is deliberately not done

- **The running player.** Every player PR can break playback on one backend and not the other
  (#267, #284). The checklist on both backends over http and https, the owner's device check, and
  one flow per PR are the answer. `PlayerInvariantsTest` reads the view model's source: a move that
  renames a call there fails it, which is the point; update the test with the call, never loosen it.
- **Cast without a receiver.** Emulators find none, and the debug stand-in reports nothing. PR 10
  waits for a real receiver; until then Cast keeps its own decider.
- **Upgrades.** No schema change, but the restore mapping moves (PR 2, 4, 5). The upgrade check
  covers it; a mistake there loses downloads, not only state.
- **Two vocabularies during the series.** Between PR 1 and PR 2 the machine exists unused; between
  PR 6 and PR 14 some rules are in `domain` and the services that use them are still in
  `player/local`. Each PR leaves the app working.
- **Not done:** no rewrite of the player or of the download engine; no change of behaviour in a
  move; no use case per repository call; no new domain events; no schema change; no change to
  Cast's behaviour before PR 10, and none in PR 10 either.

Found while reading, not reproduced, to be filed with a "reproduce first" section rather than fixed
in a move: a download whose start fails while online loses its pending row and is gone after a
restart (`DownloadQueue` 830 to 838 with `DownloaderImpl` 413 to 436); Cast asks the server for
every item twice, and once more for the chosen audio, and drops the series' remembered audio; Cast
sends no `PlaybackEvents`; `Segment.startTicks` and `endTicks` hold milliseconds.

## Questions for the owner

Each with a recommendation; if nobody answers, the series goes with it.

| | Question | Recommendation, and the default |
|---|---|---|
| Q1 | Shared services in `application` and rules in `domain`, or a new pure `playback` module | **`application` and `domain`.** A new module would add a layer the rules do not need; `application` is already the pure place every feature depends on |
| Q2 | Packages for the new code now, ahead of #971 | **Yes: `dev.jdtech.jellyfin.domain.playback`, `.domain.downloads`, `dev.jdtech.jellyfin.application.playback`, `.application.downloads`.** Moved code then needs no second move; #971 adopts the scheme for the rest |
| Q3 | Keep a failed download failed across a restart (schema version 22: the pending row kept until finished, with the stage and the retry count) | **Not in #970.** It changes what the user sees after a restart; an issue of its own once PR 2 has landed, with the upgrade check |
| Q4 | May a pure move in `player/*` (PR 6) merge on the emulator checklist on both backends, without the owner's device check | **Yes**, when the diff is moves and imports only and the moved tests are unchanged. Anything with a changed call site waits for the owner |
| Q5 | One log port for `application` (debug, warn, error over Timber in `core`), or one per service as `FallbackLog` | **One log port**, `FallbackLog` folded into it when PR 9 lands |
| Q6 | Cast's differences (no start position, no `PlaybackEvents`, two requests per item, the remembered audio dropped) | **Kept as they are in the moves; one issue each, fixed after PR 10** with a receiver to test on |
| Q7 | The order: downloads first, #969's player settings port before PR 11, #967 not waited for | **Yes** |

## Consequences

- The rules of playback and downloads are tested in seconds, without Android, and the download
  queue's backoff with virtual time instead of real sleeps.
- Cast and the local player share one set of report, segment and queue rules; a fix lands once.
- `PlayerViewModel` and `DownloaderImpl` shrink, and R21 keeps them from growing back.
- Upstream changes to `PlayerViewModel` and `PlaylistManager` merge by hand after PRs 8 to 14,
  as after ADR 0002's tasks.
- About 14 PRs, none visible to a user. Six touch the running player in more than imports (8, 9,
  11, 12, 13, 14), one more Cast alone (10), and three the queue's restore (2, 4, 5).
