package mmm.fluid;

/** A screen attached to one shared MAC face. Lengths are in metres. */
public record ScreenFace(int face, double poreDiameter, double openFraction) {
	public ScreenFace {
		if (face < 0 || !Double.isFinite(poreDiameter) || poreDiameter <= 0
				|| !Double.isFinite(openFraction) || openFraction <= 0 || openFraction > 1)
			throw new IllegalArgumentException("Invalid screen face");
	}
}
