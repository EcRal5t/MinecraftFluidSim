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

	public GranularEnergyColumn column() { return granularEnergy; }
	public CoherentPhaseStore coherentPhases() { return coherentPhases; }

	ThermalMaterialDefinition granularMaterial(int granularHandle) {
		return registry.material(materialByGranularHandle[granularHandle]);
	}

	double granularBaseEnergy(int granularHandle, double mass, Map<String, Long> composition) {
		return stateFunction.baseEnergy(materialByGranularHandle[granularHandle], mass, composition);
	}

	void addGranularReactionActive() { granularEnergy.addActive(0.0); revision++; }

	double takeGranularReactionActive(int active, long takenSvq, long totalSvq) {
		double taken = takenSvq == totalSvq ? granularEnergy.removeActiveSwap(active)
				: granularEnergy.takeActivePortion(active, takenSvq, totalSvq);
		revision++;
		return taken;
	}

	double takeGranularReactionDeposit(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		double taken = granularEnergy.takeDeposit(key, takenSvq, totalSvq);
		revision++;
		return taken;
	}

	void requireActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		granularEnergy.requireAddDeposit(target, granularEnergy.activeEnergy(active));
	}

	void commitActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		SignedDyadic before = granularEnergy.exactEnergy();
		double energy = granularEnergy.removeActiveSwap(active);
		granularEnergy.addDeposit(target, energy);
		audit.update(ledger -> ledger.withRoundoff(granularEnergy.exactEnergy().subtract(before)));
		revision++;
	}

	GranularInventory.EnergyPlan planActiveToDeposits(List<GranularInventory.DepositTransfer> transfers) {
		GranularEnergyColumn.ActiveToDepositsPlan columnPlan = granularEnergy.planActiveToDeposits(transfers);
		if (columnPlan == null) return null;
		long baseRevision = revision, auditRevision = audit.revision();
		ThermalAuditLedger auditAfter = audit.ledger().withRoundoff(columnPlan.roundoff);
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

	boolean canActiveMerge(int first, int second, int material, double mass, Map<String, Long> composition) {
		double left = granularEnergy.activeEnergy(first), right = granularEnergy.activeEnergy(second);
		double merged = left + right;
		if (!Double.isFinite(merged)) return false;
		try {
			stateFunction.stateForInventory(List.of(new ThermalStateFunction.InventoryPortion(
					materialByGranularHandle[material], mass, composition)), merged);
		} catch (ThermalStateFunction.EnergyOutsideValidityException outside) {
			return false;
		}
		SignedDyadic roundoff = SignedDyadic.of(merged).subtract(SignedDyadic.of(left)).subtract(SignedDyadic.of(right));
		audit.ledger().withRoundoff(roundoff);
		return true;
	}

	void commitActiveMerge(int first, int second) {
		SignedDyadic before = granularEnergy.exactEnergy();
		granularEnergy.mergeActive(first, second);
		audit.update(ledger -> ledger.withRoundoff(granularEnergy.exactEnergy().subtract(before)));
		revision++;
	}

	GranularInventory.EnergyPlan planDepositWake(GranularEnergyColumn.DepositKey source, long takenSvq, long totalSvq) {
		var columnPlan = granularEnergy.planDepositWake(source, takenSvq, totalSvq);
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
		audit.update(ledger -> ledger.withRoundoff(granularEnergy.exactEnergy().subtract(before)));
		revision++;
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

	double previewActiveTake(int active, long takenSvq, long totalSvq) {
		return granularEnergy.previewActiveTake(active, takenSvq, totalSvq);
	}

	double previewDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return granularEnergy.previewDepositTake(key, takenSvq, totalSvq);
	}

	double commitExternalActiveTake(int active, long takenSvq, long totalSvq) {
		double before = granularEnergy.activeEnergy(active);
		double preview = granularEnergy.previewActiveTake(active, takenSvq, totalSvq);
		double remaining = granularEnergy.previewActiveRemaining(active, takenSvq, totalSvq);
		ThermalAuditLedger auditAfter = externalTakeAudit(before, preview, remaining);
		double removed = takenSvq == totalSvq ? granularEnergy.removeActiveSwap(active)
				: granularEnergy.takeActivePortion(active, takenSvq, totalSvq);
		audit.replace(auditAfter);
		revision++;
		return removed;
	}

	double commitExternalDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		double before = granularEnergy.depositEnergy(key);
		double preview = granularEnergy.previewDepositTake(key, takenSvq, totalSvq);
		double remaining = granularEnergy.previewDepositRemaining(key, takenSvq, totalSvq);
		ThermalAuditLedger auditAfter = externalTakeAudit(before, preview, remaining);
		double removed = granularEnergy.takeDeposit(key, takenSvq, totalSvq);
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

	void addCoherentExternal(CoherentPhaseStore.Group group) {
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
		coherentPhases.add(group);
		audit.replace(auditAfter);
		revision++;
	}

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
