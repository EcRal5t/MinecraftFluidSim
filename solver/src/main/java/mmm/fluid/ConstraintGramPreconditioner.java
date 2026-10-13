package mmm.fluid;

import mmm.units.SignedDyadic;
import mmm.chemistry.ExactRational;
import java.util.Arrays;

/** Symmetric Gauss-Seidel on the actual pressure Gram, normalized by its positive diagonal. */
final class ConstraintGramPreconditioner {
	private final double[] inverseRoot, lower, workspace, gramDiagonal, gramRoot;
	private final int[] offsets, columns;
	private double[] coarseMode, coarseImage, coarseRhs;
	private SignedDyadic coarseEnergy;
	private ConstraintBlockProjection blocks;
	private double[] refineRhs, refineDirection, refineLast, refineImage;

	ConstraintGramPreconditioner(int markerCount, double[] inverseMass, int[] rowOffsets, int[] indices,
	                            double[] coefficients, double[] coefficientLow, double[] diagonal) {
		int rows = diagonal.length;
		gramDiagonal = diagonal.clone();
		boolean expanded = false; for (double value : coefficientLow) expanded |= value != 0;
		SignedDyadic[] exact = expanded ? new SignedDyadic[coefficients.length] : null;
		SignedDyadic[] weighted = expanded ? new SignedDyadic[coefficients.length] : null;
		if (expanded) for (int entry = 0; entry < exact.length; entry++) {
			exact[entry] = SignedDyadic.of(coefficients[entry]).add(SignedDyadic.of(coefficientLow[entry]));
			weighted[entry] = exact[entry].multiply(SignedDyadic.of(inverseMass[indices[entry] % markerCount]));
		}
		inverseRoot = new double[rows]; gramRoot = new double[rows]; workspace = new double[rows]; offsets = new int[rows + 1];
		for (int row = 0; row < rows; row++) {
			if (!(diagonal[row] >= 0 && Double.isFinite(diagonal[row]))) throw blocked("Invalid actual pressure diagonal at row " + row);
			inverseRoot[row] = diagonal[row] == 0 ? 0 : 1 / Math.sqrt(diagonal[row]);
			gramRoot[row] = inverseRoot[row] == 0 ? 0 : 1 / inverseRoot[row];
		}
		int[] heads = new int[Math.multiplyExact(3, markerCount)], next = new int[indices.length], entryRows = new int[indices.length];
		Arrays.fill(heads, -1);
		for (int row = 0; row < rows; row++) for (int entry = rowOffsets[row]; entry < rowOffsets[row + 1]; entry++) {
			entryRows[entry] = row; next[entry] = heads[indices[entry]]; heads[indices[entry]] = entry;
		}
		int[] marks = new int[rows], touched = new int[rows]; Arrays.fill(marks, -1);
		double[] sums = new double[rows]; SignedDyadic[] exactSums = expanded ? new SignedDyadic[rows] : null; int count = 0;
		int[] builtColumns = new int[Math.max(1, Math.min(indices.length, 256))]; double[] builtLower = new double[builtColumns.length];
		for (int row = 0; row < rows; row++) {
			int touchedCount = 0;
			for (int entry = rowOffsets[row]; entry < rowOffsets[row + 1]; entry++) {
				int index = indices[entry]; double massInverse = inverseMass[index % markerCount];
				for (int other = heads[index]; other >= 0; other = next[other]) {
					int column = entryRows[other]; if (column >= row) continue;
					if (marks[column] != row) { marks[column] = row; sums[column] = 0; if (expanded) exactSums[column] = SignedDyadic.ZERO; touched[touchedCount++] = column; }
					if (expanded) exactSums[column] = exactSums[column].add(weighted[entry].multiply(exact[other]));
					else sums[column] += MarkerPressureOperator.rescaledResponse(coefficients[entry], massInverse, coefficients[other]);
				}
			}
			Arrays.sort(touched, 0, touchedCount);
			for (int slot = 0; slot < touchedCount; slot++) {
				int column = touched[slot]; if (expanded ? exactSums[column].signum() == 0 : sums[column] == 0) continue;
				double normalized = expanded
						? exactSums[column].multiply(SignedDyadic.of(inverseRoot[row])).multiply(SignedDyadic.of(inverseRoot[column])).toDouble()
						: (sums[column] * inverseRoot[row]) * inverseRoot[column];
				if (!Double.isFinite(normalized)) throw blocked("Unrepresentable pressure preconditioner at row " + row);
				if (normalized == 0) continue;
				if (count == builtColumns.length) {
					int capacity = Math.addExact(count, Math.max(1, count));
					builtColumns = Arrays.copyOf(builtColumns, capacity); builtLower = Arrays.copyOf(builtLower, capacity);
				}
				builtColumns[count] = column; builtLower[count++] = normalized;
			}
			offsets[row + 1] = count;
		}
		columns = Arrays.copyOf(builtColumns, count); lower = Arrays.copyOf(builtLower, count);
	}

	/** Applies the symmetric fine inverse and the optional actual common-pressure correction. */
	void apply(double[] rhs, double[] result) {
		if (rhs.length != inverseRoot.length || result.length != rhs.length || rhs == result)
			throw new IllegalArgumentException("Pressure preconditioner vector shape or alias mismatch");
		if (coarseMode == null) { applyFine(rhs, result); return; }
		SignedDyadic coarseLoad = dot(coarseMode, rhs);
		double coarse = quotient(coarseLoad, coarseEnergy);
		for (int row = 0; row < rhs.length; row++) coarseRhs[row] = Math.fma(-coarse, coarseImage[row], rhs[row]);
		applyFine(coarseRhs, result);
		double correction = quotient(coarseLoad.subtract(dot(coarseImage, result)), coarseEnergy);
		for (int row = 0; row < rhs.length; row++) result[row] = Math.fma(coarseMode[row], correction, result[row]);
	}
	void blockProjection(ConstraintBlockProjection projection) {
		blocks = projection.active() ? projection : null;
	}
	void refine() {
		int rows = inverseRoot.length;
		refineRhs = new double[rows]; refineDirection = new double[rows]; refineLast = new double[rows]; refineImage = new double[rows];
	}
	/** Three Richardson corrections give 3B-3BHB+BHBHB, positive for symmetric H and positive B. */
	private void applyFine(double[] rhs, double[] result) {
		applyBase(rhs, result); if (refineRhs == null) return;
		applyGram(result, refineImage);
		for (int row = 0; row < rhs.length; row++) refineRhs[row] = rhs[row] - refineImage[row];
		applyBase(refineRhs, refineDirection); applyGram(refineDirection, refineImage);
		for (int row = 0; row < rhs.length; row++) refineRhs[row] -= refineImage[row];
		applyBase(refineRhs, refineLast);
		for (int row = 0; row < rhs.length; row++) result[row] += refineDirection[row] + refineLast[row];
	}
	private void applyBase(double[] rhs, double[] result) {
		if (blocks == null) applySgs(rhs, result); else blocks.apply(rhs, result, this::applySgs);
	}

	/** Sparse HZ columns retain the original normalized coefficients and their two physical scales. */
	final class GramColumns {
		private final int[] selected, starts, rowIndices, gramEntries;
		GramColumns(int[] selected) {
			this.selected = selected.clone(); starts = new int[selected.length + 1];
			int[] slots = new int[inverseRoot.length]; Arrays.fill(slots, -1);
			for (int slot = 0; slot < selected.length; slot++) { slots[selected[slot]] = slot; starts[slot + 1] = 1; }
			for (int row = 0; row < slots.length; row++) for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
				int column = columns[entry];
				if (slots[row] >= 0) starts[slots[row] + 1] = Math.addExact(starts[slots[row] + 1], 1);
				if (slots[column] >= 0) starts[slots[column] + 1] = Math.addExact(starts[slots[column] + 1], 1);
			}
			for (int slot = 0; slot < selected.length; slot++) starts[slot + 1] = Math.addExact(starts[slot + 1], starts[slot]);
			rowIndices = new int[starts[selected.length]]; gramEntries = new int[rowIndices.length];
			int[] cursor = starts.clone();
			for (int slot = 0; slot < selected.length; slot++) { int entry = cursor[slot]++; rowIndices[entry] = selected[slot]; gramEntries[entry] = -1; }
			for (int row = 0; row < slots.length; row++) for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
				int column = columns[entry];
				if (slots[row] >= 0) { int target = cursor[slots[row]]++; rowIndices[target] = column; gramEntries[target] = entry; }
				if (slots[column] >= 0) { int target = cursor[slots[column]]++; rowIndices[target] = row; gramEntries[target] = entry; }
			}
		}
		void apply(double[] value, double[] result) { multiply(value, result, false); }
		void transpose(double[] value, double[] result) { multiply(value, result, true); }
		private void multiply(double[] value, double[] result, boolean transpose) {
			Arrays.fill(result, 0);
			for (int slot = 0; slot < selected.length; slot++) {
				int source = selected[slot];
				for (int entry = starts[slot]; entry < starts[slot + 1]; entry++) {
					int target = rowIndices[entry], gramEntry = gramEntries[entry];
					double input = value[transpose ? target : source];
					double term = gramEntry < 0 ? gramDiagonal[source] * input
							: gramTerm(lower[gramEntry], Math.max(source, target), Math.min(source, target), input);
					result[transpose ? source : target] += term;
				}
			}
			for (double number : result) if (!Double.isFinite(number)) { multiplyExact(value, result, transpose); break; }
		}
		private void multiplyExact(double[] value, double[] result, boolean transpose) {
			var sums = new SignedDyadic[result.length]; Arrays.fill(sums, SignedDyadic.ZERO);
			for (int slot = 0; slot < selected.length; slot++) {
				int source = selected[slot];
				for (int entry = starts[slot]; entry < starts[slot + 1]; entry++) {
					int target = rowIndices[entry], gramEntry = gramEntries[entry], output = transpose ? source : target;
					var coefficient = gramEntry < 0 ? SignedDyadic.of(gramDiagonal[source])
							: SignedDyadic.of(lower[gramEntry]).multiply(SignedDyadic.of(gramRoot[Math.max(source, target)]))
							.multiply(SignedDyadic.of(gramRoot[Math.min(source, target)]));
					sums[output] = sums[output].add(coefficient.multiply(SignedDyadic.of(value[transpose ? target : source])));
				}
			}
			for (int row = 0; row < result.length; row++) {
				result[row] = sums[row].toDouble();
				if (!Double.isFinite(result[row])) throw blocked("Unrepresentable cached pressure column response at row " + row);
			}
		}
	}
	GramColumns captureColumns(int[] selected) { return new GramColumns(selected); }

	/** Reuses the symmetric Gram already captured for SGS; the production operator remains authoritative. */
	void applyGram(double[] value, double[] result) {
		for (int row = 0; row < value.length; row++) result[row] = gramDiagonal[row] * value[row];
		for (int row = 0; row < value.length; row++) for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
			int column = columns[entry];
			result[row] += gramTerm(lower[entry], row, column, value[column]);
			result[column] += gramTerm(lower[entry], row, column, value[row]);
		}
		for (double number : result) if (!Double.isFinite(number)) { applyGramExact(value, result); break; }
	}
	private double gramTerm(double coefficient, int row, int column, double value) {
		double first = gramRoot[row], second = gramRoot[column];
		double result = ((coefficient * first) * second) * value;
		return (!Double.isFinite(result) || result == 0) && coefficient != 0 && value != 0
				? SignedDyadic.of(coefficient).multiply(SignedDyadic.of(first)).multiply(SignedDyadic.of(second)).multiply(SignedDyadic.of(value)).toDouble()
				: result;
	}
	private void applyGramExact(double[] value, double[] result) {
		var sum = new SignedDyadic[value.length];
		for (int row = 0; row < value.length; row++) sum[row] = SignedDyadic.of(gramDiagonal[row]).multiply(SignedDyadic.of(value[row]));
		for (int row = 0; row < value.length; row++) for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
			int column = columns[entry];
			var coefficient = SignedDyadic.of(lower[entry]).multiply(SignedDyadic.of(gramRoot[row])).multiply(SignedDyadic.of(gramRoot[column]));
			sum[row] = sum[row].add(coefficient.multiply(SignedDyadic.of(value[column])));
			sum[column] = sum[column].add(coefficient.multiply(SignedDyadic.of(value[row])));
		}
		for (int row = 0; row < value.length; row++) {
			result[row] = sum[row].toDouble();
			if (!Double.isFinite(result[row])) throw blocked("Unrepresentable cached pressure Gram response at row " + row);
		}
	}

	/** Q + (I-QA) B (I-AQ), Q=chi chi^T/(chi^T A chi), B=the symmetric fine inverse. */
	void balance(double[] mode, double[] image, SignedDyadic energy) {
		if (mode.length != inverseRoot.length || image.length != mode.length || energy.signum() <= 0)
			throw new IllegalArgumentException("Invalid pressure coarse mode");
		coarseMode = mode; coarseImage = image; coarseEnergy = energy; coarseRhs = new double[mode.length];
	}

	private void applySgs(double[] rhs, double[] result) {
		for (int row = 0; row < rhs.length; row++) {
			double value = rhs[row] * inverseRoot[row];
			for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) value = Math.fma(-lower[entry], workspace[columns[entry]], value);
			workspace[row] = value;
		}
		for (int row = rhs.length - 1; row >= 0; row--) {
			double value = workspace[row]; result[row] = value * inverseRoot[row];
			for (int entry = offsets[row]; entry < offsets[row + 1]; entry++)
				workspace[columns[entry]] = Math.fma(-lower[entry], value, workspace[columns[entry]]);
		}
	}
	static SignedDyadic dot(double[] left, double[] right) {
		SignedDyadic value = SignedDyadic.ZERO;
		for (int row = 0; row < left.length; row++) value = value.add(SignedDyadic.of(left[row]).multiply(SignedDyadic.of(right[row])));
		return value;
	}
	private static double quotient(SignedDyadic numerator, SignedDyadic denominator) {
		int shift = Math.subtractExact(numerator.exponent(), denominator.exponent());
		double value = new ExactRational(shift >= 0 ? numerator.significand().shiftLeft(shift) : numerator.significand(),
				shift < 0 ? denominator.significand().shiftLeft(-shift) : denominator.significand()).toDouble();
		if (!Double.isFinite(value)) throw blocked("Unrepresentable pressure coarse correction");
		return value;
	}
	private static FluidDomain.PressureSolveBlockedException blocked(String message) { return new FluidDomain.PressureSolveBlockedException(message); }
}
