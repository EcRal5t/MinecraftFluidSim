"""Compare complete-step benchmark CSVs, preserving warmup and trajectory checks."""

import argparse
import csv
import glob
import math
import statistics


def load(pattern):
    paths = sorted(glob.glob(pattern))
    if not paths:
        raise ValueError(f"No CSV files match {pattern}")
    runs = []
    for path in paths:
        with open(path, encoding="utf-8-sig", newline="") as source:
            rows = list(csv.DictReader(source))
        if not rows:
            raise ValueError(f"Empty benchmark: {path}")
        runs.append((path, rows))
    return runs


def metrics(runs):
    rows = [row for _, run in runs for row in run if row["measured"].lower() == "true"]
    if not rows:
        raise ValueError("Benchmark has no measured steps")
    times = sorted(float(row["total_ms"]) for row in rows)
    return [
        len(rows),
        statistics.mean(times),
        times[math.ceil(len(times) * 0.95) - 1],
        statistics.mean(int(row["allocated_bytes"]) for row in rows) / 1048576,
    ]


def trajectory(rows):
    columns = ("step", "measured", "markers", "substeps", "pressure_iterations", "residual")
    if "pcg" in rows[0]:
        columns = ("step", "measured", "markers", "substeps", "pcg", "motion_calls", "thermal_calls",
                   "idle_regions", "active_parcels", "deposit_groups", "chemistry_changed", "precipitations",
                   "settled", "woken", "coupled_markers", "impulse_norm", "solid_svq", "energy_dyadic")
    return [tuple(row[column] for column in columns) for row in rows]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, help="Glob for baseline CSVs")
    parser.add_argument("--after", required=True, help="Glob for optimized CSVs")
    parser.add_argument("--check-trajectory", action="store_true",
                        help="Require identical per-step marker/substep/PCG diagnostics in every run")
    args = parser.parse_args()
    before, after = load(args.before), load(args.after)
    print("| Run | Samples | Mean ms | P95 ms | MiB/step |")
    print("|---|---:|---:|---:|---:|")
    values = []
    for label, runs in (("Before", before), ("After", after)):
        value = metrics(runs)
        values.append(value)
        print(f"| {label} | {value[0]} | " + " | ".join(f"{x:.3f}" for x in value[1:]) + " |")
    print(f"Mean reduction: {(1 - values[1][1] / values[0][1]) * 100:.2f}%")
    for label, runs in (("Before", before), ("After", after)):
        means = [metrics([run])[1] for run in runs]
        print(f"{label} independent JVM means (ms): " + ", ".join(f"{value:.3f}" for value in means))
        rows = [row for _, run in runs for row in run if row["measured"].lower() == "true"]
        stages = []
        for column in ("capture_ms", "evaluate_ms", "restore_ms", "snapshot_ms", "mesh_ms"):
            if all(column in row for row in rows):
                value = statistics.mean(float(row[column]) for row in rows)
                if value > 0:
                    stages.append(f"{column}={value:.3f}")
        if stages:
            print(f"{label} stage means: " + ", ".join(stages))
    if args.check_trajectory:
        reference = trajectory(before[0][1])
        for path, rows in before + after:
            if trajectory(rows) != reference:
                raise ValueError(f"Trajectory diagnostics differ: {path}")
        print(f"Trajectory diagnostics identical across {len(before) + len(after)} runs, "
              f"{len(reference)} steps each. Full-state equality requires separate state digests.")


if __name__ == "__main__":
    main()
