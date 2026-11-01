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

# ---------------------------------------------------------------- the device's own health, printed
# A completion-pass matrix failed nine classes and blamed the app for all of them. It was the device: `uptime`
# read a load average of 19.05 on four cores while nothing of mine was running, `dumpsys window` read
# `mCurrentFocus=Window{… Application Not Responding: com.android.systemui}`, and dropbox had the reason —
# "Input dispatching timed out ([Gesture Monitor] edge-swipe (server) is not responding. Waited 12756ms for
# MotionEvent". A tap injected into that is a tap the system never delivers, and Espresso reports it as a
# screen that would not open. So the matrix prints what the device says about itself before it spends an hour
# on it, and the same reading after, and the table is read against those numbers.
LOAD=$(adb -s "$SERIAL" shell uptime 2>/dev/null | tr -d '\r' | sed 's/.*load average: //')
MEM=$(adb -s "$SERIAL" shell cat /proc/meminfo 2>/dev/null | tr -d '\r' | awk '/MemAvailable/{print $2}')
FOCUS=$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r' | sed 's/^ *//')
ANRS=$(adb -s "$SERIAL" shell dumpsys dropbox 2>/dev/null | grep -cE "_anr" || true)
printf 'device: load average %s, MemAvailable %s kB, %s ANR records in dropbox\n' "${LOAD:-?}" "${MEM:-?}" "${ANRS:-?}"
printf 'device: %s\n' "${FOCUS:-no window reported}"

# The rest of what a matrix run has to say about the machine it just measured. Each of these lines caught a
# different false failure in the completion pass: `sys.boot_completed` because a device still starting up
# answers `Process crashed`; the crash buffer because `DeadSystemException` across six processes is the system's
# news and not the app's; the System UI pid because an ANR'ing `com.android.systemui` holds input focus while
# the app's own process is perfectly alive; and the installed APK's hash because a matrix of a binary nobody
# built is not evidence about a build.
BOOT=$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
CRASH=$(adb -s "$SERIAL" logcat -b crash -d 2>/dev/null | grep -cE "FATAL EXCEPTION|DeadSystemException|Process crashed" || true)
SYSTEMUI=$(adb -s "$SERIAL" shell pidof com.android.systemui 2>/dev/null | tr -d '\r')
APPUID=$(adb -s "$SERIAL" shell dumpsys package "$TARGET" 2>/dev/null | tr -d '\r' | grep -m1 -oE 'userId=[0-9]+' | cut -d= -f2)
APPPID=$(adb -s "$SERIAL" shell pidof "$TARGET" 2>/dev/null | tr -d '\r')
HOST_FREE=$(powershell.exe -NoProfile -Command '$os=Get-CimInstance Win32_OperatingSystem; [int]($os.FreePhysicalMemory/1024)' 2>/dev/null | tr -d '\r' | tail -1)
printf 'device: boot_completed=%s systemui_pid=%s app_uid=%s app_pid=%s crash-buffer-lines=%s\n' \
  "${BOOT:-?}" "${SYSTEMUI:-absent}" "${APPUID:-?}" "${APPPID:-not running}" "${CRASH:-?}"
printf 'host:   free %s MB\n' "${HOST_FREE:-?}"

# Provenance, refused rather than noted. The frames matrix has had this check since the completion pass found
# its two halves came from two different builds; the instrumented matrix deserved the same guard, because the
# sentence "the suite is green on this build" is only worth printing if the build is named by its bytes.
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
DEV_PATH=$(adb -s "$SERIAL" shell pm path "$TARGET" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^package://')
DEV_SHA=$(adb -s "$SERIAL" shell sha256sum "$DEV_PATH" 2>/dev/null | tr -d '\r' | cut -d' ' -f1)
LOCAL_SHA=$(sha256sum "$APK" 2>/dev/null | cut -d' ' -f1)
printf 'apk:    installed %s\n' "${DEV_SHA:-none} at ${DEV_PATH:-nowhere}"
printf 'apk:    built     %s\n' "${LOCAL_SHA:-$APK missing}"
if [ -z "$DEV_PATH" ]; then
  echo "REFUSED: $TARGET is not installed on $SERIAL. Install the build the matrix is meant to describe." >&2
  exit 7
fi
if [ -n "$LOCAL_SHA" ] && [ "$LOCAL_SHA" != "$DEV_SHA" ]; then
  echo "REFUSED: the installed app is not $APK." >&2
  echo "  A class run against different bytes is not a result about this build — reinstall" >&2
  echo "  (adb -s $SERIAL install -r -g app/build/outputs/apk/debug/app-debug.apk) and run again." >&2
  exit 7
fi

case "$FOCUS" in
  *"Application Not Responding"*|*"not responding"*)
    echo "REFUSED: an error dialog holds the device's input focus. Every tap this run injects would land on it," >&2
    echo "  and the matrix would report the app's screens as broken. Clear the dialog (or reboot the AVD: 2-7 min" >&2
    echo "  to sys.boot_completed=1, then \`settings put global hide_error_dialogs 1\`) and run again." >&2
    exit 5
    ;;
esac

# Wait for the device to be quiet enough to measure something. The threshold is not a style: at load 15 on this
# four-core image, `am start -W com.taskplanner/.ui.MainActivity` reported `WaitTime: 17612` — seventeen and a
# half seconds to show a screen — and a class that was green at load ~10 an hour earlier (nine tests) failed six
# of its nine, including one that could not get an accessibility tree inside 20 s. Those are not the app's
# failures, and a matrix of them is not evidence. So the run waits up to ten minutes for the one-minute load to
# fall under WAIT_FOR_LOAD, and refuses with the numbers if it does not, rather than spending an hour producing
# results nobody can read. The default is 12, not 5: this image's one-minute load sits between 8 and 15 while
# the operator's own tools are working, and a threshold that demands an idle machine turns the gate into a
# refusal. 20-40 — the numbers this run actually met — is the state that produces fake failures.
WAIT_FOR_LOAD=${WAIT_FOR_LOAD:-12}
for attempt in $(seq 1 20); do
  LOAD1=$(adb -s "$SERIAL" shell uptime 2>/dev/null | tr -d '\r' | sed 's/.*load average: //' | cut -d, -f1)
  if awk -v l="${LOAD1:-99}" -v m="$WAIT_FOR_LOAD" "BEGIN{exit !(l+0<=m+0)}"; then
    printf 'device: load average %s is at or under %s — running\n' "${LOAD1:-?}" "$WAIT_FOR_LOAD"
    break
  fi
  printf 'device: load average %s over %s, waiting 30 s (%s/20)\n' "${LOAD1:-?}" "$WAIT_FOR_LOAD" "$attempt"
  sleep 30
done
LOAD1=$(adb -s "$SERIAL" shell uptime 2>/dev/null | tr -d '\r' | sed 's/.*load average: //' | cut -d, -f1)
if ! awk -v l="${LOAD1:-99}" -v m="$WAIT_FOR_LOAD" "BEGIN{exit !(l+0<=m+0)}"; then
  echo "REFUSED: after ten minutes the device still reports load average $LOAD1 on $(adb -s "$SERIAL" shell cat /proc/cpuinfo 2>/dev/null | grep -c processor) cores." >&2
  echo "  A matrix run into that measures the machine: taps time out, screenshots catch a screen mid-animation," >&2
  echo "  and classes that passed an hour ago fail. Look at what is busy on the device (adb shell top) and on the" >&2
  echo "  host, let it settle, and run again. Nothing was cleared and no process was killed to get here." >&2
  exit 6
fi

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
# Four verdicts, not two. The completion pass' table printed a class that produced no verdict line at all as
# `? tests, 0 failed`, which a reader can only take as "nothing went wrong" — while the log behind it said
# `Process crashed`. A missing result is not a pass, and a machine that died is not an app that failed, so the
# row now says which of the four it was.
echo "| Test class | Tests | Passed | Failed | Verdict | Exit | APK (installed) | Log |"
echo "|---|---:|---:|---:|---|---:|---|---|"
PASS_N=0; FAIL_N=0; ENV_N=0; ATTEMPTED=0

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
    summary=$(grep -oE "^Tests run: [0-9]+,[[:space:]]+Failures: [0-9]+(,[[:space:]]+Errors: [0-9]+)?" "$log" | head -1)
    if [ -n "$summary" ]; then
      tests=$(echo "$summary" | sed 's/Tests run: \([0-9]*\).*/\1/')
      f=$(echo "$summary" | sed 's/.*Failures: \([0-9]*\).*/\1/')
      e=$(echo "$summary" | sed -n 's/.*Errors: \([0-9]*\).*/\1/p'); [ -z "$e" ] && e=0
      failed=$((f + e))
    fi
  fi
  [ -z "$failed" ] && failed=0
  passed=$(( ${tests:-0} - failed ))
  ATTEMPTED=$((ATTEMPTED + 1))
  # A verdict line outranks a crash: a class that reported its failures and then died during teardown reported
  # real results. Only a run with no verdict line at all can be the machine's story rather than the app's.
  if [ -n "$tests" ] && [ "$failed" -gt 0 ]; then
    kind=FAIL; FAIL_N=$((FAIL_N + 1)); verdict="FAIL — $failed of $tests failed"
  elif [ -n "$tests" ]; then
    kind=PASS; PASS_N=$((PASS_N + 1)); verdict="PASS — $tests of $tests"
  elif grep -qaE "Process crashed|INSTRUMENTATION_FAILED|Unable to find instrumentation info|SecurityException|adb: device (not found|offline)" "$log"; then
    kind=ENVIRONMENT; ENV_N=$((ENV_N + 1))
    verdict="ENVIRONMENT — $(grep -aoE 'Process crashed|INSTRUMENTATION_FAILED[^\"]{0,60}|Unable to find instrumentation info[^\"]{0,60}|SecurityException[^\"]{0,60}' "$log" | head -1)"
  else
    kind=ENVIRONMENT; ENV_N=$((ENV_N + 1)); verdict="ENVIRONMENT — no verdict line (exit $code)"
  fi
  echo "| $short | ${tests:-?} | $passed | $failed | $kind | $code | ${DEV_SHA:0:12}… | build/instrumented-full/$short.log |"
  if [ "$kind" != PASS ]; then
    printf '   %s :: %s :: tail: %s\n' "$short" "$verdict" "$(tail -3 "$log" | tr '\n' ' ' | cut -c1-300)" >&2
  fi
done
DISCOVERED=$(echo $CLASSES | wc -w)
printf '=== summary: discovered=%s attempted=%s PASS=%s FAIL=%s ENVIRONMENT=%s NOT-RUN=%s\n' \
  "$DISCOVERED" "$ATTEMPTED" "$PASS_N" "$FAIL_N" "$ENV_N" "$((DISCOVERED - ATTEMPTED))"
echo "=== per-class logs in $OUT ==="
# The same reading as the pre-flight, taken after the work, because a run that starts healthy and degrades is
# exactly the run whose failures must not be read as defects. dropbox timestamps are the device's own clock,
# which on this emulator runs three hours behind the machine that drives it.
printf 'device after: load average %s, %s ANR records in dropbox\n' \
  "$(adb -s "$SERIAL" shell uptime 2>/dev/null | tr -d '\r' | sed 's/.*load average: //')" \
  "$(adb -s "$SERIAL" shell dumpsys dropbox 2>/dev/null | grep -cE '_anr' || true)"
