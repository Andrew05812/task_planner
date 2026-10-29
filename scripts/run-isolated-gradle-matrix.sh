#!/bin/bash
# One Gradle invocation per instrumented class, so every class gets its own instrumentation process.
#
# Why this file exists, in one line: a single `connectedDebugAndroidTest` over the whole suite is not a
# measurement of the app. On 2026-10-29 it ran 89 tests on taskplanner_avd and reported 35 failures, every one of
# them in a class that waits for rows *it* seeded — and 17 of the 18 classes pass when run on their own. The
# reason is in the fixture's own docstring (`SeededProfile.kt:15-18`): the app resolves `users.is_current` once
# into a Hilt singleton and keeps that answer for the life of the process, so in a shared process a class writes
# its rows under the profile the *first* class resolved and its screens then read a profile that the earlier
# class has already deleted. That is a property of the harness, not of the app, and the only honest way to read
# the app through it is one class per process.
#
# Each run is preceded by the pre-flight the device deserves: the serial, the AVD name, the API level, how much
# memory the host and the guest have left (this machine has twice turned a matrix into an ANR storm), and proof
# that no other client is holding the handset.
#
# Usage: run-isolated-gradle-matrix.sh [serial] [ClassSubstring ...]
set -u
SERIAL=${1:-emulator-5584}
shift 2>/dev/null || true
FILTERS=("$@")
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/instrumented-gradle"
RESULTS="$ROOT/app/build/outputs/androidTest-results/connected/debug"
mkdir -p "$OUT"
cd "$ROOT" || exit 1

CLASSES=$(cd app/src/androidTest/java && find . \( -name '*InstrumentedTest.kt' -o -name '*CleanupTest.kt' \) \
  | sed 's|^\./||; s|\.kt$||; s|/|.|g' | sort)
if [ ${#FILTERS[@]} -gt 0 ]; then
  MATCH=""
  for c in $CLASSES; do for f in "${FILTERS[@]}"; do case "$c" in *"$f"*) MATCH="$MATCH $c"; break ;; esac; done; done
  CLASSES=$MATCH
fi
[ -z "$CLASSES" ] && { echo "no class matched" >&2; exit 4; }
TOTAL=$(echo "$CLASSES" | wc -w)
echo "device serial=$SERIAL classes=$TOTAL"
echo "| Test class | Tests | Passed | Failed | Errors | Skipped | Exit code | Log |"
echo "|---|---:|---:|---:|---:|---:|---:|---|"

index=0
for c in $CLASSES; do
  index=$((index + 1))
  short=${c##*.}

  # ---- the pre-flight
  AVD=$(adb -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')
  API=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
  if [ "$AVD" != "taskplanner_avd" ] || [ "$API" != "35" ]; then
    echo "REFUSED: $SERIAL reports avd='$AVD' api='$API'; this project's instrumented runs belong to taskplanner_avd on API 35." >&2
    exit 2
  fi
  HOST_FREE=$(powershell.exe -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1KB)" 2>/dev/null | tr -d '\r')
  GUEST_FREE=$(adb -s "$SERIAL" shell cat /proc/meminfo 2>/dev/null | head -2 | tail -1 | tr -d '\r' | awk '{print $2}')
  JVM=$(tasklist //FI "IMAGENAME eq java.exe" 2>/dev/null | grep -c "java.exe" || true)
  printf '[%d/%d] %s — host free %s MB, guest free %s kB, java.exe alive %s\n' \
    "$index" "$TOTAL" "$short" "${HOST_FREE:-?}" "${GUEST_FREE:-?}" "${JVM:-?}"

  # The app is stopped between classes so the instrumentation gets a fresh process and a fresh singleton, and so
  # no window left standing by the previous class can be mistaken for the next one's defect.
  adb -s "$SERIAL" shell am force-stop com.taskplanner >/dev/null 2>&1
  adb -s "$SERIAL" shell am force-stop com.taskplanner.test >/dev/null 2>&1

  rm -f "$RESULTS"/*.xml 2>/dev/null
  log="$OUT/$short.log"
  # `--no-build-cache` is not a performance choice, and it is not decoration. A run of this matrix was once
  # interrupted by killing its Gradle JVM, and what that left in the project build cache made every later build
  # fail in `dexBuilderDebug` with "class … requires its nest host … to be on program or class path" — a broken
  # cache *row*, restored again over again, that no `clean` removed. Six classes of one matrix were reported as
  # compile failures because of it. Reading those as test results would have blamed the app for a cache entry.
  ./gradlew --no-daemon --no-build-cache --console=plain --offline connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$c" > "$log" 2>&1
  code=$?
  xml=$(find "$RESULTS" -maxdepth 1 -name 'TEST-*.xml' | head -1)
  tests="?"; pass="?"; fail="?"; err="?"; skip="?"
  if [ -n "$xml" ]; then
    read -r tests fail err skip < <(python - "$xml" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"[^>]*skipped="(\d+)"', s)
print(*(m.groups() if m else ('?', '0', '0', '0')))
PY
)
    [ "$tests" != "?" ] && pass=$((tests - ${fail:-0} - ${err:-0}))
    cp "$xml" "$OUT/$short.xml" 2>/dev/null
  fi
  echo "| $short | ${tests:-?} | ${pass:-?} | ${fail:-?} | ${err:-?} | ${skip:-?} | $code | build/instrumented-gradle/$short.log |"
  if [ "$code" -ne 0 ]; then
    printf '   !!! %s :: %s\n' "$short" "$(grep -aE "failed|FAILED|Error:|Exception" "$log" | tail -3 | tr '\n' ' ' | cut -c1-300)" >&2
  fi
done
echo "=== per-class logs and result XMLs in $OUT ==="
