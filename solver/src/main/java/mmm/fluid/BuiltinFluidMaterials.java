package mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Stable built-in material definitions used by solver, snapshots and persistence. */
public final class BuiltinFluidMaterials {
	public static final FluidMaterial WATER = new FluidMaterial("mmm:water", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0);
	public static final FluidMaterial OIL = new FluidMaterial("mmm:oil", 800.0, "mmm:oil", 0.0, Math.PI / 2.0);
	public static final FluidMaterial MERCURY = new FluidMaterial("mmm:mercury", 13_600.0, "mmm:mercury", 0.0, Math.PI / 2.0);
	public static final List<FluidMaterial> DEFAULT_PALETTE = List.of(WATER, OIL, MERCURY);

	private static final double[][] WATER_VISCOSITY_POINTS = {
			{273.15, 1.788e-3}, {293.15, 1.002e-3}, {313.15, 6.53e-4}, {333.15, 4.66e-4},
			{353.15, 3.54e-4}, {373.15, 2.82e-4}, {423.15, 1.86e-4}, {473.15, 1.36e-4},
			{523.15, 1.07e-4}, {573.15, 8.60e-5}, {623.15, 6.70e-5}, {645.0, 5.00e-5}};
	private static final double[][] OIL_VISCOSITY_POINTS = {
			{253.15, 0.180}, {273.15, 0.112}, {293.15, 0.055}, {313.15, 0.028},
			{333.15, 0.016}, {353.15, 0.010}, {373.15, 6.0e-3}, {423.15, 3.0e-3}, {473.15, 1.8e-3}};
	private static final double[][] MERCURY_VISCOSITY_POINTS = {
			{234.32, 2.40e-3}, {273.15, 1.69e-3}, {293.15, 1.526e-3}, {323.15, 1.370e-3},
			{373.15, 1.245e-3}, {473.15, 1.08e-3}, {630.0, 9.00e-4}};

	private BuiltinFluidMaterials() { }

	/** Canonical frozen default registry: the palette plus declared Newtonian liquid viscosities; no pair tensions yet. */
	public static MaterialRegistry createRegistry() {
		return new MaterialRegistry(List.of(
				new MaterialRegistry.MaterialEntry(WATER, viscositySegments(WATER_VISCOSITY_POINTS)),
				new MaterialRegistry.MaterialEntry(OIL, viscositySegments(OIL_VISCOSITY_POINTS)),
				new MaterialRegistry.MaterialEntry(MERCURY, viscositySegments(MERCURY_VISCOSITY_POINTS))), List.of());
	}

	private static List<MaterialRegistry.ViscositySegment> viscositySegments(double[][] points) {
		ArrayList<MaterialRegistry.ViscositySegment> segments = new ArrayList<>(points.length - 1);
		for (int index = 0; index < points.length - 1; index++) {
			segments.add(new MaterialRegistry.ViscositySegment(points[index][0], points[index + 1][0],
					points[index][1], points[index + 1][1]));
		}
		return segments;
	}

	/** Display-only palette metadata; it never participates in marker inventory or solver coefficients. */
	public static int displayArgb(String materialId) {
		return switch (materialId) {
			case "mmm:water" -> 0xff197ae8;
			case "mmm:oil" -> 0xffc89a32;
			case "mmm:mercury" -> 0xffaeb6c2;
			default -> 0xffff00ff;
		};
	}
}
