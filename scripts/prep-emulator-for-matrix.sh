#!/bin/bash
# Put this project's emulator into a state an instrumented matrix can be trusted in, and say what it found.
#
# Why this exists, measured: the Stage 14 completion matrix failed nine classes and every failure message
# described the app refusing to open a screen. The app was fine. The device read `load average: 19.05, 17.30,
# 14.58` on four cores while nothing of mine was running, `mCurrentFocus=Window{… Application Not Responding:
# com.android.systemui}`, and dropbox's own words were "Input dispatching timed out ([Gesture Monitor]
# edge-swipe (server) is not responding. Waited 12756ms for MotionEvent" plus a run of
# `… failed to complete startup` ANRs for Google background processes — which the system then kills, restarts,
# and ANRs again. A fresh reboot did not fix it: twenty minutes later the same device read load 43 and an
# `am instrument` that died with `shortMsg=Process crashed`. A matrix run on that is not evidence about a UI.
#
# What this script does, and does not do:
#   * it stops the system's error windows from taking input focus mid-run
#     (`settings put global hide_error_dialogs 1`), which is what an ANR dialog of *somebody else's* process
#     should be allowed to do to this project's taps: nothing. It does not hide a crash of the app under test —
#     `am instrument` still reports `Process crashed`, and ANRs still land in dropbox, which is what the
#     reading at the end of this script counts.
#   * it disables four Google background packages that Task Planner's instrumented tests never touch — the
#     search box, Digital Wellbeing, Settings Intelligence and the on-device AI service. Sign-in is not among
#     them: `com.google.android.gms` is left exactly as it is, because Stage 13's account code is under test
#     even though Google Sign-In itself is blocked on configuration. Disabling a package is reversible with
#     `pm enable`, and it is done here on this project's own AVD, by name, after the AVD has been verified.
#   * it refuses to touch a device that is not `taskplanner_avd`, and it never clears data or kills a process
#     that belongs to something else.
#
# Usage: prep-emulator-for-matrix.sh [serial] [--reboot]
set -u
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
SERIAL=${1:-emulator-5584}
REBOOT=${2:-}
TARGET=com.taskplanner
# The stale-directory check below reads this app's external files directory. Without TARGET set it ran against
# `/storage/emulated/0/Android/data/` — the parent of every app's directory — compared a root owner to an empty
# uid, printed the "agrees" line, and did nothing at all. The EACCES guard this script documents was therefore
# inert on every run until now, which is how a script passes while proving nothing.
CHURN=(
  com.google.android.apps.wellbeing
  com.google.android.googlequicksearchbox
  com.android.settings.intelligence
  com.google.android.as
)

AVD=$(adb -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')
API=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
if [ "$AVD" != "taskplanner_avd" ]; then
  echo "REFUSED: $SERIAL is '$AVD', not taskplanner_avd. This project's device prep belongs to one AVD." >&2
  exit 2
fi
echo "device: $SERIAL is $AVD on API $API"
echo "before: $(adb -s "$SERIAL" shell uptime 2>/dev/null | tr -d '\r' | sed 's/load average/load/') "
echo "before: $(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r' | sed 's/^ *//')"
echo "before: ANR records in dropbox: $(adb -s "$SERIAL" shell dumpsys dropbox 2>/dev/null | grep -cE '_anr')"

adb -s "$SERIAL" shell settings put global hide_error_dialogs 1 >/dev/null
for pkg in "${CHURN[@]}"; do
  state=$(adb -s "$SERIAL" shell pm list packages -d 2>/dev/null | tr -d '\r' | grep -c "package:$pkg\$")
  if [ "$state" = "0" ]; then
    echo "disable: $pkg  -> $(adb -s "$SERIAL" shell pm disable-user --user 0 "$pkg" 2>&1 | tr -d '\r' | tail -1)"
  else
    echo "already disabled: $pkg"
  fi
done

if [ "$REBOOT" = "--reboot" ]; then
  echo "reboot: issued $(date +%H:%M:%S)"
  adb -s "$SERIAL" reboot
  adb -s "$SERIAL" wait-for-device >/dev/null 2>&1
  for _ in $(seq 1 60); do
    [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 10
  done
  echo "reboot: boot_completed=$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') at $(date +%H:%M:%S)"
fi

# A stale external files directory owned by a *previous* install of the app imitates an app defect exactly:
# `/storage/emulated/0/Android/data/com.taskplanner` was left behind by the old uid after an uninstall and
# reinstall, and `ReportPdfBytesInstrumentedTest` died with `open failed: EACCES (Permission denied)` writing
# its own proof file inside it — a directory the app owns by definition. The owner and the current uid are
# compared, and a directory that belongs to somebody who is no longer installed is removed. This deletes only
# the app's own external scratch area on this project's AVD: proof PDFs and the frame shots, both regenerated
# by the runs that write them.
DIR=/storage/emulated/0/Android/data/$TARGET
OWNER=$(adb -s "$SERIAL" shell stat -c %U "$DIR" 2>/dev/null | tr -d '')
# Not `UID`: bash keeps that name as a readonly variable, so the assignment failed with
# `UID: readonly variable`, the comparison below ran against the *host’s* uid instead of the app’s,
# and the script deleted a directory that did agree with the app. A check whose variable cannot be set is
# worse than no check at all, because it acts.
APP_UID=$(adb -s "$SERIAL" shell dumpsys package "$TARGET" 2>/dev/null | tr -d '' | grep -m1 -oE 'userId=[0-9]+' | cut -d= -f2)
if [ -n "$OWNER" ] && [ -n "$APP_UID" ] && [ "$OWNER" != "u${APP_UID}" ] && [ "$OWNER" != "root" ]; then
  echo "stale: $DIR is owned by $OWNER, the app is u$APP_UID — removing it so the app recreates its own"
  adb -s "$SERIAL" shell am force-stop "$TARGET" >/dev/null 2>&1
  adb -s "$SERIAL" shell rm -rf "$DIR" >/dev/null 2>&1
else
  echo "external files dir: ${OWNER:-absent} agrees with the app's uid ${APP_UID:-?}"
fi

# The screen has to be awake and unlocked for a tap to reach the app, and after a reboot the keyguard is up.
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1
echo "waiting 90 s for the device to settle before it is judged…"
sleep 90
echo "after:  $(adb -s "$SERIAL" shell uptime 2>/dev/null | tr -d '\r' | sed 's/load average/load/')"
echo "after:  $(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r' | sed 's/^ *//')"
echo "after:  hide_error_dialogs=$(adb -s "$SERIAL" shell settings get global hide_error_dialogs | tr -d '\r')"
echo "after:  ANR records in dropbox: $(adb -s "$SERIAL" shell dumpsys dropbox 2>/dev/null | grep -cE '_anr')"
echo "top:    $(adb -s "$SERIAL" shell top -b -n 1 2>/dev/null | sed -n '4p' | tr -d '\r')"
echo "top:    $(adb -s "$SERIAL" shell top -b -n 1 2>/dev/null | sed -n '5,7p' | tr -d '\r' | awk '{printf "%s%% %s | ", $2, $NF}')"
