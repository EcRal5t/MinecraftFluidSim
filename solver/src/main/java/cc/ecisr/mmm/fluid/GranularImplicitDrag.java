package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.SolidVolume;

import java.util.Arrays;

/** Symmetric finite-mass drag resolvent used by both pressure assembly and velocity projection. */
final class GranularImplicitDrag {
	private final FluidDomain domain;
	private final GranularPressureCoupling pressure;
	private GranularCoupledPressureSolver solver;
	private final int[] offsets = new int[3];
	private final int faceCount;
	private int[] coupledFaces = new int[0];
	private int[] faceOrdinal = new int[0], neighborOffset = new int[0], corners = new int[0];
	private double[] faceResistance = new double[0];
	private boolean[] touchedFaces = new boolean[0];
	private int coupledFaceCount;
	private int size, contacts;
	private double dt;
	private double lastError;
	private double[] mass = new double[0], input = new double[0], delta = new double[0];
	private double[] initial = new double[0], forced = new double[0], endVelocity = new double[0];
	private boolean[] active = new boolean[0];
	private double[] stokes = new double[0], reynoldsScale = new double[0], dragValue = new double[0];
	private double[] diagonal = new double[0], residual = new double[0], direction = new double[0], product = new double[0];
	private int[] firstRow = new int[0], secondRow = new int[0];
	private double[] divergence = new double[0], boundaryVelocity = new double[0];
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
		if (firstRow.length == 0) {
			coupledFaces = new int[faceCount]; touchedFaces = new boolean[faceCount];
			faceOrdinal = new int[faceCount]; neighborOffset = new int[81];
			for (int axis = 0; axis < 3; axis++) {
				int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
				for (int z = -1; z <= 1; z++) for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
					neighborOffset[axis * 27 + (z + 1) * 9 + (y + 1) * 3 + x + 1] = (z * ny + y) * nx + x;
				}
			}
			firstRow = new int[faceCount]; secondRow = new int[faceCount];
			divergence = new double[faceCount]; boundaryVelocity = new double[faceCount];
		}
		size = faceCount + 3 * parcels;
		if (mass.length < size) {
			mass = new double[size]; input = new double[size]; delta = new double[size];
			initial = new double[size]; forced = new double[size]; endVelocity = new double[size];
			active = new boolean[parcels]; stokes = new double[parcels]; reynoldsScale = new double[parcels]; dragValue = new double[parcels];
			diagonal = new double[size]; residual = new double[size]; direction = new double[size]; product = new double[size];
			particle = new int[3 * parcels]; counts = new int[3 * parcels]; strength = new double[3 * parcels];
			particleResponse = new double[3 * parcels];
			indices = new int[24 * parcels]; weights = new double[24 * parcels];
			corners = new int[24 * parcels];
		}
		Arrays.fill(active, false);
		Arrays.fill(stokes, 0);
		Arrays.fill(mass, 0); Arrays.fill(boundaryVelocity, 0); Arrays.fill(divergence, 0);
		Arrays.fill(firstRow, Integer.MAX_VALUE); Arrays.fill(secondRow, Integer.MAX_VALUE);
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
			double grains = p.solidVolumeSvq() / (double) SolidVolume.SVQ_PER_BV
					/ (Math.PI * diameter * diameter * diameter / 6) * fluid.submergedFraction();
			stokes[index] = 3 * Math.PI * fluid.dynamicViscosity() * diameter * grains;
			dragValue[index] = resistance(index, slip);
			for (int axis = 0; axis < 3; axis++) prepareContact(index, p, axis, dragValue[index]);
		}
		if (contacts == 0) return;
		if (solver == null) solver = new GranularCoupledPressureSolver(domain, this);
		solver.reset();
		Arrays.fill(touchedFaces, false);
		for (int contact = 0; contact < contacts; contact++) {
			for (int slot = contact * 8; slot < contact * 8 + counts[contact]; slot++) touchedFaces[indices[slot]] = true;
		}
		coupledFaceCount = 0;
		for (int face = 0; face < faceCount; face++) if (touchedFaces[face]) coupledFaces[coupledFaceCount++] = face;
		for (int ordinal = 0; ordinal < coupledFaceCount; ordinal++) faceOrdinal[coupledFaces[ordinal]] = ordinal;
		if (faceResistance.length < coupledFaceCount * 27) faceResistance = new double[coupledFaceCount * 27];
		readVelocities(initial);
		System.arraycopy(initial, 0, forced, 0, size);
		for (int face = 0; face < faceCount; face++) forced[face] += boundaryVelocity[face];
		pressure.addBoundaryVelocity(forced, faceCount, dt);
		rebuildDiagonal();
	}

	private double resistance(int parcel, double slip) {
		double re = reynoldsScale[parcel] * slip;
		return stokes[parcel] * GranularMechanics.dragResistanceFactor(re);
	}

	/** Picard iteration uses the final relative velocity in the nonlinear resistance law. */
	boolean updateResistance() {
		if (contacts == 0) return false;
		pressureVelocity(solver.pressure());
		for (int index = 0; index < size; index++) input[index] = forced[index] + dt * input[index];
		responseDelta();
		for (int index = 0; index < size; index++) endVelocity[index] = input[index] + delta[index];
		Arrays.fill(product, 0);
		for (int contact = 0; contact < contacts; contact++) {
			double slip = endVelocity[particle[contact]];
			for (int slot = contact * 8; slot < contact * 8 + counts[contact]; slot++) {
				slip -= weights[slot] * endVelocity[indices[slot]];
			}
			product[(particle[contact] - faceCount) / 3] += slip * slip;
		}
		Arrays.fill(residual, 0);
		System.arraycopy(mass, 0, direction, 0, size);
		for (int contact = 0; contact < contacts; contact++) {
			int parcel = particle[contact], index = (parcel - faceCount) / 3;
			double next = resistance(index, Math.sqrt(product[index]));
			double slip = endVelocity[parcel];
			for (int slot = contact * 8; slot < contact * 8 + counts[contact]; slot++) {
				slip -= weights[slot] * endVelocity[indices[slot]];
			}
			double forceResidual = dt * (next - dragValue[index]) * slip;
			residual[parcel] += forceResidual;
			direction[parcel] += dt * next;
			for (int slot = contact * 8; slot < contact * 8 + counts[contact]; slot++) {
				residual[indices[slot]] -= weights[slot] * forceResidual;
				direction[indices[slot]] += dt * next * weights[slot] * weights[slot];
			}
		}
		double error = 0;
		for (int index = 0; index < size; index++) if (direction[index] > 0) {
			double velocityScale = Math.max(1, Math.max(Math.abs(initial[index]), Math.abs(endVelocity[index])));
			double tolerance = 32 * Math.ulp((float) velocityScale);
			error = Math.max(error, Math.abs(residual[index]) / direction[index] / tolerance);
		}
		lastError = error;
		if (error <= 1) return false;
		for (int index = 0; index < (size - faceCount) / 3; index++) if (active[index]) {
			product[index] = 0.5 * (resistance(index, Math.sqrt(product[index])) + dragValue[index]);
		}
		for (int contact = 0; contact < contacts; contact++) {
			int index = (particle[contact] - faceCount) / 3;
			dragValue[index] = product[index];
			double impulseRate = dt * dragValue[index], parcelMass = mass[particle[contact]];
			particleResponse[contact] = impulseRate / (parcelMass + impulseRate);
			strength[contact] = parcelMass * particleResponse[contact];
		}
		rebuildDiagonal();
		return true;
	}

	private void rebuildDiagonal() {
		System.arraycopy(mass, 0, diagonal, 0, size);
		Arrays.fill(faceResistance, 0);
		for (int contact = 0; contact < contacts; contact++) {
			int base = contact * 8, end = base + counts[contact];
			for (int slot = base; slot < end; slot++) for (int other = base; other < end; other++) {
				int entry = 27 * faceOrdinal[indices[slot]] + corners[other] - corners[slot] + 13;
				faceResistance[entry] += strength[contact] * (weights[slot] * weights[other]);
			}
		}
		for (int ordinal = 0; ordinal < coupledFaceCount; ordinal++) {
			diagonal[coupledFaces[ordinal]] += faceResistance[ordinal * 27 + 13];
		}
	}

	private void prepareFaces() {
		double h = domain.config.cellSize(), cellVolume = h*h*h;
		for (int axis = 0; axis < 3; axis++) {
			int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
			int nz = domain.sizeZ + (axis == 2 ? 1 : 0);
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				if (axis == 0 && (x == 0 || x == domain.sizeX)
						|| axis == 1 && (y == 0 || y == domain.sizeY)
						|| axis == 2 && (z == 0 || z == domain.sizeZ)) continue;
				int a = domain.cellIndex(x - (axis == 0 ? 1 : 0), y - (axis == 1 ? 1 : 0), z - (axis == 2 ? 1 : 0));
				int b = domain.cellIndex(x, y, z);
				if (domain.geometry.solid[a] || domain.geometry.solid[b]) continue;
				float open = domain.pressureOpenFaceFromCell(a, b, axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
				if (!(open > 0)) continue;
				int local = (z * ny + y) * nx + x, face = offsets[axis] + local;
				if (!domain.isFluid(a) && !domain.isFluid(b)) {
					float[] p2g = axis == 0 ? domain.mac.p2gWeightX : axis == 1 ? domain.mac.p2gWeightY : domain.mac.p2gWeightZ;
					if (domain.carrierFaceActive(a, b, open)) mass[face] = domain.unitScale.kilograms(
							p2g[local] * (domain.variableDensityActive ? 1.0 : domain.config.density()));
					continue;
				}
				var metric = domain.pressureFaceCoupling(a, b, axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
				mass[face] = cellVolume * domain.config.density() * open / metric.mobilityScale();
				divergence[face] = open / h;
				firstRow[face] = row(a); secondRow[face] = row(b);
				boundaryVelocity[face] = dt * metric.mobilityScale() / (domain.config.density() * h)
						* (boundaryPressure(a) - boundaryPressure(b) - metric.pressureJumpFirstMinusSecond());
			}
		}
	}

	private void prepareContact(int index, GranularParcelStore.Parcel p, int axis, double drag) {
		double h = domain.config.cellSize();
		double x = p.x() / h - (axis == 0 ? 0 : 0.5), y = p.y() / h - (axis == 1 ? 0 : 0.5);
		double z = p.z() / h - (axis == 2 ? 0 : 0.5);
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
		int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
		int nz = domain.sizeZ + (axis == 2 ? 1 : 0), count = 0, base = contacts * 8;
		float[] p2g = axis == 0 ? domain.mac.p2gWeightX : axis == 1 ? domain.mac.p2gWeightY : domain.mac.p2gWeightZ;
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
			corners[base + count] = (oz * 3 + oy) * 3 + ox;
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
		double rz = 0;
		for (int ordinal = 0; ordinal < coupledFaceCount; ordinal++) {
			int index = coupledFaces[ordinal];
			direction[index] = diagonal[index] > 0 ? residual[index] / diagonal[index] : 0;
			rz += residual[index] * direction[index];
		}
		double target = rz * 1.0e-28;
		if (rz != 0) solveFaces(rz, target);
		for (int contact = 0; contact < contacts; contact++) {
			int base = contact * 8, end = base + counts[contact], parcel = particle[contact];
			double slip = -input[parcel];
			for (int slot = base; slot < end; slot++) slip += weights[slot] * (input[indices[slot]] + delta[indices[slot]]);
			delta[parcel] = particleResponse[contact] * slip;
		}
	}

	private void solveFaces(double rz, double target) {
		for (int iteration = 0; iteration < faceCount; iteration++) {
			for (int ordinal = 0; ordinal < coupledFaceCount; ordinal++) {
				int index = coupledFaces[ordinal];
				product[index] = mass[index] * direction[index];
			}
			addResistance(direction, product);
			double denominator = 0;
			for (int ordinal = 0; ordinal < coupledFaceCount; ordinal++) {
				int index = coupledFaces[ordinal];
				denominator += direction[index] * product[index];
			}
			if (!(denominator > 0 && Double.isFinite(denominator))) {
				throw new FluidDomain.PressureSolveBlockedException("Coupled drag inertia matrix lost positive definiteness");
			}
			double alpha = rz / denominator, nextRz = 0;
			for (int ordinal = 0; ordinal < coupledFaceCount; ordinal++) {
				int index = coupledFaces[ordinal];
				delta[index] += alpha * direction[index];
				residual[index] -= alpha * product[index];
				if (diagonal[index] > 0) nextRz += residual[index] * residual[index] / diagonal[index];
			}
			if (nextRz <= target) return;
			double beta = nextRz / rz;
			for (int ordinal = 0; ordinal < coupledFaceCount; ordinal++) {
				int index = coupledFaces[ordinal];
				direction[index] = residual[index] / diagonal[index] + beta * direction[index];
			}
			rz = nextRz;
		}
		throw new FluidDomain.PressureSolveBlockedException("Coupled drag inertia solve did not converge");
	}

	private void addResistance(double[] source, double[] target) {
		// Eight-face contacts only connect the 27 neighboring faces of the same axis.
		// Assemble their shared coefficients once per nonlinear iterate, not once per PCG product.
		for (int ordinal = 0; ordinal < coupledFaceCount; ordinal++) {
			int face = coupledFaces[ordinal], axis = face < offsets[1] ? 0 : face < offsets[2] ? 1 : 2;
			for (int slot = 0; slot < 27; slot++) {
				double value = faceResistance[ordinal * 27 + slot];
				if (value != 0) target[face] += value * source[face + neighborOffset[axis * 27 + slot]];
			}
		}
	}

	boolean active() { return contacts > 0; }
	double parcelInertiaRatio(int parcel) {
		if (contacts == 0 || !active[parcel]) return 1;
		double parcelMass = mass[faceCount + 3 * parcel];
		return parcelMass / (parcelMass + dt * dragValue[parcel]);
	}
	double nonlinearResidual() { return lastError; }
	PressureProjectionResult solvePressure(double target) { return solver.solve(pressure, target); }

	void buildRhs(double[] result) {
		Arrays.fill(result, 0);
		// Assemble storage and advected momentum in one uniform pressure gauge.
		System.arraycopy(forced, 0, input, 0, size);
		responseDelta();
		for (int index = 0; index < size; index++) delta[index] += input[index];
		addDivergence(delta, result, -domain.config.density() / dt);
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			result[domain.cellCount + node] += domain.projection.gasStorageCoefficient[node]
					* (domain.pressureBoundaryView.gasPressureOfNode(node) - domain.pressureReference);
		}
	}

	void applyMatrix(double[] source, double[] result) {
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
	}

	private void pressureVelocity(double[] source) {
		Arrays.fill(input, 0);
		double h = domain.config.cellSize(), cellVolume = h * h * h;
		for (int face = 0; face < faceCount; face++) if (mass[face] > 0) {
			input[face] = cellVolume * divergence[face]
					* (value(firstRow[face], source) - value(secondRow[face], source)) / mass[face];
		}
		pressure.addPressureVelocity(input, faceCount, source);
	}

	private void addDivergence(double[] vector, double[] result, double scale) {
		for (int face = 0; face < faceCount; face++) {
			double flux = scale * divergence[face] * vector[face];
			add(firstRow[face], result, flux);
			add(secondRow[face], result, -flux);
		}
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
					(float) vx, (float) vy, (float) vz, p.solidVolumeSvq(), p.mass(), p.materialHandle(),
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

	private int row(int cell) {
		if (domain.isFluid(cell)) return cell == domain.pressureAnchor ? Integer.MAX_VALUE : cell;
		int node = domain.gasNodeOfAirCell(cell);
		return node > 0 && domain.projection.gasPressureUnknown[node] ? -node : Integer.MAX_VALUE;
	}
	private double boundaryPressure(int cell) {
		return row(cell) == Integer.MAX_VALUE && domain.isAir(cell)
				? domain.gasPressureOfAirCell(cell) - domain.pressureReference : 0;
	}
	private double value(int row, double[] source) {
		return row == Integer.MAX_VALUE ? 0 : source[row >= 0 ? row : domain.cellCount - row];
	}
	private void add(int row, double[] target, double value) {
		if (row != Integer.MAX_VALUE) target[row >= 0 ? row : domain.cellCount - row] += value;
	}
}
