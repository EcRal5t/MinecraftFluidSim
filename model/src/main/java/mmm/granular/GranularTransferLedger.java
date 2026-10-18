package mmm.granular;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runtime SVQ/kilogram transfer value object; persistence converts material/species handles to stable IDs. */
public record GranularTransferLedger(List<Group> groups) {
	public static final int MAX_GROUPS = 16;

	public GranularTransferLedger {
		if (groups == null || groups.isEmpty() || groups.size() > MAX_GROUPS) {
			throw new IllegalArgumentException("Granular transfer groups are empty or over budget");
		}
		ArrayList<Group> canonical = new ArrayList<>(groups); canonical.sort(Comparator.comparing(Group::materialId));
		Set<String> materials = new HashSet<>(); long totalSvq = 0L; long totalMass = 0;
		for (Group group : canonical) {
			if (!materials.add(group.materialId())) throw new IllegalArgumentException("Duplicate granular transfer material");
			totalSvq = Math.addExact(totalSvq, group.solidVolumeSvq()); totalMass = Math.addExact(totalMass, group.massUnits());
		}
		if (totalSvq > SolidVolume.SVQ_PER_CUBIC_METER || !(Double.isFinite(totalMass) && totalMass > 0.0)) {
			throw new IllegalArgumentException("Granular transfer exceeds one dry container");
		}
		groups = List.copyOf(canonical);
	}

	public long totalSolidVolumeSvq() {
		long result = 0L; for (Group group : groups) result = Math.addExact(result, group.solidVolumeSvq()); return result;
	}
	public long totalMassUnits() {
		long total = 0;
		for (Group value : groups) total = Math.addExact(total, value.massUnits());
		return total;
	}
	public double totalMass() { return mmm.units.Mass.kilograms(totalMassUnits()); }

	public record Group(String materialId, long solidVolumeSvq, long massUnits, SolidCompositionRecord composition) {
		public Group {
			if (solidVolumeSvq <= 0L
					|| !(massUnits > 0)) {
				throw new IllegalArgumentException("Invalid granular transfer group");
			}
			GranularIds.requireId(materialId, "granular transfer material id");
		}
		public double mass() { return mmm.units.Mass.kilograms(massUnits); }
	}
}
