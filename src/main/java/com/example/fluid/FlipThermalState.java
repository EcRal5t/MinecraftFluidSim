package com.example.fluid;

import com.example.chemistry.ChemicalMixtureRecord;
import com.example.chemistry.ChemicalRegistry;
import com.example.granular.SolidVolume;
import com.example.fluid.air.AirNetwork;
import com.example.fluid.air.GasInventory;
import com.example.fluid.air.GasRegistry;
import com.example.thermal.GasThermalModel;
import com.example.thermal.GasLiquidPhaseModel;
import com.example.thermal.CoherentPhaseStore;
import com.example.thermal.CondensedMassScale;
import com.example.thermal.GranularEnergyColumn;
import com.example.thermal.SignedDyadic;
import com.example.thermal.ThermalAuditLedger;
import com.example.thermal.ThermalCellGrid;
import com.example.thermal.ThermalLimits;
import com.example.thermal.ThermalMaterialDefinition;
import com.example.thermal.ThermalPhaseChangeEngine;
import com.example.thermal.ThermalRegistry;
import com.example.thermal.ThermalStateFunction;
import com.example.thermal.ThermalDisplaySnapshot;
import com.example.thermal.ThermalTransport;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Owns the optional liquid-marker M9 energy column; FlipFluidDomain only supplies carrier/cell orchestration. */
final class FlipThermalState {
	interface MarkerContext {
		int markerCount();
		int cell(int marker);
		int fluidMaterialHandle(int marker);
		double mass(int marker);
		double volume(int marker);
		ChemicalMixtureRecord mixture(int marker);
		ChemicalRegistry chemicalRegistry();
		default int granularMaterialCount() { return 0; }
		default String granularRegistryFingerprint() { return ""; }
		default String granularMaterialId(int materialHandle) { throw new IllegalArgumentException("No granular state"); }
		default int activeParcelCount() { return 0; }
		default int activeParcelCell(int ordinal) { throw new IllegalArgumentException("No granular state"); }
		default int activeParcelMaterial(int ordinal) { throw new IllegalArgumentException("No granular state"); }
		default int activeParcelComposition(int ordinal) { throw new IllegalArgumentException("No granular state"); }
		default long activeParcelSvq(int ordinal) { throw new IllegalArgumentException("No granular state"); }
		default double activeParcelMass(int ordinal) { throw new IllegalArgumentException("No granular state"); }
		default List<GranularEnergyColumn.DepositKey> depositKeys() { return List.of(); }
		default long depositSvq(GranularEnergyColumn.DepositKey key) { throw new IllegalArgumentException("No granular state"); }
		default double depositMass(GranularEnergyColumn.DepositKey key) { throw new IllegalArgumentException("No granular state"); }
		default Map<String, Long> solidComposition(int compositionHandle) {
			if (compositionHandle == 0) return Map.of();
			throw new IllegalArgumentException("No solid composition state");
		}
	}

	record Truncation(double newEnergy, double removedEnergy) {
		Truncation {
			if (!Double.isFinite(newEnergy) || !Double.isFinite(removedEnergy)) {
				throw new IllegalArgumentException("Thermal truncation ledger is invalid");
			}
		}
	}
	record LiquidInsertion(int fluidMaterialHandle, double mass, ChemicalMixtureRecord mixture) {
		LiquidInsertion {
			if (fluidMaterialHandle < 0 || !(Double.isFinite(mass) && mass > 0.0)) {
				throw new IllegalArgumentException("Thermal liquid insertion inventory is invalid");
			}
		}
	}
	record ExternalLiquidInsertionPlan(double[] energies, SignedDyadic source, SignedDyadic roundoff) {
		ExternalLiquidInsertionPlan {
			if (energies == null || energies.length == 0 || source == null || roundoff == null) {
				throw new IllegalArgumentException("Thermal liquid insertion plan is incomplete");
			}
			energies = energies.clone();
		}
		@Override public double[] energies() { return energies.clone(); }
	}
	enum GranularReactionKind { ADD_ACTIVE, REPLACE_ACTIVE, REMOVE_ACTIVE, REPLACE_DEPOSIT, REMOVE_DEPOSIT }

	record ReactionMarker(int marker, double mass, ChemicalMixtureRecord mixture) {
		ReactionMarker {
			if (marker < 0 || !(Double.isFinite(mass) && mass > 0.0) || mixture == null) {
				throw new IllegalArgumentException("Thermal reaction marker is invalid");
			}
		}
	}

	record GranularReactionEdit(
			GranularReactionKind kind, int activeOrdinal, GranularEnergyColumn.DepositKey depositKey,
			int materialHandle, double finalMass, Map<String, Long> finalComposition
	) {
		GranularReactionEdit {
			if (kind == null || finalComposition == null) {
				throw new IllegalArgumentException("Thermal granular reaction edit is missing");
			}
			finalComposition = Map.copyOf(finalComposition);
			boolean active = kind == GranularReactionKind.ADD_ACTIVE || kind == GranularReactionKind.REPLACE_ACTIVE
					|| kind == GranularReactionKind.REMOVE_ACTIVE;
			boolean add = kind == GranularReactionKind.ADD_ACTIVE;
			boolean remove = kind == GranularReactionKind.REMOVE_ACTIVE || kind == GranularReactionKind.REMOVE_DEPOSIT;
			if (active != (depositKey == null) || active && add != (activeOrdinal < 0)
					|| !add && active && activeOrdinal < 0 || !active && activeOrdinal != -1
					|| remove && (finalMass != 0.0 || materialHandle != -1 || !finalComposition.isEmpty())
					|| !remove && (materialHandle < 0 || !(Double.isFinite(finalMass) && finalMass > 0.0))) {
				throw new IllegalArgumentException("Thermal granular reaction edit shape is invalid");
			}
		}
	}

	record ReactionPlan(int cell, SignedDyadic cellEnergy, SignedDyadic inventoryBefore, double equilibriumTemperatureK) { }
	record PhaseMechanicalWorkPlan(
			double[] targetCellEnergy, SignedDyadic inventoryBefore, SignedDyadic mechanicalWork
	) {
		PhaseMechanicalWorkPlan {
			targetCellEnergy = targetCellEnergy.clone();
		}
		@Override public double[] targetCellEnergy() { return targetCellEnergy.clone(); }
	}
	record GasRemapPlan(
			double[] nodeEnergy, SignedDyadic sourceEnergy, SignedDyadic sinkEnergy,
			SignedDyadic mechanicalWork, SignedDyadic roundoff
	) {
		GasRemapPlan { nodeEnergy = nodeEnergy.clone(); }
		@Override public double[] nodeEnergy() { return nodeEnergy.clone(); }
	}

	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;
	private final CondensedMassScale condensedMassScale;
	private final int[] materialByFluidHandle;
	private final int[] materialByGranularHandle;
	private final ThermalCellGrid cellGrid;
	private final ThermalTransport transport;
	private final CoherentPhaseStore coherentPhases;
	private final ThermalPhaseChangeEngine phaseChange;
	private final GranularEnergyColumn granularEnergy;
	private final GasThermalModel gasModel;
	private final double insertionTemperatureK;
	private double[] internalEnergy;
	private double[] gasNodeEnergy;
	private ThermalAuditLedger audit;
	private ThermalTransport.Stats lastTransport = new ThermalTransport.Stats(
			0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO);

	FlipThermalState(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			MarkerContext markers
	) {
		this(registry, liquids, initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ, cellSize, markers, null);
	}

	FlipThermalState(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			MarkerContext markers, AirNetwork airNetwork
	) {
		this(registry, liquids, initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ, cellSize,
				markers, airNetwork, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}

	FlipThermalState(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			MarkerContext markers, AirNetwork airNetwork, int maximumTransportSubsteps
	) {
		if (registry == null || liquids == null || markers == null) {
			throw new IllegalArgumentException("Thermal binding input is missing");
		}
		if (!Double.isFinite(initialTemperatureK)) throw new IllegalArgumentException("Initial temperature must be finite");
		this.registry = registry;
		this.stateFunction = new ThermalStateFunction(registry);
		this.condensedMassScale = CondensedMassScale.forCellSize(cellSize);
		this.materialByFluidHandle = new int[liquids.size()];
		for (int handle = 0; handle < liquids.size(); handle++) {
			int thermalHandle = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,
					liquids.material(handle).id());
			ThermalMaterialDefinition material = registry.material(thermalHandle);
			if (initialTemperatureK < material.minimumTemperatureK()
					|| initialTemperatureK > material.maximumTemperatureK()) {
				throw new IllegalArgumentException("Initial temperature is outside " + material.id());
			}
			materialByFluidHandle[handle] = thermalHandle;
		}
		this.materialByGranularHandle = new int[markers.granularMaterialCount()];
		if (materialByGranularHandle.length > 0
				&& !registry.granularRegistryFingerprint().equals(markers.granularRegistryFingerprint())) {
			throw new IllegalArgumentException("Thermal registry granular dependency mismatch");
		}
		for (int handle = 0; handle < materialByGranularHandle.length; handle++) {
			int thermalHandle = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,
					markers.granularMaterialId(handle));
			ThermalMaterialDefinition material = registry.material(thermalHandle);
			if (initialTemperatureK < material.minimumTemperatureK()
					|| initialTemperatureK > material.maximumTemperatureK()) {
				throw new IllegalArgumentException("Initial temperature is outside " + material.id());
			}
			materialByGranularHandle[handle] = thermalHandle;
		}
		this.cellGrid = new ThermalCellGrid(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ));
		this.transport = new ThermalTransport(sizeX, sizeY, sizeZ, cellSize, maximumTransportSubsteps);
		this.coherentPhases = new CoherentPhaseStore(
				Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ),
				Math.min(ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION,
						Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ), 8)));
		this.phaseChange = new ThermalPhaseChangeEngine(registry);
		this.granularEnergy = new GranularEnergyColumn(
				com.example.granular.GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION,
				com.example.granular.GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
		this.insertionTemperatureK = initialTemperatureK;
		this.internalEnergy = new double[initialCapacity];
		for (int marker = 0; marker < markers.markerCount(); marker++) {
			internalEnergy[marker] = modeledEnergy(marker, markers.mass(marker), markers.mixture(marker),
					initialTemperatureK, markers);
		}
		for (int parcel = 0; parcel < markers.activeParcelCount(); parcel++) {
			granularEnergy.addActive(modeledGranularEnergy(markers.activeParcelMaterial(parcel),
					markers.activeParcelComposition(parcel), markers.activeParcelMass(parcel),
					initialTemperatureK, markers));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) {
			granularEnergy.addDeposit(key, modeledGranularEnergy(key.materialHandle(), key.compositionHandle(),
					markers.depositMass(key), initialTemperatureK, markers));
		}
		boolean supportsGas = airNetwork != null;
		if (supportsGas) for (GasRegistry.AmbientFraction fraction : airNetwork.registry().ambient()) {
			if (!registry.hasMaterial(ThermalMaterialDefinition.CarrierKind.GAS, fraction.componentId())) supportsGas = false;
		}
		if (supportsGas) for (int node = 1; node <= airNetwork.nodeCount(); node++) {
			for (var component : airNetwork.inventory(node).components()) if (!registry.hasMaterial(
					ThermalMaterialDefinition.CarrierKind.GAS, component.componentId())) supportsGas = false;
		}
		if (supportsGas) {
			this.gasModel = new GasThermalModel(registry, airNetwork.registry());
			this.gasNodeEnergy = new double[airNetwork.nodeCount()];
			for (int node = 1; node <= airNetwork.nodeCount(); node++) {
				gasNodeEnergy[node - 1] = gasModel.internalEnergyAtTemperature(
						airNetwork.inventory(node), initialTemperatureK);
			}
		} else {
			if (airNetwork != null && airNetwork.nodeCount() > 0) {
				throw new IllegalArgumentException("Sealed gas inventory requires complete gas thermal definitions");
			}
			this.gasModel = null;
			this.gasNodeEnergy = new double[0];
		}
		this.audit = ThermalAuditLedger.initialized(exactEnergy(markers.markerCount()));
	}

	String fingerprint() { return registry.fingerprint(); }
	ThermalAuditLedger audit() { return audit; }
	CoherentPhaseStore coherentPhases() { return coherentPhases; }
	GranularEnergyColumn granularEnergy() { return granularEnergy; }
	ThermalRegistry registry() { return registry; }
	ThermalPhaseChangeEngine phaseChange() { return phaseChange; }
	GasLiquidPhaseModel gasLiquidPhase(AirNetwork network) {
		if (gasModel == null) throw new IllegalStateException("Gas thermal model is not bound");
		return new GasLiquidPhaseModel(registry, network.registry());
	}
	double liquidInternalEnergy(int marker) { return internalEnergy[marker]; }
	ThermalTransport.Stats lastTransport() { return lastTransport; }
	double totalEnergy(int count) {
		double total = 0.0;
		for (int marker = 0; marker < count; marker++) total += internalEnergy[marker];
		for (CoherentPhaseStore.Group group : coherentPhases.snapshot()) total += group.internalEnergy();
		total += granularEnergy.exactEnergy().toDouble();
		for (double energy : gasNodeEnergy) total += energy;
		return total;
	}
	SignedDyadic exactEnergy(int count) {
		return SignedDyadic.sum(Arrays.copyOf(internalEnergy, count)).add(coherentPhases.exactEnergy())
				.add(granularEnergy.exactEnergy()).add(SignedDyadic.sum(gasNodeEnergy));
	}
	SignedDyadic exactLiquidEnergy(int count) {
		return SignedDyadic.sum(Arrays.copyOf(internalEnergy, count));
	}

	void ensureCapacity(int capacity) {
		if (capacity > internalEnergy.length) internalEnergy = Arrays.copyOf(internalEnergy, capacity);
	}

	FlipThermalParticleState capture(int sizeX, int sizeY, int sizeZ, float cellSize, int count) {
		return new FlipThermalParticleState(sizeX, sizeY, sizeZ, cellSize, count, registry.fingerprint(),
				Arrays.copyOf(internalEnergy, count), granularEnergy.copyActiveEnergy(),
				granularEnergy.depositSnapshot().stream()
						.mapToDouble(GranularEnergyColumn.DepositEnergy::internalEnergy).toArray(),
				coherentPhases.snapshot(), gasNodeEnergy, audit);
	}

	void restore(FlipThermalParticleState snapshot, MarkerContext markers, AirNetwork airNetwork) {
		if (coherentPhases.groupCount() != 0) throw new IllegalStateException("Thermal restore target is not empty");
		double[] restored = snapshot.copyInternalEnergy();
		ensureCapacity(restored.length);
		System.arraycopy(restored, 0, internalEnergy, 0, restored.length);
		for (CoherentPhaseStore.Group group : snapshot.coherentGroups()) {
			int material = registry.materialHandle(group.phaseId());
			if (registry.material(material).carrierKind() != ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID) {
				throw new IllegalArgumentException("Restored coherent phase has the wrong thermal carrier kind");
			}
			stateFunction.stateForInternalEnergy(material, group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts(),
					group.internalEnergy());
		}
		coherentPhases.restore(snapshot.coherentGroups());
		double[] activeEnergy = snapshot.copyActiveParcelEnergy(), depositEnergy = snapshot.copyDepositEnergy();
		List<GranularEnergyColumn.DepositKey> depositKeys = markers.depositKeys();
		if (activeEnergy.length != markers.activeParcelCount() || depositEnergy.length != depositKeys.size()) {
			throw new IllegalArgumentException("Restored granular thermal ordinals differ from M8 inventory");
		}
		java.util.ArrayList<GranularEnergyColumn.DepositEnergy> deposits = new java.util.ArrayList<>(depositEnergy.length);
		for (int index = 0; index < depositEnergy.length; index++) {
			deposits.add(new GranularEnergyColumn.DepositEnergy(depositKeys.get(index), depositEnergy[index]));
		}
		granularEnergy.replace(activeEnergy, deposits);
		double[] restoredGas = snapshot.copyGasNodeEnergy();
		if (restoredGas.length != (airNetwork == null ? 0 : airNetwork.nodeCount())
				|| restoredGas.length > 0 && gasModel == null) {
			throw new IllegalArgumentException("Restored gas thermal ordinals differ from gas v4 inventory");
		}
		for (int node = 0; node < restoredGas.length; node++) gasModel.absolutePressure(
				airNetwork.inventory(node + 1), airNetwork.availableVolume(node + 1), restoredGas[node]);
		gasNodeEnergy = restoredGas;
		for (int marker = 0; marker < markers.markerCount(); marker++) temperature(marker, markers);
		cellGrid.rebuild(carrierAccess(markers));
		audit = snapshot.audit();
		if (!exactEnergy(markers.markerCount()).equals(audit.expectedInventory())) {
			throw new IllegalArgumentException("Restored thermal audit does not close");
		}
	}

	boolean hasGasEnergy(int nodeCount) { return gasModel != null && gasNodeEnergy.length == nodeCount; }
	boolean gasModelBound() { return gasModel != null; }
	double gasNodeEnergy(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasNodeEnergy[nodeId - 1];
	}
	double gasTemperature(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.state(network.inventory(nodeId), gasNodeEnergy[nodeId - 1]).temperatureK();
	}
	double gasGaugePressure(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.absolutePressure(network.inventory(nodeId), network.availableVolume(nodeId),
				gasNodeEnergy[nodeId - 1]) - GasRegistry.STANDARD_PRESSURE_PA;
	}
	double gasDensity(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.density(network.inventory(nodeId), network.availableVolume(nodeId));
	}

	double requireGasNodeState(int nodeId, GasInventory inventory, double energy, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.absolutePressure(inventory, network.availableVolume(nodeId), energy);
	}

	void commitGasNodeEnergy(int nodeId, double energy) {
		if (nodeId <= 0 || nodeId > gasNodeEnergy.length || !Double.isFinite(energy)) {
			throw new IllegalArgumentException("Gas phase energy commit is invalid");
		}
		gasNodeEnergy[nodeId - 1] = energy;
	}

	double[] partitionGasNodeEnergy(int nodeId, List<GasInventory> children, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.partitionEnergy(network.inventory(nodeId), gasNodeEnergy[nodeId - 1], children);
	}

	double ambientGasEnergy(GasInventory inventory) {
		if (gasModel == null) throw new IllegalStateException("Gas thermal model is not bound");
		return gasModel.internalEnergyAtTemperature(inventory, insertionTemperatureK);
	}

	GasRemapPlan planGasRemap(
			AirNetwork oldNetwork, List<GasInventory> inventories, double[] availableVolumes,
			double[] mixedEnergy, double[] inheritedAvailableVolumes,
			double sourceEnergy, double sinkEnergy
	) {
		if (!hasGasEnergy(oldNetwork.nodeCount()) || inventories == null
				|| inventories.size() != availableVolumes.length || inventories.size() != mixedEnergy.length
				|| inventories.size() != inheritedAvailableVolumes.length
				|| !Double.isFinite(sourceEnergy) || !Double.isFinite(sinkEnergy)) {
			throw new IllegalArgumentException("Gas thermal remap plan is incompatible");
		}
		double[] result = mixedEnergy.clone();
		for (int node = 0; node < result.length; node++) {
			if (inventories.get(node).isEmpty() || !(Double.isFinite(availableVolumes[node]) && availableVolumes[node] > 0.0)
					|| !(Double.isFinite(inheritedAvailableVolumes[node]) && inheritedAvailableVolumes[node] > 0.0)
					|| !Double.isFinite(result[node])) {
				throw new IllegalArgumentException("Gas thermal remap produced an invalid node");
			}
			if (Double.doubleToRawLongBits(inheritedAvailableVolumes[node])
					== Double.doubleToRawLongBits(availableVolumes[node])) {
				gasModel.absolutePressure(inventories.get(node), availableVolumes[node], result[node]);
			} else {
				result[node] = gasModel.compressAdiabatically(inventories.get(node), result[node],
						inheritedAvailableVolumes[node], availableVolumes[node]).finalInternalEnergy();
			}
		}
		SignedDyadic oldGas = SignedDyadic.sum(gasNodeEnergy);
		SignedDyadic mixed = SignedDyadic.sum(mixedEnergy);
		SignedDyadic finalGas = SignedDyadic.sum(result);
		SignedDyadic source = SignedDyadic.of(sourceEnergy), sink = SignedDyadic.of(sinkEnergy);
		SignedDyadic work = finalGas.subtract(mixed);
		SignedDyadic roundoff = finalGas.subtract(oldGas).subtract(source).add(sink).subtract(work);
		return new GasRemapPlan(result, source, sink, work, roundoff);
	}

	void commitGasRemap(GasRemapPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Gas thermal remap plan is missing");
		gasNodeEnergy = plan.nodeEnergy();
		audit = audit.withExternalSourceEnergy(plan.sourceEnergy()).withExternalSinkEnergy(plan.sinkEnergy())
				.withMechanicalWork(plan.mechanicalWork()).withRoundoff(plan.roundoff());
	}

	Runnable planGasTransfer(
			int sourceNode, int targetNode, GasInventory moved,
			GasInventory sourceAfter, GasInventory targetAfter, AirNetwork network
	) {
		if (!hasGasEnergy(network.nodeCount()) || moved == null || moved.isEmpty()) {
			throw new IllegalArgumentException("Gas thermal transfer plan is incompatible");
		}
		double[] result = gasNodeEnergy.clone();
		double sourceTemperature = sourceNode == AirNetwork.AMBIENT_NODE
				? insertionTemperatureK : gasTemperature(sourceNode, network);
		double carried = gasModel.enthalpy(moved, sourceTemperature);
		SignedDyadic before = SignedDyadic.sum(gasNodeEnergy);
		SignedDyadic imported = SignedDyadic.ZERO, exported = SignedDyadic.ZERO;
		if (sourceNode == AirNetwork.AMBIENT_NODE) {
			result[targetNode - 1] += carried;
			gasModel.absolutePressure(targetAfter, network.availableVolume(targetNode), result[targetNode - 1]);
			imported = SignedDyadic.of(carried);
		} else {
			result[sourceNode - 1] -= carried;
			gasModel.absolutePressure(sourceAfter, network.availableVolume(sourceNode), result[sourceNode - 1]);
			if (targetNode == AirNetwork.AMBIENT_NODE) exported = SignedDyadic.of(carried);
			else {
				result[targetNode - 1] += carried;
				gasModel.absolutePressure(targetAfter, network.availableVolume(targetNode), result[targetNode - 1]);
			}
		}
		SignedDyadic after = SignedDyadic.sum(result);
		SignedDyadic roundoff = after.subtract(before).subtract(imported).add(exported);
		SignedDyadic finalImported = imported, finalExported = exported;
		return () -> {
			gasNodeEnergy = result;
			audit = audit.withExternalSourceEnergy(finalImported).withExternalSinkEnergy(finalExported)
					.withRoundoff(roundoff);
		};
	}

	private void requireGasNode(int nodeId, AirNetwork network) {
		if (!hasGasEnergy(network.nodeCount()) || nodeId <= 0 || nodeId > gasNodeEnergy.length) {
			throw new IllegalStateException("Gas thermal state is not bound to the current topology");
		}
	}

	double temperature(int marker, MarkerContext markers) {
		ThermalMaterialDefinition material = material(marker, markers);
		double sensible = (internalEnergy[marker] - baseEnergy(marker, markers))
				/ condensedMassScale.kilograms(markers.mass(marker));
		return material.temperatureForSensibleSpecificEnergy(sensible);
	}

	void requireReactionState(
			int marker, ChemicalMixtureRecord candidateMixture, double candidateMass, MarkerContext markers
	) {
		ThermalMaterialDefinition material = material(marker, markers);
		int thermalHandle = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
		double physicalMass = condensedMassScale.kilograms(candidateMass);
		double base = stateFunction.baseEnergy(
				thermalHandle, physicalMass, candidateMixture, markers.chemicalRegistry());
		double sensible = (internalEnergy[marker] - base) / physicalMass;
		material.temperatureForSensibleSpecificEnergy(sensible);
	}

	ReactionPlan requireGranularReaction(
			int cell, List<ReactionMarker> markerReplacements, GranularReactionEdit granularEdit,
			MarkerContext markers
	) {
		if (cell < 0 || cell >= cellGrid.cellCount() || markerReplacements == null || granularEdit == null) {
			throw new IllegalArgumentException("Thermal granular reaction plan is invalid");
		}
		HashMap<Integer, ReactionMarker> replacements = new HashMap<>();
		for (ReactionMarker replacement : markerReplacements) {
			if (replacement.marker() >= markers.markerCount() || markers.cell(replacement.marker()) != cell
					|| replacements.put(replacement.marker(), replacement) != null) {
				throw new IllegalArgumentException("Thermal reaction marker set is not unique in its cell");
			}
		}
		ThermalCellGrid.CarrierAccess current = carrierAccess(markers);
		SignedDyadic cellEnergy = SignedDyadic.ZERO;
		for (int carrier = 0; carrier < current.carrierCount(); carrier++) if (current.cell(carrier) == cell) {
			cellEnergy = cellEnergy.add(SignedDyadic.of(current.internalEnergy(carrier)));
		}
		ArrayList<ThermalStateFunction.InventoryPortion> finalInventory = new ArrayList<>();
		for (int marker = 0; marker < markers.markerCount(); marker++) if (markers.cell(marker) == cell) {
			ReactionMarker replacement = replacements.remove(marker);
			double mass = replacement == null ? markers.mass(marker) : replacement.mass();
			ChemicalMixtureRecord mixture = replacement == null ? markers.mixture(marker) : replacement.mixture();
			finalInventory.add(new ThermalStateFunction.InventoryPortion(
					materialByFluidHandle[markers.fluidMaterialHandle(marker)], condensedMassScale.kilograms(mass),
					stableAmounts(mixture, markers.chemicalRegistry())));
		}
		if (!replacements.isEmpty()) throw new IllegalArgumentException("Thermal reaction names a missing liquid marker");
		for (CoherentPhaseStore.Group group : coherentPhases.snapshot()) if (group.cell() == cell) {
			finalInventory.add(new ThermalStateFunction.InventoryPortion(registry.materialHandle(group.phaseId()), group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts()));
		}
		boolean matchedGranular = granularEdit.kind() == GranularReactionKind.ADD_ACTIVE;
		for (int active = 0; active < markers.activeParcelCount(); active++) if (markers.activeParcelCell(active) == cell) {
			if (granularEdit.activeOrdinal() == active) {
				matchedGranular = true;
				if (granularEdit.kind() != GranularReactionKind.REMOVE_ACTIVE) addGranularPortion(
						finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(),
						granularEdit.finalComposition());
			} else addGranularPortion(finalInventory, markers.activeParcelMaterial(active),
					markers.activeParcelMass(active), markers.solidComposition(markers.activeParcelComposition(active)));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) if (key.cell() == cell) {
			if (key.equals(granularEdit.depositKey())) {
				matchedGranular = true;
				if (granularEdit.kind() != GranularReactionKind.REMOVE_DEPOSIT) addGranularPortion(
						finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(),
						granularEdit.finalComposition());
			} else addGranularPortion(finalInventory, key.materialHandle(), markers.depositMass(key),
					markers.solidComposition(key.compositionHandle()));
		}
		if (!matchedGranular) throw new IllegalArgumentException("Thermal reaction names a missing granular carrier");
		if (granularEdit.kind() == GranularReactionKind.ADD_ACTIVE) {
			addGranularPortion(finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(),
					granularEdit.finalComposition());
			granularEnergy.requireAddActive(0.0);
		}
		ThermalStateFunction.State state = stateFunction.stateForInventory(finalInventory, cellEnergy.toDouble());
		return new ReactionPlan(cell, cellEnergy, exactEnergy(markers.markerCount()), state.temperatureK());
	}

	void addGranularReactionActive() { granularEnergy.addActive(0.0); }
	double takeGranularReactionActive(int active, long takenSvq, long totalSvq) {
		return takenSvq == totalSvq ? granularEnergy.removeActiveSwap(active)
				: granularEnergy.takeActivePortion(active, takenSvq, totalSvq);
	}
	double takeGranularReactionDeposit(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return granularEnergy.takeDeposit(key, takenSvq, totalSvq);
	}
	void commitGranularReaction(ReactionPlan plan, MarkerContext markers) {
		if (plan == null) throw new IllegalArgumentException("Thermal granular reaction plan is missing");
		cellGrid.rebuildWithCellEnergy(carrierAccess(markers), plan.cell(), plan.cellEnergy().toDouble());
		double[] target = cellGrid.copyInternalEnergy();
		cellGrid.setCellInternalEnergies(target);
		audit = audit.withRoundoff(exactEnergy(markers.markerCount()).subtract(plan.inventoryBefore()));
	}

	PhaseMechanicalWorkPlan planPhaseMechanicalWork(
			SignedDyadic[] pendingCellVolume, float[] gaugePressurePa, float cellSize, MarkerContext markers
	) {
		if (pendingCellVolume == null || gaugePressurePa == null
				|| pendingCellVolume.length != cellGrid.cellCount()
				|| gaugePressurePa.length != cellGrid.cellCount()
				|| !(Float.isFinite(cellSize) && cellSize > 0.0F)) {
			throw new IllegalArgumentException("Pending phase mechanical-work input is invalid");
		}
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		double[] target = cellGrid.copyInternalEnergy();
		SignedDyadic work = SignedDyadic.ZERO;
		double cellVolumeM3 = (double) cellSize * cellSize * cellSize;
		for (int cell = 0; cell < pendingCellVolume.length; cell++) {
			SignedDyadic signedCellVolume = pendingCellVolume[cell];
			if (signedCellVolume == null) throw new IllegalArgumentException("Pending phase volume is missing");
			if (signedCellVolume.equals(SignedDyadic.ZERO)) continue;
			if (!cellGrid.occupied(cell)) {
				throw new IllegalArgumentException("Pending phase volume names an empty thermal cell");
			}
			double absolutePressure = com.example.fluid.air.AirNetwork.REFERENCE_PRESSURE
					+ gaugePressurePa[cell];
			if (!(Double.isFinite(absolutePressure) && absolutePressure >= 0.0)) {
				throw new IllegalArgumentException("Phase mechanical-work pressure is invalid");
			}
			double deltaVolumeM3 = signedCellVolume.toDouble() * cellVolumeM3;
			double cellWork = -absolutePressure * deltaVolumeM3;
			if (!Double.isFinite(cellWork) || !Double.isFinite(target[cell] + cellWork)) {
				throw new IllegalArgumentException("Phase mechanical work is not representable");
			}
			target[cell] += cellWork;
			work = work.add(SignedDyadic.of(cellWork));
		}
		cellGrid.validateCellInternalEnergies(target);
		return new PhaseMechanicalWorkPlan(target, before, work);
	}

	void requirePendingPhaseCells(SignedDyadic[] pendingCellVolume, MarkerContext markers) {
		if (pendingCellVolume == null || pendingCellVolume.length != cellGrid.cellCount()) {
			throw new IllegalArgumentException("Pending phase-volume cells are incompatible");
		}
		cellGrid.rebuild(carrierAccess(markers));
		for (int cell = 0; cell < pendingCellVolume.length; cell++) {
			if (pendingCellVolume[cell] == null) throw new IllegalArgumentException("Pending phase volume is missing");
			if (!pendingCellVolume[cell].equals(SignedDyadic.ZERO) && !cellGrid.occupied(cell)) {
				throw new IllegalArgumentException("Pending phase volume names an empty thermal cell");
			}
		}
	}

	void commitPhaseMechanicalWork(PhaseMechanicalWorkPlan plan, MarkerContext markers) {
		if (plan == null) throw new IllegalArgumentException("Phase mechanical-work plan is missing");
		cellGrid.setCellInternalEnergies(plan.targetCellEnergy());
		SignedDyadic actualDelta = exactEnergy(markers.markerCount()).subtract(plan.inventoryBefore());
		audit = audit.withMechanicalWork(plan.mechanicalWork())
				.withRoundoff(actualDelta.subtract(plan.mechanicalWork()));
	}

	double temperatureInCell(int cell, MarkerContext markers) {
		cellGrid.rebuild(carrierAccess(markers));
		return cellGrid.occupied(cell) ? cellGrid.temperature(cell) : Double.NaN;
	}

	/** Solves a prospective liquid/coherent phase edit while retaining every same-cell granular heat-bath carrier. */
	double phaseCandidateTemperature(
			int cell, List<ThermalStateFunction.InventoryPortion> phaseInventory, MarkerContext markers
	) {
		if (cell < 0 || cell >= cellGrid.cellCount() || phaseInventory == null || phaseInventory.isEmpty()) {
			throw new IllegalArgumentException("Thermal phase candidate inventory is invalid");
		}
		ThermalCellGrid.CarrierAccess current = carrierAccess(markers);
		SignedDyadic cellEnergy = SignedDyadic.ZERO;
		for (int carrier = 0; carrier < current.carrierCount(); carrier++) if (current.cell(carrier) == cell) {
			cellEnergy = cellEnergy.add(SignedDyadic.of(current.internalEnergy(carrier)));
		}
		return phaseCandidateState(cell, phaseInventory, cellEnergy, markers).temperatureK();
	}

	/** Validates a prospective gas/liquid edit against every unchanged same-cell solid heat-bath carrier. */
	double requirePhaseCandidateState(
			int cell, List<ThermalStateFunction.InventoryPortion> liquidInventory,
			double cellEnergyDelta, MarkerContext markers
	) {
		if (cell < 0 || cell >= cellGrid.cellCount() || liquidInventory == null
				|| !Double.isFinite(cellEnergyDelta)) {
			throw new IllegalArgumentException("Thermal gas/liquid candidate is invalid");
		}
		ThermalCellGrid.CarrierAccess current = carrierAccess(markers);
		SignedDyadic cellEnergy = SignedDyadic.of(cellEnergyDelta);
		for (int carrier = 0; carrier < current.carrierCount(); carrier++) if (current.cell(carrier) == cell) {
			cellEnergy = cellEnergy.add(SignedDyadic.of(current.internalEnergy(carrier)));
		}
		ArrayList<ThermalStateFunction.InventoryPortion> condensedInventory = new ArrayList<>(liquidInventory);
		for (CoherentPhaseStore.Group group : coherentPhases.snapshot()) if (group.cell() == cell) {
			condensedInventory.add(new ThermalStateFunction.InventoryPortion(
					registry.materialHandle(group.phaseId()), group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts()));
		}
		return phaseCandidateState(cell, condensedInventory, cellEnergy, markers).temperatureK();
	}

	private ThermalStateFunction.State phaseCandidateState(
			int cell, List<ThermalStateFunction.InventoryPortion> phaseInventory,
			SignedDyadic cellEnergy, MarkerContext markers
	) {
		ArrayList<ThermalStateFunction.InventoryPortion> finalInventory = new ArrayList<>(phaseInventory);
		for (int active = 0; active < markers.activeParcelCount(); active++) {
			if (markers.activeParcelCell(active) == cell) addGranularPortion(finalInventory,
					markers.activeParcelMaterial(active), markers.activeParcelMass(active),
					markers.solidComposition(markers.activeParcelComposition(active)));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) if (key.cell() == cell) {
			addGranularPortion(finalInventory, key.materialHandle(), markers.depositMass(key),
					markers.solidComposition(key.compositionHandle()));
		}
		if (finalInventory.isEmpty()) {
			double energy = cellEnergy.toDouble();
			if (Math.abs(energy) > Math.max(1.0E-9, Math.ulp(energy) * 16.0)) {
				throw new IllegalArgumentException("Empty phase cell retains internal energy");
			}
			return null;
		}
		return stateFunction.stateForInventory(finalInventory, cellEnergy.toDouble());
	}

	int thermalMaterialHandleForFluid(int fluidHandle) {
		if (fluidHandle < 0 || fluidHandle >= materialByFluidHandle.length) {
			throw new IllegalArgumentException("Unknown liquid phase carrier");
		}
		return materialByFluidHandle[fluidHandle];
	}

	ThermalDisplaySnapshot displaySnapshot(MarkerContext markers, AirNetwork network) {
		cellGrid.rebuild(carrierAccess(markers));
		short[] temperatures = new short[cellGrid.cellCount()];
		byte[] coherent = new byte[cellGrid.cellCount()];
		byte[] flags = new byte[cellGrid.cellCount()];
		java.util.Arrays.fill(temperatures, (short) ThermalDisplaySnapshot.EMPTY_TEMPERATURE_Q);
		for (int cell = 0; cell < cellGrid.cellCount(); cell++) {
			if (cellGrid.occupied(cell)) {
				temperatures[cell] = (short) quantizedTemperature(cellGrid.temperature(cell));
				flags[cell] |= ThermalDisplaySnapshot.FLAG_CONDENSED;
			}
			long solid = coherentPhases.cellOccupiedSvq(cell);
			if (solid > 0L) {
				coherent[cell] = (byte) Math.min(255L,
						(solid * 255L + com.example.granular.SolidVolume.SVQ_PER_CELL / 2L)
								/ com.example.granular.SolidVolume.SVQ_PER_CELL);
				flags[cell] |= ThermalDisplaySnapshot.FLAG_COHERENT;
			}
		}
		java.util.ArrayList<ThermalDisplaySnapshot.GasNode> gas = new java.util.ArrayList<>(network.nodeCount());
		for (int node : network.nodeIds()) gas.add(new ThermalDisplaySnapshot.GasNode(node,
				quantizedTemperature(gasTemperature(node, network)),
				Math.toIntExact(Math.round(gasGaugePressure(node, network) / 10.0))));
		return new ThermalDisplaySnapshot(temperatures, coherent, flags, gas);
	}

	private static int quantizedTemperature(double temperatureK) {
		long quantized = Math.round(temperatureK * 16.0);
		if (quantized <= 0L || quantized >= ThermalDisplaySnapshot.EMPTY_TEMPERATURE_Q) {
			throw new IllegalArgumentException("Thermal display temperature is outside the v5 palette");
		}
		return Math.toIntExact(quantized);
	}

	void setTemperatureInCell(int cell, double temperatureK, MarkerContext markers) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		if (!cellGrid.occupied(cell)) throw new IllegalArgumentException("Cannot set temperature in an empty cell");
		double[] target = cellGrid.copyInternalEnergy();
		target[cell] = modeledCellEnergy(cell, temperatureK, markers);
		cellGrid.setCellInternalEnergies(target);
		audit = audit.withHeatExchange(exactEnergy(markers.markerCount()).subtract(before));
	}

	void resetMarkerTemperature(int marker, double temperatureK, MarkerContext markers, SignedDyadic inventoryBefore) {
		internalEnergy[marker] = modeledEnergy(marker, markers.mass(marker), markers.mixture(marker), temperatureK, markers);
		audit = audit.withHeatExchange(exactEnergy(markers.markerCount()).subtract(inventoryBefore));
	}

	ThermalTransport.Stats step(
			MarkerContext markers, ThermalTransport.FaceArea faceArea, double dtSeconds
	) {
		return step(markers, faceArea, (x, y, z, direction) -> null, dtSeconds);
	}

	ThermalTransport.Stats step(
			MarkerContext markers, ThermalTransport.FaceArea faceArea,
			ThermalTransport.BoundaryFace boundaryFace, double dtSeconds
	) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		lastTransport = transport.step(cellGrid, faceArea, boundaryFace, dtSeconds);
		SignedDyadic physicalDelta = exactEnergy(markers.markerCount()).subtract(before);
		audit = audit.withHeatExchange(lastTransport.reservoirExchange())
				.withRoundoff(physicalDelta.subtract(lastTransport.reservoirExchange()));
		return lastTransport;
	}

	void addExternalMarker(int marker, MarkerContext markers) {
		ensureCapacity(marker + 1);
		internalEnergy[marker] = modeledEnergy(marker, markers.mass(marker), markers.mixture(marker),
				insertionTemperatureK, markers);
		audit = audit.withExternalSourceEnergy(SignedDyadic.of(internalEnergy[marker]));
	}

	ExternalLiquidInsertionPlan planExternalLiquidInsertions(
			List<LiquidInsertion> insertions, double totalEnergy, MarkerContext markers
	) {
		if (insertions == null || insertions.isEmpty() || !Double.isFinite(totalEnergy)) {
			throw new IllegalArgumentException("Explicit thermal liquid insertion is invalid");
		}
		ArrayList<ThermalStateFunction.InventoryPortion> inventory = new ArrayList<>(insertions.size());
		for (LiquidInsertion insertion : insertions) {
			if (insertion.fluidMaterialHandle() >= materialByFluidHandle.length) {
				throw new IllegalArgumentException("Unknown fluid material in thermal insertion");
			}
			inventory.add(new ThermalStateFunction.InventoryPortion(
					materialByFluidHandle[insertion.fluidMaterialHandle()],
					condensedMassScale.kilograms(insertion.mass()),
					stableAmounts(insertion.mixture(), markers.chemicalRegistry())));
		}
		double temperatureK = stateFunction.stateForInventory(inventory, totalEnergy).temperatureK();
		double[] energies = new double[insertions.size()];
		for (int index = 0; index < energies.length; index++) {
			LiquidInsertion insertion = insertions.get(index);
			energies[index] = modeledEnergy(insertion.fluidMaterialHandle(), insertion.mass(),
					insertion.mixture(), temperatureK, markers.chemicalRegistry());
		}
		SignedDyadic source = SignedDyadic.of(totalEnergy);
		return new ExternalLiquidInsertionPlan(energies, source, SignedDyadic.sum(energies).subtract(source));
	}

	void commitExternalLiquidInsertions(int firstOrdinal, ExternalLiquidInsertionPlan plan) {
		if (plan == null || firstOrdinal < 0 || firstOrdinal + plan.energies.length > internalEnergy.length) {
			throw new IllegalArgumentException("Thermal liquid insertion ordinals are incompatible");
		}
		for (int index = 0; index < plan.energies.length; index++) {
			int ordinal = firstOrdinal + index;
			if (Double.doubleToRawLongBits(internalEnergy[ordinal]) != Double.doubleToRawLongBits(0.0)) {
				throw new IllegalStateException("Explicit thermal insertion did not reserve an empty energy ordinal");
			}
			internalEnergy[ordinal] = plan.energies[index];
		}
		audit = audit.withExternalSourceEnergy(plan.source()).withRoundoff(plan.roundoff());
	}

	void removeExternalAndSwap(int index, int last) {
		audit = audit.withExternalSinkEnergy(SignedDyadic.of(internalEnergy[index]));
		if (index != last) internalEnergy[index] = internalEnergy[last];
	}

	void recordExternalSink(SignedDyadic energy) { audit = audit.withExternalSinkEnergy(energy); }
	void settleExternalExtraction(double removedCarrierEnergy, double coupledItemEnergy) {
		if (!Double.isFinite(removedCarrierEnergy) || !Double.isFinite(coupledItemEnergy)) {
			throw new IllegalArgumentException("Container thermal settlement is not finite");
		}
		SignedDyadic delta = SignedDyadic.of(coupledItemEnergy).subtract(SignedDyadic.of(removedCarrierEnergy));
		audit = audit.withExternalSinkEnergy(delta).withRoundoff(delta);
	}
	void recordRoundoff(SignedDyadic delta) { audit = audit.withRoundoff(delta); }

	void addCoherentExternal(CoherentPhaseStore.Group group) {
		int material = registry.materialHandle(group.phaseId());
		if (registry.material(material).carrierKind() != ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID) {
			throw new IllegalArgumentException("Coherent group phase is not a coherent-solid thermal material");
		}
		Map<String, Long> composition = group.composition() == null ? Map.of() : group.composition().stableAmounts();
		stateFunction.stateForInternalEnergy(material, group.mass(), composition, group.internalEnergy());
		coherentPhases.add(group);
		audit = audit.withExternalSourceEnergy(SignedDyadic.of(group.internalEnergy()));
	}

	void addCoherentInternal(CoherentPhaseStore.Group group) { coherentPhases.add(group); }
	CoherentPhaseStore.Group takeCoherentInternal(int cell, String phaseId, long occupiedSvq) {
		return coherentPhases.take(cell, phaseId, occupiedSvq);
	}
	void clearCoherentExternal(List<CoherentPhaseStore.Group> expected, SignedDyadic expectedEnergy) {
		if (expected == null || expectedEnergy == null || !coherentPhases.snapshot().equals(expected)
				|| !coherentPhases.exactEnergy().equals(expectedEnergy)) {
			throw new IllegalStateException("Coherent phase inventory changed after clear planning");
		}
		coherentPhases.clear();
		audit = audit.withExternalSinkEnergy(expectedEnergy);
	}
	void addInternalMarker(int marker, double energy) {
		ensureCapacity(marker + 1);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Internal phase marker energy is not finite");
		internalEnergy[marker] = energy;
	}
	double removeInternalAndSwap(int index, int last) {
		double removed = internalEnergy[index];
		if (index != last) internalEnergy[index] = internalEnergy[last];
		return removed;
	}
	void addLiquidEnergy(int marker, double energy) {
		double result = internalEnergy[marker] + energy;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Internal phase energy transfer overflow");
		internalEnergy[marker] = result;
	}
	void repartitionCellEnergy(int cell, double targetEnergy, MarkerContext markers, SignedDyadic inventoryBefore) {
		cellGrid.rebuild(carrierAccess(markers));
		double[] target = cellGrid.copyInternalEnergy();
		target[cell] = targetEnergy;
		cellGrid.setCellInternalEnergies(target);
		audit = audit.withRoundoff(exactEnergy(markers.markerCount()).subtract(inventoryBefore));
	}

	/**
	 * Closes an inventory-preserving structural commit by assigning the exact pre-commit remainder to one cell.
	 * Gas and non-target carrier energy are already authoritative when this is called; the cell remains the LTE
	 * degree of freedom. This avoids losing a physically meaningful amount through rounded local-energy arithmetic.
	 */
	void repartitionCellEnergyConserving(int cell, MarkerContext markers, SignedDyadic inventoryBefore) {
		ThermalCellGrid.CarrierAccess current = carrierAccess(markers);
		SignedDyadic currentCellEnergy = SignedDyadic.ZERO;
		for (int carrier = 0; carrier < current.carrierCount(); carrier++) if (current.cell(carrier) == cell) {
			currentCellEnergy = currentCellEnergy.add(SignedDyadic.of(current.internalEnergy(carrier)));
		}
		SignedDyadic energyOutsideCell = exactEnergy(markers.markerCount()).subtract(currentCellEnergy);
		double targetCellEnergy = inventoryBefore.subtract(energyOutsideCell).toDouble();
		if (!Double.isFinite(targetCellEnergy)) {
			throw new IllegalArgumentException("Conservative thermal cell remainder is not representable");
		}
		cellGrid.rebuildWithCellEnergy(current, cell, targetCellEnergy);
		double[] target = cellGrid.copyInternalEnergy();
		cellGrid.setCellInternalEnergies(target);
		audit = audit.withRoundoff(exactEnergy(markers.markerCount()).subtract(inventoryBefore));
	}

	void requireActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		granularEnergy.requireAddDeposit(target, granularEnergy.activeEnergy(active));
	}
	void commitActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		SignedDyadic before = granularEnergy.exactEnergy();
		double energy = granularEnergy.removeActiveSwap(active);
		granularEnergy.addDeposit(target, energy);
		audit = audit.withRoundoff(granularEnergy.exactEnergy().subtract(before));
	}
	void requireActiveMerge(int first, int second) { granularEnergy.requireMergeActive(first, second); }
	void commitActiveMerge(int first, int second) {
		SignedDyadic before = granularEnergy.exactEnergy();
		granularEnergy.mergeActive(first, second);
		audit = audit.withRoundoff(granularEnergy.exactEnergy().subtract(before));
	}
	void requireDepositWake(GranularEnergyColumn.DepositKey source, long takenSvq, long totalSvq) {
		granularEnergy.previewDepositTake(source, takenSvq, totalSvq);
		granularEnergy.requireAddActive(0.0);
	}
	void commitDepositWake(GranularEnergyColumn.DepositKey source, long takenSvq, long totalSvq) {
		SignedDyadic before = granularEnergy.exactEnergy();
		double energy = granularEnergy.takeDeposit(source, takenSvq, totalSvq);
		granularEnergy.addActive(energy);
		audit = audit.withRoundoff(granularEnergy.exactEnergy().subtract(before));
	}
	void requireDepositMove(
			GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedSvq, long sourceTotalSvq
	) {
		double moved = granularEnergy.previewDepositTake(source, movedSvq, sourceTotalSvq);
		granularEnergy.requireAddDeposit(target, moved);
	}
	void commitDepositMove(
			GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedSvq, long sourceTotalSvq
	) {
		SignedDyadic before = granularEnergy.exactEnergy();
		granularEnergy.moveDeposit(source, target, movedSvq, sourceTotalSvq);
		audit = audit.withRoundoff(granularEnergy.exactEnergy().subtract(before));
	}
	void clearGranularExternal() {
		SignedDyadic removed = granularEnergy.clear();
		audit = audit.withExternalSinkEnergy(removed);
	}
	double[] planExternalActiveInsertions(int firstOrdinal, int count, MarkerContext markers) {
		if (firstOrdinal != granularEnergy.activeCount() || count < 0
				|| firstOrdinal + count != markers.activeParcelCount()) {
			throw new IllegalArgumentException("Granular thermal insertion ordinals are incompatible");
		}
		double[] energies = new double[count];
		for (int index = 0; index < count; index++) {
			int ordinal = firstOrdinal + index;
			energies[index] = modeledGranularEnergy(markers.activeParcelMaterial(ordinal),
					markers.activeParcelComposition(ordinal), markers.activeParcelMass(ordinal),
					insertionTemperatureK, markers);
			granularEnergy.requireAddActive(energies[index]);
		}
		return energies;
	}
	double planExternalGranularEnergy(
			int material, int composition, double mass, MarkerContext markers
	) {
		double energy = modeledGranularEnergy(material, composition, mass, insertionTemperatureK, markers);
		granularEnergy.requireAddActive(energy);
		return energy;
	}
	void commitExternalActiveInsertions(double[] energies) {
		if (energies == null) throw new IllegalArgumentException("Granular thermal insertion plan is missing");
		SignedDyadic source = SignedDyadic.ZERO;
		for (double energy : energies) {
			granularEnergy.addActive(energy); source = source.add(SignedDyadic.of(energy));
		}
		audit = audit.withExternalSourceEnergy(source);
	}
	double previewActiveTake(int active, long takenSvq, long totalSvq) {
		return granularEnergy.previewActiveTake(active, takenSvq, totalSvq);
	}
	double previewDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return granularEnergy.previewDepositTake(key, takenSvq, totalSvq);
	}
	double commitExternalActiveTake(int active, long takenSvq, long totalSvq) {
		double removed = takenSvq == totalSvq ? granularEnergy.removeActiveSwap(active)
				: granularEnergy.takeActivePortion(active, takenSvq, totalSvq);
		audit = audit.withExternalSinkEnergy(SignedDyadic.of(removed));
		return removed;
	}
	double commitExternalDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		double removed = granularEnergy.takeDeposit(key, takenSvq, totalSvq);
		audit = audit.withExternalSinkEnergy(SignedDyadic.of(removed));
		return removed;
	}

	Truncation planTruncate(
			int marker, double newMass, ChemicalMixtureRecord keptMixture,
			double previousTemperatureK, MarkerContext markers
	) {
		double previousEnergy = internalEnergy[marker];
		double newEnergy = newMass == 0.0 ? 0.0
				: modeledEnergy(marker, newMass, keptMixture, previousTemperatureK, markers);
		return new Truncation(newEnergy, previousEnergy - newEnergy);
	}

	void commitTruncate(int marker, Truncation plan) {
		if (plan == null) return;
		internalEnergy[marker] = plan.newEnergy();
		audit = audit.withExternalSinkEnergy(SignedDyadic.of(plan.removedEnergy()));
	}

	void commitInternalTruncate(int marker, Truncation plan) {
		if (plan == null) return;
		internalEnergy[marker] = plan.newEnergy();
	}

	FlipParticleReseeder.ThermalAdapter reseedAdapter(MarkerContext markers) {
		return new FlipParticleReseeder.ThermalAdapter() {
			@Override public double totalEnergy(int count) { return FlipThermalState.this.totalEnergy(count); }
			@Override public void merge(int target, int source) {
				internalEnergy[target] += internalEnergy[source];
				if (!Double.isFinite(internalEnergy[target])) {
					throw new IllegalArgumentException("Reseeded thermal merge energy is invalid");
				}
			}
			@Override public FlipParticleReseeder.ThermalSplit beforeSplit(int source) {
				return new FlipParticleReseeder.ThermalSplit(internalEnergy[source], temperature(source, markers));
			}
			@Override public void afterSplit(FlipParticleReseeder.ThermalSplit split, int source, int destination) {
				internalEnergy[source] = modeledEnergy(source, markers.mass(source), markers.mixture(source),
						split.temperatureK(), markers);
				internalEnergy[destination] = split.parentEnergy() - internalEnergy[source];
				validateTemperature(destination, split.temperatureK(), markers);
			}
			@Override public void move(int source, int destination) { internalEnergy[destination] = internalEnergy[source]; }
		};
	}

	private ThermalCellGrid.CarrierAccess carrierAccess(MarkerContext markers) {
		int liquidCount = markers.markerCount(), coherentCount = coherentPhases.groupCount();
		int activeCount = markers.activeParcelCount();
		List<GranularEnergyColumn.DepositKey> depositKeys = markers.depositKeys();
		if (activeCount != granularEnergy.activeCount()
				|| !depositKeys.equals(granularEnergy.depositSnapshot().stream()
				.map(GranularEnergyColumn.DepositEnergy::key).toList())) {
			throw new IllegalStateException("Granular thermal ordinals differ from M8 inventory");
		}
		int coherentStart = liquidCount, activeStart = Math.addExact(coherentStart, coherentCount);
		int depositStart = Math.addExact(activeStart, activeCount);
		return new ThermalCellGrid.CarrierAccess() {
			@Override public int carrierCount() { return Math.addExact(depositStart, depositKeys.size()); }
			@Override public int cell(int carrier) {
				if (carrier < liquidCount) return markers.cell(carrier);
				if (carrier < activeStart) return coherentPhases.group(carrier - coherentStart).cell();
				if (carrier < depositStart) return markers.activeParcelCell(carrier - activeStart);
				return depositKeys.get(carrier - depositStart).cell();
			}
			@Override public ThermalMaterialDefinition material(int carrier) {
				if (carrier < liquidCount) return FlipThermalState.this.material(carrier, markers);
				if (carrier < activeStart) return registry.material(registry.materialHandle(
						coherentPhases.group(carrier - coherentStart).phaseId()));
				int material = carrier < depositStart ? markers.activeParcelMaterial(carrier - activeStart)
						: depositKeys.get(carrier - depositStart).materialHandle();
				return registry.material(materialByGranularHandle[material]);
			}
			@Override public double mass(int carrier) {
				if (carrier < liquidCount) return condensedMassScale.kilograms(markers.mass(carrier));
				if (carrier < activeStart) return coherentPhases.group(carrier - coherentStart).mass();
				return condensedMassScale.kilograms(carrier < depositStart
						? markers.activeParcelMass(carrier - activeStart)
						: markers.depositMass(depositKeys.get(carrier - depositStart)));
			}
			@Override public double volume(int carrier) {
				if (carrier < liquidCount) return markers.volume(carrier);
				if (carrier < activeStart) return SolidVolume.toCellVolume(
						coherentPhases.group(carrier - coherentStart).occupiedVolumeSvq());
				return SolidVolume.toCellVolume(carrier < depositStart
						? markers.activeParcelSvq(carrier - activeStart)
						: markers.depositSvq(depositKeys.get(carrier - depositStart)));
			}
			@Override public double baseEnergy(int carrier) {
				if (carrier < liquidCount) return FlipThermalState.this.baseEnergy(carrier, markers);
				if (carrier < activeStart) {
					CoherentPhaseStore.Group group = coherentPhases.group(carrier - coherentStart);
					return stateFunction.baseEnergy(registry.materialHandle(group.phaseId()), group.mass(),
							group.composition() == null ? Map.of() : group.composition().stableAmounts());
				}
				int material, composition; double mass;
				if (carrier < depositStart) {
					int parcel = carrier - activeStart;
					material = markers.activeParcelMaterial(parcel); composition = markers.activeParcelComposition(parcel);
					mass = markers.activeParcelMass(parcel);
				} else {
					GranularEnergyColumn.DepositKey key = depositKeys.get(carrier - depositStart);
					material = key.materialHandle(); composition = key.compositionHandle(); mass = markers.depositMass(key);
				}
				return stateFunction.baseEnergy(materialByGranularHandle[material],
						condensedMassScale.kilograms(mass),
						markers.solidComposition(composition));
			}
			@Override public double internalEnergy(int carrier) {
				if (carrier < liquidCount) return internalEnergy[carrier];
				if (carrier < activeStart) return coherentPhases.group(carrier - coherentStart).internalEnergy();
				return carrier < depositStart ? granularEnergy.activeEnergy(carrier - activeStart)
						: granularEnergy.depositEnergy(depositKeys.get(carrier - depositStart));
			}
			@Override public void setInternalEnergy(int carrier, double energy) {
				if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal carrier energy is not finite");
				if (carrier < liquidCount) internalEnergy[carrier] = energy;
				else if (carrier < activeStart) coherentPhases.setInternalEnergy(carrier - coherentStart, energy);
				else if (carrier < depositStart) granularEnergy.setActiveEnergy(carrier - activeStart, energy);
				else granularEnergy.setDepositEnergy(depositKeys.get(carrier - depositStart), energy);
			}
		};
	}

	private double modeledCellEnergy(int cell, double temperatureK, MarkerContext markers) {
		double total = 0.0;
		for (int marker = 0; marker < markers.markerCount(); marker++) if (markers.cell(marker) == cell) {
			total += modeledEnergy(marker, markers.mass(marker), markers.mixture(marker), temperatureK, markers);
		}
		for (CoherentPhaseStore.Group group : coherentPhases.snapshot()) if (group.cell() == cell) {
			int material = registry.materialHandle(group.phaseId());
			total += stateFunction.internalEnergyAtTemperature(material, group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts(), temperatureK);
		}
		for (int parcel = 0; parcel < markers.activeParcelCount(); parcel++) {
			if (markers.activeParcelCell(parcel) == cell) total += modeledGranularEnergy(
					markers.activeParcelMaterial(parcel), markers.activeParcelComposition(parcel),
					markers.activeParcelMass(parcel), temperatureK, markers);
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) if (key.cell() == cell) {
			total += modeledGranularEnergy(key.materialHandle(), key.compositionHandle(),
					markers.depositMass(key), temperatureK, markers);
		}
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal cell modeled energy is invalid");
		return total;
	}

	private ThermalMaterialDefinition material(int marker, MarkerContext markers) {
		return registry.material(materialByFluidHandle[markers.fluidMaterialHandle(marker)]);
	}

	private double baseEnergy(int marker, MarkerContext markers) {
		return stateFunction.baseEnergy(materialByFluidHandle[markers.fluidMaterialHandle(marker)],
				condensedMassScale.kilograms(markers.mass(marker)),
				markers.mixture(marker), markers.chemicalRegistry());
	}

	private double modeledEnergy(
			int marker, double mass, ChemicalMixtureRecord mixture, double temperatureK, MarkerContext markers
	) {
		return modeledEnergy(markers.fluidMaterialHandle(marker), mass, mixture, temperatureK,
				markers.chemicalRegistry());
	}

	private double modeledEnergy(
			int fluidMaterialHandle, double mass, ChemicalMixtureRecord mixture, double temperatureK,
			ChemicalRegistry chemicalRegistry
	) {
		int thermalHandle = materialByFluidHandle[fluidMaterialHandle];
		ThermalMaterialDefinition material = registry.material(thermalHandle);
		double physicalMass = condensedMassScale.kilograms(mass);
		double base = stateFunction.baseEnergy(thermalHandle, physicalMass, mixture, chemicalRegistry);
		double energy = base + physicalMass * material.sensibleSpecificEnergyAt(temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal marker modeled energy is invalid");
		return energy;
	}

	private double modeledGranularEnergy(
			int granularMaterial, int composition, double mass, double temperatureK, MarkerContext markers
	) {
		if (granularMaterial < 0 || granularMaterial >= materialByGranularHandle.length) {
			throw new IllegalArgumentException("Unknown granular thermal material handle");
		}
		int thermalHandle = materialByGranularHandle[granularMaterial];
		ThermalMaterialDefinition material = registry.material(thermalHandle);
		double physicalMass = condensedMassScale.kilograms(mass);
		double energy = stateFunction.baseEnergy(thermalHandle, physicalMass, markers.solidComposition(composition))
				+ physicalMass * material.sensibleSpecificEnergyAt(temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Granular modeled energy is invalid");
		return energy;
	}

	private void addGranularPortion(
			List<ThermalStateFunction.InventoryPortion> inventory, int granularMaterial,
			double mass, Map<String, Long> composition
	) {
		if (granularMaterial < 0 || granularMaterial >= materialByGranularHandle.length) {
			throw new IllegalArgumentException("Unknown granular reaction thermal material");
		}
		inventory.add(new ThermalStateFunction.InventoryPortion(
				materialByGranularHandle[granularMaterial], condensedMassScale.kilograms(mass), composition));
	}

	private static Map<String, Long> stableAmounts(
			ChemicalMixtureRecord mixture, ChemicalRegistry chemicalRegistry
	) {
		if (mixture == null) return Map.of();
		if (chemicalRegistry == null) throw new IllegalArgumentException("Thermal chemical registry is missing");
		TreeMap<String, Long> result = new TreeMap<>();
		for (int entry = 0; entry < mixture.entryCount(); entry++) result.put(
				chemicalRegistry.species(mixture.speciesHandle(entry)).id(), mixture.amountAt(entry));
		return Map.copyOf(result);
	}

	private void validateTemperature(int marker, double expected, MarkerContext markers) {
		double restored = temperature(marker, markers);
		double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(restored), Math.ulp(expected)) * 16.0);
		if (Math.abs(restored - expected) > tolerance) {
			throw new IllegalArgumentException("Reseeded thermal split left local equilibrium");
		}
	}
}
