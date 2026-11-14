#!/usr/bin/env bash
# Drives a throwaway Android emulator for checking Janeway by hand or from Claude Code.
#
#   scripts/emulator-test.sh start                 create the AVD if needed, boot it headless, wait until usable
#   scripts/emulator-test.sh push <dir>            copy a folder of test music to /sdcard/Music/<dir name>
#   scripts/emulator-test.sh install               install the debug APK and launch the app
#   scripts/emulator-test.sh pick-folder <name>    choose /sdcard/Music/<name> in the app's folder picker
#   scripts/emulator-test.sh tap <regex>           tap the first element whose text matches (ERE; escape ( ) .)
#   scripts/emulator-test.sh texts                 print the visible texts, one per line
#   scripts/emulator-test.sh screenshot <file>     save a PNG screenshot
#   scripts/emulator-test.sh crashes               print crash logs since the last install
#   scripts/emulator-test.sh stop                  shut the emulator down and delete the AVD
#
# Environment: ANDROID_HOME (default ~/Library/Android/sdk), JANEWAY_AVD_IMAGE
# (default "system-images;android-33;google_apis;arm64-v8a", must be installed).
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
AVD="janeway_test"
IMAGE="${JANEWAY_AVD_IMAGE:-system-images;android-33;google_apis;arm64-v8a}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
PACKAGE="io.github.reva.janeway"

ui_lines() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml | perl -pe 's/></>\n</g'
}

tap() {
  local pattern="$1" bounds
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    bounds=$(ui_lines | grep -E "(text|content-desc)=\"$pattern\"" | head -1 \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    if [ -n "$bounds" ]; then
      set -- $bounds
      "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
      return 0
    fi
    sleep 1
  done
  echo "No element matches: $pattern" >&2
  return 1
}

case "${1:-}" in
  start)
    if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
      echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d pixel_6 >/dev/null
    fi
    if ! "$ADB" devices | grep -q '^emulator-'; then
      nohup "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-snapshot -no-boot-anim >/dev/null 2>&1 &
    fi
    "$ADB" wait-for-device shell \
      'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 2; done; while ! ls /sdcard/Music >/dev/null 2>&1; do sleep 2; done'
    sleep 5
    echo "Emulator ready"
    ;;
  push)
    "$ADB" push "${2:?folder}" /sdcard/Music/ | tail -1
    ;;
  install)
    "$ADB" install -r "$APK" | tail -1
    "$ADB" logcat -c
    "$ADB" shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null
    ;;
  pick-folder)
    tap "Choose folder|Folder"
    tap "Music"
    tap "${2:?folder name}"
    tap "USE THIS FOLDER"
    tap "ALLOW"
    ;;
  tap)
    tap "${2:?text regex}"
    ;;
  texts)
    ui_lines | grep -oE 'text="[^"]+"' | sed -e 's/^text="//' -e 's/"$//'
    ;;
  screenshot)
    "$ADB" exec-out screencap -p > "${2:?file}"
    ;;
  crashes)
    "$ADB" logcat -d -b crash
    ;;
  stop)
    "$ADB" emu kill >/dev/null 2>&1 || true
    sleep 2
    "$AVDMANAGER" delete avd -n "$AVD" >/dev/null 2>&1 || true
    echo "Emulator removed"
    ;;
  *)
    sed -n '2,15p' "$0"
    exit 1
    ;;
esac
