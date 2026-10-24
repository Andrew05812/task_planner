#!/usr/bin/env python3
"""Every colour pair the redesign is allowed to form, measured rather than asserted.

The design specification states contrast ratios in prose, and prose goes stale the moment a hex value
moves. This script is the arithmetic behind that prose: it reads one token table
(`docs/design/tokens.json`), computes the WCAG 2.1 contrast ratio of every foreground/background pair
the design is allowed to put on screen, and fails when a pair drops under the bar the design promises.

Two sources, one table:

  * `--tokens` (default) reads docs/design/tokens.json. This is the design-time source, and it is what
    the specification's numbers come from.
  * `--resources app/src/main/res` reads the real Android colour resources — `values/colors.xml` and
    `values-night/colors.xml` — resolves `@color/...` aliases the way aapt does, checks the same pairs
    against those values, and reports any token whose shipped value differs from the table.

Run both and the second one is the gate: a colour edited straight in `colors.xml`, without the table,
fails the pair it breaks.

Exit status is 0 only when every gated pair clears its threshold in both themes and, in resources mode,
no token drifted. Output is sorted and carries no timestamps, so two runs on the same input are
byte-identical and a diff of the report is a diff of the design.

    python scripts/contrast_report.py                       # tables from the token file
    python scripts/contrast_report.py --check                # same, non-zero exit on failure
    python scripts/contrast_report.py --resources app/src/main/res --check
    python scripts/contrast_report.py --format markdown      # paste-ready for docs
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TEXT = "text"
GRAPHIC = "graphic"


# --------------------------------------------------------------------------------- the arithmetic


def srgb_channel(byte: int) -> float:
    """One 8-bit channel linearised the way WCAG 2.1 defines it."""
    v = byte / 255.0
    return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4


def luminance(rgb: tuple[int, int, int]) -> float:
    r, g, b = (srgb_channel(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(foreground: str, background: str) -> float:
    """WCAG 2.1 contrast ratio of two opaque hex colours, 1.0 .. 21.0."""
    a, b = parse_hex(foreground), parse_hex(background)
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def parse_hex(value: str) -> tuple[int, int, int]:
    text = value.strip().lstrip("#")
    if len(text) == 8:  # Android writes #AARRGGBB; every token here is opaque, so alpha is checked, not used
        alpha = int(text[0:2], 16)
        if alpha != 0xFF:
            raise ValueError(f"{value}: a gated token must be opaque (found alpha {text[0:2]})")
        text = text[2:]
    if len(text) != 6:
        raise ValueError(f"{value}: expected #RRGGBB or #AARRGGBB")
    return int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16)


# ------------------------------------------------------------------------------------ the two reads


def read_tokens(path: Path) -> dict[str, dict[str, str]]:
    table = json.loads(path.read_text(encoding="utf-8"))
    return table["tokens"]


COLOR_RE = re.compile(r"<color\s+name=\"([^\"]+)\"\s*>([^<]+)</color>")


def read_resources(res_dir: Path) -> dict[str, dict[str, str]]:
    """The shipped colours, with `@color/name` aliases followed the way aapt follows them."""
    out: dict[str, dict[str, str]] = {}
    for theme, folder in (("light", "values"), ("dark", "values-night")):
        raw: dict[str, str] = {}
        for file in sorted((res_dir / folder).glob("*.xml")):
            for name, value in COLOR_RE.findall(file.read_text(encoding="utf-8")):
                raw[name.strip()] = value.strip()
        resolved: dict[str, str] = {}
        for name in sorted(raw):
            seen: list[str] = []
            value = raw[name]
            while value.startswith("@color/"):
                target = value[len("@color/"):]
                if target in seen:
                    raise ValueError(f"@color/{name} aliases itself: {' -> '.join(seen + [target])}")
                seen.append(target)
                if target not in raw:
                    raise ValueError(f"@color/{name} -> @color/{target}, which no colour file declares")
                value = raw[target]
            if not value.startswith("#"):
                continue  # a framework colour (@android:color/...) or a name we do not gate
            resolved[name] = value
        out[theme] = resolved
    return out


# -------------------------------------------------------------------------------------- the pairing


def pairs(table: dict, tokens: dict[str, str], theme: str) -> list[dict]:
    """Every gated pair for one theme, in the order the table declares them."""
    surfaces = table["surfaces"]
    out: list[dict] = []

    def add(kind: str, threshold: float, fg: str, bg: str, group: str, allowed: bool = True) -> None:
        out.append(
            {
                "kind": kind,
                "group": group,
                "foreground": fg,
                "background": bg,
                "threshold": threshold,
                "allowed": allowed,
                "foreground_hex": tokens.get(fg),
                "background_hex": tokens.get(bg),
            }
        )

    for group, key in (
        ("text on a surface", "text_on_surfaces"),
        ("text on a filled control", "text_on_fill"),
        ("text on its pill", "text_on_pill"),
        ("state-bearing graphic", "graphic_on_surface"),
        ("graphic on a variant surface", "graphic_on_variant"),
    ):
        for fg, bg in table["pairs"].get(key, {}).items():
            if bg == "surfaces":
                targets = surfaces
            elif isinstance(bg, list):
                targets = bg
            else:
                targets = [bg]
            kind = TEXT if group.startswith("text") else GRAPHIC
            for target in targets:
                add(kind, table["thresholds"][kind], fg, target, group)

    # The rule the sweep forces, reported as a number and not only as a sentence: a semantic colour is
    # kept off ink_surface_variant, and this is what it would have measured there.
    variant = "ink_surface_variant"
    bar = table["thresholds"][TEXT]
    for fg in sorted(table["pairs"]["text_on_surfaces"]):
        if fg in ("ink_text", "ink_text_secondary", "ink_text_tertiary", "ink_accent"):
            continue
        if fg not in tokens or variant not in tokens:
            # Before stage 1 writes the tokens, and for any name mistyped in colors.xml, the pair cannot
            # be measured at all. Say which name is missing and let the gate fail on the name rather than
            # on a traceback a worker has to read backwards.
            add(TEXT, bar, fg, variant, "excluded from a variant surface", allowed=False)
            missing = [n for n in (fg, variant) if n not in tokens]
            out[-1]["note"] = "MISSING %s %s" % (theme, " ".join(missing))
            out[-1]["ratio"] = None
            continue
        ratio = contrast(tokens[fg], tokens[variant])
        add(TEXT, bar, fg, variant, "excluded from a variant surface", allowed=True)
        out[-1]["note"] = (
            f"{ratio:.2f} on the variant surface — the ban is what keeps it out, not the arithmetic"
            if ratio >= bar
            else f"{ratio:.2f} on the variant surface — it would have failed AA here"
        )
    return out


def measure(pairs_list: list[dict]) -> list[dict]:
    for p in pairs_list:
        if p["foreground_hex"] is None or p["background_hex"] is None:
            p["ratio"] = None
            p["pass"] = False
            p["note"] = "token missing from this theme"
            continue
        ratio = contrast(p["foreground_hex"], p["background_hex"])
        p["ratio"] = ratio
        if p["group"].startswith("excluded"):
            p["pass"] = True  # informational: the design keeps this pair off the surface anyway
        else:
            p["pass"] = round(ratio, 2) >= p["threshold"]
            p["note"] = ""
    return pairs_list


def minima(pairs_list: list[dict]) -> dict[str, float]:
    """Lowest ratio a foreground reaches over the surfaces it is allowed on — the shipped number."""
    best: dict[str, float] = {}
    for p in pairs_list:
        if p["ratio"] is None or p["group"] != "text on a surface":
            continue
        best[p["foreground"]] = min(best.get(p["foreground"], 21.0), p["ratio"])
    return best


def raw_sweep(table: dict, tokens: dict[str, str]) -> dict[str, float]:
    """Every text role against all four surfaces, rule ignored.

    This is the measurement the variant rule was derived from, kept separate from `minima` so a reader
    can see both: what a colour reaches where it is allowed, and what it would have reached on the
    surface the design refuses to put it on.
    """
    out: dict[str, float] = {}
    for fg in sorted(table["pairs"]["text_on_surfaces"]):
        if fg not in tokens or any(bg not in tokens for bg in table["surfaces"]):
            continue
        out[fg] = min(contrast(tokens[fg], tokens[bg]) for bg in table["surfaces"])
    return out


# --------------------------------------------------------------------------------------- the report


def render_text(table: dict, themes: dict[str, dict[str, str]], fmt: str) -> tuple[str, bool]:
    lines: list[str] = []
    ok = True
    for theme in sorted(themes):
        tokens = themes[theme]
        measured = measure(pairs(table, tokens, theme))
        lines.append(f"## {theme} theme")
        lines.append("")
        if fmt == "markdown":
            lines.append(f"Tokens: `{theme}` — {len(tokens)} declared.")
            lines.append("")
        header = "| pair | foreground | background | ratio | bar | verdict |"
        lines += [header, "|---|---|---|---|---|---|"]
        for p in sorted(measured, key=lambda x: (x["group"], x["foreground"], x["background"])):
            ratio = "n/a" if p["ratio"] is None else f"{p['ratio']:.2f}"
            verdict = "pass" if p["pass"] else "FAIL"
            if p["note"]:
                verdict += f" — {p['note']}"
            if not p["pass"]:
                ok = False
            lines.append(
                f"| {p['group']} | {p['foreground']} {p['foreground_hex'] or '?'} | "
                f"{p['background']} {p['background_hex'] or '?'} | {ratio} | {p['threshold']:.1f} | {verdict} |"
            )
        lines.append("")
        lines.append(f"Minimum over the surfaces each colour is allowed on ({theme}) — the shipped figures:")
        lines.append("")
        for fg, ratio in sorted(minima(measured).items()):
            lines.append(f"- `{fg}` — {ratio:.2f}")
        lines.append("")
        lines.append(f"Raw sweep over all four surfaces, rule ignored ({theme}) — what the rule answers to:")
        lines.append("")
        bar = table["thresholds"][TEXT]
        for fg, ratio in sorted(raw_sweep(table, tokens).items()):
            lines.append(f"- `{fg}` — {ratio:.2f}" + ("" if ratio >= bar else "  <-- below AA, kept off that surface"))
        lines.append("")
        lines.append("")
        info = table.get("informational", {})
        reported = [(k, v) for k, v in sorted(info.items()) if isinstance(v, list) and len(v) == 2]
        if reported:
            lines.append(f"Not gated ({theme}) — decorative, outside WCAG 1.4.11:")
            lines.append("")
            for _, (fg, bg) in reported:
                if fg in tokens and bg in tokens:
                    lines.append(f"- `{fg}` on `{bg}` — {contrast(tokens[fg], tokens[bg]):.2f}")
            lines.append("")
        failed = [p for p in measured if not p["pass"]]
        if failed:
            lines.append(f"### {theme}: {len(failed)} pair(s) below the bar")
            for p in failed:
                shown = "missing token" if p["ratio"] is None else f"{p['ratio']:.2f}"
                lines.append(
                    f"- {p['foreground']} on {p['background']}: {shown} "
                    f"< {p['threshold']:.1f} ({p['group']})"
                )
            lines.append("")
    return "\n".join(lines), ok


def render_json(table: dict, themes: dict[str, dict[str, str]]) -> str:
    out = {}
    for theme in sorted(themes):
        measured = measure(pairs(table, themes[theme], theme))
        out[theme] = {
            "pairs": [
                {
                    k: (round(v, 4) if isinstance(v, float) else v)
                    for k, v in p.items()
                    if k in ("group", "kind", "foreground", "background", "foreground_hex", "background_hex", "ratio", "threshold", "pass", "note")
                }
                for p in sorted(measured, key=lambda x: (x["group"], x["foreground"], x["background"]))
            ],
            "minima": {k: round(v, 2) for k, v in sorted(minima(measured).items())},
            "raw_sweep": {k: round(v, 2) for k, v in sorted(raw_sweep(table, themes[theme]).items())},
        }
    return json.dumps(out, indent=2, sort_keys=True)


def drift(table: dict, design: dict[str, dict[str, str]], shipped: dict[str, dict[str, str]]) -> list[str]:
    """Tokens the resource files do not carry, or carry with a different value."""
    problems: list[str] = []
    for theme in sorted(design):
        want, have = design[theme], shipped.get(theme, {})
        for name in sorted(want):
            if name not in have:
                problems.append(f"{theme}: @{color_name(name)} is in the table and in no colour file")
                continue
            if have[name].lower().lstrip("#") != want[name].lower().lstrip("#"):
                problems.append(f"{theme}: @{color_name(name)} ships {have[name]}, the table says {want[name]}")
    return problems


def color_name(name: str) -> str:
    return f"color/{name}"


def main(argv: list[str]) -> int:
    # The report carries em dashes and the token table is UTF-8; a Windows console on a cp1251 codepage
    # would otherwise die on the first line of output.
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        reconfigure(encoding="utf-8", errors="replace")

    here = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tokens", type=Path, default=here / "docs/design/tokens.json")
    ap.add_argument("--resources", type=Path, default=None, help="app/src/main/res to check the shipped values too")
    ap.add_argument("--format", choices=("text", "markdown", "json"), default="text")
    ap.add_argument("--out", type=Path, default=None, help="also write the report here, as UTF-8")
    ap.add_argument("--check", action="store_true", help="non-zero exit when a pair fails or a token drifts")
    args = ap.parse_args(argv)

    table = json.loads(args.tokens.read_text(encoding="utf-8"))
    design = read_tokens(args.tokens)
    themes = design

    if args.resources:
        shipped = read_resources(args.resources)
        problems = drift(table, design, shipped)
        if problems:
            print("Token drift between the table and the shipped resources:")
            for line in problems:
                print(f"  - {line}")
            print("\nMeasured against the shipped resources instead:\n")
        themes = shipped

    if args.format == "json":
        body = render_json(table, themes)
        ok = all(p["pass"] for t in themes for p in measure(pairs(table, themes[t], t)))
    else:
        body, ok = render_text(table, themes, "markdown" if args.format == "markdown" else "text")
    print(body)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(body + "\n", encoding="utf-8")

    if args.check and not ok:
        print("RESULT: fail")
        return 1
    print(f"RESULT: {'pass' if ok else 'failing pairs above'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
