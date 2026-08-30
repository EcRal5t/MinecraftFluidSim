"""Reports imported simple names that no longer appear anywhere in a Java source body.

Usage: uv run pytool/unused_imports.py <file.java> [...]
Registered in docs/maintenance/pytool.md.
"""
import re
import sys
import pathlib

IMPORT = re.compile(r"^import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;", re.MULTILINE)


def simple_name(imported):
    if imported.endswith(".*"):
        return None
    return imported.rsplit(".", 1)[-1]


def main(paths):
    exit_code = 0
    for raw in paths:
        path = pathlib.Path(raw)
        text = path.read_text(encoding="utf-8")
        body = IMPORT.sub("", text)
        for imported in IMPORT.findall(text):
            name = simple_name(imported)
            if name is None:
                continue
            if not re.search(r"\b" + re.escape(name) + r"\b", body):
                print(f"{path}: unused import {imported}")
                exit_code = 1
    return exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
