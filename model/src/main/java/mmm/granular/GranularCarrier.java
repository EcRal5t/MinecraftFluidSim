package mmm.granular;

/**
 * Drag calibration for one liquid carrier, keyed by the stable liquid material id.
 *
 * <p>The carrier binds a liquid material and the Reynolds range in which its drag correlation was
 * calibrated. Dynamic viscosity is not part of this record: it belongs to the world material
 * registry, which declares it as a temperature-dependent property.
 */
public record GranularCarrier(String liquidMaterialId, double maximumReynolds) {
	public GranularCarrier {
		GranularIds.requireId(liquidMaterialId, "liquid material id");
		if (!(Double.isFinite(maximumReynolds) && maximumReynolds > 0.0)) {
			throw new IllegalArgumentException("Carrier Reynolds limit must be finite and positive");
		}
	}
}
