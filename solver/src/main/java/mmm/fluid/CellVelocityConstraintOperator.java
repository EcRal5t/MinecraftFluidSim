package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import org.ejml.data.DMatrixSparseCSC;

/** Fixed volume and normal-trace constraints pulled back through the actual-mass cell basis. */
final class CellVelocityConstraintOperator {
	record Block(int velocityBlock, int axis, SignedDyadic[] exactCoefficients) {
		Block(int velocityBlock, int axis, double[] coefficients) { this(velocityBlock, axis, coefficients, 1); }
		Block(int velocityBlock, int axis, double[] coefficients, double scale) {
			this(velocityBlock, axis, scaled(coefficients, scale));
		}
		Block {
			if (exactCoefficients == null || exactCoefficients.length != 4)
				throw new IllegalArgumentException("Invalid cell constraint block");
			exactCoefficients = exactCoefficients.clone();
			for (var value : exactCoefficients) if (value == null)
				throw new IllegalArgumentException("Missing cell constraint coefficient");
		}
		@Override public SignedDyadic[] exactCoefficients() { return exactCoefficients.clone(); }
		SignedDyadic coefficientExact(int term) { return exactCoefficients[term]; }
		double[] coefficients() {
			var result = new double[4];
			for (int term = 0; term < 4; term++) result[term] = exactCoefficients[term].toDouble();
			return result;
		}
		private static SignedDyadic[] scaled(double[] coefficients, double scale) {
			if (coefficients == null || coefficients.length != 4 || !Double.isFinite(scale))
				throw new IllegalArgumentException("Invalid cell constraint block or scale");
			var result = new SignedDyadic[4]; var weight = SignedDyadic.of(scale);
			for (int term = 0; term < 4; term++) result[term] = SignedDyadic.of(coefficients[term]).multiply(weight);
			return result;
		}
	}
	record Row(List<Block> blocks, double storage) {
		Row {
			if (blocks == null || !(storage >= 0 && Double.isFinite(storage))) throw new IllegalArgumentException("Invalid cell constraint row");
			blocks = List.copyOf(blocks);
		}
	}
	private static final int MODAL_STRIDE = 12;
	private record Factor(int velocityBlock, int modeCount, SignedDyadic[] gram) { }

	private final CellAffineVelocity basis;
	private final int markerCount;
	private final double[] storage, canonicalDiagonal;
	private final int[] factorOffsets, factorIndices;
	private final Factor[] factors;
	private final SignedDyadic[] exactInverseMass, exactStorage, factorCoefficients, cellLoad, cellResponse;

	CellVelocityConstraintOperator(CellAffineVelocity basis, List<Row> rows) {
		if (basis == null || rows == null) throw new IllegalArgumentException("Constraint snapshot is missing");
		this.basis = basis;
		markerCount = basis.markerCount(); exactInverseMass = new SignedDyadic[markerCount];
		for (int marker = 0; marker < markerCount; marker++) {
			double inverseMass = 1 / basis.massKilograms(marker);
			if (!(inverseMass > 0 && Double.isFinite(inverseMass)))
				throw new IllegalArgumentException("Actual marker inertia is unrepresentable");
			exactInverseMass[marker] = SignedDyadic.of(inverseMass);
		}
		var factorSlots = new TreeMap<Integer, Integer>();
		for (Row definition : rows) for (Block block : definition.blocks) {
			if (block.velocityBlock < 0 || block.velocityBlock >= basis.blockCount() || block.axis < 0 || block.axis >= 3)
				throw new IllegalArgumentException("Cell constraint references an invalid velocity block");
			for (var value : block.exactCoefficients) if (value.signum() != 0) factorSlots.put(block.velocityBlock, 0);
		}
		factors = new Factor[factorSlots.size()]; int slot = 0;
		for (var entry : factorSlots.entrySet()) {
			entry.setValue(slot); factors[slot++] = captureFactor(entry.getKey());
		}
		cellLoad = new SignedDyadic[Math.multiplyExact(MODAL_STRIDE, factors.length)]; cellResponse = new SignedDyadic[cellLoad.length];
		storage = new double[rows.size()]; exactStorage = new SignedDyadic[rows.size()];
		factorOffsets = new int[rows.size() + 1];
		var declaredIndices = new ArrayList<Integer>(); var declared = new ArrayList<SignedDyadic>();
		for (int row = 0; row < rows.size(); row++) {
			Row definition = rows.get(row); storage[row] = definition.storage; exactStorage[row] = SignedDyadic.of(storage[row]);
			var merged = new TreeMap<Integer, SignedDyadic>();
			for (Block block : definition.blocks) {
				boolean active = false; for (var value : block.exactCoefficients) active |= value.signum() != 0;
				if (!active) continue;
				int factor = factorSlots.get(block.velocityBlock);
				for (int mode = 0; mode < factors[factor].modeCount; mode++) {
					SignedDyadic value = SignedDyadic.ZERO;
					for (int term = 0; term < 4; term++) if (block.exactCoefficients[term].signum() != 0)
						value = value.add(block.exactCoefficients[term]
								.multiply(basis.modeCoefficientExact(block.velocityBlock, mode, block.axis, term)));
					if (value.signum() != 0) merged.merge(MODAL_STRIDE * factor + mode, value, SignedDyadic::add);
				}
			}
			for (var entry : merged.entrySet()) if (entry.getValue().signum() != 0) {
				declaredIndices.add(entry.getKey()); declared.add(entry.getValue());
			}
			factorOffsets[row + 1] = declaredIndices.size();
		}
		factorIndices = declaredIndices.stream().mapToInt(Integer::intValue).toArray(); factorCoefficients = declared.toArray(SignedDyadic[]::new);
		canonicalDiagonal = captureDiagonal();
	}


	/** Sparse physical-pressure interpolation; each column owns its fine rows and weights. */
	record CoarseSpace(CellVelocityConstraintOperator operator, int[][] fineRows, double[][] weights) { }
	private CoarseSpace coarseSpace;
	private int[][] coarseRows;
	private double[][] coarseWeights;
	CoarseSpace coarseSpace() {
		if (coarseSpace == null && coarseRows != null)
			coarseSpace = new CoarseSpace(new CellVelocityConstraintOperator(this, coarseRows, coarseWeights), coarseRows, coarseWeights);
		return coarseSpace;
	}
	void coarseRows(int[][] fineRows, double[][] weights) {
		coarseRows = fineRows; coarseWeights = weights; coarseSpace = null;
	}
	/** Reuses the captured actual inertia; only the pressure constraints are restricted. */
	private CellVelocityConstraintOperator(CellVelocityConstraintOperator source, int[][] rows, double[][] weights) {
		if (rows.length != weights.length) throw new IllegalArgumentException("Coarse pressure interpolation shape mismatch");
		basis = source.basis; markerCount = source.markerCount; factors = source.factors;
		exactInverseMass = source.exactInverseMass;
		storage = new double[rows.length]; exactStorage = new SignedDyadic[rows.length];
		factorOffsets = new int[rows.length + 1];
		var indices = new ArrayList<Integer>(); var coefficients = new ArrayList<SignedDyadic>();
		int[] storageOwner = new int[source.rowCount()]; Arrays.fill(storageOwner, -1);
		for (int coarse = 0; coarse < rows.length; coarse++) {
			if (rows[coarse].length != weights[coarse].length) throw new IllegalArgumentException("Coarse pressure column shape mismatch");
			var merged = new TreeMap<Integer, SignedDyadic>(); var gas = SignedDyadic.ZERO;
			for (int i = 0; i < rows[coarse].length; i++) {
				int row = rows[coarse][i]; var weight = SignedDyadic.of(weights[coarse][i]);
				if (weight.signum() == 0) continue;
				for (int e = source.factorOffsets[row]; e < source.factorOffsets[row + 1]; e++)
					merged.merge(source.factorIndices[e], source.factorCoefficients[e].multiply(weight), SignedDyadic::add);
				if (source.storage[row] > 0) {
					// Finite gas pressure has its own coordinate, retaining diagonal storage under T^T S T.
					if (storageOwner[row] >= 0) throw new IllegalArgumentException("Finite gas storage needs an independent coarse coordinate");
					storageOwner[row] = coarse;
					gas = gas.add(source.exactStorage[row].multiply(weight).multiply(weight));
				}
			}
			for (var entry : merged.entrySet()) if (entry.getValue().signum() != 0) {
				indices.add(entry.getKey()); coefficients.add(entry.getValue());
			}
			factorOffsets[coarse + 1] = indices.size(); exactStorage[coarse] = gas; storage[coarse] = gas.toDouble();
		}
		factorIndices = indices.stream().mapToInt(Integer::intValue).toArray(); factorCoefficients = coefficients.toArray(SignedDyadic[]::new);
		cellLoad = new SignedDyadic[MODAL_STRIDE * factors.length]; cellResponse = new SignedDyadic[cellLoad.length];
		canonicalDiagonal = captureDiagonal();
	}
	PressureRowBasis coarseBasis() { return PressureRowBasis.coarse(canonicalDiagonal); }

	private Factor captureFactor(int block) {
		int count = basis.blockMarkerCount(block), modes = basis.modeCount(block);
		if (modes < 0 || modes > MODAL_STRIDE) throw new IllegalArgumentException("Invalid cell mode count");
		if (modes == 0) return new Factor(block, 0, new SignedDyadic[0]);
		var fit = new SignedDyadic[modes][3 * count]; var inertia = new SignedDyadic[3 * count];
		for (int axis = 0; axis < 3; axis++) for (int ordinal = 0; ordinal < count; ordinal++) {
			int marker = basis.blockMarker(block, ordinal), column = axis * count + ordinal;
			inertia[column] = exactInverseMass[marker];
			for (int mode = 0; mode < modes; mode++) fit[mode][column] = basis.modeWeightExact(marker, mode, axis);
		}
		return new Factor(block, modes, ConstraintNumerics.massGram(fit, inertia));
	}

	int rowCount() { return storage.length; }
	List<PressureRowBasis> rowPatches() {
		return PressureRowBasis.patches(factorOffsets, factorIndices, factors.length, MODAL_STRIDE, storage, canonicalDiagonal);
	}

	record WhitenedSize(int features, int maximumEntries) { }
	private PressureResponse pressureResponse;
	private PressureResponse pressureResponse() {
		if (pressureResponse == null) pressureResponse = capturePressureResponse();
		return pressureResponse;
	}
	long pressureResponseBytes() { return pressureResponse().bytes(); }
	WhitenedSize whitenedSize(PressureRowBasis selected) { return pressureResponse().size(selected); }
	DMatrixSparseCSC massWhitenedTranspose(PressureRowBasis selected, double[] scale) {
		return pressureResponse().restrict(selected, scale);
	}
	double applyCachedResponse(double[] high, double[] low, double[] result) {
		checkRows(high); checkRows(low); checkRows(result);
		if (high == result || low == result) throw new IllegalArgumentException("Aliased constraint vectors");
		return pressureResponse().apply(high, low, result);
	}
	/** Capture the actual constraint and inertia once; whitening is confined to preconditioner preparation. */
	private PressureResponse capturePressureResponse() {
		int[] starts = new int[factors.length + 1];
		for (int slot = 0; slot < factors.length; slot++) starts[slot + 1] = Math.addExact(starts[slot],
				Math.multiplyExact(3, basis.blockMarkerCount(factors[slot].velocityBlock)));
		int features = starts[factors.length]; long entries = 0;
		for (int row = 0; row < rowCount(); row++) {
			int entry = factorOffsets[row];
			while (entry < factorOffsets[row + 1]) {
				int slot = factorIndices[entry] / MODAL_STRIDE;
				entries += starts[slot + 1] - starts[slot];
				do { entry++; } while (entry < factorOffsets[row + 1] && factorIndices[entry] / MODAL_STRIDE == slot);
			}
			if (storage[row] > 0) { features = Math.addExact(features, 1); entries++; }
		}
		if (entries > Integer.MAX_VALUE) throw new PressureRowBasis.Unavailable("Pressure constraint cache exceeds sparse index capacity");
		if (40.0 * entries + 40.0 * features + 8.0 * (rowCount() + 1L) > PressureRowBasis.availableMemory())
			throw new PressureRowBasis.Unavailable("Pressure constraint cache exceeds available memory");
		var matrix = new DMatrixSparseCSC(features, rowCount(), (int) entries);
		var tails = new double[(int) entries]; var inertia = new double[features];
		int storageFeature = starts[factors.length];
		for (int row = 0; row < rowCount(); row++) {
			matrix.col_idx[row] = matrix.nz_length;
			int first = factorOffsets[row];
			while (first < factorOffsets[row + 1]) {
				int slot = factorIndices[first] / MODAL_STRIDE, end = first + 1; Factor factor = factors[slot];
				while (end < factorOffsets[row + 1] && factorIndices[end] / MODAL_STRIDE == slot) end++;
				int count = basis.blockMarkerCount(factor.velocityBlock);
				for (int axis = 0; axis < 3; axis++) for (int ordinal = 0; ordinal < count; ordinal++) {
					int marker = basis.blockMarker(factor.velocityBlock, ordinal); SignedDyadic value = SignedDyadic.ZERO;
					for (int entry = first; entry < end; entry++) value = value.add(factorCoefficients[entry]
							.multiply(basis.modeWeightExact(marker, factorIndices[entry] % MODAL_STRIDE, axis)));
					double observed = value.toDouble();
					if (!Double.isFinite(observed)) throw new PressureRowBasis.Unavailable("Unrepresentable pressure constraint");
					if (observed != 0) {
						int feature = starts[slot] + axis * count + ordinal, entry = matrix.nz_length++;
						matrix.nz_rows[entry] = feature; matrix.nz_values[entry] = observed;
						tails[entry] = value.subtract(SignedDyadic.of(observed)).toDouble();
						inertia[feature] = exactInverseMass[marker].toDouble();
					}
				}
				first = end;
			}
			if (storage[row] > 0) {
				int entry = matrix.nz_length++;
				matrix.nz_rows[entry] = storageFeature; matrix.nz_values[entry] = 1; inertia[storageFeature++] = storage[row];
			}
		}
		matrix.col_idx[rowCount()] = matrix.nz_length; matrix.indicesSorted = true;
		return new PressureResponse(matrix, tails, inertia);
	}

	/** D A F v, with one final rounding per volume or normal-trace row. */
	void volumeRate(double[][] velocity, double[] result) {
		checkMarkers(velocity); checkRows(result);
		for (double[] axis : velocity) if (axis == result) throw new IllegalArgumentException("Aliased constraint vectors");
		prepareVelocityResponse(velocity);
		for (int row = 0; row < rowCount(); row++) result[row] = rowResponse(row).toDouble();
	}
	private void prepareVelocityResponse(double[][] velocity) {
		Arrays.fill(cellResponse, SignedDyadic.ZERO);
		for (int slot = 0; slot < factors.length; slot++) {
			Factor factor = factors[slot];
			for (int axis = 0; axis < 3; axis++) for (int ordinal = 0; ordinal < basis.blockMarkerCount(factor.velocityBlock); ordinal++) {
				int marker = basis.blockMarker(factor.velocityBlock, ordinal); SignedDyadic value = SignedDyadic.of(velocity[axis][marker]);
				for (int mode = 0; mode < factor.modeCount; mode++) cellResponse[MODAL_STRIDE * slot + mode] = cellResponse[MODAL_STRIDE * slot + mode]
						.add(basis.modeWeightExact(marker, mode, axis).multiply(value));
			}
		}
	}

	/** F^T A^T D^T p, preserving the same exact virtual-work pairing as volumeRate. */
	void force(double[] pressure, double[][] result) {
		force(pressure, null, result);
	}
	void force(double[] pressure, double[] reference, double[][] result) {
		force(pressure, reference, null, result);
	}
	void force(double[] pressure, double[] reference, double[] low, double[][] result) {
		checkRows(pressure); checkMarkers(result);
		if (reference != null) checkRows(reference);
		if (low != null) checkRows(low);
		for (double[] axis : result) {
			if (axis == pressure || axis == reference || axis == low) throw new IllegalArgumentException("Aliased constraint vectors");
			Arrays.fill(axis, 0);
		}
		prepareLoad(pressure, reference, low);
		for (int slot = 0; slot < factors.length; slot++) {
			Factor factor = factors[slot];
			for (int axis = 0; axis < 3; axis++) for (int ordinal = 0; ordinal < basis.blockMarkerCount(factor.velocityBlock); ordinal++) {
				int marker = basis.blockMarker(factor.velocityBlock, ordinal); SignedDyadic value = SignedDyadic.ZERO;
				for (int mode = 0; mode < factor.modeCount; mode++) value = value.add(basis.modeWeightExact(marker, mode, axis)
						.multiply(cellLoad[MODAL_STRIDE * slot + mode]));
				result[axis][marker] = value.toDouble();
			}
		}
	}
	private void prepareLoad(double[] pressure) {
		prepareLoad(pressure, null);
	}
	private void prepareLoad(double[] pressure, double[] reference) {
		prepareLoad(pressure, reference, null);
	}
	private void prepareLoad(double[] pressure, double[] reference, double[] low) {
		Arrays.fill(cellLoad, SignedDyadic.ZERO);
		for (int row = 0; row < rowCount(); row++) {
			SignedDyadic value = SignedDyadic.of(pressure[row]);
			if (reference != null) value = value.add(SignedDyadic.of(reference[row]));
			if (low != null) value = value.add(SignedDyadic.of(low[row]));
			for (int entry = factorOffsets[row]; entry < factorOffsets[row + 1]; entry++) {
				int index = factorIndices[entry]; cellLoad[index] = cellLoad[index].add(factorCoefficients[entry].multiply(value));
			}
		}
	}
	private SignedDyadic rowResponse(int row) {
		SignedDyadic value = SignedDyadic.ZERO;
		for (int entry = factorOffsets[row]; entry < factorOffsets[row + 1]; entry++)
			value = value.add(factorCoefficients[entry].multiply(cellResponse[factorIndices[entry]]));
		return value;
	}

	/** (D A) (F M_actual^-1 F^T) (D A)^T plus finite gas storage; every intermediate retains all bits. */
	void apply(double[] pressure, double[] result) { apply(pressure, null, result, false); }
	void apply(double[] pressure, double[] low, double[] result) { apply(pressure, low, result, false); }
	double applyWithWork(double[] pressure, double[] result) { return apply(pressure, null, result, true); }
	double applyWithWork(double[] pressure, double[] low, double[] result) { return apply(pressure, low, result, true); }
	private double apply(double[] pressure, double[] low, double[] result, boolean work) {
		checkRows(pressure); checkRows(result);
		if (low != null) checkRows(low);
		if (pressure == result || result == low) throw new IllegalArgumentException("Aliased constraint vectors");
		prepareLoad(pressure, null, low);
		respondToLoad();
		for (int row = 0; row < rowCount(); row++) {
			var value = SignedDyadic.of(pressure[row]);
			if (low != null) value = value.add(SignedDyadic.of(low[row]));
			result[row] = rowResponse(row).add(exactStorage[row].multiply(value)).toDouble();
		}
		return work ? positiveWork(pressure, low) : 0;
	}
	private void respondToLoad() {
		Arrays.fill(cellResponse, SignedDyadic.ZERO);
		for (int slot = 0; slot < factors.length; slot++) {
			Factor factor = factors[slot];
			for (int first = 0; first < factor.modeCount; first++) {
				SignedDyadic value = SignedDyadic.ZERO;
				for (int second = 0; second < factor.modeCount; second++) value = value
						.add(factor.gram[factor.modeCount * first + second].multiply(cellLoad[MODAL_STRIDE * slot + second]));
				cellResponse[MODAL_STRIDE * slot + first] = value;
			}
		}
	}

	/** The affine pressure source is reduced before rounding its small residual. */
	SignedDyadic[] referencedSource(double[][] predictor, float dt, double[] initial, double[] reference) {
		checkMarkers(predictor); checkRows(initial); checkRows(reference);
		if (!(dt > 0 && Float.isFinite(dt))) throw new IllegalArgumentException("Invalid pressure source time step");
		prepareVelocityResponse(predictor);
		var rate = new SignedDyadic[rowCount()];
		for (int row = 0; row < rowCount(); row++) rate[row] = rowResponse(row);
		// A common reciprocal preserves the exact C relations before the source is observed.
		var inverseTime = SignedDyadic.of(1.0 / dt);
		prepareLoad(reference); respondToLoad();
		var result = new SignedDyadic[rowCount()];
		for (int row = 0; row < rowCount(); row++) result[row] = exactStorage[row]
				.multiply(SignedDyadic.of(initial[row]).subtract(SignedDyadic.of(reference[row])))
				.subtract(rate[row].multiply(inverseTime)).subtract(rowResponse(row));
		return result;
	}
	void residual(double[] pressure, SignedDyadic[] source, double[] result) {
		residual(pressure, null, source, result);
	}
	void residual(double[] pressure, double[] low, SignedDyadic[] source, double[] result) {
		checkRows(pressure); checkRows(result);
		if (low != null) checkRows(low);
		if (source == null || source.length != rowCount() || pressure == result || result == low)
			throw new IllegalArgumentException("Invalid exact pressure residual vectors");
		prepareLoad(pressure, low); respondToLoad();
		for (int row = 0; row < rowCount(); row++) {
			SignedDyadic value = SignedDyadic.of(pressure[row]);
			if (low != null) value = value.add(SignedDyadic.of(low[row]));
			result[row] = source[row].subtract(rowResponse(row)).subtract(exactStorage[row].multiply(value)).toDouble();
		}
	}

	/** Exact local mass work equals |F^T A^T D^T p|^2/M, independently of pressure-row cancellation. */
	private double positiveWork(double[] pressure, double[] low) {
		SignedDyadic work = SignedDyadic.ZERO;
		for (int index = 0; index < cellLoad.length; index++) work = work.add(cellLoad[index].multiply(cellResponse[index]));
		for (int row = 0; row < rowCount(); row++) {
			SignedDyadic value = SignedDyadic.of(pressure[row]);
			if (low != null) value = value.add(SignedDyadic.of(low[row]));
			work = work.add(exactStorage[row].multiply(value).multiply(value));
		}
		return work.toDouble();
	}

	void diagonal(double[] result) {
		checkRows(result);
		System.arraycopy(canonicalDiagonal, 0, result, 0, result.length);
	}
	/** Exact local modal mass work, with one final rounding per canonical diagonal entry. */
	private double[] captureDiagonal() {
		var result = new double[rowCount()];
		for (int row = 0; row < rowCount(); row++) {
			SignedDyadic value = exactStorage[row];
			int first = factorOffsets[row];
			while (first < factorOffsets[row + 1]) {
				int slot = factorIndices[first] / MODAL_STRIDE, end = first + 1; Factor factor = factors[slot];
				while (end < factorOffsets[row + 1] && factorIndices[end] / MODAL_STRIDE == slot) end++;
				for (int a = first; a < end; a++) {
					SignedDyadic response = SignedDyadic.ZERO;
					for (int b = first; b < end; b++) response = response.add(factor.gram[factor.modeCount
							* (factorIndices[a] % MODAL_STRIDE) + factorIndices[b] % MODAL_STRIDE].multiply(factorCoefficients[b]));
					value = value.add(factorCoefficients[a].multiply(response));
				}
				first = end;
			}
			result[row] = value.toDouble();
		}
		return result;
	}

	private void checkRows(double[] vector) {
		if (vector == null || vector.length != rowCount()) throw new IllegalArgumentException("Constraint row shape mismatch");
	}
	private void checkMarkers(double[][] vector) {
		if (vector == null || vector.length != 3) throw new IllegalArgumentException("Expected three marker velocity axes");
		for (double[] axis : vector) if (axis == null || axis.length != markerCount) throw new IllegalArgumentException("Marker velocity shape mismatch");
		if (vector[0] == vector[1] || vector[0] == vector[2] || vector[1] == vector[2]) throw new IllegalArgumentException("Aliased marker velocity axes");
	}
}
