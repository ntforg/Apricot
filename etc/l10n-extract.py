#!/usr/bin/env python3
"""Extract every translatable literal in the client into template dictionaries.

The client can already record what it failed to translate while you play, but
that only finds text you actually walked past. This reads the source instead,
so a translator gets the whole of Apricot's own interface up front -- every
settings panel, every tooltip -- without hunting for it.

Run from the repository root:

    python etc/l10n-extract.py

It writes English-to-English templates to Translations/_template/. Copy the
ones you want into Translations/<language>/ and replace the values. Leading
underscores keep _template out of the client's language menu.

Only literal strings are extracted. Text built at runtime out of variables
cannot be a dictionary key, so it is skipped rather than emitted broken.
"""

import io
import json
import os
import re
import sys

SRC = "src"
OUT = os.path.join("Translations", "_template")

# Call sites that reach a translated chokepoint, and the bundle each feeds.
# The pattern must leave the cursor just past the opening quote of the literal.
CALLS = [
    ("label",   r"new\s+Label\s*\(\s*"),
    ("label",   r"new\s+CheckBox\s*\(\s*"),
    ("label",   r"\.settext\s*\(\s*"),
    # The width argument is usually UI.scale(200), so parentheses have to be
    # allowed through; a comma is what ends it.
    ("button",  r"new\s+Button\s*\(\s*[^,]+?,\s*"),
    ("button",  r"new\s+PButton\s*\(\s*[^,]+?,\s*"),
    ("button",  r"\.change\s*\(\s*"),
    # Window captions: "super(Coord.z, "Title", ...)" and "new Window(sz, ...)".
    ("window",  r"new\s+Window\s*\(\s*[^,]+?,\s*"),
    ("window",  r"super\s*\(\s*(?:UI\.scale\s*\()?\s*(?:Coord|new\s+Coord|sz)[^,]*,\s*"),
    ("tooltip", r"\.settip\s*\(\s*"),
    # Text passed straight to the translator rather than through a widget.
    ("label",   r"L10N\.label\s*\(\s*"),
    ("tooltip", r"L10N\.tooltip\s*\(\s*"),
    ("msg",     r"L10N\.msg\s*\(\s*"),
    ("flower",  r"L10N\.flower\s*\(\s*"),
    ("tooltip", r"L10N\.richtip\s*\(\s*"),
    ("msg",     r"\.(?:msg|error)\s*\(\s*"),
]

# Text that is a placeholder rather than something to translate.
SKIP = re.compile(r"^\s*$|^[\W\d]+$|%[sdfn,.\d]")

JAVA_ESCAPES = {
    "n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f",
    '"': '"', "'": "'", "\\": "\\",
}


def strip_comments(text):
    """Remove // and /* */ comments without touching string literals."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def read_literal(text, i):
    """Read the Java string literal starting at text[i] == '"'.

    Returns (value, index just past the closing quote), or (None, i) if the
    literal does not terminate.
    """
    if i >= len(text) or text[i] != '"':
        return None, i
    buf = []
    i += 1
    while i < len(text):
        c = text[i]
        if c == "\\":
            nxt = text[i + 1] if i + 1 < len(text) else ""
            if nxt == "u":
                try:
                    buf.append(chr(int(text[i + 2:i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    return None, i
            buf.append(JAVA_ESCAPES.get(nxt, nxt))
            i += 2
            continue
        if c == '"':
            return "".join(buf), i + 1
        buf.append(c)
        i += 1
    return None, i


def read_concat(text, i):
    """Read a run of string literals joined by '+'.

    Returns the joined value, or None if anything other than a literal or a
    '+' appears -- a runtime-built string cannot serve as a dictionary key.
    """
    value, i = read_literal(text, i)
    if value is None:
        return None
    while True:
        j = i
        while j < len(text) and text[j] in " \t\r\n":
            j += 1
        if j >= len(text) or text[j] != "+":
            return value
        j += 1
        while j < len(text) and text[j] in " \t\r\n":
            j += 1
        if j >= len(text) or text[j] != '"':
            # Concatenated with something computed; not a usable key.
            return None
        more, i = read_literal(text, j)
        if more is None:
            return None
        value += more


def scan_pbutton(text, found):
    """Collect the optional last argument of a PButton.

    An options-screen button carries the caption its panel gives the window,
    which reaches a different bundle than the button's own label. It is the
    last argument, so the call has to be walked rather than pattern-matched.
    """
    for m in re.finditer(r"new\s+PButton\s*\(", text):
        i, depth, last = m.end(), 1, None
        while i < len(text) and depth > 0:
            c = text[i]
            if c == '"':
                value, j = read_literal(text, i)
                if value is None:
                    break
                if depth == 1:
                    last = value
                i = j
                continue
            if c in "([":
                depth += 1
            elif c in ")]":
                depth -= 1
            elif c == ";":
                break
            i += 1
        if last is None:
            continue
        last = last.strip()
        if not SKIP.match(last):
            found.setdefault("window", {})[last] = last


def scan(path, found):
    text = strip_comments(io.open(path, encoding="utf-8", errors="replace").read())
    scan_pbutton(text, found)
    for bundle, prefix in CALLS:
        for m in re.finditer(prefix + r'(?=")', text):
            value = read_concat(text, m.end())
            if value is None:
                continue
            # Captions padded to reserve room ("Options            ") are
            # matched trimmed by the client, so store the clean key.
            value = value.strip()
            if SKIP.match(value):
                continue
            found.setdefault(bundle, {})[value] = value


def main():
    if not os.path.isdir(SRC):
        sys.exit("run this from the repository root (no %s directory here)" % SRC)
    found = {}
    for root, dirs, names in os.walk(SRC):
        for name in names:
            if name.endswith(".java"):
                scan(os.path.join(root, name), found)

    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    total = 0
    for bundle in sorted(found):
        entries = found[bundle]
        total += len(entries)
        path = os.path.join(OUT, bundle + ".json")
        with io.open(path, "w", encoding="utf-8", newline="\n") as fp:
            fp.write("{\n")
            items = sorted(entries.items())
            for n, (k, v) in enumerate(items):
                fp.write("    %s: %s%s\n" % (
                    json.dumps(k, ensure_ascii=False),
                    json.dumps(v, ensure_ascii=False),
                    "," if n < len(items) - 1 else ""))
            fp.write("}\n")
        print("%-28s %4d entries" % (path, len(entries)))
    print("%d strings total" % total)


if __name__ == "__main__":
    main()
