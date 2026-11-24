# Contributing to Ephyra

Bug reports and pull requests are welcome. For a larger change, open an issue first so we can
agree on the direction before you build it.

## Building

You need JDK 21 and the Android SDK. Build the `libre` flavour; the phone app also has a
`proprietary` flavour with Google Cast, which you only need for Cast work. New to the project, or
on a new machine? [docs/get-started.md](docs/get-started.md) walks through what to install per
system, `scripts/doctor.sh` (checks the machine), the first build, the test server and an
emulator.

```bash
./gradlew :app:phone:assembleLibreDebug   # phone and tablet
./gradlew :app:tv:assembleLibreDebug      # Android TV
```

The debug build installs next to the release as **Ephyra Debug**, with its own data.

## Something to test on

No Jellyfin server or Android device at hand? [docs/dev-environment.md](docs/dev-environment.md)
sets up a throwaway server in Docker, with generated media for the awkward cases and test users,
and emulators for phone, tablet and Android TV:

```bash
tools/test-server/setup.sh       # Jellyfin at http://localhost:8096
scripts/avds.sh create ce_phone  # then: start ce_phone, install ce_phone
```

## Before you open a pull request

```bash
./gradlew ktfmtFormat          # CI fails on unformatted code
./gradlew testDebugUnitTest    # CI runs them too, but a local run is quicker to fix
```

- **Commit messages** follow [Conventional Commits](https://www.conventionalcommits.org/):
  `fix(player): …`, `feat(downloads): …`. The next version number is picked from them, so use
  `feat` only for something a user gets.
- **Strings.** Never edit `values-<lang>/strings.xml`: those are Findroid's and come from its
  Weblate. A string Ephyra adds goes in the module's `values/strings.xml`, with a translation in
  every `values-<lang>/strings_ce.xml` (36 languages). CI checks this with
  `.claude/skills/translate-strings/scripts/check-translations.py`. Machine-assisted
  translations are fine; a fix to one is a welcome pull request by itself.
- **Player changes** go through [docs/mpv-checklist.md](docs/mpv-checklist.md), on ExoPlayer and
  on mpv.
- **Anything visual** follows [docs/identity/VISION.md](docs/identity/VISION.md).
- Leave `CHANGELOG.md` and the version alone; both are written at release time.

## Finding your way

- The [development site](https://midasvo.nl/ephyra/dev/) is the way in: the areas of the app,
  a guide per area (where the code is, the rules, how to test it, what went wrong before), the
  modules, the architecture rules and an interactive model. Its pages are the Markdown in
  [docs/](docs/), so you can read them on GitHub too. Start with the area you want to change in
  [docs/areas.yaml](docs/areas.yaml).
- When you change code that an area guide describes, update the guide in the same pull request,
  or say in the description why it still holds (`Docs: no change needed — <reason>`; the pull
  request template has the line, and a check asks for it).
- [AGENTS.md](AGENTS.md) has the conventions in one place: build commands, architecture rules,
  commits and pull requests. Coding agents read it (Claude Code reads [CLAUDE.md](CLAUDE.md), with the same rules),
  and it reads just as well for people.
- [docs/](docs/README.md) lists the design documents and which of them are still current.
- A feature users get is added to [docs/features.yaml](docs/features.yaml): the
  [guide](https://midasvo.nl/ephyra/guide/) on the site and the chart in the README are drawn
  from it (`npm run readme-assets` in `devsite/`).
- Ephyra is a fork of [Findroid](https://github.com/jarnedemeulemeester/findroid), and Findroid's
  changes are merged in regularly. Code Ephyra adds is kept in its own files where it can be, so
  those merges stay small. A fix in code that Findroid still owns is best sent to Findroid too.
