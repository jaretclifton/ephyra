# ADR 0003: Use cases for playback and downloads

**Status:** Proposed (2026-11-25, in #970). Refines ADR 0001, decision 1, and ADR 0002, decision 3.
Nothing is built yet; the PRs are in "The order of the PRs" below. Corrected the same day after a
second-opinion review on PR #1000, with the overseer's decisions on the order, the rule numbers
and Q4.

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
   only does I/O. Its table describes today's behaviour completely and is pinned by tests against
   the current code before it replaces anything. `DownloadState` stays what the rows say (no
   schema change in this series); the stage does not decide it.
5. **One rule set for both players.** The local player and Cast call the same services and rules.
   Where Cast behaves differently today, the move keeps the difference as an explicit input, and a
   change of behaviour is a separate `fix` PR with its own issue.
6. **No change of behaviour in a move.** Each PR moves one flow, keeps the existing tests green
   and adds pure tests. A bug found on the way is filed, not fixed in the move.
7. **New code goes in packages by layer, area and kind now**, in the scheme #971 asks for (Q2):
   `dev.jdtech.jellyfin.domain.<area>.model` and `.rule`, `dev.jdtech.jellyfin.application.<area>.port`
   and `.service`, with `playback` and `downloads` as the areas, so #971 does not move it again.

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
  `player/local/.../di/`, as today. It stays a main-thread class: it reads the player before it
  launches, records each start's job on the snapshot before it waits (the progress gate and the
  stop's wait read it), sends in `reportScope`, and catches every exception there but
  cancellation (see "How a use case is shaped"). What stays in the view model: telling the service
  what happened, and reading the player's position for it (`ReportedPlayer`). Cast shares the stop
  rule only: its position and percentage (Cast clamps to 0..100, the local player does not: an
  input). Cast's gate is a different rule (the receiver plays the current item and no load is
  pending), decided from the receiver's status updates and a clock, and stays Cast's. Its other
  differences (no position on the start, no `PlaybackEvents`) stay until their own fix (Q6).

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
- *How the code runs today*, which the rule must keep:
  - The autoplay value is a `Deferred` that may wait for the server. It is awaited only on the
    branch that needs it (VM 1260 to 1270: a next item exists, the sleep timer does not stop here,
    the up next card was not cancelled), and the item is checked again after the wait: the user
    may have moved on.
  - `StillWatchingTracker.onAutoAdvance` changes its counters (VM 1294). It runs only when an
    advance is reached, and decides there whether to ask.
  - There are two clocks: wall time for still watching (1294, 1331, 1443, 1534) and
    `elapsedRealtime` for the sleep timer and the prompt's deadline (1426, 1461 to 1481).
- **Decision: a rule in `domain`, the end of an item, in steps.** The first step takes what is known
  at once (a next item, the sleep timer at the end of the item, the up next card cancelled) and
  returns one of: stay paused for the timer, end without advancing, the last item (the normal path
  to `STATE_ENDED`), or "needs autoplay". The view model awaits autoplay only then, checks the item
  again, and calls the second step with it. That step returns end without advancing, or advance. The
  view model then calls the still watching tracker as it does now, which decides between asking
  and advancing. So a slow server never holds up the sleep timer's end, and an advance that is not
  reached is not counted. The outro auto-skip (VM 1042 to 1076) uses the same steps; times come in
  as parameters, one per clock. The view model carries each result out. This is the largest
  untested rule in the view model, and PR 11's table tests exactly these steps.

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
  `Downloader` port (reshaped first, PR 5: today it lives in `downloads` and returns `UiText` from
  `settings` and the engine's int status), `DownloadSettings`, `NetworkConnectivity` and
  `ServerAvailability`, with its scope, its dispatcher and its two clocks injected (wall time for
  `addedAt` and `retryAt`, which are compared with stored rows; a monotonic clock for the #912
  guard). The choice of what starts next is a rule in `domain`. The pump service, the failure
  notification and the texts go behind small ports implemented in `downloads`.
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

The table below is **today's behaviour**, read from `DownloadQueue` and `DownloaderImpl` on `main`
at `41de995ad`, including the paths that look odd. The machine reproduces it row by row. A row
whose "Pinned by" cell says **none** has no test today: PR 1 adds a characterisation test for it
against the current code, before the machine replaces anything. A change to a row is a `fix` PR
with its own issue, never part of a move.

**Stages** (one sealed type in `domain`). Every stage carries the facts of the download itself:
the item, the quality asked for, whether Smart Downloads queued it, `addedAt`, and `retryCount`,
which goes along through Running, Paused and Failed so that an engine failure can count the
retries (as `Entry.retryCount` does today).

- **Queued** (`retryAt`, `waitingForNetwork`, `starting`): waits for a slot; `starting` while the
  downloader sets the download up. Today's Pending.
- **Running** (`downloadId`, progress, bytes, speed): the engine transfers. Today's Downloading.
- **Paused** (`downloadId`): the engine stopped for a network the settings forbid; keeps its slot.
- **Finalizing** (`downloadId`): the engine finished and the downloader renames and writes the
  rows. It lasts from the poll that sees the engine succeed until that poll publishes, so no screen
  sees it today: it exists in the machine to name the finalize's outcome.
- **Finished**: finished in this process. Today's Completed. A download finished in an earlier
  process is not in the queue at all (`restoreAll` loads active and pending downloads only).
- **Failed** (`reason`, `resumesWhenServerReturns`): waits for the user, or for the server when
  flagged. The flag is decided as today, not derived from the reason (rows 10 and 21).

**The reason** carries everything the queue shows today, so the screens show the same after the
move: not enough storage, with the size needed and the space free (`DownloaderImpl` 334 to 341);
storage unavailable (319); no sources (461); a start error with its exception, which
`texts.startError` turns into words (1027); the server refused, with the HTTP status
(`DownloadQueue.serverRefused`); the generic downloading error (a finalize that failed, a
`downloadItem` that threw); and no text at all (an engine failure without a status). The texts
themselves stay outside `domain`.

**What the stage does not decide: `DownloadState`.** The item buttons read `DownloadState` from the
rows, next to the queue's entry for the same item. The two do not follow each other: a Queued
download with only a pending row has no LOCAL source, so its `DownloadState` is `NONE`; so is a
Failed one whose fresh start failed. A Queued download after an engine failure, or a Failed one
after a failed resume or a failed finalize, has a partial row and is `DOWNLOADING`. A Finished one
is `FINISHED`. The machine does not change `DownloadState` or the rows that make it.

| # | From | Event | To | Effects | Pinned by |
|---|---|---|---|---|---|
| 1 | (none) | Enqueue (also `enqueueAll`) | Queued (`addedAt` now; `enqueueAll` adds one per item, in order) | save the pending row; start the pump | partly: every queue test enqueues; the saved row: none |
| 2 | Failed or Finished | Enqueue of the same item | Queued, fresh (new quality, counters at 0) | save the pending row | none |
| 3 | Queued, Running or Paused, queued by Smart Downloads | Enqueue by the user | same stage, no longer Smart Downloads' | `clearSmartQueued` on the rows | none |
| 4 | Queued, Running or Paused | any other Enqueue | unchanged | none | none |
| 5 | Queued, not starting, `retryAt` passed, online, a slot free (Running and Paused count) | Start | Queued (`starting`) | delete the pending row, then call the downloader (today's order, #1002) | partly: `DownloadQueueNetworkTest` |
| 6 | Queued (`starting`) | Started (a download id) | Running (`startedAt` now) | none | `DownloadQueueFinalizeTest`, `DownloadQueueHttpErrorTest` (as setup) |
| 7 | (none: removed while starting) | Started (a download id) | (none) | cancel that download: the engine task, and the source of that id | none |
| 8 | Queued, not starting (removed and queued again while the old start ran) | Started of the old start | Running with the old start's id (today's behaviour, #1003) | none | none |
| 9 | Queued (`starting`) | StartFailed, offline now | Queued (`waitingForNetwork`), retries not counted | save the pending row again | `DownloadQueueNetworkTest` |
| 10 | Queued (`starting`) | StartFailed, online | Failed (the downloader's reason, or the downloading error when it threw), `resumesWhenServerReturns` set whatever the reason | none from the queue. What the downloader left: the fresh branch deleted what it wrote (`DownloaderImpl` 413 to 436); a failed resume keeps its partial row and file (272 to 277); the early returns wrote nothing. The pending row is gone (#1002) | `DownloadQueueNetworkTest`, `DownloadQueueServerReturnTest` |
| 11 | Queued | NetworkChanged | Queued (`waitingForNetwork` follows) | none | `DownloadQueueNetworkTest` |
| 12 | Running | EnginePaused | Paused | none | none |
| 13 | Paused | EngineRunning (or the engine's pending) | Running | none | none |
| 14 | Running or Paused | EngineProgress | same | publish progress, bytes, speed (an estimated size for a transcode) | partly: `DownloadSpeedTest` |
| 15 | Running or Paused | the engine has no task for the id | as row 19 to 21, with no status | as there | none |
| 16 | Running or Paused | EngineSucceeded | Finalizing | finalize | `DownloadQueueFinalizeTest` |
| 17 | Finalizing | Finalized | Finished, progress 100 | publish a data change (#251) | `DownloadQueueFinalizeTest` |
| 18 | Finalizing | FinalizeFailed (false, or it threw) | Failed (the downloading error), no auto-retry, not resumed when the server returns | the failure notice; the partial file and row stay (#848) | `DownloadQueueFinalizeTest`; the notice: none |
| 19 | Running or Paused | EngineFailed, not permanent, fewer than 3 retries | Queued (`retryCount` + 1, `retryAt` now + 30 s, 2 min, 10 min), never published as Failed (#560) | save the pending row; the partial file and row stay | `DownloadQueueRetryTransitionTest`, `DownloadQueueHttpErrorTest` |
| 20 | Running or Paused | EngineFailed, permanent (a 4xx but 408 and 429, #383) | Failed (server refused, with the status), not flagged | the failure notice | `DownloadQueueHttpErrorTest`, `DownloadQueueServerReturnTest` |
| 21 | Running or Paused | EngineFailed, retries used up | Failed (server refused when it had a status, else no text); flagged only without a text | the failure notice | `DownloadQueueRetryTransitionTest`; the flag: none |
| 22 | Failed by row 21 in this poll, flagged | the server was confirmed after the poll began | Queued, as row 24 | as row 24 | none |
| 23 | Failed | Retry | Queued (counters at 0, `addedAt` now) | save the pending row | `DownloadQueueHttpErrorTest`, `DownloadQueueFinalizeTest` |
| 24 | Failed, flagged | ServerConfirmed | Queued, as row 23 | save the pending row | `DownloadQueueServerReturnTest` |
| 25 | Failed, not flagged | ServerConfirmed | unchanged | none | `DownloadQueueServerReturnTest` |
| 26 | Queued, Running, Paused or Failed | Remove | (none) | delete the pending row; for every unfinished download id (the entry's, and those on its partial rows, #426) cancel the engine task and delete the source of that id | `DownloadQueueCancelTest`, `DownloadQueueHttpErrorTest`, `DownloadQueueServerReturnTest` |
| 27 | Finalizing | Remove | (none) | as row 26: the source of that id is deleted, partial or final. The finalize's late result is dropped. A rename that lands between the two leaves a final file without a row, which the orphan sweep deletes after a day | none |
| 28 | Finished | Remove, or `clearCompleted` | (none) | none: the entry leaves the list, the files stay. Deleting a finished download is `Downloader.deleteItem`, called by the view models, outside the machine | none |
| 29 | (process start) | Restored from a partial row with no engine task, item found in Room | Queued (`addedAt` now − 1, ahead of the pending ones) | none; its pending row, if any, is skipped | `DownloadQueueCancelTest` |
| 30 | (process start) | Restored from a pending row whose item resolves | Queued (`addedAt` from the row) | none | none |
| 31 | (process start) | a pending row whose item does not resolve | not in the queue | the row stays; dropped after 30 days; a kind other than movie or episode is dropped at once | none |
| 32 | (process start) | a partial row whose file was renamed before the stop (#848) | not in the queue | the downloader finishes the finalize inside the restore; when that fails the row stays for the next start and is never resumed (the partial file is gone) | `DownloadFinalizeFailureTest` |
| 33 | (none), or Failed or Finished | `restore(item, downloadId)` from a detail screen of an item still downloading | Running with that id and no engine task; the next poll goes on by row 15 | none | none |

Every other pair is not legal: the state stays as it is, there are no effects, and the queue logs
it. Row 22 is not such a case: today it re-applies a confirmation that arrived while the poll was
still deciding (`DownloadQueue` 760 to 768, `lastServerConfirmedAt` against `polledAt`, a
monotonic clock). The machine keeps it as an event of its own, with the confirmation's time.

**Outside the machine**, on purpose: the external subtitles. They have their own life today
(`finalizeFinishedMediaStreams` at the start of `restoreAll`, `retryFailedMediaStreams` every 30 s
while a download is Running but not Paused, the finalize-later job of up to 5 min, #573), tested by
`SubtitleFinalizeTest`. They stay with `DownloaderImpl` and the queue's poll; the machine only
knows the video. Also outside: the #848 repair of row 32 (the downloader's, at restore) and the
orphan sweep.

**Room.** No schema change in this series: what Room stores carries every stage that survives a
restart today, and Failed, Paused and the retry counters are lost at process death today as well.
Keeping Failed across a restart would change what the user sees after one, so it is its own
decision (Q3), with a schema version 22. The lost pending row of a start that failed (#1002) needs
no schema change and is a fix of its own.

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
- **Threads.** A player service holds its state without locks, as `PlaybackReporter` does today: it
  is called on the player's (main) thread only, and reads the player there, before it launches
  anything. Only the port calls run on the injected dispatcher. `runTest` does not show a mistake
  here, so the KDoc of each service says which thread it is called on. The download queue keeps
  its mutex.
- **Scope, dispatcher, clocks.** A service takes `CoroutineScope` only when its work must outlive
  the caller (a stop report outlives the player: today `@ApplicationScope` in
  `PlaybackReporterModule`); otherwise it suspends and the caller's scope runs it. It takes a
  `CoroutineDispatcher` for blocking work, and one injected source per clock it reads: wall time
  where a time is stored or compared with stored times (the queue's `addedAt` and `retryAt`), a
  monotonic clock where only an order matters (the #912 guard's `nanoTime`, the sleep timer's
  `elapsedRealtime`). Two clocks stay two parameters. In `application` defaults appear only in the
  constructor (as `ProfileLock` and PR 987 do: the production constructor passes `Dispatchers.IO`).
  Hilt does not use Kotlin default arguments on an `@Inject` constructor, so a `@Provides` passes
  each one. A test passes a `StandardTestDispatcher`, `backgroundScope` and
  `testScheduler.currentTime`, and runs under `runTest` with `advanceUntilIdle()`, with no real
  dispatcher left running.
- **Errors.** A port throws `ServerException` for an I/O failure. A service catches it only where
  its rule says what happens then; otherwise it lets it through. A report is the exception to the
  exception: it runs on the application's scope, where an uncaught exception crashes the app, and
  `postPlaybackStop` also writes Room, so a report catches every exception but
  `CancellationException`, as `PlaybackReporter` does today. A switch is undone and returns a
  failure that carries the exception. `CancellationException` is always rethrown. Texts for the
  user (`LoadErrorText`, `switchFailureText`, the download texts) are made outside `application`,
  from the exception or the reason.
- **Logging.** `application` has no logger (R19). A service that logs takes a log port, as the
  stored fallback takes `FallbackLog`, implemented over Timber in `core` (Q5).
- **Hilt.** `application` declares no Hilt annotation and no `@Inject` (R19: no library but
  kotlinx). A feature module provides each service with `@Provides` in its `di/` package, as
  `ExternalRatingsModule` in `core` provides `ExternalRatingsService`: the player's services in
  `player/local/.../di/` (unscoped, one per player, as `PlaybackReporter` is now), the download
  queue in `downloads/.../di/` as a `@Singleton`. Cast takes the same bindings, since
  `player/cast` depends on `player/local`.
- **Constraints.** R5 and R19: a service in `application` imports only Kotlin, kotlinx, the JDK and
  our own code, so no media3, no `Uri`, no `Parcelable`, no `PlayerItem`, no `UiText`: it gets
  values of our own (ids, positions in milliseconds, a missing duration as null). R6: shared player
  code imports no engine package; the services cannot, because they are not in `player/local` at
  all. The Cast flavours: a service is compiled once in `application` and used from
  `player/cast/src/proprietary` and `src/main`; `src/libre` needs nothing new. Nothing in a service
  names a Cast type: the receiver is `PlaybackTarget.CastReceiver` of `domain`.

The two sketches below show **the shape only** (where a class lives, what it takes, what it
returns). They are not compiled and are not the control flow: an implementer ports the current
control flow of `PlaybackReporter.sendPlaybackStart` and of the table above, line by line, and
does not start from the sketch.

```kotlin
// SHAPE ONLY. application, package dev.jdtech.jellyfin.application.playback.service
class PlaybackReports(
    private val reports: PlaybackReportsRole,        // the role of MediaRepository after #967
    private val events: PlaybackEvents,
    private val reportScope: CoroutineScope,         // the application's: a stop outlives the player
    private val io: CoroutineDispatcher,
    private val log: AppLog,
) {
    /** Main thread only, like everything here: the state below has no lock. */
    suspend fun sendPlaybackStart(stream: QueuedStream, player: ReportedPlayer) {
        val seq = transitionSeq
        stopsInFlight.toList().joinAll()                         // #890: the stops go first
        if (seq != transitionSeq || playing?.itemId != stream.itemId) return  // re-checked after the wait
        val positionTicks = player.positionTicks()               // read here, on the main thread (#868)
        val start = reportScope.launch(io) {
            try { reports.start(stream.itemId, positionTicks, stream.transcoded) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.error(e, "playback start") }  // everything: this is the app's scope
        }
        playing = playing?.copy(startJob = start)                // recorded before the wait: progress and stop read it
        start.join()
    }
}
```

```kotlin
// SHAPE ONLY. domain, package dev.jdtech.jellyfin.domain.downloads.rule
data class Transition(val stage: DownloadStage?, val effects: List<DownloadEffect>)

/** One row of the table per branch; a pair that is not in the table returns this unchanged. */
fun DownloadStage?.on(event: DownloadEvent, now: Long): Transition = when {
    this is Queued && starting && event is Started -> Transition(Running(facts, event.downloadId, startedAt = now), emptyList())
    this == null && event is Started -> Transition(null, listOf(CancelDownload(event.downloadId)))      // row 7
    (this is Running || this is Paused) && event is EngineFailed && event.retriesLeft(facts) ->
        Transition(Queued(facts.retried(), retryAt = now + backoff(facts.retryCount)), listOf(SavePending))  // row 19
    // … one branch per row
    else -> Transition(this, emptyList())
}
```

## The order of the PRs

One flow per PR, smallest and best tested first. The marks:

- **Player**: changes code the running player executes. It runs `docs/mpv-checklist.md` on both
  backends, over http and https, and waits for the owner's check on a real device before it
  merges.
- **Move**: a pure move in `player/*` (Q4). It may merge on the emulator checklist on both backends
  without the owner's device check, but only when it passes the mechanical test under Q4.
- **Cast**: needs a real Cast receiver. Emulators find none, and the debug stand-in
  (`FakeCast.kt`) shows states but sends no reports. Such a PR is prepared and tested on the
  stand-in, and is **not merged** until the owner has run it on a receiver, as with the device
  check. An unattended run stops there and reports; it never merges on the stand-in.
- **Downloads**: changes what the running download queue does or how it is built. It runs the
  downloads guide's device checks: a download cut off by `iptables` and brought back, airplane mode,
  and a restart during a download.
- **Upgrade**: touches what the queue restores. The previous build with downloads in every stage
  (queued, running, cut off, failed, finished, with external subtitles), the new build installed
  over it, and the database and the queue compared before and after.
- **None**: tests or new code without a caller. Nothing that runs changes.

| # | PR | What it does | Must not change | Tests it adds | Marks |
|---|---|---|---|---|---|
| 1 | Characterisation tests of the queue | a test against the current `DownloadQueue` and `DownloaderImpl` for every row marked "none" in the table | any production file | one per such row | None |
| 2 | Download machine in `domain` | the stages, events, effects and the transition function; no caller | anything | one per row of the table, and that every other pair is unchanged | None |
| 3 | The queue drives the machine | the queue's decisions go through the machine; `Entry` and `EntryState` stay as the published view, mapped from the stage | the tests of PR 1 and the existing `DownloadQueue*Test`, unchanged | none new: PR 1 and 2 hold it | Downloads, Upgrade |
| 4 | The queue's clocks, dispatcher and scope injected | wall time and a monotonic clock as two sources, the dispatcher and the scope as constructor parameters, bound in `downloads/.../di/`; the tests move from `Thread.sleep` to `runTest` | behaviour | backoff, the #912 guard and the server's return in virtual time | Downloads |
| 5 | The `Downloader` port reshaped | `Downloader` to `application`, with a start result and a failure reason of our own instead of `Pair<Long, UiText?>`, and an engine status of our own instead of the int `DownloadStatus` in `Progress`; `DownloaderImpl`, the fakes and the callers follow | what is shown and done | the port's fakes in `testing` | Downloads |
| 6 | The queue to `application` | the service, with ports for the pump service, the failure notice and the texts; the choice of what starts next as a `domain` rule | behaviour; R15's names | the queue without Robolectric | Downloads, Upgrade |
| 7 | The screens read the stage | the readers of `EntryState` (`NavigationRoot`, `DownloaderViewModel`, `DownloadPumpNotice`, `SmartDownloads`, `EpisodeDownloadSelection`, `DownloadsViewModel`, `SeasonViewModel`) and both `DownloadStatus` types (the engine's and the screens') replaced by the stage's view; the old types deleted | what the screens show | the view models' mapping | Downloads |
| 8 | `DownloaderImpl`'s rules to `domain` | the sweep, the paths, the subtitle choice, the progress, the metered and roaming rule as one function the engine calls; the finalize as effects | the files kept and deleted | the sweep and finalize tables | Downloads, Upgrade |
| 9 | True moves of player rules | `SegmentTracker`, `UpNextCountdown`, `StillWatchingTracker`, `SleepTimer`, `PlaybackStartGate`, `ReportedPause`, `matchSeriesTrack`, `seriesTrackChoice` to `domain`, as renames | anything but `package` and `import` lines | none: their tests move with them | Move |
| 10 | Player rules with changed call sites | `playbackStopReport` and `PlayingSnapshot` with a missing duration as null instead of `C.TIME_UNSET` (`PlaybackReporter` 167 and 266); `AutoPlayNext` without `settings.domain.Constants`, through #969's port; `serverAudioTracks` split from `ServerAudioTrack` of `player/core` | behaviour | the moved rules' tests, adapted | Player |
| 11 | The end of an item as a rule | a `domain` rule in steps, no caller (see P7) | anything | a table of today's order in `onPlayWhenReadyChanged`, `updateUpNext` and the outro skip, with a slow autoplay answer and both clocks | None |
| 12 | The view model uses the end of an item | VM 1042 to 1076 and 1225 to 1535 carry out the rule's steps | the order of the end of an item | the view model's part through 11 | Player (the checklist's playlist and autoplay lines) |
| 13 | The reports to `application` | `PlaybackReporter` with a stream value of our own and a log port | every report and its order; `PlayerInvariantsTest` | the existing tests, under `runTest` | Player |
| 14 | Cast uses the shared stop rule | `CastPlaybackReporter` takes the stop's position and percentage from `domain` (Cast's clamp to 0..100 as an input), and Cast's three report calls go through the shared service; Cast's own gate (the receiver plays the current item, no load pending) stays | every Cast report | Cast's decider over the shared rule | Cast |
| 15 | `ResolvePlayback` to `application` | the use case, with a resolved stream of our own | every request to the server | without `AppPreferences` | Player (a quality switch over https) |
| 16 | Track preferences to `application` | `PlaylistManager`'s audio choice and the view model's remembering and applying | which tracks play | the order of apply, reset and read | Player (a series' saved choice and a movie) |
| 17 | The play queue to `application` | `PlaylistManager`'s queue and the start position | the queue on both players | the queue without the mapping | Player, Cast |
| 18 | The stream switches to `application` | `StreamSwitches` over 15 and 17 | the newest switch wins; the reload | the switches over fakes | Player (the checklist's switch and slow link lines), Cast |

Without a receiver the series goes on past 14 with 15 and 16, and stops before 17. Rule R25 then
does not land, and R27 waits as well.

**Order around the other issues.** #967, #966 and #969 run before this series, one at a time. So
when PR 1 opens: `MediaRepository` and `DownloadStore` are split by role and every service here
takes the roles it uses; `MediaRepositoryImpl` is in `application`, so the report requests are
built there already; and the player settings port of #969 exists, before PR 9, which settles
`segmentTrackerFrom`, `AutoPlayNext`'s `Constants` and `ResolvePlayback`'s `AppPreferences`. Should
one of them still be open when a PR here opens, the files that PR moves are frozen for it until the
PR merges: git does not carry an edit across a move to another module. #971 comes last, and finds
the new code already in its packages (Q2).

**Done for #970:**
- `PlayerViewModel` at most 1800 lines (from 2583), what is left being media3 wiring, the screen's
  state, the subtitle sync sheet and trickplay; `DownloadQueue` gone from `downloads`, or only its
  Android shell (at most 150 lines); `DownloaderImpl` at most 1100 lines (from 1529). The ceilings
  are R24's numbers.
- Each of the ten flows above that becomes a rule or a service (P1 to P7, D1, D2, D4) has its
  tests in `domain` or `application`, with no Robolectric. Of the 11 rules in the table under
  "Context", all but #869 (the mpv engine's own) and #850 (the engine's `If-Range`) are then in
  `domain` or `application`.
- The download machine in `domain`, every row of its table tested, and the rows PR 1 found
  untested pinned before the move.
- No playback rule exists twice: Cast uses the stop rule and the segment rule of `domain`; R25
  holds.
- Every player PR ran the mpv checklist on both backends over http and https.

## New architecture rules

R20 is taken by #965; R21, R22 and R23 are reserved for #967 (the port size), #966 and #969. This
series takes R24 on. The numbers are those of the PR that lands a rule; `rules.md` is generated
from the test names, so a number that changes before merge costs one rename. What a reviewer
cannot check ("a view model holds no rule") is replaced by what a test can:

- **R24: size ceilings.** A list of the largest files (`PlayerViewModel`, `CastPlayerControllerImpl`,
  `CastViewModel`, `DownloaderImpl`, `DownloadQueue`) with a line ceiling each, in a file of its
  own under `architecture/` (a list of ceilings, not a baseline). The test fails when a file grows
  past its ceiling. There is no lower bound, so a PR elsewhere that shrinks one of these files
  (#969, an upstream sync) does not have to touch the list; this series lowers the numbers in its
  own PRs. Kept because it is the only check that the view model stops growing back; moving code
  into an extension file to pass it is a review finding. Lands with PR 3.
- **R25: one sender of playback reports.** Only the reports service calls the report operations
  (`postPlaybackStart`, `postPlaybackProgress`, `postPlaybackStop`, or the report role after #967),
  as R16 holds the server switch to one place. Kept because it is what makes "no rule twice" true
  for Cast and TV, and a call site is easy to find. Lands with PR 14, when Cast's three calls go
  through the service (`CastPlayerControllerImpl` 505, 520 and 535 call them until then).
- **R26: no hidden time in the pure modules.** `domain` names no clock and no `Dispatchers`;
  `application` names them only as a constructor parameter's default. `delay` is fine: virtual
  time covers it. Kept because it is what lets every service run under `runTest`, and it passes
  today (only `ProfileLock`'s default dispatcher). Lands with PR 4.
- **R27, deferred until after PR 18: the player's rules import no media3.** Mappers import media3
  by their nature (`isNewEndOfItem`'s player constants, `toPlayerItem` with `C` and `toUri`), so
  the rule waits until what is left of the player's `domain` package is either moved to `domain`
  or `application`, or is a mapper in a `mapping` package of `player/local`. The rule then says
  that no file of `player/local` outside `engine`, `mapping`, `presentation` and `di` imports
  media3.
- **Dropped:** a rule that "the download stages are declared in `domain` alone". As a test it is a
  denylist of two names (`EntryState`, `DownloadStatus`), and PR 7 deletes both types, which is
  stronger than forbidding their names.

## Risks, and what is deliberately not done

- **The running player.** Every player PR can break playback on one backend and not the other
  (#267, #284). The checklist on both backends over http and https, the owner's device check, and
  one flow per PR are the answer. `PlayerInvariantsTest` reads the view model's source: a move that
  renames a call there fails it, which is the point; update the test with the call, never loosen it.
- **Cast without a receiver.** PRs 14, 17 and 18 wait for one (see the marks); until then Cast keeps
  its own reporter and its own calls.
- **The download table is long, and an implementer follows it row by row.** That is why PR 1 pins
  every row against the current code first: a row that is wrong here shows up as a failing
  characterisation test, not as a change of behaviour on a phone.
- **Upgrades.** No schema change, but the restore mapping moves (PRs 3, 6, 8). The upgrade check
  covers it; a mistake there loses downloads, not only state.
- **Two vocabularies during the series.** Between PR 2 and PR 7 the queue publishes `EntryState`
  mapped from the stage; between PR 9 and PR 18 some rules are in `domain` and the services that use
  them are still in `player/local`. Each PR leaves the app working.
- **Not done:** no rewrite of the player or of the download engine; no change of behaviour in a
  move; no use case per repository call; no new domain events; no schema change; no change to
  Cast's behaviour, in PR 14 either.

Found while reading, filed to reproduce first rather than fixed in a move:
- #1002: a download whose start fails while online, with the server away, loses its pending row
  and is gone after a process death.
- #1003: a cancel and a new enqueue of the same item while a start runs attach the old engine task
  to the new entry (table row 8).

Not filed:
- Cast asks the server for every item twice, and once more for the chosen audio, and drops the
  series' remembered audio.
- Cast sends no `PlaybackEvents`.

Both wait for Q6. One more trap with no bug behind it: `Segment.startTicks` and `endTicks` hold
milliseconds.

## Questions for the owner

Each has a recommendation, decided by the overseer on 2026-11-25 where marked. If nobody answers,
the series goes with it.

| | Question | Recommendation, and the default |
|---|---|---|
| Q1 | Shared services in `application` and rules in `domain`, or a new pure `playback` module | **`application` and `domain`.** A new module would add a layer the rules do not need; `application` is already the pure place every feature depends on |
| Q2 | Packages for the new code now, ahead of #971 | **Yes, in the scheme #971 asks for: by area, then by what a thing is.** `dev.jdtech.jellyfin.domain.<area>.model` and `.rule`, `dev.jdtech.jellyfin.application.<area>.port` and `.service`, with `playback` and `downloads` as the areas. Moved code then needs no second move. Should #971 settle on other names, that PR renames these packages with the rest |
| Q3 | Keep a failed download failed across a restart (schema version 22: the pending row kept until finished, with the stage and the retry count) | **Not in #970.** It changes what the user sees after a restart; an issue of its own once PR 3 has landed, with the upgrade check. The lost pending row of #1002 is separate and needs no schema change |
| Q4 | May a pure move in `player/*` merge on the emulator checklist on both backends, without the owner's device check | **Yes, only when the test below passes (overseer, 2026-11-25).** Anything else in the player waits for the owner |
| Q5 | One log port for `application` (debug, warn, error over Timber in `core`), or one per service as `FallbackLog` | **One log port**, `FallbackLog` folded into it when PR 13 lands |
| Q6 | Cast's differences (no start position, no `PlaybackEvents`, two requests per item, the remembered audio dropped) | **Kept as they are in the moves; one issue each, fixed after PR 14** with a receiver to test on |
| Q7 | The order: #967, #966 and #969 first, then the downloads, then the player | **Yes (overseer, 2026-11-25)** |

The test for Q4. In the PR's branch, against `origin/main`:
- every changed file that touches `player/` must be a rename;
- no build file under `player/` may change;
- the only changed Kotlin lines anywhere must be `package` and `import` lines.

```bash
git fetch origin
# 1. Every change that touches player/ is a rename (status R). The renames are detected on the
#    whole diff first, so a move from player/ to domain/ shows as R, not as a deletion. A changed
#    build file under player/ is not a rename, so it prints too.
git diff -M --name-status origin/main...HEAD | grep -v '^R' | grep 'player/'
# 2. No changed Kotlin line other than package and import lines.
git diff -M -U0 origin/main...HEAD -- '*.kt' | grep '^[+-]' | grep -v '^\(+++\|---\)' \
  | grep -v '^[+-]\(package\|import\) ' | grep -v '^[+-]$'
```

Both commands must print nothing. A PR for which either prints a line is marked Player, not Move.

## Consequences

- The rules of playback and downloads are tested in seconds, without Android, and the download
  queue's backoff with virtual time instead of real sleeps.
- Cast and the local player share one set of report, segment and queue rules; a fix lands once.
- `PlayerViewModel` and `DownloaderImpl` shrink, and R24 keeps them from growing back.
- Upstream changes to `PlayerViewModel` and `PlaylistManager` merge by hand after PRs 12 to 18,
  as after ADR 0002's tasks.
- About 18 PRs, none visible to a user. Breakdown:
  - Seven touch the running player beyond imports: 10, 12, 13, 15, 16, 17, 18.
  - Three need a Cast receiver: 14, 17, 18.
  - Six change the running download queue or its screens: 3 to 8. Three of those also change its
    restore: 3, 6, 8.
  - One is a pure move: 9.
  - Three change nothing that runs: 1, 2, 11.
