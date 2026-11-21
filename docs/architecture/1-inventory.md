# 1. Inventory

What the code does today, on `main` at `3c7c205c9` (2026-11-20). "Production" means everything
outside `src/test*` and `build/`.

The counts come from these commands, run from the repository root:

```bash
# Production files that mention the SDK (imports, fully qualified names, KDoc links)
grep -rl "org.jellyfin.sdk" --include=*.kt . | grep -v "/build/\|/src/test\|^./.claude"
# Files that use the SDK wrapper
grep -rl "import dev.jdtech.jellyfin.api.JellyfinApi" --include=*.kt app core modes player setup settings | grep -v "/build/\|/src/test"
# Modules that declare the SDK
grep -l "libs.jellyfin.core" */build.gradle.kts */*/build.gradle.kts
```

## 1.1 Where SDK types cross a module boundary

### By module

| Module | Files mentioning the SDK | Declares `libs.jellyfin.core` | Uses `JellyfinApi` | Test files importing the SDK |
|---|---|---|---|---|
| `data` | 29 | yes | (owns it) | 8 |
| `app/phone` | 14 | yes | 3 | 4 |
| `modes/film` | 12 | yes | 1 | 8 |
| `core` | 9 | yes | 7 | 6 |
| `player/cast` | 7 (5 in `proprietary`/`debug`, 2 in `main`) | yes | 2 | 2 (`testProprietary`) |
| `setup` | 5 | yes | 4 | 6 |
| `app/tv` | 5 | yes | 7 | 1 |
| `player/local` | 4 | yes | 1 | 10 |
| `settings` | **0** | **yes (unused)** | 0 | 0 |
| `player/core` | 0 | no | 0 | 0 |
| **Total** | **85** (56 outside `data`) | **9** | **25** outside `data` | 45 |

Every dependency in the build is `implementation`; nothing uses `api(...)`. So each module that
touches an SDK type has to declare the SDK itself, which is why the line is in nine build files.
That is also the enforcement lever: remove the line and the compiler rejects every use, including
the ones that go through a property type without an import.

### By type, outside `data`

| Type | Files | Kind of leak |
|---|---|---|
| `BaseItemKind` | 23 | Enum used in UI and as a repository parameter |
| `MediaStreamType` | 10 | Enum read from `FindroidMediaStream.type` |
| `InvalidStatusException` | 6 | Exception type (plus `TimeoutException`, `SecureConnectionException` once each) |
| `PersonKind` | 4 (+1 by full name in `InfoText.kt`) | Enum read from `FindroidItemPerson.type` |
| `ItemFields` | 4 | Request parameter built outside `data` |
| `BaseItemDto` | 4 | DTO passed through |
| `SubtitleDeliveryMethod` | 3 | Enum read from `FindroidMediaStream.deliveryMethod` |
| `ServerDiscoveryInfo`, `QuickConnectResult`, `RecommendedServerInfoScore` | 2 each | DTOs and a call parameter in `setup` |
| `PlaybackInfoResponse`, `MediaSourceInfo` | 2 each | DTOs in Cast |
| `buildDeviceProfile` and five profile enums | 2 each | The two Chromecast device profiles |
| `ItemFilter`, `VideoRangeType`, `PublicSystemInfo`, `PlaybackInfoDto` and 12 more | 1 each | Mixed |

The quick scan said the leak is dominated by `BaseItemKind`, `BaseItemDto` and `MediaStreamType`.
That holds for the whole tree (31, 18 and 12 files), but 14 of the 18 `BaseItemDto` files are the
mappers in `data`. Outside `data` the third type is `InvalidStatusException`, not `BaseItemDto`.

### Enum used in UI

- **`BaseItemKind`.** Nine constants are used: `MOVIE`, `SERIES`, `SEASON`, `EPISODE`, `BOX_SET`,
  `FOLDER`, `PHOTO_ALBUM`, `VIDEO`, `MUSIC_VIDEO`. It travels three ways:
  - as a value: `CastPlayback.play(itemId, itemKind: BaseItemKind, …)` and
    `CastNowPlaying.kind` (`player/cast/.../CastViewModel.kt`), read by `CastPlayer.kt` and
    `CastMiniPlayer.kt` to pick a poster or a 16:9 frame;
  - as its `serialName` in a string: the `"itemKind"` extra of `PlayerActivity` (phone screens
    `EpisodeScreen`, `MovieScreen`, `ShowScreen`, `SeasonScreen`, `SeasonWideLayout`,
    `ItemExtras`, `components/PlayDownload`) and `PlayerRoute.itemKind` on TV
    (`app/tv/.../NavigationRoot.kt`). `PlayerViewModel.initializePlayer` turns it back with
    `BaseItemKind.fromName`.

  `pending_downloads.itemKind` in Room looks like the same thing and is not: `DownloaderImpl`
  writes the literal strings `"MOVIE"` and `"EPISODE"` there.
- **`MediaStreamType`.** Read from `FindroidMediaStream.type` in `modes/film`
  (`domain/MediaInfo.kt`, `domain/VideoMetadataParser.kt`), TV `ce/presentation/film/SeasonScreen.kt`,
  `core` (`DownloaderImpl.kt`, two preview fixtures), `player/local`
  (`domain/SubtitleFiles.kt`, `domain/SeriesTrackPreference.kt`), Cast (`CastTracks.kt`) and
  one phone preview (`MediaInfoSheet.kt`). **Room stores it by name** in
  `mediastreams.type` (`FindroidMediaStreamDto`).
- **`PersonKind`.** `ACTOR`, `DIRECTOR`, `WRITER`, in `MovieViewModel`, `ShowViewModel`,
  `EpisodeViewModel`, one `core` preview fixture and `InfoText.kt` (by full name, in a preview).
- **`VideoRangeType`.** One `when` over 11 constants in `VideoMetadataParser.kt`.
  **`SubtitleDeliveryMethod`.** One comparison in `SubtitleFiles.kt`.

### DTO passed through

- `JellyfinRepository.getUserViews(): List<BaseItemDto>` is held by `HomeViewModel`
  (`cachedLibraries`), filtered by `HomeRowsStore.homeLibraries()`, and mapped by
  `BaseItemDto.toView` in `core/.../utils/CoreExtensions.kt`. It reads the SDK's
  `collectionType` enum without importing it.
- `JellyfinRepository.getPublicSystemInfo(): PublicSystemInfo` is read by
  `modes/film/.../home/ServerName.kt` (`serverName`, `id`) and `AboutViewModel` (`version`).
- `JellyfinRepository.getUserConfiguration(): UserConfiguration?` is read once, in
  `PlayerViewModel`, for `enableNextEpisodeAutoPlay`.
- `SetupRepository` returns `Flow<ServerDiscoveryInfo>` and `QuickConnectResult`.
  `AddServerViewModel` maps the first to `DiscoveredServer` (`core/.../models`); `LoginViewModel`
  keeps the second in a local variable and puts only `.code` in the state.
- `CastMediaItem.playbackInfo: PlaybackInfoResponse?` (`player/cast/src/main/.../CastModels.kt`)
  is also serialized into the receiver's `customData` and read back.
- `org.jellyfin.sdk.model.DateTime` (a typealias) is the type of
  `FindroidEpisode.premiereDate`, `FindroidEpisode.lastPlayedDate` and `FindroidShow.endDate`,
  of a Room converter (`database/Converters.kt`) and of `DateTime.format` in `CoreExtensions.kt`.

### SDK call made outside `data`

| Where | What it calls |
|---|---|
| `setup/.../data/SetupRepositoryImpl.kt` | Discovery (`jellyfin.discovery.discoverLocalServers`, `getAddressCandidates`, `getRecommendedServers`), `authenticationApi` (login, Quick Connect), `brandingApi`, `userApi.getPublicUsers`, `api.update(...)` to switch server and user |
| `setup/.../addresses/ServerAddressesViewModel.kt` | Builds its own `JellyfinApi`, calls discovery and writes `ServerDatabaseDao` directly: a ViewModel that bypasses its repository |
| `core/.../work/SyncWorker.kt` | `jellyfin.createApi(...)` per saved user, `libraryApi.getItems`, `sessionApi.getSessions`, `sessionApi.reportPlaybackStopped`, `userDataApi.updateItemUserData` |
| `core/.../core/session/ServerSessionKeeper.kt` | `api.webSocket.subscribe<PlaystateMessage>()`, `userApi.getCurrentUser()`, `api.update(accessToken = null)` |
| `core/.../di/ApiModule.kt`, `ApiSession.kt` | Create the wrapper and set base URL, token and user id on it |
| `core/.../core/reachability/ServerReachability.kt` | `probeServer()`, `requestWatcher` |
| `core/.../work/UserDataSync.kt` | `serverReturned` |
| `player/cast/src/proprietary/.../CastPlayerControllerImpl.kt` | `mediaInfoApi.getPostedPlaybackInfo` with a Chromecast device profile; reads `api.baseUrl` and `api.accessToken` |
| `player/local/.../presentation/PlayerViewModel.kt` | `jellyfinApi.httpClient` (the OkHttp client both engines stream through), `jellyfinApi.userId` |
| Phone `HomeScreen.kt`, `ProfilePinGateScreen.kt`, `UsersScreen.kt`; TV `MainScreen.kt`, `TvProfilePinGateScreen.kt`, `UsersScreen.kt` | `JellyfinApi.getInstance(context).api.baseUrl` in a composable, for the profile picture URL |
| TV `ScreensaverScreen.kt`, `ScreensaverService.kt`, `WatchNextSync.kt`, `MainActivity.kt`, `MainScreen.kt` | `jellyfinApi.userId` |

Request parameters built outside `data` are the same leak in a milder form:
`includeTypes: List<BaseItemKind>` (`LibraryViewModel`, `PersonViewModel`, `SearchViewModel`, TV
`SearchScreen.kt`, `ScreensaverItems.kt`), `filters = listOf(ItemFilter.IS_UNPLAYED)`
(`LibraryViewModel`) and `fields: List<ItemFields>` (`ShowViewModel`, `SeasonViewModel`,
`SmartDownloads`, `PlaylistManager`).

### Jellyfin URL shapes built by hand

These do not import anything, so no import check finds them:

| Where | What |
|---|---|
| `data/.../models/FindroidImages.kt`, `FindroidItemPerson.kt`, `UserImage.kt` | `items/{id}/Images/{type}?tag=`, `Users/{id}/Images/Primary?tag=` |
| `player/local/.../PlayerViewModel.kt` (`"$base/Items/$itemId/Images/Chapter/…"`) and `player/cast/.../CastViewModel.kt` | Chapter image URLs |
| `app/tv/.../ce/presentation/film/HeroImages.kt` | Rewrites an episode's primary URL into `items/{seasonId}/Images/Backdrop/0` |
| `core/.../utils/JellyfinImageSizeInterceptor.kt` | Coil interceptor that matches `(items|users)/…/images/` and adds `fillWidth` and quality; `JellyfinImageCacheStrategy` relies on `tag=` |
| `player/cast/src/proprietary/.../CastUrls.kt` | `/Videos/{id}/stream?static=true&mediaSourceId=…&api_key=…` |
| `data/.../api/JellyfinApi.kt` | `/System/Info/Public` (the reachability probe), `api_key` / `ApiKey` (revoked-token detection) |

### Ids

Ids are `java.util.UUID` throughout: 160 production files import it and 46 places call
`UUID.fromString`. Navigation routes carry ids as `String` and convert at the edge
(`NavigationRoot.kt` in both apps, `PlayerActivity`). Room stores UUIDs as `TEXT` through
`Converters`, so the column type would not change with another id type. Server ids and media
source ids are already `String`.

This matters for Emby, whose item ids are not UUIDs (they are numeric strings, as far as we know;
not checked against a server). It does not block the move to ports. Decision D1 in the ADR: aliases now, the choice when the Emby work starts.

## 1.2 What `JellyfinRepository` exposes

`data/.../repository/JellyfinRepository.kt` has 49 functions. There is **one implementation**,
`JellyfinRepositoryImpl` (1,113 lines). The offline implementation was deleted in #281:
`RepositoryModule` always provides `JellyfinRepositoryImpl`, which falls back to the Room copy of
a downloaded item when the server fails or takes over 3 s (`StoredFallback.kt`).

### Jellyfin-specific parts of the interface

| Member | SDK type | Server-neutral replacement |
|---|---|---|
| `getPublicSystemInfo()` | `PublicSystemInfo` | `ServerInfo(id, name, version, product)` |
| `getUserViews()` | `List<BaseItemDto>` | `List<FindroidCollection>`; the model exists, `getLibraries()` already returns it |
| `getItems`, `getItemsPage`, `getItemsPaging`, `getItemCount`, `getRandomItemsWithBackdrop`, `getPersonItems`, `getSearchItems`, `getLatestMedia` | `List<BaseItemKind>` | `List<ItemKind>` |
| `getItems`, `getItemsPage`, `getItemsPaging`, `getItemCount` | `List<ItemFilter>` | `Set<ItemFilter>` of our own (only `IS_UNPLAYED` is used) |
| `getEpisodes(fields = …)` | `List<ItemFields>` | `Set<EpisodeField>` (`OVERVIEW`, `CAN_DOWNLOAD`, `CHAPTERS`, `TRICKPLAY` are used) |
| `getUserConfiguration()` | `UserConfiguration?` | `suspend fun autoPlayNextEpisode(): Boolean?` |
| `getBaseUrl()`, `getUserId()` | none, but they exist so callers can build Jellyfin URLs and Room keys | A session port; URL building moves behind it |
| `getSeasons(offline = …)`, `getEpisodes(offline = …)` | none | Left over from the offline repository; "read the stored list" is a Room question |

Everything else already returns `Findroid*` models, `String` or `ByteArray`.

### The models

The `Findroid*` models in `data/.../models/` are the app's own, but they are not clean yet:

- **SDK-typed properties:** `FindroidMediaStream.type`, `.videoRangeType`, `.deliveryMethod`;
  `FindroidItemPerson.type`; `FindroidEpisode.premiereDate`, `.lastPlayedDate`;
  `FindroidShow.endDate`; `CollectionType.latestItemTypes`; and the Room entity
  `FindroidMediaStreamDto.type`.
- **The mappers sit in the model files and take the repository:** every model file has a
  `BaseItemDto.toFindroid…(jellyfinRepository, …)` extension, and they call
  `jellyfinRepository.getBaseUrl()` and `getStreamUrl()` while mapping. That is why 21 of the 29
  SDK files in `data` are model files.
- **Two Android types:** `FindroidImages` and `FindroidItemPerson` use `android.net.Uri`. Ten
  consumer files call a `Uri` method on an image; the rest hand it to Coil.

### What the implementation mixes

Counting calls per function in `JellyfinRepositoryImpl`: 32 functions only call the server, 5 only
read Room (`getDownloads`, `getSegments`, `getSeriesTrackPreferences`, `storedItem`,
`localSourcesFor`), and 11 do both. The Room part is server-neutral and would be the same for
Emby:

- item getters fall back to the stored copy (`serverOrStored`, `storedItem`);
- lists get the local sources of downloaded items merged in (`localSourcesFor`,
  `toFindroidItems`);
- `getPlaybackSources` returns a finished download before asking the server;
- progress, favourite and played changes are written to Room and marked `toBeSynced`
  (`writeAndReport` in `UserDataReport.kt`), then sent;
- `postPlaybackStop` applies the server's resume rules locally (`stopUserData`,
  `ResumeThresholdsStore`).

`JellyfinRepositoryImpl.getPlaybackSources` also reads the `playerBackend` preference to choose
the device profile. The engine choice belongs to the player, not to the data layer.

### Room

`ServerDatabase` is at version 20 with 14 entities: servers, addresses and users; movies, shows,
seasons and episodes of downloads; sources, media streams, trickplay, segments, user data,
pending downloads and series track preferences. `servers` has **no server type column**.
22 production files outside `data` use `ServerDatabaseDao` directly (13 in `core`, 5 in
`modes/film`, 3 in `setup`, 1 in `player/local`).

### A server-neutral port

Two layers, because the Room behaviour above must not be written twice:

- **Server ports** (one implementation per server type, no Room): catalog reads, user data
  writes, playback (sources, stream URLs, segments, trickplay, reports), session, and connecting
  (discovery, login). Signatures in [3-target.md](3-target.md).
- **The repository** that ViewModels call stays one class. It composes a server port with Room.
  It keeps its name until the Emby work starts (decision D2, #774).

## 1.3 Playback

### How it is tied to Jellyfin

| Part | Where | Coupling |
|---|---|---|
| Device profile | `data/.../player/DeviceProfileBuilder.kt` returns the SDK's `DeviceProfile`. Four profiles: `getDeviceProfile` (ExoPlayer, from probed codecs), `getDirectPlayProfile` (mpv), `getDirectPlayTranscodeFallbackProfile` (mpv with a bitrate cap), `getDownloadProfile` | The probing (`MediaCodecList`, display HDR) is about the device; the assembly is Jellyfin's format. `DeviceCapabilityReport.kt`, `AboutViewModel` and the TV device profile screen show the result |
| Playback info | `JellyfinRepositoryImpl.getPlaybackSources` and `getMediaSources` call `mediaInfoApi.getPostedPlaybackInfo`. Cast calls it itself | The server decides direct play or transcode. The client only sees `transcodingUrl` set or not (`MediaSourceInfo.toFindroidSource`) |
| Stream URL | `toFindroidSource`: the transcoding URL joined to the base URL, or `videoApi.getVideoStreamUrl(static = true)`, or the HTTP path. Subtitle URLs come from `deliveryUrl` | The player gets finished URLs. The media HTTP client adds no auth header, so credentials are in the URL |
| Reports | `postPlaybackStart`, `postPlaybackProgress`, `postPlaybackStop`, `setPlaySession`, with `PlaySessions` and `stopUserData` | Server-neutral in signature; ticks (100 ns) are the unit |
| Segments | `getSegments` (Room first), `getServerSegments` (`mediaSegmentApi`) → `FindroidSegment` | Neutral model. The fields are named `…Ticks` but hold milliseconds |
| Trickplay | `FindroidTrickplayInfo` mirrors Jellyfin's tile sheets; `getTrickplayData(itemId, width, index)` | The tile-sheet model is Jellyfin's |
| Chapters | `PlayerChapter.imageTag`, URL built in `PlayerViewModel` and `CastViewModel` | Jellyfin URL shape outside `data` |
| Player models | `player/core` (9 files): `PlayerItem`, `ServerAudioTrack`, `ExternalSubtitle`, `PlayerChapter`, `TrickplayInfo`, `Trickplay`, `Track`, `TrackDisplayInfo`, `SubtitleSyncCue` | No SDK import. `UUID` ids |

### ExoPlayer and mpv

Both engines are already exposed as `androidx.media3.common.Player`: `MPVPlayer` extends
`BasePlayer`. `PlayerViewModel` holds one `player: Player` and one `MediaSession`, listens through
`Player.Listener`, and uses media3 track selection on both. The same `MediaItem` feeds both.

Where they differ:

| Place | What |
|---|---|
| `PlayerViewModel` init, `when (playerBackend)` | Builds either engine: ExoPlayer with `DefaultRenderersFactory`, `SubtitleOffsetRenderer`, `buildMediaSourceFactory`, `AudioDecoderFallback`; mpv with vo, ao, hwdec and subtitle options |
| `PlayerViewModel`, 3 `MPVPlayer` checks | Subtitle delay (mpv has its own call; ExoPlayer uses the offset renderer) and mpv subtitle option changes |
| `app/phone/.../utils/PlayerGestureHelper.kt` | `MPVPlayer.updateZoomMode` |
| `JellyfinRepositoryImpl.getPlaybackSources` | The device profile depends on the engine |

Files: `player/local/.../mpv/` (14 files) is mpv only. The ExoPlayer-only files are not in a
package of their own: `domain/AudioDecoderFallback.kt`, `domain/MediaSourceFactory.kt`,
`domain/ImageSubtitleFiles.kt`, and in `presentation/` `SubtitleOffsetRenderer.kt`,
`SubtitleReplaySampleStream.kt`, `SubtitleDelayMediaPeriod.kt`, `SubtitleDelayMediaSource.kt` and
`SubtitleFileMediaSource.kt`. The rest of `domain/` (28 files in all) and `presentation/` is
shared.

There is no fallback from one engine to the other; the engine is one preference.

### Where the logic is

- **What to play:** `PlaylistManager` (`player/local/.../domain/`). `getInitialItem` builds the
  queue per item kind, `FindroidItem.toPlayerItem` picks the source (a finished download first)
  and asks for playback sources. Cast reuses it.
- **Reporting:** `PlayerViewModel` (`sendPlaybackStart`, `reportProgress`, `reportStopped`), with
  the pure helpers `PlaybackStartGate`, `ReportedPause`, `PlaybackStopReport`. Three timers call
  in: `PlayerActivity`, `TvPlayerScreen`, and a background loop in the ViewModel.
- **Queue and up next:** `PlaylistManager`, mirrored in `PlayerViewModel`; `UpNextCountdown`,
  `StillWatchingTracker`, `AutoPlayNext`.
- **Tracks:** `SeriesTrackPreference`, `TrackPicks`, `SubtitleFiles`. `PlayerViewModel` reads and
  writes the track preferences through `ServerDatabaseDao` directly.

## 1.4 Setup, downloads and Cast

### Setup

`setup` has its own port, `SetupRepository` (20 functions), and its own implementation in the same
module, `SetupRepositoryImpl` (369 lines), which calls the SDK through `JellyfinApi`. Nothing in
`data` is involved except Room.

- **Adding a server** goes through the SDK's `getRecommendedServers`, which scores a server and
  reports `InvalidProductName` when it is not Jellyfin. An Emby server is rejected here today, so
  the server type has to be found before this call, not after.
- **Login** maps `AuthenticationResult` to a `User` row and sets the token on the client.
- **Quick Connect:** `getQuickConnectEnabled`, `initiateQuickConnect`, `getQuickConnectState`,
  `authenticateWithQuickConnect`. `QuickConnectError.kt` reads HTTP statuses out of
  `InvalidStatusException` to tell "off" from "expired".
- **Switching server or user** is `api.update(baseUrl, accessToken)` plus `userId`, done in
  `SetupRepositoryImpl`, `ApiSession.applySession` and `ServerSessionKeeper.signOut`.
- **Flavors:** none. `setup` is the same in every build.

### Downloads

The download state machine is already separate from Jellyfin:

- `MediaDownloadEngine` (`core/.../utils/download/`) transfers bytes with OkHttp:
  pending → paused or running → successful or failed, with Range resume.
- `DownloadQueue` (`core/.../core/presentation/downloader/`) schedules:
  `Pending`, `Downloading`, `Paused`, `Completed`, `Failed`, with retry backoff, and restores
  itself from Room (`pending_downloads`, sources whose path ends in `.download`).
- `DownloaderImpl` (1,508 lines) asks the repository for everything Jellyfin-shaped:
  `getMediaSources`, `getServerSegments`, `getTrickplayData`, `getShow`, `getSeason`,
  `getMovie`, `getEpisode`, `getUserId`. It never builds a URL; it uses `source.path` and
  `mediaStream.path`.

What is left of the SDK in downloads: `MediaStreamType.SUBTITLE` in `DownloaderImpl`,
`ItemFields.CAN_DOWNLOAD` in `SmartDownloads`, and `SyncWorker`, which is the real leak (see 1.1).
Downloads exist on the phone only; there are no flavors in this code.

### Cast

`player/cast` has three source sets that matter:

| Source set | Contents | SDK |
|---|---|---|
| `main` | The ports `CastSessionManager` and `CastPlayerController`, the models, `CastViewModel`, the DI module | `PlaybackInfoResponse` in `CastMediaItem`, `BaseItemKind` in `CastViewModel` |
| `libre` | Stubs (`availability = NOT_IN_BUILD`) | none |
| `proprietary` | Google Cast: `CastPlayerControllerImpl` (about 1,050 lines), `CastSessionManagerImpl`, `CastTracks`, `CastUrls`, `CastPlaybackReporter`, `CastPlaylistOrder`, `devices/Chromecast.kt`, `devices/ChromecastH265.kt` | Playback info request, `MediaSourceInfo`, two device profiles |
| `debug` | `FakeCast.kt`, the stand-in for emulators | `BaseItemKind.fromName` |

Cast already has the shape this plan wants: two ports in `main`, a real adapter and a stub. The
problem is on its other side. `CastPlayerControllerImpl` asks the server for playback info itself
(the receiver's profile differs from the phone's), keeps the SDK response, and builds stream URLs
by hand in `CastUrls.kt`. Reports and item lookups do go through `JellyfinRepository`.

A change to anything in `proprietary` is not compiled by the default (`libre`) build, and the
`testProprietary` tests only run for that flavor. `CastSessionManagerImpl` injects `JellyfinApi`
and does not use it.

## 1.5 Features that need a capability flag

Not every server will have these. The first column is what the code does today; the Emby column
is from general knowledge of Emby and **has not been checked against a server**.

| Feature | Today | Emby (unverified) |
|---|---|---|
| Quick Connect | `SetupRepository.getIsQuickConnectEnabled` hides the button | No Quick Connect |
| Public user list on the login screen | `getPublicUsers` | Has one |
| Login disclaimer | `brandingApi` | Different endpoint or none |
| Media segments (skip intro, credits) | `mediaSegmentApi`; empty list when none | Chapter markers instead of a segments API |
| Trickplay tile sheets | `trickPlayApi`, `FindroidTrickplayInfo` | BIF files, a different model |
| Theme songs | `getThemeSongUrl`, default `null` | Has theme media, different endpoint |
| Similar items, extras, "included in" collections | Defaults in the interface return empty | Mixed |
| Random items with a backdrop | `getRandomItemsWithBackdrop`, with a local fallback | Likely |
| Server resume thresholds | `ResumeThresholds.of(ServerConfiguration)`, default 5 % / 90 % / 300 s | Unknown |
| Auto-play next episode from the server | `UserConfiguration.enableNextEpisodeAutoPlay` | Unknown |
| Session capabilities and the WebSocket | `postCapabilities`, `ServerSessionKeeper` | Different socket protocol |
| Renaming the device on the server | `updateDeviceName` | Unknown |
| A capped-bitrate download transcode | `getMediaSources(maxBitrate = …)` | Needs its own check |
| Search with a limit per kind | `getSearchItems(itemKinds = …)` | Can be done client-side |
| Server-side sign out of a token | `JellyfinApi.endSession` | Has a logout endpoint |
| Local discovery | SDK discovery | Similar broadcast, different payload |

Several of these already degrade through a default in the interface (`emptyList()`, `null`). A
flag is needed where the UI has to hide something rather than show nothing: Quick Connect, the
skip buttons' settings, trickplay settings, the device rename field and the public user list.

No code in the UI modules checks a server version today. `AboutScreen` only shows it.
