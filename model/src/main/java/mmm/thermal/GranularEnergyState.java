package mmm.thermal;

import mmm.units.SignedDyadic;

import java.util.List;
import java.util.Map;
import mmm.granular.GranularInventory;

/**
 * Owns the granular and coherent-solid energy truth: the granular energy column (active
 * parcels plus deposit groups keyed by stable inventory identity) and the coherent phase
 * store. Every ordinal/keystone edit goes through here with its audit booking, so this
 * state's revision is the granular/coherent thermal truth token.
 */
public final class GranularEnergyState {
	private final ThermalRegistry registry;
	private final GranularEnergyColumn granularEnergy;
	private final CoherentPhaseStore coherentPhases;
	private final ThermalStateFunction stateFunction;
	private final int[] materialByGranularHandle;
	private final double insertionTemperatureK;
	private final ThermalAuditState audit;
	private long revision;

	GranularEnergyState(
			ThermalRegistry registry, GranularEnergyColumn granularEnergy, CoherentPhaseStore coherentPhases,
			ThermalStateFunction stateFunction, int[] materialByGranularHandle,
			double insertionTemperatureK, ThermalAuditState audit
	) {
		this.registry = registry;
		this.granularEnergy = granularEnergy;
		this.coherentPhases = coherentPhases;
		this.stateFunction = stateFunction;
		this.materialByGranularHandle = materialByGranularHandle;
		this.insertionTemperatureK = insertionTemperatureK;
		this.audit = audit;
	}

	public enum GranularReactionKind { ADD_ACTIVE, REPLACE_ACTIVE, REMOVE_ACTIVE, REPLACE_DEPOSIT, REMOVE_DEPOSIT }

	public record ReactionMarker(int marker, double mass, mmm.chemistry.ChemicalMixtureRecord mixture) {
		public ReactionMarker {
			if (marker < 0 || !(Double.isFinite(mass) && mass > 0.0) || mixture == null) {
				throw new IllegalArgumentException("Thermal reaction marker is invalid");
			}
		}
	}

	public record GranularReactionEdit(
			GranularReactionKind kind, int activeOrdinal, GranularEnergyColumn.DepositKey depositKey,
			int materialHandle, double finalMass, Map<String, Long> finalComposition
	) {
		public GranularReactionEdit {
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

	public long revision() { return revision; }

	void addMechanicalHeat(int ordinal,SignedDyadic heat) {
		if(heat.signum()==0)return;
		double before=granularEnergy.activeEnergy(ordinal);
		var exact=SignedDyadic.of(before).add(heat);
		double after=exact.toDouble();
		granularEnergy.setActiveEnergy(ordinal,after);
		audit.replace(audit.ledger().withMechanicalWork(heat).withRoundoff(SignedDyadic.of(after).subtract(exact)));
		revision++;
	}

	public GranularEnergyColumn column() { return granularEnergy; }
	public CoherentPhaseStore coherentPhases() { return coherentPhases; }

	ThermalMaterialDefinition granularMaterial(int granularHandle) {
		return registry.material(materialByGranularHandle[granularHandle]);
	}

	double granularBaseEnergy(int granularHandle, double mass, Map<String, Long> composition) {
		return stateFunction.baseEnergy(materialByGranularHandle[granularHandle], mass, composition);
	}

	void addGranularReactionActive() { granularEnergy.addActive(0.0); revision++; }

	double takeGranularReactionActive(int active, long takenMassUnits, long totalMassUnits) {
		double taken = takenMassUnits == totalMassUnits ? granularEnergy.removeActiveSwap(active)
				: granularEnergy.takeActivePortion(active, takenMassUnits, totalMassUnits);
		revision++;
		return taken;
	}

	double takeGranularReactionDeposit(GranularEnergyColumn.DepositKey key, long takenMassUnits, long totalMassUnits) {
		double taken = granularEnergy.takeDeposit(key, takenMassUnits, totalMassUnits);
		revision++;
		return taken;
	}

	GranularInventory.EnergyPlan planActiveToDeposits(List<GranularInventory.DepositTransfer> transfers) {
		GranularEnergyColumn.ActiveToDepositsPlan columnPlan = granularEnergy.planActiveToDeposits(transfers);
		if (columnPlan == null) return null;
		long baseRevision = revision, auditRevision = audit.revision();
		ThermalAuditLedger auditAfter = audit.ledger().withMechanicalWork(columnPlan.mechanicalHeat).withRoundoff(columnPlan.roundoff);
		return new GranularInventory.EnergyPlan() {
			@Override public void requireCurrent() {
				if (revision != baseRevision || audit.revision() != auditRevision) {
					throw new IllegalStateException("Granular thermal state changed after settlement planning");
				}
				granularEnergy.requireCurrent(columnPlan);
			}
			@Override public void commit() {
				requireCurrent();
				granularEnergy.commitActiveToDeposits(columnPlan);
				audit.replace(auditAfter); revision++;
			}
		};
	}

	boolean canActiveMerge(int first, int second, int material, double mass, Map<String, Long> composition, SignedDyadic heat) {
		double left = granularEnergy.activeEnergy(first), right = granularEnergy.activeEnergy(second);
		double merged = SignedDyadic.of(left).add(SignedDyadic.of(right)).add(heat).toDouble();
		if (!Double.isFinite(merged)) return false;
		try {
			stateFunction.stateForInventory(List.of(new ThermalStateFunction.InventoryPortion(
					materialByGranularHandle[material], mass, composition)), merged);
		} catch (ThermalStateFunction.EnergyOutsideValidityException outside) {
			return false;
		}
		SignedDyadic roundoff = SignedDyadic.of(merged).subtract(SignedDyadic.of(left)).subtract(SignedDyadic.of(right)).subtract(heat);
		audit.ledger().withMechanicalWork(heat).withRoundoff(roundoff);
		return true;
	}

	void commitActiveMerge(int first, int second, SignedDyadic heat) {
		SignedDyadic before = SignedDyadic.of(granularEnergy.activeEnergy(first)).add(SignedDyadic.of(granularEnergy.activeEnergy(second)));
		int last = granularEnergy.activeCount() - 1;
		int target = first == last && second != last ? second : first;
		granularEnergy.mergeActive(first, second, heat);
		SignedDyadic roundoff = SignedDyadic.of(granularEnergy.activeEnergy(target)).subtract(before).subtract(heat);
		audit.update(ledger -> ledger.withMechanicalWork(heat).withRoundoff(roundoff));
		revision++;
	}

	GranularInventory.EnergyPlan planDepositWake(GranularEnergyColumn.DepositKey source, long takenMassUnits, long totalMassUnits) {
		var columnPlan = granularEnergy.planDepositWake(source, takenMassUnits, totalMassUnits);
		long baseRevision = revision, auditRevision = audit.revision();
		var auditAfter = audit.ledger().withRoundoff(columnPlan.roundoff);
		return new GranularInventory.EnergyPlan() {
			@Override public void requireCurrent() {
				if (revision != baseRevision || audit.revision() != auditRevision)
					throw new IllegalStateException("Granular thermal state changed after wake planning");
				granularEnergy.requireCurrent(columnPlan);
			}
			@Override public void commit() {
				requireCurrent(); granularEnergy.commitDepositWake(columnPlan); audit.replace(auditAfter); revision++;
			}
		};
	}

	GranularInventory.EnergyPlan planDepositMove(GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedMassUnits, long sourceMassUnits, SignedDyadic heat) {
		var columnPlan = granularEnergy.planDepositMove(source, target, movedMassUnits, sourceMassUnits, heat);
		long baseRevision = revision, auditRevision = audit.revision();
		var auditAfter = audit.ledger().withMechanicalWork(heat).withRoundoff(columnPlan.roundoff);
		return new GranularInventory.EnergyPlan() {
			@Override public void requireCurrent() {
				if (revision != baseRevision || audit.revision() != auditRevision)
					throw new IllegalStateException("Thermal state changed after bed relaxation planning");
				granularEnergy.requireCurrent(columnPlan);
			}
			@Override public void commit() {
				requireCurrent(); granularEnergy.commitDepositMove(columnPlan); audit.replace(auditAfter); revision++;
			}
		};
	}

	void clearGranularExternal() {
		SignedDyadic removed = granularEnergy.exactEnergy();
		ThermalAuditLedger auditAfter = audit.ledger().withExternalSinkEnergy(removed);
		granularEnergy.clear();
		audit.replace(auditAfter);
		revision++;
	}

	double[] planExternalActiveInsertions(int firstOrdinal, int count, ThermalCarrierContext markers) {
		if (firstOrdinal != granularEnergy.activeCount() || count < 0
				|| count > mmm.granular.GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION - firstOrdinal
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
		externalInsertionAudit(SignedDyadic.sum(energies));
		return energies;
	}

	double planExternalGranularEnergy(int material, int composition, double mass, ThermalCarrierContext markers) {
		double energy = modeledGranularEnergy(material, composition, mass, insertionTemperatureK, markers);
		granularEnergy.requireAddActive(energy);
		externalInsertionAudit(SignedDyadic.of(energy));
		return energy;
	}

	void commitExternalActiveInsertions(double[] energies) {
		if (energies == null) throw new IllegalArgumentException("Granular thermal insertion plan is missing");
		if (energies.length > mmm.granular.GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION
				- granularEnergy.activeCount()) {
			throw new IllegalStateException("Granular thermal active budget exceeded");
		}
		for (double energy : energies) granularEnergy.requireAddActive(energy);
		ThermalAuditLedger auditAfter = externalInsertionAudit(SignedDyadic.sum(energies));
		for (double energy : energies) granularEnergy.addActive(energy);
		audit.replace(auditAfter);
		revision++;
	}

	private ThermalAuditLedger externalInsertionAudit(SignedDyadic source) {
		ThermalAuditLedger after = audit.ledger().withExternalSourceEnergy(source);
		after.expectedInventory();
		return after;
	}

	double previewActiveTake(int active, long takenMassUnits, long totalMassUnits) {
		return granularEnergy.previewActiveTake(active, takenMassUnits, totalMassUnits);
	}

	double previewDepositTake(GranularEnergyColumn.DepositKey key, long takenMassUnits, long totalMassUnits) {
		return granularEnergy.previewDepositTake(key, takenMassUnits, totalMassUnits);
	}

	double commitExternalActiveTake(int active, long takenMassUnits, long totalMassUnits) {
		double before = granularEnergy.activeEnergy(active);
		double preview = granularEnergy.previewActiveTake(active, takenMassUnits, totalMassUnits);
		double remaining = granularEnergy.previewActiveRemaining(active, takenMassUnits, totalMassUnits);
		ThermalAuditLedger auditAfter = externalTakeAudit(before, preview, remaining);
		double removed = takenMassUnits == totalMassUnits ? granularEnergy.removeActiveSwap(active)
				: granularEnergy.takeActivePortion(active, takenMassUnits, totalMassUnits);
		audit.replace(auditAfter);
		revision++;
		return removed;
	}

	double commitExternalDepositTake(GranularEnergyColumn.DepositKey key, long takenMassUnits, long totalMassUnits) {
		double before = granularEnergy.depositEnergy(key);
		double preview = granularEnergy.previewDepositTake(key, takenMassUnits, totalMassUnits);
		double remaining = granularEnergy.previewDepositRemaining(key, takenMassUnits, totalMassUnits);
		ThermalAuditLedger auditAfter = externalTakeAudit(before, preview, remaining);
		double removed = granularEnergy.takeDeposit(key, takenMassUnits, totalMassUnits);
		audit.replace(auditAfter);
		revision++;
		return removed;
	}

	private ThermalAuditLedger externalTakeAudit(double before, double removed, double remaining) {
		SignedDyadic exported = SignedDyadic.of(removed);
		SignedDyadic after = SignedDyadic.of(remaining);
		return audit.ledger().withExternalSinkEnergy(exported)
				.withRoundoff(after.add(exported).subtract(SignedDyadic.of(before)));
	}

	double modeledGranularEnergy(
			int granularMaterial, int composition, double mass, double temperatureK, ThermalCarrierContext markers
	) {
		if (granularMaterial < 0 || granularMaterial >= materialByGranularHandle.length) {
			throw new IllegalArgumentException("Unknown granular thermal material handle");
		}
		int thermalHandle = materialByGranularHandle[granularMaterial];
		ThermalMaterialDefinition material = registry.material(thermalHandle);
		double physicalMass = mass;
		Map<String, Long> species = markers.solidComposition(composition);
		double energy = ThermalCaloricInversion.energyAt(material, physicalMass,
				stateFunction.baseEnergy(thermalHandle, physicalMass, species), temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Granular modeled energy is invalid");
		stateFunction.stateForInternalEnergy(thermalHandle, physicalMass, species, energy);
		return energy;
	}

	void addGranularPortion(
			List<ThermalStateFunction.InventoryPortion> inventory, int granularMaterial,
			double mass, Map<String, Long> composition
	) {
		if (granularMaterial < 0 || granularMaterial >= materialByGranularHandle.length) {
			throw new IllegalArgumentException("Unknown granular reaction thermal material");
		}
		inventory.add(new ThermalStateFunction.InventoryPortion(
				materialByGranularHandle[granularMaterial], mass, composition));
	}

	public final class CoherentExternalPlan implements GranularInventory.EnergyPlan {
		private final CoherentPhaseStore.Group candidate;
		private final int index;
		private final long sourceRevision = revision;
		private final ThermalAuditLedger auditBefore = audit.ledger(), auditAfter;
		private CoherentExternalPlan(CoherentPhaseStore.Group candidate, int index, ThermalAuditLedger auditAfter) {
			this.candidate = candidate; this.index = index; this.auditAfter = auditAfter;
		}
		public ThermalAuditLedger auditAfter() { return auditAfter; }
		@Override public void requireCurrent() {
			if (revision != sourceRevision || audit.ledger() != auditBefore)
				throw new IllegalStateException("Coherent external source changed after preparation");
		}
		@Override public void commit() {
			requireCurrent();
			coherentPhases.commitPreparedAdd(index, candidate);
			audit.replace(auditAfter); revision++;
		}
	}

	CoherentExternalPlan planCoherentExternal(CoherentPhaseStore.Group group) {
		int material = registry.materialHandle(group.phaseId());
		if (registry.material(material).carrierKind() != ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID) {
			throw new IllegalArgumentException("Coherent group phase is not a coherent-solid thermal material");
		}
		Map<String, Long> composition = group.composition() == null ? Map.of() : group.composition().stableAmounts();
		stateFunction.stateForInternalEnergy(material, group.mass(), composition, group.internalEnergy());
		CoherentPhaseStore.Group candidate = coherentPhases.previewAdd(group);
		Map<String, Long> finalComposition = candidate.composition() == null
				? Map.of() : candidate.composition().stableAmounts();
		stateFunction.stateForInventory(List.of(new ThermalStateFunction.InventoryPortion(
				material, candidate.mass(), finalComposition)), candidate.internalEnergy());
		int previous = coherentPhases.find(group.cell(), group.phaseId());
		SignedDyadic oldEnergy = previous < 0 ? SignedDyadic.ZERO
				: SignedDyadic.of(coherentPhases.group(previous).internalEnergy());
		SignedDyadic source = SignedDyadic.of(group.internalEnergy());
		SignedDyadic roundoff = SignedDyadic.of(candidate.internalEnergy()).subtract(oldEnergy).subtract(source);
		ThermalAuditLedger auditAfter = audit.ledger().withExternalSourceEnergy(source).withRoundoff(roundoff);
		auditAfter.expectedInventory();
		return new CoherentExternalPlan(candidate, previous, auditAfter);
	}

	GranularInventory.EnergyPlan planEnergyReplacement(double[] active,List<GranularEnergyColumn.DepositEnergy> deposits,
	                                                  List<CoherentPhaseStore.Group> coherent) {
		var candidate=granularEnergy.prepareReplacement(active,deposits);
		var coherentCandidate=coherent==null?null:coherentPhases.prepareReplacement(coherent);
		long before=revision,auditBefore=audit.revision();
		return new GranularInventory.EnergyPlan() {
			@Override public void requireCurrent() {
				if(before!=revision||auditBefore!=audit.revision()) throw new IllegalStateException("Solid thermal owners changed after batch planning");
			}
			@Override public void commit() {
				requireCurrent();granularEnergy.commitPreparedReplacement(candidate);
				if(coherentCandidate!=null) coherentPhases.commitPreparedReplacement(coherentCandidate);
				revision++;
			}
		};
	}

	void commitCoherentReplacement(CoherentPhaseStore prepared) { coherentPhases.commitPreparedReplacement(prepared); revision++; }

	void addCoherentInternal(CoherentPhaseStore.Group group) {
		coherentPhases.add(group);
		revision++;
	}

	CoherentPhaseStore.Group takeCoherentInternal(int cell, String phaseId, long occupiedSvq,
	                                           mmm.chemistry.ChemicalRegistry chemistry) {
		CoherentPhaseStore.Group taken = coherentPhases.take(cell, phaseId, occupiedSvq, chemistry);
		revision++;
		return taken;
	}

	void clearCoherentExternal(List<CoherentPhaseStore.Group> expected, SignedDyadic expectedEnergy) {
		if (expected == null || expectedEnergy == null || !coherentPhases.snapshot().equals(expected)
				|| !coherentPhases.exactEnergy().equals(expectedEnergy)) {
			throw new IllegalStateException("Coherent phase inventory changed after clear planning");
		}
		ThermalAuditLedger auditAfter = audit.ledger().withExternalSinkEnergy(expectedEnergy);
		coherentPhases.clear();
		audit.replace(auditAfter);
		revision++;
	}

	void restoreCoherent(List<CoherentPhaseStore.Group> groups) {
		coherentPhases.restore(groups);
		revision++;
	}

	void restoreGranular(double[] activeEnergy, List<GranularEnergyColumn.DepositEnergy> deposits) {
		granularEnergy.replace(activeEnergy, deposits);
		revision++;
	}
}
