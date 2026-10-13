package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.Arrays;

/**
 * Actual-mass fit and adjoint for cell affine fields. Free gradients use the source spatial hull H.
 * Off-hull evaluation uses the minimum-gradient extension. Rank-one production fields use
 * one actual amplitude per component, extended linearly to a single wetted normal wall.
 */
final class CellAffineVelocity {
	private final int cellCount, markerCount;
	private final double h;
	private final int[] cells, offsets, markers, ranks, hullPoints;
	private final double[] mass, position, center, coefficients, coefficientLow, directions, wallSlopes;
	private final boolean[] representable, expanded;
	private double[] wallCoefficients;

	static CellAffineVelocity capture(FluidDomain domain) { return new CellAffineVelocity(domain, null); }
	static CellAffineVelocity capture(FluidDomain domain, LiquidPressureGeometry geometry) {
		if (geometry == null) throw new IllegalArgumentException("Missing affine wet geometry");
		return new CellAffineVelocity(domain, geometry);
	}
	private CellAffineVelocity(FluidDomain domain, LiquidPressureGeometry geometry) {
		cellCount = domain.cellCount; markerCount = domain.particles.count(); h = domain.config.cellSize();
		cells = new int[markerCount]; mass = new double[markerCount]; position = new double[3 * markerCount];
		offsets = new int[cellCount + 1]; markers = new int[markerCount]; ranks = new int[cellCount];
		center = new double[3 * cellCount]; directions = new double[9 * cellCount]; wallSlopes = new double[3 * cellCount];
		hullPoints = new int[4 * cellCount]; Arrays.fill(hullPoints, -1);
		coefficients = new double[4 * markerCount]; representable = new boolean[cellCount];
		coefficientLow = new double[4 * markerCount]; expanded = new boolean[cellCount];
		for (int i = 0; i < markerCount; i++) {
			cells[i] = domain.particleCellIndex(i); offsets[cells[i] + 1]++;
			mass[i] = domain.unitScale.kilograms(domain.particles.mass(i));
			if (!(mass[i] > 0 && Double.isFinite(mass[i]))) throw new IllegalStateException("Invalid affine marker mass");
			position[3 * i] = domain.particles.positionX(i); position[3 * i + 1] = domain.particles.positionY(i); position[3 * i + 2] = domain.particles.positionZ(i);
			for (int a = 0; a < 3; a++) if (!Double.isFinite(position[3 * i + a])) throw new IllegalStateException("Invalid affine marker position");
		}
		for (int c = 0; c < cellCount; c++) offsets[c + 1] += offsets[c];
		int[] cursor = offsets.clone();
		for (int i = 0; i < markerCount; i++) markers[cursor[cells[i]]++] = i;
		for (int c = 0; c < cellCount; c++) if (offsets[c] != offsets[c + 1]) {
			captureCenter(c); captureHull(c); representable[c] = fitMap(c) || fitExpandedMap(c);
			if (geometry != null && ranks[c] == 1 && representable[c]) captureWallExtension(domain, geometry, c);
		}
	}
	private void captureWallExtension(FluidDomain domain, LiquidPressureGeometry geometry, int cell) {
		var profile = geometry.cellSnapshot(cell); if (profile == null) return;
		int[] planes = wallPlanes(profile);
		int[] index = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
		for (int axis = 0; axis < 3; axis++) if (Integer.bitCount(planes[axis]) == 1) {
			double wall = (index[axis] + .5 * Integer.numberOfTrailingZeros(planes[axis])) * h;
			double offset = (center(cell, axis) - wall) / h; if (offset == 0) continue;
			double slope = 1 / offset;
			if (!Double.isFinite(slope)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable rank-one wall extension at cell " + cell);
			wallSlopes[3 * cell + axis] = slope;
			if (!expanded[cell]) {
				if (wallCoefficients == null) wallCoefficients = new double[3 * markerCount];
				for (int k = offsets[cell]; k < offsets[cell + 1]; k++) {
					int marker = markers[k]; double value = coefficients[4 * marker] * slope;
					if (!Double.isFinite(value)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable stored wall coefficient at cell " + cell);
					wallCoefficients[3 * marker + axis] = value;
				}
			}
		}
	}
	static int[] wallPlanes(LiquidPressureGeometry.CellSnapshot profile) {
		int[] planes = new int[3];
		for (var facet : profile.facets()) if (facet.kind() == LiquidPressureGeometry.FacetKind.WALL) {
			int plane = (facet.microKey() / 6 >> facet.axis() & 1) + (facet.sign() > 0 ? 1 : 0);
			planes[facet.axis()] |= 1 << plane;
		}
		return planes;
	}

	private void captureCenter(int cell) {
		SignedDyadic total = SignedDyadic.ZERO;
		SignedDyadic[] first = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO};
		for (int k = offsets[cell]; k < offsets[cell + 1]; k++) {
			int i = markers[k]; SignedDyadic m = SignedDyadic.of(mass[i]); total = total.add(m);
			for (int a = 0; a < 3; a++) first[a] = first[a].add(m.multiply(SignedDyadic.of(position[3 * i + a])));
		}
		for (int a = 0; a < 3; a++) {
			int shift = first[a].exponent() - total.exponent();
			center[3 * cell + a] = new ExactRational(first[a].significand().shiftLeft(Math.max(0, shift)),
					total.significand().shiftLeft(Math.max(0, -shift))).toDouble();
		}
	}

	/** Geometric independence uses exact raw coordinates, separately from numerical QR rank. */
	private void captureHull(int cell) {
		int base = 4 * cell, origin = markers[offsets[cell]]; hullPoints[base] = origin;
		int rank = 1;
		for (int k = offsets[cell] + 1; k < offsets[cell + 1] && rank < 4; k++) {
			int i = markers[k];
			boolean independent = rank == 1 ? !samePoint(origin, i) : rank == 2
					? !collinear(origin, hullPoints[base + 1], i)
					: determinant(origin, hullPoints[base + 1], hullPoints[base + 2], i).signum() != 0;
			if (independent) hullPoints[base + rank++] = i;
		}
		ranks[cell] = rank;
		for (int j = 0; j < rank - 1; j++) {
			double[] e = new double[3]; int i = hullPoints[base + j + 1];
			for (int a = 0; a < 3; a++) e[a] = (position[3 * i + a] - position[3 * origin + a]) / h;
			for (int pass = 0; pass < 2; pass++) for (int prior = 0; prior < j; prior++) {
				double dot = 0; for (int a = 0; a < 3; a++) dot += e[a] * directions[9 * cell + 3 * prior + a];
				for (int a = 0; a < 3; a++) e[a] -= dot * directions[9 * cell + 3 * prior + a];
			}
			double norm = Math.hypot(Math.hypot(e[0], e[1]), e[2]);
			if (!(norm > 0 && Double.isFinite(norm))) return;
			for (int a = 0; a < 3; a++) directions[9 * cell + 3 * j + a] = e[a] / norm;
		}
	}

	private boolean fitMap(int cell) {
		int n = offsets[cell + 1] - offsets[cell], rank = ranks[cell];
		double[] root = new double[n]; double[][] feature = new double[rank][n], q = new double[rank][n], lower = new double[rank][rank];
		double maximumMass = 0;
		for (int k = offsets[cell]; k < offsets[cell + 1]; k++) maximumMass = Math.max(maximumMass, mass[markers[k]]);
		for (int i = 0; i < n; i++) {
			int marker = markers[offsets[cell] + i]; root[i] = Math.sqrt(mass[marker] / maximumMass);
			if (!(root[i] > 0 && Double.isFinite(root[i]))) return false;
			feature[0][i] = 1;
			for (int j = 1; j < rank; j++) for (int a = 0; a < 3; a++) feature[j][i] += directions[9 * cell + 3 * (j - 1) + a]
					* ((position[3 * marker + a] - center[3 * cell + a]) / h);
		}
		// Weighted row QR avoids covariance normal equations; geometry fixed the required rank above.
		for (int row = 0; row < rank; row++) {
			for (int i = 0; i < n; i++) q[row][i] = root[i] * feature[row][i];
			for (int pass = 0; pass < 2; pass++) for (int prior = 0; prior < row; prior++) {
				double projection = dot(q[row], q[prior]); lower[row][prior] += projection;
				for (int i = 0; i < n; i++) q[row][i] -= projection * q[prior][i];
			}
			double norm = 0; for (double value : q[row]) norm = Math.hypot(norm, value);
			if (!(norm > 0 && Double.isFinite(norm))) return false;
			lower[row][row] = norm; for (int i = 0; i < n; i++) q[row][i] /= norm;
		}
		double[][] local = new double[rank][n];
		for (int i = 0; i < n; i++) for (int row = rank - 1; row >= 0; row--) {
			double value = q[row][i] * root[i];
			for (int later = row + 1; later < rank; later++) value -= lower[later][row] * local[later][i];
			local[row][i] = value / lower[row][row];
		}
		for (int row = 0; row < rank; row++) {
			// Correct only arithmetic error in the fixed least-squares map.
			for (int pass = 0; pass < 2; pass++) {
				double[] z = new double[rank];
				for (int j = 0; j < rank; j++) {
					double residual = SignedDyadic.of(j == row ? 1 : 0).subtract(exactDot(local[row], feature[j])).toDouble();
					for (int prior = 0; prior < j; prior++) residual -= lower[j][prior] * z[prior];
					z[j] = residual / lower[j][j];
				}
				for (int i = 0; i < n; i++) for (int j = 0; j < rank; j++) local[row][i] += root[i] * q[j][i] * z[j];
			}
			for (double value : local[row]) if (!Double.isFinite(value)) return false;
			for (int j = 0; j < rank; j++) {
				SignedDyadic actual = SignedDyadic.ZERO;
				for (int i = 0; i < n; i++) actual = actual.add(SignedDyadic.of(local[row][i]).multiply(SignedDyadic.of(feature[j][i])));
				SignedDyadic error = actual.subtract(SignedDyadic.of(row == j ? 1 : 0));
				if ((error.signum() < 0 ? error.negate() : error).compareTo(SignedDyadic.of((n + 32.0) * Math.ulp(1.0))) > 0) return false;
			}
		}
		for (int i = 0; i < n; i++) {
			int marker = markers[offsets[cell] + i]; coefficients[4 * marker] = local[0][i];
			for (int a = 0; a < 3; a++) {
				SignedDyadic sum = SignedDyadic.ZERO;
				for (int j = 1; j < rank; j++) sum = sum.add(SignedDyadic.of(directions[9 * cell + 3 * (j - 1) + a]).multiply(SignedDyadic.of(local[j][i])));
				coefficients[4 * marker + a + 1] = sum.toDouble();
			}
		}
		// Close constant reproduction in the finest available coefficient quantum.
		for (int term = 0; term < 4; term++) {
			int pivot = markers[offsets[cell]];
			for (int k = offsets[cell] + 1; k < offsets[cell + 1]; k++)
				if (Math.ulp(coefficients[4 * markers[k] + term]) < Math.ulp(coefficients[4 * pivot + term])) pivot = markers[k];
			SignedDyadic others = SignedDyadic.ZERO;
			for (int k = offsets[cell]; k < offsets[cell + 1]; k++) if (markers[k] != pivot)
				others = others.add(SignedDyadic.of(coefficients[4 * markers[k] + term]));
			coefficients[4 * pivot + term] = SignedDyadic.of(term == 0 ? 1 : 0).subtract(others).toDouble();
		}
		// Certify the stored global-gradient map too; the basis-coordinate transform rounds once more.
		for (int term = 0; term < 4; term++) for (int j = 0; j < rank; j++) {
			SignedDyadic actual = SignedDyadic.ZERO;
			for (int i = 0; i < n; i++) actual = actual.add(SignedDyadic.of(coefficients[4 * markers[offsets[cell] + i] + term]).multiply(SignedDyadic.of(feature[j][i])));
			double expected = term == 0 ? (j == 0 ? 1 : 0) : j == 0 ? 0 : directions[9 * cell + 3 * (j - 1) + term - 1];
			SignedDyadic error = actual.subtract(SignedDyadic.of(expected));
			if ((error.signum() < 0 ? error.negate() : error).compareTo(SignedDyadic.of((n + 64.0) * Math.ulp(1.0))) > 0) return false;
		}
		return true;
	}

	private boolean fitExpandedMap(int cell) {
		int n = cellMarkerCount(cell), rank = ranks[cell];
		var localMass = new double[n]; var feature = new double[rank][n]; var localDirections = new double[rank - 1][3];
		for (int j = 0; j < rank - 1; j++) for (int a = 0; a < 3; a++) localDirections[j][a] = rank == 4
				? (j == a ? 1 : 0) : directions[9 * cell + 3 * j + a];
		for (int i = 0; i < n; i++) {
			int marker = cellMarker(cell, i); localMass[i] = mass[marker]; feature[0][i] = 1;
			for (int j = 1; j < rank; j++) for (int a = 0; a < 3; a++) feature[j][i] += localDirections[j - 1][a]
					* ((position[3 * marker + a] - center[3 * cell + a]) / h);
		}
		var fitted = ExactCellAffineFit.fit(localMass, feature, localDirections);
		if (fitted == null) return false;
		for (int i = 0; i < n; i++) {
			int marker = cellMarker(cell, i);
			for (int term = 0; term < 4; term++) {
				coefficients[4 * marker + term] = fitted[0][4 * i + term]; coefficientLow[4 * marker + term] = fitted[1][4 * i + term];
			}
		}
		expanded[cell] = true; return true;
	}

	int markerCount() { return markerCount; }
	int cellCount() { return cellCount; }
	double cellSize() { return h; }
	int markerCell(int marker) { return cells[marker]; }
	int cellMarkerCount(int cell) { return offsets[cell + 1] - offsets[cell]; }
	int cellMarker(int cell, int ordinal) { if (ordinal < 0 || ordinal >= cellMarkerCount(cell)) throw new IndexOutOfBoundsException("Unknown cell marker"); return markers[offsets[cell] + ordinal]; }
	int rank(int cell) { return ranks[cell]; }
	boolean hasRepresentableBasis(int cell) { return representable[cell]; }
	boolean expandedCell(int cell) { return expanded[cell]; }
	private boolean canRepresentDirection(int cell, int axis) {
		if (axis < 0 || axis >= 3) throw new IllegalArgumentException("Invalid spatial axis");
		int base = 4 * cell, origin = hullPoints[base];
		if (ranks[cell] < 2) return false;
		SignedDyadic[] direction = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO}; direction[axis] = SignedDyadic.of(1);
		return ranks[cell] == 4 || (ranks[cell] == 2 ? crossZero(vector(origin, hullPoints[base + 1]), direction)
				: determinant(vector(origin, hullPoints[base + 1]), vector(origin, hullPoints[base + 2]), direction).signum() == 0);
	}
	double massKilograms(int marker) { return mass[marker]; }
	double position(int marker, int axis) { return position[3 * marker + axis]; }
	double center(int cell, int axis) { return center[3 * cell + axis]; }
	double coefficient(int marker, int term) { requireRepresentable(cells[marker]); return coefficients[4 * marker + term]; }
	SignedDyadic coefficientExact(int marker, int term) { requireRepresentable(cells[marker]); return storedCoefficientExact(marker, term); }
	double coefficient(int marker, int axis, int term) {
		requireRepresentable(cells[marker]); double slope = wallSlopes[3 * cells[marker] + axis];
		if (slope != 0 && term != 0) return term != axis + 1 ? 0
				: expanded[cells[marker]] ? coefficients[4 * marker] * slope : wallCoefficients[3 * marker + axis];
		return coefficients[4 * marker + term];
	}
	SignedDyadic coefficientExact(int marker, int axis, int term) {
		requireRepresentable(cells[marker]); return storedCoefficientExact(marker, axis, term);
	}
	private SignedDyadic storedCoefficientExact(int marker, int axis, int term) {
		double slope = wallSlopes[3 * cells[marker] + axis];
		if (slope != 0 && term != 0) return term != axis + 1 ? SignedDyadic.ZERO : expanded[cells[marker]]
				? storedCoefficientExact(marker, 0).multiply(SignedDyadic.of(slope)) : SignedDyadic.of(wallCoefficients[3 * marker + axis]);
		return storedCoefficientExact(marker, term);
	}
	private SignedDyadic storedCoefficientExact(int marker, int term) {
		return SignedDyadic.of(coefficients[4 * marker + term]).add(SignedDyadic.of(coefficientLow[4 * marker + term]));
	}
	double projector(int cell, int first, int second) {
		double value = 0; for (int j = 0; j < ranks[cell] - 1; j++) value += directions[9 * cell + 3 * j + first] * directions[9 * cell + 3 * j + second]; return value;
	}
	void fit(double[][] markerVelocity, double[][] cellCoefficients) {
		checkShape(markerVelocity, markerCount); checkShape(cellCoefficients, 4 * cellCount); checkAlias(markerVelocity, cellCoefficients);
		for (double[] axis : cellCoefficients) Arrays.fill(axis, 0);
		for (int i = 0; i < markerCount; i++) {
			int cell = cells[i]; requireRepresentable(cell);
			if (expanded[cell]) continue;
			if (wallCoefficients != null && ranks[cell] == 1) {
				for (int a = 0; a < 3; a++) {
					cellCoefficients[a][4 * cell] += coefficients[4 * i] * markerVelocity[a][i];
					cellCoefficients[a][4 * cell + a + 1] += wallCoefficients[3 * i + a] * markerVelocity[a][i];
				}
				continue;
			}
			for (int a = 0; a < 3; a++) for (int term = 0; term < 4; term++) cellCoefficients[a][4 * cell + term] += coefficients[4 * i + term] * markerVelocity[a][i];
		}
		for (int cell = 0; cell < cellCount; cell++) if (expanded[cell]) for (int a = 0; a < 3; a++) for (int term = 0; term < 4; term++) {
			SignedDyadic sum = SignedDyadic.ZERO;
			for (int k = offsets[cell]; k < offsets[cell + 1]; k++) sum = sum.add(storedCoefficientExact(markers[k], a, term).multiply(SignedDyadic.of(markerVelocity[a][markers[k]])));
			cellCoefficients[a][4 * cell + term] = sum.toDouble();
		}
	}
	void transposeLoad(double[][] cellLoad, double[][] markerForce) {
		checkShape(cellLoad, 4 * cellCount); checkShape(markerForce, markerCount); checkAlias(cellLoad, markerForce);
		for (double[] axis : markerForce) Arrays.fill(axis, 0);
		for (int i = 0; i < markerCount; i++) {
			int cell = cells[i]; requireRepresentable(cell);
			if (expanded[cell]) {
				for (int a = 0; a < 3; a++) {
					SignedDyadic sum = SignedDyadic.ZERO;
					for (int term = 0; term < 4; term++) sum = sum.add(storedCoefficientExact(i, a, term).multiply(SignedDyadic.of(cellLoad[a][4 * cell + term])));
					markerForce[a][i] = sum.toDouble();
				}
				continue;
			}
			if (wallCoefficients != null && ranks[cell] == 1) {
				for (int a = 0; a < 3; a++) markerForce[a][i] = coefficients[4 * i] * cellLoad[a][4 * cell]
						+ wallCoefficients[3 * i + a] * cellLoad[a][4 * cell + a + 1];
				continue;
			}
			for (int a = 0; a < 3; a++) for (int term = 0; term < 4; term++) markerForce[a][i] += coefficients[4 * i + term] * cellLoad[a][4 * cell + term];
		}
	}
	void predict(double[][] cellCoefficients, double[][] markerVelocity) {
		checkShape(cellCoefficients, 4 * cellCount); checkShape(markerVelocity, markerCount); checkAlias(cellCoefficients, markerVelocity);
		for (int i = 0; i < markerCount; i++) for (int a = 0; a < 3; a++) markerVelocity[a][i] = value(cellCoefficients, a, cells[i], position[3 * i], position[3 * i + 1], position[3 * i + 2]);
	}
	double value(double[][] cellCoefficients, int axis, int cell, double x, double y, double z) {
		requireRepresentable(cell); int base = 4 * cell;
		return cellCoefficients[axis][base] + cellCoefficients[axis][base + 1] * ((x - center[3 * cell]) / h)
				+ cellCoefficients[axis][base + 2] * ((y - center[3 * cell + 1]) / h)
				+ cellCoefficients[axis][base + 3] * ((z - center[3 * cell + 2]) / h);
	}
	double divergence(double[][] cellCoefficients, int cell) {
		requireRepresentable(cell); return (cellCoefficients[0][4 * cell + 1] + cellCoefficients[1][4 * cell + 2] + cellCoefficients[2][4 * cell + 3]) / h;
	}
	void pointWeights(int cell, double x, double y, double z, double[] result) {
		requireRepresentable(cell); if (result.length != 4) throw new IllegalArgumentException("Expected four affine weights");
		result[0] = 1; result[1] = (x - center[3 * cell]) / h; result[2] = (y - center[3 * cell + 1]) / h; result[3] = (z - center[3 * cell + 2]) / h;
	}
	/** Integral of the derivative of the same restricted polynomial used by every surface trace. */
	void volumeDivergenceWeights(int cell, int axis, double volume, double[] result) {
		requireRepresentable(cell);
		if (axis < 0 || axis >= 3 || result.length != 4 || !(volume > 0 && Double.isFinite(volume)))
			throw new IllegalArgumentException("Invalid wet-volume derivative");
		Arrays.fill(result, 0); result[axis + 1] = volume / h;
	}
	/** Admitted affine field vanishing on an entire axis-normal wall plane. */
	boolean wallNormalWeights(int cell, int axis, double wall, double[] result) {
		requireRepresentable(cell);
		if (result.length != 4 || !Double.isFinite(wall)) throw new IllegalArgumentException("Invalid fixed wall plane");
		Arrays.fill(result, 0);
		if (wallSlopes[3 * cell + axis] == 0 && !canRepresentDirection(cell, axis)) return false;
		result[0] = (center(cell, axis) - wall) / h; result[axis + 1] = 1;
		return true;
	}

	/** Exact membership in the source affine hull, independently of the chosen field extension. */
	boolean canRepresentPoint(int cell, double x, double y, double z) {
		if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) || ranks[cell] == 0) return false;
		int base = 4 * cell, origin = hullPoints[base];
		SignedDyadic[] point = {difference(x, position[3 * origin]), difference(y, position[3 * origin + 1]), difference(z, position[3 * origin + 2])};
		return switch (ranks[cell]) {
			case 1 -> point[0].signum() == 0 && point[1].signum() == 0 && point[2].signum() == 0;
			case 2 -> crossZero(vector(origin, hullPoints[base + 1]), point);
			case 3 -> determinant(vector(origin, hullPoints[base + 1]), vector(origin, hullPoints[base + 2]), point).signum() == 0;
			default -> true;
		};
	}
	private boolean samePoint(int a, int b) { return position[3 * a] == position[3 * b] && position[3 * a + 1] == position[3 * b + 1] && position[3 * a + 2] == position[3 * b + 2]; }
	private boolean collinear(int origin, int a, int b) { return crossZero(vector(origin, a), vector(origin, b)); }
	private SignedDyadic determinant(int origin, int a, int b, int c) { return determinant(vector(origin, a), vector(origin, b), vector(origin, c)); }
	private SignedDyadic[] vector(int origin, int point) { return new SignedDyadic[]{difference(position[3 * point], position[3 * origin]), difference(position[3 * point + 1], position[3 * origin + 1]), difference(position[3 * point + 2], position[3 * origin + 2])}; }
	private static SignedDyadic difference(double a, double b) { return SignedDyadic.of(a).subtract(SignedDyadic.of(b)); }
	private static boolean crossZero(SignedDyadic[] a, SignedDyadic[] b) {
		for (int i = 0; i < 3; i++) if (!a[(i + 1) % 3].multiply(b[(i + 2) % 3]).equals(a[(i + 2) % 3].multiply(b[(i + 1) % 3]))) return false; return true;
	}
	private static SignedDyadic determinant(SignedDyadic[] a, SignedDyadic[] b, SignedDyadic[] c) {
		SignedDyadic value = SignedDyadic.ZERO;
		for (int i = 0; i < 3; i++) value = value.add(a[i].multiply(b[(i + 1) % 3].multiply(c[(i + 2) % 3]).subtract(b[(i + 2) % 3].multiply(c[(i + 1) % 3])))); return value;
	}
	private void requireRepresentable(int cell) {
		if (!representable[cell]) throw new FluidDomain.PressureSolveBlockedException("Cell affine velocity is not representable at cell " + cell + ", geometric rank " + ranks[cell]);
	}
	private static double dot(double[] a, double[] b) { double value = 0; for (int i = 0; i < a.length; i++) value += a[i] * b[i]; return value; }
	private static SignedDyadic exactDot(double[] a, double[] b) {
		SignedDyadic value = SignedDyadic.ZERO;
		for (int i = 0; i < a.length; i++) value = value.add(SignedDyadic.of(a[i]).multiply(SignedDyadic.of(b[i])));
		return value;
	}
	private static void checkShape(double[][] vectors, int count) {
		if (vectors.length != 3) throw new IllegalArgumentException("Expected three affine axes");
		for (double[] axis : vectors) if (axis.length != count) throw new IllegalArgumentException("Affine vector shape mismatch");
		if (vectors[0] == vectors[1] || vectors[0] == vectors[2] || vectors[1] == vectors[2]) throw new IllegalArgumentException("Aliased affine axes");
	}
	private static void checkAlias(double[][] first, double[][] second) { for (double[] a : first) for (double[] b : second) if (a == b) throw new IllegalArgumentException("Aliased affine vectors"); }
}
