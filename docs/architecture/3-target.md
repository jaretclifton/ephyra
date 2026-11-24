# 3. Target

**Status: historical.** This was the target of ADR 0001 (2026-11-20), and it was built. ADR 0002
then moved the code further, so this file is kept as written and not corrected. Where the code
differs (2026-11-23):

- A pure Kotlin `application` module holds `MediaRepository` and the store ports `ServerStore`,
  `DownloadStore`, `PendingUserDataStore`; `data` implements them. Features depend on
  `application`, not on `data`: only the apps depend on `data` (rule R9), as on `server/jellyfin`.
  The dependency picture of 3.1 is replaced by the one in the [README](README.md).
- The download engine is the `downloads` module, out of `core`.
- Only `data` sees `ServerDatabaseDao` (R4, baseline empty): `MediaRepositoryImpl` and the
  `Room*Store` use it, everything outside `data` goes through a store.
- Ids are value classes over `String` (`ItemId`, `UserId`, `ServerId`, `MediaSourceId`), not
  `UUID` aliases (D1 replaced); the repository is `MediaRepository` and the models are `Item`,
  `Movie`, `ItemSource`, `Library`, … (D2 replaced, #774). The signatures below use the old names.
- The server in use is one `ActiveServer` value; `MediaServer.at(active)` hands it to the adapter.
- New in `domain`: `PlayState` (the user-data rules, which replaced `stopUserData`),
  `DownloadState`, `Ticks`, `AccountKey`.

The module layout at the end of the migration and the ports as Kotlin signatures. The signatures
are the design; a task issue may adjust a name, not the direction of a dependency.

## 3.1 Modules

```
domain             NEW   Pure Kotlin. The models (Findroid*, SortBy, SortOrder, ItemsPage), ids,
                         enums, the ports, and the rules with no I/O (stopUserData,
                         ResumeThresholds, PlaySessions).
server/jellyfin    NEW   Android library. The only module that declares org.jellyfin.sdk.
                         JellyfinApi, the mappers, the server ports' implementations, the
                         connector, the device profiles, Jellyfin's image URL rules. Its own
                         Hilt module.
server/emby        LATER The same ports for Emby.
data                     Room (database, DAO, entities) and the repository that composes a
                         server with Room. No SDK.
core                     DI, the downloader, the workers, shared Compose. No SDK.
modes/film               ViewModels. No SDK.
setup                    Onboarding ViewModels and SetupRepository over a ServerConnector. No SDK.
settings                 Preferences. No SDK (it has none today, only the dependency line).
player/core              Player models. Unchanged.
player/local             engine/exo, engine/mpv behind PlayerEngine; the shared player.
player/cast              Unchanged shape (ports in main, Google Cast in proprietary). No SDK.
app/phone, app/tv        Screens and the composition root: the only modules that depend on
                         server/jellyfin.
architecture       NEW   Pure Kotlin, tests only. The Konsist rules and their baselines.
testing            NEW   Pure Kotlin, used by tests only. FakeMediaServer, ContractLibrary and
                         the contract suites.
```

Dependencies point inwards:

```
app/phone, app/tv ──► server/jellyfin ──► domain
        │                                   ▲
        ├──► modes/film, setup, player/*, core ──► data ──► domain
        └──► (everything above)
```

Rules that follow from it:

- `domain` depends on nothing in this repository. Every module that names a domain type declares
  `domain` itself: the build uses `implementation` everywhere, so nothing comes through `data`.
- `server/jellyfin` depends on `domain` and `settings` (for the timeouts in `Constants`), not on
  `data`: an adapter does not know Room.
- `data` depends on `domain` and `settings`, not on a server module.
- No feature module depends on a server module.

**Moved files keep their package.** `dev.jdtech.jellyfin.models.Movie` stays
`dev.jdtech.jellyfin.models.Movie` when its file moves from `data` to `domain`. A move
then changes no import anywhere, git records it as a pure rename, and an upstream change to the
file still merges. Packages are only named anew for new code
(`dev.jdtech.jellyfin.server.jellyfin`, `dev.jdtech.jellyfin.domain.ports`).

## 3.2 Shared types (`domain`)

```kotlin
package dev.jdtech.jellyfin.models

// Aliases today, so signatures say what an id is. Making them opaque is decided when the Emby work starts (decision D1, #773).
typealias ItemId = java.util.UUID
typealias UserId = java.util.UUID
typealias ServerId = String
typealias MediaSourceId = String

enum class ServerType { JELLYFIN, EMBY }

/** Which player engine plays. Here and not in `player/local`, because a playback request names it. */
enum class PlayerEngineId(val preferenceValue: String) { EXOPLAYER("exoplayer"), MPV("mpv") }

/** The kinds of item the app handles. [serialName] is what a route or an intent extra carries. */
enum class ItemKind(val serialName: String) {
    MOVIE("Movie"), SERIES("Series"), SEASON("Season"), EPISODE("Episode"), BOX_SET("BoxSet"),
    FOLDER("Folder"), PHOTO_ALBUM("PhotoAlbum"), VIDEO("Video"), MUSIC_VIDEO("MusicVideo"),
    COLLECTION_FOLDER("CollectionFolder"), UNKNOWN("Unknown");

    companion object { fun fromName(name: String): ItemKind /* by serialName or enum name */ }
}

// Constant names match the SDK's: Room stores this one by name in mediastreams.type.
enum class MediaStreamType { AUDIO, VIDEO, SUBTITLE, EMBEDDED_IMAGE, DATA, LYRIC }
enum class SubtitleDeliveryMethod { ENCODE, EMBED, EXTERNAL, HLS, DROP }
// serialName is what the SDK matches on; fromNameOrNull keeps doing that (see #776 for what that means for downloads).
// The 13 constants and serial names of SDK 1.9.4.
enum class VideoRangeType(val serialName: String) {
    UNKNOWN("Unknown"), SDR("SDR"), HDR10("HDR10"), HLG("HLG"), DOVI("DOVI"),
    DOVI_WITH_HDR10("DOVIWithHDR10"), DOVI_WITH_HLG("DOVIWithHLG"), DOVI_WITH_SDR("DOVIWithSDR"),
    DOVI_WITH_EL("DOVIWithEL"), DOVI_WITH_HDR10_PLUS("DOVIWithHDR10Plus"),
    DOVI_WITH_ELHDR10_PLUS("DOVIWithELHDR10Plus"), DOVI_INVALID("DOVIInvalid"), HDR10_PLUS("HDR10Plus");

    companion object { fun fromNameOrNull(serialName: String): VideoRangeType? }
}
// All 26 constants of SDK 1.9.4, by name: people are grouped by id and kind, so kinds must not collapse.
// Nothing stores or matches a serial name of this one, so it has none.
enum class PersonKind {
    UNKNOWN, ACTOR, DIRECTOR, COMPOSER, WRITER, GUEST_STAR, PRODUCER, CONDUCTOR, LYRICIST, ARRANGER,
    ENGINEER, MIXER, REMIXER, CREATOR, ARTIST, ALBUM_ARTIST, AUTHOR, ILLUSTRATOR, PENCILLER, INKER,
    COLORIST, LETTERER, COVER_ARTIST, EDITOR, TRANSLATOR, NARRATOR
}
enum class ItemFilter { IS_UNPLAYED }
enum class EpisodeField { OVERVIEW, CAN_DOWNLOAD, CHAPTERS, TRICKPLAY }

data class ServerInfo(val id: String, val name: String, val version: String?, val product: String?)

/** What a server adapter throws. Nothing above an adapter sees another exception type for I/O. */
sealed class ServerException(message: String?, cause: Throwable?) : Exception(message, cause) {
    class HttpStatus(val status: Int, cause: Throwable? = null) : ServerException("HTTP $status", cause)
    class Timeout(cause: Throwable? = null) : ServerException("timeout", cause)
    class Tls(cause: Throwable? = null) : ServerException("TLS", cause)
    class Unreachable(cause: Throwable? = null) : ServerException("unreachable", cause)
}

data class ServerCapabilities(
    val quickConnect: Boolean,
    val publicUsers: Boolean,
    val loginDisclaimer: Boolean,
    val mediaSegments: Boolean,
    val trickplay: Boolean,
    val themeSongs: Boolean,
    val similarItems: Boolean,
    val extras: Boolean,
    val itemCollections: Boolean,
    val serverResumeThresholds: Boolean,
    val autoPlayNextSetting: Boolean,
    val sessionSocket: Boolean,
    val deviceRename: Boolean,
    val cappedDownloadTranscode: Boolean,
)
```

`ItemImages` and `ItemPerson` carry image URLs as `String` instead of
`android.net.Uri`, so the models compile without Android.

## 3.3 Server ports (`domain`, implemented by `server/jellyfin`)

One object per server type gives access to the ports. They know nothing about Room: a movie from
`CatalogPort` has only remote sources.

```kotlin
package dev.jdtech.jellyfin.domain.ports

interface MediaServer {
    val type: ServerType
    val capabilities: ServerCapabilities
    val session: ServerSession
    val catalog: CatalogPort
    val userData: UserDataPort
    val playback: PlaybackPort
}

data class ItemQuery(
    val parentId: ItemId? = null,
    val kinds: List<ItemKind>? = null,
    val recursive: Boolean = false,
    val sortBy: SortBy = SortBy.defaultValue,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val filters: Set<ItemFilter> = emptySet(),
    val startIndex: Int? = null,
    val limit: Int? = null,
)

interface CatalogPort {
    suspend fun serverInfo(): ServerInfo
    suspend fun libraries(): List<Library>
    suspend fun userViews(): List<Library>
    suspend fun item(id: ItemId): Item?
    suspend fun movie(id: ItemId): Movie
    suspend fun show(id: ItemId): Show
    suspend fun season(id: ItemId): Season
    suspend fun episode(id: ItemId): Episode
    suspend fun items(query: ItemQuery, includeTotal: Boolean = false): ItemsPage
    suspend fun itemCount(query: ItemQuery, nameLessThan: String? = null): Int
    suspend fun randomItemsWithBackdrop(kinds: List<ItemKind>, limit: Int): List<Item>
    suspend fun person(id: ItemId): Person
    suspend fun personItems(personIds: List<ItemId>, kinds: List<ItemKind>?, recursive: Boolean, limit: Int?): List<Item>
    suspend fun favorites(): List<Item>
    suspend fun search(query: String, kinds: List<ItemKind>? = null): List<Item>
    suspend fun suggestions(): List<Item>
    suspend fun resumeItems(): List<Item>
    suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<Item>
    suspend fun seasons(seriesId: ItemId): List<Season>
    /** [dateCutoff] comes from a preference; the repository computes it. */
    suspend fun nextUp(seriesId: ItemId?, dateCutoff: java.time.LocalDateTime?): List<Episode>
    suspend fun episodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        fields: Set<EpisodeField>?,
        startItemId: ItemId? = null,
        limit: Int? = null,
    ): List<Episode>
    // Empty or null when the capability is off.
    suspend fun similar(itemId: ItemId, limit: Int): List<Item>
    suspend fun extras(itemId: ItemId, localTrailers: Boolean, specialFeatures: Boolean): List<Movie>
    suspend fun collectionsOf(itemId: ItemId): List<Item>
    suspend fun themeSongUrl(itemId: ItemId): String?
}

/** What the server knows about an item for one user. */
data class ServerUserData(
    val itemId: ItemId,
    val played: Boolean,
    val favorite: Boolean,
    val playbackPositionTicks: Long,
    val lastPlayedDate: java.time.LocalDateTime?,
)

interface UserDataPort {
    suspend fun setFavorite(itemId: ItemId, favorite: Boolean)
    suspend fun setPlayed(itemId: ItemId, played: Boolean)
}

/** User data on the server for one saved user, who need not be the current one. For the sync. */
interface UserDataSyncPort {
    suspend fun userDataOf(itemIds: List<ItemId>): Map<ItemId, ServerUserData>
    suspend fun putUserData(data: ServerUserData)
    suspend fun reportStopped(itemId: ItemId, positionTicks: Long)
    suspend fun itemLeftPlaying(): ItemId?
}

fun interface UserDataSyncPorts {
    fun forUser(baseUrl: String, accessToken: String?, userId: UserId): UserDataSyncPort
}

/** Who plays the stream. The adapter turns this into whatever its server needs (a device profile). */
sealed interface PlaybackTarget {
    data class LocalPlayer(val engine: PlayerEngineId, val directPlayDolbyVision: Boolean) : PlaybackTarget
    data class Download(val transcodeDolbyVision: Boolean) : PlaybackTarget
    data class CastReceiver(val supportsHevc: Boolean) : PlaybackTarget
}

data class PlaybackRequest(
    val itemId: ItemId,
    val target: PlaybackTarget,
    val mediaSourceId: MediaSourceId? = null,
    /** Only together with [mediaSourceId]. */
    val audioStreamIndex: Int? = null,
    /** Bits per second; null is no cap. */
    val maxBitrate: Int? = null,
    /** Ask for the URL of file sources too (downloads need it). */
    val includePath: Boolean = false,
)

data class PlaybackReport(
    val itemId: ItemId,
    val positionTicks: Long,
    val isPaused: Boolean,
    val transcoded: Boolean,
    val playSessionId: String?,
)

interface PlaybackPort {
    /** Sources with finished URLs. `ItemSource.transcoded` and `.playSessionId` say what the server chose. */
    suspend fun sources(request: PlaybackRequest): List<ItemSource>
    suspend fun streamUrl(itemId: ItemId, mediaSourceId: MediaSourceId): String
    suspend fun segments(itemId: ItemId): List<Segment>
    suspend fun trickplayTile(itemId: ItemId, width: Int, index: Int): ByteArray?
    fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String?
    fun seasonBackdropUrl(seasonId: ItemId): String?
    suspend fun reportStart(report: PlaybackReport)
    suspend fun reportProgress(report: PlaybackReport)
    suspend fun reportStopped(report: PlaybackReport)
    suspend fun announceCapabilities()
    /** Null when the server has none; the defaults apply. */
    suspend fun resumeThresholds(): ResumeThresholds?
}

interface ServerSession {
    val baseUrl: String?
    val userId: UserId?
    val accessToken: String?
    /** Tokens the server refused while they were current. */
    val unauthorized: kotlinx.coroutines.flow.Flow<String>
    /** Emits when the server answers again after requests that could not reach it. */
    val serverReturned: kotlinx.coroutines.flow.Flow<Unit>
    /** Told how each request of the app goes; the reachability banner follows it. */
    var requestWatcher: RequestWatcher?
    val probeTimeoutMs: Long
    fun use(baseUrl: String?, accessToken: String?, userId: UserId?)
    /** True when the server answered, false when it did not, null when no server is set. */
    suspend fun probe(): Boolean?
    /** False only when the server says the current token is not valid. */
    suspend fun isTokenValid(): Boolean
    suspend fun autoPlayNextEpisode(): Boolean?
    suspend fun updateDeviceName(name: String)
    /** The tag of the current user's profile picture as the server has it now, or null when they have none. */
    suspend fun currentUserImageTag(): String?
    /** The URL of a profile picture of a user, or null when no server is set. */
    fun userImageUrl(userId: UserId, tag: String): String?
    /** Keeps the server's session socket open while collected. Completes at once when unsupported. */
    fun keepAlive(): kotlinx.coroutines.flow.Flow<Nothing>
}
```

The connector is separate because it works before there is a session:

```kotlin
data class DiscoveredServer(val id: String, val name: String, val address: String)

/** [detail] is what the message shows: a version, a product name, a response time. */
data class ServerIssue(val kind: Kind, val detail: String? = null) {
    enum class Kind { OUTDATED_VERSION, UNSUPPORTED_VERSION, NOT_THIS_SERVER_TYPE, SLOW_RESPONSE, NO_ID, UNKNOWN }
}

/** No usable server at the address. [issues] is empty when nothing answered. */
class ServerConnectException(val issues: List<ServerIssue>) : Exception()

data class ResolvedServer(val info: ServerInfo, val address: String)
data class AuthenticatedUser(val id: UserId, val name: String, val serverId: ServerId,
    val accessToken: String, val primaryImageTag: String?)
data class PublicUser(val id: UserId, val name: String, val primaryImageTag: String?)
data class QuickConnectState(val code: String, val secret: String, val authenticated: Boolean)

interface ServerConnector {
    val capabilities: ServerCapabilities
    fun discover(): kotlinx.coroutines.flow.Flow<DiscoveredServer>
    /** The best address among the candidates for [address], or [ServerConnectException]. */
    suspend fun resolve(address: String): ResolvedServer
    // Every call after resolve names the address it is for; a connector holds no "current server".
    suspend fun loginDisclaimer(address: String): String?
    suspend fun publicUsers(address: String): List<PublicUser>
    suspend fun login(address: String, username: String, password: String): AuthenticatedUser
    suspend fun quickConnectEnabled(address: String): Boolean
    suspend fun startQuickConnect(address: String): QuickConnectState
    suspend fun quickConnectState(address: String, secret: String): QuickConnectState
    suspend fun loginWithQuickConnect(address: String, secret: String): AuthenticatedUser
    suspend fun endSession(baseUrl: String, accessToken: String): Boolean
}
```

With more than one server type installed, each adapter contributes its `MediaServer` and
`ServerConnector` to a map keyed by `ServerType`. `CurrentMediaServer` delegates to the adapter of
the server in use, and `ServerConnectors.resolve(address)` tries each connector until one
recognizes the server.

More interfaces are in `domain` so that the adapter, which may not depend on `data` or `core`,
can implement them. `RequestWatcher` moves there from `data/api`, into `domain.ports`. (This plan
put `MediaHttpClient`, the player's OkHttp client, in `domain` too, as the one
library type there. #964 moved it into a module of its own, `http`, which the adapter and the
player both use, and rule R19 keeps libraries out of `domain` and `application`.)

```kotlin
/** The device report the About screen copies: what the adapter would tell the server about this device. */
fun interface DeviceReportSource {
    suspend fun buildJson(appVersion: String, buildType: String, transcodeDolbyVision: Boolean, directPlayDolbyVision: Boolean): String
}

/**
 * What this device can decode. The Android probing (`MediaCodecList`, the display) stays in `data`;
 * the adapter builds its profile from this. `ProbedCodecs` and `DolbyVisionLimit` are plain data
 * classes and move to `domain` with this port, in their package `dev.jdtech.jellyfin.player`.
 */
interface DeviceCodecs {
    fun probedCodecsSnapshot(): ProbedCodecs
    fun displaySupportsDolbyVision(): Boolean
}

/**
 * Puts the saved server, user and token on a new session before anything uses it. Implemented in
 * `core`, which knows Room. Not suspending on purpose: it is called from the Hilt provider of the
 * session, which cannot suspend, and returns only when the session is set. As today (#250) it reads
 * the copy in preferences, which is in memory, and blocks on Room only when that copy is missing.
 */
fun interface SessionRestorer { fun restore(session: ServerSession) }
```

## 3.4 The repository (`data`)

ViewModels keep calling one interface. It is today's `MediaRepository` without SDK types; it
keeps its name until the Emby work starts (decision D2, #774). Its one implementation composes a `MediaServer` with Room:

```kotlin
class MediaRepositoryImpl(          // no SDK import
    private val context: Context,          // for the trickplay files of downloads
    private val server: MediaServer,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val syncRequester: UserDataSyncRequester,
) : MediaRepository {
    override suspend fun getMovie(itemId: ItemId): Movie =
        serverOrStored(stored = storedMovie(itemId)) {
            server.catalog.movie(itemId).withLocalSources(localSourcesFor(itemId))   // remote sources first, then local
        }
    // …
}
```

A ViewModel may also inject a port directly when there is nothing to compose, for example
`ServerSession` for the base URL. It may not inject an adapter class.

## 3.5 Player engines (`player/local`)

media3's `Player` stays the control surface. Both engines implement it already, and
`MediaSession` needs it. The port is the factory around it and the few things `Player` has no
call for:

```kotlin
package dev.jdtech.jellyfin.player.local.engine

// PlayerEngineId is in domain (3.2).

data class PlayerEngineConfig(
    val httpClient: okhttp3.OkHttpClient,
    val trackSelectionParameters: androidx.media3.common.TrackSelectionParameters,
    val seekBackMs: Long,
    val seekForwardMs: Long,
    val isTv: Boolean,
)

interface EnginePlayer {
    val player: androidx.media3.common.Player
    fun setSubtitleDelayMs(delayMs: Long)
    /** Re-applies settings the engine drops when an item loads. */
    fun onItemReady() {}
    fun setZoomMode(enabled: Boolean) {}
    /**
     * True when preparing the player again can recover from the error (ExoPlayer has ruled out the
     * audio decoder that failed). The caller then calls `player.prepare()` and stops handling the
     * error; the engine does not retry itself. False for everything else.
     */
    fun onPlayerError(error: androidx.media3.common.PlaybackException): Boolean = false
    fun release()
}

interface PlayerEngine {
    val id: PlayerEngineId
    fun create(config: PlayerEngineConfig): EnginePlayer
}
```

Packages: `engine/` (the interfaces), `engine/exo/`, `engine/mpv/`. The shared player code
imports `engine` only. `PlayerViewModel` gets a `Map<PlayerEngineId, PlayerEngine>` from Hilt and
has no `when (playerBackend)` and no `is MPVPlayer`.

## 3.6 Use cases

Four, where there is logic that is neither a call nor a screen. Each is a plain class with its
own unit tests; none is an interface.

| Use case | From | What it decides |
|---|---|---|
| `ResolvePlayback` | `PlaylistManager.toPlayerItem`, the profile choice in `MediaRepositoryImpl.getPlaybackSources` | Which source plays (a finished download first), for which target, with which cap and audio stream |
| `PlaybackReporter` | `PlayerViewModel.sendPlaybackStart`, `reportProgress`, `reportStopped`, with `PlaybackStartGate`, `ReportedPause`, `PlaybackStopReport` | When start, progress and stop are sent, in what order, with which session |
| `PlayQueue` | `PlaylistManager` (queue, shuffle, next season, next up) | What plays next |
| `DownloadQueue` | Exists (`core/.../downloader/DownloadQueue.kt`) | Scheduling, retry, restore. Unchanged |

Everything else is a ViewModel calling the repository or a port.

## 3.7 Cast

`CastSessionManager` and `CastPlayerController` stay as they are. `CastPlayerControllerImpl`
asks `PlaybackPort.sources(PlaybackRequest(target = CastReceiver(supportsHevc)))` instead of the
SDK, builds its tracks from `ItemSource.mediaStreams` instead of `MediaSourceInfo`, and takes
stream URLs from the source. The two Chromecast device profiles move into the Jellyfin adapter,
which is where `PlaybackTarget.CastReceiver` is turned into a profile.
