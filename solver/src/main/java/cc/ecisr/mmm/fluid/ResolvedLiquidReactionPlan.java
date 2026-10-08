package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.*;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.ExactVolume;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Instantaneous reactions on complete local controls, preserving mass, residuals and stored energy. */
final class ResolvedLiquidReactionPlan {
	private record Group(int control, int phase) implements Comparable<Group> {
		public int compareTo(Group other) { int order = Integer.compare(control, other.control); return order != 0 ? order : Integer.compare(phase, other.phase); }
	}
	final CarrierMutationPlan intent;
	private final ExactVolume[] volumes; private final SignedDyadic[] masses;
	final ChemicalParcelStore.MarkerRemap chemical;
	private final int[] dirty;
	final int cursor;
	final ChemicalParcelStore.ReactionStats stats;
	private final FluidDomain owner;
	private final LiquidGeometryState geometry;
	private final FluidMaterialRegistry materials;
	private final ChemicalParcelStore store;
	private final ThermalDomainState thermal;
	private final ThermalDomainState.Revision thermalRevision;
	private final SignedDyadic[] originalMass;
	private final ChemicalMixtureRecord[] originalRecords;
	private final boolean[] originalIndicators;
	private final int[] originalMaterial, originalDirty;
	private final int originalCursor;
	private final long chemicalRevision;
	private final ChemicalTransferLedger source, sink;

	ResolvedLiquidReactionPlan(FluidDomain domain) {
		owner = domain; geometry = domain.liquidGeometryState; materials = domain.materialRegistry;
		store = domain.chemicalParcels; thermal = domain.thermalState;
		if (geometry == null || materials == null || store == null) throw new IllegalArgumentException("Resolved reaction inventory is missing");
		requireGeometry(domain);
		intent = CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), domain.particles.count());
		thermalRevision = thermal == null ? null : thermal.thermalRevision(); chemicalRevision = store.mutationRevision();
		originalDirty = store.dirtyCells(); originalCursor = store.reactionCursor(); source = store.externalSource(); sink = store.escapedSink();
		int count = intent.expectedCount(); volumes = new ExactVolume[count]; originalMass = new SignedDyadic[count]; originalMaterial = new int[count];
		var records = new ChemicalMixtureRecord[count]; var indicators = new boolean[count];
		for (int p = 0; p < count; p++) {
			volumes[p] = domain.particles.exactVolume(p); originalMass[p] = domain.particles.exactMass(p); originalMaterial[p] = domain.particles.materialHandle(p);
			records[p] = store.record(p); indicators[p] = store.indicator(p);
		}
		originalRecords = records.clone(); originalIndicators = indicators.clone();
		masses = originalMass.clone();
		var support = LiquidGeometryInventorySupport.volumesByOwner(domain); var pending = new BitSet(domain.cellCount);
		for (int c : originalDirty) pending.set(c);
		var groups = new TreeMap<Group, List<Integer>>(); int[] controls = geometry.copyMarkerCells();
		for (int p = 0; p < count; p++) {
			boolean active = false; for (int cell : support.get(controls[p]).keySet()) active |= pending.get(cell);
			if (!active) continue;
			int phase = store.registry().phaseHandleForMaterial(materials.material(originalMaterial[p]).id());
			groups.computeIfAbsent(new Group(controls[p], phase), ignored -> new ArrayList<>()).add(p);
		}
		var costs = new int[store.registry().phases().size()];
		for (var rule : store.registry().reactionOrder()) costs[store.registry().phaseHandle(rule.phaseId())]++;
		int evaluations = 0; var outstanding = new int[domain.cellCount];
		for (var group : groups.keySet()) {
			evaluations = Math.addExact(evaluations, costs[group.phase()]);
			for (int c : support.get(group.control()).keySet()) if (pending.get(c)) outstanding[c]++;
		}
		if (evaluations > ChemicalLimits.MAX_REACTION_EVALUATIONS_PER_STEP)
			throw new ChemicalReactionEngine.BudgetExceededException("Resolved reaction exhausted its complete-control evaluation budget");
		var order = new ArrayList<>(groups.keySet());
		order.sort(Comparator.comparingInt((Group group) -> Math.floorMod(support.get(group.control()).keySet().iterator().next() - originalCursor, domain.cellCount)).thenComparing(Comparator.naturalOrder()));
		var engine = new ChemicalReactionEngine(store.registry()); long total = store.totalQuanta(); int changed = 0, nextCursor = originalCursor;
		for (var group : order) {
			var markers = groups.get(group); var inventory = new long[store.registry().species().size()];
			for (int p : markers) for (int e = 0; e < records[p].entryCount(); e++)
				inventory[records[p].speciesHandle(e)] = Math.addExact(inventory[records[p].speciesHandle(e)], records[p].amountAt(e));
			nextCursor = (support.get(group.control()).keySet().iterator().next() + 1) % domain.cellCount;
			try {
				var reacted = engine.reactInventory(group.phase(), inventory).inventory();
				if (!Arrays.equals(inventory, reacted)) {
					var products = ChemicalReactionAllocation.products(store, markers, reacted); var replacements = products.mixtures();
					long nextTotal = Math.addExact(total, products.quantaDelta());
					if (nextTotal > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) throw new ChemicalReactionEngine.BudgetExceededException("Resolved reaction exceeds region quanta capacity");
					var ideal = new ArrayList<Fraction>(); var complete = SignedDyadic.ZERO; var scale = Fraction.of(domain.unitScale.cubicMetersPerNormalizedVolume());
					for (int i = 0; i < markers.size(); i++) {
						int p = markers.get(i);
						var change = replacements[i].exactSpeciesMassKilograms(store.registry()).subtract(records[p].exactSpeciesMassKilograms(store.registry()));
						var value = Fraction.of(originalMass[p]).add(new Fraction(change.numerator(), change.denominator()).divide(scale));
						if (!(value.signum() > 0 && Double.isFinite(value.rounded()) && value.rounded() > 0))
							throw new ChemicalReactionEngine.BudgetExceededException("Reaction products exceed positive carrier mass support");
						ideal.add(value); complete = complete.add(originalMass[p]);
					}
					var allocated = ExactVolumeAllocation.round(complete, ideal).values();
					for (int i = 0; i < markers.size(); i++) {
						int p = markers.get(i); store.requireCompatible(replacements[i], materials.material(originalMaterial[p]).id(), indicators[p]);
						if (thermal != null) ChemicalReactionStage.requireThermalCandidate(thermal, p, replacements[i], allocated[i], domain.thermalMarkers);
					}
					for (int i = 0; i < markers.size(); i++) { int p = markers.get(i); records[p] = replacements[i]; masses[p] = allocated[i]; }
					total = nextTotal; changed += products.changed();
				}
				for (int c : support.get(group.control()).keySet()) if (pending.get(c)) outstanding[c]--;
			} catch (ChemicalReactionEngine.BudgetExceededException unavailable) { /* The original complete group remains scheduled. */ }
		}
		for (int c : originalDirty) if (outstanding[c] == 0) pending.clear(c);
		dirty = pending.stream().toArray(); cursor = nextCursor;
		new LiquidInventoryState(volumes, masses);
		chemical = changed == 0 ? null : store.planMarkerRemap(count, records, indicators);
		stats = new ChemicalParcelStore.ReactionStats(originalDirty.length, evaluations, changed);
	}
	ChemicalParcelStore.ReactionStats publish() { owner.carrierMutations().publishGeometricReaction(this); return stats; }
	int markerCount() { return masses.length; }
	ExactVolume volume(int marker) { return volumes[marker]; }
	SignedDyadic mass(int marker) { return masses[marker]; }
	int[] copyDirtyCells() { return dirty.clone(); }
	static void requireGeometry(FluidDomain domain) {
		var geometry = domain.liquidGeometryState; LiquidGeometryStateMapper.requireCompatible(geometry, domain);
		if (!Arrays.equals(geometry.copyFragmentOwners(), geometry.copyControlCells()))
			throw new IllegalStateException("Resolved reaction requires independent inventories on every mechanical control");
	}
	void requireSource(FluidDomain domain) {
		if (domain != owner || domain.liquidGeometryState != geometry || domain.materialRegistry != materials || domain.chemicalParcels != store || domain.thermalState != thermal
				|| store.mutationRevision() != chemicalRevision || store.reactionCursor() != originalCursor || !Arrays.equals(store.dirtyCells(), originalDirty)
				|| store.externalSource() != source || store.escapedSink() != sink || thermal != null && !thermal.thermalRevision().equals(thermalRevision))
			throw new IllegalStateException("Resolved reaction candidate refers to an obsolete inventory");
		for (int p = 0; p < originalMass.length; p++) if (!domain.particles.exactMass(p).equals(originalMass[p]) || !domain.particles.exactVolume(p).equals(volumes[p]) || domain.particles.materialHandle(p) != originalMaterial[p]
				|| !store.record(p).equals(originalRecords[p]) || store.indicator(p) != originalIndicators[p])
			throw new IllegalStateException("Resolved reaction stock changed after planning");
		LiquidGeometryStateMapper.requireCompatible(geometry, domain);
	}
}
