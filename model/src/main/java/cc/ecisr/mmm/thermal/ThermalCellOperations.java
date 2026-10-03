package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.SolverUnitScale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derived LTE-cell algorithms over explicit thermal owners. This service owns no inventory:
 * it composes carrier views, validates reaction/phase candidates and repartitions cell energy;
 * authoritative writes still go through the injected owner columns and audit state.
 */
final class ThermalCellOperations {
	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;
	private final SolverUnitScale unitScale;
	private final int[] materialByFluidHandle;
	private final int[] materialByGranularHandle;
	private final ThermalCellGrid cellGrid;
	private final ThermalAuditState audit;
	private final LiquidEnergyColumn liquid;
	private final GranularEnergyState granular;
	private final GasEnergyStore gas;

	ThermalCellOperations(
			ThermalRegistry registry, ThermalStateFunction stateFunction, SolverUnitScale unitScale,
			int[] materialByFluidHandle, int[] materialByGranularHandle, ThermalCellGrid cellGrid,
			ThermalAuditState audit, LiquidEnergyColumn liquid,
			GranularEnergyState granular, GasEnergyStore gas
	) {
		this.registry = registry;
		this.stateFunction = stateFunction;
		this.unitScale = unitScale;
		this.materialByFluidHandle = materialByFluidHandle;
		this.materialByGranularHandle = materialByGranularHandle;
		this.cellGrid = cellGrid;
		this.audit = audit;
		this.liquid = liquid;
		this.granular = granular;
		this.gas = gas;
	}

	ThermalCellGrid.CarrierAccess carrierAccess(ThermalCarrierContext markers) {
		int liquidCount = markers.markerCount(), coherentCount = granular.coherentPhases().groupCount();
		int activeCount = markers.activeParcelCount();
		List<GranularEnergyColumn.DepositKey> depositKeys = markers.depositKeys();
		if (activeCount != granular.column().activeCount()
				|| !depositKeys.equals(granular.column().depositSnapshot().stream()
				.map(GranularEnergyColumn.DepositEnergy::key).toList())) {
			throw new IllegalStateException("Granular thermal ordinals differ from granular inventory");
		}
		int coherentStart = liquidCount, activeStart = Math.addExact(coherentStart, coherentCount);
		int depositStart = Math.addExact(activeStart, activeCount);
		return new ThermalCellGrid.CarrierAccess() {
			@Override public int carrierCount() { return Math.addExact(depositStart, depositKeys.size()); }
			@Override public int cell(int carrier) {
				if (carrier < liquidCount) return markers.cell(carrier);
				if (carrier < activeStart) return granular.coherentPhases().group(carrier - coherentStart).cell();
				if (carrier < depositStart) return markers.activeParcelCell(carrier - activeStart);
				return depositKeys.get(carrier - depositStart).cell();
			}
			@Override public ThermalMaterialDefinition material(int carrier) {
				if (carrier < liquidCount) return liquidMaterial(carrier, markers);
				if (carrier < activeStart) return registry.material(registry.materialHandle(
						granular.coherentPhases().group(carrier - coherentStart).phaseId()));
				int material = carrier < depositStart ? markers.activeParcelMaterial(carrier - activeStart)
						: depositKeys.get(carrier - depositStart).materialHandle();
				return registry.material(materialByGranularHandle[material]);
			}
			@Override public double mass(int carrier) {
				if (carrier < liquidCount) return unitScale.kilograms(markers.mass(carrier));
				if (carrier < activeStart) return granular.coherentPhases().group(carrier - coherentStart).mass();
				return carrier < depositStart
						? markers.activeParcelMass(carrier - activeStart)
						: markers.depositMass(depositKeys.get(carrier - depositStart));
			}
			@Override public double volume(int carrier) {
				if (carrier < liquidCount) return markers.volume(carrier);
				if (carrier < activeStart) return SolidVolume.toCellVolume(
						granular.coherentPhases().group(carrier - coherentStart).occupiedVolumeSvq());
				return SolidVolume.toCellVolume(carrier < depositStart
						? markers.activeParcelSvq(carrier - activeStart)
						: markers.depositSvq(depositKeys.get(carrier - depositStart)));
			}
			@Override public double baseEnergy(int carrier) {
				if (carrier < liquidCount) return liquid.baseEnergy(carrier, markers);
				if (carrier < activeStart) {
					CoherentPhaseStore.Group group = granular.coherentPhases().group(carrier - coherentStart);
					return stateFunction.baseEnergy(registry.materialHandle(group.phaseId()), group.mass(),
							group.composition() == null ? Map.of() : group.composition().stableAmounts());
				}
				int material, composition; double mass;
				if (carrier < depositStart) {
					int parcel = carrier - activeStart;
					material = markers.activeParcelMaterial(parcel);
					composition = markers.activeParcelComposition(parcel);
					mass = markers.activeParcelMass(parcel);
				} else {
					GranularEnergyColumn.DepositKey key = depositKeys.get(carrier - depositStart);
					material = key.materialHandle(); composition = key.compositionHandle();
					mass = markers.depositMass(key);
				}
				return stateFunction.baseEnergy(materialByGranularHandle[material], mass,
						markers.solidComposition(composition));
			}
			@Override public double internalEnergy(int carrier) {
				if (carrier < liquidCount) return liquid.energy(carrier);
				if (carrier < activeStart) return granular.coherentPhases()
						.group(carrier - coherentStart).internalEnergy();
				return carrier < depositStart ? granular.column().activeEnergy(carrier - activeStart)
						: granular.column().depositEnergy(depositKeys.get(carrier - depositStart));
			}
			@Override public void setInternalEnergy(int carrier, double energy) {
				if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal carrier energy is not finite");
				if (carrier < liquidCount) liquid.setEnergy(carrier, energy);
				else if (carrier < activeStart) granular.coherentPhases()
						.setInternalEnergy(carrier - coherentStart, energy);
				else if (carrier < depositStart) granular.column().setActiveEnergy(carrier - activeStart, energy);
				else granular.column().setDepositEnergy(depositKeys.get(carrier - depositStart), energy);
			}
		};
	}

	ThermalDomainState.ReactionPlan requireGranularReaction(
			int cell, List<GranularEnergyState.ReactionMarker> markerReplacements,
			GranularEnergyState.GranularReactionEdit granularEdit, ThermalCarrierContext markers
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
		for (CoherentPhaseStore.Group group : granular.coherentPhases().snapshot()) if (group.cell() == cell) {
			finalInventory.add(new ThermalStateFunction.InventoryPortion(registry.materialHandle(group.phaseId()), group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts()));
		}
		boolean matchedGranular = granularEdit.kind() == GranularEnergyState.GranularReactionKind.ADD_ACTIVE;
		for (int active = 0; active < markers.activeParcelCount(); active++) if (markers.activeParcelCell(active) == cell) {
			if (granularEdit.activeOrdinal() == active) {
				matchedGranular = true;
				if (granularEdit.kind() != GranularEnergyState.GranularReactionKind.REMOVE_ACTIVE) granular.addGranularPortion(
						finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(), granularEdit.finalComposition());
			} else granular.addGranularPortion(finalInventory, markers.activeParcelMaterial(active),
					markers.activeParcelMass(active), markers.solidComposition(markers.activeParcelComposition(active)));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys(cell)) {
			if (key.equals(granularEdit.depositKey())) {
				matchedGranular = true;
				if (granularEdit.kind() != GranularEnergyState.GranularReactionKind.REMOVE_DEPOSIT) granular.addGranularPortion(
						finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(), granularEdit.finalComposition());
			} else granular.addGranularPortion(finalInventory, key.materialHandle(), markers.depositMass(key),
					markers.solidComposition(key.compositionHandle()));
		}
		if (!matchedGranular) throw new IllegalArgumentException("Thermal reaction names a missing granular carrier");
		if (granularEdit.kind() == GranularEnergyState.GranularReactionKind.ADD_ACTIVE) {
			granular.addGranularPortion(finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(),
					granularEdit.finalComposition());
			granular.column().requireAddActive(0.0);
		}
		ThermalStateFunction.State state = stateFunction.stateForInventory(finalInventory, cellEnergy.toDouble());
		return new ThermalDomainState.ReactionPlan(
				cell, cellEnergy, exactEnergy(markers.markerCount()), state.temperatureK());
	}

	void commitGranularReaction(ThermalDomainState.ReactionPlan plan, ThermalCarrierContext markers) {
		if (plan == null) throw new IllegalArgumentException("Thermal granular reaction plan is missing");
		cellGrid.rebuildWithCellEnergy(carrierAccess(markers), plan.cell(), plan.cellEnergy().toDouble());
		double[] target = cellGrid.copyInternalEnergy();
		cellGrid.setCellInternalEnergies(target);
		audit.update(ledger -> ledger.withRoundoff(exactEnergy(markers.markerCount()).subtract(plan.inventoryBefore())));
	}

	void requirePendingPhaseCells(SignedDyadic[] pendingCellVolume, ThermalCarrierContext markers) {
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

	double temperatureInCell(int cell, ThermalCarrierContext markers) {
		cellGrid.rebuild(carrierAccess(markers));
		return cellGrid.occupied(cell) ? cellGrid.temperature(cell) : Double.NaN;
	}

	void preparePhaseScan(ThermalCarrierContext markers) { cellGrid.rebuild(carrierAccess(markers)); }

	double phaseScanTemperatureInCell(int cell) {
		if (cell < 0 || cell >= cellGrid.cellCount()) {
			throw new IllegalArgumentException("Thermal phase-scan cell is invalid");
		}
		return cellGrid.occupied(cell) ? cellGrid.temperature(cell) : Double.NaN;
	}

	double observeTemperatureInCell(int cell, ThermalCarrierContext markers) {
		if (cell < 0 || cell >= cellGrid.cellCount() || markers == null) {
			throw new IllegalArgumentException("Thermal observation cell is invalid");
		}
		// Keep local observations independent of the reusable transport grid.
		// Bulk solver temperature reads use preparePhaseScan.
		ArrayList<ThermalStateFunction.InventoryPortion> inventory = new ArrayList<>();
		SignedDyadic energy = SignedDyadic.ZERO;
		for (int marker = markers.nextMarkerInCell(cell, -1); marker >= 0; marker = markers.nextMarkerInCell(cell, marker)) {
			inventory.add(new ThermalStateFunction.InventoryPortion(
					materialByFluidHandle[markers.fluidMaterialHandle(marker)],
					unitScale.kilograms(markers.mass(marker)),
					ThermalStateFunction.stableAmounts(markers.mixture(marker), markers.chemicalRegistry())));
			energy = energy.add(SignedDyadic.of(liquid.energy(marker)));
		}
		for (CoherentPhaseStore.Group group : granular.coherentPhases().snapshot()) if (group.cell() == cell) {
			inventory.add(new ThermalStateFunction.InventoryPortion(
					registry.materialHandle(group.phaseId()), group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts()));
			energy = energy.add(SignedDyadic.of(group.internalEnergy()));
		}
		if (markers.activeParcelCount() != granular.column().activeCount()) {
			throw new IllegalStateException("Granular thermal ordinals differ from active inventory");
		}
		for (int parcel = 0; parcel < markers.activeParcelCount(); parcel++) {
			if (markers.activeParcelCell(parcel) != cell) continue;
			granular.addGranularPortion(inventory, markers.activeParcelMaterial(parcel),
					markers.activeParcelMass(parcel), markers.solidComposition(markers.activeParcelComposition(parcel)));
			energy = energy.add(SignedDyadic.of(granular.column().activeEnergy(parcel)));
		}
		List<GranularEnergyColumn.DepositKey> depositKeys = markers.depositKeys();
		if (!depositKeys.equals(granular.column().depositSnapshot().stream()
				.map(GranularEnergyColumn.DepositEnergy::key).toList())) {
			throw new IllegalStateException("Granular thermal ordinals differ from deposit inventory");
		}
		for (GranularEnergyColumn.DepositKey key : depositKeys) if (key.cell() == cell) {
			granular.addGranularPortion(inventory, key.materialHandle(), markers.depositMass(key),
					markers.solidComposition(key.compositionHandle()));
			energy = energy.add(SignedDyadic.of(granular.column().depositEnergy(key)));
		}
		return inventory.isEmpty() ? Double.NaN
				: stateFunction.stateForInventory(inventory, energy.toDouble()).temperatureK();
	}

	double phaseCandidateTemperature(
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

	ThermalDomainState.PhaseCandidateCell preparePhaseCandidateCell(int cell, ThermalCarrierContext markers) {
		if (cell < 0 || cell >= cellGrid.cellCount() || markers == null) {
			throw new IllegalArgumentException("Thermal phase candidate cell is invalid");
		}
		SignedDyadic cellEnergy = cellGrid.exactCarrierEnergyInCell(cell);
		ArrayList<ThermalStateFunction.InventoryPortion> unchangedSolids = new ArrayList<>();
		for (CoherentPhaseStore.Group group : granular.coherentPhases().snapshot()) if (group.cell() == cell) {
			unchangedSolids.add(new ThermalStateFunction.InventoryPortion(
					registry.materialHandle(group.phaseId()), group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts()));
		}
		for (int active = 0; active < markers.activeParcelCount(); active++) {
			if (markers.activeParcelCell(active) == cell) granular.addGranularPortion(unchangedSolids,
					markers.activeParcelMaterial(active), markers.activeParcelMass(active),
					markers.solidComposition(markers.activeParcelComposition(active)));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys(cell)) {
			granular.addGranularPortion(unchangedSolids, key.materialHandle(), markers.depositMass(key),
					markers.solidComposition(key.compositionHandle()));
		}
		return new ThermalDomainState.PhaseCandidateCell(cell, cellEnergy, List.copyOf(unchangedSolids));
	}

	double requirePhaseCandidateState(
			ThermalDomainState.PhaseCandidateCell current,
			List<ThermalStateFunction.InventoryPortion> liquidInventory, double gasEnergyBefore, double gasEnergyAfter
	) {
		if (current == null || liquidInventory == null
				|| !Double.isFinite(gasEnergyBefore) || !Double.isFinite(gasEnergyAfter)) {
			throw new IllegalArgumentException("Thermal gas/liquid candidate is invalid");
		}
		ArrayList<ThermalStateFunction.InventoryPortion> finalInventory = new ArrayList<>(
				liquidInventory.size() + current.unchangedSolids().size());
		finalInventory.addAll(liquidInventory);
		finalInventory.addAll(current.unchangedSolids());
		SignedDyadic energy = current.cellEnergy().add(SignedDyadic.of(gasEnergyBefore))
				.subtract(SignedDyadic.of(gasEnergyAfter));
		if (finalInventory.isEmpty()) {
			if (!energy.equals(SignedDyadic.ZERO)) {
				throw new ThermalDomainState.EmptyPhaseCandidateEnergyException();
			}
			return Double.NaN;
		}
		return stateFunction.stateForInventory(finalInventory, energy.toDouble()).temperatureK();
	}

	void setTemperatureInCell(int cell, double temperatureK, ThermalCarrierContext markers) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		if (!cellGrid.occupied(cell)) throw new IllegalArgumentException("Cannot set temperature in an empty cell");
		double[] target = cellGrid.copyInternalEnergy();
		target[cell] = modeledCellEnergy(cell, temperatureK, markers);
		cellGrid.setCellInternalEnergies(target);
		audit.update(ledger -> ledger.withHeatExchange(exactEnergy(markers.markerCount()).subtract(before)));
	}

	void repartitionCellEnergy(
			int cell, double targetEnergy, ThermalCarrierContext markers, SignedDyadic inventoryBefore
	) {
		cellGrid.rebuild(carrierAccess(markers));
		double[] target = cellGrid.copyInternalEnergy();
		target[cell] = targetEnergy;
		cellGrid.setCellInternalEnergies(target);
		audit.update(ledger -> ledger.withRoundoff(exactEnergy(markers.markerCount()).subtract(inventoryBefore)));
	}

	void repartitionCellEnergyConserving(
			int cell, ThermalCarrierContext markers, SignedDyadic inventoryBefore
	) {
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
		audit.update(ledger -> ledger.withRoundoff(exactEnergy(markers.markerCount()).subtract(inventoryBefore)));
	}

	void repartitionPhaseCellAndGasEnergyConserving(
			ThermalDomainState.PhaseCandidateCell beforeCell, double gasEnergyBefore, double gasEnergyAfter,
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
		audit.update(ledger -> ledger.withRoundoff(afterPair.subtract(beforePair)));
	}

	ThermalDisplaySnapshot displaySnapshot(ThermalCarrierContext markers, AirNetwork network) {
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
			long solid = granular.coherentPhases().cellOccupiedSvq(cell);
			if (solid > 0L) {
				coherent[cell] = (byte) Math.min(255L,
						(solid * 255L + SolidVolume.SVQ_PER_CELL / 2L) / SolidVolume.SVQ_PER_CELL);
				flags[cell] |= ThermalDisplaySnapshot.FLAG_COHERENT;
			}
		}
		ArrayList<ThermalDisplaySnapshot.GasNode> gasNodes = new ArrayList<>(network.nodeCount());
		for (int node : network.nodeIds()) gasNodes.add(new ThermalDisplaySnapshot.GasNode(node,
				quantizedTemperature(gas.temperature(node, network)),
				quantizedGaugePressure(gas.gaugePressure(node, network))));
		return new ThermalDisplaySnapshot(temperatures, coherent, flags, gasNodes);
	}

	private static int quantizedTemperature(double temperatureK) {
		if (!(Double.isFinite(temperatureK) && temperatureK > 0.0)) {
			throw new IllegalArgumentException("Thermal display temperature is not finite and positive");
		}
		long quantized = Math.round(temperatureK * 16.0);
		return (int) Math.max(1L, Math.min(ThermalDisplaySnapshot.MAX_TEMPERATURE_Q, quantized));
	}

	private static int quantizedGaugePressure(double pressurePa) {
		if (!Double.isFinite(pressurePa)) {
			throw new IllegalArgumentException("Thermal display pressure is not finite");
		}
		long decaPascal = Math.round(pressurePa / 10.0);
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, decaPascal));
	}

	private ThermalStateFunction.State phaseCandidateState(
			int cell, List<ThermalStateFunction.InventoryPortion> phaseInventory,
			SignedDyadic cellEnergy, ThermalCarrierContext markers
	) {
		ArrayList<ThermalStateFunction.InventoryPortion> finalInventory = new ArrayList<>(phaseInventory);
		for (int active = 0; active < markers.activeParcelCount(); active++) {
			if (markers.activeParcelCell(active) == cell) granular.addGranularPortion(finalInventory,
					markers.activeParcelMaterial(active), markers.activeParcelMass(active),
					markers.solidComposition(markers.activeParcelComposition(active)));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys(cell)) {
			granular.addGranularPortion(finalInventory, key.materialHandle(), markers.depositMass(key),
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

	private double modeledCellEnergy(int cell, double temperatureK, ThermalCarrierContext markers) {
		double total = 0.0;
		for (int marker = 0; marker < markers.markerCount(); marker++) if (markers.cell(marker) == cell) {
			total += liquid.modeledEnergy(
					marker, markers.mass(marker), markers.mixture(marker), temperatureK, markers);
		}
		for (CoherentPhaseStore.Group group : granular.coherentPhases().snapshot()) if (group.cell() == cell) {
			int material = registry.materialHandle(group.phaseId());
			total += stateFunction.internalEnergyAtTemperature(material, group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts(), temperatureK);
		}
		for (int parcel = 0; parcel < markers.activeParcelCount(); parcel++) {
			if (markers.activeParcelCell(parcel) == cell) total += granular.modeledGranularEnergy(
					markers.activeParcelMaterial(parcel), markers.activeParcelComposition(parcel),
					markers.activeParcelMass(parcel), temperatureK, markers);
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys(cell)) {
			total += granular.modeledGranularEnergy(key.materialHandle(), key.compositionHandle(),
					markers.depositMass(key), temperatureK, markers);
		}
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal cell modeled energy is invalid");
		return total;
	}

	private SignedDyadic exactEnergy(int liquidCount) {
		return liquid.exactLiquidEnergy(liquidCount)
				.add(granular.coherentPhases().exactEnergy())
				.add(granular.column().exactEnergy())
				.add(gas.exactGasEnergy());
	}

	private ThermalMaterialDefinition liquidMaterial(int marker, ThermalCarrierContext markers) {
		return registry.material(materialByFluidHandle[markers.fluidMaterialHandle(marker)]);
	}

	private double liquidBaseEnergy(int marker, ThermalCarrierContext markers) {
		return stateFunction.baseEnergy(materialByFluidHandle[markers.fluidMaterialHandle(marker)],
				unitScale.kilograms(markers.mass(marker)),
				markers.mixture(marker), markers.chemicalRegistry());
	}
}
