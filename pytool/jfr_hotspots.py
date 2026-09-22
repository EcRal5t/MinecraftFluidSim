"""Aggregate JFR execution samples into self-time and inclusive-time method tables.

Usage:
    uv run pytool/jfr_hotspots.py <recording.jfr> [--top N] [--module cc.ecisr.mmm]

The JDK `jfr` tool is resolved from `JAVA_HOME`, with the repository's documented local JDK
as the fallback.
Only `jdk.ExecutionSample` / `jdk.NativeMethodSample` events are read; every sample
contributes one inclusive hit to every frame of its stack and one self hit to the leaf.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

DEFAULT_JDK = Path(os.environ.get("JAVA_HOME", r"D:\ProgramFiles\BienCreng\jdk-26.0.2"))
DEFAULT_JFR = str(DEFAULT_JDK / "bin" / "jfr.exe")
FRAME = re.compile(r"^\s{4}(\S.*?)(?:\s+line:\s*\d+)?$")
EVENT = re.compile(r"^jdk\.(?:ExecutionSample|NativeMethodSample)\s\{$")


def collect(jfr_tool: str, recording: Path) -> list[list[str]]:
    text = subprocess.run(
        [jfr_tool, "print", "--events", "ExecutionSample,NativeMethodSample", str(recording)],
        check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    stacks: list[list[str]] = []
    inside = False
    frames: list[str] = []
    for line in text.splitlines():
        if EVENT.match(line):
            inside = True
            frames = []
            continue
        if not inside:
            continue
        if line.strip() == "}":
            inside = False
            if frames:
                stacks.append(frames)
            continue
        if line.strip().startswith("...") or ":" in line and not line.startswith("    "):
            continue
        match = FRAME.match(line)
        if match:
            frames.append(match.group(1).strip())
    return stacks


def tally(stacks: list[list[str]], module: str) -> tuple[Counter, Counter]:
    self_hits: Counter = Counter()
    inclusive_hits: Counter = Counter()
    for frames in stacks:
        if frames and module in frames[0]:
            self_hits[frames[0]] += 1
        for frame in frames:
            if module in frame:
                inclusive_hits[frame] += 1
    return self_hits, inclusive_hits


def show_stacks(stacks: list[list[str]], pattern: str, top: int) -> None:
    """Print representative stacks (leaf first) that contain `pattern`."""
    print(f"\n== stacks containing {pattern!r} ==")
    shown = 0
    for frames in stacks:
        depth = next((i for i, frame in enumerate(frames) if pattern in frame), None)
        if depth is None:
            continue
        print(f"  -- sample {shown + 1} (match at depth {depth}) --")
        for frame in frames[: min(len(frames), depth + 6)]:
            print(f"    {frame}")
        shown += 1
        if shown >= top:
            break


def neighbours(stacks: list[list[str]], pattern: str, direction: str, top: int) -> None:
    """Caller/callee split for every sample whose stack contains `pattern`."""
    hits: Counter = Counter()
    for frames in stacks:
        for depth, frame in enumerate(frames):
            if pattern not in frame:
                continue
            index = depth + 1 if direction == "caller" else depth - 1
            if 0 <= index < len(frames):
                hits[frames[index]] += 1
            break
    print(f"\n== samples containing {pattern!r} -> {direction} ({sum(hits.values())}) ==")
    for name, count in hits.most_common(top):
        print(f"  {count:7d}  {name}")


def report(title: str, hits: Counter, top: int, module: str) -> None:
    rows = [(name, count) for name, count in hits.items() if module in name]
    rows.sort(key=lambda row: -row[1])
    total = sum(count for _, count in rows)
    print(f"\n== {title} (module filter {module!r}, {total} hits) ==")
    for name, count in rows[:top]:
        print(f"  {count:7d}  {name}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("recording", type=Path)
    parser.add_argument("--top", type=int, default=25)
    parser.add_argument("--module", default="cc.ecisr.mmm")
    parser.add_argument("--callers", metavar="PATTERN",
                        help="split the samples containing PATTERN by their immediate caller")
    parser.add_argument("--callees", metavar="PATTERN",
                        help="split the samples containing PATTERN by their immediate callee")
    parser.add_argument("--stacks", metavar="PATTERN",
                        help="print representative leaf-first stacks that contain PATTERN")
    parser.add_argument("--jfr-tool", default=DEFAULT_JFR)
    args = parser.parse_args()
    stacks = collect(args.jfr_tool, args.recording)
    if args.callers:
        neighbours(stacks, args.callers, "caller", args.top)
        return 0
    if args.callees:
        neighbours(stacks, args.callees, "callee", args.top)
        return 0
    if args.stacks:
        show_stacks(stacks, args.stacks, args.top)
        return 0
    self_hits, inclusive_hits = tally(stacks, args.module)
    report("self time (leaf frames)", self_hits, args.top, args.module)
    report("inclusive time (all frames)", inclusive_hits, args.top, args.module)
    return 0


if __name__ == "__main__":
    sys.exit(main())
