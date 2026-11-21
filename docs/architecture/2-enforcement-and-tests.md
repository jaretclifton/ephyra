# 2. Enforcement and tests

How the build keeps the boundaries, what a contract suite per port looks like, and which test
gaps have to close before which phase.

## 2.1 Enforcing the boundaries

Two mechanisms, each for what it is good at.

### Gradle: who may see the SDK at all

Every module dependency in this build is `implementation`. A module that does not declare
`libs.jellyfin.core` cannot compile against an SDK type, whether it imports it or only reads a
property of that type. So the hard boundary is one line per build file.

Nine build files declare the SDK today. The target is one (`server/jellyfin`). To make the
progress visible and to stop the line from coming back, a check reads the build files and compares
them with an allowlist:

```
architecture/baseline/sdk-modules.txt      9 lines today, 1 at the end
```

Removing the line from a module is the last step of cleaning that module, and it is the step that
makes the cleanup permanent.

Two more module rules follow the same pattern once the modules exist:

- `domain` is a pure Kotlin module (no Android plugin). It cannot depend on Android, Room, the SDK
  or media3 because none of those are on its classpath.
- Only the two app modules depend on `server/jellyfin`. The adapter carries its own Hilt module;
  the apps are the composition root. No feature module can reach into the adapter.

### An architecture test: what Gradle cannot say

Gradle cannot express three things this plan needs:

1. **A ratchet while the work is in progress.** `core` will keep its SDK dependency until
   `SyncWorker` is done, but no *new* file in `core` should start importing the SDK meanwhile.
2. **Rules inside a module.** The ExoPlayer package and the mpv package of `player/local` must not
   import each other; the shared player code must not import either.
3. **Rules about our own types.** `JellyfinApi` is not an SDK type, so removing the SDK from a
   module does not stop a composable from calling `JellyfinApi.getInstance(context)`.

The rules, each with a baseline file of the current violations:

| Rule | Baseline today | Ends at |
|---|---|---|
| R1. No production file outside the adapter mentions `org.jellyfin.sdk` | 56 files outside `data` | 0 |
| R2. No production file outside the adapter imports `dev.jdtech.jellyfin.api.JellyfinApi` | 25 files | 0 |
| R3. Only allowlisted modules declare `libs.jellyfin.core` | 9 modules | 1 |
| R4. No file outside `data` imports `ServerDatabaseDao` | 22 files | frozen; not part of this migration |
| R5. `domain` imports nothing from `android.`, `androidx.` (except `androidx.paging`), `org.jellyfin.sdk`, `dev.jdtech.jellyfin.database`, `dev.jdtech.jellyfin.api` | 0 | 0 |
| R6. In `player/local`, engine packages do not import each other and shared code imports neither | added in phase 5 | 0 |

**A baseline is exact.** The test fails when a file that is not listed violates a rule, and it
also fails when a listed file no longer does ("remove it from the baseline"). So a baseline can
only shrink, a cleanup PR shows its progress as deleted lines, and nobody can add an entry
without a reviewer seeing it in the diff. `main` stays green throughout; "red turning green" is
the baseline going from 56 lines to none, after which the file and the exception for it are
deleted.

The "adapter" in R1 and R2 narrows as the work goes on: the whole `data` module at first; from
#753 the packages `dev.jdtech.jellyfin.server.jellyfin` and `dev.jdtech.jellyfin.api` inside
`data` (the other `data` files that still mention the SDK join the baseline then); from #758 the
`server/jellyfin` module alone.

### Konsist or ArchUnit

| | Konsist | ArchUnit |
|---|---|---|
| Reads | Kotlin source | Compiled bytecode |
| Sees a type used without an import (a property type, a fully qualified name) | No (imports and declarations only) | Yes |
| Sees all source sets at once (`main`, `libre`, `proprietary`, `debug`) | Yes | No: one compiled variant per run, so Cast needs a second run for `proprietary` |
| Needs the modules compiled first | No. Parsing all 896 Kotlin files of this repository took 0.7 s | Yes: the app modules, both flavors |
| Where the test lives | One pure Kotlin module that reads the tree | In the unit tests of each app module (their classpath has the library classes), with the rules shared from a helper module |
| Kotlin constructs (top-level functions, extensions, `internal`, file names) | First-class | Seen as `FooKt` classes and JVM visibility |
| Built-in baseline | No, about 30 lines to write | Yes, `FreezingArchRule` |
| Follows the Kotlin version | Yes, it embeds the compiler: `1.0.0-beta.1` embeds 2.4.21, the version this build uses | No, only the class file version |

**Konsist.** The reasons, in order:

1. The one thing ArchUnit does better, seeing a type that is used without an import, is exactly
   what the Gradle rule already covers, and covers at compile time.
2. It sees the `proprietary` source set in the same run. The default build does not compile that
   source set, and it is where most of Cast's SDK use is.
3. An agent gets the answer in about a second without building anything.
4. The rules that stay after the migration (packages inside `player/local`, purity of `domain`)
   are about Kotlin files and packages, which is Konsist's model.

It was tried on this tree before choosing: a scratch project outside the repository, Konsist
`1.0.0-beta.1` (what `latest.release` resolves to; it depends on
`kotlin-compiler-embeddable:2.4.21`), `Konsist.scopeFromExternalDirectory(<repo>)`. It parsed 896
files and reported the same per-module counts as `grep`, apart from `InfoText.kt`, which uses an
SDK type by its full name and has no import. Rule R1 is therefore a text check on the file
(`org.jellyfin.sdk.`), not an import check; the test module does both kinds.

Two things to know when writing the test:

- Agents work in `.claude/worktrees/<name>/`, so the repository path itself contains `.claude`.
  Filter on the path **relative to the project root**, or the test sees no files in a worktree
  and passes.
- Konsist finds the project root by walking up from the working directory. It also parses
  `build/` and `buildSrc/` unless they are filtered out.

The risk is that `1.0.0-beta.1` is a beta. It is a test-only dependency and the rules use a
small part of its API. If it stops keeping up with Kotlin, R1 to R4 are plain text checks that
survive without it (decision D4).

### Where the checks run

CI runs `./gradlew testDebugUnitTest --continue` on every pull request since #720. A pure Kotlin
module has a `test` task and no `testDebugUnitTest`, so the `architecture` and `domain` modules
register a `testDebugUnitTest` task that depends on `test`. Then CI, the upstream-sync skill's
check step and the command in `CLAUDE.md` all pick them up unchanged.

## 2.2 Contract tests

A contract suite is an abstract test class per port. Each implementation of the port has a
concrete subclass that only says how to build the implementation over a fixed set of data. The
tests are written once.

### The fixed data

One small library, described in code (`ContractLibrary`): a movie with a resume position, a
favourite movie, a series with two seasons of three episodes of which one is played, a box set,
one person, an item with two media sources, an item with an external subtitle, segments on one
episode. Each implementation has to present that library:

| Implementation | How it presents the library |
|---|---|
| `FakeMediaServer` (in the `testing` module) | Holds it in memory. This is the fake every ViewModel test uses, so the contract is what keeps it honest |
| Jellyfin adapter | Serves recorded JSON for it from a stand-in HTTP server on a `ServerSocket`, the pattern `data/src/test/.../repository/HomeRowFieldsTest.kt` already uses. The JSON is recorded once from the Docker test server (`docs/dev-environment.md`) |
| Emby adapter, later | The same, with recordings from an Emby server |
| Jellyfin adapter, live (optional) | Against the Docker test server when `EPHYRA_TEST_SERVER` is set. Not in CI |

### The suites

```kotlin
abstract class CatalogContract {
    abstract fun catalog(): CatalogPort

    @Test fun `a movie has its name, runtime and resume position`()
    @Test fun `items of a kind are only that kind`()
    @Test fun `a page reports the total only when asked`()
    @Test fun `episodes of a season come in episode order`()
    @Test fun `next up is the first unplayed episode`()
    @Test fun `an unknown id gives null from item and throws from movie`()
    @Test fun `an unanswered request throws ServerException Unreachable`()
    // …
}
abstract class UserDataContract { /* favourite and played round-trip; unknown item */ }
abstract class PlaybackContract {
    // A source that plays directly has a URL and transcoded = false.
    // A capped request for a high-bitrate source comes back transcoded.
    // audioStreamIndex without mediaSourceId is ignored.
    // start, progress and stop reach the server with the play session of the stream.
    // Capability-gated: `assumeTrue(capabilities.mediaSegments)` before the segment tests.
}
abstract class ServerConnectorContract { /* resolve an address, login, wrong password, Quick Connect when supported */ }
```

Tests for a feature behind a capability flag start with an assumption on the flag, so an
implementation without the feature skips them instead of failing.

### The repository, which used to be "online and offline"

The offline implementation is gone, so there is no second implementation of the repository to run
a shared suite against. What needs pinning instead is the Room behaviour that is the same for
every server. That is an ordinary test class for the repository, built with `FakeMediaServer` and
an in-memory Room database:

- a downloaded item is returned from Room when the server throws or takes over 3 s;
- a list has the local source of a downloaded item merged in;
- `getPlaybackSources` returns a finished download without asking the server;
- a favourite, played or progress change is written and marked before the server is asked, and
  the mark is cleared only when the server answered;
- `postPlaybackStop` applies the resume thresholds.

Parts of this exist (`StoredItemFallbackTest`, `PendingUserDataSyncTest`,
`UnfinishedDownloadPlaybackTest`, `StopUserDataTest`), built on reflection proxies or a real
`JellyfinApi`. They move onto the fake when the repository is split.

### Frameworks

The unit tests are JUnit 4 on the JUnit Platform (Vintage engine), with `kotlin.test` assertions
available since #720; #699 tracks the move to Jupiter. The adapter needs an Android `Context` for
the SDK client, so its tests need Robolectric, which has no Jupiter runner yet. The contract
classes therefore use JUnit 4 annotations and `kotlin.test` assertions, and move with #699.

33 test files fake `JellyfinRepository` today, 27 of them through `java.lang.reflect.Proxy`
(`JellyfinRepository::class.java`), dispatching on the method name: 11 in `modes/film`, 8 in
`player/local`, 4 in `data`, 3 in `core`, 1 in `app/tv`. A proxy keeps compiling when a signature
changes and then fails at run time, or passes for the wrong reason when a method is renamed.
Every task that changes the interface lists this as a trap, and the command to find them:
`grep -rl "JellyfinRepository::class.java" --include=*.kt . | grep /src/test`.

## 2.3 Test gaps

There are about 1,560 unit tests and no instrumented tests (only the baseline profile module).
What the tests do not cover, by phase:

| Gap | Makes risky | Has to exist first |
|---|---|---|
| `PlayerViewModel` (2,836 lines) has no test of its own. `PlayerInvariantsTest` reads source text for 16 invariants. Untested: engine construction, `toMediaItem`, the order of start, progress and stop reports, `onMediaItemTransition`, quality and audio switches | Phase 5 (playback) | Extract-and-test, one piece at a time: the reporter is moved out together with its tests, not refactored in place. `PlayerInvariantsTest` greps `PlayerViewModel.kt` and `MPVPlayer.kt` by path and content, so moved code needs the invariant moved with it |
| The playback info request: which device profile, `enableTranscoding`, the bitrate cap, the `mediaSourceId` and `audioStreamIndex` pair. No test | Phase 3 (splitting the repository) and phase 5 | A characterization test over a stand-in server that records the request body, for ExoPlayer, mpv and mpv with a cap, and for the three download cases |
| `MediaSourceInfo.toFindroidSource`: transcode, file or HTTP path. No test | Phase 3 | Part of the same characterization test |
| `postPlaybackStart`, `postPlaybackProgress`, `postPlaybackStop`: request bodies and the Room writes. Only the helpers (`PlaySessions`, `stopUserData`) are tested | Phase 3 | The same |
| The mappers `BaseItemDto.toFindroid*`. A few fields are tested (`HomeRowFieldsTest`, `SegmentsTest`, `ServerUrlTest`); no test maps a whole item | Phase 3 (moving the mappers) | Golden tests: recorded JSON for one item of each kind, mapped and compared with a stored result |
| `SyncWorker.doWork` against a server. The decisions are tested (`SyncWorkerDecisionsTest`, `LastPlayedDateSyncTest`), the calls are not | Phase 4 | A test of the new port's fake taking the place of the calls; the decisions stay as they are |
| `SetupRepositoryImpl`: one test (`AddSavedServerSessionTest`). Login, Quick Connect and address scoring are tested from the ViewModels with fakes | Phase 4 | The connector contract suite |
| `CastPlayerControllerImpl` (1,056 lines): no test. Its helpers are tested (`CastPlaybackReporterTest`, `CastTracksTest`, `CastUrlsTest`, `CastPlaylistOrderTest`, `CastDeviceProfileTest`), only in the `proprietary` flavor | Phase 6 | The helper tests are the net. There is no receiver to test on since 2026-11-15, only the debug stand-in (`FakeCast.kt`) |
| `DownloaderImpl.downloadItem` (fresh and resume). The queue and the engine are tested | Nothing in this plan restructures it | Nothing; noted so nobody assumes it is covered |
| Device behaviour: the display refresh-rate vote, mpv on real hardware, Dolby Vision | Phase 5 | `docs/mpv-checklist.md` on both backends for every PR under `player/local` or a player screen, as `CLAUDE.md` already requires. mpv crashes emulators with a host GPU, so this needs a phone |

The pattern for phase 3 and 5 is the same: **pin the behaviour at the HTTP boundary first** (what
the app sends and what it makes of the answer), then move code behind it. A test at that boundary
survives every refactoring in between, because neither side of it moves.
