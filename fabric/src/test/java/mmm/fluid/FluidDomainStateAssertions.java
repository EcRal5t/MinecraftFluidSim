package mmm.fluid;

import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularStateSnapshot;

import java.util.Arrays;

/**
 * Exact inventory and raw phase-occupancy/MAC/thermal-bit equality for {@link FluidDomainState} handoffs. Derived physical pressure
 * permits the float roundoff of its hydrostatic/capillary reconstruction. Record default equals is
 * not used for any array-bearing field; saved occupancy, MAC and thermal columns are compared by their exact bit
 * patterns so capture/restore/candidate refactors cannot hide silent rounding.
 */
public final class FluidDomainStateAssertions {
	private FluidDomainStateAssertions() { }

	public static void assertSameState(FluidDomainState expected, FluidDomainState actual, String context) {
		compareState(expected, actual, context, true);
	}

	/**
	 * Durable projection of {@link #assertSameState}: excludes transient state that the on-disk wire
	 * does not carry. The persistence layer serializes chemical mixture and species quantities but
	 * not the chemistry dirty-cell queue, and it omits the granular deposit scheduler queues and ages,
	 * the per-step condensed-phase pressure scratch and the world-derived constant-temperature
	 * boundary field; those are rebuilt on load. Physical inventory, gas ownership, the thermal audit
	 * and pending phase work stay bit-compared.
	 */
	public static void assertSameDurableState(FluidDomainState expected, FluidDomainState actual, String context) {
		compareState(expected, actual, context, false);
	}

	private static void compareState(FluidDomainState expected, FluidDomainState actual, String context,
	                                 boolean includeTransient) {
		require(expected != null && actual != null, context + ": state must not be null");
		require(expected.config().equals(actual.config()), context + ": solver config changed");
		require(expected.sealedDomainBoundary() == actual.sealedDomainBoundary(),
				context + ": sealed boundary flag changed");
		require(expected.carrierKind() == actual.carrierKind(), context + ": carrier kind changed");
		require(Arrays.equals(expected.materialDefinitions() == null ? null : expected.materialDefinitions().canonicalBytes(),
				actual.materialDefinitions() == null ? null : actual.materialDefinitions().canonicalBytes()),
				context + ": world material definitions changed");
		require(expected.precipitationEnabled() == actual.precipitationEnabled(),
				context + ": precipitation flag changed");
		require(expected.maximumThermalTransportSubsteps() == actual.maximumThermalTransportSubsteps(),
				context + ": thermal substep budget changed");
		require(expected.sizeX()==actual.sizeX()&&expected.sizeY()==actual.sizeY()&&expected.sizeZ()==actual.sizeZ(),context+": grid changed");
		require(expected.liquid().stocks().equals(actual.liquid().stocks()),context+": cell inventories changed");
		requireRawDoubles(expected.phaseOccupancy().copyLiquidMicroVolumes(),
				actual.phaseOccupancy().copyLiquidMicroVolumes(),context+" phase occupancy");
		require(expected.materialPalette().equals(actual.materialPalette()),context+": material palette changed");
		require(Arrays.equals(expected.copyCollisionMasks(),actual.copyCollisionMasks()),context+": collision mask changed");
		requireRawDoubles(expected.momentum().copyX(),actual.momentum().copyX(),context+" MAC X momentum");
		requireRawDoubles(expected.momentum().copyY(),actual.momentum().copyY(),context+" MAC Y momentum");
		requireRawDoubles(expected.momentum().copyZ(),actual.momentum().copyZ(),context+" MAC Z momentum");
		require(java.util.Objects.equals(expected.chemicalRegistry()==null?null:expected.chemicalRegistry().fingerprint(),
				actual.chemicalRegistry()==null?null:actual.chemicalRegistry().fingerprint()),context+": chemistry definitions changed");
		require(java.util.Objects.equals(expected.chemicalSource()==null?null:expected.chemicalSource().groups(),
				actual.chemicalSource()==null?null:actual.chemicalSource().groups()),context+": chemical source changed");
		require(java.util.Objects.equals(expected.chemicalSink()==null?null:expected.chemicalSink().groups(),
				actual.chemicalSink()==null?null:actual.chemicalSink().groups()),context+": chemical sink changed");
		if (includeTransient) {
			require(Arrays.equals(expected.copyChemicalDirtyCells(), actual.copyChemicalDirtyCells()),
					context + ": chemical dirty-cell schedule changed");
			require(expected.chemicalReactionCursor()==actual.chemicalReactionCursor(),context+": reaction cursor changed");
		}
		compareGranular(expected.granular(), actual.granular(), context);
		if (includeTransient) {
			compareGranularDynamics(expected.granularDynamics(), actual.granularDynamics(), context);
		} else {
			require((expected.granularDynamics() == null) == (actual.granularDynamics() == null),
					context + ": granular scheduler presence changed");
		}
		compareGas(expected.gas(), actual.gas(), context);
		compareThermal(expected.thermalOwner(), actual.thermalOwner(), context);
		comparePendingPhaseVolume(expected.pendingPhaseVolume(), actual.pendingPhaseVolume(), context);
		if (includeTransient) {
			requireRawDoublesNullable(expected.copyThermalBoundaryTemperaturesK(),
					actual.copyThermalBoundaryTemperaturesK(), context + " thermal boundary");
			compareCondensedPhasePressure(expected.condensedPhasePressure(),
					actual.condensedPhasePressure(), context);
		}
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
		require(expected.totalMassUnits() == actual.totalMassUnits(), context + ": granular total mass changed");
		require(expected.externalSource().equals(actual.externalSource()), context + ": granular source changed");
		require(expected.escapedSink().equals(actual.escapedSink()), context + ": granular sink changed");
	}

	private static void compareGranularDynamics(
			mmm.granular.GranularDepositDynamics.State expected,
			mmm.granular.GranularDepositDynamics.State actual,
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
		require(Arrays.equals(expected.pieceNodeOrdinal(), actual.pieceNodeOrdinal()),
				context + ": gas microbox ownership changed");
		require(expected.externalSource().equals(actual.externalSource()), context + ": gas source changed");
		require(expected.escapedSink().equals(actual.escapedSink()), context + ": gas sink changed");
	}

	private static void compareThermal(mmm.thermal.ThermalOwnerSnapshot expected, mmm.thermal.ThermalOwnerSnapshot actual,
	                                   String context) {
		require((expected == null) == (actual == null), context + ": thermal presence changed");
		if (expected == null) return;
		require(Double.doubleToRawLongBits(expected.insertionTemperatureK())==Double.doubleToRawLongBits(actual.insertionTemperatureK()),context+": insertion temperature changed");
		require(expected.registryFingerprint().equals(actual.registryFingerprint()),
				context + ": thermal registry fingerprint changed");
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
		float[] first = expected.copyRelativeGaugePressurePa(), second = actual.copyRelativeGaugePressurePa();
		require(first.length == second.length, context + ": phase-pressure length changed");
		for (int index = 0; index < first.length; index++) {
			double before = (double) expected.pressureReferencePa() + first[index];
			double after = (double) actual.pressureReferencePa() + second[index];
			// Reconstructing p from float residual pressure and known loads incurs cancellation.
			// Four float ULPs at atmospheric or local pressure scale allow that representation error.
			float scale = (float) Math.max(mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA,
					Math.max(Math.abs(before), Math.abs(after)));
			double tolerance = 4.0 * Math.ulp(scale);
			require(Math.abs(before - after) <= tolerance,
					context + " phase-pressure changed at [" + index + "]: " + before + " vs " + after
							+ " Pa, allowed=" + tolerance + " Pa");
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
