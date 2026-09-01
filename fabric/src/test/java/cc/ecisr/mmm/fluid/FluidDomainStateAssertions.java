package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularStateSnapshot;

import java.util.Arrays;

/**
 * Raw-bit structural equality for {@link FluidDomainState} handoffs. Record default equals is
 * not used for any array-bearing field; float/double carriers are compared by their exact bit
 * patterns so capture/restore/candidate refactors cannot hide silent rounding.
 */
public final class FluidDomainStateAssertions {
	private FluidDomainStateAssertions() { }

	public static void assertSameState(FluidDomainState expected, FluidDomainState actual, String context) {
		require(expected != null && actual != null, context + ": state must not be null");
		require(expected.config().equals(actual.config()), context + ": solver config changed");
		require(expected.maximumParticleCount() == actual.maximumParticleCount(),
				context + ": marker budget changed");
		require(expected.sealedDomainBoundary() == actual.sealedDomainBoundary(),
				context + ": sealed boundary flag changed");
		require(expected.carrierKind() == actual.carrierKind(), context + ": carrier kind changed");
		require(expected.precipitationEnabled() == actual.precipitationEnabled(),
				context + ": precipitation flag changed");
		require(expected.maximumThermalTransportSubsteps() == actual.maximumThermalTransportSubsteps(),
				context + ": thermal substep budget changed");
		switch (expected.carrierKind()) {
			case PURE -> compareParticles(expected.particles(), actual.particles(), context + " pure carrier");
			case MATERIAL -> compareMaterials(expected.materials(), actual.materials(),
					context + " material carrier");
			case CHEMICAL -> compareChemicals(expected.chemicals(), actual.chemicals(),
					context + " chemical carrier");
		}
		require(Arrays.equals(expected.copyChemicalDirtyCells(), actual.copyChemicalDirtyCells()),
				context + ": chemical dirty-cell schedule changed");
		compareGranular(expected.granular(), actual.granular(), context);
		compareGranularDynamics(expected.granularDynamics(), actual.granularDynamics(), context);
		compareGas(expected.gas(), actual.gas(), context);
		compareThermal(expected.thermal(), actual.thermal(), context);
		comparePendingPhaseVolume(expected.pendingPhaseVolume(), actual.pendingPhaseVolume(), context);
		requireRawDoublesNullable(expected.copyThermalBoundaryTemperaturesK(),
				actual.copyThermalBoundaryTemperaturesK(), context + " thermal boundary");
		compareCondensedPhasePressure(expected.condensedPhasePressure(),
				actual.condensedPhasePressure(), context);
	}

	private static void compareParticles(FlipParticleState expected, FlipParticleState actual, String context) {
		require((expected == null) == (actual == null), context + ": presence changed");
		if (expected == null) return;
		require(expected.sizeX() == actual.sizeX() && expected.sizeY() == actual.sizeY()
						&& expected.sizeZ() == actual.sizeZ() && expected.cellSize() == actual.cellSize(),
				context + ": grid changed");
		require(expected.particleCount() == actual.particleCount(), context + ": marker count changed");
		require(Float.compare(expected.totalVolume(), actual.totalVolume()) == 0, context + ": volume ledger changed");
		require(Float.compare(expected.centerOfMassX(), actual.centerOfMassX()) == 0,
				context + ": center of mass changed");
		require(expected.solidCellCount() == actual.solidCellCount(), context + ": solid cell count changed");
		requireRawFloats(expected.copyPositionVolume(), actual.copyPositionVolume(), context + " position/volume");
		requireRawFloats(expected.copyParticleVelocities(), actual.copyParticleVelocities(), context + " velocity");
		require(Arrays.equals(expected.copyCollisionMicroMasks(), actual.copyCollisionMicroMasks()),
				context + ": collision micro mask changed");
	}

	private static void compareMaterials(FlipMaterialParticleState expected, FlipMaterialParticleState actual,
	                                     String context) {
		require((expected == null) == (actual == null), context + ": presence changed");
		if (expected == null) return;
		require(expected.sizeX() == actual.sizeX() && expected.sizeY() == actual.sizeY()
						&& expected.sizeZ() == actual.sizeZ() && expected.cellSize() == actual.cellSize(),
				context + ": grid changed");
		require(expected.particleCount() == actual.particleCount(), context + ": marker count changed");
		require(expected.totalVolume() == actual.totalVolume(), context + ": total volume changed");
		require(Double.doubleToRawLongBits(expected.totalMass()) == Double.doubleToRawLongBits(actual.totalMass()),
				context + ": total mass changed");
		require(expected.materials().equals(actual.materials()), context + ": material palette changed");
		requireRawFloats(expected.copyPositionVolume(), actual.copyPositionVolume(), context + " position/volume");
		requireRawFloats(expected.copyParticleVelocities(), actual.copyParticleVelocities(), context + " velocity");
		require(Arrays.equals(expected.copySolidMask(), actual.copySolidMask()),
				context + ": collision micro mask changed");
		requireRawDoubles(expected.copyParticleMass(), actual.copyParticleMass(), context + " marker mass");
		require(Arrays.equals(expected.copyMaterialPaletteIndex(), actual.copyMaterialPaletteIndex()),
				context + ": material palette index changed");
	}

	private static void compareChemicals(FlipChemicalParticleState expected, FlipChemicalParticleState actual,
	                                     String context) {
		require((expected == null) == (actual == null), context + ": presence changed");
		if (expected == null) return;
		compareMaterials(expected.materials(), actual.materials(), context + " chemical base");
		require(expected.registryFingerprint().equals(actual.registryFingerprint()),
				context + ": chemical registry fingerprint changed");
		require(expected.speciesPalette().equals(actual.speciesPalette()), context + ": species palette changed");
		require(expected.mixtures().equals(actual.mixtures()), context + ": mixture composition changed");
		require(Arrays.equals(expected.copyMarkerMixtureIndex(), actual.copyMarkerMixtureIndex()),
				context + ": marker mixture index changed");
		require(Arrays.equals(expected.copyIndicator(), actual.copyIndicator()), context + ": indicator column changed");
		require(Arrays.equals(expected.copySpeciesTotals(), actual.copySpeciesTotals()),
				context + ": species totals changed");
		require(expected.residualLedgerBits() == actual.residualLedgerBits(),
				context + ": mass residual ledger changed");
		require(expected.externalSource().groups().equals(actual.externalSource().groups()),
				context + ": chemical source changed");
		require(expected.escapedSink().groups().equals(actual.escapedSink().groups()),
				context + ": chemical sink changed");
	}

	private static void compareGranular(GranularStateSnapshot expected, GranularStateSnapshot actual, String context) {
		require((expected == null) == (actual == null), context + ": granular presence changed");
		if (expected == null) return;
		require(expected.registryFingerprint().equals(actual.registryFingerprint()),
				context + ": granular registry fingerprint changed");
		require(expected.chemicalRegistryFingerprint().equals(actual.chemicalRegistryFingerprint()),
				context + ": granular chemical fingerprint changed");
		require(expected.materialPalette().equals(actual.materialPalette()),
				context + ": granular material palette changed");
		require(expected.solidCompositions().equals(actual.solidCompositions()),
				context + ": granular solid composition changed");
		require(expected.solidSpeciesTotals().equals(actual.solidSpeciesTotals()),
				context + ": granular species totals changed");
		require(expected.activeParcels().equals(actual.activeParcels()), context + ": active parcels changed");
		require(expected.depositGroups().equals(actual.depositGroups()), context + ": deposit groups changed");
		require(expected.totalSolidVolumeSvq() == actual.totalSolidVolumeSvq(),
				context + ": granular total SVQ changed");
		require(expected.totalMassBits() == actual.totalMassBits(), context + ": granular total mass changed");
		require(expected.externalSource().equals(actual.externalSource()), context + ": granular source changed");
		require(expected.escapedSink().equals(actual.escapedSink()), context + ": granular sink changed");
	}

	private static void compareGranularDynamics(
			cc.ecisr.mmm.granular.GranularDepositDynamics.State expected,
			cc.ecisr.mmm.granular.GranularDepositDynamics.State actual,
			String context
	) {
		require((expected == null) == (actual == null), context + ": granular scheduler presence changed");
		if (expected == null) return;
		require(expected.logicalStep() == actual.logicalStep(), context + ": granular logical step changed");
		require(Arrays.equals(expected.stableAge(), actual.stableAge()),
				context + ": granular stable-age schedule changed");
		require(Arrays.equals(expected.queuedColumns(), actual.queuedColumns()),
				context + ": granular dirty queue changed");
	}

	private static void compareGas(GasStateSnapshot expected, GasStateSnapshot actual, String context) {
		require((expected == null) == (actual == null), context + ": gas presence changed");
		if (expected == null) return;
		require(expected.sizeX() == actual.sizeX() && expected.sizeY() == actual.sizeY()
						&& expected.sizeZ() == actual.sizeZ(),
				context + ": gas grid changed");
		require(expected.registryFingerprint().equals(actual.registryFingerprint()),
				context + ": gas registry fingerprint changed");
		require(expected.chemicalRegistryFingerprint().equals(actual.chemicalRegistryFingerprint()),
				context + ": gas chemical fingerprint changed");
		require(expected.nodes().equals(actual.nodes()), context + ": gas nodes changed");
		require(Arrays.equals(expected.cellNodeOrdinal(), actual.cellNodeOrdinal()),
				context + ": gas cell ownership changed");
		require(expected.externalSource().equals(actual.externalSource()), context + ": gas source changed");
		require(expected.escapedSink().equals(actual.escapedSink()), context + ": gas sink changed");
		require(expected.massRoundoff().equals(actual.massRoundoff()), context + ": gas mass roundoff changed");
		require(expected.residualRoundoff().equals(actual.residualRoundoff()),
				context + ": gas residual roundoff changed");
	}

	private static void compareThermal(FlipThermalParticleState expected, FlipThermalParticleState actual,
	                                   String context) {
		require((expected == null) == (actual == null), context + ": thermal presence changed");
		if (expected == null) return;
		require(expected.particleCount() == actual.particleCount(), context + ": thermal carrier count changed");
		require(expected.thermalRegistryFingerprint().equals(actual.thermalRegistryFingerprint()),
				context + ": thermal registry fingerprint changed");
		requireRawDoubles(expected.copyInternalEnergy(), actual.copyInternalEnergy(), context + " liquid U");
		requireRawDoubles(expected.copyActiveParcelEnergy(), actual.copyActiveParcelEnergy(), context + " parcel U");
		requireRawDoubles(expected.copyDepositEnergy(), actual.copyDepositEnergy(), context + " deposit U");
		requireRawDoubles(expected.copyGasNodeEnergy(), actual.copyGasNodeEnergy(), context + " gas node U");
		require(expected.coherentGroups().equals(actual.coherentGroups()), context + ": coherent groups changed");
		require(expected.audit().equals(actual.audit()), context + ": thermal audit changed");
	}

	private static void comparePendingPhaseVolume(PendingPhaseVolumeState expected, PendingPhaseVolumeState actual,
	                                              String context) {
		require((expected == null) == (actual == null), context + ": pending phase presence changed");
		if (expected == null) return;
		require(expected.cellCount() == actual.cellCount(), context + ": pending phase cell count changed");
		require(expected.entries().equals(actual.entries()), context + ": pending phase entries changed");
	}

	private static void compareCondensedPhasePressure(
			CondensedPhasePressureField expected, CondensedPhasePressureField actual, String context
	) {
		require((expected == null) == (actual == null), context + ": phase-pressure presence changed");
		if (expected == null) return;
		require(Float.floatToRawIntBits(expected.pressureReferencePa())
				== Float.floatToRawIntBits(actual.pressureReferencePa()),
				context + ": phase-pressure reference changed");
		requireRawFloats(expected.copyRelativeGaugePressurePa(), actual.copyRelativeGaugePressurePa(),
				context + " phase-pressure field");
	}

	private static void requireRawFloats(float[] expected, float[] actual, String context) {
		require(expected.length == actual.length, context + ": length changed");
		for (int index = 0; index < expected.length; index++) {
			require(Float.floatToRawIntBits(expected[index]) == Float.floatToRawIntBits(actual[index]),
					context + " raw bits changed at [" + index + "]: " + expected[index] + " vs " + actual[index]);
		}
	}

	private static void requireRawDoubles(double[] expected, double[] actual, String context) {
		require(expected.length == actual.length, context + ": length changed");
		for (int index = 0; index < expected.length; index++) {
			require(Double.doubleToRawLongBits(expected[index]) == Double.doubleToRawLongBits(actual[index]),
					context + " raw bits changed at [" + index + "]: " + expected[index] + " vs " + actual[index]);
		}
	}

	private static void requireRawDoublesNullable(double[] expected, double[] actual, String context) {
		require((expected == null) == (actual == null), context + ": presence changed");
		if (expected != null) requireRawDoubles(expected, actual, context);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
