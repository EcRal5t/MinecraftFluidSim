package cc.ecisr.mmm.thermal;

/** World-snapshotted selector for an adiabatic wall or an infinite fixed-temperature reservoir. */
public record ThermalBoundaryDefinition(
		String id,
		TargetKind targetKind,
		String targetId,
		Kind kind,
		double surfaceTemperatureK
) {
	public enum TargetKind { BLOCK, BLOCK_TAG }
	public enum Kind { ADIABATIC, FIXED_SURFACE_TEMPERATURE }

	public ThermalBoundaryDefinition {
		ThermalIds.requireId(id, "thermal boundary id");
		if (targetKind == null || kind == null) throw new IllegalArgumentException("Thermal boundary kind is missing");
		ThermalIds.requireId(targetId, "thermal boundary target id");
		if (!Double.isFinite(surfaceTemperatureK)) {
			throw new IllegalArgumentException("Thermal boundary temperature must be finite");
		}
		if (kind == Kind.ADIABATIC && Double.doubleToRawLongBits(surfaceTemperatureK) != Double.doubleToRawLongBits(0.0)
				|| kind == Kind.FIXED_SURFACE_TEMPERATURE
				&& surfaceTemperatureK < ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K) {
			throw new IllegalArgumentException("Thermal boundary temperature disagrees with its kind");
		}
	}
}
