package com.example.fluid;

import java.util.regex.Pattern;

/** Minimal immutable M3 material definition. It is not a network or persistence format. */
public record FluidMaterial(String id, double referenceDensity, String miscibilityGroup) {
	private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

	public FluidMaterial {
		requireKey(id, "material id");
		requireKey(miscibilityGroup, "miscibility group");
		if (!(Double.isFinite(referenceDensity) && referenceDensity > 0.0)) {
			throw new IllegalArgumentException("Material reference density must be finite and positive");
		}
	}

	private static void requireKey(String value, String label) {
		if (value == null || !KEY.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		}
	}
}
