# 3. Target

The module layout at the end of the migration and the ports as Kotlin signatures. The signatures
are the design; a task issue may adjust a name, not the direction of a dependency.

## 3.1 Modules

```
domain             NEW   Pure Kotlin. The models (Findroid*), ids, enums, the ports, and the
                         rules with no I/O (stopUserData, ResumeThresholds, PlaySessions).
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

- `domain` depends on nothing in this repository.
- `server/jellyfin` depends on `domain` and `settings` (for the timeouts in `Constants`), not on
  `data`: an adapter does not know Room.
- `data` depends on `domain` and `settings`, not on a server module.
- No feature module depends on a server module.

**Moved files keep their package.** `dev.jdtech.jellyfin.models.FindroidMovie` stays
`dev.jdtech.jellyfin.models.FindroidMovie` when its file moves from `data` to `domain`. A move
then changes no import anywhere, git records it as a pure rename, and an upstream change to the
file still merges. Packages are only named anew for new code
(`dev.jdtech.jellyfin.server.jellyfin`, `dev.jdtech.jellyfin.domain.ports`).

## 3.2 Shared types (`domain`)

```kotlin
package dev.jdtech.jellyfin.models

// Aliases today, so signatures say what an id is. See decision D1 for making them opaque.
typealias ItemId = java.util.UUID
typealias UserId = java.util.UUID
typealias ServerId = String
typealias MediaSourceId = String

enum class ServerType { JELLYFIN, EMBY }

/** The kinds of item the app handles. [serialName] is what a route or an intent extra carries. */
enum class ItemKind(val serialName: String) {
    MOVIE("Movie"), SERIES("Series"), SEASON("Season"), EPISODE("Episode"), BOX_SET("BoxSet"),
    FOLDER("Folder"), PHOTO_ALBUM("PhotoAlbum"), VIDEO("Video"), MUSIC_VIDEO("MusicVideo"),
    COLLECTION_FOLDER("CollectionFolder"), UNKNOWN("Unknown");

    companion object { fun fromName(name: String): ItemKind /* by serialName or enum name */ }
}

// Constant names match the SDK's: Room stores this one by name in mediastreams.type.
enum class MediaStreamType { AUDIO, VIDEO, SUBTITLE, EMBEDDED_IMAGE, DATA, LYRIC }
enum class SubtitleDelivery { ENCODE, EMBED, EXTERNAL, HLS, DROP }
enum class VideoRangeType { UNKNOWN, SDR, HDR10, HLG, DOVI, DOVI_WITH_HDR10, DOVI_WITH_HLG,
    DOVI_WITH_SDR, DOVI_WITH_EL, DOVI_WITH_HDR10_PLUS, DOVI_WITH_ELHDR10_PLUS, DOVI_INVALID,
    HDR10_PLUS }
enum class PersonKind { ACTOR, DIRECTOR, WRITER, PRODUCER, GUEST_STAR, COMPOSER, OTHER }
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

`FindroidImages` and `FindroidItemPerson` carry image URLs as `String` instead of
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
    suspend fun libraries(): List<FindroidCollection>
    suspend fun item(id: ItemId): FindroidItem?
    suspend fun movie(id: ItemId): FindroidMovie
    suspend fun show(id: ItemId): FindroidShow
    suspend fun season(id: ItemId): FindroidSeason
    suspend fun episode(id: ItemId): FindroidEpisode
    suspend fun items(query: ItemQuery, includeTotal: Boolean = false): ItemsPage
    suspend fun itemCount(query: ItemQuery, nameLessThan: String? = null): Int
    suspend fun randomItemsWithBackdrop(kinds: List<ItemKind>, limit: Int): List<FindroidItem>
    suspend fun person(id: ItemId): FindroidPerson
    suspend fun personItems(personIds: List<ItemId>, kinds: List<ItemKind>?, limit: Int?): List<FindroidItem>
    suspend fun favorites(): List<FindroidItem>
    suspend fun search(query: String, kinds: List<ItemKind>? = null): List<FindroidItem>
    suspend fun suggestions(): List<FindroidItem>
    suspend fun resumeItems(): List<FindroidItem>
    suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<FindroidItem>
    suspend fun seasons(seriesId: ItemId): List<FindroidSeason>
    suspend fun nextUp(seriesId: ItemId? = null): List<FindroidEpisode>
    suspend fun episodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        include: Set<EpisodeField> = emptySet(),
        startItemId: ItemId? = null,
        limit: Int? = null,
    ): List<FindroidEpisode>
    // Empty or null when the capability is off.
    suspend fun similar(itemId: ItemId, limit: Int): List<FindroidItem>
    suspend fun extras(itemId: ItemId, localTrailers: Boolean, specialFeatures: Boolean): List<FindroidMovie>
    suspend fun collectionsOf(itemId: ItemId): List<FindroidItem>
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
    /** For the sync: the server's rows for [itemIds], and writing ours back. */
    suspend fun userDataOf(itemIds: List<ItemId>): List<ServerUserData>
    suspend fun putUserData(data: ServerUserData)
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
)

data class PlaybackReport(
    val itemId: ItemId,
    val positionTicks: Long,
    val isPaused: Boolean,
    val transcoded: Boolean,
    val playSessionId: String?,
)

interface PlaybackPort {
    /** Sources with finished URLs. `FindroidSource.transcoded` and `.playSessionId` say what the server chose. */
    suspend fun sources(request: PlaybackRequest): List<FindroidSource>
    suspend fun segments(itemId: ItemId): List<FindroidSegment>
    suspend fun trickplayTile(itemId: ItemId, width: Int, index: Int): ByteArray?
    fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String
    suspend fun reportStart(report: PlaybackReport)
    suspend fun reportProgress(report: PlaybackReport)
    suspend fun reportStopped(report: PlaybackReport)
    suspend fun announceCapabilities()
    /** Null when the server has none; the defaults apply. */
    suspend fun resumeThresholds(): ResumeThresholds?
    /** For the About and device profile screens: what the adapter would tell the server about this device. */
    fun describeTarget(target: PlaybackTarget): String
}

interface ServerSession {
    val baseUrl: String?
    val userId: UserId?
    /** Tokens the server refused while they were current. */
    val unauthorized: kotlinx.coroutines.flow.Flow<String>
    /** Emits when the server answers again after requests that could not reach it. */
    val serverReturned: kotlinx.coroutines.flow.Flow<Unit>
    fun use(baseUrl: String?, accessToken: String?, userId: UserId?)
    suspend fun probe(): Boolean?
    suspend fun serverInfo(): ServerInfo
    suspend fun isTokenValid(): Boolean
    suspend fun autoPlayNextEpisode(): Boolean?
    suspend fun updateDeviceName(name: String)
    /** Keeps the server's session socket open while collected. Completes at once when unsupported. */
    fun keepAlive(): kotlinx.coroutines.flow.Flow<Nothing>
    /** A session for another saved user, for the sync. */
    fun forUser(baseUrl: String, accessToken: String, userId: UserId): UserDataPort
}
```

The connector is separate because it works before there is a session:

```kotlin
data class DiscoveredServer(val id: String, val name: String, val address: String)

enum class ServerIssue { OUTDATED_VERSION, UNSUPPORTED_VERSION, NOT_THIS_SERVER_TYPE, SLOW_RESPONSE, NOT_FOUND }
class ServerConnectException(val issues: List<ServerIssue>) : Exception()

data class ResolvedServer(val info: ServerInfo, val address: String, val type: ServerType)
data class AuthenticatedUser(val id: UserId, val name: String, val serverId: ServerId,
    val accessToken: String, val primaryImageTag: String?)
data class PublicUser(val id: UserId, val name: String, val primaryImageTag: String?)
data class QuickConnectState(val code: String, val secret: String, val authenticated: Boolean)

interface ServerConnector {
    val type: ServerType
    fun discover(): kotlinx.coroutines.flow.Flow<DiscoveredServer>
    /** The best address among the candidates for [address], or [ServerConnectException]. */
    suspend fun resolve(address: String): ResolvedServer
    suspend fun loginDisclaimer(): String?
    suspend fun publicUsers(): List<PublicUser>
    suspend fun login(username: String, password: String): AuthenticatedUser
    suspend fun quickConnectEnabled(): Boolean
    suspend fun startQuickConnect(): QuickConnectState
    suspend fun quickConnectState(secret: String): QuickConnectState
    suspend fun loginWithQuickConnect(secret: String): AuthenticatedUser
    suspend fun endSession(baseUrl: String, accessToken: String): Boolean
}
```

Two things stay out of `domain` because they are not pure Kotlin. They are small interfaces in
`data`, implemented by the adapter:

```kotlin
/** The OkHttp client that media is streamed through, so the player shares the API's connections. */
fun interface MediaHttpClient { fun client(): okhttp3.OkHttpClient }

/** Told how each request of the app goes; the reachability banner follows it. Exists today in data/api. */
interface RequestWatcher { /* unchanged */ }
```

## 3.4 The repository (`data`)

ViewModels keep calling one interface. It is today's `JellyfinRepository` without SDK types; it
keeps its name until decision D2. Its one implementation composes a `MediaServer` with Room:

```kotlin
class JellyfinRepositoryImpl(          // no SDK import
    private val server: MediaServer,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val syncRequester: UserDataSyncRequester,
) : JellyfinRepository {
    override suspend fun getMovie(itemId: ItemId): FindroidMovie =
        serverOrStored(stored = storedMovie(itemId)) {
            server.catalog.movie(itemId).withLocalSources(localSourcesFor(itemId))
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

enum class PlayerEngineId(val preferenceValue: String) { EXOPLAYER("exoplayer"), MPV("mpv") }

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
| `ResolvePlayback` | `PlaylistManager.toPlayerItem`, the profile choice in `JellyfinRepositoryImpl.getPlaybackSources` | Which source plays (a finished download first), for which target, with which cap and audio stream |
| `PlaybackReporter` | `PlayerViewModel.sendPlaybackStart`, `reportProgress`, `reportStopped`, with `PlaybackStartGate`, `ReportedPause`, `PlaybackStopReport` | When start, progress and stop are sent, in what order, with which session |
| `PlayQueue` | `PlaylistManager` (queue, shuffle, next season, next up) | What plays next |
| `DownloadQueue` | Exists (`core/.../downloader/DownloadQueue.kt`) | Scheduling, retry, restore. Unchanged |

Everything else is a ViewModel calling the repository or a port.

## 3.7 Cast

`CastSessionManager` and `CastPlayerController` stay as they are. `CastPlayerControllerImpl`
asks `PlaybackPort.sources(PlaybackRequest(target = CastReceiver(supportsHevc)))` instead of the
SDK, builds its tracks from `FindroidSource.mediaStreams` instead of `MediaSourceInfo`, and takes
stream URLs from the source. The two Chromecast device profiles move into the Jellyfin adapter,
which is where `PlaybackTarget.CastReceiver` is turned into a profile.
