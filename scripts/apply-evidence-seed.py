#!/usr/bin/env python3
"""Apply the UI-evidence fixture to the Task Planner database on a connected device.

Why a script and not a one-liner: every `connectedDebugAndroidTest` invocation wipes this app's data
(`clearPackageData` in `app/build.gradle.kts:29`), and AGP uninstalls the app afterwards. Without a
re-appliable fixture the before/after matrix could be photographed exactly once, which is not evidence.

Reads `scripts/evidence-seed.sql`, pushes it, runs it through the device's own `sqlite3` as the app's
user (`run-as`, which works because this is a debuggable build), and prints the row counts the fixture
claims. It never deletes a row the fixture did not create — see the header of the SQL file.

Usage:  python scripts/apply-evidence-seed.py [-s emulator-5554] [--profile-created]
"""

import argparse
import os
import subprocess
import sys

PACKAGE = "com.taskplanner"
DB = "databases/taskplanner.db"
HERE = os.path.dirname(os.path.abspath(__file__))
SQL = os.path.join(HERE, "evidence-seed.sql")
REMOTE = "/data/local/tmp/evidence-seed.sql"


def adb(serial, *args, check=True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    result = subprocess.run(cmd, capture_output=True, text=True)
    out = ((result.stdout or "") + (result.stderr or "")).strip()
    if check and result.returncode != 0:
        sys.exit("adb failed: %s\n%s" % (" ".join(cmd), out))
    return out, result.returncode


def run_sqlite(serial, statement_file):
    """Run the script as the app's own user; fall back to a rooted adbd if `run-as` is refused."""
    out, code = adb(
        serial, "shell",
        "run-as %s sqlite3 %s < %s" % (PACKAGE, DB, statement_file), check=False
    )
    if code == 0:
        return out
    # Root path: same file, absolute path, only on a debuggable system image.
    adb(serial, "root", check=False)
    out, code = adb(serial, "shell",
                    "sqlite3 /data/data/%s/%s < %s" % (PACKAGE, DB, statement_file), check=False)
    if code != 0:
        sys.exit(
            "Could not run the fixture (exit %d). Neither `run-as %s` nor a rooted adbd reached the "
            "database — is the app installed, and is this a debuggable image?\n%s" % (code, PACKAGE, out)
        )
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    args = parser.parse_args()
    serial = args.serial or None

    if not os.path.isfile(SQL):
        sys.exit("missing %s" % SQL)

    installed, _ = adb(serial, "shell", "pm", "list", "packages", check=False)
    if PACKAGE not in installed:
        sys.exit(
            "%s is not installed. Install the APK first (`adb install -r "
            "app/build/outputs/apk/debug/app-debug.apk`) and open it once, so Room creates the v16 "
            "database this fixture writes into." % PACKAGE
        )

    adb(serial, "shell", "am", "force-stop", PACKAGE, check=False)
    adb(serial, "push", SQL, REMOTE)
    print(run_sqlite(serial, REMOTE))
    adb(serial, "shell", "rm", REMOTE, check=False)

    profile, _ = adb(
        serial, "shell",
        "run-as %s sqlite3 %s 'select id || \" / \" || name || \" / current=\" || is_current "
        "from users where is_current = 1 limit 1'" % (PACKAGE, DB),
        check=False
    )
    print("\nProfile the fixture bound to: %s" % (profile.strip() or "NONE — nothing was planted"))
    print("Relaunch the app to see it.")


if __name__ == "__main__":
    main()
