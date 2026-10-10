#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["sympy>=1.13,<2"]
# ///
"""Generate exact reference modified face bubbles on a tetrahedral Alfeld split.

Guzman--Neilan, arXiv:1710.08044, Sections 3--5. This constructs the
local polynomial right inverse of divergence; it does not establish a
cut-domain discretization, transport scheme, or simulation cost.
The rational calculation runs once when generating the reference table.
"""
from __future__ import annotations

import argparse
import itertools
import json
import math
from pathlib import Path

import sympy as sp


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def powers(degree: int):
    return [(a, b, c) for total in range(degree + 1)
            for a in range(total + 1) for b in range(total - a + 1)
            for c in [total - a - b]]


def coefficients(expression, xyz, indices):
    polynomial = sp.Poly(sp.expand(expression), *xyz)
    return [polynomial.coeff_monomial(index) for index in indices]


def on_plane(expression, plane, xyz):
    axis = next(a for a in xyz if sp.diff(plane, a) != 0)
    location = sp.solve(plane, axis)[0]
    return sp.expand(expression.subs(axis, location))


def generate():
    xyz = sp.symbols("x y z")
    x, y, z = xyz
    vertices = [sp.Matrix(p) for p in [(0, 0, 0), (1, 0, 0),
                                      (0, 1, 0), (0, 0, 1)]]
    centre = sp.Matrix([sp.Rational(1, 4)] * 3)
    nodes, node_ids, pieces = [], {}, []
    indices3, indices2 = powers(3), powers(2)
    multi = [a for a in itertools.product(range(4), repeat=4) if sum(a) == 3]
    for omitted in range(4):
        points = [centre] + [p for j, p in enumerate(vertices) if j != omitted]
        affine = sp.Matrix.vstack(sp.Matrix.hstack(*points), sp.ones(1, 4))
        local = list(affine.inv() * sp.Matrix([x, y, z, 1]))
        basis = []
        for alpha in multi:
            point = tuple(sum((alpha[j] * points[j] for j in range(4)),
                              sp.zeros(3, 1)) / 3)
            if point not in node_ids:
                node_ids[point] = len(nodes)
                nodes.append(point)
            polynomial = sp.Integer(1)
            for j, count in enumerate(alpha):
                for k in range(count):
                    polynomial *= (3 * local[j] - k) / (k + 1)
            basis.append((node_ids[point], sp.expand(polynomial)))
        pieces.append(basis)
    # All exterior degree-three nodes have a zero macro barycentric coordinate.
    def on_exterior(p):
        return 0 in (*p, 1 - sum(p))
    interior = [n for n, p in enumerate(nodes) if not on_exterior(p)]
    require(len(nodes) == 35 and len(interior) == 15, "Unexpected Alfeld node layout")
    columns = {(n, axis): 3 * j + axis for j, n in enumerate(interior)
               for axis in range(3)}
    divergence = sp.zeros(40, 45)
    for piece, basis in enumerate(pieces):
        for node, polynomial in basis:
            if node not in interior:
                continue
            for axis in range(3):
                derivative = coefficients(sp.diff(polynomial, xyz[axis]), xyz, indices2)
                for k, value in enumerate(derivative):
                    divergence[10 * piece + k, columns[node, axis]] = value
    barycentric = [1 - x - y - z, x, y, z]
    rhs, bubbles, constants = sp.zeros(40, 12), [], []
    for face in range(4):
        bubble = sp.expand(27 * sp.prod(barycentric[j] for j in range(4) if j != face))
        for axis in range(3):
            derivative = sp.Poly(sp.diff(bubble, xyz[axis]), *xyz)
            average = sum(value * 6 * sp.Rational(
                math.prod(math.factorial(k) for k in exponent),
                math.factorial(sum(exponent) + 3))
                for exponent, value in derivative.terms())
            residual = coefficients(average - derivative.as_expr(), xyz, indices2)
            column = 3 * face + axis
            for piece in range(4):
                for k, value in enumerate(residual):
                    rhs[10 * piece + k, column] = value
            bubbles.append((face, axis, bubble))
            constants.append(average)
    solution, parameters = divergence.gauss_jordan_solve(rhs)
    solution = solution.subs({p: 0 for p in parameters.free_symbols})
    require(divergence * solution == rhs, "The exact divergence right inverse failed")
    # Zero exterior nodal values enforce identical zero traces on every outer face;
    # shared nodal coefficients enforce continuous traces between sub-tetrahedra.
    fields = []
    trace_checks = 0
    for column, (face, direction, bubble) in enumerate(bubbles):
        field = []
        polynomials = []
        for piece, basis in enumerate(pieces):
            vector = []
            for axis in range(3):
                value = bubble if axis == direction else sp.Integer(0)
                for node, polynomial in basis:
                    if node in interior:
                        value += solution[columns[node, axis], column] * polynomial
                vector.append(sp.expand(value))
            require(sp.expand(sum(sp.diff(vector[a], xyz[a]) for a in range(3))) == constants[column],
                    "Modified face bubble has nonconstant divergence")
            polynomials.append(vector)
            field.append([[str(v) for v in coefficients(value, xyz, indices3)]
                          for value in vector])
        for axis in range(3):
            for piece in range(4):
                expected = bubble if axis == direction else sp.Integer(0)
                require(on_plane(polynomials[piece][axis] - expected, barycentric[piece], xyz) == 0,
                        "Modified face bubble changed its exterior trace")
                trace_checks += 1
            for left, right in itertools.combinations(range(4), 2):
                require(on_plane(polynomials[left][axis] - polynomials[right][axis],
                                 barycentric[left] - barycentric[right], xyz) == 0,
                        "Modified face bubble is discontinuous on an internal face")
                trace_checks += 1
        fields.append(field)
    return {
        "reference": "https://arxiv.org/abs/1710.08044",
        "macro_vertices": [[0, 0, 0], [1, 0, 0], [0, 1, 0], [0, 0, 1]],
        "subdivision_centre": ["1/4"] * 3,
        "piece_order": "centre, followed by macro vertices except the omitted vertex",
        "field_order": "face-major; reference vector direction x,y,z",
        "monomials": indices3,
        "divergence_constants": [str(c) for c in constants],
        "fields": fields,
        "verification": {"local_nodes": len(nodes), "interior_nodes": len(interior),
                         "divergence_rank": 45 - parameters.rows,
                         "exact_divergence": True, "exact_trace_checks": trace_checks,
                         "global_unknowns_per_macro": 16,
                         "pressure_unknowns_per_macro": 1},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--numeric-output", type=Path,
                        help="also export correctly rounded binary64 polynomial coefficients")
    args = parser.parse_args()
    data = generate()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    if args.numeric_output:
        args.numeric_output.parent.mkdir(parents=True, exist_ok=True)
        rows = [" ".join(float(sp.Rational(c)).hex() for c in component)
                for field in data["fields"] for piece in field for component in piece]
        args.numeric_output.write_text("\n".join(rows) + "\n", encoding="ascii")
    print(json.dumps(data["verification"], indent=2))


if __name__ == "__main__":
    main()
