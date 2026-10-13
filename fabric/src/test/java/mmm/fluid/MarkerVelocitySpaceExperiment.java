package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.Arrays;

/** Independent representation checks and a test-only wall-adapted polynomial candidate. */
final class MarkerVelocitySpaceExperiment {
	record ComponentFixture(FluidDomain domain, double[][] velocity) { }
	private MarkerVelocitySpaceExperiment() { }

	static void validateProduction() {
		var failures = new AssertionError("Production velocity space does not satisfy the retained physical modes");
		for (int axis = 0; axis < 3; axis++) {
			int rotation = axis;
			checkCase("closed circulation axis " + axis, () -> closedCirculation(rotation), failures);
		}
		checkCase("single marker near a wall", MarkerVelocitySpaceExperiment::singleMarkerWallMode, failures);
		checkCase("disconnected component motion", MarkerVelocitySpaceExperiment::disconnectedComponentMotion, failures);
		checkCase("independent static liquid pressures", () -> MarkerPressureValidation.pressureUsesCurrentMicroGasGeometry(true), failures);
		if (failures.getSuppressed().length != 0) throw failures;
	}

	private static void checkCase(String name, Runnable test, AssertionError failures) {
		try { test.run(); System.out.println("PASS velocity space: " + name); }
		catch (AssertionError | RuntimeException failure) {
			System.out.println("FAIL velocity space: " + name + ": " + failure.getMessage());
			failures.addSuppressed(failure);
		}
	}

	private static FluidDomain cube() {
		var d = new FluidDomain(2, 2, 2, new FluidSolverConfig(1, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, 0));
		for (int cell = 1; cell < 8; cell++) d.setSolid(cell % 2, cell / 2 % 2, cell / 4, true);
		d.setVolume(0, 0, 0, 1); d.ensureGrid(); return d;
	}

	/** Curl of psi=x(1-x)y(1-y): zero divergence and zero normal velocity on every wall. */
	private static double[] circulation(double[] point, int axis) {
		int a = (axis + 1) % 3, b = (axis + 2) % 3;
		var result = new double[3];
		result[a] = point[a] * (1 - point[a]) * (1 - 2 * point[b]);
		result[b] = -point[b] * (1 - point[b]) * (1 - 2 * point[a]);
		return result;
	}

	private static void closedCirculation(int axis) {
		var d = cube(); var basis = CellAffineVelocity.capture(d);
		var input = new double[3][basis.markerCount()];
		for (int i = 0; i < basis.markerCount(); i++) {
			var v = circulation(point(basis, i), axis);
			for (int a = 0; a < 3; a++) input[a][i] = v[a];
		}
		var projected = new double[3][basis.markerCount()]; var impulse = new double[3][basis.markerCount()];
		new CellAffineWallProjection(d, basis, LiquidPressureGeometry.capture(d)).project(input, projected, impulse);
		double before = energy(basis, input), after = energy(basis, projected);
		System.out.printf("Closed circulation axis=%d kinetic=%g -> %g J%n", axis, before, after);
		near(after, before, 1e-12, "An admissible closed circulation lost kinetic energy");
		for (int a = 0; a < 3; a++) for (int i = 0; i < basis.markerCount(); i++)
			near(projected[a][i], input[a][i], 1e-14, "Wall response changed admissible circulation");
	}

	private static void singleMarkerWallMode() {
		var d = new FluidDomain(3, 3, 3, new FluidSolverConfig(1, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, 0));
		d.restoreParticleState(new FlipParticleState(3, 3, 3, 1, 1, .125F,
				new float[]{.25F, 1.5F, 1.5F, .125F}, new float[]{1, 0, 0}, new byte[27]));
		d.ensureGrid(); var basis = CellAffineVelocity.capture(d);
		double[][] input = {{1}, {0}, {0}}, result = new double[3][1], impulse = new double[3][1];
		new CellAffineWallProjection(d, basis, LiquidPressureGeometry.capture(d)).project(input, result, impulse);
		// u=4x represents this value with actual mass and vanishes on x=0. This is a space check, before pressure.
		near(result[0][0], 1, 1e-14, "A supported one-amplitude wall mode was removed");
	}

	private static void disconnectedComponentMotion() {
		var fixture = componentFixture(); var basis = CellAffineVelocity.capture(fixture.domain()); var input = fixture.velocity();
		var coefficients = new double[3][4 * basis.cellCount()]; basis.fit(input, coefficients);
		var resolved = new double[3][16]; basis.predict(coefficients, resolved);
		double relative = 0;
		for (int a = 0; a < 3; a++) for (int i = 0; i < 16; i++) relative += basis.massKilograms(i) * Math.pow(input[a][i] - resolved[a][i], 2);
		relative = Math.sqrt(relative / (2 * energy(basis, input)));
		System.out.printf("Disconnected component motion: relative fit error=%g%n", relative);
		near(relative, 0, 1e-13, "A common cell fit coupled separately resolved component circulation");
	}
	static ComponentFixture componentFixture() {
		var d = new FluidDomain(3, 3, 3, new FluidSolverConfig(1, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, 0));
		byte[] masks = new byte[27]; Arrays.fill(masks, (byte) 255); masks[13] = 126;
		float[] positions = new float[64], velocities = new float[48];
		for (int component = 0; component < 2; component++) for (int i = 0; i < 8; i++) {
			int marker = 8 * component + i; double[] local = new double[3];
			for (int a = 0; a < 3; a++) {
				local[a] = (i >> a & 1) == 0 ? .25 : .75;
				positions[4 * marker + a] = (float) (1 + .5 * component + .5 * local[a]);
			}
			positions[4 * marker + 3] = 1F / 64;
			var v = circulation(local, 2);
			for (int a = 0; a < 3; a++) velocities[3 * marker + a] = (float) ((component == 0 ? 1 : -1) * v[a]);
		}
		d.restoreParticleState(new FlipParticleState(3, 3, 3, 1, 16, .25F, positions, velocities, masks));
		d.ensureInventoryGrid();
		var input = new double[3][16]; for (int a = 0; a < 3; a++) for (int i = 0; i < 16; i++) input[a][i] = velocities[3 * i + a];
		return new ComponentFixture(d, input);
	}

	static void validateCandidate() {
		sparseAffinePressureCompatibility();
		for (boolean heterogeneous : new boolean[]{false, true}) {
			validateCandidate(false, heterogeneous);
			validateCandidate(true, heterogeneous);
		}
		validateTensorSupport();
		validateComponentCandidate();
	}

	/** Exact B/D comparison for the retained two-point pour source and its single-point limit. */
	private static void sparseAffinePressureCompatibility() {
		double[][] source = {{1.47851717472F, .510141730309F, 2.00144600868F},
				{1.47851717472F, .510141730309F, 2.00539636612F}};
		for (int count : new int[]{1, 2}) for (int rotation = 0; rotation < 3; rotation++) {
			double h = .5, volume = count * Math.scalb(15.0, -26) * h * h * h, mass = 1000 * volume / count;
			double[][] sample = new double[3 * count][12], gram = new double[12][12];
			var moments = new CellRemapSpatialMoments();
			for (int p = 0; p < count; p++) {
				var position = new double[3];
				for (int a = 0; a < 3; a++) {
					int axis = (a + rotation) % 3;
					position[a] = (source[p][axis] - (source[0][axis] + source[count - 1][axis]) / 2) / h;
				}
				moments.add(mass, position, new double[]{-.952395439148F, -.725018322468F, 1.09476661682F});
				for (int a = 0; a < 3; a++) {
					sample[3 * p + a][4 * a] = 1;
					for (int b = 0; b < 3; b++) sample[3 * p + a][4 * a + b + 1] = position[b];
				}
			}
			for (double[] row : sample) for (int a = 0; a < 12; a++) for (int b = 0; b < 12; b++) gram[a][b] += mass * row[a] * row[b];
			double[][] joint = Arrays.copyOf(sample, sample.length + 1); joint[sample.length] = new double[12];
			for (int a = 0; a < 3; a++) joint[sample.length][4 * a + a + 1] = volume / h;
			near(rank(sample), 3 * count, 0, "Actual sparse affine sampling rank");
			near(rank(gram), rank(sample), 0, "Sparse actual-mass Gram rank");
			near(rank(joint), rank(sample) + 1, 0, "Volume constraint adds one unsupported affine direction");
			int hiddenAxis = (3 - rotation) % 3, hiddenColumn = 4 * hiddenAxis + hiddenAxis + 1;
			for (double[] row : sample) near(row[hiddenColumn], 0, 0, "Unobserved affine gradient has zero sampled velocity");
			near(joint[sample.length][hiddenColumn], volume / h, 0, "Unobserved affine gradient has positive volume response");
			var replacement = moments.fit(new double[]{1, 1, 1, 1, 1, 1, 1, 1}, new double[8][3]);
			var features = new double[8][4];
			for (int p = 0; p < 8; p++) {
				features[p][0] = 1; System.arraycopy(replacement.positions()[p], 0, features[p], 1, 3);
			}
			near(rank(features), count, 0, "Positive joint-moment refinement retains its actual spatial rank");
			System.out.printf("Sparse affine compatibility: points=%d rotation=%d sampleRank=%d GramRank=%d jointRank=%d eightNodeRank=%d%n",
					count, rotation, rank(sample), rank(gram), rank(joint), rank(features));
		}
	}
	private static void validateComponentCandidate() {
		var fixture = componentFixture(); var d = fixture.domain(); var basis = CellAffineVelocity.capture(d);
		for (int component = 0; component < 2; component++) {
			int support = component == 0 ? 1 : 128;
			var coefficients = new double[12];
			for (int axis = 0; axis < 3; axis++) {
				var gram = new double[4][4]; var right = new double[4];
				for (int i = 0; i < basis.markerCount(); i++) if (CellRemapMicroTopology.markerSupport(d, i) == support) {
					var p = point(basis, i); for (int a = 0; a < 3; a++) p[a] = (p[a] - 1 - .5 * component) * 2;
					var feature = tensorFeature(p, axis, 2);
					for (int a = 0; a < 4; a++) {
						right[a] += basis.massKilograms(i) * feature[a] * fixture.velocity()[axis][i];
						for (int b = 0; b < 4; b++) gram[a][b] += basis.massKilograms(i) * feature[a] * feature[b];
					}
				}
				if (rank(gram) != 4) throw new AssertionError("Component candidate lacks actual mass support");
				var fit = solve(gram, right); System.arraycopy(fit, 0, coefficients, 4 * axis, 4);
			}
			for (double residual : multiply(tensorDivergence(), coefficients)) near(residual, 0, 1e-13, "Independent component divergence");
			for (int i = 0; i < basis.markerCount(); i++) if (CellRemapMicroTopology.markerSupport(d, i) == support) {
				var p = point(basis, i); for (int a = 0; a < 3; a++) p[a] = (p[a] - 1 - .5 * component) * 2;
				for (int axis = 0; axis < 3; axis++) near(value(p, axis, coefficients, true), fixture.velocity()[axis][i], 1e-14, "Independent component field retains its own circulation");
			}
		}
		System.out.println("PASS tensor component candidate: separate mass-supported fields retain opposite circulations in fixed micro components");
	}
	private static void validateCandidate(boolean tensor, boolean heterogeneous) {
		var d = cube();
		if (heterogeneous) for (int i = 0; i < d.particleCount(); i++) d.particles.setMass(i, d.particles.mass(i) * (1 + i / 8.0));
		var basis = CellAffineVelocity.capture(d);
		// Compare two twelve-coefficient wall spaces with different complete divergence spaces.
		double[][] gram = new double[12][12];
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < basis.markerCount(); i++) {
			var feature = feature(point(basis, i), axis, tensor);
			for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++)
				gram[4 * axis + a][4 * axis + b] += basis.massKilograms(i) * feature[a] * feature[b];
		}
		if (rank(gram) != 12) throw new AssertionError("Wall candidate has unsupported inertia");
		double[][] divergence = divergence(tensor);
		int constraints = tensor ? 7 : 9;
		if (rank(divergence) != constraints) throw new AssertionError("Independent polynomial divergence lost its expected rank");
		for (int rotation = 0; rotation < 3; rotation++) {
			int a = (rotation + 1) % 3, b = (rotation + 2) % 3;
			var coefficient = new double[12]; coefficient[4 * a + (tensor ? 1 : b + 1)] = -2;
			coefficient[4 * b + (tensor ? 2 : a + 1)] = 2;
			for (double residual : multiply(divergence, coefficient)) near(residual, 0, 0, "Analytic circulation divergence");
			for (double x : new double[]{0, .125, .25, .5, .75, .875, 1}) for (double y : new double[]{0, .25, .5, .75, 1})
				for (double z : new double[]{0, .25, .5, .75, 1}) {
					var p = new double[]{x, y, z}; var expected = circulation(p, rotation);
					for (int axis = 0; axis < 3; axis++) near(value(p, axis, coefficient, tensor), expected[axis], 0, "Independent candidate point value");
				}
			var right = new double[12];
			for (int axis = 0; axis < 3; axis++) for (int i = 0; i < basis.markerCount(); i++) {
				var p = point(basis, i); var feature = feature(p, axis, tensor); var expected = circulation(p, rotation);
				for (int term = 0; term < 4; term++) right[4 * axis + term] += basis.massKilograms(i) * feature[term] * expected[axis];
			}
			var fitted = solve(gram, right);
			for (int term = 0; term < 12; term++) near(fitted[term], coefficient[term], 1e-13, "Actual-mass candidate fit");
		}
		// The constant divergence is fixed by the closed-face Gauss identity; the other rows are independent.
		double[][] c = Arrays.copyOfRange(divergence, 1, divergence.length);
		double[][] response = new double[constraints][12], schur = new double[constraints][constraints];
		for (int row = 0; row < constraints; row++) response[row] = solve(gram, c[row]);
		for (int row = 0; row < constraints; row++) for (int column = 0; column < constraints; column++) schur[row][column] = dot(c[row], response[column]);
		var input = new double[12]; for (int i = 0; i < 12; i++) input[i] = Math.sin(.37 * i);
		var pressure = solve(schur, multiply(c, input)); var output = input.clone();
		for (int i = 0; i < 12; i++) for (int row = 0; row < constraints; row++) output[i] -= response[row][i] * pressure[row];
		for (double residual : multiply(divergence, output)) near(residual, 0, 1e-12, "Complete pointwise divergence after actual-mass projection");
		var delta = new double[12]; for (int i = 0; i < 12; i++) delta[i] = input[i] - output[i];
		near(dot(input, multiply(gram, input)) - dot(output, multiply(gram, output)), dot(delta, multiply(gram, delta)), 1e-11,
				"Actual-mass projection energy identity");
		double markerWork = 0;
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < basis.markerCount(); i++) {
			var p = point(basis, i); markerWork += basis.massKilograms(i) * value(p, axis, input, tensor) * value(p, axis, delta, tensor);
		}
		near(markerWork, dot(pressure, multiply(c, input)), 1e-11, "Pressure/actual-marker adjoint virtual work");
		verifyDivergenceValues(input, divergence, tensor);
		System.out.printf("PASS %s wall candidate (%s mass): 12 velocity DOFs, divergence rank %d, nullity %d, mass projection and adjoint work%n",
				tensor ? "tensor" : "P2", heterogeneous ? "heterogeneous" : "uniform", constraints, 12 - constraints);
	}

	private static void validateTensorSupport() {
		var basis = CellAffineVelocity.capture(cube());
		for (int walls = 0; walls < 3; walls++) for (int axis = 0; axis < 3; axis++) {
			int count = walls == 2 ? 4 : 8; var samples = new double[basis.markerCount()][count];
			var gram = new double[count][count];
			for (int i = 0; i < basis.markerCount(); i++) {
				samples[i] = tensorFeature(point(basis, i), axis, walls);
				for (int a = 0; a < count; a++) for (int b = 0; b < count; b++)
					gram[a][b] += basis.massKilograms(i) * samples[i][a] * samples[i][b];
			}
			if (rank(samples) != count || rank(gram) != count) throw new AssertionError("Tensor candidate introduced an unsupported degree of freedom");
			if (walls == 0) for (int mode = 0; mode < 6; mode++) {
				var right = new double[count];
				for (int i = 0; i < basis.markerCount(); i++) {
					double value = rigid(point(basis, i), axis, mode);
					for (int term = 0; term < count; term++) right[term] += basis.massKilograms(i) * samples[i][term] * value;
				}
				var coefficients = solve(gram, right);
				for (double x : new double[]{0, .125, .5, .875, 1}) for (double y : new double[]{0, .5, 1}) for (double z : new double[]{0, .5, 1}) {
					var p = new double[]{x, y, z};
					near(dot(tensorFeature(p, axis, 0), coefficients), rigid(p, axis, mode), 1e-13, "Free tensor field preserves translation and rotation");
				}
				for (int term = 4; term < 8; term++) near(coefficients[term], 0, 1e-13, "Rigid motion has zero component-normal derivative");
			}
		}
		double mass = basis.massKilograms(0), x = .25;
		double amplitude = mass * x / (mass * x * x);
		near(amplitude * x, 1, 0, "Single-marker wall shape has actual one-amplitude inertia");
		near(amplitude * 0, 0, 0, "Single-marker wall normal trace");
		System.out.println("PASS tensor support: 24/24/12 velocity DOFs for zero/one/two normal walls, eight actual markers; free translation/rotation; sparse wall amplitude");
	}

	private static double rigid(double[] p, int axis, int mode) {
		if (mode < 3) return axis == mode ? 1 : 0;
		int rotation = mode - 3;
		return axis == (rotation + 1) % 3 ? -(p[(rotation + 2) % 3] - .5)
				: axis == (rotation + 2) % 3 ? p[(rotation + 1) % 3] - .5 : 0;
	}
	private static double[] tensorFeature(double[] p, int axis, int walls) {
		double t = p[(axis + 1) % 3] - .5, u = p[(axis + 2) % 3] - .5;
		var tangent = new double[]{1, t, u, t*u};
		double q = walls == 0 ? 1 : walls == 1 ? p[axis] : p[axis] * (1 - p[axis]);
		var result = new double[walls == 2 ? 4 : 8];
		for (int i = 0; i < 4; i++) {
			result[i] = q * tangent[i];
			if (walls != 2) result[i + 4] = q * (p[axis] - .5) * tangent[i];
		}
		return result;
	}

	private static double[][] divergence(boolean tensor) {
		if (tensor) return tensorDivergence();
		// Monomials: 1,x,y,z,x^2,y^2,z^2,xy,xz,yz.
		var result = new double[10][12];
		for (int axis = 0; axis < 3; axis++) {
			int base = 4 * axis;
			result[0][base] = 1; result[axis + 1][base] = -2;
			result[0][base + axis + 1] = -.5; result[axis + 1][base + axis + 1] = 3; result[axis + 4][base + axis + 1] = -3;
			for (int tangent = 0; tangent < 3; tangent++) if (tangent != axis) {
				int term = base + tangent + 1, cross = axis + tangent == 1 ? 7 : axis + tangent == 2 ? 8 : 9;
				result[0][term] = -.5; result[axis + 1][term] = 1; result[tangent + 1][term] = 1; result[cross][term] = -2;
			}
		}
		return result;
	}
	private static double[][] tensorDivergence() {
		// Monomials: 1,x,y,z,xy,xz,yz,xyz.
		var result = new double[8][12];
		for (int axis = 0; axis < 3; axis++) {
			int base = 4 * axis, t = (axis + 1) % 3, u = (axis + 2) % 3;
			result[0][base] = 1; result[axis + 1][base] = -2;
			for (int slot = 1; slot <= 2; slot++) {
				int tangent = slot == 1 ? t : u, term = base + slot;
				result[0][term] = -.5; result[axis + 1][term] = 1; result[tangent + 1][term] = 1;
				result[cross(axis, tangent)][term] = -2;
			}
			result[0][base + 3] = .25; result[axis + 1][base + 3] = -.5;
			result[t + 1][base + 3] = result[u + 1][base + 3] = -.5;
			result[cross(axis, t)][base + 3] = result[cross(axis, u)][base + 3] = result[cross(t, u)][base + 3] = 1;
			result[7][base + 3] = -2;
		}
		return result;
	}
	private static int cross(int a, int b) { return a + b == 1 ? 4 : a + b == 2 ? 5 : 6; }
	private static void verifyDivergenceValues(double[] coefficients, double[][] matrix, boolean tensor) {
		var polynomial = multiply(matrix, coefficients);
		for (double x : new double[]{0, .25, .5, .75, 1}) for (double y : new double[]{0, .25, .5, .75, 1})
			for (double z : new double[]{0, .25, .5, .75, 1}) {
				var p = new double[]{x, y, z}; double derivative = 0;
				for (int axis = 0; axis < 3; axis++) {
					double phi;
					if (tensor) {
						double t = p[(axis + 1) % 3] - .5, u = p[(axis + 2) % 3] - .5;
						phi = coefficients[4 * axis] + coefficients[4 * axis + 1] * t + coefficients[4 * axis + 2] * u + coefficients[4 * axis + 3] * t * u;
					} else {
						phi = coefficients[4 * axis]; for (int a = 0; a < 3; a++) phi += coefficients[4 * axis + a + 1] * (p[a] - .5);
						derivative += p[axis] * (1 - p[axis]) * coefficients[4 * axis + axis + 1];
					}
					derivative += (1 - 2 * p[axis]) * phi;
				}
				double[] monomials = tensor ? new double[]{1, x, y, z, x*y, x*z, y*z, x*y*z}
						: new double[]{1, x, y, z, x*x, y*y, z*z, x*y, x*z, y*z};
				near(dot(polynomial, monomials), derivative, 1e-14, "Independent analytic divergence coefficients");
			}
	}
	private static double[] feature(double[] p, int axis, boolean tensor) {
		double q = p[axis] * (1 - p[axis]);
		if (!tensor) return new double[]{q, q * (p[0] - .5), q * (p[1] - .5), q * (p[2] - .5)};
		return tensorFeature(p, axis, 2);
	}
	private static double value(double[] p, int axis, double[] coefficients, boolean tensor) {
		var feature = feature(p, axis, tensor); double result = 0;
		for (int term = 0; term < 4; term++) result += feature[term] * coefficients[4 * axis + term]; return result;
	}
	private static double[] point(CellAffineVelocity basis, int marker) {
		return new double[]{basis.position(marker, 0), basis.position(marker, 1), basis.position(marker, 2)};
	}
	private static double energy(CellAffineVelocity basis, double[][] velocity) {
		double sum = 0; for (int a = 0; a < 3; a++) for (int i = 0; i < basis.markerCount(); i++) sum += .5 * basis.massKilograms(i) * velocity[a][i] * velocity[a][i]; return sum;
	}
	private static double[] multiply(double[][] matrix, double[] vector) {
		var result = new double[matrix.length]; for (int row = 0; row < result.length; row++) result[row] = dot(matrix[row], vector); return result;
	}
	private static double dot(double[] a, double[] b) {
		double sum = 0; for (int i = 0; i < a.length; i++) sum += a[i] * b[i]; return sum;
	}
	/** Small, well-conditioned test-only dense reference, independent of production PCG and fits. */
	static double[] solve(double[][] input, double[] right) {
		int n = right.length; var a = new double[n][n + 1];
		for (int row = 0; row < n; row++) { System.arraycopy(input[row], 0, a[row], 0, n); a[row][n] = right[row]; }
		for (int column = 0; column < n; column++) {
			int pivot = column; for (int row = column + 1; row < n; row++) if (Math.abs(a[row][column]) > Math.abs(a[pivot][column])) pivot = row;
			if (a[pivot][column] == 0) throw new AssertionError("Independent candidate matrix is singular");
			var swap = a[pivot]; a[pivot] = a[column]; a[column] = swap;
			double scale = a[column][column]; for (int j = column; j <= n; j++) a[column][j] /= scale;
			for (int row = 0; row < n; row++) if (row != column) { double q = a[row][column]; for (int j = column; j <= n; j++) a[row][j] -= q * a[column][j]; }
		}
		var result = new double[n]; for (int i = 0; i < n; i++) result[i] = a[i][n]; return result;
	}
	/** Exact rank for these small dyadic matrices, including every real input direction. */
	static int rank(double[][] input) {
		var a = new ExactRational[input.length][];
		for (int row = 0; row < a.length; row++) {
			a[row] = new ExactRational[input[row].length];
			for (int col = 0; col < a[row].length; col++) a[row][col] = exact(input[row][col]);
		}
		return rank(a);
	}
	static ExactRational exact(double input) {
		var value = SignedDyadic.of(input);
		return new ExactRational(value.significand().shiftLeft(Math.max(0, value.exponent())),
				BigInteger.ONE.shiftLeft(Math.max(0, -value.exponent())));
	}
	static int rank(ExactRational[][] input) { return eliminate(input).pivots().length; }
	private record Echelon(ExactRational[][] values, int[] pivots) { }
	private static Echelon eliminate(ExactRational[][] input) {
		var a = new ExactRational[input.length][]; for (int row = 0; row < a.length; row++) a[row] = input[row].clone();
		int columns = a.length == 0 ? 0 : a[0].length; var pivots = new int[Math.min(a.length, columns)];
		int rank = 0;
		for (int column = 0; column < columns && rank < a.length; column++) {
			int pivot = rank; while (pivot < a.length && a[pivot][column].signum() == 0) pivot++;
			if (pivot == a.length) continue;
			var swap = a[pivot]; a[pivot] = a[rank]; a[rank] = swap;
			var scale = a[rank][column]; var inverse = new ExactRational(scale.denominator(), scale.numerator());
			for (int j = column; j < a[rank].length; j++) a[rank][j] = a[rank][j].multiply(inverse);
			for (int row = rank + 1; row < a.length; row++) { var q = a[row][column]; for (int j = column; j < a[row].length; j++) a[row][j] = a[row][j].subtract(q.multiply(a[rank][j])); }
			pivots[rank++] = column;
		}
		return new Echelon(a, Arrays.copyOf(pivots, rank));
	}
	/** Each returned row is an exact coefficient vector in the input's null space. */
	static ExactRational[][] nullspace(ExactRational[][] input) {
		var reduction = eliminate(input); var a = reduction.values(); var pivots = reduction.pivots(); int columns = input[0].length;
		var result = new ExactRational[columns - pivots.length][columns]; int output = 0, pivot = 0;
		for (int free = 0; free < columns; free++) {
			if (pivot < pivots.length && pivots[pivot] == free) { pivot++; continue; }
			var vector = result[output++]; Arrays.fill(vector, ExactRational.ZERO); vector[free] = ExactRational.ONE;
			for (int row = pivots.length - 1; row >= 0; row--) {
				var value = ExactRational.ZERO;
				for (int column = pivots[row] + 1; column < columns; column++) value = value.subtract(a[row][column].multiply(vector[column]));
				vector[pivots[row]] = value;
			}
		}
		return result;
	}
	static void near(double actual, double expected, double tolerance, String message) {
		if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance) throw new AssertionError(message + ": actual=" + actual + ", expected=" + expected);
	}
}
