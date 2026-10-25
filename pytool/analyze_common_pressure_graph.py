# /// script
# requires-python = ">=3.13"
# dependencies = ["numpy", "scipy"]
# ///
"""Describe a saved common pressure graph for solver cost and coarsening decisions."""

import argparse
import json
from pathlib import Path
import struct

import numpy as np
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components


def load(path):
    data = Path(path).read_bytes()
    magic, sx, sy, sz, n, gas, nnz, edges, dt, size = struct.unpack_from("<8s7i2d", data)
    if magic != b"MMMPRE01":
        raise ValueError("Unsupported common pressure dump")
    cursor = 52
    result = dict(shape=[sx, sy, sz], n=n, gas=gas, dt=dt, cell_size=size)
    for name, count, dtype in [
        ("starts", n + 1, "<i4"), ("columns", nnz, "<i4"), ("values", nnz, "<f8"),
        ("rows", n, "<i4"), ("bounded", n, "<i4"), ("free", n, "<i4"),
        ("rhs", n, "<f8"), ("pressure", n, "<f8"), ("lower", n, "<f8"), ("warm", n, "<f8"),
        ("gas_offsets", gas + 1, "<i4"), ("gas_peers", edges, "<i4"), ("gas_weights", edges, "<f8"),
        ("gas_storage", gas, "<f8"), ("gas_diagonal", gas, "<f8"),
    ]:
        result[name] = np.frombuffer(data, dtype=dtype, count=count, offset=cursor)
        cursor += count * np.dtype(dtype).itemsize
    if cursor != len(data):
        raise ValueError(f"Dump size differs: {cursor} != {len(data)}")
    return result


def quantiles(values):
    return {str(q): float(np.quantile(values, q)) for q in [0, .1, .5, .9, .99, 1]} if len(values) else {}


def component_sizes(matrix):
    count, labels = connected_components(matrix, directed=False)
    sizes = np.bincount(labels)
    return {"count": int(count), "largest": np.sort(sizes)[-16:][::-1].tolist()}


def analyze(g):
    n = g["n"]
    liquid = int(np.prod(g["shape"]))
    enabled = g["rows"] >= 0
    active = np.flatnonzero(enabled)
    mapping = np.full(n, -1, dtype=np.int32)
    mapping[active] = np.arange(len(active))
    end = int(g["starts"][liquid])
    lr = np.repeat(np.arange(liquid), np.diff(g["starts"][:liquid + 1]))
    lc, lv = g["columns"][:end], g["values"][:end]
    gr = np.repeat(np.arange(g["gas"]) + liquid, np.diff(g["gas_offsets"]))
    finite = g["gas_peers"] >= 0
    rows = np.r_[lr, np.arange(g["gas"]) + liquid, gr[finite]]
    cols = np.r_[lc, np.arange(g["gas"]) + liquid, g["gas_peers"][finite]]
    vals = np.r_[lv, g["gas_diagonal"], -g["gas_weights"][finite]]
    selected = (vals != 0) & enabled[rows] & enabled[cols]
    matrix = coo_matrix((vals[selected], (mapping[rows[selected]], mapping[cols[selected]])),
                        shape=(len(active), len(active))).tocsr()
    matrix.sum_duplicates()
    diagonal = matrix.diagonal()
    off = matrix.copy()
    off.setdiag(0)
    off.eliminate_zeros()
    coo = off.tocoo()
    strength = np.abs(coo.data) / np.sqrt(diagonal[coo.row] * diagonal[coo.col])
    absolute_rows = np.asarray(abs(off).sum(axis=1)).ravel()
    free = g["free"][active] != 0
    free_graph = off[free][:, free]
    active_liquid = np.flatnonzero(enabled[:liquid])
    ll = off[:len(active_liquid), :len(active_liquid)].tocoo()
    splits = {}
    sx, sy, sz = g["shape"]
    coordinates = np.stack([active_liquid % sx, active_liquid // sx % sy, active_liquid // (sx * sy)], axis=1)
    for shift in range(1, 5):
        coarse_shape = (np.array([sx, sy, sz]) + (1 << shift) - 1) >> shift
        c = coordinates >> shift
        groups = (c[:, 2] * coarse_shape[1] + c[:, 1]) * coarse_shape[0] + c[:, 0]
        inside = groups[ll.row] == groups[ll.col]
        local = coo_matrix((np.ones(np.count_nonzero(inside)), (ll.row[inside], ll.col[inside])), shape=ll.shape).tocsr()
        _, labels = connected_components(local, directed=False)
        pairs = np.unique(np.stack([groups, labels], axis=1), axis=0)
        _, pieces = np.unique(pairs[:, 0], return_counts=True)
        splits[str(1 << shift)] = {"nonempty_aggregates": len(pieces), "disconnected_aggregates": int(np.count_nonzero(pieces > 1)),
                                   "components_per_aggregate": quantiles(pieces)}
    carriers = {}
    for name, lower, upper in [("liquid", 0, liquid), ("gas", liquid, n)]:
        mask = enabled[lower:upper]
        bounded = g["bounded"][lower:upper] != 0
        free = g["free"][lower:upper] != 0
        carriers[name] = {"active_rows": int(mask.sum()), "bounded_rows": int((mask & bounded).sum()),
                          "free_rows": int((mask & free).sum()),
                          "at_lower": int((mask & bounded & (g["pressure"][lower:upper] == g["lower"][lower:upper])).sum())}
    split = len(active_liquid)
    carriers["liquid"]["liquid_neighbors"] = quantiles(np.diff(off[:split, :split].indptr))
    carriers["liquid"]["gas_neighbors"] = quantiles(np.diff(off[:split, split:].indptr))
    carriers["gas"]["liquid_neighbors"] = quantiles(np.diff(off[split:, :split].indptr))
    carriers["gas"]["gas_neighbors"] = quantiles(np.diff(off[split:, split:].indptr))
    return {"shape": g["shape"], "duration": g["dt"], "cell_size": g["cell_size"], "carriers": carriers,
            "active_nodes": len(active), "matrix_entries": matrix.nnz, "positive_off_diagonal_entries": int(np.count_nonzero(coo.data > 0)),
            "diagonal": quantiles(diagonal), "off_diagonal_degree": quantiles(np.diff(off.indptr)),
            "normalized_edge_strength": quantiles(strength), "absolute_off_diagonal_over_diagonal": quantiles(absolute_rows / diagonal),
            "full_components": component_sizes(off), "free_components": component_sizes(free_graph),
            "free_off_diagonal_degree": quantiles(np.diff(free_graph.indptr)),
            "liquid_components": component_sizes(ll.tocsr()),
            "spatial_aggregation": splits}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dump", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    rendered = json.dumps(analyze(load(args.dump)), indent=2)
    if args.json:
        args.json.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
