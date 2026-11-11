"""Check relative links and anchors in the repository Markdown documents.

Usage: uv run pytool/doc_links.py [path ...]   (defaults to docs/ and README.md)
Reports missing target files and missing heading or <a id> anchors.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*$", re.M)
ANCHOR = re.compile(r"<a id=\"([^\"]+)\"")


def slug(text):
    text = re.sub(r"`|\*\*|\[|\]\([^)]*\)", "", text).strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def anchors(path, cache={}):
    if path not in cache:
        text = path.read_text(encoding="utf-8")
        cache[path] = {slug(h) for h in HEADING.findall(text)} | set(ANCHOR.findall(text))
    return cache[path]


def check(md):
    problems = []
    text = md.read_text(encoding="utf-8")
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    for target in LINK.findall(text):
        if re.match(r"[a-z]+:", target):
            continue
        file_part, _, anchor = target.partition("#")
        dest = (md.parent / file_part).resolve() if file_part else md
        if not dest.exists():
            problems.append(f"{md.relative_to(ROOT)}: missing file {target}")
        elif anchor and dest.suffix == ".md" and anchor not in anchors(dest) and slug(anchor) not in anchors(dest):
            problems.append(f"{md.relative_to(ROOT)}: missing anchor {target}")
    return problems


def main(args):
    paths = [Path(a) for a in args] or [ROOT / "docs", ROOT / "README.md"]
    files = [f for p in paths for f in ([p] if p.is_file() else sorted(p.rglob("*.md")))]
    problems = [p for f in files for p in check(f.resolve())]
    print("\n".join(problems) or f"{len(files)} files, all links resolve")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
