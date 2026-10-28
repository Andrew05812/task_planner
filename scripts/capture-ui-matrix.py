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


def adb_soft(serial, *args):
    """A probe rather than a step.

    `adb()` takes the process down when a command exits non-zero, which is right for a step that has to happen
    and wrong for a question that is allowed to come back "no such directory". The navigation matrix asks
    twice per configuration — what is on the device before a run, and what is left after a wipe — and both
    answers are evidence either way.
    """
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    result = subprocess.run(cmd, capture_output=True)
    return (result.returncode,
            result.stdout.decode("utf-8", "replace").strip(),
            result.stderr.decode("utf-8", "replace").strip())


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
            # The accessibility tree's other name for a view: Material marks the bottom bar's label `TextView`s
            # `importantForAccessibility=no` and gives the item node the tab's word here instead. A check that
            # counted only `text` would report a bar that every screen reader reads as empty.
            "contentDescription": field("content-desc"),
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
    # The third value is the whole run: `OK (7 tests)` only means something beside the names it ran, and a
    # caller that has to say *which* test refused needs the stack rather than the summary line.
    return result.returncode, quoted, text


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
            code, quoted, _ = instrument(
                serial, FRAMES_CLASS, tag=tag,
                raw_to=os.path.join(logs, "frames-%s.txt" % tag))
            summary = " ".join(quoted) or "code %d" % code
            log.write("| %s | %s | %s | %s |\n" % (tag, seen + " | focus `" + focus + "`", FRAMES_CLASS, summary))
            if not summary.startswith("OK ("):
                refused.append("%s frames: %s" % (tag, summary))
            log.flush()
            print("   %s" % summary)
            # Pulled here, before the next configuration's run, rather than at the end. What actually protects
            # the pictures is this: `clearPackageData=true` (`app/build.gradle.kts:29`) clears the app's *own*
            # data, and a file under `/storage/emulated/0/Android/data/...` is external storage that the sweep
            # does not take — measured on this device, the previous run's frames were still there afterwards.
            # That is a reason to pull immediately and to wipe the tag before a run writes it, not a reason to
            # believe a picture has to outlive the process that wrote it.
            written, on_device, why = pull_frames(serial, out, tag)
            log.write("| %s | %s | frames on disk | %d of %d on device (%s) |\n"
                      % (tag, seen, written, on_device, why))
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
                code, quoted, _ = instrument(
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


def pull_frames(serial, out, tag, merge=False):
    """Copy one configuration's evidence off the device.

    Returns (frames that landed, frames that were on the device, why). Three outcomes used to collapse into the
    number zero — `adb pull` failing, the directory not existing, and the directory being there but empty —
    and all three printed "0 frames", which is how a manifest comes to say nothing was photographed while nine
    pictures were on disk. The caller compares the first two against what the class was supposed to write.

    `merge` keeps what is already on disk. The navigation matrix runs one method per instrumentation and the
    nine runs of a configuration write into the same directory on the device, so pulling into a fresh local
    copy each time would leave the manifest holding only the last method's pictures and call the missing
    eight a defect of the screen.
    """
    parent = os.path.join(out, "frames")
    local = os.path.join(parent, tag)
    if os.path.isdir(local) and not merge:
        shutil.rmtree(local)
    os.makedirs(parent, exist_ok=True)
    remote = "%s/shots/%s" % (SHOTS_DIR, tag)
    exists, listing, err = adb_soft(serial, "shell", "ls -1 %s" % remote)
    if exists != 0:
        return 0, 0, "the run wrote no directory at %s (%s)" % (remote, err or "no such directory")
    on_device = len([n for n in listing.splitlines() if n.endswith(".png")])
    # adb is asked to pull the directory itself into `frames`, so it recreates `<tag>` there. The trailing
    # slash was tried first and produced `frames/<tag>/<tag>/*.png` on this host, which the count below then
    # read as "no frames".
    # Pulled into a staging name and moved in, because `adb pull <dir> <parent>` nests when the destination
    # already exists — which is exactly what a merge is trying not to do.
    staging = os.path.join(parent, tag + ".part")
    if os.path.isdir(staging):
        shutil.rmtree(staging)
    # The destination has to exist before files are moved into it. `adb pull` would have created it when it
    # was the destination itself; moving into a directory that is not there makes the first file *become* the
    # directory and the second fail, which is how the first per-method run died.
    os.makedirs(local, exist_ok=True)
    result = subprocess.run(
        ["adb"] + (["-s", serial] if serial else []) + ["pull", remote, staging], capture_output=True)
    if result.returncode != 0:
        return 0, on_device, "adb pull failed: %s" % result.stderr.decode("utf-8", "replace").strip()
    if not os.path.isdir(staging):
        return 0, on_device, "adb pull reported success but %s is not on disk" % staging
    for name in os.listdir(staging):
        shutil.move(os.path.join(staging, name), os.path.join(local, name))
    os.rmdir(staging)
    frames = [n for n in os.listdir(local) if n.endswith(".png")]
    return (len(frames), on_device,
            "ok" if frames else "the directory is there and holds no picture")


NAV_CLASS = "com.taskplanner.ui.BottomNavigationInstrumentedTest"
NAV_TEST_CLASS = "com.taskplanner.ui.BottomNavigationInstrumentedTest"
# The bar is one widget in four configurations: the reference screen, the narrowest the app supports, the
# width between them, and the theme that changes every colour under it. All four are at font scale 1.0, because
# 1.0 is the scale the design promises five whole words at. At 1.3 the slot a label owns is still the bar's
# width over five while the words grow, and Material pins these labels to one line with `ellipsize=end`
# (`design_bottom_navigation_item.xml:51,58`), so an over-long word does not wrap into a second line — it loses
# letters. That is measured, in the table below, rather than gated here.
NAV_MATRIX = [
    ("393-light-10", 393, "1.0", False),
    ("320-light-10", 320, "1.0", False),
    ("360-light-10", 360, "1.0", False),
    ("393-dark-10", 393, "1.0", True),
]
# Measured rather than gated: one method, at the largest font scale the app is asked to support, on the
# reference width and on the narrowest. It writes `label-budget.txt` beside its frame, and that file is this
# stage's record of which words lost how many characters.
NAV_LABEL_METHOD = "theLabelsToldAtTheLargestFontScaleAreWrittenDown"

# (tag, width dp, font scale, night, methods). Each of these runs one method rather than the class, and each
# is a measurement rather than a gate: what they record is what the two long words and the plate's vertical
# room do at the largest font scale the app supports, which Stage 10 owns and this stage only witnesses.
NAV_MEASURE_MATRIX = [
    ("393-light-13", 393, "1.3", False, [NAV_LABEL_METHOD, "theChosenTabSitsOnADrawnPlate"]),
    ("360-light-13", 360, "1.3", False, [NAV_LABEL_METHOD, "theChosenTabSitsOnADrawnPlate"]),
    ("320-light-13", 320, "1.3", False, [NAV_LABEL_METHOD, "theChosenTabSitsOnADrawnPlate"]),
]
# Frames each measured method is expected to write when it runs alone.
NAV_MEASURE_FRAMES = {NAV_LABEL_METHOD: 1, "theChosenTabSitsOnADrawnPlate": 0}
NAV_METHODS = [
    "allFiveLabelsAreOnTheScreenAtOnce",
    NAV_LABEL_METHOD,
    "eachTabOpensTheScreenItNames",
    "theChosenTabIsDrawnInADifferentColourFromTheOtherFour",
    "aDrawnRuleSeparatesTheBarFromThePage",
    "theBoundaryBetweenBarAndPageIsMeasurable",
    "theChosenTabSitsOnADrawnPlate",
    "theAccessibilityTreeOffersAllFiveNames",
    "holdingAFingerOnTheChosenTabChangesItsPixels",
]
# What each method owes on its own — the frames it photographs, and whether it is one of the methods that
# writes a measurement file — and, for a whole configuration, what the nine runs have to leave on disk between
# them. A count is the only way this script can notice that a method was skipped, that the device refused the
# shutter, or that a run which "passed" ran nothing.
NAV_METHOD_OUTPUT = {
    "allFiveLabelsAreOnTheScreenAtOnce": (0, False),
    "theAccessibilityTreeOffersAllFiveNames": (0, False),
    "theLabelsToldAtTheLargestFontScaleAreWrittenDown": (1, True),
    "eachTabOpensTheScreenItNames": (5, False),
    "theChosenTabIsDrawnInADifferentColourFromTheOtherFour": (0, False),
    "aDrawnRuleSeparatesTheBarFromThePage": (0, False),
    "theBoundaryBetweenBarAndPageIsMeasurable": (0, True),
    "theChosenTabSitsOnADrawnPlate": (0, True),
    "holdingAFingerOnTheChosenTabChangesItsPixels": (0, True),
}
# Five tab frames are the stage's evidence; the label-budget frame is checked as a record rather than as a
# frame, because a measured row runs one method only.
NAV_EXPECT_FILES = [
    "s7-nav-tasks.png", "s7-nav-calendar.png", "s7-nav-categories.png", "s7-nav-statistics.png",
    "s7-nav-profile.png",
]
NAV_EXPECT_NOTES = ["label-budget.txt", "accessibility-tree.txt"]
# How many times one method may be handed to the device. More than this and a real crash would be hidden by
# the retry; the count is written into the row either way.
NAV_RUN_ATTEMPTS = 3


def wipe_shots(serial, tag):
    """Take one tag's directory off the device before the run that is about to write it.

    The pull deletes the *local* copy first, so a previous run's frame left on the device lands beside the new
    one and reads as this configuration's output. The name is what gets deleted — clearing the whole `shots/`
    directory would throw away the configuration that is on the device right now because of the row above it,
    and there is no reason for a stage to own that much of the device. Names are reported rather than
    swallowed: a directory that was never there and a directory that was emptied are different facts.
    """
    remote = "%s/shots/%s" % (SHOTS_DIR, tag)
    exists, listing, _ = adb_soft(serial, "shell", "ls -1 %s" % remote)
    if exists != 0:
        return "nothing was there"
    names = [n for n in listing.splitlines() if n]
    adb(serial, "shell", "rm", "-r", remote)
    after, _, _ = adb_soft(serial, "shell", "ls -1 %s" % remote)
    if after == 0:
        return "still on the device after rm -r: %s" % remote
    return "removed %d (%s)" % (len(names), ", ".join(names) if names else "an empty directory")


def open_bar_screen(serial, tag):
    """Bring the app to the front in the configuration the tag names, and wait for the bar to be on screen.

    The dump is evidence about a window that has to exist, so the wait is for `bottom_navigation` itself rather
    than for a fixed sleep. Returns how many tries it took, which is what a reader of the manifest wants when a
    configuration is slow.
    """
    for pkg in ("com.taskplanner", "com.taskplanner.test"):
        adb(serial, "shell", "am force-stop %s" % pkg)
    adb(serial, "shell", "cmd statusbar collapse")
    adb(serial, "shell", "monkey -p com.taskplanner -c android.intent.category.LAUNCHER 1")
    for attempt in range(24):
        time.sleep(2.5)
        if any(n["id"] == "bottom_navigation" for n in nodes(serial)):
            return attempt
    return -1


def focused_window(serial):
    """What the window manager says has focus — the sentence to write when a configuration will not open."""
    code, out, _ = adb_soft(serial, "shell", "dumpsys window | grep -m1 mCurrentFocus")
    return out.strip() if code == 0 and out.strip() else "no focus reported"


def nav_bar_nodes(serial):
    """What a screen reader is offered inside the bar, read out of a live `uiautomator` dump.

    The dump is the accessibility tree and not the view tree: Material marks the label `TextView`s
    `importantForAccessibility=no` and lets the item node carry the tab's word as a content description, so a
    check that counted only `text` nodes would answer "zero" about a bar every screen reader reads correctly.
    Both fields are counted and both are written to the manifest, because the claim this stage has to make is
    that a reader is given five names — not that five widgets happen to own a text property.
    """
    tree = nodes(serial)
    bar = next((n for n in tree if n["id"] == "bottom_navigation"), None)
    if bar is None:
        return None
    left, top, right, bottom = bar["box"]
    inside = [
        n for n in tree
        if left <= n["box"][0] and n["box"][2] <= right and top <= n["box"][1] and n["box"][3] <= bottom + 2
    ]
    return {
        "box": bar["box"],
        "nodes": len(inside),
        "texts": [n["text"] for n in inside if n["text"]],
        "descriptions": [n["contentDescription"] for n in inside if n["contentDescription"]],
        "selected": [(n["contentDescription"] or n["text"]) for n in inside if n["selected"] == "true"],
    }


def nav_matrix(serial, out, device="com.taskplanner", only=None):
    """The bottom bar, run in every configuration the stage has to be seen in.

    The editor matrix's contract, plus what this stage's reviews asked for: apply the configuration, read it
    back, start the device clean, wipe the tag this run is about to write, run the class, require the *number*
    of tests the class has rather than any line beginning "OK (", pull the evidence and require the number of
    frames it should have written, ask the accessibility tree how many names it offers, and restore the device
    whether the run passed, failed or took the process down. An `adb` call inside `instrument()` exits the
    interpreter, and a manifest left half-written with the device still at 270 dpi is the failure that a
    following stage reads as a configuration it can trust.
    """
    N = chr(10)
    manifest = os.path.join(out, "nav-matrix.md")
    logs = os.path.join(out, "runs")
    os.makedirs(logs, exist_ok=True)
    refused = []

    def one_run(tag, dp, font, night, cls, expect_tests, expect_frames, expect_notes=True,
                first_of_tag=True):
        if first_of_tag:
            set_dp(serial, dp)
            set_font(serial, font)
            set_night(serial, night)
            # The configuration has to be finished being applied, not merely asked for: `wm density` returns
            # before the windows have been re-created, and a process started inside that window is the one the
            # system then kills.
            time.sleep(15)
        seen = readback_configuration(serial)
        # Warm the configuration before the class runs in it.
        #
        # Measured on this device: of the six configurations, the four that needed no change from the state the
        # previous row left behind answered `OK (9 tests)`, and the two that changed density or night mode
        # immediately before the run answered `shortMsg=Process crashed` — the system log calling it
        # `Killing …/com.taskplanner: bg anr`. A window that has just been reconfigured is still being
        # re-rendered by the launcher and the status bar, and an instrumentation that starts inside that
        # window times out on the *system*, not on the app. So the app is opened once here, waited for, and
        # stopped again: it is also the preflight the stage asks for, that the application does not crash when
        # it is opened in this configuration.
        # Only the first run of a tag pays for the warm-up; the methods that follow inherit a settled device.
        warmed_in = open_bar_screen(serial, tag) if first_of_tag else "inherited"
        if warmed_in == -1:
            return {
                "tag": tag, "seen": seen, "focus": "not reached", "wiped": "not reached", "cls": cls,
                "summary": "REFUSED: the app did not come to the bar in this configuration after 24 tries "
                           "(the screen it reached was %s)" % focused_window(serial),
                "frames": 0, "notes": [], "resumed": resumed_activity(serial), "raw": "",
            }
        focus = start_clean(serial, device)
        # Cleared once per tag, not once per method: nine methods write the same directory on the device, and
        # wiping it between them would throw away the frames the earlier methods photographed.
        wiped = wipe_shots(serial, tag) if first_of_tag else "kept for the next method of this tag"
        # The log is named after what ran, not only after the configuration: a class run and the single-method
        # run that follows it share a tag, and one `nav-<tag>.txt` in the middle of the other is exactly the
        # kind of overwritten evidence a reader cannot trust.
        label = cls.split(".")[-1] + ("-" + cls.split("#")[-1] if "#" in cls else "")
        raw_to = os.path.join(logs, "nav-%s-%s.txt" % (label, tag))
        attempts = 0
        code, quoted, text = 0, [], ""
        while attempts < NAV_RUN_ATTEMPTS:
            attempts += 1
            code, quoted, text = instrument(serial, cls, tag=tag, raw_to=raw_to)
            summary = " ".join(quoted) or "code %d and nothing recognisable in the output" % code
            # Only a run the device took down is retried. A test that asserted and failed is a finding, and
            # repeating it would be the matrix trying to lose the evidence.
            if "Process crashed" not in summary and "shortMsg" not in summary:
                break
            if attempts < NAV_RUN_ATTEMPTS:
                print("      %s: the device took the run down (attempt %d), settling and trying again"
                      % (tag, attempts))
                time.sleep(20)
        summary = " ".join(quoted) or "code %d and nothing recognisable in the output" % code
        if attempts > 1:
            summary += " [after %d attempts]" % attempts
        _, frames, why = pull_frames(serial, out, tag, merge=not first_of_tag)
        directory = os.path.join(out, "frames", tag)
        notes = sorted(n for n in os.listdir(directory) if not n.endswith(".png")) if os.path.isdir(directory) else []
        # Every way a run can fail is collected, not short-circuited. An `if / elif` chain reports the first
        # reason it found and hides the rest, which is how "the class answered OK but ran six of eight tests"
        # and "no frames landed" become the same single line — and a non-zero `am instrument` code beside an
        # `OK (` summary has to be a refusal too, because the exit code is the only thing that notices a
        # crash the report never printed.
        reasons = []
        if not summary.startswith("OK ("):
            reasons.append(summary)
        if code != 0:
            reasons.append("am instrument exited %d" % code)
        ran = re.search(r"OK \((\d+) test", summary)
        if ran and int(ran.group(1)) != expect_tests:
            reasons.append("answered %s tests, expected %d, so a method was skipped or renamed"
                           % (ran.group(1), expect_tests))
        if frames < expect_frames:
            reasons.append("%d frames of %d expected (%s)" % (frames, expect_frames, why))
        if expect_notes and not notes:
            reasons.append("no measurement or label-budget file beside the frames")
        if expect_frames == 0:
            # A method that photographs nothing for the manifest must not be scored on frames at all; the
            # configuration's own verdict is taken from the union of the nine runs below.
            reasons = [r for r in reasons if "frames" not in r]
        if reasons:
            summary = "REFUSED: " + "; ".join(reasons)
            refused.append("%s @ %s: %s" % (tag, cls.split("#")[-1], summary))
        row = {
            "tag": tag, "seen": seen, "focus": focus, "wiped": wiped, "cls": cls, "summary": summary,
            "frames": frames, "notes": notes, "resumed": resumed_activity(serial), "raw": text,
            "warmed_in": warmed_in,
        }
        return row

    def log_row(log, row, a11y):
        log.write(("| %s | %s | opened the bar in %s re-tries | focus `%s` | wiped: %s | `%s` | %s |"
                   " %d frames, %d notes | resumed `%s` |" + N) % (
            row["tag"], row["seen"], row.get("warmed_in", "n/a"), row["focus"], row["wiped"], row["cls"],
            row["summary"], row["frames"], len(row["notes"]), row["resumed"]))
        if a11y is False:
            pass  # asked once per configuration, by the caller below, and written there as its own row
        elif a11y is None:
            log.write(("| %s | accessibility | uiautomator gave no hierarchy at all |" + N) % row["tag"])
        else:
            log.write(("| %s | accessibility | bar %s | %d nodes inside | text %s | content-desc %s"
                       " | selected %s |" + N) % (
                row["tag"], a11y["box"], a11y["nodes"], a11y["texts"], a11y["descriptions"], a11y["selected"]))
        log.write(("| %s | evidence files | %s |" + N) % (row["tag"], ", ".join(row["notes"]) or "NONE"))
        log.flush()

    with open(manifest, "w", encoding="utf-8") as log:
        log.write("| configuration | readback | app opened cleanly | focus before the run | shots wiped |"
                  " class | result | pulled | resumed activity |" + N)
        log.write("|---|---|---|---|---|---|---|---|---|" + N)
        try:
            gated = NAV_MATRIX if not only else [r for r in NAV_MATRIX if r[0] in only]
            for tag, dp, font, night in gated:
                # One instrumentation per method, and the configuration judged on the union of the nine. The
                # class as a whole is not used: on this machine it answered `shortMsg=Process crashed` in three
                # of the four configurations while every method answered `OK (1 test)` on its own — nine
                # windows, each with up to three full-screen bitmaps, is more than the emulator has while a
                # second emulator and the build hold the rest of the memory. A manifest that called that the
                # stage failing would be blaming the screen for the machine.
                rows = []
                for position, method in enumerate(NAV_METHODS):
                    frames, notes = NAV_METHOD_OUTPUT.get(method, (0, False))
                    row = one_run(tag, dp, font, night, "%s#%s" % (NAV_CLASS, method), 1, frames, notes,
                                  first_of_tag=(position == 0))
                    rows.append(row)
                    # The outside dump is asked once, after the nine runs, because the window the methods
                    # owned is closed by then; asking per method would ask the launcher nine times.
                    log_row(log, row, False)
                    print("%s #%s -> %s" % (tag, method, row["summary"]))
                directory = os.path.join(out, "frames", tag)
                on_disk = os.listdir(directory) if os.path.isdir(directory) else []
                landed = sorted(n for n in on_disk if n.endswith(".png"))
                notes_landed = sorted(n for n in on_disk if not n.endswith(".png"))
                missing = [f for f in NAV_EXPECT_FILES if f not in landed]
                if missing:
                    refused.append("%s: the nine runs left no %s on disk" % (tag, ", ".join(missing)))
                for wanted in NAV_EXPECT_NOTES:
                    if wanted not in notes_landed:
                        refused.append("%s: the nine runs wrote no %s" % (tag, wanted))
                passed = len([r for r in rows if r["summary"].startswith("OK (")])
                if passed != len(NAV_METHODS):
                    refused.append("%s: %d of %d methods answered OK" % (tag, passed, len(NAV_METHODS)))
                # The window the methods owned is closed by now, so the outside tree is asked of a fresh start
                # of the same configuration — the `uiautomator dump` the stage's acceptance names, taken while
                # the bar really is on screen.
                opened_in = open_bar_screen(serial, tag)
                if opened_in < 0:
                    refused.append("%s: the bar never came on screen after restarting the app" % tag)
                a11y = nav_bar_nodes(serial)
                log.write(("| %s | bar on screen after %s re-tries | resumed `%s` |" + N) % (
                    tag, opened_in, resumed_activity(serial)))
                if a11y is None:
                    refused.append("%s: uiautomator gave no hierarchy, so the accessibility tree was never seen" % tag)
                elif len(a11y["texts"]) != 5 and len(a11y["descriptions"]) != 5:
                    refused.append("%s: the accessibility tree offers %d text nodes and %d described nodes"
                                   " inside the bar" % (tag, len(a11y["texts"]), len(a11y["descriptions"])))
                else:
                    names = sorted({s for s in a11y["selected"] if s})
                    if len(names) != 1:
                        refused.append("%s: the bar tells a reader these tabs are selected: %s" % (tag, names))
                if a11y is not None:
                    log.write(("| %s | accessibility | bar %s | %d nodes inside | text %s | content-desc %s"
                               " | selected %s |" + N) % (
                        tag, a11y["box"], a11y["nodes"], a11y["texts"], a11y["descriptions"],
                        a11y["selected"]))
                log.write(("| %s | configuration verdict | %d of %d runs OK | frames: %s | records: %s |" + N)
                          % (tag, passed, len(NAV_METHODS), ", ".join(landed), ", ".join(notes_landed)))
                log.flush()
                print("%s -> %d/%d methods OK, %d frames on disk" % (tag, passed, len(NAV_METHODS), len(landed)))
            for tag, dp, font, night, methods in NAV_MEASURE_MATRIX:
                if only and tag not in only:
                    continue
                for position, method in enumerate(methods):
                    row = one_run(tag, dp, font, night, "%s#%s" % (NAV_CLASS, method), 1,
                                  NAV_MEASURE_FRAMES.get(method, 0),
                                  expect_notes=False, first_of_tag=(position == 0))
                    log_row(log, row, nav_bar_nodes(serial))
                    print("%s (measured, not gated) #%s -> %s" % (tag, method, row["summary"]))
            log.write(N + "A gated row is nine runs, one per test method, and the configuration's verdict is"
                      " the union of what those nine left on disk. The class as a whole is not used: on this"
                      " machine it answered `shortMsg=Process crashed` in three of the four configurations"
                      " while every method answered `OK (1 test)` alone — nine windows and up to three"
                      " full-screen bitmaps each is more than the emulator has while a second emulator and the"
                      " build hold the rest of the memory. Measured rows run one method each at font scale 1.3"
                      " and are measurements rather than gates: they record what the labels and the plate do"
                      " when the reader has asked for larger text, which Stage 10 owns. The accessibility"
                      " columns come from a live `uiautomator dump` — the tree a screen reader sees, which is"
                      " not the view tree: Material marks the label views `importantForAccessibility=no` and"
                      " puts the tab's word on the item node as a content description." + N)
            log.write(N + "Every frame was written by the run that checked it: the bar region was measured for"
                      " luminance spread and required to sit wholly inside the picture before the file was"
                      " allowed, the configuration named by its tag was read back off the device at the shutter,"
                      " and the rule, the surface and the plate were sampled as pixels in the same photograph."
                      " Raw `am instrument` output is under `runs/`." + N)
        finally:
            # The next stage inherits this device, and `readback_configuration` is asked afterwards rather
            # than assumed: an emulator that refused `wm density` is not in the reference configuration, and
            # the next stage's default tag would then be a lie.
            set_dp(serial, 393)
            set_font(serial, "1.0")
            set_night(serial, False)
            print("device restored: %s" % readback_configuration(serial))
        print("manifest: %s" % manifest)
    if refused:
        raise SystemExit("the navigation matrix did not pass:" + N + N.join(sorted(set(refused))))


def resumed_activity(serial):
    """Which activity the framework says is resumed — the platform's own words for the screen the reader is on.

    Three spellings, because the line moved: `mResumedActivity` on older releases, `topResumedActivity` on this
    API 35 image, and `mFocusedApp` as the last thing that still says where input is going. Returning
    "unknown" without trying the others is how a manifest claims it could not see a screen it was looking at.
    """
    for needle in ("topResumedActivity", "mResumedActivity", "mFocusedApp"):
        code, out, _ = adb_soft(
            serial, "shell", "dumpsys activity activities | grep -m1 %s" % needle)
        if code == 0 and out.strip():
            # `grep -m1` prints the field name; the value is what belongs in the manifest, not the needle twice.
            return out.strip().split("=", 1)[-1].strip() or "unknown"
    return "unknown"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", default="")
    parser.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    parser.add_argument("--editor-matrix", action="store_true",
                        help="run the editor's instrumented frames instead of the screen walkthrough")
    parser.add_argument("--nav-matrix", action="store_true",
                        help="run the bottom bar's instrumented class across the stage's configurations")
    parser.add_argument("--nav-only", default="",
                        help="with --nav-matrix: only these configuration tags, comma separated — the way to"
                             " re-run one refused row without re-running the six that answered")
    args = parser.parse_args()
    serial = args.serial or None
    os.makedirs(args.out, exist_ok=True)
    # A screenshot of a dimmed screen is not evidence, and the panel sleeps mid-run otherwise.
    adb(serial, "shell", "svc power stayon true")
    adb(serial, "shell", "input keyevent KEYCODE_WAKEUP")

    if args.editor_matrix:
        editor_matrix(serial, args.out)
        return

    if args.nav_matrix:
        only = {w.strip() for w in args.nav_only.split(",") if w.strip()}
        nav_matrix(serial, args.out, only=only or None)
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
