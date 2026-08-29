#!/usr/bin/env python3
"""Emit the architecture baseline metrics for the restructuring plan.

Scans production Java sources (api, core, fabric main/client, gpu-probe) and prints:
  1. a markdown table of every production class above the warn threshold (600 lines);
  2. the production class count and total line count per module.

The output feeds docs/maintenance/architecture-baseline.md. It only reads files; run from the
repository root:

    uv run pytool/arch_baseline.py
"""

from __future__ import annotations

import sys
from pathlib import Path

WARN_LINES = 600
SIZE_LIMIT = 1000
SOURCE_ROOTS = (
    "api/src/main/java",
    "core/src/main/java",
    "fabric/src/main/java",
    "fabric/src/client/java",
    "gpu-probe/src/main/java",
)


def scan(root: Path) -> list[tuple[str, str, int]]:
    rows: list[tuple[str, str, int]] = []
    for relative in SOURCE_ROOTS:
        source_root = root / relative
        if not source_root.is_dir():
            continue
        for path in sorted(source_root.rglob("*.java")):
            lines = len(path.read_text(encoding="utf-8").splitlines())
            rows.append((relative, path.stem, lines))
    return rows


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    rows = scan(root)
    if not rows:
        print("no production sources found", file=sys.stderr)
        return 1

    print("### Classes above the %d-line warning threshold" % WARN_LINES)
    print()
    print("| 模块 | 类 | 行数 | 责任切片 |")
    print("|---|---|---:|---|")
    over_limit = 0
    for module, name, lines in sorted(rows, key=lambda row: -row[2]):
        if lines <= WARN_LINES:
            continue
        marker = " 超限" if lines > SIZE_LIMIT else ""
        over_limit += lines > SIZE_LIMIT
        print(f"| {module} | {name} | {lines} | S00 allowlist{marker} |")

    print()
    print("### Module totals")
    print()
    print("| 模块 | 文件 | 行数 |")
    print("|---|---:|---:|")
    for module in SOURCE_ROOTS:
        subset = [row for row in rows if row[0] == module]
        if subset:
            print(f"| {module} | {len(subset)} | {sum(row[2] for row in subset)} |")
    print()
    print(f"classes over the {SIZE_LIMIT}-line hard limit: {over_limit}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
