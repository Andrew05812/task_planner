## dark theme

Tokens: `dark` — 20 declared.

| pair | foreground | background | ratio | bar | verdict |
|---|---|---|---|---|---|
| excluded from a variant surface | ink_error #F0836F | ink_surface_variant #26231F | 6.09 | 4.5 | pass — 6.09 on the variant surface — the ban is what keeps it out, not the arithmetic |
| excluded from a variant surface | ink_success #7CC98F | ink_surface_variant #26231F | 7.90 | 4.5 | pass — 7.90 on the variant surface — the ban is what keeps it out, not the arithmetic |
| excluded from a variant surface | ink_warning #E0A95F | ink_surface_variant #26231F | 7.46 | 4.5 | pass — 7.46 on the variant surface — the ban is what keeps it out, not the arithmetic |
| graphic on a variant surface | ink_outline_strong #7A736A | ink_surface_variant #26231F | 3.34 | 3.0 | pass |
| state-bearing graphic | ink_accent #6FD3B8 | ink_surface #1A1817 | 9.85 | 3.0 | pass |
| state-bearing graphic | ink_error #F0836F | ink_surface #1A1817 | 6.89 | 3.0 | pass |
| state-bearing graphic | ink_outline_strong #7A736A | ink_surface #1A1817 | 3.78 | 3.0 | pass |
| state-bearing graphic | ink_warning #E0A95F | ink_surface #1A1817 | 8.43 | 3.0 | pass |
| text on a filled control | ink_on_accent #0E201B | ink_accent #6FD3B8 | 9.42 | 4.5 | pass |
| text on a filled control | ink_on_error #2B0F0A | ink_error #F0836F | 6.96 | 4.5 | pass |
| text on a surface | ink_accent #6FD3B8 | ink_background #12100F | 10.56 | 4.5 | pass |
| text on a surface | ink_accent #6FD3B8 | ink_surface #1A1817 | 9.85 | 4.5 | pass |
| text on a surface | ink_accent #6FD3B8 | ink_surface_raised #221F1D | 9.12 | 4.5 | pass |
| text on a surface | ink_accent #6FD3B8 | ink_surface_variant #26231F | 8.70 | 4.5 | pass |
| text on a surface | ink_error #F0836F | ink_background #12100F | 7.39 | 4.5 | pass |
| text on a surface | ink_error #F0836F | ink_surface #1A1817 | 6.89 | 4.5 | pass |
| text on a surface | ink_error #F0836F | ink_surface_raised #221F1D | 6.38 | 4.5 | pass |
| text on a surface | ink_success #7CC98F | ink_background #12100F | 9.59 | 4.5 | pass |
| text on a surface | ink_success #7CC98F | ink_surface #1A1817 | 8.94 | 4.5 | pass |
| text on a surface | ink_success #7CC98F | ink_surface_raised #221F1D | 8.28 | 4.5 | pass |
| text on a surface | ink_text #F2EDE7 | ink_background #12100F | 16.30 | 4.5 | pass |
| text on a surface | ink_text #F2EDE7 | ink_surface #1A1817 | 15.20 | 4.5 | pass |
| text on a surface | ink_text #F2EDE7 | ink_surface_raised #221F1D | 14.08 | 4.5 | pass |
| text on a surface | ink_text #F2EDE7 | ink_surface_variant #26231F | 13.44 | 4.5 | pass |
| text on a surface | ink_text_secondary #A39B92 | ink_background #12100F | 6.92 | 4.5 | pass |
| text on a surface | ink_text_secondary #A39B92 | ink_surface #1A1817 | 6.45 | 4.5 | pass |
| text on a surface | ink_text_secondary #A39B92 | ink_surface_raised #221F1D | 5.98 | 4.5 | pass |
| text on a surface | ink_text_secondary #A39B92 | ink_surface_variant #26231F | 5.71 | 4.5 | pass |
| text on a surface | ink_text_tertiary #968D84 | ink_background #12100F | 5.82 | 4.5 | pass |
| text on a surface | ink_text_tertiary #968D84 | ink_surface #1A1817 | 5.43 | 4.5 | pass |
| text on a surface | ink_text_tertiary #968D84 | ink_surface_raised #221F1D | 5.02 | 4.5 | pass |
| text on a surface | ink_text_tertiary #968D84 | ink_surface_variant #26231F | 4.80 | 4.5 | pass |
| text on a surface | ink_warning #E0A95F | ink_background #12100F | 9.04 | 4.5 | pass |
| text on a surface | ink_warning #E0A95F | ink_surface #1A1817 | 8.43 | 4.5 | pass |
| text on a surface | ink_warning #E0A95F | ink_surface_raised #221F1D | 7.81 | 4.5 | pass |
| text on its pill | ink_accent #6FD3B8 | pill_accent #26322E | 7.40 | 4.5 | pass |
| text on its pill | ink_error #F0836F | pill_error #382723 | 5.52 | 4.5 | pass |
| text on its pill | ink_success #7CC98F | pill_success #283128 | 6.80 | 4.5 | pass |
| text on its pill | ink_text_secondary #A39B92 | pill_neutral #26231F | 5.71 | 4.5 | pass |
| text on its pill | ink_warning #E0A95F | pill_warning #362C21 | 6.50 | 4.5 | pass |

Minimum over the surfaces each colour is allowed on (dark) — the shipped figures:

- `ink_accent` — 8.70
- `ink_error` — 6.38
- `ink_success` — 8.28
- `ink_text` — 13.44
- `ink_text_secondary` — 5.71
- `ink_text_tertiary` — 4.80
- `ink_warning` — 7.81

Raw sweep over all four surfaces, rule ignored (dark) — what the rule answers to:

- `ink_accent` — 8.70
- `ink_error` — 6.09
- `ink_success` — 7.90
- `ink_text` — 13.44
- `ink_text_secondary` — 5.71
- `ink_text_tertiary` — 4.80
- `ink_warning` — 7.46


Not gated (dark) — decorative, outside WCAG 1.4.11:

- `ink_outline` on `ink_surface` — 1.30

## light theme

Tokens: `light` — 20 declared.

| pair | foreground | background | ratio | bar | verdict |
|---|---|---|---|---|---|
| excluded from a variant surface | ink_error #C0402F | ink_surface_variant #F1EDE7 | 4.48 | 4.5 | pass — 4.48 on the variant surface — it would have failed AA here |
| excluded from a variant surface | ink_success #2F7A4B | ink_surface_variant #F1EDE7 | 4.49 | 4.5 | pass — 4.49 on the variant surface — it would have failed AA here |
| excluded from a variant surface | ink_warning #8F5B18 | ink_surface_variant #F1EDE7 | 4.90 | 4.5 | pass — 4.90 on the variant surface — the ban is what keeps it out, not the arithmetic |
| graphic on a variant surface | ink_outline_strong #8C8378 | ink_surface_variant #F1EDE7 | 3.20 | 3.0 | pass |
| state-bearing graphic | ink_accent #2F6F5E | ink_surface #FFFFFF | 5.91 | 3.0 | pass |
| state-bearing graphic | ink_error #C0402F | ink_surface #FFFFFF | 5.23 | 3.0 | pass |
| state-bearing graphic | ink_outline_strong #8C8378 | ink_surface #FFFFFF | 3.73 | 3.0 | pass |
| state-bearing graphic | ink_warning #8F5B18 | ink_surface #FFFFFF | 5.71 | 3.0 | pass |
| text on a filled control | ink_on_accent #FFFFFF | ink_accent #2F6F5E | 5.91 | 4.5 | pass |
| text on a filled control | ink_on_error #FFFFFF | ink_error #C0402F | 5.23 | 4.5 | pass |
| text on a surface | ink_accent #2F6F5E | ink_background #FBF9F6 | 5.62 | 4.5 | pass |
| text on a surface | ink_accent #2F6F5E | ink_surface #FFFFFF | 5.91 | 4.5 | pass |
| text on a surface | ink_accent #2F6F5E | ink_surface_raised #FFFDFB | 5.82 | 4.5 | pass |
| text on a surface | ink_accent #2F6F5E | ink_surface_variant #F1EDE7 | 5.06 | 4.5 | pass |
| text on a surface | ink_error #C0402F | ink_background #FBF9F6 | 4.98 | 4.5 | pass |
| text on a surface | ink_error #C0402F | ink_surface #FFFFFF | 5.23 | 4.5 | pass |
| text on a surface | ink_error #C0402F | ink_surface_raised #FFFDFB | 5.15 | 4.5 | pass |
| text on a surface | ink_success #2F7A4B | ink_background #FBF9F6 | 4.99 | 4.5 | pass |
| text on a surface | ink_success #2F7A4B | ink_surface #FFFFFF | 5.24 | 4.5 | pass |
| text on a surface | ink_success #2F7A4B | ink_surface_raised #FFFDFB | 5.17 | 4.5 | pass |
| text on a surface | ink_text #1A1614 | ink_background #FBF9F6 | 17.10 | 4.5 | pass |
| text on a surface | ink_text #1A1614 | ink_surface #FFFFFF | 17.97 | 4.5 | pass |
| text on a surface | ink_text #1A1614 | ink_surface_raised #FFFDFB | 17.71 | 4.5 | pass |
| text on a surface | ink_text #1A1614 | ink_surface_variant #F1EDE7 | 15.41 | 4.5 | pass |
| text on a surface | ink_text_secondary #6B635C | ink_background #FBF9F6 | 5.61 | 4.5 | pass |
| text on a surface | ink_text_secondary #6B635C | ink_surface #FFFFFF | 5.89 | 4.5 | pass |
| text on a surface | ink_text_secondary #6B635C | ink_surface_raised #FFFDFB | 5.81 | 4.5 | pass |
| text on a surface | ink_text_secondary #6B635C | ink_surface_variant #F1EDE7 | 5.05 | 4.5 | pass |
| text on a surface | ink_text_tertiary #6E655D | ink_background #FBF9F6 | 5.43 | 4.5 | pass |
| text on a surface | ink_text_tertiary #6E655D | ink_surface #FFFFFF | 5.70 | 4.5 | pass |
| text on a surface | ink_text_tertiary #6E655D | ink_surface_raised #FFFDFB | 5.62 | 4.5 | pass |
| text on a surface | ink_text_tertiary #6E655D | ink_surface_variant #F1EDE7 | 4.89 | 4.5 | pass |
| text on a surface | ink_warning #8F5B18 | ink_background #FBF9F6 | 5.43 | 4.5 | pass |
| text on a surface | ink_warning #8F5B18 | ink_surface #FFFFFF | 5.71 | 4.5 | pass |
| text on a surface | ink_warning #8F5B18 | ink_surface_raised #FFFDFB | 5.63 | 4.5 | pass |
| text on its pill | ink_accent #2F6F5E | pill_accent #EEF3F2 | 5.27 | 4.5 | pass |
| text on its pill | ink_error #C0402F | pill_error #FAF0EE | 4.67 | 4.5 | pass |
| text on its pill | ink_success #2F7A4B | pill_success #EEF4F1 | 4.70 | 4.5 | pass |
| text on its pill | ink_text_secondary #6B635C | pill_neutral #F1EDE7 | 5.05 | 4.5 | pass |
| text on its pill | ink_warning #8F5B18 | pill_warning #F6F2ED | 5.12 | 4.5 | pass |

Minimum over the surfaces each colour is allowed on (light) — the shipped figures:

- `ink_accent` — 5.06
- `ink_error` — 4.98
- `ink_success` — 4.99
- `ink_text` — 15.41
- `ink_text_secondary` — 5.05
- `ink_text_tertiary` — 4.89
- `ink_warning` — 5.43

Raw sweep over all four surfaces, rule ignored (light) — what the rule answers to:

- `ink_accent` — 5.06
- `ink_error` — 4.48  <-- below AA, kept off that surface
- `ink_success` — 4.49  <-- below AA, kept off that surface
- `ink_text` — 15.41
- `ink_text_secondary` — 5.05
- `ink_text_tertiary` — 4.89
- `ink_warning` — 4.90


Not gated (light) — decorative, outside WCAG 1.4.11:

- `ink_outline` on `ink_surface` — 1.35

