package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.thermal.FiniteDeviceExchangeCoordinator;
import cc.ecisr.mmm.thermal.FiniteThermalExchange;
import cc.ecisr.mmm.thermal.GasEnergyStore;
import cc.ecisr.mmm.thermal.GasLiquidPhaseModel;
import cc.ecisr.mmm.thermal.GranularEnergyColumn;
import cc.ecisr.mmm.thermal.GranularEnergyState;
import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.thermal.ThermalCellGrid;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.thermal.ThermalPhaseChangeEngine;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.thermal.ThermalTransport;

import java.util.List;
import java.util.Map;
import java.util.List;

/**
 * Package-private facade over {@link ThermalDomainState}, the single thermal aggregate.
 * Every method is one delegation; capture/restore and the reseed adapter stay here because
 * they are bound to fluid-side persistence and reseeder types. The facade is deleted once
 * the domain backend migrates off {@link FlipFluidDomain}.
 */
final class FlipThermalState {
	FlipThermalState(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			ThermalCarrierContext markers
	) {
		this(registry, liquids, initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ, cellSize, markers, null);
	}

	FlipThermalState(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			ThermalCarrierContext markers, AirNetwork airNetwork
	) {
		this(registry, liquids, initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ, cellSize,
				markers, airNetwork, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}

	FlipThermalState(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			ThermalCarrierContext markers, AirNetwork airNetwork, int maximumTransportSubsteps
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
			if (initialTemperatureK < material.minimumTemperatureK()
					|| initialTemperatureK > material.maximumTemperatureK()) {
				throw new IllegalArgumentException("Initial temperature is outside " + material.id());
			}
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
			if (initialTemperatureK < material.minimumTemperatureK()
					|| initialTemperatureK > material.maximumTemperatureK()) {
				throw new IllegalArgumentException("Initial temperature is outside " + material.id());
			}
			materialByGranularHandle[handle] = thermalHandle;
		}
		this.domain = new ThermalDomainState(registry, materialByFluidHandle, materialByGranularHandle,
				initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ, cellSize, markers,
				airNetwork, maximumTransportSubsteps);
	}

	private final ThermalDomainState domain;
	String fingerprint() { return domain.registry().fingerprint(); }
	ThermalAuditLedger audit() { return domain.auditLedger(); }
	CoherentPhaseStore coherentPhases() { return domain.coherentPhases(); }
	GranularEnergyColumn granularEnergy() { return domain.granularColumn(); }
	ThermalRegistry registry() { return domain.registry(); }
	ThermalCellGrid.TemperatureRange observedCarrierTemperatureRange() {
		return domain.observedCarrierTemperatureRange();
	}
	ThermalPhaseChangeEngine phaseChange() { return domain.phaseChange(); }
	GasLiquidPhaseModel gasLiquidPhase(AirNetwork network) { return domain.gasLiquidPhase(network); }
	long thermalRevision() { return domain.thermalRevision(); }

	double liquidInternalEnergy(int marker) { return domain.liquidInternalEnergy(marker); }
	ThermalTransport.Stats lastTransport() { return domain.lastTransport(); }
	int maximumTransportSubsteps() { return domain.maximumTransportSubsteps(); }
	double totalEnergy(int count) { return domain.totalEnergy(count); }
	SignedDyadic exactEnergy(int count) { return domain.exactEnergy(count); }
	SignedDyadic exactGasEnergy() { return domain.exactGasEnergy(); }
	SignedDyadic exactLiquidEnergy(int count) { return domain.exactLiquidEnergy(count); }
	void ensureCapacity(int capacity) { domain.ensureCapacity(capacity); }

	FlipThermalParticleState capture(int sizeX, int sizeY, int sizeZ, float cellSize, int count) {
		return new FlipThermalParticleState(sizeX, sizeY, sizeZ, cellSize, count, domain.registry().fingerprint(),
				domain.liquid().copyEnergies(count), domain.granularColumn().copyActiveEnergy(),
				domain.granularColumn().depositSnapshot().stream()
						.mapToDouble(GranularEnergyColumn.DepositEnergy::internalEnergy).toArray(),
				domain.coherentPhases().snapshot(), domain.gas().nodeEnergies(), domain.audit().ledger());
	}

	void restore(FlipThermalParticleState snapshot, ThermalCarrierContext markers, AirNetwork airNetwork) {
		if (domain.coherentPhases().groupCount() != 0) throw new IllegalStateException("Thermal restore target is not empty");
		double[] restored = snapshot.copyInternalEnergy();
		domain.restoreLiquidEnergies(restored);
		for (CoherentPhaseStore.Group group : snapshot.coherentGroups()) {
			int material = domain.registry().materialHandle(group.phaseId());
			if (domain.registry().material(material).carrierKind() != ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID) {
				throw new IllegalArgumentException("Restored coherent phase has the wrong thermal carrier kind");
			}
			domain.stateFunction().stateForInternalEnergy(material, group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts(),
					group.internalEnergy());
		}
		domain.restoreThermalCoherent(snapshot.coherentGroups());
		double[] activeEnergy = snapshot.copyActiveParcelEnergy(), depositEnergy = snapshot.copyDepositEnergy();
		List<GranularEnergyColumn.DepositKey> depositKeys = markers.depositKeys();
		if (activeEnergy.length != markers.activeParcelCount() || depositEnergy.length != depositKeys.size()) {
			throw new IllegalArgumentException("Restored granular thermal ordinals differ from granular inventory");
		}
		java.util.ArrayList<GranularEnergyColumn.DepositEnergy> deposits = new java.util.ArrayList<>(depositEnergy.length);
		for (int index = 0; index < depositEnergy.length; index++) {
			deposits.add(new GranularEnergyColumn.DepositEnergy(depositKeys.get(index), depositEnergy[index]));
		}
		domain.restoreThermalGranular(activeEnergy, deposits);
		double[] restoredGas = snapshot.copyGasNodeEnergy();
		if (restoredGas.length != (airNetwork == null ? 0 : airNetwork.nodeCount())
				|| restoredGas.length > 0 && !domain.gas().gasModelBound()) {
			throw new IllegalArgumentException("Restored gas thermal ordinals differ from gas inventory");
		}
		domain.restoreGasEnergies(airNetwork, restoredGas);
		for (int marker = 0; marker < markers.markerCount(); marker++) temperature(marker, markers);
		domain.preparePhaseScan(markers);
		domain.restoreAuditLedger(snapshot.audit());
		if (!domain.exactEnergy(markers.markerCount()).equals(domain.audit().ledger().expectedInventory())) {
			throw new IllegalArgumentException("Restored thermal audit does not close");
		}
	}

	boolean hasGasEnergy(int nodeCount) { return domain.hasGasEnergy(nodeCount); }
	boolean gasModelBound() { return domain.gasModelBound(); }
	double gasNodeEnergy(int nodeId, AirNetwork network) { return domain.gasNodeEnergy(nodeId, network); }
	double gasTemperature(int nodeId, AirNetwork network) { return domain.gasTemperature(nodeId, network); }
	double gasGaugePressure(int nodeId, AirNetwork network) { return domain.gasGaugePressure(nodeId, network); }
	double gasDensity(int nodeId, AirNetwork network) { return domain.gasDensity(nodeId, network); }
	double requireGasNodeState(int nodeId, GasInventory inventory, double energy, AirNetwork network) {
		return domain.requireGasNodeState(nodeId, inventory, energy, network);
	}
	void commitGasNodeEnergy(int nodeId, double energy) { domain.commitGasNodeEnergy(nodeId, energy); }
	double[] partitionGasNodeEnergy(int nodeId, List<GasInventory> children, AirNetwork network) {
		return domain.partitionGasNodeEnergy(nodeId, children, network);
	}
	double ambientGasEnergy(GasInventory inventory) { return domain.ambientGasEnergy(inventory); }
	GasEnergyStore.GasRemapPlan planGasRemap(
			AirNetwork oldNetwork, List<GasInventory> inventories, double[] availableVolumes,
			double[] mixedEnergy, double[] inheritedAvailableVolumes,
			double sourceEnergy, double sinkEnergy
	) {
		return domain.planGasRemap(oldNetwork, inventories, availableVolumes, mixedEnergy,
				inheritedAvailableVolumes, sourceEnergy, sinkEnergy);
	}
	void requireCanCommitGasRemap(GasEnergyStore.GasRemapPlan plan) { domain.requireCanCommitGasRemap(plan); }
	void commitGasRemap(GasEnergyStore.GasRemapPlan plan) { domain.commitGasRemap(plan); }
	void requireCanCommitOperatorClear(
			long baseRevision, SignedDyadic liquidEnergy, SignedDyadic coherentEnergy,
			SignedDyadic granularEnergy, GasEnergyStore.GasRemapPlan gasPlan
	) {
		domain.requireCanCommitOperatorClear(
				baseRevision, liquidEnergy, coherentEnergy, granularEnergy, gasPlan);
	}
	GasEnergyStore.GasVentPlan planGasVent(AirNetwork network, int markerCount) {
		return domain.planGasVent(network, markerCount);
	}
	void commitGasVent(GasEnergyStore.GasVentPlan plan) { domain.commitGasVent(plan); }
	AirNetwork.TransferCommit planGasTransfer(
			int sourceNode, int targetNode, GasInventory moved,
			GasInventory sourceAfter, GasInventory targetAfter, AirNetwork network
	) {
		return domain.planGasTransfer(sourceNode, targetNode, moved, sourceAfter, targetAfter, network);
	}

	double temperature(int marker, ThermalCarrierContext markers) { return domain.temperature(marker, markers); }
	void requireReactionState(
			int marker, ChemicalMixtureRecord candidateMixture, double candidateMass, ThermalCarrierContext markers
	) {
		domain.requireReactionState(marker, candidateMixture, candidateMass, markers);
	}
	ThermalDomainState.ReactionPlan requireGranularReaction(
			int cell, List<GranularEnergyState.ReactionMarker> markerReplacements,
			GranularEnergyState.GranularReactionEdit granularEdit,
			ThermalCarrierContext markers
	) {
		return domain.requireGranularReaction(cell, markerReplacements, granularEdit, markers);
	}
	void addGranularReactionActive() { domain.addGranularReactionActive(); }
	double takeGranularReactionActive(int active, long takenSvq, long totalSvq) {
		return domain.takeGranularReactionActive(active, takenSvq, totalSvq);
	}
	double takeGranularReactionDeposit(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return domain.takeGranularReactionDeposit(key, takenSvq, totalSvq);
	}
	void commitGranularReaction(ThermalDomainState.ReactionPlan plan, ThermalCarrierContext markers) {
		domain.commitGranularReaction(plan, markers);
	}
	ThermalDomainState.PhaseMechanicalWorkPlan planPhaseMechanicalWork(
			SignedDyadic[] pendingCellVolume, float[] relativeGaugePressurePa,
			float pressureReferencePa, float cellSize, ThermalCarrierContext markers
	) {
		return domain.planPhaseMechanicalWork(pendingCellVolume, relativeGaugePressurePa,
				pressureReferencePa, cellSize, markers);
	}
	void requirePendingPhaseCells(SignedDyadic[] pendingCellVolume, ThermalCarrierContext markers) {
		domain.requirePendingPhaseCells(pendingCellVolume, markers);
	}
	void commitPhaseMechanicalWork(ThermalDomainState.PhaseMechanicalWorkPlan plan, ThermalCarrierContext markers) {
		domain.commitPhaseMechanicalWork(plan, markers);
	}
	double temperatureInCell(int cell, ThermalCarrierContext markers) { return domain.temperatureInCell(cell, markers); }
	void preparePhaseScan(ThermalCarrierContext markers) { domain.preparePhaseScan(markers); }
	double phaseScanTemperatureInCell(int cell) { return domain.phaseScanTemperatureInCell(cell); }
	List<FiniteThermalExchange.Contact> finiteDeviceContacts(
			List<FiniteDeviceExchangeCoordinator.CellContactRequest> requests, ThermalCarrierContext markers
	) {
		return domain.finiteDeviceContacts(requests, markers);
	}
	FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan planFiniteDeviceCellExchange(
			List<FiniteDeviceExchangeCoordinator.CellEnergyDelta> deltas, ThermalCarrierContext markers
	) {
		return domain.planFiniteDeviceCellExchange(deltas, markers);
	}
	void commitFiniteDeviceCellExchange(
			FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan plan, ThermalCarrierContext markers
	) {
		domain.commitFiniteDeviceCellExchange(plan, markers);
	}
	double observeTemperatureInCell(int cell, ThermalCarrierContext markers) {
		return domain.observeTemperatureInCell(cell, markers);
	}
	double phaseCandidateTemperature(
			int cell, List<ThermalStateFunction.InventoryPortion> phaseInventory, ThermalCarrierContext markers
	) {
		return domain.phaseCandidateTemperature(cell, phaseInventory, markers);
	}
	ThermalDomainState.PhaseCandidateCell preparePhaseCandidateCell(int cell, ThermalCarrierContext markers) {
		return domain.preparePhaseCandidateCell(cell, markers);
	}
	double requirePhaseCandidateState(
			ThermalDomainState.PhaseCandidateCell current, List<ThermalStateFunction.InventoryPortion> liquidInventory,
			double cellEnergyDelta
	) {
		return domain.requirePhaseCandidateState(current, liquidInventory, cellEnergyDelta);
	}
	int thermalMaterialHandleForFluid(int fluidHandle) { return domain.thermalMaterialHandleForFluid(fluidHandle); }
	ThermalDisplaySnapshot displaySnapshot(ThermalCarrierContext markers, AirNetwork network) {
		return domain.displaySnapshot(markers, network);
	}
	void setTemperatureInCell(int cell, double temperatureK, ThermalCarrierContext markers) {
		domain.setTemperatureInCell(cell, temperatureK, markers);
	}
	void resetMarkerTemperature(int marker, double temperatureK, ThermalCarrierContext markers, SignedDyadic inventoryBefore) {
		domain.resetMarkerTemperature(marker, temperatureK, markers, inventoryBefore);
	}
	ThermalTransport.Stats step(ThermalCarrierContext markers, ThermalTransport.FaceArea faceArea, double dtSeconds) {
		return domain.step(markers, faceArea, dtSeconds);
	}
	ThermalTransport.Stats step(
			ThermalCarrierContext markers, ThermalTransport.FaceArea faceArea,
			ThermalTransport.BoundaryFace boundaryFace, double dtSeconds
	) {
		return domain.step(markers, faceArea, boundaryFace, dtSeconds);
	}
	void addExternalMarker(int marker, ThermalCarrierContext markers) { domain.addExternalMarker(marker, markers); }
	LiquidEnergyColumn.ExternalLiquidInsertionPlan planExternalLiquidInsertions(
			List<LiquidEnergyColumn.LiquidInsertion> insertions, double totalEnergy, ThermalCarrierContext markers
	) {
		return domain.planExternalLiquidInsertions(insertions, totalEnergy, markers);
	}
	void commitExternalLiquidInsertions(int firstOrdinal, LiquidEnergyColumn.ExternalLiquidInsertionPlan plan) {
		domain.commitExternalLiquidInsertions(firstOrdinal, plan);
	}
	void removeExternalAndSwap(int index, int last) { domain.removeExternalAndSwap(index, last); }
	void recordExternalSink(SignedDyadic energy) { domain.recordExternalSink(energy); }
	void settleExternalExtraction(double removedCarrierEnergy, double coupledItemEnergy) {
		domain.settleExternalExtraction(removedCarrierEnergy, coupledItemEnergy);
	}
	void recordRoundoff(SignedDyadic delta) { domain.recordRoundoff(delta); }
	void addCoherentExternal(CoherentPhaseStore.Group group) { domain.addCoherentExternal(group); }
	void addCoherentInternal(CoherentPhaseStore.Group group) { domain.addCoherentInternal(group); }
	CoherentPhaseStore.Group takeCoherentInternal(int cell, String phaseId, long occupiedSvq) {
		return domain.takeCoherentInternal(cell, phaseId, occupiedSvq);
	}
	void clearCoherentExternal(List<CoherentPhaseStore.Group> expected, SignedDyadic expectedEnergy) {
		domain.clearCoherentExternal(expected, expectedEnergy);
	}
	void addInternalMarker(int marker, double energy) { domain.addInternalMarker(marker, energy); }
	double removeInternalAndSwap(int index, int last) { return domain.removeInternalAndSwap(index, last); }
	void addLiquidEnergy(int marker, double energy) { domain.addLiquidEnergy(marker, energy); }
	void repartitionCellEnergy(int cell, double targetEnergy, ThermalCarrierContext markers, SignedDyadic inventoryBefore) {
		domain.repartitionCellEnergy(cell, targetEnergy, markers, inventoryBefore);
	}
	void repartitionCellEnergyConserving(int cell, ThermalCarrierContext markers, SignedDyadic inventoryBefore) {
		domain.repartitionCellEnergyConserving(cell, markers, inventoryBefore);
	}
	void repartitionPhaseCellAndGasEnergyConserving(
			ThermalDomainState.PhaseCandidateCell beforeCell, double gasEnergyBefore, double gasEnergyAfter,
			ThermalCarrierContext markers, boolean carrierTopologyChanged
	) {
		domain.repartitionPhaseCellAndGasEnergyConserving(beforeCell, gasEnergyBefore, gasEnergyAfter,
				markers, carrierTopologyChanged);
	}
	void requireActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		domain.requireActiveToDeposit(active, target);
	}
	void commitActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		domain.commitActiveToDeposit(active, target);
	}
	void requireActiveMerge(int first, int second) { domain.requireActiveMerge(first, second); }
	void commitActiveMerge(int first, int second) { domain.commitActiveMerge(first, second); }
	void requireDepositWake(GranularEnergyColumn.DepositKey source, long takenSvq, long totalSvq) {
		domain.requireDepositWake(source, takenSvq, totalSvq);
	}
	void commitDepositWake(GranularEnergyColumn.DepositKey source, long takenSvq, long totalSvq) {
		domain.commitDepositWake(source, takenSvq, totalSvq);
	}
	void requireDepositMove(
			GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedSvq, long sourceTotalSvq
	) {
		domain.requireDepositMove(source, target, movedSvq, sourceTotalSvq);
	}
	void commitDepositMove(
			GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedSvq, long sourceTotalSvq
	) {
		domain.commitDepositMove(source, target, movedSvq, sourceTotalSvq);
	}
	void clearGranularExternal() { domain.clearGranularExternal(); }
	double[] planExternalActiveInsertions(int firstOrdinal, int count, ThermalCarrierContext markers) {
		return domain.planExternalActiveInsertions(firstOrdinal, count, markers);
	}
	double planExternalGranularEnergy(int material, int composition, double mass, ThermalCarrierContext markers) {
		return domain.planExternalGranularEnergy(material, composition, mass, markers);
	}
	void commitExternalActiveInsertions(double[] energies) { domain.commitExternalActiveInsertions(energies); }
	double previewActiveTake(int active, long takenSvq, long totalSvq) {
		return domain.previewActiveTake(active, takenSvq, totalSvq);
	}
	double previewDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return domain.previewDepositTake(key, takenSvq, totalSvq);
	}
	double commitExternalActiveTake(int active, long takenSvq, long totalSvq) {
		return domain.commitExternalActiveTake(active, takenSvq, totalSvq);
	}
	double commitExternalDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return domain.commitExternalDepositTake(key, takenSvq, totalSvq);
	}
	LiquidEnergyColumn.Truncation planTruncate(
			int marker, double newMass, ChemicalMixtureRecord keptMixture,
			double previousTemperatureK, ThermalCarrierContext markers
	) {
		return domain.planTruncate(marker, newMass, keptMixture, previousTemperatureK, markers);
	}
	void commitTruncate(int marker, LiquidEnergyColumn.Truncation plan) { domain.commitTruncate(marker, plan); }
	void commitInternalTruncate(int marker, LiquidEnergyColumn.Truncation plan) {
		domain.commitInternalTruncate(marker, plan);
	}

	FlipParticleReseeder.ThermalAdapter reseedAdapter(ThermalCarrierContext markers) {
		return new FlipParticleReseeder.ThermalAdapter() {
			@Override public double totalEnergy(int count) { return domain.totalEnergy(count); }
			@Override public void merge(int target, int source) { domain.mergeReseedLiquidEnergy(target, source); }
			@Override public FlipParticleReseeder.ThermalSplit beforeSplit(int source) {
				return new FlipParticleReseeder.ThermalSplit(domain.liquidInternalEnergy(source), temperature(source, markers));
			}
			@Override public void afterSplit(FlipParticleReseeder.ThermalSplit split, int source, int destination) {
				double sourceModeled = domain.liquid().modeledEnergy(source, markers.mass(source), markers.mixture(source),
						split.temperatureK(), markers);
				domain.assignReseededLiquidEnergies(source, sourceModeled, destination,
						split.parentEnergy() - sourceModeled);
				validateTemperature(destination, split.temperatureK(), markers);
			}
			@Override public void move(int source, int destination) { domain.moveReseedLiquidEnergy(source, destination); }
		};
	}

	private void validateTemperature(int marker, double expected, ThermalCarrierContext markers) {
		double restored = temperature(marker, markers);
		double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(restored), Math.ulp(expected)) * 16.0);
		if (Math.abs(restored - expected) > tolerance) {
			throw new IllegalArgumentException("Reseeded thermal split left local equilibrium");
		}
	}
}
