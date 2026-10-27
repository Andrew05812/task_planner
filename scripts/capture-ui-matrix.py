#!/usr/bin/env python3
"""Photograph the Task Planner UI in every configuration the redesign is judged in.

Why a script: the same matrix has to exist twice — once before the change (`before/`) and once after
(`after/`) — and a pair is only evidence if both halves were taken in the same configuration, against the
same data, through the same path. Doing that by hand twice is how a baseline quietly stops matching.

Every navigation step resolves its target from a live `uiautomator` dump by resource-id or visible text
rather than from a hard-coded coordinate, so the same script works at 320dp and at 393dp and at a font
scale of 1.3. Each photograph is recorded in `manifest.md` next to the exact `wm size` / `wm density` /
`font_scale` / `uimode` quadruple and the row count the database held at that moment.

Usage:
    python scripts/capture-ui-matrix.py --out docs/images/ui-redesign/before
    python scripts/capture-ui-matrix.py --out docs/images/ui-redesign/after --only 01-tasks,27-swipe-open
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import time

PACKAGE = "com.taskplanner"
DB = "databases/taskplanner.db"


# ------------------------------------------------------------------ adb plumbing
def adb(serial, *args, binary=False):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        sys.exit("adb failed: %s\n%s" % (" ".join(cmd), result.stderr.decode("utf-8", "replace")))
    return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()


# ------------------------------------------------------------------ the hierarchy
def nodes(serial):
    """The live hierarchy. `uiautomator dump` fails outright while a window is animating, so this retries
    rather than treating one refused dump as a missing view."""
    for _ in range(6):
        try:
            adb(serial, "shell", "uiautomator dump /sdcard/matrix-ui.xml")
            xml = adb(serial, "exec-out", "cat /sdcard/matrix-ui.xml")
            break
        except SystemExit:
            time.sleep(1.2)
    else:
        return []
    out = []
    for match in re.finditer(r"<node[^>]*?/?>", xml):
        tag = match.group(0)

        def field(name):
            found = re.search(name + r'="([^"]*)"', tag)
            return found.group(1) if found else ""

        bounds = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", field("bounds"))
        if not bounds:
            continue
        left, top, right, bottom = (int(g) for g in bounds.groups())
        out.append({
            "id": field("resource-id").split("/")[-1],
            "text": field("text"),
            "class": field("class"),
            "selected": field("selected"),
            "box": (left, top, right, bottom),
            "center": ((left + right) // 2, (top + bottom) // 2),
        })
    return out


def wait_for_any_id(serial, ids, tries=20):
    """Poll until one of several ids is on screen — the way to wait for a screen that has more than one
    legitimate state (Profile with a profile, and Profile asking for one)."""
    for _ in range(tries):
        present = {n["id"] for n in nodes(serial)}
        for wanted in ids:
            if wanted in present:
                return wanted
        time.sleep(0.7)
    return None


def wait_for(serial, key, value, tries=20):
    """Poll for a view rather than sleeping for a guess: the emulator is slow and the wait varies."""
    for _ in range(tries):
        found = [n for n in nodes(serial) if n[key] == value and n[key]]
        if found:
            return found[0]
        time.sleep(0.7)
    return None


def tap_id(serial, resource_id):
    node = wait_for(serial, "id", resource_id)
    if not node:
        raise SystemExit("no view with id %s on screen. Present: %s"
                         % (resource_id, ", ".join(sorted({n["id"] or n["text"] for n in nodes(serial)
                                                           if n["id"] or n["text"]})[:40])))
    x, y = node["center"]
    adb(serial, "shell", "input tap %d %d" % (x, y))


def tap_text(serial, text):
    for _ in range(20):
        found = [n for n in nodes(serial) if n["text"].upper() == text.upper()]
        if found:
            x, y = found[0]["center"]
            adb(serial, "shell", "input tap %d %d" % (x, y))
            return
        time.sleep(0.7)
    raise SystemExit("no view with text %r on screen. Present: %s"
                     % (text, ", ".join(sorted({n["id"] or n["text"] for n in nodes(serial)
                                                if n["id"] or n["text"]})[:40])))


def recycler_row(serial, index=0):
    """The centre of one of the task rows, read from the live list rather than assumed.

    `item_task.xml:38` names the card `taskCard`, so the rows are found by their own id rather than by a
    position that would shift when a group header appears or the filter changes.
    """
    for _ in range(20):
        rows = [n for n in nodes(serial) if n["id"] == "taskCard"]
        if len(rows) > index:
            return rows[index]
        time.sleep(0.7)
    return None


# ------------------------------------------------------------------ configuration
def selected_tab(serial):
    """Which bottom-nav item the app believes is current.

    `BottomNavigationView` shows the selected item's large label and hides the others, so the visible
    label names the tab — and reading it is the only way to tell a tap that missed from a tap that
    landed on a screen that is still drawing.
    """
    for node in nodes(serial):
        if node["id"].startswith("navigation_") and node["id"] != "navigation_bar_item_icon_container":
            if node["selected"] == "true" and re.match(r"navigation_(tasks|calendar|categories|statistics|profile)$",
                                                       node["id"]):
                return node["id"].split("_")[1]
    return None


# What each tab's own screen puts on the window. Selecting a tab and the fragment under it finishing its
# first frame are two moments, and a screenshot taken between them is the previous screen.
TAB_MARKERS = {
    "tasks": ("recyclerView",),
    "profile": ("openGoalsButton", "noUserGroup"),
    "statistics": ("statisticsPeriodScroll",),
    "categories": ("recyclerViewCategories", "fabAddCategory"),
    "calendar": ("calendarTitle",),
}

TAP_ATTEMPTS = 4


def go_tab(serial, tab):
    """Tap a bottom-nav tab and do not move on until the app agrees it is the current one.

    Measured on this emulator: a tap injected seconds after a launch is sometimes swallowed — the same
    coordinate, tapped again, switches. A screenshot of the wrong screen would look exactly like a
    screenshot of the right one, so this retries and then fails loudly rather than writing the file.
    """
    for _ in range(TAP_ATTEMPTS):
        tap_id(serial, "navigation_%s" % tab)
        time.sleep(1)
        if selected_tab(serial) != tab:
            continue
        if wait_for_any_id(serial, TAB_MARKERS[tab], tries=12):
            return
    raise SystemExit("tapping navigation_%s never brought up its screen (selected: %r)"
                     % (tab, selected_tab(serial)))


def physical_width(serial):
    line = adb(serial, "shell", "wm size")
    return int(re.search(r"(\d+)x\d+", line).group(1))


def set_dp(serial, dp):
    px = physical_width(serial)
    density = int(round(px * 160.0 / dp))
    adb(serial, "shell", "wm density %d" % density)
    return density


def set_font(serial, scale):
    adb(serial, "shell", "settings put system font_scale %s" % scale)


def set_night(serial, night):
    adb(serial, "shell", "cmd uimode night %s" % ("yes" if night else "no"))


def restart(serial):
    adb(serial, "shell", "am force-stop", PACKAGE)
    time.sleep(1)
    adb(serial, "shell", "monkey -p %s -c android.intent.category.LAUNCHER 1" % PACKAGE)
    time.sleep(6)


def focused(serial):
    return adb(serial, "shell", "dumpsys window | grep -i mCurrentFocus")


def ensure_foreground(serial, tries=20):
    """Refuse to photograph the wrong app, or nothing.

    `mCurrentFocus` reads `null` for a moment during every activity switch and while the screen is
    dimming, so this polls instead of sampling once; only a window that stays wrong for the whole budget
    is the failure worth stopping for.
    """
    for attempt in range(2):
        for _ in range(tries):
            if PACKAGE in focused(serial):
                return
            time.sleep(0.8)
        restart(serial)
    raise SystemExit("%s never became the focused window: %s" % (PACKAGE, focused(serial).strip()))


def row_count(serial):
    out = adb(serial, "shell",
              "run-as %s sqlite3 %s \"select count(*) from tasks where archived=0 and is_deleted=0\""
              % (PACKAGE, DB), binary=False)
    return out.strip() or "?"


# ------------------------------------------------------------------ the screens
def go_tasks(serial):
    go_tab(serial, "tasks")
    wait_for(serial, "id", "recyclerView")


def go(serial, tab):
    go_tab(serial, tab)


def from_profile(serial, button_text):
    """Open a Profile entry point and wait until the screen it names has actually replaced Profile.

    The button's own text is the arrival test: once the destination is up, the Profile list is gone from
    the hierarchy. Retrying matters because a swallowed tap would otherwise photograph Profile and be
    filed under, say, `08-goals`.
    """
    go_tab(serial, "profile")
    # The tab reports itself selected a moment before the fragment under it has drawn. Waiting for the
    # screen rather than for the tap is what stops a swallowed swap being reported as a missing button.
    if not wait_for_any_id(serial, ("openGoalsButton", "noUserGroup"), tries=12):
        raise SystemExit("the Profile screen never drew after selecting its tab")
    for _ in range(TAP_ATTEMPTS):
        tap_text(serial, button_text)
        # The arrival test is the *screen* leaving, not the button's caption disappearing: the Reports
        # destination prints "Reports" as its own heading, and a check on the caption reads that as
        # "still on Profile" and taps a second time on a screen that has no such button.
        for _ in range(12):
            if not wait_for_any_id(serial, ("openGoalsButton",), tries=1):
                return
            time.sleep(0.7)
    raise SystemExit("tapping %r never left the Profile screen" % button_text)


def open_editor(serial):
    """The `+` button, tapped until the editor answers."""
    go_tab(serial, "tasks")
    for _ in range(TAP_ATTEMPTS):
        tap_id(serial, "fabAddTask")
        time.sleep(1.5)
        if wait_for(serial, "id", "task_title", tries=4):
            return
    raise SystemExit("the + button never opened the editor")


SHOTS = [
    # file, human screen name, theme, dp, font, navigation
    ("01-tasks", "Tasks, list with data", "light", 393, "1.0", go_tasks),
    ("02-profile", "Profile", "light", 393, "1.0", lambda s: go(s, "profile")),
    ("03-statistics", "Statistics", "light", 393, "1.0", lambda s: go(s, "statistics")),
    ("04-categories", "Categories", "light", 393, "1.0", lambda s: go(s, "categories")),
    ("05-calendar", "Calendar", "light", 393, "1.0", lambda s: go(s, "calendar")),
    ("06-task-editor", "Editor, opened from +", "light", 393, "1.0",
     lambda s: open_editor(s)),
    ("07-insights", "Insights", "light", 393, "1.0", lambda s: from_profile(s, "INSIGHTS")),
    ("08-goals", "Goals", "light", 393, "1.0", lambda s: from_profile(s, "GOALS")),
    ("09-habits", "Habits", "light", 393, "1.0", lambda s: from_profile(s, "HABITS")),
    ("10-reports", "Reports", "light", 393, "1.0", lambda s: from_profile(s, "REPORTS")),
    ("13-settings", "Settings", "light", 393, "1.0", lambda s: from_profile(s, "SETTINGS")),
    ("14-profile-selection", "Profile selection", "light", 393, "1.0",
     lambda s: from_profile(s, "Switch Profile")),
    # The three focus screens, in the other configurations the acceptance list names.
    ("15-tasks-dark", "Tasks", "dark", 393, "1.0", go_tasks),
    ("16-profile-dark", "Profile", "dark", 393, "1.0", lambda s: go(s, "profile")),
    ("17-editor-dark", "Editor", "dark", 393, "1.0",
     lambda s: open_editor(s)),
    ("18-tasks-320", "Tasks", "light", 320, "1.0", go_tasks),
    ("19-profile-320", "Profile", "light", 320, "1.0", lambda s: go(s, "profile")),
    ("20-editor-320", "Editor", "light", 320, "1.0",
     lambda s: open_editor(s)),
    ("21-tasks-360", "Tasks", "light", 360, "1.0", go_tasks),
    ("22-profile-360", "Profile", "light", 360, "1.0", lambda s: go(s, "profile")),
    ("23-editor-360", "Editor", "light", 360, "1.0",
     lambda s: open_editor(s)),
    ("24-tasks-font13", "Tasks", "light", 393, "1.3", go_tasks),
    ("25-profile-font13", "Profile", "light", 393, "1.3", lambda s: go(s, "profile")),
    ("26-editor-font13", "Editor", "light", 393, "1.3",
     lambda s: open_editor(s)),
]


def swipe_open_row(serial):
    """Drag a card left until the action panel under it is what the photograph shows.

    The arrival test is the card's own left edge: `item_task.xml` slides the card over the panel, so an
    open row is a row whose bounds moved right. A swipe that was swallowed would otherwise be filed as
    `27-swipe-open` while showing a closed list, which is the exact defect the shot exists to document.
    """
    go_tab(serial, "tasks")
    for _ in range(TAP_ATTEMPTS):
        row = recycler_row(serial, 1)
        if not row:
            raise SystemExit("no card to swipe open")
        left, _top, right, _bottom = row["box"]
        y = row["center"][1]
        # Stay away from the screen edges: a drag that starts within ~20px of the right edge is the system
        # back gesture, and in an earlier run it backgrounded the app and photographed whatever was behind.
        inset = max(40, (right - left) // 8)
        adb(serial, "shell", "input swipe %d %d %d %d 400" % (right - inset, y, left + inset, y))
        time.sleep(1)
        after = recycler_row(serial, 1)
        if after and after["box"][0] > left + 20:
            return
    raise SystemExit("the swipe never opened the action panel")


def open_selection(serial):
    """Enter selection mode the way the row offers it — and accept that on this emulator the two
    gestures are inverted by latency.

    A 1200 ms hold delivers DOWN…UP with no slop-crossing MOVE, which the row's own `ACTION_UP` branch
    reads as a click; a *short* tap, on this slow image, has its `postDelayed(longPressRunnable, 400)`
    fire before the UP is delivered, so the tap is what enters selection mode here. Both are tried, in
    the order a user would expect, and the arrival test is the selection bar becoming visible.
    """
    go_tab(serial, "tasks")
    for _ in range(TAP_ATTEMPTS):
        row = recycler_row(serial, 1)
        if not row:
            raise SystemExit("no card to select")
        x, y = row["center"]
        for gesture in ("input tap %d %d" % (x, y), "input swipe %d %d %d %d 1200" % (x, y, x, y)):
            adb(serial, "shell", gesture)
            time.sleep(1.2)
            # A GONE view is absent from the accessibility tree, so "the node exists" *is* "the bar is
            # visible" — this dump format carries no visibility attribute to read instead.
            if any(n["id"] == "selectionBar" for n in nodes(serial)):
                return
    raise SystemExit("neither a tap nor a hold entered selection mode")


# 27-swipe-open is not in this list. Six injected horizontal drags of 150/200/250/300/400/600/900 ms,
# kept inside the card and away from both screen edges, never moved `taskCard` by a pixel — while a
# vertical drag from the same tool scrolled the list, so the events arrive. The photograph is therefore
# recorded as blocked (see docs/UI_UX_AUDIT.md §5) rather than faked, and stage 2 has to demonstrate the
# reveal for real, which is the measurement that says whether this is the emulator or the row's
# `OnTouchListener`.
EXTRA = [
    ("28-selection-mode", "selection mode", "light", 393, "1.0", open_selection),
]


# ---------------------------------------------------------------- the editor's frames (Stage 6)
# The editor's frames are not screencaps this script takes. They are photographs the instrumented class
# writes from inside the app, because only the test knows which view is on the screen at the moment the
# shutter opens, and the class refuses to write a file whose name it cannot prove: `-e tag 320-light-10`
# is checked against the device's own `displayMetrics`, font scale and night mode, and a mismatch fails
# the test rather than the file name. What this script owns is the other half — applying the configuration,
# reading it back, and running the classes that read it. Without the command in the repository the matrix
# is something a person typed into a shell once, which is not a thing the next stage can repeat.
EDITOR_MATRIX = [
    # (tag, width dp, font scale, night) — the tag is what the class is handed, the rest is what the
    # device is set to. The three widths are reached by density alone on this 540 px panel: 540 px at
    # 270 dpi is 320 dp, at 240 dpi 360 dp, at 220 dpi 393 dp. Twelve configurations, not a sample: the
    # brief's matrix is widths x font x theme, and a frame refused at 320dp/1.3/dark is a defect this
    # stage owns, so leaving one of the twelve unphotographed would be leaving it unmeasured.
    ("320-light-10", 320, "1.0", False),
    ("320-light-13", 320, "1.3", False),
    ("320-dark-10", 320, "1.0", True),
    ("320-dark-13", 320, "1.3", True),
    ("360-light-10", 360, "1.0", False),
    ("360-light-13", 360, "1.3", False),
    ("360-dark-10", 360, "1.0", True),
    ("360-dark-13", 360, "1.3", True),
    ("393-light-10", 393, "1.0", False),
    ("393-light-13", 393, "1.3", False),
    ("393-dark-10", 393, "1.0", True),
    ("393-dark-13", 393, "1.3", True),
]
# The classes that measure the screen rather than photograph it need the reference and the narrow screen:
# "the footer is a floor" is only interesting where the room is smallest, and the keyboard claim is the one
# a width can break. Repeating them at every theme would re-run identical geometry under a different paint.
EDITOR_BEHAVIOUR_TAGS = ["393-light-10", "320-light-10"]
FRAMES_CLASS = "com.taskplanner.ui.tasks.TaskEditorFramesInstrumentedTest"
EDITOR_CLASSES = [
    "com.taskplanner.testing.SeededProfileCleanupTest",
    FRAMES_CLASS,
    "com.taskplanner.ui.tasks.TaskEditorInstrumentedTest",
    "com.taskplanner.ui.tasks.TasksFabClearanceInstrumentedTest",
    "com.taskplanner.ui.TasksScreenInstrumentedTest",
]
INSTRUMENT_TARGET = "com.taskplanner.test/androidx.test.runner.AndroidJUnitRunner"
SHOTS_DIR = "/storage/emulated/0/Android/data/com.taskplanner/files"


def instrument(serial, cls, tag=None, raw_to=None):
    """One class in one invocation. Returns (exit code, every line worth quoting).

    `am instrument -w` prints the result and leaves the shell with a nonzero code only for a malformed
    run, so a failing test arrives as text, not as an error this function could otherwise swallow. The
    quoted lines are what the manifest can carry; the whole output goes to `raw_to` when a caller wants
    to read a failure message rather than guess it from a stack trace in logcat.
    """
    cmd = ["adb"] + (["-s", serial] if serial else []) + [
        "shell", "am", "instrument", "-w", "-e", "class", cls]
    if tag:
        cmd += ["-e", "tag", tag]
    cmd += [INSTRUMENT_TARGET]
    result = subprocess.run(cmd, capture_output=True)
    text = result.stdout.decode("utf-8", "replace")
    if raw_to:
        with open(raw_to, "w", encoding="utf-8") as handle:
            handle.write("$ %s\n\n%s" % (" ".join(cmd), text))
            if result.stderr:
                handle.write("\n--- stderr ---\n%s" % result.stderr.decode("utf-8", "replace"))
    quoted = [line for line in text.splitlines() if line.startswith(
        ("OK (", "Tests run", "FAILURES!!", "There were", "INSTRUMENTATION_RESULT", "Error in", "Exception in"))]
    return result.returncode, quoted


def start_clean(serial, device):
    """Put the device in a known state before one instrumented class, and say who had focus.

    Two ways this matrix lied without meaning to, both measured on `emulator-5584`: a notification shade
    left open (every Espresso call then waits ten seconds for a focus that is not coming, and a whole class
    errors while the screen behind the shade is healthy), and an app process left alive by a killed run —
    which keeps the profile it read at start-up, so the next class seeds a profile the running app cannot
    see and photographs an empty form. Both are device state, not app behaviour, and both are cleared here
    rather than worked around in a test.
    """
    for pkg in (device, device + ".test"):
        adb(serial, "shell", "am force-stop %s" % pkg)
    adb(serial, "shell", "cmd statusbar collapse")
    focus = adb(serial, "shell", "dumpsys window | grep -m1 mCurrentFocus").strip()
    return focus.split("u0 ")[-1] if focus else "focus unknown"


def readback_configuration(serial):
    """What the device says it is, after a configuration was asked for: the same three numbers the
    frames class reads, from outside, so the script's log and the test's failure agree."""
    density = adb(serial, "shell", "wm density")
    font = adb(serial, "shell", "settings get system font_scale")
    night = adb(serial, "shell", "dumpsys uimode | grep -o mComputedNightMode=[a-z]*")
    return "%s | font=%s | %s" % (density.strip(), font.strip(), night.strip())


def editor_matrix(serial, out, device="com.taskplanner"):
    manifest = os.path.join(out, "editor-matrix.md")
    logs = os.path.join(out, "runs")
    os.makedirs(logs, exist_ok=True)
    by_tag = {tag: (dp, font, night) for tag, dp, font, night in EDITOR_MATRIX}
    # Every run is checked for `OK (`; the list is what the script fails with at the end. A matrix
    # that prints FAILURES!! and exits 0 is a manifest nobody can trust.
    refused = []
    with open(manifest, "w", encoding="utf-8") as log:
        log.write("| configuration | readback | class | result |\n|---|---|---|---|\n")
        for tag, dp, font, night in EDITOR_MATRIX:
            set_dp(serial, dp)
            set_font(serial, font)
            set_night(serial, night)
            time.sleep(6)
            seen = readback_configuration(serial)
            focus = start_clean(serial, device)
            print("%s: %s (focus %s)" % (tag, seen, focus))
            code, quoted = instrument(
                serial, FRAMES_CLASS, tag=tag,
                raw_to=os.path.join(logs, "frames-%s.txt" % tag))
            summary = " ".join(quoted) or "code %d" % code
            log.write("| %s | %s | %s | %s |\n" % (tag, seen + " | focus `" + focus + "`", FRAMES_CLASS, summary))
            if not summary.startswith("OK ("):
                refused.append("%s frames: %s" % (tag, summary))
            log.flush()
            print("   %s" % summary)
            # Pulled here, before the next configuration's run, rather than at the end. The app's data
            # directory is cleared between instrumentations by `clearPackageData`, and measured on this
            # device the frames did survive — but a picture that has to outlive the run that wrote it is a
            # picture whose safety depends on something the script does not control.
            written = pull_frames(serial, out, tag)
            log.write("| %s | %s | frames on disk | %d |\n" % (tag, seen, written))
            log.flush()
            print("   frames pulled: %d" % written)
        # The classes that photograph nothing, at the reference width and at the narrow one.
        for tag in EDITOR_BEHAVIOUR_TAGS:
            dp, font, night = by_tag[tag]
            set_dp(serial, dp)
            set_font(serial, font)
            set_night(serial, night)
            time.sleep(6)
            seen = readback_configuration(serial)
            start_clean(serial, device)
            for cls in EDITOR_CLASSES:
                if cls == FRAMES_CLASS:
                    continue
                code, quoted = instrument(
                    serial, cls, tag=tag,
                    raw_to=os.path.join(logs, "%s-%s.txt" % (cls.split(".")[-1], tag)))
                summary = " ".join(quoted) or "code %d" % code
                log.write("| %s | %s | %s | %s |\n" % (tag, seen, cls, summary))
                if not summary.startswith("OK ("):
                    refused.append("%s @ %s: %s" % (cls.split(".")[-1], tag, summary))
                log.flush()
                print("   %s @ %s -> %s" % (cls.split(".")[-1], tag, summary))
        # The next stage reads the panel the way this one left it, and the frames class defaults to
        # `393-light-10` when no tag is passed: leaving 270 dpi and night mode behind would make the next
        # run's default configuration a lie.
        set_dp(serial, 393)
        set_font(serial, "1.0")
        set_night(serial, False)
        log.write("\nEvery frame here was written by the class that checked it: the configuration its tag "
                  "names was read back off the device before the shutter opened, the region photographed was "
                  "measured for luminance spread inside the form's viewport, and a refusal fails the run "
                  "rather than filing a smaller picture. The raw `am instrument` output for every run is "
                  "under `runs/`, and the frames under `frames/<configuration>/`.\n")
        print("manifest: %s" % manifest)
    if refused:
        raise SystemExit(
            "the editor matrix did not pass. %d run(s) came back without `OK (`:\n  %s"
            % (len(refused), "\n  ".join(refused))
        )


def pull_frames(serial, out, tag):
    """Copy one configuration's frames off the device. Returns how many landed."""
    parent = os.path.join(out, "frames")
    local = os.path.join(parent, tag)
    if os.path.isdir(local):
        shutil.rmtree(local)
    os.makedirs(parent, exist_ok=True)
    # adb is asked to pull the directory itself into `frames`, so it recreates `<tag>` there. The trailing
    # slash was tried first and produced `frames/<tag>/<tag>/*.png` on this host, which the count below then
    # read as "no frames" — a manifest that says nothing was photographed when nine pictures were on disk.
    adb(serial, "pull", "%s/shots/%s" % (SHOTS_DIR, tag), parent)
    return len([n for n in os.listdir(local) if n.endswith(".png")]) if os.path.isdir(local) else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", default="")
    parser.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    parser.add_argument("--editor-matrix", action="store_true",
                        help="run the editor's instrumented frames instead of the screen walkthrough")
    args = parser.parse_args()
    serial = args.serial or None
    os.makedirs(args.out, exist_ok=True)
    # A screenshot of a dimmed screen is not evidence, and the panel sleeps mid-run otherwise.
    adb(serial, "shell", "svc power stayon true")
    adb(serial, "shell", "input keyevent KEYCODE_WAKEUP")

    if args.editor_matrix:
        editor_matrix(serial, args.out)
        return

    wanted = {w.strip() for w in args.only.split(",") if w.strip()}
    manifest = os.path.join(args.out, "manifest.md")
    writing_new = not os.path.exists(manifest)
    log = open(manifest, "a", encoding="utf-8")
    if writing_new:
        log.write("| file | screen | theme | width dp | density | font scale | uimode | visible tasks |\n")
        log.write("|---|---|---|---|---|---|---|---|\n")

    for name, screen, theme, dp, font, navigate in SHOTS + EXTRA:
        if wanted and name not in wanted:
            continue
        density = set_dp(serial, dp)
        set_font(serial, font)
        set_night(serial, theme == "dark")
        time.sleep(1)
        restart(serial)
        navigate(serial)
        ensure_foreground(serial)
        time.sleep(1)
        path = os.path.join(args.out, "%s.png" % name)
        with open(path, "wb") as handle:
            handle.write(adb(serial, "exec-out", "screencap -p", binary=True))
        log.write("| %s | %s | %s | %d | %d | %s | %s | %s |\n" % (
            name, screen, theme, dp, density, font, theme, row_count(serial)))
        log.flush()
        print("wrote %s" % path)

    log.close()
    # Leave the device in the reference configuration the next stage inherits.
    set_dp(serial, 393)
    set_font(serial, "1.0")
    set_night(serial, False)
    print("manifest: %s" % manifest)


if __name__ == "__main__":
    main()
