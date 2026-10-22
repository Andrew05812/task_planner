# Report export: the csv, the pages, and where a file goes

Stage 3J Part 4 turns one `Report` into two files. The same figures go into both; only the
sentences differ. Nothing here reads the database, and nothing here recomputes a number —
`ReportRepository` answered the ten sections once, and every file below is those answers laid out.

```
ReportRepository  →  ReportLayout (sentences)   →  ReportPagination (pages)  →  PdfReport / PdfDocumentSurface
               

ReportRepository  →  CsvReport (rows)           →  ReportExportRepository     →  the picker's uri
                                                                            →  FileProvider + Sharesheet
```

## The csv

One column set runs from the first row to the last, so the file can be filtered without a
parser that learns a new table shape per section.

| Column | What holds it |
| --- | --- |
| `section` | `meta`, `summary`, `completed_tasks`, `worked_time`, `categories`, `goals`, `habits`, `dynamics`, `achievements`, `comparison`, `findings` — §6's ten, in order, with a `meta` block in front |
| `subject_id` | The identity the app gives the row for exactly this purpose: a goal's or habit's `uuid`, an achievement's `rule key`. Empty for the report itself, for a day row, and for a category — a category is named, because its numeric row id means nothing on another device |
| `subject` | The name the reader gave it, verbatim |
| `metric` | `completed_tasks`, `worked_seconds`, `finished`, `marks`, `kept_percent`, `progress`, `reached`, `moved_in_period`, … |
| `value` | The figure, in the unit named by `unit`. Absent means empty, never `0` and never a dash |
| `unit` | The unit the figure is stated in: `tasks`, `seconds`, `days`, `sessions`, `intervals`, `percent`, `share`, `flag`, `version`, `grants`, `items`, `rows`, `weeks`, `days` for a habit run. An achievement row names what *its own rule* counted — `tasks`, `goals`, `projects`, `habits`, `marks`, `sessions`, `streak_days`, `active_days`, `hours`, `other` — because the unit is stored on the grant rather than looked up from the rule that made it |
| `scope` | `period` (what happened in the window), `standing` (what is true the day the report was written), `previous` (the comparison window), `meta` |
| `day` | `uuuu-MM-dd`, the local day the row is about, in the report's own zone. Empty when the row is not about a day |
| `text` | Prose that belongs to the row: a category name, an achievement's stored description, an insight's paragraph, a note that explains why a figure is missing |

**Encoding and separators.** UTF-8, written with the byte-order mark first — that is how Excel on
Windows decides the file is UTF-8 rather than its own code page, and without it a Russian
category name arrives as mojibake. Fields are separated by `,` or `;`: Excel takes its list
separator from the locale, and a locale that writes `3,14` expects `;` between fields, so
`CsvReport.defaultDelimiter()` follows that rule and the delimiter is also a parameter, for a
caller writing the file for a parser instead of for a spreadsheet.

**Numbers and dates.** `Locale.ROOT`, no grouping separators, a full stop as the decimal mark,
and a measured duration in whole seconds — the figure the database holds, not a rounded
"4 h". Dates are ISO-8601. A report's own day-by-day lines are therefore sortable by any tool
that reads them, and a `31 июня` cannot appear.

**Quoting.** RFC 4180: a field is wrapped in double quotes when it holds the delimiter, a quote,
a line break, or a carriage return, and every quote inside it is doubled. Rows end with `CRLF`
and the file ends with a line break. No field is ever concatenated into a row without passing
through `CsvReport.quote`, which is the only reason a task titled `Work, "urgent"` is one field
rather than three.

**Formula injection.** A stored string that begins with `=`, `+`, `-` or `@` — or with whitespace
hiding which of those is first — is prefixed with a single apostrophe, the marker Excel and
LibreOffice read as "this is text" and do not display. The rest of the field is untouched, so the
reader's own words are all still there. The marker is applied only to fields that came from
stored text; a figure this app printed (`-5`, `0.333`) is left alone, because a minus sign on a
number is arithmetic rather than a formula. This is a visible change to one character of one
field, and it is the smaller of the two distortions available: the other is a spreadsheet that
runs a formula from a task title.

**What is not in the file.** No profile id, no numeric row id, no `sync_state`, no tombstone
flag, no token, no Firebase uid. A report is a document about work, and the columns that exist
to move rows between this app's own copies are nobody else's business.

## The PDF

A4 portrait (595 × 842 pt), 44 pt side margins, and the app's own typographic scale narrowed
for print: a 22 pt title, 13.5 pt section headings, 10.5 pt body, 9.5 pt table text, 8.5 pt
captions. The colours are fixed for paper — the accent for headings and rules, the app's ink for
text, its grey for qualifications, the green of a finished task for finished work — and are read
from `report_*` colour resources, so the light theme and the dark theme print the same page.

Layout is decided in `ReportPagination`, not in the drawer:

* Text is wrapped by measurement through `ReportMeasurer`, so a heading in Cyrillic and a heading
  in Latin are asked the same question.
* A paragraph may end wherever the paper runs out. A heading, a label with its figure, a table
  row and a chart may not.
* A table that continues is given its column titles again on the next sheet, with the hairline
  under them. A flow's first group always carries its header; a later group carries it whenever
  it opens a page.
* A row taller than a whole sheet is sliced between its own lines rather than drawn past the
  bottom, and every line of it arrives somewhere on the following sheets.
* A chart given a short page gives up plot height first — `ChartGeometry` normalises against the
  tallest value, so a shorter plot is the same chart — and below 40 pt it keeps its words and
  drops the picture.
* Every page numbers itself (`3 / 7`); every page after the first carries the report's title as a
  running header, shortened with an ellipsis only if it cannot fit, which hides nothing because
  the title is printed in full on the first sheet.

Charts are built only from the report's own figures: the day-by-day work and completions as two
single-series bar charts (the same pair the dashboard puts beside each other), and the categories'
share of measured time as one proportion bar with its parts listed. A series with nothing in it
gets a sentence instead of a chart.

**Cyrillic and emoji.** Text is drawn in the named `sans-serif` family, whose glyphs the platform's
writer embeds into the file, so `Привет`, `ё` and `Ё` travel with the document. Emoji are drawn
from a separate colour font whose glyphs are bitmaps and cannot join a text layer, so a picture
in the reader's own title is marked `[?]` in the PDF, once per run, and the report's closing
section says that it was. The csv keeps the emoji exactly as typed.

## Where a file goes

**Saving.** `ActivityResultContracts.CreateDocument(mimeType)` opens the system picker, and the
uri it returns is opened with `contentResolver.openOutputStream(uri, "wt")` — the same shape the
backup export already uses. The stream is closed before the export is called saved, and the four
ways it can end are four different answers: `Saved(uri, bytes)`, `Cancelled`, and `Failed` with
`NO_PROFILE`, `LOCATION_UNWRITABLE`, `WRITE_INTERRUPTED` or `EMPTY_FILE`. No storage permission is
requested, and nothing is ever written to a path the app made up.

**Naming.** `taskplanner-report-<date>.<ext>` in ASCII: a day names its whole date, a month its
year and month, a year its year, a week the day it starts on, a chosen range both ends. The
period sorts a folder of exports into the order the periods ran.

**Sharing.** A share needs a copy the app can grant over, so a staged report lives in
`cache/report_exports/` and its uri is minted by `FileProvider` at the moment the intent is
built, with `FLAG_GRANT_READ_URI_PERMISSION` so the right lasts as long as the hand-over does. The
provider's configuration is one rule — that directory — and not a path to the app's files or to
the database. The copy is not deleted when the share is handed over: a recipient that has not
opened it yet would find nothing. The directory is capped instead — the newest five staged exports
are kept and older ones are evicted when a new one lands — and `pruneStaged()` offers the same
sweep to a caller that wants it at start-up, which arrives with the report screen. No age limit is
applied: a timer would delete a file somebody is still opening.

Handing a file to the Sharesheet is the end of what this app can see. It is a hand-over, not a
delivery, and no screen promises more than "the file was given to the app you chose".

## Measured cost, and what is not measured here

On the JVM (Robolectric, medians of seven runs, a 512 MB test heap):

| Report | blocks | sheets | draws | csv | layout | paging | csv write |
| --- | --- | --- | --- | --- | --- | --- | --- |
| one day | — | 1–2 | — | 3.3 KB | — | — | — |
| a year, 40 categories | 196 | 22 | 3 007 | 190 KB | 41 ms | 10 ms | 11 ms |
| the fullest account: 366 days, 300 categories, 120 goals, 120 habits, 25 grants, 60 findings | 316 | 37 | 5 260 | 411 KB | 86 ms | 33 ms | 25 ms |

The ceilings the tests assert are absolute: 500 ms per pass and 4 MB per file. No ratio between
two JVM timings is asserted anywhere, because that measures the scheduler rather than the code.

These numbers are the JVM's, not a handset's. The half that only a device can answer is the
drawing: `PdfDocument` writes into native memory, its pages are rasterised by the platform's own
text engine, and Robolectric's implementation accepts every drawing call and produces a file of no
bytes at all. `PdfReportInstrumentedTest` covers that half and is run on a device or an emulator —
CI compiles it and does not run it.

## Needs a real device

1. Choose a place to save on a physical handset, with a Documents UI provider from the manufacturer
   rather than the sample one, and confirm the file opens afterwards.
2. Open the exported PDF in two independent viewers (the system viewer and a third-party one), and
   read the Cyrillic, the `ё`, the em dashes and the numbers in the tables.
3. Select text in the PDF and copy it: what comes out should be the report's own words, which is how
   a missing font embedding shows up.
4. Share a PDF to another app, and confirm the recipient can open it without this app holding any
   permission.
5. Report a year with three hundred categories, print it, and look at the sheet where a table
   continues: the column titles should be there again, and no value should stop at the edge of the
   paper.
6. Save a title with an emoji in it, and confirm the PDF marks it `[?]` and the csv keeps the emoji.
