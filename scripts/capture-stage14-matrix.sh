#!/bin/bash
# The Stage 14 language frame matrix, in one deterministic run.
#
# Why a script rather than a sequence of commands typed at the shell: the first two matrices were assembled by
# hand and both got it wrong in the same way — a `ru` directory holding an English screen, and a `01-tasks.png`
# that was a photograph of Settings. Each mistake was a step an operator could forget between the five
# configurations: a `wm density` not applied, a `force-stop` skipped, a `reset` flag left at its default so the
# teardown handed the language back before the walk ran. A script cannot forget one of five.
#
# What each configuration is:
#   * two instrumentation runs, with the app force-stopped between them. The first chooses the language through
#     the Settings row and photographs that row (`-e mode choose`, `-e reset false` so the choice survives the
#     teardown). The second starts a fresh process with the choice already in it and walks the seven screens
#     (`-e mode walk`, `-e apply false`). The force-stop is load-bearing: the app reads `users.is_current` once
#     per process into a Hilt singleton, and the choose run's teardown has taken its seeded profile away, so a
#     walk that reused that process would photograph an empty dashboard and a Profile screen with no name.
#   * the walk run resets the language on the way out, so the next configuration starts from the system default.
#
# Usage: capture-stage14-matrix.sh [serial] [substring]
#   The second argument selects one configuration ("320", "ru 320", "dark") so a single width can be re-shot
#   without re-running the four that already passed.
#   The frames land in docs/images/ui-redesign/after/s14/s14-<language>-<tag>/, and nothing is written there
#   until the run that produces it has passed.
set -u
# Git Bash rewrites a `/sdcard/...` argument into a Windows path before adb ever sees it. These two
# variables are what the rest of this script's device paths are written assuming; without them every
# `shell rm`, `shell ls` and `pull` below operates on the wrong file.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
SERIAL=${1:-emulator-5554}
ONLY=${2:-}
RUNNER=androidx.test.runner.AndroidJUnitRunner
PKG=com.taskplanner.test
TARGET=com.taskplanner
CLASS=com.taskplanner.ui.Stage14LanguageFramesInstrumentedTest
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# Every local path below is relative to this directory, and that is not a style choice: `adb` here is the
# Windows binary, and a destination like /c/my/... is a Git-Bash spelling it does not understand. It fails,
# and a script that sends the failure to /dev/null reports "0 frames" for a matrix it actually captured.
cd "$ROOT" || exit 3
OUT="docs/images/ui-redesign/after/s14"
DEVICE_DIR=/sdcard/Android/data/$TARGET/files/shots
LOGS="build/stage14-matrix"
mkdir -p "$LOGS"

AVD=$(adb -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')
if [ "$AVD" != "taskplanner_avd" ]; then
  echo "REFUSED: $SERIAL is '$AVD', not taskplanner_avd." >&2
  exit 2
fi

# ---------------------------------------------------------------- one configuration
# args: language width theme font-scale
run_config() {
  local language=$1 width=$2 theme=$3 scale=$4
  # The tag names the font scale in tenths — `13` for 1.3 — because a directory name cannot hold a dot and
  # stay readable. The setting, on the other hand, is a decimal: `settings put system font_scale` refuses
  # "13" outright, and a script that sets the scale and never checks it is the script that photographs a
  # 1.0 screen under a 1.3 header. `photograph` reads the ratio an sp and a dp resolve to and refuses that
  # lie; this is the half that has to get it right in the first place.
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
  sleep 5

  adb -s "$SERIAL" shell am force-stop "$TARGET" >/dev/null
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null
  # Cleared here, before the runs, so a configuration can never pull a frame an earlier run left behind.
  adb -s "$SERIAL" shell rm -rf "$DEVICE_DIR/s14-$language-$tag" >/dev/null 2>&1

  local runs=()
  if [ "$language" = "ru" ]; then
    runs+=("choose|-e reset false")
  fi
  runs+=("walk|-e apply false")

  local mode extra log code
  for entry in "${runs[@]}"; do
    mode=${entry%%|*}
    extra=${entry#*|}
    [ "$mode" = "walk" ] && adb -s "$SERIAL" shell am force-stop "$TARGET" >/dev/null
    log="$LOGS/$language-$tag-$mode.log"
    adb -s "$SERIAL" shell am instrument -w -e class "$CLASS" \
      -e language "$language" -e tag "$tag" -e mode "$mode" $extra \
      "$PKG/$RUNNER" > "$log" 2>&1
    code=$?
    if ! grep -qa "^OK (" "$log"; then
      echo "FAILED: $language/$tag/$mode (exit $code). See $log" >&2
      tail -20 "$log" >&2
      return 1
    fi
    printf '   %-6s %s\n' "$mode" "$(grep -ao 'OK ([0-9]* tests\?)' "$log" | head -1)"
  done

  # The frames are only copied after both runs said OK, so a failed configuration leaves the previous
  # evidence in place rather than replacing it with a half walk. The device directory is cleared *before*
  # the runs, not after: clearing it after would delete the frames this configuration just took.
  rm -rf "$LOGS/pull-$language-$tag"
  if ! adb -s "$SERIAL" pull "$DEVICE_DIR/s14-$language-$tag" "$LOGS/pull-$language-$tag" 2>&1 | tail -2; then
    echo "FAILED: could not pull $language/$tag from the device" >&2
    return 1
  fi
  count=$(ls "$LOGS/pull-$language-$tag" 2>/dev/null | wc -l)
  if [ "$count" -lt 7 ]; then
    echo "FAILED: $language/$tag produced $count frames, expected 7" >&2
    return 1
  fi
  mkdir -p "$OUT/s14-$language-$tag"
  cp "$LOGS/pull-$language-$tag"/*.png "$OUT/s14-$language-$tag/"
  echo "   wrote $(ls "$OUT/s14-$language-$tag" | tr '\n' ' ')"
}

# ---------------------------------------------------------------- the matrix
# English is walked, not chosen: the device's own language is English, and "leave it alone" is the
# system-default case the same frames have to show.
FAILED=0
# Six configurations, not five. The sixth — English at 320dp with the font at 1.3 — is there because a
# truncated Russian heading is only a localization finding if the same screen in English fits. «Октябрь
# 2026» and "October 2026" are the same twelve characters, so without this column the matrix cannot say
# whether a cut is the language's or the width's, and the honest answer to that question is a frame.
for cfg in "en 393 light 1.0" "en 393 dark 1.0" "en 320 light 1.3" \
           "ru 393 light 1.0" "ru 393 dark 1.0" "ru 320 light 1.3"; do
  set -- $cfg
  if [ -n "$ONLY" ] && [[ "$*" != *"$ONLY"* ]]; then continue; fi
  run_config "$1" "$2" "$3" "$4" || FAILED=1
done

# The device is handed back the way it was found, because the next stage's tests assume an English app on a
# 393dp light screen at font 1.0.
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
