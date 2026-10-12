package mmm.granular;

import mmm.chemistry.ChemicalRegistry;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/** Cumulative stable-ID audit for solid inventory crossing a region boundary. */
public record GranularAuditLedger(List<Entry> entries) {
	public static final GranularAuditLedger EMPTY = new GranularAuditLedger(List.of());

	public GranularAuditLedger {
		if (entries == null || entries.size() > GranularLimits.MAX_MATERIALS) {
			throw new IllegalArgumentException("Granular audit entries are missing or over budget");
		}
		ArrayList<Entry> canonical = new ArrayList<>(entries); canonical.sort(Comparator.comparing(Entry::materialId));
		Set<String> materials = new HashSet<>(); long totalSvq = 0L; double totalMass = 0.0;
		for (Entry entry : canonical) {
			if (!materials.add(entry.materialId())) throw new IllegalArgumentException("Duplicate granular audit material");
			totalSvq = Math.addExact(totalSvq, entry.solidVolumeSvq()); totalMass += entry.mass();
		}
		if (!canonical.isEmpty() && !(Double.isFinite(totalMass) && totalMass > 0.0)) {
			throw new IllegalArgumentException("Granular audit total mass overflow");
		}
		entries = List.copyOf(canonical);
	}

	public boolean isEmpty() { return entries.isEmpty(); }
	public boolean requiresChemistry() { return entries.stream().anyMatch(Entry::reactive); }

	public GranularAuditLedger plus(
			GranularTransferLedger addition, GranularRegistry registry, ChemicalRegistry chemistry
	) {
		if (addition == null || registry == null) throw new IllegalArgumentException("Granular audit addition/registry is missing");
		TreeMap<String, MutableEntry> result = new TreeMap<>();
		for (Entry entry : entries) result.put(entry.materialId(), new MutableEntry(entry));
		for (GranularTransferLedger.Group group : addition.groups()) {
			var material = registry.material(registry.materialHandle(group.materialId()));
			if (material.reactive() != (group.composition() != null)) {
				throw new IllegalArgumentException("Granular audit material/composition kind mismatch");
			}
			if (group.composition() != null && (chemistry == null
					|| !registry.chemicalRegistryFingerprint().equals(chemistry.fingerprint())
					|| !massClose(group.composition().totalMass(chemistry), group.mass()))) {
				throw new IllegalArgumentException("Reactive granular audit identity/mass mismatch");
			}
			result.computeIfAbsent(group.materialId(), ignored -> new MutableEntry()).add(group, chemistry);
		}
		return new GranularAuditLedger(result.entrySet().stream().map(entry -> entry.getValue().build(entry.getKey())).toList());
	}

	/** Adds another already-stable audit ledger without applying the one-container transfer limit. */
	public GranularAuditLedger plus(GranularAuditLedger addition) {
		if (addition == null) throw new IllegalArgumentException("Granular audit addition is missing");
		TreeMap<String, MutableEntry> result = new TreeMap<>();
		for (Entry entry : entries) result.put(entry.materialId(), new MutableEntry(entry));
		for (Entry entry : addition.entries) {
			result.computeIfAbsent(entry.materialId(), ignored -> new MutableEntry()).add(entry);
		}
		return new GranularAuditLedger(result.entrySet().stream().map(entry -> entry.getValue().build(entry.getKey())).toList());
	}

	/** Captures a complete active+deposit runtime inventory for an operator sink or region-boundary escape. */
	public static GranularAuditLedger captureInventory(
			GranularRegistry registry, GranularParcelStore parcels,
			GranularDepositStore deposits, SolidCompositionPool pool
	) {
		if (registry == null || parcels == null || deposits == null) {
			throw new IllegalArgumentException("Granular audit inventory is missing");
		}
		ChemicalRegistry chemistry = pool == null ? null : pool.registry();
		TreeMap<String, MutableEntry> result = new TreeMap<>();
		for (int index = 0; index < parcels.count(); index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			ParticulateMaterial material = registry.material(parcel.materialHandle());
			SolidCompositionRecord composition = parcel.solidCompositionHandle() == 0 ? null
					: requireComposition(pool, parcel.solidCompositionHandle());
			if (material.reactive() != (composition != null)) {
				throw new IllegalStateException("Granular audit inventory material/composition kind mismatch");
			}
			result.computeIfAbsent(material.id(), ignored -> new MutableEntry()).add(
					parcel.solidVolumeSvq(), parcel.mass(), composition, chemistry);
		}
		deposits.forEachGroup((cell, porosity, materialHandle, compositionHandle, solidVolumeSvq, mass) -> {
			ParticulateMaterial material = registry.material(materialHandle);
			SolidCompositionRecord composition = compositionHandle == 0 ? null
					: requireComposition(pool, compositionHandle);
			if (material.reactive() != (composition != null)) {
				throw new IllegalStateException("Granular audit inventory material/composition kind mismatch");
			}
			result.computeIfAbsent(material.id(), ignored -> new MutableEntry()).add(
					solidVolumeSvq, mass, composition, chemistry);
		});
		return new GranularAuditLedger(result.entrySet().stream().map(entry -> entry.getValue().build(entry.getKey())).toList());
	}

	/** Captures a planned subset without applying container group limits or mutating runtime ownership. */
	public static GranularAuditLedger captureSelected(
			GranularRegistry registry, GranularParcelStore parcels, IntPredicate selectedParcel,
			GranularDepositStore deposits, IntPredicate selectedDepositCell, SolidCompositionPool pool
	) {
		if (registry == null || parcels == null || selectedParcel == null || deposits == null
				|| selectedDepositCell == null) throw new IllegalArgumentException("Granular audit selection is incomplete");
		ChemicalRegistry chemistry = pool == null ? null : pool.registry();
		TreeMap<String, MutableEntry> result = new TreeMap<>();
		for (int index = 0; index < parcels.count(); index++) if (selectedParcel.test(index)) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			ParticulateMaterial material = registry.material(parcel.materialHandle());
			SolidCompositionRecord composition = parcel.solidCompositionHandle() == 0 ? null
					: requireComposition(pool, parcel.solidCompositionHandle());
			if (material.reactive() != (composition != null)) {
				throw new IllegalStateException("Granular audit selection material/composition kind mismatch");
			}
			result.computeIfAbsent(material.id(), ignored -> new MutableEntry()).add(
					parcel.solidVolumeSvq(), parcel.mass(), composition, chemistry);
		}
		deposits.forEachGroup((cell, porosity, materialHandle, compositionHandle, solidVolumeSvq, mass) -> {
			if (!selectedDepositCell.test(cell)) return;
			ParticulateMaterial material = registry.material(materialHandle);
			SolidCompositionRecord composition = compositionHandle == 0 ? null
					: requireComposition(pool, compositionHandle);
			if (material.reactive() != (composition != null)) {
				throw new IllegalStateException("Granular audit selection material/composition kind mismatch");
			}
			result.computeIfAbsent(material.id(), ignored -> new MutableEntry()).add(
					solidVolumeSvq, mass, composition, chemistry);
		});
		return new GranularAuditLedger(result.entrySet().stream()
				.map(entry -> entry.getValue().build(entry.getKey())).toList());
	}

	private static SolidCompositionRecord requireComposition(SolidCompositionPool pool, int handle) {
		if (pool == null || !pool.isLive(handle)) {
			throw new IllegalStateException("Granular audit inventory has no live solid composition");
		}
		return pool.record(handle);
	}

	public void validateAgainst(GranularRegistry registry, ChemicalRegistry chemistry) {
		if (registry == null) throw new IllegalArgumentException("Granular audit registry is missing");
		for (Entry entry : entries) {
			var material = registry.material(registry.materialHandle(entry.materialId()));
			if (material.reactive() != entry.reactive()) throw new IllegalArgumentException("Granular audit material kind mismatch");
			if (!entry.reactive()) continue;
			if (chemistry == null || !registry.chemicalRegistryFingerprint().equals(chemistry.fingerprint())) {
				throw new IllegalArgumentException("Reactive granular audit requires authoritative chemistry");
			}
			SolidCompositionRecord composition = entry.toComposition(chemistry);
			if (!massClose(composition.totalMass(chemistry), entry.mass())) {
				throw new IllegalArgumentException("Granular audit composition/mass mismatch");
			}
		}
	}

	public record Entry(
			String materialId, long solidVolumeSvq, long massBits, long massResidualBits,
			List<SpeciesAmount> composition
	) {
		public Entry {
			if (solidVolumeSvq <= 0L
					|| !(Double.isFinite(Double.longBitsToDouble(massBits)) && Double.longBitsToDouble(massBits) > 0.0)
					|| composition == null || composition.size() > GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES) {
				throw new IllegalArgumentException("Invalid granular audit entry");
			}
			GranularIds.requireId(materialId, "granular audit material id");
			ArrayList<SpeciesAmount> canonical = new ArrayList<>(composition);
			canonical.sort(Comparator.comparing(SpeciesAmount::speciesId));
			if (!canonical.equals(composition)) throw new IllegalArgumentException("Granular audit composition is not canonical");
			Set<String> species = new HashSet<>(); for (SpeciesAmount amount : canonical) if (!species.add(amount.speciesId())) {
				throw new IllegalArgumentException("Duplicate granular audit species");
			}
			if (canonical.isEmpty() && massResidualBits != 0L
					|| !canonical.isEmpty() && !Double.isFinite(Double.longBitsToDouble(massResidualBits))) {
				throw new IllegalArgumentException("Invalid granular audit residual");
			}
			composition = List.copyOf(canonical);
		}
		public double mass() { return Double.longBitsToDouble(massBits); }
		public boolean reactive() { return !composition.isEmpty(); }
		public SolidCompositionRecord toComposition(ChemicalRegistry chemistry) {
			TreeMap<String, Long> amounts = new TreeMap<>();
			for (SpeciesAmount amount : composition) amounts.put(amount.speciesId(), amount.amountQuanta());
			return SolidCompositionRecord.fromStableAmounts(chemistry, amounts,
					Double.longBitsToDouble(massResidualBits));
		}
	}

	public record SpeciesAmount(String speciesId, long amountQuanta) {
		public SpeciesAmount {
			if (amountQuanta <= 0L) {
				throw new IllegalArgumentException("Invalid granular audit species amount");
			}
			GranularIds.requireId(speciesId, "granular audit species id");
		}
	}

	private static final class MutableEntry {
		private long svq;
		private SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
		private Boolean reactive;
		private TreeMap<String, Long> species;
		MutableEntry() { }
		MutableEntry(Entry source) {
			svq = source.solidVolumeSvq();
			mass = SignedDyadic.of(source.mass());
			residual = SignedDyadic.of(Double.longBitsToDouble(source.massResidualBits()));
			reactive = source.reactive();
			if (source.reactive()) {
				species = new TreeMap<>(); for (SpeciesAmount amount : source.composition()) species.put(amount.speciesId(), amount.amountQuanta());
			}
		}
		void add(GranularTransferLedger.Group group, ChemicalRegistry chemistry) {
			add(group.solidVolumeSvq(), group.mass(), group.composition(), chemistry);
		}
		void add(long addedSvq, double addedMass, SolidCompositionRecord composition, ChemicalRegistry chemistry) {
			if (addedSvq <= 0L || !(Double.isFinite(addedMass) && addedMass > 0.0)) {
				throw new IllegalArgumentException("Granular audit inventory portion is invalid");
			}
			requireKind(composition != null);
			svq = Math.addExact(svq, addedSvq); mass = mass.add(SignedDyadic.of(addedMass));
			if (composition == null) {
				if (species != null) throw new IllegalArgumentException("Mixed inert/reactive granular audit material");
				return;
			}
			if (chemistry == null || !massClose(composition.totalMass(chemistry), addedMass)) {
				throw new IllegalArgumentException("Reactive granular audit inventory mass mismatch");
			}
			if (species == null) species = new TreeMap<>();
			residual = residual.add(SignedDyadic.of(composition.massResidual()));
			for (Map.Entry<String, Long> amount : composition.stableAmounts(chemistry).entrySet()) {
				species.merge(amount.getKey(), amount.getValue(), Math::addExact);
			}
		}
		void add(Entry entry) {
			requireKind(entry.reactive());
			svq = Math.addExact(svq, entry.solidVolumeSvq());
			mass = mass.add(SignedDyadic.of(entry.mass()));
			if (!entry.reactive()) {
				if (species != null) throw new IllegalArgumentException("Mixed inert/reactive granular audit material");
				return;
			}
			if (species == null) species = new TreeMap<>();
			residual = residual.add(SignedDyadic.of(Double.longBitsToDouble(entry.massResidualBits())));
			for (SpeciesAmount amount : entry.composition()) {
				species.merge(amount.speciesId(), amount.amountQuanta(), Math::addExact);
			}
		}
		private void requireKind(boolean incomingReactive) {
			if (reactive != null && reactive != incomingReactive) {
				throw new IllegalArgumentException("Mixed inert/reactive granular audit material");
			}
			reactive = incomingReactive;
		}
		Entry build(String materialId) {
			List<SpeciesAmount> composition = species == null ? List.of() : species.entrySet().stream()
					.map(entry -> new SpeciesAmount(entry.getKey(), entry.getValue())).toList();
			return new Entry(materialId, svq, Double.doubleToRawLongBits(mass.toDouble()),
					species == null ? 0L : Double.doubleToRawLongBits(residual.toDouble()), composition);
		}
	}

	private static boolean massClose(double left, double right) {
		return Math.abs(left - right) <= Math.max(1.0E-9, Math.max(Math.ulp(left), Math.ulp(right)) * 8.0);
	}
}
