package com.example.fluid;

import com.example.fluid.air.AirNetwork;

/**
 * M5 active-solver gas-liquid coupling gate: sealed-cavity compression, negative-gauge suction,
 * orifice equalization through geometric edges and exact gas/liquid ledgers. Pure water with
 * zero surface tension, so any deviation from M4 behaviour comes only from sealed gas pressure.
 */
public final class M5GasLiquidCouplingValidation {
	private static final float DENSITY = 1_000.0F;
	private static final float GRAVITY = 9.81F;

	public static void main(String[] args) {
		validateOpenTubesStayAmbient();
		validateSealedCompression();
		validateNegativeGaugeSuction();
		validateOrificeEqualization();
		System.out.println("M5 gas-liquid coupling gate passed: sealed compression, negative-gauge suction, "
				+ "finite-rate orifice equalization and exact gas/liquid ledgers");
	}

	private static void validateOpenTubesStayAmbient() {
		FlipFluidDomain domain = utubeDomain(false);
		domain.step();
		check(!domain.gasPressureActive(), "open-topped tube pair must not create sealed gas");
		check(domain.airNetwork().nodeCount() == 0, "open-topped tube pair must keep zero sealed nodes");
		checkLedger(domain.airNetwork(), "open tubes");
	}

	private static void validateSealedCompression() {
		FlipFluidDomain domain = utubeDomain(true);
		domain.step();
		long initialGasNanos = domain.airNetwork().standardVolumeNanos(1);
		// Quasi-static compression: top the right column up slowly so no air is entrained below the surfaces.
		float addedTotal = 0.0F;
		double previousPressure = 0.0;
		for (int fill = 0; fill < 48; fill++) {
			addedTotal += domain.addVolumeNear(9, 6, 1, 2.0F, 12);
			for (int settle = 0; settle < 150; settle++) {
				FlipFluidDomain.StepStats stats = domain.step();
				check(stats.relativeVolumeError() <= 1.0E-7F, "quasi-static fill changed exact marker volume");
				check(Float.isFinite(stats.pressureResidual()), "quasi-static fill residual became non-finite");
				checkLedger(domain.airNetwork(), "quasi-static fill step " + fill + "/" + settle);
			}
			double pressure = domain.airNetwork().gaugePressure(1);
			check(pressure >= previousPressure - 250.0,
					"gas pressure must rise monotonically under quasi-static compression at fill " + fill);
			previousPressure = pressure;
		}
		check(Math.abs(addedTotal - 96.0F) <= 1.0E-3F, "quasi-static fill must add the requested water, got " + addedTotal);
		double exactVolume = domain.totalVolume();
		double exactMass = domain.totalMass();
		double pressureSum = 0.0;
		double headSum = 0.0;
		int averageSamples = 0;
		for (int steps = 0; steps < 3_000; steps++) {
			FlipFluidDomain.StepStats stats = domain.step();
			check(stats.relativeVolumeError() <= 1.0E-7F, "sealed compression changed exact marker volume");
			check(stats.relativeMassError() <= 1.0E-12, "sealed compression changed exact marker mass");
			check(Float.isFinite(stats.pressureResidual()), "sealed compression pressure residual became non-finite");
			check(domain.maximumParticleSpeed() <= 100.0F, "sealed compression agitation diverged");
			checkLedger(domain.airNetwork(), "sealed compression step " + steps);
			if (steps >= 1_500 && steps % 16 == 0) {
				pressureSum += domain.airNetwork().gaugePressure(1);
				headSum += DENSITY * GRAVITY * (columnSurface(domain, 7, 10, 8) - columnSurface(domain, 1, 5, 10));
				averageSamples++;
			}
		}
		double gasPressure = pressureSum / averageSamples;
		double measuredHead = headSum / averageSamples;
		double error = Math.abs(gasPressure - measuredHead) / Math.abs(measuredHead);
		System.out.printf("  sealed compression averaged over %d samples: pGas=%.0f Pa measuredHead=%.0f Pa "
				+ "error=%.2f%% maxSpeed=%.3g%n",
				averageSamples, gasPressure, measuredHead, error * 100.0, domain.maximumParticleSpeed());
		check(error <= 0.08, "sealed compression pressure must balance the liquid head within 8%, got " + error);
		check(domain.maximumParticleSpeed() <= 0.5F, "sealed compression sloshing did not stay bounded");
		check(domain.airNetwork().standardVolumeNanos(1) == initialGasNanos,
				"sealed gas inventory must stay exactly constant without orifice edges");
		check(domain.totalVolume() == exactVolume && domain.totalMass() == exactMass,
				"sealed compression changed physical ledgers");
		check(domain.airNetwork().nodeCount() == 1, "sealed compression must keep exactly one cavity");
	}

	private static void validateNegativeGaugeSuction() {
		FlipFluidDomain domain = utubeDomain(true);
		domain.step();
		double initialAvailable = domain.airNetwork().availableVolume(1);
		domain.airNetwork().setStandardVolume(1, 0.75 * initialAvailable);
		long presetNanos = domain.airNetwork().standardVolumeNanos(1);
		double exactVolume = domain.totalVolume();
		double pressureSum = 0.0;
		double headSum = 0.0;
		int averageSamples = 0;
		int steps = 0;
		for (; steps < 8_000; steps++) {
			FlipFluidDomain.StepStats stats = domain.step();
			check(stats.relativeVolumeError() <= 1.0E-7F, "negative-gauge suction changed exact marker volume");
			check(Float.isFinite(stats.pressureResidual()), "negative-gauge residual became non-finite");
			check(domain.maximumParticleSpeed() <= 100.0F, "negative-gauge agitation diverged");
			checkLedger(domain.airNetwork(), "negative-gauge step " + steps);
			if (steps >= 4_000 && steps % 16 == 0) {
				pressureSum += domain.airNetwork().gaugePressure(1);
				headSum += DENSITY * GRAVITY * (columnSurface(domain, 7, 10, 8) - columnSurface(domain, 1, 5, 10));
				averageSamples++;
			}
		}
		double gasPressure = pressureSum / averageSamples;
		double measuredHead = headSum / averageSamples;
		double error = Math.abs(gasPressure - measuredHead) / Math.abs(measuredHead);
		System.out.printf("  negative-gauge suction averaged over %d samples: pGas=%.0f Pa measuredHead=%.0f Pa "
				+ "error=%.2f%% maxSpeed=%.3g%n",
				averageSamples, gasPressure, measuredHead, error * 100.0, domain.maximumParticleSpeed());
		check(error <= 0.10, "negative-gauge pressure must balance the liquid head within 10%, got " + error);
		check(domain.airNetwork().standardVolumeNanos(1) == presetNanos,
				"preset gas inventory must stay exactly constant");
		check(domain.totalVolume() == exactVolume, "negative-gauge suction changed exact marker volume");
	}

	private static void validateOrificeEqualization() {
		FlipFluidDomain domain = chamberPairDomain();
		domain.step();
		AirNetwork network = domain.airNetwork();
		check(network.nodeCount() == 3, "dry chamber pair must expose two chambers and the sliver pocket");
		check(network.edgeCount() == 2, "the gap must chain both chambers through the sliver pocket, got "
				+ network.edgeCount());
		double leftAvailable = network.availableVolume(1);
		double rightAvailable = network.availableVolume(3);
		network.setStandardVolume(1, 2.0 * leftAvailable);
		network.setStandardVolume(3, rightAvailable);
		double initialDelta = Math.abs(network.gaugePressure(1) - network.gaugePressure(3));
		check(initialDelta > 0.9 * AirNetwork.REFERENCE_PRESSURE, "pressurized chamber must start with a large delta");
		double previousDelta = initialDelta;
		int steps = 0;
		double delta = initialDelta;
		while (delta > 0.01 * initialDelta && steps < 20_000) {
			domain.step();
			steps++;
			delta = Math.abs(network.gaugePressure(1) - network.gaugePressure(3));
			check(Double.isFinite(delta) && delta <= previousDelta * 1.001,
					"orifice chain equalization must decay monotonically, step " + steps);
			checkLedger(network, "orifice chain step " + steps);
			if (steps == 5) check(delta > 0.2 * initialDelta, "orifice chain must not equalize instantly");
			previousDelta = Math.min(previousDelta, delta);
		}
		System.out.printf("  orifice chain equalized to %.2f%% of initial delta in %d steps%n",
				100.0 * delta / initialDelta, steps);
		check(delta <= 0.01 * initialDelta, "orifice chain did not equalize, residual " + delta);
	}

	/** Both chambers share one water body through a bottom opening; the left top can be sealed. */
	private static FlipFluidDomain utubeDomain(boolean sealLeft) {
		int sizeX = 12, sizeY = 24, sizeZ = 4;
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, DENSITY, GRAVITY, 1.0F / 20.0F, 400, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F);
		FlipFluidDomain domain = new FlipFluidDomain(sizeX, sizeY, sizeZ, config, sizeX * sizeY * sizeZ * 8);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			boolean shell = x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1 || y == 0;
			boolean divider = x == 6 && y >= 5;
			boolean roof = sealLeft && y == sizeY - 1 && x <= 5;
			if (shell || divider || roof) domain.setSolid(x, y, z, true);
		}
		for (int z = 1; z <= 2; z++) for (int y = 1; y <= 8; y++) {
			for (int x = 1; x <= 5; x++) domain.setVolume(x, y, z, 1.0F);
			for (int x = 7; x <= 10; x++) domain.setVolume(x, y, z, 1.0F);
		}
		// the bottom passage under the divider must be water-filled, otherwise it traps a sealed air lock
		for (int z = 1; z <= 2; z++) for (int y = 1; y <= 4; y++) domain.setVolume(6, y, z, 1.0F);
		return domain;
	}

	/** Two dry sealed chambers separated by a solid wall whose one cell keeps a quarter-open gap. */
	private static FlipFluidDomain chamberPairDomain() {
		int n = 8;
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, DENSITY, 0.0F, 1.0F / 20.0F, 100, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F);
		FlipFluidDomain domain = new FlipFluidDomain(n, n, n, config, n * n * n * 8);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0 || y == n - 1;
			boolean divider = x == 4 && y >= 1 && y < n - 1 && z >= 1 && z < n - 1;
			if (shell || divider) domain.setSolid(x, y, z, true);
		}
		// one wall cell keeps a single open quadrant on each side: a through-gap of two quarter-open faces
		domain.setSolidMicroMask(4, 1, 1, 0xDE);
		return domain;
	}

	/** Mean water surface height in cells over a column range from the smoothed cell volumes. */
	private static double columnSurface(FlipFluidDomain domain, int fromX, int toX, int crossSection) {
		double volume = 0.0;
		for (int y = 0; y < domain.sizeY(); y++) for (int z = 0; z < domain.sizeZ(); z++) {
			for (int x = fromX; x <= toX; x++) volume += domain.volume(x, y, z);
		}
		check(volume > 0.0, "column " + fromX + ".." + toX + " has no water");
		return volume / crossSection;
	}

	private static void checkLedger(AirNetwork network, String context) {
		long sealed = network.sealedInventoryNanos();
		if (sealed + network.ventedNanos() != network.drawnFromAmbientNanos()) {
			throw new AssertionError("gas ledger identity broken after " + context + ": sealed=" + sealed
					+ " vented=" + network.ventedNanos() + " drawn=" + network.drawnFromAmbientNanos());
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
