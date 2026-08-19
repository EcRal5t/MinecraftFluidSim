package com.example.fluid;

import java.util.Arrays;
import java.util.List;

/** M3 test-side P2G gate for simultaneous marker volume, mass and momentum transfer. */
final class M3MaterialTransferValidation {
	private static final int SIZE = 4;
	private static final double CELL_SIZE = 1.0;

	private M3MaterialTransferValidation() { }

	static Summary validate() {
		FluidMaterialRegistry registry = registry();
		validateRegistry(registry);
		validateActiveDomainP2G(registry);
		validateActivePhaseAggregation(registry);
		validateActiveVariableDensityPressure(registry);

		Markers equalDensity = markers(registry, false);
		Transfer equalTransfer = transfer(equalDensity);
		validateTransferLedger(equalDensity, equalTransfer);
		check(Arrays.equals(equalTransfer.volumeWeightedVelocity(), equalTransfer.massWeightedVelocity()),
				"equal-density material labels changed P2G face velocity");

		Markers variableDensity = markers(registry, true);
		Transfer variableTransfer = transfer(variableDensity);
		validateTransferLedger(variableDensity, variableTransfer);
		check(!Arrays.equals(variableTransfer.volumeWeightedVelocity(), variableTransfer.massWeightedVelocity()),
				"variable-density P2G did not switch from volume weighting to momentum/mass weighting");
		return new Summary(variableDensity.totalVolume(), variableDensity.totalMass(),
				variableDensity.momentum(0), maximumDifference(
						variableTransfer.volumeWeightedVelocity(), variableTransfer.massWeightedVelocity()));
	}

	private static void validateActiveDomainP2G(FluidMaterialRegistry registry) {
		FlipFluidDomain pure = activeDomainWithShear();
		FlipFluidDomain equal = activeDomainWithShear();
		FlipFluidDomain variable = activeDomainWithShear();
		int a = registry.handle("realwater:material_a");
		int b = registry.handle("realwater:material_b");
		int c = registry.handle("realwater:material_c");
		equal.setMaterialInCell(1, 1, 1, registry, a);
		equal.setMaterialInCell(2, 1, 1, registry, b);
		variable.setMaterialInCell(1, 1, 1, registry, a);
		variable.setMaterialInCell(2, 1, 1, registry, c);
		check(!pure.usesMassWeightedP2G() && !equal.usesMassWeightedP2G(),
				"pure or equal-density active domain left the strict volume-weighted path");
		check(variable.usesMassWeightedP2G(), "variable-density active domain did not enable mass-weighted P2G");
		float pureVelocity = pure.sampleVelocity(2.0F, 1.5F, 1.5F).x();
		float equalVelocity = equal.sampleVelocity(2.0F, 1.5F, 1.5F).x();
		float variableVelocity = variable.sampleVelocity(2.0F, 1.5F, 1.5F).x();
		check(Float.floatToRawIntBits(pureVelocity) == Float.floatToRawIntBits(equalVelocity),
				"equal-density material labels changed active P2G velocity");
		check(Math.abs(variableVelocity - pureVelocity) > 1.0E-4F,
				"active variable-density P2G did not change the shared face velocity");
		checkClose(variable.totalMass(), 1_800.0, "active marker mass ledger");
		expectIllegalState(variable::exportParticleState);
	}

	private static FlipFluidDomain activeDomainWithShear() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 0.0F, 1.0F / 20.0F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.80F
		);
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config, 64);
		domain.setVolume(1, 1, 1, 1.0F);
		domain.setVolume(2, 1, 1, 1.0F);
		FlipParticleState initial = domain.exportParticleState();
		float[] positionVolume = initial.copyPositionVolume();
		float[] velocity = initial.copyParticleVelocities();
		for (int particle = 0; particle < initial.particleCount(); particle++) {
			velocity[particle * 3] = positionVolume[particle * 4] >= 2.0F ? 1.0F : 0.0F;
		}
		domain.restoreParticleState(new FlipParticleState(
				domain.sizeX(), domain.sizeY(), domain.sizeZ(), config.cellSize(), initial.particleCount(),
				initial.totalVolume(), positionVolume, velocity, initial.copySolidMask()
		));
		return domain;
	}

	private static void validateActivePhaseAggregation(FluidMaterialRegistry registry) {
		int a = registry.handle("realwater:material_a");
		int b = registry.handle("realwater:material_b");
		int c = registry.handle("realwater:material_c");
		int d = registry.handle("realwater:material_d");
		FlipFluidDomain miscible = singleCellDomain();
		for (int particle = 0; particle < miscible.particleCount(); particle++) {
			miscible.setMarkerMaterial(particle, registry, (particle & 1) == 0 ? a : d);
		}
		check(miscible.phaseCountInCell(1, 1, 1) == 1,
				"miscible materials did not aggregate into one local liquid phase");
		checkClose(miscible.materialInventory(a).mass(), 500.0, "miscible material A mass");
		checkClose(miscible.materialInventory(d).mass(), 525.0, "miscible material D mass");
		FlipFluidDomain.StepStats miscibleStats = miscible.step();
		check(miscible.phaseCountInCell(1, 1, 1) == 1,
				"miscible phase split during an idle active step");
		check(miscibleStats.relativeVolumeError() <= 1.0E-7F && miscibleStats.relativeMassError() <= 1.0E-12,
				"miscible active step changed physical inventories");

		FlipFluidDomain junction = singleCellDomain();
		int[] material = {a, b, c, a, b, c, a, b};
		for (int particle = 0; particle < material.length; particle++) {
			junction.setMarkerMaterial(particle, registry, material[particle]);
		}
		check(junction.phaseCountInCell(1, 1, 1) == 3,
				"three-phase junction was truncated to the old two-phase cache");
		FlipFluidDomain.StepStats junctionStats = junction.step();
		check(junction.phaseCountInCell(1, 1, 1) == 3,
				"three-phase junction was lost during pressure/reseeding");
		checkClose(junction.totalMass(), 950.0, "three-phase marker mass");
		check(junctionStats.relativeVolumeError() <= 1.0E-7F && junctionStats.relativeMassError() <= 1.0E-12,
				"three-phase active step changed physical inventories");

		FluidMaterialRegistry wrongDefault = new FluidMaterialRegistry(List.of(registry.material(c)));
		expectIllegal(() -> singleCellDomain().setMarkerMaterial(0, wrongDefault, 0));
	}

	private static FlipFluidDomain singleCellDomain() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 0.0F, 1.0F / 20.0F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.80F
		);
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config, 64);
		domain.setVolume(1, 1, 1, 1.0F);
		return domain;
	}

	private static FluidMaterialRegistry registry() {
		return new FluidMaterialRegistry(List.of(
				new FluidMaterial("realwater:material_a", 1_000.0, "realwater:aqueous"),
				new FluidMaterial("realwater:material_b", 1_000.0, "realwater:immiscible_b"),
				new FluidMaterial("realwater:material_c", 800.0, "realwater:immiscible_c"),
				new FluidMaterial("realwater:material_d", 1_050.0, "realwater:aqueous"),
				new FluidMaterial("realwater:material_e", 13_600.0, "realwater:immiscible_e")
		));
	}

	private static void validateRegistry(FluidMaterialRegistry registry) {
		check(registry.size() == 5, "material registry size changed");
		int a = registry.handle("realwater:material_a");
		int b = registry.handle("realwater:material_b");
		int c = registry.handle("realwater:material_c");
		int d = registry.handle("realwater:material_d");
		int e = registry.handle("realwater:material_e");
		check(a == 0 && b == 1 && c == 2 && d == 3 && e == 4,
				"runtime handles are not declaration-order dense");
		check(registry.areMiscible(a, d), "same miscibility group was rejected");
		check(!registry.areMiscible(a, b) && !registry.areMiscible(a, c), "unlike miscibility groups were merged");
		check(registry.phaseHandle(a) == registry.phaseHandle(d), "miscible materials did not share a phase handle");
		check(registry.phaseHandle(a) != registry.phaseHandle(b), "immiscible materials shared a phase handle");
		expectIllegal(() -> new FluidMaterial("Bad Id", 1_000.0, "realwater:aqueous"));
		expectIllegal(() -> new FluidMaterial("realwater:bad", 0.0, "realwater:aqueous"));
		expectIllegal(() -> new FluidMaterialRegistry(List.of(
				registry.material(a), registry.material(a))));
		expectIllegal(() -> registry.material(-1));
		expectIllegal(() -> registry.handle("realwater:missing"));
	}

	private static void validateActiveVariableDensityPressure(FluidMaterialRegistry registry) {
		validateActiveHydrostaticColumn(registry, "water-oil-1.25:1",
				registry.handle("realwater:material_a"), registry.handle("realwater:material_c"));
		validateActiveHydrostaticColumn(registry, "mercury-water-13.6:1",
				registry.handle("realwater:material_e"), registry.handle("realwater:material_a"));
	}

	private static void validateActiveHydrostaticColumn(FluidMaterialRegistry registry, String name,
	                                                    int heavyHandle, int lightHandle) {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				200, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F
		);
		FlipFluidDomain domain = new FlipFluidDomain(4, 7, 4, config, 128);
		for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) {
			for (int x = 0; x < domain.sizeX(); x++) domain.setSolid(x, y, z, true);
		}
		for (int y = 1; y <= 5; y++) domain.setSolid(1, y, 1, false);
		for (int y = 1; y <= 4; y++) domain.setVolume(1, y, 1, 1.0F);
		for (int y = 1; y <= 2; y++) domain.setMaterialInCell(1, y, 1, registry, heavyHandle);
		for (int y = 3; y <= 4; y++) domain.setMaterialInCell(1, y, 1, registry, lightHandle);

		double heavyDensity = registry.material(heavyHandle).referenceDensity();
		double lightDensity = registry.material(lightHandle).referenceDensity();
		FlipFluidDomain.StepStats stats = domain.step();
		double[] expected = {
				config.gravity() * (1.5 * lightDensity + 0.5 * (heavyDensity + lightDensity) + heavyDensity),
				config.gravity() * (1.5 * lightDensity + 0.5 * (heavyDensity + lightDensity)),
				config.gravity() * 1.5 * lightDensity,
				config.gravity() * 0.5 * lightDensity
		};
		double maximumExpected = expected[0], maximumError = 0.0;
		double[] actual = new double[4];
		for (int y = 1; y <= 4; y++) {
			actual[y - 1] = domain.pressure(1, y, 1);
			maximumError = Math.max(maximumError, Math.abs(actual[y - 1] - expected[y - 1]));
		}
		double pressureError = maximumError / maximumExpected;
		check(pressureError <= 2.0E-5, name + " active pressure missed analytic hydrostatics: " + pressureError
				+ ", actual=" + Arrays.toString(actual) + ", expected=" + Arrays.toString(expected));
		check(stats.pressureIterations() > 0 && stats.pressureIterations() <= 32,
				name + " active PCG iteration count escaped the column bound");
		check(stats.pressureResidual() <= 1.0E-3F, name + " active PCG residual is too large");
		check(domain.maximumParticleSpeed() <= 2.0E-4F, name + " active projection left parasitic marker velocity");
		check(stats.relativeVolumeError() <= 1.0E-7F && stats.relativeMassError() <= 1.0E-12,
				name + " active step changed physical inventories");
		System.out.printf("  active %-18s pressureErr=%.3g PCG=%d residual=%.3g maxSpeed=%.3g%n",
				name, pressureError, stats.pressureIterations(), stats.pressureResidual(), domain.maximumParticleSpeed());
	}

	private static Markers markers(FluidMaterialRegistry registry, boolean variableDensity) {
		double[] x = {1.25, 1.75, 2.25, 2.75};
		double[] y = {1.25, 2.25, 1.75, 2.75};
		double[] z = {1.75, 1.25, 2.75, 2.25};
		double[] volume = {0.125, 0.250, 0.125, 0.250};
		double[] velocityX = {0.5, -1.0, 1.5, -0.5};
		double[] velocityY = {-0.5, 0.25, 1.0, -1.5};
		double[] velocityZ = {1.0, -0.25, 0.5, -1.0};
		int[] material = {
				registry.handle("realwater:material_a"), registry.handle("realwater:material_b"),
				registry.handle(variableDensity ? "realwater:material_c" : "realwater:material_a"),
				registry.handle(variableDensity ? "realwater:material_c" : "realwater:material_b")
		};
		int[] phase = {
				registry.phaseHandle(material[0]), registry.phaseHandle(material[1]),
				registry.phaseHandle(material[2]), registry.phaseHandle(material[3])
		};
		double[] mass = new double[volume.length];
		for (int marker = 0; marker < mass.length; marker++) {
			mass[marker] = volume[marker] * registry.material(material[marker]).referenceDensity();
		}
		return new Markers(x, y, z, volume, mass, velocityX, velocityY, velocityZ, material, phase);
	}

	private static Transfer transfer(Markers markers) {
		int totalFaces = (SIZE + 1) * SIZE * SIZE * 3;
		double[] faceVolume = new double[totalFaces];
		double[] faceMass = new double[totalFaces];
		double[] volumeMomentum = new double[totalFaces];
		double[] massMomentum = new double[totalFaces];
		int offset = 0;
		for (int axis = 0; axis < 3; axis++) {
			int nx = axis == 0 ? SIZE + 1 : SIZE;
			int ny = axis == 1 ? SIZE + 1 : SIZE;
			int nz = axis == 2 ? SIZE + 1 : SIZE;
			for (int marker = 0; marker < markers.size(); marker++) {
				double gx = markers.x()[marker] / CELL_SIZE - (axis == 0 ? 0.0 : 0.5);
				double gy = markers.y()[marker] / CELL_SIZE - (axis == 1 ? 0.0 : 0.5);
				double gz = markers.z()[marker] / CELL_SIZE - (axis == 2 ? 0.0 : 0.5);
				int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
				double fx = gx - x0, fy = gy - y0, fz = gz - z0;
				double velocity = markers.velocity(axis)[marker];
				for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
					int x = x0 + ox, y = y0 + oy, z = z0 + oz;
					if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) {
						throw new IllegalArgumentException("Transfer marker touched the grid boundary");
					}
					double weight = trilinearWeight(ox, oy, oz, fx, fy, fz);
					int face = offset + x + nx * (y + ny * z);
					faceVolume[face] += weight * markers.volume()[marker];
					faceMass[face] += weight * markers.mass()[marker];
					volumeMomentum[face] += weight * markers.volume()[marker] * velocity;
					massMomentum[face] += weight * markers.mass()[marker] * velocity;
				}
			}
			offset += nx * ny * nz;
		}
		double[] volumeVelocity = new double[totalFaces];
		double[] massVelocity = new double[totalFaces];
		for (int face = 0; face < totalFaces; face++) {
			if (faceVolume[face] > 0.0) volumeVelocity[face] = volumeMomentum[face] / faceVolume[face];
			if (faceMass[face] > 0.0) massVelocity[face] = massMomentum[face] / faceMass[face];
		}
		return new Transfer(faceVolume, faceMass, volumeMomentum, massMomentum, volumeVelocity, massVelocity);
	}

	private static void validateTransferLedger(Markers markers, Transfer transfer) {
		int axisFaces = (SIZE + 1) * SIZE * SIZE;
		for (int axis = 0; axis < 3; axis++) {
			int from = axis * axisFaces, to = from + axisFaces;
			checkClose(sum(transfer.faceVolume(), from, to), markers.totalVolume(), "P2G volume ledger");
			checkClose(sum(transfer.faceMass(), from, to), markers.totalMass(), "P2G mass ledger");
			checkClose(sum(transfer.massMomentum(), from, to), markers.momentum(axis), "P2G momentum ledger");
		}
	}

	private static double trilinearWeight(int ox, int oy, int oz, double fx, double fy, double fz) {
		return (ox == 0 ? 1.0 - fx : fx) * (oy == 0 ? 1.0 - fy : fy) * (oz == 0 ? 1.0 - fz : fz);
	}

	private static double sum(double[] values, int from, int to) {
		double result = 0.0;
		for (int i = from; i < to; i++) result += values[i];
		return result;
	}

	private static double maximumDifference(double[] left, double[] right) {
		double result = 0.0;
		for (int i = 0; i < left.length; i++) result = Math.max(result, Math.abs(left[i] - right[i]));
		return result;
	}

	private static void checkClose(double actual, double expected, String message) {
		double tolerance = 1.0E-12 * Math.max(1.0, Math.abs(expected));
		check(Math.abs(actual - expected) <= tolerance, message + ": expected=" + expected + ", actual=" + actual);
	}

	private static void expectIllegal(Runnable action) {
		try {
			action.run();
			throw new AssertionError("Expected invalid material state to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static void expectIllegalState(Runnable action) {
		try {
			action.run();
			throw new AssertionError("Expected unsupported multi-material handoff to fail closed");
		} catch (IllegalStateException expected) {
			// Expected.
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	record Summary(double volume, double mass, double momentumX, double maximumWeightingDifference) { }

	private record Transfer(double[] faceVolume, double[] faceMass,
	                        double[] volumeMomentum, double[] massMomentum,
	                        double[] volumeWeightedVelocity, double[] massWeightedVelocity) { }

	private record Markers(double[] x, double[] y, double[] z, double[] volume, double[] mass,
	                       double[] velocityX, double[] velocityY, double[] velocityZ,
	                       int[] materialHandle, int[] phaseLabel) {
		int size() { return x.length; }
		double[] velocity(int axis) { return axis == 0 ? velocityX : axis == 1 ? velocityY : velocityZ; }
		double totalVolume() { return sum(volume, 0, volume.length); }
		double totalMass() { return sum(mass, 0, mass.length); }
		double momentum(int axis) {
			double result = 0.0;
			double[] velocity = velocity(axis);
			for (int marker = 0; marker < size(); marker++) result += mass[marker] * velocity[marker];
			return result;
		}
	}
}
