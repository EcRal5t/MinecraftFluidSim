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

	private final int markerCount;
	private final double[] inverseMass, storage, coefficients, coefficientLow;
	private final int[] offsets, indices;
	private final double[] response;
	private final SignedDyadic[] exactCoefficients, exactInverseMass, exactStorage, exactResponse, exactMassResponse;

	CellVelocityConstraintOperator(CellAffineVelocity basis, List<Row> rows) {
		if (basis == null || rows == null) throw new IllegalArgumentException("Constraint snapshot is missing");
		markerCount = basis.markerCount(); inverseMass = new double[markerCount];
		for (int marker = 0; marker < markerCount; marker++) {
			inverseMass[marker] = 1 / basis.massKilograms(marker);
			if (!(inverseMass[marker] > 0 && Double.isFinite(inverseMass[marker])))
				throw new IllegalArgumentException("Actual marker inertia is unrepresentable");
		}
		storage = new double[rows.size()]; offsets = new int[rows.size() + 1];
		var entryIndices = new ArrayList<Integer>(); var high = new ArrayList<Double>(); var low = new ArrayList<Double>();
		boolean hasExpandedRows = false;
		for (int row = 0; row < rows.size(); row++) {
			Row definition = rows.get(row); storage[row] = definition.storage; boolean expanded = false;
			for (Block block : definition.blocks) {
				if (block.velocityBlock < 0 || block.velocityBlock >= basis.blockCount() || block.axis < 0 || block.axis >= 3)
					throw new IllegalArgumentException("Cell constraint references an invalid velocity block");
				expanded |= basis.expandedCell(block.velocityBlock);
			}
			hasExpandedRows |= expanded;
			if (expanded) appendExpanded(basis, definition, entryIndices, high, low);
			else appendFast(basis, definition, entryIndices, high, low);
			offsets[row + 1] = entryIndices.size();
		}
		indices = new int[entryIndices.size()]; coefficients = new double[indices.length]; coefficientLow = new double[indices.length];
		boolean expanded = hasExpandedRows;
		for (int entry = 0; entry < indices.length; entry++) {
			indices[entry] = entryIndices.get(entry); coefficients[entry] = high.get(entry); coefficientLow[entry] = low.get(entry);
			expanded |= coefficientLow[entry] != 0;
		}
		response = new double[Math.multiplyExact(3, markerCount)];
		exactCoefficients = expanded ? new SignedDyadic[indices.length] : null;
		exactInverseMass = expanded ? new SignedDyadic[markerCount] : null;
		exactStorage = expanded ? new SignedDyadic[storage.length] : null;
		exactResponse = expanded ? new SignedDyadic[response.length] : null;
		exactMassResponse = expanded ? new SignedDyadic[response.length] : null;
		if (expanded) {
			for (int entry = 0; entry < indices.length; entry++) exactCoefficients[entry] = SignedDyadic.of(coefficients[entry]).add(SignedDyadic.of(coefficientLow[entry]));
			for (int marker = 0; marker < markerCount; marker++) exactInverseMass[marker] = SignedDyadic.of(inverseMass[marker]);
			for (int row = 0; row < storage.length; row++) exactStorage[row] = SignedDyadic.of(storage[row]);
		}
	}

	private void appendFast(CellAffineVelocity basis, Row row, List<Integer> indices, List<Double> high, List<Double> low) {
		var merged = new TreeMap<Integer, Double>();
		for (Block block : row.blocks) for (int ordinal = 0; ordinal < basis.blockMarkerCount(block.velocityBlock); ordinal++) {
			int marker = basis.blockMarker(block.velocityBlock, ordinal); double value = 0;
			for (int term = 0; term < 4; term++) value = Math.fma(block.coefficients[term], basis.coefficient(marker, block.axis, term), value);
			if (!Double.isFinite(value)) throw new IllegalArgumentException("Pulled-back cell constraint is unrepresentable");
			if (value != 0) merged.merge(block.axis * markerCount + marker, value, Double::sum);
		}
		for (var entry : merged.entrySet()) if (entry.getValue() != 0) {
			if (!Double.isFinite(entry.getValue())) throw new IllegalArgumentException("Merged cell constraint is unrepresentable");
			indices.add(entry.getKey()); high.add(entry.getValue()); low.add(0.0);
		}
	}
	private void appendExpanded(CellAffineVelocity basis, Row row, List<Integer> indices, List<Double> high, List<Double> low) {
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
			if (hi == 0 && lo == 0) throw new IllegalArgumentException("Expanded cell constraint underflows its representation");
			indices.add(entry.getKey()); high.add(hi); low.add(lo);
		}
	}

	int rowCount() { return storage.length; }
	ConstraintGramPreconditioner preconditioner(double[] diagonal) {
		checkRows(diagonal);
		return new ConstraintGramPreconditioner(markerCount, inverseMass, offsets, indices, coefficients, coefficientLow, diagonal);
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
		SignedDyadic[] exactForce = new SignedDyadic[response.length]; Arrays.fill(exactForce, SignedDyadic.ZERO);
		for (int row = 0; row < rowCount(); row++) for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
			SignedDyadic coefficient = exactCoefficients == null ? SignedDyadic.of(coefficients[entry]) : exactCoefficients[entry];
			exactForce[indices[entry]] = exactForce[indices[entry]].add(coefficient.multiply(SignedDyadic.of(mode[row])));
		}
		for (int index = 0; index < response.length; index++) {
			SignedDyadic f = SignedDyadic.of(force[index / markerCount][index % markerCount]), mass = SignedDyadic.of(inverseMass[index % markerCount]);
			positive = positive.add(f.multiply(f).multiply(mass));
			exactPositive = exactPositive.add(exactForce[index].multiply(exactForce[index]).multiply(mass));
		}
		for (int row = 0; row < rowCount(); row++) {
			SignedDyadic weight = SignedDyadic.of(mode[row]), stored = SignedDyadic.of(storage[row]).multiply(weight).multiply(weight);
			positive = positive.add(stored); exactPositive = exactPositive.add(stored);
			SignedDyadic rowBound;
			if (exactCoefficients != null) rowBound = SignedDyadic.of(Math.ulp(image[row])).multiply(SignedDyadic.of(1, -1));
			else {
				SignedDyadic magnitude = absolute(SignedDyadic.of(storage[row]).multiply(weight)), propagated = SignedDyadic.ZERO;
				SignedDyadic underflow = SignedDyadic.of(Double.MIN_VALUE);
				for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
					int index = indices[entry]; SignedDyadic f = SignedDyadic.of(force[index / markerCount][index % markerCount]);
					SignedDyadic coefficient = SignedDyadic.of(coefficients[entry]).multiply(SignedDyadic.of(inverseMass[index % markerCount]));
					magnitude = magnitude.add(absolute(coefficient.multiply(f)));
					propagated = propagated.add(absolute(coefficient.multiply(f.subtract(exactForce[index]))));
					underflow = underflow.add(SignedDyadic.of(Double.MIN_VALUE).multiply(absolute(f).add(SignedDyadic.of(1))));
				}
				double operations = 3.0 * (offsets[row + 1] - offsets[row]) + 1, error = operations * 0x1.0p-53;
				rowBound = propagated.add(magnitude.multiply(SignedDyadic.of(Math.nextUp(error / (1 - error))))).add(underflow);
			}
			bound = bound.add(absolute(weight).multiply(rowBound));
		}
		bound = bound.add(absolute(exactPositive.subtract(positive)));
		if (absolute(energy.subtract(positive)).compareTo(bound) > 0)
			throw blocked("Common-pressure work exceeds its rounding bound: work=" + energy.toDouble()
					+ ", positive=" + positive.toDouble() + ", bound=" + bound.toDouble());
	}
	private static SignedDyadic absolute(SignedDyadic value) { return value.signum() < 0 ? value.negate() : value; }
	private static FluidDomain.PressureSolveBlockedException blocked(String message) { return new FluidDomain.PressureSolveBlockedException(message); }

	/** C v: volume rates and surface-tested normal velocity rates in their declared SI units. */
	void volumeRate(double[][] velocity, double[] result) {
		checkMarkers(velocity); checkRows(result);
		for (double[] axis : velocity) if (axis == result) throw new IllegalArgumentException("Aliased constraint vectors");
		Arrays.fill(result, 0);
		for (int row = 0; row < storage.length; row++) {
			if (exactCoefficients != null) {
				SignedDyadic value = SignedDyadic.ZERO;
				for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
					int index = indices[entry]; value = value.add(exactCoefficients[entry].multiply(SignedDyadic.of(velocity[index / markerCount][index % markerCount])));
				}
				result[row] = value.toDouble();
			} else for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
				int index = indices[entry]; result[row] = Math.fma(coefficients[entry], velocity[index / markerCount][index % markerCount], result[row]);
			}
		}
	}

	/** C^T p, using the same immutable scalar coefficients and ordinary SI virtual work. */
	void force(double[] pressure, double[][] result) {
		checkRows(pressure); checkMarkers(result);
		for (double[] axis : result) {
			if (axis == pressure) throw new IllegalArgumentException("Aliased constraint vectors");
			Arrays.fill(axis, 0);
		}
		if (exactCoefficients != null) {
			prepareExactResponse(pressure);
			for (int index = 0; index < exactResponse.length; index++) result[index / markerCount][index % markerCount] = exactResponse[index].toDouble();
			return;
		}
		for (int row = 0; row < storage.length; row++) for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
			int index = indices[entry], axis = index / markerCount, marker = index % markerCount;
			result[axis][marker] = Math.fma(coefficients[entry], pressure[row], result[axis][marker]);
		}
	}
	private void prepareExactResponse(double[] pressure) {
		Arrays.fill(exactResponse, SignedDyadic.ZERO);
		for (int row = 0; row < storage.length; row++) {
			SignedDyadic value = SignedDyadic.of(pressure[row]);
			for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
				int index = indices[entry]; exactResponse[index] = exactResponse[index].add(exactCoefficients[entry].multiply(value));
			}
		}
	}

	/** C M_actual^-1 C^T plus the finite gas compliance; expanded responses retain all product bits. */
	void apply(double[] pressure, double[] result) {
		apply(pressure,result,false);
	}
	double applyWithWork(double[] pressure,double[] result) {
		return apply(pressure,result,true);
	}
	private double apply(double[] pressure,double[] result,boolean work) {
		checkRows(pressure); checkRows(result);
		if (pressure == result) throw new IllegalArgumentException("Aliased constraint vectors");
		if (exactCoefficients != null) {
			prepareExactResponse(pressure);
			for (int index = 0; index < exactResponse.length; index++)
				exactMassResponse[index] = exactResponse[index].multiply(exactInverseMass[index % markerCount]);
			for (int row = 0; row < storage.length; row++) {
				SignedDyadic value = exactStorage[row].multiply(SignedDyadic.of(pressure[row]));
				for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
					int index = indices[entry]; value = value.add(exactCoefficients[entry].multiply(exactMassResponse[index]));
				}
				result[row] = value.toDouble();
			}
			return work ? positiveWork(pressure) : 0;
		}
		Arrays.fill(response, 0);
		for (int row = 0; row < storage.length; row++) for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
			int index = indices[entry]; response[index] = Math.fma(coefficients[entry], pressure[row], response[index]);
		}
		for (int row = 0; row < storage.length; row++) {
			result[row] = storage[row] * pressure[row];
			for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
				int index = indices[entry]; result[row] += MarkerPressureOperator.rescaledResponse(coefficients[entry], inverseMass[index % markerCount], response[index]);
			}
		}
		return work ? positiveWork(pressure) : 0;
	}

	/** The same pulled-back force measures positive work without cancellation between pressure rows. */
	private double positiveWork(double[] pressure) {
		if(exactCoefficients!=null) {
			SignedDyadic work=SignedDyadic.ZERO;
			for(int index=0;index<exactResponse.length;index++)
				work=work.add(exactResponse[index].multiply(exactMassResponse[index]));
			for(int row=0;row<storage.length;row++) {
				var value=SignedDyadic.of(pressure[row]); work=work.add(exactStorage[row].multiply(value).multiply(value));
			}
			return work.toDouble();
		}
		double work=0,error=0;
		for(int index=0;index<response.length+storage.length;index++) {
			double value=index<response.length
					? MarkerPressureOperator.rescaledResponse(response[index],inverseMass[index%markerCount],response[index])
					: MarkerPressureOperator.rescaledResponse(pressure[index-response.length],storage[index-response.length],pressure[index-response.length]);
			double adjusted=value-error, next=work+adjusted; error=(next-work)-adjusted; work=next;
		}
		return work;
	}

	void diagonal(double[] result) {
		checkRows(result); System.arraycopy(storage, 0, result, 0, storage.length);
		for (int row = 0; row < storage.length; row++) {
			if (exactCoefficients != null) {
				SignedDyadic value = exactStorage[row];
				for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
					int marker = indices[entry] % markerCount; value = value.add(exactCoefficients[entry].multiply(exactCoefficients[entry]).multiply(exactInverseMass[marker]));
				}
				result[row] = value.toDouble();
			} else for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
				int marker = indices[entry] % markerCount; double value = coefficients[entry];
				result[row] += MarkerPressureOperator.rescaledResponse(value, inverseMass[marker], value);
			}
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
