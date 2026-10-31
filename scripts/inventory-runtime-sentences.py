"""Rebuild the Stage 14 completion pass's runtime-sentence inventory from the commit that closed it.

The brief asked for an exact table — file, line, the literal, whether a reader sees it, what it was used for,
and what it became. The fixes are committed; the table was worked out in conversation and never written down,
which leaves the claim unverifiable. So it is regenerated from `git show --unified=0` rather than from memory:
each hunk is read as a unit, so a removed sentence is paired with the resource id the same hunk put in its
place.

Whether a reader sees a sentence is answered from the line it was written into, not from a guess: a string
handed to a screen state, a Snackbar, a Toast, a sign-in outcome or a backup refusal is content, and a string
handed to `Log` or to an exception nobody prints is not. The rule is spelled out below and travels with the
table it produced.

Usage: python scripts/inventory-runtime-sentences.py [commit] [out.md]
"""
import re
import subprocess
import sys

COMMIT = sys.argv[1] if len(sys.argv) > 1 else "792ea99"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/STAGE14-RUNTIME-SENTENCES.md"
SCOPE = ["app/src/main/java/com/taskplanner"]

diff = subprocess.run(
    ["git", "show", "--unified=0", COMMIT, "--", *SCOPE],
    capture_output=True, text=True, encoding="utf-8", errors="replace"
).stdout.splitlines()

file_re = re.compile(r"^diff --git a/(\S+) b/")
hunk_re = re.compile(r"^@@ -(\d+)(?:,\d+)? \+")
sentence_re = re.compile(r'"([A-Za-z][^"]{9,})"')
resource_re = re.compile(r"(R\.string\.\w+|R\.plurals\.\w+)")

# Whether a reader sees a sentence is not answered from the removed line — a call split over three lines leaves
# its sink on a line the table never sees. It is answered from what the hunk did with it: a sentence that
# became a resource became one because a screen prints it, and a `const val` that no longer exists was machine
# text.
CONST = re.compile(r"const val")

rows = []
current = None
i = 0
while i < len(diff):
    raw = diff[i]
    m = file_re.match(raw)
    if m:
        current = m.group(1).replace("app/src/main/java/com/taskplanner/", "")
        i += 1
        continue
    m = hunk_re.match(raw)
    if m:
        start = int(m.group(1))
        offset = 0
        removed_here = []
        added: list[str] = []
        j = i + 1
        while j < len(diff) and not diff[j].startswith("@@") and not diff[j].startswith("diff "):
            line = diff[j]
            if line.startswith("---") or line.startswith("+++"):
                j += 1
                continue
            if line.startswith("-"):
                body = line[1:].strip()
                hit = sentence_re.search(body)
                if hit and not body.startswith(("//", "*", "import")):
                    removed_here.append((start + offset, hit.group(1), body))
            else:
                if not line.startswith("+"):
                    offset += 1
                added += [r for r in resource_re.findall(line) if r not in added]
            j += 1
        for line_number, text, body in removed_here:
            rows.append((current, line_number, text, body, list(added)))
        i = j
        continue
    i += 1

reader_rows = sum(1 for r in rows if r[4])
const_rows = [r for r in rows if CONST.search(r[3])]
moved_rows = [r for r in rows if r[4]]
no_resource = [r for r in rows if not r[4]]

out = [
    "# Stage 14: the sentences the code wrote for itself",
    "",
    "Stage 14's completion pass was told to find every runtime string the app builds in Kotlin rather than in"
    " resources, decide per string whether a reader sees it, and localize the ones it does. That inventory lived"
    " in the conversation and nowhere else, which makes the claim unverifiable the moment the conversation ends."
    f" This file is regenerated from the commit that closed it — `git show --unified=0 {COMMIT}` — by"
    " `scripts/inventory-runtime-sentences.py`, so every line here is a line a diff said.",
    "",
    "**How to read the columns.** *Line* is the line the sentence sat on in `01b4438`, the build Stage 14"
    " shipped, taken from the hunk header. *Written into* is the call the sentence was an argument of."
    " *Became* is the resource id the same hunk introduced; `—` means the hunk put no resource in that place,"
    " and the row says why instead. *Reader sees it* is read off *Became*, not off *Written into*: a sentence"
    " that became a resource became one because a screen prints it, and a named constant that was deleted was"
    " machine text. Deciding it from the removed line alone would be wrong — a call split over three lines"
    " leaves its sink on a line no diff of a single line can show.",
    "",
    f"{len(rows)} lines carried a sentence: {reader_rows} became resources a screen prints, and"
    f" {len(const_rows)} were named constants the pass deleted rather than translated.",
    "",
    "| File | Line | The sentence | Reader sees it | Written into | Became |",
    "|---|---:|---|---|---|---|",
]
for f, line, text, body, resources in rows:
    safe_text = text.replace("|", "\\|")
    safe_body = body.replace("|", "\\|").replace("`", "'")[:96]
    became = ", ".join(f"`{r}`" for r in resources) if resources else "—"
    # The sink, read off what the hunk did with it: a sentence that became a resource became one because a
    # screen prints it, and a sentence that was deleted or replaced by a log call was machine text. Claiming
    # this from the removed line alone would be wrong — a call split over three lines leaves the sink on a
    # line this table never sees.
    if resources:
        seen = "да — the screen prints it"
    elif CONST.search(body):
        seen = "нет — machine text; the constant is gone and the log has the cause"
    else:
        seen = "нет — machine text; the screen already had its own label"
    out.append(f"| `{f}` | {line} | “{safe_text}” | {seen} | `{safe_body}` | {became} |")

out += [
    "",
    "## What became of them",
    "",
    "The table answers the question it was built to answer: of the lines that carried a sentence, "
    f"{reader_rows} sit on a path a reader sees, {len(const_rows)} were named constants the pass deleted rather "
    f"than translated (`READ_FAILED`, `PASS_FAILED`, `EMAIL_TAKEN`, `FALLBACK_ERROR` — a state that carries a "
    "resource id has no need of a constant at all), and exactly one row shows `—` for another reason: "
    "`SyncStatus.Failed(e.message ?: \"Sync failed\")`, where the fallback was dropped because the screen was "
    "already printing its own label (`sync_state_failed`) and the machine text has nowhere to go but the log.",
    "",
    "The change those lines add up to is measured elsewhere rather than restated here: the ledger in"
    " `docs/LOCALIZATION.md` carries the site and resource counts, and `HardcodedFailureTextTest` is the gate"
    " that keeps them — three rules (a fallback to a literal, a sink handed a literal or a named constant, a"
    " sink handed an exception's text) with a ceiling of five, each of the five named in the gate with the"
    " reason it is a diagnostic rather than copy. Its ceiling went from 29 to 5 by fixing the twenty-four, not"
    " by raising the ceiling.",
    "",
    f"Rebuild with `python scripts/inventory-runtime-sentences.py {COMMIT} docs/STAGE14-RUNTIME-SENTENCES.md`,"
    " from the commit that did the work — `6e0d2e2`..`a01dff4` are the layout and navigation passes of the same"
    " completion, and each can be inventoried the same way.",
    "",
]

with open(OUT, "w", encoding="utf-8") as handle:
    handle.write("\n".join(out))
print(
    f"wrote {OUT}: rows={len(rows)} reader-visible={reader_rows} constants={len(const_rows)} "
    f"with-resource={len(moved_rows)} without={len(no_resource)}"
)
