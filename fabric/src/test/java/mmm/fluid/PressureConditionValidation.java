package mmm.fluid;

import java.lang.reflect.Field;
import java.util.Arrays;
import mmm.units.SignedDyadic;

/** Bounded production-source pressure conditioning and factorization diagnostics. */
final class PressureConditionValidation {
	private PressureConditionValidation() { }

	/** Stops at the first failed pressure source; the original forty-step chemistry audit stays intact. */
	static void validateChemicalSource() {
		FluidDomain source;
		try { source = M7ChemicalTransportValidation.thermalChemicalExtractionSource(); }
		catch (Exception invalid) { throw new AssertionError("Chemical pressure fixture could not load", invalid); }
		var impulse = M7ChemicalTransportValidation.thermalChemicalExtractionImpulse(source);
		for (int logical = 0; logical < 4; logical++) {
			var saved = source.captureAuthoritativeState(); int minimum = 1; long spent = 0;
			for (int attempt = 0; attempt <= 16; attempt++) {
				var d = FluidDomain.restoreAuthoritativeState(saved); d.minimumMechanicalSubsteps = minimum;
				d.applyMovingBodyCouplings(logical == 0 ? java.util.List.of(impulse) : java.util.List.of());
				int substeps = d.calculateSubsteps(d.config.timeStep()); spent += substeps;
				if (spent > CpuFluidSolverBackend.MAXIMUM_CUMULATIVE_SUBSTEPS)
					throw new AssertionError("Chemical pressure diagnostic reached the production cumulative substep budget");
				try {
					d.step(false, 0, false, true, substeps); source = d;
					System.out.printf("CHEM logical=%d attempt=%d complete substeps=%d markers=%d%n", logical, attempt, substeps, d.particleCount());
					break;
				} catch (MechanicalTimeRefinement finer) {
					if (attempt == 16) throw finer;
					minimum = Math.max(Math.multiplyExact(minimum, 2), finer.minimumSubsteps);
				} catch (FluidDomain.PressureSolveBlockedException failed) {
					System.out.printf("CHEM logical=%d attempt=%d substeps=%d pressureFailure=%s%n", logical, attempt, substeps, failed);
					compareChemicalPressure(d, logical, attempt, substeps); throw failed;
				}
			}
		}
		System.out.println("CHEM diagnostic completed four logical steps without reproducing the retained pressure failure");
	}
	private static void compareChemicalPressure(FluidDomain d, int logical, int attempt, int substeps) {
		var plan = d.pressureSystem.markerProjection();
		if (!value(plan, "active", Boolean.class)) return;
		var operator = plan.operator(); int rows = operator.rowCount();
		double[] rhs = value(plan, "rhs", double[].class).clone();
		double[] common = value(plan, "commonPressureMode", double[].class), diagonal = new double[rows]; operator.diagonal(diagonal);
		double[] initial = chemicalInitialPressure(plan, common);
		for (int row = 0; row < rows; row++) if (diagonal[row] == 0) initial[row] = 0;
		int[] kinds = new int[rows]; Arrays.fill(kinds, 1);
		for (int row : value(plan, "liquidRows", int[].class)) if (row >= 0) kinds[row] = 0;
		Arrays.fill(kinds, plan.sharedRowStart(), plan.sharedRowEnd(), 2);
		for (int row : value(plan, "gasRows", int[].class)) if (row >= 0) kinds[row] = 3;
		int[] ranks = new int[5]; int expanded = 0;
		for (int block = 0; block < plan.basis().blockCount(); block++) if (plan.basis().blockMarkerCount(block) > 0) {
			ranks[plan.basis().rank(block)]++; if (plan.basis().expandedCell(block)) expanded++;
		}
		double h = d.config.cellSize(), unit = h * h * h / d.config.density();
		System.out.printf("CHEM pressure source logical=%d attempt=%d substeps=%d dt=%g markers=%d rows=%d rankCounts=%s expanded=%d%n",
				logical, attempt, substeps, value(plan, "dt", Float.class), d.particleCount(), rows, Arrays.toString(ranks), expanded);
		long started = System.nanoTime(); double[] applied = new double[rows]; operator.apply(plan.pressure(), applied);
		for (int row = 0; row < rows; row++) applied[row] = rhs[row] - applied[row];
		double residual = pressureRms(applied) / unit;
		var production = new PressureProjectionResult(d.lastPressureIterations, residual, Double.isFinite(residual) && residual <= d.config.pressureTolerance());
		pressurePartition("production-retained", production, operator, rhs, plan.pressure(), kinds, unit, -1);
		pressureFactorization(plan, rhs, applied, unit);
		for (int mode = 0; mode < 2; mode++) {
			started = System.nanoTime();
			var preconditioner = mode == 0 ? operator.preconditioner(diagonal) : operator.preconditioner(diagonal, common);
			double[] pressure = initial.clone();
			var result = comparePressure(operator, preconditioner, rhs, pressure, d.config.maxPressureIterations(), d.config.pressureTolerance() * unit, unit);
			pressurePartition(mode == 0 ? "SGS" : "chi-SGS", result, operator, rhs, pressure, kinds, unit, started);
		}
	}
	private static double[] chemicalInitialPressure(MarkerPressureProjection plan, double[] common) {
		double[] storage = value(plan, "storage", double[].class), initialGas = value(plan, "gasInitialPressure", double[].class);
		int[] gasRows = value(plan, "gasRows", int[].class); double largest = 0, weight = 0, mean = 0;
		for (int row : gasRows) if (row >= 0) largest = Math.max(largest, storage[row]);
		if (largest > 0) for (int row : gasRows) if (row >= 0) {
			double added = storage[row] / largest, total = weight + added;
			if (added == 0) continue;
			mean += added / total * (initialGas[row] - mean); weight = total;
		}
		double[] initial = new double[common.length];
		for (int row = 0; row < common.length; row++) initial[row] = common[row] * mean;
		return initial;
	}
	private static void pressureFactorization(MarkerPressureProjection plan, double[] rhs, double[] residual, double unit) {
		var basis = plan.basis(); var operator = plan.operator(); int count = basis.markerCount();
		int[] offsets = value(operator, "offsets", int[].class), indices = value(operator, "indices", int[].class);
		double[] high = value(operator, "coefficients", double[].class), low = value(operator, "coefficientLow", double[].class);
		double[] inverseMass = value(operator, "inverseMass", double[].class);
		var approximate = new SignedDyadic[high.length];
		for (int i = 0; i < approximate.length; i++) approximate[i] = SignedDyadic.of(high[i]).add(SignedDyadic.of(low[i]));
		var stored = value(operator, "exactCoefficients", SignedDyadic[].class);
		if (stored == null) stored = approximate;
		var velocity = value(plan, "wallPredictor", double[][].class);
		double[] storage = value(plan, "storage", double[].class), gas = value(plan, "gasInitialPressure", double[].class);
		var dt = SignedDyadic.of(value(plan, "dt", Float.class)); double normalizedTime = dt.toDouble() * unit;
		double sourceNorm = 0, sourceMaximum = 0;
		for (int row = 0; row < rhs.length; row++) {
			var error = SignedDyadic.of(rhs[row]).subtract(SignedDyadic.of(storage[row]).multiply(SignedDyadic.of(gas[row]))).multiply(dt);
			for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
				int index = indices[entry]; error = error.add(stored[entry].multiply(SignedDyadic.of(velocity[index / count][index % count])));
			}
			double scaled = Math.abs(error.toDouble() / normalizedTime);
			sourceNorm = Math.hypot(sourceNorm, scaled); sourceMaximum = Math.max(sourceMaximum, scaled);
		}
		System.out.printf("CHEM source rhsRoundRms=%g rhsRoundMax=%g%n", sourceNorm / Math.sqrt(rhs.length), sourceMaximum);
		var definitions = value(plan, "rows", java.util.List.class); var selected = new java.util.TreeSet<Integer>();
		for (int block = 0; block < basis.blockCount(); block++) if (basis.blockMarkerCount(block) > 0 && basis.expandedCell(block)) selected.add(block);
		int worst = -1;
		for (int row = plan.sharedRowStart(); row < plan.sharedRowEnd(); row++)
			if (worst < 0 || Math.abs(residual[row]) > Math.abs(residual[worst])) worst = row;
		if (worst >= 0) for (var part : ((CellVelocityConstraintOperator.Row) definitions.get(worst)).blocks()) selected.add(part.velocityBlock());
		int physicalDofs = 0;
		for (int block = 0; block < basis.blockCount(); block++) if (basis.blockMarkerCount(block) > 0)
			for (int axis = 0; axis < 3; axis++) physicalDofs += basis.axisRank(block, axis);
		System.out.printf("CHEM rank physicalDofs=%d rows=%d auditedBlocks=%d prime=65537%n", physicalDofs, rhs.length, selected.size());
		for (int block : selected) {
			int n = basis.blockMarkerCount(block), physical = 0, columns = 0, mismatches = 0;
			// The stored bracket map has at most four columns per active axis; sparse hulls admit fewer.
			// Exceeding this larger upper bound certifies extra directions, independently of D's modular rank.
			for (int axis = 0; axis < 3; axis++) { physical += basis.axisRank(block, axis); if (basis.axisRank(block, axis) > 0) columns += 4; }
			int[] ordinal = new int[count]; Arrays.fill(ordinal, -1);
			var fit = new SignedDyadic[12][n]; double[] rootMass = new double[n];
			for (int i = 0; i < n; i++) {
				int marker = basis.blockMarker(block, i); ordinal[marker] = i; rootMass[i] = Math.sqrt(inverseMass[marker]);
				for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++) fit[4 * axis + term][i] = basis.coefficientExact(marker, axis, term);
			}
			var cRows = new java.util.ArrayList<int[]>(); var approximateRows = new java.util.ArrayList<int[]>(); var dRows = new java.util.ArrayList<int[]>();
			double coefficientMax = 0, defectMax = 0, sourceDefectMax = 0;
			for (int row = 0; row < rhs.length; row++) {
				var definition = (CellVelocityConstraintOperator.Row) definitions.get(row); boolean touches = false;
				var declared = new SignedDyadic[12]; Arrays.fill(declared, SignedDyadic.ZERO);
				for (var part : definition.blocks()) if (part.velocityBlock() == block) {
					touches = true; double[] coefficient = part.coefficients();
					for (int term = 0; term < 4; term++) declared[4 * part.axis() + term] = declared[4 * part.axis() + term].add(SignedDyadic.of(coefficient[term]));
				}
				if (!touches) continue;
				var actual = new SignedDyadic[3 * n]; Arrays.fill(actual, SignedDyadic.ZERO);
				int[] approximateRow = new int[3 * n];
				for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
					int index = indices[entry], local = ordinal[index % count];
					if (local >= 0) {
						actual[index / count * n + local] = stored[entry];
						approximateRow[index / count * n + local] = modular(approximate[entry]);
					}
				}
				int[] c = new int[3 * n], d = new int[12]; var sourceDefect = SignedDyadic.ZERO;
				for (int term = 0; term < 12; term++) d[term] = modular(declared[term]);
				for (int axis = 0; axis < 3; axis++) for (int i = 0; i < n; i++) {
					int local = axis * n + i; var factored = SignedDyadic.ZERO;
					for (int term = 0; term < 4; term++) factored = factored.add(declared[4 * axis + term].multiply(fit[4 * axis + term][i]));
					var defect = actual[local].subtract(factored); if (defect.signum() != 0) mismatches++;
					coefficientMax = Math.max(coefficientMax, Math.abs(actual[local].toDouble() * rootMass[i]));
					defectMax = Math.max(defectMax, Math.abs(defect.toDouble() * rootMass[i])); c[local] = modular(actual[local]);
					sourceDefect = sourceDefect.add(defect.multiply(SignedDyadic.of(velocity[axis][basis.blockMarker(block, i)])));
				}
				sourceDefectMax = Math.max(sourceDefectMax, Math.abs(sourceDefect.toDouble() / normalizedTime));
				cRows.add(c); approximateRows.add(approximateRow); dRows.add(d);
			}
			int rankC = modularRank(cRows), rankD = modularRank(dRows);
			System.out.printf("CHEM rank block=%d cell=%d markers=%d physicalDof=%d coefficientUpper=%d declaredModRank=%d actualModRank=%d approxModRank=%d strictExtra=%s factorMismatchScalars=%d massWeightedCMax=%g massWeightedDefectMax=%g sourceFactorDefectMax=%g%n",
					block, basis.physicalCell(block), n, physical, columns, rankD, rankC, modularRank(approximateRows), rankC > columns,
					mismatches, coefficientMax, defectMax, sourceDefectMax);
		}
	}
	private static int modular(SignedDyadic value) {
		long power = value.exponent(); long residue = value.significand().mod(java.math.BigInteger.valueOf(65537)).longValue();
		return (int) (residue * modularPower(power >= 0 ? 2 : 32769, Math.abs(power)) % 65537);
	}
	private static long modularPower(long base, long exponent) {
		long result = 1;
		for (; exponent > 0; exponent >>>= 1, base = base * base % 65537) if ((exponent & 1) != 0) result = result * base % 65537;
		return result;
	}
	private static int modularRank(java.util.List<int[]> input) {
		if (input.isEmpty()) return 0;
		int[][] matrix = input.toArray(int[][]::new); int rank = 0;
		for (int col = 0; col < matrix[0].length && rank < matrix.length; col++) {
			int pivot = rank; while (pivot < matrix.length && matrix[pivot][col] == 0) pivot++;
			if (pivot == matrix.length) continue;
			int[] swap = matrix[rank]; matrix[rank] = matrix[pivot]; matrix[pivot] = swap;
			long inverse = modularPower(matrix[rank][col], 65535);
			for (int row = rank + 1; row < matrix.length; row++) {
				long factor = matrix[row][col] * inverse % 65537;
				for (int k = col; k < matrix[row].length; k++) matrix[row][k] = (int) ((matrix[row][k] - factor * matrix[rank][k] % 65537 + 65537) % 65537);
			}
			rank++;
		}
		return rank;
	}

	private static PressureProjectionResult comparePressure(CellVelocityConstraintOperator operator, ConstraintGramPreconditioner preconditioner,
	                                                       double[] rhs, double[] pressure, int limit, double tolerance, double unit) {
		int n = rhs.length, iterations = 0;
		double[] r = new double[n], z = new double[n], direction = new double[n], applied = new double[n];
		operator.apply(pressure, applied); for (int i = 0; i < n; i++) r[i] = rhs[i] - applied[i];
		preconditioner.apply(r, z); System.arraycopy(z, 0, direction, 0, n);
		double residual = pressureRms(r), rz = pressureDot(r, z);
		while (residual > tolerance && iterations < limit) {
			double denominator = operator.applyWithWork(direction, applied);
			if (!(denominator > 0 && Double.isFinite(denominator) && rz > 0 && Double.isFinite(rz))) break;
			double alpha = rz / denominator; if (!Double.isFinite(alpha)) break;
			for (int i = 0; i < n; i++) { pressure[i] += alpha * direction[i]; r[i] -= alpha * applied[i]; }
			iterations++; residual = pressureRms(r);
			if (residual <= tolerance) {
				operator.apply(pressure, applied); for (int i = 0; i < n; i++) r[i] = rhs[i] - applied[i]; residual = pressureRms(r);
				if (residual <= tolerance) break;
				preconditioner.apply(r, z); System.arraycopy(z, 0, direction, 0, n); rz = pressureDot(r, z); continue;
			}
			preconditioner.apply(r, z); double nextRz = pressureDot(r, z), beta = nextRz / rz;
			if (!(nextRz > 0 && Double.isFinite(nextRz) && Double.isFinite(beta))) break;
			for (int i = 0; i < n; i++) direction[i] = z[i] + beta * direction[i]; rz = nextRz;
		}
		operator.apply(pressure, applied); for (int i = 0; i < n; i++) r[i] = rhs[i] - applied[i]; residual = pressureRms(r);
		return new PressureProjectionResult(iterations, residual / unit, Double.isFinite(residual) && residual <= tolerance);
	}
	private static void pressurePartition(String mode, PressureProjectionResult result, CellVelocityConstraintOperator operator,
	                                      double[] rhs, double[] pressure, int[] kinds, double unit, long started) {
		double[] applied = new double[rhs.length], norm = new double[4], maximum = new double[4]; int[] counts = new int[4];
		operator.apply(pressure, applied);
		for (int row = 0; row < rhs.length; row++) {
			int kind = kinds[row]; double residual = (rhs[row] - applied[row]) / unit;
			counts[kind]++; norm[kind] = Math.hypot(norm[kind], residual); maximum[kind] = Math.max(maximum[kind], Math.abs(residual));
		}
		System.out.printf("CHEM pressure mode=%s result=%s elapsedSeconds=%g%n", mode, result,
				started < 0 ? Double.NaN : (System.nanoTime() - started) / 1e9);
		String[] names = {"mean", "internal", "shared", "gas"};
		for (int kind = 0; kind < 4; kind++) System.out.printf("CHEM residual mode=%s kind=%s rows=%d rms=%g max=%g%n",
				mode, names[kind], counts[kind], counts[kind] == 0 ? 0 : norm[kind] / Math.sqrt(counts[kind]), maximum[kind]);
	}
	private static double pressureDot(double[] first, double[] second) {
		double result = 0; for (int i = 0; i < first.length; i++) result += first[i] * second[i]; return result;
	}
	private static double pressureRms(double[] values) {
		double norm = 0; for (double value : values) norm = Math.hypot(norm, value); return norm / Math.sqrt(Math.max(1, values.length));
	}

	private static <T> T value(Object object, String name, Class<T> type) {
		try {
			Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
			return type.cast(field.get(object));
		} catch (ReflectiveOperationException failure) { throw new AssertionError("Missing pressure diagnostic snapshot: " + name, failure); }
	}
}
