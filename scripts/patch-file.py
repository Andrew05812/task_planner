"""Patch helper that respects each file's own line endings.

The repository is mixed: hand-written Kotlin added in this session is LF, while the XML resources and the older
sources are CRLF (`.gitattributes`-free checkout on Windows). A patch that assumes `\\n` silently misses every
anchor in a CRLF file, so this reads the bytes, learns the dominant ending, works in `\\n` internally and writes
back in whatever the file already used.

Usage: `python scripts/patch-file.py <edits.json>` where edits.json is a list of
`{"path": ..., "edits": [["old", "new"], ...]}`. Every anchor must appear exactly once or nothing is written.
"""
import io
import json
import os
import sys
import tempfile


def read(path):
    raw = io.open(path, 'rb').read()
    ending = '\r\n' if raw.count(b'\r\n') > raw.count(b'\n') // 2 else '\n'
    return raw.decode('utf-8').replace('\r\n', '\n').replace('\n', ending), ending


def write(path, text, ending):
    descriptor, temporary = tempfile.mkstemp(dir=os.path.dirname(path), suffix='.buildtmp')
    with os.fdopen(descriptor, 'wb') as handle:
        handle.write(text.encode('utf-8'))
    os.replace(temporary, path)


def main():
    plan = json.load(io.open(sys.argv[1], encoding='utf-8'))
    for step in plan:
        path = step['path']
        text, ending = read(path)
        for old, new in step['edits']:
            old = old.replace('\r\n', '\n').replace('\n', ending)
            new = new.replace('\r\n', '\n').replace('\n', ending)
            hits = text.count(old)
            if hits != 1:
                raise SystemExit('anchor %r matched %d times in %s; nothing was written' % (old[:60], hits, path))
            text = text.replace(old, new)
        write(path, text, ending)
        print('patched %s (%d edits, %r endings)' % (path, len(step['edits']), ending))


if __name__ == '__main__':
    main()
