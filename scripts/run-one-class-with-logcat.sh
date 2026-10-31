#!/bin/bash
# One instrumented class, with logcat captured beside it, so an expired UI wait can be read for *where the app
# actually was* rather than argued about.
#
# Why: three classes in the Stage 14 completion matrix failed with a message that says only "the screen did not
# happen within N ms". None of them said which screen the app was on instead. The per-class log from
# `am instrument` cannot answer that — it holds the JUnit text and nothing else — while the system log holds
# every activity start, every `Displayed` line, every ANR and every window-focus change. Capturing it for the
# duration of one class is what turns "the tab press was lost" from a theory into something readable.
#
# Usage: run-one-class-with-logcat.sh <serial> <Class fully-qualified or substring> [extra -e args...]
#   run-one-class-with-logcat.sh emulator-5584 ReportScreenInstrumentedTest
set -u
SERIAL=${1:?serial, e.g. emulator-5584}
CLASS=${2:?class name}
shift 2
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/instrumented-probe"
mkdir -p "$OUT"
STAMP=$(date +%H%M%S)
RUNNER=androidx.test.runner.AndroidJUnitRunner
PKG=com.taskplanner.test
TARGET=com.taskplanner

AVD=$(adb -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')
if [ "$AVD" != "taskplanner_avd" ]; then
  echo "REFUSED: $SERIAL is '$AVD', not taskplanner_avd." >&2
  exit 2
fi

# Resolve a substring to the fully-qualified class the way the matrix script does, so `-e class` gets one name
# and an ambiguous substring fails here rather than silently running nothing.
ALL=$(cd "$ROOT/app/src/androidTest/java" && find . -name '*Test.kt' \
      | sed 's|^\./||; s|\.kt$||; s|/|.|g' | sort)
MATCHES=""
for c in $ALL; do
  short=${c##*.}
  case "$short" in *"$CLASS"*) MATCHES="$MATCHES $c" ;; esac
done
COUNT=$(echo $MATCHES | wc -w)
if [ "$COUNT" -ne 1 ]; then
  echo "REFUSED: '$CLASS' matched $COUNT classes:$MATCHES" >&2
  exit 4
fi
FULL=$(echo $MATCHES)

log="$OUT/$FULL.$STAMP.log"
catlog="$OUT/$FULL.$STAMP.logcat.txt"
adb -s "$SERIAL" logcat -c >/dev/null 2>&1
adb -s "$SERIAL" shell am force-stop "$TARGET" >/dev/null 2>&1
adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1

adb -s "$SERIAL" logcat -v time > "$catlog" 2>&1 &
LOGGER=$!
sleep 1
adb -s "$SERIAL" shell am instrument -w -e class "$FULL" -e clearPackageData true "$@" \
  "$PKG/$RUNNER" > "$log" 2>&1
code=$?
sleep 2
kill $LOGGER >/dev/null 2>&1

echo "exit=$code class=$FULL"
echo "instrument: $log"
echo "logcat:     $catlog"
tail -6 "$log"
echo "--- the system's side of the same minute ---"
grep -E "Displayed|ActivityTaskManager|ANR |FATAL EXCEPTION|window focus|Config changing|LocaleManager|am_proc_died|lowmemory" \
  "$catlog" | tail -40
