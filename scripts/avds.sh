#!/usr/bin/env bash
# Create, start, stop and delete the test AVDs: phone, tablets, fold and Android TV.
# Works under Git Bash on Windows and on Linux/macOS. Needs ANDROID_HOME (or ANDROID_SDK_ROOT).
# docs/dev-environment.md has the whole story; the tablet sizes are docs/tablet/4-design.md rule 9.
#
#   scripts/avds.sh list                       every AVD here: port, exists, running
#   scripts/avds.sh images                     the system images they need, and how to install them
#   scripts/avds.sh create  [--force] [name|all]   skips an AVD that exists, unless --force
#   scripts/avds.sh start   <name> [--gpu]     cold boot, waits until booted, media volume to 0
#   scripts/avds.sh up      <name> [--gpu]     create if missing, start if not running, install
#   scripts/avds.sh stop    <name>
#   scripts/avds.sh delete  <name|all>
#   scripts/avds.sh install <name> [apk]       the debug build that fits the AVD (phone or tv, its ABI)
#   scripts/avds.sh portrait|landscape <name>
#   scripts/avds.sh shot    <name> <file.png>
#
# `start` renders in software (-gpu swiftshader_indirect): slower, but mpv crashes emulators that
# use the host GPU. `--gpu` leaves the choice to the emulator, for work that never opens mpv.
# Every start passes -crash-report-mode never -no-metrics: without them a new emulator can hang on
# a consent dialog nobody sees. EMULATOR_FLAGS adds flags of your own, such as -no-window.
# The media volume is set to 0 after boot: the test media is a test tone.
#
# The 13" Chromebook (1600x900 dp) needs no AVD of its own: use the `resizable` device profile
# or the emulator's resizable window and resize it.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "Set ANDROID_HOME to the Android SDK" >&2; exit 1; }
API=android-34
NAMES="ce_phone ce_phone_tmp ce_tablet_8 ce_tablet_10 ce_fold ce_tv_1080p ce_tv_4k"

exe() { # exe <path without extension> -> the path with .exe/.bat if that is what exists
  local base="$1" ext
  for ext in "" .exe .bat; do
    if [ -f "$base$ext" ]; then echo "$base$ext"; return; fi
  done
  echo "Not found: $base" >&2; exit 1
}
AVDMANAGER="$(exe "$SDK/cmdline-tools/latest/bin/avdmanager")"
EMULATOR="$(exe "$SDK/emulator/emulator")"
ADB="$(command -v adb || exe "$SDK/platform-tools/adb")"

port() {
  case "$1" in
    ce_phone) echo 5554 ;;
    ce_tv_1080p) echo 5572 ;;
    ce_tv_4k) echo 5574 ;;
    ce_phone_tmp) echo 5580 ;;
    ce_tablet_8) echo 5582 ;;
    ce_tablet_10) echo 5584 ;;
    ce_fold) echo 5586 ;;
    *) echo "Unknown AVD: $1 (one of: $NAMES)" >&2; exit 1 ;;
  esac
}

serial() { echo "emulator-$(port "$1")"; }

is_tv() { case "$1" in ce_tv_*) return 0 ;; *) return 1 ;; esac; }

# The TV image is 32-bit x86: Google ships no x86_64 Android TV image for this API level.
tag_for() { if is_tv "$1"; then echo android-tv; else echo google_apis; fi; }
abi_for() { if is_tv "$1"; then echo x86; else echo x86_64; fi; }
image_for() { echo "system-images;$API;$(tag_for "$1");$(abi_for "$1")"; }
has_image() { [ -d "$SDK/system-images/$API/$(tag_for "$1")/$(abi_for "$1")" ]; }

has_device() { "$AVDMANAGER" list device -c | tr -d '\r' | grep -qxF "$1"; }

device_for() {
  case "$1" in
    ce_phone | ce_phone_tmp) echo pixel_6 ;; # 412x915 dp
    ce_tablet_8) echo small_tablet ;;        # resized to 1024x640 dp in config.ini
    ce_tablet_10) echo pixel_tablet ;;       # 1280x800 dp, natural orientation landscape
    ce_tv_1080p) echo tv_1080p ;;            # 960x540 dp
    ce_tv_4k) echo tv_4k ;;                  # 960x540 dp at 4K
    ce_fold)
      local d
      for d in pixel_fold pixel_9_pro_fold pixel_10_pro_fold; do
        if has_device "$d"; then echo "$d"; return; fi
      done
      echo "No foldable profile in 'avdmanager list device -c'" >&2; exit 1 ;;
  esac
}

avd_dir() { echo "${ANDROID_AVD_HOME:-$HOME/.android/avd}/$1.avd"; }

set_config() { # set_config <name> <key> <value>
  local f; f="$(avd_dir "$1")/config.ini"
  if grep -q "^$2 *=" "$f"; then sed -i.bak "s|^$2 *=.*|$2 = $3|" "$f"; else echo "$2 = $3" >> "$f"; fi
  rm -f "$f.bak"
}

images() {
  local n img seen=""
  for n in $NAMES; do
    img="$(image_for "$n")"
    case " $seen " in *" $img "*) continue ;; esac
    seen="$seen $img"
    if has_image "$n"; then echo "installed: $img"; else echo "missing:   $img"; fi
  done
  echo
  echo "Install a missing one yourself (it asks you to accept Google's licence, and is over 1 GB):"
  echo "  \"$SDK/cmdline-tools/latest/bin/sdkmanager\" \"<image>\""
}

create_one() {
  local n="$1" d
  if ! has_image "$n"; then
    echo "$n needs $(image_for "$n"), which is not installed: see 'scripts/avds.sh images'" >&2
    return 1
  fi
  # An AVD holds the app's login and downloads: never replace one without being asked to.
  if [ -d "$(avd_dir "$n")" ] && [ "$FORCE" != yes ]; then
    echo "$n exists already, left alone (create --force $n replaces it and loses its data)"
    return 0
  fi
  d="$(device_for "$n")"
  echo "Creating $n ($d)"
  echo no | "$AVDMANAGER" create avd --force -n "$n" -k "$(image_for "$n")" -d "$d" >/dev/null
  set_config "$n" hw.keyboard yes
  if [ "$n" = ce_tablet_8 ]; then # 1024x640 px at 160 dpi is 1024x640 dp
    set_config "$n" hw.lcd.width 1024
    set_config "$n" hw.lcd.height 640
    set_config "$n" hw.lcd.density 160
  fi
}

delete_one() {
  if "$AVDMANAGER" delete avd -n "$1" >/dev/null 2>&1; then echo "Deleted $1"; else echo "$1 not present"; fi
}

each() { # each <function> <name|all>
  local n
  if [ "$2" = all ]; then
    for n in $NAMES; do "$1" "$n"; done
  else
    port "$2" >/dev/null
    "$1" "$2"
  fi
}

rotate() { # rotate <name> <portrait|landscape>
  # user_rotation 0 is the natural orientation: landscape on the tablets and the unfolded fold
  # (its hinge is then vertical, so tabletop needs portrait), portrait on the phone.
  local rot=0 s
  s="$(serial "$1")"
  case "$1" in
    ce_tablet_8|ce_tablet_10|ce_fold) if [ "$2" = portrait ]; then rot=1; fi ;;
    *) if [ "$2" = landscape ]; then rot=1; fi ;;
  esac
  "$ADB" -s "$s" shell settings put system accelerometer_rotation 0
  "$ADB" -s "$s" shell settings put system user_rotation "$rot"
}

list() {
  local n existing running state
  existing="$("$EMULATOR" -list-avds | tr -d '\r')"
  running="$("$ADB" devices | tr -d '\r')"
  for n in $NAMES; do
    state="not created"
    if echo "$existing" | grep -qxF "$n"; then state="created"; fi
    if echo "$running" | grep -q "^$(serial "$n")[[:space:]]"; then state="running"; fi
    echo "$n  $(serial "$n")  $state"
  done
}

install() { # install <name> [apk]
  local n="$1" apk="${2:-}" app=phone root
  if is_tv "$n"; then app=tv; fi
  if [ -z "$apk" ]; then
    root="$(cd "$(dirname "$0")/.." && pwd)"
    apk="$root/app/$app/build/outputs/apk/libre/debug/$app-libre-$(abi_for "$n")-debug.apk"
    [ -f "$apk" ] || { echo "No $apk: run ./gradlew :app:$app:assembleLibreDebug first" >&2; exit 1; }
  fi
  # -r keeps the app's data, so the login stays. Do not uninstall first.
  "$ADB" -s "$(serial "$n")" install -r "$apk"
}

# The port says nothing about which AVD holds it: ask the emulator its name.
avd_on_port() { # the AVD name the emulator on this AVD's port reports; tries for 10 seconds
  local i name
  for i in 1 2 3 4 5; do
    name="$("$ADB" -s "$(serial "$1")" emu avd name 2>/dev/null | tr -d '\r' | head -1)"
    if [ -n "$name" ] && [ "$name" != OK ]; then echo "$name"; return 0; fi
    sleep 2
  done
  return 1
}

is_running() { # is_running <name>: its port answers, as this AVD. Stops the script otherwise.
  local other
  "$ADB" devices | tr -d '\r' | grep -q "^$(serial "$1")[[:space:]]" || return 1
  if ! other="$(avd_on_port "$1")"; then
    echo "$(serial "$1") does not say which AVD it is: stop it, or use 'install' if you know it is $1" >&2
    exit 1
  fi
  if [ "$other" != "$1" ]; then
    echo "$(serial "$1") is $other, not $1: stop it or start $1 yourself on another port" >&2
    exit 1
  fi
}

wait_booted() { # wait_booted <name> [emulator pid]: one 10-minute deadline for adb and the boot
  local s deadline
  s="$(serial "$1")"
  deadline=$(($(date +%s) + 600))
  until [ "$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    if [ -n "${2:-}" ] && ! kill -0 "$2" 2>/dev/null; then echo "the emulator for $1 exited before it booted" >&2; return 1; fi
    if [ "$(date +%s)" -ge "$deadline" ]; then echo "$1 did not finish booting in 10 minutes" >&2; return 1; fi
    sleep 3
  done
}

start_one() { # start_one <name> [--gpu]
  local n="$1" p gpu="-gpu swiftshader_indirect" pid
  p="$(port "$n")"
  if [ "${2:-}" = --gpu ]; then gpu=""; fi
  if is_running "$n"; then echo "$n is running already as emulator-$p" >&2; return 1; fi
  # shellcheck disable=SC2086
  "$EMULATOR" -avd "$n" -port "$p" -no-snapshot -no-boot-anim $gpu -crash-report-mode never -no-metrics \
    ${EMULATOR_FLAGS:-} >/dev/null 2>&1 &
  pid=$!
  wait_booted "$n" "$pid" || return 1
  # Volume down to 0. `cmd media_session volume --set 0` reports success and changes nothing on
  # the API 34 images; key presses do.
  "$ADB" -s "emulator-$p" shell 'for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do input keyevent 25; done' || true
  echo "$n is up as emulator-$p"
}

cmd="${1:-}"
shift || true
case "$cmd" in
  create)
    FORCE=no
    if [ "${1:-}" = --force ]; then FORCE=yes; shift; fi
    each create_one "${1:-all}" ;;
  delete) each delete_one "${1:?name or all}" ;;
  images) images ;;
  list) list ;;
  install) install "${1:?name}" "${2:-}" ;;
  start) start_one "${1:?name}" "${2:-}" ;;
  up)
    n="${1:?name}"
    FORCE=no
    create_one "$n"
    if is_running "$n"; then
      echo "$n is running already as $(serial "$n")"
      wait_booted "$n" # it may still be booting
    else
      start_one "$n" "${2:-}"
    fi
    install "$n" ;;
  stop) "$ADB" -s "$(serial "${1:?name}")" emu kill ;;
  portrait | landscape) rotate "${1:?name}" "$cmd" ;;
  shot)
    n="${1:?name}"
    out="${2:?file}"
    export MSYS_NO_PATHCONV=1
    "$ADB" -s "$(serial "$n")" shell screencap -p /data/local/tmp/cap.png
    "$ADB" -s "$(serial "$n")" pull /data/local/tmp/cap.png "$out" >/dev/null
    echo "Saved $out" ;;
  *) sed -n '2,24p' "$0"; exit 1 ;;
esac
