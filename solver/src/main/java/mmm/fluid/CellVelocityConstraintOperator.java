package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/** Fixed volume and normal-trace constraints pulled back through the actual-mass cell basis. */
final class CellVelocityConstraintOperator {
	record Block(int velocityBlock, int axis, double[] coefficients) {
		Block {
			if (coefficients == null || coefficients.length != 4) throw new IllegalArgumentException("Invalid cell constraint block");
			coefficients = coefficients.clone();
			for (double value : coefficients) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite cell constraint coefficient");
		}
		@Override public double[] coefficients() { return coefficients.clone(); }
	}
	record Row(List<Block> blocks, double storage) {
		Row {
			if (blocks == null || !(storage >= 0 && Double.isFinite(storage))) throw new IllegalArgumentException("Invalid cell constraint row");
			blocks = List.copyOf(blocks);
		}
	}
	private record Factor(int velocityBlock, int axis, SignedDyadic[] gram) { }

	private final CellAffineVelocity basis;
	private final int markerCount;
	private final double[] inverseMass, storage, coefficients, coefficientLow;
	private final int[] offsets, indices, factorOffsets, factorIndices;
	private final Factor[] factors;
	private final SignedDyadic[] exactCoefficients, exactInverseMass, exactStorage, factorCoefficients, cellLoad, cellResponse;

	CellVelocityConstraintOperator(CellAffineVelocity basis, List<Row> rows) {
		if (basis == null || rows == null) throw new IllegalArgumentException("Constraint snapshot is missing");
		this.basis = basis;
		markerCount = basis.markerCount(); inverseMass = new double[markerCount]; exactInverseMass = new SignedDyadic[markerCount];
		for (int marker = 0; marker < markerCount; marker++) {
			inverseMass[marker] = 1 / basis.massKilograms(marker);
			if (!(inverseMass[marker] > 0 && Double.isFinite(inverseMass[marker])))
				throw new IllegalArgumentException("Actual marker inertia is unrepresentable");
			exactInverseMass[marker] = SignedDyadic.of(inverseMass[marker]);
		}
		var factorSlots = new TreeMap<Integer, Integer>();
		for (Row definition : rows) for (Block block : definition.blocks) {
			if (block.velocityBlock < 0 || block.velocityBlock >= basis.blockCount() || block.axis < 0 || block.axis >= 3)
				throw new IllegalArgumentException("Cell constraint references an invalid velocity block");
			for (double value : block.coefficients) if (value != 0) factorSlots.put(3 * block.velocityBlock + block.axis, 0);
		}
		factors = new Factor[factorSlots.size()]; int slot = 0;
		for (var entry : factorSlots.entrySet()) {
			entry.setValue(slot); factors[slot++] = captureFactor(entry.getKey() / 3, entry.getKey() % 3);
		}
		cellLoad = new SignedDyadic[Math.multiplyExact(4, factors.length)]; cellResponse = new SignedDyadic[cellLoad.length];
		storage = new double[rows.size()]; exactStorage = new SignedDyadic[rows.size()];
		offsets = new int[rows.size() + 1]; factorOffsets = new int[rows.size() + 1];
		var entryIndices = new ArrayList<Integer>(); var high = new ArrayList<Double>(); var low = new ArrayList<Double>();
		var full = new ArrayList<SignedDyadic>(); var declaredIndices = new ArrayList<Integer>(); var declared = new ArrayList<SignedDyadic>();
		for (int row = 0; row < rows.size(); row++) {
			Row definition = rows.get(row); storage[row] = definition.storage; exactStorage[row] = SignedDyadic.of(storage[row]);
			var merged = new TreeMap<Integer, SignedDyadic>();
			for (Block block : definition.blocks) for (int term = 0; term < 4; term++) if (block.coefficients[term] != 0)
				merged.merge(4 * factorSlots.get(3 * block.velocityBlock + block.axis) + term,
						SignedDyadic.of(block.coefficients[term]), SignedDyadic::add);
			for (var entry : merged.entrySet()) if (entry.getValue().signum() != 0) {
				declaredIndices.add(entry.getKey()); declared.add(entry.getValue());
			}
			factorOffsets[row + 1] = declaredIndices.size();
			appendCoefficients(definition, entryIndices, high, low, full);
			offsets[row + 1] = entryIndices.size();
		}
		factorIndices = declaredIndices.stream().mapToInt(Integer::intValue).toArray(); factorCoefficients = declared.toArray(SignedDyadic[]::new);
		indices = new int[entryIndices.size()]; coefficients = new double[indices.length]; coefficientLow = new double[indices.length];
		for (int entry = 0; entry < indices.length; entry++) {
			indices[entry] = entryIndices.get(entry); coefficients[entry] = high.get(entry); coefficientLow[entry] = low.get(entry);
		}
		// Full C remains the preconditioner input; the physical action retains D and L separately.
		exactCoefficients = full.toArray(SignedDyadic[]::new);
	}

	private Factor captureFactor(int block, int axis) {
		int count = basis.blockMarkerCount(block); var fit = new SignedDyadic[4 * count]; var gram = new SignedDyadic[16];
		for (int ordinal = 0; ordinal < count; ordinal++) for (int term = 0; term < 4; term++)
			fit[4 * ordinal + term] = basis.coefficientExact(basis.blockMarker(block, ordinal), axis, term);
		for (int first = 0; first < 4; first++) for (int second = 0; second <= first; second++) {
			SignedDyadic value = SignedDyadic.ZERO;
			for (int ordinal = 0; ordinal < count; ordinal++)
				value = value.add(fit[4 * ordinal + first].multiply(fit[4 * ordinal + second])
						.multiply(exactInverseMass[basis.blockMarker(block, ordinal)]));
			gram[4 * first + second] = gram[4 * second + first] = value;
		}
		return new Factor(block, axis, gram);
	}
	private void appendCoefficients(Row row, List<Integer> indices, List<Double> high, List<Double> low, List<SignedDyadic> full) {
		var merged = new TreeMap<Integer, SignedDyadic>();
		for (Block block : row.blocks) for (int ordinal = 0; ordinal < basis.blockMarkerCount(block.velocityBlock); ordinal++) {
			int marker = basis.blockMarker(block.velocityBlock, ordinal); SignedDyadic value = SignedDyadic.ZERO;
			for (int term = 0; term < 4; term++) if (block.coefficients[term] != 0)
				value = value.add(SignedDyadic.of(block.coefficients[term]).multiply(basis.coefficientExact(marker, block.axis, term)));
			if (value.signum() != 0) merged.merge(block.axis * markerCount + marker, value, SignedDyadic::add);
		}
		for (var entry : merged.entrySet()) if (entry.getValue().signum() != 0) {
			double hi = entry.getValue().toDouble();
			if (!Double.isFinite(hi)) throw new IllegalArgumentException("Merged cell constraint is unrepresentable");
			double lo = entry.getValue().subtract(SignedDyadic.of(hi)).toDouble();
			if (hi == 0 && lo == 0) throw new IllegalArgumentException("Cell constraint underflows its preconditioner representation");
			indices.add(entry.getKey()); high.add(hi); low.add(lo); full.add(entry.getValue());
		}
	}

	int rowCount() { return storage.length; }
	ConstraintGramPreconditioner preconditioner(double[] diagonal) {
		checkRows(diagonal);
		return new ConstraintGramPreconditioner(markerCount, inverseMass, offsets, indices, coefficients, coefficientLow, diagonal, exactCoefficients);
	}
	ConstraintGramPreconditioner preconditioner(double[] diagonal, double[] commonMode) {
		var result = preconditioner(diagonal); checkRows(commonMode);
		boolean compliant = false; for (double value : storage) compliant |= value > 0;
		if (!compliant) return result;
		double[] mode = commonMode.clone(), image = new double[rowCount()];
		for (int row = 0; row < mode.length; row++) if (diagonal[row] == 0) mode[row] = 0;
		apply(mode, image);
		for (double value : image) if (!Double.isFinite(value)) throw blocked("Unrepresentable common-pressure image");
		SignedDyadic energy = ConstraintGramPreconditioner.dot(mode, image);
		verifyCoarseEnergy(mode, image, energy);
		if (energy.signum() <= 0) throw blocked("Nonpositive common-pressure coarse energy");
		result.balance(mode, image, energy); return result;
	}
	ConstraintGramPreconditioner preconditioner(double[] diagonal, double[] commonMode, List<int[]> internalRows) {
		var result = preconditioner(diagonal, commonMode);
		if (!internalRows.isEmpty()) result.blockProjection(new ConstraintBlockProjection(result, internalRows, markerCount,
				inverseMass, offsets, indices, coefficients, coefficientLow, diagonal));
		result.refine();
		return result;
	}

	/** Independent positive mass/storage work, with forward rounding of the actual operator. */
	private void verifyCoarseEnergy(double[] mode, double[] image, SignedDyadic energy) {
		double[][] force = new double[3][markerCount]; force(mode, force);
		for (double[] axis : force) for (double value : axis)
			if (!Double.isFinite(value)) throw blocked("Unrepresentable common-pressure force");
		SignedDyadic positive = SignedDyadic.ZERO, exactPositive = SignedDyadic.ZERO, bound = SignedDyadic.ZERO;
		SignedDyadic[] exactForce = new SignedDyadic[Math.multiplyExact(3, markerCount)]; Arrays.fill(exactForce, SignedDyadic.ZERO);
		for (int row = 0; row < rowCount(); row++) for (int entry = offsets[row]; entry < offsets[row + 1]; entry++)
			exactForce[indices[entry]] = exactForce[indices[entry]].add(exactCoefficients[entry].multiply(SignedDyadic.of(mode[row])));
		for (int index = 0; index < exactForce.length; index++) {
			SignedDyadic f = SignedDyadic.of(force[index / markerCount][index % markerCount]), mass = exactInverseMass[index % markerCount];
			positive = positive.add(f.multiply(f).multiply(mass));
			exactPositive = exactPositive.add(exactForce[index].multiply(exactForce[index]).multiply(mass));
		}
		for (int row = 0; row < rowCount(); row++) {
			SignedDyadic weight = SignedDyadic.of(mode[row]), stored = exactStorage[row].multiply(weight).multiply(weight);
			positive = positive.add(stored); exactPositive = exactPositive.add(stored);
			bound = bound.add(absolute(weight).multiply(SignedDyadic.of(Math.ulp(image[row])).multiply(SignedDyadic.of(1, -1))));
		}
		bound = bound.add(absolute(exactPositive.subtract(positive)));
		if (absolute(energy.subtract(positive)).compareTo(bound) > 0)
			throw blocked("Common-pressure work exceeds its rounding bound: work=" + energy.toDouble()
					+ ", positive=" + positive.toDouble() + ", bound=" + bound.toDouble());
	}
	private static SignedDyadic absolute(SignedDyadic value) { return value.signum() < 0 ? value.negate() : value; }
	private static FluidDomain.PressureSolveBlockedException blocked(String message) { return new FluidDomain.PressureSolveBlockedException(message); }

	/** D L v, with one final rounding per volume or normal-trace row. */
	void volumeRate(double[][] velocity, double[] result) {
		checkMarkers(velocity); checkRows(result);
		for (double[] axis : velocity) if (axis == result) throw new IllegalArgumentException("Aliased constraint vectors");
		Arrays.fill(cellResponse, SignedDyadic.ZERO);
		for (int slot = 0; slot < factors.length; slot++) {
			Factor factor = factors[slot];
			for (int ordinal = 0; ordinal < basis.blockMarkerCount(factor.velocityBlock); ordinal++) {
				int marker = basis.blockMarker(factor.velocityBlock, ordinal); SignedDyadic value = SignedDyadic.of(velocity[factor.axis][marker]);
				for (int term = 0; term < 4; term++) cellResponse[4 * slot + term] = cellResponse[4 * slot + term]
						.add(basis.coefficientExact(marker, factor.axis, term).multiply(value));
			}
		}
		for (int row = 0; row < rowCount(); row++) result[row] = rowResponse(row).toDouble();
	}

	/** L^T D^T p, preserving the same exact virtual-work pairing as volumeRate. */
	void force(double[] pressure, double[][] result) {
		checkRows(pressure); checkMarkers(result);
		for (double[] axis : result) {
			if (axis == pressure) throw new IllegalArgumentException("Aliased constraint vectors");
			Arrays.fill(axis, 0);
		}
		prepareLoad(pressure);
		for (int slot = 0; slot < factors.length; slot++) {
			Factor factor = factors[slot];
			for (int ordinal = 0; ordinal < basis.blockMarkerCount(factor.velocityBlock); ordinal++) {
				int marker = basis.blockMarker(factor.velocityBlock, ordinal); SignedDyadic value = SignedDyadic.ZERO;
				for (int term = 0; term < 4; term++) value = value.add(basis.coefficientExact(marker, factor.axis, term).multiply(cellLoad[4 * slot + term]));
				result[factor.axis][marker] = value.toDouble();
			}
		}
	}
	private void prepareLoad(double[] pressure) {
		Arrays.fill(cellLoad, SignedDyadic.ZERO);
		for (int row = 0; row < rowCount(); row++) {
			SignedDyadic value = SignedDyadic.of(pressure[row]);
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

	/** D (L M_actual^-1 L^T) D^T plus finite gas storage; every intermediate retains all bits. */
	void apply(double[] pressure, double[] result) { apply(pressure, result, false); }
	double applyWithWork(double[] pressure, double[] result) { return apply(pressure, result, true); }
	private double apply(double[] pressure, double[] result, boolean work) {
		checkRows(pressure); checkRows(result);
		if (pressure == result) throw new IllegalArgumentException("Aliased constraint vectors");
		prepareLoad(pressure);
		for (int slot = 0; slot < factors.length; slot++) for (int first = 0; first < 4; first++) {
			SignedDyadic value = SignedDyadic.ZERO;
			for (int second = 0; second < 4; second++) value = value.add(factors[slot].gram[4 * first + second].multiply(cellLoad[4 * slot + second]));
			cellResponse[4 * slot + first] = value;
		}
		for (int row = 0; row < rowCount(); row++)
			result[row] = rowResponse(row).add(exactStorage[row].multiply(SignedDyadic.of(pressure[row]))).toDouble();
		return work ? positiveWork(pressure) : 0;
	}

	/** Exact local mass work equals |L^T D^T p|^2/M, independently of pressure-row cancellation. */
	private double positiveWork(double[] pressure) {
		SignedDyadic work = SignedDyadic.ZERO;
		for (int index = 0; index < cellLoad.length; index++) work = work.add(cellLoad[index].multiply(cellResponse[index]));
		for (int row = 0; row < rowCount(); row++) {
			SignedDyadic value = SignedDyadic.of(pressure[row]); work = work.add(exactStorage[row].multiply(value).multiply(value));
		}
		return work.toDouble();
	}

	void diagonal(double[] result) {
		checkRows(result);
		for (int row = 0; row < rowCount(); row++) {
			SignedDyadic value = exactStorage[row];
			for (int entry = offsets[row]; entry < offsets[row + 1]; entry++)
				value = value.add(exactCoefficients[entry].multiply(exactCoefficients[entry]).multiply(exactInverseMass[indices[entry] % markerCount]));
			result[row] = value.toDouble();
		}
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
