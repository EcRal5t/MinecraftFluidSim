# /// script
# requires-python = ">=3.13"
# dependencies = ["numpy", "scipy"]
# ///
"""Describe a requested GPU aperture dump to choose a pressure graph algorithm."""

import argparse
from collections import deque
import json
from pathlib import Path
import struct

import numpy as np
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components


def load(path):
    data = Path(path).read_bytes()
    magic, nodes, pairs, duration = struct.unpack_from("<8siid", data)
    if magic != b"MMMGAS01":
        raise ValueError("Unsupported aperture graph dump")
    cursor = 24
    result = {"nodes": nodes, "pairs": pairs, "duration": duration}
    for name, count, dtype in [
        ("storage", nodes, "<f8"), ("root", nodes, "<f8"),
        ("rhs", nodes, "<f8"), ("initial", nodes, "<f8"),
        ("keys", nodes, "<i4"), ("a", pairs, "<i4"),
        ("b", pairs, "<i4"), ("g", pairs, "<f8"),
    ]:
        result[name] = np.frombuffer(data, dtype=dtype, count=count, offset=cursor)
        cursor += count * np.dtype(dtype).itemsize
    if cursor != len(data):
        raise ValueError(f"Dump size differs: expected {cursor}, got {len(data)}")
    return result


def quantiles(values):
    values = np.asarray(values)
    if values.size == 0:
        return {}
    return {str(q): float(np.quantile(values, q)) for q in [0, .1, .5, .9, .99, 1]}


def peel(adjacency, threshold):
    degree = np.diff(adjacency.indptr).copy()
    removed = np.zeros(len(degree), dtype=bool)
    level = np.full(len(degree), -1, dtype=np.int32)
    queued = degree <= threshold
    queue = deque(np.flatnonzero(queued).tolist())
    level[queued] = 0
    while queue:
        node = queue.popleft()
        if removed[node]:
            continue
        removed[node] = True
        for peer in adjacency.indices[adjacency.indptr[node]:adjacency.indptr[node + 1]]:
            if removed[peer]:
                continue
            degree[peer] -= 1
            if degree[peer] <= threshold and not queued[peer]:
                queued[peer] = True
                level[peer] = level[node] + 1
                queue.append(int(peer))
    counts = np.bincount(level[level >= 0])
    return removed, {"removed": int(removed.sum()), "core": int((~removed).sum()),
                     "maximum_round": int(level.max(initial=-1)), "round_counts": counts.tolist()}


def independent_schur_round(nodes, a, b):
    adjacency = coo_matrix((np.ones(2 * len(a), dtype=np.int8),
                            (np.r_[a, b], np.r_[b, a])), shape=(nodes, nodes)).tocsr()
    degree = np.diff(adjacency.indptr)
    eliminated = degree <= 2
    adjacent_low = (degree[a] <= 2) & (degree[b] <= 2)
    eliminated[np.maximum(a[adjacent_low], b[adjacent_low])] = False
    retained = np.flatnonzero(~eliminated)
    mapping = np.full(nodes, -1, dtype=np.int64)
    mapping[retained] = np.arange(len(retained))
    direct = (~eliminated[a]) & (~eliminated[b])
    channels = np.flatnonzero(eliminated & (degree == 2))
    first = adjacency.indices[adjacency.indptr[channels]]
    second = adjacency.indices[adjacency.indptr[channels] + 1]
    pairs = np.r_[np.c_[mapping[a[direct]], mapping[b[direct]]],
                  np.c_[mapping[first], mapping[second]]]
    pairs.sort(axis=1)
    pairs = np.unique(pairs[pairs[:, 0] != pairs[:, 1]], axis=0)
    return len(retained), pairs[:, 0], pairs[:, 1], int(eliminated.sum())


def schur_structure(nodes, a, b, maximum_rounds=12):
    rounds = []
    for number in range(maximum_rounds):
        next_nodes, next_a, next_b, eliminated = independent_schur_round(nodes, a, b)
        adjacency = coo_matrix((np.ones(2 * len(next_a), dtype=np.int8),
                                (np.r_[next_a, next_b], np.r_[next_b, next_a])),
                               shape=(next_nodes, next_nodes)).tocsr()
        components, labels = connected_components(adjacency, directed=False)
        sizes = np.bincount(labels)
        degrees = np.diff(adjacency.indptr)
        rounds.append({"round": number + 1, "input_nodes": nodes, "eliminated": eliminated,
                       "core_nodes": next_nodes, "core_edges": len(next_a),
                       "connected_components": int(components),
                       "largest_components": np.sort(sizes)[-15:][::-1].tolist(),
                       "components_over_256": int(np.count_nonzero(sizes > 256)),
                       "nodes_in_components_over_256": int(sizes[sizes > 256].sum()),
                       "cycle_rank": int(len(next_a) - next_nodes + components),
                       "degree_histogram": {str(int(d)): int(c) for d, c in zip(*np.unique(degrees, return_counts=True))}})
        if next_nodes == 0 or eliminated == 0:
            break
        nodes, a, b = next_nodes, next_a, next_b
    return rounds


def analyze(graph, top):
    n = graph["nodes"]
    weight = graph["duration"] * graph["g"]
    active = weight > 0
    finite = active & (graph["a"] > 0) & (graph["b"] > 0)
    a, b, w = graph["a"][finite] - 1, graph["b"][finite] - 1, weight[finite]
    row, col = np.r_[a, b], np.r_[b, a]
    weights = np.r_[w, w]
    adjacency = coo_matrix((np.ones(len(row), dtype=np.int8), (row, col)), shape=(n, n)).tocsr()
    adjacency.sum_duplicates()
    degree = np.diff(adjacency.indptr)
    weighted = np.bincount(row, weights=weights, minlength=n)
    ambient = np.zeros(n)
    for first, second in [(graph["a"], graph["b"]), (graph["b"], graph["a"])]:
        mask = active & (first > 0) & (second == 0)
        np.add.at(ambient, first[mask] - 1, weight[mask])
    diagonal = graph["root"] ** 2
    strength = w / np.sqrt(diagonal[a] * diagonal[b])
    max_strength = np.zeros(n)
    np.maximum.at(max_strength, a, strength)
    np.maximum.at(max_strength, b, strength)
    best = np.full(n, n, dtype=np.int64)
    np.minimum.at(best, a[strength == max_strength[a]], b[strength == max_strength[a]])
    np.minimum.at(best, b[strength == max_strength[b]], a[strength == max_strength[b]])
    mutual = np.flatnonzero(best < n)
    mutual = mutual[(best[mutual] > mutual) & (best[best[mutual]] == mutual)]
    component_count, labels = connected_components(adjacency, directed=False)
    sizes = np.bincount(labels)
    removed, leaf = peel(adjacency, 1)
    _, degree_two = peel(adjacency, 2)
    leaf["core_edges"] = int(np.count_nonzero(~removed[a] & ~removed[b]))
    low = degree <= 2
    low_indices = np.flatnonzero(low)
    low_graph = adjacency[low_indices][:, low_indices]
    low_components, low_labels = connected_components(low_graph, directed=False)
    low_sizes = np.bincount(low_labels)
    low_component = np.full(n, -1, dtype=np.int32)
    low_component[low_indices] = low_labels
    boundary_a = low[a] & ~low[b]
    boundary_b = ~low[a] & low[b]
    boundary_components = np.r_[low_component[a[boundary_a]], low_component[b[boundary_b]]]
    boundary_nodes = np.r_[b[boundary_a], a[boundary_b]]
    boundary_counts = np.bincount(boundary_components, minlength=low_components)
    component_sizes, size_counts = np.unique(low_sizes, return_counts=True)
    boundary_values, boundary_histogram = np.unique(boundary_counts, return_counts=True)
    low_description = {
        "components": int(low_components), "nodes": int(low.sum()),
        "component_size_histogram": {str(int(k)): int(v) for k, v in zip(component_sizes, size_counts)},
        "boundary_edge_histogram": {str(int(k)): int(v) for k, v in zip(boundary_values, boundary_histogram)},
        "largest_components": np.sort(low_sizes)[-10:][::-1].tolist(),
        "isolated_cycles": int(np.count_nonzero((boundary_counts == 0) & (low_sizes > 1) &
                                                 (np.bincount(low_labels, weights=degree[low_indices]) == 2 * low_sizes))),
    }
    two_boundaries = np.flatnonzero(boundary_counts == 2)
    boundary_order = np.argsort(boundary_components)
    sorted_components, sorted_nodes = boundary_components[boundary_order], boundary_nodes[boundary_order]
    first_boundary = np.searchsorted(sorted_components, two_boundaries)
    reduced_pairs = np.c_[sorted_nodes[first_boundary], sorted_nodes[first_boundary + 1]]
    direct = (~low[a]) & (~low[b])
    reduced_pairs = np.r_[reduced_pairs, np.c_[a[direct], b[direct]]]
    reduced_pairs.sort(axis=1)
    reduced_pairs = reduced_pairs[reduced_pairs[:, 0] != reduced_pairs[:, 1]]
    low_description["retained_core_nodes"] = int((~low).sum())
    low_description["retained_core_direct_edges"] = int(direct.sum())
    low_description["retained_core_unique_edges"] = int(len(np.unique(reduced_pairs, axis=0)))
    histogram_values, histogram_counts = np.unique(degree, return_counts=True)
    hubs = []
    order = np.argsort(degree)[::-1]
    for node in order[:top]:
        peers = adjacency.indices[adjacency.indptr[node]:adjacency.indptr[node + 1]]
        hubs.append({"node": int(node), "topology_key": int(graph["keys"][node]),
                     "degree": int(degree[node]), "degree_one_neighbors": int(np.count_nonzero(degree[peers] == 1)),
                     "degree_two_neighbors": int(np.count_nonzero(degree[peers] == 2)),
                     "weighted_degree": float(weighted[node]), "storage_ratio": float(graph["storage"][node] / diagonal[node]),
                     "ambient_ratio": float(ambient[node] / diagonal[node]), "strongest_normalized_edge": float(max_strength[node])})
    hub_coverage = {}
    for count in [1, 4, 16, 64, 256]:
        chosen = np.zeros(n, dtype=bool)
        chosen[order[:count]] = True
        touched = chosen[a] | chosen[b]
        hub_coverage[str(count)] = {"edge_fraction": float(touched.mean()) if len(w) else 0,
                                     "weight_fraction": float(w[touched].sum() / w.sum()) if w.sum() else 0}
    return {"nodes": n, "pairs": graph["pairs"], "duration": graph["duration"],
            "finite_positive_edges": len(w), "ambient_positive_edges": int(np.count_nonzero(active & ~finite)),
            "degree_histogram": {str(int(k)): int(v) for k, v in zip(histogram_values, histogram_counts)},
            "degree_quantiles": quantiles(degree), "storage_over_diagonal": quantiles(graph["storage"] / diagonal),
            "normalized_edge_strength": quantiles(strength), "strongest_edge_per_node": quantiles(max_strength),
            "strong_edge_counts": {str(threshold): int(np.count_nonzero(strength >= threshold)) for threshold in [.1, .25, .5, .75, .9]},
            "mutual_strongest_pairs": len(mutual), "mutual_strongest_node_fraction": 2 * len(mutual) / max(1, n),
            "connected_components": int(component_count), "largest_components": np.sort(sizes)[-10:][::-1].tolist(),
            "cycle_rank": int(len(w) - n + component_count), "low_degree_components": low_description,
            "independent_schur_rounds": schur_structure(n, a, b), "leaf_peeling": leaf, "degree_two_peeling": degree_two,
            "top_hubs": hubs, "top_hub_edge_coverage": hub_coverage,
            "initial_physical_pressure": quantiles(graph["initial"] / graph["root"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dump", type=Path)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--top", type=int, default=16)
    args = parser.parse_args()
    report = analyze(load(args.dump), args.top)
    rendered = json.dumps(report, indent=2, ensure_ascii=False)
    if args.json:
        args.json.write_bytes((rendered + "\n").encode())
    print(rendered)


if __name__ == "__main__":
    main()
