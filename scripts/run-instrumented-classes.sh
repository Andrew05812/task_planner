#!/bin/bash
# One instrumented class per instrumentation process, against one device, with the numbers written down.
#
# Why one class per run rather than one run for the suite — measured, not assumed:
#  * the app keeps its signed-in profile in a Hilt singleton, and `SeededProfile` documents it
#    (`app/src/androidTest/.../testing/SeededProfile.kt:15-18`): the process reads `users.is_current` once and
#    holds that answer for its life. A class that seeds a profile of its own therefore writes under the
#    *previous* class's profile when the two share a process, and its rows never reach the screen. That is what
#    a single `connectedDebugAndroidTest` over 89 tests showed: 21 failures, every one of them a UI class
#    waiting for seeded data that went to somebody else's profile.
#  * the same emulator also answered a long runner with `shortMsg=Process crashed` from the system after a
#    handful of classes. A crash is not a test result.
#
# Each class is therefore started by its own `am instrument`, and the app is force-stopped first so the
# instrumentation gets a fresh process and a fresh singleton.
#
# Usage: run-instrumented-classes.sh [serial] [ClassSubstring ...]
#   run-instrumented-classes.sh                          # every class, in order
#   run-instrumented-classes.sh emulator-5584 Stage8a    # one class
set -u
SERIAL=${1:-emulator-5584}
shift 2>/dev/null || true
FILTERS=("$@")
RUNNER=androidx.test.runner.AndroidJUnitRunner
PKG=com.taskplanner.test
TARGET=com.taskplanner
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/instrumented-full"
mkdir -p "$OUT"

# ---------------------------------------------------------------- the pre-flight
# A wrong device is the most expensive mistake this script can make: it would write rows into somebody else's
# app and report results from a handset nobody asked about.
AVD=$(adb -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')
API=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
if [ "$AVD" != "taskplanner_avd" ]; then
  echo "REFUSED: $SERIAL is '$AVD', not taskplanner_avd. This project's instrumented tests belong to one AVD." >&2
  exit 2
fi
if [ "$API" != "35" ]; then
  echo "REFUSED: $SERIAL reports API $API, not the 35 these tests were written against." >&2
  exit 2
fi
# Another Gradle build on this machine means a second client on the same handset, and this matrix is only
# evidence while it is the only one. Reported rather than refused by name, because an idle Gradle daemon is a
# JVM too and refusing on that would make the script unusable on the machine it was written for — but a
# `BUILD` line in the output of `jps`/the running task list means stop and come back.
JVM=$(tasklist //FI "IMAGENAME eq java.exe" 2>/dev/null | grep -c "java.exe" || true)
printf 'note: %s java.exe alive (1 = the Gradle daemon idling; more than that means a build is running)\n' "${JVM:-0}"

# ---------------------------------------------------------------- the classes, read off the source
# Discovered rather than listed, because a class added to the tree and not to this file is a class that never
# runs and nobody notices. The pattern is `*Test.kt` and not the `*InstrumentedTest.kt` it used to be: Stage 14
# added `CalendarLocaleTest` and `CsvLocaleTest`, which are on-device classes that do not carry the word
# "Instrumented" in their names, and the narrower pattern let the script report a clean twenty-class matrix
# while silently never running those two. A discovery rule that can miss a class is a list with the list's
# weakness and none of the list's visibility.
ALL=$(cd "$ROOT/app/src/androidTest/java" && find . -name '*Test.kt' \
      | sed 's|^\./||; s|\.kt$||; s|/|.|g' | sort)
CLASSES=""
for c in $ALL; do
  if [ ${#FILTERS[@]} -eq 0 ]; then CLASSES="$CLASSES $c"; continue; fi
  for f in "${FILTERS[@]}"; do
    case "$c" in *"$f"*) CLASSES="$CLASSES $c"; break ;; esac
  done
done
if [ -z "$CLASSES" ]; then echo "REFUSED: no class matched ${FILTERS[*]:-<none>}" >&2; exit 4; fi

printf '%s\n' "serial=$SERIAL avd=$AVD api=$API classes=$(echo $CLASSES | wc -w)"
echo "| Test class | Tests | Passed | Failed | Exit code | Log |"
echo "|---|---:|---:|---:|---:|---|"

for c in $CLASSES; do
  short=${c##*.}
  log="$OUT/$short.log"
  adb -s "$SERIAL" shell am force-stop "$TARGET" >/dev/null 2>&1
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1
  # `clearPackageData` is what Gradle's own connected-test task passes, and a raw `am instrument` does not get
  # it from `build.gradle.kts` — the argument lives in the build script, not in the runner. Without it the app
  # keeps the task the previous class left: a force-stop kills the process but not the record, so the next
  # launch restores the screen the last class ended on, the bottom bar restores its selection along with it,
  # and the first tab press of the next class is a re-selection that navigates nowhere. That is how
  # `Stage8aScreensInstrumentedTest` came to fail with "the tab did not draw its screen" only in a run that
  # followed the Stage 14 frame walk, whose last screen is a task detail behind a bar holding Profile.
  adb -s "$SERIAL" shell am instrument -w -e class "$c" -e clearPackageData true \
    "$PKG/$RUNNER" > "$log" 2>&1
  code=$?
  # `OK (N tests)` when all of them passed; `Tests run: N, Failures: F, Errors: E` when they did not.
  tests=$(grep -oE "OK \(([0-9]+) test" "$log" | grep -oE "[0-9]+" | head -1)
  failed=""
  if [ -z "$tests" ]; then
    summary=$(grep -oE "^Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" "$log" | head -1)
    if [ -n "$summary" ]; then
      tests=$(echo "$summary" | sed 's/Tests run: \([0-9]*\).*/\1/')
      f=$(echo "$summary" | sed 's/.*Failures: \([0-9]*\).*/\1/')
      e=$(echo "$summary" | sed 's/.*Errors: \([0-9]*\).*/\1/')
      failed=$((f + e))
    fi
  fi
  [ -z "$failed" ] && failed=0
  passed=$(( ${tests:-0} - failed ))
  verdict="NO VERDICT LINE"
  [ -n "$tests" ] && verdict="$tests tests, $failed failed"
  echo "| $short | ${tests:-?} | $passed | $failed | $code | build/instrumented-full/$short.log |"
  if [ "$code" -ne 0 ] || [ "$verdict" = "NO VERDICT LINE" ]; then
    printf '   %s :: tail: %s\n' "$short" "$(tail -3 "$log" | tr '\n' ' ' | cut -c1-300)" >&2
  fi
done
echo "=== per-class logs in $OUT ==="
