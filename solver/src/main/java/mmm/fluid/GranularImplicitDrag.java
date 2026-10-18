package mmm.fluid;

import mmm.granular.GranularMechanics;
import mmm.granular.GranularParcelStore;
import mmm.granular.SolidVolume;

import java.util.Arrays;
import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.interfaces.linsol.LinearSolverSparse;
import org.ejml.ops.DConvertMatrixStruct;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.CommonOps_DSCC;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

/**
 * First-order semi-implicit drag: source slip fixes the constitutive resistance, while finite-mass
 * velocities and pressure share three sparse positive inertia factors for the whole substep.
 */
final class GranularImplicitDrag implements PressureLinearSolver.Operator {
	private final FluidDomain domain;
	private final GranularPressureCoupling pressure;
	private PressureLinearSolver solver;
	private final int[] offsets = new int[3];
	private final int faceCount;
	private AxisFactor[] factors;
	private int[] faceOrdinal = new int[0];
	private boolean[] touchedFaces = new boolean[0];
	private int size, contacts;
	private double dt;
	private double[] mass = new double[0], input = new double[0], delta = new double[0];
	private double[] initial = new double[0], forced = new double[0];
	private boolean[] active = new boolean[0];
	private double[] stokes = new double[0], reynoldsScale = new double[0], dragValue = new double[0];
	private double[] residual = new double[0];
	private int[] indices = new int[0], particle = new int[0], counts = new int[0];
	private double[] weights = new double[0], strength = new double[0], particleResponse = new double[0];

	GranularImplicitDrag(FluidDomain domain, GranularPressureCoupling pressure) {
		this.domain = domain;
		this.pressure = pressure;
		offsets[1] = domain.mac.velocityX.length;
		offsets[2] = offsets[1] + domain.mac.velocityY.length;
		faceCount = offsets[2] + domain.mac.velocityZ.length;
	}

	void prepare(float step) {
		dt = step;
		int parcels = domain.granularState == null ? 0 : domain.granularState.parcels.count();
		contacts = 0;
		if (parcels == 0) return;
		if (faceOrdinal.length == 0) { faceOrdinal = new int[faceCount]; touchedFaces = new boolean[faceCount]; }
		size = faceCount + 3 * parcels;
		if (mass.length < size) {
			mass = new double[size]; input = new double[size]; delta = new double[size];
			initial = new double[size]; forced = new double[size];
			active = new boolean[parcels]; stokes = new double[parcels]; reynoldsScale = new double[parcels]; dragValue = new double[parcels];
			residual = new double[size];
			particle = new int[3 * parcels]; counts = new int[3 * parcels]; strength = new double[3 * parcels];
			particleResponse = new double[3 * parcels];
			indices = new int[24 * parcels]; weights = new double[24 * parcels];
		}
		Arrays.fill(active, false);
		Arrays.fill(stokes, 0);
		Arrays.fill(mass, 0);
		prepareFaces();
		for (int index = 0; index < parcels; index++) {
			var p = domain.granularState.parcels.parcel(index);
			for (int axis = 0; axis < 3; axis++) mass[faceCount + 3 * index + axis] = p.mass();
			var fluid = domain.granularMechanicalStage.sample(p.x(), p.y(), p.z());
			if (!fluid.hasLiquid()) continue;
			double diameter = domain.granularState.registry.material(p.materialHandle()).primaryGrainDiameterMeters();
			double sx = p.velocityX() - fluid.velocityX(), sy = p.velocityY() - fluid.velocityY();
			double sz = p.velocityZ() - fluid.velocityZ(), slip = Math.sqrt(sx*sx + sy*sy + sz*sz);
			reynoldsScale[index] = fluid.density() * diameter / fluid.dynamicViscosity();
			double grains = p.solidVolumeSvq() / (double) SolidVolume.SVQ_PER_CUBIC_METER
					/ (Math.PI * diameter * diameter * diameter / 6) * fluid.submergedFraction();
			stokes[index] = 3 * Math.PI * fluid.dynamicViscosity() * diameter * grains;
			dragValue[index] = resistance(index, slip);
			for (int axis = 0; axis < 3; axis++) prepareContact(index, p, axis, dragValue[index]);
		}
		if (contacts == 0) return;
		if (factors == null) factors = new AxisFactor[] {new AxisFactor(), new AxisFactor(), new AxisFactor()};
		if (solver == null) solver = new PressureLinearSolver(domain, this);
		solver.reset();
		Arrays.fill(touchedFaces, false);
		for (int contact = 0; contact < contacts; contact++) {
			for (int slot = contact * 8; slot < contact * 8 + counts[contact]; slot++) touchedFaces[indices[slot]] = true;
		}
		for (int axis = 0; axis < 3; axis++) {
			int start = offsets[axis], end = axis == 2 ? faceCount : offsets[axis + 1], count = 0;
			for (int face = start; face < end; face++) if (touchedFaces[face]) count++;
			factors[axis].reshape(count);
			for (int face = start, ordinal = 0; face < end; face++) if (touchedFaces[face]) {
				factors[axis].faces[ordinal] = face; faceOrdinal[face] = ordinal++;
			}
		}
		readVelocities(initial);
		System.arraycopy(initial, 0, forced, 0, size);
		pressure.addBoundaryVelocity(forced, faceCount, dt);
		rebuildDiagonal();
	}

	private double resistance(int parcel, double slip) {
		double re = reynoldsScale[parcel] * slip;
		return stokes[parcel] * GranularMechanics.dragResistanceFactor(re);
	}

	/** Eliminating each parcel leaves independent, positive face systems along the three axes. */
	private void rebuildDiagonal() {
		for (AxisFactor factor : factors) {
			int count = factor.faces.length;
			factor.entries.reshape(count, count);
			for (int row = 0; row < count; row++) factor.entries.addItem(row, row, mass[factor.faces[row]]);
		}
		for (int contact = 0; contact < contacts; contact++) {
			AxisFactor factor = factors[(particle[contact] - faceCount) % 3];
			int base = contact * 8, end = base + counts[contact];
			for (int slot = base; slot < end; slot++) for (int other = base; other < end; other++)
				factor.entries.addItem(faceOrdinal[indices[slot]], faceOrdinal[indices[other]],
						strength[contact] * weights[slot] * weights[other]);
		}
		for (AxisFactor factor : factors) factor.factor();
	}

	private static final class AxisFactor {
		private final LinearSolverSparse<DMatrixSparseCSC, DMatrixRMaj> solver = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE);
		private final DMatrixSparseTriplet entries = new DMatrixSparseTriplet();
		private DMatrixSparseCSC matrix = new DMatrixSparseCSC(0, 0);
		private final DMatrixRMaj rhs = new DMatrixRMaj(0, 1), solution = new DMatrixRMaj(0, 1);
		private int[] faces = new int[0];

		void reshape(int count) {
			solver.setStructureLocked(false);
			if (faces.length != count) faces = new int[count];
			rhs.reshape(count, 1); solution.reshape(count, 1);
		}

		void factor() {
			if (faces.length == 0) return;
			matrix = DConvertMatrixStruct.convert(entries, matrix);
			CommonOps_DSCC.duplicatesAdd(matrix, null);
			if (!solver.setA(matrix)) throw new FluidDomain.PressureSolveBlockedException("Drag inertia Cholesky factor lost positive definiteness");
			solver.setStructureLocked(true);
		}

		void apply(double[] source, double[] destination) {
			if (faces.length == 0) return;
			for (int i = 0; i < faces.length; i++) rhs.data[i] = source[faces[i]];
			solver.solve(rhs, solution);
			for (int i = 0; i < faces.length; i++) destination[faces[i]] = solution.data[i];
		}
	}

	private void prepareFaces() {
		var faces = domain.pressureSystem.faces;
		for (int face = 0; face < faceCount; face++) if (faces.inverseMass[face] > 0) mass[face] = faces.faceMass(face);
	}

	private void prepareContact(int index, GranularParcelStore.Parcel p, int axis, double drag) {
		double h = domain.config.cellSize();
		double x = p.x() / h - (axis == 0 ? 0 : 0.5), y = p.y() / h - (axis == 1 ? 0 : 0.5);
		double z = p.z() / h - (axis == 2 ? 0 : 0.5);
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
		int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
		int nz = domain.sizeZ + (axis == 2 ? 1 : 0), count = 0, base = contacts * 8;
		double[] p2g = axis == 0 ? domain.mac.p2gWeightX : axis == 1 ? domain.mac.p2gWeightY : domain.mac.p2gWeightZ;
		double sum = 0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int cx = x0 + ox, cy = y0 + oy, cz = z0 + oz;
			if (cx < 0 || cx >= nx || cy < 0 || cy >= ny || cz < 0 || cz >= nz) continue;
			int local = (cz * ny + cy) * nx + cx, face = offsets[axis] + local;
			if (!(mass[face] > 0 && p2g[local] > 0) || !domain.granularCarrierFaceActive(axis, cx, cy, cz)) continue;
			double weight = (ox == 0 ? 1 - x + x0 : x - x0) * (oy == 0 ? 1 - y + y0 : y - y0)
					* (oz == 0 ? 1 - z + z0 : z - z0) * p2g[local];
			if (!(weight > 0)) continue;
			indices[base + count] = face; weights[base + count] = weight;
			sum += weight; count++;
		}
		if (count == 0) return;
		active[index] = true;
		int parcel = faceCount + 3 * index + axis;
		for (int slot = base; slot < base + count; slot++) {
			weights[slot] /= sum;
		}
		particle[contacts] = parcel; counts[contacts] = count;
		particleResponse[contacts] = dt * drag / (mass[parcel] + dt * drag);
		strength[contacts] = mass[parcel] * particleResponse[contacts];
		contacts++;
	}

	/** Eliminates each parcel velocity exactly, then solves the finite face-mass Schur system. */
	private void responseDelta() {
		Arrays.fill(delta, 0); Arrays.fill(residual, 0);
		for (int contact = 0; contact < contacts; contact++) {
			int base = contact * 8, end = base + counts[contact];
			double slip = input[particle[contact]];
			for (int slot = base; slot < end; slot++) slip -= weights[slot] * input[indices[slot]];
			for (int slot = base; slot < end; slot++) residual[indices[slot]] += strength[contact] * weights[slot] * slip;
		}
		for (AxisFactor factor : factors) factor.apply(residual, delta);
		for (int contact = 0; contact < contacts; contact++) {
			int base = contact * 8, end = base + counts[contact], parcel = particle[contact];
			double slip = -input[parcel];
			for (int slot = base; slot < end; slot++) slip += weights[slot] * (input[indices[slot]] + delta[indices[slot]]);
			delta[parcel] = particleResponse[contact] * slip;
		}
	}

	boolean active() { return contacts > 0; }

	double parcelInertiaRatio(int parcel) {
		if (contacts == 0 || !active[parcel]) return 1;
		double parcelMass = mass[faceCount + 3 * parcel];
		return parcelMass / (parcelMass + dt * dragValue[parcel]);
	}
	PressureProjectionResult solvePressure(double target) { return solver.solve(pressure, target); }
	double[] pressure() { return solver.pressure(); }

	@Override public void buildRhs(double[] result) {
		Arrays.fill(result, 0);
		// Assemble storage and advected momentum in one uniform pressure gauge.
		System.arraycopy(forced, 0, input, 0, size);
		responseDelta();
		for (int index = 0; index < size; index++) delta[index] += input[index];
		addDivergence(delta, result, -domain.config.density() / dt);
		domain.pressureSystem.addStorageRhs(result, dt);
	}

	@Override public void applyMatrix(double[] source, double[] result) {
		Arrays.fill(result, 0);
		pressureVelocity(source);
		responseDelta();
		for (int index = 0; index < size; index++) delta[index] += input[index];
		addDivergence(delta, result, domain.config.density());
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal], row = domain.cellCount + node;
			result[row] += domain.projection.gasStorageCoefficient[node] * source[row];
		}
	}

	/** Writes the final joint velocity once, without a large intermediate pressure kick. */
	void project() {
		pressureVelocity(solver.pressure());
		for (int index = 0; index < size; index++) input[index] = forced[index] + dt * input[index];
		responseDelta();
		for (int index = 0; index < size; index++) delta[index] += input[index];
		writeVelocities(delta, true);
		pressure.recordBoundary((float)dt,solver.pressure());
	}

	private void pressureVelocity(double[] source) {
		Arrays.fill(input, 0);
		domain.pressureSystem.faces.pressureVelocity(source, input);
		pressure.addPressureVelocity(input, faceCount, source);
	}
	private void addDivergence(double[] vector, double[] result, double scale) {
		domain.pressureSystem.faces.addDivergence(vector, result, scale);
		pressure.addVelocityDivergence(vector, faceCount, result, scale);
	}

	private void readVelocities(double[] vector) {
		float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int axis = 0; axis < 3; axis++) for (int face = 0; face < fields[axis].length; face++) {
			vector[offsets[axis] + face] = fields[axis][face];
		}
		for (int index = 0; index < domain.granularState.parcels.count(); index++) {
			var p = domain.granularState.parcels.parcel(index);
			vector[faceCount + 3 * index] = p.velocityX();
			vector[faceCount + 3 * index + 1] = p.velocityY();
			vector[faceCount + 3 * index + 2] = p.velocityZ();
		}
	}

	private void writeVelocities(double[] vector, boolean accountRounding) {
		double[] rounding = new double[3];
		float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int axis = 0; axis < 3; axis++) for (int face = 0; face < fields[axis].length; face++) {
			double velocity = vector[offsets[axis] + face];
			fields[axis][face] = (float) velocity;
			if (accountRounding) rounding[axis] += mass[offsets[axis] + face] * (velocity - fields[axis][face]);
		}
		for (int index = 0; index < domain.granularState.parcels.count(); index++) {
			var p = domain.granularState.parcels.parcel(index);
			double vx = vector[faceCount + 3 * index];
			double vy = vector[faceCount + 3 * index + 1];
			double vz = vector[faceCount + 3 * index + 2];
			domain.granularState.parcels.overwrite(index, new GranularParcelStore.Parcel(p.x(), p.y(), p.z(),
					(float) vx, (float) vy, (float) vz, p.solidVolumeSvq(), p.massUnits(), p.materialHandle(),
					p.solidCompositionHandle(), p.contactAge(), p.flags()));
			if (accountRounding) {
				rounding[0] += p.mass() * (vx - (float) vx);
				rounding[1] += p.mass() * (vy - (float) vy);
				rounding[2] += p.mass() * (vz - (float) vz);
			}
		}
		domain.lastGranularBoundaryImpulseX += rounding[0];
		domain.lastGranularBoundaryImpulseY += rounding[1];
		domain.lastGranularBoundaryImpulseZ += rounding[2];
	}

}
