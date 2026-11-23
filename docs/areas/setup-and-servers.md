# Setup, servers and users

Status: built, on phone and TV; Jellyfin is the only server type with an adapter. As of 2026-11-23 (`main` at `71c25144b`).

## What it does

Setup finds a server (discovery on the local network, or an address typed in), checks it, saves it and signs a user in: with a password, a public user's tile or Quick Connect. Later the user adds and removes servers, addresses and users, and switches between them. The server in use is one `ActiveServer` value (type, address, token, user) on `ActiveServerHolder`: a switch replaces it whole, so a request never sees one server's adapter with another's address or token ([#899](https://github.com/midasvo/ephyra/issues/899)). `CurrentMediaServer` picks the adapter by the server's type. At start, `SavedSessionRestorer` puts the last session back from a copy in preferences, without waiting for Room. While the app runs, `ServerSessionKeeper` keeps the server's session open and signs out a revoked token, and `ServerReachability` follows whether the server answers (phone only). Profiles and the PIN lock are their own area (`profiles`: `setup/.../presentation/pin/`, `docs/profiles/`).

## Where the code is

| Path | What |
|---|---|
| [`setup/.../setup/data/SetupRepositoryImpl.kt`](../../setup/src/main/java/dev/jdtech/jellyfin/setup/data/SetupRepositoryImpl.kt) | Servers, addresses and users in Room; talks to a server through its connector; one of the two places that call `switchTo` |
| [`setup/.../setup/domain/SetupRepository.kt`](../../setup/src/main/java/dev/jdtech/jellyfin/setup/domain/SetupRepository.kt) | Its interface; also `AddressRejected`, `LocalNetworkAccess`, `ServerLocalNetworkBlock` there |
| [`setup/.../setup/presentation/`](../../setup/src/main/java/dev/jdtech/jellyfin/setup/presentation/) | ViewModels and State/Action: `addserver`, `addresses`, `login`, `servers`, `users`, `welcome` (actions only), `localnetwork`, `permission` |
| [`domain/.../domain/ports/ServerConnector.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/ServerConnector.kt) | The port before there is a session: discover, resolve, find an address, public users, login, Quick Connect, end a session |
| [`server/jellyfin/.../server/jellyfin/JellyfinServerConnector.kt`](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/JellyfinServerConnector.kt) | The Jellyfin implementation; bound in [`JellyfinServerModule.kt`](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/di/JellyfinServerModule.kt) |
| [`domain/.../domain/ports/ActiveServer.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/ActiveServer.kt) | `ActiveServer` and `ActiveServerHolder` (`switchTo`, `onSameServer`) |
| [`core/.../core/server/`](../../core/src/main/java/dev/jdtech/jellyfin/core/server/) | `CurrentMediaServer`, `ServerConnectors` (by `ServerType`), `UnsupportedMediaServer`, `StoredType.kt` |
| [`core/.../di/ApiSession.kt`](../../core/src/main/java/dev/jdtech/jellyfin/di/ApiSession.kt) | `SavedSessionRestorer`: the session at start, and its copy in preferences; the other `switchTo` caller |
| [`core/.../core/session/ServerSessionKeeper.kt`](../../core/src/main/java/dev/jdtech/jellyfin/core/session/ServerSessionKeeper.kt) | The WebSocket that keeps the server's session; sign-out after a revoked token ([#334](https://github.com/midasvo/ephyra/issues/334)) |
| [`core/.../core/reachability/`](../../core/src/main/java/dev/jdtech/jellyfin/core/reachability/) | `ServerReachability`, `ServerReachabilityMonitor`, `ServerReachabilityTracker` (the pure rules) |
| [`application/.../repository/ServerStore.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/ServerStore.kt) | Store port of servers, addresses and users; Room in [`data/.../database/RoomServerStore.kt`](../../data/src/main/java/dev/jdtech/jellyfin/database/RoomServerStore.kt) |
| [`core/.../viewmodels/MainViewModel.kt`](../../core/src/main/java/dev/jdtech/jellyfin/viewmodels/MainViewModel.kt) | Whether there are servers and a user at start, the unreachable banner |
| [`app/phone/.../presentation/setup/`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/setup/) | Phone screens: welcome, add server, addresses, login, servers, users, permission |
| [`app/tv/.../presentation/setup/`](../../app/tv/src/main/java/dev/jdtech/jellyfin/presentation/setup/) | TV screens (no addresses screen) |
| [`testing/.../testing/FakeServerConnector.kt`](../../testing/src/main/kotlin/dev/jdtech/jellyfin/testing/FakeServerConnector.kt), [`ServerConnectorContract.kt`](../../testing/src/main/kotlin/dev/jdtech/jellyfin/testing/ServerConnectorContract.kt) | The fake and the contract suite every connector passes |

## Rules

- **R16**: the server in use is written as one `ActiveServer`. Only `ActiveServer.kt` keeps a mutable one; only `ApiSession.kt` and `SetupRepositoryImpl.kt` call `.switchTo(`; no `copy(type = …)` on an `ActiveServer` outside the holder; nothing calls the SDK client's `update(…)`.
- **R1, R2, R3**: the SDK is only in `server/jellyfin`. Setup talks to a server through `ServerConnector`; failures are `ServerException`.
- **R4, R9**: Room through `ServerStore`; `setup` and `core` do not depend on `data` (their tests may).
- **R10**: `ServerId` and `UserId`, never `String` or `UUID`. Address ids are random `UUID`s the app makes: `SetupRepository.kt`, `SetupRepositoryImpl.kt`, `ServerStore.kt` and the addresses and servers ViewModels are on the allowlists in [`architecture/baseline/`](../../architecture/baseline/). Do not add a line to get past the rule.
- **R11**: no `Jellyfin*` type outside the adapter.
- What a server can do is `ServerCapabilities` (Quick Connect, public users, the login disclaimer), never a version check.
- Project rules (`CLAUDE.md`): a setup flow PR that no bot reviewed gets an independent review posted on the PR before it merges. CE strings in 36 languages; upstream's setup texts are not edited, a `ce_` key replaces them.

## How to test it

Unit tests:

```bash
./gradlew :domain:test :application:test :architecture:test   # ServerTypeTest, ServerFailuresTest, R16
./gradlew :setup:testDebugUnitTest                             # SetupRepositoryConnectorTest, AddSavedServerSessionTest, Login*, AddServer*, ServerAddressesFeedbackTest
./gradlew :core:testLibreDebugUnitTest                         # CurrentMediaServer*Test, ServerConnectorsTest, ServerSessionKeeperTest, ServerReachability*Test, SavedSessionRestorerTest
./gradlew :data:testDebugUnitTest --tests '*RoomServerStoreTest*'
./gradlew :server:jellyfin:testDebugUnitTest                   # JellyfinServerConnector*Test, SwitchDuringRequestTest, SocketAcrossASwitchTest, contract tests
./gradlew :testing:test                                        # the fake against the contract suites
./gradlew :app:tv:testLibreDebugUnitTest                       # LoginKeyboardTest, LoginServerButtonFocusTest, UsersFocusRestoreTest
```

The connector contract tests replay recordings in `server/jellyfin/src/test/resources/contract/`. With `EPHYRA_TEST_SERVER=http://localhost:8096` they run against the test server instead, as `EPHYRA_TEST_USER` (default `contract`, which `setup.sh` does not create) and `EPHYRA_TEST_PASSWORD`; `-Dcontract.record=true` records again. Not in CI.

On a device: the test server (`docs/dev-environment.md`, `tools/test-server/setup.sh`, `test-server` skill). From an emulator the address is `http://10.0.2.2:8096`.

- Users: `tester` (normal), `nodownload` (may not download), `second` (a second user on the same server, for user switching), `nopass` (empty password). Never log in to the app as `admin`. Add your own with `python3 tools/test-server/user.py add <name>` (`--no-password`) when someone else tests at the same time.
- A second address of the same server: `python3 tools/test-server/forward.py` listens on 8097 and passes to 8096; add `http://10.0.2.2:8097` on the Addresses screen. Stopping the script takes that address away.
- A second server: `setup.sh` with other `EPHYRA_TEST_SERVER_DIR`, `EPHYRA_TEST_SERVER_PORT` and `EPHYRA_TEST_SERVER_NAME` runs one next to the first.
- Server gone, internet still there (phone or tablet AVD): `adb root`, then `adb shell iptables -I OUTPUT -d 10.0.2.2 -p tcp --dport 8096 -j REJECT --reject-with tcp-reset`; `-D` removes it. On TV use `forward.py` instead.
- A fresh login: `ce_phone_tmp` is the AVD for throwaway logins. On TV, type into text fields with `scripts/tv-dpad.sh`, not `adb shell input keyevent`.
- The local network permission (`LocalNetworkAccess`, [#376](https://github.com/midasvo/ephyra/issues/376)) applies from Android 17 only; the standard AVDs are Android 14.
- A script that logs in to the server needs its own `DeviceId`: a second login with the same one ends the first session.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :domain:test :application:test :architecture:test
./gradlew :setup:testDebugUnitTest :core:testLibreDebugUnitTest :server:jellyfin:testDebugUnitTest
./gradlew :app:phone:compileLibreDebugKotlin :app:tv:compileLibreDebugKotlin
python3 .claude/skills/translate-strings/scripts/check-translations.py   # when strings changed
```

Then walk the flow on an emulator: add the server, log in, switch user, switch server, remove an address. If no review bot reviewed the PR, get the independent review first.

## Decisions and history

- ADR [0001](../architecture/adr/0001-ports-and-adapters.md) and [0002](../architecture/adr/0002-hexagonal-ddd.md); T7 in [`docs/architecture/5-hexagonal-ddd.md`](../architecture/5-hexagonal-ddd.md).
- [#761](https://github.com/midasvo/ephyra/issues/761): discovery, login and Quick Connect move behind `ServerConnector`.
- [#763](https://github.com/midasvo/ephyra/issues/763): session keeper and reachability go through `ServerSession`.
- [#770](https://github.com/midasvo/ephyra/issues/770): servers have a type; the `MediaServer` and connector are chosen by it.
- [#819](https://github.com/midasvo/ephyra/issues/819): adding a server does not count as traffic of the server in use.
- [#899](https://github.com/midasvo/ephyra/issues/899): `ServerStore` ([#913](https://github.com/midasvo/ephyra/pull/913)) and the switch as one `ActiveServer` ([#915](https://github.com/midasvo/ephyra/pull/915)), rule R16.
- [#281](https://github.com/midasvo/ephyra/issues/281): `ServerReachability` and the unreachable banner.
- [#334](https://github.com/midasvo/ephyra/issues/334): sessions closed with the app; a revoked token signs out.
- [#250](https://github.com/midasvo/ephyra/issues/250): the session at start from a copy in preferences, not Room.
- [#425](https://github.com/midasvo/ephyra/issues/425): adding a saved server again keeps its user signed in.
- [#376](https://github.com/midasvo/ephyra/issues/376), [#571](https://github.com/midasvo/ephyra/issues/571): say when Android 17's local network permission blocks a server.
- [#606](https://github.com/midasvo/ephyra/issues/606): Quick Connect failures in plain words.
- [#918](https://github.com/midasvo/ephyra/issues/918) (open): `data` and the adapter stop reading `AppPreferences`.

## Pitfalls

- **The current server id is a preference, not only `ActiveServer`.** `AddServerViewModel` and `ServersViewModel` write `AppPreferences.setCurrentServerId`; `SetupRepositoryImpl` switches the holder. `MediaRepositoryImpl`, `CurrentServerId` and several ViewModels read the preference. A change to the switch must keep both in step ([#918](https://github.com/midasvo/ephyra/issues/918), [#926](https://github.com/midasvo/ephyra/pull/926)).
- **The login screens use the client's address, not Room's.** Re-adding a saved server by a new address points `ActiveServer` there and leaves Room's current address as it was (`clientAddress()` in `SetupRepositoryImpl`).
- **Checking another server's address must run unwatched**, or its silence shows the unreachable banner for the server in use ([#819](https://github.com/midasvo/ephyra/issues/819), `addAddress`).
- **Deleting the active user is allowed**: the guard in `SetupRepositoryImpl.deleteUser` is commented out (setup-02 in [`docs/open-decisions.md`](../open-decisions.md)). It now clears the current user and the token.
- **A server type without an adapter** gets `UnsupportedMediaServer`: every request fails; its downloads open from Room.
- **Reachability does not run on TV** (`serverReachability.start()` is only in `app/phone`'s `BaseApplication`).
- **Profile switch race**: an unplayed mark during a profile switch may clear the other user's last-played date ([#907](https://github.com/midasvo/ephyra/issues/907), open).
- **Downgrade**: an older build over a newer Room schema can drop the database and the login (`docs/dev-environment.md`).
