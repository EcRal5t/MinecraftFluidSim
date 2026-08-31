package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.thermal.ThermalTransport;

import java.util.Arrays;

/** Replays controller injection through the default live step and the complete authoritative handoff. */

abstract class ProductionReplayValidationSupport {
	static final int REPLAY_STEPS = 20;
	static final int WARMUP_STEPS = 20;
	static final double STEP_BUDGET_MILLIS = 40.0;

	static FluidDomain createPouredControllerDomain() {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(
				domain, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		FluidDomain.VolumeAddition addition = domain.addVolumeNearBudgeted(3, 0, 0, 128.0F, 6);
		require(addition.addedVolume() == 128.0F && !addition.markerBudgetLimited(),
				"fixture did not inject the complete 16 BV source: " + addition);
		return domain;
	}

	static FluidDomain createFullyBoundHalfFilledDomain(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = createEmptyFullyBoundController(registries);
		int[][] sources = {{3, 0, 0}, {4, 7, 7}};
		for (int pour = 0; pour < sources.length; pour++) {
			int[] source = sources[pour];
			FluidDomain.VolumeAddition addition = domain.addVolumeNearBudgeted(
					source[0], source[1], source[2], 128.0F, 6);
			require(addition.addedVolume() == 128.0F && !addition.markerBudgetLimited(),
					"full-capability fixture did not inject pour " + (pour + 1) + ": " + addition);
		}
		return domain;
	}

	static FluidDomain createEmptyFullyBoundController(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	static FluidDomain createFullyBoundPlanarHalfFill(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		for (int y = 0; y < 4; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	static FluidDomain createFullyBoundCompressedPlanarHalfFill(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		for (int y = 0; y < 4; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		return domain;
	}

	/** Maximum-fill solver-side shape and capability profile created by {@code /mmm demo} in empty space. */
	static FluidDomain createFullyBoundDamBreakDemo(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(24, 16, 24, productionConfig(), 73_728);
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		for (int y = 0; y < 12; y++) for (int z = 0; z < 24; z++) for (int x = 0; x < 9; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		require(domain.particleCount() == 20_736 && domain.totalVolumeExact() == 2_592.0,
				"dam-break fixture does not match the production demo inventory");
		return domain;
	}

	/** Production {@code /mmm demo} over four full collision layers, matching the world fixture. */
	static FluidDomain createFullyBoundFloorDamBreakDemo(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(24, 16, 24, productionConfig(), 73_728);
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		for (int y = 0; y < 4; y++) for (int z = 0; z < 24; z++) for (int x = 0; x < 24; x++) {
			domain.setSolid(x, y, z, true);
		}
		for (int y = 0; y < 12; y++) for (int z = 0; z < 24; z++) for (int x = 0; x < 9; x++) {
			if (!domain.isSolid(x, y, z)) domain.setVolume(x, y, z, 1.0F);
		}
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		require(domain.particleCount() == 13_824 && domain.totalVolumeExact() == 1_728.0,
				"floor dam-break fixture does not match the production demo inventory");
		return domain;
	}

	static double kineticEnergy(FlipMaterialParticleState state) {
		double[] mass = state.copyParticleMass();
		float[] velocity = state.copyParticleVelocities();
		double result = 0.0;
		for (int marker = 0; marker < mass.length; marker++) {
			int base = marker * 3;
			result += 0.5 * mass[marker] * (velocity[base] * velocity[base]
					+ velocity[base + 1] * velocity[base + 1]
					+ velocity[base + 2] * velocity[base + 2]);
		}
		return result * Math.pow(state.cellSize(), 3.0);
	}

	static double centerOfMassY(FlipMaterialParticleState state) {
		double[] mass = state.copyParticleMass();
		float[] positionVolume = state.copyPositionVolume();
		double weighted = 0.0, total = 0.0;
		for (int marker = 0; marker < mass.length; marker++) {
			weighted += mass[marker] * positionVolume[marker * 4 + 1];
			total += mass[marker];
		}
		return weighted / total;
	}

	static FluidSolverConfig productionConfig() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	}

	static FullCapabilityTestFixture.Registries loadProductionRegistries() throws Exception {
		return FullCapabilityTestFixture.load();
	}

	static void requireSameGasState(GasStateSnapshot expected, GasStateSnapshot actual) {
		require(expected != null && actual != null
				&& expected.registryFingerprint().equals(actual.registryFingerprint())
				&& expected.chemicalRegistryFingerprint().equals(actual.chemicalRegistryFingerprint())
				&& expected.nodes().equals(actual.nodes())
				&& Arrays.equals(expected.cellNodeOrdinal(), actual.cellNodeOrdinal())
				&& expected.externalSource().equals(actual.externalSource())
				&& expected.escapedSink().equals(actual.escapedSink())
				&& expected.massRoundoff().equals(actual.massRoundoff())
				&& expected.residualRoundoff().equals(actual.residualRoundoff()),
				"full-capability gas topology or inventory changed across capture/restore");
	}

	/** Higham's gamma bound for a sequence of correctly rounded operations. */
	static double floatingPointOperationBound(double magnitude, long operations, int precisionBits) {
		if (operations <= 0 || magnitude == 0.0) return 0.0;
		double accumulatedRoundoff = operations * Math.scalb(1.0, -precisionBits);
		if (!(accumulatedRoundoff < 1.0)) return Double.POSITIVE_INFINITY;
		return Math.abs(magnitude) * accumulatedRoundoff / (1.0 - accumulatedRoundoff);
	}

	static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
