package mmm.fluid;

import java.util.Arrays;
import static mmm.fluid.MarkerVelocitySpaceExperiment.near;
import static mmm.fluid.MarkerVelocitySpaceExperiment.rank;
import static mmm.fluid.MarkerVelocitySpaceExperiment.solve;

/** Test-only comparison of shared face means with local, zero-flux curl modes. */
final class MicroFaceVelocityEnrichmentExperiment {
	private record Projection(double[] velocity, double[] pressure) { }
	private MicroFaceVelocityEnrichmentExperiment() { }

	static void validate() {
		MicroFaceVelocitySpaceExperiment.validate();
		verifyCurlGeometry();
		for (boolean heterogeneous : new boolean[]{false, true}) {
			var d = denseCube();
			if (heterogeneous) for (int i = 0; i < d.particleCount(); i++) d.particles.setMass(i, d.particles.mass(i) * (1 + i / 64.0));
			var model = new Model(d); model.verifySupported();
			for (int axis = 0; axis < 3; axis++) {
				var input = new double[3 * d.particleCount()];
				for (int i = 0; i < d.particleCount(); i++) {
					var v = curl(position(d, i), axis);
					for (int a = 0; a < 3; a++) input[a * d.particleCount() + i] = v[a];
				}
				model.verifyCirculation(input);
			}
			model.verifyProjection();
			if (!heterogeneous) model.verifyHydrostatics();
			System.out.printf("PASS enriched closed cube (%s mass): 64 markers, 12 face + 24 curl DOFs, full actual inertia, circulation and pressure work%n",
					heterogeneous ? "heterogeneous" : "uniform");
		}
		var disconnected = MarkerVelocitySpaceExperiment.componentFixture();
		var separate = new Model(disconnected.domain()); separate.verifySupported();
		var input = new double[3 * disconnected.domain().particleCount()];
		for (int a = 0; a < 3; a++) System.arraycopy(disconnected.velocity()[a], 0, input, a * disconnected.domain().particleCount(), disconnected.domain().particleCount());
		near(separate.markerEnergy(input), 2.197265625, 0, "Disconnected fixture has actual supported rotational kinetic energy");
		separate.verifyCirculation(input); separate.verifyProjection();
		System.out.println("PASS enriched disconnected microboxes: six internal curl DOFs retain 2.197265625 J without face means");
		var sparse = new Model(MicroFaceVelocitySpaceExperiment.closedMask(0));
		int sampleRank = rank(sparse.sample);
		near(sampleRank, 12, 0, "Centred one-per-microbox layout only supports the original face modes");
		near(sparse.jointRank(), sampleRank, 0, "Invisible centred curl modes have no pressure response");
		System.out.println("PASS centred sparse alias check: 24 curl columns have zero inertia and zero divergence; no inertial diagonal is added");
		var shifted = MicroFaceVelocitySpaceExperiment.closedMask(0);
		for (int i = 0; i < shifted.particleCount(); i++) shifted.particles.setPositionX(i, shifted.particles.positionX(i) + 1F / 16);
		shifted.gridDirty = true; shifted.ensureInventoryGrid();
		var alias = new Model(shifted); int actualRank = rank(alias.sample), jointRank = alias.jointRank();
		System.out.printf("UNSUPPORTED shifted sparse enrichment: 8 markers, %d DOFs, sample rank=%d, joint rank=%d%n", alias.dofs, actualRank, jointRank);
		if (jointRank <= actualRank) throw new AssertionError("Retained sparse enrichment alias no longer reproduces its zero-inertia volume response; reassess the supported representation");
		near(alias.volumeMomentDefect(), .0078125, 0, "Shifted sparse curl quadrature has a retained geometric first-moment defect");
		System.out.println("UNSUPPORTED shifted curl quadrature: local integral velocity is zero; actual volume samples imply 0.0078125 m^4/s for a unit-velocity mode");
	}

	private static FluidDomain denseCube() {
		var d = MicroFaceVelocitySpaceExperiment.domain(2, 2, 2);
		var positions = new float[256]; var masks = new byte[8]; Arrays.fill(masks, (byte) 255); masks[0] = 0;
		for (int micro = 0; micro < 8; micro++) for (int point = 0; point < 8; point++) {
			int marker = 8 * micro + point;
			for (int axis = 0; axis < 3; axis++) positions[4 * marker + axis] = .5F * (micro >> axis & 1) + .125F + .25F * (point >> axis & 1);
			positions[4 * marker + 3] = 1F / 64;
		}
		d.restoreParticleState(new FlipParticleState(2, 2, 2, 1, 64, 1, positions, new float[192], masks));
		d.ensureInventoryGrid(); return d;
	}
	private static double[] position(FluidDomain d, int marker) {
		return new double[]{d.particles.positionX(marker), d.particles.positionY(marker), d.particles.positionZ(marker)};
	}
	/** Curl of x(1-x)y(1-y) along the selected rotation axis, in local microbox coordinates. */
	private static double[] curl(double[] p, int rotation) {
		int a = (rotation + 1) % 3, b = (rotation + 2) % 3; var v = new double[3];
		v[a] = p[a] * (1 - p[a]) * (1 - 2 * p[b]);
		v[b] = -p[b] * (1 - p[b]) * (1 - 2 * p[a]); return v;
	}
	private static void verifyCurlGeometry() {
		for (int rotation = 0; rotation < 3; rotation++) {
			for (int axis = 0; axis < 3; axis++) for (double side : new double[]{0, 1})
				for (double t : new double[]{0, .25, .5, .75, 1}) for (double u : new double[]{0, .25, .5, .75, 1}) {
					var p = new double[]{.5, .5, .5}; p[axis] = side; p[(axis+1)%3] = t; p[(axis+2)%3] = u;
					near(curl(p, rotation)[axis], 0, 0, "Internal curl has zero normal trace on every microface");
				}
			for (double x : new double[]{.125, .5, .875}) for (double y : new double[]{.125, .5, .875}) for (double z : new double[]{.125, .5, .875}) {
				var p = new double[]{x, y, z}; double divergence = 0;
				for (int axis = 0; axis < 3; axis++) {
					var plus = p.clone(); var minus = p.clone(); plus[axis] += 1.0/32; minus[axis] -= 1.0/32;
					divergence += 16 * (curl(plus, rotation)[axis] - curl(minus, rotation)[axis]);
				}
				near(divergence, 0, 0, "Internal curl is pointwise divergence free");
			}
		}
	}

	private static final class Model {
		final MicroFaceVelocitySpaceExperiment.Model faces;
		final FluidDomain domain;
		final double[][] sample, mass, divergence;
		final int[] markerPiece;
		final int dofs;
		Model(FluidDomain d) {
			domain = d; faces = new MicroFaceVelocitySpaceExperiment.Model(d);
			if (!faces.gasRows.isEmpty()) throw new IllegalArgumentException("Enrichment reference currently requires closed liquid components");
			dofs = faces.faces.size() + 3 * faces.pieces.size();
			markerPiece = new int[d.particleCount()];
			sample = new double[3 * d.particleCount()][dofs]; mass = new double[dofs][dofs];
			divergence = new double[faces.pieces.size()][dofs];
			for (int row = 0; row < sample.length; row++) System.arraycopy(faces.sample[row], 0, sample[row], 0, faces.faces.size());
			for (int row = 0; row < divergence.length; row++) System.arraycopy(faces.divergence[row], 0, divergence[row], 0, faces.faces.size());
			for (int marker = 0; marker < d.particleCount(); marker++) {
				var p = position(d, marker); var micro = new int[3]; var local = new double[3];
				for (int a = 0; a < 3; a++) { micro[a] = (int) Math.floor(p[a] / faces.h); local[a] = p[a] / faces.h - micro[a]; }
				int piece = markerPiece[marker] = faces.index.get(faces.pieceKey(micro));
				for (int rotation = 0; rotation < 3; rotation++) {
					var v = curl(local, rotation);
					for (int a = 0; a < 3; a++) sample[a * d.particleCount() + marker][faces.faces.size() + 3 * piece + rotation] = v[a];
				}
			}
			for (int row = 0; row < sample.length; row++) {
				double m = d.unitScale.kilograms(d.particles.mass(row % d.particleCount()));
				for (int a = 0; a < dofs; a++) for (int b = 0; b < dofs; b++) mass[a][b] += m * sample[row][a] * sample[row][b];
			}
		}
		int jointRank() {
			var joint = new double[sample.length + divergence.length][];
			System.arraycopy(sample, 0, joint, 0, sample.length); System.arraycopy(divergence, 0, joint, sample.length, divergence.length);
			return rank(joint);
		}
		void verifySupported() {
			near(rank(sample), dofs, 0, "All enriched modes have actual marker velocity support");
			near(rank(mass), dofs, 0, "Actual-mass enriched inertia has full rank");
			near(jointRank(), dofs, 0, "Enriched volume constraints use supported velocity modes");
			near(rank(divergence), faces.pieces.size() - faces.componentCount, 0, "Enrichment preserves component pressure rank");
			near(volumeMomentDefect(), 0, 0, "Local curl quadrature agrees with the zero volume first-moment rate");
		}
		/** Gauss identity: zero divergence and zero box-normal flux imply integral u dV = 0. */
		double volumeMomentDefect() {
			var moments = new double[3 * faces.pieces.size()][3]; double h = domain.config.cellSize();
			for (int marker = 0; marker < domain.particleCount(); marker++) {
				int piece = markerPiece[marker]; double volume = domain.particles.volume(marker) * h * h * h;
				for (int rotation = 0; rotation < 3; rotation++) for (int axis = 0; axis < 3; axis++)
					moments[3 * piece + rotation][axis] += volume * sample[axis * domain.particleCount() + marker][faces.faces.size() + 3 * piece + rotation];
			}
			double defect = 0; for (var moment : moments) for (double value : moment) defect = Math.max(defect, Math.abs(value)); return defect;
		}
		double[] fit(double[] velocity) {
			var right = new double[dofs];
			for (int row = 0; row < sample.length; row++) for (int a = 0; a < dofs; a++)
				right[a] += domain.unitScale.kilograms(domain.particles.mass(row % domain.particleCount())) * sample[row][a] * velocity[row];
			return solve(mass, right);
		}
		Projection project(double[] input, double dt) {
			var result = faces.project(input, dt, mass, divergence);
			return new Projection(result.coefficients(), result.liquidPressure());
		}
		void verifyCirculation(double[] velocity) {
			var fitted = fit(velocity); var projected = project(fitted, .001); var sampled = multiply(sample, projected.velocity());
			for (int row = 0; row < sampled.length; row++) near(sampled[row], velocity[row], 1e-12, "Enriched field retains its resolved circulation");
			near(energy(projected.velocity()), markerEnergy(velocity), 1e-10, "Resolved circulation retains actual incoming marker kinetic energy");
			near(energy(fitted), energy(projected.velocity()), 1e-10, "Supported internal circulation retains kinetic energy");
		}
		void verifyProjection() {
			var input = new double[dofs]; for (int a = 0; a < dofs; a++) input[a] = Math.sin(.37 * a);
			var output = project(input, .001); var removed = input.clone(); for (int a = 0; a < dofs; a++) removed[a] -= output.velocity()[a];
			for (double rate : multiply(divergence, output.velocity())) near(rate, 0, 1e-12, "Enriched projection continuity");
			near(energy(input) - energy(output.velocity()), energy(removed), 1e-10, "Enriched actual-mass projection energy identity");
			var pressure = new double[divergence.length]; for (int i = 0; i < pressure.length; i++) pressure[i] = Math.cos(.39 * i);
			var load = new double[dofs]; for (int piece = 0; piece < pressure.length; piece++) for (int a = 0; a < dofs; a++) load[a] += divergence[piece][a] * pressure[piece];
			var acceleration = multiply(sample, solve(mass, load)); var velocity = multiply(sample, input); double work = 0;
			for (int row = 0; row < sample.length; row++) work += domain.unitScale.kilograms(domain.particles.mass(row % domain.particleCount())) * velocity[row] * acceleration[row];
			near(work, dot(pressure, multiply(divergence, input)), 1e-12, "Enriched actual-marker transpose work");
			faces.verifyPressureReaction(acceleration, pressure);
		}
		void verifyHydrostatics() {
			var gravity = new double[sample.length]; Arrays.fill(gravity, domain.particleCount(), 2 * domain.particleCount(), -.001);
			var projected = project(fit(gravity), .001);
			for (double v : multiply(sample, projected.velocity())) near(v, 0, 1e-13, "Uniform dense liquid remains at rest under gravity");
			for (var entry : faces.index.entrySet()) {
				int key = entry.getKey(), microY = 2 * (key / 8 / domain.sizeX % domain.sizeY) + (key % 8 >> 1 & 1);
				near(projected.pressure()[entry.getValue()], -1000 * faces.h * microY, 1e-9, "Dense hydrostatic pressure differences follow actual density and height");
			}
		}
		double energy(double[] velocity) { return .5 * dot(velocity, multiply(mass, velocity)); }
		double markerEnergy(double[] velocity) {
			double result = 0; for (int row = 0; row < velocity.length; row++)
				result += .5 * domain.unitScale.kilograms(domain.particles.mass(row % domain.particleCount())) * velocity[row] * velocity[row];
			return result;
		}
	}
	private static double[] multiply(double[][] matrix, double[] v) { var result = new double[matrix.length]; for (int i = 0; i < result.length; i++) result[i] = dot(matrix[i], v); return result; }
	private static double dot(double[] a, double[] b) { double result = 0; for (int i = 0; i < a.length; i++) result += a[i] * b[i]; return result; }
}
