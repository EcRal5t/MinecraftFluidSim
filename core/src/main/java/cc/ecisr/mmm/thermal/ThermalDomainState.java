package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The thermal aggregate of one region: the carrier energy owners (liquid, gas,
 * granular/coherent), the audit state, the derived LTE cell grid, the transport service and
 * the finite-device coordinator. All thermal truth lives here; the fluid facade only
 * delegates, and every truth mutation bumps one of the owner revisions that compose
 * {@link #thermalRevision()}.
 */
public final class ThermalDomainState {
	public record ReactionPlan(int cell, SignedDyadic cellEnergy, SignedDyadic inventoryBefore, double equilibriumTemperatureK) { }
	public record PhaseMechanicalWorkPlan(
			double[] targetCellEnergy, SignedDyadic inventoryBefore, SignedDyadic mechanicalWork
	) {
		public PhaseMechanicalWorkPlan {
			targetCellEnergy = targetCellEnergy.clone();
		}
		@Override public double[] targetCellEnergy() { return targetCellEnergy.clone(); }
	}
	public record PhaseCandidateCell(
			int cell, SignedDyadic cellEnergy,
			List<ThermalStateFunction.InventoryPortion> unchangedSolids
	) { }

	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;
	private final SolverUnitScale unitScale;
	private final int[] materialByFluidHandle;
	private final int[] materialByGranularHandle;
	private final ThermalCellGrid cellGrid;
	private final ThermalTransport transport;
	private final ThermalPhaseChangeEngine phaseChange;
	private final double insertionTemperatureK;
	private final ThermalAuditState auditState;
	private final LiquidEnergyColumn liquidEnergy;
	private final GranularEnergyState granularState;
	private final GasEnergyStore gasState;
	private final FiniteDeviceExchangeCoordinator devices;
	private ThermalTransport.Stats lastTransport = new ThermalTransport.Stats(
			0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO);

	public ThermalDomainState(
			ThermalRegistry registry, int[] materialByFluidHandle, int[] materialByGranularHandle,
			double initialTemperatureK, int initialCapacity, int sizeX, int sizeY, int sizeZ,
			float cellSize, ThermalCarrierContext markers, AirNetwork airNetwork, int maximumTransportSubsteps
	) {
		if (registry == null || materialByFluidHandle == null || materialByGranularHandle == null || markers == null) {
			throw new IllegalArgumentException("Thermal binding input is missing");
		}
		if (!Double.isFinite(initialTemperatureK)) throw new IllegalArgumentException("Initial temperature must be finite");
		this.registry = registry;
		this.stateFunction = new ThermalStateFunction(registry);
		this.unitScale = SolverUnitScale.forCellSize(cellSize);
		this.materialByFluidHandle = materialByFluidHandle;
		this.materialByGranularHandle = materialByGranularHandle;
		this.cellGrid = new ThermalCellGrid(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ));
		this.transport = new ThermalTransport(sizeX, sizeY, sizeZ, cellSize, maximumTransportSubsteps);
		CoherentPhaseStore coherentPhases = new CoherentPhaseStore(
				Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ),
				Math.min(ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION,
						Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ), 8)));
		this.phaseChange = new ThermalPhaseChangeEngine(registry);
		this.insertionTemperatureK = initialTemperatureK;
		this.auditState = new ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO));
		this.liquidEnergy = new LiquidEnergyColumn(registry, stateFunction, unitScale, materialByFluidHandle,
				initialTemperatureK, auditState, initialCapacity, markers);
		GranularEnergyColumn granularColumn = new GranularEnergyColumn(
				cc.ecisr.mmm.granular.GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION,
				cc.ecisr.mmm.granular.GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
		this.granularState = new GranularEnergyState(registry, granularColumn, coherentPhases, stateFunction,
				materialByGranularHandle, initialTemperatureK, auditState);
		for (int parcel = 0; parcel < markers.activeParcelCount(); parcel++) {
			granularColumn.addActive(granularState.modeledGranularEnergy(markers.activeParcelMaterial(parcel),
					markers.activeParcelComposition(parcel), markers.activeParcelMass(parcel),
					initialTemperatureK, markers));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) {
			granularColumn.addDeposit(key, granularState.modeledGranularEnergy(key.materialHandle(), key.compositionHandle(),
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
		if (!supportsGas && airNetwork != null && airNetwork.nodeCount() > 0) {
			throw new IllegalArgumentException("Sealed gas inventory requires complete gas thermal definitions");
		}
		this.gasState = new GasEnergyStore(supportsGas ? new GasThermalModel(registry, airNetwork.registry()) : null,
				initialTemperatureK, auditState, airNetwork);
		this.devices = new FiniteDeviceExchangeCoordinator(this);
		auditState.replace(ThermalAuditLedger.initialized(exactEnergy(markers.markerCount())));
	}

	public long thermalRevision() {
		return liquidEnergy.revision() + granularState.revision() + gasState.revision() + auditState.revision();
	}

	public ThermalRegistry registry() { return registry; }
	public ThermalAuditState audit() { return auditState; }
	public ThermalAuditLedger auditLedger() { return auditState.ledger(); }

	// ---- fluid reseeder / restore / capture seams (all revision-bumping coordinated edits) ----

	public void mergeReseedLiquidEnergy(int target, int source) { liquidEnergy.mergeReseedEnergy(target, source); }

	public void assignReseededLiquidEnergies(int source, double sourceEnergy, int destination, double destinationEnergy) {
		liquidEnergy.assignReseededEnergies(source, sourceEnergy, destination, destinationEnergy);
	}

	public void moveReseedLiquidEnergy(int source, int destination) { liquidEnergy.moveEnergy(source, destination); }

	public void restoreLiquidEnergies(double[] restored) { liquidEnergy.adoptRestored(restored); }

	public void restoreGasEnergies(AirNetwork network, double[] restored) {
		gasState.validateRestoredNodeEnergies(network, restored);
		gasState.replaceNodeEnergies(restored);
	}

	public void restoreThermalCoherent(List<CoherentPhaseStore.Group> groups) { granularState.restoreCoherent(groups); }

	public void restoreThermalGranular(double[] activeEnergy, List<GranularEnergyColumn.DepositEnergy> deposits) {
		granularState.restoreGranular(activeEnergy, deposits);
	}

	public void restoreAuditLedger(ThermalAuditLedger ledger) { auditState.replace(ledger); }
	public LiquidEnergyColumn liquid() { return liquidEnergy; }
	public GranularEnergyState granular() { return granularState; }
	public GasEnergyStore gas() { return gasState; }
	public CoherentPhaseStore coherentPhases() { return granularState.coherentPhases(); }
	public GranularEnergyColumn granularColumn() { return granularState.column(); }
	public ThermalPhaseChangeEngine phaseChange() { return phaseChange; }
	public ThermalStateFunction stateFunction() { return stateFunction; }
	ThermalCellGrid grid() { return cellGrid; }
	ThermalTransport transport() { return transport; }

	ThermalCellGrid.CarrierAccess carrierAccess(ThermalCarrierContext markers) {
		int liquidCount = markers.markerCount(), coherentCount = granularState.coherentPhases().groupCount();
		int activeCount = markers.activeParcelCount();
		List<GranularEnergyColumn.DepositKey> depositKeys = markers.depositKeys();
		if (activeCount != granularState.column().activeCount()
				|| !depositKeys.equals(granularState.column().depositSnapshot().stream()
				.map(GranularEnergyColumn.DepositEnergy::key).toList())) {
			throw new IllegalStateException("Granular thermal ordinals differ from granular inventory");
		}
		int coherentStart = liquidCount, activeStart = Math.addExact(coherentStart, coherentCount);
		int depositStart = Math.addExact(activeStart, activeCount);
		return new ThermalCellGrid.CarrierAccess() {
			@Override public int carrierCount() { return Math.addExact(depositStart, depositKeys.size()); }
			@Override public int cell(int carrier) {
				if (carrier < liquidCount) return markers.cell(carrier);
				if (carrier < activeStart) return granularState.coherentPhases().group(carrier - coherentStart).cell();
				if (carrier < depositStart) return markers.activeParcelCell(carrier - activeStart);
				return depositKeys.get(carrier - depositStart).cell();
			}
			@Override public ThermalMaterialDefinition material(int carrier) {
				if (carrier < liquidCount) return ThermalDomainState.this.material(carrier, markers);
				if (carrier < activeStart) return registry.material(registry.materialHandle(
						granularState.coherentPhases().group(carrier - coherentStart).phaseId()));
				int material = carrier < depositStart ? markers.activeParcelMaterial(carrier - activeStart)
						: depositKeys.get(carrier - depositStart).materialHandle();
				return registry.material(materialByGranularHandle[material]);
			}
			@Override public double mass(int carrier) {
				if (carrier < liquidCount) return unitScale.kilograms(markers.mass(carrier));
				if (carrier < activeStart) return granularState.coherentPhases().group(carrier - coherentStart).mass();
				return carrier < depositStart
						? markers.activeParcelMass(carrier - activeStart)
						: markers.depositMass(depositKeys.get(carrier - depositStart));
			}
			@Override public double volume(int carrier) {
				if (carrier < liquidCount) return markers.volume(carrier);
				if (carrier < activeStart) return SolidVolume.toCellVolume(
						granularState.coherentPhases().group(carrier - coherentStart).occupiedVolumeSvq());
				return SolidVolume.toCellVolume(carrier < depositStart
						? markers.activeParcelSvq(carrier - activeStart)
						: markers.depositSvq(depositKeys.get(carrier - depositStart)));
			}
			@Override public double baseEnergy(int carrier) {
				if (carrier < liquidCount) return ThermalDomainState.this.baseEnergy(carrier, markers);
				if (carrier < activeStart) {
					CoherentPhaseStore.Group group = granularState.coherentPhases().group(carrier - coherentStart);
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
				return stateFunction.baseEnergy(materialByGranularHandle[material], mass,
						markers.solidComposition(composition));
			}
			@Override public double internalEnergy(int carrier) {
				if (carrier < liquidCount) return liquidEnergy.energy(carrier);
				if (carrier < activeStart) return granularState.coherentPhases().group(carrier - coherentStart).internalEnergy();
				return carrier < depositStart ? granularState.column().activeEnergy(carrier - activeStart)
						: granularState.column().depositEnergy(depositKeys.get(carrier - depositStart));
			}
			@Override public void setInternalEnergy(int carrier, double energy) {
				if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal carrier energy is not finite");
				if (carrier < liquidCount) liquidEnergy.setEnergy(carrier, energy);
				else if (carrier < activeStart) granularState.coherentPhases().setInternalEnergy(carrier - coherentStart, energy);
				else if (carrier < depositStart) granularState.column().setActiveEnergy(carrier - activeStart, energy);
				else granularState.column().setDepositEnergy(depositKeys.get(carrier - depositStart), energy);
			}
		};
	}

	// ---- composed energy sums ----

	public double totalEnergy(int count) {
		double total = liquidEnergy.totalEnergy(count);
		for (CoherentPhaseStore.Group group : granularState.coherentPhases().snapshot()) total += group.internalEnergy();
		total += granularState.column().exactEnergy().toDouble();
		for (double energy : gasState.nodeEnergiesRaw()) total += energy;
		return total;
	}

	public SignedDyadic exactEnergy(int count) {
		return liquidEnergy.exactLiquidEnergy(count).add(granularState.coherentPhases().exactEnergy())
				.add(granularState.column().exactEnergy()).add(gasState.exactGasEnergy());
	}

	public SignedDyadic exactGasEnergy() { return gasState.exactGasEnergy(); }
	public SignedDyadic exactLiquidEnergy(int count) { return liquidEnergy.exactLiquidEnergy(count); }
	public void ensureCapacity(int capacity) { liquidEnergy.ensureCapacity(capacity); }

	public ThermalCellGrid.TemperatureRange observedCarrierTemperatureRange() {
		return cellGrid.observedTemperatureRange();
	}

	public GasLiquidPhaseModel gasLiquidPhase(AirNetwork network) {
		if (!gasState.gasModelBound()) throw new IllegalStateException("Gas thermal model is not bound");
		return new GasLiquidPhaseModel(registry, network.registry());
	}

	// ---- liquid pass-throughs ----

	public double liquidInternalEnergy(int marker) { return liquidEnergy.energy(marker); }
	public double temperature(int marker, ThermalCarrierContext markers) { return liquidEnergy.temperature(marker, markers); }

	public void requireReactionState(
			int marker, ChemicalMixtureRecord candidateMixture, double candidateMass, ThermalCarrierContext markers
	) {
		liquidEnergy.requireReactionState(marker, candidateMixture, candidateMass, markers);
	}

	public void addExternalMarker(int marker, ThermalCarrierContext markers) {
		liquidEnergy.addExternalMarker(marker, markers);
	}

	public LiquidEnergyColumn.ExternalLiquidInsertionPlan planExternalLiquidInsertions(
			List<LiquidEnergyColumn.LiquidInsertion> insertions, double totalEnergy, ThermalCarrierContext markers
	) {
		return liquidEnergy.planExternalLiquidInsertions(insertions, totalEnergy, markers);
	}

	public void commitExternalLiquidInsertions(int firstOrdinal, LiquidEnergyColumn.ExternalLiquidInsertionPlan plan) {
		liquidEnergy.commitExternalLiquidInsertions(firstOrdinal, plan);
	}

	public void removeExternalAndSwap(int index, int last) { liquidEnergy.removeExternalAndSwap(index, last); }
	public void addInternalMarker(int marker, double energy) { liquidEnergy.addInternalMarker(marker, energy); }
	public double removeInternalAndSwap(int index, int last) { return liquidEnergy.removeInternalAndSwap(index, last); }
	public void addLiquidEnergy(int marker, double energy) { liquidEnergy.addLiquidEnergy(marker, energy); }

	public LiquidEnergyColumn.Truncation planTruncate(
			int marker, double newMass, ChemicalMixtureRecord keptMixture,
			double previousTemperatureK, ThermalCarrierContext markers
	) {
		return liquidEnergy.planTruncate(marker, newMass, keptMixture, previousTemperatureK, markers);
	}

	public void commitTruncate(int marker, LiquidEnergyColumn.Truncation plan) { liquidEnergy.commitTruncate(marker, plan); }
	public void commitInternalTruncate(int marker, LiquidEnergyColumn.Truncation plan) {
		liquidEnergy.commitInternalTruncate(marker, plan);
	}

	public void resetMarkerTemperature(int marker, double temperatureK, ThermalCarrierContext markers, SignedDyadic inventoryBefore) {
		liquidEnergy.resetMarkerTemperature(marker, temperatureK, markers);
		auditState.update(ledger -> ledger.withHeatExchange(exactEnergy(markers.markerCount()).subtract(inventoryBefore)));
	}

	public int thermalMaterialHandleForFluid(int fluidHandle) {
		if (fluidHandle < 0 || fluidHandle >= materialByFluidHandle.length) {
			throw new IllegalArgumentException("Unknown liquid phase carrier");
		}
		return materialByFluidHandle[fluidHandle];
	}

	// ---- gas pass-throughs ----

	public boolean hasGasEnergy(int nodeCount) { return gasState.hasGasEnergy(nodeCount); }
	public boolean gasModelBound() { return gasState.gasModelBound(); }
	public double gasNodeEnergy(int nodeId, AirNetwork network) { return gasState.nodeEnergy(nodeId, network); }
	public double gasTemperature(int nodeId, AirNetwork network) { return gasState.temperature(nodeId, network); }
	public double gasGaugePressure(int nodeId, AirNetwork network) { return gasState.gaugePressure(nodeId, network); }
	public double gasDensity(int nodeId, AirNetwork network) { return gasState.density(nodeId, network); }

	public double requireGasNodeState(int nodeId, GasInventory inventory, double energy, AirNetwork network) {
		return gasState.requireNodeState(nodeId, inventory, energy, network);
	}

	public void commitGasNodeEnergy(int nodeId, double energy) { gasState.commitNodeEnergy(nodeId, energy); }

	public double[] partitionGasNodeEnergy(int nodeId, List<GasInventory> children, AirNetwork network) {
		return gasState.partitionNodeEnergy(nodeId, children, network);
	}

	public double ambientGasEnergy(GasInventory inventory) { return gasState.ambientGasEnergy(inventory); }

	public GasEnergyStore.GasRemapPlan planGasRemap(
			AirNetwork oldNetwork, List<GasInventory> inventories, double[] availableVolumes,
			double[] mixedEnergy, double[] inheritedAvailableVolumes,
			double sourceEnergy, double sinkEnergy
	) {
		return gasState.planRemap(oldNetwork, inventories, availableVolumes, mixedEnergy,
				inheritedAvailableVolumes, sourceEnergy, sinkEnergy);
	}

	public void commitGasRemap(GasEnergyStore.GasRemapPlan plan) { gasState.commitRemap(plan); }

	/** Prevalidates removal of every sealed-node energy as one external sink against the joint inventory. */
	public GasEnergyStore.GasVentPlan planGasVent(AirNetwork network, int markerCount) {
		if (!hasGasEnergy(network.nodeCount()) || !exactEnergy(markerCount).equals(auditState.ledger().expectedInventory())) {
			throw new IllegalStateException("Gas energy is not bound to a closed thermal inventory");
		}
		return gasState.planVent();
	}

	/** Publishes an already validated all-node gas-energy removal using assignments only. */
	public void commitGasVent(GasEnergyStore.GasVentPlan plan) { gasState.commitVent(plan); }

	public AirNetwork.TransferCommit planGasTransfer(
			int sourceNode, int targetNode, GasInventory moved,
			GasInventory sourceAfter, GasInventory targetAfter, AirNetwork network
	) {
		return gasState.planTransfer(sourceNode, targetNode, moved, sourceAfter, targetAfter, network);
	}

	// ---- granular/coherent pass-throughs ----

	public void addGranularReactionActive() { granularState.addGranularReactionActive(); }
	public double takeGranularReactionActive(int active, long takenSvq, long totalSvq) {
		return granularState.takeGranularReactionActive(active, takenSvq, totalSvq);
	}
	public double takeGranularReactionDeposit(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return granularState.takeGranularReactionDeposit(key, takenSvq, totalSvq);
	}
	public void requireActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		granularState.requireActiveToDeposit(active, target);
	}
	public void commitActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		granularState.commitActiveToDeposit(active, target);
	}
	public void requireActiveMerge(int first, int second) { granularState.requireActiveMerge(first, second); }
	public void commitActiveMerge(int first, int second) { granularState.commitActiveMerge(first, second); }
	public void requireDepositWake(GranularEnergyColumn.DepositKey source, long takenSvq, long totalSvq) {
		granularState.requireDepositWake(source, takenSvq, totalSvq);
	}
	public void commitDepositWake(GranularEnergyColumn.DepositKey source, long takenSvq, long totalSvq) {
		granularState.commitDepositWake(source, takenSvq, totalSvq);
	}
	public void requireDepositMove(
			GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedSvq, long sourceTotalSvq
	) {
		granularState.requireDepositMove(source, target, movedSvq, sourceTotalSvq);
	}
	public void commitDepositMove(
			GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedSvq, long sourceTotalSvq
	) {
		granularState.commitDepositMove(source, target, movedSvq, sourceTotalSvq);
	}
	public void clearGranularExternal() { granularState.clearGranularExternal(); }
	public double[] planExternalActiveInsertions(int firstOrdinal, int count, ThermalCarrierContext markers) {
		return granularState.planExternalActiveInsertions(firstOrdinal, count, markers);
	}
	public double planExternalGranularEnergy(int material, int composition, double mass, ThermalCarrierContext markers) {
		return granularState.planExternalGranularEnergy(material, composition, mass, markers);
	}
	public void commitExternalActiveInsertions(double[] energies) { granularState.commitExternalActiveInsertions(energies); }
	public double previewActiveTake(int active, long takenSvq, long totalSvq) {
		return granularState.previewActiveTake(active, takenSvq, totalSvq);
	}
	public double previewDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return granularState.previewDepositTake(key, takenSvq, totalSvq);
	}
	public double commitExternalActiveTake(int active, long takenSvq, long totalSvq) {
		return granularState.commitExternalActiveTake(active, takenSvq, totalSvq);
	}
	public double commitExternalDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return granularState.commitExternalDepositTake(key, takenSvq, totalSvq);
	}
	public void addCoherentExternal(CoherentPhaseStore.Group group) { granularState.addCoherentExternal(group); }
	public void addCoherentInternal(CoherentPhaseStore.Group group) { granularState.addCoherentInternal(group); }
	public CoherentPhaseStore.Group takeCoherentInternal(int cell, String phaseId, long occupiedSvq) {
		return granularState.takeCoherentInternal(cell, phaseId, occupiedSvq);
	}
	public void clearCoherentExternal(List<CoherentPhaseStore.Group> expected, SignedDyadic expectedEnergy) {
		granularState.clearCoherentExternal(expected, expectedEnergy);
	}

	// ---- audit pass-throughs ----

	public void recordExternalSink(SignedDyadic energy) {
		auditState.update(ledger -> ledger.withExternalSinkEnergy(energy));
	}

	public void settleExternalExtraction(double removedCarrierEnergy, double coupledItemEnergy) {
		if (!Double.isFinite(removedCarrierEnergy) || !Double.isFinite(coupledItemEnergy)) {
			throw new IllegalArgumentException("Container thermal settlement is not finite");
		}
		SignedDyadic delta = SignedDyadic.of(coupledItemEnergy).subtract(SignedDyadic.of(removedCarrierEnergy));
		auditState.update(ledger -> ledger.withExternalSinkEnergy(delta).withRoundoff(delta));
	}

	public void recordRoundoff(SignedDyadic delta) { auditState.update(ledger -> ledger.withRoundoff(delta)); }

	// ---- granular reaction plan ----

	public ReactionPlan requireGranularReaction(
			int cell, List<GranularEnergyState.ReactionMarker> markerReplacements,
			GranularEnergyState.GranularReactionEdit granularEdit,
			ThermalCarrierContext markers
	) {
		if (cell < 0 || cell >= cellGrid.cellCount() || markerReplacements == null || granularEdit == null) {
			throw new IllegalArgumentException("Thermal granular reaction plan is invalid");
		}
		HashMap<Integer, GranularEnergyState.ReactionMarker> replacements = new HashMap<>();
		for (GranularEnergyState.ReactionMarker replacement : markerReplacements) {
			if (replacement.marker() >= markers.markerCount() || markers.cell(replacement.marker()) != cell
					|| replacements.put(replacement.marker(), replacement) != null) {
				throw new IllegalArgumentException("Thermal reaction marker set is not unique in its cell");
			}
		}
		SignedDyadic cellEnergy = cellGrid.exactCarrierEnergyInCell(cell);
		ArrayList<ThermalStateFunction.InventoryPortion> finalInventory = new ArrayList<>();
		for (int marker = 0; marker < markers.markerCount(); marker++) if (markers.cell(marker) == cell) {
			GranularEnergyState.ReactionMarker replacement = replacements.remove(marker);
			double mass = replacement == null ? markers.mass(marker) : replacement.mass();
			ChemicalMixtureRecord mixture = replacement == null ? markers.mixture(marker) : replacement.mixture();
			finalInventory.add(new ThermalStateFunction.InventoryPortion(
					materialByFluidHandle[markers.fluidMaterialHandle(marker)], unitScale.kilograms(mass),
					ThermalStateFunction.stableAmounts(mixture, markers.chemicalRegistry())));
		}
		if (!replacements.isEmpty()) throw new IllegalArgumentException("Thermal reaction names a missing liquid marker");
		for (CoherentPhaseStore.Group group : granularState.coherentPhases().snapshot()) if (group.cell() == cell) {
			finalInventory.add(new ThermalStateFunction.InventoryPortion(registry.materialHandle(group.phaseId()), group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts()));
		}
		boolean matchedGranular = granularEdit.kind() == GranularEnergyState.GranularReactionKind.ADD_ACTIVE;
		for (int active = 0; active < markers.activeParcelCount(); active++) if (markers.activeParcelCell(active) == cell) {
			if (granularEdit.activeOrdinal() == active) {
				matchedGranular = true;
				if (granularEdit.kind() != GranularEnergyState.GranularReactionKind.REMOVE_ACTIVE) granularState.addGranularPortion(
						finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(),
						granularEdit.finalComposition());
			} else granularState.addGranularPortion(finalInventory, markers.activeParcelMaterial(active),
					markers.activeParcelMass(active), markers.solidComposition(markers.activeParcelComposition(active)));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) if (key.cell() == cell) {
			if (key.equals(granularEdit.depositKey())) {
				matchedGranular = true;
				if (granularEdit.kind() != GranularEnergyState.GranularReactionKind.REMOVE_DEPOSIT) granularState.addGranularPortion(
						finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(),
						granularEdit.finalComposition());
			} else granularState.addGranularPortion(finalInventory, key.materialHandle(), markers.depositMass(key),
					markers.solidComposition(key.compositionHandle()));
		}
		if (!matchedGranular) throw new IllegalArgumentException("Thermal reaction names a missing granular carrier");
		if (granularEdit.kind() == GranularEnergyState.GranularReactionKind.ADD_ACTIVE) {
			granularState.addGranularPortion(finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(),
					granularEdit.finalComposition());
			granularState.column().requireAddActive(0.0);
		}
		ThermalStateFunction.State state = stateFunction.stateForInventory(finalInventory, cellEnergy.toDouble());
		return new ReactionPlan(cell, cellEnergy, exactEnergy(markers.markerCount()), state.temperatureK());
	}

	public void commitGranularReaction(ReactionPlan plan, ThermalCarrierContext markers) {
		if (plan == null) throw new IllegalArgumentException("Thermal granular reaction plan is missing");
		cellGrid.rebuildWithCellEnergy(carrierAccess(markers), plan.cell(), plan.cellEnergy().toDouble());
		double[] target = cellGrid.copyInternalEnergy();
		cellGrid.setCellInternalEnergies(target);
		auditState.update(ledger -> ledger.withRoundoff(exactEnergy(markers.markerCount()).subtract(plan.inventoryBefore())));
	}

	// ---- pending phase mechanical work ----

	public PhaseMechanicalWorkPlan planPhaseMechanicalWork(
			SignedDyadic[] pendingCellVolume, float[] relativeGaugePressurePa,
			float pressureReferencePa, float cellSize, ThermalCarrierContext markers
	) {
		if (pendingCellVolume == null || relativeGaugePressurePa == null
				|| pendingCellVolume.length != cellGrid.cellCount()
				|| relativeGaugePressurePa.length != cellGrid.cellCount()
				|| !Float.isFinite(pressureReferencePa)
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
			double absolutePressure = AirNetwork.REFERENCE_PRESSURE
					+ pressureReferencePa + relativeGaugePressurePa[cell];
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

	public void requirePendingPhaseCells(SignedDyadic[] pendingCellVolume, ThermalCarrierContext markers) {
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

	public void commitPhaseMechanicalWork(PhaseMechanicalWorkPlan plan, ThermalCarrierContext markers) {
		if (plan == null) throw new IllegalArgumentException("Phase mechanical-work plan is missing");
		cellGrid.setCellInternalEnergies(plan.targetCellEnergy());
		SignedDyadic actualDelta = exactEnergy(markers.markerCount()).subtract(plan.inventoryBefore());
		auditState.update(ledger -> ledger.withMechanicalWork(plan.mechanicalWork())
				.withRoundoff(actualDelta.subtract(plan.mechanicalWork())));
	}

	// ---- cell grid observations and repartitions ----

	public double temperatureInCell(int cell, ThermalCarrierContext markers) {
		cellGrid.rebuild(carrierAccess(markers));
		return cellGrid.occupied(cell) ? cellGrid.temperature(cell) : Double.NaN;
	}

	/** Rebuilds the derived LTE grid once before a phase scan over many cells. */
	public void preparePhaseScan(ThermalCarrierContext markers) {
		cellGrid.rebuild(carrierAccess(markers));
	}

	/** Reads one cell from the phase-scan grid; callers must rebuild after every committed carrier edit. */
	public double phaseScanTemperatureInCell(int cell) {
		if (cell < 0 || cell >= cellGrid.cellCount()) {
			throw new IllegalArgumentException("Thermal phase-scan cell is invalid");
		}
		return cellGrid.occupied(cell) ? cellGrid.temperature(cell) : Double.NaN;
	}

	/**
	 * Derives one cell's LTE temperature directly from authoritative carrier inventory and energy.
	 * Unlike {@link #temperatureInCell(int, ThermalCarrierContext)}, this observation path does not rebuild or
	 * mutate the reusable transport grid.
	 */
	public double observeTemperatureInCell(int cell, ThermalCarrierContext markers) {
		if (cell < 0 || cell >= cellGrid.cellCount() || markers == null) {
			throw new IllegalArgumentException("Thermal observation cell is invalid");
		}
		ArrayList<ThermalStateFunction.InventoryPortion> inventory = new ArrayList<>();
		SignedDyadic energy = SignedDyadic.ZERO;
		for (int marker = 0; marker < markers.markerCount(); marker++) if (markers.cell(marker) == cell) {
			inventory.add(new ThermalStateFunction.InventoryPortion(
					materialByFluidHandle[markers.fluidMaterialHandle(marker)],
					unitScale.kilograms(markers.mass(marker)),
					ThermalStateFunction.stableAmounts(markers.mixture(marker), markers.chemicalRegistry())));
			energy = energy.add(SignedDyadic.of(liquidEnergy.energy(marker)));
		}
		for (CoherentPhaseStore.Group group : granularState.coherentPhases().snapshot()) if (group.cell() == cell) {
			inventory.add(new ThermalStateFunction.InventoryPortion(
					registry.materialHandle(group.phaseId()), group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts()));
			energy = energy.add(SignedDyadic.of(group.internalEnergy()));
		}
		if (markers.activeParcelCount() != granularState.column().activeCount()) {
			throw new IllegalStateException("Granular thermal ordinals differ from active inventory");
		}
		for (int parcel = 0; parcel < markers.activeParcelCount(); parcel++) {
			if (markers.activeParcelCell(parcel) != cell) continue;
			granularState.addGranularPortion(inventory, markers.activeParcelMaterial(parcel),
					markers.activeParcelMass(parcel),
					markers.solidComposition(markers.activeParcelComposition(parcel)));
			energy = energy.add(SignedDyadic.of(granularState.column().activeEnergy(parcel)));
		}
		List<GranularEnergyColumn.DepositKey> depositKeys = markers.depositKeys();
		if (!depositKeys.equals(granularState.column().depositSnapshot().stream()
				.map(GranularEnergyColumn.DepositEnergy::key).toList())) {
			throw new IllegalStateException("Granular thermal ordinals differ from deposit inventory");
		}
		for (GranularEnergyColumn.DepositKey key : depositKeys) if (key.cell() == cell) {
			granularState.addGranularPortion(inventory, key.materialHandle(), markers.depositMass(key),
					markers.solidComposition(key.compositionHandle()));
			energy = energy.add(SignedDyadic.of(granularState.column().depositEnergy(key)));
		}
		return inventory.isEmpty() ? Double.NaN
				: stateFunction.stateForInventory(inventory, energy.toDouble()).temperatureK();
	}

	/** Solves a prospective liquid/coherent phase edit while retaining every same-cell granular heat-bath carrier. */
	public double phaseCandidateTemperature(
			int cell, List<ThermalStateFunction.InventoryPortion> phaseInventory, ThermalCarrierContext markers
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

	/** Freezes exact same-cell energy and unchanged solid heat baths for a gas/liquid candidate search. */
	public PhaseCandidateCell preparePhaseCandidateCell(int cell, ThermalCarrierContext markers) {
		if (cell < 0 || cell >= cellGrid.cellCount() || markers == null) {
			throw new IllegalArgumentException("Thermal phase candidate cell is invalid");
		}
		SignedDyadic cellEnergy = cellGrid.exactCarrierEnergyInCell(cell);
		ArrayList<ThermalStateFunction.InventoryPortion> unchangedSolids = new ArrayList<>();
		for (CoherentPhaseStore.Group group : granularState.coherentPhases().snapshot()) if (group.cell() == cell) {
			unchangedSolids.add(new ThermalStateFunction.InventoryPortion(
					registry.materialHandle(group.phaseId()), group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts()));
		}
		for (int active = 0; active < markers.activeParcelCount(); active++) {
			if (markers.activeParcelCell(active) == cell) granularState.addGranularPortion(unchangedSolids,
					markers.activeParcelMaterial(active), markers.activeParcelMass(active),
					markers.solidComposition(markers.activeParcelComposition(active)));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) if (key.cell() == cell) {
			granularState.addGranularPortion(unchangedSolids, key.materialHandle(), markers.depositMass(key),
					markers.solidComposition(key.compositionHandle()));
		}
		return new PhaseCandidateCell(cell, cellEnergy, List.copyOf(unchangedSolids));
	}

	/** Validates one prospective liquid inventory against a frozen same-cell energy/solid context. */
	public double requirePhaseCandidateState(
			PhaseCandidateCell current, List<ThermalStateFunction.InventoryPortion> liquidInventory,
			double cellEnergyDelta
	) {
		if (current == null || liquidInventory == null || !Double.isFinite(cellEnergyDelta)) {
			throw new IllegalArgumentException("Thermal gas/liquid candidate is invalid");
		}
		ArrayList<ThermalStateFunction.InventoryPortion> finalInventory = new ArrayList<>(
				liquidInventory.size() + current.unchangedSolids().size());
		finalInventory.addAll(liquidInventory);
		finalInventory.addAll(current.unchangedSolids());
		SignedDyadic energy = current.cellEnergy().add(SignedDyadic.of(cellEnergyDelta));
		if (finalInventory.isEmpty()) {
			double value = energy.toDouble();
			if (Math.abs(value) > Math.max(1.0E-9, Math.ulp(value) * 16.0)) {
				throw new IllegalArgumentException("Empty phase cell retains internal energy");
			}
			return Double.NaN;
		}
		return stateFunction.stateForInventory(finalInventory, energy.toDouble()).temperatureK();
	}

	private ThermalStateFunction.State phaseCandidateState(
			int cell, List<ThermalStateFunction.InventoryPortion> phaseInventory,
			SignedDyadic cellEnergy, ThermalCarrierContext markers
	) {
		ArrayList<ThermalStateFunction.InventoryPortion> finalInventory = new ArrayList<>(phaseInventory);
		for (int active = 0; active < markers.activeParcelCount(); active++) {
			if (markers.activeParcelCell(active) == cell) granularState.addGranularPortion(finalInventory,
					markers.activeParcelMaterial(active), markers.activeParcelMass(active),
					markers.solidComposition(markers.activeParcelComposition(active)));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) if (key.cell() == cell) {
			granularState.addGranularPortion(finalInventory, key.materialHandle(), markers.depositMass(key),
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

	public void setTemperatureInCell(int cell, double temperatureK, ThermalCarrierContext markers) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		if (!cellGrid.occupied(cell)) throw new IllegalArgumentException("Cannot set temperature in an empty cell");
		double[] target = cellGrid.copyInternalEnergy();
		target[cell] = modeledCellEnergy(cell, temperatureK, markers);
		cellGrid.setCellInternalEnergies(target);
		auditState.update(ledger -> ledger.withHeatExchange(exactEnergy(markers.markerCount()).subtract(before)));
	}

	public void repartitionCellEnergy(int cell, double targetEnergy, ThermalCarrierContext markers, SignedDyadic inventoryBefore) {
		cellGrid.rebuild(carrierAccess(markers));
		double[] target = cellGrid.copyInternalEnergy();
		target[cell] = targetEnergy;
		cellGrid.setCellInternalEnergies(target);
		auditState.update(ledger -> ledger.withRoundoff(exactEnergy(markers.markerCount()).subtract(inventoryBefore)));
	}

	/**
	 * Closes an inventory-preserving structural commit by assigning the exact pre-commit remainder to one cell.
	 * Gas and non-target carrier energy are already authoritative when this is called; the cell remains the LTE
	 * degree of freedom. This avoids losing a physically meaningful amount through rounded local-energy arithmetic.
	 */
	public void repartitionCellEnergyConserving(int cell, ThermalCarrierContext markers, SignedDyadic inventoryBefore) {
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
		cellGrid.rebuildPhaseCellWithEnergy(current, cell, targetCellEnergy);
		auditState.update(ledger -> ledger.withRoundoff(exactEnergy(markers.markerCount()).subtract(inventoryBefore)));
	}

	/** Closes a gas/liquid transfer over its exact two-owner energy boundary without rescanning the region. */
	public void repartitionPhaseCellAndGasEnergyConserving(
			PhaseCandidateCell beforeCell, double gasEnergyBefore, double gasEnergyAfter,
			ThermalCarrierContext markers, boolean carrierTopologyChanged
	) {
		if (beforeCell == null || !Double.isFinite(gasEnergyBefore) || !Double.isFinite(gasEnergyAfter)) {
			throw new IllegalArgumentException("Gas/liquid thermal closure is invalid");
		}
		SignedDyadic beforePair = beforeCell.cellEnergy().add(SignedDyadic.of(gasEnergyBefore));
		double targetCellEnergy = beforePair.subtract(SignedDyadic.of(gasEnergyAfter)).toDouble();
		if (!Double.isFinite(targetCellEnergy)) {
			throw new IllegalArgumentException("Gas/liquid cell energy remainder is not representable");
		}
		ThermalCellGrid.CarrierAccess current = carrierAccess(markers);
		if (carrierTopologyChanged) {
			cellGrid.rebuildPhaseCellWithEnergy(current, beforeCell.cell(), targetCellEnergy);
		} else {
			cellGrid.refreshPhaseCellWithEnergy(current, beforeCell.cell(), targetCellEnergy);
		}
		SignedDyadic afterPair = cellGrid.exactCarrierEnergyInCell(beforeCell.cell())
				.add(SignedDyadic.of(gasEnergyAfter));
		auditState.update(ledger -> ledger.withRoundoff(afterPair.subtract(beforePair)));
	}

	// ---- display ----

	public ThermalDisplaySnapshot displaySnapshot(ThermalCarrierContext markers, AirNetwork network) {
		cellGrid.rebuild(carrierAccess(markers));
		short[] temperatures = new short[cellGrid.cellCount()];
		byte[] coherent = new byte[cellGrid.cellCount()];
		byte[] flags = new byte[cellGrid.cellCount()];
		Arrays.fill(temperatures, (short) ThermalDisplaySnapshot.EMPTY_TEMPERATURE_Q);
		for (int cell = 0; cell < cellGrid.cellCount(); cell++) {
			if (cellGrid.occupied(cell)) {
				temperatures[cell] = (short) quantizedTemperature(cellGrid.temperature(cell));
				flags[cell] |= ThermalDisplaySnapshot.FLAG_CONDENSED;
			}
			long solid = granularState.coherentPhases().cellOccupiedSvq(cell);
			if (solid > 0L) {
				coherent[cell] = (byte) Math.min(255L,
						(solid * 255L + cc.ecisr.mmm.granular.SolidVolume.SVQ_PER_CELL / 2L)
								/ cc.ecisr.mmm.granular.SolidVolume.SVQ_PER_CELL);
				flags[cell] |= ThermalDisplaySnapshot.FLAG_COHERENT;
			}
		}
		ArrayList<ThermalDisplaySnapshot.GasNode> gas = new ArrayList<>(network.nodeCount());
		for (int node : network.nodeIds()) gas.add(new ThermalDisplaySnapshot.GasNode(node,
				quantizedTemperature(gasState.temperature(node, network)),
				Math.toIntExact(Math.round(gasState.gaugePressure(node, network) / 10.0))));
		return new ThermalDisplaySnapshot(temperatures, coherent, flags, gas);
	}

	private static int quantizedTemperature(double temperatureK) {
		long quantized = Math.round(temperatureK * 16.0);
		if (quantized <= 0L || quantized >= ThermalDisplaySnapshot.EMPTY_TEMPERATURE_Q) {
			throw new IllegalArgumentException("Thermal display temperature is outside the quantized palette");
		}
		return Math.toIntExact(quantized);
	}

	// ---- transport ----

	public ThermalTransport.Stats lastTransport() { return lastTransport; }
	public int maximumTransportSubsteps() { return transport.maximumSubsteps(); }

	public ThermalTransport.Stats step(
			ThermalCarrierContext markers, ThermalTransport.FaceArea faceArea, double dtSeconds
	) {
		return step(markers, faceArea, (x, y, z, direction) -> null, dtSeconds);
	}

	public ThermalTransport.Stats step(
			ThermalCarrierContext markers, ThermalTransport.FaceArea faceArea,
			ThermalTransport.BoundaryFace boundaryFace, double dtSeconds
	) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		lastTransport = transport.step(cellGrid, faceArea, boundaryFace, dtSeconds);
		SignedDyadic physicalDelta = exactEnergy(markers.markerCount()).subtract(before);
		auditState.update(ledger -> ledger.withHeatExchange(lastTransport.reservoirExchange())
				.withRoundoff(physicalDelta.subtract(lastTransport.reservoirExchange())));
		return lastTransport;
	}

	// ---- finite device delegation ----

	public List<FiniteThermalExchange.Contact> finiteDeviceContacts(
			List<FiniteDeviceExchangeCoordinator.CellContactRequest> requests, ThermalCarrierContext markers
	) {
		return devices.finiteDeviceContacts(requests, markers);
	}

	public FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan planFiniteDeviceCellExchange(
			List<FiniteDeviceExchangeCoordinator.CellEnergyDelta> deltas, ThermalCarrierContext markers
	) {
		return devices.planFiniteDeviceCellExchange(deltas, markers);
	}

	public void commitFiniteDeviceCellExchange(
			FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan plan, ThermalCarrierContext markers
	) {
		devices.commitFiniteDeviceCellExchange(plan, markers);
	}

	// ---- private modeling helpers ----

	private double modeledCellEnergy(int cell, double temperatureK, ThermalCarrierContext markers) {
		double total = 0.0;
		for (int marker = 0; marker < markers.markerCount(); marker++) if (markers.cell(marker) == cell) {
			total += modeledEnergy(marker, markers.mass(marker), markers.mixture(marker), temperatureK, markers);
		}
		for (CoherentPhaseStore.Group group : granularState.coherentPhases().snapshot()) if (group.cell() == cell) {
			int material = registry.materialHandle(group.phaseId());
			total += stateFunction.internalEnergyAtTemperature(material, group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts(), temperatureK);
		}
		for (int parcel = 0; parcel < markers.activeParcelCount(); parcel++) {
			if (markers.activeParcelCell(parcel) == cell) total += granularState.modeledGranularEnergy(
					markers.activeParcelMaterial(parcel), markers.activeParcelComposition(parcel),
					markers.activeParcelMass(parcel), temperatureK, markers);
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) if (key.cell() == cell) {
			total += granularState.modeledGranularEnergy(key.materialHandle(), key.compositionHandle(),
					markers.depositMass(key), temperatureK, markers);
		}
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal cell modeled energy is invalid");
		return total;
	}

	private ThermalMaterialDefinition material(int marker, ThermalCarrierContext markers) {
		return registry.material(materialByFluidHandle[markers.fluidMaterialHandle(marker)]);
	}

	private double baseEnergy(int marker, ThermalCarrierContext markers) {
		return stateFunction.baseEnergy(materialByFluidHandle[markers.fluidMaterialHandle(marker)],
				unitScale.kilograms(markers.mass(marker)),
				markers.mixture(marker), markers.chemicalRegistry());
	}

	private double modeledEnergy(
			int marker, double mass, ChemicalMixtureRecord mixture, double temperatureK, ThermalCarrierContext markers
	) {
		return liquidEnergy.modeledEnergy(marker, mass, mixture, temperatureK, markers);
	}
}
