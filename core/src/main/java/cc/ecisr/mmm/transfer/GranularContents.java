package cc.ecisr.mmm.transfer;

import cc.ecisr.mmm.api.StableId;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidCompositionRecord;
import cc.ecisr.mmm.granular.SolidVolume;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Canonical dry-container truth. SVQ, raw kilogram mass and optional solid composition remain independent. */
public record GranularContents(String registryFingerprint, long svqPerBlockVolume, List<Group> groups) {
	private static final String ENCODING_TAG = "mmm:granular-contents";
	public static final int MAX_GROUPS = 16;
	public static final int MAX_TOTAL_COMPOSITION_ENTRIES = 64;
	public static final int MAX_ENCODED_LENGTH = 64 * 1024;

	public GranularContents {
		if (registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")
				|| svqPerBlockVolume != SolidVolume.SVQ_PER_BV || groups == null || groups.isEmpty()
				|| groups.size() > MAX_GROUPS) {
			throw new IllegalArgumentException("Invalid granular contents identity/groups");
		}
		ArrayList<Group> canonical = new ArrayList<>(groups);
		canonical.sort(Group.ORDER);
		Set<String> keys = new HashSet<>(); long totalSvq = 0L; double totalMass = 0.0; int entries = 0;
		for (Group group : canonical) {
			if (!keys.add(group.materialId())) {
				throw new IllegalArgumentException("Duplicate granular material group");
			}
			totalSvq = Math.addExact(totalSvq, group.solidVolumeSvq());
			totalMass += group.mass();
			entries = Math.addExact(entries, group.composition().size());
		}
		if (totalSvq > SolidVolume.SVQ_PER_BV || entries > MAX_TOTAL_COMPOSITION_ENTRIES
				|| !(Double.isFinite(totalMass) && totalMass > 0.0)) {
			throw new IllegalArgumentException("Granular contents exceed the frozen container budget");
		}
		groups = List.copyOf(canonical);
	}

	public long totalSolidVolumeSvq() {
		long result = 0L; for (Group group : groups) result = Math.addExact(result, group.solidVolumeSvq()); return result;
	}

	public double totalMass() {
		double result = 0.0; for (Group group : groups) result += group.mass();
		if (!(Double.isFinite(result) && result > 0.0)) throw new IllegalStateException("Granular contents mass overflow");
		return result;
	}

	public void validateAgainst(GranularRegistry registry, ChemicalRegistry chemistry) {
		if (registry == null || !registryFingerprint.equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Foreign granular registry fingerprint");
		}
		for (Group group : groups) {
			var material = registry.material(registry.materialHandle(group.materialId()));
			if (material.reactive() != group.reactive()) {
				throw new IllegalArgumentException("Granular material/composition kind mismatch: " + group.materialId());
			}
			if (!group.reactive()) continue;
			if (chemistry == null || !registry.chemicalRegistryFingerprint().equals(chemistry.fingerprint())) {
				throw new IllegalArgumentException("Reactive granular contents require the authoritative chemical registry");
			}
			SolidCompositionRecord composition = group.toComposition(chemistry);
			if (!massClose(composition.totalMass(chemistry), group.mass())) {
				throw new IllegalArgumentException("Granular composition/mass ledger mismatch");
			}
		}
	}

	public static GranularContents fromLedger(
			GranularTransferLedger ledger, GranularRegistry registry, ChemicalRegistry chemistry
	) {
		if (ledger == null || registry == null) throw new IllegalArgumentException("Granular ledger/registry is missing");
		ArrayList<Group> groups = new ArrayList<>(ledger.groups().size());
		for (GranularTransferLedger.Group source : ledger.groups()) {
			registry.material(registry.materialHandle(source.materialId()));
			if (source.composition() == null) groups.add(new Group(source.materialId(), source.solidVolumeSvq(), source.mass()));
			else {
				if (chemistry == null) throw new IllegalArgumentException("Reactive granular ledger requires chemistry");
				List<SpeciesAmount> species = source.composition().stableAmounts(chemistry).entrySet().stream()
						.sorted(Map.Entry.comparingByKey())
						.map(entry -> new SpeciesAmount(entry.getKey(), entry.getValue())).toList();
				groups.add(new Group(source.materialId(), source.solidVolumeSvq(), source.massBits(),
						source.composition().massResidualBits(), species));
			}
		}
		GranularContents result = new GranularContents(registry.fingerprint(), SolidVolume.SVQ_PER_BV, groups);
		result.validateAgainst(registry, chemistry); return result;
	}

	public GranularTransferLedger toLedger(GranularRegistry registry, ChemicalRegistry chemistry) {
		validateAgainst(registry, chemistry);
		ArrayList<GranularTransferLedger.Group> result = new ArrayList<>(groups.size());
		for (Group group : groups) result.add(new GranularTransferLedger.Group(group.materialId(),
				group.solidVolumeSvq(), group.massBits(), group.reactive() ? group.toComposition(chemistry) : null));
		return new GranularTransferLedger(result);
	}

	public String encode() {
		StringBuilder encoded = new StringBuilder(ENCODING_TAG).append('|').append(registryFingerprint).append('|').append(svqPerBlockVolume);
		for (Group group : groups) {
			encoded.append('|').append(group.materialId()).append(',').append(group.solidVolumeSvq()).append(',')
					.append(Long.toUnsignedString(group.massBits(), 16)).append(',');
			if (!group.reactive()) encoded.append("-,-");
			else {
				encoded.append(Long.toUnsignedString(group.massResidualBits(), 16)).append(',');
				for (int entry = 0; entry < group.composition().size(); entry++) {
					if (entry > 0) encoded.append('+');
					SpeciesAmount amount = group.composition().get(entry);
					encoded.append(amount.speciesId()).append('=').append(amount.amountQuanta());
				}
			}
		}
		if (encoded.length() > MAX_ENCODED_LENGTH) throw new IllegalArgumentException("Granular contents exceed 64 KiB");
		return encoded.toString();
	}

	public static GranularContents decode(String encoded) {
		if (encoded == null || encoded.length() > MAX_ENCODED_LENGTH) {
			throw new IllegalArgumentException("Invalid granular contents size");
		}
		String[] parts = encoded.split("\\|", -1);
		if (parts.length < 4 || !parts[0].equals(ENCODING_TAG)) throw new IllegalArgumentException("Unsupported granular contents");
		long scale;
		try { scale = Long.parseLong(parts[2]); }
		catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid granular SVQ scale", invalid); }
		ArrayList<Group> groups = new ArrayList<>(parts.length - 3);
		for (int index = 3; index < parts.length; index++) {
			String[] fields = parts[index].split(",", -1);
			if (fields.length != 5) throw new IllegalArgumentException("Malformed granular contents group");
			try {
				ArrayList<SpeciesAmount> composition = new ArrayList<>();
				long residualBits;
				if (fields[3].equals("-") && fields[4].equals("-")) residualBits = 0L;
				else {
					if (fields[3].equals("-") || fields[4].isEmpty() || fields[4].equals("-")) {
						throw new IllegalArgumentException("Malformed reactive granular composition");
					}
					residualBits = Long.parseUnsignedLong(fields[3], 16);
					for (String entry : fields[4].split("\\+", -1)) {
						String[] pair = entry.split("=", -1);
						if (pair.length != 2) throw new IllegalArgumentException("Malformed granular species entry");
						composition.add(new SpeciesAmount(pair[0], Long.parseLong(pair[1])));
					}
				}
				groups.add(new Group(fields[0], Long.parseLong(fields[1]),
						Long.parseUnsignedLong(fields[2], 16), residualBits, composition));
			} catch (NumberFormatException invalid) {
				throw new IllegalArgumentException("Malformed granular contents number", invalid);
			}
		}
		GranularContents contents = new GranularContents(parts[1], scale, groups);
		if (!contents.encode().equals(encoded)) throw new IllegalArgumentException("Granular contents are not canonical");
		return contents;
	}

	private static boolean massClose(double left, double right) {
		return Math.abs(left - right) <= Math.max(1.0E-9, Math.max(Math.ulp(left), Math.ulp(right)) * 8.0);
	}

	public record Group(
			String materialId, long solidVolumeSvq, long massBits, long massResidualBits,
			List<SpeciesAmount> composition
	) {
		private static final Comparator<Group> ORDER = Comparator.comparing(Group::materialId);

		public Group {
			if (!StableId.isValid(materialId, 128) || solidVolumeSvq <= 0L
					|| solidVolumeSvq > SolidVolume.SVQ_PER_BV
					|| !(Double.isFinite(Double.longBitsToDouble(massBits)) && Double.longBitsToDouble(massBits) > 0.0)
					|| composition == null || composition.size() > GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES) {
				throw new IllegalArgumentException("Invalid granular contents group");
			}
			ArrayList<SpeciesAmount> canonical = new ArrayList<>(composition);
			canonical.sort(Comparator.comparing(SpeciesAmount::speciesId));
			if (!canonical.equals(composition)) throw new IllegalArgumentException("Granular composition is not canonical");
			Set<String> ids = new HashSet<>(); for (SpeciesAmount amount : canonical) if (!ids.add(amount.speciesId())) {
				throw new IllegalArgumentException("Duplicate granular composition species");
			}
			if (canonical.isEmpty() && massResidualBits != 0L
					|| !canonical.isEmpty() && !Double.isFinite(Double.longBitsToDouble(massResidualBits))) {
				throw new IllegalArgumentException("Invalid granular composition residual");
			}
			composition = List.copyOf(canonical);
		}

		public Group(String materialId, long solidVolumeSvq, double mass) {
			this(materialId, solidVolumeSvq, Double.doubleToRawLongBits(mass), 0L, List.of());
		}

		public double mass() { return Double.longBitsToDouble(massBits); }
		public boolean reactive() { return !composition.isEmpty(); }
		String compositionKey() {
			if (!reactive()) return "-";
			StringBuilder result = new StringBuilder(Long.toUnsignedString(massResidualBits, 16));
			for (SpeciesAmount amount : composition) result.append('|').append(amount.speciesId()).append('=').append(amount.amountQuanta());
			return result.toString();
		}
		public SolidCompositionRecord toComposition(ChemicalRegistry chemistry) {
			if (!reactive()) throw new IllegalStateException("Inert granular group has no solid composition");
			TreeMap<String, Long> amounts = new TreeMap<>();
			for (SpeciesAmount amount : composition) amounts.put(amount.speciesId(), amount.amountQuanta());
			return SolidCompositionRecord.fromStableAmounts(chemistry, amounts,
					Double.longBitsToDouble(massResidualBits));
		}
	}

	public record SpeciesAmount(String speciesId, long amountQuanta) {
		public SpeciesAmount {
			if (!StableId.isValid(speciesId, 128) || amountQuanta <= 0L) {
				throw new IllegalArgumentException("Invalid granular composition amount");
			}
		}
	}
}
