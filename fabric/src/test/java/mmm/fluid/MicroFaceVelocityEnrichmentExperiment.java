package mmm.fluid;

import java.util.Arrays;
import java.util.ArrayList;
import mmm.chemistry.ExactRational;
import static mmm.fluid.MarkerVelocitySpaceExperiment.exact;
import static mmm.fluid.MarkerVelocitySpaceExperiment.nullspace;
import static mmm.fluid.MarkerVelocitySpaceExperiment.near;
import static mmm.fluid.MarkerVelocitySpaceExperiment.rank;
import static mmm.fluid.MarkerVelocitySpaceExperiment.solve;

/** Test-only comparison of shared face means with local, zero-flux curl modes. */
final class MicroFaceVelocityEnrichmentExperiment {
	private record Projection(double[] velocity, double[] pressure) { }
	private record InternalMode(int piece, ExactRational[] coefficients, double[] sample) {
		double[] value(double[] local) {
			var result = new double[3];
			for (int axis = 0; axis < 3; axis++) {
				var value = ExactRational.ZERO;
				for (int rotation = 0; rotation < 3; rotation++) value = value.add(exact(curl(local, rotation)[axis]).multiply(coefficients[rotation]));
				result[axis] = value.toDouble();
			}
			return result;
		}
	}
	private MicroFaceVelocityEnrichmentExperiment() { }

	static void validateMomentSupport() {
		MarkerVelocitySpaceExperiment.validateCandidate();
		validate();
		for (int mask = 0; mask < 256; mask++) for (boolean heterogeneous : new boolean[]{false, true}) for (boolean shifted : new boolean[]{false, true}) {
			var d = MicroFaceVelocitySpaceExperiment.closedMask(mask);
			if (heterogeneous) for (int i = 0; i < d.particleCount(); i++) d.particles.setMass(i, d.particles.mass(i) * (1 + i / 8.0));
			if (shifted) for (int i = 0; i < d.particleCount(); i++) {
				d.particles.setPositionX(i, d.particles.positionX(i) + (i % 3 - 1) / 32F);
				d.particles.setPositionY(i, d.particles.positionY(i) + ((i + 1) % 3 - 1) / 32F);
				d.particles.setPositionZ(i, d.particles.positionZ(i) + ((i + 2) % 3 - 1) / 32F);
			}
			d.gridDirty = true; d.ensureInventoryGrid();
			var model = new Model(d, true); model.verifySupported(); model.verifyProjection();
			near(model.dofs, model.faces.faces.size(), 0, "Single-point microboxes add no unresolved internal velocity");
		}
		for (boolean heterogeneous : new boolean[]{false, true}) {
			var d = denseCube();
			if (heterogeneous) for (int i = 0; i < d.particleCount(); i++) d.particles.setMass(i, d.particles.mass(i) * (1 + i / 64.0));
			var model = new Model(d, true); model.verifySupported(); model.verifyProjection();
			near(model.dofs, 36, 0, "Dense microboxes retain every supported curl mode");
			verifyGlobalCirculations(model);
			if (!heterogeneous) model.verifyHydrostatics();
		}
		var line = new Model(sampledCube(2), true); line.verifySupported(); line.verifyProjection(); line.verifyHydrostatics(); verifyGlobalCirculations(line);
		near(line.dofs, 28, 0, "Antipodal two-marker microboxes retain two supported internal rotation modes each");
		var fixture = MarkerVelocitySpaceExperiment.componentFixture(); var separate = new Model(fixture.domain(), true); separate.verifySupported();
		var input = new double[3 * fixture.domain().particleCount()];
		for (int a = 0; a < 3; a++) System.arraycopy(fixture.velocity()[a], 0, input, a * fixture.domain().particleCount(), fixture.domain().particleCount());
		separate.verifyCirculation(input); near(separate.dofs, 6, 0, "Disconnected resolved curl modes remain independent");
		System.out.println("PASS volume-moment support: 1024 microbox layouts, actual supported inertia and wall reaction; dense and disconnected curl modes retained without particle edits");
		var shifted = shiftedSparseCube(); var before = shifted.exportParticleState();
		var masses = new double[shifted.particleCount()]; for (int i = 0; i < masses.length; i++) masses[i] = shifted.particles.mass(i);
		var corrected = new Model(shifted, true); corrected.verifySupported(); corrected.verifyProjection();
		near(corrected.dofs, 12, 0, "Original shifted alias keeps twelve supported face coefficients");
		var after = shifted.exportParticleState();
		if (!Arrays.equals(before.copyPositionVolume(), after.copyPositionVolume()) || !Arrays.equals(before.copyParticleVelocities(), after.copyParticleVelocities())
				|| !Arrays.equals(before.copySolidMask(), after.copySolidMask())) throw new AssertionError("Support adaptation changed raw particle or collision state");
		for (int i = 0; i < masses.length; i++) near(shifted.particles.mass(i), masses[i], 0, "Support adaptation preserves actual carrier mass");
		System.out.println("PASS original shifted alias: full rank twelve, zero internal volume-moment defect, unchanged raw particle state");
		var volumeMoment = new double[3]; double h = shifted.config.cellSize();
		for (int i = 0; i < shifted.particleCount(); i++) {
			var p = position(shifted, i); for (int axis = 0; axis < 3; axis++) volumeMoment[axis] += shifted.particles.volume(i) * h * h * h * p[axis];
		}
		var geometricMoment = LiquidPressureGeometry.capture(shifted).cellSnapshot(0).firstMoment();
		for (int axis = 0; axis < 3; axis++) volumeMoment[axis] -= geometricMoment.component(axis);
		System.out.printf("Shifted source raw-minus-PLIC volume first moment=%s m^4%n", Arrays.toString(volumeMoment));
		corrected.verifyHydrostatics();
	}

	static void validate() {
		MicroFaceVelocitySpaceExperiment.validate();
		verifyCurlGeometry();
		for (boolean heterogeneous : new boolean[]{false, true}) {
			var d = denseCube();
			if (heterogeneous) for (int i = 0; i < d.particleCount(); i++) d.particles.setMass(i, d.particles.mass(i) * (1 + i / 64.0));
			var model = new Model(d); model.verifySupported();
			verifyGlobalCirculations(model);
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
		var alias = new Model(shiftedSparseCube()); int actualRank = rank(alias.sample), jointRank = alias.jointRank();
		System.out.printf("UNSUPPORTED shifted sparse enrichment: 8 markers, %d DOFs, sample rank=%d, joint rank=%d%n", alias.dofs, actualRank, jointRank);
		if (jointRank <= actualRank) throw new AssertionError("Retained sparse enrichment alias no longer reproduces its zero-inertia volume response; reassess the supported representation");
		near(alias.volumeMomentDefect(), .0078125, 0, "Shifted sparse curl quadrature has a retained geometric first-moment defect");
		System.out.println("UNSUPPORTED shifted curl quadrature: local integral velocity is zero; actual volume samples imply 0.0078125 m^4/s for a unit-velocity mode");
	}
	private static FluidDomain shiftedSparseCube() {
		var d = MicroFaceVelocitySpaceExperiment.closedMask(0);
		for (int i = 0; i < d.particleCount(); i++) d.particles.setPositionX(i, d.particles.positionX(i) + 1F / 16);
		d.gridDirty = true; d.ensureInventoryGrid(); return d;
	}

	private static void verifyGlobalCirculations(Model model) {
		var d = model.domain;
		for (int axis = 0; axis < 3; axis++) {
			var input = new double[3 * d.particleCount()];
			for (int i = 0; i < d.particleCount(); i++) {
				var v = curl(position(d, i), axis);
				for (int a = 0; a < 3; a++) input[a * d.particleCount() + i] = v[a];
			}
			model.verifyCirculation(input);
		}
	}
	private static FluidDomain denseCube() { return sampledCube(8); }
	private static FluidDomain sampledCube(int points) {
		var d = MicroFaceVelocitySpaceExperiment.domain(2, 2, 2);
		var positions = new float[32 * points]; var masks = new byte[8]; Arrays.fill(masks, (byte) 255); masks[0] = 0;
		for (int micro = 0; micro < 8; micro++) for (int point = 0; point < points; point++) {
			int marker = points * micro + point;
			for (int axis = 0; axis < 3; axis++) positions[4 * marker + axis] = .5F * (micro >> axis & 1) + .125F + .25F * (points == 2 ? point : point >> axis & 1);
			positions[4 * marker + 3] = 1F / (8 * points);
		}
		d.restoreParticleState(new FlipParticleState(2, 2, 2, 1, 8 * points, 1, positions, new float[24 * points], masks));
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
		final InternalMode[] internalModes;
		final int dofs;
		Model(FluidDomain d) { this(d, false); }
		Model(FluidDomain d, boolean adaptMoments) {
			domain = d; faces = new MicroFaceVelocitySpaceExperiment.Model(d);
			if (!faces.gasRows.isEmpty()) throw new IllegalArgumentException("Enrichment reference currently requires closed liquid components");
			int columns = faces.faces.size() + 3 * faces.pieces.size();
			markerPiece = new int[d.particleCount()];
			var complete = new double[3 * d.particleCount()][columns];
			for (int row = 0; row < complete.length; row++) System.arraycopy(faces.sample[row], 0, complete[row], 0, faces.faces.size());
			for (int marker = 0; marker < d.particleCount(); marker++) {
				var p = position(d, marker); var micro = new int[3]; var local = new double[3];
				for (int a = 0; a < 3; a++) { micro[a] = (int) Math.floor(p[a] / faces.h); local[a] = p[a] / faces.h - micro[a]; }
				int piece = markerPiece[marker] = faces.index.get(faces.pieceKey(micro));
				for (int rotation = 0; rotation < 3; rotation++) {
					var v = curl(local, rotation);
					for (int a = 0; a < 3; a++) complete[a * d.particleCount() + marker][faces.faces.size() + 3 * piece + rotation] = v[a];
				}
			}
			var admitted = adaptMoments ? admitMomentCompatible(complete) : null;
			internalModes = admitted == null ? null : admitted.toArray(InternalMode[]::new);
			dofs = admitted == null ? columns : faces.faces.size() + admitted.size();
			sample = admitted == null ? complete : new double[complete.length][dofs];
			if (admitted != null) for (int row = 0; row < sample.length; row++) {
				System.arraycopy(complete[row], 0, sample[row], 0, faces.faces.size());
				for (int column = 0; column < admitted.size(); column++) sample[row][faces.faces.size() + column] = admitted.get(column).sample()[row];
			}
			mass = new double[dofs][dofs]; divergence = new double[faces.pieces.size()][dofs];
			for (int row = 0; row < divergence.length; row++) System.arraycopy(faces.divergence[row], 0, divergence[row], 0, faces.faces.size());
			for (int row = 0; row < sample.length; row++) {
				double m = d.unitScale.kilograms(d.particles.mass(row % d.particleCount()));
				for (int a = 0; a < dofs; a++) for (int b = 0; b < dofs; b++) mass[a][b] += m * sample[row][a] * sample[row][b];
			}
		}
		/** Preserve the exact zero volume mean of a closed, divergence-free microbox mode before testing its inertia. */
		private ArrayList<InternalMode> admitMomentCompatible(double[][] complete) {
			var admitted = new ArrayList<InternalMode>(); int n = domain.particleCount();
			for (int piece = 0; piece < faces.pieces.size(); piece++) {
				var markers = new ArrayList<Integer>(); for (int marker = 0; marker < n; marker++) if (markerPiece[marker] == piece) markers.add(marker);
				var local = new ExactRational[3 * markers.size()][3]; var mean = new ExactRational[3][3];
				for (var row : mean) Arrays.fill(row, ExactRational.ZERO);
				for (int k = 0; k < markers.size(); k++) for (int axis = 0; axis < 3; axis++) for (int rotation = 0; rotation < 3; rotation++) {
					int marker = markers.get(k);
					var value = local[3 * k + axis][rotation] = exact(complete[axis * n + marker][faces.faces.size() + 3 * piece + rotation]);
					mean[axis][rotation] = mean[axis][rotation].add(value.multiply(ExactRational.ofFloat(domain.particles.volume(marker))));
				}
				var selected = new ArrayList<ExactRational[]>();
				for (var coefficients : nullspace(mean)) {
					var candidate = new ExactRational[local.length];
					for (int row = 0; row < local.length; row++) {
						candidate[row] = ExactRational.ZERO;
						for (int rotation = 0; rotation < 3; rotation++) candidate[row] = candidate[row].add(local[row][rotation].multiply(coefficients[rotation]));
					}
					var support = new ExactRational[local.length][selected.size() + 1];
					for (int row = 0; row < local.length; row++) {
						for (int column = 0; column < selected.size(); column++) support[row][column] = selected.get(column)[row];
						support[row][selected.size()] = candidate[row];
					}
					if (rank(support) == selected.size()) continue;
					selected.add(candidate); var column = new double[complete.length];
					for (int k = 0; k < markers.size(); k++) for (int axis = 0; axis < 3; axis++) column[axis * n + markers.get(k)] = candidate[3 * k + axis].toDouble();
					admitted.add(new InternalMode(piece, coefficients.clone(), column));
				}
			}
			return admitted;
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
			if (internalModes != null) verifyInternalSamples();
		}
		void verifyInternalSamples() {
			int n = domain.particleCount();
			for (int column = 0; column < internalModes.length; column++) for (int marker = 0; marker < n; marker++) {
				var mode = internalModes[column]; var local = position(domain, marker);
				for (int axis = 0; axis < 3; axis++) local[axis] = local[axis] / faces.h - Math.floor(local[axis] / faces.h);
				var value = mode.piece() == markerPiece[marker] ? mode.value(local) : new double[3];
				for (int axis = 0; axis < 3; axis++) near(sample[axis * n + marker][faces.faces.size() + column], value[axis], 0,
						"Retained spatial curl basis reproduces its exact sampled velocity");
			}
		}
		/** Gauss identity: zero divergence and zero box-normal flux imply integral u dV = 0. */
		double volumeMomentDefect() {
			var moments = new double[dofs - faces.faces.size()][3]; double h = domain.config.cellSize();
			for (int marker = 0; marker < domain.particleCount(); marker++) {
				double volume = domain.particles.volume(marker) * h * h * h;
				for (int column = 0; column < moments.length; column++) for (int axis = 0; axis < 3; axis++)
					moments[column][axis] += volume * sample[axis * domain.particleCount() + marker][faces.faces.size() + column];
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
			var velocity = multiply(sample, projected.velocity()); double maxVelocity = 0;
			for (double v : velocity) maxVelocity = Math.max(maxVelocity, Math.abs(v));
			System.out.printf("Hydrostatic quadrature: markers=%d, maximum component speed=%g m/s, gravity/pressure load defect=%g N%n",
					domain.particleCount(), maxVelocity, gravityLoadDefect());
			near(gravityLoadDefect(), 0, 0, "Uniform-density reference requires compatible geometric and actual-mass quadrature");
			for (double v : velocity) near(v, 0, 1e-13, "Uniform liquid remains at rest under gravity");
			for (var entry : faces.index.entrySet()) {
				int key = entry.getKey(), microY = 2 * (key / 8 / domain.sizeX % domain.sizeY) + (key % 8 >> 1 & 1);
				near(projected.pressure()[entry.getValue()], -1000 * faces.h * microY, 1e-9, "Dense hydrostatic pressure differences follow actual density and height");
			}
		}
		double gravityLoadDefect() {
			var load = new double[dofs]; int n = domain.particleCount();
			for (int marker = 0; marker < n; marker++) for (int column = 0; column < dofs; column++)
				load[column] -= domain.unitScale.kilograms(domain.particles.mass(marker)) * sample[n + marker][column];
			for (var entry : faces.index.entrySet()) {
				int key = entry.getKey(), y = 2 * (key / 8 / domain.sizeX % domain.sizeY) + (key % 8 >> 1 & 1);
				for (int column = 0; column < dofs; column++) load[column] -= 1000 * faces.h * y * divergence[entry.getValue()][column];
			}
			double defect = 0; for (double value : load) defect = Math.max(defect, Math.abs(value)); return defect;
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
