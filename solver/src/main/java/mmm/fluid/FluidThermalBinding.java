package mmm.fluid;

import mmm.fluid.air.AirNetwork;
import mmm.inventory.LiquidCellInventory;
import mmm.units.Mass;
import mmm.units.SignedDyadic;
import mmm.thermal.CoherentPhaseStore;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.ThermalCarrierContext;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fluid-side construction and persistence mapping for the thermal aggregate. */
final class FluidThermalBinding {
	private FluidThermalBinding() { }

	/** Reference energy follows the allocated integer species; only sensible U follows mass. */
	static LiquidCellInventory.EnergyPartitioner liquidEnergyPartitioner(FluidDomain domain) {
		if (domain.thermalState == null) return null;
		var thermal = domain.thermalState;
		var function = thermal.stateFunction();
		var chemistry = domain.liquidCells.chemicalRegistry();
		return (source, masses, mixtures, largest, energies) -> {
			int material = thermal.thermalMaterialHandleForFluid(source.key().material());
			double sourceReference = function.baseEnergy(material, source.massKilograms(), source.mixture(), chemistry);
			double sensibleEnergy = source.internalEnergy()-sourceReference;
			for (int i = 0; i < masses.length; i++) if (i != largest && masses[i] > 0) {
				double reference = function.baseEnergy(material, Mass.kilograms(masses[i]), mixtures[i], chemistry);
				energies[i] = reference+sensibleEnergy*(masses[i]/(double)source.massUnits());
			}
		};
	}

	static ThermalDomainState create(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			ThermalCarrierContext markers, AirNetwork airNetwork, int maximumTransportSubsteps
	) {
		MaterialMappings mappings = mappings(registry, liquids, initialTemperatureK, markers);
		return new ThermalDomainState(registry, mappings.liquid(), mappings.granular(),
				initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ, cellSize, markers,
				airNetwork, maximumTransportSubsteps);
	}

	private static MaterialMappings mappings(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			ThermalCarrierContext markers
	) {
		if (registry == null || liquids == null || markers == null) {
			throw new IllegalArgumentException("Thermal binding input is missing");
		}
		if (!Double.isFinite(initialTemperatureK)) throw new IllegalArgumentException("Initial temperature must be finite");
		int[] materialByFluidHandle = new int[liquids.size()];
		for (int handle = 0; handle < liquids.size(); handle++) {
			int thermalHandle = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,
					liquids.material(handle).id());
			ThermalMaterialDefinition material = registry.material(thermalHandle);
			requireTemperature(material, initialTemperatureK);
			materialByFluidHandle[handle] = thermalHandle;
		}
		int[] materialByGranularHandle = new int[markers.granularMaterialCount()];
		if (materialByGranularHandle.length > 0
				&& !registry.granularRegistryFingerprint().equals(markers.granularRegistryFingerprint())) {
			throw new IllegalArgumentException("Thermal registry granular dependency mismatch");
		}
		for (int handle = 0; handle < materialByGranularHandle.length; handle++) {
			int thermalHandle = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,
					markers.granularMaterialId(handle));
			ThermalMaterialDefinition material = registry.material(thermalHandle);
			requireTemperature(material, initialTemperatureK);
			materialByGranularHandle[handle] = thermalHandle;
		}
		return new MaterialMappings(materialByFluidHandle, materialByGranularHandle);
	}

	static ThermalDomainState createOwnerRestoreTarget(ThermalRegistry registry, FluidMaterialRegistry liquids,
			double insertionTemperatureK,int initialCapacity,int sizeX,int sizeY,int sizeZ,float cellSize,
			ThermalCarrierContext owners,AirNetwork network,int maximumTransportSubsteps) {
		var mappings = mappings(registry,liquids,insertionTemperatureK,owners);
		return ThermalDomainState.restoreOwnerTarget(registry,mappings.liquid(),mappings.granular(),insertionTemperatureK,
				initialCapacity,sizeX,sizeY,sizeZ,cellSize,owners,network,maximumTransportSubsteps);
	}

	private record MaterialMappings(int[] liquid, int[] granular) { }

	static ThermalDomainState create(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			ThermalCarrierContext markers, AirNetwork airNetwork
	) {
		return create(registry, liquids, initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ,
				cellSize, markers, airNetwork, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}

	static ThermalDomainState create(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			ThermalCarrierContext markers
	) {
		return create(registry, liquids, initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ,
				cellSize, markers, null);
	}

	static FlipThermalParticleState capture(
			ThermalDomainState state, int sizeX, int sizeY, int sizeZ, float cellSize, int count
	) {
		return new FlipThermalParticleState(sizeX, sizeY, sizeZ, cellSize, count, state.registry().fingerprint(), state.insertionTemperatureK(),
				state.liquid().copyEnergies(count), state.granularColumn().copyActiveEnergy(),
				state.granularColumn().depositSnapshot().stream()
						.mapToDouble(GranularEnergyColumn.DepositEnergy::internalEnergy).toArray(),
				state.coherentPhases().snapshot(), state.gas().nodeEnergies(), state.audit().ledger());
	}

	static void restore(
			ThermalDomainState state, FlipThermalParticleState snapshot,
			ThermalCarrierContext markers, AirNetwork airNetwork
	) {
		if (state.coherentPhases().groupCount() != 0) throw new IllegalStateException("Thermal restore target is not empty");
		// The candidate aggregate reads the live carrier identities, while restored liquid
		// energy stays private until every carrier and the shared audit have passed.
		double[] liquidEnergy = snapshot.copyInternalEnergy();
		if (liquidEnergy.length != markers.markerCount()) throw new IllegalArgumentException("Restored liquid energy count differs");
		for (int ordinal = 0; ordinal < liquidEnergy.length; ordinal++) {
			state.liquid().temperatureForInventory(markers.fluidMaterialHandle(ordinal), markers.mass(ordinal),
					markers.mixture(ordinal), liquidEnergy[ordinal], markers.chemicalRegistry());
		}
		SignedDyadic restoredEnergy = SignedDyadic.sum(liquidEnergy);
		for (CoherentPhaseStore.Group group : snapshot.coherentGroups()) {
			group.requireCompositionMass(markers.chemicalRegistry());
			int material = state.registry().materialHandle(group.phaseId());
			if (state.registry().material(material).carrierKind()
					!= ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID) {
				throw new IllegalArgumentException("Restored coherent phase has the wrong thermal carrier kind");
			}
			state.stateFunction().stateForInternalEnergy(material, group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts(),
					group.internalEnergy());
			restoredEnergy = restoredEnergy.add(SignedDyadic.of(group.internalEnergy()));
		}
		state.restoreThermalCoherent(snapshot.coherentGroups());
		double[] activeEnergy = snapshot.copyActiveParcelEnergy();
		double[] depositEnergy = snapshot.copyDepositEnergy();
		List<GranularEnergyColumn.DepositKey> depositKeys = markers.depositKeys();
		if (activeEnergy.length != markers.activeParcelCount() || depositEnergy.length != depositKeys.size()) {
			throw new IllegalArgumentException("Restored granular thermal ordinals differ from granular inventory");
		}
		ArrayList<GranularEnergyColumn.DepositEnergy> deposits = new ArrayList<>(depositEnergy.length);
		for (int index = 0; index < depositEnergy.length; index++) {
			deposits.add(new GranularEnergyColumn.DepositEnergy(depositKeys.get(index), depositEnergy[index]));
		}
		state.restoreThermalGranular(activeEnergy, deposits, markers);
		double[] restoredGas = snapshot.copyGasNodeEnergy();
		if (restoredGas.length != (airNetwork == null ? 0 : airNetwork.nodeCount())
				|| restoredGas.length > 0 && !state.gas().gasModelBound()) {
			throw new IllegalArgumentException("Restored gas thermal ordinals differ from gas inventory");
		}
		state.restoreGasEnergies(airNetwork, restoredGas);
		restoredEnergy = restoredEnergy.add(SignedDyadic.sum(activeEnergy))
				.add(SignedDyadic.sum(depositEnergy)).add(SignedDyadic.sum(restoredGas));
		state.restoreAuditLedger(snapshot.audit());
		if (!restoredEnergy.equals(state.audit().ledger().expectedInventory())) {
			throw new IllegalArgumentException("Restored thermal audit does not close");
		}
	}

	private static void requireTemperature(ThermalMaterialDefinition material, double temperatureK) {
		if (temperatureK < material.minimumTemperatureK() || temperatureK > material.maximumTemperatureK()) {
			throw new IllegalArgumentException("Initial temperature is outside " + material.id());
		}
	}

}
