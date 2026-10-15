package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.Arrays;

/**
 * Actual-mass fit and adjoint for q_axis times affine fields on each supported open block.
 * Wet walls determine q; reliable support admits strain while actual positions retain rotation.
 */
final class CellAffineVelocity {
	private final int physicalCellCount, blockCount, markerCount, sizeX, sizeY;
	private final double h;
	private final int[] cells, offsets, markers, ranks, hullPoints;
	private final int[] blockCells, supports, extraOffsets, localOrdinals;
	private final double[] mass, position, center, normalShapes;
	private final boolean[] normalAxes;
	private final CellAffineFit.Model[] models;

	static CellAffineVelocity capture(FluidDomain domain) { return new CellAffineVelocity(domain, null); }
	static CellAffineVelocity capture(FluidDomain domain, LiquidPressureGeometry geometry) {
		if (geometry == null) throw new IllegalArgumentException("Missing affine wet geometry");
		return new CellAffineVelocity(domain, geometry);
	}
	private CellAffineVelocity(FluidDomain domain, LiquidPressureGeometry geometry) {
		physicalCellCount = domain.cellCount; markerCount = domain.particles.count(); h = domain.config.cellSize();
		sizeX = domain.sizeX; sizeY = domain.sizeY;
		cells = new int[markerCount]; mass = new double[markerCount]; position = new double[3 * markerCount];
		int[] active = new int[physicalCellCount];
		for (int i = 0; i < markerCount; i++) {
			cells[i] = domain.particleCellIndex(i);
			CellRemapMicroTopology.markerSupport(domain, i);
			mass[i] = domain.unitScale.kilograms(domain.particles.mass(i));
			if (!(mass[i] > 0 && Double.isFinite(mass[i]))) throw new IllegalStateException("Invalid affine marker mass");
			position[3 * i] = domain.particles.positionX(i); position[3 * i + 1] = domain.particles.positionY(i); position[3 * i + 2] = domain.particles.positionZ(i);
			for (int a = 0; a < 3; a++) if (!Double.isFinite(position[3 * i + a])) throw new IllegalStateException("Invalid affine marker position");
			active[cells[i]] |= 1 << CellRemapMicroTopology.positionMicro((float) h, (float) position[3 * i], (float) position[3 * i + 1], (float) position[3 * i + 2]);
		}
		extraOffsets = new int[physicalCellCount + 1];
		for (int cell = 0; cell < physicalCellCount; cell++) {
			int count = 0;
			for (int support : CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))) if ((active[cell] & support) != 0)
				count += splitMicro(geometry, cell, support, active[cell]) ? Integer.bitCount(active[cell] & support) : 1;
			extraOffsets[cell + 1] = Math.addExact(extraOffsets[cell], Math.max(0, count - 1));
		}
		blockCount = Math.addExact(physicalCellCount, extraOffsets[physicalCellCount]);
		blockCells = new int[blockCount]; supports = new int[blockCount];
		for (int cell = 0; cell < physicalCellCount; cell++) {
			blockCells[cell] = cell; int ordinal = 0;
			for (int support : CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))) if ((active[cell] & support) != 0) {
				int remaining = splitMicro(geometry, cell, support, active[cell]) ? active[cell] & support : 0;
				do {
					int extra = extraOffsets[cell] + ordinal - 1, block = ordinal == 0 ? cell : physicalCellCount + extra;
					blockCells[block] = cell; supports[block] = remaining == 0 ? support : Integer.lowestOneBit(remaining);
					ordinal++; remaining &= remaining - 1;
				} while (remaining != 0);
			}
		}
		offsets = new int[blockCount + 1]; markers = new int[markerCount]; ranks = new int[blockCount];
		center = new double[3 * blockCount]; normalShapes = new double[9 * blockCount];
		normalAxes = new boolean[3 * blockCount];
		for (int key = 0; key < normalAxes.length; key++) normalShapes[3 * key] = 1;
		hullPoints = new int[4 * blockCount]; Arrays.fill(hullPoints, -1);
		models = new CellAffineFit.Model[blockCount]; localOrdinals = new int[markerCount];
		for (int i = 0; i < markerCount; i++) {
			int micro = CellRemapMicroTopology.positionMicro((float) h, (float) position[3 * i], (float) position[3 * i + 1], (float) position[3 * i + 2]);
			cells[i] = blockForMicro(cells[i], micro);
			if (cells[i] < 0) throw new IllegalStateException("Affine source lost its fixed open component");
			offsets[cells[i] + 1]++;
		}
		for (int c = 0; c < blockCount; c++) offsets[c + 1] += offsets[c];
		int[] cursor = offsets.clone();
		for (int i = 0; i < markerCount; i++) {
			localOrdinals[i] = cursor[cells[i]] - offsets[cells[i]];
			markers[cursor[cells[i]]++] = i;
		}
		for (int c = 0; c < blockCount; c++) if (offsets[c] != offsets[c + 1]) {
			captureCenter(c);
			if (geometry != null) captureNormalShapes(geometry, c);
			models[c] = captureModel(c);
		}
	}
	private CellAffineFit.Model captureModel(int block) {
		int n = blockMarkerCount(block);
		var localMass = new double[n]; var relative = new double[n][3]; var error = new double[n][3];
		var shape = new double[3][n]; var shaped = new boolean[3]; var centerOffset = new double[3];
		for (int axis = 0; axis < 3; axis++) {
			shaped[axis] = hasNormalShape(block, axis);
			centerOffset[axis] = (center(block, axis) - physicalCenter(block, axis)) / h;
		}
		for (int i = 0; i < n; i++) {
			int marker = blockMarker(block, i); localMass[i] = mass[marker];
			for (int axis = 0; axis < 3; axis++) {
				double coordinate = position[3 * marker + axis];
				relative[i][axis] = (coordinate - center(block, axis)) / h;
				error[i][axis] = .5 * Math.ulp((float) coordinate) / h;
				shape[axis][i] = shapeValue(block, axis, coordinate);
			}
		}
		return CellAffineFit.capture(localMass, relative, error, shape, shaped, centerOffset);
	}

	private static boolean splitMicro(LiquidPressureGeometry geometry, int cell, int support, int sourceMicros) {
		return geometry != null && geometry.needsMicroBlocks(cell, support)
				&& (sourceMicros & support) == (geometry.wetMicroMask(cell) & support);
	}
	private void captureNormalShapes(LiquidPressureGeometry geometry, int cell) {
		var profile = geometry.componentSnapshot(physicalCell(cell), blockSupport(cell)); if (profile == null) return;
		int[] planes = wallPlanes(profile);
		for (int axis = 0; axis < 3; axis++) if (planes[axis] != 0) {
			int key = 3 * cell + axis, base = 3 * key; normalAxes[key] = true;
			Arrays.fill(normalShapes, base, base + 3, 0);
			int count = Integer.bitCount(planes[axis]);
			double lo = .5 * Integer.numberOfTrailingZeros(planes[axis]) - .5;
			if (count == 1) { normalShapes[base] = -lo; normalShapes[base + 1] = 1; }
			else if (count == 2) {
				double hi = .5 * (31 - Integer.numberOfLeadingZeros(planes[axis])) - .5;
				normalShapes[base] = -lo * hi; normalShapes[base + 1] = lo + hi; normalShapes[base + 2] = -1;
			}
		}
	}
	static int[] wallPlanes(LiquidPressureGeometry.Profile profile) {
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

	/** On-demand diagnostic hull; production modes use the reliable support captured by CellAffineFit. */
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
	}

	int markerCount() { return markerCount; }
	int blockCount() { return blockCount; }
	int physicalCellCount() { return physicalCellCount; }
	double cellSize() { return h; }
	int markerBlock(int marker) { return cells[marker]; }
	int physicalCell(int block) { return blockCells[block]; }
	int blockSupport(int block) { return supports[block]; }
	int blockCountForCell(int physicalCell) {
		if (physicalCell < 0 || physicalCell >= physicalCellCount) throw new IllegalArgumentException("Invalid velocity cell");
		return supports[physicalCell] == 0 ? 0 : 1 + extraOffsets[physicalCell + 1] - extraOffsets[physicalCell];
	}
	int blockForCell(int physicalCell, int ordinal) {
		if (ordinal < 0 || ordinal >= blockCountForCell(physicalCell)) throw new IndexOutOfBoundsException("Unknown cell velocity block");
		return ordinal == 0 ? physicalCell : physicalCellCount + extraOffsets[physicalCell] + ordinal - 1;
	}
	int blockMarkerCount(int block) { return offsets[block + 1] - offsets[block]; }
	int blockMarker(int block, int ordinal) { if (ordinal < 0 || ordinal >= blockMarkerCount(block)) throw new IndexOutOfBoundsException("Unknown block marker"); return markers[offsets[block] + ordinal]; }
	int blockForMicro(int physicalCell, int micro) {
		if (physicalCell < 0 || physicalCell >= physicalCellCount || micro < 0 || micro >= 8) throw new IllegalArgumentException("Invalid velocity microbox");
		int bit = 1 << micro;
		if ((supports[physicalCell] & bit) != 0) return physicalCell;
		for (int slot = extraOffsets[physicalCell]; slot < extraOffsets[physicalCell + 1]; slot++) {
			int block = physicalCellCount + slot; if ((supports[block] & bit) != 0) return block;
		}
		return -1;
	}
	int blockForPoint(int physicalCell, double x, double y, double z) {
		if (physicalCell < 0 || physicalCell >= physicalCellCount || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
			throw new IllegalArgumentException("Invalid velocity sample point");
		int micro = (x >= (physicalCell % sizeX + .5) * h ? 1 : 0)
				| (y >= (physicalCell / sizeX % sizeY + .5) * h ? 2 : 0)
				| (z >= (physicalCell / (sizeX * sizeY) + .5) * h ? 4 : 0);
		return blockForMicro(physicalCell, micro);
	}
	int rank(int cell) {
		if (ranks[cell] == 0 && blockMarkerCount(cell) > 0) captureHull(cell);
		return ranks[cell];
	}
	int reliableRank(int block) { return models[block] == null ? 0 : models[block].reliableRank(); }
	int modeCount(int block) { return models[block] == null ? 0 : models[block].modeCount(); }
	SignedDyadic modeCoefficientExact(int block, int mode, int axis, int term) {
		requireRepresentable(block); return models[block].fieldExact(mode, axis, term);
	}
	SignedDyadic modeWeightExact(int marker, int mode, int inputAxis) {
		int block = cells[marker]; requireRepresentable(block);
		return models[block].weightExact(mode, inputAxis, localOrdinals[marker]);
	}

	ExactRational normalizedCenterOffsetExact(int block, int axis) {
		SignedDyadic delta = difference(physicalCenter(block, axis), center(block, axis)), size = SignedDyadic.of(h);
		int shift = delta.exponent() - size.exponent();
		return new ExactRational(delta.significand().shiftLeft(Math.max(0, shift)), size.significand().shiftLeft(Math.max(0, -shift)));
	}
	boolean hasRepresentableBasis(int cell) { return models[cell] != null; }
	double massKilograms(int marker) { return mass[marker]; }
	double position(int marker, int axis) { return position[3 * marker + axis]; }
	double center(int cell, int axis) { return center[3 * cell + axis]; }
	/** On-demand observation of A F for independent diagnostics. Production retains both factors. */
	SignedDyadic coefficientExact(int marker, int outputAxis, int inputAxis, int term) {
		int block = cells[marker]; requireRepresentable(block); SignedDyadic sum = SignedDyadic.ZERO;
		for (int mode = 0; mode < modeCount(block); mode++)
			sum = sum.add(modeCoefficientExact(block, mode, outputAxis, term).multiply(modeWeightExact(marker, mode, inputAxis)));
		return sum;
	}
	void fit(double[][] markerVelocity, double[][] cellCoefficients) {
		checkShape(markerVelocity, markerCount); checkShape(cellCoefficients, 4 * blockCount); checkAlias(markerVelocity, cellCoefficients);
		for (double[] axis : cellCoefficients) Arrays.fill(axis, 0);
		for (int block = 0; block < blockCount; block++) if (models[block] != null) {
			var modal = new SignedDyadic[modeCount(block)]; Arrays.fill(modal, SignedDyadic.ZERO);
			for (int mode = 0; mode < modal.length; mode++)
				for (int k = offsets[block]; k < offsets[block + 1]; k++) {
					int marker = markers[k];
					for (int axis = 0; axis < 3; axis++) modal[mode] = modal[mode].add(
							modeWeightExact(marker, mode, axis).multiply(SignedDyadic.of(markerVelocity[axis][marker])));
				}
			for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++) {
				SignedDyadic sum = SignedDyadic.ZERO;
				for (int mode = 0; mode < modal.length; mode++)
					sum = sum.add(modeCoefficientExact(block, mode, axis, term).multiply(modal[mode]));
				cellCoefficients[axis][4 * block + term] = sum.toDouble();
			}
		}
	}
	void transposeLoad(double[][] cellLoad, double[][] markerForce) {
		checkShape(cellLoad, 4 * blockCount); checkShape(markerForce, markerCount); checkAlias(cellLoad, markerForce);
		for (double[] axis : markerForce) Arrays.fill(axis, 0);
		for (int block = 0; block < blockCount; block++) if (models[block] != null) {
			var modal = new SignedDyadic[modeCount(block)]; Arrays.fill(modal, SignedDyadic.ZERO);
			for (int mode = 0; mode < modal.length; mode++) for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++)
				modal[mode] = modal[mode].add(modeCoefficientExact(block, mode, axis, term)
						.multiply(SignedDyadic.of(cellLoad[axis][4 * block + term])));
			for (int k = offsets[block]; k < offsets[block + 1]; k++) {
				int marker = markers[k];
				for (int axis = 0; axis < 3; axis++) {
					SignedDyadic sum = SignedDyadic.ZERO;
					for (int mode = 0; mode < modal.length; mode++) sum = sum.add(modeWeightExact(marker, mode, axis).multiply(modal[mode]));
					markerForce[axis][marker] = sum.toDouble();
				}
			}
		}
	}

	void predict(double[][] cellCoefficients, double[][] markerVelocity) {
		checkShape(cellCoefficients, 4 * blockCount); checkShape(markerVelocity, markerCount); checkAlias(cellCoefficients, markerVelocity);
		for (int i = 0; i < markerCount; i++) for (int a = 0; a < 3; a++) markerVelocity[a][i] = value(cellCoefficients, a, cells[i], position[3 * i], position[3 * i + 1], position[3 * i + 2]);
	}
	double value(double[][] cellCoefficients, int axis, int cell, double x, double y, double z) {
		requireRepresentable(cell); int base = 4 * cell;
		double value = cellCoefficients[axis][base] + cellCoefficients[axis][base + 1] * ((x - center[3 * cell]) / h)
				+ cellCoefficients[axis][base + 2] * ((y - center[3 * cell + 1]) / h)
				+ cellCoefficients[axis][base + 3] * ((z - center[3 * cell + 2]) / h);
		return value * shapeValue(cell, axis, axis == 0 ? x : axis == 1 ? y : z);
	}
	double divergence(double[][] cellCoefficients, int cell) {
		requireRepresentable(cell); double value = 0;
		for (int axis = 0; axis < 3; axis++) {
			int base = 9 * cell + 3 * axis; double coordinate = center(cell, axis);
			double t = (coordinate - physicalCenter(cell, axis)) / h;
			value += (normalShapes[base + 1] + 2 * normalShapes[base + 2] * t) * cellCoefficients[axis][4 * cell]
					+ shapeValue(cell, axis, coordinate) * cellCoefficients[axis][4 * cell + axis + 1];
		}
		return value / h;
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
		if (hasNormalShape(cell, axis)) throw new IllegalArgumentException("Shaped volume derivative requires polynomial moments");
		Arrays.fill(result, 0); result[axis + 1] = volume / h;
	}
	boolean hasNormalShape(int block, int axis) { return normalAxes[3 * block + axis]; }
	double[] normalShape(int block, int axis) { return Arrays.copyOfRange(normalShapes, 9 * block + 3 * axis, 9 * block + 3 * axis + 3); }
	double shapeValue(int block, int axis, double physicalCoordinate) {
		if (!hasNormalShape(block, axis)) return 1;
		int base = 9 * block + 3 * axis; double t = (physicalCoordinate - physicalCenter(block, axis)) / h;
		return Math.fma(Math.fma(normalShapes[base + 2], t, normalShapes[base + 1]), t, normalShapes[base]);
	}
	private double physicalCenter(int block, int axis) {
		int cell = physicalCell(block), coordinate = axis == 0 ? cell % sizeX : axis == 1 ? cell / sizeX % sizeY : cell / (sizeX * sizeY);
		return (coordinate + .5) * h;
	}
	/** h times the derivative, in monomial order 1,x,y,z,x2,xy,xz,y2,yz,z2 about the physical cell centre. */
	void divergencePolynomial(int block, int axis, int term, double[] result) {
		if (axis < 0 || axis >= 3 || term < 0 || term >= 4 || result.length != 10) throw new IllegalArgumentException("Invalid shaped derivative polynomial");
		Arrays.fill(result, 0); int base = 9 * block + 3 * axis;
		double q0 = normalShapes[base], q1 = normalShapes[base + 1], q2 = normalShapes[base + 2];
		if (term == 0) { result[0] = q1; result[axis + 1] = 2 * q2; return; }
		int direction = term - 1; double offset = (physicalCenter(block, direction) - center(block, direction)) / h;
		result[0] = q1 * offset; result[direction + 1] += q1; result[axis + 1] += 2 * q2 * offset;
		int quadratic = axis == 0 ? direction == 0 ? 4 : direction == 1 ? 5 : 6
				: axis == 1 ? direction == 0 ? 5 : direction == 1 ? 7 : 8 : direction == 0 ? 6 : direction == 1 ? 8 : 9;
		result[quadratic] += 2 * q2;
		if (direction == axis) { result[0] += q0; result[axis + 1] += q1; result[quadratic] += q2; }
	}

	/** Exact membership in the source affine hull, independently of the chosen field extension. */
	boolean canRepresentPoint(int cell, double x, double y, double z) {
		if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) || rank(cell) == 0) return false;
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
		if (models[cell] == null) throw new IllegalStateException("Missing cell velocity model at block " + cell);
	}
	private static void checkShape(double[][] vectors, int count) {
		if (vectors.length != 3) throw new IllegalArgumentException("Expected three affine axes");
		for (double[] axis : vectors) if (axis.length != count) throw new IllegalArgumentException("Affine vector shape mismatch");
		if (vectors[0] == vectors[1] || vectors[0] == vectors[2] || vectors[1] == vectors[2]) throw new IllegalArgumentException("Aliased affine axes");
	}
	private static void checkAlias(double[][] first, double[][] second) { for (double[] a : first) for (double[] b : second) if (a == b) throw new IllegalArgumentException("Aliased affine vectors"); }
}
