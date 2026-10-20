package mmm.thermal;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.fluid.air.AirNetwork;
import mmm.granular.SolidVolume;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;

import java.util.ArrayList;
import java.util.ArrayDeque;
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
	private ThermalDomainState.Revision preparedPhaseRevision, preparedCandidatesRevision;
	private ThermalCarrierContext preparedPhaseOwners, preparedCandidatesOwners;
	private ThermalDomainState.PhaseCandidateCell[] preparedCandidates;

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
		return carrierAccess(markers,granular.coherentPhases());
	}
	private ThermalCellGrid.CarrierAccess carrierAccess(ThermalCarrierContext markers,CoherentPhaseStore coherent) {
		int liquidCount = markers.markerCount(), coherentCount = coherent.groupCount();
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
				if (carrier < activeStart) return coherent.group(carrier - coherentStart).cell();
				if (carrier < depositStart) return markers.activeParcelCell(carrier - activeStart);
				return depositKeys.get(carrier - depositStart).cell();
			}
			@Override public ThermalMaterialDefinition material(int carrier) {
				if (carrier < liquidCount) return liquidMaterial(carrier, markers);
				if (carrier < activeStart) return registry.material(registry.materialHandle(
						coherent.group(carrier - coherentStart).phaseId()));
				int material = carrier < depositStart ? markers.activeParcelMaterial(carrier - activeStart)
						: depositKeys.get(carrier - depositStart).materialHandle();
				return registry.material(materialByGranularHandle[material]);
			}
			@Override public double mass(int carrier) {
				if (carrier < liquidCount) return unitScale.kilograms(markers.mass(carrier));
				if (carrier < activeStart) return coherent.group(carrier - coherentStart).mass();
				return carrier < depositStart
						? markers.activeParcelMass(carrier - activeStart)
						: markers.depositMass(depositKeys.get(carrier - depositStart));
			}
			@Override public double volume(int carrier) {
				if (carrier < liquidCount) return markers.volume(carrier);
				if (carrier < activeStart) return SolidVolume.toCellVolume(
						coherent.group(carrier - coherentStart).occupiedVolumeSvq());
				return SolidVolume.toCellVolume(carrier < depositStart
						? markers.activeParcelSvq(carrier - activeStart)
						: markers.depositSvq(depositKeys.get(carrier - depositStart)));
			}
			@Override public double baseEnergy(int carrier) {
				if (carrier < liquidCount) return liquid.baseEnergy(carrier, markers);
				if (carrier < activeStart) {
					CoherentPhaseStore.Group group = coherent.group(carrier - coherentStart);
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
			@Override public void setInternalEnergies(double[] energies) {
				if(energies.length!=carrierCount()) throw new IllegalArgumentException("Thermal energy layout differs");
				for(double energy:energies) if(!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal carrier energy is not finite");
				markers.setLiquidInternalEnergies(Arrays.copyOf(energies,liquidCount));
				for(int carrier=liquidCount;carrier<energies.length;carrier++) setInternalEnergy(carrier,energies[carrier]);
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

	PhaseCellAssignment requireGranularReaction(
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
		if (granularEdit.kind() == GranularEnergyState.GranularReactionKind.ADD_ACTIVE) {
			granular.addGranularPortion(finalInventory, granularEdit.materialHandle(), granularEdit.finalMass(),
					granularEdit.finalComposition());
			granular.column().requireAddActive(0.0);
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
		return prepareRawCellEnergy(cell, finalInventory, cellEnergy);
	}

	void requirePendingPhaseCells(SignedDyadic[] pendingCellVolume, ThermalCarrierContext markers, ThermalDomainState.Revision revision) {
		if (pendingCellVolume == null || pendingCellVolume.length != cellGrid.cellCount()) {
			throw new IllegalArgumentException("Pending phase-volume cells are incompatible");
		}
		preparePhaseScan(markers,revision);
		for (int cell = 0; cell < pendingCellVolume.length; cell++) {
			if (pendingCellVolume[cell] == null) throw new IllegalArgumentException("Pending phase volume is missing");
			// Full vaporization leaves no condensed thermal carrier; its negative volume remains pending.
			if (pendingCellVolume[cell].signum() > 0 && !cellGrid.occupied(cell)) {
				throw new IllegalArgumentException("Pending phase volume names an empty thermal cell");
			}
		}
	}

	double temperatureInCell(int cell, ThermalCarrierContext markers) {
		cellGrid.rebuild(carrierAccess(markers));
		return cellGrid.occupied(cell) ? cellGrid.temperature(cell) : Double.NaN;
	}

	void preparePhaseScan(ThermalCarrierContext markers, ThermalDomainState.Revision revision) {
		if (preparedPhaseOwners == markers && revision.equals(preparedPhaseRevision)) return;
		cellGrid.rebuild(carrierAccess(markers));
		preparedPhaseOwners = markers;
		preparedPhaseRevision = revision;
	}

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
				: stateFunction.stateForInventory(inventory, energy).temperatureK();
	}


	ThermalDomainState.PhaseCandidateCell[] preparePhaseCandidateCells(int[] cells, ThermalCarrierContext markers,
			ThermalDomainState owner, ThermalDomainState.Revision revision) {
		preparePhaseScan(markers, revision);
		int count=cellGrid.cellCount();
		if (preparedCandidatesOwners != markers || !revision.equals(preparedCandidatesRevision)) {
			preparedCandidates = new ThermalDomainState.PhaseCandidateCell[count];
			preparedCandidatesOwners = markers;
			preparedCandidatesRevision = revision;
		}
		var access=cellGrid.carrierAccess();
		int coherentStart=markers.markerCount(),activeStart=coherentStart+granular.coherentPhases().groupCount();
		int depositStart=activeStart+markers.activeParcelCount();var deposits=markers.depositKeys();
		for(int cell:cells) {
			if(cell<0||cell>=count) throw new IllegalArgumentException("Invalid phase batch cell");
			if(preparedCandidates[cell]!=null)continue;
			var solids=new ArrayList<ThermalStateFunction.InventoryPortion>();var owners=new ArrayList<PhaseOwner>();
			// The prepared LTE grid already indexes every carrier by cell and has its reference energy.
			for(int carrier=cellGrid.firstCarrier(cell);carrier>=0;carrier=cellGrid.nextCarrier(carrier)) {
				if(carrier<coherentStart)continue;
				Map<String,Long> composition;
				if(carrier<activeStart) {
					var group=granular.coherentPhases().group(carrier-coherentStart);
					composition=group.composition()==null?Map.of():group.composition().stableAmounts();
				} else if(carrier<depositStart) composition=markers.solidComposition(markers.activeParcelComposition(carrier-activeStart));
				else composition=markers.solidComposition(deposits.get(carrier-depositStart).compositionHandle());
				var material=access.material(carrier);double mass=access.mass(carrier);
				solids.add(new ThermalStateFunction.InventoryPortion(registry.materialHandle(material.id()),mass,composition));
				owners.add(new PhaseOwner(material,mass,cellGrid.cachedBaseEnergy(carrier)));
			}
			preparedCandidates[cell]=new ThermalDomainState.PhaseCandidateCell(owner,revision,cell,
					cellGrid.exactCarrierEnergyInCell(cell),solids,owners.toArray(PhaseOwner[]::new));
		}
		return preparedCandidates;
	}

	record PhaseCellAssignment(int cell, ThermalCellEnergyAssignment.Plan raw,
			ThermalAuditLedger beforeAudit, ThermalAuditLedger afterAudit, SignedDyadic roundoff) { }

	record PhaseOwner(ThermalMaterialDefinition material, double mass, double base) { }
	private record PhaseInventory(PhaseOwner[] owners) implements ThermalCellEnergyAssignment.Inventory {
		public int first() { return owners.length == 0 ? -1 : 0; }
		public int next(int item) { return item + 1 < owners.length ? item + 1 : -1; }
		public ThermalMaterialDefinition material(int item) { return owners[item].material(); }
		public double mass(int item) { return owners[item].mass(); }
		public double baseEnergy(int item) { return owners[item].base(); }
	}

	ThermalCellEnergyAssignment.Trial trialPhaseCellEnergy(ThermalDomainState.PhaseCandidateCell current,
			List<ThermalStateFunction.InventoryPortion> prospectiveInventory,
			double gasEnergyBefore, double gasEnergyAfter, boolean replaceCoherent) {
		return trialPhaseCellEnergy(current, prospectiveInventory, gasEnergyBefore, gasEnergyAfter, replaceCoherent, SignedDyadic.ZERO);
	}
	ThermalCellEnergyAssignment.Trial trialPhaseCellEnergy(ThermalDomainState.PhaseCandidateCell current,
			List<ThermalStateFunction.InventoryPortion> prospectiveInventory,
			double gasEnergyBefore, double gasEnergyAfter, boolean replaceCoherent, SignedDyadic mechanicalHeat) {
		if (prospectiveInventory == null || !Double.isFinite(gasEnergyBefore) || !Double.isFinite(gasEnergyAfter))
			throw new IllegalArgumentException("Thermal phase candidate is invalid");
		PhaseOwner[] solids = current.unchangedOwners();
		int count = prospectiveInventory.size();
		for (var solid : solids) if (!replaceCoherent
				|| solid.material().carrierKind() == ThermalMaterialDefinition.CarrierKind.GRANULAR) count++;
		PhaseOwner[] owners = new PhaseOwner[count];
		int index = 0;
		for (var portion : prospectiveInventory) owners[index++] = prepareOwner(portion);
		for (var solid : solids) if (!replaceCoherent
				|| solid.material().carrierKind() == ThermalMaterialDefinition.CarrierKind.GRANULAR) owners[index++] = solid;
		SignedDyadic requested = current.cellEnergy().add(SignedDyadic.of(gasEnergyBefore)).subtract(SignedDyadic.of(gasEnergyAfter)).add(mechanicalHeat);
		return trialRawCellEnergy(new PhaseInventory(owners), requested);
	}

	ThermalCellEnergyAssignment.Trial trialOwnedCellEnergy(ThermalDomainState.PhaseCandidateCell current,
			List<ThermalStateFunction.InventoryPortion> inventory,SignedDyadic mechanicalHeat) {
		return trialRawCellEnergy(prepareInventory(inventory),current.cellEnergy().add(mechanicalHeat));
	}

	private PhaseOwner prepareOwner(ThermalStateFunction.InventoryPortion portion) {
		return new PhaseOwner(registry.material(portion.materialHandle()), portion.massKg(),
				stateFunction.baseEnergy(portion.materialHandle(), portion.massKg(), portion.speciesQuanta()));
	}

	private PhaseInventory prepareInventory(List<ThermalStateFunction.InventoryPortion> portions) {
		PhaseOwner[] owners = new PhaseOwner[portions.size()];
		for (int index = 0; index < owners.length; index++) owners[index] = prepareOwner(portions.get(index));
		return new PhaseInventory(owners);
	}

	private ThermalCellEnergyAssignment.Trial trialRawCellEnergy(PhaseInventory inventory, SignedDyadic requested) {
		if (inventory.owners().length == 0) {
			if (!requested.equals(SignedDyadic.ZERO)) throw new ThermalDomainState.EmptyPhaseCandidateEnergyException();
			return null;
		}
		return new ThermalCellEnergyAssignment.Trial(inventory, requested);
	}

	private PhaseCellAssignment prepareRawCellEnergy(int cell,
			List<ThermalStateFunction.InventoryPortion> portions, SignedDyadic requested) {
		return preparePhaseCellEnergy(cell, trialRawCellEnergy(prepareInventory(portions), requested), SignedDyadic.ZERO);
	}

	PhaseCellAssignment preparePhaseCellEnergy(int cell, ThermalCellEnergyAssignment.Trial trial, SignedDyadic emptyEnergyRemainder) {
		return preparePhaseCellEnergy(cell,trial,emptyEnergyRemainder,true);
	}
	PhaseCellAssignment prepareBatchCellEnergy(int cell,ThermalCellEnergyAssignment.Trial trial,SignedDyadic emptyEnergyRemainder) {
		return preparePhaseCellEnergy(cell,trial,emptyEnergyRemainder,false);
	}
	private PhaseCellAssignment preparePhaseCellEnergy(int cell,ThermalCellEnergyAssignment.Trial trial,SignedDyadic emptyEnergyRemainder,boolean individualAudit) {
		var raw = trial == null ? new ThermalCellEnergyAssignment.Plan(List.of(), SignedDyadic.ZERO, null)
				: ThermalCellEnergyAssignment.prepare(trial);
		SignedDyadic requested = trial == null ? emptyEnergyRemainder : trial.requested;
		ThermalAuditLedger before = audit.ledger();
		SignedDyadic roundoff=raw.actualEnergy().subtract(requested);
		ThermalAuditLedger after=individualAudit?before.withRoundoff(roundoff):null;
		if(individualAudit)after.expectedInventory();
		return new PhaseCellAssignment(cell,raw,before,after,roundoff);
	}

	private record CaloricOwner(ThermalMaterialDefinition material, double mass, double base) { }
	private record SolidEnergyWrite(boolean coherent,int ordinal,GranularEnergyColumn.DepositKey deposit,double energy) { }
	record PhaseBatchAssignment(List<PhaseCellAssignment> cells, int[] liquidCounts,
	                            ThermalAuditLedger beforeAudit, ThermalAuditLedger afterAudit, CoherentPhaseStore coherent,
	                            boolean callerPublishesAll,List<SolidEnergyWrite> solidEnergies) { }

	PhaseBatchAssignment preparePhaseBatch(List<PhaseCellAssignment> cells, int[] liquidCounts,
	                                      SignedDyadic gasRoundoff, SignedDyadic mechanicalHeat, ThermalCarrierContext markers) {
		return preparePhaseBatch(cells,liquidCounts,gasRoundoff,mechanicalHeat,markers,null);
	}
	PhaseBatchAssignment preparePhaseBatch(List<PhaseCellAssignment> cells,int[] liquidCounts,
	                                      SignedDyadic gasRoundoff,SignedDyadic mechanicalHeat,ThermalCarrierContext markers,
	                                      List<CoherentPhaseStore.Group> futureCoherent) {
		if (cells.size() != liquidCounts.length || gasRoundoff == null)
			throw new IllegalArgumentException("Phase batch cell counts differ");
		SignedDyadic roundoff = gasRoundoff;
		for (var cell : cells) roundoff = roundoff.add(cell.roundoff());
		var coherent=futureCoherent==null?null:granular.coherentPhases().prepareReplacement(futureCoherent);
		var after=audit.ledger().withMechanicalWork(mechanicalHeat).withRoundoff(roundoff);
		after.expectedInventory();
		return new PhaseBatchAssignment(List.copyOf(cells),liquidCounts.clone(),audit.ledger(),after,coherent,false,
				preparePhaseSolidEnergies(cells,liquidCounts,markers,coherent==null?granular.coherentPhases():coherent));
	}

	PhaseBatchAssignment prepareOwnedBatch(List<PhaseCellAssignment> cells,int[] counts,SignedDyadic heat) {
		SignedDyadic roundoff=SignedDyadic.ZERO;
		for(var cell:cells) roundoff=roundoff.add(cell.roundoff());
		var after=audit.ledger().withMechanicalWork(heat).withRoundoff(roundoff);after.expectedInventory();
		return new PhaseBatchAssignment(List.copyOf(cells),counts.clone(),audit.ledger(),after,null,true,List.of());
	}
	void commitOwnedBatch(PhaseBatchAssignment batch) { audit.replace(batch.afterAudit()); }

	void commitPhaseBatch(PhaseBatchAssignment batch, ThermalCarrierContext markers) {
		if(batch.coherent()!=null) granular.commitCoherentReplacement(batch.coherent());
		for(var write:batch.solidEnergies()) {
			if(write.coherent())granular.coherentPhases().setInternalEnergy(write.ordinal(),write.energy());
			else if(write.deposit()==null)granular.column().setActiveEnergy(write.ordinal(),write.energy());
			else granular.column().setDepositEnergy(write.deposit(),write.energy());
		}
		audit.replace(batch.afterAudit());
	}

	private List<SolidEnergyWrite> preparePhaseSolidEnergies(List<PhaseCellAssignment> cells,int[] liquidCounts,
			ThermalCarrierContext markers,CoherentPhaseStore coherent) {
		var byCell = new HashMap<Integer, HashMap<CaloricOwner, ArrayDeque<ThermalCellEnergyAssignment.Entry>>>();
		for (int index = 0; index < cells.size(); index++) {
			var cell = cells.get(index); var owners = new HashMap<CaloricOwner, ArrayDeque<ThermalCellEnergyAssignment.Entry>>();
			if (byCell.put(cell.cell(), owners) != null) throw new IllegalArgumentException("Repeated phase batch cell");
			var entries = cell.raw().entries(); int liquidCount = liquidCounts[index];
			if (liquidCount < 0 || liquidCount > entries.size()) throw new IllegalArgumentException("Phase liquid owner count differs");
			for (int owner = liquidCount; owner < entries.size(); owner++) {
				var entry = entries.get(owner);
				owners.computeIfAbsent(new CaloricOwner(entry.material(), entry.mass(), entry.base()), ignored -> new ArrayDeque<>()).add(entry);
			}
		}
		var writes=new ArrayList<SolidEnergyWrite>();
		for(int ordinal=0;ordinal<coherent.groupCount();ordinal++) {
			var group=coherent.group(ordinal);var matches=byCell.get(group.cell());if(matches==null)continue;
			var material=registry.material(registry.materialHandle(group.phaseId()));
			double base=stateFunction.baseEnergy(registry.materialHandle(group.phaseId()),group.mass(),
					group.composition()==null?Map.of():group.composition().stableAmounts());
			writes.add(new SolidEnergyWrite(true,ordinal,null,takePhaseSolidEnergy(matches,material,group.mass(),base)));
		}
		for(int ordinal=0;ordinal<markers.activeParcelCount();ordinal++) {
			var matches=byCell.get(markers.activeParcelCell(ordinal));if(matches==null)continue;
			int handle=materialByGranularHandle[markers.activeParcelMaterial(ordinal)];double mass=markers.activeParcelMass(ordinal);
			double base=stateFunction.baseEnergy(handle,mass,markers.solidComposition(markers.activeParcelComposition(ordinal)));
			writes.add(new SolidEnergyWrite(false,ordinal,null,takePhaseSolidEnergy(matches,registry.material(handle),mass,base)));
		}
		for(var key:markers.depositKeys()) {
			var matches=byCell.get(key.cell());if(matches==null)continue;
			int handle=materialByGranularHandle[key.materialHandle()];double mass=markers.depositMass(key);
			double base=stateFunction.baseEnergy(handle,mass,markers.solidComposition(key.compositionHandle()));
			writes.add(new SolidEnergyWrite(false,0,key,takePhaseSolidEnergy(matches,registry.material(handle),mass,base)));
		}
		for (var owners : byCell.values()) for (var entries : owners.values()) if (!entries.isEmpty())
			throw new IllegalStateException("Phase batch retains a missing solid heat bath");
		return List.copyOf(writes);
	}
	private static double takePhaseSolidEnergy(HashMap<CaloricOwner,ArrayDeque<ThermalCellEnergyAssignment.Entry>> matches,
			ThermalMaterialDefinition material,double mass,double base) {
		var entries=matches.get(new CaloricOwner(material,mass,base));
		if(entries==null||entries.isEmpty())throw new IllegalStateException("Phase batch solid heat bath changed");
		return entries.removeFirst().energy();
	}

	void commitPhaseCellEnergy(PhaseCellAssignment plan, ThermalCarrierContext markers) {
		if (audit.ledger() != plan.beforeAudit()) throw new IllegalStateException("Thermal phase audit plan is stale");
		ThermalCellGrid.CarrierAccess current = carrierAccess(markers);
		HashMap<CaloricOwner, ArrayDeque<ThermalCellEnergyAssignment.Entry>> byOwner = new HashMap<>();
		for (var entry : plan.raw().entries()) byOwner.computeIfAbsent(
				new CaloricOwner(entry.material(), entry.mass(), entry.base()), key -> new ArrayDeque<>()).add(entry);
		ArrayList<Integer> ordinals = new ArrayList<>();
		ArrayList<Double> energies = new ArrayList<>();
		for (int carrier = 0; carrier < current.carrierCount(); carrier++) if (current.cell(carrier) == plan.cell()) {
			var key = new CaloricOwner(current.material(carrier), current.mass(carrier), current.baseEnergy(carrier));
			var matches = byOwner.get(key);
			if (matches == null || matches.isEmpty()) throw new IllegalStateException("Thermal phase owner differs from its prepared inventory");
			ordinals.add(carrier); energies.add(matches.removeFirst().energy());
		}
		for (var matches : byOwner.values()) if (!matches.isEmpty())
			throw new IllegalStateException("Thermal phase plan retains a missing owner");
		for (int index = 0; index < ordinals.size(); index++) current.setInternalEnergy(ordinals.get(index), energies.get(index));
		cellGrid.rebuildAssignedPhaseCell(current, plan.cell(), plan.raw().actualEnergy(), plan.raw().actualState());
		audit.replace(plan.afterAudit());
	}

	void setTemperatureInCell(int cell, double temperatureK, ThermalCarrierContext markers) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		if (!cellGrid.occupied(cell)) throw new IllegalArgumentException("Cannot set temperature in an empty cell");
		cellGrid.setCellInternalEnergy(cell, modeledCellEnergy(cell, temperatureK, markers));
		audit.update(ledger -> ledger.withHeatExchange(exactEnergy(markers.markerCount()).subtract(before)));
	}

	void repartitionCellEnergy(
			int cell, double targetEnergy, ThermalCarrierContext markers, SignedDyadic inventoryBefore
	) {
		cellGrid.rebuild(carrierAccess(markers));
		cellGrid.setCellInternalEnergy(cell, targetEnergy);
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
		SignedDyadic targetCellEnergy = inventoryBefore.subtract(energyOutsideCell);
		cellGrid.rebuildPhaseCellWithEnergy(current, cell, targetCellEnergy);
		audit.update(ledger -> ledger.withRoundoff(exactEnergy(markers.markerCount()).subtract(inventoryBefore)));
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
