package com.example.granular;

import com.example.chemistry.ChemicalRegistry;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
			if (Identifier.tryParse(materialId) == null || solidVolumeSvq <= 0L
					|| !(Double.isFinite(Double.longBitsToDouble(massBits)) && Double.longBitsToDouble(massBits) > 0.0)
					|| composition == null || composition.size() > GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES) {
				throw new IllegalArgumentException("Invalid granular audit entry");
			}
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
			if (Identifier.tryParse(speciesId) == null || amountQuanta <= 0L) {
				throw new IllegalArgumentException("Invalid granular audit species amount");
			}
		}
	}

	private static final class MutableEntry {
		private long svq; private double mass, residual; private TreeMap<String, Long> species;
		MutableEntry() { }
		MutableEntry(Entry source) {
			svq = source.solidVolumeSvq(); mass = source.mass(); residual = Double.longBitsToDouble(source.massResidualBits());
			if (source.reactive()) {
				species = new TreeMap<>(); for (SpeciesAmount amount : source.composition()) species.put(amount.speciesId(), amount.amountQuanta());
			}
		}
		void add(GranularTransferLedger.Group group, ChemicalRegistry chemistry) {
			svq = Math.addExact(svq, group.solidVolumeSvq()); mass += group.mass();
			if (group.composition() == null) {
				if (species != null) throw new IllegalArgumentException("Mixed inert/reactive granular audit material");
				return;
			}
			if (species == null) species = new TreeMap<>(); residual += group.composition().massResidual();
			for (Map.Entry<String, Long> amount : group.composition().stableAmounts(chemistry).entrySet()) {
				species.merge(amount.getKey(), amount.getValue(), Math::addExact);
			}
		}
		Entry build(String materialId) {
			List<SpeciesAmount> composition = species == null ? List.of() : species.entrySet().stream()
					.map(entry -> new SpeciesAmount(entry.getKey(), entry.getValue())).toList();
			return new Entry(materialId, svq, Double.doubleToRawLongBits(mass),
					species == null ? 0L : Double.doubleToRawLongBits(residual), composition);
		}
	}

	private static boolean massClose(double left, double right) {
		return Math.abs(left - right) <= Math.max(1.0E-9, Math.max(Math.ulp(left), Math.ulp(right)) * 8.0);
	}
}
