package cc.ecisr.mmm.granular;

/** Drag properties keyed by the stable liquid material id. */
public record GranularCarrier(String liquidMaterialId, double maximumReynolds) {
	public GranularCarrier {
		GranularIds.requireId(liquidMaterialId, "liquid material id");
		if (!(Double.isFinite(maximumReynolds) && maximumReynolds > 0.0)) {
			throw new IllegalArgumentException("Carrier Reynolds limit must be finite and positive");
		}
	}
}
