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
	private final int markerCount;
	private final int[] rowStarts, columns;
	private final SignedDyadic[] coefficients, exactInverseMass, exactStorage, exactLoad;
	private final double[] storage, canonicalDiagonal;
	private final List<PressureRowBasis> patches;

	CellVelocityConstraintOperator(CellAffineVelocity basis, List<Row> rows) {
		if (basis == null || rows == null) throw new IllegalArgumentException("Constraint snapshot is missing");
		markerCount = basis.markerCount(); exactInverseMass = new SignedDyadic[markerCount];
		for (int marker = 0; marker < markerCount; marker++) {
			double inverse = 1 / basis.massKilograms(marker);
			if (!(inverse > 0 && Double.isFinite(inverse))) throw new IllegalArgumentException("Actual marker inertia is unrepresentable");
			exactInverseMass[marker] = SignedDyadic.of(inverse);
		}
		rowStarts = new int[rows.size() + 1]; storage = new double[rows.size()]; exactStorage = new SignedDyadic[rows.size()];
		var indices = new ArrayList<Integer>(); var values = new ArrayList<SignedDyadic>();
		var blockStarts = new int[rowStarts.length]; var blocks = new ArrayList<Integer>();
		for (int row = 0; row < rows.size(); row++) {
			Row definition = rows.get(row); storage[row] = definition.storage; exactStorage[row] = SignedDyadic.of(storage[row]);
			// Modal pullback is local to construction; every later operation uses the same actual C row.
			var modal = new TreeMap<Integer, SignedDyadic[]>();
			for (Block block : definition.blocks) {
				if (block.velocityBlock < 0 || block.velocityBlock >= basis.blockCount() || block.axis < 0 || block.axis >= 3)
					throw new IllegalArgumentException("Cell constraint references an invalid velocity block");
				int modes = basis.modeCount(block.velocityBlock);
				var pulled = modal.computeIfAbsent(block.velocityBlock, ignored -> {
					var value = new SignedDyadic[modes]; Arrays.fill(value, SignedDyadic.ZERO); return value;
				});
				for (int mode = 0; mode < modes; mode++) for (int term = 0; term < 4; term++)
					if (block.exactCoefficients[term].signum() != 0) pulled[mode] = pulled[mode].add(block.exactCoefficients[term]
							.multiply(basis.modeCoefficientExact(block.velocityBlock, mode, block.axis, term)));
			}
			var merged = new TreeMap<Integer, SignedDyadic>();
			for (var entry : modal.entrySet()) {
				int block = entry.getKey(); var pulled = entry.getValue(); boolean active = false;
				for (int axis = 0; axis < 3; axis++) for (int ordinal = 0; ordinal < basis.blockMarkerCount(block); ordinal++) {
					int marker = basis.blockMarker(block, ordinal); var value = SignedDyadic.ZERO;
					for (int mode = 0; mode < pulled.length; mode++) if (pulled[mode].signum() != 0)
						value = value.add(pulled[mode].multiply(basis.modeWeightExact(marker, mode, axis)));
					if (value.signum() != 0) { merged.put(axis * markerCount + marker, value); active = true; }
				}
				if (active) blocks.add(block);
			}
			append(merged, indices, values); rowStarts[row + 1] = indices.size(); blockStarts[row + 1] = blocks.size();
		}
		columns = indices.stream().mapToInt(Integer::intValue).toArray(); coefficients = values.toArray(SignedDyadic[]::new);
		exactLoad = new SignedDyadic[Math.multiplyExact(3, markerCount)];
		canonicalDiagonal = captureDiagonal();
		patches = PressureRowBasis.patches(blockStarts, blocks.stream().mapToInt(Integer::intValue).toArray(),
				basis.blockCount(), storage, canonicalDiagonal);
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
	/** The coarse matrix is the exact restriction T^T C of the already captured fine constraints. */
	private CellVelocityConstraintOperator(CellVelocityConstraintOperator source, int[][] rows, double[][] weights) {
		if (rows.length != weights.length) throw new IllegalArgumentException("Coarse pressure interpolation shape mismatch");
		markerCount = source.markerCount; exactInverseMass = source.exactInverseMass;
		rowStarts = new int[rows.length + 1]; storage = new double[rows.length]; exactStorage = new SignedDyadic[rows.length];
		var indices = new ArrayList<Integer>(); var values = new ArrayList<SignedDyadic>();
		int[] storageOwner = new int[source.rowCount()]; Arrays.fill(storageOwner, -1);
		for (int coarse = 0; coarse < rows.length; coarse++) {
			if (rows[coarse].length != weights[coarse].length) throw new IllegalArgumentException("Coarse pressure column shape mismatch");
			var merged = new TreeMap<Integer, SignedDyadic>(); var gas = SignedDyadic.ZERO;
			for (int i = 0; i < rows[coarse].length; i++) {
				int row = rows[coarse][i]; var weight = SignedDyadic.of(weights[coarse][i]);
				if (weight.signum() == 0) continue;
				for (int entry = source.rowStarts[row]; entry < source.rowStarts[row + 1]; entry++)
					merged.merge(source.columns[entry], source.coefficients[entry].multiply(weight), SignedDyadic::add);
				if (source.exactStorage[row].signum() > 0) {
					if (storageOwner[row] >= 0) throw new IllegalArgumentException("Finite gas storage needs an independent coarse coordinate");
					storageOwner[row] = coarse;
					gas = gas.add(source.exactStorage[row].multiply(weight).multiply(weight));
				}
			}
			append(merged, indices, values); rowStarts[coarse + 1] = indices.size();
			exactStorage[coarse] = gas; storage[coarse] = gas.toDouble();
		}
		columns = indices.stream().mapToInt(Integer::intValue).toArray(); coefficients = values.toArray(SignedDyadic[]::new);
		exactLoad = new SignedDyadic[Math.multiplyExact(3, markerCount)];
		canonicalDiagonal = captureDiagonal(); patches = List.of();
	}
	private static void append(TreeMap<Integer, SignedDyadic> row, ArrayList<Integer> columns, ArrayList<SignedDyadic> values) {
		for (var entry : row.entrySet()) if (entry.getValue().signum() != 0) { columns.add(entry.getKey()); values.add(entry.getValue()); }
	}
	int rowCount() { return storage.length; }
	List<PressureRowBasis> rowPatches() { return patches; }
	PressureRowBasis coarseBasis() { return PressureRowBasis.coarse(canonicalDiagonal); }

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
	private PressureResponse capturePressureResponse() {
		int features = 3 * markerCount, entries = coefficients.length;
		for (var gas : exactStorage) if (gas.signum() > 0) { features = Math.addExact(features, 1); entries = Math.addExact(entries, 1); }
		if (40.0 * entries + 48.0 * features + 8.0 * (rowCount() + 1L) > PressureRowBasis.availableMemory())
			throw new PressureRowBasis.Unavailable("Pressure constraint cache exceeds available memory");
		var matrix = new DMatrixSparseCSC(features, rowCount(), entries);
		var tails = new double[entries]; var inertia = new double[features];
		for (int column = 0; column < 3 * markerCount; column++) inertia[column] = exactInverseMass[column % markerCount].toDouble();
		int storageFeature = 3 * markerCount;
		for (int row = 0; row < rowCount(); row++) {
			matrix.col_idx[row] = matrix.nz_length;
			for (int entry = rowStarts[row]; entry < rowStarts[row + 1]; entry++) {
				var value = coefficients[entry]; double observed = value.toDouble();
				if (!Double.isFinite(observed)) throw new PressureRowBasis.Unavailable("Unrepresentable pressure constraint");
				if (observed != 0) {
					int index = matrix.nz_length++; matrix.nz_rows[index] = columns[entry]; matrix.nz_values[index] = observed;
					tails[index] = value.subtract(SignedDyadic.of(observed)).toDouble();
				}
			}
			if (exactStorage[row].signum() > 0) {
				int index = matrix.nz_length++; matrix.nz_rows[index] = storageFeature; matrix.nz_values[index] = 1;
				inertia[storageFeature++] = storage[row];
			}
		}
		matrix.col_idx[rowCount()] = matrix.nz_length; matrix.indicesSorted = true;
		return new PressureResponse(matrix, tails, inertia);
	}

	/** C v, preserving the exact pairing with the transpose force. */
	void volumeRate(double[][] velocity, double[] result) {
		checkMarkers(velocity); checkRows(result);
		for (double[] axis : velocity) if (axis == result) throw new IllegalArgumentException("Aliased constraint vectors");
		prepareVelocity(velocity);
		for (int row = 0; row < rowCount(); row++) result[row] = rowResponse(row).toDouble();
	}
	private void prepareVelocity(double[][] velocity) {
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < markerCount; marker++)
			exactLoad[axis * markerCount + marker] = SignedDyadic.of(velocity[axis][marker]);
	}
	void force(double[] pressure, double[][] result) { force(pressure, null, result); }
	void force(double[] pressure, double[] reference, double[][] result) { force(pressure, reference, null, result); }
	/** C^T p, combining pressure references and both increment columns before force rounding. */
	void force(double[] pressure, double[] reference, double[] low, double[][] result) {
		checkRows(pressure); checkMarkers(result);
		if (reference != null) checkRows(reference);
		if (low != null) checkRows(low);
		for (double[] axis : result) if (axis == pressure || axis == reference || axis == low)
			throw new IllegalArgumentException("Aliased constraint vectors");
		prepareLoad(pressure, reference, low);
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < markerCount; marker++)
			result[axis][marker] = exactLoad[axis * markerCount + marker].toDouble();
	}
	private void prepareLoad(double[] pressure, double[] reference, double[] low) {
		Arrays.fill(exactLoad, SignedDyadic.ZERO);
		for (int row = 0; row < rowCount(); row++) {
			var value = pressureValue(pressure, reference, low, row);
			for (int entry = rowStarts[row]; entry < rowStarts[row + 1]; entry++) {
				int column = columns[entry]; exactLoad[column] = exactLoad[column].add(coefficients[entry].multiply(value));
			}
		}
	}
	private static SignedDyadic pressureValue(double[] pressure, double[] reference, double[] low, int row) {
		var value = SignedDyadic.of(pressure[row]);
		if (reference != null) value = value.add(SignedDyadic.of(reference[row]));
		if (low != null) value = value.add(SignedDyadic.of(low[row]));
		return value;
	}
	private SignedDyadic rowResponse(int row) {
		var value = SignedDyadic.ZERO;
		for (int entry = rowStarts[row]; entry < rowStarts[row + 1]; entry++)
			value = value.add(coefficients[entry].multiply(exactLoad[columns[entry]]));
		return value;
	}
	/** Weight the same actual carrier load used by the pressure transpose. */
	private SignedDyadic weightLoad(boolean work) {
		var energy = SignedDyadic.ZERO;
		for (int column = 0; column < exactLoad.length; column++) {
			var load = exactLoad[column]; exactLoad[column] = load.multiply(exactInverseMass[column % markerCount]);
			if (work) energy = energy.add(load.multiply(exactLoad[column]));
		}
		return energy;
	}
	void apply(double[] pressure, double[] result) { apply(pressure, null, result, false); }
	void apply(double[] pressure, double[] low, double[] result) { apply(pressure, low, result, false); }
	double applyWithWork(double[] pressure, double[] result) { return apply(pressure, null, result, true); }
	double applyWithWork(double[] pressure, double[] low, double[] result) { return apply(pressure, low, result, true); }
	private double apply(double[] pressure, double[] low, double[] result, boolean work) {
		checkRows(pressure); checkRows(result);
		if (low != null) checkRows(low);
		if (pressure == result || result == low) throw new IllegalArgumentException("Aliased constraint vectors");
		prepareLoad(pressure, null, low); var energy = weightLoad(work);
		for (int row = 0; row < rowCount(); row++) {
			var value = pressureValue(pressure, null, low, row);
			result[row] = rowResponse(row).add(exactStorage[row].multiply(value)).toDouble();
			if (work) energy = energy.add(exactStorage[row].multiply(value).multiply(value));
		}
		return work ? energy.toDouble() : 0;
	}
	SignedDyadic[] referencedSource(double[][] predictor, float dt, double[] initial, double[] reference) {
		checkMarkers(predictor); checkRows(initial); checkRows(reference);
		if (!(dt > 0 && Float.isFinite(dt))) throw new IllegalArgumentException("Invalid pressure source time step");
		prepareVelocity(predictor); var rate = new SignedDyadic[rowCount()];
		for (int row = 0; row < rowCount(); row++) rate[row] = rowResponse(row);
		// A common reciprocal preserves the exact C relations before the source is observed.
		var inverseTime = SignedDyadic.of(1.0 / dt);
		prepareLoad(reference, null, null); weightLoad(false);
		var result = new SignedDyadic[rowCount()];
		for (int row = 0; row < rowCount(); row++) result[row] = exactStorage[row]
				.multiply(SignedDyadic.of(initial[row]).subtract(SignedDyadic.of(reference[row])))
				.subtract(rate[row].multiply(inverseTime)).subtract(rowResponse(row));
		return result;
	}
	void residual(double[] pressure, SignedDyadic[] source, double[] result) { residual(pressure, null, source, result); }
	void residual(double[] pressure, double[] low, SignedDyadic[] source, double[] result) {
		checkRows(pressure); checkRows(result);
		if (low != null) checkRows(low);
		if (source == null || source.length != rowCount() || pressure == result || result == low)
			throw new IllegalArgumentException("Invalid exact pressure residual vectors");
		prepareLoad(pressure, null, low); weightLoad(false);
		for (int row = 0; row < rowCount(); row++) result[row] = source[row].subtract(rowResponse(row))
				.subtract(exactStorage[row].multiply(pressureValue(pressure, null, low, row))).toDouble();
	}
	void diagonal(double[] result) {
		checkRows(result); System.arraycopy(canonicalDiagonal, 0, result, 0, result.length);
	}
	private double[] captureDiagonal() {
		var result = new double[rowCount()];
		for (int row = 0; row < rowCount(); row++) {
			var value = exactStorage[row];
			for (int entry = rowStarts[row]; entry < rowStarts[row + 1]; entry++) {
				var coefficient = coefficients[entry];
				value = value.add(coefficient.multiply(coefficient).multiply(exactInverseMass[columns[entry] % markerCount]));
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
