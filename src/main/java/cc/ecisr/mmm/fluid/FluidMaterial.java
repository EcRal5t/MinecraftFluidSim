package cc.ecisr.mmm.fluid;

import java.util.regex.Pattern;

/**
 * Immutable material definition. It is not a network or persistence format; surface fields come from the
 * authoritative runtime definition set.
 */
public record FluidMaterial(
		String id,
		double referenceDensity,
		String miscibilityGroup,
		double surfaceTension,
		double contactAngleRadians
) {
	private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
	private static final double HALF_PI = Math.PI / 2.0;

	public FluidMaterial {
		requireKey(id, "material id");
		requireKey(miscibilityGroup, "miscibility group");
		if (!(Double.isFinite(referenceDensity) && referenceDensity > 0.0)) {
			throw new IllegalArgumentException("Material reference density must be finite and positive");
		}
		if (!(Double.isFinite(surfaceTension) && surfaceTension >= 0.0)) {
			throw new IllegalArgumentException("Material surface tension must be finite and non-negative");
		}
		if (!(Double.isFinite(contactAngleRadians) && contactAngleRadians > 0.0 && contactAngleRadians < Math.PI)) {
			throw new IllegalArgumentException("Material contact angle must be within (0, PI) radians");
		}
	}

	/**
	 * Data-contract constructor with surface tension and contact angle. {@code contactAngleDegrees} is the Young
	 * equilibrium angle against a solid, in degrees; it is stored in radians. Zero surface tension is the
	 * degeneration, and a 90-degree angle is the neutral no-curvature contact condition.
	 */
	public static FluidMaterial withSurfaceTension(String id, double referenceDensity, String miscibilityGroup,
	                                               double surfaceTension, double contactAngleDegrees) {
		return new FluidMaterial(id, referenceDensity, miscibilityGroup, surfaceTension, Math.toRadians(contactAngleDegrees));
	}

	private static void requireKey(String value, String label) {
		if (value == null || !KEY.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		}
	}
}
