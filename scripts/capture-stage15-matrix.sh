#!/bin/bash
# The Stage 15 Google Keep import frame matrix, in one deterministic run.
#
# Why a script rather than a hand-run sequence: the Stage 14 matrix was assembled by hand twice and both runs got
# the same class of thing wrong — a `ru` directory holding an English screen, a frame that photographed a
# different screen than its name claimed. Six configurations times five frames is thirty chances to forget
# one `wm density`, one `force-stop`, one font-scale check. The same guards are in this file for the same reason.
#
# What each configuration does:
#   * sets density, night mode and font scale, and *reads the font scale back* — `settings put system font_scale`
#     can and does fail silently from a script that ignores it, which produces a 1.0 frame filed under 1.3;
#   * force-stops both packages, so the configuration's process starts with the configuration's settings, and
#     clears the device's frame directory so no frame can be pulled that an earlier run left behind;
#   * runs `KeepImportInstrumentedTest#zFramesForThisConfiguration` once, which pastes the fixture, parses it,
#     photographs the field, the statistics card, one closed topic, the tree scrolled to its deepest line and the
#     imported subtree in the task list — each after the drawn row has been read and the window has been left long
#     enough to present a frame, not merely to bind one;
#   * copies the frames into docs only after the run said OK, so a failed configuration leaves the previous
#     evidence untouched instead of replacing it with half a set.
#
# The frames come from a test method rather than from `uiautomator` because the thing worth photographing is the
# screen after a real paste through the real widget: the indentation that decides the whole tree cannot be typed
# in by a shell, and a photograph of an empty field proves nothing about it.
#
# Usage: capture-stage15-matrix.sh [serial] [substring]
#   The second argument selects one configuration ("320", "ru 320", "dark") so a single width can be re-shot
#   without re-running the ones that already passed.
set -u
# Git Bash rewrites a `/sdcard/...` argument into a Windows path before adb ever sees it. Every device path
# below is written assuming these two variables are set.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
# This project's AVD is `taskplanner_avd`, which this host answers on emulator-5584. The guard under it is what
# makes naming a foreign device unforgivable rather than merely wrong: no default, typo or recycled shell can
# instrument an emulator that is not this one's.
SERIAL=${1:-emulator-5584}
ONLY=${2:-}
RUNNER=androidx.test.runner.AndroidJUnitRunner
PKG=com.taskplanner.test
TARGET=com.taskplanner
METHOD=com.taskplanner.ui.keep.KeepImportInstrumentedTest#zFramesForThisConfiguration
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT" || exit 3
OUT="docs/images/ui-redesign/after/s15"
DEVICE_DIR=/sdcard/Android/data/$TARGET/files/shots
LOGS="build/stage15-matrix"
FRAMES_PER_CONFIG=5
mkdir -p "$LOGS"

AVD=$(adb -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')
if [ "$AVD" != "taskplanner_avd" ]; then
  echo "REFUSED: $SERIAL is '$AVD', not taskplanner_avd." >&2
  exit 2
fi

# ---------------------------------------------------------------- one APK, named by its bytes
# The matrix is evidence about a build, so it has to say which one. The local APK and the one the package
# manager is running must be the same bytes, or there is no matrix to report.
APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [ ! -f "$APK" ] || [ ! -f "$TEST_APK" ]; then
  echo "REFUSED: $APK or $TEST_APK does not exist. Build them; do not photograph whatever is installed." >&2
  exit 2
fi
LOCAL_SHA=$(sha256sum "$APK" | cut -d' ' -f1)
DEV_PATH=$(adb -s "$SERIAL" shell pm path "$TARGET" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^package://')
DEV_SHA=$(adb -s "$SERIAL" shell sha256sum "$DEV_PATH" 2>/dev/null | tr -d '\r' | cut -d' ' -f1)
if [ -z "$DEV_PATH" ] || [ "$LOCAL_SHA" != "$DEV_SHA" ]; then
  echo "REFUSED: the installed APK is not the one in $APK." >&2
  echo "  local      $LOCAL_SHA" >&2
  echo "  installed  ${DEV_SHA:-none} at ${DEV_PATH:-nowhere}" >&2
  echo "  install both: adb -s $SERIAL install -r -g $APK && adb -s $SERIAL install -r $TEST_APK" >&2
  exit 2
fi
echo "provenance: $DEV_PATH"
echo "provenance: sha256=$LOCAL_SHA — local and installed agree; every frame below comes from these bytes"

run_config() {
  local language=$1 width=$2 theme=$3 scale=$4
  # The tag names the font scale in tenths because a directory name cannot hold a dot and stay readable. The
  # setting itself is a decimal, and the read-back under it is what stops a 1.0 frame being filed as a 1.3 one.
  local tag="$width-$theme${scale:+-}${scale/.}"
  local density=220
  [ "$width" = "320" ] && density=270

  echo "=== $language $tag: density=$density uimode=$theme font_scale=$scale"
  adb -s "$SERIAL" shell wm density "$density" >/dev/null
  adb -s "$SERIAL" shell "cmd uimode night $([ "$theme" = dark ] && echo yes || echo no)" >/dev/null
  adb -s "$SERIAL" shell "settings put system font_scale $scale" >/dev/null 2>&1
  local seen
  seen=$(adb -s "$SERIAL" shell settings get system font_scale | tr -d '\r')
  if [ "$seen" != "$scale" ]; then
    echo "FAILED: asked for font_scale $scale, the device answers $seen" >&2
    return 1
  fi
  # The density has to be read back for the same reason the scale is: `wm density` accepts a value the device
  # then refuses to apply on a reboot-less switch, and a frame shot at the old density is mislabelled evidence.
  local seen_density
  seen_density=$(adb -s "$SERIAL" shell wm density | tr -d '\r' | grep -o '[0-9]\+' | tail -1)
  if [ "$seen_density" != "$density" ]; then
    echo "FAILED: asked for density $density, the device answers $seen_density" >&2
    return 1
  fi
  sleep 5

  adb -s "$SERIAL" shell am force-stop "$TARGET" >/dev/null
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null
  adb -s "$SERIAL" shell rm -rf "$DEVICE_DIR/s15-$language-$tag" >/dev/null 2>&1

  local log
  log="$LOGS/$language-$tag.log"
  adb -s "$SERIAL" shell am instrument -w -e class "$METHOD" \
    -e language "$language" -e tag "$tag" "$PKG/$RUNNER" > "$log" 2>&1
  local code=$?
  if ! grep -qa "^OK (" "$log"; then
    echo "FAILED: $language/$tag (exit $code). See $log" >&2
    tail -25 "$log" >&2
    return 1
  fi
  printf '   frames %s\n' "$(grep -ao 'OK ([0-9]* tests\?)' "$log" | head -1)"

  rm -rf "$LOGS/pull-$language-$tag"
  if ! adb -s "$SERIAL" pull "$DEVICE_DIR/s15-$language-$tag" "$LOGS/pull-$language-$tag" 2>&1 | tail -2; then
    echo "FAILED: could not pull $language/$tag from the device" >&2
    return 1
  fi
  local count
  count=$(ls "$LOGS/pull-$language-$tag" 2>/dev/null | wc -l)
  if [ "$count" -lt "$FRAMES_PER_CONFIG" ]; then
    echo "FAILED: $language/$tag produced $count frames, expected $FRAMES_PER_CONFIG" >&2
    return 1
  fi
  # The directory is replaced, not merged into. A re-run of one configuration with a different set of frames — or
  # with a frame that has since been renamed — must not leave the previous run's picture sitting beside the new
  # ones under the same header.
  rm -rf "$OUT/s15-$language-$tag"
  mkdir -p "$OUT/s15-$language-$tag"
  cp "$LOGS/pull-$language-$tag"/*.png "$OUT/s15-$language-$tag/"
  echo "   wrote $(ls "$OUT/s15-$language-$tag" | tr '\n' ' ')"
}

# ---------------------------------------------------------------- the matrix
# Six configurations, the same set Stage 14 settled on: both languages at the device's own width in light and in
# dark, and the worst case — 320dp with the font at 1.3 — in both languages. A long Russian label and a
# four-level indent are two different ways that column fails, and only a frame says which one it is.
FAILED=0
for cfg in "en 393 light 1.0" "en 393 dark 1.0" "en 320 light 1.3" \
           "ru 393 light 1.0" "ru 393 dark 1.0" "ru 320 light 1.3"; do
  set -- $cfg
  if [ -n "$ONLY" ] && [[ "$*" != *"$ONLY"* ]]; then continue; fi
  run_config "$1" "$2" "$3" "$4" || FAILED=1
done

# The device is handed back as it was found: the next stage's tests assume an English app on a 393dp light
# screen at font 1.0, and a matrix that leaves the density at 270 teaches the following run a lie.
adb -s "$SERIAL" shell wm density reset >/dev/null
adb -s "$SERIAL" shell cmd uimode night no >/dev/null
adb -s "$SERIAL" shell settings put system font_scale 1.0 >/dev/null
adb -s "$SERIAL" shell am force-stop "$TARGET" >/dev/null

if [ "$FAILED" = "0" ]; then
  echo "=== matrix complete: $(ls "$OUT" | tr '\n' ' ')"
else
  echo "=== matrix INCOMPLETE: see the FAILED lines above; nothing partial was copied over good frames" >&2
fi
exit $FAILED
