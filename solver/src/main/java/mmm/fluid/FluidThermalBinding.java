package mmm.fluid;

import mmm.fluid.air.AirNetwork;
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

	static ThermalDomainState createRestoreTarget(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			ThermalCarrierContext markers, AirNetwork airNetwork, int maximumTransportSubsteps,
			double[] restoredLiquidEnergy
	) {
		MaterialMappings mappings = mappings(registry, liquids, initialTemperatureK, markers);
		return ThermalDomainState.restoreTarget(registry, mappings.liquid(), mappings.granular(),
				initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ, cellSize, markers,
				airNetwork, maximumTransportSubsteps, restoredLiquidEnergy);
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
		for (CoherentPhaseStore.Group group : snapshot.coherentGroups()) {
			int material = state.registry().materialHandle(group.phaseId());
			if (state.registry().material(material).carrierKind()
					!= ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID) {
				throw new IllegalArgumentException("Restored coherent phase has the wrong thermal carrier kind");
			}
			state.stateFunction().stateForInternalEnergy(material, group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts(),
					group.internalEnergy());
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
		if (markers.markerCount() > 0) state.temperature(0, markers);
		mmm.compute.CpuWorkBatch.run(markers.markerCount(), (from, until) -> {
			for (int marker = from; marker < until; marker++) state.temperature(marker, markers);
		});
		state.restoreAuditLedger(snapshot.audit());
		if (!state.exactEnergy(markers.markerCount()).equals(state.audit().ledger().expectedInventory())) {
			throw new IllegalArgumentException("Restored thermal audit does not close");
		}
	}

	static FlipParticleReseeder.ThermalAdapter reseedAdapter(
			ThermalDomainState state, ThermalCarrierContext markers
	) {
		return new FlipParticleReseeder.ThermalAdapter() {
			@Override public double totalEnergy(int count) { return state.totalEnergy(count); }
			@Override public void merge(int target, int source) { state.mergeReseedLiquidEnergy(target, source); }
			@Override public FlipParticleReseeder.ThermalSplit beforeSplit(int source) {
				return new FlipParticleReseeder.ThermalSplit(
						state.liquidInternalEnergy(source), state.temperature(source, markers));
			}
			@Override public void afterSplit(FlipParticleReseeder.ThermalSplit split, int source, int destination) {
				double sourceModeled = state.liquid().modeledEnergy(source, markers.mass(source),
						markers.mixture(source), split.temperatureK(), markers);
				state.assignReseededLiquidEnergies(source, sourceModeled, destination,
						split.parentEnergy() - sourceModeled);
				validateTemperature(state, destination, split.temperatureK(), markers);
			}
			@Override public void move(int source, int destination) {
				state.moveReseedLiquidEnergy(source, destination);
			}
		};
	}

	static double gasVolumeCompliance(ThermalDomainState state, int nodeId, AirNetwork network) {
		return network.availableVolume(nodeId)
				* state.gasHydrodynamicState(nodeId, network).pressureCompliancePerPa();
	}

	private static void requireTemperature(ThermalMaterialDefinition material, double temperatureK) {
		if (temperatureK < material.minimumTemperatureK() || temperatureK > material.maximumTemperatureK()) {
			throw new IllegalArgumentException("Initial temperature is outside " + material.id());
		}
	}

	private static void validateTemperature(
			ThermalDomainState state, int marker, double expected, ThermalCarrierContext markers
	) {
		double restored = state.temperature(marker, markers);
		double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(restored), Math.ulp(expected)) * 16.0);
		if (Math.abs(restored - expected) > tolerance) {
			throw new IllegalArgumentException("Reseeded thermal split left local equilibrium");
		}
	}
}
