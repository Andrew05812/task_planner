#!/usr/bin/env python3
"""Write the depersonalized Keep corpus that CI parses.

This script never reads the user's real export. Every number below was measured from it once, by hand, on
2026-11-02, and is written here as a constant; the titles are synthetic. That direction — original measured,
fixture generated, never the reverse — is the point: a fixture rebuilt from the old backup JSON would have
reproduced that backup's mistakes (one flat group, 34 topics' completion states with nowhere to go) instead of
the source's shape.

Measured facts reproduced here, and asserted by `KeepCorpusContractTest`:

    439 physical lines, CRLF, no BOM, no blank lines
    382 titled checkbox items      296 "[X]"  +  86 "[ ]"
     57 empty checkbox rows        separators, not tasks
     56 root topics                37 with children, 19 without, 34 of them "[X]"
    326 children                   262 "[X]"  +  64 "[ ]", 95 of them under the biggest topic
      one space is one level; the real file never goes deeper than two levels
     19 titles repeat, 55 occurrences between them (36 beyond the first)
     48 titles carry an emoji, 39 a hh:mm, 4 a dd.mm.yy, 83 a " - ", one is exactly 169 characters long

Run:  python scripts/make-keep-corpus-fixture.py
"""
import os

OUT = "app/src/test/resources/keep/keep-corpus-shaped-like-the-original.txt"

TOPICS = 56
TOPICS_WITH_CHILDREN = 37
TOPICS_COMPLETED = 34
CHILDREN = 326
CHILDREN_COMPLETED = 262
SEPARATORS = 57
REPEATED_NAMES = 19
REPEATED_OCCURRENCES = 55
EMOJI_TITLES = 48
TIME_TITLES = 39
DATE_TITLES = 4
DASH_TITLES = 83
LONGEST_TITLE = 169
EMOJIS = ["🟥", "", "🟩", "🟦", "⭐", "📌", "✅", "🔁"]


def child_counts():
    """326 children across 37 topics, the largest holding 95 — the shape the source actually has."""
    sizes = [95]
    remaining, topics_left = CHILDREN - 95, TOPICS_WITH_CHILDREN - 1
    for index in range(topics_left):
        left = topics_left - index
        take = max(1, min(remaining - (left - 1), round(remaining / left)))
        sizes.append(take)
        remaining -= take
    assert remaining == 0 and len(sizes) == TOPICS_WITH_CHILDREN
    return sizes


def repeated_names():
    """19 names, 55 occurrences: 17 of them three times and 2 of them twice."""
    threes = REPEATED_OCCURRENCES - 2 * REPEATED_NAMES
    plan = [("Repeated errand %02d" % (i + 1), 3) for i in range(threes)]
    plan += [("Repeated errand %02d" % (i + threes + 1), 2) for i in range(REPEATED_NAMES - threes)]
    assert sum(count for _, count in plan) == REPEATED_OCCURRENCES
    return [name for name, count in plan for _ in range(count)]


def decorate(name, slot):
    """Marks by disjoint slot ranges, so each count is exact rather than approximate."""
    if slot < EMOJI_TITLES:
        return f"{EMOJIS[slot % len(EMOJIS)]}{name}"
    slot -= EMOJI_TITLES
    if slot < TIME_TITLES:
        return f"{name} - {8 + slot % 12:02d}:{(slot * 7) % 60:02d}"
    slot -= TIME_TITLES
    if slot < DATE_TITLES:
        return f"{name} - {11 + slot:02d}.{6 + slot:02d}.26"
    slot -= DATE_TITLES
    if slot < DASH_TITLES:
        return f"{name} - a place, a number and a comma, kept verbatim"
    slot -= DASH_TITLES
    if slot == 0:
        filler = "длинная строка экспорта, "
        padded = (name + " — " + filler * 9)[:LONGEST_TITLE]
        if padded.endswith(" "):
            # A title that trims back to 168 characters is not the source's longest line: end it on a letter.
            padded = padded[:-1] + "я"
        return padded.ljust(LONGEST_TITLE, "и")
    return name


def build():
    sizes = child_counts()
    repeats = repeated_names()
    # Spread the repeated names over the child slots instead of stacking them in one topic.
    repeat_at = {CHILDREN * (i + 1) // (len(repeats) + 1): name for i, name in enumerate(repeats)}

    plan = []
    child_index = 0
    for topic in range(TOPICS):
        plan.append(("topic", "Topic %02d" % topic, False))
        for _ in range(sizes[topic] if topic < len(sizes) else 0):
            # The child index is global across the file: `repeat_at` and the completed/open split are both
            # counted that way, and a per-topic index would name every topic's first child "Item 000".
            repeated = repeat_at.get(child_index)
            plan.append(("child", repeated or "Item %03d" % child_index, repeated is not None))
            child_index += 1
    assert child_index == CHILDREN, child_index

    # A repeated name is never decorated: a suffix would make two occurrences of it two different titles, and
    # the contract is about the source's 19 names carrying 36 extra copies.
    decorated, slot = [], 0
    for kind, base, is_repeated in plan:
        if is_repeated:
            decorated.append((kind, base))
        else:
            decorated.append((kind, decorate(base, slot)))
            slot += 1

    lines, topics_seen, children_seen = [], 0, 0
    for kind, name in decorated:
        if kind == "topic":
            mark = "X" if topics_seen < TOPICS_COMPLETED else " "
            topics_seen += 1
            lines.append(f"[{mark}] {name} ")
        else:
            mark = "X" if children_seen < CHILDREN_COMPLETED else " "
            children_seen += 1
            lines.append(f" [{mark}] {name}")

    separators = 0
    # 54 separators after every sixth child, the last 3 at the end: 57 in all, none of them a task.
    with_separators, child_seen = [], 0
    for line in lines:
        with_separators.append(line)
        if not line.startswith(" ["):
            continue
        child_seen += 1
        if child_seen % 6 == 0 and separators < SEPARATORS - 3:
            with_separators.append(" [ ] ")
            separators += 1
    while separators < SEPARATORS:
        with_separators.append("[X] ")
        separators += 1
    assert len(with_separators) == 439, len(with_separators)
    return with_separators


def main():
    lines = build()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="") as handle:
        handle.write("\r\n".join(lines))

    titled = [l for l in lines if l.strip() not in ("[ ]", "[X]", "[x]")]
    checked = sum(1 for l in titled if "] " in l and l.strip()[1] == "X")
    roots = sum(1 for l in titled if not l.startswith(" "))
    longest = max(len(l.split("] ", 1)[1]) for l in titled)
    print(f"wrote {OUT}")
    print(f"  lines={len(lines)}  titled={len(titled)}  checked={checked}  unchecked={len(titled) - checked}")
    print(f"  roots={roots}  children={len(titled) - roots}  separators={len(lines) - len(titled)}")
    print(f"  longest title={longest}")


if __name__ == "__main__":
    main()
