#!/usr/bin/env bash
# Checks the machine for what Ephyra's development needs, and says per missing part what to do.
# Installs nothing and changes nothing. Works in Git Bash on Windows, on Linux and on macOS.
# docs/get-started.md is the page around it.
#
#   scripts/doctor.sh                 every group
#   scripts/doctor.sh build site      only these groups
#
# Groups:
#   build    git, JDK 21, the Android SDK and its packages, python3   (build, unit tests, check-docs)
#   site     Node and npm                                           (the development site)
#   server   Docker (in WSL on Windows), the test server             (tools/test-server/setup.sh)
#   devices  emulator, avdmanager, acceleration, system images, adb  (scripts/avds.sh)
#   github   gh, signed in                                          (pull requests, issues)
#
# Every line starts with ok, warn or FAIL. A FAIL line is followed by a "fix:" line.
# Exit code 0 when nothing failed, 1 when something did (warnings do not count).
#
# EPHYRA_WSL_DISTRO picks the WSL distribution that runs Docker (default: WSL's default).
# EPHYRA_TEST_SERVER_PORT is the test server's port (default 8096).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAILS=0
WARNS=0

ok() { printf 'ok    %-14s %s\n' "$1" "$2"; }
warn() { printf 'warn  %-14s %s\n' "$1" "$2"; WARNS=$((WARNS + 1)); [ -z "${3:-}" ] || printf '      %-14s fix: %s\n' "" "$3"; }
fail() { printf 'FAIL  %-14s %s\n' "$1" "$2"; printf '      %-14s fix: %s\n' "" "$3"; FAILS=$((FAILS + 1)); }
has() { command -v "$1" >/dev/null 2>&1; }

case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) OS=windows ;;
  Darwin) OS=mac ;;
  *) OS=linux ;;
esac

# A value from buildSrc/src/main/kotlin/Versions.kt, such as COMPILE_SDK.
version_of() { sed -n "s/.*const val $1 = \"\{0,1\}\([0-9.]*\)\"\{0,1\}.*/\1/p" "$ROOT/buildSrc/src/main/kotlin/Versions.kt" | head -1; }

# The Android SDK as Gradle finds it: local.properties first, then ANDROID_HOME, ANDROID_SDK_ROOT.
SDK=""
SDK_FROM=""
find_sdk() {
  local line
  if [ -f "$ROOT/local.properties" ]; then
    line="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tr -d '\r' | sed 's/\\\\/\\/g; s/\\:/:/g')"
    if [ -n "$line" ]; then
      if [ "$OS" = windows ] && has cygpath; then line="$(cygpath -u "$line")"; fi
      SDK="$line"; SDK_FROM="local.properties"; return
    fi
  fi
  if [ -n "${ANDROID_HOME:-}" ]; then SDK="${ANDROID_HOME}"; SDK_FROM="ANDROID_HOME"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then SDK="${ANDROID_SDK_ROOT}"; SDK_FROM="ANDROID_SDK_ROOT"; fi
  if [ "$OS" = windows ] && [ -n "$SDK" ] && has cygpath; then SDK="$(cygpath -u "$SDK")"; fi
}

sdk_tool() { # sdk_tool <path under the SDK, without .exe/.bat>
  local ext
  for ext in "" .exe .bat; do [ -f "$SDK/$1$ext" ] && { echo "$SDK/$1$ext"; return 0; }; done
  return 1
}

default_sdk() {
  case "$OS" in
    windows) echo "%LOCALAPPDATA%\\Android\\Sdk" ;;
    mac) echo "~/Library/Android/sdk" ;;
    *) echo "~/Android/Sdk" ;;
  esac
}

check_build() {
  local v major java_bin compile minor tools pkg dir
  echo "# build"
  if has git; then ok git "$(git --version | sed 's/git version //')"; else fail git "not found" "install Git (on Windows: Git for Windows, which brings Git Bash)"; fi

  java_bin=java
  if [ -n "${JAVA_HOME:-}" ]; then java_bin="${JAVA_HOME%/}/bin/java"; fi
  if v="$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -1)" && [ -n "$v" ]; then
    major="${v%%.*}"
    if [ "$major" = 21 ]; then ok jdk "$v${JAVA_HOME:+ (JAVA_HOME)}"
    else fail jdk "$v; the build needs 21" "install a JDK 21 (Temurin) and point JAVA_HOME at it"; fi
  else
    fail jdk "no java${JAVA_HOME:+ in JAVA_HOME}" "install a JDK 21 (Temurin) and set JAVA_HOME"
  fi

  find_sdk
  if [ -z "$SDK" ]; then
    fail android-sdk "not set: no local.properties, no ANDROID_HOME" \
      "install the SDK (Android Studio, or the command-line tools) and export ANDROID_HOME=$(default_sdk); a git worktree has no local.properties of its own"
  elif [ ! -d "$SDK" ]; then
    fail android-sdk "$SDK_FROM points at a folder that does not exist" "correct $SDK_FROM"
  else
    ok android-sdk "from $SDK_FROM"
    compile="$(version_of COMPILE_SDK)"; minor="$(version_of COMPILE_SDK_MINOR)"; tools="$(version_of BUILD_TOOLS)"
    dir="android-$compile"; [ -z "$minor" ] || [ "$minor" = 0 ] || dir="android-$compile.$minor"
    for pkg in "platforms/$dir" "build-tools/$tools" "platform-tools"; do
      if [ -d "$SDK/$pkg" ]; then ok sdk-package "$pkg"
      elif [ -d "$SDK/licenses" ] && [ -n "$(ls "$SDK/licenses" 2>/dev/null)" ]; then
        warn sdk-package "$pkg missing; Gradle downloads it on the first build (licences are accepted)"
      else
        fail sdk-package "$pkg missing, and no licences accepted" "sdkmanager --licenses, then sdkmanager \"$(echo "$pkg" | tr / ';')\""
      fi
    done
  fi

  # On Windows, python3 can be the Microsoft Store stub, which prints a message and fails.
  if has python3 && v="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null)"; then
    ok python3 "$v"
  elif [ "$OS" = windows ] && has python && v="$(python -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null)"; then
    warn python3 "python3 is not a working Python here; python is $v" \
      "use python instead of python3 in the commands, or turn off the python3 app execution alias in Windows settings"
  else
    fail python3 "not found" "install Python 3 (python.org, or your package manager)"
  fi
}

check_site() {
  local v
  echo "# site"
  if has node && v="$(node --version 2>/dev/null)"; then
    if [ "$(echo "${v#v}" | cut -d. -f1)" -lt 24 ]; then warn node "$v; CI builds the site with Node 24" "install Node 24 or newer"
    else ok node "$v"; fi
  else
    fail node "not found" "install Node 24 (nodejs.org, or your package manager)"
  fi
  if has npm; then ok npm "$(npm --version 2>/dev/null)"; else fail npm "not found" "npm comes with Node"; fi
}

# Runs a command where Docker lives: here, or in WSL on Windows.
in_docker_host() {
  if [ "$OS" = windows ]; then
    MSYS_NO_PATHCONV=1 wsl.exe ${EPHYRA_WSL_DISTRO:+-d "$EPHYRA_WSL_DISTRO"} -- "$@"
  else
    "$@"
  fi
}

check_server() {
  local v port where answer
  echo "# server"
  port="${EPHYRA_TEST_SERVER_PORT:-8096}"
  where=""
  if [ "$OS" = windows ]; then
    where=" in WSL${EPHYRA_WSL_DISTRO:+ ($EPHYRA_WSL_DISTRO)}"
    if ! has wsl.exe || ! wsl.exe --status >/dev/null 2>&1; then
      fail wsl "not available" "install WSL 2 with a Linux distribution (wsl --install), then Docker inside it"
      return
    fi
  fi
  if v="$(in_docker_host docker version --format '{{.Server.Version}}' 2>/dev/null)" && [ -n "$v" ]; then
    ok docker "$v$where"
  else
    fail docker "no Docker engine answers$where" "install Docker Engine (or Docker Desktop with WSL integration) and start it"
  fi
  if v="$(in_docker_host python3 --version 2>/dev/null)"; then ok python3-server "${v#Python }$where"
  else fail python3-server "no python3$where" "install python3 where Docker runs"; fi
  if has curl && answer="$(curl -s -m 5 "http://localhost:$port/System/Info/Public" 2>/dev/null)" && [ -n "$answer" ]; then
    ok test-server "answers on localhost:$port, Jellyfin $(echo "$answer" | sed -n 's/.*"Version":"\([^"]*\)".*/\1/p')"
  else
    warn test-server "nothing answers on localhost:$port" \
      "tools/test-server/setup.sh$( [ "$OS" = windows ] && echo " (from Git Bash: wsl.exe -- bash tools/test-server/setup.sh)")"
  fi
}

check_devices() {
  local emu out
  echo "# devices"
  [ -n "$SDK" ] || find_sdk
  if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    fail android-sdk "not found, see the build group" "set ANDROID_HOME"
    return
  fi
  if emu="$(sdk_tool emulator/emulator)"; then
    ok emulator "$("$emu" -version 2>/dev/null | head -1 | sed 's/Android emulator version //')"
    out="$("$emu" -accel-check 2>&1 | tr -d '\r')"
    if echo "$out" | grep -qi 'usable\|is installed and usable\|KVM (version'; then
      ok acceleration "$(echo "$out" | grep -i 'usable\|kvm' | head -1)"
    else
      fail acceleration "$(echo "$out" | grep -v '^accel\|^[0-9]*$' | head -1)" \
        "turn on virtualisation: WHPX (Windows Hypervisor Platform) on Windows, KVM on Linux"
    fi
  else
    fail emulator "not in the SDK" "sdkmanager emulator"
  fi
  if sdk_tool cmdline-tools/latest/bin/avdmanager >/dev/null; then ok cmdline-tools "avdmanager found"
  else fail cmdline-tools "no cmdline-tools/latest" "install 'Android SDK Command-line Tools (latest)' from Android Studio's SDK Manager"; fi
  if has adb || sdk_tool platform-tools/adb >/dev/null; then ok adb "$( (has adb && adb version || "$(sdk_tool platform-tools/adb)" version) 2>/dev/null | head -1 | sed 's/Android Debug Bridge version //')"
  else fail adb "not found" "sdkmanager platform-tools"; fi
  local state img
  while read -r state img; do
    case "$state" in
      installed:) ok system-image "$img" ;;
      missing:) warn system-image "$img missing" \
        "sdkmanager \"$img\" (you accept Google's licence; over 1 GB; on Windows put the name in a file and pass --package_file=<file>)" ;;
    esac
  done < <(ANDROID_HOME="$SDK" bash "$ROOT/scripts/avds.sh" images 2>/dev/null)
}

check_github() {
  echo "# github"
  if ! has gh; then warn gh "not found" "install the GitHub CLI (cli.github.com); needed for pull requests and issues"; return; fi
  if gh auth status >/dev/null 2>&1; then ok gh "$(gh --version | head -1 | sed 's/gh version //; s/ .*//'), signed in"
  else warn gh "not signed in" "gh auth login"; fi
}

WANT="${*:-build site server devices github}"
for g in $WANT; do
  case "$g" in
    build) check_build ;;
    site) check_site ;;
    server) check_server ;;
    devices) check_devices ;;
    github) check_github ;;
    -h | --help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "Unknown group: $g (build, site, server, devices, github)" >&2; exit 2 ;;
  esac
done

echo
if [ "$FAILS" -gt 0 ]; then echo "doctor: $FAILS to fix, $WARNS warning(s)"; exit 1; fi
echo "doctor: ready ($WARNS warning(s))"
