package com.example.granular;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runtime transfer value object; persistence converts material/species handles to stable IDs. */
public record GranularTransferLedger(List<Group> groups) {
	public static final int MAX_GROUPS = 16;

	public GranularTransferLedger {
		if (groups == null || groups.isEmpty() || groups.size() > MAX_GROUPS) {
			throw new IllegalArgumentException("Granular transfer groups are empty or over budget");
		}
		ArrayList<Group> canonical = new ArrayList<>(groups); canonical.sort(Comparator.comparing(Group::materialId));
		Set<String> materials = new HashSet<>(); long totalSvq = 0L; double totalMass = 0.0;
		for (Group group : canonical) {
			if (!materials.add(group.materialId())) throw new IllegalArgumentException("Duplicate granular transfer material");
			totalSvq = Math.addExact(totalSvq, group.solidVolumeSvq()); totalMass += group.mass();
		}
		if (totalSvq > SolidVolume.SVQ_PER_BV || !(Double.isFinite(totalMass) && totalMass > 0.0)) {
			throw new IllegalArgumentException("Granular transfer exceeds one dry container");
		}
		groups = List.copyOf(canonical);
	}

	public long totalSolidVolumeSvq() {
		long result = 0L; for (Group group : groups) result = Math.addExact(result, group.solidVolumeSvq()); return result;
	}
	public double totalMass() {
		double result = 0.0; for (Group group : groups) result += group.mass(); return result;
	}

	public record Group(String materialId, long solidVolumeSvq, long massBits, SolidCompositionRecord composition) {
		public Group(String materialId, long solidVolumeSvq, double mass, SolidCompositionRecord composition) {
			this(materialId, solidVolumeSvq, Double.doubleToRawLongBits(mass), composition);
		}
		public Group {
			if (Identifier.tryParse(materialId) == null || solidVolumeSvq <= 0L
					|| !(Double.isFinite(Double.longBitsToDouble(massBits)) && Double.longBitsToDouble(massBits) > 0.0)) {
				throw new IllegalArgumentException("Invalid granular transfer group");
			}
		}
		public double mass() { return Double.longBitsToDouble(massBits); }
	}
}
