package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.SolidVolume;

/** Physical invariants at the production MAC/parcel and pressure/parcel interfaces. */
public final class GranularCarrierValidation {
	private GranularCarrierValidation() { }

	public static void validate() {
		for (boolean massWeighted : new boolean[]{false, true}) macExchange(massWeighted);
		finitePressureResponse();
		hydrostaticParcelForce();
		sharedGasPressureAlgebra();
	}

	private static FluidDomain domain() {
		var domain = new FluidDomain(6, 6, 6,
				new FluidSolverConfig(0.5F, 1_000F, 0, 0.05F, 400, 1.0e-5F, 0.45F, 0.002F, 0.8F), 4096);
		domain.bindGranular(BuiltinGranularDefinitions.createRegistry(),
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		return domain;
	}

	private static void addParcel(FluidDomain domain, float x, float y, float z, float vx, float vy, float vz) {
		int sand = domain.granularState.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = volume / (double) SolidVolume.SVQ_PER_BV
				* domain.granularState.registry.material(sand).referenceDensity();
		domain.granularState.parcels.add(new GranularParcelStore.Parcel(x, y, z, vx, vy, vz,
				volume, mass, sand, 0, 0, (byte) 0));
	}

	private static void macExchange(boolean massWeighted) {
		var domain = domain();
		domain.setVolume(2, 2, 2, 0.001F);
		if (massWeighted) for (int p = 0; p < domain.particles.count(); p++) {
			domain.particles.setMass(p, domain.particles.mass(p) * 13.6);
		}
		for (int p = 0; p < 32; p++) addParcel(domain,
				1.26F + 0.005F * (p % 4), 1.28F + 0.005F * (p % 3), 1.29F,
				1, -0.5F, 0.25F);
		domain.ensureGrid();
		require(domain.variableDensityActive == massWeighted, "both P2G mass conventions are exercised");
		double beforeEnergy = totalMacEnergy(domain);
		double[] beforeMomentum = momentum(domain);
		var stats = domain.granularMechanicalStage.stepVelocities(0.05F);
		double afterEnergy = totalMacEnergy(domain);
		double[] afterMomentum = momentum(domain);
		for (int axis = 0; axis < 3; axis++) require(Math.abs(afterMomentum[axis] - beforeMomentum[axis]) < 2.0e-5,
				"production MAC drag lost momentum on axis " + axis + ": " + (afterMomentum[axis] - beforeMomentum[axis]));
		require(afterEnergy < beforeEnergy - 1.0e-5, "production MAC drag must dissipate relative kinetic energy");
		require(Math.abs(stats.fluidImpulseX()) > 1.0e-5, "thin liquid must receive the drag reaction");
		require(Math.abs(stats.boundaryImpulseX()) + Math.abs(stats.boundaryImpulseY())
				+ Math.abs(stats.boundaryImpulseZ()) < 2.0e-5, "an interior exchange cannot assign physical impulse to the wall");
	}

	private static double totalMacEnergy(FluidDomain domain) {
		return domain.gridKineticEnergyJoules() + solidEnergy(domain);
	}

	private static double solidEnergy(FluidDomain domain) {
		double energy = 0;
		for (int p = 0; p < domain.granularState.parcels.count(); p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			energy += 0.5 * parcel.mass() * (parcel.velocityX() * (double) parcel.velocityX()
					+ parcel.velocityY() * (double) parcel.velocityY() + parcel.velocityZ() * (double) parcel.velocityZ());
		}
		return energy;
	}

	private static double[] momentum(FluidDomain domain) {
		double[] result = new double[3];
		float[][] velocities = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		float[][] weights = {domain.mac.p2gWeightX, domain.mac.p2gWeightY, domain.mac.p2gWeightZ};
		double massScale = domain.unitScale.kilograms(domain.variableDensityActive ? 1 : domain.config.density());
		for (int axis = 0; axis < 3; axis++) for (int face = 0; face < velocities[axis].length; face++) {
			result[axis] += massScale * weights[axis][face] * velocities[axis][face];
		}
		for (int p = 0; p < domain.granularState.parcels.count(); p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			result[0] += parcel.mass() * parcel.velocityX();
			result[1] += parcel.mass() * parcel.velocityY();
			result[2] += parcel.mass() * parcel.velocityZ();
		}
		return result;
	}

	private static void finitePressureResponse() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, 1);
		for (int p = 0; p < 60; p++) addParcel(domain, 1.75F, 1.75F, 1.75F, 0.25F, -1, 0.5F);
		domain.ensureGrid();
		double before = pressureEnergy(domain) + solidEnergy(domain);
		double solidBefore = solidEnergy(domain);
		domain.pressureStage.solve(0.05F);
		domain.pressureStage.projectVelocities(0.05F);
		double liquidAfter = pressureEnergy(domain), solidAfter = solidEnergy(domain);
		require(liquidAfter > 1.0e-3 && solidAfter < solidBefore - 1.0e-3,
				"finite parcel pressure must exchange work with the liquid");
		require(liquidAfter + solidAfter <= before * (1 + 2.0e-6),
				"coupled pressure injected kinetic energy: before=" + before + ", after=" + (liquidAfter + solidAfter));
		// Check the independent finite-volume continuity equation after both velocities were projected.
		var coupling = new GranularPressureCoupling(domain);
		java.util.Arrays.fill(domain.projection.rhs, 0);
		coupling.prepare(0.05F);
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int cell = domain.projection.fluidUnknownCells[ordinal];
			int x = cell % 6, y = cell / 6 % 6, z = cell / 36;
			double div = (domain.pressureXFaceOpen(x + 1, y, z) * domain.mac.velocityX[domain.xFaceIndex(x + 1, y, z)]
					- domain.pressureXFaceOpen(x, y, z) * domain.mac.velocityX[domain.xFaceIndex(x, y, z)]
					+ domain.pressureYFaceOpen(x, y + 1, z) * domain.mac.velocityY[domain.yFaceIndex(x, y + 1, z)]
					- domain.pressureYFaceOpen(x, y, z) * domain.mac.velocityY[domain.yFaceIndex(x, y, z)]
					+ domain.pressureZFaceOpen(x, y, z + 1) * domain.mac.velocityZ[domain.zFaceIndex(x, y, z + 1)]
					- domain.pressureZFaceOpen(x, y, z) * domain.mac.velocityZ[domain.zFaceIndex(x, y, z)]) / domain.config.cellSize();
			double solidRate = domain.projection.rhs[cell] * 0.05 / domain.config.density();
			// Float pressure/velocity writes and the parcel reduction can cancel to almost zero.
			// Bound roundoff at the fixture's original 1 m/s velocity scale, not the cancelled flux.
			double tolerance = 2 * domain.granularState.parcels.count() * Math.ulp(1.0F) / domain.config.cellSize();
			require(Math.abs(div - solidRate) < tolerance, "liquid and solid velocities disagree on volume flux: " + (div - solidRate));
		}
	}

	private static void hydrostaticParcelForce() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, 1);
		addParcel(domain, 1.43F, 1.62F, 1.39F, 0, 0, 0);
		domain.ensureGrid();
		domain.pressureStage.solve(0.05F);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int y = cell / 6 % 6;
			domain.projection.pressure[cell] = 1_000F * 9.81F * (3 - (y + 0.5F) * 0.5F);
		}
		var coupling = new GranularPressureCoupling(domain);
		coupling.prepare(0.05F);
		coupling.project(0.05F);
		var p = domain.granularState.parcels.parcel(0);
		double expected = 0.05 * 9.81 * 1_000 * p.solidVolumeSvq() / SolidVolume.SVQ_PER_BV / p.mass();
		require(Math.abs(p.velocityY() - expected) < 2.0e-7 && Math.abs(p.velocityX()) < 1.0e-7
				&& Math.abs(p.velocityZ()) < 1.0e-7, "pressure gradient must give Archimedes buoyancy without a second buoyancy impulse");
	}

	private static void sharedGasPressureAlgebra() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, 1);
		addParcel(domain, 1.43F, 1.5F, 1.39F, 0, 0, 0);
		domain.sealDomainBoundary();
		domain.bootstrapGasState(null);
		domain.ensureGrid();
		domain.pressureStage.solve(0.05F);
		require(domain.projection.gasUnknownCount == 1, "mixed parcel support must reach the sealed gas unknown");
		var coupling = new GranularPressureCoupling(domain);
		coupling.prepare(0.05F);
		coupling.rebuildDiagonal();
		int cells = domain.cellCount, node = domain.projection.gasUnknownNodes[0];
		float[] a = new float[cells], b = new float[cells], ag = new float[cells + 1], bg = new float[cells + 1];
		float[] aa = new float[cells], ab = new float[cells], aag = new float[cells + 1], abg = new float[cells + 1];
		java.util.Arrays.fill(a, 1); ag[node] = 1;
		coupling.apply(a, ag, aa, aag);
		for (float value : aa) require(Math.abs(value) < 1.0e-12, "uniform liquid/gas pressure must exert no parcel force");
		require(Math.abs(aag[node]) < 1.0e-12, "uniform pressure must preserve gas volume");
		java.util.Arrays.fill(a, 0); java.util.Arrays.fill(aa, 0); java.util.Arrays.fill(aag, 0);
		coupling.apply(a, ag, aa, aag);
		require(coupling.gasDiagonal(node) > 0 && aag[node] == coupling.gasDiagonal(node),
				"gas diagonal must square the merged support gradient");
		for (int cell = 0; cell < cells; cell++) { a[cell] = (cell % 7) - 3; b[cell] = (cell % 11) - 5; }
		bg[node] = -2;
		java.util.Arrays.fill(aa, 0); java.util.Arrays.fill(aag, 0);
		coupling.apply(a, ag, aa, aag); coupling.apply(b, bg, ab, abg);
		double aAb = ag[node] * abg[node], bAa = bg[node] * aag[node], aAa = ag[node] * aag[node];
		for (int cell = 0; cell < cells; cell++) {
			aAb += a[cell] * (double) ab[cell]; bAa += b[cell] * (double) aa[cell]; aAa += a[cell] * (double) aa[cell];
		}
		require(Math.abs(aAb - bAa) < 1.0e-6 && aAa >= 0,
				"mixed liquid/gas parcel pressure block must be symmetric positive semidefinite");
	}

	/** Kinetic metric of the finite-volume pressure operator (including pore aperture). */
	private static double pressureEnergy(FluidDomain domain) {
		double energy = 0, h = domain.config.cellSize();
		for (int axis = 0; axis < 3; axis++) for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
			int nx = x + (axis == 0 ? 1 : 0), ny = y + (axis == 1 ? 1 : 0), nz = z + (axis == 2 ? 1 : 0);
			if (nx >= 6 || ny >= 6 || nz >= 6) continue;
			int first = domain.cellIndex(x, y, z), second = domain.cellIndex(nx, ny, nz);
			float open = axis == 0 ? domain.pressureXFaceOpen(nx, y, z) : axis == 1
					? domain.pressureYFaceOpen(x, ny, z) : domain.pressureZFaceOpen(x, y, nz);
			double velocity = axis == 0 ? domain.mac.velocityX[domain.xFaceIndex(nx, y, z)] : axis == 1
					? domain.mac.velocityY[domain.yFaceIndex(x, ny, z)] : domain.mac.velocityZ[domain.zFaceIndex(x, y, nz)];
			float mobility = domain.pressureFaceCoupling(first, second, nx - x, ny - y, nz - z).mobilityScale();
			energy += 0.5 * domain.config.density() * h * h * h * open / mobility * velocity * velocity;
		}
		return energy;
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
