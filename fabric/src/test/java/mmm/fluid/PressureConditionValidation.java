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
					minimum = CpuFluidSolverBackend.refinedSubsteps(substeps, finer);
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
		int[] kinds = new int[rows]; Arrays.fill(kinds, 1);
		for (int row : value(plan, "liquidRows", int[].class)) if (row >= 0) kinds[row] = 0;
		Arrays.fill(kinds, plan.sharedRowStart(), plan.sharedRowEnd(), 2);
		for (int row : value(plan, "gasRows", int[].class)) if (row >= 0) kinds[row] = 3;
		int[] ranks = new int[5], reliableRanks = new int[5]; int modes = 0;
		for (int block = 0; block < plan.basis().blockCount(); block++) if (plan.basis().blockMarkerCount(block) > 0) {
			ranks[MarkerVelocitySpaceExperiment.rank(plan.basis(), block)]++; reliableRanks[plan.basis().reliableRank(block)]++; modes += plan.basis().modeCount(block);
		}
		double h = d.config.cellSize(), unit = h * h * h / d.config.density();
		System.out.printf("CHEM pressure source logical=%d attempt=%d substeps=%d dt=%g markers=%d rows=%d rankCounts=%s reliableRankCounts=%s modes=%d%n",
				logical, attempt, substeps, value(plan, "dt", Float.class), d.particleCount(), rows, Arrays.toString(ranks), Arrays.toString(reliableRanks), modes);
		double[] applied = new double[rows];
		var exactSource = operator.referencedSource(value(plan, "wallPredictor", double[][].class), value(plan, "dt", Float.class),
				value(plan, "gasInitialPressure", double[].class), value(plan, "pressureOrigin", double[].class));
		operator.residual(value(plan, "pressureCorrection", double[].class), value(plan, "pressureCorrectionLow", double[].class), exactSource, applied);
		double residual = pressureRms(applied) / unit;
		var production = new PressureProjectionResult(d.lastPressureIterations, residual, Double.isFinite(residual) && residual <= d.config.pressureTolerance());
		pressurePartition(production, applied, kinds, unit);
		worstSharedTrace(plan, applied, unit);
		pressureFactorization(plan, rhs, applied, unit);
	}
	private static void worstSharedTrace(MarkerPressureProjection plan, double[] residual, double unit) {
		int worst = -1;
		for (int row = plan.sharedRowStart(); row < plan.sharedRowEnd(); row++)
			if (worst < 0 || Math.abs(residual[row]) > Math.abs(residual[worst])) worst = row;
		if (worst < 0) return;
		int start = worst - (worst - plan.sharedRowStart()) % 3;
		System.out.printf("CHEM worstShared row=%d faceStart=%d test=%d residual=%g%n", worst, start, worst - start, residual[worst] / unit);
		var definitions = value(plan, "rows", java.util.List.class); var basis = plan.basis();
		var gram = new double[3][3]; double[] direction = new double[residual.length], image = new double[residual.length];
		for (int test = 0; test < 3; test++) {
			direction[start + test] = 1; plan.operator().apply(direction, image); direction[start + test] = 0;
			for (int other = 0; other < 3; other++) gram[other][test] = image[start + other];
			for (var block : ((CellVelocityConstraintOperator.Row) definitions.get(start + test)).blocks())
				System.out.printf("CHEM sharedBlock row=%d block=%d cell=%d axis=%d reliableRank=%d modes=%d coefficients=%s%n",
						start + test, block.velocityBlock(), basis.physicalCell(block.velocityBlock()), block.axis(),
						basis.reliableRank(block.velocityBlock()), basis.modeCount(block.velocityBlock()), Arrays.toString(block.coefficients()));
		}
		for (int test = 0; test < 3; test++) {
			double[] normalized = new double[3];
			for (int other = 0; other < 3; other++) normalized[other] = gram[test][test] > 0 && gram[other][other] > 0
					? gram[test][other] / Math.sqrt(gram[test][test]) / Math.sqrt(gram[other][other]) : 0;
			System.out.printf("CHEM sharedGram row=%d residual=%g actual=%s correlation=%s%n",
					start + test, residual[start + test] / unit, Arrays.toString(gram[test]), Arrays.toString(normalized));
		}
	}
	private static void pressureFactorization(MarkerPressureProjection plan, double[] rhs, double[] residual, double unit) {
		var basis = plan.basis(); int count = basis.markerCount();
		@SuppressWarnings("unchecked") var definitions = (java.util.List<CellVelocityConstraintOperator.Row>) value(plan, "rows", java.util.List.class);
		var matrix = fullConstraint(basis, definitions); var inverseMass = new double[count];
		for (int marker = 0; marker < count; marker++) inverseMass[marker] = 1 / basis.massKilograms(marker);
		var velocity = value(plan, "wallPredictor", double[][].class);
		double[] storage = value(plan, "storage", double[].class), gas = value(plan, "gasInitialPressure", double[].class);
		var dt = SignedDyadic.of(value(plan, "dt", Float.class)); double normalizedTime = dt.toDouble() * unit;
		double sourceNorm = 0, sourceMaximum = 0;
		for (int row = 0; row < rhs.length; row++) {
			var error = SignedDyadic.of(rhs[row]).subtract(SignedDyadic.of(storage[row]).multiply(SignedDyadic.of(gas[row]))).multiply(dt);
			for (int index = 0; index < matrix[row].length; index++)
				error = error.add(matrix[row][index].multiply(SignedDyadic.of(velocity[index / count][index % count])));
			double scaled = Math.abs(error.toDouble() / normalizedTime);
			sourceNorm = Math.hypot(sourceNorm, scaled); sourceMaximum = Math.max(sourceMaximum, scaled);
		}
		System.out.printf("CHEM source rhsRoundRms=%g rhsRoundMax=%g%n", sourceNorm / Math.sqrt(rhs.length), sourceMaximum);
		var selected = new java.util.TreeSet<Integer>();
		for (int block = 0; block < basis.blockCount(); block++)
			if (basis.blockMarkerCount(block) > 0 && basis.reliableRank(block) + 1 < MarkerVelocitySpaceExperiment.rank(basis, block)) selected.add(block);
		int worst = -1;
		for (int row = plan.sharedRowStart(); row < plan.sharedRowEnd(); row++)
			if (worst < 0 || Math.abs(residual[row]) > Math.abs(residual[worst])) worst = row;
		if (worst >= 0) for (var part : ((CellVelocityConstraintOperator.Row) definitions.get(worst)).blocks()) selected.add(part.velocityBlock());
		int physicalDofs = 0;
		for (int block = 0; block < basis.blockCount(); block++) if (basis.blockMarkerCount(block) > 0)
			physicalDofs += basis.modeCount(block);
		System.out.printf("CHEM rank physicalDofs=%d rows=%d auditedBlocks=%d prime=65537%n", physicalDofs, rhs.length, selected.size());
		for (int block : selected) {
			int n = basis.blockMarkerCount(block), physical = basis.modeCount(block), columns = physical, mismatches = 0;

			var fit = new SignedDyadic[12][3 * n]; double[] rootMass = new double[n];
			for (int i = 0; i < n; i++) {
				int marker = basis.blockMarker(block, i); rootMass[i] = Math.sqrt(inverseMass[marker]);
				for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++) for (int inputAxis = 0; inputAxis < 3; inputAxis++)
					fit[4 * axis + term][inputAxis * n + i] = MarkerVelocitySpaceExperiment.coefficientExact(basis, marker, axis, inputAxis, term);
			}
			var cRows = new java.util.ArrayList<int[]>(); var dRows = new java.util.ArrayList<int[]>();
			double coefficientMax = 0, defectMax = 0, sourceDefectMax = 0;
			for (int row = 0; row < rhs.length; row++) {
				var definition = (CellVelocityConstraintOperator.Row) definitions.get(row); boolean touches = false;
				var declared = new SignedDyadic[12]; Arrays.fill(declared, SignedDyadic.ZERO);
				for (var part : definition.blocks()) if (part.velocityBlock() == block) {
					touches = true;
					for (int term = 0; term < 4; term++) declared[4 * part.axis() + term] = declared[4 * part.axis() + term]
							.add(part.coefficientExact(term));
				}
				if (!touches) continue;
				var actual = new SignedDyadic[3 * n]; Arrays.fill(actual, SignedDyadic.ZERO);
				for (int axis = 0; axis < 3; axis++) for (int i = 0; i < n; i++)
					actual[axis * n + i] = matrix[row][axis * count + basis.blockMarker(block, i)];
				int[] c = new int[3 * n], d = new int[physical]; var sourceDefect = SignedDyadic.ZERO;
				for (int mode = 0; mode < physical; mode++) {
					var load = SignedDyadic.ZERO;
					for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++)
						load = load.add(declared[4 * axis + term].multiply(basis.modeCoefficientExact(block, mode, axis, term)));
					d[mode] = modular(load);
				}
				for (int axis = 0; axis < 3; axis++) for (int i = 0; i < n; i++) {
					int local = axis * n + i; var factored = SignedDyadic.ZERO;
					for (int term = 0; term < 12; term++) factored = factored.add(declared[term].multiply(fit[term][local]));
					var defect = actual[local].subtract(factored); if (defect.signum() != 0) mismatches++;
					coefficientMax = Math.max(coefficientMax, Math.abs(actual[local].toDouble() * rootMass[i]));
					defectMax = Math.max(defectMax, Math.abs(defect.toDouble() * rootMass[i])); c[local] = modular(actual[local]);
					sourceDefect = sourceDefect.add(defect.multiply(SignedDyadic.of(velocity[axis][basis.blockMarker(block, i)])));
				}
				sourceDefectMax = Math.max(sourceDefectMax, Math.abs(sourceDefect.toDouble() / normalizedTime));
				cRows.add(c); dRows.add(d);
			}
			int rankC = modularRank(cRows), rankD = modularRank(dRows);
			System.out.printf("CHEM rank block=%d cell=%d markers=%d physicalDof=%d coefficientUpper=%d declaredModRank=%d actualModRank=%d strictExtra=%s factorMismatchScalars=%d massWeightedCMax=%g massWeightedDefectMax=%g sourceFactorDefectMax=%g%n",
					block, basis.physicalCell(block), n, physical, columns, rankD, rankC, rankC > columns,
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

	private static void pressurePartition(PressureProjectionResult result, double[] residual, int[] kinds, double unit) {
		double[] norm = new double[4], maximum = new double[4]; int[] counts = new int[4];
		for (int row = 0; row < residual.length; row++) {
			int kind = kinds[row]; double value = residual[row] / unit;
			counts[kind]++; norm[kind] = Math.hypot(norm[kind], value); maximum[kind] = Math.max(maximum[kind], Math.abs(value));
		}
		System.out.printf("CHEM retained pressure result=%s%n", result);
		String[] names = {"mean", "internal", "shared", "gas"};
		for (int kind = 0; kind < 4; kind++) System.out.printf("CHEM residual kind=%s rows=%d rms=%g max=%g%n",
				names[kind], counts[kind], counts[kind] == 0 ? 0 : norm[kind] / Math.sqrt(counts[kind]), maximum[kind]);
	}
	private static double pressureRms(double[] values) {
		double norm = 0; for (double value : values) norm = Math.hypot(norm, value); return norm / Math.sqrt(Math.max(1, values.length));
	}

	/** Independent full marker constraints from the declared rows and physical field pullback. */
	private static SignedDyadic[][] fullConstraint(CellAffineVelocity basis, java.util.List<CellVelocityConstraintOperator.Row> definitions) {
		int count = basis.markerCount(); var matrix = new SignedDyadic[definitions.size()][3 * count];
		for (var row : matrix) Arrays.fill(row, SignedDyadic.ZERO);
		for (int row = 0; row < definitions.size(); row++) for (var block : definitions.get(row).blocks())
			for (int ordinal = 0; ordinal < basis.blockMarkerCount(block.velocityBlock()); ordinal++) {
				int marker = basis.blockMarker(block.velocityBlock(), ordinal);
				for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++) {
					int index = axis * count + marker;
					matrix[row][index] = matrix[row][index].add(block.coefficientExact(term)
							.multiply(MarkerVelocitySpaceExperiment.coefficientExact(basis, marker, block.axis(), axis, term)));
				}
			}
		return matrix;
	}
	static <T> T value(Object object, String name, Class<T> type) {
		try {
			Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
			return type.cast(field.get(object));
		} catch (ReflectiveOperationException failure) { throw new AssertionError("Missing pressure diagnostic snapshot: " + name, failure); }
	}
}
