package com.example.granular;

/** Drag properties keyed by the stable liquid material id. */
public record GranularCarrier(String liquidMaterialId, double dynamicViscosity, double maximumReynolds) {
	public GranularCarrier {
		GranularIds.requireId(liquidMaterialId, "liquid material id");
		if (!(Double.isFinite(dynamicViscosity) && dynamicViscosity > 0.0)) {
			throw new IllegalArgumentException("Carrier viscosity must be finite and positive");
		}
		if (!(Double.isFinite(maximumReynolds) && maximumReynolds > 0.0)) {
			throw new IllegalArgumentException("Carrier Reynolds limit must be finite and positive");
		}
	}
}
