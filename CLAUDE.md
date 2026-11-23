# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The app is **Ephyra**, a fork of [Findroid](https://github.com/jarnedemeulemeester/findroid). It was called Findroid CE until #172. The repo is `midasvo/ephyra` (renamed from `midasvo/findroid-ce` in #174; GitHub redirects the old URL). The application ID, the Kotlin packages and internal names (`FindroidTheme`, `Theme.Findroid`) keep the old names on purpose. The repository and the models do not: since #774 they are `MediaRepository`, `Item`, `Movie`, `ItemSource`, `Library`, … (upstream calls them `JellyfinRepository` and `Findroid*`), and the architecture rule R11 allows no other `Findroid*` type.

## Build Commands

```bash
# Debug APK (most common during development)
./gradlew :app:phone:assembleLibreDebug

# Release APK
./gradlew :app:phone:assembleLibreRelease

# TV debug APK
./gradlew :app:tv:assembleLibreDebug

# Compile only (faster feedback, no APK)
./gradlew :app:phone:compileLibreDebugKotlin
./gradlew :app:tv:compileLibreDebugKotlin
./gradlew :modes:film:compileDebugSources

# Full build (all modules)
./gradlew build

# Clean
./gradlew clean
```

```bash
# Formatting check (runs in CI)
./gradlew ktfmtCheck

# Format the tree
./gradlew ktfmtFormat

# Unit tests of every module and flavour (CI runs them too: run them yourself before opening a PR to save a round trip)
./gradlew testDebugUnitTest
```

CI on a pull request runs `ktfmtCheck` (when Kotlin files or `gradle/libs.versions.toml` change) and, unless the change touches only docs-like paths (see `build.yaml`), the CE translation check, a debug build of every app variant and the unit tests (`testDebugUnitTest`, which uploads the test reports when it fails). Pushes to `main` run the same jobs; the Beta workflow does not wait for them. The build needs JDK 21.

## Module Structure

```
app/phone        — Phone UI: screens, navigation, Compose layouts
app/tv           — TV UI: separate screen implementations for Android TV
core             — Shared DI modules, DownloaderViewModel, MainViewModel, common Composables
domain           — pure Kotlin: the app's own models and the ports. No Android, no SDK
application      — pure Kotlin: MediaRepository and its event flows (DataChangeEvents, ReloadPolicy, …), which features use instead of data
data             — Room database + the repository over a server's ports (MediaRepositoryImpl). No SDK. Only the apps depend on it (rule R9, baseline core, modes/film, setup)
server/jellyfin  — the Jellyfin adapter: the only module with the Jellyfin SDK (JellyfinApi, mappers, ports, device profiles) and its own Hilt module. Only app/phone and app/tv depend on it
modes/film       — Feature ViewModels and State/Action classes for home, library, search, episode, movie, show, season
player/core      — Player models shared by the player and its callers (PlayerItem, Track, Trickplay)
player/local     — ExoPlayer + mpv PlayerViewModel and playback logic
player/cast      — Google Cast: session and playback on a receiver (real in the proprietary flavor, stubs in libre)
setup            — Server discovery and login onboarding flow
settings         — AppPreferences (DataStore) and settings screen
baselineprofile  — Macrobenchmark module for app/phone: baseline profile and startup benchmark
architecture     — Konsist rules: only server/jellyfin touches the Jellyfin SDK (no exceptions)
```

The `app/phone` module depends on all other modules. ViewModels live in `modes/film`, `setup`, `settings`, and `player/local` — NOT in `app/phone` or `app/tv`. Screens in the app modules consume state from those ViewModels.

**TV:** new TV screens and components go in `app/tv/src/main/java/dev/jdtech/jellyfin/ce/`, next to upstream's screens instead of rewriting them, and `NavigationRoot.kt` points each route at the CE one (ADR 0001). The TV decisions are in `docs/tv-research/adr/`.

## Architecture

**MVI-style MVVM with Unidirectional Data Flow:**

Each screen follows this pattern:
- `FooState` — immutable data class holding all UI state
- `FooAction` — sealed interface of user intents
- `FooViewModel` — `@HiltViewModel`, exposes `StateFlow<FooState>`, handles `onAction()`
- `FooScreen` — stateful Composable that wires ViewModel to layout
- `FooScreenLayout` — stateless Composable that takes state + callbacks (used for previews)

Events that fire once (navigation, toasts) use a `Channel<FooEvent>` exposed as `Flow`, observed via `ObserveAsEvents()`.

**Dependency Injection:** Hilt throughout. DI modules are in `core/src/main/java/dev/jdtech/jellyfin/di/`, except the Jellyfin adapter's `JellyfinServerModule` in `server/jellyfin`.

**Navigation:** Navigation 3 with `@Serializable` route objects. `NavigationRoot.kt` in `app/phone` is the single source of truth for the nav graph.

**Downloads without the server:** there is no offline mode (#281). `RepositoryModule` always provides `MediaRepositoryImpl`. Its item getters (`getMovie`, `getEpisode`, `getShow`, `getSeason`, `getItem`) return the Room copy of a downloaded item when the server fails or takes over 3 s (`StoredFallback.kt`), `getPlaybackSources` picks a finished download before asking the server, and user data written while the server is away is marked `toBeSynced` and sent by `UserDataSync`/`SyncWorker`. The Downloads screens read Room only. Screens that need the server show the unreachable banner (see below).

**Server reachability:** `ServerReachability` (in `core`) follows the app's own requests and probes the server on a network change, on return to the app and after a failed request. Phone screens read it through `LocalServerStatus` and show the unreachable banner (`ServerOrErrorCard`) with a way to Downloads. It is not started on TV.

## Key Patterns

**Identity:** the visual identity (palette, type, shape, motion, glow rules) is specified in `docs/identity/VISION.md`. Quote its principles in reviews; don't invent colours outside its token tables.

**Downloading:** `Downloader` interface (in `core`) is injected into ViewModels that need it. `DownloaderViewModel` (also in `core`) collects the entries of `DownloadQueue` and exposes `DownloaderState`. Storage index is read from `AppPreferences.downloadStorageIndex`, so downloads don't ask for a storage location. Each download does ask for a quality, through `DownloadQualityDialog`.

**ItemButtonsBar:** The shared download/play/favorite buttons component. Accepts `canDownload` and `isDownloaded` overrides so aggregate items (seasons, shows) can pass computed values instead of deriving from the item directly.

**Build variants:** Build types are `debug`, `release`, `staging`. The default flavor is `libre`: use its variants (e.g. `assembleLibreDebug`, `compileLibreDebugKotlin`) unless the change is about Cast. `libre` must stay free of Google code. Both phone flavors share one application ID and signing key (one installs over the other); the TV app has only `libre`. Channels: Google Play phone `proprietary`, Google Play TV `libre`, Amazon Appstore `libre` (Fire OS has no Play Services), F-Droid `libre`, GitHub releases and betas both (`ephyra-v<version>-libre-<abi>.apk`, `-proprietary-<abi>.apk`, `-libre-tv-<abi>.apk`).

**Cast (#626):** `app/phone` and `player/cast` have a second flavor, `proprietary`, with Google Cast (Play Services). Everything that touches the Cast SDK is in `player/cast/src/proprietary`; `player/cast/src/libre` has stubs that say Cast is not supported, and `player/cast/src/main` (interfaces, models, ViewModels) and the Cast UI in `app/phone/.../presentation/cast` compile in both. A change there must compile in both flavors (`compileLibreDebugKotlin` and `compileProprietaryDebugKotlin`). Releases and betas attach the `proprietary` APKs, and the Google Play phone bundle is `proprietary` (#641). The Cast surfaces (top bar button, device sheet, mini player, player) are stateless layouts fed by one `CastViewModel`; `CastHost` wraps the navigation display and is the only Cast code `NavigationRoot` knows. Emulators find no receivers: a debug build of the `proprietary` flavor has a stand-in (`adb shell setprop debug.ephyra.cast fake`, see `player/cast/src/debug/.../FakeCast.kt`) to look at every state. Anything about a real receiver still needs a real one (memory note `cast-device-test`).

**App ID:** `nl.midasvo.findroid.ce` (this is a fork — do not revert to the upstream `dev.jdtech.jellyfin` ID, and do not change it to match the Ephyra name: existing installs update over it).

**Version:** `EphyraVersion` in `buildSrc/src/main/kotlin/EphyraVersion.kt`: `NAME` (semver, from 2.0.0) with the literals `CODE` and `TV_CODE`, which must match it. Only a release PR changes them. Upstream's `Versions.APP_NAME` / `APP_CODE` are unused. Releases (`vX.Y.Z`) go through the `release` skill; merging Findroid's changes is the separate `upstream-sync` skill and never releases anything.

**App name:** `app_name` in `core/src/{main,debug,staging}/res/values/strings.xml` (`Ephyra`, `Ephyra Debug`, `Ephyra Beta`). The Jellyfin client name follows the app label. Where an upstream string names the app (the welcome screen, the local network rationale), CE uses its own `ce_` key instead of editing upstream's translations. The launcher icons and the TV banner are generated by `images/branding/ephyra/make-ephyra.sh`.

**Player PRs:** anything under `player/local` or the player screens runs `docs/mpv-checklist.md` on both backends before merge. It includes the display refresh-rate vote check: ExoPlayer must keep its default frame-rate strategy and mpv must hint the surface on every device, or phones play at their maximum refresh rate (#338). `player/local`'s `PlayerInvariantsTest` fails the build if either is undone.

**AppVariant:** An `AppVariant` enum (`PHONE`, `TV`) is provided by Hilt modules in `app/phone` and `app/tv`, allowing shared ViewModels (e.g. `SettingsViewModel`) to tailor behavior per variant instead of inspecting device hardware.

## Commits and Pull Requests

- **Conventional commits**, on every commit: `type(scope): summary`, such as `fix(player): …` or `feat(profiles): …`. The release skill picks the next version from them: `feat` is a minor release, `type!:` or a `BREAKING CHANGE` footer a major one, anything else a patch. Use `feat` only for something a user gets.
- Branches are `feat/<issue>-<slug>`, `fix/<issue>-<slug>`, `refactor/<issue>-<slug>` or `docs/<slug>`. The PR title follows the same convention and names the issue. Merging a `refactor/` branch publishes no beta (`beta.yml`): use it only for a change no user can notice. For a tests-only or CI-only PR, put `[skip beta]` in the merge commit: `gh pr merge <pr> --merge --body "[skip beta]"`.
- Pull requests are merged with a merge commit, once the checks are green. `main` has no required checks, so `gh pr merge --auto` merges at once: wait for the checks yourself.
- **Review bots:** Sourcery (`sourcery-ai[bot]`, check `Sourcery review`) and CodeRabbit (`coderabbitai[bot]`) review every PR a couple of minutes after it opens, and again after a push. Their checks pass whatever they find, so a green check doesn't mean the review is dealt with. Before merging, read each review (Sourcery's "Prompt for AI Agents" block in the review body lists every comment with its file and line) and answer each inline comment in its thread: fix it and say in which commit, or say why it doesn't apply (for example a setting that only exists on phones). Be critical: the bots are often wrong or theoretical, so check each finding against the code before acting on it, and push back when it doesn't hold. A reply that disagrees, with the reason, is a fine answer. Don't add code just to make a comment go away. Don't merge with a bot comment that has no reply. Reply with `gh api repos/midasvo/ephyra/pulls/<pr>/comments/<comment-id>/replies -f body='…'`. A finding that is real but outside the PR becomes an issue, linked in the reply.
- A push brings a new review: after the last push, wait until each bot's check has finished (it stays pending while the bot reviews) and answer that review too. Right before merging, count the inline comments without a reply once more, as a step of its own and not in the same command as the merge: a comment can arrive late.
- A bot that is out of its review budget shows `skipping` and posts no review (Sourcery's budget is weekly, counted in characters of diff). When no bot reviewed a PR, don't merge on the green checks alone. A change of a few lines can be read by whoever merges it, with a comment on the PR saying so. Anything larger, and anything in the player, a Room migration or the setup flow, gets an independent review posted on the PR first: a reviewer that did not write the change compares old and new behaviour and posts its findings as inline comments, which are answered like a bot's.
- Don't edit `CHANGELOG.md` or the version in a feature PR. The release skill writes both.

## Docs

Read the one that fits the change before starting:

| Change | Doc |
|---|---|
| Colours, type, shape, motion | `docs/identity/VISION.md` |
| `player/local`, `PlayerViewModel`, a player screen or the device profile | `docs/mpv-checklist.md`: run it on both backends |
| Phone layouts at tablet width | `docs/tablet/4-design.md`, test sizes in `docs/tablet/6-test-matrix.md` |
| TV screens | `docs/tv-research/adr/` |
| Startup or scrolling performance | `docs/performance.md` |
| Screenshots for the README, the site or the Play listing | `docs/screenshots.md` |
| Track selection or subtitle styling | `docs/track-test-series.md` |
| Google Play | `docs/play-store.md` |
| A server or an emulator to test on | `docs/dev-environment.md`, through the `test-server` and `test-devices` skills |
| Ports and adapters, the Jellyfin SDK boundary, `architecture/baseline/` | `docs/architecture/` |

The numbered files in `docs/mpv-research/`, `docs/tablet/` and `docs/tv-research/` are research from before the work was done. Where they differ from the code, the code is right.

## Translations & Localization

All user-visible strings and plurals that CE adds must be translated into all 36 supported languages. The `translate-strings` skill has the rules and the language list; use it for every string change.
- Upstream's translations come from Weblate into `values-<lang>/strings.xml`. Never edit those files. To change the text of an upstream string, add a CE key (`ce_<name>`) and use that instead.
- CE-only translations live in `<module>/src/main/res/values-<lang>/strings_ce.xml`, so upstream syncs don't conflict.
- `python3 .claude/skills/translate-strings/scripts/check-translations.py` verifies keys, placeholders and plural quantities. CI fails a PR with an incomplete CE string.
