package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

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
	private record ExpandedC(int[] offsets, int[] indices, double[] coefficients, double[] coefficientLow,
	                         SignedDyadic[] exactCoefficients) { }

	private final CellAffineVelocity basis;
	private final int markerCount;
	private final double[] inverseMass, storage, canonicalDiagonal;
	private final int[] factorOffsets, factorIndices;
	private final Factor[] factors;
	private final SignedDyadic[] exactInverseMass, exactStorage, factorCoefficients, cellLoad, cellResponse;
	private ExpandedC expandedC;

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
	/** Full marker C belongs to the preconditioner workspace and is expanded only on its first request. */
	private ExpandedC expandedC() {
		if (expandedC != null) return expandedC;
		var offsets = new int[rowCount() + 1];
		var entryIndices = new ArrayList<Integer>(); var high = new ArrayList<Double>(); var low = new ArrayList<Double>();
		var full = new ArrayList<SignedDyadic>();
		for (int row = 0; row < rowCount(); row++) {
			appendCoefficients(row, entryIndices, high, low, full); offsets[row + 1] = entryIndices.size();
		}
		var indices = new int[entryIndices.size()]; var coefficients = new double[indices.length]; var coefficientLow = new double[indices.length];
		for (int entry = 0; entry < indices.length; entry++) {
			indices[entry] = entryIndices.get(entry); coefficients[entry] = high.get(entry); coefficientLow[entry] = low.get(entry);
		}
		expandedC = new ExpandedC(offsets, indices, coefficients, coefficientLow, full.toArray(SignedDyadic[]::new));
		return expandedC;
	}
	private void appendCoefficients(int row, List<Integer> indices,
			List<Double> high, List<Double> low, List<SignedDyadic> full) {
		var merged = new TreeMap<Integer, SignedDyadic>();
		for (int entry = factorOffsets[row]; entry < factorOffsets[row + 1]; entry++) {
			int index = factorIndices[entry]; Factor factor = factors[index / MODAL_STRIDE]; int mode = index % MODAL_STRIDE;
			for (int axis = 0; axis < 3; axis++) for (int ordinal = 0; ordinal < basis.blockMarkerCount(factor.velocityBlock); ordinal++) {
				int marker = basis.blockMarker(factor.velocityBlock, ordinal);
				SignedDyadic value = factorCoefficients[entry].multiply(basis.modeWeightExact(marker, mode, axis));
				if (value.signum() != 0) merged.merge(axis * markerCount + marker, value, SignedDyadic::add);
			}
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
	PressureRowBasis rowBasis() {
		SignedDyadic[][] inertia = new SignedDyadic[factors.length][];
		for (int factor = 0; factor < factors.length; factor++) inertia[factor] = factors[factor].gram;
		return new PressureRowBasis(factorOffsets, factorIndices, factorCoefficients, inertia, MODAL_STRIDE, storage, canonicalDiagonal);
	}
	record PrincipalGram(int[] rows, int[] columns, SignedDyadic[] values) { }
	private record FactorRow(int row, SignedDyadic[] coefficients, SignedDyadic[] response) { }
	/** Sparse principal (D A) H (D A)^T, assembled exactly before final factorization rounding. */
	PrincipalGram principalGram(PressureRowBasis selected) {
		var incidence = new ArrayList<ArrayList<FactorRow>>(factors.length);
		for (int factor = 0; factor < factors.length; factor++) incidence.add(new ArrayList<>());
		for (int position = 0; position < selected.size(); position++) {
			int row = selected.row(position), entry = factorOffsets[row];
			while (entry < factorOffsets[row + 1]) {
				int factor = factorIndices[entry] / MODAL_STRIDE, modes = factors[factor].modeCount;
				SignedDyadic[] d = new SignedDyadic[modes], response = new SignedDyadic[modes];
				Arrays.fill(d, SignedDyadic.ZERO);
				while (entry < factorOffsets[row + 1] && factorIndices[entry] / MODAL_STRIDE == factor) {
					d[factorIndices[entry] % MODAL_STRIDE] = factorCoefficients[entry]; entry++;
				}
				for (int first = 0; first < modes; first++) {
					response[first] = SignedDyadic.ZERO;
					for (int second = 0; second < modes; second++) response[first] = response[first]
							.add(factors[factor].gram[modes * first + second].multiply(d[second]));
				}
				incidence.get(factor).add(new FactorRow(position, d, response));
			}
		}
		var lower = new TreeMap<Long, SignedDyadic>(); long bytes = 0, available = PressureRowBasis.availableMemory();
		for (var members : incidence) for (int first = 0; first < members.size(); first++) for (int second = 0; second <= first; second++) {
			FactorRow a = members.get(first), b = members.get(second); SignedDyadic value = SignedDyadic.ZERO;
			for (int mode = 0; mode < a.coefficients.length; mode++) value = value.add(a.coefficients[mode].multiply(b.response[mode]));
			long key = ((long) a.row << 32) | b.row; SignedDyadic previous = lower.get(key);
			if (previous != null) { bytes -= gramBytes(previous); value = previous.add(value); }
			if (value.signum() == 0) lower.remove(key); else { lower.put(key, value); bytes += gramBytes(value); }
			if (bytes > available / 2) throw new PressureRowBasis.Unavailable("Principal pressure Gram exceeds available memory");
		}
		for (int position = 0; position < selected.size(); position++) if (exactStorage[selected.row(position)].signum() != 0) {
			long key = ((long) position << 32) | position; lower.merge(key, exactStorage[selected.row(position)], SignedDyadic::add);
		}
		int[] rows = new int[lower.size()], columns = new int[lower.size()]; SignedDyadic[] values = new SignedDyadic[lower.size()]; int entry = 0;
		for (var value : lower.entrySet()) { rows[entry] = (int) (value.getKey() >>> 32); columns[entry] = value.getKey().intValue(); values[entry++] = value.getValue(); }
		return new PrincipalGram(rows, columns, values);
	}
	private static long gramBytes(SignedDyadic value) { return 256L + 2L * ((value.significand().bitLength() + 7L) / 8); }
	ConstraintGramPreconditioner preconditioner(double[] diagonal) {
		checkRows(diagonal);
		var c = expandedC();
		return new ConstraintGramPreconditioner(markerCount, inverseMass, c.offsets, c.indices, c.coefficients, c.coefficientLow, diagonal, c.exactCoefficients);
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
		if (!internalRows.isEmpty()) {
			var c = expandedC();
			result.blockProjection(new ConstraintBlockProjection(result, internalRows, markerCount,
					inverseMass, c.offsets, c.indices, c.coefficients, c.coefficientLow, diagonal));
		}
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
		var c = expandedC();
		for (int row = 0; row < rowCount(); row++) for (int entry = c.offsets[row]; entry < c.offsets[row + 1]; entry++)
			exactForce[c.indices[entry]] = exactForce[c.indices[entry]].add(c.exactCoefficients[entry].multiply(SignedDyadic.of(mode[row])));
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
	void apply(double[] pressure, double[] result) { apply(pressure, result, false); }
	double applyWithWork(double[] pressure, double[] result) { return apply(pressure, result, true); }
	private double apply(double[] pressure, double[] result, boolean work) {
		checkRows(pressure); checkRows(result);
		if (pressure == result) throw new IllegalArgumentException("Aliased constraint vectors");
		prepareLoad(pressure);
		respondToLoad();
		for (int row = 0; row < rowCount(); row++)
			result[row] = rowResponse(row).add(exactStorage[row].multiply(SignedDyadic.of(pressure[row]))).toDouble();
		return work ? positiveWork(pressure) : 0;
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
