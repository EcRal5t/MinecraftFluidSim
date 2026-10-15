package mmm.fluid;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Reusable convex clipping in a tight box or a plane-local slab chart. */
final class ConvexPolyhedron {
	private static final int MAX_VERTICES = 128, MAX_FACES = 32, FACE_VERTICES = 64, EDGE_SLOTS = 512;
	private static final SignedDyadic ZERO = SignedDyadic.ZERO, ONE = SignedDyadic.of(1), HALF = SignedDyadic.of(1, -1);
	private static final int[][] BOX_FACES = {
			{0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}
	};
	private static final int[][] POWERS = {
			{0,0,0}, {1,0,0}, {0,1,0}, {0,0,1}, {2,0,0}, {1,1,0}, {1,0,1}, {0,2,0}, {0,1,1}, {0,0,2},
			{3,0,0}, {2,1,0}, {2,0,1}, {1,2,0}, {1,1,1}, {1,0,2}, {0,3,0}, {0,2,1}, {0,1,2}, {0,0,3}
	};
	private static final int[] POWER_INDEX = new int[64];
	private static final int[] POWER_STEP = {16, 4, 1};
	static {
		Arrays.fill(POWER_INDEX, -1);
		for (int i = 0; i < POWERS.length; i++) POWER_INDEX[powerCode(POWERS[i])] = i;
	}

	private final double[] x = new double[MAX_VERTICES], y = new double[MAX_VERTICES], z = new double[MAX_VERTICES];
	private final double[] distance = new double[MAX_VERTICES];
	private final double[][] faceNormal = new double[MAX_FACES][3];
	private final double[] faceOffsetHigh = new double[MAX_FACES], faceOffsetLow = new double[MAX_FACES];
	private final int[] faceAxis = new int[MAX_FACES], hull = new int[2 * MAX_VERTICES];
	private final int[][] face = new int[MAX_FACES][FACE_VERTICES];
	private final int[] faceSize = new int[MAX_FACES], activeFaces = new int[MAX_FACES], nextFaces = new int[MAX_FACES];
	private final int[] activeVertices = new int[MAX_VERTICES], freeVertices = new int[MAX_VERTICES], cap = new int[MAX_VERTICES];
	private final int[] scratch = new int[2 * FACE_VERTICES], edgeKey = new int[EDGE_SLOTS], edgeVertex = new int[EDGE_SLOTS];
	private final boolean[] vertexUsed = new boolean[MAX_VERTICES], faceUsed = new boolean[MAX_FACES];
	private int faceCount, vertexCount, freeCount, capCount;

	private final SignedDyadic[] lower = new SignedDyadic[3], upper = new SignedDyadic[3], width = new SignedDyadic[3];
	private final SignedDyadic[] planeCoefficients = new SignedDyadic[3];
	private final double[] boxScale = new double[3];
	private SignedDyadic frameDenominator = ONE;
	private final SignedDyadic[] originNumerator = new SignedDyadic[3];
	private final SignedDyadic[][] axesNumerator = new SignedDyadic[3][3];
	private GasGeometryOverlap.HalfSpace chart;
	private SignedDyadic slabNumerator, slabDenominator;
	private int dominant, tangent, other;
	private double jacobian;

	private final double[][] tetra = new double[4][3];
	private final double[] sum = new double[3];
	private final int[] triangle = new int[3];

	/** Local moment tensor and its common affine coordinate map. */
	static final class Moments {
		final double[] raw = new double[20];
		// Physical position = (originNumerator + axesNumerator * localOffset) / denominator.
		SignedDyadic denominator = ONE;
		final SignedDyadic[] originNumerator = new SignedDyadic[3];
		final SignedDyadic[][] axesNumerator = new SignedDyadic[3][3];
		private int degree = -1;

		private void clear(int order) {
			Arrays.fill(raw, 0);
			degree = order;
		}

		/** Whole-cell-centred monomials, formed from the local moment tensor before rational conversion. */
		ExactRational[] globalMoments(int order) {
			if (order < 0 || order > degree) throw new IllegalArgumentException("Unavailable convex moment degree");
			int length = momentCount(order);
			var result = new ExactRational[length];
			Arrays.fill(result, ExactRational.ZERO);
			if (raw[0] == 0) return result;
			if (order == 0) {
				result[0] = rational(SignedDyadic.of(raw[0]));
				return result;
			}
			var local = new SignedDyadic[length];
			for (int i = 0; i < length; i++) local[i] = SignedDyadic.of(raw[i]);
			var denominatorPowers = new SignedDyadic[order + 1];
			denominatorPowers[0] = ONE;
			for (int i = 1; i <= order; i++) denominatorPowers[i] = denominatorPowers[i - 1].multiply(denominator);
			var polynomials = new SignedDyadic[length][];
			polynomials[0] = new SignedDyadic[length];
			Arrays.fill(polynomials[0], ZERO);
			polynomials[0][0] = ONE;
			result[0] = rational(local[0]);
			for (int target = 1; target < length; target++) {
				int axis = 0; while (POWERS[target][axis] == 0) axis++;
				int previous = POWER_INDEX[powerCode(POWERS[target]) - POWER_STEP[axis]];
				var source = polynomials[previous];
				var polynomial = new SignedDyadic[length]; Arrays.fill(polynomial, ZERO);
				polynomials[target] = polynomial;
				for (int term = 0; term < length; term++) if (source[term].signum() != 0) {
					polynomial[term] = polynomial[term].add(source[term].multiply(originNumerator[axis]));
					for (int b = 0; b < 3; b++) if (axesNumerator[axis][b].signum() != 0) {
						int index = POWER_INDEX[powerCode(POWERS[term]) + POWER_STEP[b]];
						polynomial[index] = polynomial[index].add(source[term].multiply(axesNumerator[axis][b]));
					}
				}
				SignedDyadic value = ZERO;
				for (int term = 0; term < length; term++) if (polynomial[term].signum() != 0)
					value = value.add(polynomial[term].multiply(local[term]));
				int targetDegree = POWERS[target][0] + POWERS[target][1] + POWERS[target][2];
				result[target] = rational(value, denominatorPowers[targetDegree]);
			}
			return result;
		}
	}

	void resetBox(int micro, List<GasGeometryOverlap.HalfSpace> planes) {
		if (micro < 0 || micro >= 8 || planes == null || planes.size() > 12)
			throw new IllegalArgumentException("Invalid clipped microbox");
		for (int a = 0; a < 3; a++) {
			lower[a] = SignedDyadic.of(micro >> a & 1, -1);
			upper[a] = lower[a].add(HALF);
		}
		if (!tightenAxes(planes)) return;
		chart = null;
		frameDenominator = ONE;
		for (int a = 0; a < 3; a++) {
			width[a] = upper[a].subtract(lower[a]);
			if (width[a].signum() <= 0) { faceCount = 0; return; }
			boxScale[a] = width[a].toDouble();
			if (!(boxScale[a] > 0)) throw new IllegalArgumentException("Box dimension underflows double");
			originNumerator[a] = lower[a].subtract(HALF);
			Arrays.fill(axesNumerator[a], ZERO);
			axesNumerator[a][a] = width[a];
		}
		if (!selectChart(planes)) return;
		if (chart != null) installChart();
		else jacobian = boxScale[0] * boxScale[1] * boxScale[2];
		initializeBox();
		for (var plane : planes) {
			int axis = axisPlane(plane);
			if (axis == -1 || axis >= 0 && powerOfTwo(component(plane, axis))) continue;
			transformedClip(plane);
			if (faceCount == 0) return;
		}
		if (chart != null) {
			var unit = new SignedDyadic[] {ZERO, ZERO, ZERO};
			unit[dominant] = ONE;
			transformedClip(new GasGeometryOverlap.HalfSpace(unit[0], unit[1], unit[2], lower[dominant]));
			unit[dominant] = ONE.negate();
			transformedClip(new GasGeometryOverlap.HalfSpace(unit[0], unit[1], unit[2], upper[dominant].negate()));
		}
	}

	private boolean tightenAxes(List<GasGeometryOverlap.HalfSpace> planes) {
		for (var plane : planes) {
			int axis = axisPlane(plane);
			if (axis == -1) {
				if (plane.threshold().signum() > 0) { faceCount = 0; return false; }
				continue;
			}
			if (axis >= 0 && powerOfTwo(component(plane, axis))) {
				var normal = component(plane, axis);
				var value = new SignedDyadic(normal.signum() < 0 ? plane.threshold().significand().negate()
						: plane.threshold().significand(), plane.threshold().exponent() - normal.exponent());
				if (normal.signum() > 0) {
					if (value.compareTo(lower[axis]) > 0) lower[axis] = value;
				} else if (value.compareTo(upper[axis]) < 0) upper[axis] = value;
				if (lower[axis].compareTo(upper[axis]) >= 0) { faceCount = 0; return false; }
				continue;
			}
			// Every admitted point lies at most maximum/|n_i| from this plane's maximum corner.
			// Compute the common maximum before changing any of this plane's axis bounds.
			var maximum = plane.threshold().negate();
			for (int a = 0; a < 3; a++) {
				var normal = component(plane, a);
				maximum = maximum.add(normal.multiply(normal.signum() > 0 ? upper[a] : lower[a]));
			}
			if (maximum.signum() <= 0) { faceCount = 0; return false; }
			for (int a = 0; a < 3; a++) {
				var normal = component(plane, a);
				if (normal.signum() == 0) continue;
				var magnitude = abs(normal);
				if (maximum.compareTo(magnitude.multiply(upper[a].subtract(lower[a]))) >= 0) continue;
				double rounded = ratio(maximum, magnitude);
				if (SignedDyadic.of(rounded).multiply(magnitude).compareTo(maximum) < 0) rounded = Math.nextUp(rounded);
				var extent = SignedDyadic.of(rounded);
				if (normal.signum() > 0) {
					var bound = upper[a].subtract(extent);
					if (bound.compareTo(lower[a]) > 0) lower[a] = bound;
				} else {
					var bound = lower[a].add(extent);
					if (bound.compareTo(upper[a]) < 0) upper[a] = bound;
				}
			}
		}
		return true;
	}

	/** q0=(n.x-d)/slabWidth; the other coordinates span the two tangential box intervals. */
	private boolean selectChart(List<GasGeometryOverlap.HalfSpace> planes) {
		double best = 1;
		for (var plane : planes) {
			int k = 0;
			for (int a = 1; a < 3; a++) if (abs(component(plane, a)).compareTo(abs(component(plane, k))) > 0) k = a;
			var normal = component(plane, k);
			if (normal.signum() <= 0) continue;
			int i = (k + 1) % 3, j = (k + 2) % 3;
			SignedDyadic range = ZERO;
			for (int a = 0; a < 3; a++) range = range.add(abs(component(plane, a)).multiply(width[a]));
			for (var bound : planes) {
				var opposing = component(bound, k);
				if (opposing.signum() >= 0) continue;
				var first = component(bound, i).multiply(normal).subtract(opposing.multiply(component(plane, i)));
				var second = component(bound, j).multiply(normal).subtract(opposing.multiply(component(plane, j)));
				var maximum = opposing.multiply(plane.threshold()).subtract(bound.threshold().multiply(normal))
						.add(first.multiply(first.signum() >= 0 ? upper[i] : lower[i]))
						.add(second.multiply(second.signum() >= 0 ? upper[j] : lower[j]));
				if (maximum.signum() <= 0) { faceCount = 0; return false; }
				var denominator = opposing.negate();
				double squeezing = ratio(maximum, denominator.multiply(range));
				if (squeezing < best) {
					best = squeezing;
					chart = plane;
					slabNumerator = maximum;
					slabDenominator = denominator;
					dominant = k; tangent = i; other = j;
				}
			}
		}
		return true;
	}

	private void installChart() {
		var normal = component(chart, dominant);
		frameDenominator = slabDenominator.multiply(normal);
		for (var row : axesNumerator) Arrays.fill(row, ZERO);
		axesNumerator[dominant][0] = slabNumerator;
		axesNumerator[dominant][1] = component(chart, tangent).multiply(width[tangent]).multiply(slabDenominator).negate();
		axesNumerator[dominant][2] = component(chart, other).multiply(width[other]).multiply(slabDenominator).negate();
		axesNumerator[tangent][1] = width[tangent].multiply(frameDenominator);
		axesNumerator[other][2] = width[other].multiply(frameDenominator);
		for (int a = 0; a < 3; a++) {
			originNumerator[a] = lower[a].subtract(HALF).multiply(frameDenominator);
		}
		originNumerator[dominant] = chart.threshold().subtract(component(chart, tangent).multiply(lower[tangent]))
				.subtract(component(chart, other).multiply(lower[other])).subtract(normal.multiply(HALF)).multiply(slabDenominator);
		jacobian = ratio(slabNumerator.multiply(width[tangent]).multiply(width[other]), frameDenominator);
	}

	private void initializeBox() {
		for (int i = 0; i < 8; i++) { x[i] = i & 1; y[i] = i >> 1 & 1; z[i] = i >> 2 & 1; }
		faceCount = 6;
		Arrays.fill(faceUsed, false);
		for (int f = 0; f < 6; f++) {
			activeFaces[f] = f; faceUsed[f] = true; faceSize[f] = 4;
			System.arraycopy(BOX_FACES[f], 0, face[f], 0, 4);
			Arrays.fill(faceNormal[f], 0);
			faceNormal[f][f / 2] = f % 2 == 0 ? 1 : -1;
			faceOffsetHigh[f] = f % 2 == 0 ? 0 : -1; faceOffsetLow[f] = 0;
			faceAxis[f] = f / 2;
		}
	}

	/** Eliminate the dominant coordinate before converting plane coefficients to double. */
	private void transformedClip(GasGeometryOverlap.HalfSpace plane) {
		SignedDyadic threshold;
		if (chart == null) {
			threshold = plane.threshold();
			for (int a = 0; a < 3; a++) {
				threshold = threshold.subtract(component(plane, a).multiply(lower[a]));
				planeCoefficients[a] = component(plane, a).multiply(width[a]);
			}
		} else {
			var normal = component(chart, dominant);
			var projected = component(plane, dominant);
			var first = component(plane, tangent).multiply(normal).subtract(projected.multiply(component(chart, tangent)));
			var second = component(plane, other).multiply(normal).subtract(projected.multiply(component(chart, other)));
			planeCoefficients[0] = projected.multiply(slabNumerator);
			planeCoefficients[1] = first.multiply(slabDenominator).multiply(width[tangent]);
			planeCoefficients[2] = second.multiply(slabDenominator).multiply(width[other]);
			threshold = plane.threshold().multiply(normal).subtract(projected.multiply(chart.threshold())).multiply(slabDenominator)
					.subtract(first.multiply(slabDenominator).multiply(lower[tangent]))
					.subtract(second.multiply(slabDenominator).multiply(lower[other]));
		}
		double high = threshold.toDouble(), low = threshold.subtract(SignedDyadic.of(high)).toDouble();
		clip(planeCoefficients[0].toDouble(), planeCoefficients[1].toDouble(), planeCoefficients[2].toDouble(), high, low);
	}

	private void collectVertices() {
		Arrays.fill(vertexUsed, false);
		vertexCount = 0;
		for (int i = 0; i < faceCount; i++) {
			int f = activeFaces[i];
			for (int k = 0; k < faceSize[f]; k++) {
				int v = face[f][k];
				if (!vertexUsed[v]) { vertexUsed[v] = true; activeVertices[vertexCount++] = v; }
			}
		}
		freeCount = 0;
		for (int v = MAX_VERTICES - 1; v >= 0; v--) if (!vertexUsed[v]) freeVertices[freeCount++] = v;
	}

	private double signedDistance(int v, double nx, double ny, double nz, double high, double low) {
		return planeDistance(x[v], y[v], z[v], nx, ny, nz, high, low);
	}

	private static double planeDistance(double px, double py, double pz, double nx, double ny, double nz, double high, double low) {
		double a = nx * px, b = ny * py, c = nz * pz, s = a + b, t = s + c, u = t - high;
		return u + (Math.fma(nx, px, -a) + Math.fma(ny, py, -b) + Math.fma(nz, pz, -c)
				+ sumError(a, b, s) + sumError(s, c, t) + sumError(t, -high, u) - low);
	}

	private void clip(double nx, double ny, double nz, double high, double low) {
		if (faceCount == 0) return;
		collectVertices();
		boolean positive = false, negative = false;
		capCount = 0;
		for (int i = 0; i < vertexCount; i++) {
			int v = activeVertices[i];
			double value = signedDistance(v, nx, ny, nz, high, low);
			distance[v] = value;
			positive |= value > 0; negative |= value < 0;
			if (value == 0) cap[capCount++] = v;
		}
		if (!negative) return;
		if (!positive) { faceCount = 0; return; }
		Arrays.fill(edgeKey, -1);
		int nextCount = 0;
		for (int i = 0; i < faceCount; i++) {
			int f = activeFaces[i], length = faceSize[f], output = 0;
			for (int k = 0; k < length; k++) {
				int a = face[f][k], b = face[f][(k + 1) % length];
				double da = distance[a], db = distance[b];
				if (da >= 0 && (output == 0 || scratch[output - 1] != a)) scratch[output++] = a;
				if (da < 0 && db > 0 || da > 0 && db < 0) {
					int v = intersection(a, b, da, db);
					if (output == 0 || scratch[output - 1] != v) scratch[output++] = v;
				}
			}
			if (output > 1 && scratch[0] == scratch[output - 1]) output--;
			if (output >= 3) {
				System.arraycopy(scratch, 0, face[f], 0, output);
				faceSize[f] = projectedHull(face[f], output, faceAxis[f], faceNormal[f][faceAxis[f]]);
				if (faceSize[f] >= 3) nextFaces[nextCount++] = f;
				else faceUsed[f] = false;
			} else faceUsed[f] = false;
		}
		if (capCount >= 3) {
			int capFace = closeCap(nx, ny, nz, high, low);
			if (faceSize[capFace] >= 3) nextFaces[nextCount++] = capFace;
			else faceUsed[capFace] = false;
		}
		faceCount = nextCount;
		System.arraycopy(nextFaces, 0, activeFaces, 0, faceCount);
	}

	private int intersection(int a, int b, double da, double db) {
		int key = (Math.min(a, b) << 8) | Math.max(a, b), slot = key * 0x9e3779b9 >>> 23;
		while (edgeKey[slot] != -1 && edgeKey[slot] != key) slot = (slot + 1) & (EDGE_SLOTS - 1);
		if (edgeKey[slot] == key) return edgeVertex[slot];
		if (freeCount == 0) throw new IllegalStateException("Clipped vertex workspace exhausted");
		int v = freeVertices[--freeCount];
		edgeKey[slot] = key; edgeVertex[slot] = v;
		if (Math.abs(da) <= Math.abs(db)) {
			double t = da / (da - db);
			x[v] = Math.fma(t, x[b] - x[a], x[a]);
			y[v] = Math.fma(t, y[b] - y[a], y[a]);
			z[v] = Math.fma(t, z[b] - z[a], z[a]);
		} else {
			double t = db / (db - da);
			x[v] = Math.fma(t, x[a] - x[b], x[b]);
			y[v] = Math.fma(t, y[a] - y[b], y[b]);
			z[v] = Math.fma(t, z[a] - z[b], z[b]);
		}
		distance[v] = 0;
		cap[capCount++] = v;
		return v;
	}

	private int closeCap(double nx, double ny, double nz, double high, double low) {
		int axis = Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz) ? 0 : Math.abs(ny) >= Math.abs(nz) ? 1 : 2;
		int f = 0;
		while (f < MAX_FACES && faceUsed[f]) f++;
		if (f == MAX_FACES) throw new IllegalStateException("Clipped face workspace exhausted");
		faceUsed[f] = true; faceAxis[f] = axis;
		faceNormal[f][0] = nx; faceNormal[f][1] = ny; faceNormal[f][2] = nz;
		faceOffsetHigh[f] = high; faceOffsetLow[f] = low;
		faceSize[f] = projectedHull(cap, capCount, axis, faceNormal[f][axis]);
		System.arraycopy(cap, 0, face[f], 0, faceSize[f]);
		return f;
	}

	/** A face is the convex polygon in its supporting plane's two independent coordinates. */
	private int projectedHull(int[] vertices, int length, int axis, double inwardNormal) {
		int first = (axis + 1) % 3, second = (axis + 2) % 3;
		for (int index = 1; index < length; index++) {
			int v = vertices[index], place = index;
			while (place > 0 && compareProjected(vertices[place - 1], v, first, second) > 0) {
				vertices[place] = vertices[place - 1]; place--;
			}
			vertices[place] = v;
		}
		int unique = 0;
		for (int index = 0; index < length; index++)
			if (unique == 0 || compareProjected(vertices[unique - 1], vertices[index], first, second) != 0)
				vertices[unique++] = vertices[index];
		if (unique < 3) return unique;
		int size = 0;
		for (int index = 0; index < unique; index++) {
			while (size >= 2 && projectedArea(hull[size - 2], hull[size - 1], vertices[index], axis) <= 0) size--;
			hull[size++] = vertices[index];
		}
		int lowerSize = size;
		for (int index = unique - 2; index >= 0; index--) {
			while (size > lowerSize && projectedArea(hull[size - 2], hull[size - 1], vertices[index], axis) <= 0) size--;
			hull[size++] = vertices[index];
		}
		size--;
		for (int index = 0; index < size; index++) vertices[index] = hull[inwardNormal > 0 ? size - 1 - index : index];
		return size;
	}

	private int compareProjected(int a, int b, int first, int second) {
		int comparison = Double.compare(coordinate(a, first), coordinate(b, first));
		return comparison != 0 ? comparison : Double.compare(coordinate(a, second), coordinate(b, second));
	}
	private double coordinate(int vertex, int axis) { return axis == 0 ? x[vertex] : axis == 1 ? y[vertex] : z[vertex]; }
	private double projectedArea(int a, int b, int c, int axis) {
		int first = (axis + 1) % 3, second = (axis + 2) % 3;
		double ab = coordinate(b, first) - coordinate(a, first), ac = coordinate(c, second) - coordinate(a, second);
		double ba = coordinate(b, second) - coordinate(a, second), ca = coordinate(c, first) - coordinate(a, first);
		double product = ba * ca;
		return Math.fma(ab, ac, -product) - Math.fma(ba, ca, -product);
	}

	@FunctionalInterface interface PointConsumer { void accept(double x, double y, double z, double weight); }
	@FunctionalInterface private interface TetraConsumer { void accept(double volume); }
	private double[] interiorAnchor() {
		collectVertices(); var anchor = new double[3];
		for (int i = 0; i < vertexCount; i++) for (int a = 0; a < 3; a++) anchor[a] += coordinate(activeVertices[i], a) / vertexCount;
		return anchor;
	}
	private void visitTetrahedra(double[] anchor, boolean coordinates, TetraConsumer accept) {
		for (int i = 0; i < faceCount; i++) {
			int f = activeFaces[i], axis = faceAxis[f], first = (axis + 1) % 3, second = (axis + 2) % 3;
			var normal = faceNormal[f];
			double height = planeDistance(anchor[0], anchor[1], anchor[2], normal[0], normal[1], normal[2], faceOffsetHigh[f], faceOffsetLow[f]);
			for (int k = 1; k + 1 < faceSize[f]; k++) {
				triangle[0] = face[f][0]; triangle[1] = face[f][k]; triangle[2] = face[f][k + 1];
				double volume = projectedArea(triangle[0], triangle[1], triangle[2], axis) * (-height / normal[axis]) * jacobian / 6;
				if (volume < 0) throw new IllegalStateException("Inward convex face");
				if (volume == 0) continue;
				if (coordinates) for (int p = 0; p < 3; p++) {
					int v = triangle[p];
					tetra[p + 1][first] = coordinate(v, first) - anchor[first];
					tetra[p + 1][second] = coordinate(v, second) - anchor[second];
					tetra[p + 1][axis] = (-height - normal[first] * tetra[p + 1][first]
							- normal[second] * tetra[p + 1][second]) / normal[axis];
				}
				accept.accept(volume);
			}
		}
	}
	void integrate(int order, Moments result) {
		if (order < 0 || order > 3) throw new IllegalArgumentException("Unsupported convex moment degree");
		result.clear(order); if (faceCount == 0) return;
		var anchor = interiorAnchor(); captureFrame(result, anchor[0], anchor[1], anchor[2]);
		visitTetrahedra(anchor, order > 0, volume -> addTetraMoments(result.raw, volume, order));
	}
	/** Positive polynomial rule in whole-cell-centred coordinates, sharing the moment tetrahedra. */
	void quadrature(int degree, PointConsumer accept) {
		if (degree < 0 || degree > 4) throw new IllegalArgumentException("Unsupported convex quadrature degree");
		if (faceCount == 0) return;
		var anchor = interiorAnchor(); var frame = new Moments(); captureFrame(frame, anchor[0], anchor[1], anchor[2]);
		var origin = new double[3]; var axes = new double[3][3];
		for (int a = 0; a < 3; a++) {
			origin[a] = rational(frame.originNumerator[a], frame.denominator).toDouble();
			for (int b = 0; b < 3; b++) axes[a][b] = rational(frame.axesNumerator[a][b], frame.denominator).toDouble();
		}
		int nr = (degree + 4) / 2, ns = (degree + 3) / 2, nt = (degree + 2) / 2;
		var rn = LiquidViscousQuadrature.nodes(nr); var sn = LiquidViscousQuadrature.nodes(ns); var tn = LiquidViscousQuadrature.nodes(nt);
		var rw = LiquidViscousQuadrature.weights(nr); var sw = LiquidViscousQuadrature.weights(ns); var tw = LiquidViscousQuadrature.weights(nt);
		visitTetrahedra(anchor, true, volume -> {
			for (int i = 0; i < nr; i++) for (int j = 0; j < ns; j++) for (int k = 0; k < nt; k++) {
				double r = rn[i], s = sn[j], t = tn[k];
				double first = r, second = (1 - r) * s, third = (1 - r) * (1 - s) * t;
				var point = origin.clone();
				for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++)
					point[a] += axes[a][b] * (first * tetra[1][b] + second * tetra[2][b] + third * tetra[3][b]);
				double weight = 6 * volume * (1 - r) * (1 - r) * (1 - s)
						* rw[i] * sw[j] * tw[k];
				accept.accept(point[0], point[1], point[2], weight);
			}
		});
	}

	private void captureFrame(Moments result, double cx, double cy, double cz) {
		result.denominator = frameDenominator;
		for (int a = 0; a < 3; a++) {
			var anchorNumerator = originNumerator[a];
			for (int b = 0; b < 3; b++) {
				double coordinate = b == 0 ? cx : b == 1 ? cy : cz;
				anchorNumerator = anchorNumerator.add(axesNumerator[a][b].multiply(SignedDyadic.of(coordinate)));
				result.axesNumerator[a][b] = axesNumerator[a][b];
			}
			result.originNumerator[a] = anchorNumerator;
		}
	}

	private void addTetraMoments(double[] moments, double volume, int order) {
		moments[0] += volume;
		if (order == 0) return;
		for (int a = 0; a < 3; a++) {
			sum[a] = tetra[1][a] + tetra[2][a] + tetra[3][a];
			moments[a + 1] += volume * sum[a] / 4;
		}
		if (order < 2) return;
		int entry = 4;
		for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) {
			double product = 0;
			for (int p = 1; p < 4; p++) product += tetra[p][a] * tetra[p][b];
			moments[entry++] += volume * (sum[a] * sum[b] + product) / 20;
		}
		if (order < 3) return;
		for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) for (int c = b; c < 3; c++) {
			double ab = 0, ac = 0, bc = 0, abc = 0;
			for (int p = 1; p < 4; p++) {
				ab += tetra[p][a] * tetra[p][b]; ac += tetra[p][a] * tetra[p][c]; bc += tetra[p][b] * tetra[p][c];
				abc += tetra[p][a] * tetra[p][b] * tetra[p][c];
			}
			moments[entry++] += volume * (sum[a] * sum[b] * sum[c] + ab * sum[c] + ac * sum[b] + bc * sum[a] + 2 * abc) / 120;
		}
	}

	private static int axisPlane(GasGeometryOverlap.HalfSpace plane) {
		int axis = -1;
		for (int a = 0; a < 3; a++) if (component(plane, a).signum() != 0) {
			if (axis >= 0) return -2;
			axis = a;
		}
		return axis;
	}
	private static SignedDyadic component(GasGeometryOverlap.HalfSpace plane, int axis) {
		return axis == 0 ? plane.nx() : axis == 1 ? plane.ny() : plane.nz();
	}
	private static boolean powerOfTwo(SignedDyadic value) { return value.significand().abs().equals(BigInteger.ONE); }
	private static SignedDyadic abs(SignedDyadic value) { return value.signum() < 0 ? value.negate() : value; }
	private static double ratio(SignedDyadic numerator, SignedDyadic denominator) {
		int shift = numerator.exponent() - denominator.exponent();
		return ExactRational.roundQuotientToDouble(shift >= 0 ? numerator.significand().shiftLeft(shift) : numerator.significand(),
				shift >= 0 ? denominator.significand() : denominator.significand().shiftLeft(-shift));
	}
	private static ExactRational rational(SignedDyadic value) { return rational(value, ONE); }
	private static ExactRational rational(SignedDyadic numerator, SignedDyadic denominator) {
		int shift = numerator.exponent() - denominator.exponent();
		return new ExactRational(shift >= 0 ? numerator.significand().shiftLeft(shift) : numerator.significand(),
				shift >= 0 ? denominator.significand() : denominator.significand().shiftLeft(-shift));
	}
	private static double sumError(double a, double b, double sum) { double q = sum - a; return a - (sum - q) + (b - q); }

	private static int powerCode(int[] powers) { return 16 * powers[0] + 4 * powers[1] + powers[2]; }
	private static int momentCount(int order) { return order <= 1 ? 4 : order == 2 ? 10 : 20; }
}
