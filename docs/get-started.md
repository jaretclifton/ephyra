# Get started

From an empty machine to the app on an emulator, logged in to a test server with test data. For
people and for agents: every step is a command, with what it prints when it works.

The steps in order. Stop where your task stops: a docs change needs step 2 at most, a change to
the shared Kotlin code step 3, anything you have to see in the app all six.

| Step | What | Time, measured |
|---|---|---|
| 1 | Install the tools | Depends on the downloads |
| 2 | Check the machine: `scripts/doctor.sh` | A few seconds |
| 3 | The fast check and the first build | About a minute each with a warm Gradle cache; see below |
| 4 | The test server: `tools/test-server/setup.sh` | About 2.5 minutes, plus a 2.5 GB image |
| 5 | An emulator with the app: `scripts/avds.sh up` | About 50 seconds once the system image is there |
| 6 | Log in | A minute by hand |

The test server and the emulators are described in full in
[dev-environment.md](dev-environment.md): what is on the server, the users, every AVD and the
things that cost us time. This page gets you there; that page is the reference.

Measured on a Windows 11 machine with the tools installed: from nothing to the app on a new
emulator, logged in to a new test server, took about six minutes of waiting and eight commands.
The downloads come on top the first time: Gradle and the dependencies, the Jellyfin image
(2.5 GB) and a system image (over 1 GB).

## 1. Install the tools

| Tool | For | Windows 11 | Linux | macOS |
|---|---|---|---|---|
| Git | Everything | Git for Windows (it brings Git Bash: run the scripts there) | Package manager | Xcode tools or Homebrew |
| JDK 21 | The build | Temurin 21, with `JAVA_HOME` set | Package manager or Temurin | Temurin 21 |
| Android SDK | The build, the emulators | Android Studio, or the command-line tools | The same | The same |
| Python 3 | `check-docs.py`, the translation check, the test server's scripts | python.org; see the note below | Usually there | Usually there |
| Docker | The test server only | Inside WSL 2 (Docker Engine in the distribution, or Docker Desktop with WSL integration) | Docker Engine | Docker Desktop, or another engine |
| Node 24 | The development site only | nodejs.org | Package manager or nodejs.org | The same |
| `gh` | Pull requests and issues | cli.github.com | The same | The same |

Android SDK packages, all from Android Studio's SDK Manager or `sdkmanager`:

- for the build: the platform and the build tools that `buildSrc/src/main/kotlin/Versions.kt`
  names (`COMPILE_SDK`, `BUILD_TOOLS`), and `platform-tools`. Once the SDK licences are accepted,
  Gradle downloads a missing platform or build tools itself on the first build;
- for the emulators: `emulator`, `cmdline-tools;latest`, hardware acceleration (WHPX on Windows,
  KVM on Linux), and the system images that `scripts/avds.sh images` lists.

Notes per system:

- **Windows.** Run the repository's scripts in Git Bash. The test server runs in WSL; the build,
  the emulators and `adb` run on Windows. `python3` may be the Microsoft Store stub, which prints
  "Python was not found": use `python`, or turn the alias off under the app execution aliases in
  Windows settings.
- **Mac with Apple Silicon.** The build works. The emulators in `scripts/avds.sh` use x86 system
  images, which an ARM host cannot run; make an AVD from an `arm64-v8a` image by hand
  (`avdmanager create avd -n <name> -k "system-images;android-34;google_apis;arm64-v8a" -d
  pixel_6`) and install `phone-libre-arm64-v8a-debug.apk` with `adb install -r`. Not tried here:
  there was no Mac.
- **Git worktrees** have no `local.properties` of their own. Set `ANDROID_HOME` in your shell
  profile instead, so every checkout finds the SDK.
- **CI** is Linux (`ubuntu-latest`) with JDK 21; the site is built with Node 24.

## 2. Check the machine

```bash
git clone https://github.com/midasvo/ephyra.git
cd ephyra
scripts/doctor.sh
```

`doctor.sh` installs nothing. It checks each part and prints one line per check: `ok`, `warn` or
`FAIL`, with a `fix:` line under each that is not `ok`. It exits with 1 when something failed.
Pass the groups you need (`build`, `site`, `server`, `devices`, `github`) to check only those;
`scripts/doctor.sh build` is what a build and the unit tests need.

On a machine with everything:

```text
# build
ok    git            2.55.0
ok    jdk            21.0.12.1 (JAVA_HOME)
ok    android-sdk    from ANDROID_HOME
ok    sdk-package    platforms/android-37.1
ok    sdk-package    build-tools/37.0.0
ok    sdk-package    platform-tools
ok    python3        3.12
# site
ok    node           v24.11.0
ok    npm            11.6.2
# server
ok    docker         29.9.0 in WSL
ok    python3-server 3.14.4 in WSL
ok    test-server    answers on localhost:8096, Jellyfin 12.4.0
# devices
ok    emulator       37.2.13.0
ok    acceleration   WHPX(10.0.26300) is installed and usable.
ok    cmdline-tools  avdmanager found
ok    adb            1.0.41
ok    system-image   system-images;android-34;google_apis;x86_64
ok    system-image   system-images;android-34;android-tv;x86
# github
ok    gh             2.101.0, signed in

doctor: ready (0 warning(s))
```

## 3. The fast check and the first build

```bash
./gradlew :domain:test :application:test :architecture:test   # pure Kotlin and the architecture rules
./gradlew :app:phone:assembleLibreDebug                       # the phone app, debug
./gradlew :app:tv:assembleLibreDebug                          # the TV app, debug
```

Each ends with `BUILD SUCCESSFUL`. The APKs are in
`app/phone/build/outputs/apk/libre/debug/` (one per ABI; an emulator takes the `x86_64` one) and
the same path under `app/tv`. The first run of Gradle downloads Gradle itself and the
dependencies; after that, with a warm cache, the fast check took 50 seconds and the phone APK
57 seconds on the machine this was measured on. Without the build cache, compiling the phone
APK took 1 minute 32 seconds (downloads not counted). CI's assemble jobs take about a minute and a half.

Before a pull request: `./gradlew ktfmtCheck testDebugUnitTest` (what CI runs) and, for a
change to the docs, `python3 .claude/skills/start-work/scripts/check-docs.py`.

## 4. The test server

```bash
tools/test-server/setup.sh                              # Linux, macOS, inside WSL
wsl.exe -- bash tools/test-server/setup.sh              # Windows, from Git Bash in the checkout
```

It pulls the Jellyfin image (2.5 GB, once), generates the media with the ffmpeg inside that
image, runs the setup wizard through the API, adds the libraries and the test users, and waits
for the scan. A first run took 141 seconds once the image was there. It ends with a report:

```text
with a poster: 19; with cast: 19
with trickplay: 58
with chapters: 2
users: admin, nodownload, nopass, second, tester
credentials are in /home/<you>/ephyra-test-server/creds.env (emulator address: http://10.0.2.2:8096)
```

Running it again starts a stopped server and changes nothing else. The media, the users and the
other commands are in [dev-environment.md](dev-environment.md#the-test-server). The passwords
stay in `creds.env`: read one with `sed -n 's/^JF_TESTER_PASS=//p' <path to creds.env>` and do
not paste it anywhere.

## 5. An emulator with the app

```bash
scripts/avds.sh images        # the two system images; install a missing one with sdkmanager
scripts/avds.sh up ce_phone   # Pixel 6, Android 14: create, start, install the debug APK
```

`up` creates the AVD if it is missing, starts it if it is not running (software rendering, no
crash dialog, media volume at 0: the test media is a test tone) and installs the debug APK that
fits it. It ends with:

```text
ce_phone is up as emulator-5554
Performing Streamed Install
Success
```

A first `up` took 51 seconds, a second one on a stopped AVD 32. For the TV app: `ce_tv_1080p`,
after `./gradlew :app:tv:assembleLibreDebug`. Every AVD, its port and its purpose:
[dev-environment.md](dev-environment.md#the-emulators).

## 6. Log in

In the app: add the server `http://10.0.2.2:8096` and log in as `tester` with the password from
`creds.env`. `install` keeps the app's data, so this is once per AVD.

## For agents

- Start from `CLAUDE.md`. `scripts/doctor.sh` tells you what this machine can do; act on its
  `fix:` lines only where the line says nothing is installed (installing is the user's step).
- Without an emulator and without Docker (a cloud sandbox, say) you can still do most of the
  work. With no Android SDK at all: the fast check (`:domain:test :application:test
  :architecture:test`, pure Kotlin), `ktfmtCheck`, `check-docs.py`, the translation check and the
  site build; a task of an Android module stops with "SDK location not found". With the SDK but
  no emulator: every unit test (`./gradlew testDebugUnitTest`) and every APK. What you cannot do
  there is see the app run; say so in the pull request.
- The `test-server` and `test-devices` skills in `.claude/skills/` have the rules for acting on
  the server and the emulators (whose devices you may touch, passwords, input). They point back
  here and to [dev-environment.md](dev-environment.md).
- Address every device by serial (`adb -s emulator-5554 …`); never a serial that does not start
  with `emulator-` unless the user named it.
