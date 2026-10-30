#!/usr/bin/env python3
"""
The localization completeness gate, in the form CI can run.

Why a script when `StringCompletenessTest`, `PluralsContractTest` and `StringFormatContractTest` already enforce the
same rules inside the build: the two audiences fail differently. A JVM test tells a developer that something is wrong
when they run the suite; this tells a reviewer, in one block of output on a pull request, *which resource* is missing
and how many there are, without a Gradle daemon and without an Android SDK. Both are kept because both are cheap, and
the rules are the same seven: if they ever drift, the JVM tests are the authority and this file is the one to fix.

Parsing is `xml.etree`, not regular expressions, for the same reason the test parses with a DOM parser: a `<string>`
element spans lines, carries attributes and holds entities, and a text match sees either two entries where there is
one or none where there is one.

Exit code is 0 only when every check passes, so a step in the workflow can fail the build on it.
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Blocks whose Russian forms are legitimately the same sentence: the phrase carries no counted noun to decline,
# so `one`/`few`/`many`/`other` cannot differ and repeating is correct rather than unfinished. Every entry needs a
# reason, and `PluralsContractTest` carries the same list -- the two are kept together by that test's own check.
IDENTICAL_FORMS_ALLOWED = {
    "history_manual_count": "%1$d внесено вручную carries no counted noun",
    "goal_count_figure": "%1$d из %2$d выполнено: the noun is governed by the denominator, not this argument",
    "goal_set_figure": "after из plus a numeral Russian always takes the genitive plural",
    "habit_times_per_week": "the Russian counter word 'раз' has two shapes for whole numbers: 1 раз, 2-4 раза, 5 раз",
    "achievement_unit_other": "a bare %d with no unit word, and no achievement rule uses OTHER",
}

# The same CLDR rule the JVM test carries: Russian counts one, few, many, and falls back to other.
RUSSIAN_CATEGORIES = ("one", "few", "many", "other")
ENGLISH_CATEGORIES = ("one", "other")
KEY_COUNTS = [0, 1, 2, 3, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111]

SPECIFIER = re.compile(r"%(\d+\$)?[-+ #0]*\d*(?:\.\d+)?[a-zA-Z%]")


def russian_category(count: int) -> str:
    ten, hundred = count % 10, count % 100
    if ten == 1 and hundred != 11:
        return "one"
    if 2 <= ten <= 4 and hundred not in range(11, 15):
        return "few"
    if ten == 0 or 5 <= ten <= 9 or hundred in range(11, 15):
        return "many"
    return "other"


def load(directory: Path) -> dict:
    """Every string, plurals block and array in a resource directory, merged the way Android merges them."""
    entries = {}
    if not directory.is_dir():
        return entries
    for file in sorted(directory.glob("*.xml")):
        try:
            root = ET.parse(file).getroot()
        except ET.ParseError as error:
            entries[f"!!parse!! {file.name}"] = {"type": "invalid", "error": str(error)}
            continue
        for node in root:
            if node.tag not in ("string", "plurals", "string-array", "array"):
                continue
            name = node.get("name")
            if not name:
                continue
            where = f"{directory.name}/{file.name}"
            if node.tag == "string":
                entries[name] = {
                    "type": "string",
                    "text": "".join(node.itertext()),
                    "translatable": node.get("translatable") != "false",
                    "where": where,
                }
            else:
                items = {}
                for item in node.findall("item"):
                    key = item.get("quantity") or str(len(items))
                    items[key] = "".join(item.itertext())
                entries[name] = {
                    "type": "plurals" if node.tag == "plurals" else "string-array",
                    "quantities": items,
                    "where": where,
                }
    return entries


def specifiers(text: str) -> list:
    return [match.group(0) for match in SPECIFIER.finditer(text)]


def arguments(text: str) -> list:
    return [s for s in specifiers(text) if not s.endswith("%")]


def positional(text: str) -> list:
    return [a[1:].split("$")[0] for a in arguments(text) if "$" in a]


def conversion(text: str) -> list:
    """The argument types with any index stripped, so English and Russian can be compared on what they ask for."""
    return [re.sub(r"^\d+\$", "%", a) for a in arguments(text)]


def check(source: dict, target: dict, target_categories: tuple, label: str) -> list:
    findings = []
    for name, entry in sorted(source.items()):
        if entry["type"] == "invalid":
            findings.append(f"invalid XML in the default set: {name}: {entry['error']}")
            continue
        if entry.get("translatable") is False:
            continue
        counterpart = target.get(name)
        if counterpart is None:
            findings.append(f"{name} has no {label} translation ({entry['where']})")
            continue
        if counterpart["type"] == "invalid":
            findings.append(f"invalid XML in the {label} set: {name}: {counterpart['error']}")
            continue
        if counterpart["type"] != entry["type"]:
            findings.append(
                f"{name} is a {entry['type']} in English and a {counterpart['type']} in {label}"
            )
            continue
        if entry["type"] == "string":
            findings += compare_value(name, entry["text"], counterpart.get("text", ""), target_categories)
        elif entry["type"] == "plurals":
            missing = [q for q in target_categories if q not in counterpart["quantities"]]
            if missing:
                findings.append(f"{name} is missing the {label} categor{'ies' if len(missing) > 1 else 'y'} {missing}")
            for quantity, english_text in entry["quantities"].items():
                russian_text = counterpart["quantities"].get(quantity)
                if russian_text is None:
                    continue
                findings += compare_value(f"{name}[{quantity}]", english_text, russian_text, target_categories)
            # The three categories a whole number reaches must differ; `other` is the fraction fallback and may
            # repeat any of them, which is the CLDR rule rather than an unfinished translation.
            forms = [counterpart["quantities"].get(q, "").strip() for q in ("one", "few", "many")]
            if all(forms) and len(set(forms)) < 3 and name not in IDENTICAL_FORMS_ALLOWED:
                findings.append(
                    f"{name}: the one/few/many forms of this {label} block repeat, so some counts read wrong "
                    f"({counterpart['where']})"
                )
        else:  # string-array
            if len(entry["quantities"]) != len(counterpart["quantities"]):
                findings.append(
                    f"{name}: the array holds {len(entry['quantities'])} items in English and "
                    f"{len(counterpart['quantities'])} in {label}"
                )
    for name in sorted(set(target) - set(source)):
        if target[name]["type"] != "invalid":
            findings.append(f"{name} exists only in {label}; nothing in the app can ask for it")
    return findings


def compare_value(name: str, english: str, translated: str, target_categories: tuple) -> list:
    findings = []
    if not translated.strip():
        findings.append(f"{name} is present in the translation but empty")
        return findings
    # A translation may put the arguments in a different order -- that is what positional markers are for -- so the
    # comparison is on the multiset of types, while the rule below still demands every argument be marked.
    if sorted(conversion(english)) != sorted(conversion(translated)):
        findings.append(
            f"{name}: English asks {conversion(english)}, the translation asks {conversion(translated)}"
        )
    for text, language in ((english, "English"), (translated, "translation")):
        if len(arguments(text)) > 1 and len(positional(text)) != len(arguments(text)):
            findings.append(
                f"{name}: {len(arguments(text))} arguments in the {language} form with no positional markers"
            )
    if english.count("%") != translated.count("%"):
        findings.append(
            f"{name}: {english.count('%')} literal percent signs in English, {translated.count('%')} in the "
            "translation"
        )
    if english.strip() == translated.strip() and re.search(r"[A-Za-zЀ-ӿ]", re.sub(SPECIFIER, "", english)):
        # A value whose only letters belong to a format specifier ("%1$s") is a joiner, not a sentence, so there
        # is nothing here for a translator to say differently in Russian.
        findings.append(f"{name}: the translation repeats the English text verbatim: {english.strip()[:60]!r}")
    return findings


def plural_selection(source: dict, target: dict) -> list:
    """Every count a Russian reader will meet picks a category the Russian block actually has."""
    findings = []
    for name, entry in sorted(target.items()):
        if entry["type"] != "plurals":
            continue
        for count in KEY_COUNTS:
            category = russian_category(count)
            if category not in entry["quantities"]:
                findings.append(f"{name}: the count {count} takes category \"{category}\", which the block does not have")
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--res", default="app/src/main/res", help="the module's res/ directory")
    args = parser.parse_args()
    res = Path(args.res)

    default = load(res / "values")
    russian = load(res / "values-ru")

    missing_directory = not (res / "values-ru").is_dir()
    findings = [] if missing_directory else check(default, russian, RUSSIAN_CATEGORIES, "Russian")

    # A pass has to mean two non-empty sets were compared. `values/` parsed to nothing — a moved directory, a
    # root tag this script does not read, a build that packaged no resources — used to print "0 missing" against
    # an equally empty `values-ru/` and exit 0. That is the one answer a completeness gate must never give.
    if not missing_directory and not default:
        print("  !! values/: no <string>, <plurals> or <string-array> entries parsed at all")
    if not missing_directory and not russian:
        print("  !! values-ru/: exists but holds no parsed entries")
    findings += plural_selection(default, russian) if russian else []

    strings = sum(1 for e in default.values() if e["type"] == "string")
    plurals = sum(1 for e in default.values() if e["type"] == "plurals")
    arrays = sum(1 for e in default.values() if e["type"] == "string-array")
    ru_strings = sum(1 for e in russian.values() if e["type"] == "string")
    ru_plurals = sum(1 for e in russian.values() if e["type"] == "plurals")
    ru_arrays = sum(1 for e in russian.values() if e["type"] == "string-array")

    print(f"English resources: {strings} strings, {plurals} plurals, {arrays} string-arrays")
    print(f"Russian resources: {ru_strings} strings, {ru_plurals} plurals, {ru_arrays} string-arrays")
    if missing_directory:
        print(f"{res}/values-ru does not exist: every reader of the default language is all the app can serve")
    print(f"Missing resources: {len([f for f in findings if 'no Russian translation' in f])}")
    print(f"Missing plural quantities: {len([f for f in findings if 'missing the Russian' in f])}")
    print(f"Placeholder mismatches: {len([f for f in findings if 'asks' in f or 'positional' in f or 'percent' in f])}")
    print(f"Invalid XML: {len([f for f in findings if 'invalid XML' in f])}")
    for finding in findings[:40]:
        print("  - " + finding)
    if len(findings) > 40:
        print(f"  ... and {len(findings) - 40} more")
    compared = bool(default) and bool(russian)
    passed = not findings and not missing_directory and compared
    print("RESULT: " + ("pass" if passed else "fail"))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
